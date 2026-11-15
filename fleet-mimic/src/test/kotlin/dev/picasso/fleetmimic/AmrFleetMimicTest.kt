package dev.picasso.fleetmimic

import dev.picasso.middleware.ExecutionLookup
import dev.picasso.middleware.TransportHandle
import dev.picasso.middleware.TransportOrder
import dev.picasso.middleware.TransportState
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 출하된 플릿 미믹의 약속(§15.326) — 세계 채우기와 관측, 수동 걸음, 시계 구동, 모르는 핸들, 여러 스레드.
 *
 * picasso 의 플릿 위임 시험(`DeliverContainerTest` 등)이 미들웨어와 엮어 보는 것과 겹치지 않게, 여기서는 미믹을 혼자 본다.
 */
class AmrFleetMimicTest {

    private var clock = Instant.parse("2026-11-15T00:00:00Z")
    private fun order(ref: String = "ref-1") = TransportOrder(ref, CONTAINER, SOURCE, DEST)

    @Test
    fun `세계를 채우고 보는 것이 사본이다`() {
        val fleet = AmrFleetMimic(now = { clock })
        val at = clock.minusSeconds(5)
        fleet.place(SOURCE, CONTAINER, at)
        fleet.block(DEST)

        val seen = fleet.containersAt.toMap()
        val window = fleet.containersAt
        assertEquals(mapOf(SOURCE to CONTAINER), seen)
        assertEquals(CONTAINER, fleet.containerAt(SOURCE))
        assertEquals(at, fleet.placedAtOf(SOURCE))
        assertEquals(setOf(DEST), fleet.blocked)

        fleet.clear(SOURCE)
        fleet.unblock(DEST)
        assertEquals(mapOf(SOURCE to CONTAINER), seen, "읽어 간 맵이 세계의 뒤를 따라 바뀌었다 — 내부 맵이 샜다")
        assertNull(fleet.containerAt(SOURCE))
        assertNull(fleet.placedAtOf(SOURCE))
        assertEquals(emptySet(), fleet.blocked)
        assertEquals(emptyMap(), window.toMap(), "창이 지금의 세계를 안 비춘다")

        // 옛 쓰기 표기는 놓인 시각을 건드리지 않는다.
        fleet.place(DEST, CONTAINER, at)
        fleet.containersAt[DEST] = OTHER
        assertEquals(OTHER, fleet.containerAt(DEST))
        assertEquals(at, fleet.placedAtOf(DEST))
        fleet.clear(DEST)

        // 시각 없이 두면 이전 시각을 남기지 않는다.
        fleet.place(SOURCE, CONTAINER, at)
        fleet.place(SOURCE, OTHER)
        assertEquals(OTHER, fleet.containerAt(SOURCE))
        assertNull(fleet.placedAtOf(SOURCE))
    }

    @Test
    fun `수동 걸음으로 인계까지 가고 같은 참조는 같은 운반이다`() {
        val fleet = AmrFleetMimic(now = { clock })
        fleet.place(SOURCE, CONTAINER)
        val handle = fleet.dispatch(order())!!
        assertEquals(handle, fleet.dispatch(order()), "같은 참조가 새 운반이 됐다")
        assertEquals(handle, fleet.find("ref-1"))
        assertNull(fleet.find("ref-없음"), "찾기가 새로 맡았다")
        assertEquals(1, fleet.dispatches)
        assertEquals(ExecutionLookup.CLIENT_REFERENCE, fleet.executionLookup)
        assertEquals(order(), fleet.orderOf(handle))

        clock = clock.plusSeconds(60)
        assertEquals(TransportState.ACCEPTED, fleet.status(handle).state, "수동인데 시계가 걸음을 냈다")
        val seen = mutableListOf<TransportState>()
        repeat(3) { fleet.tick(); seen += fleet.status(handle).state }
        assertEquals(listOf(TransportState.PICKED_UP, TransportState.IN_TRANSIT, TransportState.DELIVERED), seen)
        assertEquals(CONTAINER, fleet.containerAt(DEST))
        assertEquals(clock, fleet.placedAtOf(DEST))
        assertFalse(fleet.status(handle).holding)
        assertFalse(fleet.cancel(handle), "인계한 운반을 거뒀다")
    }

    @Test
    fun `출발지가 다르면 인수하지 않고 취소는 실은 것을 출발지로 되돌린다`() {
        val fleet = AmrFleetMimic(now = { clock })
        fleet.place(SOURCE, OTHER)
        val rejected = fleet.dispatch(order("ref-x"))!!
        assertEquals(TransportState.REJECTED_AT_SOURCE, fleet.status(rejected).state)
        assertEquals(OTHER, fleet.status(rejected).observedContainer)
        assertFalse(fleet.cancel(rejected))

        fleet.place(SOURCE, CONTAINER)
        val handle = fleet.dispatch(order("ref-y"))!!
        fleet.tick()
        assertTrue(fleet.status(handle).holding)
        assertNull(fleet.containerAt(SOURCE))
        assertTrue(fleet.cancel(handle))
        assertTrue(fleet.cancel(handle), "다시 거두기가 멱등이 아니다")
        assertEquals(CONTAINER, fleet.containerAt(SOURCE))
        assertEquals(TransportState.CANCELLED, fleet.status(handle).state)
    }

    @Test
    fun `모르는 핸들에 던지지 않고 정의된 결과를 준다`() {
        val fleet = AmrFleetMimic(now = { clock })
        val unknown = TransportHandle("tr-없음")
        val status = fleet.status(unknown)
        assertEquals(TransportState.FAILED, status.state)
        assertFalse(status.holding)
        assertNull(status.observedContainer)
        assertTrue("모르는 운반" in status.detail.orEmpty(), status.detail)
        assertFalse(fleet.cancel(unknown))
        assertNull(fleet.orderOf(unknown))
    }

    @Test
    fun `시계 구동은 지난 시간만큼 걸음을 따라잡고 놓인 시각은 걸음의 시각이다`() {
        val every = Duration.ofSeconds(1)
        val fleet = AmrFleetMimic(now = { clock }, stepEvery = every)
        val start = clock
        fleet.place(SOURCE, CONTAINER)
        val handle = fleet.dispatch(order())!!
        assertEquals(TransportState.ACCEPTED, fleet.status(handle).state)

        clock = start.plusMillis(1_500)
        assertEquals(TransportState.PICKED_UP, fleet.status(handle).state, "1.5 초에 한 걸음이어야 한다")

        clock = start.plusSeconds(10)
        assertEquals(TransportState.DELIVERED, fleet.status(handle).state)
        assertEquals(start.plusSeconds(3), fleet.placedAtOf(DEST), "놓인 시각이 묻는 순간이다 — 걸음의 시각이어야 한다")

        // 시계가 멈추면 세계도 멈춘다. 수동 걸음을 섞어도 된다.
        fleet.place(SOURCE, CONTAINER)
        val again = fleet.dispatch(order("ref-2"))!!
        assertEquals(TransportState.ACCEPTED, fleet.status(again).state)
        fleet.tick()
        assertEquals(TransportState.PICKED_UP, fleet.status(again).state)
    }

    @Test
    fun `시계 구동이 막힌 목적지에서 기다리다 풀리면 다음 걸음에 인계한다`() {
        val fleet = AmrFleetMimic(now = { clock }, stepEvery = Duration.ofSeconds(1))
        val start = clock
        fleet.place(SOURCE, CONTAINER)
        fleet.block(DEST)
        val handle = fleet.dispatch(order())!!
        // 긴 공백도 걸음을 끝없이 돌지 않는다 — 바뀔 것이 없으면 그친다.
        clock = start.plus(Duration.ofDays(365))
        assertEquals(TransportState.WAITING_HANDOVER, fleet.status(handle).state)
        assertTrue(fleet.status(handle).holding)
        assertNull(fleet.containerAt(DEST), "막힌 목적지에 내려놓았다")

        fleet.unblock(DEST)
        assertEquals(TransportState.WAITING_HANDOVER, fleet.status(handle).state, "풀린 순간에 걸음 없이 인계했다")
        clock = clock.plusSeconds(1)
        assertEquals(TransportState.DELIVERED, fleet.status(handle).state)
        assertEquals(clock, fleet.placedAtOf(DEST))
    }

    @Test
    fun `걸음 간격은 양수여야 한다`() {
        assertFailsWith<IllegalArgumentException> { AmrFleetMimic(now = { clock }, stepEvery = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { AmrFleetMimic(now = { clock }, stepEvery = Duration.ofSeconds(-1)) }
    }

    @Test
    fun `여러 스레드가 같은 참조로 맡기고 걸음과 읽기가 겹쳐도 운반은 하나다`() {
        val fleet = AmrFleetMimic()
        val threads = 8
        val rounds = 500
        repeat(threads) { fleet.place("$SOURCE-$it", "$CONTAINER-$it") }
        fleet.place("$SOURCE-shared", "$CONTAINER-shared")
        val pool = Executors.newFixedThreadPool(threads + 1)
        val go = CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        try {
            val handles = (0 until threads).map { i ->
                pool.submit<Set<TransportHandle>> {
                    go.await()
                    val seen = mutableSetOf<TransportHandle>()
                    repeat(rounds) { r ->
                        try {
                            // 스레드마다 제 참조 하나와, 모두가 함께 쓰는 참조 하나.
                            seen += fleet.dispatch(TransportOrder("ref-$i", "$CONTAINER-$i", "$SOURCE-$i", "$DEST-$i"))!!
                            fleet.dispatch(TransportOrder("ref-shared", "$CONTAINER-shared", "$SOURCE-shared", "$DEST-shared"))
                            fleet.status(seen.first())
                            fleet.containersAt.size
                            if (r % 7 == 0) fleet.tick()
                        } catch (t: Throwable) {
                            failures += t
                        }
                    }
                    seen
                }
            }
            val ticker = pool.submit { go.await(); repeat(rounds) { fleet.tick(); fleet.blocked } }
            go.countDown()
            val perThread = handles.map { it.get(30, TimeUnit.SECONDS) }
            ticker.get(30, TimeUnit.SECONDS)
            assertEquals(emptyList(), failures.map { it.toString() })
            assertTrue(perThread.all { it.size == 1 }, "한 스레드의 같은 참조가 여러 운반이 됐다: $perThread")
            assertEquals(threads + 1, fleet.dispatches, "같은 참조가 경합 끝에 둘이 됐다")
            repeat(5) { fleet.tick() }
            (0 until threads).forEach { i ->
                assertEquals(TransportState.DELIVERED, fleet.status(perThread[i].single()).state)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `사본과 자리 관측은 용기와 놓인 시각을 한 번에 준다`() {
        val fleet = AmrFleetMimic(now = { clock })
        val at = clock.minusSeconds(5)
        fleet.place(SOURCE, CONTAINER, at)
        fleet.place(DEST, OTHER)
        val snap = fleet.snapshot()
        assertEquals(mapOf(SOURCE to Placed(CONTAINER, at), DEST to Placed(OTHER, null)), snap)
        assertEquals(Placed(CONTAINER, at), fleet.observe(SOURCE))
        assertNull(fleet.observe("없는 자리"))
        fleet.clear(SOURCE)
        assertEquals(Placed(CONTAINER, at), snap[SOURCE], "사본이 세계의 뒤를 따라 바뀌었다")
        assertNull(fleet.observe(SOURCE))
    }

    @Test
    fun `취소의 되돌림은 출발지를 조건 없이 덮어쓴다`() {
        // 미믹의 약속을 그대로 적어 둔다(KDoc) — 한 자리에 용기 하나라 인수 뒤 둔 다른 용기는 사라진다.
        val fleet = AmrFleetMimic(now = { clock })
        fleet.place(SOURCE, CONTAINER)
        val handle = fleet.dispatch(order())!!
        fleet.tick()
        fleet.place(SOURCE, OTHER)
        assertTrue(fleet.cancel(handle))
        assertEquals(CONTAINER, fleet.containerAt(SOURCE))
        assertFalse(fleet.snapshot().values.any { it.containerId == OTHER })
    }

    @Test
    fun `시계가 다른 잠금을 잡고 미믹을 다시 불러도 교착하지 않는다`() {
        // 시계는 바깥 잠금 L 을 잡고 미믹을 읽는다. 다른 스레드는 L 을 잡은 채 미믹을 부른다. 미믹이 자물쇠를 쥔 채 시계를 부르면
        // 한쪽은 미믹 자물쇠 → L, 다른 쪽은 L → 미믹 자물쇠 순으로 잡아 교착한다.
        val outer = java.util.concurrent.locks.ReentrantLock()
        // 시계 구동은 생성 때 시계를 한 번 읽으므로 그때는 아직 부를 미믹이 없다.
        val manualRef = java.util.concurrent.atomic.AtomicReference<AmrFleetMimic>()
        val drivenRef = java.util.concurrent.atomic.AtomicReference<AmrFleetMimic>()
        val manual = AmrFleetMimic(now = {
            outer.lock(); try { manualRef.get()?.let { it.containerAt(SOURCE); it.dispatches }; Instant.now() } finally { outer.unlock() }
        }).also(manualRef::set)
        val driven = AmrFleetMimic(
            now = { outer.lock(); try { drivenRef.get()?.let { it.blocked; it.dispatches }; Instant.now() } finally { outer.unlock() } },
            stepEvery = Duration.ofNanos(1_000),
        ).also(drivenRef::set)
        val pool = Executors.newFixedThreadPool(4) { r -> Thread(r).apply { isDaemon = true } }
        try {
            val jobs = listOf(manual, driven).flatMap { fleet ->
                fleet.place(SOURCE, CONTAINER)
                listOf(
                    pool.submit {
                        repeat(2_000) { i ->
                            fleet.dispatch(TransportOrder("ref-$i", CONTAINER, SOURCE, DEST))
                            fleet.tick()
                            fleet.place(SOURCE, CONTAINER)
                        }
                    },
                    pool.submit {
                        repeat(2_000) { outer.lock(); try { fleet.observe(DEST); fleet.snapshot() } finally { outer.unlock() } }
                    },
                )
            }
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
            assertEquals(Placed(CONTAINER, manual.placedAtOf(DEST)), manual.observe(DEST))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `잠금 전에 읽은 이른 시각으로 걸어도 놓인 시각은 거꾸로 가지 않는다`() {
        val fleet = AmrFleetMimic(now = { clock })
        val start = clock
        fleet.place(SOURCE, CONTAINER, start)
        assertTrue(fleet.dispatch(order()) != null)
        clock = start.plusSeconds(10)
        fleet.tick()
        fleet.tick()
        // 다른 호출이 먼저 읽어 둔 시각처럼, 이미 걸은 시각보다 이른 시각으로 마지막 걸음이 들어온다.
        clock = start.plusSeconds(5)
        fleet.tick()
        assertEquals(CONTAINER, fleet.containerAt(DEST))
        assertEquals(start.plusSeconds(10), fleet.placedAtOf(DEST), "인계가 이미 걸은 시각보다 이른 시각으로 기록됐다")
    }

    private companion object {
        const val SOURCE = "WH-OUT-01"
        const val DEST = "CELL-IN-01"
        const val CONTAINER = "HU-0001"
        const val OTHER = "HU-0002"
    }
}

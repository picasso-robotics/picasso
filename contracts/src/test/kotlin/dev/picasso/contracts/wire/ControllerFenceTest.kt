package dev.picasso.contracts.wire

import dev.picasso.contracts.v1.FenceStatus
import dev.picasso.contracts.v1.RejectionCode
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 수신 측 세대 대조의 규칙(ADR 54) — 수신자 둘이 같은 판정을 쓰므로 판정 자체를 여기서 댄다. 수신자별 거동은 각 모듈의 시험이 댄다. */
class ControllerFenceTest {

    private fun fenced(store: FenceStore = FenceStore.memory()) = ControllerFence(FenceMode.FENCED, store)

    @Test
    fun `대조하지 않는 수신자는 아무것도 거절하지 않고 바닥값이 0 이다`() {
        val fence = ControllerFence()
        assertIs<ControllerFence.Admission.Admitted>(fence.command("r1", 0, FencedCall.START)).close()
        assertIs<ControllerFence.Admission.Admitted>(fence.command("r1", 5, FencedCall.CANCEL)).close()
        assertIs<ControllerFence.Admission.Admitted>(fence.command("r1", 2, FencedCall.CANCEL)).close()
        assertEquals(0L, fence.floor("r1"), "대조하지 않는데 바닥값을 올렸다 — 응답 0 은 «대조 안 함» 이다")
        assertNull(fence.confirm("r1", 9, Duration.ZERO), "대조하지 않는 수신자가 인수 확인을 답했다")
        assertTrue(fence.current("r1", 0))
    }

    @Test
    fun `대조하는 수신자는 0 과 낮은 세대를 거절하고 같은 세대를 통과시키고 높은 세대로 올린다`() {
        val fence = fenced()
        val missing = assertIs<ControllerFence.Admission.Stale>(fence.command("r1", 0, FencedCall.START))
        assertTrue(missing.missing)
        assertIs<ControllerFence.Admission.Admitted>(fence.command("r1", 3, FencedCall.START)).close()
        assertEquals(3L, fence.floor("r1"))
        assertIs<ControllerFence.Admission.Admitted>(fence.command("r1", 3, FencedCall.PAUSE)).close()
        val low = assertIs<ControllerFence.Admission.Stale>(fence.command("r1", 2, FencedCall.CANCEL))
        assertFalse(low.missing)
        assertEquals(3L, low.floor)
        val rejection = low.rejection()
        assertEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, rejection.code)
        assertEquals(2L, rejection.requestedControllerEpoch)
        assertEquals(3L, rejection.controllerEpochFloor)
        // 기체마다 따로다 — 한 기체의 바닥값이 다른 기체의 낮은 세대를 막지 않는다.
        assertIs<ControllerFence.Admission.Admitted>(fence.command("r2", 1, FencedCall.START)).close()
        assertFalse(fence.current("r1", 2))
        assertTrue(fence.current("r1", 3))
        assertFalse(fence.current("r1", 0))
    }

    @Test
    fun `저장이 던지면 바닥값을 올리지 않고 그 요청이 실패한다`() {
        val broken = object : FenceStore {
            override val durability = FenceDurability.DURABLE
            override fun load(): Map<String, Long> = emptyMap()
            override fun save(robotId: String, floor: Long) = throw IllegalStateException("disk full")
        }
        val fence = fenced(broken)
        assertFailsWith<IllegalStateException> { fence.command("r1", 4, FencedCall.START) }
        assertEquals(0L, fence.floor("r1"), "저장 전에 메모리 바닥값을 올렸다 — 적용 전 기록이 아니다")
        assertFailsWith<IllegalStateException> { fence.confirm("r1", 4, Duration.ZERO) }
    }

    @Test
    fun `자격이 없으면 올리지 않는다`() {
        val fence = ControllerFence(FenceMode.FENCED, FenceStore.memory(), EpochAdmission { _, _, call -> call == FencedCall.START })
        assertIs<ControllerFence.Admission.Stale>(fence.command("r1", 4, FencedCall.CANCEL))
        assertNull(fence.confirm("r1", 4, Duration.ZERO))
        assertEquals(0L, fence.floor("r1"))
    }

    @Test
    fun `인수 확인은 낮은 세대로 통과한 명령이 닫히기를 기다리고 기한이 지나면 정리 안 됨이다`() {
        val fence = fenced()
        val old = assertIs<ControllerFence.Admission.Admitted>(fence.command("r1", 1, FencedCall.START))
        assertEquals(FenceConfirmation(drained = false), fence.confirm("r1", 2, Duration.ofMillis(50)))
        assertEquals(2L, fence.floor("r1"), "기한이 지나도 바닥값은 올라 있어야 한다 — 새 진입은 막힌다")
        assertIs<ControllerFence.Admission.Stale>(fence.command("r1", 1, FencedCall.START))

        val waiting = CountDownLatch(1)
        var answer: FenceConfirmation? = null
        val t = thread { answer = fence.confirm("r1", 2, Duration.ofSeconds(5)); waiting.countDown() }
        assertFalse(waiting.await(100, TimeUnit.MILLISECONDS), "통과한 옛 명령이 닫히기 전에 확인을 답했다")
        old.close()
        assertTrue(waiting.await(5, TimeUnit.SECONDS))
        t.join()
        assertEquals(FenceConfirmation(drained = true), answer)
        // 같은 세대로 통과한 명령은 기다리지 않는다 — 자기 명령이다.
        val mine = assertIs<ControllerFence.Admission.Admitted>(fence.command("r1", 2, FencedCall.START))
        assertEquals(FenceConfirmation(drained = true), fence.confirm("r1", 2, Duration.ZERO))
        mine.close()
    }

    @Test
    fun `낮은 세대와 세대 0 의 스냅샷은 인수 확인이 아니다`() {
        val fence = fenced()
        assertEquals(FenceConfirmation(drained = true), fence.confirm("r1", 5, Duration.ZERO))
        assertNull(fence.confirm("r1", 4, Duration.ZERO))
        assertNull(fence.confirm("r1", 0, Duration.ZERO))
        assertEquals(5L, fence.floor("r1"))
    }

    @Test
    fun `확인의 답은 메모리면 VOLATILE, 정리가 안 됐거나 미확정이 남으면 PENDING 이다`() {
        val drained = FenceConfirmation(drained = true)
        assertEquals(FenceStatus.FENCE_STATUS_VOLATILE, drained.status(FenceDurability.VOLATILE, inDoubt = false))
        assertEquals(FenceStatus.FENCE_STATUS_VOLATILE, FenceConfirmation(false).status(FenceDurability.VOLATILE, inDoubt = true))
        assertEquals(FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED, drained.status(FenceDurability.DURABLE, inDoubt = false))
        assertEquals(FenceStatus.FENCE_STATUS_DURABLE_PENDING, drained.status(FenceDurability.DURABLE, inDoubt = true))
        assertEquals(FenceStatus.FENCE_STATUS_DURABLE_PENDING, FenceConfirmation(false).status(FenceDurability.DURABLE, inDoubt = false))
    }

    @Test
    fun `저장된 바닥값으로 다시 선다`() {
        val store = FenceStore.memory()
        fenced(store).command("r1", 6, FencedCall.START).let { (it as ControllerFence.Admission.Admitted).close() }
        val again = fenced(store)
        assertEquals(6L, again.floor("r1"))
        assertIs<ControllerFence.Admission.Stale>(again.command("r1", 5, FencedCall.START))
    }

    @Test
    fun `기다리는 사이 더 높은 세대가 올리면 그 확인은 인수 확인이 아니다`() {
        val fence = fenced()
        val old = assertIs<ControllerFence.Admission.Admitted>(fence.command("r1", 1, FencedCall.START))
        var answer: FenceConfirmation? = FenceConfirmation(drained = true)
        val t = thread { answer = fence.confirm("r1", 2, Duration.ofSeconds(5)) }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (fence.confirmsWaiting == 0) { assertTrue(System.nanoTime() < until); Thread.onSpinWait() }
        assertIs<ControllerFence.Admission.Admitted>(fence.command("r1", 3, FencedCall.START)).close()
        old.close()
        t.join(5_000)
        assertNull(answer, "더 높은 세대가 올린 뒤에도 낮은 세대의 확인을 답했다")
        assertEquals(3L, fence.floor("r1"))
    }

    @Test
    fun `거절의 까닭은 요청 세대와 바닥값으로 갈린다 — 밀려남은 바닥값보다 낮을 때 하나다`() {
        assertEquals(StaleReason.EPOCH_MISSING, StaleReason.of(0, 5))
        assertEquals(StaleReason.BELOW_FLOOR, StaleReason.of(4, 5))
        assertEquals(StaleReason.NOT_ADMITTED, StaleReason.of(6, 5))
        assertEquals(StaleReason.NOT_ADMITTED, StaleReason.of(5, 5))
        // uint64 의 위 절반은 Long 으로 음수다 — 받는 범위 밖이다.
        assertEquals(StaleReason.OUT_OF_RANGE, StaleReason.of(-1, 0))
        assertEquals(StaleReason.OUT_OF_RANGE, StaleReason.of(Long.MIN_VALUE, 0))
    }

    @Test
    fun `받는 범위 밖의 세대는 바닥값이 0 이어도 거절하고 인수 확인도 아니다`() {
        val fence = fenced()
        val stale = assertIs<ControllerFence.Admission.Stale>(fence.command("r1", -1, FencedCall.START))
        assertEquals(StaleReason.OUT_OF_RANGE, stale.reason)
        assertTrue(stale.detail.contains("18446744073709551615"), stale.detail)
        assertNull(fence.confirm("r1", Long.MIN_VALUE, Duration.ZERO))
        assertEquals(0L, fence.floor("r1"))
        assertFalse(fence.current("r1", -1))
    }

    @Test
    fun `통과 표지를 두 번 닫아도 같은 세대의 다른 명령 표지를 지우지 않는다`() {
        val fence = fenced()
        val a = assertIs<ControllerFence.Admission.Admitted>(fence.command("r1", 1, FencedCall.START))
        val b = assertIs<ControllerFence.Admission.Admitted>(fence.command("r1", 1, FencedCall.CANCEL))
        a.close()
        a.close()
        assertEquals(FenceConfirmation(drained = false), fence.confirm("r1", 2, Duration.ofMillis(30)), "겹쳐 닫힌 표지가 남은 명령의 표지를 지웠다")
        b.close()
    }
}

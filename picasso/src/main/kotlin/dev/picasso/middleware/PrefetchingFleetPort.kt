package dev.picasso.middleware

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 운반 상태([status])와 찾기([find])를 **소비자의 잠금 밖에서** 읽는 [AmrFleetPort](§15.237). [PrefetchingRobotPort] 의 플릿 쪽이다.
 *
 * 미들웨어는 [prefetchedStatus]·[prefetchedFind] 로 다 읽은 값만 받고 잠금 아래에서는 기다리지 않는다.
 *
 * - **읽을 것마다 읽기는 하나다.** 운반 상태는 핸들마다, 찾기는 참조마다 하나씩 돈다. 하나가 끝나기 전에는 다음 읽기를 걸지 않고, 답하지
 *   않는 운반 하나가 다른 운반의 읽기를 늦추지 않는다.
 * - **다 읽은 값은 한 번만 준다.** 같은 값을 다음 라운드에 다시 주면 멎은 읽기가 매 라운드 새 관측으로 보인다. 준 뒤에는 다음 읽기를 건다.
 * - **시각은 실제 왕복의 것이다.** [FleetRead.Read] 가 보낸 시각과 받은 시각을 든다.
 * - **낡은 값은 버린다.** 받은 지 [maxAge] 넘은 값은 주지 않고 새 읽기를 건다. 읽는 중인 채 [maxAge] 가 지나면 [FleetRead.Overdue] 다.
 * - **받아 가지 않는 자리는 지운다.** 끝난 운반은 다시 묻지 않으므로, 읽기가 돌지 않고 [maxAge] 넘게 받아 가지 않은 자리는 다음에 물을 때
 *   지운다.
 * - **명령과 [find]·[status] 는 그대로 지금 부른다.** 맡기기와 취소를 잠금 밖으로 보내는 것은 [IssuingFleetPort] 다. 둘 다 나머지를
 *   위임하므로 어느 쪽으로 겹쳐 감싸도 된다.
 *
 * 위임 포트는 두 스레드에서 불린다 — 이 데코레이터의 읽기와 명령. 그것을 견디는지는 위임 포트의 몫이다. [prefetchedStatus]·[prefetchedFind]
 * 는 한 스레드(소비자의 잠금 아래)에서 부른다.
 *
 * @param now 받은 시각과 낡음을 재는 시계. 미들웨어와 같은 시계여야 한다.
 * @param maxAge 받은 값을 쓸 수 있는 나이이자 읽기를 기다리는 기한. 현장 실측 전까지 적정값을 모른다(기한 예산과 같은 처지).
 * @param executor 읽기를 돌릴 실행기. 없으면 읽기마다 가상 스레드를 쓰고 [close] 가 그것을 내린다.
 */
class PrefetchingFleetPort(
    private val delegate: AmrFleetPort,
    private val now: () -> Instant = Instant::now,
    val maxAge: Duration = Duration.ofSeconds(2),
    executor: Executor? = null,
) : AmrFleetPort by delegate, AutoCloseable {

    private val owned: ExecutorService? = if (executor == null) Executors.newVirtualThreadPerTaskExecutor() else null
    private val executor: Executor = executor ?: checkNotNull(owned)

    /** 다 읽은 것 하나. [at] 은 낡음을 재는 시각이다 — 답이 왔으면 받은 시각, 던졌으면 던진 시각. */
    private class Done(val read: FleetRead<Any?>, val at: Instant)

    /** 읽을 것 하나의 자리. 모든 칸은 이 객체의 모니터 아래에서 읽고 쓴다. */
    private class Slot {
        var inFlight = false

        /** 지금 도는 읽기가 나간 시각. */
        var launchedAt: Instant? = null

        /** 다 읽었고 아직 주지 않은 것. */
        var done: Done? = null
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    private fun statusKey(handle: TransportHandle) = "status ${handle.id}"

    private fun findKey(reference: String) = "find $reference"

    /** 이 운반의 상태 읽기가 도는 중인가. 시험이 읽기가 끝나기를 기다리는 문이다. */
    internal fun readingStatus(handle: TransportHandle): Boolean = reading(statusKey(handle))

    /** 이 참조의 찾기가 도는 중인가. */
    internal fun readingFind(reference: String): Boolean = reading(findKey(reference))

    /** 남은 자리 수. 시험이 받아 가지 않은 자리가 지워지는지 보는 문이다. */
    internal fun slotCount(): Int = slots.size

    private fun reading(key: String): Boolean = slots[key]?.let { synchronized(it) { it.inFlight } } ?: false

    private fun fresh(at: Instant?): Boolean = at != null && !now().isAfter(at.plus(maxAge))

    override fun prefetchedStatus(handle: TransportHandle): FleetRead<TransportStatus> =
        prefetch(statusKey(handle)) { delegate.status(handle) }

    override fun prefetchedFind(reference: String): FleetRead<TransportHandle?> =
        prefetch(findKey(reference)) { delegate.find(reference) }

    @Suppress("UNCHECKED_CAST")
    private fun <T> prefetch(key: String, read: () -> T): FleetRead<T> {
        sweep(key)
        val slot = slots.computeIfAbsent(key) { Slot() }
        val launch: Boolean
        var answer: FleetRead<Any?>
        synchronized(slot) {
            val done = slot.done
            slot.done = null
            answer = when {
                done != null && fresh(done.at) -> done.read
                slot.inFlight -> if (fresh(slot.launchedAt)) FleetRead.Pending else FleetRead.Overdue(checkNotNull(slot.launchedAt))
                else -> FleetRead.Pending
            }
            launch = !slot.inFlight
            if (launch) {
                slot.inFlight = true
                slot.launchedAt = now()
            }
        }
        if (launch) {
            try {
                executor.execute { run(slot, read) }
            } catch (e: RejectedExecutionException) {
                synchronized(slot) { slot.inFlight = false }
                if (answer == FleetRead.Pending) answer = FleetRead.Failed(e)
            }
        }
        return answer as FleetRead<T>
    }

    /** 읽기가 돌지 않고 [maxAge] 넘게 받아 가지 않은 자리를 지운다. 지금 묻는 [key] 는 남긴다. */
    private fun sweep(key: String) {
        slots.entries.removeIf { (k, slot) ->
            k != key && synchronized(slot) { !slot.inFlight && !fresh(slot.done?.at ?: slot.launchedAt) }
        }
    }

    /** 읽기 한 번. 잠금 밖에서 돈다. */
    private fun run(slot: Slot, read: () -> Any?) {
        var done: Done? = null
        try {
            val sentAt = now()
            done = try {
                val value = read()
                val receivedAt = now()
                Done(FleetRead.Read(value, sentAt, receivedAt), receivedAt)
            } catch (e: RuntimeException) {
                Done(FleetRead.Failed(e), now())
            }
        } finally {
            synchronized(slot) {
                slot.inFlight = false
                slot.done = done
            }
        }
    }

    /** 이 데코레이터가 만든 실행기를 내린다. 넘겨받은 실행기는 부른 쪽의 것이라 건드리지 않는다. */
    override fun close() {
        owned?.shutdownNow()
    }
}

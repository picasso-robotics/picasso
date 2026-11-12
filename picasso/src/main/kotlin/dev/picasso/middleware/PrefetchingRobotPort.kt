package dev.picasso.middleware

import dev.picasso.contracts.v1.Capability
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * 스냅숏과 재생을 **소비자의 잠금 밖에서** 읽는 [RobotPort](§15.237·§15.267).
 *
 * 미들웨어는 자체 스레드가 없고 소비자의 잠금 아래에서 돈다. 그 아래에서 [snapshot]·[replay] 를 동기로 부르면 답하지 않는 기체 하나가
 * 라운드를 기한만큼 늘리고, 그동안 소비자의 다른 일(HTTP 엔드포인트 등)도 같은 잠금을 기다린다. 이 데코레이터는 기체마다 읽기를 하나씩
 * 자기 스레드에서 돌리고, 미들웨어는 [prefetched] 로 다 읽은 값만 받는다. 잠금 아래에서는 기다리지 않는다.
 *
 * - **기체마다 읽기는 하나다.** 하나가 끝나기 전에는 다음 읽기를 걸지 않는다. 답하지 않는 기체는 제 읽기만 붙들고 다른 기체의 읽기를
 *   늦추지 않는다.
 * - **다 읽은 값은 한 번만 준다.** 같은 값을 다음 라운드에 다시 주면 멎은 읽기가 매 라운드 새 관측으로 보인다. 준 뒤에는 다음 읽기를 건다.
 * - **시각은 실제 왕복의 것이다.** [PrefetchedRead.Read] 가 보낸 시각과 받은 시각을 들고, 미들웨어는 그것으로 끊김 유예와 시계 차를 잰다.
 * - **낡은 값은 버린다.** 받은 지 [maxAge] 넘은 값은 주지 않는다. 읽는 중인데 마지막 읽기가 [maxAge] 넘게 지났으면 [PrefetchedRead.Unread] 다.
 * - **재생은 그 읽기에 잇는다.** 스냅숏 바로 뒤에 [replay] 로 지난번에 물은 번호부터 묻는다. 미들웨어가 다른 번호를 물으면, 미리 읽은
 *   번호가 그 번호 이하이고 이벤트를 받았을 때만 그 번호 이후로 걸러 준다(재생은 버퍼 끝까지 읽는다). 아니면 널을 주고 다음 읽기에서 그
 *   번호로 묻는다. 세션이 다르면 묻지 않는다 — 번호는 세션 안의 것이다.
 * - **[snapshot] 은 지금 묻지 않는다.** 실행을 다시 세우는 자리([Middleware.resume])가 부른다. 받은 지 [maxAge] 안의 값이 있으면 그것을
 *   주고(소비하지 않는다), 없으면 읽기를 걸고 널을 준다 — 미들웨어는 스냅숏을 못 읽었다고 거부하고 부른 쪽이 다시 시도한다.
 * - **기체의 선언([capabilities])도 그 읽기에 잇는다.** 스냅숏을 읽을 때마다 선언도 한 번 묻고, [capabilities] 는 지금 묻지 않고 받은 지
 *   [maxAge] 안의 마지막 선언을 준다(소비하지 않는다). 없으면 읽기를 걸고 널을 준다 — 미들웨어는 선언을 모를 때처럼 선택 파라미터를
 *   붙이지 않는다(§15.41). 읽기 한 번에 하위 호출이 하나 늘어난다.
 * - **명령은 그대로 지금 부른다.** [start]·[cancel]·[watch] 는 위임 포트로 바로 간다. 출발을 잠금 밖으로 보내는 것은
 *   [IssuingRobotPort] 다(§15.237). 둘 다 나머지를 위임하므로 어느 쪽으로 겹쳐 감싸도 된다.
 *
 * 위임 포트는 두 스레드에서 불린다 — 이 데코레이터의 읽기와 잠금 아래의 명령. [ClientRobotPort] 는 그것을 견딘다.
 *
 * @param now 받은 시각과 낡음을 재는 시계. 미들웨어와 같은 시계여야 한다.
 * @param maxAge 받은 값을 쓸 수 있는 나이. 현장 실측 전까지 적정값을 모른다(기한 예산과 같은 처지).
 * @param executor 읽기를 돌릴 실행기. 없으면 기체마다 가상 스레드를 쓰고 [close] 가 그것을 내린다.
 */
class PrefetchingRobotPort(
    private val delegate: RobotPort,
    private val now: () -> Instant = Instant::now,
    val maxAge: Duration = Duration.ofSeconds(2),
    executor: Executor? = null,
) : RobotPort by delegate, AutoCloseable {

    private val owned: ExecutorService? = if (executor == null) Executors.newVirtualThreadPerTaskExecutor() else null
    private val executor: Executor = executor ?: checkNotNull(owned)

    /** 재생을 물을 자리 — 그 세션의 그 번호부터. */
    private data class Hint(val session: String, val from: Long)

    /** 읽기 한 번에 이어 물은 재생. [result] 는 위임 포트의 답(던졌으면 실패)이다. */
    private class ReplayRead(val from: Long, val result: Result<Replay?>)

    /** 다 읽은 것 하나. [at] 은 낡음을 재는 시각이다 — 답이 왔으면 받은 시각, 던졌으면 던진 시각. */
    private class Done(val read: PrefetchedRead, val replay: ReplayRead?, val at: Instant)

    /** 기체 하나의 읽기 자리. 모든 칸은 이 객체의 모니터 아래에서 읽고 쓴다. */
    private class Slot {
        var inFlight = false

        /** 다 읽었고 아직 주지 않은 것. */
        var done: Done? = null

        /** 이번에 준 것 — 미들웨어가 이어 묻는 [replay] 가 그 재생을 꺼낸다. */
        var taken: Done? = null

        /** 마지막으로 답이 온 스냅숏(준 것이든 아니든). [snapshot] 이 준다. */
        var latest: PrefetchedRead.Read? = null

        /** 마지막 읽기가 끝난 시각. 읽는 중일 때 [PrefetchedRead.Pending] 과 [PrefetchedRead.Unread] 를 가른다. */
        var lastDoneAt: Instant? = null

        var hint: Hint? = null

        /** 마지막으로 답이 온 선언과 그것을 받은 시각. [capabilities] 가 준다. */
        var capabilities: Pair<Capability, Instant>? = null

        /** 마지막으로 선언을 물은 시각(답이 왔든 아니든). */
        var capabilitiesAskedAt: Instant? = null

        /** [capabilitiesPending] 이 처음 참을 준 시각. 기다림은 여기서부터 [maxAge] 까지다. */
        var capabilitiesWaitSince: Instant? = null
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    private fun slot(robotId: String): Slot = slots.computeIfAbsent(robotId) { Slot() }

    /** 이 기체의 읽기가 도는 중인가. 시험이 읽기가 끝나기를 기다리는 문이다. */
    internal fun reading(robotId: String): Boolean = slots[robotId]?.let { synchronized(it) { it.inFlight } } ?: false

    private fun fresh(at: Instant?): Boolean = at != null && !now().isAfter(at.plus(maxAge))

    override fun prefetched(robotId: String): PrefetchedRead {
        val slot = slot(robotId)
        val launch: Boolean
        val answer: PrefetchedRead
        synchronized(slot) {
            val done = slot.done
            slot.done = null
            slot.taken = null
            if (done != null && fresh(done.at)) {
                slot.taken = done
                answer = done.read
                val snapshot = (done.read as? PrefetchedRead.Read)?.snapshot
                // 처음이거나 세션이 바뀌었으면 미들웨어는 이 스냅숏의 번호부터 다시 센다 — 다음 재생도 거기서부터다.
                if (snapshot != null && slot.hint?.session != snapshot.session) slot.hint = Hint(snapshot.session, snapshot.sequence)
            } else {
                answer = if (slot.inFlight && fresh(slot.lastDoneAt)) PrefetchedRead.Pending else PrefetchedRead.Unread
            }
            launch = !slot.inFlight
            if (launch) slot.inFlight = true
        }
        if (launch) launch(robotId, slot)
        return answer
    }

    override fun replay(robotId: String, from: Long): Replay? {
        val slot = slot(robotId)
        val pre = synchronized(slot) {
            val taken = slot.taken
            slot.taken = null
            val session = (taken?.read as? PrefetchedRead.Read)?.snapshot?.session ?: slot.hint?.session ?: ""
            slot.hint = Hint(session, from)
            taken?.replay
        } ?: return null
        return when (val replay = pre.result.getOrThrow()) {
            null -> null
            Replay.Evicted -> Replay.Evicted.takeIf { pre.from == from }
            is Replay.Events -> if (pre.from <= from) Replay.Events(replay.events.filter { it.header.sequence >= from }) else null
        }
    }

    override fun snapshot(robotId: String): RobotSnapshot? {
        val slot = slot(robotId)
        val launch: Boolean
        val latest: RobotSnapshot?
        synchronized(slot) {
            latest = slot.latest?.takeIf { fresh(it.receivedAt) }?.snapshot
            launch = latest == null && !slot.inFlight
            if (launch) slot.inFlight = true
        }
        if (launch) launch(robotId, slot)
        return latest
    }

    override fun capabilities(robotId: String): Capability? {
        val slot = slot(robotId)
        val launch: Boolean
        val latest: Capability?
        synchronized(slot) {
            latest = slot.capabilities?.takeIf { fresh(it.second) }?.first
            launch = latest == null && !slot.inFlight
            if (launch) slot.inFlight = true
        }
        if (launch) launch(robotId, slot)
        return latest
    }

    /**
     * 받은 지 [maxAge] 안의 선언이 없고 그 사이 선언을 물은 적도 없으면 읽는 중이다. 기다림은 처음 참을 준 때부터 [maxAge] 까지다 —
     * 스냅숏 읽기가 던지면 선언을 묻지 않으므로, 기한이 없으면 출발이 끝없이 밀린다.
     */
    override fun capabilitiesPending(robotId: String): Boolean {
        val slot = slot(robotId)
        var launch = false
        val pending: Boolean
        synchronized(slot) {
            if (slot.capabilities?.let { fresh(it.second) } == true || fresh(slot.capabilitiesAskedAt)) {
                slot.capabilitiesWaitSince = null
                pending = false
            } else {
                val since = slot.capabilitiesWaitSince ?: now().also { slot.capabilitiesWaitSince = it }
                pending = fresh(since)
                launch = !slot.inFlight
                if (launch) slot.inFlight = true
            }
        }
        if (launch) launch(robotId, slot)
        return pending
    }

    private fun launch(robotId: String, slot: Slot) {
        try {
            executor.execute { read(robotId, slot) }
        } catch (e: RejectedExecutionException) {
            synchronized(slot) { slot.inFlight = false }
        }
    }

    /** 읽기 한 번 — 스냅숏, 이어서 재생과 선언. 잠금 밖에서 돈다. */
    private fun read(robotId: String, slot: Slot) {
        var done: Done? = null
        var capabilities: Pair<Capability, Instant>? = null
        var capabilitiesAskedAt: Instant? = null
        try {
            val hint = synchronized(slot) { slot.hint }
            val sentAt = now()
            done = try {
                val snapshot = delegate.snapshot(robotId)
                val receivedAt = now()
                val replay = if (snapshot != null && hint != null && hint.session == snapshot.session) {
                    ReplayRead(hint.from, runCatching { delegate.replay(robotId, hint.from) })
                } else {
                    null
                }
                // 선언을 못 물어봤으면 앞서 받은 선언을 그대로 두고 기한이 그것을 버린다 — 널로 덮으면 한 번의 실패가 곧 «모른다» 가 된다.
                capabilities = runCatching { delegate.capabilities(robotId) }.getOrNull()?.let { it to now() }
                capabilitiesAskedAt = now()
                Done(PrefetchedRead.Read(snapshot, sentAt, receivedAt), replay, receivedAt)
            } catch (e: RuntimeException) {
                Done(PrefetchedRead.Failed(e), null, now())
            }
        } finally {
            synchronized(slot) {
                slot.inFlight = false
                done?.let { d ->
                    slot.done = d
                    slot.lastDoneAt = d.at
                    (d.read as? PrefetchedRead.Read)?.takeIf { it.snapshot != null }?.let { slot.latest = it }
                }
                capabilities?.let { slot.capabilities = it }
                capabilitiesAskedAt?.let { slot.capabilitiesAskedAt = it }
            }
        }
    }

    /** 이 데코레이터가 만든 실행기를 내린다. 넘겨받은 실행기는 부른 쪽의 것이라 건드리지 않는다. */
    override fun close() {
        owned?.shutdownNow()
    }
}

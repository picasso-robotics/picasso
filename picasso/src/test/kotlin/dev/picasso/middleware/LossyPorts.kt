package dev.picasso.middleware

import dev.picasso.contracts.v1.Precondition
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.ProgressBasis
import dev.picasso.contracts.v1.Reference
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Duration
import java.time.Instant

/**
 * **응답 유실**의 더블 — 요청은 하류에 닿고 답만 오지 않는다(보고서 17장 3번 *"요청 직후 연결 단절"*).
 *
 * 미믹의 gRPC 에는 장애 주입이 없다(`InjectTransportFault` 는 발행 축이다). 그리고 잃어버리는 것은 미들웨어와 하류
 * 사이의 선이므로 그 선의 더블이 여기 있는 것이 맞다 — 하류는 정상적으로 접수했다. 그래서 위임한 **뒤에** 던진다.
 */
class LossyRobotPort(private val delegate: RobotPort, private var dropStartResponses: Int) : RobotPort {
    var starts = 0
        private set

    override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
        starts += 1
        val response = delegate.start(robotId, taskId, revision, skillType, parameters)
        if (dropStartResponses > 0) {
            dropStartResponses -= 1
            throw IllegalStateException("response lost after the request reached the robot")
        }
        return response
    }

    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> = delegate.watch(robotId, handle)
    override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = delegate.cancel(robotId, handle)
    override fun snapshot(robotId: String): RobotSnapshot? = delegate.snapshot(robotId)
    override fun replay(robotId: String, from: Long): Replay? = delegate.replay(robotId, from)
}

/** 상태를 **못 물어보는** 포트 — 스냅샷이 안 열린다. `null` 은 "없음" 이 아니라 "모름" 이고, 엔진은 그 둘을 다르게 다뤄야 한다. */
class FaultBlindRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    override fun snapshot(robotId: String): RobotSnapshot? = null
    override fun replay(robotId: String, from: Long): Replay? = null
}

/**
 * 하위와의 선이 **재시작 없이 끊긴** 것처럼 답하는 포트(§15.242). [down] 인 동안 스냅숏과 재생을 못 읽고(널), 갱신 스트림은 끊기기 전까지
 * 받은 것만 돌려준다 — 끊긴 스트림이 조용히 다시 붙으려 하는 동안 새 갱신이 안 오는 모양이다. 읽기는 던지지 않는다. 취소는 닿지 않고
 * 던진다 — [cancelThrough] 면 끊긴 동안에도 취소만은 닿는다(읽기만 못 하는 선). 하위는 그동안에도 일을 한다.
 */
class LinkDownRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    var down = false
    var cancelThrough = false

    private val seen = mutableMapOf<String, List<WatchTaskResponse>>()

    override fun snapshot(robotId: String): RobotSnapshot? = if (down) null else delegate.snapshot(robotId)
    override fun replay(robotId: String, from: Long): Replay? = if (down) null else delegate.replay(robotId, from)
    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> =
        if (down) seen[handle.taskId].orEmpty() else delegate.watch(robotId, handle).also { seen[handle.taskId] = it }
    override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse =
        if (down && !cancelThrough) throw IllegalStateException("link down: cancel did not reach the robot") else delegate.cancel(robotId, handle)
}

/**
 * **요청이 닿지 않는** 포트 — `StartTask` 가 하위에 닿기 전에 던진다. 미들웨어에게는 응답 유실([LossyRobotPort])과 똑같이 보이지만 기체는
 * 그 태스크를 모르고, 그 태스크의 취소에 `NOT_FOUND` 로 답한다. 위임하지 않으므로 [starts] 는 보낸 횟수이지 닿은 횟수가 아니다.
 */
class UnsentStartRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    var starts = 0
        private set

    override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
        starts += 1
        throw IllegalStateException("request never reached the robot")
    }
}

/**
 * 하위가 **태스크를 잊은** 것처럼 답하는 포트(§15.228) — 어댑터 호스트가 다시 뜬 모양이다. [forget] 뒤로 그 태스크의 갱신 스트림은 받은
 * 것에서 멈추고 따라갈 수 없다고 답한다(`NOT_FOUND`). `hide` 면 스냅숏에도 그 태스크가 없다. 포트가 그 태스크를 놓으면([release]) 다음
 * [watch] 는 하위를 처음부터 다시 따라간다. 하위는 그동안에도 일을 한다. [starts] 는 `StartTask` 를 보낸 횟수다.
 */
class ForgettingRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    var starts = 0
        private set

    private var forgotten: String? = null
    private var hidden: String? = null
    private var frozen: List<WatchTaskResponse> = emptyList()

    fun forget(robotId: String, handle: TaskHandle, hide: Boolean) {
        frozen = delegate.watch(robotId, handle)
        forgotten = handle.taskId
        hidden = handle.taskId.takeIf { hide }
    }

    override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
        starts += 1
        return delegate.start(robotId, taskId, revision, skillType, parameters)
    }

    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> =
        if (handle.taskId == forgotten) frozen else delegate.watch(robotId, handle)

    override fun unfollowable(robotId: String, handle: TaskHandle): String? =
        if (handle.taskId == forgotten) "NOT_FOUND" else delegate.unfollowable(robotId, handle)

    override fun release(robotId: String, handle: TaskHandle) {
        if (handle.taskId == forgotten) forgotten = null
        delegate.release(robotId, handle)
    }

    override fun snapshot(robotId: String): RobotSnapshot? = delegate.snapshot(robotId)?.let { s ->
        val hide = hidden ?: return@let s
        s.copy(tasks = s.tasks - hide, revisions = s.revisions - hide)
    }
}

/** 재생 버퍼가 **한 번 축출**된 것처럼 답하는 포트 — 소비자가 스냅샷부터 다시 세우는지 본다(17장 9번). */
class EvictingRobotPort(private val delegate: RobotPort, private var evictions: Int = 1) : RobotPort by delegate {
    var replays = 0
        private set

    override fun replay(robotId: String, from: Long): Replay? {
        replays += 1
        if (evictions > 0) {
            evictions -= 1
            return Replay.Evicted
        }
        return delegate.replay(robotId, from)
    }
}

/**
 * 하위가 **다시 뜬** 것처럼 답하는 포트(§15.215 · 옛 커서). [restart] 뒤로 세션이 바뀌고 이벤트 번호가 0 부터 다시 선다.
 * 재생은 새 프로세스처럼 요청 번호 이상만 거르므로, 앞 세션의 커서로 물으면 그 번호에 닿을 때까지 빈 답이다 — 축출로
 * 답하지 않는다. 발신자 둘(`adapter-host`·미믹)이 그렇게 답한다.
 */
class RestartingRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    private var session = "before-restart"

    /** 다시 뜬 때의 하위 번호. 그 뒤의 번호는 이만큼 빼서 새 프로세스가 0 부터 센 것으로 보인다. */
    private var base = 0L

    fun restart(robotId: String) {
        base = delegate.snapshot(robotId)?.sequence ?: error("하위를 못 본다")
        session = "after-restart"
    }

    override fun snapshot(robotId: String): RobotSnapshot? =
        delegate.snapshot(robotId)?.let { it.copy(sequence = it.sequence - base, session = session) }

    override fun replay(robotId: String, from: Long): Replay? = when (val replay = delegate.replay(robotId, from + base)) {
        is Replay.Events -> Replay.Events(
            replay.events.map { e -> e.toBuilder().setHeader(e.header.toBuilder().setSequence(e.header.sequence - base)).build() },
        )
        else -> replay
    }
}

/**
 * 플릿 쪽의 같은 더블 — 그리고 **조회 능력을 선언**한다. 조사한 실물 하류 셋이 전부 클라이언트 참조 키를 안 받으므로
 * (보고서 13.2), [ExecutionLookup.NONE] 인 플릿이 현실에 가깝다. 그때 `IN_DOUBT` 는 자동으로 안 풀리고 운영자에게 간다.
 */
class LossyFleet(
    private val delegate: AmrFleetPort,
    private var dropDispatchResponses: Int,
    override val executionLookup: ExecutionLookup = ExecutionLookup.CLIENT_REFERENCE,
) : AmrFleetPort {
    override fun dispatch(order: TransportOrder): TransportHandle? {
        val handle = delegate.dispatch(order)
        if (dropDispatchResponses > 0) {
            dropDispatchResponses -= 1
            throw IllegalStateException("response lost after the fleet accepted the order")
        }
        return handle
    }

    override fun find(reference: String): TransportHandle? = delegate.find(reference)
    override fun status(handle: TransportHandle): TransportStatus = delegate.status(handle)
    override fun cancel(handle: TransportHandle): Boolean = delegate.cancel(handle)
}

/**
 * 진행률의 **근거**를 갈아 끼우는 포트 — 실물의 두 갈래를 미믹 위에서 만든다.
 *
 * 미믹은 언제나 잰다(경과 시간 비율). 실물은 그렇지 않다 — Orbit·Digit 은 세고 Spot·G1 은 못 잰다(§15.108).
 * 그 둘을 미들웨어에 보이려면 여기서 만드는 수밖에 없다. **값을 지어내는 것이 아니라 계약의 자격 필드를
 * 갈아 끼우는 것**이며, 실물이 그 자리에 무엇을 싣는지는 어댑터 시험이 따로 본다.
 */
class ProgressPort(
    private val delegate: RobotPort,
    /** 널이면 하류가 낸 것을 그대로 둔다. */
    private val basis: ProgressBasis?,
    /** 널이 아니면 진행률을 이 값에 **묶는다** — 재는데 안 움직이는 기체를 만든다. */
    private val pinned: Double? = null,
) : RobotPort by delegate {
    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> =
        delegate.watch(robotId, handle).map { response ->
            response.toBuilder()
                .also { b -> basis?.let(b::setProgressBasis) }
                .also { b -> pinned?.let(b::setProgress) }
                .build()
        }
}

/** 능력을 못 묻는 포트 — 계획 시점 사슬 검사가 건너뛰어야 하는 경우를 만든다(리뷰 C10). */
class CapabilityBlindRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    override fun capabilities(robotId: String): Capability? = null
}

/** 로봇이 계약에 없는 주어(99)로 조건을 선언한 것처럼 보이게 하는 포트 — 새 계약의 발신자 흉내(리뷰 C5). */
class AlienSubjectRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    override fun capabilities(robotId: String): Capability? {
        val real = delegate.capabilities(robotId) ?: return null
        val builder = real.toBuilder()
        builder.skillsBuilderList.filter { it.skillType == "navigate_to" }.forEach { skill ->
            skill.addPreconditions(Precondition.newBuilder().setSubjectValue(99).setRequires(HoldKind.HOLD_KIND_EMPTY))
        }
        return builder.build()
    }
}

/**
 * 무엇을 보내든 발신자가 **사전 조건으로 거절**하는 포트. 실물의 거절을 흉내내는 것이 목적이 아니라,
 * 사건이 **첫 pump 에** 일어나 가상 시계가 원점에 머무르게 하는 것이 목적이다 — 번들 해시를 두 판
 * 사이에서 대조하려면 사건 시각이 구동 횟수에 흔들리면 안 된다.
 */
class PreconditionRefusingRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    override fun start(
        robotId: String,
        taskId: String,
        revision: Int,
        skillType: String,
        parameters: Map<String, String>,
    ): StartTaskResponse = StartTaskResponse.newBuilder()
        .setRejection(
            Rejection.newBuilder()
                .setCode(RejectionCode.REJECTION_CODE_PRECONDITION_UNMET)
                .setDetail("HOLD: 요구=EMPTY 관측=HOLDING")
                .addReferences(
                    Reference.newBuilder()
                        .setKey(Reference.Key.KEY_PRECONDITION_SUBJECT)
                        .setValue("HOLD"),
                ),
        )
        .build()
}

/**
 * 파지를 **볼 수 없는** 기체 — 갱신의 파지를 `NOT_OBSERVABLE` 로 덮는다.
 *
 * 미믹은 언제나 관측한다(못 보는 기종이 아니다). 그래서 설계안 §10.1 시나리오 4(관측 불가)를 만들 수단이
 * 엔진 쪽에 없고, 만들려고 미믹을 고치면 다른 시험들이 기대는 성질이 흔들린다. 못 보는 것은 **선의 성질**이
 * 아니라 기체의 성질이지만, 여기서는 그 기체를 흉내내는 더블이 이 자리에 있는 것이 맞다.
 */
class HoldBlindRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> =
        delegate.watch(robotId, handle).map { update ->
            update.toBuilder()
                .setHold(HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_NOT_OBSERVABLE))
                .build()
        }
}

/**
 * **그리퍼가 안 열린 기체** — 하류는 성공했다는데 끝난 갱신의 파지가 여전히 든 채다.
 *
 * 미믹은 성공에 언제나 빈손으로 두므로 이 상황을 못 만든다. 설계안 §5.1 표 둘째 줄(놓고 끝나야 하는데
 * 들고 있음)이 겨냥하는 현실이고, 실물에서는 파지 해제 실패가 이 모양으로 온다.
 */
class StuckGripperRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> =
        delegate.watch(robotId, handle).map { update ->
            if (update.state != TaskState.TASK_STATE_SUCCEEDED) {
                update
            } else {
                update.toBuilder()
                    .setHold(HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_HOLDING).setObjectRef("stuck"))
                    .build()
            }
        }
}

/**
 * 설비 읽기가 **던지는** 포트 — [at] 의 자리를 [armed] 가 켜진 동안 읽으면 예외가 나온다. PLC 읽기 시간 초과의 모양이다.
 *
 * 켜기 전에는 그대로 위임한다. 제출 때의 게이트도 같은 포트를 읽으므로, 던지게 하는 시점을 시험이 정한다.
 */
class ThrowingCellSignals(private val delegate: CellSignals, private val at: Set<String>) : CellSignals by delegate {
    var armed = false
    var throws = 0
        private set

    override fun observe(location: String): SlotSignal? {
        if (armed && location in at) {
            throws += 1
            throw IllegalStateException("PLC read timed out at $location")
        }
        return delegate.observe(location)
    }
}

/**
 * **취소 전송이 던지는** 포트 — 처음 [failures] 번은 하위에 닿지 않은 채 던지고, 그 뒤는 위임한다. 연결 거부나 기한 초과로
 * 취소가 나가지 못한 모양이다. 닿지 않았으므로 위임하지 않는다(응답만 잃는 [LossyRobotPort] 와 다르다).
 */
class FlakyCancelRobotPort(private val delegate: RobotPort, private var failures: Int = Int.MAX_VALUE) : RobotPort by delegate {
    var attempts = 0
        private set
    var delivered = 0
        private set

    override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse {
        attempts += 1
        if (failures > 0) {
            failures -= 1
            throw IllegalStateException("cancel did not reach the robot")
        }
        delivered += 1
        return delegate.cancel(robotId, handle)
    }
}

/** 플릿 쪽의 같은 더블 — 처음 [failures] 번의 취소가 던진다. */
class FlakyCancelFleet(private val delegate: AmrFleetPort, private var failures: Int = Int.MAX_VALUE) : AmrFleetPort by delegate {
    var attempts = 0
        private set

    override fun cancel(handle: TransportHandle): Boolean {
        attempts += 1
        if (failures > 0) {
            failures -= 1
            throw IllegalStateException("cancel did not reach the fleet")
        }
        return delegate.cancel(handle)
    }
}

/** 그 기체의 스냅숏 읽기가 던지는 포트 — [ClientRobotPort] 는 잡아 `null` 로 접지만 다른 구현은 그러지 않을 수 있다. */
class ThrowingSnapshotRobotPort(
    private val delegate: RobotPort,
    private val robotId: String,
    var armed: Boolean = true,
) : RobotPort by delegate {

    override fun snapshot(robotId: String): RobotSnapshot? {
        if (armed && robotId == this.robotId) throw IllegalStateException("snapshot read timed out for $robotId")
        return delegate.snapshot(robotId)
    }
}

/**
 * **발신자 시계가 어긋난** 포트 — 어댑터 호스트·에뮬레이터가 자기 시계로 찍는 두 시각을 [shift] 만큼 옮긴다. 하위가 상태 전이를 적은
 * 시각(감시 응답 헤더의 `state_as_of`)과 응답을 찍은 시각(스냅숏 헤더의 `occurred_at`)이다. 음수면 발신자가 미들웨어보다 늦다.
 * 같은 시계로 도는 시험 세계에서 환경 전제 K 가 깨진 현장을 만든다. 값을 지어내는 것이 아니라 한 시계의 오차를 두 자리에 같이 싣는다.
 */
class ClockShiftRobotPort(private val delegate: RobotPort, var shift: Duration) : RobotPort by delegate {
    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> =
        delegate.watch(robotId, handle).map { response ->
            val stamped = response.header.stateAsOf
            if (stamped.isBlank()) response else response.toBuilder()
                .setHeader(response.header.toBuilder().setStateAsOf(Instant.parse(stamped).plus(shift).toString()))
                .build()
        }

    override fun snapshot(robotId: String): RobotSnapshot? =
        delegate.snapshot(robotId)?.let { s -> s.copy(occurredAt = s.occurredAt?.plus(shift)) }
}

/** 스냅숏 왕복이 [spend] 만큼 걸리는 포트 — 응답을 받은 뒤 가상 시계를 민다. 보낸 시각과 받은 시각이 벌어진다. */
class SlowSnapshotRobotPort(private val delegate: RobotPort, private val spend: () -> Unit) : RobotPort by delegate {
    override fun snapshot(robotId: String): RobotSnapshot? = delegate.snapshot(robotId).also { spend() }
}

/** 옛 발신자(계약 0.10.0) — 스냅숏에 손을 싣지 않는다(`GetSnapshotResponse.hold` 이전). 소비자는 그 손을 빈손으로도 든 채로도 지어내지 않아야 한다. */
class HandSilentRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
    override fun snapshot(robotId: String): RobotSnapshot? = delegate.snapshot(robotId)?.copy(hold = null)
}

package dev.picasso.middleware

import dev.picasso.client.PicassoClient
import dev.picasso.client.TaskFollower
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.FaultObservability
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.ValueType
import dev.picasso.contracts.v1.WatchTaskResponse
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.time.Duration
import java.time.Instant

/**
 * [PicassoClient] 위의 [RobotPort]. 태스크마다 받은 갱신을 누적해 들고, 스트림이 닫히면 다시 붙는다([watch]).
 *
 * 태스크별 표는 잠금 없는 맵이다. 호출자는 미들웨어 하나이고 그 미들웨어를 지키는 호스트의 잠금 아래에서
 * 부른다고 전제한다(미들웨어 자체에 스레드가 없다). 차단 창 표만은 동시 맵이다 — [PrefetchingRobotPort] 가 [snapshot]·[replay] 를
 * 자기 스레드에서 부르는 동안 잠금 아래의 명령이 같은 표를 읽고 쓴다(§15.267).
 *
 * **답하지 않는 기체는 잠시 묻지 않는다**(§15.237). 단건 호출(`GetCapabilities`·`StartTask`·`CancelTask`·`GetSnapshot`·
 * `ReplayEvents`)이 기한을 다 쓰고(`DEADLINE_EXCEEDED`) 끝나면 그 기체의 단건 호출을 [backoff] 동안 하위로 보내지 않고 바로
 * [CommandNotSent] 로 실패시킨다 — 보내지 않은 것을 아는 자리라 미들웨어가 미확정으로 읽지 않는다. 호출자의 잠금 아래에서 도는 라운드가 답하지 않는 기체 하나 때문에 라운드마다 기한만큼 늘지 않게 —
 * 차단 창 동안 그 기체가 라운드에 더하는 지연은 없고, 창이 지나면 한 번 다시 묻는다. 답이 오면(상태든 거부든 gRPC 오류든) 창을 걷는다.
 *
 * - **기한 값은 그대로다.** 적정 예산은 현장 실측 전까지 모른다. 바꾸는 것은 같은 기체에 기한을 되풀이해 쓰는 횟수뿐이다.
 * - **기한 초과에만 건다.** `UNAVAILABLE` 은 어댑터 호스트가 바로 돌려주는 답이기도 하다(벤더 링크 오류, 아는 이름을 못 읽은 질의) —
 *   빨리 온 답은 라운드를 늘리지 않는다.
 * - **기체마다 따로다.** 한 기체의 창이 다른 기체의 호출을 막지 않는다.
 * - 갱신 스트림(`WatchTask`)은 비동기라 라운드를 막지 않으므로 창과 상관없다.
 *
 * @param now 창을 재는 시계. 기본은 벽시계다.
 * @param backoff 차단 창의 길이. 기본 30초는 [LogicalCapability.linkLossWindow] 와 같다 — 스냅숏을 못 읽는 창이 끝날 무렵 다시
 *   물어, 끝내 답하지 않는 기체는 미들웨어가 끊김으로 판정한다. 현장마다 바꿀 소비자가 아직 없어 현장 시간값에 넣지 않는다(ADR 9).
 */
class ClientRobotPort(
    private val client: PicassoClient,
    private val now: () -> Instant = Instant::now,
    val backoff: Duration = Duration.ofSeconds(30),
) : RobotPort {

    /** 기체마다 차단 창이 끝나는 시각. 창이 지나도 남아 있다가 다음 호출이 답을 받으면 걷힌다. */
    private val backingOff = java.util.concurrent.ConcurrentHashMap<String, Instant>()

    /** 이 기체의 차단 창이 끝나는 시각. 창이 없으면 `null`. 시험이 창을 보는 문이다. */
    internal fun backingOffUntil(robotId: String): Instant? = backingOff[robotId]

    /** [call] 을 [robotId] 의 차단 창 아래에서 부른다. 창 안이면 하위로 보내지 않고 [CommandNotSent] 로 던진다. */
    private fun <T> guarded(robotId: String, call: () -> T): T {
        backingOff[robotId]?.let { until ->
            if (now().isBefore(until)) {
                throw CommandNotSent("robot=$robotId 가 기한 안에 답하지 않아 $until 까지 묻지 않는다")
            }
        }
        val result = try {
            call()
        } catch (e: StatusRuntimeException) {
            if (e.status.code == Status.Code.DEADLINE_EXCEEDED) backingOff[robotId] = now().plus(backoff) else backingOff.remove(robotId)
            throw e
        }
        backingOff.remove(robotId)
        return result
    }

    /** `PicassoClient` 가 이미 세대별로 캐시한다 — 여기서 또 들면 두 캐시가 어긋난다. */
    override fun capabilities(robotId: String): Capability? = runCatching { guarded(robotId) { client.capabilities(robotId) } }.getOrNull()

    /** 태스크마다 지금까지 받은 갱신과 지금 열린 팔로워. 팔로워는 끊기면 바뀌고 누적 목록은 남는다. */
    private class Followed(var follower: TaskFollower) {
        val updates = mutableListOf<WatchTaskResponse>()

        /** [follower] 에서 이미 [updates] 로 옮긴 수. */
        var taken = 0
    }

    private val followed = mutableMapOf<String, Followed>()

    /** 지금 표에 든 태스크 id. 시험이 [release] 가 표를 비우는지 보는 문이다. */
    internal val following: Set<String> get() = followed.keys.toSet()

    override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
        val typed = parameters.map { (k, v) -> typed(robotId, skillType, k, v) }
        return guarded(robotId) { client.start(robotId, taskId, revision, skillType, typed) }
    }

    /**
     * 문자열 하나를 **계약이 선언한 타입**으로 옮긴다.
     *
     * 미들웨어의 파라미터가 전부 문자열인 것은 상류 때문이다 — ISA-95 의 `Value` 가 문자열이고, 층 ③ 은 그것을
     * 그대로 나른다. 계약은 타입이 있다(`ValueType`). **그 경계가 여기다.**
     *
     * 타입을 모르면(능력을 못 물어봤거나 선언에 없는 키) 문자열로 보낸다 — 선언에 없는 코어 키는 어차피
     * 로봇이 거절하고(§5.3 fail-closed), 그 거절이 조용한 변환보다 낫다.
     *
     * ★이 자리가 없던 동안 미들웨어가 보내던 것은 전부 문자열 파라미터였다. `verify_grasp`(BOOL)가 처음으로
     * 타입이 다른 것이었고, 문자열로 보내니 **태스크가 통째로 `PARAMETER_INVALID` 로 죽었다**(§15.116).
     */
    private fun typed(robotId: String, skillType: String, key: String, value: String): ParameterValue {
        val builder = ParameterValue.newBuilder().setKey(key)
        val declared = capabilities(robotId)
            ?.skillsList?.firstOrNull { it.skillType == skillType }
            ?.parametersList?.firstOrNull { it.key == key }
        return when (declared?.valueType) {
            ValueType.VALUE_TYPE_BOOL -> builder.setBoolValue(value.toBooleanStrict()).build()
            ValueType.VALUE_TYPE_NUMBER -> builder.setNumberValue(value.toDouble()).build()
            ValueType.VALUE_TYPE_INTEGER -> builder.setIntegerValue(value.toLong()).build()
            else -> builder.setStringValue(value).build()
        }
    }

    /**
     * 지금까지 받은 갱신 **전체**를 돌려준다(미들웨어가 마지막 원소를 상태로 읽는다).
     *
     * 스트림은 기한(`PicassoClient` 의 `deadlineSeconds`)이 지나거나 연결이 끊기면 닫힌다. 그때 마지막 갱신이
     * 종료가 아니면 **그 다음 갱신 번호부터 다시 붙는다** — 계약의 `from_update_index` 다. 다시 붙지 않으면
     * 기한보다 긴 스킬의 종료를 영영 못 본다. 이어 붙인 목록은 갱신 번호가 늘기만 한다.
     *
     * **실패 종류를 가른다.** 계약이 `WatchTask` 의 실패로 정한 `NOT_FOUND`(기체가 모르는 태스크)와 `OUT_OF_RANGE`(아직 없는
     * 색인)는 같은 요청으로 다시 열어도 같은 답이므로 다시 붙지 않는다(§15.210). 그 둘이 아니면 — 기한 초과·단절 — 다시 붙는다.
     */
    override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> {
        val f = followed.getOrPut(handle.taskId) { Followed(client.follow(robotId, handle, from = 0)) }
        take(f)
        if (f.follower.ended && f.updates.lastOrNull()?.state !in TERMINAL && !f.follower.closedForGood) {
            val next = f.updates.lastOrNull()?.let { it.header.updateIndex + 1 } ?: 0L
            f.follower = client.follow(robotId, handle, from = next)
            f.taken = 0
            take(f)
        }
        return f.updates.toList()
    }

    /** 팔로워가 새로 받은 것을 누적 목록으로 옮긴다. */
    private fun take(f: Followed) {
        val fresh = f.follower.updates
        f.updates += fresh.drop(f.taken)
        f.taken = fresh.size
    }

    /** 다시 열어도 같은 실패로 닫힐 스트림인가. */
    private val TaskFollower.closedForGood: Boolean
        get() = error?.let { Status.fromThrowable(it).code in PERMANENT } ?: false

    override fun unfollowable(robotId: String, handle: TaskHandle): String? {
        val f = followed[handle.taskId] ?: return null
        if (!f.follower.ended || !f.follower.closedForGood || f.updates.lastOrNull()?.state in TERMINAL) return null
        return f.follower.error?.let { Status.fromThrowable(it).code.name }
    }

    /** 그 태스크의 누적 목록과 팔로워를 버린다. 다시 [watch] 하면 처음부터 다시 따라간다. */
    override fun release(robotId: String, handle: TaskHandle) {
        followed.remove(handle.taskId)
    }

    override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = guarded(robotId) { client.cancel(robotId, handle) }

    override fun snapshot(robotId: String): RobotSnapshot? = try {
        val s = guarded(robotId) { client.snapshot(robotId) }
        RobotSnapshot(
            sequence = s.sequence,
            faults = s.faultsList,
            connection = s.connectionState,
            tasks = s.tasksList.associate { it.taskId to it.state },
            revisions = s.tasksList.associate { it.taskId to it.revision },
            session = s.header.sessionId,
            occurredAt = s.header.occurredAt.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() },
            faultsObservable = s.faultObservability != FaultObservability.FAULT_OBSERVABILITY_NOT_OBSERVABLE,
            // 칸이 없거나 UNSPECIFIED 면 옛 발신자다 — 빈손으로 읽지 않고 «말하지 않음» 으로 둔다.
            hold = s.hold.takeIf { s.hasHold() && it.kind != HoldKind.HOLD_KIND_UNSPECIFIED },
        )
    } catch (_: RuntimeException) {
        null
    }

    override fun replay(robotId: String, from: Long): Replay? = try {
        val responses = guarded(robotId) { client.replay(robotId, from) }
        if (responses.any { it.hasRejection() && it.rejection.code == RejectionCode.REJECTION_CODE_SEQUENCE_EVICTED }) {
            Replay.Evicted
        } else {
            Replay.Events(responses.filter { it.hasEvent() }.map { it.event })
        }
    } catch (_: RuntimeException) {
        null
    }

    private companion object {
        /** 이 상태 뒤에는 갱신이 없다 — 다시 붙을 까닭이 없다. */
        val TERMINAL = setOf(
            TaskState.TASK_STATE_SUCCEEDED,
            TaskState.TASK_STATE_FAILED,
            TaskState.TASK_STATE_CANCELLED,
            TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED,
        )

        /** 계약이 `WatchTask` 의 실패로 정한 둘(`docs/contract.md` §7.1). 같은 요청에는 같은 답이다. */
        val PERMANENT = setOf(Status.Code.NOT_FOUND, Status.Code.OUT_OF_RANGE)
    }
}

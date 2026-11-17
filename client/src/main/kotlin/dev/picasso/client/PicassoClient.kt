package dev.picasso.client

import dev.picasso.contracts.v1.CancelTaskRequest
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.CapabilityRequirement
import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.GetCapabilitiesRequest
import dev.picasso.contracts.v1.GetKnownSiteNamesRequest
import dev.picasso.contracts.v1.GetKnownSiteNamesResponse
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.GetSnapshotResponse
import dev.picasso.contracts.v1.ReplayEventsRequest
import dev.picasso.contracts.v1.ReplayEventsResponse
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.PauseTaskRequest
import dev.picasso.contracts.v1.PauseTaskResponse
import dev.picasso.contracts.v1.ProtocolLimits
import dev.picasso.contracts.v1.ResumeTaskRequest
import dev.picasso.contracts.v1.ResumeTaskResponse
import dev.picasso.contracts.v1.RetryTaskRequest
import dev.picasso.contracts.v1.RetryTaskResponse
import dev.picasso.contracts.v1.SkillServiceGrpc
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.v1.WatchTaskRequest
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.contracts.wire.RequestHeaders
import dev.picasso.profile.RequirementSet
import io.grpc.Channel
import io.grpc.stub.StreamObserver
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 계약을 두드리는 얇은 소비자(§3.3).
 *
 * **하는 일은 넷뿐이다** — 요구 집합을 협상하고, 능력을 받아 캐시하고,
 * 태스크를 걸고 따라가고, 명령을 **시키는 대로** 보낸다. 언제 무엇을 보낼지는
 * 정하지 않는다.
 *
 * **능력을 보고 스스로 판단하지 않는다.** `pause_support`가 `NO`인 스킬에
 * `PauseTask`를 안 보내는 "똑똑한" 클라이언트는 §7.4의 기종 차이를 소비자
 * 코드에 다시 새기는 것이고, 완료 기준 7이 증명하려는 거절 경로를 영영 안
 * 밟는다. 보내고 거절을 받는다.
 *
 * **헤더를 손으로 만들지 않는다** — §5.5의 요청 열은 [RequestHeaders]가 안다.
 *
 * @param identityOverride 있으면 **헤더의 `client_id`만** 이것으로 바꾸고
 *   페이로드는 [clientId]를 그대로 쓴다. 완료 기준 13의 `IDENTITY_MISMATCH`
 *   픽스처다. 둘을 함께 바꾸면 어긋나지 않는다. `Negotiate`에서만 발화한다 —
 *   태스크 RPC는 `client_id`를 싣지 않고, `GetCapabilities`는 거절 자리가
 *   없어 gRPC 상태로 나간다(§15).
 * @param controllerEpoch 이 발신자의 제어자 세대(계약 0.12.0, ADR 54). 요청 헤더마다 싣는다. **처음 본 0 아닌 값을 붙잡고 바꾸지 않는다** —
 *   프로세스 하나는 세대 하나다. 붙잡은 뒤 공급자가 다른 값을 내면 명령은 보내지 않고 [ControllerEpochChanged] 로 던진다(세대가 바뀌면
 *   클라이언트와 그 위의 포트를 새로 만든다). 읽기는 붙잡은 값을 싣고 그대로 보낸다. 기본은 0 — «싣지 않음» 이다.
 */
class PicassoClient(
    channel: Channel,
    val clientId: String,
    private val identityOverride: String? = null,
    private val deadlineSeconds: Long = 10,
    private val controllerEpoch: () -> Long = { 0L },
) {
    private val skills = SkillServiceGrpc.newBlockingStub(channel)
    private val tasks = TaskServiceGrpc.newBlockingStub(channel)
    private val tasksAsync = TaskServiceGrpc.newStub(channel)
    private val events = EventServiceGrpc.newBlockingStub(channel)

    /**
     * 능력 캐시와 그때 본 세대. §5.5의 `capability_epoch`가 ETag다.
     *
     * 동시 맵이다. 모든 응답이 세대를 적으므로([note]) 스냅숏·재생을 다른 스레드에서 읽는 소비자(미들웨어의 `PrefetchingRobotPort`)가
     * 있으면 두 스레드가 이 표를 함께 쓴다.
     */
    private val cache = ConcurrentHashMap<String, Capability>()
    private val epochs = ConcurrentHashMap<String, Long>()

    private val capabilityRpcs = AtomicInteger()

    /** 시험이 "두 번째 호출은 RPC를 안 낸다"를 확인하는 데 쓴다. */
    val capabilityRpcCount: Int get() = capabilityRpcs.get()

    /** 붙잡은 세대. 아직 0 아닌 값을 못 봤으면 0. */
    @Volatile private var pinned = 0L

    /** 이 클라이언트의 세대 — 붙잡은 값, 아직 없으면 공급자의 지금 값. 받은 바닥값과 맞대는 쪽(포트)이 읽는다. */
    val epoch: Long get() = pinned.takeIf { it != 0L } ?: controllerEpoch()

    /** 실을 세대. 처음 본 0 아닌 값을 붙잡는다. [command] 면 붙잡은 뒤 공급자가 다른 값을 낼 때 보내지 않고 던진다. */
    private fun epochFor(command: Boolean): Long = synchronized(this) {
        val supplied = controllerEpoch()
        if (pinned == 0L) pinned = supplied
        if (command && supplied != pinned) throw ControllerEpochChanged(pinned, supplied)
        pinned
    }

    private fun header(schemaId: String, robotId: String, command: Boolean = false): MessageHeader =
        RequestHeaders.build(schemaId, robotId, identityOverride ?: clientId, epochFor(command))

    // ── 핸드셰이크 (§5.4)

    fun negotiate(robotId: String, requirements: RequirementSet): NegotiateResponse {
        val response = skills.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).negotiate(
            NegotiateRequest.newBuilder()
                .setHeader(header("picasso.v1.NegotiateRequest", robotId))
                .setRequirement(
                    CapabilityRequirement.newBuilder()
                        // **페이로드는 요구 집합의 client_id다.**
                        .setClientId(clientId)
                        .setRobotId(robotId)
                        .addAllRequirements(requirements.requirements.map { it.toString() })
                        .addAllOptionalFieldsUsed(requirements.optionalFieldsUsed)
                        .setLimitsNeeded(
                            ProtocolLimits.newBuilder()
                                .setMaxStringLength(requirements.limitsNeeded.maxStringLength)
                                .setMaxArrayLength(requirements.limitsNeeded.maxArrayLength),
                        ),
                )
                .build(),
        )
        note(robotId, response.header)
        return response
    }

    // ── 능력 (§7.3)

    fun capabilities(robotId: String): Capability = cache.getOrPut(robotId) {
        capabilityRpcs.incrementAndGet()
        val response = skills.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).getCapabilities(
            GetCapabilitiesRequest.newBuilder()
                .setHeader(header("picasso.v1.GetCapabilitiesRequest", robotId))
                .build(),
        )
        note(robotId, response.header)
        response.capability
    }

    /**
     * 응답 헤더의 세대를 본다. 바뀌었으면 캐시를 버린다(§5.5의 ETag).
     *
     * **매 응답에서 본다.** `GetCapabilities`를 다시 부를 때만 확인하는
     * 구현은 능력이 사라진 줄 모르는 채 계속 태스크를 건다.
     *
     * 이 청크에서 `mimic`의 세대는 상수이므로 **버림이 실제로 발화하는 것은
     * 단위 시험이 본다.** 끝에서 끝까지의 증명은 런타임 축소(완료 기준 14)와
     * 함께 온다.
     */
    /**
     * 응답 머리말을 본다 — 능력 세대([note])와 제어자 바닥값(ADR 54). 바닥값은 기체마다 지금까지 본 가장 큰 값을 든다 —
     * 머리말을 받는 쪽에 넘기지 않는 읽기(`capabilities` 의 능력만 돌려주는 것 따위)도 밀려남을 놓치지 않게, 포트가 [floorSeen] 으로 읽는다.
     */
    private fun note(robotId: String, header: MessageHeader) {
        note(robotId, header.capabilityEpoch)
        if (header.controllerEpoch > 0) floors.merge(robotId, header.controllerEpoch, ::maxOf)
    }

    private val floors = ConcurrentHashMap<String, Long>()

    /** 이 기체의 응답 머리말에서 지금까지 본 가장 큰 제어자 바닥값. 못 봤으면 0. */
    fun floorSeen(robotId: String): Long = floors[robotId] ?: 0L

    private fun note(robotId: String, epoch: Long) {
        if (epoch == 0L) return
        val previous = epochs.put(robotId, epoch)
        if (previous != null && previous != epoch) cache.remove(robotId)
    }

    /** 시험이 세대 변화를 흉내 내는 문. 실제로는 응답 헤더가 부른다. */
    internal fun observeEpoch(robotId: String, epoch: Long) = note(robotId, epoch)

    /**
     * `GetKnownSiteNames`(ADR 35 확인 질의) — 이 기체가 아는 사이트 이름. **판단하지 않는다**: `unsupported` 와 빈 목록을
     * 가르는 것도, `total_count > names.size` 로 잘림을 아는 것도 소비자의 일이다. 지금까지 계약에는 있는데 이 소비자에
     * 없었다(§15.87 미결).
     */
    fun knownSiteNames(robotId: String): GetKnownSiteNamesResponse =
        skills.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).getKnownSiteNames(
            GetKnownSiteNamesRequest.newBuilder()
                .setHeader(header("picasso.v1.GetKnownSiteNamesRequest", robotId))
                .setRobotId(robotId)
                .build(),
        ).also { note(robotId, it.header) }

    // ── 상태 (§4.8)

    /** `GetSnapshot` — 현재값. 활성 결함·태스크·스킬 스냅샷과 다음 이벤트 번호. 판단은 하지 않는다. */
    fun snapshot(robotId: String): GetSnapshotResponse = events.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).getSnapshot(
        GetSnapshotRequest.newBuilder()
            .setHeader(header("picasso.v1.GetSnapshotRequest", robotId))
            .setRobotId(robotId)
            .build(),
    ).also { note(robotId, it.header) }

    /**
     * `ReplayEvents(from_sequence)` — 재생 버퍼를 **끝까지** 읽어 돌려준다. 발신자가 스트림을 닫으므로 블로킹으로
     * 모아도 된다. 버퍼를 벗어났으면 첫 항목이 `SEQUENCE_EVICTED` 거절이다 — 소비자는 `GetSnapshot` 부터 다시 세운다.
     */
    fun replay(robotId: String, fromSequence: Long): List<ReplayEventsResponse> =
        events.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).replayEvents(
            ReplayEventsRequest.newBuilder()
                .setHeader(header("picasso.v1.ReplayEventsRequest", robotId))
                .setRobotId(robotId)
                .setFromSequence(fromSequence)
                .build(),
        ).asSequence().toList()

    // ── 태스크 (§4.4)

    fun start(
        robotId: String,
        taskId: String,
        revision: Int,
        skillType: String,
        parameters: List<ParameterValue>,
    ): StartTaskResponse = tasks.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).startTask(
        StartTaskRequest.newBuilder()
            .setHeader(header("picasso.v1.StartTaskRequest", robotId, command = true))
            .setTaskId(taskId).setRevision(revision).setRobotId(robotId)
            .setSkillType(skillType).addAllParameters(parameters)
            .build(),
    ).also { note(robotId, it.header) }

    /**
     * 스트림을 열고 **돌려준다.** 종착까지 블로킹으로 모으지 않는다 — 시간을
     * 흘리는 것은 호출자이고, 블로킹으로 기다리면 그 호출자가 시계를 밀 수
     * 없어 결함이 시험 실패가 아니라 **정지**로 나타난다.
     */
    fun follow(robotId: String, handle: TaskHandle, from: Long = 0): TaskFollower {
        val follower = TaskFollower()
        tasksAsync.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).watchTask(
            WatchTaskRequest.newBuilder()
                .setHeader(header("picasso.v1.WatchTaskRequest", robotId))
                .setHandle(handle).setFromUpdateIndex(from)
                .build(),
            follower,
        )
        return follower
    }

    fun pause(robotId: String, handle: TaskHandle): PauseTaskResponse =
        tasks.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).pauseTask(
            PauseTaskRequest.newBuilder()
                .setHeader(header("picasso.v1.PauseTaskRequest", robotId, command = true))
                .setHandle(handle).build(),
        )

    fun resume(robotId: String, handle: TaskHandle): ResumeTaskResponse =
        tasks.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).resumeTask(
            ResumeTaskRequest.newBuilder()
                .setHeader(header("picasso.v1.ResumeTaskRequest", robotId, command = true))
                .setHandle(handle).build(),
        )

    fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse =
        tasks.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).cancelTask(
            CancelTaskRequest.newBuilder()
                .setHeader(header("picasso.v1.CancelTaskRequest", robotId, command = true))
                .setHandle(handle).build(),
        )

    fun retry(robotId: String, handle: TaskHandle): RetryTaskResponse =
        tasks.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).retryTask(
            RetryTaskRequest.newBuilder()
                .setHeader(header("picasso.v1.RetryTaskRequest", robotId, command = true))
                .setHandle(handle).build(),
        )
}

/**
 * **세대가 바뀌어 보내지 않았다**(ADR 54). 클라이언트가 붙잡은 세대와 공급자의 지금 값이 다르다 — 명령은 선로에 나가지 않았다.
 * 프로세스 하나는 세대 하나이므로 바뀌면 클라이언트와 그 위의 포트를 새로 만든다.
 */
class ControllerEpochChanged(val pinned: Long, val supplied: Long) :
    IllegalStateException("제어자 세대가 바뀌었다: 붙잡은 값=$pinned, 지금 값=$supplied — 보내지 않았다. 클라이언트를 새로 만든다")

/**
 * 열려 있는 `WatchTask` 스트림이 준 것을 모은다.
 *
 * **쓰는 스레드와 읽는 스레드가 다르다.** 네트워크 채널에서는 gRPC 스레드가 [onNext] 를 부르고 미들웨어의
 * pump 가 [updates] 를 읽는다. 그래서 목록은 잠금 안에서만 만지고 끝 표시는 `@Volatile` 이다. in-process
 * 채널의 `directExecutor` 는 둘이 한 스레드라 이 결함이 시험에서 안 보였다.
 */
class TaskFollower : StreamObserver<WatchTaskResponse> {

    private val received = mutableListOf<WatchTaskResponse>()

    val updates: List<WatchTaskResponse> get() = synchronized(received) { received.toList() }

    @Volatile
    var completed: Boolean = false
        private set

    @Volatile
    var error: Throwable? = null
        private set

    /** 스트림이 닫혔다(정상 종료든 오류든). 닫힌 뒤에는 아무것도 더 오지 않는다. */
    val ended: Boolean get() = completed || error != null

    override fun onNext(value: WatchTaskResponse) {
        synchronized(received) { received += value }
    }

    override fun onError(t: Throwable) {
        error = t
    }

    override fun onCompleted() {
        completed = true
    }
}

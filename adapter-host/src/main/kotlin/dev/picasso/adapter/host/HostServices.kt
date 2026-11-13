package dev.picasso.adapter.host

import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.SiteNames
import dev.picasso.contracts.v1.CancelTaskRequest
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.FaultObservability
import dev.picasso.contracts.v1.GetCapabilitiesRequest
import dev.picasso.contracts.v1.GetCapabilitiesResponse
import dev.picasso.contracts.v1.GetKnownSiteNamesRequest
import dev.picasso.contracts.v1.GetKnownSiteNamesResponse
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.GetSnapshotResponse
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.PauseTaskRequest
import dev.picasso.contracts.v1.PauseTaskResponse
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.Reference
import dev.picasso.contracts.v1.ReplayEventsRequest
import dev.picasso.contracts.v1.ReplayEventsResponse
import dev.picasso.contracts.v1.ResumeTaskRequest
import dev.picasso.contracts.v1.ResumeTaskResponse
import dev.picasso.contracts.v1.RetryTaskRequest
import dev.picasso.contracts.v1.RetryTaskResponse
import dev.picasso.contracts.v1.SkillServiceGrpc
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.v1.TaskSnapshot
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskRequest
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.capability.Negotiation
import dev.picasso.capability.Negotiator
import dev.picasso.contracts.wire.RequestHeaders
import dev.picasso.uplink.report.HandshakeReport
import dev.picasso.uplink.report.HandshakeReporter
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver

/** 단항 RPC 하나를 응답한다 — 새어 나온 `StatusRuntimeException` 을 `UNKNOWN` 으로 접히지 않게(미믹과 같은 이유). */
internal inline fun <T> reply(observer: StreamObserver<T>, block: () -> T) {
    try {
        observer.onNext(block())
        observer.onCompleted()
    } catch (e: StatusRuntimeException) {
        observer.onError(e)
    }
}

/**
 * 라우팅 — 계약 개정판 차단이 먼저, 그 다음 `robot_id`.
 *
 * **기체 여럿을 들 수 있다.** 플릿 뒤에는 기체가 여럿이고(§15.106) 계약은 요청 헤더의 `robot_id` 로 그중 하나를
 * 지목한다 — 미믹이 한 프로세스에 여럿을 호스팅하는 것과 같은 편의이며 같은 단서가 붙는다: **PoC 의 편의이지
 * 아키텍처 주장이 아니다**(ADR 21). 그리고 **모든 RPC 가 펌프를
 * 먼저 지난다** — 그러지 않으면 열린 `WatchTask` 가 다른 RPC 가 만든 전이를 놓친다.
 *
 * 펌프는 세 단계로 돈다 — 읽을 자리와 읽은 것을 적는 일만 호스트 잠금 아래이고, 어댑터에 묻는 동안은 링크 잠금만 쥔다. 명령 RPC 는
 * 호스트 잠금을 쥐지 않고 들어오므로(§15.269) 그 펌프가 호스트 잠금 밖에서 읽는다. 그 기체에 보내는 중인 명령이 있으면 읽지
 * 않는다([HostedRobot.pumpReleased]).
 *
 * **[pumpOnRead] 가 거짓이면 링크를 안 쓰는 RPC 는 펌프를 건너뛴다**([AdapterHost.pumpOnRead]). 그때는 펌프 스레드가 전이를
 * 적고 열린 스트림에 민다. 명령(`read` 가 거짓인 것)은 언제나 펌프를 먼저 지난다 — 끝난 태스크를 아직 안 읽은 채 새 접수를
 * «도는 태스크가 있다» 로 거절하지 않으려고.
 */
internal class Routing(private val robots: Map<String, HostedRobot>, private val pumpOnRead: Boolean = true) {

    /** 기체 하나짜리 배치 — 시험과 직결 어댑터가 쓴다. */
    constructor(robot: HostedRobot) : this(mapOf(robot.robotId to robot))

    /** @param read 링크를 안 쓰고 기록만 읽는 RPC 인가. */
    fun enter(header: MessageHeader, read: Boolean = false): HostedRobot {
        val compatibility = RequestHeaders.compatibility(header)
        if (compatibility.blocking) {
            throw Status.FAILED_PRECONDITION.withDescription("계약 리비전이 호환되지 않는다: $compatibility").asRuntimeException()
        }
        if (header.robotId.isBlank()) {
            throw Status.INVALID_ARGUMENT.withDescription("요청 헤더에 robot_id가 없다 — §5.5는 gRPC 요청에 싣는다").asRuntimeException()
        }
        val robot = robots[header.robotId]
            ?: throw Status.NOT_FOUND.withDescription(
                "호스팅하지 않는 기체다: ${header.robotId} (있는 것: ${robots.keys.sorted()})",
            ).asRuntimeException()
        if (!read || pumpOnRead) robot.pumpReleased()
        return robot
    }
}

// ── TaskService (§4.4)

internal class HostTaskService(robots: Map<String, HostedRobot>, pumpOnRead: Boolean = true) : TaskServiceGrpc.TaskServiceImplBase() {

    constructor(robot: HostedRobot) : this(mapOf(robot.robotId to robot))

    private val routing = Routing(robots, pumpOnRead)

    private class Watcher(val observer: StreamObserver<WatchTaskResponse>, var nextIndex: Long) {
        /** 소비자가 스트림을 **취소했는가**. 취소된 쪽은 표에서 빠지기 전이라도 밀지 않는다. */
        val departed: Boolean get() = observer is ServerCallStreamObserver<*> && observer.isCancelled
    }

    private val watchers = mutableMapOf<String, MutableList<Watcher>>()

    init {
        // **기체마다 매단다.** 스트림은 태스크 단위이고 태스크는 기체의 것이므로, 미는 쪽이 어느 기체인지 알아야
        // 헤더(세션·`profile_ref`)를 그 기체의 것으로 쓸 수 있다.
        robots.values.forEach(::attach)
    }

    /** 기체 하나에 미는 쪽을 매단다. 기동 뒤에 들어온 기체는 호스트가 잠금 아래에서 부른다([AdapterHost.admit]). */
    fun attach(hosted: HostedRobot) {
        hosted.onUpdate += { task, _ -> push(hosted, task) }
    }

    /**
     * 열린 스트림에 아직 안 보낸 것을 민다. 종착이면 닫는다.
     *
     * **떠난 소비자를 먼저 지운다.** 이 함수는 갱신을 적은 쪽의 호출 안에서 돈다 — 펌프를 지난 아무 RPC 나 그 갱신을
     * 적는다. 취소된 스트림에 밀면 gRPC 가 `CANCELLED` 로 던지고, 그 예외가 그 무관한 RPC 의 실패로 나가며 같은 태스크의
     * 남은 스트림은 그 갱신을 못 받는다(§15.220). 미믹의 `TaskServiceImpl.publish` 가 같은 이유로 같은 모양이다.
     */
    private fun push(robot: HostedRobot, task: HostedRobot.HostedTask) {
        val open = watchers[task.taskId] ?: return
        open.removeAll { it.departed }
        if (open.isEmpty()) {
            watchers.remove(task.taskId)
            return
        }
        open.forEach { watcher ->
            task.updates.drop(watcher.nextIndex.toInt()).forEach { watcher.observer.onNext(responseOf(robot, task, it)) }
            watcher.nextIndex = task.updates.size.toLong()
        }
        if (task.terminal) {
            open.forEach { it.observer.onCompleted() }
            watchers.remove(task.taskId)
        }
    }

    override fun startTask(request: StartTaskRequest, observer: StreamObserver<StartTaskResponse>) = reply(observer) {
        // 호스트 잠금 없이 들어온다(§15.269) — 기록을 읽는 자리는 [HostedRobot.recorded] 로 감싼다.
        val robot = routing.enter(request.header)
        val builder = StartTaskResponse.newBuilder().setHeader(robot.recorded { robot.header(StartTaskResponse.getDescriptor()) })
        identity(request.header, request.robotId)?.let { return@reply builder.setRejection(it).build() }

        when (val outcome = robot.start(request.taskId, request.revision, request.skillType, parametersOf(request.parametersList))) {
            is HostedRobot.StartOutcome.Accepted -> builder.setHandle(robot.recorded { handleOf(robot, outcome.task) })
            is HostedRobot.StartOutcome.Idempotent -> builder.setHandle(robot.recorded { handleOf(robot, outcome.task) })
            is HostedRobot.StartOutcome.Rejected -> builder.setRejection(rejection(outcome.code, outcome.detail, outcome.references))
            is HostedRobot.StartOutcome.Unavailable -> throw outcome.status.asRuntimeException()
        }
        builder.build()
    }

    /** 떠난 스트림 하나를 표에서 뺀다. 같은 태스크의 다른 스트림은 그대로 둔다. */
    private fun forget(taskId: String, watcher: Watcher) {
        val open = watchers[taskId] ?: return
        open.remove(watcher)
        if (open.isEmpty()) watchers.remove(taskId)
    }

    override fun watchTask(request: WatchTaskRequest, observer: StreamObserver<WatchTaskResponse>) {
        // **취소 처리기를 먼저 단다.** 처리기가 있으면 취소된 스트림의 `onNext` 가 던지지 않고 버려진다. 처리기는 호출의
        // 취소 콜백에서 돌고, 그 콜백은 `AdapterHost` 가 호스트 잠금 아래로 줄 세우므로 표를 고치는 다른 손과 겹치지 않는다.
        var registered: Pair<String, Watcher>? = null
        (observer as? ServerCallStreamObserver<WatchTaskResponse>)?.setOnCancelHandler {
            registered?.let { (taskId, watcher) -> forget(taskId, watcher) }
        }
        // **괄호가 있어야 한다.** `a to b ?: c` 는 `(a to b) ?: c` 로 읽혀 쌍이 널이 아니므로 엘비스가 안 걸린다 —
        // 그러면 태스크가 널인 채로 흘러간다(실측으로 컴파일러가 잡았다).
        val (robot, task) = try {
            val hosted = routing.enter(request.header, read = true)
            hosted to (
                hosted.task(request.handle.taskId)
                    ?: throw Status.NOT_FOUND.withDescription("모르는 태스크다: ${request.handle.taskId}").asRuntimeException()
                )
        } catch (e: StatusRuntimeException) {
            observer.onError(e); return
        }
        val from = request.fromUpdateIndex
        if (from > task.updates.size) {
            observer.onError(Status.OUT_OF_RANGE.withDescription("아직 없는 색인이다: $from (있는 것: 0..${task.updates.size - 1})").asRuntimeException())
            return
        }
        task.updates.drop(from.toInt()).forEach { observer.onNext(responseOf(robot, task, it)) }
        if (task.terminal) {
            observer.onCompleted()
            return
        }
        val watcher = Watcher(observer, task.updates.size.toLong())
        watchers.getOrPut(task.taskId) { mutableListOf() }.add(watcher)
        registered = task.taskId to watcher
    }

    override fun pauseTask(request: PauseTaskRequest, observer: StreamObserver<PauseTaskResponse>) = reply(observer) {
        val robot = routing.enter(request.header)
        val (state, rejection) = manipulate(robot, request.handle, "pause") { robot.adapter.pause() }
        PauseTaskResponse.newBuilder().setHeader(robot.recorded { robot.header(PauseTaskResponse.getDescriptor()) })
            .also { b -> state?.let(b::setState); rejection?.let(b::setRejection) }.build()
    }

    override fun resumeTask(request: ResumeTaskRequest, observer: StreamObserver<ResumeTaskResponse>) = reply(observer) {
        val robot = routing.enter(request.header)
        // **지금 파라미터로** 다시 시작한다 — 멈춘 동안 갱신이 왔으면 그것이다(§4.4).
        val (state, rejection) = manipulate(robot, request.handle, "resume") { robot.adapter.resume(it) }
        ResumeTaskResponse.newBuilder().setHeader(robot.recorded { robot.header(ResumeTaskResponse.getDescriptor()) })
            .also { b -> state?.let(b::setState); rejection?.let(b::setRejection) }.build()
    }

    override fun cancelTask(request: CancelTaskRequest, observer: StreamObserver<CancelTaskResponse>) = reply(observer) {
        val robot = routing.enter(request.header)
        val (state, rejection) = manipulate(robot, request.handle, "cancel") { robot.adapter.cancel() }
        CancelTaskResponse.newBuilder().setHeader(robot.recorded { robot.header(CancelTaskResponse.getDescriptor()) })
            .also { b -> state?.let(b::setState); rejection?.let(b::setRejection) }.build()
    }

    override fun retryTask(request: RetryTaskRequest, observer: StreamObserver<RetryTaskResponse>) = reply(observer) {
        val robot = routing.enter(request.header)
        val (state, rejection) = manipulate(robot, request.handle, "retry") { robot.adapter.retry(it) }
        RetryTaskResponse.newBuilder().setHeader(robot.recorded { robot.header(RetryTaskResponse.getDescriptor()) })
            .also { b -> state?.let(b::setState); rejection?.let(b::setRejection) }.build()
    }

    /**
     * 조작 넷의 공통 — 기체가 태스크를 찾고 보내고 적는다([HostedRobot.manipulate]), 여기서는 그 답을 계약으로 옮긴다.
     *
     * 어댑터가 *수단이 없다* 고 하면 그 조작의 코드(`PAUSE_UNSUPPORTED`·`CANCEL_UNSUPPORTED`)다 — 지원하지 않는 것을
     * 지원하는 것처럼 감추지 않는다. 재개·재시도에는 그런 코드가 없어 `INVALID_TRANSITION` 에 사정을 붙인다.
     */
    private fun manipulate(
        robot: HostedRobot,
        handle: TaskHandle,
        what: String,
        send: (Map<String, Any>) -> Applied,
    ): Pair<TaskState?, Rejection?> = when (val manipulated = robot.manipulate(handle.taskId, send)) {
        HostedRobot.Manipulated.Unknown ->
            throw Status.NOT_FOUND.withDescription("모르는 태스크다: ${handle.taskId}").asRuntimeException()
        is HostedRobot.Manipulated.Latched -> null to rejection(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, manipulated.detail)
        is HostedRobot.Manipulated.Done -> when (val applied = manipulated.applied) {
            Applied.Ok -> manipulated.state to null
            is Applied.Refused -> when (applied.reason) {
                Refusal.NO_VENDOR_PRIMITIVE -> when (what) {
                    "pause" -> null to rejection(RejectionCode.REJECTION_CODE_PAUSE_UNSUPPORTED, applied.detail)
                    "cancel" -> null to rejection(RejectionCode.REJECTION_CODE_CANCEL_UNSUPPORTED, applied.detail)
                    else -> null to rejection(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, "$what: ${applied.detail}")
                }
                Refusal.LINK_ERROR -> throw Status.UNAVAILABLE.withDescription(applied.detail).asRuntimeException()
                Refusal.LINK_IN_DOUBT -> throw Status.UNKNOWN.withDescription(applied.detail).asRuntimeException()
                Refusal.VENDOR_SURFACE_ABSENT -> throw Status.UNAVAILABLE.withDescription(applied.detail).asRuntimeException()
                else -> null to rejection(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, "$what: ${applied.detail}")
            }
        }
    }

    private fun identity(header: MessageHeader, payloadRobotId: String): Rejection? =
        if (payloadRobotId.isNotBlank() && payloadRobotId != header.robotId) {
            rejection(RejectionCode.REJECTION_CODE_IDENTITY_MISMATCH, "헤더와 페이로드의 robot_id가 다르다: 헤더='${header.robotId}', 페이로드='$payloadRobotId'")
        } else {
            null
        }

    private fun handleOf(robot: HostedRobot, task: HostedRobot.HostedTask): TaskHandle =
        TaskHandle.newBuilder().setTaskId(task.taskId).setRevision(task.revision).setRobotId(robot.robotId).build()

    private fun responseOf(robot: HostedRobot, task: HostedRobot.HostedTask, update: HostedRobot.TaskUpdate): WatchTaskResponse =
        WatchTaskResponse.newBuilder()
            .setHeader(robot.header(WatchTaskResponse.getDescriptor(), updateIndex = update.index, stateAsOf = update.occurredAt))
            .setState(update.state)
            .setRevision(update.revision)
            .setAttempt(update.attempt)
            .setProgress(update.progress)
            .setPartialResult(update.partialResult)
            .setHold(update.hold)
            .setProgressBasis(update.progressBasis)
            .also { b -> update.fault?.let(b::setFault) }
            .build()

    private companion object {
        fun rejection(code: RejectionCode, detail: String, references: List<Reference> = emptyList()): Rejection =
            Rejection.newBuilder().setCode(code).setDetail(detail).addAllReferences(references).build()

        /** 계약의 값 → 어댑터가 받는 값. 타입은 계약의 oneof 가 정한다. */
        fun parametersOf(values: List<ParameterValue>): Map<String, Any> = values.associate { p ->
            p.key to when (p.valueCase) {
                ParameterValue.ValueCase.BOOL_VALUE -> p.boolValue
                ParameterValue.ValueCase.INTEGER_VALUE -> p.integerValue
                ParameterValue.ValueCase.NUMBER_VALUE -> p.numberValue
                ParameterValue.ValueCase.STRING_VALUE -> p.stringValue
                ParameterValue.ValueCase.VALUE_NOT_SET, null -> ""
            }
        }
    }
}

// ── SkillService (§5.4)

internal class HostSkillService(
    robots: Map<String, HostedRobot>,
    /**
     * §5.4 — *"결과는 성공·실패 모두 `registry`에 보고된다."* 기본값이 [HandshakeReporter.NONE] 인 것은 §3.2 가
     * 이 방향을 **런타임 접근**으로 두었기 때문이다: 레지스트리가 없어도 호스트는 돈다.
     */
    private val reporter: HandshakeReporter = HandshakeReporter.NONE,
    pumpOnRead: Boolean = true,
) : SkillServiceGrpc.SkillServiceImplBase() {

    constructor(robot: HostedRobot, reporter: HandshakeReporter = HandshakeReporter.NONE) :
        this(mapOf(robot.robotId to robot), reporter)

    private val routing = Routing(robots, pumpOnRead)

    override fun getCapabilities(request: GetCapabilitiesRequest, observer: StreamObserver<GetCapabilitiesResponse>) = reply(observer) {
        val robot = routing.enter(request.header, read = true)
        if (request.robotId.isNotBlank() && request.robotId != request.header.robotId) {
            throw Status.INVALID_ARGUMENT.withDescription("헤더와 페이로드의 robot_id가 다르다").asRuntimeException()
        }
        // `robot_software` 는 싣지 않는다 — 기체에 물어 오는 사실이고 어댑터 셋 중 그것을 읽는 것이 아직 없다(Spot 은 GetRobotId 가 있으나 안 읽는다).
        GetCapabilitiesResponse.newBuilder()
            .setHeader(robot.header(GetCapabilitiesResponse.getDescriptor()))
            .setCapability(robot.capability)
            .build()
    }

    /**
     * §5.4 의 핸드셰이크. **판정은 미믹과 같은 함수**(`capability` 모듈의 `Negotiator`)이고 여기서는 계약으로 옮기고
     * 보고할 뿐이다. 둘로 두면 같은 요구 집합에 미믹이 수락하고 실물이 거절하는 일이 가능해진다(§15.100).
     *
     * **판정 기준은 프로파일의 투영이다** — `GetCapabilities` 가 돌려주는 그것과 같다. 어댑터가 그 선언을 실제로
     * 다 드는지는 여기서 확인하지 않으며, 갈리면 협상을 통과한 소비자가 `StartTask` 에서 거절당한다(C-3 적합성).
     */
    override fun negotiate(request: NegotiateRequest, observer: StreamObserver<NegotiateResponse>) = reply(observer) {
        val robot = routing.enter(request.header, read = true)

        // 판정 불가는 거절이 아니다 — 요구를 못 읽어 답 자체가 없는 것이라 응답이 아니라 gRPC 상태로 나간다.
        val judged = when (val outcome = Negotiator.negotiate(robot.capability, request.header, request.requirement)) {
            is Negotiation.Judged -> outcome
            is Negotiation.Unparseable -> throw Status.INVALID_ARGUMENT.withDescription(outcome.detail).asRuntimeException()
        }

        val response = NegotiateResponse.newBuilder()
            .setHeader(robot.header(NegotiateResponse.getDescriptor()))
            // accepted 와 거절 목록을 따로 계산하지 않는다 — 어긋나면 소비자가 통과했다고 믿는다.
            .setAccepted(judged.rejections.isEmpty())
            .addAllRejections(judged.rejections)
            .build()

        // **§5.4 — 보고 실패는 핸드셰이크 결과에 영향을 주지 않는다.** 그래서 삼킨다. 삼켜도 잃지 않는 것은
        // 폴백의 몫이고(`FileHandshakeReporter`), 삼킨 것이 조용하지 않은 것은 워터마크의 몫이다. 느린 것도 영향이므로
        // 레지스트리 연계의 보고자는 큐에 넣기만 한다(`RegistryLink`, §15.266) — 여기는 호스트 잠금 아래다.
        runCatching { reporter.report(HandshakeReport(robot.site, request, response)) }

        response
    }

    override fun getKnownSiteNames(request: GetKnownSiteNamesRequest, observer: StreamObserver<GetKnownSiteNamesResponse>) = reply(observer) {
        // 호스트 잠금 없이 들어온다(§15.269). 어댑터에 묻는 동안은 링크 잠금만 쥔다.
        val robot = routing.enter(request.header)
        val builder = GetKnownSiteNamesResponse.newBuilder()
            .setHeader(robot.recorded { robot.header(GetKnownSiteNamesResponse.getDescriptor()) })
        when (val names = robot.adapter.knownSiteNames()) {
            SiteNames.Unsupported -> builder.setUnsupported(true)
            is SiteNames.Known -> builder
                .addAllNames(names.names.take(robot.document.maxArrayLength))
                .setTotalCount(names.names.size)
            // 못 물어봤다 — 0 개도 못 함도 아니다. 계약에 그 자리가 없어 gRPC 상태다.
            is SiteNames.Unavailable -> throw Status.UNAVAILABLE.withDescription(names.reason).asRuntimeException()
        }
        builder.build()
    }
}

// ── EventService (§4.8)

internal class HostEventService(robots: Map<String, HostedRobot>, pumpOnRead: Boolean = true) : EventServiceGrpc.EventServiceImplBase() {

    constructor(robot: HostedRobot) : this(mapOf(robot.robotId to robot))

    private val routing = Routing(robots, pumpOnRead)

    override fun getSnapshot(request: GetSnapshotRequest, observer: StreamObserver<GetSnapshotResponse>) = reply(observer) {
        val robot = routing.enter(request.header, read = true)
        val builder = GetSnapshotResponse.newBuilder()
            .setHeader(robot.header(GetSnapshotResponse.getDescriptor()))
            .setSequence(robot.sequence)
            .addAllTasks(
                robot.tasks.map {
                    TaskSnapshot.newBuilder().setTaskId(it.taskId).setSkillType(it.skillType).setState(it.last.state).setRevision(it.revision).setAttempt(0).build()
                },
            )
            // MQTT connection 스트림과 같은 값이다(미믹의 §15.95 정정과 같은 자리).
            .setConnectionState(robot.connectionState)
            // 손은 펌프가 읽어 둔 것이다(0.11.0) — 결함처럼 남쪽을 기다리지 않는다. 못 읽었으면 관측 불가이지 빈손이 아니다.
            .setHold(robot.handHold)
        // **결함을 못 봤으면 없다고 하지 않는다**(계약 0.10.0, §15.263). 결함은 비우고 못 봤다고 적는다 — [HostedRobot.faults] 는
        // 마지막으로 본 낡은 값이라 싣지 않는다. 태스크·연결·번호는 그대로 현재값이라 스냅샷을 오류로 돌리지 않는다.
        if (robot.faultsObservable) {
            builder.addAllFaults(robot.faults.values)
        } else {
            builder.setFaultObservability(FaultObservability.FAULT_OBSERVABILITY_NOT_OBSERVABLE)
        }
        builder.build()
    }

    override fun replayEvents(request: ReplayEventsRequest, observer: StreamObserver<ReplayEventsResponse>) {
        val robot = try {
            routing.enter(request.header, read = true)
        } catch (e: StatusRuntimeException) {
            observer.onError(e); return
        }
        val events = robot.replay(request.fromSequence)
        if (events == null) {
            observer.onNext(
                ReplayEventsResponse.newBuilder()
                    .setHeader(robot.header(ReplayEventsResponse.getDescriptor()))
                    .setRejection(
                        Rejection.newBuilder().setCode(RejectionCode.REJECTION_CODE_SEQUENCE_EVICTED)
                            .setDetail(
                                if (request.fromSequence > robot.sequence) {
                                    "아직 나지 않은 번호다: 요청=${request.fromSequence}, 다음=${robot.sequence} — 발신자가 다시 떴을 수 있다. GetSnapshot부터 다시 세워라"
                                } else {
                                    "재생 버퍼를 벗어났다: 요청=${request.fromSequence}, 버린 것 ≤ ${robot.evictedUpTo} — GetSnapshot부터 다시 세워라"
                                },
                            ),
                    )
                    .build(),
            )
            observer.onCompleted()
            return
        }
        events.forEach {
            observer.onNext(ReplayEventsResponse.newBuilder().setHeader(robot.header(ReplayEventsResponse.getDescriptor())).setEvent(it).build())
        }
        observer.onCompleted()
    }
}

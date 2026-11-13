package dev.picasso.adapter.host

import dev.picasso.contracts.v1.Reference
import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.ProgressObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.adapter.core.ActiveRevision
import dev.picasso.adapter.core.SiteBinding
import dev.picasso.adapter.core.SiteBindingSource
import dev.picasso.adapter.core.SiteNames
import dev.picasso.adapter.core.classifiedFault
import dev.picasso.contracts.v1.CancelTaskRequest
import dev.picasso.contracts.v1.CapabilityRequirement
import dev.picasso.contracts.v1.ConnectionMessage
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.FailureClass
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.FaultObservability
import dev.picasso.contracts.v1.GetCapabilitiesRequest
import dev.picasso.contracts.v1.GetKnownSiteNamesRequest
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.ProtocolLimits
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.ProgressKind
import dev.picasso.contracts.v1.PauseTaskRequest
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.ResumeTaskRequest
import dev.picasso.contracts.v1.RetryTaskRequest
import dev.picasso.contracts.v1.ReplayEventsRequest
import dev.picasso.contracts.v1.SkillServiceGrpc
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskRequest
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.contracts.wire.RequestHeaders
import dev.picasso.profile.ProfileDocument
import dev.picasso.uplink.Publication
import dev.picasso.uplink.report.HandshakeReporter
import dev.picasso.uplink.report.RecordingHandshakeReporter
import dev.picasso.uplink.report.RegistryLink
import dev.picasso.uplink.Publisher
import dev.picasso.uplink.RecordingPublisher
import dev.picasso.uplink.Topics
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientResponseObserver
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 계약 뒤에 선 어댑터 — 소비자가 보는 것은 미믹과 같은 모양이어야 한다. 가짜 어댑터로 계약 면을 하나씩 본다:
 * 접수와 로그, `WatchTask` 의 되짚기와 밀기, 조작 넷의 거절 어휘, 스냅샷과 재생, 축출, 그리고 발행 셋(§4.7).
 */
class AdapterHostTest {

    /** 각본대로 답하는 어댑터. 기종이 없다 — 이 시험이 보는 것은 호스트다. */
    private class ScriptedAdapter : RobotAdapter {
        var accepted = mutableListOf<Triple<String, Map<String, Any>, Instant>>()
        var refuseWith: Acceptance.Refused? = null
        var next: TaskState = TaskState.TASK_STATE_RUNNING
        var pauseAnswer: Applied = Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "없다")
        var cancelAnswer: Applied = Applied.Ok
        var updateAnswer: Applied = Applied.Ok
        val updated = mutableListOf<Triple<String, String, Map<String, Any>>>()
        var holding: HoldObservation = HoldObservation.Empty
        /** 주면 [hold] 가 이것을 던진다 — 남쪽 읽기가 실패한 기체. */
        var holdFailure: RuntimeException? = null
        var observedFaults: FaultObservation = FaultObservation.Observed(emptyList())
        var failureToReport: Fault? = null
        var resultToReport: String? = null
        var siteNames: SiteNames = SiteNames.Known(listOf("dock-3", "bay-7"))
        var reportedProgress: ProgressObservation = ProgressObservation.NotObservable("각본이 안 정했다")
        private var current: TaskState = TaskState.TASK_STATE_UNSPECIFIED

        override val state: TaskState get() = current
        override fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance {
            refuseWith?.let { return it }
            accepted += Triple(skillType, parameters, startedAt)
            current = TaskState.TASK_STATE_RUNNING
            return Acceptance.Accepted(taskId)
        }
        override fun poll(now: Instant): TaskState { current = next; return current }
        var resumeAnswer: Applied = Applied.Ok
        val resumed = mutableListOf<Map<String, Any>>()
        val retried = mutableListOf<Map<String, Any>>()

        override fun pause(): Applied = pauseAnswer.also { if (it == Applied.Ok) current = TaskState.TASK_STATE_PAUSED }
        override fun resume(parameters: Map<String, Any>): Applied =
            resumeAnswer.also { if (it == Applied.Ok) { resumed += parameters; current = TaskState.TASK_STATE_RUNNING } }
        override fun retry(parameters: Map<String, Any>): Applied =
            resumeAnswer.also { if (it == Applied.Ok) { retried += parameters; current = TaskState.TASK_STATE_RUNNING } }
        override fun cancel(): Applied = cancelAnswer.also { if (it == Applied.Ok) { current = TaskState.TASK_STATE_CANCELLING; next = TaskState.TASK_STATE_CANCELLED } }
        override fun update(taskId: String, skillType: String, parameters: Map<String, Any>, at: Instant): Applied =
            updateAnswer.also { if (it == Applied.Ok) updated += Triple(taskId, skillType, parameters) }
        override fun hold(): HoldObservation = holdFailure?.let { throw it } ?: holding
        override fun faults(): FaultObservation = observedFaults
        override fun failure(): Fault? = failureToReport
        override fun result(): String? = resultToReport
        override fun knownSiteNames(): SiteNames = siteNames
        override fun progress(): ProgressObservation = reportedProgress
    }

    /** 브로커가 막힌 것을 흉내낸다 — 막힌 동안 받은 것은 던지고 기록하지 않는다. */
    private class FlakyPublisher(private val delegate: RecordingPublisher) : Publisher {
        var broken = false
        override fun publish(publication: Publication) {
            if (broken) throw IllegalStateException("브로커 없음")
            delegate.publish(publication)
        }
    }

    /** 결속 정본 각본. 축 둘의 활성 판과 이름별 등록 판을 시험이 정한다. */
    private class ScriptedBindings(
        var active: ActiveRevision = ActiveRevision.NotConfigured,
        var calibration: ActiveRevision = ActiveRevision.NotConfigured,
        val table: MutableMap<String, String> = mutableMapOf(),
        val taught: MutableMap<String, String> = mutableMapOf(),
    ) : SiteBindingSource {
        override fun activeMap(): ActiveRevision = active
        override fun activeCalibration(): ActiveRevision = calibration
        override fun binding(name: String): SiteBinding? =
            table[name]?.let { SiteBinding(name, "site-registry", it, taught[name] ?: "cal-1") }
    }

    private class World(
        profileJson: String = Files.readString(PROFILE),
        val bindings: ScriptedBindings = ScriptedBindings(),
        /** 보고자를 바꿀 때만 준다. 주지 않으면 [handshakes] 가 받는다. */
        reporter: HandshakeReporter? = null,
    ) : AutoCloseable {
        var now: Instant = Instant.parse("2026-09-10T00:00:00Z")
        val adapter = ScriptedAdapter()
        val published = RecordingPublisher()
        val flaky = FlakyPublisher(published)
        val robot = HostedRobot(
            ROBOT, ProfileDocument.parse("test", profileJson).getOrThrow(), adapter,
            publisher = flaky, site = "line-a", bindings = bindings,
        ) { now }
        val handshakes = RecordingHandshakeReporter()
        private val name = InProcessServerBuilder.generateName()
        val host = AdapterHost(robot, InProcessServerBuilder.forName(name).directExecutor(), reporter ?: handshakes).start()
        val channel: ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()
        val tasks: TaskServiceGrpc.TaskServiceBlockingStub = TaskServiceGrpc.newBlockingStub(channel)
        val skills: SkillServiceGrpc.SkillServiceBlockingStub = SkillServiceGrpc.newBlockingStub(channel)
        val events: EventServiceGrpc.EventServiceBlockingStub = EventServiceGrpc.newBlockingStub(channel)

        fun header(schema: String) = RequestHeaders.build(schema, ROBOT, "test-client")

        fun start(taskId: String = "T-1", revision: Int = 1, skill: String = "pick_place", params: Map<String, String> = pick): StartTaskResponse =
            tasks.startTask(
                StartTaskRequest.newBuilder().setHeader(header("picasso.v1.StartTaskRequest"))
                    .setTaskId(taskId).setRevision(revision).setRobotId(ROBOT).setSkillType(skill)
                    .addAllParameters(params.map { (k, v) -> ParameterValue.newBuilder().setKey(k).setStringValue(v).build() })
                    .build(),
            )

        fun watch(handle: TaskHandle, from: Long = 0): List<WatchTaskResponse> =
            tasks.watchTask(WatchTaskRequest.newBuilder().setHeader(header("picasso.v1.WatchTaskRequest")).setHandle(handle).setFromUpdateIndex(from).build()).asSequence().toList()

        /** 아무 RPC 하나 — 펌프를 지나게 하려고 부른다. */
        fun snapshot() = events.getSnapshot(GetSnapshotRequest.newBuilder().setHeader(header("picasso.v1.GetSnapshotRequest")).setRobotId(ROBOT).build())

        fun topic(stream: Topics.Stream) = Topics.robot(dev.picasso.contracts.wire.ContractIdentity.major, "line-a", ROBOT, stream)
        fun connections() = published.topic(topic(Topics.Stream.connection)).map { it.message as ConnectionMessage }
        fun states() = published.topic(topic(Topics.Stream.state)).map { it.message as StateMessage }
        fun eventsOut() = published.topic(topic(Topics.Stream.event)).map { it.message as Event }

        override fun close() { channel.shutdownNow(); host.shutdown() }

        companion object {
            val pick = mapOf("object_id" to "SEQ-IN-02.BIN-A", "destination" to "RACK-204.S01")
        }
    }

    @Test
    fun `접수하면 ACCEPTED 와 RUNNING 이 로그에 적히고 WatchTask 가 되짚어 준다`() {
        World().use { w ->
            val response = w.start()
            assertTrue(response.hasHandle(), response.rejection.toString())
            assertEquals(ROBOT, response.header.robotId)
            assertEquals(listOf("pick_place"), w.adapter.accepted.map { it.first })
            assertEquals("SEQ-IN-02.BIN-A", w.adapter.accepted.single().second["object_id"])

            w.adapter.next = TaskState.TASK_STATE_SUCCEEDED
            w.adapter.resultToReport = "evidence://ref-1"
            val updates = w.watch(response.handle)
            assertEquals(listOf(TaskState.TASK_STATE_ACCEPTED, TaskState.TASK_STATE_RUNNING, TaskState.TASK_STATE_SUCCEEDED), updates.map { it.state })
            assertEquals(listOf(0L, 1L, 2L), updates.map { it.header.updateIndex })
            assertEquals("evidence://ref-1", updates.last().partialResult, "어댑터의 결과 참조가 partial_result 로 올라간다")
            assertEquals(1.0, updates.last().progress)
            assertEquals(HoldKind.HOLD_KIND_EMPTY, updates.last().hold.kind)
        }
    }

    @Test
    fun `실패 종착에는 어댑터의 정준 분류가 fault 로 실린다`() {
        World().use { w ->
            val handle = w.start().handle
            w.adapter.failureToReport = classifiedFault("X_TEST_GRASP", FailureClass.FAILURE_CLASS_GRASP_FAILED, "vendor=code-7", "다시 잡으십시오")
            w.adapter.next = TaskState.TASK_STATE_FAILED
            val last = w.watch(handle).last()
            assertEquals(TaskState.TASK_STATE_FAILED, last.state)
            assertEquals(FailureClass.FAILURE_CLASS_GRASP_FAILED, last.fault.failureClass)
            assertEquals("vendor=code-7", last.fault.vendorDetail)
        }
    }

    @Test
    fun `같은 요청은 같은 핸들이고, 낮은 revision 은 거절이다`() {
        World().use { w ->
            val first = w.start().handle
            val again = w.start()
            assertEquals(first, again.handle)
            assertEquals(1, w.adapter.accepted.size, "재전송이 로봇에 두 번 갔다")
            assertEquals(2, w.robot.task("T-1")!!.updates.size, "멱등 재수신이 로그를 늘렸다")
            assertEquals(RejectionCode.REJECTION_CODE_OUTDATED_REVISION, w.start(revision = 0).rejection.code)
        }
    }

    // ── 도는 태스크의 갱신 (§4.4 — Halt → Reset → Start)

    @Test
    fun `높은 revision 은 어댑터의 갱신 합성으로 가고, 같은 핸들에 RUNNING 한 줄이 더 적힌다`() {
        World().use { w ->
            val handle = w.start().handle
            val before = w.robot.task("T-1")!!.updates.size

            val bumped = w.start(revision = 2, params = mapOf("object_id" to "SEQ-IN-02.BIN-B", "destination" to "RACK-204.S02"))
            assertTrue(bumped.hasHandle(), bumped.rejection.toString())
            // **핸들은 `(task_id, revision)` 이다** — 태스크의 신원은 그대로이고 개정판만 오른다(미믹과 같다).
            assertEquals(handle.taskId, bumped.handle.taskId, "갱신이 태스크의 신원을 바꿨다")
            assertEquals(2, bumped.handle.revision)

            // **합성이 로봇까지 갔다** — 새 파라미터로.
            assertEquals(1, w.adapter.updated.size)
            assertEquals("SEQ-IN-02.BIN-B", w.adapter.updated.single().third["object_id"])
            assertEquals(1, w.adapter.accepted.size, "갱신이 접수로 다시 갔다")

            // **갱신 자체가 로그 한 줄이다**(§4.4) — 상태는 그대로 RUNNING 이고 revision 이 올랐다.
            val task = w.robot.task("T-1")!!
            assertEquals(before + 1, task.updates.size)
            assertEquals(TaskState.TASK_STATE_RUNNING, task.last.state)
            assertEquals(2, task.last.revision)
            assertEquals(2, task.revision)
        }
    }

    @Test
    fun `갱신은 진행률을 새 revision 에서 0 부터 다시 센다`() {
        World().use { w ->
            // 갱신은 도는 태스크에만 가므로 상태를 안 바꾸고 진행률을 실어야 한다 — 접수 직후의 RUNNING 이 그 자리다.
            w.adapter.reportedProgress = ProgressObservation.Fraction(0.8, "행동 4/5")
            w.start()
            assertEquals(TaskState.TASK_STATE_RUNNING, w.robot.task("T-1")!!.last.state)
            assertEquals(0.8, w.robot.task("T-1")!!.last.progress, 1e-9)

            // 단조 비감소는 `(task_id, revision, attempt)` 안에서만 성립한다(§4.4). 구간이 바뀌면 바닥도 바뀐다 —
            // 안 그러면 앞 revision 의 값이 새 구간의 바닥이 되어 **갱신 뒤 진행률이 내려갈 수 없게** 된다.
            w.adapter.reportedProgress = ProgressObservation.NotObservable("다시 시작했다")
            w.start(revision = 2)
            assertEquals(0.0, w.robot.task("T-1")!!.last.progress, 1e-9)
        }
    }

    @Test
    fun `갱신 합성을 못 드는 기체는 UPDATE_UNSUPPORTED 다`() {
        World().use { w ->
            w.start()
            w.adapter.updateAnswer = Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "플릿에 도는 미션을 멈추는 문이 없다")

            val refused = w.start(revision = 2)
            // **INVALID_TRANSITION 에 접지 않는다.** 소비자는 *지금 상태가 안 받는다* 와 *이 로봇은 원래 못 한다* 에
            // 다르게 대응한다 — 앞은 기다렸다 다시 보내고 뒤는 취소한 뒤 새 task_id 로 간다(계약 0.7.0).
            assertEquals(RejectionCode.REJECTION_CODE_UPDATE_UNSUPPORTED, refused.rejection.code)
            assertTrue(refused.rejection.detail.contains("멈추는 문이 없다"), refused.rejection.detail)
            assertEquals(1, w.robot.task("T-1")!!.revision, "거절인데 revision 이 올랐다")
        }
    }

    @Test
    fun `갱신으로 스킬을 바꿀 수는 없다`() {
        World().use { w ->
            w.start()
            val refused = w.start(revision = 2, skill = "navigate_to", params = mapOf("location" to "DOCK-3"))
            assertEquals(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, refused.rejection.code)
            assertTrue(refused.rejection.detail.contains("스킬"), refused.rejection.detail)
            assertTrue(w.adapter.updated.isEmpty(), "스킬이 바뀐 갱신이 로봇에 갔다")
        }
    }

    @Test
    fun `갱신도 프로파일의 선언에 대고 검사받는다`() {
        World().use { w ->
            w.start()
            val refused = w.start(revision = 2, params = mapOf("object_id" to "x"))
            assertEquals(RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING, refused.rejection.code)
            assertTrue(w.adapter.updated.isEmpty(), "선언을 어긴 갱신이 로봇에 갔다")
        }
    }

    @Test
    fun `멈춘 태스크의 갱신은 갈아만 두고, 재개가 새 파라미터로 다시 시작한다`() {
        World().use { w ->
            val handle = w.start().handle
            w.adapter.next = TaskState.TASK_STATE_PAUSED
            w.robot.pump()

            // §4.4 — 파라미터만 교체하고 `PAUSED` 유지. **로봇에는 아무것도 안 간다.**
            val bumped = w.start(revision = 2, params = mapOf("object_id" to "SEQ-IN-02.BIN-B", "destination" to "RACK-204.S02"))
            assertTrue(bumped.hasHandle(), bumped.rejection.toString())
            assertTrue(w.adapter.updated.isEmpty(), "멈춘 태스크에 갱신 합성을 보냈다")
            val task = w.robot.task("T-1")!!
            assertEquals(TaskState.TASK_STATE_PAUSED, task.last.state, "갱신이 상태를 바꿨다")
            assertEquals(2, task.last.revision, "갱신 자체가 로그 한 줄이다(§4.4)")

            // 그리고 재개할 때 **그 파라미터로** 다시 시작한다. 이 자리가 없으면 갱신이 조용히 버려진다.
            w.adapter.next = TaskState.TASK_STATE_RUNNING
            w.tasks.resumeTask(ResumeTaskRequest.newBuilder().setHeader(w.header("picasso.v1.ResumeTaskRequest")).setHandle(handle).build())
            assertEquals("SEQ-IN-02.BIN-B", w.adapter.resumed.single()["object_id"])
        }
    }

    @Test
    fun `사람을 기다리는 태스크도 파라미터를 받는다 — 그것이 그 상태의 존재 이유다`() {
        World().use { w ->
            val handle = w.start().handle
            w.adapter.next = TaskState.TASK_STATE_NEEDS_INTERVENTION
            w.robot.pump()

            // **개입한 사람이 파라미터를 고쳐 넣는 경로가 이것이다**(§4.4). 막으면 이 상태가 막다른 길이 된다.
            val bumped = w.start(revision = 2, params = mapOf("object_id" to "SEQ-IN-02.BIN-C", "destination" to "RACK-204.S03"))
            assertTrue(bumped.hasHandle(), bumped.rejection.toString())
            assertEquals(TaskState.TASK_STATE_NEEDS_INTERVENTION, w.robot.task("T-1")!!.last.state)

            w.adapter.next = TaskState.TASK_STATE_RUNNING
            w.tasks.retryTask(RetryTaskRequest.newBuilder().setHeader(w.header("picasso.v1.RetryTaskRequest")).setHandle(handle).build())
            assertEquals("SEQ-IN-02.BIN-C", w.adapter.retried.single()["object_id"])
        }
    }

    @Test
    fun `갱신 없이 재개하면 접수 때의 파라미터가 그대로 간다`() {
        // 호출자가 *갱신이 있었나* 를 가르지 않아도 되게 한다 — 가르면 그 분기가 곧 틀린다.
        World().use { w ->
            val handle = w.start().handle
            w.adapter.next = TaskState.TASK_STATE_PAUSED
            w.robot.pump()
            w.tasks.resumeTask(ResumeTaskRequest.newBuilder().setHeader(w.header("picasso.v1.ResumeTaskRequest")).setHandle(handle).build())
            assertEquals("SEQ-IN-02.BIN-A", w.adapter.resumed.single()["object_id"])
        }
    }

    @Test
    fun `정리 중과 종착의 갱신은 자리가 없다`() {
        World().use { w ->
            w.start()
            w.adapter.next = TaskState.TASK_STATE_SUCCEEDED
            w.robot.pump()

            val refused = w.start(revision = 2)
            assertEquals(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, refused.rejection.code)
            assertTrue(w.adapter.updated.isEmpty())
        }
    }

    @Test
    fun `프로파일이 선언하지 않은 스킬과 빠진 필수 파라미터는 계약의 어휘로 거절된다`() {
        World().use { w ->
            assertEquals(RejectionCode.REJECTION_CODE_SKILL_ABSENT, w.start(skill = "fly").rejection.code)
            assertEquals(RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING, w.start(params = mapOf("object_id" to "x")).rejection.code)
            assertTrue(w.adapter.accepted.isEmpty(), "거절될 요청이 로봇에 갔다")
        }
    }

    @Test
    fun `어댑터의 거절이 계약의 코드로 옮겨진다 — 모르는 이름은 PARAMETER_INVALID, 표면 부재는 CAPABILITY_WITHDRAWN`() {
        World().use { w ->
            w.adapter.refuseWith = Acceptance.Refused(Refusal.SITE_NAME_UNKNOWN, "no such name")
            assertEquals(RejectionCode.REJECTION_CODE_PARAMETER_INVALID, w.start().rejection.code)
            w.adapter.refuseWith = Acceptance.Refused(Refusal.VENDOR_SURFACE_ABSENT, "no mission layer")
            assertEquals(RejectionCode.REJECTION_CODE_CAPABILITY_WITHDRAWN, w.start().rejection.code)
            w.adapter.refuseWith = Acceptance.Refused(Refusal.LINK_ERROR, "socket")
            val e = assertFailsWith<StatusRuntimeException> { w.start() }
            assertEquals(Status.Code.UNAVAILABLE, e.status.code)
        }
    }

    @Test
    fun `취소는 어댑터가 없다 하면 CANCEL_UNSUPPORTED 이고, 되면 CANCELLING 뒤 CANCELLED 다`() {
        World().use { w ->
            val handle = w.start().handle
            w.adapter.cancelAnswer = Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "취소 없음")
            val refused = w.tasks.cancelTask(CancelTaskRequest.newBuilder().setHeader(w.header("picasso.v1.CancelTaskRequest")).setHandle(handle).build())
            assertEquals(RejectionCode.REJECTION_CODE_CANCEL_UNSUPPORTED, refused.rejection.code)

            w.adapter.cancelAnswer = Applied.Ok
            val ok = w.tasks.cancelTask(CancelTaskRequest.newBuilder().setHeader(w.header("picasso.v1.CancelTaskRequest")).setHandle(handle).build())
            assertEquals(TaskState.TASK_STATE_CANCELLING, ok.state)
            assertEquals(TaskState.TASK_STATE_CANCELLED, w.watch(handle).last().state)

            val paused = w.tasks.pauseTask(PauseTaskRequest.newBuilder().setHeader(w.header("picasso.v1.PauseTaskRequest")).setHandle(handle).build())
            assertEquals(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, paused.rejection.code, "종착은 래치된다")
        }
    }

    /** 스트림을 열고 받은 것을 모은다. [leave] 는 소비자가 떠나는 것 — 클라이언트 쪽에서 호출을 취소한다. */
    private class Following : ClientResponseObserver<WatchTaskRequest, WatchTaskResponse> {
        private lateinit var call: ClientCallStreamObserver<WatchTaskRequest>
        val received = mutableListOf<WatchTaskResponse>()
        override fun beforeStart(requestStream: ClientCallStreamObserver<WatchTaskRequest>) { call = requestStream }
        override fun onNext(value: WatchTaskResponse) { received += value }
        override fun onError(t: Throwable) = Unit
        override fun onCompleted() = Unit
        fun leave() = call.cancel("소비자가 떠난다", null)
    }

    @Test
    fun `떠난 WatchTask 스트림에는 밀지 않고, 그 일로 다른 RPC 가 깨지지 않는다`() {
        // §15.220 — 취소된 스트림이 표에 남으면 다음 전이를 미는 `onNext` 가 `CANCELLED` 를 던지고, 그 예외가 펌프를 지난
        // 무관한 RPC 의 실패로 나간다. 떠난 쪽을 앞에 두어 같은 태스크의 남은 스트림이 그 뒤에서 갱신을 놓치는지도 본다.
        World().use { w ->
            val handle = w.start().handle
            val request = WatchTaskRequest.newBuilder().setHeader(w.header("picasso.v1.WatchTaskRequest")).setHandle(handle).build()
            val async = TaskServiceGrpc.newStub(w.channel)
            val left = Following().also { async.watchTask(request, it) }
            val stayed = Following().also { async.watchTask(request, it) }
            left.leave()

            w.adapter.next = TaskState.TASK_STATE_SUCCEEDED
            val snapshot = runCatching { w.snapshot() }
            assertNull(snapshot.exceptionOrNull(), "떠난 스트림 때문에 무관한 RPC 가 깨졌다")
            assertEquals(TaskState.TASK_STATE_SUCCEEDED, stayed.received.lastOrNull()?.state, "남은 스트림이 종착을 못 받았다: ${stayed.received.map { it.state }}")
        }
    }

    @Test
    fun `기동 뒤에 들인 기체도 ONLINE 을 알리고 WatchTask 스트림에 갱신을 민다`() {
        // §15.292 — 플릿에 기체가 늘면 런처가 기동한 호스트에 그 기체를 더한다. 맵에만 넣으면 라우팅은 찾지만, 열린 스트림에
        // 미는 쪽이 그 기체에 안 매달려 갱신이 소비자에게 안 간다.
        World().use { w ->
            val second = ScriptedAdapter()
            val other = HostedRobot(
                OTHER, ProfileDocument.parse("test", Files.readString(PROFILE)).getOrThrow(), second,
                publisher = w.published, site = "line-a",
            ) { w.now }
            assertTrue(w.host.admit(other))
            assertFalse(w.host.admit(other), "이미 든 기체를 다시 들였다")

            val online = w.published.topic(Topics.robot(dev.picasso.contracts.wire.ContractIdentity.major, "line-a", OTHER, Topics.Stream.connection))
                .map { (it.message as ConnectionMessage).state }
            assertEquals(listOf(ConnectionState.CONNECTION_STATE_ONLINE), online)

            fun header(schema: String) = RequestHeaders.build(schema, OTHER, "test-client")
            val handle = w.tasks.startTask(
                StartTaskRequest.newBuilder().setHeader(header("picasso.v1.StartTaskRequest"))
                    .setTaskId("T-9").setRevision(1).setRobotId(OTHER).setSkillType("pick_place")
                    .addAllParameters(World.pick.map { (k, v) -> ParameterValue.newBuilder().setKey(k).setStringValue(v).build() })
                    .build(),
            ).handle
            val request = WatchTaskRequest.newBuilder().setHeader(header("picasso.v1.WatchTaskRequest")).setHandle(handle).build()
            val stream = Following().also { TaskServiceGrpc.newStub(w.channel).watchTask(request, it) }

            second.next = TaskState.TASK_STATE_SUCCEEDED
            w.events.getSnapshot(GetSnapshotRequest.newBuilder().setHeader(header("picasso.v1.GetSnapshotRequest")).setRobotId(OTHER).build())
            assertEquals(TaskState.TASK_STATE_SUCCEEDED, stream.received.lastOrNull()?.state, "들인 기체의 갱신이 스트림에 안 밀렸다: ${stream.received.map { it.state }}")
        }
    }

    @Test
    fun `스냅샷은 결함과 태스크와 ONLINE 을 싣고, 결함을 못 봤으면 결함만 비우고 NOT_OBSERVABLE 이다`() {
        World().use { w ->
            val handle = w.start().handle
            val fault = classifiedFault("LOCALIZATION_LOST", FailureClass.FAILURE_CLASS_LOCALIZATION_LOST, "", "재측위", canAcceptNewTask = false)
            w.adapter.observedFaults = FaultObservation.Observed(listOf(fault))
            val snapshot = w.events.getSnapshot(GetSnapshotRequest.newBuilder().setHeader(w.header("picasso.v1.GetSnapshotRequest")).setRobotId(ROBOT).build())
            assertEquals(listOf("LOCALIZATION_LOST"), snapshot.faultsList.map { it.errorType })
            assertEquals(FaultObservability.FAULT_OBSERVABILITY_UNSPECIFIED, snapshot.faultObservability)
            assertEquals(handle.taskId, snapshot.tasksList.single().taskId)
            assertEquals(dev.picasso.contracts.v1.ConnectionState.CONNECTION_STATE_ONLINE, snapshot.connectionState)

            val replay = w.events.replayEvents(ReplayEventsRequest.newBuilder().setHeader(w.header("picasso.v1.ReplayEventsRequest")).setRobotId(ROBOT).setFromSequence(0).build()).asSequence().toList()
            val kinds = replay.map { if (it.event.hasTaskTransition()) "T:${it.event.taskTransition.to.name}" else "F:${it.event.faultEvent.fault.errorType}" }
            assertEquals(listOf("T:TASK_STATE_ACCEPTED", "T:TASK_STATE_RUNNING", "F:LOCALIZATION_LOST"), kinds)
            assertEquals(replay.map { it.event.header.sequence }, replay.map { it.event.header.sequence }.sorted())

            // **못 봤으면 결함만 모른다**(계약 0.10.0). 마지막으로 본 결함을 현재값으로 싣지 않고, 태스크·연결은 그대로 싣는다 —
            // 오류로 돌리면 소비자가 리빌드·연결 검사에 쓸 태스크와 연결까지 잃는다(§15.263).
            w.adapter.observedFaults = FaultObservation.NotObservable("lowstate 없음")
            val blind = w.events.getSnapshot(GetSnapshotRequest.newBuilder().setHeader(w.header("picasso.v1.GetSnapshotRequest")).setRobotId(ROBOT).build())
            assertEquals(FaultObservability.FAULT_OBSERVABILITY_NOT_OBSERVABLE, blind.faultObservability)
            assertTrue(blind.faultsList.isEmpty(), "못 본 결함 자리에 낡은 결함을 실었다: ${blind.faultsList.map { it.errorType }}")
            assertEquals(handle.taskId, blind.tasksList.single().taskId)
            assertEquals(dev.picasso.contracts.v1.ConnectionState.CONNECTION_STATE_ONLINE, blind.connectionState)
        }
    }

    @Test
    fun `스냅샷은 펌프가 읽어 둔 손을 싣고, 못 읽으면 빈손이 아니라 NOT_OBSERVABLE 이다`() {
        // 계약 0.11.0. 도는 태스크가 없어도 손을 싣는다 — 소비자가 든 채 쉬는 기체를 접수 전에 보는 유일한 자리다(§15.150).
        World().use { w ->
            fun snapshot() = w.events.getSnapshot(GetSnapshotRequest.newBuilder().setHeader(w.header("picasso.v1.GetSnapshotRequest")).setRobotId(ROBOT).build())
            assertEquals(HoldKind.HOLD_KIND_EMPTY, snapshot().hold.kind)

            w.adapter.holding = HoldObservation.Holding("BIN-A")
            val held = snapshot().hold
            assertEquals(HoldKind.HOLD_KIND_HOLDING to "BIN-A", held.kind to held.objectRef)

            // 못 읽은 것을 마지막으로 본 값이나 빈손으로 접지 않는다. 스냅샷 자체는 오류가 아니다 — 태스크·연결은 그대로 싣는다.
            w.adapter.holdFailure = IllegalStateException("그리퍼 상태 읽기 실패")
            val blind = snapshot()
            assertEquals(HoldKind.HOLD_KIND_NOT_OBSERVABLE, blind.hold.kind)
            assertTrue("그리퍼 상태 읽기 실패" in blind.hold.reason, blind.hold.reason)
            assertEquals(dev.picasso.contracts.v1.ConnectionState.CONNECTION_STATE_ONLINE, blind.connectionState)
        }
    }

    @Test
    fun `아직 나지 않은 번호를 물으면 SEQUENCE_EVICTED 다`() {
        // §15.288 — 그런 커서는 다시 뜨기 전 호스트의 것이다. 세션이 되풀이되면 소비자가
        // 재시작을 못 보므로, 빈 응답을 주면 새 세션의 앞 이벤트를 조용히 건너뛴다.
        World(Files.readString(PROFILE)).use { w ->
            w.start()
            val next = w.robot.sequence
            fun replay(from: Long) = w.events.replayEvents(ReplayEventsRequest.newBuilder().setHeader(w.header("picasso.v1.ReplayEventsRequest")).setRobotId(ROBOT).setFromSequence(from).build()).asSequence().toList()
            assertTrue(replay(next).none { it.hasRejection() }, "다음 번호는 축출이 아니다")
            val ahead = replay(next + 1)
            assertEquals(RejectionCode.REJECTION_CODE_SEQUENCE_EVICTED, ahead.single().rejection.code)
            assertTrue(ahead.single().rejection.detail.contains("아직 나지 않은 번호"), ahead.single().rejection.detail)
        }
    }

    @Test
    fun `재생 버퍼를 벗어나면 SEQUENCE_EVICTED 다`() {
        val small = Files.readString(PROFILE).replace("\"replay_buffer_size\": 256", "\"replay_buffer_size\": 2")
        World(small).use { w ->
            val handle = w.start().handle // ACCEPTED, RUNNING → 둘
            w.adapter.next = TaskState.TASK_STATE_SUCCEEDED
            w.watch(handle) // SUCCEEDED → 셋째. 버퍼 2 라 첫 이벤트가 버려진다
            // **번호를 가정하지 않는다** — `sequence` 축은 연결·상태 발행과 공유라(§5.5) 이벤트가 0 번이 아니다. 버린 번호는 호스트가 안다.
            val dropped = w.robot.evictedUpTo ?: error("아무것도 안 버렸다")
            val evicted = w.events.replayEvents(ReplayEventsRequest.newBuilder().setHeader(w.header("picasso.v1.ReplayEventsRequest")).setRobotId(ROBOT).setFromSequence(dropped).build()).asSequence().toList()
            assertEquals(RejectionCode.REJECTION_CODE_SEQUENCE_EVICTED, evicted.single().rejection.code)
            val fine = w.events.replayEvents(ReplayEventsRequest.newBuilder().setHeader(w.header("picasso.v1.ReplayEventsRequest")).setRobotId(ROBOT).setFromSequence(dropped + 1).build()).asSequence().toList()
            assertEquals(2, fine.size)
            assertEquals(listOf(TaskState.TASK_STATE_RUNNING, TaskState.TASK_STATE_SUCCEEDED), fine.map { it.event.taskTransition.to })
        }
    }

    @Test
    fun `진행률은 어댑터가 낼 때만 움직이고, 못 재는 기종은 0 에 머문다`() {
        // **`watch` 는 종착까지 막힌다** — 그래서 도는 동안의 갱신은 로그로 본다. 마지막에 종착시키고 나서
        // 계약 면으로 한 번 확인한다.
        World().use { w ->
            val handle = w.start().handle
            val log = { w.robot.task("T-1")?.last ?: error("태스크가 없다") }
            // **못 재는 기종이 기본이다.** 계약에는 *못 잰다* 를 실을 자리가 없어 0 으로 접히고, 그것이 지금의 한계다.
            assertEquals(0.0, log().progress)

            w.adapter.reportedProgress = ProgressObservation.Fraction(0.4, "행동 2/5")
            w.adapter.next = TaskState.TASK_STATE_PAUSED
            w.robot.pump()
            assertEquals(0.4, log().progress, 1e-9)

            // **되감기지 않는다**(§4.4 의 단조 비감소). 플릿이 개수를 다시 세는 날 숫자가 내려갈 수 있다.
            w.adapter.reportedProgress = ProgressObservation.Fraction(0.1, "행동 1/10")
            w.adapter.next = TaskState.TASK_STATE_RUNNING
            w.robot.pump()
            assertEquals(0.4, log().progress, 1e-9)

            // 범위 밖은 자른다 — 벤더 개수가 어긋나도 계약의 0..1 은 지킨다.
            w.adapter.reportedProgress = ProgressObservation.Fraction(7.0, "행동 7/5")
            w.adapter.next = TaskState.TASK_STATE_PAUSED
            w.robot.pump()
            assertEquals(1.0, log().progress, 1e-9)

            w.adapter.next = TaskState.TASK_STATE_SUCCEEDED
            assertEquals(1.0, w.watch(handle).last().progress, 1e-9)
        }
    }

    @Test
    fun `진행률의 근거가 계약에 실린다 — 못 재는 것과 아직 아무것도 안 한 것이 갈린다`() {
        World().use { w ->
            // 못 재는 기체. **`progress = 0.0` 은 값이 아니다** — 계약이 그것을 말할 자리를 갖는다(0.8.0).
            // 근거는 **갱신이 적히는 순간** 정해지므로 접수보다 먼저 정한다.
            w.adapter.reportedProgress = ProgressObservation.NotObservable("국면만 있다")
            val handle = w.start().handle
            w.adapter.next = TaskState.TASK_STATE_SUCCEEDED
            val updates = w.watch(handle)
            val running = updates.first { it.state == TaskState.TASK_STATE_RUNNING }
            assertEquals(ProgressKind.PROGRESS_KIND_NOT_OBSERVABLE, running.progressBasis.kind)
            assertEquals("국면만 있다", running.progressBasis.reason)
            assertEquals(0.0, running.progress)

            // 성공 종착의 1.0 은 셀 것이 없어도 사실이다 — 그때의 근거는 종착이다.
            val done = updates.last()
            assertEquals(ProgressKind.PROGRESS_KIND_MEASURED, done.progressBasis.kind)
            assertEquals(1.0, done.progress)
        }
    }

    @Test
    fun `세는 기체는 무엇을 셌는지까지 싣는다`() {
        World().use { w ->
            w.adapter.reportedProgress = ProgressObservation.Fraction(0.4, "행동 2/5")
            val handle = w.start().handle
            w.adapter.next = TaskState.TASK_STATE_SUCCEEDED
            val running = w.watch(handle).first { it.state == TaskState.TASK_STATE_RUNNING }

            assertEquals(ProgressKind.PROGRESS_KIND_MEASURED, running.progressBasis.kind)
            // **숫자만 내면 그것이 무엇을 센 것인지 아무도 모른다.** 기종마다 세는 단위가 다르다.
            assertEquals("행동 2/5", running.progressBasis.basis)
            assertEquals(0.4, running.progress, 1e-9)
        }
    }

    @Test
    fun `실패로 끝나도 진행률이 되감기지 않는다`() {
        World().use { w ->
            val handle = w.start().handle
            w.adapter.reportedProgress = ProgressObservation.Fraction(0.6, "행동 3/5")
            w.adapter.next = TaskState.TASK_STATE_PAUSED
            w.robot.pump()

            // **실행이 끝나면 셀 것이 없어진다** — 플릿의 실행이 사라지면 어댑터는 못 잰다고 답한다.
            // 그때 마지막 값을 안 들고 있으면 진행률이 0 으로 되감긴다.
            w.adapter.reportedProgress = ProgressObservation.NotObservable("실행이 끝나 셀 것이 없다")

            // 실패는 **거기서 멈춘 것**이지 아무것도 안 한 것이 아니다. 0 으로 되돌리면 되감기이고,
            // 소비자가 재시도 여부를 그 숫자로 가늠할 때 사실을 잃는다.
            w.adapter.next = TaskState.TASK_STATE_FAILED
            val last = w.watch(handle).last()
            assertEquals(TaskState.TASK_STATE_FAILED, last.state)
            assertEquals(0.6, last.progress, 1e-9)
        }
    }

    @Test
    fun `못 보낸 구간이 버퍼에서 밀려나면 세션을 새로 낸다`() {
        // §10.6 — 못 보낸 이벤트가 재생 버퍼에서 밀려나면 그 구간은 소비자에게 **영영** 안 간다. 세션을 새로 내
        // 스냅샷부터 다시 세우게 하는 것이 유일하게 정직한 답이다(미믹의 `EventStream.evict` 와 같은 규율).
        val small = Files.readString(PROFILE).replace("\"replay_buffer_size\": 256", "\"replay_buffer_size\": 2")
        World(small).use { w ->
            val first = w.robot.sessionId
            w.flaky.broken = true
            val handle = w.start().handle // ACCEPTED·RUNNING 이 버퍼에만 남는다
            assertEquals(2, w.robot.events.size)
            assertEquals(first, w.robot.sessionId, "아직 아무것도 안 버렸다")

            w.adapter.next = TaskState.TASK_STATE_SUCCEEDED
            w.watch(handle) // 셋째가 들어오며 못 보낸 첫 이벤트가 밀려난다
            assertNotEquals(first, w.robot.sessionId, "못 보낸 구간을 버렸는데 세션이 그대로다")

            // **소비자는 이것으로 안다.** 새 세션이 응답 헤더로 나가고, 그것이 "너에게 안 간 구간이 있다" 를 알리는
            // 유일한 방법이다.
            assertEquals(w.robot.sessionId, w.snapshot().header.sessionId)

            // **버린 것을 다 센다.** 못 보낸 구간 전체가 버려지므로 축출 경계도 그 끝이어야 한다 — 첫 하나만 적으면
            // 되짚기가 나머지에 대고 "잃은 것 없다" 고 답한다.
            assertEquals(emptyList(), w.robot.events, "옛 구간을 안 비우면 다음 발행이 지난 세션의 이벤트를 민다")
            val lost = w.robot.evictedUpTo ?: error("아무것도 안 버렸다")
            assertEquals(w.robot.sequence - 1, lost, "마지막으로 낸 이벤트까지 버렸는데 축출 경계가 그 앞이다")
            val rejected = w.events.replayEvents(
                ReplayEventsRequest.newBuilder().setHeader(w.header("picasso.v1.ReplayEventsRequest")).setRobotId(ROBOT).setFromSequence(lost).build(),
            ).asSequence().toList()
            assertEquals(RejectionCode.REJECTION_CODE_SEQUENCE_EVICTED, rejected.single().rejection.code)
        }
    }

    @Test
    fun `세션은 기동 시각을 들어 다시 띄운 호스트가 카운터를 처음부터 세어도 앞 세션과 겹치지 않는다`() {
        // 프로세스 카운터만 쓰면 다시 띄울 때마다 같은 세션이 되풀이되어 소비자가 재시작을 못 본다(§15.259). 한 JVM 에서는
        // 카운터가 처음으로 돌아가지 않아 재시작을 흉내 낼 수 없으므로 세션이 기동 시각을 드는지 본다.
        World().use { w ->
            val boot = "host-%013d-".format(w.now.toEpochMilli())
            assertTrue(w.robot.sessionId.startsWith(boot), "세션 ${w.robot.sessionId} 이 기동 시각을 안 든다")
        }
    }

    @Test
    fun `세션은 프로세스 기동 난수를 들어 같은 밀리초에 다시 띄워도 앞 세션과 겹치지 않는다`() {
        // 기동 시각과 카운터만이면 같은 밀리초에 다시 뜨거나 시계가 되감길 때 앞 세션이 되풀이된다(§15.21). 한 JVM 에서는
        // 다시 띄움을 흉내 낼 수 없으므로 세션 끝에 난수가 붙고, 한 프로세스 안에서는 그 값이 같은지 본다.
        val shape = Regex("""host-\d{13}-\d{6}-([0-9a-f]{16})""")
        World().use { a ->
            World().use { b ->
                val first = assertNotNull(shape.matchEntire(a.robot.sessionId), "세션 ${a.robot.sessionId} 이 기동 난수를 안 든다")
                val second = assertNotNull(shape.matchEntire(b.robot.sessionId), "세션 ${b.robot.sessionId} 이 기동 난수를 안 든다")
                assertEquals(first.groupValues[1], second.groupValues[1], "한 프로세스 안에서 기동 난수가 바뀌었다")
                assertNotEquals(a.robot.sessionId, b.robot.sessionId, "같은 시계의 두 호스트가 세션을 겹쳐 썼다")
            }
        }
    }

    @Test
    fun `이미 나간 구간이 밀려나는 것으로는 세션을 안 바꾼다`() {
        // 단절만으로, 또는 다 나간 구간을 버린 것으로 세션을 바꾸면 **버퍼링이 무의미해진다** — 소비자가 매번
        // 스냅샷부터 다시 세우게 된다. 넘칠 때만, 그리고 **못 보낸 것을** 버릴 때만 바꾼다.
        val small = Files.readString(PROFILE).replace("\"replay_buffer_size\": 256", "\"replay_buffer_size\": 2")
        World(small).use { w ->
            val first = w.robot.sessionId
            val handle = w.start().handle
            w.adapter.next = TaskState.TASK_STATE_SUCCEEDED
            w.watch(handle)
            assertNotNull(w.robot.evictedUpTo, "버린 것이 없으면 이 시험은 아무것도 안 본다")
            assertEquals(first, w.robot.sessionId)
            assertEquals(3, w.eventsOut().size, "다 나갔다")
        }
    }

    @Test
    fun `능력은 프로파일의 투영이고, 아는 이름 셋 답은 계약대로 갈린다`() {
        World().use { w ->
            val capability = w.skills.getCapabilities(GetCapabilitiesRequest.newBuilder().setHeader(w.header("picasso.v1.GetCapabilitiesRequest")).setRobotId(ROBOT).build()).capability
            assertEquals(w.robot.document.vendor, capability.vendor)
            assertTrue(capability.skillsList.any { it.skillType == "pick_place" })

            val req = GetKnownSiteNamesRequest.newBuilder().setHeader(w.header("picasso.v1.GetKnownSiteNamesRequest")).setRobotId(ROBOT).build()
            assertEquals(listOf("dock-3", "bay-7"), w.skills.getKnownSiteNames(req).namesList)
            w.adapter.siteNames = SiteNames.Unsupported
            assertTrue(w.skills.getKnownSiteNames(req).unsupported)
            w.adapter.siteNames = SiteNames.Unavailable("graph 못 받음")
            assertEquals(Status.Code.UNAVAILABLE, assertFailsWith<StatusRuntimeException> { w.skills.getKnownSiteNames(req) }.status.code)
        }
    }

    // ── 협상 (§5.4)

    /** 픽스처 프로파일을 만족하는 요구. 여기서 출발해 하나씩 어긴다 — 미믹의 `NegotiateTest` 와 같은 씨앗이다. */
    private fun satisfying(): CapabilityRequirement.Builder = CapabilityRequirement.newBuilder()
        .setClientId("test-client")
        .setRobotId(ROBOT)
        .addRequirements("pick_place@^1.2")
        .addRequirements("navigate_to@^1.0")
        .addOptionalFieldsUsed("task.parameters.verify_grasp")
        .setLimitsNeeded(ProtocolLimits.newBuilder().setMaxStringLength(64).setMaxArrayLength(8))

    private fun World.negotiate(requirement: CapabilityRequirement.Builder): NegotiateResponse =
        skills.negotiate(NegotiateRequest.newBuilder().setHeader(header("picasso.v1.NegotiateRequest")).setRequirement(requirement).build())

    @Test
    fun `협상이 프로파일의 투영에 대고 판정되고, 결과가 보고된다`() {
        World().use { w ->
            val accepted = w.negotiate(satisfying())
            assertTrue(accepted.accepted, accepted.rejectionsList.toString())
            assertEquals(emptyList(), accepted.rejectionsList)
            assertEquals(ROBOT, accepted.header.robotId)

            // **거절도 보고된다** — §5.4 는 성공·실패 **모두** 보고하라 한다. 수락만 보내면 원장은 거절을 못 본다.
            val rejected = w.negotiate(satisfying().clearRequirements().addRequirements("fly@^1.0"))
            assertFalse(rejected.accepted)
            assertEquals(listOf(RejectionCode.REJECTION_CODE_SKILL_ABSENT), rejected.rejectionsList.map { it.code })

            // **거절이 있으면 수락이 아니다 — 거절의 종류와 무관하게.** 수락 여부를 거절 목록과 따로 계산하는 구현은
            // 스킬이 없는 경우만 보는 시험을 통과한다(그 경우에는 두 계산이 우연히 같은 답이다).
            val overLimit = w.negotiate(satisfying().setLimitsNeeded(ProtocolLimits.newBuilder().setMaxStringLength(99999).setMaxArrayLength(8)))
            assertFalse(overLimit.accepted)
            assertEquals(listOf(RejectionCode.REJECTION_CODE_LIMIT_EXCEEDED), overLimit.rejectionsList.map { it.code })

            assertEquals(listOf(true, false, false), w.handshakes.reports.map { it.response.accepted }, "성공·실패 둘 다 보고돼야 한다")
            assertEquals(listOf("line-a", "line-a", "line-a"), w.handshakes.reports.map { it.site }, "토픽의 site 가 보고에 실려야 한다")
        }
    }

    @Test
    fun `레지스트리가 답하지 않는 동안에도 협상과 다른 RPC 가 보고를 기다리지 않는다`() {
        // 협상은 호스트 잠금 아래에서 답한다(§15.220). 보고를 동기로 보내면 레지스트리가 멈춘 동안 이 호스트의 모든 RPC 가
        // 보고 제한(연결 2초, 요청 3초)만큼 선다. 보고자는 큐에 넣기만 해야 한다(§15.266).
        val release = CountDownLatch(1)
        val arrived = AtomicInteger()
        val registry = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/ingest") { exchange ->
                arrived.incrementAndGet()
                exchange.requestBody.readBytes()
                release.await(10, TimeUnit.SECONDS)
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("{}".toByteArray()) }
            }
            executor = Executors.newCachedThreadPool()
            start()
        }
        val dir = Files.createTempDirectory("picasso-host-registry")
        val link = RegistryLink.http("http://127.0.0.1:${registry.address.port}", "t", "line-a", dir)
        try {
            World(reporter = link.reporter).use { w ->
                assertTrue(w.negotiate(satisfying()).accepted)
                val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
                while (arrived.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
                assertTrue(arrived.get() > 0, "보고가 레지스트리에 안 닿았다")

                // 첫 보고가 레지스트리에 서 있는 동안 협상과 스냅숏을 잰다.
                val started = System.nanoTime()
                assertTrue(w.negotiate(satisfying()).accepted)
                w.snapshot()
                val waited = Duration.ofNanos(System.nanoTime() - started)
                assertTrue(waited < Duration.ofMillis(500), "호스트 RPC 가 레지스트리의 답을 기다렸다: $waited")
            }
        } finally {
            release.countDown()
            link.close()
            registry.stop(0)
        }
    }

    @Test
    fun `안 맞는 것을 한 번에 전부 돌려준다`() {
        World().use { w ->
            // 셋을 한꺼번에 어긴다 — 첫 거절에서 끊는 구현은 한 가지씩만 어기는 시험을 전부 통과한다.
            val response = w.negotiate(
                satisfying()
                    .clearRequirements().addRequirements("fly@^1.0").addRequirements("pick_place@^9.0")
                    .clearOptionalFieldsUsed()
                    .setLimitsNeeded(ProtocolLimits.newBuilder().setMaxStringLength(99999).setMaxArrayLength(8)),
            )
            assertFalse(response.accepted)
            assertEquals(
                setOf(
                    RejectionCode.REJECTION_CODE_SKILL_ABSENT,
                    RejectionCode.REJECTION_CODE_MAJOR_MISMATCH,
                    RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING,
                    RejectionCode.REJECTION_CODE_LIMIT_EXCEEDED,
                ),
                response.rejectionsList.map { it.code }.toSet(),
            )
        }
    }

    @Test
    fun `읽지 못한 요구는 거절이 아니라 판정 불가다`() {
        World().use { w ->
            // 캐럿 없는 요구는 문법이 아니다. 거절 목록에 담으면 "능력이 부족하다" 로 보이는데 그것이 아니다.
            val e = assertFailsWith<StatusRuntimeException> {
                w.negotiate(satisfying().clearRequirements().addRequirements("pick_place@1.2").addRequirements("navigate_to"))
            }
            assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
            val detail = e.status.description ?: ""
            // **틀린 것을 전부 열거한다** — 설정 파일의 오타를 한 줄씩 고치며 왕복하게 두지 않는다.
            assertTrue("pick_place@1.2" in detail && "navigate_to" in detail, detail)
            assertEquals(emptyList(), w.handshakes.reports, "판정 불가는 핸드셰이크 결과가 아니라 보고할 것이 없다")
        }
    }

    // ── 발행 (§4.7) — 미믹과 같은 토픽·같은 축

    @Test
    fun `기동하면 ONLINE 이 retain 으로 나가고, 세 스트림이 sequence 축 하나를 쓴다`() {
        World().use { w ->
            // 기동 발행 — 포트가 열린 뒤 하나, retain.
            val online = w.published.topic(w.topic(Topics.Stream.connection)).single()
            assertTrue(online.retained, "connection 은 retain 이어야 새 구독자가 지금 상태를 안다")
            assertEquals(ConnectionState.CONNECTION_STATE_ONLINE, (online.message as ConnectionMessage).state)
            assertEquals(0L, online.sequence)

            val handle = w.start().handle
            w.adapter.next = TaskState.TASK_STATE_SUCCEEDED
            w.watch(handle)

            // 이벤트 스트림 = 재생 버퍼. 같은 객체가 같은 순서로 나갔다.
            assertEquals(w.robot.events, w.eventsOut(), "발행된 이벤트가 재생 버퍼와 다르다")
            assertEquals(listOf(TaskState.TASK_STATE_ACCEPTED, TaskState.TASK_STATE_RUNNING, TaskState.TASK_STATE_SUCCEEDED), w.eventsOut().map { it.taskTransition.to })

            // 축 하나 — 세 토픽을 합쳐 번호가 빈틈 없이 오르고, 헤더의 번호와 Publication 의 번호가 같다.
            val all = w.published.publications
            assertEquals((0L until all.size).toList(), all.map { it.sequence }, "sequence 에 빈틈이나 중복이 있다")
            all.forEach { p ->
                val header = when (val m = p.message) { is Event -> m.header; is StateMessage -> m.header; is ConnectionMessage -> m.header; else -> error(m) }
                assertEquals(p.sequence, header.sequence)
                assertEquals(ROBOT, header.robotId)
                assertEquals(w.robot.sessionId, header.sessionId)
            }
            assertEquals(setOf(w.topic(Topics.Stream.connection), w.topic(Topics.Stream.state), w.topic(Topics.Stream.event)), all.map { it.topic }.toSet())
        }
    }

    @Test
    fun `상태는 프로파일의 최대 발행 간격마다 나가고 현재값을 싣는다`() {
        World().use { w ->
            val handle = w.start().handle
            val first = w.states()
            assertEquals(1, first.size, "첫 펌프가 상태를 한 번 낸다")
            // 첫 펌프는 접수 RPC 의 **진입**에서 돌았다(모든 RPC 가 펌프를 먼저 지난다) — 그때는 아직 태스크가 없다.
            // 전이는 이벤트가 나른다; 상태는 현재값이라 다음 간격에 잡힌다(§3.5).
            assertEquals(emptyList(), first.single().tasksList)

            // 간격(30초) 안 — 펌프가 지나도 안 나간다.
            w.now = w.now.plusSeconds(10); w.snapshot()
            assertEquals(1, w.states().size, "간격 안에 상태가 또 나갔다")

            // 간격을 넘기면 현재값 — 그 사이 종착했으면 종착으로.
            w.adapter.next = TaskState.TASK_STATE_SUCCEEDED
            w.watch(handle)
            w.now = w.now.plusSeconds(25); w.snapshot()
            assertEquals(2, w.states().size, "간격을 넘겼는데 상태가 안 나갔다")
            assertEquals(TaskState.TASK_STATE_SUCCEEDED, w.states().last().tasksList.single().state)
            assertTrue(w.states().last().header.sequence > first.single().header.sequence)
        }
    }

    @Test
    fun `발행이 막히면 이벤트는 재생 버퍼에 남고 다음 발행 때 순서대로 밀린다`() {
        World().use { w ->
            w.flaky.broken = true
            val handle = w.start().handle // ACCEPTED·RUNNING 이 못 나간다. 상태 발행도 막히지만 그것은 버린다.
            assertEquals(emptyList(), w.eventsOut())
            assertEquals(2, w.robot.events.size, "발행이 막혀도 재생 버퍼에는 남아야 한다")
            val stuck = w.robot.unsentFrom ?: error("못 보낸 경계가 안 적혔다")
            assertEquals(w.robot.events.first().header.sequence, stuck)

            w.flaky.broken = false
            w.adapter.next = TaskState.TASK_STATE_SUCCEEDED
            w.watch(handle) // SUCCEEDED 가 적히며 밀린 것부터 순서대로 나간다
            assertEquals(listOf(TaskState.TASK_STATE_ACCEPTED, TaskState.TASK_STATE_RUNNING, TaskState.TASK_STATE_SUCCEEDED), w.eventsOut().map { it.taskTransition.to })
            assertEquals(w.eventsOut().map { it.header.sequence }, w.eventsOut().map { it.header.sequence }.sorted())
            assertNull(w.robot.unsentFrom)
        }
    }

    @Test
    fun `포트가 열린 뒤에 ONLINE 이 나간다`() {
        // §10.2 의 기동 순서. **포트가 안 열렸는데 온라인이라 알리면 소비자가 붙을 수 없는 기체를 살아 있다고
        // 읽는다.** 순서를 묻는 유일한 방법이 실 포트다 — `Server.getPort()` 가 기동 전에는 던지기 때문이다.
        var host: AdapterHost? = null
        var portWhenAnnounced: Int? = null
        val watcher = Publisher { publication ->
            if (publication.retained && portWhenAnnounced == null) {
                portWhenAnnounced = runCatching { host?.port }.getOrNull()
            }
        }

        val robot = HostedRobot(ROBOT, ProfileDocument.parse("test", Files.readString(PROFILE)).getOrThrow(), ScriptedAdapter(), publisher = watcher)
        host = AdapterHost(robot, io.grpc.ServerBuilder.forPort(0))
        try {
            host.start()
            // 널이면 그때 포트가 아직 없었다는 뜻이다 — 즉 알림이 먼저 나갔다.
            assertEquals(host.port, portWhenAnnounced, "ONLINE 이 포트보다 먼저 나갔다")
            assertTrue(host.port > 0)
        } finally {
            host.shutdown()
        }
    }

    @Test
    fun `닫으면 OFFLINE 을 retain 으로 남긴다`() {
        val w = World()
        w.close()
        val last = w.published.topic(w.topic(Topics.Stream.connection)).last()
        assertEquals(ConnectionState.CONNECTION_STATE_OFFLINE, (last.message as ConnectionMessage).state)
        assertTrue(last.retained)
        assertFalse(w.robot.publishStateIfDue(), "OFFLINE 뒤에는 상태가 안 나간다 — 그 침묵이 §4.7 이다")
    }

    @Test
    fun `생존 보고의 사이트 이름 요약은 어댑터의 세 답을 접지 않는다`() {
        val adapter = ScriptedAdapter()
        adapter.siteNames = SiteNames.Known(listOf("dock-3", "bay-7"))
        assertEquals(dev.picasso.uplink.report.SiteNameSummary(unsupported = false, count = 2), HostUplink.siteNames(adapter))
        adapter.siteNames = SiteNames.Unsupported
        assertEquals(dev.picasso.uplink.report.SiteNameSummary(unsupported = true, count = 0), HostUplink.siteNames(adapter))
        // 못 물어봤다 — 0 개도 못 함도 아니다. 레지스트리는 널을 "이미 받은 답을 지우지 않는다" 로 다룬다.
        adapter.siteNames = SiteNames.Unavailable("graph 못 받음")
        assertNull(HostUplink.siteNames(adapter))
    }

    @Test
    fun `다른 기체를 지목하면 라우팅 실패이지 거절이 아니다`() {
        World().use { w ->
            val e = assertFailsWith<StatusRuntimeException> {
                w.tasks.startTask(StartTaskRequest.newBuilder().setHeader(RequestHeaders.build("picasso.v1.StartTaskRequest", "someone-else", "c")).setTaskId("T").setRevision(1).setSkillType("pick_place").build())
            }
            assertEquals(Status.Code.NOT_FOUND, e.status.code)
        }
    }

    private companion object {
        const val ROBOT = "fake-01"
        const val OTHER = "fake-02"
        val PROFILE: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
        /** minimal 에 `navigate_to: HOLD requires EMPTY` 를 더한 것 — 사전 조건 시험의 프로파일. */
        val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()
    }

    // ── 사전 조건 (설계안 §3 — 물리 동작 전, 남쪽 호출 전에 거절한다)

    // ── 자리 결속의 기준 지도 판(§15.155)

    @Test
    fun `정본을 안 붙이면 자리 판을 묻지 않는다`() {
        // 정본 없는 배치를 통째로 멈추면 이 검사가 곧 꺼진다. 안 붙인 것도 명시된 상태다.
        World().use { w ->
            assertTrue(w.start(taskId = "T-nav", skill = "navigate_to", params = mapOf("location" to "dock-3")).hasHandle())
        }
    }

    @Test
    fun `옛 판에서 배운 자리로는 보내지 않는다`() {
        // 지도가 갱신되면 같은 이름이 다른 자리를 가리킨다. **푸는 것보다 먼저 막는다** —
        // 뒤에 두면 이미 옛 좌표로 움직인 뒤에 판을 보게 된다.
        World().use { w ->
            w.bindings.active = ActiveRevision.Known("map-8")
            w.bindings.table["dock-3"] = "map-7"
            val response = w.start(taskId = "T-nav", skill = "navigate_to", params = mapOf("location" to "dock-3"))

            assertEquals(RejectionCode.REJECTION_CODE_PRECONDITION_UNMET, response.rejection.code, response.rejection.toString())
            assertTrue("map-7" in response.rejection.detail && "map-8" in response.rejection.detail, response.rejection.detail)
            assertEquals(emptyList(), w.adapter.accepted, "판이 어긋난 자리가 남쪽 호출까지 갔다")
        }
    }

    @Test
    fun `재등록하면 다시 흐른다`() {
        // 막는 것이 이름이 아니라 판이라는 것 — 판을 맞추면 같은 요청이 그대로 통과한다.
        World().use { w ->
            w.bindings.active = ActiveRevision.Known("map-8")
            w.bindings.table["dock-3"] = "map-7"
            assertFalse(w.start(taskId = "T-nav", skill = "navigate_to", params = mapOf("location" to "dock-3")).hasHandle())

            w.bindings.table["dock-3"] = "map-8"
            assertTrue(w.start(taskId = "T-nav2", skill = "navigate_to", params = mapOf("location" to "dock-3")).hasHandle())
        }
    }

    @Test
    fun `정본이 모르는 이름은 파라미터가 틀린 것이다`() {
        // 어느 판에서 배운 것인지 알 근거가 없다. 기체가 우연히 풀 수도 있으나 **확인 못 한 것을
        // 통과로 접지 않는다.** 자리 이름이 틀린 것과 같은 자리로 낸다(`SITE_NAME_UNKNOWN` 과 같다).
        World().use { w ->
            w.bindings.active = ActiveRevision.Known("map-8")
            val response = w.start(taskId = "T-nav", skill = "navigate_to", params = mapOf("location" to "dock-3"))

            assertEquals(RejectionCode.REJECTION_CODE_PARAMETER_INVALID, response.rejection.code, response.rejection.toString())
            assertEquals(emptyList(), w.adapter.accepted)
        }
    }

    @Test
    fun `정본에 못 물어보면 명령을 내지 않는다`() {
        // **모르면 멈춘다.** 빈 답을 «판이 같다» 로 접으면 지도가 바뀐 뒤에도 명령이 계속 나간다.
        // 요청이 틀린 것이 아니라 우리 쪽 상류가 안 닿는 것이라 거절이 아니라 gRPC 상태다.
        World().use { w ->
            w.bindings.active = ActiveRevision.Unavailable("정본 응답 없음")
            val thrown = assertFailsWith<StatusRuntimeException> {
                w.start(taskId = "T-nav", skill = "navigate_to", params = mapOf("location" to "dock-3"))
            }
            assertEquals(Status.Code.UNAVAILABLE, thrown.status.code, thrown.message)
            assertEquals(emptyList(), w.adapter.accepted)
        }
    }

    @Test
    fun `개체를 교체하면 같은 지도라도 막힌다`() {
        // **지도 축만으로는 통과하던 경우다.** 기체를 교체하고 세계 모델을 복원해도 티칭 기준이 달라
        // 같은 이름이 다른 자세를 뜻한다. 계약의 자리는 지도 축과 같다 — 소비자는 요청을 고치는 것이
        // 아니라 재티칭을 기다린다.
        World().use { w ->
            w.bindings.active = ActiveRevision.Known("map-8")
            w.bindings.calibration = ActiveRevision.Known("cal-2")
            w.bindings.table["dock-3"] = "map-8"
            w.bindings.taught["dock-3"] = "cal-1"

            val response = w.start(taskId = "T-nav", skill = "navigate_to", params = mapOf("location" to "dock-3"))
            assertEquals(RejectionCode.REJECTION_CODE_PRECONDITION_UNMET, response.rejection.code, response.rejection.toString())
            assertTrue("캘리브레이션" in response.rejection.detail, response.rejection.detail)
            assertEquals(emptyList(), w.adapter.accepted, "개체 판이 어긋난 자리가 남쪽 호출까지 갔다")

            // 재티칭하면 다시 흐른다 — 막은 것이 이름이 아니라 판이다.
            w.bindings.taught["dock-3"] = "cal-2"
            assertTrue(w.start(taskId = "T-nav2", skill = "navigate_to", params = mapOf("location" to "dock-3")).hasHandle())
        }
    }

    @Test
    fun `자리 둘을 나르는 단위는 둘 다 이 판의 것이어야 한다`() {
        // `pick_place` 는 대상과 목적지를 함께 나른다. 하나만 보면 나머지가 옛 판인 채로 나간다.
        World().use { w ->
            w.bindings.active = ActiveRevision.Known("map-8")
            w.bindings.table["tote-7"] = "map-8"
            w.bindings.table["rack-1"] = "map-7"
            val response = w.start(
                taskId = "T-pp", skill = "pick_place",
                params = mapOf("object_id" to "tote-7", "destination" to "rack-1"),
            )
            assertEquals(RejectionCode.REJECTION_CODE_PRECONDITION_UNMET, response.rejection.code, response.rejection.toString())
            assertTrue("rack-1" in response.rejection.detail, response.rejection.detail)
        }
    }

    @Test
    fun `쥔 채로 온 요청은 adapter accept 를 부르지 않는다`() {
        World(profileJson = Files.readString(PRECOND)).use { w ->
            w.adapter.holding = HoldObservation.Holding("tote-7")
            val response = w.start(taskId = "T-nav", skill = "navigate_to", params = mapOf("location" to "dock-3"))
            assertEquals(RejectionCode.REJECTION_CODE_PRECONDITION_UNMET, response.rejection.code, response.rejection.toString())
            assertEquals(
                listOf("HOLD"),
                response.rejection.referencesList.filter { it.key == Reference.Key.KEY_PRECONDITION_SUBJECT }.map { it.value },
                "어느 조건인지 참조로 실려야 한다",
            )
            assertEquals(emptyList(), w.adapter.accepted, "조건 위반이 남쪽 호출까지 갔다")
            // 빈손이면 같은 요청이 접수된다 — 막은 것은 조건이지 스킬이 아니다.
            w.adapter.holding = HoldObservation.Empty
            assertTrue(w.start(taskId = "T-nav", skill = "navigate_to", params = mapOf("location" to "dock-3")).hasHandle())
        }
    }

    @Test
    fun `관측 불가는 조건이 있는 스킬만 막는다`() {
        World(profileJson = Files.readString(PRECOND)).use { w ->
            // 빈손이 아니다 — 못 봤을 뿐이다. 조건이 있으면 접수하지 않는다(설계안 §3.2).
            w.adapter.holding = HoldObservation.NotObservable("벤더가 파지 판정을 안 준다")
            val nav = w.start(taskId = "T-nav", skill = "navigate_to", params = mapOf("location" to "dock-3"))
            assertEquals(RejectionCode.REJECTION_CODE_PRECONDITION_UNMET, nav.rejection.code, nav.rejection.toString())
            assertTrue("벤더가 파지 판정을 안 준다" in nav.rejection.detail, nav.rejection.detail)
            // 조건이 없는 스킬은 관측 불가여도 접수된다 — 선언하지 않은 조건은 제약 없음이다(설계안 §6).
            assertTrue(w.start(taskId = "T-pick").hasHandle())
        }
    }

    @Test
    fun `벤더가 PRECONDITION_FAILED 로 거절하면 PRECONDITION_UNMET 이다`() {
        World().use { w ->
            // 선언된 조건은 벤더 거절을 대체하지 않는다(설계안 §3.4) — 다만 벤더가 "지금 못 받는다" 고 답하면
            // 그것도 같은 자리다. 지금까지 INVALID_TRANSITION 에 사정을 붙여 냈다(§15.98 의 정직 메모).
            w.adapter.refuseWith = Acceptance.Refused(
                Refusal.VENDOR_REJECTED, "로봇이 그 명령을 받을 상태가 아니다",
                failureClass = FailureClass.FAILURE_CLASS_PRECONDITION_FAILED, vendorDetail = "LOCOSTATE_NOT_AVAILABLE",
            )
            val response = w.start()
            assertEquals(RejectionCode.REJECTION_CODE_PRECONDITION_UNMET, response.rejection.code, response.rejection.toString())
            assertTrue("PRECONDITION_FAILED" in response.rejection.detail, response.rejection.detail)
            // 벤더 원천에는 선언된 주어가 없다 — 참조는 비고 detail 의 [class=…; vendor=…] 가 원천을 말한다(계약 주석과 같다).
            assertTrue(response.rejection.referencesList.none { it.key == Reference.Key.KEY_PRECONDITION_SUBJECT }, response.rejection.toString())
            assertTrue("[class=PRECONDITION_FAILED" in response.rejection.detail, response.rejection.detail)
            // 다른 분류의 벤더 거절은 그대로다 — 자리가 생긴 것은 사전 조건뿐이다.
            w.adapter.refuseWith = Acceptance.Refused(Refusal.VENDOR_REJECTED, "x", failureClass = FailureClass.FAILURE_CLASS_HARDWARE_FAULT)
            assertEquals(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, w.start(taskId = "T-2").rejection.code)
        }
    }
}

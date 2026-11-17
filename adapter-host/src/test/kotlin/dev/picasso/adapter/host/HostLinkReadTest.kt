package dev.picasso.adapter.host

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.ProgressObservation
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.adapter.core.SiteNames
import dev.picasso.contracts.v1.CapabilityRequirement
import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.ProtocolLimits
import dev.picasso.contracts.v1.SkillServiceGrpc
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.wire.RequestHeaders
import dev.picasso.profile.ProfileDocument
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.ServerBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 펌프의 사우스바운드 읽기는 호스트 잠금 밖에서 돈다(§15.268).
 *
 * 플릿이 답하지 않는 동안 펌프의 읽기는 링크 잠금만 쥐고 선다. 링크를 안 쓰는 RPC 는 그것을 기다리지 않아야 하고, 그 사이 다른
 * 손이 적은 것을 늦게 끝난 읽기가 덮지 않아야 한다.
 */
class HostLinkReadTest {

    /** [holding] 인 동안 `poll` 이 [release] 를 기다리는 어댑터. `poll` 은 [next] 가 있으면 그 상태로 옮긴다. */
    private class GatedAdapter : RobotAdapter {
        @Volatile var current: TaskState = TaskState.TASK_STATE_UNSPECIFIED
        @Volatile var next: TaskState? = null
        @Volatile var faultList: List<Fault> = emptyList()
        val holding = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override val state: TaskState get() = current
        override fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance {
            current = TaskState.TASK_STATE_RUNNING
            return Acceptance.Accepted(taskId)
        }
        override fun poll(now: Instant): TaskState {
            if (holding.get()) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            next?.let { current = it }
            return current
        }
        override fun pause(): Applied = Applied.Ok
        override fun cancel(): Applied {
            current = TaskState.TASK_STATE_CANCELLED
            return Applied.Ok
        }
        override fun hold(): HoldObservation = HoldObservation.Empty
        override fun faults(): FaultObservation = FaultObservation.Observed(faultList)
        override fun knownSiteNames(): SiteNames = SiteNames.Unsupported
    }

    private fun robot(adapter: RobotAdapter) =
        HostedRobot(ROBOT, ProfileDocument.parse("test", Files.readString(PROFILE)).getOrThrow(), adapter)

    private class World : AutoCloseable {
        val adapter = GatedAdapter()
        val robot = HostedRobot(ROBOT, ProfileDocument.parse("test", Files.readString(PROFILE)).getOrThrow(), adapter)

        // 실 포트, 펌프 스레드가 있는 배치의 모양 — 런처가 펌프 스레드를 세우면 읽기 RPC 의 펌프를 끈다.
        val host = AdapterHost(robot, ServerBuilder.forPort(0), pumpOnRead = false).start()
        val channel: ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", host.port).usePlaintext().build()
        val tasks: TaskServiceGrpc.TaskServiceBlockingStub = TaskServiceGrpc.newBlockingStub(channel)
        val events: EventServiceGrpc.EventServiceBlockingStub = EventServiceGrpc.newBlockingStub(channel)
        val skills: SkillServiceGrpc.SkillServiceBlockingStub = SkillServiceGrpc.newBlockingStub(channel)

        fun header(schema: String) = RequestHeaders.build(schema, ROBOT, "test-client")

        fun start() = tasks.withDeadlineAfter(5, TimeUnit.SECONDS).startTask(
            StartTaskRequest.newBuilder().setHeader(header("picasso.v1.StartTaskRequest"))
                .setTaskId("T-1").setRevision(1).setRobotId(ROBOT).setSkillType("pick_place")
                .addAllParameters(PICK.map { (k, v) -> ParameterValue.newBuilder().setKey(k).setStringValue(v).build() })
                .build(),
        )

        fun snapshot() = events.withDeadlineAfter(2, TimeUnit.SECONDS)
            .getSnapshot(GetSnapshotRequest.newBuilder().setHeader(header("picasso.v1.GetSnapshotRequest")).setRobotId(ROBOT).build())

        fun negotiate() = skills.withDeadlineAfter(2, TimeUnit.SECONDS).negotiate(
            NegotiateRequest.newBuilder().setHeader(header("picasso.v1.NegotiateRequest")).setRequirement(
                CapabilityRequirement.newBuilder().setClientId("test-client").setRobotId(ROBOT)
                    .addRequirements("pick_place@^1.2").addRequirements("navigate_to@^1.0")
                    .addOptionalFieldsUsed("task.parameters.verify_grasp")
                    .setLimitsNeeded(ProtocolLimits.newBuilder().setMaxStringLength(64).setMaxArrayLength(8)),
            ).build(),
        )

        /** 도는 태스크를 두고 펌프의 읽기를 플릿 앞에 세운 채 [block] 을 돈다. */
        fun <T> whileReadHeld(block: () -> T): T {
            assertTrue(start().hasHandle())
            adapter.holding.set(true)
            val pump = thread(name = "pump") { runCatching { host.pump() } }
            try {
                assertTrue(adapter.entered.await(5, TimeUnit.SECONDS), "펌프가 남쪽 읽기에 들지 않았다")
                return block()
            } finally {
                adapter.release.countDown()
                pump.join(10_000)
            }
        }

        override fun close() {
            adapter.release.countDown()
            channel.shutdownNow()
            host.shutdown()
        }
    }

    private fun timed(block: () -> Unit): Pair<Duration, Throwable?> {
        val started = System.nanoTime()
        val failure = runCatching(block).exceptionOrNull()
        return Duration.ofNanos(System.nanoTime() - started) to failure
    }

    @Test
    fun `펌프가 남쪽을 읽는 동안에도 스냅숏은 그 읽기를 기다리지 않는다`() {
        World().use { w ->
            val (waited, failure) = w.whileReadHeld { timed { w.snapshot() } }
            assertTrue(failure == null && waited < Duration.ofMillis(500), "스냅숏이 펌프의 남쪽 읽기를 기다렸다: $waited ($failure)")
        }
    }

    @Test
    fun `펌프가 남쪽을 읽는 동안에도 협상은 그 읽기를 기다리지 않는다`() {
        World().use { w ->
            val (waited, failure) = w.whileReadHeld { timed { assertTrue(w.negotiate().accepted) } }
            assertTrue(failure == null && waited < Duration.ofMillis(500), "협상이 펌프의 남쪽 읽기를 기다렸다: $waited ($failure)")
        }
    }

    @Test
    fun `읽는 동안 취소가 종착을 적으면 그 읽기의 전이는 버린다`() {
        val adapter = GatedAdapter()
        val robot = robot(adapter)
        val task = (robot.start("T-1", 1, "pick_place", PICK) as HostedRobot.StartOutcome.Accepted).task
        assertEquals(TaskState.TASK_STATE_RUNNING, task.last.state)

        // 펌프가 읽을 자리를 정하고 플릿에 묻는다 — 그 답은 성공이다.
        val plan = robot.plan()
        adapter.next = TaskState.TASK_STATE_SUCCEEDED
        val reading = robot.read(plan)
        adapter.next = null

        // 그 답을 적기 전에 명령이 취소를 적는다.
        assertEquals(Applied.Ok, robot.adapter.cancel())
        robot.syncCurrent()
        robot.apply(reading)

        assertEquals(
            listOf(TaskState.TASK_STATE_ACCEPTED, TaskState.TASK_STATE_RUNNING, TaskState.TASK_STATE_CANCELLED),
            task.updates.map { it.state },
            "종착 뒤에 앞서 읽은 전이를 적었다",
        )
    }

    @Test
    fun `늦게 끝난 앞 읽기는 뒤 읽기가 적은 결함 표를 되돌리지 않는다`() {
        val adapter = GatedAdapter()
        val robot = robot(adapter)
        adapter.faultList = listOf(FAULT)
        val earlier = robot.read(robot.plan())
        adapter.faultList = emptyList()
        val later = robot.read(robot.plan())

        // 펌프 스레드의 읽기가 호스트 잠금을 기다리는 동안 명령 경로의 펌프가 더 새 것을 먼저 적은 차례다.
        robot.apply(later)
        robot.apply(earlier)

        assertEquals(emptyMap(), robot.faults, "앞 읽기가 뒤 읽기의 결함 표를 옛 값으로 되돌렸다")
    }

    /** 멤버마다 링크 잠금을 쥔 채 불렸는지 적는 어댑터. */
    private class LockProbe(val lock: () -> Any) : RobotAdapter {
        val held = linkedMapOf<String, Boolean>()
        private fun <T> seen(name: String, value: T): T {
            held[name] = Thread.holdsLock(lock())
            return value
        }

        override val state: TaskState get() = seen("state", TaskState.TASK_STATE_RUNNING)
        override fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance =
            seen("accept", Acceptance.Accepted(taskId))
        override fun poll(now: Instant): TaskState = seen("poll", TaskState.TASK_STATE_RUNNING)
        override fun pause(): Applied = seen("pause", Applied.Ok)
        override fun resume(parameters: Map<String, Any>): Applied = seen("resume", Applied.Ok)
        override fun cancel(): Applied = seen("cancel", Applied.Ok)
        override fun update(taskId: String, skillType: String, parameters: Map<String, Any>, at: Instant): Applied = seen("update", Applied.Ok)
        override fun retry(parameters: Map<String, Any>): Applied = seen("retry", Applied.Ok)
        override fun hold(): HoldObservation = seen("hold", HoldObservation.Empty)
        override fun progress(): ProgressObservation = seen("progress", ProgressObservation.NotObservable("시험"))
        override fun faults(): FaultObservation = seen("faults", FaultObservation.Observed(emptyList()))
        override fun failure(): Fault? = seen("failure", null)
        override fun result(): String? = seen("result", null)
        override fun knownSiteNames(): SiteNames = seen("knownSiteNames", SiteNames.Unsupported)
        override fun robotSoftware(): String? = seen("robotSoftware", null)
        override val pollObservesVendor: Boolean get() = seen("getPollObservesVendor", false)
    }

    @Test
    fun `어댑터 호출은 기본 구현이 있는 것까지 전부 링크 잠금을 쥐고 간다`() {
        lateinit var robot: HostedRobot
        val probe = LockProbe { robot.southbound }
        robot = robot(probe)
        val a = robot.adapter
        val at = Instant.EPOCH
        a.state; a.accept("T", "pick_place", emptyMap(), at); a.poll(at); a.pause(); a.resume(emptyMap()); a.cancel()
        a.update("T", "pick_place", emptyMap(), at); a.retry(emptyMap()); a.hold(); a.progress(); a.faults(); a.failure()
        a.result(); a.knownSiteNames(); a.robotSoftware(); a.pollObservesVendor

        // 포트에 멤버가 늘면 여기도 는다 — 껍질이 그것을 넘기지 않으면 아래 대조가 잡는다.
        val members = RobotAdapter::class.java.declaredMethods.filterNot { it.isSynthetic }
            .map { if (it.name == "getState") "state" else it.name }.toSet()
        assertEquals(members, probe.held.keys, "껍질이 넘기지 않은 멤버가 있다")
        assertEquals(emptyList(), probe.held.filterValues { !it }.keys.toList(), "링크 잠금 없이 부른 멤버")
    }

    private companion object {
        const val ROBOT = "fake-01"
        val PROFILE: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
        val PICK = mapOf("object_id" to "SEQ-IN-02.BIN-A", "destination" to "RACK-204.S01")
        val FAULT: Fault = Fault.newBuilder().setErrorType("SKILL_EXECUTION_FAILED").build()
    }
}

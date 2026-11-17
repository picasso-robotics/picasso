package dev.picasso.adapter.host

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.adapter.core.SiteNames
import dev.picasso.contracts.v1.CancelTaskRequest
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.FenceStatus
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.GetSnapshotResponse
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.wire.ControllerFence
import dev.picasso.contracts.wire.EpochAdmission
import dev.picasso.contracts.wire.FenceDurability
import dev.picasso.contracts.wire.FenceMode
import dev.picasso.contracts.wire.FenceStore
import dev.picasso.contracts.wire.FencedCall
import dev.picasso.contracts.wire.RequestHeaders
import dev.picasso.profile.ProfileDocument
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **어댑터 호스트의 인수 확인과 경합**(ADR 54). 두 수신자가 같이 하는 규칙은 `harness` 의 `ControllerFenceParityTest` 가 대고, 여기는
 * 호스트만의 것을 댄다 — 명령이 호스트 잠금 밖에서 벤더로 가므로 «입구를 지났으나 아직 안 보낸 옛 명령» 이 있을 수 있다.
 *
 * 인수 확인(`GetSnapshot`, 새 세대)이 답한 뒤에는 낮은 세대의 명령이 벤더에 **새로** 닿지 않아야 하고, 이미 닿은 것은 장부에 반영돼
 * 그 스냅샷에 있어야 한다. 옛 명령을 네 자리 — 입구 앞(차선 대기), 장부 자리를 잡기 전(호스트 잠금 대기), 링크 대기, 벤더 호출 중(장부
 * 반영 직전) — 에 세운 채 새 세대가 확인을 묻는다. 시험은 in-process 채널이고 호출마다 제 스레드에서 돈다.
 */
class HostControllerFenceTest {

    /** 닫혀 있으면 지나는 쪽을 [open] 까지 세운다. */
    private class Gate {
        val closed = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        private val release = CountDownLatch(1)

        fun pass() {
            if (!closed.get()) return
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }

        fun open() = release.countDown()
    }

    private class Adapter : RobotAdapter {
        val accepts = AtomicInteger()
        val cancels = AtomicInteger()
        val accept = Gate()
        val updates = AtomicInteger()
        val update = Gate()

        /** [blockHoldAt] 번째 `hold` 를 [holdGate] 에 세운다 — 사전 조건의 관측이다(펌프의 읽기도 `hold` 를 부른다). */
        val holdCalls = AtomicInteger()
        @Volatile var blockHoldAt = -1
        val holdGate = Gate()

        @Volatile var refuse: Refusal? = null

        @Volatile var current: TaskState = TaskState.TASK_STATE_UNSPECIFIED

        override val state: TaskState get() = current
        override fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance {
            refuse?.let { return Acceptance.Refused(it, "시험: $it") }
            accepts.incrementAndGet()
            accept.pass()
            current = TaskState.TASK_STATE_RUNNING
            return Acceptance.Accepted(taskId)
        }
        /** 펌프의 `poll` 이 벤더 관측이라고 말하는가 — 결과를 모르는 조작을 종착으로 걷는 근거다. */
        @Volatile var vendorPoll = false
        override val pollObservesVendor: Boolean get() = vendorPoll

        @Volatile var updateRefuse: Refusal? = null

        override fun update(taskId: String, skillType: String, parameters: Map<String, Any>, at: Instant): Applied {
            updates.incrementAndGet()
            updateRefuse?.let { return Applied.Refused(it, "시험: $it") }
            update.pass()
            return Applied.Ok
        }
        val polls = AtomicInteger()
        override fun poll(now: Instant): TaskState {
            polls.incrementAndGet()
            return current
        }
        override fun pause(): Applied = Applied.Ok
        @Volatile var cancelRefuse: Refusal? = null

        override fun cancel(): Applied {
            cancelRefuse?.let { return Applied.Refused(it, "시험: $it") }
            cancels.incrementAndGet()
            current = TaskState.TASK_STATE_CANCELLED
            return Applied.Ok
        }
        override fun hold(): HoldObservation {
            if (holdCalls.incrementAndGet() == blockHoldAt) {
                holdGate.closed.set(true)
                holdGate.pass()
            }
            return HoldObservation.Empty
        }
        override fun faults(): FaultObservation = FaultObservation.Observed(emptyList())
        override fun knownSiteNames(): SiteNames = SiteNames.Unsupported
    }

    /** 세대 1 의 출발이 입구를 지나는 순간을 알리고, [go] 가 열릴 때까지 그 자리에 세운다(그동안 대조의 기체 잠금은 쥔 채다). */
    private class Admission : EpochAdmission {
        val holdOld = AtomicBoolean(false)
        val admitted = CountDownLatch(1)
        val go = CountDownLatch(1)
        override fun mayRaise(robotId: String, epoch: Long, call: FencedCall): Boolean {
            if (holdOld.get() && call == FencedCall.START && epoch == 1L) {
                admitted.countDown()
                go.await(10, TimeUnit.SECONDS)
            }
            return true
        }
    }

    private class World(
        store: FenceStore = FenceStore.memory(),
        fenceWait: Duration = Duration.ofSeconds(5),
        val admission: Admission = Admission(),
        profile: Path = PROFILE,
    ) : AutoCloseable {
        val adapter = Adapter()
        val robot = HostedRobot(ROBOT, ProfileDocument.parse("test", Files.readString(profile)).getOrThrow(), adapter)
        private val name = InProcessServerBuilder.generateName()
        val host = AdapterHost(
            robot, InProcessServerBuilder.forName(name).directExecutor(), pumpOnRead = false,
            fence = ControllerFence(FenceMode.FENCED, store, admission), fenceWait = fenceWait,
        ).start()
        val channel: ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()
        private val tasks = TaskServiceGrpc.newBlockingStub(channel)
        private val events = EventServiceGrpc.newBlockingStub(channel)

        fun header(schema: String, epoch: Long) = RequestHeaders.build(schema, ROBOT, "test-client", epoch)

        fun start(taskId: String, epoch: Long, revision: Int = 1): StartTaskResponse = tasks.withDeadlineAfter(10, TimeUnit.SECONDS).startTask(
            StartTaskRequest.newBuilder().setHeader(header("picasso.v1.StartTaskRequest", epoch))
                .setTaskId(taskId).setRevision(revision).setRobotId(ROBOT).setSkillType("pick_place")
                .addAllParameters(PICK.map { (k, v) -> ParameterValue.newBuilder().setKey(k).setStringValue(v).build() })
                .build(),
        )

        fun navigate(taskId: String, epoch: Long): StartTaskResponse = tasks.withDeadlineAfter(10, TimeUnit.SECONDS).startTask(
            StartTaskRequest.newBuilder().setHeader(header("picasso.v1.StartTaskRequest", epoch))
                .setTaskId(taskId).setRevision(1).setRobotId(ROBOT).setSkillType("navigate_to")
                .addParameters(ParameterValue.newBuilder().setKey("location").setStringValue("RACK-204.S01"))
                .build(),
        )

        fun cancel(taskId: String, epoch: Long): CancelTaskResponse = tasks.withDeadlineAfter(10, TimeUnit.SECONDS).cancelTask(
            CancelTaskRequest.newBuilder().setHeader(header("picasso.v1.CancelTaskRequest", epoch))
                .setHandle(TaskHandle.newBuilder().setTaskId(taskId).setRevision(1).setRobotId(ROBOT)).build(),
        )

        fun snapshot(epoch: Long): GetSnapshotResponse = events.withDeadlineAfter(10, TimeUnit.SECONDS)
            .getSnapshot(GetSnapshotRequest.newBuilder().setHeader(header("picasso.v1.GetSnapshotRequest", epoch)).setRobotId(ROBOT).build())

        /** [lock] 을 다른 스레드가 쥐고 [release] 까지 놓지 않는다. 쥐었으면 돌아온다. */
        fun holding(lock: Any, release: CountDownLatch): Thread {
            val held = CountDownLatch(1)
            val t = thread(name = "holder") { synchronized(lock) { held.countDown(); release.await(10, TimeUnit.SECONDS) } }
            assertTrue(held.await(5, TimeUnit.SECONDS))
            return t
        }

        override fun close() {
            adapter.accept.open()
            adapter.update.open()
            adapter.holdGate.open()
            admission.go.countDown()
            channel.shutdownNow()
            host.shutdown()
        }
    }

    /** [condition] 이 설 때까지 기다린다 — 잠자기로 «아직 안 됨» 을 추정하지 않고 그 자리에 든 것을 확정한다. */
    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            assertTrue(System.nanoTime() < until, what)
            Thread.onSpinWait()
        }
    }

    /** [block] 을 다른 스레드에서 돌고 그 답을 [into] 에 둔다. */
    private fun <T> async(into: AtomicReference<Result<T>>, block: () -> T): Thread = thread { into.set(runCatching(block)) }

    @Test
    fun `인수 확인 뒤에 들어온 옛 명령은 입구에서 거절되고 벤더에 안 닿는다`() {
        World().use { w ->
            val confirmed = w.snapshot(2)
            assertEquals(FenceStatus.FENCE_STATUS_VOLATILE, confirmed.fenceStatus)
            val old = w.start("T-old", 1)
            assertEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, old.rejection.code)
            assertEquals(0, w.adapter.accepts.get(), "인수 확인 뒤 옛 명령이 벤더에 닿았다")
        }
    }

    @Test
    fun `벤더 호출 직전 사전 조건을 보던 옛 명령은 인수 확인이 기다리고 재대조가 막는다`() {
        World(profile = PRECOND).use { w ->
            // 옛 명령은 입구 앞 펌프에서 손을 한 번 읽고(첫째), 입구를 지난 뒤 사전 조건으로 한 번 더 읽는다(둘째) — 둘째에서 세운다.
            w.adapter.blockHoldAt = w.adapter.holdCalls.get() + 2
            val oldAnswer = AtomicReference<Result<StartTaskResponse>>()
            val sender = async(oldAnswer) { w.navigate("T-old", 1) }
            assertTrue(w.adapter.holdGate.entered.await(5, TimeUnit.SECONDS), "옛 명령이 입구를 지나 사전 조건에 들지 않았다")

            val snapshot = AtomicReference<Result<GetSnapshotResponse>>()
            val confirmer = async(snapshot) { w.snapshot(2) }
            awaitTrue("인수 확인이 앞서 통과한 명령을 기다리는 자리에 들지 않았다") { w.host.fence.confirmsWaiting == 1 }
            assertEquals(null, snapshot.get(), "입구를 지난 옛 명령이 끝나기 전에 인수 확인을 답했다")
            w.adapter.holdGate.open()
            sender.join(10_000)
            confirmer.join(10_000)

            assertEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, oldAnswer.get().getOrThrow().rejection.code, "벤더 호출 직전 재대조가 옛 명령을 막지 않았다")
            assertEquals(0, w.adapter.accepts.get(), "인수 확인 중에 옛 명령이 벤더에 닿았다")
            val answer = snapshot.get().getOrThrow()
            assertEquals(2L, answer.header.controllerEpoch)
            assertTrue(answer.tasksList.isEmpty(), "벤더에 안 닿은 옛 명령이 장부에 올랐다")
        }
    }

    @Test
    fun `링크를 기다리던 옛 명령은 링크 잠금 안의 재대조가 막는다`() {
        World().use { w ->
            w.admission.holdOld.set(true)
            val oldAnswer = AtomicReference<Result<StartTaskResponse>>()
            val sender = async(oldAnswer) { w.start("T-old", 1) }
            assertTrue(w.admission.admitted.await(5, TimeUnit.SECONDS))
            val releaseLink = CountDownLatch(1)
            val holder = w.holding(w.robot.southbound, releaseLink)
            w.admission.go.countDown()

            val snapshot = AtomicReference<Result<GetSnapshotResponse>>()
            val confirmer = async(snapshot) { w.snapshot(2) }
            awaitTrue("인수 확인이 앞서 통과한 명령을 기다리는 자리에 들지 않았다") { w.host.fence.confirmsWaiting == 1 }
            assertEquals(null, snapshot.get(), "링크를 기다리는 옛 명령이 끝나기 전에 인수 확인을 답했다")
            releaseLink.countDown()
            holder.join(5_000)
            sender.join(10_000)
            confirmer.join(10_000)

            assertEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, oldAnswer.get().getOrThrow().rejection.code)
            assertEquals(0, w.adapter.accepts.get(), "링크를 얻은 옛 명령이 재대조 없이 벤더에 닿았다")
            assertTrue(snapshot.get().getOrThrow().tasksList.isEmpty())
            // 거절한 옛 명령은 자리를 남기지 않는다 — 새 세대의 접수가 바로 선다.
            assertTrue(w.start("T-new", 2).hasHandle())
        }
    }

    @Test
    fun `벤더 호출 중인 옛 명령은 인수 확인이 장부 반영까지 기다리고 그 응답 뒤로는 옛 명령이 벤더에 새로 안 닿는다`() {
        World().use { w ->
            assertTrue(w.start("T-old", 1).hasHandle(), "시험의 전제: 옛 세대가 태스크를 들었다")
            // 옛 세대의 갱신(같은 태스크, 리비전 2)이 벤더 호출 안에서 선다 — 장부 반영 직전이다.
            w.adapter.update.closed.set(true)
            val oldAnswer = AtomicReference<Result<StartTaskResponse>>()
            val sender = async(oldAnswer) { w.start("T-old", 1, revision = 2) }
            assertTrue(w.adapter.update.entered.await(5, TimeUnit.SECONDS), "옛 갱신이 벤더에 들지 않았다")
            // 조작 대기 — 보내는 중인 갱신 뒤에서 기다리는 옛 취소도 입구를 지난 명령이다.
            val cancelAnswer = AtomicReference<Result<CancelTaskResponse>>()
            val canceller = async(cancelAnswer) { w.cancel("T-old", 1) }
            awaitTrue("옛 취소가 보내는 중인 갱신 뒤에서 기다리지 않았다") { w.robot.waitingTurns.get() == 1 }
            assertEquals(null, cancelAnswer.get(), "시험의 전제: 옛 취소가 보내는 중인 갱신을 기다린다")

            val snapshot = AtomicReference<Result<GetSnapshotResponse>>()
            val confirmer = async(snapshot) { w.snapshot(2) }
            awaitTrue("인수 확인이 앞서 통과한 명령을 기다리는 자리에 들지 않았다") { w.host.fence.confirmsWaiting == 1 }
            assertEquals(null, snapshot.get(), "벤더에 닿은 옛 명령이 장부에 반영되기 전에 인수 확인을 답했다")
            w.adapter.update.open()
            sender.join(10_000)
            canceller.join(10_000)
            confirmer.join(10_000)

            assertTrue(oldAnswer.get().getOrThrow().hasHandle(), "이미 벤더에 닿은 옛 갱신의 결과를 버렸다")
            val answer = snapshot.get().getOrThrow()
            assertEquals(listOf(2), answer.tasksList.map { it.revision }, "이미 닿은 옛 갱신이 확인 스냅샷에 없다")
            assertEquals(FenceStatus.FENCE_STATUS_VOLATILE, answer.fenceStatus)
            assertEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, cancelAnswer.get().getOrThrow().rejection.code, "기다리던 옛 취소가 확인 뒤 벤더로 갔다")
            assertEquals(0, w.adapter.cancels.get())
            assertEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, w.cancel("T-old", 1).rejection.code)
            assertEquals(1, w.adapter.updates.get())
            assertEquals(0, w.adapter.cancels.get(), "확인 응답 뒤 옛 명령이 벤더에 새로 닿았다")
            // 새 세대의 취소는 간다.
            assertTrue(w.cancel("T-old", 2).hasState())
            assertEquals(1, w.adapter.cancels.get())
        }
    }

    @Test
    fun `영속 저장이면 정리가 끝난 확인은 DURABLE_CONFIRMED 이고 기한을 넘기면 DURABLE_PENDING 이다`() {
        val dir = Files.createTempDirectory("fence")
        FileFenceStore(dir).use { store ->
            World(store, fenceWait = Duration.ofMillis(150)).use { w ->
                assertEquals(FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED, w.snapshot(1).fenceStatus)
                w.adapter.accept.closed.set(true)
                val oldAnswer = AtomicReference<Result<StartTaskResponse>>()
                val sender = async(oldAnswer) { w.start("T-old", 1) }
                assertTrue(w.adapter.accept.entered.await(5, TimeUnit.SECONDS))
                val pending = w.snapshot(2)
                assertEquals(FenceStatus.FENCE_STATUS_DURABLE_PENDING, pending.fenceStatus, "멈춘 벤더 호출을 둔 채 확인을 답했다")
                assertEquals(2L, pending.header.controllerEpoch, "기한을 넘겨도 바닥값은 올라 있어야 한다")
                w.adapter.accept.open()
                sender.join(10_000)
                assertEquals(FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED, w.snapshot(2).fenceStatus, "정리가 끝난 뒤 다시 물어도 확인이 아니다")
            }
        }
    }

    @Test
    fun `나갔는지 모르는 접수는 기체가 쉰다고 해도 확인을 막고 같은 키 재전송은 보내지 않는다`() {
        FileFenceStore(Files.createTempDirectory("fence")).use { store ->
            World(store).use { w ->
                w.adapter.refuse = Refusal.LINK_IN_DOUBT
                assertFailsWith<StatusRuntimeException> { w.start("T-old", 1) }
                assertEquals(FenceStatus.FENCE_STATUS_DURABLE_PENDING, w.snapshot(2).fenceStatus, "장부에 없는 미확정 접수를 두고 확인을 답했다")
                w.adapter.refuse = null
                // 어댑터의 «쉼»(UNSPECIFIED)은 지역 상태다 — 응답을 잃은 어댑터는 지역 태스크를 안 세운다. 해소의 증명이 아니다.
                w.host.pump()
                assertEquals(FenceStatus.FENCE_STATUS_DURABLE_PENDING, w.snapshot(2).fenceStatus, "지역 상태의 쉼으로 미확정 접수를 걷었다")
                val again = assertFailsWith<StatusRuntimeException> { w.start("T-old", 2) }
                assertEquals(Status.Code.UNKNOWN, again.status.code)
                assertEquals(0, w.adapter.accepts.get(), "나갔는지 모르는 접수를 다시 보냈다")
            }
        }
    }

    @Test
    fun `다른 접수를 벤더가 받아도 앞의 미확정 접수는 확인을 막는다`() {
        FileFenceStore(Files.createTempDirectory("fence")).use { store ->
            World(store).use { w ->
                w.adapter.refuse = Refusal.LINK_IN_DOUBT
                assertFailsWith<StatusRuntimeException> { w.start("T-old", 1) }
                w.adapter.refuse = null
                assertTrue(w.start("T-new", 2).hasHandle())
                assertEquals(FenceStatus.FENCE_STATUS_DURABLE_PENDING, w.snapshot(2).fenceStatus, "새 접수의 수락을 앞 접수의 해소로 읽었다")
            }
        }
    }

    @Test
    fun `결과를 모르는 갱신은 못 든 것으로 접지 않고 확인을 막으며 같은 갱신을 다시 보내지 않는다`() {
        FileFenceStore(Files.createTempDirectory("fence")).use { store ->
            World(store).use { w ->
                assertTrue(w.start("T-1", 1).hasHandle())
                w.adapter.updateRefuse = Refusal.LINK_IN_DOUBT
                val first = assertFailsWith<StatusRuntimeException> { w.start("T-1", 1, revision = 2) }
                assertEquals(Status.Code.UNKNOWN, first.status.code, "결과를 모르는 갱신을 UPDATE_UNSUPPORTED 로 접었다")
                assertEquals(FenceStatus.FENCE_STATUS_DURABLE_PENDING, w.snapshot(2).fenceStatus, "결과를 모르는 갱신을 두고 확인을 답했다")
                w.adapter.updateRefuse = null
                assertFailsWith<StatusRuntimeException> { w.start("T-1", 2, revision = 2) }
                assertEquals(1, w.adapter.updates.get(), "결과를 모르는 갱신을 다시 보냈다")
            }
        }
    }

    @Test
    fun `결과를 모르는 조작은 그 태스크의 종착을 벤더 관측으로 읽을 때만 걷힌다`() {
        listOf(false, true).forEach { vendor ->
            FileFenceStore(Files.createTempDirectory("fence")).use { store ->
                World(store).use { w ->
                    w.adapter.vendorPoll = vendor
                    assertTrue(w.start("T-1", 1).hasHandle())
                    w.adapter.cancelRefuse = Refusal.LINK_IN_DOUBT
                    assertFailsWith<StatusRuntimeException> { w.cancel("T-1", 1) }
                    assertEquals(FenceStatus.FENCE_STATUS_DURABLE_PENDING, w.snapshot(2).fenceStatus, "결과를 모르는 취소를 두고 확인을 답했다")
                    w.adapter.cancelRefuse = null
                    w.adapter.current = TaskState.TASK_STATE_CANCELLED
                    w.host.pump()
                    val expected = if (vendor) FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED else FenceStatus.FENCE_STATUS_DURABLE_PENDING
                    assertEquals(expected, w.snapshot(2).fenceStatus, "벤더 관측=$vendor 인데 걷음이 어긋났다")
                }
            }
        }
    }

    @Test
    fun `뒤 조작의 거절·미지원 답은 앞 조작의 미확정을 걷지 않는다`() {
        FileFenceStore(Files.createTempDirectory("fence")).use { store ->
            World(store).use { w ->
                assertTrue(w.start("T-1", 1).hasHandle())
                w.adapter.cancelRefuse = Refusal.LINK_IN_DOUBT
                assertFailsWith<StatusRuntimeException> { w.cancel("T-1", 1) }
                w.adapter.cancelRefuse = Refusal.NO_VENDOR_PRIMITIVE
                assertEquals(RejectionCode.REJECTION_CODE_CANCEL_UNSUPPORTED, w.cancel("T-1", 2).rejection.code)
                assertEquals(FenceStatus.FENCE_STATUS_DURABLE_PENDING, w.snapshot(2).fenceStatus, "뒤 조작의 미지원 답을 앞 조작의 해소로 읽었다")
            }
        }
    }

    @Test
    fun `펌프가 읽은 뒤에 생긴 조작 미확정은 그 읽기가 걷지 않는다`() {
        FileFenceStore(Files.createTempDirectory("fence")).use { store ->
            World(store).use { w ->
                w.adapter.vendorPoll = true
                assertTrue(w.start("T-1", 1).hasHandle())
                // 계획·읽기를 먼저 한다 — 그때는 결과를 모르는 조작이 없다. 읽은 상태는 종착이다.
                w.adapter.current = TaskState.TASK_STATE_CANCELLED
                val plan = w.host.exclusive { w.robot.plan() }
                val reading = w.robot.read(plan)
                w.adapter.current = TaskState.TASK_STATE_RUNNING
                w.adapter.cancelRefuse = Refusal.LINK_IN_DOUBT
                // 펌프를 지나지 않는 조작으로 미확정을 등록한다 — RPC 는 입구에서 펌프를 돌아 더 새 읽기로 앞 읽기를 버리므로, 읽기와 적기 사이에
                // 등록되는 경합(스케줄러 펌프와 링크 잠금을 놓친 명령)을 직접 짓는다.
                w.robot.manipulate("T-1", what = "cancel") { w.robot.adapter.cancel() }
                w.host.exclusive { w.robot.apply(reading) }
                assertEquals(FenceStatus.FENCE_STATUS_DURABLE_PENDING, w.snapshot(2).fenceStatus, "읽은 뒤에 생긴 미확정을 앞 읽기가 걷었다")
            }
        }
    }

    @Test
    fun `같은 태스크의 새 조작 미확정은 앞 미확정을 본 읽기가 걷지 않는다`() {
        FileFenceStore(Files.createTempDirectory("fence")).use { store ->
            World(store).use { w ->
                w.adapter.vendorPoll = true
                assertTrue(w.start("T-1", 1).hasHandle())
                w.adapter.cancelRefuse = Refusal.LINK_IN_DOUBT
                w.robot.manipulate("T-1", what = "cancel") { w.robot.adapter.cancel() } // 앞 미확정(토큰 1)
                // 앞 미확정이 있는 채로 계획·읽기 — 읽은 상태는 종착이다.
                w.adapter.current = TaskState.TASK_STATE_CANCELLED
                val plan = w.host.exclusive { w.robot.plan() }
                val reading = w.robot.read(plan)
                w.adapter.current = TaskState.TASK_STATE_RUNNING
                // 같은 태스크에 새 조작이 또 결과를 모른 채 끝난다(토큰 2) — 그 뒤 앞 읽기를 적는다.
                w.robot.manipulate("T-1", what = "pause") { w.robot.adapter.cancel() }
                w.host.exclusive { w.robot.apply(reading) }
                assertEquals(FenceStatus.FENCE_STATUS_DURABLE_PENDING, w.snapshot(2).fenceStatus, "앞 읽기가 같은 태스크의 새 미확정까지 걷었다")
                // 태스크는 앞 읽기로 종착이 적혔다 — 그래도 남은 미확정 때문에 다음 펌프가 그 태스크를 다시 읽고, 벤더 관측 종착이 새 토큰을 걷는다.
                w.adapter.current = TaskState.TASK_STATE_CANCELLED
                w.host.pump()
                assertEquals(FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED, w.snapshot(2).fenceStatus, "종착으로 적힌 태스크의 남은 미확정을 다시 읽지 않았다")
            }
        }
    }

    @Test
    fun `벤더 관측을 안 주는 어댑터면 종착으로 적힌 태스크를 미확정 때문에 다시 읽지 않는다`() {
        FileFenceStore(Files.createTempDirectory("fence")).use { store ->
            World(store).use { w ->
                assertTrue(w.start("T-1", 1).hasHandle())
                w.adapter.cancelRefuse = Refusal.LINK_IN_DOUBT
                w.robot.manipulate("T-1", what = "cancel") { w.robot.adapter.cancel() }
                w.adapter.current = TaskState.TASK_STATE_CANCELLED
                w.host.pump() // 종착을 적는다 — 걷을 근거는 없다(벤더 관측 아님).
                val polls = w.adapter.polls.get()
                repeat(3) { w.host.pump() }
                assertEquals(polls, w.adapter.polls.get(), "걷을 근거가 안 나오는 종착 태스크를 펌프마다 다시 읽었다")
                assertEquals(FenceStatus.FENCE_STATUS_DURABLE_PENDING, w.snapshot(2).fenceStatus)
            }
        }
    }

    @Test
    fun `벤더 관측 어댑터의 읽기가 한 번 관측이 아니어도 다시 관측이 되면 걷힌다`() {
        FileFenceStore(Files.createTempDirectory("fence")).use { store ->
            World(store).use { w ->
                w.adapter.vendorPoll = true
                assertTrue(w.start("T-1", 1).hasHandle())
                w.host.pump() // 벤더 관측 읽기를 한 번 적는다.
                w.adapter.cancelRefuse = Refusal.LINK_IN_DOUBT
                w.robot.manipulate("T-1", what = "cancel") { w.robot.adapter.cancel() }
                // 관측이 아닌 읽기 한 번에 종착이 적힌다 — 걷을 근거는 아니다.
                w.adapter.vendorPoll = false
                w.adapter.current = TaskState.TASK_STATE_CANCELLED
                w.host.pump()
                assertEquals(FenceStatus.FENCE_STATUS_DURABLE_PENDING, w.snapshot(2).fenceStatus, "벤더 관측이 아닌 읽기로 걷었다")
                // 관측이 돌아온다 — 종착으로 적힌 그 태스크를 다시 읽어 걷는다.
                w.adapter.vendorPoll = true
                w.host.pump()
                assertEquals(FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED, w.snapshot(2).fenceStatus, "한 번의 비관측 읽기 뒤 관측이 돌아와도 못 걷었다")
            }
        }
    }

    @Test
    fun `읽은 것을 적는 동안 링크 잠금을 기다리지 않는다`() {
        World().use { w ->
            assertTrue(w.start("T-1", 1).hasHandle())
            w.adapter.current = TaskState.TASK_STATE_SUCCEEDED
            val plan = w.host.exclusive { w.robot.plan() }
            val reading = w.robot.read(plan)
            // 다른 명령이 벤더 호출 중이라 링크를 쥐고 있다 — 적는 일은 호스트 잠금만으로 끝나야 한다.
            val releaseLink = CountDownLatch(1)
            val holder = w.holding(w.robot.southbound, releaseLink)
            val applied = CountDownLatch(1)
            val applier = thread { w.host.exclusive { w.robot.apply(reading) }; applied.countDown() }
            try {
                assertTrue(applied.await(3, TimeUnit.SECONDS), "호스트 잠금을 쥔 채 링크 잠금을 기다렸다")
            } finally {
                releaseLink.countDown()
                holder.join(5_000)
                applier.join(5_000)
            }
        }
    }

    @Test
    fun `저장하지 못하면 인수 확인도 명령도 실패한다`() {
        val broken = object : FenceStore {
            override val durability = FenceDurability.DURABLE
            override fun load(): Map<String, Long> = emptyMap()
            override fun save(robotId: String, floor: Long) = throw java.io.IOException("disk full")
        }
        World(broken).use { w ->
            val snapshot = assertFailsWith<StatusRuntimeException> { w.snapshot(1) }
            assertEquals(Status.Code.FAILED_PRECONDITION, snapshot.status.code)
            val command = assertFailsWith<StatusRuntimeException> { w.start("T-1", 1) }
            assertEquals(Status.Code.FAILED_PRECONDITION, command.status.code)
            assertEquals(0, w.adapter.accepts.get(), "바닥값을 저장하지 못한 명령이 벤더에 닿았다")
        }
    }

    @Test
    fun `재시작한 호스트는 파일에서 바닥값을 되찾고 손상된 파일이면 서지 않는다`() {
        val dir = Files.createTempDirectory("fence")
        FileFenceStore(dir).use { store -> World(store).use { w -> assertTrue(w.start("T-1", 4).hasHandle()) } }
        FileFenceStore(dir).use { store ->
            World(store).use { w ->
                assertEquals(4L, w.snapshot(0).header.controllerEpoch, "다시 뜬 호스트가 바닥값을 잃었다")
                assertEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, w.start("T-2", 3).rejection.code)
            }
        }
        Files.list(dir).use { files -> files.filter { it.fileName.toString().endsWith(".floor") }.toList() }
            .forEach { Files.writeString(it, "not-a-number") }
        FileFenceStore(dir).use { store ->
            assertFailsWith<IllegalStateException> { ControllerFence(FenceMode.FENCED, store) }
        }
    }

    @Test
    fun `바닥값 디렉터리는 한 호스트만 쓴다`() {
        val dir = Files.createTempDirectory("fence")
        FileFenceStore(dir).use {
            assertFailsWith<IllegalStateException> { FileFenceStore(dir) }
        }
        FileFenceStore(dir).close()
    }

    private companion object {
        const val ROBOT = "r1"
        val PROFILE: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
        val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()
        val PICK = mapOf("object_id" to "SEQ-IN-02.BIN-A", "destination" to "RACK-204.S01")
    }
}

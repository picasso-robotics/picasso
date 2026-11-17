package dev.picasso.harness

import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.adapter.core.SiteNames
import dev.picasso.adapter.host.AdapterHost
import dev.picasso.adapter.host.HostedRobot
import dev.picasso.client.PicassoClient
import dev.picasso.contracts.v1.FenceStatus
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.wire.ControllerFence
import dev.picasso.contracts.wire.FenceMode
import dev.picasso.profile.ProfileDocument
import dev.picasso.profile.RequirementSet
import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * **수신자 둘(미믹·어댑터 호스트)이 제어자 세대 대조를 같은 표로 한다**(ADR 54).
 *
 * 판정은 `contracts` 의 [ControllerFence] 한 벌이지만, 그것을 어디서 부르는지(식별 정보 대조 뒤, 엔진·벤더 앞)와 응답 머리말에 무엇을
 * 싣는지는 수신자마다 따로 지었다. 그래서 같은 장면을 둘에 똑같이 돌린다. 벤더 호출이 없었다는 것은 호스트에서는 어댑터 호출 수로,
 * 미믹에서는 스냅샷의 태스크 표가 그대로인 것으로 본다. 경합·영속·기다림은 호스트만의 것이라 `adapter-host` 의 시험이 댄다.
 */
class ControllerFenceParityTest {

    /** 태스크를 받고 [next] 로 답하는 어댑터. 호출을 센다 — 벤더에 닿았는지의 증거다. */
    private class CountingAdapter : RobotAdapter {
        val calls = AtomicInteger()

        @Volatile var next: TaskState = TaskState.TASK_STATE_RUNNING

        override fun update(taskId: String, skillType: String, parameters: Map<String, Any>, at: Instant): Applied {
            calls.incrementAndGet(); return Applied.Ok
        }
        override val state: TaskState get() = TaskState.TASK_STATE_UNSPECIFIED
        override fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance {
            calls.incrementAndGet(); return Acceptance.Accepted(taskId)
        }
        override fun poll(now: Instant): TaskState = next
        override fun pause(): Applied { calls.incrementAndGet(); return Applied.Ok }
        override fun resume(parameters: Map<String, Any>): Applied { calls.incrementAndGet(); return Applied.Ok }
        override fun cancel(): Applied { calls.incrementAndGet(); return Applied.Ok }
        override fun retry(parameters: Map<String, Any>): Applied { calls.incrementAndGet(); return Applied.Ok }
        override fun hold(): HoldObservation = HoldObservation.Empty
        override fun faults(): FaultObservation = FaultObservation.Observed(emptyList())
        override fun knownSiteNames(): SiteNames = SiteNames.Known(emptyList())
    }

    /** 수신자 하나. 세대마다 클라이언트를 새로 짓는다 — 클라이언트는 세대를 붙잡는다. */
    private interface Receiver : AutoCloseable {
        val name: String
        fun client(epoch: Long): PicassoClient

        /** 벤더에 닿은 호출 수. 미믹은 벤더가 없어 널이다 — 대신 스냅샷의 태스크 표로 본다. */
        fun vendorCalls(): Int?

        /** 도는 태스크를 성공으로 끝낸다. */
        fun finish()
    }

    private class Mimic(mode: FenceMode) : Receiver {
        val harness = Harness(mapOf(ROBOT to PROFILE), fence = ControllerFence(mode))
        override val name = "mimic"
        override fun client(epoch: Long) = harness.client(CLIENT, controllerEpoch = { epoch })
        override fun vendorCalls(): Int? = null
        override fun finish() = harness.advance(Duration.ofMinutes(30))
        override fun close() = harness.close()
    }

    private class Host(mode: FenceMode) : Receiver {
        val adapter = CountingAdapter()
        val robot = HostedRobot(ROBOT, ProfileDocument.parse("minimal", Files.readString(PROFILE)).getOrThrow(), adapter)
        private val serverName = InProcessServerBuilder.generateName()
        val host = AdapterHost(robot, InProcessServerBuilder.forName(serverName).directExecutor(), fence = ControllerFence(mode)).start()
        private val channel: ManagedChannel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
        override val name = "adapter-host"
        override fun client(epoch: Long) = PicassoClient(channel, CLIENT, controllerEpoch = { epoch })
        override fun vendorCalls(): Int = adapter.calls.get()
        override fun finish() {
            adapter.next = TaskState.TASK_STATE_SUCCEEDED
            host.pump()
        }
        override fun close() {
            channel.shutdownNow()
            host.shutdown()
        }
    }

    private fun both(mode: FenceMode, scene: (Receiver) -> Unit) =
        listOf<() -> Receiver>({ Mimic(mode) }, { Host(mode) }).forEach { make -> make().use(scene) }

    private fun tasks(r: Receiver) = r.client(0).snapshot(ROBOT).tasksList.associate { it.taskId to it.state }

    private fun stale(label: String, r: Receiver, rejection: Rejection?, requested: Long, floor: Long) {
        assertEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, rejection?.code, "${r.name}: $label 가 세대로 거절되지 않았다")
        assertEquals(requested, rejection!!.requestedControllerEpoch, "${r.name}: $label 의 요청 세대")
        assertEquals(floor, rejection.controllerEpochFloor, "${r.name}: $label 의 바닥값")
    }

    @Test
    fun `낮은 세대의 명령 다섯은 둘 다 STALE_CONTROLLER 이고 벤더도 장부도 그대로다`() = both(FenceMode.FENCED) { r ->
        val owner = r.client(2)
        val handle = owner.start(ROBOT, "t-1", 1, "pick_place", PICK).handle
        val calls = r.vendorCalls()
        // 미믹은 태스크 RPC 마다 정착한다 — 접수가 도는 중으로 넘어가는 것은 시간의 일이라 한 번 정착시킨 뒤를 기준으로 삼는다.
        owner.follow(ROBOT, handle)
        val before = tasks(r)
        val old = r.client(1)

        stale("StartTask", r, old.start(ROBOT, "t-2", 1, "pick_place", PICK).rejection, 1, 2)
        stale("PauseTask", r, old.pause(ROBOT, handle).rejection, 1, 2)
        stale("ResumeTask", r, old.resume(ROBOT, handle).rejection, 1, 2)
        stale("RetryTask", r, old.retry(ROBOT, handle).rejection, 1, 2)
        stale("CancelTask", r, old.cancel(ROBOT, handle).rejection, 1, 2)
        // 같은 키의 낮은 세대 재전송도 거절이다 — 멱등 응답을 주지 않는다.
        stale("같은 키 StartTask", r, old.start(ROBOT, "t-1", 1, "pick_place", PICK).rejection, 1, 2)

        assertEquals(calls, r.vendorCalls(), "${r.name}: 거절한 명령이 벤더에 닿았다")
        assertEquals(before, tasks(r), "${r.name}: 거절한 명령이 장부를 바꿨다")
        assertEquals(2L, old.start(ROBOT, "t-3", 1, "pick_place", PICK).header.controllerEpoch, "${r.name}: 거절 응답이 바닥값을 싣지 않았다")
    }

    @Test
    fun `세대를 싣지 않은 명령은 대조하는 수신자가 거절한다 — 바닥값이 0 이어도`() = both(FenceMode.FENCED) { r ->
        val none = r.client(0)
        val rejection = none.start(ROBOT, "t-1", 1, "pick_place", PICK).rejection
        stale("세대 0 StartTask", r, rejection, 0, 0)
        assertTrue(tasks(r).isEmpty(), "${r.name}: 세대 0 명령이 태스크를 만들었다")
    }

    @Test
    fun `높은 세대는 바닥값을 올린 뒤 처리하고 같은 세대는 통과한다`() = both(FenceMode.FENCED) { r ->
        val first = r.client(1).start(ROBOT, "t-1", 1, "pick_place", PICK)
        assertTrue(first.hasHandle(), "${r.name}: 첫 세대 명령이 거절됐다: ${first.rejection}")
        assertEquals(1L, first.header.controllerEpoch)

        val higher = r.client(3)
        val cancelled = higher.cancel(ROBOT, first.handle)
        assertNotEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, cancelled.rejection.code, "${r.name}: 높은 세대를 거절했다")
        assertEquals(3L, cancelled.header.controllerEpoch, "${r.name}: 높은 세대가 바닥값을 안 올렸다")
        val again = higher.cancel(ROBOT, first.handle)
        assertNotEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, again.rejection.code, "${r.name}: 같은 세대를 거절했다")
        stale("올린 뒤 옛 세대", r, r.client(1).cancel(ROBOT, first.handle).rejection, 1, 3)
    }

    @Test
    fun `대조하지 않는 수신자는 오늘 거동 그대로이고 바닥값 0 을 답한다`() = both(FenceMode.UNFENCED) { r ->
        val high = r.client(5).start(ROBOT, "t-1", 1, "pick_place", PICK)
        assertTrue(high.hasHandle(), "${r.name}: ${high.rejection}")
        assertEquals(0L, high.header.controllerEpoch, "${r.name}: 대조하지 않는데 바닥값을 실었다 — 0 은 «대조 안 함» 이다")
        val low = r.client(1).cancel(ROBOT, high.handle)
        assertNotEquals(RejectionCode.REJECTION_CODE_STALE_CONTROLLER, low.rejection.code, "${r.name}: 대조하지 않는데 낮은 세대를 거절했다")
        val snapshot = r.client(9).snapshot(ROBOT)
        assertEquals(0L, snapshot.header.controllerEpoch)
        assertEquals(FenceStatus.FENCE_STATUS_UNSPECIFIED, snapshot.fenceStatus, "${r.name}: 대조하지 않는데 인수 확인을 답했다")
        assertTrue(r.client(0).start(ROBOT, "t-1", 1, "pick_place", PICK).hasHandle(), "${r.name}: 세대 0 을 거절했다")
    }

    @Test
    fun `낮은 세대의 읽기와 새 구독은 통과하고 바닥값을 싣는다 — 다른 읽기는 올리지 않고 스냅샷은 올린다`() = both(FenceMode.FENCED) { r ->
        val handle = r.client(4).start(ROBOT, "t-1", 1, "pick_place", PICK).handle
        val old = r.client(1)
        val snapshot = old.snapshot(ROBOT)
        assertEquals(4L, snapshot.header.controllerEpoch, "${r.name}: 낮은 세대 스냅샷에 바닥값이 없다")
        assertEquals(FenceStatus.FENCE_STATUS_UNSPECIFIED, snapshot.fenceStatus, "${r.name}: 낮은 세대 읽기를 인수 확인으로 답했다")
        assertEquals(listOf("t-1"), snapshot.tasksList.map { it.taskId })
        val follower = old.follow(ROBOT, handle)
        assertTrue(follower.error == null, "${r.name}: 낮은 세대의 새 구독을 거절했다: ${follower.error}")
        assertTrue(follower.updates.isNotEmpty() && follower.updates.all { it.header.controllerEpoch == 4L }, "${r.name}: 스트림 머리말에 바닥값이 없다")
        old.replay(ROBOT, 0).forEach { assertEquals(4L, it.header.controllerEpoch) }

        // 스냅샷 말고 읽기는 올리지 않는다.
        val higher = r.client(9)
        higher.capabilities(ROBOT)
        higher.negotiate(ROBOT, REQUIREMENTS)
        higher.replay(ROBOT, 0)
        higher.follow(ROBOT, handle)
        assertEquals(4L, r.client(0).snapshot(ROBOT).header.controllerEpoch, "${r.name}: 스냅샷이 아닌 읽기가 바닥값을 올렸다")
        val confirmed = higher.snapshot(ROBOT)
        assertEquals(9L, confirmed.header.controllerEpoch, "${r.name}: 높은 세대 스냅샷이 바닥값을 안 올렸다")
        stale("스냅샷 뒤 옛 세대", r, r.client(4).cancel(ROBOT, handle).rejection, 4, 9)
    }

    @Test
    fun `인수 확인은 둘 다 메모리 저장이면 VOLATILE 이다`() = both(FenceMode.FENCED) { r ->
        assertEquals(FenceStatus.FENCE_STATUS_VOLATILE, r.client(2).snapshot(ROBOT).fenceStatus, "${r.name}: 메모리 저장이 영속처럼 답했다")
        assertEquals(FenceStatus.FENCE_STATUS_VOLATILE, r.client(2).snapshot(ROBOT).fenceStatus, "${r.name}: 같은 세대의 다시 확인")
    }

    @Test
    fun `새 세대의 같은 task_id·revision 재전송은 기존 핸들에 붙는다 — 멱등 키에 세대가 없다`() = both(FenceMode.FENCED) { r ->
        val first = r.client(1).start(ROBOT, "t-1", 1, "pick_place", PICK)
        val calls = r.vendorCalls()
        val taken = r.client(2).start(ROBOT, "t-1", 1, "pick_place", PICK)
        assertTrue(taken.hasHandle(), "${r.name}: 새 세대의 같은 키가 거절됐다: ${taken.rejection}")
        assertEquals(first.handle, taken.handle, "${r.name}: 새 세대의 같은 키가 새 핸들을 받았다")
        assertEquals(calls, r.vendorCalls(), "${r.name}: 같은 키 재전송이 벤더에 다시 닿았다")
    }

    @Test
    fun `옛 세대가 연 스트림은 인수 뒤에도 종착까지 받고 수락된 태스크는 완주한다`() = both(FenceMode.FENCED) { r ->
        val old = r.client(1)
        val handle: TaskHandle = old.start(ROBOT, "t-1", 1, "pick_place", PICK).handle
        val follower = old.follow(ROBOT, handle)
        assertEquals(2L, r.client(2).snapshot(ROBOT).header.controllerEpoch, "시험의 전제: 새 세대가 넘겨받았다")
        r.finish()
        r.client(0).snapshot(ROBOT) // 정착시킨다 — 열린 스트림에 민다.
        assertTrue(follower.completed, "${r.name}: 넘겨받은 뒤 옛 세대의 스트림이 종착 전에 끊겼다: ${follower.error}")
        assertEquals(TaskState.TASK_STATE_SUCCEEDED, follower.updates.last().state, "${r.name}: 수락된 태스크가 완주하지 않았다")
    }

    @Test
    fun `헤더와 핸들의 기체가 다른 조작은 세대보다 먼저 IDENTITY_MISMATCH 로 거절하고 바닥값을 올리지 않는다`() = both(FenceMode.FENCED) { r ->
        val handle = r.client(1).start(ROBOT, "t-1", 1, "pick_place", PICK).handle
        val mismatched = handle.toBuilder().setRobotId("someone-else").build()
        val answer = r.client(9).cancel(ROBOT, mismatched)
        assertEquals(RejectionCode.REJECTION_CODE_IDENTITY_MISMATCH, answer.rejection.code, "${r.name}: 식별 정보 대조보다 세대 대조가 먼저다")
        assertEquals(1L, r.client(0).snapshot(ROBOT).header.controllerEpoch, "${r.name}: 식별 정보가 어긋난 요청이 바닥값을 올렸다")
    }

    @Test
    fun `받는 범위 밖의 세대는 둘 다 거절한다`() = both(FenceMode.FENCED) { r ->
        stale("uint64 위 절반", r, r.client(-1).start(ROBOT, "t-1", 1, "pick_place", PICK).rejection, -1, 0)
        assertTrue(tasks(r).isEmpty())
    }

    private companion object {
        const val ROBOT = "r1"
        const val CLIENT = "line-controller"
        val PROFILE: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
        val PICK = listOf(
            ParameterValue.newBuilder().setKey("object_id").setStringValue("SEQ-IN-02.BIN-A").build(),
            ParameterValue.newBuilder().setKey("destination").setStringValue("RACK-204.S01").build(),
        )
        val REQUIREMENTS: RequirementSet = RequirementSet
            .parse("minimal", Files.readString(Path.of("..", "profile", "requirements", "minimal.json").normalize()))
            .copy(clientId = CLIENT)
    }
}

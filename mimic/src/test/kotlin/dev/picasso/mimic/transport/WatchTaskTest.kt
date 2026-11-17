package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.CancelTaskRequest
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.PauseTaskRequest
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskRequest
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.contracts.wire.HeaderColumns
import dev.picasso.mimic.engine.Resolution
import dev.picasso.mimic.engine.TaskCommand
import dev.picasso.mimic.engine.TaskMachineFixtures
import dev.picasso.mimic.engine.VirtualClock
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.picasso.mimic.engine.TaskState as EngineState

class TaskTickTest {

    private val clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z"))
    private val fixture = GrpcFixture(mapOf("r1" to TaskMachineFixtures.document()), clock)

    @AfterTest fun close() = fixture.close()

    private val host get() =
        fixture.registry.require(GrpcFixture.requestHeader("r1")).instance.tasks

    private fun startNavigate(taskId: String = "t1") {
        fixture.tasks.startTask(
            StartTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1"))
                .setTaskId(taskId).setRevision(1).setSkillType("navigate_to")
                .addParameters(
                    ParameterValue.newBuilder().setKey("location").setStringValue("dock-3").build(),
                ).build(),
        )
    }

    @Test
    fun `상태 열 개마다 tick의 결과를 통째로 확인한다`() {
        // **전수 축은 상태 열이다.** 종착 여부만 보는 구현은 CANCELLING인
        // 태스크를 SUCCEEDED로 만든다 — CANCELLING은 종착이 아니고 스킬은
        // 복구를 수행하며 계속 돈다(리뷰 실측).
        val moves = mapOf(
            EngineState.ACCEPTED to EngineState.RUNNING,
            EngineState.RUNNING to EngineState.SUCCEEDED,
            EngineState.CANCELLING to EngineState.CANCELLED,
        )
        assertEquals(3, moves.size, "표가 비면 '아무 일도 없다'만 확인하고 통과한다")

        EngineState.entries.forEach { from ->
            GrpcFixture(mapOf("r1" to TaskMachineFixtures.document()), VirtualClock(Instant.EPOCH))
                .use { f ->
                    f.tasks.startTask(
                        StartTaskRequest.newBuilder()
                            .setHeader(GrpcFixture.requestHeader("r1"))
                            .setTaskId("t1").setRevision(1).setSkillType("navigate_to")
                            .addParameters(
                                ParameterValue.newBuilder().setKey("location")
                                    .setStringValue("dock-3").build(),
                            ).build(),
                    )
                    val tasks = f.registry.require(GrpcFixture.requestHeader("r1")).instance.tasks
                    val task = tasks.find("t1")!!
                    if (from != EngineState.ACCEPTED) {
                        tasks.tick()
                        TaskMachineFixtures.driveTo(task, from)
                    }
                    check(task.machine.state == from) { "$from 로 몰지 못했다: ${task.machine.state}" }

                    // 소요시간을 훌쩍 넘긴다. RUNNING이 아니면 아무 일도 없어야 한다.
                    (f.clock as VirtualClock).advance(Duration.ofSeconds(500))
                    tasks.tick()

                    assertEquals(
                        moves[from] ?: from,
                        task.machine.state,
                        "$from 에서 tick()이 상태를 바꿨다",
                    )
                }
        }
    }

    @Test
    fun `CANCELLING인 태스크는 완주하지 않는다`() {
        // 위 시험 안에 있지만 따로 이름을 준다 — 리뷰가 실측한 자리다.
        startNavigate()
        host.tick()
        host.find("t1")!!.machine.apply(TaskCommand.CANCEL)
        assertEquals(EngineState.CANCELLING, host.find("t1")!!.machine.state)

        clock.advance(Duration.ofSeconds(500))
        host.tick()
        // 복구를 마쳐 CANCELLED가 된다. **SUCCEEDED로 가면 안 된다** —
        // 소요시간을 훌쩍 넘겼으므로 종착 여부만 보는 tick()은 그리로 간다.
        assertEquals(
            EngineState.CANCELLED, host.find("t1")!!.machine.state,
            "취소 중인 태스크가 완주 판정을 받았다",
        )
    }

    @Test
    fun `두 태스크가 서로 다른 소요시간으로 완주한다`() {
        // 소요시간을 상수로 하드코딩한 구현을 잡는다.
        // navigate_to 20초(지터 없음), pick_place 45초 ±10% → 40.5..49.5초.
        // **경계를 45초로 잡지 않는다** — 지터가 붙은 뒤로 그 값은 절반만
        // 넘는다(§10.4 ②).
        startNavigate("nav")
        fixture.tasks.startTask(
            StartTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1"))
                .setTaskId("pick").setRevision(1).setSkillType("pick_place")
                .addParameters(
                    ParameterValue.newBuilder().setKey("object_id").setStringValue("b").build(),
                )
                .addParameters(
                    ParameterValue.newBuilder().setKey("destination").setStringValue("d").build(),
                ).build(),
        )
        host.tick()

        clock.advance(Duration.ofSeconds(20))
        host.tick()
        assertEquals(EngineState.SUCCEEDED, host.find("nav")!!.machine.state)
        assertEquals(EngineState.RUNNING, host.find("pick")!!.machine.state)

        clock.advance(Duration.ofSeconds(30))
        host.tick()
        assertEquals(EngineState.SUCCEEDED, host.find("pick")!!.machine.state)
    }

    @Test
    fun `시계가 안 움직이면 아무 일도 없다`() {
        startNavigate()
        host.tick()
        val size = host.find("t1")!!.log.size
        repeat(5) { host.tick() }
        assertEquals(size, host.find("t1")!!.log.size, "시계가 멈췄는데 로그가 늘었다")
    }

    @Test
    fun `완주가 로그에 남는다`() {
        startNavigate()
        host.tick()
        clock.advance(Duration.ofSeconds(20))
        host.tick()
        assertEquals(EngineState.SUCCEEDED, host.find("t1")!!.log.last!!.state)
        assertEquals(1.0, host.find("t1")!!.log.last!!.progress)
    }
}

class WatchTaskTest {

    private val clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z"))
    private val fixture = GrpcFixture(mapOf("r1" to TaskMachineFixtures.document()), clock)

    @AfterTest fun close() = fixture.close()

    private fun handle(revision: Int = 1) =
        TaskHandle.newBuilder().setTaskId("t1").setRevision(revision).setRobotId("r1").build()

    /**
     * 접수하고 **집어 들리기까지** 한다. 시계를 먼저 돌리면 소요시간이
     * RUNNING 전이 시점부터 세어지므로 태스크가 안 끝나고, 비종착 스트림에
     * 건 블로킹 스텁이 영영 돌아오지 않는다(실측: 시험이 멈췄다).
     */
    private fun start() {
        fixture.tasks.startTask(
            StartTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1"))
                .setTaskId("t1").setRevision(1).setSkillType("navigate_to")
                .addParameters(
                    ParameterValue.newBuilder().setKey("location").setStringValue("dock-3").build(),
                ).build(),
        )
        fixture.registry.require(GrpcFixture.requestHeader("r1")).instance.tasks.tick()
    }

    /**
     * **마감을 건다.** 스트림이 닫혀야 하는데 안 닫히면 블로킹 스텁이 영영
     * 돌아오지 않고, 그러면 결함이 시험 실패가 아니라 **빌드 정지**로
     * 나타난다 — CI가 실패하는 대신 선다(실측: 로그 범위 초과를 조용히 빈
     * 목록으로 접는 결함을 주입했더니 빌드가 멈췄고, 주입 하네스가 그것을
     * 통과로 읽었다).
     */
    private fun watch(from: Long = 0): List<WatchTaskResponse> =
        fixture.tasks
            .withDeadlineAfter(10, TimeUnit.SECONDS)
            .watchTask(
                WatchTaskRequest.newBuilder()
                    .setHeader(GrpcFixture.requestHeader("r1"))
                    .setHandle(handle()).setFromUpdateIndex(from).build(),
            ).asSequence().toList()

    @Test
    fun `from_update_index 0이면 처음부터 준다`() {
        start()
        clock.advance(Duration.ofSeconds(20))
        // watchTask 진입의 tick()이 집어 들고 완주시킨다.
        val updates = watch(0)
        assertEquals(
            listOf(
                TaskState.TASK_STATE_ACCEPTED,
                TaskState.TASK_STATE_RUNNING,
                TaskState.TASK_STATE_SUCCEEDED,
            ),
            updates.map { it.state },
        )
        assertEquals(listOf(0L, 1L, 2L), updates.map { it.header.updateIndex })
    }

    @Test
    fun `중간부터 이어받는다`() {
        start()
        clock.advance(Duration.ofSeconds(20))
        val all = watch(0)
        assertEquals(all.drop(2).map { it.state }, watch(2).map { it.state })
        assertEquals(listOf(2L), watch(2).map { it.header.updateIndex })
    }

    @Test
    fun `이미 종착인 태스크도 로그를 주고 닫는다`() {
        start()
        clock.advance(Duration.ofSeconds(20))
        watch(0)
        // 두 번째 구독도 같은 것을 받고 끝난다 — 스트림이 닫혔는지가
        // asSequence().toList()가 돌아온다는 사실로 확인된다.
        assertEquals(3, watch(0).size)
    }

    @Test
    fun `종착에 닿으면 스트림이 닫힌다`() {
        // 닫지 않으면 소비자가 영원히 기다린다. toList()가 돌아오는 것이 증거다.
        start()
        clock.advance(Duration.ofSeconds(20))
        assertEquals(TaskState.TASK_STATE_SUCCEEDED, watch(0).last().state)
    }

    @Test
    fun `열려 있는 스트림에 이후 전이가 밀려온다`() {
        // directExecutor()에서 밀어넣기가 호출 스레드에서 동기로 일어난다.
        // Thread.sleep으로 기다리면 흔들린다.
        start()
        val received = mutableListOf<WatchTaskResponse>()
        var completed = false
        fixture.tasksAsync.watchTask(
            WatchTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1"))
                .setHandle(handle()).setFromUpdateIndex(0).build(),
            object : StreamObserver<WatchTaskResponse> {
                override fun onNext(value: WatchTaskResponse) { received += value }
                override fun onError(t: Throwable) = throw t
                override fun onCompleted() { completed = true }
            },
        )
        val backlog = received.size
        assertTrue(backlog >= 1, "밀린 것을 안 줬다")
        assertFalse(completed, "비종착인데 스트림을 닫았다")

        fixture.tasks.pauseTask(
            PauseTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1")).setHandle(handle()).build(),
        )
        assertTrue(received.size > backlog, "열린 스트림에 아무것도 안 왔다")
        assertEquals(TaskState.TASK_STATE_PAUSED, received.last().state)
        assertFalse(completed)

        // 종착에 닿으면 닫힌다.
        fixture.tasks.cancelTask(
            CancelTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1")).setHandle(handle()).build(),
        )
        assertEquals(TaskState.TASK_STATE_CANCELLING, received.last().state)
        assertFalse(completed, "CANCELLING은 종착이 아닌데 스트림을 닫았다")

        // 복구는 다음 tick에 끝난다. 관측 순서가 CANCELLING → 종착이다.
        fixture.tasks.pauseTask(
            PauseTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1")).setHandle(handle()).build(),
        )
        assertTrue(completed, "종착인데 스트림을 안 닫았다: ${received.map { it.state }}")
        assertTrue(received.last().state in setOf(
            TaskState.TASK_STATE_CANCELLED, TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED,
        ), "${received.last().state}")
    }

    @Test
    fun `열려 있는 스트림이 시간이 만든 전이를 받는다`() {
        // **밀어내기를 명령 RPC에만 두면 이것이 영영 안 온다.** 열린 스트림은
        // 멈춘 채로 남고, 결함이 시험 실패가 아니라 **정지**로 나타난다
        // (리뷰가 계획 단계에서 찾았고 코드에서 실측됐다).
        start()
        val received = mutableListOf<WatchTaskResponse>()
        var completed = false
        fixture.tasksAsync.watchTask(
            WatchTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1"))
                .setHandle(handle()).setFromUpdateIndex(0).build(),
            object : StreamObserver<WatchTaskResponse> {
                override fun onNext(value: WatchTaskResponse) { received += value }
                override fun onError(t: Throwable) = throw t
                override fun onCompleted() { completed = true }
            },
        )
        val backlog = received.size
        assertFalse(completed)

        // 명령을 하나도 보내지 않는다. 시간만 흐른다.
        fixture.advance(Duration.ofSeconds(20))

        assertTrue(received.size > backlog, "시간이 만든 전이가 스트림에 안 왔다")
        assertEquals(TaskState.TASK_STATE_SUCCEEDED, received.last().state)
        assertTrue(completed, "종착인데 스트림을 안 닫았다")
        // 커서가 없으면 마지막 것만 밀려 색인에 구멍이 난다.
        assertEquals(received.indices.map { it.toLong() }, received.map { it.header.updateIndex })
    }

    @Test
    fun `아무 일도 없으면 아무것도 안 민다`() {
        // 커서 없이 "마지막 갱신을 민다"로 구현하면 정착할 때마다 같은 것이
        // 다시 나가고, 소비자의 멱등 처리가 없으면 진행률이 되감긴 것처럼
        // 보인다. PAUSED는 시간이 흘러도 변하지 않으므로 그 자리다.
        start()
        fixture.tasks.pauseTask(
            PauseTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1")).setHandle(handle()).build(),
        )
        val received = mutableListOf<WatchTaskResponse>()
        fixture.tasksAsync.watchTask(
            WatchTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1"))
                .setHandle(handle()).setFromUpdateIndex(0).build(),
            object : StreamObserver<WatchTaskResponse> {
                override fun onNext(value: WatchTaskResponse) { received += value }
                override fun onError(t: Throwable) = throw t
                override fun onCompleted() = Unit
            },
        )
        val backlog = received.size
        assertTrue(backlog >= 1)

        repeat(3) { fixture.advance(Duration.ofSeconds(20)) }

        assertEquals(backlog, received.size, "아무 일도 없는데 밀었다: ${received.map { it.state }}")
    }

    @Test
    fun `되짚어 보낸 갱신은 그때의 시각을 싣는다`() {
        // 지금 시각을 실으면 30초 전 전이가 방금 일어난 것으로 보이고
        // 소비자의 신선도 판정(§5.5)이 거짓말을 한다.
        start()
        clock.advance(Duration.ofSeconds(20))
        val updates = watch(0)
        assertEquals("2026-09-06T00:00:00Z", updates.first().header.stateAsOf)
        assertEquals("2026-09-06T00:00:20Z", updates.last().header.stateAsOf)
        // occurred_at은 응답을 만든 시각이라 전부 같다 — 둘을 같은 값으로
        // 접으면 이 시험이 무의미해지므로 다르다는 것도 본다.
        assertEquals("2026-09-06T00:00:20Z", updates.first().header.occurredAt)
    }

    @Test
    fun `update_index가 헤더에 실리고 로그의 색인과 같다`() {
        start()
        clock.advance(Duration.ofSeconds(20))
        watch(0).forEachIndexed { i, response ->
            assertEquals(i.toLong(), response.header.updateIndex)
        }
    }

    @Test
    fun `응답에 sequence가 없다`() {
        // 두 카운터는 다른 축이다(§3.5). 같이 실으면 gRPC에만 나가는
        // 진행률이 소비한 번호를 MQTT 소비자가 결손으로 오탐한다.
        start()
        clock.advance(Duration.ofSeconds(20))
        val updates = watch(0)
        updates.forEach { response ->
            assertFalse(HeaderColumns.isSet(response.header, "sequence"))
            assertFalse(HeaderColumns.isSet(response.header, "client_id"))
            // **update_index는 뺀다.** 계약이 0부터 세라고 했고(task.proto)
            // proto3 암묵 존재라 0은 "안 실었다"와 구별되지 않는다. 값으로
            // 본다 — capability_epoch를 1에서 시작한 것과 같은 문제이고,
            // 이쪽은 계약이 0을 못박아 피할 수 없다(§15).
            (HeaderColumns.WATCH_RESPONSE - "update_index" - HeaderColumns.ZERO_IS_VALUE).forEach {
                assertTrue(HeaderColumns.isSet(response.header, it), "$it 가 비었다")
            }
        }
        assertEquals(updates.indices.map { it.toLong() }, updates.map { it.header.updateIndex })
    }

    @Test
    fun `범위를 넘는 from_update_index는 OUT_OF_RANGE다`() {
        // 조용히 빈 목록을 주면 재접속한 소비자가 오지 않을 갱신을 영원히 기다린다.
        start()
        val error = assertFailsWith<StatusRuntimeException> { watch(99) }
        assertEquals(Status.Code.OUT_OF_RANGE, error.status.code)
    }

    @Test
    fun `모르는 task_id는 NOT_FOUND다`() {
        val error = assertFailsWith<StatusRuntimeException> {
            // **기한을 건다.** 블로킹 반복자는 스트림이 안 닫히면 영원히
            // 기다리고, 그러면 결함이 실패가 아니라 **정지**로 나타난다.
            fixture.tasks.withDeadlineAfter(10, TimeUnit.SECONDS).watchTask(
                WatchTaskRequest.newBuilder()
                    .setHeader(GrpcFixture.requestHeader("r1"))
                    .setHandle(handle().toBuilder().setTaskId("nope")).build(),
            ).asSequence().toList()
        }
        assertEquals(Status.Code.NOT_FOUND, error.status.code)
    }

    @Test
    fun `진행률이 스트림 안에서 단조 비감소다`() {
        start()
        clock.advance(Duration.ofSeconds(20))
        val progress = watch(0).map { it.progress }
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a }, "$progress")
        assertTrue(progress.last() == 1.0, "$progress")
        assertTrue(progress.all { it in 0.0..1.0 }, "$progress")
    }

    @Test
    fun `태스크마다 색인이 독립이다`() {
        // 기체 전역 카운터를 쓰는 구현을 잡는다.
        start()
        fixture.tasks.startTask(
            StartTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1"))
                .setTaskId("t2").setRevision(1).setSkillType("navigate_to")
                .addParameters(
                    ParameterValue.newBuilder().setKey("location").setStringValue("dock-9").build(),
                ).build(),
        )
        fixture.registry.require(GrpcFixture.requestHeader("r1")).instance.tasks.tick()
        clock.advance(Duration.ofSeconds(20))

        // **기한이 없으면 여기서 CI가 멈춘다.** 실측: 추첨을 망가뜨려
        // `navigate_to`가 `NEEDS_INTERVENTION`으로 끝나게 하면 — 그것은
        // 종착이 아니므로 — 스트림이 안 닫히고 이 반복자가 영원히 기다렸다.
        // 결함이 빨간 시험이 아니라 300초 정지로 나타났다.
        val second = fixture.tasks.withDeadlineAfter(10, TimeUnit.SECONDS).watchTask(
            WatchTaskRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1"))
                .setHandle(handle().toBuilder().setTaskId("t2")).build(),
        ).asSequence().toList()

        assertEquals(0L, second.first().header.updateIndex, "두 번째 태스크가 0에서 시작하지 않는다")
    }
}

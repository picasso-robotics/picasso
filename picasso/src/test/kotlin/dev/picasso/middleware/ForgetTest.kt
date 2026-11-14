package dev.picasso.middleware

import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 끝난 실행을 놓는다([Middleware.forget]) — 담는 쪽이 부르지 않으면 실행과 그 통보는 인스턴스가 사는 동안 남는다.
 *
 * 놓는 것은 **구동이 더는 돌리지 않고 통보가 전부 확인된** 실행뿐이다. 놓은 뒤에도 같은 작업 지시 id 는 받지 않고, 실행 id 는
 * 다시 쓰지 않으며, 사건 번들은 남는다. 이 묶음은 그 넷과 «놓으면 표가 빈다» 를 묻는다.
 */
class ForgetTest {

    private class World(port: (RobotPort) -> RobotPort = { it }, retention: Retention = Retention()) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()))
        val mw = Middleware(port(ClientRobotPort(harness.client())), now = { harness.clock.now() }, retention = retention)

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList

        fun forceFault(errorType: String, taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType(errorType).setTaskId(taskId).build(),
        ).taskState

        fun drive(rounds: Int = 120, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다: tasks=${tasks().map { it.taskId to it.taskState }}")
        }

        /** 받아서 구동이 더는 돌리지 않을 때까지 민다. */
        fun settled(order: JobOrder): Middleware.Execution {
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order, ROBOT)).execution
            drive { !exec.physicalState.pumps }
            return exec
        }

        /**
         * 점검 단위에 결함을 걸어 `PARTIAL` 로 끝낸다 — 이동은 끝났고 점검은 실패했다. 결함 관문이 막지 않도록 결함을 못 보는 포트로
         * 띄운 세계에서만 부른다(막으면 운영자 보류에 선다).
         */
        fun partial(): Middleware.Execution {
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(patrol(), ROBOT)).execution
            val inspect = "PATROL-1#$TARGET"
            drive { tasks().any { it.taskId == inspect && it.taskState == "RUNNING" } }
            forceFault("LOCALIZATION_LOST", inspect)
            drive { exec.physicalState == PhysicalState.PARTIAL && exec.active == null }
            return exec
        }

        fun ackAll() = mw.pending().forEach { assertTrue(mw.ack(it.jobResponseId)) }

        override fun close() = harness.close()
    }

    private companion object {
        const val ROBOT = "quad-01"
        const val TARGET = "PUMP-01"

        fun patrol(jobOrderId: String = "PATROL-1", version: Int = 1) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = InspectAsset.WORK_MASTER,
            version = version,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = listOf(
                EquipmentRequirement(TARGET, EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM")),
            ),
        )
    }

    @Test
    fun `끝나고 통보가 확인된 실행을 놓으면 실행 표와 아웃박스에서 빠진다`() {
        World().use { w ->
            val exec = w.settled(patrol())
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            w.ackAll()

            assertEquals(ForgetOutcome.Forgotten, w.mw.forget(exec.executionId))
            assertTrue(w.mw.executions().isEmpty(), "놓은 실행이 표에 남았다")
            assertNull(w.mw.execution(exec.executionId))
            assertTrue(w.mw.responses().none { it.executionId == exec.executionId }, "놓은 실행의 통보가 아웃박스에 남았다")
            assertNull(w.mw.judgmentRecord(exec.executionId))
            // 한 번 놓은 것은 이제 모르는 실행이다 — 두 번 불러도 아무것도 안 바뀐다.
            assertEquals(ForgetOutcome.Unknown, w.mw.forget(exec.executionId))
        }
    }

    @Test
    fun `끝나지 않은 실행은 놓지 않는다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol(), ROBOT)).execution
            w.drive { exec.physicalState == PhysicalState.RUNNING }

            val refused = assertIs<ForgetOutcome.Refused>(w.mw.forget(exec.executionId))
            assertTrue("끝나지 않은" in refused.reason, refused.reason)
            assertNotNull(w.mw.execution(exec.executionId), "거절해 놓고 실행을 뺐다")
        }
    }

    @Test
    fun `확인되지 않은 통보가 하나라도 있으면 놓지 않는다`() {
        // 앞 통보만 확인하면 실행의 상류 확인 표시는 아직 ACKED 가 아니다(§15.262). 놓기는 그 표시가 아니라 응답마다의 확인을 본다.
        World(port = { FaultBlindRobotPort(it) }).use { w ->
            val exec = w.partial()
            assertTrue(w.mw.cancel(exec.executionId))
            w.drive { exec.physicalState == PhysicalState.ABORTED }
            val mine = w.mw.pending().filter { it.executionId == exec.executionId }
            assertTrue(mine.size >= 2, "통보가 둘 이상이어야 앞 것만 확인할 수 있다: ${mine.map { it.physicalState }}")
            mine.dropLast(1).forEach { w.mw.ack(it.jobResponseId) }
            assertEquals(UpstreamAck.SENT_UNACKED, exec.upstreamAck, "앞 통보의 확인으로 실행 전체가 확인된 것으로 읽혔다")

            val refused = assertIs<ForgetOutcome.Refused>(w.mw.forget(exec.executionId))
            assertTrue(mine.last().jobResponseId in refused.reason, refused.reason)
            assertNotNull(w.mw.execution(exec.executionId))
            assertEquals(listOf(mine.last()), w.mw.pending(), "거절해 놓고 확인 안 된 통보를 뺐다")

            w.mw.ack(mine.last().jobResponseId)
            assertEquals(UpstreamAck.ACKED, exec.upstreamAck)
            assertEquals(ForgetOutcome.Forgotten, w.mw.forget(exec.executionId))
        }
    }

    @Test
    fun `구동이 아직 돌리는 PARTIAL 은 놓지 않고 취소로 끝낸 뒤 놓으며 사건 번들은 남는다`() {
        World(port = { FaultBlindRobotPort(it) }).use { w ->
            val exec = w.partial()
            w.ackAll()

            val refused = assertIs<ForgetOutcome.Refused>(w.mw.forget(exec.executionId))
            assertTrue("PARTIAL" in refused.reason, refused.reason)
            val incidents = w.mw.incidents().filter { it.executionId == exec.executionId }
            assertTrue(incidents.isNotEmpty(), "실패한 단위가 사건을 안 열었다 — 남는지를 물을 수 없다")

            assertTrue(w.mw.cancel(exec.executionId))
            w.drive { exec.physicalState == PhysicalState.ABORTED }
            w.ackAll()
            assertEquals(ForgetOutcome.Forgotten, w.mw.forget(exec.executionId))
            assertEquals(incidents, w.mw.incidents().filter { it.executionId == exec.executionId }, "놓으면서 사건 번들을 뺐다")
        }
    }

    @Test
    fun `놓은 뒤 선 실행은 놓은 실행의 id 를 다시 쓰지 않는다`() {
        // ★id 를 표의 크기로 세면 놓은 실행의 id 가 새 실행에 붙고, 그 id 를 든 사건 번들과 통보가 새 실행을 가리킨다.
        World().use { w ->
            val first = w.settled(patrol("PATROL-1"))
            w.ackAll()
            assertEquals(ForgetOutcome.Forgotten, w.mw.forget(first.executionId))

            val second = w.settled(patrol("PATROL-2"))
            assertNotEquals(first.executionId, second.executionId)
        }
    }

    @Test
    fun `놓은 작업 지시 id 는 같은 판도 높은 판도 다시 받지 않는다`() {
        World().use { w ->
            val exec = w.settled(patrol())
            w.ackAll()
            assertEquals(ForgetOutcome.Forgotten, w.mw.forget(exec.executionId))
            val issued = w.tasks().size

            listOf(
                w.mw.submit(patrol(), ROBOT),
                w.mw.submit(patrol(version = 2), ROBOT),
                // 후보가 없어도 «배정할 기체가 없다» 가 아니라 «놓은 작업 지시» 다 — 후보가 관문을 지나면 접수가 거부하므로 그것으로는 못 가른다.
                w.mw.adopt(patrol(), emptyList()),
                w.mw.resume(patrol(), ROBOT, ActiveMission(InspectAsset(), null)),
            ).forEach { answer ->
                val rejected = assertIs<Middleware.Submission.Rejected>(answer)
                assertTrue(rejected.reason.startsWith(Middleware.FORGOTTEN_JOB_ORDER), rejected.reason)
                assertTrue(exec.executionId in rejected.reason, rejected.reason)
            }
            repeat(3) { w.mw.pump() }
            assertTrue(w.mw.executions().isEmpty(), "놓은 작업 지시가 새 실행으로 섰다")
            assertEquals(issued, w.tasks().size, "놓은 작업 지시의 태스크가 다시 나갔다")
        }
    }

    @Test
    fun `끝나고 놓기를 거듭해도 실행 표와 아웃박스가 비어 있다`() {
        World().use { w ->
            val ids = (1..4).map { n ->
                val exec = w.settled(patrol("PATROL-$n"))
                w.ackAll()
                assertEquals(ForgetOutcome.Forgotten, w.mw.forget(exec.executionId))
                assertTrue(w.mw.executions().isEmpty(), "${n}번째를 놓은 뒤 표가 안 비었다")
                assertTrue(w.mw.responses().isEmpty(), "${n}번째를 놓은 뒤 아웃박스가 안 비었다")
                exec.executionId
            }
            assertEquals(ids.size, ids.toSet().size, "놓고 세운 실행들이 id 를 겹쳐 썼다: $ids")
        }
    }

    // ── 보존 상한(§15.296) — 담는 쪽이 놓지 않아도 표가 끝없이 늘지 않는다

    @Test
    fun `실행 표가 상한을 넘으면 끝나고 확인된 실행부터 이빅션한다`() {
        World(retention = Retention(executions = 1)).use { w ->
            val first = w.settled(patrol("PATROL-1"))
            w.ackAll()
            val second = w.settled(patrol("PATROL-2"))

            assertNull(w.mw.execution(first.executionId), "상한을 넘었는데 끝나고 확인된 실행이 남았다")
            assertNotNull(w.mw.execution(second.executionId), "확인 안 된 통보가 있는 실행을 놓았다")
            assertEquals(1, w.mw.retentionEvictions().executions)
            val again = assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-1"), ROBOT))
            assertTrue(again.reason.startsWith(Middleware.FORGOTTEN_JOB_ORDER), again.reason)
        }
    }

    @Test
    fun `확인 안 된 통보가 있는 실행은 상한을 넘어도 이빅션하지 않는다`() {
        World(retention = Retention(executions = 1)).use { w ->
            val first = w.settled(patrol("PATROL-1"))
            val second = w.settled(patrol("PATROL-2"))

            assertNotNull(w.mw.execution(first.executionId), "나르지 않은 통보와 함께 실행을 놓았다")
            assertNotNull(w.mw.execution(second.executionId))
            assertEquals(0, w.mw.retentionEvictions().executions)
        }
    }

    @Test
    fun `넘침을 EVICT 로 선언하면 놓은 작업 지시 표시가 상한을 넘을 때 오래된 것부터 내린다`() {
        World(retention = Retention(forgottenOrders = 1, forgottenOverflow = ForgottenOverflow.EVICT)).use { w ->
            listOf("PATROL-1", "PATROL-2").forEach { id ->
                val exec = w.settled(patrol(id))
                w.ackAll()
                assertEquals(ForgetOutcome.Forgotten, w.mw.forget(exec.executionId))
            }

            assertEquals(1, w.mw.retentionEvictions().forgottenOrders)
            assertEquals(ForgottenOrdersState(1, 1, ForgottenOverflow.EVICT, 0, 0), w.mw.forgottenOrders())
            // 내린 표시의 id 는 이 층이 더는 막지 못한다 — 선언이 그 막음을 담는 쪽의 기록에 넘겼다(§15.324).
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-1"), ROBOT))
            val kept = assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-2"), ROBOT))
            assertTrue(kept.reason.startsWith(Middleware.FORGOTTEN_JOB_ORDER), kept.reason)
        }
    }

    // ── 넘침 선언과 놓는 입구(§15.324) — 선언하지 않으면 표시를 내리지 않는다

    @Test
    fun `놓은 작업 지시 표시가 상한에 차면 기본은 실행과 통보를 그대로 두고 놓기를 거부한다`() {
        // ★표시 없이 실행만 빼면 같은 id 가 조용히 새 실행으로 선다. 거부는 실행·아웃박스를 건드리기 전이어야 한다.
        World(retention = Retention(forgottenOrders = 1)).use { w ->
            val first = w.settled(patrol("PATROL-1"))
            w.ackAll()
            assertEquals(ForgetOutcome.Forgotten, w.mw.forget(first.executionId), "상한 1 의 첫 놓기를 거부했다")
            val second = w.settled(patrol("PATROL-2"))
            w.ackAll()
            val responses = w.mw.responses().filter { it.executionId == second.executionId }
            assertTrue(responses.isNotEmpty(), "둘째 실행의 통보가 없다 — 그대로 남는지를 물을 수 없다")

            val refused = assertIs<ForgetOutcome.Refused>(w.mw.forget(second.executionId))
            assertTrue("상한에 찼다" in refused.reason && "releaseForgotten" in refused.reason, refused.reason)
            assertNotNull(w.mw.execution(second.executionId), "거부해 놓고 실행을 뺐다")
            assertEquals(responses, w.mw.responses().filter { it.executionId == second.executionId }, "거부해 놓고 통보를 뺐다")
            assertEquals(0, w.mw.retentionEvictions().forgottenOrders, "선언하지 않았는데 표시를 내렸다")
            assertEquals(ForgottenOrdersState(1, 1, ForgottenOverflow.REFUSE, 1, 0), w.mw.forgottenOrders())
            val again = assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-1"), ROBOT))
            assertTrue(again.reason.startsWith(Middleware.FORGOTTEN_JOB_ORDER), again.reason)

            // 담는 쪽이 끝난 작업 지시를 제 기록에 적고 표시를 비운다. 둘째 부름과 표에 아직 있는 실행은 거짓이다.
            assertTrue(w.mw.releaseForgotten("PATROL-1"))
            assertFalse(w.mw.releaseForgotten("PATROL-1"), "이미 지운 표시를 다시 지웠다고 냈다")
            assertFalse(w.mw.releaseForgotten("PATROL-2"), "실행이 표에 있는 작업 지시의 표시를 지웠다고 냈다")
            assertEquals(ForgetOutcome.Forgotten, w.mw.forget(second.executionId))
            assertEquals(ForgottenOrdersState(1, 1, ForgottenOverflow.REFUSE, 1, 1), w.mw.forgottenOrders())

            // 지운 id 는 처음 보는 것으로 받는다 — 막는 것은 이제 담는 쪽의 기록이다. 실행 id 는 다시 쓰지 않는다.
            val fresh = assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-1"), ROBOT)).execution
            assertNotEquals(first.executionId, fresh.executionId)
            assertNotEquals(second.executionId, fresh.executionId)
        }
    }

    @Test
    fun `놓은 작업 지시 표시가 차 있으면 라운드 끝 정리가 그 라운드에서 멈추고 실행 표는 상한을 넘은 채 남는다`() {
        World(retention = Retention(executions = 1, forgottenOrders = 1)).use { w ->
            val first = w.settled(patrol("PATROL-1"))
            w.ackAll()
            assertEquals(ForgetOutcome.Forgotten, w.mw.forget(first.executionId))
            val second = w.settled(patrol("PATROL-2"))
            w.ackAll()
            val third = w.settled(patrol("PATROL-3"))
            w.ackAll()

            // 놓을 수 있는 실행이 둘인데 한 라운드에 거부가 하나만 는다 — 첫 거부에서 정리를 멈췄다.
            val before = w.mw.forgottenOrders().refusedForgets
            assertTrue(before > 0, "표가 찼는데 정리가 놓기를 시도하지 않았다")
            w.mw.pump()
            assertEquals(before + 1, w.mw.forgottenOrders().refusedForgets, "찬 표를 보고도 이 라운드에 놓기를 더 불렀다")
            assertEquals(setOf(second.executionId, third.executionId), w.mw.executions().map { it.executionId }.toSet())
            assertEquals(0, w.mw.retentionEvictions().executions)
            assertEquals(0, w.mw.retentionEvictions().forgottenOrders)

            // 표시를 비우면 다음 라운드의 정리가 다시 놓는다.
            assertTrue(w.mw.releaseForgotten("PATROL-1"))
            w.mw.pump()
            assertNull(w.mw.execution(second.executionId), "표시를 비웠는데 정리가 오래된 실행을 놓지 않았다")
            assertNotNull(w.mw.execution(third.executionId))
            assertEquals(1, w.mw.retentionEvictions().executions)
        }
    }
}

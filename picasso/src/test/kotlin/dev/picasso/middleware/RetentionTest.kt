package dev.picasso.middleware

import dev.picasso.capability.Remedy
import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 보존 상한(§15.166) — 이 층이 메모리에 드는 기록은 상한을 넘으면 오래된 것부터 내린다. 단 구동이 돌리는 실행의 인시던트 번들과
 * 소모 기록은 남긴다. 내린 제안과 소모 기록은 «제안이 없다» 로 거부되고, 그 답은 «선 적이 없다» 고 단정하지 않는다.
 */
class RetentionTest {

    // ── 인시던트 번들 — 실제 미들웨어로

    private class World(retention: Retention) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to QUAD, OTHER to QUAD))
        val mw = Middleware(FaultBlindRobotPort(ClientRobotPort(harness.client())), now = { harness.clock.now() }, retention = retention)

        fun tasks(robotId: String) =
            harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(robotId).build()).tasksList

        fun drive(rounds: Int = 120, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다")
        }

        /** 점검 단위에 결함을 걸어 `PARTIAL` 로 끝낸다 — 인시던트가 하나 서고 구동은 아직 그 실행을 돌린다. */
        fun partial(robotId: String, jobOrderId: String): Middleware.Execution {
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(patrol(jobOrderId), robotId)).execution
            val inspect = "$jobOrderId#$TARGET"
            drive { tasks(robotId).any { it.taskId == inspect && it.taskState == "RUNNING" } }
            harness.oracle.forceFault(
                ForceFaultRequest.newBuilder().setRobotId(robotId).setErrorType("LOCALIZATION_LOST").setTaskId(inspect).build(),
            )
            drive { exec.physicalState == PhysicalState.PARTIAL && exec.active == null && mw.incidents().any { it.executionId == exec.executionId } }
            return exec
        }

        override fun close() = harness.close()
    }

    @Test
    fun `번들이 상한을 넘으면 구동이 끝난 실행의 것부터 내린다`() {
        World(Retention(incidents = 1)).use { w ->
            val first = w.partial(ROBOT, "PATROL-1")
            assertTrue(w.mw.cancel(first.executionId))
            w.drive { first.physicalState == PhysicalState.ABORTED && !first.physicalState.pumps }

            val second = w.partial(ROBOT, "PATROL-2")

            assertTrue(w.mw.incidents().none { it.executionId == first.executionId }, "끝난 실행의 번들이 상한을 넘어 남았다")
            assertTrue(w.mw.incidents().any { it.executionId == second.executionId }, "방금 연 번들을 내렸다")
            assertTrue(w.mw.retentionEvictions().incidents >= 1, "내린 수를 안 셌다: ${w.mw.retentionEvictions()}")
        }
    }

    @Test
    fun `구동이 돌리는 실행의 번들은 상한을 넘어도 남긴다`() {
        World(Retention(incidents = 1)).use { w ->
            val first = w.partial(ROBOT, "PATROL-1")
            val second = w.partial(OTHER, "PATROL-2")
            assertTrue(first.physicalState.pumps && second.physicalState.pumps)

            assertTrue(w.mw.incidents().any { it.executionId == first.executionId }, "운영자가 다루는 실행의 근거를 내렸다")
            assertTrue(w.mw.incidents().any { it.executionId == second.executionId })
            assertEquals(0, w.mw.retentionEvictions().incidents)
        }
    }

    // ── 승인 장부 — 장부만 따로

    /** 장부가 기체에 묻는 일이 없어야 하는 자리만 쓴다. 물으면 시험이 깨진다. */
    private class Silent : RobotPort {
        override fun capabilities(robotId: String): Capability = error("묻지 않아야 한다")
        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse =
            error("묻지 않아야 한다")
        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> = error("묻지 않아야 한다")
        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = error("묻지 않아야 한다")
        override fun snapshot(robotId: String): RobotSnapshot? = error("묻지 않아야 한다")
        override fun replay(robotId: String, from: Long): Replay? = error("묻지 않아야 한다")
    }

    private fun desk(retention: Retention, live: (String) -> Boolean = { false }) =
        RemedyDesk(Silent(), Entitlements.None, 0, { T0 }, { T0 }, { _, _ -> null }, retention, live)

    private val found = Remedy.Found(listOf(RemedyStep("grip", emptyList(), HoldKind.HOLD_KIND_HOLDING, HoldKind.HOLD_KIND_EMPTY)))

    private fun propose(desk: RemedyDesk, jobOrderId: String) =
        desk.record(ROBOT, patrol(jobOrderId), Middleware.Submission.Rejected("막혔다", remedy = found))

    private fun consumed(executionId: String) = ConsumedApproval(OPERATOR, T0, T0, executionId, emptyList())

    private fun judge(desk: RemedyDesk, jobOrderId: String) =
        assertIs<RemedyDesk.Judgment.No>(desk.judge(ROBOT, jobOrderId, OPERATOR, null, null))

    @Test
    fun `제안이 상한을 넘으면 가장 오래 선 것을 내리고 그 승인은 제안이 없다고 거부한다`() {
        val desk = desk(Retention(proposals = 1))
        propose(desk, "PATROL-1")
        propose(desk, "PATROL-2")

        assertNull(desk.proposal(ROBOT, "PATROL-1"), "오래된 제안이 상한을 넘어 남았다")
        assertNotNull(desk.proposal(ROBOT, "PATROL-2"), "방금 세운 제안을 내렸다")
        val no = judge(desk, "PATROL-1")
        assertEquals(ApprovalRefusal.NO_PROPOSAL, no.refusal)
        assertTrue("보존 상한" in no.reason, "내린 제안을 선 적이 없다고 단정했다: ${no.reason}")
        assertEquals(1, desk.evictions(0, 0, 0).proposals)
    }

    @Test
    fun `탐색 기록은 상한을 넘으면 오래된 것부터 내린다`() {
        val desk = desk(Retention(remedySearches = 1))
        propose(desk, "PATROL-1")
        propose(desk, "PATROL-2")

        assertEquals(listOf("PATROL-2"), desk.remedySearches().map { it.jobOrderId })
        assertEquals(1, desk.evictions(0, 0, 0).remedySearches)
    }

    @Test
    fun `소모 기록이 상한을 넘으면 오래된 것을 내리고 다시 온 승인은 제안이 없다고 받는다`() {
        val desk = desk(Retention(consumedApprovals = 1))
        desk.restore(ROBOT, "PATROL-1", consumed("exec-1"))
        desk.restore(ROBOT, "PATROL-2", consumed("exec-2"))

        val gone = judge(desk, "PATROL-1")
        assertEquals(ApprovalRefusal.NO_PROPOSAL, gone.refusal)
        assertTrue("보존 상한" in gone.reason, gone.reason)
        assertEquals(ApprovalRefusal.CONSUMED, judge(desk, "PATROL-2").refusal, "방금 적은 소모 기록을 내렸다")
        assertEquals(1, desk.evictions(0, 0, 0).consumedApprovals)
    }

    @Test
    fun `구동이 돌리는 실행의 소모 기록은 상한을 넘어도 남긴다`() {
        val desk = desk(Retention(consumedApprovals = 1), live = { it == "exec-1" })
        desk.restore(ROBOT, "PATROL-1", consumed("exec-1"))
        desk.restore(ROBOT, "PATROL-2", consumed("exec-2"))

        assertEquals(ApprovalRefusal.CONSUMED, judge(desk, "PATROL-1").refusal, "도는 실행의 소모 기록을 내렸다")
        assertEquals(ApprovalRefusal.CONSUMED, judge(desk, "PATROL-2").refusal)
        assertEquals(0, desk.evictions(0, 0, 0).consumedApprovals)
    }

    @Test
    fun `상한이 양수가 아니면 만들지 않는다`() {
        assertFailsWith<IllegalArgumentException> { Retention(incidents = 0) }
    }

    private companion object {
        const val ROBOT = "quad-01"
        const val OTHER = "quad-02"
        const val TARGET = "PUMP-01"
        val QUAD: Path = Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()
        val T0: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)

        fun patrol(jobOrderId: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = listOf(
                EquipmentRequirement(TARGET, EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM")),
            ),
        )
    }
}

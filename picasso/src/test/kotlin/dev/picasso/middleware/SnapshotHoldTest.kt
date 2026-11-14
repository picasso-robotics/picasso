package dev.picasso.middleware

import dev.picasso.capability.Remedy
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 기체 스냅숏의 손(계약 `GetSnapshotResponse.hold`, 0.11.0)으로 도는 단위가 없는 기체에도 계획 시점 사슬 검사를 건다(§15.150·§15.316).
 *
 * 앞 판은 도는 단위가 없으면 손을 볼 길이 없어, 든 채 쉬는 기체에 낸 작업 지시가 접수되고 기체에서야 `PRECONDITION_UNMET` 으로 거부되었다 —
 * 조치 탐색도 제안도 서지 않았다. 스냅숏의 손은 물을 때마다 다시 읽은 값이라 리뷰 C2(§15.146)가 버린 «다시 볼 길 없는 종착 파지» 가 아니다.
 *
 * **남이 쥔 기체에는 제안을 세우지 않는다.** 그 손에 든 것이 다른 실행의 일일 수 있다. 거절은 하되(알고도 보내지 않는다) 조치 제안은 세우지 않고,
 * 제안이 선 뒤 점유가 생기면 승인 접수도 받지 않는다.
 */
class SnapshotHoldTest {

    /** 시험이 선언 목록을 든다. */
    private class Declared(val rows: Map<String, Entitlement>) : Entitlements {
        override fun declaredFor(approverId: String): Entitlement? = rows[approverId]
    }

    private class World(port: (RobotPort) -> RobotPort = { it }) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to PRECOND, SPARE to PRECOND))
        val cell = CellMimic(now = { harness.clock.now() })
        /** 발신자가 실제로 내는 스냅숏 — 시험의 전제를 이것으로 확인한다. */
        val raw = ClientRobotPort(harness.client())
        val robots = port(raw)
        val mw = Middleware(
            robots,
            cell,
            now = { harness.clock.now() },
            entitlements = Declared(
                mapOf(
                    AGENT.id to Entitlement(
                        AGENT.id,
                        listOf(DeclaredAction(PrepareSequencedRack.SKILL, mapOf(PrepareSequencedRack.P_DESTINATION to DROP_SLOT))),
                        setOf(ROBOT),
                        Instant.parse("2099-01-01T00:00:00Z"),
                    ),
                ),
            ),
        )

        private var racks = 0

        fun drive(rounds: Int = 120, step: Duration = Duration.ofSeconds(1), until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(step)
                Thread.sleep(40)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다")
        }

        fun rack(): JobOrder = JobOrder(
            jobOrderId = "SEQ-${++racks}",
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = 17,
            requiredEvidence = Evidence.E2,
            materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", 1)),
            equipmentRequirements = listOf(
                EquipmentRequirement("RACK-204.S0$racks", "destination", mapOf("material" to "ENGINE-COVER-A")),
                EquipmentRequirement("SEQ-IN-02.BIN-A", "source", mapOf("material" to "ENGINE-COVER-A")),
            ),
        ).also { order ->
            order.equipmentRequirements.filter { it.equipmentUse == "destination" }.forEach { cell.program(it.id, it.properties["material"]) }
        }

        /** 든 채로 실패해 운영자 보류에 선 랙. 도는 단위가 없고 기체는 든 채다. 새 접수를 막지 않는 실패다. */
        fun heldOnHold(): Middleware.Execution {
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(rack(), ROBOT)).execution
            val first = exec.units.first()
            drive { first.hold.kind == HoldKind.HOLD_KIND_HOLDING }
            harness.oracle.forceFault(
                ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType("SKILL_EXECUTION_FAILED").setTaskId(first.taskId).build(),
            )
            drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }
            assertNull(exec.active, "이 시험이 보려는 경우가 아니다 — 도는 단위가 있다")
            assertEquals(HoldKind.HOLD_KIND_HOLDING, raw.snapshot(ROBOT)?.hold?.kind, "이 시험이 보려는 경우가 아니다 — 발신자 스냅숏이 든 채가 아니다")
            return exec
        }

        /** 보류를 사람이 닫는다 — 실행이 정착해 기체를 놓는다. 손은 그대로 든 채다. */
        fun close(exec: Middleware.Execution) {
            val unit = exec.units.first { it.state == UnitState.OPERATOR_HOLD }
            assertIs<ResolveOutcome.Resolved>(mw.resolve(exec.executionId, unit.unitId, OperatorDecision.CONFIRM_DONE, OPERATOR))
            drive { exec.physicalState.isSettled }
            assertTrue(!exec.physicalState.occupiesRobot, "닫은 실행이 아직 기체를 쥔다: ${exec.physicalState}")
        }

        override fun close() = harness.close()
    }

    @Test
    fun `남이 쥔 기체는 스냅숏의 손으로 거절하되 제안을 세우지 않고, 놓인 뒤에는 제안이 서서 값 없이 승인된다`() {
        World().use { w ->
            val held = w.heldOnHold()

            // 보류가 기체를 쥔 동안 — 알고도 보내지 않는다. 그 손에 든 것은 보류 실행의 일일 수 있어 제안은 세우지 않는다.
            val blocked = assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol(), ROBOT))
            assertTrue("기체 스냅숏의 파지" in blocked.reason, blocked.reason)
            assertTrue(held.executionId in blocked.reason, blocked.reason)
            assertNull(blocked.remedy, "남이 쥔 기체에 조치 열을 실었다")
            assertNull(w.mw.proposal(ROBOT, "PATROL-1"), "남이 쥔 기체에 제안이 섰다")
            assertIs<RemedyOutcome.Found>(w.mw.remedySearches().last().outcome, "찾은 것은 적어야 한다 — 안 찾아본 것과 같은 침묵이 된다")
            val refused = assertIs<ApprovalOutcome.Refused>(w.mw.attemptApproval(attempt()))
            assertEquals(ApprovalRefusal.NO_PROPOSAL, refused.refusal)
            assertTrue(w.mw.executions().none { it.order.jobOrderId == "PATROL-1" })
            assertEquals(PhysicalState.OPERATOR_HOLD, held.physicalState)

            // 사람이 보류를 닫으면 기체를 쥔 실행이 없다. 같은 손, 같은 작업 지시 — 이번에는 제안이 선다.
            w.close(held)
            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol(), ROBOT))
            assertTrue("기체 스냅숏의 파지" in rejected.reason, rejected.reason)
            assertIs<Remedy.Found>(assertNotNull(w.mw.proposal(ROBOT, "PATROL-1")))

            // 값 없는 승인 — 대상은 지금 스냅숏의 손에서, 목적지는 선언에서 온다. 도는 단위가 없어도 값이 찬다.
            val approved = assertIs<ApprovalOutcome.Approved>(w.mw.attemptApproval(attempt()))
            val step = approved.steps.single()
            assertEquals(PrepareSequencedRack.SKILL, step.skillType)
            assertEquals(DROP_SLOT, step.parameters[PrepareSequencedRack.P_DESTINATION])
            assertEquals(w.robots.snapshot(ROBOT)?.hold?.objectRef, step.parameters[PrepareSequencedRack.P_OBJECT])
            val exec = assertNotNull(w.mw.execution(approved.executionId))
            val remedy = exec.units.first()
            assertTrue(remedy.unitId.startsWith("remedy-"), exec.units.map { it.unitId }.toString())
            w.drive { remedy.taskId.isNotEmpty() }
        }
    }

    @Test
    fun `제안이 선 뒤 기체를 쥐는 실행이 생기면 승인 접수는 받지 않고 제안은 그대로 둔다`() {
        World().use { w ->
            w.close(w.heldOnHold())
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol(), ROBOT))
            assertNotNull(w.mw.proposal(ROBOT, "PATROL-1"))

            // 다른 일이 그 기체를 쥔다(아직 첫 단위가 안 나가 도는 단위는 없다). 손은 여전히 스냅숏의 것이다.
            val other = assertIs<Middleware.Submission.Accepted>(w.mw.submit(w.rack(), ROBOT)).execution
            assertNull(other.active)
            // 값 없는 문: 남이 쥔 기체의 스냅숏 손으로는 값을 채우지 않는다 — 제안을 세운 규칙과 같은 규칙이다.
            val refused = assertIs<ApprovalOutcome.Refused>(w.mw.attemptApproval(attempt()))
            assertEquals(ApprovalRefusal.OBJECT_NOT_OBSERVED, refused.refusal, refused.reason)
            // 값을 든 사람의 문: 값은 채워져도 관문이 조치 열을 받지 않는다. 승인 접수가 조치 열을 붙여 다시 관문을 지나기 때문이다.
            val person = assertIs<Middleware.Submission.Rejected>(w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(place), OPERATOR))
            assertEquals(ApprovalRefusal.REFUSED_BY_GATE, person.approvalRefusal, person.reason)
            assertTrue(other.executionId in person.reason, person.reason)
            assertNotNull(w.mw.proposal(ROBOT, "PATROL-1"), "승인 시점 거절이 제안을 지웠다")
            assertTrue(w.mw.executions().none { it.order.jobOrderId == "PATROL-1" })
        }
    }

    @Test
    fun `손을 싣지 않는 옛 발신자면 지금처럼 접수하고 발신자가 판정한다`() {
        // 말하지 않은 손을 빈손으로도 든 채로도 지어내지 않는다 — 출발점이 없으면 사슬을 안 본다.
        World(port = { HandSilentRobotPort(it) }).use { w ->
            w.close(w.heldOnHold())
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol(), ROBOT))
        }
    }

    /** 선언을 읽다가 못 읽게 되는 포트. 못 읽으면 관문이 사슬 검사를 건너뛴다(`AdmissionGate.chainOf` 의 첫 줄). */
    private class Blinding(private val delegate: RobotPort) : RobotPort by delegate {
        val blind = mutableSetOf<String>()
        override fun capabilities(robotId: String) = if (robotId in blind) null else delegate.capabilities(robotId)
    }

    @Test
    fun `거절만 받아 가는 후보는 떨어지면 다른 후보처럼 제안이 서고 실행은 서지 않는다`() {
        World().use { w ->
            w.close(w.heldOnHold())
            val before = w.mw.executions().size
            val out = assertIs<Unassigned>(w.mw.assign(patrol(), listOf(ROBOT), trialOnly = setOf(ROBOT)))
            assertTrue("기체 스냅숏의 파지" in assertNotNull(out.refusals[ROBOT]), out.toString())
            assertEquals(emptyList(), out.trialPassed)
            assertIs<Remedy.Found>(assertNotNull(w.mw.proposal(ROBOT, "PATROL-1")))
            assertEquals(before, w.mw.executions().size)
            // 제안은 보통의 제안이다 — 값 없는 문이 그대로 승인한다.
            assertIs<ApprovalOutcome.Approved>(w.mw.attemptApproval(attempt()))
        }
    }

    @Test
    fun `거절만 받아 가는 후보가 관문을 통과하면 채택하지 않고 다음 후보로 간다`() {
        World().use { w ->
            w.close(w.heldOnHold())
            // 빈손 기체를 시도 전용으로 넘긴다 — 관문은 통과로 답하지만 채택하지 않는다. 실행도 제안도 대장 줄도 없다.
            val searched = w.mw.remedySearches().size
            val empty = assertIs<Unassigned>(w.mw.adopt(patrol(), listOf(SPARE), trialOnly = setOf(SPARE)))
            assertEquals(listOf(SPARE), empty.trialPassed)
            assertEquals(emptyMap(), empty.refusals)
            assertTrue(w.mw.executions().none { it.order.jobOrderId == "PATROL-1" })
            assertEquals(searched, w.mw.remedySearches().size, "통과한 시도에 대장 줄이 섰다")

            // 통과한 시도 전용 후보 뒤의 후보가 떨어지면 아무도 채택되지 않았으므로 떨어진 후보는 보통처럼 적힌다.
            val mixed = assertIs<Unassigned>(w.mw.adopt(patrol("PATROL-2"), listOf(SPARE, ROBOT), trialOnly = setOf(SPARE)))
            assertEquals(listOf(SPARE), mixed.trialPassed)
            assertEquals(setOf(ROBOT), mixed.refusals.keys)
            assertIs<Remedy.Found>(assertNotNull(w.mw.proposal(ROBOT, "PATROL-2")))
            assertNull(w.mw.proposal(SPARE, "PATROL-2"))
        }
    }

    @Test
    fun `선언을 못 읽어 사슬 검사를 건너뛴 시도 전용 후보에도 실행이 서지 않는다`() {
        var blinding: Blinding? = null
        World(port = { Blinding(it).also { b -> blinding = b } }).use { w ->
            w.close(w.heldOnHold())
            assertNotNull(blinding).blind += ROBOT
            // 미리 묻고 다시 내는 사이에 선언 읽기가 끊긴 장면 — 관문은 통과로 답한다. 시도 전용이라 실행은 서지 않는다.
            assertEquals(Admission.Passed, w.mw.admits(patrol(), ROBOT, InspectAsset().plan(patrol())))
            val out = assertIs<Unassigned>(w.mw.assign(patrol(), listOf(ROBOT), trialOnly = setOf(ROBOT)))
            assertEquals(listOf(ROBOT), out.trialPassed)
            assertTrue(w.mw.executions().none { it.order.jobOrderId == "PATROL-1" })
            assertNull(w.mw.proposal(ROBOT, "PATROL-1"))

            // 시도 전용 후보가 통과해도 뒤의 보통 후보는 채택된다 — 시도는 순위를 막지 않는다.
            val taken = assertIs<Middleware.Submission.Accepted>(w.mw.adopt(patrol("PATROL-3"), listOf(ROBOT, SPARE), trialOnly = setOf(ROBOT)))
            assertEquals(SPARE, taken.execution.robotId)
        }
    }

    @Test
    fun `시도 전용 후보에 같은 작업 지시의 실행이 있으면 개정하지 않고 거절한다`() {
        World().use { w ->
            // 잔여 파지의 원천이 그 실행이다. 같은 작업 지시를 버전을 올려 시도로 넘기면 개정이 정착한 실행을 되살릴 수 있다.
            val held = w.heldOnHold()
            val state = held.physicalState
            val count = w.mw.executions().size
            val version = held.order.version
            val bumped = held.order.copy(version = held.order.version + 1)
            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.assign(bumped, listOf(ROBOT), trialOnly = setOf(ROBOT)))
            assertTrue("시도 전용" in refused.reason && held.executionId in refused.reason, refused.reason)
            assertEquals(state, held.physicalState)
            assertEquals(version, held.order.version, "개정이 실행의 작업 지시를 바꿨다")
            assertEquals(count, w.mw.executions().size)
        }
    }

    private val place = mapOf(PrepareSequencedRack.P_OBJECT to "ENGINE-COVER-A", PrepareSequencedRack.P_DESTINATION to DROP_SLOT)

    private fun attempt() = ApprovalAttempt(AGENT, ROBOT, "PATROL-1", listOf(PrepareSequencedRack.SKILL))

    private fun patrol(jobOrderId: String = "PATROL-1") = JobOrder(
        jobOrderId = jobOrderId,
        workMasterId = InspectAsset.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E0,
        equipmentRequirements = listOf(
            EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1")),
        ),
    )

    private companion object {
        const val ROBOT = "hum-02"
        const val SPARE = "hum-04"
        const val DROP_SLOT = "DROP-01"
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)
        val AGENT = Approver("recovery-1", ApproverKind.AGENT)
        val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()
    }
}

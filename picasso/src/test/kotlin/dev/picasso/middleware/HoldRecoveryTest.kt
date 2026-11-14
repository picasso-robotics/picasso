package dev.picasso.middleware

import dev.picasso.capability.Remedy
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.Reference
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * **발신자 거부 뒤 제자리 회복**(§15.320, §15.150 의 남은 몫).
 *
 * 접수 때 손을 못 본 기체(손을 싣지 않는 옛 발신자 포트 등)에 낸 작업 지시는 관문을 지나고, 기체에서야 `PRECONDITION_UNMET`(파지 주어)으로 거부되어
 * 운영자 보류에 선다. 앞 판은 거기서 사람의 재작업·취소만 받았다. 이 판은 그 보류를 신선한 손으로 다시 보고 회복 제안을 세우며, **사람만** 그것을
 * 승인한다. 승인되면 같은 실행에 조치 열이 그 단위 바로 앞에 끼고 그 단위는 새 시도로 나간다. 조치가 `DONE` 아닌 채 끝나면 원래 단위는 나가지 않는다.
 *
 * 사람이 보류 실행을 닫는 걸음은 이제 운영자 취소 입구(사람 식별자 필수)가 사건의 판단 칸에 싣는다 — 이 파일의 끝 두 시험.
 */
class HoldRecoveryTest {

    /** 시험이 선언 목록을 든다. 값 없는 문이 회복 제안에 «자격 없음» 이 아니라 «사람만» 으로 답하는지 보려고 자격을 하나 둔다. */
    private class Declared(val rows: Map<String, Entitlement>) : Entitlements {
        override fun declaredFor(approverId: String): Entitlement? = rows[approverId]
    }

    /** 손을 말하지 않다가 [speak] 가 켜지면 말하는 포트 — 접수 때 손을 못 본 옛 발신자 포트를 흉내 낸다. 손 밖의 것은 그대로다. */
    private class LateHand(private val delegate: RobotPort) : RobotPort by delegate {
        var speak = false
        override fun snapshot(robotId: String): RobotSnapshot? = delegate.snapshot(robotId)?.let { if (speak) it else it.copy(hold = null) }
    }

    /**
     * [refuse] 의 태스크를 **처음 한 번** 파지 주어로 사전 조건 거부하고, [held] 가 켜진 동안 스냅숏의 손을 든 채로 보이는 포트. [holdOnRefuse] 면
     * 거부하는 순간 [held] 를 켠다 — 그 사이 손이 든 채가 된 기체다.
     *
     * 미믹으로는 «앞 단위가 끝난 뒤 손이 든 채가 되어 다음 단위가 거부되는» 장면을 못 만든다 — 미믹의 쥐는 스킬은 성공하면 빈손이고, 든 채 실패는 실행 중
     * 실패의 잔여 파지라 이 경로가 아니다(ADR 47). 그래서 끝난 앞 단위가 있는 재기동 장면과 «위반 없음» 장면만 이 더블로 짓는다. 다른 시험은 미믹의 손을 쓴다.
     */
    private class HoldRefusing(private val delegate: RobotPort, private val refuse: String, private val holdOnRefuse: Boolean) : RobotPort by delegate {
        var held = false
        private var refused = false
        override fun snapshot(robotId: String): RobotSnapshot? = delegate.snapshot(robotId)?.let {
            if (held) it.copy(hold = HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_HOLDING).setObjectRef("ENGINE-COVER-A").build()) else it
        }

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            if (taskId != refuse || refused) return delegate.start(robotId, taskId, revision, skillType, parameters)
            refused = true
            if (holdOnRefuse) held = true
            return StartTaskResponse.newBuilder().setRejection(
                Rejection.newBuilder()
                    .setCode(RejectionCode.REJECTION_CODE_PRECONDITION_UNMET)
                    .setDetail("HOLD: 요구=EMPTY 관측=HOLDING")
                    .addReferences(Reference.newBuilder().setKey(Reference.Key.KEY_PRECONDITION_SUBJECT).setValue("HOLD")),
            ).build()
        }
    }

    private class World(port: (RobotPort) -> RobotPort = { LateHand(it) }) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to PRECOND))
        val cell = CellMimic(now = { harness.clock.now() })

        /** 발신자가 실제로 내는 스냅숏 — 시험의 전제를 이것으로 확인한다. */
        val raw = ClientRobotPort(harness.client())
        val port = port(raw)
        val mw = middleware()

        fun middleware() = Middleware(
            port,
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

        fun drive(rounds: Int = 120, step: Duration = Duration.ofSeconds(1), mw: Middleware = this.mw, until: () -> Boolean) {
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

        fun forceFault(taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType("SKILL_EXECUTION_FAILED").setTaskId(taskId).build(),
        )

        private fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList

        /** 미믹이 든 태스크 id. */
        fun taskIds(): List<String> = tasks().map { it.taskId }

        /** 미믹에서 [unit] 의 태스크가 도는 중이 될 때까지 민다 — 결함은 도는 태스크에만 밀어 넣을 수 있다. */
        fun untilRunning(unit: ExecutionUnit) = drive { unit.taskId.isNotEmpty() && tasks().any { it.taskId == unit.taskId && "RUNNING" in it.taskState.toString() } }

        /** 미들웨어가 쥔 실행 없이 손만 든 채로 남은 기체([leaveHolding]) — 랙이 든 채 실패하고 사람이 운영자 취소로 닫는다. */
        fun leftHolding() {
            val rack = JobOrder(
                jobOrderId = "SEQ-1",
                workMasterId = PrepareSequencedRack.WORK_MASTER,
                version = 17,
                requiredEvidence = Evidence.E2,
                materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", 1)),
                equipmentRequirements = listOf(
                    EquipmentRequirement("RACK-204.S01", "destination", mapOf("material" to "ENGINE-COVER-A")),
                    EquipmentRequirement("SEQ-IN-02.BIN-A", "source", mapOf("material" to "ENGINE-COVER-A")),
                ),
            )
            cell.program("RACK-204.S01", "ENGINE-COVER-A")
            mw.leaveHolding(assertIs<Middleware.Submission.Accepted>(mw.submit(rack, ROBOT)).execution, harness) { drive(until = it) }
            assertEquals(HoldKind.HOLD_KIND_HOLDING, raw.snapshot(ROBOT)?.hold?.kind, "이 시험이 보려는 경우가 아니다 — 발신자 스냅숏이 든 채가 아니다")
        }

        /** 손을 못 본 채 접수된 순회가 발신자의 파지 거부로 보류에 선다. */
        fun refused(order: JobOrder = patrol()): Middleware.Execution {
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order, ROBOT), "손을 못 본 접수가 거절됐다 — 이 장면이 아니다").execution
            val target = exec.units.first()
            drive { target.state == UnitState.OPERATOR_HOLD && exec.physicalState == PhysicalState.OPERATOR_HOLD }
            assertEquals(RejectionCode.REJECTION_CODE_PRECONDITION_UNMET.name, target.failureClass)
            assertTrue("HOLD" in target.preconditionSubjects, target.preconditionSubjects.toString())
            return exec
        }

        /** 발신자가 손을 말하기 시작한 뒤의 한 라운드 — 재확인이 회복 제안을 세운다. */
        fun reveal() {
            (port as LateHand).speak = true
            mw.pump()
        }

        /** 지금 든 것을 내려놓는 값 — 사람이 그 자리에서 준다. */
        fun place(): Map<String, String> = mapOf(
            PrepareSequencedRack.P_OBJECT to assertNotNull(raw.snapshot(ROBOT)?.hold?.objectRef),
            PrepareSequencedRack.P_DESTINATION to DROP_SLOT,
        )

        fun incidentsOf(exec: Middleware.Execution, unitId: String) =
            mw.incidents().filter { it.executionId == exec.executionId && it.unitId == unitId }

        override fun close() = harness.close()
    }

    @Test
    fun `발신자의 파지 거부로 선 보류에 회복 제안이 서고 사람이 승인하면 같은 실행에서 조치 뒤 원래 단위가 새 시도로 나간다`() {
        World().use { w ->
            w.leftHolding()
            val exec = w.refused()
            val travel = exec.units.first()
            assertEquals(1, w.incidentsOf(exec, travel.unitId).size)
            assertTrue(w.incidentsOf(exec, travel.unitId).single().unresolved)

            // 손을 모르는 동안 — 보류는 그대로이고 제안도 탐색도 없다. 다음 라운드에 다시 본다.
            repeat(3) { w.mw.pump() }
            assertNull(w.mw.proposal(ROBOT, PATROL), "모르는 손으로 제안을 세웠다")
            assertTrue(w.mw.remedySearches().none { it.jobOrderId == PATROL }, "모르는 손으로 탐색을 적었다")
            assertEquals(UnitState.OPERATOR_HOLD, travel.state)

            // 손을 말하기 시작한다 — 신선한 스냅숏의 손으로 다시 본다.
            w.reveal()
            assertIs<RemedyOutcome.Found>(w.mw.remedySearches().last { it.jobOrderId == PATROL }.outcome)
            val proposal = assertNotNull(w.mw.proposal(ROBOT, PATROL), "회복 제안이 서지 않았다")
            val target = assertIs<ProposalTarget.Hold>(w.mw.proposalTarget(ROBOT, PATROL))
            assertEquals(ProposalTarget.Hold(1, travel.unitId, 0), target)
            assertEquals(1, w.incidentsOf(exec, travel.unitId).size, "재확인이 둘째 인시던트를 열었다")
            assertTrue(exec.eventTrail.any { it.kind == MiddlewareCodes.HOLD_RECHECK && "회복 제안이 섰다" in it.detail })

            // 사람만 승인한다 — 값 없는 문도, 회복 입구의 에이전트도.
            val agent = assertIs<ApprovalOutcome.Refused>(w.mw.attemptApproval(ApprovalAttempt(AGENT, ROBOT, PATROL, listOf(PrepareSequencedRack.SKILL))))
            assertEquals(ApprovalRefusal.HUMAN_APPROVAL_REQUIRED, agent.refusal, agent.reason)
            val byAgent = assertIs<Middleware.Submission.Rejected>(w.mw.approveRecovery(ROBOT, PATROL, target, proposal.steps, listOf(w.place()), AGENT))
            assertEquals(ApprovalRefusal.HUMAN_APPROVAL_REQUIRED, byAgent.approvalRefusal)
            assertNotNull(w.mw.proposal(ROBOT, PATROL), "거절이 제안을 지웠다")

            val count = w.mw.executions().size
            val approved = assertIs<Middleware.Submission.Accepted>(
                w.mw.approveRecovery(ROBOT, PATROL, target, proposal.steps, listOf(w.place()), OPERATOR),
            )
            assertSame(exec, approved.execution, "회복이 새 실행을 세웠다")
            assertEquals(count, w.mw.executions().size)
            assertEquals(listOf("remedy-1-pick_place", travel.unitId, "PUMP-01"), exec.units.map { it.unitId }, "조치가 그 단위 앞에 끼지 않았다")
            assertEquals(UnitState.PENDING to 1, travel.state to travel.attempt, "보류 단위가 새 시도의 대기로 돌아가지 않았다")
            assertEquals(PhysicalState.RUNNING, exec.physicalState)
            assertEquals(target, exec.approval?.position)
            assertEquals(OPERATOR, exec.approvedBy)

            // 조치가 내려놓고 끝나면 원래 단위가 새 태스크 id 로 나가고 거부되지 않는다.
            val remedy = exec.units.first()
            w.drive(rounds = 400) { remedy.state == UnitState.DONE && travel.state == UnitState.DONE }
            assertTrue(travel.taskId.endsWith("@r1"), "원래 단위가 같은 태스크 id 로 다시 나갔다: ${travel.taskId}")
            assertEquals(HoldKind.HOLD_KIND_EMPTY, w.raw.snapshot(ROBOT)?.hold?.kind)

            // 둘째 승인은 소모로 답한다 — 같은 대상이다.
            val again = assertIs<Middleware.Submission.Rejected>(w.mw.approveRecovery(ROBOT, PATROL, target, proposal.steps, listOf(w.place()), OPERATOR))
            assertEquals(ApprovalRefusal.CONSUMED, again.approvalRefusal, again.reason)
            assertEquals(target, assertNotNull(again.consumedApproval).target)
            assertEquals(exec.executionId, again.consumedApproval!!.executionId)

            // 인시던트는 여전히 하나이고 «조치 승인 적용됨» 걸음이 실렸다 — 회복 완료로 적지 않는다.
            val incident = w.incidentsOf(exec, travel.unitId).single()
            val step = assertNotNull(incident.resolution)
            assertEquals(IncidentDecision.REMEDY_APPLIED to OPERATOR, step.decision to step.decidedBy)
            assertTrue(exec.decisions[travel.unitId] == null, "승인된 조치 뒤 재발행을 사람의 재작업으로 적었다")
        }
    }

    @Test
    fun `제안이 선 뒤 사람이 재작업하면 승인은 조치를 끼우지 않고 본 조치 열이나 대상이 다르면 거절한다`() {
        World().use { w ->
            w.leftHolding()
            val exec = w.refused()
            val travel = exec.units.first()
            w.reveal()
            val proposal = assertNotNull(w.mw.proposal(ROBOT, PATROL))
            val target = assertIs<ProposalTarget.Hold>(w.mw.proposalTarget(ROBOT, PATROL))

            // 본 조치 열(유형·값·순서)이 다르면 — 유형이 같아도 값이 다르면 다른 열이다.
            val other = proposal.steps.map { it.copy(expectedHold = HoldKind.HOLD_KIND_HOLDING) }
            val changed = assertIs<Middleware.Submission.Rejected>(w.mw.approveRecovery(ROBOT, PATROL, target, other, listOf(w.place()), OPERATOR))
            assertEquals(ApprovalRefusal.PROPOSAL_CHANGED, changed.approvalRefusal, changed.reason)
            val doubled = assertIs<Middleware.Submission.Rejected>(
                w.mw.approveRecovery(ROBOT, PATROL, target, proposal.steps + proposal.steps, listOf(w.place(), w.place()), OPERATOR),
            )
            assertEquals(ApprovalRefusal.PROPOSAL_CHANGED, doubled.approvalRefusal)
            // 대상이 다르면.
            val elsewhere = assertIs<Middleware.Submission.Rejected>(
                w.mw.approveRecovery(ROBOT, PATROL, target.copy(attempt = 1), proposal.steps, listOf(w.place()), OPERATOR),
            )
            assertEquals(ApprovalRefusal.PROPOSAL_CHANGED, elsewhere.approvalRefusal)
            // 접수의 사람 문은 보류 대상을 승인하지 못한다.
            val admission = assertIs<Middleware.Submission.Rejected>(w.mw.approveRemedy(ROBOT, PATROL, listOf(w.place()), OPERATOR))
            assertEquals(ApprovalRefusal.PROPOSAL_CHANGED, admission.approvalRefusal)
            // 손에 든 물건과 사람이 준 대상 이름이 다르면 — 파지 종류만 굴리는 재검사는 이것을 못 본다.
            val wrongObject = w.place() + (PrepareSequencedRack.P_OBJECT to "ENGINE-COVER-Z")
            val mismatched = assertIs<Middleware.Submission.Rejected>(w.mw.approveRecovery(ROBOT, PATROL, target, proposal.steps, listOf(wrongObject), OPERATOR))
            assertEquals(ApprovalRefusal.REFUSED_BY_GATE, mismatched.approvalRefusal, mismatched.reason)
            assertNotNull(w.mw.proposal(ROBOT, PATROL), "거절이 제안을 지웠다")
            assertTrue(exec.units.none { it.unitId.startsWith("remedy-") })

            // 사람이 재작업했다 — 대상은 더는 그 시도의 보류가 아니다. 조회에서 곧바로 안 보이고, 승인은 제안을 내리고 조치를 끼우지 않는다.
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, travel.unitId, OperatorDecision.REWORK, OPERATOR))
            assertNull(w.mw.proposal(ROBOT, PATROL), "낡은 회복 제안이 조회에 보인다")
            assertNull(w.mw.proposalTarget(ROBOT, PATROL))
            val stale = assertIs<Middleware.Submission.Rejected>(w.mw.approveRecovery(ROBOT, PATROL, target, proposal.steps, listOf(w.place()), OPERATOR))
            assertEquals(ApprovalRefusal.REMEDY_NOT_APPLIED, stale.approvalRefusal, stale.reason)
            assertTrue(exec.units.none { it.unitId.startsWith("remedy-") }, "낡은 제안의 조치가 끼었다")
            assertNull(w.mw.proposal(ROBOT, PATROL), "낡은 제안을 내리지 않았다")
            assertNull(exec.approval)
        }
    }

    @Test
    fun `조치가 실패하면 원래 단위도 뒤의 독립 단위도 나가지 않고 원래 단위가 둘째 인시던트 없이 운영자 보류에 선다`() {
        World().use { w ->
            w.leftHolding()
            val exec = w.refused(patrol(targets = 2))
            val travel = exec.units.first()
            val nextTravel = exec.units.single { it.unitId == "PUMP-02.travel" }
            w.reveal()
            val target = assertIs<ProposalTarget.Hold>(w.mw.proposalTarget(ROBOT, PATROL))
            assertIs<Middleware.Submission.Accepted>(
                w.mw.approveRecovery(ROBOT, PATROL, target, assertNotNull(w.mw.proposal(ROBOT, PATROL)).steps, listOf(w.place()), OPERATOR),
            )
            val remedy = exec.units.first()
            w.untilRunning(remedy)
            w.forceFault(remedy.taskId)
            w.drive(rounds = 200) { remedy.state != UnitState.RUNNING && exec.physicalState == PhysicalState.OPERATOR_HOLD }
            repeat(5) { w.mw.pump() }

            assertTrue(remedy.state != UnitState.DONE, "조치가 실패하지 않았다 — 이 장면이 아니다: ${remedy.state}")
            assertEquals(UnitState.OPERATOR_HOLD, travel.state, "조치가 실패했는데 원래 단위가 보류에 서지 않았다")
            assertEquals(MiddlewareCodes.REMEDY_NOT_DONE, travel.failureClass)
            assertEquals("", travel.taskId, "조치가 실패했는데 원래 단위가 나갔다")
            assertTrue(w.taskIds().none { it.startsWith("$PATROL#${travel.unitId}@r") }, "기체에 원래 단위의 새 시도가 갔다: ${w.taskIds()}")
            assertEquals(UnitState.PENDING to "", nextTravel.state to nextTravel.taskId, "뒤의 독립 단위가 나갔다")
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState)
            assertEquals(1, w.incidentsOf(exec, travel.unitId).size, "미발행 보류가 둘째 인시던트를 열었다")
            assertTrue(w.mw.pending().last().operatorRequired)

            // 그 뒤 사람의 걸음이 버려지지 않는다 — 원래 단위의 인시던트는 판단 칸이 차 있으므로 실패한 조치의 인시던트에 실린다.
            assertEquals(ResolveOutcome.Resolved, w.mw.cancelByOperator(exec.executionId, OPERATOR))
            val stepped = w.mw.incidents().filter { it.executionId == exec.executionId && it.resolution?.decision == IncidentDecision.OPERATOR_CANCEL }
            assertEquals(listOf(remedy.unitId), stepped.map { it.unitId }, "조치 실패 뒤 사람의 걸음이 사라졌다")
            assertEquals(OPERATOR, stepped.single().resolution!!.decidedBy)
            assertTrue(""""decision":"OPERATOR_CANCEL"""" in LedgerExport.incidents(stepped), "내보낸 인시던트에 사람의 걸음이 없다")
            assertEquals(IncidentDecision.REMEDY_APPLIED, w.incidentsOf(exec, travel.unitId).single().resolution?.decision, "승인 걸음을 덮었다")
        }
    }

    @Test
    fun `조치 단위의 이름이 계획의 단위 id 와 겹치면 접미를 붙여 피한다`() {
        // 점검 대상의 설비 id 가 곧 단위 id 다 — 작업 지시가 `remedy-1-pick_place` 를 대상으로 낼 수 있다.
        val clash = "remedy-1-pick_place"
        val order = patrol().copy(
            equipmentRequirements = listOf(EquipmentRequirement(clash, EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1"))),
        )
        // 회복 경로.
        World().use { w ->
            w.leftHolding()
            val exec = w.refused(order)
            w.reveal()
            val target = assertIs<ProposalTarget.Hold>(w.mw.proposalTarget(ROBOT, PATROL))
            assertIs<Middleware.Submission.Accepted>(
                w.mw.approveRecovery(ROBOT, PATROL, target, assertNotNull(w.mw.proposal(ROBOT, PATROL)).steps, listOf(w.place()), OPERATOR),
            )
            assertEquals(listOf("$clash-2", "$clash.travel", clash), exec.units.map { it.unitId })
            assertEquals(listOf("$clash-2"), exec.approval!!.steps.map { it.unitId })
        }
        // 작업 수락 승인 경로.
        World(port = { it }).use { w ->
            w.leftHolding()
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(order, ROBOT))
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(ROBOT, PATROL, listOf(w.place()), OPERATOR)).execution
            assertEquals(listOf("$clash-2", "$clash.travel", clash), exec.units.map { it.unitId })
        }
    }

    @Test
    fun `승인 적용 직후의 판단 기록으로 다시 지으면 조치가 그 단위 앞에 서고 소모로 응답하며 끝난 앞 단위는 다시 돌지 않는다`() {
        val refuse = "$PATROL#PUMP-02.travel"
        var double: HoldRefusing? = null
        World(port = { HoldRefusing(it, refuse, holdOnRefuse = true).also { d -> double = d } }).use { w ->
            val order = patrol(targets = 2)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT)).execution
            val first = exec.units.first()
            val travel = exec.units.single { it.unitId == "PUMP-02.travel" }
            w.drive(rounds = 300) { travel.state == UnitState.OPERATOR_HOLD }
            assertEquals(UnitState.DONE, first.state, "앞 단위가 끝나지 않았다 — 이 장면이 아니다")
            val firstTask = first.taskId

            // 그 사이 손이 든 채가 됐다 — 재확인이 회복 제안을 세웠다.
            assertTrue(assertNotNull(double).held)
            val proposal = assertNotNull(w.mw.proposal(ROBOT, PATROL))
            val target = ProposalTarget.Hold(1, travel.unitId, 0)
            assertEquals(target, w.mw.proposalTarget(ROBOT, PATROL))
            val place = mapOf(PrepareSequencedRack.P_OBJECT to "ENGINE-COVER-A", PrepareSequencedRack.P_DESTINATION to DROP_SLOT)
            assertIs<Middleware.Submission.Accepted>(w.mw.approveRecovery(ROBOT, PATROL, target, proposal.steps, listOf(place), OPERATOR))
            double!!.held = false

            // 적용 직후, 펌프 전에 받은 기록 — 승인과 위치를 싣고 조치 태스크는 아직 안 나갔다.
            val record = JudgmentRecordWire.decode(JudgmentRecordWire.encode(assertNotNull(w.mw.judgmentRecord(exec.executionId))))
            assertEquals(target, assertNotNull(record.approval).position)
            assertEquals(listOf("remedy-1-pick_place"), record.approval!!.steps.map { it.unitId })
            assertEquals(1, record.units.single { it.unitId == travel.unitId }.attempt)
            assertTrue(w.taskIds().none { "remedy-" in it }, "기록을 받기 전에 조치가 나갔다")

            // 재기동 — 새 인스턴스가 기록으로 다시 짓는다.
            val again = w.middleware()
            val resumed = assertIs<Middleware.Submission.Accepted>(again.resume(order, ROBOT, ActiveMission(InspectAsset(), null), record)).execution
            assertEquals(
                listOf("PUMP-01.travel", "PUMP-01", "remedy-1-pick_place", "PUMP-02.travel", "PUMP-02"),
                resumed.units.map { it.unitId },
                "조치가 계획 맨 앞에 섰다 — 위치 칸을 읽지 않았다",
            )
            assertEquals(target, resumed.approval?.position)
            assertNull(again.proposal(ROBOT, PATROL), "다시 지은 뒤 회복 제안이 서 있다")
            val consumed = assertIs<Middleware.Submission.Rejected>(again.approveRecovery(ROBOT, PATROL, target, proposal.steps, listOf(place), OPERATOR))
            assertEquals(ApprovalRefusal.CONSUMED, consumed.approvalRefusal, consumed.reason)
            assertEquals(resumed.executionId, consumed.consumedApproval!!.executionId)

            // 끝난 앞 단위는 다시 돌지 않는다. 조치가 끝나면 원래 단위가 새 시도로 나간다.
            val rebuiltFirst = resumed.units.first()
            val remedy = resumed.units.single { it.unitId == "remedy-1-pick_place" }
            val rebuiltTravel = resumed.units.single { it.unitId == travel.unitId }
            w.drive(rounds = 400, mw = again) { remedy.state == UnitState.DONE && rebuiltTravel.state == UnitState.DONE }
            assertEquals(UnitState.DONE to firstTask, rebuiltFirst.state to rebuiltFirst.taskId)
            assertEquals(1, w.taskIds().count { it.startsWith("$PATROL#PUMP-01.travel") }, "끝난 앞 단위가 다시 나갔다: ${w.taskIds()}")
            assertTrue(rebuiltTravel.taskId.endsWith("@r1"), rebuiltTravel.taskId)
        }
    }

    @Test
    fun `다시 지은 실행은 기록의 거부 주어를 옮겨 다시 연 보류에서 회복 재확인이 다시 돈다`() {
        World().use { w ->
            w.leftHolding()
            val exec = w.refused()
            val travel = exec.units.first()
            // 손을 모르는 동안 받은 기록 — 회복 제안은 아직 없다. 판정은 보류이고 주어는 HOLD 다.
            val record = JudgmentRecordWire.decode(JudgmentRecordWire.encode(assertNotNull(w.mw.judgmentRecord(exec.executionId))))
            assertEquals(listOf("HOLD"), record.units.single { it.unitId == travel.unitId }.verdict?.preconditionSubjects)

            (w.port as LateHand).speak = true
            val again = w.middleware()
            val resumed = assertIs<Middleware.Submission.Accepted>(again.resume(patrol(), ROBOT, ActiveMission(InspectAsset(), null), record)).execution
            val reopened = resumed.units.single { it.unitId == travel.unitId }
            assertEquals(UnitState.OPERATOR_HOLD to listOf("HOLD"), reopened.state to reopened.preconditionSubjects, "거부 주어를 옮기지 않았다")
            assertEquals(listOf("HOLD"), again.incidents().single { it.unitId == travel.unitId }.preconditionSubjects, "다시 연 인시던트가 주어를 안 실었다")
            again.pump()
            assertEquals(ProposalTarget.Hold(1, travel.unitId, 0), again.proposalTarget(ROBOT, PATROL), "다시 연 보류에서 재확인이 돌지 않았다")
        }
    }

    @Test
    fun `작업 수락 승인을 든 실행의 발신자 거부에는 회복 제안 없이 탐색만 적는다`() {
        World(port = { it }).use { w ->
            w.leftHolding()
            // 손을 보는 접수 — 스냅숏의 손으로 거절되고 접수 제안이 선다. 사람이 승인해 조치를 든 실행이 선다.
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol(), ROBOT))
            assertEquals(ProposalTarget.Order, w.mw.proposalTarget(ROBOT, PATROL))
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(ROBOT, PATROL, listOf(w.place()), OPERATOR)).execution
            assertNull(exec.approval?.position)

            // 조치가 실패해 든 채로 남는다. 원래 단위는 나가서 발신자의 파지 거부로 보류에 선다(접수 승인에는 위치 칸이 없다).
            val remedy = exec.units.first()
            val travel = exec.units[1]
            w.untilRunning(remedy)
            w.forceFault(remedy.taskId)
            w.drive(rounds = 200) { travel.state == UnitState.OPERATOR_HOLD }
            assertEquals(RejectionCode.REJECTION_CODE_PRECONDITION_UNMET.name, travel.failureClass)
            repeat(3) { w.mw.pump() }

            assertIs<RemedyOutcome.Found>(w.mw.remedySearches().last().outcome, "찾은 것은 적어야 한다")
            assertEquals(PATROL, w.mw.remedySearches().last().jobOrderId)
            assertNull(w.mw.proposal(ROBOT, PATROL), "승인을 든 실행에 회복 제안이 섰다")
            assertNull(w.mw.proposalTarget(ROBOT, PATROL))
            assertTrue(exec.eventTrail.any { it.kind == MiddlewareCodes.HOLD_RECHECK && "제안을 세우지 않았다" in it.detail })
            val target = ProposalTarget.Hold(1, travel.unitId, travel.attempt)
            val refused = assertIs<Middleware.Submission.Rejected>(
                w.mw.approveRecovery(ROBOT, PATROL, target, listOf(), listOf(), OPERATOR),
            )
            assertEquals(ApprovalRefusal.NO_PROPOSAL, refused.approvalRefusal, refused.reason)
            assertEquals(1, w.mw.executions().count { it.order.jobOrderId == PATROL })
        }
    }

    @Test
    fun `신선한 손으로 굴려도 어긋나지 않으면 자취만 남기고 다시 내지 않는다`() {
        val refuse = "$PATROL#PUMP-01.travel"
        World(port = { HoldRefusing(it, refuse, holdOnRefuse = false) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol(), ROBOT)).execution
            val travel = exec.units.first()
            w.drive { travel.state == UnitState.OPERATOR_HOLD }
            repeat(5) { w.mw.pump() }
            assertTrue(exec.eventTrail.any { it.kind == MiddlewareCodes.HOLD_RECHECK && "어긋나지 않는다" in it.detail })
            assertEquals(1, exec.eventTrail.count { it.kind == MiddlewareCodes.HOLD_RECHECK }, "같은 시도를 두 번 판정했다")
            assertNull(w.mw.proposal(ROBOT, PATROL))
            assertTrue(w.mw.remedySearches().isEmpty())
            assertEquals(UnitState.OPERATOR_HOLD to 0, travel.state to travel.attempt, "위반이 없다고 자동으로 다시 냈다")
        }
    }

    // ── 운영자 취소(§15.320 — 한계 대장 §15.319 를 닫는다)

    @Test
    fun `운영자 취소는 사람의 걸음을 사건 판단 칸에 싣고 에이전트는 받지 않는다`() {
        World().use { w ->
            w.leftHolding()
            // [leftHolding] 이 운영자 취소로 닫았다 — 그 실행의 사건에 사람의 걸음이 실린다.
            val rack = w.mw.executions().single { it.order.jobOrderId == "SEQ-1" }
            val incident = w.mw.incidents().single { it.executionId == rack.executionId }
            val step = assertNotNull(incident.resolution, "운영자 취소가 사건에 안 실렸다")
            assertEquals(IncidentDecision.OPERATOR_CANCEL to CLOSING_OPERATOR, step.decision to step.decidedBy)
            assertTrue(""""decision":"OPERATOR_CANCEL"""" in LedgerExport.incidents(listOf(incident)))

            // 에이전트는 운영자 취소를 내지 못한다 — 실행은 그대로다.
            val exec = w.refused()
            val refused = assertIs<ResolveOutcome.Refused>(w.mw.cancelByOperator(exec.executionId, AGENT))
            assertTrue(AGENT.id in refused.reason)
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState)
            // 이름 없는 사람도 받지 않는다 — 빈 식별자는 무기명 걸음이다.
            listOf("", "  ").forEach { blank ->
                assertIs<ResolveOutcome.Refused>(w.mw.cancelByOperator(exec.executionId, Approver(blank, ApproverKind.PERSON)), "«$blank»")
            }
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState, "이름 없는 취소가 걸렸다")
            assertEquals(ResolveOutcome.NotHeld, w.mw.cancelByOperator("exec-none", OPERATOR))
        }
    }

    @Test
    fun `상류의 취소는 누가 했는지가 없어 사건 판단 칸에 싣지 않는다`() {
        World().use { w ->
            w.leftHolding()
            val exec = w.refused()
            assertTrue(w.mw.cancel(exec.executionId))
            w.drive { exec.physicalState.isSettled }
            assertNull(w.incidentsOf(exec, exec.units.first().unitId).single().resolution, "승인자 없는 취소가 판단 칸에 실렸다")
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val PATROL = "PATROL-1"
        const val DROP_SLOT = "DROP-01"
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)
        val AGENT = Approver("recovery-1", ApproverKind.AGENT)
        val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()

        fun patrol(targets: Int = 1) = JobOrder(
        jobOrderId = PATROL,
        workMasterId = InspectAsset.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E0,
            equipmentRequirements = (1..targets).map {
                EquipmentRequirement("PUMP-0$it", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-$it"))
            },
        )
    }
}

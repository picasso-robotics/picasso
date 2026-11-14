package dev.picasso.middleware

import dev.picasso.capability.HoldMismatch
import dev.picasso.capability.Remedy
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.harness.Harness
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **판단 기록으로 다시 짓기**(§15.221). 앞 인스턴스가 낸 판단 기록을 담는 쪽이 영속했다가 재기동 뒤 [Middleware.resume] 에
 * 넘기면, 다시 지은 실행은 그 판정과 판단이 사라진 적 없는 것처럼 이어 간다.
 *
 * 재기동은 **미들웨어를 새로 세우는 것**이다. 기체와 셀은 그대로 두고 같은 더블 위에 두 번째 [Middleware] 를 띄운다. 기록은
 * 언제나 인코딩을 한 번 지나 넘긴다([persisted]) — 담는 쪽이 저장소에서 읽어 오는 모양이다.
 *
 * 시험마다 **기록 없이 다시 지은 대조**를 함께 둔다. 기록 없이도 같은 답이 나오면 그 시험은 기록이 무엇을 옮기는지 말하지 못한다.
 */
class JudgmentRecordTest {

    // ── 운영자 판단

    @Test
    fun `운영자가 확인한 단위는 다시 지은 뒤 다시 보류에 서지 않는다`() {
        World().use { w ->
            val exec = w.submit(order(required = Evidence.E2))
            w.old.pump()
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_FAILED)
            w.cell.program(slot(1), PART)
            w.old.pump()
            assertEquals(UnitState.OPERATOR_HOLD, exec.units.single().state, "시험의 전제: 하류는 실패, 설비에는 있다")
            assertEquals(ResolveOutcome.Resolved, w.old.resolve(exec.executionId, "s1", OperatorDecision.CONFIRM_DONE, OPERATOR))
            val record = persisted(w.old.judgmentRecord(exec.executionId))

            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2), record)
            again.mw.pump()
            again.mw.pump()
            val unit = resumed.units.single()
            assertEquals(UnitState.DONE, unit.state, "확인한 단위가 다시 판정됐다: ${unit.note}")
            assertEquals(Evidence.E2, unit.reached)
            assertEquals(PhysicalState.PHYSICALLY_DONE, resumed.physicalState)
            assertEquals(emptyList(), again.mw.incidents(), "확인한 단위의 사건을 다시 열었다")
            val decision = assertNotNull(again.mw.judgmentRecord(resumed.executionId)!!.units.single().decision, "판단을 다시 내보내지 않는다")
            assertEquals(OperatorDecision.CONFIRM_DONE to OPERATOR, decision.decision to decision.approver)

            // 대조: 기록 없이 다시 지으면 같은 실패와 설비 근거로 다시 보류에 선다.
            val bare = w.restart()
            val rebuilt = bare.resumeWith(order(required = Evidence.E2), null)
            bare.mw.pump()
            bare.mw.pump()
            assertEquals(UnitState.OPERATOR_HOLD, rebuilt.units.single().state)
        }
    }

    @Test
    fun `재작업 판단이 서고 내기 전에 재기동해도 새 시도로 나가고 다시 묻지 않는다`() {
        World().use { w ->
            val exec = w.submit(order(required = Evidence.E2))
            w.old.pump()
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_FAILED)
            w.cell.program(slot(1), PART)
            w.old.pump()
            w.old.resolve(exec.executionId, "s1", OperatorDecision.REWORK, OPERATOR)
            val record = persisted(w.old.judgmentRecord(exec.executionId))
            val created = w.robot.created

            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2), record)
            assertEquals(1, resumed.units.single().attempt)
            again.mw.pump()
            assertEquals("$ORDER#s1@r1", w.robot.starts.last(), "재작업 판단이 내기 전에 사라졌다")
            assertEquals(created + 1, w.robot.created)
            assertEquals(UnitState.RUNNING, resumed.units.single().state)
            val decision = assertNotNull(again.mw.judgmentRecord(resumed.executionId)!!.units.single().decision)
            assertEquals(OperatorDecision.REWORK to 0, decision.decision to decision.attempt)
        }
    }

    // ── 감수한 결함

    @Test
    fun `감수한 결함은 다시 지은 뒤에도 감수한 채라 운영자를 다시 부르지 않는다`() {
        World().use { w ->
            w.robot.faults = listOf(Fault.newBuilder().setErrorType("LOW_BATTERY").setCanAcceptNewTask(false).build())
            val exec = w.submit(order(slots = 2))
            w.old.pump()
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState, "시험의 전제: 결함이 첫 단위를 막는다")
            assertTrue(w.old.release(exec.executionId))
            w.old.pump()
            assertEquals(UnitState.RUNNING, exec.units.first().state)
            val record = persisted(w.old.judgmentRecord(exec.executionId))

            val again = w.restart()
            val resumed = again.resumeWith(order(slots = 2), record)
            again.mw.pump()
            assertEquals(emptyList(), resumed.blockedBy, "감수한 결함이 다시 막았다")
            assertEquals(UnitState.RUNNING, resumed.units.first().state)
            assertEquals(PhysicalState.RUNNING, resumed.physicalState)

            val bare = w.restart()
            val rebuilt = bare.resumeWith(order(slots = 2), null)
            bare.mw.pump()
            assertEquals(listOf("LOW_BATTERY"), rebuilt.blockedBy.map { it.errorType })
        }
    }

    // ── 근거 윈도우와 기한

    @Test
    fun `근거 윈도우의 요청 시각이 넘어와 처음 요청 뒤의 설비 신호를 잠정 근거로 본다`() {
        World(lookup = ExecutionLookup.NONE).use { w ->
            w.robot.lost += "$ORDER#s1"
            val exec = w.submit(order())
            w.old.pump()
            assertEquals(UnitState.IN_DOUBT, exec.units.single().state, "시험의 전제: 첫 요청의 답을 잃었다")
            val record = persisted(w.old.judgmentRecord(exec.executionId))
            assertEquals(T0, record.units.single().requestedAt)

            // 재기동 사이에 설비가 부품을 봤다. 처음 요청 뒤의 일이므로 이 요청의 근거다.
            w.t = T0.plusSeconds(50)
            w.cell.programAt(slot(1), PART, T0.plusSeconds(5))
            val again = w.restart()
            val resumed = again.resumeWith(order(), record)
            again.mw.pump()
            again.mw.pump()
            val unit = resumed.units.single()
            assertEquals(UnitState.OPERATOR_HOLD, unit.state, "처음 요청 시각을 잃어 신호를 옛 것으로 읽었다: ${unit.note}")
            assertEquals(Verification.MATCHED, unit.verification)
            assertEquals(T0, unit.requestedAt)

            // 대조: 요청 시각이 재기동 시각이면 그 신호는 창 앞이라 유예가 끝날 때까지 기다린다.
            val bare = w.restart()
            val rebuilt = bare.resumeWith(order(), null)
            bare.mw.pump()
            bare.mw.pump()
            assertEquals(UnitState.IN_DOUBT, rebuilt.units.single().state)
        }
    }

    @Test
    fun `설비 대기의 기한은 처음 기다리기 시작한 시각부터 이어 잰다`() {
        World().use { w ->
            val exec = w.submit(order(workMaster = WaitThenSlot.WORK_MASTER))
            w.old.pump()
            assertEquals(UnitState.RUNNING, exec.units.first().state, "시험의 전제: 대기가 시작했다")
            val record = persisted(w.old.judgmentRecord(exec.executionId))

            w.t = T0.plusSeconds(50)
            val again = w.restart()
            val resumed = again.resumeWith(order(workMaster = WaitThenSlot.WORK_MASTER), record, WaitThenSlot())
            val bare = w.restart()
            val rebuilt = bare.resumeWith(order(workMaster = WaitThenSlot.WORK_MASTER), null, WaitThenSlot())
            again.mw.pump()
            bare.mw.pump()

            w.t = T0.plusSeconds(61)
            again.mw.pump()
            bare.mw.pump()
            val wait = resumed.units.first()
            assertEquals(UnitState.OPERATOR_HOLD, wait.state, "기한을 재기동 시각부터 다시 쟀다")
            assertEquals(WaitSpec.SIGNAL_DEADLINE, wait.failureClass)
            assertEquals(UnitState.RUNNING, rebuilt.units.first().state, "대조: 기록 없이는 기한이 재기동 시각부터다")
        }
    }

    @Test
    fun `검증 대기 중이던 단위의 근거 기한은 처음 완료 시각에서 잰다`() {
        World().use { w ->
            val exec = w.submit(order(required = Evidence.E2))
            w.old.pump()
            w.t = T0.plusSeconds(5)
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_SUCCEEDED)
            w.old.pump()
            assertEquals(UnitState.VERIFYING, exec.units.single().state, "시험의 전제: 설비 신호를 기다린다")
            val record = persisted(w.old.judgmentRecord(exec.executionId))
            assertEquals(VerifyWindow(T0.plusSeconds(5), T0.plusSeconds(20)), record.units.single().verifyWindow)

            w.t = T0.plusSeconds(30)
            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2), record)
            val bare = w.restart()
            val rebuilt = bare.resumeWith(order(required = Evidence.E2), null)
            repeat(2) {
                again.mw.pump()
                bare.mw.pump()
            }
            assertEquals(UnitState.UNVERIFIED, resumed.units.single().state, "근거 기한을 다시 관측한 완료 시각에서 다시 정했다")
            assertEquals(T0.plusSeconds(20), resumed.units.single().evidenceDeadline)
            assertEquals(UnitState.VERIFYING, rebuilt.units.single().state, "대조: 기록 없이는 기한이 재기동 뒤의 관측에서 선다")

            // 기한은 그때의 뒤 폭으로 정한 값이다 — 다시 계산하지 않는다. 그때 뒤 폭이 35초였던 기록이면 기한은 그대로 T0+40 이다.
            val wider = record.copy(units = listOf(record.units.single().copy(verifyWindow = VerifyWindow(T0.plusSeconds(5), T0.plusSeconds(40)))))
            val third = w.restart()
            val kept = third.resumeWith(order(required = Evidence.E2), wider)
            repeat(2) { third.mw.pump() }
            assertEquals(T0.plusSeconds(40), kept.units.single().evidenceDeadline, "기록의 기한을 지금의 뒤 폭으로 다시 계산했다")
            assertEquals(UnitState.VERIFYING, kept.units.single().state)
        }
    }

    // ── 이전 근거 확인 결과

    @Test
    fun `이전 근거 확인 결과가 남아 다시 지은 뒤에도 요구 등급 E2 를 채운다`() {
        World().use { w ->
            val exec = w.submit(order(required = Evidence.E2, slots = 2))
            w.cell.program(slot(1), PART)
            w.old.pump()
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_SUCCEEDED)
            w.old.pump()
            assertEquals(Evidence.E2, exec.units.first().reached, "시험의 전제: 첫 슬롯은 설비로 확인됐다")
            assertEquals(UnitState.RUNNING, exec.units[1].state)
            val record = persisted(w.old.judgmentRecord(exec.executionId))

            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2, slots = 2), record)
            w.cell.program(slot(2), PART)
            again.mw.pump()
            w.robot.set("$ORDER#s2", TaskState.TASK_STATE_SUCCEEDED)
            again.mw.pump()

            val first = resumed.units.first()
            assertEquals(Evidence.E2 to Verification.MATCHED, first.reached to first.verification, "앞 인스턴스의 확인 결과를 잃었다")
            assertEquals(PhysicalState.PHYSICALLY_DONE, resumed.physicalState)
            assertEquals(Evidence.E2, again.mw.responses().last().reachedEvidence, "요구 등급을 채운 작업 응답이 E0 으로 나갔다")
            assertTrue(resumed.eventTrail.any { it.kind == Middleware.RESUME_RECORD && it.detail.startsWith("s1:") }, "옮긴 판정이 자취에 없다")
        }
    }

    @Test
    fun `미확인으로 정착한 단위는 다시 지은 뒤에도 미확인이고 다시 관측한 성공으로 닫지 않는다`() {
        World().use { w ->
            val exec = w.submit(order(required = Evidence.E2, slots = 2))
            w.old.pump()
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_SUCCEEDED)
            w.old.pump()
            w.t = T0.plusSeconds(16)
            w.old.pump()
            assertEquals(UnitState.UNVERIFIED, exec.units.first().state, "시험의 전제: 시간창 안에 신호가 없었다")
            assertEquals(UnitState.RUNNING, exec.units[1].state)
            val record = persisted(w.old.judgmentRecord(exec.executionId))

            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2, slots = 2), record)
            val bare = w.restart()
            val rebuilt = bare.resumeWith(order(required = Evidence.E2, slots = 2), null)
            repeat(3) {
                again.mw.pump()
                bare.mw.pump()
            }
            assertEquals(UnitState.UNVERIFIED, resumed.units.first().state, "미확인을 다시 판정했다")
            assertEquals(UnitState.DONE, rebuilt.units.first().state, "대조: 기록 없이는 앞선 단위를 다시 관측한 성공으로 닫는다")
        }
    }

    // ── 승인 소모

    @Test
    fun `승인 소모는 다시 지은 뒤에도 소모로 답하고 조치 열이 계획 앞에 다시 선다`() {
        Approvals().use { w ->
            val found = assertIs<Remedy.Found>(assertNotNull(w.rejectedWhileHolding().remedy))
            val place = mapOf(PrepareSequencedRack.P_OBJECT to "ENGINE-COVER-A", PrepareSequencedRack.P_DESTINATION to "RACK-204.S01")
            val approved = assertIs<Middleware.Submission.Accepted>(
                w.old.approveRemedy(ROBOT, PATROL, List(found.steps.size) { place }, OPERATOR),
            ).execution
            val record = persisted(w.old.judgmentRecord(approved.executionId))
            assertEquals(listOf("remedy-1-pick_place"), record.approval!!.steps.map { it.unitId })

            val again = w.restart()
            val resumed = assertIs<Middleware.Submission.Accepted>(
                again.resume(patrol(), ROBOT, ActiveMission(InspectAsset(), null), record),
            ).execution
            assertEquals("remedy-1-pick_place", resumed.units.first().unitId, "승인한 조치 열이 계획에서 사라졌다")
            assertEquals(place, resumed.units.first().parameters)
            assertEquals(OPERATOR, resumed.approvedBy)

            val refused = assertIs<ApprovalOutcome.Refused>(
                again.attemptApproval(ApprovalAttempt(OPERATOR, ROBOT, PATROL, listOf("pick_place"))),
            )
            assertEquals(ApprovalRefusal.CONSUMED, refused.refusal, refused.reason)
            assertEquals(OPERATOR, refused.consumed!!.approver)
            assertEquals(resumed.executionId, refused.consumed!!.executionId)
            assertIs<Middleware.Submission.Rejected>(again.approveRemedy(ROBOT, PATROL, listOf(place), OPERATOR), "소모된 승인이 다시 쓰였다")

            // 대조: 기록 없이 다시 지으면 조치 열이 없고 소모도 모른다.
            val bare = w.restart()
            val rebuilt = assertIs<Middleware.Submission.Accepted>(bare.resume(patrol(), ROBOT, ActiveMission(InspectAsset(), null))).execution
            assertTrue(rebuilt.units.none { it.unitId.startsWith("remedy-") })
            val none = assertIs<ApprovalOutcome.Refused>(bare.attemptApproval(ApprovalAttempt(OPERATOR, ROBOT, PATROL, listOf("pick_place"))))
            assertEquals(ApprovalRefusal.NO_PROPOSAL, none.refusal)
        }
    }

    // ── 기록과 스냅숏

    @Test
    fun `기록의 시도가 스냅숏보다 앞서면 그 단위의 판정을 옮기지 않는다`() {
        World().use { w ->
            val exec = w.submit(order(required = Evidence.E2))
            w.old.pump()
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_FAILED)
            w.cell.program(slot(1), PART)
            w.old.pump()
            // 보류일 때 받아 둔 기록. 그 뒤 재작업을 내 기체에 새 시도가 돈다.
            val stale = persisted(w.old.judgmentRecord(exec.executionId))
            w.old.resolve(exec.executionId, "s1", OperatorDecision.REWORK, OPERATOR)
            w.old.pump()
            assertEquals("$ORDER#s1@r1", exec.units.single().taskId)
            val created = w.robot.created

            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2), stale)
            again.mw.pump()
            val unit = resumed.units.single()
            assertEquals(UnitState.RUNNING, unit.state, "앞 시도의 보류를 도는 재작업에 붙였다")
            assertEquals("$ORDER#s1@r1", unit.taskId)
            assertEquals(created, w.robot.created)
            assertTrue(resumed.eventTrail.any { it.kind == Middleware.RESUME_RECORD_SUPERSEDED && it.detail.startsWith("s1:") })
            assertEquals(emptyList(), again.mw.incidents(), "앞 시도의 보류로 인시던트를 다시 열었다")
        }
    }

    @Test
    fun `다른 작업 지시나 버전의 기록이면 거부하고 실행을 만들지 않는다`() {
        World().use { w ->
            val exec = w.submit(order())
            val record = persisted(w.old.judgmentRecord(exec.executionId))

            val again = w.restart()
            listOf(
                record.copy(jobOrderId = "OTHER"),
                record.copy(orderVersion = 2),
                record.copy(units = record.units + record.units.single().copy(unitId = "ghost")),
            ).forEach { wrong ->
                val rejected = assertIs<Middleware.Submission.Rejected>(again.mw.resume(order(), ROBOT, ActiveMission(Slots(), null), wrong))
                assertTrue(rejected.reason.startsWith(Middleware.RESUME_RECORD_MISMATCH), rejected.reason)
            }
            assertEquals(emptyList(), again.mw.executions())
            assertEquals(0, w.robot.snapshots, "기록을 대조하기 전에 스냅숏을 읽었다")
        }
    }

    @Test
    fun `다시 지은 뒤 내기 전에 받은 기록도 넘겨받은 요청 시각을 싣는다`() {
        World().use { w ->
            val exec = w.submit(order(workMaster = WaitThenSlot.WORK_MASTER))
            w.old.pump()
            val record = persisted(w.old.judgmentRecord(exec.executionId))

            val again = w.restart()
            val resumed = again.resumeWith(order(workMaster = WaitThenSlot.WORK_MASTER), record, WaitThenSlot())
            assertEquals(T0, again.mw.judgmentRecord(resumed.executionId)!!.units.first().requestedAt, "다시 지은 직후의 기록이 기한의 시작을 잃었다")
        }
    }

    @Test
    fun `재기동 뒤 첫 출발을 보내지 못해도 넘겨받은 요청 시각을 잃지 않는다`() {
        World().use { w ->
            w.robot.unreached += "$ORDER#s1"
            val exec = w.submit(order())
            w.old.pump()
            assertEquals(UnitState.IN_DOUBT, exec.units.single().state, "시험의 전제: 첫 요청이 닿지 않고 답도 잃었다")
            val record = persisted(w.old.judgmentRecord(exec.executionId))
            assertEquals(T0, record.units.single().requestedAt)

            w.t = T0.plusSeconds(50)
            w.robot.unreached.clear()
            w.robot.unsent += "$ORDER#s1"
            val again = w.restart()
            val resumed = again.resumeWith(order(), record)
            again.mw.pump()
            assertEquals(UnitState.PENDING, resumed.units.single().state, "시험의 전제: 재기동 뒤의 출발을 보내지 못했다")
            assertEquals(T0, again.mw.judgmentRecord(resumed.executionId)!!.units.single().requestedAt, "보내지 못한 출발이 넘겨받은 요청 시각을 지웠다")

            w.robot.unsent.clear()
            again.mw.pump()
            assertEquals(T0, resumed.units.single().requestedAt, "다시 낸 출발이 처음 요청 시각을 잃었다 — 앞 인스턴스의 요청이 닿았을 수 있다")
        }
    }

    // ── 운영자 보류의 인시던트

    @Test
    fun `기록의 운영자 보류로 다시 지은 단위는 다시 열었다고 적은 인시던트 하나를 이 인스턴스에 연다`() {
        World().use { w ->
            val record = held(w)
            val unit = record.units.single()
            val verdict = assertNotNull(unit.verdict)

            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2), record)
            // 첫 pump 전이다 — 담는 쪽의 판단 화면은 기동 직후에도 이 인시던트를 읽는다.
            val incident = again.mw.incidents().single()
            assertEquals(Triple(resumed.executionId, "s1", ROBOT), Triple(incident.executionId, incident.unitId, incident.robotId))
            assertTrue(incident.unresolved)
            assertEquals(null, incident.resolution)
            assertEquals(verdict.failureClass, incident.failureClass)
            assertEquals(Verification.MATCHED, incident.verification)
            assertEquals(null, incident.fault, "기록에 없는 결함 원문을 지어냈다")
            val marker = incident.evidenceWindow.single { it.kind == Middleware.RESUME_HOLD_REOPENED }
            assertTrue(marker.local)
            listOf("s1:", "robot=$ROBOT", "attempt=0", "failureClass=${verdict.failureClass}", "requestedAt=${unit.requestedAt}", "note=${verdict.note}")
                .forEach { assertTrue(it in marker.detail, "다시 연 표시에 $it 가 없다: ${marker.detail}") }

            repeat(2) { again.mw.pump() }
            assertEquals(1, again.mw.incidents().size, "pump 가 같은 보류의 인시던트를 또 열었다")
            assertEquals(PhysicalState.OPERATOR_HOLD, resumed.physicalState)

            // 대조: 기록 없이 다시 지으면 다시 관측한 실패로 보통의 인시던트를 연다 — 다시 연 표시가 없다.
            val bare = w.restart()
            bare.resumeWith(order(required = Evidence.E2), null)
            assertEquals(emptyList(), bare.mw.incidents())
            repeat(2) { bare.mw.pump() }
            assertTrue(bare.mw.incidents().single().evidenceWindow.none { it.kind == Middleware.RESUME_HOLD_REOPENED })
        }
    }

    @Test
    fun `다시 연 인시던트에 낸 판단이 보류를 풀고 그 인시던트와 판단 기록에 실린다`() {
        World().use { w ->
            val record = held(w)

            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2), record)
            val incident = again.mw.incidents().single()
            assertEquals(
                ResolveOutcome.Resolved,
                again.mw.resolve(incident.executionId, incident.unitId, OperatorDecision.CONFIRM_DONE, OPERATOR),
            )
            val unit = resumed.units.single()
            assertEquals(UnitState.DONE to Evidence.E2, unit.state to unit.reached)
            val resolution = assertNotNull(again.mw.incident(incident.incidentId)!!.resolution, "판단이 다시 연 인시던트에 안 붙었다")
            assertEquals(IncidentDecision.CONFIRM_DONE to OPERATOR, resolution.decision to resolution.decidedBy)
            val recorded = again.mw.judgmentRecord(resumed.executionId)!!.units.single()
            assertEquals(OperatorDecision.CONFIRM_DONE to 0, recorded.decision?.let { it.decision to it.attempt })
            assertEquals(UnitState.DONE, recorded.verdict?.state)
            repeat(2) { again.mw.pump() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, resumed.physicalState)

            // 재작업도 보통의 보류와 같다 — 새 시도로 나간다.
            val created = w.robot.created
            val third = w.restart()
            val reworked = third.resumeWith(order(required = Evidence.E2), record)
            val reopened = third.mw.incidents().single()
            assertEquals(
                ResolveOutcome.Resolved,
                third.mw.resolve(reopened.executionId, reopened.unitId, OperatorDecision.REWORK, OPERATOR),
            )
            third.mw.pump()
            assertEquals("$ORDER#s1@r1", w.robot.starts.last())
            assertEquals(created + 1, w.robot.created)
            assertEquals(IncidentDecision.REWORK, third.mw.incident(reopened.incidentId)!!.resolution?.decision)
            val rework = third.mw.judgmentRecord(reworked.executionId)!!.units.single()
            assertEquals(OperatorDecision.REWORK to 0, rework.decision?.let { it.decision to it.attempt })
            assertEquals(1, rework.attempt)
        }
    }

    // ── 인시던트 정체성(§15.322)

    @Test
    fun `보류가 선 라운드 직후 받은 판단 기록에 그 인시던트의 정체성과 처음 선 시각이 실린다`() {
        // ★키는 봉인과 같은 라운드에 기록에 실려야 한다. 다음 라운드로 미루면 그 사이 죽은 인스턴스의 기록으로 다시 연 인시던트가 원래 보류와
        //   이어지지 않는다.
        World().use { w ->
            val exec = w.submit(order(required = Evidence.E2))
            w.old.pump()
            assertEquals(null, w.old.judgmentRecord(exec.executionId)!!.units.single().verdict, "시험의 전제: 아직 판정이 없다")
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_FAILED)
            w.cell.program(slot(1), PART)
            w.old.pump()
            val incident = w.old.incidents().single()
            assertEquals(OpeningKind.NEW to 0, incident.openingKind to incident.attempt)
            val origin = assertNotNull(incident.origin, "새로 선 인시던트에 정체성이 없다")
            assertEquals(incident.at, origin.firstOpenedAt)
            assertTrue(w.old.instanceId in origin.key, "열쇠가 최초 봉인의 인스턴스를 안 든다: ${origin.key}")

            val record = persisted(w.old.judgmentRecord(exec.executionId))
            assertEquals(UnitState.OPERATOR_HOLD, record.units.single().verdict?.state)
            assertEquals(origin, record.units.single().verdict?.incident, "보류가 선 라운드 직후의 기록에 정체성이 없다")
        }
    }

    @Test
    fun `재기동이 다시 연 인시던트는 원래 보류의 정체성을 두 차례 재기동에도 잇고 요약은 다르다`() {
        World().use { w ->
            val record = held(w)
            val first = w.old.incidents().single()
            val origin = assertNotNull(first.origin)

            val second = w.restart()
            val resumed = second.resumeWith(order(required = Evidence.E2), record)
            val reopened = second.mw.incidents().single()
            assertEquals(OpeningKind.REOPENED, reopened.openingKind)
            assertEquals(origin, reopened.origin, "다시 연 인시던트가 원래 보류의 정체성을 안 이었다")
            assertTrue(first.digest() != reopened.digest(), "다시 연 인시던트의 요약이 원래 것과 같다 — 사본 둘이 한 줄로 접힌다")
            assertTrue(second.mw.instanceId != w.old.instanceId, "시험의 전제: 다른 인스턴스다")

            // 두 번째 재기동 — 다시 연 인스턴스의 기록이 같은 정체성을 다시 싣는다.
            val again = persisted(second.mw.judgmentRecord(resumed.executionId))
            assertEquals(origin, again.units.single().verdict?.incident)
            val third = w.restart()
            third.resumeWith(order(required = Evidence.E2), again)
            val twice = third.mw.incidents().single()
            assertEquals(origin to OpeningKind.REOPENED, twice.origin to twice.openingKind, "두 번째 재기동에 정체성이 끊겼다")

            // 대조: 기록 없이 다시 지으면 다시 관측한 실패로 새로 선다 — 정체성도 새 것이다.
            val bare = w.restart()
            bare.resumeWith(order(required = Evidence.E2), null)
            repeat(2) { bare.mw.pump() }
            val observed = bare.mw.incidents().single()
            assertEquals(OpeningKind.NEW, observed.openingKind)
            assertTrue(observed.origin != origin, "기록 없이 새로 선 인시던트가 원래 정체성을 지어냈다")
        }
    }

    @Test
    fun `다시 연 뒤 재작업해 또 깨진 보류는 시도가 오르고 새 정체성으로 새로 선다`() {
        World().use { w ->
            val record = held(w)
            val origin = assertNotNull(w.old.incidents().single().origin)
            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2), record)
            val reopened = again.mw.incidents().single()
            assertEquals(ResolveOutcome.Resolved, again.mw.resolve(reopened.executionId, reopened.unitId, OperatorDecision.REWORK, OPERATOR))
            again.mw.pump()
            w.robot.set("$ORDER#s1@r1", TaskState.TASK_STATE_FAILED)
            again.mw.pump()
            assertEquals(UnitState.OPERATOR_HOLD, resumed.units.single().state, "시험의 전제: 재작업도 보류에 선다")

            val fresh = again.mw.incidents().last()
            assertEquals(OpeningKind.NEW, fresh.openingKind, "같은 원인의 새 보류를 다시 연 것으로 적었다")
            assertEquals(0 to 1, reopened.attempt to fresh.attempt, "재발 판정의 재료인 시도가 안 올랐다")
            val renewed = assertNotNull(fresh.origin)
            assertTrue(renewed != origin && renewed != reopened.origin, "새 보류가 앞 보류의 정체성을 이었다")
            // 그 뒤의 판단 기록은 지금 선 보류의 정체성을 싣는다 — 다음 재기동은 이 보류를 잇는다.
            assertEquals(renewed, persisted(again.mw.judgmentRecord(resumed.executionId)).units.single().verdict?.incident)
        }
    }

    @Test
    fun `번들이 보존 상한으로 내려간 뒤에도 장부에 있는 보류 실행의 판단 기록은 정체성을 싣고 재기동하면 잇는다`() {
        // ★정체성은 무거운 번들과 따로 그 실행이 장부에 있는 동안 남는다. 번들과 함께 내리면 연결할 수 있었던 보류가 재기동 뒤 연결 불명이 된다.
        World().use { w ->
            val small = Middleware(w.robot, w.cell, capabilities = listOf(Slots()), now = { w.t }, retention = Retention(incidents = 1))
            val exec = assertIs<Middleware.Submission.Accepted>(small.submit(order(required = Evidence.E2), ROBOT)).execution
            small.pump()
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_FAILED)
            w.cell.program(slot(1), PART)
            small.pump()
            val origin = assertNotNull(small.incidents().single().origin)
            assertEquals(ResolveOutcome.Resolved, small.cancelByOperator(exec.executionId, OPERATOR))
            repeat(3) { small.pump() }
            assertEquals(PhysicalState.ABORTED to UnitState.OPERATOR_HOLD, exec.physicalState to exec.units.single().state, "시험의 전제: 구동이 놓았고 판정은 보류다")

            // 다른 실행의 인시던트가 상한(1)을 넘겨 구동이 놓은 앞 실행의 번들을 밀어낸다.
            val other = JobOrder("REC-2", Slots.WORK_MASTER, 1, Evidence.E2, parameters = mapOf("slots" to "1"))
            assertIs<Middleware.Submission.Accepted>(small.submit(other, OTHER))
            small.pump()
            w.robot.set("REC-2#s1", TaskState.TASK_STATE_FAILED)
            small.pump()
            assertTrue(small.incidents().none { it.executionId == exec.executionId }, "시험의 전제: 앞 실행의 번들이 내려갔다")

            val record = persisted(small.judgmentRecord(exec.executionId))
            assertEquals(origin, record.units.single().verdict?.incident, "번들과 함께 정체성을 잃었다")
            w.restart().let { again ->
                again.resumeWith(order(required = Evidence.E2), record)
                assertEquals(origin, again.mw.incidents().single().origin, "재기동 뒤 원래 보류와 안 이어졌다")
            }
        }
    }

    @Test
    fun `같은 시드로 두 번 지은 인스턴스의 첫 인시던트는 열쇠가 다르다`() {
        // ★열쇠의 유일성은 인스턴스 id 가 진다 — 인시던트 id 는 인스턴스마다 `incident-1` 부터 다시 센다. 인스턴스 id 를 고정하면 둘이 겹친다.
        val keys = (1..2).map {
            World().use { w ->
                held(w)
                val incident = w.old.incidents().single()
                assertEquals("incident-1", incident.incidentId)
                assertNotNull(incident.origin).key
            }
        }
        assertTrue(keys[0] != keys[1], "같은 시드의 두 인스턴스가 같은 열쇠를 냈다: $keys")
    }

    @Test
    fun `판 4 기록으로 다시 연 인시던트는 정체성이 널이고 그 뒤의 기록도 지어내지 않는다`() {
        World().use { w ->
            val record = held(w)
            val v4 = JudgmentRecordWire.encode(record)
                .replace("\"schemaVersion\":\"${JudgmentRecordWire.SCHEMA_VERSION}\"", "\"schemaVersion\":\"4\"")
                .replace(Regex(""","incidentKey":"[^"]*","firstOpenedAt":"[^"]*""""), "")
            assertTrue("incidentKey" !in v4, "시험의 전제: 판 4 모양을 못 지었다")

            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2), JudgmentRecordWire.decode(v4))
            val reopened = again.mw.incidents().single()
            assertEquals(OpeningKind.REOPENED, reopened.openingKind)
            assertEquals(null, reopened.origin, "옛 판 기록에 없는 정체성을 지어냈다 — 연결 불명이어야 한다")
            val line = LedgerExport.incidents(listOf(reopened))
            assertTrue("\"incidentKey\":null,\"firstOpenedAt\":null,\"openingKind\":\"REOPENED\"" in line, line)
            assertEquals(null, persisted(again.mw.judgmentRecord(resumed.executionId)).units.single().verdict?.incident)
        }
    }

    @Test
    fun `운영자 보류가 아닌 판정으로 다시 지은 단위는 인시던트를 열지 않는다`() {
        World().use { w ->
            val record = held(w)
            val unit = record.units.single()
            (UnitVerdict.STATES - UnitState.OPERATOR_HOLD).forEach { state ->
                val settled = record.copy(units = listOf(unit.copy(verdict = unit.verdict!!.copy(state = state))))
                val again = w.restart()
                val resumed = again.resumeWith(order(required = Evidence.E2), settled)
                assertEquals(state, resumed.units.single().state)
                assertEquals(emptyList(), again.mw.incidents(), "$state 로 옮긴 단위의 인시던트를 열었다")
                assertTrue(resumed.eventTrail.none { it.kind == Middleware.RESUME_HOLD_REOPENED }, "$state")
            }
        }
    }

    @Test
    fun `이미 다시 지은 실행에 resume 을 다시 불러도 인시던트를 겹쳐 열지 않는다`() {
        World().use { w ->
            val record = held(w)
            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2), record)
            val mission = ActiveMission(Slots(), null)
            assertIs<Middleware.Submission.Idempotent>(again.mw.resume(order(required = Evidence.E2), ROBOT, mission, record))
            again.mw.pump()
            assertIs<Middleware.Submission.Idempotent>(again.mw.resume(order(required = Evidence.E2), ROBOT, mission, record))
            assertEquals(listOf(resumed.executionId), again.mw.incidents().map { it.executionId })
            assertEquals(1, resumed.eventTrail.count { it.kind == Middleware.RESUME_HOLD_REOPENED })
        }
    }

    @Test
    fun `시계가 읽을 때마다 흘러도 다시 연 인시던트마다 at 이 그 단위의 다시 연 줄 시각과 같다`() {
        World().use { w ->
            val base = held(w)
            val unit = base.units.single()
            // 보류 둘을 든 기록. 한 번 읽은 시각을 다시 연 보류 모두에 쓰는지 본다 — 단위마다 읽으면 둘째가 어긋난다.
            val record = base.copy(units = listOf(unit, unit.copy(unitId = "s2")))
            w.step = Duration.ofMillis(1)

            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2, slots = 2), record)
            val trail = resumed.eventTrail
            val lines = trail.filter { it.kind == Middleware.RESUME_HOLD_REOPENED }
            val incidents = again.mw.incidents()
            assertEquals(listOf("s1", "s2"), incidents.map { it.unitId })
            assertEquals(2, lines.size)
            incidents.forEach { incident ->
                val line = lines.single { it.detail.startsWith("${incident.unitId}:") }
                assertEquals(Instant.parse(line.occurredAt), incident.at, "${incident.unitId}: 다시 연 줄의 시각이 인시던트 at 과 다르다")
                // 다시 연 것은 줄 시각으로 추정하지 않고 재개 경로가 직접 적는다(§15.322).
                assertEquals(OpeningKind.REOPENED, incident.openingKind, incident.unitId)
            }

            // 시험의 전제: 시계가 정말 흐른다 — 다시 연 줄 바로 앞의 옮김 줄은 그보다 이르고, 봉인 뒤에 읽은 시각은 그보다 늦다.
            val before = trail[trail.indexOf(lines.first()) - 1]
            assertEquals(Middleware.RESUME_RECORD, before.kind)
            assertTrue(Instant.parse(before.occurredAt).isBefore(Instant.parse(lines.first().occurredAt)), "시계가 흐르지 않았다")
            assertTrue(w.t.isAfter(incidents.first().at), "시계가 흐르지 않았다")
        }
    }

    @Test
    fun `다시 연 뒤 근거 윈도우 앞 폭 안에 새로 선 보류는 그 줄을 싣지만 새로 선 인시던트로 적히고 정체성이 다르다`() {
        World().use { w ->
            val record = held(w)
            w.step = Duration.ofMillis(1)

            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2), record)
            val reopened = again.mw.incidents().single()
            val line = resumed.eventTrail.single { it.kind == Middleware.RESUME_HOLD_REOPENED }
            val lineAt = Instant.parse(line.occurredAt)
            assertEquals(lineAt, reopened.at)

            // 재작업으로 새 시도를 내고, 그 시도도 하류 실패·설비 있음으로 새 보류에 선다.
            assertEquals(
                ResolveOutcome.Resolved,
                again.mw.resolve(reopened.executionId, reopened.unitId, OperatorDecision.REWORK, OPERATOR),
            )
            again.mw.pump()
            assertEquals("$ORDER#s1@r1", w.robot.starts.last())
            w.robot.set("$ORDER#s1@r1", TaskState.TASK_STATE_FAILED)
            again.mw.pump()
            assertEquals(UnitState.OPERATOR_HOLD, resumed.units.single().state, "시험의 전제: 재작업도 하류 실패·설비 있음으로 보류에 선다")
            val fresh = again.mw.incidents().last()
            assertTrue(fresh.incidentId != reopened.incidentId, "시험의 전제: 새 보류의 인시던트가 따로 열린다")
            assertTrue(Duration.between(lineAt, fresh.at) < Duration.ofSeconds(30), "시험의 전제: 근거 윈도우 앞 폭 안이다")

            assertTrue(fresh.evidenceWindow.any { it.kind == Middleware.RESUME_HOLD_REOPENED && it.occurredAt == line.occurredAt }, "새 보류의 윈도우에도 그 줄이 실린다")
            // ★줄이 있는 것만으로는 둘 다 다시 연 것처럼 보인다. 가르는 것은 재개 경로가 적은 열림의 종류와 정체성이다(§15.322) — 줄 시각을
            //   `at` 과 맞대 추정하지 않는다.
            assertEquals(listOf(OpeningKind.REOPENED, OpeningKind.NEW), again.mw.incidents().map { it.openingKind })
            assertTrue(fresh.origin != null && fresh.origin != reopened.origin, "새 보류가 다시 연 인시던트의 정체성을 이었다: ${fresh.origin}")
        }
    }

    @Test
    fun `보류 둘로 다시 지은 실행에서 하나를 판단하면 실행은 보류 그대로이고 작업 응답이 더 나가지 않는다`() {
        World().use { w ->
            val base = held(w)
            val unit = base.units.single()
            val record = base.copy(units = listOf(unit, unit.copy(unitId = "s2")))
            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2, slots = 2), record)
            again.mw.pump()
            assertEquals(PhysicalState.OPERATOR_HOLD, resumed.physicalState, "시험의 전제: 다시 지은 실행이 보류다")
            val before = again.mw.responses().size

            assertEquals(ResolveOutcome.Resolved, again.mw.resolve(resumed.executionId, "s1", OperatorDecision.CONFIRM_DONE, OPERATOR))
            assertEquals(PhysicalState.OPERATOR_HOLD, resumed.physicalState, "다른 단위가 보류인데 실행의 보류가 걷혔다")
            again.mw.pump()
            assertEquals(before, again.mw.responses().size, "보류가 그대로인데 작업 응답이 나갔다: ${again.mw.responses().drop(before)}")
        }
    }

    // ── 파지 이력(§15.257)

    @Test
    fun `쥐었다가 재기동 사이에 놓친 단위가 다시 지은 뒤 실패로 끝나면 빈손을 효과 어긋남으로 판정한다`() {
        World().use { w ->
            val exec = w.submit(order())
            w.old.pump()
            w.robot.holds["$ORDER#s1"] = HoldKind.HOLD_KIND_HOLDING
            w.old.pump()
            assertTrue(exec.units.single().everHeld, "시험의 전제: 앞 인스턴스가 쥔 것을 봤다")
            val record = persisted(w.old.judgmentRecord(exec.executionId))
            // 재기동 사이에 놓쳤고 그 태스크는 실패로 끝났다 — 다시 지은 인스턴스는 빈손만 본다.
            w.robot.holds["$ORDER#s1"] = HoldKind.HOLD_KIND_EMPTY
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_FAILED)

            val again = w.restart()
            val resumed = again.resumeWith(order(), record)
            again.mw.pump()
            again.mw.pump()
            val unit = resumed.units.single()
            assertEquals(HoldKind.HOLD_KIND_HOLDING, unit.holdExpected, "쥔 적 있는 중단의 기대를 잃었다")
            assertEquals(HoldMismatch.PAYLOAD_LOST, unit.holdMismatch, "놓친 자재를 판정하지 않았다: ${unit.note}")

            // 대조: 기록 없이 다시 지으면 쥔 적을 모르니 기대가 없고 판정도 없다.
            val bare = w.restart()
            val rebuilt = bare.resumeWith(order(), null)
            bare.mw.pump()
            bare.mw.pump()
            assertEquals(null, rebuilt.units.single().holdExpected)
            assertEquals(null, rebuilt.units.single().holdMismatch)
        }
    }

    @Test
    fun `쥔 것을 본 단위가 보류에 선 채 다시 지으면 재할당이 자재를 다룬 단위로 거른다`() {
        World().use { w ->
            val exec = w.submit(order(required = Evidence.E2))
            w.old.pump()
            w.robot.holds["$ORDER#s1"] = HoldKind.HOLD_KIND_HOLDING
            w.old.pump()
            w.robot.holds["$ORDER#s1"] = HoldKind.HOLD_KIND_EMPTY
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_FAILED)
            w.cell.program(slot(1), PART)
            w.old.pump()
            assertEquals(UnitState.OPERATOR_HOLD, exec.units.single().state, "시험의 전제: 하류는 실패, 설비에는 있다")
            val record = persisted(w.old.judgmentRecord(exec.executionId))

            val again = w.restart()
            val resumed = again.resumeWith(order(required = Evidence.E2), record)
            val kept = assertIs<Reassignment.Kept>(again.mw.reassign(resumed.executionId, listOf(OTHER)))
            assertTrue("자재를 다룬 단위가 있다" in kept.reason, kept.reason)

            // 대조: 기록 없이 다시 지은 실행은 쥔 것을 다시 보지 못해 이 사유로 거르지 않는다.
            val bare = w.restart()
            val rebuilt = bare.resumeWith(order(required = Evidence.E2), null)
            bare.mw.pump()
            bare.mw.pump()
            val other = bare.mw.reassign(rebuilt.executionId, listOf(OTHER))
            assertTrue(other !is Reassignment.Kept || "자재를 다룬 단위가 있다" !in other.reason, "$other")
        }
    }

    @Test
    fun `기록이 재작업보다 먼저 적혔으면 그 시도의 파지는 앞 시도의 파지로 접는다`() {
        World().use { w ->
            val exec = w.submit(order(required = Evidence.E2))
            w.old.pump()
            w.robot.holds["$ORDER#s1"] = HoldKind.HOLD_KIND_HOLDING
            w.old.pump()
            w.robot.holds["$ORDER#s1"] = HoldKind.HOLD_KIND_EMPTY
            w.robot.set("$ORDER#s1", TaskState.TASK_STATE_FAILED)
            w.cell.program(slot(1), PART)
            w.old.pump()
            // 보류일 때 받아 둔 기록(시도 0 에서 쥔 것을 적었다). 그 뒤 재작업을 내 기체에 새 시도가 돈다.
            val stale = persisted(w.old.judgmentRecord(exec.executionId))
            assertEquals(0 to true, stale.units.single().let { it.attempt to it.everHeld }, "시험의 전제: 기록은 시도 0 의 파지를 적었다")
            w.old.resolve(exec.executionId, "s1", OperatorDecision.REWORK, OPERATOR)
            w.old.pump()
            assertEquals("$ORDER#s1@r1", exec.units.single().taskId)

            val resumed = w.restart().resumeWith(order(required = Evidence.E2), stale)
            val unit = resumed.units.single()
            assertEquals(1, unit.attempt, "시험의 전제: 스냅숏의 시도가 크다")
            assertTrue(unit.heldInEarlierAttempt, "앞 시도의 파지를 버렸다 — 재할당이 자재를 다룬 단위를 거르지 못한다")
            assertFalse(unit.everHeld, "앞 시도의 파지를 새 시도의 것으로 옮겼다 — 쥔 적 없는 시도가 든 채를 기대한다")
        }
    }

    /** 앞 인스턴스가 하류 실패·설비 있음으로 단위를 운영자 보류에 세운 뒤 받아 둔 기록. */
    private fun held(w: World): JudgmentRecord {
        val exec = w.submit(order(required = Evidence.E2))
        w.old.pump()
        w.robot.set("$ORDER#s1", TaskState.TASK_STATE_FAILED)
        w.cell.program(slot(1), PART)
        w.old.pump()
        assertEquals(UnitState.OPERATOR_HOLD, exec.units.single().state, "시험의 전제: 하류는 실패, 설비에는 있다")
        assertEquals(1, w.old.incidents().size, "시험의 전제: 앞 인스턴스에 보류의 인시던트가 있다")
        return persisted(w.old.judgmentRecord(exec.executionId))
    }

    // ── 더블

    /** 앞 인스턴스와, 같은 기체·셀 위에 다시 세우는 인스턴스. */
    private class World(lookup: ExecutionLookup = ExecutionLookup.CLIENT_REFERENCE) : AutoCloseable {
        var t: Instant = T0

        /** 미들웨어가 시계를 읽을 때마다 [t] 를 이만큼 민다. 기본은 0 이라 고정 시계다 — 실제 시계처럼 흐르게 할 때만 준다. */
        var step: Duration = Duration.ZERO
        val robot = Robot({ t }, lookup)
        val cell = CellMimic(now = { t })
        val old = middleware()

        fun middleware() = Middleware(robot, cell, capabilities = listOf(Slots(), WaitThenSlot()), now = { t.also { t = t.plus(step) } })

        fun submit(order: JobOrder): Middleware.Execution = assertIs<Middleware.Submission.Accepted>(old.submit(order, ROBOT)).execution

        fun restart() = Restarted(middleware())

        override fun close() = Unit
    }

    private class Restarted(val mw: Middleware) {
        fun resumeWith(order: JobOrder, record: JudgmentRecord?, capability: LogicalCapability = Slots()): Middleware.Execution =
            assertIs<Middleware.Submission.Accepted>(mw.resume(order, ROBOT, ActiveMission(capability, null), record)).execution
    }

    /**
     * 승인 경로는 에뮬레이터 위에서 세운다 — 든 채인 기체와 그 거절이 있어야 제안이 선다(`RemedyApprovalTest` 와 같은 모양). 랙은 든 채
     * 실패해 사람이 취소로 닫는다([leaveHolding]) — 든 채 **도는** 랙 뒤에 내면 그 손에 든 것이 랙의 일이라 제안이 서지 않는다(§15.319).
     */
    private class Approvals : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to PRECOND))
        val cell = CellMimic(now = { harness.clock.now() })
        val old = restart()

        fun restart() = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() })

        fun rejectedWhileHolding(): Middleware.Submission.Rejected {
            val rack = JobOrder(
                jobOrderId = "SEQ-204",
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
            val exec = assertIs<Middleware.Submission.Accepted>(old.submit(rack, ROBOT)).execution
            old.leaveHolding(exec, harness) { drive(it) }
            return assertIs(old.submit(patrol(), ROBOT))
        }

        private fun drive(until: () -> Boolean) {
            repeat(120) {
                old.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(1))
                Thread.sleep(40)
                old.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다")
        }

        override fun close() = harness.close()
    }

    /**
     * 재기동 전 인스턴스가 낸 태스크를 든 기체. 같은 태스크 id 가 다시 오면 기존 핸들을 돌려주고 [created] 를 올리지 않는다.
     * [lost] 의 태스크는 받되 답을 잃는다(던진다). 갱신은 지금 상태 하나를 돌려주고 그 시각을 지금으로 찍는다. [holds] 에 든
     * 태스크는 그 파지를 갱신에 싣고, 없으면 싣지 않는다.
     */
    private class Robot(private val now: () -> Instant, override val executionLookup: ExecutionLookup) : RobotPort {
        private val tasks = linkedMapOf<String, TaskState>()
        val starts = mutableListOf<String>()
        val lost = mutableSetOf<String>()

        /** 받지 못하고 답도 잃는 태스크 — 기체는 그 태스크를 모른다. */
        val unreached = mutableSetOf<String>()

        /** 포트가 하위로 보내지 않았다고 답하는 태스크([CommandNotSent]). */
        val unsent = mutableSetOf<String>()
        val holds = mutableMapOf<String, HoldKind>()
        var faults: List<Fault> = emptyList()
        var created = 0
        var snapshots = 0

        fun set(taskId: String, state: TaskState) {
            tasks[taskId] = state
        }

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            if (taskId in unsent) throw CommandNotSent("시험: 하위로 보내지 않았다")
            starts += taskId
            if (taskId in unreached) throw IllegalStateException("닿지 않았다")
            if (taskId !in tasks) {
                tasks[taskId] = TaskState.TASK_STATE_RUNNING
                created += 1
            }
            if (taskId in lost) throw IllegalStateException("답을 잃었다")
            return StartTaskResponse.newBuilder()
                .setHandle(TaskHandle.newBuilder().setTaskId(taskId).setRevision(revision).setRobotId(robotId))
                .build()
        }

        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> {
            val state = tasks[handle.taskId] ?: return emptyList()
            return listOf(
                WatchTaskResponse.newBuilder()
                    .setHeader(MessageHeader.newBuilder().setStateAsOf(now().toString()))
                    .setState(state)
                    .setRevision(handle.revision)
                    .apply { holds[handle.taskId]?.let { setHold(HoldState.newBuilder().setKind(it)) } }
                    .build(),
            )
        }

        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = CancelTaskResponse.getDefaultInstance()

        override fun snapshot(robotId: String): RobotSnapshot {
            snapshots += 1
            return RobotSnapshot(0, faults, ConnectionState.CONNECTION_STATE_ONLINE, tasks.toMap())
        }

        override fun replay(robotId: String, from: Long): Replay = Replay.Events(emptyList())
    }

    /** 슬롯마다 로봇 단위 하나. 목적지에 부품을 놓으므로 요구가 E2 면 설비로 확인한다. */
    private class Slots : LogicalCapability {
        override val workMasterId: String = WORK_MASTER
        override val maxEvidence: Evidence = Evidence.E2
        override fun plan(order: JobOrder): List<ExecutionUnit> =
            (1..order.parameters.getValue("slots").toInt()).map { slotUnit(it) }

        companion object {
            const val WORK_MASTER = "Slots"
        }
    }

    /** 랙 도착 대기(기한 60초, 지나면 운영자) 뒤 슬롯 하나. */
    private class WaitThenSlot : LogicalCapability {
        override val workMasterId: String = WORK_MASTER
        override val maxEvidence: Evidence = Evidence.E2
        override fun plan(order: JobOrder): List<ExecutionUnit> = listOf(
            ExecutionUnit(
                unitId = "w",
                route = Route.SIGNAL,
                skillType = WaitSpec.SKILL_TYPE,
                parameters = emptyMap(),
                expectedIdentity = null,
                source = null,
                destination = null,
                wait = WaitSpec("rack_arrived", "true", Duration.ofSeconds(60), DeadlineOutcome.OPERATOR_HOLD),
            ),
            slotUnit(1),
        )

        companion object {
            const val WORK_MASTER = "WaitThenSlot"
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val OTHER = "hum-03"
        const val ORDER = "REC-1"
        const val PATROL = "PATROL-1"
        const val PART = "ENGINE-COVER-A"
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)
        val T0: Instant = Instant.parse("2026-10-14T00:00:00Z")
        val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()

        fun slot(n: Int) = "RACK-1.S0$n"

        fun slotUnit(n: Int) = ExecutionUnit(
            unitId = "s$n",
            route = Route.ROBOT,
            skillType = "pick_place",
            parameters = emptyMap(),
            expectedIdentity = PART,
            source = null,
            destination = slot(n),
        )

        fun order(required: Evidence = Evidence.E0, slots: Int = 1, workMaster: String = Slots.WORK_MASTER) =
            JobOrder(ORDER, workMaster, 1, required, parameters = mapOf("slots" to "$slots"))

        fun patrol() = JobOrder(
            jobOrderId = PATROL,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            equipmentRequirements = listOf(
                EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1")),
            ),
        )

        /** 담는 쪽이 저장소에 적었다가 읽어 오는 모양 — 인코딩을 한 번 지난다. */
        fun persisted(record: JudgmentRecord?): JudgmentRecord =
            JudgmentRecordWire.decode(JudgmentRecordWire.encode(assertNotNull(record, "판단 기록이 없다")))
    }
}

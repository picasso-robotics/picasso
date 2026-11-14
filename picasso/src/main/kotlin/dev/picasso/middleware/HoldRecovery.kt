package dev.picasso.middleware

import dev.picasso.capability.ObjectReferences
import dev.picasso.capability.Remedy
import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.middleware.Middleware.Submission
import dev.picasso.middleware.MiddlewareCodes.HOLD_RECHECK
import dev.picasso.middleware.MiddlewareCodes.REMEDY_APPLIED
import dev.picasso.middleware.MiddlewareCodes.REMEDY_NOT_DONE
import dev.picasso.middleware.RemedyDesk.Judgment

/**
 * **발신자 거부 뒤 제자리 회복**(§15.320, §15.150 의 남은 몫) — 접수된 실행의 단위가 기체에서 `PRECONDITION_UNMET` 으로 거부되어 운영자 보류에
 * 섰고 거부의 구조화된 주어에 파지(`HOLD`)가 있을 때, 그 보류를 신선한 손으로 다시 보고 사람이 승인할 회복 제안을 세우고, 승인되면 조치 열을
 * 그 단위 바로 앞에 끼운다. 제 상태가 없는 규약이라 [RemedyDesk] 처럼 제 클래스로 두되 장부는 [RemedyDesk] 와 [IncidentLog] 의 것을 쓴다.
 *
 * ## 차례
 *
 * 1. **재확인**([review]) — 라운드 끝에 (단위, 시도)마다 한 번. 손은 신선한 기체 스냅숏의 것이고 든 채·빈손만 근거다. 모르면 판정하지 않고 보류인
 *    동안 다음 라운드에 다시 본다. 그 손에서 [보류 단위 + 남은 대기 단위]를 굴려 어긋나면 [RemedyDesk.record] 에 보류 대상으로 넘긴다 — 찾았으면 회복
 *    제안이 서고 못 찾았으면 그 답이 적힌다. 어긋나지 않으면 자취만 남기고 **자동으로 다시 내지 않는다.** 둘째 인시던트를 열지 않는다.
 * 2. **승인**([approve]) — 사람만. 대상(판·단위·시도)과 본 조치 열(유형·값·순서)을 들고 와 서 있는 제안과 정확히 맞아야 한다.
 * 3. **적용** — 재검증, 적용 직전 재검사, 삽입, 새 시도의 대기, 보류 해제 알림, 소모 기록. 새 단위 상태가 없다.
 * 4. **조치 실패 시 미발행**([halts]) — 구동이 원래 단위를 고를 때 조치가 `DONE` 아닌 채 끝났으면 내지 않고 운영자 보류로 세운다.
 *
 * ## 실행당 승인 하나
 *
 * 판단 기록이 승인 하나와 위치 칸 하나를 싣는다([RecordedApproval.position]). 그래서 이미 승인을 든 실행(접수 승인 포함)에는 회복 제안을 세우지 않고
 * 탐색만 적는다. 실행당 여러 번의 회복은 한계 대장이 연다.
 *
 * ## 빼는 것
 *
 * 회복 자동 승인(에이전트·값 없는 문), `HOLD` 밖의 주어, 다른 기체에 묶인 단위, 실행 중 실패의 잔여 파지(`SKILL_EXECUTION_FAILED`, ADR 47 사람 몫),
 * 손을 못 읽는 발신자(지금처럼 운영자만), 위반 없음의 자동 재발행, 승인 적용과 판단 기록 저장 사이의 틈을 막는 장치.
 */
internal class HoldRecovery(
    private val env: CommandEnv,
    private val gate: AdmissionGate,
    private val desk: RemedyDesk,
    private val incidentLog: IncidentLog,
    /** [Middleware] 가 든 실행 표 — 같은 인스턴스로 받아 읽기만 한다. */
    private val executions: Map<String, Execution>,
    /** 보류가 걷힌 것을 마지막 작업 응답과 대 보고 알린다(`Middleware.noteAttention`). */
    private val attention: (Execution) -> Unit,
) {

    /**
     * 라운드 끝의 재확인. 보류가 선 것을 아는 자리가 여기다 — 생애 안이 아니다. 판정하면 그 (단위, 시도)를 [Notice.Recheck] 로 적어 다시 보지 않는다.
     */
    fun review(execution: Execution) {
        if (!execution.physicalState.pumps || execution.cancelRequested) return
        val held = execution.units.firstOrNull { it.state == UnitState.OPERATOR_HOLD } ?: return
        if (!refusedForHold(held) || execution.robotOf(held) != execution.robotId) return
        val mark = Notice.Recheck(held.unitId, held.attempt)
        if (execution.noticed[mark] != null) return
        val robot = execution.robotId
        val outcome = gate.recheck(robot, remainingFrom(execution, held), gate.snapshotHold(robot), SNAPSHOT_BASIS)
        if (outcome == AdmissionGate.Recheck.Unknown) return
        execution.noticed.set(mark, true)
        val detail = when (outcome) {
            AdmissionGate.Recheck.Unknown, AdmissionGate.Recheck.Clear -> "신선한 스냅숏의 손으로 굴려도 어긋나지 않는다 — 자동으로 다시 내지 않고 운영자 판단을 기다린다"
            is AdmissionGate.Recheck.Violated -> {
                val target = ProposalTarget.Hold(execution.order.version, held.unitId, held.attempt)
                val answered = desk.record(
                    robot, execution.order, outcome.rejection,
                    carriesApproval = execution.approval != null, revising = execution.executionId, target = target,
                )
                val answer = when {
                    answered.remedyWithheld -> "회복 제안을 가렸다"
                    answered.remedy is Remedy.Found -> "회복 제안이 섰다(사람만 승인한다)"
                    answered.remedy is Remedy.None -> "조치 열을 못 찾았다"
                    else -> "제안을 세우지 않았다"
                }
                "$answer: ${answered.reason}"
            }
        }
        with(env) { execution.trail(HOLD_RECHECK, "${held.unitId}: attempt ${held.attempt} — $detail") }
    }

    /**
     * **사람의 회복 입구**([Middleware.approveRecovery]). 에이전트는 [ApprovalRefusal.HUMAN_APPROVAL_REQUIRED] 다. 판정은 [RemedyDesk.judge] 가 하고
     * (대상·본 조치 열의 대조, 값의 선언 대조, 낡은 제안 내리기), 서면 [apply] 앞에서 다시 본다.
     *
     * 다시 보는 것은 둘이다. **재검증** — 실행이 제안의 기체에 있고 아직 승인을 들지 않았으며(실행당 하나), 보내는 중인 명령이 없고, 자기 말고
     * 그 기체를 쥔 실행이 없다. **적용 직전 재검사** — [AdmissionGate.fillHold] 의 손(자기 제외)에서 [조치 열 + 보류 단위 + 남은 단위]를 굴린다. 굴림은 파지의
     * **종류**만 본다 — 그래서 손에 든 물건만 바뀐 경우는 따로 댄다. 첫 걸음의 대상 이름 칸(계약 `is_object_reference`)에 사람이 준 값이 지금 손이 말한
     * 물건 이름과 다르면 받지 않는다(손이 이름을 말하지 않으면 대지 못한다). 걸리면 [ApprovalRefusal.REFUSED_BY_GATE] 이고 제안은 그대로다 — 승인 중의
     * 거절은 제안을 바꾸지 않는다. 사람 식별자가 비었으면 에이전트와 같이 [ApprovalRefusal.HUMAN_APPROVAL_REQUIRED] 다.
     */
    fun approve(
        robotId: String,
        jobOrderId: String,
        target: ProposalTarget.Hold,
        saw: List<RemedyStep>,
        parameters: List<Map<String, String>>,
        approver: Approver,
    ): Submission {
        if (approver.kind != ApproverKind.PERSON || approver.id.isBlank()) {
            return refused("에이전트는 회복을 승인하지 못한다 — 발신자 거부 뒤 회복은 사람의 몫이다: ${approver.id}", ApprovalRefusal.HUMAN_APPROVAL_REQUIRED)
        }
        val go = when (val judgment = desk.judge(robotId, jobOrderId, approver, given = parameters, saw = null, target = target, sawSteps = saw)) {
            is Judgment.No -> return Submission.Rejected(judgment.reason, approvalRefusal = judgment.refusal, consumedApproval = judgment.consumed)
            is Judgment.Go -> judgment
        }
        // 판정이 대상을 확인했다([holdStands]) — 그 작업 지시의 실행이 있고 그 단위가 같은 판·같은 시도의 보류다.
        val execution = executions.values.first { it.order.jobOrderId == jobOrderId }
        val unit = execution.units.first { it.unitId == target.unitId }
        if (execution.robotId != robotId || execution.approval != null) {
            return refused("그 실행이 이 기체에 없거나 이미 승인을 들었다 — 조치 열을 끼우지 않았다: ${execution.executionId}", ApprovalRefusal.REMEDY_NOT_APPLIED)
        }
        // 조치 단위의 이름은 판정이 계획의 단위 id 를 피해 지었다. 그 사이 단위 열이 바뀌었으면 여기서 막는다 — 겹친 id 는 한쪽을 가린다.
        duplicateUnitIdRefusal(go.prefix + execution.units)?.let { return refused(it.reason, ApprovalRefusal.REMEDY_NOT_APPLIED) }
        if (execution.active != null || execution.inFlight != null || execution.fleetInFlight != null || execution.cancelInFlight != null) {
            return refused("그 실행에 보내는 중인 명령이 있다 — 그 결과를 받은 뒤 다시 승인한다: ${execution.executionId}", ApprovalRefusal.REFUSED_BY_GATE)
        }
        gate.holder(robotId, execution.executionId)?.let {
            return refused("그 기체를 다른 실행 $it 가 쥐고 있어 조치 열을 끼우지 않는다 (보내지 않았다)", ApprovalRefusal.REFUSED_BY_GATE)
        }
        val hand = gate.fillHold(robotId, jobOrderId)
        val held = hand?.objectRef.orEmpty()
        go.prefix.firstOrNull()?.let { first ->
            val named = ObjectReferences.keysOf(first.skillType).mapNotNull { first.parameters[it] }.filter { it != held }
            if (held.isNotEmpty() && named.isNotEmpty()) {
                return refused("사람이 준 대상 $named 와 지금 손에 든 «$held» 가 다르다 — 조치 열을 끼우지 않았다", ApprovalRefusal.REFUSED_BY_GATE)
            }
        }
        when (val check = gate.recheck(robotId, go.prefix + remainingFrom(execution, unit), hand, APPLY_BASIS)) {
            AdmissionGate.Recheck.Unknown -> return refused("승인 직전에 기체의 손을 모른다 — 끼울 조치 열을 굴려 볼 수 없어 끼우지 않았다", ApprovalRefusal.REFUSED_BY_GATE)
            is AdmissionGate.Recheck.Violated -> return refused(check.rejection.reason, ApprovalRefusal.REFUSED_BY_GATE)
            AdmissionGate.Recheck.Clear -> Unit
        }
        apply(execution, unit, go, target)
        return Submission.Accepted(execution)
    }

    /**
     * 끼운다. 조치 단위를 보류 단위의 **그 자리 바로 앞**에 넣고, 보류 단위를 새 시도의 대기로 되돌린다(같은 태스크 id 의 재전송을 피한다). 보류 해제를
     * 먼저 알리고([Middleware.resolve] 와 같은 순서) 그 다음 소모를 기록한다. 인시던트에는 «조치 승인 적용됨» 걸음만 싣는다 — 사람의 재작업이 아니고
     * 회복 완료도 아니다. 결과는 조치 단위와 원래 단위 새 시도의 상태로 읽는다.
     */
    private fun apply(execution: Execution, unit: ExecutionUnit, go: Judgment.Go, target: ProposalTarget.Hold) {
        go.prefix.forEach { it.revision = execution.order.version }
        execution.mutableUnits.addAll(execution.mutableUnits.indexOf(unit), go.prefix)
        env.reworkAsNewAttempt(execution, unit)
        unit.note = "approved remedy ${go.prefix.joinToString { it.unitId }} inserted before attempt ${unit.attempt} — reissued after it"
        val stillHeld = execution.units.firstOrNull { it.state == UnitState.OPERATOR_HOLD }
        execution.noticed.set(Notice.Hold, stillHeld?.unitId)
        execution.physicalState = if (stillHeld != null) PhysicalState.OPERATOR_HOLD else PhysicalState.RUNNING
        attention(execution)
        incidentLog.noteResolution(execution.executionId, unit.unitId, IncidentDecision.REMEDY_APPLIED, go.approver)
        val consumed = desk.settle(go, execution.executionId)
        execution.approval = RecordedApproval(go.robotId, consumed.approver, consumed.at, consumed.wallClockAt, consumed.steps, position = target)
        execution.approvedBy = go.approver
        with(env) {
            execution.trail(
                REMEDY_APPLIED,
                "${unit.unitId}: ${go.prefix.joinToString { it.unitId }} before attempt ${unit.attempt}, approved by ${go.approver.id}(${go.approver.kind})",
            )
        }
    }

    /**
     * **조치 실패 시 미발행**(§15.320). [next] 가 회복 대상(위치 칸의 단위, 거부된 시도의 다음 시도)이고 조치 걸음 가운데 `DONE` 아닌 채 끝난 것이 있으면
     * [next] 를 내지 않고 운영자 보류로 세워 참을 돌려준다 — 부르는 쪽은 곧바로 라운드를 끝낸다. 첫 보류에서 구동이 멈추므로 뒤의 독립 단위도 안 나간다.
     * 선행 관계(`after`)만으로는 안 된다 — 원래 단위만 `FAILED` 로 접히고 독립 후속이 나간다. 둘째 인시던트를 열지 않는다(`markIncident` 를 안 부른다).
     * 거부 때 선 인시던트가 그 사건이고 그 걸음은 이 실행의 자취([REMEDY_NOT_DONE])에 남는다. `UNVERIFIED` 도 `DONE` 이 아니다.
     */
    fun halts(execution: Execution, next: ExecutionUnit): Boolean {
        val approval = execution.approval ?: return false
        val position = approval.position ?: return false
        if (next.unitId != position.unitId || next.attempt != position.attempt + 1) return false
        val steps = approval.steps.map { it.unitId }.toSet()
        val failed = execution.units.firstOrNull { it.unitId in steps && it.state != UnitState.DONE && it.state !in MiddlewareCodes.OPEN_UNIT_STATES }
            ?: return false
        // ★**사람의 걸음이 실릴 자리를 둔다.** 원래 단위의 인시던트는 판단 칸에 «조치 승인 적용됨» 이 실려 있어 그 뒤의 사람 걸음(재작업·확인·운영자
        //   취소)이 붙을 빈칸이 없다. 그 걸음은 실패한 조치 단위의 인시던트에 붙인다([stepSlots]). 조치의 실패가 인시던트를 열지 않는 경로(미확인·중단)면
        //   여기서 조치 단위의 인시던트를 연다 — 원래 단위의 둘째 인시던트가 아니라 실패한 조치 자신의 것이다.
        val open = failed.unitId in execution.pendingIncidents ||
            incidentLog.incidents().any { it.executionId == execution.executionId && it.unitId == failed.unitId && it.resolution == null }
        if (!open) execution.markIncident(failed)
        next.state = UnitState.OPERATOR_HOLD
        next.failureClass = REMEDY_NOT_DONE
        next.note = "회복 조치 ${failed.unitId} 가 ${failed.state.name} 로 끝나 내지 않았다 — 운영자 판단을 기다린다"
        execution.physicalState = PhysicalState.OPERATOR_HOLD
        // 알리는 것은 라운드 끝의 주의 대조다. 보류 자리를 알린 것으로 둬야 다음 라운드의 보류 분기가 같은 통보를 또 내지 않는다.
        execution.noticed.set(Notice.Hold, next.unitId)
        with(env) { execution.trail(REMEDY_NOT_DONE, "${next.unitId}: ${next.note}") }
        return true
    }

    /** 발신자가 파지 주어로 사전 조건 거부를 했고 그 시도가 보류에 섰는가. 다른 보류(실행 중 실패의 잔여 파지 등)는 이 경로가 아니다. */
    private fun refusedForHold(unit: ExecutionUnit): Boolean =
        unit.route == Route.ROBOT && unit.failureClass == RejectionCode.REJECTION_CODE_PRECONDITION_UNMET.name && HOLD_SUBJECT in unit.preconditionSubjects

    /** [unit] 과 그 뒤의 대기 단위 — 굴릴 사슬이다. 끝난 단위는 사실이고 다시 재단하지 않는다. */
    private fun remainingFrom(execution: Execution, unit: ExecutionUnit): List<ExecutionUnit> =
        listOf(unit) + execution.units.drop(execution.units.indexOf(unit) + 1).filter { it.state == UnitState.PENDING }

    private fun refused(reason: String, refusal: ApprovalRefusal) = Submission.Rejected(reason, approvalRefusal = refusal)

    private companion object {
        /** 거부의 구조화된 주어 가운데 파지(계약 `PreconditionSubject.HOLD` 의 이름). */
        const val HOLD_SUBJECT = "HOLD"
        const val SNAPSHOT_BASIS = "기체 스냅숏의 파지"
        const val APPLY_BASIS = "승인 직전 기체의 손"
    }
}

/**
 * [unit] 에 사람이 낸 걸음이 원래 단위의 인시던트에 자리가 없을 때 붙을 단위들(§15.320) — 조치 실패로 미발행 보류([REMEDY_NOT_DONE])에 선 단위면 그 승인의
 * 조치 단위들, 아니면 빈 집합. [IncidentLog.noteResolution] 이 그 단위의 빈 판단 칸을 못 찾을 때 이 단위들의 가장 최근 빈칸에 붙인다.
 * 걸음이 단위를 되돌리기 전에 부른다(재작업은 실패 분류를 지운다).
 */
internal fun Execution.stepSlots(unit: ExecutionUnit): Set<String> =
    if (unit.failureClass == REMEDY_NOT_DONE) approval?.steps.orEmpty().mapTo(HashSet()) { it.unitId } else emptySet()

/**
 * 재기동 복원의 회복 몫(§15.320) — 판단 기록을 옮긴 직후([Middleware.resume], 첫 pump 전)에 부른다. 둘을 바로잡는다.
 *
 * - 승인이 회복이었으면(위치 칸) 다시 세운 소모 기록의 대상을 그 보류로 둔다. 옮기는 단계는 대상을 모르고 작업 수락으로 세운다.
 * - 판정을 옮긴 단위(기록의 시도가 남은 단위)에 거부의 구조화된 주어를 채운다 — 다시 연 보류에서 회복 재확인이 다시 돌게 한다. 인시던트 봉인보다 먼저라
 *   다시 연 인시던트도 그 주어를 싣는다.
 */
internal fun RemedyDesk.restoreRecovery(execution: Execution, record: JudgmentRecord) {
    record.approval?.let { a ->
        val position = a.position ?: return@let
        restore(a.robotId, execution.order.jobOrderId, ConsumedApproval(a.approver, a.at, a.wallClockAt, execution.executionId, a.steps, position))
    }
    val byId = execution.units.associateBy { it.unitId }
    record.units.forEach { r ->
        val subjects = r.verdict?.preconditionSubjects.orEmpty()
        val unit = byId[r.unitId] ?: return@forEach
        if (subjects.isNotEmpty() && unit.attempt == r.attempt && unit.state == r.verdict?.state) unit.preconditionSubjects = subjects
    }
}

/**
 * 그 작업 지시의 실행에서 [target] 이 아직 같은 판·같은 시도의 운영자 보류이고 취소가 걸리지 않았는가(§15.320). 회복 제안의 판정([RemedyDesk.judge])이
 * 묻는다 — 아니면 그 제안은 낡았다(사람이 재작업·확인·취소했거나 개정으로 판이 올랐다).
 */
internal fun holdStands(executions: Collection<Execution>, jobOrderId: String, target: ProposalTarget.Hold): Boolean {
    val execution = executions.firstOrNull { it.order.jobOrderId == jobOrderId } ?: return false
    if (execution.cancelRequested || execution.order.version != target.orderVersion) return false
    return execution.units.any { it.unitId == target.unitId && it.attempt == target.attempt && it.state == UnitState.OPERATOR_HOLD }
}

/**
 * 판단 기록의 승인 걸음을 계획에 다시 세운다([Middleware.resume]). 위치 칸([RecordedApproval.position])이 있으면 그 단위 바로 앞, 없으면(접수 승인) 맨
 * 앞이다 — 처음 승인이 세운 자리와 같아야 다시 지은 실행이 같은 순서로 돈다. 위치의 단위가 계획에 없으면 맨 앞이다.
 */
internal fun plannedWith(approval: RecordedApproval?, plan: List<ExecutionUnit>): List<ExecutionUnit> {
    val steps = approval?.steps.orEmpty().map { remedyUnit(it.unitId, it.skillType, it.parameters) }
    val at = approval?.position?.let { p -> plan.indexOfFirst { it.unitId == p.unitId } }?.takeIf { it >= 0 } ?: 0
    return plan.take(at) + steps + plan.drop(at)
}

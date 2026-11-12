package dev.picasso.middleware

import dev.picasso.middleware.Middleware.Companion.RESUME_ATTEMPT
import dev.picasso.middleware.Middleware.Companion.RESUME_HOLD_REOPENED
import dev.picasso.middleware.Middleware.Companion.RESUME_PRIOR_ROBOT
import dev.picasso.middleware.Middleware.Companion.RESUME_RECORD
import dev.picasso.middleware.Middleware.Companion.RESUME_RECORD_MISMATCH
import dev.picasso.middleware.Middleware.Companion.RESUME_RECORD_SUPERSEDED
import dev.picasso.middleware.Middleware.Companion.RESUME_SIGNAL_PASSED
import dev.picasso.middleware.Middleware.Companion.RESUME_STALE_VERSION
import dev.picasso.middleware.Middleware.Companion.RETRY_SUFFIX
import dev.picasso.middleware.Middleware.Execution
import dev.picasso.middleware.Middleware.Submission
import java.time.Instant

// 재개와 판단 기록의 비공개 절차. 공개 창구(resume, judgmentRecord)는 Middleware 에 있고 여기 함수들을 부른다.
// 상태(실행 표, 실행)는 Middleware 에 있다. 이 파일은 같은 클래스의 일부를 파일로만 나눈 것이다
// (docs/superpowers/plans/2026-11-12-middleware-seam-split-2.md).

/**
 * 기체가 넘긴 작업 지시보다 높은 리비전으로 그 작업 지시의 태스크를 드는가. 들면 거부의 사유, 아니면 널.
 *
 * 들면 넘긴 것은 앞 인스턴스가 받은 마지막 버전이 아니다. 그대로 지으면 그 단위의 재전송이 `OUTDATED_REVISION` 으로 실패로
 * 적히고, 기체가 아직 그 태스크를 도는 동안 실행이 다음 단위를 낸다(§15.260). 리비전을 안 싣는 포트에서는 볼 수 없다(§15.238).
 */
internal fun Middleware.staleVersionRefusal(order: JobOrder, snapshots: Map<String, RobotSnapshot>): Submission.Rejected? {
    val prefix = "${order.jobOrderId}#"
    val ahead = snapshots.flatMap { (robot, snapshot) ->
        snapshot.revisions.filter { (taskId, revision) -> taskId.startsWith(prefix) && revision > order.version }
            .map { (taskId, revision) -> "$robot:$taskId@$revision" }
    }
    if (ahead.isEmpty()) return null
    return Submission.Rejected("$RESUME_STALE_VERSION: 작업 지시 버전=${order.version}, 기체가 든 리비전=${ahead.joinToString()}")
}

/** 판단 기록이 넘긴 작업 지시와 맞는가. 맞지 않으면 거부의 사유, 맞으면 널. */
internal fun Middleware.recordRefusal(order: JobOrder, record: JudgmentRecord, planned: List<ExecutionUnit>): Submission.Rejected? {
    val why = when {
        record.jobOrderId != order.jobOrderId -> "작업 지시가 다르다: 기록=${record.jobOrderId}, 작업 지시=${order.jobOrderId}"
        record.orderVersion != order.version -> "버전이 다르다: 기록=${record.orderVersion}, 작업 지시=${order.version}"
        else -> (record.units.map { it.unitId } - planned.map { it.unitId }.toSet()).takeIf { it.isNotEmpty() }
            ?.let { "계획에 없는 단위가 있다: $it" }
    } ?: return null
    return Submission.Rejected("$RESUME_RECORD_MISMATCH: $why")
}

/**
 * [resume] 의 7 — 판단 기록을 옮긴다. 판정은 그대로 쓰고 다시 내리지 않는다. 옮긴 것은 실행 자취에 [RESUME_RECORD] 로 남긴다.
 *
 * 시도 번호는 스냅숏과 기록 가운데 큰 쪽이다. 기록이 크면 재작업 판단이 섰는데 아직 내지 않은 것이고, 스냅숏이 크면 기록이 그
 * 재작업보다 먼저 적힌 것이다 — 그때 그 단위의 시각과 판정은 앞 시도의 것이라 옮기지 않는다.
 *
 * **파지 이력은 시도에 맞춰 옮긴다**(§15.257). 다시 관측해도 돌아오지 않는 지난 관측이다 — 재기동 사이에 놓쳤으면 기체는 빈손을
 * 보이고, 끝난 시도의 파지는 스냅숏에 없다. 시도가 같으면 그대로, 스냅숏이 더 크면 [reworkAsNewAttempt] 처럼 앞 시도의 것으로
 * 접는다. 지금의 파지([ExecutionUnit.hold])는 건드리지 않는다 — 그것은 이 인스턴스가 기체에서 본다.
 *
 * 돌려주는 것은 운영자 보류를 다시 연 시각이다. 처음 다시 열 때 시계를 한 번 읽고 다시 연 보류 모두에 쓴다. 다시 연 것이
 * 없으면 널이다.
 */
internal fun Middleware.restore(execution: Execution, record: JudgmentRecord): Instant? {
    var reopenedAt: Instant? = null
    // **건 취소는 걸린 채로 짓는다**(§15.253). 걸지 않으면 그 사이 취소로 끝난 하위 태스크를 요청 없는 취소로 읽는다
    // ([holdUnrequestedCancel]). 하위로 보내는 것은 첫 pump 다 — 붙지 않은 태스크는 [attachForCancel] 이 붙어 멈춘다.
    if (record.cancelRequested) {
        execution.command.cancelIntent = CancelIntent.Requested
        execution.physicalState = PhysicalState.CANCELING
        execution.trail(RESUME_RECORD, "cancel requested before restart — 걸린 채로 짓고 첫 pump 가 하위로 보낸다")
    }
    execution.acknowledgedFaults += record.acknowledgedFaults
    if (record.acknowledgedFaults.isNotEmpty()) {
        execution.trail(RESUME_RECORD, "acknowledged faults: ${record.acknowledgedFaults.joinToString()}")
    }
    record.approval?.let { approval ->
        execution.approval = approval
        execution.approvedBy = approval.approver
        // **소모로 다시 세운다.** 실행 id 는 이 인스턴스의 것으로 단다 — 앞 인스턴스의 id 는 여기서 가리키는 것이 없다(ADR 48).
        desk.restore(
            approval.robotId,
            execution.order.jobOrderId,
            ConsumedApproval(approval.approver, approval.at, approval.wallClockAt, execution.executionId, approval.steps),
        )
        execution.trail(RESUME_RECORD, "approval consumed by ${approval.approver.id}(${approval.approver.kind}) at ${approval.at}")
    }
    val byId = execution.units.associateBy { it.unitId }
    record.units.forEach { r ->
        val unit = byId.getValue(r.unitId)
        r.decision?.let { execution.decisions[unit.unitId] = it }
        if (r.attempt < unit.attempt) {
            // 기록의 «이 시도» 는 스냅숏의 앞 시도다. 새 시도의 파지로 옮기면 쥔 적 없는 시도가 든 채를 기대한다.
            unit.heldInEarlierAttempt = r.heldInEarlierAttempt || r.everHeld
            execution.trail(
                RESUME_RECORD_SUPERSEDED,
                "${unit.unitId}: 스냅숏의 시도 ${unit.attempt} 가 기록의 시도 ${r.attempt} 보다 크다, 기록의 시각과 판정을 옮기지 않는다",
            )
            return@forEach
        }
        unit.attempt = r.attempt
        unit.everHeld = r.everHeld
        unit.heldInEarlierAttempt = r.heldInEarlierAttempt
        r.requestedAt?.let { execution.restoredRequests[unit.unitId] = r.attempt to it }
        r.verifyWindow?.let { execution.restoredWindows[unit.unitId] = r.attempt to it }
        val verdict = r.verdict ?: return@forEach
        unit.state = verdict.state
        unit.reached = verdict.reached
        unit.verification = verdict.verification
        unit.evidenceAt = verdict.evidenceAt
        unit.failureClass = verdict.failureClass
        unit.note = verdict.note
        unit.result = verdict.result
        unit.taskId = r.taskId
        unit.requestedAt = r.requestedAt
        execution.resumedPrior -= unit.unitId
        execution.trail(
            RESUME_RECORD,
            "${unit.unitId}: verdict ${verdict.state.name} ${verdict.reached.name} ${verdict.verification.name} (attempt ${r.attempt}), 다시 판정하지 않는다",
        )
        if (verdict.state == UnitState.OPERATOR_HOLD) {
            val at = reopenedAt ?: now().also { reopenedAt = it }
            reopenHold(execution, unit, r, verdict, at)
        }
    }
    return reopenedAt
}

/**
 * [resume] 의 8 — 기록의 운영자 보류를 **이 인스턴스의 인시던트로 다시 연다.**
 *
 * 인시던트 번들은 판단 기록에 들지 않는다. 그래서 보류로 옮긴 단위는 사람이 판단할 자리인데 이 인스턴스에 열린 인시던트가
 * 없고, 담는 쪽의 판단 화면은 열린 인시던트에서 판단을 받으므로 사람이 그 보류를 풀 길을 잃는다.
 *
 * **관측을 지어내지 않는다.** 번들은 지금 단위가 든 것(기록에서 옮긴 실패 분류·근거 등급·대조 결과)만 싣고, 결함 원문·파지
 * 관측은 이 인스턴스가 아직 보지 않았으므로 비어 있다. 기록에만 있는 사실(기체, 시도, 요청 시각, 근거 시각, 보류 사유)은 실행
 * 자취에 [RESUME_HOLD_REOPENED] 로 남기고, 그 줄이 번들의 근거 윈도우에 실려 «재기동이 다시 연 인시던트» 의 표시가 된다.
 * 이 인스턴스가 보류를 낸 시각이 아니므로 번들의 `at` 은 다시 연 시각이다 — 기록에는 보류가 선 시각이 따로 없다.
 *
 * **줄 시각과 번들의 `at` 은 같은 한 번의 읽기다([at]).** 줄이 있는 것만으로는 가르지 못한다 — 다시 연 뒤 근거 윈도우 앞 폭
 * 안에 새로 선 보류의 윈도우에도 그 줄이 실린다. 가르는 것은 그 줄의 시각이 번들 `at` 과 같은 것이고, 시계가 흘러도 그렇다.
 *
 * 판단은 보통의 보류와 같다. [resolve] 가 보류를 풀고 판단 기록에 실으며, 판단은 이 인시던트에 붙는다.
 * 봉인은 [resume] 이 끝에서 한 번 한다 — 첫 [pump] 전에도 판단 화면이 이 인시던트를 읽는다.
 */
private fun Middleware.reopenHold(execution: Execution, unit: ExecutionUnit, r: UnitRecord, verdict: UnitVerdict, at: Instant) {
    execution.trail(
        RESUME_HOLD_REOPENED,
        "${unit.unitId}: 기록의 운영자 보류를 이 인스턴스의 인시던트로 다시 연다, robot=${execution.robotOf(unit)} " +
            "attempt=${r.attempt} failureClass=${verdict.failureClass} requestedAt=${r.requestedAt} " +
            "evidenceAt=${verdict.evidenceAt} note=${verdict.note}",
        at,
    )
    execution.markIncident(unit)
}

/** [resume] 의 4·5·6. 스냅숏의 태스크 id 로 재작업 횟수, 지난 설비 대기, 앞선 로봇 단위를 정하고 자취에 남긴다. */
internal fun Middleware.rebuildFrom(execution: Execution, snapshots: Map<String, RobotSnapshot>) {
    val jobOrderId = execution.order.jobOrderId
    var lastHeld = -1
    execution.units.forEachIndexed { index, unit ->
        if (unit.route != Route.ROBOT) return@forEachIndexed
        val base = "$jobOrderId#${unit.unitId}"
        // 단위가 묶인 기체의 스냅숏이다. 실행의 기체로 읽으면 다른 기체에 묶인 단위의 재작업 횟수를 못 찾는다.
        val tasks = snapshots.getValue(execution.robotOf(unit)).tasks
        val found = tasks.keys.mapNotNull { taskId -> attemptOf(taskId, base)?.let { taskId to it } }
        val top = found.maxByOrNull { it.second } ?: return@forEachIndexed
        unit.attempt = top.second
        execution.unattached[unit.unitId] = top.second
        lastHeld = index
        execution.trail(RESUME_ATTEMPT, "${unit.unitId}: attempt=${top.second} (스냅숏 태스크 ${top.first})")
    }
    val last = execution.units.getOrNull(lastHeld) ?: return
    val before = execution.units.take(lastHeld)
    before.filter { it.route == Route.SIGNAL }.forEach {
        it.state = UnitState.DONE
        execution.trail(
            RESUME_SIGNAL_PASSED,
            "${it.unitId}: 뒤 로봇 단위 ${last.unitId} 의 태스크가 기체에 있어 통과한 것으로 둔다, 이 인스턴스가 관측하지 않아 근거 등급 E0",
        )
    }
    before.filter { it.route == Route.ROBOT }.forEach {
        execution.resumedPrior += it.unitId
        execution.trail(
            RESUME_PRIOR_ROBOT,
            "${it.unitId}: 뒤 로봇 단위 ${last.unitId} 보다 앞서 성공을 다시 관측해도 설비 근거를 다시 묻지 않는다, 근거 등급 E0",
        )
    }
}

/** [taskId] 가 [base] 의 태스크면 재작업 횟수, 아니면 널. 접미사가 없으면 0 이다. */
private fun Middleware.attemptOf(taskId: String, base: String): Int? {
    if (taskId == base) return 0
    if (!taskId.startsWith(base)) return null
    return RETRY_SUFFIX.matchEntire(taskId.substring(base.length))?.groupValues?.get(1)?.toIntOrNull()
}

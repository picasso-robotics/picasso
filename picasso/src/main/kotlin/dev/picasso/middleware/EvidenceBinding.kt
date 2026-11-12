package dev.picasso.middleware

import dev.picasso.capability.HoldEffects
import dev.picasso.capability.HoldMismatch
import dev.picasso.contracts.v1.Fault
import dev.picasso.middleware.Middleware.Companion.MISMATCH
import dev.picasso.middleware.Middleware.Companion.OPEN_UNIT_STATES
import dev.picasso.middleware.Middleware.Companion.PREDECESSOR_NOT_DONE
import java.time.Instant

// 근거 결합(보고서 12장)과 운영자 판단의 보조. 공개 창구(resolve)는 Middleware 에 있다. 상태는 Middleware 에 있다.

/**
 * 설계안 §5 — 선언된 효과와 마지막 관측을 대조해, 운영자에게 «모른다» 로 나갈 자리 중 **근거로 판정할 수
 * 있는 것을 판정으로 바꾼다.** 미결이 «손에 없다»·«아직 들고 있다» 가 되면 다음 행동이 갈린다(§5.3).
 *
 * **플릿의 운반은 보지 않는다** — 카탈로그에 없는 단위의 효과를 지어내지 않는다. 그리고 관측이 없거나
 * 볼 수 없으면 [HoldEffects.compare] 가 판정하지 않는다(§5.2).
 */
internal fun Middleware.judgeEffectMismatch(execution: Execution, unit: ExecutionUnit, completed: Boolean) {
    if (unit.route != Route.ROBOT) return
    val expected = HoldEffects.expectedAtEnd(unit.skillType, unit.everHeld, completed) ?: return
    // **어긋나지 않아도 기대를 남긴다.** 판단 경로는 판정이 났을 때만 필요한 것이 아니다 —
    // «무엇을 기대했고 무엇을 봤는가» 가 있어야 읽는 사람이 왜 아무 판정도 안 났는지까지 따라간다.
    unit.holdExpected = expected
    val mismatch: HoldMismatch = HoldEffects.compare(expected, unit.hold.kind) ?: return
    if (unit.holdMismatch == mismatch) return
    unit.holdMismatch = mismatch
    unit.annotate("effect/observation mismatch: ${mismatch.name} (expected=${expected.name} hold=${unit.hold.kind.name})")
    // **성공으로 끝났는데 어긋난 경우에도 사건을 연다.** 하류는 끝났다는데 손에 남아 있다 —
    // 운영자가 봐야 하는 사실이고, 사건이 없으면 그 사실이 어디에도 안 실린다.
    execution.markIncident(unit)
}

/**
 * 하류의 완료를 받는다 — 그리고 **근거를 결합하기 시작한다**(보고서 10.2 — 번역만으로는 안 된다).
 *
 * 하류가 준 등급([reachedByDownstream]: 로봇 E0, 플릿 E1)이 요구 이상이면 그대로 완료.
 * 아니면 [UnitState.VERIFYING] 으로 옮기고 시간창이 닫힐 때까지([EvidenceWindow.after])
 * 설비에 묻는다 — PLC 는 폴링이라 신호가 보고보다 **늦게** 읽힐 수 있다. 첫 확인은 지금 한다.
 */
internal fun Middleware.beginVerify(
    execution: Execution,
    unit: ExecutionUnit,
    reachedByDownstream: Evidence,
    doneAt: Instant,
    strict: Boolean = false,
    prior: Boolean = false,
    /** 판단 기록이 넘긴 근거 기한. 널이면 지금 정한다. */
    deadline: Instant? = null,
) {
    unit.reached = reachedByDownstream
    unit.downstreamDoneAt = doneAt
    // `strict` — 하류의 보고만으로는 못 닫는다(지연 이벤트: 옛 버전의 완료). 설비가 지금 버전의 기대에 대고 봐야 한다.
    // `prior` 는 [resume] 이 앞선 것으로 둔 로봇 단위다. 설비를 다시 묻지 않고 하류 등급 그대로 닫는다.
    if (!strict && (prior || execution.order.requiredEvidence <= reachedByDownstream)) {
        unit.verification = Verification.NOT_REQUESTED
        unit.state = UnitState.DONE
        return
    }
    unit.state = UnitState.VERIFYING
    // **이 순간의 뒤 폭으로 정해 저장한다.** 나중에 뒤 폭이 바뀌어도 다시 계산하지 않는다.
    unit.evidenceDeadline = deadline ?: doneAt.plus(windowOf(execution).after)
    checkEvidence(execution, unit)
}

/**
 * 시간창 안에서 설비 신호를 찾는다(보고서 12.1):
 * `t_p ∈ [t_r − before, t_r + after]` 이고 재석이면 **이 완료의** 근거다.
 *
 * - 맞으면 E2 `DONE`. 다르면 오인계 의심 — `FAILED` `VERIFICATION_MISMATCH`, 무엇을 어디서 봤는지 기록(12.3 넷째 행).
 * - 창 밖의 신호(옛 것)는 세지 않는다. 창이 닫힐 때까지 없으면 `UNVERIFIED` — 재작업 금지, 운영자 확인(첫째 행).
 * - 시각을 안 주는 설비는 **읽은 순간**을 `t_p` 로 친다.
 *
 * @return 단위가 종착했는가(아직 기다리는 중이면 `false`).
 */
internal fun Middleware.checkEvidence(execution: Execution, unit: ExecutionUnit): Boolean {
    val doneAt = unit.downstreamDoneAt!!
    val window = windowOf(execution)
    val current = now()
    unit.rechecks += 1

    val signal = execution.observeCell(unit)
    val observedAt = signal?.observedAt ?: current
    val inWindow = signal != null && signal.occupied &&
        !observedAt.isBefore(doneAt.minus(window.before)) && !observedAt.isAfter(doneAt.plus(window.after))

    when {
        inWindow && unit.expectedIdentity != null && signal!!.identity != unit.expectedIdentity -> {
            unit.verification = Verification.MISMATCH
            unit.state = UnitState.FAILED
            unit.failureClass = MISMATCH
            unit.evidenceAt = observedAt
            unit.annotate("observed=${signal.identity} at ${unit.destination}")
            execution.markIncident(unit)
        }

        inWindow -> {
            unit.verification = Verification.MATCHED
            unit.reached = Evidence.E2
            unit.evidenceAt = observedAt
            unit.state = UnitState.DONE
        }

        current.isAfter(unit.evidenceDeadline!!) -> {
            unit.verification = Verification.ABSENT
            unit.state = UnitState.UNVERIFIED
            unit.annotate(
                if (signal != null && signal.occupied) {
                    "signal at $observedAt is outside [${doneAt.minus(window.before)}, ${doneAt.plus(window.after)}] — stale"
                } else {
                    "no signal within window after ${unit.rechecks} rechecks"
                },
            )
            // 신호가 윈도우 밖이라 닫혔다면 윈도우의 기준인 발신자 시계를 의심할 만하다. 의심이 있을 때만 싣는다.
            if (signal != null && signal.occupied) {
                clockSuspicionOf(execution, views[execution.robotOf(unit)]?.clockOffset)?.let { unit.annotate("시계 차 의심 — ${it.describe()}") }
            }
        }

        else -> {
            execution.physicalState = PhysicalState.RUNNING
            return false
        }
    }
    return true
}

/**
 * 하류는 실패라는데 설비에는 있을 수 있다(보고서 12.3 둘째 행 — *물리 완료 가능성*).
 * 요구 등급이 설비 확인을 포함하면 지금 묻고, 시간창 안에 기대한 것이 있으면 `FAILED` 로
 * 적지 않고 [UnitState.OPERATOR_HOLD] 로 세운다 — 운영자가 [resolve] 로 판단한다.
 */
internal fun Middleware.failWithEvidenceCheck(
    execution: Execution,
    unit: ExecutionUnit,
    failureClass: String,
    detail: String,
    at: Instant,
    /** 분류가 그 값이 된 근거. 하류가 결함 없이 실패를 알렸으면 널이다(§15.177). */
    fault: Fault? = null,
) {
    unit.failureClass = failureClass
    unit.fault = fault
    unit.note = detail
    unit.downstreamDoneAt = at
    if (execution.order.requiredEvidence > Evidence.E1) {
        val window = windowOf(execution)
        val signal = execution.observeCell(unit)
        val observedAt = signal?.observedAt ?: now()
        val present = signal != null && signal.occupied &&
            (unit.expectedIdentity == null || signal.identity == unit.expectedIdentity) &&
            !observedAt.isBefore(at.minus(window.before)) && !observedAt.isAfter(at.plus(window.after))
        if (present) {
            unit.verification = Verification.MATCHED
            unit.evidenceAt = observedAt
            unit.state = UnitState.OPERATOR_HOLD
            unit.note = "downstream reported $failureClass ($detail) but evidence present at ${unit.destination}"
            execution.markIncident(unit)
            return
        }
    }
    unit.state = UnitState.FAILED
    execution.markIncident(unit)
}

/**
 * 단위를 새 시도로 되돌린다. 다음 [startUnit] 이 `@rN` 태스크 id 로 낸다. [resolve] 의 재작업이 보류 단위와 건너뛴 대상 두 경로에서
 * 같은 되돌림을 쓴다 — 경로마다 풀어 쓰면 한쪽만 고치는 날 시도 번호와 태스크 id 가 어긋난다.
 */
internal fun Middleware.reworkAsNewAttempt(unit: ExecutionUnit) {
    unit.attempt += 1
    unit.state = UnitState.PENDING
    unit.taskId = ""
    unit.startedRevision = null
    unit.failureClass = null
    unit.verification = Verification.NOT_REQUESTED
    unit.note = "operator ordered rework"
    // 시도별 관측을 비운다(§15.236). 남기면 새 시도의 사건 번들이 앞 시도의 결함·어긋남·근거 등급을 싣고, 정체는 한 번 알린 표시와 앞
    // 시도의 진행률에 막혀 새 시도에서 다시 알리지 않는다. 비운 값은 계획이 처음 지은 단위의 값이다. 쥔 것을 본 사실만 따로 남긴다 —
    // 재할당이 그것으로 반쯤 한 물리 작업을 거른다.
    unit.heldInEarlierAttempt = unit.heldInEarlierAttempt || unit.everHeld
    unit.everHeld = false
    unit.holdExpected = null
    unit.holdMismatch = null
    unit.fault = null
    unit.reached = Evidence.E0
    unit.downstreamDoneAt = null
    unit.evidenceDeadline = null
    unit.evidenceAt = null
    unit.rechecks = 0
    unit.requestedAt = null
    unit.progress = 0.0
    unit.progressAt = null
    unit.progressObservable = null
    unit.progressStalled = false
    unit.preconditionSubjects = emptyList()
    unit.result = null
}

/**
 * [unit] 이 건너뛴 점검 모양이면 그 선행 이동을, 아니면 널을 돌려준다.
 *
 * 모양은 다섯이다. 단위가 `FAILED` 이고 분류가 [PREDECESSOR_NOT_DONE] 이며 태스크를 쓴 적이 없고(내지 않았다), 선행([ExecutionUnit.after])이
 * 이 실행에 있으며 `FAILED` 이고 태스크를 썼다(시도된 실패). 선행이 `UNVERIFIED`·`ABORTED` 이거나 점검 자신이 시도 뒤에 실패한
 * 단위는 이 경로의 범위 밖이다.
 */
private fun Middleware.skippedPair(execution: Execution, unit: ExecutionUnit): ExecutionUnit? {
    if (unit.state != UnitState.FAILED || unit.failureClass != PREDECESSOR_NOT_DONE || unit.taskId.isNotEmpty()) return null
    val predecessor = execution.units.firstOrNull { it.unitId == unit.after } ?: return null
    if (predecessor.state != UnitState.FAILED || predecessor.taskId.isEmpty()) return null
    return predecessor
}

/**
 * [resolve] 의 건너뛴 대상 경로. 보류 단위가 아닌 [unitId] 가 건너뛴 점검이면 그 점검과 선행 이동을 한 쌍으로 재작업한다.
 *
 * - 건너뛴 점검 모양이 아니면 [ResolveOutcome.NotHeld] 다(이동 단위 id, 시도 뒤에 실패한 점검, 끝난 단위, 없는 단위).
 * - [OperatorDecision.CONFIRM_DONE] 은 [ResolveOutcome.Refused] 다. 확인은 «하위는 실패라는데 설비에는 있다» 를 사람이 닫는 자리이고
 *   하지 않은 점검에는 확인할 것이 없다.
 * - 받을 수 없는 때는 [skippedReworkRefusal] 의 사유로 [ResolveOutcome.Refused] 다. 아무것도 안 바뀐다.
 *
 * 받으면 두 단위에 같은 판단을 적고(시도는 오르기 전 값) 같은 되돌림을 쓴 뒤 실행을 `RUNNING` 으로 두고 작업 응답 하나를 낸다.
 * 인시던트에는 **선행 이동의 것**에 판단을 붙인다 — 건너뛴 점검은 인시던트를 열지 않았다. 새 인시던트는 열지 않고, 되돌린 이동이 다시
 * 실패하면 보통 경로로 연다.
 *
 * 작업 응답은 [noteAttention] 이 아니라 [notify] 로 직접 낸다. [noteAttention] 은 `OPERATOR_HOLD`·`IN_DOUBT` 가 풀리는 것만 대 보므로
 * `PARTIAL`·`FAILED` 에서 `RUNNING` 으로 돌아오는 것은 그 비교에 잡히지 않는다.
 */
internal fun Middleware.resolveSkipped(execution: Execution, unitId: String, decision: OperatorDecision, approver: Approver): ResolveOutcome {
    val skipped = execution.units.firstOrNull { it.unitId == unitId } ?: return ResolveOutcome.NotHeld
    val travel = skippedPair(execution, skipped) ?: return ResolveOutcome.NotHeld
    if (decision == OperatorDecision.CONFIRM_DONE) {
        return ResolveOutcome.Refused("점검을 하지 않은 단위는 확인할 것이 없다 — 재작업만 받는다: $unitId")
    }
    skippedReworkRefusal(execution, skipped, travel)?.let { return ResolveOutcome.Refused(it) }

    val at = now()
    for (unit in listOf(travel, skipped)) {
        execution.decisions[unit.unitId] = RecordedDecision(decision, approver, at, unit.attempt)
        reworkAsNewAttempt(unit)
    }
    execution.noticed.clear(Notice.Hold)
    execution.physicalState = PhysicalState.RUNNING
    notify(execution)
    incidentLog.noteResolution(execution.executionId, travel.unitId, decision, approver)
    return ResolveOutcome.Resolved
}

/**
 * 건너뛴 대상의 재작업을 **지금 받지 않는 사유**. 받을 수 있으면 널이다.
 *
 * - 취소한 실행은 받지 않는다. 사람이나 상위가 멈추기로 한 결정이다.
 * - 순회가 종결한 뒤에만 받는다. 실행이 `PARTIAL`·`FAILED` 이고 낼 단위·도는 단위·미확정·보류가 없어야 한다. 도는 중에 앞 대상을
 *   되돌리면 다음 단위가 앞 대상의 이동 뒤에 나가 다른 대상의 점검이 앞 대상의 자리에서 나간다. [ExecutionUnit.after] 는 선행이
 *   `DONE` 인지만 보고 기체가 지금 그 자리에 있는지는 보지 않는다.
 * - 두 단위의 기체를 쥔 다른 활성 실행이 없어야 한다. `PARTIAL` 은 기체를 쥔 채라 그 사이 다른 실행이 서지 않지만 `FAILED` 는
 *   기체를 내주므로 그 사이 다른 실행이 섰을 수 있다.
 */
private fun Middleware.skippedReworkRefusal(execution: Execution, skipped: ExecutionUnit, travel: ExecutionUnit): String? {
    val state = execution.physicalState
    if (state == PhysicalState.ABORTED) return "취소한 실행의 대상은 재작업하지 않는다 — 새 작업 지시로 낸다"
    if ((state != PhysicalState.PARTIAL && state != PhysicalState.FAILED) || execution.units.any { it.state in OPEN_UNIT_STATES }) {
        return "순회가 종결한 뒤 한 대상씩 재작업한다 — 아직 낼 단위나 도는 단위가 있다"
    }
    val robots = setOf(execution.robotOf(skipped), execution.robotOf(travel))
    val other = executions.values.firstOrNull {
        it.executionId != execution.executionId && it.physicalState.occupiesRobot && it.robots.any { robot -> robot in robots }
    }
    return other?.let { "같은 기체에 다른 활성 실행이 있다 — 그 실행이 끝난 뒤 재작업한다: ${it.executionId}" }
}

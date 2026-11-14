package dev.picasso.middleware

import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.middleware.Middleware.Submission
import dev.picasso.middleware.MiddlewareCodes.ISSUE_RESUMED
import dev.picasso.middleware.MiddlewareCodes.ISSUE_WITHHELD
import dev.picasso.middleware.MiddlewareCodes.UPDATE_NOT_SENT

// 리비전 개정: 높은 버전의 작업 지시로 계획을 다시 세우고, 도는 단위에 개정 갱신을 보내고 정산한다.
// 상태는 Middleware 에 있다. 개정 갱신의 송신은 명령 생애(Lifecycle*.kt)와 같은 송신 기록(InFlight)을 쓴다.

/**
 * 리비전이 아직 내지 않은 단위를 새 계획의 단위([fresh])로 바꿀 때 **시도와 앞 시도의 파지를 옮긴다.** 새 계획의 단위는 시도 0 이고
 * 쥔 적이 없다. 재작업이 올린 시도를 잃으면 다음 [startUnit] 이 첫 시도의 태스크 id 로 내고, 같은 태스크 id 로 보내면 기체에 있던 첫
 * 시도의 태스크에 붙는다. 앞 시도의 파지([ExecutionUnit.heldInEarlierAttempt])를 잃으면 재할당이 반쯤 한 물리 작업을 옮긴다.
 * 새 계획에 이 단위가 없으면 그대로 둔다.
 */
private fun ExecutionUnit.replannedAs(fresh: ExecutionUnit?): ExecutionUnit =
    fresh?.also {
        it.attempt = attempt
        it.heldInEarlierAttempt = heldInEarlierAttempt
    } ?: this

/**
 * @param prefix 승인된 조치 열(§15.175). [commit] 만 채운다. 새 계획에서 다시 계획하는 첫 단위 앞에 서고, 사슬 검사도 그 앞에서
 *   굴린다 — 첫 접수가 계획 맨 앞에 세우는 것과 같은 모양이다. 이미 승인을 든 실행에는 [commit] 이 부르지 않는다.
 */
internal fun CommandEnv.revise(
    gate: AdmissionGate,
    desk: RemedyDesk,
    execution: Execution,
    order: JobOrder,
    prefix: List<ExecutionUnit> = emptyList(),
    approvedBy: Approver? = null,
): Submission {
    // **실행이 쥔 케이퍼빌리티로 판정한다**(카탈로그가 아니다). 다른 WorkMaster 로 바꾸는 리비전은 그 케이퍼빌리티로
    // 계획할 수 없으므로 받지 않는다 — 새 작업 지시다.
    if (order.workMasterId != execution.order.workMasterId) {
        return Submission.Rejected(
            "리비전이 WorkMaster 를 바꾼다: 받은 값=${order.workMasterId}, 현재=${execution.order.workMasterId} — 새 작업 지시로 낸다",
        )
    }
    evidenceRefusal(order, execution.capability)?.let { return it }
    if (order.version == execution.order.version) return Submission.Idempotent(execution)
    if (order.version < execution.order.version) {
        return Submission.Rejected("이미 지난 버전이다: 받은 값=${order.version}, 현재=${execution.order.version}")
    }
    if (execution.physicalState == PhysicalState.ABORTED || execution.physicalState == PhysicalState.PHYSICALLY_DONE) {
        return Submission.Rejected("종료한 실행에는 새 버전이 붙지 않는다 — 재작업은 새 주문이다(보고서 14.1)")
    }

    // 개정판도 사슬 검사를 받는다 — 단, 이 개정이 실제로 교체·추가할 단위만(리뷰 C3). 이미 끝났거나 도는 단위를
    // 다시 재단하면 끝난 일을 되돌리라는 말이 된다.
    val fresh = execution.capability.plan(order)
    duplicateUnitIdRefusal(prefix + fresh)?.let { return it }
    val toPlan = fresh.filter { f ->
        val current = execution.units.firstOrNull { it.unitId == f.unitId }
        current == null || current.state == UnitState.PENDING ||
            (current.state == UnitState.FAILED && current.taskId.isEmpty())
    }
    // **이미 승인을 든 실행의 개정은 제안을 세우지 않는다**(ADR 46, §15.175). 판단 기록이 승인 하나만 싣고, 그 열쇠에는 소모 기록이
    // 서 있다 — 세우면 재시도가 «이미 소모됐다» 를 못 본다. 승인한 조치 열을 붙인 개정의 거절은 서 있는 제안을 바꾸지 않는다.
    // 그 실행 자신의 도는 단위는 남은 단위 앞의 고리다 — 그 단위가 끝난 뒤의 파지에서 출발한다(§15.315).
    gate.chainRefusal(execution.robotId, prefix + toPlan, revising = execution)?.let {
        return desk.record(
            execution.robotId, order, it, carriesApproval = execution.approval != null, approval = approvedBy != null, revising = execution.executionId,
        )
    }

    // **확정된 단위 이후에만 붙는다.** 종착한 단위는 그대로(래치 — 계약이 이미 그렇게 한다),
    // 아직 안 시작한 단위는 새 계획으로 교체, 도는 단위는 계약의 갱신 규칙(§4.4)을 탄다.
    val replanned = fresh.associateBy { it.unitId }
    val kept = execution.units.map { unit ->
        when (unit.state) {
            UnitState.DONE, UnitState.UNVERIFIED, UnitState.ABORTED,
            UnitState.VERIFYING, UnitState.OPERATOR_HOLD -> unit
            // 물리적으로 시도된 실패는 확정된 단위다. 계획 단계에서 못 시작한 것(태스크가 없다)은
            // 아니다 — C형 부족처럼 새 버전이 공급을 채우면 그 슬롯은 다시 계획된다.
            UnitState.FAILED -> if (unit.taskId.isEmpty()) {
                unit.replannedAs(replanned[unit.unitId]).also { it.revision = order.version }
            } else {
                unit
            }
            // **보내는 중인 첫 출발의 단위는 바꾸지 않고 고친다**(§15.237). 바꾸면 그 출발의 결과가 받을 단위를 잃는다. 새 판의 계획과
            // 기대는 그 단위에 싣고 갱신은 접수를 본 뒤로 미룬다 — 기체에서 돌 것은 보내는 중인 리비전이다. 취소가 걸렸으면 그대로 둔다.
            UnitState.PENDING -> if (execution.command.inFlight?.unitId == unit.unitId) {
                val fresh = replanned[unit.unitId]
                if (fresh != null && !execution.cancelRequested) {
                    val sending = checkNotNull(execution.command.inFlight).revision
                    unit.revision = order.version
                    unit.parameters = fresh.parameters
                    unit.expectedIdentity = fresh.expectedIdentity
                    unit.source = fresh.source
                    unit.destination = fresh.destination
                    execution.command.deferredUpdate = execution.command.deferredUpdate ?: sending
                }
                unit
            } else if (execution.command.fleetInFlight?.unitId == unit.unitId) {
                // **맡기는 중인 운반 단위도 바꾸지 않는다**(§15.237). 플릿 계약에 갱신이 없다 — 도는 운반과 같은 규칙이다.
                unit
            } else if (execution.cancelRequested && execution.unattached[unit.unitId] == unit.attempt) {
                // **붙기 전 단위는 기체에서 돌고 있다**(§15.306 (e)). 취소가 걸렸으면 도는 단위와 같이 그대로 둔다(§15.256) — 리비전을
                // 올리면 붙기 전 취소의 핸들과 지연 이벤트의 판정이 기체와 어긋난다.
                unit
            } else {
                unit.replannedAs(replanned[unit.unitId]).also { it.revision = order.version }
            }
            // 도는 단위와 답을 못 받은 단위는 같은 길이다 — 새 버전을 보내는 것이 계약에서는 갱신이자 조회다(같은
            // task_id: 접수돼 있었으면 갱신, 아니었으면 새 접수. 어느 쪽이든 핸들이 돌아오면 이제 추적한다).
            UnitState.RUNNING, UnitState.IN_DOUBT -> {
                val fresh = replanned[unit.unitId]
                // 경로마다 가른다. 로봇 단위만 계약의 갱신 규칙을 탄다 — 플릿에 맡긴 운반은 아래 이유로, 설비 대기는
                // 같은 실행이 쥔 같은 정의의 같은 노드라 바꿀 것이 없어 그대로 둔다(기다림이 이어진다).
                // **취소가 걸렸으면 로봇 단위도 그대로 둔다**(§15.256). 멈추라는 말 뒤에 새 버전을 보내면 기체가 그 버전으로 다시
                // 움직일 수 있고(미룬 갱신과 같은 규칙), 원 요청이 안 닿은 미확정 단위면 갱신이 새 태스크로 접수된다. 리비전도
                // 올리지 않는다 — 기체에서 도는 것이 옛 리비전이라, 올리면 스냅숏 부착과 키로 짓는 취소 핸들이 기체와 어긋난다.
                val updatable = RouteDriver.of(unit.route).takesUpdate && !execution.cancelRequested
                if (fresh != null && updatable) {
                    val previous = unit.revision
                    unit.revision = order.version
                    // **기대도 새 버전의 것이다.** 옛 기대를 들고 있으면 옛 버전의 완료가 옛 기대에 맞아 새 버전의 완료로 적힌다.
                    unit.parameters = fresh.parameters
                    unit.expectedIdentity = fresh.expectedIdentity
                    unit.source = fresh.source
                    unit.destination = fresh.destination
                    // **갱신도 새 로봇 명령이다** — 같은 발행 관문을 지난다(ADR 51 결정 6). 막히면 단위의 리비전과 기대는
                    // 이미 새 판의 것으로 두고 보내기만 미룬다. 그래야 옛 리비전으로 끝난 결과가 지연 이벤트로 읽혀 새 판의
                    // 기대에 대고 확인된다 — 보내지 못한 갱신이 «옛 판대로 끝남» 을 새 판의 완료로 적게 하지 않는다.
                    val sending = execution.command.inFlight?.takeIf { it.unitId == unit.unitId }
                    if (sending != null) {
                        // **보내는 중인 출발이 있으면 갱신을 그 결과 뒤로 미룬다**(§15.237). 같은 기체에 명령 둘을 함께 내지 않는다.
                        // 결과가 접수면 기체에서 도는 것은 보내는 중인 리비전이고, 미룬 갱신이 다음 라운드에 이 리비전으로 나간다.
                        execution.command.deferredUpdate = execution.command.deferredUpdate ?: sending.revision
                    } else if (issuable(execution, unit, WithheldIssue.REVISION_UPDATE)) {
                        // 앞 판의 갱신이 미뤄져 있었으면 이 갱신이 그것까지 싣는다 — 기체에서 도는 것은 그 앞 판이다.
                        val running = execution.command.deferredUpdate ?: previous
                        execution.command.deferredUpdate = null
                        sendRevisionUpdate(execution, unit, running)
                    } else if (unit.state == UnitState.RUNNING) {
                        // 막힌 채 판이 또 오르면 기체에서 도는 것은 여전히 처음 리비전이다 — 그것을 지킨다.
                        execution.command.deferredUpdate = execution.command.deferredUpdate ?: previous
                    }
                    // `IN_DOUBT` 이면 따로 미룰 것이 없다. 다음 조회가 새 리비전으로 나가고, 그 조회도 같은 관문을 지난다.
                }
                // 플릿에 맡긴 운반은 도중에 바꾸지 않는다 — 플릿 계약에 갱신이 없다. 끝난 뒤 새 주문이다.
                unit
            }
        }
    }
    val added = replanned.values.filter { fresh -> kept.none { it.unitId == fresh.unitId } }
        .onEach { it.revision = order.version }
    val units = (kept + added).toMutableList()
    if (prefix.isNotEmpty()) {
        // **다시 계획하는 첫 단위 앞에 선다.** 보내는 중인 단위는 그 결과를 기다리므로 그 뒤다. 다시 계획할 단위가 없으면 끝에 선다.
        val replans = toPlan.map { it.unitId }.toSet() - setOfNotNull(execution.command.inFlight?.unitId, execution.command.fleetInFlight?.unitId)
        val at = units.indexOfFirst { it.unitId in replans }.takeIf { it >= 0 } ?: units.size
        prefix.forEach { it.revision = order.version }
        units.addAll(at, prefix)
        execution.approvedBy = approvedBy
    }
    execution.mutableUnits.clear()
    execution.mutableUnits.addAll(units)
    execution.order = order
    if (execution.physicalState == PhysicalState.PARTIAL || execution.physicalState == PhysicalState.UNVERIFIED ||
        execution.physicalState == PhysicalState.FAILED
    ) {
        execution.physicalState = PhysicalState.RUNNING
    }
    // 도는 단위에 보낸 갱신이 미확정을 내거나 풀었으면 받은 쪽(`Middleware`)이 지금 알린다 — 새 판과 새 단위 열이 선 뒤라 응답이 이 판의 것이다.
    return Submission.Accepted(execution)
}

/**
 * 도는 로봇 단위에 개정의 갱신을 보낸다 — 같은 태스크 id, 단위의 지금 리비전([ExecutionUnit.revision]). [revise] 와 미룬 갱신을
 * 허가가 돌아온 뒤 보내는 [pumpRound] 가 함께 쓴다. 부르기 전에 발행 관문을 지났어야 한다. 취소가 걸린 실행에는 둘 다 부르지
 * 않는다(§15.256).
 *
 * @param previous 기체에서 지금 도는 리비전. 거절 사유에 «어느 판으로 끝났나» 로 남는다.
 */
internal fun CommandEnv.sendRevisionUpdate(execution: Execution, unit: ExecutionUnit, previous: Int) {
    val flight = InFlight(
        InFlight.Kind.UPDATE, unit.unitId, execution.robotOf(unit), unit.taskId, unit.revision, unit.skillType,
        execution.command.withOptionals(unit, unit.parameters), previous = previous, restore = null,
    )
    execution.command.settleIssue(flight, issueStart(flight))
}

/**
 * 개정 갱신의 결과. **보내는 중에 걸린 취소는 이 결과 뒤에 보낸다**(§15.237) — 갱신이 닿았든 안 나갔든 기체에서 도는 태스크의 핸들은
 * 그대로다. 미확정이면 [resolveDoubt] 의 취소 길로 간다.
 */
internal fun CommandEnv.settleUpdate(execution: Execution, unit: ExecutionUnit, flight: InFlight, issue: Issue) {
    val revision = flight.revision
    val previous = flight.previous
    when (issue) {
        Issue.Pending -> return
        is Issue.Answered -> {
            val response = issue.response
            when {
                response.hasHandle() -> if (unit.state == UnitState.IN_DOUBT) {
                    execution.handle = response.handle
                    unit.state = UnitState.RUNNING
                    // 실행 상태도 함께 되돌린다 — 안 되돌리면 이 판의 작업 응답이 미확정 단위 없이 `IN_DOUBT` 를 든다.
                    execution.physicalState = PhysicalState.RUNNING
                    execution.noticed.clear(Notice.Doubt)
                    unit.annotate("IN_DOUBT resolved by revision $revision update")
                }

                // **이미 종착한 태스크다** — 갱신이 닿기 전에 끝났다(계약의 래치, §4.4). 그 종착은 옛 버전의
                // 것이고 pump 가 **지연 이벤트**로 받는다(15.1). 여기서 실패로 적으면 물리 결과를 잃는다.
                response.rejection.code == RejectionCode.REJECTION_CODE_INVALID_TRANSITION ->
                    unit.annotate("revision $revision update rejected: task already terminal under revision $previous")

                else -> {
                    unit.state = UnitState.FAILED
                    unit.failureClass = response.rejection.code.name
                }
            }
        }
        is Issue.NotSent -> {
            // **보내지 않았다**(§15.237). 기체에서 도는 것은 여전히 [previous] 다 — 미확정으로 두지 않고 갱신을 미룬다. 허가가 막은
            // 갱신과 같은 자리라 다음 라운드의 [pumpRound] 가 발행 게이트를 다시 지나 보낸다. 미확정 단위면 미룰 것이 없다 — 다음 조회가
            // 새 리비전으로 나간다. 보내는 중에 개정이 또 왔어도 기체에서 도는 것은 [previous] 다.
            if (unit.state == UnitState.RUNNING) execution.command.deferredUpdate = previous
            unit.annotateOnce(UPDATE_NOT_SENT)
        }
        is Issue.InDoubt -> {
            // 갱신의 답을 못 받았다 — 적용됐는지 모른다. 도는 중이었더라도 이제는 미확정이다. 미룰 것도 없다 — 다음 조회가 단위의
            // 지금 리비전으로 나간다.
            unit.state = UnitState.IN_DOUBT
            unit.requestedAt = issue.sentAt ?: now()
            unit.lookups = 0
            unit.lastLookupAt = null
            unit.annotate("revision $revision update unanswered")
            execution.physicalState = PhysicalState.IN_DOUBT
            execution.command.deferredUpdate = null
        }
    }
    if (execution.cancelRequested && unit.state == UnitState.RUNNING) execution.command.sendCancel()
}

/**
 * **발행 관문을 묻는다**(ADR 51 결정 6, ADR 52) — 이 실행의 기체에 [unit] 으로 향하는 새 로봇 명령([withheld])을 내도 되는가.
 *
 * 막히면 [Execution.permitBlock] 에 어느 기체가·무엇 때문에·무엇이 바뀌면 풀리는지를 두고 자취에 한 번 남긴다. 원천은 관측
 * 시각을 계속 새로 찍으므로 그 시각은 «같은 막음» 의 열쇠에서 뺀다. 통과하면 앞의 막음을 걷고 무엇이 허가했는지를 남긴다.
 *
 * 사건을 열지 않는다. 실패가 아니라 기다림이고, 운영자가 판단할 것이 없다.
 */
internal fun CommandEnv.issuable(execution: Execution, unit: ExecutionUnit, withheld: WithheldIssue): Boolean =
    when (val verdict = issuing.check(setOf(execution.robotOf(unit)))) {
        is IssueVerdict.Issue -> {
            execution.permitBlock?.let { lifted ->
                execution.permitBlock = null
                execution.trail(
                    ISSUE_RESUMED,
                    "${lifted.unitId} ${lifted.withheld.name}: " + verdict.basis.joinToString { p ->
                        "${p.robotId}=${p.permit.name} " + p.reasons.joinToString { "by ${it.source} at ${it.observedAt}" }
                    },
                )
            }
            true
        }

        is IssueVerdict.Withheld -> {
            val previous = execution.permitBlock
            val sameTarget = previous != null && previous.unitId == unit.unitId && previous.withheld == withheld
            val block = PermitBlock(unit.unitId, withheld, verdict.robots, since = if (sameTarget) previous!!.since else now())
            execution.permitBlock = block
            if (previous?.signature != block.signature) {
                execution.trail(
                    ISSUE_WITHHELD,
                    "${block.unitId} ${withheld.name}: " + block.robots.joinToString { r ->
                        "${r.robotId}=${r.permit.name} " + r.reasons.joinToString {
                            "${it.cause.name} source=${it.source} subject=${it.subject} resume=${it.resumeBasis}"
                        }
                    },
                )
            }
            false
        }
    }

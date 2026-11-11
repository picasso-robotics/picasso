package dev.picasso.middleware

import dev.picasso.contracts.v1.Reference
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.middleware.Middleware.Companion.DOUBT_ATTACHED
import dev.picasso.middleware.Middleware.Companion.DOUBT_REVISION_AHEAD
import dev.picasso.middleware.Middleware.Companion.LOOKUP_INTERVAL
import dev.picasso.middleware.Middleware.Companion.RESUME_REATTACHED
import dev.picasso.middleware.Middleware.Execution

// 미확정 해소: 보냈는지 모르는 명령을 조회와 스냅숏으로 확정하고, 다시 붙는다.
// 명령 생애 넷(LifecycleIssue, LifecycleDoubt, LifecycleCancelAttach, LifecycleCancel)은 서로를 양방향으로 부르는
// 하위 상태기계 하나다. 파일만 나눴을 뿐 모듈이 아니다. 상태(실행의 송신 기록, 취소 표시)는 Middleware.Execution 에 있다.

/**
 * `IN_DOUBT` 의 해소 — 보고서 13.2 의 순서에 스냅숏 부착을 앞에 더했다(§15.238).
 *
 * 0. **스냅숏 부착**(로봇 경로만) — 기체의 스냅숏에 이 단위의 태스크가 보이면 `StartTask` 없이 핸들을 지어 붙는다
 *    ([attachFromSnapshot]). 읽기뿐이라 발행 게이트를 묻지 않고, 조회가 막힌 동안에도 붙는다.
 *    **취소가 걸렸으면** 스냅숏으로 못 붙어도 태스크 id·리비전으로 핸들을 지어 취소를 보내고, 기체가 받으면 그 핸들로 붙는다
 *    ([cancelByKey], §15.219). 취소도 발행이 아니라서 허가를 묻지 않는다(ADR 52). 이때는 1 을 건너뛴다 — 멈추려는 태스크를 같은
 *    참조의 `StartTask` 로 다시 보내면 원 요청이 안 닿았던 기체가 그 일을 새로 시작한다.
 * 1. **하류의 클라이언트 참조 기반 조회** — 같은 참조로 다시 묻는다. 계약은 같은 핸들을 돌려주고 플릿은 같은 운반을
 *    돌려준다. 돌아오면 그 실행을 **이어서 추적**한다(새 실행이 아니다 — 17장 3번 *자동 재실행 없음*, 9번 재동기화는
 *    처음부터 되짚는 `WatchTask(0)` 이 한다). **횟수가 아니라 시간으로 잰다** — 미확정 유예([LogicalCapability.inDoubtGrace]) 안에서
 *    pump 마다 한 번 묻는다. 횟수로 재면 pump 주기가 짧을수록 짧은 끊김에도 예산이 바닥난다. 유예가 지나도 한 번은 묻는다 —
 *    허가가 막은 동안 유예가 다 가서 «하위가 답하지 않았다» 가 아닌 것이 그렇게 적히는 일이 없게.
 * 2. **물리 상태 관측** — 하류에 물을 수 없을 때 설비를 본다. 요청 시각부터 [LogicalCapability.inDoubtGrace] 안에
 *    기대한 것이 목적지에 나타나면 **잠정 완료**다(12.3 셋째 행). 확정은 조회가 하고, 조회가 없으면 운영자가 한다.
 * 3. **운영자** — `OPERATOR_HOLD`. 자동 재실행은 없다. 재요청은 사람이 [OperatorDecision.REWORK] 로 명시적으로 낸다(13.3).
 *
 * @return 단위가 이 펌프에서 종착(운영자 보류 포함)했는가.
 */
internal fun Middleware.resolveDoubt(execution: Execution, unit: ExecutionUnit): Boolean {
    // **보내는 중인 조회가 있으면 그 결과부터 받는다**(§15.237). 그동안 스냅숏 부착도 키 취소도 하지 않는다 — 조회의 답이 이 단위를
    // 붙이거나 거부로 끝낼 수 있다.
    execution.inFlight?.takeIf { it.kind == InFlight.Kind.LOOKUP && it.unitId == unit.unitId }?.let { flight ->
        val settled = settleIssue(execution, flight, issueStart(flight))
        if (execution.inFlight != null) execution.physicalState = PhysicalState.IN_DOUBT
        return settled
    }
    execution.fleetInFlight?.takeIf { it.kind == FleetFlight.Kind.LOOKUP && it.unitId == unit.unitId }?.let { flight ->
        val settled = settleDispatch(execution, flight, issueDispatch(flight.order))
        if (execution.fleetInFlight != null) execution.physicalState = PhysicalState.IN_DOUBT
        return settled
    }
    val lookup = lookupOf(unit.route)
    // ⓪ 스냅숏 부착 — 붙었으면 이 라운드는 여기까지다. 다음 라운드의 [pumpRobotUnit] 이 처음부터 되짚는다. 기체가 더 높은
    // 리비전을 들었으면 붙지도 묻지도 않고 운영자에게 간다.
    if (unit.route == Route.ROBOT && !execution.cancelRequested && holdRevisionAhead(execution, unit)) return true
    if (unit.route == Route.ROBOT && attachFromSnapshot(execution, unit)) return false
    // ⓪′ 키로 지은 핸들로 취소 — 받으면 붙는다. 기체가 모른다고 해도 쉬고 있다는 증거가 아니다 — 아래 관측과 운영자로 간다.
    val cancelling = unit.route == Route.ROBOT && execution.cancelRequested
    // 잠금 밖에서 보내는 중이면 그 답을 받을 때까지 미확정인 채 둔다 — 아래 관측이 먼저 종결하면 붙을 자리가 사라진다.
    if (cancelling && (cancelByKey(execution, unit) || execution.cancelInFlight != null)) return false

    val requestedAt = unit.requestedAt!!
    // 스트림을 잃은 단위는 그때부터 잰다 — 요청 시각부터 재면 긴 태스크는 잃은 순간 이미 유예 밖이다.
    val inGrace = now().isBefore((unit.streamLostAt ?: requestedAt).plus(inDoubtGraceOf(execution)))
    if (lookup == ExecutionLookup.CLIENT_REFERENCE && unit.streamLostAt == null && !cancelling && (inGrace || unit.lookups == 0)) {
        // 조회 사이에 간격을 둔다([LOOKUP_INTERVAL]). 간격 안이면 이 라운드는 묻지 않고 미확정인 채 기다린다.
        val last = unit.lastLookupAt
        if (last != null && now().isBefore(last.plus(LOOKUP_INTERVAL))) {
            execution.physicalState = PhysicalState.IN_DOUBT
            return false
        }
        // **로봇 경로의 조회는 같은 참조의 `StartTask` 재전송이다** — 원 요청이 안 닿았으면 새 접수가 되어 기체가 움직인다.
        // 그래서 새 발행과 같은 게이트를 지난다(ADR 52). 막히면 조회 횟수를 쓰지 않고 미확정인 채 기다린다 — 유예가 다 갔어도
        // 한 번도 못 물었으면 계속 기다린다. 안 그러면 허가가 막은 동안에 운영자 판단으로 넘어가,
        // «하류가 답하지 않았다» 가 아닌 것이 그렇게 적힌다.
        if (unit.route == Route.ROBOT && !issuable(execution, unit, WithheldIssue.IN_DOUBT_LOOKUP)) {
            execution.physicalState = PhysicalState.IN_DOUBT
            return false
        }
        unit.lookups += 1
        unit.lastLookupAt = now()
        if (unit.route == Route.ROBOT) {
            val flight = InFlight(
                InFlight.Kind.LOOKUP, unit.unitId, execution.robotOf(unit), unit.taskId, unit.revision, unit.skillType,
                withOptionals(execution, unit, unit.parameters), previous = unit.revision, restore = null,
            )
            val settled = settleIssue(execution, flight, issueStart(flight))
            if (execution.inFlight != null) execution.physicalState = PhysicalState.IN_DOUBT
            return settled
        }
        if (unit.route == Route.FLEET) {
            val flight = FleetFlight(FleetFlight.Kind.LOOKUP, unit.unitId, transportOrderOf(unit), restore = null)
            val settled = settleDispatch(execution, flight, issueDispatch(flight.order))
            if (execution.fleetInFlight != null) execution.physicalState = PhysicalState.IN_DOUBT
            return settled
        }
        val found = try {
            lookupDownstream(execution, unit)
        } catch (_: CommandNotSent) {
            return lookupNotSent(execution, unit)
        } catch (_: RuntimeException) {
            false
        }
        return lookupFound(execution, unit, found)
    }

    // ② 물리 관측 — 요청 시각부터의 신호만 이 요청의 것이다.
    val window = windowOf(execution)
    val signal = execution.observeCell(unit)
    val observedAt = signal?.observedAt ?: now()
    val provisional = signal != null && signal.occupied &&
        (unit.expectedIdentity == null || signal.identity == unit.expectedIdentity) &&
        !observedAt.isBefore(requestedAt.minus(window.before))
    if (!provisional && inGrace) {
        execution.physicalState = PhysicalState.IN_DOUBT
        return false
    }

    // ③ 운영자.
    val why = when {
        unit.streamLostAt != null -> "downstream forgot the task, lookup not re-sent"
        lookup == ExecutionLookup.NONE -> "downstream has no client-reference lookup"
        else -> "lookup unanswered within grace (${unit.lookups} time(s))"
    }
    val lost = if (unit.streamLostAt != null) "task stream closed" else "response lost"
    if (provisional) {
        unit.verification = Verification.MATCHED
        unit.evidenceAt = observedAt
        unit.annotate("IN_DOUBT: $lost; evidence present at ${unit.destination} (provisional done); $why — confirm")
    } else {
        unit.annotate("IN_DOUBT: $lost; no evidence at ${unit.destination} within grace; $why — no automatic re-execution")
    }
    unit.state = UnitState.OPERATOR_HOLD
    execution.notedDoubt = null
    // **취소가 걸렸는데 핸들이 없다** — 취소가 하위에 닿지 못했다. 이 단위를 든 채 실행을 중단으로 닫으면 기체가 응답을 잃은 그
    // 태스크를 하는 동안 실행이 기체를 내준다. 운영자 판단까지 보류에 둔다(§15.92, §15.238).
    if (execution.cancelRequested && execution.handle == null && execution.transport == null) holdUnsentCancel(execution, unit)
    execution.markIncident(unit)
    return true
}

/**
 * ⓪ 스냅숏 부착(§15.238) — 기체의 스냅숏에 이 단위의 지금 시도의 태스크가 있으면 `StartTask` 를 다시 보내지 않고 그 태스크의
 * 핸들(태스크 id·리비전·기체)을 지어 붙는다. 단위는 도는 중이 되고, 취소가 걸려 있으면 그 핸들로 바로 보낸다. 부착한 라운드의
 * 상태 읽기는 다음 라운드의 [pumpRobotUnit] 이 `WatchTask(0)` 으로 처음부터 되짚는다 — 이미 끝난 태스크도 그 종료를 읽는다.
 *
 * - **읽기뿐이라 발행 게이트([issuable])를 묻지 않는다.** 붙는 데 새 로봇 명령이 없다([attachForCancel] 과 같은 이유).
 * - **리비전이 단위의 것과 같아야 한다.** 개정 갱신의 미확정은 태스크가 원래 있고, 기체가 아직 옛 리비전이면 갱신이 안 닿은 것이라
 *   붙지 않고 조회가 갱신을 다시 보낸다. 더 높으면 [holdRevisionAhead] 가 먼저 운영자에게 보낸다.
 * - **포트가 이 태스크의 리비전을 안 실으면** 이 태스크를 한 리비전으로만 보낸 단위([ExecutionUnit.startedRevision])만 붙는다 — 보이는
 *   태스크가 곧 그 리비전이다. 갱신을 보냈거나 재기동 뒤라 모르면 붙지 않고 조회가 맡는다.
 * - **스냅숏을 못 읽었으면 붙지 않는다.** 못 읽은 것은 태스크가 없다는 관측이 아니다. 조회가 맡는다.
 *
 * @return 붙었는가.
 */
internal fun Middleware.attachFromSnapshot(execution: Execution, unit: ExecutionUnit): Boolean {
    val robot = execution.robotOf(unit)
    val view = views[robot]?.takeIf { it.observable } ?: return false
    val state = view.tasks[unit.taskId] ?: return false
    val held = view.taskRevisions[unit.taskId]
    if (held == null && unit.startedRevision != unit.revision) return false
    if (held != null && held != unit.revision) return false

    execution.handle = handleFor(robot, unit.taskId, unit.revision)
    unit.state = UnitState.RUNNING
    unit.streamLostAt = null
    execution.notedDoubt = null
    unit.annotate("IN_DOUBT resolved by snapshot attach: ${unit.taskId}=${state.name} revision ${unit.revision}")
    execution.trail(DOUBT_ATTACHED, "${unit.unitId}: 스냅숏의 ${unit.taskId}=${state.name}(리비전 ${unit.revision})에 StartTask 없이 붙었다")
    execution.physicalState = if (execution.cancelRequested) PhysicalState.CANCELING else PhysicalState.RUNNING
    if (execution.cancelRequested) sendCancel(execution)
    return true
}

/**
 * 기체의 스냅숏이 이 단위의 태스크를 **단위보다 높은 리비전으로** 드는가(§15.238). 들면 이 인스턴스가 보낸 적 없는 개정이 기체에
 * 닿은 것이다 — 다른 명령자가 그 태스크를 고쳤다. 붙으면 계획하지 않은 파라미터를 따라가고, 조회(낮은 리비전의 `StartTask` 재전송)는
 * `OUTDATED_REVISION` 으로 단위를 실패로 적어 기체가 그 태스크를 도는 동안 실행이 다음 단위를 낸다. 그래서 붙지도 묻지도 않고 단위를
 * 운영자 보류에 둔다. 취소가 걸린 실행은 이 길을 타지 않는다 — 키로 지은 핸들의 취소가 맡는다.
 *
 * @return 운영자 보류에 두었는가.
 */
internal fun Middleware.holdRevisionAhead(execution: Execution, unit: ExecutionUnit): Boolean {
    val view = views[execution.robotOf(unit)]?.takeIf { it.observable } ?: return false
    if (view.tasks[unit.taskId] == null) return false
    val held = view.taskRevisions[unit.taskId]?.takeIf { it > unit.revision } ?: return false
    unit.annotate("IN_DOUBT: robot holds ${unit.taskId} at revision $held above unit revision ${unit.revision}; not attached, no lookup — confirm")
    unit.state = UnitState.OPERATOR_HOLD
    execution.notedDoubt = null
    execution.trail(DOUBT_REVISION_AHEAD, "${unit.unitId}: 기체가 ${unit.taskId} 를 단위 리비전 ${unit.revision} 보다 높은 리비전 $held 로 든다 — 붙지 않고 운영자 판단을 기다린다")
    execution.markIncident(unit)
    return true
}

/** 스냅숏으로 짓는 태스크 핸들 — [attachForCancel] 과 [attachFromSnapshot] 이 같은 값을 짓는다. */
internal fun Middleware.handleFor(robot: String, taskId: String, revision: Int): TaskHandle =
    TaskHandle.newBuilder().setTaskId(taskId).setRevision(revision).setRobotId(robot).build()

/**
 * 미확정 조회의 결과([resolveDoubt]). 찾지 못했으면 보내는 중에 미룬 갱신을 걷는다 — 다음 조회가 단위의 지금 리비전으로 나간다.
 *
 * @return 단위가 이 라운드에 종료했는가.
 */
internal fun Middleware.settleLookup(execution: Execution, unit: ExecutionUnit, issue: Issue): Boolean {
    val found = when (issue) {
        Issue.Pending -> return false
        is Issue.NotSent -> return lookupNotSent(execution, unit)
        is Issue.InDoubt -> false
        is Issue.Answered -> applyStartResponse(execution, unit, issue.response)
    }
    if (!found) execution.deferredUpdate = null
    return lookupFound(execution, unit, found)
}

/**
 * **조회가 나가지 않았다**(§15.237) — 허가가 막은 조회처럼 횟수를 쓰지 않는다. 안 그러면 한 번도 못 물은 채 유예가 다 가서
 * «하류가 답하지 않았다» 가 아닌 것이 그렇게 적힌다. 간격은 쓴다 — 다음 시도는 [LOOKUP_INTERVAL] 뒤다.
 */
internal fun Middleware.lookupNotSent(execution: Execution, unit: ExecutionUnit): Boolean {
    unit.lookups -= 1
    execution.physicalState = PhysicalState.IN_DOUBT
    return false
}

/** 조회가 기존 실행을 찾았으면 이어서 추적하고, 하류가 거부했으면 종료로, 아니면 미확정인 채 둔다. */
internal fun Middleware.lookupFound(execution: Execution, unit: ExecutionUnit, found: Boolean): Boolean {
    if (found) {
        unit.state = UnitState.RUNNING
        // 풀린 것은 라운드 끝의 대조가 알린다([noteAttention]). 표시를 걷어야 재작업한 같은 단위가 다시 미확정이 될 때 알린다.
        execution.notedDoubt = null
        unit.annotate("IN_DOUBT resolved by client-reference lookup after ${unit.lookups} lookup(s)")
        execution.physicalState = if (execution.cancelRequested) PhysicalState.CANCELING else PhysicalState.RUNNING
        if (execution.cancelRequested) sendCancel(execution)
        return false
    }
    if (unit.state == UnitState.FAILED) { // 하류가 이 요청을 거절했다 — 미실행이 확인된 것이고 그 사유는 우리 어휘다
        execution.notedDoubt = null
        return true
    }
    execution.physicalState = PhysicalState.IN_DOUBT
    return false
}

/** [lookupDownstream] 의 로봇 경로가 받은 답을 적는다 — 핸들이면 잡고 참, 거부면 단위를 실패나 운영자 보류로 적고 거짓. */
internal fun Middleware.applyStartResponse(execution: Execution, unit: ExecutionUnit, response: StartTaskResponse): Boolean =
    if (response.hasHandle()) {
        execution.handle = response.handle
        true
    } else {
        val rejection = response.rejection
        if (rejection.code == RejectionCode.REJECTION_CODE_PRECONDITION_UNMET) {
            // 발신자가 사전 조건으로 거절했다 — 요청이 틀린 것이 아니라 **지금 못 받는** 것이다. 계약은 «기다리거나
            // 앞 단위를 바꾼다» 고 했고, 그 판단은 밖(운영자)에 있다. FAILED 로 접어 다음 단위로 넘어가면 그 판단이
            // 실행되지 않는다(리뷰 C10). 사유와 주어를 단위에 남긴다 — 응답만 보고 무엇이 걸렸는지 알 수 있게.
            unit.state = UnitState.OPERATOR_HOLD
            unit.failureClass = rejection.code.name
            unit.preconditionSubjects = rejection.referencesList
                .filter { it.key == Reference.Key.KEY_PRECONDITION_SUBJECT }.map { it.value }
            unit.note = "sender refused: ${rejection.detail} [subject=${unit.preconditionSubjects.joinToString()}]"
        } else {
            unit.state = UnitState.FAILED
            unit.failureClass = rejection.code.takeIf { it != RejectionCode.REJECTION_CODE_UNSPECIFIED }?.name ?: "REJECTED"
        }
        execution.markIncident(unit)
        false
    }

/** 13.2 ① — 같은 참조로 다시 묻는다. 돌아오면 핸들을 잡고 참, 거절이면 단위를 실패로 적고 거짓, 답이 없으면 던진다. */
internal fun Middleware.lookupDownstream(execution: Execution, unit: ExecutionUnit): Boolean = when (unit.route) {
    Route.ROBOT -> applyStartResponse(
        execution, unit,
        robots.start(execution.robotOf(unit), unit.taskId, unit.revision, unit.skillType, withOptionals(execution, unit, unit.parameters)),
    )

    Route.FLEET -> acceptTransport(execution, unit, fleet.dispatch(transportOrderOf(unit)))

    // 설비 대기는 하위에 보낼 요청이 없다 — 시작이 곧 기다림의 시작이고, 읽기는 [pumpSignalUnit] 이 한다.
    Route.SIGNAL -> true
}

/**
 * 경로마다 하위가 클라이언트 참조로 기존 실행을 찾아 주는가(13.2 ①).
 *
 * 설비 대기는 요청을 보내지 않으므로 미확정이 생기지 않는다 — 실행의 미확정 자동 해소를 막지 않는 쪽으로 응답한다.
 */
internal fun Middleware.lookupOf(route: Route): ExecutionLookup = when (route) {
    Route.ROBOT -> robots.executionLookup
    Route.FLEET -> fleet.executionLookup
    Route.SIGNAL -> ExecutionLookup.CLIENT_REFERENCE
}

/**
 * 다시 지은 실행([resume])의 붙지 않은 [unit] 을 스냅숏의 태스크에 `StartTask` 없이 붙인다(§15.219). 스냅숏에 이 단위의 지금 시도의
 * 태스크가 단위의 리비전으로 있어야 한다 — 기체가 그 태스크를 들고 있다는 관측이고, 붙는 데 새 로봇 명령이 없다. 그래서 발행 게이트를
 * 묻지 않는다. 이미 끝난 태스크도 붙고 그 종료는 다음 라운드의 [pumpRobotUnit] 이 `WatchTask(0)` 으로 읽는다.
 *
 * 스냅숏을 못 읽었거나, 그 태스크가 없거나, 리비전이 다르거나 비어 있으면 붙지 않는다. 그때는 지금처럼 같은 태스크 id 의 재전송이
 * 게이트를 지난다 — 리비전이 다르면 그 재전송은 개정 갱신이라 새 명령이다.
 *
 * @return 붙었는가.
 */
internal fun Middleware.reattachFromSnapshot(execution: Execution, unit: ExecutionUnit): Boolean {
    if (execution.unattached[unit.unitId] != unit.attempt) return false
    val robot = execution.robotOf(unit)
    val view = views[robot]?.takeIf { it.observable } ?: return false
    val taskId = taskIdOf(execution, unit)
    val state = view.tasks[taskId] ?: return false
    if (view.taskRevisions[taskId] != unit.revision) return false

    unit.taskId = taskId
    unit.requestedAt = requestedAtOf(execution, unit)
    unit.lookups = 0
    unit.lastLookupAt = null
    unit.streamLostAt = null
    unit.keyedCancelAt = null
    execution.unattached.remove(unit.unitId)
    execution.active = unit
    execution.handle = handleFor(robot, taskId, unit.revision)
    unit.state = UnitState.RUNNING
    execution.physicalState = PhysicalState.RUNNING
    execution.trail(RESUME_REATTACHED, "${unit.unitId}: 스냅숏의 $taskId=${state.name}(리비전 ${unit.revision})에 StartTask 없이 붙었다")
    return true
}

package dev.picasso.middleware

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.middleware.Middleware.Companion.CANCEL_ATTACHED
import dev.picasso.middleware.Middleware.Companion.CANCEL_RESENT
import dev.picasso.middleware.Middleware.Companion.CANCEL_UNATTACHED_WAIT
import dev.picasso.middleware.Middleware.Companion.CANCEL_UNSENT
import dev.picasso.middleware.Middleware.Companion.DOUBT_ATTACHED
import dev.picasso.middleware.Middleware.Companion.HOLD_UNSENT_CAUSE
import dev.picasso.middleware.Middleware.Companion.KEYED_UNSENT_CAUSE
import dev.picasso.middleware.Middleware.Companion.LINK_UNSENT_CAUSE
import dev.picasso.middleware.Middleware.Companion.LOOKUP_INTERVAL
import dev.picasso.middleware.Middleware.Companion.LOOKUP_UNSENT_CAUSE
import dev.picasso.middleware.Middleware.Companion.TRANSPORT_ENDED
import dev.picasso.middleware.Middleware.Companion.UNATTACHED_UNSENT_CAUSE
import dev.picasso.middleware.Middleware.Execution

// 취소용 붙이기: 핸들 없는 취소, 키로 보낸 취소, 붙기 전에 건 취소와 그 기다림.
// 명령 생애 넷(LifecycleIssue, LifecycleDoubt, LifecycleCancelAttach, LifecycleCancel)은 서로를 양방향으로 부르는
// 하위 상태기계 하나다. 파일만 나눴을 뿐 모듈이 아니다. 상태(실행의 송신 기록, 취소 표시)는 Middleware.Execution 에 있다.

/**
 * 취소가 걸린 채 미확정이 안 풀린 [unit] 의 취소를 «보내지 못한 취소» 로 표시한다([Execution.cancelUnsent]). 실행은 단위가 운영자
 * 보류인 동안 중단으로 닫히지 않는다([cancelWaitsOnHeldDoubt]). 운영자가 판단하면 취소는 보통 경로로 끝나고 취소 응답에
 * [CANCEL_NOT_SENT] 가 실린다.
 */
internal fun Middleware.holdUnsentCancel(execution: Execution, unit: ExecutionUnit) {
    val keyed = unit.route == Route.ROBOT
    val cause = if (keyed) KEYED_UNSENT_CAUSE else HOLD_UNSENT_CAUSE
    execution.cancelDelivery = execution.cancelDelivery.unsent(cause)
    execution.cancelWait = CancelWait.Held(unit.unitId)
    unit.annotate("cancel requested but never reached the downstream: no task handle" + if (keyed) ", keyed cancel not accepted" else "")
    execution.trail(CANCEL_UNSENT, "${unit.unitId}: 취소를 하위에 보내지 못했다 ($cause) — 운영자 판단을 기다린다")
}

/**
 * ⓪′ 키로 지은 핸들로 취소(§15.219) — 취소가 걸린 미확정 단위에 스냅숏으로 못 붙었을 때, 태스크 id·리비전으로 핸들을 지어
 * `CancelTask` 를 보낸다. 기체가 상태로 답하면 그 태스크가 있는 것이고 그 핸들로 붙는다. 단위는 도는 중이 되고 다음 라운드의
 * [pumpRobotUnit] 이 `WatchTask(0)` 으로 처음부터 되짚어 취소의 끝을 읽는다. 그 스킬이 취소를 안 들면(`CANCEL_UNSUPPORTED`) 그것도
 * 기체가 그 태스크를 안다는 답이다 — 거부를 싣고 붙어 끝까지 따라간다.
 *
 * - **허가를 묻지 않는다.** 취소는 새 발행이 아니다(ADR 52). 허가가 조회를 막은 동안에도 나간다.
 * - **간격을 둔다**([LOOKUP_INTERVAL], [ExecutionUnit.keyedCancelAt]). 간격 안이면 보내지 않는다.
 * - **던지거나 다른 거부면 붙지 않는다.** 기체가 그 태스크를 모른다는 답(`NOT_FOUND`)은 기체가 쉬고 있다는 증거가 아니다 — 원 요청이
 *   아직 오는 중일 수 있다. 부른 쪽이 설비 관측과 운영자 보류로 간다.
 * - **잠금 밖에서 보내면 이 라운드에는 붙지 않는다**(§15.237). 답은 다음 라운드에 [settleCancel] 이 받고, 단위가 그때도 같은
 *   리비전으로 미확정이면 붙는다.
 *
 * @return 붙었는가.
 */
internal fun Middleware.cancelByKey(execution: Execution, unit: ExecutionUnit): Boolean {
    val last = unit.keyedCancelAt
    if (last != null && now().isBefore(last.plus(LOOKUP_INTERVAL))) return false
    unit.keyedCancelAt = now()
    val robot = execution.robotOf(unit)
    val handle = handleFor(robot, unit.taskId, unit.revision)
    val answered = cancelRobot(execution, CancelFlight(CancelFlight.Site.KEY, robot, handle, unit.unitId)) as? CancelIssue.Answered
        ?: return false
    return attachByKey(execution, unit, handle, answered.response)
}

/** [cancelByKey] 가 답을 받은 뒤. 잠금 밖에서 보냈으면 다음 라운드에 [settleCancel] 이 부른다. @return 붙었는가. */
internal fun Middleware.attachByKey(execution: Execution, unit: ExecutionUnit, handle: TaskHandle, response: CancelTaskResponse): Boolean {
    val refusal = response.takeUnless { it.hasState() }?.rejection?.code
    if (refusal != null && refusal != RejectionCode.REJECTION_CODE_CANCEL_UNSUPPORTED) return false

    execution.cancelIntent = refusal?.let { CancelIntent.Refused(it.name) } ?: CancelIntent.Requested
    execution.handle = handle
    unit.state = UnitState.RUNNING
    unit.streamLostAt = null
    execution.notedDoubt = null
    unit.annotate("IN_DOUBT resolved by keyed cancel: ${unit.taskId} revision ${unit.revision}")
    execution.trail(DOUBT_ATTACHED, "${unit.unitId}: 키로 지은 핸들(${unit.taskId}, 리비전 ${unit.revision})로 보낸 취소를 기체가 받아 StartTask 없이 붙었다")
    execution.physicalState = PhysicalState.CANCELING
    return true
}

/**
 * 운영자 보류에 둔 취소([holdUnsentCancel])를 다시 보낸다(§15.238). 기체의 스냅숏에 그 단위의 태스크가 같은 리비전으로 아직 끝나지 않은
 * 채 보이면 키로 지은 핸들로 `CancelTask` 를 보낸다. **보류는 그대로다** — 단위의 결과는 여전히 운영자가 판단하고 실행은 그 판단을
 * 기다린다. 기체가 답하면(상태든 거부든) [CANCEL_RESENT] 를 남기고 [Execution.cancelUnsent] 를 내린다 — 취소 응답이 닿지 못했다고
 * 말하지 않는다. 던지면 간격([LOOKUP_INTERVAL]) 뒤에 스냅숏부터 다시 본다. 스냅숏을 못 읽었거나 태스크가 끝났으면 보내지 않는다.
 */
internal fun Middleware.resendHeldCancel(execution: Execution, unit: ExecutionUnit) {
    if (unit.route != Route.ROBOT) return
    val robot = execution.robotOf(unit)
    val view = views[robot]?.takeIf { it.observable } ?: return
    val state = view.tasks[unit.taskId] ?: return
    if (state.endedOnRobot() || view.taskRevisions[unit.taskId] != unit.revision) return
    val last = unit.keyedCancelAt
    if (last != null && now().isBefore(last.plus(LOOKUP_INTERVAL))) return
    unit.keyedCancelAt = now()
    val flight = CancelFlight(CancelFlight.Site.HELD, robot, handleFor(robot, unit.taskId, unit.revision), unit.unitId, state.name)
    val answered = cancelRobot(execution, flight) as? CancelIssue.Answered ?: return
    heldCancelAnswered(execution, unit, state.name, answered.response)
}

/** [resendHeldCancel] 이 답을 받은 뒤. 잠금 밖에서 보냈으면 다음 라운드에 [settleCancel] 이 부른다. */
internal fun Middleware.heldCancelAnswered(execution: Execution, unit: ExecutionUnit, stateName: String, response: CancelTaskResponse) {
    noteRefusal(execution, response)
    execution.cancelDelivery = CancelDelivery.Clear
    unit.annotate("held cancel re-sent by key: snapshot showed ${unit.taskId}=$stateName")
    execution.trail(CANCEL_RESENT, "${unit.unitId}: 운영자 보류 중 스냅숏에 ${unit.taskId}=$stateName 가 보여 키로 지은 핸들로 취소를 보냈다 — 판단은 운영자가 한다")
}

/**
 * 연결이 끊긴 기체에 건 취소가 미확정 유예 안에 닿지 않으면 도는 [unit] 을 운영자 보류에 둔다(§15.243). 기체가 멈췄는지 확인할 수 없어
 * 기체를 내주지 않는 쪽이다 — 실행은 중단으로 닫지 않고 운영자 판단을 기다린다([cancelWaitsOnHeldDoubt]). 보류 중에 스냅숏에 그 태스크가
 * 보이면 [resendHeldCancel] 이 다시 보낸다. 유예 안에 연결이 돌아오거나 취소가 닿으면 [pumpRound] 머리의 다시 보내기가 그대로 끝낸다.
 *
 * @return 단위가 이 라운드에 운영자 보류가 됐는가.
 */
internal fun Middleware.holdUnreachedCancel(execution: Execution, unit: ExecutionUnit): Boolean {
    val delivery = when (val d = execution.cancelDelivery) {
        is CancelDelivery.Unsent -> d.takeIf { execution.cancelRequested && execution.linkBroken }
            ?: run {
                // 끊김이 풀렸거나 취소가 걷혔다 — 끊김 시각을 걷는다.
                execution.cancelDelivery = d.copy(linkSince = null)
                return false
            }
        CancelDelivery.Clear -> return false
    }
    val since = delivery.linkSince ?: now().also { execution.cancelDelivery = delivery.copy(linkSince = it) }
    if (now().isBefore(since.plus(inDoubtGraceOf(execution)))) return false
    unit.annotate("cancel requested but never reached the downstream: link broken since $since, not answered within grace")
    unit.state = UnitState.OPERATOR_HOLD
    execution.cancelDelivery = CancelDelivery.Unsent(LINK_UNSENT_CAUSE)
    execution.cancelWait = CancelWait.Held(unit.unitId)
    execution.trail(CANCEL_UNSENT, "${unit.unitId}: 취소를 하위에 보내지 못했다 ($LINK_UNSENT_CAUSE) — 운영자 판단을 기다린다")
    execution.markIncident(unit)
    return true
}

/** 걸린 취소가 [holdUnsentCancel] 로 서 있고 그 단위가 아직 운영자 보류다 — 실행은 운영자 판단을 기다린다. */
internal fun Middleware.cancelWaitsOnHeldDoubt(execution: Execution): Boolean {
    return when (val wait = execution.cancelWait) {
        is CancelWait.Held -> execution.units.any { it.unitId == wait.unitId && it.state == UnitState.OPERATOR_HOLD }
        is CancelWait.Attaching, CancelWait.None -> false
    }
}

/**
 * **하위가 스스로 낸 취소** — 이 층이 [cancel] 을 걸지 않았는데 하위가 단위를 취소로 끝냈다. 현장의 사람이 기체·플릿 쪽에서
 * 멈췄거나, 이 층이 취소를 건 뒤 다시 떠서([resume]) 그 요청을 모르는 경우다(판단 기록 없이, 또는 건 취소 칸이 없는 버전 1
 * 기록으로 다시 지었다, §15.253). 둘을 가르지 않는다 — 다시 뜬 쪽은 누가 멈췄는지 모르고, 모르면 진행하지 않는다.
 *
 * **다음 단위로 가지 않는다.** 단위를 운영자 보류로 세우고 인시던트를 연다. 중단(`ABORTED`)으로 적으면 실행이 취소를 안 받았으므로
 * 구동이 다음 단위를 찾아 내고, 사람이 세운 기체가 다시 움직인다. 닫는 길은 보통의 보류와 같다 — [resolve] 의 재작업·확인, 또는
 * [cancel] 로 실행을 끝낸다. 알리는 것은 보류의 자리([pumpRound])다.
 *
 * **분류를 지어내지 않는다.** 하위가 결함을 실었으면 그 정준 분류, 아니면 비워 두고 하위가 말한 것을 메모에 남긴다.
 */
internal fun Middleware.holdUnrequestedCancel(execution: Execution, unit: ExecutionUnit, detail: String, fault: Fault? = null) {
    unit.failureClass = fault?.let { canonicalClassOf(it) }
    unit.fault = fault
    unit.annotate("$detail without a cancel request from this layer — not proceeding; operator decides")
    unit.state = UnitState.OPERATOR_HOLD
    execution.markIncident(unit)
}

/**
 * **붙기 전에 걸린 취소**(§15.231) — 다시 지은 실행([resume])에 취소가 걸렸는데 도는 단위가 없다. 기체에 이 실행의 태스크가 아직
 * 붙지 않은 채([Execution.unattached]) 있으면 그것에 붙어 멈추고, 정착은 그 태스크의 종료를 본 [pumpRobotUnit] 이 한다.
 *
 * 규칙 다섯이다.
 *
 * 1. **`StartTask` 로 붙지 않는다.** 스냅숏에서 이 단위의 지금 시도의 태스크 id 를 찾고, 핸들(태스크 id·리비전·기체)을 그 값으로
 *    짓는다. 같은 참조의 재전송은 그 사이 태스크가 끝났고 하위가 끝난 태스크를 안 들면 새 접수가 되어 기체가 움직인다 — 멈추려고
 *    붙는 자리에서 일을 새로 낸다.
 * 2. **기체가 아직 하는 태스크면 취소를 보낸다.** 계약의 종착(성공·실패·취소 둘)이 아니면 하는 중이다 — 사람을 기다리는 태스크도
 *    기체가 붙들고 있으므로 취소를 보낸다. 하위가 취소를 거절하면 [cancel] 과 같이 그 사유를 취소 응답에 싣고 단위는 끝까지 간다.
 *    답이 없으면 붙지 않고 다음 라운드에 스냅숏부터 다시 본다.
 * 3. **이미 끝난 태스크면 보내지 않고 붙기만 한다.** 그 종료로 단위를 판정하는 것은 보통의 정착 규칙이다 — 취소로 끝났으면 요청한
 *    취소의 중단, 성공이면 완료(중단점은 그 뒤), 실패면 실패.
 * 4. **스냅숏을 못 읽으면 정착하지 않는다.** 실행은 `CANCELING` 인 채 다음 라운드에 다시 본다. 못 읽은 것은 태스크가 없다는 관측이
 *    아니다 — 그것을 «지울 것 없음» 으로 읽으면 기체가 일하는 동안 실행이 기체를 내준다. 기다리는 까닭은 자취에
 *    [CANCEL_UNATTACHED_WAIT] 로 한 번 남긴다. 미확정 유예가 지나도 못 읽으면 그 단위를 운영자 보류에 둔다([waitUnattached]).
 * 5. **스냅숏에 그 태스크가 없으면 대상이 아니다.** 붙을 것이 하나도 없으면 거짓을 돌려주고 부르는 쪽이 지금처럼 «지울 것 없음» 으로
 *    정착한다.
 *
 * 하는 중인 태스크가 있으면 그것을, 아니면 끝난 것 가운데 계획의 마지막 것을 고른다. 실행은 단위를 차례로 돌므로 하는 중인 것은
 * 많아야 하나다.
 *
 * **발행 관문([issuable])을 묻지 않는다.** 여기서 나가는 것은 스냅숏 읽기·취소·관측뿐이고 새 로봇 명령이 없다. 작업 허가는 새
 * 발행만 막고 취소·관측은 막지 않는다([WorkPermitPort], ADR 52). 취소는 기체를 멈추는 쪽이라, 막으면 허가가 없는 동안 실행이 붙지
 * 않은 채 서 있거나 기체를 내주고 둘 다 기체는 그 일을 계속 한다.
 *
 * @return 붙었거나 기다린다 — 이번 라운드에 정착하지 않는다. 거짓이면 기체에 붙을 이 실행의 태스크가 없다.
 */
internal fun Middleware.attachForCancel(execution: Execution): Boolean {
    val candidates = execution.units.filter {
        it.route == Route.ROBOT && it.state == UnitState.PENDING && execution.unattached[it.unitId] == it.attempt
    }
    if (candidates.isEmpty()) return false

    val onRobot = mutableListOf<Pair<ExecutionUnit, TaskState>>()
    for (unit in candidates) {
        val robot = execution.robotOf(unit)
        val view = views[robot]
        if (view == null || !view.observable) {
            return waitUnattached(execution, unit, "robot=$robot 스냅숏을 못 읽었다 — ${unit.unitId} 의 태스크를 볼 때까지 종결하지 않는다")
        }
        val state = view.tasks[taskIdOf(execution, unit)]
        if (state == null) execution.unattached.remove(unit.unitId) else onRobot += unit to state
    }
    val (unit, state) = onRobot.firstOrNull { !it.second.endedOnRobot() } ?: onRobot.lastOrNull() ?: return false

    val robot = execution.robotOf(unit)
    val taskId = taskIdOf(execution, unit)
    val handle = handleFor(robot, taskId, unit.revision)
    val sent = !state.endedOnRobot()
    if (sent) {
        when (val result = cancelRobot(execution, CancelFlight(CancelFlight.Site.ATTACH, robot, handle, unit.unitId, state.name))) {
            CancelIssue.Pending -> return waitUnattached(execution, unit, "$taskId 의 취소를 보내는 중이다 — 다음 라운드에 답을 받는다")
            is CancelIssue.Failed -> return waitUnattached(execution, unit, unansweredAttach(taskId))
            is CancelIssue.Answered -> noteRefusal(execution, result.response)
        }
    }
    attachUnit(execution, unit, taskId, handle, state.name, sent)
    return true
}

internal fun Middleware.unansweredAttach(taskId: String) = "$taskId 의 취소에 답이 없다 — 다음 라운드에 스냅숏부터 다시 본다"

/** [attachForCancel] 이 붙는다. 잠금 밖에서 취소를 보냈으면 다음 라운드에 [settleCancel] 이 부른다. */
internal fun Middleware.attachUnit(execution: Execution, unit: ExecutionUnit, taskId: String, handle: TaskHandle, stateName: String, sent: Boolean) {
    unit.taskId = taskId
    unit.requestedAt = requestedAtOf(execution, unit)
    unit.lookups = 0
    unit.lastLookupAt = null
    unit.keyedCancelAt = null
    unit.state = UnitState.RUNNING
    execution.unattached.remove(unit.unitId)
    execution.cancelWait = execution.cancelWait.attached()
    execution.active = unit
    execution.handle = handle
    execution.physicalState = PhysicalState.CANCELING
    execution.trail(
        CANCEL_ATTACHED,
        "${unit.unitId}: 스냅숏의 $taskId=$stateName 에 StartTask 없이 붙었다, " +
            if (sent) "취소를 보냈다" else "이미 끝나 그 종료로 판정한다",
    )
}

/**
 * **붙기 전에 걸린 취소의 플릿 쪽**(§15.254) — 다시 지은 실행([resume])에 취소가 걸렸는데 도는 단위가 없고, 대기 중인 플릿 운반
 * 단위가 재기동 전에 플릿에 맡겨졌을 수 있다. 다시 짓기는 플릿을 읽지 않으므로([rebuildFrom] 은 로봇 단위만 본다) 여기서 찾는다.
 *
 * 규칙 다섯이다.
 *
 * 1. **[AmrFleetPort.find] 로 찾기만 한다.** 같은 참조의 [AmrFleetPort.dispatch] 재전송은 플릿이 그 운반을 모르면 새로 맡긴다 —
 *    멈추려고 붙는 자리에서 운반을 새로 낸다([attachForCancel] 의 1 과 같은 이유).
 * 2. **찾으면 붙는다.** 끝나지 않은 운반이면 취소를 보내고([sendCancel]), 끝났으면 보내지 않는다. 그 종료로 단위를 판정하는 것은
 *    다음 라운드의 [pumpFleetUnit] 이다.
 * 3. **답이 없으면 정착하지 않고** 다음 라운드에 다시 찾는다. 까닭은 자취에 [CANCEL_UNATTACHED_WAIT] 로 한 번 남긴다. 미확정 유예가
 *    지나도 답이 없으면 그 단위를 운영자 보류에 둔다([waitUnattached]).
 * 4. **플릿에 조회가 없으면**([ExecutionLookup.NONE]) 판단 기록이 지금 시도를 맡겼다고 적은 단위를 운영자 보류에 둔다. 맡긴 운반이
 *    도는지 볼 길이 없다 — «지울 것 없음» 으로 정착하면 운반이 도는 동안 실행이 끝난다. 기록이 맡겼다고 적지 않은 단위는 대상이 아니다.
 * 5. **찾았는데 없으면 대상이 아니다.** 붙을 것이 하나도 없으면 거짓을 돌려주고 부르는 쪽이 «지울 것 없음» 으로 정착한다.
 *
 * 실행은 단위를 차례로 돌므로 맡겨진 운반은 많아야 하나다 — 계획 순서로 처음 찾은 것에 붙는다. 취소와 읽기뿐이라 발행 게이트를
 * 묻지 않는다(플릿 운반은 원래 묻지 않는다).
 *
 * @return 붙었거나 기다리거나 보류에 뒀다 — 이번 라운드에 정착하지 않는다.
 */
internal fun Middleware.attachTransportForCancel(execution: Execution): Boolean {
    for (unit in execution.units.filter { it.route == Route.FLEET && it.state == UnitState.PENDING }) {
        val reference = taskIdOf(execution, unit)
        if (fleet.executionLookup == ExecutionLookup.NONE) {
            if (execution.restoredRequests[unit.unitId]?.first != unit.attempt) continue
            unit.taskId = reference
            unit.requestedAt = requestedAtOf(execution, unit)
            unit.state = UnitState.OPERATOR_HOLD
            unit.annotate("cancel requested but the fleet has no lookup for the transport dispatched before restart")
            execution.cancelDelivery = execution.cancelDelivery.unsent(LOOKUP_UNSENT_CAUSE)
            execution.cancelWait = CancelWait.Held(unit.unitId)
            execution.trail(CANCEL_UNSENT, "${unit.unitId}: 취소를 하위에 보내지 못했다 ($LOOKUP_UNSENT_CAUSE) — 운영자 판단을 기다린다")
            execution.markIncident(unit)
            return true
        }
        // 미리 읽는 포트면 그 읽기를 받는다(§15.237). 읽는 중이면 답을 기다리는 것과 같다 — 같은 미확정 유예를 센다.
        val handle = when (val read = fleet.prefetchedFind(reference) ?: findNow(reference)) {
            FleetRead.Pending -> return waitUnattached(execution, unit, "$reference 의 운반을 읽는 중이다 — 다음 라운드에 답을 받는다")
            is FleetRead.Overdue, is FleetRead.Failed ->
                return waitUnattached(execution, unit, "$reference 의 운반을 찾는 데 답이 없다 — 다음 라운드에 다시 찾는다")
            is FleetRead.Read -> read.value
        } ?: continue
        val status = when (val read = fleet.prefetchedStatus(handle) ?: statusNow(handle)) {
            FleetRead.Pending -> return waitUnattached(execution, unit, "$reference 의 운반을 읽는 중이다 — 다음 라운드에 답을 받는다")
            is FleetRead.Overdue, is FleetRead.Failed ->
                return waitUnattached(execution, unit, "$reference 의 운반(${handle.id}) 상태에 답이 없다 — 다음 라운드에 다시 찾는다")
            is FleetRead.Read -> read.value
        }
        val ended = status.state in TRANSPORT_ENDED
        unit.taskId = reference
        unit.requestedAt = requestedAtOf(execution, unit)
        unit.state = UnitState.RUNNING
        execution.cancelWait = execution.cancelWait.attached()
        execution.active = unit
        execution.transport = handle
        execution.physicalState = PhysicalState.CANCELING
        if (!ended) sendCancel(execution)
        execution.trail(
            CANCEL_ATTACHED,
            "${unit.unitId}: 플릿의 $reference=${status.state.name}(${handle.id}) 에 새로 맡기지 않고 붙었다, " +
                if (ended) "이미 끝나 그 종료로 판정한다" else "취소를 보냈다",
        )
        return true
    }
    return false
}

/**
 * 붙기 전에 건 취소가 [unit] 의 답을 기다린다 — [attachForCancel] 의 4, [attachTransportForCancel] 의 3. 실행은 `CANCELING` 인 채
 * 다음 라운드에 다시 보고, 까닭은 같은 것이면 자취에 한 번만 남긴다.
 *
 * **기한은 미확정 유예다**(§15.255). 처음 기다린 때부터 [LogicalCapability.inDoubtGrace] 가 지나도 답이 없으면 [unit] 을 운영자
 * 보류에 두고 인시던트를 연다. 기체나 운반이 멈췄는지 확인할 수 없어 내주지 않는 쪽이다 — 끊긴 기체에 건 취소([holdUnreachedCancel])와
 * 같은 기한, 같은 보류다. 보류 중에 스냅숏에 그 태스크가 보이면 [resendHeldCancel] 이 취소를 다시 보낸다(로봇 경로만).
 *
 * @return 늘 참 — 이번 라운드에 정착하지 않는다.
 */
/** 미리 읽지 않는 플릿에는 지금 찾는다. 던지면 [FleetRead.Failed] 다. */
private fun Middleware.findNow(reference: String): FleetRead<TransportHandle?> = try {
    FleetRead.Read(fleet.find(reference), now(), now())
} catch (e: RuntimeException) {
    FleetRead.Failed(e)
}

/** 미리 읽지 않는 플릿에는 지금 읽는다. 던지면 [FleetRead.Failed] 다. */
private fun Middleware.statusNow(handle: TransportHandle): FleetRead<TransportStatus> = try {
    FleetRead.Read(fleet.status(handle), now(), now())
} catch (e: RuntimeException) {
    FleetRead.Failed(e)
}

internal fun Middleware.waitUnattached(execution: Execution, unit: ExecutionUnit, why: String): Boolean {
    val waiting = when (val wait = execution.cancelWait) {
        is CancelWait.Attaching -> wait
        // 보류에 묶였던 단위는 운영자가 판단했다 — 그래야 이 자리에 온다([cancelWaitsOnHeldDoubt]).
        is CancelWait.Held, CancelWait.None -> CancelWait.Attaching(now()).also { execution.cancelWait = it }
    }
    if (now().isBefore(waiting.since.plus(inDoubtGraceOf(execution)))) {
        execution.physicalState = PhysicalState.CANCELING
        if (waiting.noted != why) {
            execution.cancelWait = waiting.copy(noted = why)
            execution.trail(CANCEL_UNATTACHED_WAIT, why)
        }
        return true
    }
    val since = waiting.since
    execution.unattached.remove(unit.unitId)
    unit.taskId = taskIdOf(execution, unit)
    unit.requestedAt = requestedAtOf(execution, unit)
    unit.state = UnitState.OPERATOR_HOLD
    unit.annotate("cancel requested before attach but never answered: waiting since $since, not answered within grace")
    execution.cancelDelivery = execution.cancelDelivery.unsent(UNATTACHED_UNSENT_CAUSE)
    execution.cancelWait = CancelWait.Held(unit.unitId)
    execution.trail(CANCEL_UNSENT, "${unit.unitId}: 취소를 하위에 보내지 못했다 ($UNATTACHED_UNSENT_CAUSE) — 운영자 판단을 기다린다")
    execution.markIncident(unit)
    return true
}

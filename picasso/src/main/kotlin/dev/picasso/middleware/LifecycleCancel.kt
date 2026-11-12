package dev.picasso.middleware

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.middleware.Middleware.Companion.CANCEL_RESENT
import dev.picasso.middleware.Middleware.Companion.CANCEL_UNSENT
import dev.picasso.middleware.Middleware.Execution

// 취소 송신과 폴링: 도는 단위의 취소를 보내고 답을 정산한다. 공개 창구(cancel)는 Middleware 에 있다.
// 명령 생애 넷(LifecycleIssue, LifecycleDoubt, LifecycleCancelAttach, LifecycleCancel)은 서로를 양방향으로 부르는
// 하위 상태기계 하나다. 파일만 나눴을 뿐 모듈이 아니다. 상태(실행의 송신 기록, 취소 표시)는 Middleware.Execution 에 있다.

/**
 * 건 취소를 하위로 보낸다 — 도는 단위의 핸들이 있으면 그 단위가 묶인 기체의 로봇에, 운반이 있으면 플릿에. 부르는 자리는
 * [cancel]·[resolveDoubt]·[pumpRound] 머리의 다시 보내기 셋이다.
 *
 * **던져도 부른 쪽으로 내보내지 않는다.** 보내지 못했으면 [Execution.cancelUnsent] 를 세우고 트레이스에 [CANCEL_UNSENT] 를
 * 같은 까닭이면 한 번만 남긴다. 응답을 받으면 — 상태든 거부든 — 내린다. 거부는 하위가 답한 것이라 다시 보내지 않는다.
 * **거부를 감추지 않는다.** 그 스킬이 취소를 안 들면(`CANCEL_UNSUPPORTED`) 진행 중 단위는 끝까지 가고 다음 경계에서 멈춘다 —
 * 그 사실이 취소 응답에 드러나야 상위가 중단점을 안다(16장).
 *
 * 보낼 핸들도 운반도 없으면 아무것도 하지 않고 표시를 그대로 둔다.
 */
internal fun Middleware.sendCancel(execution: Execution) {
    // **보내는 중인 출발이 있으면 보내지 않는다**(§15.237). 그 결과를 받은 쪽이 보낸다 — 지금 보내면 기체에 아직 없는 태스크나 앞
    // 단위의 핸들로 나간다. 취소 요청([Execution.cancelRequested])은 그대로 남는다.
    if (execution.inFlight != null || execution.fleetInFlight != null) return
    var failure: RuntimeException? = null
    var attempted = false
    var pending = false
    val handle = execution.handle
    if (handle != null) {
        attempted = true
        val robot = execution.active?.let(execution::robotOf) ?: execution.robotId
        when (val result = cancelRobot(execution, CancelFlight(CancelFlight.Site.SEND, robot, handle))) {
            CancelIssue.Pending -> pending = true
            is CancelIssue.Answered -> noteRefusal(execution, result.response)
            is CancelIssue.Failed -> failure = result.failure
        }
    }
    val transport = execution.transport
    if (transport != null) {
        attempted = true
        when (val result = cancelFleet(execution, transport)) {
            FleetCancelIssue.Pending -> pending = true
            is FleetCancelIssue.Answered -> Unit
            is FleetCancelIssue.Failed -> failure = failure ?: result.failure
        }
    }
    if (!attempted) return
    if (failure != null) {
        // 한쪽이 보내는 중이어도 다른 쪽이 실패했으면 보내는 중인 쪽의 답만으로 «닿았다» 로 내리지 않는다.
        if (pending) markOtherFailed(execution)
        cancelNotReached(execution, failure)
        return
    }
    if (!pending) cancelReached(execution)
}

/** 도는 단위의 취소([sendCancel])에서 한쪽이 실패했다 — 보내는 중인 다른 쪽이 답해도 «닿았다» 로 내리지 않게 한다. */
private fun Middleware.markOtherFailed(execution: Execution) {
    execution.cancelInFlight?.takeIf { it.site == CancelFlight.Site.SEND }?.otherFailed = true
    execution.fleetCancelInFlight?.otherFailed = true
}

/**
 * 플릿에 운반 취소를 보낸다([sendCancel], §15.237). 포트가 잠금 밖에서 보내면([AmrFleetPort.issueCancel]) [FleetCancelIssue.Pending]
 * 을 받고 [Execution.fleetCancelInFlight] 에 둔다. 아니면 [AmrFleetPort.cancel] 을 지금 부른다. 이미 보내는 중이면 새로 보내지 않는다.
 */
private fun Middleware.cancelFleet(execution: Execution, handle: TransportHandle): FleetCancelIssue {
    if (execution.fleetCancelInFlight != null) return FleetCancelIssue.Pending
    val reference = execution.active?.takeIf { it.route == Route.FLEET }?.taskId ?: handle.id
    val result = fleet.issueCancel(reference, handle) ?: try {
        FleetCancelIssue.Answered(fleet.cancel(handle))
    } catch (e: RuntimeException) {
        FleetCancelIssue.Failed(e)
    }
    if (result == FleetCancelIssue.Pending) execution.cancelSending = execution.cancelSending.withFleet(FleetCancelFlight(reference, handle))
    return result
}

/** 닿지 않았다 — 같은 까닭이면 자취에 다시 남기지 않는다. 끊김 시각([CancelDelivery.Unsent.linkSince])은 그대로 둔다. */
private fun Middleware.cancelNotReached(execution: Execution, failure: RuntimeException) {
    val cause = "${failure::class.simpleName}: ${failure.message}"
    when (val delivery = execution.cancelDelivery) {
        is CancelDelivery.Unsent -> if (delivery.cause == cause) return
        CancelDelivery.Clear -> Unit
    }
    execution.cancelDelivery = execution.cancelDelivery.unsent(cause)
    execution.trail(CANCEL_UNSENT, "취소를 하위에 보내지 못했다 ($cause) — 다음 라운드에 다시 보낸다")
}

/** 닿았다 — 닿지 않은 채였으면 그 사실을 자취에 남기고 걷는다. */
private fun Middleware.cancelReached(execution: Execution) {
    when (execution.cancelDelivery) {
        is CancelDelivery.Unsent -> {
            execution.cancelDelivery = CancelDelivery.Clear
            execution.trail(CANCEL_RESENT, "보내지 못했던 취소가 하위에 닿았다")
        }
        CancelDelivery.Clear -> Unit
    }
}

internal fun Middleware.noteRefusal(execution: Execution, response: CancelTaskResponse) {
    if (!response.hasState()) execution.cancelIntent = CancelIntent.Refused(response.rejection.code.name)
}

/**
 * 로봇에 취소를 보낸다 — 네 자리([sendCancel]·[cancelByKey]·[resendHeldCancel]·[attachForCancel])가 함께 쓴다(§15.237).
 * 포트가 잠금 밖에서 보내면([RobotPort.issueCancel]) [CancelIssue.Pending] 을 받고 [flight] 를 [Execution.cancelInFlight] 에
 * 둔다. 아니면 [RobotPort.cancel] 을 지금 부르고 그 결과를 같은 셋으로 감싼다. 이미 보내는 중인 취소가 있으면 새로 보내지 않고
 * [CancelIssue.Pending] 이다.
 */
internal fun Middleware.cancelRobot(execution: Execution, flight: CancelFlight): CancelIssue {
    if (execution.cancelInFlight != null) return CancelIssue.Pending
    val result = robots.issueCancel(flight.robot, flight.handle) ?: try {
        CancelIssue.Answered(robots.cancel(flight.robot, flight.handle))
    } catch (e: RuntimeException) {
        CancelIssue.Failed(e)
    }
    if (result == CancelIssue.Pending) execution.cancelSending = execution.cancelSending.withRobot(flight)
    return result
}

/**
 * 보내는 중인 취소의 결과를 묻는다([pump] 머리). 끝난 실행도 묻는다 — 받아 가지 않으면 포트의 그 기체 차선이 풀리지 않는다. 끝난
 * 실행의 결과는 버린다.
 *
 * @return 아직 보내는 중인가. 참이면 이 라운드는 진행하지 않는다.
 */
internal fun Middleware.pollCancel(execution: Execution): Boolean {
    val robot = pollRobotCancel(execution)
    val fleet = pollFleetCancel(execution)
    return robot || fleet
}

private fun Middleware.pollFleetCancel(execution: Execution): Boolean {
    val flight = execution.fleetCancelInFlight ?: return false
    val result = fleet.issueCancel(flight.reference, flight.handle)
        ?: FleetCancelIssue.Failed(IllegalStateException("플릿 포트가 보내는 중인 운반 ${flight.reference} 취소의 결과를 주지 않는다"))
    if (result == FleetCancelIssue.Pending) return true
    execution.cancelSending = execution.cancelSending.withFleet(null)
    if (!execution.physicalState.pumps) return false
    when (result) {
        is FleetCancelIssue.Answered -> if (!flight.otherFailed && execution.cancelInFlight == null) cancelReached(execution)
        is FleetCancelIssue.Failed -> {
            execution.cancelInFlight?.takeIf { it.site == CancelFlight.Site.SEND }?.otherFailed = true
            cancelNotReached(execution, result.failure)
        }
        FleetCancelIssue.Pending -> Unit
    }
    return false
}

private fun Middleware.pollRobotCancel(execution: Execution): Boolean {
    val flight = execution.cancelInFlight ?: return false
    val result = robots.issueCancel(flight.robot, flight.handle)
        ?: CancelIssue.Failed(IllegalStateException("robot=${flight.robot} 의 포트가 보내는 중인 취소의 결과를 주지 않는다"))
    if (result == CancelIssue.Pending) return true
    execution.cancelSending = execution.cancelSending.withRobot(null)
    if (execution.physicalState.pumps) settleCancel(execution, flight, result)
    return false
}

/** 잠금 밖에서 보낸 취소의 결과로 그 자리의 뒷일을 한다. 그 사이 단위가 다른 자리로 갔으면 결과만 버린다. */
private fun Middleware.settleCancel(execution: Execution, flight: CancelFlight, result: CancelIssue) {
    val unit = flight.unitId?.let { id -> execution.units.firstOrNull { it.unitId == id } }
    when (flight.site) {
        CancelFlight.Site.SEND -> when (result) {
            is CancelIssue.Answered -> {
                noteRefusal(execution, result.response)
                if (!flight.otherFailed && execution.fleetCancelInFlight == null) cancelReached(execution)
            }
            is CancelIssue.Failed -> {
                execution.fleetCancelInFlight?.otherFailed = true
                cancelNotReached(execution, result.failure)
            }
            CancelIssue.Pending -> Unit
        }
        CancelFlight.Site.KEY -> {
            if (unit == null || unit.state != UnitState.IN_DOUBT) return
            if (unit.taskId != flight.handle.taskId || unit.revision != flight.handle.revision) return
            if (result is CancelIssue.Answered) attachByKey(execution, unit, flight.handle, result.response)
        }
        CancelFlight.Site.HELD -> {
            if (unit == null || unit.state != UnitState.OPERATOR_HOLD) return
            // 보류에 묶인 이 단위의 취소가 아직 닿지 않은 채일 때만 받는다.
            val held = when (val wait = execution.cancelWait) {
                is CancelWait.Held -> wait.unitId == unit.unitId
                is CancelWait.Attaching, CancelWait.None -> false
            }
            if (!held || execution.cancelDelivery !is CancelDelivery.Unsent) return
            if (result is CancelIssue.Answered) heldCancelAnswered(execution, unit, checkNotNull(flight.stateName), result.response)
        }
        CancelFlight.Site.ATTACH -> {
            if (unit == null || unit.state != UnitState.PENDING || execution.active != null) return
            if (execution.unattached[unit.unitId] != unit.attempt) return
            when (result) {
                is CancelIssue.Answered -> {
                    noteRefusal(execution, result.response)
                    attachUnit(execution, unit, flight.handle.taskId, flight.handle, checkNotNull(flight.stateName), sent = true)
                }
                is CancelIssue.Failed -> waitUnattached(execution, unit, unansweredAttach(flight.handle.taskId))
                CancelIssue.Pending -> Unit
            }
        }
    }
}

/**
 * 보내는 중인 취소 하나(§15.237, [Middleware.Execution.cancelInFlight]). 포트에 다시 물을 때 같은 기체와 핸들을 쓰고, 결과를 받으면
 * [site] 의 뒷일을 한다.
 *
 * @property unitId 도는 단위의 취소([Site.SEND])가 아니면 그 단위.
 * @property stateName 보류 중 다시 보내기([Site.HELD])·붙기 전의 취소([Site.ATTACH])면 보낼 때 스냅숏에 보인 태스크 상태.
 */
internal class CancelFlight(
    val site: Site,
    val robot: String,
    val handle: TaskHandle,
    val unitId: String? = null,
    val stateName: String? = null,
) {
    /** 도는 단위의 취소에서 같은 호출의 플릿 취소가 실패했는가. 그러면 로봇이 답해도 «닿았다» 로 내리지 않는다. */
    var otherFailed: Boolean = false

    enum class Site { SEND, KEY, HELD, ATTACH }
}

/** 보내는 중인 운반 취소 하나(§15.237, [Middleware.Execution.fleetCancelInFlight]). 포트에 다시 물을 때 같은 참조와 핸들을 쓴다. */
internal class FleetCancelFlight(val reference: String, val handle: TransportHandle) {
    /** 같은 호출의 로봇 취소가 실패했는가. 그러면 플릿이 답해도 «닿았다» 로 내리지 않는다. */
    var otherFailed: Boolean = false
}

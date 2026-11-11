package dev.picasso.middleware

import dev.picasso.middleware.Middleware.Companion.FLEET_REJECTED
import dev.picasso.middleware.Middleware.Companion.START_NOT_SENT
import dev.picasso.middleware.Middleware.Execution
import java.time.Instant

// 명령 발행: 로봇 출발과 플릿 맡기기를 내고(잠금 밖 송신) 그 결과를 정산한다. 잠금 밖 송신의 약속은
// IssuingGate, CommandLanes, Prefetching 포트와의 것이다.
// 명령 생애 넷(LifecycleIssue, LifecycleDoubt, LifecycleCancelAttach, LifecycleCancel)은 서로를 양방향으로 부르는
// 하위 상태기계 하나다. 파일만 나눴을 뿐 모듈이 아니다. 상태(실행의 송신 기록, 취소 표시)는 Middleware.Execution 에 있다.

internal fun Middleware.transportOrderOf(unit: ExecutionUnit) = TransportOrder(
    reference = unit.taskId,
    containerId = unit.expectedIdentity ?: unit.unitId,
    source = unit.source ?: "",
    destination = unit.destination ?: "",
)

/**
 * 능력이 쓰고 싶다는 **선택 파라미터**를 붙인다 — **로봇이 선언한 것만.**
 *
 * 선택 필드는 기종마다 있고 없다. 안 드는 기종에 보내면 코어 키는 fail-closed 라 태스크 자체가
 * `PARAMETER_INVALID` 로 거절되고(§5.3), 그러면 선택 필드 하나 때문에 그 기종에서 이 능력을 못 쓴다 —
 * 실측으로 그런 기종이 있다 — 어느 것인지는 이 모듈이 알 자리가 아니다(게이트 7번).
 *
 * ★**뺐다는 사실을 적는다.** 조용히 빼면 *파지 확인을 요구했다* 와 *못 해서 안 했다* 가 같아 보이고,
 * 그것은 이 저장소가 진행률·결함·파지에서 반복해 거절한 접기다. 기체마다 한 번만 적는다.
 *
 * 능력을 **못 물어봤으면 안 붙인다** — 널은 *아무것도 안 든다* 가 아니라 *모른다* 이고, 모를 때는
 * 막는 방향이다(§15.41).
 */
internal fun Middleware.withOptionals(execution: Execution, unit: ExecutionUnit, base: Map<String, String>): Map<String, String> {
    val wanted = execution.capability.preferredOptionals
    if (wanted.isEmpty()) return base

    val robot = execution.robotOf(unit)
    // **던져도 «못 물어봤다» 다**(§15.295). 여기는 출발 요청을 만드는 도중이라, 던지면 나가지도 않은 명령이 미확정으로 읽힌다.
    val declared = runCatching { robots.capabilities(robot) }.getOrNull()
        ?.skillsList?.firstOrNull { it.skillType == unit.skillType }
        ?.parametersList?.map { it.key }?.toSet()

    val kept = if (declared == null) emptyMap() else wanted.filterKeys { it in declared }
    (wanted.keys - kept.keys).forEach { key ->
        if (!droppedOptionals.add(robot to key)) return@forEach
        val why = if (declared == null) "케이퍼빌리티를 못 물어봤다" else "이 기종이 선언하지 않는다"
        execution.trail("OPTIONAL_NOT_SENT", "$key: $why — 요구하지 않고 보낸다")
    }
    return base + kept
}

/** 이 단위의 지금 시도가 쓰는 태스크 id. 재작업은 새 정체성이다 — 같은 task_id 는 계약이 같은(종착한) 핸들로 돌려준다. */
internal fun Middleware.taskIdOf(execution: Execution, unit: ExecutionUnit): String =
    "${execution.order.jobOrderId}#${unit.unitId}" + if (unit.attempt > 0) "@r${unit.attempt}" else ""

/**
 * 판단 기록이 이 시도의 요청 시각을 넘겼으면 그것이다(§15.221). 다시 지은 뒤 같은 시도를 다시 보내는 것이지 새 요청이 아니므로,
 * 미확정 유예·관측 창·설비 대기의 기한은 처음 보낸 시각부터 이어 잰다. 다른 시도(재작업)면 쓰지 않는다.
 */
internal fun Middleware.requestedAtOf(execution: Execution, unit: ExecutionUnit): Instant =
    execution.restoredRequests.remove(unit.unitId)?.takeIf { it.first == unit.attempt }?.second ?: now()

internal fun Middleware.startUnit(execution: Execution, unit: ExecutionUnit) {
    // 보내지 못하면 이 시도 앞의 자리로 되돌린다. 판단 기록([judgmentRecord])은 태스크 id 가 있으면 낸 것으로 읽어 요청 시각을
    // 남긴다 — 되돌리지 않으면 보내지 않은 시각이 재기동 뒤 미확정 유예의 기준이 된다. 재기동 뒤 다시 세운 실행의 요청 시각과 붙지 않은
    // 태스크는 다음 출발이 다시 쓴다.
    val restore = StartRestore(
        taskId = unit.taskId,
        requestedAt = unit.requestedAt,
        restored = execution.restoredRequests[unit.unitId],
        unattached = execution.unattached[unit.unitId],
        restoredUsed = execution.restoredRequests[unit.unitId]?.first == unit.attempt,
    )
    unit.taskId = taskIdOf(execution, unit)
    // 재기동 뒤 다시 세운 시도는 앞 인스턴스가 다른 리비전으로 보냈을 수 있다 — 모르는 것으로 둔다.
    unit.startedRevision = unit.revision.takeIf { execution.inherited[unit.unitId] != unit.attempt }
    unit.requestedAt = requestedAtOf(execution, unit)
    unit.lookups = 0
    unit.lastLookupAt = null
    unit.streamLostAt = null
    unit.keyedCancelAt = null
    // 같은 태스크 id 로 보내면 기체에 있던 태스크에 붙는다 — 이제 이 인스턴스가 든 태스크다.
    execution.unattached.remove(unit.unitId)
    execution.active = unit
    if (unit.route == Route.ROBOT) {
        val flight = InFlight(
            InFlight.Kind.START, unit.unitId, execution.robotOf(unit), unit.taskId, unit.revision, unit.skillType,
            withOptionals(execution, unit, unit.parameters), previous = unit.revision, restore = restore,
        )
        settleIssue(execution, flight, issueStart(flight))
        return
    }
    if (unit.route == Route.FLEET) {
        val flight = FleetFlight(FleetFlight.Kind.START, unit.unitId, transportOrderOf(unit), restore)
        settleDispatch(execution, flight, issueDispatch(flight.order))
        return
    }
    val accepted = try {
        lookupDownstream(execution, unit)
    } catch (_: CommandNotSent) {
        startNotSent(execution, unit, restore)
        return
    } catch (_: RuntimeException) {
        startInDoubt(execution, unit)
        return
    }
    if (!accepted) {
        execution.active = null
        return
    }
    unit.state = UnitState.RUNNING
    execution.physicalState = PhysicalState.RUNNING
}

/**
 * 로봇 단위의 출발(`StartTask`)을 보낸다 — 첫 출발·개정 갱신·미확정 조회가 함께 쓴다(§15.237). 포트가 잠금 밖에서 보내면
 * ([RobotPort.issue]) 기다리지 않고 그 처지를 돌려주고, 아니면 [RobotPort.start] 를 지금 불러 그 결과를 같은 넷으로 감싼다. 보내는
 * 중인 출발의 결과를 다시 물을 때도 이것을 부른다 — 같은 키면 포트가 같은 송신의 처지를 준다.
 */
internal fun Middleware.issueStart(flight: InFlight): Issue =
    robots.issue(flight.robot, flight.taskId, flight.revision, flight.skillType, flight.parameters) ?: try {
        Issue.Answered(robots.start(flight.robot, flight.taskId, flight.revision, flight.skillType, flight.parameters), sentAt = null)
    } catch (e: CommandNotSent) {
        Issue.NotSent(e.message ?: "not sent")
    } catch (e: RuntimeException) {
        Issue.InDoubt(e, sentAt = null)
    }

/**
 * 출발 송신의 처지를 적는다. 보내는 중이면 [Execution.inFlight] 에 두고, 결과면 그 종류의 정리로 넘긴다.
 *
 * @return 단위가 이 라운드에 종료(운영자 보류 포함)했는가 — 미확정 조회([resolveDoubt])만 쓴다.
 */
internal fun Middleware.settleIssue(execution: Execution, flight: InFlight, issue: Issue): Boolean {
    if (issue is Issue.Pending) {
        execution.inFlight = flight
        return false
    }
    execution.inFlight = null
    val unit = execution.units.first { it.unitId == flight.unitId }
    return when (flight.kind) {
        InFlight.Kind.START -> {
            settleStart(execution, unit, flight, issue)
            false
        }
        InFlight.Kind.UPDATE -> {
            settleUpdate(execution, unit, flight, issue)
            false
        }
        InFlight.Kind.LOOKUP -> settleLookup(execution, unit, issue)
    }
}

/**
 * 첫 출발의 결과. 요청 시각은 포트가 실제로 보낸 시각으로 고친다 — 재기동 전 요청 시각을 넘겨받은 시도면 그대로 둔다(§15.221).
 * **보내는 중에 걸린 취소는 접수를 본 뒤 보낸다**(§15.237). 미확정이면 [resolveDoubt] 의 취소 길로, 보내지 않았으면 지울 것이 없다.
 */
internal fun Middleware.settleStart(execution: Execution, unit: ExecutionUnit, flight: InFlight, issue: Issue) {
    val restore = checkNotNull(flight.restore)
    when (issue) {
        Issue.Pending -> Unit
        is Issue.NotSent -> {
            execution.deferredUpdate = null
            startNotSent(execution, unit, restore)
        }
        is Issue.InDoubt -> {
            if (!restore.restoredUsed) issue.sentAt?.let { unit.requestedAt = it }
            execution.deferredUpdate = null
            startInDoubt(execution, unit)
        }
        is Issue.Answered -> {
            if (!restore.restoredUsed) issue.sentAt?.let { unit.requestedAt = it }
            if (!applyStartResponse(execution, unit, issue.response)) {
                execution.active = null
                execution.deferredUpdate = null
                return
            }
            unit.state = UnitState.RUNNING
            execution.physicalState = if (execution.cancelRequested) PhysicalState.CANCELING else PhysicalState.RUNNING
            if (execution.cancelRequested) sendCancel(execution)
        }
    }
}

/** 플릿이 준 운반 [handle] 을 잡는다. 널이면 플릿이 맡지 않은 것이라 단위를 실패로 적는다. @return 잡았는가. */
internal fun Middleware.acceptTransport(execution: Execution, unit: ExecutionUnit, handle: TransportHandle?): Boolean {
    if (handle != null) {
        execution.transport = handle
        return true
    }
    unit.state = UnitState.FAILED
    unit.failureClass = FLEET_REJECTED
    execution.markIncident(unit)
    return false
}

/**
 * 플릿 맡기기를 보낸다 — 첫 맡기기와 미확정 조회가 함께 쓴다(§15.237). 포트가 잠금 밖에서 보내면([AmrFleetPort.issueDispatch])
 * 기다리지 않고 그 처지를 돌려주고, 아니면 [AmrFleetPort.dispatch] 를 지금 불러 그 결과를 같은 넷으로 감싼다. 보내는 중인 맡기기의
 * 결과를 다시 물을 때도 이것을 부른다.
 */
internal fun Middleware.issueDispatch(order: TransportOrder): DispatchIssue =
    fleet.issueDispatch(order) ?: try {
        DispatchIssue.Answered(fleet.dispatch(order), sentAt = null)
    } catch (e: CommandNotSent) {
        DispatchIssue.NotSent(e.message ?: "not sent")
    } catch (e: RuntimeException) {
        DispatchIssue.InDoubt(e, sentAt = null)
    }

/**
 * 맡기기 송신의 처지를 적는다. 보내는 중이면 [Execution.fleetInFlight] 에 두고, 결과면 그 종류의 정리로 넘긴다. 정리는 로봇 출발과
 * 같은 함수([startNotSent]·[startInDoubt]·[lookupFound])를 쓴다.
 *
 * @return 단위가 이 라운드에 종료(운영자 보류 포함)했는가 — 미확정 조회([resolveDoubt])만 쓴다.
 */
internal fun Middleware.settleDispatch(execution: Execution, flight: FleetFlight, issue: DispatchIssue): Boolean {
    if (issue is DispatchIssue.Pending) {
        execution.fleetInFlight = flight
        return false
    }
    execution.fleetInFlight = null
    val unit = execution.units.first { it.unitId == flight.unitId }
    return when (flight.kind) {
        FleetFlight.Kind.START -> {
            settleFleetStart(execution, unit, checkNotNull(flight.restore), issue)
            false
        }
        FleetFlight.Kind.LOOKUP -> when (issue) {
            DispatchIssue.Pending -> false
            is DispatchIssue.NotSent -> lookupNotSent(execution, unit)
            is DispatchIssue.InDoubt -> lookupFound(execution, unit, false)
            is DispatchIssue.Answered -> lookupFound(execution, unit, acceptTransport(execution, unit, issue.handle))
        }
    }
}

/** 첫 맡기기의 결과. 요청 시각과 보내는 중에 걸린 취소는 로봇의 첫 출발([settleStart])과 같은 규칙이다. */
internal fun Middleware.settleFleetStart(execution: Execution, unit: ExecutionUnit, restore: StartRestore, issue: DispatchIssue) {
    when (issue) {
        DispatchIssue.Pending -> Unit
        is DispatchIssue.NotSent -> startNotSent(execution, unit, restore)
        is DispatchIssue.InDoubt -> {
            if (!restore.restoredUsed) issue.sentAt?.let { unit.requestedAt = it }
            startInDoubt(execution, unit)
        }
        is DispatchIssue.Answered -> {
            if (!restore.restoredUsed) issue.sentAt?.let { unit.requestedAt = it }
            if (!acceptTransport(execution, unit, issue.handle)) {
                execution.active = null
                return
            }
            unit.state = UnitState.RUNNING
            execution.physicalState = if (execution.cancelRequested) PhysicalState.CANCELING else PhysicalState.RUNNING
            if (execution.cancelRequested) sendCancel(execution)
        }
    }
}

/**
 * **보내지 않았다**(§15.237) — 포트가 하위로 보내지 않은 것을 안다. 미확정이 아니다. 단위는 `PENDING` 그대로 두고 도는 단위를
 * 비운다. 다음 라운드가 결함·허가 게이트를 다시 지나 처음부터 낸다 — 요청 시각도 그때 다시 찍혀 실제로 보낸 시각이 된다.
 */
internal fun Middleware.startNotSent(execution: Execution, unit: ExecutionUnit, restore: StartRestore) {
    execution.active = null
    unit.taskId = restore.taskId
    unit.requestedAt = restore.requestedAt
    restore.restored?.let { execution.restoredRequests[unit.unitId] = it }
    restore.unattached?.let { execution.unattached[unit.unitId] = it }
    unit.annotateOnce(START_NOT_SENT)
}

/**
 * **요청은 갔는데 답이 없다.** 접수됐는지 모른다 — 다시 보내지 않는다(물리 작업이 둘이 될 수 있다). 해소는 13.2 의 순서로
 * [resolveDoubt] 가 한다. 상류에는 미확정이라는 사실을 드러낸다(16장).
 */
internal fun Middleware.startInDoubt(execution: Execution, unit: ExecutionUnit) {
    unit.state = UnitState.IN_DOUBT
    execution.physicalState = PhysicalState.IN_DOUBT
    if (execution.notedDoubt != unit.unitId) {
        execution.notedDoubt = unit.unitId
        notify(execution)
    }
}

/**
 * 보내는 중인 출발 하나(§15.237, [Middleware.Execution.inFlight]). 포트에 다시 물을 때 같은 키와 같은 인자를 쓴다.
 *
 * @property previous 개정 갱신이면 기체에서 지금 도는 리비전. 다른 종류는 [revision] 과 같다.
 * @property restore 첫 출발이면 그 시도 앞의 자리. 보내지 못하면 이것으로 되돌린다.
 */
internal class InFlight(
    val kind: Kind,
    val unitId: String,
    val robot: String,
    val taskId: String,
    val revision: Int,
    val skillType: String,
    val parameters: Map<String, String>,
    val previous: Int,
    val restore: StartRestore?,
) {
    enum class Kind { START, UPDATE, LOOKUP }
}

/**
 * 보내는 중인 플릿 맡기기 하나(§15.237, [Middleware.Execution.fleetInFlight]). 포트에 다시 물을 때 같은 [order] 를 쓴다.
 *
 * @property restore 첫 맡기기면 그 시도 앞의 자리. 보내지 못하면 이것으로 되돌린다.
 */
internal class FleetFlight(val kind: Kind, val unitId: String, val order: TransportOrder, val restore: StartRestore?) {
    enum class Kind { START, LOOKUP }
}

/** 첫 출발 앞의 단위 자리. [restoredUsed] 면 이 시도의 요청 시각이 재기동 전 것이라 보낸 시각으로 고치지 않는다(§15.221). */
internal class StartRestore(
    val taskId: String,
    val requestedAt: Instant?,
    val restored: Pair<Int, Instant>?,
    val unattached: Int?,
    val restoredUsed: Boolean,
)

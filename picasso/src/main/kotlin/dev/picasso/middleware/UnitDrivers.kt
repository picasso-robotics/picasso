package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.ProgressKind
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.middleware.Middleware.Companion.NO_SIGNAL
import dev.picasso.middleware.Middleware.Companion.SOURCE_MISMATCH
import dev.picasso.middleware.Middleware.Companion.UNCLASSIFIED
import dev.picasso.middleware.Middleware.Companion.WAITING_HANDOVER
import dev.picasso.middleware.Middleware.Companion.WATCH_CLOSED
import dev.picasso.middleware.Middleware.Execution
import java.time.Duration

// 경로별 단위 구동: 로봇 스킬, 설비 신호, 플릿 운반 단위를 한 라운드 진행한다. 라운드의 순서(pumpRound, 종착 전이 abort,
// 통보 notify)는 Middleware 에 남는다. 상태는 Middleware 에 있다.

/**
 * 진행률을 읽고 **정체를 보이게 한다**(계약 0.8.0 의 `progress_basis` 를 읽는 자리).
 *
 * 규칙 둘이다.
 *
 * 1. **못 재는 기체는 판정하지 않는다.** `NOT_OBSERVABLE` 이면 그 사실을 자취에 **한 번** 적고 끝이다 —
 *    진행률의 `0.0` 을 정체로 읽으면 진행률을 안 내는 기종(Spot·G1)이 언제나 멈춰 있는 것으로 보이고,
 *    그러면 이 경보 자체가 곧 무시된다. 옛 발신자(`UNSPECIFIED`)도 같게 다룬다 — **막는 방향**이다(§15.41).
 * 2. **잴 수 있는데 안 움직이면 알린다.** 실패로 적지 않고 상태도 안 바꾼다. 느린 것과 멈춘 것을 우리가
 *    못 가르기 때문이고, 그래서 판단은 사람의 것이다.
 */
private fun Middleware.noteProgress(execution: Execution, unit: ExecutionUnit, update: WatchTaskResponse) {
    val basis = update.progressBasis
    val measured = basis.kind == ProgressKind.PROGRESS_KIND_MEASURED
    if (unit.progressObservable == null) {
        unit.progressObservable = measured
        if (!measured) {
            val why = basis.reason.ifBlank { "기체가 진행률의 근거를 안 낸다(kind=${basis.kind.name})" }
            execution.trail("PROGRESS_NOT_OBSERVABLE", "${unit.unitId}: $why — 정체 판정을 하지 않는다")
        }
    }
    if (!measured) return

    val at = now()
    if (unit.progressAt == null || update.progress > unit.progress) {
        unit.progress = update.progress
        unit.progressAt = at
        unit.progressStalled = false
        return
    }
    if (unit.progressStalled) return
    val window = stallWindowOf(execution)
    if (Duration.between(unit.progressAt, at) < window) return

    unit.progressStalled = true
    unit.annotate("진행률이 $window 동안 안 움직였다 (${basis.basis})")
    execution.trail(
        "PROGRESS_STALLED",
        "${unit.unitId}: ${basis.basis} at ${update.progress} — 느린 것과 멈춘 것은 이 층이 못 가른다. 사람이 본다",
    )
    notify(execution)
}

/** @return 단위가 이 펌프에서 종착(또는 검증 대기로 이행)해 active 에서 내려와도 되는가. */
internal fun Middleware.pumpRobotUnit(execution: Execution, unit: ExecutionUnit): Boolean {
    val robot = execution.robotOf(unit)
    val handle = execution.handle!!
    val updates = robots.watch(robot, handle)
    // **결과 이벤트는 (실행, 버전)으로 맞춘다**(15.1). 이 단위의 지금 버전보다 낮은 버전의 종착은 지연 이벤트로
    // **보존**하고, 이 버전의 갱신만 상태로 옮긴다.
    val (late, current) = updates.partition { it.revision < unit.revision }
    late.filter { it.state.isTerminal() }.forEach { execution.noteLate(unit, it) }
    val last = current.lastOrNull()
        ?: return late.lastOrNull { it.state.isTerminal() }?.let { settleLate(execution, unit, it) }
            ?: robots.unfollowable(robot, handle)?.let { loseStream(execution, unit, handle, it) }
            ?: false
    unit.observeHold(last.hold)
    if (!last.state.isTerminal()) {
        robots.unfollowable(robot, handle)?.let { return loseStream(execution, unit, handle, it) }
        noteProgress(execution, unit, last)
        execution.physicalState = when {
            execution.cancelRequested -> PhysicalState.CANCELING
            execution.linkBroken -> PhysicalState.IN_DOUBT
            else -> PhysicalState.RUNNING
        }
        return false
    }
    judgeEffectMismatch(execution, unit, completed = last.state == TaskState.TASK_STATE_SUCCEEDED)
    when (last.state) {
        TaskState.TASK_STATE_SUCCEEDED -> {
            // 하류가 실어 준 결과 참조(E0 의 내용). 비어 있으면 비어 있는 채로 — 지어내지 않는다.
            unit.result = last.partialResult.takeIf { it.isNotBlank() }
            val prior = unit.unitId in execution.resumedPrior
            // 판단 기록이 이 시도의 검증 대기 창을 넘겼으면 그 창에 대고 본다 — 완료 시각과 기한을 다시 정하지 않는다(§15.221).
            val window = execution.restoredWindows.remove(unit.unitId)?.takeIf { it.first == unit.attempt }?.second
            beginVerify(
                execution, unit, reachedByDownstream = Evidence.E0, doneAt = window?.doneAt ?: stateTime(last), prior = prior,
                deadline = window?.deadline,
            )
            if (unit.state == UnitState.VERIFYING) return false // active 로 남아 시간창을 기다린다
        }

        TaskState.TASK_STATE_CANCELLED, TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED ->
            if (execution.cancelRequested) {
                unit.state = UnitState.ABORTED
            } else {
                holdUnrequestedCancel(execution, unit, downstreamDetail(last), last.fault.takeIf { last.hasFault() })
            }

        else -> {
            // 계약이 실은 **정준 분류**로만 분기한다(어댑터가 벤더 코드에서 옮긴 것, 미믹은 프로파일
            // 모드에서). 하류 상태 이름·모드 이름·벤더 원문은 note 에 동반할 뿐이다 — 상류에는 분류만
            // 간다(보고서 16장). 분류가 없으면 UNCLASSIFIED 이지 지어낸 분류가 아니다.
            failWithEvidenceCheck(
                execution, unit, canonicalClass(last),
                detail = downstreamDetail(last), at = stateTime(last),
                fault = last.fault.takeIf { last.hasFault() },
            )
        }
    }
    if (execution.cancelRequested) {
        abort(
            execution, inProgress = unit, hold = last.hold,
            cleanup = when (last.state) {
                TaskState.TASK_STATE_CANCELLED -> "done"
                TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED -> "failed"
                else -> "not_applicable"
            },
        )
    }
    return true
}

/**
 * 도는 단위의 갱신 스트림이 종료 없이 영구히 닫혔다(§15.228, [RobotPort.unfollowable]) — 대개 어댑터 호스트가 다시 떠 태스크를 잊은 것이다.
 *
 * 단위를 **미확정**으로 옮긴다. 기체가 그 일을 마쳤는지, 하는 중인지, 멈췄는지 이 층은 모른다. 해소는 [resolveDoubt] 의 순서이되 같은
 * 참조의 `StartTask` 재전송(①)은 건너뛴다 — 원 요청은 이미 닿았고 하위가 그것을 잊었으므로, 재전송은 같은 일을 새로 낸다. 스냅숏에
 * 태스크가 보이면 붙고(⓪), 아니면 설비를 보고(②) 사람에게 간다(③). 핸들은 버린다 — 미확정 단위는 핸들을 들지 않고, 붙으면 다시 짓는다.
 *
 * @return 늘 거짓 — 단위는 이 펌프에서 종착하지 않는다.
 */
private fun Middleware.loseStream(execution: Execution, unit: ExecutionUnit, handle: TaskHandle, why: String): Boolean {
    robots.release(execution.robotOf(unit), handle)
    execution.handle = null
    unit.state = UnitState.IN_DOUBT
    unit.streamLostAt = now()
    unit.annotate("task stream closed for good ($why) — result unconfirmed, no re-send")
    execution.trail(WATCH_CLOSED, "${unit.unitId}: ${unit.taskId} 의 갱신 스트림이 $why 로 닫혔다 — 미확정, 같은 참조로 다시 보내지 않는다")
    execution.physicalState = PhysicalState.IN_DOUBT
    if (execution.noticed.note(Notice.Doubt, unit.unitId)) {
        notify(execution)
    }
    return false
}

/**
 * 옛 버전의 종착만 있고 새 버전의 갱신은 오지 않는다 — 갱신이 닿기 전에 끝난 태스크다(보고서 17장 6번).
 *
 * **새 버전의 완료로 적지 않는다**(15.1). 물리적으로 일어난 일은 옛 버전의 파라미터로 한 일이므로, 그것이 새 버전의
 * 기대에 맞는지는 **설비가 새 버전의 기대에 대고** 판정한다 — 요구 등급이 E0 라도 설비를 묻고(`strict`), 설비가
 * 없으면 `UNVERIFIED` 다. 실패·중단은 버전과 무관한 물리 사실이라 그대로 옮기되 어느 버전의 것인지를 남긴다.
 */
private fun Middleware.settleLate(execution: Execution, unit: ExecutionUnit, update: WatchTaskResponse): Boolean {
    unit.observeHold(update.hold)
    unit.annotate("late event: revision ${update.revision} ${update.state.name} arrived under revision ${unit.revision}")
    when (update.state) {
        TaskState.TASK_STATE_SUCCEEDED -> {
            beginVerify(execution, unit, reachedByDownstream = Evidence.E0, doneAt = stateTime(update), strict = true)
            if (unit.state == UnitState.VERIFYING) return false
        }
        TaskState.TASK_STATE_CANCELLED, TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED ->
            if (execution.cancelRequested) {
                unit.state = UnitState.ABORTED
            } else {
                holdUnrequestedCancel(execution, unit, downstreamDetail(update), update.fault.takeIf { update.hasFault() })
            }
        else -> failWithEvidenceCheck(
            execution, unit, canonicalClass(update),
            detail = downstreamDetail(update), at = stateTime(update),
            fault = update.fault.takeIf { update.hasFault() },
        )
    }
    return true
}

/**
 * 설비 대기 단위를 한 단계 민다([Route.SIGNAL]) — 이름 있는 신호를 당겨 읽고, 기대 값이면 끝낸다.
 *
 * 규칙 넷이다.
 *
 * 1. **취소가 걸렸으면 기다림을 끝낸다.** 하위 손잡이가 없어 취소가 하위로 가지 않으므로 여기서 본다. 대기 단위는
 *    `ABORTED`, 실행은 취소 경로(정리 «해당 없음»)로 끝난다.
 * 2. **지금 값이 기대 값이면 `DONE` 이다.** 설비가 관측한 사실이라 근거 등급은 E2 다. 관측 시각은 자취에만 남긴다.
 * 3. **못 읽으면(`null`) 계속 기다린다.** 못 읽은 것을 «기대 값이 아님» 으로 접지 않는다 — 기한이 판정한다.
 * 4. **기한(시작 시각 + 기한)이 지나면** [WaitSpec.onDeadline] 으로 간다. 마지막으로 읽은 값을 자취에 한 번 남기고
 *    인시던트를 연다(`SIGNAL_DEADLINE`).
 *
 * 신호는 **값이 바뀔 때만** 자취에 남긴다. 진행마다 남기면 자취가 넘쳐 근거 윈도우가 그 줄로 찬다.
 *
 * @return 단위가 이 진행에서 종료(운영자 보류 포함)했는가.
 */
internal fun Middleware.pumpSignalUnit(execution: Execution, unit: ExecutionUnit): Boolean {
    val wait = unit.wait ?: error("설비 대기 단위에 대기 사양이 없다: ${unit.unitId}")
    if (execution.cancelRequested) {
        unit.state = UnitState.ABORTED
        unit.annotate("cancelled while waiting for ${wait.signal}")
        abort(execution, inProgress = unit, hold = null, cleanup = "not_applicable")
        return true
    }

    val reading = cell.signal(wait.signal)
    val seen = reading?.value ?: NO_SIGNAL
    if (execution.noticed.note(Notice.Signal(unit.taskId), seen)) {
        execution.trail("CELL_SIGNAL", "signal ${wait.signal}: ${describe(reading)}")
    }

    if (reading != null && reading.value == wait.expect) {
        unit.reached = Evidence.E2
        unit.verification = Verification.MATCHED
        unit.evidenceAt = reading.observedAt ?: now()
        unit.state = UnitState.DONE
        return true
    }

    val startedAt = unit.requestedAt!!
    if (!now().isAfter(startedAt.plus(wait.deadline))) {
        execution.physicalState = PhysicalState.RUNNING
        return false
    }

    // 기한이 지났다. 마지막으로 읽은 값을 **기한 시점의 관측으로** 한 번 더 남긴다 — 값이 안 바뀌었으면 그 줄은 시작
    // 무렵에 한 번 적혔을 뿐이라 인시던트의 근거 윈도우 밖에 있다.
    execution.trail("CELL_SIGNAL", "signal ${wait.signal} at deadline: ${describe(reading)}")
    unit.failureClass = WaitSpec.SIGNAL_DEADLINE
    unit.annotate("signal ${wait.signal} did not read '${wait.expect}' within ${wait.deadline} — ${wait.onDeadline.name}")
    execution.markIncident(unit)
    when (wait.onDeadline) {
        DeadlineOutcome.OPERATOR_HOLD -> unit.state = UnitState.OPERATOR_HOLD
        DeadlineOutcome.ABORTED -> {
            unit.state = UnitState.FAILED
            // **남은 단위는 내보내지 않는다** — 먼저 중단으로 적고 실행을 끝낸다. `PENDING` 으로 두면 그 단위가
            // «아직 안 시작함» 으로 남아, 진행 루프가 출발시킬 자리와 정산 규칙이 부분 완료로 읽을 자리가 남는다.
            execution.units.filter { it.state == UnitState.PENDING }.forEach {
                it.state = UnitState.ABORTED
                it.annotate("not started: ${wait.signal} deadline aborted the execution")
            }
            abort(execution, inProgress = unit, hold = null, cleanup = "not_applicable", requested = false)
        }
    }
    return true
}

internal fun Middleware.describe(reading: NamedSignal?): String =
    if (reading == null) NO_SIGNAL else "value=${reading.value} at=${reading.observedAt?.toString() ?: "(read now)"}"

/** @return 단위가 종착했는가. */
internal fun Middleware.pumpFleetUnit(execution: Execution, unit: ExecutionUnit): Boolean {
    val handle = execution.transport!!
    // 미리 읽는 포트면 그 읽기를 받는다(§15.237). 읽는 중이면 이번 라운드에는 단위를 두고, 기한 넘게 답이 없으면 동기 읽기가 던지던
    // 것과 같이 다룬다. 끝난 시각은 그 읽기가 받은 시각이다 — 받아 든 시각으로 찍으면 근거 창이 그만큼 밀린다.
    val (status, at) = when (val read = fleet.prefetchedStatus(handle) ?: FleetRead.Read(fleet.status(handle), now(), now())) {
        FleetRead.Pending -> return false
        is FleetRead.Overdue -> throw IllegalStateException("운반(${handle.id}) 상태 읽기가 ${read.since} 부터 답이 없다")
        is FleetRead.Failed -> throw read.failure
        is FleetRead.Read -> read.value to read.receivedAt
    }
    unit.observeHold(holdOf(status, unit))
    when (status.state) {
        TransportState.ACCEPTED, TransportState.PICKED_UP, TransportState.IN_TRANSIT -> {
            unit.note = null
            execution.physicalState = if (execution.cancelRequested) PhysicalState.CANCELING else PhysicalState.RUNNING
            return false
        }

        TransportState.WAITING_HANDOVER -> {
            // 목적지에 이전 용기가 남아 있다 — 인계 대기와 **지연 보고**. 임의의 다른 자리에 내려놓지 않는다.
            unit.note = WAITING_HANDOVER
            execution.physicalState = PhysicalState.RUNNING
            if (execution.noticed.note(Notice.Delay, unit.unitId)) {
                notify(execution)
            }
            return false
        }

        TransportState.DELIVERED -> {
            beginVerify(execution, unit, reachedByDownstream = Evidence.E1, doneAt = at)
            if (unit.state == UnitState.VERIFYING) return false
        }

        TransportState.REJECTED_AT_SOURCE -> {
            // 출발지의 용기가 요청과 다르다 — 인수하지 않고 불일치 보고. 상류(WMS)가 할당·현장 재고를 확인한다.
            unit.state = UnitState.FAILED
            unit.failureClass = SOURCE_MISMATCH
            unit.note = status.observedContainer?.let { "observed=$it at ${unit.source}" }
            execution.markIncident(unit)
        }

        TransportState.CANCELLED ->
            if (execution.cancelRequested) {
                unit.state = UnitState.ABORTED
            } else {
                holdUnrequestedCancel(execution, unit, "fleet=${TransportState.CANCELLED.name} ${status.detail.orEmpty()}".trim())
            }

        // 플릿 계약은 분류를 안 싣는다 — 프로젝트용 계약의 남은 자리이며 여기서는 UNCLASSIFIED 다.
        TransportState.FAILED -> failWithEvidenceCheck(
            execution, unit, UNCLASSIFIED, detail = "fleet=${TransportState.FAILED.name} ${status.detail.orEmpty()}".trim(), at = at,
        )
    }
    if (execution.cancelRequested) {
        abort(
            execution, inProgress = unit, hold = unit.hold,
            cleanup = if (status.holding) "failed" else "done",
        )
    }
    return true
}

private fun Middleware.holdOf(status: TransportStatus, unit: ExecutionUnit): HoldState =
    if (status.holding) {
        HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_HOLDING).setObjectRef(status.observedContainer ?: unit.unitId).build()
    } else {
        HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_EMPTY).build()
    }

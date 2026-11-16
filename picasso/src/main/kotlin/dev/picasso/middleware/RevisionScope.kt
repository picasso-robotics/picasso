package dev.picasso.middleware

import dev.picasso.middleware.MiddlewareCodes.REWORK_RECHECK

// 리비전이 작업 지시에서 항목을 뺄 때의 판정과 기록(§15.329). 리비전([revise])이 분기 자리 거부(§15.328) 바로 뒤, 다시 계획·사슬 검사·도는 단위의
// 갱신 송신보다 앞에서 한 번 판정하고, 받으면 단위 열을 갈아 끼울 때 지운다. 상태는 실행과 레지스터([RevisionRemovals])에 있다.

/** 리비전이 뺀 단위의 판정. */
internal sealed interface ScopeCut {
    /** 받지 않는다 — 실행은 그대로다. 사유는 «새 작업 지시로 낸다» 결이다. */
    data class Refused(val reason: String) : ScopeCut

    /** 받는다. [units] 는 지울 단위(실행 열의 순서), 비었으면 빼는 것이 없다. */
    class Removes(val units: List<ExecutionUnit>) : ScopeCut {
        val ids: Set<String> = units.map { it.unitId }.toSet()
    }
}

/**
 * **리비전이 뺀 단위를 판정한다**(§15.329). 뺀 것은 옛·새 작업 지시의 계획([LogicalCapability.plan])의 단위 id 차집합이다 — 실행 단위와 맞대지
 * 않는다. 실행 단위에는 승인된 조치 열의 걸음(§15.175)이 계획 밖에 서 있어, 맞대면 그 걸음이 «빠짐» 으로 잡힌다.
 *
 * 빠진 단위가 **모두 손대지 않은 것**([untouched])이면 받아 지운다. 하나라도 손댔으면(발행·끝남·보내는 중·시도가 오른 대기 등) 리비전 전체를
 * 거부한다 — 한 항목의 단위가 섞여도 그렇다. 지운 단위를 남는 단위가 선행(`after`)으로 가리키면 거부한다(엔진은 선행이 없으면 기다리지 않는다).
 * 선행도 함께 빠지거나 새 계획에서 관계가 없어지면 받는다. 연쇄로 지우거나 선행을 성공으로 치지 않는다. 항목을 모두 빼는 리비전은 거부한다 —
 * 그 길은 취소다.
 *
 * **받은 뒤의 실행 열**([inPlanOrder])에서 선행이 의존 단위보다 뒤에 서면 거부한다 — 빼는 것이 없는 리비전도 같다. 남은 단위의 상대 순서는
 * 그대로이고 새 단위만 새 계획에서 자기 뒤에 오는 남은 단위 앞에 끼므로, 남는 것은 남은 단위끼리 선행이 뒤집힌 경우다.
 *
 * 실행 상태를 바꾸지 않는다. 거부하면 리비전은 아무것도 바꾸지 않은 채 돌아간다.
 */
internal fun scopeCut(execution: Execution, order: JobOrder, prefix: List<ExecutionUnit>): ScopeCut {
    val fresh = execution.capability.plan(order)
    // 단위 id 가 겹치는 계획은 [revise] 가 바로 뒤에서 그 사유로 거부한다(`DUPLICATE_UNIT_ID`) — 겹친 id 로 열을 지어 다른 사유를 내지 않는다.
    if (fresh.map { it.unitId }.toSet().size != fresh.size) return ScopeCut.Removes(emptyList())
    val gone = execution.capability.plan(execution.order).map { it.unitId }.toSet() - fresh.map { it.unitId }.toSet()
    val removed = execution.units.filter { it.unitId in gone }
    val ids = removed.map { it.unitId }.toSet()
    // 받은 뒤 남는 실행 열 — 갈아 끼울 단위는 새 계획의 것(선행도)이고, 새 단위는 새 계획에서 자기 뒤의 남은 단위 앞이다. 승인 걸음은 선행을 들지 않는다.
    val replanned = fresh.associateBy { it.unitId }
    val remaining = inPlanOrder(
        execution.units.filter { it.unitId !in ids }.map { unit -> replanned[unit.unitId]?.takeIf { replaces(execution, unit) } ?: unit },
        fresh.filter { f -> execution.units.none { it.unitId == f.unitId } },
        fresh,
    )
    val late = remaining.withIndex().filter { (at, unit) -> remaining.indexOfFirst { it.unitId == unit.after } > at }.map { it.value }
    if (late.isNotEmpty()) {
        return ScopeCut.Refused(
            "리비전 뒤 선행이 의존 단위보다 뒤에 선다: ${late.joinToString { "${it.unitId} after ${it.after}" }} — 이미 낸 단위의 선행을 바꾸는 리비전이다. 새 작업 지시로 낸다",
        )
    }
    if (removed.isEmpty()) return ScopeCut.Removes(emptyList())
    if (fresh.isEmpty()) {
        return ScopeCut.Refused("리비전이 작업 지시의 항목을 모두 뺀다: ${removed.joinToString { it.unitId }} — 실행을 멈추려면 취소한다")
    }
    val touched = removed.filterNot { untouched(execution, it) }
    if (touched.isNotEmpty()) {
        return ScopeCut.Refused(
            "리비전이 손댄 단위를 뺀다: ${touched.joinToString { "${it.unitId}(${it.state.name})" }} — 발행했거나 끝난 단위가 든 항목은 뺄 수 없다. 새 작업 지시로 낸다",
        )
    }
    val dangling = (remaining + prefix).filter { it.after in ids }
    if (dangling.isNotEmpty()) {
        return ScopeCut.Refused(
            "리비전이 뺀 단위를 남는 단위가 선행으로 기다린다: ${dangling.joinToString { "${it.unitId} after ${it.after}" }} — 선행을 함께 빼거나 새 작업 지시로 낸다",
        )
    }
    return ScopeCut.Removes(removed)
}

/**
 * 리비전이 이 단위를 새 계획의 단위로 갈아 끼우는가 — [revise] 의 `kept` 갈래와 같은 판정이다. 아직 내지 않은 대기(보내는 중·맡기는 중·취소가 걸린
 * 붙기 전이 아님)와 계획 때 못 시작한 실패(`REWORK_RECHECK` 아님)다. 그 밖은 제자리에 남는다.
 */
internal fun replaces(execution: Execution, unit: ExecutionUnit): Boolean = when (unit.state) {
    UnitState.PENDING -> execution.command.inFlight?.unitId != unit.unitId && execution.command.fleetInFlight?.unitId != unit.unitId &&
        !(execution.cancelRequested && execution.unattached[unit.unitId] == unit.attempt)
    UnitState.FAILED -> unit.taskId.isEmpty() && unit.failureClass != REWORK_RECHECK
    else -> false
}

/**
 * **받은 리비전 뒤의 실행 열**(§15.329). 남은 단위([kept] — 제자리에 둔 것과 새 계획의 것으로 갈아 끼운 것)는 **상대 순서를 그대로** 두고, 새 단위
 * ([added])만 새 계획([fresh])에서 자기 뒤에 오는 첫 남은 단위 바로 앞에 끼운다(없으면 끝). 단위 id 가 바뀌는 리비전(`[X1, Y after X1]` →
 * `[X2, Y after X2]`)에서 새 선행이 끝에 붙어 의존 단위가 먼저 나가지 않게 하고, 설비 순서만 바꾼 리비전은 이미 선 열을 흔들지 않는다.
 */
internal fun inPlanOrder(kept: List<ExecutionUnit>, added: List<ExecutionUnit>, fresh: List<ExecutionUnit>): List<ExecutionUnit> {
    val keptIds = kept.map { it.unitId }.toSet()
    val result = kept.toMutableList()
    fresh.withIndex().filter { (_, unit) -> added.any { it === unit } }.forEach { (at, unit) ->
        val next = fresh.drop(at + 1).firstOrNull { it.unitId in keptIds }?.unitId
        result.add(result.indexOfFirst { it.unitId == next }.takeIf { it >= 0 } ?: result.size, unit)
    }
    return result
}

/**
 * 지운 단위에 걸린 막음을 걷는다(§15.329). 허가 막음([Execution.permitBlock])은 지운 단위의 것일 때. 결함 막음([Execution.blockedBy])과 그 알림
 * 표시, 결함이 세운 실행의 운영자 보류(단위 보류가 없을 때)는 **지운 뒤 `PENDING` 이 하나도 남지 않을 때만** — 그때만 막을 대상이 없다. 첫
 * `PENDING` 이 막힌 단위라는 보장이 없으므로(리비전이 순서를 바꿨을 수 있다) 대기가 남으면 그대로 두고 구동이 다시 계산한다.
 */
internal fun liftCutBlocks(execution: Execution, cut: ScopeCut.Removes) {
    if (execution.permitBlock?.let { it.unitId in cut.ids } == true) execution.permitBlock = null
    if (cut.units.isEmpty() || execution.blockedBy.isEmpty() || execution.units.any { it.state == UnitState.PENDING }) return
    execution.blockedBy = emptyList()
    execution.noticed.clear(Notice.Block)
    if (execution.physicalState == PhysicalState.OPERATOR_HOLD && execution.units.none { it.state == UnitState.OPERATOR_HOLD }) {
        execution.physicalState = PhysicalState.RUNNING
    }
}

/**
 * **손대지 않은 단위** — 지워도 기체·플릿·사람의 판단에 남는 것이 없다. 상태와 태스크 유무만으로 가르지 않는다.
 *
 * - `PENDING` 이면 시도 0, 태스크 없음, 앞 시도의 파지 없음, 보내는 중인 출발([CommandLifecycle.inFlight])·맡기는 중인 운반
 *   ([CommandLifecycle.fleetInFlight])이 아님, 재기동이 기체에서 본 붙기 전 태스크([Execution.unattached])가 아님, 판단 기록이 이 시도의
 *   요청 시각을 넘기지 않음([Execution.restoredRequests] — 앞 인스턴스가 낸 시도다).
 * - `FAILED` 이면 계획 때 못 시작한 실패 — 태스크가 없고 첫 발행 직전 재검사로 닫힌 재작업 시도(`REWORK_RECHECK`, §15.323)가 아니다.
 *
 * 그 밖의 상태(도는 중·미확정·검증 대기·운영자 보류·완료·미확인·중단·시도된 실패)는 손댄 것이다.
 */
internal fun untouched(execution: Execution, unit: ExecutionUnit): Boolean = when (unit.state) {
    UnitState.PENDING -> unit.attempt == 0 && unit.taskId.isEmpty() && !unit.heldInEarlierAttempt &&
        execution.command.inFlight?.unitId != unit.unitId && execution.command.fleetInFlight?.unitId != unit.unitId &&
        unit.unitId !in execution.unattached && execution.restoredRequests[unit.unitId]?.first != unit.attempt
    UnitState.FAILED -> unit.taskId.isEmpty() && unit.failureClass != REWORK_RECHECK
    else -> false
}

/**
 * 지운 뒤 남은 단위가 모두 종착했는가 — 그러면 리비전은 실행을 실행 중으로 되돌리지 않고 그 자리에서 종결 상태를 정산한다(§15.329). 운영자 보류가
 * 남았거나 취소가 걸렸거나 실행 수준의 막음(결함·허가·실행의 운영자 보류)이 [liftCutBlocks] 뒤에도 남았으면 정산하지 않는다 — 그 정착은 구동의 몫이다.
 */
internal fun settlesAfterCut(execution: Execution, cut: ScopeCut.Removes): Boolean =
    cut.units.isNotEmpty() && !execution.cancelRequested && execution.units.isNotEmpty() && execution.blockedBy.isEmpty() &&
        execution.permitBlock == null && execution.physicalState != PhysicalState.OPERATOR_HOLD &&
        execution.units.all { it.state in SETTLED_UNIT_STATES }

private val SETTLED_UNIT_STATES = setOf(UnitState.DONE, UnitState.FAILED, UnitState.UNVERIFIED, UnitState.ABORTED)

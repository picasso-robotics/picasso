package dev.picasso.middleware

import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.middleware.FailureClassNames.UNCLASSIFIED
import dev.picasso.middleware.Middleware.Submission
import dev.picasso.middleware.RefusalReasons.DUPLICATE_UNIT_ID

// 단위와 하류 응답을 읽는 순수 함수(재설계 R5, 설계 일지 §15.305). Middleware 의 상태를 읽지 않아 그 클래스의 멤버일 까닭이 없다 —
// 멤버로 두면 접합부 파일이 부르려고 internal 로 열어야 한다.

/** [Middleware.DUPLICATE_UNIT_ID] 의 값. 이 파일이 `Middleware` 에 기대지 않게 여기 둔다. */
internal object RefusalReasons {
    const val DUPLICATE_UNIT_ID = "계획의 단위 id 가 겹쳐 받지 않는다"
}

/**
 * 보고서 11.3 — 능력은 최고 등급을 선언하고 요청은 요구 등급을 지정한다. 확인 수단이 없으면 제공 불가다.
 * 받아 놓고 UNVERIFIED 로 끝내는 것은 상류에 "될지도 모른다" 고 말한 셈이다.
 */
internal fun evidenceRefusal(order: JobOrder, capability: LogicalCapability): Submission.Rejected? =
    if (order.requiredEvidence > capability.maxEvidence) {
        Submission.Rejected(
            "요구 근거 등급 ${order.requiredEvidence} 은 ${capability.workMasterId} 의 최고 등급 ${capability.maxEvidence} 를 넘는다 — 확인 수단이 없다",
        )
    } else {
        null
    }

/**
 * 계획의 단위 id 가 겹치면 거부, 아니면 널(§15.227).
 *
 * 리비전은 단위 id 로 접고 인시던트는 단위 id 로 찾으며 작업 응답은 단위 id 로 완료·미완료를 적는다 — 겹치면 한쪽이 가려진다.
 * 대기 단위의 id 는 임무 정의의 노드 id 이고 반복 단위의 id 는 작업 지시의 설비 id 라, 둘이 같은지는 작업 지시가 와야 안다.
 * 그래서 검증기가 아니라 작업 수락([submit])과 리비전([revise])이 본다. **id 를 바꿔 피하지 않는다** — 태스크 id
 * `jobOrderId#unitId` 가 단위 id 를 싣고, 겹치지 않는 작업 지시의 id 는 그대로여야 한다.
 */
internal fun duplicateUnitIdRefusal(planned: List<ExecutionUnit>): Submission.Rejected? {
    val repeated = planned.groupingBy { it.unitId }.eachCount().filterValues { it > 1 }.keys
    if (repeated.isEmpty()) return null
    return Submission.Rejected(
        "$DUPLICATE_UNIT_ID: ${repeated.joinToString()} — 작업 지시의 설비 id 가 다른 단위의 id(임무 정의의 노드 id 등)와 같다",
    )
}

/** 사정을 덧붙인다 — 앞의 것을 지우지 않는다. 지연 이벤트·미확정 같은 사정은 종착 사유와 함께 남아야 한다. */
internal fun ExecutionUnit.annotate(message: String) {
    note = if (note.isNullOrBlank()) message else "$note; $message"
}

/** [annotate] 와 같되 이미 든 사정이면 다시 덧붙이지 않는다 — 라운드마다 되풀이되는 사정(보내지 못한 명령)이 사유를 덮지 않게. */
internal fun ExecutionUnit.annotateOnce(message: String) {
    if (note?.contains(message) != true) annotate(message)
}

/** 계약의 정준 분류 이름(접두사 없이). 결함이 없거나 분류가 비어 있으면 [UNCLASSIFIED]. */
internal fun canonicalClass(update: WatchTaskResponse): String =
    if (update.hasFault()) canonicalClassOf(update.fault) else UNCLASSIFIED

/** 하류가 말한 그대로 — 상태 이름, 모드 이름, 벤더 원문. 로그의 것이지 분기의 것이 아니다. */
internal fun downstreamDetail(update: WatchTaskResponse): String = buildString {
    append("downstream=").append(update.state.name)
    if (update.hasFault()) {
        if (update.fault.errorType.isNotBlank()) append(" error_type=").append(update.fault.errorType)
        if (update.fault.vendorDetail.isNotBlank()) append(" vendor=").append(update.fault.vendorDetail)
    }
}

internal fun TaskState.isTerminal(): Boolean = this == TaskState.TASK_STATE_SUCCEEDED ||
    this == TaskState.TASK_STATE_FAILED || this == TaskState.TASK_STATE_CANCELLED ||
    this == TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED ||
    // 사람이 와야 진행되는 것은 이 층에서 단위의 종착이다 — 운영자 판단은 상류의 것.
    this == TaskState.TASK_STATE_NEEDS_INTERVENTION || this == TaskState.TASK_STATE_RETRIABLE

/**
 * 낼 단위가 없을 때 실행이 정착할 상태 — 단위 상태들로만 정한다(`Middleware` 의 정착이 부른다, §15.320 에서 옮겼다). 운영자 보류가 하나라도
 * 있으면 보류, 전부 완료면 물리 완료, 전부 실패면 실패, 실패가 섞이면 부분, 미확인이 있으면 미확인, 중단이 있으면 중단이다.
 */
internal fun settledStateOf(units: List<ExecutionUnit>): PhysicalState {
    val states = units.map { it.state }
    return when {
        states.any { it == UnitState.OPERATOR_HOLD } -> PhysicalState.OPERATOR_HOLD
        states.all { it == UnitState.DONE } -> PhysicalState.PHYSICALLY_DONE
        states.all { it == UnitState.FAILED } -> PhysicalState.FAILED
        states.any { it == UnitState.FAILED } -> PhysicalState.PARTIAL
        states.any { it == UnitState.UNVERIFIED } -> PhysicalState.UNVERIFIED
        states.any { it == UnitState.ABORTED } -> PhysicalState.ABORTED
        else -> PhysicalState.PHYSICALLY_DONE
    }
}


package dev.picasso.middleware

import dev.picasso.contracts.v1.FailureClass
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.middleware.FailureClassNames.UNCLASSIFIED

// 결과 통보·사건 번들·이벤트 자취가 나눠 쓰는 정준 투영. 분류(canonicalClassOf)는 세 곳이 다 쓰고, 잔여 파지
// (residualHoldOf)는 통보와 번들이 쓴다. 결함 투영(faultDetailOf)은 번들만 쓰지만 분류를 canonicalClassOf 에서
// 받으므로 곁에 둔다. 한 클래스에 두면 다른 쪽이 그 클래스를 건너가 읽고, 두 벌로 두면 어느 날 한쪽만 는다(§15.115).
// 셋 다 상태가 없다.

/** [Middleware.UNCLASSIFIED] 의 값. 이 파일과 [canonicalClassOf] 를 쓰는 하위 파일이 `Middleware` 에 기대지 않게 여기 둔다. */
internal object FailureClassNames {
    const val UNCLASSIFIED = "UNCLASSIFIED"
}

/**
 * 결함 하나를 번들이 드는 모양으로 — **정준 분류와 벤더 원문을 함께**(§15.177).
 *
 * **새로 판단하지 않는다.** 분류는 [canonicalClassOf] 가 이미 매긴 것이고 나머지는 계약이 실어 준
 * 값을 옮기는 것뿐이다. 상류 통보는 여전히 분류만 낸다 — 그쪽은 계약 소비자가 분기할 값이고
 * 이쪽은 사람이 원인을 말할 재료라, 성질이 다르므로 싣는 것도 다르다.
 */
internal fun faultDetailOf(fault: Fault): FaultDetail = FaultDetail(
    failureClass = canonicalClassOf(fault),
    errorType = fault.errorType,
    vendorDetail = fault.vendorDetail,
    errorHint = fault.errorHint,
    references = fault.referencesList.map { FaultReference(it.key.name, it.value) },
    canContinueCurrentTask = fault.canContinueCurrentTask,
    canAcceptNewTask = fault.canAcceptNewTask,
    activeUntilKind = fault.activeUntil.kind.name,
    activeUntilTime = fault.activeUntil.until,
)

internal fun canonicalClassOf(fault: Fault): String =
    fault.failureClass
        .takeIf { it != FailureClass.FAILURE_CLASS_UNSPECIFIED && it != FailureClass.UNRECOGNIZED }
        ?.name?.removePrefix("FAILURE_CLASS_")
        ?: UNCLASSIFIED

/** 든 단위가 있으면 그것, 없으면 마지막으로 관측한 파지(빈손) — "빈손" 을 "말하지 않았다" 로 접지 않는다(§15.145). */
internal fun residualHoldOf(units: List<ExecutionUnit>): HoldState =
    units.lastOrNull { it.hold.kind == HoldKind.HOLD_KIND_HOLDING }?.hold
        ?: units.lastOrNull { it.hold.kind != HoldKind.HOLD_KIND_UNSPECIFIED }?.hold
        ?: HoldState.getDefaultInstance()

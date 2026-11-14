package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.ForceFaultRequest

// **미들웨어가 쥔 실행 없이 발신자에 잔여 파지가 남은 기체** — 조치 제안을 세우는 시험·시연이 함께 쓰는 장면(§15.319).
//
// 앞 판의 시험은 «든 채 도는 랙 A 뒤에 순회 B 를 낸다» 로 제안을 세웠다. 그 제안은 A 가 든 물건을 B 의 조치가 처분하는 길이라
// §15.319 가 막았다 — 남이 쥔 기체에는 근거가 도는 단위의 관측이든 기체 스냅숏이든 제안을 세우지 않는다. 그래서 장면을 다시 짓는다.
// 든 채 실패한 A 가 운영자 보류에 서고(사건이 열린다), 사람이 그 실행을 취소로 닫는다. 실행은 정착해 기체를 놓고, 손은 발신자에
// 든 채로 남는다. 이 뒤에 낸 B 는 기체 스냅숏의 손으로 거절되고 제안이 선다(§15.316).

/**
 * 든 채 실패해 운영자 보류에 선 랙. 도는 단위가 없고 기체는 든 채이며, 보류 실행이 아직 기체를 쥔다.
 *
 * @param exec 든 채가 될 랙 실행. 이미 접수된 것이다.
 * @param errorType 밀어 넣을 결함. 기본은 새 접수를 막지 않는 실패다. 벤더 이름공간의 결함을 실어야 하는 한 벌이 바꾼다.
 * @param drive 그 세계의 구동 — 조건이 설 때까지 펌프와 시계를 돌린다.
 */
internal fun Middleware.holdOnFailure(
    exec: Middleware.Execution,
    harness: Harness,
    errorType: String = "SKILL_EXECUTION_FAILED",
    drive: (until: () -> Boolean) -> Unit,
): Middleware.Execution {
    val first = exec.units.first()
    drive { first.hold.kind == HoldKind.HOLD_KIND_HOLDING }
    harness.oracle.forceFault(
        ForceFaultRequest.newBuilder().setRobotId(exec.robotId).setErrorType(errorType).setTaskId(first.taskId).build(),
    )
    drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }
    check(exec.active == null) { "도는 단위가 남았다 — 이 장면이 아니다" }
    return exec
}

/**
 * 보류 실행을 사람이 **취소로 닫는다** — 실행이 든 채 정착해(`ABORTED`) 기체를 놓는다. 발신자의 손은 바꾸지 않는다.
 *
 * «완료 확인» 으로 닫지 않는다. 그것은 하지 않은 일을 했다고 적는 것이다.
 */
internal fun Middleware.closeByCancel(exec: Middleware.Execution, drive: (until: () -> Boolean) -> Unit): Middleware.Execution {
    check(cancel(exec.executionId)) { "보류 실행 ${exec.executionId} 의 취소가 안 받아졌다" }
    drive { exec.physicalState.isSettled }
    check(!exec.physicalState.occupiesRobot) { "닫은 실행이 아직 기체를 쥔다: ${exec.physicalState}" }
    return exec
}

/** [holdOnFailure] 뒤 [closeByCancel] — 미들웨어가 쥔 실행 없이 손만 든 채로 남은 기체. */
internal fun Middleware.leaveHolding(
    exec: Middleware.Execution,
    harness: Harness,
    errorType: String = "SKILL_EXECUTION_FAILED",
    drive: (until: () -> Boolean) -> Unit,
): Middleware.Execution = closeByCancel(holdOnFailure(exec, harness, errorType, drive), drive)

/**
 * 실행이 기체를 **놓게** 한다 — 도는 단위가 없어지고 정착하거나 보류에 설 때까지 돌리고, 아직 구동이 돌리면(보류·`PARTIAL`) 취소로 끝낸다.
 * 같은 기체에 다음 제안을 세우는 시험이 쓴다. 기체를 쥔 실행이 남아 있으면 다음 거절에 제안이 서지 않는다(§15.319).
 */
internal fun Middleware.letGo(exec: Middleware.Execution, drive: (until: () -> Boolean) -> Unit): Middleware.Execution {
    drive { exec.active == null && (exec.physicalState.isSettled || exec.physicalState == PhysicalState.OPERATOR_HOLD) }
    if (exec.physicalState.pumps) {
        check(cancel(exec.executionId)) { "실행 ${exec.executionId} 의 취소가 안 받아졌다" }
        drive { exec.physicalState.isSettled }
    }
    check(!exec.physicalState.occupiesRobot) { "실행이 아직 기체를 쥔다: ${exec.physicalState}" }
    return exec
}

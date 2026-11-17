package dev.picasso.middleware

import dev.picasso.contracts.v1.RejectionCode

// 제어자 세대 거절의 소비(ADR 54) — 명령 생애(`CommandLifecycle`)의 확장이다. 수신자가 답한 `STALE_CONTROLLER` 를 일반 실패보다 먼저 받아
// «보내지 않음»·«닿지 않음» 으로 접고, 그 실행의 자취에 한 번 남긴다. 밀려남이면 발행 관문에 적어 그 기체의 새 발행과 미확정 유예·인시던트
// 판단을 멈추고, 밀려남이 아니면 같은 단위의 다시 내기를 늦출 뿐 정상 경로를 막지 않는다.

/**
 * **수신자가 이 제어자 세대를 거절했다**(`STALE_CONTROLLER`, ADR 54) — 그 답은 일반 거절로 읽지 않는다. 출발 셋(첫 출발·개정 갱신·미확정
 * 조회)의 정산 자리가 일반 실패 처리보다 먼저 부른다. 단위 실패·재작업·인시던트·회복 제안으로 가지 않게 답을 «보내지 않음» 으로 접되
 * ([Issue.NotSent.refusal] 에 거절을 싣는다 — 수신자는 벤더도 장부도 건드리지 않았다), 그 거절을 실행 자취에
 * [MiddlewareCodes.CONTROLLER_REFUSED] 로 한 번 남긴다.
 *
 * - **밀려남**(`BELOW_FLOOR`)이면 발행 관문에 적는다 — 관문이 그 기체의 새 발행을 막고 그 기체의 미확정 유예·인시던트 판단이 멈춘다([fenceHeld]).
 *   포트가 보내지 않고 던진 것이 밀려남 때문이면([RobotPort.controllerRefusal]) 그 사실도 같이 적는다.
 * - **밀려남이 아니면**(세대 0·범위 밖·자격 없음) 그 명령만 거절된 설정 오류다. 정상 경로(유예 → 운영자 보류 등)를 막지 않고, 같은 단위의
 *   출발·개정 갱신 다시 내기만 조회와 같은 간격으로 늦춘다([refusedStart]).
 */
internal fun CommandLifecycle.fenced(robot: String, unitId: String, issue: Issue): Issue {
    val rejection = (issue as? Issue.Answered)?.response?.takeIf { it.hasRejection() }?.rejection
    if (rejection?.code == RejectionCode.REJECTION_CODE_STALE_CONTROLLER) {
        val refusal = refusalOf(robot, rejection)
        noteRefusal(refusal)
        if (!refusal.superseded) refusedStart = unitId to refusal.at
        return Issue.NotSent(refusal.line, refusal)
    }
    if (issue is Issue.NotSent && issue.refusal == null) env.robots.controllerRefusal(robot)?.let { noteRefusal(it) }
    return issue
}

/** 취소의 같은 소비([fenced]) — 세대 거절은 «닿지 않음» 이다. */
internal fun CommandLifecycle.fencedCancel(robot: String, issue: CancelIssue): CancelIssue {
    val rejection = (issue as? CancelIssue.Answered)?.response?.takeIf { it.hasRejection() }?.rejection
    if (rejection?.code == RejectionCode.REJECTION_CODE_STALE_CONTROLLER) {
        val refusal = refusalOf(robot, rejection)
        noteRefusal(refusal)
        return CancelIssue.Failed(CommandNotSent(refusal.line, refusal))
    }
    if (issue is CancelIssue.Failed && issue.failure is CommandNotSent) env.robots.controllerRefusal(robot)?.let { noteRefusal(it) }
    return issue
}

/** 받은 거절. 시각은 미들웨어가 그 답을 받아 정산한 시각이다 — 잠금 밖에서 보낸 출발이면 실제 수신보다 늦을 수 있다(라운드 머리). */
private fun CommandLifecycle.refusalOf(robot: String, rejection: dev.picasso.contracts.v1.Rejection) = ControllerRefusal(
    robot, dev.picasso.contracts.wire.StaleReason.of(rejection.requestedControllerEpoch, rejection.controllerEpochFloor),
    rejection.requestedControllerEpoch, rejection.controllerEpochFloor, env.now(),
)

/** 거절을 관문에 적고(밀려남만 남는다) 이 실행의 자취에 한 번 남긴다 — «보내지 않음» 과 갈라 그 거절 자체(까닭·요청 세대·바닥값·시각)를 싣는다. */
private fun CommandLifecycle.noteRefusal(refusal: ControllerRefusal) {
    with(env) {
        issuing.noteRefusal(refusal)
        if (refusalsTrailed.add("${refusal.robotId}|${refusal.reason}|${refusal.floor}")) execution.trail(MiddlewareCodes.CONTROLLER_REFUSED, refusal.line)
    }
}

/** [unit] 의 조회가 받은 세대 거절 사유를 비운다 — 조회가 풀렸거나(답·부착) 보류로 갔거나 새 시도가 시작됐다. */
internal fun CommandLifecycle.forgetRefusedLookup(unit: ExecutionUnit) {
    if (refusedLookup?.first == unit.unitId) refusedLookup = null
}

/** 이 기체에서 밀려났는가 — 그러면 미확정 유예·인시던트 판단을 멈춘다(ADR 54). 밀려남이 아닌 거절은 멈추지 않는다. */
internal fun CommandLifecycle.fenceHeld(robot: String): Boolean = env.issuing.refusalOf(robot) != null

/** 밀려남이 아닌 세대 거절을 받은 [unitId] 의 출발·개정 갱신을 아직 다시 내지 않는가 — 간격([MiddlewareCodes.LOOKUP_INTERVAL]) 안이면 참. */
internal fun CommandLifecycle.startBackingOff(unitId: String): Boolean {
    val (unit, at) = refusedStart ?: return false
    if (unit != unitId) return false
    if (env.now().isBefore(at.plus(MiddlewareCodes.LOOKUP_INTERVAL))) return true
    refusedStart = null
    return false
}

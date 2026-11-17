package dev.picasso.middleware

import java.time.Duration

/**
 * `Middleware` 의 companion 상수 가운데 명령 생애·접합부가 쓰는 것의 값(의존 정비 S4, 설계 변경 이력 §15.311). 뜻은 같은 이름의
 * `Middleware` 상수의 KDoc 에 있다 — 공개 이름은 그쪽이고 그것이 이 값을 가리킨다. 생애·접합부가 `Middleware` 를 거치지 않고
 * 이 값을 쓰게 바닥 층에 둔다.
 */
internal object MiddlewareCodes {
    const val MISMATCH = "VERIFICATION_MISMATCH"
    const val SOURCE_MISMATCH = "SOURCE_CONTAINER_MISMATCH"
    const val WAITING_HANDOVER = "WAITING_HANDOVER"
    const val FLEET_REJECTED = "FLEET_REJECTED"
    const val NO_SIGNAL = "no signal"
    const val RESUME_STALE_VERSION = "넘긴 작업 지시보다 높은 리비전을 기체가 들어 리빌드하지 않는다"
    const val FORGOTTEN_JOB_ORDER = "이미 끝나 이빅션한 작업 지시라 다시 받지 않는다"
    const val RESUME_ATTEMPT = "RESUME_ATTEMPT"
    const val RESUME_SIGNAL_PASSED = "RESUME_SIGNAL_PASSED"
    const val RESUME_PRIOR_ROBOT = "RESUME_PRIOR_ROBOT"
    const val RESUME_REATTACHED = "RESUME_REATTACHED"
    const val RESUME_RECORD_MISMATCH = "판단 기록이 이 작업 지시의 것이 아니어서 리빌드하지 않는다"
    const val RESUME_RECORD = "RESUME_RECORD"
    const val RESUME_RECORD_SUPERSEDED = "RESUME_RECORD_SUPERSEDED"
    const val RESUME_HOLD_REOPENED = "RESUME_HOLD_REOPENED"
    const val CANCEL_ATTACHED = "CANCEL_ATTACHED"
    const val CANCEL_UNATTACHED_WAIT = "CANCEL_UNATTACHED_WAIT"
    val LOOKUP_INTERVAL: Duration = Duration.ofSeconds(2)
    const val START_NOT_SENT = "start not sent; reissued after the gates"
    const val UPDATE_NOT_SENT = "revision update not sent; deferred"

    /** 수신자가 세대로 거절한 출발·갱신의 단위 메모(ADR 54). [START_NOT_SENT] 와 가른다 — 하위가 답했다. */
    const val FENCE_REFUSED = "refused by the receiver's controller-epoch fence; not applied, no failure judged"

    /** 세대 거절의 자취 종류(ADR 54). 같은 실행·기체·까닭·바닥값이면 한 번만 남긴다. */
    const val CONTROLLER_REFUSED = "CONTROLLER_REFUSED"
    const val DOUBT_ATTACHED = "DOUBT_ATTACHED"
    const val DOUBT_REVISION_AHEAD = "DOUBT_REVISION_AHEAD"
    const val WATCH_CLOSED = "WATCH_CLOSED"
    const val HOLD_UNSENT_CAUSE = "미확정이라 태스크 핸들이 없다"
    const val KEYED_UNSENT_CAUSE = "미확정이라 태스크 핸들이 없고 키로 보낸 취소도 기체가 받지 않았다"
    const val LINK_UNSENT_CAUSE = "연결이 끊긴 채 미확정 유예 안에 닿지 않았다"
    const val LOOKUP_UNSENT_CAUSE = "플릿에 맡긴 운반을 찾을 조회가 없다"
    const val UNATTACHED_UNSENT_CAUSE = "붙기 전에 건 취소가 미확정 유예 안에 답을 받지 못했다"
    val TRANSPORT_ENDED = setOf(TransportState.DELIVERED, TransportState.REJECTED_AT_SOURCE, TransportState.CANCELLED, TransportState.FAILED)
    const val CANCEL_UNSENT = "CANCEL_UNSENT"
    const val CANCEL_RESENT = "CANCEL_RESENT"
    const val ACKNOWLEDGED_FAULT_CLEARED = "ACKNOWLEDGED_FAULT_CLEARED"
    const val CANCEL_NOT_SENT = "CANCEL_NOT_SENT"
    const val ISSUE_WITHHELD = "ISSUE_WITHHELD"
    const val ISSUE_RESUMED = "ISSUE_RESUMED"
    const val PREDECESSOR_NOT_DONE = "PREDECESSOR_NOT_DONE"
    const val CLOCK_SKEW = "CLOCK_SKEW"

    /** 발신자 거부로 선 보류를 신선한 스냅숏의 손으로 다시 봤다는 실행 자취의 종류(§15.320). 세부에 답(찾음·못 찾음·위반 없음)이 든다. */
    const val HOLD_RECHECK = "HOLD_RECHECK"

    /** 사람이 승인한 회복 조치 열이 보류였던 단위 앞에 끼고 그 단위가 새 시도의 대기로 돌아갔다는 실행 자취의 종류(§15.320). */
    const val REMEDY_APPLIED = "REMEDY_APPLIED"

    /**
     * 회복 조치가 `DONE` 아닌 채 끝나 원래 단위를 내지 않고 운영자 보류로 세웠다 — 실패 분류이자 실행 자취의 종류(§15.320). 태스크가 없으므로
     * 하위 분류가 아니라 이 계층의 이름이다. 둘째 인시던트를 열지 않는다 — 거부 때 선 인시던트가 그 사건이다.
     */
    const val REMEDY_NOT_DONE = "REMEDY_NOT_DONE"

    /** 보류가 아닌 단위의 재작업을 받지 않았다는 실행 자취의 종류(§15.323). 세부에 사유와, 읽었으면 손 종류·자리 신호·읽은 시각이 든다. */
    const val REWORK_REFUSED = "REWORK_REFUSED"

    /**
     * 실행을 세울 때(작업 수락·리빌드) 계획이 고른 접수 시점 분기의 갈래를 적은 실행 자취의 종류(§15.328). 세부에 분기 자리마다 고른
     * 갈래(`arms[i]`·`otherwise`·값이 없어 접힌 `whenMissing`), 작업 지시 리비전, 임무 버전이 든다. 분기가 없으면 남기지 않는다.
     */
    const val BRANCH_CHOSEN = "BRANCH_CHOSEN"

    /**
     * 리비전이 작업 지시에서 항목을 빼 손대지 않은 단위를 실행에서 지웠다는 실행 자취의 종류(§15.329). 세부에 옛·새 작업 지시 버전과 지운 단위·그때의
     * 상태가 든다. 같은 사실이 감사 내보내기의 리비전 제거 사건([RevisionRemoval])으로도 남는다 — 자취는 판단 기록에 실리지 않는다.
     */
    const val REVISION_REMOVED = "REVISION_REMOVED"

    /** 재작업을 받았는데 판단이 붙을 인시던트가 묶음에 없었다는 실행 자취의 종류(§15.323). */
    const val REWORK_NOTED = "REWORK_NOTED"

    /**
     * 재작업 시도를 처음 내기 직전에 다시 읽은 손·자리가 물리 관문을 못 지나(또는 읽기를 기다린 창을 넘겨) 그 시도를 내지 않고 태스크 없이 `FAILED`
     * 로 닫았다 — 실패 분류이자 실행 자취의 종류(§15.323). 자취에는 «읽힐 때까지 기다린다» 줄도 이 종류로 한 번 선다. 태스크가 없으므로 이 계층의
     * 이름이다. 실패 인시던트를 연다. 재작업 묶음의 뿌리라 사람이 다시 `REWORK` 를 내면 물리 관문을 다시 지나 시도가 오른다. 확인은 받지 않는다.
     */
    const val REWORK_RECHECK = "REWORK_RECHECK"
    val OPEN_UNIT_STATES = setOf(UnitState.PENDING, UnitState.RUNNING, UnitState.VERIFYING, UnitState.IN_DOUBT, UnitState.OPERATOR_HOLD)
    val RETRY_SUFFIX = Regex("""@r([1-9]\d*)""")
}

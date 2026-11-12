package dev.picasso.middleware

/**
 * **실행 하나의 명령 생애**(재설계 R3, 설계 일지 §15.303). 출발·미확정·취소의 상태를 들고, 그 전이는 이 객체의 확장(`Lifecycle*.kt`)이
 * 한다. 확장은 하위 포트와 자취·통보를 [mw] 로 부른다(`with(mw)`). 라운드 안의 순서(송신 결과를 먼저 받고, 취소를 다시 보내고, 봉한
 * 뒤 통보한다)는 [Middleware.pumpRound] 가 정한다 — 생애 객체는 전이를 하고 순서는 부르는 쪽이 정한다(계획서 원칙 5).
 *
 * 앞에서는 이 상태가 [Execution] 의 필드였다. 실행의 읽는 이름(`cancelRequested`·`inFlight` 같은 것)은 이 객체에서 계산한다.
 */
internal class CommandLifecycle(internal val mw: Middleware, internal val execution: Execution) {

    // ── 취소의 네 축(§15.301, `CancelAxes.kt`). 축 안의 값은 배타이고 축끼리는 함께 선다.
    internal var cancelIntent: CancelIntent = CancelIntent.None
    internal var cancelDelivery: CancelDelivery = CancelDelivery.Clear
    internal var cancelWait: CancelWait = CancelWait.None
    internal var cancelSending: CancelSending = CancelSending.Idle

    /**
     * 보내는 중인 출발(§15.237). 포트가 잠금 밖에서 보내는 동안만 있다([RobotPort.issue]). 있는 동안 이 실행은 다른 로봇 명령을
     * 내지 않는다 — 취소는 요청만 남고, 개정의 갱신은 미뤄진다. 결과는 [Middleware.pumpRound] 머리(첫 출발·개정 갱신)나 [resolveDoubt]
     * (미확정 조회)가 받는다.
     */
    internal var inFlight: InFlight? = null

    /**
     * 보내는 중인 플릿 맡기기(§15.237). 포트가 잠금 밖에서 보내는 동안만 있다([AmrFleetPort.issueDispatch]). 결과는 라운드 머리(첫
     * 맡기기)나 [resolveDoubt] 머리(미확정 조회)가 받는다. 있는 동안 이 실행은 운반 취소를 내지 않는다 — 아직 운반 핸들이 없다.
     */
    internal var fleetInFlight: FleetFlight? = null

    /**
     * 개정이 도는 로봇 단위에 보내지 못한 갱신의 **직전 리비전**(기체에서 지금 도는 리비전). 널이면 미룬 갱신이 없다. 허가가 돌아오면
     * 라운드가 보낸다. 그 전에 옛 리비전으로 끝나면 보통의 지연 이벤트 길(새 판의 기대에 대고 설비로 확인)을 탄다.
     */
    internal var deferredUpdate: Int? = null
}

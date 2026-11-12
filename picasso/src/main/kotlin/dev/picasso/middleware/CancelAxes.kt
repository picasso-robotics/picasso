package dev.picasso.middleware

import java.time.Instant

// 취소의 네 축(재설계 R1, 설계 일지 §15.301). 한 실행의 취소는 아래 넷이 각각 한 값을 갖는다. 축 안의 값은 서로 배타이고,
// 축끼리는 함께 선다 — 예를 들어 보류에 묶인 취소([CancelWait.Held])를 다시 보내는 중([CancelSending])이면서 아직 닿지
// 않았다([CancelDelivery.Unsent]). 어느 조합이 실제로 서는지는 `docs/superpowers/plans/2026-11-12-middleware-r0-state-map.md`
// 의 상태 지도와 §15.301 에 적었다. 전이는 명령 생애 넷(Lifecycle*)과 Middleware 의 cancel·abort 가 한다.

/**
 * **취소 의도** — 상위가 건 취소와 그 뒤. [None] 에서 [Middleware.cancel] 이나 판단 기록([Middleware.restore])으로 [Requested] 가
 * 되고, 하위가 거부하면 [Refused], 실행이 중단으로 닫혀 취소 응답을 내면 [Reported] 다. 다시 건 취소는 거부를 지운다([Requested]).
 * [None] 이 아닌 값은 모두 «걸렸다» 이다([requested]).
 */
internal sealed interface CancelIntent {
    data object None : CancelIntent

    data object Requested : CancelIntent

    /** 하류가 진행 중 단위의 중단을 거절했다 — 그 단위는 끝까지 가고 다음 경계에서 멈춘다. [code] 는 거부 코드 이름이다. */
    data class Refused(val code: String) : CancelIntent

    /** 실행이 중단으로 닫혔고 [report] 를 냈다. 거부 사유는 [CancelReport.refusal] 에 들었다. */
    data class Reported(val report: CancelReport) : CancelIntent

    val requested: Boolean get() = this != None
}

/**
 * **닿음** — 건 취소가 하위에 닿았는지. 보내다가 포트가 던지면 [Unsent] 이고, 응답을 받으면(상태든 거부든) [Clear] 로 돌아간다.
 * [Clear] 는 «닿았다» 와 «아직 안 보냈다» 를 가르지 않는다 — 지금 코드가 그 둘을 다르게 다루지 않는다.
 */
internal sealed interface CancelDelivery {
    data object Clear : CancelDelivery

    /**
     * 건 취소가 하위에 **아직 닿지 않았다.** 서 있는 동안 도는 단위가 있으면 라운드 머리가 다시 보내고, 닿지 못한 채 단위가 끝나면
     * 취소 응답이 [Middleware.CANCEL_NOT_SENT] 를 싣는다.
     *
     * @property cause 자취에 남긴 마지막 까닭. 같은 까닭을 라운드마다 되풀이하지 않는다.
     * @property linkSince 연결이 끊긴 채([Execution.linkBroken]) 닿지 않은 것을 처음 본 시각(§15.243). 미확정 유예가
     *   이 시각부터 지나면 도는 단위를 운영자 보류에 둔다. 끊김이 풀리면 걷힌다. 판단 기록에는 싣지 않는다.
     */
    data class Unsent(val cause: String, val linkSince: Instant? = null) : CancelDelivery

    /** [cause] 로 닿지 않았다. 이미 닿지 않은 채였으면 끊김 시각([Unsent.linkSince])은 그대로 둔다. */
    fun unsent(cause: String): CancelDelivery = Unsent(cause, (this as? Unsent)?.linkSince)
}

/**
 * **기다림** — 취소가 정착하지 못하고 기다리는 것. 붙기 전에 건 취소가 기체·플릿의 답을 기다리면 [Attaching], 그 답이 유예 안에
 * 안 오거나 미확정·끊김 때문에 닿지 못해 단위를 운영자 보류에 뒀으면 [Held] 다.
 */
internal sealed interface CancelWait {
    data object None : CancelWait

    /**
     * 붙기 전에 건 취소가 답을 처음 기다린 시각(§15.255). 미확정 유예가 [since] 부터 지나면 기다리던 단위를 운영자 보류에 둔다.
     * 붙으면 걷힌다. [noted] 는 기다리는 까닭을 자취에 남긴 마지막 값이다. 판단 기록에는 싣지 않는다.
     */
    data class Attaching(val since: Instant, val noted: String? = null) : CancelWait

    /**
     * 취소가 닿지 못해 운영자 보류에 둔 단위(§15.238·§15.243·§15.255). 그 단위가 아직 운영자 보류인 동안 실행은 중단으로 닫지 않고
     * 운영자 판단을 기다린다. **운영자가 판단해도 걷지 않는다** — 그 단위가 보류가 아니면 아무것도 막지 않는다.
     */
    data class Held(val unitId: String) : CancelWait

    /** 붙었다 — 붙기 전 대기를 걷는다. 보류에 묶인 것([Held])은 그대로 둔다. */
    fun attached(): CancelWait = if (this is Attaching) None else this
}

/**
 * **보내는 중** — 포트가 잠금 밖에서 보내는 취소(§15.237). 도는 단위의 취소는 로봇과 플릿에 함께 나갈 수 있어 둘이 함께 선다.
 * 있는 동안 이 실행의 라운드는 그 결과를 받기 전까지 다른 일을 하지 않는다. 끝난 실행도 받아서 버린다(포트의 차선을 놓게 한다).
 */
internal sealed interface CancelSending {
    data object Idle : CancelSending

    data class Robot(override val robot: CancelFlight) : CancelSending

    data class Fleet(override val fleet: FleetCancelFlight) : CancelSending

    data class Both(override val robot: CancelFlight, override val fleet: FleetCancelFlight) : CancelSending

    val robot: CancelFlight? get() = null
    val fleet: FleetCancelFlight? get() = null

    fun withRobot(flight: CancelFlight?): CancelSending = of(flight, fleet)
    fun withFleet(flight: FleetCancelFlight?): CancelSending = of(robot, flight)

    companion object {
        fun of(robot: CancelFlight?, fleet: FleetCancelFlight?): CancelSending = when {
            robot != null && fleet != null -> Both(robot, fleet)
            robot != null -> Robot(robot)
            fleet != null -> Fleet(fleet)
            else -> Idle
        }
    }
}

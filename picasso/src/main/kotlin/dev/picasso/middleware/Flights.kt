package dev.picasso.middleware

import dev.picasso.contracts.v1.TaskHandle
import java.time.Instant

/**
 * 보내는 중인 취소 하나(§15.237, [Middleware.Execution.cancelInFlight]). 포트에 다시 물을 때 같은 기체와 핸들을 쓰고, 결과를 받으면
 * [site] 의 뒷일을 한다.
 *
 * @property unitId 도는 단위의 취소([Site.SEND])가 아니면 그 단위.
 * @property stateName 보류 중 다시 보내기([Site.HELD])·붙기 전의 취소([Site.ATTACH])면 보낼 때 스냅숏에 보인 태스크 상태.
 */
internal class CancelFlight(
    val site: Site,
    val robot: String,
    val handle: TaskHandle,
    val unitId: String? = null,
    val stateName: String? = null,
) {
    /** 도는 단위의 취소에서 같은 호출의 플릿 취소가 실패했는가. 그러면 로봇이 답해도 «닿았다» 로 내리지 않는다. */
    var otherFailed: Boolean = false

    enum class Site { SEND, KEY, HELD, ATTACH }
}

/** 보내는 중인 운반 취소 하나(§15.237, [Middleware.Execution.fleetCancelInFlight]). 포트에 다시 물을 때 같은 참조와 핸들을 쓴다. */
internal class FleetCancelFlight(val reference: String, val handle: TransportHandle) {
    /** 같은 호출의 로봇 취소가 실패했는가. 그러면 플릿이 답해도 «닿았다» 로 내리지 않는다. */
    var otherFailed: Boolean = false
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

package dev.picasso.middleware

/**
 * 셀 안 자리를 **누가 잡고 있는가**(§15.153).
 *
 * 상태 축이 파지 하나뿐이라 이 층은 «저 자리가 지금 쓰이는가» 를 몰랐다. 두 주문이 같은 슬롯을
 * 목적지로 삼아도 둘 다 접수됐고, 출발 자리가 비어 있어도 하달한 뒤 로봇이 빈 자리에서 집으려다 실패했다.
 *
 * **점유는 계약의 사전 조건이 될 수 없다.** 계약의 `PreconditionSubject` 는 *"조건이 보는 로봇 상태"* 이고
 * *"값이 느는 조건은 하나다: 그것을 **관측하는 어댑터**가 있어야 한다"* 고 적혀 있다. 슬롯 점유를 보는 것은
 * 어댑터가 아니라 셀 설비(`CellSignals`, E2)다. 그래서 이 축은 **이 층의 관문**이지 계약의 주어가 아니고,
 * 대안 탐색(선언된 능력 전이를 도는 것)의 상태 공간도 넓히지 않는다.
 *
 * @param material 그 자리에 놓일 자재. **아는 것을 놓는 자리만 점유다** — 아래 [SlotClaim] 을 만드는 쪽이 그것을 가른다
 */
data class SlotClaim(
    val location: String,
    val executionId: String,
    val jobOrderId: String,
    val material: String,
)

/**
 * 출발 자리를 하달 전에 본 결과.
 *
 * **셋으로 가른다.** 「비었다」와 「말이 없다」를 접으면 관측 경로가 죽었을 때 라인이 통째로 서고,
 * 「말이 없다」를 「있다」로 접으면 빈 자리로 로봇을 보낸다. `SlotSignal` 이 `null` 로 침묵을 말하는 것과
 * 같은 규율이다.
 */
sealed interface SourceCheck {

    /** 그 자재가 거기 있다. 또는 있다는 것까지는 설비가 말했다. */
    data object Ready : SourceCheck

    /** 설비가 말이 없다. **판정하지 않는다** — 없는 관측을 위반으로 세지 않는다. */
    data object Silent : SourceCheck

    /** 비었거나 다른 것이 있다. [observed] 가 널이면 빈 자리이고, 아니면 거기 있는 다른 신원이다. */
    data class Missing(val observed: String?) : SourceCheck

    /**
     * 단위가 관측을 요구했는데([SourceObservation.REQUIRED]) 판정할 관측이 없다. **결품이 아니다** — 없다는 관측도 없다.
     *
     * @param occupied 설비가 말한 것. 널이면 말이 없었고, 참이면 «있다» 고만 하고 무엇인지는 말하지 않았다
     */
    data class Unobserved(val occupied: Boolean?) : SourceCheck
}

/**
 * 목적지를 하달 전에 본 결과. 목적지 관측을 요구한 단위([DestinationObservation.REQUIRED])만 이 판정을 지난다.
 *
 * **셋으로 가른다.** 「차 있다」와 「말이 없다」를 접으면 운영자가 치울 것이 없는 자리를 찾아가고, 「말이 없다」를 「비었다」로
 * 접으면 선언한 뜻이 사라진다. [SourceCheck] 와 같은 규율이다.
 */
sealed interface DestinationCheck {

    /** 설비가 비었다고 말했다. */
    data object Free : DestinationCheck

    /**
     * 무엇이 있다. **식별 정보가 맞아도 점유다** — 출발 자리와 달리 목적지는 무엇이 있든 둘 자리가 없다. 같은 부품이 있으면
     * 앞선 작업 지시가 이미 둔 것일 수 있고, 거기에 또 두면 겹친다.
     *
     * @param observed 설비가 말한 식별 정보. 널이면 «있다» 고만 했다
     */
    data class Occupied(val observed: String?) : DestinationCheck

    /** 설비가 말이 없다. **비었다고 세지 않는다** — 없다는 관측도 없다. 다음 행동은 사람의 확인이다. */
    data object Unobserved : DestinationCheck
}

/** 점유 판정의 순수한 부분. 관측을 받아 답만 내며, 어디에 물을지는 부르는 쪽이 정한다. */
object CellOccupancy {

    /**
     * 이 출발 자리에서 집을 수 있는가.
     *
     * **요구가 없으면 판정하지 않는다** — 자재를 지정하지 않은 단위에 «다른 것이 있다» 를 말할 근거가 없다.
     * 설비가 «있다» 고만 하고 무엇인지 말하지 않은 경우도 같다: 있다는 것은 관측이고, 무엇인지는 아니다.
     *
     * [observation] 이 [SourceObservation.REQUIRED] 면 말이 없는 것과 신원 없는 «있다» 가 통과가 아니라
     * [SourceCheck.Unobserved] 다. 비었거나 다른 것이 있으면 선언과 무관하게 결품이다.
     */
    fun sourceCheck(
        signal: SlotSignal?,
        material: String?,
        observation: SourceObservation = SourceObservation.OPTIONAL,
    ): SourceCheck = when {
        // **관측을 요구한 단위만 침묵을 답으로 바꾼다.** 결품으로 세지 않는다 — 없다는 관측도 없다.
        signal == null -> if (observation == SourceObservation.REQUIRED) SourceCheck.Unobserved(null) else SourceCheck.Silent
        !signal.occupied -> SourceCheck.Missing(null)
        material.isNullOrEmpty() -> SourceCheck.Ready
        signal.identity == null -> if (observation == SourceObservation.REQUIRED) SourceCheck.Unobserved(true) else SourceCheck.Ready
        signal.identity != material -> SourceCheck.Missing(signal.identity)
        else -> SourceCheck.Ready
    }

    /**
     * 이 목적지에 둘 수 있는가. **부르는 쪽이 선언을 먼저 가른다** — [DestinationObservation.OPTIONAL] 인 단위는 여기 오지 않고
     * 설비에 묻지도 않는다. 그래서 침묵을 통과로 바꾸는 갈래가 이 함수에는 없다.
     */
    fun destinationCheck(signal: SlotSignal?): DestinationCheck = when {
        signal == null -> DestinationCheck.Unobserved
        signal.occupied -> DestinationCheck.Occupied(signal.identity)
        else -> DestinationCheck.Free
    }

    /**
     * 제시할 다른 자리. **이미 누가 잡은 자리는 빼고** 낸다 — 제시하자마자 관문에 걸릴 자리를 내면
     * 운영자가 같은 거절을 두 번 받는다.
     *
     * 셀이 답하지 않으면(`null`) 빈 목록이 아니라 **모른다**가 그대로 흐른다. 빈 목록으로 접으면
     * «그 자재를 든 자리가 하나도 없다» 로 읽힌다.
     */
    fun alternatives(known: List<String>?, taken: Set<String>, exclude: String): List<String>? =
        known?.filterNot { it == exclude || it in taken }
}

package dev.picasso.middleware

import dev.picasso.capability.PreconditionViolation
import dev.picasso.capability.Remedy
import dev.picasso.capability.RemedyStep
import java.time.Instant

/**
 * 관문이 막은 한 번에 대해 이 층이 **무엇을 답했는가.**
 *
 * 앞의 셋은 능력 사슬의 탐색이 낸 답이고([Found]·[None]·[Withheld]), 나머지는 셀 자리의 점유 관문이 낸 답이다 —
 * 출발 결품([SourceMissing]), 출발 미관측([SourceUnobserved]), 목적지 점유([DestinationOccupied]), 목적지 미관측
 * ([DestinationUnobserved]). 뒤의 셋은 판 8 에서 붙었다(§15.322) — 앞 판은 산문 사유로만 냈다. 둘을 한 대장에 두는 이유는 **접수 관문이 첫 거절에서 되돌아가기 때문**이다 —
 * 한 번의 접수는 많아야 한 번 거절되고, 그 거절에 대한 답은 하나뿐이다. 갈래마다 대장을 따로 두면
 * 읽는 쪽이 «이 주문이 왜 안 들어갔나» 를 알려고 여러 파일을 합쳐 봐야 한다.
 *
 * ## 승인할 수 있는 답은 [Found] 뿐이다
 *
 * 점유 관문의 답은 전부 걸음을 싣지 않고 제안 표에도 서지 않는다. 미관측 둘과 목적지 점유는 «사람이 확인하거나 치우면 통과한다»
 * 는 답이고, 확인은 셀의 관측으로 들어온다 — 이 층에 덮어쓰는 입구는 없다.
 *
 * [SourceMissing] 은 조치 열이 아니라 «주문을 고치면 통과한다» 는 답이다. **어느 자리를 쓸지는 주문을
 * 고치는 쪽의 결정이고 이 층은 고르지 않는다**(§15.159). 그래서 걸음을 싣지 않고 제안 표에도 서지 않는다 —
 * 실을 것이 없어서가 아니라 **승인이라는 행위가 성립하지 않아서**다.
 *
 * ## 세 「없음」을 접지 않는다
 *
 * 밖에서 보면 «제안이 없다» 하나가 셋을 덮는다 — **이 기체로는 안 된다**([None] 의 `NO_CAPABILITY`),
 * **더 찾아보면 있을 수 있다**([None] 의 `DEPTH_LIMIT`), **사람이 먼저 진단하라**([Withheld]). 셋은 다음
 * 행동이 다르다. 접으면 운영자가 의도적 비자동화를 고장으로 읽고, 상한에 걸린 것을 «없다» 로 읽는다
 * (설계안 §6.3 · §7.2 넷째).
 *
 * ## 가려 둔 것의 내용은 여기로 새지 않는다
 *
 * [Withheld] 가 걸음을 싣지 않는 것은 누락이 아니라 성질이다. 이 대장은 밖이 읽는 조회면이므로, 여기에
 * 걸음을 실으면 가려 둔 제안을 조회로 읽을 수 있고 그러면 가리는 일 자체가 무의미해진다.
 */
sealed interface RemedyOutcome {

    /** 여기까지 하면 그 스킬을 보낼 수 있다. */
    data class Found(val steps: List<RemedyStep>) : RemedyOutcome

    /** 못 찾았다. [cause] 가 «없다» 와 «덜 봤다» 를 가르고, [unmet] 이 끝내 못 채운 조건이다. */
    data class None(val cause: Remedy.None.Cause, val unmet: List<PreconditionViolation>) : RemedyOutcome

    /** 찾았으나 가렸다. **걸음을 싣지 않는다.** */
    data object Withheld : RemedyOutcome

    /**
     * 출발 자리에 그 자재가 없다. 깨진 전제는 능력이 아니라 **셀의 자리**이므로 탐색이 아니라 점유 관문이
     * 답한다 — 계약의 `PreconditionSubject` 는 «조건이 보는 로봇 상태» 이고 자리를 보는 것은 셀 설비다(§15.159).
     *
     * @param material 그 자리에 있어야 했던 것. 단위가 자재를 지정하지 않으면 널이고, 그때 [alternatives] 도 널이다
     * @param source 비어 있던 출발 자리
     * @param observed 거기서 **실제로 본 것**. 널이면 빈 자리이고, 아니면 거기 있는 다른 신원이다
     * @param alternatives 그 자재를 든 다른 자리. **널과 빈 목록이 다르다** — 널은 셀이 그 질문에 답하지
     *   않은 것이고, 빈 목록은 «든 자리가 하나도 없다» 는 답이다. 접으면 못 물어본 것이 재고 부족으로 읽힌다
     */
    data class SourceMissing(
        val material: String?,
        val source: String,
        val observed: String?,
        val alternatives: List<String>?,
    ) : RemedyOutcome

    /**
     * 목적지 관측을 요구한(`REQUIRED`) 단위의 목적지가 **차 있다**(§15.235, §15.322). 대체 자리를 계산하지 않는다 —
     * 어느 자리를 쓸지는 주문을 고치는 쪽이 정하고, 치울 것은 사람이 치운다.
     *
     * @param destination 차 있던 목적지
     * @param observed 거기 있는 신원. **널이면 설비가 «있다» 고만 했다**(점유 신원 불명) — 빈 문자열로 접지 않는다
     */
    data class DestinationOccupied(val destination: String, val observed: String?) : RemedyOutcome

    /** 목적지 관측을 요구한 단위의 목적지를 설비가 **말해 주지 않았다**(§15.322). 점유와 접지 않는다 — 말이 없는 것을 차 있다고 지어내지 않는다. */
    data class DestinationUnobserved(val destination: String) : RemedyOutcome
}

/**
 * 탐색 한 번. **새 사실을 만들지 않는다** — 이미 계산된 답을 그때의 두 시각과 함께 옮겨 실은 것이다(ADR 40).
 *
 * ## 열쇠가 사건이 아니라 (기체, 주문)인 이유
 *
 * 탐색은 **접수 관문 안**에서 돌고, 관문이 막으면 실행이 만들어지지 않는다. 실행이 없으면 실행 식별자도
 * 근거 창도 막는 결함도 없으므로 **거절은 사건 번들이 될 수 없다** — 그런 번들을 열면 창이 빈 것이
 * «창이 완전한데 아무 일도 없었다» 로 읽히고, 검토율의 분모도 거절 수만큼 늘어난다. 그래서 대장을 따로 둔다.
 *
 * 같은 (기체, 주문)이 거듭 거절될 수 있으므로 **덮어쓰지 않고 쌓는다.** 지금 서 있는 제안이 무엇인지는
 * 여전히 [Middleware.proposal] 과 [Middleware.withheldProposal] 이 답한다 — 이 대장은 **그때 무엇을
 * 답했는가** 의 기록이지 지금 상태가 아니다.
 */
data class RemedySearchRecord(
    /** `search-N`. **세는 수다** — 같은 시드면 같은 값이고, 조회 커서로 쓴다. 뜯어 읽지 않는다. */
    val searchId: String,
    val robotId: String,
    val jobOrderId: String,
    /** 가상 시계. 재현의 기준이다. */
    val at: Instant,
    /** 실 시계. 현장 대조의 기준이다. */
    val wallClockAt: Instant,
    val outcome: RemedyOutcome,
)

/**
 * 출발 자리를 **확인하지 못해** 보내지 않았다 — 관측을 요구한 단위([SourceObservation.REQUIRED])의 답이다.
 *
 * **결품([RemedyOutcome.SourceMissing])과 접지 않는다.** 결품은 «없다» 는 관측이고 다음 행동이 주문을 고치는 것(대체
 * 자리)이다. 이것은 관측이 없다는 것이고 다음 행동이 **사람의 확인**이다 — 접으면 운영자가 재고를 의심하고 멀쩡한 자재를
 * 두고 주문을 고친다. 그래서 대체 자리를 계산하지 않는다.
 *
 * **확인은 이 층의 덮어쓰기 칸으로 들어오지 않는다.** 상위 시스템의 확인, 인계 시점 점검, 기록된 수동 확인 중 무엇이든
 * 셀 포트([CellSignals.observe])가 그 자리의 신원을 답하는 것으로 들어오고, 같은 작업 요청을 다시 내면 관문이 그 관측으로
 * 판정한다. 일반 운영 해제로 이 답을 넘기는 길은 이 층에 없다.
 *
 * **조치 탐색 대장에 적는다**(§15.322). 대장의 답([RemedyOutcome])의 한 갈래이고 내보내기 줄의 `outcome` 은 `SOURCE_UNOBSERVED` 다 —
 * 걸음이 없고 승인할 조치가 아니다. 앞 판은 산문 사유로만 냈다(§15.218).
 *
 * @param material 그 자리에 있어야 할 것. 단위가 자재를 지정하지 않으면 널이다
 * @param source 확인하지 못한 출발 자리
 * @param occupied 설비가 말한 것. 널이면 말이 없었고, 참이면 «있다» 고만 했다(무엇인지는 모른다)
 */
data class SourceUnobserved(
    val material: String?,
    val source: String,
    val occupied: Boolean?,
) : RemedyOutcome

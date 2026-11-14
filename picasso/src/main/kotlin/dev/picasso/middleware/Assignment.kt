package dev.picasso.middleware

/**
 * 배정 관문의 답 — **통과인가 거부인가, 거부면 왜인가.**
 *
 * ## 관문은 고르지 않는다
 *
 * 순위를 정하는 것은 배정 정책의 일이고, 관문은 «이 기체가 이 주문을 받을 수 있는가» 하나에만 답한다.
 * 고르기 시작하면 그것은 관문이 아니라 배정기이고, 그 순간 정책이 이 층 안으로 들어온다.
 *
 * ## 관문은 상태를 바꾸지 않는다
 *
 * 순수 술어여야 **여러 후보에 대고 물어볼 수 있다.** 부작용이 있으면 후보 셋을 물어보는 것만으로
 * 제안이 셋 쌓이고 가림 차례가 세 칸 돌아간다 — 묻는 것이 곧 결정이 된다(§15.161).
 */
sealed interface Admission {

    /** 받을 수 있다. */
    data object Passed : Admission

    /**
     * 못 받는다. [rejection] 은 이 거부를 그대로 상류에 낼 때 쓰는 답이며, **제안의 부작용은 아직 없다** —
     * 기록은 채택을 시도한 쪽이 한다.
     *
     * @param occupancy 점유 관문이 **이미 계산한** 답 — 출발 결품([RemedyOutcome.SourceMissing]), 출발 미관측([SourceUnobserved]),
     *   목적지 점유·미관측([RemedyOutcome.DestinationOccupied]·[RemedyOutcome.DestinationUnobserved], §15.322). 널이면 이 거절은 그 갈래가 아니다.
     *   계산은 관문 안에서 하고 기록은 밖에서 한다 — 값을 여기 들려 보내도 [Middleware.admits] 는
     *   여전히 순수하고, 후보 셋에 물어봐도 대장에 셋이 쌓이지 않는다(§15.161).
     */
    data class Refused(
        val rejection: Middleware.Submission.Rejected,
        val occupancy: RemedyOutcome? = null,
    ) : Admission {
        /** [occupancy] 가 출발 결품이면 그 답. 미배정([Unassigned.sourceMissing])이 후보마다 모은다. */
        val sourceMissing: RemedyOutcome.SourceMissing? get() = occupancy as? RemedyOutcome.SourceMissing
    }
}

/**
 * 배정 비용 — **정책은 데이터로 밖에, 평가는 안에서**(설계안 §7).
 *
 * 가중치를 코드에 박으면 현장마다 다른 값을 릴리스로 바꾸게 된다. 반대로 순위 계산을 통째로 밖에 두면
 * 결정 시점과 적용 시점이 갈라지고, 그 창은 통신 지연이 아니라 **읽는 자와 쓰는 자가 다르기 때문에**
 * 생긴다. 그래서 값은 밖에서 받고 계산은 tick 안에서 한다.
 *
 * **항은 이 층이 실제로 아는 것뿐이다.** 이동 거리나 배터리는 여기 없다 — 이 층은 좌표도 기체 내부도
 * 모른다(기종 비인지). 지어낸 항을 넣으면 가중치가 아무 의미 없는 수를 곱하게 된다.
 *
 * @param perLiveExecution 진행 중인 실행 하나마다 더하는 비용. 바쁜 기체를 뒤로 민다
 * @param whenHolding 지금 무언가 든 채면 더하는 비용. 든 기체는 사슬 검사에서 걸릴 확률이 높다
 */
data class AssignmentCost(val perLiveExecution: Int = 1, val whenHolding: Int = 10) {

    /** 비용이 낮을수록 앞이다. 같으면 기체 이름으로 가른다 — **재생이 결정적이어야 한다.** */
    fun rank(candidates: List<String>, live: (String) -> Int, holding: (String) -> Boolean): List<String> =
        candidates.distinct().sortedWith(compareBy({ of(live(it), holding(it)) }, { it }))

    fun of(liveExecutions: Int, holding: Boolean): Int =
        liveExecutions * perLiveExecution + if (holding) whenHolding else 0
}

/**
 * 순위 목록 전부가 관문에서 떨어졌다(거절만 받아 가는 후보는 통과했어도 채택하지 않았다, [trialPassed]) — **미배정으로 남는다.**
 *
 * 재계산을 요청하지 않는다. 요청하면 이 층이 중재자가 되고, 배정기와 관문 사이에 «다시 해 봐» 가 오가는
 * 고리가 생긴다. 후보마다의 사유를 함께 내는 이유는, 그것이 없으면 배정기가 **무엇을 고쳐야 하는지**
 * 모른 채 같은 목록을 다시 보내기 때문이다.
 *
 * **이 답이 서면 후보마다의 거절은 이미 대장과 제안 장부에 적혀 있다**([Middleware.adopt], §15.164). 능력 사슬의 탐색 결과(조치 열)는
 * 이 답에 싣지 않는다 — 후보마다 [Middleware.proposal]·[Middleware.withheldProposal]·[Middleware.remedySearches] 로 읽는다.
 *
 * @param sourceMissing 출발 결품으로 떨어진 후보마다 관문이 **이미 계산한** 답. 다른 사유로
 *   떨어진 후보는 여기 없다. 산문의 사유만 내면 대체 자리가 문장 안에 갇혀, 읽는 쪽이 한국어를 뜯어야 한다.
 *   대장에도 `SOURCE_MISSING` 줄로 적힌다
 * @param sourceUnobserved 출발 자리를 확인하지 못해 떨어진 후보마다 관문의 답([SourceUnobserved]). [sourceMissing] 과
 *   **접지 않는다** — 결품은 주문을 고칠 일이고 이것은 사람이 확인할 일이다. 대장에도 `SOURCE_UNOBSERVED` 줄로 적힌다(§15.322) — [Middleware.submit] 의 거절과 같다
 * @param trialPassed 거절만 받아 가는 후보([Middleware.adopt] 의 `trialOnly`) 가운데 **관문을 통과해 채택하지 않은** 기체, 순위 차례대로.
 *   [refusals] 에 없다 — 관문이 막지 않았고 담는 쪽의 정책이 막았다. 이 기체에는 실행도 제안도 서지 않았다(§15.317)
 */
data class Unassigned(
    val refusals: Map<String, String>,
    val sourceMissing: Map<String, RemedyOutcome.SourceMissing> = emptyMap(),
    val sourceUnobserved: Map<String, SourceUnobserved> = emptyMap(),
    val trialPassed: List<String> = emptyList(),
) : Middleware.Submission

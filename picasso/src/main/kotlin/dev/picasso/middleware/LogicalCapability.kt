package dev.picasso.middleware

import java.time.Duration

/**
 * 논리적 능력 — 상류 업무 단위의 계약을 원자 단위 열로 나눈다(보고서 3.1, 설계 §1.2).
 *
 * 이름은 보고서가 *프로젝트 정의*로 표시한 것을 그대로 쓴다(ADR 38 결정 6).
 * 조합은 미션 계층의 일이고 그것은 이 모듈 안이다.
 */
interface LogicalCapability {
    val workMasterId: String

    /** 이 능력이 제공할 수 있는 최고 근거 등급(보고서 11.3 규칙 1). */
    val maxEvidence: Evidence

    /**
     * 시간창 δ(보고서 12.2 — 능력 단위 설정 항목). 앞쪽은 옛 신호를 거르는 폭, 뒤쪽은
     * 보고 지연·폴링 지연·네트워크 지연의 합이다. **현장별로 설정**하는 값이며 여기는 기본값이다.
     */
    val evidenceWindow: EvidenceWindow get() = EvidenceWindow(before = Duration.ofSeconds(30), after = Duration.ofSeconds(15))

    /**
     * `IN_DOUBT` 에서 하류에 물을 수 없을 때 **물리 관측을 기다리는 유예**(보고서 12.3 셋째 행 — 무응답·설비 있음 → 잠정
     * 완료). 요청 시각부터 이만큼 설비를 보고, 그 안에 기대한 것이 나타나면 잠정 완료로, 아니면 그대로 운영자에게 세운다.
     * 시간창 δ 와 다른 값이다 — 저것은 보고와 신호의 어긋남이고 이것은 답 없는 하류가 일을 마치는 데 걸리는 시간이다.
     */
    val inDoubtGrace: Duration get() = Duration.ofSeconds(60)

    /**
     * **진행 정체를 사람에게 보이기까지의 유예.** 도는 단위의 진행률이 이만큼 안 움직이면 자취에 적고 상류에 알린다.
     *
     * **실패가 아니고 자동 조치도 없다** — 느린 것과 멈춘 것을 우리가 못 가르므로 판단은 사람이 한다. 이 층이 하는
     * 것은 *보이게* 하는 것까지다.
     *
     * ★**못 재는 기체에는 이 판정을 아예 안 한다**(계약 0.8.0 의 `ProgressBasis`). 진행률의 `0.0` 을 정체로 읽으면
     * 진행률을 안 내는 기종이 **언제나 멈춰 있는 것으로** 보이고, 그러면 운영자가 그 경보를 곧 무시하게 된다.
     * 그 구분을 읽는 소비자가 여기이며, 계약이 그 자리를 만든 이유다(§15.112).
     */
    val stallWindow: Duration get() = Duration.ofMinutes(5)

    /**
     * **도는 단위의 기체를 못 본 채 연결 끊김으로 판정하기까지의 유예.** 마지막으로 스냅숏을 읽은 뒤 이만큼 못 읽으면, 스냅숏이 끊김을
     * 말한 것과 같게 실행의 결과를 미확정으로 둔다(§15.242). 단위는 도는 중으로 남고 다시 읽히면 이어서 추적한다 — 재실행이 아니다.
     *
     * 한 번 못 읽은 것으로 판정하지 않는다. 호출 기한 한 번에도 판정과 복구가 오락가락하며 상류에 알림이 되풀이된다. **한 번도 못
     * 읽은 기체는 판정하지 않는다** — 스냅숏을 늘 못 내는 기체(§15.241)는 이 유예가 언제나 지나 있어 모든 단위가 미확정이 된다.
     * 현장 시간값([SiteTimings])에 들지 않는다. 현장마다 바꿀 소비자가 아직 없다(ADR 9).
     */
    val linkLossWindow: Duration get() = Duration.ofSeconds(30)

    /**
     * 이 능력이 **쓰고 싶은 선택 파라미터**(계약 §5.3 의 선택 필드).
     *
     * 필수와 다르다 — 로봇이 선언하지 않았으면 **안 보낸다.** 보내면 코어 키는 fail-closed 라 태스크가
     * `PARAMETER_INVALID` 로 거절되고(§5.3), 그러면 선택 필드 하나 때문에 그 기종에서 이 능력을 못 쓴다.
     * **실제로 갈린다** — 조사한 실물 중에 `pick_place` 를 들면서 `verify_grasp` 는 선언하지 않는 기종이 있다.
     * (어느 기종인지는 이 모듈이 알 자리가 아니다 — 게이트 7번이 그것을 막는다. `profile/profiles/` 를 보라.)
     *
     * **안 보냈다는 사실은 적는다**(§15.116). 조용히 빼면 *파지 확인을 요구했다* 와 *못 해서 안 했다* 가
     * 같아 보인다.
     */
    val preferredOptionals: Map<String, String> get() = emptyMap()

    /** 상류 요청을 원자 단위 열로. 계획이지 실행이 아니다. */
    fun plan(order: JobOrder): List<ExecutionUnit>

    /**
     * [plan] 이 이 작업 지시로 고른 **접수 시점 분기의 갈래**(§15.328) — 분기 자리 → 갈래 이름. 데이터 정의의 `choice` 노드만
     * 내고(`mission.DefinedCapability`), 분기가 없는 능력은 빈 맵이다.
     *
     * 엔진이 읽는 자리는 둘이다. 실행을 세울 때 트레이스 한 줄([MiddlewareCodes.BRANCH_CHOSEN])과, 리비전이 고른 갈래를 바꾸는지의
     * 대조([choiceRefusal]). 판단 기록·내보내기에는 싣지 않는다 — 같은 작업 지시 리비전과 실행이 쥔 임무 버전으로 다시 계획하면
     * 같은 갈래가 나온다.
     */
    fun choices(order: JobOrder): Map<String, String> = emptyMap()

    /** [choices] 의 자리마다 그 자리가 낸 단위 id. 리비전이 남은 자리의 단위 id 집합을 바꾸는지 본다([choiceRefusal]). */
    fun choiceUnits(order: JobOrder): Map<String, List<String>> = emptyMap()

    /**
     * 이 작업 지시를 계획할 수 없는 까닭 — 단위 id 겹침과 같은 결의 작업 수락 거부 사유다. 데이터 정의는 분기 자리가 겹치는 작업
     * 지시(분기가 든 그룹의 쓰임에 같은 설비 id 가 둘)에 사유를 낸다(§15.328). 그 밖에는 널이다.
     */
    fun planRefusal(order: JobOrder): String? = null
}

/** 실행을 세울 때와 리비전이 자리를 더할 때 남길 트레이스 한 줄의 세부([MiddlewareCodes.BRANCH_CHOSEN], §15.328) — 고른 가지, 작업 지시 리비전, 임무 버전. 없으면 널. */
internal fun choiceTrail(choices: Map<String, String>, order: JobOrder, missionVersion: Int?): String? =
    if (choices.isEmpty()) {
        null
    } else {
        choices.entries.joinToString { (at, arm) -> "$at=$arm" } + "; order revision ${order.version}; mission version ${missionVersion ?: "code"}"
    }

/** 리비전이 새로 연 분기 자리와 그 가지 — 옛 작업 지시에 없던 자리다(설비를 더한 리비전). 트레이스 한 줄로 남긴다. */
internal fun addedChoices(capability: LogicalCapability, current: JobOrder, order: JobOrder): Map<String, String> =
    capability.choices(current).keys.let { before -> capability.choices(order).filterKeys { it !in before } }

/**
 * 분기가 든 정의의 실행에서 리비전은 **분기 자리를 더하기만 한다**(§15.328). 거부하는 것은 넷이다.
 *
 * 1. 분기 자리가 겹치는 작업 지시([LogicalCapability.planRefusal])
 * 2. 옛 리비전의 자리를 하나라도 없애는 것 — 그 설비를 빼거나 그룹이 조건부 포함으로 접혀 자리가 사라지는 것. 끝났는지 묻지 않는다.
 *    없앤 자리는 비교에서 빠지므로, 다음 리비전이 같은 id 를 다른 가지로 다시 넣는 두 걸음 우회가 열린다. 끝난 자리라도 빼면
 *    리빌드의 다시 계획에 그 완료 단위가 없어 판단 기록 대조(`RESUME_RECORD_MISMATCH`)가 실행을 다시 짓지 못한다
 * 3. 남은 자리의 가지를 바꾸는 것 — 값이 없어 접힌 것(`whenMissing`)도 가지 하나다. 가지를 바꾸면 고른 가지가 실행 중에 바뀐다 — 옛 가지의
 *    단위가 손대지 않은 채라 리비전이 지울 수 있어도(§15.329) 분기 자리는 더하기만 한다
 * 4. 남은 자리가 내는 단위 id 집합을 바꾸는 것 — 가지는 같아도 단위 id 가 설비 속성에서 오면 리비전이 그 값으로 옛 단위를 빼고 다른 id 의
 *    단위를 들인다. 일반 규칙([scopeCut])은 손대지 않은 옛 단위면 받지만 분기 자리는 이 거부가 먼저다
 *
 * 새 자리(리비전이 더한 설비)만 받는다. 판정은 옛·새 작업 지시의 계획만으로 한다 — 실행 상태를 보지 않으므로 리빌드 뒤에도 같다.
 * 돌려주는 것은 거부 사유다 — 이 파일은 바닥 층이라 `Middleware.Submission` 을 모른다(`LayeringTest`).
 */
internal fun choiceRefusal(capability: LogicalCapability, current: JobOrder, order: JobOrder): String? {
    capability.planRefusal(order)?.let { return it }
    val before = capability.choices(current)
    val after = capability.choices(order)
    val dropped = before.keys.filter { it !in after }
    if (dropped.isNotEmpty()) {
        return "리비전이 분기 자리를 없앤다: ${dropped.joinToString()} — 분기가 든 실행의 리비전은 자리를 더하기만 한다. 새 작업 지시로 낸다"
    }
    val changed = before.keys.filter { after[it] != before[it] }
    if (changed.isNotEmpty()) {
        return "리비전이 분기의 가지를 바꾼다: " + changed.joinToString { "$it 받은 값=${after[it]}, 현재=${before[it]}" } + " — 새 작업 지시로 낸다"
    }
    val was = capability.choiceUnits(current)
    val now = capability.choiceUnits(order)
    val moved = before.keys.filter { was[it].orEmpty().toSet() != now[it].orEmpty().toSet() }
    if (moved.isEmpty()) return null
    return "리비전이 분기 자리의 단위 id 를 바꾼다: " + moved.joinToString { "$it 받은 값=${now[it].orEmpty()}, 현재=${was[it].orEmpty()}" } + " — 새 작업 지시로 낸다"
}

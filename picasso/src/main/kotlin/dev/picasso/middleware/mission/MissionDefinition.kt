package dev.picasso.middleware.mission

import dev.picasso.middleware.DestinationObservation
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.Route
import dev.picasso.middleware.SourceObservation

/**
 * 임무 정의 — WorkMaster 하나를 실행 단위로 펼치는 규칙을 **데이터로** 적은 것(ADR 50).
 *
 * 문서 하나가 WorkMaster 하나의 정의이고, 노드는 직선이다(분기·병렬은 없다). 노드는 셋이다 — 로봇 스킬(또는 플릿 운반)을
 * 부르는 단위 노드([UnitStep]), 설비 신호를 기다리는 대기 노드([WaitStep]), 설비 하나마다 안쪽 단위 노드 여럿을 차례로
 * 펼치는 반복 그룹([EachStep], §15.325). 그룹은 분기가 아니다 — 안쪽 노드는 늘 같은 순서로 펼쳐지고, 조건부 포함
 * ([UnitStep.requires]·[EachStep.requires])은 입력이 없을 때 실패 단위 하나로 접을 뿐이다. 단위 하나의 생애(9상태)와 보장
 * 전이는 엔진이 고정하고, 정의는 그 안쪽을 그리지 않는다.
 *
 * 이 모양은 [MissionDefinitionParser] 가 읽은 그대로다 — **모양과 형만 맞다.** 기한이 양수인지, 참조한 신호가 있는지
 * 같은 판정은 [MissionValidator] 의 일이고, 활성화는 그것을 통과해야 선다([InMemoryMissionCatalog]).
 *
 * @param schemaVersion 이 문서 모양의 버전. 지금은 1 뿐이다. 임무 버전(활성화마다 오르는 번호)과 다르다. §15.325 의 새 칸은
 *   버전 1 에 **더한 선택 칸**이다 — 그 칸을 안 쓰는 문서의 뜻과 검증 결과는 그대로다.
 * @param maxEvidence 이 정의가 제공할 수 있는 최고 근거 등급(E0~E2).
 * @param preferredOptionals 로봇이 선언하면 붙이는 선택 파라미터(`LogicalCapability.preferredOptionals`).
 */
data class MissionDefinition(
    val schemaVersion: Int,
    val workMasterId: String,
    val maxEvidence: Evidence,
    val preferredOptionals: Map<String, String>,
    val steps: List<MissionStep>,
)

/** 직선 노드 하나. [id] 는 정의 안에서 겹치면 안 된다 — 그룹 안쪽 노드의 id 도 같은 이름 공간이다. 검증기가 본다. */
sealed interface MissionStep {
    val id: String
}

/**
 * 단위 노드(`kind: "unit"`) — 작업 지시 설비 중 [forEach] 쓰임인 것마다 단위 하나. [once] 면 작업 지시마다 한 번이다.
 *
 * 값은 전부 [ValueSource] 로 적는다. 짝([pairWith])이 있으면 반복 중인 설비의 속성 값과 같은 값을 가진 다른 쓰임의 설비를
 * 찾는다. 짝이 없으면 단위는 계획 때 `FAILED` 이고 실패 분류는 [whenUnpaired] 다 — 시작도 못 하는 부족은 계획에서 드러난다.
 *
 * 그룹([EachStep]) 안쪽의 단위 노드는 [forEach]·[pairWith]·[whenUnpaired]·[once]·[requires]·[whenMissing] 을 갖지 않는다
 * (파서가 막는다). 반복과 조건은 그룹이 쥔다.
 *
 * @param skill 계약 카탈로그의 스킬 이름. [route] 가 [Route.FLEET] 면 플릿 계약의 운반 이름(`transport`)이다.
 * @param forEach 반복할 설비 쓰임. [once] 노드와 그룹 안쪽 노드는 널이다.
 * @param unitId 단위 id 의 출처. **반드시 값을 내야 한다**(파서가 막는다) — `ITEM_ID`(접미사 [ValueSource.suffix] 가능),
 *   `ORDER_ID`, 또는 [ValueSource.orElse] 가 그 둘 중 하나인 것. 작업 응답과 리비전이 단위 id 로 접으므로 비거나 갈리면 안 된다.
 * @param parameters 파라미터 이름 → 값 출처. 값이 없으면(대체값도 없으면) 그 파라미터는 빠진다.
 * @param sourceObservation 출발 자리의 관측을 요구하는가. 널이면 **선언하지 않은 것**이다 — [source] 와
 *   [expectedIdentity] 가 둘 다 있는 로봇 경로 노드는 선언이 없으면 검증기가 거부한다([MissionValidator]).
 * @param destinationObservation 목적지가 비었는지를 설비 관측으로 볼 것인가. 널이면 **선언하지 않은 것**이다 — [destination] 과
 *   [expectedIdentity] 가 둘 다 있는 로봇 경로 노드는 선언이 없으면 검증기가 거부한다([MissionValidator]).
 * @param once 작업 지시마다 한 번 평가한다([forEach] 와 배타). 그 쓰임의 설비가 하나도 없어도 한 번이다. 값은 작업 지시와
 *   `FIRST_*` 출처에서 온다.
 * @param route 단위의 경로. [Route.ROBOT](기본)·[Route.FLEET] 만 온다 — 설비 대기는 대기 노드의 몫이다.
 * @param requires 모두 값을 내야 이 노드가 정상으로 펼쳐지는 출처들(부재는 **널 기준**, 빈 문자열은 값이다). 비었으면 조건이 없다.
 * @param whenMissing [requires] 가 하나라도 값을 못 내면 이 노드를 접을 모양. [requires] 와 함께 온다.
 * @param after 같은 그룹의 **앞 형제** 노드 id. 해석기가 같은 항목에서 그 노드가 낸 단위 id 로 풀어 `ExecutionUnit.after` 에
 *   넣는다(§15.234). 그룹 밖·최상위 노드의 선행은 받지 않는다(검증기).
 */
data class UnitStep(
    override val id: String,
    val skill: String,
    val forEach: String?,
    val unitId: ValueSource,
    val parameters: Map<String, ValueSource>,
    val expectedIdentity: ValueSource?,
    val source: ValueSource?,
    val destination: ValueSource?,
    val pairWith: Pairing?,
    val whenUnpaired: String?,
    val sourceObservation: SourceObservation? = null,
    val destinationObservation: DestinationObservation? = null,
    val once: Boolean = false,
    val route: Route = Route.ROBOT,
    val requires: List<ValueSource> = emptyList(),
    val whenMissing: WhenMissing? = null,
    val after: String? = null,
) : MissionStep

/**
 * 반복 그룹(`kind: "each"`, §15.325) — [forEach] 쓰임의 설비마다 안쪽 단위 노드([steps])를 **차례로** 펼친다. 대상이 A·B 면
 * `A 의 1번 노드, A 의 2번 노드, B 의 1번 노드, B 의 2번 노드` 다.
 *
 * 그룹은 중첩되지 않고 안쪽에 대기 노드가 없다. 같은 forEach 의 최상위 노드 연속을 저절로 묶지 않는다 — 그러면 기존 문서의
 * 순서(노드마다 전체 반복)가 바뀐다.
 *
 * @param requires 항목마다 모두 값을 내야 안쪽 노드가 정상으로 펼쳐진다. 하나라도 없으면 [whenMissing] 대로 단위 하나로 접는다.
 */
data class EachStep(
    override val id: String,
    val forEach: String,
    val requires: List<ValueSource>,
    val whenMissing: WhenMissing?,
    val steps: List<UnitStep>,
) : MissionStep

/**
 * 조건부 포함의 접힘 모양 — 입력이 없으면 노드(그룹이면 [unit] 하나)만 `FAILED` 로 남는다.
 *
 * 남는 단위의 단위 id·경로·스킬·기대 식별·출발·목적지는 정상 해석 그대로다(값이 없으면 널). 파라미터는 [parameters] 에 든 이름만
 * 정상 해석 값으로 남고, 선행(`after`)은 널이다.
 *
 * @param failureClass 그 단위의 실패 분류. 비지 않는다.
 * @param unit 남길 안쪽 노드 id. 노드가 둘 이상인 그룹이면 필수이고, 그룹이 아니면 오지 않는다.
 * @param parameters 남길 파라미터 이름. 빈 목록이면 파라미터가 없다.
 */
data class WhenMissing(val failureClass: String, val unit: String?, val parameters: List<String>)

/**
 * 설비 대기 노드(`kind: "wait"`) — 이름 있는 신호가 [expect] 를 읽을 때까지 기다린다.
 *
 * [deadlineSeconds]·[onDeadline] 이 널일 수 있는 것은 **파서가 값을 판정하지 않기 때문이다.** 없거나 허용 밖인 것은
 * 검증기의 기한 검사가 거부한다 — 그래야 그 검사를 빼는 결함이 검증기 시험에 잡힌다.
 */
data class WaitStep(
    override val id: String,
    val signal: String,
    val expect: String,
    val deadlineSeconds: Long?,
    val onDeadline: String?,
) : MissionStep

/** 짝 규칙 — [equipmentUse] 쓰임의 설비 중 [property] 가 반복 중인 설비의 같은 속성과 같은 것. 여럿이면 목록에서 뒤엣것. */
data class Pairing(val equipmentUse: String, val property: String)

/**
 * 값 하나의 출처.
 *
 * @param property [ValueFrom.ITEM_PROPERTY]·[ValueFrom.FIRST_PROPERTY] 일 때 읽을 속성 이름. 다른 출처에서는 널이다.
 * @param otherwise 출처가 값을 못 낼 때(속성이 없다, 짝이 없다) 쓸 대체값. 널이면 값이 없다(`null`).
 * @param equipmentUse [ValueFrom.FIRST_ID]·[ValueFrom.FIRST_PROPERTY] 가 볼 설비 쓰임. 다른 출처에서는 널이다.
 * @param parameter [ValueFrom.ORDER_PARAMETER] 가 읽을 작업 지시 파라미터 이름. 다른 출처에서는 널이다.
 * @param suffix 값 뒤에 붙일 글자(`unitId` 에만 온다). 값이 없으면 붙이지 않는다.
 * @param orElse 출처가 값을 못 낼 때 대신 볼 출처 한 단계(JSON 의 `else`, `unitId` 에만 온다). `ITEM_ID`·`ORDER_ID` 뿐이다.
 */
data class ValueSource(
    val from: ValueFrom,
    val property: String? = null,
    val otherwise: String? = null,
    val equipmentUse: String? = null,
    val parameter: String? = null,
    val suffix: String? = null,
    val orElse: ValueSource? = null,
)

/**
 * 값 출처의 종류.
 *
 * - [ITEM_ID] — 반복 중인 설비의 id. 언제나 있다
 * - [ITEM_PROPERTY] — 반복 중인 설비의 속성. 그 속성이 없으면 값이 없다
 * - [PAIRED_ID] — 짝지은 설비의 id. 짝이 없으면 값이 없다
 * - [ORDER_ID] — 작업 지시 id. 언제나 있다
 * - [ORDER_PARAMETER] — 작업 지시 `parameters` 의 값. 그 이름이 없으면 값이 없다
 * - [FIRST_ID] — 그 쓰임의 **첫 설비**의 id. 그 쓰임이 없으면 값이 없다. `once` 노드에만 온다
 * - [FIRST_PROPERTY] — 그 쓰임의 첫 설비의 속성. 값을 가진 첫 설비가 아니다 — 첫 설비에 속성이 없으면 값이 없다. `once` 노드에만 온다
 */
enum class ValueFrom { ITEM_ID, ITEM_PROPERTY, PAIRED_ID, ORDER_ID, ORDER_PARAMETER, FIRST_ID, FIRST_PROPERTY }

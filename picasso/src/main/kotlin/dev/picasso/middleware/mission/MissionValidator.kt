package dev.picasso.middleware.mission

import dev.picasso.contracts.v1.SkillCatalog
import dev.picasso.middleware.DeadlineOutcome
import dev.picasso.middleware.DeliverContainer
import dev.picasso.middleware.FloorOwner
import dev.picasso.middleware.FloorOwnership
import dev.picasso.middleware.Route
import java.time.Instant

/**
 * 활성화 거부 하나 — **종류와 후속 행동**으로 보인다(운영 관리 화면 설계 제안 §8.4).
 *
 * 사유 텍스트만 주면 읽는 쪽이 문자열 대조에 기대므로 종류를 열거형으로 둔다. 후속 행동과 해결 담당은 종류가 정한다
 * ([MissionRefusalKind]) — 종류를 가르는 기준이 곧 후속 행동이기 때문이다.
 *
 * @param nodeId 막힌 노드. 정의 전체의 문제(읽을 수 없음)면 널이다.
 * @param observed 지금 값. 없는 것이 문제면 «없음» 이다.
 * @param expected 통과에 필요한 값.
 * @param checkedAt 마지막 확인 시각 — 활성화를 시도한 시각이다.
 * @param basisVersion 근거 버전 — 어느 버전의 현장 설정으로 판정했는가. 활성화 검사는 현장 설정 버전과 무관한 판정이라
 *   «해당 없음»(널)이다. 현장 설정에 버전이 생기면(S3c) 그 번호가 온다.
 */
data class MissionRefusal(
    val kind: MissionRefusalKind,
    val nodeId: String?,
    val observed: String,
    val expected: String,
    val checkedAt: Instant,
    val basisVersion: Int? = null,
) {
    /** 해결 담당 — 화면 안의 누구인가, 화면 밖인가. */
    val owner: RefusalOwner get() = kind.owner

    /** 바로 갈 작업 하나. */
    val nextAction: String get() = kind.nextAction
}

/** 거부를 푸는 쪽. */
enum class RefusalOwner {
    /** 화면 안의 시운전·통합 엔지니어 — 임무 버전 작성, 신호 사양, 기체 배치를 맡는다. */
    ENGINEER,

    /** 화면 밖 — 자원 소유 레지스터는 현장의 것이고 이 저장소 밖에 산다(§15.154). */
    OUTSIDE_CONSOLE,
}

/** 거부 종류. **후속 행동이 다르면 종류가 다르다.** */
enum class MissionRefusalKind(val owner: RefusalOwner, val nextAction: String) {
    /** 문서를 읽을 수 없다 — 모르는 키, 빠진 칸, 형이 틀린 값, 중복 키, 문법. */
    UNREADABLE(RefusalOwner.ENGINEER, "정의 JSON 의 틀린 칸을 고친다"),

    /** 노드 id 가 정의 안에서 겹친다. 리비전과 작업 응답이 단위 id 로 접으므로 겹치면 한쪽이 사라진다. */
    DUPLICATE_NODE_ID(RefusalOwner.ENGINEER, "노드 id 를 겹치지 않게 고친다"),

    /** 기한 검사 — 대기 노드에 양의 기한이 없거나 기한 뒤 상태가 허용 밖이다. */
    DEADLINE_INVALID(RefusalOwner.ENGINEER, "대기 노드의 기한 칸을 고친다"),

    /** 신호 검사 — 참조한 신호가 신호 사양에 없다. */
    SIGNAL_NOT_IN_SPEC(RefusalOwner.ENGINEER, "신호 이름을 고치거나 신호 사양에 더한다"),

    /** 신호 검사 — 기대 값이 그 신호의 종류로 읽힐 수 없는 값이다(참거짓 신호에 `true`·`false` 밖의 값). 영영 안 끝나는 대기다. */
    SIGNAL_VALUE_INVALID(RefusalOwner.ENGINEER, "기대 값을 신호 종류에 맞게 고친다"),

    /** 자원 검사 — 대기 신호의 자리에 바닥 소유자가 없다(`Unowned`). 관문과 같은 규칙이다. */
    FLOOR_UNOWNED(RefusalOwner.OUTSIDE_CONSOLE, "그 자리의 바닥 소유를 선언한다"),

    /** 스킬 검사 — 계약 카탈로그에 없는 스킬이다. */
    SKILL_NOT_IN_CONTRACT(RefusalOwner.ENGINEER, "스킬 이름을 계약 카탈로그의 이름으로 고친다"),

    /** 스킬 검사 — 계약에는 있으나 현장 기체가 제공하지 않는다. */
    SKILL_NOT_ON_SITE(RefusalOwner.ENGINEER, "그 스킬을 제공하는 기체를 현장에 둔다"),

    /** 안전 검사 — 안전 신호를 기다린다. 안전 기능은 이 소프트웨어 계약을 거치지 않는다(ADR 32). */
    SAFETY_SIGNAL_WAIT(RefusalOwner.ENGINEER, "대기 노드를 안전 신호가 아닌 신호로 바꾼다"),

    /**
     * 출발 관측 검사 — 출발 자리의 신원을 대는 단위 노드가 `sourceObservation` 을 선언하지 않았다. 설비가 말이 없을 때
     * 보낼지가 암묵으로 남는다.
     */
    SOURCE_OBSERVATION_UNDECLARED(RefusalOwner.ENGINEER, "단위 노드에 출발 관측을 REQUIRED 나 OPTIONAL 로 적는다"),

    /**
     * 출발 관측 검사 — 출발 자리의 신원을 대지 않는 단위 노드가 `sourceObservation` 을 선언했다(§15.227). 출발 자리가 없으면
     * 관문이 그 선언을 읽지 않고, 기대 신원이 없으면 `REQUIRED` 가 신원 대조 없이 설비의 침묵만 막는다. 어느 쪽이든 적은 뜻과
     * 도는 뜻이 다르다.
     */
    SOURCE_OBSERVATION_UNUSED(RefusalOwner.ENGINEER, "출발 관측 선언을 지우거나 출발 자리와 기대 식별 정보를 함께 적는다"),

    /**
     * 목적지 관측 검사 — 기대 식별 정보와 목적지를 둘 다 대는 단위 노드가 `destinationObservation` 을 선언하지 않았다(§15.235).
     * 작업 전 목적지 «있다» 를 물리적 점유로 볼지 옛 신호로 볼지가 암묵으로 남는다.
     */
    DESTINATION_OBSERVATION_UNDECLARED(RefusalOwner.ENGINEER, "단위 노드에 목적지 관측을 REQUIRED 나 OPTIONAL 로 적는다"),

    /**
     * 목적지 관측 검사 — 목적지나 기대 식별 정보가 없는 단위 노드가 `destinationObservation` 을 선언했다(§15.235). 목적지가 없으면
     * 게이트가 볼 자리가 없고, 기대 식별 정보가 없으면 그 목적지는 자재를 둘 자리가 아니라 갈 자리라 게이트가 선언을 읽지 않는다.
     * 어느 쪽이든 적은 뜻과 실제 동작이 다르다. 출발 관측의 [SOURCE_OBSERVATION_UNUSED] 와 같은 기준이다.
     */
    DESTINATION_OBSERVATION_UNUSED(RefusalOwner.ENGINEER, "목적지 관측 선언을 지우거나 목적지와 기대 식별 정보를 함께 적는다"),

    /**
     * 선행 검사 — 단위 노드의 `after` 가 같은 반복 그룹의 **앞 형제** 단위 노드를 가리키지 않는다(자기·뒤·그룹 밖·없는 노드·
     * 최상위 노드의 선행, §15.325). 엔진은 참조한 단위가 없으면 기다리지 않고 내므로 끊긴 선행은 조용히 순서를 잃는다.
     */
    AFTER_INVALID(RefusalOwner.ENGINEER, "after 를 같은 그룹의 앞 단위 노드 id 로 고치거나 지운다"),

    /**
     * 단위 id 모양 검사 — 같은 그룹의 두 단위 노드가 같은 모양(출처·접미사)의 단위 id 를 낸다. 항목마다 단위 id 가 겹쳐
     * 작업 수락이 늘 거부한다(`DUPLICATE_UNIT_ID`). 설비 id 가 우연히 겹치는 것은 작업 지시가 와야 알므로 실행 때 막는다.
     */
    UNIT_ID_SHAPE_COLLISION(RefusalOwner.ENGINEER, "한쪽 단위 id 에 다른 접미사를 붙인다"),

    /**
     * 플릿 경로 검사 — `route: FLEET` 단위 노드의 모양이 운반이 아니다. 스킬은 `transport` 뿐이고 출발·목적지·기대 식별 정보가
     * 다 있어야 하며, 관측 선언은 오지 않는다(배정 관문이 플릿 단위의 선언을 읽지 않는다).
     */
    FLEET_UNIT_INVALID(RefusalOwner.ENGINEER, "플릿 노드를 transport 와 출발·목적지·기대 식별 정보로 적고 관측 선언을 지운다"),

    /**
     * 조건부 포함 검사 — `whenMissing` 이 남길 노드를 모르거나(노드가 둘 이상인 그룹에 `unit` 이 없다, 없는 노드다) 남길 파라미터
     * 이름이 그 노드의 파라미터가 아니다.
     */
    WHEN_MISSING_INVALID(RefusalOwner.ENGINEER, "whenMissing 의 unit 과 parameters 를 그 그룹의 노드와 파라미터 이름으로 고친다"),
}

/**
 * 검증기 — 임무 정의가 활성화될 수 있는가. **라이브러리다** — 같은 규칙으로 화면이 저장 전에 막고 활성화가 막는다.
 *
 * 검사는 열하나이고(기한·신호·자원·스킬·안전·출발 관측·목적지 관측, §15.325 의 선행·단위 id 모양·플릿 경로·조건부 포함) 그 앞에
 * 노드 id 중복을 본다. §15.325 의 네 검사는 버전 1 에 더한 칸에만 걸린다 — 그 칸을 안 쓰는 문서의 결과는 그대로다. **모든 거부를 모아 낸다** — 하나만 알리면
 * 고치고 다시 내고 또 거부되는 왕복이 생긴다.
 *
 * 함수 이름은 관문의 판정 이름과 겹치지 않게 둔다 — 자리 대조 시험이 이름으로 판정의 자리를 찾는다.
 *
 * ## 보지 않는 것
 *
 * - **파라미터 이름.** 오타는 실행 때 하위가 `PARAMETER_INVALID` 로 거부한다
 * - **작업 지시에서 오는 자리**(슬롯·제시 자리). 정의에 고정으로 적힌 것이 아니라 실행 때 관문이 본다
 * - **작업 지시의 설비 id 와 대기 노드 id 의 겹침.** 설비 id 는 작업 지시가 오기 전에는 모른다. 작업 수락과 리비전이
 *   계획의 단위 id 겹침으로 막는다(`Middleware.DUPLICATE_UNIT_ID`)
 */
object MissionValidator {

    /**
     * @param signals 현장 신호 사양.
     * @param floors 바닥 소유. 관문이 받는 것과 같은 것을 준다.
     * @param siteSkills 현장 기체들이 제공하는 스킬 이름의 합.
     * @param at 활성화를 시도한 시각 — 거부의 마지막 확인 시각이 된다.
     */
    fun validate(
        definition: MissionDefinition,
        signals: List<SignalSpec>,
        floors: FloorOwnership,
        siteSkills: Set<String>,
        at: Instant,
    ): List<MissionRefusal> {
        val byName = signals.associateBy { it.name }
        return duplicateNodeIds(definition, at) +
            deadlineRefusals(definition, at) +
            signalRefusals(definition, byName, at) +
            floorRefusals(definition, byName, floors, at) +
            skillRefusals(definition, siteSkills, at) +
            safetyRefusals(definition, byName, at) +
            sourceObservationRefusals(definition, at) +
            destinationObservationRefusals(definition, at) +
            afterRefusals(definition, at) +
            unitIdShapeRefusals(definition, at) +
            fleetRefusals(definition, at) +
            whenMissingRefusals(definition, at)
    }

    /** 단위 노드 전부 — 최상위의 것과 반복 그룹 안쪽의 것, 정의 순서대로. 해석기도 같은 목록을 본다. */
    fun unitNodes(definition: MissionDefinition): List<UnitStep> = definition.steps.flatMap { step ->
        when (step) {
            is UnitStep -> listOf(step)
            is EachStep -> step.steps
            is WaitStep -> emptyList()
        }
    }

    /**
     * 이 단위 노드가 출발 관측 선언을 **요구하는가** — 출발 자리(`source`)와 거기서 읽을 신원(`expectedIdentity`)을
     * 둘 다 댈 때다. 관문은 그런 단위의 출발 자리를 하달 전에 설비 관측으로 대조하고, 설비가 말이 없을 때 보낼지가 이
     * 선언이다. 해석기([DefinedCapability])도 같은 함수로 본다 — 두 벌로 두면 한쪽만 기준이 바뀌는 날이 온다.
     *
     * **로봇 경로 노드만이다.** 관문은 로봇 단위의 출발 자리만 읽는다(`AdmissionGate`) — 플릿 노드에 선언을 요구하면 쓰이지 않는
     * 선언이 선다(§15.227 의 기준). 관문이 플릿 단위를 읽게 되면 이 조건을 함께 푼다(§15.325).
     */
    fun needsSourceObservation(step: UnitStep): Boolean =
        step.route == Route.ROBOT && step.source != null && step.expectedIdentity != null

    /**
     * 이 단위 노드가 목적지 관측 선언을 **요구하는가** — 목적지(`destination`)와 거기 둘 것의 식별 정보(`expectedIdentity`)를
     * 둘 다 댈 때다. 게이트가 예약으로 세는 자리(자재를 둘 자리)와 같은 기준이다. 식별 정보가 없는 목적지는 갈 자리라 점유가 아니다.
     * 해석기([DefinedCapability])도 같은 함수로 본다. 출발 관측과 같은 까닭으로 로봇 경로 노드만이다.
     */
    fun needsDestinationObservation(step: UnitStep): Boolean =
        step.route == Route.ROBOT && step.destination != null && step.expectedIdentity != null

    /** 계약 카탈로그의 스킬 이름 전부 — 기술자의 `skill_type_name` 옵션에서 읽는다. */
    val contractSkills: Set<String> by lazy {
        SkillCatalog.getDescriptor().messageTypes
            .mapNotNull { it.options.getExtension(SkillCatalog.skillTypeName).takeIf { name -> !name.isNullOrEmpty() } }
            .toSet()
    }

    /** 노드 id 중복 — 그룹과 그 안쪽 노드도 한 이름 공간이다. */
    private fun duplicateNodeIds(definition: MissionDefinition, at: Instant): List<MissionRefusal> =
        definition.steps.flatMap { if (it is EachStep) listOf(it) + it.steps else listOf(it) }.groupingBy { it.id }.eachCount().filterValues { it > 1 }.map { (id, n) ->
            MissionRefusal(MissionRefusalKind.DUPLICATE_NODE_ID, id, "$n 번 나온다", "정의 안에서 한 번", at)
        }

    private fun waits(definition: MissionDefinition) = definition.steps.filterIsInstance<WaitStep>()

    /** 기한 — 대기 노드마다 양의 기한과 허용된 기한 뒤 상태. 파서가 아니라 여기서 본다. */
    private fun deadlineRefusals(definition: MissionDefinition, at: Instant): List<MissionRefusal> =
        waits(definition).flatMap { wait ->
            val allowed = DeadlineOutcome.entries.joinToString(" 또는 ") { it.name }
            listOfNotNull(
                wait.deadlineSeconds.let { seconds ->
                    if (seconds != null && seconds > 0) {
                        null
                    } else {
                        MissionRefusal(
                            MissionRefusalKind.DEADLINE_INVALID, wait.id,
                            "deadlineSeconds=${seconds ?: "없음"}", "양의 정수 초", at,
                        )
                    }
                },
                wait.onDeadline.let { outcome ->
                    if (DeadlineOutcome.entries.any { it.name == outcome }) {
                        null
                    } else {
                        MissionRefusal(MissionRefusalKind.DEADLINE_INVALID, wait.id, "onDeadline=${outcome ?: "없음"}", allowed, at)
                    }
                },
            )
        }

    /** 신호 — 참조한 신호가 사양에 있는가, 기대 값이 그 신호의 종류로 읽히는가. */
    private fun signalRefusals(definition: MissionDefinition, byName: Map<String, SignalSpec>, at: Instant): List<MissionRefusal> =
        waits(definition).mapNotNull { wait ->
            val spec = byName[wait.signal]
                ?: return@mapNotNull MissionRefusal(
                    MissionRefusalKind.SIGNAL_NOT_IN_SPEC, wait.id, wait.signal,
                    "신호 사양의 이름 중 하나(${byName.keys.sorted().joinToString()})", at,
                )
            if (spec.kind == SignalKind.BOOLEAN && wait.expect !in BOOLEAN_VALUES) {
                MissionRefusal(MissionRefusalKind.SIGNAL_VALUE_INVALID, wait.id, wait.expect, BOOLEAN_VALUES.joinToString(" 또는 "), at)
            } else {
                null
            }
        }

    /**
     * 자원 — 정의에 고정으로 적힌 자리(대기 신호의 자리)의 바닥 소유. **관문과 같은 규칙이다** — `Unowned` 만 거부하고
     * `Declared`·`NotDeclared` 는 통과한다. 선언 없음을 거부로 읽으면 레지스터를 안 붙인 현장에서 자리가 적힌 신호를 쓰는
     * 정의가 모두 거부된다. 자리가 없는 신호는 건너뛴다.
     */
    private fun floorRefusals(
        definition: MissionDefinition,
        byName: Map<String, SignalSpec>,
        floors: FloorOwnership,
        at: Instant,
    ): List<MissionRefusal> = waits(definition).mapNotNull { wait ->
        val where = byName[wait.signal]?.location ?: return@mapNotNull null
        if (floors.ownerOf(where) != FloorOwner.Unowned) return@mapNotNull null
        MissionRefusal(MissionRefusalKind.FLOOR_UNOWNED, wait.id, "$where: 소유자 없음", "$where: 소유자 선언", at)
    }

    /** 스킬 — 계약에 있는 스킬인가, 현장 기체가 제공하는가. 둘은 다른 물음이다. 플릿 노드의 운반은 로봇 스킬이 아니다([fleetRefusals]). */
    private fun skillRefusals(definition: MissionDefinition, siteSkills: Set<String>, at: Instant): List<MissionRefusal> =
        robotUnits(definition).mapNotNull { unit ->
            when {
                unit.skill !in contractSkills -> MissionRefusal(
                    MissionRefusalKind.SKILL_NOT_IN_CONTRACT, unit.id, unit.skill, "계약 카탈로그의 스킬 이름", at,
                )
                unit.skill !in siteSkills -> MissionRefusal(
                    MissionRefusalKind.SKILL_NOT_ON_SITE, unit.id, unit.skill,
                    "현장 기체가 제공하는 스킬(${siteSkills.sorted().joinToString()})", at,
                )
                else -> null
            }
        }

    /** 안전 — 안전 신호를 기다리는 대기 노드가 없는가(ADR 32). */
    private fun safetyRefusals(definition: MissionDefinition, byName: Map<String, SignalSpec>, at: Instant): List<MissionRefusal> =
        waits(definition).mapNotNull { wait ->
            if (byName[wait.signal]?.safety != true) return@mapNotNull null
            MissionRefusal(MissionRefusalKind.SAFETY_SIGNAL_WAIT, wait.id, "${wait.signal}: 안전 신호", "안전 신호가 아닌 신호", at)
        }

    /**
     * 출발 관측 — 신원을 대는 출발 자리가 있는 단위 노드는 관측을 요구하는지 적는다. 기본을 정하지 않고 묻는다. 그 밖의 단위
     * 노드는 적지 않는다 — 적으면 쓰이지 않거나 신원 없이 침묵만 막는 선언이 조용히 선다(§15.227).
     *
     * 해석기([DefinedCapability])는 적지 않아야 할 선언을 막지 않는다. 이미 활성화된 버전을 다시 지을 때 넘어지지 않게 한다.
     */
    private fun sourceObservationRefusals(definition: MissionDefinition, at: Instant): List<MissionRefusal> =
        robotUnits(definition).mapNotNull { unit ->
            val declared = unit.sourceObservation
            when {
                needsSourceObservation(unit) && declared == null -> MissionRefusal(
                    MissionRefusalKind.SOURCE_OBSERVATION_UNDECLARED, unit.id, "sourceObservation=없음", "REQUIRED 또는 OPTIONAL", at,
                )
                !needsSourceObservation(unit) && declared != null -> MissionRefusal(
                    MissionRefusalKind.SOURCE_OBSERVATION_UNUSED, unit.id, "sourceObservation=${declared.name}",
                    "선언 없음(출발 자리와 기대 식별 정보가 둘 다 있는 노드만 선언한다)", at,
                )
                else -> null
            }
        }

    /**
     * 목적지 관측 — 식별 정보를 대는 목적지가 있는 단위 노드는 작업 전 목적지 관측을 볼지 적는다. 기본을 정하지 않고 묻는다.
     * 그 밖의 단위 노드는 적지 않는다. 출발 관측([sourceObservationRefusals])과 같은 모양이고, 해석기도 같은 이유로 적지 않아야 할
     * 선언은 거부하지 않는다.
     */
    private fun destinationObservationRefusals(definition: MissionDefinition, at: Instant): List<MissionRefusal> =
        robotUnits(definition).mapNotNull { unit ->
            val declared = unit.destinationObservation
            when {
                needsDestinationObservation(unit) && declared == null -> MissionRefusal(
                    MissionRefusalKind.DESTINATION_OBSERVATION_UNDECLARED, unit.id, "destinationObservation=없음",
                    "REQUIRED 또는 OPTIONAL", at,
                )
                !needsDestinationObservation(unit) && declared != null -> MissionRefusal(
                    MissionRefusalKind.DESTINATION_OBSERVATION_UNUSED, unit.id, "destinationObservation=${declared.name}",
                    "선언 없음(목적지와 기대 식별 정보가 둘 다 있는 노드만 선언한다)", at,
                )
                else -> null
            }
        }

    /** 로봇 경로 단위 노드. 플릿 노드의 스킬·관측 선언은 [fleetRefusals] 가 본다 — 한 문제에 거부 하나다. */
    private fun robotUnits(definition: MissionDefinition) = unitNodes(definition).filter { it.route == Route.ROBOT }

    /** 노드 묶음 — 최상위 단위 노드는 노드 하나짜리 묶음이고 반복 그룹은 그 안쪽 노드들이다. */
    private fun groups(definition: MissionDefinition): List<Pair<MissionStep, List<UnitStep>>> = definition.steps.mapNotNull { step ->
        when (step) {
            is UnitStep -> step to listOf(step)
            is EachStep -> step to step.steps
            is WaitStep -> null
        }
    }

    /** 선행 — `after` 는 같은 반복 그룹의 앞 형제 단위 노드만 가리킨다. 해석기도 같은 조건을 생성 때 본다. */
    private fun afterRefusals(definition: MissionDefinition, at: Instant): List<MissionRefusal> =
        groups(definition).flatMap { (step, nodes) ->
            nodes.mapIndexedNotNull { index, node ->
                val after = node.after ?: return@mapIndexedNotNull null
                if (step is EachStep && after in nodes.take(index).map { it.id }) return@mapIndexedNotNull null
                val siblings = if (step is EachStep) nodes.take(index).map { it.id } else emptyList()
                MissionRefusal(
                    MissionRefusalKind.AFTER_INVALID, node.id, "after=$after",
                    "같은 그룹의 앞 단위 노드 id(${siblings.joinToString().ifEmpty { "없음" }})", at,
                )
            }
        }

    /**
     * 단위 id 모양 — 같은 그룹의 두 노드가, 또는 최상위 `once` 노드 둘이 낼 수 있는 단위 id 모양(출처·인자·접미사, `else` 의 끝값까지)이
     * 하나라도 같으면 겹친다. 그룹은 항목마다, `once` 노드는 작업 지시마다 겹친다. 기존 문서에는 그룹도 `once` 도 없어 걸리지 않는다.
     */
    private fun unitIdShapeRefusals(definition: MissionDefinition, at: Instant): List<MissionRefusal> {
        val sets = definition.steps.filterIsInstance<EachStep>().map { it.steps } +
            listOf(definition.steps.filterIsInstance<UnitStep>().filter { it.once })
        return sets.flatMap { nodes ->
            nodes.indices.flatMap { j ->
                (0 until j).mapNotNull { i ->
                    val shared = shapes(nodes[i].unitId).intersect(shapes(nodes[j].unitId).toSet()).firstOrNull() ?: return@mapNotNull null
                    MissionRefusal(
                        MissionRefusalKind.UNIT_ID_SHAPE_COLLISION, nodes[j].id,
                        "${nodes[i].id}, ${nodes[j].id}: ${shapeName(shared)}", "노드마다 다른 단위 id 모양", at,
                    )
                }
            }
        }
    }

    /** 단위 id 가 낼 수 있는 모양 — 출처 자신과 `else` 의 끝값. 대체값(`otherwise`)은 단위 id 의 모양을 정하지 않는다. */
    private fun shapes(unitId: ValueSource): List<ValueSource> =
        listOfNotNull(unitId, unitId.orElse).map { it.copy(otherwise = null, orElse = null) }

    private fun shapeName(shape: ValueSource): String =
        shape.from.name + listOfNotNull(shape.equipmentUse, shape.property, shape.parameter).joinToString(".").let { if (it.isEmpty()) "" else "($it)" } +
            shape.suffix.orEmpty()

    /** 플릿 경로 — 운반 하나의 모양(`transport`, 출발·목적지·기대 식별 정보)이고 관측 선언이 없다. */
    private fun fleetRefusals(definition: MissionDefinition, at: Instant): List<MissionRefusal> =
        unitNodes(definition).filter { it.route == Route.FLEET }.flatMap { unit ->
            val missing = listOfNotNull(
                "expectedIdentity".takeIf { unit.expectedIdentity == null },
                "source".takeIf { unit.source == null },
                "destination".takeIf { unit.destination == null },
            )
            val declared = listOfNotNull(
                unit.sourceObservation?.let { "sourceObservation=${it.name}" },
                unit.destinationObservation?.let { "destinationObservation=${it.name}" },
            )
            listOfNotNull(
                MissionRefusal(MissionRefusalKind.FLEET_UNIT_INVALID, unit.id, "skill=${unit.skill}", "skill=$FLEET_SKILL", at)
                    .takeIf { unit.skill != FLEET_SKILL },
                MissionRefusal(MissionRefusalKind.FLEET_UNIT_INVALID, unit.id, "${missing.joinToString()}=없음", "출발·목적지·기대 식별 정보", at)
                    .takeIf { missing.isNotEmpty() },
                MissionRefusal(MissionRefusalKind.FLEET_UNIT_INVALID, unit.id, declared.joinToString(), "관측 선언 없음", at)
                    .takeIf { declared.isNotEmpty() },
            )
        }

    /** 조건부 포함 — 남길 노드를 알고, 남길 파라미터가 그 노드의 것이다. */
    private fun whenMissingRefusals(definition: MissionDefinition, at: Instant): List<MissionRefusal> =
        groups(definition).flatMap { (step, nodes) ->
            val whenMissing = when (step) {
                is UnitStep -> step.whenMissing
                is EachStep -> step.whenMissing
                is WaitStep -> null
            } ?: return@flatMap emptyList()
            val kept = (if (whenMissing.unit != null) nodes.firstOrNull { it.id == whenMissing.unit } else nodes.singleOrNull())
                ?: return@flatMap listOf(
                    MissionRefusal(
                        MissionRefusalKind.WHEN_MISSING_INVALID, step.id, "unit=${whenMissing.unit ?: "없음"}",
                        "남길 안쪽 노드 id(${nodes.joinToString { it.id }})", at,
                    ),
                )
            val unknown = whenMissing.parameters.filterNot { it in kept.parameters }
            if (unknown.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    MissionRefusal(
                        MissionRefusalKind.WHEN_MISSING_INVALID, step.id, "parameters=${unknown.joinToString()}",
                        "${kept.id} 의 파라미터 이름(${kept.parameters.keys.joinToString()})", at,
                    ),
                )
            }
        }

    /** 플릿 계약의 운반 이름 — 계약 카탈로그의 스킬이 아니다. */
    private const val FLEET_SKILL = DeliverContainer.TRANSPORT

    private val BOOLEAN_VALUES = listOf("true", "false")
}

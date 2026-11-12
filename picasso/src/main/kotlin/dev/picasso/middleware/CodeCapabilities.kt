package dev.picasso.middleware

/**
 * `PrepareSequencedRack` — 생산 순서에 맞춰 랙의 슬롯에 부품을 배치한다(시나리오 ②).
 *
 * 입력(ISA-95 JobOrder 의 모양, `docs/scenarios.md` §4.1):
 * - `EquipmentRequirements` 중 `EquipmentUse = destination` 인 것이 슬롯이고 `material`
 *   속성이 그 슬롯에 놓일 부품 **타입**이다.
 * - `EquipmentUse = source` 인 것이 제시 자리이고 `material` 속성이 거기 놓인 타입이다.
 *
 * 슬롯 하나 = `pick_place(object_id = 제시 자리, destination = 슬롯)` 하나. `object_id`
 * 가 부품 타입이 아니라 **제시 자리의 이름**인 것은 로봇에 제품 타입 개념이
 * 없기 때문이다(§15.80) — *이 자리에는 A형만 있다* 가 환경 전제 C 다.
 *
 * 최고 근거 등급은 E2 — 셀 검증 장치([CellSignals])가 있으면 슬롯 점유와 품번을
 * 독립적으로 확인한다.
 */
class PrepareSequencedRack : LogicalCapability {

    override val workMasterId: String = WORK_MASTER

    override val maxEvidence: Evidence = Evidence.E2

    /**
     * **파지 확인을 요구한다**(시나리오 ② §4.2 의 요청 모양).
     *
     * 이 능력은 집어서 놓는 일이고, 집었는지를 로봇이 확인해 주면 E0 의 값이 올라간다. 계약이 그것을
     * 선택 파라미터로 두었으므로 **드는 기종에만** 간다.
     */
    override val preferredOptionals: Map<String, String> = mapOf(P_VERIFY_GRASP to "true")

    override fun plan(order: JobOrder): List<ExecutionUnit> {
        val sources = order.equipmentRequirements
            .filter { it.equipmentUse == EquipmentUse.SOURCE }
            .associateBy { it.properties[EquipmentUse.PROP_MATERIAL] }

        return order.equipmentRequirements
            .filter { it.equipmentUse == EquipmentUse.DESTINATION }
            .map { slot ->
                val material = slot.properties[EquipmentUse.PROP_MATERIAL]
                val source = material?.let { sources[it] }
                ExecutionUnit(
                    unitId = slot.id,
                    route = Route.ROBOT,
                    skillType = SKILL,
                    parameters = mapOf(
                        P_OBJECT to (source?.id ?: ""),
                        P_DESTINATION to slot.id,
                    ),
                    expectedIdentity = material,
                    source = source?.id,
                    destination = slot.id,
                    // 제시 자리가 없으면 집을 것이 없다 — 시작도 못 한다. 부족은 여기서 드러난다.
                    state = if (source == null) UnitState.FAILED else UnitState.PENDING,
                    failureClass = if (source == null) NO_SOURCE else null,
                )
            }
    }

    companion object {
        const val WORK_MASTER = "PrepareSequencedRack"

        /** 계약 카탈로그의 스킬 이름. 미들웨어가 아는 유일한 하류 어휘는 계약이다. */
        const val SKILL = "pick_place"
        const val P_OBJECT = "object_id"
        const val P_DESTINATION = "destination"

        /** 계약 카탈로그 `PickPlaceV1.verify_grasp` — 선택 파라미터다. */
        const val P_VERIFY_GRASP = "verify_grasp"

        /** 그 타입을 제시하는 자리가 주문에 없다 — 부품 부족의 미들웨어 쪽 이름. */
        const val NO_SOURCE = "NO_SOURCE_FOR_MATERIAL"
    }
}

/**
 * `DeliverContainer` — 할당된 용기를 출발 인계점에서 도착 인계점으로 공급한다(시나리오 ①).
 *
 * 입력(보고서 5장): `EquipmentUse = source` 인 것이 출발 인계점이고 `container` 속성이
 * WMS 가 할당한 용기, `EquipmentUse = destination` 이 도착 인계점. 상류는 어느 AMR 을
 * 쓸지도 경로도 지정하지 않는다.
 *
 * 단위 하나 = 운반 하나. 하류는 **플릿에 D 수준으로 위임**한다([Route.FLEET]) — 플릿이
 * 운반 전체를 제공하므로 미들웨어가 이동·도킹·하역을 조합하지 않는다. 플릿의 완료가
 * E1 이고, 그 용기가 그 자리에 있는지는 인계 설비(E2)가 말한다. 최고 등급 E2.
 */
class DeliverContainer : LogicalCapability {

    override val workMasterId: String = WORK_MASTER

    override val maxEvidence: Evidence = Evidence.E2

    override fun plan(order: JobOrder): List<ExecutionUnit> {
        val source = order.equipmentRequirements.firstOrNull { it.equipmentUse == EquipmentUse.SOURCE }
        val destination = order.equipmentRequirements.firstOrNull { it.equipmentUse == EquipmentUse.DESTINATION }
        val container = source?.properties?.get(EquipmentUse.PROP_CONTAINER)

        if (source == null || destination == null || container == null) {
            return listOf(
                ExecutionUnit(
                    unitId = container ?: order.jobOrderId,
                    route = Route.FLEET,
                    skillType = TRANSPORT,
                    parameters = emptyMap(),
                    expectedIdentity = container,
                    source = source?.id,
                    destination = destination?.id,
                    state = UnitState.FAILED,
                    failureClass = INCOMPLETE_ORDER,
                ),
            )
        }

        return listOf(
            ExecutionUnit(
                unitId = container,
                route = Route.FLEET,
                skillType = TRANSPORT,
                parameters = mapOf(
                    P_CONTAINER to container,
                    P_SOURCE to source.id,
                    P_DESTINATION to destination.id,
                ),
                expectedIdentity = container,
                source = source.id,
                destination = destination.id,
            ),
        )
    }

    companion object {
        const val WORK_MASTER = "DeliverContainer"

        /** 플릿 계약의 실행 단위 이름 — 계약 카탈로그의 스킬이 아니다. */
        const val TRANSPORT = "transport"
        const val P_CONTAINER = "container"
        const val P_SOURCE = "source"
        const val P_DESTINATION = "destination"

        const val INCOMPLETE_ORDER = "INCOMPLETE_ORDER"
    }
}

/**
 * `InspectAsset` — 점검 대상 목록을 순회하며 항목을 살핀다(시나리오 ③, 보고서 7장).
 *
 * 입력: `EquipmentUse = inspection_target` 인 것이 점검 대상이고, `location` 속성이 그 대상을 살필
 * 자리(장소의 이름 — `is_site_reference`), `item` 이 점검 항목이다. 목록의 순서가 순회 순서다. 실행
 * 조건은 주문의 `parameters` 로 온다(`mode` 가 있으면 `inspect` 의 선택 파라미터로 넘긴다).
 *
 * 대상 하나 = 단위 둘 — `<대상>.travel`(`navigate_to(location)`) 과 `<대상>`(`inspect(target)`). 갈 곳은
 * 장소의 이름이고 살필 것은 대상의 이름이다(공간 둘, §15.78). 상류가 아는 단위는 뒤의 것(항목)이고,
 * 앞의 것은 거기까지 가는 걸음이다 — 둘 다 결과 목록에 오르므로 어디서 멈췄는지가 보인다.
 *
 * **최고 근거 등급은 E0 다.** 점검 결과는 로봇 자신의 보고(측정값·증거 자료 참조)이고 그것을 독립적으로
 * 확인하는 설비가 없다. E2 를 요구하는 주문은 접수하지 않는다(보고서 11.3 — *확인 수단이 없으면 제공 불가*).
 *
 * 점검 결과를 실을 자리는 계약에 `partial_result` 문자열 하나뿐이고 미믹은 채우지 않는다(§15.76·§15.87).
 * 그래서 이 능력이 낸 `JobResponse.results` 는 지금 비어 있으며, 그 사실을 시험이 고정한다.
 *
 * 이 능력을 들일 때 공통 엔진은 손대지 않았다 — 이 클래스와 [EquipmentUse] 의 용어 셋이 그때 확장의 전부였다(17장 10번).
 * 그때 단위끼리 독립이라는 엔진의 전제를 그대로 가져왔으나 대상 안의 두 단위는 독립이 아니다. 점검 단위가 이동 단위를
 * 선행으로 든다([ExecutionUnit.after], §15.234). 이동이 실패해 건너뛴 대상은 순회가 종결한 뒤 사람이 한 쌍씩 재작업한다
 * ([Middleware.resolve]) — 리비전은 시도된 이동 실패를 다시 내지 않는다.
 * 단위의 **종류**가 늘면 이야기가 다르다 — 설비 대기는 엔진에 경로 하나([Route.SIGNAL])와 그 진행을 더했다(ADR 50).
 */
class InspectAsset : LogicalCapability {

    override val workMasterId: String = WORK_MASTER

    override val maxEvidence: Evidence = Evidence.E0

    override fun plan(order: JobOrder): List<ExecutionUnit> {
        val mode = order.parameters[P_MODE]
        return order.equipmentRequirements
            .filter { it.equipmentUse == EquipmentUse.INSPECTION_TARGET }
            .flatMap { target ->
                val location = target.properties[EquipmentUse.PROP_LOCATION]
                if (location == null) {
                    // 살필 자리를 모르면 갈 수도 살필 수도 없다 — 부족은 계획에서 드러난다(PrepareSequencedRack 의 NO_SOURCE 와 같은 자리).
                    return@flatMap listOf(
                        ExecutionUnit(
                            unitId = target.id,
                            route = Route.ROBOT,
                            skillType = INSPECT,
                            parameters = mapOf(P_TARGET to target.id),
                            expectedIdentity = null,
                            source = null,
                            destination = null,
                            state = UnitState.FAILED,
                            failureClass = NO_LOCATION,
                        ),
                    )
                }
                listOf(
                    ExecutionUnit(
                        unitId = "${target.id}$TRAVEL_SUFFIX",
                        route = Route.ROBOT,
                        skillType = NAVIGATE,
                        parameters = mapOf(P_LOCATION to location),
                        expectedIdentity = null,
                        source = null,
                        destination = location,
                    ),
                    ExecutionUnit(
                        unitId = target.id,
                        route = Route.ROBOT,
                        skillType = INSPECT,
                        parameters = buildMap {
                            put(P_TARGET, target.id)
                            if (mode != null) put(P_MODE, mode)
                        },
                        expectedIdentity = null,
                        source = null,
                        destination = location,
                    ).apply {
                        // 점검은 그 자리에 가 있어야 뜻이 있다. 이동이 완료되지 않았으면 이 단위는 나가지 않는다(§15.234).
                        // 대상끼리는 묶지 않는다 — 한 대상의 이동이 실패해도 다음 대상은 간다.
                        after = "${target.id}$TRAVEL_SUFFIX"
                    },
                )
            }
    }

    companion object {
        const val WORK_MASTER = "InspectAsset"

        /** 계약 카탈로그의 스킬 둘. */
        const val NAVIGATE = "navigate_to"
        const val INSPECT = "inspect"
        const val P_LOCATION = "location"
        const val P_TARGET = "target"
        const val P_MODE = "mode"

        const val TRAVEL_SUFFIX = ".travel"

        /** 그 대상을 살필 자리가 주문에 없다. */
        const val NO_LOCATION = "NO_LOCATION_FOR_TARGET"
    }
}

package dev.picasso.middleware.mission

import dev.picasso.middleware.DeadlineOutcome
import dev.picasso.middleware.DestinationObservation
import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.ExecutionUnit
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.LogicalCapability
import dev.picasso.middleware.Route
import dev.picasso.middleware.SourceObservation
import dev.picasso.middleware.UnitState
import dev.picasso.middleware.WaitSpec
import java.time.Duration

/**
 * 해석기 — 임무 정의([MissionDefinition])를 [LogicalCapability] 로 바꾼다.
 *
 * 그래서 실행·리비전·인시던트는 코드 케이퍼빌리티와 **같은 경로**를 탄다. 엔진이 아는 것은 계획된 단위 시퀀스뿐이고,
 * 그것이 코드에서 왔는지 데이터에서 왔는지 모른다.
 *
 * **검증을 통과한 정의만 받는다.** 대기 노드의 기한과 기한 뒤 상태를 생성 때 엔진의 값으로 바꾸며, 없거나 허용 밖이면
 * 여기서 실패한다 — 계획 때 실패하면 작업 지시를 받는 도중에 미들웨어가 넘어진다. 활성화는 검증기를 먼저 부르므로
 * ([InMemoryMissionCatalog.activate]) 그 길에서는 여기서 실패하지 않는다.
 */
class DefinedCapability(val definition: MissionDefinition) : LogicalCapability {

    override val workMasterId: String = definition.workMasterId

    override val maxEvidence: Evidence = definition.maxEvidence

    override val preferredOptionals: Map<String, String> = definition.preferredOptionals

    /** 대기 노드마다 엔진의 대기 사양. 생성 때 한 번 바꾼다. */
    private val waits: Map<String, WaitSpec> = definition.steps.filterIsInstance<WaitStep>().associate { step ->
        val seconds = requireNotNull(step.deadlineSeconds) { "검증을 거치지 않은 정의다: ${step.id} 에 기한이 없다" }
        require(seconds > 0) { "검증을 거치지 않은 정의다: ${step.id} 의 기한이 양수가 아니다($seconds)" }
        val outcome = DeadlineOutcome.entries.firstOrNull { it.name == step.onDeadline }
            ?: throw IllegalArgumentException("검증을 거치지 않은 정의다: ${step.id} 의 기한 뒤 상태가 허용 밖이다(${step.onDeadline})")
        step.id to WaitSpec(step.signal, step.expect, Duration.ofSeconds(seconds), outcome)
    }

    init {
        // **선언 없는 신원 대조를 기본값으로 메우지 않는다.** 메우면 검증기가 없앤 암묵이 이 길로 돌아온다.
        MissionValidator.unitNodes(definition).forEach { step ->
            require(!MissionValidator.needsSourceObservation(step) || step.sourceObservation != null) {
                "검증을 거치지 않은 정의다: ${step.id} 가 출발 자리의 식별 정보를 대는데 출발 관측 선언이 없다"
            }
            require(!MissionValidator.needsDestinationObservation(step) || step.destinationObservation != null) {
                "검증을 거치지 않은 정의다: ${step.id} 가 목적지에 둘 것의 식별 정보를 대는데 목적지 관측 선언이 없다"
            }
        }
        // 선행이 끊기면 엔진은 그 단위를 그냥 낸다(참조한 단위가 없으면 기다릴 것이 없다) — 조용히 순서가 사라진다.
        definition.steps.forEach { step ->
            val (nodes, whenMissing) = when (step) {
                is WaitStep -> return@forEach
                is UnitStep -> listOf(step) to step.whenMissing
                is EachStep -> step.steps to step.whenMissing
            }
            nodes.forEachIndexed { at, node ->
                require(node.after == null || (step is EachStep && node.after in nodes.take(at).map { it.id })) {
                    "검증을 거치지 않은 정의다: ${node.id} 의 선행 ${node.after} 가 같은 그룹의 앞 노드가 아니다"
                }
            }
            val requires = when (step) {
                is UnitStep -> step.requires
                is EachStep -> step.requires
                is WaitStep -> emptyList()
            }
            require(requires.isEmpty() || whenMissing != null) {
                "검증을 거치지 않은 정의다: ${step.id} 에 조건(requires)이 있는데 접힘(whenMissing)이 없다"
            }
            require(whenMissing == null || kept(nodes, whenMissing) != null) {
                "검증을 거치지 않은 정의다: ${step.id} 의 접힘이 남길 노드(${whenMissing?.unit})를 모른다"
            }
        }
    }

    override fun plan(order: JobOrder): List<ExecutionUnit> = definition.steps.flatMap { step ->
        when (step) {
            is WaitStep -> listOf(waitUnit(step))
            is UnitStep -> {
                val index = partners(listOf(step), order)
                if (step.once) {
                    expand(listOf(step), step.requires, step.whenMissing, order, item = null, index)
                } else {
                    items(order, step.forEach).flatMap { expand(listOf(step), step.requires, step.whenMissing, order, it, index) }
                }
            }
            is EachStep -> {
                val index = partners(step.steps, order)
                items(order, step.forEach).flatMap { expand(step.steps, step.requires, step.whenMissing, order, it, index) }
            }
        }
    }

    /**
     * 대기 노드 하나 = 대기 단위 하나. 단위 id 는 노드 id 다 — 작업 응답의 완료·미완료 목록에 다른 단위처럼 오른다.
     *
     * 대기 사양을 **파라미터에도 싣는다.** 엔진은 [ExecutionUnit.wait] 를 읽고, 파라미터는 인시던트의 `intent.unitParameters`
     * 로 나가 무엇을 언제까지 기다렸는지를 칸을 늘리지 않고 보인다.
     */
    private fun waitUnit(step: WaitStep): ExecutionUnit {
        val wait = waits.getValue(step.id)
        return ExecutionUnit(
            unitId = step.id,
            route = Route.SIGNAL,
            skillType = WaitSpec.SKILL_TYPE,
            parameters = mapOf(
                WaitSpec.P_SIGNAL to wait.signal,
                WaitSpec.P_EXPECT to wait.expect,
                WaitSpec.P_DEADLINE_SECONDS to wait.deadline.seconds.toString(),
                WaitSpec.P_ON_DEADLINE to wait.onDeadline.name,
            ),
            expectedIdentity = null,
            source = null,
            destination = null,
            wait = wait,
        )
    }

    /** 반복할 설비 — 작업 지시의 설비 순서대로. */
    private fun items(order: JobOrder, use: String?): List<EquipmentRequirement> =
        order.equipmentRequirements.filter { it.equipmentUse == use }

    /** 접힘이 남길 노드 — 그룹이면 [WhenMissing.unit] 이 가리키는 것, 아니면 그 노드 하나. */
    private fun kept(nodes: List<UnitStep>, whenMissing: WhenMissing): UnitStep? =
        if (whenMissing.unit != null) nodes.firstOrNull { it.id == whenMissing.unit } else nodes.singleOrNull()

    /**
     * 항목 하나(once 면 작업 지시 하나)에서 노드들을 차례로 펼친다. 단위 노드 하나도 «노드 하나짜리 그룹» 으로 여기서 펼친다.
     *
     * [requires] 가 하나라도 값을 못 내면 [WhenMissing] 대로 단위 하나로 접는다 — 남는 단위의 칸은 정상 해석 그대로이고 파라미터만
     * 목록에 든 것이며 선행은 없다. 아니면 노드마다 단위 하나이고, 선행(`after`)은 **같은 항목에서** 그 노드가 낸 단위 id 다
     * (대상끼리 묶지 않는다 — 한 대상의 이동이 실패해도 다음 대상은 간다).
     *
     * 짝 규칙은 코드 `PrepareSequencedRack` 과 같다. 후보는 짝 쓰임의 설비 중 맞출 속성이 있는 것이고, 같은 값이 여럿이면
     * **목록에서 뒤엣것**이다(`associateBy`). 반복 중인 설비에 그 속성이 없으면 짝이 없다.
     */
    private fun expand(
        nodes: List<UnitStep>,
        requires: List<ValueSource>,
        whenMissing: WhenMissing?,
        order: JobOrder,
        item: EquipmentRequirement?,
        index: Map<String, Map<String, EquipmentRequirement>>,
    ): List<ExecutionUnit> {
        val scopes = nodes.associateWith { node ->
            val paired = node.pairWith?.let { p -> item?.properties?.get(p.property)?.let { index[node.id]?.get(it) } }
            Scope(order, item, paired)
        }
        val missing = whenMissing != null && requires.any { resolve(it, scopes.getValue(nodes.first())) == null }
        if (missing) {
            val node = checkNotNull(kept(nodes, whenMissing))
            val scope = scopes.getValue(node)
            return listOf(
                unit(node, scope, UnitState.FAILED, whenMissing.failureClass) { name -> name in whenMissing.parameters },
            )
        }
        val produced = mutableMapOf<String, String>()
        return nodes.map { node ->
            val scope = scopes.getValue(node)
            // 짝이 없으면 시작도 못 한다 — 부족은 계획에서 드러난다(코드 케이퍼빌리티와 같은 자리).
            val unpaired = node.pairWith != null && scope.paired == null
            unit(node, scope, if (unpaired) UnitState.FAILED else UnitState.PENDING, if (unpaired) node.whenUnpaired else null) { true }
                .apply { after = node.after?.let { produced.getValue(it) } }
                .also { produced[node.id] = it.unitId }
        }
    }

    private fun unit(node: UnitStep, scope: Scope, state: UnitState, failureClass: String?, keep: (String) -> Boolean): ExecutionUnit {
        val value = { source: ValueSource -> resolve(source, scope) }
        return ExecutionUnit(
            unitId = checkNotNull(value(node.unitId)) { "검증을 거치지 않은 정의다: ${node.id} 의 단위 id 가 값을 못 냈다" },
            route = node.route,
            skillType = node.skill,
            parameters = node.parameters.filterKeys(keep).mapNotNull { (name, source) -> value(source)?.let { name to it } }.toMap(),
            expectedIdentity = node.expectedIdentity?.let(value),
            source = node.source?.let(value),
            destination = node.destination?.let(value),
            // 신원을 안 대는 노드는 선언이 없어도 된다 — 그때는 코드 케이퍼빌리티와 같은 기본값이다.
            sourceObservation = node.sourceObservation ?: SourceObservation.OPTIONAL,
            // 목적지도 같다 — 식별 정보를 안 대는 목적지는 선언이 없어도 되고, 그때는 보지 않는다.
            destinationObservation = node.destinationObservation ?: DestinationObservation.OPTIONAL,
            state = state,
            failureClass = failureClass,
        )
    }

    /**
     * 짝 색인 — 짝 규칙이 있는 노드마다 «맞출 속성 값 → 짝 설비». 작업 지시마다 노드당 **한 번** 짓고 설비마다 다시 쓴다(옛 `units()` 와
     * 같다). 후보는 짝 쓰임의 설비 중 맞출 속성이 있는 것이고, 같은 값이 여럿이면 목록에서 뒤엣것이다(`associateBy`).
     */
    private fun partners(nodes: List<UnitStep>, order: JobOrder): Map<String, Map<String, EquipmentRequirement>> =
        nodes.mapNotNull { node ->
            node.pairWith?.let { p ->
                node.id to order.equipmentRequirements
                    .filter { it.equipmentUse == p.equipmentUse && p.property in it.properties }
                    .associateBy { it.properties.getValue(p.property) }
            }
        }.toMap()

    /** 값을 푸는 자리 — 작업 지시, 반복 중인 설비(once 면 널), 그 짝. */
    private class Scope(val order: JobOrder, val item: EquipmentRequirement?, val paired: EquipmentRequirement?)

    /**
     * 값 출처 하나를 푼다. 출처가 값을 못 내면 대체값, 그다음 `else` 출처, 그것도 없으면 `null`. 접미사는 출처가 낸 값에만 붙는다.
     * `FIRST_*` 는 그 쓰임의 **첫 설비**를 본다 — 첫 설비에 속성이 없으면 다음 설비를 찾지 않는다(코드 `DeliverContainer` 와 같다).
     */
    private fun resolve(source: ValueSource, scope: Scope): String? {
        val first = { scope.order.equipmentRequirements.firstOrNull { it.equipmentUse == source.equipmentUse } }
        val value = when (source.from) {
            ValueFrom.ITEM_ID -> scope.item?.id
            ValueFrom.ITEM_PROPERTY -> source.property?.let { scope.item?.properties?.get(it) }
            ValueFrom.PAIRED_ID -> scope.paired?.id
            ValueFrom.ORDER_ID -> scope.order.jobOrderId
            ValueFrom.ORDER_PARAMETER -> source.parameter?.let { scope.order.parameters[it] }
            ValueFrom.FIRST_ID -> first()?.id
            ValueFrom.FIRST_PROPERTY -> source.property?.let { first()?.properties?.get(it) }
        }
        return value?.let { it + source.suffix.orEmpty() } ?: source.otherwise ?: source.orElse?.let { resolve(it, scope) }
    }
}

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

    /** 대기 노드마다 엔진의 대기 사양 — 최상위 분기의 갈래 안 대기 노드까지. 생성 때 한 번 바꾼다. */
    private val waits: Map<String, WaitSpec> = MissionValidator.waitNodes(definition).associate { step ->
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
        MissionValidator.predecessors(definition).forEach { (node, allowed) ->
            require(node.after == null || node.after in allowed) {
                "검증을 거치지 않은 정의다: ${node.id} 의 선행 ${node.after} 가 같은 그룹의 앞 노드가 아니다"
            }
        }
        definition.steps.forEach { step ->
            val (nodes, whenMissing) = when (step) {
                is WaitStep -> return@forEach
                is UnitStep -> listOf(step) to step.whenMissing
                is EachStep -> step.steps to step.whenMissing
                is ChoiceStep -> listOf(step) to null
            }
            val requires = when (step) {
                is UnitStep -> step.requires
                is EachStep -> step.requires
                is WaitStep, is ChoiceStep -> emptyList()
            }
            require(requires.isEmpty() || whenMissing != null) {
                "검증을 거치지 않은 정의다: ${step.id} 에 조건(requires)이 있는데 접힘(whenMissing)이 없다"
            }
            require(whenMissing == null || kept(nodes, whenMissing) != null) {
                "검증을 거치지 않은 정의다: ${step.id} 의 접힘이 남길 노드(${whenMissing?.unit})를 모른다"
            }
            // 분기의 접힘(§15.328) — 남길 노드가 갈래 안에 있고 그 단위 id 가 조건 값 없이 값을 낸다. 못 내면 계획 때 넘어진다.
            nodes.filterIsInstance<ChoiceStep>().forEach { choice ->
                val kept = kept(listOf(choice), choice.whenMissing)
                require(kept != null && MissionValidator.yieldsAlone(kept.unitId, item = step is EachStep) && (step is EachStep || kept.once)) {
                    "검증을 거치지 않은 정의다: ${choice.id} 의 접힘이 남길 노드(${choice.whenMissing.unit})를 모르거나 그 단위 id 가 조건 값 없이 값을 못 낸다"
                }
            }
        }
    }

    override fun plan(order: JobOrder): List<ExecutionUnit> = planned(order).first

    /**
     * 접수 시점 분기의 선택(§15.328) — 분기 자리마다 고른 갈래(`arms[i]`·`otherwise`·`whenMissing`). 최상위 분기는 분기 id, 그룹
     * 안 분기는 `분기 id[설비 id]` 가 자리다(항목마다 고른다). 그룹이 조건부 포함으로 접힌 항목의 분기는 고르지 않아 자리가 없다.
     * [plan] 과 같은 펼침에서 나온다 — 따로 셈하면 둘이 갈릴 날이 온다.
     */
    override fun choices(order: JobOrder): Map<String, String> = planned(order).second.choices

    /** 분기 자리마다 그 자리가 낸 단위 id(고른 갈래의 단위, 접혔으면 접힌 단위 하나). 리비전이 남은 자리의 단위 id 를 바꾸는지의 판정이 쓴다(§15.328). */
    override fun choiceUnits(order: JobOrder): Map<String, List<String>> = planned(order).second.units

    /**
     * 분기 자리가 겹치는 작업 지시는 받지 않는다(§15.328) — 분기가 든 그룹의 쓰임에 같은 설비 id 가 둘이면 `분기 id[설비 id]` 가 한
     * 자리에 둘이 되어, 한쪽의 선택이 가려지고 리비전 대조가 엉뚱한 쪽을 본다.
     */
    override fun planRefusal(order: JobOrder): String? = planned(order).second.clashes.takeIf { it.isNotEmpty() }?.let {
        "분기 자리가 겹쳐 받지 않는다: ${it.joinToString()} — 분기가 든 반복 그룹의 설비 id 가 같은 쓰임 안에서 겹친다"
    }

    private fun planned(order: JobOrder): Pair<List<ExecutionUnit>, Picks> {
        val picked = Picks()
        return definition.steps.flatMap { step -> top(step, order, picked) } to picked
    }

    /** 계획 한 번이 고른 갈래 — 자리마다 갈래 이름과 낸 단위 id, 그리고 두 번 나온 자리. */
    private class Picks {
        val choices = linkedMapOf<String, String>()
        val units = linkedMapOf<String, List<String>>()
        val clashes = linkedSetOf<String>()

        fun pick(at: String, choice: String, made: List<ExecutionUnit>): List<ExecutionUnit> {
            if (at in choices) clashes += at
            choices[at] = choice
            units[at] = units[at].orEmpty() + made.map { it.unitId }
            return made
        }
    }

    /** 최상위 노드 하나를 펼친다. 최상위 분기는 작업 지시마다 한 번 고르고 고른 갈래의 노드를 최상위 노드처럼 펼친다. */
    private fun top(step: MissionStep, order: JobOrder, picked: Picks): List<ExecutionUnit> = when (step) {
        is WaitStep -> listOf(waitUnit(step))
        is UnitStep -> {
            val index = partners(listOf(step), order)
            if (step.once) {
                expand(listOf(step), step.requires, step.whenMissing, order, item = null, index, picked)
            } else {
                items(order, step.forEach).flatMap { expand(listOf(step), step.requires, step.whenMissing, order, it, index, picked) }
            }
        }
        is EachStep -> {
            val index = partners(MissionValidator.unitsOf(step), order)
            items(order, step.forEach).flatMap { expand(step.steps, step.requires, step.whenMissing, order, it, index, picked) }
        }
        is ChoiceStep -> {
            val choice = step.pick(resolve(step.on, Scope(order, null, null)))
            picked.pick(step.id, choice, step.steps(choice)?.flatMap { top(it, order, picked) } ?: listOf(folded(step, Scope(order, null, null))))
        }
    }

    /** 분기의 접힘 — 조건 값이 없으면 `whenMissing.unit` 의 단위 하나를 `FAILED` 로 남긴다. 칸은 조건부 포함의 접힘과 같다. */
    private fun folded(choice: ChoiceStep, scope: Scope): ExecutionUnit {
        val node = checkNotNull(kept(listOf(choice), choice.whenMissing))
        return unit(node, scope, UnitState.FAILED, choice.whenMissing.failureClass) { name -> name in choice.whenMissing.parameters }
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

    /**
     * 접힘이 남길 노드 — [WhenMissing.unit] 이 있으면 그 id 의 단위 노드(그룹 안 분기의 갈래까지 찾는다), 없으면 노드가 단위 노드
     * 하나일 때 그것.
     */
    private fun kept(nodes: List<MissionStep>, whenMissing: WhenMissing): UnitStep? =
        if (whenMissing.unit != null) {
            nodes.flatMap(MissionValidator::unitsOf).firstOrNull { it.id == whenMissing.unit }
        } else {
            nodes.singleOrNull() as? UnitStep
        }

    /**
     * 항목 하나(once 면 작업 지시 하나)에서 노드들을 차례로 펼친다. 단위 노드 하나도 «노드 하나짜리 그룹» 으로 여기서 펼친다.
     *
     * [requires] 가 하나라도 값을 못 내면 [WhenMissing] 대로 단위 하나로 접는다 — 남는 단위의 칸은 정상 해석 그대로이고 파라미터만
     * 목록에 든 것이며 선행은 없다. 아니면 노드마다 단위 하나이고, 선행(`after`)은 **같은 항목에서** 그 노드가 낸 단위 id 다
     * (대상끼리 묶지 않는다 — 한 대상의 이동이 실패해도 다음 대상은 간다).
     *
     * 그룹 안 분기(§15.328)는 **이 항목의** 조건 값으로 갈래 하나를 골라 그 노드만 펼치고([picked] 에 `분기 id[설비 id]` 로 적는다),
     * 값이 없으면 분기의 접힘대로 단위 하나를 `FAILED` 로 남긴다(선행 없음). 갈래 노드의 선행은 그 분기 앞 형제나 같은 갈래의 앞
     * 노드가 이 항목에서 낸 단위 id 다.
     *
     * 짝 규칙은 코드 `PrepareSequencedRack` 과 같다. 후보는 짝 쓰임의 설비 중 맞출 속성이 있는 것이고, 같은 값이 여럿이면
     * **목록에서 뒤엣것**이다(`associateBy`). 반복 중인 설비에 그 속성이 없으면 짝이 없다.
     */
    private fun expand(
        nodes: List<GroupStep>,
        requires: List<ValueSource>,
        whenMissing: WhenMissing?,
        order: JobOrder,
        item: EquipmentRequirement?,
        index: Map<String, Map<String, EquipmentRequirement>>,
        picked: Picks,
    ): List<ExecutionUnit> {
        val scope = { node: UnitStep ->
            val paired = node.pairWith?.let { p -> item?.properties?.get(p.property)?.let { index[node.id]?.get(it) } }
            Scope(order, item, paired)
        }
        val plain = Scope(order, item, null)
        val head = (nodes.first() as? UnitStep)?.let(scope) ?: plain
        val missing = whenMissing != null && requires.any { resolve(it, head) == null }
        if (missing) {
            val node = checkNotNull(kept(nodes, whenMissing))
            return listOf(
                unit(node, scope(node), UnitState.FAILED, whenMissing.failureClass) { name -> name in whenMissing.parameters },
            )
        }
        val produced = mutableMapOf<String, String>()
        val emit = { node: UnitStep ->
            val at = scope(node)
            // 짝이 없으면 시작도 못 한다 — 부족은 계획에서 드러난다(코드 케이퍼빌리티와 같은 자리).
            val unpaired = node.pairWith != null && at.paired == null
            unit(node, at, if (unpaired) UnitState.FAILED else UnitState.PENDING, if (unpaired) node.whenUnpaired else null) { true }
                .apply { after = node.after?.let { produced.getValue(it) } }
                .also { produced[node.id] = it.unitId }
        }
        return nodes.flatMap { node ->
            when (node) {
                is UnitStep -> listOf(emit(node))
                is ChoiceStep -> {
                    val choice = node.pick(resolve(node.on, plain))
                    picked.pick("${node.id}[${item?.id}]", choice, node.steps(choice)?.map { emit(it as UnitStep) } ?: listOf(folded(node, plain)))
                }
            }
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

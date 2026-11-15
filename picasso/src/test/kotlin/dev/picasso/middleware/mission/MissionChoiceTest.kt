package dev.picasso.middleware.mission

import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.EquipmentUse
import dev.picasso.middleware.FloorOwnership
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.Route
import dev.picasso.middleware.UnitState
import dev.picasso.middleware.WaitSpec
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 접수 시점 분기(`kind: "choice"`, §15.328) — 고르는 규칙 셋(첫 일치·기본 갈래·부재 접힘)과 항목마다 고르기, 파서가 막는 모양
 * (중첩·조건 출처·갈래 칸), 검증기가 넓힌 검사(모든 갈래의 정적 검사, 갈래를 넘는 선행, 갈래 사이 단위 id 모양, 접힘의 단위 id).
 *
 * 리비전·실행·리빌드·재작업은 엔진을 지나므로 `MissionChoiceScenarioTest` 가 본다.
 */
class MissionChoiceTest {

    private val at: Instant = Instant.parse("2026-11-16T09:00:00Z")
    private val inspect = DefinedCapability(MissionFixtures.parsed(MissionFixtures.INSPECT_BY_ITEM))
    private val carry = DefinedCapability(MissionFixtures.parsed(MissionFixtures.CARRY_BY_MODE))

    private fun target(id: String, item: String?) = EquipmentRequirement(
        id, EquipmentUse.INSPECTION_TARGET,
        mapOfNotNull(EquipmentUse.PROP_LOCATION to "$id-SPOT", item?.let { EquipmentUse.PROP_ITEM to it }),
    )

    private fun mapOfNotNull(vararg pairs: Pair<String, String>?) = pairs.filterNotNull().toMap()

    private fun patrol(vararg targets: EquipmentRequirement) =
        JobOrder("PATROL-9", "InspectByItem", 1, parameters = mapOf("mode" to "scan"), equipmentRequirements = targets.toList())

    private fun delivery(carrier: String?) = JobOrder(
        "DLV-1", "CarryByMode", 1,
        parameters = listOfNotNull(carrier?.let { "carrier" to it }).toMap(),
        equipmentRequirements = listOf(
            EquipmentRequirement("DOCK-1", EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_CONTAINER to "TOTE-7")),
            EquipmentRequirement("LINE-3", EquipmentUse.DESTINATION),
        ),
    )

    private fun refusals(text: String, skills: Set<String> = MissionFixtures.SITE_SKILLS): List<MissionRefusal> =
        MissionValidator.validate(MissionFixtures.parsed(text), MissionFixtures.SIGNALS, FloorOwnership.None, skills, at)

    private fun problems(text: String): List<String> =
        assertIs<MissionParse.Unreadable>(MissionDefinitionParser.parse(text)).problems

    private fun only(kind: MissionRefusalKind, found: List<MissionRefusal>): MissionRefusal {
        assertEquals(listOf(kind), found.map { it.kind }, "그 종류 하나만 걸려야 한다: $found")
        return found.single()
    }

    // ── 고르는 규칙

    @Test
    fun `두 갈래가 다 맞으면 선언 순서 첫 갈래만 펼친다`() {
        val plan = inspect.plan(patrol(target("PUMP-01", "thermal")))
        assertEquals(listOf("PUMP-01.travel", "PUMP-01"), plan.map { it.unitId }, "thermal 은 두 갈래에 다 맞는다 — 앞 갈래 하나만")
        val thermal = plan[1]
        assertEquals(UnitState.PENDING to "PUMP-01.travel", thermal.state to thermal.after, "갈래 노드의 선행은 분기 앞 형제의 단위 id 다")
        assertEquals(mapOf("target" to "PUMP-01", "mode" to "scan"), thermal.parameters)
        assertEquals(mapOf("how[PUMP-01]" to "arms[0]"), inspect.choices(patrol(target("PUMP-01", "thermal"))))
    }

    @Test
    fun `값이 어느 갈래와도 안 맞으면 기본 갈래이고 빈 기본 갈래는 단위를 내지 않는다`() {
        val order = patrol(target("COMP-2", "acoustic"))
        assertEquals(listOf("COMP-2.travel"), inspect.plan(order).map { it.unitId })
        assertEquals(mapOf("how[COMP-2]" to ChoiceStep.OTHERWISE), inspect.choices(order))
        // 빈 문자열은 값이다 — 접히지 않고 기본 갈래로 간다.
        assertEquals(listOf("COMP-2.travel"), inspect.plan(patrol(target("COMP-2", ""))).map { it.unitId })
    }

    @Test
    fun `값이 없으면 접힘대로 단위 하나를 FAILED 로 남기고 파라미터는 목록의 것만이며 선행이 없다`() {
        val order = patrol(target("PANEL-3", null))
        val plan = inspect.plan(order)
        assertEquals(listOf("PANEL-3.travel", "PANEL-3"), plan.map { it.unitId }, "부재는 기본 갈래가 아니라 실패 단위 하나다")
        val folded = plan[1]
        assertEquals(UnitState.FAILED to "NO_ITEM_FOR_TARGET", folded.state to folded.failureClass)
        assertEquals(mapOf("target" to "PANEL-3"), folded.parameters, "남길 파라미터 목록 밖의 mode 가 남았다")
        assertNull(folded.after, "접힌 단위가 선행을 들었다")
        assertEquals("inspect" to "PANEL-3-SPOT", folded.skillType to folded.destination, "접힌 단위의 다른 칸은 정상 해석 그대로다")
        assertEquals(UnitState.PENDING, plan[0].state, "분기 밖 형제는 접힘과 무관하다")
        assertEquals(mapOf("how[PANEL-3]" to ChoiceStep.MISSING), inspect.choices(order))
    }

    @Test
    fun `그룹 안 분기는 항목마다 고른다`() {
        val order = patrol(target("PUMP-01", "thermal"), target("PANEL-3", "visual"), target("COMP-2", "acoustic"), target("FAN-4", null))
        val plan = inspect.plan(order)
        assertEquals(
            listOf("PUMP-01.travel", "PUMP-01", "PANEL-3.travel", "PANEL-3.look", "COMP-2.travel", "FAN-4.travel", "FAN-4"),
            plan.map { it.unitId },
        )
        assertEquals("PANEL-3.travel", plan.single { it.unitId == "PANEL-3.look" }.after, "선행이 다른 항목의 이동으로 풀렸다")
        assertEquals(
            mapOf("how[PUMP-01]" to "arms[0]", "how[PANEL-3]" to "arms[1]", "how[COMP-2]" to "otherwise", "how[FAN-4]" to "whenMissing"),
            inspect.choices(order),
        )
    }

    @Test
    fun `최상위 분기는 작업 지시마다 한 번 고르고 갈래 안 대기 노드와 단위 노드를 최상위 노드처럼 펼친다`() {
        val fleet = carry.plan(delivery("fleet"))
        assertEquals(listOf("TOTE-7" to Route.FLEET), fleet.map { it.unitId to it.route })
        val robot = carry.plan(delivery("robot"))
        assertEquals(listOf(MissionFixtures.WAIT_NODE to Route.SIGNAL, "DLV-1.carry" to Route.ROBOT), robot.map { it.unitId to it.route })
        assertEquals(MissionFixtures.RACK_PRESENT, robot[0].parameters[WaitSpec.P_SIGNAL])
        assertEquals(emptyList(), carry.plan(delivery("drone")), "기본 갈래가 비었는데 단위가 났다")
        val folded = carry.plan(delivery(null)).single()
        assertEquals(Triple("TOTE-7", UnitState.FAILED, "NO_CARRIER"), Triple(folded.unitId, folded.state, folded.failureClass))
        assertEquals(emptyMap(), folded.parameters)
        assertEquals(mapOf("carrier" to "arms[1]"), carry.choices(delivery("robot")))
        assertEquals(mapOf("carrier" to ChoiceStep.MISSING), carry.choices(delivery(null)))
    }

    @Test
    fun `분기를 안 쓰는 정의는 고른 갈래가 없다`() {
        listOf(MissionFixtures.PREPARE_SEQUENCED_RACK, MissionFixtures.INSPECT_ASSET, MissionFixtures.DELIVER_CONTAINER, MissionFixtures.MANUFACTURING_INSPECTION)
            .forEach { assertEquals(emptyMap(), DefinedCapability(MissionFixtures.parsed(it)).choices(patrol(target("PUMP-01", "thermal")))) }
    }

    // ── 파서

    @Test
    fun `분기의 칸을 읽는다`() {
        val group = assertIs<EachStep>(MissionFixtures.parsed(MissionFixtures.INSPECT_BY_ITEM).steps.single())
        val choice = assertIs<ChoiceStep>(group.steps[1])
        assertEquals(ValueSource(ValueFrom.ITEM_PROPERTY, property = "item"), choice.on)
        assertEquals(listOf(listOf("thermal"), listOf("visual", "thermal")), choice.arms.map { it.values })
        assertEquals(listOf(listOf("thermal"), listOf("look"), emptyList()), choice.branches.map { b -> b.map { it.id } })
        assertEquals(WhenMissing("NO_ITEM_FOR_TARGET", "thermal", listOf("target")), choice.whenMissing)
        val top = assertIs<ChoiceStep>(MissionFixtures.parsed(MissionFixtures.CARRY_BY_MODE).steps.single())
        assertIs<WaitStep>(top.arms[1].steps[0])
    }

    @Test
    fun `분기는 중첩되지 않고 갈래 안에 그룹이 오지 않으며 그룹 안 분기의 갈래에는 대기가 오지 않는다`() {
        val nested = MissionFixtures.CARRY_BY_MODE.replace(
            "{\"kind\": \"wait\", \"id\": \"${MissionFixtures.WAIT_NODE}\"",
            "{\"kind\": \"choice\", \"id\": \"inner\", \"on\": {\"from\": \"ORDER_PARAMETER\", \"parameter\": \"x\"}, \"arms\": [], " +
                "\"otherwise\": {\"steps\": []}, \"whenMissing\": {\"failureClass\": \"X\", \"unit\": \"carry\", \"parameters\": []}},\n" +
                "{\"kind\": \"wait\", \"id\": \"${MissionFixtures.WAIT_NODE}\"",
        )
        assertTrue(problems(nested).single().startsWith("$.steps[0].arms[1].steps[0].kind: 갈래 안에는 unit·wait 만 온다(choice)"), "${problems(nested)}")
        val group = nested.replace("{\"kind\": \"choice\", \"id\": \"inner\"", "{\"kind\": \"each\", \"id\": \"inner\"")
        assertTrue(problems(group).single().startsWith("$.steps[0].arms[1].steps[0].kind: 갈래 안에는 unit·wait 만 온다(each)"), "${problems(group)}")
        val waitInGroup = MissionFixtures.INSPECT_BY_ITEM.replace("\"kind\": \"unit\",\n\"id\": \"look\",", "\"kind\": \"wait\",\n\"id\": \"look\",")
        assertTrue(
            problems(waitInGroup).single().startsWith("$.steps[0].steps[1].arms[1].steps[0].kind: 그룹 안 분기의 갈래에는 unit 만 온다(wait)"),
            "${problems(waitInGroup)}",
        )
    }

    @Test
    fun `분기 조건은 작업 지시 값이고 대체값이 없으며 자리마다 출처가 다르다`() {
        val on = "\"on\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"item\" },"
        val id = MissionFixtures.INSPECT_BY_ITEM.replace(on, "\"on\": { \"from\": \"ITEM_ID\" },")
        assertTrue(problems(id).single().startsWith("$.steps[0].steps[1].on.from: ORDER_PARAMETER·ITEM_PROPERTY·FIRST_PROPERTY 중 하나"), "${problems(id)}")
        val fallback = MissionFixtures.INSPECT_BY_ITEM.replace(on, "\"on\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"item\", \"otherwise\": \"thermal\" },")
        assertTrue(problems(fallback).single().startsWith("$.steps[0].steps[1].on.otherwise: 분기 조건에는 대체값이 오지 않는다"), "${problems(fallback)}")
        val first = MissionFixtures.INSPECT_BY_ITEM.replace(on, "\"on\": { \"from\": \"FIRST_PROPERTY\", \"equipmentUse\": \"source\", \"property\": \"item\" },")
        assertTrue(problems(first).single().startsWith("$.steps[0].steps[1].on.from: FIRST_ID·FIRST_PROPERTY 는 once 노드에만"), "${problems(first)}")
        val carrier = "\"on\": { \"from\": \"ORDER_PARAMETER\", \"parameter\": \"carrier\" },"
        val item = MissionFixtures.CARRY_BY_MODE.replace(carrier, "\"on\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"carrier\" },")
        assertTrue(problems(item).single().startsWith("$.steps[0].on.from: once 노드에는 반복 중인 설비가 없다"), "${problems(item)}")
        val firstTop = MissionFixtures.CARRY_BY_MODE.replace(carrier, "\"on\": { \"from\": \"FIRST_PROPERTY\", \"equipmentUse\": \"source\", \"property\": \"carrier\" },")
        assertIs<MissionParse.Parsed>(MissionDefinitionParser.parse(firstTop))
    }

    @Test
    fun `갈래·기본 갈래·접힘의 칸을 엄격히 읽는다`() {
        val both = MissionFixtures.INSPECT_BY_ITEM.replace("{ \"equals\": \"thermal\", \"steps\"", "{ \"equals\": \"thermal\", \"in\": [\"x\"], \"steps\"")
        assertTrue(problems(both).single().startsWith("$.steps[0].steps[1].arms[0]: equals 와 in 중 하나만 온다"), "${problems(both)}")
        val emptyIn = MissionFixtures.INSPECT_BY_ITEM.replace("\"in\": [\"visual\", \"thermal\"]", "\"in\": []")
        assertTrue(problems(emptyIn).single().startsWith("$.steps[0].steps[1].arms[1].in: 값이 하나도 없다"), "${problems(emptyIn)}")
        val noOtherwise = MissionFixtures.INSPECT_BY_ITEM.replace("\"otherwise\": { \"steps\": [] },\n", "")
        assertEquals(listOf("$.steps[0].steps[1].otherwise: 빠졌다"), problems(noOtherwise))
        val noWhenMissing = MissionFixtures.INSPECT_BY_ITEM.replace(",\n\"whenMissing\": { \"failureClass\": \"NO_ITEM_FOR_TARGET\", \"unit\": \"thermal\", \"parameters\": [\"target\"] }", "")
        assertEquals(listOf("$.steps[0].steps[1].whenMissing: 빠졌다"), problems(noWhenMissing))
        val noUnit = MissionFixtures.INSPECT_BY_ITEM.replace("\"unit\": \"thermal\", ", "")
        assertEquals(listOf("$.steps[0].steps[1].whenMissing.unit: 빠졌다"), problems(noUnit))
        val noArms = MissionFixtures.CARRY_BY_MODE.substringBefore("\"arms\": [") + "\"arms\": [],\n\"otherwise\": { \"steps\": [] },\n" +
            "\"whenMissing\": { \"failureClass\": \"NO_CARRIER\", \"unit\": \"deliver\", \"parameters\": [] }\n}\n]\n}"
        assertEquals(listOf("$.steps[0].arms: 갈래가 하나도 없다"), problems(noArms))
        val emptyArm = MissionFixtures.CARRY_BY_MODE.substringBefore("{ \"equals\": \"robot\"") + "{ \"equals\": \"robot\", \"steps\": [] }\n],\n" +
            MissionFixtures.CARRY_BY_MODE.substringAfter("] }\n],\n")
        assertEquals(listOf("$.steps[0].arms[1].steps: 노드가 하나도 없다"), problems(emptyArm))
    }

    // ── 검증기

    @Test
    fun `분기를 쓴 정의가 통과한다`() {
        assertEquals(emptyList(), refusals(MissionFixtures.INSPECT_BY_ITEM))
        assertEquals(emptyList(), refusals(MissionFixtures.CARRY_BY_MODE))
        assertEquals(emptyList(), refusals(MissionFixtures.FILL_BY_LANE))
    }

    @Test
    fun `고르지 않을 갈래의 없는 스킬과 없는 신호도 활성화를 거부한다`() {
        val skill = only(MissionRefusalKind.SKILL_NOT_ON_SITE, refusals(MissionFixtures.CARRY_BY_MODE, skills = setOf("navigate_to", "inspect")))
        assertEquals("carry", skill.nodeId)
        val signal = only(
            MissionRefusalKind.SIGNAL_NOT_IN_SPEC,
            refusals(MissionFixtures.CARRY_BY_MODE.replace("\"signal\": \"${MissionFixtures.RACK_PRESENT}\"", "\"signal\": \"rack_ready\"")),
        )
        assertEquals(MissionFixtures.WAIT_NODE, signal.nodeId)
        val deadline = only(MissionRefusalKind.DEADLINE_INVALID, refusals(MissionFixtures.CARRY_BY_MODE.replace("\"deadlineSeconds\": 120", "\"deadlineSeconds\": 0")))
        assertEquals(MissionFixtures.WAIT_NODE, deadline.nodeId)
        val observation = only(
            MissionRefusalKind.SOURCE_OBSERVATION_UNDECLARED,
            refusals(MissionFixtures.CARRY_BY_MODE.replace("\"sourceObservation\": \"OPTIONAL\",\n", "")),
        )
        assertEquals("carry", observation.nodeId)
        val inGroup = only(MissionRefusalKind.SKILL_NOT_IN_CONTRACT, refusals(MissionFixtures.INSPECT_BY_ITEM.replace("\"id\": \"look\",\n\"skill\": \"inspect\"", "\"id\": \"look\",\n\"skill\": \"teleport\"")))
        assertEquals("look", inGroup.nodeId)
    }

    @Test
    fun `분기와 갈래 안 노드도 노드 id 의 한 이름 공간이다`() {
        val arm = only(MissionRefusalKind.DUPLICATE_NODE_ID, refusals(MissionFixtures.INSPECT_BY_ITEM.replace("\"id\": \"look\",", "\"id\": \"travel\",")))
        assertEquals("travel", arm.nodeId, "갈래 안 노드 id 가 분기 밖 형제와 겹쳐도 안 걸렸다")
        val choice = only(MissionRefusalKind.DUPLICATE_NODE_ID, refusals(MissionFixtures.INSPECT_BY_ITEM.replace("\"id\": \"how\",", "\"id\": \"visit\",")))
        assertEquals("visit", choice.nodeId, "분기 id 가 그룹 id 와 겹쳐도 안 걸렸다")
    }

    @Test
    fun `갈래를 넘는 선행을 거부한다`() {
        val cross = only(MissionRefusalKind.AFTER_INVALID, refusals(MissionFixtures.INSPECT_BY_ITEM.replace("\"id\": \"look\",\n\"skill\": \"inspect\",\n\"after\": \"travel\",", "\"id\": \"look\",\n\"skill\": \"inspect\",\n\"after\": \"thermal\",")))
        assertEquals("look" to "after=thermal", cross.nodeId to cross.observed)
        assertEquals("같은 그룹의 앞 단위 노드 id(travel)", cross.expected, "다른 갈래의 노드가 앞 노드로 보였다")
        // 분기 밖 뒤 형제가 갈래 안 노드를 가리키는 것도 갈래를 넘는다.
        val after = MissionFixtures.INSPECT_BY_ITEM.replace(
            "\"whenMissing\": { \"failureClass\": \"NO_ITEM_FOR_TARGET\", \"unit\": \"thermal\", \"parameters\": [\"target\"] }\n}",
            "\"whenMissing\": { \"failureClass\": \"NO_ITEM_FOR_TARGET\", \"unit\": \"thermal\", \"parameters\": [\"target\"] }\n},\n" +
                "{ \"kind\": \"unit\", \"id\": \"report\", \"skill\": \"inspect\", \"after\": \"thermal\", \"unitId\": { \"from\": \"ITEM_ID\", \"suffix\": \".report\" }, \"parameters\": {} }",
        )
        val outside = only(MissionRefusalKind.AFTER_INVALID, refusals(after))
        assertEquals("report" to "같은 그룹의 앞 단위 노드 id(travel)", outside.nodeId to outside.expected)
        // 최상위 분기의 갈래 노드는 최상위 노드다 — 선행이 없다.
        val top = MissionFixtures.CARRY_BY_MODE.replace("\"id\": \"carry\",", "\"id\": \"carry\",\n\"after\": \"deliver\",")
        assertEquals("같은 그룹의 앞 단위 노드 id(없음)", only(MissionRefusalKind.AFTER_INVALID, refusals(top)).expected)
        assertFailsWith<IllegalArgumentException> { DefinedCapability(MissionFixtures.parsed(top)) }
    }

    @Test
    fun `갈래 사이 단위 id 모양이 같으면 거부한다`() {
        val group = only(MissionRefusalKind.UNIT_ID_SHAPE_COLLISION, refusals(MissionFixtures.INSPECT_BY_ITEM.replace("\"unitId\": { \"from\": \"ITEM_ID\", \"suffix\": \".look\" },", "\"unitId\": { \"from\": \"ITEM_ID\" },")))
        assertEquals("look" to "thermal, look: ITEM_ID", group.nodeId to group.observed)
        // 최상위 분기 — 다른 갈래의 두 once 노드. 같은 쌍을 두 번 알리지 않는다.
        val top = MissionFixtures.CARRY_BY_MODE.replace("\"unitId\": { \"from\": \"ORDER_ID\", \"suffix\": \".carry\" },", "\"unitId\": { \"from\": \"ORDER_ID\" },")
        val once = only(MissionRefusalKind.UNIT_ID_SHAPE_COLLISION, refusals(top))
        assertEquals("carry" to "deliver, carry: ORDER_ID", once.nodeId to once.observed)
        // 최상위 분기의 반복 노드도 갈래 사이에서 본다(once 노드끼리만이 아니다).
        val loops = MissionFixtures.CARRY_BY_MODE
            .replace("\"id\": \"carry\",\n\"once\": true,", "\"id\": \"carry\",\n\"forEach\": \"destination\",")
            .replace("\"unitId\": { \"from\": \"ORDER_ID\", \"suffix\": \".carry\" },", "\"unitId\": { \"from\": \"ITEM_ID\" },")
            .replace("\"parameters\": { \"object_id\": $CONTAINER },", "\"parameters\": {},")
            .replace("\"expectedIdentity\": $CONTAINER,\n\"source\": $FIRST_SOURCE,\n\"sourceObservation\": \"OPTIONAL\",\n\"destinationObservation\": \"OPTIONAL\",\n\"destination\": $FIRST_DESTINATION", "\"destination\": { \"from\": \"ITEM_ID\" }")
        val twin = loops.replace("{ \"equals\": \"fleet\", \"steps\": [", "{ \"equals\": \"fleet\", \"steps\": [\n" +
            "{ \"kind\": \"unit\", \"id\": \"stage\", \"skill\": \"navigate_to\", \"forEach\": \"source\", \"unitId\": { \"from\": \"ITEM_ID\" }, \"parameters\": {} },")
        assertEquals(emptyList(), refusals(loops), "고친 문서가 다른 데서 걸린다")
        val crossLoop = only(MissionRefusalKind.UNIT_ID_SHAPE_COLLISION, refusals(twin))
        assertEquals("carry" to "stage, carry: ITEM_ID", crossLoop.nodeId to crossLoop.observed)
    }

    @Test
    fun `분기의 접힘은 갈래 안 노드를 남기고 그 단위 id 가 조건 값 없이 값을 내야 한다`() {
        val stray = only(MissionRefusalKind.WHEN_MISSING_INVALID, refusals(MissionFixtures.INSPECT_BY_ITEM.replace("\"unit\": \"thermal\"", "\"unit\": \"travel\"")))
        assertEquals("how" to "unit=travel", stray.nodeId to stray.observed, "분기 밖 형제를 남길 노드로 받았다")
        assertEquals("남길 안쪽 노드 id(thermal, look)", stray.expected)
        val parameter = only(MissionRefusalKind.WHEN_MISSING_INVALID, refusals(MissionFixtures.INSPECT_BY_ITEM.replace("\"parameters\": [\"target\"]", "\"parameters\": [\"location\"]")))
        assertEquals("parameters=location", parameter.observed)
        // 최상위 분기는 반복 중인 설비가 없다 — 반복 노드(ITEM_ID)를 남기면 계획 때 단위 id 가 없다.
        val loops = MissionFixtures.CARRY_BY_MODE.replace(
            "{ \"equals\": \"fleet\", \"steps\": [",
            "{ \"equals\": \"fleet\", \"steps\": [\n{ \"kind\": \"unit\", \"id\": \"stage\", \"skill\": \"navigate_to\", \"forEach\": \"source\", \"unitId\": { \"from\": \"ITEM_ID\", \"suffix\": \".stage\" }, \"parameters\": {} },",
        )
        assertEquals(emptyList(), refusals(loops))
        val item = loops.replace("\"unit\": \"deliver\"", "\"unit\": \"stage\"")
        val noValue = refusals(item)
        assertEquals(listOf(MissionRefusalKind.WHEN_MISSING_INVALID, MissionRefusalKind.WHEN_MISSING_INVALID), noValue.map { it.kind }, "$noValue")
        assertEquals(listOf("stage.unitId=ITEM_ID.stage", "unit=stage(forEach)"), noValue.map { it.observed })
        assertEquals("조건 값 없이 값을 내는 단위 id(ORDER_ID 또는 else: ORDER_ID)", noValue[0].expected)
        // 대체값으로 값은 내도 반복 노드다 — 최상위 분기의 접힘은 작업 지시마다 하나라 once 노드만 남긴다(그래야 once 노드끼리의 모양 비교에 든다).
        val constant = loops.replace("\"unitId\": { \"from\": \"ITEM_ID\", \"suffix\": \".stage\" }", "\"unitId\": { \"from\": \"ITEM_ID\", \"otherwise\": \"TOTE-7\" }")
            .replace("\"unit\": \"deliver\"", "\"unit\": \"stage\"")
        val loop = only(MissionRefusalKind.WHEN_MISSING_INVALID, refusals(constant))
        assertEquals("unit=stage(forEach)" to "작업 지시마다 한 번인 노드(once)", loop.observed to loop.expected)
        assertFailsWith<IllegalArgumentException> { DefinedCapability(MissionFixtures.parsed(constant)) }
        assertFailsWith<IllegalArgumentException> { DefinedCapability(MissionFixtures.parsed(item)) }
    }

    @Test
    fun `최상위 분기의 갈래 안 단위 노드의 조건부 포함도 남길 파라미터를 본다`() {
        val conditional = MissionFixtures.CARRY_BY_MODE.replace(
            "\"id\": \"carry\",\n\"once\": true,",
            "\"id\": \"carry\",\n\"once\": true,\n\"requires\": [ $FIRST_DESTINATION ],\n\"whenMissing\": { \"failureClass\": \"NO_DESTINATION\", \"parameters\": [\"object_id\"] },",
        )
        assertEquals(emptyList(), refusals(conditional))
        val stray = only(MissionRefusalKind.WHEN_MISSING_INVALID, refusals(conditional.replace("\"parameters\": [\"object_id\"]", "\"parameters\": [\"nope\"]")))
        assertEquals("carry" to "parameters=nope", stray.nodeId to stray.observed)
    }

    @Test
    fun `분기가 든 그룹의 쓰임에 같은 설비 id 가 둘이면 계획 거부 사유를 낸다`() {
        val clash = patrol(target("PUMP-01", "thermal"), target("PUMP-01", "acoustic"))
        assertEquals(
            "분기 자리가 겹쳐 받지 않는다: how[PUMP-01] — 분기가 든 반복 그룹의 설비 id 가 같은 쓰임 안에서 겹친다",
            inspect.planRefusal(clash),
        )
        assertNull(inspect.planRefusal(patrol(target("PUMP-01", "thermal"), target("PANEL-3", "thermal"))))
        assertNull(DefinedCapability(MissionFixtures.parsed(MissionFixtures.INSPECT_ASSET)).planRefusal(clash), "분기 없는 정의가 거부 사유를 냈다")
    }

    @Test
    fun `분기 자리마다 그 자리가 낸 단위 id 를 안다`() {
        val order = patrol(target("PUMP-01", "thermal"), target("PANEL-3", "visual"), target("COMP-2", "acoustic"), target("FAN-4", null))
        assertEquals(
            mapOf("how[PUMP-01]" to listOf("PUMP-01"), "how[PANEL-3]" to listOf("PANEL-3.look"), "how[COMP-2]" to emptyList(), "how[FAN-4]" to listOf("FAN-4")),
            inspect.choiceUnits(order),
        )
        assertEquals(mapOf("carrier" to listOf(MissionFixtures.WAIT_NODE, "DLV-1.carry")), carry.choiceUnits(delivery("robot")))
    }

    private companion object {
        const val CONTAINER = "{ \"from\": \"FIRST_PROPERTY\", \"equipmentUse\": \"source\", \"property\": \"container\" }"
        const val FIRST_SOURCE = "{ \"from\": \"FIRST_ID\", \"equipmentUse\": \"source\" }"
        const val FIRST_DESTINATION = "{ \"from\": \"FIRST_ID\", \"equipmentUse\": \"destination\" }"
    }
}

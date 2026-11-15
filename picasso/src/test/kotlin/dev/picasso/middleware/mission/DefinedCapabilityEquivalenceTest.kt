package dev.picasso.middleware.mission

import dev.picasso.middleware.DeadlineOutcome
import dev.picasso.middleware.DeliverContainer
import dev.picasso.middleware.DestinationObservation
import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.EquipmentUse
import dev.picasso.middleware.ExecutionUnit
import dev.picasso.middleware.FloorOwnership
import dev.picasso.middleware.InspectAsset
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.LogicalCapability
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.middleware.Route
import dev.picasso.middleware.SourceObservation
import dev.picasso.middleware.UnitState
import dev.picasso.middleware.WaitSpec
import java.lang.reflect.Modifier
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * **데이터로 옮긴 코드 정의 셋(`PrepareSequencedRack`·`InspectAsset`·`DeliverContainer`)이 코드와 같은 계획을 내는가** — 작업 지시
 * 모양마다, 단위마다, 칸마다.
 *
 * 기존 `SequencingRackTest` 의 작업 지시 도우미는 제시 자리를 material 로 묶은 맵으로 만들어 «같은 material 이 둘» 모양이
 * 없다. 그래서 여기서 모양을 새로 짓는다 — 짝 규칙(뒤엣것이 이긴다)이 갈리는 자리가 바로 그 모양이다. 점검·운반도 같은 까닭으로
 * 갈래가 갈리는 모양(위치 없음과 mode 있음이 함께, 첫 source 에 container 가 없고 둘째에 있음)을 여기서 짓는다(§15.325).
 */
class DefinedCapabilityEquivalenceTest {

    private val code = PrepareSequencedRack()
    private val data = DefinedCapability(MissionFixtures.parsed(MissionFixtures.PREPARE_SEQUENCED_RACK))
    private val inspectCode = InspectAsset()
    private val inspectData = DefinedCapability(MissionFixtures.parsed(MissionFixtures.INSPECT_ASSET))
    private val deliverCode = DeliverContainer()
    private val deliverData = DefinedCapability(MissionFixtures.parsed(MissionFixtures.DELIVER_CONTAINER))

    private fun slot(id: String, material: String?) =
        EquipmentRequirement(id, EquipmentUse.DESTINATION, material?.let { mapOf(EquipmentUse.PROP_MATERIAL to it) }.orEmpty())

    private fun bin(id: String, material: String?) =
        EquipmentRequirement(id, EquipmentUse.SOURCE, material?.let { mapOf(EquipmentUse.PROP_MATERIAL to it) }.orEmpty())

    private fun order(vararg equipment: EquipmentRequirement) =
        JobOrder("SEQ-9", PrepareSequencedRack.WORK_MASTER, version = 1, equipmentRequirements = equipment.toList())

    /** 이름 붙인 작업 지시 모양들. 이름이 실패 메시지에 나온다. */
    private val shapes: Map<String, JobOrder> = mapOf(
        "제시 자리 있음" to order(slot("S01", "A"), slot("S02", "B"), bin("BIN-A", "A"), bin("BIN-B", "B")),
        "제시 자리 없음" to order(slot("S01", "A"), slot("S02", "C"), bin("BIN-A", "A")),
        "같은 material 둘 — 뒤엣것이 이긴다" to order(bin("BIN-A1", "A"), slot("S01", "A"), bin("BIN-A2", "A"), slot("S02", "A")),
        "material 없는 슬롯" to order(slot("S01", null), slot("S02", "A"), bin("BIN-A", "A")),
        "material 없는 제시 자리" to order(slot("S01", "A"), bin("BIN-X", null), bin("BIN-A", "A")),
        "목적지 0개" to order(bin("BIN-A", "A")),
        "무관한 쓰임" to order(
            EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_MATERIAL to "A")),
            slot("S01", "A"),
            EquipmentRequirement("BIN-Z", "staging", mapOf(EquipmentUse.PROP_MATERIAL to "A")),
            bin("BIN-A", "A"),
        ),
        "제시 자리가 슬롯보다 앞" to order(bin("BIN-B", "B"), bin("BIN-A", "A"), slot("S01", "A"), slot("S02", "B"), slot("S03", "A")),
        "빈 작업 지시" to order(),
    )

    private fun target(id: String, location: String?, item: String? = null) = EquipmentRequirement(
        id, EquipmentUse.INSPECTION_TARGET,
        listOfNotNull(location?.let { EquipmentUse.PROP_LOCATION to it }, item?.let { EquipmentUse.PROP_ITEM to it }).toMap(),
    )

    private fun patrol(parameters: Map<String, String>, vararg equipment: EquipmentRequirement) =
        JobOrder("PATROL-9", InspectAsset.WORK_MASTER, version = 1, parameters = parameters, equipmentRequirements = equipment.toList())

    private val inspectShapes: Map<String, JobOrder> = mapOf(
        "대상 하나" to patrol(emptyMap(), target("PUMP-01", "PUMP-ROOM")),
        "대상 여럿 — 대상마다 이동 뒤 점검" to patrol(emptyMap(), target("PUMP-01", "PUMP-ROOM"), target("PANEL-3", "SWITCHGEAR"), target("COMP-2", "BAY")),
        "위치 없음 단독" to patrol(emptyMap(), target("VALVE-9", null)),
        "위치 없음이 사이에 낌" to patrol(emptyMap(), target("PUMP-01", "PUMP-ROOM"), target("VALVE-9", null), target("COMP-2", "BAY")),
        "mode 있음" to patrol(mapOf(InspectAsset.P_MODE to "thermal"), target("PUMP-01", "PUMP-ROOM"), target("PANEL-3", "SWITCHGEAR")),
        "mode 없음 — 다른 파라미터만" to patrol(mapOf("shift" to "night"), target("PUMP-01", "PUMP-ROOM")),
        "mode 빈 값" to patrol(mapOf(InspectAsset.P_MODE to ""), target("PUMP-01", "PUMP-ROOM")),
        "위치 없음과 mode 있음이 함께" to patrol(mapOf(InspectAsset.P_MODE to "thermal"), target("VALVE-9", null), target("PUMP-01", "PUMP-ROOM")),
        "대상 0개" to patrol(mapOf(InspectAsset.P_MODE to "thermal"), EquipmentRequirement("S01", EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_LOCATION to "X"))),
        "무관한 쓰임 섞임" to patrol(
            emptyMap(),
            EquipmentRequirement("BIN-A", EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_LOCATION to "DOCK")),
            target("PUMP-01", "PUMP-ROOM"),
            EquipmentRequirement("mission-1", "mission_target", mapOf(EquipmentUse.PROP_LOCATION to "bay-7")),
            target("PANEL-3", "SWITCHGEAR"),
        ),
        "item 속성 있음" to patrol(emptyMap(), target("PUMP-01", "PUMP-ROOM", item = "thermal"), target("VALVE-9", null, item = "leak")),
        "location 빈 문자열 — 값이다" to patrol(emptyMap(), target("PUMP-01", "")),
        "빈 작업 지시" to patrol(emptyMap()),
    )

    private fun dock(id: String, container: String?) =
        EquipmentRequirement(id, EquipmentUse.SOURCE, container?.let { mapOf(EquipmentUse.PROP_CONTAINER to it) }.orEmpty())

    private fun line(id: String) = EquipmentRequirement(id, EquipmentUse.DESTINATION)

    private fun delivery(vararg equipment: EquipmentRequirement) =
        JobOrder("WMS-42", DeliverContainer.WORK_MASTER, version = 1, equipmentRequirements = equipment.toList())

    private val deliverShapes: Map<String, JobOrder> = mapOf(
        "정상" to delivery(dock("DOCK-A", "C-1"), line("LINE-3")),
        "source 없음" to delivery(line("LINE-3")),
        "destination 없음" to delivery(dock("DOCK-A", "C-1")),
        "container 없음" to delivery(dock("DOCK-A", null), line("LINE-3")),
        "셋 다 없음 — 단위 id 는 작업 지시 id" to delivery(target("PUMP-01", "PUMP-ROOM")),
        "source 둘·destination 둘 — 첫 것" to delivery(line("LINE-3"), dock("DOCK-A", "C-1"), line("LINE-4"), dock("DOCK-B", "C-2")),
        "첫 source 에 container 없고 둘째에 있음 — 실패" to delivery(dock("DOCK-A", null), dock("DOCK-B", "C-2"), line("LINE-3")),
        "container 빈 문자열 — 값이다" to delivery(dock("DOCK-A", ""), line("LINE-3")),
        "빈 작업 지시" to delivery(),
    )

    /** 두 단위의 뒷받침 필드 가운데 값이 다른 것의 이름. 단위는 같음 비교가 없는 일반 클래스라 필드로 댄다 — 새 칸도 저절로 든다. */
    private fun differing(e: ExecutionUnit, a: ExecutionUnit): List<String> =
        ExecutionUnit::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .onEach { it.isAccessible = true }
            .filter { it.get(e) != it.get(a) }
            .map { it.name }

    /** 대조하는 칸. 하나씩 이름을 붙여 어느 칸이 갈렸는지 보이게 한다. */
    private val fields: Map<String, (ExecutionUnit) -> Any?> = mapOf(
        "단위 id" to { it.unitId },
        "경로" to { it.route },
        "스킬" to { it.skillType },
        "파라미터" to { it.parameters },
        "기대 식별" to { it.expectedIdentity },
        "제시 자리" to { it.source },
        "목적지" to { it.destination },
        "상태" to { it.state },
        "실패 분류" to { it.failureClass },
        "대기 사양" to { it.wait },
        "출발 관측" to { it.sourceObservation },
        "목적지 관측" to { it.destinationObservation },
        "선행" to { it.after },
    )

    @Test
    fun `작업 지시 모양마다 데이터 정의가 코드와 같은 계획을 칸마다 낸다`() {
        assertSamePlans(code, data, shapes)
    }

    @Test
    fun `점검 — 작업 지시 모양마다 데이터 정의가 코드와 같은 계획을 칸마다 낸다`() {
        assertSamePlans(inspectCode, inspectData, inspectShapes)
    }

    @Test
    fun `운반 — 작업 지시 모양마다 데이터 정의가 코드와 같은 계획을 칸마다 낸다`() {
        assertSamePlans(deliverCode, deliverData, deliverShapes)
    }

    private fun assertSamePlans(code: LogicalCapability, data: LogicalCapability, shapes: Map<String, JobOrder>) {
        shapes.forEach { (shape, order) ->
            val expected = code.plan(order)
            val actual = data.plan(order)
            assertEquals(expected.size, actual.size, "$shape: 단위 수가 갈렸다")
            expected.zip(actual).forEachIndexed { at, (e, a) ->
                fields.forEach { (field, of) ->
                    assertEquals(of(e), of(a), "$shape: ${at + 1}번째 단위의 $field 이 갈렸다")
                }
                // 칸 목록이 낡으면(새 칸이 생기면) 위 대조가 빠뜨린다 — 뒷받침 필드 전부로도 댄다(§15.309, 단위는 일반 클래스다).
                assertEquals(emptyList(), differing(e, a), "$shape: ${at + 1}번째 단위가 갈렸다")
            }
        }
    }

    @Test
    fun `모양들이 짝 규칙의 갈래를 전부 밟는다`() {
        // ★대조가 무언가를 가리려면 갈래가 실제로 나와야 한다 — 전부 짝이 있으면 실패 분류 칸은 늘 널끼리 같다.
        val planned = shapes.values.flatMap { code.plan(it) }
        assertTrue(planned.any { it.state == UnitState.FAILED && it.failureClass == PrepareSequencedRack.NO_SOURCE })
        assertTrue(planned.any { it.state == UnitState.PENDING })
        assertTrue(planned.any { it.expectedIdentity == null }, "material 없는 슬롯이 안 나왔다")
        assertTrue(planned.any { it.source == "BIN-A2" }, "같은 material 의 뒤엣것이 안 나왔다")
        assertTrue(shapes.values.any { code.plan(it).isEmpty() }, "목적지 0개가 안 나왔다")
    }

    @Test
    fun `점검 모양들이 접힘·선행·mode 의 갈래를 전부 밟는다`() {
        val planned = inspectShapes.values.flatMap { inspectCode.plan(it) }
        val failed = planned.filter { it.state == UnitState.FAILED }
        assertTrue(failed.any { it.failureClass == InspectAsset.NO_LOCATION && it.parameters.keys == setOf(InspectAsset.P_TARGET) })
        assertTrue(failed.all { it.after == null && it.destination == null }, "접힌 점검에 선행이나 목적지가 있다")
        assertTrue(planned.any { it.skillType == InspectAsset.NAVIGATE && it.state == UnitState.PENDING }, "이동이 안 나왔다")
        assertTrue(planned.any { it.after == "${it.unitId}${InspectAsset.TRAVEL_SUFFIX}" }, "선행이 안 나왔다")
        assertTrue(planned.any { it.parameters[InspectAsset.P_MODE] == "thermal" }, "mode 있음이 안 나왔다")
        assertTrue(planned.any { it.parameters[InspectAsset.P_MODE] == "" }, "mode 빈 값이 안 나왔다")
        assertTrue(planned.any { it.skillType == InspectAsset.INSPECT && InspectAsset.P_MODE !in it.parameters && it.state == UnitState.PENDING })
        assertTrue(planned.any { it.destination == "" }, "location 빈 문자열이 안 나왔다")
        // ★위치 없음과 mode 가 함께인 모양 — 접힌 점검이 mode 를 들면 whenMissing.parameters 를 무시한 것이다.
        assertTrue(inspectCode.plan(inspectShapes.getValue("위치 없음과 mode 있음이 함께")).first().let { it.state == UnitState.FAILED && InspectAsset.P_MODE !in it.parameters })
        assertTrue(inspectShapes.values.any { inspectCode.plan(it).isEmpty() }, "대상 0개가 안 나왔다")
    }

    @Test
    fun `운반 모양들이 첫 것·실패·대체 단위 id 의 갈래를 전부 밟는다`() {
        val planned = deliverShapes.mapValues { (_, order) -> deliverCode.plan(order).single() }
        assertTrue(planned.values.all { it.route == Route.FLEET && it.skillType == DeliverContainer.TRANSPORT })
        assertTrue(planned.values.any { it.state == UnitState.PENDING && it.parameters.size == 3 })
        assertTrue(planned.values.filter { it.state == UnitState.FAILED }.all { it.failureClass == DeliverContainer.INCOMPLETE_ORDER && it.parameters.isEmpty() })
        assertTrue(planned.values.any { it.state == UnitState.FAILED && it.unitId == "WMS-42" }, "작업 지시 id 단위가 안 나왔다")
        assertTrue(planned.values.any { it.state == UnitState.FAILED && it.unitId == "C-1" }, "container 를 든 실패가 안 나왔다")
        assertEquals("DOCK-A" to "LINE-3", planned.getValue("source 둘·destination 둘 — 첫 것").let { it.source to it.destination })
        // ★값을 가진 첫 source(DOCK-B)를 고르면 이 모양이 정상으로 나간다 — 코드는 첫 source 만 본다.
        assertEquals(UnitState.FAILED to "WMS-42", planned.getValue("첫 source 에 container 없고 둘째에 있음 — 실패").let { it.state to it.unitId })
    }

    @Test
    fun `최고 근거 등급과 선택 파라미터와 WorkMaster 가 같다`() {
        listOf(code to data, inspectCode to inspectData, deliverCode to deliverData).forEach { (code, data) ->
            assertEquals(code.workMasterId, data.workMasterId)
            assertEquals(code.maxEvidence, data.maxEvidence, code.workMasterId)
            assertEquals(code.preferredOptionals, data.preferredOptionals, code.workMasterId)
            assertEquals(code.evidenceWindow, data.evidenceWindow, code.workMasterId)
            assertEquals(code.inDoubtGrace, data.inDoubtGrace, code.workMasterId)
            assertEquals(code.stallWindow, data.stallWindow, code.workMasterId)
        }
    }

    @Test
    fun `mission-ops 예제 모양의 버전 1 정의는 대상마다 점검 하나이고 선행이 없다`() {
        val capability = DefinedCapability(MissionFixtures.parsed(MissionFixtures.MANUFACTURING_INSPECTION))
        val order = JobOrder(
            "MI-1", "ManufacturingInspection", version = 1,
            equipmentRequirements = listOf(
                EquipmentRequirement("T1", "mission_target", mapOf(EquipmentUse.PROP_LOCATION to "bay-7")),
                EquipmentRequirement("T2", "mission_target"),
            ),
        )
        val planned = capability.plan(order)
        assertEquals(listOf("T1", "T2"), planned.map { it.unitId })
        assertEquals(listOf("bay-7", null), planned.map { it.destination })
        assertTrue(planned.all { it.route == Route.ROBOT && it.state == UnitState.PENDING && it.after == null && it.parameters == mapOf("target" to it.unitId) })
    }

    @Test
    fun `검증을 거치지 않은 선행과 접힘은 해석기가 받지 않는다`() {
        // 엔진은 참조한 단위가 없으면 기다리지 않고 낸다 — 끊긴 선행은 조용히 순서를 잃는다.
        val broken = listOf(
            MissionFixtures.INSPECT_ASSET.replace("\"after\": \"travel\"", "\"after\": \"nowhere\""),
            MissionFixtures.INSPECT_ASSET.replace("\"after\": \"travel\"", "\"after\": \"inspect\""),
            MissionFixtures.PREPARE_SEQUENCED_RACK.replace("\"id\": \"place\",", "\"id\": \"place\", \"after\": \"place\","),
            MissionFixtures.INSPECT_ASSET.replace("\"unit\": \"inspect\", ", ""),
            MissionFixtures.INSPECT_ASSET.replace("\"unit\": \"inspect\"", "\"unit\": \"nowhere\""),
        )
        broken.forEach { text ->
            assertFailsWith<IllegalArgumentException>(text) { DefinedCapability(MissionFixtures.parsed(text)) }
        }
    }

    @Test
    fun `대기 노드는 신호 경로의 단위 하나가 되고 사양을 파라미터에도 싣는다`() {
        val capability = DefinedCapability(MissionFixtures.parsed(MissionFixtures.withArrivalWait(deadlineSeconds = 90, onDeadline = DeadlineOutcome.ABORTED)))
        val planned = capability.plan(shapes.getValue("제시 자리 있음"))

        val wait = planned.first()
        assertEquals(MissionFixtures.WAIT_NODE, wait.unitId)
        assertEquals(Route.SIGNAL, wait.route)
        assertEquals(WaitSpec.SKILL_TYPE, wait.skillType)
        assertEquals(UnitState.PENDING, wait.state)
        assertEquals(WaitSpec(MissionFixtures.RACK_PRESENT, "true", Duration.ofSeconds(90), DeadlineOutcome.ABORTED), wait.wait)
        assertEquals(
            mapOf("signal" to "rack_present", "expect" to "true", "deadlineSeconds" to "90", "onDeadline" to "ABORTED"),
            wait.parameters,
        )
        // 대기 뒤는 버전 1 과 같은 계획이다.
        val expected = code.plan(shapes.getValue("제시 자리 있음"))
        assertEquals(expected.size, planned.size - 1)
        expected.zip(planned.drop(1)).forEachIndexed { at, (e, a) -> assertEquals(emptyList(), differing(e, a), "${at + 1}번째 단위가 갈렸다") }
    }

    @Test
    fun `대기 단위의 스킬 이름은 계약 카탈로그와 겹치지 않는다`() {
        // 겹치면 효과·사전 조건·선택 파라미터를 읽는 자리가 대기 단위를 그 스킬로 잘못 읽는다.
        assertTrue(MissionValidator.contractSkills.isNotEmpty())
        assertTrue(WaitSpec.SKILL_TYPE !in MissionValidator.contractSkills)
    }

    @Test
    fun `선언한 출발 관측이 단위에 실리고 선언 없는 신원 대조는 해석기가 받지 않는다`() {
        val required = DefinedCapability(MissionFixtures.parsed(MissionFixtures.prepareSequencedRack("REQUIRED")))
        val planned = required.plan(shapes.getValue("제시 자리 있음"))
        assertTrue(planned.isNotEmpty())
        assertEquals(listOf(SourceObservation.REQUIRED), planned.map { it.sourceObservation }.distinct())
        // 코드 케이퍼빌리티는 검증기를 거치지 않으므로 오늘의 거동(OPTIONAL)이다 — 동등성 시험의 «출발 관측» 칸이 그것을 댄다.
        assertEquals(listOf(SourceObservation.OPTIONAL), code.plan(shapes.getValue("제시 자리 있음")).map { it.sourceObservation }.distinct())

        // 기본값으로 메우면 검증기가 없앤 암묵이 이 길로 돌아온다.
        assertFailsWith<IllegalArgumentException> {
            DefinedCapability(MissionFixtures.parsed(MissionFixtures.prepareSequencedRack(null)))
        }
    }

    @Test
    fun `선언한 목적지 관측이 단위에 실리고 선언 없는 목적지 식별 정보는 해석기가 받지 않는다`() {
        val required = DefinedCapability(
            MissionFixtures.parsed(MissionFixtures.prepareSequencedRack("OPTIONAL", destinationObservation = "REQUIRED")),
        )
        val planned = required.plan(shapes.getValue("제시 자리 있음"))
        assertTrue(planned.isNotEmpty())
        assertEquals(listOf(DestinationObservation.REQUIRED), planned.map { it.destinationObservation }.distinct())
        assertEquals(listOf(SourceObservation.OPTIONAL), planned.map { it.sourceObservation }.distinct(), "목적지 선언이 출발 칸에 실렸다")
        // 코드 케이퍼빌리티는 오늘의 동작(OPTIONAL, 보지 않는다)이다 — 동등성 시험의 «목적지 관측» 칸이 그것을 댄다.
        assertEquals(
            listOf(DestinationObservation.OPTIONAL),
            code.plan(shapes.getValue("제시 자리 있음")).map { it.destinationObservation }.distinct(),
        )

        // 기본값으로 메우면 검증기가 없앤 암묵이 이 길로 돌아온다.
        assertFailsWith<IllegalArgumentException> {
            DefinedCapability(MissionFixtures.parsed(MissionFixtures.prepareSequencedRack("OPTIONAL", destinationObservation = null)))
        }
    }

    @Test
    fun `검증을 거치지 않은 기한은 해석기가 받지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            DefinedCapability(MissionFixtures.parsed(MissionFixtures.withArrivalWait(deadlineSeconds = 0)))
        }
        assertFailsWith<IllegalArgumentException> {
            DefinedCapability(
                MissionFixtures.parsed(MissionFixtures.withArrivalWait().replace("\"OPERATOR_HOLD\"", "\"RETRY\"")),
            )
        }
    }

    @Test
    fun `최상위 반복 노드의 조건은 설비마다 접고 작업 지시 파라미터도 조건이 된다`() {
        val text = MissionFixtures.PREPARE_SEQUENCED_RACK.replace(
            "\"whenUnpaired\": \"NO_SOURCE_FOR_MATERIAL\",",
            "\"whenUnpaired\": \"NO_SOURCE_FOR_MATERIAL\",\n" +
                "\"requires\": [ { \"from\": \"ITEM_PROPERTY\", \"property\": \"material\" }, { \"from\": \"ORDER_PARAMETER\", \"parameter\": \"lot\" } ],\n" +
                "\"whenMissing\": { \"failureClass\": \"NO_LOT\", \"parameters\": [\"destination\"] },",
        )
        val definition = MissionFixtures.parsed(text)
        assertEquals(emptyList(), MissionValidator.validate(definition, MissionFixtures.SIGNALS, FloorOwnership.None, MissionFixtures.SITE_SKILLS, Instant.EPOCH))
        val capability = DefinedCapability(definition)
        val base = shapes.getValue("material 없는 슬롯")

        // 파라미터가 있으면 material 있는 슬롯은 코드와 같고, material 없는 슬롯만 접힌다.
        val withLot = base.copy(parameters = mapOf("lot" to "L-1"))
        val (s01, s02) = capability.plan(withLot)
        assertEquals(UnitState.FAILED to "NO_LOT", s01.state to s01.failureClass)
        assertEquals(mapOf("destination" to "S01"), s01.parameters)
        assertEquals(emptyList(), differing(code.plan(withLot)[1], s02))

        // 파라미터가 없으면 모두 접히고, 접혀도 출발·기대 식별은 정상 해석 그대로다.
        val planned = capability.plan(base)
        assertEquals(listOf("NO_LOT", "NO_LOT"), planned.map { it.failureClass })
        assertEquals(listOf(null, "BIN-A"), planned.map { it.source })
        assertTrue(planned.all { it.parameters.keys == setOf("destination") && it.after == null })
    }

    @Test
    fun `조건만 있고 접힘이 없는 정의는 해석기가 받지 않는다`() {
        val parsed = MissionFixtures.parsed(MissionFixtures.DELIVER_CONTAINER)
        val broken = parsed.copy(steps = parsed.steps.map { (it as UnitStep).copy(whenMissing = null) })
        assertFailsWith<IllegalArgumentException> { DefinedCapability(broken) }
    }

    @Test
    fun `짝 색인은 노드마다 한 번 짓는다 — 설비 수에 따라 작업 지시를 다시 훑지 않는다`() {
        // 옛 units() 와 같다. 설비마다 다시 지으면 슬롯 n 개에 작업 지시를 n 번 더 훑는다.
        val equipment = (1..40).map { slot("S%02d".format(it), "A") } + bin("BIN-A", "A")
        var walks = 0
        val counted = object : AbstractList<EquipmentRequirement>() {
            override val size get() = equipment.size
            override fun get(index: Int) = equipment[index]
            override fun iterator(): Iterator<EquipmentRequirement> = equipment.iterator().also { walks++ }
        }
        val planned = data.plan(JobOrder("SEQ-9", PrepareSequencedRack.WORK_MASTER, version = 1, equipmentRequirements = counted))
        assertEquals(40, planned.size)
        assertTrue(walks <= 2, "작업 지시를 $walks 번 훑었다 — 짝 색인을 설비마다 다시 지었다")
    }
}

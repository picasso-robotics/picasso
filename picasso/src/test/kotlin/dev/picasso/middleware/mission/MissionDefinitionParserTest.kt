package dev.picasso.middleware.mission

import dev.picasso.middleware.DestinationObservation
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.Route
import dev.picasso.middleware.SourceObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 임무 정의의 엄격한 읽기 — 모르는 키·빠진 칸·형이 틀린 값을 **모두 모아 한 번에** 알린다.
 *
 * 값의 판정(기한이 양수인가, 기한 뒤 상태가 허용된 값인가)은 여기서 안 본다 — 검증기 시험이 본다. 파서가 그것을
 * 거부하면 검증기의 기한 검사를 빼는 결함이 파서에 가려진다.
 */
class MissionDefinitionParserTest {

    private fun problems(text: String): List<String> =
        assertIs<MissionParse.Unreadable>(MissionDefinitionParser.parse(text)).problems

    @Test
    fun `코드 케이퍼빌리티를 옮긴 문서를 그대로 읽는다`() {
        val definition = MissionFixtures.parsed(MissionFixtures.withArrivalWait())

        assertEquals(1, definition.schemaVersion)
        assertEquals("PrepareSequencedRack", definition.workMasterId)
        assertEquals(Evidence.E2, definition.maxEvidence)
        assertEquals(mapOf("verify_grasp" to "true"), definition.preferredOptionals)
        assertEquals(listOf("rack-arrival", "place"), definition.steps.map { it.id })

        val wait = assertIs<WaitStep>(definition.steps[0])
        assertEquals(WaitStep("rack-arrival", "rack_present", "true", 120, "OPERATOR_HOLD"), wait)

        val unit = assertIs<UnitStep>(definition.steps[1])
        assertEquals(Pairing("source", "material"), unit.pairWith)
        assertEquals("NO_SOURCE_FOR_MATERIAL", unit.whenUnpaired)
        assertEquals(ValueSource(ValueFrom.PAIRED_ID, otherwise = ""), unit.parameters["object_id"])
        assertEquals(ValueSource(ValueFrom.ITEM_PROPERTY, property = "material"), unit.expectedIdentity)
    }

    @Test
    fun `모르는 키와 빠진 칸과 형이 틀린 값을 한 번에 모은다`() {
        val text = """
            {
              "schemaVersion": 1.5,
              "workMasterId": "PrepareSequencedRack",
              "maxEvidence": "E3",
              "preferredOptionals": { "verify_grasp": true },
              "colour": "red",
              "steps": [
                { "kind": "wait", "id": "w", "signal": "rack_present", "expect": "true",
                  "deadlineSeconds": "soon", "onDeadline": 7, "retry": 2 },
                { "kind": "unit", "id": "u", "skill": "pick_place",
                  "unitId": { "from": "ITEM_ID" }, "parameters": {} },
                { "kind": "jump", "id": "j" }
              ]
            }
        """.trimIndent()

        val found = problems(text)

        val expected = listOf(
            "$.schemaVersion: 정수여야",
            "$.maxEvidence: E0·E1·E2 중 하나",
            "$.preferredOptionals.verify_grasp: 문자열이어야",
            "$: 모르는 키 'colour'",
            "$.steps[0]: 모르는 키 'retry'",
            "$.steps[0].deadlineSeconds: 정수여야",
            "$.steps[0].onDeadline: 문자열이어야",
            "$.steps[1].forEach: 빠졌다",
            "$.steps[2].kind: unit·wait·each 중 하나여야",
        )
        expected.forEach { needle ->
            assertTrue(found.any { it.startsWith(needle) }, "'$needle' 이 안 모였다: $found")
        }
        assertEquals(expected.size, found.size, "모은 것이 기대와 다르다: $found")
    }

    @Test
    fun `기한의 값은 판정하지 않는다 — 없거나 음수거나 허용 밖이어도 읽는다`() {
        // ★검증기의 기한 검사가 이 자리를 맡는다. 여기서 거부하면 그 검사를 빼는 결함이 안 보인다.
        val text = MissionFixtures.withArrivalWait(deadlineSeconds = -5)
            .replace("\"onDeadline\": \"OPERATOR_HOLD\"", "\"onDeadline\": \"RETRY\"")
        val wait = assertIs<WaitStep>(MissionFixtures.parsed(text).steps[0])
        assertEquals(-5, wait.deadlineSeconds)
        assertEquals("RETRY", wait.onDeadline)

        val bare = MissionFixtures.withArrivalWait()
            .replace(", \"deadlineSeconds\": 120", "")
            .replace(", \"onDeadline\": \"OPERATOR_HOLD\"", "")
        val missing = assertIs<WaitStep>(MissionFixtures.parsed(bare).steps[0])
        assertNull(missing.deadlineSeconds)
        assertNull(missing.onDeadline)
    }

    @Test
    fun `같은 키가 둘이면 거부한다 — 어느 쪽을 뜻했는지 모른다`() {
        // `JsonFormat` 은 말없이 뒤엣것을 쓴다. 그러면 앞의 값이 조용히 사라진다.
        val text = MissionFixtures.withArrivalWait()
            .replace("\"deadlineSeconds\": 120", "\"deadlineSeconds\": 120, \"deadlineSeconds\": 5")
        assertEquals(listOf("$.steps[0]: 키 'deadlineSeconds' 가 두 번 나온다"), problems(text))
    }

    @Test
    fun `JSON 이 아닌 것과 뒤에 붙은 글자를 거부한다`() {
        assertTrue(problems("{ steps: [] }").single().startsWith("$: 키는 큰따옴표 문자열이어야 한다"))
        assertTrue(problems(MissionFixtures.PREPARE_SEQUENCED_RACK + " x").single().startsWith("$: JSON 값 뒤에 글자가 더 있다"))
        assertTrue(problems("[1, 2]").single().startsWith("$: JSON 객체가 아니다"))
    }

    @Test
    fun `짝 규칙과 실패 분류는 함께 오고 짝 없이 짝의 값을 못 쓴다`() {
        val noClass = MissionFixtures.PREPARE_SEQUENCED_RACK.replace("\"whenUnpaired\": \"NO_SOURCE_FOR_MATERIAL\",", "")
        assertTrue(problems(noClass).single().startsWith("$.steps[0]: pairWith 와 whenUnpaired 는 함께 온다"))

        // 짝 규칙을 통째로 빼면 짝의 값을 쓰는 두 칸(object_id 파라미터, source)이 각각 걸린다.
        val noPairing = noClass.replace("\"pairWith\": { \"equipmentUse\": \"source\", \"property\": \"material\" },", "")
        val found = problems(noPairing)
        assertEquals(2, found.count { "PAIRED_ID 인데 이 노드에 pairWith 가 없다" in it }, "$found")
        assertEquals(2, found.size, "$found")
    }

    @Test
    fun `단위 id 는 반드시 값을 내는 출처만 받는다`() {
        // 값을 못 낼 수 있는 출처(속성)는 else 로 언제나 값을 내는 출처를 둘 때만 받는다(§15.325).
        val text = MissionFixtures.PREPARE_SEQUENCED_RACK
            .replace("\"unitId\": { \"from\": \"ITEM_ID\" }", "\"unitId\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"material\" }")
        assertTrue(problems(text).single().startsWith("$.steps[0].unitId.from: ITEM_ID 나 ORDER_ID 여야 한다"))

        val fallback = MissionFixtures.PREPARE_SEQUENCED_RACK.replace(
            "\"unitId\": { \"from\": \"ITEM_ID\" }",
            "\"unitId\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"material\", \"else\": { \"from\": \"ITEM_ID\" } }",
        )
        val unit = assertIs<UnitStep>(MissionFixtures.parsed(fallback).steps[0])
        assertEquals(ValueSource(ValueFrom.ITEM_ID), unit.unitId.orElse)
    }

    @Test
    fun `값 출처의 속성은 속성 출처에만 오고 모르는 출처는 거부한다`() {
        val stray = MissionFixtures.PREPARE_SEQUENCED_RACK
            .replace("\"destination\": { \"from\": \"ITEM_ID\" }\n}", "\"destination\": { \"from\": \"ITEM_ID\", \"property\": \"x\" }\n}")
        assertTrue(problems(stray).single().startsWith("$.steps[0].destination.property: ITEM_PROPERTY·FIRST_PROPERTY 에만 온다"))

        val unknown = MissionFixtures.PREPARE_SEQUENCED_RACK.replace("\"from\": \"PAIRED_ID\", \"otherwise\"", "\"from\": \"ORDER\", \"otherwise\"")
        assertTrue(
            problems(unknown).single().startsWith(
                "$.steps[0].parameters.object_id.from: ITEM_ID·ITEM_PROPERTY·PAIRED_ID·ORDER_ID·ORDER_PARAMETER·FIRST_ID·FIRST_PROPERTY 중 하나",
            ),
        )
    }

    @Test
    fun `출발 관측은 REQUIRED 나 OPTIONAL 만 읽고 없어도 읽는다`() {
        val required = assertIs<UnitStep>(MissionFixtures.parsed(MissionFixtures.prepareSequencedRack("REQUIRED")).steps[0])
        assertEquals(SourceObservation.REQUIRED, required.sourceObservation)
        // 없음은 파서가 아니라 검증기가 거부한다 — 여기서 막으면 검증기의 출발 관측 검사를 빼는 결함이 가려진다.
        val bare = assertIs<UnitStep>(MissionFixtures.parsed(MissionFixtures.prepareSequencedRack(null)).steps[0])
        assertNull(bare.sourceObservation)

        val maybe = problems(MissionFixtures.prepareSequencedRack("MAYBE"))
        assertTrue(maybe.single().startsWith("$.steps[0].sourceObservation: REQUIRED·OPTIONAL 중 하나여야 한다"), "$maybe")
        val flag = problems(MissionFixtures.PREPARE_SEQUENCED_RACK.replace("\"sourceObservation\": \"OPTIONAL\"", "\"sourceObservation\": true"))
        assertTrue(flag.single().startsWith("$.steps[0].sourceObservation: 문자열이어야 한다"), "$flag")
    }

    @Test
    fun `목적지 관측은 REQUIRED 나 OPTIONAL 만 읽고 없어도 읽는다`() {
        val required = assertIs<UnitStep>(
            MissionFixtures.parsed(MissionFixtures.prepareSequencedRack("OPTIONAL", destinationObservation = "REQUIRED")).steps[0],
        )
        assertEquals(DestinationObservation.REQUIRED, required.destinationObservation)
        assertEquals(SourceObservation.OPTIONAL, required.sourceObservation, "목적지 선언이 출발 선언 칸에 들었다")
        // 없음은 파서가 아니라 검증기가 거부한다 — 여기서 거부하면 검증기의 목적지 관측 검사를 빼는 결함이 가려진다.
        val bare = assertIs<UnitStep>(
            MissionFixtures.parsed(MissionFixtures.prepareSequencedRack("OPTIONAL", destinationObservation = null)).steps[0],
        )
        assertNull(bare.destinationObservation)

        val maybe = problems(MissionFixtures.prepareSequencedRack("OPTIONAL", destinationObservation = "MAYBE"))
        assertTrue(maybe.single().startsWith("$.steps[0].destinationObservation: REQUIRED·OPTIONAL 중 하나여야 한다"), "$maybe")
    }

    @Test
    fun `모르는 문서 버전과 빈 노드 목록을 거부한다`() {
        val v2 = MissionFixtures.PREPARE_SEQUENCED_RACK.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        assertTrue(problems(v2).single().startsWith("$.schemaVersion: 모르는 문서 버전이다"))

        val empty = """{"schemaVersion":1,"workMasterId":"W","maxEvidence":"E0","preferredOptionals":{},"steps":[]}"""
        assertEquals(listOf("$.steps: 노드가 하나도 없다"), problems(empty))
    }

    // ── 버전 1 에 더한 칸(§15.325). 칸마다 정상·형이 틀림·자리가 틀림을 댄다.

    @Test
    fun `새 칸을 안 쓴 버전 1 문서는 새 칸의 기본값으로 읽힌다`() {
        val unit = assertIs<UnitStep>(MissionFixtures.parsed(MissionFixtures.PREPARE_SEQUENCED_RACK).steps.single())
        assertEquals("destination", unit.forEach)
        assertEquals(false, unit.once)
        assertEquals(Route.ROBOT, unit.route)
        assertEquals(emptyList(), unit.requires)
        assertNull(unit.whenMissing)
        assertNull(unit.after)
        assertEquals(ValueSource(ValueFrom.ITEM_ID), unit.unitId)

        val example = assertIs<UnitStep>(MissionFixtures.parsed(MissionFixtures.MANUFACTURING_INSPECTION).steps.single())
        assertEquals("mission_target", example.forEach)
        assertEquals(ValueSource(ValueFrom.ITEM_PROPERTY, property = "location"), example.destination)
    }

    @Test
    fun `반복 그룹과 접미사와 작업 지시 값과 조건과 선행을 읽는다`() {
        val group = assertIs<EachStep>(MissionFixtures.parsed(MissionFixtures.INSPECT_ASSET).steps.single())
        assertEquals("visit" to "inspection_target", group.id to group.forEach)
        assertEquals(listOf(ValueSource(ValueFrom.ITEM_PROPERTY, property = "location")), group.requires)
        assertEquals(WhenMissing("NO_LOCATION_FOR_TARGET", "inspect", listOf("target")), group.whenMissing)
        assertEquals(listOf("travel", "inspect"), group.steps.map { it.id })
        val (travel, inspect) = group.steps
        assertEquals(ValueSource(ValueFrom.ITEM_ID, suffix = ".travel"), travel.unitId)
        assertNull(travel.forEach)
        assertEquals("travel", inspect.after)
        assertEquals(ValueSource(ValueFrom.ORDER_PARAMETER, parameter = "mode"), inspect.parameters["mode"])
    }

    @Test
    fun `once 와 플릿 경로와 첫 설비 출처와 단위 id 의 대체를 읽는다`() {
        val unit = assertIs<UnitStep>(MissionFixtures.parsed(MissionFixtures.DELIVER_CONTAINER).steps.single())
        assertTrue(unit.once)
        assertNull(unit.forEach)
        assertEquals(Route.FLEET, unit.route)
        val container = ValueSource(ValueFrom.FIRST_PROPERTY, property = "container", equipmentUse = "source")
        assertEquals(container.copy(orElse = ValueSource(ValueFrom.ORDER_ID)), unit.unitId)
        assertEquals(listOf(ValueSource(ValueFrom.FIRST_ID, equipmentUse = "source"), ValueSource(ValueFrom.FIRST_ID, equipmentUse = "destination"), container), unit.requires)
        assertEquals(WhenMissing("INCOMPLETE_ORDER", null, emptyList()), unit.whenMissing)
    }

    @Test
    fun `그룹 안 단위 노드는 반복과 조건을 갖지 않고 그룹 안에는 단위 노드만 온다`() {
        val inner = MissionFixtures.INSPECT_ASSET.replace("\"id\": \"travel\",", "\"id\": \"travel\", \"forEach\": \"x\", \"once\": true,")
        val found = problems(inner)
        assertEquals(
            listOf("$.steps[0].steps[0].forEach", "$.steps[0].steps[0].once"),
            found.map { it.substringBefore(":") },
            "$found",
        )
        assertTrue(found.all { "그룹 안 단위 노드에는 오지 않는다" in it }, "$found")

        val wait = MissionFixtures.INSPECT_ASSET.replace("\"kind\": \"unit\",\n\"id\": \"travel\",", "\"kind\": \"wait\",\n\"id\": \"travel\",")
        assertTrue(problems(wait).single().startsWith("$.steps[0].steps[0].kind: 그룹 안에는 unit 만 온다(wait)"), "${problems(wait)}")

        val empty = MissionFixtures.INSPECT_ASSET.substringBefore("\"steps\": [\n{\n\"kind\": \"unit\"") + "\"steps\": [] } ] }"
        assertTrue(problems(empty).any { it.startsWith("$.steps[0].steps: 노드가 하나도 없다") }, "${problems(empty)}")
        val noForEach = MissionFixtures.INSPECT_ASSET.replace("\"forEach\": \"inspection_target\",", "")
        assertEquals(listOf("$.steps[0].forEach: 빠졌다"), problems(noForEach))
    }

    @Test
    fun `once 는 forEach 와 배타이고 once 노드에는 반복 중인 설비의 값이 없으며 첫 설비 출처는 once 에만 온다`() {
        val both = MissionFixtures.DELIVER_CONTAINER.replace("\"once\": true,", "\"once\": true, \"forEach\": \"source\",")
        assertTrue(problems(both).single().startsWith("$.steps[0]: once 와 forEach 는 함께 오지 않는다"), "${problems(both)}")
        val flag = MissionFixtures.DELIVER_CONTAINER.replace("\"once\": true,", "\"once\": \"yes\",")
        assertTrue(problems(flag).any { it.startsWith("$.steps[0].once: 참거짓이어야 한다") }, "${problems(flag)}")
        val item = MissionFixtures.DELIVER_CONTAINER.replace("\"expectedIdentity\": {", "\"expectedIdentity\": { \"from\": \"ITEM_ID\" }, \"x\": {")
        assertTrue(problems(item).any { it.startsWith("$.steps[0].expectedIdentity.from: once 노드에는 반복 중인 설비가 없다") }, "${problems(item)}")
        val first = MissionFixtures.MANUFACTURING_INSPECTION.replace(
            "\"target\": {\"from\": \"ITEM_ID\"}",
            "\"target\": {\"from\": \"FIRST_ID\", \"equipmentUse\": \"source\"}",
        )
        assertTrue(problems(first).single().startsWith("$.steps[0].parameters.target.from: FIRST_ID·FIRST_PROPERTY 는 once 노드에만 온다"), "${problems(first)}")
    }

    @Test
    fun `접미사와 대체는 unitId 에만 오고 대체는 언제나 값을 내는 출처 한 단계다`() {
        val suffix = MissionFixtures.INSPECT_ASSET.replace("\"target\": { \"from\": \"ITEM_ID\" }", "\"target\": { \"from\": \"ITEM_ID\", \"suffix\": \".x\" }")
        assertTrue(problems(suffix).single().startsWith("$.steps[0].steps[1].parameters.target.suffix: unitId 에만 온다"), "${problems(suffix)}")
        val orElse = MissionFixtures.INSPECT_ASSET.replace("\"target\": { \"from\": \"ITEM_ID\" }", "\"target\": { \"from\": \"ITEM_ID\", \"else\": { \"from\": \"ORDER_ID\" } }")
        assertTrue(problems(orElse).single().startsWith("$.steps[0].steps[1].parameters.target.else: unitId 에만 한 단계로 온다"), "${problems(orElse)}")
        val blank = MissionFixtures.INSPECT_ASSET.replace("\"suffix\": \".travel\"", "\"suffix\": \"\"")
        assertTrue(problems(blank).single().startsWith("$.steps[0].steps[0].unitId.suffix: 비었다"), "${problems(blank)}")
        val maybe = MissionFixtures.DELIVER_CONTAINER.replace("\"else\": { \"from\": \"ORDER_ID\" }", "\"else\": { \"from\": \"FIRST_ID\", \"equipmentUse\": \"source\" }")
        assertTrue(problems(maybe).single().startsWith("$.steps[0].unitId.else.from: ITEM_ID 나 ORDER_ID 여야 한다(FIRST_ID)"), "${problems(maybe)}")
        val none = MissionFixtures.DELIVER_CONTAINER.replace(", \"else\": { \"from\": \"ORDER_ID\" }", "")
        assertTrue(problems(none).single().startsWith("$.steps[0].unitId.from: ITEM_ID 나 ORDER_ID 여야 한다(FIRST_PROPERTY)"), "${problems(none)}")
    }

    @Test
    fun `경로는 ROBOT 이나 FLEET 이고 설비 대기는 받지 않는다`() {
        val signal = MissionFixtures.DELIVER_CONTAINER.replace("\"route\": \"FLEET\"", "\"route\": \"SIGNAL\"")
        assertTrue(problems(signal).single().startsWith("$.steps[0].route: ROBOT·FLEET 중 하나여야 한다(SIGNAL)"), "${problems(signal)}")
        val number = MissionFixtures.DELIVER_CONTAINER.replace("\"route\": \"FLEET\"", "\"route\": 2")
        assertTrue(problems(number).single().startsWith("$.steps[0].route: 문자열이어야 한다"), "${problems(number)}")
        val after = MissionFixtures.INSPECT_ASSET.replace("\"after\": \"travel\"", "\"after\": [\"travel\"]")
        assertTrue(problems(after).single().startsWith("$.steps[0].steps[1].after: 문자열이어야 한다"), "${problems(after)}")
    }

    @Test
    fun `requires 와 whenMissing 은 함께 오고 실패 분류와 남길 파라미터가 필수다`() {
        val alone = MissionFixtures.DELIVER_CONTAINER.replace("\"whenMissing\": { \"failureClass\": \"INCOMPLETE_ORDER\", \"parameters\": [] },", "")
        assertTrue(problems(alone).single().startsWith("$.steps[0]: requires 와 whenMissing 은 함께 온다"), "${problems(alone)}")
        val noClass = MissionFixtures.DELIVER_CONTAINER.replace("\"failureClass\": \"INCOMPLETE_ORDER\", ", "")
        assertTrue(problems(noClass).single().startsWith("$.steps[0].whenMissing.failureClass: 빠졌다"), "${problems(noClass)}")
        val blankClass = MissionFixtures.DELIVER_CONTAINER.replace("\"failureClass\": \"INCOMPLETE_ORDER\"", "\"failureClass\": \"\"")
        assertTrue(problems(blankClass).single().startsWith("$.steps[0].whenMissing.failureClass: 비었다"), "${problems(blankClass)}")
        val noParameters = MissionFixtures.DELIVER_CONTAINER.replace(", \"parameters\": [] }", " }")
        assertTrue(problems(noParameters).single().startsWith("$.steps[0].whenMissing.parameters: 빠졌다"), "${problems(noParameters)}")
        val notNames = MissionFixtures.INSPECT_ASSET.replace("\"parameters\": [\"target\"]", "\"parameters\": [7]")
        assertTrue(problems(notNames).single().startsWith("$.steps[0].whenMissing.parameters[0]: 문자열이어야 한다"), "${problems(notNames)}")
        val unit = MissionFixtures.DELIVER_CONTAINER.replace("\"parameters\": [] }", "\"parameters\": [], \"unit\": \"deliver\" }")
        assertTrue(problems(unit).single().startsWith("$.steps[0].whenMissing.unit: 그룹에만 온다"), "${problems(unit)}")
        val empty = MissionFixtures.INSPECT_ASSET.replace("\"requires\": [ { \"from\": \"ITEM_PROPERTY\", \"property\": \"location\" } ]", "\"requires\": []")
        assertTrue(problems(empty).single().startsWith("$.steps[0].requires: 출처가 하나도 없다"), "${problems(empty)}")
    }

    @Test
    fun `새 출처의 인자 칸은 그 출처에 필수이고 다른 출처에는 오지 않는다`() {
        val noName = MissionFixtures.INSPECT_ASSET.replace("\"from\": \"ORDER_PARAMETER\", \"parameter\": \"mode\"", "\"from\": \"ORDER_PARAMETER\"")
        assertTrue(problems(noName).single().startsWith("$.steps[0].steps[1].parameters.mode.parameter: 빠졌다"), "${problems(noName)}")
        val stray = MissionFixtures.INSPECT_ASSET.replace("\"target\": { \"from\": \"ITEM_ID\" }", "\"target\": { \"from\": \"ITEM_ID\", \"parameter\": \"mode\" }")
        assertTrue(problems(stray).single().startsWith("$.steps[0].steps[1].parameters.target.parameter: ORDER_PARAMETER 에만 온다"), "${problems(stray)}")
        val noUse = MissionFixtures.DELIVER_CONTAINER.replace("\"expectedIdentity\": {", "\"expectedIdentity\": { \"from\": \"FIRST_ID\" }, \"x\": {")
        assertTrue(problems(noUse).any { it.startsWith("$.steps[0].expectedIdentity.equipmentUse: 빠졌다") }, "${problems(noUse)}")
    }

    @Test
    fun `반복 노드의 단위 id 는 설비마다 다른 출처로 시작하고 else 는 ITEM_ID 다`() {
        val order = MissionFixtures.MANUFACTURING_INSPECTION.replace("\"unitId\": {\"from\": \"ITEM_ID\"}", "\"unitId\": {\"from\": \"ORDER_ID\"}")
        assertTrue(problems(order).single().startsWith("$.steps[0].unitId.from: 반복 노드의 단위 id 는 ITEM_ID·ITEM_PROPERTY 로 시작한다(ORDER_ID)"), "${problems(order)}")
        val inner = MissionFixtures.INSPECT_ASSET.replace("\"unitId\": { \"from\": \"ITEM_ID\", \"suffix\": \".travel\" }", "\"unitId\": { \"from\": \"ORDER_ID\" }")
        assertTrue(problems(inner).single().startsWith("$.steps[0].steps[0].unitId.from: 반복 노드의 단위 id 는"), "${problems(inner)}")
        val orElse = MissionFixtures.MANUFACTURING_INSPECTION.replace(
            "\"unitId\": {\"from\": \"ITEM_ID\"}",
            "\"unitId\": {\"from\": \"ITEM_PROPERTY\", \"property\": \"location\", \"else\": {\"from\": \"ORDER_ID\"}}",
        )
        assertTrue(problems(orElse).single().startsWith("$.steps[0].unitId.else.from: 반복 노드의 else 는 ITEM_ID 여야 한다(ORDER_ID)"), "${problems(orElse)}")
    }

    @Test
    fun `단위 id 에 else 가 있으면 otherwise 가 오지 않는다`() {
        // otherwise 가 먼저 이겨 else 가 쓰이지 않는다 — 대체값이 단위 id 로 늘 겹친다.
        val text = MissionFixtures.DELIVER_CONTAINER.replace("\"else\": { \"from\": \"ORDER_ID\" }", "\"otherwise\": \"X\", \"else\": { \"from\": \"ORDER_ID\" }")
        assertTrue(problems(text).single().startsWith("$.steps[0].unitId.otherwise: else 와 함께 오지 않는다"), "${problems(text)}")
    }

    @Test
    fun `조건에는 늘 값이 있는 출처와 대체값이 오지 않는다`() {
        val requires = "\"requires\": [ { \"from\": \"ITEM_PROPERTY\", \"property\": \"location\" } ]"
        val always = MissionFixtures.INSPECT_ASSET.replace(requires, "\"requires\": [ { \"from\": \"ITEM_ID\" } ]")
        assertTrue(problems(always).single().startsWith("$.steps[0].requires[0].from: 늘 값이 있는 출처는 조건이 되지 않는다(ITEM_ID)"), "${problems(always)}")
        val fallback = MissionFixtures.INSPECT_ASSET.replace(requires, "\"requires\": [ { \"from\": \"ITEM_PROPERTY\", \"property\": \"location\", \"otherwise\": \"X\" } ]")
        assertTrue(problems(fallback).single().startsWith("$.steps[0].requires[0].otherwise: 조건에는 대체값이 오지 않는다"), "${problems(fallback)}")
        val order = MissionFixtures.DELIVER_CONTAINER.replace("\"requires\": [ ", "\"requires\": [ { \"from\": \"ORDER_ID\" }, ")
        assertTrue(problems(order).single().startsWith("$.steps[0].requires[0].from: 늘 값이 있는 출처는 조건이 되지 않는다(ORDER_ID)"), "${problems(order)}")
        val first = MissionFixtures.INSPECT_ASSET.replace(requires, "\"requires\": [ { \"from\": \"FIRST_ID\", \"equipmentUse\": \"source\" } ]")
        assertTrue(problems(first).single().startsWith("$.steps[0].requires[0].from: FIRST_ID·FIRST_PROPERTY 는 once 노드에만 온다"), "${problems(first)}")
    }

    @Test
    fun `그룹 안 단위 노드는 로봇 경로뿐이고 짝과 조건을 갖지 않는다`() {
        val fleet = MissionFixtures.INSPECT_ASSET.replace("\"id\": \"travel\",", "\"id\": \"travel\", \"route\": \"FLEET\",")
        assertTrue(problems(fleet).single().startsWith("$.steps[0].steps[0].route: 그룹 안 단위 노드는 ROBOT 경로뿐이다"), "${problems(fleet)}")
        val owned = MissionFixtures.INSPECT_ASSET.replace(
            "\"id\": \"travel\",",
            "\"id\": \"travel\", \"pairWith\": { \"equipmentUse\": \"source\", \"property\": \"material\" }, \"whenUnpaired\": \"X\", " +
                "\"requires\": [ { \"from\": \"ITEM_PROPERTY\", \"property\": \"location\" } ], \"whenMissing\": { \"failureClass\": \"X\", \"parameters\": [] },",
        )
        val found = problems(owned)
        assertEquals(
            listOf("pairWith", "whenUnpaired", "requires", "whenMissing").map { "$.steps[0].steps[0].$it" },
            found.map { it.substringBefore(":") },
            "$found",
        )
    }

    @Test
    fun `once 노드에는 짝이 없다`() {
        val text = MissionFixtures.DELIVER_CONTAINER.replace(
            "\"once\": true,",
            "\"once\": true, \"pairWith\": { \"equipmentUse\": \"source\", \"property\": \"container\" }, \"whenUnpaired\": \"X\",",
        )
        assertTrue(problems(text).single().startsWith("$.steps[0].pairWith: once 노드에는 짝이 없다"), "${problems(text)}")
    }
}

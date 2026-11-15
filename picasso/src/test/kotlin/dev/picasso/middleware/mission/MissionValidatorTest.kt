package dev.picasso.middleware.mission

import dev.picasso.middleware.FloorOwner
import dev.picasso.middleware.FloorOwnership
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 검증기 — 다섯 검사(기한·신호·자원·스킬·안전)와 노드 id 중복이 **각각** 잘못된 정의를 거부하고, 거부가 종류와
 * 후속 행동으로 보인다.
 *
 * 검사마다 그 검사만 걸리는 정의를 하나씩 든다. 한 정의가 둘에 걸리면 한 검사를 빼도 다른 검사가 거부해 그 결함이
 * 안 보인다 — 그래서 «그 종류 하나뿐» 을 댄다.
 */
class MissionValidatorTest {

    private val at: Instant = Instant.parse("2026-10-08T09:00:00Z")

    private fun floors(vararg unowned: String) = object : FloorOwnership {
        override fun ownerOf(location: String): FloorOwner =
            if (location in unowned) FloorOwner.Unowned else FloorOwner.Declared("line-a")
    }

    private fun refusals(
        text: String,
        signals: List<SignalSpec> = MissionFixtures.SIGNALS,
        floors: FloorOwnership = FloorOwnership.None,
        siteSkills: Set<String> = MissionFixtures.SITE_SKILLS,
    ): List<MissionRefusal> = MissionValidator.validate(MissionFixtures.parsed(text), signals, floors, siteSkills, at)

    private fun only(kind: MissionRefusalKind, found: List<MissionRefusal>): MissionRefusal {
        assertEquals(listOf(kind), found.map { it.kind }, "그 종류 하나만 걸려야 한다: $found")
        return found.single()
    }

    @Test
    fun `통과하는 정의는 거부가 없다`() {
        assertEquals(emptyList(), refusals(MissionFixtures.PREPARE_SEQUENCED_RACK))
        assertEquals(emptyList(), refusals(MissionFixtures.withArrivalWait()))
        // 텍스트 신호는 어떤 값이든 기다릴 수 있다.
        assertEquals(emptyList(), refusals(MissionFixtures.withArrivalWait(signal = "lot_code", expect = "LOT-7")))
        // §15.325 의 칸으로 옮긴 두 정의와 mission-ops 예제 모양. 운반의 transport 는 로봇 스킬이 아니라 현장 스킬 목록에 없어도 된다.
        assertEquals(emptyList(), refusals(MissionFixtures.INSPECT_ASSET))
        assertEquals(emptyList(), refusals(MissionFixtures.DELIVER_CONTAINER))
        assertEquals(emptyList(), refusals(MissionFixtures.MANUFACTURING_INSPECTION))
    }

    @Test
    fun `기한 검사 — 양의 기한이 없으면 거부한다`() {
        val zero = only(MissionRefusalKind.DEADLINE_INVALID, refusals(MissionFixtures.withArrivalWait(deadlineSeconds = 0)))
        assertEquals(MissionFixtures.WAIT_NODE, zero.nodeId)
        assertEquals("deadlineSeconds=0", zero.observed)

        val missing = MissionFixtures.withArrivalWait().replace(", \"deadlineSeconds\": 120", "")
        assertEquals("deadlineSeconds=없음", only(MissionRefusalKind.DEADLINE_INVALID, refusals(missing)).observed)
    }

    @Test
    fun `기한 검사 — 기한 뒤 상태가 허용 밖이거나 없으면 거부한다`() {
        val retry = MissionFixtures.withArrivalWait().replace("\"onDeadline\": \"OPERATOR_HOLD\"", "\"onDeadline\": \"RETRY\"")
        val refusal = only(MissionRefusalKind.DEADLINE_INVALID, refusals(retry))
        assertEquals("onDeadline=RETRY", refusal.observed)
        assertEquals("OPERATOR_HOLD 또는 ABORTED", refusal.expected)

        val missing = MissionFixtures.withArrivalWait().replace(", \"onDeadline\": \"OPERATOR_HOLD\"", "")
        assertEquals("onDeadline=없음", only(MissionRefusalKind.DEADLINE_INVALID, refusals(missing)).observed)
    }

    @Test
    fun `신호 검사 — 신호 사양에 없는 신호를 참조하면 거부한다`() {
        val refusal = only(MissionRefusalKind.SIGNAL_NOT_IN_SPEC, refusals(MissionFixtures.withArrivalWait(signal = "rack_ready")))
        assertEquals(MissionFixtures.WAIT_NODE, refusal.nodeId)
        assertEquals("rack_ready", refusal.observed)
        assertTrue("rack_present" in refusal.expected, refusal.expected)
    }

    @Test
    fun `신호 검사 — 참거짓 신호에 그 밖의 값을 기다리면 거부한다`() {
        // 영영 안 끝나는 대기다 — 기한까지 서 있다가 운영자에게 간다.
        val refusal = only(MissionRefusalKind.SIGNAL_VALUE_INVALID, refusals(MissionFixtures.withArrivalWait(expect = "1")))
        assertEquals("1", refusal.observed)
    }

    @Test
    fun `자원 검사 — 대기 신호의 자리에 바닥 소유자가 없으면 거부하고 선언 없음은 통과한다`() {
        val refusal = only(
            MissionRefusalKind.FLOOR_UNOWNED,
            refusals(MissionFixtures.withArrivalWait(), floors = floors(MissionFixtures.DOCK)),
        )
        assertEquals(MissionFixtures.WAIT_NODE, refusal.nodeId)
        assertEquals(RefusalOwner.OUTSIDE_CONSOLE, refusal.owner)

        // ★관문과 같은 규칙 — 레지스터를 안 붙인 배치(NotDeclared)와 소유자가 있는 자리(Declared)는 통과한다.
        assertEquals(emptyList(), refusals(MissionFixtures.withArrivalWait(), floors = FloorOwnership.None))
        assertEquals(emptyList(), refusals(MissionFixtures.withArrivalWait(), floors = floors("OTHER")))
        // 자리가 없는 신호는 건너뛴다.
        val nowhere = MissionFixtures.SIGNALS.map { if (it.name == MissionFixtures.RACK_PRESENT) it.copy(location = null) else it }
        assertEquals(emptyList(), refusals(MissionFixtures.withArrivalWait(), signals = nowhere, floors = floors(MissionFixtures.DOCK)))
    }

    @Test
    fun `스킬 검사 — 계약에 없는 스킬을 거부한다`() {
        val text = MissionFixtures.PREPARE_SEQUENCED_RACK.replace("\"skill\": \"pick_place\"", "\"skill\": \"pick_and_place\"")
        val refusal = only(MissionRefusalKind.SKILL_NOT_IN_CONTRACT, refusals(text))
        assertEquals("place", refusal.nodeId)
        assertEquals("pick_and_place", refusal.observed)
    }

    @Test
    fun `스킬 검사 — 현장 기체가 제공하지 않는 스킬을 거부한다`() {
        val refusal = only(
            MissionRefusalKind.SKILL_NOT_ON_SITE,
            refusals(MissionFixtures.PREPARE_SEQUENCED_RACK, siteSkills = setOf("navigate_to")),
        )
        assertEquals("pick_place", refusal.observed)
        assertEquals("그 스킬을 제공하는 기체를 현장에 둔다", refusal.nextAction)
    }

    @Test
    fun `안전 검사 — 안전 신호를 기다리는 대기를 거부한다`() {
        val refusal = only(MissionRefusalKind.SAFETY_SIGNAL_WAIT, refusals(MissionFixtures.withArrivalWait(signal = "guard_closed")))
        assertEquals(MissionFixtures.WAIT_NODE, refusal.nodeId)
    }

    @Test
    fun `출발 관측 검사 — 신원을 대는 출발 자리가 있는데 선언이 없으면 거부한다`() {
        // 설비가 말이 없을 때 보낼지가 암묵으로 남는다 — 기본을 정하지 않고 묻는다.
        val refusal = only(
            MissionRefusalKind.SOURCE_OBSERVATION_UNDECLARED,
            refusals(MissionFixtures.prepareSequencedRack(sourceObservation = null)),
        )
        assertEquals("place", refusal.nodeId)
        assertEquals("sourceObservation=없음", refusal.observed)
        assertEquals("REQUIRED 또는 OPTIONAL", refusal.expected)
        assertEquals(RefusalOwner.ENGINEER, refusal.owner)
    }

    @Test
    fun `출발 관측 검사 — REQUIRED 와 OPTIONAL 은 통과한다`() {
        assertEquals(emptyList(), refusals(MissionFixtures.prepareSequencedRack(sourceObservation = "REQUIRED")))
        assertEquals(emptyList(), refusals(MissionFixtures.prepareSequencedRack(sourceObservation = "OPTIONAL")))
    }

    @Test
    fun `출발 관측 검사 — 출발 자리나 기대 신원이 없는 노드는 선언 없이 통과한다`() {
        // 관문이 출발 자리의 신원을 대조하지 않는 노드에 선언을 요구하면 상관없는 정의가 활성화되지 않는다.
        val undeclared = MissionFixtures.prepareSequencedRack(sourceObservation = null)
        val noSource = undeclared.replace("\"source\": { \"from\": \"PAIRED_ID\" },\n", "")
        // 기대 식별 정보를 빼면 목적지 관측 선언도 쓰이지 않는 선언이 되므로 그 선언까지 뺀 문서로 만든다(§15.235).
        val noIdentity = MissionFixtures.prepareSequencedRack(sourceObservation = null, destinationObservation = null).replace("\"expectedIdentity\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"material\" },\n", "")
        assertTrue(noSource != undeclared && noIdentity != undeclared, "시험 문서가 안 바뀌었다")
        assertEquals(emptyList(), refusals(noSource))
        assertEquals(emptyList(), refusals(noIdentity))
    }

    @Test
    fun `출발 관측 검사 — 출발 자리나 기대 신원이 없는 노드의 선언은 거부한다`() {
        // ★출발 자리가 없으면 관문이 선언을 안 읽고, 기대 신원이 없으면 REQUIRED 가 신원 대조 없이 침묵만 막는다.
        //   어느 쪽이든 적은 뜻과 도는 뜻이 달라 조용히 받으면 안 된다.
        val required = MissionFixtures.prepareSequencedRack(sourceObservation = "REQUIRED")
        val noSource = required.replace("\"source\": { \"from\": \"PAIRED_ID\" },\n", "")
        val optional = MissionFixtures.prepareSequencedRack(sourceObservation = "OPTIONAL", destinationObservation = null)
        val noIdentity = optional.replace("\"expectedIdentity\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"material\" },\n", "")
        assertTrue(noSource != required && noIdentity != optional, "시험 문서가 안 바뀌었다")

        val unread = only(MissionRefusalKind.SOURCE_OBSERVATION_UNUSED, refusals(noSource))
        assertEquals("place", unread.nodeId)
        assertEquals("sourceObservation=REQUIRED", unread.observed)
        assertEquals(RefusalOwner.ENGINEER, unread.owner)
        assertEquals("sourceObservation=OPTIONAL", only(MissionRefusalKind.SOURCE_OBSERVATION_UNUSED, refusals(noIdentity)).observed)
    }

    @Test
    fun `목적지 관측 검사 — 식별 정보를 대는 목적지가 있는데 선언이 없으면 거부한다`() {
        // 작업 전 목적지 «있다» 를 점유로 볼지 옛 신호로 볼지가 암묵으로 남는다 — 기본을 정하지 않고 묻는다.
        val refusal = only(
            MissionRefusalKind.DESTINATION_OBSERVATION_UNDECLARED,
            refusals(MissionFixtures.prepareSequencedRack(sourceObservation = "OPTIONAL", destinationObservation = null)),
        )
        assertEquals("place", refusal.nodeId)
        assertEquals("destinationObservation=없음", refusal.observed)
        assertEquals("REQUIRED 또는 OPTIONAL", refusal.expected)
        assertEquals(RefusalOwner.ENGINEER, refusal.owner)
    }

    @Test
    fun `목적지 관측 검사 — REQUIRED 와 OPTIONAL 은 통과한다`() {
        assertEquals(emptyList(), refusals(MissionFixtures.prepareSequencedRack("OPTIONAL", destinationObservation = "REQUIRED")))
        assertEquals(emptyList(), refusals(MissionFixtures.prepareSequencedRack("OPTIONAL", destinationObservation = "OPTIONAL")))
    }

    @Test
    fun `목적지 관측 검사 — 목적지나 기대 식별 정보가 없는 노드는 선언 없이 통과하고 선언하면 거부한다`() {
        // 목적지가 없으면 게이트가 볼 자리가 없고, 기대 식별 정보가 없으면 그 목적지는 갈 자리라 게이트가 선언을 읽지 않는다.
        // 출발 관측 선언은 빼 둔다 — 기대 식별 정보를 빼면 출발 관측 검사도 걸려 «그 종류 하나뿐» 이 흐려진다.
        fun doc(declaration: String?) = MissionFixtures.prepareSequencedRack(sourceObservation = null, destinationObservation = declaration)
        val destination = ",\n\"destination\": { \"from\": \"ITEM_ID\" }"
        val identity = "\"expectedIdentity\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"material\" },\n"

        val undeclared = doc(null)
        val noDestination = undeclared.replace(destination, "")
        val noIdentity = undeclared.replace(identity, "")
        assertTrue(noDestination != undeclared && noIdentity != undeclared, "시험 문서가 안 바뀌었다")
        // 목적지만 뺀 문서는 출발 관측 선언이 없어 그 검사에 걸린다 — 목적지 관측 종류가 없는 것만 본다.
        assertTrue(refusals(noDestination).none { it.kind.name.startsWith("DESTINATION_") }, "${refusals(noDestination)}")
        assertEquals(emptyList(), refusals(noIdentity))

        val required = doc("REQUIRED")
        val unread = refusals(required.replace(destination, "")).single { it.kind == MissionRefusalKind.DESTINATION_OBSERVATION_UNUSED }
        assertEquals("place", unread.nodeId)
        assertEquals("destinationObservation=REQUIRED", unread.observed)
        assertEquals(RefusalOwner.ENGINEER, unread.owner)
        val walk = only(MissionRefusalKind.DESTINATION_OBSERVATION_UNUSED, refusals(doc("OPTIONAL").replace(identity, "")))
        assertEquals("destinationObservation=OPTIONAL", walk.observed)
    }

    @Test
    fun `노드 id 가 겹치면 거부한다`() {
        val text = MissionFixtures.withArrivalWait().replace("\"id\": \"place\"", "\"id\": \"${MissionFixtures.WAIT_NODE}\"")
        val refusal = only(MissionRefusalKind.DUPLICATE_NODE_ID, refusals(text))
        assertEquals(MissionFixtures.WAIT_NODE, refusal.nodeId)
        assertEquals("2 번 나온다", refusal.observed)
    }

    // ── §15.325 의 칸에만 걸리는 검사

    @Test
    fun `노드 id 는 그룹과 그 안쪽 노드가 한 이름 공간이다`() {
        val text = MissionFixtures.INSPECT_ASSET.replace("\"id\": \"visit\"", "\"id\": \"travel\"")
        val refusal = only(MissionRefusalKind.DUPLICATE_NODE_ID, refusals(text))
        assertEquals("travel", refusal.nodeId)
    }

    @Test
    fun `스킬과 관측 선언 검사는 그룹 안쪽 노드에도 걸린다`() {
        val skill = MissionFixtures.INSPECT_ASSET.replace("\"skill\": \"navigate_to\"", "\"skill\": \"fly_to\"")
        assertEquals("travel", only(MissionRefusalKind.SKILL_NOT_IN_CONTRACT, refusals(skill)).nodeId)
        val identity = MissionFixtures.INSPECT_ASSET.replace("\"skill\": \"navigate_to\",", "\"skill\": \"navigate_to\", \"expectedIdentity\": { \"from\": \"ITEM_ID\" },")
        assertEquals("travel", only(MissionRefusalKind.DESTINATION_OBSERVATION_UNDECLARED, refusals(identity)).nodeId)
    }

    @Test
    fun `선행 검사 — after 는 같은 그룹의 앞 단위 노드만 가리킨다`() {
        fun after(to: String) = MissionFixtures.INSPECT_ASSET.replace("\"after\": \"travel\"", "\"after\": \"$to\"")
        val later = only(MissionRefusalKind.AFTER_INVALID, refusals(after("inspect")))
        assertEquals("inspect", later.nodeId)
        assertEquals("after=inspect", later.observed)
        assertEquals("같은 그룹의 앞 단위 노드 id(travel)", later.expected)
        assertEquals("after=visit", only(MissionRefusalKind.AFTER_INVALID, refusals(after("visit"))).observed)
        assertEquals("after=nowhere", only(MissionRefusalKind.AFTER_INVALID, refusals(after("nowhere"))).observed)
        // 앞 형제가 아니라 뒤 형제에서 앞을 가리키는 것 — 첫 노드에는 앞이 없다.
        val first = MissionFixtures.INSPECT_ASSET.replace("\"id\": \"travel\",", "\"id\": \"travel\", \"after\": \"inspect\",")
        assertEquals("travel", only(MissionRefusalKind.AFTER_INVALID, refusals(first)).nodeId)
        // 최상위 노드와 대기 노드에는 같은 항목의 앞 노드가 없다.
        val top = MissionFixtures.withArrivalWait().replace("\"id\": \"place\",", "\"id\": \"place\", \"after\": \"${MissionFixtures.WAIT_NODE}\",")
        val refusal = only(MissionRefusalKind.AFTER_INVALID, refusals(top))
        assertEquals("place" to "같은 그룹의 앞 단위 노드 id(없음)", refusal.nodeId to refusal.expected)
    }

    @Test
    fun `단위 id 모양 검사 — 같은 그룹의 두 노드가 같은 모양의 단위 id 를 내면 거부한다`() {
        val text = MissionFixtures.INSPECT_ASSET.replace(", \"suffix\": \".travel\"", "")
        val refusal = only(MissionRefusalKind.UNIT_ID_SHAPE_COLLISION, refusals(text))
        assertEquals("inspect", refusal.nodeId)
        assertEquals("travel, inspect: ITEM_ID", refusal.observed)
        // 대체값만 다른 것도 같은 모양이다 — ITEM_ID 는 언제나 값을 내므로 대체가 쓰이지 않는다.
        val fallback = text.replace("\"unitId\": { \"from\": \"ITEM_ID\" },\n\"parameters\": { \"target\"", "\"unitId\": { \"from\": \"ITEM_ID\", \"otherwise\": \"x\" },\n\"parameters\": { \"target\"")
        assertTrue(fallback != text, "시험 문서가 안 바뀌었다")
        only(MissionRefusalKind.UNIT_ID_SHAPE_COLLISION, refusals(fallback))
    }

    @Test
    fun `플릿 경로 검사 — 운반의 모양이 아니면 거부하고 로봇 스킬 검사에 걸지 않는다`() {
        val skill = only(MissionRefusalKind.FLEET_UNIT_INVALID, refusals(MissionFixtures.DELIVER_CONTAINER.replace("\"skill\": \"transport\"", "\"skill\": \"pick_place\"")))
        assertEquals("deliver" to "skill=pick_place", skill.nodeId to skill.observed)
        val missing = MissionFixtures.DELIVER_CONTAINER.replace(",\n\"destination\": { \"from\": \"FIRST_ID\", \"equipmentUse\": \"destination\" }\n}", "\n}")
        assertTrue(missing != MissionFixtures.DELIVER_CONTAINER, "시험 문서가 안 바뀌었다")
        assertEquals("destination=없음", only(MissionRefusalKind.FLEET_UNIT_INVALID, refusals(missing)).observed)
        // 로봇 경로였다면 운반 이름은 계약에 없는 스킬이다 — 플릿 노드라서 그 검사에 안 걸린다.
        val robot = MissionFixtures.DELIVER_CONTAINER.replace("\"route\": \"FLEET\",", "")
        assertTrue(refusals(robot).any { it.kind == MissionRefusalKind.SKILL_NOT_IN_CONTRACT }, "${refusals(robot)}")
    }

    @Test
    fun `플릿 경로 검사 — 관측 선언을 거부하고 출발·목적지 관측 검사에 걸지 않는다`() {
        // 배정 관문은 로봇 단위의 출발·목적지만 읽는다 — 플릿 노드의 선언은 쓰이지 않는 선언이다(§15.227 의 기준).
        val declared = MissionFixtures.DELIVER_CONTAINER.replace(
            "\"skill\": \"transport\",",
            "\"skill\": \"transport\", \"sourceObservation\": \"REQUIRED\", \"destinationObservation\": \"OPTIONAL\",",
        )
        val refusal = only(MissionRefusalKind.FLEET_UNIT_INVALID, refusals(declared))
        assertEquals("sourceObservation=REQUIRED, destinationObservation=OPTIONAL", refusal.observed)
        // 선언이 없는 플릿 노드는 출발·목적지·기대 식별 정보를 다 대도 선언을 요구받지 않는다.
        val unit = assertIs<UnitStep>(MissionFixtures.parsed(MissionFixtures.DELIVER_CONTAINER).steps.single())
        assertFalse(MissionValidator.needsSourceObservation(unit) || MissionValidator.needsDestinationObservation(unit))
    }

    @Test
    fun `조건부 포함 검사 — 남길 노드와 남길 파라미터를 그 그룹에서 찾는다`() {
        val noUnit = MissionFixtures.INSPECT_ASSET.replace("\"unit\": \"inspect\", ", "")
        val refusal = only(MissionRefusalKind.WHEN_MISSING_INVALID, refusals(noUnit))
        assertEquals("visit" to "unit=없음", refusal.nodeId to refusal.observed)
        assertEquals("남길 안쪽 노드 id(travel, inspect)", refusal.expected)
        val unknown = MissionFixtures.INSPECT_ASSET.replace("\"unit\": \"inspect\"", "\"unit\": \"nowhere\"")
        assertEquals("unit=nowhere", only(MissionRefusalKind.WHEN_MISSING_INVALID, refusals(unknown)).observed)
        val parameter = MissionFixtures.INSPECT_ASSET.replace("\"parameters\": [\"target\"]", "\"parameters\": [\"target\", \"location\"]")
        val stray = only(MissionRefusalKind.WHEN_MISSING_INVALID, refusals(parameter))
        assertEquals("parameters=location" to "inspect 의 파라미터 이름(target, mode)", stray.observed to stray.expected)
        // 노드 하나짜리 그룹은 unit 이 없어도 그 노드가 남는다.
        val single = MissionFixtures.MANUFACTURING_INSPECTION.replace(
            "{\"kind\": \"unit\", \"id\": \"inspect\", \"skill\": \"inspect\", \"forEach\": \"mission_target\", ",
            "{\"kind\": \"each\", \"id\": \"visit\", \"forEach\": \"mission_target\", \"requires\": [{\"from\": \"ITEM_PROPERTY\", \"property\": \"location\"}], " +
                "\"whenMissing\": {\"failureClass\": \"NO_LOCATION\", \"parameters\": []}, \"steps\": [{\"kind\": \"unit\", \"id\": \"inspect\", \"skill\": \"inspect\", ",
        ).replace("\"property\": \"location\"}}\n]", "\"property\": \"location\"}}]}\n]")
        assertEquals(emptyList(), refusals(single))
    }

    @Test
    fun `거부를 전부 모으고 각 거부가 여섯 칸과 노드를 든다`() {
        val text = MissionFixtures.withArrivalWait(deadlineSeconds = -1, signal = "rack_ready")
            .replace("\"skill\": \"pick_place\"", "\"skill\": \"teleport\"")
        val found = refusals(text)
        assertEquals(
            setOf(MissionRefusalKind.DEADLINE_INVALID, MissionRefusalKind.SIGNAL_NOT_IN_SPEC, MissionRefusalKind.SKILL_NOT_IN_CONTRACT),
            found.map { it.kind }.toSet(),
        )
        found.forEach {
            assertEquals(at, it.checkedAt, "마지막 확인 시각은 활성화를 시도한 시각이다")
            assertNull(it.basisVersion, "근거 버전은 해당 없음이다")
            assertTrue(it.nextAction.isNotBlank() && it.expected.isNotBlank() && it.observed.isNotBlank())
            assertTrue(it.nodeId != null)
        }
    }

    @Test
    fun `종류마다 후속 행동이 다르다`() {
        // 종류를 가르는 기준이 후속 행동이다 — 같은 행동을 가진 두 종류는 하나로 접혀야 한다.
        val actions = MissionRefusalKind.entries.map { it.nextAction }
        assertEquals(actions.size, actions.toSet().size, "후속 행동이 같은 종류가 있다: $actions")
    }

    @Test
    fun `단위 id 모양 검사 — else 의 끝값과 최상위 once 노드끼리도 본다`() {
        // 위치가 없는 대상에서 이동의 단위 id 가 점검의 단위 id(ITEM_ID)와 같아진다.
        val fallback = MissionFixtures.INSPECT_ASSET.replace(
            "\"unitId\": { \"from\": \"ITEM_ID\", \"suffix\": \".travel\" }",
            "\"unitId\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"location\", \"else\": { \"from\": \"ITEM_ID\" } }",
        )
        val refusal = only(MissionRefusalKind.UNIT_ID_SHAPE_COLLISION, refusals(fallback))
        assertEquals("inspect" to "travel, inspect: ITEM_ID", refusal.nodeId to refusal.observed)

        // 작업 지시마다 한 번인 노드 둘이 같은 모양이면 작업 지시마다 겹친다. 속성 이름까지 보인다.
        val node = MissionFixtures.DELIVER_CONTAINER.substringAfter("\"steps\": [\n").substringBeforeLast("\n]")
        val twice = MissionFixtures.DELIVER_CONTAINER.replace(node, node + ",\n" + node.replace("\"id\": \"deliver\"", "\"id\": \"deliver2\""))
        val once = only(MissionRefusalKind.UNIT_ID_SHAPE_COLLISION, refusals(twice))
        assertEquals("deliver2" to "deliver, deliver2: FIRST_PROPERTY(source.container)", once.nodeId to once.observed)
    }
}

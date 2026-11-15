package dev.picasso.middleware.mission

import dev.picasso.middleware.DeadlineOutcome

/**
 * 시험이 쓰는 임무 정의 문서와 현장 데이터.
 *
 * [PREPARE_SEQUENCED_RACK] 는 코드 `PrepareSequencedRack` 을 **데이터로 옮긴 것**이다 — 동등성 시험이 그 둘이 같은
 * 계획을 내는지 칸마다 댄다. [withArrivalWait] 는 그 앞에 랙 도착 대기를 둔 버전이다(버전 2 의 모양).
 *
 * 단위 노드는 출발 관측과 목적지 관측을 둘 다 `OPTIONAL` 로 선언한다 — 코드 케이퍼빌리티의 기본값과 같아야 동등성이 선다.
 * [prepareSequencedRack] 이 그 선언만 바꾼 문서를 낸다.
 *
 * [INSPECT_ASSET]·[DELIVER_CONTAINER] 는 코드 `InspectAsset`·`DeliverContainer` 를 §15.325 의 칸(반복 그룹·접미사·작업 지시 값·
 * 조건부 포함·플릿 경로·선행·once)으로 옮긴 것이다. [MANUFACTURING_INSPECTION] 은 mission-ops 예제와 같은 모양의 버전 1 문서다 —
 * 새 칸을 안 쓰는 문서가 그대로 읽히고 통과하는지 댄다.
 */
object MissionFixtures {

    const val RACK_PRESENT = "rack_present"

    private const val FIRST_SOURCE = "{ \"from\": \"FIRST_ID\", \"equipmentUse\": \"source\" }"
    private const val FIRST_DESTINATION = "{ \"from\": \"FIRST_ID\", \"equipmentUse\": \"destination\" }"
    private const val CONTAINER = "{ \"from\": \"FIRST_PROPERTY\", \"equipmentUse\": \"source\", \"property\": \"container\" }"
    const val WAIT_NODE = "rack-arrival"

    /** 랙 도착 신호를 내는 자리. 자원 검사가 이 자리의 바닥 소유를 본다. */
    const val DOCK = "RACK-DOCK"

    /**
     * 단위 노드 하나 — 슬롯마다 `pick_place`, 제시 자리는 material 로 짝짓는다.
     *
     * 시험이 문자열을 바꿔 틀린 문서를 만들므로 **칸마다 한 줄**이고 들여쓰기가 없다.
     */
    private fun placeNode(sourceObservation: String?, destinationObservation: String? = "OPTIONAL") = listOfNotNull(
        "{",
        "\"kind\": \"unit\",",
        "\"id\": \"place\",",
        "\"skill\": \"pick_place\",",
        "\"forEach\": \"destination\",",
        "\"pairWith\": { \"equipmentUse\": \"source\", \"property\": \"material\" },",
        "\"whenUnpaired\": \"NO_SOURCE_FOR_MATERIAL\",",
        "\"unitId\": { \"from\": \"ITEM_ID\" },",
        "\"parameters\": { \"object_id\": { \"from\": \"PAIRED_ID\", \"otherwise\": \"\" }, \"destination\": { \"from\": \"ITEM_ID\" } },",
        "\"expectedIdentity\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"material\" },",
        "\"source\": { \"from\": \"PAIRED_ID\" },",
        sourceObservation?.let { "\"sourceObservation\": \"$it\"," },
        destinationObservation?.let { "\"destinationObservation\": \"$it\"," },
        "\"destination\": { \"from\": \"ITEM_ID\" }",
        "}",
    ).joinToString("\n")

    private val PLACE_NODE = placeNode("OPTIONAL")

    private fun document(vararg steps: String) =
        definition("PrepareSequencedRack", "E2", "{ \"verify_grasp\": \"true\" }", *steps)

    private fun definition(workMaster: String, maxEvidence: String, optionals: String, vararg steps: String) = listOf(
        "{",
        "\"schemaVersion\": 1,",
        "\"workMasterId\": \"$workMaster\",",
        "\"maxEvidence\": \"$maxEvidence\",",
        "\"preferredOptionals\": $optionals,",
        "\"steps\": [",
        steps.joinToString(",\n"),
        "]",
        "}",
    ).joinToString("\n")

    /** 코드 `PrepareSequencedRack` 의 데이터판 — 버전 1 의 모양. */
    val PREPARE_SEQUENCED_RACK: String = document(PLACE_NODE)

    /** 버전 1 의 모양에서 출발 관측·목적지 관측 선언만 바꾼 문서. 널이면 그 선언을 뺀다. */
    fun prepareSequencedRack(sourceObservation: String?, destinationObservation: String? = "OPTIONAL"): String =
        document(placeNode(sourceObservation, destinationObservation))

    /** 맨 앞에 «랙 도착 신호가 기대 값이 될 때까지 대기» 를 둔 버전 — 버전 2 의 모양. 대기 노드는 한 줄이다. */
    fun withArrivalWait(
        deadlineSeconds: Long = 120,
        onDeadline: DeadlineOutcome = DeadlineOutcome.OPERATOR_HOLD,
        signal: String = RACK_PRESENT,
        expect: String = "true",
    ): String = document(
        "{\"kind\": \"wait\", \"id\": \"$WAIT_NODE\", \"signal\": \"$signal\", \"expect\": \"$expect\", " +
            "\"deadlineSeconds\": $deadlineSeconds, \"onDeadline\": \"${onDeadline.name}\"}",
        PLACE_NODE,
    )

    /**
     * 코드 `InspectAsset` 의 데이터판 — 대상마다 이동(`<대상>.travel`) 뒤 점검, 점검은 같은 대상의 이동을 선행으로 든다. 살필 자리가
     * 없으면 점검 단위 하나가 `FAILED` 로 남고 파라미터는 target 뿐이다. 칸마다 한 줄이다.
     */
    val INSPECT_ASSET: String = definition(
        "InspectAsset", "E0", "{}",
        listOf(
            "{",
            "\"kind\": \"each\",",
            "\"id\": \"visit\",",
            "\"forEach\": \"inspection_target\",",
            "\"requires\": [ { \"from\": \"ITEM_PROPERTY\", \"property\": \"location\" } ],",
            "\"whenMissing\": { \"failureClass\": \"NO_LOCATION_FOR_TARGET\", \"unit\": \"inspect\", \"parameters\": [\"target\"] },",
            "\"steps\": [",
            "{",
            "\"kind\": \"unit\",",
            "\"id\": \"travel\",",
            "\"skill\": \"navigate_to\",",
            "\"unitId\": { \"from\": \"ITEM_ID\", \"suffix\": \".travel\" },",
            "\"parameters\": { \"location\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"location\" } },",
            "\"destination\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"location\" }",
            "},",
            "{",
            "\"kind\": \"unit\",",
            "\"id\": \"inspect\",",
            "\"skill\": \"inspect\",",
            "\"after\": \"travel\",",
            "\"unitId\": { \"from\": \"ITEM_ID\" },",
            "\"parameters\": { \"target\": { \"from\": \"ITEM_ID\" }, \"mode\": { \"from\": \"ORDER_PARAMETER\", \"parameter\": \"mode\" } },",
            "\"destination\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"location\" }",
            "}",
            "]",
            "}",
        ).joinToString("\n"),
    )

    /**
     * 코드 `DeliverContainer` 의 데이터판 — 작업 지시마다 플릿 운반 하나. 첫 source·첫 destination 과 첫 source 의 container 가
     * 다 있어야 정상이고, 하나라도 없으면 그 단위 하나가 `FAILED`(`INCOMPLETE_ORDER`)이며 파라미터가 없다. 단위 id 는 container,
     * 없으면 작업 지시 id 다. 칸마다 한 줄이다.
     */
    val DELIVER_CONTAINER: String = definition(
        "DeliverContainer", "E2", "{}",
        listOf(
            "{",
            "\"kind\": \"unit\",",
            "\"id\": \"deliver\",",
            "\"once\": true,",
            "\"route\": \"FLEET\",",
            "\"skill\": \"transport\",",
            "\"requires\": [ $FIRST_SOURCE, $FIRST_DESTINATION, $CONTAINER ],",
            "\"whenMissing\": { \"failureClass\": \"INCOMPLETE_ORDER\", \"parameters\": [] },",
            "\"unitId\": { \"from\": \"FIRST_PROPERTY\", \"equipmentUse\": \"source\", \"property\": \"container\", \"else\": { \"from\": \"ORDER_ID\" } },",
            "\"parameters\": { \"container\": $CONTAINER, \"source\": $FIRST_SOURCE, \"destination\": $FIRST_DESTINATION },",
            "\"expectedIdentity\": $CONTAINER,",
            "\"source\": $FIRST_SOURCE,",
            "\"destination\": $FIRST_DESTINATION",
            "}",
        ).joinToString("\n"),
    )

    /** mission-ops 예제 `ManufacturingInspection` 과 같은 모양 — 버전 1 의 단위 노드 하나(대상마다 점검). */
    val MANUFACTURING_INSPECTION: String = definition(
        "ManufacturingInspection", "E0", "{}",
        "{\"kind\": \"unit\", \"id\": \"inspect\", \"skill\": \"inspect\", \"forEach\": \"mission_target\", " +
            "\"unitId\": {\"from\": \"ITEM_ID\"}, \"parameters\": {\"target\": {\"from\": \"ITEM_ID\"}}, " +
            "\"destination\": {\"from\": \"ITEM_PROPERTY\", \"property\": \"location\"}}",
    )

    // ── 접수 시점 분기(§15.328)

    /**
     * 그룹 안 분기 — 대상마다 이동 뒤, 대상의 `item` 속성으로 점검을 고른다. `thermal` 이면 열 점검(`<대상>`), `visual`·`thermal` 이면
     * 눈 점검(`<대상>.look`) — `thermal` 은 두 갈래에 다 맞아 **앞 갈래**가 이긴다. 다른 값이면 점검 없음, `item` 이 없으면 열 점검
     * 단위 하나가 `FAILED`(`NO_ITEM_FOR_TARGET`, 파라미터 target 뿐)다. 두 점검은 같은 대상의 이동을 선행으로 든다. 칸마다 한 줄이다.
     */
    val INSPECT_BY_ITEM: String = definition(
        "InspectByItem", "E0", "{}",
        listOf(
            "{",
            "\"kind\": \"each\",",
            "\"id\": \"visit\",",
            "\"forEach\": \"inspection_target\",",
            "\"steps\": [",
            "{",
            "\"kind\": \"unit\",",
            "\"id\": \"travel\",",
            "\"skill\": \"navigate_to\",",
            "\"unitId\": { \"from\": \"ITEM_ID\", \"suffix\": \".travel\" },",
            "\"parameters\": { \"location\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"location\" } },",
            "\"destination\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"location\" }",
            "},",
            "{",
            "\"kind\": \"choice\",",
            "\"id\": \"how\",",
            "\"on\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"item\" },",
            "\"arms\": [",
            "{ \"equals\": \"thermal\", \"steps\": [",
            "{",
            "\"kind\": \"unit\",",
            "\"id\": \"thermal\",",
            "\"skill\": \"inspect\",",
            "\"after\": \"travel\",",
            "\"unitId\": { \"from\": \"ITEM_ID\" },",
            "\"parameters\": { \"target\": { \"from\": \"ITEM_ID\" }, \"mode\": { \"from\": \"ORDER_PARAMETER\", \"parameter\": \"mode\" } },",
            "\"destination\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"location\" }",
            "}",
            "] },",
            "{ \"in\": [\"visual\", \"thermal\"], \"steps\": [",
            "{",
            "\"kind\": \"unit\",",
            "\"id\": \"look\",",
            "\"skill\": \"inspect\",",
            "\"after\": \"travel\",",
            "\"unitId\": { \"from\": \"ITEM_ID\", \"suffix\": \".look\" },",
            "\"parameters\": { \"target\": { \"from\": \"ITEM_ID\" } },",
            "\"destination\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"location\" }",
            "}",
            "] }",
            "],",
            "\"otherwise\": { \"steps\": [] },",
            "\"whenMissing\": { \"failureClass\": \"NO_ITEM_FOR_TARGET\", \"unit\": \"thermal\", \"parameters\": [\"target\"] }",
            "}",
            "]",
            "}",
        ).joinToString("\n"),
    )

    /**
     * 최상위 분기 — 작업 지시 파라미터 `carrier` 로 운반을 고른다. `fleet` 이면 플릿 운반 하나([DELIVER_CONTAINER] 의 노드), `robot` 이면
     * 랙 도착 대기 뒤 로봇 운반 하나(`<작업 지시>.carry`), 다른 값이면 단위 없음, `carrier` 가 없으면 운반 단위 하나가
     * `FAILED`(`NO_CARRIER`, 파라미터 없음)다. 칸마다 한 줄이다.
     */
    val CARRY_BY_MODE: String = definition(
        "CarryByMode", "E2", "{}",
        listOf(
            "{",
            "\"kind\": \"choice\",",
            "\"id\": \"carrier\",",
            "\"on\": { \"from\": \"ORDER_PARAMETER\", \"parameter\": \"carrier\" },",
            "\"arms\": [",
            "{ \"equals\": \"fleet\", \"steps\": [",
            "{",
            "\"kind\": \"unit\",",
            "\"id\": \"deliver\",",
            "\"once\": true,",
            "\"route\": \"FLEET\",",
            "\"skill\": \"transport\",",
            "\"unitId\": { \"from\": \"FIRST_PROPERTY\", \"equipmentUse\": \"source\", \"property\": \"container\", \"else\": { \"from\": \"ORDER_ID\" } },",
            "\"parameters\": { \"container\": $CONTAINER },",
            "\"expectedIdentity\": $CONTAINER,",
            "\"source\": $FIRST_SOURCE,",
            "\"destination\": $FIRST_DESTINATION",
            "}",
            "] },",
            "{ \"equals\": \"robot\", \"steps\": [",
            "{\"kind\": \"wait\", \"id\": \"$WAIT_NODE\", \"signal\": \"$RACK_PRESENT\", \"expect\": \"true\", \"deadlineSeconds\": 120, \"onDeadline\": \"OPERATOR_HOLD\"},",
            "{",
            "\"kind\": \"unit\",",
            "\"id\": \"carry\",",
            "\"once\": true,",
            "\"skill\": \"pick_place\",",
            "\"unitId\": { \"from\": \"ORDER_ID\", \"suffix\": \".carry\" },",
            "\"parameters\": { \"object_id\": $CONTAINER },",
            "\"expectedIdentity\": $CONTAINER,",
            "\"source\": $FIRST_SOURCE,",
            "\"sourceObservation\": \"OPTIONAL\",",
            "\"destinationObservation\": \"OPTIONAL\",",
            "\"destination\": $FIRST_DESTINATION",
            "}",
            "] }",
            "],",
            "\"otherwise\": { \"steps\": [] },",
            "\"whenMissing\": { \"failureClass\": \"NO_CARRIER\", \"unit\": \"deliver\", \"parameters\": [] }",
            "}",
        ).joinToString("\n"),
    )

    /**
     * 그룹 안 분기의 목적지 관측 — 슬롯마다, 작업 지시 파라미터 `lane` 으로 둘 단위를 고른다. `main` 이면 `<슬롯>`(목적지 관측
     * `OPTIONAL`), `check` 면 `<슬롯>.checked`(목적지 관측 `REQUIRED`). 출발 자리는 슬롯의 `bin` 속성이다. 칸마다 한 줄이다.
     */
    val FILL_BY_LANE: String = definition(
        PREPARE, "E2", "{}",
        listOf(
            "{",
            "\"kind\": \"each\",",
            "\"id\": \"fill\",",
            "\"forEach\": \"destination\",",
            "\"steps\": [",
            "{",
            "\"kind\": \"choice\",",
            "\"id\": \"lane\",",
            "\"on\": { \"from\": \"ORDER_PARAMETER\", \"parameter\": \"lane\" },",
            "\"arms\": [",
            "{ \"equals\": \"main\", \"steps\": [ ${fillNode("place", "", "OPTIONAL")} ] },",
            "{ \"equals\": \"check\", \"steps\": [ ${fillNode("place-checked", ", \"suffix\": \".checked\"", "REQUIRED")} ] }",
            "],",
            "\"otherwise\": { \"steps\": [] },",
            "\"whenMissing\": { \"failureClass\": \"NO_LANE\", \"unit\": \"place\", \"parameters\": [] }",
            "}",
            "]",
            "}",
        ).joinToString("\n"),
    )

    private const val PREPARE = "PrepareSequencedRack"

    private fun fillNode(id: String, suffix: String, destinationObservation: String) =
        "{ \"kind\": \"unit\", \"id\": \"$id\", \"skill\": \"pick_place\", \"unitId\": { \"from\": \"ITEM_ID\"$suffix }, " +
            "\"parameters\": { \"object_id\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"material\" }, \"destination\": { \"from\": \"ITEM_ID\" } }, " +
            "\"expectedIdentity\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"material\" }, \"source\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"bin\" }, " +
            "\"sourceObservation\": \"OPTIONAL\", \"destinationObservation\": \"$destinationObservation\", \"destination\": { \"from\": \"ITEM_ID\" } }"


    /** 현장 신호 사양 — 랙 도착(자리 있음), 안전 신호 하나, 텍스트 신호 하나. */
    val SIGNALS: List<SignalSpec> = listOf(
        SignalSpec(RACK_PRESENT, location = DOCK, kind = SignalKind.BOOLEAN),
        SignalSpec("guard_closed", kind = SignalKind.BOOLEAN, safety = true),
        SignalSpec("lot_code", kind = SignalKind.TEXT),
    )

    /** 현장 기체들이 제공하는 스킬. */
    val SITE_SKILLS: Set<String> = setOf("pick_place", "navigate_to", "inspect")

    fun parsed(text: String): MissionDefinition = when (val p = MissionDefinitionParser.parse(text)) {
        is MissionParse.Parsed -> p.definition
        is MissionParse.Unreadable -> error("시험 정의를 못 읽었다: ${p.problems}")
    }
}

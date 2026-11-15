package dev.picasso.middleware.mission

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import dev.picasso.middleware.DestinationObservation
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.Route
import dev.picasso.middleware.SourceObservation

/** 임무 정의를 읽은 결과 — 읽었거나, 읽을 수 없는 자리를 **모두** 든다. */
sealed interface MissionParse {
    data class Parsed(val definition: MissionDefinition) : MissionParse

    /** 틀린 곳 전부. 하나만 알리면 고치고 다시 내고 또 거부되는 왕복이 틀린 곳 수만큼 생긴다. */
    data class Unreadable(val problems: List<String>) : MissionParse
}

/**
 * 임무 정의 JSON 의 **엄격한** 읽기 — 모르는 키, 빠진 필수 칸, 형이 틀린 값을 모두 모아 한 번에 알린다.
 *
 * **모양과 형만 본다.** 기한이 양수인지, 기한 뒤 상태가 허용된 값인지, 참조한 신호·스킬이 있는지는 [MissionValidator] 의
 * 일이다. 파서가 그것까지 보면 검증기의 검사를 빼는 결함이 파서에 가려 안 잡힌다.
 *
 * 값은 `JsonFormat` 으로 `Struct` 에 읽는다 — 새 의존을 들이지 않는다(결정 (차)). `Struct` 의 수는 실수로 오므로
 * 정수 칸은 정수인지 따로 본다. 문법과 중복 키는 `JsonFormat` 이 너그러워 [StrictJson] 이 먼저 본다.
 *
 * ## 표기
 *
 * ```json
 * {
 *   "schemaVersion": 1,
 *   "workMasterId": "PrepareSequencedRack",
 *   "maxEvidence": "E2",
 *   "preferredOptionals": { "verify_grasp": "true" },
 *   "steps": [
 *     { "kind": "wait", "id": "rack-arrival", "signal": "rack_present", "expect": "true",
 *       "deadlineSeconds": 120, "onDeadline": "OPERATOR_HOLD" },
 *     { "kind": "unit", "id": "place", "skill": "pick_place", "forEach": "destination",
 *       "pairWith": { "equipmentUse": "source", "property": "material" },
 *       "whenUnpaired": "NO_SOURCE_FOR_MATERIAL",
 *       "unitId": { "from": "ITEM_ID" },
 *       "parameters": {
 *         "object_id": { "from": "PAIRED_ID", "otherwise": "" },
 *         "destination": { "from": "ITEM_ID" }
 *       },
 *       "expectedIdentity": { "from": "ITEM_PROPERTY", "property": "material" },
 *       "source": { "from": "PAIRED_ID" },
 *       "sourceObservation": "OPTIONAL",
 *       "destinationObservation": "OPTIONAL",
 *       "destination": { "from": "ITEM_ID" } }
 *   ]
 * }
 * ```
 *
 * 최상위 다섯 칸은 모두 필수다. 단위 노드는 `id`·`skill`·`forEach`(또는 `"once": true`)·`unitId`·`parameters` 가 필수이고
 * 나머지는 선택이며, `pairWith` 와 `whenUnpaired` 는 함께 온다. `sourceObservation` 은 `REQUIRED`·`OPTIONAL` 중 하나이고, 없어도 읽는다 —
 * 신원을 대는 출발 자리에 선언이 없는 것은 검증기가 거부한다. `destinationObservation` 도 같은 두 값이고 없어도 읽는다 —
 * 식별 정보를 대는 목적지에 선언이 없는 것은 검증기가 거부한다. 대기 노드는 `id`·`signal`·`expect` 가 필수이고 `deadlineSeconds`·`onDeadline`
 * 은 형만 본다(없음은 검증기가 거부한다).
 *
 * ## 버전 1 에 더한 칸(§15.325)
 *
 * 아래 칸을 안 쓰는 문서는 전과 같은 뜻으로 읽힌다.
 *
 * ```json
 * { "kind": "each", "id": "visit", "forEach": "inspection_target",
 *   "requires": [ { "from": "ITEM_PROPERTY", "property": "location" } ],
 *   "whenMissing": { "failureClass": "NO_LOCATION_FOR_TARGET", "unit": "inspect", "parameters": ["target"] },
 *   "steps": [
 *     { "kind": "unit", "id": "travel", "skill": "navigate_to",
 *       "unitId": { "from": "ITEM_ID", "suffix": ".travel" }, "parameters": { … } },
 *     { "kind": "unit", "id": "inspect", "skill": "inspect", "after": "travel",
 *       "unitId": { "from": "ITEM_ID" },
 *       "parameters": { "mode": { "from": "ORDER_PARAMETER", "parameter": "mode" } } } ] }
 *
 * { "kind": "unit", "id": "deliver", "once": true, "route": "FLEET", "skill": "transport",
 *   "unitId": { "from": "FIRST_PROPERTY", "equipmentUse": "source", "property": "container", "else": { "from": "ORDER_ID" } }, … }
 * ```
 *
 * - **반복 그룹** `kind: "each"` — `id`·`forEach`·`steps`(단위 노드 1개 이상)가 필수다. 안쪽 단위 노드는 `forEach`·`once`·`pairWith`·
 *   `whenUnpaired`·`requires`·`whenMissing` 을 갖지 않는다. 그룹 안에는 대기 노드와 그룹이 오지 않는다
 * - **`once`** — 참거짓. 참이면 `forEach` 가 오지 않고 `pairWith` 도 오지 않는다
 * - **`route`** — `ROBOT`·`FLEET`. 없으면 `ROBOT` 이다. `after` — 문자열(참조가 맞는지는 검증기가 본다)
 * - **`requires`·`whenMissing`** — 함께 온다. `requires` 는 값 출처 1개 이상, `whenMissing` 은 `failureClass`(비지 않음)·`parameters`
 *   (문자열 배열, 빈 배열 가능)가 필수이고 `unit` 은 그룹에만 온다
 * - **값 출처** — `ORDER_ID`, `ORDER_PARAMETER`(`parameter` 필수), `FIRST_ID`(`equipmentUse` 필수), `FIRST_PROPERTY`(`equipmentUse`·
 *   `property` 필수)를 더했다. `FIRST_*` 는 `once` 노드에만, `ITEM_*`·`PAIRED_ID` 는 `once` 가 아닌 노드에만 온다
 * - **`unitId` 전용** — `suffix`(비지 않은 문자열)와 `else`(한 단계, `ITEM_ID`·`ORDER_ID`). 단위 id 는 반드시 값을 내야 하므로
 *   출처가 `ITEM_ID`·`ORDER_ID` 가 아니면 `else` 가 있어야 한다
 *
 * ## 버전 1 에 더한 칸 — 접수 시점 분기(§15.328)
 *
 * ```json
 * { "kind": "choice", "id": "how", "on": { "from": "ITEM_PROPERTY", "property": "item" },
 *   "arms": [ { "equals": "thermal", "steps": [ { "kind": "unit", "id": "thermal", … } ] },
 *             { "in": ["visual", "acoustic"], "steps": [ { "kind": "unit", "id": "look", … } ] } ],
 *   "otherwise": { "steps": [] },
 *   "whenMissing": { "failureClass": "NO_ITEM_FOR_TARGET", "unit": "thermal", "parameters": ["target"] } }
 * ```
 *
 * - **분기** `kind: "choice"` — `id`·`on`·`arms`(1개 이상)·`otherwise`·`whenMissing` 이 모두 필수다. 최상위나 반복 그룹 안에 온다
 * - **`on`** — 값 출처 하나, 대체값(`otherwise`) 없음. 출처는 `ORDER_PARAMETER`·`ITEM_PROPERTY`·`FIRST_PROPERTY` 뿐이고, 최상위
 *   분기는 작업 지시마다 한 번 고르므로 `once` 노드와 같은 자리 규칙(`ITEM_*` 금지), 그룹 안 분기는 그룹 안 노드와 같은 규칙(`FIRST_*` 금지)이다
 * - **갈래** — `equals`(문자열, 빈 문자열도 값) 와 `in`(문자열 1개 이상) 중 **하나**, 그리고 `steps`(1개 이상). 갈래 사이 값이 겹쳐도
 *   읽는다 — 선언 순서 첫 일치가 이긴다
 * - **`otherwise`** — `{ "steps": [...] }`, 빈 배열이 «단위 없음» 이다
 * - **`whenMissing`** — 조건부 포함의 것과 같은 칸이고 `unit`(갈래 안 단위 노드 id)이 필수다
 * - **갈래 안 노드** — 최상위 분기는 단위 노드(최상위 단위 노드와 같은 칸)와 대기 노드, 그룹 안 분기는 그룹 안 단위 노드뿐이다.
 *   분기·그룹은 갈래 안에 오지 않는다(중첩 없음)
 */
object MissionDefinitionParser {

    /** 지금 읽을 줄 아는 문서 모양의 버전. */
    const val SCHEMA_VERSION = 1

    fun parse(text: String): MissionParse {
        val scanned = StrictJson.scan(text)
        if (scanned.broken) return MissionParse.Unreadable(scanned.problems)
        val root = try {
            Struct.newBuilder().also { JsonFormat.parser().merge(text, it) }.build()
        } catch (e: InvalidProtocolBufferException) {
            return MissionParse.Unreadable(scanned.problems + "$: JSON 객체가 아니다 — ${e.message}")
        }
        val reader = Reader()
        val definition = reader.definition(root.fieldsMap)
        val problems = scanned.problems + reader.problems
        return if (problems.isEmpty() && definition != null) MissionParse.Parsed(definition) else MissionParse.Unreadable(problems)
    }

    private val TOP = setOf("schemaVersion", "workMasterId", "maxEvidence", "preferredOptionals", "steps")
    private val UNIT = setOf(
        "kind", "id", "skill", "forEach", "unitId", "parameters",
        "expectedIdentity", "source", "destination", "pairWith", "whenUnpaired", "sourceObservation",
        "destinationObservation", "once", "route", "requires", "whenMissing", "after",
    )

    /** 그룹이 쥐는 칸 — 그룹 안 단위 노드에는 오지 않는다. */
    private val GROUP_OWNED = listOf("forEach", "once", "pairWith", "whenUnpaired", "requires", "whenMissing")
    private val EACH = setOf("kind", "id", "forEach", "requires", "whenMissing", "steps")
    private val WAIT = setOf("kind", "id", "signal", "expect", "deadlineSeconds", "onDeadline")
    private val PAIRING = setOf("equipmentUse", "property")
    private val VALUE_SOURCE = setOf("from", "property", "otherwise", "equipmentUse", "parameter", "suffix", "else")
    private val WHEN_MISSING = setOf("failureClass", "unit", "parameters")
    private val CHOICE = setOf("kind", "id", "on", "arms", "otherwise", "whenMissing")
    private val ARM = setOf("equals", "in", "steps")
    private val OTHERWISE = setOf("steps")

    /**
     * 분기 조건이 읽는 출처 — 작업 지시가 싣는 값(파라미터·설비 속성)만이다(§15.328). 늘 값이 있는 id 출처는 부재가 없어 접힘이 영영
     * 안 돌고, 설비 id 로 갈래를 고르는 것은 작업 지시 값의 뜻이 아니다.
     */
    private val CHOICE_SOURCES = setOf(ValueFrom.ORDER_PARAMETER, ValueFrom.ITEM_PROPERTY, ValueFrom.FIRST_PROPERTY)

    /** 단위 노드가 받는 경로. 설비 대기([Route.SIGNAL])는 대기 노드의 몫이다. */
    private val ROUTES = listOf(Route.ROBOT, Route.FLEET)

    /** 언제나 값을 내는 출처 — 단위 id 는 이것이거나 `else` 가 이것이어야 한다. */
    private val ALWAYS = setOf(ValueFrom.ITEM_ID, ValueFrom.ORDER_ID)
    /** 반복 노드의 단위 id 가 시작할 출처 — 설비마다 다른 값이다. */
    private val LOOP_IDS = setOf(ValueFrom.ITEM_ID, ValueFrom.ITEM_PROPERTY)
    private val PROPERTY_SOURCES = setOf(ValueFrom.ITEM_PROPERTY, ValueFrom.FIRST_PROPERTY)
    private val FIRST_SOURCES = setOf(ValueFrom.FIRST_ID, ValueFrom.FIRST_PROPERTY)

    /** 반복 중인 설비(또는 그 짝)를 읽는 출처 — `once` 노드에는 반복 중인 설비가 없다. */
    private val ITEM_SOURCES = setOf(ValueFrom.ITEM_ID, ValueFrom.ITEM_PROPERTY, ValueFrom.PAIRED_ID)

    /** 값 출처를 읽는 자리 — 짝이 있는 노드인가, 작업 지시마다 한 번인 노드인가. */
    private data class Scope(val paired: Boolean, val once: Boolean)

    /** 정의가 낼 수 있는 등급. E3(업무 확인)은 상위의 ack 이라 케이퍼빌리티가 약속할 수 없다. */
    private val EVIDENCE = listOf(Evidence.E0, Evidence.E1, Evidence.E2)

    /**
     * 읽는 동안 틀린 곳을 모은다. 칸 하나가 틀려도 **나머지를 계속 읽는다** — 그래야 한 번에 다 알린다.
     * 틀린 칸의 함수는 널을 돌려주고, 널을 받은 쪽은 문제를 다시 적지 않는다.
     */
    private class Reader {
        val problems = mutableListOf<String>()

        private fun problem(path: String, what: String) {
            problems += "$path: $what"
        }

        private fun unknownKeys(fields: Map<String, Value>, allowed: Set<String>, path: String) {
            (fields.keys - allowed).sorted().forEach { problem(path, "모르는 키 '$it'") }
        }

        private fun present(fields: Map<String, Value>, key: String, path: String, required: Boolean): Value? {
            val value = fields[key]
            if (value == null) {
                if (required) problem("$path.$key", "빠졌다")
                return null
            }
            return value
        }

        fun string(fields: Map<String, Value>, key: String, path: String, required: Boolean = true): String? {
            val value = present(fields, key, path, required) ?: return null
            if (!value.hasStringValue()) {
                problem("$path.$key", "문자열이어야 한다(${kindOf(value)})")
                return null
            }
            if (value.stringValue.isEmpty() && required) {
                problem("$path.$key", "비었다")
                return null
            }
            return value.stringValue
        }

        fun integer(fields: Map<String, Value>, key: String, path: String, required: Boolean = true): Long? {
            val value = present(fields, key, path, required) ?: return null
            val n = if (value.hasNumberValue()) value.numberValue else null
            // `Struct` 는 수를 실수로 준다 — 1.5 나 1e300 을 정수로 접으면 쓴 값과 다른 값으로 돈다.
            if (n == null || n != Math.rint(n) || n < Long.MIN_VALUE.toDouble() || n > Long.MAX_VALUE.toDouble()) {
                problem("$path.$key", "정수여야 한다(${kindOf(value)})")
                return null
            }
            return n.toLong()
        }

        fun bool(fields: Map<String, Value>, key: String, path: String): Boolean? {
            val value = present(fields, key, path, required = false) ?: return null
            if (!value.hasBoolValue()) {
                problem("$path.$key", "참거짓이어야 한다(${kindOf(value)})")
                return null
            }
            return value.boolValue
        }

        fun list(fields: Map<String, Value>, key: String, path: String, required: Boolean): List<Value>? {
            val value = present(fields, key, path, required) ?: return null
            if (!value.hasListValue()) {
                problem("$path.$key", "배열이어야 한다(${kindOf(value)})")
                return null
            }
            return value.listValue.valuesList
        }

        fun obj(fields: Map<String, Value>, key: String, path: String, required: Boolean = true): Map<String, Value>? {
            val value = present(fields, key, path, required) ?: return null
            if (!value.hasStructValue()) {
                problem("$path.$key", "객체여야 한다(${kindOf(value)})")
                return null
            }
            return value.structValue.fieldsMap
        }

        fun stringMap(fields: Map<String, Value>, key: String, path: String): Map<String, String>? {
            val map = obj(fields, key, path) ?: return null
            val out = linkedMapOf<String, String>()
            map.forEach { (k, v) ->
                if (v.hasStringValue()) out[k] = v.stringValue else problem("$path.$key.$k", "문자열이어야 한다(${kindOf(v)})")
            }
            return out
        }

        fun definition(fields: Map<String, Value>): MissionDefinition? {
            val path = "$"
            unknownKeys(fields, TOP, path)
            val schemaVersion = integer(fields, "schemaVersion", path)
            if (schemaVersion != null && schemaVersion != SCHEMA_VERSION.toLong()) {
                problem("$path.schemaVersion", "모르는 문서 버전이다($schemaVersion) — 읽을 줄 아는 것은 $SCHEMA_VERSION 이다")
            }
            val workMasterId = string(fields, "workMasterId", path)
            val maxEvidence = string(fields, "maxEvidence", path)?.let { name ->
                EVIDENCE.firstOrNull { it.name == name }
                    ?: null.also { problem("$path.maxEvidence", "${EVIDENCE.joinToString("·")} 중 하나여야 한다($name)") }
            }
            val optionals = stringMap(fields, "preferredOptionals", path)
            val steps = steps(fields, path)
            if (problems.isNotEmpty()) return null
            return MissionDefinition(
                schemaVersion!!.toInt(), workMasterId!!, maxEvidence!!, optionals!!, steps!!,
            )
        }

        private fun steps(fields: Map<String, Value>, path: String): List<MissionStep>? {
            val items = nodes(fields, path) ?: return null
            return items.mapIndexedNotNull { index, item ->
                val at = "$path.steps[$index]"
                val node = item ?: return@mapIndexedNotNull null
                when (val kind = string(node, "kind", at)) {
                    null -> null
                    "unit" -> unit(node, at, inner = false)
                    "wait" -> wait(node, at)
                    "each" -> each(node, at)
                    "choice" -> choice(node, at, inner = false)
                    else -> null.also { problem("$at.kind", "unit·wait·each·choice 중 하나여야 한다($kind)") }
                }
            }
        }

        /** `steps` 배열을 읽는다 — 객체가 아닌 항목은 그 자리에 널을 두고 알린다. 비었으면 알린다. */
        private fun nodes(fields: Map<String, Value>, path: String): List<Map<String, Value>?>? {
            val value = present(fields, "steps", path, required = true) ?: return null
            if (!value.hasListValue()) {
                problem("$path.steps", "배열이어야 한다(${kindOf(value)})")
                return null
            }
            val items = value.listValue.valuesList
            if (items.isEmpty()) problem("$path.steps", "노드가 하나도 없다")
            return items.mapIndexed { index, item ->
                if (item.hasStructValue()) {
                    item.structValue.fieldsMap
                } else {
                    null.also { problem("$path.steps[$index]", "객체여야 한다(${kindOf(item)})") }
                }
            }
        }

        /**
         * 단위 노드. [inner] 면 반복 그룹 안쪽 노드다 — 반복(`forEach`·`once`)·짝·조건은 그룹이 쥐므로 그 칸이 오면 알린다.
         */
        private fun unit(node: Map<String, Value>, path: String, inner: Boolean): UnitStep? {
            unknownKeys(node, UNIT, path)
            if (inner) {
                GROUP_OWNED.filter { it in node }.forEach { problem("$path.$it", "그룹 안 단위 노드에는 오지 않는다 — 반복과 조건은 그룹이 쥔다") }
            }
            val id = string(node, "id", path)
            val skill = string(node, "skill", path)
            val once = !inner && bool(node, "once", path) == true
            if (once && "forEach" in node) problem(path, "once 와 forEach 는 함께 오지 않는다 — 작업 지시마다 한 번이거나 설비마다 하나다")
            if (once && "pairWith" in node) problem("$path.pairWith", "once 노드에는 짝이 없다 — 반복 중인 설비가 없다")
            val forEach = if (inner || once) null else string(node, "forEach", path)
            val pairWith = if (inner) null else obj(node, "pairWith", path, required = false)?.let { pairing(it, "$path.pairWith") }
            val whenUnpaired = if (inner) null else string(node, "whenUnpaired", path, required = false)
            if (!inner && ("pairWith" in node) != ("whenUnpaired" in node)) {
                problem(path, "pairWith 와 whenUnpaired 는 함께 온다 — 짝이 없을 때의 실패 분류가 없으면 그 단위를 어떻게 둘지 모른다")
            }
            val scope = Scope(paired = !inner && "pairWith" in node, once = once)
            val unitId = source(node, "unitId", path, required = true, scope = scope, unitId = true)
            if (unitId != null && unitId.from !in ALWAYS && unitId.orElse == null) {
                problem(
                    "$path.unitId.from",
                    "${ALWAYS.joinToString(" 나 ")} 여야 한다(${unitId.from}) — 단위 id 는 반드시 값을 낸다. 다른 출처는 else 로 그 둘 중 하나를 둔다",
                )
            }
            // 반복 노드의 단위 id 는 설비마다 달라야 한다 — 작업 지시의 값이나 짝의 id 는 설비마다 같을 수 있다(§15.325). FIRST_* 는 위에서 막는다.
            if (unitId != null && !once && unitId.from !in LOOP_IDS && unitId.from !in FIRST_SOURCES) {
                problem("$path.unitId.from", "반복 노드의 단위 id 는 ${LOOP_IDS.joinToString("·")} 로 시작한다(${unitId.from}) — 설비마다 같을 수 있는 값이라 단위 id 가 겹친다")
            }
            if (unitId?.orElse != null && !once && unitId.orElse.from != ValueFrom.ITEM_ID) {
                problem("$path.unitId.else.from", "반복 노드의 else 는 ${ValueFrom.ITEM_ID} 여야 한다(${unitId.orElse.from}) — 설비마다 같은 값이라 단위 id 가 겹친다")
            }
            val parameters = obj(node, "parameters", path)?.let { map ->
                val out = linkedMapOf<String, ValueSource>()
                map.forEach { (name, v) ->
                    val at = "$path.parameters.$name"
                    if (!v.hasStructValue()) {
                        problem(at, "값 출처 객체여야 한다(${kindOf(v)})")
                    } else {
                        valueSource(v.structValue.fieldsMap, at, scope)?.let { out[name] = it }
                    }
                }
                out
            }
            val expected = source(node, "expectedIdentity", path, required = false, scope = scope)
            val source = source(node, "source", path, required = false, scope = scope)
            val destination = source(node, "destination", path, required = false, scope = scope)
            val observation = string(node, "sourceObservation", path, required = false)?.let { name ->
                SourceObservation.entries.firstOrNull { it.name == name }
                    ?: null.also {
                        problem("$path.sourceObservation", "${SourceObservation.entries.joinToString("·")} 중 하나여야 한다($name)")
                    }
            }
            val destinationObservation = string(node, "destinationObservation", path, required = false)?.let { name ->
                DestinationObservation.entries.firstOrNull { it.name == name }
                    ?: null.also {
                        problem(
                            "$path.destinationObservation",
                            "${DestinationObservation.entries.joinToString("·")} 중 하나여야 한다($name)",
                        )
                    }
            }
            val route = string(node, "route", path, required = false)?.let { name ->
                ROUTES.firstOrNull { it.name == name }
                    ?: null.also { problem("$path.route", "${ROUTES.joinToString("·")} 중 하나여야 한다($name) — 설비 대기는 대기 노드로 적는다") }
            } ?: Route.ROBOT
            // 그룹 안의 플릿 노드는 두 정의 어느 쪽도 쓰지 않고 실행 시험이 없다 — 받지 않는다(§15.325 «넣지 않은 것»).
            if (inner && route == Route.FLEET) problem("$path.route", "그룹 안 단위 노드는 ${Route.ROBOT} 경로뿐이다 — 플릿 운반은 once 노드로 적는다")
            val after = string(node, "after", path, required = false)
            val (requires, whenMissing) = if (inner) emptyList<ValueSource>() to null else conditions(node, path, scope, group = false)
            if (id == null || skill == null || (forEach == null && !inner && !once) || unitId == null || parameters == null) return null
            return UnitStep(
                id, skill, forEach, unitId, parameters, expected, source, destination, pairWith, whenUnpaired, observation,
                destinationObservation, once, route, requires, whenMissing, after,
            )
        }

        /** 반복 그룹(`kind: "each"`). 안쪽은 단위 노드와 분기만 온다 — 대기·그룹은 그룹 밖에 둔다(중첩 없음). */
        private fun each(node: Map<String, Value>, path: String): EachStep? {
            unknownKeys(node, EACH, path)
            val id = string(node, "id", path)
            val forEach = string(node, "forEach", path)
            val (requires, whenMissing) = conditions(node, path, Scope(paired = false, once = false), group = true)
            val steps = nodes(node, path)?.mapIndexedNotNull { index, item ->
                val at = "$path.steps[$index]"
                val inner = item ?: return@mapIndexedNotNull null
                when (val kind = string(inner, "kind", at)) {
                    null -> null
                    "unit" -> unit(inner, at, inner = true)
                    "choice" -> choice(inner, at, inner = true)
                    else -> null.also { problem("$at.kind", "그룹 안에는 unit·choice 만 온다($kind) — 대기·그룹은 그룹 밖에 둔다") }
                }
            }
            if (id == null || forEach == null || steps == null) return null
            return EachStep(id, forEach, requires, whenMissing, steps)
        }

        /** 조건부 포함 — `requires`(값 출처 목록)와 `whenMissing` 은 함께 온다. [group] 이 아니면 `whenMissing.unit` 이 오지 않는다. */
        private fun conditions(node: Map<String, Value>, path: String, scope: Scope, group: Boolean): Pair<List<ValueSource>, WhenMissing?> {
            if (("requires" in node) != ("whenMissing" in node)) {
                problem(path, "requires 와 whenMissing 은 함께 온다 — 입력이 없을 때 그 단위를 어떻게 둘지 모른다")
            }
            val requires = list(node, "requires", path, required = false)?.let { items ->
                if (items.isEmpty()) problem("$path.requires", "출처가 하나도 없다")
                items.mapIndexedNotNull { index, v ->
                    val at = "$path.requires[$index]"
                    if (v.hasStructValue()) {
                        valueSource(v.structValue.fieldsMap, at, scope)?.also { required ->
                            // 늘 값을 내는 출처는 영영 접히지 않는다 — 적은 조건이 돌지 않는다.
                            if (required.from in ALWAYS) problem("$at.from", "늘 값이 있는 출처는 조건이 되지 않는다(${required.from})")
                            if (required.otherwise != null) problem("$at.otherwise", "조건에는 대체값이 오지 않는다 — 대체값이 있으면 영영 접히지 않는다")
                        }
                    } else {
                        null.also { problem(at, "값 출처 객체여야 한다(${kindOf(v)})") }
                    }
                }
            }.orEmpty()
            val whenMissing = obj(node, "whenMissing", path, required = false)?.let { whenMissing(it, "$path.whenMissing", group, unitRequired = false) }
            return requires to whenMissing
        }

        /** 접힘 모양. [unitAllowed] 가 아니면 `unit` 이 오지 않고, [unitRequired] 면 `unit` 이 필수다(분기). */
        private fun whenMissing(fields: Map<String, Value>, at: String, unitAllowed: Boolean, unitRequired: Boolean): WhenMissing? {
            unknownKeys(fields, WHEN_MISSING, at)
            val failureClass = string(fields, "failureClass", at)
            if (!unitAllowed && "unit" in fields) problem("$at.unit", "그룹에만 온다 — 그룹이 아닌 노드는 그 노드 하나가 남는다")
            val unit = if (unitAllowed) string(fields, "unit", at, required = unitRequired) else null
            val parameters = list(fields, "parameters", at, required = true)?.mapIndexedNotNull { index, v ->
                if (v.hasStringValue()) v.stringValue else null.also { problem("$at.parameters[$index]", "문자열이어야 한다(${kindOf(v)})") }
            }
            return if (failureClass == null || parameters == null || (unitRequired && unit == null)) null else WhenMissing(failureClass, unit, parameters)
        }

        /**
         * 접수 시점 분기(`kind: "choice"`, §15.328). [inner] 면 반복 그룹 안이다 — 조건은 항목마다 읽고(`FIRST_*` 금지), 갈래 안에는
         * 그룹 안 단위 노드만 온다. 최상위면 조건은 작업 지시마다 한 번이고(`ITEM_*` 금지) 갈래 안에 최상위 단위 노드와 대기 노드가 온다.
         */
        private fun choice(node: Map<String, Value>, path: String, inner: Boolean): ChoiceStep? {
            unknownKeys(node, CHOICE, path)
            val id = string(node, "id", path)
            val on = source(node, "on", path, required = true, scope = Scope(paired = false, once = !inner))?.also { on ->
                if (on.from !in CHOICE_SOURCES) problem("$path.on.from", "${CHOICE_SOURCES.joinToString("·")} 중 하나여야 한다(${on.from}) — 분기 조건은 작업 지시 값이다")
                if (on.otherwise != null) problem("$path.on.otherwise", "분기 조건에는 대체값이 오지 않는다 — 대체값이 있으면 영영 접히지 않는다")
            }
            val arms = list(node, "arms", path, required = true)?.let { items ->
                if (items.isEmpty()) problem("$path.arms", "갈래가 하나도 없다")
                items.mapIndexedNotNull { index, v ->
                    val at = "$path.arms[$index]"
                    if (v.hasStructValue()) arm(v.structValue.fieldsMap, at, inner) else null.also { problem(at, "객체여야 한다(${kindOf(v)})") }
                }
            }
            val otherwise = obj(node, "otherwise", path)?.let { fields ->
                unknownKeys(fields, OTHERWISE, "$path.otherwise")
                branch(fields, "$path.otherwise", inner, empty = true)
            }
            val whenMissing = obj(node, "whenMissing", path)?.let { whenMissing(it, "$path.whenMissing", unitAllowed = true, unitRequired = true) }
            if (id == null || on == null || arms == null || otherwise == null || whenMissing == null) return null
            return ChoiceStep(id, on, arms, otherwise, whenMissing)
        }

        /** 갈래 하나 — `equals`(문자열 하나) 와 `in`(문자열 목록) 중 하나와 `steps`. 빈 문자열도 값이다. */
        private fun arm(fields: Map<String, Value>, path: String, inner: Boolean): ChoiceArm? {
            unknownKeys(fields, ARM, path)
            if (("equals" in fields) == ("in" in fields)) problem(path, "equals 와 in 중 하나만 온다 — 갈래가 맞는 값을 정한다")
            val values = fields["equals"]?.let { v ->
                if (v.hasStringValue()) listOf(v.stringValue) else null.also { problem("$path.equals", "문자열이어야 한다(${kindOf(v)})") }
            } ?: list(fields, "in", path, required = false)?.let { items ->
                if (items.isEmpty()) problem("$path.in", "값이 하나도 없다")
                items.mapIndexedNotNull { index, v ->
                    if (v.hasStringValue()) v.stringValue else null.also { problem("$path.in[$index]", "문자열이어야 한다(${kindOf(v)})") }
                }
            }
            val steps = branch(fields, path, inner, empty = false)
            return if (values == null || steps == null) null else ChoiceArm(values, steps)
        }

        /** 갈래의 `steps` — [empty] 면 빈 배열이 «단위 없음» 이다. 분기·그룹은 오지 않는다(중첩 없음). */
        private fun branch(fields: Map<String, Value>, path: String, inner: Boolean, empty: Boolean): List<MissionStep>? {
            val items = list(fields, "steps", path, required = true) ?: return null
            if (items.isEmpty() && !empty) problem("$path.steps", "노드가 하나도 없다")
            val allowed = if (inner) "unit" else "unit·wait"
            return items.mapIndexedNotNull { index, item ->
                val at = "$path.steps[$index]"
                if (!item.hasStructValue()) return@mapIndexedNotNull null.also { problem(at, "객체여야 한다(${kindOf(item)})") }
                val fieldsAt = item.structValue.fieldsMap
                when (val kind = string(fieldsAt, "kind", at)) {
                    null -> null
                    "unit" -> unit(fieldsAt, at, inner = inner)
                    "wait" -> if (inner) null.also { problem("$at.kind", "그룹 안 분기의 갈래에는 $allowed 만 온다($kind) — 대기는 그룹 밖에 둔다") } else wait(fieldsAt, at)
                    else -> null.also { problem("$at.kind", "갈래 안에는 $allowed 만 온다($kind) — 분기·그룹은 갈래 밖에 둔다(중첩 없음)") }
                }
            }
        }

        private fun pairing(fields: Map<String, Value>, path: String): Pairing? {
            unknownKeys(fields, PAIRING, path)
            val use = string(fields, "equipmentUse", path)
            val property = string(fields, "property", path)
            return if (use == null || property == null) null else Pairing(use, property)
        }

        private fun source(
            node: Map<String, Value>,
            key: String,
            path: String,
            required: Boolean,
            scope: Scope,
            unitId: Boolean = false,
        ): ValueSource? = obj(node, key, path, required)?.let { valueSource(it, "$path.$key", scope, unitId) }

        /**
         * 값 출처 하나. [unitId] 면 `suffix`·`else` 를 받는다(단위 id 에만 온다). `else` 는 한 단계이고 `ITEM_ID`·`ORDER_ID` 뿐이다.
         */
        private fun valueSource(fields: Map<String, Value>, path: String, scope: Scope, unitId: Boolean = false): ValueSource? {
            unknownKeys(fields, VALUE_SOURCE, path)
            val fromName = string(fields, "from", path) ?: return null
            val from = ValueFrom.entries.firstOrNull { it.name == fromName }
                ?: return null.also { problem("$path.from", "${ValueFrom.entries.joinToString("·")} 중 하나여야 한다($fromName)") }
            val property = argument(fields, "property", path, from, PROPERTY_SOURCES)
            val equipmentUse = argument(fields, "equipmentUse", path, from, FIRST_SOURCES)
            val parameter = argument(fields, "parameter", path, from, setOf(ValueFrom.ORDER_PARAMETER))
            if (from == ValueFrom.PAIRED_ID && !scope.paired) {
                problem("$path.from", "${ValueFrom.PAIRED_ID} 인데 이 노드에 pairWith 가 없다")
            }
            if (scope.once && from in ITEM_SOURCES) problem("$path.from", "once 노드에는 반복 중인 설비가 없다($from)")
            if (!scope.once && from in FIRST_SOURCES) problem("$path.from", "${FIRST_SOURCES.joinToString("·")} 는 once 노드에만 온다($from)")
            val otherwise = string(fields, "otherwise", path, required = false)
            val suffix = if ("suffix" in fields && !unitId) {
                null.also { problem("$path.suffix", "unitId 에만 온다") }
            } else {
                string(fields, "suffix", path, required = "suffix" in fields)
            }
            if (unitId && "else" in fields && "otherwise" in fields) {
                problem("$path.otherwise", "else 와 함께 오지 않는다 — otherwise 가 먼저 이겨 else 가 쓰이지 않는다")
            }
            val orElse = if ("else" in fields && !unitId) {
                null.also { problem("$path.else", "unitId 에만 한 단계로 온다") }
            } else {
                obj(fields, "else", path, required = false)?.let { valueSource(it, "$path.else", scope) }?.also { fallback ->
                    if (fallback.from !in ALWAYS) problem("$path.else.from", "${ALWAYS.joinToString(" 나 ")} 여야 한다(${fallback.from})")
                }
            }
            if ((from in PROPERTY_SOURCES && property == null) || (from in FIRST_SOURCES && equipmentUse == null)) return null
            if (from == ValueFrom.ORDER_PARAMETER && parameter == null) return null
            return ValueSource(from, property, otherwise, equipmentUse, parameter, suffix, orElse)
        }

        /** 출처의 인자 칸 하나 — [owners] 출처에는 필수이고 다른 출처에는 오지 않는다. */
        private fun argument(fields: Map<String, Value>, key: String, path: String, from: ValueFrom, owners: Set<ValueFrom>): String? {
            if (from !in owners && key in fields) {
                problem("$path.$key", "${owners.joinToString("·")} 에만 온다")
                return null
            }
            return string(fields, key, path, required = from in owners)
        }

        private fun wait(node: Map<String, Value>, path: String): WaitStep? {
            unknownKeys(node, WAIT, path)
            val id = string(node, "id", path)
            val signal = string(node, "signal", path)
            // 기대 값은 빈 문자열도 값이다 — TEXT 신호가 비었을 때를 기다릴 수 있다.
            val expect = present(node, "expect", path, required = true)?.let { v ->
                if (v.hasStringValue()) v.stringValue else null.also { problem("$path.expect", "문자열이어야 한다(${kindOf(v)})") }
            }
            val deadline = integer(node, "deadlineSeconds", path, required = false)
            val onDeadline = string(node, "onDeadline", path, required = false)
            if (id == null || signal == null || expect == null) return null
            return WaitStep(id, signal, expect, deadline, onDeadline)
        }

        private fun kindOf(value: Value): String = when (value.kindCase) {
            Value.KindCase.NULL_VALUE -> "null"
            Value.KindCase.NUMBER_VALUE -> "수 ${value.numberValue}"
            Value.KindCase.STRING_VALUE -> "문자열 '${value.stringValue}'"
            Value.KindCase.BOOL_VALUE -> "참거짓 ${value.boolValue}"
            Value.KindCase.STRUCT_VALUE -> "객체"
            Value.KindCase.LIST_VALUE -> "배열"
            else -> "값 없음"
        }
    }
}

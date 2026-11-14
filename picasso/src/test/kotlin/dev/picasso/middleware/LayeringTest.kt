package dev.picasso.middleware

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **미들웨어 패키지의 파일이 층을 거슬러 기대지 않는다**(의존 정비 층 시험, 설계 변경 이력 §15.310).
 *
 * 파일마다 층을 정해 두고([LAYERS]), 파일 사이 간선이 아래로만 가는지를 댄다. 간선은 두 갈래로 모아 합친다 — 둘이 놓치는
 * 것이 달라서다.
 * - **소스 이름**: 주석과 문자열 글자를 뗀 본문에서, 다른 파일이 최상위로 선언한 이름이 낱말로 나오면 간선이다. `const val` 은
 *   컴파일 결과에서 값으로 인라인돼 사라지므로 이쪽만 본다. 대신 수신자 안의 한정 없는 멤버 호출(`fun Middleware.x` 와
 *   `with(mw)` 안의 `notify(...)`)은 이름이 안 보여 못 센다.
 * - **컴파일 결과**: 이 모듈의 클래스 파일을 상수 풀까지 읽어, 메서드·필드 참조의 주인과 서술자·서명에 든 타입을 간선으로 센다.
 *   클래스는 `SourceFile` 속성과 패키지로 원본 파일에 대응시킨다. `InnerClasses` 의 바깥 클래스 기록만으로는 간선을 세지 않는다
 *   (`Middleware.Submission.Rejected` 를 쓰면 `Middleware` 가 바깥 클래스로 적힌다). 못 보는 것: 타입 별칭, 인라인된 `const val`,
 *   서술자에 안 남는 `checkcast` 대상.
 *
 * 두 갈래가 같기를 요구하지 않는다. 합친 간선을 같은 표에 댄다.
 *
 * 층(아래가 먼저): [Layer.FLOOR] 값·포트 계약·인코딩(안에서 순환 금지) → [Layer.STATE] 실행 상태·관문·장부 →
 * [Layer.TRANSITION] 명령 생애와 접합부(안에서 순환 허용) → [Layer.FACADE] `Middleware`. 옆가지 [Layer.SIDE](포트 구현·데코레이터)와
 * [Layer.MISSION](`mission/` 하위 패키지 전체, 한 마디로 본다)은 바닥과 저희끼리만 쓰고, 다른 층은 옆가지를 쓰지 않는다.
 *
 * 거스르는 간선은 [UPWARD] 에 까닭과 함께 적은 것만 허용하고, 실제 집합이 그것과 **정확히 같아야** 한다 — 줄면 목록도 줄여야
 * 빨강이 풀린다(S4 가 생애의 `Middleware` 역참조를 걷을 때). `Middleware.kt` 밖에서 부르는 `Middleware` 멤버의 이름도
 * [MIDDLEWARE_MEMBERS] 와 같아야 한다 — 같은 파일 쌍 안에서 멤버를 하나 더 부르는 것도 잡는다.
 */
class LayeringTest {

    @Test
    fun `출하 파일마다 층이 있고 표에 없는 파일이 없다`() {
        val files = sources().keys
        val nodes = files.map(::node).toSet()
        assertEquals(emptyList(), (nodes - LAYERS.keys).sorted(), "층 표에 없는 출하 파일 — LAYERS 에 층과 함께 적는다")
        assertEquals(emptyList(), (LAYERS.keys - nodes).sorted(), "층 표에만 있고 없는 파일 — 표에서 지운다")
    }

    @Test
    fun `간선이 층을 거스르지 않는다`() {
        val edges = edges()
        val upward = edges.filter { (from, to) -> !allowed(layer(from), layer(to)) }.toSet()
        assertEquals(
            UPWARD.keys.sortedBy { it.toString() },
            upward.sortedBy { it.toString() },
            "층을 거스르는 간선이 허용 목록과 다르다 — 새 간선은 고치거나 까닭과 함께 UPWARD 에 적는다. 사라진 간선은 목록에서 지운다",
        )
    }

    @Test
    fun `바닥 층 안에 순환이 없다`() {
        val floor = edges().filter { (a, b) -> layer(a) == Layer.FLOOR && layer(b) == Layer.FLOOR }
        val next = floor.groupBy({ it.first }, { it.second })
        val cycle = findCycle(next)
        assertEquals(null, cycle, "바닥 층의 순환")
    }

    @Test
    fun `Middleware 밖의 파일은 Middleware 에서 Submission 만 쓴다`() {
        // S4c(§15.313)부터 상태 층만이 아니라 Middleware.kt 밖 모든 출하 파일에 건다 — 전이 층의 Revision 도 Submission 을 돌려준다.
        val qualified = sources().filterKeys { it != "Middleware.kt" }.mapValues { (_, text) -> qualifiedMiddlewareNames(text) }
        assertEquals(
            emptyMap(),
            qualified.mapValues { (_, names) -> names.filterNot { it == "Submission" } }.filterValues { it.isNotEmpty() },
            "Middleware.kt 밖의 파일이 Middleware 의 Submission 밖 이름을 쓴다",
        )
        val classes = compiled()
        val bad = classes.filter { it.file != "Middleware.kt" }.flatMap { c ->
            c.typeRefs.filter { it == MIDDLEWARE || (it.startsWith("$MIDDLEWARE$") && !it.startsWith("$MIDDLEWARE\$Submission")) }
                .map { "${c.file}: $it" } +
                c.memberRefs.filter { it.first == MIDDLEWARE || it.first == COMPANION }.map { "${c.file}: ${it.second}" }
        }
        assertEquals(emptyList(), bad.distinct().sorted(), "Middleware.kt 밖의 파일이 Middleware 의 객체·멤버를 쓴다(Submission 만 허용)")
    }

    @Test
    fun `Middleware 밖에서 부르는 Middleware 멤버가 고정 목록과 같다`() {
        val used = compiled().filter { it.file != "Middleware.kt" }
            .flatMap { c -> c.memberRefs.filter { it.first == MIDDLEWARE || it.first == COMPANION }.map { it.second } }
            .toSortedSet()
        assertTrue(compiled().any { it.memberRefs.isNotEmpty() }, "멤버 참조를 못 읽었다 — 판독기가 낡았나")
        assertEquals(
            MIDDLEWARE_MEMBERS.toSortedSet(),
            used,
            "Middleware.kt 밖에서 부르는 Middleware 멤버가 바뀌었다 — 늘었으면 S4 의 좁은 환경으로 돌리고, 줄었으면 목록을 줄인다",
        )
    }

    // ── 층 ────────────────────────────────────────────────────────────────────────────────────────────

    enum class Layer(val rank: Int) { FLOOR(0), STATE(1), TRANSITION(2), FACADE(3), SIDE(-1), MISSION(-2) }

    private fun layer(node: String): Layer = LAYERS[node] ?: error("층 표에 없는 마디: $node")

    private fun allowed(from: Layer, to: Layer): Boolean = when {
        from == to -> true
        to == Layer.FLOOR -> true
        from == Layer.SIDE || from == Layer.MISSION || to == Layer.SIDE || to == Layer.MISSION -> false
        else -> to.rank <= from.rank
    }

    /** `mission/` 아래는 한 마디로 본다. 나머지는 파일 이름이 마디다. */
    private fun node(file: String): String = if (file.startsWith("mission/")) "mission/" else file

    // ── 간선 ──────────────────────────────────────────────────────────────────────────────────────────

    private fun edges(): Set<Pair<String, String>> {
        val bySource = sourceEdges()
        val byClass = classEdges()
        assertTrue(bySource.size > 50, "소스 간선을 못 읽었다: ${bySource.size}")
        assertTrue(byClass.size > 50, "클래스 간선을 못 읽었다: ${byClass.size}")
        return (bySource + byClass).filter { it.first != it.second }.toSet()
    }

    private fun sourceEdges(): Set<Pair<String, String>> {
        val texts = sources().mapValues { (_, text) -> stripped(text) }
        val owners = mutableMapOf<String, MutableSet<String>>()
        texts.forEach { (file, text) -> topLevelNames(text).forEach { owners.getOrPut(it) { mutableSetOf() } += file } }
        val unique = owners.filterValues { it.size == 1 }.mapValues { it.value.single() }
        val edges = mutableSetOf<Pair<String, String>>()
        texts.forEach { (file, text) ->
            val words = WORD.findAll(text).map { it.value }.toSet()
            val members = MEMBER_DECL.findAll(text).map { it.groupValues[1] }.toSet()
            unique.forEach { (name, owner) ->
                if (owner != file && name in words && name !in members) edges += node(file) to node(owner)
            }
        }
        return edges
    }

    private fun classEdges(): Set<Pair<String, String>> {
        val classes = compiled()
        val known = classes.associate { it.name to it.file }
        // 클래스가 없는 중첩 이름(메타데이터에 남은 중첩 타입 별칭, 예: Middleware$Execution)은 바깥 클래스의 파일로 본다.
        val fileOf = { n: String -> known[n] ?: n.takeIf { '$' in it }?.let { known[it.substringBeforeLast('$')] } }
        val missing = classes.flatMap { c -> (c.typeRefs + c.memberRefs.map { it.first }).filter { fileOf(it) == null } }.toSortedSet()
        assertEquals(emptySet(), missing, "이 패키지의 클래스인데 원본 파일을 못 찾았다")
        return classes.flatMap { c ->
            (c.typeRefs + c.memberRefs.map { it.first }).map { node(c.file) to node(fileOf(it)!!) }
        }.toSet()
    }

    private fun findCycle(next: Map<String, List<String>>): List<String>? {
        val state = mutableMapOf<String, Int>()
        val path = ArrayDeque<String>()
        fun visit(v: String): List<String>? {
            state[v] = 1; path.addLast(v)
            for (w in next[v].orEmpty()) {
                when (state[w]) {
                    1 -> return path.dropWhile { it != w } + w
                    null -> visit(w)?.let { return it }
                }
            }
            state[v] = 2; path.removeLast()
            return null
        }
        next.keys.sorted().forEach { if (state[it] == null) visit(it)?.let { c -> return c } }
        return null
    }

    // ── 소스 ──────────────────────────────────────────────────────────────────────────────────────────

    /** 패키지 아래 출하 소스, 패키지 기준 상대 경로에서 본문으로. */
    private fun sources(): Map<String, String> {
        val root = Path.of("src", "main", "kotlin", "dev", "picasso", "middleware")
        val found = Files.walk(root).use { paths ->
            paths.asSequence().filter { it.isRegularFile() && it.name.endsWith(".kt") }
                .map { root.relativize(it).toString().replace('\\', '/') to Files.readString(it) }.toList()
        }
        assertTrue(found.size > 40, "출하 소스를 못 훑었다: ${found.size}")
        return found.toMap()
    }

    /** 주석을 떼고 문자열의 글자를 비운다. 문자열 템플릿 `${…}` 의 안은 코드라 남긴다. */
    private fun stripped(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        val templates = ArrayDeque<Int>()
        var inString: String? = null
        while (i < text.length) {
            val c = text[i]
            if (inString == null) {
                when {
                    text.startsWith("//", i) -> { while (i < text.length && text[i] != '\n') i++; continue }
                    text.startsWith("/*", i) -> { i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }; out.append(' '); continue }
                    text.startsWith("\"\"\"", i) -> { inString = "\"\"\""; out.append("\"\""); i += 3; continue }
                    c == '"' -> { inString = "\""; out.append("\"\""); i++; continue }
                    c == '\'' -> { val end = text.indexOf('\'', i + if (text.startsWith("'\\", i)) 3 else 2); i = if (end < 0) i + 1 else end + 1; out.append("''"); continue }
                    c == '}' && templates.isNotEmpty() && templates.last() == 0 -> { templates.removeLast(); inString = stringStack.removeLast(); i++; continue }
                    c == '{' && templates.isNotEmpty() -> templates[templates.lastIndex] = templates.last() + 1
                    c == '}' && templates.isNotEmpty() -> templates[templates.lastIndex] = templates.last() - 1
                }
                out.append(c); i++
            } else {
                when {
                    c == '\\' && inString == "\"" -> i += 2
                    text.startsWith(inString, i) -> { i += inString.length; inString = null }
                    text.startsWith("\${", i) -> { stringStack.addLast(inString); inString = null; templates.addLast(0); out.append(' '); i += 2 }
                    else -> i++
                }
            }
        }
        return out.toString()
    }

    private val stringStack = ArrayDeque<String>()

    private fun topLevelNames(text: String): Set<String> =
        (TOP_TYPE.findAll(text).map { it.groupValues[1] } + TOP_FUN.findAll(text).map { it.groupValues[1] } +
            TOP_VAL.findAll(text).map { it.groupValues[1] }).filterNot { it in PRIVATE_TOP.findAll(text).map { m -> m.groupValues[1] }.toSet() }
            .toSet()

    /** `Middleware.X` 와 `import ….Middleware.X` 의 X(주석·문자열 밖). `Middleware.Companion.Y` 는 `Companion` 으로 센다. */
    private fun qualifiedMiddlewareNames(text: String): Set<String> =
        Regex("""\bMiddleware\.([A-Za-z_]\w*)""").findAll(stripped(text)).map { it.groupValues[1] }.toSet()

    // ── 컴파일 결과 ───────────────────────────────────────────────────────────────────────────────────

    private class Compiled(
        val name: String,
        val file: String,
        val typeRefs: Set<String>,
        val memberRefs: Set<Pair<String, String>>,
    )

    private fun compiled(): List<Compiled> = compiledCache

    private val compiledCache: List<Compiled> by lazy {
        val root = Path.of("build", "classes", "kotlin", "main")
        val pkg = root.resolve(Path.of("dev", "picasso", "middleware"))
        val found = Files.walk(pkg).use { paths ->
            paths.asSequence().filter { it.isRegularFile() && it.name.endsWith(".class") }.map { read(Files.readAllBytes(it)) }.toList()
        }
        assertTrue(found.size > 100, "클래스 파일을 못 훑었다: ${found.size} — 컴파일 산출 자리가 바뀌었나")
        // 인라인된 표준 라이브러리 함수가 만든 합성 클래스(예: AdmissionGate$inconsistent$$inlined$groupingBy$1)는 SourceFile 이
        // 라이브러리 파일(_Collections.kt)을 가리킨다. 그런 클래스는 이름의 맨 앞 클래스가 난 파일로 본다.
        val shipped = sources().keys
        val byName = found.associateBy { it.name }
        found.map { c ->
            if (c.file in shipped) c
            else {
                val outer = byName[c.name.substringBefore('$')]?.file?.takeIf { it in shipped }
                Compiled(c.name, checkNotNull(outer) { "${c.name} 의 원본 파일을 못 찾았다(${c.file})" }, c.typeRefs, c.memberRefs)
            }
        }
    }

    /** 클래스 파일 하나를 상수 풀과 클래스 속성까지 읽는다. 모르는 상수 태그는 실패한다. */
    private fun read(bytes: ByteArray): Compiled {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        check(input.readInt() == 0xCAFEBABE.toInt()) { "클래스 파일이 아니다" }
        input.readUnsignedShort(); input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val utf8 = arrayOfNulls<String>(count)
        val classIndex = IntArray(count) { -1 }
        val refs = mutableListOf<Pair<Int, Int>>() // (class 항목, name-and-type 항목)
        val nameAndType = IntArray(count) { -1 }
        var i = 1
        while (i < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[i] = input.readUTF()
                3, 4 -> input.readInt()
                5, 6 -> { input.readLong(); i++ }
                7 -> classIndex[i] = input.readUnsignedShort()
                8, 16, 19, 20 -> input.readUnsignedShort()
                9, 10, 11 -> refs += input.readUnsignedShort() to input.readUnsignedShort()
                12 -> { nameAndType[i] = input.readUnsignedShort(); input.readUnsignedShort() }
                15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                17, 18 -> { input.readUnsignedShort(); input.readUnsignedShort() }
                else -> error("모르는 상수 태그 $tag")
            }
            i++
        }
        fun className(entry: Int): String = utf8[classIndex[entry]]!!
        input.readUnsignedShort()
        val self = className(input.readUnsignedShort())
        input.readUnsignedShort()
        repeat(input.readUnsignedShort()) { input.readUnsignedShort() }
        fun skipMembers() = repeat(input.readUnsignedShort()) {
            input.readUnsignedShort(); input.readUnsignedShort(); input.readUnsignedShort()
            repeat(input.readUnsignedShort()) { input.readUnsignedShort(); input.skipBytes(input.readInt()) }
        }
        skipMembers(); skipMembers()
        var source: String? = null
        val outerOnly = mutableSetOf<Int>()
        repeat(input.readUnsignedShort()) {
            val attribute = utf8[input.readUnsignedShort()]
            val length = input.readInt()
            when (attribute) {
                "SourceFile" -> source = utf8[input.readUnsignedShort()]
                "InnerClasses" -> repeat(input.readUnsignedShort()) {
                    input.readUnsignedShort(); outerOnly += input.readUnsignedShort(); input.readUnsignedShort(); input.readUnsignedShort()
                }
                else -> input.skipBytes(length)
            }
        }
        val owners = refs.map { it.first }.toSet()
        val inPackage = { n: String -> n.startsWith(PKG) }
        val classes = (1 until count).filter { classIndex[it] >= 0 && (it in owners || it !in outerOnly) }.map(::className)
        val described = utf8.filterNotNull().flatMap { s -> DESCRIPTOR.findAll(s).map { it.groupValues[1] } }
        val types = (classes + described).filter(inPackage).filter { it != self && !it.startsWith("$self$") }.toSet()
        val members = refs.map { (owner, nat) -> className(owner) to utf8[nameAndType[nat]]!! }.filter { inPackage(it.first) }.toSet()
        val dir = self.removePrefix(PKG).substringBeforeLast('/', "")
        val file = (if (dir.isEmpty()) "" else "$dir/") + checkNotNull(source) { "$self 에 SourceFile 이 없다" }
        return Compiled(self, file, types, members.filter { it.first != self }.toSet())
    }

    private companion object {
        const val PKG = "dev/picasso/middleware/"
        const val MIDDLEWARE = "dev/picasso/middleware/Middleware"
        const val COMPANION = "dev/picasso/middleware/Middleware\$Companion"

        val WORD = Regex("""[A-Za-z_][A-Za-z0-9_]*""")
        val DESCRIPTOR = Regex("""L(dev/picasso/middleware/[A-Za-z0-9_/$]+)[;<]""")
        val TOP_TYPE = Regex("""(?m)^(?:@\S+\s+)*(?:(?:public|internal|sealed|data|enum|abstract|open|value|annotation|inline)\s+)*(?:fun\s+interface|class|interface|object|typealias)\s+([A-Z]\w*)""")
        val TOP_FUN = Regex("""(?m)^(?:(?:public|internal|inline)\s+)*fun\s+(?:<[^>]+>\s+)?(?:[A-Z][\w<>?, .]*\.)?([a-zA-Z]\w*)\s*\(""")
        val TOP_VAL = Regex("""(?m)^(?:(?:public|internal|const)\s+)*val\s+([A-Za-z_]\w*)""")
        val PRIVATE_TOP = Regex("""(?m)^private\s+(?:fun\s+(?:<[^>]+>\s+)?(?:[A-Z][\w<>?, .]*\.)?|val\s+|(?:(?:data|sealed|enum)\s+)?(?:class|interface|object)\s+)([A-Za-z_]\w*)""")
        val MEMBER_DECL = Regex("""(?m)^[ \t]+(?:(?:override|private|internal|protected|open)\s+)*(?:fun|val|var)\s+(?:<[^>]+>\s+)?(?:\w+\.)?([A-Za-z_]\w*)""")

        /** 파일(또는 `mission/`)마다의 층. 새 파일은 여기 적어야 초록이다. */
        val LAYERS: Map<String, Layer> = linkedMapOf(
            // 바닥 — 값, 포트 계약, 인코딩, 정준 투영, 코드 업무. 실행 상태를 모른다.
            "Model.kt" to Layer.FLOOR,
            "Entitlement.kt" to Layer.FLOOR,
            "Json.kt" to Layer.FLOOR,
            "Notices.kt" to Layer.FLOOR,
            "Workspace.kt" to Layer.FLOOR,
            "RemedyLedger.kt" to Layer.FLOOR,
            "Retention.kt" to Layer.FLOOR,
            "Reassignment.kt" to Layer.FLOOR,
            "FloorOwnership.kt" to Layer.FLOOR,
            "LogicalCapability.kt" to Layer.FLOOR,
            "Incident.kt" to Layer.FLOOR,
            "Approval.kt" to Layer.FLOOR,
            "JudgmentRecord.kt" to Layer.FLOOR,
            "Occupancy.kt" to Layer.FLOOR,
            "CancelAxes.kt" to Layer.FLOOR,
            "Flights.kt" to Layer.FLOOR,
            "Canonical.kt" to Layer.FLOOR,
            "Ports.kt" to Layer.FLOOR,
            "CodeCapabilities.kt" to Layer.FLOOR,
            "LedgerExport.kt" to Layer.FLOOR,
            "ResultExport.kt" to Layer.FLOOR,
            "MiddlewareCodes.kt" to Layer.FLOOR,
            "RobotView.kt" to Layer.FLOOR,
            // 상태 — 실행, 배정 게이트, 장부, 단위의 순수 판정
            "Execution.kt" to Layer.STATE,
            "AdmissionGate.kt" to Layer.STATE,
            "Assignment.kt" to Layer.STATE,
            "RemedyDesk.kt" to Layer.STATE,
            "IncidentLog.kt" to Layer.STATE,
            "IssuingGate.kt" to Layer.STATE,
            "UnitFacts.kt" to Layer.STATE,
            "CommandEnv.kt" to Layer.STATE,
            "CommandLifecycle.kt" to Layer.STATE,
            // 전이 — 명령 생애와 접합부. 안의 순환은 허용한다.
            "LifecycleCancel.kt" to Layer.TRANSITION,
            "LifecycleCancelAttach.kt" to Layer.TRANSITION,
            "LifecycleDoubt.kt" to Layer.TRANSITION,
            "LifecycleIssue.kt" to Layer.TRANSITION,
            "RouteDrivers.kt" to Layer.TRANSITION,
            "UnitDrivers.kt" to Layer.TRANSITION,
            "Revision.kt" to Layer.TRANSITION,
            "Resume.kt" to Layer.TRANSITION,
            "RobotSync.kt" to Layer.TRANSITION,
            "EvidenceBinding.kt" to Layer.TRANSITION,
            "CommandPorts.kt" to Layer.TRANSITION,
            "ClockSkew.kt" to Layer.TRANSITION,
            // 발신자 거부 뒤 회복(§15.320) — 장부·관문을 쓰고 재작업의 되돌림(EvidenceBinding)을 부른다.
            "HoldRecovery.kt" to Layer.TRANSITION,
            // 조율·공개 창구
            "Middleware.kt" to Layer.FACADE,
            // 옆가지 — 포트 구현과 데코레이터
            "ClientRobotPort.kt" to Layer.SIDE,
            "PrefetchingRobotPort.kt" to Layer.SIDE,
            "PrefetchingFleetPort.kt" to Layer.SIDE,
            "IssuingRobotPort.kt" to Layer.SIDE,
            "IssuingFleetPort.kt" to Layer.SIDE,
            "CommandLanes.kt" to Layer.SIDE,
            // 임무 정의(하위 패키지 전체)
            "mission/" to Layer.MISSION,
        )

        /** 층을 거스르는 간선과 그 까닭. 실제 집합과 정확히 같아야 한다. */
        val UPWARD: Map<Pair<String, String>, String> = buildMap {
            // 공개 결과 계약 — 하위 파일이 `Middleware.Submission.*` 을 짓거나 돌려준다(§15.307, 리비전은 §15.313). 이 간선은 Submission 만 쓴다는
            // 것을 `Middleware 밖의 파일은 Middleware 에서 Submission 만 쓴다` 가 따로 댄다. 풀 단계 없음 — 푸는 것은 공개 API 변경이다.
            listOf("AdmissionGate.kt", "Assignment.kt", "RemedyDesk.kt", "Revision.kt", "UnitFacts.kt", "HoldRecovery.kt")
                .forEach { put(it to "Middleware.kt", "공개 결과 계약 Submission(§15.307)") }
        }

        /**
         * `Middleware.kt` 밖에서 부르는 `Middleware`(와 companion) 멤버의 JVM 이름. 2026-11-13 실측 28 → S4a(§15.311) 21 → S4b(§15.312) 10 →
         * S4c(§15.313) 0: 창구 절차(재개·리비전·건너뛴 단위)도 좁은 환경의 확장이 되고 실행 표·게이트·장부는 인자로 받는다. 다시 늘면 빨강이다.
         */
        val MIDDLEWARE_MEMBERS: Set<String> = emptySet()
    }
}

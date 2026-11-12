package dev.picasso.gate

import dev.picasso.gate.checks.Check07ModelBranching
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **세어서 적은 것을 다시 센다.**
 *
 * 이 저장소가 반복해 물린 자리다 — 모듈이 늘고 검사가 늘고 어댑터가 느는데 산문의 숫자는 그대로였다. 사람이
 * 훑어 고치면 다음 달에 다시 어긋나므로, **숫자를 코드에서 세어 문서와 댄다.**
 *
 * ## 왜 게이트에 있나
 *
 * 게이트가 이미 저장소의 파일을 읽어 규칙을 집행하는 자리이고(검사 4·5·7·8), 이 검사도 같은 종류다. **다만
 * 게이트 검사로 만들지는 않았다** — 게이트는 PR 을 막는 것이고 산문의 숫자 하나로 남의 PR 을 막으면 그 검사는
 * 곧 꺼진다. 여기서는 시험으로 둔다.
 *
 * ## 텍스트만 읽는다
 *
 * `gate` 는 `contracts` 에도 `registry` 에도 의존하지 않는다(§3.2). 그래서 계약 개정판도 진단 개수도 **소스를
 * 문자열로 읽어** 센다 — 의존을 만들어 이 시험을 편하게 하면 그것이 §3.2 를 어기는 첫 예외가 된다.
 */
class DocumentClaimsTest {

    private val repo: Path = Repo.root

    private fun read(relative: String): String = Repo.read(relative)

    /** 관문 칸의 백틱 기호. `Type.member` 또는 `member` 꼴만 본다. */
    private val gateSymbol = Regex("`([A-Za-z][A-Za-z0-9]*(?:\\.[A-Za-z][A-Za-z0-9]*)?)`")

    /** 백틱 안이지만 기호가 아닌 것 — 거절의 **값**이지 관문의 이름이 아니다. */
    private val notSymbols = setOf("null", "true", "false")

    /**
     * **모든 모듈의** 출하 소스를 파일마다. 점 기호의 **소속**을 댈 때 쓴다 — 주인과 멤버가 같은 파일에
     * 선언돼 있는가. 훑는 범위의 규칙은 여기 한 벌이고 [mainSource] 는 이것을 잇는다.
     *
     * ★**범위는 `settings.gradle.kts` 가 선언한 모듈의 `src/main` 이다.** 시험의 수를 세는 자리와 같은
     * 범위이고, `Repo.path` 를 지나므로 선언된 입력 밖은 못 읽는다. 저장소를 통째로 걷고 경로 문자열로
     * 거르던 판은 워크트리(`.claude/worktrees/`) 안에서 돌면 모든 경로에 `/.claude/` 가 들어 **범위가
     * 비었고** 대장의 기호가 전부 «코드에 없다» 로 나왔다(§15.204). 음성 하네스의 overlay 도 같이 읽혀서
     * 진짜 선언이 지워져도 overlay 의 사본이 그 기호를 살려 둘 수 있었다.
     */
    private val mainFiles: Map<Path, String> by lazy {
        modules().distinct().flatMap { module ->
            val dir = Repo.path("$module/src/main")
            if (!Files.isDirectory(dir)) emptyList()
            else Files.walk(dir).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
            }
        }.associateWith { Files.readString(it) }
    }

    /**
     * **모든 모듈의** 출하 소스 전문. 기호 하나마다 파일을 다시 읽지 않는다.
     *
     * ★한 모듈만 훑던 판이 있었다. 대장의 관문은 미들웨어에만 있는 것이 아니라 어댑터 경계에도 있고
     * (`SiteBindingCheck`), 훑는 범위가 좁으면 **멀쩡한 기호가 «코드에 없다» 로 나온다** — 그때 고쳐지는
     * 것은 검사가 아니라 문서이고, 그러면 대장이 검사를 피해 쓰이기 시작한다.
     */
    private val mainSource by lazy { mainFiles.values.joinToString("\n") }

    /**
     * **`picasso` 의 시험 소스 전문.** 승인 창구는 배포 가능한 프로세스가 아니라 시험 곁에 있고(§15.176),
     * 출하 소스만 훑으면 **멀쩡한 이름이 «코드에 없다» 로 나온다** — 그때 고쳐지는 것은 검사가 아니라
     * 문서다(`mainSource` 가 같은 자리에서 한 번 물렸다).
     */
    private val hostSource by lazy {
        Files.walk(Repo.path("picasso/src/test")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .map { Files.readString(it) }
                .toList()
                .joinToString("\n")
        }
    }

    private val readme by lazy { read("README.md") }
    private val design by lazy { read("docs/superpowers/specs/2026-09-05-picasso-design.md") }

    /** `settings.gradle.kts` 의 `include(...)` 에 적힌 이름 전부. */
    private fun modules(): List<String> =
        Regex("\"([a-z0-9-]+)\"").findAll(read("settings.gradle.kts").substringAfter("rootProject.name"))
            .map { it.groupValues[1] }
            .filter { it != "picasso" || true }
            .toList()

    private fun claimed(pattern: String, where: String = readme): Int {
        val match = Regex(pattern).find(where) ?: error("문서에서 이 진술을 못 찾았다: $pattern")
        // 산문의 낱말에는 문장부호가 붙는다 — 그것까지 세지 않는다.
        val word = match.groupValues[1].trim('.', ',', ')', '(', '·', ':', '*')
        return word.toIntOrNull() ?: NUMERALS[word] ?: error("셀 수 없는 낱말이다: '$word'")
    }

    @Test
    fun `README 의 모듈 나무가 빌드의 모듈과 같다`() {
        // 나무에 없는 모듈은 **읽는 사람에게 없는 모듈**이고, 나무에만 있는 모듈은 사라진 것을 가리킨다.
        val tree = readme.substringAfter("```").substringBefore("```")
        val declared = modules().toSet()
        val missing = declared.filterNot { "$it/" in tree }
        assertEquals(emptyList(), missing, "빌드에는 있는데 README 나무에 없다")

        val listed = Regex("^([a-z0-9-]+)/", RegexOption.MULTILINE).findAll(tree).map { it.groupValues[1] }.toSet()
        assertEquals(emptyList(), (listed - declared - IGNORED_DIRS).toList(), "README 나무에만 있고 빌드에 없다")
    }

    @Test
    fun `모듈마다 문이 있다`() {
        // **모듈 나무가 이름을 적는 것과 그 안으로 들어갈 문이 있는 것은 다르다.** 모듈이 늘면 문도 늘어야 하고,
        // 그것을 사람이 기억하지 않는다.
        val missing = modules().filterNot { Files.isRegularFile(Repo.path("$it/README.md")) }
        assertEquals(emptyList(), missing, "이 모듈에는 README.md 가 없다")
    }

    @Test
    fun `게이트 검사의 수를 README 가 맞게 적는다`() {
        assertEquals(GateChecks.all().size, claimed("""검사 (\S+)이 있고"""), "검사가 늘었는데 README 가 그대로다")
    }

    @Test
    fun `실물 어댑터의 수를 README 가 맞게 적는다`() {
        // `adapter-core`·`adapter-host` 는 기종을 모르는 공용 모듈이라 세지 않는다(ADR 33·39).
        val adapters = modules().count { it.startsWith("adapter-") && it !in setOf("adapter-core", "adapter-host") }
        assertEquals(adapters, claimed("""실물 어댑터가 (\S+) 있다"""), "어댑터가 늘었는데 README 가 그대로다")
        assertEquals(adapters, claimed("""어댑터 (\S+) 중 어느 것도 실물에 붙여 보지 못했다"""))
    }

    @Test
    fun `거리 문서의 수를 README 가 맞게 적는다`() {
        // 거리 문서는 **잰 벤더 표면마다 하나**다. 어댑터가 늘면 여기도 는다.
        val measured = Repo.list("profile/distance", ".json").size
        assertEquals(measured, claimed("""실물 (\S+)이 계약에 얼마나 닿나"""), "거리 문서가 늘었는데 README 가 그대로다")
    }

    @Test
    fun `계약 개정판을 README 가 맞게 적는다`() {
        val semver = Regex("""val contractSemver = "([0-9.]+)"""").find(read("contracts/build.gradle.kts"))
            ?.groupValues?.get(1) ?: error("contracts 가 개정판을 안 적는다")
        assertTrue("계약 개정판은 **$semver**" in readme, "README 의 계약 개정판이 $semver 가 아니다")
    }

    @Test
    fun `진단 표면의 수를 README 가 맞게 적는다`() {
        val diagnostics = Regex("""@GetMapping\("/diag/""")
            .findAll(read("registry/src/main/kotlin/dev/picasso/registry/web/DiagController.kt")).count()
        assertEquals(diagnostics, claimed("""진단 표면 (\S+)"""), "진단이 늘었는데 README 가 그대로다")
    }

    @Test
    fun `게이트 7번이 보는 모듈 목록을 설계 문서가 그대로 적는다`() {
        // **목록이 두 곳에 있다.** 코드가 정본이고 문서는 그것을 옮긴 것이므로, 어긋나면 문서가 틀린 것이다.
        val listed = Check07ModelBranching.MODULES.joinToString(" · ") { "`$it`" }
        assertTrue(listed in design, "설계 §11.2 의 7번 목록이 코드와 다르다 — 코드: $listed")
    }

    @Test
    fun `ADR 파일마다 설계 §14 의 행이 있다`() {
        // ★★**이 표가 세 번째로 낡았다.** §15.165 가 «39 에서 멈춰 있었다» 를 잡아 43 까지 채웠는데
        //   44 가 들어오면서 또 멈췄다. 색인이 «번호는 §14 의 행 번호» 라 적고 있는데도 그렇다 —
        //   **대는 시험이 없는 표는 낡는다.** 사람이 훑는 일을 여기서 그만둔다.
        val files = Repo.declaredFiles()
            .map { ClaimSurface.relative(it) }
            .mapNotNull { Regex("""^docs/adr/(\d{4})-""").find(it)?.groupValues?.get(1)?.toInt() }
            .sorted()
        assertTrue(files.isNotEmpty(), "ADR 파일을 하나도 못 읽었다 — 선언된 입력을 먼저 본다")

        val rows = Regex("""(?m)^\| \[?(\d+)\]?[(|]""").findAll(design)
            .map { it.groupValues[1].toInt() }
            .toSet()
        assertEquals(emptyList(), files.filterNot { it in rows }, "ADR 파일이 있는데 설계 §14 에 행이 없다")
    }

    @Test
    fun `자원 소유 대장이 대는 관문이 코드에 실재한다`() {
        // `orchestration.md` 는 *"이 자원으로 가는 명령은 누구의 관문을 지나나"* 에 답하는 표다. 거기 적힌
        // 기호가 사라지거나 이름이 바뀌면 **그 답이 틀린 답이 된다** — 이름을 바꾼 사람은 그 문서를 안 본다.
        val doc = Repo.read("docs/orchestration.md")
        val table = doc.lines().dropWhile { !it.startsWith("## 2.") }.takeWhile { !it.startsWith("### ") }
            .filter { it.startsWith("|") }
            .filterNot { it.startsWith("| 자원 ") || it.startsWith("|---") }

        // ★**못 읽은 줄을 실패로 적는다**(§15.120 이 남긴 규율). 한 줄의 모양만 바꿔도 그 줄이 조용히
        // 빠지면 검사 범위가 줄고, 줄어든 것을 아무도 모른다.
        val row = Regex("""\| ([^|]+) \| ([^|]+) \| ([^|]+) \| ([^|]+) \|""")
        val unreadable = table.filterNot { row.matchEntire(it) != null }
        assertEquals(emptyList(), unreadable, "대장 줄을 못 읽었다 — 모양이 바뀌면 검사가 그 줄을 건너뛴다")

        val rows = table.mapNotNull { row.matchEntire(it) }
        assertTrue(rows.size >= 9, "대장이 " + rows.size + " 줄이다 — 자원을 지우면 그 자원의 무승인 경로가 안 보인다")

        // 관문 칸의 백틱 기호. `null` 처럼 기호가 아닌 것은 뺀다 — 거절의 값이지 관문의 이름이 아니다.
        // ★**점이 있으면 소속까지 본다.** 조각마다 «어딘가에 있다» 만 보면 멤버가 다른 클래스로 옮겨 가도
        //   초록이다 — 표가 틀린 주인을 댄 채로. 같은 파일에 주인과 멤버가 함께 선언돼야 통과한다.
        //   못 보는 자리: 한 파일에 클래스가 둘이면 멤버가 그중 어느 쪽 것인지까지는 안 본다.
        fun declares(text: String, name: String) =
            Regex("(fun|interface|class|object|val) " + name + "[^A-Za-z0-9_]").containsMatchIn(text)
        val broken = rows.flatMap { hit ->
            gateSymbol.findAll(hit.groupValues[3]).map { it.groupValues[1] }.filterNot { it in notSymbols }
        }.filterNot { symbol ->
            val parts = symbol.split(".")
            if (parts.size == 1) {
                declares(mainSource, parts[0])
            } else {
                mainFiles.values.any { declares(it, parts[0]) && declares(it, parts[1]) }
            }
        }
        assertEquals(emptyList(), broken, "자원 소유 대장이 대는 관문이 출하 소스에 없거나 다른 주인에 있다")

        // ★**관문이 없는 행은 한계 대장의 id 를 대야 한다.** 이것이 이 표의 요점이다 — 빈 칸이 무승인
        // 경로인데, 빈 칸을 근거 없이 적으면 그 사실이 어디에도 추적되지 않고 «알고 안 한 것» 과
        // «잊은 것» 이 같은 모양이 된다.
        val known = LimitsLedger.allIds()
        val unledgered = rows.filter { it.groupValues[3].trim().let { cell -> cell == "—" || cell == "**없음**" } }
            .filterNot { hit -> known.any { it in hit.groupValues[4] } }
            .map { it.groupValues[1].trim() }
        assertEquals(emptyList(), unledgered, "관문이 없는 자원인데 한계 대장의 id 를 안 댄다")
    }

    @Test
    fun `한계 대장의 항목 수를 README 가 맞게 적는다`() {
        // ★**세어서 적은 것을 다시 센다.** 이 줄은 손으로 적힌 채 낡아 있었다(실측 2026-09-17: 문서는 37,
        // 실제는 40). 산문의 숫자는 아무도 다시 안 세므로 여기서 센다.
        // ★**행을 센다. id 를 세지 않는다** — 한 칸에 id 를 `·` 로 이어 적은 행이 있어서 둘이 다르다.
        val rows = LimitsLedger.rows(Repo.read("docs/limits.md"))
        // ★**열린 갈래를 이름으로 든다.** «의도적 제외가 아닌 전부» 로 세면 나중에 절이 하나 늘 때
        // 그 표가 조용히 미결로 셈된다. 소비자 대기는 폐지했다(§15.265) — 행이 없다는 것은 아래 시험이 댄다.
        val openBranches = setOf(LimitsLedger.INTERNAL, LimitsLedger.EXTERNAL)
        val open = rows.count { it.first in openBranches }
        assertEquals(open, claimed("""미결 한계 항목 (\S+)개"""), "미결 항목이 늘었는데 README 가 그대로다")

        // ★**같은 수를 두 자리에 적었고 한쪽만 셌더니 다른 쪽이 낡았다**(실측 2026-09-18: 한쪽은 46,
        // 다른 쪽은 37). 센 자리만 안 낡는다 — 그러니 갈래별 수까지 여기서 센다.
        assertEquals(open, claimed("""등록된 미결 항목은 \*\*(\S+?)개\*\*"""), "미결 합을 README 가 두 값으로 적는다")
        assertEquals(rows.count { it.first == LimitsLedger.INTERNAL }, claimed("""내부 (\S+?)개"""), "내부 미결 수가 대장과 다르다")
        // 수요 없음은 의도적 제외 안의 한 표다. 표시가 마지막 칸에 있는 행을 센다 — 표시를 지운 행은 비목표로 셈되어 수가 어긋난다.
        val noDemand = LimitsLedger.cells(Repo.read("docs/limits.md"))
            .count { (branch, _, reason) -> branch == LimitsLedger.SCOPED_OUT && LimitsLedger.NO_DEMAND in reason }
        assertEquals(noDemand, claimed("""수요 없음 (\S+?)개"""), "수요 없음 수가 레지스터와 다르다")
        assertEquals(rows.count { it.first == LimitsLedger.EXTERNAL }, claimed("""외부 (\S+?)개"""), "외부 미결 수가 대장과 다르다")
        assertEquals(rows.count { it.first == LimitsLedger.SCOPED_OUT }, claimed("""의도적 제외 (\S+?)개"""), "의도적 제외 수가 대장과 다르다")
    }

    @Test
    fun `한계 대장의 갈래가 해소의 주어와 맞는다`() {
        // ★★**이 대조가 없어서 드리프트했다.** 갈래는 「해소의 주어가 누구인가」로 갈리는데, 주어는
        //   해소 조건의 산문에만 있고 갈래는 절 머리에만 있다. 둘이 따로 적히니 **밖이 주어인 행이
        //   내부 미결에 앉아도 아무도 안 빨개졌다** — 실측으로 열셋이 그렇게 앉아 있었다(§15.194).
        //
        //   ★**낱말로 댄다**(ADR 23 과 같은 규율). 밖의 주어를 이름으로 부르는 행은 이것으로 잡힌다.
        //   낱말 없이 밖에 기대는 행은 못 잡으며 그 한계를 대장에 적었다 — 반을 막는 대조가 아무것도
        //   안 막는 것보다 낫고, 그 반이 지금까지 시끄러웠던 쪽이다.
        val ledger = LimitsLedger.cells(Repo.read("docs/limits.md"))

        // ★**하나도 못 읽으면 통과가 아니다.** 표의 모양이 바뀌면 아래가 전부 «위반 없음» 으로
        //   맞아떨어져 「깨끗하다」와 「못 읽었다」가 같은 모양이 된다.
        assertTrue(ledger.size > 50, "대장을 못 읽었다: ${ledger.size}")

        val outside = listOf("받는 쪽", "읽는 쪽", "소비자", "상류", "실물", "벤더", "현장")
        val misfiled = ledger.mapNotNull { (branch, id, condition) ->
            val named = when (branch) {
                // 이 저장소가 혼자 닫는다는 갈래인데 해소 조건이 밖의 주어를 부른다.
                LimitsLedger.INTERNAL -> outside.filter { it in condition }
                else -> emptyList()
            }
            if (named.isEmpty()) null else "$id(갈래 $branch) → ${named.joinToString("·")}"
        }
        assertEquals(emptyList(), misfiled, "해소 조건이 밖의 주어를 대는데 갈래는 그렇지 않다")
    }

    @Test
    fun `폐지한 소비자 대기 하위 범주에 행이 없다`() {
        // ★2026-11-04 소유자가 밖의 소비자는 생기지 않는다고 했다(§15.265). 소비자를 기다리는 행은 의도적 제외의
        //   수요 없음 표에 다시 여는 조건과 함께 둔다. 이 절에 행이 다시 서면 «언젠가 올 소비자» 가 미결로 셈된다.
        val ledger = LimitsLedger.cells(Repo.read("docs/limits.md"))
        assertTrue(ledger.size > 50, "레지스터를 못 읽었다: ${ledger.size}")
        val parked = ledger.filter { it.first == LimitsLedger.CONSUMER }.map { it.second }
        assertEquals(emptyList(), parked, "폐지한 소비자 대기에 행이 있다 — 지어서 닫거나 수요 없음 표로 옮겨라")
    }

    @Test
    fun `교체 지점의 인터페이스가 전부 실재한다`() {
        // `seams.md` 는 *"실물로 바꾸려면 어디를 고치나"* 에 답하는 문서다. 거기 적힌 타입이 사라지거나
        // 파일이 옮겨지면 **그 답이 틀린 답이 된다** — 이름을 바꾼 사람은 문서를 안 본다.
        val seams = Repo.read("docs/seams.md")
        val table = seams.lines().dropWhile { !it.startsWith("## 색인") }
            .filter { it.startsWith("|") }
            .filterNot { it.startsWith("| 자리 ") || it.startsWith("|---") }
        val row = Regex("""\| [^|]+ \| `([A-Za-z0-9_]+)` \| `([^`]+\.kt)` \|""")

        // ★**못 읽은 줄을 실패로 적는다.** 앞 판은 맞는 줄만 골라 세고 하한(12줄)만 봤는데, 주입이
        // 한 줄에서 백틱만 빼도 그 줄이 조용히 빠지고 하한은 그대로 넘는 것을 찾아냈다 — **검사 범위가 준다.**
        val unreadable = table.filterNot { row.matchEntire(it) != null }
        assertEquals(emptyList(), unreadable, "색인 줄을 못 읽었다 — 모양이 바뀌면 검사가 그 줄을 건너뛴다")

        // 색인과 본문이 **같은 집합**이어야 한다. 한쪽만 보면 반만 막힌다 — 주입이 그것을 보였다:
        // 본문에 안 나오는 줄은 통째로 지워도 안 걸렸다(§15.120). 하한은 내가 손으로 적은 숫자지만
        // 이 대조는 **문서가 스스로 대는 목록**이다.
        val rows = table.mapNotNull { row.matchEntire(it) }
        val indexed = rows.map { it.groupValues[1] }.toSet()
        val named = Regex("""\*\*인터페이스[^*]*\*\*: (.*)""").findAll(seams)
            .flatMap { Regex("`([A-Z][A-Za-z0-9]*[a-z][A-Za-z0-9]*)").findAll(it.groupValues[1]) }
            .map { it.groupValues[1] }.toSet()
        assertEquals(emptySet<String>(), named - indexed, "본문이 이름 지은 인터페이스가 색인에 없다")
        assertEquals(emptySet<String>(), indexed - named, "색인에만 있고 본문이 설명 안 하는 자리가 있다")

        val broken = rows.mapNotNull { hit ->
            val (type, path) = hit.destructured
            val file = Repo.path(path)
            when {
                !Files.isRegularFile(file) -> "$type: 파일이 없다 — $path"
                !Regex("(fun )?interface " + type + "[^A-Za-z0-9_]").containsMatchIn(Files.readString(file)) ->
                    "$type: $path 에 그 인터페이스가 없다"
                else -> null
            }
        }
        assertEquals(emptyList(), broken, "교체 지점 색인이 코드와 다르다")
    }

    @Test
    fun `계약이 적은 담보마다 그것을 지키는 시험이 실재한다`() {
        // **시험이 없는 담보는 담보가 아니라 희망이다.** `contract.md` 는 담보마다 그것을 지키는 시험을
        // 이름으로 댄다. 시험을 지우거나 이름을 바꾼 사람은 그 문서를 안 보므로 여기서 맞댄다.
        val doc = Repo.read("docs/contract.md")
        val cite = Regex("""`([a-z0-9-]+)` · `([A-Za-z0-9]+Test)` · `([^`]+)`""")

        // 담보 표의 **모든 줄**이 시험을 대야 한다. 증명 칸을 비워 두면 주장만 남는다.
        val rows = doc.lines().dropWhile { !it.startsWith("| 담보 |") }.drop(2).takeWhile { it.startsWith("|") }
        assertTrue(rows.size >= 15, "담보 표가 비었거나 모양이 바뀌었다: ${rows.size} 줄")
        assertEquals(emptyList(), rows.filterNot { cite.containsMatchIn(it) }, "담보를 적고 지키는 시험을 안 댔다")

        val broken = cite.findAll(doc).mapNotNull { hit ->
            val (module, cls, name) = hit.destructured
            val file = Files.walk(Repo.path("$module/src/test")).use { walk ->
                walk.filter { it.fileName.toString() == "$cls.kt" }.findFirst().orElse(null)
            }
            when {
                file == null -> "$module · $cls: 그런 시험 파일이 없다"
                !Repo.read(file).contains("fun `$name`(") -> "$module · $cls: `$name` 이 없다"
                else -> null
            }
        }.toList()
        assertEquals(emptyList(), broken, "계약 문서가 대는 시험이 코드에 없다")
    }

    @Test
    fun `ISA-95 대조가 코드와 같은 타입을 대고, 근거 등급이 계약으로 안 샌다`() {
        // 대조표의 값은 **표준의 타입 이름**이다. 코드 주석이 짚는 것과 다르면 둘 중 하나가 낡은 것이다.
        val names = Regex("""ISA95[A-Za-z]+DataType""")
        val inDoc = names.findAll(Repo.read("docs/isa95.md")).map { it.value }.toSet()
        val inCode = Repo.list("picasso/src/main/kotlin/dev/picasso/middleware", ".kt")
            .flatMap { file -> names.findAll(Repo.read(file)).map { it.value } }.toSet()
        assertTrue(inCode.size >= 4, "코드가 짚는 표준 타입이 사라졌다: $inCode")
        assertEquals(inCode, inDoc, "ISA-95 타입 이름이 문서와 코드에서 다르다")

        // ★**§4 의 논거 전체가 이 한 줄에 매달려 있다.** 근거 등급이 계약 면으로 나가면 로봇이 자기 완료의
        // 신뢰도를 자기가 선언하게 되고 — 확인하려는 대상에게 확인을 맡기는 것이 된다.
        val leaked = Repo.list("contracts/proto/picasso/v1", ".proto")
            .filter { Repo.read(it).contains("evidence", ignoreCase = true) }
            .map { it.fileName.toString() }
        assertEquals(emptyList(), leaked, "근거 등급이 계약 proto 로 샜다 — isa95.md §4 의 논거가 무너진다")
    }

    @Test
    fun `작업 허가 어휘가 계약 proto 로 안 샌다`() {
        // 작업 허가(PERMITTED · INHIBITED · UNKNOWN)는 이 저장소의 어휘이고 받는 쪽은 미들웨어의 발행 관문 하나다(ADR 52).
        // 계약에 실리면 로봇이 읽을 값이 생기고, 그 순간 셀 안전 상태의 정보 투영이 계약을 지나는 안전 신호처럼 읽힌다(ADR 32).
        // 소비자 없는 칸(ADR 9)이기도 하다. `UNKNOWN` 은 계약의 다른 열거에도 쓰일 수 있는 낱말이라 바늘에서 뺀다.
        val needle = Regex("""\bPERMITTED\b|\bINHIBITED\b|work_?permit""", RegexOption.IGNORE_CASE)
        val protos = Repo.list("contracts/proto/picasso/v1", ".proto")
        assertTrue(protos.size >= 3, "계약 proto 를 못 읽었다: ${protos.size}")
        val leaked = protos.filter { needle.containsMatchIn(Repo.read(it)) }.map { it.fileName.toString() }
        assertEquals(emptyList(), leaked, "작업 허가 어휘가 계약 proto 로 샜다 — ADR 52 의 «계약 밖 포트» 가 무너진다")
    }

    @Test
    fun `설정 표면 목록이 바꾸는 문을 빠짐없이 적는다`() {
        // `commissioning.md` §4 는 *"설정 표면 전부"* 라고 말한다. **전부가 아니면 그 문장이 거짓이고**,
        // 문서에 없는 문으로 현장 설정을 바꿀 수 있다는 뜻이 된다.
        val listed = Regex("""`(GET|POST|DELETE) (/[^`]+)`""").findAll(Repo.read("docs/commissioning.md"))
            .map { it.groupValues[1] + " " + it.groupValues[2] }.toSet()
        val mapped = Repo.list("registry/src/main/kotlin/dev/picasso/registry/web", ".kt")
            .flatMap { file ->
                Regex("""@(Get|Post|Delete)Mapping\("([^"]+)"\)""").findAll(Repo.read(file)).map { it.groupValues[1].uppercase() + " " + it.groupValues[2] }
            }.toSet()
        assertTrue(mapped.size >= 15, "표면을 못 읽었다: $mapped")

        assertEquals(emptySet<String>(), listed - mapped, "문서가 없는 표면을 적는다")

        // **바꾸는 문만 전수를 요구한다.** 열람(`/diag`)은 늘어도 설정 표면이 아니고, 그것까지 요구하면
        // 진단 하나 더할 때마다 이 표가 커져서 아무도 안 읽는다.
        val doors = mapped.filter { it.contains("/operations/") || it.contains("/ingest/") || it.endsWith("/requirements") }
        assertEquals(emptyList(), doors.filterNot { it in listed }.sorted(), "설정을 바꾸는 문이 문서에 없다")
    }

    @Test
    fun `문서가 가리키는 파일이 전부 실재한다`() {
        // 문서 사이의 링크가 이 저장소에서 유일하게 **자동으로 낡는 것**이다 — 파일 이름을 바꾸면 아무도 안 알려 준다.
        // 숫자를 세는 것과 같은 이유로 여기서 본다.
        //
        // ★**앞 판은 `Repo.list("docs", ".md")` 를 썼고 그것은 비재귀다**(`Files.list`). 그래서 `docs/adr/` ·
        // `docs/vendors/` · `docs/superpowers/specs/` 의 링크를 **아예 안 봤고**, ADR 39 가 이름이 바뀐 파일을
        // 가리키는 채로 초록이었다(실측 2026-09-11). 이제 **주장의 자리 전부**를 훑는다 — 목록이
        // `ClaimSurface` 한 곳에서 나오므로 새 문서가 생겨도 저절로 들어온다.
        val docs = ClaimSurface.documents()
        val broken = docs.flatMap { doc ->
            Regex("""\]\(([^)#:]+\.md[^)#]*)\)""").findAll(Files.readString(doc)).map { doc to it.groupValues[1] }
        }.filterNot { (doc, link) -> Files.exists(doc.parent.resolve(link).normalize()) }
            .map { (doc, link) -> "${repo.relativize(doc)} → $link" }

        assertEquals(emptyList(), broken, "문서가 없는 파일을 가리킨다")
    }

    @Test
    fun `상태기계 표의 값이 코드와 같다`() {
        // ★§15.119 와 같은 자리다 — **산문이면 눈에 띌 것이 표의 한 칸에 묻힌다.**
        // 실측(2026-09-11): 실행 상태에 `ACCEPTED` 가 빠져 있었고, 태스크 상태 칸에 **실패 분류**인
        // `CONTROL_AUTHORITY_LOST` 가 섞여 있었다. 그 표의 요지가 *"둘은 다른 층이고 하나가 다른 하나를
        // 대신하지 않는다"* 인데 **표 자신이 셋째 어휘를 들여놓고 있었다.**
        val row = read("docs/architecture.md").lines().single { it.startsWith("| **값** |") }
        val cells = row.split("|").map { it.trim() }
        fun quoted(cell: String) = Regex("`([A-Z_]+)`").findAll(cell).map { it.groupValues[1] }.toList()

        val physical = Regex("""enum class PhysicalState \{([^;]+);""")
            .find(read("picasso/src/main/kotlin/dev/picasso/middleware/Model.kt"))
            ?.groupValues?.get(1)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: error("PhysicalState 를 못 읽었다")
        assertEquals(physical, quoted(cells[2]), "실행 상태 값이 PhysicalState 와 다르다")

        val task = Regex("""TASK_STATE_([A-Z_]+) = \d+""")
            .findAll(read("contracts/proto/picasso/v1/task.proto"))
            .map { it.groupValues[1] }
            .filterNot { it == "UNSPECIFIED" }
            .toList()
        assertEquals(task.sorted(), quoted(cells[3]).sorted(), "태스크 상태 값이 계약의 TaskState 와 다르다")
    }

    @Test
    fun `모듈 의존 그림이 빌드와 같다`() {
        // ★**`architecture.md` 의 의존 그림 아홉 줄 중 게이트가 집행하던 것은 둘뿐이었다**(검사 5 의
        // `contracts` 의존 0, 검사 7 의 기종 문자열). 나머지 일곱은 산문이었고, 그래서 `README.md` 가
        // *"어댑터는 `contracts` 하나에만 의존한다"* 를 **`adapter-core` 가 생긴 뒤에도** 들고 있었다.
        //
        // **출하 의존만 센다.** 시험이 무엇을 끌어오는지는 다른 이야기다 — 하네스가 어댑터를 끌어오는 것이
        // 정상이고, 섞으면 이 표가 아무것도 못 막는다.
        val declared = Regex("""^\s*(\w+)\s*\(\s*(?:testFixtures\s*\()?\s*project\("[:]([a-z0-9-]+)"\)""")
        val actual = modules().associateWith { module ->
            Repo.read("$module/build.gradle.kts").lines()
                .filterNot { it.trimStart().startsWith("//") }
                .mapNotNull { declared.find(it) }
                .filterNot { it.groupValues[1].startsWith("test") }
                .map { it.groupValues[2] }
                .toSortedSet()
        }

        val table = read("docs/architecture.md").substringAfter("## 4b.").substringAfter("```text").substringBefore("```")
        val documented = table.lines().mapNotNull { line ->
            val (name, deps) = line.split("→").takeIf { it.size == 2 } ?: return@mapNotNull null
            name.trim() to deps.trim().takeIf { it != "(없음)" }.orEmpty()
                .split("·").map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()
        }.toMap()

        assertEquals(actual, documented, "의존이 바뀌었는데 architecture.md §4b 가 그대로다")
    }

    @Test
    fun `게이트가 계약에 빌드 의존 대신 태스크 의존을 건다`() {
        // `README.md` 와 `gate/README.md` 가 *"빌드 의존을 안 걸고 디스크립터 바이트를 읽는다 —
        // `:gate:test` 가 그 태스크에 매달려 있어 손으로 먼저 돌릴 명령이 없다"* 고 적는다.
        // 앞 절반은 §4b 의 표가 대고(게이트의 출하 의존은 `profile-model` 뿐), 뒤 절반이 이 줄이다.
        val build = read("gate/build.gradle.kts")
        assertTrue("dependsOn(\":contracts:generateProto\")" in build, "태스크 의존이 사라졌다 — 손으로 먼저 돌려야 하는 상태가 된다")
        assertTrue("inputs.files(descriptor)" in build, "디스크립터가 입력 선언에서 빠졌다 — 바뀌어도 UP-TO-DATE 로 넘어간다")
    }

    @Test
    fun `모듈 문이 적은 수가 코드와 같다`() {
        // ★**수 세기가 루트 `README.md` 와 `docs/` 만 보고 있었다.** 그래서 모듈 문의 숫자는 아무도 안
        // 셌고, 실측(2026-09-11) 셋이 낡아 있었다 — `contracts` 가 proto 를 다섯이라 적었고(여섯),
        // `registry` 가 마이그레이션을 V14 까지라 적었고(V15), 문 시험을 다섯이라 적었다(넷).
        assertEquals(
            Repo.list("contracts/proto/picasso/v1", ".proto").size,
            claimed("""의 (\S+) 파일이고""", read("contracts/README.md")),
            "proto 파일이 늘거나 줄었는데 contracts/README.md 가 그대로다",
        )

        val latestMigration = Repo.declaredFiles()
            .mapNotNull { Regex("""^V(\d+)__""").find(it.fileName.toString())?.groupValues?.get(1)?.toInt() }
            .maxOrNull() ?: error("마이그레이션을 못 읽었다")
        assertEquals(
            latestMigration,
            claimed("""V1~V(\S+)\)""", read("registry/README.md")),
            "마이그레이션이 늘었는데 registry/README.md 가 그대로다",
        )

        val doors = Repo.declaredFiles().count { it.fileName.toString().endsWith("EndpointTest.kt") }
        assertEquals(
            doors,
            claimed("""`web/\*EndpointTest` (\S+)이""", read("registry/README.md")),
            "문 시험이 늘거나 줄었는데 registry/README.md 가 그대로다",
        )
    }

    @Test
    fun `계약에 남아 있는 스킬 타입의 수를 계약 문서가 맞게 적는다`() {
        // ★**"관문을 못 지났다" 를 "계약에 없다" 로 읽으면 안 된다.** 스킬 카탈로그는 ADR 36 이
        // *양쪽 다 없던 어휘* 로 판정했지만 빼는 것이 major 개정이라 **계약에 그대로 있다.**
        // 실측(2026-09-11): 문서가 그 둘을 안 갈라 적어 계약에 카탈로그가 없는 것처럼 읽혔다.
        val actual = Regex("""skill_type_name\) = """)
            .findAll(read("contracts/proto/picasso/v1/skill_catalog.proto")).count()
        assertEquals(
            actual,
            claimed("""스킬 타입 (\S+)이 거기 있다""", read("docs/contract.md")),
            "카탈로그의 스킬 타입이 늘거나 줄었는데 contract.md 가 그대로다",
        )
    }

    @Test
    fun `레지스트리 문 시험의 수를 검증 근거 표가 맞게 적는다`() {
        // 실측(2026-09-11): 표가 다섯이라 적었는데 넷이었다. 다섯째 후보인 `IngestTokenTest` 는
        // 스스로 *"서버 없이 본다"* 고 적으므로 실물 등급의 증명이 아니다.
        val actual = Repo.declaredFiles().count { it.fileName.toString().endsWith("EndpointTest.kt") }
        assertEquals(
            actual,
            claimed("""`\*EndpointTest` (\S+)""", read("docs/verification.md")),
            "레지스트리 문 시험이 늘거나 줄었는데 verification.md 가 그대로다",
        )
    }

    @Test
    fun `설계 일지의 마지막 번호를 한계 대장이 맞게 적는다`() {
        // ★**이 시험이 없어서 물렸다.** `limits.md` 가 *"번호가 106 까지 갔고"* 라 적어 둔 채 124 까지 갔고,
        // 같은 문서가 §15.123·§15.124 를 대장 행으로 싣고 있었다 — **낡은 산문이 세 줄 아래의
        // *"산문이 낡는 것을 사람이 훑어 막지 않는다"* 를 바로 반증하고 있었다.**
        val last = Regex("""(?m)^(\d+)\. \*\*""").findAll(design)
            .map { it.groupValues[1].toInt() }
            .maxOrNull() ?: error("설계 일지의 번호를 못 읽었다")
        assertEquals(
            last,
            claimed("""번호가 (\S+) 까지 갔고""", read("docs/limits.md")),
            "일지가 늘었는데 limits.md 가 그대로다",
        )
    }

    @Test
    fun `그림이 인용한 벤더 심볼과 실패 분류가 코드에 실재한다`() {
        // ★**그림도 주장이다.** 산문에 걸어 둔 규율을 그림에도 건다 — `skill-mapping.svg` 는 벤더 심볼
        // 다섯을, `mission.svg` 는 능력마다의 실패 분류 이름을 **그대로 적는다.** 심볼이 바뀌거나 분류가
        // 개명되면 얼굴에 걸린 그림이 없는 것을 가리킨 채 남고, **그림은 아무도 grep 하지 않는다.**
        val mapping = read("docs/diagrams/skill-mapping.svg")
        val spotLink = read(
            "adapter-boston-dynamics-spot/src/main/kotlin/dev/picasso/adapter/spot/SpotLink.kt",
        )
        val symbols = Regex("""bosdyn\.api\.[A-Za-z_.]+""").findAll(mapping)
            .map { it.value }.distinct().toList()
        assertTrue(symbols.size >= 5, "그림에서 벤더 심볼을 못 읽었다 — 정규식이 낡았나: $symbols")
        assertEquals(
            emptyList(),
            symbols.filterNot { """VendorSurface("$it")""" in spotLink },
            "그림이 든 벤더 심볼이 SpotLink 의 @VendorSurface 인용에 없다",
        )

        // 이름은 밑줄 대문자다. 세 글자짜리(MES·WMS)가 안 걸리게 일곱 자 이상만 본다.
        val mission = read("docs/diagrams/mission.svg")
        // 계약(LogicalCapability.kt)과 구체 업무(CodeCapabilities.kt)를 함께 본다 — 의존 정비 S1(§15.307)이 두 파일로 갈랐다.
        val capability = read("picasso/src/main/kotlin/dev/picasso/middleware/LogicalCapability.kt") +
            read("picasso/src/main/kotlin/dev/picasso/middleware/CodeCapabilities.kt")
        val names = Regex("""[A-Z][A-Z_]{6,}""").findAll(mission).map { it.value }.distinct().toList()
        assertTrue(names.size >= 3, "그림에서 이름을 못 읽었다 — 정규식이 낡았나: $names")
        assertEquals(
            emptyList(),
            names.filterNot { it in capability },
            "그림이 든 이름이 LogicalCapability 에 없다",
        )
    }

    @Test
    fun `승인 창구를 부르는 이름이 하나이고 용어집에 있다`() {
        // ★**막는 물건을 두는 것과 그 물건을 지나가게 하는 것은 다른 일이다.** `glossary.md` 는 스스로를
        //   용어의 SSOT 라 적어 두었는데, 승인 표면의 어휘는 **그 문서를 한 번도 안 거치고** 코드 주석에서
        //   태어나 두 저장소로 퍼졌다. 밖에서 사람이 «이게 뭐냐» 고 물을 때까지 아무도 몰랐다(§15.185).
        //
        //   그래서 이 시험은 낱말이 빠져나간 **바로 그 길** 에 선다 — 기동 배너와 Gradle 태스크 설명과
        //   빌드 안내다. 용어집에 표제어를 하나 더 넣는 것만으로는 다음 낱말이 같은 길로 또 나간다.
        val glossary = read("docs/glossary.md")
        val term = Regex("""^### (\S+ \S+) \(ApprovalHost\)""", RegexOption.MULTILINE)
            .find(glossary)?.groupValues?.get(1)
            ?: error("용어집에 ApprovalHost 의 우리말 표제어가 없다 — 이름이 다시 용어집 밖으로 나갔다")

        val naming = mapOf(
            "CLAUDE.md" to read("CLAUDE.md"),
            "picasso/build.gradle.kts" to read("picasso/build.gradle.kts"),
            "docs/limits.md" to read("docs/limits.md"),
            "ScenarioHost.kt" to read("picasso/src/test/kotlin/dev/picasso/middleware/host/ScenarioHost.kt"),
        )
        assertEquals(
            emptySet<String>(),
            naming.filterValues { term !in it }.keys,
            "이 물건을 부르는 자리가 «$term» 을 안 쓴다 — 어휘가 둘이 되면 밖이 둘 다 받아 적는다",
        )

        // ★**옛 이름은 지우지 않고 용어집에만 둔다** — 철회를 표시로 남기는 ADR 45 와 같은 모양이다.
        //   이미 밖으로 나간 문서가 그 낱말을 쓰므로, 지우면 「다른 것을 가리킨다」와 「같은 것의 옛
        //   이름이다」가 접힌다. **다만 부르는 자리에는 남으면 안 된다** — 거기 남으면 이름이 둘이다.
        assertEquals(
            emptySet<String>(),
            naming.filterValues { STALE_HOST_TERM in it }.keys,
            "옛 이름 «$STALE_HOST_TERM» 이 부르는 자리에 남아 있다",
        )
        assertTrue(STALE_HOST_TERM in glossary, "용어집이 옛 이름을 안 든다 — 이미 나간 문서를 못 되짚는다")
    }

    @Test
    fun `용어집이 승인 표면에 대는 이름과 수가 코드와 같다`() {
        // 용어집은 이 저장소에서 **SSOT 라고 스스로 적어 둔 유일한 문서이면서 대조가 하나도 없던 문서다.**
        // 다른 문서는 전부 여기서 다시 세는데 이것만 안 셌다.
        val glossary = read("docs/glossary.md")
        val section = glossary.substringAfter("## 6. 승인과 자격", "")
        assertTrue(section.isNotBlank(), "승인 어휘 절이 없다")

        val enumBody = Regex("""enum class ApprovalRefusal[^{]*\{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL)
            .find(mainSource)?.groupValues?.get(1)
            ?: error("ApprovalRefusal 을 출하 소스에서 못 찾았다")
        val values = Regex("""^ {4}([A-Z][A-Z_]+)\s*[,;]""", RegexOption.MULTILINE)
            .findAll(enumBody).map { it.groupValues[1] }.toList()
        // 하한을 둔다 — 정규식이 낡아 0개를 읽으면 «수가 같다» 가 조용히 참이 된다.
        assertTrue(values.size >= 5, "열거값을 못 읽었다 — 정규식이 낡았나: $values")
        assertEquals(values.size, claimed("""가르는 (\S+?)값 열거""", glossary), "거절 사유의 수가 코드와 다르다")

        val named = Regex("""`([A-Z][A-Za-z0-9_]+)`""").findAll(section).map { it.groupValues[1] }.toSet()
        assertTrue(named.size >= 5, "절에서 기호를 못 읽었다: $named")
        assertEquals(
            emptySet<String>(),
            named.filterNot { it in values || it in mainSource || it in hostSource }.toSet(),
            "용어집이 이름 지은 기호가 코드에 없다",
        )
    }

    @Test
    fun `자동화 시험의 수를 대외 문서가 맞게 적는다`() {
        // ★**세는 시험이 없는 수는 낡는다.** `verification.md` 가 1,480여 에서 멈춘 채 삼백 넘게
        //   벌어져 있었다(§15.189). 이 파일의 머리가 «이 저장소가 반복해 물린 자리» 라 적은 그것이고,
        //   대외 문서가 틀린 수를 들고 나가던 자리다.
        //
        //   ★**소스의 `@Test` 를 센다 — 실행 결과가 아니다.** 결과(XML)로 세면 Docker 가 없는 기계에서
        //   두 모듈이 통째로 빠져 수가 줄고, 그러면 같은 문서가 기계마다 다른 값을 요구한다.
        //
        //   ★★**선언된 모듈만 훑는다.** 저장소를 통째로 걸으면 `.claude/worktrees/` 의 사본이 같이
        //   세어져 수가 거의 배로 뛴다(실측: 시험 소스 157 파일). 그 사본은 CI 에는 없으므로
        //   **기계마다 다른 답이 나오는 대조**가 된다 — 같은 구멍에 `mainSource` 도 물려 있었다.
        // ⛔`modules()` 는 `rootProject.name` 의 값까지 집어 `picasso` 를 **두 번** 돌려준다.
        //   집합으로 대는 쪽은 안 물리지만 더하는 쪽은 그 모듈이 통째로 두 번 세어진다(실측 2,081).
        val counted = modules().distinct().sumOf { module ->
            val dir = Repo.path("$module/src/test")
            if (!Files.isDirectory(dir)) 0
            else Files.walk(dir).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .map { TEST_ANNOTATION.findAll(Files.readString(it)).count() }
                    .toList()
                    .sum()
            }
        }
        // 하한을 둔다 — 정규식이 낡아 0 을 세면 «문서와 같다» 가 조용히 참이 될 수 있다.
        assertTrue(counted > 1_000, "시험을 못 셌다 - 정규식이나 경로가 낡았나: $counted")

        val claims = Regex("""([0-9][0-9,]{2,})\s*여?\s*개\s*(자동화\s*)?(테스트|시험)""")
        listOf("CLAUDE.md", "docs/verification.md").forEach { doc ->
            val text = read(doc)
            val hit = claims.find(text) ?: error("$doc 이 자동화 시험의 수를 안 적는다")
            assertEquals(
                counted,
                hit.groupValues[1].replace(",", "").toInt(),
                "$doc 의 시험 수가 실측과 다르다",
            )
        }
    }

    private companion object {
        /** 이 저장소의 산문은 작은 수를 낱말로 쓴다. 셈은 여기서 한 번만 한다. */
        val NUMERALS = mapOf(
            "하나" to 1, "둘" to 2, "셋" to 3, "넷" to 4, "다섯" to 5, "여섯" to 6, "일곱" to 7,
            "여덟" to 8, "아홉" to 9, "열" to 10, "열하나" to 11, "열둘" to 12, "열셋" to 13,
            "열넷" to 14, "열다섯" to 15, "열여섯" to 16, "열일곱" to 17, "열여덟" to 18,
            "열아홉" to 19, "스물" to 20,
        )

        /** 줄 머리의 `@Test`. 주석이나 문자열 안의 것은 안 센다. */
        val TEST_ANNOTATION = Regex("""^\s*@Test\b""", RegexOption.MULTILINE)

        /** 2026-09-22 까지 승인 창구를 부르던 이름. 용어집이 들고 있고 부르는 자리에는 없어야 한다. */
        const val STALE_HOST_TERM = "승인 입"

        /** 모듈이 아니지만 나무에 있는 것들 — 도구와 문서. */
        val IGNORED_DIRS = setOf("tools", "docs", "profile", "ci")
    }
}

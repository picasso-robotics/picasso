package dev.picasso.middleware

import dev.picasso.middleware.MiddlewareSeams.codeLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **출하 소스가 예산 안에 있다.** `Middleware.kt` 는 9/30 분할(1,532줄) 뒤 막는 장치가 없어 PR 62개 만에 4,338줄이
 * 되었다. 2차 분할(`docs/superpowers/plans/2026-11-12-middleware-seam-split-2.md`)과 함께 이 시험이 그 성장을
 * 빌드에서 보이게 한다. 세는 단위는 실코드 줄이다([MiddlewareSeams.codeLines]).
 *
 * ★**상한을 올리는 것은 이 시험을 고치는 일이다.** 상수를 바꾸는 PR 에 설계 일지 항목(무엇이 왜 커졌고, 접합부로
 * 옮기거나 경계를 다시 보는 대신 올리는 까닭)을 같이 두고 소유자의 승인을 받는다. 줄었을 때 상한을 따라 내리는
 * 것은 강제하지 않는다 — 다만 남은 여유를 다음 성장의 몫으로 여기지 않는다.
 *
 * 줄 수는 비상 제동이다. 이 클래스가 커지는 까닭(명령 생애가 실행의 널·불리언 조합으로 짜인 것, 상태의 주인이
 * `Middleware` 하나인 것, 경로 둘이 같은 생애를 따로 구현하는 것)은 재설계가 다룬다. 그 진행을 재는 것이 아래
 * 구조 예산의 `Execution` 가변 필드 수다. 재설계 R0(`docs/superpowers/plans/2026-11-12-middleware-r0-state-map.md`)이
 * 그 옆에 `Execution` 의 가변 컬렉션 수와 `ExecutionUnit` 의 가변 필드 수를 더했다 — `var` 만 세면 생애 상태가
 * 맵·집합이나 단위 쪽으로 옮겨 가도 숫자가 준다.
 */
class SourceSizeBudgetTest {

    @Test
    fun `Middleware 와 접합부 파일이 실코드 예산 안에 있다`() {
        val sources = MiddlewareSeams.sources()
        val over = mutableListOf<String>()
        val core = codeLines(sources.getValue("Middleware.kt"))
        if (core > MIDDLEWARE) over += "Middleware.kt $core > $MIDDLEWARE"
        var total = core
        MiddlewareSeams.files.forEach { file ->
            val text = sources[file]
            if (text == null) {
                over += "$file 이 없다"
                return@forEach
            }
            val lines = codeLines(text)
            total += lines
            if (lines > SEAM) over += "$file $lines > $SEAM"
        }
        if (total > TOTAL) over += "Middleware.kt 와 접합부 파일의 합 $total > $TOTAL"
        assertEquals(emptyList(), over, ADVICE)
    }

    @Test
    fun `그 밖의 출하 파일이 실코드 예산 안에 있다`() {
        val sources = MiddlewareSeams.sources()
        val others = sources - MiddlewareSeams.files - "Middleware.kt"
        assertTrue(others.size > 10, "그 밖의 출하 파일을 못 훑었다: ${others.size}")
        val over = others.mapNotNull { (file, text) -> codeLines(text).takeIf { it > OTHER }?.let { "$file $it > $OTHER" } }
        assertEquals(emptyList(), over, ADVICE)
    }

    @Test
    fun `실행의 가변 필드와 라운드 함수가 구조 예산 안에 있다`() {
        val lines = MiddlewareSeams.sources().getValue("Middleware.kt").split("\r\n", "\n")

        // 실행의 가변 필드. 취소·미확정 같은 생애가 여기에 널과 불리언으로 쌓였다(9/30 18개 → 11/12 32개 → R1 25개 → R2 20개).
        // 실행은 S2(§15.308)부터 최상위 클래스라 Execution.kt 를 읽는다. 전처럼 생성자의 var(order·robotId)도 센다.
        val execution = block(
            MiddlewareSeams.sources().getValue("Execution.kt").split("\r\n", "\n"),
            Regex("^class Execution\\b"),
            end = "}",
            file = "Execution.kt",
        )
        val fields = execution.count { Regex("^    (internal |private )?var ").containsMatchIn(it) }
        assertTrue(fields > 0, "실행의 가변 필드를 못 셌다 — 선언 모양이 바뀌면 이 예산이 조용히 빈다")
        assertTrue(fields <= EXECUTION_VARS, "실행의 가변 필드 $fields > $EXECUTION_VARS. $ADVICE")

        // 실행의 가변 컬렉션 — 생성기로 초기화한 val. var 만 세면 «한 번만 알림» 표시나 붙기 전 목록이 맵·집합으로 들어와도 안 보인다.
        val collections = execution.filter { COLLECTION.containsMatchIn(it) }
        assertTrue(collections.isNotEmpty(), "실행의 가변 컬렉션을 못 셌다 — 선언 모양이 바뀌면 이 예산이 조용히 빈다")
        assertTrue(
            collections.size <= EXECUTION_COLLECTIONS,
            "실행의 가변 컬렉션 ${collections.size} > $EXECUTION_COLLECTIONS: ${collections.map { COLLECTION.find(it)!!.groupValues[1] }}. $ADVICE",
        )

        // 라운드 함수. 라운드 안의 순서가 불변식이라 한 함수에 있다 — 그래서 다음 성장도 여기 온다.
        val round = block(lines, Regex("^    (internal |private )?fun pumpRound\\("))
        assertTrue(round.size <= PUMP_ROUND, "pumpRound ${round.size}줄 > $PUMP_ROUND. $ADVICE")
    }

    @Test
    fun `Middleware 가 연 멤버가 구조 예산 안에 있다`() {
        // 접합부 파일의 확장이 부르려고 `private` 에서 `internal` 로 연 멤버(2차 분할 37). 재설계는 생애 쪽 것부터 닫는다(R3, §15.303).
        // 접합부를 늘리는 쉬운 길이 멤버를 여는 것이라, 세지 않으면 닫는 쪽으로 가는지 아무도 모른다.
        val lines = MiddlewareSeams.sources().getValue("Middleware.kt").split("\r\n", "\n")
        val open = lines.mapNotNull { OPEN_MEMBER.find(it)?.groupValues?.get(1) }
        // S4c(§15.313)부터 0 이 정상값이다. 정규식이 낡아 조용히 0 이 되는 것은 같은 들여쓰기의 멤버 선언이 보이는지로 막는다.
        val members = lines.count { Regex("""^    (?:private |internal |override )?(?:val|var|fun) """).containsMatchIn(it) }
        assertTrue(members > 20, "Middleware 의 멤버 선언을 못 셌다($members) — 선언 모양이 바뀌면 이 예산이 조용히 빈다")
        assertTrue(open.size <= OPEN_MEMBERS, "Middleware 의 internal 멤버 ${open.size} > $OPEN_MEMBERS: $open. $ADVICE")
    }

    @Test
    fun `실행 단위의 가변 필드가 구조 예산 안에 있다`() {
        // 단위 하나의 가변 필드 — 생성자의 var 와 본문의 var. 실행의 생애가 단위 쪽 표시(조회 시각, 키 취소 시각 같은 것)로도 쌓였다.
        val lines = MiddlewareSeams.sources().getValue("Model.kt").split("\r\n", "\n")
        val unit = block(lines, Regex("^class ExecutionUnit\\("), end = "}", file = "Model.kt")
        val fields = unit.mapNotNull { Regex("^    (?:internal |private )?var (\\w+)").find(it)?.groupValues?.get(1) }
        assertTrue(fields.isNotEmpty(), "실행 단위의 가변 필드를 못 셌다 — 선언 모양이 바뀌면 이 예산이 조용히 빈다")
        assertTrue(fields.size <= UNIT_VARS, "실행 단위의 가변 필드 ${fields.size} > $UNIT_VARS: $fields. $ADVICE")
    }

    /** 선언 줄부터 [end] 와 같은 닫는 줄까지(기본은 네 칸 들여 쓴 `}`). 못 찾으면 실패한다. */
    private fun block(lines: List<String>, start: Regex, end: String = "    }", file: String = "Middleware.kt"): List<String> {
        val from = lines.indexOfFirst { start.containsMatchIn(it) }
        assertTrue(from >= 0, "$file 에서 '$start' 를 못 찾았다")
        val to = assertNotNull((from until lines.size).firstOrNull { lines[it] == end }, "'$start' 의 끝을 못 찾았다")
        return lines.subList(from, to + 1)
    }

    private companion object {
        // 2026-11-12 2차 분할 뒤 실측: Middleware.kt 1,036, 접합부 최대 241(UnitDrivers.kt), 합 2,650,
        // 그 밖의 파일 최대 291(Ports.kt), 실행의 가변 필드 32, pumpRound 179줄.
        // 2026-11-13 의존 정비 S4c 실측: Middleware.kt 696, 합 1,670(그대로), Middleware 의 internal 멤버 0(§15.313). 창구 절차도 좁은 환경의 확장이
        // 되어 internal 로 열 까닭이 없어졌다. 리비전을 받은 뒤의 알림은 호출 자리 셋에 한 줄씩 들였다.
        // 2026-11-13 의존 정비 S4b 실측: Middleware.kt 696, 합 1,670, Middleware 의 internal 멤버 9(§15.312). 기체 뷰·라운드 시간값·트레이스·설비 관측이
        // 좁은 환경 CommandEnv.kt 로 나가 세는 범위 밖으로 갔다(코드가 준 것이 아니다). robots·cell·fleet·issuing 은 private 로 닫았다.
        // 2026-11-13 의존 정비 S4a 실측: Middleware.kt 754, 합 1,717, Middleware 의 internal 멤버 20(§15.311). 생애·접합부가 쓰던 companion 상수의
        // 값을 MiddlewareCodes.kt 로(여러 줄 값이 한 줄이 됐다), markIncident·observeHold 를 실행·단위의 멤버로 옮겼다.
        // 2026-11-13 의존 정비 S3 실측: 실행의 가변 컬렉션 11. 단위 열이 생성자 인자에서 실행이 쥔 뒷받침(mutableUnits = units.toMutableList())이
        // 되어 이제 센다 — 전에도 실행이 가진 컬렉션이었으나 생성자로 받아 안 셌다. 상태가 는 것이 아니라 누락을 고친 것이다(§15.309, 소유자 승인).
        // 2026-11-13 의존 정비 S2 실측: Middleware.kt 765, 합 1,728. Execution 이 최상위 Execution.kt 로 나가 세는 범위 밖으로 갔다(§15.308) —
        // 코드가 준 것이 아니다. 실행의 가변 필드 13·컬렉션 10 은 Execution.kt 에서 그대로 센다.
        // 2026-11-12 R0 표시 다섯의 fix(§15.306) 뒤 실측: Middleware.kt 817, 합 1,786(고침이 17줄을 더했다 — 상한을 올린다),
        // pumpRound 143줄(끊김 추적을 RobotSync.kt 의 trackLink 로).
        // 2026-11-12 재설계 R5 실측: Middleware.kt 843(기체 동기화를 RobotSync.kt 로, abort 를 취소 생애로, 순수 함수 일곱을 UnitFacts.kt 로,
        // §15.305), 합 1,769, Middleware 의 internal 멤버 22.
        // 2026-11-12 재설계 R4 실측: Middleware.kt 와 접합부 파일의 합 1,829(경로 갈래를 RouteDriver 로, §15.304).
        // 2026-11-12 재설계 R3 실측: 실행의 가변 필드 13(취소 네 축과 송신 기록 셋을 CommandLifecycle 로, §15.303), Middleware.kt 와
        // 접합부 파일의 합 1,840(명령 생애 넷이 접합부 목록을 떠났다), Middleware 의 internal 멤버 30.
        // 2026-11-12 재설계 R2 실측: 실행의 가변 필드 20, 가변 컬렉션 10(«한 번만 알림» 아홉을 Noticed 하나로, §15.302).
        // 2026-11-12 재설계 R1 실측: 실행의 가변 필드 25(취소 필드 열하나를 네 축으로, §15.301).
        // 2026-11-12 재설계 R0 실측: 실행의 가변 컬렉션 14, 실행 단위의 가변 필드 36(생성자 30, 본문 6).
        const val MIDDLEWARE = 696
        const val SEAM = 500
        const val TOTAL = 1_670
        const val OTHER = 600
        const val EXECUTION_VARS = 13
        const val PUMP_ROUND = 143
        const val EXECUTION_COLLECTIONS = 11
        const val UNIT_VARS = 36
        const val OPEN_MEMBERS = 0

        /** 네 칸 들여 쓴(클래스 본문의) `internal` 선언. 무리 1 은 선언 줄이다. */
        val OPEN_MEMBER = Regex("^    (internal (?:fun|val|var|(?:data |sealed |enum )?(?:class|object|interface)) .*)$")

        /** 네 칸 들여 쓴(최상위 `Execution` 본문의) `val` 가운데 가변 컬렉션 생성기로 초기화한 것. 게터(`get() =`)는 계산 값이라 안 센다. 무리 1 은 이름이다. */
        val COLLECTION = Regex("^    (?:internal |private )?val (\\w+)\\b(?![^=]*\\bget\\(\\))[^=]*= (?:mutableMapOf|mutableSetOf|linkedSetOf|mutableListOf|linkedMapOf|\\w+\\.toMutable(?:List|Set|Map))\\b")

        const val ADVICE = "새 하위 절차는 접합부 파일로 옮기거나 경계를 다시 본다. 제 상태가 있는 새 규약은 RemedyDesk 처럼 " +
            "제 클래스로 둔다. 상한을 올리려면 SourceSizeBudgetTest 의 상수와 설계 일지 항목을 같은 PR 에 두고 소유자 승인을 받는다 " +
            "(docs/superpowers/plans/2026-11-12-middleware-seam-split-2.md)"
    }
}

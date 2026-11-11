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
 * 구조 예산의 `Execution` 가변 필드 수다.
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

        // 실행의 가변 필드. 취소·미확정 같은 생애가 여기에 널과 불리언으로 쌓였다(9/30 18개 → 11/12 32개).
        val execution = block(lines, Regex("^    inner class Execution\\b"))
        val fields = execution.count { Regex("^        (internal |private )?var ").containsMatchIn(it) }
        assertTrue(fields > 0, "실행의 가변 필드를 못 셌다 — 선언 모양이 바뀌면 이 예산이 조용히 빈다")
        assertTrue(fields <= EXECUTION_VARS, "실행의 가변 필드 $fields > $EXECUTION_VARS. $ADVICE")

        // 라운드 함수. 라운드 안의 순서가 불변식이라 한 함수에 있다 — 그래서 다음 성장도 여기 온다.
        val round = block(lines, Regex("^    (internal |private )?fun pumpRound\\("))
        assertTrue(round.size <= PUMP_ROUND, "pumpRound ${round.size}줄 > $PUMP_ROUND. $ADVICE")
    }

    /** 네 칸 들여 쓴 선언 줄부터 같은 들여쓰기의 닫는 괄호까지. 못 찾으면 실패한다. */
    private fun block(lines: List<String>, start: Regex): List<String> {
        val from = lines.indexOfFirst { start.containsMatchIn(it) }
        assertTrue(from >= 0, "Middleware.kt 에서 '$start' 를 못 찾았다")
        val to = assertNotNull((from until lines.size).firstOrNull { lines[it] == "    }" }, "'$start' 의 끝을 못 찾았다")
        return lines.subList(from, to + 1)
    }

    private companion object {
        // 2026-11-12 2차 분할 뒤 실측: Middleware.kt 1,036, 접합부 최대 241(UnitDrivers.kt), 합 2,650,
        // 그 밖의 파일 최대 291(Ports.kt), 실행의 가변 필드 32, pumpRound 179줄.
        const val MIDDLEWARE = 1_080
        const val SEAM = 500
        const val TOTAL = 2_780
        const val OTHER = 600
        const val EXECUTION_VARS = 32
        const val PUMP_ROUND = 180

        const val ADVICE = "새 하위 절차는 접합부 파일로 옮기거나 경계를 다시 본다. 제 상태가 있는 새 규약은 RemedyDesk 처럼 " +
            "제 클래스로 둔다. 상한을 올리려면 SourceSizeBudgetTest 의 상수와 설계 일지 항목을 같은 PR 에 두고 소유자 승인을 받는다 " +
            "(docs/superpowers/plans/2026-11-12-middleware-seam-split-2.md)"
    }
}

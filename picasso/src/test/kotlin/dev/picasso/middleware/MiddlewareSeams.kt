package dev.picasso.middleware

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Middleware` 의 접합부 파일과 거기 둔 확장 함수의 목록, 그리고 출하 소스를 세는 규칙. [SeamPlacementTest] 와
 * [SourceSizeBudgetTest] 가 함께 쓴다 — 목록을 두 벌로 두면 어느 날 한쪽만 는다.
 *
 * 2차 분할(`docs/superpowers/plans/2026-11-12-middleware-seam-split-2.md`)이 상태 없는 하위 절차를
 * `fun Middleware.name(` 확장으로 이 파일들에 옮겼다. 상태와 라운드 순서, 공개 창구는 `Middleware.kt` 에 남는다.
 * 순서는 원래 `Middleware.kt` 의 순서다. 명령 생애 넷(`Lifecycle*.kt`)은 재설계 R3(§15.303)이 실행마다의 생애 객체
 * `CommandLifecycle` 의 확장으로 바꿔 이 목록을 떠났다 — 그 파일에 `fun Middleware.` 가 다시 서면 [SeamPlacementTest] 가 빨개진다.
 * 실행을 읽지 않는 포트 보조만 `CommandPorts.kt` 에 남았다.
 */
internal object MiddlewareSeams {

    val homes: Map<String, List<String>> = linkedMapOf(
        "Resume.kt" to listOf("staleVersionRefusal", "recordRefusal", "restore", "reopenHold", "rebuildFrom", "attemptOf"),
        "Revision.kt" to listOf("revise", "sendRevisionUpdate", "settleUpdate", "issuable"),
        "ClockSkew.kt" to listOf("readNow", "measureClock", "clockSuspicionOf"),
        "UnitDrivers.kt" to listOf(
            "noteProgress", "pumpRobotUnit", "loseStream", "settleLate", "pumpSignalUnit", "describe", "pumpFleetUnit", "holdOf",
        ),
        "EvidenceBinding.kt" to listOf(
            "judgeEffectMismatch", "beginVerify", "checkEvidence", "failWithEvidenceCheck", "reworkAsNewAttempt", "skippedPair",
            "resolveSkipped", "skippedReworkRefusal",
        ),
        "CommandPorts.kt" to listOf(
            "transportOrderOf", "issueStart", "issueDispatch", "handleFor", "lookupOf", "unansweredAttach", "findNow", "statusNow",
        ),
    )

    val files: Set<String> get() = homes.keys

    /** 이 모듈의 출하 소스, 파일 이름에서 본문으로. 이 패키지 하나뿐이라 이름이 겹치지 않는다. */
    fun sources(): Map<String, String> {
        val found = Files.walk(Path.of("src", "main")).use { paths ->
            paths.asSequence()
                .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                .map { it.fileName.toString() to Files.readString(it) }
                .toList()
        }
        assertTrue(found.size > 10, "이 모듈의 출하 소스를 못 훑었다: ${found.size}")
        assertEquals(found.size, found.map { it.first }.toSet().size, "출하 소스의 파일 이름이 겹친다 — 이름으로 대조할 수 없다")
        return found.toMap()
    }

    /**
     * **실코드 줄** — 앞 공백을 뗀 줄이 비었거나 `/**`·`*`·`//` 로 시작하면 안 센다(`*/` 는 `*` 에 든다).
     * 줄은 `\r\n` 과 `\n` 둘 다로 가르고, 마지막 개행 뒤의 빈 조각은 빈 줄이라 안 센다. KDoc 은 세지 않는다 —
     * 세면 예산이 문서를 깎는 쪽으로 민다. 못 보는 자리: 여러 줄 문자열 안의 `*`·`//` 줄과 `*` 로 시작하는
     * 펼침 인자는 주석으로 센다. 한 줄에 여러 문장을 몰아 쓰면 줄어든다 — 통상의 포맷을 전제한다.
     */
    fun codeLines(text: String): Int = text.split("\r\n", "\n").count { line ->
        val s = line.trim()
        s.isNotEmpty() && !s.startsWith("/**") && !s.startsWith("*") && !s.startsWith("//")
    }
}

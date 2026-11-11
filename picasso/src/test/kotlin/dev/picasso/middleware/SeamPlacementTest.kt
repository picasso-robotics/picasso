package dev.picasso.middleware

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **떼어 낸 선언이 제 파일에만 있다.** 자주 바뀌는 접합부 가운데 배정 관문의 판정과 제안·사건 두 장부는
 * `Middleware.kt` 에서 떼어 각자의 파일로 옮긴다(`docs/superpowers/plans/2026-09-26-middleware-seam-split.md`).
 * `Middleware.kt` 에는 핵심 상태기계와 공개 창구, 그리고 접합부를 핵심에 잇는 조율(재할당의 판정 포함)이
 * 남는다 — 그 조율도 빨리 바뀌는 코드다.
 * 여러 절이 나눠 쓰는 정준 투영은 `Canonical.kt` 에 한 벌만 둔다(§15.115) — 같은 이름의 멤버를 클래스 안에
 * 다시 두면 그 클래스의 호출은 조용히 그 멤버를 부른다.
 *
 * 다시 한 파일로 모아도 다른 시험은 전부 초록이다. 그래서 갈라 둔 것이 조용히 사라진다(§15.193 이 내보내기
 * 세 벌에서 본 모양). 여기서는 출하 소스를 훑어 **선언이 어느 파일에 있는지**를 댄다. 이 파일은 `src/test`
 * 에 있어 제 바늘에 안 걸린다.
 */
class SeamPlacementTest {

    @Test
    fun `떼어 낸 선언이 제 파일에만 있다`() {
        val homes = mapOf(
            // 정준 투영 — 나눠 쓰는 쪽들의 바깥에 한 벌
            "fun canonicalClassOf(" to "Canonical.kt",
            "fun faultDetailOf(" to "Canonical.kt",
            "fun residualHoldOf(" to "Canonical.kt",
            // 배정 관문 — 피어 시스템 접합부
            "fun inconsistent(" to "AdmissionGate.kt",
            "fun chainRefusal(" to "AdmissionGate.kt",
            "fun occupancyViolation(" to "AdmissionGate.kt",
            "fun destinationViolation(" to "AdmissionGate.kt",
            "fun unownedFloor(" to "AdmissionGate.kt",
            "fun workspaceViolation(" to "AdmissionGate.kt",
            // 장부가 함수로 받아 쓰는 관문의 조회 — 같은 이름의 fun 이 끼면 그쪽이 이긴다
            "fun liveHold(" to "AdmissionGate.kt",
            // 제안과 승인 장부 — 운영자 접합부
            "var proposalsMade" to "RemedyDesk.kt",
            "val remedyLog" to "RemedyDesk.kt",
            "fun scopeRefusal(" to "RemedyDesk.kt",
            // 사건 번들 장부 — 운영자 접합부
            "var incidentSeq" to "IncidentLog.kt",
            "fun sealIncidents(" to "IncidentLog.kt",
        )
        val sources = Files.walk(Path.of("src", "main")).use { paths ->
            paths.asSequence()
                .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                .map { it.fileName.toString() to Files.readString(it) }
                .toList()
        }
        assertTrue(sources.size > 10, "이 모듈의 출하 소스를 못 훑었다: ${sources.size}")
        homes.forEach { (needle, home) ->
            val found = sources.filter { needle in it.second }.map { it.first }.toSortedSet()
            assertEquals(sortedSetOf(home), found, "'$needle' 이 제 파일($home) 밖에 있거나 사라졌다")
        }
    }

    /**
     * **옮긴 하위 절차는 제 접합부 파일에만 있고, `Middleware` 에 같은 이름의 멤버가 없다**(2차 분할).
     *
     * 확장 함수는 같은 서명의 멤버에 조용히 진다. 누가 `Middleware.kt` 에 `fun resolveDoubt(` 를 다시 두면 호출이 전부
     * 그 멤버로 가고 컴파일러는 경고만 낸다(이 빌드는 경고를 실패로 안 본다). 그래서 확장 쪽의 자리만 대면 그 사고에
     * 초록이다 — 음성 바늘이 그 자리를 막는다. 접합부 파일의 확장 목록은 [MiddlewareSeams.homes] 와 같아야 하고,
     * `fun Middleware.` 는 그 파일들 밖에 없어야 한다. 새 확장을 더하면 목록에도 더한다.
     */
    @Test
    fun `옮긴 하위 절차는 제 파일에만 있고 Middleware 에 같은 이름의 멤버가 없다`() {
        val sources = MiddlewareSeams.sources()
        val extension = Regex("""^(?:internal|private) fun Middleware\.([A-Za-z0-9_]+)\(""", RegexOption.MULTILINE)
        val declared = sources.mapValues { (_, text) -> extension.findAll(text).map { it.groupValues[1] }.toList() }
            .filterValues { it.isNotEmpty() }
        assertEquals(MiddlewareSeams.files, declared.keys, "fun Middleware. 확장이 접합부 목록 밖의 파일에 있거나 목록의 파일에 없다")
        MiddlewareSeams.homes.forEach { (file, names) ->
            assertEquals(names, declared[file], "$file 의 확장 목록이 MiddlewareSeams.homes 와 다르다")
        }
        val core = sources.getValue("Middleware.kt")
        val shadowed = MiddlewareSeams.homes.values.flatten().filter { Regex("""\bfun (?:[A-Za-z]+\.)?$it\(""").containsMatchIn(core) }
        assertEquals(emptyList(), shadowed, "Middleware.kt 에 옮긴 절차와 같은 이름의 멤버가 있다 — 확장이 조용히 가려진다")
    }
}

package dev.picasso.host

import dev.picasso.middleware.Evidence
import dev.picasso.middleware.JobResponse
import dev.picasso.middleware.LedgerExport
import dev.picasso.middleware.PhysicalState
import dev.picasso.middleware.RemedyOutcome
import dev.picasso.middleware.RemedySearchRecord
import dev.picasso.middleware.ResultExport
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 한 벌을 담는 절차 — **읽는 쪽이 이것으로 승인을 거른다.**
 *
 * 여기서 지키는 것은 넷이다: 구동 식별자가 한 벌 내내 같을 것, 대장이 안 늘면 다시 안 쓸 것,
 * 안내가 대는 수가 대장의 줄 수와 같을 것, 그리고 **이 절차가 저장소에 한 벌뿐일 것.**
 */
class BundleWriterTest {

    private fun record(n: Int) = RemedySearchRecord(
        searchId = "search-$n",
        robotId = "hum-02",
        jobOrderId = "PATROL-APPROVES",
        at = Instant.parse("2026-09-06T02:00:00Z").plusSeconds(n.toLong()),
        wallClockAt = Instant.parse("2026-09-22T07:00:00Z").plusSeconds(n.toLong()),
        outcome = RemedyOutcome.Withheld,
    )

    private fun manifest(dir: Path) = Files.readString(dir.resolve(LedgerExport.MANIFEST))

    private fun runId(body: String) = Regex(""""runId":"([^"]+)"""").find(body)?.groupValues?.get(1)

    @Test
    fun `구동 식별자가 한 벌 내내 같다`() {
        // ★★읽는 쪽은 「같은 구동의 같은 줄에는 한 번만 부른다」로 승인을 거른다. 식별자가 내보낼
        //   때마다 바뀌면 **그 거름망이 통째로 죽는다** — 같은 한 벌을 다시 훑을 때마다 또 부른다.
        //   앞 판이 그랬고 30분 돌린 판에서 식별자가 `-10930` 까지 갔다.
        val dir = createTempDirectory("bundle")
        val writer = BundleWriter(dir, "mw-1")

        assertTrue(writer.snapshot(emptyList(), listOf(record(1)), NOW), "첫 한 벌을 안 썼다")
        val first = runId(manifest(dir))

        assertTrue(writer.snapshot(emptyList(), listOf(record(1), record(2)), NOW), "대장이 늘었는데 안 썼다")
        val second = runId(manifest(dir))

        assertEquals(first, second, "같은 한 벌인데 구동 식별자가 바뀌었다")
        assertTrue(first != null && first.isNotBlank(), "구동 식별자가 비었다: $first")
    }

    @Test
    fun `쓰개가 둘이면 구동 식별자도 둘이다`() {
        // ★**한 벌마다 쓰개가 하나여야 한다.** 재실행 쌍(`run-1`·`run-2`)은 해시가 같고 식별자만
        //   다른 것이 존재 이유인데, 두 벌을 한 쓰개로 쓰면 식별자까지 같아져 그 성질이 사라진다.
        //   자리가 생성 인자라 그 길이 애초에 없다는 것을 여기서 댄다.
        val first = createTempDirectory("bundle")
        val second = createTempDirectory("bundle")

        BundleWriter(first, "mw-1").snapshot(emptyList(), listOf(record(1)), NOW)
        BundleWriter(second, "mw-1").snapshot(emptyList(), listOf(record(1)), NOW)

        assertTrue(runId(manifest(first)) != runId(manifest(second)), "두 벌의 구동 식별자가 같다")
    }

    @Test
    fun `대장이 안 늘면 다시 안 쓴다`() {
        // 안 늘었는데 다시 쓰면 읽는 쪽이 세 파일을 한 벌로 집을 때 파일마다 다른 순간을 볼 수 있다.
        val dir = createTempDirectory("bundle")
        val writer = BundleWriter(dir, "mw-1")
        val searches = listOf(record(1))

        assertTrue(writer.snapshot(emptyList(), searches, NOW), "첫 한 벌을 안 썼다")
        val before = manifest(dir)

        assertFalse(writer.snapshot(emptyList(), searches, NOW.plusSeconds(60)), "안 늘었는데 다시 썼다")
        assertEquals(before, manifest(dir), "안 썼다면서 안내가 바뀌었다")

        // ★**늘면 다시 쓴다.** 건너뛰기가 «영영 안 쓴다» 가 되면 창구가 도는데 한 벌이 멈춘다.
        assertTrue(writer.snapshot(emptyList(), searches + record(2), NOW), "늘었는데 안 썼다")
    }

    @Test
    fun `안내가 대는 수가 대장의 줄 수와 같다`() {
        // 안내가 마지막에 나타나는 것이 «다 나왔다» 는 신호다. 그 수가 줄 수와 다르면 읽는 쪽은
        // 파편을 한 벌로 읽는다.
        val dir = createTempDirectory("bundle")
        val searches = listOf(record(1), record(2), record(3))

        BundleWriter(dir, "mw-1").snapshot(emptyList(), searches, NOW)

        val lines = Files.readString(dir.resolve(LedgerExport.REMEDY_SEARCHES)).trim().lines()
        assertEquals(searches.size, lines.size, "탐색 대장의 줄 수가 다르다")
        assertTrue(""""remedySearches":${searches.size}""" in manifest(dir), "안내가 댄 수가 다르다: ${manifest(dir)}")
        assertEquals("", Files.readString(dir.resolve(LedgerExport.INCIDENTS)), "빈 사건 대장이 안 비었다")
    }

    @Test
    fun `한 벌을 담는 절차가 저장소에 하나뿐이다`() {
        // ★★**이 시험이 없어서 갈렸다.** 같은 절차가 세 자리에 있었고 창구 쪽만 고침을 얻었는데,
        //   셋이 각자 제 시험으로 초록이라 **갈림 자체는 아무도 안 빨개졌다**(§15.186). 합치기만 하면
        //   다음 사람이 급할 때 또 적어 같은 자리로 돌아온다 — 그러니 **자리의 수를 댄다.**
        //
        //   ★바늘을 조각으로 잇는다. 통째로 적으면 **이 시험이 저를 잡는다**(검사 11 과 같은 덫).
        //
        //   ★**바늘이 둘이다.** 원자적 이름 바꾸기 하나만 보면 그것을 안 쓰고 다시 적은 쓰개는
        //   안 보인다. 한 벌을 내려면 안내를 짓는 수밖에 없으므로 그 부름도 함께 센다 —
        //   `LedgerExportTest` 는 그 인코더 자체를 재는 자리라 목록에 든다.
        //
        //   ★**두 모듈을 함께 훑고 저장소 상대 경로로 댄다**(의존 정비 H1, §15.314). 쓰개는 이 모듈(`host-kit`)로 나왔지만 내보내기
        //   시험과 참조 실행기는 picasso 에 남았다 — 한쪽만 보면 다른 쪽에 사본이 서도 초록이고, 파일 이름만 보면 같은 이름의 사본을
        //   못 가른다.
        val declared = mapOf(
            "ATOMIC_" + "MOVE" to setOf("host-kit/src/main/kotlin/dev/picasso/host/BundleWriter.kt"),
            "LedgerExport." + "manifest(" to setOf(
                "host-kit/src/main/kotlin/dev/picasso/host/BundleWriter.kt",
                "picasso/src/test/kotlin/dev/picasso/middleware/LedgerExportTest.kt",
            ),
        )

        val root = Path.of("..").toAbsolutePath().normalize()
        val sources = listOf("host-kit", "picasso").flatMap { module ->
            Files.walk(root.resolve(module).resolve("src")).use { paths ->
                paths.asSequence()
                    .filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                    .map { root.relativize(it).toString().replace('\\', '/') to Files.readString(it) }
                    .toList()
            }
        }
        // ★**소스를 하나도 못 읽으면 통과가 아니다.** 훑는 자리가 틀리면 아래가 전부 빈 집합으로
        //   맞아떨어져 「한 벌뿐이다」와 「안 훑었다」가 같은 모양이 된다.
        assertTrue(sources.count { it.first.startsWith("picasso/") } > 20, "picasso 의 소스를 못 훑었다: ${sources.size}")
        assertTrue(sources.count { it.first.startsWith("host-kit/") } >= 5, "host-kit 의 소스를 못 훑었다: ${sources.size}")

        declared.forEach { (needle, allowed) ->
            // **양방향으로 댄다.** 「목록에 없는 자리가 생겼다」만 보면 목록이 낡아도 모르고,
            // 옛 이름 아래 새 쓰개가 조용히 들어온다.
            val found = sources.filter { needle in it.second }.map { it.first }.toSortedSet()
            assertEquals(allowed.toSortedSet(), found, "한 벌을 담는 자리가 늘었거나 사라졌다")
        }
    }

    @Test
    fun `결과 통보는 늘 때만 다시 쓰고 대장의 안내는 그것을 세지 않는다`() {
        // ★대장 한 벌의 안내가 이 파일을 세면 판이 다른 두 형식이 한 안내에 묶여, 결과 통보가 바뀔 때 대장의 판도 오른다.
        val dir = createTempDirectory("bundle")
        val writer = BundleWriter(dir, "mw-1")
        val response = JobResponse(
            jobResponseId = "resp-1", jobOrderId = "PATROL-APPROVES", executionId = "exec-1", version = 1,
            physicalState = PhysicalState.RUNNING, requiredEvidence = Evidence.E0, reachedEvidence = Evidence.E0,
            completedUnits = emptyList(), unverifiedUnits = emptyList(), incompleteUnits = emptyMap(),
            operatorRequired = false, residualHold = dev.picasso.contracts.v1.HoldState.getDefaultInstance(),
        )

        assertTrue(writer.carry(listOf(response), "mw-1"), "첫 통보를 안 썼다")
        assertFalse(writer.carry(listOf(response), "mw-1"), "통보가 안 늘었는데 다시 썼다")
        assertTrue(writer.carry(listOf(response, response.copy(jobResponseId = "resp-2")), "mw-1"), "통보가 늘었는데 안 썼다")

        val lines = Files.readString(dir.resolve(ResultExport.JOB_RESPONSES)).lines().filter { it.isNotEmpty() }
        assertEquals(2, lines.size)
        assertTrue(lines.all { "\"instanceId\":\"mw-1\"" in it }, lines.joinToString(" | "))

        writer.snapshot(emptyList(), listOf(record(1)), NOW)
        assertFalse("jobResponses" in manifest(dir), "대장의 안내가 결과 통보를 센다")
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-09-06T02:47:57Z")
    }
}

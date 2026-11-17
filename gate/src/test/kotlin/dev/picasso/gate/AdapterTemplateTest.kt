package dev.picasso.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **골격은 컴파일되지 않는다. 그래서 시험이 유일한 보증이다.**
 *
 * `tools/adapter-template/` 을 모듈로 만들면 벤더도 프로파일도 없는 모듈이 빌드에 서고 게이트
 * 검사 여덟이 전부 그것을 대상으로 삼는다. 그래서 `.kt.txt` 로 두는데, 그러면 `RobotAdapter` 가
 * 바뀌어도 골격은 **조용히 낡는다** — 이 저장소가 반복해 물린 바로 그 모양이다.
 *
 * 여기서 막는 것 셋:
 *
 * | | 막는 것 |
 * |---|---|
 * | 면의 자리를 빠짐없이 든다 | `RobotAdapter` 에 자리가 늘었는데 골격이 그대로인 것 |
 * | 기본값 있는 것을 갈라 적는다 | *"안 채워도 된다"* 는 목록이 낡는 것 |
 * | 파일 목록이 실제 어댑터와 같다 | 관례가 바뀌었는데 문서의 목록이 그대로인 것 |
 *
 * ★**못 보는 것.** 타입이 맞는지도, 자리를 **어떻게** 채웠는지도 안 본다. 이름이 있다는 것만
 * 본다 — 벤더 매니페스트 대조와 같은 한계다(ADR 23 의 가족).
 */
class AdapterTemplateTest {

    private val adapterSource by lazy {
        Repo.read("adapter-core/src/main/kotlin/dev/picasso/adapter/core/RobotAdapter.kt")
    }
    private val skeleton by lazy { Repo.read("tools/adapter-template/Adapter.kt.txt") }
    private val guide by lazy { Repo.read("tools/adapter-template/README.md") }

    /** `interface RobotAdapter { … }` 안의 최상위 멤버를 기본 구현 유무로 가른다. */
    private fun members(): Pair<List<String>, List<String>> {
        val body = adapterSource.substringAfter("interface RobotAdapter {").substringBeforeLast("\n}")
        val must = mutableListOf<String>()
        val defaulted = mutableListOf<String>()
        Regex("""(?m)^ {4}(?:val|fun)\s+(\w+)([^\n]*)""").findAll(body).forEach { m ->
            (if (m.groupValues[2].contains("=")) defaulted else must) += m.groupValues[1]
        }
        check(must.isNotEmpty() && defaulted.isNotEmpty()) { "면을 못 읽었다 — 정규식이 낡았나" }
        return must to defaulted
    }

    @Test
    fun `골격이 반드시 채우는 자리를 빠짐없이 든다`() {
        // ★**면이 늘면 여기가 먼저 빨개진다.** 그것이 이 시험의 전부이고, 골격을 믿을 수 있게
        // 하는 유일한 장치다 — 컴파일러가 안 봐 주는 자리를 대신 본다.
        val (must, _) = members()
        val absent = must.filterNot { skeleton.contains("$it(") || skeleton.contains("val $it") }
        assertEquals(emptyList(), absent, "RobotAdapter 의 자리가 골격에 없다 — 면이 늘었나")
    }

    @Test
    fun `골격이 기본값 있는 것을 안 채워도 된다고 적는다`() {
        // ★**이 목록이 낡으면 다음 사람이 억지로 채운다.** 기본값은 *없다* 이지 *된다* 가 아니라서
        // 그대로 두는 것이 정직한 선택인 경우가 많은데, 목록이 틀리면 그 선택지가 안 보인다.
        val (_, defaulted) = members()
        val absent = defaulted.filterNot { skeleton.contains(it) }
        assertEquals(emptyList(), absent, "기본 구현이 있는 자리가 골격에 안 적혀 있다")

        // 반대 방향 — 반드시 채우는 것을 "안 채워도 된다" 칸에 적어 두면 안 된다.
        val (must, _) = members()
        val heading = "안 채워도 되는 ${NUMERAL[defaulted.size]}"
        assertTrue(skeleton.contains(heading), "골격의 선택 칸 머리가 선택 자리 수(${defaulted.size})와 다르다")
        val optionalSection = skeleton.substringAfter(heading)
        val misplaced = must.filter { optionalSection.contains("$it ·") || optionalSection.contains("· $it") }
        assertEquals(emptyList(), misplaced, "반드시 채우는 자리가 선택 목록에 들어 있다")
    }

    @Test
    fun `골격이 세는 수가 면과 같다`() {
        val (must, defaulted) = members()
        assertTrue(
            guide.contains("반드시 채우는 ${NUMERAL[must.size]}"),
            "README 가 적은 필수 자리 수가 ${must.size} 와 다르다",
        )
        assertTrue(
            guide.contains("안 채워도 되는 ${NUMERAL[defaulted.size]}"),
            "README 가 적은 선택 자리 수가 ${defaulted.size} 와 다르다",
        )
    }

    @Test
    fun `골격이 적은 파일 목록을 어댑터 넷이 전부 갖는다`() {
        // ★**목록이 발명이 아니라 실측이게 한다.** README 의 *파일 일곱* 을 읽어 실제 어댑터
        // 모듈마다 대므로, 관례가 바뀌면 목록이 먼저 빨개진다. 손으로 적은 목록은 낡는다.
        val listed = Regex("""(?m)^- `([^`]+)`$""")
            .findAll(guide.substringAfter("## 파일 일곱").substringBefore("\n## "))
            .map { it.groupValues[1] }
            .toList()
        assertEquals(7, listed.size, "README 의 파일 목록을 못 읽었다: $listed")

        val modules = Repo.declaredFiles()
            .map { ClaimSurface.relative(it) }
            .filter { it.startsWith("adapter-") }
            .map { it.substringBefore("/") }
            .distinct()
            .filterNot { it == "adapter-core" || it == "adapter-host" }
            .sorted()
        assertEquals(4, modules.size, "어댑터 모듈 수가 넷이 아니다: $modules")

        val files = Repo.declaredFiles().map { ClaimSurface.relative(it) }
        modules.forEach { module ->
            val missing = listed.filterNot { wanted ->
                val tail = wanted.removePrefix("…")
                files.any { it.startsWith("$module/") && it.endsWith(tail) }
            }
            assertEquals(emptyList(), missing, "$module 이 골격이 적은 파일을 안 갖는다")
        }
    }

    private companion object {
        val NUMERAL = mapOf(
            1 to "하나", 2 to "둘", 3 to "셋", 4 to "넷", 5 to "다섯", 6 to "여섯", 7 to "일곱",
            8 to "여덟", 9 to "아홉", 10 to "열",
        )
    }
}

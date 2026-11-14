package dev.picasso.gate

/** `docs/limits.md` 의 표를 읽는다. **열림 판정의 근거가 실재하는지** 보려는 것뿐이다. */
object LimitsLedger {

    /** 갈래의 번호. **해소의 주어가 누구인가로 갈린다** — 문서의 절 번호와 한 벌이다. */
    const val SCOPED_OUT = 1
    const val INTERNAL = 2

    /** 소비자 대기. **2026-11-04 폐지**(§15.265) — 절 번호를 지키려고 남긴다. 행이 없어야 한다. */
    const val CONSUMER = 3
    const val EXTERNAL = 4

    /**
     * 폐지한 수요 없음 표의 표시(§15.265 에서 두고 §15.321 에서 폐지). 행의 마지막 칸에 **없어야 한다** —
     * 소비자의 요청 유무는 제외·보류 근거가 아니다. 날짜를 떼고 낱말만 보아 꼴을 바꾼 표시도 잡는다.
     */
    const val NO_DEMAND = "수요 없음"

    private val BRANCH = Regex("""^## (\d)\.""")

    /**
     * 표의 줄마다 (갈래, 첫 칸, 마지막 칸). **마지막 칸이 해소 필요 조건이다.**
     * 원문 순서를 지키므로 **중복도 보인다.**
     *
     * ★**칸을 뽑는 규칙은 여기 한 벌뿐이다.** `rows` 와 `parse` 와 유일성 시험이 나눠 쓴다 — 두 벌로 두면
     * 어느 날 한쪽만 는다(§15.115). 펜스 규칙도 `ClaimSurface` 의 것을 그대로 쓴다.
     */
    fun cells(content: String): List<Triple<Int, String, String>> {
        val lines = content.replace("\r\n", "\n").lines()
        val outside = ClaimSurface.outsideFence(lines)
        val out = mutableListOf<Triple<Int, String, String>>()
        var branch = 0
        lines.forEachIndexed { i, line ->
            if (!outside[i]) return@forEachIndexed
            val head = BRANCH.find(line.trimStart())
            if (head != null) {
                branch = head.groupValues[1].toInt()
                return@forEachIndexed
            }
            if (branch == 0 || !line.trimStart().startsWith("|")) return@forEachIndexed
            val parts = line.split("|")
            val cell = parts.getOrNull(1)?.replace("*", "")?.trim() ?: return@forEachIndexed
            if (cell.isEmpty() || cell.trim(':', '-').isEmpty() || cell == "출처" || cell == "갈래") return@forEachIndexed
            // `| a | b | c |` 는 조각이 다섯이라 마지막 칸이 끝에서 둘째다.
            out += Triple(branch, cell, parts.getOrNull(parts.size - 2)?.trim().orEmpty())
        }
        return out
    }

    /** 첫 칸만 갈래와 함께. */
    fun rows(content: String): List<Pair<Int, String>> = cells(content).map { it.first to it.second }

    fun parse(content: String): Map<Int, Set<String>> {
        val out = mutableMapOf<Int, MutableSet<String>>()
        for ((branch, cell) in rows(content)) {
            val bucket = out.getOrPut(branch) { mutableSetOf() }
            bucket += cell
            // ★한 칸에 id 를 `·` 로 이어 적은 행이 셋 있다. 조각도 댈 수 있어야 한다.
            if ("·" in cell) cell.split("·").map(String::trim).filterTo(bucket, String::isNotEmpty)
        }
        return out
    }

    fun of(): Map<Int, Set<String>> = parse(Repo.read("docs/limits.md"))

    fun allIds(): Set<String> = of().values.flatten().toSet()
}

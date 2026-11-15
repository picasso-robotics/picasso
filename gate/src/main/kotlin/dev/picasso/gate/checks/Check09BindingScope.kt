package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Finding
import dev.picasso.gate.GateCheck
import dev.picasso.gate.Resource
import dev.picasso.gate.Severity
import dev.picasso.gate.input.GateInput
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/**
 * 검사 9 — 자리 결속은 어댑터 경계 밖으로 안 나간다(ADR 34 · 35, §15.155).
 *
 * 자리 이름이 **어느 기종의 무엇에 묶이는지**는 어댑터 경계의 지식이다. 공통 계층이 그 타입을 알면
 * 기종 비인지가 한 겹 무너진다 — 검사 7이 기종 *좌표*를 막는다면 이 검사는 기종 *결속*을 막는다.
 * 둘을 한 검사로 합치지 않는 이유는 탐색어의 출처가 다르기 때문이다: 검사 7은 프로파일에서 얻고
 * 여기는 결속 타입이 선언된 파일에서 얻는다.
 *
 * **탐색어를 손으로 적지 않는다.** `adapter-core` 의 결속 파일이 선언한 최상위 이름 전부가 탐색어이며,
 * 그 파일에 타입을 하나 더하면 금지도 저절로 는다. 리터럴로 박으면 다음 타입이 조용히 검사 밖에 놓인다 —
 * 검사 7이 프로파일에서 좌표를 유도하는 것과 같은 이유다.
 *
 * **`src/main` 만 본다.** 시험이 결속을 이름으로 집는 것은 정당하다. 막으려는 것은 출하되는 코드다.
 *
 * 판정이 문자열 검사인 것은 검사 7과 같은 의도다(ADR 23). 남는 한계도 같다 — 별칭이나 전부 임포트로
 * 우회하면 못 본다. 그 자리는 모듈 의존 자체를 걸지 않는 빌드 구조가 메운다: 공통 모듈 중 어느 것도
 * `adapter-core` 에 의존하지 않으므로 우회하려면 빌드 스크립트를 먼저 고쳐야 하고 그것은 diff 에 보인다.
 */
class Check09BindingScope : GateCheck {

    override val id = "9"
    override val name = "결속 타입의 어댑터 경계"

    override val requires = setOf(Resource.REPO)

    override fun run(input: GateInput): CheckResult {
        val root = input.repoRoot!!
        val findings = mutableListOf<Finding>()

        val declaration = root.resolve(DECLARATION)
        if (!Files.isRegularFile(declaration)) {
            // 선언 파일이 사라졌는데 통과하면 검사가 조용히 무력해진 것이다.
            return CheckResult.Failed(
                id,
                listOf(Finding(id, Severity.ERROR, "$DECLARATION 을 찾을 수 없다 — 탐색어를 얻을 데가 없으면 통과가 아니다", DECLARATION)),
            )
        }

        val needles = declared(Files.readAllLines(declaration))
        if (needles.isEmpty()) {
            return CheckResult.Failed(
                id,
                listOf(Finding(id, Severity.ERROR, "$DECLARATION 이 최상위 타입을 하나도 선언하지 않는다 — 막을 것이 없으면 통과가 아니다", DECLARATION)),
            )
        }

        MODULES.forEach { module ->
            val dir = root.resolve("$module/$MAIN")
            if (!Files.isDirectory(dir)) {
                findings += Finding(id, Severity.ERROR, "$module/$MAIN 을 찾을 수 없다 — 검사할 대상이 없으면 통과가 아니다", "$module/$MAIN")
                return@forEach
            }
            findings += scan(root, dir, needles)
        }

        return if (findings.isEmpty()) CheckResult.Passed(id) else CheckResult.Failed(id, findings)
    }

    /** 들여쓰기 없는 선언만. 중첩 타입은 밖에서 그 이름만으로 못 부르므로 탐색어가 못 된다. */
    private fun declared(lines: List<String>): List<String> =
        lines.mapNotNull { DECLARES.find(it)?.groupValues?.get(1) }.distinct()

    private fun scan(root: Path, dir: Path, needles: List<String>): List<Finding> =
        Files.walk(dir).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.extension in SOURCE_EXTENSIONS }
                .sorted()
                .flatMap { file ->
                    val label = runCatching { root.relativize(file).toString() }.getOrElse { file.toString() }
                    Files.readAllLines(file).asSequence()
                        .withIndex()
                        .flatMap { (index, line) ->
                            needles.filter { WORD(it).containsMatchIn(line) }.map { needle ->
                                Finding(
                                    id, Severity.ERROR,
                                    "공통 계층이 자리 결속 타입을 참조한다: '$needle' " +
                                        "— 결속은 어댑터 경계의 지식이다(ADR 34 · 35)",
                                    "$label:${index + 1}",
                                )
                            }
                        }
                }
                .toList()
        }

    companion object {
        /** 탐색어의 출처. 여기 선언된 최상위 이름 전부가 금지어가 된다. */
        const val DECLARATION = "adapter-core/src/main/kotlin/dev/picasso/adapter/core/SiteBinding.kt"

        /**
         * 결속을 알아서는 안 되는 모듈.
         *
         * **어댑터 모듈과 `adapter-core`·`adapter-host` 는 여기 없다** — 결속을 읽는 것이 그들의 일이다.
         * `registry` 와 `profile-model` 이 들어온 것은 검사 7이 그 둘을 안 보기 때문이다: 검사 7의
         * 목록에 없다는 이유로 결속까지 새면 같은 구멍이 두 번째로 열린다. `fleet-mimic` 은 자리를 글자로만 다루는 플릿 더블이라
         * 결속을 몰라야 해서 출하 모듈이 되면서 들어왔다(§15.326).
         */
        val MODULES = listOf(
            "client", "mimic", "harness", "picasso", "capability", "uplink", "registry", "profile-model", "contracts", "fleet-mimic",
        )

        const val MAIN = "src/main"

        /** 최상위 선언. `data class` · `sealed interface` · `enum class` 까지 한 벌로 본다. */
        private val DECLARES =
            Regex("""^(?:data |sealed |value |enum )?(?:class|interface|object) ([A-Z][A-Za-z0-9_]*)""")

        /** 낱말 경계로 본다 — `SiteBindingSomethingElse` 가 `SiteBinding` 으로 걸리지 않게. */
        private fun WORD(needle: String) = Regex("""\b""" + Regex.escape(needle) + """\b""")

        /** 검사 7과 같다 — 텍스트로 읽히는 것을 전부 본다. */
        val SOURCE_EXTENSIONS = setOf("kt", "java", "json", "yaml", "yml", "properties", "xml", "txt", "proto")
    }
}

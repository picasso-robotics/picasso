package dev.picasso.gate.checks

import dev.picasso.gate.CheckResult
import dev.picasso.gate.Finding
import dev.picasso.gate.GateCheck
import dev.picasso.gate.ProfileDirectories
import dev.picasso.gate.Resource
import dev.picasso.gate.Severity
import dev.picasso.gate.input.GateInput
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.streams.asSequence

/**
 * 검사 7 — 기종 분기 금지(설계 §11.2).
 *
 * A-1의 "같은 클라이언트 코드"와 §10.1의 "기종별 클래스가 없다"를 지킨다.
 * 출하되는 코드가 기종 좌표를 알면 세 번째 기종은 프로파일 한 장이 아니게 된다.
 *
 * **`src/main`만 본다.** §11.2는 "소스"라고만 했지만 시험까지 훑으면 지금 당장
 * 실패한다 — `A1Test`가 두 기종을 이름으로 집는 것은 필요하고 정당하다.
 * 막으려는 것은 **출하되는 코드**가 기종을 아는 것이다.
 *
 * **주석을 걷어내지 않는다.** 부분적으로 걷어내려 하면 `"http://humanoid-a"`
 * 같은 문자열이 `//` 이후로 잘려 **위반이 숨는다.** 원문 그대로 훑고, 기종
 * 이름을 주석에 적는 것도 실패로 둔다 — 이 세 모듈은 기종 이름을 알 이유가
 * 없으므로 거짓 실패의 여지가 작다.
 *
 * **찾는 문자열은 프로파일에서 파생한다.** 리터럴로 박으면 세 번째 기종이
 * 들어올 때 검사가 그것을 모른다 — 완료 기준 11이 겨냥하는 바로 그 자리다.
 *
 * 판정이 문자열 검사인 것은 의도다(§11.2) — AST 분석은 우회 방법이 많고 유지
 * 비용이 크다. 남는 한계는 §15의 6번이며 **능력 기반 분기는 못 본다.**
 * 그 자리는 `harness`의 요청 동일성 시험이 메운다.
 */
class Check07ModelBranching : GateCheck {

    override val id = "7"
    override val name = "기종 분기 금지"

    /**
     * 문서가 없으면 **찾을 문자열이 없다.** 그 상태로 통과하면 검사가
     * 조용히 무력해지므로 건너뛴다 — §11.1의 규칙 그대로다.
     */
    override val requires = setOf(Resource.REPO, Resource.PROFILE_DOCUMENT)

    override fun run(input: GateInput): CheckResult {
        val root = input.repoRoot!!

        // **실제 기종만 본다.** 픽스처(`profile/fixtures/`)의 좌표는
        // `fixture`·`minimal` 같은 흔한 낱말이라, 출하 소스의 `minimalHeaders`
        // 같은 이름 하나가 기종 분기와 무관한 이유로 게이트를 빨갛게 만든다.
        // 이 검사가 지키려는 것은 §7.4의 기종이고 그것은 MODELS에 있다.
        val models = input.profiles.filter { normalize(it.path).contains("${ProfileDirectories.MODELS}/") }

        val needles = models
            .flatMap { listOf(it.vendor, it.model) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        if (needles.isEmpty()) {
            // 문서는 있는데 찾을 좌표가 없다. 통과시키면 아무것도 안 찾는다.
            return CheckResult.Failed(
                id,
                listOf(
                    Finding(
                        id, Severity.ERROR,
                        "${ProfileDirectories.MODELS}/ 에서 기종 좌표를 얻지 못했다 " +
                            "— 찾을 문자열이 없으면 이 검사는 무의미하다",
                    ),
                ),
            )
        }

        // **약한 탐색어를 조용히 쓰지 않는다.** 짧거나 구분 기호가 없는
        // 좌표는 평범한 식별자와 겹쳐 거짓 실패를 부르고, 그러면 사람이
        // 검사를 끄게 된다. 프로파일 쪽을 고치라고 말한다.
        needles.filter { it.length < MIN_NEEDLE || it.none { c -> c == '-' || c.isDigit() } }
            .forEach {
                return CheckResult.Failed(
                    id,
                    listOf(
                        Finding(
                            id, Severity.ERROR,
                            "기종 좌표 '$it' 이 문자열 검사로 쓰기에 약하다 " +
                                "— 평범한 식별자와 겹쳐 거짓 실패를 낸다. " +
                                "$MIN_NEEDLE 자 이상이고 '-'나 숫자를 포함하게 하라",
                        ),
                    ),
                )
            }

        val findings = mutableListOf<Finding>()

        MODULES.forEach { module ->
            val dir = root.resolve(module).resolve(MAIN)
            if (!Files.isDirectory(dir)) {
                // 모듈이 사라졌는데 통과하면 검사가 조용히 무력해진 것이다.
                findings += Finding(
                    id, Severity.ERROR,
                    "$module/$MAIN 을 찾을 수 없다 — 검사할 대상이 없으면 통과가 아니다",
                    "$module/$MAIN",
                )
                return@forEach
            }
            findings += scan(root, dir, needles)
        }

        return if (findings.isEmpty()) CheckResult.Passed(id) else CheckResult.Failed(id, findings)
    }

    private fun scan(root: Path, dir: Path, needles: List<String>): List<Finding> =
        Files.walk(dir).use { stream ->
            stream.asSequence()
                .filter { it.isRegularFile() && it.extension in SOURCE_EXTENSIONS }
                .sorted()
                .flatMap { file ->
                    val label = runCatching { root.relativize(file).toString() }
                        .getOrElse { file.toString() }
                    Files.readAllLines(file).asSequence()
                        .withIndex()
                        .flatMap { (index, line) ->
                            needles.filter { it in line }.map { needle ->
                                Finding(
                                    id, Severity.ERROR,
                                    "프로덕션 소스에 기종 좌표가 있다: '$needle' " +
                                        "— A-1의 '같은 클라이언트 코드'가 깨진다(설계 §11.2의 7번)",
                                    "$label:${index + 1}",
                                )
                            }
                        }
                }
                .toList()
        }

    /** Windows에서 `ProfileDocument.path`가 역슬래시를 담는다. */
    private fun normalize(path: String) = path.replace('\\', '/')

    /**
     * **시험이 이 목록을 읽는다.** 공개하지 않으면 시험이 모듈 이름을 두 번째로
     * 적게 되고, 목록이 느는 날 한쪽만 고쳐진다 — 이 저장소가 막으려는 그것이다
     * (실제로 `adapter-core`를 더할 때 시험 여섯이 그 이유로 깨졌다).
     */
    companion object {
        /**
         * §11.2가 지목한 셋에 `adapter-core`를 더한 넷.
         *
         * **어댑터 모듈 자체는 여기 없다** — 기종을 아는 것이 그것의 일이다
         * (ADR 33). 들어온 것은 어댑터들이 **공유하는** 모듈이며, ADR 33이
         * 대가로 적어 둔 *"그 공통 모듈은 다시 기종을 몰라야 하고 그때 검사
         * 7번의 목록에 더해야 한다"* 가 두 번째 어댑터에서 실현된 자리다.
         *
         * `contracts`·`profile-model`은 기종을 알 수 없다.
         *
         * `picasso`(미들웨어의 가운데)는 ADR 38 로 들어왔다 — 벤더 코드에서 정준
         * 분류로 옮기는 일은 어댑터의 것이고, 그 위의 실행 구조에 `if robot == …`
         * 가 생기면 정준 모델이 아니다.
         */
        val MODULES = listOf("client", "mimic", "harness", "adapter-core", "picasso", "capability", "adapter-host", "uplink", "registry", "profile-model", "host-kit")

        const val MAIN = "src/main"

        /**
         * **소스만이 아니다.** 기종→거동 표를 `src/main/resources`의 yaml이나
         * properties에 두는 것도 분기다. 텍스트로 읽히는 것을 전부 본다.
         */
        val SOURCE_EXTENSIONS = setOf(
            "kt", "java", "json", "yaml", "yml", "properties", "xml", "txt", "proto",
        )

        /** 이보다 짧은 좌표는 문자열 검사로 쓸 수 없다. */
        const val MIN_NEEDLE = 8
    }
}

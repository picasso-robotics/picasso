package dev.picasso.host

import dev.picasso.middleware.DeclaredAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 자격 선언 목록을 파일에서 읽는다 — ADR 43 이 「배치의 결정」이라 한 자리의 첫 구현체.
 *
 * **못 읽으면 던진다.** 빈 목록으로 접으면 오타 난 선언 파일이 «아무것도 선언 안 했다» 와 같은 모양이
 * 되고, 그 배치는 자동 승인이 전부 거절되는 것을 정상으로 읽는다.
 */
class FileEntitlementsTest {

    private fun file(body: String): Path {
        val dir = createTempDirectory("entitlements")
        val path = dir.resolve("entitlements.json")
        Files.writeString(path, body)
        return path
    }

    @Test
    fun `인계본에 실린 선언 파일이 실제로 읽힌다`() {
        // ★받는 쪽이 이 파일로 무엇이 승인될지를 예측한다. 여기서 안 읽히면 그 예측이 서지 않는다.
        val declared = FileEntitlements.read(SHIPPED)
        val one = assertNotNull(declared.declaredFor("narrator-1"), "선언이 없다: ${declared.approvers()}")

        assertEquals(setOf("hum-02", "hum-04"), one.robotIds)
        assertEquals(listOf(DeclaredAction("pick_place", mapOf("destination" to "DROP-01"))), one.actions)
        assertEquals(Instant.parse("2099-01-01T00:00:00Z"), one.expiresAt)
        assertNull(one.revocation, "안 철회한 선언에 철회 기록이 나왔다")
        assertNull(declared.declaredFor("아무나"), "선언 안 한 승인자에게 자격이 나왔다")
    }

    @Test
    fun `인계본이 선언 쪽 네 갈래를 실물로 세운다`() {
        // ★**칸을 만들어도 데모가 안 채우면 빈 칸이다**(§15.177). 받는 쪽이 이 파일 하나로
        //   미선언·만료·철회를 실제로 불러 볼 수 있어야 그 갈래가 값으로 보인다.
        val declared = FileEntitlements.read(SHIPPED)

        assertNull(declared.declaredFor("narrator-2"), "미선언 자리가 사라졌다 — NOT_DECLARED 를 못 본다")

        val expired = assertNotNull(declared.declaredFor("narrator-3"), "만료 자리가 없다")
        assertTrue(expired.expiresAt.isBefore(Instant.now()), "만료 자리의 기간이 안 지났다: ${expired.expiresAt}")
        assertNull(expired.revocation, "만료 자리가 철회까지 들었다 — 두 갈래가 섞인다")

        val revoked = assertNotNull(declared.declaredFor("narrator-4"), "철회 자리가 없다")
        val mark = assertNotNull(revoked.revocation, "철회 자리에 철회 기록이 없다")
        assertTrue(mark.by.isNotBlank() && mark.reason.isNotBlank(), "누가·왜가 비었다: $mark")
        // ★**철회는 지우는 것이 아니라 표시하는 것이다**(ADR 45). 지웠으면 여기서 널이 나오고,
        //   그러면 받는 쪽은 그것을 「원래 없었다」로 읽는다.
        assertTrue(revoked.expiresAt.isAfter(Instant.now()), "철회 자리가 만료까지 지났다 — 갈래가 섞인다")
    }

    @Test
    fun `반쯤 적힌 철회는 빈 값이 아니라 예외다`() {
        // 「언제·누가·왜」 중 빠진 것을 빈 문자열로 받으면 읽는 쪽이 그것을 답으로 읽는다.
        //
        // ★**칸마다 따로 뺀다.** 앞 판은 `reason` 하나만 빼 보고 초록이었는데, 그것은 나머지 둘이
        //   느슨해져도 안 빨개진다는 뜻이다 — 주입으로 드러났다(§15.184). 한 칸을 대표로 삼으면
        //   그 시험이 재는 것은 그 칸뿐이다.
        val full = mapOf(
            "at" to """"2026-09-19T04:12:00Z"""",
            "by" to """"cell-lead"""",
            "reason" to """"연속 실패로 회수"""",
        )
        full.keys.forEach { dropped ->
            val revocation = full.filterKeys { it != dropped }.entries
                .joinToString(",", "{", "}") { """"${it.key}":${it.value}""" }
            val half = """{"entitlements":[{"approverId":"a","robotIds":["r"],""" +
                """"expiresAt":"2099-01-01T00:00:00Z","actions":[],"revocation":$revocation}]}"""
            assertFailsWith<IllegalArgumentException>("«$dropped» 이 빠졌는데 파일이 섰다") {
                FileEntitlements.read(file(half))
            }
        }

        val notObject = """{"entitlements":[{"approverId":"a","robotIds":["r"],""" +
            """"expiresAt":"2099-01-01T00:00:00Z","actions":[],"revocation":"철회함"}]}"""
        assertFailsWith<IllegalArgumentException> { FileEntitlements.read(file(notObject)) }
    }

    @Test
    fun `빈 목록은 정상이고 아무에게도 자격을 안 준다`() {
        // 빈 것은 오류가 아니다 — «아무것도 안 덮는다» 는 유효한 선언이고 사람은 여전히 누른다.
        val declared = FileEntitlements.read(file("""{"entitlements":[]}"""))
        assertEquals(emptySet(), declared.approvers())
    }

    @Test
    fun `못 읽는 선언은 빈 목록이 아니라 예외다`() {
        // ★★조용히 비면 그 배치는 «선언이 안 읽혔다» 와 «아무것도 선언 안 했다» 를 구별할 방법이 없다.
        assertFailsWith<IllegalArgumentException> { FileEntitlements.read(file("{")) }
        assertFailsWith<IllegalArgumentException> { FileEntitlements.read(file("""{"목록":[]}""")) }
        assertFailsWith<IllegalArgumentException> { FileEntitlements.read(file("""{"entitlements":{}}""")) }
        assertFailsWith<IllegalArgumentException> {
            FileEntitlements.read(file("""{"entitlements":[{"approverId":"a","robotIds":[],"actions":[]}]}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            FileEntitlements.read(Path.of("없는", "파일.json"))
        }
    }

    @Test
    fun `같은 승인자를 두 번 선언하면 파일이 안 선다`() {
        // 어느 쪽이 유효한지 파일이 답하지 않는다. 하나를 고르는 규칙을 만들면 그 규칙이 선언보다 세진다.
        val twice = """{"entitlements":[$ONE,$ONE]}"""
        assertFailsWith<IllegalArgumentException> { FileEntitlements.read(file(twice)) }
    }

    private companion object {
        val SHIPPED: Path = Path.of("..", "handoff", "narrator", "entitlements.json").normalize()

        const val ONE = """{"approverId":"narrator-1","robotIds":["hum-02"],""" +
            """"expiresAt":"2099-01-01T00:00:00Z","actions":[{"skillType":"pick_place","parameters":{}}]}"""
    }
}

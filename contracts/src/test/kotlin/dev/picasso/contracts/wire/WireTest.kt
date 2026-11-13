package dev.picasso.contracts.wire

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.DescriptorProtos
import dev.picasso.contracts.v1.MessageHeader
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ContractIdentityTest {

    @Test
    fun `계약 신원을 클래스패스에서 읽는다`() {
        assertEquals("0.11.0", ContractIdentity.semver)
        assertTrue(
            ContractIdentity.digest.matches(Regex("[0-9a-f]{64}")),
            "다이제스트가 SHA-256 16진수가 아니다: ${ContractIdentity.digest}",
        )
        assertEquals(0, ContractIdentity.major)
    }

    @Test
    fun `다이제스트는 끌려 들어온 google 파일을 빼고 picasso 파일만 이름 순으로 센다`() {
        // 함께 구운 디스크립터를 원소로 갈라 셈법을 다시 댄다. 셋 통째의 SHA-256 과도 달라야
        // `descriptor.proto` 가 실제로 빠졌다는 뜻이 된다 — protobuf 판을 올려도 계약 신원이 안 바뀐다.
        val bytes = checkNotNull(javaClass.getResourceAsStream("/picasso.desc")) { "picasso.desc 가 없다" }
            .use { it.readBytes() }
        val input = CodedInputStream.newInstance(bytes)
        val files = mutableListOf<Pair<String, ByteArray>>()
        while (!input.isAtEnd) {
            assertEquals(10, input.readTag())
            val entry = input.readByteArray()
            val name = DescriptorProtos.FileDescriptorProto.parseFrom(entry).name
            val framed = java.io.ByteArrayOutputStream().also { out ->
                val o = CodedOutputStream.newInstance(out)
                o.writeByteArray(1, entry)
                o.flush()
            }.toByteArray()
            files += name to framed
        }
        assertTrue(files.any { it.first.startsWith("google/") }, "끌려 들어온 import 가 없다 — 시험이 빼는 것을 확인하지 못한다")
        val sha = MessageDigest.getInstance("SHA-256")
        files.filter { it.first.startsWith("picasso/") }.sortedBy { it.first }.forEach { sha.update(it.second) }
        val expected = sha.digest().joinToString("") { "%02x".format(it) }
        val whole = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        assertEquals(expected, ContractIdentity.digest)
        assertNotEquals(whole, ContractIdentity.digest)
    }

    @Test
    fun `리소스가 없으면 기동하지 않는다`() {
        assertFailsWith<IllegalStateException> { ContractIdentity.from(null) }
    }

    @Test
    fun `키가 비어도 기동하지 않는다`() {
        // 파일은 있는데 굽는 태스크가 반쯤 돈 경우다. 파일 존재만 보는
        // 구현은 이것을 통과시킨다.
        listOf("", "semver=0.1.0\n", "digest=abc\n", "semver=\ndigest=\n").forEach { text ->
            assertFailsWith<IllegalStateException>("'$text' 를 받아들였다") {
                ContractIdentity.from(ByteArrayInputStream(text.toByteArray()))
            }
        }
    }

    @Test
    fun `semver 해석을 통째로 확인한다`() {
        // 관대하게 파싱하면 "1"이나 "1.2"가 major 1로 읽히고, major 불일치
        // 차단이 조용히 통과 판정을 낸다.
        mapOf(
            "0.1.0" to 0, "1.0.0" to 1, "12.3.4" to 12,
            "1" to null, "1.2" to null, "1.2.3.4" to null,
            "v1.2.3" to null, "1.2.3-rc1" to null, "" to null, "x.y.z" to null,
        ).forEach { (text, expected) ->
            assertEquals(expected, ContractIdentity.majorOf(text), "'$text'")
        }
    }
}

/** §5.5의 헤더 표. 세 방향의 열이 실제로 다른지까지 본다. */
class HeaderColumnsTest {

    @Test
    fun `세 열이 MessageHeader의 필드를 빠짐없이 덮는다`() {
        // 필드가 늘면 여기서 걸린다 — 게터를 직접 부르는 시험은 새 필드를
        // 조용히 안 보게 된다.
        assertEquals(
            MessageHeader.getDescriptor().fields.map { it.name }.toSet(),
            HeaderColumns.ALL,
            "MessageHeader에 §5.5의 어느 열에도 없는 필드가 있다",
        )
        assertEquals(13, HeaderColumns.ALL.size)
    }

    @Test
    fun `세 열이 실제로 서로 다르다`() {
        // 한 열을 세 번 쓰면 위 시험은 통과하고 표는 사라진다.
        assertTrue(HeaderColumns.REQUEST != HeaderColumns.RESPONSE)
        assertTrue(HeaderColumns.PUBLISH != HeaderColumns.RESPONSE)

        // §5.5가 명시적으로 가른 셋.
        assertTrue("client_id" in HeaderColumns.REQUEST)
        assertTrue("client_id" !in HeaderColumns.RESPONSE)
        assertTrue("client_id" !in HeaderColumns.PUBLISH)

        assertTrue("sequence" in HeaderColumns.PUBLISH)
        assertTrue("sequence" !in HeaderColumns.RESPONSE)
        assertTrue("sequence" !in HeaderColumns.REQUEST)

        assertTrue("update_index" in HeaderColumns.WATCH_RESPONSE)
        assertTrue("update_index" !in HeaderColumns.RESPONSE)
        assertTrue("update_index" !in HeaderColumns.PUBLISH)

        // 요청은 세대·세션·시각을 싣지 않는다.
        listOf("capability_epoch", "session_id", "profile_ref", "event_id", "occurred_at", "state_as_of")
            .forEach { assertTrue(it !in HeaderColumns.REQUEST, "$it 가 요청 열에 있다") }
    }

    @Test
    fun `isSet이 존재를 갖는 필드와 안 갖는 필드를 모두 다룬다`() {
        // profile_ref는 메시지라 존재를 갖고, 나머지는 proto3 암묵 존재라
        // hasField가 던진다. 한쪽만 다루면 나머지 열둘에서 예외가 난다.
        val empty = MessageHeader.getDefaultInstance()
        HeaderColumns.ALL.forEach { assertFalse(HeaderColumns.isSet(empty, it), "$it") }

        val filled = RequestHeaders.build("picasso.v1.StartTaskRequest", "r1", "c1")
        HeaderColumns.REQUEST.forEach { assertTrue(HeaderColumns.isSet(filled, it), "$it") }
    }

    @Test
    fun `없는 필드를 물으면 조용히 false를 주지 않는다`() {
        // 오타 난 이름이 "안 실렸다"로 읽히면 금지 단언이 통째로 공허해진다.
        assertFailsWith<IllegalArgumentException> {
            HeaderColumns.isSet(MessageHeader.getDefaultInstance(), "client_di")
        }
    }
}

class RequestHeadersTest {

    @Test
    fun `요청 헤더가 §5-5의 요청 열을 정확히 따른다`() {
        val header = RequestHeaders.build("picasso.v1.StartTaskRequest", "r1", "line-controller")

        HeaderColumns.REQUEST.forEach {
            assertTrue(HeaderColumns.isSet(header, it), "$it 가 비었다 — §5.5는 요청에 싣는다")
        }
        (HeaderColumns.ALL - HeaderColumns.REQUEST).forEach {
            assertFalse(HeaderColumns.isSet(header, it), "$it 를 요청에 실었다 — §5.5는 싣지 않는다")
        }

        assertEquals("picasso.v1.StartTaskRequest", header.schemaId)
        assertEquals("r1", header.robotId)
        assertEquals("line-controller", header.clientId)
        // 계약 신원이 인자가 아니라는 것이 요점이다. 호출자가 채우면 §6.2의
        // 개정판 대조가 무의미해진다.
        assertEquals(ContractIdentity.semver, header.contractSemver)
        assertEquals(ContractIdentity.digest, header.contractDigest)
    }

    // ── §5.5의 차단·경보 규칙

    @Test
    fun `같은 계약이면 Same이고 차단하지 않는다`() {
        val result = RequestHeaders.compatibility(RequestHeaders.build("s", "r1", "c1"))
        assertEquals(ContractCompatibility.Same, result)
        assertFalse(result.blocking)
    }

    @Test
    fun `major가 다르면 차단한다`() {
        val result = RequestHeaders.compatibility(with(semver = "1.0.0"))
        assertTrue(result is ContractCompatibility.MajorMismatch, "$result")
        assertTrue(result.blocking)
    }

    @Test
    fun `minor와 patch 차이는 차단하지 않는다`() {
        // §5.5 — "그 외 불일치는 경보로 갈린다". 차단하면 minor 증가가
        // 호환이라는 §5.2의 규칙이 런타임에서 뒤집힌다.
        // **우리 자신의 버전을 리터럴로 쓰지 않는다.** 0.1.0 → 0.2.0으로
        // 올렸을 때 목록의 "0.2.0"이 Same이 되어 이 시험이 깨졌다 —
        // 계약을 고칠 때마다 시험을 고치게 되면 아무도 값을 안 보게 된다.
        val ours = ContractIdentity.semver
        // 우리보다 **높은** minor 하나는 ours 에서 파생한다 — 리터럴로 두면 계약을 올릴 때 겹치고(0.9.0 이 그랬다),
        // 빼면 «상대가 더 새로워도 차단하지 않는다» 를 아무도 안 본다.
        val newerMinor = ours.split(".").let { (major, minor, _) -> "$major.${minor.toInt() + 1}.0" }
        listOf(newerMinor, "0.0.1", "0.5.7")
            .filterNot { it == ours }
            .also { assertEquals(3, it.size, "목록이 우리 버전과 겹친다: $ours") }
            .forEach { theirs ->
                val result = RequestHeaders.compatibility(with(semver = theirs))
                assertTrue(result is ContractCompatibility.Divergent, "$theirs -> $result")
                assertFalse(result.blocking, "$theirs 를 차단했다")
            }
    }

    @Test
    fun `다이제스트만 달라도 경보 대상이되 차단하지 않는다`() {
        // 같은 semver로 다른 계약을 빌드한 상황이다(§15의 4번).
        val result = RequestHeaders.compatibility(with(digest = "0".repeat(64)))
        assertTrue(result is ContractCompatibility.Divergent, "$result")
        assertFalse(result.blocking)
    }

    @Test
    fun `semver를 안 실으면 차단하지 않는다`() {
        // §5.5가 "semver가 있으면"이라고 조건을 달았다. 경보는 event
        // 스트림이 필요하므로 Chunk 6이다.
        val result = RequestHeaders.compatibility(with(semver = ""))
        assertEquals(ContractCompatibility.Unstated, result)
        assertFalse(result.blocking)
    }

    @Test
    fun `해석할 수 없는 semver는 차단한다`() {
        // fail-closed. major를 모르면 호환 여부를 판정할 수 없고, 모르는
        // 것을 통과시키면 빌드 시점 보장이 런타임에서 조용히 무너진다.
        listOf("1", "1.2", "v0.1.0", "0.1.0-rc1", "abc").forEach { theirs ->
            val result = RequestHeaders.compatibility(with(semver = theirs))
            assertTrue(result is ContractCompatibility.Unparseable, "$theirs -> $result")
            assertTrue(result.blocking, "$theirs 를 통과시켰다")
        }
    }

    @Test
    fun `차단 판정이 다섯 결과를 전부 덮는다`() {
        // blocking이 언제나 false면 위 차단 시험만으로는 안 걸린다 —
        // 실제로는 걸리지만, 결과 종류가 늘 때 분류가 빠지는 것을 막는다.
        val blocking = listOf(
            ContractCompatibility.Same to false,
            ContractCompatibility.Unstated to false,
            ContractCompatibility.Unparseable("x") to true,
            ContractCompatibility.MajorMismatch("1.0.0", ContractIdentity.semver) to true,
            ContractCompatibility.Divergent("0.9.0", ContractIdentity.semver) to false,
        )
        assertEquals(5, blocking.size)
        blocking.forEach { (result, expected) -> assertEquals(expected, result.blocking, "$result") }
    }

    private fun with(
        semver: String = ContractIdentity.semver,
        digest: String = ContractIdentity.digest,
    ): MessageHeader = RequestHeaders.buildWithContract("s", "r1", "c1", semver, digest)
}

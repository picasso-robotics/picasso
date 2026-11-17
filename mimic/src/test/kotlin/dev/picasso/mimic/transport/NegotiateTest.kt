package dev.picasso.mimic.transport

import com.fasterxml.jackson.databind.ObjectMapper
import dev.picasso.contracts.v1.CapabilityRequirement
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.ProtocolLimits
import dev.picasso.contracts.v1.Reference
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.wire.HeaderColumns
import dev.picasso.mimic.engine.TaskMachineFixtures
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.picasso.profile.RequirementSet

/**
 * §5.4의 핸드셰이크. 완료 기준 13의 판정을 만든다 — 닫는 것은 Chunk 4·6이다
 * (픽스처 셋 중 둘이 거기 산출물이다).
 *
 * 픽스처 프로파일 기준:
 *   pick_place  1.2  cancel=NO   pause=YES
 *   navigate_to 1.0  cancel=YES  pause=UNKNOWN
 *   optional_fields: grip_force=SUPPORTED, verify_grasp=REQUIRED
 *   protocol_limits: string 256, array 32
 */
class NegotiateTest {

    private val fixture = GrpcFixture(mapOf("r1" to TaskMachineFixtures.document()))

    // JUnit이 시험마다 인스턴스를 새로 만든다. 안 닫으면 in-process 서버가 쌓인다.
    @AfterTest fun close() = fixture.close()

    /** 아무것도 어기지 않는 요구. 여기서 출발해 하나씩 어긴다. */
    private fun satisfying(): CapabilityRequirement.Builder = CapabilityRequirement.newBuilder()
        .setClientId("line-controller")
        .setRobotId("r1")
        .addRequirements("pick_place@^1.2")
        .addRequirements("navigate_to@^1.0")
        .addOptionalFieldsUsed("task.parameters.verify_grasp")
        .setLimitsNeeded(ProtocolLimits.newBuilder().setMaxStringLength(64).setMaxArrayLength(8))

    private fun negotiate(requirement: CapabilityRequirement.Builder): NegotiateResponse =
        fixture.skills.negotiate(
            NegotiateRequest.newBuilder()
                .setHeader(GrpcFixture.requestHeader("r1"))
                .setRequirement(requirement)
                .build(),
        )

    private fun codes(requirement: CapabilityRequirement.Builder): List<RejectionCode> =
        negotiate(requirement).rejectionsList.map { it.code }

    // ── 통과

    @Test
    fun `모두 만족하면 accepted이고 거절이 비어 있다`() {
        // 이것이 없으면 "언제나 전부 거절"하는 구현이 아래 시험을 전부 통과한다.
        val response = negotiate(satisfying())
        assertTrue(response.accepted, "만족하는 요구를 거절했다: ${response.rejectionsList}")
        assertEquals(emptyList(), response.rejectionsList)
    }

    @Test
    fun `픽스처 요구 집합 파일이 그대로 통과한다`() {
        // §5.4 — 요구 집합은 코드가 아니라 설정이다. 파일이 실제로 이
        // 프로파일과 맞물리지 않으면 Chunk 4가 그것을 못 쓴다.
        val set = RequirementSet.parse(
            "minimal",
            Files.readString(Path.of("..", "profile", "requirements", "minimal.json").normalize()),
        )
        val response = negotiate(
            CapabilityRequirement.newBuilder()
                .setClientId(set.clientId)
                .setRobotId("r1")
                .addAllRequirements(set.requirements.map { it.toString() })
                .addAllOptionalFieldsUsed(set.optionalFieldsUsed)
                .setLimitsNeeded(
                    ProtocolLimits.newBuilder()
                        .setMaxStringLength(set.limitsNeeded.maxStringLength)
                        .setMaxArrayLength(set.limitsNeeded.maxArrayLength),
                ),
        )
        assertTrue(response.accepted, "${response.rejectionsList}")
    }

    @Test
    fun `틀린 요구 집합 파일이 MAJOR_MISMATCH를 낸다`() {
        // 완료 기준 13의 픽스처 하나. 파일이 무해하면 그 기준이 공허해진다.
        val set = RequirementSet.parse(
            "wrong-major",
            Files.readString(Path.of("..", "profile", "requirements", "wrong-major.json").normalize()),
        )
        val response = negotiate(
            satisfying().clearRequirements().addAllRequirements(set.requirements.map { it.toString() }),
        )
        assertFalse(response.accepted)
        assertEquals(
            listOf(RejectionCode.REJECTION_CODE_MAJOR_MISMATCH),
            response.rejectionsList.map { it.code },
        )
    }

    // ── 거절 다섯

    @Test
    fun `거절 다섯을 각각 낸다`() {
        val cases: Map<RejectionCode, CapabilityRequirement.Builder> = mapOf(
            RejectionCode.REJECTION_CODE_IDENTITY_MISMATCH to
                satisfying().setClientId("someone-else"),
            RejectionCode.REJECTION_CODE_SKILL_ABSENT to
                satisfying().addRequirements("weld@^1.0"),
            RejectionCode.REJECTION_CODE_MAJOR_MISMATCH to
                satisfying().clearRequirements().addRequirements("pick_place@^2.0"),
            RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING to
                satisfying().clearOptionalFieldsUsed(),
            RejectionCode.REJECTION_CODE_LIMIT_EXCEEDED to
                satisfying().setLimitsNeeded(ProtocolLimits.newBuilder().setMaxStringLength(9999)),
        )
        // 표가 비면 아무것도 확인하지 않고 통과한다.
        assertEquals(5, cases.size)

        cases.forEach { (expected, requirement) ->
            val response = negotiate(requirement)
            assertFalse(response.accepted, "$expected 인데 accepted다")
            assertTrue(
                response.rejectionsList.any { it.code == expected },
                "$expected 가 안 나왔다: ${response.rejectionsList.map { it.code }}",
            )
        }
    }

    @Test
    fun `안 맞는 것을 한 번에 전부 돌려준다`() {
        // proto 주석이 요구하는 것이 이것이다. **첫 실패에서 끊는 구현은 위
        // 시험을 전부 통과한다** — 각 케이스가 한 가지만 어기기 때문이다.
        val response = negotiate(
            CapabilityRequirement.newBuilder()
                .setClientId("someone-else")                                   // IDENTITY_MISMATCH
                .setRobotId("r1")
                .addRequirements("pick_place@^2.0")                            // MAJOR_MISMATCH
                .addRequirements("weld@^1.0")                                  // SKILL_ABSENT
                .setLimitsNeeded(                                              // LIMIT_EXCEEDED ×2
                    ProtocolLimits.newBuilder().setMaxStringLength(9999).setMaxArrayLength(9999),
                ),
            // optional_fields_used 안 씀                                       REQUIRED_OPTIONAL_MISSING
        )

        assertEquals(
            setOf(
                RejectionCode.REJECTION_CODE_IDENTITY_MISMATCH,
                RejectionCode.REJECTION_CODE_MAJOR_MISMATCH,
                RejectionCode.REJECTION_CODE_SKILL_ABSENT,
                RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING,
                RejectionCode.REJECTION_CODE_LIMIT_EXCEEDED,
            ),
            response.rejectionsList.map { it.code }.toSet(),
        )
        // toSet()은 개수와 중복을 숨긴다. 한계 둘이 각각 나와야 여섯이다.
        assertEquals(
            6,
            response.rejectionsList.size,
            "거절 개수가 다르다: ${response.rejectionsList.map { "${it.code}:${it.detail}" }}",
        )
    }

    @Test
    fun `없는 스킬에 SKILL_ABSENT와 MAJOR_MISMATCH를 둘 다 내지 않는다`() {
        // 둘 다 내면 소비자가 같은 요구에 대해 두 가지를 고치려 든다.
        val rejections = negotiate(satisfying().addRequirements("weld@^1.0")).rejectionsList
        assertEquals(1, rejections.size, "${rejections.map { it.code }}")
        assertEquals(RejectionCode.REJECTION_CODE_SKILL_ABSENT, rejections.single().code)
    }

    // ── 버전 판정의 방향 (§5.2)

    @Test
    fun `클라이언트가 모르는 minor는 거절이 아니다`() {
        // pick_place는 1.2인데 ^1.0을 요구해도 통과해야 한다 — minor 증가는
        // 선택 파라미터 추가뿐이고 클라이언트가 몰라도 동작한다.
        assertTrue(negotiate(satisfying().clearRequirements().addRequirements("pick_place@^1.0")).accepted)
    }

    @Test
    fun `minor 부족은 거절이고 detail이 minor라고 말한다`() {
        // navigate_to는 1.0이다. ^1.3을 요구하면 만족하지 못한다.
        // 코드 이름만으로는 major 문제로 읽히므로 detail이 말해야 한다(§15).
        val rejection = negotiate(
            satisfying().clearRequirements().addRequirements("navigate_to@^1.3"),
        ).rejectionsList.single()

        assertEquals(RejectionCode.REJECTION_CODE_MAJOR_MISMATCH, rejection.code)
        assertTrue("minor" in rejection.detail, "minor 부족임을 말하지 않는다: ${rejection.detail}")
    }

    @Test
    fun `major 불일치와 minor 부족의 detail이 서로 다르다`() {
        val majorDetail = negotiate(
            satisfying().clearRequirements().addRequirements("pick_place@^2.0"),
        ).rejectionsList.single().detail
        val minorDetail = negotiate(
            satisfying().clearRequirements().addRequirements("navigate_to@^1.3"),
        ).rejectionsList.single().detail
        assertTrue(majorDetail != minorDetail)
        assertTrue("major" in majorDetail, majorDetail)
    }

    // ── 선택 필드 (§5.3)

    @Test
    fun `SUPPORTED 선택 필드를 안 써도 거절이 아니다`() {
        // REQUIRED만 시험하면 **선언된 선택 필드를 안 쓰면 무조건 거절**하는
        // 구현이 통과한다. 픽스처가 마침 둘을 다 갖고 있다 —
        // grip_force는 SUPPORTED, verify_grasp는 REQUIRED.
        val response = negotiate(
            satisfying().clearOptionalFieldsUsed()
                .addOptionalFieldsUsed("task.parameters.verify_grasp"),
        )
        assertTrue(response.accepted, "${response.rejectionsList.map { it.detail }}")
    }

    @Test
    fun `REQUIRED를 안 쓰면 그 필드를 가리켜 거절한다`() {
        val rejection = negotiate(satisfying().clearOptionalFieldsUsed()).rejectionsList.single()
        assertEquals(RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING, rejection.code)
        assertEquals(
            "task.parameters.verify_grasp",
            rejection.referencesList.single().value,
        )
    }

    // ── 한계 (§5.4)

    @Test
    fun `한계 필드를 전부 본다`() {
        // 한계는 둘이다. max_string_length만 시험하면 max_array_length를
        // 무시하는 구현이 통과한다. 필드가 늘면 여기서 걸린다.
        assertEquals(2, ProtocolLimits.getDescriptor().fields.size)

        assertEquals(
            listOf(RejectionCode.REJECTION_CODE_LIMIT_EXCEEDED),
            codes(satisfying().setLimitsNeeded(ProtocolLimits.newBuilder().setMaxStringLength(257))),
        )
        assertEquals(
            listOf(RejectionCode.REJECTION_CODE_LIMIT_EXCEEDED),
            codes(satisfying().setLimitsNeeded(ProtocolLimits.newBuilder().setMaxArrayLength(33))),
        )
    }

    @Test
    fun `한계와 정확히 같으면 통과한다`() {
        // 경계에서 부등호를 뒤집는 것이 가장 흔한 실수다.
        assertTrue(
            negotiate(
                satisfying().setLimitsNeeded(
                    ProtocolLimits.newBuilder().setMaxStringLength(256).setMaxArrayLength(32),
                ),
            ).accepted,
        )
    }

    @Test
    fun `한계 선언은 스키마가 1 이상을 강제한다`() {
        // "0은 말하지 않았다"가 클라이언트 쪽에서만 안전한 이유가 이것이다.
        // 로봇이 0을 선언할 수 없으므로 0 > declared 가 결코 참이 되지 않고,
        // Negotiator가 needed==0을 따로 거를 필요가 없다.
        //
        // 이 제약이 풀리면 Negotiator.exceeds를 다시 봐야 한다 — 그때
        // 이 시험이 알려 준다.
        val limits = ObjectMapper()
            .readTree(Files.readString(Path.of("..", "profile", "schema", "capability-profile.schema.json").normalize()))
            .path("properties").path("protocol_limits")

        assertEquals(
            setOf("max_string_length", "max_array_length"),
            limits.path("required").map { it.asText() }.toSet(),
        )
        limits.path("properties").fields().forEach { (name, node) ->
            assertEquals(1, node.path("minimum").asInt(), "$name 의 하한이 1이 아니다")
        }
    }

    @Test
    fun `limits_needed가 0이면 막지 않는다`() {
        // proto3 암묵 존재 — 0은 "말하지 않았다"다. 말하지 않은 클라이언트를
        // 막으면 한계를 선언하지 않는 소비자가 전부 걸린다.
        assertTrue(negotiate(satisfying().clearLimitsNeeded()).accepted)
    }

    // ── 신원 (§5.4)

    @Test
    fun `client_id와 robot_id 어느 쪽이 어긋나도 잡는다`() {
        assertTrue(
            RejectionCode.REJECTION_CODE_IDENTITY_MISMATCH in codes(satisfying().setClientId("x")),
        )
        assertTrue(
            RejectionCode.REJECTION_CODE_IDENTITY_MISMATCH in codes(satisfying().setRobotId("r9")),
        )
    }

    @Test
    fun `신원이 어긋나도 나머지 판정을 함께 돌려준다`() {
        // 신원만 단독 반환하면 클라이언트가 고칠 때마다 한 번씩 왕복한다.
        val codes = codes(satisfying().setClientId("x").clearOptionalFieldsUsed())
        assertTrue(RejectionCode.REJECTION_CODE_IDENTITY_MISMATCH in codes)
        assertTrue(RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING in codes)
    }

    // ── 거절의 품질

    @Test
    fun `거절에 언제나 detail이 붙는다`() {
        // openTCS ExplainedBoolean의 요점. 거절만 하고 이유를 안 주면
        // 운영에서 원인을 못 찾는다.
        val all: List<Rejection> = negotiate(
            CapabilityRequirement.newBuilder()
                .setClientId("x").setRobotId("r9")
                .addRequirements("pick_place@^2.0").addRequirements("weld@^1.0")
                .setLimitsNeeded(
                    ProtocolLimits.newBuilder().setMaxStringLength(9999).setMaxArrayLength(9999),
                ),
        ).rejectionsList

        assertTrue(all.size >= 6, "${all.size}")
        all.forEach { assertTrue(it.detail.isNotBlank(), "${it.code} 에 이유가 없다") }
    }

    @Test
    fun `스킬 거절이 어느 스킬인지 가리킨다`() {
        // 요구가 여럿일 때 코드만으로는 어느 것이 걸렸는지 알 수 없다.
        val rejections = negotiate(
            satisfying().clearRequirements()
                .addRequirements("pick_place@^2.0").addRequirements("weld@^1.0"),
        ).rejectionsList

        assertEquals(
            setOf("pick_place", "weld"),
            rejections.flatMap { it.referencesList }
                .filter { it.key == Reference.Key.KEY_SKILL_ID }
                .map { it.value }.toSet(),
        )
    }

    // ── 해석 불가능한 요구 (판정 이전)

    @Test
    fun `해석할 수 없는 요구는 INVALID_ARGUMENT다`() {
        // "만족하는가"를 답할 수 없는 입력이다. 거절 다섯 중 어느 것도
        // 이 뜻이 아니다.
        val error = assertFailsWith<StatusRuntimeException> {
            negotiate(satisfying().clearRequirements().addRequirements("pick_place"))
        }
        assertEquals(Status.Code.INVALID_ARGUMENT, error.status.code)
    }

    @Test
    fun `해석할 수 없는 요구가 여럿이면 전부 열거한다`() {
        // 파싱만 첫 실패에서 끊으면 "한 번에 전부"라는 이 청크의 규율과 모순이다.
        val message = assertFailsWith<StatusRuntimeException> {
            negotiate(
                satisfying().clearRequirements()
                    .addRequirements("pick_place").addRequirements("navigate_to@1.0"),
            )
        }.status.description!!

        assertTrue("pick_place" in message, message)
        assertTrue("navigate_to@1.0" in message, message)
    }

    // ── 응답 헤더

    @Test
    fun `응답 헤더가 §5-5를 따른다 — 오염된 요청으로`() {
        val response = fixture.skills.negotiate(
            NegotiateRequest.newBuilder()
                .setHeader(GrpcFixture.poisonedHeader("r1"))
                .setRequirement(satisfying().setClientId("line-controller"))
                .build(),
        )
        (HeaderColumns.RESPONSE - HeaderColumns.ZERO_IS_VALUE).forEach {
            assertTrue(HeaderColumns.isSet(response.header, it), "$it 가 비었다")
        }
        (HeaderColumns.ALL - HeaderColumns.RESPONSE).forEach {
            assertFalse(HeaderColumns.isSet(response.header, it), "$it 를 요청에서 복사했다")
        }
        assertEquals("picasso.v1.NegotiateResponse", response.header.schemaId)
    }
}

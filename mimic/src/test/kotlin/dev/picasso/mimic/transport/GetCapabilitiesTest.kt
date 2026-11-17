package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.GetCapabilitiesRequest
import dev.picasso.contracts.v1.GetCapabilitiesResponse
import dev.picasso.contracts.v1.Support
import dev.picasso.contracts.wire.HeaderColumns
import dev.picasso.mimic.engine.TaskMachineFixtures
import dev.picasso.capability.CapabilityProjection
import dev.picasso.profile.ProfileDocument
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 완료 기준 10 — "능력을 하드코딩하면 여기서 걸린다"(§12.2). */
class GetCapabilitiesTest {

    private val raw = TaskMachineFixtures.fixtureRaw

    private fun docOf(vararg edits: Pair<String, String>): ProfileDocument {
        var text = raw
        edits.forEach { (from, to) ->
            val next = text.replace(from, to)
            check(next != text) { "치환이 아무것도 바꾸지 못했다: '$from'" }
            text = next
        }
        return TaskMachineFixtures.document(text)
    }

    @Test
    fun `응답이 프로파일의 투영과 정확히 같다`() {
        val doc = TaskMachineFixtures.document()
        GrpcFixture(mapOf("r1" to doc)).use {
            assertEquals(CapabilityProjection.of(doc), it.capabilitiesOf("r1").capability)
        }
    }

    @Test
    fun `한 프로세스의 두 기체가 각자의 능력을 통째로 돌려준다`() {
        // **통째 비교여야 한다.** vendor 하나만 보면 skills·optional_fields·
        // publish_interval·exclusive_control_required를 하드코딩한 구현이
        // 통과하는데, 완료 기준 10은 "정확 비교"라고 적혀 있다.
        val a = docOf(""""vendor": "fixture"""" to """"vendor": "alpha"""")
        val b = docOf(
            """"vendor": "fixture"""" to """"vendor": "beta"""",
            """"minor": 2,""" to """"minor": 5,""",
            """"max_string_length": 256""" to """"max_string_length": 64""",
        )
        // 전제가 무너지면 아래가 아무것도 증명하지 않는다.
        assertTrue(CapabilityProjection.of(a) != CapabilityProjection.of(b))

        GrpcFixture(mapOf("r1" to a, "r2" to b)).use {
            assertEquals(CapabilityProjection.of(a), it.capabilitiesOf("r1").capability)
            assertEquals(CapabilityProjection.of(b), it.capabilitiesOf("r2").capability)
        }
    }

    @Test
    fun `프로파일의 스킬 안을 고치면 응답이 따라 바뀐다`() {
        // 최상위 필드(protocol_limits)만 고치면 skills가 파생되는지는 여전히
        // 시험되지 않는다. **스킬 안**을 고친다.
        val doc = docOf(""""pause_support": "YES"""" to """"pause_support": "UNKNOWN"""")
        GrpcFixture(mapOf("r1" to doc)).use {
            val skill = it.capabilitiesOf("r1").capability.skillsList
                .single { s -> s.skillType == "pick_place" }
            assertEquals(Support.SUPPORT_UNKNOWN, skill.pauseSupport)
        }
    }

    @Test
    fun `없는 제약은 응답에도 없다`() {
        // 명시적 존재를 계약에 넣은 이유가 이것이다. 투영이 0으로 채우면
        // 소비자가 "음수 금지"나 "빈 문자열만 허용"으로 읽는다.
        GrpcFixture(mapOf("r1" to TaskMachineFixtures.document())).use {
            val verifyGrasp = it.capabilitiesOf("r1").capability.skillsList
                .single { s -> s.skillType == "pick_place" }
                .parametersList.single { p -> p.key == "verify_grasp" }
            assertFalse(verifyGrasp.hasMinValue())
            assertFalse(verifyGrasp.hasMaxValue())
            assertFalse(verifyGrasp.hasMaxLength())
            assertFalse(verifyGrasp.hasUnit())
        }
    }

    // ── 라우팅 (§10.2)

    @Test
    fun `헤더의 robot_id로 기체를 고른다`() {
        val a = docOf(""""vendor": "fixture"""" to """"vendor": "alpha"""")
        val b = docOf(""""vendor": "fixture"""" to """"vendor": "beta"""")
        GrpcFixture(mapOf("r1" to a, "r2" to b)).use {
            assertEquals("alpha", it.capabilitiesOf("r1").capability.vendor)
            assertEquals("beta", it.capabilitiesOf("r2").capability.vendor)
        }
    }

    @Test
    fun `모르는 robot_id는 NOT_FOUND다`() {
        GrpcFixture(mapOf("r1" to TaskMachineFixtures.document())).use {
            assertEquals(Status.Code.NOT_FOUND, statusOf { it.capabilitiesOf("r9") })
        }
    }

    @Test
    fun `빈 robot_id를 첫 기체로 접지 않는다`() {
        // 접으면 기체가 하나인 시험은 전부 통과하고 둘이 되는 순간
        // 조용히 엉뚱한 기체로 간다.
        GrpcFixture(mapOf("r1" to TaskMachineFixtures.document())).use {
            assertEquals(Status.Code.INVALID_ARGUMENT, statusOf { it.capabilitiesOf("") })
        }
    }

    @Test
    fun `헤더가 권위이고 페이로드의 robot_id는 복사본이다`() {
        val a = docOf(""""vendor": "fixture"""" to """"vendor": "alpha"""")
        val b = docOf(""""vendor": "fixture"""" to """"vendor": "beta"""")
        GrpcFixture(mapOf("r1" to a, "r2" to b)).use { fixture ->
            // 같으면 통과하고 헤더가 고른 기체가 나온다.
            val ok = fixture.skills.getCapabilities(
                GetCapabilitiesRequest.newBuilder()
                    .setHeader(GrpcFixture.requestHeader("r2")).setRobotId("r2").build(),
            )
            assertEquals("beta", ok.capability.vendor)

            // 어긋나면 거절한다. 페이로드만 보는 구현은 헤더 라우팅을
            // 통째로 건너뛴다.
            assertEquals(
                Status.Code.INVALID_ARGUMENT,
                statusOf {
                    fixture.skills.getCapabilities(
                        GetCapabilitiesRequest.newBuilder()
                            .setHeader(GrpcFixture.requestHeader("r1")).setRobotId("r2").build(),
                    )
                },
            )
        }
    }

    @Test
    fun `라우팅은 헤더로 하고 신원은 그 뒤에 판정한다`() {
        // 앞 시험만으로는 **페이로드로 라우팅하는 구현이 통과한다** — 어긋나면
        // 어차피 거절하므로 결과가 같아 보인다(실측: 주입한 결함이 새어
        // 나갔다). 두 값 중 하나만 호스팅되는 경우가 순서를 드러낸다.
        GrpcFixture(mapOf("r1" to TaskMachineFixtures.document())).use { fixture ->
            // 헤더는 있는 기체, 페이로드는 없는 기체 → 신원 불일치이지
            // "그런 기체 없음"이 아니다.
            assertEquals(
                Status.Code.INVALID_ARGUMENT,
                statusOf {
                    fixture.skills.getCapabilities(
                        GetCapabilitiesRequest.newBuilder()
                            .setHeader(GrpcFixture.requestHeader("r1")).setRobotId("r9").build(),
                    )
                },
                "페이로드로 라우팅했다",
            )

            // 헤더는 없는 기체, 페이로드는 있는 기체 → 페이로드로 구제하지 않는다.
            assertEquals(
                Status.Code.NOT_FOUND,
                statusOf {
                    fixture.skills.getCapabilities(
                        GetCapabilitiesRequest.newBuilder()
                            .setHeader(GrpcFixture.requestHeader("r9")).setRobotId("r1").build(),
                    )
                },
                "헤더가 모르는 기체를 가리키는데 페이로드로 구제했다",
            )
        }
    }

    // ── 계약 개정판 차단 (§5.5)

    @Test
    fun `요청의 계약 major가 다르면 FAILED_PRECONDITION이다`() {
        GrpcFixture(mapOf("r1" to TaskMachineFixtures.document())).use { fixture ->
            assertEquals(
                Status.Code.FAILED_PRECONDITION,
                statusOf {
                    fixture.skills.getCapabilities(
                        GetCapabilitiesRequest.newBuilder()
                            .setHeader(GrpcFixture.headerWithSemver("r1", "1.0.0")).build(),
                    )
                },
            )
        }
    }

    @Test
    fun `minor 차이는 차단하지 않는다`() {
        // 차단하면 minor 증가가 호환이라는 §5.2의 규칙이 런타임에서 뒤집힌다.
        GrpcFixture(mapOf("r1" to TaskMachineFixtures.document())).use { fixture ->
            val response = fixture.skills.getCapabilities(
                GetCapabilitiesRequest.newBuilder()
                    .setHeader(GrpcFixture.headerWithSemver("r1", "0.9.0")).build(),
            )
            assertEquals("fixture", response.capability.vendor)
        }
    }

    // ── 응답 헤더 (§5.5)

    @Test
    fun `응답 헤더가 §5-5를 따른다 — 오염된 요청으로`() {
        // 깨끗한 요청으로 보면 이 시험은 우연히 통과한다. 요청이 금지 필드를
        // 실제로 실어 와야 "복사하지 않는다"가 시험된다.
        GrpcFixture(mapOf("r1" to TaskMachineFixtures.document())).use { fixture ->
            val response = fixture.skills.getCapabilities(
                GetCapabilitiesRequest.newBuilder()
                    .setHeader(GrpcFixture.poisonedHeader("r1")).build(),
            )
            val header = response.header

            (HeaderColumns.RESPONSE - HeaderColumns.ZERO_IS_VALUE).forEach {
                assertTrue(HeaderColumns.isSet(header, it), "$it 가 비었다")
            }
            (HeaderColumns.ALL - HeaderColumns.RESPONSE).forEach {
                assertFalse(HeaderColumns.isSet(header, it), "$it 를 요청에서 복사했다")
            }

            assertEquals("picasso.v1.GetCapabilitiesResponse", header.schemaId)
            assertEquals("r1", header.robotId)
            assertEquals(1L, header.capabilityEpoch, "요청의 777을 복사했다")
            assertEquals("fixture/minimal", header.profileRef.profileId)
            assertEquals("2026-09-06T00:00:00Z", header.occurredAt, "요청의 시각을 복사했다")
            assertTrue(header.sessionId != "poison-session")
            assertTrue(header.eventId != "poison-event")
        }
    }

    @Test
    fun `기체마다 다른 세션과 event_id가 나간다`() {
        val doc = TaskMachineFixtures.document()
        GrpcFixture(mapOf("r1" to doc, "r2" to doc)).use {
            val a = it.capabilitiesOf("r1").header
            val b = it.capabilitiesOf("r2").header
            assertTrue(a.sessionId != b.sessionId, "두 기체가 같은 세션을 쓴다")
            assertTrue(a.eventId != b.eventId, "두 기체가 같은 event_id를 쓴다")
        }
    }

    private fun statusOf(call: () -> Unit): Status.Code =
        assertFailsWith<StatusRuntimeException>(block = call).status.code
}

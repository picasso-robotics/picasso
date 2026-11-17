package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.GetCapabilitiesResponse
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.contracts.wire.ContractIdentity
import dev.picasso.contracts.wire.HeaderColumns
import dev.picasso.mimic.RobotInstance
import dev.picasso.mimic.engine.Clock
import dev.picasso.mimic.engine.TaskMachineFixtures
import dev.picasso.mimic.engine.VirtualClock
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §5.5의 gRPC 응답 열이 이 시험의 명세다. */
class ResponseHeadersTest {

    /**
     * 표는 [HeaderColumns]가 갖는다 — 계약의 규칙이므로 `contracts`에 있고,
     * 열이 실제로 서로 다른지는 거기서 본다. 여기서는 **구현이 그 열을
     * 따르는지**만 본다.
     */
    // 대조하지 않는 미믹의 바닥값은 0 — «대조 안 함» 이다(0.12.0). 0 이 값인 칸은 비어도 누락이 아니다.
    private val required = HeaderColumns.RESPONSE - HeaderColumns.ZERO_IS_VALUE

    /** 응답에 **실으면 안 되는** 것. `sequence`는 MQTT, `client_id`는 요청 전용. */
    private val forbidden = HeaderColumns.ALL - HeaderColumns.WATCH_RESPONSE

    @Test
    fun `표가 비어 있지 않고 자기모순이 아니다`() {
        assertEquals(10, required.size)
        assertEquals(setOf("sequence", "client_id"), forbidden)
        // 겹치면 아래 시험이 스스로 모순인 채로 통과한다.
        assertTrue(required.intersect(forbidden).isEmpty())
    }

    @Test
    fun `응답 종류 넷 모두가 §5-5의 표를 따른다`() {
        // 한 응답만 보면 다른 셋이 침묵한다. 실제로 실수는 RPC 하나를
        // 다르게 짜면서 생긴다.
        listOf(
            GetCapabilitiesResponse.getDescriptor(),
            NegotiateResponse.getDescriptor(),
            StartTaskResponse.getDescriptor(),
            WatchTaskResponse.getDescriptor(),
        ).forEach { descriptor ->
            val header = headers().forResponse(descriptor)
            required.forEach {
                assertTrue(isSet(header, it), "${descriptor.name}: $it 가 비었다 — §5.5는 응답에 싣는다")
            }
            forbidden.forEach {
                assertFalse(isSet(header, it), "${descriptor.name}: $it 를 실었다 — §5.5는 싣지 않는다")
            }
        }
    }

    @Test
    fun `update_index는 WatchTask에서만 실린다`() {
        assertFalse(isSet(headers().forResponse(GetCapabilitiesResponse.getDescriptor()), "update_index"))
        assertTrue(
            isSet(headers().forResponse(WatchTaskResponse.getDescriptor(), updateIndex = 3), "update_index"),
        )
        assertEquals(
            3L,
            headers().forResponse(WatchTaskResponse.getDescriptor(), updateIndex = 3).updateIndex,
        )
    }

    @Test
    fun `schema_id는 응답 메시지마다 다르다`() {
        // 상수로 박으면 §6.2의 "메시지마다 판정"이 통째로 무의미해진다.
        val h = headers()
        assertEquals(
            "picasso.v1.GetCapabilitiesResponse",
            h.forResponse(GetCapabilitiesResponse.getDescriptor()).schemaId,
        )
        assertEquals(
            "picasso.v1.NegotiateResponse",
            h.forResponse(NegotiateResponse.getDescriptor()).schemaId,
        )
    }

    @Test
    fun `event_id는 응답마다 새것이다`() {
        // 소비자 측 멱등 처리 키다(§5.5). 겹치면 소비자가 새 메시지를 버린다.
        val h = headers()
        val ids = (1..50).map { h.forResponse(GetCapabilitiesResponse.getDescriptor()).eventId }
        assertEquals(50, ids.toSet().size, "event_id가 중복됐다")
    }

    @Test
    fun `event_id는 기체마다 갈린다`() {
        // 카운터를 전역으로 두면 위 시험은 통과하고 기체 구분만 사라진다.
        val a = headers(robotId = "r1").forResponse(GetCapabilitiesResponse.getDescriptor()).eventId
        val b = headers(robotId = "r2").forResponse(GetCapabilitiesResponse.getDescriptor()).eventId
        assertTrue(a.startsWith("r1-"), a)
        assertTrue(b.startsWith("r2-"), b)
    }

    @Test
    fun `occurred_at과 state_as_of가 시계에서 온다`() {
        // Instant.now()를 쓰면 가상 시계가 무의미해지고 §12.1이 깨진다.
        val clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z"))
        val h = ResponseHeaders(instance(clock = clock))

        val first = h.forResponse(GetCapabilitiesResponse.getDescriptor())
        assertEquals("2026-09-06T00:00:00Z", first.occurredAt)
        assertEquals("2026-09-06T00:00:00Z", first.stateAsOf)

        clock.advance(Duration.ofSeconds(90))
        val later = h.forResponse(GetCapabilitiesResponse.getDescriptor())
        assertEquals("2026-09-06T00:01:30Z", later.occurredAt)
        // state_as_of는 소비자의 신선도 판정 입력이다(§5.5). 처음 것만 보면
        // 기동 시각을 한 번 찍어 두는 구현이 통과하고, 소비자는 모든 응답을
        // 낡은 것으로 판정한다.
        assertEquals("2026-09-06T00:01:30Z", later.stateAsOf)
    }

    @Test
    fun `profile_ref가 프로파일에서 온다`() {
        // registry가 이벤트를 적재할 때 개정판 귀속에 쓴다 — 카나리 중 두
        // 개정판이 동시에 도는 것을 관측하는 유일한 수단이다(§5.5).
        val header = headers().forResponse(GetCapabilitiesResponse.getDescriptor())
        assertEquals("fixture/minimal", header.profileRef.profileId)
        assertEquals(1, header.profileRef.revision)
    }

    @Test
    fun `robot_id와 session_id가 지정된 기체의 것이다`() {
        // **기체 둘로 본다.** isSet은 "기본값이 아니다"일 뿐이라, 하나만 보면
        // robot_id·session_id를 상수로 박거나 기체 하나를 공유하는 구현이
        // 통과한다 — Capability에 대해서는 막아 놓고 헤더에는 열어 둔 자리다.
        val a = instance(robotId = "r1")
        val b = instance(robotId = "r2")
        val ha = ResponseHeaders(a).forResponse(GetCapabilitiesResponse.getDescriptor())
        val hb = ResponseHeaders(b).forResponse(GetCapabilitiesResponse.getDescriptor())

        assertEquals("r1", ha.robotId)
        assertEquals("r2", hb.robotId)
        assertEquals(a.sessionId, ha.sessionId)
        assertEquals(b.sessionId, hb.sessionId)
        assertTrue(ha.sessionId != hb.sessionId, "두 기체가 같은 세션을 쓴다: ${ha.sessionId}")
    }

    @Test
    fun `contract_digest와 contract_semver가 계약 신원에서 온다`() {
        // 값을 안 보면 Task 2와 Task 3이 서로 이어졌는지가 시험되지 않는다.
        // 상수 두 개를 박은 구현이 isSet을 통과한다.
        val header = headers().forResponse(GetCapabilitiesResponse.getDescriptor())
        assertEquals(ContractIdentity.digest, header.contractDigest)
        assertEquals(ContractIdentity.semver, header.contractSemver)
    }

    @Test
    fun `capability_epoch는 1에서 시작한다`() {
        // 0에서 시작하면 proto3 암묵 존재 때문에 첫 세대가 헤더에서 사라진다.
        assertEquals(
            1L,
            headers().forResponse(GetCapabilitiesResponse.getDescriptor()).capabilityEpoch,
        )
    }

    // ── 헬퍼

    private fun instance(
        robotId: String = "r1",
        clock: Clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z")),
    ) = RobotInstance(robotId, TaskMachineFixtures.document(), clock)

    private fun headers(
        robotId: String = "r1",
        clock: Clock = VirtualClock(Instant.parse("2026-09-06T00:00:00Z")),
    ) = ResponseHeaders(instance(robotId, clock))

    /**
     * 필드를 **이름으로** 조회한다. 게터를 직접 부르면 필드가 늘 때 시험이
     * 컴파일은 되고 새 필드를 조용히 안 보게 된다.
     */
    private fun isSet(header: MessageHeader, field: String): Boolean =
        HeaderColumns.isSet(header, field)
}

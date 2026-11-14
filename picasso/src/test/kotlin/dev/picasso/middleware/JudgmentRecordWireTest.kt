package dev.picasso.middleware

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 판단 기록의 인코딩([JudgmentRecordWire]). 담는 쪽이 저장소에 적었다가 재기동 뒤 읽어 오는 모양이다.
 *
 * 읽기는 **전부 아니면 거부**다. 일부만 읽어 다시 지으면 못 읽은 사실이 «없었다» 가 된다 — 그래서 모르는 판·모르는 칸·빠진 칸·
 * 판정이 아닌 상태를 하나씩 넣어 거부를 본다.
 */
class JudgmentRecordWireTest {

    @Test
    fun `판단 기록은 적었다 읽으면 같다`() {
        val text = JudgmentRecordWire.encode(FULL)
        assertEquals(FULL, JudgmentRecordWire.decode(text))
        assertTrue("\"schemaVersion\":\"${JudgmentRecordWire.SCHEMA_VERSION}\"" in text, text)

        // 빈 칸이 많은 기록도 같다. 널 칸은 키를 빼지 않고 null 로 적는다.
        val bare = JudgmentRecord("J-2", 1, emptyList(), null, listOf(UnitRecord("u", 0, "", null, null, null, null)), cancelRequested = false)
        val bareText = JudgmentRecordWire.encode(bare)
        assertEquals(bare, JudgmentRecordWire.decode(bareText))
        assertTrue("\"approval\":null" in bareText && "\"requestedAt\":null" in bareText, bareText)
    }

    @Test
    fun `모르는 판이나 판이 없는 기록은 읽지 않고 거부한다`() {
        val text = JudgmentRecordWire.encode(FULL)
        listOf(
            text.replace("\"schemaVersion\":\"5\"", "\"schemaVersion\":\"6\""),
            text.replace("\"schemaVersion\":\"5\",", ""),
            text.replace("\"schemaVersion\":\"5\"", "\"schemaVersion\":5"),
        ).forEach { wrong ->
            assertTrue(wrong != text, "시험의 전제: 바꿀 자리를 못 찾았다")
            val e = assertFailsWith<IllegalArgumentException>(wrong) { JudgmentRecordWire.decode(wrong) }
            assertTrue("모르는 판단 기록 판" in e.message!!, e.message)
        }
    }

    @Test
    fun `버전 1 기록은 건 취소 칸 없이 읽고 취소가 걸리지 않은 기록이 된다`() {
        val text = JudgmentRecordWire.encode(FULL)
        val v1 = withoutHeld(withoutPlaced(text.replace("\"schemaVersion\":\"5\"", "\"schemaVersion\":\"1\""))).replace(",\"cancelRequested\":true", "")
        assertTrue(v1 != text && "cancelRequested" !in v1 && "everHeld" !in v1, "시험의 전제: 버전 1 모양을 못 지었다")
        assertEquals(NEVER_HELD.copy(cancelRequested = false), JudgmentRecordWire.decode(v1))

        // 버전마다 칸 목록이 정확해야 한다 — 버전 1 에 그 칸이 있거나, 버전 3 에 그 칸이 없거나, 값이 참거짓이 아니면 거부한다.
        mapOf(
            v1.replace("\"units\":", "\"cancelRequested\":true,\"units\":") to "모르는 칸",
            text.replace(",\"cancelRequested\":true", "") to "칸이 없다",
            text.replace("\"cancelRequested\":true", "\"cancelRequested\":\"yes\"") to "참거짓이 아니다",
        ).forEach { (wrong, why) ->
            assertTrue(wrong != text && wrong != v1, "시험의 전제: 바꿀 자리를 못 찾았다")
            val e = assertFailsWith<IllegalArgumentException>(wrong) { JudgmentRecordWire.decode(wrong) }
            assertTrue(why in e.message!!, e.message)
        }
    }

    @Test
    fun `버전 2 기록은 단위의 파지 이력 칸 없이 읽고 쥔 것을 본 적 없는 단위가 된다`() {
        val text = JudgmentRecordWire.encode(FULL)
        val v2 = withoutHeld(withoutPlaced(text.replace("\"schemaVersion\":\"5\"", "\"schemaVersion\":\"2\"")))
        assertTrue(v2 != text && "everHeld" !in v2 && "heldInEarlierAttempt" !in v2, "시험의 전제: 버전 2 모양을 못 지었다")
        assertEquals(NEVER_HELD, JudgmentRecordWire.decode(v2))

        // 버전 2 에 그 칸이 있거나, 버전 3 에 그 칸이 없거나, 값이 참거짓이 아니면 거부한다.
        mapOf(
            v2.replace("\"decision\":null}", "\"decision\":null,\"everHeld\":true}") to "모르는 칸",
            text.replace(",\"heldInEarlierAttempt\":true", "") to "칸이 없다",
            text.replace("\"everHeld\":true", "\"everHeld\":1") to "참거짓이 아니다",
        ).forEach { (wrong, why) ->
            assertTrue(wrong != text && wrong != v2, "시험의 전제: 바꿀 자리를 못 찾았다")
            val e = assertFailsWith<IllegalArgumentException>(wrong) { JudgmentRecordWire.decode(wrong) }
            assertTrue(why in e.message!!, e.message)
        }
    }

    @Test
    fun `버전 3 기록은 위치 칸과 거부 주어 칸 없이 읽고 접수 승인과 주어 없는 판정이 된다`() {
        val text = JudgmentRecordWire.encode(FULL)
        assertTrue("\"position\":{\"orderVersion\":3,\"unitId\":\"s2\",\"attempt\":1}" in text, text)
        assertTrue("\"preconditionSubjects\":[\"HOLD\"]" in text, text)
        val v3 = withoutPlaced(text.replace("\"schemaVersion\":\"5\"", "\"schemaVersion\":\"3\""))
        assertTrue(v3 != text && "position" !in v3 && "preconditionSubjects" !in v3, "시험의 전제: 버전 3 모양을 못 지었다")
        assertEquals(UNPLACED, JudgmentRecordWire.decode(v3))

        // 버전 4 부터 두 칸이 비면 키를 싣지 않는다 — 그 모양은 앞 버전과 같고, 읽으면 위치 없음·주어 없음이다.
        val unplaced = JudgmentRecordWire.encode(UNPLACED)
        assertTrue("position" !in unplaced && "preconditionSubjects" !in unplaced, unplaced)
        assertEquals(UNPLACED, JudgmentRecordWire.decode(unplaced))
        assertEquals(UNPLACED, JudgmentRecordWire.decode(withoutPlaced(withoutIdentity(text))))

        // 버전 3 에 그 칸이 있으면 거부한다.
        mapOf(
            v3.replace("\"steps\":", "\"position\":null,\"steps\":") to "모르는 칸",
            v3.replace("\"result\":\"ref://m1\"", "\"result\":\"ref://m1\",\"preconditionSubjects\":[]") to "모르는 칸",
        ).forEach { (wrong, why) ->
            assertTrue(wrong != text && wrong != v3, "시험의 전제: 바꿀 자리를 못 찾았다")
            val e = assertFailsWith<IllegalArgumentException>(wrong) { JudgmentRecordWire.decode(wrong) }
            assertTrue(why in e.message!!, e.message)
        }
    }

    @Test
    fun `버전 4 기록은 보류 판정의 인시던트 정체성 칸 없이 읽고 정체성 없는 판정이 된다`() {
        // §15.322 — 판 4 로 다시 연 인시던트는 연결 불명이다. 판 5 는 정체성이 없으면 두 칸을 싣지 않아(판 4 의 두 칸과 같은 규칙) 판 4 와 같은 모양이다.
        val text = JudgmentRecordWire.encode(FULL)
        assertTrue("\"incidentKey\":\"mw-a/incident-3\",\"firstOpenedAt\":\"2026-10-14T00:00:04Z\"" in text, text)
        val v4 = withoutIdentity(text.replace("\"schemaVersion\":\"5\"", "\"schemaVersion\":\"4\""))
        assertTrue(v4 != text && "incidentKey" !in v4 && "firstOpenedAt" !in v4, "시험의 전제: 버전 4 모양을 못 지었다")
        assertEquals(UNIDENTIFIED, JudgmentRecordWire.decode(v4))

        val unidentified = JudgmentRecordWire.encode(UNIDENTIFIED)
        assertTrue("incidentKey" !in unidentified && "firstOpenedAt" !in unidentified, unidentified)
        assertEquals(UNIDENTIFIED, JudgmentRecordWire.decode(unidentified))

        // 버전 4 에 그 칸이 있거나, 버전 5 에 두 칸 가운데 하나만 있거나, 시각이 아니면 거부한다.
        mapOf(
            text.replace("\"schemaVersion\":\"5\"", "\"schemaVersion\":\"4\"") to "모르는 칸",
            text.replace(",\"firstOpenedAt\":\"2026-10-14T00:00:04Z\"", "") to "하나만",
            text.replace("\"incidentKey\":\"mw-a/incident-3\",", "") to "하나만",
            text.replace("\"firstOpenedAt\":\"2026-10-14T00:00:04Z\"", "\"firstOpenedAt\":\"어제\"") to "시각이 아니다",
        ).forEach { (wrong, why) ->
            assertTrue(wrong != text, "시험의 전제: 바꿀 자리를 못 찾았다")
            val e = assertFailsWith<IllegalArgumentException>(wrong) { JudgmentRecordWire.decode(wrong) }
            assertTrue(why in e.message!!, e.message)
        }
    }

    @Test
    fun `모르는 칸이나 빠진 칸이 있으면 거부한다`() {
        val text = JudgmentRecordWire.encode(FULL)
        mapOf(
            text.replace("\"taskId\":", "\"lease\":7,\"taskId\":") to "모르는 칸",
            text.replace("\"result\":\"ref://m1\"", "\"result\":\"ref://m1\",\"epoch\":3") to "모르는 칸",
            text.replace(",\"decision\":null", "") to "칸이 없다",
            text.replace("\"acknowledgedFaults\":[\"LOW_BATTERY[]\"],", "") to "칸이 없다",
        ).forEach { (wrong, why) ->
            assertTrue(wrong != text, "시험의 전제: 바꿀 자리를 못 찾았다")
            val e = assertFailsWith<IllegalArgumentException>(wrong) { JudgmentRecordWire.decode(wrong) }
            assertTrue(why in e.message!!, e.message)
        }
    }

    @Test
    fun `판정 칸에 정착하지 않은 상태나 모르는 값이 오면 거부한다`() {
        val text = JudgmentRecordWire.encode(FULL)
        listOf(
            text.replace("\"state\":\"DONE\"", "\"state\":\"RUNNING\""),
            text.replace("\"state\":\"DONE\"", "\"state\":\"FINISHED\""),
            text.replace("\"decision\":\"REWORK\"", "\"decision\":\"SKIP\""),
            text.replace("\"attempt\":2", "\"attempt\":2.5"),
            text.replace("\"at\":\"2026-10-14T00:00:30Z\"", "\"at\":\"어제\""),
        ).forEach { wrong ->
            assertTrue(wrong != text, "시험의 전제: 바꿀 자리를 못 찾았다")
            assertFailsWith<IllegalArgumentException>(wrong) { JudgmentRecordWire.decode(wrong) }
        }
    }

    private companion object {
        val T0: Instant = Instant.parse("2026-10-14T00:00:00Z")
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)

        /** 칸마다 값이 든 기록. 사람이 적은 글(메모)에 따옴표와 줄바꿈을 넣는다. */
        val FULL = JudgmentRecord(
            jobOrderId = "J-1",
            orderVersion = 3,
            acknowledgedFaults = listOf("LOW_BATTERY[]"),
            approval = RecordedApproval(
                robotId = "hum-02",
                approver = OPERATOR,
                at = T0,
                wallClockAt = T0.plusMillis(7),
                steps = listOf(ApprovedStep("pick_place", mapOf("object" to "A", "destination" to "S01"), "remedy-1-pick_place")),
                position = ProposalTarget.Hold(orderVersion = 3, unitId = "s2", attempt = 1),
            ),
            units = listOf(
                UnitRecord(
                    unitId = "s1",
                    attempt = 0,
                    taskId = "J-1#s1",
                    requestedAt = T0,
                    verifyWindow = null,
                    verdict = UnitVerdict(
                        UnitState.DONE, Evidence.E2, Verification.MATCHED, T0.plusSeconds(3), null, "operator \"confirmed\"\n두 줄", "ref://m1", listOf("HOLD"),
                        IncidentOrigin("mw-a/incident-3", T0.plusSeconds(4)),
                    ),
                    decision = null,
                    everHeld = true,
                ),
                UnitRecord(
                    unitId = "s2",
                    attempt = 2,
                    taskId = "J-1#s2@r2",
                    requestedAt = T0.plusSeconds(20),
                    verifyWindow = VerifyWindow(T0.plusSeconds(25), T0.plusSeconds(40)),
                    verdict = null,
                    decision = RecordedDecision(OperatorDecision.REWORK, OPERATOR, T0.plusSeconds(30), 1),
                    heldInEarlierAttempt = true,
                ),
            ),
            cancelRequested = true,
        )

        /** 판정의 정체성 칸이 없는 앞 버전(1~4)으로 읽으면 나와야 하는 기록 — 판정에 정체성이 없다. */
        val UNIDENTIFIED = FULL.copy(units = FULL.units.map { u -> u.copy(verdict = u.verdict?.copy(incident = null)) })

        /** 위치 칸과 거부 주어 칸이 없는 앞 버전(1·2·3)으로 읽으면 나와야 하는 기록 — 접수 승인이고 판정에 주어가 없다. */
        val UNPLACED = UNIDENTIFIED.copy(
            approval = FULL.approval!!.copy(position = null),
            units = UNIDENTIFIED.units.map { u -> u.copy(verdict = u.verdict?.copy(preconditionSubjects = emptyList())) },
        )

        /** 파지 이력 칸이 없는 앞 버전으로 읽으면 나와야 하는 기록 — 두 칸이 거짓인 것 말고는 [UNPLACED] 와 같다. */
        val NEVER_HELD = UNPLACED.copy(units = UNPLACED.units.map { it.copy(everHeld = false, heldInEarlierAttempt = false) })

        /** 승인의 위치 칸과 판정의 거부 주어 칸, 판정의 정체성 칸을 지운다 — 버전 3 까지의 모양이다. */
        fun withoutPlaced(text: String): String =
            withoutIdentity(text).replace(Regex(""","position":(null|\{[^}]*\})"""), "").replace(Regex(""","preconditionSubjects":\[[^\]]*]"""), "")

        /** 판정의 정체성 두 칸을 지운다 — 버전 4 까지의 모양이다. */
        fun withoutIdentity(text: String): String = text.replace(Regex(""","incidentKey":"[^"]*","firstOpenedAt":"[^"]*""""), "")

        /** 단위의 파지 이력 칸을 지운다 — 앞 버전의 모양이다. */
        fun withoutHeld(text: String): String = text.replace(Regex(",\"everHeld\":(true|false),\"heldInEarlierAttempt\":(true|false)"), "")
    }
}

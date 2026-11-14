package dev.picasso.middleware

import com.google.protobuf.Struct
import com.google.protobuf.util.JsonFormat
import dev.picasso.contracts.wire.ContractIdentity
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 승인 표면의 **규약**(ADR 44, `docs/orchestration.md` §7).
 *
 * `LedgerExport` 와 같은 성질이다 — 문자열만 다루므로 인프라 없이 시험이 문다. 여기서 고정하는 것은
 * 읽는 쪽이 맞출 칸이며, 이것이 없으면 밖은 인코더 소스를 읽어 추측으로 수신기를 만든다.
 */
class ApprovalWireTest {

    private fun parse(json: String): Map<String, com.google.protobuf.Value> =
        Struct.newBuilder().also { JsonFormat.parser().merge(json, it) }.build().fieldsMap

    // ── 요청

    @Test
    fun `요청은 가리킬 제안과 누가 누르는지만 싣는다`() {
        // ★★**값도 주문도 걸음도 실을 칸이 없다.** 그것이 이 표면의 요점이고, 칸이 없으므로 부르는
        //   쪽의 권한은 규율이 아니라 모양 때문에 «승인 시도 한 번» 을 못 넘는다.
        val attempt = ApprovalWire.decode(
            """{"approverId":"narrator-1","approverKind":"AGENT","robotId":"hum-02",""" +
                """"jobOrderId":"PATROL-1","sawSkillTypes":["pick_place"]}""",
        )
        assertEquals(Approver("narrator-1", ApproverKind.AGENT), attempt.approver)
        assertEquals("hum-02", attempt.robotId)
        assertEquals("PATROL-1", attempt.jobOrderId)
        assertEquals(listOf("pick_place"), attempt.sawSkillTypes)
    }

    @Test
    fun `자격 주장을 실어도 읽히지 않는다`() {
        // ★부르는 쪽이 «나는 자격이 있다» 고 적어도 판정에 닿는 길이 없다. 남는 칸은 신원뿐이고
        //   그것은 위조 가능한 조사 단서다(§15.3).
        val attempt = ApprovalWire.decode(
            """{"approverId":"narrator-1","approverKind":"AGENT","robotId":"hum-02","jobOrderId":"PATROL-1",""" +
                """"sawSkillTypes":["pick_place"],"entitled":true,"skillTypes":["아무거나"],"parameters":{"destination":"내가-정한-자리"}}""",
        )
        assertEquals(
            ApprovalAttempt(Approver("narrator-1", ApproverKind.AGENT), "hum-02", "PATROL-1", listOf("pick_place")),
            attempt,
            "규약 밖의 칸이 요청에 실려 들어왔다",
        )
    }

    @Test
    fun `못 읽는 요청은 거절이 아니라 오류다`() {
        // ★둘을 같은 모양으로 내면 읽는 쪽이 자기 오타와 «자격 없음» 을 구별하지 못한다.
        assertFailsWith<IllegalArgumentException> { ApprovalWire.decode("{") }
        assertFailsWith<IllegalArgumentException> {
            ApprovalWire.decode("""{"approverId":"a","approverKind":"AGENT","robotId":"r","jobOrderId":"j"}""")
        }
        assertFailsWith<IllegalArgumentException> {
            ApprovalWire.decode(
                """{"approverId":"a","approverKind":"로봇","robotId":"r","jobOrderId":"j","sawSkillTypes":[]}""",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ApprovalWire.decode(
                """{"approverId":"a","approverKind":"AGENT","robotId":"r","jobOrderId":"j","sawSkillTypes":"pick_place"}""",
            )
        }
    }

    // ── 답

    @Test
    fun `승인의 답이 실제로 나간 값을 싣는다`() {
        val json = encode(
            ApprovalOutcome.Approved(
                "exec-3",
                listOf(ApprovedStep("pick_place", mapOf("object_id" to "COVER-7", "destination" to "RACK-204.S01"), "remedy-1-pick_place")),
            ),
        )
        val fields = parse(json)
        assertEquals("APPROVED", fields.getValue("outcome").stringValue)
        assertEquals("exec-3", fields.getValue("executionId").stringValue)
        assertEquals(ApprovalWire.SCHEMA_VERSION, fields.getValue("schemaVersion").stringValue)
        assertEquals(ContractIdentity.semver, fields.getValue("contractSemver").stringValue)

        val step = fields.getValue("steps").listValue.getValues(0).structValue.fieldsMap
        assertEquals("pick_place", step.getValue("skillType").stringValue)
        val parameters = step.getValue("parameters").structValue.fieldsMap
        assertEquals("COVER-7", parameters.getValue("object_id").stringValue)
        assertEquals("RACK-204.S01", parameters.getValue("destination").stringValue)
    }

    @Test
    fun `거절의 답이 종류를 값으로 싣는다`() {
        // ★★사유 산문만 내면 읽는 쪽이 한국어를 문자열로 맞춰야 하고, 그 시험은 «거절됐다» 만 보므로
        //   아무 거절에나 초록이 된다 — 자격이 없어서인지 제안이 없어서인지 갈리지 않는다.
        val fields = parse(
            encode(ApprovalOutcome.Refused(ApprovalRefusal.NOT_DECLARED, "자동 승인 자격이 선언돼 있지 않다: narrator-1")),
        )
        assertEquals("REFUSED", fields.getValue("outcome").stringValue)
        assertEquals("NOT_DECLARED", fields.getValue("refusal").stringValue)
        assertTrue("narrator-1" in fields.getValue("reason").stringValue)
    }

    @Test
    fun `철회와 미선언이 서로 다른 값으로 나간다`() {
        // ★★**이것이 ADR 45 가 만든 갈래다.** 둘 다 「지금 자격이 없다」인데 다음 행동이 반대다 —
        //   철회는 사후 검토로, 미선언은 선언을 올리러. 같은 값으로 나가면 읽는 쪽이 못 가른다.
        fun refusalOf(refusal: ApprovalRefusal) =
            parse(encode(refused(refusal))).getValue("refusal").stringValue

        assertEquals("REVOKED", refusalOf(ApprovalRefusal.REVOKED))
        assertEquals("NOT_DECLARED", refusalOf(ApprovalRefusal.NOT_DECLARED))
        assertEquals("EXPIRED", refusalOf(ApprovalRefusal.EXPIRED))

        // 열거의 이름이 그대로 나간다. 여기서 값을 가공하면 읽는 쪽이 붙든 이름이 코드와 갈린다.
        val everyName = ApprovalRefusal.entries.map { it.name }.toSet()
        assertEquals(everyName, ApprovalRefusal.entries.map { refusalOf(it) }.toSet())
    }

    @Test
    fun `사람이 적은 글이 답을 깨지 않는다`() {
        // 진단 사유가 그대로 답에 실린다. 따옴표 하나만 새어도 그 답이 통째로 못 읽히고, 읽는 쪽에는
        // «부르면 깨진다» 로만 보인다.
        val nasty = "따옴표 \" 와 역슬래시 \\ 와 줄바꿈 \n 이 든 사유"
        val fields = parse(encode(ApprovalOutcome.Refused(ApprovalRefusal.WITHHELD, nasty)))
        assertEquals(nasty, fields.getValue("reason").stringValue)
    }

    @Test
    fun `모든 거절 종류가 답에 실릴 수 있다`() {
        // 종류가 늘었는데 답이 그것을 못 실으면 읽는 쪽이 모르는 값을 만나는 대신 **아무 값도** 못 받는다.
        ApprovalRefusal.entries.forEach { refusal ->
            val fields = parse(encode(refused(refusal)))
            assertEquals(refusal.name, fields.getValue("refusal").stringValue)
        }
    }

    // ── 소모(ADR 46)

    @Test
    fun `소모 거절만 소모 기록을 든다`() {
        // ★`CONSUMED` 인데 기록이 없으면 인코더가 «소모됐지만 누가인지 모른다» 를 정상 답처럼 낸다.
        assertFailsWith<IllegalArgumentException> { ApprovalOutcome.Refused(ApprovalRefusal.CONSUMED, "사유") }
        assertFailsWith<IllegalArgumentException> {
            ApprovalOutcome.Refused(ApprovalRefusal.NO_PROPOSAL, "사유", CONSUMED_SAMPLE)
        }
        ApprovalOutcome.Refused(ApprovalRefusal.CONSUMED, "사유", CONSUMED_SAMPLE)
    }

    @Test
    fun `소모 거절이 누가 언제 무엇을 보냈는지 싣는다`() {
        val fields = parse(encode(refused(ApprovalRefusal.CONSUMED)))
        // ★판은 글자로 댄다 — 상수끼리 대면 판을 안 올려도 초록이다.
        assertEquals("5", fields.getValue("schemaVersion").stringValue)
        assertEquals("CONSUMED", fields.getValue("refusal").stringValue)

        val consumed = fields.getValue("consumed").structValue.fieldsMap
        assertEquals("narrator-1", consumed.getValue("approverId").stringValue)
        assertEquals("AGENT", consumed.getValue("approverKind").stringValue)
        assertEquals("2026-09-06T00:00:01Z", consumed.getValue("at").stringValue)
        assertEquals("2026-10-01T07:12:44.120Z", consumed.getValue("wallClockAt").stringValue)
        assertEquals("exec-2", consumed.getValue("executionId").stringValue)
        val step = consumed.getValue("steps").listValue.getValues(0).structValue.fieldsMap
        assertEquals("pick_place", step.getValue("skillType").stringValue)
        val parameters = step.getValue("parameters").structValue.fieldsMap
        assertEquals("ENGINE-COVER-A", parameters.getValue("object_id").stringValue)
        assertEquals("DROP-01", parameters.getValue("destination").stringValue)
        // 접수 승인의 소모는 대상 칸을 빼지 않고 null 로 싣는다(§15.320).
        assertTrue(consumed.getValue("target").hasNullValue(), "접수 승인 소모의 target 이 null 이 아니다")
    }

    @Test
    fun `회복 승인의 소모는 보류 대상을 싣고 회복 제안의 거부 값이 바깥 형식에 실린다`() {
        // ★회복 입구는 같은 대상의 소모만 `CONSUMED` 로 받는다 — 밖의 문이 받은 소모가 어느 보류의 것인지 읽을 칸이다(§15.320).
        val hold = ProposalTarget.Hold(orderVersion = 3, unitId = "PUMP-01.travel", attempt = 0)
        val fields = parse(encode(ApprovalOutcome.Refused(ApprovalRefusal.CONSUMED, "사유", CONSUMED_SAMPLE.copy(target = hold))))
        val target = fields.getValue("consumed").structValue.fieldsMap.getValue("target").structValue.fieldsMap
        assertEquals(setOf("orderVersion", "unitId", "attempt"), target.keys)
        assertEquals(3.0, target.getValue("orderVersion").numberValue)
        assertEquals("PUMP-01.travel", target.getValue("unitId").stringValue)
        assertEquals(0.0, target.getValue("attempt").numberValue)
        val human = parse(encode(ApprovalOutcome.Refused(ApprovalRefusal.HUMAN_APPROVAL_REQUIRED, "사람만")))
        assertEquals("HUMAN_APPROVAL_REQUIRED", human.getValue("refusal").stringValue)
        assertEquals("5", human.getValue("schemaVersion").stringValue)
    }

    @Test
    fun `소모가 아닌 거절은 consumed 를 null 로 싣는다`() {
        // ★키를 빼면 «소모가 아니다» 와 «이 판이 그 칸을 모른다» 가 같은 모양이 된다.
        ApprovalRefusal.entries.filterNot { it == ApprovalRefusal.CONSUMED }.forEach { refusal ->
            val fields = parse(encode(ApprovalOutcome.Refused(refusal, "사유")))
            assertTrue("consumed" in fields, "$refusal 의 답에 consumed 키가 없다")
            assertTrue(fields.getValue("consumed").hasNullValue(), "$refusal 의 consumed 가 null 이 아니다")
        }
        // 승인 답은 이 칸을 안 싣는다 — 소모는 거절의 사정이다.
        val approved = parse(encode(ApprovalOutcome.Approved("exec-3", emptyList())))
        assertFalse("consumed" in approved, "승인 답에 consumed 가 실렸다")
    }

    // ── 결과 통보와 잇는 칸(ADR 48)

    @Test
    fun `답마다 그 답을 낸 인스턴스를 싣는다`() {
        // ★실행 식별자는 인스턴스 안의 셈이라 다시 뜨면 되풀이된다. 승인 답에만 빠지면 읽는 쪽은 결과 통보의
        //   `exec-2` 가 이번 승인의 것인지 앞 구동의 것인지 못 가른다. 거절에도 싣는다 — 다시 떴다는 신호다.
        val approved = parse(encode(ApprovalOutcome.Approved("exec-3", emptyList())))
        assertEquals("5", approved.getValue("schemaVersion").stringValue)
        assertEquals(INSTANCE, approved.getValue("instanceId").stringValue)
        ApprovalRefusal.entries.forEach { refusal ->
            assertEquals(INSTANCE, parse(encode(refused(refusal))).getValue("instanceId").stringValue, "$refusal")
        }
    }

    @Test
    fun `걸음마다 그 걸음이 선 단위를 싣는다`() {
        // ★결과 통보는 단위로 말한다(`completedUnits`). 걸음이 단위를 안 실으면 읽는 쪽은 이름 규칙을 추측해 잇는다.
        val approved = parse(
            encode(ApprovalOutcome.Approved("exec-3", listOf(ApprovedStep("pick_place", emptyMap(), "remedy-1-pick_place")))),
        )
        val step = approved.getValue("steps").listValue.getValues(0).structValue.fieldsMap
        assertEquals("remedy-1-pick_place", step.getValue("unitId").stringValue)

        val consumed = parse(encode(refused(ApprovalRefusal.CONSUMED))).getValue("consumed").structValue.fieldsMap
        val consumedStep = consumed.getValue("steps").listValue.getValues(0).structValue.fieldsMap
        assertEquals("remedy-1-pick_place", consumedStep.getValue("unitId").stringValue)
    }
}

/** 답을 낸 인스턴스 — 표본 값이다. */
private const val INSTANCE = "mw-test-instance"

private fun encode(outcome: ApprovalOutcome) = ApprovalWire.encode(outcome, INSTANCE)

/** 소모 기록 표본 — 칸마다 다른 값이라 어느 칸이 어디로 갔는지 가린다. */
private val CONSUMED_SAMPLE = ConsumedApproval(
    approver = Approver("narrator-1", ApproverKind.AGENT),
    at = Instant.parse("2026-09-06T00:00:01Z"),
    wallClockAt = Instant.parse("2026-10-01T07:12:44.120Z"),
    executionId = "exec-2",
    steps = listOf(ApprovedStep("pick_place", mapOf("destination" to "DROP-01", "object_id" to "ENGINE-COVER-A"), "remedy-1-pick_place")),
)

/** 모든 거절 종류로 답을 짓는다. `CONSUMED` 는 기록 없이 안 서므로 표본을 단다. */
private fun refused(refusal: ApprovalRefusal) =
    if (refusal == ApprovalRefusal.CONSUMED) {
        ApprovalOutcome.Refused(refusal, "사유", CONSUMED_SAMPLE)
    } else {
        ApprovalOutcome.Refused(refusal, "사유")
    }

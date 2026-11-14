package dev.picasso.middleware

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import dev.picasso.capability.PreconditionViolation
import dev.picasso.capability.Remedy
import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.Precondition
import dev.picasso.contracts.v1.PreconditionSubject
import dev.picasso.harness.Harness
import dev.picasso.host.BundleWriter
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 두 대장을 밖으로 내보내는 규약(`docs/orchestration.md` §6).
 *
 * **JSON 인지 아닌지를 눈으로 안 본다.** 줄마다 계약의 JSON 파서로 다시 읽어 구조로 단언한다 —
 * 그래야 따옴표 하나가 새는 것도, 키가 조용히 빠지는 것도 같은 시험이 문다.
 */
class LedgerExportTest {

    // ── 줄을 구조로 되읽는다

    private fun parse(line: String): Map<String, Value> {
        val b = Struct.newBuilder()
        JsonFormat.parser().merge(line, b)
        return b.build().fieldsMap
    }

    private fun lines(text: String): List<Map<String, Value>> =
        text.trim().lines().filter { it.isNotBlank() }.map { parse(it) }

    // ── 탐색 결과의 세 갈래

    @Test
    fun `가려 둔 것은 가렸다고만 적고 걸음 칸 자체가 없다`() {
        // ★적재면의 방벽이다. 여기서 편의로 제안 표의 걸음을 꺼내 실으면 **조회 한 번으로 가림이
        //   풀린다.** 값이 비는 것이 아니라 키가 없는 것이 답이다.
        val line = parse(LedgerExport.remedySearches(listOf(record(RemedyOutcome.Withheld))).trim())

        assertEquals("WITHHELD", line.getValue("outcome").stringValue)
        assertFalse("steps" in line, "가려 둔 제안의 걸음이 적재면으로 샜다: $line")
        assertFalse("cause" in line, "가린 것에 못 찾은 사유가 붙었다: $line")
    }

    @Test
    fun `대안 없음은 사유와 못 채운 조건을 함께 적는다`() {
        val outcome = RemedyOutcome.None(
            Remedy.None.Cause.DEPTH_LIMIT,
            listOf(PreconditionViolation(PreconditionSubject.PRECONDITION_SUBJECT_HOLD, HoldKind.HOLD_KIND_EMPTY, HoldKind.HOLD_KIND_HOLDING, "든 채다")),
        )
        val line = parse(LedgerExport.remedySearches(listOf(record(outcome))).trim())

        assertEquals("NONE", line.getValue("outcome").stringValue)
        assertEquals("DEPTH_LIMIT", line.getValue("cause").stringValue)
        val unmet = line.getValue("unmet").listValue.valuesList.single().structValue.fieldsMap
        assertEquals("HOLD_KIND_EMPTY", unmet.getValue("required").stringValue)
        assertEquals("든 채다", unmet.getValue("detail").stringValue)
    }

    @Test
    fun `출발 결품은 무엇이 어디에 없었고 어디에 있는지를 적는다`() {
        val outcome = RemedyOutcome.SourceMissing(
            material = "ENGINE-COVER-B",
            source = "SEQ-IN-03.BIN-A",
            observed = null,
            alternatives = listOf("SEQ-IN-03.BIN-B"),
        )
        val line = parse(LedgerExport.remedySearches(listOf(record(outcome))).trim())

        assertEquals("SOURCE_MISSING", line.getValue("outcome").stringValue)
        assertEquals("ENGINE-COVER-B", line.getValue("material").stringValue)
        assertEquals("SEQ-IN-03.BIN-A", line.getValue("source").stringValue)
        assertEquals(
            listOf("SEQ-IN-03.BIN-B"),
            line.getValue("alternatives").listValue.valuesList.map { it.stringValue },
        )

        // ★**승인할 것이 없다.** 걸음을 실으면 읽는 쪽이 이것을 조치 열로 읽고, 승인 API 에는
        //   그 주문을 받을 자리가 없다. 가림의 방벽과 같은 모양이되 이유가 다르다.
        assertFalse("steps" in line, "승인할 수 없는 답에 걸음이 실렸다: $line")
    }

    @Test
    fun `출발 결품에서 못 물어본 것과 든 자리가 없는 것이 갈린다`() {
        // ★**널과 빈 목록을 접으면 못 물어본 것이 재고 부족으로 읽힌다.** 키 집합 대조로는 이 둘이
        //   같은 모양이라(§15.177) 값으로 본다.
        fun alternativesOf(known: List<String>?) = parse(
            LedgerExport.remedySearches(
                listOf(record(RemedyOutcome.SourceMissing("ENGINE-COVER-B", "SEQ-IN-03.BIN-A", null, known))),
            ).trim(),
        ).getValue("alternatives")

        assertTrue(alternativesOf(null).hasNullValue(), "셀이 답을 안 한 것이 빈 목록으로 접혔다")
        assertEquals(0, alternativesOf(emptyList()).listValue.valuesCount, "든 자리가 없다는 답이 널로 접혔다")

        // 빈 자리와 «다른 것이 있다» 도 같은 규율로 갈린다.
        fun observedOf(seen: String?) = parse(
            LedgerExport.remedySearches(
                listOf(record(RemedyOutcome.SourceMissing("ENGINE-COVER-B", "SEQ-IN-03.BIN-A", seen, emptyList()))),
            ).trim(),
        ).getValue("observed")

        assertTrue(observedOf(null).hasNullValue(), "빈 자리가 빈 문자열로 접혔다")
        assertEquals("ENGINE-COVER-A", observedOf("ENGINE-COVER-A").stringValue)
    }

    @Test
    fun `대안 있음은 걸음과 그 걸음의 전제를 적는다`() {
        val step = RemedyStep(
            skillType = "pick_place",
            requires = listOf(Precondition.newBuilder().setSubject(PreconditionSubject.PRECONDITION_SUBJECT_HOLD).setRequires(HoldKind.HOLD_KIND_HOLDING).build()),
            expectedHold = HoldKind.HOLD_KIND_EMPTY,
            onFailureHold = HoldKind.HOLD_KIND_HOLDING,
        )
        val line = parse(LedgerExport.remedySearches(listOf(record(RemedyOutcome.Found(listOf(step))))).trim())

        assertEquals("FOUND", line.getValue("outcome").stringValue)
        val steps = line.getValue("steps").listValue.valuesList.single().structValue.fieldsMap
        assertEquals("pick_place", steps.getValue("skillType").stringValue)
        assertEquals("HOLD_KIND_EMPTY", steps.getValue("expectedHold").stringValue)
        val requires = steps.getValue("requires").listValue.valuesList.single().structValue.fieldsMap
        assertEquals("PRECONDITION_SUBJECT_HOLD", requires.getValue("subject").stringValue)
    }

    // ── 없음을 키 누락으로 적지 않는다

    @Test
    fun `없는 값은 키를 빼지 않고 널로 적는다`() {
        // ★키를 빼면 «값이 없다» 와 «이 판이 아직 그 필드를 안 낸다» 가 같은 모양이 된다.
        val line = parse(LedgerExport.incidents(listOf(bundle())).trim())

        listOf("failureClass", "expectedHold", "effectMismatch", "review").forEach {
            assertTrue(it in line, "없는 값의 키가 빠졌다: $it")
            assertTrue(line.getValue(it).hasNullValue(), "$it 가 널이 아니다: ${line[it]}")
        }
        assertEquals(0, line.getValue("blockedBy").listValue.valuesCount)
        assertTrue("blockedBy" in line, "빈 목록의 키가 빠졌다")
    }

    @Test
    fun `말하지 않은 파지가 통째로 사라지지 않는다`() {
        // ★protobuf JSON 의 기본은 기본값 필드를 빼는 것이라, 그대로 쓰면 `HoldState` 가
        //   UNSPECIFIED 일 때 `{}` 가 된다 — **빈손과 침묵이 접힌다**(§15.145).
        val line = parse(LedgerExport.incidents(listOf(bundle())).trim())
        val hold = line.getValue("residualHold").structValue.fieldsMap

        assertEquals("HOLD_KIND_UNSPECIFIED", hold.getValue("kind").stringValue, "말하지 않은 파지가 접혔다: $hold")
        assertTrue("objectRef" in hold, "빈 문자열 필드가 빠졌다: $hold")
        assertTrue("reason" in hold, "빈 문자열 필드가 빠졌다: $hold")
    }

    @Test
    fun `사람이 적은 글이 줄을 깨지 않는다`() {
        // 진단 사유와 검토 의견은 사람이 적는다. 따옴표 하나가 새면 그 줄이 통째로 못 읽히고,
        // 읽는 쪽에는 «한 줄이 사라졌다» 로만 보인다.
        val nasty = "그리퍼에 \"부품\"이 남았다\n줄바꿈\t탭 \\역슬래시"
        val line = parse(LedgerExport.incidents(listOf(bundle(review = IncidentReview(ReviewVerdict.DISPUTED, nasty, AT)))).trim())

        assertEquals(nasty, line.getValue("review").structValue.fieldsMap.getValue("cause").stringValue)
    }

    @Test
    fun `되짚지 않고 읽을 칸이 값까지 실려 나간다`() {
        // ★★**키만 맞대는 시험은 값이 비는 것을 못 본다.** 인코더가 벤더 원문을 빈 문자열로 내도
        //   키는 그대로 있고, 인계본은 이미 찍힌 스냅샷이라 안 바뀐다 — 그 사이로 새는 자리가 있었다.
        val line = parse(LedgerExport.incidents(listOf(bundle(fault = FAULT))).trim())

        assertEquals(ROBOT, line.getValue("robotId").stringValue, "어느 기체인지가 값으로 안 나갔다")
        assertEquals("E2", line.getValue("requiredEvidence").stringValue)
        assertEquals("E0", line.getValue("reachedEvidence").stringValue)
        assertEquals("NOT_REQUESTED", line.getValue("verification").stringValue)

        val fault = line.getValue("fault").structValue.fieldsMap
        assertEquals("GRASP_FAILED", fault.getValue("failureClass").stringValue)
        assertEquals("X_FIXTURE_GRIPPER_SLIP", fault.getValue("errorType").stringValue)
        assertEquals("X_FIXTURE_GRIPPER_SLIP", fault.getValue("vendorDetail").stringValue, "벤더 원문이 값으로 안 나갔다")
        assertEquals("패드를 점검하라", fault.getValue("errorHint").stringValue)
        val reference = fault.getValue("references").listValue.getValues(0).structValue.fieldsMap
        assertEquals("KEY_SKILL_ID", reference.getValue("key").stringValue)
        assertEquals("pick_place", reference.getValue("value").stringValue)

        val step = line.getValue("step").structValue.fieldsMap
        assertEquals(2.0, step.getValue("at").numberValue)
        assertEquals(2, step.getValue("plan").listValue.valuesCount, "계획이 값으로 안 나갔다")
        assertEquals(1, step.getValue("completed").listValue.valuesCount)

        assertEquals("ROBOT", line.getValue("route").stringValue, "누구의 일인지가 값으로 안 나갔다")

        val intent = line.getValue("intent").structValue.fieldsMap
        assertEquals("PrepareSequencedRack", intent.getValue("workMasterId").stringValue)
        assertEquals("pick_place", intent.getValue("skillType").stringValue)
        assertEquals("RACK-204.S01", intent.getValue("destination").stringValue)
        assertEquals("ENGINE-COVER-A", intent.getValue("expectedIdentity").stringValue)
        assertEquals("PT30S", intent.getValue("evidenceWindowBefore").stringValue, "창이 왜 그 범위였는지가 안 나갔다")
        assertEquals(
            "SEQ-IN-02.BIN-A",
            intent.getValue("unitParameters").structValue.fieldsMap.getValue("object_id").stringValue,
            "단위에 실린 값이 안 나갔다",
        )
        assertEquals(
            1.0,
            intent.getValue("materials").listValue.getValues(0).structValue.fieldsMap.getValue("quantity").numberValue,
        )

        val observation = line.getValue("observation").structValue.fieldsMap
        assertTrue(observation.getValue("linkBroken").hasBoolValue())
        // ★★**3값이다.** 널을 거짓으로 접으면 «못 물어봤다» 가 «못 잰다» 가 된다.
        assertTrue(observation.getValue("progressObservable").hasNullValue(), "3값의 널이 접혔다")
        assertEquals(0, observation.getValue("lateEvents").listValue.valuesCount)
    }

    @Test
    fun `사건에 대해 할 말을 바꾸는 값은 해시를 바꾼다`() {
        // ★★**이 목록이 곧 「무엇이 결정적인가」의 답이다.** 칸이 늘 때마다 여기 한 줄을 더해야
        //   «해시에 넣었다» 가 말로만 남지 않는다 — 실제로 빠뜨린 적이 있다(경로가 그랬다).
        val b = bundle(fault = FAULT)
        val changed = mapOf(
            "기체" to b.copy(robotId = "다른-기체"),
            "경로" to b.copy(route = "FLEET"),
            "요구 등급" to b.copy(requiredEvidence = Evidence.E0),
            "도달 등급" to b.copy(reachedEvidence = Evidence.E2),
            "설비 대조" to b.copy(verification = Verification.MISMATCH),
            "분류의 근거" to b.copy(fault = FAULT.copy(vendorDetail = "다른-원문")),
            "막는 결함" to b.copy(blockedBy = listOf(FAULT)),
            "걸음 위치" to b.copy(step = b.step.copy(at = 1)),
            "의도" to b.copy(intent = b.intent.copy(destination = "다른-자리")),
            "임무 버전" to b.copy(intent = b.intent.copy(missionVersion = 2)),
            "관측 신뢰" to b.copy(observation = b.observation.copy(linkBroken = true)),
        )

        changed.forEach { (what, other) ->
            assertTrue(b.digest() != other.digest(), "$what 가 해시에 안 들어갔다")
        }
    }

    @Test
    fun `사건 사이의 연결과 판단자는 해시를 바꾸지 않는다`() {
        // ★판 8 의 연결 칸(§15.322)은 사건의 내용이 아니다. 해시에 넣으면 같은 보류가 재기동마다 다른 요약을 갖는 것은 그대로이고, 같은 시드의
        //   기존 요약까지 움직여 인계 번들의 요약이 판 7 과 갈린다.
        val b = bundle()
        val same = mapOf(
            "정체성 열쇠" to b.copy(origin = IncidentOrigin("mw-다른/incident-9", AT.plusSeconds(60))),
            "연결 불명" to b.copy(origin = null, openingKind = OpeningKind.REOPENED),
            "다시 연 것" to b.copy(openingKind = OpeningKind.REOPENED),
            "시도" to b.copy(attempt = 3),
            "판단자" to b.copy(resolution = IncidentResolution(IncidentDecision.REWORK, AT, AT, Approver("op-9", ApproverKind.PERSON))),
        )
        same.forEach { (what, other) -> assertEquals(b.digest(), other.digest(), "$what 가 해시에 들었다") }
    }

    @Test
    fun `정체성 넷과 판단자와 임무·설정 칸이 널도 키를 빼지 않고 실린다`() {
        val opened = parse(LedgerExport.incidents(listOf(bundle())).trim())
        assertEquals("mw-test/incident-1", opened.getValue("incidentKey").stringValue)
        assertEquals(AT.toString(), opened.getValue("firstOpenedAt").stringValue)
        assertEquals("NEW", opened.getValue("openingKind").stringValue)
        assertEquals(0.0, opened.getValue("attempt").numberValue)
        val intent = opened.getValue("intent").structValue.fieldsMap
        listOf("missionVersion", "siteSettingsVersion", "inDoubtGrace", "stallWindow").forEach {
            assertTrue(intent.getValue(it).hasNullValue(), "$it 가 널로 안 실렸다")
        }

        // 연결 불명은 두 칸이 널이고 «다시 열었다» 는 그대로 실린다.
        val unknown = parse(LedgerExport.incidents(listOf(bundle().copy(origin = null, openingKind = OpeningKind.REOPENED, attempt = 2))).trim())
        assertTrue(unknown.getValue("incidentKey").hasNullValue() && unknown.getValue("firstOpenedAt").hasNullValue())
        assertEquals("REOPENED", unknown.getValue("openingKind").stringValue)
        assertEquals(2.0, unknown.getValue("attempt").numberValue)

        val set = bundle().let { it.copy(intent = it.intent.copy(missionVersion = 4, siteSettingsVersion = 7, inDoubtGrace = "PT20S", stallWindow = "PT40S")) }
        val withSettings = parse(LedgerExport.incidents(listOf(set)).trim()).getValue("intent").structValue.fieldsMap
        assertEquals(4.0, withSettings.getValue("missionVersion").numberValue)
        assertEquals(7.0, withSettings.getValue("siteSettingsVersion").numberValue)
        assertEquals("PT20S" to "PT40S", withSettings.getValue("inDoubtGrace").stringValue to withSettings.getValue("stallWindow").stringValue)

        val decided = bundle().copy(resolution = IncidentResolution(IncidentDecision.OPERATOR_CANCEL, AT, AT, Approver("op-9", ApproverKind.PERSON)))
        val by = parse(LedgerExport.incidents(listOf(decided)).trim()).getValue("resolution").structValue.fieldsMap.getValue("decidedBy").structValue.fieldsMap
        assertEquals("op-9" to "PERSON", by.getValue("id").stringValue to by.getValue("kind").stringValue)
    }

    @Test
    fun `현장 설정 셋은 함께 널이거나 함께 있다`() {
        // 버전만 있고 값이 빠진 의도는 서지 않는다 — 내보내기 줄이 «설정은 있었는데 그 값은 모른다» 를 지어내게 된다.
        val i = bundle().intent
        assertFailsWith<IllegalArgumentException> { i.copy(siteSettingsVersion = 7) }
        assertFailsWith<IllegalArgumentException> { i.copy(inDoubtGrace = "PT20S", stallWindow = "PT40S") }
        assertFailsWith<IllegalArgumentException> { i.copy(siteSettingsVersion = 7, inDoubtGrace = "PT20S") }
    }

    @Test
    fun `새로 선 인시던트는 정체성 없이 서지 않는다`() {
        assertFailsWith<IllegalArgumentException> { bundle().copy(origin = null) }
    }

    @Test
    fun `출발 미관측과 목적지 점유·미관측은 갈래마다 제 값을 적고 걸음 칸이 없다`() {
        // 판 8 의 탐색 새 갈래(§15.322). 승인할 조치가 아니므로 `steps` 키가 없다 — 키를 맞추려고 빈 배열을 적으면 «걸음이 빈 조치» 로 읽힌다.
        val silent = parse(LedgerExport.remedySearches(listOf(record(SourceUnobserved("ENGINE-COVER-A", "SEQ-IN-02.BIN-A", null)))).trim())
        assertEquals("SOURCE_UNOBSERVED", silent.getValue("outcome").stringValue)
        assertEquals("ENGINE-COVER-A" to "SEQ-IN-02.BIN-A", silent.getValue("material").stringValue to silent.getValue("source").stringValue)
        assertTrue(silent.getValue("occupied").hasNullValue(), "설비의 침묵이 널로 안 실렸다")
        val bare = parse(LedgerExport.remedySearches(listOf(record(SourceUnobserved(null, "SEQ-IN-02.BIN-A", true)))).trim())
        assertTrue(bare.getValue("occupied").boolValue, "«있다» 고만 한 것이 안 실렸다")
        assertTrue(bare.getValue("material").hasNullValue())

        val occupied = parse(LedgerExport.remedySearches(listOf(record(RemedyOutcome.DestinationOccupied("RACK-204.S01", null)))).trim())
        assertEquals("DESTINATION_OCCUPIED" to "RACK-204.S01", occupied.getValue("outcome").stringValue to occupied.getValue("destination").stringValue)
        assertTrue(occupied.getValue("observed").hasNullValue(), "점유 신원 불명이 널로 안 실렸다")
        val unobserved = parse(LedgerExport.remedySearches(listOf(record(RemedyOutcome.DestinationUnobserved("RACK-204.S01")))).trim())
        assertEquals("DESTINATION_UNOBSERVED" to "RACK-204.S01", unobserved.getValue("outcome").stringValue to unobserved.getValue("destination").stringValue)

        listOf(silent, bare, occupied, unobserved).forEach { assertTrue("steps" !in it.keys, "승인할 조치가 아닌 줄에 걸음 칸이 섰다: $it") }
    }

    @Test
    fun `맵의 순회 순서가 해시를 바꾸지 않는다`() {
        // ★의도가 맵 둘을 데려온다. 순회 순서가 해시를 바꾸면 **같은 사건이 두 값을 갖고**, 읽는 쪽의
        //   멱등이 그 위에 서 있으므로 같은 사건을 두 번 처리한다.
        val forward = bundle().let { it.copy(intent = it.intent.copy(unitParameters = linkedMapOf("a" to "1", "b" to "2"))) }
        val backward = bundle().let { it.copy(intent = it.intent.copy(unitParameters = linkedMapOf("b" to "2", "a" to "1"))) }

        assertEquals(forward.intent.unitParameters, backward.intent.unitParameters, "이 시험의 전제가 비었다")
        assertEquals(forward.digest(), backward.digest(), "맵의 순회 순서가 해시를 바꿨다")
    }

    @Test
    fun `번들의 해시를 함께 적는다`() {
        // 읽는 쪽이 같은 사건을 두 번 받았는지 가르는 유일한 결정적 열쇠다.
        val b = bundle()
        assertEquals(b.digest(), parse(LedgerExport.incidents(listOf(b)).trim()).getValue("digest").stringValue)
    }

    // ── 구동 식별자

    @Test
    fun `두 구동은 같은 시계에서도 다른 식별자를 갖는다`() {
        // ★★**이 값 하나만 결정적이지 않다.** 나머지는 같은 시드면 같은 값이고 그것이 재현의 근거인데,
        //   읽는 쪽이 멱등 열쇠를 그 값들로 들면 두 번째 구동이 전부 «이미 본 것» 으로 접혀
        //   **아무 신호 없이** 처리가 사라진다. 결정적으로 바꾸면 그 고장이 조용히 돌아온다.
        assertNotEquals(LedgerExport.newRunId(AT), LedgerExport.newRunId(AT))
    }

    @Test
    fun `한 벌의 명세가 구동 식별자와 개수를 싣는다`() {
        val runId = LedgerExport.newRunId(AT)
        val m = parse(LedgerExport.manifest(runId, AT, AT.plusSeconds(5), incidents = 2, remedySearches = 3, instanceId = "mw-test"))

        assertEquals(LedgerExport.SCHEMA_VERSION, m.getValue("schemaVersion").stringValue)
        assertEquals(runId, m.getValue("runId").stringValue)
        // 한 벌은 한 인스턴스의 것이다(판 8, §15.322) — 인시던트 id 는 그 인스턴스 안의 셈이다.
        assertEquals("mw-test", m.getValue("instanceId").stringValue)
        assertEquals(AT.plusSeconds(5).toString(), m.getValue("virtualNow").stringValue)
        val counts = m.getValue("counts").structValue.fieldsMap
        assertEquals(2.0, counts.getValue("incidents").numberValue)
        assertEquals(3.0, counts.getValue("remedySearches").numberValue)
    }

    // ── 한 벌을 실제로 내보낸다

    @Test
    fun `시나리오를 돌리면 세 파일이 한 벌로 나온다`() {
        // 사건 하나와 탐색 하나를 만들어 내보낸다. **두 대장을 합치지 않는다** — 읽는 쪽이 갈래를
        // 다시 나눠야 하고, 그러면 그 분류가 두 곳에 생긴다.
        World().use { w ->
            val order = rack()
            order.equipmentRequirements.filter { it.equipmentUse == "destination" }
                .forEach { w.cell.program(it.id, it.properties["material"]) }
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT)).execution
            w.drive { exec.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING }

            // 든 채로 점검 순회를 밀어 넣는다 — 탐색이 여기서 돈다. **둘을 넣는 이유는 순서다** —
            // 하나뿐이면 순서를 뒤집어도 같은 값이라 그 단언이 아무것도 안 문다.
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-1"), ROBOT))
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-2"), ROBOT))
            // 적재를 잃는다 — 사건이 여기서 열린다.
            w.forceFault("PAYLOAD_LOST", exec.units.first().taskId)
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            val dir = w.export()

            val manifest = parse(Files.readString(dir.resolve(LedgerExport.MANIFEST)))
            val incidents = lines(Files.readString(dir.resolve(LedgerExport.INCIDENTS)))
            val searches = lines(Files.readString(dir.resolve(LedgerExport.REMEDY_SEARCHES)))

            assertTrue(incidents.isNotEmpty(), "사건이 안 나왔다")
            assertTrue(searches.isNotEmpty(), "탐색 결과가 안 나왔다")
            assertEquals(incidents.size.toDouble(), manifest.getValue("counts").structValue.fieldsMap.getValue("incidents").numberValue)
            assertEquals(searches.size.toDouble(), manifest.getValue("counts").structValue.fieldsMap.getValue("remedySearches").numberValue)
            assertTrue(manifest.getValue("runId").stringValue.isNotBlank(), "구동 식별자가 비었다")

            // 순서가 보증된다. 읽는 쪽은 식별자를 뜯지 않고 이 순서에 기댄다.
            assertContentEquals(w.mw.incidents().map { it.incidentId }, incidents.map { it.getValue("incidentId").stringValue })
            assertContentEquals(w.mw.remedySearches().map { it.searchId }, searches.map { it.getValue("searchId").stringValue })
        }
    }

    // ── 재료

    private fun record(outcome: RemedyOutcome) =
        RemedySearchRecord("search-1", ROBOT, "PATROL-1", AT, AT, outcome)

    private val FAULT = FaultDetail(
        failureClass = "GRASP_FAILED",
        errorType = "X_FIXTURE_GRIPPER_SLIP",
        vendorDetail = "X_FIXTURE_GRIPPER_SLIP",
        errorHint = "패드를 점검하라",
        references = listOf(FaultReference("KEY_SKILL_ID", "pick_place")),
        canContinueCurrentTask = false,
        canAcceptNewTask = false,
        activeUntilKind = "KIND_UNTIL_CLEARED",
        activeUntilTime = "",
    )

    private fun bundle(review: IncidentReview? = null, fault: FaultDetail? = null) = IncidentBundle(
        incidentId = "incident-1",
        origin = IncidentOrigin("mw-test/incident-1", AT),
        openingKind = OpeningKind.NEW,
        attempt = 0,
        jobOrderId = "SEQ-204",
        executionId = "exec-1",
        robotId = ROBOT,
        unitId = "RACK-204.S01",
        at = AT,
        wallClockAt = AT,
        failureClass = fault?.failureClass,
        fault = fault,
        blockedBy = emptyList(),
        residualHold = HoldState.getDefaultInstance(),
        unresolved = false,
        preconditionSubjects = emptyList(),
        evidenceWindow = emptyList(),
        windowTruncated = false,
        expectedHold = null,
        observedHold = HoldKind.HOLD_KIND_EMPTY,
        effectMismatch = null,
        requiredEvidence = Evidence.E2,
        reachedEvidence = Evidence.E0,
        verification = Verification.NOT_REQUESTED,
        step = StepPosition(2, listOf("RACK-204.S01", "RACK-204.S02"), listOf("RACK-204.S01")),
        route = "ROBOT",
        intent = Intent(
            workMasterId = "PrepareSequencedRack",
            orderVersion = 17,
            orderParameters = mapOf("priority" to "normal"),
            materials = listOf(MaterialRequirement("ENGINE-COVER-A", 1)),
            equipment = listOf(EquipmentRequirement("RACK-204.S01", "destination", mapOf("material" to "ENGINE-COVER-A"))),
            capabilityMaxEvidence = Evidence.E2,
            evidenceWindowBefore = "PT30S",
            evidenceWindowAfter = "PT15S",
            skillType = "pick_place",
            unitParameters = mapOf("destination" to "RACK-204.S01", "object_id" to "SEQ-IN-02.BIN-A"),
            source = "SEQ-IN-02.BIN-A",
            destination = "RACK-204.S01",
            expectedIdentity = "ENGINE-COVER-A",
        ),
        observation = ObservationTrust(
            linkBroken = false,
            lateEvents = emptyList(),
            progressObservable = null,
            progressStalled = false,
        ),
        profileRevision = 1,
        contractSemver = "0.9.0",
        review = review,
    )

    private class World : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to PRECOND))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() })

        fun drive(rounds: Int = 80, step: Duration = Duration.ofSeconds(1), until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(step)
                Thread.sleep(40)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다")
        }

        fun forceFault(errorType: String, taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType(errorType).setTaskId(taskId).build(),
        ).taskState

        /**
         * 두 대장을 파일로 낸다. 절차는 [BundleWriter] 한 벌뿐이다 — 임시 이름으로 쓰고 이름만 바꾸며
         * 명세가 맨 마지막이다. **앞 판은 그 절차를 여기에 따로 적었다**(§15.193).
         */
        fun export(dir: Path = Path.of("build", "export")): Path {
            check(BundleWriter(dir, mw.instanceId).snapshot(mw.incidents(), mw.remedySearches(), harness.clock.now())) {
                "$dir 에 한 벌을 안 썼다"
            }
            return dir
        }

        override fun close() = harness.close()
    }

    private fun rack() = JobOrder(
        jobOrderId = "SEQ-204",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = 17,
        requiredEvidence = Evidence.E2,
        materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", 2)),
        equipmentRequirements = listOf(
            EquipmentRequirement("RACK-204.S01", "destination", mapOf("material" to "ENGINE-COVER-A")),
            EquipmentRequirement("RACK-204.S02", "destination", mapOf("material" to "ENGINE-COVER-A")),
            EquipmentRequirement("SEQ-IN-02.BIN-A", "source", mapOf("material" to "ENGINE-COVER-A")),
        ),
    )

    private fun patrol(jobOrderId: String) = JobOrder(
        jobOrderId = jobOrderId,
        workMasterId = InspectAsset.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E0,
        equipmentRequirements = listOf(
            EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1")),
        ),
    )

    companion object {
        const val ROBOT = "hum-02"
        private val AT: Instant = Instant.parse("2026-09-05T00:03:21Z")
        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()
    }
}

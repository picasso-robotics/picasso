package dev.picasso.middleware

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import dev.picasso.capability.PreconditionViolation
import dev.picasso.capability.Remedy
import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.PreconditionSubject
import dev.picasso.contracts.wire.ContractIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `handoff/narrator/` 에 **커밋된 한 벌이 지금의 인코더와 같은 칸을 쓰는가.**
 *
 * ## 스냅샷은 낡는다
 *
 * 인계 지점의 한 벌은 한 번 찍은 것이라, 인코더가 칸을 늘리거나 줄여도 그대로 남는다. 그러면 받는
 * 쪽은 **없는 계약에 맞춘 수신기**를 들고 있게 되고, 그 어긋남은 실물을 다시 받는 날까지 안 보인다.
 * 그래서 커밋된 한 벌을 지금의 인코더로 다시 재서 **칸이 같은지**만 본다.
 *
 * **값은 안 본다.** `runId` 와 `wallClockAt` 은 구동마다 다르고 그것이 설계다 — 값을 고정하면 이
 * 시험이 그 설계와 싸운다. 보는 것은 **키의 집합**과 판 표기뿐이다.
 */
class HandoffFixtureTest {

    private fun parse(line: String): Map<String, Value> {
        val b = Struct.newBuilder()
        JsonFormat.parser().merge(line, b)
        return b.build().fieldsMap
    }

    private fun lines(run: String, name: String): List<String> =
        Files.readString(HANDOFF.resolve(run).resolve(name)).trim().lines().filter { it.isNotBlank() }

    private fun keys(line: String): Set<String> = parse(line).keys

    // ── 커밋된 한 벌이 지금의 인코더와 같은 칸을 쓴다

    @Test
    fun `인계한 사건 줄이 지금 인코더와 같은 칸을 쓴다`() {
        val expected = keys(LedgerExport.incidents(listOf(bundle())).trim())
        RUNS.forEach { run ->
            lines(run, LedgerExport.INCIDENTS).forEachIndexed { at, line ->
                assertEquals(expected, keys(line), "$run 의 ${at + 1}번째 사건 줄이 지금의 칸과 다르다")
            }
        }
    }

    @Test
    fun `인계한 탐색 줄이 갈래마다 지금 인코더와 같은 칸을 쓴다`() {
        val expected = mapOf(
            "FOUND" to keys(LedgerExport.remedySearches(listOf(record(RemedyOutcome.Found(listOf(STEP))))).trim()),
            "NONE" to keys(LedgerExport.remedySearches(listOf(record(NONE))).trim()),
            "WITHHELD" to keys(LedgerExport.remedySearches(listOf(record(RemedyOutcome.Withheld))).trim()),
            "SOURCE_MISSING" to keys(LedgerExport.remedySearches(listOf(record(SOURCE_MISSING))).trim()),
        )
        RUNS.forEach { run ->
            lines(run, LedgerExport.REMEDY_SEARCHES).forEach { line ->
                val tag = parse(line).getValue("outcome").stringValue
                assertEquals(expected.getValue(tag), keys(line), "$run 의 $tag 줄이 지금의 칸과 다르다")
            }
        }
    }

    @Test
    fun `인계한 명세가 지금의 판과 계약 버전을 댄다`() {
        // 판이 올라갔는데 인계한 한 벌이 옛 판이면, 받는 쪽은 멈추는 대신 조용히 옛 규약으로 읽는다.
        RUNS.forEach { run ->
            val m = parse(Files.readString(HANDOFF.resolve(run).resolve(LedgerExport.MANIFEST)))
            assertEquals(LedgerExport.SCHEMA_VERSION, m.getValue("schemaVersion").stringValue, "$run 의 판이 낡았다")
            assertEquals(ContractIdentity.semver, m.getValue("contractSemver").stringValue, "$run 의 계약 버전이 낡았다")
        }
    }

    // ── 두 벌이 있는 이유

    @Test
    fun `두 벌의 해시가 같고 구동 식별자만 다르다`() {
        // ★이것이 `runId` 가 존재하는 이유다. 받는 쪽이 이 두 벌로 «둘째 구동이 새 사건으로 들어온다» 를
        //   회귀로 박으므로, 두 벌이 이 성질을 잃으면 그 회귀가 아무것도 안 막는다.
        //
        // **재실행 쌍에만 건다.** `run-3` 은 재실행이 아니라 다른 시나리오이고, 여기에 끼면 이 시험이
        // «같은 시드의 두 구동» 이 아니라 «모든 벌이 같다» 를 주장하게 된다.
        val digests = REPLAY.map { run ->
            lines(run, LedgerExport.INCIDENTS).map { parse(it).getValue("digest").stringValue }
        }
        val runIds = REPLAY.map { run ->
            parse(Files.readString(HANDOFF.resolve(run).resolve(LedgerExport.MANIFEST))).getValue("runId").stringValue
        }

        assertTrue(digests[0].isNotEmpty(), "인계한 한 벌에 사건이 없다")
        assertEquals(digests[0], digests[1], "두 벌의 해시가 갈렸다 — 같은 시드의 두 구동이 아니다")
        assertNotEquals(runIds[0], runIds[1], "두 벌의 구동 식별자가 같다")
    }

    @Test
    fun `현장 설정 없이 만든 번들의 요약이 설정 칸을 더하기 전과 같다`() {
        // ★**커밋된 인계 요약을 지금 인코더로 다시 대는 시험이 없다** — 위의 대조는 두 구동을 서로 견줄 뿐이라, 요약의
        //   재료가 모든 번들에서 바뀌어도 둘이 함께 바뀌어 초록이다. 그래서 설정 버전 칸을 더하기 전(`2ca7cd6`)에 잰
        //   값을 문자열로 박는다. 설정 없이 도는 번들의 요약이 움직이면 인계 번들과 내보내기 버전이 함께 움직여야 한다.
        assertEquals(GOLDEN_DIGEST, bundle().digest(), "현장 설정 없이 만든 번들의 요약이 바뀌었다")
    }

    @Test
    fun `안내문이 대는 구동 식별자가 커밋된 한 벌의 것과 같다`() {
        // ★**한 번 낡았던 자리다.** 한 벌을 다시 산출하면서 `INDEX.txt` 의 값만 안 고쳐, 받는 쪽이
        //   읽는 안내문이 없는 구동을 가리키고 있었다. 칸과 판을 대는 검사는 **값을 안 보므로**
        //   (그게 설계다) 이 어긋남에 아무도 안 빨개진다 — 그래서 여기서 값을 댄다.
        //
        //   둘 다 커밋된 파일이라 같이 움직인다. 유지비는 «한 벌을 갱신하면 안내문도 갱신한다» 하나이고,
        //   그건 어차피 해야 하는 일이다.
        // ★**안내문은 이제 추적하지 않는다**(공개 대상이 아님, §15.190). 그래서 CI 체크아웃에는
        //   없고 고치는 기계에만 있다. 낡는 일은 고치는 자리에서 나므로 그 자리에서는 잡힌다.
        //
        //   ⛔**«없다» 를 그냥 통과시키면 안 된다.** 실수로 지운 것과 모양이 같아져 이 시험이 조용히
        //   참이 된다. 없으면 **무시 목록이 그것을 이름으로 드는지** 보고, 그때만 건너뛴다.
        val indexFile = HANDOFF.resolve("INDEX.txt")
        if (!Files.isRegularFile(indexFile)) {
            val ignored = Files.readString(Path.of("..", ".gitignore").normalize())
            assertTrue("handoff/narrator/INDEX.txt" in ignored, "안내문이 없는데 무시 목록에도 없다")
            return
        }
        val index = Files.readString(indexFile)
        RUNS.forEach { run ->
            val runId = parse(Files.readString(HANDOFF.resolve(run).resolve(LedgerExport.MANIFEST)))
                .getValue("runId").stringValue
            assertTrue(runId in index, "$run 의 구동 식별자가 안내문에 없다: $runId")
        }
    }

    @Test
    fun `재발이 있는 한 벌이 두 축으로 갈리고 같은 초에 겹치지 않는다`() {
        // ★**재발은 읽는 쪽이 센다** — 이 층은 사실만 낸다(ADR 40). 그런데 재실행 쌍은 한 벌 안에
        //   같은 기체·같은 분류가 없어 그 셈법이 **실물로는 한 번도 안 밟힌다.** `run-3` 이 그 자리이고,
        //   이 시험이 없으면 시나리오를 고치다 재발이 사라져도 아무도 모른다.
        val incidents = lines(RECURRENCE, LedgerExport.INCIDENTS).map { parse(it) }
        assertTrue(incidents.isNotEmpty(), "재발 한 벌에 사건이 없다")

        val byRobotAndClass = incidents.groupingBy {
            it.getValue("robotId").stringValue to it.getValue("failureClass").stringValue
        }.eachCount()
        val repeated = byRobotAndClass.filterValues { it >= 3 }
        assertEquals(1, repeated.size, "같은 기체·같은 분류가 셋인 묶음이 하나가 아니다: $byRobotAndClass")

        val (robot, failureClass) = repeated.keys.first()
        val rows = incidents.filter {
            it.getValue("robotId").stringValue == robot && it.getValue("failureClass").stringValue == failureClass
        }

        // ★**같은 초에 둘이 들어오면 읽는 쪽이 그 둘 사이의 재발을 안 센다.** 가상 시계가 초 단위라
        //   앞뒤를 못 가르고, 그쪽은 덜 세는 쪽으로 틀린다 — 한 벌이 재발을 싣고도 재발로 안 읽힌다.
        val at = rows.map { it.getValue("at").stringValue }
        assertEquals(at.size, at.toSet().size, "같은 초에 겹친 사건이 있다: $at")

        // 축이 둘이다 — 기체 축과 자리 축. 자리가 전부 다르면 «같은 자리에서 또» 가 한 번도 안 나온다.
        val places = rows.groupingBy { it.getValue("unitId").stringValue }.eachCount()
        assertEquals(1, places.count { it.value == 2 }, "같은 자리에서 두 번 깨진 자리가 하나가 아니다: $places")

        // **재발이 없는 사건이 있어야 그 셈이 무언가를 가린다.** 전부 재발이면 그 칸은 늘 켜져 있다.
        assertTrue(
            incidents.size > rows.size,
            "재발이 없는 사건이 없다 — 재발 여부가 아무것도 안 가른다",
        )
    }

    @Test
    fun `인계한 한 벌이 대장의 네 갈래와 승인자 두 갈래를 든다`() {
        // 받는 쪽의 방벽이 이것들에 선다. 하나라도 빠지면 그쪽 시험이 전제부터 빈다.
        val outcomes = lines("run-1", LedgerExport.REMEDY_SEARCHES).map { parse(it).getValue("outcome").stringValue }
        assertEquals(
            setOf("FOUND", "NONE", "WITHHELD", "SOURCE_MISSING"),
            outcomes.toSet(),
            "대장의 갈래가 빠졌다: $outcomes",
        )

        // ★**승인할 수 있는 답은 `FOUND` 뿐이다.** `SOURCE_MISSING` 에 걸음이 실리면 읽는 쪽이 그것을
        //   승인 대상으로 읽고, 승인 API 에는 그 주문을 받을 자리가 없다.
        val relocations = lines("run-1", LedgerExport.REMEDY_SEARCHES).map { parse(it) }
            .filter { it.getValue("outcome").stringValue == "SOURCE_MISSING" }
        assertTrue(relocations.isNotEmpty(), "점유 축이 계산한 회복이 한 벌에 없다")
        relocations.forEach {
            assertTrue("steps" !in it.keys, "SOURCE_MISSING 줄에 걸음이 실렸다 — 승인 대상으로 읽힌다")
            assertTrue(it.getValue("source").stringValue.isNotBlank(), "어느 자리가 비었는지가 없다")
            assertTrue(it.getValue("material").stringValue.isNotBlank(), "무엇이 있어야 했는지가 없다")
        }

        val approvers = lines("run-1", LedgerExport.INCIDENTS).map { parse(it).getValue("approvedBy") }
        assertTrue(approvers.any { it.hasNullValue() }, "승인을 안 거친 사건이 없다")
        assertTrue(approvers.any { it.hasStructValue() }, "승인을 거친 사건이 없다")
    }

    @Test
    fun `인계한 한 벌이 되짚지 않고 읽을 재료를 든다`() {
        // ★★**칸을 만들어도 시나리오가 안 채우면 빈 칸이다**(§15.177). 받는 쪽이 이 한 벌로 골든셋을
        //   짜므로, 갈래가 비어 있으면 그쪽은 다시 추론으로 돌아간다 — 분류에서 기종을 역추론하는
        //   것이 정확히 그렇게 생겼다.
        val incidents = lines("run-1", LedgerExport.INCIDENTS).map { parse(it) }
        assertTrue(incidents.isNotEmpty(), "인계한 한 벌에 사건이 없다")

        assertTrue(
            incidents.all { it.getValue("robotId").stringValue.isNotBlank() },
            "어느 기체인지 안 적힌 줄이 있다",
        )
        assertTrue(
            incidents.any { one ->
                val fault = one.getValue("fault")
                fault.hasStructValue() && fault.structValue.fieldsMap.getValue("vendorDetail").stringValue.isNotBlank()
            },
            "벤더 원문이 실린 사건이 없다 — 분류를 되풀이하는 것 말고 할 수 있는 것이 없다",
        )
        assertTrue(
            incidents.any { it.getValue("failureClass").stringValue == "UNCLASSIFIED" },
            "안 좁혀진 사건이 없다 — 1순위 원인 지표가 맞는 답만 보게 된다",
        )
        assertTrue(
            incidents.any { it.getValue("step").structValue.fieldsMap.getValue("at").numberValue > 1 },
            "전부 첫 걸음에서 깨졌다 — 「몇 걸음 중 어디서」를 가려 주는 사건이 없다",
        )
        assertTrue(
            incidents.any {
                it.getValue("requiredEvidence").stringValue != it.getValue("reachedEvidence").stringValue
            },
            "요구 등급과 도달 등급이 갈리는 사건이 없다 — 「얼마나 믿어야 하나」를 잴 것이 없다",
        )

        // ── 둘째 묶음(§15.178). **한 갈래뿐이면 그 칸은 값이 있어도 아무것도 안 가른다.**
        assertEquals(
            setOf("ROBOT", "FLEET"),
            incidents.map { it.getValue("route").stringValue }.toSet(),
            "경로가 한 갈래뿐이다 — 책임 소재를 가르는 것이 안 보인다",
        )
        assertTrue(
            incidents.any { it.getValue("observation").structValue.fieldsMap.getValue("linkBroken").boolValue },
            "선이 끊긴 채 난 사건이 없다 — 관측 신뢰가 늘 온전한 것으로 보인다",
        )
        assertTrue(
            incidents.any { it.getValue("observation").structValue.fieldsMap.getValue("progressObservable").hasNullValue() },
            "진행률 관측 가능성이 널인 사건이 없다 — 3값의 널을 볼 자리가 없다",
        )
        assertTrue(
            incidents.all { it.getValue("intent").structValue.fieldsMap.getValue("skillType").stringValue.isNotBlank() },
            "무엇을 하려던 일이었는지가 안 적힌 줄이 있다",
        )
    }

    @Test
    fun `사건 뒤에 탐색이 서는 한 벌이 같은 기체의 짝을 든다`() {
        // ★**나머지 벌들은 순서가 반대다.** 접수 관문의 거절이 제안을 세우고(§15.150) 승인된 조치가
        //   나중에 깨진다 — 그래서 읽는 쪽이 «이 실패를 어떻게 회복하나» 를 사건 옆에서 못 읽었고
        //   사건 아홉에 짝이 하나였다(읽는 쪽 실측). `run-4` 가 그 자리이며, 이 시험이 없으면
        //   시나리오를 고치다 순서가 되돌아가도 아무도 모른다.
        val incidents = lines(AFTER_INCIDENT, LedgerExport.INCIDENTS).map { parse(it) }
        val searches = lines(AFTER_INCIDENT, LedgerExport.REMEDY_SEARCHES).map { parse(it) }
        assertTrue(incidents.isNotEmpty() && searches.isNotEmpty(), "한 벌이 비었다")

        val at = { row: Map<String, Value> -> Instant.parse(row.getValue("at").stringValue) }
        searches.forEach { search ->
            val robot = search.getValue("robotId").stringValue
            val before = incidents.filter { it.getValue("robotId").stringValue == robot && at(it).isBefore(at(search)) }
            assertTrue(
                before.isNotEmpty(),
                "탐색 ${search.getValue("searchId").stringValue} 앞에 같은 기체의 사건이 없다: " +
                    incidents.map { it.getValue("robotId").stringValue to at(it) },
            )
        }

        // ★**짝이 없는 사건이 있어야 그 셈이 무언가를 가린다**(§15.177).
        val paired = searches.map { it.getValue("robotId").stringValue }.toSet()
        assertTrue(
            incidents.any { it.getValue("robotId").stringValue !in paired },
            "짝 없는 사건이 없다 — 읽는 쪽의 셈이 늘 켜져 있게 된다",
        )

        // 「답이 섰다」와 「가렸다」가 사건 옆에서도 갈려야 한다.
        val outcomes = searches.map { it.getValue("outcome").stringValue }.toSet()
        assertTrue("FOUND" in outcomes && "WITHHELD" in outcomes, "선 답과 가린 답이 같이 있지 않다: $outcomes")
    }

    @Test
    fun `사건과 그 뒤의 탐색 사이에 판단을 지어 싣지 않는다`() {
        // ★★**빠짐으로 거짓말하지 않는다**(§15.188) — 그리고 **지어 넣어서도 거짓말하지 않는다.** 앞 판의 이 한 벌은 사람이 재작업을
        //   내고 그 새 태스크의 관측으로 탐색을 세웠고, 이 시험은 사건과 탐색 사이의 `REWORK` 걸음을 댔다. 재작업하는 실행이 기체를
        //   쥐므로 그 거절에는 이제 제안이 서지 않는다(§15.319). 그래서 사람이 보류 실행을 **취소로 닫고** 탐색이 선다. 취소는 운영자
        //   판단(`resolve`)이 아니라 사건의 판단 칸에 실리지 않는다 — 그 빈칸은 한계 대장 §15.319 행이 연다(셋째 판단 값과 조회 버전
        //   상승). 여기서 대는 것은 그 사이에 **하지 않은 판단이 실리지 않는 것**이다: «완료 확인» 으로 닫았다고 적으면 하지 않은 일을
        //   했다고 적는다.
        val incidents = lines(AFTER_INCIDENT, LedgerExport.INCIDENTS).map { parse(it) }
        val searches = lines(AFTER_INCIDENT, LedgerExport.REMEDY_SEARCHES).map { parse(it) }
        val at = { row: Map<String, Value> -> Instant.parse(row.getValue("at").stringValue) }

        searches.forEach { search ->
            val robot = search.getValue("robotId").stringValue
            val incident = incidents
                .filter { it.getValue("robotId").stringValue == robot && at(it).isBefore(at(search)) }
                .maxByOrNull { at(it) }
                ?: error("$robot 의 앞선 사건이 없다")
            assertTrue(
                incident.getValue("resolution").hasNullValue(),
                "$robot 의 사건에 하지 않은 판단이 실렸다: ${incident.getValue("resolution")}",
            )
            assertTrue(incident.getValue("approvedBy").hasNullValue(), "$robot 의 사건이 승인으로 선 실행의 것이다 — 이 한 벌의 장면이 아니다")
        }

        // 이 한 벌에는 판단이 하나도 없다 — 하나라도 있으면 장면이 바뀐 것이고, 그때는 이 시험과 한계 대장 행을 함께 고친다.
        incidents.forEach {
            assertTrue(it.getValue("resolution").hasNullValue(), "판단이 실린 사건이 있다: ${it.getValue("incidentId").stringValue}")
        }
    }

    companion object {
        private val HANDOFF: Path = Path.of("..", "handoff", "narrator").normalize()

        /** **같은 시드를 두 번 돌린 쌍.** 해시가 같고 `runId` 만 다른 것이 이 둘의 존재 이유다. */
        private val REPLAY = listOf("run-1", "run-2")

        /** 재발이 실제로 일어나는 한 벌. 재실행이 아니라 **다른 시나리오**다. */
        private const val RECURRENCE = "run-3"

        /** 사건이 **먼저** 나고 탐색이 그 뒤에 서는 한 벌. 읽는 쪽이 회복 설명을 재는 자리다. */
        private const val AFTER_INCIDENT = "run-4"

        /** 인계 지점의 모든 벌. 칸과 판을 대는 검사는 전부에 건다. */
        private val RUNS = REPLAY + RECURRENCE + AFTER_INCIDENT
        private val AT: Instant = Instant.parse("2026-09-05T00:03:21Z")

        /** [bundle] 의 요약을 `2ca7cd6`(현장 설정 칸을 더하기 전)에서 잰 값. */
        private const val GOLDEN_DIGEST = "d511e96df06e6be67f0b4af8d73bef4a23aedab6253870c30908768358801682"

        private val STEP = RemedyStep("pick_place", emptyList(), HoldKind.HOLD_KIND_EMPTY, HoldKind.HOLD_KIND_HOLDING)

        private val NONE = RemedyOutcome.None(
            Remedy.None.Cause.NO_CAPABILITY,
            listOf(
                PreconditionViolation(
                    PreconditionSubject.PRECONDITION_SUBJECT_HOLD,
                    HoldKind.HOLD_KIND_EMPTY,
                    HoldKind.HOLD_KIND_HOLDING,
                    "든 채다",
                ),
            ),
        )

        /** 널과 빈 목록이 다르다는 것까지 칸으로 드러나야 하므로 **둘 다 값을 가진 판**으로 재 본다. */
        private val SOURCE_MISSING = RemedyOutcome.SourceMissing(
            material = "ENGINE-COVER-B",
            source = "SEQ-IN-03.BIN-A",
            observed = null,
            alternatives = listOf("SEQ-IN-03.BIN-B"),
        )

        private fun record(outcome: RemedyOutcome) =
            RemedySearchRecord("search-1", "hum-02", "PATROL-1", AT, AT, outcome)

        private fun bundle() = IncidentBundle(
            incidentId = "incident-1",
            jobOrderId = "SEQ-204",
            executionId = "exec-1",
            robotId = "hum-02",
            unitId = "RACK-204.S01",
            at = AT,
            wallClockAt = AT,
            failureClass = null,
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
            step = StepPosition(1, listOf("RACK-204.S01"), emptyList()),
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
            // [GOLDEN_DIGEST] 를 잰 때의 계약 버전이다. 계약 버전은 요약의 재료라 지금 값을 넣으면 계약을 올릴 때마다(0.10.0,
            // §15.263) 이 시험이 설정 칸과 무관하게 빨개진다. 칸 집합을 대는 위 시험은 값을 안 보므로 상관없다.
            contractSemver = "0.9.0",
            approvedBy = null,
            review = null,
        )
    }
}

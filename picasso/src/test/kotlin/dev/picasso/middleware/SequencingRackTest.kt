package dev.picasso.middleware

import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.mimic.control.v1.ForceFaultRequest
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.RemoveCapabilityRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 시나리오 ② — 부품 시퀀싱을 **미들웨어 층**에서 돌린다(보고서 6장, `docs/scenarios.md` §4).
 *
 * `SequencingCellTest`(harness)가 계약 ④에서 답하는 것을 봤다면, 여기는 그 위 —
 * 상류(예상 소비자 = 이 시험)가 JobOrder 를 넣고, 미들웨어가 슬롯마다 원자
 * 태스크로 조합해 mimic 에 돌리고, 셀 검증 장치(PLC/WCS Mimic)의 신호와 결합해
 * 근거 등급을 매기고, JobResponse 를 아웃박스에 낸다. 보고서 6장 상황표 넷과
 * 17장의 1·2·4 를 이 층에서 단언한다.
 *
 * 상류 Mock 은 없다 — 이 시험이 MES 의 역할이다(ADR 38 결정 4).
 */
class SequencingRackTest {

    private fun order(
        version: Int = V17,
        required: Evidence = Evidence.E2,
        slots: Map<String, String> = mapOf("S01" to "A", "S02" to "B", "S03" to "A", "S04" to "C"),
        sources: Map<String, String> = mapOf("A" to "SEQ-IN-02.BIN-A", "B" to "SEQ-IN-02.BIN-B", "C" to "SEQ-IN-02.BIN-C"),
    ) = JobOrder(
        jobOrderId = "SEQ-204",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = version,
        requiredEvidence = required,
        materialRequirements = slots.values.groupingBy { it }.eachCount().map { (m, n) -> MaterialRequirement("ENGINE-COVER-$m", n) },
        equipmentRequirements =
        slots.map { (slot, m) -> EquipmentRequirement("RACK-204.$slot", "destination", mapOf("material" to "ENGINE-COVER-$m")) } +
            sources.map { (m, bin) -> EquipmentRequirement(bin, "source", mapOf("material" to "ENGINE-COVER-$m")) },
    )

    /** 완벽한 세계 — 로봇이 놓은 대로 셀 장치가 본다. 시험이 그 세계를 프로그램한다. */
    private fun CellMimic.perfect(order: JobOrder) {
        order.equipmentRequirements.filter { it.equipmentUse == "destination" }
            .forEach { program(it.id, it.properties["material"]) }
    }

    private class World(
        profile: Path,
        port: (RobotPort) -> RobotPort = { it },
        missions: MissionCatalog? = null,
    ) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to profile))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(port(ClientRobotPort(harness.client())), cell, now = { harness.clock.now() }, missions = missions)

        fun tasks() = harness.oracle.dumpInternalState(
            DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build(),
        ).tasksList

        /**
         * 시간을 밀고 미들웨어를 돌린다 — [until] 이 참이 될 때까지, 최대 [rounds] 번.
         * 팔로워가 비동기라 각 라운드에 잠깐 기다린다.
         */
        fun drive(rounds: Int = 40, step: Duration = Duration.ofSeconds(30), until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(step)
                Thread.sleep(40)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다: tasks=${tasks().map { it.taskId to it.taskState }}")
        }

        fun forceFault(errorType: String, taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType(errorType).setTaskId(taskId).build(),
        ).taskState

        override fun close() = harness.close()
    }

    private fun world() = World(Path.of("..", "profile", "fixtures", "minimal.json").normalize())

    /** minimal 에 `navigate_to: HOLD requires EMPTY` 와 차단하는 `PAYLOAD_LOST` 를 더한 프로파일. */
    private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()

    /** 점검 순회 주문 하나 — 첫 단위가 `navigate_to` 다. */
    private fun inspection(jobOrderId: String = "PATROL-1", version: Int = 1, targets: Int = 1) = JobOrder(
        jobOrderId = jobOrderId,
        workMasterId = InspectAsset.WORK_MASTER,
        version = version,
        requiredEvidence = Evidence.E0,
        equipmentRequirements = (1..targets).map {
            EquipmentRequirement("PUMP-0$it", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-$it"))
        },
    )

    private fun Middleware.Execution.settled() = physicalState.isSettled && active == null

    // ── 정상

    @Test
    fun `슬롯 넷이 차례로 돌고, 셀 장치가 확인하면 E2 로 물리 완료다`() {
        world().use { w ->
            val order = order()
            w.cell.perfect(order)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT)).execution

            w.drive { exec.settled() }

            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(listOf("RACK-204.S01", "RACK-204.S02", "RACK-204.S03", "RACK-204.S04"), exec.completedUnits)
            assertTrue(exec.units.all { it.reached == Evidence.E2 && it.verification == Verification.MATCHED })
            // 슬롯 넷 = 원자 태스크 넷, 차례로.
            assertEquals(4, w.tasks().size)
            assertTrue(w.tasks().all { it.taskState == "SUCCEEDED" })

            // 결과 통보 — 아웃박스에 있고 아직 ack 전이다(두 축의 둘째).
            val response = w.mw.pending().single()
            assertEquals(PhysicalState.PHYSICALLY_DONE, response.physicalState)
            assertEquals(Evidence.E2, response.reachedEvidence)
            assertEquals(UpstreamAck.SENT_UNACKED, response.ack)
            assertEquals(UpstreamAck.SENT_UNACKED, exec.upstreamAck)

            assertTrue(w.mw.ack(response.jobResponseId))
            assertEquals(emptyList(), w.mw.pending(), "ack 뒤에도 아웃박스에 남아 있다")
            assertEquals(UpstreamAck.ACKED, exec.upstreamAck)
        }
    }

    @Test
    fun `자재 선언과 배정이 어긋난 주문은 받지 않는다`() {
        // 상류가 A형 둘이라 해 놓고 슬롯이 셋을 요구하면 그 주문은 자기 안에서 모순이다.
        // **재고 판단이 아니다** — 그것은 WMS 의 일이고, 여기서 보는 것은 주문의 정합성이다.
        val broken = order().let { o ->
            o.copy(materialRequirements = o.materialRequirements.map { m ->
                if (m.materialDefinitionId == "ENGINE-COVER-A") m.copy(quantity = 3) else m
            })
        }
        world().use { w ->
            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(broken, ROBOT))
            assertTrue("선언" in rejected.reason && "배정" in rejected.reason, rejected.reason)
            // **받아 놓고 돌리면** 어긋남이 로봇이 실패한 뒤에야 보인다. 그때는 이미 움직인 뒤다.
            assertEquals(emptyList(), w.tasks(), "거절인데 로봇에 갔다")
        }
    }

    @Test
    fun `자재 선언이 없으면 검사하지 않는다 — 없는 것과 어긋나는 것은 다르다`() {
        world().use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order().copy(materialRequirements = emptyList()), ROBOT))
        }
    }

    @Test
    fun `요구 등급이 E0 이면 셀 장치를 묻지 않는다`() {
        world().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(required = Evidence.E0), ROBOT)).execution
            w.drive { exec.settled() }

            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertTrue(exec.units.all { it.verification == Verification.NOT_REQUESTED && it.reached == Evidence.E0 })
            assertEquals(Evidence.E0, w.mw.pending().single().reachedEvidence)
        }
    }

    // ── 상황표 (보고서 6장)

    @Test
    fun `B형 슬롯에 A형이 감지되면 그 슬롯은 완료가 아니고 불일치로 보고된다`() {
        world().use { w ->
            val order = order()
            w.cell.perfect(order)
            w.cell.program("RACK-204.S02", "ENGINE-COVER-A") // 오인계
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT)).execution
            w.drive { exec.settled() }

            val s02 = exec.units.single { it.unitId == "RACK-204.S02" }
            assertEquals(UnitState.FAILED, s02.state)
            assertEquals(Verification.MISMATCH, s02.verification)
            assertEquals("observed=ENGINE-COVER-A at RACK-204.S02", s02.note, "잘못 놓인 것의 위치를 기록한다(12.3 넷째 행)")
            assertEquals(PhysicalState.PARTIAL, exec.physicalState, "다른 슬롯은 보존되고 실행은 부분 완료다")

            val response = w.mw.pending().single()
            assertEquals(mapOf("RACK-204.S02" to Middleware.MISMATCH), response.incompleteUnits)
            assertEquals(listOf("RACK-204.S01", "RACK-204.S03", "RACK-204.S04"), response.completedUnits)
            assertTrue(response.operatorRequired, "오인계 의심은 운영자 확인이다(보고서 12.3)")
        }
    }

    @Test
    fun `로봇은 끝났다는데 설비가 말이 없으면 UNVERIFIED 다 — 재작업이 아니라 운영자 확인`() {
        world().use { w ->
            val order = order()
            w.cell.perfect(order)
            w.cell.silence("RACK-204.S03")
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT)).execution
            w.drive { exec.settled() }

            val s03 = exec.units.single { it.unitId == "RACK-204.S03" }
            assertEquals(UnitState.UNVERIFIED, s03.state)
            assertEquals(Verification.ABSENT, s03.verification)
            assertEquals(PhysicalState.UNVERIFIED, exec.physicalState)
            assertEquals(4, w.tasks().size, "UNVERIFIED 가 재실행을 만들었다")

            val response = w.mw.pending().single()
            assertEquals(listOf("RACK-204.S03"), response.unverifiedUnits)
            assertTrue(response.operatorRequired)
        }
    }

    @Test
    fun `C형이 부족하면 그 슬롯만 못 시작하고 완료 슬롯은 보존된다 — 그리고 v18 이 채운다`() {
        world().use { w ->
            val v17 = order(sources = mapOf("A" to "SEQ-IN-02.BIN-A", "B" to "SEQ-IN-02.BIN-B")) // C 없음
            w.cell.perfect(v17)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(v17, ROBOT)).execution
            w.drive { exec.settled() }

            assertEquals(PhysicalState.PARTIAL, exec.physicalState)
            assertEquals(
                mapOf("RACK-204.S04" to PrepareSequencedRack.NO_SOURCE),
                w.mw.pending().single().incompleteUnits,
            )
            assertEquals(3, w.tasks().size, "못 시작할 슬롯에 태스크가 생겼다")

            // 같은 버전 재발행 — 멱등, 새 태스크 없음.
            assertIs<Middleware.Submission.Idempotent>(w.mw.submit(v17, ROBOT))
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(v17.copy(version = V17 - 1), ROBOT))

            // v18 — C형 공급이 생겼다. 완료 슬롯 셋은 그대로, S04 만 새로 돈다.
            val v18 = order(version = V18)
            w.cell.perfect(v18)
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(v18, ROBOT))
            w.drive { exec.settled() }

            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(4, w.tasks().size, "완료 슬롯이 다시 돌았다")
            assertEquals(V18, exec.units.single { it.unitId == "RACK-204.S04" }.revision)
            assertEquals(V17, exec.units.single { it.unitId == "RACK-204.S01" }.revision, "완료 슬롯은 새 버전을 상속한다 — 다시 안 돈다")
            assertEquals(2, w.mw.responses().size, "부분 완료와 완료, 통보 둘")
        }
    }

    @Test
    fun `부분 완료로 개정판을 기다리는 동안에도 그 기체의 능력 변경이 자취에 남는다`() {
        // PARTIAL 은 종착이지만 개정판이 남은 슬롯을 다시 세운다(위 시험의 v18). 그 사이 기체에 생긴 일은 그 실행의
        // 자취에 있어야 한다 — 없으면 개정판이 돌다 막혔을 때 읽는 쪽이 «언제부터 그 스킬이 없었나» 를 못 따라간다.
        world().use { w ->
            val v17 = order(sources = mapOf("A" to "SEQ-IN-02.BIN-A", "B" to "SEQ-IN-02.BIN-B")) // C 없음
            w.cell.perfect(v17)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(v17, ROBOT)).execution
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PARTIAL, exec.physicalState, "이 시험이 보려는 경우가 아니다")
            assertTrue(exec.eventTrail.none { it.kind == "CAPABILITY_CHANGED" }, "전제 — 아직 능력 변경이 없다")

            w.harness.oracle.removeCapability(
                RemoveCapabilityRequest.newBuilder().setRobotId(ROBOT).setSkillType(PrepareSequencedRack.SKILL).build(),
            )
            w.mw.pump()

            assertTrue(
                exec.eventTrail.any { it.kind == "CAPABILITY_CHANGED" && PrepareSequencedRack.SKILL in it.detail },
                exec.eventTrail.joinToString("\n") { "${it.kind}: ${it.detail}" },
            )
            assertEquals(PhysicalState.PARTIAL, exec.physicalState, "능력 변경이 실행 상태를 바꿨다")
        }
    }

    // ── 취소 (보고서 14, 17장 4번)

    private fun cancellableProfile(): Path {
        val raw = Files.readString(Path.of("..", "profile", "fixtures", "minimal.json").normalize())
        val needle = "\"cancel_support\": \"NO\""
        check(raw.split(needle).size == 2) { "픽스처에 cancel_support NO 가 하나가 아니다" }
        val copy = Files.createTempFile("picasso-mw-cancellable-", ".json")
        Files.writeString(copy, raw.replace(needle, "\"cancel_support\": \"YES\""))
        copy.toFile().deleteOnExit()
        return copy
    }

    @Test
    fun `S03 파지 중 취소 — 중단점과 잔여 상태가 응답에 있고 재작업은 자동으로 돌지 않는다`() {
        World(cancellableProfile()).use { w ->
            val order = order()
            w.cell.perfect(order)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT)).execution

            w.drive(step = Duration.ofSeconds(30)) { exec.completedUnits.size == 2 && exec.active?.unitId == "RACK-204.S03" }
            w.harness.advance(Duration.ofSeconds(5))
            Thread.sleep(40)
            w.mw.pump()
            assertEquals("RACK-204.S03", exec.active?.unitId)
            assertEquals(HoldKind.HOLD_KIND_HOLDING, exec.active!!.hold.kind, "전제 — S03 을 든 채여야 한다")

            assertTrue(w.mw.cancel(exec.executionId))
            w.drive(step = Duration.ofSeconds(1)) { exec.physicalState == PhysicalState.ABORTED }

            val report = assertNotNull(w.mw.lastCancel(exec.executionId))
            assertEquals(listOf("RACK-204.S01", "RACK-204.S02"), report.completedUnits)
            assertEquals("RACK-204.S03", report.inProgressUnit)
            assertEquals(listOf("RACK-204.S04"), report.notStartedUnits)
            assertEquals("done", report.cleanup)
            assertEquals(HoldKind.HOLD_KIND_EMPTY, report.residualHold.kind, "복구했다면서 든 채다")
            assertEquals(3, w.tasks().size, "S04 가 자동으로 돌았다 — 재작업은 자동이 아니다")

            val response = w.mw.pending().single()
            assertEquals(PhysicalState.ABORTED, response.physicalState)
            assertEquals(setOf("RACK-204.S03", "RACK-204.S04"), response.incompleteUnits.keys)
        }
    }

    @Test
    fun `단위 사이에서 취소하면 다음 단위를 시작하지 않는다`() {
        // 결함 주입이 찾은 구멍 — 취소를 걸었을 때 도는 단위가 없으면(접수 직후, 또는
        // 단위 사이) 아무것도 새로 시작하면 안 된다. 앞 판 시험은 도는 중의 취소만 봤다.
        World(cancellableProfile()).use { w ->
            val order = order()
            w.cell.perfect(order)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT)).execution

            assertTrue(w.mw.cancel(exec.executionId), "접수 직후의 취소가 거절됐다")
            w.mw.pump()

            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(0, w.tasks().size, "취소된 실행이 단위를 시작했다")
            val report = assertNotNull(w.mw.lastCancel(exec.executionId))
            assertEquals(emptyList(), report.completedUnits)
            assertEquals(null, report.inProgressUnit)
            assertEquals(4, report.notStartedUnits.size)
            assertEquals("nothing_to_clean", report.cleanup)
        }
    }

    @Test
    fun `부분 완료한 실행도 취소를 받고 다음 펌프가 중단을 적는다`() {
        // PARTIAL 은 개정판을 기다리는 자리다(C형 부족 뒤의 v18). 상류가 채우지 않기로 하면 그 실행을 닫을 길이
        // 취소다. 취소가 거절되면 그 실행은 상류가 닫을 수 없는 부분 완료로 남는다.
        world().use { w ->
            val v17 = order(sources = mapOf("A" to "SEQ-IN-02.BIN-A", "B" to "SEQ-IN-02.BIN-B")) // C 없음
            w.cell.perfect(v17)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(v17, ROBOT)).execution
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PARTIAL, exec.physicalState, "이 시험이 보려는 경우가 아니다")

            assertTrue(w.mw.cancel(exec.executionId), "부분 완료한 실행이 취소를 거절했다")
            w.mw.pump()

            assertEquals(PhysicalState.ABORTED, exec.physicalState, "취소를 받았는데 중단이 안 적혔다")
            val report = assertNotNull(w.mw.lastCancel(exec.executionId))
            assertEquals(listOf("RACK-204.S01", "RACK-204.S02", "RACK-204.S03"), report.completedUnits)
            assertEquals(null, report.inProgressUnit)
            assertEquals("nothing_to_clean", report.cleanup)
            assertEquals(3, w.tasks().size, "취소가 단위를 새로 시작했다")
            assertEquals(PhysicalState.ABORTED, w.mw.pending().last().physicalState)
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val V17 = 17
        const val V18 = 18
        const val GO = "GO"
    }

    // ── 사전 조건 (설계안 §4 — 계획 시점 사슬 검사) · 상관 실패

    @Test
    fun `든 채로 관측된 로봇에는 EMPTY 를 요구하는 첫 단위를 보내기 전에 제출이 막힌다`() {
        // 미들웨어는 마지막 관측 파지(WatchTask 갱신)에서 출발해 카탈로그 효과로 단위를 따라가며 로봇이 선언한
        // 조건과 대조한다. 어긋나면 접수 요청조차 보내지 않는다. 권위는 발신자다 — 관측이 없는 로봇은 미리 재단하지 않는다.
        World(PRECOND).use { w ->
            val rack = order(slots = mapOf("S01" to "A"), sources = mapOf("A" to "SEQ-IN-02.BIN-A"))
            w.cell.perfect(rack)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack, ROBOT)).execution
            // pick_place 가 도는 동안 로봇은 HOLDING 이다(§4.4) — 미들웨어가 그것을 관측할 때까지 민다.
            w.drive(step = Duration.ofSeconds(1)) { exec.units.single().hold.kind == HoldKind.HOLD_KIND_HOLDING }

            val patrol = inspection()
            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol, ROBOT))
            assertTrue("HOLD" in refused.reason && "navigate_to" in refused.reason, refused.reason)
            assertEquals(1, w.tasks().size, "막혔는데 접수 요청이 나갔다")

            // 놓고 끝나면(pick_place 는 releases_object) 같은 주문이 받아진다 — 막은 것은 파지이지 주문이 아니다.
            w.drive { exec.settled() }
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol, ROBOT))
        }
    }

    @Test
    fun `적재를 잃은 실패는 단위·차단·잔여 파지 세 축에 다 적힌다`() {
        // 상관 실패는 새 모양이 아니라 세 축의 결합이다 — 어느 단위가 왜 멈췄나(incompleteUnits), 무엇이 실행을
        // 막나(blockedBy), 로봇이 무엇을 들고 있나(residualHold). 프로파일이 적재 유실을 can_accept_new_task=false 로
        // 선언해야 둘째 축에 실린다 — 어댑터 골격의 규약이다. 셋째 축은 "말하지 않았다" 가 아니라 "빈손" 이어야 한다.
        World(PRECOND).use { w ->
            val rack = order(slots = mapOf("S01" to "A", "S02" to "B"), sources = mapOf("A" to "SEQ-IN-02.BIN-A", "B" to "SEQ-IN-02.BIN-B"))
            // 셀에 증거를 두지 않는다 — 잃은 적재는 슬롯에 없다. 증거가 있으면 미들웨어는 "하류는 잃었다는데 셀엔 있다" 로
            // 그 슬롯을 운영자 판단에 세우고(다른 규칙, 다른 시험의 몫) 다음 단위까지 가지 않는다.
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack, ROBOT)).execution
            w.drive(step = Duration.ofSeconds(1)) { exec.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING }

            w.forceFault("PAYLOAD_LOST", exec.units.first().taskId)
            w.drive(step = Duration.ofSeconds(1)) { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            val report = w.mw.pending().last()
            assertEquals("PAYLOAD_LOST", report.incompleteUnits["RACK-204.S01"], report.incompleteUnits.toString())
            assertEquals(listOf("PAYLOAD_LOST"), report.blockedBy)
            assertEquals(HoldKind.HOLD_KIND_EMPTY, report.residualHold.kind, "잔여 파지가 빈손이 아니라 미정으로 나갔다")
            assertTrue(report.operatorRequired)
            assertEquals(1, w.tasks().size, "막혔는데 둘째 슬롯이 나갔다")
        }
    }

    // ── 리뷰 개선 — 낡은 관측·개정판·발신자 거절·모르는 주어

    @Test
    fun `든 채로 끝난 뒤의 새 주문은 종착 파지가 아니라 스냅숏의 손으로 막는다`() {
        // 리뷰 C2 는 그대로다 — 종착한 단위의 HOLDING 은 낡을 수 있고(사람이 비웠을 수 있다) 다시 볼 길이 없어 쓰지 않는다. 이제 기체가
        // 스냅숏마다 손을 다시 읽어 주므로(계약 0.11.0, §15.316) 도는 단위가 없어도 «지금의 손» 이 있다. 막는 근거가 그것임을 사유가 말한다.
        World(PRECOND).use { w ->
            val rack = order(slots = mapOf("S01" to "A"), sources = mapOf("A" to "SEQ-IN-02.BIN-A"))
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack, ROBOT)).execution
            w.drive(step = Duration.ofSeconds(1)) { exec.units.single().hold.kind == HoldKind.HOLD_KIND_HOLDING }
            w.forceFault("LOCALIZATION_LOST", exec.units.single().taskId)   // 든 채로 실패 — 파지는 안 바뀐다
            w.drive(step = Duration.ofSeconds(1)) { exec.settled() }

            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(inspection(), ROBOT))
            assertTrue("기체 스냅숏의 파지" in refused.reason, refused.reason)
        }
    }

    @Test
    fun `든 채로 끝난 뒤의 새 주문은 손을 안 싣는 옛 발신자면 막지 않는다 — 권위는 발신자다`() {
        // 리뷰 C2 — 계획 시점 검사는 낡을 수 있는 종착 파지를 근거로 삼지 않는다. 손을 싣지 않는 발신자에게는 지금의 손이 없으니 보낸다.
        World(PRECOND, port = { HandSilentRobotPort(it) }).use { w ->
            val rack = order(slots = mapOf("S01" to "A"), sources = mapOf("A" to "SEQ-IN-02.BIN-A"))
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack, ROBOT)).execution
            w.drive(step = Duration.ofSeconds(1)) { exec.units.single().hold.kind == HoldKind.HOLD_KIND_HOLDING }
            w.forceFault("LOCALIZATION_LOST", exec.units.single().taskId)   // 든 채로 실패 — 파지는 안 바뀐다
            w.drive(step = Duration.ofSeconds(1)) { exec.settled() }
            assertEquals(HoldKind.HOLD_KIND_HOLDING, exec.units.single().hold.kind, "전제가 무너졌다 — 든 채로 끝나지 않았다")

            // 도는 단위가 없다 — 미들웨어는 재단하지 않고 보낸다. 실제로 든 채면 발신자가 거절한다.
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(inspection(), ROBOT))
        }
    }

    @Test
    fun `개정판 주문도 사슬 검사를 받는다`() {
        // 리뷰 C3 — 같은 주문의 새 버전은 revise() 로 가는데, 그 길에는 사슬 검사가 없었다.
        World(PRECOND).use { w ->
            val patrol = inspection(targets = 2)
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol, ROBOT))   // 아직 아무것도 안 돈다 — 통과
            val rack = order(slots = mapOf("S01" to "A"), sources = mapOf("A" to "SEQ-IN-02.BIN-A"))
            val rackExec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack, ROBOT)).execution
            w.drive(step = Duration.ofSeconds(1)) { rackExec.units.single().hold.kind == HoldKind.HOLD_KIND_HOLDING }

            // 로봇이 든 채로 도는데 같은 순회의 v2 — 남은 navigate_to(HOLD requires EMPTY) 는 보내면 안 된다.
            val revised = w.mw.submit(patrol.copy(version = 2), ROBOT)
            assertIs<Middleware.Submission.Rejected>(revised, "개정판이 사슬 검사를 건너뛰었다: $revised")
        }
    }

    /** 코드 랙 임무의 계획 끝에 이동 하나(`GO`, 빈손을 요구)를 붙인 임무. 같은 실행 안에서 놓는 단위 뒤에 이동이 선다. */
    private class RackThenGo(private val base: PrepareSequencedRack = PrepareSequencedRack()) : LogicalCapability by base {
        override fun plan(order: JobOrder): List<ExecutionUnit> = base.plan(order) + ExecutionUnit(
            unitId = GO, route = Route.ROBOT, skillType = InspectAsset.NAVIGATE, parameters = mapOf(InspectAsset.P_LOCATION to "DOCK-1"),
            expectedIdentity = null, source = null, destination = null,
        )
    }

    /** 슬롯 하나 랙 뒤에 이동 하나 — 첫 단위(pick_place)가 든 채 도는 데까지 민다. [revise] 면 그때 같은 작업 지시의 판을 올린다. */
    private fun rackThenGo(revise: Boolean): Triple<Middleware.Execution, Middleware.Submission?, Int> {
        World(PRECOND, missions = MissionCatalog.of(listOf(RackThenGo()))).use { w ->
            val rack = order(slots = mapOf("S01" to "A"), sources = mapOf("A" to "SEQ-IN-02.BIN-A"))
            w.cell.perfect(rack)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack, ROBOT)).execution
            assertEquals(listOf("RACK-204.S01", GO), exec.units.map { it.unitId })
            w.drive(step = Duration.ofSeconds(1)) { exec.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING }
            val revised = if (revise) w.mw.submit(rack.copy(version = V18), ROBOT) else null
            val searches = w.mw.remedySearches().size
            w.drive(rounds = 120, step = Duration.ofSeconds(1)) { exec.physicalState.isSettled }
            return Triple(exec, revised, searches)
        }
    }

    @Test
    fun `도는 단위가 놓고 끝나는 개정은 그 단위가 끝난 뒤의 파지로 사슬을 본다`() {
        // 같은 실행 안의 도는 pick_place 는 끝나면 빈손이다(HoldEffects). 개정 검사가 그 단위의 지금 관측(HOLDING)에서 바로 남은 GO 를
        // 굴리면 멀쩡한 개정을 거부하고 쓸데없는 내려놓기 제안을 세운다 — 개정 없이 돌린 같은 장면에서 GO 는 거절 없이 나간다.
        val (plain, _, _) = rackThenGo(revise = false)
        val (revisedExec, revised, searches) = rackThenGo(revise = true)

        assertIs<Middleware.Submission.Accepted>(revised, "도는 단위의 효과를 굴리지 않아 개정을 거부했다: $revised")
        assertEquals(0, searches, "개정이 조치 탐색을 돌렸다 — 거짓 제안이 선다")
        val ends = { e: Middleware.Execution -> e.units.map { Triple(it.unitId, it.state, it.failureClass) } }
        assertEquals(ends(plain), ends(revisedExec), "개정 유무가 결말을 바꿨다")
        assertEquals(null, revisedExec.units.single { it.unitId == GO }.failureClass, "GO 가 발신자에서 거절됐다")
    }

    @Test
    fun `개정 뒤 도는 단위가 든 채 실패하면 개정이 들인 단위는 나가지 않는다`() {
        // 끝난 뒤의 파지는 성공 가정이다. 그 가정이 틀려 든 채 실패해도 든 채인 기체로 GO 가 나가지 않아야 한다 — 실패한 단위가
        // 운영자 판단으로 서고 다음 단위는 그 판단을 기다린다. 거기서 조치 탐색도 돌지 않는다(제안은 계획 시점 거절에만, §15.150).
        World(PRECOND, missions = MissionCatalog.of(listOf(RackThenGo()))).use { w ->
            val rack = order(slots = mapOf("S01" to "A"), sources = mapOf("A" to "SEQ-IN-02.BIN-A"))
            w.cell.perfect(rack)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack, ROBOT)).execution
            val s01 = exec.units.first()
            w.drive(step = Duration.ofSeconds(1)) { s01.hold.kind == HoldKind.HOLD_KIND_HOLDING }
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack.copy(version = V18), ROBOT))

            // 새 접수를 막지 않는 실패다(can_accept_new_task=true) — 막는 결함이 GO 를 붙잡은 것으로 읽히지 않게.
            w.forceFault("SKILL_EXECUTION_FAILED", s01.taskId)
            w.drive(rounds = 60, step = Duration.ofSeconds(5)) { exec.physicalState == PhysicalState.OPERATOR_HOLD }
            assertEquals(HoldKind.HOLD_KIND_HOLDING, s01.hold.kind, "전제가 무너졌다 — 든 채로 실패하지 않았다")
            assertEquals(UnitState.OPERATOR_HOLD, s01.state)
            assertTrue(exec.blockedBy.isEmpty(), "막는 결함이 GO 를 붙잡았다 — 이 시험이 보려는 길이 아니다: ${exec.blockedBy}")
            val go = exec.units.single { it.unitId == GO }
            assertEquals(UnitState.PENDING, go.state, "든 채 실패한 뒤 GO 가 나갔다")
            assertEquals(0, w.mw.remedySearches().size, "실패한 단위가 조치 탐색을 돌렸다")
        }
    }

    @Test
    fun `발신자가 사전 조건으로 거절하면 그 단위는 운영자 판단으로 서고 다음 단위는 나가지 않는다`() {
        // 리뷰 C10 — 계획 시점 검사가 건너뛴 경우(능력을 못 물었다) 발신자의 PRECONDITION_UNMET 이 유일한 방어선이다.
        // 그것을 FAILED 로 접고 다음 단위로 넘어가면 계약이 말한 «기다리거나 앞 단위를 바꾼다» 가 실행되지 않는다.
        World(PRECOND, port = { CapabilityBlindRobotPort(it) }).use { w ->
            val rack = order(slots = mapOf("S01" to "A"), sources = mapOf("A" to "SEQ-IN-02.BIN-A"))
            val rackExec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack, ROBOT)).execution
            w.drive(step = Duration.ofSeconds(1)) { rackExec.units.single().hold.kind == HoldKind.HOLD_KIND_HOLDING }

            val patrol = assertIs<Middleware.Submission.Accepted>(w.mw.submit(inspection(targets = 2), ROBOT)).execution
            val nav = patrol.units.first()
            w.drive(step = Duration.ofSeconds(1)) { nav.state != UnitState.PENDING }

            assertEquals(UnitState.OPERATOR_HOLD, nav.state, "발신자의 사전 조건 거절이 FAILED 로 접혔다")
            assertEquals(RejectionCode.REJECTION_CODE_PRECONDITION_UNMET.name, nav.failureClass)
            assertTrue(nav.note.orEmpty().contains("HOLD"), "거절 사유·주어가 단위에 남아야 한다: ${nav.note}")
            // 실행 수준의 판단은 다음 pump 가 매긴다 — 운영자 판단 단위가 있으면 다음으로 가지 않는다.
            w.drive(step = Duration.ofSeconds(1)) { patrol.physicalState == PhysicalState.OPERATOR_HOLD }
            assertTrue(patrol.units.drop(1).all { it.state == UnitState.PENDING }, "다음 단위가 나갔다")
            assertTrue(w.mw.pending().last().operatorRequired)
        }
    }

    @Test
    fun `소비자는 모르는 주어를 지어내지 않는다 — 유보하고 보낸다`() {
        // 리뷰 C5 — 새 계약의 발신자가 모르는 주어를 선언하면 옛 미들웨어는 UNRECOGNIZED 로 읽는다. 거절이 아니라 유보다.
        World(PRECOND, port = { AlienSubjectRobotPort(it) }).use { w ->
            val first = assertIs<Middleware.Submission.Accepted>(w.mw.submit(inspection("PATROL-A"), ROBOT)).execution
            w.drive(step = Duration.ofSeconds(1)) { first.units.first().hold.kind == HoldKind.HOLD_KIND_EMPTY }   // 도는 단위가 있어 관측이 살아 있다

            // 빈손이고 아는 조건(HOLD requires EMPTY)은 만족한다 — 모르는 주어 하나 때문에 막으면 마이너 호환이 깨진다.
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(inspection("PATROL-B"), ROBOT))
        }
    }
}

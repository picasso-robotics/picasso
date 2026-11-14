package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 시나리오 ③ — 설비 점검 순회를 **미들웨어 층에서**(보고서 7장, 17장 10번). 계획 단계 7.
 *
 * 합격 기준은 *"아무 코드도 수정하지 않는다"* 가 아니다. 새 점검 의미는 확장 지점([InspectAsset])에 더하되 공통
 * 실행 엔진에 능력·기종 분기가 퍼지지 않고 기존 두 업무(①②)의 뜻이 유지되는지, 그리고 이 기체가 다른 기체와
 * **다르게 답하는 취소·조회를 능력 명세에 드러내는지**를 본다 — 지원하지 않는 것을 지원하는 것처럼 감추지 않는다.
 *
 * 기체는 4족 픽스처 `quadruped-b`: `navigate_to` 는 취소 `NO`, `inspect` 는 취소 `YES`. 그래서 이동 중 취소와 점검 중
 * 취소의 답이 다르고, 그 차이가 `CancelReport.refusal` 에 나타난다. 하네스 `InspectionPatrolTest` 가 계약 표면에서
 * 같은 순회를 돌린다 — 여기는 그 위에서 상류의 모양(JobOrder → JobResponse)으로 본다.
 */
class InspectAssetTest {

    private data class Point(val location: String, val target: String)

    private val points = listOf(
        Point("PUMP-ROOM", "PUMP-01"),
        Point("SWITCHGEAR", "PANEL-3"),
        Point("COMPRESSOR-BAY", "COMP-2"),
    )

    private fun order(required: Evidence = Evidence.E0) = JobOrder(
        jobOrderId = "PATROL-7",
        workMasterId = InspectAsset.WORK_MASTER,
        version = 1,
        requiredEvidence = required,
        parameters = mapOf("shift" to "night"),
        equipmentRequirements = points.map {
            EquipmentRequirement(it.target, EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to it.location, EquipmentUse.PROP_ITEM to "thermal"))
        },
    )

    private class World(port: (RobotPort) -> RobotPort = { it }) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()))
        val mw = Middleware(port(ClientRobotPort(harness.client())), now = { harness.clock.now() })

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList
        fun state(taskId: String) = tasks().firstOrNull { it.taskId == taskId }?.taskState

        fun forceFault(errorType: String, taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType(errorType).setTaskId(taskId).build(),
        ).taskState

        fun drive(rounds: Int = 120, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다: tasks=${tasks().map { it.taskId to it.taskState }}")
        }

        override fun close() = harness.close()
    }

    private fun Middleware.Execution.settled() = physicalState.isSettled && active == null
    private fun taskOf(unitId: String) = "PATROL-7#$unitId"

    @Test
    fun `지점 셋은 단위 여섯이고 태스크 여섯이다 — 공통 엔진이 새 능력을 그대로 돈다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            assertEquals(6, exec.units.size)
            w.drive { exec.settled() }

            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            val expected = points.flatMap { listOf("${it.target}${InspectAsset.TRAVEL_SUFFIX}", it.target) }
            assertEquals(expected, exec.completedUnits, "순회 순서 = 목록 순서, 지점마다 이동 뒤 점검")
            assertEquals(expected.map(::taskOf), w.tasks().map { it.taskId }, "지점 셋 = 태스크 여섯")
            assertTrue(w.tasks().all { it.taskState == "SUCCEEDED" })

            // 점검은 대상을 참조할 뿐 쥐지 않는다(§15.87) — 순회 내내 빈손이다.
            assertTrue(exec.units.all { it.hold.kind == HoldKind.HOLD_KIND_EMPTY }, exec.units.map { it.hold.kind }.toString())

            val response = w.mw.pending().single()
            assertEquals(Evidence.E0, response.reachedEvidence)
            assertEquals(Evidence.E0, response.requiredEvidence)
            assertEquals(expected, response.completedUnits)
            assertTrue(response.autoResolvesInDoubt)
        }
    }

    @Test
    fun `요구 등급이 능력의 최고 등급을 넘으면 접수하지 않는다 — 확인 수단이 없으면 제공 불가`() {
        World().use { w ->
            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(order(required = Evidence.E2), ROBOT))
            assertTrue(rejected.reason.contains("E0"), rejected.reason)
            assertEquals(0, w.tasks().size)
        }
    }

    @Test
    fun `점검 결과를 실을 자리가 비어 있다 — 이 시험은 통과하는 것이 좋은 상태가 아니다`() {
        // 시나리오 ③의 결과는 *항목별 수행 상태와 측정값 또는 증거 자료 참조*다. 상태는 온다(위 시험). 측정값·증거 참조가
        // 올 자리는 계약의 partial_result 하나이고 아무도 채우지 않는다(§15.76). 채우는 쪽이 생기면 여기가 빨개진다 —
        // 그때 이 시험을 지우고 results 의 내용을 단언한다. 하네스 InspectionPatrolTest 가 계약 표면에서 같은 것을 고정한다.
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.settled() }
            assertTrue(w.mw.pending().single().results.isEmpty(), "점검 결과를 채우는 쪽이 생겼다 — 이 시험을 지우고 결과 단언을 세울 때다")
            assertTrue(exec.units.all { it.result == null })
        }
    }

    @Test
    fun `점검 중 위치를 잃으면 그 항목은 LOCALIZATION_LOST 이고, 기체가 새 태스크를 못 받는다 하니 다음 지점으로 가지 않는다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(taskOf("PUMP-01")) == "RUNNING" }

            assertEquals("NEEDS_INTERVENTION", w.forceFault("LOCALIZATION_LOST", taskOf("PUMP-01")))
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            val pump = exec.units.single { it.unitId == "PUMP-01" }
            assertEquals(UnitState.FAILED, pump.state)
            // 정준 분류가 프로파일 모드에서 유도된다 — 벤더 이름도 상태 이름도 상류에는 없다.
            assertEquals("LOCALIZATION_LOST", pump.failureClass)

            // 계약은 다음 태스크를 막지 않는다(결함은 인터록이 아니다). 판단은 이 층의 것이고 — 기체가 can_accept_new_task=false
            // 라 말하는 동안 다음 단위를 보내지 않는다. 자동으로 넘어가지도 않는다.
            assertEquals(2, w.tasks().size, "기체가 못 받는다는데 다음 지점을 보냈다")
            val held = w.mw.pending().last()
            assertEquals(PhysicalState.OPERATOR_HOLD, held.physicalState)
            assertEquals(listOf("LOCALIZATION_LOST"), held.blockedBy)
            assertTrue(held.operatorRequired)
            assertEquals(mapOf("PUMP-01" to "LOCALIZATION_LOST"), held.incompleteUnits.filterKeys { it == "PUMP-01" })

            // 사람이 조치하고 감수한다 — 결함은 기체에 그대로 있어도(계약에 지우는 표면이 없다) 같은 결함은 다시 막지 않는다.
            assertTrue(w.mw.release(exec.executionId))
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PARTIAL, exec.physicalState)
            assertEquals(5, exec.completedUnits.size)
            assertEquals(6, w.tasks().size)
            assertEquals(setOf("LOCALIZATION_LOST[]"), exec.acknowledgedFaults, "감수한 결함이 기록에 남는다")
            assertTrue(w.mw.pending().last().blockedBy.isEmpty())
        }
    }

    @Test
    fun `감수하면 막음이 걷힌 작업 응답이 곧바로 하나 나가고 다시 감수해도 더 나가지 않는다`() {
        // 안 알리면 마지막 작업 응답이 실행이 다음에 알릴 때까지 `OPERATOR_HOLD` 와 감수한 결함을 든다 — 상류는 감수가 안 먹은 줄 안다.
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(taskOf("PUMP-01")) == "RUNNING" }
            w.forceFault("LOCALIZATION_LOST", taskOf("PUMP-01"))
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }
            val before = w.mw.responses().size

            assertTrue(w.mw.release(exec.executionId))
            val sent = w.mw.responses().drop(before)
            assertEquals(1, sent.size, "감수가 작업 응답을 안 냈다: $sent")
            assertEquals(PhysicalState.RUNNING to emptyList(), sent.single().physicalState to sent.single().blockedBy)

            assertFalse(w.mw.release(exec.executionId), "막는 결함이 없는데 감수가 섰다")
            assertEquals(before + 1, w.mw.responses().size, "바뀐 것 없는 감수가 작업 응답을 또 냈다")
        }
    }

    @Test
    fun `결함을 못 물어보면 막지 않는다 — 없다고도 하지 않고 그 사실을 남긴다`() {
        World(port = { FaultBlindRobotPort(it) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(taskOf("PUMP-01")) == "RUNNING" }
            w.forceFault("LOCALIZATION_LOST", taskOf("PUMP-01"))
            w.drive { exec.settled() }

            // 관측 실패로 현장을 세우지는 않는다 — 다음 단위가 나간다. 그러나 그 단위에 "못 물어봤다" 가 남는다.
            assertEquals(PhysicalState.PARTIAL, exec.physicalState)
            assertEquals(6, w.tasks().size)
            val next = exec.units.single { it.unitId == "PANEL-3${InspectAsset.TRAVEL_SUFFIX}" }
            assertTrue(next.note!!.contains("not observable"), next.note)
            assertTrue(w.mw.pending().last().blockedBy.isEmpty())
        }
    }

    @Test
    fun `이동 중 취소는 하류가 거절하고 다음 경계에서 멈춘다 — 그 차이가 취소 응답에 드러난다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            val travel = "PUMP-01${InspectAsset.TRAVEL_SUFFIX}"
            w.drive { w.state(taskOf(travel)) == "RUNNING" }

            assertTrue(w.mw.cancel(exec.executionId))
            w.drive { exec.settled() }

            val report = w.mw.lastCancel(exec.executionId)!!
            assertEquals("REJECTION_CODE_CANCEL_UNSUPPORTED", report.refusal, "navigate_to 는 취소를 안 든다 — 감추면 격리가 아니다")
            assertEquals(listOf(travel), report.completedUnits, "거절된 단위는 끝까지 간다")
            assertNull(report.inProgressUnit, "중단된 단위는 없다 — 끝까지 갔다")
            assertEquals(travel, report.stoppedAfter, "그 뒤에서 멈췄다")
            assertEquals(5, report.notStartedUnits.size, "다음 경계에서 멈춘다 — 점검은 시작하지 않는다")
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(1, w.tasks().size)
            assertEquals("SUCCEEDED", w.state(taskOf(travel)))
        }
    }

    @Test
    fun `취소 응답은 관측과 판정에서 온 칸만 든다`() {
        // 관측 없이 늘 같은 값을 채우는 칸은 하위가 거부했든 받아들였든 같은 말을 한다(§15.258).
        val fields = CancelReport::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(
            setOf(
                "executionId", "version", "completedUnits", "inProgressUnit", "notStartedUnits",
                "residualHold", "cleanup", "finalState", "refusal", "stoppedAfter",
            ),
            fields,
        )
    }

    @Test
    fun `취소가 닿기 전에 하위가 이미 끝냈으면 거부 코드와 그 뒤에서 멈춘 단위가 취소 응답에 있다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(taskOf("PUMP-01")) == "RUNNING" }
            // 이 인스턴스가 끝을 읽기 전에 하위가 점검을 끝낸다 — 시계만 민다.
            var waited = 0
            while (w.state(taskOf("PUMP-01")) != "SUCCEEDED" && waited++ < 120) {
                w.harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
            }
            assertEquals(UnitState.RUNNING, exec.units.single { it.unitId == "PUMP-01" }.state, "끝을 이미 읽어 경합이 아니다")

            assertTrue(w.mw.cancel(exec.executionId))
            w.drive { exec.settled() }

            val report = w.mw.lastCancel(exec.executionId)!!
            assertEquals("REJECTION_CODE_INVALID_TRANSITION", report.refusal, "끝난 태스크에 건 취소의 거부를 버렸다")
            assertNull(report.inProgressUnit, "중단된 단위는 없다 — 이미 끝났다")
            assertEquals("PUMP-01", report.stoppedAfter)
            assertEquals(4, report.notStartedUnits.size, "다음 경계에서 멈춘다")
            assertEquals(2, w.tasks().size)
        }
    }

    @Test
    fun `마지막보다 낮은 버전으로 다시 지으려 하면 거부하고 기체에 새 태스크를 내지 않는다`() {
        // 앞 인스턴스가 버전 2 를 받아 이동을 리비전 2 로 갱신한 뒤 내려갔다. 버전 1 로 그대로 지으면 그 이동의 재전송이
        // `OUTDATED_REVISION` 으로 실패로 적히고, 기체가 아직 도는 동안 다음 지점의 이동이 나갔다(§15.260).
        val travel = taskOf("PUMP-01${InspectAsset.TRAVEL_SUFFIX}")
        World().use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT))
            w.mw.pump()
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order().copy(version = 2), ROBOT))
            w.mw.pump()
            assertEquals("RUNNING", w.state(travel))

            val again = Middleware(ClientRobotPort(w.harness.client()), now = { w.harness.clock.now() })
            val rejected = assertIs<Middleware.Submission.Rejected>(again.resume(order(), ROBOT, ActiveMission(InspectAsset(), null)))
            assertTrue(rejected.reason.startsWith(Middleware.RESUME_STALE_VERSION), rejected.reason)
            assertTrue("$travel@2" in rejected.reason, rejected.reason)
            assertEquals(emptyList(), again.executions())
            again.pump()
            assertEquals(listOf(travel), w.tasks().map { it.taskId }, "거부한 리빌드가 기체에 태스크를 냈다")

            // 마지막 버전을 넘기면 도는 이동에 다시 붙는다.
            val rebuilt = assertIs<Middleware.Submission.Accepted>(again.resume(order().copy(version = 2), ROBOT, ActiveMission(InspectAsset(), null))).execution
            again.pump()
            assertEquals(UnitState.RUNNING, rebuilt.units.first().state)
            assertEquals(listOf(travel), w.tasks().map { it.taskId })
        }
    }

    @Test
    fun `점검 중 취소는 받아들여지고 빈손으로 끝난다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { w.state(taskOf("PUMP-01")) == "RUNNING" }

            assertTrue(w.mw.cancel(exec.executionId))
            w.drive { exec.settled() }

            val report = w.mw.lastCancel(exec.executionId)!!
            assertNull(report.refusal)
            assertNull(report.stoppedAfter)
            assertEquals("done", report.cleanup)
            assertEquals("PUMP-01", report.inProgressUnit)
            assertEquals(listOf("PUMP-01${InspectAsset.TRAVEL_SUFFIX}"), report.completedUnits)
            assertEquals(HoldKind.HOLD_KIND_EMPTY, report.residualHold.kind, "쥔 것이 없으니 복구할 것도 없다")
            assertEquals("CANCELLED", w.state(taskOf("PUMP-01")))
            assertEquals(2, w.tasks().size)
        }
    }

    @Test
    fun `살필 자리가 없는 대상은 계획에서 드러난다`() {
        World().use { w ->
            val blind = order().copy(equipmentRequirements = listOf(EquipmentRequirement("VALVE-9", EquipmentUse.INSPECTION_TARGET)))
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(blind, ROBOT)).execution
            w.drive { exec.settled() }
            assertEquals(PhysicalState.FAILED, exec.physicalState)
            assertEquals(mapOf("VALVE-9" to InspectAsset.NO_LOCATION), w.mw.pending().single().incompleteUnits)
            assertEquals(0, w.tasks().size)
        }
    }

    // ── 대상 안의 순서: 이동이 완료되지 않으면 그 대상의 점검을 내지 않는다(§15.234)

    @Test
    fun `점검 단위만 그 대상의 이동을 선행으로 든다 — 이동과 슬롯 단위는 비워 둔다`() {
        val travel = { target: String -> "$target${InspectAsset.TRAVEL_SUFFIX}" }
        assertEquals(
            points.flatMap { listOf(travel(it.target) to null, it.target to travel(it.target)) },
            InspectAsset().plan(order()).map { it.unitId to it.after },
            "선행은 같은 대상의 이동 하나다 — 대상끼리 묶으면 한 대상의 실패가 나머지를 세운다",
        )
        val rack = JobOrder(
            jobOrderId = "RACK-1",
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = 1,
            equipmentRequirements = listOf(
                EquipmentRequirement("S01", EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to "A")),
                EquipmentRequirement("S02", EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to "A")),
                EquipmentRequirement("FEED-A", EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to "A")),
            ),
        )
        assertTrue(PrepareSequencedRack().plan(rack).all { it.after == null }, "슬롯끼리 독립인 계획에 선행을 넣었다")
    }

    @Test
    fun `이동이 결함 없이 실패하면 그 대상의 점검은 나가지 않고 다음 대상의 이동이 나간다`() {
        val travel = "PUMP-01${InspectAsset.TRAVEL_SUFFIX}"
        World(port = { FailingWithoutFaultPort(it, setOf(taskOf(travel))) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.settled() }

            val moved = exec.units.single { it.unitId == travel }
            assertEquals(UnitState.FAILED, moved.state)
            assertNull(moved.fault, "이 경우는 결함 없는 실패여야 한다 — 결함이 있으면 다른 경로를 본다")
            val expected = points.flatMap { listOf("${it.target}${InspectAsset.TRAVEL_SUFFIX}", it.target) } - "PUMP-01"
            assertEquals(expected.map(::taskOf), w.tasks().map { it.taskId }, "이동이 실패한 대상의 점검이 나갔거나 다음 대상이 안 나갔다")

            val skipped = exec.units.single { it.unitId == "PUMP-01" }
            assertEquals(UnitState.FAILED to "", skipped.state to skipped.taskId)
            assertEquals(SKIPPED, skipped.failureClass)
            assertEquals(PhysicalState.PARTIAL, exec.physicalState)
        }
    }

    @Test
    fun `이동이 기체가 새 태스크를 못 받는 결함으로 실패하면 보류가 먼저 서고 수용한 뒤에 그 대상의 점검을 거른다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            val travel = "PUMP-01${InspectAsset.TRAVEL_SUFFIX}"
            w.drive { w.state(taskOf(travel)) == "RUNNING" }
            w.forceFault("LOCALIZATION_LOST", taskOf(travel))
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            // 결함의 보류가 먼저다(§15.94). 점검은 아직 거르지도 내지도 않았다.
            assertEquals(listOf("LOCALIZATION_LOST"), w.mw.pending().last().blockedBy)
            assertEquals(UnitState.PENDING, exec.units.single { it.unitId == "PUMP-01" }.state, "결함의 보류보다 먼저 점검을 걸렀다")

            assertTrue(w.mw.release(exec.executionId))
            w.drive { exec.settled() }
            assertEquals(SKIPPED, exec.units.single { it.unitId == "PUMP-01" }.failureClass)
            assertTrue(w.tasks().none { it.taskId == taskOf("PUMP-01") })
            assertEquals(5, w.tasks().size, "수용한 뒤 다음 대상들이 나가야 한다")
            assertEquals(PhysicalState.PARTIAL, exec.physicalState)
        }
    }

    @Test
    fun `점검을 내지 않은 대상은 작업 응답의 미완료에 새 사유로 오르고 인시던트는 이동의 것 하나다`() {
        val travel = "PUMP-01${InspectAsset.TRAVEL_SUFFIX}"
        World(port = { FailingWithoutFaultPort(it, setOf(taskOf(travel))) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.settled() }

            val response = w.mw.pending().last()
            assertEquals(mapOf(travel to Middleware.UNCLASSIFIED, "PUMP-01" to SKIPPED), response.incompleteUnits)
            assertEquals(4, response.completedUnits.size)
            // 앞 단위의 실패가 이미 인시던트를 열었다. 내지 않은 단위는 계획 단계 실패와 같은 모양이라 따로 열지 않는다.
            assertEquals(listOf(travel), w.mw.incidents().map { it.unitId })
            assertEquals(1, exec.eventTrail.count { it.kind == SKIPPED && it.detail.startsWith("PUMP-01:") }, exec.eventTrail.toString())

            // 판단 기록은 인코딩을 지나도 그 단위를 «태스크 없이 실패» 로 싣는다 — 리빌드가 그 판정을 그대로 옮긴다.
            val record = JudgmentRecordWire.decode(JudgmentRecordWire.encode(w.mw.judgmentRecord(exec.executionId)!!))
            val unit = record.units.single { it.unitId == "PUMP-01" }
            assertEquals("" to null, unit.taskId to unit.requestedAt)
            assertEquals(UnitState.FAILED to SKIPPED, unit.verdict!!.state to unit.verdict!!.failureClass)
        }
    }

    @Test
    fun `이동이 전부 실패하면 점검은 하나도 안 나가고 실행은 FAILED 다`() {
        val travels = points.map { taskOf("${it.target}${InspectAsset.TRAVEL_SUFFIX}") }.toSet()
        World(port = { FailingWithoutFaultPort(it, travels) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.settled() }

            assertEquals(travels.toList(), w.tasks().map { it.taskId })
            assertEquals(points.map { it.target }.toSet(), exec.units.filter { it.failureClass == SKIPPED }.map { it.unitId }.toSet())
            assertEquals(PhysicalState.FAILED, exec.physicalState)
        }
    }

    @Test
    fun `리비전이 그 점검을 다시 계획해도 이동이 시도된 실패로 남아 있으면 다시 내지 않는다`() {
        val travel = "PUMP-01${InspectAsset.TRAVEL_SUFFIX}"
        World(port = { FailingWithoutFaultPort(it, setOf(taskOf(travel))) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.settled() }
            val issued = w.tasks().size

            // 태스크 없는 실패는 리비전이 다시 계획한다(계획 단계 실패와 같은 규칙). 이동은 시도된 실패라 그대로다.
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order().copy(version = 2), ROBOT))
            assertEquals(2 to UnitState.PENDING, exec.units.single { it.unitId == "PUMP-01" }.let { it.revision to it.state })
            w.drive { exec.settled() }

            assertEquals(issued, w.tasks().size, "선행이 실패로 남았는데 리비전 뒤에 점검을 냈다")
            val skipped = exec.units.single { it.unitId == "PUMP-01" }
            assertEquals(Triple(UnitState.FAILED, "", SKIPPED), Triple(skipped.state, skipped.taskId, skipped.failureClass))
            assertEquals(2 to SKIPPED, w.mw.pending().last().let { it.version to it.incompleteUnits["PUMP-01"] })
        }
    }

    @Test
    fun `리빌드한 실행도 같은 규칙이다 — 다시 붙은 이동이 실패면 그 대상의 점검을 내지 않는다`() {
        val travel = "PUMP-01${InspectAsset.TRAVEL_SUFFIX}"
        val port: (RobotPort) -> RobotPort = { FailingWithoutFaultPort(it, setOf(taskOf(travel))) }
        World(port = port).use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT))
            w.mw.pump()
            // 앞 인스턴스는 이동을 낸 뒤 그 종료를 읽기 전에 내려간다 — 시계만 민다.
            var waited = 0
            while (w.state(taskOf(travel)) != "SUCCEEDED" && waited++ < 120) {
                w.harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
            }
            assertEquals("SUCCEEDED", w.state(taskOf(travel)))

            // 기록 없이 스냅숏으로 리빌드한다. 다시 붙은 이동의 종료를 이 인스턴스가 처음 읽는다.
            val again = Middleware(port(ClientRobotPort(w.harness.client())), now = { w.harness.clock.now() })
            val rebuilt = assertIs<Middleware.Submission.Accepted>(again.resume(order(), ROBOT, ActiveMission(InspectAsset(), null))).execution
            var rounds = 0
            while (!rebuilt.settled() && rounds++ < 120) {
                again.pump()
                if (rebuilt.settled()) break
                w.harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
            }
            assertTrue(rebuilt.settled(), rebuilt.units.map { it.unitId to it.state }.toString())

            assertTrue(w.tasks().none { it.taskId == taskOf("PUMP-01") }, "리빌드한 실행이 이동이 실패한 대상의 점검을 냈다")
            assertEquals(SKIPPED, rebuilt.units.single { it.unitId == "PUMP-01" }.failureClass)
            assertEquals(PhysicalState.PARTIAL, rebuilt.physicalState)
        }
    }

    // ── 건너뛴 대상 재작업: 순회가 종결한 뒤 사람이 한 쌍씩 낸다

    private fun travelId(target: String) = "$target${InspectAsset.TRAVEL_SUFFIX}"

    private fun failing(vararg taskIds: String): (RobotPort) -> RobotPort = { FailingWithoutFaultPort(it, taskIds.toSet()) }

    private fun Middleware.Execution.unit(unitId: String) = units.single { it.unitId == unitId }

    private fun Middleware.Execution.attemptsOf(vararg unitIds: String) = unitIds.map { unit(it).attempt }

    /** 순회를 접수하고 종결할 때까지 돈다. */
    private fun World.runToEnd(): Middleware.Execution {
        val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order(), ROBOT)).execution
        drive { exec.settled() }
        return exec
    }

    /** 첫 대상의 이동만 결함 없이 실패한 순회가 `PARTIAL` 로 종결할 때까지 돈다. */
    private fun World.partialWithSkippedFirst(): Middleware.Execution {
        val exec = runToEnd()
        assertEquals(PhysicalState.PARTIAL, exec.physicalState)
        assertEquals(SKIPPED, exec.unit("PUMP-01").failureClass)
        return exec
    }

    @Test
    fun `종결한 순회에서 건너뛴 대상을 재작업하면 그 대상의 이동과 점검이 새 시도로 나가고 실행은 PHYSICALLY_DONE 으로 끝난다`() {
        val travel = travelId("PUMP-01")
        World(port = failing(taskOf(travel))).use { w ->
            val exec = w.partialWithSkippedFirst()
            val issued = w.tasks().size

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, "PUMP-01", OperatorDecision.REWORK, OPERATOR))
            assertEquals(PhysicalState.RUNNING, exec.physicalState)
            listOf(travel, "PUMP-01").forEach { id ->
                val unit = exec.unit(id)
                assertEquals(Triple(1, UnitState.PENDING, ""), Triple(unit.attempt, unit.state, unit.taskId), id)
                assertNull(unit.failureClass, id)
                assertEquals("operator ordered rework", unit.note, id)
            }

            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(
                listOf("${taskOf(travel)}@r1", "${taskOf("PUMP-01")}@r1"),
                w.tasks().map { it.taskId }.drop(issued),
                "같은 실행 안에서 이동 뒤 점검이 새 시도로 나가야 한다",
            )
            assertEquals(6, exec.completedUnits.size)
            assertEquals(
                listOf(0, 0, 0, 0),
                exec.attemptsOf(travelId("PANEL-3"), "PANEL-3", travelId("COMP-2"), "COMP-2"),
                "다른 대상의 시도가 올랐다",
            )
        }
    }

    @Test
    fun `재작업한 이동이 또 실패하면 점검은 다시 건너뛰고 실행은 다시 PARTIAL 이며 다시 재작업하면 r2 로 나간다`() {
        val travel = travelId("PUMP-01")
        World(port = failing(taskOf(travel), "${taskOf(travel)}@r1")).use { w ->
            val exec = w.partialWithSkippedFirst()
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, "PUMP-01", OperatorDecision.REWORK, OPERATOR))
            w.drive { exec.settled() }

            assertEquals(PhysicalState.PARTIAL, exec.physicalState)
            assertEquals(listOf(1, 1), exec.attemptsOf(travel, "PUMP-01"))
            assertEquals(UnitState.FAILED to "", exec.unit("PUMP-01").let { it.state to it.taskId })
            assertEquals(SKIPPED, exec.unit("PUMP-01").failureClass)
            assertEquals(UnitState.FAILED, exec.unit(travel).state)
            assertTrue(w.tasks().any { it.taskId == "${taskOf(travel)}@r1" })
            assertTrue(w.tasks().none { it.taskId.startsWith(taskOf("PUMP-01")) && !it.taskId.contains(InspectAsset.TRAVEL_SUFFIX) }, "건너뛴 점검이 나갔다")
            assertEquals(2, w.mw.incidents().count { it.unitId == travel }, "재작업한 이동의 새 실패는 보통 경로로 새 인시던트를 연다")

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, "PUMP-01", OperatorDecision.REWORK, OPERATOR))
            assertEquals(listOf(2, 2), exec.attemptsOf(travel, "PUMP-01"))
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(
                listOf("${taskOf(travel)}@r2", "${taskOf("PUMP-01")}@r2"),
                w.tasks().map { it.taskId }.takeLast(2),
            )
        }
    }

    @Test
    fun `순회가 종결하기 전에는 건너뛴 대상의 재작업을 받지 않는다 — 단위·태스크·인시던트·판단 기록이 그대로다`() {
        val travel = travelId("PUMP-01")
        World(port = failing(taskOf(travel))).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.unit("PUMP-01").failureClass == SKIPPED }
            assertFalse(exec.physicalState.isSettled, "다음 대상이 도는 중이어야 이 시험이 선다")
            val tasksBefore = w.tasks().map { it.taskId }
            val responsesBefore = w.mw.responses().size

            val refused = assertIs<ResolveOutcome.Refused>(w.mw.resolve(exec.executionId, "PUMP-01", OperatorDecision.REWORK, OPERATOR))
            assertTrue("종결" in refused.reason, refused.reason)

            assertEquals(listOf(0, 0), exec.attemptsOf(travel, "PUMP-01"))
            assertEquals(listOf(UnitState.FAILED, UnitState.FAILED), listOf(travel, "PUMP-01").map { exec.unit(it).state })
            assertEquals(SKIPPED, exec.unit("PUMP-01").failureClass)
            assertEquals(tasksBefore, w.tasks().map { it.taskId })
            assertEquals(responsesBefore, w.mw.responses().size, "거부가 작업 응답을 냈다")
            assertNull(w.mw.incidents().single { it.unitId == travel }.resolution)
            assertTrue(w.mw.judgmentRecord(exec.executionId)!!.units.all { it.decision == null })

            // 종결한 뒤에는 같은 요청을 받는다 — 거부는 때의 문제였다.
            w.drive { exec.settled() }
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, "PUMP-01", OperatorDecision.REWORK, OPERATOR))
        }
    }

    @Test
    fun `에이전트는 건너뛴 대상도 재작업하거나 확인하지 못한다 — 아무것도 안 바뀐다`() {
        val travel = travelId("PUMP-01")
        World(port = failing(taskOf(travel))).use { w ->
            val exec = w.partialWithSkippedFirst()
            OperatorDecision.entries.forEach { decision ->
                val refused = assertIs<ResolveOutcome.Refused>(w.mw.resolve(exec.executionId, "PUMP-01", decision, AGENT), "$decision")
                assertTrue(AGENT.id in refused.reason, refused.reason)
            }
            assertEquals(PhysicalState.PARTIAL, exec.physicalState)
            assertEquals(listOf(0, 0), exec.attemptsOf(travel, "PUMP-01"))
            assertEquals(listOf(UnitState.FAILED, UnitState.FAILED), listOf(travel, "PUMP-01").map { exec.unit(it).state })
            assertNull(w.mw.incidents().single { it.unitId == travel }.resolution)
            assertTrue(w.mw.judgmentRecord(exec.executionId)!!.units.all { it.decision == null })
        }
    }

    @Test
    fun `건너뛴 대상에 확인은 거부한다 — 점검을 하지 않은 단위는 확인할 것이 없고 아무것도 안 바뀐다`() {
        val travel = travelId("PUMP-01")
        World(port = failing(taskOf(travel))).use { w ->
            val exec = w.partialWithSkippedFirst()
            val responsesBefore = w.mw.responses().size

            val refused = assertIs<ResolveOutcome.Refused>(w.mw.resolve(exec.executionId, "PUMP-01", OperatorDecision.CONFIRM_DONE, OPERATOR))
            assertTrue("확인할 것이 없다" in refused.reason, refused.reason)

            assertEquals(PhysicalState.PARTIAL, exec.physicalState)
            assertEquals(listOf(UnitState.FAILED, UnitState.FAILED), listOf(travel, "PUMP-01").map { exec.unit(it).state })
            assertEquals(SKIPPED, exec.unit("PUMP-01").failureClass)
            assertEquals(responsesBefore, w.mw.responses().size)
            assertNull(w.mw.incidents().single { it.unitId == travel }.resolution)
            assertTrue(w.mw.judgmentRecord(exec.executionId)!!.units.all { it.decision == null })
        }
    }

    @Test
    fun `건너뛴 단위 모양이 아니면 NotHeld 다 — 이동 단위, 시도 뒤에 실패한 점검, 끝난 단위, 없는 단위`() {
        // 둘째 대상의 이동이 실패하고(건너뛴 점검 PANEL-3), 첫 대상의 점검이 시도 뒤에 실패한다(PUMP-01).
        World(port = failing(taskOf(travelId("PANEL-3")), taskOf("PUMP-01"))).use { w ->
            val exec = w.runToEnd()
            assertEquals(PhysicalState.PARTIAL, exec.physicalState)
            assertEquals(SKIPPED, exec.unit("PANEL-3").failureClass)
            assertEquals(UnitState.FAILED to false, exec.unit("PUMP-01").let { it.state to it.taskId.isEmpty() }, "PUMP-01 은 시도 뒤에 실패해야 한다")
            val tasksBefore = w.tasks().map { it.taskId }

            listOf(
                travelId("PANEL-3") to "건너뛴 점검의 선행 이동",
                "PUMP-01" to "시도 뒤에 실패한 점검",
                "COMP-2" to "끝난 단위",
                "NO-SUCH" to "없는 단위",
            ).forEach { (id, why) ->
                assertEquals(ResolveOutcome.NotHeld, w.mw.resolve(exec.executionId, id, OperatorDecision.REWORK, OPERATOR), why)
            }
            assertEquals(ResolveOutcome.NotHeld, w.mw.resolve("exec-없음", "PANEL-3", OperatorDecision.REWORK, OPERATOR))

            assertEquals(PhysicalState.PARTIAL, exec.physicalState)
            assertEquals(0, exec.units.sumOf { it.attempt })
            assertEquals(tasksBefore, w.tasks().map { it.taskId })
            assertTrue(w.mw.incidents().all { it.resolution == null })
        }
    }

    @Test
    fun `취소로 끝난 실행의 건너뛴 대상은 재작업하지 않는다`() {
        val travel = travelId("PUMP-01")
        World(port = failing(taskOf(travel))).use { w ->
            val exec = w.partialWithSkippedFirst()
            assertTrue(w.mw.cancel(exec.executionId))
            w.drive { exec.settled() }
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(SKIPPED, exec.unit("PUMP-01").failureClass)
            val tasksBefore = w.tasks().map { it.taskId }

            val refused = assertIs<ResolveOutcome.Refused>(w.mw.resolve(exec.executionId, "PUMP-01", OperatorDecision.REWORK, OPERATOR))
            assertTrue("취소" in refused.reason, refused.reason)

            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(listOf(0, 0), exec.attemptsOf(travel, "PUMP-01"))
            assertEquals(UnitState.FAILED, exec.unit("PUMP-01").state)
            assertEquals(tasksBefore, w.tasks().map { it.taskId })
        }
    }

    @Test
    fun `이동이 전부 실패해 FAILED 로 끝난 실행도 재작업을 받되 같은 기체에 다른 활성 실행이 있으면 거부한다`() {
        val travels = points.map { taskOf(travelId(it.target)) }.toTypedArray()
        World(port = failing(*travels)).use { w ->
            val exec = w.runToEnd()
            assertEquals(PhysicalState.FAILED, exec.physicalState)
            val other = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order().copy(jobOrderId = "PATROL-8"), ROBOT)).execution
            assertFalse(other.physicalState.isSettled, "다른 활성 실행이 서 있어야 이 시험이 선다")
            val tasksBefore = w.tasks().map { it.taskId }

            val refused = assertIs<ResolveOutcome.Refused>(w.mw.resolve(exec.executionId, "PUMP-01", OperatorDecision.REWORK, OPERATOR))
            assertTrue(other.executionId in refused.reason, refused.reason)
            assertEquals(PhysicalState.FAILED, exec.physicalState)
            assertEquals(listOf(0, 0), exec.attemptsOf(travelId("PUMP-01"), "PUMP-01"))
            assertEquals(tasksBefore, w.tasks().map { it.taskId })
            assertNull(w.mw.incidents().first { it.executionId == exec.executionId && it.unitId == travelId("PUMP-01") }.resolution)

            // 다른 실행이 끝나면 같은 요청을 받는다.
            assertTrue(w.mw.cancel(other.executionId))
            w.drive { other.settled() }
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, "PUMP-01", OperatorDecision.REWORK, OPERATOR))
            assertEquals(PhysicalState.RUNNING, exec.physicalState)
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PARTIAL, exec.physicalState, "나머지 두 대상은 그대로 건너뛴 채다")
            assertEquals(
                listOf("${taskOf(travelId("PUMP-01"))}@r1", "${taskOf("PUMP-01")}@r1"),
                w.tasks().map { it.taskId }.filter { it.endsWith("@r1") },
            )
        }
    }

    @Test
    fun `재작업 판단은 이동의 인시던트에 결정자와 함께 붙고 내보내는 인시던트 줄도 결정자를 싣는다`() {
        val travel = travelId("PUMP-01")
        World(port = failing(taskOf(travel))).use { w ->
            val exec = w.partialWithSkippedFirst()
            val opened = w.mw.incidents().single()
            assertEquals(travel, opened.unitId)
            assertFalse(opened.unresolved, "이동 FAILED 는 보류가 아니었다")
            assertNull(opened.resolution)

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, "PUMP-01", OperatorDecision.REWORK, OPERATOR))

            val bundle = w.mw.incidents().single()
            assertEquals(travel, bundle.unitId)
            assertFalse(bundle.unresolved)
            val resolution = assertNotNull(bundle.resolution)
            assertEquals(IncidentDecision.REWORK to OPERATOR, resolution.decision to resolution.decidedBy)
            val line = LedgerExport.incidents(w.mw.incidents())
            assertTrue("\"resolution\":{\"decision\":\"REWORK\"" in line, line)
            // 판 8(§15.322) — 판단자가 승인자와 같은 모양으로 실린다.
            assertTrue("\"decidedBy\":{\"id\":\"${OPERATOR.id}\",\"kind\":\"PERSON\"}" in line, line)
            assertEquals("8", LedgerExport.SCHEMA_VERSION)
        }
    }

    @Test
    fun `재작업하면 RUNNING 작업 응답이 하나 나가고 두 단위가 재작업 메모로 오르며 다시 눌러도 더 나가지 않는다`() {
        val travel = travelId("PUMP-01")
        World(port = failing(taskOf(travel))).use { w ->
            val exec = w.partialWithSkippedFirst()
            val before = w.mw.responses().size

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, "PUMP-01", OperatorDecision.REWORK, OPERATOR))
            val sent = w.mw.responses().drop(before)
            assertEquals(1, sent.size, "재작업이 작업 응답을 하나 내지 않았다: $sent")
            assertEquals(PhysicalState.RUNNING, sent.single().physicalState)
            assertEquals(
                mapOf(travel to "operator ordered rework", "PUMP-01" to "operator ordered rework"),
                sent.single().incompleteUnits,
            )

            assertEquals(ResolveOutcome.NotHeld, w.mw.resolve(exec.executionId, "PUMP-01", OperatorDecision.REWORK, OPERATOR), "이미 되돌린 대상을 다시 받았다")
            assertEquals(before + 1, w.mw.responses().size)
        }
    }

    @Test
    fun `판단 기록이 두 단위의 판단과 오른 시도를 싣고 이동을 내기 전에 리빌드하면 r1 로 낸다`() {
        val travel = travelId("PUMP-01")
        val port = failing(taskOf(travel))
        World(port = port).use { w ->
            val exec = w.partialWithSkippedFirst()
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, "PUMP-01", OperatorDecision.REWORK, OPERATOR))

            val record = w.mw.judgmentRecord(exec.executionId)!!
            listOf(travel, "PUMP-01").forEach { id ->
                val unit = record.units.single { it.unitId == id }
                val decision = assertNotNull(unit.decision, "$id 의 판단이 기록에 없다")
                assertEquals(Triple(OperatorDecision.REWORK, OPERATOR, 0), Triple(decision.decision, decision.approver, decision.attempt), id)
                assertEquals(1, unit.attempt, id)
            }
            assertTrue(record.units.filter { it.unitId !in setOf(travel, "PUMP-01") }.all { it.decision == null })

            // 이동을 내기 전에 리빌드한다. 스냅숏에는 시도 0 의 이동뿐이고 재작업은 기록만 안다.
            val again = Middleware(port(ClientRobotPort(w.harness.client())), now = { w.harness.clock.now() })
            val rebuilt = assertIs<Middleware.Submission.Accepted>(
                again.resume(order(), ROBOT, ActiveMission(InspectAsset(), null), JudgmentRecordWire.decode(JudgmentRecordWire.encode(record))),
            ).execution
            assertEquals(listOf(1, 1), rebuilt.attemptsOf(travel, "PUMP-01"))
            assertEquals(listOf(UnitState.PENDING, UnitState.PENDING), listOf(travel, "PUMP-01").map { rebuilt.unit(it).state })
            var rounds = 0
            while (!rebuilt.settled() && rounds++ < 120) {
                again.pump()
                if (rebuilt.settled()) break
                w.harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
            }
            assertTrue(rebuilt.settled(), rebuilt.units.map { it.unitId to it.state }.toString())

            assertEquals(
                listOf("${taskOf(travel)}@r1", "${taskOf("PUMP-01")}@r1"),
                w.tasks().map { it.taskId }.filter { it.endsWith("@r1") },
            )
            assertEquals(PhysicalState.PHYSICALLY_DONE, rebuilt.physicalState)
        }
    }

    @Test
    fun `리빌드할 때 기체에 r1 이동이 있으면 기록 없이도 그 시도로 붙는다`() {
        val travel = travelId("PUMP-01")
        val port = failing(taskOf(travel))
        World(port = port).use { w ->
            val exec = w.partialWithSkippedFirst()
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, "PUMP-01", OperatorDecision.REWORK, OPERATOR))
            w.mw.pump()
            assertNotNull(w.state("${taskOf(travel)}@r1"), "재작업한 이동이 기체에 나가 있어야 이 시험이 선다")

            val again = Middleware(port(ClientRobotPort(w.harness.client())), now = { w.harness.clock.now() })
            val rebuilt = assertIs<Middleware.Submission.Accepted>(again.resume(order(), ROBOT, ActiveMission(InspectAsset(), null))).execution
            assertEquals(1, rebuilt.unit(travel).attempt, "스냅숏의 r1 에서 시도를 되찾지 못했다")
            var rounds = 0
            while (!rebuilt.settled() && rounds++ < 120) {
                again.pump()
                if (rebuilt.settled()) break
                w.harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
            }
            assertTrue(rebuilt.settled(), rebuilt.units.map { it.unitId to it.state }.toString())
            assertEquals(UnitState.DONE to "${taskOf(travel)}@r1", rebuilt.unit(travel).let { it.state to it.taskId })
        }
    }

    /**
     * 정해 둔 태스크의 성공을 결함 없는 실패로 바꿔 돌려주는 포트. 에뮬레이터의 실패 주입(`ForceFault`)은 늘 결함을 세우고, 이 기체의
     * 결함은 새 태스크를 못 받는다고 말해 다음 단위를 세운다. 그래서 결함 없이 이동만 실패하는 경우를 이 대역이 만든다. 기체에서는
     * 태스크가 성공한 채다 — 스냅숏은 손대지 않는다.
     */
    private class FailingWithoutFaultPort(private val delegate: RobotPort, private val failing: Set<String>) : RobotPort by delegate {
        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> =
            delegate.watch(robotId, handle).map {
                if (handle.taskId in failing && it.state == TaskState.TASK_STATE_SUCCEEDED) {
                    it.toBuilder().setState(TaskState.TASK_STATE_FAILED).clearFault().build()
                } else {
                    it
                }
            }
    }

    private companion object {
        const val ROBOT = "quad-01"

        val OPERATOR = Approver("op-1", ApproverKind.PERSON)
        val AGENT = Approver("narrator-1", ApproverKind.AGENT)

        /** 선행 단위가 완료되지 않아 내지 않은 단위의 실패 분류. 상위가 작업 응답에서 읽는 값이라 문자 그대로 고정한다. */
        const val SKIPPED = "PREDECESSOR_NOT_DONE"
    }
}

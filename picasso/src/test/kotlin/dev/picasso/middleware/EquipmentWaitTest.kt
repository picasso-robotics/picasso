package dev.picasso.middleware

import dev.picasso.harness.Harness
import dev.picasso.middleware.mission.Activation
import dev.picasso.middleware.mission.InMemoryMissionCatalog
import dev.picasso.middleware.mission.MissionFixtures
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 설비 대기 — 임무 정의의 대기 노드가 엔진에서 이름 있는 신호를 기다리고, 기한이 지나면 정해 둔 상태로 간다.
 *
 * 대역은 `SequencingRackTest` 와 같다(in-process 하네스, `ClientRobotPort`, `CellMimic`). 정의는 데이터판
 * `PrepareSequencedRack` 의 맨 앞에 «랙 도착 신호가 `true` 가 될 때까지» 대기를 둔 것이다.
 *
 * ★**대기 중에는 로봇 태스크가 하나도 없어야 한다.** 신호를 못 본 채 로봇이 움직이면 대기를 둔 뜻이 없다 — 그래서
 * 기한 뒤 갈래마다 에뮬레이터의 태스크 수를 댄다.
 */
class EquipmentWaitTest {

    private class World(wait: String) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val cell = CellMimic(now = { harness.clock.now() })
        val catalog = InMemoryMissionCatalog(now = { harness.clock.now() })
        val mw: Middleware

        init {
            val activated = catalog.activate(wait, MissionFixtures.SIGNALS, FloorOwnership.None, MissionFixtures.SITE_SKILLS)
            assertIs<Activation.Activated>(activated, "$activated")
            mw = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() }, missions = catalog)
        }

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList

        /** 시간을 [by] 만큼 밀고 미들웨어를 한 번 돌린다. */
        fun step(by: Duration) {
            harness.advance(by)
            Thread.sleep(40)
            mw.pump()
        }

        fun drive(rounds: Int = 40, step: Duration = Duration.ofSeconds(30), until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                step(step)
                if (until()) return
            }
            error("조건에 못 미쳤다: tasks=${tasks().map { it.taskId to it.taskState }}")
        }

        override fun close() = harness.close()
    }

    private fun order(version: Int = 1) = JobOrder(
        jobOrderId = "SEQ-401",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = version,
        requiredEvidence = Evidence.E2,
        materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", 1), MaterialRequirement("ENGINE-COVER-B", 1)),
        equipmentRequirements = listOf(
            EquipmentRequirement("RACK-401.S01", EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to "ENGINE-COVER-A")),
            EquipmentRequirement("RACK-401.S02", EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to "ENGINE-COVER-B")),
            EquipmentRequirement("BIN-401-A", EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to "ENGINE-COVER-A")),
            EquipmentRequirement("BIN-401-B", EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to "ENGINE-COVER-B")),
        ),
    )

    private fun World.start(order: JobOrder = order()): Middleware.Execution {
        order.equipmentRequirements.filter { it.equipmentUse == EquipmentUse.DESTINATION }
            .forEach { cell.program(it.id, it.properties[EquipmentUse.PROP_MATERIAL]) }
        val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order, ROBOT)).execution
        mw.pump() // 대기 단위가 출발한다 — 시작 시각이 지금이다
        assertEquals(UnitState.RUNNING, exec.waitUnit().state)
        return exec
    }

    private fun Middleware.Execution.waitUnit() = units.single { it.unitId == MissionFixtures.WAIT_NODE }
    private fun Middleware.Execution.settled() = physicalState.isSettled && active == null
    private fun Middleware.Execution.signalTrail() = eventTrail.filter { it.kind == "CELL_SIGNAL" && it.detail.startsWith("signal ") }

    // ── 신호를 본다

    @Test
    fun `신호가 기대 값을 읽으면 대기가 E2 로 끝나고 그 뒤에 로봇 단위가 출발한다`() {
        World(MissionFixtures.withArrivalWait()).use { w ->
            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "false")
            val exec = w.start()
            assertEquals(Route.SIGNAL, exec.waitUnit().route)
            assertEquals(1, exec.missionVersion)

            w.step(Duration.ofSeconds(30))
            assertEquals(UnitState.RUNNING, exec.waitUnit().state)
            assertEquals(0, w.tasks().size, "대기 중에 로봇 태스크가 나갔다")

            val arrivedAt = w.harness.clock.now()
            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "true", at = arrivedAt)
            w.step(Duration.ofSeconds(1))
            val wait = exec.waitUnit()
            assertEquals(UnitState.DONE, wait.state)
            assertEquals(Evidence.E2, wait.reached, "설비가 관측한 사실이라 E2 다")
            assertEquals(Verification.MATCHED, wait.verification)
            assertEquals(arrivedAt, wait.evidenceAt)

            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(listOf(MissionFixtures.WAIT_NODE, "RACK-401.S01", "RACK-401.S02"), exec.completedUnits)
            assertEquals(2, w.tasks().size, "대기 단위가 로봇 태스크가 됐다")
            assertEquals(Evidence.E2, w.mw.responses().last().reachedEvidence)
        }
    }

    @Test
    fun `신호가 이미 기대 값이면 바로 끝난다 — 지금 값으로 판정한다`() {
        // 상태 신호만 다룬다. 시작 전부터 그 값이었다면 그 상태가 이미 성립한 것이다(이벤트형 신호는 한계).
        World(MissionFixtures.withArrivalWait()).use { w ->
            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "true", at = Instant.parse("2020-01-01T00:00:00Z"))
            val exec = w.start()
            w.mw.pump()
            assertEquals(UnitState.DONE, exec.waitUnit().state)
        }
    }

    @Test
    fun `신호 읽기는 값이 바뀔 때만 자취에 남는다`() {
        World(MissionFixtures.withArrivalWait()).use { w ->
            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "false")
            val exec = w.start()
            repeat(5) { w.step(Duration.ofSeconds(10)) }
            assertEquals(1, exec.signalTrail().size, "같은 값을 진행마다 남겼다: ${exec.signalTrail()}")

            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "true")
            w.step(Duration.ofSeconds(1))
            val trail = exec.signalTrail().map { it.detail }
            assertEquals(2, trail.size, "$trail")
            assertTrue(trail[0].startsWith("signal rack_present: value=false"), "$trail")
            assertTrue(trail[1].startsWith("signal rack_present: value=true"), "$trail")
        }
    }

    // ── 기한

    @Test
    fun `신호를 못 읽으면 계속 기다리고 기한 시각에는 아직 서 있다가 넘으면 운영자 보류로 선다`() {
        World(MissionFixtures.withArrivalWait(deadlineSeconds = 120)).use { w ->
            // 신호를 정하지 않았다 — 셀이 그 이름에 말이 없다(null). «기대 값이 아님» 으로 접지 않는다.
            val exec = w.start()
            w.step(Duration.ofSeconds(120))
            assertEquals(UnitState.RUNNING, exec.waitUnit().state, "기한 시각에 벌써 넘겼다")
            assertEquals(PhysicalState.RUNNING, exec.physicalState)
            assertEquals(listOf("signal rack_present: no signal"), exec.signalTrail().map { it.detail })

            w.step(Duration.ofSeconds(1))
            val wait = exec.waitUnit()
            assertEquals(UnitState.OPERATOR_HOLD, wait.state)
            assertEquals(WaitSpec.SIGNAL_DEADLINE, wait.failureClass)
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState)
            assertEquals(0, w.tasks().size, "기한 뒤 보류인데 로봇이 움직였다")

            // 라인이 멈춘다 — 더 돌려도 다음 단위가 안 나간다.
            repeat(3) { w.step(Duration.ofSeconds(30)) }
            assertEquals(0, w.tasks().size)
            assertTrue(w.mw.responses().last().operatorRequired)
            assertEquals(WaitSpec.SIGNAL_DEADLINE, w.mw.responses().last().incompleteUnits[MissionFixtures.WAIT_NODE])

            val incident = w.mw.incidents().single()
            assertEquals(MissionFixtures.WAIT_NODE, incident.unitId)
            assertEquals(WaitSpec.SIGNAL_DEADLINE, incident.failureClass)
            assertEquals("SIGNAL", incident.route)
            assertTrue(incident.unresolved)
            assertEquals(1, incident.intent.missionVersion)
            assertEquals(
                mapOf("signal" to "rack_present", "expect" to "true", "deadlineSeconds" to "120", "onDeadline" to "OPERATOR_HOLD"),
                incident.intent.unitParameters,
                "무엇을 언제까지 기다렸는지가 인시던트에 안 실렸다",
            )
            // ★기한 시점의 관측이 근거 윈도우 안에 있다 — 값이 안 바뀌어 시작 무렵에 한 번 적힌 줄은 윈도우 밖이다.
            assertTrue(
                incident.evidenceWindow.any { it.detail == "signal rack_present at deadline: no signal" },
                "${incident.evidenceWindow}",
            )
        }
    }

    @Test
    fun `보류를 재작업하면 기한이 다시 시작하고 확인하면 근거 E0 로 진행한다`() {
        World(MissionFixtures.withArrivalWait(deadlineSeconds = 60)).use { w ->
            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "false")
            val exec = w.start()
            w.step(Duration.ofSeconds(61))
            assertEquals(UnitState.OPERATOR_HOLD, exec.waitUnit().state)

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, MissionFixtures.WAIT_NODE, OperatorDecision.REWORK, OPERATOR))
            w.mw.pump() // 다시 출발한다 — 새 시도, 새 시작 시각
            val restarted = exec.waitUnit()
            assertEquals(UnitState.RUNNING, restarted.state)
            assertEquals(1, restarted.attempt)
            assertEquals(w.harness.clock.now(), restarted.requestedAt)
            w.step(Duration.ofSeconds(60))
            assertEquals(UnitState.RUNNING, exec.waitUnit().state, "기한이 다시 시작하지 않았다")
            // 첫 시도의 값, 기한 시점의 값, 새 시도의 첫 값.
            assertEquals(3, exec.signalTrail().size, "새 시도의 첫 값을 다시 남기지 않았다: ${exec.signalTrail()}")
            w.step(Duration.ofSeconds(1))
            assertEquals(UnitState.OPERATOR_HOLD, exec.waitUnit().state)

            // 사람이 신호 대신 확인했다 — 근거는 E0 로 남고 작업 응답의 등급도 거기로 내려간다.
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, MissionFixtures.WAIT_NODE, OperatorDecision.CONFIRM_DONE, OPERATOR))
            assertEquals(UnitState.DONE, exec.waitUnit().state)
            assertEquals(Evidence.E0, exec.waitUnit().reached)
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(Evidence.E0, w.mw.responses().last().reachedEvidence)
            assertEquals(2, w.mw.incidents().size, "기한마다 인시던트 하나")
            assertEquals(listOf(IncidentDecision.REWORK, IncidentDecision.CONFIRM_DONE), w.mw.incidents().map { it.resolution?.decision })
        }
    }

    @Test
    fun `기한 뒤 ABORTED 면 대기 단위는 FAILED 로 남고 실행이 중단되며 남은 단위는 안 나간다`() {
        World(MissionFixtures.withArrivalWait(deadlineSeconds = 60, onDeadline = DeadlineOutcome.ABORTED)).use { w ->
            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "false")
            val exec = w.start()
            w.step(Duration.ofSeconds(61))

            val wait = exec.waitUnit()
            assertEquals(UnitState.FAILED, wait.state)
            assertEquals(WaitSpec.SIGNAL_DEADLINE, wait.failureClass)
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(listOf(UnitState.ABORTED, UnitState.ABORTED), exec.units.drop(1).map { it.state })
            assertNull(exec.active)
            // ★취소 요청이 아니다 — 아무도 취소하지 않았는데 취소 응답이 있으면 «누가 멈췄나» 에 거짓으로 답한다.
            assertNull(w.mw.lastCancel(exec.executionId), "기한 중단이 취소 응답을 남겼다")

            // ★남은 단위가 안 나간다 — 같은 라운드에도, 더 돌려도.
            repeat(4) { w.step(Duration.ofSeconds(30)) }
            assertEquals(0, w.tasks().size, "중단된 실행의 다음 단위가 나갔다")
            assertEquals(PhysicalState.ABORTED, exec.physicalState)

            val response = w.mw.responses().last()
            assertEquals(PhysicalState.ABORTED, response.physicalState)
            assertEquals(emptyList(), response.completedUnits)
            val notStarted = "not started: rack_present deadline aborted the execution"
            assertEquals(
                mapOf(MissionFixtures.WAIT_NODE to WaitSpec.SIGNAL_DEADLINE, "RACK-401.S01" to notStarted, "RACK-401.S02" to notStarted),
                response.incompleteUnits,
            )
            assertTrue(!response.operatorRequired, "정해 둔 중단이다 — 사람의 판단을 기다리지 않는다")
            assertEquals(1, w.mw.responses().count { it.physicalState == PhysicalState.ABORTED }, "중단 통보가 거듭 나갔다")

            val incident = w.mw.incidents().single()
            assertEquals(WaitSpec.SIGNAL_DEADLINE, incident.failureClass)
            assertTrue(!incident.unresolved, "중단은 운영자 판단을 기다리지 않는다")

            // 종료한 실행은 리비전으로 다시 열리지 않는다 — 재작업은 새 작업 지시다.
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(order(version = 2), ROBOT))
        }
    }

    // ── 취소와 리비전

    @Test
    fun `대기 중에 취소하면 대기 단위는 ABORTED 이고 취소 경로로 끝난다`() {
        World(MissionFixtures.withArrivalWait()).use { w ->
            val exec = w.start()
            assertTrue(w.mw.cancel(exec.executionId))
            w.mw.pump()

            assertEquals(UnitState.ABORTED, exec.waitUnit().state)
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            val report = assertNotNull(w.mw.lastCancel(exec.executionId))
            assertEquals(MissionFixtures.WAIT_NODE, report.inProgressUnit)
            assertEquals(listOf("RACK-401.S01", "RACK-401.S02"), report.notStartedUnits)
            assertEquals("not_applicable", report.cleanup)
            assertEquals(0, w.tasks().size)
        }
    }

    @Test
    fun `대기 중의 리비전은 기다림을 그대로 잇는다`() {
        World(MissionFixtures.withArrivalWait()).use { w ->
            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "false")
            val exec = w.start()
            val wait = exec.waitUnit()
            val startedAt = wait.requestedAt

            w.step(Duration.ofSeconds(30))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 2), ROBOT))
            assertSame(wait, exec.waitUnit(), "대기 단위가 바뀌었다")
            assertEquals(UnitState.RUNNING, wait.state)
            assertEquals(startedAt, wait.requestedAt, "기한이 다시 시작했다")
            assertEquals(0, w.tasks().size, "리비전이 대기 단위를 로봇으로 보냈다")

            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "true")
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(2, w.tasks().size)
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        val OPERATOR = Approver("operator-1", ApproverKind.PERSON)
    }
}

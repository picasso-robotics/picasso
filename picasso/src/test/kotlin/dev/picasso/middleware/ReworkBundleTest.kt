package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
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
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **재작업 묶음의 경계**(§15.323 검토 고침) — 직접 지은 계획으로 본다. 코드 업무에는 없는 모양(이동 뒤 `pick_place`, 설비 근거를 요구하는 두 슬롯)을
 * 시험이 케이퍼빌리티로 짓는다.
 *
 * - 재기동 사이에 재작업한 시도가 «앞선 로봇 단위» 로 남아 설비 근거 없이 닫히지 않는다(고침 1).
 * - 첫 발행 직전 재검사를 못 지난 시도는 실패로 닫혀 실행이 종결하고, 같은 실행의 다른 대상 재작업이 막히지 않는다(고침 6).
 * - 앞 시도에서 태스크가 나간 `pick_place` 는 뒤에 건너뛴 묶음으로 되돌려도 물리 관문을 다시 지난다(고침 8).
 */
class ReworkBundleTest {

    /** 두 슬롯 — 각 슬롯을 집어 놓는다. [travel] 이면 첫 슬롯 앞에 이동을 두고 첫 슬롯이 그 이동을 기다린다. */
    private class Slots(private val travel: Boolean, private val chain: Boolean = false) : LogicalCapability {
        override val workMasterId: String = WORK_MASTER
        override val maxEvidence: Evidence = Evidence.E2

        override fun plan(order: JobOrder): List<ExecutionUnit> = buildList {
            if (travel) add(ExecutionUnit(TRAVEL, Route.ROBOT, "navigate_to", mapOf("location" to "CELL-1"), null, null, "CELL-1"))
            add(place(A, SLOT_A).also { if (travel) it.after = TRAVEL })
            // 사슬 — A 를 기다리는 C. C 의 목적지는 물리 점유로 보지 않는 자리다.
            if (chain) add(place(C, SLOT_C, DestinationObservation.OPTIONAL).also { it.after = A })
            if (!travel) add(place(B, SLOT_B))
        }

        private fun place(id: String, slot: String, observation: DestinationObservation = DestinationObservation.REQUIRED) = ExecutionUnit(
            id, Route.ROBOT, "pick_place", mapOf("object_id" to MATERIAL, "destination" to slot), MATERIAL, BIN, slot,
            destinationObservation = observation,
        )
    }

    /** 스냅숏의 손만 바꾸고, 정해 둔 태스크의 성공을 결함 없는 실패로 바꾸는 포트. */
    private class Port(private val delegate: RobotPort) : RobotPort by delegate {
        var hand: HoldKind? = null
        val failing = mutableSetOf<String>()
        override fun snapshot(robotId: String): RobotSnapshot? = delegate.snapshot(robotId)?.let { s ->
            hand?.let { s.copy(hold = HoldState.newBuilder().setKind(it).build()) } ?: s
        }

        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> = delegate.watch(robotId, handle).map {
            if (handle.taskId in failing && it.state == TaskState.TASK_STATE_SUCCEEDED) it.toBuilder().setState(TaskState.TASK_STATE_FAILED).clearFault().build() else it
        }
    }

    private class World(travel: Boolean = false, val required: Evidence = Evidence.E2, chain: Boolean = false) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val cell = CellMimic(now = { harness.clock.now() })
        val capability = Slots(travel, chain)
        val port = Port(ClientRobotPort(harness.client()))
        val mw = middleware()

        fun middleware() = Middleware(port, cell, capabilities = listOf(capability), now = { harness.clock.now() })

        init {
            cell.program(BIN, MATERIAL)
            cell.empty(SLOT_A)
            cell.empty(SLOT_B)
        }

        fun order() = JobOrder(ORDER, WORK_MASTER, 1, requiredEvidence = required)

        fun tasks(): List<String> = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList.map { it.taskId }

        fun drive(mw: Middleware = this.mw, rounds: Int = 300, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(1))
                Thread.sleep(30)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다: tasks=${tasks()}")
        }

        fun forceFault(unit: ExecutionUnit) {
            harness.oracle.forceFault(ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType("SKILL_EXECUTION_FAILED").setTaskId(unit.taskId).build())
        }

        override fun close() = harness.close()
    }

    private fun Middleware.Execution.unit(id: String) = units.single { it.unitId == id }

    /** A 가 쥔 채 실패하고 B 가 설비 근거로 끝난 실행 — `PARTIAL` 로 종결한다. */
    private fun World.failedFirst(mw: Middleware = this.mw): Middleware.Execution {
        val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order(), ROBOT)).execution
        val a = exec.unit(A)
        drive(mw) { a.hold.kind == HoldKind.HOLD_KIND_HOLDING }
        forceFault(a)
        drive(mw) { a.state == UnitState.FAILED }
        // B 가 놓이면 설비가 본다.
        cell.program(SLOT_B, MATERIAL)
        drive(mw) { exec.physicalState.isSettled && exec.active == null }
        assertEquals(UnitState.DONE to Evidence.E2, exec.unit(B).let { it.state to it.reached }, "이 시험이 보려는 경우가 아니다")
        assertEquals(PhysicalState.PARTIAL, exec.physicalState)
        return exec
    }

    @Test
    fun `발행 전에 기록을 넘겨 재기동해도 재작업한 시도는 설비 근거 없이 닫히지 않는다`() {
        World().use { w ->
            val exec = w.failedFirst()
            w.port.hand = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, A, OperatorDecision.REWORK, OPERATOR))
            val record = JudgmentRecordWire.decode(JudgmentRecordWire.encode(assertNotNull(w.mw.judgmentRecord(exec.executionId))))

            // 스냅숏에는 A 의 시도 0 과 B 가 있다 — 리빌드는 B 앞의 A 를 «앞선 로봇 단위» 로 둔다. 재작업한 시도는 그것이 아니다.
            val second = w.middleware()
            val rebuilt = assertIs<Middleware.Submission.Accepted>(second.resume(w.order(), ROBOT, ActiveMission(w.capability, null), record)).execution
            val a = rebuilt.unit(A)
            assertEquals(1 to UnitState.PENDING, a.attempt to a.state)
            w.drive(second) { a.taskId.endsWith("@r1") && a.state != UnitState.RUNNING && a.state != UnitState.VERIFYING }
            // 목적지는 비어 있다 — 설비가 못 봤으니 완료가 아니다.
            assertNotEquals(UnitState.DONE, a.state, "재작업한 시도가 설비 근거 없이 닫혔다: ${a.reached}")
            assertEquals(UnitState.UNVERIFIED, a.state)
        }
    }

    @Test
    fun `기록 없이 다시 지은 실행에서 재작업해도 새 시도는 설비 근거를 다시 묻는다`() {
        World().use { w ->
            w.failedFirst()
            val second = w.middleware()
            val rebuilt = assertIs<Middleware.Submission.Accepted>(second.resume(w.order(), ROBOT, ActiveMission(w.capability, null))).execution
            w.drive(second) { rebuilt.physicalState.isSettled && rebuilt.active == null }
            assertEquals(UnitState.FAILED, rebuilt.unit(A).state, "다시 붙은 A 가 실패를 읽어야 이 시험이 선다")
            assertEquals(PhysicalState.PARTIAL, rebuilt.physicalState)

            w.port.hand = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, second.resolve(rebuilt.executionId, A, OperatorDecision.REWORK, OPERATOR))
            val a = rebuilt.unit(A)
            w.drive(second) { a.taskId.endsWith("@r1") && a.state != UnitState.RUNNING && a.state != UnitState.VERIFYING }
            assertEquals(UnitState.UNVERIFIED, a.state, "재작업한 시도가 앞선 단위로 닫혔다: ${a.reached}")
        }
    }

    @Test
    fun `발행 직전 재검사를 못 지난 시도는 실패로 닫혀 실행이 종결하고 같은 실행의 다른 대상 재작업도 받힌다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(w.order(), ROBOT)).execution
            val a = exec.unit(A)
            val b = exec.unit(B)
            w.drive { a.hold.kind == HoldKind.HOLD_KIND_HOLDING }
            w.forceFault(a)
            w.drive { b.hold.kind == HoldKind.HOLD_KIND_HOLDING }
            w.forceFault(b)
            w.drive { exec.physicalState.isSettled && exec.active == null }
            assertEquals(PhysicalState.FAILED, exec.physicalState)

            w.port.hand = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, A, OperatorDecision.REWORK, OPERATOR))
            w.port.hand = HoldKind.HOLD_KIND_HOLDING
            w.drive { exec.physicalState.isSettled }
            assertEquals(Triple(UnitState.FAILED, MiddlewareCodes.REWORK_RECHECK, ""), Triple(a.state, a.failureClass, a.taskId))
            assertEquals(PhysicalState.FAILED, exec.physicalState, "재검사 실패가 실행을 종결시키지 않았다")
            assertTrue(!exec.physicalState.occupiesRobot, "실행이 기체를 쥔 채다")

            // 같은 실행의 다른 대상 — 막히지 않는다.
            w.port.hand = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, B, OperatorDecision.REWORK, OPERATOR))
            w.drive { b.taskId.endsWith("@r1") }
        }
    }

    @Test
    fun `앞 시도에서 나간 pick_place 는 건너뛴 묶음으로 되돌려도 물리 관문을 다시 지난다`() {
        World(travel = true, required = Evidence.E0).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(w.order(), ROBOT)).execution
            val a = exec.unit(A)
            w.drive { a.hold.kind == HoldKind.HOLD_KIND_HOLDING }
            w.forceFault(a)
            w.drive { exec.physicalState.isSettled && exec.active == null }
            assertEquals(UnitState.DONE to UnitState.FAILED, exec.unit(TRAVEL).state to a.state)

            // 시도된 실패의 재작업 — 이동(DONE)과 함께 되돌린다. 이번에는 이동이 실패해 A 가 건너뛰어진다.
            w.port.hand = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, A, OperatorDecision.REWORK, OPERATOR))
            w.port.failing += "$ORDER#$TRAVEL@r1"
            w.drive { exec.physicalState.isSettled && exec.active == null }
            assertEquals(MiddlewareCodes.PREDECESSOR_NOT_DONE, a.failureClass, "A 가 건너뛰어져야 이 시험이 선다")
            assertTrue(w.mw.judgmentRecord(exec.executionId)!!.units.single { it.unitId == A }.issuedInEarlierAttempt, "앞 시도의 태스크 표시가 기록에 없다")

            // 건너뛴 쌍의 모양이지만 A 는 앞 시도에서 쥐었을 수 있다 — 물리 관문을 다시 지나야 한다.
            assertEquals(ReworkKind.ATTEMPTED_HANDLING, w.mw.reworkCandidates().single().kind)
            w.port.hand = HoldKind.HOLD_KIND_HOLDING
            val refused = assertIs<ResolveOutcome.Refused>(w.mw.resolve(exec.executionId, A, OperatorDecision.REWORK, OPERATOR))
            assertTrue("빈손이 아니다" in refused.reason, refused.reason)
            assertEquals(1, a.attempt)

            w.port.hand = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, A, OperatorDecision.REWORK, OPERATOR))
            val decided = w.mw.judgmentRecord(exec.executionId)!!.units.associate { it.unitId to it.decision }
            assertEquals(HoldKind.HOLD_KIND_EMPTY, decided.getValue(A)!!.reworkBasis?.hold?.kind, "A 의 판단에 읽은 손이 없다")
            val travel = assertNotNull(decided.getValue(TRAVEL)?.reworkBasis, "뿌리(이동)의 판단에 근거가 없다")
            assertEquals(ReworkBasis(ROBOT, readAt = travel.readAt), travel, "뿌리는 표지만 싣는다")
        }
    }

    @Test
    fun `재작업 뒤 회복 승인이 시도를 하나 더 올려도 재기동한 그 시도는 설비 근거 없이 닫히지 않는다`() {
        // 회복 승인(§15.320)은 시도만 올리고 재작업 판단은 시도 0 에 남는다 — 판단 시도 0, 단위 시도 2. 회복 흐름 자체는 HoldRecoveryTest 가 대고, 여기서는
        // 그 결과의 모양(시도가 두 번 오른 재작업 단위)을 시험이 직접 짓는다.
        World().use { w ->
            val exec = w.failedFirst()
            w.port.hand = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, A, OperatorDecision.REWORK, OPERATOR))
            exec.unit(A).attempt = 2
            val record = JudgmentRecordWire.decode(JudgmentRecordWire.encode(assertNotNull(w.mw.judgmentRecord(exec.executionId))))
            assertEquals(0 to 2, record.units.single { it.unitId == A }.let { it.decision!!.attempt to it.attempt })

            val second = w.middleware()
            val rebuilt = assertIs<Middleware.Submission.Accepted>(second.resume(w.order(), ROBOT, ActiveMission(w.capability, null), record)).execution
            val a = rebuilt.unit(A)
            w.drive(second) { a.taskId.endsWith("@r2") && a.state != UnitState.RUNNING && a.state != UnitState.VERIFYING }
            assertEquals(UnitState.UNVERIFIED, a.state, "회복이 올린 시도가 설비 근거 없이 닫혔다: ${a.reached}")
        }
    }

    @Test
    fun `부분 완료 실행에서 재검사가 실패하면 실행은 다시 PARTIAL 로 종결하고 기체를 앞처럼 쥔다`() {
        // PARTIAL 은 기체를 쥔 채다(앞선 규칙 그대로) — 다른 실행의 재작업은 이 실행이 끝나거나 취소된 뒤다.
        World().use { w ->
            val exec = w.failedFirst()
            w.port.hand = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, A, OperatorDecision.REWORK, OPERATOR))
            w.port.hand = HoldKind.HOLD_KIND_HOLDING
            w.drive { exec.physicalState.isSettled || exec.physicalState == PhysicalState.PARTIAL && exec.unit(A).state == UnitState.FAILED }
            assertEquals(Triple(UnitState.FAILED, MiddlewareCodes.REWORK_RECHECK, PhysicalState.PARTIAL), Triple(exec.unit(A).state, exec.unit(A).failureClass, exec.physicalState))
            assertTrue(exec.physicalState.occupiesRobot, "PARTIAL 이 기체를 놓았다 — 앞선 규칙과 다르다")
            assertEquals(ReworkKind.ATTEMPTED_HANDLING, w.mw.reworkCandidates().single().kind)
        }
    }

    @Test
    fun `관측 없이 아는 물리 관문의 거부는 관문이 필요한 묶음의 단위 전부에 건다`() {
        // 사슬 이동 → A → C. 이동이 실패해 A·C 가 건너뛰어졌다. 기록이 두 단위 모두 «앞 시도에 태스크가 나갔음» 을 싣고 재기동한다 — 두 단위 다 관문이
        // 필요하다. 관측은 첫 단위(A)만 읽지만 C 의 목적지가 물리 점유로 보지 않는 자리라는 것은 읽지 않고도 안다.
        World(travel = true, required = Evidence.E0, chain = true).use { w ->
            w.port.failing += "$ORDER#$TRAVEL"
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(w.order(), ROBOT)).execution
            w.drive { exec.physicalState.isSettled && exec.active == null }
            assertEquals(listOf(MiddlewareCodes.PREDECESSOR_NOT_DONE, MiddlewareCodes.PREDECESSOR_NOT_DONE), listOf(A, C).map { exec.unit(it).failureClass })
            val saved = assertNotNull(w.mw.judgmentRecord(exec.executionId))
            val record = saved.copy(units = saved.units.map { if (it.unitId == TRAVEL) it else it.copy(issuedInEarlierAttempt = true) })

            val second = w.middleware()
            val rebuilt = assertIs<Middleware.Submission.Accepted>(second.resume(w.order(), ROBOT, ActiveMission(w.capability, null), record)).execution
            w.drive(second) { rebuilt.physicalState.isSettled }
            w.port.hand = HoldKind.HOLD_KIND_EMPTY
            val refused = assertIs<ResolveOutcome.Refused>(second.resolve(rebuilt.executionId, A, OperatorDecision.REWORK, OPERATOR))
            assertTrue("물리 점유로 보지 않는 자리" in refused.reason && C in refused.reason, refused.reason)
            assertEquals(0, rebuilt.unit(A).attempt)
        }
    }

    private companion object {
        const val ROBOT = "hum-01"
        const val WORK_MASTER = "slots-test"
        const val ORDER = "SLOTS-1"
        const val TRAVEL = "CELL-1.travel"
        const val A = "A"
        const val B = "B"
        const val SLOT_A = "RACK-1.S01"
        const val SLOT_B = "RACK-1.S02"
        const val SLOT_C = "RACK-1.S03"
        const val C = "C"
        const val BIN = "BIN-1"
        const val MATERIAL = "PART-1"
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)
    }
}

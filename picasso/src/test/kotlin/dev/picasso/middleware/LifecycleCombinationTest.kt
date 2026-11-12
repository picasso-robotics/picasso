package dev.picasso.middleware

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **명령 생애 조합의 특성화 시험**(재설계 R0, `docs/superpowers/plans/2026-11-12-middleware-r0-state-map.md` §3).
 *
 * 취소 × 미확정 × 링크 끊김 × 재기동 × 개정 가운데 다른 시험이 덮지 않는 조합에서 **지금 동작을 그대로** 적는다. 옳은 동작을 적는
 * 시험이 아니다. 재설계 PR(R1~R5)은 이 시험을 바꾸지 않고 초록으로 지났다. R0 이 틀려 보인다고 표시한 다섯((a)~(e))은 §15.306 의
 * fix 가 고쳤고, 그 PR 이 해당 단언을 고친 동작으로 바꿨다(주석 `§15.306 (x)`). 동작을 바꾸는 PR 은 여기의 단언을 같은 PR 에서 바꾼다.
 *
 * 기체는 4족 픽스처 `quadruped-b`, 업무는 점검 순회(이동 뒤 점검, 두 지점)다. 나간 명령과 그 답은 계약 클라이언트 바로 위의 [Wire] 가 센다.
 * 끊긴 동안([Wire.down]) 출발·취소는 기체에 닿지 않고 던지며, 스냅숏은 못 읽고, 갱신 스트림은 받은 것에서 멈춘다.
 */
class LifecycleCombinationTest {

    private class Wire(private val delegate: RobotPort) : RobotPort by delegate {
        /** 나간 `StartTask` 의 (태스크 id, 리비전). 끊긴 동안 던진 것도 센다. */
        val starts = mutableListOf<Pair<String, Int>>()

        /** 나간 `CancelTask` 의 (태스크 id, 리비전). 끊긴 동안 던진 것도 센다. */
        val cancels = mutableListOf<Pair<String, Int>>()

        /** 기체에 닿은 `CancelTask` 의 답 — 거절 코드 또는 태스크 상태. */
        val cancelAnswers = mutableListOf<String>()

        var down = false

        /** 참이면 다음 `StartTask` 를 기체에 닿게 한 뒤 답을 잃는다. */
        var loseNextStart = false

        /** 참이면 링크가 살아 있어도 `CancelTask` 가 기체에 닿지 않고 던진다. */
        var failCancels = false

        private val seen = mutableMapOf<String, List<WatchTaskResponse>>()

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            starts += taskId to revision
            if (down) throw IllegalStateException("link down: start did not reach the robot")
            val response = delegate.start(robotId, taskId, revision, skillType, parameters)
            if (loseNextStart) {
                loseNextStart = false
                throw IllegalStateException("response lost after the request reached the robot")
            }
            return response
        }

        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse {
            cancels += handle.taskId to handle.revision
            if (down) throw IllegalStateException("link down: cancel did not reach the robot")
            if (failCancels) throw IllegalStateException("cancel unanswered")
            return delegate.cancel(robotId, handle).also {
                cancelAnswers += if (it.hasRejection()) it.rejection.code.name else it.state.name
            }
        }

        override fun snapshot(robotId: String): RobotSnapshot? = if (down) null else delegate.snapshot(robotId)

        override fun replay(robotId: String, from: Long): Replay? = if (down) null else delegate.replay(robotId, from)

        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> =
            if (down) seen[handle.taskId].orEmpty() else delegate.watch(robotId, handle).also { seen[handle.taskId] = it }
    }

    private class World : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()))
        val wire = Wire(ClientRobotPort(harness.client()))
        val mw = Middleware(wire, now = { harness.clock.now() })

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList
        fun state(taskId: String) = tasks().firstOrNull { it.taskId == taskId }?.taskState

        /** 다시 뜬 인스턴스 — 새 포트, 같은 기체. */
        fun restart(): Pair<Middleware, Wire> {
            val wire = Wire(ClientRobotPort(harness.client()))
            return Middleware(wire, now = { harness.clock.now() }) to wire
        }

        fun drive(mw: Middleware = this.mw, rounds: Int = 60, until: () -> Boolean) {
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

        /** 시계를 밀지 않고 기체의 태스크가 [expected] 가 될 때까지 기다린다. */
        fun await(taskId: String, expected: String) {
            repeat(100) {
                if (state(taskId) == expected) return
                Thread.sleep(20)
            }
            error("기체의 $taskId 가 $expected 가 아니다: ${state(taskId)}")
        }

        /** 점검 단위가 기체에서 돌 때까지 몬다. */
        fun runUntilInspecting(): Middleware.Execution {
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order(1), ROBOT)).execution
            drive { state(TASK) == "RUNNING" }
            return exec
        }

        /** 점검 단위가 돌 때의 판단 기록으로 새 인스턴스에 실행을 다시 짓는다 — 첫 pump 전이라 아직 붙지 않았다. */
        fun rebuildWhileInspecting(): Triple<Middleware, Wire, Middleware.Execution> {
            val first = runUntilInspecting()
            val record = assertNotNull(mw.judgmentRecord(first.executionId))
            val (again, wire) = restart()
            val rebuilt = assertIs<Middleware.Submission.Accepted>(again.resume(order(1), ROBOT, ActiveMission(InspectAsset(), null), record)).execution
            return Triple(again, wire, rebuilt)
        }

        /** 끊고 끊김 판정(`LINK_BROKEN`)까지 간다. 그동안 기체는 점검을 마친다. */
        fun breakLink(mw: Middleware, wire: Wire, exec: Middleware.Execution) {
            wire.down = true
            harness.advance(Duration.ofSeconds(35))
            await(TASK, "SUCCEEDED")
            mw.pump()
            assertTrue(exec.linkBroken, "시험의 전제: 끊김으로 판정했다")
            assertEquals(PhysicalState.IN_DOUBT, exec.physicalState)
            assertEquals(UnitState.RUNNING, exec.units.single { it.unitId == INSPECT }.state, "끊김은 실행만 미확정에 두고 단위는 RUNNING 그대로다")
        }

        override fun close() = harness.close()
    }

    private fun Middleware.Execution.unit() = units.single { it.unitId == INSPECT }

    private fun Middleware.revise() = assertIs<Middleware.Submission.Accepted>(submit(order(2), ROBOT))

    // ── 취소 × 미확정 × 링크 끊김 × 개정

    @Test
    fun `끊긴 채 개정 갱신을 잃은 단위에 건 취소는 링크가 돌아오면 끝난 태스크에 키로 붙어 그 종료로 판정하고 중단으로 끝난다`() {
        World().use { w ->
            val exec = w.runUntilInspecting()
            w.breakLink(w.mw, w.wire, exec)

            w.mw.revise()
            assertEquals(TASK to 2, w.wire.starts.last(), "개정 갱신이 끊긴 기체로 나갔다")
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertEquals(2, exec.unit().revision)
            assertTrue(exec.unit().note!!.contains("revision 2 update unanswered"), exec.unit().note)

            assertTrue(w.mw.cancel(exec.executionId))
            w.mw.pump()
            assertEquals(PhysicalState.IN_DOUBT, exec.physicalState, "미확정 실행의 취소는 CANCELING 으로 바꾸지 않는다")
            assertTrue(exec.eventTrail.any { it.kind == Middleware.CANCEL_UNSENT }, exec.eventTrail.map { it.kind }.toString())

            w.wire.down = false
            w.drive { !exec.physicalState.pumps }

            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals("SUCCEEDED", w.state(TASK), "기체는 끊긴 동안 점검을 마쳤다")
            // §15.306 (b): 이미 끝난 태스크라는 거절(INVALID_TRANSITION)은 기체가 그 태스크를 안다는 답이다 — 키 핸들로 붙어 그 종료를 읽는다.
            // 옛 리비전의 완료라 지연 이벤트이고, 새 판의 기대를 설비가 확인하지 못해 UNVERIFIED 다.
            assertEquals(listOf("REJECTION_CODE_INVALID_TRANSITION"), w.wire.cancelAnswers, "붙은 뒤에 취소를 또 보냈다")
            assertEquals(UnitState.UNVERIFIED, exec.unit().state)
            assertTrue(exec.unit().note!!.contains("late event: revision 1 TASK_STATE_SUCCEEDED"), exec.unit().note)
            val report = assertNotNull(w.mw.lastCancel(exec.executionId))
            assertEquals("REJECTION_CODE_INVALID_TRANSITION", report.refusal, "닿아서 거절된 취소를 «닿지 못했다» 로 적었다")
            assertEquals(null to INSPECT, report.inProgressUnit to report.stoppedAfter, "끝까지 간 단위를 중단된 단위로 적었다")
            assertEquals(emptyList(), w.mw.incidents())
            // §15.306 (c): 링크가 돌아오면 미확정 단위의 끊김 표시도 걷는다.
            assertFalse(exec.linkBroken, "링크가 돌아왔는데 끊김 표시가 남았다")
            assertEquals(listOf(1, 2), w.wire.starts.filter { it.first == TASK }.map { it.second }, "링크가 돌아온 뒤 조회(재전송)가 나갔다")
            // 건 순간은 붙어 있던 핸들(리비전 1)로 나가 끊긴 선에서 던지고, 그 뒤는 키로 지은 핸들(리비전 2)이다.
            assertEquals(TASK to 1, w.wire.cancels.first())
            assertTrue(w.wire.cancels.drop(1).all { it == TASK to 2 }, "키 취소의 리비전: ${w.wire.cancels}")
        }
    }

    @Test
    fun `끊긴 채 개정 갱신을 잃은 단위에 건 취소가 링크가 돌아와도 닿지 않으면 단위와 실행을 운영자 보류에 둔다`() {
        World().use { w ->
            val exec = w.runUntilInspecting()
            w.breakLink(w.mw, w.wire, exec)
            w.mw.revise()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertNotNull(exec.handle, "시험의 전제: 갱신 전의 핸들이 남아 있다")

            assertTrue(w.mw.cancel(exec.executionId))
            w.wire.failCancels = true
            w.wire.down = false
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD || !exec.physicalState.pumps }

            // §15.306 (a): 핸들이 있어도 그 핸들로 보낸 취소가 닿지 않은 채면, 미확정 유예가 끝나 단위를 보류에 둘 때 실행도 운영자 판단을
            // 기다린다. 앞에서는 실행이 중단으로 닫혀 기체가 멈췄는지 모르는 채 내줬다.
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState, "닿지 않은 취소를 든 채 실행을 닫았다")
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state)
            assertEquals(null, w.mw.lastCancel(exec.executionId), "닿지 않은 취소에 취소 응답이 나갔다")
            assertEquals(listOf(INSPECT), w.mw.incidents().map { it.unitId })
            assertTrue(exec.unit().note!!.contains("the cancel sent was not answered"), exec.unit().note)
            // §15.306 (c): 단위가 미확정인 채 링크가 돌아와도 끊김 표시를 걷는다.
            assertFalse(exec.linkBroken, "링크가 돌아왔는데 끊김 표시가 남았다")
            assertTrue(exec.eventTrail.any { it.kind == Middleware.CANCEL_UNSENT }, exec.eventTrail.map { it.kind }.toString())

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, INSPECT, OperatorDecision.CONFIRM_DONE, OPERATOR))
            w.drive { !exec.physicalState.pumps }
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(Middleware.CANCEL_NOT_SENT, assertNotNull(w.mw.lastCancel(exec.executionId)).refusal)
        }
    }

    // ── 취소 × 재기동 × 개정 (붙기 전)

    @Test
    fun `다시 지은 실행에 붙기 전 개정이 오고 취소가 걸리면 기체가 든 리비전의 핸들로 취소하고 중단으로 끝난다`() {
        World().use { w ->
            val (again, wire, exec) = w.rebuildWhileInspecting()

            again.revise()
            assertEquals(UnitState.PENDING, exec.unit().state, "시험의 전제: 아직 붙지 않았다")
            assertEquals(2, exec.unit().revision, "붙기 전 단위는 PENDING 이라 새 계획으로 바뀐다")
            assertEquals(PhysicalState.ACCEPTED, exec.physicalState)

            assertTrue(again.cancel(exec.executionId))
            again.pump()
            assertEquals(PhysicalState.CANCELING, exec.physicalState)
            assertEquals(UnitState.RUNNING, exec.unit().state)
            assertTrue(exec.eventTrail.any { it.kind == "CANCEL_ATTACHED" }, exec.eventTrail.map { it.kind }.toString())
            assertEquals(emptyList(), wire.starts, "취소하려고 붙으면서 StartTask 를 보냈다")

            w.drive(again) { !exec.physicalState.pumps }
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(UnitState.ABORTED, exec.unit().state)
            assertEquals("CANCELLED", w.state(TASK))
            // §15.306 (d): 단위는 개정으로 리비전 2 지만 기체에서 도는 것은 1 이다 — 취소 핸들은 기체의 스냅숏이 보인 리비전으로 짓는다.
            assertEquals(2, exec.unit().revision)
            assertEquals(listOf(TASK to 1), wire.cancels)
            assertEquals(emptyList(), again.incidents())
            assertEquals(INSPECT, assertNotNull(again.lastCancel(exec.executionId)).inProgressUnit)
        }
    }

    @Test
    fun `다시 지은 실행에 붙기 전 취소를 건 뒤 개정이 오면 붙기 전 단위를 그대로 두고 기체의 리비전으로 취소해 중단으로 끝난다`() {
        World().use { w ->
            val (again, wire, exec) = w.rebuildWhileInspecting()

            assertTrue(again.cancel(exec.executionId))
            again.revise()
            assertEquals(PhysicalState.CANCELING, exec.physicalState)
            assertEquals(UnitState.PENDING, exec.unit().state)
            // §15.306 (e): 취소가 먼저 걸렸으면 붙기 전 단위도 도는 단위처럼 개정이 바꾸지 않는다(§15.256).
            assertEquals(1, exec.unit().revision)
            assertEquals(2, exec.version, "시험의 전제: 개정은 받았다")
            assertEquals(emptyList(), wire.cancels, "붙기 전이라 취소가 아직 안 나갔다")

            again.pump()
            assertEquals(UnitState.RUNNING, exec.unit().state)
            assertEquals(listOf(TASK to 1), wire.cancels)

            w.drive(again) { !exec.physicalState.pumps }
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(UnitState.ABORTED, exec.unit().state)
            assertEquals("CANCELLED", w.state(TASK))
            assertEquals(emptyList(), wire.starts)
            assertEquals(emptyList(), again.incidents())
        }
    }

    // ── 취소 × 미확정 × 재기동 × 개정

    @Test
    fun `다시 지은 실행에서 개정 갱신의 답을 잃은 채 건 취소는 옛 핸들로 거절된 뒤 스냅숏에 붙어 새 리비전으로 취소해 중단으로 끝난다`() {
        World().use { w ->
            val (again, wire, exec) = w.rebuildWhileInspecting()
            again.pump()
            assertEquals(UnitState.RUNNING, exec.unit().state, "시험의 전제: 스냅숏의 태스크에 다시 붙었다")
            assertEquals(emptyList(), wire.starts)

            wire.loseNextStart = true
            again.revise()
            assertEquals(listOf(TASK to 2), wire.starts, "개정 갱신이 안 나갔다")
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertEquals(PhysicalState.IN_DOUBT, exec.physicalState)

            assertTrue(again.cancel(exec.executionId))
            assertEquals(PhysicalState.CANCELING, exec.physicalState)
            // 취소가 붙어 있던 핸들(리비전 1)로 바로 나가 기체가 낡은 리비전이라고 거절한다.
            assertEquals(listOf(TASK to 1), wire.cancels)
            assertEquals(listOf("REJECTION_CODE_OUTDATED_REVISION"), wire.cancelAnswers)

            again.pump()
            assertEquals(UnitState.RUNNING, exec.unit().state)
            assertTrue(exec.unit().note!!.contains("IN_DOUBT resolved by snapshot attach"), exec.unit().note)
            assertEquals(listOf(TASK to 1, TASK to 2), wire.cancels, "붙은 뒤 새 리비전으로 다시 보내지 않았다")
            assertEquals("TASK_STATE_CANCELLING", wire.cancelAnswers.last())

            w.drive(again) { !exec.physicalState.pumps }
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(UnitState.ABORTED, exec.unit().state)
            assertEquals("CANCELLED", w.state(TASK))
            assertEquals(listOf(TASK to 2), wire.starts, "미확정 해소가 StartTask 를 다시 냈다")
            assertEquals(emptyList(), again.incidents())
        }
    }

    // ── 다섯 축 전부

    @Test
    fun `다시 지은 실행이 끊긴 채 개정 갱신을 잃고 취소가 걸리면 링크가 돌아온 뒤 끝난 태스크에 키로 붙어 중단으로 끝난다`() {
        World().use { w ->
            val (again, wire, exec) = w.rebuildWhileInspecting()
            again.pump()
            assertEquals(UnitState.RUNNING, exec.unit().state, "시험의 전제: 스냅숏의 태스크에 다시 붙었다")
            w.breakLink(again, wire, exec)

            again.revise()
            again.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertEquals(2, exec.unit().revision)
            assertEquals(listOf(TASK to 2, TASK to 2), wire.starts, "개정 갱신과 첫 조회가 끊긴 기체로 나갔다")

            assertTrue(again.cancel(exec.executionId))
            again.pump()
            assertEquals(PhysicalState.IN_DOUBT, exec.physicalState)

            wire.down = false
            w.drive(again) { !exec.physicalState.pumps }

            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            // §15.306 (b)(c): 앞의 끊김 시험과 같다.
            assertEquals(UnitState.UNVERIFIED, exec.unit().state)
            assertEquals(emptyList(), again.incidents())
            assertEquals(listOf("REJECTION_CODE_INVALID_TRANSITION"), wire.cancelAnswers)
            assertEquals("REJECTION_CODE_INVALID_TRANSITION", assertNotNull(again.lastCancel(exec.executionId)).refusal)
            assertFalse(exec.linkBroken)
            assertEquals("SUCCEEDED", w.state(TASK))
            assertEquals(listOf(TASK to 2, TASK to 2), wire.starts, "링크가 돌아온 뒤 조회(재전송)가 나갔다")
        }
    }

    private companion object {
        const val ROBOT = "quad-01"
        const val ORDER = "PATROL-9"
        const val INSPECT = "PUMP-01"
        const val TASK = "$ORDER#$INSPECT"

        /** 판단하는 사람 — 운영자 판단은 사람만 낸다(ADR 47). */
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)

        fun order(version: Int) = JobOrder(
            jobOrderId = ORDER,
            workMasterId = InspectAsset.WORK_MASTER,
            version = version,
            requiredEvidence = Evidence.E0,
            parameters = mapOf("shift" to "night"),
            equipmentRequirements = listOf("PUMP-ROOM" to "PUMP-01", "SWITCHGEAR" to "PANEL-3").map { (location, target) ->
                EquipmentRequirement(target, EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to location, EquipmentUse.PROP_ITEM to "thermal"))
            },
        )
    }
}

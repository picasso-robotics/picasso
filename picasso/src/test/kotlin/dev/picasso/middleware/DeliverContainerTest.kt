package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.harness.Harness
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 시나리오 ① — 용기 공급을 **미들웨어 층**에서 돌린다(보고서 5장, `docs/scenarios.md` §3).
 *
 * WMS(예상 소비자 = 이 시험)가 *"창고 작업 WT-781 에 할당된 용기 HU-1042 를 OUT-07 에서
 * SEQ-IN-02 로 공급하고 인계 결과를 보고하라"* 고 한다. 미들웨어는 운반 전체를
 * **AMR 플릿에 D 수준으로 위임**하고(`AmrFleetMimic`, 프로젝트용 계약), 플릿의 완료(E1)를
 * 인계 설비(`CellMimic`)의 신호와 결합해 E2 로 물리 완료를 판정하고, JobResponse 를
 * 아웃박스에 낸다. 상황표 셋(목적지 점유 · 출발지 용기 불일치 · WMS 응답 유실)과
 * 17장 1·2 를 단언한다.
 *
 * 로봇은 이 시나리오에 없다 — 계약(④)이 닿지 않는다는 것이 `scenarios.md` §1 의 경계다.
 * 하네스는 미들웨어의 로봇 포트를 채우기 위해서만 뜬다.
 */
class DeliverContainerTest {

    private fun order(required: Evidence = Evidence.E2) = JobOrder(
        jobOrderId = "WT-781",
        workMasterId = DeliverContainer.WORK_MASTER,
        version = 1,
        requiredEvidence = required,
        // **요청 식별자와 납기를 안 싣는다.** 시나리오 ① 의 표에는 있지만 이 층이 쓸 자리가 없다 —
        // 재전송 구분은 `(jobOrderId, version)` 이 이미 하고(두 키로 같은 질문에 답하면 어긋나는 날이 온다),
        // 납기는 배차의 입력인데 배차는 밖이다(§1.3). 안 쓰는 값을 나르지 않는 것이 ADR 9 다(§15.117).
        equipmentRequirements = listOf(
            EquipmentRequirement(SOURCE, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_CONTAINER to CONTAINER)),
            EquipmentRequirement(DEST, EquipmentUse.DESTINATION),
        ),
    )

    private class World : AutoCloseable {
        val harness = Harness(mapOf("idle-robot" to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val fleet = AmrFleetMimic(now = { harness.clock.now() })
        /** 인계 설비는 플릿이 실제로 내려놓은 것을 **놓인 시각과 함께** 본다 — 침묵시키기 전까지. */
        val cell = CellMimic(now = { harness.clock.now() }, live = { loc -> fleet.containersAt[loc]?.let { it to fleet.placedAt[loc] } })
        val mw = Middleware(ClientRobotPort(harness.client()), cell, fleet, now = { harness.clock.now() })

        init {
            fleet.place(SOURCE, CONTAINER)
        }

        /** 플릿 틱 하나를 세계 시간 10초로 친다 — 시간창(뒤쪽 15초)은 시계가 가야 닫힌다. */
        fun drive(rounds: Int = 12, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(10))
                fleet.tick()
            }
            error("조건에 못 미쳤다: 세계=${fleet.containersAt}")
        }

        override fun close() = harness.close()
    }

    private fun Middleware.Execution.settled() = physicalState.isSettled && active == null

    // ── 정상 (17장 1)

    @Test
    fun `운반 전체를 플릿에 맡기고, 인계 설비가 그 용기를 보면 E2 로 물리 완료다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.settled() }

            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            val unit = exec.units.single()
            assertEquals(CONTAINER, unit.unitId)
            assertEquals(Route.FLEET, unit.route)
            assertEquals(Evidence.E2, unit.reached)
            assertEquals(Verification.MATCHED, unit.verification)
            assertEquals(HoldKind.HOLD_KIND_EMPTY, unit.hold.kind, "AMR 이 용기를 계속 보유하고 있다")

            // 플릿 계약의 완료 = 도착 ∧ 하역 ∧ 인수 ∧ 미보유 — 세계가 그렇게 바뀌었다.
            assertEquals(CONTAINER, w.fleet.containersAt[DEST])
            assertFalse(SOURCE in w.fleet.containersAt)
            assertEquals(1, w.fleet.dispatches)

            val response = w.mw.pending().single()
            assertEquals(Evidence.E2, response.reachedEvidence)
            assertEquals(listOf(CONTAINER), response.completedUnits)
            assertFalse(response.operatorRequired)
        }
    }

    @Test
    fun `요구 등급이 E1 이면 플릿의 완료로 충분하다 — 설비를 묻지 않는다`() {
        World().use { w ->
            w.cell.silence(DEST)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(required = Evidence.E1), ROBOT)).execution
            w.drive { exec.settled() }

            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(Evidence.E1, exec.units.single().reached)
            assertEquals(Verification.NOT_REQUESTED, exec.units.single().verification)
        }
    }

    @Test
    fun `플릿은 인계했다는데 설비가 말이 없으면 UNVERIFIED 다 — E1 까지만 도달했다`() {
        World().use { w ->
            w.cell.silence(DEST)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.settled() }

            assertEquals(PhysicalState.UNVERIFIED, exec.physicalState)
            assertEquals(Evidence.E1, exec.units.single().reached)
            assertEquals(Verification.ABSENT, exec.units.single().verification)
            assertEquals(1, w.fleet.dispatches, "UNVERIFIED 가 재실행을 만들었다")
            assertTrue(w.mw.pending().single().operatorRequired)
        }
    }

    // ── 상황표 (보고서 5장)

    @Test
    fun `목적지에 이전 용기가 남아 있으면 인계를 기다리고 지연을 보고한다 — 다른 자리에 내려놓지 않는다`() {
        World().use { w ->
            w.fleet.block(DEST)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution

            w.drive { exec.units.single().note == Middleware.WAITING_HANDOVER }
            assertEquals(PhysicalState.RUNNING, exec.physicalState, "대기는 실패가 아니다")
            assertEquals(HoldKind.HOLD_KIND_HOLDING, exec.units.single().hold.kind, "기다리는 동안 AMR 이 싣고 있다")

            // 지연 보고 — 아웃박스에 한 번.
            val delay = w.mw.pending().single()
            assertEquals(PhysicalState.RUNNING, delay.physicalState)
            assertEquals(mapOf(CONTAINER to Middleware.WAITING_HANDOVER), delay.incompleteUnits)

            // 기다리는 동안 몇 번을 돌아도 임의의 자리에 내려놓지 않는다.
            repeat(3) { w.fleet.tick(); w.mw.pump() }
            assertFalse(w.fleet.containersAt.containsValue(CONTAINER), "기다리다 아무 데나 내려놓았다")
            assertEquals(1, w.mw.pending().size, "지연 보고가 매번 나간다")

            // 상류가 대기 유지를 택했고 목적지가 비었다.
            w.fleet.unblock(DEST)
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(CONTAINER, w.fleet.containersAt[DEST])
            assertEquals(1, w.fleet.dispatches)
        }
    }

    @Test
    fun `출발 위치의 용기가 요청과 다르면 인수하지 않고 불일치를 보고한다`() {
        World().use { w ->
            w.fleet.place(SOURCE, "HU-9999")
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.settled() }

            assertEquals(PhysicalState.FAILED, exec.physicalState)
            val unit = exec.units.single()
            assertEquals(UnitState.FAILED, unit.state)
            assertEquals(Middleware.SOURCE_MISMATCH, unit.failureClass)
            assertEquals("HU-9999", w.fleet.containersAt[SOURCE], "인수하지 않았어야 한다 — 출발지가 그대로다")
            assertEquals(HoldKind.HOLD_KIND_EMPTY, unit.hold.kind)

            val response = w.mw.pending().single()
            assertEquals(mapOf(CONTAINER to Middleware.SOURCE_MISMATCH), response.incompleteUnits)
            assertTrue(response.operatorRequired, "WMS 가 할당·현장 재고를 확인해야 한다")
        }
    }

    @Test
    fun `물리적 인계 뒤 WMS 응답이 유실돼도 운반은 다시 실행되지 않는다 — 같은 통보를 다시 건넨다`() {
        // 17장 2 — 명령 재시도와 통보 재시도의 분리(보고서 13.3).
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.settled() }
            val first = w.mw.pending().single()

            // WMS 가 ack 를 안 했다. 상류가 같은 요청을 다시 낸다 — 멱등이고 새 운반은 없다.
            assertIs<Middleware.Submission.Idempotent>(w.mw.submit(order(), ROBOT))
            repeat(3) { w.mw.pump(); w.fleet.tick() }
            assertEquals(1, w.fleet.dispatches, "통보가 유실됐다고 운반을 다시 했다")
            assertEquals(UpstreamAck.SENT_UNACKED, exec.upstreamAck)

            // 같은 통보가 아웃박스에 그대로 있다 — 그것을 다시 건네는 것이 통보 재시도다.
            assertEquals(first.jobResponseId, w.mw.pending().single().jobResponseId)

            assertTrue(w.mw.ack(first.jobResponseId))
            assertEquals(emptyList(), w.mw.pending())
            assertEquals(UpstreamAck.ACKED, exec.upstreamAck)
        }
    }

    @Test
    fun `같은 참조로 두 번 맡겨도 운반은 하나다 — 플릿 계약의 멱등`() {
        World().use { w ->
            val a = assertNotNull(w.fleet.dispatch(TransportOrder("WT-781#HU-1042", CONTAINER, SOURCE, DEST)))
            val b = assertNotNull(w.fleet.dispatch(TransportOrder("WT-781#HU-1042", CONTAINER, SOURCE, DEST)))
            assertEquals(a, b)
            assertEquals(1, w.fleet.dispatches)
        }
    }

    @Test
    fun `싣고 가는 중 취소하면 플릿이 출발지로 되돌리고 응답에 그 사실이 남는다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.units.single().hold.kind == HoldKind.HOLD_KIND_HOLDING }

            assertTrue(w.mw.cancel(exec.executionId))
            w.drive { exec.physicalState == PhysicalState.ABORTED }

            val report = assertNotNull(w.mw.lastCancel(exec.executionId))
            assertEquals(CONTAINER, report.inProgressUnit)
            assertEquals("done", report.cleanup)
            assertEquals(HoldKind.HOLD_KIND_EMPTY, report.residualHold.kind)
            assertEquals(CONTAINER, w.fleet.containersAt[SOURCE], "정리 동작이 출발지로 되돌려 놓아야 한다")
            assertFalse(DEST in w.fleet.containersAt)
        }
    }

    @Test
    fun `플릿이 스스로 취소하면 실행을 중단으로 끝내지 않고 운영자 보류로 세운다`() {
        // 이 층은 취소를 걸지 않았다. 현장에서 플릿 쪽으로 멈춘 운반을 중단으로 접으면 아무도 판단하지 않은 채 실행이 끝난다.
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.units.single().state == UnitState.RUNNING }

            assertTrue(w.fleet.cancel(assertNotNull(exec.transport)))
            w.drive { exec.units.single().state != UnitState.RUNNING }

            assertEquals(UnitState.OPERATOR_HOLD, exec.units.single().state)
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState)
            assertTrue(w.mw.pending().last().operatorRequired)
        }
    }

    private companion object {
        const val ROBOT = "idle-robot"
        const val CONTAINER = "HU-1042"
        const val SOURCE = "OUT-07"
        const val DEST = "SEQ-IN-02"
    }
}

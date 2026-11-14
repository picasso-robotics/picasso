package dev.picasso.middleware

import dev.picasso.capability.Remedy
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.harness.Harness
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 제안과 승인(`INCIDENT_AND_REMEDY_PROPOSAL.md` §6.4) — 대안은 **제안**이다.
 *
 * **승인 없이 실행되는 경로는 만들지 않는다.** 그것이 이 묶음이 지켜야 하는 담보이고, 나머지는 그 담보가
 * 실제로 서 있는지를 여러 각도에서 묻는 것이다. 승인은 사람의 책임 있는 행위이므로 조치가 요구하는 값도
 * 사람이 준다 — 지어내면 승인은 무엇을 승인하는지 모르는 채 누르는 단추가 된다.
 *
 * **제안은 계획 시점 거절에 붙는다.** 관측과 선언이 둘 다 있는 자리가 거기뿐이기 때문이다. 여기서 관측은 기체 스냅숏의
 * 손이다(§15.316) — 든 채 도는 다른 실행의 관측이면 그 손에 든 것이 그 실행의 일이라 제안이 서지 않는다(§15.319).
 */
class RemedyApprovalTest {

    private class World(profile: Path) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to profile))
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

        override fun close() = harness.close()
    }

    private fun rack() = JobOrder(
        jobOrderId = "SEQ-204",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = 17,
        requiredEvidence = Evidence.E2,
        materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", 1)),
        equipmentRequirements = listOf(
            EquipmentRequirement("RACK-204.S01", "destination", mapOf("material" to "ENGINE-COVER-A")),
            EquipmentRequirement("SEQ-IN-02.BIN-A", "source", mapOf("material" to "ENGINE-COVER-A")),
        ),
    )

    private fun patrol() = JobOrder(
        jobOrderId = "PATROL-1",
        workMasterId = InspectAsset.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E0,
        equipmentRequirements = listOf(
            EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1")),
        ),
    )

    /**
     * 든 채로 남은 기체를 만들고, 빈손을 요구하는 주문을 넣어 거절을 받는다.
     *
     * 앞 주문은 든 채 실패해 사람이 취소로 닫는다([leaveHolding]) — 기체를 쥔 실행 없이 손만 든 채다. 든 채 **도는** 앞 주문 뒤에
     * 내면 그 손에 든 것이 앞 주문의 일이라 제안이 서지 않는다(§15.319).
     */
    private fun World.rejectedWhileHolding(): Middleware.Submission.Rejected {
        val order = rack()
        order.equipmentRequirements.filter { it.equipmentUse == "destination" }
            .forEach { cell.program(it.id, it.properties["material"]) }
        val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order, ROBOT)).execution
        mw.leaveHolding(exec, harness) { drive(until = it) }
        return assertIs<Middleware.Submission.Rejected>(mw.submit(patrol(), ROBOT))
    }

    @Test
    fun `거절에 조치 열이 함께 온다`() {
        World(PRECOND).use { w ->
            val rejected = w.rejectedWhileHolding()
            val found = assertIs<Remedy.Found>(assertNotNull(rejected.remedy, "거절만 하고 대안을 안 냈다"))
            assertEquals(listOf("pick_place"), found.steps.map { it.skillType })
            assertEquals(HoldKind.HOLD_KIND_EMPTY, found.steps.single().expectedHold)
            assertNotNull(w.mw.proposal(ROBOT, "PATROL-1"), "제안이 서 있지 않다 — 승인할 대상이 없다")
        }
    }

    @Test
    fun `승인하기 전에는 어떤 조치도 실행되지 않는다`() {
        // 이 묶음의 담보. 거절을 받아 둔 채로 계속 돌려도 조치가 나가면 안 된다.
        World(PRECOND).use { w ->
            w.rejectedWhileHolding()
            repeat(20) {
                w.mw.pump()
                w.harness.advance(Duration.ofSeconds(1))
            }
            assertTrue(
                w.mw.executions().none { it.order.jobOrderId == "PATROL-1" },
                "승인 없이 주문이 섰다",
            )
            assertNotNull(w.mw.proposal(ROBOT, "PATROL-1"), "제안이 저절로 사라졌다")
        }
    }

    @Test
    fun `서 있는 제안이 없으면 승인이 아무것도 하지 않는다`() {
        // 없는 제안을 승인으로 지어내면 «승인 없이 실행되는 경로» 가 그 자리에서 생긴다.
        World(PRECOND).use { w ->
            val answer = assertIs<Middleware.Submission.Rejected>(
                w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(emptyMap()), OPERATOR),
            )
            assertTrue(answer.reason.contains("승인할 제안이 없다"), answer.reason)
            assertTrue(w.mw.executions().none { it.order.jobOrderId == "PATROL-1" })
        }
    }

    @Test
    fun `조치가 요구하는 파라미터가 없으면 승인을 받지 않는다`() {
        // 탐색기는 어느 스킬을 딛을지까지만 계산한다. 어디에 놓을지는 사람이 말한다.
        World(PRECOND).use { w ->
            w.rejectedWhileHolding()
            val answer = assertIs<Middleware.Submission.Rejected>(
                w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(emptyMap()), OPERATOR),
            )
            assertTrue(answer.reason.contains("파라미터가 없다"), answer.reason)
            assertNotNull(w.mw.proposal(ROBOT, "PATROL-1"), "거절해 놓고 제안을 지웠다")
        }
    }

    @Test
    fun `걸음 수와 파라미터 수가 다르면 승인을 받지 않는다`() {
        World(PRECOND).use { w ->
            w.rejectedWhileHolding()
            val answer = assertIs<Middleware.Submission.Rejected>(
                w.mw.approveRemedy(ROBOT, "PATROL-1", emptyList(), OPERATOR),
            )
            assertTrue(answer.reason.contains("단계 수와 파라미터 수가 다르다"), answer.reason)
        }
    }

    @Test
    fun `승인하면 조치가 앞에 서고 막혔던 주문이 실제로 돈다`() {
        // 설계안 §10.2 셋째 — 실행 가능하지 않은 대안은 대안이 아니다. 제안을 에뮬레이터에서 돌려
        // 막혔던 단위가 실제로 나가는지 본다.
        World(PRECOND).use { w ->
            val found = assertIs<Remedy.Found>(assertNotNull(w.rejectedWhileHolding().remedy))
            val place = mapOf(
                PrepareSequencedRack.P_OBJECT to "ENGINE-COVER-A",
                PrepareSequencedRack.P_DESTINATION to "RACK-204.S01",
            )
            val accepted = assertIs<Middleware.Submission.Accepted>(
                w.mw.approveRemedy(ROBOT, "PATROL-1", List(found.steps.size) { place }, OPERATOR),
            )

            assertEquals("remedy-1-pick_place", accepted.execution.units.first().unitId, "조치가 앞에 서지 않았다")
            assertNull(w.mw.proposal(ROBOT, "PATROL-1"), "승인한 제안이 그대로 남아 두 번 쓸 수 있다")
            w.drive(rounds = 400) { accepted.execution.units.first().state != UnitState.PENDING }
            assertTrue(
                accepted.execution.units.first().state != UnitState.PENDING,
                "조치가 돌지 않았다: ${accepted.execution.units.map { it.unitId to it.state }}",
            )
        }
    }

    companion object {
        /** 사람이 누른다 — 선언 목록을 안 본다(ADR 43). */
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)

        const val ROBOT = "hum-02"
        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()
    }
}

package dev.picasso.middleware

import dev.picasso.capability.Remedy
import dev.picasso.harness.Harness
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
 * 의도적 비자동화와 반복 승인 지표(`INCIDENT_AND_REMEDY_PROPOSAL.md` §7.2 넷째 · §7.3).
 *
 * **에이전트가 사건을 처리할수록 사람은 진단하는 연습을 잃는다.** 그러면 모델 밖 사건이 왔을 때 아무도
 * 들어가지 못한다. 그래서 일부 사건은 제안을 가린 채 사람이 먼저 진단하게 둔다 — 비용임을 알고
 * 지불하는 결정이어야 유지되므로, 몇 번에 한 번 가릴지는 배치가 명시한다.
 *
 * 그리고 **임시 대안이 매끄럽게 작동할수록 근본 원인을 고칠 압력이 사라진다.** 반복되는 임시 조치는
 * 미해결 근본 원인의 지표이고, 시스템이 그것을 스스로 고발해야 한다.
 */
class WithholdingTest {

    private class World(profile: Path, withholdEvery: Int = 0) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to profile))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(
            ClientRobotPort(harness.client()),
            cell,
            now = { harness.clock.now() },
            withholdEvery = withholdEvery,
        )

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

        /**
         * 든 채로 남은 기체를 만들고, 빈손을 요구하는 주문을 넣어 거절을 받는다. 랙은 든 채 실패해 사람이 취소로 닫는다([leaveHolding]):
         * 든 채 **도는** 랙 뒤에 내면 그 손에 든 것이 랙의 일이라 제안이 서지 않는다(§15.319).
         */
        fun rejectedWhileHolding(jobOrderId: String = "PATROL-1"): Middleware.Submission.Rejected {
            val order = JobOrder(
                jobOrderId = "SEQ-${++racks}",
                workMasterId = PrepareSequencedRack.WORK_MASTER,
                version = 17,
                requiredEvidence = Evidence.E2,
                materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", 1)),
                equipmentRequirements = listOf(
                    EquipmentRequirement("RACK-204.S01", "destination", mapOf("material" to "ENGINE-COVER-A")),
                    EquipmentRequirement("SEQ-IN-02.BIN-A", "source", mapOf("material" to "ENGINE-COVER-A")),
                ),
            )
            order.equipmentRequirements.filter { it.equipmentUse == "destination" }
                .forEach { cell.program(it.id, it.properties["material"]) }
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order, ROBOT)).execution
            mw.leaveHolding(exec, harness) { drive(until = it) }
            return assertIs<Middleware.Submission.Rejected>(mw.submit(patrol(jobOrderId), ROBOT))
        }

        private var racks = 0

        override fun close() = harness.close()
    }

    private val place = mapOf(
        PrepareSequencedRack.P_OBJECT to "ENGINE-COVER-A",
        PrepareSequencedRack.P_DESTINATION to "RACK-204.S01",
    )

    // ── 의도적 비자동화

    @Test
    fun `가리지 않기로 했으면 늘 제안이 온다`() {
        // 0 을 고르는 것도 결정이다. 기본값이 아니라 배치가 명시한다.
        World(PRECOND, withholdEvery = 0).use { w ->
            repeat(3) {
                val rejected = w.rejectedWhileHolding("PATROL-$it")
                assertIs<Remedy.Found>(assertNotNull(rejected.remedy))
                assertFalse(rejected.remedyWithheld)
            }
        }
    }

    @Test
    fun `가릴 차례의 제안은 대안 없음이 아니라 가려졌다고 답한다`() {
        // 둘을 접으면 의도적 비자동화가 능력 부재와 구별되지 않아, 운영자가 시스템을 고장으로 읽는다.
        World(PRECOND, withholdEvery = 1).use { w ->
            val rejected = w.rejectedWhileHolding()

            assertNull(rejected.remedy, "가린다면서 제안을 같이 보냈다")
            assertTrue(rejected.remedyWithheld, "«가렸다» 와 «없다» 가 같은 답으로 나간다")
            assertTrue(w.mw.withheldProposal(ROBOT, "PATROL-1"))
            assertNull(w.mw.proposal(ROBOT, "PATROL-1"), "가려 둔 제안이 조회로 새어 나간다")
        }
    }

    @Test
    fun `가려 둔 제안은 승인할 수 없다`() {
        World(PRECOND, withholdEvery = 1).use { w ->
            w.rejectedWhileHolding()
            val answer = assertIs<Middleware.Submission.Rejected>(
                w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(place), OPERATOR),
            )
            assertTrue(answer.reason.contains("사람이 먼저 진단해야 한다"), answer.reason)
        }
    }

    @Test
    fun `사람이 먼저 진단하면 제안이 풀린다`() {
        // 제안을 보고 나서 적으면 그것은 진단이 아니라 동의다. 그래서 순서가 뒤집히지 않는다.
        World(PRECOND, withholdEvery = 1).use { w ->
            w.rejectedWhileHolding()
            assertTrue(w.mw.diagnose(ROBOT, "PATROL-1", "그리퍼에 부품이 남아 있다"))

            assertFalse(w.mw.withheldProposal(ROBOT, "PATROL-1"))
            assertEquals("그리퍼에 부품이 남아 있다", w.mw.diagnosis(ROBOT, "PATROL-1"))
            assertIs<Remedy.Found>(assertNotNull(w.mw.proposal(ROBOT, "PATROL-1")))
            assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(place), OPERATOR))
        }
    }

    @Test
    fun `가려 둔 제안이 없으면 진단이 적히지 않는다`() {
        // 없는 사건에 진단을 남기지 않는다 — 남기면 그 기록이 나중에 대조의 근거인 척한다.
        World(PRECOND, withholdEvery = 0).use { w ->
            assertFalse(w.mw.diagnose(ROBOT, "PATROL-1", "아무거나"))
            assertNull(w.mw.diagnosis(ROBOT, "PATROL-1"))
        }
    }

    // ── 반복 승인은 결함 신호다

    @Test
    fun `같은 조치가 거듭 승인되면 시스템이 그것을 고발한다`() {
        // 우회 경로가 매번 잘 돌아가면 아무도 그리퍼를 교체하지 않는다. 그래서 반복 자체를 올린다.
        World(PRECOND, withholdEvery = 0).use { w ->
            listOf("PATROL-A", "PATROL-B").forEach { id ->
                w.rejectedWhileHolding(id)
                val approved = assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(ROBOT, id, listOf(place), OPERATOR)).execution
                // 다음 제안이 서려면 이 승인의 실행이 기체를 놓아야 한다 — 남이 쥔 기체에는 제안을 세우지 않는다(§15.319).
                w.mw.letGo(approved) { w.drive(rounds = 400, until = it) }
            }

            val repeated = assertNotNull(w.mw.repeatedRemedies().singleOrNull(), "반복이 안 잡혔다: ${w.mw.repeatedRemedies()}")
            assertEquals(ROBOT, repeated.robotId)
            assertEquals(listOf("pick_place"), repeated.steps)
            assertEquals(2, repeated.approvals)
        }
    }

    @Test
    fun `한 번 승인한 것은 아직 반복이 아니다`() {
        // 한 번은 조치이고 두 번부터가 양식이다. 한 번에 고발하면 지표가 곧 소음이 된다.
        World(PRECOND, withholdEvery = 0).use { w ->
            w.rejectedWhileHolding("PATROL-A")
            assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(ROBOT, "PATROL-A", listOf(place), OPERATOR))

            assertEquals(emptyList(), w.mw.repeatedRemedies())
            assertEquals(1, w.mw.repeatedRemedies(atLeast = 1).single().approvals)
        }
    }

    companion object {
        /** 사람이 누른다 — 선언 목록을 안 본다(ADR 43). */
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)

        const val ROBOT = "hum-02"

        /** 첫 단위가 `navigate_to` 인 점검 순회 — 든 채로는 못 보내는 주문이다. */
        private fun patrol(jobOrderId: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = listOf(
                EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1")),
            ),
        )
        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()
    }
}

package dev.picasso.middleware

import dev.picasso.capability.Remedy
import dev.picasso.harness.Harness
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 배정 관문과 제안 포트(설계안 §7).
 *
 * **관문은 고르지 않고 상태를 바꾸지 않는다.** 고르면 배정기가 되고, 상태를 바꾸면 후보를 물어보는 것이
 * 곧 결정이 된다. 순위는 밖에서 오거나 비용 함수가 매기며, **채택 시점에 관문을 다시 묻는다** — 순위가
 * 만들어진 뒤 상태가 바뀌었을 수 있기 때문이다.
 */
class AssignmentTest {

    private class World(val cost: AssignmentCost = AssignmentCost()) : AutoCloseable {
        val harness = Harness(mapOf(A to PRECOND, B to PRECOND))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() }, cost = cost)

        fun drive(rounds: Int = 200, step: Duration = Duration.ofSeconds(1), until: () -> Boolean) {
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
         * 그 기체를 든 채로 만든다 — 사슬 검사가 막을 상태다.
         *
         * **랙은 든 채 실패해 사람이 취소로 닫는다**([leaveHolding]). 든 채 도는 랙이면 그 손에 든 것이 랙의 일이라 거절은 해도
         * 제안이 서지 않는다(§15.319). 기체를 쥔 실행 없이 손만 든 채여야 기체 스냅숏의 손으로 거절되고 제안이 선다.
         *
         * **기체마다 자리를 따로 준다.** 같은 자리를 쓰면 둘째 기체가 점유 관문에 먼저 걸려,
         * 이 시험이 보려는 것(사슬 검사)이 아니라 다른 것을 보게 된다.
         */
        fun makeHolding(robotId: String) {
            val slot = "$RACK.$robotId"
            val bin = "$BIN.$robotId"
            cell.program(slot, MATERIAL)
            cell.program(bin, MATERIAL)
            val order = rack("SEQ-HOLD-$robotId", slot, bin)
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order, robotId)).execution
            mw.leaveHolding(exec, harness) { drive(until = it) }
        }

        override fun close() = harness.close()
    }

    // ── 관문의 성질

    @Test
    fun `관문은 상태를 바꾸지 않는다`() {
        // ★부작용이 있으면 후보 셋을 물어보는 것만으로 제안이 셋 쌓이고 가림 차례가 세 칸 돌아간다.
        //   묻는 것이 곧 결정이 되면 그것은 관문이 아니다.
        World().use { w ->
            w.makeHolding(A)
            val order = patrol("PATROL-1")
            val planned = InspectAsset().plan(order)

            repeat(3) { assertIs<Admission.Refused>(w.mw.admits(order, A, planned)) }

            assertNull(w.mw.proposal(A, "PATROL-1"), "관문에 물어본 것만으로 제안이 쌓였다")
            assertEquals(1, w.mw.executions().size, "관문이 실행을 만들었다")
        }
    }

    @Test
    fun `채택을 시도해야 제안이 남는다`() {
        // 물어본 것과 내려다 막힌 것은 다른 일이다. 앞엣것에 기록이 붙으면 «물어봤다» 가 «시도했다» 로 쌓인다.
        World().use { w ->
            w.makeHolding(A)
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-1"), A))
            assertIs<Remedy.Found>(w.mw.proposal(A, "PATROL-1"))
        }
    }

    @Test
    fun `가중치가 달라도 관문의 답은 같다`() {
        // 정책이 판정을 흔들지 않는다는 것이 이 배선의 요점이다. 관문은 비용을 읽지 않는다.
        val order = patrol("PATROL-1")
        val planned = InspectAsset().plan(order)
        World(AssignmentCost(perLiveExecution = 1, whenHolding = 10)).use { cheap ->
            World(AssignmentCost(perLiveExecution = 20, whenHolding = 1)).use { dear ->
                cheap.makeHolding(A)
                dear.makeHolding(A)
                assertEquals(
                    cheap.mw.admits(order, A, planned)::class,
                    dear.mw.admits(order, A, planned)::class,
                )
                assertEquals(Admission.Passed, cheap.mw.admits(order, B, planned))
                assertEquals(Admission.Passed, dear.mw.admits(order, B, planned))
            }
        }
    }

    // ── 제안 포트

    @Test
    fun `순위 목록 위에서부터 첫 통과를 채택한다`() {
        // 순서는 배정기의 것이고 이 층은 다시 정렬하지 않는다.
        World().use { w ->
            val accepted = assertIs<Middleware.Submission.Accepted>(w.mw.adopt(patrol("PATROL-1"), listOf(B, A)))
            assertEquals(B, accepted.execution.robotId)
        }
    }

    @Test
    fun `낡은 제안은 채택 시점에 걸린다`() {
        // ★순위가 만들어진 뒤 그 기체의 상태가 바뀌었을 수 있다. 그 창은 통신 지연이 아니라
        //   읽는 자와 쓰는 자가 다르기 때문에 생긴다 — 그래서 채택 시점에 다시 묻는다.
        World().use { w ->
            w.makeHolding(A)
            val accepted = assertIs<Middleware.Submission.Accepted>(w.mw.adopt(patrol("PATROL-1"), listOf(A, B)))
            assertEquals(B, accepted.execution.robotId, "든 채인 기체가 순위 첫째라고 채택됐다")
        }
    }

    @Test
    fun `전부 떨어지면 미배정으로 남는다`() {
        // 재계산을 요청하지 않는다 — 요청하면 이 층이 배정기와 관문 사이의 중재자가 된다.
        World().use { w ->
            w.makeHolding(A)
            w.makeHolding(B)
            val unassigned = assertIs<Unassigned>(w.mw.adopt(patrol("PATROL-1"), listOf(A, B)))
            assertEquals(setOf(A, B), unassigned.refusals.keys)
            assertTrue(unassigned.refusals.values.all { it.isNotEmpty() }, "왜 떨어졌는지가 없다")
        }
    }

    @Test
    fun `미배정은 실행을 만들지 않는다`() {
        World().use { w ->
            w.makeHolding(A)
            w.makeHolding(B)
            val before = w.mw.executions().size
            assertIs<Unassigned>(w.mw.adopt(patrol("PATROL-1"), listOf(A, B)))
            assertEquals(before, w.mw.executions().size)
        }
    }

    // ── 미배정의 기록(§15.164)

    @Test
    fun `전부 떨어지면 후보마다 제안이 서고 순위 차례대로 대장에 남는다`() {
        // ★배정으로만 내는 호스트는 미배정이 아무것도 안 적으면 승인할 제안을 영영 못 본다. 관문은 그대로 순수하다 —
        //   적는 것은 순위 목록을 끝까지 시도한 채택이고, 열쇠는 작업 수락과 같은 (기체, 작업 지시)다.
        World().use { w ->
            w.makeHolding(A)
            w.makeHolding(B)
            assertIs<Unassigned>(w.mw.adopt(patrol("PATROL-1"), listOf(B, A)))

            val log = w.mw.remedySearches().filter { it.jobOrderId == "PATROL-1" }
            assertEquals(listOf(B, A), log.map { it.robotId }, "순위 차례대로 적히지 않았다: $log")
            assertTrue(log.all { it.outcome is RemedyOutcome.Found }, "대장: $log")
            assertIs<Remedy.Found>(w.mw.proposal(A, "PATROL-1"))
            assertIs<Remedy.Found>(w.mw.proposal(B, "PATROL-1"))
        }
    }

    @Test
    fun `하나라도 통과하면 앞에서 떨어진 후보는 적지 않는다`() {
        // 채택된 작업 지시의 제안은 승인해도 조치 열이 못 나간다(ADR 46). 적으면 승인할 수 없는 제안이 장부에 선다.
        World().use { w ->
            w.makeHolding(A)
            assertIs<Middleware.Submission.Accepted>(w.mw.adopt(patrol("PATROL-1"), listOf(A, B)))

            assertNull(w.mw.proposal(A, "PATROL-1"), "채택된 작업 지시에 제안이 섰다")
            assertEquals(emptyList(), w.mw.remedySearches().filter { it.jobOrderId == "PATROL-1" })
        }
    }

    @Test
    fun `작업 수락이 관문 앞에서 거부할 작업 지시는 미배정이어도 적지 않는다`() {
        // 승인하면 작업 수락이 근거 등급으로 거부하는 제안이다. 한 기체에 낸 작업 수락도 관문 앞에서 돌아가 안 적는다.
        World().use { w ->
            w.makeHolding(A)
            assertIs<Unassigned>(w.mw.adopt(patrol("PATROL-1").copy(requiredEvidence = Evidence.E1), listOf(A)))

            assertNull(w.mw.proposal(A, "PATROL-1"), "승인할 수 없는 제안이 섰다")
            assertEquals(emptyList(), w.mw.remedySearches().filter { it.jobOrderId == "PATROL-1" })
        }
    }

    @Test
    fun `미배정에서 선 제안의 승인은 작업 수락의 제안과 같이 소모되고 세어진다`() {
        // 같은 작업 지시의 다른 후보 제안은 남고, 누르면 조치 열이 못 나간 것으로 답한다 — 그 작업 지시는 이미 실행으로 섰다(ADR 46).
        World().use { w ->
            w.makeHolding(A)
            w.makeHolding(B)
            assertIs<Unassigned>(w.mw.adopt(patrol("PATROL-1"), listOf(A, B)))

            val accepted = assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(A, "PATROL-1", listOf(PLACE), OPERATOR))
            assertEquals(A, accepted.execution.robotId)
            val again = assertIs<Middleware.Submission.Rejected>(w.mw.approveRemedy(A, "PATROL-1", listOf(PLACE), OPERATOR))
            assertEquals(ApprovalRefusal.CONSUMED, again.approvalRefusal, again.reason)
            val sibling = assertIs<Middleware.Submission.Rejected>(w.mw.approveRemedy(B, "PATROL-1", listOf(PLACE), OPERATOR))
            assertEquals(ApprovalRefusal.REMEDY_NOT_APPLIED, sibling.approvalRefusal, sibling.reason)
            assertEquals(listOf(RepeatedRemedy(A, listOf(PrepareSequencedRack.SKILL), 1)), w.mw.repeatedRemedies(atLeast = 1))
        }
    }

    // ── 비용 함수

    @Test
    fun `가중치를 바꾸면 순위가 바뀐다`() {
        // 정책은 데이터로 밖에. 한 기체는 바쁘고 다른 기체는 든 채일 때, 무엇을 더 싫어하는지가 값으로 갈린다.
        val busy = { id: String -> if (id == A) 3 else 0 }
        val holding = { id: String -> id == B }

        assertEquals(listOf(A, B), AssignmentCost(perLiveExecution = 1, whenHolding = 10).rank(listOf(A, B), busy, holding))
        assertEquals(listOf(B, A), AssignmentCost(perLiveExecution = 20, whenHolding = 1).rank(listOf(A, B), busy, holding))
    }

    @Test
    fun `같은 비용이면 이름으로 가른다`() {
        // ★재생이 결정적이어야 한다. 같은 입력에 순서가 흔들리면 tick 재생이 같은 답을 안 낸다.
        val flat = { _: String -> 0 }
        val none = { _: String -> false }
        assertEquals(listOf(A, B), AssignmentCost().rank(listOf(B, A), flat, none))
        assertEquals(listOf(A, B), AssignmentCost().rank(listOf(A, B), flat, none))
    }

    @Test
    fun `비용은 이 층이 아는 항만 쓴다`() {
        // 이동 거리나 배터리는 여기 없다 — 이 층은 좌표도 기체 내부도 모른다. 지어낸 항을 넣으면
        // 가중치가 아무 의미 없는 수를 곱한다.
        val cost = AssignmentCost(perLiveExecution = 2, whenHolding = 5)
        assertEquals(0, cost.of(liveExecutions = 0, holding = false))
        assertEquals(4, cost.of(liveExecutions = 2, holding = false))
        assertEquals(9, cost.of(liveExecutions = 2, holding = true))
    }

    @Test
    fun `중복 후보는 한 번만 센다`() {
        // 같은 기체가 목록에 두 번 있으면 관문에 두 번 묻게 되고, 미배정 사유도 두 벌이 된다.
        assertEquals(listOf(A, B), AssignmentCost().rank(listOf(A, B, A), { 0 }, { false }))
    }

    companion object {
        const val A = "hum-02"
        const val B = "hum-03"
        const val MATERIAL = "ENGINE-COVER-A"
        const val RACK = "RACK-204.S01"
        const val BIN = "SEQ-IN-02.BIN-A"

        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()

        private val OPERATOR = Approver("op-1", ApproverKind.PERSON)

        /** 사람이 조치 걸음(`pick_place`)에 싣는 값. */
        private val PLACE = mapOf(
            PrepareSequencedRack.P_OBJECT to MATERIAL,
            PrepareSequencedRack.P_DESTINATION to "DROP-01",
        )

        private fun rack(jobOrderId: String, to: String, from: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E2,
            materialRequirements = listOf(MaterialRequirement(MATERIAL, 1)),
            equipmentRequirements = listOf(
                EquipmentRequirement(to, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to MATERIAL)),
                EquipmentRequirement(from, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to MATERIAL)),
            ),
        )

        /** 든 채로는 못 보내는 주문 — 첫 단위가 `navigate_to` 다. */
        private fun patrol(jobOrderId: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = listOf(
                EquipmentRequirement(
                    "PUMP-01",
                    EquipmentUse.INSPECTION_TARGET,
                    mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1"),
                ),
            ),
        )
    }
}

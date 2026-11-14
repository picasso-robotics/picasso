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
import kotlin.test.assertTrue

/**
 * 탐색 결과 대장 — **밖에서 세 답이 구별되는가**(설계안 §6.3 · §7.2 넷째).
 *
 * 밖에서 보면 «제안이 없다» 하나가 셋을 덮는다. 「이 기체로는 안 된다」와 「더 찾아보면 있을 수 있다」와
 * 「사람이 먼저 진단하라」는 다음 행동이 전부 다르므로, 접히면 운영자가 의도적 비자동화를 고장으로 읽고
 * 상한에 걸린 것을 능력 부재로 읽는다.
 */
class RemedyLedgerTest {

    private class Floors(private val rows: Map<String, FloorOwner>) : FloorOwnership {
        override fun ownerOf(location: String): FloorOwner = rows[location] ?: FloorOwner.NotDeclared
    }

    private class World(
        profile: Path,
        withholdEvery: Int = 0,
        floors: FloorOwnership = FloorOwnership.None,
    ) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to profile))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(
            ClientRobotPort(harness.client()),
            cell,
            now = { harness.clock.now() },
            withholdEvery = withholdEvery,
            floors = floors,
        )

        private var racks = 0

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
         * 든 채로 남은 기체를 만든다 — 빈손을 요구하는 주문이 여기서만 거절된다. 랙은 든 채 실패해 사람이 취소로 닫는다([leaveHolding]):
         * 든 채 **도는** 랙 뒤에 내면 그 손에 든 것이 랙의 일이라 제안이 서지 않는다(§15.319).
         */
        fun holding(): Middleware.Execution {
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
            return mw.leaveHolding(exec, harness) { drive(until = it) }
        }

        fun settle(exec: Middleware.Submission.Accepted) = settle(exec.execution)

        fun settle(exec: Middleware.Execution) = drive(rounds = 250) { exec.physicalState.isSettled }

        override fun close() = harness.close()
    }

    // ── 세 답

    @Test
    fun `대안을 찾으면 그 걸음이 대장에 남는다`() {
        World(PRECOND).use { w ->
            val exec = w.holding()
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-1"), ROBOT))
            w.settle(exec)

            val record = assertNotNull(w.mw.remedySearches().singleOrNull(), "대장: ${w.mw.remedySearches()}")
            assertEquals(ROBOT, record.robotId)
            assertEquals("PATROL-1", record.jobOrderId)
            val found = assertIs<RemedyOutcome.Found>(record.outcome)
            assertEquals(listOf("pick_place"), found.steps.map { it.skillType })
        }
    }

    @Test
    fun `대안이 없으면 그 사유가 대장에 남는다`() {
        // ★이것이 이 대장의 존재 이유다. 「이 기체로는 안 된다」가 여기 안 실리면 밖에서는 탐색이
        //   안 돈 것과 구별되지 않고, 그 구별이 없으면 운영자는 시스템이 고장 난 것으로 읽는다.
        World(NO_REMEDY).use { w ->
            val exec = w.holding()
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-1"), ROBOT))
            w.settle(exec)

            val record = assertNotNull(w.mw.remedySearches().singleOrNull(), "대장: ${w.mw.remedySearches()}")
            val none = assertIs<RemedyOutcome.None>(record.outcome)
            assertEquals(Remedy.None.Cause.NO_CAPABILITY, none.cause)
            assertTrue(none.unmet.isNotEmpty(), "못 채운 조건이 비었다 — 왜 못 찾았는지가 사라진다")
            assertEquals(HoldKind.HOLD_KIND_EMPTY, none.unmet.single().required)
        }
    }

    @Test
    fun `가려 둔 제안은 가렸다고만 답하고 걸음을 안 싣는다`() {
        // ★대장이 가린 것의 내용을 내면 조회 한 번으로 가림이 풀린다 — 가리는 일 자체가 무의미해진다.
        World(PRECOND, withholdEvery = 1).use { w ->
            val exec = w.holding()
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-1"), ROBOT))
            w.settle(exec)

            val record = assertNotNull(w.mw.remedySearches().singleOrNull(), "대장: ${w.mw.remedySearches()}")
            assertEquals(RemedyOutcome.Withheld, record.outcome)
            assertTrue(w.mw.withheldProposal(ROBOT, "PATROL-1"), "가린 것이 아니라면 이 시험의 전제가 틀렸다")
            val leaked = w.mw.remedySearches().map { it.outcome }.filterIsInstance<RemedyOutcome.Found>()
            assertEquals(emptyList(), leaked, "가려 둔 제안의 걸음이 대장으로 샜다")
        }
    }

    // ── 무엇을 적고 무엇을 안 적는가

    @Test
    fun `탐색이 안 돈 거절은 대장에 없다`() {
        // 안 찾아본 것을 「못 찾았다」로 적으면 그 순간 대장이 없는 사실을 만든다. 다른 관문의 거절은
        // 파지를 보지도 않았으므로 탐색의 답이 아니다.
        World(PRECOND, floors = Floors(mapOf(PUMP_ROOM to FloorOwner.Unowned))).use { w ->
            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-1"), ROBOT))
            assertTrue("소유자가 없다" in rejected.reason, rejected.reason)
            assertEquals(emptyList(), w.mw.remedySearches())
        }
    }

    @Test
    fun `미배정으로 끝난 채택도 시도한 것으로 대장에 남는다`() {
        // ★관문은 순수 술어라 후보 셋에 물어봐도 아무것도 안 쌓인다(§15.161). 쌓는 것은 실제로 내려 본 쪽이고,
        //   순위 목록을 끝까지 시도해 전부 떨어진 채택이 그쪽이다(§15.164). 안 적으면 배정으로만 내는 호스트에는
        //   «찾았다» 와 «안 찾아봤다» 가 같은 침묵이다.
        World(PRECOND).use { w ->
            val exec = w.holding()
            assertIs<Unassigned>(w.mw.adopt(patrol("PATROL-1"), listOf(ROBOT)))
            w.settle(exec)

            val record = assertNotNull(w.mw.remedySearches().singleOrNull(), "대장: ${w.mw.remedySearches()}")
            assertEquals(ROBOT, record.robotId)
            assertEquals("PATROL-1", record.jobOrderId)
            assertIs<RemedyOutcome.Found>(record.outcome)
        }
    }

    @Test
    fun `거듭 거절되면 덮어쓰지 않고 답한 순서대로 쌓인다`() {
        // 같은 (기체, 주문)도 거듭 거절될 수 있고, 덮어쓰면 밖에서 주기 스캔하는 쪽이 사이의 답을 놓친다.
        World(PRECOND).use { w ->
            listOf("PATROL-A", "PATROL-B").forEach { id ->
                val exec = w.holding()
                assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol(id), ROBOT))
                w.settle(exec)
            }

            val log = w.mw.remedySearches()
            assertEquals(listOf("search-1", "search-2"), log.map { it.searchId })
            assertEquals(listOf("PATROL-A", "PATROL-B"), log.map { it.jobOrderId })
            assertTrue(log.all { it.outcome is RemedyOutcome.Found })
        }
    }

    companion object {
        const val ROBOT = "hum-02"
        const val PUMP_ROOM = "PUMP-ROOM-1"

        private fun patrol(jobOrderId: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = listOf(
                EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to PUMP_ROOM)),
            ),
        )

        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()

        /** `pick_place` 도 빈손을 요구한다 — 든 채로는 딛을 스킬이 없어 탐색이 「없다」를 답한다. */
        private val NO_REMEDY: Path = Path.of("..", "profile", "fixtures", "no-remedy.json").normalize()
    }
}

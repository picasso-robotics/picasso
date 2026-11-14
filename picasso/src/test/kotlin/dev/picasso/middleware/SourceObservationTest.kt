package dev.picasso.middleware

import dev.picasso.harness.Harness
import dev.picasso.middleware.mission.Activation
import dev.picasso.middleware.mission.InMemoryMissionCatalog
import dev.picasso.middleware.mission.MissionFixtures
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 출발 관측 선언 — **설비가 말이 없을 때 보내는가는 임무 정의가 정한다.**
 *
 * 기본은 그대로다(`OPTIONAL`, 말이 없으면 보낸다 — [OccupancyTest] 의 «설비가 말이 없으면 보낸다»). 바뀐 것은 그 기본이
 * 암묵이 아니라는 것이다. `REQUIRED` 로 선언한 단위는 말이 없거나 신원 없이 «있다» 만 들은 출발 자리로 보내지 않고,
 * 그 답은 결품([RemedyOutcome.SourceMissing])과 다른 **확인 요구**([SourceUnobserved])다 — 결품으로 접으면 운영자가
 * 재고를 의심하고, 대체 자리를 찾아 주문을 고친다.
 *
 * 확인은 이 층의 덮어쓰기 칸이 아니라 **셀의 관측**으로 들어온다. 그래서 마지막 시험이 셀이 신원을 답한 뒤 같은
 * 작업 요청이 통과하는 것을 댄다.
 */
class SourceObservationTest {

    private class World(declaration: String) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to PRECOND))
        val cell = CellMimic(now = { harness.clock.now() })
        val catalog = InMemoryMissionCatalog(now = { harness.clock.now() })
        val mw = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() }, missions = catalog)

        init {
            val activated = catalog.activate(
                MissionFixtures.prepareSequencedRack(sourceObservation = declaration),
                MissionFixtures.SIGNALS,
                FloorOwnership.None,
                MissionFixtures.SITE_SKILLS,
            )
            assertIs<Activation.Activated>(activated, "시험 정의가 서지 않았다")
            cell.program(RACK, MATERIAL)
        }

        override fun close() = harness.close()
    }

    @Test
    fun `OPTIONAL 이면 설비가 말이 없어도 보낸다`() {
        // 오늘의 거동이다. 선언했다는 것만 다르다.
        World("OPTIONAL").use { w ->
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack("SEQ-1"), ROBOT))
        }
    }

    @Test
    fun `REQUIRED 이면 설비가 말이 없을 때 확인을 요구하며 보내지 않는다`() {
        World("REQUIRED").use { w ->
            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(rack("SEQ-1"), ROBOT))

            val unobserved = assertIs<SourceUnobserved>(rejected.sourceUnobserved, "확인 요구가 구조로 안 실렸다")
            assertEquals(BIN_A, unobserved.source)
            assertEquals(MATERIAL, unobserved.material)
            assertNull(unobserved.occupied, "말이 없었는데 있다·없다를 지어냈다")
            // 결품이 아니다 — 대체 자리를 계산하지 않는다. 대장에는 결품이 아니라 출발 미관측 줄로 선다(§15.322).
            assertNull(rejected.alternativeLocations)
            assertEquals(listOf<RemedyOutcome>(unobserved), w.mw.remedySearches().map { it.outcome }, "확인 요구가 출발 미관측으로 대장에 안 섰다")
            assertEquals(emptyList(), w.mw.executions(), "확인 전에 실행이 생겼다")
        }
    }

    @Test
    fun `REQUIRED 이면 미배정의 후보마다 확인 요구가 남고 결품 답에는 없다`() {
        World("REQUIRED").use { w ->
            val unassigned = assertIs<Unassigned>(w.mw.adopt(rack("SEQ-1"), listOf(ROBOT)))

            val unobserved = unassigned.sourceUnobserved.getValue(ROBOT)
            assertEquals(BIN_A, unobserved.source)
            assertNull(unobserved.occupied)
            assertEquals(emptyMap(), unassigned.sourceMissing, "확인 요구가 결품으로 접혔다")
            assertTrue(ROBOT in unassigned.refusals)
            // 미배정도 작업 수락의 거절과 같이 적는다(§15.164). 확인 요구는 출발 미관측 줄이다(§15.322).
            assertEquals(listOf<RemedyOutcome>(unobserved), w.mw.remedySearches().map { it.outcome }, "확인 요구가 대장에 출발 미관측으로 안 섰다")
        }
    }

    @Test
    fun `REQUIRED 이면 신원 없이 있다고만 한 자리도 확인을 요구한다`() {
        World("REQUIRED").use { w ->
            w.cell.program(BIN_A, null)

            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(rack("SEQ-1"), ROBOT))
            val unobserved = assertIs<SourceUnobserved>(rejected.sourceUnobserved)
            assertEquals(true, unobserved.occupied, "있다는 관측을 버렸다")
            assertEquals(listOf<RemedyOutcome>(unobserved), w.mw.remedySearches().map { it.outcome })
        }
    }

    @Test
    fun `REQUIRED 이어도 맞는 신원이 관측되면 보낸다`() {
        World("REQUIRED").use { w ->
            w.cell.program(BIN_A, MATERIAL)
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack("SEQ-1"), ROBOT))
        }
    }

    @Test
    fun `REQUIRED 이어도 빈 자리는 결품이다`() {
        // 결품은 결품으로 남는다 — 확인 요구로 접으면 대체 자리 제시가 사라진다.
        World("REQUIRED").use { w ->
            w.cell.empty(BIN_A)

            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(rack("SEQ-1"), ROBOT))
            assertNull(rejected.sourceUnobserved, "결품이 확인 요구로 접혔다")
            assertEquals(emptyList(), rejected.alternativeLocations)
            assertIs<RemedyOutcome.SourceMissing>(w.mw.remedySearches().single().outcome)

            val unassigned = assertIs<Unassigned>(w.mw.adopt(rack("SEQ-2"), listOf(ROBOT)))
            assertEquals(BIN_A, unassigned.sourceMissing.getValue(ROBOT).source)
            assertEquals(emptyMap(), unassigned.sourceUnobserved)
        }
    }

    @Test
    fun `확인이 셀의 관측으로 들어오면 같은 작업 요청이 통과한다`() {
        // 상위 시스템의 확인·인계 시점 점검·기록된 수동 확인 — 셋 다 셀 포트가 신원을 답하는 것으로 들어온다.
        // 이 층에는 덮어쓰기 칸이 없다.
        World("REQUIRED").use { w ->
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(rack("SEQ-1"), ROBOT))
            w.cell.program(BIN_A, MATERIAL)
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack("SEQ-1"), ROBOT))
        }
    }

    companion object {
        const val ROBOT = "hum-02"
        const val MATERIAL = "ENGINE-COVER-A"
        const val RACK = "RACK-204.S01"
        const val BIN_A = "SEQ-IN-02.BIN-A"

        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()

        /** 한 자재를 [BIN_A] 에서 집어 선반에 놓는 주문. */
        private fun rack(jobOrderId: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E2,
            materialRequirements = listOf(MaterialRequirement(MATERIAL, 1)),
            equipmentRequirements = listOf(
                EquipmentRequirement(RACK, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to MATERIAL)),
                EquipmentRequirement(BIN_A, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to MATERIAL)),
            ),
        )
    }
}

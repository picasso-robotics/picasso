package dev.picasso.middleware

import dev.picasso.harness.Harness
import dev.picasso.middleware.mission.Activation
import dev.picasso.middleware.mission.InMemoryMissionCatalog
import dev.picasso.middleware.mission.MissionFixtures
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 목적지 관측 선언 — **자재를 둘 자리가 비었는지를 설비 관측으로 볼지는 임무 정의가 정한다.**
 *
 * 기본은 그대로다(`OPTIONAL`, 목적지를 설비 관측으로 보지 않는다). 시험 픽스처 대부분이 목적지를 제출 전에 «있다» 로
 * 프로그램하는 것은 보고서 12.1 의 전제(작업 전 목적지 신호는 옛 신호)를 따른 것이고, 그 시험들은 그대로 지나야 한다.
 * `REQUIRED` 로 선언한 단위는 그 현장이 작업 전 목적지 «있다» 를 물리적 점유로 본다고 선언한 것이다. 그래서 목적지에
 * 무엇이 있으면 «목적지 점유», 설비가 말이 없으면 «목적지 미관측» 으로 거부하고, «비었다» 일 때만 보낸다.
 *
 * 게이트가 보는 것은 진행 중인 실행의 예약이 아니라 **지금 자리의 관측**이다. 그래서 마지막 두 시험이 대는 경우, 곧 넣다가
 * 다른 부품이 남은 채 실행이 종료해 예약이 풀린 자리를 `REQUIRED` 단위가 다시 쓰지 못한다. `OPTIONAL` 은 오늘처럼 보낸다.
 */
class DestinationObservationTest {

    private class World(declaration: String) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to PRECOND))
        val cell = CellMimic(now = { harness.clock.now() })
        val catalog = InMemoryMissionCatalog(now = { harness.clock.now() })
        val mw = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() }, missions = catalog)

        init {
            val activated = catalog.activate(
                MissionFixtures.prepareSequencedRack(sourceObservation = "OPTIONAL", destinationObservation = declaration),
                MissionFixtures.SIGNALS,
                FloorOwnership.None,
                MissionFixtures.SITE_SKILLS,
            )
            assertIs<Activation.Activated>(activated, "시험 정의가 서지 않았다: $activated")
            cell.program(BIN_A, MATERIAL)
        }

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

        override fun close() = harness.close()
    }

    @Test
    fun `REQUIRED 이면 목적지에 다른 부품이 있을 때 보내지 않는다`() {
        World("REQUIRED").use { w ->
            w.cell.program(RACK, OTHER)

            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(rack("SEQ-1"), ROBOT))
            assertTrue(RACK in rejected.reason && "목적지 점유" in rejected.reason, rejected.reason)
            assertTrue(OTHER in rejected.reason, "무엇이 있는지 적어야 운영자가 치울 것을 안다: ${rejected.reason}")
            // 결품도 출발 미관측도 아니다 — 대체 자리를 계산하지 않는다. 레지스터에는 목적지 점유 줄로 선다(§15.322) — 걸음 없이.
            assertNull(rejected.alternativeLocations)
            assertNull(rejected.sourceUnobserved)
            assertNull(rejected.remedy, "목적지 점유가 승인할 조치로 섰다")
            assertEquals(listOf<RemedyOutcome>(RemedyOutcome.DestinationOccupied(RACK, OTHER)), w.mw.remedySearches().map { it.outcome }, "목적지 점유가 구조로 안 남았다")
            assertEquals(emptyList(), w.mw.executions(), "차 있는 자리로 실행이 생겼다")
        }
    }

    @Test
    fun `REQUIRED 이면 있다고만 한 목적지와 같은 부품이 있는 목적지도 점유다`() {
        // ★출발 쪽과 다르다 — 출발은 식별 정보가 맞아야 통과지만, 목적지는 무엇이 있든 차 있으면 둘 자리가 없다.
        World("REQUIRED").use { w ->
            w.cell.program(RACK, null)
            val bare = assertIs<Middleware.Submission.Rejected>(w.mw.submit(rack("SEQ-1"), ROBOT))
            assertTrue("목적지 점유" in bare.reason, bare.reason)
            // 있다고만 했다 — 점유 신원 불명은 널이다. 빈 문자열로 접지 않는다.
            assertEquals(RemedyOutcome.DestinationOccupied(RACK, null), w.mw.remedySearches().last().outcome)

            w.cell.program(RACK, MATERIAL)
            val same = assertIs<Middleware.Submission.Rejected>(w.mw.submit(rack("SEQ-1"), ROBOT))
            assertTrue("목적지 점유" in same.reason, same.reason)
            assertEquals(emptyList(), w.mw.executions())
        }
    }

    @Test
    fun `REQUIRED 이면 설비가 말이 없을 때 목적지 미관측으로 보내지 않는다`() {
        World("REQUIRED").use { w ->
            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(rack("SEQ-1"), ROBOT))
            assertTrue(RACK in rejected.reason && "목적지 미관측" in rejected.reason, rejected.reason)
            assertFalse("목적지 점유" in rejected.reason, "말이 없었는데 차 있다고 지어냈다: ${rejected.reason}")
            assertNull(rejected.alternativeLocations)
            assertEquals(listOf<RemedyOutcome>(RemedyOutcome.DestinationUnobserved(RACK)), w.mw.remedySearches().map { it.outcome }, "목적지 미관측이 구조로 안 남았다")
            assertEquals(emptyList(), w.mw.executions(), "확인 전에 실행이 생겼다")
        }
    }

    @Test
    fun `REQUIRED 이어도 목적지가 비었다고 관측되면 보낸다`() {
        World("REQUIRED").use { w ->
            w.cell.empty(RACK)
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack("SEQ-1"), ROBOT))
        }
    }

    @Test
    fun `REQUIRED 여도 식별 정보가 없는 단위의 목적지는 보지 않는다`() {
        // 예약과 같은 기준이다 — 식별 정보가 없으면 자재를 둘 자리가 아니다. 여기서는 material 없는 슬롯이라 계획에서 실패한 단위다.
        World("REQUIRED").use { w ->
            val bare = JobOrder(
                jobOrderId = "SEQ-1",
                workMasterId = PrepareSequencedRack.WORK_MASTER,
                version = 1,
                requiredEvidence = Evidence.E2,
                equipmentRequirements = listOf(EquipmentRequirement(RACK, EquipmentUse.DESTINATION)),
            )
            val accepted = assertIs<Middleware.Submission.Accepted>(w.mw.submit(bare, ROBOT))
            assertNull(accepted.execution.units.single().expectedIdentity, "이 시험이 보려는 경우가 아니다")
        }
    }

    @Test
    fun `OPTIONAL 이면 목적지를 설비 관측으로 보지 않는다`() {
        // 오늘의 동작이다 — 차 있다는 관측도, 말이 없는 것도 판정하지 않는다.
        World("OPTIONAL").use { w ->
            w.cell.program(RACK, OTHER)
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack("SEQ-1"), ROBOT))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack("SEQ-2", slot = RACK_2), ROBOT))
        }
    }

    @Test
    fun `REQUIRED 이면 미배정의 거부 사유와 레지스터에 목적지 점유가 남고 결품과 출발 미관측 응답에는 없다`() {
        World("REQUIRED").use { w ->
            w.cell.program(RACK, OTHER)

            val unassigned = assertIs<Unassigned>(w.mw.adopt(rack("SEQ-1"), listOf(ROBOT)))
            assertTrue("목적지 점유" in unassigned.refusals.getValue(ROBOT), unassigned.toString())
            assertEquals(emptyMap(), unassigned.sourceMissing, "목적지 점유가 결품으로 접혔다")
            assertEquals(emptyMap(), unassigned.sourceUnobserved, "목적지 점유가 출발 미관측으로 접혔다")
            // 후보 전부가 떨어진 미배정은 시도한 것으로 적는다(§15.164) — 목적지 점유 줄이다(§15.322).
            assertEquals(listOf<RemedyOutcome>(RemedyOutcome.DestinationOccupied(RACK, OTHER)), w.mw.remedySearches().map { it.outcome })
        }
    }

    @Test
    fun `목적지 점유는 출발 결품보다 먼저 본다`() {
        // 목적지가 차 있으면 출발 자리를 고쳐도 보낼 수 없다 — 대체 출발 자리를 제시하면 운영자가 소용없는 수정을 한다.
        World("REQUIRED").use { w ->
            w.cell.program(RACK, OTHER)
            w.cell.empty(BIN_A)

            val rejected = assertIs<Middleware.Submission.Rejected>(w.mw.submit(rack("SEQ-1"), ROBOT))
            assertTrue("목적지 점유" in rejected.reason, rejected.reason)
            assertNull(rejected.alternativeLocations, "목적지가 차 있는데 대체 출발 자리를 냈다")
            assertEquals(listOf<RemedyOutcome>(RemedyOutcome.DestinationOccupied(RACK, OTHER)), w.mw.remedySearches().map { it.outcome }, "목적지가 차 있는데 결품이 레지스터에 섰다")
        }
    }

    @Test
    fun `넣다가 다른 부품이 남은 자리는 실행이 종료한 뒤에도 REQUIRED 단위를 보내지 않는다`() {
        // 설계 검토 문제 7 의 시나리오다. 실패로 종료한 실행은 예약을 풀지만(§15.159), 자리에 남은 것은 셀이 본다.
        World("REQUIRED").use { w ->
            w.cell.empty(RACK)
            val first = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack("SEQ-1"), ROBOT)).execution
            // 둔 뒤 셀 검증 장치가 다른 부품을 본다 — 불일치로 실패하고 그 부품은 자리에 남는다.
            w.cell.program(RACK, OTHER)
            w.drive { first.physicalState.isSettled && first.active == null }
            assertEquals(Middleware.MISMATCH, first.units.single().failureClass, "이 시험이 보려는 경우가 아니다")

            val second = assertIs<Middleware.Submission.Rejected>(w.mw.submit(rack("SEQ-2"), ROBOT))
            assertTrue("목적지 점유" in second.reason, second.reason)
            assertFalse("SEQ-1" in second.reason, "예약으로 거부했다 — 종료한 실행의 예약은 풀려 있어야 한다: ${second.reason}")

            // 사람이 치운 것이 셀의 관측으로 들어오면 같은 작업 지시가 통과한다.
            w.cell.empty(RACK)
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack("SEQ-2"), ROBOT))
        }
    }

    @Test
    fun `OPTIONAL 이면 넣다가 다른 부품이 남은 자리로 오늘처럼 보낸다`() {
        // 남는 위험이다 — 선언하지 않은 현장과 코드 케이퍼빌리티는 이 경우를 거부하지 않는다(한계 레지스터).
        World("OPTIONAL").use { w ->
            w.cell.empty(RACK)
            val first = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack("SEQ-1"), ROBOT)).execution
            w.cell.program(RACK, OTHER)
            w.drive { first.physicalState.isSettled && first.active == null }
            assertEquals(Middleware.MISMATCH, first.units.single().failureClass)

            assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack("SEQ-2"), ROBOT))
        }
    }

    companion object {
        const val ROBOT = "hum-02"
        const val MATERIAL = "ENGINE-COVER-A"
        const val OTHER = "ENGINE-COVER-B"
        const val RACK = "RACK-204.S01"
        const val RACK_2 = "RACK-205.S01"
        const val BIN_A = "SEQ-IN-02.BIN-A"

        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()

        /** 한 자재를 [BIN_A] 에서 집어 [slot] 에 두는 작업 지시. */
        private fun rack(jobOrderId: String, slot: String = RACK) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E2,
            materialRequirements = listOf(MaterialRequirement(MATERIAL, 1)),
            equipmentRequirements = listOf(
                EquipmentRequirement(slot, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to MATERIAL)),
                EquipmentRequirement(BIN_A, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to MATERIAL)),
            ),
        )
    }
}

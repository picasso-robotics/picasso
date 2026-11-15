package dev.picasso.middleware

import dev.picasso.harness.Harness
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **① 이 남기는 한 줄을 ② 가 받는다.**
 *
 * `docs/scenarios.md` 는 둘을 이야기로 이어 적었다 — *"picasso 에서 이 시나리오가 남기는 것은 한 줄이다:
 * `SEQ-IN-02` 에 `HU-1042` 가 있다 — ② 의 환경 전제"*. 그런데 **시험은 각각 독립이었고 ② 는 그 전제를
 * 픽스처로 다시 세웠다.** 두 시나리오가 이름 규약으로만 이어져 있었다(§15.117).
 *
 * 이 시험이 그것을 잇는다. 한 미들웨어가 ① 을 돌려 용기를 셀에 넣고, **그 결과에서 ② 의 출발 자리를 유도해**
 * 이어서 돌린다. 그리고 전제가 없으면 어떻게 되는지도 같이 본다.
 *
 * ## 이 시험의 세계 모형은 거칠다
 *
 * 설비 대역이 *"입고 자리에 용기가 있으면 슬롯에서 그 부품을 본다"* 로 단순화돼 있다. 실제로는 로봇이 놓아야
 * 보이고, 그 사이에 집기·놓기가 있다. **여기서 잇는 것은 물리가 아니라 전제다** — ② 의 확인 가능성이 ① 의
 * 결과에 달려 있다는 것. 물리를 흉내내면 그것은 우리가 지은 또 하나의 세계가 된다.
 */
class ScenarioChainTest {

    private class World : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val fleet = AmrFleetMimic(now = { harness.clock.now() })

        /**
         * 인계 자리는 플릿이 실제로 내려놓은 것을 보고, **슬롯은 셀에 용기가 있을 때만** 부품을 본다.
         * 뒤엣것이 ① → ② 의 연결이다 — 부품이 셀에 없으면 슬롯에서 확인할 것도 없다.
         */
        val cell = CellMimic(
            now = { harness.clock.now() },
            live = { location ->
                when {
                    location.startsWith(RACK) ->
                        if (fleet.containersAt[INPUT] == CONTAINER) PART to harness.clock.now() else null
                    else -> fleet.containersAt[location]?.let { it to fleet.placedAt[location] }
                }
            },
        )

        val mw = Middleware(ClientRobotPort(harness.client()), cell, fleet, now = { harness.clock.now() })

        init {
            fleet.place(OUT, CONTAINER)
        }

        fun drive(rounds: Int = 40, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(5))
                fleet.tick()
                Thread.sleep(20)
            }
            error("조건에 못 미쳤다: 세계=${fleet.containersAt}")
        }

        override fun close() = harness.close()
    }

    /** ① — 용기를 창고 출고 버퍼에서 셀 입고 자리로. */
    private fun supply() = JobOrder(
        jobOrderId = "WT-781",
        workMasterId = DeliverContainer.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E2,
        equipmentRequirements = listOf(
            EquipmentRequirement(OUT, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_CONTAINER to CONTAINER)),
            EquipmentRequirement(INPUT, EquipmentUse.DESTINATION),
        ),
    )

    /**
     * ② — **출발 자리를 ① 의 목적지에서 만든다.** 리터럴로 적으면 두 시나리오가 다시 갈라진다.
     */
    private fun sequence(inputStation: String) = JobOrder(
        jobOrderId = "SEQ-204",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = 17,
        requiredEvidence = Evidence.E2,
        materialRequirements = listOf(MaterialRequirement(PART, 1)),
        equipmentRequirements = listOf(
            EquipmentRequirement("$RACK.S01", EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
            EquipmentRequirement("$inputStation.BIN-A", EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
        ),
    )

    @Test
    fun `공급이 끝나야 시퀀싱이 닫힌다 — 그 한 줄이 전제다`() {
        World().use { w ->
            // ① — 운반 전체를 플릿에 맡긴다. 로봇 계약은 여기 안 닿는다.
            val supplied = assertIs<Middleware.Submission.Accepted>(w.mw.submit(supply(), ROBOT)).execution
            w.drive { supplied.physicalState == PhysicalState.PHYSICALLY_DONE }

            // ★**① 이 남기는 한 줄.** 이것이 ② 의 환경 전제다.
            assertEquals(CONTAINER, w.fleet.containersAt[INPUT], "용기가 셀 입고 자리에 없다")
            val inputStation = supplied.units.single().destination!!
            assertEquals(INPUT, inputStation)

            // ② — 그 자리에서 집는다. 출발 자리를 ① 의 결과에서 만든다.
            val sequencing = assertIs<Middleware.Submission.Accepted>(w.mw.submit(sequence(inputStation), ROBOT)).execution
            assertEquals("$INPUT.BIN-A", sequencing.units.single().source)

            w.drive { sequencing.physicalState.isSettled }
            assertEquals(PhysicalState.PHYSICALLY_DONE, sequencing.physicalState)
            assertEquals(Evidence.E2, sequencing.units.single().reached, "설비가 확인했는데 등급이 안 올랐다")
        }
    }

    @Test
    fun `공급 없이 시퀀싱하면 설비가 확인할 것이 없다 — UNVERIFIED 이고 재작업이 아니다`() {
        // ① 을 안 돌린 세계. 로봇은 여전히 성공이라 말한다 — **미믹은 세계를 모른다.**
        // 갈리는 것은 E2 이고, 그것이 두 시나리오가 근거로 이어져 있다는 증거다.
        World().use { w ->
            val sequencing = assertIs<Middleware.Submission.Accepted>(w.mw.submit(sequence(INPUT), ROBOT)).execution
            w.drive { sequencing.physicalState.isSettled }

            assertEquals(PhysicalState.UNVERIFIED, sequencing.physicalState)
            assertEquals(UnitState.UNVERIFIED, sequencing.units.single().state)
            val notice = w.mw.responses().last()
            assertTrue(notice.operatorRequired, "확인 못 한 것을 조용히 넘겼다")
            assertEquals(listOf("$RACK.S01"), notice.unverifiedUnits)
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val OUT = "OUT-07"
        const val INPUT = "SEQ-IN-02"
        const val RACK = "RACK-204"
        const val CONTAINER = "HU-1042"
        const val PART = "ENGINE-COVER-A"
    }
}

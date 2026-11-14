package dev.picasso.middleware

import dev.picasso.harness.Harness
import dev.picasso.middleware.mission.Activation
import dev.picasso.middleware.mission.InMemoryMissionCatalog
import dev.picasso.middleware.mission.MissionFixtures
import dev.picasso.middleware.mission.MissionRefusalKind
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 임무 버전 — **도는 실행은 옛 버전으로 끝나고 새 작업 지시만 새 버전으로 돈다**(운영 관리 화면 설계 제안 §10 의 3·4).
 *
 * 버전 1 은 코드 `PrepareSequencedRack` 을 데이터로 옮긴 것이고, 버전 2 는 그 앞에 랙 도착 대기를 둔 것이다. 버전 3 은
 * 신호 사양에 없는 신호를 참조한 잘못된 변경이다.
 *
 * 같은 기체에 두 실행을 겹치지 않는다 — 버전 1 의 실행은 [ROBOT_A] 와 랙 301, 버전 2 의 실행은 [ROBOT_B] 와 랙 302 다.
 * 슬롯 점유도 안 겹친다.
 */
class MissionVersionScenarioTest {

    private class World : AutoCloseable {
        val harness = Harness(
            mapOf(
                ROBOT_A to Path.of("..", "profile", "fixtures", "minimal.json").normalize(),
                ROBOT_B to Path.of("..", "profile", "fixtures", "minimal.json").normalize(),
            ),
        )
        val cell = CellMimic(now = { harness.clock.now() })
        val catalog = InMemoryMissionCatalog(now = { harness.clock.now() })
        val mw = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() }, missions = catalog)

        fun activate(text: String) = catalog.activate(text, MissionFixtures.SIGNALS, FloorOwnership.None, MissionFixtures.SITE_SKILLS)

        fun tasks(robot: String) =
            harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(robot).build()).tasksList

        fun drive(rounds: Int = 60, step: Duration = Duration.ofSeconds(30), until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(step)
                Thread.sleep(40)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다: A=${tasks(ROBOT_A).map { it.taskId to it.taskState }} B=${tasks(ROBOT_B).map { it.taskId to it.taskState }}")
        }

        override fun close() = harness.close()
    }

    /** 랙 하나, 슬롯 둘(A형·B형), 제시 자리 둘. [wrongAt] 슬롯에는 셀 장치가 다른 부품을 본다. */
    private fun World.order(rack: String, version: Int = 1, wrongAt: String? = null): JobOrder {
        val order = JobOrder(
            jobOrderId = "SEQ-$rack",
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = version,
            requiredEvidence = Evidence.E2,
            materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", 1), MaterialRequirement("ENGINE-COVER-B", 1)),
            equipmentRequirements = listOf(
                EquipmentRequirement("RACK-$rack.S01", EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to "ENGINE-COVER-A")),
                EquipmentRequirement("RACK-$rack.S02", EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to "ENGINE-COVER-B")),
                EquipmentRequirement("BIN-$rack-A", EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to "ENGINE-COVER-A")),
                EquipmentRequirement("BIN-$rack-B", EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to "ENGINE-COVER-B")),
            ),
        )
        order.equipmentRequirements.filter { it.equipmentUse == EquipmentUse.DESTINATION }.forEach {
            cell.program(it.id, if (it.id == wrongAt) "ENGINE-COVER-X" else it.properties[EquipmentUse.PROP_MATERIAL])
        }
        return order
    }

    private fun Middleware.Execution.settled() = physicalState.isSettled && active == null

    @Test
    fun `버전 1 실행 중 버전 2 를 활성화하면 새 작업 지시만 버전 2 로 대기를 거치고 옛 실행은 버전 1 로 끝난다`() {
        World().use { w ->
            assertEquals(Activation.Activated(PrepareSequencedRack.WORK_MASTER, 1), w.activate(MissionFixtures.PREPARE_SEQUENCED_RACK))

            // 버전 1 — 둘째 슬롯에서 셀 장치가 다른 부품을 본다. 그 인시던트는 버전 2 가 선 **뒤에** 난다.
            val first = assertIs<Middleware.Submission.Accepted>(w.mw.submit(w.order("301", wrongAt = "RACK-301.S02"), ROBOT_A)).execution
            assertEquals(1, first.missionVersion)
            w.drive { "RACK-301.S01" in first.completedUnits }
            assertTrue(w.mw.incidents().isEmpty(), "버전 2 가 서기 전에 인시던트가 났다 — 시나리오가 아무것도 안 가린다")

            // 버전 2 가 선다 — 맨 앞에 랙 도착 대기. 기한은 넉넉히 둔다(옛 실행이 끝날 때까지 기다리게 한다).
            assertEquals(
                Activation.Activated(PrepareSequencedRack.WORK_MASTER, 2),
                w.activate(MissionFixtures.withArrivalWait(deadlineSeconds = 3600)),
            )

            // 버전 3 — 신호 사양에 없는 신호. 거부되고 활성 버전은 2 로 남는다. 거부는 종류와 후속 행동으로 보인다.
            val refused = assertIs<Activation.Refused>(w.activate(MissionFixtures.withArrivalWait(signal = "rack_ready")))
            val refusal = refused.refusals.single()
            assertEquals(MissionRefusalKind.SIGNAL_NOT_IN_SPEC, refusal.kind)
            assertEquals(MissionFixtures.WAIT_NODE, refusal.nodeId)
            assertEquals("신호 이름을 고치거나 신호 사양에 더한다", refusal.nextAction)
            assertEquals(2, w.catalog.active(PrepareSequencedRack.WORK_MASTER)!!.missionVersion)

            // 새 작업 지시는 버전 2 — 다른 기체, 다른 랙.
            val second = assertIs<Middleware.Submission.Accepted>(w.mw.submit(w.order("302"), ROBOT_B)).execution
            assertEquals(2, second.missionVersion)
            assertEquals(Route.SIGNAL, second.units.first().route)

            // 랙이 도착하기 전에는 버전 2 의 로봇이 안 움직인다. 옛 실행은 그동안 끝난다.
            w.drive { first.settled() }
            assertEquals(0, w.tasks(ROBOT_B).size, "대기 전에 버전 2 의 로봇이 움직였다")

            // 옛 실행의 리비전은 **버전 1 로** 계획한다 — 대기 단위가 들어오지 않는다. 부분 완료는 리비전으로 다시 열리므로
            // 카탈로그로 계획하면 새 버전의 대기 단위가 «새 단위» 로 붙는다.
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(w.order("301", version = 2, wrongAt = "RACK-301.S02"), ROBOT_A))
            assertEquals(listOf("RACK-301.S01", "RACK-301.S02"), first.units.map { it.unitId }, "리비전이 새 버전의 단위를 들였다")
            assertEquals(1, first.missionVersion)

            w.cell.setSignal(MissionFixtures.RACK_PRESENT, "true")
            w.drive { second.settled() && first.settled() }

            // 옛 실행은 버전 1 로 끝났다 — 대기 없이, 둘째 슬롯은 불일치.
            assertEquals(PhysicalState.PARTIAL, first.physicalState)
            assertTrue(first.units.none { it.route == Route.SIGNAL })
            assertEquals(Middleware.MISMATCH, first.units.single { it.unitId == "RACK-301.S02" }.failureClass)

            // 새 실행은 버전 2 로 끝났다 — 대기를 거쳤다.
            assertEquals(PhysicalState.PHYSICALLY_DONE, second.physicalState)
            assertEquals(listOf(MissionFixtures.WAIT_NODE, "RACK-302.S01", "RACK-302.S02"), second.completedUnits)

            // ★인시던트는 그 실행의 버전을 싣는다 — 활성 버전(2)이 아니다.
            val incident = w.mw.incidents().single()
            assertEquals(first.executionId, incident.executionId)
            assertEquals(1, incident.intent.missionVersion, "인시던트가 실행이 쥔 버전이 아닌 것을 실었다")
        }
    }

    @Test
    fun `버전 2 의 대기 기한 인시던트가 그 버전을 싣고 해시에 들며 내보내기에도 실린다`() {
        World().use { w ->
            w.activate(MissionFixtures.PREPARE_SEQUENCED_RACK)
            w.activate(MissionFixtures.withArrivalWait(deadlineSeconds = 60))
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(w.order("303"), ROBOT_B)).execution
            w.drive(step = Duration.ofSeconds(20)) { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            // 기한 뒤에 버전이 또 올라도 인시던트는 그 실행의 버전이다.
            assertEquals(Activation.Activated(PrepareSequencedRack.WORK_MASTER, 3), w.activate(MissionFixtures.withArrivalWait(deadlineSeconds = 30)))
            val incident = w.mw.incidents().single()
            assertEquals(WaitSpec.SIGNAL_DEADLINE, incident.failureClass)
            assertEquals("SIGNAL", incident.route)
            assertEquals(2, incident.intent.missionVersion)

            // 해시에 든다 — 다른 버전으로 펼친 같은 모양의 인시던트는 다른 인시던트다.
            val other = incident.copy(intent = incident.intent.copy(missionVersion = 3))
            assertTrue(incident.digest() != other.digest(), "임무 버전이 해시에 안 들어갔다")
            assertTrue(incident.digest() != incident.copy(intent = incident.intent.copy(missionVersion = null)).digest())

            // 판 8 부터 의도의 칸으로 실린다(§15.322) — 지금 활성인 3 이 아니라 실행이 쥔 2 다. 경로의 새 하위 범주도 나간다.
            val line = LedgerExport.incidents(listOf(incident))
            assertTrue("\"missionVersion\":2" in line, line)
            assertTrue("\"route\":\"SIGNAL\"" in line, line)
        }
    }

    @Test
    fun `카탈로그를 안 주면 코드 케이퍼빌리티가 버전 없이 돈다`() {
        World().use { w ->
            val plain = Middleware(ClientRobotPort(w.harness.client()), w.cell, now = { w.harness.clock.now() })
            val exec = assertIs<Middleware.Submission.Accepted>(plain.submit(w.order("304", wrongAt = "RACK-304.S01"), ROBOT_A)).execution
            assertNull(exec.missionVersion)
            assertIs<PrepareSequencedRack>(exec.capability)
            for (round in 1..20) {
                plain.pump()
                if (plain.incidents().isNotEmpty()) break
                w.harness.advance(Duration.ofSeconds(30))
                Thread.sleep(40)
            }
            assertNull(plain.incidents().first().intent.missionVersion)
        }
    }

    @Test
    fun `배정은 카탈로그를 한 번 읽은 버전으로 실행을 세운다`() {
        World().use { w ->
            w.activate(MissionFixtures.withArrivalWait())
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.adopt(w.order("305"), listOf(ROBOT_A))).execution
            assertEquals(1, exec.missionVersion)
            assertEquals(Route.SIGNAL, exec.units.first().route)
        }
    }

    @Test
    fun `배정 사이에 활성화가 끼어도 실행은 관문에 댄 버전을 쥔다`() {
        // ★한 스레드의 시험에서는 배정이 카탈로그를 두 번 읽어도 같은 답이 온다 — 그래서 두 번째 읽기부터 다음 버전을
        //   주는 카탈로그로 «관문을 본 뒤, 작업 수락 전» 의 활성화를 흉내 낸다.
        World().use { w ->
            w.activate(MissionFixtures.PREPARE_SEQUENCED_RACK)
            val v1 = w.catalog.active(PrepareSequencedRack.WORK_MASTER)!!
            w.activate(MissionFixtures.withArrivalWait())
            val v2 = w.catalog.active(PrepareSequencedRack.WORK_MASTER)!!
            val flipping = object : MissionCatalog {
                var reads = 0
                override fun active(workMasterId: String): ActiveMission = if (reads++ == 0) v1 else v2
            }
            val mw = Middleware(ClientRobotPort(w.harness.client()), w.cell, now = { w.harness.clock.now() }, missions = flipping)

            val exec = assertIs<Middleware.Submission.Accepted>(mw.adopt(w.order("306"), listOf(ROBOT_A))).execution
            assertEquals(1, exec.missionVersion, "관문은 버전 1 로 봤는데 실행은 다른 버전을 쥐었다")
            assertTrue(exec.units.none { it.route == Route.SIGNAL }, "관문이 본 적 없는 단위가 실행에 들었다")
            assertEquals(1, flipping.reads, "배정이 카탈로그를 두 번 읽었다")
        }
    }

    @Test
    fun `리비전이 WorkMaster 를 바꾸면 거부하고 실행은 그대로다`() {
        // 리비전은 실행이 쥔 케이퍼빌리티로 계획한다. 다른 WorkMaster 의 작업 지시를 그것으로 펼치면 다른 일을 옛
        // 정의로 돌리게 된다 — 새 작업 지시다. 요구 등급을 E0 로 낮춰 근거 등급 검사가 대신 거부하지 않게 한다.
        World().use { w ->
            w.activate(MissionFixtures.PREPARE_SEQUENCED_RACK)
            val order = w.order("307")
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order, ROBOT_A)).execution
            val planned = exec.units.map { it.unitId }

            val switched = order.copy(workMasterId = InspectAsset.WORK_MASTER, version = 2, requiredEvidence = Evidence.E0)
            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(switched, ROBOT_A))
            assertTrue("WorkMaster" in refused.reason, refused.reason)

            assertEquals(PrepareSequencedRack.WORK_MASTER, exec.order.workMasterId, "거부된 리비전이 작업 지시를 바꿨다")
            assertEquals(1, exec.version)
            assertEquals(planned, exec.units.map { it.unitId })
            assertEquals(1, exec.missionVersion)
        }
    }

    // ── 단위 id 겹침(§15.227)

    /** 첫 슬롯의 id 를 대기 노드 id 로 바꾼 작업 지시. 대기 단위의 id 는 노드 id, 슬롯 단위의 id 는 설비 id 라 둘이 겹친다. */
    private fun JobOrder.withSlotNamedAsWaitNode(): JobOrder = copy(
        equipmentRequirements = equipmentRequirements.map {
            if (it.id.endsWith(".S01")) it.copy(id = MissionFixtures.WAIT_NODE) else it
        },
    )

    @Test
    fun `작업 지시의 설비 id 가 대기 노드 id 와 같으면 받지 않는다`() {
        // ★검증기는 설비 id 를 모른다. 받으면 리비전·인시던트·작업 응답이 단위 id 로 접어 한쪽이 가려진다.
        World().use { w ->
            w.activate(MissionFixtures.withArrivalWait())
            val refused = assertIs<Middleware.Submission.Rejected>(
                w.mw.submit(w.order("308").withSlotNamedAsWaitNode(), ROBOT_A),
            )
            assertTrue(refused.reason.startsWith(Middleware.DUPLICATE_UNIT_ID), refused.reason)
            assertTrue(MissionFixtures.WAIT_NODE in refused.reason, refused.reason)
            assertTrue(w.mw.executions().isEmpty(), "겹친 단위 id 로 실행이 섰다")
        }
    }

    @Test
    fun `리비전이 대기 노드 id 와 같은 설비 id 를 들이면 받지 않고 실행은 그대로다`() {
        // 리비전은 새 계획을 단위 id 로 접는다 — 겹치면 대기 단위가 슬롯 단위로 바뀌어 대기가 사라진다.
        World().use { w ->
            w.activate(MissionFixtures.withArrivalWait())
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(w.order("309"), ROBOT_A)).execution
            val planned = exec.units.map { it.unitId to it.route }

            val refused = assertIs<Middleware.Submission.Rejected>(
                w.mw.submit(w.order("309", version = 2).withSlotNamedAsWaitNode(), ROBOT_A),
            )
            assertTrue(refused.reason.startsWith(Middleware.DUPLICATE_UNIT_ID), refused.reason)
            assertEquals(1, exec.version)
            assertEquals(planned, exec.units.map { it.unitId to it.route }, "거부된 리비전이 단위를 바꿨다")
        }
    }

    private companion object {
        const val ROBOT_A = "hum-02"
        const val ROBOT_B = "hum-03"
    }
}

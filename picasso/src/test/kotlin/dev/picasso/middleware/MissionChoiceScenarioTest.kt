package dev.picasso.middleware

import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.harness.Harness
import dev.picasso.middleware.mission.Activation
import dev.picasso.middleware.mission.InMemoryMissionCatalog
import dev.picasso.middleware.mission.MissionFixtures
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 접수 시점 분기(§15.328)를 **엔진 위에서** — 데이터 정의의 `choice` 로 접수하면 고른 갈래의 단위만 나가고, 고른 갈래가 트레이스 한
 * 줄로 남으며, 리비전은 고른 갈래를 바꾸지 못하고, 리빌드는 같은 계획을 다시 세우고, 재작업은 갈래를 다시 고르지 않는다. 배정
 * 관문은 고른 갈래의 단위만 본다.
 *
 * 순회는 [MissionFixtures.INSPECT_BY_ITEM] — 대상마다 이동 뒤 `item` 으로 열 점검(`<대상>`)·눈 점검(`<대상>.look`)·점검 없음을 고른다.
 * 기체는 [InspectAssetTest] 와 같은 4족 픽스처다.
 */
class MissionChoiceScenarioTest {

    private class World(port: (RobotPort) -> RobotPort = { it }, document: String = MissionFixtures.INSPECT_BY_ITEM) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()))
        val catalog = InMemoryMissionCatalog(now = { harness.clock.now() })
        val mw = Middleware(port(ClientRobotPort(harness.client())), now = { harness.clock.now() }, missions = catalog)

        init {
            val activated = catalog.activate(document, MissionFixtures.SIGNALS, FloorOwnership.None, MissionFixtures.SITE_SKILLS)
            assertIs<Activation.Activated>(activated, "$activated")
        }

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList

        fun drive(mw: Middleware = this.mw, rounds: Int = 120, until: () -> Boolean) {
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

        override fun close() = harness.close()
    }

    private fun target(id: String, item: String?, located: Boolean = true) = EquipmentRequirement(
        id, EquipmentUse.INSPECTION_TARGET,
        listOfNotNull(("$id-SPOT").takeIf { located }?.let { EquipmentUse.PROP_LOCATION to it }, item?.let { EquipmentUse.PROP_ITEM to it }).toMap(),
    )

    private fun order(
        version: Int = 1,
        shift: String = "night",
        items: List<Pair<String, String?>> = DEFAULT,
        unlocated: Set<String> = emptySet(),
    ) = JobOrder(
        jobOrderId = JOB,
        workMasterId = WORK_MASTER,
        version = version,
        parameters = mapOf("shift" to shift),
        equipmentRequirements = items.map { (id, item) -> target(id, item, located = id !in unlocated) },
    )

    private fun Middleware.Execution.settled() = physicalState.isSettled && active == null
    private fun Middleware.Execution.ids() = units.map { it.unitId }
    private fun taskOf(unitId: String) = "$JOB#$unitId"

    @Test
    fun `데이터 정의의 분기로 접수하면 고른 갈래의 단위만 나가고 고른 갈래가 트레이스 한 줄로 남는다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            assertEquals(PLANNED, exec.ids(), "고르지 않은 갈래의 단위가 계획에 들었다")
            w.drive { exec.settled() }

            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState, "고르지 않은 갈래가 실패로 세어졌다")
            assertEquals(PLANNED.map(::taskOf), w.tasks().map { it.taskId }, "고른 갈래의 단위만 차례로 나가야 한다")
            val chosen = exec.eventTrail.filter { it.kind == MiddlewareCodes.BRANCH_CHOSEN }
            assertEquals(
                listOf("how[PUMP-01]=arms[0], how[PANEL-3]=arms[1], how[COMP-2]=otherwise; order revision 1; mission version 1"),
                chosen.map { it.detail },
            )
        }
    }

    @Test
    fun `고른 갈래가 같은 리비전은 받고 갈래를 바꾸는 리비전은 거절하며 실행은 그대로다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution

            // 갈래가 같다 — 정의가 읽지 않는 작업 지시 파라미터만 바뀐다.
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 2, shift = "day"), ROBOT))
            assertEquals(2, exec.version)
            assertEquals(PLANNED, exec.ids())

            // PANEL-3 이 visual 에서 thermal 로 — 눈 점검에서 열 점검으로 갈래가 바뀐다.
            val switched = order(version = 3, items = listOf("PUMP-01" to "thermal", "PANEL-3" to "thermal", "COMP-2" to "acoustic"))
            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(switched, ROBOT))
            assertEquals("리비전이 분기의 가지를 바꾼다: how[PANEL-3] 받은 값=arms[0], 현재=arms[1] — 새 작업 지시로 낸다", refused.reason)
            assertEquals(2, exec.version, "거절한 리비전이 판을 올렸다")
            assertEquals(PLANNED, exec.ids(), "거절한 리비전이 단위를 바꿨다")
        }
    }

    @Test
    fun `값이 없어 접힌 분기에 값이 생기는 리비전도 갈래를 바꾸는 것이라 거절한다`() {
        World().use { w ->
            val absent = order(items = listOf("PUMP-01" to "thermal", "PANEL-3" to null))
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(absent, ROBOT)).execution
            val folded = exec.units.single { it.unitId == "PANEL-3" }
            assertEquals(UnitState.FAILED to "NO_ITEM_FOR_TARGET", folded.state to folded.failureClass)

            val filled = order(version = 2, items = listOf("PUMP-01" to "thermal", "PANEL-3" to "visual"))
            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(filled, ROBOT))
            assertTrue("how[PANEL-3] 받은 값=arms[1], 현재=whenMissing" in refused.reason, refused.reason)
            assertEquals(listOf("PUMP-01.travel", "PUMP-01", "PANEL-3.travel", "PANEL-3"), exec.ids())

            // 부재가 그대로인 리비전은 갈래가 같다.
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(absent.copy(version = 2, parameters = mapOf("shift" to "day")), ROBOT))
        }
    }

    @Test
    fun `재기동 뒤 리빌드는 같은 계획과 같은 갈래를 다시 세운다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.mw.pump()
            val record = w.mw.judgmentRecord(exec.executionId)

            val again = Middleware(ClientRobotPort(w.harness.client()), now = { w.harness.clock.now() })
            val mission = assertIs<ActiveMission>(w.catalog.active(WORK_MASTER))
            val rebuilt = assertIs<Middleware.Submission.Accepted>(again.resume(order(), ROBOT, mission, record)).execution
            assertEquals(exec.units.map { it.unitId to it.after }, rebuilt.units.map { it.unitId to it.after }, "리빌드가 다른 갈래를 펼쳤다")
            assertEquals(
                exec.eventTrail.single { it.kind == MiddlewareCodes.BRANCH_CHOSEN }.detail,
                rebuilt.eventTrail.single { it.kind == MiddlewareCodes.BRANCH_CHOSEN }.detail,
            )
            w.drive(again) { rebuilt.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, rebuilt.physicalState)
        }
    }

    @Test
    fun `재작업은 갈래를 다시 고르지 않는다 — 건너뛴 열 점검이 같은 갈래로 새 시도를 낸다`() {
        val travel = "PUMP-01.travel"
        World(port = { FailOnce(it, taskOf(travel)) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PARTIAL, exec.physicalState)
            assertEquals(MiddlewareCodes.PREDECESSOR_NOT_DONE, exec.units.single { it.unitId == "PUMP-01" }.failureClass)

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, "PUMP-01", OperatorDecision.REWORK, OPERATOR))
            assertEquals(PLANNED, exec.ids(), "재작업이 갈래를 다시 골라 단위를 바꿨다")
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(listOf("${taskOf(travel)}@r1", "${taskOf("PUMP-01")}@r1"), w.tasks().map { it.taskId }.takeLast(2))
            assertEquals(1, exec.eventTrail.count { it.kind == MiddlewareCodes.BRANCH_CHOSEN }, "재작업이 갈래를 다시 적었다")
        }
    }

    @Test
    fun `배정 관문은 고른 갈래만 본다 — 고르지 않은 갈래의 목적지 관측 요구는 접수를 막지 않는다`() {
        val harness = Harness(mapOf(FILL_ROBOT to Path.of("..", "profile", "fixtures", "precondition.json").normalize()))
        harness.use {
            val cell = CellMimic(now = { harness.clock.now() })
            val catalog = InMemoryMissionCatalog(now = { harness.clock.now() })
            assertIs<Activation.Activated>(catalog.activate(MissionFixtures.FILL_BY_LANE, MissionFixtures.SIGNALS, FloorOwnership.None, MissionFixtures.SITE_SKILLS))
            val mw = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() }, missions = catalog)
            cell.program(BIN, MATERIAL)
            cell.program(SLOT, OTHER)
            val fill = { job: String, lane: String ->
                JobOrder(
                    jobOrderId = job, workMasterId = PrepareSequencedRack.WORK_MASTER, version = 1, parameters = mapOf("lane" to lane),
                    materialRequirements = listOf(MaterialRequirement(MATERIAL, 1)),
                    equipmentRequirements = listOf(EquipmentRequirement(SLOT, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to MATERIAL, "bin" to BIN))),
                )
            }

            // 목적지 관측을 요구한 갈래를 고르면 차 있는 슬롯이 막는다 — 이 시험이 보는 막음이 실제로 선다.
            val checked = assertIs<Middleware.Submission.Rejected>(mw.submit(fill("FILL-1", "check"), FILL_ROBOT))
            assertTrue(SLOT in checked.reason && "목적지 점유" in checked.reason, checked.reason)
            // 같은 정의로 다른 갈래를 고르면 그 갈래의 단위만 관문에 간다.
            val main = assertIs<Middleware.Submission.Accepted>(mw.submit(fill("FILL-2", "main"), FILL_ROBOT)).execution
            assertEquals(listOf(SLOT), main.ids(), "고르지 않은 갈래의 단위가 계획에 들었다")
        }
    }

    @Test
    fun `분기 자리를 설비를 빼서 없애는 리비전은 거부해 두 걸음으로 가지를 바꾸지 못한다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution

            // 걸음 하나 — PUMP-01 을 뺀다. 그 자리의 열 점검이 아직 나가지 않았다.
            val dropped = order(version = 2, items = listOf("PANEL-3" to "visual", "COMP-2" to "acoustic"))
            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(dropped, ROBOT))
            assertEquals("리비전이 분기 자리를 없앤다: how[PUMP-01] — 분기가 든 실행의 리비전은 자리를 더하기만 한다. 새 작업 지시로 낸다", refused.reason)
            assertEquals(1 to PLANNED, exec.version to exec.ids(), "거부한 리비전이 실행을 바꿨다")

            // 걸음 둘 — 같은 id 를 다른 가지로 다시 넣어도 옛 선택과 맞대므로 거부한다.
            val back = order(version = 3, items = listOf("PANEL-3" to "visual", "COMP-2" to "acoustic", "PUMP-01" to "visual"))
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(back, ROBOT))
            assertEquals(PLANNED, exec.ids())
        }
    }

    @Test
    fun `분기 자리를 그룹 접힘으로 없애는 리비전도 거부한다`() {
        val located = MissionFixtures.INSPECT_BY_ITEM.replace(
            "\"forEach\": \"inspection_target\",",
            "\"forEach\": \"inspection_target\",\n\"requires\": [ { \"from\": \"ITEM_PROPERTY\", \"property\": \"location\" } ],\n" +
                "\"whenMissing\": { \"failureClass\": \"NO_LOCATION\", \"unit\": \"travel\", \"parameters\": [] },",
        )
        World(document = located).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            assertEquals(PLANNED, exec.ids())
            val folded = assertIs<Middleware.Submission.Rejected>(w.mw.submit(order(version = 2, unlocated = setOf("PANEL-3")), ROBOT))
            assertTrue(folded.reason.startsWith("리비전이 분기 자리를 없앤다: how[PANEL-3] —"), folded.reason)
            assertEquals(1, exec.version)
        }
    }

    @Test
    fun `끝난 분기 자리도 뺄 수 없고 자리를 더하기만 하는 리비전은 받아 더한 자리의 가지를 트레이스 한 줄로 남긴다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { "PUMP-01" in exec.completedUnits }

            // 끝난 자리라도 빼면 거부한다 — 받으면 리빌드의 다시 계획에 그 완료 단위가 없어 실행을 다시 짓지 못한다.
            val dropped = order(version = 2, items = listOf("PANEL-3" to "visual", "COMP-2" to "acoustic"))
            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(dropped, ROBOT))
            assertTrue(refused.reason.startsWith("리비전이 분기 자리를 없앤다: how[PUMP-01] —"), refused.reason)

            val added = order(version = 2, items = DEFAULT + ("FAN-4" to "visual"))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(added, ROBOT))
            assertEquals(2, exec.version)
            assertTrue("FAN-4.look" in exec.ids(), "더한 설비의 가지 단위가 안 들었다")
            assertEquals(
                listOf(
                    "how[PUMP-01]=arms[0], how[PANEL-3]=arms[1], how[COMP-2]=otherwise; order revision 1; mission version 1",
                    "how[FAN-4]=arms[1]; order revision 2; mission version 1",
                ),
                exec.eventTrail.filter { it.kind == MiddlewareCodes.BRANCH_CHOSEN }.map { it.detail },
            )
        }
    }

    @Test
    fun `자리를 더한 리비전 뒤 판단 기록으로 리빌드하면 최신 작업 지시로 같은 실행을 다시 짓는다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { "PUMP-01" in exec.completedUnits }
            val added = order(version = 2, items = DEFAULT + ("FAN-4" to "visual"))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(added, ROBOT))
            val record = w.mw.judgmentRecord(exec.executionId)

            val again = Middleware(ClientRobotPort(w.harness.client()), now = { w.harness.clock.now() })
            val rebuilt = again.resume(added, ROBOT, assertIs<ActiveMission>(w.catalog.active(WORK_MASTER)), record)
            val accepted = assertIs<Middleware.Submission.Accepted>(rebuilt, "리빌드가 거부됐다: $rebuilt").execution
            assertEquals(exec.ids(), accepted.ids())
        }
    }

    @Test
    fun `가지는 같아도 분기 자리의 단위 id 를 바꾸는 리비전은 거부한다`() {
        // 열 점검의 단위 id 가 대상의 tag 속성에서 온다(없으면 대상 id). tag 를 바꾸면 같은 가지가 다른 id 의 단위를 낸다 — 받으면 옛 단위가 남는다.
        val tagged = MissionFixtures.INSPECT_BY_ITEM.replace(
            "\"id\": \"thermal\",\n\"skill\": \"inspect\",\n\"after\": \"travel\",\n\"unitId\": { \"from\": \"ITEM_ID\" },",
            "\"id\": \"thermal\",\n\"skill\": \"inspect\",\n\"after\": \"travel\",\n\"unitId\": { \"from\": \"ITEM_PROPERTY\", \"property\": \"tag\", \"else\": { \"from\": \"ITEM_ID\" } },",
        )
        World(document = tagged).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            assertEquals(PLANNED, exec.ids())
            val retag = order(version = 2).let { o ->
                o.copy(equipmentRequirements = o.equipmentRequirements.map { if (it.id == "PUMP-01") it.copy(properties = it.properties + ("tag" to "B-TAG")) else it })
            }
            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(retag, ROBOT))
            assertEquals("리비전이 분기 자리의 단위 id 를 바꾼다: how[PUMP-01] 받은 값=[B-TAG], 현재=[PUMP-01] — 새 작업 지시로 낸다", refused.reason)
            assertEquals(1 to PLANNED, exec.version to exec.ids())
        }
    }

    @Test
    fun `분기가 든 그룹의 쓰임에 같은 설비 id 가 둘이면 작업 수락을 거부한다`() {
        // 이동 노드를 뺀 정의 — 이동이 있으면 이동 단위 id 가 먼저 겹쳐(DUPLICATE_UNIT_ID) 이 거부를 가린다. 가지가 갈리면 단위 id 는 안 겹친다.
        val choiceOnly = MissionFixtures.INSPECT_BY_ITEM.substringBefore("{\n\"kind\": \"unit\",\n\"id\": \"travel\"") +
            MissionFixtures.INSPECT_BY_ITEM.substringAfter("},\n{\n\"kind\": \"choice\"").let { "{\n\"kind\": \"choice\"$it" }.replace("\"after\": \"travel\",\n", "")
        World(document = choiceOnly).use { w ->
            val clash = order(items = listOf("PUMP-01" to "thermal", "PUMP-01" to "acoustic"))
            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(clash, ROBOT))
            assertTrue(refused.reason.startsWith("분기 자리가 겹쳐 받지 않는다: how[PUMP-01]"), refused.reason)
            assertTrue(w.mw.executions().isEmpty(), "겹친 분기 자리로 실행이 섰다")
        }
    }

    /** 정해 둔 태스크의 첫 성공만 결함 없는 실패로 바꾼다 — `@r1` 재시도는 그대로 성공한다([InspectAssetTest] 의 대역과 같은 모양). */
    private class FailOnce(private val delegate: RobotPort, private val failing: String) : RobotPort by delegate {
        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> =
            delegate.watch(robotId, handle).map {
                if (handle.taskId == failing && it.state == TaskState.TASK_STATE_SUCCEEDED) it.toBuilder().setState(TaskState.TASK_STATE_FAILED).clearFault().build() else it
            }
    }

    private companion object {
        const val ROBOT = "quad-01"
        const val JOB = "PATROL-9"
        const val WORK_MASTER = "InspectByItem"
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)
        val DEFAULT = listOf<Pair<String, String?>>("PUMP-01" to "thermal", "PANEL-3" to "visual", "COMP-2" to "acoustic")
        const val FILL_ROBOT = "hum-02"
        const val SLOT = "RACK-204.S01"
        const val BIN = "SEQ-IN-02.BIN-A"
        const val MATERIAL = "ENGINE-COVER-A"
        const val OTHER = "ENGINE-COVER-B"
        val PLANNED = listOf("PUMP-01.travel", "PUMP-01", "PANEL-3.travel", "PANEL-3.look", "COMP-2.travel")
    }
}

package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 재할당과 진동 방지(설계안 §7).
 *
 * **라인을 멈추지 않는 것이 최적성보다 우선한다.** 더 싼 기체가 보일 때마다 옮기면 비용이 1 흔들릴 때마다
 * 두 기체 사이를 왕복하고, 그동안 아무 일도 진행되지 않는다. 막는 장치가 셋이다 — 도는 단위, 최소 유지
 * 시간, 이득의 문턱.
 */
class ReassignmentTest {

    private class World(
        policy: ReassignPolicy = ReassignPolicy(margin = 2, minHold = Duration.ofSeconds(30)),
        workspace: Workspace = Workspace.None,
        profiles: Map<String, Path> = mapOf(A to PRECOND, B to PRECOND, C to PRECOND),
        port: (RobotPort) -> RobotPort = { it },
    ) : AutoCloseable {
        val harness = Harness(profiles)
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(
            port(ClientRobotPort(harness.client())),
            cell,
            now = { harness.clock.now() },
            reassignPolicy = policy,
            workspace = workspace,
        )

        /** 그 기체에 부담을 n 만큼 준다 — 살아 있는 실행 수가 곧 부담이다. */
        fun load(robotId: String, n: Int) = List(n) {
            assertIs<Middleware.Submission.Accepted>(mw.submit(patrol("LOAD-$robotId-$it"), robotId)).execution
        }

        fun place(robotId: String, jobOrderId: String = "JOB-1", location: String = PUMP_ROOM) =
            assertIs<Middleware.Submission.Accepted>(mw.submit(patrol(jobOrderId, location), robotId)).execution

        /**
         * 자재를 놓는 실행 하나 — 슬롯마다 목적지와 기대 자재를 들어 **자리를 잡는다.** 출발 자리에는 자재를 둔다
         * (없으면 관문이 결품으로 막는다). 펌프하지 않으므로 아직 아무 단위도 안 돈다.
         */
        fun carry(robotId: String, jobOrderId: String, slots: List<String>, bin: String): Middleware.Execution {
            cell.program(bin, MATERIAL)
            val order = JobOrder(
                jobOrderId = jobOrderId,
                workMasterId = PrepareSequencedRack.WORK_MASTER,
                version = 1,
                requiredEvidence = Evidence.E0,
                materialRequirements = listOf(MaterialRequirement(MATERIAL, slots.size)),
                equipmentRequirements = slots.map {
                    EquipmentRequirement(it, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to MATERIAL))
                } + EquipmentRequirement(bin, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to MATERIAL)),
            )
            return assertIs<Middleware.Submission.Accepted>(mw.submit(order, robotId)).execution
        }

        fun forceFault(robotId: String, errorType: String, taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(robotId).setErrorType(errorType).setTaskId(taskId).build(),
        ).taskState

        /** 기체 쪽에서 본 태스크 상태. 결함은 기체가 RUNNING 이라 말하는 태스크에만 걸린다. */
        fun taskState(robotId: String, taskId: String) = harness.oracle.dumpInternalState(
            DumpInternalStateRequest.newBuilder().setRobotId(robotId).build(),
        ).tasksList.firstOrNull { it.taskId == taskId }?.taskState

        fun drive(rounds: Int = 200, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(1))
                Thread.sleep(40)
            }
            mw.pump()
            if (until()) return
            error("조건에 못 미쳤다")
        }

        /** 그 기체를 든 채로 만든다 — 사슬 검사가 막을 상태다. */
        fun makeHolding(robotId: String) {
            val slot = "RACK.$robotId"
            val bin = "BIN.$robotId"
            cell.program(slot, MATERIAL)
            cell.program(bin, MATERIAL)
            val order = JobOrder(
                jobOrderId = "SEQ-$robotId",
                workMasterId = PrepareSequencedRack.WORK_MASTER,
                version = 1,
                requiredEvidence = Evidence.E2,
                materialRequirements = listOf(MaterialRequirement(MATERIAL, 1)),
                equipmentRequirements = listOf(
                    EquipmentRequirement(slot, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to MATERIAL)),
                    EquipmentRequirement(bin, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to MATERIAL)),
                ),
            )
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order, robotId)).execution
            repeat(60) {
                mw.pump()
                if (exec.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING) return
                harness.advance(Duration.ofSeconds(1))
                Thread.sleep(40)
            }
            error("파지 상태에 못 이르렀다")
        }

        /** 최소 유지 시간을 넘긴다 — 가상 시계라 기다리지 않는다. */
        fun age() = harness.advance(Duration.ofMinutes(5))

        override fun close() = harness.close()
    }

    // ── 막는 장치 셋

    @Test
    fun `최소 유지 시간 안에는 안 옮긴다`() {
        // 옮긴 직후 되돌아오는 것을 막는 장치다. 문턱만으로는 부족하다 — 일이 떠난 쪽의 부담이 줄어
        // 반대 방향의 이득이 곧바로 생긴다.
        World().use { w ->
            val exec = w.place(A)
            w.load(A, 5)

            val kept = assertIs<Reassignment.Kept>(w.mw.reassign(exec.executionId, listOf(B)))
            assertTrue("최소 유지 시간" in kept.reason, kept.reason)
            assertEquals(A, exec.robotId)
        }
    }

    @Test
    fun `이득이 문턱 이하면 안 옮긴다`() {
        // ★경계다. 같으면 그대로 둔다 — 동점에서 움직이는 것이 진동의 시작이다.
        World().use { w ->
            val exec = w.place(A)
            w.load(A, 2) // 이 일을 뺀 A 의 부담 = 2, B = 0. 이득 2 = 문턱
            w.age()

            val kept = assertIs<Reassignment.Kept>(w.mw.reassign(exec.executionId, listOf(B)))
            assertTrue("문턱" in kept.reason, kept.reason)
            assertEquals(A, exec.robotId)
        }
    }

    @Test
    fun `이득이 문턱을 넘으면 옮긴다`() {
        World().use { w ->
            val exec = w.place(A)
            w.load(A, 3)
            w.age()

            val moved = assertIs<Reassignment.Moved>(w.mw.reassign(exec.executionId, listOf(B)))
            assertEquals(A, moved.from)
            assertEquals(B, moved.to)
            assertEquals(3, moved.saved)
            assertEquals(B, exec.robotId)
        }
    }

    @Test
    fun `도는 단위가 있으면 안 옮긴다`() {
        // 물리적으로 움직이는 중인 일을 옮기면 한 물건에 소유자가 둘이 된다.
        World().use { w ->
            val exec = w.place(A)
            w.load(A, 5)
            repeat(6) { w.mw.pump(); w.harness.advance(Duration.ofSeconds(1)); Thread.sleep(40) }
            w.mw.pump()

            val kept = assertIs<Reassignment.Kept>(w.mw.reassign(exec.executionId, listOf(B)))
            assertTrue("도는 단위" in kept.reason, kept.reason)
        }
    }

    // ── 왕복하지 않는다

    @Test
    fun `옮긴 직후에는 되돌아오지 않는다`() {
        // ★이 시험이 진동 방지의 요점이다. 일이 A 를 떠나면 A 의 부담이 줄어 **반대 방향의 이득이
        //   곧바로 생긴다** — 최소 유지 시간이 없으면 여기서 왕복이 시작된다.
        World().use { w ->
            val exec = w.place(A)
            w.load(A, 3)
            w.age()
            assertIs<Reassignment.Moved>(w.mw.reassign(exec.executionId, listOf(B)))

            val back = assertIs<Reassignment.Kept>(w.mw.reassign(exec.executionId, listOf(A)))
            assertTrue("최소 유지 시간" in back.reason, back.reason)
            assertEquals(B, exec.robotId)
        }
    }

    @Test
    fun `시간이 지나도 이득이 문턱 이하면 그대로다`() {
        // 최소 유지 시간이 지난 뒤에도 문턱이 남는다. 둘 중 하나만으로는 왕복을 못 막는다.
        World().use { w ->
            val exec = w.place(A)
            w.load(A, 3)
            w.age()
            assertIs<Reassignment.Moved>(w.mw.reassign(exec.executionId, listOf(B)))

            w.age()
            // 이제 A 의 부담은 3, B 는 0(이 일을 뺀다). 되돌리면 이득은 음수다.
            val kept = assertIs<Reassignment.Kept>(w.mw.reassign(exec.executionId, listOf(A)))
            assertTrue("문턱" in kept.reason, kept.reason)
            assertEquals(B, exec.robotId)
        }
    }

    // ── 나머지

    @Test
    fun `후보가 자기 자신뿐이면 그대로다`() {
        World().use { w ->
            val exec = w.place(A)
            w.load(A, 5)
            w.age()

            val kept = assertIs<Reassignment.Kept>(w.mw.reassign(exec.executionId, listOf(A)))
            assertTrue("후보가 없다" in kept.reason, kept.reason)
        }
    }

    @Test
    fun `관문에서 떨어지는 후보는 안 고른다`() {
        // 든 채인 기체는 사슬 검사가 막는다. 부담이 아무리 적어도 못 받는 기체로는 안 옮긴다.
        World().use { w ->
            // **B 를 먼저 든 채로 만든다.** 그 과정이 pump 를 돌리므로, 뒤에 하면 A 의 단위가 돌기 시작해
            // 이 시험이 보려는 것(관문)이 아니라 다른 장치(도는 단위)에 걸린다.
            w.makeHolding(B)
            val exec = w.place(A)
            w.load(A, 5)
            w.age()

            val kept = assertIs<Reassignment.Kept>(w.mw.reassign(exec.executionId, listOf(B)))
            assertTrue("후보가 없다" in kept.reason, kept.reason)
            assertEquals(A, exec.robotId)
        }
    }

    @Test
    fun `모르는 기체는 관문이 막지 않는다`() {
        // ★**권위는 발신자다.** 이 층이 막는 것은 «알고도 보내는 일» 뿐이고, 능력을 못 물은 기체의
        //   조건은 지어내지 않는다. 그래서 재할당도 여기서는 통과하고 접수 때 발신자가 판정한다.
        World().use { w ->
            val exec = w.place(A)
            w.load(A, 5)
            w.age()

            assertIs<Reassignment.Moved>(w.mw.reassign(exec.executionId, listOf("없는-기체")))
        }
    }

    @Test
    fun `종착한 실행은 옮기지 않는다`() {
        // 끝난 일에는 옮길 것이 없다. 그래도 옮기면 종착한 실행의 주인이 바뀌어 감사가 어긋난다.
        World().use { w ->
            val exec = w.place(A)
            repeat(200) {
                w.mw.pump()
                if (exec.physicalState.isSettled) return@repeat
                w.harness.advance(Duration.ofSeconds(1))
                Thread.sleep(40)
            }
            assertTrue(exec.physicalState.isSettled, "이 시험이 보려는 경우가 아니다: ${exec.physicalState}")

            val kept = assertIs<Reassignment.Kept>(w.mw.reassign(exec.executionId, listOf(B)))
            assertTrue("종료" in kept.reason, kept.reason)
            assertEquals(A, exec.robotId)
        }
    }

    @Test
    fun `모르는 실행은 옮기지 않는다`() {
        World().use { w ->
            val kept = assertIs<Reassignment.Kept>(w.mw.reassign("exec-없음", listOf(B)))
            assertTrue("모르는 실행" in kept.reason, kept.reason)
        }
    }

    @Test
    fun `옮긴 사실이 자취에 남는다`() {
        // 옮긴 이력이 없으면 번들을 읽는 사람이 «이 일이 왜 저 기체에 있나» 를 못 따라간다.
        World().use { w ->
            val exec = w.place(A)
            w.load(A, 3)
            w.age()
            assertIs<Reassignment.Moved>(w.mw.reassign(exec.executionId, listOf(B)))

            assertTrue(
                exec.eventTrail.any { it.kind == "REASSIGNED" && A in it.detail && B in it.detail },
                exec.eventTrail.joinToString("\n") { "${it.kind}: ${it.detail}" },
            )
        }
    }

    @Test
    fun `같은 입력이면 같은 답이다`() {
        // 결정적 tick 에서 재현된다 — 같은 상태에 두 번 물어 다른 답이 나오면 재생이 성립하지 않는다.
        World().use { w ->
            val exec = w.place(A)
            w.load(A, 2)
            w.age()

            val first = w.mw.reassign(exec.executionId, listOf(B))
            val second = w.mw.reassign(exec.executionId, listOf(B))
            assertEquals(first, second)
        }
    }

    @Test
    fun `부담이 같은 후보 중 덜 바쁜 쪽을 고른다`() {
        // 후보가 여럿이면 가장 덜 바쁜 쪽이다. 순위 목록이 아니라 부담으로 고르는 자리이므로
        // 목록의 순서가 답을 바꾸지 않는다.
        World().use { w ->
            val exec = w.place(A)
            w.load(A, 4)
            w.load(B, 1)
            w.age()

            val moved = assertIs<Reassignment.Moved>(w.mw.reassign(exec.executionId, listOf(B, C)))
            assertEquals(C, moved.to, "덜 바쁜 쪽이 아니다")
        }
    }

    // ── 옮길 실행 자신은 관문에서 뺀다

    @Test
    fun `자리를 잡은 실행도 자기 자리에 안 걸려 옮겨진다`() {
        // ★옮길 실행은 아직 표에 있다. 빼지 않으면 그 실행이 잡은 슬롯을 «다른 주문이 잡은 자리» 로 읽어
        //   어느 후보로도 못 간다 — 자재를 놓는 실행은 재할당이 통째로 안 되는 것이었다.
        World().use { w ->
            val exec = w.carry(A, "SEQ-M", slots = listOf("RACK.M1", "RACK.M2"), bin = "BIN.M")
            w.load(A, 3)
            w.age()

            val moved = assertIs<Reassignment.Moved>(w.mw.reassign(exec.executionId, listOf(B)))
            assertEquals(B, moved.to)
            assertEquals(B, exec.robotId)
        }
    }

    @Test
    fun `작업 구역을 붙인 배치에서도 자기 구역에 안 걸려 옮겨진다`() {
        // 구역도 같다. 떠나는 기체를 통째로 빼면 안 되므로(아래 시험) 실행 하나만 뺀다.
        World(workspace = Zones(mapOf(BAY_7 to ZONE))).use { w ->
            val exec = w.place(A, "PATROL-Z", location = BAY_7)
            w.load(A, 3)
            w.age()

            val moved = assertIs<Reassignment.Moved>(w.mw.reassign(exec.executionId, listOf(B)))
            assertEquals(B, moved.to)
        }
    }

    @Test
    fun `떠나는 기체의 다른 실행은 여전히 후보를 막는다`() {
        // ★빼는 것은 실행 id 다, 기체가 아니다. 기체로 빼면 A 에 남는 일이 같은 구역에서 도는데도 B 를 그
        //   구역으로 보낸다.
        World(workspace = Zones(mapOf(BAY_7 to ZONE, BAY_8 to ZONE))).use { w ->
            val exec = w.place(A, "PATROL-Z1", location = BAY_7)
            w.place(A, "PATROL-Z2", location = BAY_8)
            w.load(A, 3)
            w.age()

            val kept = assertIs<Reassignment.Kept>(w.mw.reassign(exec.executionId, listOf(B)))
            assertTrue("후보가 없다" in kept.reason, kept.reason)
            assertEquals(A, exec.robotId)
        }
    }

    // ── 반쯤 한 물리 작업은 안 옮긴다

    @Test
    fun `끝난 단위가 있으면 안 옮긴다`() {
        // 점검 순회의 첫 이동이 끝나고 점검 중 위치를 잃었다. 기체가 새 태스크를 못 받는다 하므로 다음 단위가
        // 안 나가고, 도는 단위도 없다. 쥔 것은 없다 — 끝난 단위 하나만으로 막혀야 한다.
        World(profiles = mapOf(A to QUADRUPED, B to PRECOND)).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(
                w.mw.submit(patrol("PATROL-D", PUMP_ROOM, "PUMP-ROOM-2"), A),
            ).execution
            val inspect = exec.units.first { it.unitId == "PUMP-01" }
            w.drive { inspect.taskId.isNotEmpty() && w.taskState(A, inspect.taskId) == "RUNNING" }
            w.forceFault(A, "LOCALIZATION_LOST", inspect.taskId)
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }
            assertNull(exec.active, "이 시험이 보려는 경우가 아니다 — 도는 단위가 있다")
            assertEquals(UnitState.DONE, exec.units.first().state, "이 시험이 보려는 경우가 아니다 — 첫 이동이 안 끝났다")
            assertTrue(exec.units.none { it.everHeld }, "이 시험이 보려는 경우가 아니다 — 쥔 단위가 있다")
            w.load(A, 3)
            w.age()

            val kept = assertIs<Reassignment.Kept>(w.mw.reassign(exec.executionId, listOf(B)))
            assertTrue("끝난 단위" in kept.reason, kept.reason)
            assertEquals(A, exec.robotId)
        }
    }

    @Test
    fun `든 채로 멈춘 실행은 안 옮긴다`() {
        // 첫 슬롯을 집은 채 위치를 잃었다. 끝난 단위는 없지만 물건은 A 의 손에 있다 — 옮기면 B 가 같은
        // 출발 자리에서 다시 집으러 간다.
        // 부담으로 낸 순회가 스냅숏의 손(0.11.0)에 막히지 않게 손을 안 싣는 옛 발신자로 둔다 — 이 시험이 보는 것은 재할당이다.
        World(port = { HandSilentRobotPort(it) }).use { w ->
            val exec = w.carry(A, "SEQ-H", slots = listOf("RACK.H1", "RACK.H2"), bin = "BIN.H")
            val first = exec.units.first()
            w.drive { first.hold.kind == HoldKind.HOLD_KIND_HOLDING }
            w.forceFault(A, "LOCALIZATION_LOST", first.taskId)
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }
            assertNull(exec.active, "이 시험이 보려는 경우가 아니다 — 도는 단위가 있다")
            assertTrue(exec.units.none { it.state == UnitState.DONE }, "이 시험이 보려는 경우가 아니다 — 끝난 단위가 있다")
            assertEquals(HoldKind.HOLD_KIND_HOLDING, first.hold.kind, "이 시험이 보려는 경우가 아니다 — 든 채가 아니다")
            w.load(A, 3)
            w.age()

            val kept = assertIs<Reassignment.Kept>(w.mw.reassign(exec.executionId, listOf(B)))
            assertTrue("자재를 다룬 단위" in kept.reason, kept.reason)
            assertEquals(A, exec.robotId)
        }
    }

    // ── 술어

    @Test
    fun `추가 처리와 기체 점유는 종착하지 않은 상태와 PARTIAL 이다`() {
        // ★PARTIAL 은 종착(isSettled)이지만 개정판으로 다시 돈다. isSettled 만 읽고 기체가 비었다고 하면
        //   그 기체에 둘째 주문이 간다.
        val expected = setOf(
            PhysicalState.REQUESTED, PhysicalState.ACCEPTED, PhysicalState.RUNNING, PhysicalState.IN_DOUBT,
            PhysicalState.OPERATOR_HOLD, PhysicalState.CANCELING, PhysicalState.PARTIAL,
        )
        assertTrue(PhysicalState.PARTIAL.isSettled, "전제가 바뀌었다 — PARTIAL 이 종착이 아니다")
        assertEquals(expected, PhysicalState.entries.filter { it.pumps }.toSet(), "pumps")
        assertEquals(expected, PhysicalState.entries.filter { it.occupiesRobot }.toSet(), "occupiesRobot")
        PhysicalState.entries.filter { it !in expected }.forEach {
            assertFalse(it.pumps, it.name)
            assertFalse(it.occupiesRobot, it.name)
        }
    }

    /** 자리마다 구역을 시험이 정한다. 대장에 없는 자리는 구역 밖이다. */
    private class Zones(private val rows: Map<String, String>) : Workspace {
        override fun zoneOf(location: String): String? = rows[location]
    }

    companion object {
        const val A = "hum-02"
        const val B = "hum-03"
        const val C = "hum-04"
        const val MATERIAL = "ENGINE-COVER-A"

        const val PUMP_ROOM = "PUMP-ROOM-1"
        const val BAY_7 = "BAY-7"
        const val BAY_8 = "BAY-8"
        const val ZONE = "CELL-NORTH"

        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()

        /** `navigate_to` 와 `inspect` 를 둘 다 드는 4족 — 점검 순회가 끝까지 돈다. */
        private val QUADRUPED: Path = Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()

        /** 점검 순회. 자리마다 대상 하나(`PUMP-01`, `PUMP-02` …) — 단위는 이동과 점검 한 쌍씩이다. */
        private fun patrol(jobOrderId: String, vararg locations: String = arrayOf(PUMP_ROOM)) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = locations.mapIndexed { i, location ->
                EquipmentRequirement(
                    "PUMP-0${i + 1}",
                    EquipmentUse.INSPECTION_TARGET,
                    mapOf(EquipmentUse.PROP_LOCATION to location),
                )
            },
        )
    }
}

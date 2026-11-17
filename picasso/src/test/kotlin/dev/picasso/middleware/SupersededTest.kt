package dev.picasso.middleware

import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.wire.ControllerFence
import dev.picasso.contracts.wire.EpochAdmission
import dev.picasso.contracts.wire.FenceStore
import dev.picasso.contracts.wire.FenceMode
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **밀려난 프로세스의 명령**(ADR 54). 대조하는 미믹 앞에서 이 미들웨어는 세대 1 이고, 새 제어자가 세대 2 로 인수 확인(`GetSnapshot`)을 하면
 * 그 뒤의 명령은 `STALE_CONTROLLER` 로 거절된다.
 *
 * - 포트([ClientRobotPort])는 그 거절을 보면 명령을 닫고(래치) 담는 쪽에 한 번 알린다. 그 뒤의 명령은 선로에 안 나간다. 읽기 응답 머리말의
 *   바닥값이 더 높아도 같다. 클라이언트가 붙잡은 세대와 공급자가 갈려도 보내지 않는다.
 * - 미들웨어는 그 거절을 첫 출발·개정 갱신·미확정 조회·취소 넷 어디서든 일반 실패로 읽지 않는다 — 단위 실패·재작업·인시던트·회복 제안이
 *   없다. 밀려난 프로세스의 판단은 뜻이 없다.
 */
class SupersededTest {

    /**
     * [lost] 만큼 다음 `StartTask` 를 하위에 닿게 한 뒤 답을 잃는다. [notAdmitted] 면 하위로 보내지 않고 수신자가 답한 것처럼 «자격 없음» 세대
     * 거절(밀려남 아님)을 돌려준다 — 미확정 조회가 밀려남이 아닌 거절을 받는 장면을 짓는 시험 더블이다.
     */
    private class Wire(private val delegate: RobotPort) : RobotPort by delegate {
        var lost = 0
        var notAdmitted = false
        var refused = 0
        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            if (notAdmitted) {
                refused += 1
                return StartTaskResponse.newBuilder().setRejection(
                    dev.picasso.contracts.v1.Rejection.newBuilder().setCode(dev.picasso.contracts.v1.RejectionCode.REJECTION_CODE_STALE_CONTROLLER)
                        .setDetail("시험: 자격 없음").setRequestedControllerEpoch(1).setControllerEpochFloor(1),
                ).build()
            }
            val response = delegate.start(robotId, taskId, revision, skillType, parameters)
            if (lost > 0) {
                lost -= 1
                throw IllegalStateException("response lost after the request reached the robot")
            }
            return response
        }
    }

    private class World(
        blind: Boolean = false,
        private var epoch: Long = 1,
        admission: EpochAdmission = EpochAdmission.VERIFIED_COMMANDS_AND_SNAPSHOT,
    ) : AutoCloseable {
        val harness = Harness(
            mapOf(ROBOT to Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()),
            fence = ControllerFence(FenceMode.FENCED, FenceStore.memory(), admission),
        )
        val signals = mutableListOf<Triple<String, Long, Long>>()
        val port = ClientRobotPort(harness.client(controllerEpoch = { epoch }), onSuperseded = { r, mine, floor -> signals += Triple(r, mine, floor) })
        val wire = Wire(if (blind) FaultBlindRobotPort(port) else port)
        val mw = Middleware(wire, now = { harness.clock.now() })

        fun supply(next: Long) {
            epoch = next
        }

        /** 새 제어자가 세대 2 로 넘겨받는다 — 인수 확인 스냅샷. */
        fun takeOver() = harness.client("new-host", controllerEpoch = { 2 }).snapshot(ROBOT)

        fun starts() = harness.recorder.requests.count { it.second is StartTaskRequest }

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList
        fun state(taskId: String) = tasks().firstOrNull { it.taskId == taskId }?.taskState

        fun submit(version: Int): Middleware.Execution = assertIs<Middleware.Submission.Accepted>(mw.submit(order(version), ROBOT)).execution

        /** 실행 자취의 세대 거절 줄. */
        fun refusals(exec: Middleware.Execution) = exec.eventTrail.filter { it.kind == MiddlewareCodes.CONTROLLER_REFUSED }

        /** 미확정 유예(60초)를 넘길 만큼 라운드를 돌린다. */
        fun pastGrace() = repeat(12) {
            mw.pump()
            harness.advance(Duration.ofSeconds(10))
        }

        fun drive(rounds: Int = 60, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(10))
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다: tasks=${tasks().map { it.taskId to it.taskState }}")
        }

        override fun close() = harness.close()
    }

    /** 밀려난 거절이 일반 실패로 새지 않았다. */
    private fun World.untouched(exec: Middleware.Execution) {
        assertTrue(exec.units.none { it.state == UnitState.FAILED || it.state == UnitState.OPERATOR_HOLD }, "세대 거절을 단위 실패로 읽었다: ${exec.units.map { it.unitId to it.state }}")
        assertEquals(emptyList(), mw.incidents(), "세대 거절로 인시던트를 열었다")
        assertEquals(emptyList(), mw.reworkCandidates(), "세대 거절을 재작업 후보로 냈다")
        assertEquals(emptyList(), mw.remedySearches(), "세대 거절로 회복 제안을 찾았다")
        assertNull(exec.cancelRefusal, "세대 거절을 하위의 취소 거부로 적었다")
    }

    @Test
    fun `첫 출발이 세대로 거절되면 보내지 않은 것으로 두고 포트를 닫아 다음 출발은 선로에 안 나간다`() {
        // 스냅숏을 못 읽는 기체다 — 읽기 머리말의 바닥값으로 먼저 닫히지 않고 출발이 수신자의 거절을 직접 받는다.
        World(blind = true).use { w ->
            val exec = w.submit(1)
            w.takeOver()
            w.mw.pump()
            val first = exec.units.first()
            assertEquals(UnitState.PENDING, first.state, "세대 거절을 보내지 않음으로 읽지 않았다")
            assertEquals(listOf(Triple(ROBOT, 1L, 2L)), w.signals, "밀려남을 담는 쪽에 알리지 않았다")
            assertNotNull(w.port.superseded)
            val sent = w.starts()
            assertEquals(1, sent, "시험의 전제: 첫 출발이 수신자에 닿아 거절됐다")
            repeat(3) { w.mw.pump() }
            assertEquals(sent, w.starts(), "닫힌 포트가 출발을 선로에 냈다")
            assertEquals(1, w.signals.size, "밀려남을 되풀이해 알렸다")
            // 자취는 «보내지 않음» 이 아니라 수신자가 답한 그 거절을 한 번 싣는다 — 까닭·요청 세대·바닥값.
            val line = w.refusals(exec).single().detail
            assertTrue("수신자가 답한 세대 거절" in line && "BELOW_FLOOR" in line && "요청 세대=1" in line && "바닥값=2" in line, line)
            assertTrue(first.note.orEmpty().contains(MiddlewareCodes.FENCE_REFUSED), "세대 거절을 «보내지 않음» 일반 문구로 뭉갰다: ${first.note}")
            assertFalse(first.note.orEmpty().contains(Middleware.START_NOT_SENT), "세대 거절을 «보내지 않음» 으로 적었다: ${first.note}")
            w.pastGrace()
            assertEquals(UnitState.PENDING, first.state)
            w.untouched(exec)
        }
    }

    @Test
    fun `개정 갱신이 세대로 거절되면 미뤄 두고 단위를 실패로 적지 않는다`() {
        World().use { w ->
            val exec = w.submit(1)
            w.drive { w.state(taskOf(INSPECT)) == "RUNNING" }
            w.takeOver()
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(2), ROBOT))
            val unit = exec.units.single { it.unitId == INSPECT }
            assertEquals(UnitState.RUNNING, unit.state, "세대 거절된 갱신을 실패나 미확정으로 읽었다")
            assertEquals(1, exec.deferredUpdate, "세대 거절된 갱신을 보내지 않음으로 미루지 않았다")
            assertEquals(1, w.signals.size)
            w.untouched(exec)
        }
    }

    @Test
    fun `미확정 조회가 세대로 거절되면 조회 횟수를 쓰지 않고 미확정인 채 둔다`() {
        World(blind = true).use { w ->
            w.wire.lost = 1
            val exec = w.submit(1)
            val first = exec.units.first()
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, first.state, "시험의 전제: 첫 출발의 답을 잃었다")
            w.takeOver()
            repeat(3) {
                w.mw.pump()
                w.harness.advance(Duration.ofSeconds(5))
            }
            assertEquals(UnitState.IN_DOUBT, first.state, "세대 거절된 조회를 종결로 읽었다: ${first.note}")
            assertEquals(0, first.lookups, "보내지 않은 조회를 조회 횟수로 셌다")
            assertEquals(1, w.signals.size)
            w.untouched(exec)
        }
    }

    @Test
    fun `취소가 세대로 거절되면 닿지 않은 것으로 두고 하위의 거부로 적지 않는다`() {
        World().use { w ->
            val exec = w.submit(1)
            w.drive { w.state(taskOf(INSPECT)) == "RUNNING" }
            w.takeOver()
            w.mw.cancel(exec.executionId)
            w.mw.pump()
            assertTrue(exec.cancelUnsent, "세대 거절된 취소를 닿은 것으로 읽었다")
            assertEquals("RUNNING", w.state(taskOf(INSPECT)), "밀려난 취소가 기체 태스크를 멈췄다")
            assertEquals(1, w.signals.size)
            w.untouched(exec)
        }
    }

    @Test
    fun `읽기 응답의 바닥값이 내 세대보다 높으면 알리고 명령을 닫는다`() {
        World().use { w ->
            w.takeOver()
            assertNotNull(w.port.snapshot(ROBOT), "닫혀도 읽기는 계속한다")
            assertEquals(listOf(Triple(ROBOT, 1L, 2L)), w.signals)
            val sent = w.starts()
            assertFailsWith<CommandNotSent> { w.port.start(ROBOT, "t-1", 1, "inspect", emptyMap()) }
            assertEquals(sent, w.starts(), "닫힌 포트가 명령을 선로에 냈다")
        }
    }

    @Test
    fun `세대가 0 인 포트의 거절은 그 명령만 거절된 것이라 닫지도 알리지도 않고 일반 실패로도 읽지 않는다`() {
        World(epoch = 0).use { w ->
            val exec = w.submit(1)
            w.mw.pump()
            assertEquals(UnitState.PENDING, exec.units.first().state)
            assertEquals(emptyList(), w.signals, "세대 없이 보낸 것을 밀려남으로 알렸다 — 설정 오류다")
            assertNull(w.port.superseded, "밀려나지 않았는데 포트 전체를 닫았다")
            assertTrue(w.refusals(exec).single().detail.contains("EPOCH_MISSING"), "필수 누락을 가르지 않았다")
            // 다시 내기는 조회와 같은 간격 뒤다 — 시계가 안 흐르면 라운드마다 선로에 나가지 않는다.
            repeat(3) { w.mw.pump() }
            assertEquals(1, w.starts(), "밀려남이 아닌 거절을 라운드마다 다시 냈다")
            w.pastGrace()
            assertTrue(w.starts() > 1, "밀려나지 않은 포트가 다시 내지 않았다 — 설정 오류는 고치면 풀려야 한다")
            w.untouched(exec)
        }
    }

    @Test
    fun `올릴 자격 거부는 그 명령만 거절된 것이라 닫지도 알리지도 않는다`() {
        World(admission = { _, _, _ -> false }).use { w ->
            val exec = w.submit(1)
            w.mw.pump()
            assertEquals(UnitState.PENDING, exec.units.first().state)
            assertEquals(emptyList(), w.signals)
            assertNull(w.port.superseded)
            assertTrue(w.refusals(exec).single().detail.contains("NOT_ADMITTED"), w.refusals(exec).toString())
            w.untouched(exec)
        }
    }

    @Test
    fun `능력 응답의 바닥값이 내 세대보다 높아도 알리고 명령을 닫는다`() {
        World().use { w ->
            w.takeOver()
            assertNotNull(w.port.capabilities(ROBOT))
            assertEquals(listOf(Triple(ROBOT, 1L, 2L)), w.signals, "능력 응답 머리말의 바닥값을 버렸다")
            assertNotNull(w.port.superseded)
        }
    }

    @Test
    fun `앞서 답을 잃은 조회가 있어도 세대 거절 뒤에는 유예가 지나도 운영자 보류로 가지 않는다`() {
        World(blind = true).use { w ->
            w.wire.lost = 2 // 첫 출발과 첫 조회의 답을 잃는다 — 조회 횟수가 0 이 아니다.
            val exec = w.submit(1)
            val first = exec.units.first()
            repeat(20) {
                if (w.wire.lost == 0) return@repeat
                w.mw.pump()
                w.harness.advance(Duration.ofSeconds(1))
            }
            assertEquals(UnitState.IN_DOUBT, first.state, "시험의 전제: 미확정이다")
            assertTrue(first.lookups > 0, "시험의 전제: 답을 잃은 조회가 있다(${first.lookups})")
            w.takeOver()
            w.pastGrace()
            assertEquals(UnitState.IN_DOUBT, first.state, "세대 거절 뒤 유예가 지나 판단으로 넘어갔다: ${first.note}")
            assertEquals(1, w.refusals(exec).size)
            w.untouched(exec)
        }
    }

    @Test
    fun `미확정 중의 키 취소가 세대로 거절돼도 유예가 지나 운영자 보류로 가지 않는다`() {
        World(blind = true).use { w ->
            w.wire.lost = 1
            val exec = w.submit(1)
            val first = exec.units.first()
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, first.state, "시험의 전제: 첫 출발의 답을 잃었다")
            w.takeOver()
            w.mw.cancel(exec.executionId)
            w.pastGrace()
            assertEquals(UnitState.IN_DOUBT, first.state, "세대로 거절된 키 취소가 유예 뒤 운영자 보류가 됐다: ${first.note}")
            assertTrue(w.refusals(exec).isNotEmpty())
            w.untouched(exec)
        }
    }

    @Test
    fun `공급자의 세대가 바뀌면 명령을 보내지 않는다`() {
        World().use { w ->
            w.port.snapshot(ROBOT)
            w.port.start(ROBOT, "t-1", 1, "inspect", emptyMap())
            val sent = w.starts()
            w.supply(5)
            assertFailsWith<CommandNotSent> { w.port.start(ROBOT, "t-2", 1, "inspect", emptyMap()) }
            assertEquals(sent, w.starts(), "세대가 바뀐 명령이 선로에 나갔다")
        }
    }

    @Test
    fun `밀려남이 아닌 거절은 미확정의 정상 경로를 막지 않아 유예가 지나면 운영자 보류로 간다`() {
        World(blind = true).use { w ->
            w.wire.lost = 1
            val exec = w.submit(1)
            val first = exec.units.first()
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, first.state, "시험의 전제: 첫 출발의 답을 잃었다")
            w.wire.notAdmitted = true
            w.pastGrace()
            assertTrue(w.refusals(exec).any { "NOT_ADMITTED" in it.detail }, "시험의 전제: 조회가 자격 없음 거절을 받았다")
            assertEquals(UnitState.OPERATOR_HOLD, first.state, "밀려나지 않은 거절이 미확정의 판단을 멈췄다: ${first.note}")
            assertTrue(first.note.orEmpty().contains("controller-epoch refusal"), "수신자가 거절로 답한 조회를 «답이 없다» 로 적었다: ${first.note}")
            assertFalse(first.note.orEmpty().contains("lookup unanswered"), first.note)
            assertNull(exec.command.refusedLookup, "보류로 간 단위의 거절 사유가 다음 시도로 남았다")
            assertTrue(w.mw.incidents().isNotEmpty(), "유예가 지난 미확정에 인시던트를 열지 않았다")
        }
    }

    @Test
    fun `밀려난 기체는 새 소유자가 같은 태스크를 높은 리비전으로 고쳐도 운영자 보류로 가지 않는다`() {
        World().use { w ->
            w.wire.lost = 1
            val exec = w.submit(1)
            val first = exec.units.first()
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, first.state, "시험의 전제: 첫 출발의 답을 잃었다")
            // 새 소유자가 넘겨받고 같은 태스크를 리비전 2 로 고친다.
            val owner = ClientRobotPort(w.harness.client("new-host", controllerEpoch = { 2 }))
            owner.snapshot(ROBOT)
            assertTrue(owner.start(ROBOT, first.taskId, 2, first.skillType, first.parameters).hasHandle(), "시험의 전제: 새 소유자의 갱신이 받아졌다")
            w.pastGrace()
            assertEquals(UnitState.IN_DOUBT, first.state, "밀려난 프로세스가 높은 리비전을 보고 운영자 보류로 갔다: ${first.note}")
            assertEquals(emptyList(), w.mw.incidents(), "밀려난 프로세스가 인시던트를 열었다")
        }
    }

    @Test
    fun `다시 지은 실행의 붙기 전 취소가 밀려남으로 닿지 않아도 유예가 지나 운영자 보류로 가지 않는다`() {
        World().use { w ->
            val exec = w.submit(1)
            w.drive { w.state(taskOf(INSPECT)) == "RUNNING" }
            // 같은 세대의 새 인스턴스가 기록 없이 다시 짓는다 — 기체의 태스크는 아직 붙지 않았다. 다시 지은 뒤로는 스냅숏을 읽어(머리말의 바닥값은
            // 포트가 본다) 뷰에는 못 읽은 것으로 준다 — 붙기 전 취소가 기체를 볼 때까지 기다리는 자리(`waitUnattached`)에 머문다.
            val port = ClientRobotPort(w.harness.client(controllerEpoch = { 1 }))
            var hide = false
            val seen = object : RobotPort by port {
                override fun snapshot(robotId: String): RobotSnapshot? = port.snapshot(robotId).takeUnless { hide }
            }
            val again = Middleware(seen, now = { w.harness.clock.now() })
            val rebuilt = assertIs<Middleware.Submission.Accepted>(again.resume(exec.order, ROBOT, ActiveMission(InspectAsset(), null))).execution
            hide = true
            w.takeOver()
            again.cancel(rebuilt.executionId)
            repeat(12) {
                again.pump()
                w.harness.advance(Duration.ofSeconds(10))
            }
            assertTrue(rebuilt.units.none { it.state == UnitState.OPERATOR_HOLD }, "밀려난 인스턴스의 붙기 전 취소가 운영자 보류가 됐다: ${rebuilt.units.map { it.unitId to it.state }}")
            assertEquals(emptyList(), again.incidents(), "밀려난 인스턴스가 인시던트를 열었다")
            assertTrue(rebuilt.eventTrail.any { it.kind == MiddlewareCodes.CANCEL_UNATTACHED_WAIT }, "시험의 전제: 붙기 전 취소가 기다리는 자리를 지났다: ${rebuilt.eventTrail.map { it.kind }}")
            assertTrue(port.controllerRefusal(ROBOT)?.superseded == true, "시험의 전제: 포트가 밀려남을 알았다")
        }
    }

    @Test
    fun `밀려남이 아닌 거절을 받은 개정 갱신도 간격 뒤에 다시 낸다`() {
        World().use { w ->
            val exec = w.submit(1)
            w.drive { w.state(taskOf(INSPECT)) == "RUNNING" }
            w.wire.notAdmitted = true
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(2), ROBOT))
            assertEquals(1, w.wire.refused, "시험의 전제: 갱신이 자격 없음 거절을 받았다")
            assertEquals(1, exec.deferredUpdate, "시험의 전제: 거절된 갱신을 미뤘다")
            repeat(3) { w.mw.pump() }
            assertEquals(1, w.wire.refused, "밀려남이 아닌 거절을 받은 갱신을 라운드마다 다시 냈다")
            w.harness.advance(Duration.ofSeconds(3))
            w.mw.pump()
            assertEquals(2, w.wire.refused, "간격이 지났는데 미룬 갱신을 다시 내지 않았다")
        }
    }

    private companion object {
        const val ROBOT = "quad-01"
        const val ORDER = "PATROL-9"
        const val INSPECT = "PUMP-01"

        fun taskOf(unitId: String) = "$ORDER#$unitId"

        fun order(version: Int) = JobOrder(
            jobOrderId = ORDER,
            workMasterId = InspectAsset.WORK_MASTER,
            version = version,
            requiredEvidence = Evidence.E0,
            parameters = mapOf("shift" to "night"),
            equipmentRequirements = listOf("PUMP-ROOM" to "PUMP-01", "SWITCHGEAR" to "PANEL-3").map { (location, target) ->
                EquipmentRequirement(target, EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to location, EquipmentUse.PROP_ITEM to "thermal"))
            },
        )
    }
}

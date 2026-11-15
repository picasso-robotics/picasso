package dev.picasso.middleware

import dev.picasso.harness.Harness
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * **플릿 명령을 잠금 밖으로 보낸다**(§15.237). 포트가 [AmrFleetPort.issueDispatch]·[AmrFleetPort.issueCancel] 로 명령을 받으면 미들웨어는
 * 그 결과를 기다리지 않고 다음 라운드에 받는다. 맡기는 중에 걸린 취소와 개정은 결과 뒤로 미룬다.
 *
 * 업무는 용기 공급(운반 전체를 플릿에 맡기는 단위 하나), 플릿은 [AmrFleetMimic] 이다. 송신은 [IssuingFleetPort] 가 손으로 돌리는
 * 실행기([Manual])에서 돌린다.
 */
class OffLockFleetTest {

    /** 넘겨받은 송신을 쌓아 두었다가 [run] 이 돌린다. */
    private class Manual : Executor {
        val queued = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            queued += command
        }

        fun run() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    /** 플릿으로 나간 취소를 센다. [lost] 만큼 다음 맡기기를 플릿에 닿게 한 뒤 답을 잃고, [cancelFailures] 만큼 다음 취소를 닿지 않게 한다. */
    private class Wire(private val delegate: AmrFleetPort) : AmrFleetPort by delegate {
        var lost = 0
        var cancelFailures = 0
        val cancels = mutableListOf<String>()

        override fun dispatch(order: TransportOrder): TransportHandle? {
            val handle = delegate.dispatch(order)
            if (lost > 0) {
                lost -= 1
                throw IllegalStateException("response lost after the fleet accepted the order")
            }
            return handle
        }

        override fun cancel(handle: TransportHandle): Boolean {
            if (cancelFailures > 0) {
                cancelFailures -= 1
                throw IllegalStateException("시험: 취소가 플릿에 닿지 않았다")
            }
            cancels += handle.id
            return delegate.cancel(handle)
        }
    }

    private class World : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val fleet = AmrFleetMimic(now = { harness.clock.now() })
        val wire = Wire(fleet)
        val cell = CellMimic(now = { harness.clock.now() }, live = { loc -> fleet.containersAt[loc]?.let { it to fleet.placedAt[loc] } })
        val manual = Manual()
        val port = IssuingFleetPort(wire, now = { harness.clock.now() }, executor = manual)
        val mw = Middleware(ClientRobotPort(harness.client()), cell, port, now = { harness.clock.now() })

        init {
            fleet.place(SOURCE, CONTAINER)
        }

        fun submit(version: Int = 1): Middleware.Execution = assertIs<Middleware.Submission.Accepted>(mw.submit(order(version), ROBOT)).execution

        /** 한 라운드 펌프하고, 쌓인 송신을 보내고, 세계를 한 칸 민다. */
        fun round() {
            mw.pump()
            manual.run()
            harness.advance(Duration.ofSeconds(10))
            fleet.tick()
        }

        fun drive(rounds: Int = 20, until: () -> Boolean) {
            repeat(rounds) {
                round()
                if (until()) return
            }
            error("조건에 못 미쳤다: 세계=${fleet.containersAt}")
        }

        override fun close() {
            port.close()
            harness.close()
        }
    }

    @Test
    fun `잠금 밖 맡기기는 결과를 기다리지 않고 다음 라운드에 받는다`() {
        World().use { w ->
            val exec = w.submit()
            val unit = exec.units.single()
            w.mw.pump()

            assertEquals(UnitState.PENDING, unit.state, "맡기기의 결과를 기다렸다")
            assertEquals(0, w.fleet.dispatches, "라운드 안에서 플릿에 보냈다")
            assertEquals(FleetFlight.Kind.START, exec.fleetInFlight?.kind)

            w.manual.run()
            w.mw.pump()
            assertEquals(UnitState.RUNNING, unit.state, "다음 라운드에 결과를 받지 않았다")
            assertNull(exec.fleetInFlight)
            assertEquals(TRANSPORT, exec.transport)
            assertEquals(1, w.fleet.dispatches, "운반을 두 번 맡겼다")
        }
    }

    @Test
    fun `맡기는 중에 걸린 취소는 접수를 본 뒤 그 운반에 보낸다`() {
        World().use { w ->
            val exec = w.submit()
            w.mw.pump()
            assertTrue(w.mw.cancel(exec.executionId))
            assertEquals(emptyList(), w.wire.cancels, "맡기는 중에 취소를 보냈다")

            w.manual.run()
            w.mw.pump()
            assertNotNull(exec.fleetCancelInFlight, "접수를 본 뒤 취소를 걸지 않았다")
            w.manual.run()
            assertEquals(listOf(TRANSPORT.id), w.wire.cancels, "접수를 본 뒤 취소를 보내지 않았다")
            w.drive { !exec.physicalState.pumps }
            assertEquals(TransportState.CANCELLED, w.fleet.status(TRANSPORT).state)
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
        }
    }

    @Test
    fun `닿지 못한 잠금 밖 운반 취소는 다음 라운드에 다시 보낸다`() {
        World().use { w ->
            val exec = w.submit()
            val unit = exec.units.single()
            w.drive { unit.state == UnitState.RUNNING }
            w.wire.cancelFailures = 1

            assertTrue(w.mw.cancel(exec.executionId))
            assertNotNull(exec.fleetCancelInFlight, "운반 취소를 잠금 밖으로 걸지 않았다")
            w.mw.pump()
            assertNotNull(exec.fleetCancelInFlight, "보내지 않은 취소의 결과를 받았다")
            w.manual.run()
            w.mw.pump()
            assertTrue(exec.cancelUnsent, "닿지 못한 취소를 닿은 것으로 읽었다")
            assertNotNull(exec.fleetCancelInFlight, "닿지 못한 취소를 다시 걸지 않았다")

            w.manual.run()
            w.mw.pump()
            assertFalse(exec.cancelUnsent, "다시 보낸 취소가 닿았는데 표시를 내리지 않았다")
            assertEquals(listOf(TRANSPORT.id), w.wire.cancels)
            assertEquals(TransportState.CANCELLED, w.fleet.status(TRANSPORT).state)
        }
    }

    @Test
    fun `보내는 중인 플릿 미확정 조회는 다음 라운드에 결과를 받아 풀린다`() {
        World().use { w ->
            w.wire.lost = 1
            val exec = w.submit()
            val unit = exec.units.single()
            w.mw.pump()
            w.manual.run()
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, unit.state, "시험의 전제: 맡기기의 답을 잃었다")

            w.mw.pump()
            assertEquals(FleetFlight.Kind.LOOKUP, exec.fleetInFlight?.kind, "시험의 전제: 조회가 보내는 중이다")
            w.manual.run()
            w.mw.pump()
            assertEquals(UnitState.RUNNING, unit.state, "보내는 중인 조회의 결과를 받지 않았다: ${unit.note}")
            assertNull(exec.fleetInFlight)
            assertEquals(1, w.fleet.dispatches, "조회가 운반을 다시 맡겼다")
        }
    }

    @Test
    fun `맡기는 중에 온 개정은 운반 단위를 바꾸지 않는다`() {
        World().use { w ->
            val exec = w.submit()
            val unit = exec.units.single()
            w.mw.pump()
            assertNotNull(exec.fleetInFlight, "시험의 전제: 맡기기가 보내는 중이다")

            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(2), ROBOT))
            assertSame(unit, exec.units.single(), "맡기는 중인 운반 단위를 새 단위로 바꿨다 — 그 맡기기의 결과가 받을 단위를 잃는다")
            w.manual.run()
            w.mw.pump()
            assertEquals(UnitState.RUNNING, unit.state)
            assertEquals(1, w.fleet.dispatches)
        }
    }

    @Test
    fun `다시 보내는 운반 취소가 나가는 중에 끝난 운반은 그 답을 받은 뒤 중단을 적는다`() {
        World().use { w ->
            val exec = w.submit()
            val unit = exec.units.single()
            w.drive { unit.state == UnitState.RUNNING }
            w.wire.cancelFailures = 1
            assertTrue(w.mw.cancel(exec.executionId))
            w.manual.run()
            repeat(10) { if (w.fleet.status(TRANSPORT).state != TransportState.DELIVERED) w.fleet.tick() }
            assertEquals(TransportState.DELIVERED, w.fleet.status(TRANSPORT).state, "시험의 전제: 닿지 못한 취소 뒤 운반이 끝났다")

            w.mw.pump()
            assertTrue(exec.physicalState.pumps, "다시 보낸 취소의 답을 받기 전에 중단을 적었다")
            w.manual.run()
            w.drive { !exec.physicalState.pumps }
            val report = assertNotNull(w.mw.lastCancel(exec.executionId))
            assertTrue(report.refusal != Middleware.CANCEL_NOT_SENT, "닿은 취소를 닿지 못했다고 적었다: ${report.refusal}")
        }
    }

    /** 끝난 실행에 운반 취소가 남는 길은 운영자 판단이다. 여기서는 실행의 상태를 직접 끝내 그 뒤만 본다. */
    @Test
    fun `끝난 실행에 남은 운반 취소는 이빅션을 막고 다음 라운드에 차선을 푼다`() {
        World().use { w ->
            val exec = w.submit()
            val unit = exec.units.single()
            w.drive { unit.state == UnitState.RUNNING }
            assertTrue(w.mw.cancel(exec.executionId))
            exec.physicalState = PhysicalState.ABORTED

            val refused = assertIs<ForgetOutcome.Refused>(w.mw.forget(exec.executionId))
            assertTrue("취소" in refused.reason, refused.reason)

            w.manual.run()
            w.mw.pump()
            assertNull(exec.fleetCancelInFlight, "끝난 실행의 운반 취소 결과를 받지 않았다 — 그 운반의 차선이 풀리지 않는다")
            assertEquals(DispatchIssue.Pending, w.port.issueDispatch(TransportOrder(unit.taskId, CONTAINER, SOURCE, DEST)), "끝난 실행의 취소가 차선을 쥐고 있다")
        }
    }

    private companion object {
        const val ROBOT = "idle-robot"
        const val CONTAINER = "HU-1042"
        const val SOURCE = "OUT-07"
        const val DEST = "SEQ-IN-02"
        val TRANSPORT = TransportHandle("tr-1")

        fun order(version: Int) = JobOrder(
            jobOrderId = "WT-781",
            workMasterId = DeliverContainer.WORK_MASTER,
            version = version,
            requiredEvidence = Evidence.E2,
            equipmentRequirements = listOf(
                EquipmentRequirement(SOURCE, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_CONTAINER to CONTAINER)),
                EquipmentRequirement(DEST, EquipmentUse.DESTINATION),
            ),
        )
    }
}

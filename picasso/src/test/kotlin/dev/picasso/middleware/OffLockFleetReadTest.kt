package dev.picasso.middleware

import dev.picasso.harness.Harness
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **플릿 읽기를 잠금 밖에서 한다**(§15.237). 포트가 [AmrFleetPort.prefetchedStatus] 로 운반 상태를 주면 미들웨어는 라운드 안에서
 * 묻지 않고 다 읽은 값만 받는다. 기한 넘게 답이 없으면 동기 읽기가 던지던 것과 같이 다룬다.
 *
 * 업무는 용기 공급(운반 전체를 플릿에 맡기는 단위 하나), 플릿은 [AmrFleetMimic] 이다. 읽기는 [PrefetchingFleetPort] 가 손으로 돌리는
 * 실행기([Manual])에서 돌린다.
 */
class OffLockFleetReadTest {

    /** 넘겨받은 읽기를 쌓아 두었다가 [run] 이 돌린다. */
    private class Manual : Executor {
        val queued = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            queued += command
        }

        fun run() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    /** 운반 상태를 읽은 횟수를 센다. */
    private class Wire(private val delegate: AmrFleetPort) : AmrFleetPort by delegate {
        var statusReads = 0

        override fun status(handle: TransportHandle): TransportStatus {
            statusReads += 1
            return delegate.status(handle)
        }
    }

    private class World : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val fleet = AmrFleetMimic(now = { harness.clock.now() })
        val wire = Wire(fleet)
        val cell = CellMimic(now = { harness.clock.now() }, live = { loc -> fleet.containersAt[loc]?.let { it to fleet.placedAt[loc] } })
        val manual = Manual()
        val port = PrefetchingFleetPort(wire, now = { harness.clock.now() }, maxAge = MAX_AGE, executor = manual)
        val mw = Middleware(ClientRobotPort(harness.client()), cell, port, now = { harness.clock.now() })

        init {
            fleet.place(SOURCE, CONTAINER)
        }

        fun submit(): Middleware.Execution = (mw.submit(order(), ROBOT) as Middleware.Submission.Accepted).execution

        /** 운반이 맡겨져 도는 데까지 민다. 쌓인 읽기는 다 돌린다. */
        fun running(exec: Middleware.Execution) {
            repeat(5) {
                mw.pump()
                manual.run()
                if (exec.units.single().state == UnitState.RUNNING) return
            }
            error("운반이 돌지 않았다: ${exec.units.single()}")
        }

        /** 운반을 플릿에서 끝까지 민다. */
        fun deliver() {
            repeat(10) { if (fleet.status(TRANSPORT).state != TransportState.DELIVERED) fleet.tick() }
            check(fleet.status(TRANSPORT).state == TransportState.DELIVERED)
        }

        override fun close() {
            port.close()
            harness.close()
        }
    }

    @Test
    fun `잠금 밖 운반 상태는 라운드 안에서 묻지 않고 다 읽은 값을 다음 라운드에 받는다`() {
        World().use { w ->
            val exec = w.submit()
            val unit = exec.units.single()
            w.running(exec)
            w.deliver()

            val reads = w.wire.statusReads
            w.mw.pump()
            w.mw.pump()
            assertEquals(reads, w.wire.statusReads, "라운드 안에서 운반 상태를 읽었다")
            assertEquals(UnitState.RUNNING, unit.state, "읽기 전의 값으로 끝난 운반을 판정했다")

            repeat(3) {
                if (unit.state != UnitState.RUNNING) return@repeat
                w.manual.run()
                w.mw.pump()
            }
            assertNotEquals(UnitState.RUNNING, unit.state, "다 읽은 운반 상태를 받지 않았다")
            assertNotNull(unit.downstreamDoneAt, "끝난 운반을 하류의 끝으로 적지 않았다")
        }
    }

    @Test
    fun `끝난 운반은 받아 든 시각이 아니라 그 읽기가 받은 시각으로 끝낸다`() {
        World().use { w ->
            val exec = w.submit()
            val unit = exec.units.single()
            w.running(exec)
            w.mw.pump()
            w.deliver()
            w.manual.run()
            val receivedAt: Instant = w.harness.clock.now()

            w.harness.advance(Duration.ofSeconds(30))
            w.mw.pump()
            assertEquals(receivedAt, unit.downstreamDoneAt, "끝난 시각을 받아 든 시각으로 찍었다 — 근거 창이 그만큼 밀린다")
        }
    }

    @Test
    fun `기한 넘게 답 없는 운반 상태 읽기는 펌프 실패로 남기고 단위를 그대로 둔다`() {
        World().use { w ->
            val exec = w.submit()
            val unit = exec.units.single()
            w.running(exec)
            w.mw.pump()
            w.manual.run()
            w.mw.pump()
            assertTrue(w.manual.queued.isNotEmpty(), "시험의 전제: 다음 읽기가 걸렸다")

            w.harness.advance(MAX_AGE.plusSeconds(1))
            w.mw.pump()
            assertTrue(
                exec.eventTrail.any { it.kind == Middleware.PUMP_FAILED && TRANSPORT.id in it.detail },
                "답 없는 읽기를 자취에 남기지 않았다: ${exec.eventTrail}",
            )
            assertEquals(UnitState.RUNNING, unit.state)
        }
    }

    private companion object {
        const val ROBOT = "idle-robot"
        const val CONTAINER = "HU-1042"
        const val SOURCE = "OUT-07"
        const val DEST = "SEQ-IN-02"
        val TRANSPORT = TransportHandle("tr-1")

        /** 라운드 사이에 시계를 밀어도 다 읽은 값이 낡지 않을 만큼. */
        val MAX_AGE: Duration = Duration.ofMinutes(1)

        fun order() = JobOrder(
            jobOrderId = "WT-781",
            workMasterId = DeliverContainer.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E2,
            equipmentRequirements = listOf(
                EquipmentRequirement(SOURCE, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_CONTAINER to CONTAINER)),
                EquipmentRequirement(DEST, EquipmentUse.DESTINATION),
            ),
        )
    }
}

package dev.picasso.middleware

import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **포트가 던져도 한 실행의 일이다** — 라운드 진행([Middleware.pump])의 실행 단위 격리와, 닿지 못한 취소의 재전송(§15.237).
 *
 * 포트(설비 읽기·스냅숏·취소 전송)가 던지면 그 실행의 트레이스에 한 줄을 남기고 다음 라운드에 다시 본다. 던지는 실행 뒤에 만든 실행이
 * 그 실행이 던지는 동안 매 라운드 굶으면 안 된다. 그리고 취소는 «보냈다» 가 아니라 «닿았다» 를 기준으로 한다 — 보내지 못한 취소가
 * 조용히 사라지면 기체는 계속 움직이는데 응답은 멈췄다고 말한다.
 *
 * 막힘(응답 없는 엔드포인트가 기체마다 기한만큼 라운드를 늘림)은 벽시계와 스레드가 끼어 흔들리므로 여기서 재현하지 않는다.
 * 고치는 것은 «던지는 포트» 의 격리뿐이다.
 */
class PumpIsolationTest {

    private fun rackOrder(id: String, slot: String, bin: String, required: Evidence = Evidence.E2) = JobOrder(
        jobOrderId = id,
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = 1,
        requiredEvidence = required,
        equipmentRequirements = listOf(
            EquipmentRequirement(slot, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
            EquipmentRequirement(bin, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
        ),
    )

    /** 픽스처의 `pick_place` 는 취소 `NO` 다. 취소가 닿는 경로를 보려면 `YES` 인 사본이 필요하다([InDoubtTest] 와 같은 수법). */
    private fun cancellableProfile(): Path {
        val raw = Files.readString(PROFILE)
        val needle = "\"cancel_support\": \"NO\""
        check(raw.split(needle).size == 2) { "픽스처에 cancel_support NO 가 하나가 아니다" }
        val copy = Files.createTempFile("picasso-isolation-cancellable-", ".json")
        Files.writeString(copy, raw.replace(needle, "\"cancel_support\": \"YES\""))
        copy.toFile().deleteOnExit()
        return copy
    }

    private fun Middleware.Execution.unit() = units.single()
    private fun Middleware.Execution.settled() = physicalState.isSettled && active == null
    private fun Middleware.Execution.trailOf(kind: String) = eventTrail.filter { it.kind == kind }

    // ── 한 실행의 포트 호출이 던진다 — 설비 읽기와 기체 동기화

    /** 기체 둘. 실행 A 를 먼저 만든다 — 표와 동기화 순서에서 B 앞에 선다. */
    private inner class TwoRobots(snapshotThrowsFor: String? = null, snapshotArmed: Boolean = true) : AutoCloseable {
        val harness = Harness(mapOf(A to PROFILE, B to PROFILE))
        val cell = CellMimic(now = { harness.clock.now() })
        val throwing = ThrowingCellSignals(cell, setOf(SLOT_A))
        val snapshots: ThrowingSnapshotRobotPort? = snapshotThrowsFor?.let { ThrowingSnapshotRobotPort(ClientRobotPort(harness.client()), it, snapshotArmed) }
        val mw = Middleware(snapshots ?: ClientRobotPort(harness.client()), throwing, now = { harness.clock.now() })

        init {
            listOf(BIN_A, BIN_B, SLOT_A, SLOT_B).forEach { cell.program(it, PART) }
        }

        fun submit(id: String, slot: String, bin: String, robot: String): Middleware.Execution =
            assertIs<Middleware.Submission.Accepted>(mw.submit(rackOrder(id, slot, bin), robot)).execution

        fun drive(rounds: Int = 60, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다")
        }

        override fun close() = harness.close()
    }

    @Test
    fun `한 실행의 설비 호출이 던져도 다른 실행은 같은 라운드에 끝까지 간다`() {
        TwoRobots().use { w ->
            val a = w.submit("SEQ-A", SLOT_A, BIN_A, A)
            val b = w.submit("SEQ-B", SLOT_B, BIN_B, B)
            w.throwing.armed = true

            // A 의 검증 대기가 SLOT_A 를 읽다 던진다. 표에서 A 가 B 앞이므로 격리가 없으면 pump 가 던지고 B 는 굶는다.
            w.drive { b.settled() }

            assertTrue(w.throwing.throws > 0, "던지는 읽기가 한 번도 안 불렸다 — 시험이 격리를 보지 못한다")
            assertEquals(PhysicalState.PHYSICALLY_DONE, b.physicalState)
            assertEquals(Evidence.E2, b.unit().reached)
            // A 는 상태를 되돌리지 않은 채 검증 대기에 남고, 트레이스에 실패가 보인다.
            assertEquals(UnitState.VERIFYING, a.unit().state)
            assertFalse(a.settled())
            assertEquals(1, a.trailOf("PUMP_FAILED").size, a.eventTrail.toString())
            assertEquals(0, b.trailOf("PUMP_FAILED").size)
        }
    }

    @Test
    fun `같은 까닭의 실패는 라운드마다 되풀이해 적지 않는다`() {
        TwoRobots().use { w ->
            val a = w.submit("SEQ-A", SLOT_A, BIN_A, A)
            w.throwing.armed = true

            w.drive { w.throwing.throws >= 1 }
            val before = w.throwing.throws
            repeat(4) { w.mw.pump() }

            assertTrue(w.throwing.throws >= before + 4, "던지는 읽기가 라운드마다 다시 불려야 한다 — 다시 본다")
            assertEquals(1, a.trailOf("PUMP_FAILED").size, a.eventTrail.toString())
            assertTrue("IllegalStateException" in a.trailOf("PUMP_FAILED").single().detail, "까닭이 안 보인다")
        }
    }

    @Test
    fun `포트가 다시 답하면 던지던 실행이 처음부터 다시 보고 이어서 끝난다`() {
        TwoRobots().use { w ->
            val a = w.submit("SEQ-A", SLOT_A, BIN_A, A)
            w.throwing.armed = true
            w.drive { w.throwing.throws >= 1 }
            assertEquals(UnitState.VERIFYING, a.unit().state)

            w.throwing.armed = false
            w.drive { a.settled() }

            assertEquals(PhysicalState.PHYSICALLY_DONE, a.physicalState)
            assertEquals(Evidence.E2, a.unit().reached)
        }
    }

    @Test
    fun `기체 동기화가 던져도 다른 기체의 실행은 같은 라운드에 가고 그 기체는 못 본 것으로 둔다`() {
        TwoRobots(snapshotThrowsFor = A).use { w ->
            val a = w.submit("SEQ-A", SLOT_A, BIN_A, A)
            val b = w.submit("SEQ-B", SLOT_B, BIN_B, B)

            // 기체 A 의 스냅숏이 던진다. 동기화 순서에서 A 가 B 앞이므로 격리가 없으면 pump 가 던지고 B 는 아무것도 못 읽는다.
            w.drive { b.settled() }

            assertEquals(PhysicalState.PHYSICALLY_DONE, b.physicalState)
            assertEquals(1, a.trailOf("PUMP_FAILED").size, a.eventTrail.toString())
            assertTrue("robot=$A" in a.trailOf("PUMP_FAILED").single().detail, a.trailOf("PUMP_FAILED").single().detail)
            assertEquals(0, b.trailOf("PUMP_FAILED").size)
            assertTrue(assertNotNull(w.mw.view(B)).observable)
        }
    }

    @Test
    fun `스냅숏을 한 번 읽은 기체가 다음 라운드에 던지면 낡은 값을 관측한 값으로 두지 않는다`() {
        TwoRobots(snapshotThrowsFor = A, snapshotArmed = false).use { w ->
            w.submit("SEQ-A", SLOT_A, BIN_A, A)
            w.mw.pump()
            assertTrue(assertNotNull(w.mw.view(A)).observable, "첫 라운드는 읽혔어야 한다")

            assertNotNull(w.snapshots).armed = true
            w.mw.pump()

            assertFalse(assertNotNull(w.mw.view(A)).observable)
        }
    }

    @Test
    fun `한 번 성공한 자리가 다시 던지면 트레이스에 다시 적는다`() {
        TwoRobots(snapshotThrowsFor = A).use { w ->
            val a = w.submit("SEQ-A", SLOT_A, BIN_A, A)
            val snapshots = assertNotNull(w.snapshots)

            w.mw.pump()
            w.mw.pump()
            assertEquals(1, a.trailOf("PUMP_FAILED").size, "같은 까닭이 되풀이됐다")

            snapshots.armed = false
            w.mw.pump()
            snapshots.armed = true
            w.mw.pump()
            assertEquals(2, a.trailOf("PUMP_FAILED").size, "한 번 성공한 뒤의 실패가 첫 실패로 안 적혔다")
        }
    }

    // ── 취소 전송이 던진다 — 닿을 때까지 다시 보낸다

    private class OneRobot(profile: Path, wrap: (RobotPort) -> RobotPort = { it }) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to profile))
        val cell = CellMimic(now = { harness.clock.now() })
        val mw = Middleware(wrap(ClientRobotPort(harness.client())), cell, now = { harness.clock.now() })

        init {
            cell.program(SLOT, PART)
            cell.program(BIN, PART)
        }

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList

        fun drive(rounds: Int = 60, until: () -> Boolean) {
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

    @Test
    fun `취소 전송이 던지면 다음 라운드에 다시 보내 기체 태스크가 취소로 끝난다`() {
        lateinit var port: FlakyCancelRobotPort
        OneRobot(cancellableProfile(), wrap = { port = FlakyCancelRobotPort(it, failures = 1); port }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder("SEQ-1", SLOT, BIN), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.RUNNING, exec.unit().state)

            // 취소가 하위에 닿지 못한다 — 호출은 던지지 않는다. 받았다는 뜻이지 닿았다는 뜻이 아니다.
            assertTrue(w.mw.cancel(exec.executionId))
            assertEquals(1, port.attempts)
            assertEquals(0, port.delivered)
            assertEquals(1, exec.trailOf("CANCEL_UNSENT").size, exec.eventTrail.toString())

            w.drive { exec.settled() }
            assertEquals(2, port.attempts, "한 번 더 보냈어야 한다")
            assertEquals(1, port.delivered)
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertTrue(w.tasks().single().taskState.startsWith("CANCELLED"), w.tasks().single().taskState)
            val report = assertNotNull(w.mw.lastCancel(exec.executionId))
            assertEquals(SLOT, report.inProgressUnit)
            assertNull(report.refusal)
            assertEquals(1, exec.trailOf("CANCEL_RESENT").size, exec.eventTrail.toString())
        }
    }

    @Test
    fun `미확정이 조회로 풀린 직후의 취소 전송이 던져도 취소가 다시 나간다`() {
        lateinit var port: FlakyCancelRobotPort
        OneRobot(
            cancellableProfile(),
            wrap = { port = FlakyCancelRobotPort(LossyRobotPort(it, dropStartResponses = 1), failures = 1); port },
        ).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder("SEQ-1", SLOT, BIN), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)

            // 핸들이 없으니 요청만 적어 둔다. 조회가 핸들을 돌려주는 라운드에 취소가 나가는데 그 전송이 던진다.
            assertTrue(w.mw.cancel(exec.executionId))
            assertEquals(0, port.attempts)

            w.drive { exec.settled() }
            assertEquals(2, port.attempts, "던진 취소를 다시 보냈어야 한다")
            assertEquals(1, port.delivered)
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertTrue(w.tasks().single().taskState.startsWith("CANCELLED"), w.tasks().single().taskState)
            assertNull(assertNotNull(w.mw.lastCancel(exec.executionId)).refusal)
            assertEquals(1, exec.trailOf("CANCEL_UNSENT").size, exec.eventTrail.toString())
        }
    }

    @Test
    fun `취소가 끝내 닿지 않은 채 단위가 끝나면 응답이 닿지 못했다고 말한다`() {
        lateinit var port: FlakyCancelRobotPort
        // 픽스처의 pick_place 는 취소 NO — 닿았다면 거부(CANCEL_UNSUPPORTED)가 실렸을 자리다. 닿지 않았으니 이 층의 값이 실려야 한다.
        OneRobot(PROFILE, wrap = { port = FlakyCancelRobotPort(it); port }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder("SEQ-1", SLOT, BIN, Evidence.E0), ROBOT)).execution
            w.mw.pump()
            assertTrue(w.mw.cancel(exec.executionId))

            w.drive { exec.settled() }
            assertEquals(0, port.delivered)
            assertTrue(port.attempts >= 1, "한 번도 안 보냈다")
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals("SUCCEEDED", w.tasks().single().taskState)
            val report = assertNotNull(w.mw.lastCancel(exec.executionId))
            assertEquals("CANCEL_NOT_SENT", report.refusal)
            assertEquals(SLOT, report.stoppedAfter)
            assertNull(report.inProgressUnit, "중단된 단위는 없다 — 끝까지 갔다")
            assertEquals(1, exec.trailOf("CANCEL_UNSENT").size, "같은 까닭을 라운드마다 되풀이했다")
        }
    }

    @Test
    fun `이미 끝난 태스크에는 닿지 못한 취소를 다시 보내지 않는다`() {
        lateinit var port: FlakyCancelRobotPort
        OneRobot(PROFILE, wrap = { port = FlakyCancelRobotPort(it); port }).use { w ->
            w.cell.silence(SLOT) // 설비가 말이 없으니 하위가 끝낸 단위는 검증 대기에 선다
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder("SEQ-1", SLOT, BIN), ROBOT)).execution
            w.mw.pump()
            assertTrue(w.mw.cancel(exec.executionId))

            w.drive { exec.unit().state == UnitState.VERIFYING }
            val attempts = port.attempts
            w.drive { exec.settled() }

            assertEquals(attempts, port.attempts, "끝난 태스크에 취소를 다시 보냈다")
            assertEquals("CANCEL_NOT_SENT", assertNotNull(w.mw.lastCancel(exec.executionId)).refusal)
        }
    }

    @Test
    fun `하위가 취소를 거부하면 받은 것이다 — 다시 보내지 않는다`() {
        lateinit var port: FlakyCancelRobotPort
        OneRobot(PROFILE, wrap = { port = FlakyCancelRobotPort(it, failures = 0); port }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder("SEQ-1", SLOT, BIN, Evidence.E0), ROBOT)).execution
            w.mw.pump()
            assertTrue(w.mw.cancel(exec.executionId))

            w.drive { exec.settled() }
            assertEquals(1, port.attempts, "거부를 받고도 다시 보냈다")
            assertEquals("REJECTION_CODE_CANCEL_UNSUPPORTED", assertNotNull(w.mw.lastCancel(exec.executionId)).refusal)
            assertEquals(0, exec.trailOf("CANCEL_UNSENT").size)
        }
    }

    // ── 플릿 — 같은 규칙

    @Test
    fun `플릿 취소 전송이 던져도 다음 라운드에 다시 보내 운반이 취소로 끝난다`() {
        Harness(mapOf(FLEET_ROBOT to PROFILE)).use { harness ->
            val fleet = AmrFleetMimic(now = { harness.clock.now() })
            val cell = CellMimic(now = { harness.clock.now() }, live = { loc -> fleet.containersAt[loc]?.let { it to fleet.placedAt[loc] } })
            val flaky = FlakyCancelFleet(fleet, failures = 1)
            val mw = Middleware(ClientRobotPort(harness.client()), cell, flaky, now = { harness.clock.now() })
            fleet.place(SOURCE, CONTAINER)

            fun drive(until: () -> Boolean) {
                repeat(40) {
                    mw.pump()
                    if (until()) return
                    harness.advance(Duration.ofSeconds(10))
                    fleet.tick()
                }
                error("조건에 못 미쳤다")
            }

            val exec = assertIs<Middleware.Submission.Accepted>(
                mw.submit(
                    JobOrder(
                        jobOrderId = "WT-1",
                        workMasterId = DeliverContainer.WORK_MASTER,
                        version = 1,
                        requiredEvidence = Evidence.E2,
                        equipmentRequirements = listOf(
                            EquipmentRequirement(SOURCE, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_CONTAINER to CONTAINER)),
                            EquipmentRequirement(DEST, EquipmentUse.DESTINATION),
                        ),
                    ),
                    FLEET_ROBOT,
                ),
            ).execution
            drive { exec.transport != null }

            assertTrue(mw.cancel(exec.executionId))
            assertEquals(1, flaky.attempts)
            assertEquals(1, exec.trailOf("CANCEL_UNSENT").size, exec.eventTrail.toString())

            drive { exec.physicalState == PhysicalState.ABORTED }
            assertTrue(flaky.attempts >= 2, "한 번 더 보냈어야 한다")
            val report = assertNotNull(mw.lastCancel(exec.executionId))
            assertEquals(CONTAINER, report.inProgressUnit)
            assertNull(report.refusal)
        }
    }

    private companion object {
        val PROFILE: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()

        const val A = "hum-02"
        const val B = "hum-03"
        const val ROBOT = "hum-02"
        const val FLEET_ROBOT = "idle-robot"

        const val SLOT = "RACK-206.S01"
        const val BIN = "SEQ-IN-02.BIN-A"
        const val SLOT_A = "RACK-A.S01"
        const val SLOT_B = "RACK-B.S01"
        const val BIN_A = "BIN-A"
        const val BIN_B = "BIN-B"
        const val PART = "ENGINE-COVER-A"

        const val CONTAINER = "HU-1050"
        const val SOURCE = "OUT-08"
        const val DEST = "SEQ-IN-03"
    }
}

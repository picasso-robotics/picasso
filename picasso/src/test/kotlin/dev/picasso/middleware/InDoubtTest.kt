package dev.picasso.middleware

import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 결과 미확정(`IN_DOUBT`)과 해소 순서 — 보고서 13장, 17장 3·9번. 계획 단계 6.
 *
 * 요청은 하류에 닿았고 답만 잃었다. 미들웨어는 **다시 보내지 않는다** — 물리 작업이 둘이 될 수 있다. 대신 13.2 의 순서로
 * 푼다: ① 같은 참조로 기존 실행 조회(계약은 같은 핸들, 플릿은 같은 운반) → ② 설비 관측(잠정 완료) → ③ 운영자.
 * 조회가 있는 하류에서는 ①에서 풀리고 재실행은 0회다. 없는 하류에서는 운영자에게 가고, 재요청은 사람이 명시적으로 낸다(13.3).
 *
 * 로봇 쪽은 미믹(계약 ④ — 같은 `(task_id, revision)` 재전송이 같은 핸들), 플릿 쪽은 `AmrFleetMimic`(참조 멱등) 위에서 돌고,
 * 응답 유실은 그 사이의 선을 흉내내는 [LossyRobotPort]·[LossyFleet] 이 만든다.
 */
class InDoubtTest {

    // ── 로봇(계약 ④) — 조회가 있다

    private fun rackOrder() = JobOrder(
        jobOrderId = "SEQ-206",
        workMasterId = PrepareSequencedRack.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E2,
        equipmentRequirements = listOf(
            EquipmentRequirement(SLOT, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
            EquipmentRequirement(BIN, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
        ),
    )

    /**
     * [blind] 면 스냅숏을 못 읽는 기체다 — 미확정 태스크가 스냅숏에 안 보여 조회(`StartTask` 재전송)로만 풀 수 있다. [wrap] 은 계약
     * 클라이언트 바로 위의 더블이다.
     */
    private class RobotWorld(
        drop: Int,
        profile: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize(),
        blind: Boolean = false,
        wrap: (RobotPort) -> RobotPort = { it },
    ) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to profile))
        val cell = CellMimic(now = { harness.clock.now() })
        val port = LossyRobotPort(wrap(ClientRobotPort(harness.client())).let { if (blind) FaultBlindRobotPort(it) else it }, dropStartResponses = drop)
        val mw = Middleware(port, cell, now = { harness.clock.now() })

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

    private fun Middleware.Execution.unit() = units.single()
    private fun Middleware.Execution.settled() = physicalState.isSettled && active == null

    @Test
    fun `답을 잃으면 IN_DOUBT 이고, 같은 요청으로 조회해 이어서 추적한다 — 재실행 0회`() {
        RobotWorld(drop = 1, blind = true).use { w -> // 스냅숏이 안 열려야 조회가 푼다. 열리면 붙는다(아래 시험)
            w.cell.program(SLOT, PART)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()

            // 요청은 로봇에 닿았고(태스크가 있다) 답만 없다 — 미확정이고 상류에 그렇게 드러낸다.
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertEquals(PhysicalState.IN_DOUBT, exec.physicalState)
            assertEquals(1, w.tasks().size)
            val doubt = w.mw.pending().single()
            assertEquals(listOf(SLOT), doubt.inDoubtUnits)
            assertTrue(doubt.autoResolvesInDoubt, "계약 ④는 같은 요청 재전송이 조회다")

            // 다음 펌프 — 같은 (task_id, revision) 으로 다시 묻는다. 같은 핸들, 새 태스크 없음, 이어서 추적.
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(1, w.tasks().size, "IN_DOUBT 해소가 물리 작업을 다시 만들었다")
            assertEquals(2, w.port.starts, "조회는 한 번이면 된다")
            assertEquals(1, exec.unit().lookups)
            assertTrue(exec.unit().note!!.contains("resolved by client-reference lookup"), exec.unit().note)
            // 처음부터 되짚었으므로(WatchTask 0) 중간 전이가 유실되지 않았다 — 종착까지 도달해 E2 로 닫혔다(17장 9번).
            assertEquals(Evidence.E2, exec.unit().reached)
        }
    }

    @Test
    fun `조회가 유예 안에서 계속 안 되면 유예가 지난 뒤 운영자다 — 그래도 재실행은 없다`() {
        RobotWorld(drop = 1000, blind = true).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            val start = w.harness.clock.now()
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state)
            assertEquals(1, w.tasks().size, "조회 시도가 물리 작업을 만들었다")
            assertTrue(exec.unit().lookups > 3, "횟수가 아니라 유예 안의 시간으로 물어야 한다: ${exec.unit().lookups}")
            assertTrue(!w.harness.clock.now().isBefore(start.plus(exec.capability.inDoubtGrace)), "유예가 지나기 전에 운영자로 넘어갔다")
            assertTrue(exec.unit().note!!.contains("lookup unanswered"), exec.unit().note)
        }
    }

    @Test
    fun `1초 안의 짧은 끊김 뒤 링크가 돌아오면 미확정 유예 안에서 풀린다`() {
        // 출발의 응답과 조회 셋이 한꺼번에 사라진다(가상 시계는 안 밀렸다). 횟수로 재면 여기서 예산이 바닥나고, 시간으로 재면 유예가 남아 있다.
        RobotWorld(drop = 5, blind = true).use { w ->
            w.cell.program(SLOT, PART)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            repeat(4) { w.mw.pump() }
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertEquals(2, w.port.starts, "시험의 전제: 출발과 첫 조회의 응답을 잃었고 간격 안의 라운드는 묻지 않았다")

            w.harness.advance(Duration.ofSeconds(1))
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(1, w.tasks().size, "조회가 물리 작업을 다시 만들었다")
            assertEquals(Evidence.E2, exec.unit().reached)
        }
    }

    @Test
    fun `미확정 조회는 간격 안에서 다시 묻지 않고 간격이 지나면 묻는다`() {
        RobotWorld(drop = 1000, blind = true).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            w.mw.pump() // 첫 조회는 간격 없이 바로 나간다
            assertEquals(2, w.port.starts)

            repeat(5) { w.mw.pump() }
            assertEquals(2, w.port.starts, "간격 안인데 다시 물었다")
            w.harness.advance(Middleware.LOOKUP_INTERVAL.minusMillis(1))
            w.mw.pump()
            assertEquals(2, w.port.starts, "간격이 지나기 전에 다시 물었다")

            w.harness.advance(Duration.ofMillis(1))
            w.mw.pump()
            assertEquals(3, w.port.starts, "간격이 지났는데 안 물었다")
            assertEquals(2, exec.unit().lookups)
        }
    }

    @Test
    fun `스냅숏에 보이는 미확정 태스크에는 StartTask 없이 붙는다`() {
        RobotWorld(drop = 1).use { w ->
            w.cell.program(SLOT, PART)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)

            w.mw.pump()
            assertEquals(UnitState.RUNNING, exec.unit().state)
            assertEquals(1, w.port.starts, "스냅숏으로 붙을 수 있는데 StartTask 를 다시 보냈다")
            assertEquals(0, exec.unit().lookups)
            assertTrue(exec.unit().note!!.contains("snapshot"), exec.unit().note)
            assertEquals(exec.unit().taskId, exec.handle!!.taskId)

            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(1, w.tasks().size)
            assertEquals(Evidence.E2, exec.unit().reached, "붙은 태스크를 처음부터 되짚어 종료까지 봐야 한다")
        }
    }

    /** 리비전을 싣지 않는 포트 — 스냅숏에 태스크는 있고 리비전은 비어 있다(§15.238). */
    private class RevisionlessRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
        override fun snapshot(robotId: String): RobotSnapshot? = delegate.snapshot(robotId)?.copy(revisions = emptyMap())
    }

    /** 다른 명령자가 그 태스크를 고친 것처럼 스냅숏의 리비전을 하나씩 올려 답하는 포트(§15.238). */
    private class RevisionAheadRobotPort(private val delegate: RobotPort) : RobotPort by delegate {
        override fun snapshot(robotId: String): RobotSnapshot? =
            delegate.snapshot(robotId)?.let { s -> s.copy(revisions = s.revisions.mapValues { it.value + 1 }) }
    }

    @Test
    fun `리비전을 싣지 않는 포트에서도 한 리비전으로만 보낸 미확정 태스크에는 StartTask 없이 붙는다`() {
        RobotWorld(drop = 1, wrap = { RevisionlessRobotPort(it) }).use { w ->
            w.cell.program(SLOT, PART)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)

            w.mw.pump()
            assertEquals(UnitState.RUNNING, exec.unit().state, exec.unit().note)
            assertEquals(1, w.port.starts, "한 리비전으로만 보낸 태스크인데 붙지 않고 StartTask 를 다시 보냈다")
            assertTrue(exec.unit().note!!.contains("snapshot attach"), exec.unit().note)

            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(1, w.tasks().size)
        }
    }

    @Test
    fun `리비전을 싣지 않는 포트에서 갱신을 보낸 미확정 단위는 붙지 않고 조회로 푼다`() {
        // 출발과 갱신의 답을 둘 다 잃는다. 기체에 태스크는 보이나 어느 리비전인지 포트가 싣지 않는다 — 갱신이 닿았는지 모른다.
        RobotWorld(drop = 2, wrap = { RevisionlessRobotPort(it) }).use { w ->
            w.cell.program(SLOT, PART)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder().copy(version = 2), ROBOT))
            assertEquals(2, w.port.starts, "시험의 전제: 미확정 단위에 갱신이 나갔다")
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)

            w.mw.pump()
            assertTrue(exec.unit().note?.contains("snapshot attach") != true, "갱신이 닿았는지 모르는데 스냅숏으로 붙었다: ${exec.unit().note}")

            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertTrue(exec.unit().note!!.contains("resolved by client-reference lookup"), exec.unit().note)
            assertEquals(3, w.port.starts)
            assertEquals(1, w.tasks().size)
        }
    }

    @Test
    fun `기체가 단위보다 높은 리비전을 들면 붙지도 묻지도 않고 운영자 보류에 둔다`() {
        RobotWorld(drop = 1, wrap = { RevisionAheadRobotPort(it) }).use { w ->
            w.cell.program(SLOT, PART)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)

            w.mw.pump()
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state, exec.unit().note)
            assertEquals(1, w.port.starts, "낮은 리비전의 StartTask 로 다시 물었다")
            assertEquals(null, exec.handle, "다른 명령자가 고친 태스크에 붙었다")
            assertTrue(exec.eventTrail.any { it.kind == Middleware.DOUBT_REVISION_AHEAD }, exec.eventTrail.toString())
            assertEquals(1, w.tasks().size)
        }
    }

    /** 픽스처의 `pick_place` 는 취소 `NO` 다. 취소가 닿는 경로를 보려면 `YES` 인 사본이 필요하다(SequencingRackTest 와 같은 수법). */
    private fun cancellableProfile(): Path {
        val raw = Files.readString(Path.of("..", "profile", "fixtures", "minimal.json").normalize())
        val needle = "\"cancel_support\": \"NO\""
        check(raw.split(needle).size == 2) { "픽스처에 cancel_support NO 가 하나가 아니다" }
        val copy = Files.createTempFile("picasso-doubt-cancellable-", ".json")
        Files.writeString(copy, raw.replace(needle, "\"cancel_support\": \"YES\""))
        copy.toFile().deleteOnExit()
        return copy
    }

    /** [cancellableProfile] 에서 `pick_place` 가 600초 걸리는 사본 — 운영자 보류에 든 뒤에도 기체의 태스크가 아직 돈다. */
    private fun longCancellableProfile(): Path {
        val raw = Files.readString(cancellableProfile())
        val needle = "{ \"skill_type\": \"pick_place\", \"seconds\": 45"
        check(raw.split(needle).size == 2) { "픽스처에 pick_place 45초가 하나가 아니다" }
        val copy = Files.createTempFile("picasso-doubt-long-", ".json")
        Files.writeString(copy, raw.replace(needle, "{ \"skill_type\": \"pick_place\", \"seconds\": 600"))
        copy.toFile().deleteOnExit()
        return copy
    }

    @Test
    fun `미확정 중의 취소는 핸들이 생기는 순간 하류에 닿는다 — 재실행 없이 중단으로 끝난다`() {
        RobotWorld(drop = 1, profile = cancellableProfile()).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)

            // 답을 못 받은 채로 취소가 온다 — 보낼 핸들이 없다. 요청만 적어 둔다.
            assertTrue(w.mw.cancel(exec.executionId))
            assertEquals(PhysicalState.CANCELING, exec.physicalState)

            w.drive { exec.settled() }
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(1, w.tasks().size, "취소 경로가 물리 작업을 다시 만들었다")
            val report = w.mw.lastCancel(exec.executionId)!!
            assertEquals(null, report.refusal)
            assertEquals(SLOT, report.inProgressUnit)
            assertTrue(w.tasks().single().taskState.startsWith("CANCELLED"), w.tasks().single().taskState)
        }
    }

    @Test
    fun `미확정 중에 건 취소는 조회가 안 돼도 기체 태스크를 멈춘다`() {
        // 조회(StartTask 재전송)는 전부 응답을 잃는다. 스냅숏에 태스크가 보이면 그 핸들로 취소가 나가야 한다.
        RobotWorld(drop = 10, profile = cancellableProfile()).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertTrue(w.mw.cancel(exec.executionId))

            w.drive { exec.settled() }
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(1, w.tasks().size)
            assertTrue(w.tasks().single().taskState.startsWith("CANCELLED"), "취소가 기체 태스크에 안 닿았다: ${w.tasks().single().taskState}")
            assertEquals(null, w.mw.lastCancel(exec.executionId)!!.refusal)
        }
    }

    @Test
    fun `스냅숏도 조회도 안 되면 취소가 걸린 실행을 중단으로 닫지 않고 운영자 보류에 둔다`() {
        // 취소가 갈 핸들이 없고 키로 지은 핸들의 취소도 닿지 않는다. 중단(`ABORTED`)으로 닫으면 기체가 그 태스크를 하는 동안 실행이
        // 기체를 내준다.
        RobotWorld(drop = 1000, profile = cancellableProfile(), blind = true, wrap = { FlakyCancelRobotPort(it) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertTrue(w.mw.cancel(exec.executionId))

            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }
            repeat(3) { w.mw.pump() }

            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState, "취소가 닿지 않았는데 실행이 닫혔다")
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state)
            assertEquals(null, w.mw.lastCancel(exec.executionId), "닿지 않은 취소에 취소 응답이 나갔다")
            assertTrue(exec.physicalState.occupiesRobot, "실행이 기체를 내줬다")
            assertTrue(exec.eventTrail.any { it.kind == Middleware.CANCEL_UNSENT }, "닿지 못한 취소가 트레이스에 없다")
            assertEquals(true, w.mw.pending().last().operatorRequired)
        }
    }

    @Test
    fun `운영자 보류에 둔 취소는 운영자가 판단하면 닿지 못했다는 사유를 싣고 끝난다`() {
        RobotWorld(drop = 1000, profile = cancellableProfile(), blind = true, wrap = { FlakyCancelRobotPort(it) }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertTrue(w.mw.cancel(exec.executionId))
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, SLOT, OperatorDecision.CONFIRM_DONE, OPERATOR))
            w.drive { exec.settled() }

            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            val report = w.mw.lastCancel(exec.executionId)!!
            assertEquals(Middleware.CANCEL_NOT_SENT, report.refusal)
            assertEquals(listOf(SLOT), report.completedUnits, "운영자가 확인한 단위가 완료로 실려야 한다")
        }
    }

    @Test
    fun `스냅숏도 조회도 안 되는 미확정 단위의 취소는 키로 지은 핸들로 기체에 닿는다 — 재전송 없이 중단으로 끝난다`() {
        RobotWorld(drop = 1000, profile = cancellableProfile(), blind = true).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertTrue(w.mw.cancel(exec.executionId))

            w.mw.pump()
            assertEquals(UnitState.RUNNING, exec.unit().state, "기체가 받은 취소의 핸들로 붙지 않았다")
            assertEquals(exec.unit().taskId, exec.handle!!.taskId)
            assertTrue(exec.unit().note!!.contains("keyed cancel"), exec.unit().note)
            assertEquals(1, exec.eventTrail.count { it.kind == Middleware.DOUBT_ATTACHED }, exec.eventTrail.toString())

            w.drive { exec.settled() }
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(1, w.port.starts, "취소하려고 StartTask 를 다시 보냈다")
            assertEquals(1, w.tasks().size)
            assertTrue(w.tasks().single().taskState.startsWith("CANCELLED"), w.tasks().single().taskState)
            val report = w.mw.lastCancel(exec.executionId)!!
            assertEquals(null, report.refusal)
            assertEquals(SLOT, report.inProgressUnit)
        }
    }

    @Test
    fun `스냅숏이 안 열린 미확정 단위의 키 취소를 하류가 거절해도 붙어 끝까지 따라간다 — 거절이 응답에 있다`() {
        RobotWorld(drop = 1000, blind = true).use { w -> // 픽스처의 pick_place 는 취소 NO
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder().copy(requiredEvidence = Evidence.E0), ROBOT)).execution
            w.mw.pump()
            assertTrue(w.mw.cancel(exec.executionId))
            w.mw.pump()
            assertEquals(UnitState.RUNNING, exec.unit().state, "취소를 거부한 기체는 그 태스크를 안다 — 붙어야 한다")

            w.drive { exec.settled() }
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(1, w.port.starts)
            val report = w.mw.lastCancel(exec.executionId)!!
            assertEquals("REJECTION_CODE_CANCEL_UNSUPPORTED", report.refusal)
            assertEquals(SLOT, report.stoppedAfter)
            assertEquals("SUCCEEDED", w.tasks().single().taskState)
        }
    }

    @Test
    fun `기체가 모르는 태스크의 키 취소는 쉬고 있다는 증거가 아니다 — 재전송 없이 운영자 보류다`() {
        // 원 요청이 기체에 닿지 않았다. 키로 지은 핸들의 취소에 기체는 NOT_FOUND 로 답한다.
        lateinit var cancels: FlakyCancelRobotPort
        RobotWorld(
            drop = 0,
            profile = cancellableProfile(),
            blind = true,
            wrap = { FlakyCancelRobotPort(UnsentStartRobotPort(it), failures = 0).also { p -> cancels = p } },
        ).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertTrue(w.mw.cancel(exec.executionId))

            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state)
            assertTrue(cancels.delivered > 0, "키로 지은 핸들의 취소가 안 나갔다")
            assertEquals(1, w.port.starts, "취소가 걸렸는데 조회로 StartTask 를 다시 보냈다")
            assertEquals(0, exec.unit().lookups)
            assertTrue(w.tasks().isEmpty(), w.tasks().toString())
            assertEquals(null, w.mw.lastCancel(exec.executionId), "닿지 않은 취소에 취소 응답이 나갔다")
            assertTrue(exec.unit().note!!.contains("keyed cancel not accepted"), exec.unit().note)
            assertTrue(exec.eventTrail.any { it.kind == Middleware.CANCEL_UNSENT }, exec.eventTrail.toString())
        }
    }

    @Test
    fun `키로 보내는 취소는 간격 안에서 다시 보내지 않고 간격이 지나면 보낸다`() {
        lateinit var cancels: FlakyCancelRobotPort
        RobotWorld(
            drop = 0,
            profile = cancellableProfile(),
            blind = true,
            wrap = { FlakyCancelRobotPort(UnsentStartRobotPort(it), failures = 0).also { p -> cancels = p } },
        ).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertTrue(w.mw.cancel(exec.executionId))
            w.mw.pump()
            assertEquals(1, cancels.attempts)

            repeat(5) { w.mw.pump() }
            w.harness.advance(Middleware.LOOKUP_INTERVAL.minusMillis(1))
            w.mw.pump()
            assertEquals(1, cancels.attempts, "간격이 지나기 전에 다시 보냈다")

            w.harness.advance(Duration.ofMillis(1))
            w.mw.pump()
            assertEquals(2, cancels.attempts, "간격이 지났는데 안 보냈다")
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
        }
    }

    @Test
    fun `운영자 보류에 둔 취소는 스냅숏에 태스크가 보이면 키로 다시 보내고 보류는 그대로 둔다`() {
        lateinit var link: LinkDownRobotPort
        RobotWorld(drop = 1000, profile = longCancellableProfile(), wrap = { LinkDownRobotPort(it).also { p -> link = p } }).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            link.down = true // 스냅숏도 취소도 안 닿는다
            assertTrue(w.mw.cancel(exec.executionId))
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }
            assertEquals(1, exec.eventTrail.count { it.kind == Middleware.CANCEL_UNSENT }, exec.eventTrail.toString())
            assertEquals("RUNNING", w.tasks().single().taskState, "시험의 전제: 보류에 들 때 기체의 태스크가 아직 돈다")

            link.down = false
            w.harness.advance(Middleware.LOOKUP_INTERVAL) // 보류에 든 라운드에 키 취소를 한 번 보냈다
            w.mw.pump()
            assertEquals(1, exec.eventTrail.count { it.kind == Middleware.CANCEL_RESENT }, "스냅숏에 보이는 태스크에 취소를 다시 안 보냈다")
            assertTrue(w.tasks().single().taskState.startsWith("CANCEL"), w.tasks().single().taskState)
            assertTrue(exec.unit().note!!.contains("held cancel re-sent by key"), exec.unit().note)

            repeat(3) {
                w.harness.advance(Duration.ofSeconds(10))
                w.mw.pump()
            }
            assertEquals(1, exec.eventTrail.count { it.kind == Middleware.CANCEL_RESENT }, "닿은 취소를 또 보냈다")
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState, "보류가 풀렸다")
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state)
            assertEquals(null, w.mw.lastCancel(exec.executionId))

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, SLOT, OperatorDecision.CONFIRM_DONE, OPERATOR))
            w.drive { exec.settled() }
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(null, w.mw.lastCancel(exec.executionId)!!.refusal, "닿은 취소를 닿지 못했다고 말했다")
        }
    }

    /** 도는 단위의 기체 연결을 끊고 끊김 판정(`LINK_BROKEN`)까지 간 뒤 취소를 건다. 끊긴 동안 취소는 기체에 안 닿는다. */
    private fun cancelOverBrokenLink(w: RobotWorld, link: LinkDownRobotPort): Middleware.Execution {
        val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder(), ROBOT)).execution
        w.mw.pump()
        assertEquals(UnitState.RUNNING, exec.unit().state)
        link.down = true
        w.harness.advance(Duration.ofSeconds(35)); w.mw.pump()
        assertTrue(exec.linkBroken, "시험의 전제: 끊김으로 판정했다")
        assertTrue(w.mw.cancel(exec.executionId))
        assertEquals(PhysicalState.CANCELING, exec.physicalState)
        w.mw.pump() // 닿지 않은 취소를 이 라운드가 처음 본다 — 유예는 여기서부터다
        return exec
    }

    @Test
    fun `끊긴 기체에 건 취소가 유예 안에 닿지 않으면 운영자 보류에 두고 기체를 내주지 않는다`() {
        lateinit var link: LinkDownRobotPort
        RobotWorld(drop = 0, profile = longCancellableProfile(), wrap = { LinkDownRobotPort(it).also { p -> link = p } }).use { w ->
            val exec = cancelOverBrokenLink(w, link)

            w.harness.advance(Duration.ofSeconds(50)); w.mw.pump()
            assertEquals(UnitState.RUNNING, exec.unit().state, "유예 안에 보류로 넘겼다")
            assertEquals(PhysicalState.CANCELING, exec.physicalState)

            w.harness.advance(Duration.ofSeconds(15)); w.mw.pump()
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state, "닿지 않은 취소가 시한 없이 CANCELING 에 머문다")
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState)
            assertTrue(exec.eventTrail.any { it.kind == Middleware.CANCEL_UNSENT && it.detail.contains("연결이 끊긴 채") }, exec.eventTrail.toString())
            assertTrue(exec.unit().note!!.contains("link broken since"), exec.unit().note)
            assertEquals(exec.unit().unitId, w.mw.incidents().singleOrNull()?.unitId, "보류가 인시던트로 봉해지지 않았다")
            assertEquals(PhysicalState.OPERATOR_HOLD, w.mw.pending().last().physicalState, "상류에 운영자 보류를 안 알렸다")

            repeat(3) {
                w.harness.advance(Duration.ofSeconds(10))
                w.mw.pump()
            }
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState, "기체가 멈췄는지 모르는 채 실행을 닫았다")
            assertEquals(null, w.mw.lastCancel(exec.executionId))

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, SLOT, OperatorDecision.CONFIRM_DONE, OPERATOR))
            w.drive { exec.settled() }
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(Middleware.CANCEL_NOT_SENT, w.mw.lastCancel(exec.executionId)!!.refusal, "닿지 못한 취소를 닿았다고 말했다")
        }
    }

    @Test
    fun `끊긴 기체의 취소가 유예 안에 닿으면 보류 없이 따라가 중단으로 끝난다`() {
        lateinit var link: LinkDownRobotPort
        RobotWorld(drop = 0, profile = longCancellableProfile(), wrap = { LinkDownRobotPort(it).also { p -> link = p } }).use { w ->
            val exec = cancelOverBrokenLink(w, link)

            // 스냅숏은 여전히 못 읽고 끊김 판정도 그대로인데 취소만 닿는다.
            w.harness.advance(Duration.ofSeconds(50))
            link.cancelThrough = true
            w.mw.pump()
            assertTrue(exec.eventTrail.any { it.kind == Middleware.CANCEL_RESENT }, exec.eventTrail.toString())
            assertTrue(exec.linkBroken, "시험의 전제: 끊김 판정이 그대로다")

            w.harness.advance(Duration.ofSeconds(15)); w.mw.pump()
            assertTrue(exec.units.none { it.state == UnitState.OPERATOR_HOLD }, "닿은 취소를 운영자 보류로 넘겼다")
            assertEquals(PhysicalState.CANCELING, exec.physicalState)

            link.down = false
            w.drive { exec.settled() }
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            assertEquals(1, w.tasks().size, "끊김이 재실행을 만들었다")
            assertEquals(null, w.mw.lastCancel(exec.executionId)!!.refusal)
        }
    }

    @Test
    fun `미확정 중의 취소를 하류가 거절하면 그 단위는 끝까지 가고 — 거절과 멈춘 경계가 응답에 있다`() {
        RobotWorld(drop = 1).use { w -> // 픽스처의 pick_place 는 취소 NO
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rackOrder().copy(requiredEvidence = Evidence.E0), ROBOT)).execution
            w.mw.pump()
            assertTrue(w.mw.cancel(exec.executionId))

            w.drive { exec.settled() }
            assertEquals(PhysicalState.ABORTED, exec.physicalState)
            val report = w.mw.lastCancel(exec.executionId)!!
            assertEquals("REJECTION_CODE_CANCEL_UNSUPPORTED", report.refusal)
            assertEquals(null, report.inProgressUnit, "중단된 단위는 없다 — 끝까지 갔다")
            assertEquals(SLOT, report.stoppedAfter)
            assertEquals(listOf(SLOT), report.completedUnits)
            assertEquals("SUCCEEDED", w.tasks().single().taskState)
        }
    }

    // ── 플릿 — 조회가 있는 플릿과 없는 플릿

    private fun deliverOrder() = JobOrder(
        jobOrderId = "WT-790",
        workMasterId = DeliverContainer.WORK_MASTER,
        version = 1,
        requiredEvidence = Evidence.E2,
        equipmentRequirements = listOf(
            EquipmentRequirement(SOURCE, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_CONTAINER to CONTAINER)),
            EquipmentRequirement(DEST, EquipmentUse.DESTINATION),
        ),
    )

    private class FleetWorld(drop: Int, lookup: ExecutionLookup) : AutoCloseable {
        val harness = Harness(mapOf("idle-robot" to Path.of("..", "profile", "fixtures", "minimal.json").normalize()))
        val fleet = AmrFleetMimic(now = { harness.clock.now() })
        val cell = CellMimic(now = { harness.clock.now() }, live = { loc -> fleet.containersAt[loc]?.let { it to fleet.placedAt[loc] } })
        val mw = Middleware(ClientRobotPort(harness.client()), cell, LossyFleet(fleet, drop, lookup), now = { harness.clock.now() })

        init {
            fleet.place(SOURCE, CONTAINER)
        }

        fun drive(rounds: Int = 12, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(10))
                fleet.tick()
            }
            error("조건에 못 미쳤다: 세계=${fleet.containersAt}")
        }

        override fun close() = harness.close()
    }

    @Test
    fun `참조로 찾아 주는 플릿이면 같은 운반을 이어서 추적한다`() {
        FleetWorld(drop = 1, lookup = ExecutionLookup.CLIENT_REFERENCE).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(deliverOrder(), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertEquals(1, w.fleet.dispatches)

            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(1, w.fleet.dispatches, "같은 참조가 새 운반을 만들었다")
            assertEquals(Evidence.E2, exec.unit().reached)
        }
    }

    @Test
    fun `조회가 없는 플릿에서 용기가 도착해 있으면 잠정 완료로 운영자에게 — 확인하면 E2 완료`() {
        FleetWorld(drop = 1, lookup = ExecutionLookup.NONE).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(deliverOrder(), ROBOT)).execution
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            assertEquals(false, w.mw.pending().single().autoResolvesInDoubt, "이 하류에서는 IN_DOUBT 가 자동으로 안 풀린다 — 상류가 알아야 한다")

            // 플릿은 사실 접수했고 운반을 마친다. 우리는 그것을 물을 수 없고 — 설비가 본다(12.3 셋째 행).
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }
            assertEquals(CONTAINER, w.fleet.containersAt[DEST])
            assertEquals(1, w.fleet.dispatches, "조회가 없는데 다시 보냈다 — 물리 작업이 둘이 될 뻔했다")
            assertEquals(Verification.MATCHED, exec.unit().verification)
            assertTrue(exec.unit().note!!.contains("provisional done"), exec.unit().note)
            assertTrue(exec.unit().note!!.contains("no client-reference lookup"), exec.unit().note)

            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, CONTAINER, OperatorDecision.CONFIRM_DONE, OPERATOR))
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(Evidence.E2, exec.unit().reached)
            assertEquals(1, w.fleet.dispatches)
        }
    }

    @Test
    fun `조회가 없고 설비도 말이 없으면 운영자에게 — 재요청은 사람이 명시적으로 낸다`() {
        FleetWorld(drop = 1, lookup = ExecutionLookup.NONE).use { w ->
            w.cell.silence(DEST)
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(deliverOrder(), ROBOT)).execution
            w.drive { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            assertEquals(1, w.fleet.dispatches, "자동 재실행이 있었다")
            assertTrue(exec.unit().note!!.contains("no automatic re-execution"), exec.unit().note)
            assertEquals(Verification.NOT_REQUESTED, exec.unit().verification)

            // 사람이 미실행을 확인했다고 치고 재요청 — 새 참조다. 이것만이 명령 재시도다(13.3).
            w.cell.program(DEST, CONTAINER) // 이번에는 설비가 본다
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, CONTAINER, OperatorDecision.REWORK, OPERATOR))
            w.drive { exec.settled() }
            assertEquals(2, w.fleet.dispatches)
            assertTrue(exec.unit().taskId.endsWith("@r1"), exec.unit().taskId)
        }
    }

    private companion object {
        /** 판단하는 사람 — 운영자 판단은 사람만 낸다(ADR 47). */
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)

        const val ROBOT = "hum-02"
        const val SLOT = "RACK-206.S01"
        const val BIN = "SEQ-IN-02.BIN-A"
        const val PART = "ENGINE-COVER-A"

        const val CONTAINER = "HU-1050"
        const val SOURCE = "OUT-08"
        const val DEST = "SEQ-IN-03"
    }
}

package dev.picasso.middleware

import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.harness.Harness
import dev.picasso.middleware.mission.Activation
import dev.picasso.middleware.mission.InMemoryMissionCatalog
import dev.picasso.middleware.mission.MissionFixtures
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **시도된 `pick_place` 실패의 재작업**(§15.323) — 보류가 아닌, 든 채 실패해 `FAILED` 로 끝난 랙을 사람이 재작업할 때의 물리 관문과 첫 발행 직전 재검사.
 *
 * 쥐었다 실패한 단위는 물건이 손에 남았거나 목적지에 놓였을 수 있다. 그래서 받는 조건이 접수 관문보다 엄격하다 — 기체 스냅숏의 손이 빈손이고,
 * 목적지 선언이 `REQUIRED` 이고 설비가 비었다고 보며, 출발 자리에 그 자재가 있다고 볼 때만이다. 미믹은 든 채 실패한 뒤 손을 비우지 않으므로 «사람이
 * 현장에서 손을 비웠다» 는 스냅숏의 손만 바꾸는 대역([Hand])이 흉내 낸다.
 */
class ReworkHandlingTest {

    /**
     * 스냅숏의 손만 바꾸는 포트. [kind] 가 널이면 발신자의 손 그대로다. [blind] 면 스냅숏을 못 읽은 것처럼 널을 주고(미리 읽는 포트의 «읽는 중»),
     * [noRevisions] 면 태스크의 리비전을 싣지 않으며(옛 발신자 포트 — 재부착 대신 같은 태스크 id 의 재전송), [faults] 가 있으면 그 결함을 싣는다.
     */
    private class Hand(private val delegate: RobotPort) : RobotPort by delegate {
        var kind: HoldKind? = null
        var blind = false
        var noRevisions = false
        var faults: List<Fault>? = null
        override fun snapshot(robotId: String): RobotSnapshot? = if (blind) null else delegate.snapshot(robotId)?.let { s ->
            var out = kind?.let { s.copy(hold = HoldState.newBuilder().setKind(it).build()) } ?: s
            if (noRevisions) out = out.copy(revisions = emptyMap())
            faults?.let { out = out.copy(faults = it) }
            out
        }
    }

    private class World(destination: String = "REQUIRED", source: String = "OPTIONAL") : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to PRECOND))
        val cell = CellMimic(now = { harness.clock.now() })
        val catalog = InMemoryMissionCatalog(now = { harness.clock.now() })
        val retired = mutableMapOf<String, String>()
        val hand = Hand(ClientRobotPort(harness.client()))
        val mw = middleware()

        fun middleware() = Middleware(hand, cell, now = { harness.clock.now() }, missions = catalog, retirements = RetirementPort { retired.toMap() })

        init {
            val activated = catalog.activate(
                MissionFixtures.prepareSequencedRack(sourceObservation = source, destinationObservation = destination),
                MissionFixtures.SIGNALS,
                FloorOwnership.None,
                MissionFixtures.SITE_SKILLS,
            )
            assertIs<Activation.Activated>(activated, "시험 정의가 서지 않았다: $activated")
            cell.program(BIN_A, MATERIAL)
            cell.empty(RACK)
        }

        fun tasks(): List<String> = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList.map { it.taskId }

        fun drive(rounds: Int = 200, mw: Middleware = this.mw, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(1))
                Thread.sleep(40)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다: tasks=${tasks()}")
        }

        /** 쥔 채 실패해(`SKILL_EXECUTION_FAILED`) `FAILED` 로 끝난 랙 하나. 인시던트가 열리고 손은 발신자에 든 채로 남는다. */
        fun failedHolding(): Middleware.Execution {
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(rack(), ROBOT)).execution
            val unit = exec.units.single()
            drive { unit.hold.kind == HoldKind.HOLD_KIND_HOLDING }
            harness.oracle.forceFault(
                ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType("SKILL_EXECUTION_FAILED").setTaskId(unit.taskId).build(),
            )
            drive { exec.physicalState.isSettled && exec.active == null }
            assertEquals(PhysicalState.FAILED to UnitState.FAILED, exec.physicalState to unit.state, "이 시험이 보려는 경우가 아니다")
            assertTrue(unit.everHeld && unit.taskId.isNotEmpty())
            assertEquals(HoldKind.HOLD_KIND_HOLDING, hand.snapshot(ROBOT)?.hold?.kind, "미믹이 실패에 손을 비웠다 — 전제가 깨졌다")
            assertEquals(1, mw.incidents().size)
            return exec
        }

        override fun close() = harness.close()
    }

    /** 거부가 아무것도 바꾸지 않았는가 — 단위·태스크·인시던트·판단 기록·작업 응답. */
    private fun World.assertUnchanged(exec: Middleware.Execution, tasks: List<String>, responses: Int) {
        val unit = exec.units.single()
        assertEquals(Triple(0, UnitState.FAILED, PhysicalState.FAILED), Triple(unit.attempt, unit.state, exec.physicalState))
        assertEquals(tasks, tasks())
        assertTrue(mw.incidents().all { it.resolution == null }, "거부가 판단을 인시던트에 붙였다")
        assertTrue(mw.judgmentRecord(exec.executionId)!!.units.all { it.decision == null }, "거부가 판단 기록을 바꿨다")
        assertEquals(responses, mw.responses().size, "거부가 작업 응답을 냈다")
    }

    private fun World.refused(exec: Middleware.Execution, expected: String): String {
        val refused = assertIs<ResolveOutcome.Refused>(mw.resolve(exec.executionId, exec.units.single().unitId, OperatorDecision.REWORK, OPERATOR))
        assertTrue(expected in refused.reason, refused.reason)
        return refused.reason
    }

    @Test
    fun `든 채 끝난 pick_place 실패는 손이 든 채이거나 모르면 재작업을 받지 않고 아무것도 안 바뀐다`() {
        World().use { w ->
            val exec = w.failedHolding()
            val tasks = w.tasks()
            val responses = w.mw.responses().size

            w.refused(exec, "빈손이 아니다")
            w.assertUnchanged(exec, tasks, responses)
            val trail = exec.eventTrail.last { it.kind == MiddlewareCodes.REWORK_REFUSED }
            assertTrue("hand=HOLD_KIND_HOLDING" in trail.detail, "거부 자취에 손 종류가 없다: ${trail.detail}")

            w.hand.kind = HoldKind.HOLD_KIND_NOT_OBSERVABLE
            w.refused(exec, "손을 말하지 않는다")
            w.assertUnchanged(exec, tasks, responses)
            assertTrue("hand=unknown" in exec.eventTrail.last { it.kind == MiddlewareCodes.REWORK_REFUSED }.detail)

            // 스냅숏을 아직 못 읽었다 — 접수 때는 모름도 거부다.
            w.hand.blind = true
            w.refused(exec, "아직 못 읽었다")
            w.assertUnchanged(exec, tasks, responses)
            w.hand.blind = false

            // 후보에는 오른다 — 후보는 메모리 조건만 본다. 손은 판단 때 읽는다.
            val unitId = exec.units.single().unitId
            assertEquals(
                listOf(ReworkCandidate(exec.executionId, unitId, 0, listOf(unitId), ReworkKind.ATTEMPTED_HANDLING, unitId)),
                w.mw.reworkCandidates(),
            )
        }
    }

    @Test
    fun `빈손이고 출발에 자재가 있고 REQUIRED 목적지가 비었으면 받아 r1 로 내고 판단이 제 인시던트에 근거와 함께 붙는다`() {
        World().use { w ->
            val exec = w.failedHolding()
            val unit = exec.units.single()
            val opened = w.mw.incidents().single()
            assertEquals(false, opened.unresolved, "시도된 실패의 인시던트는 보류가 아니다")
            val before = w.mw.responses().size

            w.hand.kind = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, unit.unitId, OperatorDecision.REWORK, OPERATOR))
            assertEquals(PhysicalState.RUNNING, exec.physicalState)
            val sent = w.mw.responses().drop(before)
            assertEquals(listOf(PhysicalState.RUNNING), sent.map { it.physicalState }, "RUNNING 작업 응답 하나")
            assertEquals(Triple(1, UnitState.PENDING, ""), Triple(unit.attempt, unit.state, unit.taskId))
            assertTrue(unit.heldInEarlierAttempt, "앞 시도에서 쥔 사실이 사라졌다 — 재할당이 반쯤 한 일을 못 거른다")

            val decision = assertNotNull(w.mw.judgmentRecord(exec.executionId)!!.units.single().decision)
            assertEquals(OperatorDecision.REWORK to 0, decision.decision to decision.attempt)
            val basis = assertNotNull(decision.reworkBasis)
            assertEquals(
                ReworkBasis(
                    robotId = ROBOT,
                    hold = ReworkHold(HoldKind.HOLD_KIND_EMPTY),
                    source = ReworkSlot(BIN_A, occupied = true, identity = MATERIAL),
                    destination = ReworkSlot(RACK, occupied = false),
                    readAt = basis.readAt,
                ),
                basis,
            )
            assertEquals(opened.origin, decision.incident, "판단이 제 사건의 정체성을 안 실었다")

            // 판단은 제 인시던트에 붙는다 — `unresolved: false` 인 시도 실패 인시던트에 `REWORK`(판 9 의 새 값 조합).
            val resolution = assertNotNull(w.mw.incidents().single().resolution)
            assertEquals(Triple(IncidentDecision.REWORK, OPERATOR, basis), Triple(resolution.decision, resolution.decidedBy, resolution.reworkBasis))
            val line = LedgerExport.incidents(w.mw.incidents())
            assertTrue(
                "\"reworkBasis\":{\"robotId\":\"$ROBOT\",\"hold\":{\"kind\":\"HOLD_KIND_EMPTY\",\"objectRef\":null}," +
                    "\"source\":{\"slotId\":\"$BIN_A\",\"occupied\":true,\"identity\":\"$MATERIAL\",\"observedAt\":null}," +
                    "\"destination\":{\"slotId\":\"$RACK\",\"occupied\":false,\"identity\":null,\"observedAt\":null}," +
                    "\"readAt\":\"${basis.readAt}\"}" in line,
                line,
            )
            assertEquals(opened.digest(), w.mw.incidents().single().digest(), "판단이 요약을 바꿨다")
            assertEquals(emptyList(), w.mw.reworkCandidates(), "받은 뒤에도 후보로 남았다")

            w.drive { unit.taskId.isNotEmpty() }
            assertEquals("${unit.unitId.let { "SEQ-1#$it" }}@r1", unit.taskId)
            assertTrue(unit.taskId in w.tasks(), "새 시도가 기체에 나가지 않았다: ${w.tasks()}")
        }
    }

    @Test
    fun `목적지가 차 있거나 말이 없거나 OPTIONAL 이면, 출발이 비었거나 말이 없으면 받지 않는다`() {
        val cases: List<Triple<String, (World) -> Unit, String>> = listOf(
            Triple("REQUIRED", { w -> w.cell.program(RACK, OTHER) }, "비어 있지 않다"),
            Triple("REQUIRED", { w -> w.cell.silence(RACK) }, "목적지 $RACK 를 확인하지 못했다"),
            Triple("OPTIONAL", { _ -> }, "목적지를 물리 점유로 보지 않는 자리"),
            Triple("REQUIRED", { w -> w.cell.empty(BIN_A) }, "이 없다"),
            Triple("REQUIRED", { w -> w.cell.silence(BIN_A) }, "출발 자리 $BIN_A 의 $MATERIAL 를 확인하지 못했다"),
        )
        cases.forEach { (declaration, arrange, expected) ->
            World(destination = declaration).use { w ->
                val exec = w.failedHolding()
                val tasks = w.tasks()
                val responses = w.mw.responses().size
                w.hand.kind = HoldKind.HOLD_KIND_EMPTY
                arrange(w)
                w.refused(exec, expected)
                w.assertUnchanged(exec, tasks, responses)
            }
        }
    }

    @Test
    fun `받은 뒤 발행 전에 손이 든 채로 바뀌면 내지 않고 그 시도를 실패로 닫아 실행이 종결하며 다시 낸 재작업은 관문을 다시 지나 시도가 오른다`() {
        World().use { w ->
            val exec = w.failedHolding()
            val unit = exec.units.single()
            w.hand.kind = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, unit.unitId, OperatorDecision.REWORK, OPERATOR))

            w.hand.kind = HoldKind.HOLD_KIND_HOLDING
            w.drive { exec.physicalState.isSettled }
            assertEquals(Triple(UnitState.FAILED, MiddlewareCodes.REWORK_RECHECK, ""), Triple(unit.state, unit.failureClass, unit.taskId))
            assertEquals(1, unit.attempt)
            assertEquals(PhysicalState.FAILED, exec.physicalState, "재검사 실패가 실행을 종결시키지 않았다 — 출구 없는 보류")
            assertTrue(w.tasks().none { it.endsWith("@r1") }, "재검사를 못 지난 시도가 나갔다: ${w.tasks()}")
            val incidents = w.mw.incidents()
            assertEquals(2, incidents.size, "재검사 실패가 인시던트를 열지 않았다")
            val recheck = incidents.last()
            assertEquals(Triple(unit.unitId, false, MiddlewareCodes.REWORK_RECHECK), Triple(recheck.unitId, recheck.unresolved, recheck.failureClass))
            assertEquals(1, recheck.attempt)
            assertEquals(IncidentDecision.REWORK, incidents.first().resolution?.decision, "앞 판단이 사라졌다")
            assertTrue(exec.eventTrail.any { it.kind == MiddlewareCodes.REWORK_RECHECK && "hand=HOLD_KIND_HOLDING" in it.detail })
            // 재작업 묶음의 뿌리다 — 후보에 시도된 `pick_place` 로 다시 선다.
            assertEquals(ReworkKind.ATTEMPTED_HANDLING, w.mw.reworkCandidates().single().kind)

            // 설비 근거가 없는 실패는 확인으로 닫지 않는다.
            val confirm = assertIs<ResolveOutcome.Refused>(w.mw.resolve(exec.executionId, unit.unitId, OperatorDecision.CONFIRM_DONE, OPERATOR))
            assertTrue("설비 근거가 없는 실패" in confirm.reason, confirm.reason)
            // 다시 낸 재작업은 관문을 다시 지난다 — 든 채면 거부, 빈손이면 시도 2.
            w.refused(exec, "빈손이 아니다")
            w.hand.kind = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, unit.unitId, OperatorDecision.REWORK, OPERATOR))
            assertEquals(2, unit.attempt)
            val decision = assertNotNull(w.mw.judgmentRecord(exec.executionId)!!.units.single().decision)
            assertEquals(1, decision.attempt)
            assertEquals(recheck.origin, decision.incident)
            assertEquals(IncidentDecision.REWORK, w.mw.incidents().last().resolution?.decision)
            w.drive { unit.taskId.isNotEmpty() }
            assertTrue(unit.taskId.endsWith("@r2"), unit.taskId)
        }
    }

    @Test
    fun `재검사로 닫힌 실패는 리비전이 다시 계획하지 않고 재작업 후보로 남는다`() {
        World().use { w ->
            val exec = w.failedHolding()
            val unit = exec.units.single()
            w.hand.kind = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, unit.unitId, OperatorDecision.REWORK, OPERATOR))
            w.hand.kind = HoldKind.HOLD_KIND_HOLDING
            w.drive { exec.physicalState.isSettled }
            assertEquals(UnitState.FAILED to MiddlewareCodes.REWORK_RECHECK, unit.state to unit.failureClass)

            // 높은 작업 지시 버전 — 태스크가 없어도 계획 단계 실패가 아니라 시도된 실패다. 되살리면 사람의 새 판단 없이 나간다.
            w.hand.kind = HoldKind.HOLD_KIND_EMPTY
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack().copy(version = 2), ROBOT))
            repeat(5) { w.mw.pump() }
            assertEquals(Triple(UnitState.FAILED, MiddlewareCodes.REWORK_RECHECK, ""), Triple(unit.state, unit.failureClass, unit.taskId))
            assertTrue(w.tasks().none { it.contains("@r1") }, "리비전이 재검사로 닫힌 시도를 냈다: ${w.tasks()}")
            assertEquals(listOf(unit.unitId), w.mw.reworkCandidates().map { it.unitId })
        }
    }

    @Test
    fun `태스크 없이 닫힌 재검사 실패의 시도 번호는 재기동을 넘고 다시 낸 재작업은 r2 로 나간다`() {
        World().use { w ->
            val exec = w.failedHolding()
            val unit = exec.units.single()
            w.hand.kind = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, unit.unitId, OperatorDecision.REWORK, OPERATOR))
            w.hand.kind = HoldKind.HOLD_KIND_HOLDING
            w.drive { exec.physicalState.isSettled }
            val record = JudgmentRecordWire.decode(JudgmentRecordWire.encode(assertNotNull(w.mw.judgmentRecord(exec.executionId))))

            // 스냅숏에는 시도 0 의 태스크만 있다. 시도 1 은 기록만 안다(태스크 없이 닫힌 실패).
            val second = w.middleware()
            val rebuilt = assertIs<Middleware.Submission.Accepted>(
                second.resume(rack(), ROBOT, assertNotNull(w.catalog.active(PrepareSequencedRack.WORK_MASTER)), record),
            ).execution
            val again = rebuilt.units.single()
            assertEquals(Triple(1, UnitState.FAILED, MiddlewareCodes.REWORK_RECHECK), Triple(again.attempt, again.state, again.failureClass))
            w.drive(mw = second) { rebuilt.physicalState.isSettled }
            w.hand.kind = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, second.resolve(rebuilt.executionId, again.unitId, OperatorDecision.REWORK, OPERATOR))
            w.drive(mw = second) { again.taskId.isNotEmpty() }
            assertTrue(again.taskId.endsWith("@r2"), again.taskId)
        }
    }

    @Test
    fun `재기동 사이에도 첫 발행 직전 재검사가 판단 기록에서 유도된다`() {
        World().use { w ->
            val exec = w.failedHolding()
            val unit = exec.units.single()
            w.hand.kind = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, unit.unitId, OperatorDecision.REWORK, OPERATOR))
            val record = JudgmentRecordWire.decode(JudgmentRecordWire.encode(assertNotNull(w.mw.judgmentRecord(exec.executionId))))

            // 발행 전에 내려가고, 그 사이 손이 든 채가 됐다. 다시 지은 실행은 새 칸 없이 기록의 판단만으로 재검사 대상을 안다.
            w.hand.kind = HoldKind.HOLD_KIND_HOLDING
            val second = w.middleware()
            val rebuilt = assertIs<Middleware.Submission.Accepted>(
                second.resume(rack(), ROBOT, assertNotNull(w.catalog.active(PrepareSequencedRack.WORK_MASTER)), record),
            ).execution
            val again = rebuilt.units.single()
            assertEquals(1 to UnitState.PENDING, again.attempt to again.state)
            second.pump()
            assertEquals(UnitState.FAILED to MiddlewareCodes.REWORK_RECHECK, again.state to again.failureClass)
            assertTrue(w.tasks().none { it.endsWith("@r1") }, w.tasks().toString())
        }
    }

    @Test
    fun `발행 뒤 재기동했을 때 기체가 그 시도를 도는 중이면 다시 보지 않고 같은 태스크로 이어 간다`() {
        World().use { w ->
            val exec = w.failedHolding()
            val unit = exec.units.single()
            w.hand.kind = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, unit.unitId, OperatorDecision.REWORK, OPERATOR))
            w.mw.pump()
            assertTrue(unit.taskId.endsWith("@r1"), "새 시도가 나가야 이 시험이 선다: ${unit.taskId}")
            val record = JudgmentRecordWire.decode(JudgmentRecordWire.encode(assertNotNull(w.mw.judgmentRecord(exec.executionId))))

            // 기체는 그 시도를 도는 중이라 든 채다. 리비전을 싣지 않는 포트라 재부착 대신 같은 태스크 id 로 다시 보낸다 — 그 앞에서 재검사가 끼면 안 된다.
            w.hand.kind = HoldKind.HOLD_KIND_HOLDING
            w.hand.noRevisions = true
            val second = w.middleware()
            val rebuilt = assertIs<Middleware.Submission.Accepted>(
                second.resume(rack(), ROBOT, assertNotNull(w.catalog.active(PrepareSequencedRack.WORK_MASTER)), record),
            ).execution
            val again = rebuilt.units.single()
            repeat(3) { second.pump() }
            assertTrue(again.failureClass != MiddlewareCodes.REWORK_RECHECK, "도는 시도를 재검사가 닫았다: ${again.state}")
            assertTrue(again.taskId.endsWith("@r1"), again.taskId)
            assertEquals(1, w.tasks().count { it.endsWith("@r1") }, "같은 시도를 새 태스크로 냈다: ${w.tasks()}")
            assertTrue(second.incidents().none { it.failureClass == MiddlewareCodes.REWORK_RECHECK })
        }
    }

    @Test
    fun `발행 직전에 스냅숏을 아직 못 읽었으면 기다리고 읽히면 내며, 기다림 창을 넘기면 실패로 닫는다`() {
        World().use { w ->
            val exec = w.failedHolding()
            val unit = exec.units.single()
            w.hand.kind = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, unit.unitId, OperatorDecision.REWORK, OPERATOR))

            w.hand.blind = true
            repeat(3) { w.mw.pump() }
            assertEquals(UnitState.PENDING to "", unit.state to unit.taskId, "못 읽은 동안 내거나 닫았다")
            assertEquals(1, exec.eventTrail.count { it.kind == MiddlewareCodes.REWORK_RECHECK }, "기다림 자취는 한 번이다")
            w.hand.blind = false
            w.mw.pump()
            assertTrue(unit.taskId.endsWith("@r1"), "읽힌 뒤에도 내지 않았다: ${unit.state}")
        }
        World().use { w ->
            val exec = w.failedHolding()
            val unit = exec.units.single()
            w.hand.kind = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, unit.unitId, OperatorDecision.REWORK, OPERATOR))
            w.hand.blind = true
            w.mw.pump()
            w.harness.advance(Duration.ofMinutes(6))
            w.mw.pump()
            assertEquals(UnitState.FAILED to MiddlewareCodes.REWORK_RECHECK, unit.state to unit.failureClass)
            assertTrue("기다림 창" in unit.note.orEmpty(), unit.note)
        }
        World().use { w ->
            // 기체가 손을 «관측 불가» 로 답했다 — 기다리지 않고 곧바로 닫는다.
            val exec = w.failedHolding()
            val unit = exec.units.single()
            w.hand.kind = HoldKind.HOLD_KIND_EMPTY
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, unit.unitId, OperatorDecision.REWORK, OPERATOR))
            w.hand.kind = HoldKind.HOLD_KIND_NOT_OBSERVABLE
            w.mw.pump()
            assertEquals(UnitState.FAILED to MiddlewareCodes.REWORK_RECHECK, unit.state to unit.failureClass)
        }
    }

    @Test
    fun `결함은 굳은 기체 뷰가 아니라 그 판단에서 읽은 스냅숏으로 본다`() {
        World().use { w ->
            val exec = w.failedHolding()
            w.hand.kind = HoldKind.HOLD_KIND_EMPTY
            // 종결한 실행의 기체 뷰는 구동이 다시 읽지 않는다 — 굳은 뷰에 새 태스크를 못 받는 결함이 남아 있다.
            val view = assertNotNull(w.mw.view(ROBOT))
            view.observable = true
            view.faultsObservable = true
            view.faults = mapOf("STALE" to BLOCKING)
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, exec.units.single().unitId, OperatorDecision.REWORK, OPERATOR))
        }
        World().use { w ->
            val exec = w.failedHolding()
            w.hand.kind = HoldKind.HOLD_KIND_EMPTY
            w.hand.faults = listOf(BLOCKING)
            w.refused(exec, "새 태스크를 못 받는다")
        }
    }

    @Test
    fun `발행 관문이 막혀 있으면 받지 않고, 확인되지 않은 단위는 재작업하지 않는다`() {
        World().use { w ->
            val exec = w.failedHolding()
            val tasks = w.tasks()
            val responses = w.mw.responses().size
            w.hand.kind = HoldKind.HOLD_KIND_EMPTY
            w.retired[ROBOT] = "정비 중"
            w.refused(exec, "발행 관문")
            w.assertUnchanged(exec, tasks, responses)
            // 막음이 걷히면 같은 요청을 받는다 — 거부는 때의 문제였다.
            w.retired.clear()
            assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, exec.units.single().unitId, OperatorDecision.REWORK, OPERATOR))
        }
        World().use { w ->
            // 하류는 끝났다는데 설비가 목적지에서 못 봤다 — 놓였을 수 있다(보고서 12.3). 재작업은 거부하고 운영자가 현장을 확인한다.
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(rack(), ROBOT)).execution
            w.drive(rounds = 400) { exec.units.single().state == UnitState.UNVERIFIED && exec.physicalState.isSettled }
            w.hand.kind = HoldKind.HOLD_KIND_EMPTY
            val refused = assertIs<ResolveOutcome.Refused>(w.mw.resolve(exec.executionId, exec.units.single().unitId, OperatorDecision.REWORK, OPERATOR))
            assertTrue("12.3" in refused.reason, refused.reason)
            assertEquals(0, exec.units.single().attempt)
            assertEquals(emptyList(), w.mw.reworkCandidates())
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val MATERIAL = "ENGINE-COVER-A"
        const val OTHER = "ENGINE-COVER-B"
        const val RACK = "RACK-204.S01"
        const val BIN_A = "SEQ-IN-02.BIN-A"
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)

        val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()

        /** 새 태스크를 못 받는 결함. */
        val BLOCKING: Fault = Fault.newBuilder().setErrorType("PAYLOAD_LOST").setCanAcceptNewTask(false).setCanContinueCurrentTask(false).build()

        fun rack() = JobOrder(
            jobOrderId = "SEQ-1",
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E2,
            materialRequirements = listOf(MaterialRequirement(MATERIAL, 1)),
            equipmentRequirements = listOf(
                EquipmentRequirement(RACK, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to MATERIAL)),
                EquipmentRequirement(BIN_A, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to MATERIAL)),
            ),
        )
    }
}

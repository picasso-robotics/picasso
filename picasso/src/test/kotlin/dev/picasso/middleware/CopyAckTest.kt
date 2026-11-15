package dev.picasso.middleware

import dev.picasso.capability.Remedy
import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.WatchTaskResponse
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 담는 쪽의 사본 선언(§15.327, [Retention.copyAck]) — 켜면 확인 전의 인시던트 번들·탐색 기록은 보존 상한이 내리지 않고, 확인한 줄은 다음
 * 정리에서 상한대로 내린다. 확인 대기 한도를 넘으면 오래 기다린 줄부터 보호가 풀리고 확인 전에 내려간 수를 센다. 끄면 선언 전과 같다.
 */
class CopyAckTest {

    // ── 탐색 기록 — 장부만 따로

    private class Silent : RobotPort {
        override fun capabilities(robotId: String): Capability = error("묻지 않아야 한다")
        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse =
            error("묻지 않아야 한다")
        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> = error("묻지 않아야 한다")
        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = error("묻지 않아야 한다")
        override fun snapshot(robotId: String): RobotSnapshot? = error("묻지 않아야 한다")
        override fun replay(robotId: String, from: Long): Replay? = error("묻지 않아야 한다")
    }

    private fun desk(retention: Retention) = RemedyDesk(Silent(), Entitlements.None, 3, { T0 }, { T0 }, { _, _ -> null }, retention)

    private val found = Remedy.Found(listOf(RemedyStep("grip", emptyList(), HoldKind.HOLD_KIND_HOLDING, HoldKind.HOLD_KIND_EMPTY)))

    private fun propose(desk: RemedyDesk, jobOrderId: String) =
        desk.record(ROBOT, patrol(jobOrderId), Middleware.Submission.Rejected("막혔다", remedy = found))

    private fun ids(desk: RemedyDesk) = desk.remedySearches().map { it.searchId }

    private val declared = Retention(remedySearches = 1, copyAck = CopyAck(pendingSearches = 10))

    @Test
    fun `선언을 켜면 확인 전의 탐색 줄은 상한을 넘어도 남는다`() {
        val desk = desk(declared)
        (1..3).forEach { propose(desk, "PATROL-$it") }

        assertEquals(listOf("search-1", "search-2", "search-3"), ids(desk), "확인 전의 줄을 상한이 내렸다")
        assertEquals(0, desk.evictions(0, 0, 0).remedySearches)
        assertEquals(CopyLedgerState(pending = 3, overCap = 2, exposed = 0, evictedBeforeCopy = 0), desk.copyState())
    }

    @Test
    fun `확인한 탐색 줄은 다음 정리에서 상한대로 내린다`() {
        val desk = desk(declared)
        (1..3).forEach { propose(desk, "PATROL-$it") }

        assertEquals(2, desk.ackCopied(listOf("search-1", "search-2")))
        assertEquals(3, ids(desk).size, "확인이 곧바로 내렸다 — 내리는 것은 다음 정리다")

        propose(desk, "PATROL-4")
        assertEquals(listOf("search-3", "search-4"), ids(desk), "확인한 줄이 다음 정리에서 안 내려갔거나 확인 전의 줄이 내려갔다")
        assertEquals(2, desk.evictions(0, 0, 0).remedySearches)
        assertEquals(CopyLedgerState(pending = 2, overCap = 1, exposed = 0, evictedBeforeCopy = 0), desk.copyState())
    }

    @Test
    fun `확인 대기 한도를 넘으면 오래 기다린 줄부터 보호를 풀고 확인 전 내림을 센다`() {
        val desk = desk(Retention(remedySearches = 1, copyAck = CopyAck(pendingSearches = 2)))
        (1..3).forEach { propose(desk, "PATROL-$it") }

        assertEquals(listOf("search-2", "search-3"), ids(desk), "한도를 넘은 가장 오래된 줄이 안 내려갔다")
        assertEquals(CopyLedgerState(pending = 2, overCap = 1, exposed = 1, evictedBeforeCopy = 1), desk.copyState())
        assertEquals(1, desk.evictions(0, 0, 0).remedySearches, "확인 전 내림도 보존 상한의 내림이다")
        assertEquals(0, desk.ackCopied(listOf("search-1")), "이미 내린 줄의 확인을 셌다")
    }

    @Test
    fun `보호가 풀렸어도 내리기 전에 확인하면 확인 전 내림으로 세지 않는다`() {
        val desk = desk(Retention(remedySearches = 10, copyAck = CopyAck(pendingSearches = 1)))
        (1..2).forEach { propose(desk, "PATROL-$it") }
        assertEquals(1, desk.copyState().exposed)

        assertEquals(2, desk.ackCopied(listOf("search-1", "search-2")), "보호가 풀린 줄의 확인을 안 셌다")
        assertEquals(CopyLedgerState(pending = 0, overCap = 0, exposed = 1, evictedBeforeCopy = 0), desk.copyState())
    }

    @Test
    fun `확인은 멱등이고 모르는 id 는 무시한다`() {
        val desk = desk(declared)
        (1..2).forEach { propose(desk, "PATROL-$it") }

        assertEquals(1, desk.ackCopied(listOf("search-1", "search-1")), "같은 id 를 두 번 셌다")
        assertEquals(0, desk.ackCopied(listOf("search-1")), "다시 온 확인을 셌다")
        assertEquals(0, desk.ackCopied(listOf("search-9", "incident-1", "")), "모르는 id 를 셌다")
        assertEquals(1, desk.copyState().pending)
    }

    @Test
    fun `선언을 끄면 확인은 0 이고 탐색 대장의 바이트가 선언 전과 같다`() {
        val off = desk(Retention(remedySearches = 2))
        val on = desk(Retention(remedySearches = 2, copyAck = CopyAck()))
        (1..5).forEach {
            propose(off, "PATROL-$it")
            propose(on, "PATROL-$it")
            // 매번 곧바로 확인하면 켠 쪽도 끈 쪽과 같은 줄을 같은 때에 내린다.
            assertEquals(1, on.ackCopied(listOf("search-$it")))
            assertEquals(0, off.ackCopied(listOf("search-$it")), "선언이 꺼졌는데 확인을 셌다")
        }

        assertEquals(LedgerExport.remedySearches(off.remedySearches()), LedgerExport.remedySearches(on.remedySearches()))
        assertEquals(off.evictions(0, 0, 0), on.evictions(0, 0, 0))
        assertEquals(listOf("search-4", "search-5"), ids(off), "선언을 끈 대장이 오래된 것부터 내리지 않았다")
        assertEquals(CopyLedgerState(0, 0, 0, 0), off.copyState())
    }

    @Test
    fun `확인 대기 한도가 양수가 아니면 만들지 않는다`() {
        assertFailsWith<IllegalArgumentException> { CopyAck(pendingIncidents = 0) }
        assertFailsWith<IllegalArgumentException> { CopyAck(pendingSearches = 0) }
        assertNull(Retention().copyAck, "사본 선언의 기본이 꺼짐이 아니다")
    }

    @Test
    fun `확인 대기 한도의 기본은 대장 상한이라 선언이 상한 위로 메모리를 올리지 않는다`() {
        val desk = desk(Retention(remedySearches = 2, copyAck = CopyAck()))
        (1..3).forEach { propose(desk, "PATROL-$it") }

        assertEquals(listOf("search-2", "search-3"), ids(desk), "기본 한도가 대장 상한보다 컸다")
        assertEquals(CopyLedgerState(pending = 2, overCap = 0, exposed = 1, evictedBeforeCopy = 1), desk.copyState())
    }

    // ── 인시던트 번들 — 실제 미들웨어로

    private class World(retention: Retention) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to QUAD, OTHER to QUAD))
        val mw = Middleware(FaultBlindRobotPort(ClientRobotPort(harness.client())), now = { harness.clock.now() }, retention = retention)

        fun tasks(robotId: String) = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(robotId).build()).tasksList

        fun drive(until: () -> Boolean) {
            repeat(120) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(10))
                Thread.sleep(30)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다")
        }

        /** 점검 단위에 결함을 걸어 인시던트를 하나 세운다 — 구동은 아직 그 실행을 돌린다(번들이 구동 중 예외를 받는다). */
        fun liveIncident(jobOrderId: String, robotId: String = ROBOT): Pair<Middleware.Execution, String> {
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(patrol(jobOrderId), robotId)).execution
            val inspect = "$jobOrderId#$TARGET"
            drive { tasks(robotId).any { it.taskId == inspect && it.taskState == "RUNNING" } }
            harness.oracle.forceFault(
                ForceFaultRequest.newBuilder().setRobotId(robotId).setErrorType("LOCALIZATION_LOST").setTaskId(inspect).build(),
            )
            drive { exec.physicalState == PhysicalState.PARTIAL && exec.active == null && mw.incidents().any { it.executionId == exec.executionId } }
            return exec to mw.incidents().last { it.executionId == exec.executionId }.incidentId
        }

        /** 구동을 끝낸다 — 그 번들은 더는 구동 중 예외를 안 받는다. */
        fun close(exec: Middleware.Execution) {
            assertTrue(mw.cancel(exec.executionId))
            drive { !exec.physicalState.pumps }
        }

        /** [liveIncident] 뒤 실행을 취소해 구동을 끝낸다. */
        fun closedIncident(jobOrderId: String, robotId: String = ROBOT): String {
            val (exec, incidentId) = liveIncident(jobOrderId, robotId)
            close(exec)
            return incidentId
        }

        override fun close() = harness.close()
    }

    @Test
    fun `선언을 켜면 확인 전의 번들은 남고 확인한 번들은 다음 봉인에서 내리며 대장마다 따로 확인한다`() {
        World(Retention(incidents = 1, copyAck = CopyAck(pendingIncidents = 10))).use { w ->
            val first = w.closedIncident("PATROL-1")
            val second = w.closedIncident("PATROL-2")
            assertEquals(listOf(first, second), w.mw.incidents().map { it.incidentId }, "확인 전의 번들을 상한이 내렸다")
            assertEquals(0, w.mw.retentionEvictions().incidents)

            // 탐색 대장의 확인은 인시던트를 풀지 않는다.
            assertEquals(0, w.mw.ackCopiedSearches(listOf(first)), "탐색 대장이 인시던트 id 를 확인했다")
            assertEquals(1, w.mw.ackCopiedIncidents(listOf(first)))
            assertEquals(0, w.mw.ackCopiedIncidents(listOf(first, "incident-99")), "확인이 멱등이 아니거나 모르는 id 를 셌다")
            assertEquals(2, w.mw.incidents().size, "확인이 곧바로 내렸다 — 내리는 것은 다음 봉인이다")

            val third = w.closedIncident("PATROL-3")
            assertEquals(listOf(second, third), w.mw.incidents().map { it.incidentId }, "확인한 번들이 다음 봉인에서 안 내려갔다")
            assertEquals(1, w.mw.retentionEvictions().incidents)
            val state = w.mw.copyAcks()
            assertEquals(CopyAck(pendingIncidents = 10), state.declared)
            assertEquals(CopyLedgerState(pending = 2, overCap = 1, exposed = 0, evictedBeforeCopy = 0), state.incidents)
            assertEquals(CopyLedgerState(0, 0, 0, 0), state.remedySearches)
        }
    }

    @Test
    fun `선언을 끄면 번들은 확인 없이 상한대로 내리고 확인은 0 이다`() {
        World(Retention(incidents = 1)).use { w ->
            val first = w.closedIncident("PATROL-1")
            val second = w.closedIncident("PATROL-2")
            assertEquals(listOf(second), w.mw.incidents().map { it.incidentId })
            assertEquals(0, w.mw.ackCopiedIncidents(listOf(first, second)), "선언이 꺼졌는데 확인을 셌다")
            assertEquals(CopyAckState(null, CopyLedgerState(0, 0, 0, 0), CopyLedgerState(0, 0, 0, 0)), w.mw.copyAcks())
        }
    }

    @Test
    fun `확인한 번들도 검토로 줄이 바뀌면 다시 확인을 기다리고 다시 확인하면 다음 봉인에서 내린다`() {
        World(Retention(incidents = 1, copyAck = CopyAck(pendingIncidents = 10))).use { w ->
            val first = w.closedIncident("PATROL-1")
            assertEquals(1, w.mw.ackCopiedIncidents(listOf(first)))
            assertTrue(w.mw.reviewIncident(first, ReviewVerdict.AGREED, "패드 마모"))
            assertEquals(1, w.mw.copyAcks().incidents.pending, "검토로 바뀐 줄이 확인 대기로 안 올라갔다")

            val second = w.closedIncident("PATROL-2")
            assertEquals(listOf(first, second), w.mw.incidents().map { it.incidentId }, "검토로 바뀐 줄을 담는 쪽이 다시 적기 전에 상한이 내렸다")

            assertEquals(1, w.mw.ackCopiedIncidents(listOf(first)))
            val third = w.closedIncident("PATROL-3")
            assertEquals(listOf(second, third), w.mw.incidents().map { it.incidentId }, "다시 확인한 줄이 다음 봉인에서 안 내려갔다")
            assertEquals(0, w.mw.copyAcks().incidents.evictedBeforeCopy)
        }
    }

    @Test
    fun `확인한 번들도 판단으로 줄이 바뀌면 다시 확인을 기다린다`() {
        // 적재를 잃어 운영자 보류에 선 단위 — 운영자 취소가 그 인시던트에 판단(OPERATOR_CANCEL)을 싣는다.
        val harness = Harness(mapOf(ROBOT to PRECOND, OTHER to QUAD))
        harness.use {
            val cell = CellMimic(now = { harness.clock.now() })
            val mw = Middleware(
                ClientRobotPort(harness.client()), cell, now = { harness.clock.now() },
                retention = Retention(incidents = 1, copyAck = CopyAck(pendingIncidents = 10)),
            )
            fun drive(until: () -> Boolean) {
                repeat(80) {
                    mw.pump()
                    if (until()) return
                    harness.advance(Duration.ofSeconds(1))
                    Thread.sleep(40)
                    mw.pump()
                    if (until()) return
                }
                error("조건에 못 미쳤다")
            }
            val order = rack()
            order.equipmentRequirements.filter { it.equipmentUse == "destination" }.forEach { cell.program(it.id, it.properties["material"]) }
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order, ROBOT)).execution
            drive { exec.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING }
            harness.oracle.forceFault(
                ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType("PAYLOAD_LOST").setTaskId(exec.units.first().taskId).build(),
            )
            drive { exec.physicalState == PhysicalState.OPERATOR_HOLD && mw.incidents().isNotEmpty() }
            val held = mw.incidents().map { it.incidentId }
            assertEquals(held.size, mw.ackCopiedIncidents(held))

            assertEquals(ResolveOutcome.Resolved, mw.cancelByOperator(exec.executionId, Approver("op-1", ApproverKind.PERSON)))
            val decided = mw.incidents().filter { it.resolution?.decision == IncidentDecision.OPERATOR_CANCEL }.map { it.incidentId }
            assertTrue(decided.isNotEmpty(), "판단이 안 실렸다 — 이 시험의 전제가 틀렸다")
            assertEquals(decided.size, mw.copyAcks().incidents.pending, "판단으로 바뀐 줄이 확인 대기로 안 올라갔다")
            drive { !exec.physicalState.pumps }

            // 다음 봉인(다른 기체의 점검 결함) — 구동이 끝났어도 바뀐 줄은 담는 쪽이 다시 확인할 때까지 남는다.
            val next = assertIs<Middleware.Submission.Accepted>(mw.submit(patrol("PATROL-9"), OTHER)).execution
            val inspect = "PATROL-9#$TARGET"
            fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(OTHER).build()).tasksList
            drive { tasks().any { it.taskId == inspect && it.taskState == "RUNNING" } }
            harness.oracle.forceFault(
                ForceFaultRequest.newBuilder().setRobotId(OTHER).setErrorType("LOCALIZATION_LOST").setTaskId(inspect).build(),
            )
            drive { mw.incidents().any { it.executionId == next.executionId } }
            assertTrue(mw.incidents().map { it.incidentId }.containsAll(decided), "판단으로 바뀐 줄을 담는 쪽이 다시 적기 전에 상한이 내렸다")
        }
    }

    @Test
    fun `구동 중이라 남은 번들도 보호가 풀린 뒤 내려가면 확인 전 내림으로 센다`() {
        World(Retention(incidents = 1, copyAck = CopyAck(pendingIncidents = 1))).use { w ->
            val (live, first) = w.liveIncident("PATROL-1")
            w.closedIncident("PATROL-2", OTHER)
            // 한도 1 이라 둘째 번들이 첫째의 보호를 풀었다. 첫째는 구동 중 예외로 남는다.
            assertTrue(w.mw.incidents().any { it.incidentId == first }, "구동 중인 실행의 번들을 내렸다")
            assertEquals(1, w.mw.copyAcks().incidents.exposed)
            assertEquals(0, w.mw.copyAcks().incidents.evictedBeforeCopy)

            w.close(live)
            w.closedIncident("PATROL-3")
            assertTrue(w.mw.incidents().none { it.incidentId == first }, "구동이 끝난 뒤에도 보호가 풀린 번들이 남았다")
            val state = w.mw.copyAcks().incidents
            assertTrue(state.evictedBeforeCopy >= 1, "확인 전에 내린 번들을 안 셌다: $state")
            assertEquals(w.mw.retentionEvictions().incidents, state.evictedBeforeCopy, "내린 번들은 모두 확인 전이었다")
        }
    }

    private companion object {
        const val ROBOT = "quad-01"
        const val OTHER = "quad-02"
        const val TARGET = "PUMP-01"
        val QUAD: Path = Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()
        val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()

        fun rack() = JobOrder(
            jobOrderId = "SEQ-204",
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = 17,
            requiredEvidence = Evidence.E2,
            materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", 2)),
            equipmentRequirements = listOf(
                EquipmentRequirement("RACK-204.S01", "destination", mapOf("material" to "ENGINE-COVER-A")),
                EquipmentRequirement("RACK-204.S02", "destination", mapOf("material" to "ENGINE-COVER-A")),
                EquipmentRequirement("SEQ-IN-02.BIN-A", "source", mapOf("material" to "ENGINE-COVER-A")),
            ),
        )
        val T0: Instant = Instant.parse("2026-01-01T00:00:00Z")

        fun patrol(jobOrderId: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = listOf(
                EquipmentRequirement(TARGET, EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM")),
            ),
        )
    }
}

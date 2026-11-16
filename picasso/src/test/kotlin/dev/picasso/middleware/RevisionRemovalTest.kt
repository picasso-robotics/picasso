package dev.picasso.middleware

import com.google.protobuf.Struct
import com.google.protobuf.util.JsonFormat
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.harness.Harness
import dev.picasso.host.BundleWriter
import dev.picasso.mimic.control.v1.DumpInternalStateRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **리비전이 작업 지시에서 항목을 뺄 때**(§15.329). 빠진 단위가 모두 손대지 않은 것이면 지우고(발행하지 않고 작업 응답에서 빠지며 자취·내보내기
 * 사건이 남는다), 하나라도 손댔으면 리비전 전체를 거부하고 아무것도 바꾸지 않는다. 지운 단위를 남는 단위가 선행으로 기다리거나 항목을 모두 빼면
 * 거부한다. 뺀 뒤 판단 기록으로 최신 작업 지시를 다시 지을 수 있다.
 *
 * 순회는 [InspectAsset] — 대상마다 이동(`<대상>.travel`)과 점검(`<대상>`). 기체는 [InspectAssetTest] 와 같은 4족 픽스처다. 손댄 상태는 대부분
 * 단위의 칸을 직접 바꿔 세운다 — 판정이 보는 것은 그 칸이고, 상태마다 실물로 몰면 시험 하나가 순회 하나다.
 */
class RevisionRemovalTest {

    /** 출발·갱신 송신을 센다. 거부한 리비전이 기체에 아무것도 내지 않았는지 본다. */
    private class Counting(private val delegate: RobotPort) : RobotPort by delegate {
        var sends = 0
            private set

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            sends += 1
            return delegate.start(robotId, taskId, revision, skillType, parameters)
        }

        override fun issue(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): Issue? {
            sends += 1
            return delegate.issue(robotId, taskId, revision, skillType, parameters)
        }
    }

    private class World(capabilities: List<LogicalCapability>? = null, retention: Retention = Retention()) : AutoCloseable {
        val harness = Harness(mapOf(ROBOT to Path.of("..", "profile", "profiles", "quadruped-b.json").normalize()))
        val port = Counting(ClientRobotPort(harness.client()))
        val mw = Middleware(port, now = { harness.clock.now() }, capabilities = capabilities, retention = retention)

        fun tasks() = harness.oracle.dumpInternalState(DumpInternalStateRequest.newBuilder().setRobotId(ROBOT).build()).tasksList

        fun drive(rounds: Int = 120, until: () -> Boolean) {
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

    private fun target(id: String, located: Boolean = true) = EquipmentRequirement(
        id, EquipmentUse.INSPECTION_TARGET,
        if (located) mapOf(EquipmentUse.PROP_LOCATION to "$id-SPOT") else emptyMap(),
    )

    private fun order(version: Int = 1, targets: List<String> = ALL, unlocated: Set<String> = emptySet()) = JobOrder(
        jobOrderId = JOB,
        workMasterId = InspectAsset.WORK_MASTER,
        version = version,
        equipmentRequirements = targets.map { target(it, located = it !in unlocated) },
    )

    private fun travel(id: String) = "$id${InspectAsset.TRAVEL_SUFFIX}"
    private fun taskOf(unitId: String) = "$JOB#$unitId"
    private fun Execution.ids() = units.map { it.unitId }
    private fun Execution.unit(id: String) = units.single { it.unitId == id }
    private fun Execution.settled() = physicalState.isSettled && active == null

    /** 거부가 바꾸면 안 되는 것 — 단위·리비전·송신·인시던트·응답·제거 사건·판단 기록·자취. */
    private fun World.fingerprint(exec: Execution): List<Any?> = listOf(
        exec.version,
        exec.physicalState,
        exec.units.map { listOf(it.unitId, it.state, it.revision, it.attempt, it.taskId, it.failureClass) },
        exec.eventTrail.size,
        port.sends,
        mw.incidents().size,
        mw.responses().size,
        mw.revisionRemovals(),
        mw.judgmentRecord(exec.executionId),
    )

    @Test
    fun `손대지 않은 대기 단위를 빼는 리비전은 그 단위를 지워 내지 않고 작업 응답에서 빼며 자취와 내보내기 사건을 남긴다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            val dropped = order(version = 2, targets = listOf("PUMP-01", "PANEL-3"))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(dropped, ROBOT))

            assertEquals(listOf(travel("PUMP-01"), "PUMP-01", travel("PANEL-3"), "PANEL-3"), exec.ids(), "뺀 설비의 단위가 실행에 남았다")
            assertEquals(2, exec.version)
            assertEquals(
                listOf("order revision 2 (from 1) removed COMP-2.travel(PENDING), COMP-2(PENDING)"),
                exec.eventTrail.filter { it.kind == MiddlewareCodes.REVISION_REMOVED }.map { it.detail },
            )
            val removal = w.mw.revisionRemovals().single()
            assertEquals(exec.executionId to JOB, removal.executionId to removal.jobOrderId)
            assertEquals(ROBOT to (1 to 2), removal.robotId to (removal.fromVersion to removal.toVersion))
            assertEquals(listOf(RemovedUnit(travel("COMP-2"), UnitState.PENDING), RemovedUnit("COMP-2", UnitState.PENDING)), removal.removed)
            assertEquals(exec.eventTrail.single { it.kind == MiddlewareCodes.REVISION_REMOVED }.occurredAt, removal.at.toString(), "자취와 사건의 시각이 갈렸다")

            w.drive { exec.settled() }
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
            assertEquals(exec.ids().map(::taskOf), w.tasks().map { it.taskId }, "뺀 설비의 단위가 나갔다")
            val last = w.mw.responses().last()
            assertEquals(2, last.version)
            assertEquals(exec.ids(), last.completedUnits)
            assertEquals(emptyMap(), last.incompleteUnits, "뺀 단위가 미완료로 남았다")
        }
    }

    @Test
    fun `리비전 제거 사건은 내보내기 판 10 의 한 줄과 안내의 수로 나간다`() {
        val at = Instant.parse("2026-11-16T01:02:03Z")
        val removal = RevisionRemoval("exec-1", JOB, ROBOT, 1, 3, listOf(RemovedUnit("A", UnitState.PENDING), RemovedUnit("B", UnitState.FAILED)), at, at.plusSeconds(5))
        val line = LedgerExport.revisionRemovalLine(removal)
        assertEquals(
            """{"executionId":"exec-1","jobOrderId":"$JOB","robotId":"$ROBOT","fromVersion":1,"toVersion":3,""" +
                """"removed":[{"unitId":"A","state":"PENDING"},{"unitId":"B","state":"FAILED"}],"at":"2026-11-16T01:02:03Z","wallClockAt":"2026-11-16T01:02:08Z"}""",
            line,
        )
        assertEquals(line + "\n", LedgerExport.revisionRemovals(listOf(removal)))
        assertEquals("", LedgerExport.revisionRemovals(emptyList()))
        assertEquals("10", LedgerExport.SCHEMA_VERSION)
        // 한 벌은 쓰개가 짓는다(`BundleWriterTest` 가 안내를 짓는 자리를 센다) — 사건 하나를 넘기면 레지스터 한 줄과 안내의 수가 나온다.
        val dir = Files.createTempDirectory("revision-removals")
        assertTrue(BundleWriter(dir, "mw-1").snapshot(emptyList(), emptyList(), at, listOf(removal)))
        assertEquals(line + "\n", Files.readString(dir.resolve(LedgerExport.REVISION_REMOVALS)))
        val manifest = Struct.newBuilder().also { JsonFormat.parser().merge(Files.readString(dir.resolve(LedgerExport.MANIFEST)), it) }.build()
        val counts = manifest.fieldsMap.getValue("counts").structValue.fieldsMap
        assertEquals("10", manifest.fieldsMap.getValue("schemaVersion").stringValue)
        assertEquals(listOf(0.0, 0.0, 1.0), listOf("incidents", "remedySearches", "revisionRemovals").map { counts.getValue(it).numberValue })
    }

    @Test
    fun `계획 때 실패한 단위만 든 항목을 빼면 그 단위가 사라진다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(unlocated = setOf("COMP-2")), ROBOT)).execution
            assertEquals(UnitState.FAILED to InspectAsset.NO_LOCATION, exec.unit("COMP-2").let { it.state to it.failureClass })
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 2, targets = listOf("PUMP-01", "PANEL-3")), ROBOT))
            assertFalse("COMP-2" in exec.ids(), "계획 실패 단위가 남았다")
            assertEquals(listOf(RemovedUnit("COMP-2", UnitState.FAILED)), w.mw.revisionRemovals().single().removed)
        }
    }

    @Test
    fun `지운 뒤 남은 단위가 모두 종착했으면 실행 중으로 되돌리지 않고 그 자리에서 정산해 작업 응답을 한 번 낸다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(targets = listOf("PUMP-01", "COMP-2"), unlocated = setOf("COMP-2")), ROBOT)).execution
            w.drive { exec.settled() }
            assertEquals(PhysicalState.PARTIAL, exec.physicalState)
            val before = w.mw.responses().size

            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 2, targets = listOf("PUMP-01")), ROBOT))
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState, "남은 단위가 다 끝났는데 실행 중으로 돌아갔다")
            assertEquals(before + 1, w.mw.responses().size, "정산의 작업 응답이 한 번이 아니다")
            val last = w.mw.responses().last()
            assertEquals(Triple(2, PhysicalState.PHYSICALLY_DONE, emptyMap<String, String>()), Triple(last.version, last.physicalState, last.incompleteUnits))
            repeat(3) { w.mw.pump() }
            assertEquals(before + 1, w.mw.responses().size, "구동이 정산한 실행에 작업 응답을 또 냈다")
        }
    }

    @Test
    fun `발행했거나 끝난 단위를 빼는 리비전은 상태마다 거부하고 단위 리비전 송신 인시던트 판단 기록을 바꾸지 않는다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            // 도는 단위가 있어야 «거부가 갱신을 안 보냈다» 가 뜻이 있다 — 받은 리비전은 도는 이동에 갱신을 보낸다.
            w.drive { exec.unit(travel("PUMP-01")).state == UnitState.RUNNING }
            assertEquals(UnitState.RUNNING, exec.unit(travel("PUMP-01")).state)
            val dropped = order(version = 2, targets = listOf("PUMP-01", "PANEL-3"))
            val unit = exec.unit(travel("COMP-2"))
            val touched = listOf(
                UnitState.RUNNING, UnitState.IN_DOUBT, UnitState.VERIFYING, UnitState.OPERATOR_HOLD,
                UnitState.DONE, UnitState.UNVERIFIED, UnitState.ABORTED, UnitState.FAILED,
            )
            touched.forEach { state ->
                unit.state = state
                unit.taskId = taskOf(unit.unitId)
                // 검증 대기는 판단 기록이 창을 싣는다.
                unit.downstreamDoneAt = w.harness.clock.now().takeIf { state == UnitState.VERIFYING }
                unit.evidenceDeadline = w.harness.clock.now().plusSeconds(15).takeIf { state == UnitState.VERIFYING }
                val before = w.fingerprint(exec)
                val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(dropped, ROBOT), "$state")
                assertEquals(
                    "리비전이 손댄 단위를 뺀다: COMP-2.travel(${state.name}) — 발행했거나 끝난 단위가 든 항목은 뺄 수 없다. 새 작업 지시로 낸다",
                    refused.reason,
                )
                assertEquals(before, w.fingerprint(exec), "$state 를 뺀 리비전의 거부가 실행을 바꿨다")
                unit.state = UnitState.PENDING
                unit.taskId = ""
                unit.downstreamDoneAt = null
                unit.evidenceDeadline = null
            }
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(dropped, ROBOT), "손대지 않은 채로 되돌렸는데 받지 않았다")
        }
    }

    @Test
    fun `대기 중이어도 보내는 중이거나 맡기는 중이거나 붙기 전이거나 시도가 오른 단위를 빼는 리비전은 거부한다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            val dropped = order(version = 2, targets = listOf("PUMP-01", "PANEL-3"))
            val unit = exec.unit(travel("COMP-2"))
            val marks: List<Pair<String, (Boolean) -> Unit>> = listOf(
                "보내는 중" to { on -> exec.command.inFlight = if (on) InFlight(InFlight.Kind.START, unit.unitId, ROBOT, taskOf(unit.unitId), 1, InspectAsset.NAVIGATE, emptyMap(), 1, null) else null },
                "맡기는 중" to { on -> exec.command.fleetInFlight = if (on) FleetFlight(FleetFlight.Kind.START, unit.unitId, TransportOrder("ref", "box", "a", "b"), null) else null },
                "붙기 전" to { on -> if (on) exec.unattached[unit.unitId] = 0 else exec.unattached.remove(unit.unitId) },
                "시도가 오름" to { on -> unit.attempt = if (on) 1 else 0 },
                "태스크가 있음" to { on -> unit.taskId = if (on) taskOf(unit.unitId) else "" },
                "앞 시도의 파지" to { on -> unit.heldInEarlierAttempt = on },
                "판단 기록이 넘긴 요청" to { on -> if (on) exec.restoredRequests[unit.unitId] = 0 to w.harness.clock.now() else exec.restoredRequests.remove(unit.unitId) },
            )
            marks.forEach { (name, mark) ->
                mark(true)
                val before = w.fingerprint(exec)
                val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(dropped, ROBOT), name)
                assertTrue(refused.reason.startsWith("리비전이 손댄 단위를 뺀다: COMP-2.travel(PENDING) —"), "$name: ${refused.reason}")
                assertEquals(before, w.fingerprint(exec), "$name 단위를 뺀 리비전의 거부가 실행을 바꿨다")
                mark(false)
            }
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(dropped, ROBOT))
        }
    }

    @Test
    fun `첫 발행 직전 재검사로 닫힌 재작업 시도를 빼는 리비전은 태스크가 없어도 거부한다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            val unit = exec.unit(travel("COMP-2"))
            unit.state = UnitState.FAILED
            unit.failureClass = MiddlewareCodes.REWORK_RECHECK
            val before = w.fingerprint(exec)
            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(order(version = 2, targets = listOf("PUMP-01", "PANEL-3")), ROBOT))
            assertTrue(refused.reason.startsWith("리비전이 손댄 단위를 뺀다: COMP-2.travel(FAILED) —"), refused.reason)
            assertEquals(before, w.fingerprint(exec))
        }
    }

    @Test
    fun `한 항목의 단위가 손댄 것과 손대지 않은 것으로 섞여도 리비전 전체를 거부한다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            exec.unit(travel("COMP-2")).let {
                it.state = UnitState.DONE
                it.taskId = taskOf(it.unitId)
            }
            val before = w.fingerprint(exec)
            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(order(version = 2, targets = listOf("PUMP-01", "PANEL-3")), ROBOT))
            assertEquals("리비전이 손댄 단위를 뺀다: COMP-2.travel(DONE) — 발행했거나 끝난 단위가 든 항목은 뺄 수 없다. 새 작업 지시로 낸다", refused.reason)
            assertEquals(before, w.fingerprint(exec))
            assertEquals(UnitState.PENDING, exec.unit("COMP-2").state, "손대지 않은 짝 단위만 지웠다")
        }
    }

    @Test
    fun `승인된 조치 열의 걸음은 계획 밖이라 리비전이 뺀 것으로 잡히지 않는다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            // 승인한 조치 열의 걸음은 계획 맨 앞에 서고 계획에는 없다(§15.175). 이미 끝난 걸음이다.
            val step = ExecutionUnit("remedy-1", Route.ROBOT, InspectAsset.NAVIGATE, emptyMap(), null, null, null, state = UnitState.DONE, taskId = "$JOB#remedy-1")
            exec.mutableUnits.add(0, step)

            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 2), ROBOT), "걸음을 뺀 것으로 잡았다")
            assertEquals(emptyList(), w.mw.revisionRemovals())
            val dropped = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 3, targets = listOf("PUMP-01", "PANEL-3")), ROBOT))
            assertEquals("remedy-1", dropped.execution.units.first().unitId, "걸음이 지워졌다")
            assertEquals(listOf(travel("COMP-2"), "COMP-2"), w.mw.revisionRemovals().single().removed.map { it.unitId })
        }
    }

    @Test
    fun `판단 기록으로 제거 리비전 뒤의 최신 작업 지시를 다시 짓는다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            w.drive { "PUMP-01" in exec.completedUnits }
            val dropped = order(version = 2, targets = listOf("PUMP-01", "PANEL-3"))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(dropped, ROBOT))
            val record = w.mw.judgmentRecord(exec.executionId)

            // 앞에서는 뺀 설비의 단위가 남아 «계획에 없는 단위가 있다: [COMP-2.travel, COMP-2]» 로 다시 짓지 못했다.
            val again = Middleware(ClientRobotPort(w.harness.client()), now = { w.harness.clock.now() })
            val rebuilt = again.resume(dropped, ROBOT, ActiveMission(InspectAsset(), null), record)
            val accepted = assertIs<Middleware.Submission.Accepted>(rebuilt, "리빌드가 거부됐다: $rebuilt").execution
            assertEquals(exec.ids(), accepted.ids())
            assertEquals(listOf(UnitState.DONE, UnitState.DONE), accepted.units.take(2).map { it.state })
        }
    }

    @Test
    fun `항목을 빼면서 다른 항목을 더하는 리비전은 뺀 단위를 지우고 더한 단위를 들인다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 2, targets = listOf("PUMP-01", "PANEL-3", "FAN-4")), ROBOT))
            assertEquals(listOf(travel("PUMP-01"), "PUMP-01", travel("PANEL-3"), "PANEL-3", travel("FAN-4"), "FAN-4"), exec.ids())
            assertEquals(listOf(travel("COMP-2"), "COMP-2"), w.mw.revisionRemovals().single().removed.map { it.unitId })
        }
    }

    @Test
    fun `설비 속성이 단위 id 를 바꾸는 리비전은 빠진 id 의 단위만 지우고 남은 id 는 새 계획으로 교체한다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            // 자리를 지우면 이동이 사라지고 점검 하나가 계획 실패로 접힌다 — 이동의 id 가 빠진다.
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 2, unlocated = setOf("COMP-2")), ROBOT))
            assertFalse(travel("COMP-2") in exec.ids())
            assertEquals(UnitState.FAILED to InspectAsset.NO_LOCATION, exec.unit("COMP-2").let { it.state to it.failureClass })
            assertEquals(listOf(RemovedUnit(travel("COMP-2"), UnitState.PENDING)), w.mw.revisionRemovals().single().removed)
        }
    }

    @Test
    fun `지운 단위를 남는 단위가 선행으로 기다리면 거부하고 선행도 함께 빠지거나 관계가 없어지면 받는다`() {
        World(listOf(Chain())).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(chain(1, "X", "Y" to "X", "Z"), ROBOT)).execution
            val before = w.fingerprint(exec)
            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(chain(2, "Y" to "X", "Z"), ROBOT))
            assertEquals("리비전이 뺀 단위를 남는 단위가 선행으로 기다린다: Y after X — 선행을 함께 빼거나 새 작업 지시로 낸다", refused.reason)
            assertEquals(before, w.fingerprint(exec))

            // 새 계획에서 관계가 없어지면 받는다 — 연쇄로 지우지 않는다.
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(chain(2, "Y", "Z"), ROBOT))
            assertEquals(listOf("Y", "Z"), exec.ids())
            assertEquals(null, exec.unit("Y").after)
        }
        World(listOf(Chain())).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(chain(1, "X", "Y" to "X", "Z"), ROBOT)).execution
            // 선행도 함께 빠진다.
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(chain(2, "Z"), ROBOT))
            assertEquals(listOf("Z"), exec.ids())
            assertEquals(listOf("X", "Y"), w.mw.revisionRemovals().single().removed.map { it.unitId })
        }
    }

    @Test
    fun `항목을 모두 빼는 리비전은 거부한다 — 그 길은 취소다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            val before = w.fingerprint(exec)
            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(order(version = 2, targets = emptyList()), ROBOT))
            assertTrue(refused.reason.startsWith("리비전이 작업 지시의 항목을 모두 뺀다: "), refused.reason)
            assertTrue(refused.reason.endsWith(" — 실행을 멈추려면 취소한다"), refused.reason)
            assertEquals(before, w.fingerprint(exec))
        }
    }

    @Test
    fun `단위 id 가 바뀌는 리비전 뒤 새 선행이 의존 단위보다 먼저 서고 먼저 나가며 리빌드와 같은 순서다`() {
        World(listOf(Chain())).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(chain(1, "X1", "Y" to "X1"), ROBOT)).execution
            val moved = chain(2, "X2", "Y" to "X2")
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(moved, ROBOT))
            assertEquals(listOf("X2", "Y"), exec.ids(), "새 선행이 끝에 붙어 의존 단위가 먼저 선다")
            val record = w.mw.judgmentRecord(exec.executionId)
            val again = Middleware(ClientRobotPort(w.harness.client()), now = { w.harness.clock.now() }, capabilities = listOf(Chain()))
            val rebuilt = assertIs<Middleware.Submission.Accepted>(again.resume(moved, ROBOT, ActiveMission(Chain(), null), record)).execution
            assertEquals(exec.ids(), rebuilt.ids(), "리빌드의 순서와 다르다")

            w.drive { exec.settled() }
            assertEquals(listOf("CHAIN-1#X2", "CHAIN-1#Y"), w.tasks().map { it.taskId }, "의존 단위가 선행보다 먼저 나갔다")
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
        }
    }

    @Test
    fun `자리 없던 항목에 자리가 생기는 리비전은 이동을 점검 앞에 세워 먼저 낸다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(targets = listOf("COMP-2"), unlocated = setOf("COMP-2")), ROBOT)).execution
            assertEquals(listOf("COMP-2"), exec.ids())
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 2, targets = listOf("COMP-2")), ROBOT))
            assertEquals(listOf(travel("COMP-2"), "COMP-2"), exec.ids(), "점검이 이동보다 앞에 섰다")
            w.drive { exec.settled() }
            assertEquals(listOf(taskOf(travel("COMP-2")), taskOf("COMP-2")), w.tasks().map { it.taskId })
            assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState)
        }
    }

    @Test
    fun `받은 뒤에도 이미 낸 단위가 아직 안 낸 선행보다 앞에 서면 거부한다`() {
        World(listOf(Chain())).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(chain(1, "X", "Y" to "X"), ROBOT)).execution
            // 이미 어긋난 열 — 낸 Y 가 아직 안 낸 선행 X 보다 앞이다. 꼬리를 새 계획 순서로 세워도 제자리인 Y 는 못 옮긴다.
            exec.mutableUnits.reverse()
            exec.unit("Y").let {
                it.state = UnitState.RUNNING
                it.taskId = "CHAIN-1#Y"
            }
            val before = w.fingerprint(exec)
            val refused = assertIs<Middleware.Submission.Rejected>(w.mw.submit(chain(2, "X", "Y" to "X", "Z"), ROBOT))
            assertEquals("리비전 뒤 선행이 의존 단위보다 뒤에 선다: Y after X — 이미 낸 단위의 선행을 바꾸는 리비전이다. 새 작업 지시로 낸다", refused.reason)
            assertEquals(before, w.fingerprint(exec))
        }
    }

    @Test
    fun `지운 단위에 걸린 결함 막음과 허가 막음은 함께 걷히고 남은 단위가 다 끝났으면 정산한다`() {
        listOf("결함", "허가").forEach { scene ->
            World().use { w ->
                val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(targets = listOf("PUMP-01", "COMP-2")), ROBOT)).execution
                listOf(travel("PUMP-01"), "PUMP-01").forEach { id ->
                    exec.unit(id).state = UnitState.DONE
                    exec.unit(id).taskId = taskOf(id)
                }
                // 구동이 다음 단위(COMP-2 의 이동)를 막아 둔 모양 — 결함이면 실행 보류와 알림 표시, 허가면 막음 한 칸.
                if (scene == "결함") {
                    exec.blockedBy = listOf(dev.picasso.contracts.v1.Fault.newBuilder().setErrorType("E-STOP-LATCHED").build())
                    exec.physicalState = PhysicalState.OPERATOR_HOLD
                    exec.noticed.note(Notice.Block, "E-STOP-LATCHED")
                } else {
                    exec.permitBlock = PermitBlock(travel("COMP-2"), WithheldIssue.START, emptyList(), w.harness.clock.now())
                    exec.physicalState = PhysicalState.RUNNING
                }
                val responses = w.mw.responses().size
                assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 2, targets = listOf("PUMP-01")), ROBOT))
                assertEquals(PhysicalState.PHYSICALLY_DONE, exec.physicalState, "$scene: 지운 단위의 막음이 남아 정산하지 못했다")
                assertEquals(emptyList(), exec.blockedBy, scene)
                assertEquals(null, exec.permitBlock, scene)
                assertEquals(null, exec.noticed[Notice.Block], scene)
                assertEquals(responses + 1, w.mw.responses().size, scene)
                assertEquals(emptyList(), w.mw.responses().last().blockedBy, scene)
            }
        }
    }

    @Test
    fun `결함 막음은 대기 단위가 남으면 그대로 두고 리비전이 순서를 바꾼 뒤 다른 단위를 빼도 걷지 않는다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(targets = listOf("PUMP-01", "COMP-2")), ROBOT)).execution
            // 구동이 PUMP-01 의 이동을 결함으로 막아 둔 모양.
            exec.blockedBy = listOf(dev.picasso.contracts.v1.Fault.newBuilder().setErrorType("E-STOP-LATCHED").build())
            exec.physicalState = PhysicalState.OPERATOR_HOLD
            exec.noticed.note(Notice.Block, "E-STOP-LATCHED")
            // 설비 순서를 바꾼 리비전 — 남은 단위의 순서는 그대로다.
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 2, targets = listOf("COMP-2", "PUMP-01")), ROBOT))
            assertEquals(listOf(travel("PUMP-01"), "PUMP-01", travel("COMP-2"), "COMP-2"), exec.ids())
            // COMP-2 를 빼도 막힌 PUMP-01 이 남았다 — 막음과 보류를 걷지 않는다.
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 3, targets = listOf("PUMP-01")), ROBOT))
            assertEquals(listOf("E-STOP-LATCHED"), exec.blockedBy.map { it.errorType }, "남은 단위의 결함 막음을 걷었다")
            assertEquals(PhysicalState.OPERATOR_HOLD, exec.physicalState)
            assertEquals("E-STOP-LATCHED", exec.noticed[Notice.Block])
        }
    }

    @Test
    fun `설비 순서만 바꾼 리비전은 이미 선 실행 열을 흔들지 않고 받는다`() {
        World().use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(targets = listOf("PUMP-01", "COMP-2"), unlocated = setOf("PUMP-01")), ROBOT)).execution
            w.drive { exec.unit(travel("COMP-2")).state == UnitState.RUNNING }
            val before = exec.ids()
            assertEquals(listOf("PUMP-01", travel("COMP-2"), "COMP-2"), before)
            val reordered = assertIs<Middleware.Submission.Accepted>(
                w.mw.submit(order(version = 2, targets = listOf("COMP-2", "PUMP-01"), unlocated = setOf("PUMP-01")), ROBOT),
                "순서만 바꾼 정상 리비전을 거부했다",
            )
            assertEquals(before, reordered.execution.ids(), "남은 단위의 순서가 바뀌었다")
        }
    }

    @Test
    fun `제거 레지스터가 보존 상한에 차도 새 사건이 내보내기 파일에 실린다`() {
        World(retention = Retention(revisionRemovals = 1)).use { w ->
            val exec = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(), ROBOT)).execution
            val dir = Files.createTempDirectory("revision-removals-cap")
            val writer = BundleWriter(dir, w.mw.instanceId)
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 2, targets = listOf("PUMP-01", "PANEL-3")), ROBOT))
            assertTrue(writer.snapshot(w.mw.incidents(), w.mw.remedySearches(), w.harness.clock.now(), w.mw.revisionRemovals()))
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(version = 3, targets = listOf("PUMP-01")), ROBOT))
            assertEquals(1, w.mw.revisionRemovals().size)
            assertEquals(1L, w.mw.retentionEvictions().revisionRemovals)
            assertTrue(writer.snapshot(w.mw.incidents(), w.mw.remedySearches(), w.harness.clock.now(), w.mw.revisionRemovals()), "수가 같다고 새 사건을 건너뛰었다")
            val line = Files.readString(dir.resolve(LedgerExport.REVISION_REMOVALS)).trim()
            assertTrue("\"toVersion\":3" in line && "\"fromVersion\":2" in line, line)
            assertEquals(listOf(travel("PUMP-01"), "PUMP-01"), exec.ids())
        }
    }

    /** 설비마다 이동 단위 하나, 속성 `after` 가 선행이다 — 선행이 끊기는 리비전을 짓는 시험 케이퍼빌리티. */
    private class Chain : LogicalCapability {
        override val workMasterId: String = CHAIN
        override val maxEvidence: Evidence = Evidence.E0
        override fun plan(order: JobOrder): List<ExecutionUnit> = order.equipmentRequirements.map { e ->
            ExecutionUnit(
                e.id, Route.ROBOT, InspectAsset.NAVIGATE, mapOf(InspectAsset.P_LOCATION to "${e.id}-SPOT"), null, null, "${e.id}-SPOT",
            ).apply { after = e.properties["after"] }
        }
    }

    private fun chain(version: Int, vararg items: Any) = JobOrder(
        jobOrderId = "CHAIN-1",
        workMasterId = CHAIN,
        version = version,
        equipmentRequirements = items.map { item ->
            when (item) {
                is Pair<*, *> -> EquipmentRequirement(item.first as String, EquipmentUse.INSPECTION_TARGET, mapOf("after" to item.second as String))
                else -> EquipmentRequirement(item as String, EquipmentUse.INSPECTION_TARGET, emptyMap())
            }
        },
    )

    private companion object {
        const val ROBOT = "quad-1"
        const val JOB = "PATROL-9"
        const val CHAIN = "Chain"
        val ALL = listOf("PUMP-01", "PANEL-3", "COMP-2")
    }
}

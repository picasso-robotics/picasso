package dev.picasso.middleware

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.ProgressBasis
import dev.picasso.contracts.v1.ProgressKind
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **현장 시간값이 케이퍼빌리티 기본값을 덮는다**(§15.212).
 *
 * 미들웨어가 시간값을 읽는 자리는 일곱이다 — 정체 판정, `IN_DOUBT` 의 근거 윈도우와 유예, 완료 뒤 근거 기한, 근거 판정 둘,
 * 인시던트 봉인의 근거 윈도우. 자리마다 현장 값과 케이퍼빌리티 값이 다른 결과를 내도록 값을 골라, 어느 한 자리가 케이퍼빌리티
 * getter 로 돌아가도 그 자리의 시험이 빨개지게 한다.
 *
 * 현장 값 [SITE] 는 앞 100초 · 뒤 60초 · 유예 20초 · 정체 40초이고, 케이퍼빌리티 기본값은 30 · 15 · 60 · 300초다.
 *
 * 기체는 각본대로 답하는 더블이고 시계는 시험이 민다. 미믹을 안 쓰는 것은 완료 시각과 신호 시각을 초 단위로 맞추기 위해서다.
 */
class SiteTimingsTest {

    // ── 일곱 자리

    @Test
    fun `현장 정체 유예가 진행 정체 판정에 쓰인다`() {
        World(SITE).use { w ->
            val exec = w.submit()
            w.mw.pump()
            w.robot.push(w.task, TaskState.TASK_STATE_RUNNING, progress = 0.3)
            w.mw.pump()
            w.advance(45)
            w.mw.pump()

            // 현장 40초면 45초 뒤 정체다. 케이퍼빌리티 300초면 아직 아니다.
            assertTrue(exec.unit().progressStalled, "현장 정체 유예(40초)를 안 썼다: ${exec.unit().note}")
        }
    }

    @Test
    fun `현장 앞 폭이 IN_DOUBT 의 물리 관측에 쓰인다`() {
        World(SITE, lookup = ExecutionLookup.NONE).use { w ->
            w.robot.loseStart = true
            w.cell.programAt(SLOT, PART, at = w.t.minusSeconds(60))
            val exec = w.submit()
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)

            w.mw.pump()
            // 요청 60초 전의 신호는 현장 앞 폭(100초) 안이라 잠정 완료다. 케이퍼빌리티 앞 폭(30초)이면 유예를 기다린다.
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state, "현장 앞 폭을 안 썼다: ${exec.unit().note}")
            assertEquals(Verification.MATCHED, exec.unit().verification)
        }
    }

    @Test
    fun `현장 IN_DOUBT 유예가 운영자 대기 시점을 정한다`() {
        World(SITE, lookup = ExecutionLookup.NONE).use { w ->
            val exec = w.inDoubt()
            w.advance(30)
            w.mw.pump()

            // 현장 유예 20초가 지났다. 케이퍼빌리티 유예(60초)면 아직 IN_DOUBT 다.
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state, "현장 유예를 안 썼다: ${exec.unit().note}")
            assertTrue(exec.unit().note!!.contains("within grace"), exec.unit().note)
        }
    }

    @Test
    fun `완료 뒤 근거 기한이 현장 뒤 폭으로 정해진다`() {
        World(SITE).use { w ->
            val exec = w.submit()
            w.mw.pump()
            val doneAt = w.t
            w.robot.push(w.task, TaskState.TASK_STATE_SUCCEEDED)
            w.mw.pump()

            assertEquals(UnitState.VERIFYING, exec.unit().state)
            assertEquals(doneAt.plusSeconds(60), exec.unit().evidenceDeadline, "근거 기한이 현장 뒤 폭(60초)이 아니다")
        }
    }

    @Test
    fun `현장 앞 폭이 완료의 근거 판정에 쓰인다`() {
        World(SITE).use { w ->
            val exec = w.submit()
            w.mw.pump()
            w.cell.programAt(SLOT, PART, at = w.t.minusSeconds(60))
            w.robot.push(w.task, TaskState.TASK_STATE_SUCCEEDED)
            w.mw.pump()

            // 완료 60초 전의 신호는 현장 앞 폭(100초) 안이다. 케이퍼빌리티 앞 폭(30초)이면 옛 신호라 세지 않는다.
            assertEquals(UnitState.DONE, exec.unit().state, "현장 앞 폭을 안 썼다: ${exec.unit().note}")
            assertEquals(Evidence.E2, exec.unit().reached)
        }
    }

    @Test
    fun `현장 앞 폭이 실패 보고 뒤의 근거 판정에 쓰인다`() {
        World(SITE).use { w ->
            val exec = w.submit()
            w.mw.pump()
            w.cell.programAt(SLOT, PART, at = w.t.minusSeconds(60))
            w.robot.push(w.task, TaskState.TASK_STATE_FAILED)
            w.mw.pump()

            // 하위는 실패라는데 설비에는 현장 앞 폭 안의 신호가 있다. 케이퍼빌리티 앞 폭이면 그냥 실패다.
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state, "현장 앞 폭을 안 썼다: ${exec.unit().note}")
        }
    }

    @Test
    fun `인시던트 의도에 봉인 라운드의 설정 버전과 시간값 넷이 실린다`() {
        World(SITE, lookup = ExecutionLookup.NONE).use { w ->
            val exec = w.inDoubt()
            w.advance(30)
            w.mw.pump()
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state)

            val intent = w.mw.incidents().single().intent
            assertEquals(7L, intent.siteSettingsVersion)
            assertEquals("PT1M40S", intent.evidenceWindowBefore, "봉인의 근거 윈도우가 현장 앞 폭이 아니다")
            assertEquals("PT1M", intent.evidenceWindowAfter, "봉인의 근거 윈도우가 현장 뒤 폭이 아니다")
            assertEquals("PT20S", intent.inDoubtGrace)
            assertEquals("PT40S", intent.stallWindow)
        }
    }

    // ── 값이 없을 때

    @Test
    fun `현장 값이 없으면 케이퍼빌리티 값으로 판정하고 설정 칸은 비어 있다`() {
        // 케이퍼빌리티가 현장 값과 같은 수를 들게 한다. 소스를 안 주면 이 값으로 판정해야 한다.
        val tuned = Tuned(PrepareSequencedRack())
        var t = T0
        val robot = ScriptedRobot { t }.also { it.executionLookup = ExecutionLookup.NONE; it.loseStart = true }
        val mw = Middleware(robot, CellMimic(now = { t }), capabilities = listOf(tuned), now = { t })
        val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order(), ROBOT)).execution
        mw.pump()
        assertEquals(UnitState.IN_DOUBT, exec.unit().state)
        t = t.plusSeconds(30)
        mw.pump()

        assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state, "케이퍼빌리티 유예(20초)를 안 썼다: ${exec.unit().note}")
        val intent = mw.incidents().single().intent
        assertNull(intent.siteSettingsVersion, "현장 값이 없는데 설정 버전이 실렸다")
        assertNull(intent.inDoubtGrace)
        assertNull(intent.stallWindow)
        assertEquals("PT1M40S", intent.evidenceWindowBefore)
        assertEquals("PT1M", intent.evidenceWindowAfter)
    }

    // ── 적용 시점

    @Test
    fun `한 라운드 안에서는 소스를 한 번만 읽는다`() {
        // 읽을 때마다 버전이 오르는 소스. 라운드마다 한 번 읽으면 봉인한 버전이 그 라운드의 번호다.
        World(null, lookup = ExecutionLookup.NONE).use { w ->
            w.source = SiteTimingsSource {
                w.reads += 1
                SiteTimings.ofSeconds(w.reads.toLong(), 100, 60, 20, 40)
            }
            val exec = w.inDoubt()
            // 다른 기체로 실행을 하나 더 세운다. 실행이 둘이어도 라운드에 한 번 읽어야 한다.
            val other = assertIs<Middleware.Submission.Accepted>(w.mw.submit(order(OTHER_ORDER, OTHER_SLOT, OTHER_BIN), OTHER_ROBOT)).execution
            w.pump()
            assertEquals(UnitState.IN_DOUBT, other.unit().state)
            w.advance(30)
            w.pump()
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state)
            assertEquals(UnitState.OPERATOR_HOLD, other.unit().state)

            assertEquals(w.pumps, w.reads, "한 라운드에 소스를 여러 번 읽었다")
            assertEquals(
                listOf(w.pumps.toLong(), w.pumps.toLong()),
                w.mw.incidents().map { it.intent.siteSettingsVersion },
                "봉인이 그 라운드의 값이 아니다",
            )
        }
    }

    @Test
    fun `바꾼 IN_DOUBT 유예가 이미 IN_DOUBT 인 단위에 다음 라운드부터 미친다`() {
        World(SiteTimings.ofSeconds(1, 30, 15, 600, 300), lookup = ExecutionLookup.NONE).use { w ->
            val exec = w.inDoubt()
            w.advance(30)
            w.mw.pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state, "유예 600초 안인데 운영자에게 갔다")

            w.timings = SiteTimings.ofSeconds(2, 30, 15, 20, 300)
            w.mw.pump()
            assertEquals(UnitState.OPERATOR_HOLD, exec.unit().state, "바꾼 유예가 도는 단위에 안 미쳤다")
            assertEquals(2L, w.mw.incidents().single().intent.siteSettingsVersion)
        }
    }

    @Test
    fun `저장된 근거 기한은 뒤 폭이 바뀌어도 그대로다`() {
        World(SiteTimings.ofSeconds(1, 30, 60, 60, 300)).use { w ->
            val exec = w.submit()
            w.mw.pump()
            val doneAt = w.t
            w.robot.push(w.task, TaskState.TASK_STATE_SUCCEEDED)
            w.mw.pump()
            assertEquals(doneAt.plusSeconds(60), exec.unit().evidenceDeadline)

            // 뒤 폭을 20초로 줄인다. 이미 완료된 단위의 기한은 완료 순간의 값(60초)이다.
            w.timings = SiteTimings.ofSeconds(2, 30, 20, 60, 300)
            w.advance(30)
            w.mw.pump()
            assertEquals(doneAt.plusSeconds(60), exec.unit().evidenceDeadline, "근거 기한을 다시 계산했다")
            assertEquals(UnitState.VERIFYING, exec.unit().state, "줄인 뒤 폭으로 기한을 당겼다")
        }
    }

    @Test
    fun `저장된 근거 기한과 그 라운드의 앞 폭이 함께 쓰인다`() {
        // 완료된 단위의 판정은 저장된 기한(완료 순간의 뒤 폭)과 그 라운드의 앞 폭을 함께 쓴다(§15.212).
        World(SiteTimings.ofSeconds(1, 30, 60, 60, 300)).use { w ->
            val exec = w.submit()
            w.mw.pump()
            val doneAt = w.t
            w.cell.programAt(SLOT, PART, at = doneAt.minusSeconds(60))
            w.robot.push(w.task, TaskState.TASK_STATE_SUCCEEDED)
            w.mw.pump()
            // 완료 60초 전의 신호는 앞 폭 30초 밖이라 아직 근거가 아니다.
            assertEquals(UnitState.VERIFYING, exec.unit().state)

            // 앞 폭만 100초로 늘린다. 뒤 폭은 그대로라 기한도 그대로다.
            w.timings = SiteTimings.ofSeconds(2, 100, 60, 60, 300)
            w.mw.pump()
            assertEquals(doneAt.plusSeconds(60), exec.unit().evidenceDeadline)
            assertEquals(UnitState.DONE, exec.unit().state, "그 라운드의 앞 폭을 안 썼다: ${exec.unit().note}")
            assertEquals(Evidence.E2, exec.unit().reached)
        }
    }

    // ── 해시와 내보내기

    @Test
    fun `설정 버전과 시간값이 해시에 든다`() {
        World(SITE, lookup = ExecutionLookup.NONE).use { w ->
            w.inDoubt()
            w.advance(30)
            w.mw.pump()
            val b = w.mw.incidents().single()
            val i = b.intent
            val changed = mapOf(
                "설정 버전" to i.copy(siteSettingsVersion = 8),
                "앞 폭" to i.copy(evidenceWindowBefore = "PT1M"),
                "뒤 폭" to i.copy(evidenceWindowAfter = "PT2M"),
                "IN_DOUBT 유예" to i.copy(inDoubtGrace = "PT21S"),
                "정체 유예" to i.copy(stallWindow = "PT41S"),
            )
            changed.forEach { (what, other) ->
                assertNotEquals(b.digest(), b.copy(intent = other).digest(), "$what 가 해시에 안 들어갔다")
            }
        }
    }

    @Test
    fun `현장 설정 셋이 의도의 평평한 칸으로 함께 실리고 설정 없이 돈 줄은 셋이 함께 널이다`() {
        // 판 8(§15.322). 셋은 봉인 라운드의 값이고 함께 널이거나 함께 있다 — 인코더가 단언한다.
        World(SITE, lookup = ExecutionLookup.NONE).use { w ->
            w.inDoubt()
            w.advance(30)
            w.mw.pump()
            val line = LedgerExport.incidents(w.mw.incidents())
            val i = w.mw.incidents().single().intent
            assertTrue("\"siteSettingsVersion\":${i.siteSettingsVersion}" in line, line)
            assertTrue("\"inDoubtGrace\":\"${i.inDoubtGrace}\"" in line && "\"stallWindow\":\"${i.stallWindow}\"" in line, line)
            assertTrue("\"evidenceWindowBefore\":\"PT1M40S\"" in line, line)
            assertEquals("9", LedgerExport.SCHEMA_VERSION)

            // 설정 없이 돈 인시던트는 셋이 함께 널이다 — 키는 빼지 않는다.
            val bare = w.mw.incidents().single().let { it.copy(intent = it.intent.copy(siteSettingsVersion = null, inDoubtGrace = null, stallWindow = null)) }
            val bareLine = LedgerExport.incidents(listOf(bare))
            assertTrue("\"siteSettingsVersion\":null,\"inDoubtGrace\":null,\"stallWindow\":null" in bareLine, bareLine)
        }
    }

    // ── 더블

    private class World(timings: SiteTimings?, lookup: ExecutionLookup = ExecutionLookup.CLIENT_REFERENCE) : AutoCloseable {
        var t: Instant = T0
        val robot = ScriptedRobot { t }.also { it.executionLookup = lookup }
        val cell = CellMimic(now = { t })
        var timings: SiteTimings? = timings
        var source: SiteTimingsSource = SiteTimingsSource { this.timings }
        var reads = 0
        var pumps = 0
        val mw = Middleware(robot, cell, now = { t }, siteTimings = { source.current() })
        private var order = order()
        val task get() = "${order.jobOrderId}#$SLOT"

        fun advance(seconds: Long) {
            t = t.plusSeconds(seconds)
        }

        fun submit(o: JobOrder = order()): Middleware.Execution {
            order = o
            return assertIs<Middleware.Submission.Accepted>(mw.submit(o, ROBOT)).execution
        }

        /** 시작 응답을 잃어 `IN_DOUBT` 에 선 단위. 신호는 없다. */
        fun inDoubt(): Middleware.Execution {
            robot.loseStart = true
            val exec = submit()
            pump()
            assertEquals(UnitState.IN_DOUBT, exec.unit().state)
            return exec
        }

        /** 부른 수를 센다. 소스를 읽은 수와 맞대기 위해서다. */
        fun pump() {
            pumps += 1
            mw.pump()
        }

        override fun close() = Unit
    }

    /** 각본대로 답하는 기체. 시험이 태스크의 갱신을 넣는다. 스냅숏은 늘 온라인이고 결함이 없다. */
    private class ScriptedRobot(private val now: () -> Instant) : RobotPort {
        override var executionLookup: ExecutionLookup = ExecutionLookup.CLIENT_REFERENCE
        var loseStart = false
        private val updates = mutableMapOf<String, MutableList<WatchTaskResponse>>()
        private val revisions = mutableMapOf<String, Int>()
        private var index = 0L

        fun push(taskId: String, state: TaskState, progress: Double = 0.0) {
            updates.getOrPut(taskId) { mutableListOf() } += WatchTaskResponse.newBuilder()
                .setHeader(MessageHeader.newBuilder().setStateAsOf(now().toString()).setUpdateIndex(index++))
                .setState(state)
                .setRevision(revisions.getValue(taskId))
                .setProgress(progress)
                .setProgressBasis(ProgressBasis.newBuilder().setKind(ProgressKind.PROGRESS_KIND_MEASURED).setBasis("행동 1/3"))
                .build()
        }

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse {
            revisions[taskId] = revision
            if (loseStart) throw IllegalStateException("요청은 닿았고 응답을 잃었다")
            return StartTaskResponse.newBuilder()
                .setHandle(TaskHandle.newBuilder().setTaskId(taskId).setRevision(revision).setRobotId(robotId))
                .build()
        }

        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> = updates[handle.taskId].orEmpty().toList()
        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = CancelTaskResponse.getDefaultInstance()
        override fun snapshot(robotId: String): RobotSnapshot =
            RobotSnapshot(0, emptyList(), ConnectionState.CONNECTION_STATE_ONLINE, emptyMap())
        override fun replay(robotId: String, from: Long): Replay = Replay.Events(emptyList())
    }

    /** 현장 값 [SITE] 와 같은 수를 기본값으로 드는 케이퍼빌리티. 소스가 없을 때 이 값이 쓰이는지 본다. */
    private class Tuned(inner: LogicalCapability) : LogicalCapability by inner {
        override val evidenceWindow: EvidenceWindow get() = EvidenceWindow(Duration.ofSeconds(100), Duration.ofSeconds(60))
        override val inDoubtGrace: Duration get() = Duration.ofSeconds(20)
        override val stallWindow: Duration get() = Duration.ofSeconds(40)
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val SLOT = "RACK-212.S01"
        const val BIN = "SEQ-IN-02.BIN-A"
        const val PART = "ENGINE-COVER-A"
        val T0: Instant = Instant.parse("2026-10-09T00:00:00Z")

        /** 현장 값. 케이퍼빌리티 기본값(30 · 15 · 60 · 300)과 칸마다 다르다. */
        val SITE: SiteTimings = SiteTimings.ofSeconds(7, 100, 60, 20, 40)

        /** 둘째 실행 — 다른 기체, 다른 자리. */
        const val OTHER_ROBOT = "hum-03"
        const val OTHER_ORDER = "SEQ-213"
        const val OTHER_SLOT = "RACK-213.S01"
        const val OTHER_BIN = "SEQ-IN-03.BIN-A"

        fun order(jobOrderId: String = "SEQ-212", slot: String = SLOT, bin: String = BIN) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E2,
            equipmentRequirements = listOf(
                EquipmentRequirement(slot, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
                EquipmentRequirement(bin, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to PART)),
            ),
        )

        fun Middleware.Execution.unit() = units.single()
    }
}

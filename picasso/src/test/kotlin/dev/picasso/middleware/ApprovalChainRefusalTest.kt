package dev.picasso.middleware

import dev.picasso.capability.HoldEffects
import dev.picasso.capability.Remedy
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.ParameterDeclaration
import dev.picasso.contracts.v1.Precondition
import dev.picasso.contracts.v1.PreconditionSubject
import dev.picasso.contracts.v1.SkillDeclaration
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **승인 시점의 사슬 거절이 서 있는 제안을 바꾸지 않는다** — `commit` 의 «조건이 풀리면 같은 제안이 그대로 선다».
 *
 * 승인이 서면 조치 열을 계획 앞에 붙여 다시 접수하고, 그 접수가 관문의 사슬 검사를 다시 지난다. 그 사이 파지가 바뀌었으면 조치 열의
 * 첫 걸음이 걸리고 탐색이 그 걸음을 위한 조치 열을 다시 찾는다. 찾은 것을 제안으로 세우면 사람이 본 적 없는 조치 열이 승인을 기다리고,
 * 본 제안은 사라진다.
 *
 * **계약 카탈로그로는 이 길이 안 열린다.** 파지를 바꾸는 스킬이 `pick_place` 하나이고 끝나면 빈손이라, 어느 걸음에서 막혀도 탐색이
 * «든 채» 로 가는 조치를 못 찾는다. 그래서 시험 동안만 쥐기만 하는 스킬의 효과를 [HoldEffects] 의 표에 더한다([withGripOnly]).
 * 기체는 대본대로 답하는 시험 더블이다.
 */
class ApprovalChainRefusalTest {

    @Test
    fun `승인 시점의 사슬 거절은 서 있는 제안을 바꾸지 않고 조건이 풀리면 같은 제안으로 승인된다`() = withGripOnly {
        val robot = Robot()
        val mw = Middleware(robot, capabilities = listOf(OneSkill()), now = { T0 })

        // 기체를 쥔 실행 없이 손만 든 채다 — 사슬 검사의 출발점은 기체 스냅숏의 손이다. 든 채 도는 다른 실행이 출발점이면 그 손에 든 것이
        // 그 실행의 일이라 제안이 서지 않는다(§15.319).
        assertTrue(mw.executions().isEmpty())

        // 빈손을 요구하는 주문이 막히고 «놓는다» 가 제안으로 선다.
        assertIs<Middleware.Submission.Rejected>(mw.submit(order(JOB, "inspect"), ROBOT))
        assertEquals(listOf("pick_place"), mw.proposal(ROBOT, JOB)?.steps?.map { it.skillType })

        // 승인 전에 기체가 빈손이 됐다. 조치 열의 첫 걸음(든 채를 요구한다)이 사슬 검사에 걸린다.
        robot.hold = HoldKind.HOLD_KIND_EMPTY
        mw.pump()
        val refused = assertIs<Middleware.Submission.Rejected>(mw.approveRemedy(ROBOT, JOB, listOf(emptyMap()), OPERATOR))
        val searched = assertIs<RemedyOutcome.Found>(mw.remedySearches().last().outcome, "승인 시점의 거절이 조치 열을 찾지 않았다 — 이 시험이 보려는 길이 아니다")
        assertEquals(listOf("grip"), searched.steps.map { it.skillType })

        assertEquals(
            listOf("pick_place"),
            mw.proposal(ROBOT, JOB)?.steps?.map { it.skillType },
            "승인 시점의 거절이 서 있는 제안을 바꿨다",
        )
        assertNull(refused.remedy, "세우지 않은 조치 열을 거절에 실었다")

        // 조건이 풀리면 같은 제안이 승인된다.
        robot.hold = HoldKind.HOLD_KIND_HOLDING
        mw.pump()
        val accepted = assertIs<Middleware.Submission.Accepted>(mw.approveRemedy(ROBOT, JOB, listOf(emptyMap()), OPERATOR))
        assertEquals(listOf("remedy-1-pick_place", "u"), accepted.execution.units.map { it.unitId })
        assertEquals(listOf(RepeatedRemedy(ROBOT, listOf("pick_place"), 1)), mw.repeatedRemedies(atLeast = 1))
        assertTrue(mw.proposal(ROBOT, JOB) == null, "소모된 제안이 서 있다")
    }

    /**
     * 개정 실행 **자신의** 도는 단위가 든 채이면 개정의 사슬 거절이 제안을 세운다(§15.175) — 남의 점유가 아니다(§15.319).
     *
     * 앞 판은 이 장면을 «든 채 도는 다른 실행 뒤의 개정» 으로 지었다. 그 손에 든 것은 그 실행의 일이라 이제 제안이 서지 않는다. 계약 카탈로그의
     * `pick_place` 는 끝나면 놓으므로 그 실행 자신의 단위로는 든 채가 남지 않고, 그래서 대본대로 답하는 기체로 짓는다 — 도는 첫 단위
     * (`navigate_to`)는 파지를 안 바꾸므로 그 단위가 끝난 뒤의 파지도 든 채다.
     */
    @Test
    fun `개정의 거절이 세운 제안을 승인하면 조치 열이 다시 계획하는 첫 단위 앞에 선다`() {
        val robot = Robot()
        val mw = Middleware(robot, capabilities = listOf(TwoSkills()), now = { T0 })
        val exec = revisedWhileHolding(mw)
        assertEquals(listOf("pick_place"), mw.proposal(ROBOT, TWO)?.steps?.map { it.skillType }, "개정의 거절이 제안을 안 세웠다")

        val accepted = assertIs<Middleware.Submission.Accepted>(mw.approveRemedy(ROBOT, TWO, listOf(emptyMap()), OPERATOR))
        assertEquals(exec.executionId, accepted.execution.executionId, "승인이 다른 실행을 세웠다")
        assertEquals(2, exec.version, "승인이 개정을 내리지 않았다")
        assertEquals(listOf("u1", "remedy-1-pick_place", "u2"), exec.units.map { it.unitId }, "조치 열이 다시 계획하는 첫 단위 앞에 서지 않았다")
        assertTrue(exec.approvedBy != null, "개정이 승인자를 안 실었다")

        val again = assertIs<Middleware.Submission.Rejected>(mw.approveRemedy(ROBOT, TWO, listOf(emptyMap()), OPERATOR))
        assertEquals(ApprovalRefusal.CONSUMED, again.approvalRefusal, again.reason)
        assertEquals(exec.executionId, again.consumedApproval?.executionId)
    }

    /**
     * 이미 승인을 든 실행의 개정 거절은 제안을 세우지 않고 소모를 가리지 않는다(ADR 46, §15.175). 탐색 줄만 남는다.
     *
     * 소모된 주문의 개정판이 그 실행 자신의 든 채 단위 뒤에서 사슬 거절을 받는다. 그 거절이 제안을 세우면 재시도는 그 제안으로 판정되어
     * «이미 소모됐다» 를 영영 못 본다 — 판단 기록이 승인 하나만 실으므로 그 제안은 승인할 수도 없다.
     */
    @Test
    fun `개정 거절은 소모를 가리지 않는다`() {
        val robot = Robot()
        val mw = Middleware(robot, capabilities = listOf(TwoSkills()), now = { T0 })
        val exec = revisedWhileHolding(mw)
        assertIs<Middleware.Submission.Accepted>(mw.approveRemedy(ROBOT, TWO, listOf(emptyMap()), OPERATOR))

        // 마지막 줄만 보면 앞서 제안을 세운 줄이 대신 답한다 — 이 개정이 더한 줄만 본다.
        val searched = mw.remedySearches().size
        val revised = assertIs<Middleware.Submission.Rejected>(mw.submit(two(3, then = "inspect"), ROBOT))
        assertNull(revised.remedy, "개정 거절이 승인할 수 없는 조치 열을 내밀었다")
        assertNull(mw.proposal(ROBOT, TWO), "개정 거절이 소모된 열쇠에 제안을 세웠다")
        val noted = mw.remedySearches().drop(searched)
        assertEquals(1, noted.size, "개정 거절이 탐색의 답을 버렸다: $noted")
        assertIs<RemedyOutcome.Found>(noted.single().outcome)

        val again = assertIs<Middleware.Submission.Rejected>(mw.approveRemedy(ROBOT, TWO, listOf(emptyMap()), OPERATOR))
        assertEquals(ApprovalRefusal.CONSUMED, again.approvalRefusal, again.reason)
        assertEquals(exec.executionId, again.consumedApproval?.executionId)
    }

    /**
     * 같은 장면을 **값 없는 승인**으로 — 대상 이름은 관측에서 채운다. 그 관측은 개정할 실행 자신의 도는 단위이고, 값 채우기([AdmissionGate.fillHold])가
     * 그 실행을 «다른 실행» 으로 세면 `OBJECT_NOT_OBSERVED` 로 거절된다(§15.319). 사람의 문은 값을 들고 오므로 이 자리를 안 지난다.
     */
    @Test
    fun `개정의 거절이 세운 제안을 값 없이 승인하면 그 실행 자신의 관측으로 값이 찬다`() {
        val robot = Robot(objectParam = true)
        val mw = Middleware(
            robot,
            capabilities = listOf(TwoSkills()),
            now = { T0 },
            entitlements = object : Entitlements {
                override fun declaredFor(approverId: String): Entitlement? = if (approverId != AGENT.id) null else
                    Entitlement(AGENT.id, listOf(DeclaredAction("pick_place", emptyMap())), setOf(ROBOT), Instant.parse("2099-01-01T00:00:00Z"))
            },
        )
        val exec = revisedWhileHolding(mw)

        val outcome = mw.attemptApproval(ApprovalAttempt(AGENT, ROBOT, TWO, listOf("pick_place")))
        val approved = assertIs<ApprovalOutcome.Approved>(outcome, outcome.toString())
        assertEquals(exec.executionId, approved.executionId, "승인이 다른 실행을 세웠다")
        assertEquals(robot.objectRef, approved.steps.single().parameters[OBJECT], "대상 이름이 그 실행의 관측에서 오지 않았다")
        assertEquals(listOf("u1", "remedy-1-pick_place", "u2"), exec.units.map { it.unitId })
    }

    /**
     * **다른 실행이 쥔 기체에는 승인 접수가 손과 상관없이 서지 않는다**(§15.319). 제안이 선 뒤 다른 실행이 그 기체를 쥐었고, 그 단위의 손이
     * 빈손이거나 모르거나 든 채다. 조치 걸음(`pick_place`)에 조건이 없어서, 손이 든 채일 때만 막으면 빈손·모름에서 사슬이 그대로 지나
     * 남의 기체에 조치가 나간다. 사람의 문은 값을 들고 와 값 채우기를 안 지나므로 관문만 남는다.
     */
    @Test
    fun `다른 실행이 쥔 기체에는 손이 빈손이어도 승인 접수를 받지 않는다`() = occupiedApproval(HoldKind.HOLD_KIND_EMPTY)

    @Test
    fun `다른 실행이 쥔 기체에는 손을 몰라도 승인 접수를 받지 않는다`() = occupiedApproval(HoldKind.HOLD_KIND_NOT_OBSERVABLE)

    @Test
    fun `다른 실행이 쥔 기체에는 손이 든 채여도 승인 접수를 받지 않는다`() = occupiedApproval(HoldKind.HOLD_KIND_HOLDING)

    private fun occupiedApproval(hand: HoldKind) {
        val robot = Robot(pickRequires = null)
        val mw = Middleware(robot, capabilities = listOf(OneSkill()), now = { T0 })
        // 기체를 쥔 실행 없이 든 채 — 기체 스냅숏의 손으로 거절되고 «놓는다» 가 제안으로 선다.
        assertIs<Middleware.Submission.Rejected>(mw.submit(order(JOB, "inspect"), ROBOT))
        assertEquals(listOf("pick_place"), mw.proposal(ROBOT, JOB)?.steps?.map { it.skillType })

        // 다른 실행이 그 기체를 쥐고 첫 단위가 돈다. 그 단위와 스냅숏의 손이 [hand] 다.
        val other = assertIs<Middleware.Submission.Accepted>(mw.submit(order("MOVE-1", "navigate_to"), ROBOT)).execution
        robot.hold = hand
        repeat(2) { mw.pump() }
        assertTrue(other.active != null, "이 시험이 보려는 장면이 아니다 — 다른 실행의 도는 단위가 없다")

        val refused = assertIs<Middleware.Submission.Rejected>(mw.approveRemedy(ROBOT, JOB, listOf(emptyMap()), OPERATOR))
        assertEquals(ApprovalRefusal.REFUSED_BY_GATE, refused.approvalRefusal, refused.reason)
        assertTrue(other.executionId in refused.reason, refused.reason)
        assertEquals(listOf("pick_place"), mw.proposal(ROBOT, JOB)?.steps?.map { it.skillType }, "승인 시점 거절이 제안을 지웠다")
        assertTrue(mw.executions().none { it.order.jobOrderId == JOB }, "남이 쥔 기체에 조치 실행이 섰다")
        assertEquals(emptyList(), mw.repeatedRemedies(atLeast = 1), "나가지 않은 승인이 소모로 셌다")
    }

    /** 든 채 첫 단위가 도는 실행에 빈손을 요구하는 둘째 단위를 개정으로 더한다 — 사슬이 막고 «놓는다» 를 찾는다. */
    private fun revisedWhileHolding(mw: Middleware): Middleware.Execution {
        val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(two(1, then = "navigate_to"), ROBOT)).execution
        repeat(2) { mw.pump() }
        assertEquals(HoldKind.HOLD_KIND_HOLDING, exec.active?.hold?.kind, "이 시험이 보려는 출발점이 아니다")
        val revised = assertIs<Middleware.Submission.Rejected>(mw.submit(two(2, then = "inspect"), ROBOT), "든 채 빈손 단위를 더하는 개정이 섰다")
        assertTrue(revised.remedy != null, "개정의 거절이 조치 열을 안 내밀었다: ${revised.reason}")
        return exec
    }

    /** 단위 둘. 첫 단위의 스킬은 `first`, 둘째는 `then` 파라미터다. 자리를 쓰지 않는다. */
    private class TwoSkills : LogicalCapability {
        override val workMasterId: String = TWO_MASTER
        override val maxEvidence: Evidence = Evidence.E0
        override fun plan(order: JobOrder): List<ExecutionUnit> = listOf("u1" to "first", "u2" to "then").map { (id, key) ->
            ExecutionUnit(
                unitId = id,
                route = Route.ROBOT,
                skillType = order.parameters.getValue(key),
                parameters = emptyMap(),
                expectedIdentity = null,
                source = null,
                destination = null,
            )
        }
    }

    /** 단위 하나. 스킬은 작업 지시의 `skill` 파라미터다. 자리를 쓰지 않아 점유·구역 관문에 안 걸린다. */
    private class OneSkill : LogicalCapability {
        override val workMasterId: String = WORK_MASTER
        override val maxEvidence: Evidence = Evidence.E0
        override fun plan(order: JobOrder): List<ExecutionUnit> = listOf(
            ExecutionUnit(
                unitId = "u",
                route = Route.ROBOT,
                skillType = order.parameters.getValue("skill"),
                parameters = emptyMap(),
                expectedIdentity = null,
                source = null,
                destination = null,
            ),
        )
    }

    /**
     * 대본대로 답하는 기체. 받은 태스크는 전부 도는 중이고, 관측하는 파지는 [hold] 하나다 — 기체 스냅숏의 손도 그것이다.
     *
     * 선언: `inspect` 는 빈손, `pick_place` 는 든 채를 요구한다([pickRequires] 가 널이면 조건 없음). `grip` 과 `navigate_to` 는 조건이 없다.
     * [objectParam] 이면 `pick_place` 가 대상 이름(`object_id`, 관측에서 채우는 칸)을 필수로 받고, 관측하는 파지가 [objectRef] 를 싣는다.
     */
    private class Robot(
        private val pickRequires: HoldKind? = HoldKind.HOLD_KIND_HOLDING,
        private val objectParam: Boolean = false,
    ) : RobotPort {
        var hold: HoldKind = HoldKind.HOLD_KIND_HOLDING
        val objectRef = "PART-7"

        private fun held(): HoldState {
            val builder = HoldState.newBuilder().setKind(hold)
            if (hold == HoldKind.HOLD_KIND_HOLDING) builder.setObjectRef(objectRef)
            return builder.build()
        }

        override fun capabilities(robotId: String): Capability = Capability.newBuilder()
            .addSkills(skill("navigate_to"))
            .addSkills(skill("inspect", requires = HoldKind.HOLD_KIND_EMPTY))
            .addSkills(
                skill("pick_place", requires = pickRequires).apply {
                    if (objectParam) addParameters(ParameterDeclaration.newBuilder().setKey(OBJECT))
                },
            )
            .addSkills(skill("grip"))
            .build()

        override fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse =
            StartTaskResponse.newBuilder()
                .setHandle(TaskHandle.newBuilder().setTaskId(taskId).setRevision(revision).setRobotId(robotId))
                .build()

        override fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse> = listOf(
            WatchTaskResponse.newBuilder()
                .setHeader(MessageHeader.newBuilder().setStateAsOf(T0.toString()))
                .setState(TaskState.TASK_STATE_RUNNING)
                .setRevision(handle.revision)
                .setHold(held())
                .build(),
        )

        override fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse = CancelTaskResponse.getDefaultInstance()
        override fun snapshot(robotId: String): RobotSnapshot = RobotSnapshot(
            sequence = 0,
            faults = emptyList(),
            connection = ConnectionState.CONNECTION_STATE_ONLINE,
            tasks = emptyMap(),
            hold = held(),
        )
        override fun replay(robotId: String, from: Long): Replay? = null

        private fun skill(type: String, requires: HoldKind? = null) = SkillDeclaration.newBuilder().setSkillType(type).apply {
            requires?.let {
                addPreconditions(Precondition.newBuilder().setSubject(PreconditionSubject.PRECONDITION_SUBJECT_HOLD).setRequires(it))
            }
        }
    }

    private companion object {
        const val ROBOT = "hum-01"
        const val JOB = "CHECK-1"
        const val WORK_MASTER = "OneSkill"
        val T0: Instant = Instant.parse("2026-10-16T00:00:00Z")
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)
        val AGENT = Approver("recovery-1", ApproverKind.AGENT)
        const val OBJECT = "object_id"

        fun order(jobOrderId: String, skill: String) = JobOrder(jobOrderId, WORK_MASTER, 1, parameters = mapOf("skill" to skill))

        const val TWO = "TWO-1"
        const val TWO_MASTER = "TwoSkills"

        /** [TwoSkills] 의 작업 지시 — 첫 단위는 파지를 안 바꾸는 `navigate_to` 이다. */
        fun two(version: Int, then: String) = JobOrder(TWO, TWO_MASTER, version, parameters = mapOf("first" to "navigate_to", "then" to then))

        /**
         * 시험 동안만 **쥐기만 하는** 스킬 `grip` 의 효과(쥔다·안 놓는다)를 [HoldEffects] 의 표에 더하고, 끝나면 뺀다.
         *
         * 표는 [HoldEffects] 가 계약 카탈로그에서 한 번 읽어 든 것이다. 운영 코드에 효과를 갈아 끼울 자리를 두지 않으려고 시험이 그 표를
         * 직접 고친다 — 같은 JVM 의 다른 시험이 이 줄을 보지 않도록 반드시 뺀다.
         */
        fun withGripOnly(block: () -> Unit) {
            HoldEffects.grasps("grip") // 표를 읽힌다
            val delegate = HoldEffects::class.java.getDeclaredField("byName\$delegate").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val table = (delegate.get(null) as Lazy<*>).value as MutableMap<String, Pair<Boolean, Boolean>>
            check("grip" !in table) { "계약 카탈로그에 grip 이 생겼다 — 이 시험의 전제가 바뀌었다" }
            table["grip"] = true to false
            try {
                check(HoldEffects.after("grip", HoldState.getDefaultInstance()).kind == HoldKind.HOLD_KIND_HOLDING) { "효과를 더하지 못했다" }
                block()
            } finally {
                table.remove("grip")
            }
        }
    }
}

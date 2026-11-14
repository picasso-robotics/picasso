package dev.picasso.middleware

import dev.picasso.capability.Remedy
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.harness.Harness
import dev.picasso.mimic.control.v1.ForceFaultRequest
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 자동 승인 자격 — **밖에서 선언되고 안에서 판정된다**(ADR 43 · 44 · §15.165).
 *
 * 계산된 자격은 계산이 틀리면 조용히 넓어진다. 선언된 자격은 사람의 행위이므로 감사 가능하고 만료를
 * 걸 수 있다. 런타임이 하는 일은 **선언 목록에 있는가** 를 보는 것뿐이고, 좁히는 방향으로만 작동한다.
 *
 * ## 문이 둘이고 힘이 다르다(ADR 44)
 *
 * **사람의 문**([Middleware.approveRemedy])은 값을 든다. **밖의 문**([Middleware.attemptApproval])은
 * 값을 실을 칸이 없고, 주문도 걸음도 못 싣는다 — 부르는 쪽이 할 수 있는 말은 «그것을 하라» 하나뿐이다.
 * 이 시험이 거는 것은 그 비대칭이며, 에이전트 승인은 전부 밖의 문으로 지난다.
 */
class EntitlementTest {

    /** 시험이 선언 목록을 든다. 값이 어디에 사는지는 이 층이 모른다 — 배치의 결정이다. */
    private class Declared(var rows: Map<String, Entitlement> = emptyMap()) : Entitlements {
        override fun declaredFor(approverId: String): Entitlement? = rows[approverId]
    }

    /** 제안이 선 **뒤에** 능력을 못 물어보게 된 기체 — 제안과 승인 사이에 선이 끊긴 경우. */
    private class GoesBlind(private val delegate: RobotPort) : RobotPort by delegate {
        var blind = false
        override fun capabilities(robotId: String): Capability? =
            if (blind) null else delegate.capabilities(robotId)
    }

    private class World(
        entitlements: Entitlements = Entitlements.None,
        withholdEvery: Int = 0,
        floors: FloorOwnership = FloorOwnership.None,
        pair: Boolean = false,
    ) : AutoCloseable {
        val harness = Harness(if (pair) mapOf(ROBOT to PRECOND, OTHER to PRECOND) else mapOf(ROBOT to PRECOND))
        val cell = CellMimic(now = { harness.clock.now() })
        val robots = GoesBlind(ClientRobotPort(harness.client()))
        val mw = Middleware(
            robots,
            cell,
            now = { harness.clock.now() },
            withholdEvery = withholdEvery,
            entitlements = entitlements,
            floors = floors,
        )

        private var racks = 0

        fun drive(rounds: Int = 80, step: Duration = Duration.ofSeconds(1), until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(step)
                Thread.sleep(40)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다")
        }

        fun forceFault(errorType: String, taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(ROBOT).setErrorType(errorType).setTaskId(taskId).build(),
        ).taskState

        /** 든 채로 도는 랙 하나. 여기서 든 것이 점검 순회를 막는다. */
        fun holdingRack(): Middleware.Execution {
            val order = JobOrder(
                jobOrderId = "SEQ-${++racks}",
                workMasterId = PrepareSequencedRack.WORK_MASTER,
                version = 17,
                requiredEvidence = Evidence.E2,
                materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", 1)),
                equipmentRequirements = listOf(
                    EquipmentRequirement("RACK-204.S0$racks", "destination", mapOf("material" to "ENGINE-COVER-A")),
                    EquipmentRequirement("SEQ-IN-02.BIN-A", "source", mapOf("material" to "ENGINE-COVER-A")),
                ),
            )
            order.equipmentRequirements.filter { it.equipmentUse == "destination" }
                .forEach { cell.program(it.id, it.properties["material"]) }
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order, ROBOT)).execution
            drive { exec.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING }
            return exec
        }

        /**
         * 든 채로 점검 순회를 밀어 넣어 제안을 하나 세운다.
         *
         * **든 채로 둔 채 돌려준다.** 자동 승인의 대상은 «지금 든 것» 이고 그 관측은 승인 시점에 다시
         * 본다(ADR 44) — 여기서 랙을 완주시키면 기체가 이미 놓은 뒤라 그 출처가 사라지고, 시험은
         * 값의 출처가 아니라 값이 없는 상황을 보게 된다.
         *
         * **랙은 든 채 실패해 사람이 취소로 닫는다**([leaveHolding]). 든 채 도는 랙 뒤에 내면 그 손에 든 것이 랙의 일이라
         * 제안이 서지 않는다(§15.319). 기체를 쥔 실행 없이 손만 든 채로 남아야 기체 스냅숏의 손으로 거절되고 제안이 선다.
         *
         * **돌려주는 것이 널일 수 있다** — 가릴 차례면 제안이 조회로 안 보인다. 그것이 가림의 성질이고,
         * 여기서 널이 아니라고 단정하면 가림을 쓰는 시험이 전제부터 틀린다.
         */
        fun standingProposal(jobOrderId: String = "PATROL-1"): Remedy.Found? {
            mw.leaveHolding(holdingRack(), harness) { drive(until = it) }
            assertIs<Middleware.Submission.Rejected>(mw.submit(patrol(jobOrderId), ROBOT))
            return mw.proposal(ROBOT, jobOrderId)
        }

        /**
         * 구동이 더는 돌리지 않게 끝낸다. 운영자 보류나 `PARTIAL` 에 서면 취소로 끝낸다 — 놓기([Middleware.forget])의 시험이 어떻게
         * 끝났는지가 아니라 끝났다는 것만 쓴다.
         */
        fun ended(exec: Middleware.Execution) {
            drive(rounds = 400) { exec.active == null && (exec.physicalState.isSettled || exec.physicalState == PhysicalState.OPERATOR_HOLD) }
            if (exec.physicalState.pumps) {
                assertTrue(mw.cancel(exec.executionId))
                drive { exec.physicalState == PhysicalState.ABORTED }
            }
        }

        override fun close() = harness.close()
    }

    // ── 사람과 에이전트가 다르다

    @Test
    fun `사람은 선언 없이 승인한다`() {
        // ★선언 목록은 «사람 대신» 누르는 것을 허락하는 자리다. 사람까지 대조하면 그 목록이 운영자
        //   명부가 되고, 한 명 빠진 날 라인이 선다.
        World(Entitlements.None).use { w ->
            w.standingProposal()
            assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(PLACE), OPERATOR))
        }
    }

    @Test
    fun `에이전트는 값을 실어 승인하지 못한다`() {
        // ★★**값을 실을 수 있다는 것이 곧 조치를 기술할 수 있다는 뜻이다**(ADR 44). 선언이 덮는 것은
        //   «어느 스킬» 까지인데 값을 부르는 쪽이 고르면 실제 권한이 선언보다 넓어진다 — `pick_place`
        //   자격 하나로 무엇이든 어디에든 놓게 된다. 프로세스 안에서는 안 드러났다.
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal()
            val answer = assertIs<Middleware.Submission.Rejected>(
                w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(PLACE), AGENT),
            )
            assertTrue("값을 실어 승인하지 못한다" in answer.reason, answer.reason)
            assertEquals(ApprovalRefusal.VALUES_NOT_ACCEPTED, answer.approvalRefusal)
            assertNotNull(w.mw.proposal(ROBOT, "PATROL-1"), "거절이 제안을 소모했다")
        }
    }

    @Test
    fun `선언이 없으면 에이전트 승인은 거절된다`() {
        // ★deny by default(ADR 42). 아무것도 선언 안 한 배치에서 자동 승인은 하나도 안 통과한다 —
        //   그러나 사람이 누를 수 있으므로 라인은 서지 않는다.
        World(Entitlements.None).use { w ->
            w.standingProposal()
            assertEquals(ApprovalRefusal.NOT_DECLARED, refusal(w.mw.attemptApproval(attempt())))
        }
    }

    @Test
    fun `선언된 에이전트는 승인한다`() {
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal()
            assertIs<ApprovalOutcome.Approved>(w.mw.attemptApproval(attempt()))
        }
    }

    // ── 값은 어디서 오나(ADR 44) — 선언과 관측 둘뿐이다

    @Test
    fun `자동 승인의 값은 선언과 관측에서 오고 그 둘이 답에 돌아온다`() {
        // ★★부르는 쪽은 값을 하나도 안 보냈다. 목적지는 **사람이 미리 적은 것**이고 대상은 **기체가
        //   말한 것**이다. 돌려주지 않으면 부르는 쪽에서도 눈 감고 누르는 단추가 된다.
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal()
            val held = assertNotNull(heldObject(w), "기체가 든 것의 이름을 안 줬다 — 이 시험의 전제가 비었다")

            val approved = assertIs<ApprovalOutcome.Approved>(w.mw.attemptApproval(attempt()))
            val step = assertNotNull(approved.steps.singleOrNull(), "걸음이 하나가 아니다: ${approved.steps}")
            assertEquals(PrepareSequencedRack.SKILL, step.skillType)
            assertEquals(DROP_SLOT, step.parameters[PrepareSequencedRack.P_DESTINATION], "목적지가 선언에서 안 왔다")
            assertEquals(held, step.parameters[PrepareSequencedRack.P_OBJECT], "대상이 관측에서 안 왔다")
        }
    }

    @Test
    fun `필수 값이 선언에 없으면 자동 승인이 거절된다`() {
        // ★빈 값을 채워 넣는 갈래를 두면 그 순간 승인이 무엇을 승인하는지 모르는 채 눌린다.
        World(Declared(mapOf(AGENT.id to full(actions = listOf(DeclaredAction(PrepareSequencedRack.SKILL, emptyMap())))))).use { w ->
            w.standingProposal()
            assertEquals(ApprovalRefusal.VALUE_NOT_DECLARED, refusal(w.mw.attemptApproval(attempt())))
        }
    }

    @Test
    fun `선언이 대상을 적으면 좁히기로만 쓰인다`() {
        // 관측과 같으면 통과한다 — 선언은 «이 상황의 자격» 이라고 좁힌 것이고, 그 좁힘은 유효하다.
        // **이름을 여기 적지 않는다** — 적으면 어느 칸이 대상의 이름인지를 시험이 다시 정하게 되고,
        // 그 판단은 계약(`is_object_reference`)의 것이다.
        val rows = Declared()
        World(rows).use { w ->
            w.standingProposal()
            val held = assertNotNull(heldObject(w), "기체가 든 것의 이름을 안 줬다 — 이 시험의 전제가 비었다")
            rows.rows = mapOf(AGENT.id to full(actions = listOf(narrowed(held))))

            assertIs<ApprovalOutcome.Approved>(w.mw.attemptApproval(attempt()))
        }
    }

    @Test
    fun `선언한 대상과 든 것이 다르면 자동 승인이 거절된다`() {
        // ★★**선언은 사실을 못 이긴다.** 덮어쓸 수 있으면 자동 승인이 엉뚱한 것을 집고, 그 사고는
        //   선언을 적은 사람의 의도와도 무관하다.
        World(Declared(mapOf(AGENT.id to full(actions = listOf(narrowed("다른-물건")))))).use { w ->
            w.standingProposal()
            assertEquals(ApprovalRefusal.DECLARED_CONTRADICTS_OBSERVED, refusal(w.mw.attemptApproval(attempt())))
        }
    }

    // ── 선언이 좁히는 세 축

    @Test
    fun `만료된 선언으로는 승인하지 못한다`() {
        // ★만료 없는 선언은 다시 들여다볼 일이 없다. 그러면 프로파일이 바뀌어도 그 자격이 그대로 남는다.
        World(Declared(mapOf(AGENT.id to full(expiresAt = Instant.parse("2020-01-01T00:00:00Z"))))).use { w ->
            w.standingProposal()
            assertEquals(ApprovalRefusal.EXPIRED, refusal(w.mw.attemptApproval(attempt())))
        }
    }

    @Test
    fun `철회된 선언은 미선언과 다른 답을 받는다`() {
        // ★★**이것이 ADR 45 의 요점이다.** 둘 다 「지금 자격이 없다」인데 다음 행동이 반대다 —
        //   철회는 「무언가 바뀌었다」라 사후 검토로 가고, 미선언은 「원래 없었다」라 선언을 올리러 간다.
        //   접으면 강등된 사람을 선언 수정하러 보낸다.
        World(Declared(mapOf(AGENT.id to full(revocation = revoked())))).use { w ->
            w.standingProposal()
            val answer = assertIs<ApprovalOutcome.Refused>(w.mw.attemptApproval(attempt()))
            assertEquals(ApprovalRefusal.REVOKED, answer.refusal)
            // 사람이 읽을 사유에 누가·언제·왜가 남는다. 읽는 쪽이 갈래를 가르는 것은 종류이지 이 문장이 아니다.
            assertTrue("cell-lead" in answer.reason, answer.reason)
        }
        World(Declared(emptyMap())).use { w ->
            w.standingProposal()
            assertEquals(ApprovalRefusal.NOT_DECLARED, refusal(w.mw.attemptApproval(attempt())))
        }
    }

    @Test
    fun `철회가 만료보다 먼저 답한다`() {
        // ★**틀리는 방향을 정해 둔다.** 철회된 선언이 만료까지 지났을 때 `EXPIRED` 를 내면, 받은 쪽은
        //   갱신하면 되는 줄 알고 기간만 늘려 **철회를 조용히 되돌린다.**
        val both = full(expiresAt = Instant.parse("2020-01-01T00:00:00Z"), revocation = revoked())
        World(Declared(mapOf(AGENT.id to both))).use { w ->
            w.standingProposal()
            assertEquals(ApprovalRefusal.REVOKED, refusal(w.mw.attemptApproval(attempt())))
        }
    }

    @Test
    fun `철회는 사람의 승인을 막지 않는다`() {
        // ★**자격은 좁히기만 하고 없애지 않는다**(§15.169 의 뒷절). 사람은 선언 없이 누르므로 철회가
        //   라인을 세우지 않는다 — 세우면 아무도 철회를 쓰지 않게 되고 그때 이 장치가 죽는다.
        World(Declared(mapOf(AGENT.id to full(revocation = revoked())))).use { w ->
            assertNotNull(w.standingProposal())
            assertEquals(ApprovalRefusal.REVOKED, refusal(w.mw.attemptApproval(attempt())))
            // 거절이 제안을 소모하지 않았으므로 사람이 그대로 누른다.
            assertIs<Middleware.Submission.Accepted>(
                w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(PLACE), OPERATOR),
                "철회가 사람의 승인까지 막았다",
            )
        }
    }

    @Test
    fun `범위 밖 기체는 승인하지 못한다`() {
        World(Declared(mapOf(AGENT.id to full(robotIds = setOf("다른-기체"))))).use { w ->
            w.standingProposal()
            assertEquals(ApprovalRefusal.ROBOT_OUT_OF_SCOPE, refusal(w.mw.attemptApproval(attempt())))
        }
    }

    @Test
    fun `선언이 안 덮는 조치 유형은 승인하지 못한다`() {
        World(Declared(mapOf(AGENT.id to full(actions = listOf(DeclaredAction("다른_스킬", emptyMap())))))).use { w ->
            val proposal = assertNotNull(w.standingProposal())
            val answer = assertIs<ApprovalOutcome.Refused>(w.mw.attemptApproval(attempt()))
            assertEquals(ApprovalRefusal.SKILL_OUT_OF_SCOPE, answer.refusal)
            assertTrue(proposal.steps.first().skillType in answer.reason, answer.reason)
        }
    }

    @Test
    fun `빈 조치 목록은 전부가 아니라 아무것도 아니다`() {
        // ★빈 것을 «전부» 로 읽으면 선언을 반쯤 적은 배치가 무제한 자격을 얻는다. 넓히려면 적어야 한다.
        // ★**다른 축은 채워 둔다** — 앞 관문이 먼저 막으면 이 시험이 무엇을 보는지 알 수 없다.
        World(Declared(mapOf(AGENT.id to full(actions = emptyList())))).use { w ->
            w.standingProposal()
            assertEquals(ApprovalRefusal.SKILL_OUT_OF_SCOPE, refusal(w.mw.attemptApproval(attempt())))
        }
    }

    @Test
    fun `빈 기체 집합도 전부가 아니라 아무것도 아니다`() {
        World(Declared(mapOf(AGENT.id to full(robotIds = emptySet())))).use { w ->
            w.standingProposal()
            assertEquals(ApprovalRefusal.ROBOT_OUT_OF_SCOPE, refusal(w.mw.attemptApproval(attempt())))
        }
    }

    @Test
    fun `같은 조치 유형을 두 번 선언하면 선언 자체가 안 선다`() {
        // 어느 값이 쓰이는지 선언이 답하지 않는다. 하나를 고르는 규칙을 만들면 그 규칙이 선언보다 세진다.
        val twice = runCatching {
            Entitlement(
                AGENT.id,
                listOf(narrowed("가"), narrowed("나")),
                setOf(ROBOT),
                Instant.parse("2099-01-01T00:00:00Z"),
            )
        }
        assertTrue(twice.isFailure, "같은 유형 둘을 든 선언이 그대로 섰다")
    }

    // ── 가림과 자격의 순서

    @Test
    fun `가려 둔 제안은 자격이 있어도 승인하지 못한다`() {
        // 자격은 «누를 수 있는가» 이고 가림은 «지금 누를 것인가» 다. 자격이 가림을 뚫으면
        // 의도적 비자동화가 자동 승인 앞에서만 무의미해진다.
        World(Declared(mapOf(AGENT.id to full())), withholdEvery = 1).use { w ->
            assertNull(w.standingProposal(), "가릴 차례인데 제안이 조회로 보인다 — 이 시험의 전제가 틀렸다")
            assertTrue(w.mw.withheldProposal(ROBOT, "PATROL-1"))
            assertEquals(ApprovalRefusal.WITHHELD, refusal(w.mw.attemptApproval(attempt())))
        }
    }

    @Test
    fun `가림이 자격보다 먼저 판정된다`() {
        // ★★**순서가 규약이다.** 자격을 먼저 보면 선언 없는 에이전트가 «자격 없음» 을 받고, 가림이
        //   있었다는 사실이 답에서 사라진다 — 사람이 먼저 진단해야 한다는 지시가 전달되지 않는다.
        //   가려진 줄에는 걸음이 없으므로 부르는 쪽은 그 제안을 달리 알아볼 방법도 없다.
        World(Entitlements.None, withholdEvery = 1).use { w ->
            assertNull(w.standingProposal(), "가릴 차례인데 제안이 조회로 보인다 — 이 시험의 전제가 틀렸다")
            assertEquals(ApprovalRefusal.WITHHELD, refusal(w.mw.attemptApproval(attempt())))
        }
    }

    // ── 부르는 쪽이 본 것과 지금이 다르면

    @Test
    fun `본 조치 열과 지금 제안이 다르면 거절된다`() {
        // 읽은 뒤 제안이 바뀌면 부르는 쪽은 **자기가 못 본 것**을 자기 이름으로 승인하는 중이다.
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal()
            val stale = attempt(saw = listOf("navigate_to", PrepareSequencedRack.SKILL))
            assertEquals(ApprovalRefusal.PROPOSAL_CHANGED, refusal(w.mw.attemptApproval(stale)))
            assertNotNull(w.mw.proposal(ROBOT, "PATROL-1"), "거절이 제안을 소모했다")
        }
    }

    // ── 거절이 사람의 길을 막지 않는다

    @Test
    fun `에이전트가 거절돼도 제안은 사람에게 남는다`() {
        // ★거부가 제안을 소모하면 «사람에게 남는다» 가 성립하지 않는다 — 자격이 없다는 이유로
        //   사람까지 누를 것을 잃는다. 자격은 좁히기만 하지 없애지 않는다.
        World(Entitlements.None).use { w ->
            w.standingProposal()
            assertIs<ApprovalOutcome.Refused>(w.mw.attemptApproval(attempt()))

            assertNotNull(w.mw.proposal(ROBOT, "PATROL-1"), "에이전트 거절이 제안을 소모했다")
            assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(PLACE), OPERATOR))
        }
    }

    @Test
    fun `선언이 만료돼도 사람 승인으로 복귀한다`() {
        // ★만료가 라인을 세우면 아무도 만료를 짧게 걸지 않는다. 내려오는 길이 있어야 비대칭이 유지된다.
        World(Declared(mapOf(AGENT.id to full(expiresAt = Instant.parse("2020-01-01T00:00:00Z"))))).use { w ->
            w.standingProposal()
            assertIs<ApprovalOutcome.Refused>(w.mw.attemptApproval(attempt()))

            assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(PLACE), OPERATOR))
        }
    }

    @Test
    fun `관문이 거절해도 제안은 남는다`() {
        // ★★자리 경쟁이나 무소유 바닥으로 못 들어간 것은 **자격의 문제가 아니다.** 그 순간 제안을
        //   소모하면 조건이 풀린 뒤 누를 것이 사라지고, 사람도 다시 못 누른다.
        World(Declared(mapOf(AGENT.id to full())), floors = UNOWNED_PUMP_ROOM).use { w ->
            w.standingProposal()
            val answer = assertIs<ApprovalOutcome.Refused>(w.mw.attemptApproval(attempt()))
            assertEquals(ApprovalRefusal.REFUSED_BY_GATE, answer.refusal)
            assertTrue("소유자가 없다" in answer.reason, answer.reason)
            assertNotNull(w.mw.proposal(ROBOT, "PATROL-1"), "관문 거절이 제안을 소모했다")
            // 재시도도 서 있는 제안으로 판정된다 — 관문 거절은 소모가 아니다(ADR 46).
            assertEquals(ApprovalRefusal.REFUSED_BY_GATE, refusal(w.mw.attemptApproval(attempt())))
            // 사람의 문도 같은 종류를 싣는다 — 사유 산문으로만 내면 부르는 쪽이 문자열로 갈라야 한다.
            val human = assertIs<Middleware.Submission.Rejected>(w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(PLACE), OPERATOR))
            assertEquals(ApprovalRefusal.REFUSED_BY_GATE, human.approvalRefusal, human.reason)
        }
    }

    @Test
    fun `제안이 선 뒤 능력을 못 물어보면 거절된다`() {
        // 선이 끊긴 사이에 승인하면 조치가 실행 가능한지 확인할 방법이 없다. «모른다» 로 보내지 않는다.
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal()
            w.robots.blind = true
            assertEquals(ApprovalRefusal.CAPABILITY_UNKNOWN, refusal(w.mw.attemptApproval(attempt())))
        }
    }

    @Test
    fun `그 주문이 이미 다른 기체에서 돌면 승인으로 안 센다`() {
        // ★★접수가 멱등으로 접히면 **조치 열이 안 나간다.** 성공으로 내면 «승인했는데 아무 일도 안
        //   일어났다» 가 초록으로 보이고, 그것이 이 저장소가 반복해 물린 조용한 통과의 모양이다.
        World(Declared(mapOf(AGENT.id to full())), pair = true).use { w ->
            w.standingProposal()
            // 같은 주문을 빈손인 다른 기체가 받는다 — 이제 그 주문에는 실행이 하나 서 있다.
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol(), OTHER))

            assertEquals(ApprovalRefusal.REMEDY_NOT_APPLIED, refusal(w.mw.attemptApproval(attempt())))
        }
    }

    @Test
    fun `그 주문이 다른 기체에서 끝나 놓였으면 승인으로 안 센다`() {
        // ★놓은 뒤에는 표에 실행이 없어 멱등으로 접히지 않는다. 그 자리를 관문 거절로 내면 «조건이 풀리면 다시 누른다» 로 읽혀
        //   이미 끝난 주문에 조치를 다시 실으려 한다.
        World(Declared(mapOf(AGENT.id to full())), pair = true).use { w ->
            w.standingProposal()
            val other = assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol(), OTHER)).execution
            w.ended(other)
            w.mw.pending().forEach { w.mw.ack(it.jobResponseId) }
            assertEquals(ForgetOutcome.Forgotten, w.mw.forget(other.executionId))

            val answer = assertIs<ApprovalOutcome.Refused>(w.mw.attemptApproval(attempt()))
            assertEquals(ApprovalRefusal.REMEDY_NOT_APPLIED, answer.refusal)
            assertTrue(answer.reason.startsWith(Middleware.FORGOTTEN_JOB_ORDER), answer.reason)
            val person = assertIs<Middleware.Submission.Rejected>(w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(PLACE), OPERATOR))
            assertTrue(person.reason.startsWith(Middleware.FORGOTTEN_JOB_ORDER), person.reason)
            assertEquals(ApprovalRefusal.REMEDY_NOT_APPLIED, person.approvalRefusal, "두 문이 놓은 작업 지시를 다른 종류로 냈다")
            assertNotNull(w.mw.proposal(ROBOT, "PATROL-1"), "조치가 안 나갔는데 제안을 소모했다")
            assertTrue(w.mw.executions().none { it.order.jobOrderId == "PATROL-1" }, "놓은 주문이 승인으로 다시 섰다")
        }
    }

    @Test
    fun `승인으로 선 실행을 놓아도 다시 온 승인은 그 소모 기록을 받는다`() {
        // ★소모 기록은 실행이 아니라 승인 장부의 것이다(ADR 46). 실행과 함께 빼면 다시 온 승인이 «선 적이 없다» 를 받는다.
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal()
            val approved = assertIs<ApprovalOutcome.Approved>(w.mw.attemptApproval(attempt()))
            val exec = assertNotNull(w.mw.execution(approved.executionId))
            w.ended(exec)
            w.mw.pending().forEach { w.mw.ack(it.jobResponseId) }
            assertEquals(ForgetOutcome.Forgotten, w.mw.forget(exec.executionId))

            val again = assertIs<ApprovalOutcome.Refused>(w.mw.attemptApproval(attempt()))
            assertEquals(ApprovalRefusal.CONSUMED, again.refusal)
            assertEquals(approved.executionId, assertNotNull(again.consumed).executionId)
        }
    }

    // ── 소모된 제안(ADR 46) — «이미 소모됐다» 와 «선 적이 없다» 를 가른다

    @Test
    fun `승인한 제안에 다시 시도하면 누가 언제 무엇을 보냈는지 돌려받는다`() {
        // ★★응답을 받기 전에 죽었다가 다시 보낸 바깥 루프의 자리다. «제안이 없다» 로 받으면 제 요청이
        //   들어갔는지 모르고, 기록의 승인자를 자기 id 와 대 봐야 «근거를 기다린다» 로 간다.
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal()
            val approved = assertIs<ApprovalOutcome.Approved>(w.mw.attemptApproval(attempt()))

            val again = assertIs<ApprovalOutcome.Refused>(w.mw.attemptApproval(attempt()))
            assertEquals(ApprovalRefusal.CONSUMED, again.refusal)
            val consumed = assertNotNull(again.consumed)
            assertEquals(AGENT, consumed.approver)
            assertEquals(approved.executionId, consumed.executionId)
            assertEquals(approved.steps, consumed.steps, "소모 기록의 걸음이 승인 답과 다르다")
            assertEquals(w.harness.clock.now(), consumed.at)
        }
    }

    @Test
    fun `사람이 승인한 제안에 다시 시도하면 사람의 승인을 돌려받는다`() {
        // 두 문이 같은 `settle` 을 지난다. 사람의 문으로 소모한 것을 밖의 문이 «제안 없음» 으로 받으면
        // 다른 승인자가 눌렀다는 사실이 사라진다.
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal()
            val accepted = assertIs<Middleware.Submission.Accepted>(
                w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(PLACE), OPERATOR),
            )

            val again = assertIs<ApprovalOutcome.Refused>(w.mw.attemptApproval(attempt()))
            assertEquals(ApprovalRefusal.CONSUMED, again.refusal)
            val consumed = assertNotNull(again.consumed)
            assertEquals(OPERATOR, consumed.approver)
            assertEquals(accepted.execution.executionId, consumed.executionId)
            assertEquals(PLACE, consumed.steps.single().parameters, "사람이 실은 값이 기록에 없다")
        }
    }

    @Test
    fun `소모된 제안을 사람의 문으로 다시 누르면 소모를 사유로 받는다`() {
        // 사람의 문은 산문으로 답한다. 그 산문이 누가 어느 실행으로 소모했는지를 들어야 운영자가 되짚지 않는다.
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal()
            val approved = assertIs<ApprovalOutcome.Approved>(w.mw.attemptApproval(attempt()))

            val again = assertIs<Middleware.Submission.Rejected>(
                w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(PLACE), OPERATOR),
            )
            assertTrue(AGENT.id in again.reason, again.reason)
            assertTrue(approved.executionId in again.reason, again.reason)
            // 종류와 소모 기록도 밖의 문과 같은 값으로 싣는다(ADR 46 의 짝).
            assertEquals(ApprovalRefusal.CONSUMED, again.approvalRefusal)
            val consumed = assertNotNull(again.consumedApproval, "CONSUMED 가 소모 기록 없이 났다")
            assertEquals(AGENT, consumed.approver)
            assertEquals(approved.executionId, consumed.executionId)
        }
    }

    @Test
    fun `소모가 아닌 사람의 문 거절은 종류만 싣고 소모 기록은 없다`() {
        // 선 적이 없는 열쇠다. 소모 기록을 실으면 «누가 이미 눌렀다» 로 읽힌다.
        World().use { w ->
            val answer = assertIs<Middleware.Submission.Rejected>(
                w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(PLACE), OPERATOR),
            )
            assertEquals(ApprovalRefusal.NO_PROPOSAL, answer.approvalRefusal, answer.reason)
            assertNull(answer.consumedApproval)
        }
    }

    @Test
    fun `다른 열쇠의 소모는 이 열쇠를 소모로 만들지 않는다`() {
        // ★열쇠는 (기체, 주문) 둘이다. 한쪽만으로 묶으면 같은 기체의 다른 주문이나 다른 기체의 같은 주문이
        //   남의 소모를 받는다. 다른 기체 쪽은 판정이 능력 조회 앞에서 끝나므로 하네스에 그 기체가 없어도 된다.
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal()
            assertIs<ApprovalOutcome.Approved>(w.mw.attemptApproval(attempt()))

            val saw = listOf(PrepareSequencedRack.SKILL)
            listOf(ApprovalAttempt(AGENT, ROBOT, "PATROL-2", saw), ApprovalAttempt(AGENT, OTHER, "PATROL-1", saw)).forEach { other ->
                val answer = assertIs<ApprovalOutcome.Refused>(w.mw.attemptApproval(other))
                assertEquals(ApprovalRefusal.NO_PROPOSAL, answer.refusal, "${other.robotId} / ${other.jobOrderId}")
                assertNull(answer.consumed)
            }
        }
    }

    @Test
    fun `멱등으로 접힌 승인은 소모가 아니고 제안이 남는다`() {
        // ★조치 열이 안 나간 승인은 소모가 아니다. 소모로 적으면 재시도가 «이미 나갔다» 를 받고 근거를
        //   기다리러 가는데, 기다릴 근거가 없다.
        World(Declared(mapOf(AGENT.id to full())), pair = true).use { w ->
            w.standingProposal()
            assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol(), OTHER))

            repeat(2) { n ->
                assertEquals(ApprovalRefusal.REMEDY_NOT_APPLIED, refusal(w.mw.attemptApproval(attempt())), "${n + 1}번째 시도")
            }
            assertNotNull(w.mw.proposal(ROBOT, "PATROL-1"), "멱등 접힘이 제안을 소모했다")
        }
    }

    @Test
    fun `주문이 이미 실행으로 서 있으면 승인은 조치 열을 싣지 않고 소모로도 남지 않는다`() {
        // ★★승인의 판이 더 높아도 실행이 다른 기체에 서 있으면 개정으로 내리지 않는다(§15.289). 내리면 조치가
        //   제안의 기체가 아닌 곳에서 돌고, 다른 기체에 선 실행이 승인의 판으로 바뀌며, 그것이 소모 기록에
        //   남는다. 앞 판이 «v1 에서 도달 불가» 라 적은 길이다 — 버전이 거꾸로 도착하면 닿는다.
        World(Declared(mapOf(AGENT.id to full())), pair = true).use { w ->
            w.mw.leaveHolding(w.holdingRack(), w.harness) { w.drive(until = it) }
            assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol().copy(version = 2), ROBOT)) // 제안이 선다
            val other = assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol(), OTHER)).execution // 앞 판이 다른 기체에

            repeat(2) { n ->
                assertEquals(ApprovalRefusal.REMEDY_NOT_APPLIED, refusal(w.mw.attemptApproval(attempt())), "${n + 1}번째 시도")
            }
            // 사람의 문도 거절이고 사유가 같다 — 멱등으로 돌려주면 받는 쪽이 «이미 받았다» 로 읽는다.
            val human = assertIs<Middleware.Submission.Rejected>(
                w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(PLACE), OPERATOR),
                "사람의 문이 조치 열 없는 접수를 거절로 내지 않았다",
            )
            val outer = assertIs<ApprovalOutcome.Refused>(w.mw.attemptApproval(attempt()))
            assertEquals(outer.reason, human.reason, "두 문이 접힘을 다른 말로 냈다")
            assertEquals(outer.refusal, human.approvalRefusal, "두 문이 접힘을 다른 종류로 냈다")
            assertNotNull(w.mw.proposal(ROBOT, "PATROL-1"), "나가지 않은 승인이 제안을 소모했다")
            assertEquals(OTHER, other.robotId, "다른 기체에 선 실행이 승인의 기체로 옮겨졌다")
            assertEquals(1, other.version, "다른 기체에 선 실행이 승인의 판으로 바뀌었다")
        }
    }

    // ── 승인자가 기록으로 남는다

    // ── 결과 통보와 잇는 칸(ADR 48)

    @Test
    fun `승인 답의 걸음 단위가 실행 계획의 맨 앞에 같은 순서로 선다`() {
        // ★읽는 쪽은 이 단위로 결과 통보의 `completedUnits` 를 찾는다. 이름만 맞고 자리가 다르면 다른 단위를 보고 판정한다.
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal()
            val approved = assertIs<ApprovalOutcome.Approved>(w.mw.attemptApproval(attempt()))
            val exec = assertNotNull(w.mw.execution(approved.executionId))
            assertTrue(approved.steps.isNotEmpty())
            assertEquals(exec.units.take(approved.steps.size).map { it.unitId }, approved.steps.map { it.unitId })
            approved.steps.forEachIndexed { at, step ->
                assertEquals("remedy-${at + 1}-${step.skillType}", step.unitId)
            }
        }
    }

    @Test
    fun `결과 통보가 승인으로 선 실행과 그 걸음 단위를 가리킨다`() {
        // ★통보가 주문만 가리키면 읽는 쪽은 주문 → 실행 대응표를 따로 들어야 하고, 같은 주문이 다시 서면 그 표가 틀린다.
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal()
            val approved = assertIs<ApprovalOutcome.Approved>(w.mw.attemptApproval(attempt()))
            w.drive(rounds = 400) { w.mw.responses().any { it.executionId == approved.executionId } }

            val response = w.mw.responses().first { it.executionId == approved.executionId }
            assertEquals("PATROL-1", response.jobOrderId)
            val named = response.completedUnits + response.unverifiedUnits + response.inDoubtUnits + response.incompleteUnits.keys
            approved.steps.forEach { step -> assertTrue(step.unitId in named, "통보가 ${step.unitId} 를 모른다: $named") }
        }
    }

    @Test
    fun `인스턴스마다 다른 식별자를 든다`() {
        // ★같은 시드로 다시 띄운 인스턴스가 같은 값을 내면 앞 구동의 통보가 이번 시도의 것으로 읽힌다.
        val first = World().use { it.mw.instanceId }
        val second = World().use { it.mw.instanceId }
        assertTrue(first != second, "두 인스턴스가 같은 식별자를 냈다: $first")
    }

    @Test
    fun `승인으로 시작한 실행의 사건이 누가 눌렀는지 싣는다`() {
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal()
            val approved = assertIs<ApprovalOutcome.Approved>(w.mw.attemptApproval(attempt()))
            val exec = assertNotNull(w.mw.execution(approved.executionId))
            // **기다리는 것은 종착이 아니라 사건이다.** 승인으로 선 실행이 어디서 멈추든 이 시험이 보는
            // 것은 그 사건에 승인자가 실렸는가이고, 종착을 기다리면 그 조건이 시험의 뜻과 어긋난다.
            w.drive(rounds = 400) { w.mw.incidents().any { one -> one.approvedBy != null } }

            val byWhom = w.mw.incidents().mapNotNull { it.approvedBy }
            assertTrue(byWhom.isNotEmpty(), "승인으로 시작한 실행의 사건에 승인자가 안 실렸다: ${w.mw.incidents().size}건")
            assertEquals(ApproverKind.AGENT, byWhom.first().kind)
            assertEquals(AGENT.id, byWhom.first().id)
        }
    }

    @Test
    fun `승인을 안 거친 사건에는 승인자가 없다`() {
        // 승인과 무관한 사건을 사람 쪽 통에 몰아 넣으면 사람 승인의 이의율이 그만큼 희석된다.
        World(Entitlements.None).use { w ->
            val exec = w.holdingRack()
            w.forceFault("PAYLOAD_LOST", exec.units.first().taskId)
            w.drive(rounds = 250) { exec.physicalState == PhysicalState.OPERATOR_HOLD }

            // ★전제가 비면 이 단언은 공짜로 통과한다. 사건이 실제로 있어야 «어느 통에도 안 들어간다» 가 말이 된다.
            assertTrue(w.mw.incidents().isNotEmpty(), "사건이 하나도 안 났다 — 이 시험의 전제가 비었다")
            assertTrue(w.mw.incidents().all { it.approvedBy == null }, "승인 없이 난 사건에 승인자가 붙었다")
            assertEquals(emptyMap(), w.mw.reviewMetricsByApprover())
        }
    }

    @Test
    fun `이의율은 승인자 종류로 갈라 잰다`() {
        // ★재는 대상이 **판단 주체의 성능**이다. 에이전트 승인이 그 시험 대상이므로 한 통에 담으면
        //   사람 승인이 그것을 희석한다.
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal("PATROL-1")
            val approved = assertIs<ApprovalOutcome.Approved>(w.mw.attemptApproval(attempt()))
            val exec = assertNotNull(w.mw.execution(approved.executionId))
            // **기다리는 것은 종착이 아니라 사건이다.** 승인으로 선 실행이 어디서 멈추든 이 시험이 보는
            // 것은 그 사건에 승인자가 실렸는가이고, 종착을 기다리면 그 조건이 시험의 뜻과 어긋난다.
            w.drive(rounds = 400) { w.mw.incidents().any { one -> one.approvedBy != null } }

            val opened = w.mw.incidents().filter { it.approvedBy != null }
            assertTrue(opened.isNotEmpty(), "승인으로 시작한 사건이 없다")
            assertTrue(w.mw.reviewIncident(opened.first().incidentId, ReviewVerdict.DISPUTED, "사람 쪽 판단"))

            val byKind = w.mw.reviewMetricsByApprover()
            val agent = assertNotNull(byKind[ApproverKind.AGENT], "에이전트 통이 없다: $byKind")
            assertEquals(1, agent.disputed)
            assertEquals(1.0, agent.disputeRate)
            assertNull(byKind[ApproverKind.PERSON], "사람 승인이 없는데 통이 생겼다")
        }
    }

    @Test
    fun `반복 카운터는 승인자 종류로 가르지 않는다`() {
        // ★★**가를지는 재는 대상이 자원인지 주체인지로 갈린다**(ADR 43 §4). 이 수가 재는 것은 자원의
        //   상태 — 같은 조치가 몇 번 반복됐나 — 이고 근본 원인은 누가 눌렀는지 모른다. 가르면 사람
        //   한 번과 에이전트 한 번이 둘이 아니라 하나와 하나가 되어 한도에 안 걸린다.
        World(Declared(mapOf(AGENT.id to full()))).use { w ->
            w.standingProposal("PATROL-A")
            val first = assertIs<Middleware.Submission.Accepted>(w.mw.approveRemedy(ROBOT, "PATROL-A", listOf(PLACE), OPERATOR)).execution
            // 앞 승인의 실행이 기체를 놓아야 다음 제안이 선다 — 남이 쥔 기체에는 제안을 세우지 않는다(§15.319).
            w.ended(first)
            w.standingProposal("PATROL-B")
            val second = w.mw.attemptApproval(attempt(jobOrderId = "PATROL-B"))
            assertIs<ApprovalOutcome.Approved>(second, second.toString())

            val repeated = assertNotNull(w.mw.repeatedRemedies().singleOrNull(), "한 통이 아니다: ${w.mw.repeatedRemedies()}")
            assertEquals(2, repeated.approvals, "사람 승인과 에이전트 승인이 다른 통에 쌓였다")
        }
    }

    // ── 순수 판정

    @Test
    fun `다른 쪽이 승인한 같은 모양의 사건은 다른 사건이다`() {
        // 누가 눌렀는지가 이 사건에 대해 할 말을 바꾼다. 해시가 같으면 읽는 쪽이 둘을 한 사건으로 접는다.
        World(Entitlements.None).use { w ->
            w.standingProposal()
            val exec = assertIs<Middleware.Submission.Accepted>(
                w.mw.approveRemedy(ROBOT, "PATROL-1", listOf(PLACE), OPERATOR),
            ).execution
            // **기다리는 것은 종착이 아니라 사건이다.** 승인으로 선 실행이 어디서 멈추든 이 시험이 보는
            // 것은 그 사건에 승인자가 실렸는가이고, 종착을 기다리면 그 조건이 시험의 뜻과 어긋난다.
            w.drive(rounds = 400) { w.mw.incidents().any { one -> one.approvedBy != null } }

            val bundle = assertNotNull(w.mw.incidents().firstOrNull { it.approvedBy != null })
            assertEquals(bundle.digest(), bundle.copy().digest())
            assertTrue(bundle.digest() != bundle.copy(approvedBy = AGENT).digest(), "승인자가 해시에 안 들어갔다")
        }
    }

    @Test
    fun `아무것도 선언 안 한 목록은 누구에게도 자격을 안 준다`() {
        assertNull(Entitlements.None.declaredFor("아무나"))
    }

    companion object {
        const val ROBOT = "hum-02"
        const val OTHER = "hum-09"
        const val DROP_SLOT = "DROP-01"

        /** 사람이 누른다 — 선언 목록을 안 본다. */
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)

        /** 에이전트가 누른다 — 선언 목록에 있어야 한다. */
        val AGENT = Approver("narrator-1", ApproverKind.AGENT)

        private val PLACE = mapOf(
            PrepareSequencedRack.P_OBJECT to "ENGINE-COVER-A",
            PrepareSequencedRack.P_DESTINATION to DROP_SLOT,
        )

        /**
         * **셀 안은 주인이 있고 순회가 나가는 자리는 없는** 배치(§15.154).
         *
         * 전부 무소유로 두면 랙 주문부터 안 서고, 그러면 이 시험은 관문이 아니라 자기 전제를 본다.
         */
        private val UNOWNED_PUMP_ROOM = object : FloorOwnership {
            override fun ownerOf(location: String): FloorOwner =
                if (location.startsWith("RACK-") || location.startsWith("SEQ-IN")) {
                    FloorOwner.Declared("셀")
                } else {
                    FloorOwner.Unowned
                }
        }

        private fun refusal(outcome: ApprovalOutcome): ApprovalRefusal =
            assertIs<ApprovalOutcome.Refused>(outcome).refusal

        /** 지금 기체가 들었다고 **말한** 것의 이름. 선언이 아니라 관측이다. */
        private fun heldObject(w: World): String? = w.mw.executions()
            .flatMap { it.units }
            .firstOrNull { it.hold.kind == HoldKind.HOLD_KIND_HOLDING }
            ?.hold?.objectRef?.takeIf { it.isNotEmpty() }

        /** 사람이 **목적지만** 적은 선언 — 대상은 관측에서 온다. */
        private fun full(
            actions: List<DeclaredAction> = listOf(
                DeclaredAction(PrepareSequencedRack.SKILL, mapOf(PrepareSequencedRack.P_DESTINATION to DROP_SLOT)),
            ),
            robotIds: Set<String> = setOf(ROBOT),
            expiresAt: Instant = Instant.parse("2099-01-01T00:00:00Z"),
            revocation: Revocation? = null,
        ) = Entitlement("narrator-1", actions, robotIds, expiresAt, revocation)

        private fun revoked(reason: String = "자동 승인한 조치가 연속 실패") =
            Revocation(Instant.parse("2026-09-19T04:12:00Z"), "cell-lead", reason)

        /** 대상까지 적어 **좁힌** 선언. */
        private fun narrowed(objectId: String) = DeclaredAction(
            PrepareSequencedRack.SKILL,
            mapOf(
                PrepareSequencedRack.P_DESTINATION to DROP_SLOT,
                PrepareSequencedRack.P_OBJECT to objectId,
            ),
        )

        private fun attempt(
            approver: Approver = AGENT,
            jobOrderId: String = "PATROL-1",
            saw: List<String> = listOf(PrepareSequencedRack.SKILL),
        ) = ApprovalAttempt(approver, ROBOT, jobOrderId, saw)

        /** 점검 대상을 하나 더한 개정판. 더한 대상의 이동이 든 채라 사슬에 걸린다. */
        private fun patrol(jobOrderId: String = "PATROL-1") = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = listOf(
                EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1")),
            ),
        )

        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()
    }
}

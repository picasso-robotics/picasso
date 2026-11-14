package dev.picasso.middleware

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.TaskState
import dev.picasso.harness.Harness
import dev.picasso.host.BundleWriter
import dev.picasso.middleware.mission.Activation
import dev.picasso.middleware.mission.InMemoryMissionCatalog
import dev.picasso.middleware.mission.MissionFixtures
import dev.picasso.mimic.control.v1.ForceFaultRequest
import dev.picasso.mimic.control.v1.SetConnectionRequest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **한 벌을 실물로 만든다.** 읽는 쪽이 인코더 소스를 읽어 맞추면 그것은 관측된 계약이 아니라
 * 추론이고, 봉투 한 겹을 놓쳐도 아무도 모른다. 그래서 디스크에 한 벌을 놓는다.
 *
 * 같은 시나리오를 **두 번** 돌린다. 두 벌의 해시가 같고 구동 식별자만 다른 것이 `runId` 가 존재하는
 * 이유 그 자체이고, 읽는 쪽은 그 두 벌로 «두 번째 구동이 새 사건으로 들어온다» 를 회귀로 박는다.
 *
 * **`run-3` 은 재실행이 아니라 다른 시나리오다.** 같은 기체가 같은 분류로 거듭 깨지는 한 벌이고,
 * 앞의 두 벌과 달리 한 벌 안에서 재발이 실제로 일어난다([recurrence]).
 *
 * **`run-5a`·`run-5b` 는 재기동 장면 한 벌이다**(§15.322). 앞 인스턴스가 보류를 내고 죽은 뒤 판단 기록으로 다시 지은 인스턴스가 그 보류를
 * 다시 연다 — 두 벌이 같은 `incidentKey` 로 이어진다([restartScene]).
 *
 * 산출물은 `picasso/build/export/run-1` ~ `run-4`, `run-5a`·`run-5b` 다.
 */
class ExportFixtureTest {

    private class Declared(private val rows: Map<String, Entitlement>) : Entitlements {
        override fun declaredFor(approverId: String): Entitlement? = rows[approverId]
    }

    private class World : AutoCloseable {
        val harness = Harness(
            mapOf(
                FOUND_ROBOT to PRECOND,
                NONE_ROBOT to NO_REMEDY,
                OTHER_ROBOT to VENDOR_FAULT,
                SILENT_ROBOT to PRECOND,
                UNMAPPED_ROBOT to OPAQUE,
                CUT_ROBOT to PRECOND,
                CODE_ROBOT_A to OPAQUE,
                CODE_ROBOT_B to OPAQUE,
                CODE_ROBOT_C to OPAQUE,
            ),
        )
        val cell = CellMimic(now = { harness.clock.now() })

        /** **경로가 둘이어야 책임 소재를 가르는 것이 보인다**(§15.178). 운반은 플릿의 일이다. */
        val fleet = AmrFleetMimic(now = { harness.clock.now() })
        val mw = Middleware(
            ClientRobotPort(harness.client()),
            cell,
            fleet,
            now = { harness.clock.now() },
            // 셋째 제안이 가려진다 — 한 벌에 FOUND 와 WITHHELD 가 둘 다 있어야 읽는 쪽이 둘을 가른다.
            withholdEvery = 2,
            // 사람이 **목적지만** 적는다 — 무엇을 들었는지는 기체가 말한다(ADR 44).
            entitlements = Declared(
                mapOf(
                    AGENT.id to Entitlement(
                        AGENT.id,
                        listOf(DeclaredAction(PrepareSequencedRack.SKILL, mapOf(PrepareSequencedRack.P_DESTINATION to "RACK-204.S01"))),
                        setOf(FOUND_ROBOT),
                        FAR,
                    ),
                ),
            ),
        )

        private var racks = 0

        /** [searchAfter] 가 사람의 취소로 닫은 실행과 그 시각 — 기체마다. */
        val closings = mutableMapOf<String, Pair<String, Instant>>()

        fun drive(rounds: Int = 120, step: Duration = Duration.ofSeconds(1), until: () -> Boolean) {
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

        /** 실행 중 실패 하나 — 사건이 열리고 기체는 **든 채로** 선다. */
        fun brokenHolding(robotId: String): Middleware.Execution {
            val exec = holdingRack(robotId)
            forceFault(robotId, "SKILL_EXECUTION_FAILED", exec.units.first().taskId)
            drive(rounds = 250) { exec.physicalState == PhysicalState.OPERATOR_HOLD }
            return exec
        }

        /**
         * 사건 **뒤에** 탐색을 세운다 — 사람이 보류 실행을 취소로 닫고, 기체를 쥔 실행 없이 손만 든 채로 남은 기체에 빈손을 요구하는
         * 주문을 넣는다. 기체 스냅숏의 손으로 사슬 검사가 막고 그 거절이 제안을 세운다(§15.316).
         *
         * ★★**왜 닫는가.** 앞 판은 재작업으로 새 태스크를 세워 도는 단위의 관측으로 거절을 받았다. 그 손에 든 것은 재작업하는
         * 실행의 일이라 이제 제안이 서지 않는다(§15.319). 보류 그대로 두어도 그 실행이 기체를 쥐므로 같다. 그래서 이 한 벌의 모양은
         * «사건 → 사람이 닫음 → 탐색» 이다 — 「사건 뒤의 탐색」이되 **사람이 한 걸음 개입한 뒤**의 것인 점은 앞 판과 같다.
         *
         * ★시계를 먼저 민다. 사건과 탐색이 같은 초에 앉으면 읽는 쪽이 앞뒤를 못 가르고, 그러면 이
         * 한 벌이 «사건 뒤의 탐색» 을 싣고도 그렇게 안 읽힌다(§15.182 와 같은 자리).
         */
        fun searchAfter(exec: Middleware.Execution, robotId: String, jobOrderId: String) {
            val mark = harness.clock.now()
            drive(rounds = 10) { harness.clock.now().isAfter(mark.plusSeconds(2)) }

            // 닫는 것은 사람의 운영자 취소 입구라 그 걸음이 사건의 판단 칸에 실린다(§15.320). 닫은 시각은 시험도 쥐고 [closings] 로 댄다.
            closings[robotId] = exec.executionId to harness.clock.now()
            mw.closeByCancel(exec) { drive(rounds = 250, until = it) }
            // ★닫은 뒤에도 시계를 민다. 사람의 걸음(운영자 취소)과 뒤이은 탐색이 같은 초에 앉으면 읽는 쪽이 그 사이의 순서를 못 가른다.
            val closed = harness.clock.now()
            drive(rounds = 10) { harness.clock.now().isAfter(closed.plusSeconds(2)) }
            assertIs<Middleware.Submission.Rejected>(mw.submit(patrol(jobOrderId), robotId))
        }

        /**
         * 미들웨어가 쥔 실행 없이 손만 든 채로 남은 기체([leaveHolding]) — 랙이 든 채 실패하고(사건이 열린다) 사람이 취소로 닫는다.
         * 여기에 낸 주문만 제안을 세운다. 든 채 **도는** 랙 뒤에 내면 그 손에 든 것이 랙의 일이라 제안이 서지 않는다(§15.319).
         *
         * ★닫은 뒤 시계를 민다. 사건과 뒤이은 탐색이 같은 초에 앉으면 읽는 쪽이 앞뒤를 못 가른다([searchAfter] 와 같은 자리).
         */
        fun leftHolding(robotId: String, errorType: String = "SKILL_EXECUTION_FAILED"): Middleware.Execution {
            val exec = mw.leaveHolding(holdingRack(robotId), harness, errorType) { drive(rounds = 250, until = it) }
            val mark = harness.clock.now()
            drive(rounds = 10) { harness.clock.now().isAfter(mark.plusSeconds(2)) }
            return exec
        }

        fun setConnection(robotId: String, state: ConnectionState) = harness.oracle.setConnection(
            SetConnectionRequest.newBuilder().setRobotId(robotId).setState(state.name).build(),
        )

        fun forceFault(robotId: String, errorType: String, taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(robotId).setErrorType(errorType).setTaskId(taskId).build(),
        )

        /**
          * @param slots 슬롯 수. **둘이면 첫 걸음이 끝난 뒤 둘째에서 깨뜨릴 수 있다** — 한 벌의 사건이
          *   전부 «1 / N» 이면 「몇 걸음 중 어디서」가 값으로는 있어도 가려 주는 것이 없다(§15.177).
          */
        fun holdingRack(robotId: String, slots: Int = 1): Middleware.Execution {
            val first = racks + 1
            val ids = (0 until slots).map { "RACK-204.S0${racks + 1 + it}" }
            racks += slots
            val order = JobOrder(
                jobOrderId = "SEQ-$first",
                workMasterId = PrepareSequencedRack.WORK_MASTER,
                version = 17,
                requiredEvidence = Evidence.E2,
                materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", slots)),
                equipmentRequirements = ids.map {
                    EquipmentRequirement(it, "destination", mapOf("material" to "ENGINE-COVER-A"))
                } + listOf(EquipmentRequirement("SEQ-IN-02.BIN-A", "source", mapOf("material" to "ENGINE-COVER-A"))),
            )
            order.equipmentRequirements.filter { it.equipmentUse == "destination" }
                .forEach { cell.program(it.id, it.properties["material"]) }
            val exec = assertIs<Middleware.Submission.Accepted>(mw.submit(order, robotId)).execution
            drive { exec.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING }
            return exec
        }

        override fun close() = harness.close()
    }

    /**
     * 한 벌에 들어갈 것을 실제로 만든다 — 탐색 세 갈래와 승인자 두 갈래.
     *
     * **`DEPTH_LIMIT` 은 여기서 안 나온다.** 파지 값이 넷인데 효과가 내는 것은 빈손과 든 채 둘뿐이라
     * 너비 우선이 상한(세 걸음)에 닿기 전에 볼 것이 없어진다. 이 층을 통해서는 도달할 수 없는 값이다
     * (§15.170).
     */
    private fun scenario(w: World) {
        // **기체를 셋으로 가른다.** 한 기체는 태스크를 하나씩만 든다(§15.98) — 한 기체에 몰면 앞
        // 실행이 끝나기를 기다리는 동안 시나리오가 서로를 막는다.

        // ① 대안 있음 — 랙이 든 채 실패하고(사건) 사람이 취소로 닫은 기체에 점검 순회를 밀어 넣는다(§15.319).
        w.leftHolding(FOUND_ROBOT)
        assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-1"), FOUND_ROBOT))

        // ② **든 채 그대로** 에이전트가 밖의 문으로 승인한다 — 값을 하나도 안 싣는다(ADR 44).
        //    대상의 이름은 «지금 든 것» 의 관측(기체 스냅숏의 손)에서 온다.
        val approved = assertIs<ApprovalOutcome.Approved>(
            w.mw.attemptApproval(
                ApprovalAttempt(AGENT, FOUND_ROBOT, "PATROL-1", listOf(PrepareSequencedRack.SKILL)),
            ),
        )
        val remedied = assertNotNull(w.mw.execution(approved.executionId))
        w.drive(rounds = 250) { remedied.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING }
        w.forceFault(FOUND_ROBOT, "PAYLOAD_LOST", remedied.units.first().taskId)
        w.drive(rounds = 250) { remedied.physicalState == PhysicalState.OPERATOR_HOLD }

        // ③ 대안 없음 — 든 채로는 딛을 스킬이 없는 기체다.
        val stuck = w.holdingRack(NONE_ROBOT)
        assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-2"), NONE_ROBOT))
        w.drive(rounds = 250) { stuck.physicalState.isSettled }

        // ④ 가려짐 — 둘째 제안이 가릴 차례다. 그리고 이 실행의 사건은 승인자가 없다.
        //
        //    **벤더 이름공간의 결함을 쓴다**(§15.177). 정준 분류 이름을 오류 유형 자리에 그대로 넣으면
        //    벤더 원문 칸이 통째로 비고, 읽는 쪽은 «이 벤더 코드가 이 분류로 왔다» 를 짚을 수 없다 —
        //    칸을 만들어도 시나리오가 안 채우면 빈 칸이다. 그 결함으로 든 채 실패한 랙을 사람이 닫은 뒤에 순회를 낸다(§15.319) —
        //    그래서 이 기체에서는 사건이 탐색보다 먼저 선다.
        w.leftHolding(OTHER_ROBOT, "X_FIXTURE_GRIPPER_SLIP")
        assertIs<Middleware.Submission.Rejected>(w.mw.submit(patrol("PATROL-3"), OTHER_ROBOT))

        // ⑤ 안 드는 단위가 실패한다 — 파지를 바꾸지 않는 스킬의 사건도 한 벌에 있어야 한다.
        //
        //    **잔여 파지가 UNSPECIFIED 인 줄은 여기서 안 나온다.** 하달된 단위는 기체가 파지를 답하고,
        //    하달 전에 실패한 것(출발 결품)은 사건을 열지 않는다. 기본값 출력이 서는지는 빈 문자열
        //    필드로 확인한다 — 표준 printer 였으면 그 키들이 통째로 빠진다.
        val patrolled = assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-4"), SILENT_ROBOT)).execution
        w.drive {
            w.mw.view(SILENT_ROBOT)?.tasks?.get(patrolled.units.first().taskId) == TaskState.TASK_STATE_RUNNING
        }
        w.forceFault(SILENT_ROBOT, "LOCALIZATION_LOST", patrolled.units.first().taskId)
        w.drive(rounds = 250) { patrolled.physicalState == PhysicalState.OPERATOR_HOLD }

        // ⑥ **원인이 하나로 안 좁혀지는 사건.** 코드 하나에 서로 다른 두 원인이 묶여 있고, 코드가
        //    그 둘을 안 가른다(`fixture-stop-codes.md` §4). 전부 좁혀지면 «안 좁혀진다» 가 한 번도
        //    정답이 되지 않아 그 답을 낼 줄 아는지 잴 수가 없다.
        //    **둘째 걸음에서 깨뜨린다** — 첫 걸음이 끝난 뒤라 「몇 걸음 중 어디서」가 1 이 아니고
        //    끝난 단위 목록도 비지 않는다.
        val unmapped = w.holdingRack(UNMAPPED_ROBOT, slots = 2)  // @formatter:off
        w.drive(rounds = 400) {
            unmapped.completedUnits.isNotEmpty() && unmapped.units[1].hold.kind == HoldKind.HOLD_KIND_HOLDING
        }
        w.forceFault(UNMAPPED_ROBOT, "X_FIXTURE_E9001", unmapped.units[1].taskId)
        w.drive(rounds = 250) { unmapped.physicalState.isSettled || unmapped.physicalState == PhysicalState.OPERATOR_HOLD }

        // ⑦ **플릿의 일** — 출발지에 요청한 용기가 없어 인수되지 않는다. 경로가 `FLEET` 인 사건이
        //    한 벌에 하나는 있어야 «이 실패를 누구에게 물을 것인가» 가 값으로 갈린다.
        val delivery = assertIs<Middleware.Submission.Accepted>(w.mw.submit(deliver(), NONE_ROBOT)).execution
        w.drive(rounds = 250) { delivery.physicalState.isSettled || delivery.units.first().state == UnitState.FAILED }

        // ⑧ **선이 끊긴 채 난 사건** — 그동안의 결과는 미확정이고, 같은 등급이라도 같은 값이 아니다.
        //    돌려놓지 않는다: 복구하면 봉인 시점에는 이미 거짓이라 한 벌이 그 상태를 못 보여 준다.
        val cut = w.holdingRack(CUT_ROBOT)
        w.setConnection(CUT_ROBOT, ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN)
        w.mw.pump()
        w.forceFault(CUT_ROBOT, "PAYLOAD_LOST", cut.units.first().taskId)
        w.drive(rounds = 250) { w.mw.incidents().any { it.executionId == cut.executionId } }

        // ⑨ **원인이 번들에 없는 사건 셋.** 정지 코드만 실리고 그 뜻은 벤더 문서에만 있다.
        //
        //    ★**코드 이름이 원인을 말하면 안 된다.** 말하면 답이 입력을 복창하기만 해도 맞고,
        //      읽는 쪽의 «1순위 원인» 지표가 그 자리에서 무의미해진다(§15.179). 주입한 쪽은
        //      무엇을 넣었는지 알고, 그 정답은 번들이 아니라 `handoff/narrator/ground-truth.jsonl`
        //      로 따로 나간다.
        listOf(
            CODE_ROBOT_A to "X_FIXTURE_E4412",
            CODE_ROBOT_B to "X_FIXTURE_E2075",
            CODE_ROBOT_C to "X_FIXTURE_E6130",
        ).forEach { (robotId, code) ->
            val held = w.holdingRack(robotId)
            w.forceFault(robotId, code, held.units.first().taskId)
            w.drive(rounds = 250) { w.mw.incidents().any { it.executionId == held.executionId } }
        }

        // ⑩ **점유 축이 계산한 회복** — 출발 자리가 비었고 그 자재를 든 다른 자리가 있다.
        //
        //    이 거절은 실행을 만들지 않으므로 사건이 아니라 탐색 대장에 남는다(§15.164 와 같은 자리).
        //    ★**맨 끝에 둔다.** 거절은 시계를 안 돌리고 실행도 제안도 안 만들므로, 앞선 사건 열의
        //      시각과 해시가 그대로다 — 받는 쪽의 회귀가 이 변경으로 흔들리지 않는다.
        //
        //    ★**자재를 따로 쓴다.** 랙 시나리오의 자재를 그대로 쓰면 앞서 프로그램한 목적지들이 전부
        //      «그 자재를 든 자리» 로 나와 제시 목록이 무엇을 뜻하는지 안 보인다.
        w.cell.empty(RELOCATE_SOURCE)
        w.cell.program(RELOCATE_ALTERNATIVE, RELOCATE_MATERIAL)
        assertIs<Middleware.Submission.Rejected>(w.mw.submit(relocate(), NONE_ROBOT))
    }

    /**
     * **재발이 있는 한 벌**(`run-3`). 같은 기체가 같은 분류로 셋, 그중 둘은 같은 자리에서.
     *
     * 재발은 **읽는 쪽이 센다** — 이 층은 사실만 낸다(ADR 40). 그런데 두 벌(`run-1`·`run-2`)은 같은
     * 시드의 재실행이라 한 벌 안에 같은 기체·같은 분류가 없고, 그래서 그 셈법이 **시험으로는 서고
     * 실물로는 한 번도 안 밟혔다.** 읽는 쪽이 「센다」고 적으려면 밟히는 한 벌이 있어야 한다.
     *
     * **재발이 없는 사건도 하나 넣는다.** 전부 재발이면 그 셈이 늘 0 보다 커서, 값이 있어도 아무것도
     * 가려 주지 않는다 — 경로가 한 갈래뿐이던 자리와 같은 병이다(§15.177·§15.178).
     *
     * **앞의 둘은 `SKILL_EXECUTION_FAILED` 로 깨뜨린다.** 벤더 원문 쪽(`X_FIXTURE_GRIPPER_SLIP`)은
     * `can_accept_new_task: false` · `UNTIL_CLEARED` 라 재작업이 안 나간다. 분류는 둘 다 `GRASP_FAILED`
     * 이므로 재발의 축은 그대로이고, 벤더 원문은 재작업이 필요 없는 셋째가 든다.
     */
    private fun recurrence(w: World) {
        // ① 같은 자리의 첫 번째.
        val exec = w.holdingRack(RECUR_ROBOT)
        val slot = exec.units.first().unitId
        w.forceFault(RECUR_ROBOT, "SKILL_EXECUTION_FAILED", exec.units.first().taskId)
        w.drive(rounds = 250) { exec.physicalState == PhysicalState.OPERATOR_HOLD }

        // ② 사람이 재작업을 내고 **같은 자리가 다시 깨진다.** 재작업은 새 태스크이므로 같은 단위가
        //    두 번 사건을 연다 — 자리 축의 재발이 여기서 생긴다.
        //
        //    ★**파지로는 이 자리를 못 기다린다.** 앞 사건의 잔여 파지가 이미 `HOLDING` 이라 그 조건은
        //      새 태스크가 뜨기 전에 참이고, 그때 결함을 밀면 미믹이 `ACCEPTED` 라며 되돌린다.
        //      기다릴 것은 **새 태스크가 도는 것**이다(실측).
        val firstTask = exec.units.first().taskId
        assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, slot, OperatorDecision.REWORK, OPERATOR), "재작업이 안 받아졌다")
        w.drive(rounds = 250) {
            val task = exec.units.first().taskId
            task.isNotBlank() && task != firstTask &&
                w.mw.view(RECUR_ROBOT)?.tasks?.get(task) == TaskState.TASK_STATE_RUNNING
        }
        w.forceFault(RECUR_ROBOT, "SKILL_EXECUTION_FAILED", exec.units.first().taskId)
        w.drive(rounds = 250) { exec.physicalState == PhysicalState.OPERATOR_HOLD }

        // **자리를 놓아야 기체가 다음 주문을 받는다.** `OPERATOR_HOLD` 는 종착이 아니라 점유도 파지도
        // 그대로이고, 기체는 태스크를 하나씩만 든다(§15.98).
        assertEquals(ResolveOutcome.Resolved, w.mw.resolve(exec.executionId, slot, OperatorDecision.CONFIRM_DONE, OPERATOR), "확인이 안 받아졌다")
        w.drive(rounds = 250) { exec.physicalState.isSettled }

        // ③ 같은 기체·같은 분류, **다른 자리.** 기체 축은 셋이고 자리 축은 둘이라 두 축이 갈린다.
        val next = w.holdingRack(RECUR_ROBOT)
        w.forceFault(RECUR_ROBOT, "X_FIXTURE_GRIPPER_SLIP", next.units.first().taskId)
        w.drive(rounds = 250) { next.physicalState == PhysicalState.OPERATOR_HOLD }

        // ④ **재발이 없는 사건.** 다른 기체이고 다른 분류다.
        val patrolled = assertIs<Middleware.Submission.Accepted>(w.mw.submit(patrol("PATROL-9"), SILENT_ROBOT)).execution
        w.drive {
            w.mw.view(SILENT_ROBOT)?.tasks?.get(patrolled.units.first().taskId) == TaskState.TASK_STATE_RUNNING
        }
        w.forceFault(SILENT_ROBOT, "LOCALIZATION_LOST", patrolled.units.first().taskId)
        w.drive(rounds = 250) { patrolled.physicalState == PhysicalState.OPERATOR_HOLD }
    }

    /**
     * **사건이 먼저 나고 탐색이 그 뒤에 선다.** 지금 한 벌들은 순서가 반대다 — 접수 관문의 거절이
     * 제안을 세우고(§15.150), 승인된 조치가 **나중에** 깨져 사건이 난다. 그래서 읽는 쪽은 «이 실패를
     * 어떻게 회복하나» 를 사건 옆에서 못 읽는다. 사건 아홉에 짝이 하나였다(읽는 쪽 실측).
     *
     * ★**뒤집는 데 §15.150 을 안 연다.** 탐색은 여전히 접수 관문에서만 돈다 — 바뀌는 것은 **순서**
     * 뿐이다. 실행 중 실패가 잔여 파지를 남기고, 그 기체에 **빈손을 요구하는 주문** 이 들어가면
     * 사슬 검사가 막고 그 거절이 제안을 세운다. 발신자 거절 경로에 제안을 붙이는 것이 아니다.
     */
    private fun afterIncident(w: World) {
        // ① 짝이 있고 답이 서는 자리.
        val broken = w.brokenHolding(FOUND_ROBOT)
        w.searchAfter(broken, FOUND_ROBOT, "PATROL-AFTER-1")

        // ② 짝이 있는데 답이 가려진 자리 — `withholdEvery` 가 둘째 제안을 가린다. 읽는 쪽이
        //    «가렸다» 와 «대안이 없다» 를 사건 옆에서도 갈라야 한다.
        val withheld = w.brokenHolding(OTHER_ROBOT)
        w.searchAfter(withheld, OTHER_ROBOT, "PATROL-AFTER-2")

        // ③ ★**짝이 없는 사건이 있어야 그 셈이 무언가를 가린다.** 전부 짝이면 읽는 쪽은 자기 셈이
        //    도는지조차 모른다(§15.177). 이 기체에는 뒤이은 주문을 안 넣는다.
        w.brokenHolding(NONE_ROBOT)
    }

    /**
     * 담는 절차는 창구가 쓰는 것과 **같은 한 벌**이다(§15.193). 앞 판은 여기에 같은 절차를 따로
     * 적었고, 그래서 창구 쪽만 고침을 얻었을 때 갈림이 안 보였다.
     *
     * ★**한 벌마다 쓰개를 새로 만든다.** 자리와 구동 식별자가 생성 인자라 재실행 쌍이 같은 식별자를
     * 얻을 길이 없고, 건너뛰기도 새 쓰개에는 걸리지 않는다. 그래도 «안 썼다» 를 조용히 넘기지 않는다.
     */
    private fun write(w: World, dir: Path) {
        check(BundleWriter(dir, w.mw.instanceId).snapshot(w.mw.incidents(), w.mw.remedySearches(), w.harness.clock.now())) {
            "$dir 에 한 벌을 안 썼다"
        }
    }

    @Test
    fun `같은 시나리오를 두 번 돌려 한 벌을 둘 낸다`() {
        val dirs = listOf(Path.of("build", "export", "run-1"), Path.of("build", "export", "run-2"))
        val digests = mutableListOf<List<String>>()
        val runIds = mutableListOf<String>()
        val outcomes = mutableListOf<List<String>>()
        val approvers = mutableListOf<List<String?>>()
        val vendorDetails = mutableListOf<List<String>>()
        val classes = mutableListOf<List<String?>>()
        val steps = mutableListOf<List<Int>>()
        val routes = mutableListOf<List<String>>()
        val trust = mutableListOf<List<Boolean>>()
        val lines = mutableListOf<String>()

        dirs.forEach { dir ->
            World().use { w ->
                scenario(w)
                write(w, dir)
                digests += w.mw.incidents().map { it.digest() }
                outcomes += w.mw.remedySearches().map { record ->
                    when (val outcome = record.outcome) {
                        is RemedyOutcome.Found -> "FOUND"
                        is RemedyOutcome.None -> "NONE:${outcome.cause.name}"
                        RemedyOutcome.Withheld -> "WITHHELD"
                        is RemedyOutcome.SourceMissing -> "SOURCE_MISSING"
                        is SourceUnobserved -> "SOURCE_UNOBSERVED"
                        is RemedyOutcome.DestinationOccupied -> "DESTINATION_OCCUPIED"
                        is RemedyOutcome.DestinationUnobserved -> "DESTINATION_UNOBSERVED"
                    }
                }
                approvers += w.mw.incidents().map { it.approvedBy?.kind?.name }
                vendorDetails += w.mw.incidents()
                    .flatMap { b -> (listOfNotNull(b.fault) + b.blockedBy).map { it.vendorDetail } }
                    .filter { it.isNotBlank() }
                classes += w.mw.incidents().map { it.failureClass }
                steps += w.mw.incidents().map { it.step.at }
                routes += w.mw.incidents().map { it.route }
                trust += w.mw.incidents().map { it.observation.linkBroken }
                lines += Files.readString(dir.resolve(LedgerExport.INCIDENTS))
                runIds += Regex(""""runId":"([^"]+)"""")
                    .find(Files.readString(dir.resolve(LedgerExport.MANIFEST)))!!.groupValues[1]
            }
        }

        // 한 벌이 들어야 할 것 — 읽는 쪽의 방벽이 이것들에 선다.
        assertTrue("FOUND" in outcomes[0], "탐색 결과에 FOUND 가 없다: ${outcomes[0]}")
        assertTrue("NONE:NO_CAPABILITY" in outcomes[0], "탐색 결과에 NO_CAPABILITY 가 없다: ${outcomes[0]}")
        assertTrue("WITHHELD" in outcomes[0], "탐색 결과에 WITHHELD 가 없다: ${outcomes[0]}")
        // ★**칸을 만들어도 시나리오가 안 채우면 빈 칸이다.** 점유 축이 계산한 회복이 한 벌에 없으면
        //   읽는 쪽은 그 갈래를 코드로만 알고 실물로는 한 번도 못 본다(§15.183).
        assertTrue("SOURCE_MISSING" in outcomes[0], "탐색 결과에 SOURCE_MISSING 이 없다: ${outcomes[0]}")
        assertTrue(null in approvers[0], "승인을 안 거친 사건이 없다: ${approvers[0]}")
        assertTrue("AGENT" in approvers[0], "에이전트가 승인한 사건이 없다: ${approvers[0]}")

        // ★**칸을 만들어도 시나리오가 안 채우면 빈 칸이다**(§15.177). 벤더 원문이 실린 사건과
        //   안 좁혀진 사건이 한 벌에 각각 있어야 읽는 쪽이 그 갈래를 실물로 본다.
        assertTrue(vendorDetails[0].isNotEmpty(), "벤더 원문이 실린 결함이 한 벌에 없다")
        assertTrue("UNCLASSIFIED" in classes[0], "안 좁혀진 사건이 한 벌에 없다: ${classes[0]}")
        assertTrue(steps[0].any { it > 1 }, "전부 첫 걸음에서 깨졌다 — 「몇 걸음 중 어디서」를 가려 주는 사건이 없다: ${steps[0]}")
        assertEquals(setOf("ROBOT", "FLEET"), routes[0].toSet(), "경로가 한 갈래뿐이다 — 책임 소재를 가르는 것이 안 보인다: ${routes[0]}")
        assertTrue(trust[0].any { it }, "선이 끊긴 채 난 사건이 없다 — 관측 신뢰가 늘 온전한 것으로 보인다")
        // ★**기본값 필드가 실물 줄에 남아 있다.** protobuf JSON 의 표준 설정이었으면 빈 문자열 키가
        //   통째로 빠지고, 그러면 계약 메시지 안에서 «없다» 와 «이 판이 안 낸다» 가 접힌다(§15.145).
        assertTrue(
            """"objectRef":""""" in lines[0] && """"reason":""""" in lines[0],
            "기본값 필드가 빠졌다 — protobuf JSON 의 생략 설정이 되살아났다",
        )

        // ★**두 벌의 해시가 같고 구동 식별자만 다르다.** 이것이 `runId` 가 존재하는 이유다 —
        //   읽는 쪽이 해시로만 멱등을 걸면 둘째 구동이 통째로 접힌다.
        assertEquals(digests[0], digests[1], "같은 시나리오인데 해시가 갈렸다")
        assertEquals(outcomes[0], outcomes[1], "같은 시나리오인데 탐색 결과가 갈렸다")
        assertNotEquals(runIds[0], runIds[1], "두 구동의 식별자가 같다")
    }

    @Test
    fun `재발이 있는 한 벌을 낸다`() {
        val dir = Path.of("build", "export", "run-3")
        World().use { w ->
            recurrence(w)
            write(w, dir)

            val incidents = w.mw.incidents()
            val repeated = incidents.filter { it.robotId == RECUR_ROBOT && it.failureClass == "GRASP_FAILED" }
            assertEquals(3, repeated.size, "같은 기체·같은 분류가 셋이 아니다: ${incidents.map { it.robotId to it.failureClass }}")

            // ★**같은 초에 둘이 들어오면 읽는 쪽이 그 둘 사이의 재발을 안 센다.** 가상 시계가 초
            //   단위라 앞뒤를 못 가르고, 그쪽은 덜 세는 쪽으로 틀린다 — 그러면 이 한 벌이 재발을
            //   싣고도 재발로 안 읽힌다.
            val seconds = repeated.map { it.at.epochSecond }
            assertEquals(seconds.size, seconds.toSet().size, "같은 초에 겹친 사건이 있다: $seconds")

            // 축이 둘이다 — 기체 축은 셋, 자리 축은 둘. 자리가 전부 다르면 «같은 자리에서 또» 가 안 나온다.
            val places = repeated.groupingBy { it.unitId }.eachCount()
            assertEquals(
                1,
                places.count { it.value == 2 },
                "같은 자리에서 두 번 깨진 자리가 하나가 아니다: $places",
            )

            // ★**재발이 없는 사건이 있어야 그 셈이 무언가를 가린다.** 전부 재발이면 그 칸은 늘 켜져
            //   있어 아무것도 안 가르고, 읽는 쪽은 자기 셈이 도는지조차 모른다(§15.177).
            assertTrue(
                incidents.any { it.robotId != RECUR_ROBOT && it.failureClass != "GRASP_FAILED" },
                "재발이 없는 사건이 없다: ${incidents.map { it.robotId to it.failureClass }}",
            )
        }
    }

    @Test
    fun `사건 뒤에 탐색이 서는 한 벌을 낸다`() {
        val dir = Path.of("build", "export", "run-4")
        World().use { w ->
            afterIncident(w)
            write(w, dir)

            val incidents = w.mw.incidents()
            val searches = w.mw.remedySearches()
            assertTrue(incidents.size >= 3, "사건이 셋보다 적다: ${incidents.size}")
            assertEquals(2, searches.size, "탐색이 둘이 아니다: ${searches.map { it.robotId to it.outcome }}")

            // ★**이 한 벌의 유일한 이유다.** 같은 기체에서 사건이 먼저 나고 탐색이 그 뒤에 선다 —
            //   지금까지의 한 벌들은 전부 반대 순서였고, 그래서 읽는 쪽이 회복을 사건 옆에서 못 읽었다.
            searches.forEach { search ->
                val before = incidents.filter { it.robotId == search.robotId && it.at.isBefore(search.at) }
                assertTrue(
                    before.isNotEmpty(),
                    "탐색 ${search.searchId}(${search.robotId} · ${search.at}) 앞에 그 기체의 사건이 없다: " +
                        incidents.filter { it.robotId == search.robotId }.map { it.at },
                )
            }

            // ★**사람의 걸음은 판단 칸과 실행 상태 둘로 댄다.** 운영자 취소가 사건의 판단 칸에 실리고(§15.320) 번들에서도
            //   읽힌다. 여기서는 그 걸음이 실제 닫힘과 같은 것인지 댄다 — 짝지어진 사건의 실행이 사람의 취소로 닫혔고(`ABORTED`),
            //   그 닫힘이 사건 뒤·탐색 앞이며, 그 사건의 판단 칸이 닫은 사람의 운영자 취소다.
            searches.forEach { search ->
                val incident = incidents.filter { it.robotId == search.robotId && it.at.isBefore(search.at) }.maxBy { it.at }
                val step = assertNotNull(incident.resolution, "${search.robotId} 의 사건에 사람의 걸음이 없다")
                assertEquals(IncidentDecision.OPERATOR_CANCEL to CLOSING_OPERATOR, step.decision to step.decidedBy)
                val (closed, at) = assertNotNull(w.closings[search.robotId], "${search.robotId} 를 닫은 걸음이 없다")
                assertEquals(incident.executionId, closed, "${search.robotId} 에서 닫은 실행이 그 사건의 실행이 아니다")
                assertEquals(PhysicalState.ABORTED, assertNotNull(w.mw.execution(closed)).physicalState, "${search.robotId} 의 사건 실행이 취소로 닫히지 않았다")
                assertTrue(at.isAfter(incident.at), "${search.robotId} 의 닫힘이 사건보다 앞이다: $at <= ${incident.at}")
                assertTrue(!at.isAfter(search.at), "${search.robotId} 의 닫힘이 탐색보다 뒤다: $at > ${search.at}")
            }

            // ★**짝이 없는 사건이 있어야 그 셈이 무언가를 가린다**(§15.177). 전부 짝이면 읽는 쪽은
            //   자기 셈이 도는지조차 모른다.
            val paired = searches.map { it.robotId }.toSet()
            assertTrue(
                incidents.any { it.robotId !in paired },
                "짝 없는 사건이 없다: ${incidents.map { it.robotId }}",
            )

            // 「가렸다」와 「대안이 없다」가 사건 옆에서도 갈려야 한다 — 하나는 답이 서고 하나는 가려진다.
            assertTrue(
                searches.any { it.outcome is RemedyOutcome.Found } && searches.any { it.outcome is RemedyOutcome.Withheld },
                "선 답과 가린 답이 같이 있지 않다: ${searches.map { it.outcome }}",
            )
        }
    }

    /**
     * 재기동 장면의 세계 — 임무 정의와 현장 설정으로 돈다. 그래서 이 벌의 줄은 임무 버전과 설정 셋을 값으로 싣는다(앞 벌들은 코드 케이퍼빌리티라
     * 넷 다 널이다). 정의는 출발·목적지 관측을 둘 다 요구한다 — 그래야 점유 관문의 새 갈래 셋이 실물 장면에서 선다.
     */
    private class RestartWorld : AutoCloseable {
        val harness = Harness(mapOf(FOUND_ROBOT to PRECOND, GATE_ROBOT to PRECOND))
        val cell = CellMimic(now = { harness.clock.now() })
        val catalog = InMemoryMissionCatalog(now = { harness.clock.now() })
        private val timings = SiteTimingsSource { SiteTimings.ofSeconds(3, 30, 15, 20, 40) }

        init {
            val activated = catalog.activate(
                MissionFixtures.prepareSequencedRack(sourceObservation = "REQUIRED", destinationObservation = "REQUIRED"),
                MissionFixtures.SIGNALS,
                FloorOwnership.None,
                MissionFixtures.SITE_SKILLS,
            )
            assertIs<Activation.Activated>(activated, "시험 정의가 서지 않았다: $activated")
        }

        /** 같은 기체·셀·카탈로그 위에 새 인스턴스를 띄운다 — 재기동이다. */
        fun instance() = Middleware(ClientRobotPort(harness.client()), cell, now = { harness.clock.now() }, missions = catalog, siteTimings = timings)

        fun drive(mw: Middleware, rounds: Int = 250, until: () -> Boolean) {
            repeat(rounds) {
                mw.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(1))
                Thread.sleep(40)
                mw.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다")
        }

        fun forceFault(robotId: String, errorType: String, taskId: String) = harness.oracle.forceFault(
            ForceFaultRequest.newBuilder().setRobotId(robotId).setErrorType(errorType).setTaskId(taskId).build(),
        )

        override fun close() = harness.close()
    }

    /**
     * **재기동 장면**(`run-5a`·`run-5b`, §15.322). 앞 인스턴스에서 랙이 든 채 실패해 운영자 보류에 서고(인시던트), 점유 관문이 셋을 거절한다 —
     * 출발 미관측·목적지 점유·목적지 미관측. 그 인스턴스의 한 벌을 낸 뒤 판단 기록(인코딩을 한 번 지난다)으로 새 인스턴스가 다시 짓고, 그
     * 보류를 다시 연 인시던트로 그 인스턴스의 한 벌을 낸다.
     *
     * ★**두 벌이 같은 `incidentKey` 로 이어지는 것이 이 장면의 이유다.** 읽는 쪽은 인스턴스마다 번들을 모아 세므로, 잇는 열쇠가 없으면 같은 보류가
     * 재기동 횟수만큼 더 셈된다. 요약은 다르다 — 사본 둘은 다른 줄이다.
     */
    private fun restartScene(w: RestartWorld): Triple<Middleware, Middleware, JudgmentRecord> {
        val first = w.instance()
        w.cell.program(SCENE_SOURCE, SCENE_MATERIAL)
        w.cell.empty(SCENE_SLOT)
        val order = sceneRack("SEQ-RESTART", SCENE_SLOT, SCENE_SOURCE)
        val exec = assertIs<Middleware.Submission.Accepted>(first.submit(order, FOUND_ROBOT)).execution
        // 접수 뒤 설비가 목적지에 그 자재를 본다 — 하류는 실패하고 설비에는 있으니 사람이 판단할 보류다(목적지 관측은 접수 때 «비었다» 였다).
        w.cell.program(SCENE_SLOT, SCENE_MATERIAL)
        w.drive(first) { exec.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING }
        w.forceFault(FOUND_ROBOT, "SKILL_EXECUTION_FAILED", exec.units.first().taskId)
        w.drive(first) { exec.physicalState == PhysicalState.OPERATOR_HOLD }

        // 점유 관문의 답 셋 — 거절은 실행을 만들지 않고 시계도 안 돌린다. 빈손인 기체라 사슬 검사는 지난다.
        w.cell.empty("RACK-207.S01")
        assertIs<Middleware.Submission.Rejected>(first.submit(sceneRack("SEQ-SILENT-SOURCE", "RACK-207.S01", "SEQ-IN-09.BIN-A"), GATE_ROBOT))
        w.cell.program("RACK-208.S01", "ENGINE-COVER-B")
        assertIs<Middleware.Submission.Rejected>(first.submit(sceneRack("SEQ-FULL-SLOT", "RACK-208.S01", SCENE_SOURCE), GATE_ROBOT))
        assertIs<Middleware.Submission.Rejected>(first.submit(sceneRack("SEQ-SILENT-SLOT", "RACK-209.S01", SCENE_SOURCE), GATE_ROBOT))

        val record = JudgmentRecordWire.decode(JudgmentRecordWire.encode(assertNotNull(first.judgmentRecord(exec.executionId))))
        // ★재기동 사이에 시계가 흐른다 — 다시 연 인시던트의 `at` 은 다시 연 시각이고 `firstOpenedAt` 은 처음 선 시각이라 둘이 갈린다.
        w.harness.advance(Duration.ofSeconds(5))
        val second = w.instance()
        val mission = assertNotNull(w.catalog.active(PrepareSequencedRack.WORK_MASTER))
        assertIs<Middleware.Submission.Accepted>(second.resume(order, FOUND_ROBOT, mission, record))
        return Triple(first, second, record)
    }

    @Test
    fun `재기동 장면의 두 벌을 낸다`() {
        RestartWorld().use { w ->
            val (first, second, record) = restartScene(w)
            check(BundleWriter(Path.of("build", "export", "run-5a"), first.instanceId).snapshot(first.incidents(), first.remedySearches(), w.harness.clock.now()))
            check(BundleWriter(Path.of("build", "export", "run-5b"), second.instanceId).snapshot(second.incidents(), second.remedySearches(), w.harness.clock.now()))

            val held = first.incidents().single()
            val origin = assertNotNull(held.origin)
            assertEquals(OpeningKind.NEW, held.openingKind)
            assertEquals(origin, record.units.single().verdict?.incident, "판단 기록이 보류의 정체성을 안 실었다")
            val reopened = second.incidents().single()
            assertEquals(OpeningKind.REOPENED to origin, reopened.openingKind to reopened.origin, "다시 연 인시던트가 원래 보류와 안 이어졌다")
            assertNotEquals(held.digest(), reopened.digest(), "사본 둘의 요약이 같다")
            assertTrue(reopened.at.isAfter(origin.firstOpenedAt), "다시 연 시각과 처음 선 시각이 갈리지 않았다: ${reopened.at} / ${origin.firstOpenedAt}")

            // 이 벌은 임무 정의와 현장 설정으로 돈다 — 두 칸이 값을 싣는다.
            listOf(held, reopened).forEach {
                assertEquals(1, it.intent.missionVersion)
                assertEquals(3L, it.intent.siteSettingsVersion)
                assertEquals("PT20S" to "PT40S", it.intent.inDoubtGrace to it.intent.stallWindow)
            }

            // ★**칸을 만들어도 장면이 안 채우면 빈 칸이다.** 새 갈래 셋이 실물로 적힌다.
            val outcomes = first.remedySearches().map { it.outcome }
            assertEquals(
                listOf(
                    SourceUnobserved(SCENE_MATERIAL, "SEQ-IN-09.BIN-A", null),
                    RemedyOutcome.DestinationOccupied("RACK-208.S01", "ENGINE-COVER-B"),
                    RemedyOutcome.DestinationUnobserved("RACK-209.S01"),
                ),
                outcomes,
            )
            assertEquals(emptyList(), second.remedySearches(), "다시 짓기가 탐색을 적었다")
        }
    }

    companion object {
        /** 재기동 장면의 거절을 받는 빈손 기체. */
        const val GATE_ROBOT = "hum-11"
        const val SCENE_SLOT = "RACK-206.S01"
        const val SCENE_SOURCE = "SEQ-IN-08.BIN-A"
        const val SCENE_MATERIAL = "ENGINE-COVER-A"

        /** 한 자재를 [bin] 에서 집어 [slot] 에 두는 작업 지시(임무 정의용). */
        private fun sceneRack(jobOrderId: String, slot: String, bin: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E2,
            materialRequirements = listOf(MaterialRequirement(SCENE_MATERIAL, 1)),
            equipmentRequirements = listOf(
                EquipmentRequirement(slot, EquipmentUse.DESTINATION, mapOf(EquipmentUse.PROP_MATERIAL to SCENE_MATERIAL)),
                EquipmentRequirement(bin, EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_MATERIAL to SCENE_MATERIAL)),
            ),
        )

        /** 판단하는 사람 — 운영자 판단은 사람만 낸다(ADR 47). */
        val OPERATOR = Approver("op-1", ApproverKind.PERSON)

        const val FOUND_ROBOT = "hum-02"
        const val NONE_ROBOT = "hum-03"
        const val OTHER_ROBOT = "hum-04"
        const val SILENT_ROBOT = "hum-05"
        const val UNMAPPED_ROBOT = "hum-06"
        const val CUT_ROBOT = "hum-07"
        const val CODE_ROBOT_A = "hum-08"
        const val CODE_ROBOT_B = "hum-09"
        const val CODE_ROBOT_C = "hum-10"

        /**
         * 재발을 내는 기체(`run-3`). 벤더 원문 모드를 든 픽스처라 셋째 사건이 원문을 싣는다.
         * `run-1` 의 `GRASP_FAILED` 사건과 같은 기체·같은 픽스처다 — 읽는 쪽이 두 벌을 견줄 때
         * 달라진 것이 **재발뿐**이어야 한다.
         */
        const val RECUR_ROBOT = OTHER_ROBOT

        val AGENT = Approver("narrator-1", ApproverKind.AGENT)
        private val FAR: Instant = Instant.parse("2099-01-01T00:00:00Z")

        /**
         * 용기 공급 하나. **출발지를 비워 둔다** — 플릿이 접수 시점에 확인하고 인수하지 않으므로
         * 경로가 `FLEET` 인 사건이 여기서 열린다.
         */
        private fun deliver() = JobOrder(
            jobOrderId = "WT-781",
            workMasterId = DeliverContainer.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E2,
            equipmentRequirements = listOf(
                EquipmentRequirement("OUT-07", EquipmentUse.SOURCE, mapOf(EquipmentUse.PROP_CONTAINER to "HU-1042")),
                EquipmentRequirement("SEQ-IN-02", EquipmentUse.DESTINATION),
            ),
        )

        const val RELOCATE_MATERIAL = "ENGINE-COVER-B"
        const val RELOCATE_SOURCE = "SEQ-IN-03.BIN-A"
        const val RELOCATE_ALTERNATIVE = "SEQ-IN-03.BIN-B"

        /**
         * 출발 자리가 빈 랙 주문 하나. **접수되지 않는다** — 점유 관문이 하달 전에 막고 그 자재를 든
         * 다른 자리를 계산한다. 목적지는 앞의 랙들과 겹치지 않게 둔다.
         */
        private fun relocate() = JobOrder(
            jobOrderId = "SEQ-RELOCATE",
            workMasterId = PrepareSequencedRack.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E2,
            materialRequirements = listOf(MaterialRequirement(RELOCATE_MATERIAL, 1)),
            equipmentRequirements = listOf(
                EquipmentRequirement("RACK-205.S01", "destination", mapOf("material" to RELOCATE_MATERIAL)),
                EquipmentRequirement(RELOCATE_SOURCE, "source", mapOf("material" to RELOCATE_MATERIAL)),
            ),
        )

        private fun patrol(jobOrderId: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = listOf(
                EquipmentRequirement("PUMP-01", EquipmentUse.INSPECTION_TARGET, mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1")),
            ),
        )

        private val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()
        private val NO_REMEDY: Path = Path.of("..", "profile", "fixtures", "no-remedy.json").normalize()

        /**
         * **불투명한 정지 코드**만 내는 픽스처. 코드 자체는 아무것도 말하지 않고 뜻은
         * `docs/vendors/fixture-stop-codes.md` 에만 있다 — 읽는 쪽이 번들을 되읽는 대신 코퍼스로
         * 좁혀야 하는 자리를 이것이 만든다(§15.179).
         */
        private val OPAQUE: Path = Path.of("..", "profile", "fixtures", "opaque-fault.json").normalize()

        /**
         * 벤더 이름공간의 결함 모드를 하나 더 든 픽스처.
         *
         * ★**공용 픽스처에 모드를 더하면 안 된다.** 추첨이 해당하는 모드마다 한 번씩 뽑으므로
         *   모드가 하나 늘면 같은 시드가 다른 순서를 낸다 — `FailureDraw` 가 그 자리를 적어 뒀고,
         *   실제로 `WithholdingTest` 둘이 빨개졌다.
         */
        private val VENDOR_FAULT: Path = Path.of("..", "profile", "fixtures", "vendor-fault.json").normalize()
    }
}

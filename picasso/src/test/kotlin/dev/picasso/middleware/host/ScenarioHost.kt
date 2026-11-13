package dev.picasso.middleware.host

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.harness.Harness
import dev.picasso.host.ApprovalHost
import dev.picasso.host.BundleWriter
import dev.picasso.host.FileEntitlements
import dev.picasso.middleware.CellMimic
import dev.picasso.middleware.ClientRobotPort
import dev.picasso.middleware.EquipmentRequirement
import dev.picasso.middleware.EquipmentUse
import dev.picasso.middleware.Evidence
import dev.picasso.middleware.InspectAsset
import dev.picasso.middleware.JobOrder
import dev.picasso.middleware.MaterialRequirement
import dev.picasso.middleware.Middleware
import dev.picasso.middleware.PrepareSequencedRack
import dev.picasso.middleware.ResultExport
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * **실행 계층을 세워 두고 승인 창구를 연다** — v1 의 담는 쪽(`docs/orchestration.md` §7.4).
 *
 * ```
 * ./gradlew :picasso:runApprovalHost
 * ./gradlew :picasso:runApprovalHost --args="--port 8770 --seconds 1800"
 * ```
 *
 * ## 왜 시험 소스에 있나
 *
 * `picasso` 는 라이브러리이고 진입점을 안 든다(§6). 실행 계층을 실제로 구동하는 유일한 주체는 시나리오
 * 구동기이며 v1 에서 그것은 시험 곁에 산다 — 시험 전용 설비 더블(`CellMimic`)과 하네스로 세계를 세우기 때문이다.
 * 이 구동기가 얹는 부품(승인 호스트·선언 파일·번들 쓰기)은 프로덕션 모듈 `host-kit` 에 있다(§15.314). 진짜 배치가
 * 생기면 포트 여섯을 다 드는 호스트가 이 자리를 대신한다.
 *
 * ## 네 자리를 미리 세워 둔다
 *
 * 부르는 쪽이 **한 번에 네 갈래**를 볼 수 있도록 제안을 넷 세운다. 갈래마다 다음 행동이 다른 것이
 * 거절을 종류로 가른 이유이고, 그 차이는 값으로 봐야 보인다.
 *
 * | 기체 | 무엇이 서 있나 | 시도하면 |
 * |---|---|---|
 * | `hum-02` | 제안 있음, 선언이 덮음 | **승인된다.** 값은 선언과 관측에서 온다. 다시 부르면 `CONSUMED` — 소모 기록이 돌아온다(ADR 46) |
 * | `hum-04` | 제안이 **가려져 있음** | `WITHHELD` — 자격이 있어도 안 눌린다 |
 * | `hum-05` | 제안 있음, 선언 범위 밖 | `ROBOT_OUT_OF_SCOPE` |
 * | `hum-03` | 든 채로는 딛을 스킬이 없음 | `NO_PROPOSAL` — 탐색이 `NONE` 을 냈다 |
 *
 * ## 선언 쪽 갈래는 **승인자를 바꿔** 본다
 *
 * ★**칸을 만들어도 데모가 안 채우면 빈 칸이다**(§15.177). 선언의 상태로 갈리는 거절 넷은 값이 이미
 * 있었는데 이 구동기가 한 승인자만 세워 두어 **밖에서는 한 번도 안 보였다.** 같은 자리(`hum-05`)에
 * 승인자만 바꿔 부르면 넷이 갈린다 — 그 자리는 어느 승인자로도 거절이라 제안이 안 소모된다.
 *
 * | 승인자 | 선언의 상태 | 시도하면 | 다음 행동 |
 * |---|---|---|---|
 * | `narrator-1` | 선언됨, `hum-05` 는 범위 밖 | `ROBOT_OUT_OF_SCOPE` | 범위를 넓힌다 |
 * | `narrator-2` | **선언이 없음** | `NOT_DECLARED` | 선언을 올린다 |
 * | `narrator-3` | 선언됨, **기간이 지남** | `EXPIRED` | 갱신한다 |
 * | `narrator-4` | 선언됨, **철회됨** | `REVOKED` | 왜 철회됐는지 보고 **사후 검토로** 간다 |
 *
 * 뒤의 둘이 가르는 것이 «전에 허락됐다가 지금 아니다» 와 «원래 없었다» 다(ADR 45).
 *
 * ## 시계를 승인 전까지 세워 둔다
 *
 * 성공하는 승인은 **지금 든 것의 이름**을 관측에서 가져오므로(ADR 44), 부르는 쪽이 부르기 전에 기체가
 * 내려놓으면 그 출처가 사라진다. 그래서 첫 승인이 설 때까지 가상 시계를 멈춰 둔다. 거절 갈래 셋은 값
 * 채우기 앞에서 판정되므로 시계와 무관하다.
 */
object ScenarioHost {

    private val FIXTURES: Path = Path.of("..", "profile", "fixtures").normalize()
    private val PRECOND: Path = FIXTURES.resolve("precondition.json")
    private val NO_REMEDY: Path = FIXTURES.resolve("no-remedy.json")

    const val APPROVES = "hum-02"
    const val WITHHELD = "hum-04"
    const val OUT_OF_SCOPE = "hum-05"
    const val NO_REMEDY_ROBOT = "hum-03"

    @JvmStatic
    fun main(args: Array<String>) {
        val options = args.toList().chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] }
        val port = options["--port"]?.toInt() ?: 0
        val seconds = options["--seconds"]?.toLong() ?: 600
        val exportDir = Path.of(options["--export"] ?: "build/export/live")
        val declarations = Path.of(options["--entitlements"] ?: "../handoff/narrator/entitlements.json")

        val entitlements = FileEntitlements.read(declarations)
        val lock = Any()
        val harness = Harness(
            mapOf(
                APPROVES to PRECOND,
                WITHHELD to PRECOND,
                OUT_OF_SCOPE to PRECOND,
                NO_REMEDY_ROBOT to NO_REMEDY,
            ),
        )
        val cell = CellMimic(now = { harness.clock.now() })
        val middleware = Middleware(
            ClientRobotPort(harness.client()),
            cell,
            now = { harness.clock.now() },
            // 둘째 제안이 가려진다 — 가림이 자격보다 먼저라는 것을 부르는 쪽이 실물로 본다.
            withholdEvery = 2,
            entitlements = entitlements,
        )

        val arming = Arming(harness, cell, middleware, lock)
        // 순서가 곧 가림 차례다. 둘째로 세우는 것이 가려진다.
        arming.arm(APPROVES, "PATROL-APPROVES")
        arming.arm(WITHHELD, "PATROL-WITHHELD")
        arming.arm(OUT_OF_SCOPE, "PATROL-OUT-OF-SCOPE")
        arming.arm(NO_REMEDY_ROBOT, "PATROL-NO-REMEDY")

        ApprovalHost(middleware, lock, port).use { host ->
            announce(host, exportDir, declarations, entitlements, middleware.instanceId)
            var advancing = false
            // ★**구동 식별자는 이 바퀴에 하나다.** 내보낼 때마다 찍으면 읽는 쪽의 «같은 구동의 같은
            //   줄은 한 번만» 이 죽는다 — 앞 판이 그랬고 30분에 만 개가 넘었다(BundleWriter 의 KDoc).
            val live = BundleWriter(exportDir)
            val until = Instant.now().plusSeconds(seconds)
            while (Instant.now().isBefore(until)) {
                synchronized(lock) {
                    middleware.pump()
                    // 첫 승인이 서면 그때부터 세계가 흐른다 — 조치가 돌고 사건이 열려야 감사가 찬다.
                    if (!advancing && middleware.executions().any { it.approvedBy != null }) {
                        advancing = true
                        println("[host] 승인이 섰다. 이제 시계를 돌린다.")
                    }
                    if (advancing) {
                        harness.advance(Duration.ofSeconds(1))
                        middleware.pump()
                    }
                    live.snapshot(middleware.incidents(), middleware.remedySearches(), harness.clock.now())
                    // 결과 통보는 담는 쪽이 나르는 것이다 — 여기 쓰는 것은 그 모양의 참조다(ADR 48).
                    live.carry(middleware.responses(), middleware.instanceId)
                }
                Thread.sleep(150)
            }
            println("[host] ${seconds}초가 지나 닫는다.")
        }
        harness.close()
    }

    /** 든 채로 점검 순회를 밀어 넣어 제안을 하나 세운다. 구동기와 시험이 같은 모양을 쓴다. */
    private class Arming(
        private val harness: Harness,
        private val cell: CellMimic,
        private val middleware: Middleware,
        private val lock: Any,
    ) {
        private var racks = 0

        fun arm(robotId: String, jobOrderId: String) {
            val slot = "RACK-204.S0${++racks}"
            val order = JobOrder(
                jobOrderId = "SEQ-$racks",
                workMasterId = PrepareSequencedRack.WORK_MASTER,
                version = 17,
                requiredEvidence = Evidence.E2,
                materialRequirements = listOf(MaterialRequirement("ENGINE-COVER-A", 1)),
                equipmentRequirements = listOf(
                    EquipmentRequirement(slot, "destination", mapOf("material" to "ENGINE-COVER-A")),
                    EquipmentRequirement("SEQ-IN-02.BIN-A", "source", mapOf("material" to "ENGINE-COVER-A")),
                ),
            )
            cell.program(slot, "ENGINE-COVER-A")
            val accepted = middleware.submit(order, robotId)
            check(accepted is Middleware.Submission.Accepted) { "$robotId 가 랙을 안 받았다: $accepted" }

            repeat(200) {
                synchronized(lock) {
                    middleware.pump()
                    harness.advance(Duration.ofSeconds(1))
                    middleware.pump()
                }
                Thread.sleep(20)
                if (accepted.execution.units.first().hold.kind == HoldKind.HOLD_KIND_HOLDING) {
                    // 든 채로 순회를 밀어 넣으면 사슬 검사가 막고, 그 거절이 제안을 세운다.
                    synchronized(lock) { middleware.submit(patrol(jobOrderId), robotId) }
                    return
                }
            }
            error("$robotId 가 끝내 들지 않았다")
        }

        private fun patrol(jobOrderId: String) = JobOrder(
            jobOrderId = jobOrderId,
            workMasterId = InspectAsset.WORK_MASTER,
            version = 1,
            requiredEvidence = Evidence.E0,
            equipmentRequirements = listOf(
                EquipmentRequirement(
                    "PUMP-01",
                    EquipmentUse.INSPECTION_TARGET,
                    mapOf(EquipmentUse.PROP_LOCATION to "PUMP-ROOM-1"),
                ),
            ),
        )
    }

    private fun announce(host: ApprovalHost, exportDir: Path, declarations: Path, entitlements: FileEntitlements, instanceId: String) {
        println("[host] 승인 호스트: http://127.0.0.1:${host.port}${ApprovalHost.PATH}  (POST · 루프백 전용)")
        println("[host] 선언 목록: ${declarations.toAbsolutePath().normalize()}  승인자=${entitlements.approvers()}")
        println("[host] 두 대장: ${exportDir.toAbsolutePath().normalize()}")
        println("[host]   ★되돌려 댈 것은 인계본이 아니라 이 한 벌이다 — 창구와 같은 제안을 든다.")
        println("[host]   구동 식별자는 이 바퀴에 하나이고, 두 대장이 늘 때만 다시 쓴다.")
        println("[host] 결과 통보: ${exportDir.resolve(ResultExport.JOB_RESPONSES).toAbsolutePath().normalize()}  (판 ${ResultExport.SCHEMA_VERSION})")
        println("[host]   인스턴스=$instanceId. 승인 답과 결과 통보가 같은 값을 든다 — 실행 식별자는 이 값과 짝짓는다.")
        println("[host] 서 있는 자리 넷:")
        println("[host]   $APPROVES / PATROL-APPROVES        → 승인된다 (다시 부르면 CONSUMED)")
        println("[host]   $WITHHELD / PATROL-WITHHELD        → WITHHELD")
        println("[host]   $OUT_OF_SCOPE / PATROL-OUT-OF-SCOPE → ROBOT_OUT_OF_SCOPE")
        println("[host]   $NO_REMEDY_ROBOT / PATROL-NO-REMEDY → NO_PROPOSAL")
        println("[host] 선언 쪽 갈래는 $OUT_OF_SCOPE / PATROL-OUT-OF-SCOPE 에 승인자만 바꿔 부른다:")
        println("[host]   narrator-1 → ROBOT_OUT_OF_SCOPE  (선언됨, 그 기체가 범위 밖 — 범위를 넓힌다)")
        println("[host]   narrator-2 → NOT_DECLARED        (선언이 없다 — 올린다)")
        println("[host]   narrator-3 → EXPIRED             (기간이 지났다 — 갱신한다)")
        println("[host]   narrator-4 → REVOKED             (철회됐다 — 사후 검토로 간다)")
        println(
            """[host] 예: curl -s -X POST http://127.0.0.1:${host.port}${ApprovalHost.PATH} -d """ +
                """'{"approverId":"narrator-1","approverKind":"AGENT","robotId":"$APPROVES",""" +
                """"jobOrderId":"PATROL-APPROVES","sawSkillTypes":["pick_place"]}'""",
        )
    }
}

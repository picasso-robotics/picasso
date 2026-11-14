package dev.picasso.middleware.host

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.harness.Harness
import dev.picasso.host.ApprovalHost
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
import dev.picasso.middleware.leaveHolding
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 승인 시도가 **소켓을 실제로 지난다**(`docs/orchestration.md` §7.4).
 *
 * 구조 단언은 표면이 프로세스 밖에서 어떤 모양으로 보이는지를 증명하지 않는다 — 인코딩만 대면 봉투
 * 한 겹을 놓쳐도 아무도 모른다. 그래서 여기서는 **진짜 포트를 열고 HTTP 로 두드린다.**
 *
 * 세우는 자리는 `ScenarioHost` 와 같고 선언 목록도 같은 파일이다. 인계본에 실려 나가는 그 파일이
 * 실제로 파싱되는지가 여기서 함께 물린다.
 */
class ApprovalHostTest {

    private class World : AutoCloseable {
        val lock = Any()
        val harness = Harness(mapOf(ROBOT to PRECOND, OUT_OF_SCOPE to PRECOND))
        val cell = CellMimic(now = { harness.clock.now() })
        val middleware = Middleware(
            ClientRobotPort(harness.client()),
            cell,
            now = { harness.clock.now() },
            entitlements = FileEntitlements.read(DECLARATIONS),
        )

        private var racks = 0

        /**
         * 든 채로 점검 순회를 밀어 넣어 제안을 하나 세운다. 랙은 든 채 실패해 사람이 취소로 닫는다([leaveHolding]) — 든 채 **도는** 랙
         * 뒤에 내면 그 손에 든 것이 랙의 일이라 제안이 서지 않는다(§15.319).
         */
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
            val accepted = assertIs<Middleware.Submission.Accepted>(middleware.submit(order, robotId))
            middleware.leaveHolding(accepted.execution, harness) { drive(it) }
            assertIs<Middleware.Submission.Rejected>(middleware.submit(patrol(jobOrderId), robotId))
        }

        private fun drive(until: () -> Boolean) {
            repeat(200) {
                middleware.pump()
                if (until()) return
                harness.advance(Duration.ofSeconds(1))
                Thread.sleep(20)
                middleware.pump()
                if (until()) return
            }
            error("조건에 못 미쳤다")
        }

        override fun close() = harness.close()
    }

    private fun post(port: Int, body: String): HttpResponse<String> = CLIENT.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port${ApprovalHost.PATH}"))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun fields(json: String): Map<String, Value> =
        Struct.newBuilder().also { JsonFormat.parser().merge(json, it) }.build().fieldsMap

    @Test
    fun `밖에서 온 호출이 승인으로 서고 실린 값이 돌아온다`() {
        // ★★**이 시험이 있기 전까지 표면의 값은 0 이었다.** 프로세스 안에서 같은 함수를 부르는 것은
        //   기록을 증명하지 저장소 경계를 증명하지 않는다. 여기서는 소켓이 실제로 지나간다.
        World().use { w ->
            w.arm(ROBOT, "PATROL-1")
            val held = assertNotNull(heldObject(w), "기체가 든 것의 이름을 안 줬다 — 이 시험의 전제가 비었다")

            ApprovalHost(w.middleware, w.lock).use { host ->
                val answer = post(host.port, attempt(ROBOT, "PATROL-1"))
                assertEquals(200, answer.statusCode())

                val body = fields(answer.body())
                assertEquals("APPROVED", body.getValue("outcome").stringValue, answer.body())
                val step = body.getValue("steps").listValue.getValues(0).structValue.fieldsMap
                val parameters = step.getValue("parameters").structValue.fieldsMap
                // 목적지는 선언 파일에서, 대상은 기체의 관측에서.
                assertEquals("DROP-01", parameters.getValue("destination").stringValue)
                assertEquals(held, parameters.getValue("object_id").stringValue)
            }
        }
    }

    @Test
    fun `자격 주장을 실어도 판정이 안 바뀐다`() {
        // ★★조건 2·3 이 여기서 선다. 부르는 쪽이 «나는 자격이 있다» 고 적고 값까지 실어도, 판정은
        //   picasso 의 선언 목록이 하고 그 목록에 이 기체가 없다.
        World().use { w ->
            w.arm(OUT_OF_SCOPE, "PATROL-1")
            ApprovalHost(w.middleware, w.lock).use { host ->
                val forged = """{"approverId":"narrator-1","approverKind":"AGENT","robotId":"$OUT_OF_SCOPE",""" +
                    """"jobOrderId":"PATROL-1","sawSkillTypes":["pick_place"],"entitled":true,""" +
                    """"robotIds":["$OUT_OF_SCOPE"],"parameters":{"destination":"내가-정한-자리"}}"""
                val answer = post(host.port, forged)

                assertEquals(200, answer.statusCode(), "거절이 오류로 나갔다")
                val body = fields(answer.body())
                assertEquals("REFUSED", body.getValue("outcome").stringValue)
                assertEquals("ROBOT_OUT_OF_SCOPE", body.getValue("refusal").stringValue)
                assertNotNull(w.middleware.proposal(OUT_OF_SCOPE, "PATROL-1"), "거절이 제안을 소모했다")
            }
        }
    }

    @Test
    fun `같은 제안에 두 번 승인하면 둘째는 소모로 거절된다`() {
        // ★부르는 쪽이 응답을 받기 전에 죽었다가 다시 보내는 자리다. 둘째가 «제안 없음» 이면 제 요청이
        //   들어갔는지 모른다 — 소켓 너머로 소모 기록이 실제로 돌아와야 그것을 가른다(ADR 46).
        World().use { w ->
            w.arm(ROBOT, "PATROL-1")
            ApprovalHost(w.middleware, w.lock).use { host ->
                val first = fields(post(host.port, attempt(ROBOT, "PATROL-1")).body())
                assertEquals("APPROVED", first.getValue("outcome").stringValue)

                val again = post(host.port, attempt(ROBOT, "PATROL-1"))
                assertEquals(200, again.statusCode(), "소모가 오류로 나갔다")
                val body = fields(again.body())
                assertEquals("4", body.getValue("schemaVersion").stringValue)
                // 두 답이 같은 인스턴스를 댄다 — 소켓 너머에서도 «다시 뜨지 않았다» 를 이 값으로 가른다(ADR 48).
                assertEquals(w.middleware.instanceId, first.getValue("instanceId").stringValue)
                assertEquals(w.middleware.instanceId, body.getValue("instanceId").stringValue)
                assertEquals("REFUSED", body.getValue("outcome").stringValue)
                assertEquals("CONSUMED", body.getValue("refusal").stringValue, again.body())
                val consumed = body.getValue("consumed").structValue.fieldsMap
                assertEquals(first.getValue("executionId").stringValue, consumed.getValue("executionId").stringValue)
                assertEquals("narrator-1", consumed.getValue("approverId").stringValue)
            }
        }
    }

    @Test
    fun `못 읽는 요청만 오류이고 거절은 정상 응답이다`() {
        // ★둘을 같은 모양으로 내면 읽는 쪽이 자기 오타와 «자격 없음» 을 구별하지 못한다.
        World().use { w ->
            ApprovalHost(w.middleware, w.lock).use { host ->
                val broken = post(host.port, "{")
                assertEquals(400, broken.statusCode())
                assertTrue(fields(broken.body()).containsKey("error"), broken.body())

                // 제안이 하나도 없는 세계다. 이것은 오류가 아니라 거절이다.
                val refused = post(host.port, attempt(ROBOT, "PATROL-1"))
                assertEquals(200, refused.statusCode())
                assertEquals("NO_PROPOSAL", fields(refused.body()).getValue("refusal").stringValue)
            }
        }
    }

    @Test
    fun `POST 가 아니면 받지 않는다`() {
        World().use { w ->
            ApprovalHost(w.middleware, w.lock).use { host ->
                val answer = CLIENT.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:${host.port}${ApprovalHost.PATH}")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                )
                assertEquals(405, answer.statusCode())
                assertTrue(fields(answer.body()).containsKey("error"), answer.body())
            }
        }
    }

    private companion object {
        const val ROBOT = "hum-02"
        const val OUT_OF_SCOPE = "hum-05"

        val CLIENT: HttpClient = HttpClient.newHttpClient()
        val PRECOND: Path = Path.of("..", "profile", "fixtures", "precondition.json").normalize()

        /** **인계본에 실려 나가는 그 파일이다.** 여기서 안 파싱되면 받는 쪽도 못 읽는다. */
        val DECLARATIONS: Path = Path.of("..", "handoff", "narrator", "entitlements.json").normalize()

        fun attempt(robotId: String, jobOrderId: String) =
            """{"approverId":"narrator-1","approverKind":"AGENT","robotId":"$robotId",""" +
                """"jobOrderId":"$jobOrderId","sawSkillTypes":["pick_place"]}"""

        fun heldObject(w: World): String? = w.middleware.executions()
            .flatMap { it.units }
            .firstOrNull { it.hold.kind == HoldKind.HOLD_KIND_HOLDING }
            ?.hold?.objectRef?.takeIf { it.isNotEmpty() }

        fun patrol(jobOrderId: String) = JobOrder(
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
}

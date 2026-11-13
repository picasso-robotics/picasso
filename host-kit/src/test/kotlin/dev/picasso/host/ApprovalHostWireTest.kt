package dev.picasso.host

import com.google.protobuf.Struct
import com.google.protobuf.util.JsonFormat
import dev.picasso.middleware.ApprovalAttempt
import dev.picasso.middleware.ApprovalOutcome
import dev.picasso.middleware.ApprovalRefusal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **승인 호스트를 미들웨어 없이 댄다**(의존 정비 H1, §15.314). 승인을 미들웨어에 적용하는 것은 담는 쪽이 넘긴 콜백이고, 이 호스트는
 * 읽고·넘기고·답할 뿐이다. 미들웨어를 세운 통합 시험은 picasso 의 `ApprovalHostTest` 에 있다(시험 전용 설비 더블을 쓴다).
 */
class ApprovalHostWireTest {

    @Test
    fun `읽은 시도를 콜백에 그대로 넘기고 답에 인스턴스 id 를 싣는다`() {
        val seen = mutableListOf<ApprovalAttempt>()
        ApprovalHost("mw-test", attempt = { seen += it; ApprovalOutcome.Refused(ApprovalRefusal.NO_PROPOSAL, "제안이 없다") }).use { host ->
            val answer = post(host.port, ATTEMPT)
            assertEquals(200, answer.statusCode(), answer.body())
            assertEquals(listOf("hum-02" to "PATROL-1"), seen.map { it.robotId to it.jobOrderId })
            val body = fields(answer.body())
            assertEquals("mw-test", body.getValue("instanceId").stringValue, answer.body())
            assertEquals("NO_PROPOSAL", body.getValue("refusal").stringValue, answer.body())
        }
    }

    @Test
    fun `못 읽는 요청의 사유가 따옴표와 개행을 들어도 오류 본문은 JSON 이다`() {
        // 사유는 해석기의 예외 문구다 — 요청 본문의 조각(따옴표·역슬래시·개행)이 그대로 들어올 수 있다. 인용이 새면 읽는 쪽이 본문을 못 읽는다.
        ApprovalHost("mw-test", attempt = { error("부르면 안 된다") }).use { host ->
            val broken = post(host.port, "{\"approverId\": \"a\\\"b\n")
            assertEquals(400, broken.statusCode())
            assertEquals(setOf("error"), fields(broken.body()).keys, broken.body())
        }
    }

    private companion object {
        val CLIENT: HttpClient = HttpClient.newHttpClient()

        const val ATTEMPT = """{"approverId":"narrator-1","approverKind":"AGENT","robotId":"hum-02","jobOrderId":"PATROL-1","sawSkillTypes":["pick_place"]}"""

        fun post(port: Int, body: String): HttpResponse<String> = CLIENT.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port${ApprovalHost.PATH}"))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        fun fields(json: String): Map<String, com.google.protobuf.Value> =
            Struct.newBuilder().also { JsonFormat.parser().merge(json, it) }.build().fieldsMap
    }
}

package dev.picasso.host

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.picasso.middleware.ApprovalAttempt
import dev.picasso.middleware.ApprovalOutcome
import dev.picasso.middleware.ApprovalWire
import dev.picasso.middleware.Middleware
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * 승인 시도를 **프로세스 밖에서** 받는 입(`docs/orchestration.md` §7.4).
 *
 * ## 여기가 담는 쪽이다
 *
 * `picasso` 는 라이브러리이므로 진입점도 소켓도 안 든다(§6). 밖으로 내는 일은 **본 계층을 세우는 쪽**의
 * 몫이라 이 입은 담는 쪽의 부품 모듈(`host-kit`)에 있다(의존 정비 H1, §15.314). 지금 이것을 띄우는 것은 시험 소스의
 * 참조 실행기(`ScenarioHost`)뿐이다. 진짜 배치가 생기면 포트 여섯을 다 드는 호스트가 이것을 그대로 얹거나 인증 있는 것으로
 * 대신하며, 어느 쪽이든 `ApprovalWire` 는 그대로다(`docs/limits.md` §15.176).
 *
 * ## 승인은 담는 쪽이 적용한다
 *
 * 미들웨어는 **단일 적용자**를 전제로 한다(배선도의 `pump`). 이 입은 읽은 시도를 [attempt] 에 넘길 뿐이고, 그 안에서
 * 구동 루프와 같은 자물쇠를 지나는 것은 담는 쪽의 일이다 — 담는 쪽마다 자물쇠가 다르다(`synchronized`, `ReentrantLock`,
 * 저장 트랜잭션). 동시에 들어온 승인 둘이 같은 제안을 두 번 쓰는 일은 그 자물쇠가 막는다. 시험과 참조 실행기는
 * 객체 자물쇠 하나를 쓰는 편의 생성자를 쓴다.
 *
 * ## 거절은 200 이다
 *
 * 거절은 설계된 정상 경로이므로 오류 코드로 내지 않는다 — 내면 읽는 쪽에서 «자격 없음» 과 «시스템
 * 고장» 이 같은 모양이 된다. 오류로 내는 것은 **못 읽는 요청**(400)과 **틀린 메서드**(405)뿐이며,
 * 그 둘도 JSON 으로 답한다. 부르는 쪽이 답을 파싱하는 길이 하나여야 한다.
 */
class ApprovalHost(
    /** 답에 싣는 미들웨어 인스턴스 id([Middleware.instanceId]). */
    private val instanceId: String,
    port: Int = 0,
    /** 읽은 승인 시도를 미들웨어에 적용한다. 구동 루프와 같은 자물쇠 안에서 부르는 것은 담는 쪽의 일이다. */
    private val attempt: (ApprovalAttempt) -> ApprovalOutcome,
) : AutoCloseable {

    /** 객체 자물쇠 하나를 지나 [Middleware.attemptApproval] 을 부른다. 구동 루프가 같은 [lock] 을 든다. */
    constructor(middleware: Middleware, lock: Any, port: Int = 0) :
        this(middleware.instanceId, port, { synchronized(lock) { middleware.attemptApproval(it) } })

    /** **루프백에만 붙는다.** 이 입은 신원을 인증하지 않으므로(§15.3) 밖으로 열 것이 아니다. */
    private val server: HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)

    val port: Int get() = server.address.port

    init {
        server.createContext(PATH, ::serve)
        // 한 번에 하나. 어차피 자물쇠를 지나므로 스레드를 늘려도 얻을 것이 없다.
        server.executor = null
        server.start()
    }

    private fun serve(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "POST") {
                return reply(exchange, 405, error("승인 시도는 POST 다"))
            }
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val decoded = try {
                ApprovalWire.decode(body)
            } catch (e: IllegalArgumentException) {
                // **못 읽는 요청은 거절이 아니다.** 부르는 쪽이 자기 오타와 «자격 없음» 을 구별해야 한다.
                return reply(exchange, 400, error(e.message ?: "승인 요청을 읽지 못했다"))
            }
            reply(exchange, 200, ApprovalWire.encode(attempt(decoded), instanceId))
        } finally {
            exchange.close()
        }
    }

    /** `{"error":"<사유>"}`. 사유는 protobuf JSON 인코더가 인용한다 — 이 모듈에 인용 함수를 한 벌 더 두지 않는다. */
    private fun error(reason: String): String =
        JsonFormat.printer().omittingInsignificantWhitespace()
            .print(Struct.newBuilder().putFields("error", Value.newBuilder().setStringValue(reason).build()).build())

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    override fun close() = server.stop(0)

    companion object {
        const val PATH: String = "/approvals"
    }
}

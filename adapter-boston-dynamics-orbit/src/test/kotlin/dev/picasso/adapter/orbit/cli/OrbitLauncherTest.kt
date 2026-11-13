package dev.picasso.adapter.orbit.cli

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.picasso.adapter.orbit.OrbitHttpLink
import dev.picasso.client.PicassoClient
import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.FaultObservability
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.wire.RequestHeaders
import dev.picasso.uplink.RecordingPublisher
import dev.picasso.uplink.report.DiscoveryAck
import dev.picasso.uplink.report.RobotDiscovery
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **배치가 실제로 선다.** 런처가 플릿에 붙어 기체를 발견하고 계약 뒤에 세우며, 소비자가 **실 포트로** 그것을
 * 두드려 일을 시킨다.
 *
 * ## 이 시험이 처음 지나는 것
 *
 * 여태 조립은 시험 안에만 있었다 — `HostedRobot` 을 손으로 만들고 in-process 전송으로 붙였다. 여기서는
 * **런처가 조립하고 실 TCP 포트가 열린다.** 남쪽만 스텁이고(가짜 Orbit HTTP) 그 사이 전부가 진짜다:
 * `PicassoClient` → gRPC/netty → `AdapterHost` → `OrbitAdapter` → `OrbitHttpLink` → HTTP.
 *
 * **진짜 Orbit 인스턴스는 여전히 아니다**(C-3). 스텁이 답하는 모양은 우리가 벤더 문서에서 읽어 적은 것이다.
 */
class OrbitLauncherTest {

    private lateinit var orbit: HttpServer
    private lateinit var orbitUrl: String
    private val routes = mutableMapOf<String, Pair<Int, String>>()
    /** 스텁의 처리 스레드가 적고 시험 스레드가 읽는다 — 펌프 스레드를 돌리는 시험이 있어 줄을 세운다. */
    private val received: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** 미션 목록 응답을 이만큼 늦춘다. 플릿 서버의 왕복 시간 자리 — 접수가 그만큼 걸린다. */
    private var missionsDelayMillis = 0L

    /** 파견 뒤에 실행이 끝난 것으로 답할지. 벤더의 시간 축을 시험이 쥔다. */
    private var runEnded = false

    /** 있으면 파견 응답을 이것이 열릴 때까지 붙든다. 들어서면 [dispatchEntered] 가 내려간다. */
    @Volatile private var dispatchHeld: CountDownLatch? = null
    private val dispatchEntered = CountDownLatch(1)

    @BeforeTest
    fun startOrbit() {
        orbit = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        orbit.createContext("/") { handle(it) }
        orbit.start()
        orbitUrl = "http://127.0.0.1:${orbit.address.port}"

        routes["/"] = 200 to ""
        routes["/api/v0/api_token/authenticate/"] = 200 to """{"ok":true}"""
        routes["/api/v0/robots/"] = 200 to """[
            {"hostname":"spot-a.line-a","nickname":"spot-a","robotIndex":0,"username":"admin"},
            {"hostname":"spot-b.line-a","nickname":"spot-b","robotIndex":1,"username":"admin"}]"""
        routes["/api/v0/missions/"] = 200 to """[{"uuid":"m-1","name":"DOCK-3"}]"""
        routes["/api/v0/calendar/mission/dispatch/spot-a"] = 200 to """{"uuid":"run-1"}"""
        routes["/api/v0/run_events/"] = 200 to """{"resources":[{"uuid":"e-1","actionName":"dock","error":0}]}"""
    }

    @AfterTest
    fun stopOrbit() = orbit.stop(0)

    private fun handle(exchange: HttpExchange) {
        received += "${exchange.requestMethod} ${exchange.requestURI.path}"
        if (exchange.requestURI.path == "/api/v0/missions/" && missionsDelayMillis > 0) Thread.sleep(missionsDelayMillis)
        if (exchange.requestURI.path == "/api/v0/calendar/mission/dispatch/spot-a") {
            dispatchHeld?.let {
                dispatchEntered.countDown()
                it.await(8, TimeUnit.SECONDS)
            }
        }
        if (exchange.requestURI.path == "/") {
            exchange.responseHeaders.add("Set-Cookie", "x-csrf-token=csrf-1; Path=/")
        }
        val payload = if (exchange.requestURI.path == "/api/v0/runs/") {
            // 실행은 시험이 끝냈다고 할 때까지 안 끝난다 — 종착은 `endTime` 이 정한다.
            val end = if (runEnded) ""","endTime":"2026-09-10T00:05:00Z"""" else ""
            """{"resources":[{"uuid":"run-1","robotNickname":"spot-a","startTime":"2026-09-10T00:00:00Z"$end}]}"""
        } else {
            routes[exchange.requestURI.path]?.second ?: """{"error":"no route"}"""
        }
        val status = if (exchange.requestURI.path == "/api/v0/runs/") 200 else (routes[exchange.requestURI.path]?.first ?: 404)
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun options() = OrbitLauncher.Options(
        orbitUrl = orbitUrl,
        token = "orbit-token",
        site = "line-a",
        instance = "orbit-line-a",
        profile = PROFILE,
        port = 0,
        // 시험이 걸음을 센다 — 스케줄러가 돌면 스위트가 벽시계에 매인다(§12.1 과 같은 이유).
        pumpMillis = 0,
    )

    @Test
    fun `런처가 플릿에서 발견한 기체들을 실 포트의 계약 뒤에 세운다`() {
        val discovered = mutableListOf<String>()
        val sink = RobotDiscovery { site, instance, robots ->
            discovered += robots.map { "$site/$instance/${it.robotId}" }
            DiscoveryAck(robots.map { it.robotId }, emptyMap())
        }
        val published = RecordingPublisher()

        OrbitLauncher(options()).start(publisher = published, discovery = sink).getOrThrow().use { running ->
            // **발견이 먼저다.** 무엇을 세울지가 그 답에서 온다.
            assertEquals(listOf("line-a/orbit-line-a/spot-a", "line-a/orbit-line-a/spot-b"), discovered)
            assertEquals(listOf("spot-a", "spot-b"), running.host.robots.keys.sorted())
            assertTrue(running.port > 0, "실 포트가 안 열렸다")

            // **포트가 열린 뒤에 ONLINE 이 나간다**(§10.2) — 기체마다 하나씩.
            assertEquals(2, published.publications.count { it.retained })

            val channel: ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", running.port).usePlaintext().build()
            try {
                val client = PicassoClient(channel, "line-controller")

                // 한 프로세스가 기체 여럿을 든다 — 소비자는 헤더의 robot_id 로 지목한다.
                assertTrue(client.capabilities("spot-a").skillsList.any { it.skillType == "navigate_to" })
                assertTrue(client.capabilities("spot-b").skillsList.isNotEmpty())

                // **일을 시킨다.** 계약의 location 이 저작된 미션의 이름으로 읽혀 파견된다.
                val handle = client.start(
                    "spot-a", "t-1", revision = 1, skillType = "navigate_to",
                    parameters = listOf(ParameterValue.newBuilder().setKey("location").setStringValue("DOCK-3").build()),
                )
                assertTrue(handle.hasHandle(), handle.rejection.toString())
                assertTrue("POST /api/v0/calendar/mission/dispatch/spot-a" in received, received.toString())

                // **스트림을 먼저 연다.** 열기 전에 일어난 전이는 되짚어 오고, 그 뒤의 것은 밀려 온다.
                val follower = client.follow("spot-a", handle.handle)

                // **스냅샷은 결함만 모른다.** 플릿이 기체 결함을 안 나르므로 결함은 비우고 못 봤다고 적는다 —
                // *결함 없음* 으로 접지 않기로 한 것(§15.99)이 계약 0.10.0 의 자리에 실린다. 태스크는 그대로 현재값이라
                // 소비자가 리빌드에 쓸 수 있다(§15.263).
                val blind = client.snapshot("spot-a")
                assertEquals(FaultObservability.FAULT_OBSERVABILITY_NOT_OBSERVABLE, blind.faultObservability)
                // 손도 같은 이유로 못 본다(계약 0.11.0) — 플릿 API 가 잔여 파지를 안 나른다. 빈손으로 접지 않는다.
                assertEquals(dev.picasso.contracts.v1.HoldKind.HOLD_KIND_NOT_OBSERVABLE, blind.hold.kind)
                assertTrue(blind.faultsList.isEmpty(), blind.faultsList.toString())
                assertEquals(listOf("t-1"), blind.tasksList.map { it.taskId })

                // 실행이 끝나면 종착이 계약으로 올라온다. 펌프는 **아무 RPC 나** 지나므로 아는 이름 질의로 민다.
                runEnded = true
                assertEquals(listOf("DOCK-3"), client.knownSiteNames("spot-a").namesList)

                // 실 소켓이라 밀려 오는 데 시간이 든다. **완료를 기다리되 무한정은 아니다** — 안 오면 시험이
                // 정지가 아니라 실패로 끝나야 한다.
                val deadline = System.nanoTime() + 5_000_000_000L
                while (!follower.completed && System.nanoTime() < deadline) Thread.sleep(20)
                assertTrue(follower.completed, "종착 스트림이 안 닫혔다: ${follower.updates.map { it.state }}")
                assertEquals(TaskState.TASK_STATE_SUCCEEDED, follower.updates.last().state)
            } finally {
                channel.shutdownNow()
            }
        }
    }

    @Test
    fun `펌프 스레드가 도는 배치에서 동시에 들어온 StartTask 는 하나만 받고 플릿에 한 번만 파견한다`() {
        // 운영 배치의 모양 그대로다 — 실 포트(RPC 마다 gRPC 스레드)와 런처의 펌프 스레드. 위 시험은 펌프를 끄고
        // 한 소비자만 두드려 이 축을 안 지난다. 접수는 플릿 왕복(미션 목록·파견) 동안 «도는 태스크 없음» 인 채라,
        // 줄을 안 세우면 둘이 함께 지나가 **같은 기체에 미션이 두 번 파견된다.**
        missionsDelayMillis = 30
        OrbitLauncher(options().copy(pumpMillis = 1)).start().getOrThrow().use { running ->
            val channel: ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", running.port).usePlaintext().build()
            try {
                val tasks = TaskServiceGrpc.newBlockingStub(channel)
                val gate = CountDownLatch(1)
                val answers = Collections.synchronizedList(mutableListOf<Result<StartTaskResponse>>())
                val callers = (1..4).map { i ->
                    thread(name = "caller-$i") {
                        gate.await()
                        answers += runCatching {
                            tasks.withDeadlineAfter(10, TimeUnit.SECONDS).startTask(
                                StartTaskRequest.newBuilder()
                                    .setHeader(RequestHeaders.build("picasso.v1.StartTaskRequest", "spot-a", "line-controller-$i"))
                                    .setTaskId("t-$i").setRevision(1).setRobotId("spot-a").setSkillType("navigate_to")
                                    .addParameters(ParameterValue.newBuilder().setKey("location").setStringValue("DOCK-3"))
                                    .build(),
                            )
                        }
                    }
                }
                gate.countDown()
                callers.forEach { it.join(15_000) }

                val failed = answers.filter { it.isFailure }.map { it.exceptionOrNull() }
                assertTrue(failed.isEmpty(), "RPC 가 깨졌다: $failed")
                val accepted = answers.mapNotNull { it.getOrNull() }.filter { it.hasHandle() }
                assertEquals(1, accepted.size, "배타 검사를 둘 이상이 지났다: ${accepted.map { it.handle.taskId }}")
                val dispatched = synchronized(received) { received.count { it == "POST /api/v0/calendar/mission/dispatch/spot-a" } }
                assertEquals(1, dispatched, "같은 기체에 파견이 $dispatched 번 나갔다")
            } finally {
                channel.shutdownNow()
            }
        }
    }

    @Test
    fun `플릿이 파견에 답하지 않는 동안에도 스냅숏은 그 파견을 기다리지 않는다`() {
        // 운영 배치의 모양(실 포트와 펌프 스레드)에서 파견 POST 의 응답을 붙든다. 그 접수는 링크 잠금만 쥐고 서므로(§15.269),
        // 링크를 안 쓰는 스냅숏은 그 기체의 것이든 같은 플릿 뒤 다른 기체의 것이든 기다리지 않는다.
        val held = CountDownLatch(1)
        dispatchHeld = held
        OrbitLauncher(options().copy(pumpMillis = 1)).start().getOrThrow().use { running ->
            val channel: ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", running.port).usePlaintext().build()
            val sender = thread(name = "sender") {
                runCatching {
                    PicassoClient(channel, "line-controller").start(
                        "spot-a", "t-1", revision = 1, skillType = "navigate_to",
                        parameters = listOf(ParameterValue.newBuilder().setKey("location").setStringValue("DOCK-3").build()),
                    )
                }
            }
            try {
                assertTrue(dispatchEntered.await(5, TimeUnit.SECONDS), "파견이 플릿에 닿지 않았다")
                val events = EventServiceGrpc.newBlockingStub(channel)
                listOf("spot-a", "spot-b").forEach { robot ->
                    val started = System.nanoTime()
                    val failure = runCatching {
                        events.withDeadlineAfter(2, TimeUnit.SECONDS).getSnapshot(
                            GetSnapshotRequest.newBuilder()
                                .setHeader(RequestHeaders.build("picasso.v1.GetSnapshotRequest", robot, "line-controller"))
                                .setRobotId(robot).build(),
                        )
                    }.exceptionOrNull()
                    val waited = Duration.ofNanos(System.nanoTime() - started)
                    assertTrue(failure == null && waited < Duration.ofMillis(500), "$robot 스냅숏이 붙든 파견을 기다렸다: $waited ($failure)")
                }
            } finally {
                held.countDown()
                sender.join(10_000)
                channel.shutdownNow()
            }
        }
    }

    @Test
    fun `펌프 스레드를 세우는 배치만 읽기 RPC 의 펌프를 끈다`() {
        // 펌프 스레드가 없으면 RPC 의 펌프가 곧 구동이라 끄면 아무도 플릿에 안 묻는다. 있으면 끄고 펌프가 적은 것으로 답한다(§15.268).
        OrbitLauncher(options().copy(pumpMillis = 1)).start().getOrThrow().use { running ->
            assertEquals(false, running.host.pumpOnRead, "펌프 스레드가 있는데 읽기 RPC 가 플릿을 기다린다")
        }
        OrbitLauncher(options()).start().getOrThrow().use { running ->
            assertEquals(true, running.host.pumpOnRead, "펌프 스레드가 없는데 읽기 RPC 가 펌프를 건너뛴다")
        }
    }

    @Test
    fun `런처의 펌프 스레드는 호스트의 잠금을 지난다`() {
        // 위 시험은 접수끼리의 경합만 본다. 펌프 스레드가 잠금을 비켜 가도(기체의 펌프를 직접 부르면) 접수는 여전히
        // 서로 줄을 서서 그 시험이 초록이다. 그래서 펌프 스레드 쪽을 따로 묻는다. 도는 태스크가 있으면 펌프마다
        // `GET /runs/` 가 나가므로 그 수가 곧 펌프의 발자국이다. 펌프의 읽기는 호스트 잠금 밖이지만 읽을 자리를 정하고
        // 읽은 것을 적는 일은 잠금 아래라(§15.268), 잠금을 쥔 동안에는 이미 들어선 읽기 하나만 끝나고 펌프가 선다.
        OrbitLauncher(options().copy(pumpMillis = 1)).start().getOrThrow().use { running ->
            val channel: ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", running.port).usePlaintext().build()
            try {
                val handle = PicassoClient(channel, "line-controller").start(
                    "spot-a", "t-1", revision = 1, skillType = "navigate_to",
                    parameters = listOf(ParameterValue.newBuilder().setKey("location").setStringValue("DOCK-3").build()),
                )
                assertTrue(handle.hasHandle(), handle.rejection.toString())
                fun polls() = synchronized(received) { received.count { it == "GET /api/v0/runs/" } }

                // 펌프 스레드가 실제로 돌고 있는지부터 — 안 돌면 아래 대조가 무엇도 증명하지 않는다.
                val deadline = System.nanoTime() + 5_000_000_000L
                while (polls() < 3 && System.nanoTime() < deadline) Thread.sleep(5)
                assertTrue(polls() >= 3, "펌프 스레드가 안 돈다: ${polls()}")

                val (before, after) = running.host.exclusive {
                    val before = polls()
                    Thread.sleep(200)
                    before to polls()
                }
                assertTrue(after - before <= 1, "잠금을 쥔 200ms 동안 펌프가 플릿에 ${after - before} 번 물었다 — 잠금을 비켜 간다")
            } finally {
                channel.shutdownNow()
            }
        }
    }

    @Test
    fun `다시 발견이 플릿에 새로 나타난 기체를 세워 실 포트의 계약 뒤에 더한다`() {
        val sweeps = mutableListOf<List<String>>()
        val sink = RobotDiscovery { _, _, robots ->
            sweeps += robots.map { it.robotId }
            DiscoveryAck(robots.map { it.robotId }, emptyMap())
        }
        val published = RecordingPublisher()

        OrbitLauncher(options()).start(publisher = published, discovery = sink).getOrThrow().use { running ->
            assertEquals(listOf("spot-a", "spot-b"), running.host.robots.keys.sorted())

            // 플릿에 기체가 하나 는다.
            routes["/api/v0/robots/"] = 200 to """[
                {"hostname":"spot-a.line-a","nickname":"spot-a","robotIndex":0,"username":"admin"},
                {"hostname":"spot-b.line-a","nickname":"spot-b","robotIndex":1,"username":"admin"},
                {"hostname":"spot-c.line-a","nickname":"spot-c","robotIndex":2,"username":"admin"}]"""

            // 새로 나타난 기체만 세운다. 발견은 기동 때와 같은 적재 문으로 다시 올라간다.
            assertEquals(listOf("spot-c"), running.rediscover().getOrThrow())
            assertEquals(listOf("spot-a", "spot-b", "spot-c"), sweeps.last())
            assertEquals(listOf("spot-a", "spot-b", "spot-c"), running.host.robots.keys.sorted())
            // 더한 기체도 살아 있다고 알린다. 기체마다 하나씩.
            assertEquals(3, published.publications.count { it.retained })

            val channel: ManagedChannel = ManagedChannelBuilder.forAddress("127.0.0.1", running.port).usePlaintext().build()
            try {
                // 같은 포트에서 새 기체를 지목할 수 있다.
                assertTrue(PicassoClient(channel, "line-controller").capabilities("spot-c").skillsList.isNotEmpty())
            } finally {
                channel.shutdownNow()
            }

            // 이미 든 기체는 다시 세우지 않는다.
            assertEquals(emptyList(), running.rediscover().getOrThrow())
            assertEquals(3, published.publications.count { it.retained })
        }
    }

    @Test
    fun `다시 발견이 플릿에 못 물으면 실패로 내고 이미 든 기체는 그대로 둔다`() {
        OrbitLauncher(options()).start().getOrThrow().use { running ->
            routes["/api/v0/robots/"] = 500 to """{"error":"down"}"""

            assertTrue(running.rediscover().isFailure)
            assertEquals(listOf("spot-a", "spot-b"), running.host.robots.keys.sorted())
        }
    }

    @Test
    fun `플릿에 못 붙으면 포트를 열지 않는다`() {
        routes["/api/v0/api_token/authenticate/"] = 401 to """{"error":"bad token"}"""

        val failure = OrbitLauncher(options()).start()
        // **붙지도 못하면서 포트를 열면 소비자가 아무것도 못 하는 기체를 본다.**
        assertTrue(failure.isFailure)
        assertTrue("못 붙었다" in (failure.exceptionOrNull()?.message ?: ""), failure.exceptionOrNull()?.message ?: "")
    }

    @Test
    fun `플릿에 기체가 없으면 세울 것이 없다고 한다`() {
        routes["/api/v0/robots/"] = 200 to "[]"

        val failure = OrbitLauncher(options()).start()
        assertTrue(failure.isFailure)
        // 빈 채로 포트만 여는 것은 "기체가 있는데 안 붙는다" 와 화면에서 구별되지 않는다.
        assertTrue("하나도 없다" in (failure.exceptionOrNull()?.message ?: ""), failure.exceptionOrNull()?.message ?: "")
    }

    private companion object {
        val PROFILE: Path = Path.of("..", "profile", "fixtures", "minimal.json").normalize()
    }
}

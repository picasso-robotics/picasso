package dev.picasso.adapter.host

import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.SkillServiceGrpc
import dev.picasso.contracts.v1.TaskServiceGrpc
import dev.picasso.contracts.wire.ControllerFence
import dev.picasso.uplink.report.HandshakeReporter
import io.grpc.BindableService
import io.grpc.ForwardingServerCallListener
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.ServerInterceptors
import io.grpc.ServerServiceDefinition
import java.util.concurrent.ConcurrentHashMap

/**
 * 어댑터 호스트 — [HostedRobot] 하나를 계약의 gRPC 서비스 셋(`SkillService`·`TaskService`·`EventService`) 뒤에 세운다.
 *
 * 미믹의 `MimicServer` 와 같은 자리다. 소비자는 엔드포인트만 바꿔 미믹과 실물 어댑터를 오간다 — 그것이 이 저장소의
 * 주장이고 이 클래스가 그 주장의 실물 쪽 절반이다.
 *
 * 발행(상태·이벤트·연결)은 [HostedRobot] 이 자기 `Publisher` 로 내고, 레지스트리 적재는 그 발행자를 `uplink` 의
 * `IngestBridge` 로 감싼 쪽(조립하는 쪽)이 한다 — 미믹의 CLI 가 `RegistryLink.wrap` 으로 하는 것과 같다.
 *
 * **여기 없는 것** — 레지스트리 폴링(§10.3), 운영 배치에서 [pump] 를 부르는 스케줄러(모든 RPC 가 펌프를 지나므로
 * 소비자가 폴링하면 그것이 곧 구동이다 — 스케줄러는 런처와 함께 온다).
 *
 * ## 잠금 하나
 *
 * [HostedRobot] 의 기록(태스크 장부·도는 태스크·재생 버퍼·결함 표)과 `WatchTask` 의 열린 스트림 표에는 잠금이 없다.
 * 시험은 in-process 채널의 `directExecutor` 로 한 스레드에서 돌아 문제가 안 보였다. 실 포트에서는 RPC 가 gRPC
 * 스레드마다 오고 스케줄러는 또 다른 스레드에서 [pump] 를 부르므로, 둘이 같은 기록을 동시에 만진다 — 동시에 온
 * `StartTask` 둘이 다 «도는 태스크 없음» 을 보고 지나가 로봇에 명령이 두 번 나갔고, 재생 버퍼를 읽는 RPC 가
 * `ConcurrentModificationException` 으로 `UNKNOWN` 을 냈다(`AdapterHostConcurrencyTest`). 그래서 모든 RPC 와 [pump]·기동·종료
 * 발행을 이 잠금 하나로 줄 세운다. 미믹의 `MimicServer` 가 같은 이유로 같은 모양을 먼저 가졌다.
 *
 * **호스트 단위다, 기체 단위가 아니다.** 플릿 뒤 기체들은 남쪽 링크 하나를 같이 쓰므로(§15.106) 기체마다 잠그면 그
 * 링크가 다시 동시에 불린다. 대가는 한 기체의 느린 벤더 호출이 같은 프로세스의 다른 기체를 기다리게 하는 것이다.
 * ADR 51 결정 5 의 발신자 세대 대조가 이 층에 들어오면, 대조와 벤더 송신이 한 덩어리로 도는 바닥은 이제 링크 잠금이다(§15.269).
 *
 * ## 링크 잠금 — 펌프의 읽기는 이 잠금 밖이다 (§15.268)
 *
 * 어댑터 호출은 기체들이 같이 쓰는 링크 잠금 하나 아래로 간다([HostedRobot.adapter]). [pump] 는 무엇을 읽을지와 읽은 것을
 * 적는 일만 호스트 잠금 아래에서 하고, 어댑터에 묻는 동안은 링크 잠금만 쥔다. 그래서 플릿이 답하지 않아도 그동안 링크를
 * 안 쓰는 RPC(스냅샷·재생·능력·협상·태스크 관찰)는 기다리지 않는다 — [pumpOnRead] 가 거짓일 때다. 잠금 순서는 언제나 호스트
 * 다음 링크다.
 *
 * ## 명령도 이 잠금 밖에서 보낸다 (§15.269)
 *
 * 명령 RPC(`StartTask`·`PauseTask`·`ResumeTask`·`CancelTask`·`RetryTask`)와 어댑터에 묻는 `GetKnownSiteNames` 는 본문을 이
 * 잠금 아래로 줄 세우지 않는다([RELEASED]). 기체가 장부를 보고 적을 때만 이 잠금을 쥐고([HostedRobot.recorded]), 어댑터에
 * 보내는 동안은 링크 잠금만 쥔다. 보내는 중인 명령은 기체마다 하나이고 그 자리가 배타 검사다 — 같은 접수는 합류하고, 다른 접수는
 * 거부되고, 조작은 기다린다([HostedRobot.start]·[HostedRobot.manipulate]). 그래서 플릿이 명령에 답하지 않는 동안에도 링크를 안
 * 쓰는 RPC 는 기다리지 않는다. 같은 링크를 쓰는 것(다른 기체의 명령, 펌프의 읽기)은 여전히 그 명령을 기다린다.
 *
 * ## 생존 보고도 발행할 때 어댑터에 묻지 않는다 (§15.271)
 *
 * 상태 발행은 호스트 잠금 아래에서 오고, 그 발행이 싣는 생존 보고(로봇 소프트웨어, 사이트 이름 요약)는 어댑터에서 온다. 펌프가
 * 상태 발행 차례에 그것을 링크 잠금 아래에서 함께 읽어 두고([HostedRobot.liveness]), 발행은 읽어 둔 것을 싣는다.
 */
class AdapterHost(
    /**
     * 이 프로세스가 드는 기체들. **플릿 뒤에는 여럿이다**(§15.106) — 계약이 요청 헤더의 `robot_id` 로 지목하며,
     * 미믹이 한 프로세스에 여럿을 호스팅하는 것과 같은 편의다(ADR 21: PoC 의 편의이지 아키텍처 주장이 아니다).
     * 기동 뒤에 늘 수 있다([admit]).
     */
    robots: Map<String, HostedRobot>,
    builder: ServerBuilder<*>,
    /** §5.4 의 핸드셰이크 결과 보고. 붙이지 않으면 아무 데도 안 나간다 — 미믹의 `MimicServer` 와 같은 자리다. */
    reporter: HandshakeReporter = HandshakeReporter.NONE,
    /**
     * 링크를 안 쓰는 RPC 도 펌프를 먼저 지나는가. 참이면 모든 RPC 가 펌프를 지나고(배치에 펌프 스레드가 없을 때 그것이
     * 구동이다), 거짓이면 그 RPC 는 펌프가 마지막으로 적은 것으로 답하고 링크를 기다리지 않는다. 명령은 언제나 지난다.
     */
    val pumpOnRead: Boolean = true,
    /**
     * 제어자 세대 대조(ADR 54). **기본은 대조하지 않는다**(`UNFENCED`) — 오늘 거동이고 응답 바닥값은 0 이다. 대조하려면 `FENCED` 와 저장소를
     * 준다 — 재시작을 넘으려면 [FileFenceStore], 메모리 저장은 인수 확인이 `VOLATILE` 로 답한다(한계 대장 «§15.330 · 휘발 바닥값»).
     */
    val fence: ControllerFence = ControllerFence(),
    /** 인수 확인이 앞서 통과한 명령을 기다리는 한도. 넘기면 `DURABLE_PENDING` 이다. */
    fenceWait: java.time.Duration = java.time.Duration.ofSeconds(5),
) {

    /** 기체 하나짜리 배치 — 직결 어댑터와 시험이 쓴다. */
    constructor(
        robot: HostedRobot,
        builder: ServerBuilder<*>,
        reporter: HandshakeReporter = HandshakeReporter.NONE,
        pumpOnRead: Boolean = true,
        fence: ControllerFence = ControllerFence(),
        fenceWait: java.time.Duration = java.time.Duration.ofSeconds(5),
    ) : this(mapOf(robot.robotId to robot), builder, reporter, pumpOnRead, fence, fenceWait)

    /**
     * 이 호스트가 드는 기체들. **동시 맵이다** — 기동 뒤에 [admit] 이 더하고, 잠금 밖에서 도는 명령 RPC 의 라우팅이 같은 맵을
     * 읽는다(§15.269). 빼는 길은 없다.
     */
    val robots: Map<String, HostedRobot> = ConcurrentHashMap(robots)

    /** 기체 하나를 든 호스트의 그 기체. 여럿이면 터진다 — 부르는 쪽이 자기가 무엇을 세웠는지 알아야 한다. */
    val robot: HostedRobot get() = robots.values.single()

    /** 이 호스트의 기록 전체를 지키는 잠금. 재진입된다 — in-process 시험이 펌프를 돌리다 같은 스레드에서 RPC 를 받는다. */
    private val lock = Any()

    /** 이 잠금 아래에서 [block] 을 돈다. 같은 프로세스에서 [robots] 의 기록을 읽거나 움직이는 쪽이 쓴다. */
    fun <T> exclusive(block: () -> T): T = synchronized(lock, block)

    /** 기체들이 같이 쓰는 링크 잠금. 플릿 뒤에서는 링크가 하나다(§15.106). [admit] 으로 들어온 기체도 이것을 쓴다. */
    private val link = Any()

    /** [start] 가 지났는가. 지난 뒤에 들어온 기체는 [admit] 이 `ONLINE` 을 낸다. */
    @Volatile private var started = false

    init {
        this.robots.values.forEach {
            it.southbound = link
            it.host = lock
            it.floor = { fence.floor(it.robotId) }
        }
    }

    /** [service] 의 모든 호출을 [lock] 아래로 줄 세운다. */
    private fun serialized(service: BindableService): ServerServiceDefinition =
        ServerInterceptors.intercept(service, Serialized(lock, RELEASED))

    private val tasks = HostTaskService(this.robots, pumpOnRead, fence)

    private val server: Server = builder
        .addService(serialized(HostSkillService(this.robots, reporter, pumpOnRead, fence)))
        .addService(serialized(tasks))
        .addService(serialized(HostEventService(this.robots, pumpOnRead, fence, fenceWait)))
        .build()

    val port: Int get() = server.port

    /**
     * 포트를 열고 **그 다음에** `ONLINE` 을 발행한다(§10.2 의 순서 — 붙을 수 없는 기체를 살아 있다고 알리지 않는다). 그 앞에
     * 생존 보고의 내용을 호스트 잠금 밖에서 읽어 둔다([HostedRobot.primeLiveness]) — 발행은 어댑터에 묻지 않는다(§15.271).
     */
    fun start(): AdapterHost = apply {
        robots.values.forEach { it.primeLiveness() }
        server.start()
        // 포트가 열린 뒤라 RPC 가 들어올 수 있다. 온라인 발행도 같은 잠금 아래에 둔다 — 순번 축을 RPC 와 같이 쓴다.
        exclusive {
            robots.values.forEach { it.announceOnline() }
            started = true
        }
    }

    /**
     * 기동 뒤에 기체 하나를 더 든다 — 플릿에 기체가 늘었을 때 런처의 다시 발견이 부른다(§15.292).
     *
     * 이미 든 `robot_id` 면 아무것도 안 하고 거짓을 낸다. 생존 보고의 내용은 [start] 처럼 호스트 잠금 밖에서 읽어 두고, 맵에
     * 넣는 것과 `ONLINE` 은 같은 잠금 아래에서 한다 — 맵에 들어가 RPC 가 지목할 수 있게 된 뒤에 살아 있다고 알린다(§10.2 의 순서).
     * [start] 앞이면 맵에만 넣고 [start] 가 알린다.
     */
    fun admit(robot: HostedRobot): Boolean {
        if (robots.containsKey(robot.robotId)) return false
        robot.southbound = link
        robot.host = lock
        robot.floor = { fence.floor(robot.robotId) }
        robot.primeLiveness()
        return exclusive {
            val map = robots as ConcurrentHashMap<String, HostedRobot>
            if (map.putIfAbsent(robot.robotId, robot) != null) return@exclusive false
            tasks.attach(robot)
            if (started) robot.announceOnline()
            true
        }
    }

    /**
     * 어댑터에 묻고 달라진 것을 적어 열린 스트림에 민다. 시험과 스케줄러가 부른다.
     *
     * 기체마다 세 단계다 — 읽을 자리를 호스트 잠금 아래에서 정하고, 어댑터에 링크 잠금 아래에서 묻고, 읽은 것을 다시 호스트
     * 잠금 아래에서 적는다. 묻는 동안 호스트 잠금을 쥐지 않는다.
     */
    fun pump() = robots.values.forEach { robot ->
        val plan = exclusive { robot.plan() }
        val reading = robot.read(plan)
        exclusive { robot.apply(reading) }
    }

    /** 서버가 내려갈 때까지 기다린다. 배치의 `main` 이 프로세스를 살려 두는 데 쓴다. */
    fun awaitTermination() = server.awaitTermination()

    /** `OFFLINE` 을 남기고 닫는다 — 정상 종료는 Last Will 이 안 나가므로 여기서 말해야 한다(§4.7). */
    fun shutdown() {
        exclusive { robots.values.forEach { it.goOffline() } }
        server.shutdownNow()
    }

    /**
     * 호출의 시작과 모든 콜백(요청·반쯤 닫힘·취소·완료·준비)을 한 잠금 아래에서 돈다. 단항 RPC 의 본문은 반쯤 닫힘에서 돈다.
     * [released] 의 메서드는 그 본문만 잠금 밖에서 돈다 — 본문이 기록을 만지는 자리를 스스로 잠근다.
     */
    private class Serialized(private val lock: Any, private val released: Set<String>) : ServerInterceptor {
        override fun <ReqT : Any, RespT : Any> interceptCall(
            call: ServerCall<ReqT, RespT>,
            headers: Metadata,
            next: ServerCallHandler<ReqT, RespT>,
        ): ServerCall.Listener<ReqT> {
            val delegate = synchronized(lock) { next.startCall(call, headers) }
            val free = call.methodDescriptor.fullMethodName in released
            return object : ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(delegate) {
                override fun onMessage(message: ReqT) = synchronized(lock) { super.onMessage(message) }
                override fun onHalfClose() = if (free) super.onHalfClose() else synchronized(lock) { super.onHalfClose() }
                override fun onCancel() = synchronized(lock) { super.onCancel() }
                override fun onComplete() = synchronized(lock) { super.onComplete() }
                override fun onReady() = synchronized(lock) { super.onReady() }
            }
        }
    }

    private companion object {
        /**
         * 본문을 호스트 잠금 밖에서 도는 메서드 — 어댑터에 보내거나 묻는 것이다(§15.269). `GetSnapshot` 도 여기 있다 — 인수 확인이 앞서 통과한
         * 명령을 기다리는 동안 그 명령이 장부에 적을 수 있어야 한다(ADR 54). 본문은 기록을 읽는 자리를 스스로 잠근다.
         */
        val RELEASED: Set<String> = setOf(
            TaskServiceGrpc.getStartTaskMethod(),
            TaskServiceGrpc.getPauseTaskMethod(),
            TaskServiceGrpc.getResumeTaskMethod(),
            TaskServiceGrpc.getCancelTaskMethod(),
            TaskServiceGrpc.getRetryTaskMethod(),
            SkillServiceGrpc.getGetKnownSiteNamesMethod(),
            EventServiceGrpc.getGetSnapshotMethod(),
        ).map { it.fullMethodName }.toSet()
    }
}

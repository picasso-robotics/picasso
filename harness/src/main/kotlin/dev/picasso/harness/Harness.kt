package dev.picasso.harness

import dev.picasso.uplink.report.TaskObservations
import dev.picasso.uplink.report.IngestBridge
import com.google.protobuf.Descriptors
import com.google.protobuf.Message
import dev.picasso.client.PicassoClient
import dev.picasso.mimic.RobotInstance
import dev.picasso.mimic.engine.VirtualClock
import dev.picasso.mimic.profile.FileProfileSource
import dev.picasso.mimic.transport.MimicServer
import dev.picasso.mimic.transport.RobotRegistry
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * `mimic`을 **in-process로** 세우고 `client`로 두드린다(§10.2의 직접 실행 모드).
 *
 * **프로파일을 경로로 받아 [FileProfileSource]를 지난다.** 이미 파싱된 문서를
 * 받으면 §10.2의 기동 순서(로드 → 스키마 검증 → 기동 거부)를 하네스가 건너뛰게
 * 되고, 스키마를 어긴 프로파일 위에 시험이 서게 된다.
 *
 * **시계를 직접 쥔다.** §10.5의 제어 채널은 별도 프로세스로 띄울 때 필요하고
 * 그것은 Chunk 6이다. 다만 전진은 [MimicServer.advance] 하나를 지난다 —
 * 제어 채널의 `AdvanceClock`도 같은 함수로 내려온다.
 */
class Harness(
    robots: Map<String, Path>,
    schema: Path = Path.of("..", "profile", "schema", "capability-profile.schema.json").normalize(),
    seed: Long = 0,
    start: Instant = Instant.parse("2026-09-06T00:00:00Z"),
    /**
     * §10.3의 폴링이 당기는 곳. **기본은 없음이다** — 레지스트리가 안 떠
     * 있어도 하네스는 파일 모드로 돈다(§3.2의 "없을 때").
     */
    registrySource: dev.picasso.mimic.RegistrySource = dev.picasso.mimic.RegistrySource.NONE,
    /**
     * §3.2의 `registry ⇠ 브로커` 구독을 대신하는 적재 지점([IngestBridge]).
     * **기본은 없음이다** — 붙이지 않으면 발행은 [publisher]에만 쌓인다.
     */
    taskSink: TaskObservations? = null,
    /**
     * §5.4의 핸드셰이크 결과 보고. **기본은 없음이다** — 레지스트리가 안 떠
     * 있어도 하네스는 돈다(§3.2의 "없을 때").
     */
    reporter: dev.picasso.uplink.report.HandshakeReporter =
        dev.picasso.uplink.report.HandshakeReporter.NONE,
    /** 제어자 세대 대조(ADR 54). **기본은 대조하지 않는다** — 오늘 거동. */
    fence: dev.picasso.contracts.wire.ControllerFence = dev.picasso.contracts.wire.ControllerFence(),
) : AutoCloseable {

    val clock = VirtualClock(start)

    /** 오간 요청을 순서대로 기록한다. 완료 기준 1의 증거다. */
    val recorder = RequestRecorder()

    /** 발행을 받는 구독자 노릇. 브로커는 §15.30의 이유로 붙이지 않는다. */
    val publisher = dev.picasso.uplink.RecordingPublisher()

    private val source = FileProfileSource(schema)

    /**
     * 기체가 실제로 미는 곳. 적재 지점이 있으면 [publisher]를 감싼다.
     *
     * **감싸되 가로채지 않는다** — [publisher]는 그대로 다 받는다.
     */
    private val outbound: dev.picasso.uplink.Publisher =
        taskSink?.let { IngestBridge(publisher, it) } ?: publisher

    val registry = RobotRegistry(
        robots.map { (id, path) ->
            RobotInstance(id, source.load(path), clock, seed, outbound, site = "line-a")
        },
        registrySource,
        fence,
    )

    private val name: String = InProcessServerBuilder.generateName()

    private val server = MimicServer(
        registry,
        InProcessServerBuilder.forName(name).directExecutor().intercept(recorder),
        reporter,
    ).start()

    private val channel: ManagedChannel =
        InProcessChannelBuilder.forName(name).directExecutor().build()

    /**
     * §10.5의 제어 채널. **운영에서는 별도 포트·루프백이고 여기서는
     * in-process다** — 직접 실행 모드에서는 프로세스가 하나뿐이다.
     */
    private val control = dev.picasso.mimic.control.ControlServer(
        registry, server, InProcessServerBuilder.forName("$name-control").directExecutor(),
    ).start()

    private val controlChannel: ManagedChannel =
        InProcessChannelBuilder.forName("$name-control").directExecutor().build()

    val oracle: dev.picasso.mimic.control.v1.ControlServiceGrpc.ControlServiceBlockingStub =
        dev.picasso.mimic.control.v1.ControlServiceGrpc.newBlockingStub(controlChannel)

    /** 계약 표면의 이벤트를 읽는다. */
    val events: dev.picasso.contracts.v1.EventServiceGrpc.EventServiceBlockingStub =
        dev.picasso.contracts.v1.EventServiceGrpc.newBlockingStub(channel)

    /** [deadlineSeconds] 는 스트림에도 걸린다. 짧게 주면 기한으로 닫힌 스트림을 만들 수 있다. */
    fun client(
        clientId: String = "line-controller",
        identityOverride: String? = null,
        deadlineSeconds: Long = 10,
        controllerEpoch: () -> Long = { 0L },
    ) = PicassoClient(channel, clientId, identityOverride, deadlineSeconds, controllerEpoch)

    /** 시간을 흘리고 그것이 만든 전이를 열린 스트림까지 민다. */
    fun advance(duration: Duration) = server.advance(duration)

    override fun close() {
        channel.shutdownNow()
        controlChannel.shutdownNow()
        control.shutdown()
        server.shutdown()
    }
}

/**
 * 서버가 받은 요청 메시지를 순서대로 모은다.
 *
 * **완료 기준 1이 "동일 코드 경로"라고 한 것의 문자 그대로의 뜻이다.** 소스에
 * 기종 이름이 없는지 훑는 것으로는 `capability.skillsList.size == 3` 같은
 * 능력 기반 분기를 못 본다. 두 기체에 나간 요청이 **바이트 동일**하면
 * 그런 분기가 있을 수 없다.
 */
class RequestRecorder : ServerInterceptor {

    private val received = mutableListOf<Pair<String, Message>>()

    val requests: List<Pair<String, Message>> get() = received.toList()

    fun clear() = received.clear()

    override fun <ReqT : Any, RespT : Any> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val method = call.methodDescriptor.fullMethodName
        return object : io.grpc.ForwardingServerCallListener
        .SimpleForwardingServerCallListener<ReqT>(next.startCall(call, headers)) {
            override fun onMessage(message: ReqT) {
                if (message is Message) received += method to message
                super.onMessage(message)
            }
        }
    }

    companion object {

        /**
         * 기체를 지목하는 필드만 지운다. 나머지가 다르면 그것은 분기다.
         *
         * 이름으로 찾아 지운다 — 메시지 종류마다 손으로 쓰면 새 RPC가 생길 때
         * 조용히 빠진다.
         */
        fun normalize(message: Message): Message {
            val builder = message.toBuilder()
            message.descriptorForType.fields.forEach { field ->
                when {
                    field.name == "robot_id" -> builder.clearField(field)
                    field.javaType == Descriptors.FieldDescriptor.JavaType.MESSAGE &&
                        !field.isRepeated && message.hasField(field) ->
                        builder.setField(field, normalize(message.getField(field) as Message))
                }
            }
            return builder.build()
        }
    }
}

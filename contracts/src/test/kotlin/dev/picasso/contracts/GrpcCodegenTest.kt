package dev.picasso.contracts

import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.GetCapabilitiesRequest
import dev.picasso.contracts.v1.GetCapabilitiesResponse
import dev.picasso.contracts.v1.SkillServiceGrpc
import dev.picasso.contracts.v1.TaskServiceGrpc
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 서비스 스텁이 실제로 도는지 본다.
 *
 * **"스텁 클래스가 존재한다"만 보는 시험은 버전 충돌을 못 잡는다.** grpc-protobuf
 * 1.68.1이 protobuf-java 3.25.x를 끌고 오는데, protoc 4.28.3이 생성한 코드는
 * 런타임 4.28 이상을 요구한다(`RuntimeVersion.validateProtobufGencodeVersion`).
 * 낮은 쪽이 이기면 컴파일은 멀쩡하고 **런타임에** 터진다. 그래서 메시지를
 * 실제로 직렬화해 왕복시킨다.
 */
class GrpcCodegenTest {

    @Test
    fun `생성된 스텁으로 메시지가 왕복한다`() {
        val name = InProcessServerBuilder.generateName()
        val server = InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(object : SkillServiceGrpc.SkillServiceImplBase() {
                override fun getCapabilities(
                    request: GetCapabilitiesRequest,
                    observer: StreamObserver<GetCapabilitiesResponse>,
                ) {
                    observer.onNext(
                        GetCapabilitiesResponse.newBuilder()
                            .setCapability(Capability.newBuilder().setVendor(request.robotId))
                            .build(),
                    )
                    observer.onCompleted()
                }
            })
            .build()
            .start()
        val channel = InProcessChannelBuilder.forName(name).directExecutor().build()

        try {
            val response = SkillServiceGrpc.newBlockingStub(channel)
                .getCapabilities(GetCapabilitiesRequest.newBuilder().setRobotId("r1").build())
            assertEquals("r1", response.capability.vendor)
        } finally {
            channel.shutdownNow()
            server.shutdownNow()
        }
    }

    @Test
    fun `계약이 선언한 서비스 셋의 스텁이 전부 생성된다`() {
        // §4.1의 파일 귀속대로 셋이다. 하나라도 빠지면 표면에 구멍이 난다.
        assertEquals("picasso.v1.SkillService", SkillServiceGrpc.getServiceDescriptor().name)
        assertEquals("picasso.v1.TaskService", TaskServiceGrpc.getServiceDescriptor().name)
        assertEquals(
            "picasso.v1.EventService",
            dev.picasso.contracts.v1.EventServiceGrpc.getServiceDescriptor().name,
        )
    }

    @Test
    fun `TaskService의 RPC 여섯이 전부 있다`() {
        // §4.4의 목록. 스텁이 생성되었다는 것만으로는 무엇이 생성됐는지 모른다.
        assertEquals(
            setOf(
                "picasso.v1.TaskService/StartTask",
                "picasso.v1.TaskService/WatchTask",
                "picasso.v1.TaskService/RetryTask",
                "picasso.v1.TaskService/PauseTask",
                "picasso.v1.TaskService/ResumeTask",
                "picasso.v1.TaskService/CancelTask",
            ),
            TaskServiceGrpc.getServiceDescriptor().methods.map { it.fullMethodName }.toSet(),
        )
    }

    @Test
    fun `계약 신원 리소스가 구워진다`() {
        val props = Properties()
        checkNotNull(javaClass.getResourceAsStream("/picasso-contract.properties")) {
            "picasso-contract.properties 가 클래스패스에 없다 — 헤더가 계약 신원을 못 싣는다"
        }.use(props::load)

        assertEquals("0.11.0", props.getProperty("semver"))

        // 다이제스트를 리터럴로 박으면 proto를 고칠 때마다 시험을 고쳐야 하고
        // 그러면 아무도 값을 보지 않게 된다. 모양만 본다.
        assertTrue(
            props.getProperty("digest").orEmpty().matches(Regex("[0-9a-f]{64}")),
            "다이제스트가 SHA-256 16진수가 아니다: ${props.getProperty("digest")}",
        )
    }
}

package dev.picasso.client

import dev.picasso.client.cli.ClientCli
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.GetCapabilitiesRequest
import dev.picasso.contracts.v1.GetCapabilitiesResponse
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.NegotiateRequest
import dev.picasso.contracts.v1.NegotiateResponse
import dev.picasso.contracts.v1.SkillServiceGrpc
import dev.picasso.contracts.wire.ContractIdentity
import dev.picasso.contracts.wire.HeaderColumns
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **여기서는 거동을 증명하지 않는다.**
 *
 * `client → mimic` 은 §3.2가 금지하므로 이 모듈의 시험 서버는 손으로 만든
 * 가짜다. 가짜를 상대로 "거절을 그대로 보고한다"를 단언해 봐야 **가짜가
 * 시킨 대로 돌려준다**는 것만 확인된다 — 계약에 대해서는 아무것도 말하지
 * 않는다. 그래서 여기는 **클라이언트 자신의 책임**만 본다: 헤더를 §5.5대로
 * 만드는가, 요구 집합 파일을 읽는가, 캐시를 지키는가, 인자를 해석하는가.
 *
 * 완료 기준 1·7·13의 거동 주장은 전부 `harness`가 진짜 `mimic`을 상대로 한다.
 */
class PicassoClientTest {

    /** 받은 요청을 기록만 하는 가짜. 판정하지 않는다. */
    private val seen = mutableListOf<MessageHeader>()
    private var epoch = 7L

    private val name: String = InProcessServerBuilder.generateName()

    private val server: Server = InProcessServerBuilder.forName(name).directExecutor()
        .addService(object : SkillServiceGrpc.SkillServiceImplBase() {
            override fun getCapabilities(
                request: GetCapabilitiesRequest,
                observer: StreamObserver<GetCapabilitiesResponse>,
            ) {
                seen += request.header
                observer.onNext(
                    GetCapabilitiesResponse.newBuilder()
                        .setHeader(MessageHeader.newBuilder().setCapabilityEpoch(epoch))
                        .setCapability(Capability.newBuilder().setVendor("fake"))
                        .build(),
                )
                observer.onCompleted()
            }

            override fun negotiate(
                request: NegotiateRequest,
                observer: StreamObserver<NegotiateResponse>,
            ) {
                seen += request.header
                observer.onNext(
                    NegotiateResponse.newBuilder()
                        .setHeader(MessageHeader.newBuilder().setCapabilityEpoch(epoch))
                        .setAccepted(true)
                        // 페이로드의 client_id를 그대로 되비춘다 —
                        // identityOverride가 헤더만 바꾸는지 보려면 필요하다.
                        .build(),
                )
                observer.onCompleted()
            }
        })
        .build().start()

    private val channel: ManagedChannel =
        InProcessChannelBuilder.forName(name).directExecutor().build()

    @AfterTest
    fun close() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    private val requirements = RequirementFixture.minimal()

    @Test
    fun `요청 헤더가 §5-5의 요청 열을 따른다`() {
        // 클라이언트가 헤더를 손으로 만들면 표가 두 벌이 된다.
        PicassoClient(channel, "line-controller").capabilities("r1")

        val header = seen.single()
        // 세대를 안 준 클라이언트는 0 — «싣지 않음» 이다(0.12.0).
        (HeaderColumns.REQUEST - HeaderColumns.ZERO_IS_VALUE).forEach {
            assertTrue(HeaderColumns.isSet(header, it), "$it 가 비었다")
        }
        (HeaderColumns.ALL - HeaderColumns.REQUEST).forEach {
            assertFalse(HeaderColumns.isSet(header, it), "$it 를 요청에 실었다")
        }
        assertEquals(ContractIdentity.semver, header.contractSemver)
        assertEquals("picasso.v1.GetCapabilitiesRequest", header.schemaId)
    }

    @Test
    fun `schema_id가 RPC마다 다르다`() {
        val client = PicassoClient(channel, "line-controller")
        client.capabilities("r1")
        client.negotiate("r1", requirements)
        assertEquals(
            listOf("picasso.v1.GetCapabilitiesRequest", "picasso.v1.NegotiateRequest"),
            seen.map { it.schemaId },
        )
    }

    @Test
    fun `identity-override는 헤더의 client_id만 바꾼다`() {
        // 페이로드까지 바꾸면 어긋나지 않아 IDENTITY_MISMATCH가 안 난다 —
        // 완료 기준 13의 픽스처가 무해해진다.
        PicassoClient(channel, "line-controller", identityOverride = "someone-else")
            .negotiate("r1", requirements)
        assertEquals("someone-else", seen.single().clientId)
    }

    @Test
    fun `두 번째 능력 조회는 RPC를 내지 않는다`() {
        val client = PicassoClient(channel, "line-controller")
        client.capabilities("r1")
        client.capabilities("r1")
        assertEquals(1, client.capabilityRpcCount)
        assertEquals(1, seen.size)
    }

    @Test
    fun `기체마다 따로 캐시한다`() {
        // 하나로 캐시하면 한 프로세스가 여러 기체를 볼 때 조용히 틀린 능력을
        // 믿는다(§10.2).
        val client = PicassoClient(channel, "line-controller")
        client.capabilities("r1")
        client.capabilities("r2")
        assertEquals(2, client.capabilityRpcCount)
    }

    @Test
    fun `세대가 바뀌면 캐시를 버린다`() {
        // §5.5의 ETag. mimic의 세대는 이 청크에서 상수라 끝에서 끝까지는
        // 증명할 수 없다 — 논리만 여기서 본다(완료 기준 14가 나머지다).
        val client = PicassoClient(channel, "line-controller")
        client.capabilities("r1")
        client.observeEpoch("r1", 99)
        client.capabilities("r1")
        assertEquals(2, client.capabilityRpcCount, "세대가 바뀌었는데 캐시를 썼다")
    }

    @Test
    fun `세대가 그대로면 캐시를 지킨다`() {
        // 위 시험만 있으면 **언제나 버리는** 구현이 통과한다.
        val client = PicassoClient(channel, "line-controller")
        client.capabilities("r1")
        client.observeEpoch("r1", epoch)
        client.capabilities("r1")
        assertEquals(1, client.capabilityRpcCount)
    }
}

class ClientCliTest {

    private fun parse(vararg args: String): Pair<ClientCli.Options?, String> {
        val err = StringBuilder()
        return ClientCli().parse(arrayOf(*args), err) to err.toString()
    }

    private val requirements =
        Path.of("..", "profile", "requirements", "minimal.json").normalize().toString()

    @Test
    fun `요구 집합을 파일에서 읽는다`() {
        // §5.4 — 코드가 아니라 설정이다.
        val (options, err) = parse(
            "--target", "localhost:1", "--robot", "r1",
            "--requirements", requirements, "--skill", "navigate_to",
            "--param", "location=dock-3",
        )
        assertTrue(options != null, err)
        assertEquals("line-controller", options.requirements.clientId)
        assertEquals(2, options.requirements.requirements.size)
        assertEquals("dock-3", options.parameters.single().stringValue)
        assertNull(options.identityOverride)
    }

    @Test
    fun `요구 집합이 깨졌으면 붙기 전에 죽는다`() {
        val broken = Files.createTempFile("req", ".json")
        Files.writeString(broken, """{"schema_version": "0.9.0"}""")
        try {
            val (options, err) = parse(
                "--target", "localhost:1", "--robot", "r1",
                "--requirements", broken.toString(), "--skill", "navigate_to",
            )
            assertNull(options)
            assertTrue("요구 집합" in err, err)
        } finally {
            Files.deleteIfExists(broken)
        }
    }

    @Test
    fun `잘못된 인자를 통째로 확인한다`() {
        val cases = mapOf(
            "--target 없음" to arrayOf("--robot", "r1", "--requirements", requirements, "--skill", "s"),
            "--robot 없음" to arrayOf("--target", "t", "--requirements", requirements, "--skill", "s"),
            "--requirements 없음" to arrayOf("--target", "t", "--robot", "r1", "--skill", "s"),
            "--skill 없음" to arrayOf("--target", "t", "--robot", "r1", "--requirements", requirements),
            "--param 문법" to arrayOf(
                "--target", "t", "--robot", "r1", "--requirements", requirements,
                "--skill", "s", "--param", "novalue",
            ),
            "모르는 인자" to arrayOf("--nope", "1"),
            "값 없는 인자" to arrayOf("--target"),
        )
        assertEquals(7, cases.size)

        cases.forEach { (name, args) ->
            val (options, err) = parse(*args)
            assertNull(options, "$name 이 통과했다")
            assertTrue("사용법" in err || "요구 집합" in err, "$name: $err")
        }
    }
}

internal object RequirementFixture {
    fun minimal() = dev.picasso.profile.RequirementSet.parse(
        "minimal",
        Files.readString(Path.of("..", "profile", "requirements", "minimal.json").normalize())
            .replace("\r\n", "\n"),
    )
}

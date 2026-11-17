package dev.picasso.mimic.cli

import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.GetKnownSiteNamesRequest
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.wire.ControllerFence
import dev.picasso.contracts.wire.FenceMode
import dev.picasso.contracts.v1.SkillServiceGrpc
import dev.picasso.contracts.wire.RequestHeaders
import io.grpc.ManagedChannelBuilder
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * CLI 로 띄운 기체에 **기체가 아는 사이트 명칭을 넣을 자리**(picasso-ops P2·S1d 스펙 §6.4).
 *
 * 담는 쪽이 현장의 명칭 티칭을 흉내 내려면 서버가 실제로 들고 있는 인스턴스를 받아야 한다. 복사본을 받으면 값을 넣어도
 * 기체의 답이 안 바뀌고, 그 차이는 registry 의 명칭 상태가 `CONTRADICTED` 로 남는 것으로만 보인다.
 */
class StartedInstanceTest {

    private val schema = Path.of("..", "profile", "schema", "capability-profile.schema.json").normalize().toString()
    private val minimal = Path.of("..", "profile", "fixtures", "minimal.json").normalize().toString()

    @Test
    fun `기동한 기체에 넣은 명칭을 그 기체가 답한다`() {
        val cli = MimicCli()
        try {
            assertEquals(0, cli.run(arrayOf("--robot", "r1=$minimal", "--schema", schema, "--port", "0"), StringBuilder(), StringBuilder()))
            val started = checkNotNull(cli.started)

            started.instance("r1")!!.knownSiteNames = listOf("dock-a", "shelf-3")

            val channel = ManagedChannelBuilder.forAddress("127.0.0.1", started.server.port).usePlaintext().build()
            try {
                val answer = SkillServiceGrpc.newBlockingStub(channel).getKnownSiteNames(
                    GetKnownSiteNamesRequest.newBuilder()
                        .setHeader(RequestHeaders.build("picasso.v1.GetKnownSiteNamesRequest", "r1", "c1"))
                        .build(),
                )
                assertEquals(2, answer.totalCount)
                assertEquals(listOf("dock-a", "shelf-3"), answer.namesList)
            } finally {
                channel.shutdownNow()
            }
            assertNull(started.instance("ghost"))
        } finally {
            cli.started?.server?.shutdown()
        }
    }

    @Test
    fun `CLI 로 띄운 미믹에 대조를 넘기면 그 대조로 바닥값을 올리고 응답에 싣는다`() {
        val started = checkNotNull(
            MimicCli().start(
                mapOf("r1" to Path.of(minimal)), Path.of(schema), 0, virtual = true, seed = 1, err = StringBuilder(),
                fence = ControllerFence(FenceMode.FENCED),
            ),
        )
        try {
            val channel = ManagedChannelBuilder.forAddress("127.0.0.1", started.server.port).usePlaintext().build()
            try {
                val answer = EventServiceGrpc.newBlockingStub(channel).getSnapshot(
                    GetSnapshotRequest.newBuilder()
                        .setHeader(RequestHeaders.build("picasso.v1.GetSnapshotRequest", "r1", "c1", controllerEpoch = 5))
                        .build(),
                )
                assertEquals(5, answer.header.controllerEpoch)
            } finally {
                channel.shutdownNow()
            }
        } finally {
            started.server.shutdown()
        }
    }
}

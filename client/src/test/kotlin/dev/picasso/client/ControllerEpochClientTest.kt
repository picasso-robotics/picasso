package dev.picasso.client

import dev.picasso.contracts.v1.CancelTaskRequest
import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.GetSnapshotResponse
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.StartTaskRequest
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskServiceGrpc
import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 클라이언트가 **제어자 세대를 싣고 붙잡는다**(ADR 54). 판정은 수신자의 일이라 여기서는 클라이언트 자신의 책임만 본다 — 실었나, 처음 본
 * 0 아닌 값을 붙잡나, 공급자가 바뀌면 명령을 선로에 안 내나. 받은 요청을 기록만 하는 가짜가 상대다.
 */
class ControllerEpochClientTest {

    private val seen = mutableListOf<MessageHeader>()
    private val name = InProcessServerBuilder.generateName()

    private val server = InProcessServerBuilder.forName(name).directExecutor()
        .addService(object : TaskServiceGrpc.TaskServiceImplBase() {
            override fun startTask(request: StartTaskRequest, observer: StreamObserver<StartTaskResponse>) {
                seen += request.header
                observer.onNext(StartTaskResponse.newBuilder().setHandle(TaskHandle.newBuilder().setTaskId(request.taskId)).build())
                observer.onCompleted()
            }

            override fun cancelTask(request: CancelTaskRequest, observer: StreamObserver<CancelTaskResponse>) {
                seen += request.header
                observer.onNext(CancelTaskResponse.getDefaultInstance())
                observer.onCompleted()
            }
        })
        .addService(object : EventServiceGrpc.EventServiceImplBase() {
            override fun getSnapshot(request: GetSnapshotRequest, observer: StreamObserver<GetSnapshotResponse>) {
                seen += request.header
                observer.onNext(GetSnapshotResponse.getDefaultInstance())
                observer.onCompleted()
            }
        })
        .build().start()

    private val channel: ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()

    @AfterTest
    fun close() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `명령과 읽기가 공급자의 세대를 싣는다 — 공급자가 없으면 0 이다`() {
        PicassoClient(channel, "c", controllerEpoch = { 7 }).let {
            it.start("r1", "t-1", 1, "pick_place", emptyList())
            it.snapshot("r1")
            assertEquals(7L, it.epoch)
        }
        PicassoClient(channel, "c").start("r1", "t-2", 1, "pick_place", emptyList())
        assertEquals(listOf(7L, 7L, 0L), seen.map { it.controllerEpoch })
    }

    @Test
    fun `처음 본 0 아닌 세대를 붙잡고 공급자가 바뀌면 명령을 보내지 않는다`() {
        var supplied = 0L
        val client = PicassoClient(channel, "c", controllerEpoch = { supplied })
        client.snapshot("r1")
        supplied = 3
        client.start("r1", "t-1", 1, "pick_place", emptyList())
        supplied = 4
        val changed = assertFailsWith<ControllerEpochChanged> { client.start("r1", "t-2", 1, "pick_place", emptyList()) }
        assertEquals(3L, changed.pinned)
        assertEquals(4L, changed.supplied)
        assertFailsWith<ControllerEpochChanged> { client.cancel("r1", TaskHandle.newBuilder().setTaskId("t-1").build()) }
        // 읽기는 붙잡은 값을 싣고 그대로 간다 — 관측은 계속한다.
        client.snapshot("r1")
        assertEquals(listOf(0L, 3L, 3L), seen.map { it.controllerEpoch }, "세대가 바뀐 명령이 선로에 나갔거나 읽기가 바뀐 값을 실었다")
        assertEquals(3L, client.epoch)
    }
}

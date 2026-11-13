package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.EventServiceGrpc
import dev.picasso.contracts.v1.GetSnapshotRequest
import dev.picasso.contracts.v1.GetSnapshotResponse
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.ReplayEventsRequest
import dev.picasso.contracts.v1.ReplayEventsResponse
import io.grpc.stub.StreamObserver

/**
 * §4.4의 `GetSnapshot`·`ReplayEvents`. A-2의 재구성이 이 둘 위에 선다.
 */
class EventServiceImpl(
    private val registry: RobotRegistry,
) : EventServiceGrpc.EventServiceImplBase() {

    override fun getSnapshot(
        request: GetSnapshotRequest,
        observer: StreamObserver<GetSnapshotResponse>,
    ) = reply(observer) {
        val hosted = registry.require(request.header)
        val events = hosted.instance.events

        GetSnapshotResponse.newBuilder()
            .setHeader(hosted.headers.forResponse(GetSnapshotResponse.getDescriptor()))
            // **다음에 올 번호다.** "대응하는 번호"로 두면 0이 "0번까지
            // 반영했다"와 "아직 아무 이벤트도 없다" 둘을 뜻한다(§4.8이
            // sequence를 0부터라고 못박은 이상 다른 방법이 없다).
            .setSequence(events.nextSequence)
            .addAllSkills(events.skillSnapshots())
            .addAllTasks(events.taskSnapshots())
            // §4.6 — 활성 결함만. 수명이 지난 것은 조회 시점에 사라진다.
            .addAllFaults(hosted.instance.faults.active())
            // **연결 상태도 현재값이다.** 계약이 이 자리를 두었는데 채우지 않아 소비자가 언제나 UNSPECIFIED 를 봤다 —
            // 미들웨어 층이 단절 중 결과를 미확정으로 두려다 발견했다(§15.95). MQTT connection 스트림과 같은 값이다.
            .setConnectionState(events.connectionState)
            // **손도 현재값이다**(0.11.0). 접수 전 사전 조건이 보는 그 손이다 — 도는 태스크가 없어도 앞 태스크가 든 채 끝났으면 든 채다.
            .setHold(hosted.instance.tasks.hold())
            .build()
    }

    /**
     * §4.8의 재생 버퍼를 요청하는 **유일한** 표면이다.
     *
     * 요청한 `from_sequence`가 버퍼를 벗어났으면 `SEQUENCE_EVICTED`로 답하고
     * 소비자는 `GetSnapshot`부터 다시 세운다.
     */
    override fun replayEvents(
        request: ReplayEventsRequest,
        observer: StreamObserver<ReplayEventsResponse>,
    ) {
        val hosted = try {
            registry.require(request.header)
        } catch (e: io.grpc.StatusRuntimeException) {
            observer.onError(e); return
        }

        val buffered = hosted.instance.events.buffered
        val oldest = buffered.firstOrNull()?.header?.sequence

        // **실제로 버린 것이 있어야 축출이다.** `sequence` 축은 기체 단위
        // 하나이고 `state`·`connection`이 그것을 함께 쓰므로(§5.5의 발행
        // 열), "버퍼의 첫 항목보다 앞"은 축출과 같은 말이 아니다 — 그
        // 번호를 다른 스트림이 썼을 수 있다. 그렇게 판정하면 아무것도 안
        // 잃은 소비자에게 스냅샷부터 다시 세우라고 시킨다.
        // **버퍼가 비어 있을 수 있다.** 세션 재발급이 통째로 비우고 가므로(§10.6) 거절
        // 문구에서 버퍼의 마지막을 집으면 그 자리에서 터진다 — 소비자는 거절이 아니라
        // 정체를 본다.
        val evicted = hosted.instance.events.evictedUpTo
        // **아직 나지 않은 번호도 축출로 답한다**(§15.288). 그런 커서는 이 프로세스가 준 것이
        // 아니라 다시 뜨기 전의 것이다. 세션이 되풀이되면(같은 밀리초 재기동, 가상 시계) 소비자가
        // 재시작을 못 보는데, 빈 응답을 주면 그 커서가 새 세션의 앞 이벤트를 조용히 건너뛴다.
        val next = hosted.instance.events.nextSequence
        val ahead = request.fromSequence > next
        if (ahead || (evicted != null && request.fromSequence <= evicted)) {
            observer.onNext(
                ReplayEventsResponse.newBuilder()
                    .setHeader(hosted.headers.forResponse(ReplayEventsResponse.getDescriptor()))
                    .setRejection(
                        Rejection.newBuilder()
                            .setCode(RejectionCode.REJECTION_CODE_SEQUENCE_EVICTED)
                            .setDetail(
                                if (ahead) {
                                    "아직 나지 않은 번호다: 요청=${request.fromSequence}, 다음=$next " +
                                        "— 발신자가 다시 떴을 수 있다. GetSnapshot부터 다시 세워라"
                                } else {
                                    "재생 버퍼를 벗어났다: 요청=${request.fromSequence}, " +
                                        "버퍼=[$oldest, ${buffered.lastOrNull()?.header?.sequence}] " +
                                        "— GetSnapshot부터 다시 세워라"
                                },
                            ),
                    )
                    .build(),
            )
            // **닫는다.** 안 닫으면 소비자의 블로킹 반복자가 영영 안 돌아오고,
            // 결함이 시험 실패가 아니라 **정지**로 나타난다.
            observer.onCompleted()
            return
        }

        buffered.filter { it.header.sequence >= request.fromSequence }.forEach { event ->
            observer.onNext(
                ReplayEventsResponse.newBuilder()
                    // 바깥 헤더는 **응답 열**이고 안쪽 이벤트의 헤더는
                    // **발행 열**이다. 한 메시지가 두 열을 함께 든다.
                    .setHeader(hosted.headers.forResponse(ReplayEventsResponse.getDescriptor()))
                    // **원본 그대로다.** 다시 찍으면 `event_id`가 바뀌어
                    // 소비자의 멱등 처리가 무너지고, `occurred_at`이 바뀌어
                    // 30초 전 사건이 방금 일어난 것으로 보인다.
                    .setEvent(event)
                    .build(),
            )
        }
        observer.onCompleted()
    }
}

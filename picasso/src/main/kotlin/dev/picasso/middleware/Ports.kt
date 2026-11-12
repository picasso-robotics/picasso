package dev.picasso.middleware

import dev.picasso.contracts.v1.CancelTaskResponse
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.StartTaskResponse
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Duration
import java.time.Instant

/**
 * 하류(로봇) 포트 — 계약(④)의 소비자. 미들웨어는 이것 너머를 모른다.
 *
 * **당기는 모양이다.** 스트림을 기다리며 블로킹하지 않고 지금까지 온 갱신을
 * 읽는다. 시험이 가상 시계를 밀고 [Middleware.pump]를 부르는 결정적 구동과
 * 맞추기 위해서다(§12.1).
 */
interface RobotPort {
    /**
     * 계약(④)은 같은 `(task_id, revision)` 재전송이 같은 핸들이다(§4.4) — 그것이 보고서 13.2 ①의 조회다.
     * 벤더가 참조 키를 안 받아도 어댑터가 매핑을 들어 이 답을 지킨다(어댑터 재시작은 밖, §1.3 B-1).
     */
    val executionLookup: ExecutionLookup get() = ExecutionLookup.CLIENT_REFERENCE

    /**
     * 이 기체가 **무엇을 드는가**(계약 `GetCapabilities`).
     *
     * **널은 못 물어봤다**이지 *아무것도 안 든다* 가 아니다. 선택 파라미터를 붙일지 판정하는 데만 쓰므로,
     * 널이면 **안 붙인다** — 막는 방향이다(§15.41).
     *
     * **못 물어봤으면 던지지 말고 널을 준다**(§15.295). 미들웨어는 이것을 라운드 도중과 접수·승인 판정에서 부르고, 던진 예외는
     * 그 호출을 통째로 실패시킨다. 인시던트 봉인과 선택 파라미터 판정만은 던져도 널로 읽는다 — 앞엣것은 봉인이 반쯤 되고,
     * 뒤엣것은 출발 요청을 만드는 도중이라 그 예외가 «나갔는지 모르는 출발» 로 읽힌다.
     *
     * 미들웨어는 출발 요청을 만들 때 소비자의 잠금 아래에서 이것을 부른다. 미리 읽는 포트([PrefetchingRobotPort])는 지금 묻지 않고
     * 스냅숏 읽기에 이어 받은 선언을 준다(§15.237).
     */
    fun capabilities(robotId: String): Capability? = null

    /**
     * 이 기체의 선언([capabilities])을 **아직 읽는 중인가**(§15.237). 참이면 미들웨어는 선택 파라미터를 바라는 출발을 다음 라운드로
     * 미룬다 — 지금 내면 «모른다» 로 접혀 선택 파라미터가 빠진다. 지금 묻는 포트는 언제나 거짓이다. 미리 읽는 포트는 기한 안에서만
     * 참을 준다 — 끝내 못 읽으면 거짓이 되고, 그때 출발은 «모른다» 로 나간다.
     */
    fun capabilitiesPending(robotId: String): Boolean = false

    /**
     * 태스크를 낸다(계약 `StartTask`). 같은 `(task_id, revision)` 이면 조회다(위 [executionLookup]).
     *
     * **던지는 예외는 둘로 읽힌다**(§15.237). 요청이 하위로 나가지 않았음을 구현이 알면 [CommandNotSent] 를 던진다 — 미들웨어는 그
     * 명령을 보내지 않은 것으로 읽고 다음 라운드에 발행 게이트를 다시 지나 보낸다. 그 밖의 예외는 나갔는지 모르는 것이다(미확정).
     */
    fun start(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): StartTaskResponse

    /**
     * 출발(`StartTask`)을 **소비자의 잠금 밖에서** 보낸다(§15.237). 미들웨어의 첫 출발·개정 갱신·미확정 조회가 [start] 대신 이것을 먼저
     * 묻는다. 널이면 이 포트는 잠금 밖에서 보내지 않는다 — 미들웨어가 [start] 를 지금 부른다. 잠금 밖에서 보내는 포트
     * ([IssuingRobotPort])만 값을 준다.
     *
     * 같은 `(robotId, taskId, revision)` 으로 다시 물으면 같은 송신의 처지를 준다. 처음 물으면 송신을 걸고 [Issue.Pending] 을, 보내는
     * 동안은 [Issue.Pending] 을, 끝나면 그 결과를 **한 번** 준다. 결과를 준 뒤 같은 키로 다시 물으면 새 송신이다.
     */
    fun issue(robotId: String, taskId: String, revision: Int, skillType: String, parameters: Map<String, String>): Issue? = null

    fun watch(robotId: String, handle: TaskHandle): List<WatchTaskResponse>

    /**
     * 이 태스크를 더 안 읽는다 — 단위가 닫혔다. [watch] 가 들던 것을 버려도 된다는 말이다. 들 것이 없는 포트는 할 일이 없다.
     */
    fun release(robotId: String, handle: TaskHandle) = Unit

    /**
     * 이 태스크를 **더 따라갈 수 없는가** — 갱신 스트림이 종료 없이 닫혔고 다시 열어도 같은 실패로 닫힌다(계약 `WatchTask` 의
     * `NOT_FOUND`·`OUT_OF_RANGE`, `docs/contract.md` §7.1). 널이면 따라가는 중이거나 다시 붙을 수 있다. 값은 그 까닭이며 자취에 남는다.
     *
     * [watch] 의 반환이 갱신 목록뿐이라 이 사실을 따로 묻는다. 어댑터 호스트가 다시 떠 태스크를 잊으면 재구독이 늘 `NOT_FOUND` 이고,
     * 이 문이 없으면 그 단위는 종료를 영영 못 본 채 `RUNNING` 에 남는다(§15.228). 들 것이 없는 포트는 늘 널이다.
     */
    fun unfollowable(robotId: String, handle: TaskHandle): String? = null

    fun cancel(robotId: String, handle: TaskHandle): CancelTaskResponse

    /**
     * 취소(`CancelTask`)를 **소비자의 잠금 밖에서** 보낸다(§15.237). 미들웨어가 취소를 보내는 네 자리(도는 단위의 취소·키로 만든 핸들의
     * 취소·운영자 보류 중 다시 보내기·재기동 뒤 붙기 전의 취소)가 [cancel] 대신 이것을 먼저 묻는다. 널이면 이 포트는 잠금 밖에서 보내지
     * 않는다 — 미들웨어가 [cancel] 을 지금 부른다.
     *
     * 같은 `(robotId, handle)` 로 다시 물으면 같은 송신의 처지를 준다. 처음 물으면 송신을 걸고 [CancelIssue.Pending] 을, 보내는 동안은
     * [CancelIssue.Pending] 을, 끝나면 그 결과를 **한 번** 준다. 결과를 준 뒤 같은 키로 다시 물으면 새 송신이다.
     */
    fun issueCancel(robotId: String, handle: TaskHandle): CancelIssue? = null

    /**
     * 기체의 현재값(계약 `GetSnapshot`, §4.8) — 활성 결함·연결 상태·태스크 상태·다음 이벤트 번호. **`null` 은 못
     * 물어봤다는 뜻**이지 결함이 없다는 뜻이 아니다 — 둘을 접으면 관측 실패가 정상으로 읽힌다(원장의
     * `Observed`/`NotObservable` 과 같은 이유).
     */
    fun snapshot(robotId: String): RobotSnapshot?

    /** 재생 버퍼(계약 `ReplayEvents`, §4.8) — [from] 부터의 이벤트. 벗어났으면 [Replay.Evicted]. 못 물어봤으면 `null`. */
    fun replay(robotId: String, from: Long): Replay?

    /**
     * 소비자의 잠금 밖에서 **미리 읽은** 스냅숏과 그 왕복 시각(§15.267). 미들웨어의 동기화가 [snapshot] 대신 이것을 먼저 묻는다.
     *
     * 널이면 이 포트는 미리 읽지 않는다 — 미들웨어가 [snapshot] 을 지금 부르고 자기 시계로 보낸 시각·받은 시각을 잰다. 미리 읽는 포트
     * ([PrefetchingRobotPort])만 값을 준다. 값을 주는 포트는 그 읽기에 이어 물은 재생을 [replay] 로 돌려준다.
     */
    fun prefetched(robotId: String): PrefetchedRead? = null
}

/**
 * 미리 읽은 스냅숏 하나의 처지([RobotPort.prefetched]). 미들웨어의 동기화가 이 넷을 가른다(§15.267).
 *
 * **«아직 새 값이 없다» 와 «못 물어봤다» 는 다르다.** 읽기 한 번이 라운드 한 번보다 길면 그 사이 라운드에는 새 값이 없다. 그것을
 * 못 물어봤다로 접으면 정상적인 읽기 간격이 관측 실패가 되고, 그대로 두면 답하지 않는 기체의 낡은 값이 현재값으로 남는다. 그래서
 * [Pending] 과 [Unread] 를 [PrefetchingRobotPort.maxAge] 로 가른다.
 */
sealed interface PrefetchedRead {
    /** 읽는 중이고 마지막 읽기가 아직 낡지 않았다. 뷰를 그대로 둔다. */
    data object Pending : PrefetchedRead

    /** 쓸 읽기가 없다 — 읽은 적이 없거나, 읽는 중인 채 마지막 읽기가 낡았거나, 다 읽은 값이 낡았다. [RobotPort.snapshot] 의 널과 같다. */
    data object Unread : PrefetchedRead

    /**
     * 하위에 물었고 답이 왔다. [snapshot] 이 널이면 하위가 못 물어봤다고 답한 것이다. [sentAt]·[receivedAt] 은 그 읽기가 **실제로**
     * 보낸·받은 시각이다(소비한 시각이 아니다).
     */
    data class Read(val snapshot: RobotSnapshot?, val sentAt: Instant, val receivedAt: Instant) : PrefetchedRead

    /** 읽기가 던졌다. 받는 쪽은 지금 부른 호출이 던진 것과 같이 다룬다. */
    data class Failed(val failure: RuntimeException) : PrefetchedRead
}

/**
 * 출발 송신 하나의 처지([RobotPort.issue], §15.237). 미들웨어가 [RobotPort.start] 를 동기로 부른 결과도 같은 넷으로 감싼다.
 *
 * 보낸 시각([Answered.sentAt]·[InDoubt.sentAt])은 잠금 밖에서 보낸 포트가 **실제로 보낸 시각**이다. 미들웨어가 동기로 감싼 것은 널이다 —
 * 부른 쪽이 제 시계로 잰다.
 */
sealed interface Issue {
    /** 보내는 중이다. 이 라운드에는 결과가 없다. */
    data object Pending : Issue

    /** 하위가 답했다(핸들이든 거부든). */
    data class Answered(val response: StartTaskResponse, val sentAt: Instant?) : Issue

    /** 하위로 나가지 않았다([CommandNotSent]). 같은 기체의 다른 출발이 보내는 중이어서 걸지 못한 것도 이것이다. */
    data class NotSent(val reason: String) : Issue

    /** 나갔는지 모른다 — 보낸 뒤 답을 못 받았다. */
    data class InDoubt(val failure: RuntimeException, val sentAt: Instant?) : Issue
}

/**
 * 취소 송신 하나의 처지([RobotPort.issueCancel], §15.237). 미들웨어가 [RobotPort.cancel] 을 동기로 부른 결과도 같은 셋으로 감싼다.
 *
 * 출발과 달리 미확정을 따로 두지 않는다. 취소는 두 번 나가도 물리 작업이 둘이 되지 않는다 — 미들웨어는 실패를 «닿지 않았다» 로 읽고
 * 다시 보낸다.
 */
sealed interface CancelIssue {
    /** 보내는 중이다. 이 라운드에는 결과가 없다. */
    data object Pending : CancelIssue

    /** 하위가 답했다(상태든 거부든). */
    data class Answered(val response: CancelTaskResponse) : CancelIssue

    /** 닿았는지 모른다 — 보내지 않았거나([CommandNotSent]) 답을 못 받았다. 같은 기체의 다른 명령이 보내는 중이어서 걸지 못한 것도 이것이다. */
    data class Failed(val failure: RuntimeException) : CancelIssue
}

/**
 * **명령이 하위로 나가지 않았다**(§15.237) — [RobotPort] 구현이 그것을 알 때만 던진다. 미들웨어가 미확정(`IN_DOUBT`)과 가르는 유일한
 * 표식이다. 미들웨어는 이것을 받으면 그 명령을 보내지 않은 것으로 읽는다. 첫 출발은 단위를 `PENDING` 에 두어 다음 라운드에 다시
 * 내고, 개정 갱신은 미뤄 두며, 미확정 조회는 조회 횟수를 쓰지 않는다.
 *
 * 나갔는지 모르면 던지지 않는다. 요청을 보낸 뒤 답을 못 받은 실패를 이것으로 접으면 같은 명령이 두 번 나가 물리 작업이 둘이 될 수 있다.
 * 그래서 gRPC `UNAVAILABLE` 이라는 것만으로는 이것이 아니다 — 연결이 요청을 보낸 뒤 끊겨도 같은 코드가 온다.
 */
class CommandNotSent(message: String) : RuntimeException(message)

// ── 하류(플릿) 포트 — D 수준 위임. 프로젝트용 계약이다.

/**
 * 플릿에 맡기는 운반 하나(보고서 5장 — 시나리오 ①의 하류 구현 방식 하나: *"Fleet 시스템이
 * 용기 운반 전체를 제공하면 하나의 작업으로 위임한다"*).
 *
 * **이것은 프로젝트용 계약이지 특정 벤더 API 의 재현이 아니다**(보고서 4.1). 벤더
 * AMR API·VDA 5050 은 구현하지 않는다(`scenarios.md` §1 규칙 1). 여기 있는 것은
 * 보고서 3.4 의 필수 계약 일곱 중 이 시나리오가 요구하는 것 — 클라이언트 참조로
 * 멱등한 접수, 실행 식별과 조회, 성공의 뜻, 실패, 중단.
 *
 * @param reference 클라이언트 참조 키. 같은 참조로 다시 맡기면 새 운반이 생기지 않는다 —
 *   보고서 13.2 가 하류에 요구하는 그것이며, 접수 직후 단절의 `IN_DOUBT` 가 이것으로 풀린다.
 */
data class TransportOrder(
    val reference: String,
    val containerId: String,
    val source: String,
    val destination: String,
)

/** 플릿이 발급한 운반 식별자. */
@JvmInline
value class TransportHandle(val id: String)

/**
 * 운반의 상태 — 플릿이 자기 완료 조건으로 말한다.
 *
 * **성공의 뜻(보고서 10.2·5장)**: [DELIVERED] 는 *도착 ∧ 하역 완료 ∧ 목적지 설비가 인수 ∧
 * AMR 이 용기를 계속 보유하고 있지 않음* 이다. 이것이 플릿 계약이 보장하는 사후조건이고,
 * 그래서 E1 이다. 용기가 **그** 용기인지는 설비(E2)가 말한다.
 */
enum class TransportState {
    ACCEPTED,
    /** 출발지에서 용기를 인수했다 — 관측한 용기 태그가 [TransportStatus.observedContainer] 에. */
    PICKED_UP,
    IN_TRANSIT,
    /** 목적지가 비어 있지 않아 인계를 기다린다. 임의의 다른 자리에 내려놓지 않는다. */
    WAITING_HANDOVER,
    DELIVERED,
    /** 출발지의 용기가 요청과 달라 **인수하지 않았다**. */
    REJECTED_AT_SOURCE,
    CANCELLED,
    FAILED,
}

data class TransportStatus(
    val state: TransportState,
    /** 플릿이 읽은 용기 태그. 출발지 불일치의 근거이며, 없으면 `null`. */
    val observedContainer: String?,
    /** AMR 이 지금 용기를 싣고 있는가 — 잔여 물리 상태. */
    val holding: Boolean,
    val detail: String? = null,
)

interface AmrFleetPort {
    /**
     * 이 플릿이 [TransportOrder.reference] 로 기존 운반을 찾아 주는가. 프로젝트용 계약의 기본은 그렇다 —
     * 실물 플릿이 안 그러면 어댑터가 [ExecutionLookup.NONE] 을 선언하고, 그때 `IN_DOUBT` 는 운영자에게 간다.
     */
    val executionLookup: ExecutionLookup get() = ExecutionLookup.CLIENT_REFERENCE

    /** 맡긴다. 플릿이 거절하면 `null`. 같은 [TransportOrder.reference] 는 같은 핸들이다. */
    fun dispatch(order: TransportOrder): TransportHandle?

    /**
     * [reference] 로 맡긴 운반을 **찾기만 한다** — 없으면 `null` 이고 새로 맡기지 않는다(§15.254). [dispatch] 의 같은 참조 재전송과 다른
     * 것은 그것뿐이다. 다시 지은 실행이 취소하려고 붙는 자리에서 쓴다 — 재전송은 플릿이 그 운반을 모르면 새 운반을 낸다.
     * [executionLookup] 이 [ExecutionLookup.NONE] 이면 부르지 않는다.
     */
    fun find(reference: String): TransportHandle?
    fun status(handle: TransportHandle): TransportStatus
    fun cancel(handle: TransportHandle): Boolean

    /**
     * 맡기기([dispatch])를 **소비자의 잠금 밖에서** 보낸다(§15.237). 미들웨어의 첫 맡기기와 미확정 조회(같은 참조의 재전송)가 [dispatch]
     * 대신 이것을 먼저 묻는다. 널이면 이 포트는 잠금 밖에서 보내지 않는다 — 미들웨어가 [dispatch] 를 지금 부른다.
     *
     * 같은 [TransportOrder.reference] 로 다시 물으면 같은 송신의 처지를 준다. 처음 물으면 송신을 걸고 [DispatchIssue.Pending] 을,
     * 보내는 동안은 [DispatchIssue.Pending] 을, 끝나면 그 결과를 **한 번** 준다. 결과를 준 뒤 같은 참조로 다시 물으면 새 송신이다.
     */
    fun issueDispatch(order: TransportOrder): DispatchIssue? = null

    /**
     * 운반의 취소([cancel])를 **소비자의 잠금 밖에서** 보낸다(§15.237). [reference] 는 그 운반을 맡긴 참조다 — 잠금 밖에서 보내는 포트가
     * 같은 운반의 맡기기와 취소를 한 차선에 둔다. 널이면 미들웨어가 [cancel] 을 지금 부른다. 다시 묻는 규칙은 [issueDispatch] 와 같다.
     */
    fun issueCancel(reference: String, handle: TransportHandle): FleetCancelIssue? = null

    /**
     * 운반 상태([status])를 **소비자의 잠금 밖에서** 미리 읽은 값(§15.237). 미들웨어가 도는 운반 단위를 볼 때 [status] 대신 이것을 먼저
     * 묻는다. 널이면 이 포트는 미리 읽지 않는다 — 미들웨어가 [status] 를 지금 부른다. 다 읽은 값은 한 번만 준다([PrefetchingFleetPort]).
     */
    fun prefetchedStatus(handle: TransportHandle): FleetRead<TransportStatus>? = null

    /** 찾기([find])를 **소비자의 잠금 밖에서** 미리 읽은 값(§15.237). 규칙은 [prefetchedStatus] 와 같다. */
    fun prefetchedFind(reference: String): FleetRead<TransportHandle?>? = null

    object None : AmrFleetPort {
        override fun dispatch(order: TransportOrder): TransportHandle? = null
        override fun find(reference: String): TransportHandle? = null
        override fun status(handle: TransportHandle): TransportStatus =
            TransportStatus(TransportState.FAILED, null, false, "플릿이 없다")
        override fun cancel(handle: TransportHandle): Boolean = false
    }
}

/**
 * 미리 읽은 플릿 읽기 하나의 처지([AmrFleetPort.prefetchedStatus]·[AmrFleetPort.prefetchedFind]). [PrefetchedRead] 의 플릿 쪽이다(§15.237).
 *
 * **«아직 답이 없다» 와 «답하지 않는다» 를 가른다.** 읽기 한 번이 라운드 한 번보다 길면 그 사이 라운드에는 새 값이 없다 — [Pending] 이다.
 * 읽기가 [PrefetchingFleetPort.maxAge] 넘게 답하지 않으면 [Overdue] 다. 미들웨어는 그것을 읽기가 던진 것과 같이 다룬다 — 동기로 부를 때
 * 답하지 않는 플릿이 기한 끝에 던지던 자리다.
 */
sealed interface FleetRead<out T> {
    /** 읽는 중이고 아직 기한 안이다(방금 건 읽기도 그렇다). 이번 라운드에는 값이 없다. */
    data object Pending : FleetRead<Nothing>

    /** 읽기가 [since] 에 나가 [PrefetchingFleetPort.maxAge] 넘게 답하지 않는다. */
    data class Overdue(val since: Instant) : FleetRead<Nothing>

    /** 하위에 물었고 답이 왔다. [sentAt]·[receivedAt] 은 그 읽기가 **실제로** 보낸·받은 시각이다(받아 간 시각이 아니다). */
    data class Read<out T>(val value: T, val sentAt: Instant, val receivedAt: Instant) : FleetRead<T>

    /** 읽기가 던졌다. 받는 쪽은 지금 부른 호출이 던진 것과 같이 다룬다. */
    data class Failed(val failure: RuntimeException) : FleetRead<Nothing>
}

/**
 * 맡기기 송신 하나의 처지([AmrFleetPort.issueDispatch], §15.237). 미들웨어가 [AmrFleetPort.dispatch] 를 동기로 부른 결과도 같은 넷으로
 * 감싼다.
 */
sealed interface DispatchIssue {
    /** 보내는 중이다. 이 라운드에는 결과가 없다. */
    data object Pending : DispatchIssue

    /** 플릿이 답했다. [handle] 이 널이면 플릿이 맡지 않았다. [sentAt] 은 실제로 보낸 시각이고, 동기로 부른 결과면 널이다. */
    data class Answered(val handle: TransportHandle?, val sentAt: Instant?) : DispatchIssue

    /** 플릿으로 보내지 않았다([CommandNotSent]). 같은 참조의 다른 명령이 보내는 중이어서 걸지 못한 것도 이것이다. */
    data class NotSent(val reason: String) : DispatchIssue

    /** 보냈는데 답을 못 받았다 — 맡겨졌는지 모른다. [sentAt] 은 [Answered] 와 같다. */
    data class InDoubt(val failure: RuntimeException, val sentAt: Instant?) : DispatchIssue
}

/**
 * 운반 취소 송신 하나의 처지([AmrFleetPort.issueCancel], §15.237). 로봇 취소([CancelIssue])와 같은 까닭으로 미확정을 따로 두지 않는다 —
 * 미들웨어는 실패를 «닿지 않았다» 로 읽고 다시 보낸다.
 */
sealed interface FleetCancelIssue {
    /** 보내는 중이다. 이 라운드에는 결과가 없다. */
    data object Pending : FleetCancelIssue

    /** 플릿이 답했다. [accepted] 는 [AmrFleetPort.cancel] 의 반환값이다. */
    data class Answered(val accepted: Boolean) : FleetCancelIssue

    /** 닿았는지 모른다. 같은 참조의 다른 명령이 보내는 중이어서 걸지 못한 것도 이것이다. */
    data class Failed(val failure: RuntimeException) : FleetCancelIssue
}

// ── 독립 설비 신호 — E2

/**
 * 독립 설비 신호 — E2 의 원천(설계 §1.3, 보고서 12장). 인계 설비·셀 검증 장치.
 *
 * 현장 PLC 는 밀지 않고 폴링으로 읽는 것이 설치 기반의 현실이므로 이것도 **묻는**
 * 모양이다. 시험에서는 PLC/WCS Mimic 이 이것을 구현한다.
 *
 * **이 읽기는 막지 않아야 한다**(§15.237). 미들웨어는 소비자의 잠금 아래에서 [observe]·[holding]·[signal] 을 부르고, 이 포트에는
 * 미리 읽는 경로를 따로 두지 않는다. 설비를 원격으로 읽는 구현은 그 읽기를 잠금 밖에서 돌려 두고 여기서는 마지막으로 읽은 값만
 * 준다 — 담는 쪽이 라운드마다 셀 신호의 스냅숏을 갈아 끼우는 꼴이다. 못 읽었으면 기다리지 않고 `null` 을 준다.
 */
interface CellSignals {
    /** 그 자리에 무엇이 있는가. 신호가 없으면 `null` — 빈 자리가 아니라 **말이 없다**. */
    fun observe(location: String): SlotSignal?

    /**
     * 이 자재를 **든 자리들**(§15.153). 출발 자리가 비었을 때 다른 자리를 제시하는 데 쓴다.
     *
     * `null` 은 **설비가 그 질문에 답하지 않는다**는 뜻이고 빈 목록과 다르다. 빈 목록은 «그 자재를 든
     * 자리가 하나도 없다» 는 답이다. 둘을 접으면 못 물어본 것이 «없다» 로 읽혀 운영자가 재고를 의심한다.
     *
     * 기본값이 `null` 인 것은 **이 질문에 답할 수 있는 설비가 흔하지 않기** 때문이다 — 슬롯마다 신호만
     * 내는 접점은 자기 자리밖에 모른다. 답할 수 있는 쪽(WMS 를 낀 셀 제어기)이 구현한다.
     */
    fun holding(material: String): List<String>? = null

    /**
     * 이름 있는 설비 신호의 **지금 값**(설비 대기, [Route.SIGNAL]). 이름은 현장 신호 사양의 이름이다.
     *
     * `null` 은 **읽지 못했다**는 뜻이고 «기대 값이 아니다» 와 다르다. 설비 대기는 `null` 을 받으면 계속 기다리고,
     * 기한이 지나면 정해 둔 상태로 간다 — 못 읽은 것을 아닌 것으로 접지 않는다.
     *
     * 기본값이 `null` 인 것은 **자리별 점유만 내는 설비가 이름 있는 신호를 모르기** 때문이다. 어느 주소가 어느 신호인지의
     * 매핑은 이 층이 아니라 드라이버의 일이다.
     */
    fun signal(name: String): NamedSignal? = null

    object None : CellSignals {
        override fun observe(location: String): SlotSignal? = null
    }
}

/**
 * 이름 있는 설비 신호 하나의 값과 그 관측 시각.
 *
 * 값은 문자열이다 — 신호 사양의 종류가 `BOOLEAN` 이면 `true`·`false` 다. [observedAt] 이 `null` 이면 설비가
 * 시각을 안 주는 것이고 **읽은 순간**이 그 시각이다([SlotSignal] 과 같은 규칙). 시각은 자취에 남기는 데만 쓴다.
 */
data class NamedSignal(val value: String, val observedAt: Instant? = null)

// ── 임무 정의 — 작업 지시를 실행 단위로 펼치는 케이퍼빌리티의 출처

/**
 * WorkMaster 마다 **지금 활성인** 케이퍼빌리티와 그 임무 버전을 주는 포트.
 *
 * 미들웨어는 새 작업 지시를 계획할 때만 이것을 읽는다. 실행은 생성 때 읽은 쌍을 쥐고 끝까지 그것으로 돈다 —
 * 리비전도 그 쌍으로 계획한다. 그래서 활성 버전이 바뀌어도 **도는 실행은 옛 버전으로 끝난다.**
 *
 * 활성화(새 버전을 세우는 일)는 이 포트에 없다. 그것은 구현의 일이고, 검증을 통과해야 선다
 * (`dev.picasso.middleware.mission.InMemoryMissionCatalog`). 이 층은 읽기만 한다.
 */
interface MissionCatalog {

    /** 이 WorkMaster 의 지금 활성인 정의. 없으면 `null` — 그 작업 지시는 «모르는 논리적 능력» 으로 거부된다. */
    fun active(workMasterId: String): ActiveMission?

    companion object {
        /** 코드로 정의한 케이퍼빌리티 셋. 미들웨어의 기본 목록이다. 부를 때마다 새 인스턴스를 만든다. */
        fun codeCapabilities(): List<LogicalCapability> = listOf(PrepareSequencedRack(), DeliverContainer(), InspectAsset())

        /** 코드 케이퍼빌리티만으로 된 카탈로그 — 임무 버전이 없다. 같은 WorkMaster 가 둘이면 뒤엣것이 남는다. */
        fun of(capabilities: List<LogicalCapability>): MissionCatalog {
            val byWorkMaster = capabilities.associateBy { it.workMasterId }
            return object : MissionCatalog {
                override fun active(workMasterId: String): ActiveMission? =
                    byWorkMaster[workMasterId]?.let { ActiveMission(it, missionVersion = null) }
            }
        }
    }
}

/**
 * 활성인 정의 하나 — 케이퍼빌리티와 그것이 어느 임무 버전인지.
 *
 * @param missionVersion 데이터 정의면 그 WorkMaster 안에서 1부터 오르는 번호, 코드 케이퍼빌리티면 `null`.
 *   작업 지시의 버전(`JobOrder.version`, 단위의 `revision`)과 다른 축이다.
 */
data class ActiveMission(val capability: LogicalCapability, val missionVersion: Int?)

/**
 * 한 자리의 설비 신호 — 재석 여부와 설비가 읽은 식별자(부품 타입 라벨이든 용기 태그든,
 * 설비가 읽을 수 있는 것 — §15.80 의 신원 수단), 그리고 그 신호의 시각 `t_p`.
 *
 * [observedAt] 이 `null` 이면 설비가 시각을 안 주는 것이다 — 폴링으로 현재값만 읽는 PLC 가
 * 그렇다. 그때는 **읽은 순간**이 `t_p` 다(보고서 12.2 — 짧은 신호는 PLC 쪽 래치 비트나
 * 카운터가 있어야 폴링이 놓치지 않는다).
 */
data class SlotSignal(val occupied: Boolean, val identity: String?, val observedAt: Instant? = null)

// ── 현장 시간값 — 케이퍼빌리티 기본값을 덮는 현장 설정 한 세트

/**
 * 현장 시간값의 출처. 미들웨어는 [Middleware.pump] 가 시작할 때 **한 번** 읽고 그 라운드의 모든 판정에 같은 값을 쓴다.
 *
 * `null` 은 현장 값이 없다는 뜻이고, 그때는 케이퍼빌리티의 기본값([LogicalCapability.evidenceWindow]·
 * [LogicalCapability.inDoubtGrace]·[LogicalCapability.stallWindow])으로 판정한다. 값을 쓰기 전에 검사하는 것은 구현의 일이다.
 * 범위 밖 값을 받아 둔 구현은 그것을 주지 말고 마지막으로 통과한 값을 준다([SiteTimings.problems]).
 *
 * [current] 는 pump 안에서 불리므로 미들웨어를 지키는 소비자(호스트)의 잠금 아래에서 돈다고 전제한다(미들웨어 자체에
 * 스레드가 없다). 그래서 막히지 않아야 하고, 이미 검사한 스냅숏을 돌려준다. 여기서 DB 를 읽지 않는다. 읽기 주기가 다른
 * 스레드에서 값을 바꾸면 안전하게 게시한다(`@Volatile` 필드나 `AtomicReference`).
 */
fun interface SiteTimingsSource {

    /** 지금 적용할 값. 없으면 `null`. */
    fun current(): SiteTimings?

    companion object {
        /** 현장 값이 없다. 미들웨어의 기본값이며, 모든 판정이 케이퍼빌리티 값으로 돈다. */
        val NONE: SiteTimingsSource = SiteTimingsSource { null }
    }
}

/**
 * 현장 설정 한 버전의 시간값 넷. 현장 전체에 한 값이며, 값이 있으면 모든 케이퍼빌리티의 같은 값을 덮는다.
 *
 * 값은 **초 단위 정수**다. 허용 범위는 이 라이브러리가 쥐고([EVIDENCE_WINDOW_BEFORE_SECONDS] 들), 쓰는 쪽은 적용 전에
 * [problems] 로 검사한다. 생성자는 검사하지 않는다. 범위 밖 값을 읽어 «왜 적용하지 않았나» 를 보여야 하는 쪽이 있기 때문이다.
 *
 * @param siteSettingsVersion 현장 설정의 버전. 1 부터 오른다. 인시던트의 의도에 실린다.
 * @param evidenceWindowBefore 근거 시간 윈도우의 앞 폭. 늘리면 옛 신호가 완료 근거로 들어온다.
 * @param evidenceWindowAfter 근거 시간 윈도우의 뒤 폭. 줄이면 `UNVERIFIED` 가 는다. 단위가 완료될 때 근거 기한에 저장된다.
 * @param inDoubtGrace `IN_DOUBT` 에서 물리 관측을 기다리는 유예. 줄이면 운영자 대기가 는다.
 * @param stallWindow 진행 정체를 사람에게 보이기까지의 유예. 결과 판정은 바꾸지 않는다.
 */
data class SiteTimings(
    val siteSettingsVersion: Long,
    val evidenceWindowBefore: Duration,
    val evidenceWindowAfter: Duration,
    val inDoubtGrace: Duration,
    val stallWindow: Duration,
) {
    /** 앞·뒤 폭을 케이퍼빌리티와 같은 모양으로. */
    val evidenceWindow: EvidenceWindow get() = EvidenceWindow(before = evidenceWindowBefore, after = evidenceWindowAfter)

    /**
     * 허용 범위를 벗어난 칸마다 문장 하나. 비어 있으면 적용해도 된다.
     *
     * 문장은 칸 이름으로 시작한다(`"stallWindow: ..."`). 범위 밖 값은 적용하지 않고 마지막으로 적용한 버전을 유지한다
     * (운영 관리 화면 설계 제안 §9). 초 단위가 아닌 값(밀리초가 남는 값)도 범위 밖으로 친다.
     */
    fun problems(): List<String> = buildList {
        if (siteSettingsVersion < 1) add("siteSettingsVersion: 1 이상이어야 한다 ($siteSettingsVersion)")
        outside("evidenceWindowBefore", evidenceWindowBefore, EVIDENCE_WINDOW_BEFORE_SECONDS)?.let(::add)
        outside("evidenceWindowAfter", evidenceWindowAfter, EVIDENCE_WINDOW_AFTER_SECONDS)?.let(::add)
        outside("inDoubtGrace", inDoubtGrace, IN_DOUBT_GRACE_SECONDS)?.let(::add)
        outside("stallWindow", stallWindow, STALL_WINDOW_SECONDS)?.let(::add)
    }

    private fun outside(field: String, value: Duration, range: LongRange): String? = when {
        value.nano != 0 -> "$field: 초 단위 정수여야 한다 ($value)"
        value.seconds !in range -> "$field: ${range.first}~${range.last} 초 밖이다 (${value.seconds})"
        else -> null
    }

    companion object {
        /**
         * 앞 폭의 허용 범위(초). 하한은 셀이 슬롯을 보고 기체가 완료를 보고하는 순서가 뒤바뀌는 것을 받기 위함이고,
         * 상한(기본값의 4배)은 옛 신호가 완료 근거로 들어오는 파급을 묶는다. 기본값 30.
         */
        val EVIDENCE_WINDOW_BEFORE_SECONDS: LongRange = 5L..120L

        /** 뒤 폭의 허용 범위(초). 하한은 pump 주기와 셀 폴링 주기를 넘기기 위함이고, 상한은 기본값의 8배다. 기본값 15. */
        val EVIDENCE_WINDOW_AFTER_SECONDS: LongRange = 5L..120L

        /**
         * `IN_DOUBT` 유예의 허용 범위(초). 이 유예 안에서 pump 마다 같은 참조로 다시 묻고 스냅숏에 그 태스크가 보이는지 본다.
         * 하한은 그 뒤에도 설비를 여러 번 읽을 폭이고, 상한은 기본값의 10배다. 기본값 60.
         */
        val IN_DOUBT_GRACE_SECONDS: LongRange = 10L..600L

        /** 진행 정체 유예의 허용 범위(초). 하한은 기종 프로파일의 가장 긴 발행 간격(30초)이고, 상한은 기본값의 12배다. 기본값 300. */
        val STALL_WINDOW_SECONDS: LongRange = 30L..3600L

        /** 초 단위 정수로 만든다. 저장소가 초 단위 정수로 드는 값을 그대로 옮길 때 쓴다. */
        fun ofSeconds(siteSettingsVersion: Long, evidenceWindowBefore: Long, evidenceWindowAfter: Long, inDoubtGrace: Long, stallWindow: Long) =
            SiteTimings(
                siteSettingsVersion,
                Duration.ofSeconds(evidenceWindowBefore),
                Duration.ofSeconds(evidenceWindowAfter),
                Duration.ofSeconds(inDoubtGrace),
                Duration.ofSeconds(stallWindow),
            )
    }
}

// ── 작업 허가 — 셀 안전 상태의 정보 투영(ADR 52). 안전 기능이 아니다.

/**
 * 작업 허가의 값 셋. **이 저장소의 어휘이며 계약 proto 에 들어가지 않는다**(ADR 9·36) — 받는 쪽은 발행 관문 하나이고 로봇은
 * 이 값을 읽지 않는다.
 *
 * - [PERMITTED] 새 일을 내도 된다고 원천이 지금 말한다.
 * - [INHIBITED] 원천이 막는다. 막는 주체를 [PermitScope.subject] 로 말한다.
 * - [UNKNOWN] 모른다. **발행 관문은 이것을 막음으로 읽는다**(닫힌 쪽).
 */
enum class WorkPermit { PERMITTED, INHIBITED, UNKNOWN }

/**
 * **작업 허가의 출처**(ADR 52) — 현장의 안전 상태(셀 문, 광커튼, 셀 모드 같은)를 기체 집합마다 새 발행의 허가로 비춘 값을 준다.
 *
 * ⚠ **안전 기능이 아니다**(ADR 32). 비상정지·보호 정지·사람 감지는 계약 아래의 안전 계통이 하고, 이 포트는 그 상태를 정보로
 * 옮길 뿐이다. 이 값이 늦거나 틀려도 안전 계통은 그대로 기체를 세운다 — 그래서 이 층은 이 값으로 **새 발행만** 막고 취소·관측은
 * 막지 않는다.
 *
 * 소비자는 미들웨어의 발행 관문 하나다(ADR 9, ADR 51 결정 6). 구현은 담는 쪽(picasso-ops)이 현장 신호에서 짓는다.
 *
 * [read] 는 새 발행을 내기 직전마다 불린다. 미들웨어를 지키는 호스트의 잠금 아래에서 돌므로 막히지 않아야 하고, 이미 읽어 둔
 * 스냅숏을 돌려준다([SiteTimingsSource.current] 와 같은 전제). **던지면 모든 기체가 [WorkPermit.UNKNOWN] 이고 막힌다.**
 */
fun interface WorkPermitPort {

    /** 원천마다 지금의 판독. 원천이 하나면 원소도 하나다. */
    fun read(): List<PermitReading>
}

/**
 * 원천 하나의 판독.
 *
 * @param source 원천의 이름. 막음을 보일 때 «누가 막았나» 의 앞머리로 실린다.
 * @param session 원천의 지금 세션. 원천이 다시 붙을 때마다 바뀐다. 이것과 다른 세션의 줄([PermitScope.session])은 앞 세션의
 *   관측이라 [WorkPermit.UNKNOWN] 으로 읽힌다.
 * @param governed 이 원천이 맡은 기체 전부 — **미리 정해 둔 넓은 범위**다. 원천이 어느 기체인지 말하지 못한 줄
 *   ([PermitScope.robots] 가 널)은 이 기체 전부를 모름으로 만든다.
 */
data class PermitReading(
    val source: String,
    val session: String,
    val governed: Set<String>,
    val scopes: List<PermitScope>,
)

/**
 * 판독의 줄 하나 — 기체 집합 하나와 그 허가.
 *
 * **범위의 열쇠는 기체 id 의 집합이다.** 구역이나 셀 객체가 아니다 — 이 층이 아는 이름은 기체 id 이고, 구역을 기체로 푸는 것은
 * 원천(현장)의 일이다.
 *
 * @param robots 이 줄이 말하는 기체. 널이면 **원천이 어느 기체인지 모른다** — [PermitReading.governed] 전부가 모름이 된다.
 * @param subject 막는 주체. 원천이 말하지 않았으면 널.
 * @param resumeBasis 무엇이 바뀌면 풀리는가. 원천이 말하지 않았으면 널이고, 그때는 이 층이 까닭에서 정한 말을 보인다.
 * @param observedAt 원천이 이 값을 관측한 시각.
 * @param validFor 관측 시각부터 이 값이 지금의 값으로 읽히는 폭. 지나면 [WorkPermit.UNKNOWN] 이다.
 * @param session 이 줄을 관측한 원천 세션.
 */
data class PermitScope(
    val robots: Set<String>?,
    val permit: WorkPermit,
    val subject: String?,
    val resumeBasis: String?,
    val observedAt: Instant,
    val validFor: Duration,
    val session: String,
)

// ── 기체 퇴역 — 원장의 운영자 판단을 새 발행에 비춘다(§15.249).

/**
 * **기체 퇴역의 출처**(§15.249) — 운영자가 원장에서 퇴역으로 내린 기체와 그 사유를 준다. 구현은 담는 쪽이 원장에서 짓는다.
 *
 * 소비자는 발행 관문([IssuingGate])과 재할당([Middleware.reassign]) 둘이다. 퇴역한 기체로는 **새 로봇 명령만** 내지 않고 재할당의
 * 후보로도 쓰지 않는다. ★**도는 단위는 끊지 않는다** — 퇴역은 원장의 판단이지 제어 명령이 아니다(`docs/commissioning.md` §5).
 * 그 기체에서 도는 일을 멈추려면 운영자가 취소한다.
 *
 * [retired] 는 새 발행을 내기 직전마다 불린다. 미들웨어를 지키는 호스트의 잠금 아래에서 돌므로 막히지 않아야 하고, 이미 읽어 둔
 * 스냅숏을 돌려준다([WorkPermitPort.read] 와 같은 전제). **던지면 아무것도 막지 않는다** — 작업 허가와 반대 쪽이다. 퇴역은 안전
 * 상태가 아니라 운영자의 판단이라, 원장을 못 읽은 것으로 현장 전체를 세우지 않는다.
 */
fun interface RetirementPort {

    /** 지금 퇴역한 기체 id 와 그 사유. 퇴역하지 않은 기체는 싣지 않는다. */
    fun retired(): Map<String, String>
}

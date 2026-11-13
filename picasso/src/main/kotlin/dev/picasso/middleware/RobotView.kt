package dev.picasso.middleware

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.TaskState
import java.time.Instant

/**
 * 기체 하나에 대한 이 층의 관측 — 계약 §4.8 의 소비자 패턴 그대로다. 스냅샷으로 세우고(`cursor` = 다음에 올 번호)
 * 재생으로 이어 붙이며, 버퍼를 벗어나면 스냅샷부터 다시 세운다(보고서 17장 9번 *단절 후 재동기화*).
 *
 * 결함과 연결 상태는 **스냅샷이 권위**다(현재값). 이벤트는 그 사이에 무슨 일이 있었는지 — 섰다가 사라진 결함,
 * 전이 — 를 자취로 남긴다. 둘 중 하나만 쓰면 신규 소비자가 놓친 전이를 세우지 못하거나(이벤트만) 그 사이를
 * 모른다(스냅샷만).
 */
class RobotView internal constructor() {
    var cursor: Long? = null
        internal set
    /** [cursor] 가 속한 세션 — 마지막으로 읽은 스냅샷의 `session_id`. 못 읽었으면 널이다. */
    var session: String? = null
        internal set
    var faults: Map<String, Fault> = emptyMap()
        internal set
    var connection: ConnectionState = ConnectionState.CONNECTION_STATE_ONLINE
        internal set
    var tasks: Map<String, dev.picasso.contracts.v1.TaskState> = emptyMap()
        internal set
    /** [tasks] 의 태스크마다 기체가 든 리비전. 포트가 안 실으면 비어 있다. */
    var taskRevisions: Map<String, Int> = emptyMap()
        internal set
    /** 마지막 동기화에서 기체를 봤는가(스냅숏을 읽었는가). 거짓이면 위의 값은 낡은 것이다. */
    var observable: Boolean = false
        internal set

    /**
     * 마지막으로 읽은 스냅숏이 결함을 봤는가(§15.263). 거짓이면 [faults] 는 비어 있되 *결함 없음* 이 아니다 — 결함 표면이 없는
     * 기체다. 태스크·연결은 [observable] 이 말하는 대로 현재값이다. [observable] 이 거짓이면 의미가 없다.
     */
    var faultsObservable: Boolean = false
        internal set

    /** 마지막으로 스냅숏을 읽은 시각(미들웨어 시계). 널이면 한 번도 못 읽었다. 연결 끊김의 유예([LogicalCapability.linkLossWindow])를 여기부터 잰다. */
    var lastObservedAt: Instant? = null
        internal set
    var resyncs: Int = 0
        internal set
    var eventsSeen: Long = 0
        internal set

    /**
     * 마지막 스냅숏 왕복에서 잰 **발신자 시계 차의 구간**(발신자 시계 − 미들웨어 시계). 널이면 아직 못 쟀다 — 응답 헤더에 시각이 없었거나
     * 스냅숏을 못 읽었다. 임계 판정은 읽는 쪽 실행의 근거 시간 윈도우로 하므로 구간 그대로 든다.
     */
    internal var clockOffset: ClockOffset? = null
}

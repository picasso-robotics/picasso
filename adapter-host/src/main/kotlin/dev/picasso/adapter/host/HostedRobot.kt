package dev.picasso.adapter.host

import com.google.protobuf.Descriptors
import dev.picasso.adapter.core.Acceptance
import dev.picasso.adapter.core.ActiveRevision
import dev.picasso.adapter.core.SiteBindingCheck
import dev.picasso.adapter.core.SiteBindingSource
import dev.picasso.adapter.core.Applied
import dev.picasso.adapter.core.FaultObservation
import dev.picasso.adapter.core.HoldObservation
import dev.picasso.adapter.core.ProgressObservation
import dev.picasso.adapter.core.Refusal
import dev.picasso.adapter.core.RobotAdapter
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.ConnectionMessage
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.FaultEvent
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.ProfileRef
import dev.picasso.contracts.v1.ProgressBasis
import dev.picasso.contracts.v1.ProgressKind
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.Reference
import dev.picasso.contracts.v1.FailureClass
import dev.picasso.contracts.v1.StateMessage
import dev.picasso.contracts.v1.TaskSnapshot
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.TaskTransition
import dev.picasso.contracts.wire.ContractIdentity
import dev.picasso.contracts.wire.TaskStates
import dev.picasso.profile.ProfileDocument
import dev.picasso.capability.CapabilityProjection
import dev.picasso.capability.SiteReferences
import dev.picasso.capability.PreconditionCheck
import dev.picasso.uplink.Publication
import dev.picasso.uplink.Publisher
import dev.picasso.uplink.Topics
import dev.picasso.uplink.report.SiteNameSummary
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * 계약 뒤에 선 기체 하나 — 어댑터 하나, 프로파일 하나, 그리고 계약이 요구하는 **기록**(태스크 갱신 로그·이벤트 버퍼·
 * 헤더 열).
 *
 * ## 미믹과 무엇이 같고 무엇이 다른가
 *
 * 계약 쪽 모양은 미믹과 같아야 한다 — 같은 `WatchTask` 로그, 같은 `sequence` 축, 같은 헤더 열(§5.5). 소비자가 미믹과
 * 실물을 **엔드포인트만 바꿔** 쓰는 것이 이 저장소의 주장이고, 그 주장이 참이려면 이 층이 미믹의 계약 거동을 그대로
 * 재현해야 한다. 다른 것은 **상태의 출처**다: 미믹은 프로파일과 상태기계가 상태를 만들고, 여기는 어댑터가 로봇에게서
 * 읽어 온다. 그래서 이 클래스에는 시간이 없다 — [pump] 가 어댑터에 묻고, 달라진 것만 적는다.
 *
 * ## 펌프
 *
 * 스레드가 없다. 모든 RPC 가 [pump] 를 먼저 부르고(미믹의 `settle` 과 같은 이유 — 그러지 않으면 열린 `WatchTask` 가
 * 다른 RPC 가 만든 전이를 놓친다), 운영 배치에서는 스케줄러가 부른다. 시각은 [clock] 로만 읽는다. 배치에 스케줄러가
 * 있으면 읽기 RPC 는 펌프를 건너뛸 수 있다([AdapterHost.pumpOnRead]).
 *
 * 펌프 한 번은 세 단계다(§15.268) — [plan] 이 무엇을 읽을지 정하고, [read] 가 어댑터에 묻고, [apply] 가 적는다.
 * [read] 는 기록을 안 만지므로 호스트 잠금 밖에서 돌 수 있다. [apply] 는 그 사이에 다른 손이 같은 태스크를 적었으면
 * 그 읽기를 버린다 — 읽기를 시작할 때의 로그 길이가 그 판정이다.
 *
 * **기록은 잠그지 않는다.** RPC 스레드들과 스케줄러 스레드를 줄 세우는 것은 [AdapterHost] 의 잠금 하나다 —
 * 그래서 배치는 [pump] 를 직접 부르지 않고 [AdapterHost.pump] 를 부른다. **어댑터 호출은 [southbound] 아래로 간다** —
 * [adapter] 가 그 잠금을 쥐고 부르는 껍질이다. 호스트 잠금 밖에서 도는 [read] 와 잠금 아래의 명령이 같은 링크를
 * 동시에 부르지 않게 한다(§15.106).
 *
 * ## 발행 (§3.5 · §4.7)
 *
 * 세 스트림이 미믹과 같은 토픽·같은 헤더 열로 나간다 — `event` 는 전이와 결함(적힐 때마다), `state` 는 현재값(프로파일의
 * 최대 발행 간격마다, 펌프가 판정), `connection` 은 retain 으로 기동 때 `ONLINE`·정상 종료 때 `OFFLINE`(끊기면 브로커의
 * Last Will 이 `CONNECTION_BROKEN` 을 대신 낸다 — 그것은 [Publisher] 구현의 일이다). **`sequence` 축은 기체 단위 하나**이고
 * 셋이 함께 쓴다(§5.5 의 발행 열). 발행이 막히면 이벤트는 재생 버퍼에 남고 다음 발행 때 순서대로 밀린다(§10.6 과 같은 규칙).
 * 레지스트리 적재는 이 발행을 감싼 `IngestBridge` 가 한다 — 이 클래스는 그것을 모른다.
 *
 * ## 이 층이 하지 않는 것
 *
 * 판단. 어댑터가 거절하면 거절이고 로봇이 실패라 하면 실패다. 무엇을 할지는 계약 소비자(미들웨어)의 일이다.
 */
class HostedRobot(
    val robotId: String,
    val document: ProfileDocument,
    adapter: RobotAdapter,
    /** 발행이 나갈 곳. 붙이지 않으면 아무 데도 안 나간다 — 미믹과 같은 기본값(§15.30). */
    private val publisher: Publisher = Publisher.NONE,
    /** §5.5 토픽의 두 번째 레벨. 헤더에는 없다. */
    val site: String = "default",
    /**
     * 자리 이름의 결속 정본에 묻는 자리(§15.155). **기본값은 «안 붙였다» 이고 그것도 결정이다** — 붙이지
     * 않은 배치에서는 판 검사가 없고, 없다는 사실이 [ActiveMap.NotConfigured] 로 드러난다.
     */
    private val bindings: SiteBindingSource = SiteBindingSource.None,
    /** 마지막 인자다 — 시험이 후행 람다로 시계를 준다. */
    private val clock: () -> Instant = { Instant.now() },
) {
    /**
     * 사우스바운드 링크의 잠금. 혼자 서면 이 기체의 것이고, [AdapterHost] 가 들면 그 호스트의 기체 전부가 하나를 같이
     * 쓴다 — 플릿 뒤 기체들은 링크 하나를 같이 쓰기 때문이다(§15.106).
     */
    @Volatile
    internal var southbound: Any = Any()

    /**
     * 기록의 잠금. 혼자 서면 이 기체의 것이고, [AdapterHost] 가 들면 그 호스트의 잠금이다. 명령([start]·[manipulate])은 장부를
     * 보고 적을 때만 이것을 쥐고, 어댑터에 보내는 동안은 [southbound] 만 쥔다(§15.269). 잠금 순서는 언제나 이것 다음 링크다.
     */
    @Volatile
    internal var host: Any = Any()

    /** [host] 아래에서 [block] 을 돈다. 호스트 잠금을 쥐지 않고 들어오는 명령 RPC 가 응답 머리말과 핸들을 이것으로 읽는다. */
    internal fun <T> recorded(block: () -> T): T = synchronized(host, block)

    /** 어댑터. 모든 호출이 [southbound] 아래로 간다([SouthboundSerialized]). */
    val adapter: RobotAdapter = SouthboundSerialized(adapter) { southbound }

    val capability: Capability = CapabilityProjection.of(document)

    /**
     * 세션 — 기동 시각(밀리초)과 프로세스 카운터. 카운터만 쓰면 프로세스를 다시 띄울 때마다 같은 값이 되풀이되어
     * 소비자가 재시작을 못 보고 낡은 커서로 새 세션의 앞 이벤트를 건너뛴다(§15.259). 끝에 **프로세스마다 한 번 뽑는 난수**를
     * 붙인다(§15.297, §15.21) — 기동 시각과 카운터만으로는 같은 밀리초에 다시 뜨거나 시계가 되감기면 앞 세션이
     * 되풀이된다. 이것은 현장 호스트라 미믹과 달리 결정성(§12.1)을 지킬 까닭이 없고, 한 프로세스 안에서는 그 값이 같아
     * 주입한 시계를 고정한 시험은 여전히 기동 시각과 카운터로 세션을 가른다.
     *
     * **불변이 아니다.** 재생 버퍼가 넘쳐 **못 보낸** 이벤트를 버렸을 때 새로 발급한다([emit]) — 그 구간은 소비자에게
     * 영영 안 가므로, 세션을 바꿔 스냅샷부터 다시 세우게 하는 것이 유일하게 정직한 답이다(§10.6). 단절만으로 바꾸면
     * 버퍼링이 무의미해지므로 **넘칠 때만** 바꾼다.
     */
    var sessionId: String = newSessionId()
        private set

    /** 프로파일이 바뀌지 않는 한 1 이다 — 이 호스트는 아직 런타임 능력 변경을 안 낸다. */
    val capabilityEpoch: Long = 1

    private val eventIds = AtomicLong()
    private val nextSequence = AtomicLong()
    private val buffer = ArrayDeque<Event>()

    /** 재생 버퍼에서 실제로 버려진 가장 높은 번호. 아직 아무것도 안 버렸으면 널. */
    var evictedUpTo: Long? = null
        private set

    private val book = linkedMapOf<String, HostedTask>()
    private var current: HostedTask? = null

    /** 마지막 [pump] 에서 결함을 봤는가. 거짓이면 [faults] 는 낡은 것이다 — 스냅샷은 그때 결함을 비우고 `NOT_OBSERVABLE` 이다. */
    var faultsObservable: Boolean = true
        private set

    var faults: Map<String, Fault> = emptyMap()
        private set

    /**
     * 마지막 [pump] 에서 읽은 이 기체의 손 — 계약 `GetSnapshotResponse.hold`(0.11.0). 결함([faults])과 같이 펌프가 링크 잠금 아래에서 읽어 두고
     * 스냅샷은 그 값을 싣는다 — **스냅샷은 남쪽을 기다리지 않는다**(명령·조회가 링크를 쥔 동안에도 바로 답한다). 펌프마다 다시 읽으므로 C2
     * (§15.146)가 버린 «다시 볼 길 없는 종착 파지» 가 아니다. 접수 전 사전 조건([precondition])과 같은 관측원이다. **못 읽으면 관측 불가다** —
     * 빈손이 아니고 스냅샷을 오류로 돌리지도 않는다(§15.263 과 같은 태도). 첫 펌프 전에도 관측 불가다.
     */
    var handHold: HoldState = HoldObservation.NotObservable("아직 손을 읽지 않았다").toProto()
        private set

    /** 손을 읽는다. 링크 잠금 아래에서 부른다. 읽기가 던지면 관측 불가로 접는다 — 펌프의 다른 읽기를 무너뜨리지 않는다. */
    private fun readHold(): HoldState = try {
        adapter.hold().toProto()
    } catch (e: Exception) {
        HoldObservation.NotObservable("파지를 못 읽었다: ${e.message ?: e::class.simpleName}").toProto()
    }

    /** 갱신이 적힐 때마다 부른다 — `WatchTask` 스트림이 여기 매달린다. */
    internal val onUpdate = mutableListOf<(HostedTask, TaskUpdate) -> Unit>()

    /**
     * §4.7 의 연결 상태. 호스트가 떠 있고 어댑터가 답하면 `ONLINE` 이다 — 남쪽 링크의 단절은 어댑터가 결함·거절로 말하고,
     * 이 값의 `HIBERNATING`·`CONNECTION_BROKEN` 은 이 호스트가 아직 안 낸다(앞은 낼 이유가 없고 뒤는 브로커의 몫이다).
     */
    var connectionState: ConnectionState = ConnectionState.CONNECTION_STATE_ONLINE
        private set

    /** 아직 브로커에 못 보낸 첫 `sequence`. 없으면 전부 나갔다 — 미믹의 `EventStream.unsentFrom` 과 같은 표현이다. */
    var unsentFrom: Long? = null
        private set

    private var lastStateAt: Instant? = null

    /** 생존 보고에 실을 것. 둘 다 `null` 일 수 있다 — 소프트웨어는 못 읽는다, 요약은 못 물어봤다는 뜻이다([HostUplink.siteNames]). */
    class LivenessFacts(val software: String?, val siteNames: SiteNameSummary?)

    /**
     * 마지막으로 어댑터에서 읽은 생존 보고의 내용(§15.220). 아직 한 번도 안 읽었으면 `null` 이다. 호스트 잠금 아래에서 적고 읽는다.
     *
     * **발행할 때 어댑터에 묻지 않는다.** 발행은 호스트 잠금 아래에서 오고, 사이트 이름 조회는 사우스바운드 왕복이다(Spot 은
     * 그래프를 받고, Orbit 은 미션 목록을 받는다). 그 자리에서 물으면 링크가 답하지 않는 동안 같은 프로세스의 모든 RPC 가 선다.
     * 그래서 펌프가 상태 발행 차례에 링크 잠금 아래에서 읽어 두고([read]), 생존 보고는 이것을 싣는다. 대가로 보고의 요약은
     * 그 상태 발행 앞의 읽기 값이다.
     */
    var liveness: LivenessFacts? = null
        private set

    /** 태스크 하나의 기록 — 계약의 `WatchTask` 로그. 종착은 래치된다(§4.4). */
    class HostedTask internal constructor(
        val taskId: String,
        revision: Int,
        val skillType: String,
        parameters: Map<String, Any>,
    ) {
        /**
         * **지금 파라미터.** 갱신이 갈고([HostedRobot.update]) 재개·재시도가 이것으로 다시 시작한다 —
         * §4.4 가 `PAUSED`·`RETRIABLE`·`NEEDS_INTERVENTION` 의 갱신을 *갈아 두고 나중에 적용* 이라 한 그것이다.
         */
        var parameters: Map<String, Any> = parameters
            internal set

        /** **갱신이 올린다**(§4.4). 태스크의 신원은 `taskId` 이고 개정판은 그 위에서 바뀐다. */
        var revision: Int = revision
            internal set

        internal val log = mutableListOf<TaskUpdate>()
        val updates: List<TaskUpdate> get() = log.toList()
        val last: TaskUpdate get() = log.last()
        val terminal: Boolean get() = TaskStates.isTerminal(last.state)
    }

    data class TaskUpdate(
        val index: Long,
        val state: TaskState,
        val revision: Int,
        val attempt: Int,
        val progress: Double,
        val partialResult: String,
        val hold: HoldState,
        /** 위 [progress] 가 무엇을 센 것인가 — 계약 0.8.0. 못 재는 기체는 여기서 그렇게 말한다. */
        val progressBasis: ProgressBasis,
        val fault: Fault?,
        val occurredAt: Instant,
    )

    sealed interface StartOutcome {
        data class Accepted(val task: HostedTask) : StartOutcome
        data class Idempotent(val task: HostedTask) : StartOutcome
        data class Rejected(
            val code: RejectionCode,
            val detail: String,
            /** 응답만 보고 무엇이 걸렸는지 알 수 있게 — 사전 조건이면 그 주어. */
            val references: List<Reference> = emptyList(),
        ) : StartOutcome
        /** 계약이 답할 자리가 없는 사정 — gRPC 상태로 나간다. */
        data class Unavailable(val status: io.grpc.Status) : StartOutcome
    }

    fun now(): Instant = clock()

    fun task(taskId: String): HostedTask? = book[taskId]

    val tasks: Collection<HostedTask> get() = book.values

    val events: List<Event> get() = buffer.toList()

    val sequence: Long get() = nextSequence.get()

    // ── 접수 (§4.4 — 같은 (task_id, revision) 은 같은 핸들, 종착은 래치)

    /**
     * 받는다. 세 단계다(§15.269) — 호스트 잠금 아래에서 장부와 배타를 보고 **보낼 자리를 잡고**, 그 잠금을 놓고 링크 잠금만 쥔 채
     * 어댑터에 보내고, 다시 호스트 잠금 아래에서 적는다. 그래서 보내는 동안 이 호스트의 기록을 읽는 RPC 는 기다리지 않는다.
     *
     * 보낼 자리는 기체마다 하나다([sending]). 그 사이 같은 `(task_id, revision)` 이 또 오면 같은 결과를 기다려 받는다(합류).
     * 다른 접수는 기다리지 않고 거부한다 — 실제 하드웨어는 배타적 제어 모델이다(§4.9).
     */
    fun start(taskId: String, revision: Int, skillType: String, parameters: Map<String, Any>): StartOutcome {
        var preconditionChecked = false
        while (true) {
            when (val step = recorded { admit(taskId, revision, skillType, parameters, preconditionChecked) }) {
                is Admission.Decided -> return step.outcome
                is Admission.Join -> return joined(step.sending)
                Admission.Precondition -> {
                    // 사전 조건의 관측은 링크를 쓴다 — 호스트 잠금 밖에서 보고 다시 들어간다.
                    precondition(skillType)?.let { return it }
                    preconditionChecked = true
                }
                is Admission.Send -> return step.send()
            }
        }
    }

    /** [admit] 이 정한 다음 걸음. */
    private sealed interface Admission {
        class Decided(val outcome: StartOutcome) : Admission
        class Join(val sending: Sending) : Admission
        data object Precondition : Admission
        class Send(val send: () -> StartOutcome) : Admission
    }

    /** 보내는 중인 명령 하나. [key] 가 같은 접수는 [done] 을 기다려 같은 결과를 받는다. */
    private class Sending(val key: Any) {
        val done = CompletableFuture<Result<Any?>>()
    }

    private data class StartKey(val taskId: String, val revision: Int)

    /**
     * 보내는 중인 명령. 호스트 잠금 아래에서만 만진다. 있는 동안 펌프는 태스크 전이를 적지 않는다([apply]) — 그 사이 적는 손은
     * 그 명령의 확정 하나다.
     */
    private var sending: Sending? = null

    /**
     * 나갔는지 모르는 접수와 그 답(§15.220). 호스트 잠금 아래에서만 만진다. 같은 접수가 다시 오면 **보내지 않고** 같은 답을 준다 —
     * 다시 보내면 로봇에 같은 일을 두 번 시킬 수 있다. 장부에는 안 오른다 — 받았는지 모르는 것을 도는 태스크로 적지 않는다.
     */
    private val inDoubt = mutableMapOf<StartKey, StartOutcome>()

    /** 장부·배타·검증을 보고 다음 걸음을 정한다. 호스트 잠금 아래에서 부른다. 어댑터를 부르지 않는다. */
    private fun admit(
        taskId: String,
        revision: Int,
        skillType: String,
        parameters: Map<String, Any>,
        preconditionChecked: Boolean,
    ): Admission {
        val key = StartKey(taskId, revision)
        sending?.takeIf { it.key == key }?.let { return Admission.Join(it) }
        inDoubt[key]?.let { return Admission.Decided(it) }

        book[taskId]?.let { existing ->
            return when {
                revision == existing.revision -> Admission.Decided(StartOutcome.Idempotent(existing))
                revision < existing.revision -> Admission.Decided(
                    StartOutcome.Rejected(
                        RejectionCode.REJECTION_CODE_OUTDATED_REVISION,
                        "받은 revision=$revision, 현재=${existing.revision}",
                    ),
                )
                existing.terminal -> Admission.Decided(
                    StartOutcome.Rejected(
                        RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
                        "${existing.taskId} 은 이미 ${existing.last.state} 다 — 종료는 되돌아가지 않는다(§4.4)",
                    ),
                )
                else -> update(existing, key, revision, skillType, parameters)
            }
        }

        // **보내는 중이면 사전 조건보다 먼저 거부한다.** 사전 조건의 관측도 링크를 쓰므로, 보려면 그 명령의 남쪽 왕복을
        // 기다려야 한다.
        busy()?.let { return Admission.Decided(it) }

        // 사전 조건이 배타 실행 검사보다 먼저다 — 도는 태스크가 든 채인데 새 요청이 오면 «이미 도는 태스크» 보다
        // «무엇이 걸렸나» 가 계약이다. 미믹은 배타 검사가 없어 그렇게 답하고, 둘이 같은 말을 해야 한다(리뷰 C9).
        if (!preconditionChecked && preconditioned(skillType)) return Admission.Precondition

        current?.takeIf { !it.terminal }?.let {
            return Admission.Decided(
                StartOutcome.Rejected(
                    RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
                    "이미 도는 태스크가 있다: ${it.taskId} — 실제 하드웨어는 배타적 제어 모델이다(§4.9)",
                ),
            )
        }

        validate(skillType, parameters)?.let { return Admission.Decided(it) }
        // **푸는 것보다 먼저 묻는다.** 뒤에 두면 이미 옛 좌표로 움직인 뒤에 판을 보게 된다.
        binding(skillType, parameters)?.let { return Admission.Decided(it) }

        val reserved = reserve(key)
        return Admission.Send { sendStart(reserved, taskId, revision, skillType, parameters) }
    }

    /** 보내는 중인 명령이 있으면 거부. 기다리지 않는다 — 기다리면 그 명령의 남쪽 왕복만큼 이 RPC 도 선다. */
    private fun busy(): StartOutcome.Rejected? = sending?.let {
        StartOutcome.Rejected(
            RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
            "이 기체에 보내는 중인 명령이 있다 — 그 답이 난 뒤 다시 요청하라(§4.9)",
        )
    }

    private fun reserve(key: Any): Sending = Sending(key).also { sending = it }

    /** 합류 — 같은 접수의 결과를 기다려 받는다. 어떤 잠금도 쥐지 않고 기다린다. */
    private fun joined(s: Sending): StartOutcome =
        when (val outcome = s.done.get().getOrThrow() as StartOutcome) {
            is StartOutcome.Accepted -> StartOutcome.Idempotent(outcome.task)
            else -> outcome
        }

    /**
     * [link] 를 호스트 잠금 밖에서 돌고(어댑터를 부르는 동안 링크 잠금을 쥔다), 그것이 돌려준 확정을 호스트 잠금 아래에서 돈다.
     * 확정 전에 자리가 그 명령의 것인지 보고, 같은 잠금 구간에서 자리를 비운다. 합류한 쪽은 같은 결과를 받는다.
     *
     * [link] 가 던지면 자리를 비우고 그 예외를 내며, 같은 요청이 다시 오면 다시 보낸다. 어댑터의 링크 오류도 보내지 못한 것으로
     * 읽는다. 보냈는지 모르는 것은 어댑터가 [Refusal.LINK_IN_DOUBT] 로 가른다 — 그 답은 [inDoubt] 에 남는다(§15.220).
     */
    private fun <T> sent(s: Sending, link: () -> () -> T): T {
        val result = runCatching {
            val commit = link()
            recorded {
                check(sending === s) { "보낼 자리가 그 명령의 것이 아니다" }
                try {
                    commit()
                } finally {
                    sending = null
                }
            }
        }
        if (result.isFailure) recorded { if (sending === s) sending = null }
        s.done.complete(result)
        return result.getOrThrow()
    }

    /**
     * 접수를 보낸다. 받았으면 적을 것 — 접수의 관측과, 어댑터가 지금 말하는 상태가 접수와 다르면 그 상태와 관측 — 도 같은 링크
     * 잠금 구간에서 읽는다. 그 사이 펌프의 읽기가 끼지 않는다.
     */
    private fun sendStart(
        s: Sending,
        taskId: String,
        revision: Int,
        skillType: String,
        parameters: Map<String, Any>,
    ): StartOutcome = sent(s) {
        val (acceptance, seen) = synchronized(southbound) {
            val acceptance = adapter.accept(taskId, skillType, parameters, clock())
            acceptance to (acceptance as? Acceptance.Accepted)?.let {
                val first = observe(TaskState.TASK_STATE_ACCEPTED)
                val then = adapter.state.takeIf { it != TaskState.TASK_STATE_UNSPECIFIED && it != TaskState.TASK_STATE_ACCEPTED }
                Triple(first, then, then?.let(::observe))
            }
        }
        val commit: () -> StartOutcome = {
            when (acceptance) {
                is Acceptance.Accepted -> {
                    val (first, then, thenObserved) = checkNotNull(seen)
                    val task = HostedTask(taskId, revision, skillType, parameters)
                    book[taskId] = task
                    current = task
                    record(task, TaskState.TASK_STATE_ACCEPTED, first)
                    if (then != null) record(task, then, checkNotNull(thenObserved))
                    StartOutcome.Accepted(task)
                }
                is Acceptance.Refused -> refused(acceptance).also {
                    if (acceptance.reason == Refusal.LINK_IN_DOUBT) inDoubt[StartKey(taskId, revision)] = it
                }
            }
        }
        commit
    }

    /**
     * **도는 태스크의 갱신**(§4.4) — 파라미터를 갈고 태스크의 신원은 그대로 둔다.
     *
     * 벤더 쪽에서 이것은 `Halt` → `Reset` → 새 파라미터로 `Start` 의 **합성**이고, 그 셋을 다 드는 기체만 갱신을
     * 할 수 있다. 어댑터가 못 든다 하면 계약의 `UPDATE_UNSUPPORTED` 다 — `INVALID_TRANSITION` 에 접으면
     * 소비자가 *지금 상태가 안 받는다* 와 *이 로봇은 원래 못 한다* 를 구별하지 못한다(계약 0.7.0).
     *
     * ## 상태별로 다르다 — 그리고 셋은 아직 안 든다
     *
     * `ACCEPTED`·`RUNNING` 만 든다. **실물에서 `ACCEPTED` 는 이미 로봇에 명령이 나간 상태다** — 어댑터의
     * `accept` 가 곧 벤더 호출이므로, 미믹의 *아직 시작 전* 과 달라 여기서도 합성이 필요하다.
     *
     * `PAUSED`·`RETRIABLE`·`NEEDS_INTERVENTION` 은 §4.4 가 *파라미터만 갈아 두고 재개·재시도 때 적용하라* 고
     * 하는데, 그러려면 호스트가 대기 파라미터를 들고 [RobotAdapter.resume]·[RobotAdapter.retry] 에 실어야 하고
     * 그 자리가 포트에 없다. **로봇이 못 하는 것이 아니므로 `UPDATE_UNSUPPORTED` 로 답하지 않는다.**
     */
    private fun update(task: HostedTask, key: StartKey, revision: Int, skillType: String, parameters: Map<String, Any>): Admission {
        if (skillType != task.skillType) {
            return Admission.Decided(
                StartOutcome.Rejected(
                    RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
                    "갱신은 파라미터를 갈는 것이지 스킬을 바꾸는 것이 아니다: ${task.skillType} → $skillType — 새 task_id 로 요청하라",
                ),
            )
        }
        val state = task.last.state
        if (state !in UPDATABLE_STATES && state !in DEFERRED_UPDATE_STATES) {
            return Admission.Decided(
                StartOutcome.Rejected(
                    RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
                    "$state 에서는 파라미터를 바꿀 자리가 없다 — 정리 중이거나 종료이다(§4.4)",
                ),
            )
        }
        validate(skillType, parameters)?.let { return Admission.Decided(it) }
        busy()?.let { return Admission.Decided(it) }

        // **지금 안 보내는 상태가 있다**(§4.4). 멈췄거나 재시도를 기다리거나 사람을 기다리는 태스크는
        // 파라미터만 갈아 두고 그 상태를 유지한다 — 재개·재시도가 그때 새 파라미터로 다시 시작한다.
        // 로봇에 아무것도 안 보내므로 **어댑터가 갱신을 못 들어도 이 경로는 된다.** 적을 관측은 그래도 링크로 읽는다.
        val deferred = state in DEFERRED_UPDATE_STATES
        val reserved = reserve(key)
        return Admission.Send {
            sent(reserved) {
                val (answer, observed) = synchronized(southbound) {
                    val answer = if (deferred) Applied.Ok else adapter.update(task.taskId, skillType, parameters, clock())
                    answer to (if (answer == Applied.Ok) observe(state) else null)
                }
                val commit: () -> StartOutcome = {
                    when (answer) {
                        Applied.Ok -> applied(task, revision, parameters, state, checkNotNull(observed))
                        is Applied.Refused -> StartOutcome.Rejected(RejectionCode.REJECTION_CODE_UPDATE_UNSUPPORTED, answer.detail)
                    }
                }
                commit
            }
        }
    }

    /** 갱신을 적는다 — 파라미터를 갈고 `revision` 을 올리고 **로그 한 줄**(§4.4). 상태는 안 바뀐다. */
    private fun applied(task: HostedTask, revision: Int, parameters: Map<String, Any>, state: TaskState, observed: Observed): StartOutcome {
        task.parameters = parameters
        task.revision = revision
        record(task, state, observed)
        return StartOutcome.Accepted(task)
    }

    /** 조작 하나의 답. */
    sealed interface Manipulated {
        /** 모르는 태스크다. */
        data object Unknown : Manipulated

        /** 이미 종착했다(§4.4 의 래치). */
        data class Latched(val detail: String) : Manipulated

        /** 어댑터의 답과, 적고 난 뒤 그 태스크의 상태. */
        data class Done(val applied: Applied, val state: TaskState) : Manipulated
    }

    private sealed interface Turn {
        class Answer(val manipulated: Manipulated) : Turn
        class Wait(val sending: Sending) : Turn
        class Go(val sending: Sending, val task: HostedTask, val last: TaskState, val parameters: Map<String, Any>) : Turn
    }

    /**
     * 조작 넷(일시정지·재개·취소·재시도)의 공통 — 접수와 같은 세 단계다(§15.269). 보내는 중인 명령이 있으면 **그것이 끝날 때까지
     * 기다린다**. 취소를 일시적인 사정으로 거부하지 않으려는 것이고, 기다리는 동안 호스트 잠금도 링크 잠금도 쥐지 않는다.
     *
     * 어댑터가 받았거나 상태로 답할 거절이면 어댑터가 지금 말하는 상태를 같은 링크 잠금 구간에서 읽어 함께 적는다.
     *
     * @param send 지금 파라미터를 받아 어댑터에 시킨다 — 재개·재시도는 멈춘 동안 갈아 둔 파라미터로 다시 시작한다(§4.4).
     */
    internal fun manipulate(taskId: String, send: (Map<String, Any>) -> Applied): Manipulated {
        while (true) {
            val turn = recorded {
                val task = book[taskId] ?: return@recorded Turn.Answer(Manipulated.Unknown)
                if (task.terminal) {
                    return@recorded Turn.Answer(
                        Manipulated.Latched("${task.taskId} 은 이미 ${task.last.state} 다 — 종료는 되돌아가지 않는다(§4.4)"),
                    )
                }
                sending?.let { return@recorded Turn.Wait(it) }
                Turn.Go(reserve(Any()), task, task.last.state, task.parameters)
            }
            when (turn) {
                is Turn.Answer -> return turn.manipulated
                // 앞 명령이 끝나기를 기다린다. 그 결과는 그 명령의 것이라 여기서 보지 않는다. 그 사이 끝난 태스크를 읽고 다시 본다.
                is Turn.Wait -> {
                    runCatching { turn.sending.done.get() }
                    pumpReleased()
                }
                is Turn.Go -> return sent(turn.sending) {
                    val (applied, then) = synchronized(southbound) {
                        val applied = send(turn.parameters)
                        val now = if (syncsAfter(applied)) {
                            adapter.state.takeIf { it != TaskState.TASK_STATE_UNSPECIFIED && it != turn.last }
                        } else {
                            null
                        }
                        applied to now?.let { it to observe(it) }
                    }
                    val commit: () -> Manipulated = {
                        if (then != null && current === turn.task && !turn.task.terminal) record(turn.task, then.first, then.second)
                        Manipulated.Done(applied, turn.task.last.state)
                    }
                    commit
                }
            }
        }
    }

    /**
     * 프로파일이 선언한 것에 대고 본다 — 선언 안 한 스킬, 빠진 필수 파라미터, 문자열 길이. **값의 범위와 허용 값은
     * 어댑터·로봇이 답한다**(미믹의 §10.4 ③ 검사를 여기서 다 흉내내지 않는다 — 여기는 로봇이 있다).
     */
    /**
     * 프로파일이 선언한 사전 조건(설계안 §3) — **남쪽 호출 전에** 판정한다. 관측은 [RobotAdapter.hold] 이고
     * 평가기는 미믹과 같은 `capability` 의 것이다(§15.100). 조건이 없는 스킬은 묻지도 않는다 — 선언하지 않은
     * 조건은 제약 없음이다. 갱신([update])에는 걸지 않는다: 도는 태스크 자신이 들고 있는 것이 정상이다.
     */
    private fun preconditioned(skillType: String): Boolean =
        (capability.skillsList.firstOrNull { it.skillType == skillType }?.preconditionsCount ?: 0) > 0

    private fun precondition(skillType: String): StartOutcome.Rejected? {
        val declared = capability.skillsList.firstOrNull { it.skillType == skillType } ?: return null
        if (declared.preconditionsCount == 0) return null
        val violations = PreconditionCheck.check(declared, adapter.hold().toProto())
        if (violations.isEmpty()) return null
        return StartOutcome.Rejected(
            RejectionCode.REJECTION_CODE_PRECONDITION_UNMET,
            PreconditionCheck.rejectionDetail(violations),
            PreconditionCheck.subjects(violations).map {
                Reference.newBuilder().setKey(Reference.Key.KEY_PRECONDITION_SUBJECT).setValue(it).build()
            },
        )
    }

    /**
     * 자리 이름이 **지금 판에서 유효한가**(§15.155). 어느 칸이 자리 이름인지는 계약 카탈로그가 말하고
     * (`is_site_reference`), 그 이름이 어느 판에서 등록된 것인지는 결속 정본이 말한다.
     *
     * **여기서 이름을 풀지 않는다.** 푸는 것은 기체의 일이고(ADR 35), 이 층이 묻는 것은 풀어도 되는지다.
     * 거절은 [refused] 한 곳을 지난다 — 어휘 대응표를 두 벌로 두지 않는다.
     */
    private fun binding(skillType: String, parameters: Map<String, Any>): StartOutcome? {
        val names = SiteReferences.of(skillType).mapNotNull { parameters[it] as? String }
        val refusal = SiteBindingCheck.refusalFor(names, bindings) ?: return null
        return refused(Acceptance.Refused(refusal, SiteBindingCheck.detailFor(refusal, names, bindings)))
    }

    private fun validate(skillType: String, parameters: Map<String, Any>): StartOutcome.Rejected? {
        val skill = document.skills.firstOrNull { it.skillType == skillType }
            ?: return StartOutcome.Rejected(
                RejectionCode.REJECTION_CODE_SKILL_ABSENT,
                "프로파일이 선언하지 않은 스킬이다: $skillType (선언: ${document.skills.map { it.skillType }})",
            )
        val missing = skill.parameters.filter { !it.optional && it.key !in parameters }.map { it.key }
        if (missing.isNotEmpty()) {
            return StartOutcome.Rejected(RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING, "필수 파라미터가 없다: $missing")
        }
        parameters.forEach { (key, value) ->
            if (value is String) {
                val limit = skill.parameters.firstOrNull { it.key == key }?.maxLength ?: document.maxStringLength
                if (value.length > limit) {
                    return StartOutcome.Rejected(RejectionCode.REJECTION_CODE_LIMIT_EXCEEDED, "$key 의 길이 ${value.length} 이 한도 $limit 를 넘는다")
                }
            }
        }
        return null
    }

    /**
     * 어댑터의 거절 → 계약. **어휘가 하나씩 대응하지는 않는다** — 계약의 거절 코드는 소비자의 요청이 틀린 경우를 위해
     * 만들어졌고, 어댑터의 거절에는 *로봇이 지금 못 받는다* 도 있다. 그런 것은 `INVALID_TRANSITION` 에 사정을 붙인다
     * (§15.98 의 정직 메모 — 계약에 그 자리가 없었다. 0.9.0 이 사전 조건 위반에는 `PRECONDITION_UNMET` 을 줬고, 나머지는 그대로다). 우리 쪽 배선이 틀린 것(신원)과 남쪽이 안 닿는 것(링크)은 계약이 답할
     * 일이 아니라 gRPC 상태다.
     */
    private fun refused(refusal: Acceptance.Refused): StartOutcome = when (refusal.reason) {
        Refusal.UNSUPPORTED_SKILL -> StartOutcome.Rejected(RejectionCode.REJECTION_CODE_SKILL_ABSENT, refusal.detail)
        Refusal.PARAMETER_MISSING -> StartOutcome.Rejected(RejectionCode.REJECTION_CODE_REQUIRED_OPTIONAL_MISSING, refusal.detail)
        Refusal.SITE_NAME_UNKNOWN, Refusal.SITE_NAME_AMBIGUOUS, Refusal.SITE_BINDING_ABSENT ->
            StartOutcome.Rejected(RejectionCode.REJECTION_CODE_PARAMETER_INVALID, refusal.detail)
        // 판이 어긋난 것은 «요청이 틀렸다» 가 아니라 «지금은 그 자리로 못 간다» 다 — 소비자는 요청을 고치는
        // 것이 아니라 재등록을 기다린다. 사전 조건의 자리이고, **새 이유 코드를 만들지 않는다**.
        Refusal.SITE_BINDING_STALE_MAP, Refusal.SITE_BINDING_STALE_CALIBRATION ->
            StartOutcome.Rejected(RejectionCode.REJECTION_CODE_PRECONDITION_UNMET, refusal.detail)
        // 못 물어본 것은 소비자의 잘못이 아니라 우리 쪽 상류가 안 닿는 것이다 — LINK_ERROR 와 같은 자리.
        Refusal.SITE_BINDING_UNVERIFIABLE ->
            StartOutcome.Unavailable(io.grpc.Status.UNAVAILABLE.withDescription(refusal.detail))
        Refusal.VENDOR_SURFACE_ABSENT -> StartOutcome.Rejected(RejectionCode.REJECTION_CODE_CAPABILITY_WITHDRAWN, refusal.detail)
        Refusal.ALREADY_RUNNING, Refusal.TERMINAL_LATCHED, Refusal.NO_TASK, Refusal.NO_VENDOR_PRIMITIVE ->
            StartOutcome.Rejected(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, refusal.detail)
        Refusal.VENDOR_REJECTED -> StartOutcome.Rejected(
            // 로봇이 *지금 못 받는다*(PRECONDITION_FAILED)고 답하면 사전 조건의 자리다(계약 0.9.0, §15.144) — 소비자는
            // 요청을 고치는 게 아니라 기다리거나 앞 단위를 바꾼다. 다른 분류는 여전히 INVALID_TRANSITION 에 사정을 붙인다.
            if (refusal.failureClass == FailureClass.FAILURE_CLASS_PRECONDITION_FAILED) RejectionCode.REJECTION_CODE_PRECONDITION_UNMET
            else RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
            "${refusal.detail} [class=${refusal.failureClass.name.removePrefix("FAILURE_CLASS_")}; vendor=${refusal.vendorDetail}]",
        )
        Refusal.CONTROL_AUTHORITY_LOST -> StartOutcome.Rejected(RejectionCode.REJECTION_CODE_INVALID_TRANSITION, refusal.detail)
        Refusal.IDENTITY_UNSET -> StartOutcome.Unavailable(io.grpc.Status.FAILED_PRECONDITION.withDescription(refusal.detail))
        Refusal.LINK_ERROR -> StartOutcome.Unavailable(io.grpc.Status.UNAVAILABLE.withDescription(refusal.detail))
        // `UNAVAILABLE` 은 «다시 보내도 된다» 로 읽힌다. 나갔는지 모르는 것은 그렇게 답하지 않는다.
        Refusal.LINK_IN_DOUBT -> StartOutcome.Unavailable(
            io.grpc.Status.UNKNOWN.withDescription("${refusal.detail} — 같은 접수는 다시 보내지 않는다(§15.220)"),
        )
    }

    // ── 펌프

    /** 어댑터에 묻고 달라진 것을 적는다. 몇 번 불러도 같다 — 상태 발행만 시계가 정한다. */
    fun pump() = apply(read(plan()))

    /**
     * [pump] 와 같되 읽을 자리와 적는 일만 [host] 아래에서 하고 읽는 동안은 링크 잠금만 쥔다. **보내는 중인 명령이 있으면 읽지
     * 않는다** — 그 명령이 링크를 쥐고 있어 읽으려면 그 남쪽 왕복을 기다려야 하고, 그동안 적지도 못한다([apply]).
     */
    internal fun pumpReleased() {
        val plan = recorded { if (sending == null) plan() else null } ?: return
        val reading = read(plan)
        recorded { apply(reading) }
    }

    /** 펌프 한 번이 읽을 자리 — 도는 태스크와 그때의 로그 길이, 그리고 생존 보고의 내용을 함께 읽을지. */
    internal class PumpPlan(val task: HostedTask?, val last: TaskState?, val generation: Int, val liveness: Boolean = false)

    /** 기록할 때 상태와 함께 어댑터에서 읽는 것. [progress] 는 성공 종착이면 묻지 않는다. */
    internal class Observed(val failure: Fault?, val result: String, val progress: ProgressObservation?, val hold: HoldObservation)

    /**
     * [read] 한 번의 답. [ticket] 은 링크 잠금 아래에서 매긴 차례다. [observed] 는 상태가 달라졌을 때만 있다. [liveness] 는
     * [PumpPlan.liveness] 일 때만 있다.
     */
    internal class Reading(
        val plan: PumpPlan,
        val ticket: Long,
        val state: TaskState?,
        val observed: Observed?,
        val faults: FaultObservation,
        val liveness: LivenessFacts? = null,
        val hold: HoldState? = null,
    )

    /** 읽기에 매긴 마지막 차례. [southbound] 아래에서만 만진다. */
    private var readTickets = 0L

    /** 적은 마지막 읽기의 차례. 호스트 잠금 아래에서만 만진다. */
    private var appliedTicket = 0L

    /**
     * 무엇을 읽을지 정한다. 호스트 잠금 아래에서 부른다. 상태를 발행할 차례이거나 생존 보고의 내용을 아직 안 읽었으면 그것도
     * 함께 읽는다 — 상태 발행이 생존 보고를 싣는다(`IngestBridge`).
     */
    internal fun plan(): PumpPlan {
        val task = current?.takeIf { !it.terminal }
        return PumpPlan(task, task?.last?.state, task?.log?.size ?: 0, liveness = liveness == null || stateDue())
    }

    /**
     * 어댑터에 묻는다 — 도는 태스크의 상태, 달라졌으면 함께 적을 것, 결함. **기록을 만지지 않는다.** 링크 잠금을 처음부터
     * 끝까지 쥐므로 그 사이에 명령이 같은 링크를 부르지 않고, 차례는 실제로 읽은 순서다.
     */
    internal fun read(plan: PumpPlan): Reading = synchronized(southbound) {
        val ticket = ++readTickets
        val state = plan.task?.let { adapter.poll(clock()) }
        val observed = state?.takeIf { it != plan.last }?.let(::observe)
        Reading(plan, ticket, state, observed, adapter.faults(), if (plan.liveness) readLiveness() else null, readHold())
    }

    /** 생존 보고의 내용을 어댑터에서 읽는다. 링크 잠금 아래로 간다 — 호스트 잠금을 쥐고 부르지 않는다. */
    private fun readLiveness(): LivenessFacts = LivenessFacts(adapter.robotSoftware(), HostUplink.siteNames(adapter))

    /**
     * 기동 때 생존 보고의 내용을 읽어 둔다 — 첫 `ONLINE` 보고가 비지 않게 한다. [AdapterHost.start] 가 포트를 열기 전에,
     * 호스트 잠금 밖에서 부른다.
     */
    internal fun primeLiveness() {
        val facts = readLiveness()
        recorded { liveness = facts }
    }

    /**
     * 읽은 것을 적는다. 호스트 잠금 아래에서 부른다.
     *
     * - **뒤 읽기를 이미 적었으면 앞 읽기는 버린다.** 링크 잠금 밖에서 끝난 읽기가 호스트 잠금을 기다리는 동안 명령 경로의
     *   펌프가 더 새 것을 적을 수 있다. 앞 것을 그 뒤에 적으면 결함 표가 옛 값으로 돌아간다.
     * - **읽는 사이 그 태스크에 무엇이 적혔으면 그 상태는 버린다.** 취소가 종착을 적었는데 그 앞에 읽은 전이를 뒤에 붙이면
     *   종착 뒤에 줄이 생긴다. 버린 전이는 잃지 않는다 — 어댑터가 같은 상태를 들고 있어 다음 읽기가 다시 본다.
     * - **보내는 중인 명령이 있으면 그 상태는 버린다**(§15.269). 확정이 그 앞에 읽은 상태로 갱신 한 줄을 적으므로, 그 사이 펌프가
     *   종착을 먼저 적으면 종착 뒤에 줄이 생긴다. 이것도 다음 읽기가 다시 본다.
     */
    internal fun apply(reading: Reading) {
        if (reading.ticket <= appliedTicket) return
        appliedTicket = reading.ticket
        reading.liveness?.let { liveness = it }
        reading.hold?.let { handHold = it }
        val task = reading.plan.task
        val observed = reading.observed
        if (task != null && observed != null && current === task && task.log.size == reading.plan.generation && sending == null) {
            record(task, checkNotNull(reading.state), observed)
        }
        when (val seen = reading.faults) {
            is FaultObservation.Observed -> {
                faultsObservable = true
                val next = seen.faults.associateBy(::faultKey)
                (next.keys - faults.keys).forEach { emit(faultEvent(next.getValue(it), cleared = false)) }
                (faults.keys - next.keys).forEach { emit(faultEvent(faults.getValue(it), cleared = true)) }
                faults = next
            }
            is FaultObservation.NotObservable -> faultsObservable = false
        }
        publishStateIfDue()
    }

    // ── 발행 (§4.7 — 상태·이벤트·연결)

    /**
     * §7.2 의 최대 발행 간격을 넘겼으면 현재값을 발행한다. **스케줄러가 아니라 펌프가 판정한다** — 시각은 [clock] 하나다.
     * `ONLINE` 이 아니면 안 나간다: 그 침묵이 §4.7 이 연결 스트림으로 가르라고 한 바로 그것이다.
     *
     * @return 실제로 발행했으면 참.
     */
    fun publishStateIfDue(): Boolean {
        if (!stateDue()) return false
        publishState()
        return true
    }

    /** `ONLINE` 이고 최대 발행 간격을 넘겼는가. */
    private fun stateDue(): Boolean {
        if (connectionState != ConnectionState.CONNECTION_STATE_ONLINE) return false
        val due = lastStateAt?.plusSeconds(document.publishIntervalMaxSeconds.toLong())
        return due == null || !clock().isBefore(due)
    }

    /** 현재값 — 태스크 스냅샷과 활성 결함. 스킬 상태기계가 없으므로 `skills` 는 비운다(어댑터는 스킬 상태를 안 낸다). */
    fun publishState() {
        lastStateAt = clock()
        val sequence = nextSequence.getAndIncrement()
        val message = StateMessage.newBuilder()
            .setHeader(publishHeader(StateMessage.getDescriptor(), sequence))
            .addAllTasks(taskSnapshots())
            .addAllFaults(faults.values)
            .build()
        send(Publication(topic(Topics.Stream.state), message, sequence))
    }

    fun taskSnapshots(): List<TaskSnapshot> = book.values.map {
        TaskSnapshot.newBuilder().setTaskId(it.taskId).setSkillType(it.skillType).setState(it.last.state).setRevision(it.revision).setAttempt(0).build()
    }

    /**
     * 기동 발행 — **포트가 열린 뒤** [AdapterHost.start] 가 부른다. 포트가 안 열렸는데 온라인이라 알리면 소비자가 붙을 수
     * 없는 기체를 살아 있다고 읽는다(미믹의 `MimicServer.start` 와 같은 순서).
     */
    fun announceOnline() = publishConnection(connectionState)

    /**
     * 정상 종료 — `OFFLINE` 을 retain 으로 남기고 침묵한다. Last Will 은 **끊겼을 때만** 브로커가 내므로, 여기서 안 내면
     * 정상 종료가 `ONLINE` 을 retain 에 남긴 채 사라진다.
     */
    fun goOffline() {
        if (connectionState == ConnectionState.CONNECTION_STATE_OFFLINE) return
        connectionState = ConnectionState.CONNECTION_STATE_OFFLINE
        publishConnection(connectionState)
    }

    private fun publishConnection(state: ConnectionState) {
        val sequence = nextSequence.getAndIncrement()
        val message = ConnectionMessage.newBuilder().setHeader(publishHeader(ConnectionMessage.getDescriptor(), sequence)).setState(state).build()
        send(Publication(topic(Topics.Stream.connection), message, sequence, retained = true))
    }

    /**
     * 발행한다. 못 보내고 있던 이벤트가 있으면 **그것부터 순서대로** 민다. 이벤트는 재생 버퍼가 들고 있으므로 따로 큐가 없다
     * (§10.6 — 미믹과 같은 규칙); 상태·연결은 현재값이라 못 보내면 버리고 다음 것이 대신한다.
     */
    private fun send(publication: Publication) {
        if (unsentFrom != null) {
            val drained = drain()
            // **이벤트는 여기서 또 내지 않는다.** 이미 버퍼에 있어 드레인이 함께 밀었다 — 둘 다 하면 마지막 하나가 두 번
            // 나간다. 미믹이 실측으로 걸렸고(EventStream.send) 여기서도 시험이 같은 것을 잡았다.
            if (publication.message is Event || !drained) return
        }
        try {
            publisher.publish(publication)
        } catch (e: Exception) {
            if (publication.message is Event && unsentFrom == null) unsentFrom = publication.sequence
        }
    }

    private fun drain(): Boolean {
        val from = unsentFrom ?: return true
        buffer.filter { it.header.sequence >= from }.forEach { pending ->
            try {
                publisher.publish(Publication(topic(Topics.Stream.event), pending, pending.header.sequence))
            } catch (e: Exception) {
                unsentFrom = pending.header.sequence
                return false
            }
        }
        unsentFrom = null
        return true
    }

    private fun topic(stream: Topics.Stream): String = Topics.robot(ContractIdentity.major, site, robotId, stream)

    private fun publishHeader(schema: Descriptors.Descriptor, sequence: Long): MessageHeader =
        header(schema).toBuilder().setSequence(sequence).build()

    /** 조작 뒤 — 시간을 안 흘리고 어댑터가 지금 말하는 상태만 적는다. */
    internal fun syncCurrent() {
        val task = current ?: return
        if (task.terminal) return
        val state = adapter.state
        if (state != TaskState.TASK_STATE_UNSPECIFIED && state != task.last.state) record(task, state)
    }

    private fun record(task: HostedTask, state: TaskState) = record(task, state, observe(state))

    /** [state] 를 적을 때 함께 실을 것을 어댑터에서 읽는다. 순서는 실패·결과·진행률·파지다. */
    private fun observe(state: TaskState): Observed {
        val failure = if (state in FAILURE_STATES) adapter.failure() else null
        val result = if (state == TaskState.TASK_STATE_SUCCEEDED) adapter.result().orEmpty() else ""
        val progress = if (state == TaskState.TASK_STATE_SUCCEEDED) null else adapter.progress()
        return Observed(failure, result, progress, adapter.hold())
    }

    private fun record(task: HostedTask, state: TaskState, observed: Observed) {
        val from = task.log.lastOrNull()?.state ?: TaskState.TASK_STATE_UNSPECIFIED
        val update = TaskUpdate(
            index = task.log.size.toLong(),
            state = state,
            revision = task.revision,
            attempt = 0,
            progress = progressOf(task, state, observed.progress),
            progressBasis = basisOf(state, observed.progress),
            partialResult = observed.result,
            hold = observed.hold.toProto(),
            fault = observed.failure,
            occurredAt = clock(),
        )
        task.log += update
        emit(
            Event.newBuilder()
                .setTaskTransition(
                    TaskTransition.newBuilder()
                        .setTaskId(task.taskId).setSkillType(task.skillType)
                        .setFrom(from).setTo(state)
                        .setRevision(task.revision).setAttempt(0),
                ),
        )
        onUpdate.forEach { it(task, update) }
    }

    /**
     * 계약의 `progress` — **어댑터가 셀 수 있을 때만 움직인다.**
     *
     * 규칙 셋이 여기 모여 있다. ① 성공 종착은 1.0 이다(§4.4). ② 값은 `(task_id, revision, attempt)` 안에서
     * **단조 비감소**이므로 앞의 값이 바닥이다 — 벤더가 개수를 다시 세면 숫자가 내려갈 수 있고, 실패 종착도
     * *거기서 멈춘 것*이지 아무것도 안 한 것이 아니다. ③ 범위 밖은 자른다.
     *
     * **못 재는 기종은 0.0 에 머문다.** 계약에 *못 잰다* 를 실을 자리가 없어 [ProgressObservation.NotObservable]
     * 이 여기서 접히며, 그것이 지금의 한계다(§15.108).
     */
    private fun progressOf(task: HostedTask, state: TaskState, progress: ProgressObservation?): Double {
        // **바닥은 같은 구간 안에서만 유효하다.** `revision` 이 오르면(갱신) 진행률은 0 에서 다시 센다(§4.4).
        val floor = task.log.lastOrNull()?.takeIf { it.revision == task.revision }?.progress ?: 0.0
        val measured = when {
            state == TaskState.TASK_STATE_SUCCEEDED -> 1.0
            else -> (progress as? ProgressObservation.Fraction)?.fraction?.coerceIn(0.0, 1.0) ?: 0.0
        }
        return maxOf(floor, measured)
    }

    /**
     * 계약의 `progress_basis`(0.8.0) — **숫자가 무엇을 센 것인지**.
     *
     * 어댑터의 [ProgressObservation] 을 그대로 옮긴다. 종착이 성공이면 셀 것이 없어도 1.0 이 사실이므로
     * 측정된 것으로 낸다 — 그때의 근거는 *종착* 이다.
     */
    private fun basisOf(state: TaskState, progress: ProgressObservation?): ProgressBasis {
        val builder = ProgressBasis.newBuilder()
        if (state == TaskState.TASK_STATE_SUCCEEDED) {
            return builder.setKind(ProgressKind.PROGRESS_KIND_MEASURED).setBasis("성공 종료").build()
        }
        // 성공 종착이 아니면 [observe] 가 진행률을 읽었다.
        return when (val observed = checkNotNull(progress)) {
            is ProgressObservation.Fraction ->
                builder.setKind(ProgressKind.PROGRESS_KIND_MEASURED).setBasis(observed.basis).build()

            is ProgressObservation.NotObservable ->
                builder.setKind(ProgressKind.PROGRESS_KIND_NOT_OBSERVABLE).setReason(observed.reason).build()
        }
    }

    private fun faultEvent(fault: Fault, cleared: Boolean): Event.Builder =
        Event.newBuilder().setFaultEvent(FaultEvent.newBuilder().setFault(fault).setCleared(cleared))

    private fun emit(builder: Event.Builder) {
        val sequence = nextSequence.getAndIncrement()
        val event = builder.setHeader(publishHeader(Event.getDescriptor(), sequence)).build()
        // **버퍼에 먼저 넣고 발행한다.** 발행이 막혀도 재생 버퍼에는 남아야 한다(§10.6).
        buffer.addLast(event)
        evict()
        send(Publication(topic(Topics.Stream.event), event, sequence))
    }

    /**
     * 버퍼가 넘치면 가장 오래된 것을 버린다.
     *
     * **실제로 버린 것만 축출이다** — 버퍼의 첫 항목보다 앞이라는 이유로 축출이라 판정하면 `state`·`connection` 이 쓴
     * 번호를 요청한 소비자에게 *잃었다* 고 거짓말하게 된다(셋이 같은 `sequence` 축을 쓴다, §5.5).
     *
     * **버린 것이 아직 못 보낸 것이면 세션을 새로 낸다**(§10.6). 그러면 옛 구간을 다시 밀 뜻이 없으므로 버퍼를 비우고
     * 못 보낸 경계도 지운다 — 안 비우면 다음 발행이 **지난 세션의** 이벤트를 소비자에게 민다. 비운 것은 버린 것이니
     * 축출 경계를 **그 끝까지** 올린다; 첫 하나만 적으면 되짚기가 나머지에 대고 *잃은 것 없다* 고 답한다.
     */
    private fun evict() {
        while (buffer.size > document.replayBufferSize) {
            val dropped = buffer.removeFirst().header.sequence
            evictedUpTo = dropped
            val unsent = unsentFrom
            if (unsent != null && dropped >= unsent) {
                sessionId = newSessionId()
                buffer.lastOrNull()?.let { evictedUpTo = it.header.sequence }
                buffer.clear()
                unsentFrom = null
            }
        }
    }

    /**
     * `ReplayEvents(from)` — 벗어났으면 널(축출), 아니면 그 번호부터.
     *
     * **아직 나지 않은 번호도 축출이다**(§15.288). 그런 커서는 이 호스트가 준 것이 아니라 다시 뜨기 전의 것이며,
     * 세션이 되풀이되면 소비자가 재시작을 못 본다. 빈 목록을 주면 그 커서가 새 세션의 앞 이벤트를 조용히 건너뛴다.
     */
    fun replay(from: Long): List<Event>? {
        if (from > nextSequence.get()) return null
        val evicted = evictedUpTo
        if (evicted != null && from <= evicted) return null
        return buffer.filter { it.header.sequence >= from }
    }

    // ── 헤더 (§5.5 의 응답 열 — 미믹의 ResponseHeaders 와 같은 표)

    fun header(schema: Descriptors.Descriptor, updateIndex: Long? = null, stateAsOf: Instant? = null): MessageHeader {
        val now = clock()
        return MessageHeader.newBuilder()
            .setSchemaId(schema.fullName)
            .setContractDigest(ContractIdentity.digest)
            .setContractSemver(ContractIdentity.semver)
            .setRobotId(robotId)
            .setCapabilityEpoch(capabilityEpoch)
            .setSessionId(sessionId)
            .setProfileRef(ProfileRef.newBuilder().setProfileId("${document.vendor}/${document.model}").setRevision(document.revision))
            .setEventId("$sessionId-%08d".format(eventIds.incrementAndGet()))
            .setOccurredAt(ISO.format(now))
            .setStateAsOf(ISO.format(stateAsOf ?: now))
            .also { b -> updateIndex?.let(b::setUpdateIndex) }
            .build()
    }

    private fun faultKey(fault: Fault): String =
        fault.errorType + fault.referencesList.joinToString(prefix = "[", postfix = "]") { "${it.key}=${it.value}" }

    private fun newSessionId(): String = "host-%013d-%06d-%s".format(clock().toEpochMilli(), SESSIONS.incrementAndGet(), BOOT)

    private companion object {
        val SESSIONS = AtomicLong()

        /** 이 프로세스의 기동 난수 — 세션 끝에 붙는다([sessionId]). 다시 뜬 프로세스는 다른 값을 뽑는다. */
        val BOOT: String = "%016x".format(java.security.SecureRandom().nextLong())
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
        val FAILURE_STATES = setOf(TaskState.TASK_STATE_FAILED, TaskState.TASK_STATE_RETRIABLE, TaskState.TASK_STATE_NEEDS_INTERVENTION)

        /** 갱신을 받아 **어댑터까지 보내는** 상태 — 로봇이 지금 그 파라미터로 움직이고 있다. */
        val UPDATABLE_STATES = setOf(TaskState.TASK_STATE_ACCEPTED, TaskState.TASK_STATE_RUNNING)

        /**
         * 조작 뒤 어댑터의 지금 상태를 함께 적는 답. 수단이 없다·못 닿았다·표면이 없다는 로봇에 아무 일도 안 일어난 것이라
         * 읽지 않는다.
         */
        fun syncsAfter(applied: Applied): Boolean = when (applied) {
            Applied.Ok -> true
            is Applied.Refused -> applied.reason !in setOf(Refusal.NO_VENDOR_PRIMITIVE, Refusal.LINK_ERROR, Refusal.VENDOR_SURFACE_ABSENT)
        }

        /**
         * 갱신을 받되 **갈아만 두는** 상태(§4.4). 재개·재시도가 그때 새 파라미터로 다시 시작한다.
         *
         * `NEEDS_INTERVENTION` 이 여기 있는 것이 요점이다 — **개입한 사람이 파라미터를 고쳐 넣는 경로가 그것**이고,
         * 막으면 그 상태의 존재 이유와 어긋난다.
         */
        val DEFERRED_UPDATE_STATES = setOf(
            TaskState.TASK_STATE_PAUSED,
            TaskState.TASK_STATE_RETRIABLE,
            TaskState.TASK_STATE_NEEDS_INTERVENTION,
        )
    }
}

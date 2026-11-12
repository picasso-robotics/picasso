package dev.picasso.middleware

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.capability.Remedy
import dev.picasso.middleware.RemedyDesk.Judgment
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState
import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * 공통 실행 구조(설계 §2) — 접수 · 조합 · 실행 상태기계 · 근거 결합 · 취소 · 결과 통보.
 *
 * ## 구동
 *
 * 스레드가 없다. [submit]·[cancel]·[resolve]·[ack]·[forget] 이 밖에서 오고, 하류의 전이는 [pump] 를
 * 부를 때 읽는다. 시험이 가상 시계를 밀고 `pump()` 를 부르는 결정적 구동에 맞춘
 * 것이며(§12.1), 운영 배치에서는 스케줄러가 `pump()` 를 돌린다. 시각은 [now] 로만
 * 읽는다 — 시간창 δ 가 시각을 비교하기 때문이다.
 *
 * ## 하류가 둘
 *
 * [Route.ROBOT] 은 계약(④)의 원자 스킬이고 [Route.FLEET] 은 D 수준 위임이다.
 * 둘을 가르는 것은 하류의 **종류**이지 기종이 아니다 — 여기에 기종 이름은 없고
 * 게이트 7번이 그것을 지킨다. 셋째 경로 [Route.SIGNAL] 은 하위가 아니라 설비 대기다 —
 * 요청을 보내지 않고 이름 있는 설비 신호를 기다린다.
 *
 * ## 기체
 *
 * 실행 하나는 기체 하나에서 **차례로** 돈다 — 실물은 예외 없이 배타적 제어
 * 모델이고(§4.9) 어느 기체에 줄지(배차)는 이 모듈의 일이 아니다(ADR 38).
 */
class Middleware(
    internal val robots: RobotPort,
    internal val cell: CellSignals = CellSignals.None,
    internal val fleet: AmrFleetPort = AmrFleetPort.None,
    /**
     * 코드로 정의한 케이퍼빌리티 목록. 널이면 [MissionCatalog.codeCapabilities] 다. [missions] 를 주면 쓰지 않으므로
     * 둘을 함께 주면 생성이 실패한다 — 한쪽이 조용히 무시되는 길을 두지 않는다.
     */
    capabilities: List<LogicalCapability>? = null,
    internal val now: () -> Instant = { Instant.now() },
    /**
     * 실 시계 — 사건 번들의 **현장 대조**·검토 시각, 탐색 대장([RemedySearchRecord])과 승인 소모 기록의 실 시각용이다.
     * [now] 와 나누는 이유는 [now] 가 시험에서 가상 시계이기 때문이다. 둘을 접으면 재현 판정과 현장 대조 중 하나를 잃는다(설계안 §4.2).
     */
    wallClock: () -> Instant = { Instant.now() },
    /**
     * 몇 번에 한 번 제안을 **가릴 것인가**(설계안 §7.2 넷째 — 의도적 비자동화). 0 이면 가리지 않는다.
     *
     * 에이전트가 사건을 처리할수록 사람은 진단하는 연습을 잃고, 그러면 모델 밖 사건이 왔을 때 아무도
     * 들어가지 못한다. 가리는 것은 **비용이다** — 그 사건에서 운영자가 더 오래 걸린다. 비용임을 알고
     * 지불하는 결정이어야 유지되므로 기본값을 두지 않고 배치가 명시하게 한다. 0 을 고르는 것도 결정이다.
     */
    withholdEvery: Int = 0,
    /**
     * 배정 비용의 가중치(설계안 §7). **정책은 데이터로 밖에, 평가는 안에서** — 값은 배치가 주고
     * 계산은 tick 안에서 한다. 순위 계산을 통째로 밖에 두면 읽은 시점과 적용 시점이 갈라진다.
     */
    private val cost: AssignmentCost = AssignmentCost(),
    /**
     * 재할당의 진동 방지(설계안 §7). **라인을 멈추지 않는 것이 최적성보다 우선한다** — 값은 배치가 준다.
     */
    private val reassignPolicy: ReassignPolicy = ReassignPolicy(),
    /**
     * 자원 소유 대장(§15.154). **대장은 현장의 것이고 이 저장소 밖에 산다** — 붙이지 않은 배치에서는
     * 모든 자리가 «선언 안 됨» 이고 관문이 아무것도 막지 않는다.
     */
    floors: FloorOwnership = FloorOwnership.None,
    /**
     * 자리가 속한 작업 구역(§15.153). **붙이지 않은 배치에서는 모든 자리가 구역 밖**이고 관문이
     * 아무것도 막지 않는다 — 구역을 안 붙인 현장에서 라인이 서면 이 관문이 곧 꺼진다.
     */
    workspace: Workspace = Workspace.None,
    /**
     * 자동 승인 자격의 선언 목록(ADR 43). **기본값은 아무것도 선언 안 된 것**이고 그때 에이전트 승인은
     * 전부 거절된다 — 사람 승인은 선언을 안 보므로 라인은 서지 않는다.
     */
    entitlements: Entitlements = Entitlements.None,
    /**
     * 이 미들웨어 인스턴스가 뜬 한 번을 가리키는 값(ADR 48). 결과 통보의 바깥 형식과 승인 답에 같은 값이 실린다.
     *
     * **실행 식별자(`exec-N`)와 결과 통보 식별자(`resp-N`)는 프로세스 안의 셈이라 다시 뜨면 되풀이된다.** 밖의
     * 소비자가 «이 통보가 이번 시도의 것인가» 를 가르려면 이 값과 짝지어야 한다. 그래서 **결정적이지 않아야 한다** —
     * 같은 시드로 다시 띄운 인스턴스가 같은 값을 내면 앞 구동의 통보가 이번 시도의 것으로 읽힌다.
     */
    val instanceId: String = "mw-${java.util.UUID.randomUUID()}",
    /**
     * 작업 지시를 계획할 케이퍼빌리티의 출처(임무 버전). 널이면 [capabilities] 로 만든 카탈로그다(버전 없음).
     *
     * **새 작업 지시만 읽는다.** 실행은 생성 때 읽은 케이퍼빌리티와 버전을 쥐고 리비전까지 그것으로 돈다 —
     * 그래서 활성 버전이 바뀌어도 도는 실행은 옛 버전으로 끝난다.
     */
    missions: MissionCatalog? = null,
    /**
     * 현장 시간값의 출처. 기본값은 «없음» 이고, 그때는 케이퍼빌리티 값으로 판정한다.
     *
     * [pump] 가 시작할 때 한 번 읽어 그 라운드의 모든 판정에 같은 값을 쓴다. 바꾼 값은 다음 pump 부터다. 단위에 저장하는 값은
     * 근거 기한(완료 시각 + 뒤 폭) 하나뿐이라, 바꾼 `inDoubtGrace` 는 이미 `IN_DOUBT` 인 단위에도 다음 라운드부터 미친다.
     */
    private val siteTimings: SiteTimingsSource = SiteTimingsSource.NONE,
    /**
     * 작업 허가의 출처(ADR 52) — 발행 관문([IssuingGate])의 둘째 입력. **널이면 붙이지 않은 것이고 관문이 아무것도 막지 않는다** —
     * 오늘의 거동 그대로다. 붙이면 닫힌 쪽으로 판정한다. `PERMITTED` 가 아니면(모름 포함) 새 로봇 명령을 내지 않고 기다린다.
     *
     * 붙이지 않은 배치까지 닫힌 쪽으로 두지 않는 것은 그러면 허가 원천이 없는 현장이 통째로 서고, 그 관문은 곧 꺼지기 때문이다
     * ([floors]·[workspace] 와 같은 이유).
     */
    permits: WorkPermitPort? = null,
    /**
     * 기체 퇴역의 출처(§15.249) — 발행 관문([IssuingGate])의 셋째 입력이고 재할당([reassign])이 후보를 거르는 데도 쓴다. **널이면
     * 붙이지 않은 것이고 아무것도 막지 않는다** — 오늘의 거동 그대로다. 붙이면 퇴역한 기체로는 새 로봇 명령을 내지 않고 기다리며,
     * 도는 단위는 끊지 않는다.
     */
    retirements: RetirementPort? = null,
    /**
     * 인시던트 번들·탐색 기록·제안·승인 소모 기록의 보존 상한(§15.166). 넘치면 오래된 것부터 내리되 구동이 돌리는 실행의 것은
     * 남긴다. 기본값은 [Retention] 의 값이다.
     */
    retention: Retention = Retention(),
) {
    // ⚠ 생성자 인자 `missions`(널 허용)가 이 속성을 가린다 — 다른 속성의 초기화식에서 `missions` 는 인자다. 거기서는 `this.missions` 로 쓴다.
    private val missions: MissionCatalog = run {
        require(capabilities == null || missions == null) {
            "케이퍼빌리티 목록과 임무 카탈로그를 함께 줬다 — 카탈로그를 주면 목록은 쓰지 않는다"
        }
        missions ?: MissionCatalog.of(capabilities ?: MissionCatalog.codeCapabilities())
    }
    internal val executions = linkedMapOf<String, Execution>()

    /**
     * 실행 id 의 셈. **표의 크기로 세지 않는다** — [forget] 이 표에서 빼므로 크기로 세면 놓은 실행의 id 가 새 실행에 다시 붙고,
     * 그 id 를 든 사건 번들·소모 기록·통보가 새 실행을 가리키게 된다.
     */
    private var executionSeq = 0

    /**
     * 놓은 작업 지시 — 작업 지시 id 마다 그 실행의 id·판·종착 상태 하나([forget]). 같은 작업 지시 id 가 다시 오면 이것으로 거부한다.
     *
     * 실행을 통째로 들지 않고 이 셋만 든다. 넣은 순서를 지키고 [Retention.forgottenOrders] 를 넘으면 오래된 것부터 내린다(§15.296) —
     * 내린 id 의 재전송은 새 실행으로 서므로 그 막음은 담는 쪽의 기록 몫이 된다.
     */
    private val forgotten = linkedMapOf<String, ForgottenOrder>()

    /** 보존 상한(§15.166, §15.296). 생성자 인자 `retention` 을 속성으로 든다. */
    private val caps = retention

    /** 상한으로 놓은 실행과 내린 놓은 작업 지시 표시의 수([retentionEvictions]). */
    private var executionsEvicted = 0L
    private var forgottenEvicted = 0L

    private data class ForgottenOrder(val executionId: String, val version: Int, val state: PhysicalState)

    internal val views = mutableMapOf<String, RobotView>()

    /** 배정 관문(설계안 §7). 실행 표를 같은 인스턴스로 넘긴다 — 관문은 이 층이 지금 든 것을 읽는다. */
    internal val gate = AdmissionGate(robots, cell, floors, workspace, executions)

    /**
     * 발행 관문(ADR 51 결정 6, ADR 52). 배정 관문([gate])과 다르다 — 배정 관문은 작업 지시를 받을 때 한 번 묻고, 이것은 새 로봇
     * 명령이 나가기 직전마다 묻는다. 허가는 받은 뒤에도 바뀌기 때문이다.
     */
    internal val issuing = IssuingGate(permits, retirements, now)

    /** 제안과 승인의 장부(설계안 §6.4). 승인 뒤의 접수는 [commit] 에 남고, 승인의 기록([RemedyDesk.settle])은 접수가 된 뒤에만 한다. */
    internal val desk = RemedyDesk(robots, entitlements, withholdEvery, now, wallClock, gate::liveHold, retention, ::pumping)

    /** 사건 번들의 장부(설계안 §4). 봉인은 [pump] 가 라운드 끝에 부른다. */
    internal val incidentLog = IncidentLog(robots, now, wallClock, retention.incidents, ::pumping)

    /** 이 실행을 아직 구동이 돌리는가 — 보존 상한이 그 실행의 기록을 남길지 가른다([Retention]). */
    private fun pumping(executionId: String): Boolean = executions[executionId]?.physicalState?.pumps == true

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

    fun view(robotId: String): RobotView? = views[robotId]
    private val outbox = mutableListOf<JobResponse>()
    private var responseSeq = 0

    /** 실행 하나 — 주문·기체·단위 열·두 축의 상태. */
    inner class Execution(
        val executionId: String,
        var order: JobOrder,
        /** 지금 이 일을 든 기체. **재할당으로 바뀐다** — 옮긴 이력은 자취에 남는다. */
        var robotId: String,
        val capability: LogicalCapability,
        /**
         * 이 실행을 계획한 임무 버전. 코드 케이퍼빌리티면 `null` 이다. **생성 때 정해지고 바뀌지 않는다** —
         * 리비전도 [capability] 로 계획하므로, 활성 버전이 바뀌어도 이 실행은 이 버전으로 끝난다.
         */
        val missionVersion: Int?,
        val units: MutableList<ExecutionUnit>,
    ) {
        var physicalState: PhysicalState = PhysicalState.REQUESTED
            internal set

        /** 지금 기체에 배정된 시각. 최소 유지 시간의 기준이고 재할당 때 다시 찍힌다. */
        internal var assignedAt: Instant = now()

        /**
         * 이 실행이 **승인된 조치로 시작됐다면** 그것을 누른 쪽. 아니면 널이다.
         *
         * 사건이 이 실행에서 열리면 번들이 이 값을 싣고, 그래서 «에이전트가 승인한 조치의 이의율» 을
         * 사람 승인과 따로 잴 수 있다(ADR 43 §4).
         */
        internal var approvedBy: Approver? = null

        /**
         * 이 실행의 통보가 상류에 어디까지 확인됐는가. 통보를 내면 `SENT_UNACKED`, 그 실행의 통보가 **전부** 확인돼야 `ACKED` 다.
         * 앞 통보 하나의 확인으로 `ACKED` 가 되면 마지막 통보가 확인 전인데도 확인된 것으로 읽힌다(§15.262). 응답마다의 확인은
         * [JobResponse.ack] 에 있다.
         */
        var upstreamAck: UpstreamAck = UpstreamAck.NOT_SENT
            internal set
        internal var active: ExecutionUnit? = null
        internal var handle: TaskHandle? = null
        internal var transport: TransportHandle? = null
        /**
         * **명령 생애**(§15.303, `CommandLifecycle.kt`). 취소의 네 축(§15.301)과 송신 기록(보내는 중인 출발·맡기기, 미룬 갱신)은 이
         * 실행의 생애 객체가 든다. 출발·미확정·취소의 전이는 그 객체의 확장(`Lifecycle*.kt`)이 하고, 라운드 안의 순서는 [pumpRound] 가 정한다.
         */
        internal val command = CommandLifecycle(this@Middleware, this)

        // 생애 객체를 읽는 이름. 상태가 아니라 위 객체에서 계산한다.
        internal val cancelRequested: Boolean get() = command.cancelIntent.requested
        internal val cancelRefusal: String? get() = (command.cancelIntent as? CancelIntent.Refused)?.code
        internal val lastCancel: CancelReport? get() = (command.cancelIntent as? CancelIntent.Reported)?.report
        internal val cancelUnsent: Boolean get() = command.cancelDelivery is CancelDelivery.Unsent
        internal val cancelInFlight: CancelFlight? get() = command.cancelSending.robot
        internal val fleetCancelInFlight: FleetCancelFlight? get() = command.cancelSending.fleet
        internal val inFlight: InFlight? get() = command.inFlight
        internal val fleetInFlight: FleetFlight? get() = command.fleetInFlight
        internal val deferredUpdate: Int? get() = command.deferredUpdate

        /**
         * **알린 것**(§15.302, `Notices.kt`). 지연 보고·운영자 보류·미확정·막음 통보, 마지막 작업 응답이 보인 주의([noteAttention]),
         * 포트가 던진 까닭([PUMP_SOURCE]·`sync:<기체>`), 기체마다의 시계 차([CLOCK_SKEW]), 설비 대기의 신호 값, 지연 이벤트를 자리마다
         * 한 번만 낸다. 새 자리는 [Notice] 에 열쇠를 더해 붙는다.
         */
        internal val noticed = Noticed()

        /** 지금 다음 단위를 막고 있는 기체 수준 결함. 비어 있으면 막힌 것이 없다. */
        var blockedBy: List<Fault> = emptyList()
            internal set

        /**
         * 지금 이 실행의 새 로봇 명령을 막고 있는 작업 허가(ADR 52). 널이면 막힌 것이 없다.
         *
         * **[blockedBy] 와 칸이 다르다.** 결함은 사람이 [release] 로 감수할 수 있지만 허가는 감수할 값이 아니다 — 원천이 다시
         * `PERMITTED` 를 낼 때만 걷힌다. 한 칸에 두면 해제 한 번이 허가 막음까지 걷는다. 막힌 단위는 실패가 아니고 기다린다 —
         * 그래서 실행 상태([physicalState])도 운영자 보류로 바꾸지 않는다. 운영자가 판단할 것이 없다.
         */
        var permitBlock: PermitBlock? = null
            internal set

        /**
         * 운영자가 [release] 로 감수한 결함의 열쇠 — 같은 결함은 다시 막지 않는다. 새 결함은 막는다. 결함이 걷히면 열쇠를 놓으므로
         * 걷혔다가 같은 열쇠로 다시 생긴 결함도 새 결함이다([forgetAcknowledged], §15.248). 놓은 것은 트레이스에 남는다.
         */
        val acknowledgedFaults: MutableSet<String> = linkedSetOf()

        /** 이 실행이 도는 동안 기체가 낸 이벤트와 이 층의 관측 — 감사 자취(보고서 7장 *실행 추적·이벤트 전달·감사 기록*). */
        val eventTrail: MutableList<ObservedEvent> = mutableListOf()

        /**
         * 이번 라운드에 미완료로 닫혔거나 운영자 판단에 선 단위. **라운드 끝에** 번들로 봉한다 —
         * 전이 순간에는 실행 수준의 사실(막는 결함)이 아직 안 정해져 있다.
         */
        internal val pendingIncidents: MutableSet<String> = linkedSetOf()

        /** 연결이 끊긴 채 도는 중인가 — 그동안 결과는 미확정이다(`scenarios.md` §4.4 넷째 행). */
        var linkBroken: Boolean = false
            internal set

        /**
         * [resume] 이 마지막 로봇 태스크보다 앞선 것으로 둔 로봇 단위. 다시 관측한 성공에 설비 근거를 다시 묻지 않는다. 이전
         * 인스턴스가 이미 판정했고 설비 값은 그 뒤 바뀌었을 수 있다.
         */
        internal val resumedPrior: MutableSet<String> = mutableSetOf()

        /**
         * [resume] 이 스냅숏에서 태스크를 본 로봇 단위와 그때의 시도. **이 인스턴스가 아직 붙지 않은 태스크**다 — 첫 pump 가
         * [startUnit] 으로 붙거나, 그 전에 취소가 걸리면 [attachForCancel] 이 붙는다. 붙으면 뺀다. 시도가 다르면(기록이 재작업을
         * 넘겼다) 그 시도의 태스크는 기체에 없으므로 대상이 아니다.
         */
        internal val unattached: MutableMap<String, Int> = mutableMapOf()

        /**
         * [resume] 이 다시 세운 로봇 단위와 그때의 시도(§15.238). 그 시도의 태스크는 앞 인스턴스가 어느 리비전으로 보냈는지 모른다 — 스냅숏이
         * 안 보였거나 기록이 없어도 그렇다. [startUnit] 은 이 시도에 [ExecutionUnit.startedRevision] 을 적지 않는다. 재작업으로 시도가
         * 바뀌면 새 태스크라 대상이 아니다.
         */
        internal val inherited: MutableMap<String, Int> = mutableMapOf()

        /** 지연 이벤트(15.1) — 옛 버전의 종착. 폐기하지 않는다. */
        val lateEvents: MutableList<LateEvent> = mutableListOf()

        internal fun noteLate(unit: ExecutionUnit, update: WatchTaskResponse) {
            if (!noticed.note(Notice.Late(unit.unitId, update.revision, update.state.name), true)) return
            lateEvents += LateEvent(unit.unitId, update.revision, unit.revision, update.state.name, stateTime(update))
        }

        val completedUnits: List<String> get() = units.filter { it.state == UnitState.DONE }.map { it.unitId }
        val version: Int get() = order.version

        /**
         * 이 단위를 맡은 기체 — 단위의 묶음([ExecutionUnit.robotBinding]), 없으면 이 실행의 기체([robotId]).
         *
         * **단위의 기체를 읽는 자리는 전부 이것을 부른다.** [robotId] 를 바로 읽으면 오늘은 같은 값이라 아무 시험도 안 깨지고,
         * 단위를 다른 기체에 묶는 날 그 자리만 옛 기체로 명령을 낸다.
         */
        fun robotOf(unit: ExecutionUnit): String = unit.robotBinding ?: robotId

        /** 이 실행이 쓰는 기체 전부 — 실행의 기체와 단위가 묶인 기체. 동기화와 부담 셈이 읽는다. */
        val robots: Set<String> get() = linkedSetOf(robotId).apply { units.forEach { add(robotOf(it)) } }

        /** 이 실행을 세운 승인의 소모. 승인된 조치로 서지 않았으면 널이다. 판단 기록([judgmentRecord])이 싣는다. */
        internal var approval: RecordedApproval? = null

        /** 운영자 판단 — 단위마다 마지막 하나. 판단 기록이 싣는다. */
        internal val decisions: MutableMap<String, RecordedDecision> = linkedMapOf()

        /**
         * 판단 기록이 넘긴 요청 시각 — 단위마다 (시도, 시각). `startUnit` 이 **그 시도를** 낼 때 한 번 쓰고 지운다. 다른 시도면
         * 쓰지 않는다 — 재작업은 새 요청이고 그 시각은 지금이다.
         */
        internal val restoredRequests: MutableMap<String, Pair<Int, Instant>> = mutableMapOf()

        /** 판단 기록이 넘긴 검증 대기의 창 — 단위마다 (시도, 창). 그 시도의 완료를 다시 관측할 때 한 번 쓰고 지운다. */
        internal val restoredWindows: MutableMap<String, Pair<Int, VerifyWindow>> = mutableMapOf()
    }

    sealed interface Submission {
        data class Accepted(val execution: Execution) : Submission
        data class Idempotent(val execution: Execution) : Submission
        /**
         * 받지 않았다. [remedy] 가 있으면 **깨진 전제를 충족시키는 조치 열**이 계산됐다는 뜻이며,
         * 사람이 [approveRemedy] 로 승인해야 실행된다 — 승인 없이 도는 길은 없다(설계안 §6.4).
         */
        data class Rejected(
            val reason: String,
            val remedy: Remedy? = null,
            /**
             * 조치 열이 **계산됐으나 가려졌다**(설계안 §7.2 넷째). [remedy] 가 널이면서 이것이 참이면
             * «대안이 없다» 가 아니라 «사람이 먼저 진단하라» 는 뜻이다. 둘을 접으면 의도적 비자동화가
             * 능력 부재와 구별되지 않아, 운영자가 시스템을 고장으로 읽는다.
             */
            val remedyWithheld: Boolean = false,
            /**
             * 출발 자리가 비었을 때 **그 자재를 든 다른 자리**(§15.153). 셀이 그 질문에 답하지 않으면 널이고,
             * 빈 목록은 «든 자리가 하나도 없다» 는 답이다 — 둘을 접으면 못 물어본 것이 재고 부족으로 읽힌다.
             *
             * 자리 이름만 낸다. 어느 자리를 쓸지는 주문을 고치는 쪽의 결정이고, 이 층은 고르지 않는다.
             */
            val alternativeLocations: List<String>? = null,
            /**
             * 관측을 요구한 단위의 출발 자리를 **확인하지 못했다**([SourceUnobserved]). 널이면 이 거절은 그 갈래가 아니다.
             * 결품이 아니므로 [alternativeLocations] 는 널이고, 다음 행동은 주문을 고치는 것이 아니라 사람의 확인이다.
             */
            val sourceUnobserved: SourceUnobserved? = null,
            /**
             * 사람의 문([approveRemedy])이 낸 거절의 **종류**. 밖의 문([attemptApproval])의 [ApprovalOutcome.Refused.refusal] 과 같은
             * 판정에서 같은 값이 온다 — 사유 산문만 내면 부르는 쪽이 문자열로 갈라야 한다(ADR 44·46). 승인이 아닌 접수의 거절이면 널이다.
             */
            val approvalRefusal: ApprovalRefusal? = null,
            /**
             * [approvalRefusal] 이 [ApprovalRefusal.CONSUMED] 일 때의 소모 기록(ADR 46). 그때만 있고 그때는 반드시 있다 — 판정
             * ([RemedyDesk.Judgment.No])이 짝을 검사한 그대로 옮긴다.
             */
            val consumedApproval: ConsumedApproval? = null,
        ) : Submission
    }

    fun execution(executionId: String): Execution? = executions[executionId]

    /** 지금 이 층이 든 실행 전부. 조회만 한다. */
    fun executions(): List<Execution> = executions.values.toList()

    /** 상류에 갈 결과 통보 중 아직 ack 되지 않은 것 — 아웃박스. 통보 재시도의 자리다(보고서 13.3). */
    fun pending(): List<JobResponse> = outbox.filter { it.ack != UpstreamAck.ACKED }

    fun responses(): List<JobResponse> = outbox.toList()

    // ── 접수 (보고서 15 — 정체성과 버전)

    /**
     * JobOrder 를 받는다. 같은 `jobOrderId` 가 오면 버전으로 판정한다 — 같으면 멱등,
     * 낮으면 거절, 높으면 **갱신**(보고서 15.3: 새 버전은 확정된 단위 이후에만 붙는다).
     */
    fun submit(order: JobOrder, robotId: String): Submission = submit(order, robotId, prefix = emptyList())

    /**
     * @param prefix 승인된 조치 열(설계안 §6.4). **[approveRemedy] 만 채운다** — 밖에서 부를 길이 없으므로
     *   승인 없이 조치가 실행되는 경로가 생기지 않는다.
     */
    private fun submit(
        order: JobOrder,
        robotId: String,
        prefix: List<ExecutionUnit>,
        approvedBy: Approver? = null,
        /** [adopt] 가 관문에 대려고 이미 읽은 쌍. 널이면 지금 카탈로그를 읽는다 — 한 작업 수락이 카탈로그를 두 번 읽지 않게 한다. */
        mission: ActiveMission? = null,
    ): Submission {
        forgottenRefusal(order.jobOrderId)?.let { return it }
        // **이미 있는 실행이면 카탈로그를 안 본다.** 리비전은 그 실행이 쥔 케이퍼빌리티로 계획한다 — 여기서 카탈로그를
        // 읽으면 활성 버전이 바뀐 뒤의 리비전이 새 버전의 단위를 옛 실행에 들인다.
        val existing = executions.values.firstOrNull { it.order.jobOrderId == order.jobOrderId }
        if (existing != null) return revise(existing, order, prefix, approvedBy)

        val active = mission ?: missions.active(order.workMasterId)
            ?: return Submission.Rejected("모르는 논리적 케이퍼빌리티다: ${order.workMasterId}")
        val capability = active.capability
        evidenceRefusal(order, capability)?.let { return it }

        val planned = prefix + capability.plan(order)
        duplicateUnitIdRefusal(planned)?.let { return it }
        when (val admission = admits(order, robotId, planned)) {
            // 승인한 조치 열을 붙인 접수의 거절은 서 있는 제안을 바꾸지 않는다([commit]).
            is Admission.Refused -> return desk.record(
                robotId, order, admission.rejection, admission.sourceMissing, approval = approvedBy != null,
            )
            Admission.Passed -> Unit
        }

        return Submission.Accepted(create(order, robotId, active, planned, approvedBy))
    }

    /** 실행 하나를 세워 표에 넣는다. [submit] 과 [resume] 이 함께 쓴다. 판정은 부르는 쪽이 이미 끝냈다. */
    private fun create(
        order: JobOrder,
        robotId: String,
        active: ActiveMission,
        planned: List<ExecutionUnit>,
        approvedBy: Approver?,
    ): Execution {
        val execution = Execution(
            executionId = "exec-${++executionSeq}",
            order = order,
            robotId = robotId,
            capability = active.capability,
            missionVersion = active.missionVersion,
            units = planned.toMutableList(),
        )
        execution.units.forEach { it.revision = order.version }
        execution.approvedBy = approvedBy
        execution.physicalState = PhysicalState.ACCEPTED
        executions[execution.executionId] = execution
        return execution
    }

    /**
     * 담는 쪽이 재기동한 뒤 **자기 기록으로 실행을 다시 짓는 입구**다. 이전 인스턴스가 받은 작업 지시와 기체, 그때 계획한 임무
     * 정의와 버전([mission])을 넘겨받는다. 미들웨어를 담는 실행 호스트가 기동할 때 [pump] 를 켜기 전에 부르는 자리다.
     *
     * **상태를 되살리는 것이 아니라 같은 정체성으로 다시 짓는 것이다.** 실행 id 와 작업 응답 id 는 이 인스턴스의 셈으로 새로
     * 붙고, 로봇 단위와 플릿 단위는 대기에서 시작한다. 로봇 단위의 상태는 [pump] 가 기체에서 다시 관측해 세운다. 첫 pump 는 스냅숏에
     * 그 시도의 태스크가 단위의 리비전으로 보이면 `StartTask` 없이 붙고 발행 게이트를 묻지 않는다([reattachFromSnapshot], §15.219).
     * 리비전을 모르면 같은 태스크 id 와 리비전으로 `StartTask` 를 다시 보내며, 기체가 기존 핸들을 돌려주므로(계약의 멱등) 새 명령이
     * 나가지 않지만 그 재전송은 게이트를 지난다.
     *
     * **넘기는 작업 지시는 이전 인스턴스가 받은 마지막 버전이어야 한다.** 리비전은 단위의 `revision` 이 되고 태스크의 멱등은
     * 같은 `task_id`·`revision` 에만 서므로, 낮은 버전을 넘기면 기체가 기존 태스크에 붙여 주지 않는다. 그래서 스냅숏이 넘긴 버전보다
     * 높은 리비전으로 이 작업 지시의 태스크를 보이면 [RESUME_STALE_VERSION] 으로 거부한다(2 의 끝).
     *
     * 같은 작업 지시 id 의 실행이 이미 있으면 [submit] 과 같이 리비전 경로로 간다. 같은 버전이면 [Submission.Idempotent] 다.
     * 이 인스턴스가 그 작업 지시를 [forget] 으로 놓았으면 [submit] 과 같이 [FORGOTTEN_JOB_ORDER] 로 거부한다.
     *
     * 없으면 차례는 이렇다.
     *
     * 1. **넘겨받은 정의의 WorkMaster 를 작업 지시와 대조한다.** 다르면 거부한다.
     * 2. **단위가 묶인 기체마다 스냅숏을 한 번 읽는다.** 하나라도 못 읽으면(`null`) 실행을 만들지 않고 [RESUME_SNAPSHOT_UNREADABLE]
     *    로 시작하는 사유로 거부한다(사유에 못 읽은 기체가 실린다). 스냅숏 없이 지으면 첫 pump 가 `@rN` 없는 태스크 id 로 가서 이미 끝난 첫 태스크에 붙고, 도는 재작업 태스크는
     *    고아가 된다. 스냅숏이 넘긴 버전보다 높은 리비전으로 이 작업 지시의 태스크를 보이면 실행을 만들지 않고
     *    [RESUME_STALE_VERSION] 으로 시작하는 사유로 거부한다([staleVersionRefusal]).
     * 3. **넘겨받은 정의로 계획한다.** 카탈로그의 지금 활성 버전은 읽지 않는다. 근거 등급 검사만 거치고 **배정 관문을 걸지
     *    않으며 조치 탐색 기록도 남기지 않는다.** 명령이 이미 나간 실행이라 관문이 다시 판정할 대상이 아니고, 관문은 이미 집어 간
     *    자재를 출발 결품으로 읽어 거부할 수 있다.
     * 4. **로봇 단위의 재작업 횟수를 태스크 id 에서 되찾는다.** 스냅숏 태스크 id 가운데 `jobOrderId#unitId` 이거나
     *    `jobOrderId#unitId@rN` 인 것의 가장 큰 N(접미사가 없으면 0)을 [ExecutionUnit.attempt] 에 넣는다. 계약 스냅숏의 태스크
     *    `attempt` 칸은 발신자의 재시도 횟수라 다른 값이다.
     * 5. **앞선 설비 대기를 통과한 것으로 둔다.** 스냅숏에 태스크가 있는 마지막 로봇 단위보다 앞선 설비 대기 단위는 `DONE` 이다.
     *    실행은 단위를 차례로 돌므로 뒤 로봇 단위가 기체에 있으면 앞 대기는 이미 통과했고, 지금 신호로 다시 판정하면 그 뒤 바뀐
     *    신호로 보류나 중단이 난다. 이 인스턴스가 관측하지 않았으므로 근거 등급은 E0 그대로이고, 그래서 작업 응답의 도달 근거
     *    등급도 E0 이다. 그 뒤의 설비 대기는 다시 기다리므로 재기동 뒤 바뀐 신호로 곧바로 보류나 중단이 날 수 있다.
     * 6. **앞선 로봇 단위의 완료를 다시 확인하지 않는다.** 같은 마지막 로봇 단위보다 앞선 로봇 단위는 [pump] 가 다시 관측한
     *    성공에 설비 근거를 다시 묻지 않고 근거 등급 E0 인 채 `DONE` 으로 둔다. 이전 인스턴스가 그 완료를 이미 확인했거나 못
     *    했고, 설비 값은 그 뒤 바뀌었을 수 있다. 실패로 다시 관측되면 보통의 실패 경로를 탄다. 마지막 로봇 단위는 보통대로 확인한다.
     *
     * 마지막 로봇 단위를 찾을 때 플릿 단위는 세지 않는다. 4·5·6 의 사실은 단위 메모가 아니라 실행 자취([Execution.eventTrail])에
     * [RESUME_ATTEMPT]·[RESUME_SIGNAL_PASSED]·[RESUME_PRIOR_ROBOT] 으로 남긴다. 단위 메모는 작업 응답의 미완 단위 값으로 나가기
     * 때문이다.
     *
     * **판단 기록([record])을 넘기면** 4·5·6 뒤에 그 사실을 그대로 옮긴다(7). 넘기지 않으면 아래의 것이 넘어오지 않는다.
     *
     * 7. **판단 기록을 옮긴다.** 감수한 결함, 승인 소모(조치 열의 걸음은 계획 앞에 다시 서고 승인 장부에 소모로 다시 선다),
     *    단위마다의 시도 번호, 요청 시각(근거 윈도우와 미확정 유예의 기준, 설비 대기의 기한 시작), 검증 대기의 창, 이미 내린
     *    판정, 운영자 판단, 건 취소, 파지 이력이다. **다시 판정하지 않는다** — 판정이 있는 단위는 다시 돌리지 않고, 앞선 로봇 단위라도 기록의 근거
     *    등급을 그대로 쓴다. 시각은 그 시도를 다시 낼 때 쓰므로 기한은 재기동 시각이 아니라 처음 시작한 시각부터 이어 간다.
     *    스냅숏이 기록보다 큰 시도를 보이는 단위는 기록이 그 재작업보다 먼저 적힌 것이라 그 단위의 시각과 판정을 옮기지 않고
     *    [RESUME_RECORD_SUPERSEDED] 로 남긴다. 그 단위의 파지 이력은 버리지 않고 앞 시도의 것으로 접는다. 기록의 작업 지시 id 나 버전이 넘긴 작업 지시와 다르거나 계획에 없는 단위가 있으면
     *    [RESUME_RECORD_MISMATCH] 로 시작하는 사유로 거부하고 실행을 만들지 않는다 — 일부만 옮기면 옮기지 못한 사실이 «없었다» 가
     *    된다. 기록 대조는 스냅숏보다 먼저 한다.
     * 8. **운영자 보류로 옮긴 단위마다 인시던트를 다시 연다.** 인시던트는 기록에 들지 않으므로 이 인스턴스에서 새로 열고, 실행
     *    자취의 [RESUME_HOLD_REOPENED] 가 그 인시던트의 근거 윈도우에 실려 다시 연 것임을 가른다. 그 줄의 시각과 인시던트의
     *    `at` 은 시계를 한 번 읽은 같은 값이다(줄이 있는 것만으로는 가르지 못한다, [reopenHold]). 판단은 보통의 보류와 같이
     *    [resolve] 로 낸다. 같은 작업 지시로 다시 부르면 리비전 경로라 다시 열지 않는다.
     *
     * **기록 없이 넘어오지 않는 것**: 운영자 판단, 감수한 결함([Execution.acknowledgedFaults]), 승인 소모 기록, 근거 윈도우의 요청
     * 시각, 설비 대기의 기한 시작 시각, 앞선 로봇 단위에 이전 인스턴스가 낸 근거 확인 결과, 건 취소. 모두 이 인스턴스에서 새로
     * 서거나(6 은 E0 으로) 비어 있다. 계약과 내보내기 형식은 그대로다.
     *
     * **스냅숏은 단위가 묶인 기체마다 읽는다**([Execution.robotOf]). 오늘은 단위가 전부 실행의 기체라 한 번이다. 기체가 N 이면 N 번
     * 읽고 하나만 못 읽어도 다시 짓기를 미룬다(ADR 51 «대가») — 그 기체의 단위는 재작업 횟수를 정할 근거가 없고, 못 읽은 기체를
     * 빼고 지으면 그 단위가 이미 끝난 태스크에 붙는다.
     *
     * **첫 pump 전에 건 취소**는 붙지 않은 태스크에 `StartTask` 없이 붙어 멈추고 그 종료를 본 뒤 정착한다([attachForCancel]). 기록이
     * 건 취소를 들고 오면 실행은 그 취소가 걸린 채로 지어지므로(7) 담는 쪽이 다시 걸지 않아도 같다. 기록 없이 첫 pump 가 붙은 뒤로
     * 미루면 그 사이 취소로 끝난 태스크를 이 인스턴스가 요청 없는 취소로 읽는다([holdUnrequestedCancel]).
     */
    fun resume(order: JobOrder, robotId: String, mission: ActiveMission, record: JudgmentRecord? = null): Submission {
        forgottenRefusal(order.jobOrderId)?.let { return it }
        val existing = executions.values.firstOrNull { it.order.jobOrderId == order.jobOrderId }
        if (existing != null) return revise(existing, order)

        if (mission.capability.workMasterId != order.workMasterId) {
            return Submission.Rejected(
                "넘겨받은 임무 정의의 WorkMaster 가 작업 지시와 다르다: 정의=${mission.capability.workMasterId}, 작업 지시=${order.workMasterId}",
            )
        }
        // 승인된 조치 열은 계획 앞에 선다(`commit` 과 같은 모양). 기록이 그 걸음을 들고 오지 않으면 다시 지을 길이 없다.
        val planned = record?.approval?.steps.orEmpty().map { remedyUnit(it.unitId, it.skillType, it.parameters) } +
            mission.capability.plan(order)
        record?.let { recordRefusal(order, it, planned) }?.let { return it }
        val snapshots = linkedMapOf<String, RobotSnapshot>()
        for (robot in planned.map { it.robotBinding ?: robotId }.distinct()) {
            snapshots[robot] = robots.snapshot(robot)
                ?: return Submission.Rejected("$RESUME_SNAPSHOT_UNREADABLE: robot=$robot, 재작업 횟수와 지난 설비 대기를 정할 근거가 없다")
        }
        staleVersionRefusal(order, snapshots)?.let { return it }
        evidenceRefusal(order, mission.capability)?.let { return it }

        val execution = create(order, robotId, mission, planned, approvedBy = null)
        rebuildFrom(execution, snapshots)
        val reopenedAt = record?.let { restore(execution, it) }
        execution.units.filter { it.route == Route.ROBOT }.forEach { execution.inherited[it.unitId] = it.attempt }
        // 8 이 다시 연 인시던트를 지금 봉한다. 지금의 현장 시간값으로 — 라운드의 값([round])은 [pump] 의 것이라 건드리지 않는다.
        // 봉인 시각은 다시 연 줄의 시각이다. 따로 읽으면 실제 시계에서 둘이 어긋나 줄 시각 = `at` 으로 가르지 못한다.
        incidentLog.sealIncidents(execution, siteTimings.current(), reopenedAt)
        return Submission.Accepted(execution)
    }

    /**
     * 이 실행의 **판단 기록**(§15.221) — 담는 쪽이 영속하고 재기동 뒤 [resume] 에 넘길 사실. 모르는 실행이면 널이다.
     *
     * 언제 받아 둘지는 담는 쪽이 정한다. 판정과 판단은 [pump]·[resolve]·[release]·승인 안에서 서므로 그 뒤마다 받아 두면 빠지는 것이
     * 없다. 다시 지은 뒤 아직 내지 않은 시도의 시각도 싣는다 — 다시 지은 직후 다시 죽어도 기한의 시작을 잃지 않는다.
     */
    fun judgmentRecord(executionId: String): JudgmentRecord? {
        val execution = executions[executionId] ?: return null
        return JudgmentRecord(
            jobOrderId = execution.order.jobOrderId,
            orderVersion = execution.order.version,
            acknowledgedFaults = execution.acknowledgedFaults.toList(),
            approval = execution.approval,
            units = execution.units.map { unit ->
                val issued = unit.taskId.isNotEmpty()
                val pendingRequest = execution.restoredRequests[unit.unitId]?.takeIf { it.first == unit.attempt }?.second
                val pendingWindow = execution.restoredWindows[unit.unitId]?.takeIf { it.first == unit.attempt }?.second
                UnitRecord(
                    unitId = unit.unitId,
                    attempt = unit.attempt,
                    taskId = unit.taskId,
                    requestedAt = (if (issued) unit.requestedAt else null) ?: pendingRequest,
                    verifyWindow = if (unit.state == UnitState.VERIFYING) {
                        VerifyWindow(unit.downstreamDoneAt!!, unit.evidenceDeadline!!)
                    } else {
                        pendingWindow
                    },
                    verdict = unit.takeIf { it.state in UnitVerdict.STATES }?.let {
                        UnitVerdict(it.state, it.reached, it.verification, it.evidenceAt, it.failureClass, it.note, it.result)
                    },
                    decision = execution.decisions[unit.unitId],
                    everHeld = unit.everHeld,
                    heldInEarlierAttempt = unit.heldInEarlierAttempt,
                )
            },
            cancelRequested = execution.cancelRequested,
        )
    }

    /**
     * **배정 관문**(설계안 §7)의 공개 창구. 판정은 [AdmissionGate.admits] 가 한다.
     *
     * 이름을 여기 두는 이유: `orchestration.md` §2 와 ADR 42 가 `Middleware.admits` 로 대고,
     * 게이트는 §2 표의 점 기호마다 주인과 멤버가 한 파일에 선언돼 있는지 본다.
     *
     * **새 작업 지시로 묻는다.** 그 작업 지시의 실행이 이미 서 있어도 빼지 않고 세므로, 그 실행이 잡은 자리·구역에 걸려 거절로 답할 수 있다.
     * 이미 선 작업 지시는 [submit]·[adopt] 가 이 관문 앞에서 리비전 경로로 가른다. 빼고 물으면 지금 기체가 아닌 후보에 통과를 내는데, 그
     * 작업 지시는 그 후보로 가지 않는다.
     */
    fun admits(order: JobOrder, robotId: String, planned: List<ExecutionUnit>): Admission =
        gate.admits(order, robotId, planned)

    /**
     * **제안 포트**(설계안 §7) — 순위 목록을 받아 위에서부터 첫 통과를 채택한다.
     *
     * 순서는 배정기의 것이고 이 층은 그것을 다시 정렬하지 않는다. 다만 **채택 시점에 관문을 다시 묻는다** —
     * 순위가 만들어진 뒤 그 기체의 상태가 바뀌었을 수 있고, 그 창은 통신 지연이 아니라 읽는 자와 쓰는 자가
     * 다르기 때문에 생긴다. 낡은 제안은 여기서 걸린다.
     *
     * 전부 떨어지면 [Unassigned] 다. **재계산을 요청하지 않는다** — 요청하면 이 층이 중재자가 된다.
     *
     * **전부 떨어졌을 때만 후보마다의 거절을 조치 탐색 대장과 제안 장부에 적는다**(§15.164) — [submit] 이 한 기체에서 막혔을 때와 같은
     * 기록이고 열쇠도 (기체, 작업 지시)다. 관문([admits])은 그대로 순수하다. 적는 것은 순위 목록을 끝까지 시도한 이 함수다.
     *
     * **이미 실행이 선 작업 지시는 관문에 대지 않는다.** [submit] 과 같이 리비전 경로로 간다 — 같은 버전이면 [Submission.Idempotent],
     * 낮으면 거부, 높으면 그 실행의 개정이다. 순위 목록은 읽지 않으며 개정된 실행은 지금 기체에 남는다. 관문에 먼저 대면 그 실행이 제 자리와
     * 제 구역에 걸려 «배정할 기체가 없다» 로 읽힌다.
     */
    fun adopt(order: JobOrder, ranked: List<String>): Submission {
        // 놓은 작업 지시는 관문에 대기 전에 거부한다. 후보가 전부 떨어지면 «배정할 기체가 없다» 로 읽힌다.
        forgottenRefusal(order.jobOrderId)?.let { return it }
        // 이미 선 실행은 카탈로그도 안 본다 — [submit] 과 같은 이유다.
        executions.values.firstOrNull { it.order.jobOrderId == order.jobOrderId }?.let { return revise(it, order) }
        // **카탈로그를 한 번 읽고 그 쌍을 작업 수락까지 넘긴다.** 관문에 댄 계획과 실행이 쥘 계획이 같은 버전이어야
        // 한다 — 그 사이에 활성화가 끼면 관문이 본 적 없는 단위가 나간다.
        val mission = missions.active(order.workMasterId)
            ?: return Submission.Rejected("모르는 논리적 케이퍼빌리티다: ${order.workMasterId}")
        val planned = mission.capability.plan(order)
        val refusals = linkedMapOf<String, String>()
        val missing = linkedMapOf<String, RemedyOutcome.SourceMissing>()
        val unobserved = linkedMapOf<String, SourceUnobserved>()
        val tried = linkedMapOf<String, Admission.Refused>()
        for (robotId in ranked) {
            when (val admission = admits(order, robotId, planned)) {
                Admission.Passed -> return submit(order, robotId, prefix = emptyList(), mission = mission)
                is Admission.Refused -> {
                    refusals[robotId] = admission.rejection.reason
                    admission.sourceMissing?.let { missing[robotId] = it }
                    admission.rejection.sourceUnobserved?.let { unobserved[robotId] = it }
                    tried[robotId] = admission
                }
            }
        }
        // **전부 떨어졌으면 후보마다 시도한 것으로 적는다**(§15.164). [submit] 이 한 기체에 내려 막혔을 때와 같은 [RemedyDesk.record] 이고
        // 열쇠도 같은 (기체, 작업 지시)다 — 조치 열을 찾은 후보마다 제안이 서고, 두 문이 그 열쇠로 승인한다. 순위 목록의 차례대로 적는다.
        // 하나라도 통과하면 위에서 돌아가므로 앞에서 떨어진 후보는 적지 않는다 — 채택된 작업 지시의 제안은 승인해도 조치 열이 못 나간다(ADR 46).
        // [submit] 이 관문 앞에서 거부했을 작업 지시(근거 등급 초과·단위 id 중복)도 적지 않는다 — 그 제안을 승인하면 같은 거부를 받는다.
        // 답의 모양은 그대로다. 가린 제안은 [withheldProposal] 로 본다.
        if (evidenceRefusal(order, mission.capability) == null && duplicateUnitIdRefusal(planned) == null) {
            tried.forEach { (robotId, admission) -> desk.record(robotId, order, admission.rejection, admission.sourceMissing) }
        }
        return Unassigned(refusals, missing, unobserved)
    }

    /**
     * 비용으로 순위를 매기고 채택한다 — 배정기가 붙기 전의 기본 정책.
     *
     * 항은 이 층이 실제로 아는 것뿐이다([AssignmentCost]). 가중치를 바꾸면 순위가 바뀌고 **관문의 답은
     * 그대로다** — 정책이 판정을 흔들지 않는다는 것이 이 배선의 요점이다.
     */
    fun assign(order: JobOrder, candidates: List<String>): Submission =
        adopt(order, cost.rank(candidates, live = ::liveExecutionCount, holding = ::isHolding))

    /**
     * 이 기체에서 지금 도는 실행 수. 비용의 항이고 관문의 입력은 아니다.
     *
     * [excluding] 은 재할당이 쓴다 — **옮길 일 자신을 양쪽에서 빼야** 두 기체의 부담을 같은 자로 잰다.
     * 빼지 않으면 지금 든 쪽이 그 일 하나만큼 늘 불리해 보여, 이득이 없어도 옮기는 쪽으로 기운다.
     */
    private fun liveExecutionCount(robotId: String, excluding: String? = null): Int =
        executions.values.count {
            // 단위가 묶인 기체도 센다 — 그 기체는 이 실행의 단위를 맡았다.
            robotId in it.robots && !it.physicalState.isSettled && it.executionId != excluding
        }

    /** 지금 든 채인가. [AdmissionGate.liveHold] 의 답을 비용이 읽는 모양으로 줄인 것이다. */
    private fun isHolding(robotId: String): Boolean =
        gate.liveHold(robotId)?.kind == HoldKind.HOLD_KIND_HOLDING

    /**
     * 이 실행을 다른 기체로 옮긴다(설계안 §7) — **진동 방지가 붙은 자리.**
     *
     * 언제 부를지는 배정 정책의 몫이고(ADR 41), 트리거는 **이미 사건 번들이 생기는 그 자리**다 —
     * 실패 판정·깨진 전제·잔여 파지. 이 층이 하는 일은 «옮겨도 되는가» 를 판정하는 것뿐이며,
     * 진동을 막는 장치가 셋이다: 도는 단위, 최소 유지 시간, 이득의 문턱. 그 앞에서 반쯤 한 물리 작업을 거른다(아래) —
     * 그래서 잔여 파지가 트리거면 그 실행은 안 옮겨진다.
     *
     * **도는 단위가 있으면 안 옮긴다.** 물리적으로 움직이는 중인 일을 옮기면 한 물건에 소유자가 둘이 된다.
     *
     * **옮기는 것은 아직 물리적으로 손대지 않은 실행뿐이다.** 끝난 단위가 있거나, 로봇 단위가 한 번이라도 쥔 것이
     * 관측됐으면(지금 든 채든, 쥐었다가 지금은 모르든) 안 옮긴다 — 반쯤 한 물리 작업(놓인 자재·든 물건·채운 슬롯)을
     * 다른 기체가 이어받게 하는 인계는 이 함수가 보장하지 않는다. 플릿 경로의 파지는 플릿 AMR 이 용기를 든 것이라
     * 이 판정에 넣지 않는다.
     *
     * 관문은 **옮길 실행 자신을 빼고** 묻는다([AdmissionGate.admits] 의 `moving`). 그 실행은 아직 표에 있어서, 빼지
     * 않으면 자기 자리·자기 구역에 걸려 어느 후보도 통과하지 못한다. 빼는 것은 그 실행 하나이고 떠나는 기체의 다른
     * 실행은 그대로 센다.
     *
     * 후보가 실재하는지·연결됐는지·그 스킬을 드는지는 보지 않는다 — 능력을 못 물은 기체의 조건은 지어내지 않고
     * 접수 때 발신자가 판정한다(권위는 발신자다). **퇴역한 후보는 거른다**(§15.249) — 옮기면 발행 관문이 첫 단위부터 막는다.
     */
    fun reassign(executionId: String, candidates: List<String>): Reassignment {
        val execution = executions[executionId]
            ?: return Reassignment.Kept("", "모르는 실행이다: $executionId")
        val from = execution.robotId
        if (execution.physicalState.isSettled) return Reassignment.Kept(from, "종료한 실행이다 — 옮길 것이 없다")
        if (execution.active != null) return Reassignment.Kept(from, "도는 단위가 있다 — 움직이는 중인 일은 안 옮긴다")
        execution.units.firstOrNull { it.state == UnitState.DONE }?.let {
            return Reassignment.Kept(
                from,
                "끝난 단위가 있다(${it.unitId}) — 반쯤 한 물리 작업의 인계는 재할당이 보장하지 않는다",
            )
        }
        execution.units.firstOrNull { it.route == Route.ROBOT && (it.everHeld || it.heldInEarlierAttempt) }?.let {
            return Reassignment.Kept(
                from,
                "자재를 다룬 단위가 있다(${it.unitId}, 마지막 파지 ${it.hold.kind.name}) — " +
                    "반쯤 한 물리 작업의 인계는 재할당이 보장하지 않는다",
            )
        }

        val held = Duration.between(execution.assignedAt, now())
        if (held < reassignPolicy.minHold) {
            return Reassignment.Kept(from, "최소 유지 시간 안이다 — ${held.seconds}초 지났고 ${reassignPolicy.minHold.seconds}초가 필요하다")
        }

        // 남은 단위로만 관문을 건다 — 끝난 단위는 사실이고 다시 재단하지 않는다. 위에서 끝난 단위가 있으면 돌려보냈으므로
        // 지금은 전부이고, 그래서 자재 선언과 배정의 대조도 주문 전체로 맞는다.
        val remaining = execution.units.filter { it.state != UnitState.DONE }
        val mine = liveExecutionCount(from, excluding = executionId)
        var best: Pair<String, Int>? = null
        for (robotId in candidates) {
            if (robotId == from) continue
            if (issuing.retirementOf(robotId) != null) continue
            if (gate.admits(execution.order, robotId, remaining, moving = executionId) !is Admission.Passed) continue
            val load = liveExecutionCount(robotId, excluding = executionId)
            if (best == null || load < best.second) best = robotId to load
        }
        val target = best ?: return Reassignment.Kept(from, "게이트를 통과하는 후보가 없다")

        // **더 싸야 옮긴다.** 같으면 그대로 둔다 — 동점에서 움직이는 것이 진동의 시작이다.
        val saved = mine - target.second
        if (saved <= reassignPolicy.margin) {
            return Reassignment.Kept(from, "이득이 문턱 이하다 — ${saved} 이고 문턱은 ${reassignPolicy.margin} 이다")
        }

        // 묶지 않은 단위는 실행의 기체를 따라 함께 옮긴다. 다른 기체에 묶인 단위는 그대로다 — 그 묶음은 이 실행의 기체와 따로 정한 것이다.
        execution.robotId = target.first
        execution.assignedAt = now()
        execution.trail("REASSIGNED", "$from -> ${target.first} (부담 차 $saved)")
        return Reassignment.Moved(from, target.first, saved)
    }

    // ── 구동

    /**
     * 이번 라운드의 현장 시간값. [pump] 가 시작할 때 [siteTimings] 를 한 번 읽어 둔다. 널이면 케이퍼빌리티 값이다.
     *
     * 라운드 안에서는 다시 읽지 않는다. 판정마다 읽으면 한 라운드 안에서 두 버전의 값이 섞인다.
     */
    private var round: SiteTimings? = null

    /** 이 라운드의 근거 시간 윈도우. 현장 값이 있으면 그것, 없으면 케이퍼빌리티 값이다. */
    internal fun windowOf(execution: Execution): EvidenceWindow = round?.evidenceWindow ?: execution.capability.evidenceWindow

    /** 이 라운드의 `IN_DOUBT` 유예. 단위에 저장하지 않는다. */
    internal fun inDoubtGraceOf(execution: Execution): Duration = round?.inDoubtGrace ?: execution.capability.inDoubtGrace

    /** 이 라운드의 진행 정체 유예. */
    internal fun stallWindowOf(execution: Execution): Duration = round?.stallWindow ?: execution.capability.stallWindow

    /**
     * 하류에서 온 것을 읽고 상태를 한 걸음 민다. 몇 번 불러도 같은 결과다(멱등).
     *
     * **포트가 던져도 한 실행의 일이다.** 기체 동기화와 실행의 라운드 진행을 각각 격리한다 — 던지면 그 기체의 실행들(동기화) 또는 그
     * 실행(진행)의 트레이스에 [PUMP_FAILED] 를 같은 까닭이면 한 번만 남기고, 상태는 되돌리지 않은 채 다음 실행으로 간다.
     * 던진 실행은 다음 라운드에 처음부터 다시 본다. 격리가 없으면 표에서 던지는 실행 뒤의 실행이 그 실행이 던지는 동안 매 라운드 굶는다.
     */
    fun pump() {
        round = siteTimings.current()
        val live = executions.values.filter { it.physicalState.pumps }
        // 단위가 묶인 기체까지 동기화한다 — 안 하면 그 기체의 결함·연결은 «못 봤다» 로 남아 관문이 그 단위를 막지 않는다.
        live.flatMap { it.robots }.distinct().forEach { robot ->
            val related = live.filter { robot in it.robots }
            val source = "sync:$robot"
            try {
                sync(robot, related)
                related.forEach { it.noticed.clear(Notice.PumpFailure(source)) }
            } catch (e: RuntimeException) {
                related.forEach { it.notePumpFailure(source, "robot=$robot 동기화", e) }
            }
        }
        executions.values.forEach { pump(it) }
        trimSettled()
    }

    /**
     * 실행 표가 [Retention.executions] 를 넘으면 놓을 수 있는 실행을 오래된 것부터 놓는다(§15.296). 놓는 규칙은 [forget] 그대로다 —
     * 구동이 돌리거나 확인 안 된 통보가 있는 실행은 거부되어 남는다. 담는 쪽이 [forget] 을 부르지 않아도 표가 끝없이 늘지 않는다.
     */
    private fun trimSettled() {
        var excess = executions.size - caps.executions
        if (excess <= 0) return
        for (id in executions.keys.toList()) {
            if (excess <= 0) return
            if (forget(id) == ForgetOutcome.Forgotten) {
                executionsEvicted++
                excess--
            }
        }
    }

    /** 포트가 던진 것을 이 실행의 트레이스에 남긴다. [source] 마다 같은 까닭은 한 번만 남는다. */
    private fun Execution.notePumpFailure(source: String, where: String, e: RuntimeException) {
        val cause = "${e::class.simpleName}: ${e.message}"
        if (!noticed.note(Notice.PumpFailure(source), cause)) return
        trail(PUMP_FAILED, "$where 중 포트가 던졌다 ($cause) — 상태는 그대로 두고 다음 라운드에 다시 본다")
    }

    /** 시계 차 의심을 트레이스에 한 번 남긴다. 같은 방향·같은 크기 등급이면 되풀이하지 않고, 허용 안으로 들어오면 표시를 지운다. */
    internal fun Execution.noteClockSkew(robot: String, offset: ClockOffset) {
        val suspicion = clockSuspicionOf(this, offset)
        if (suspicion == null) {
            noticed.clear(Notice.ClockSkew(robot))
            return
        }
        if (!noticed.note(Notice.ClockSkew(robot), suspicion.signature)) return
        trail(
            CLOCK_SKEW,
            "robot=$robot ${suspicion.describe()} (임계 ${suspicion.threshold.toMillis() / 1000.0}초) — 근거 시간 윈도우가 이 차를 그대로 먹는다. 판정은 바꾸지 않는다",
        )
    }

    /** [at] 은 이 줄의 시각이다. 기본은 지금이고, 같은 시각을 다른 사실과 나눠 써야 하는 자리만 넘긴다([reopenHold]). */
    internal fun Execution.trail(kind: String, detail: String, at: Instant = now()) {
        eventTrail += ObservedEvent(views[robotId]?.cursor ?: 0, at.toString(), kind, detail, local = true)
    }

    private fun pump(execution: Execution) {
        val pumped = execution.physicalState.pumps
        try {
            // 보내는 중인 취소가 있으면 그 결과부터 받는다. 아직이면 이 라운드는 진행하지 않는다.
            if (!execution.command.pollCancel()) pumpRound(execution)
            execution.noticed.clear(Notice.PumpFailure(PUMP_SOURCE))
        } catch (e: RuntimeException) {
            execution.notePumpFailure(PUMP_SOURCE, "라운드 진행", e)
        }
        if (pumped) noteAttention(execution)
        incidentLog.sealIncidents(execution, round)
    }

    private fun pumpRound(execution: Execution) {
        if (!execution.physicalState.pumps) return

        // **보내는 중인 출발이 있으면 그 결과부터 받는다**(§15.237). 첫 출발과 개정 갱신은 결과를 받기 전까지 이 실행의 다른 일을 하지
        // 않는다 — 취소·미룬 갱신·다음 단위가 그 결과에 달렸다. 미확정 조회는 [resolveDoubt] 가 받는다.
        execution.command.inFlight?.takeIf { it.kind != InFlight.Kind.LOOKUP }?.let { flight ->
            execution.command.settleIssue(flight, issueStart(flight))
            return
        }
        // 플릿의 첫 맡기기도 같다. 미확정 조회는 [resolveDoubt] 가 받는다.
        execution.command.fleetInFlight?.takeIf { it.kind == FleetFlight.Kind.START }?.let { flight ->
            execution.command.settleDispatch(flight, issueDispatch(flight.order))
            return
        }

        // **닿지 못한 취소는 닿을 때까지 다시 보낸다.** 도는 단위의 태스크에만 의미가 있다 — 이미 끝난 태스크(검증 대기)에는 보내지
        // 않고, 그대로 끝나면 취소 응답이 닿지 못했다고 말한다([abort]). 미확정 중에는 핸들이 없고, [resolveDoubt] 가 붙으면서 보낸다.
        if (execution.cancelUnsent && execution.active?.state == UnitState.RUNNING) execution.command.sendCancel()
        // 다시 보낸 취소가 잠금 밖에서 나가는 중이면 이 라운드는 여기까지다. 같은 라운드에 단위가 끝나 중단을 적으면 그 답(거부 사유)
        // 없이 «닿지 못했다» 로 적는다.
        if (execution.cancelInFlight != null || execution.fleetCancelInFlight != null) return

        // **연결이 끊기면 도는 단위의 결과는 미확정이다.** 계약이 OFFLINE 과 CONNECTION_BROKEN 을 가른다(완료 기준 5);
        // HIBERNATING 은 침묵하지만 정상이다(§4.7). 되돌아오면 스냅샷으로 태스크 상태를 다시 세워 이어 간다 —
        // 재실행이 아니다. 어느 쪽도 상류에 한 번씩 알린다(16장 — 결과가 아직 확인되지 않았다는 사실).
        // **스냅숏을 못 읽는 것도 유예가 지나면 끊김이다**(§15.242). 하위가 다시 뜨지 않고 끊기면 포트가 던지지도 끊김을 말하지도 않는다.
        val runningRobotUnit = execution.active?.takeIf { it.route == Route.ROBOT && it.state == UnitState.RUNNING }
        val view = runningRobotUnit?.let { views[execution.robotOf(it)] }
        val unseenSince = view?.takeIf { !it.observable }?.lastObservedAt
            ?.takeIf { !now().isBefore(it.plus(execution.capability.linkLossWindow)) }
        if (unseenSince != null && runningRobotUnit != null && !execution.linkBroken) {
            execution.linkBroken = true
            execution.trail("LINK_BROKEN", "snapshot unreadable since $unseenSince while ${runningRobotUnit.unitId} is running — result unconfirmed")
            execution.physicalState = PhysicalState.IN_DOUBT
            notify(execution)
        }
        if (view != null && view.observable && runningRobotUnit != null) {
            val down = view.connection == ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN ||
                view.connection == ConnectionState.CONNECTION_STATE_OFFLINE
            if (down && !execution.linkBroken) {
                execution.linkBroken = true
                execution.trail("LINK_BROKEN", "${view.connection.name} while ${runningRobotUnit.unitId} is running — result unconfirmed")
                execution.physicalState = PhysicalState.IN_DOUBT
                notify(execution)
            } else if (!down && execution.linkBroken) {
                execution.linkBroken = false
                val downstream = view.tasks[runningRobotUnit.taskId]?.name ?: "(not in snapshot)"
                execution.trail("LINK_RESTORED", "snapshot says ${runningRobotUnit.taskId}=$downstream — resuming tracking, no re-execution")
                runningRobotUnit.annotate("link restored; snapshot state $downstream")
                execution.physicalState = PhysicalState.RUNNING
                notify(execution)
            }
        }

        // **미룬 개정의 갱신**(ADR 52). 허가가 돌아왔으면 지금 보낸다. 취소가 걸렸으면 보내지 않는다 — 멈추라는 말 뒤에 새 판을
        // 보내면 기체가 그 판으로 다시 움직일 수 있다.
        execution.active?.let { unit ->
            val previous = execution.command.deferredUpdate ?: return@let
            if (execution.cancelRequested || unit.state != UnitState.RUNNING) return@let
            if (issuable(execution, unit, WithheldIssue.REVISION_UPDATE)) {
                execution.command.deferredUpdate = null
                sendRevisionUpdate(execution, unit, previous)
            }
        }
        // 갱신이 보내는 중이면 이 라운드는 여기까지다(§15.237). 이어서 단위를 읽으면 태스크가 이미 끝난 경우 다음 단위의 출발이 나가
        // 보내는 중인 갱신을 덮는다 — 그 결과를 받을 자리가 사라진다. 보내는 중인 조회는 막지 않는다 — 그 결과는 아래 [resolveDoubt] 가 받는다.
        if (execution.command.inFlight?.kind == InFlight.Kind.UPDATE) return

        val active = execution.active
        if (active != null) {
            val settled = when {
                active.state == UnitState.IN_DOUBT -> execution.command.resolveDoubt(active)
                active.state == UnitState.VERIFYING -> checkEvidence(execution, active)
                else -> RouteDriver.of(active.route).pump(execution.command, active)
            }
            if (!settled) return
            // 미룬 갱신이 끝내 안 나갔다 — 단위는 옛 리비전으로 끝났고 그 결과는 지연 이벤트로 새 판의 기대에 대고 확인됐다.
            // 그 사실을 자취에 남기고 이 단위에 걸린 막음을 걷는다. 다음 단위의 출발은 따로 관문을 묻는다.
            execution.command.deferredUpdate?.let { previous ->
                execution.trail(ISSUE_WITHHELD, "${active.unitId}: revision ${active.revision} update never sent; task ended under revision $previous")
                execution.command.deferredUpdate = null
            }
            if (execution.permitBlock?.unitId == active.unitId) execution.permitBlock = null
            execution.active = null
            // 닫힌 단위의 태스크는 다시 안 읽는다 — 포트가 들던 갱신 목록을 놓게 한다(§15.210).
            execution.handle?.let { robots.release(execution.robotOf(active), it) }
            execution.handle = null
            execution.transport = null
            if (execution.cancelRequested && !execution.command.cancelWaitsOnHeldDoubt()) {
                // 하류 갱신을 읽은 경로는 abort 를 이미 적었다. 검증 대기 중에 취소가 걸린 경우만 여기서 적는다.
                if (execution.physicalState != PhysicalState.ABORTED) execution.command.abort(inProgress = active, hold = active.hold, cleanup = "not_applicable")
                return
            }
            // **설비 대기의 기한이 실행을 중단했다**(`onDeadline = ABORTED`). 취소 요청이 아니라서 위 분기에 안 걸리고,
            // 여기서 돌아가지 않으면 아래가 다음 단위를 찾아 출발시킨다 — 신호를 못 본 채 로봇이 움직인다.
            if (execution.physicalState == PhysicalState.ABORTED) return
        }

        // 취소가 걸린 채 미확정이 끝내 안 풀린 단위는 취소가 하위에 닿지 못했다 — 중단으로 닫지 않고 아래 운영자 보류에 둔다(§15.238).
        if (execution.cancelRequested && !execution.command.cancelWaitsOnHeldDoubt()) {
            // 다시 지은 실행이 아직 붙지 않은 태스크가 기체에 있으면 그것부터 멈춘다. 붙지 않은 채 정착하면 기체가 그 태스크를 하는
            // 동안 실행이 기체를 내준다. 플릿에 맡긴 운반도 같다(§15.254).
            if (RouteDriver.ATTACH_ORDER.any { it.attachForCancel(execution.command) }) return
            execution.command.abort(inProgress = null, hold = null, cleanup = "nothing_to_clean")
            return
        }

        // 운영자가 판단할 단위가 있으면 다음으로 가지 않는다 — 판단이 재작업이면 그 슬롯이 먼저다.
        val held = execution.units.firstOrNull { it.state == UnitState.OPERATOR_HOLD }
        if (held != null) {
            if (execution.cancelUnsent && (execution.command.cancelWait as? CancelWait.Held)?.unitId == held.unitId) execution.command.resendHeldCancel(held)
            execution.physicalState = PhysicalState.OPERATOR_HOLD
            if (execution.noticed.note(Notice.Hold, held.unitId)) {
                notify(execution)
            }
            return
        }

        val next = execution.units.firstOrNull { it.state == UnitState.PENDING }
        if (next == null) {
            settleExecution(execution)
            return
        }

        // **다시 지은 실행의 재부착은 새 발행이 아니다**(§15.219). 기체가 이미 하는 태스크에 붙는 것이라 아래 결함·허가 게이트를 묻지
        // 않는다 — 막으면 기체가 그 일을 하는 동안 이 인스턴스는 따라가지 않는다.
        if (next.route == Route.ROBOT && execution.command.reattachFromSnapshot(next)) return

        // **기체가 새 태스크를 못 받는다고 말하면 보내지 않는다.** 계약은 막지 않는다 — 결함은 인터록이 아니고
        // "판단은 밖으로"(§4.6·§15.87)이며, 그 밖이 여기다. 자동으로 넘어가지 않는다: 사람이 [release] 로 감수하거나
        // 결함이 사라져야 다음 단위가 나간다. 못 물어봤으면(null) 막지 않고 그 사실만 남긴다 — 관측 실패로 현장을
        // 세우지는 않되, 없다고도 하지 않는다. 이미 막고 있었으면 못 물어본 동안 그 막음을 그대로 둔다([blockingFaults]).
        val blocking = blockingFaults(execution, next)
        // **작업 허가는 결함과 함께 묻고 따로 둔다**(ADR 52). 둘 다 서 있으면 둘 다 보여야 하고, 해제([release])는 결함만
        // 감수한다. 그리고 ★**두 침묵은 다르다** — 위의 결함 관문은 못 물으면 통과시키고(관측 실패로 현장을 세우지 않는다),
        // 이 관문은 모르면(UNKNOWN) 막는다. 허가를 모른 채 내는 것은 «막혀 있을지 모르는 셀에 새 일을 낸다» 이다.
        // 로봇 경로만 묻는다 — 플릿 운반은 어느 기체가 갈지 플릿이 정하고, 설비 대기는 아무것도 보내지 않는다.
        val permitted = if (next.route == Route.ROBOT) {
            issuable(execution, next, WithheldIssue.START)
        } else {
            if (execution.permitBlock?.withheld == WithheldIssue.START) execution.permitBlock = null
            true
        }
        if (blocking.isNotEmpty()) {
            execution.blockedBy = blocking
            execution.physicalState = PhysicalState.OPERATOR_HOLD
            val key = blocking.joinToString { faultKey(it) }
            if (execution.noticed.note(Notice.Block, key)) {
                notify(execution)
            }
            return
        }
        if (execution.blockedBy.isNotEmpty()) {
            // **결함이 사라졌다** — 사람의 감수([release]) 없이 막음이 걷힌다. 실행 상태를 되돌리고 표시를 걷는다. 표시를 남기면 같은
            // 결함이 다음 경계에서 다시 설 때 알리지 않는다. 걷힌 것을 알리는 것은 라운드 끝의 대조다([noteAttention]) — 여기서 알리면
            // 아래 출발이 미확정을 알릴 때 한 라운드에 둘이 나간다.
            execution.blockedBy = emptyList()
            execution.noticed.clear(Notice.Block)
            execution.physicalState = PhysicalState.RUNNING
        }
        // 막히면 단위는 `PENDING` 그대로 기다린다. 실패도 운영자 보류도 아니고, 허가가 돌아오는 라운드에 나간다.
        if (!permitted) return
        // **선행 단위가 완료되지 않았으면 그 단위는 내지 않고 다음으로 간다**(§15.234). 결함과 허가를 먼저 묻는다 — 기체가 새 태스크를
        // 못 받는다고 말하면 그 사실이 실행 보류와 `blockedBy` 로 먼저 드러나고, 사람이 수용한 뒤에 이 단위를 거른다. 거른 뒤 이 라운드를
        // 다시 돌아 다음 단위를 같은 순서로 본다. 단위마다 한 번만 거르므로 깊이는 단위 수를 넘지 않는다.
        if (skipAfterPredecessor(execution, next)) {
            pumpRound(execution)
            return
        }
        // 기체의 선언을 아직 읽는 중이면 이 라운드에는 내지 않는다(§15.237). 허가가 막을 때처럼 단위는 `PENDING` 그대로 기다린다.
        if (next.route == Route.ROBOT && optionalsPending(execution, next)) return
        execution.command.startUnit(next)
    }

    /** 선택 파라미터를 바라는데 [unit] 기체의 선언을 아직 읽는 중인가([RobotPort.capabilitiesPending]). */
    private fun optionalsPending(execution: Execution, unit: ExecutionUnit): Boolean =
        execution.capability.preferredOptionals.isNotEmpty() && robots.capabilitiesPending(execution.robotOf(unit))

    /**
     * [unit] 의 선행 단위([ExecutionUnit.after])가 종료했는데 `DONE` 이 아니면 [unit] 을 태스크 없이 `FAILED` 로 두고 참을 돌려준다.
     * 선행이 없거나 `DONE` 이면 거짓이다.
     *
     * 모양은 계획 단계 실패(`NO_LOCATION_FOR_TARGET` 등)와 같다. 그래서 종결(`PARTIAL`·`FAILED`)과 리비전 규칙이 그대로 선다 — 리비전은
     * 이 단위를 다시 계획하지만 선행이 시도된 실패로 남아 있으면 다음 라운드에 같은 이유로 다시 거른다. 인시던트는 열지 않는다. 선행의
     * 실패가 이미 열었다. 이 단위를 다시 내는 길은 리비전이 아니라 종결한 뒤 사람이 낸 재작업이다([resolve], [resolveSkipped]).
     *
     * 선행이 아직 종료하지 않은 경우는 여기 오지 않는다. 단위는 목록 순서로 나가고 선행은 늘 앞에 있으며, 운영자 보류·검증 대기·미확정은
     * 이 앞에서 라운드를 끝낸다.
     */
    private fun skipAfterPredecessor(execution: Execution, unit: ExecutionUnit): Boolean {
        val after = unit.after ?: return false
        val predecessor = execution.units.firstOrNull { it.unitId == after } ?: return false
        if (predecessor.state !in ENDED_NOT_DONE) return false
        unit.state = UnitState.FAILED
        unit.failureClass = PREDECESSOR_NOT_DONE
        unit.note = "선행 단위 ${predecessor.unitId} 가 ${predecessor.state.name} 로 종료해 내지 않았다"
        execution.trail(PREDECESSOR_NOT_DONE, "${unit.unitId}: ${unit.note}")
        return true
    }

    /**
     * [next] 를 막는 결함 — 기체가 새 태스크를 못 받는다고 말하고 운영자가 감수하지 않은 것.
     *
     * **못 물어봤을 때 두 갈래다.** «관측 실패로 현장을 세우지 않는다» 는 **새로 막지 않는다** 는 말이다 — 처음부터 못 봤으면 지금처럼
     * 통과시키고 그 사실을 단위에 남긴다. 그러나 **이미 막고 있었으면**([Execution.blockedBy]) 그 막음을 그대로 돌려준다. 스냅숏을 한 번
     * 못 읽은 것은 결함이 사라졌다는 관측이 아니다 — 그것을 통과로 읽으면 사람이 세운 기체에 다음 단위가 나간다. 막음은 다시 읽은
     * 스냅숏에 결함이 없거나 사람이 [release] 로 감수할 때 걷힌다.
     *
     * **결함을 못 본다는 스냅숏도 못 물어본 것과 같다**(§15.263). 스냅숏은 읽혔지만 [RobotView.faults] 가 비어 있는 것은 결함이 없다는
     * 관측이 아니다.
     */
    private fun blockingFaults(execution: Execution, next: ExecutionUnit): List<Fault> {
        if (next.route != Route.ROBOT) return emptyList()
        val view = views[execution.robotOf(next)]
        if (view == null || !view.observable || !view.faultsObservable) {
            if (execution.blockedBy.isNotEmpty()) return execution.blockedBy
            // 한 번만 적는다 — 허가가 막는 동안 이 관문은 라운드마다 다시 불리고, 같은 메모가 쌓이면 응답의 미완 사유가 넘친다.
            val note = "robot faults not observable before start — proceeding without the gate"
            if (next.note?.contains(note) != true) next.annotate(note)
            return emptyList()
        }
        return view.faults.values.filter { !it.canAcceptNewTask && faultKey(it) !in execution.acknowledgedFaults }
    }

    /**
     * 운영자의 해제 — *지금 막고 있는* 결함을 감수하고 다음 단위로 간다. 결함을 지우지 않는다(그것은 기체 쪽 일이고
     * 계약에 그 표면이 없다). 감수한 열쇠가 남으므로 같은 결함은 다시 막지 않고, **새 결함은 다시 막는다.** 결함이 걷히면 열쇠를
     * 놓으므로 걷혔다가 다시 생긴 결함도 새 결함이다([forgetAcknowledged]).
     *
     * **작업 허가의 막음([Execution.permitBlock])은 건드리지 않는다**(ADR 52). 허가는 사람이 감수할 값이 아니라 원천이 다시
     * `PERMITTED` 를 낼 때만 걷힌다. 결함 없이 허가만 막고 있으면 거짓을 돌려준다.
     *
     * 참을 돌려줄 때 작업 응답 하나를 낸다(`RUNNING`, 빈 `blockedBy`). 거짓이면 내지 않는다.
     */
    fun release(executionId: String): Boolean {
        val execution = executions[executionId] ?: return false
        if (execution.blockedBy.isEmpty()) return false
        execution.blockedBy.forEach { execution.acknowledgedFaults += faultKey(it) }
        execution.blockedBy = emptyList()
        execution.noticed.clear(Notice.Block)
        execution.physicalState = PhysicalState.RUNNING
        // **막음이 걷힌 것을 곧바로 알린다.** 안 알리면 마지막 작업 응답이 실행이 다음에 알릴 때까지 `OPERATOR_HOLD` 와 감수한
        // 결함의 `blockedBy` 를 든다. 막는 결함이 없으면 위에서 거짓으로 돌아가므로 다시 불러도 응답이 더 나가지 않는다.
        notify(execution)
        return true
    }

    /** 이미 적은 (기체, 선택 키). 같은 사실을 되풀이하면 운영자가 곧 무시한다. */
    internal val droppedOptionals = mutableSetOf<Pair<String, String>>()

    /** 이 단위가 이번 라운드에 닫혔다. 봉하는 것은 [IncidentLog.sealIncidents] 다. */
    internal fun Execution.markIncident(unit: ExecutionUnit) {
        pendingIncidents += unit.unitId
    }

    /** 관측을 한 곳으로 — 쥔 것을 본 적이 있는지는 중단 시점의 기대를 정한다(설계안 §5). */
    internal fun ExecutionUnit.observeHold(observed: HoldState) {
        hold = observed
        if (observed.kind == HoldKind.HOLD_KIND_HOLDING) everHeld = true
    }

    /** 설비에 묻고 **그 사실을 자취에 남긴다** — 조회하고 버리면 번들의 근거 창에서 설비 쪽이 빈다. */
    internal fun Execution.observeCell(unit: ExecutionUnit): SlotSignal? {
        val where = unit.destination ?: return null
        val signal = cell.observe(where)
        val what = if (signal == null) {
            "no signal"
        } else {
            "occupied=${signal.occupied} identity=${signal.identity ?: "(none)"} at=${signal.observedAt?.toString() ?: "(read now)"}"
        }
        trail("CELL_SIGNAL", "$where: $what")
        return signal
    }

    private fun settleExecution(execution: Execution) {
        val states = execution.units.map { it.state }
        val before = execution.physicalState
        execution.physicalState = when {
            states.any { it == UnitState.OPERATOR_HOLD } -> PhysicalState.OPERATOR_HOLD
            states.all { it == UnitState.DONE } -> PhysicalState.PHYSICALLY_DONE
            states.all { it == UnitState.FAILED } -> PhysicalState.FAILED
            states.any { it == UnitState.FAILED } -> PhysicalState.PARTIAL
            states.any { it == UnitState.UNVERIFIED } -> PhysicalState.UNVERIFIED
            states.any { it == UnitState.ABORTED } -> PhysicalState.ABORTED
            else -> PhysicalState.PHYSICALLY_DONE
        }
        if (before != execution.physicalState || execution.upstreamAck == UpstreamAck.NOT_SENT) notify(execution)
    }

    // ── 근거 결합 (보고서 12장)

    /**
     * 운영자의 판단(12.3 둘째 행). [OperatorDecision.CONFIRM_DONE] 은 설비 근거로 완료(E2),
     * [OperatorDecision.REWORK] 는 그 단위를 **새 정체성**으로 다시 계획한다 — 자동으로 돌지 않았던 것을 사람이 돌린다.
     *
     * **사람만 판단한다**(ADR 47). 확인은 설비 근거 없이도 단위를 완료로 올리고 재작업은 실물을 다시 돌린다 — 둘 다
     * 자동으로 하지 않기로 한 것을 사람이 하는 자리라, 에이전트가 누르면 그 결정이 자동화로 되돌아간다.
     * **누가 판단했는지 없이는 판단이 안 된다** — 기본값을 두면 그 기본값이 무기명 판단 경로가 된다(ADR 43 과 같은 이유).
     *
     * 판단으로 실행의 보류가 걷히면 작업 응답 하나를 낸다(다른 단위가 아직 보류이면 실행은 보류 그대로이고 내지 않는다).
     *
     * **건너뛴 대상도 재작업을 받는다**(§15.234 의 소유자 결정). 단위가 보류가 아니라 선행 이동이 시도 뒤 실패해 내지 않은
     * 점검이면([skippedPair]) 그 점검과 이동을 한 쌍으로 새 시도로 되돌린다([resolveSkipped]). 받는 조건은 다섯이다.
     * 승인자가 사람, 실행이 메모리에 있음, 건너뛴 단위의 모양, 실행이 `PARTIAL`·`FAILED` 로 종결했고 더 낼 단위가 없음, 그 기체에
     * 다른 활성 실행이 없음. 앞의 둘은 위에서, 모양이 아니면 [ResolveOutcome.NotHeld], 나머지 둘이 서지 않으면
     * [ResolveOutcome.Refused] 다. 확인은 받지 않는다 — 하지 않은 점검은 확인할 것이 없다.
     */
    fun resolve(executionId: String, unitId: String, decision: OperatorDecision, approver: Approver): ResolveOutcome {
        if (approver.kind == ApproverKind.AGENT) {
            return ResolveOutcome.Refused("에이전트는 운영자 판단을 내지 못한다 — 확인과 재작업은 사람의 것이다: ${approver.id}")
        }
        val execution = executions[executionId] ?: return ResolveOutcome.NotHeld
        val unit = execution.units.firstOrNull { it.unitId == unitId && it.state == UnitState.OPERATOR_HOLD }
            ?: return resolveSkipped(execution, unitId, decision, approver)
        // 판단 기록이 싣는다(§15.221). 판단이 선 시도를 단다 — 재작업이면 아래에서 시도가 하나 오른다.
        execution.decisions[unitId] = RecordedDecision(decision, approver, now(), unit.attempt)
        when (decision) {
            OperatorDecision.CONFIRM_DONE -> {
                // 설비 근거가 있었으면 E2 다. 없이 사람이 확인한 것은 근거 등급을 올리지 않는다 — 그 사실이 note 에 남는다.
                if (unit.verification == Verification.MATCHED) unit.reached = Evidence.E2
                unit.state = UnitState.DONE
                unit.note = "operator confirmed" + (if (unit.verification == Verification.MATCHED) "" else " without equipment evidence") + ": ${unit.note}"
            }
            OperatorDecision.REWORK -> reworkAsNewAttempt(unit)
        }
        // 다른 단위가 아직 보류이면 실행은 보류 그대로이고 그 단위를 이미 알린 것으로 둔다. 아니면 보류가 걷힌다.
        val stillHeld = execution.units.firstOrNull { it.state == UnitState.OPERATOR_HOLD }
        execution.noticed.set(Notice.Hold, stillHeld?.unitId)
        execution.physicalState = if (stillHeld != null) PhysicalState.OPERATOR_HOLD else PhysicalState.RUNNING
        // **보류가 걷힌 것을 곧바로 알린다**([release] 와 같은 이유). 안 알리면 마지막 작업 응답이 다음 통지까지 `OPERATOR_HOLD` 를 든다.
        noteAttention(execution)

        // ★**사람의 걸음을 사건에 남긴다.** 안 남기면 사건과 그 뒤의 탐색 사이가 비어 보이고, 읽는
        //   쪽은 그 사이를 «자동으로 회복했다» 로 메운다(§15.188). 아직 판단이 안 실린 **가장 최근의**
        //   사건에 붙인다 — 같은 단위가 두 번 깨지면 걸음도 둘이고 각각 제 사건에 속한다.
        incidentLog.noteResolution(executionId, unitId, decision, approver)
        return ResolveOutcome.Resolved
    }

    // ── 제안과 승인(설계안 §6.4)

    /**
     * 탐색이 답한 것들, 답한 순서대로 — [RemedyDesk.remedySearches].
     *
     * 순서는 이 층이 보증한다 — 읽는 쪽이 `searchId` 를 뜯어 번호를 꺼내면 그 순간 형식에 묶이고,
     * 그 형식을 대는 시험은 어디에도 없다.
     */
    fun remedySearches(): List<RemedySearchRecord> = desk.remedySearches()

    /** 이 (기체, 주문)에 서 있는 제안. 없거나 가려져 있으면 널 — [RemedyDesk.proposal]. */
    fun proposal(robotId: String, jobOrderId: String): Remedy.Found? = desk.proposal(robotId, jobOrderId)

    /** 가려 둔 제안이 있는가 — [RemedyDesk.withheldProposal]. */
    fun withheldProposal(robotId: String, jobOrderId: String): Boolean = desk.withheldProposal(robotId, jobOrderId)

    /**
     * 사람이 먼저 진단한다 — [RemedyDesk.diagnose].
     *
     * @return 가려 둔 제안이 실제로 있었는가. 없으면 적히지 않는다 — 없는 사건에 진단을 남기지 않는다.
     */
    fun diagnose(robotId: String, jobOrderId: String, cause: String): Boolean = desk.diagnose(robotId, jobOrderId, cause)

    /** 사람이 먼저 적은 진단 — [RemedyDesk.diagnosis]. */
    fun diagnosis(robotId: String, jobOrderId: String): String? = desk.diagnosis(robotId, jobOrderId)

    /** 같은 조치가 거듭 승인된 것 — [RemedyDesk.repeatedRemedies]. */
    fun repeatedRemedies(atLeast: Int = 2): List<RepeatedRemedy> = desk.repeatedRemedies(atLeast)

    /**
     * **사람의 문**(설계안 §6.4) — 운영자가 그 자리의 값을 들고 제안을 승인한다.
     *
     * 파라미터는 **사람이 준다.** 탐색기는 어느 스킬을 딛을지까지만 계산하며, 그 스킬이 요구하는 값(어디에
     * 놓을 것인가 같은)은 지어낼 수 없다 — 지어내면 승인은 무엇을 승인하는지 모르는 채 누르는 단추가 된다.
     *
     * **주문을 안 받는다**(ADR 44). 승인은 «무엇을 하라» 가 아니라 «그것을 하라» 이므로, 가리킬 제안의
     * 열쇠만 받고 주문은 제안과 함께 이 층이 들고 있던 것을 쓴다.
     *
     * **에이전트는 이 문으로 못 들어온다.** 값을 실을 수 있다는 것이 곧 조치를 기술할 수 있다는 뜻이고,
     * 그러면 선언이 덮는 것이 «어느 스킬» 까지라서 실제 권한이 선언보다 넓어진다. 에이전트의 문은
     * [attemptApproval] 이며 그쪽에는 값을 실을 칸이 없다.
     *
     * **누가 눌렀는지 없이는 승인이 안 된다**(ADR 43). 기본값을 두면 그 기본값이 무기명 승인 경로가 되고,
     * 자원 소유 대장이 비워 두면 안 된다고 적은 자리가 바로 거기다.
     *
     * @param parameters 걸음마다 하나씩, 걸음 순서대로.
     * @return 거절이면 [Submission.Rejected.approvalRefusal] 가 종류를, `CONSUMED` 면 [Submission.Rejected.consumedApproval] 가 소모
     *   기록을 싣는다. 에이전트가 이 문으로 오면 [ApprovalRefusal.VALUES_NOT_ACCEPTED] 다.
     */
    fun approveRemedy(
        robotId: String,
        jobOrderId: String,
        parameters: List<Map<String, String>>,
        approver: Approver,
    ): Submission {
        if (approver.kind == ApproverKind.AGENT) {
            return Submission.Rejected(
                "에이전트는 값을 실어 승인하지 못한다 — 값은 선언과 관측에서 온다: ${approver.id}",
                approvalRefusal = ApprovalRefusal.VALUES_NOT_ACCEPTED,
            )
        }
        // 거절마다 종류를 싣는다 — 밖의 문과 같은 판정, 같은 값이다. 사유 산문은 그대로다.
        return when (val judgment = desk.judge(robotId, jobOrderId, approver, given = parameters, saw = null)) {
            is Judgment.No -> Submission.Rejected(
                judgment.reason,
                approvalRefusal = judgment.refusal,
                consumedApproval = judgment.consumed,
            )
            is Judgment.Go -> when (val submission = commit(judgment)) {
                // **접힌 접수는 승인이 아니다** — 밖의 문과 같은 사유로 거절한다(ADR 46). 멱등으로 돌려주면 받는 쪽이
                // «이미 받았다» 로 읽고, 조치 열이 안 나간 것이 성공의 모양으로 보인다.
                is Submission.Idempotent -> Submission.Rejected(
                    notApplied(submission.execution),
                    approvalRefusal = ApprovalRefusal.REMEDY_NOT_APPLIED,
                )
                is Submission.Rejected -> submission.copy(approvalRefusal = commitRefusal(judgment))
                else -> submission
            }
        }
    }

    /**
     * **밖의 문**(ADR 44) — 값을 싣지 못하는 승인 시도.
     *
     * 부르는 쪽의 권한이 여기서 «승인 시도 한 번» 을 넘지 않는 것은 규율이 아니라 **표면의 모양** 때문이다:
     * 주문도 걸음도 값도 실을 칸이 없다. 값은 사람의 선언과 기체의 관측에서만 오며 이 함수를 부르는 쪽은
     * 둘 다 못 바꾼다.
     *
     * **선언이 없으면 거절이다 — 승인자 종류를 안 가린다.** 사람이 선언 없이 누를 수 있는 것은 값을 들고
     * 오기 때문이고(ADR 43), 값 없이 들어오는 문에서는 선언이 곧 값의 출처다. 종류로 가르면 사람 이름을
     * 단 시도가 값 없이 통과하는 길이 생긴다.
     */
    fun attemptApproval(attempt: ApprovalAttempt): ApprovalOutcome {
        val judgment = desk.judge(
            attempt.robotId,
            attempt.jobOrderId,
            attempt.approver,
            given = null,
            saw = attempt.sawSkillTypes,
        )
        return when (judgment) {
            // 소모 기록은 판정이 든 그대로 옮긴다 — 부르는 쪽이 «내 요청» 을 가르는 재료다(ADR 46).
            is Judgment.No -> ApprovalOutcome.Refused(judgment.refusal, judgment.reason, judgment.consumed)
            is Judgment.Go -> when (val submission = commit(judgment)) {
                // **실린 값을 돌려준다** — 부르는 쪽이 고르지 않았으므로, 자기 이름으로 무엇이 나갔는지
                // 아는 길이 이것뿐이다.
                is Submission.Accepted -> ApprovalOutcome.Approved(
                    submission.execution.executionId,
                    judgment.prefix.map { ApprovedStep(it.skillType, it.parameters, it.unitId) },
                )
                is Submission.Rejected -> ApprovalOutcome.Refused(commitRefusal(judgment), submission.reason)
                // **접힌 접수는 승인이 아니다.** 그 주문이 이미 실행으로 서 있으면 조치 열은 안 나간다
                // (§15.175, ADR 46). 성공으로 내면 «승인했는데 아무 일도 안 일어났다» 가 초록으로 보인다.
                is Submission.Idempotent -> ApprovalOutcome.Refused(
                    ApprovalRefusal.REMEDY_NOT_APPLIED,
                    notApplied(submission.execution),
                )
                // **[adopt] 만 내는 값이다.** 여기로 오면 접수 경로가 바뀐 것이고, 조용히 삼키면
                // 승인이 안 된 채로 답만 돌아간다.
                is Unassigned -> error("접수가 이 값을 낼 자리가 아니다: $submission")
            }
        }
    }

    /**
     * 판정이 선 뒤 실제로 내린다.
     *
     * **관문이 거절하면 제안을 안 지운다.** 자리 경쟁으로 못 들어간 것은 자격의 문제가 아니고, 그 순간
     * 제안을 소모하면 사람이 나중에 누를 것까지 함께 사라진다 — 조건이 풀리면 같은 제안이 그대로 선다.
     * **바꾸지도 않는다.** 조치 열을 붙인 계획이 사슬 검사에 걸려 탐색이 다른 조치 열을 찾아도 그것은 제안으로 서지 않는다
     * ([RemedyDesk.record] 의 `approval`).
     */
    private fun commit(go: Judgment.Go): Submission {
        // **그 주문이 이미 실행으로 서 있으면 개정만 조치 열을 싣는다**(§15.175). 판이 같거나 낮으면 실을 개정이 없다. 실행이 다른
        // 기체에 서 있으면 조치가 제안의 기체가 아닌 곳에서 돈다. 이미 승인을 든 실행이면 판단 기록이 승인 하나만 싣는다. 끝난 실행에는
        // 새 판이 안 붙는다. 넷 다 여기서 접는다 — 그대로 내리면 승인이 성공을 내고 조치는 안 나가며 그 거짓이 소모 기록에 남는다(ADR 46).
        executions.values.firstOrNull { it.order.jobOrderId == go.order.jobOrderId }?.let { existing ->
            val ended = existing.physicalState == PhysicalState.ABORTED || existing.physicalState == PhysicalState.PHYSICALLY_DONE
            if (go.order.version <= existing.order.version || existing.robotId != go.robotId || existing.approval != null || ended) {
                return Submission.Idempotent(existing)
            }
        }
        // 놓은 작업 지시는 아래 접수가 거부한다([forgottenRefusal]). 거부라서 제안을 지우지 않고 소모로도 남기지 않는다.
        val submission = submit(go.order, go.robotId, go.prefix, approvedBy = go.approver)
        if (submission !is Submission.Accepted) return submission
        val consumed = desk.settle(go, submission.execution.executionId)
        // 판단 기록이 싣는다(§15.221). 다시 지을 때 걸음이 계획 앞에 다시 서고 승인 장부에 소모로 다시 선다.
        submission.execution.approval = RecordedApproval(go.robotId, consumed.approver, consumed.at, consumed.wallClockAt, consumed.steps)
        return submission
    }

    /**
     * 판정이 선 뒤 접수가 거절한 승인의 종류 — 두 문이 같은 값을 낸다.
     *
     * 놓은 작업 지시면 관문이 아니라 그 작업 지시가 이미 실행으로 섰다가 끝난 것이다 — 조치 열이 안 나간 것과 같은 답이다.
     */
    private fun commitRefusal(go: Judgment.Go): ApprovalRefusal =
        if (go.order.jobOrderId in forgotten) ApprovalRefusal.REMEDY_NOT_APPLIED else ApprovalRefusal.REFUSED_BY_GATE

    /** 접힌 승인의 사유 — 두 문이 같은 말을 한다(ADR 46). */
    private fun notApplied(execution: Execution): String =
        "그 주문이 이미 실행으로 서 있다 — 조치 열이 안 나갔다: " +
            "${execution.executionId} · ${execution.robotId} · 판 ${execution.version}"

    // ── 사건 번들(설계안 §4) — 장부는 IncidentLog 에 있고, 여기는 조회와 검토의 창구다.

    /** 열린 순서대로 — [IncidentLog.incidents]. */
    fun incidents(): List<IncidentBundle> = incidentLog.incidents()

    fun incident(incidentId: String): IncidentBundle? = incidentLog.incident(incidentId)

    /** 보존 상한으로 내린 기록의 수(§15.166, [Retention]). */
    fun retentionEvictions(): RetentionEvictions = desk.evictions(incidentLog.evicted, executionsEvicted, forgottenEvicted)

    /**
     * 사람이 사건을 읽고 판정을 남긴다 — [IncidentLog.reviewIncident].
     * **자동으로 채우지 않는다.** 동의도 사람이 눌러야 동의다.
     */
    fun reviewIncident(incidentId: String, verdict: ReviewVerdict, cause: String): Boolean =
        incidentLog.reviewIncident(incidentId, verdict, cause)

    /**
     * 승인자 종류별 검토 지표 — [IncidentLog.reviewMetricsByApprover].
     *
     * @param since 실 시계 기준 이 시각부터의 사건만. 널이면 전부.
     */
    fun reviewMetricsByApprover(since: Instant? = null): Map<ApproverKind, ReviewMetrics> =
        incidentLog.reviewMetricsByApprover(since)

    /**
     * 검토가 실제로 일어나는가 — [IncidentLog.reviewMetrics].
     *
     * @param since 실 시계 기준 이 시각부터의 사건만. 교대 단위로 보라고 있는 자리다. 널이면 전부.
     */
    fun reviewMetrics(since: Instant? = null): ReviewMetrics = incidentLog.reviewMetrics(since)

    // ── 취소 (보고서 14)

    /**
     * 취소를 건다. 실제 중단과 잔여 상태는 [pump] 가 하류를 읽고 [CancelReport] 로 확정한다. 다시 지은 실행이 아직 붙지 않았으면
     * 기체의 태스크에 붙어 취소를 보내는 것도 [pump] 다([attachForCancel]). 플릿에 맡긴 운반도 같다([attachTransportForCancel]).
     *
     * **참은 «받았다» 이지 «닿았다» 가 아니다.** 하위로 보내다가 포트가 던져도 이 호출은 던지지 않고 참을 돌려준다 — 건 취소는
     * 실행에 남고, 닿을 때까지 [pump] 가 다시 보낸다([sendCancel]).
     */
    fun cancel(executionId: String): Boolean {
        val execution = executions[executionId] ?: return false
        if (!execution.physicalState.pumps) return false
        execution.command.cancelIntent = CancelIntent.Requested
        execution.command.sendCancel()
        execution.physicalState = PhysicalState.CANCELING
        return true
    }

    fun lastCancel(executionId: String): CancelReport? = executions[executionId]?.lastCancel

    // ── 결과 통보 (보고서 13.3 — 명령 재시도와 통보 재시도의 분리)

    internal fun notify(execution: Execution) {
        val units = execution.units
        val response = JobResponse(
            jobResponseId = "resp-${++responseSeq}",
            jobOrderId = execution.order.jobOrderId,
            executionId = execution.executionId,
            version = execution.order.version,
            physicalState = execution.physicalState,
            requiredEvidence = execution.order.requiredEvidence,
            reachedEvidence = units.filter { it.state == UnitState.DONE }.minOfOrNull { it.reached } ?: Evidence.E0,
            completedUnits = units.filter { it.state == UnitState.DONE }.map { it.unitId },
            unverifiedUnits = units.filter { it.state == UnitState.UNVERIFIED }.map { it.unitId },
            incompleteUnits = units.filter { it.state != UnitState.DONE && it.state != UnitState.UNVERIFIED }
                .associate { it.unitId to (it.failureClass ?: it.note ?: it.state.name) },
            operatorRequired = units.any {
                it.state == UnitState.UNVERIFIED || it.state == UnitState.OPERATOR_HOLD ||
                    it.verification == Verification.MISMATCH || it.failureClass == SOURCE_MISMATCH
            } || execution.lastCancel?.cleanup == "failed" || execution.blockedBy.isNotEmpty(),
            // 든 단위가 있으면 그것, 없으면 마지막으로 관측한 파지(빈손) — "빈손" 을 "말하지 않았다" 로 접지 않는다(§15.145).
            residualHold = residualHoldOf(units),
            inDoubtUnits = units.filter { it.state == UnitState.IN_DOUBT }.map { it.unitId },
            results = units.filter { it.state == UnitState.DONE && !it.result.isNullOrBlank() }.associate { it.unitId to it.result!! },
            blockedBy = execution.blockedBy.map { canonicalClassOf(it) },
            // 기체 연결은 도는 단위가 묶인 기체의 것이다. 도는 단위가 없으면 실행의 기체다.
            connection = (views[execution.active?.let(execution::robotOf) ?: execution.robotId]?.takeIf { it.observable }?.connection ?: ConnectionState.CONNECTION_STATE_UNSPECIFIED).name,
            autoResolvesInDoubt = units.all { RouteDriver.of(it.route).lookupKind(this) == ExecutionLookup.CLIENT_REFERENCE },
        )
        outbox += response
        execution.noticed.set(Notice.Attention, Attention.of(response.physicalState, response.blockedBy, response.inDoubtUnits))
        execution.upstreamAck = UpstreamAck.SENT_UNACKED
    }

    /** 상위가 «사람이 볼 것이 있는가» 로 읽는 칸 — 보류·미확정인 실행 상태, 막는 결함의 분류, 미확정 단위. */
    private data class Attention(val state: PhysicalState?, val blockedBy: List<String>, val inDoubt: List<String>) {
        companion object {
            fun of(state: PhysicalState, blockedBy: List<String>, inDoubt: List<String>) = Attention(
                state.takeIf { it == PhysicalState.IN_DOUBT || it == PhysicalState.OPERATOR_HOLD }, blockedBy, inDoubt,
            )
        }
    }

    /**
     * **막음·보류·미확정이 마지막 작업 응답과 달라졌으면 알린다 — 풀릴 때도.**
     *
     * 서는 쪽은 자리마다 한 번씩 알린다([Notice.Block]·[Notice.Hold]·[Notice.Doubt]). 풀리는 쪽은 자리가 여럿이고(결함이 사라짐, 운영자
     * 판단, 조회가 미확정을 풂, 개정 갱신의 답) 자리마다 알리면 한 라운드에 둘이 나가거나 하나가 빠진다. 그래서 라운드 끝([pump])과
     * 밖에서 오는 판단([resolve]·개정) 끝에서 한 번 대 본다. 같으면 내지 않는다 — 앞 자리가 이미 알렸으면 여기서 다시 내지 않는다.
     *
     * 대는 것은 [Attention] 뿐이다. 단위가 끝나 완료 목록이 늘어난 것 같은 진행은 여기서 알리지 않는다 — 그것은 정착([settleExecution])의 일이다.
     */
    internal fun noteAttention(execution: Execution) {
        val current = Attention.of(
            execution.physicalState,
            execution.blockedBy.map { canonicalClassOf(it) },
            execution.units.filter { it.state == UnitState.IN_DOUBT }.map { it.unitId },
        )
        val last = execution.noticed[Notice.Attention] as? Attention ?: Attention(null, emptyList(), emptyList())
        if (current != last) notify(execution)
    }

    /**
     * 상류가 결과를 확정했다 — 두 축 중 둘째가 닫힌다. 그 응답을 확인하고, 그 실행의 통보가 전부 확인됐으면 실행의
     * [Execution.upstreamAck] 도 `ACKED` 로 둔다.
     */
    fun ack(jobResponseId: String): Boolean {
        val response = outbox.firstOrNull { it.jobResponseId == jobResponseId } ?: return false
        response.ack = UpstreamAck.ACKED
        val execution = executions[response.executionId] ?: return true
        if (outbox.none { it.executionId == execution.executionId && it.ack != UpstreamAck.ACKED }) {
            execution.upstreamAck = UpstreamAck.ACKED
        }
        return true
    }

    // ── 놓기 — 끝난 실행을 이 층의 메모리에서 뺀다

    /**
     * 담는 쪽이 **끝난 실행을 놓는다.** 실행 표([executions])와 아웃박스([responses])에서 그 실행과 그 통보를 빼고, 작업 지시 id 하나만
     * 남긴다. 언제 놓을지는 그 실행의 사실을 다 옮겨 적었는지 아는 담는 쪽이 정한다 — 부르지 않으면 실행 표 상한까지 남는다(아래).
     *
     * 놓는 것은 둘이 다 섰을 때뿐이다.
     *
     * 1. **구동이 더는 돌리지 않는다**([PhysicalState.pumps]가 거짓). `PARTIAL` 은 종착이지만 구동이 계속 돌리고 개정과 취소를 받으므로
     *    놓지 않는다 — 개정으로 다시 돌거나 [cancel] 로 `ABORTED` 가 된 뒤 놓는다. 도는 실행을 놓으면 [pump] 가 그것을 조용히 멈춘다.
     * 2. **그 실행의 통보가 전부 상류에 확인됐다.** 확인되지 않은 통보와 함께 빼면 [pending] 에서 사라져 아무도 다시 나르지 않는다.
     *
     * 놓은 뒤에는 이렇다.
     *
     * - **같은 작업 지시 id 는 판과 무관하게 받지 않는다.** [submit]·[adopt]·[resume] 이 [FORGOTTEN_JOB_ORDER] 로 시작하는 사유로 거부하고
     *   (사유에 그 실행의 id·판·종착 상태가 실린다), 승인은 조치가 안 나간 것으로 거절한다(`REMEDY_NOT_APPLIED`). 같은 id 를 다시 받으면
     *   같은 태스크 id 로 새 실행이 서서 이미 끝난 태스크에 붙거나 같은 일이 두 번 돈다. 그래서 `FAILED`·`UNVERIFIED` 실행을 놓으면 더
     *   높은 판의 개정도 받지 않는다 — 다시 할 일은 새 작업 지시 id 로 낸다.
     * - **실행 id 는 다시 쓰지 않는다**([executionSeq]).
     * - **남는 것**: 사건 번들([incidents]), 조치 탐색 대장, 서 있는 제안과 승인 소모 기록(ADR 46), 같은 조치의 승인 횟수. 이것들은 실행이
     *   아니라 이 층의 다른 장부이고 그 적재는 따로 추적한다. 사건 번들을 빼면 검토 지표와 내보내기가 바뀌고, 소모 기록을 빼면 다시 온
     *   승인이 `CONSUMED` 대신 `NO_PROPOSAL` 을 받는다.
     * - **사라지는 것**: [execution]·[judgmentRecord]·[lastCancel] 이 널을 내고 [resolve]·[release]·[cancel]·[reassign] 이 모르는 실행으로
     *   답한다. 놓기 전에 받아 둘 것은 받아 둔다. 아웃박스가 그 인스턴스의 전체 이력이 아니게 되므로, 통보를 [responses] 로 통째로 다시
     *   쓰는 담는 쪽은 놓기 전에 이어 붙이는 쪽으로 바꿔야 한다.
     *
     * 놓은 작업 지시 id 는 이 인스턴스의 메모리에만 있고 [Retention.forgottenOrders] 를 넘으면 오래된 것부터 내린다. 다시 뜬 인스턴스와
     * 내린 표시는 모르므로 그 재전송은 담는 쪽의 기록이 막는다.
     *
     * 부르지 않아도 실행 표가 [Retention.executions] 를 넘으면 [pump] 가 라운드 끝에 같은 규칙으로 오래된 실행부터 놓는다(§15.296).
     */
    fun forget(executionId: String): ForgetOutcome {
        val execution = executions[executionId] ?: return ForgetOutcome.Unknown
        val state = execution.physicalState
        if (state.pumps) {
            return ForgetOutcome.Refused(
                if (state.isSettled) {
                    "$state 는 종료이지만 구동이 아직 돌린다 — 개정으로 재실행하거나 취소로 끝낸 뒤 이빅션한다: $executionId"
                } else {
                    "끝나지 않은 실행이다($state): $executionId"
                },
            )
        }
        if (execution.cancelInFlight != null || execution.fleetCancelInFlight != null) {
            return ForgetOutcome.Refused("보내는 중인 취소가 있다 — 다음 라운드에 그 결과를 받은 뒤 이빅션한다: $executionId")
        }
        val unacked = outbox.filter { it.executionId == executionId && it.ack != UpstreamAck.ACKED }
        if (unacked.isNotEmpty()) {
            return ForgetOutcome.Refused("상위가 확인하지 않은 통보가 있다: ${unacked.joinToString { it.jobResponseId }} — 나르고 ack 한 뒤 이빅션한다")
        }
        executions.remove(executionId)
        outbox.removeAll { it.executionId == executionId }
        forgotten[execution.order.jobOrderId] = ForgottenOrder(executionId, execution.version, state)
        while (forgotten.size > caps.forgottenOrders) {
            forgotten.remove(forgotten.keys.first())
            forgottenEvicted++
        }
        return ForgetOutcome.Forgotten
    }

    /** 놓은 작업 지시면 거부, 아니면 널. */
    private fun forgottenRefusal(jobOrderId: String): Submission.Rejected? {
        val gone = forgotten[jobOrderId] ?: return null
        return Submission.Rejected(
            "$FORGOTTEN_JOB_ORDER: $jobOrderId · ${gone.executionId} · 판 ${gone.version} · ${gone.state} — 다시 할 일은 새 작업 지시 id 로 낸다",
        )
    }

    /** 하류 갱신의 시각 — 헤더의 `state_as_of`(§5.5). 없거나 못 읽으면 지금. */
    internal fun stateTime(update: WatchTaskResponse): Instant = try {
        update.header.stateAsOf.takeIf { it.isNotBlank() }?.let { Instant.parse(it) } ?: now()
    } catch (_: DateTimeParseException) {
        now()
    }

    companion object {
        /** 검증 불일치 — 하류는 성공이라는데 설비가 다른 것을 봤다(보고서 12.3 넷째 행). */
        const val MISMATCH = "VERIFICATION_MISMATCH"

        /** 출발지의 용기가 요청과 달라 인수하지 않았다(보고서 5장 상황표 둘째 행). */
        const val SOURCE_MISMATCH = "SOURCE_CONTAINER_MISMATCH"

        /** 목적지가 비어 있지 않아 인계를 기다린다(같은 표 첫째 행) — 실패가 아니라 지연이다. */
        const val WAITING_HANDOVER = "WAITING_HANDOVER"

        const val FLEET_REJECTED = "FLEET_REJECTED"

        /** 계약 `FailureClass.UNCLASSIFIED` 의 이름 — 하류가 분류를 안 실었을 때의 값. 지어낸 분류가 아니다. */
        const val UNCLASSIFIED = FailureClassNames.UNCLASSIFIED

        /** 설비 대기가 이름 있는 신호를 못 읽었다 — 자취의 값 자리. «기대 값이 아님» 과 다르다. */
        internal const val NO_SIGNAL = "no signal"

        /**
         * [resume] 이 기체 스냅숏을 못 읽어 거부할 때 사유의 앞머리. 담는 쪽은 이것으로 «나중에 다시 시도» 와 그 밖의 거부를
         * 나눈다. 뒤에 기체 id 가 붙는다.
         */
        const val RESUME_SNAPSHOT_UNREADABLE = "기체 스냅숏을 못 읽어 리빌드하지 않는다"

        /**
         * [resume] 이 넘겨받은 작업 지시보다 높은 리비전을 기체에서 보고 거부할 때 사유의 앞머리. 다시 시도해도 같다 — 담는 쪽이
         * 앞 인스턴스가 받은 마지막 버전을 넘겨야 한다. 뒤에 기체와 태스크, 그 리비전이 붙는다.
         */
        const val RESUME_STALE_VERSION = "넘긴 작업 지시보다 높은 리비전을 기체가 들어 리빌드하지 않는다"

        /**
         * 이 인스턴스가 [forget] 으로 놓은 작업 지시 id 가 다시 왔을 때 거부 사유의 앞머리. 담는 쪽은 이것으로 «이미 끝나 놓은 일» 과 그 밖의
         * 거부를 나눈다. 뒤에 작업 지시 id·실행 id·판·종착 상태가 붙는다. [RESUME_SNAPSHOT_UNREADABLE] 과 달리 다시 시도해도 같다.
         */
        const val FORGOTTEN_JOB_ORDER = "이미 끝나 이빅션한 작업 지시라 다시 받지 않는다"

        /**
         * 작업 지시로 펼친 계획의 단위 id 가 겹쳐 받지 않을 때 거부 사유의 앞머리(§15.227). [submit] 과 리비전이 낸다. 뒤에 겹친
         * id 가 붙는다. [FORGOTTEN_JOB_ORDER] 와 같이 같은 작업 지시로 다시 내도 같다 — 설비 id 를 고친 작업 지시가 와야 받는다.
         */
        const val DUPLICATE_UNIT_ID = RefusalReasons.DUPLICATE_UNIT_ID

        /** [resume] 이 로봇 단위의 재작업 횟수를 스냅숏 태스크 id 에서 맞췄다는 실행 자취의 종류. */
        const val RESUME_ATTEMPT = "RESUME_ATTEMPT"

        /** [resume] 이 마지막 로봇 태스크 앞의 설비 대기를 관측 없이 끝난 것으로 뒀다는 실행 자취의 종류. */
        const val RESUME_SIGNAL_PASSED = "RESUME_SIGNAL_PASSED"

        /** [resume] 이 마지막 로봇 태스크 앞의 로봇 단위를 설비 근거 재확인 없이 닫기로 했다는 실행 자취의 종류. */
        const val RESUME_PRIOR_ROBOT = "RESUME_PRIOR_ROBOT"

        /** 다시 지은 실행이 붙지 않은 단위를 스냅숏의 태스크에 `StartTask` 없이 붙였다는 실행 자취의 종류(§15.219). */
        const val RESUME_REATTACHED = "RESUME_REATTACHED"

        /**
         * [resume] 이 판단 기록을 받지 않을 때의 사유 앞머리 — 기록의 작업 지시 id·버전이 넘긴 작업 지시와 다르거나 계획에 없는 단위가
         * 있다. [RESUME_SNAPSHOT_UNREADABLE] 과 달리 다시 시도해도 같다. 담는 쪽이 넘긴 기록이 이 실행의 것이 아니다.
         */
        const val RESUME_RECORD_MISMATCH = "판단 기록이 이 작업 지시의 것이 아니어서 리빌드하지 않는다"

        /** [resume] 이 판단 기록의 사실(감수한 결함, 승인 소모, 단위의 판정)을 옮겼다는 실행 자취의 종류. */
        const val RESUME_RECORD = "RESUME_RECORD"

        /** [resume] 이 스냅숏보다 앞선 시도의 기록이라 그 단위의 시각과 판정을 옮기지 않았다는 실행 자취의 종류. */
        const val RESUME_RECORD_SUPERSEDED = "RESUME_RECORD_SUPERSEDED"

        /**
         * [resume] 이 기록의 운영자 보류를 이 인스턴스의 인시던트로 다시 열었다는 실행 자취의 종류. 그 인시던트의 근거 윈도우에
         * 실려 «재기동이 다시 연 인시던트» 를 가르는 표시다. 세부에 기록의 기체·시도·요청 시각·근거 시각·보류 사유가 든다.
         */
        const val RESUME_HOLD_REOPENED = "RESUME_HOLD_REOPENED"

        /** 붙기 전에 걸린 취소가 기체의 태스크에 `StartTask` 없이 붙었다는 실행 자취의 종류(§15.231). 취소를 보냈는지가 세부에 든다. */
        const val CANCEL_ATTACHED = "CANCEL_ATTACHED"

        /** 붙기 전에 걸린 취소가 기체를 못 봐 정착하지 않고 기다린다는 실행 자취의 종류(§15.231). 같은 까닭은 한 번만 남는다. */
        const val CANCEL_UNATTACHED_WAIT = "CANCEL_UNATTACHED_WAIT"

        /**
         * 유예 안에서 미확정 조회(`StartTask` 재전송) 사이에 두는 최소 간격(§15.238). 새 현장 시간값이 아니라 이 층의 상수다.
         *
         * 2초로 고른 근거: ops 의 pump 주기 250ms 에서 라운드마다 묻는 것보다 8배 줄고(멈춘 엔드포인트가 호스트 잠금을 쥐는 횟수가
         * 그만큼 준다), 유예 최솟값 10초 안에서도 다섯 번 묻고, 1초 안팎의 짧은 끊김은 첫 조회 뒤 2초 안에 다시 묻는 다음 조회로
         * 풀린다. 첫 조회는 간격 없이 바로 나간다. 단위의 마지막 조회 시각([ExecutionUnit.lastLookupAt])은 판단 기록에 안 들어 리빌드 뒤에는
         * 없고, 그때 첫 조회가 한 번 더 바로 나가는 것은 무해하다 — 같은 참조의 재전송은 멱등이고 간격은 부하를 줄이려는 것이지 정합성의
         * 조건이 아니다.
         */
        internal val LOOKUP_INTERVAL: Duration = Duration.ofSeconds(2)

        /** 포트가 하위로 보내지 않았다고 답한 첫 출발([CommandNotSent])의 단위 사정. 단위는 `PENDING` 에 남아 다시 나간다(§15.237). */
        internal const val START_NOT_SENT = "start not sent; reissued after the gates"

        /** 포트가 하위로 보내지 않았다고 답한 개정 갱신([CommandNotSent])의 단위 사정. 갱신은 미뤄져 다시 나간다(§15.237). */
        internal const val UPDATE_NOT_SENT = "revision update not sent; deferred"

        /** 미확정 단위가 기체의 스냅숏에 보이는 태스크에 `StartTask` 없이 붙어 풀렸다는 실행 트레이스의 종류(§15.238). */
        const val DOUBT_ATTACHED = "DOUBT_ATTACHED"

        /** 미확정 단위의 태스크를 기체가 단위보다 높은 리비전으로 들어 붙지 않고 운영자 보류에 두었다는 실행 트레이스의 종류(§15.238). */
        const val DOUBT_REVISION_AHEAD = "DOUBT_REVISION_AHEAD"

        /** 도는 단위의 갱신 스트림이 종료 없이 영구히 닫혀 단위를 미확정으로 옮겼다는 실행 트레이스의 종류(§15.228). */
        const val WATCH_CLOSED = "WATCH_CLOSED"

        /** 취소가 걸린 채 미확정이 안 풀려 취소가 닿지 못했다는 [CANCEL_UNSENT] 의 까닭(§15.238). 한 번만 남는다. */
        internal const val HOLD_UNSENT_CAUSE = "미확정이라 태스크 핸들이 없다"

        /** 로봇 경로의 [HOLD_UNSENT_CAUSE] — 키로 지은 핸들로 보낸 취소도 기체가 받지 않았다(§15.219). */
        internal const val KEYED_UNSENT_CAUSE = "미확정이라 태스크 핸들이 없고 키로 보낸 취소도 기체가 받지 않았다"

        /** 연결이 끊긴 기체에 건 취소가 유예 안에 닿지 않았다는 [CANCEL_UNSENT] 의 까닭(§15.243). */
        internal const val LINK_UNSENT_CAUSE = "연결이 끊긴 채 미확정 유예 안에 닿지 않았다"

        /** 다시 지은 실행이 재기동 전에 맡긴 운반을 찾을 수 없어 취소가 닿지 못했다는 [CANCEL_UNSENT] 의 까닭(§15.254). */
        internal const val LOOKUP_UNSENT_CAUSE = "플릿에 맡긴 운반을 찾을 조회가 없다"

        /** 붙기 전에 건 취소가 유예 안에 하위의 답을 받지 못했다는 [CANCEL_UNSENT] 의 까닭(§15.255). */
        internal const val UNATTACHED_UNSENT_CAUSE = "붙기 전에 건 취소가 미확정 유예 안에 답을 받지 못했다"

        /** 플릿이 더는 그 운반을 하지 않는 상태. */
        internal val TRANSPORT_ENDED = setOf(
            TransportState.DELIVERED, TransportState.REJECTED_AT_SOURCE, TransportState.CANCELLED, TransportState.FAILED,
        )

        /** 라운드 진행([PUMP_SOURCE])의 실패를 [Execution.noticed] 에 적는 자리 이름. 기체 동기화는 `sync:<기체>` 다. */
        private const val PUMP_SOURCE = "pump"

        /**
         * 포트가 던져 한 실행의 라운드 진행 또는 기체 동기화가 중단됐다는 실행 트레이스의 종류(§15.237). 상태는 되돌리지 않고 다음
         * 라운드에 다시 본다. 같은 자리가 같은 까닭으로 계속 던지면 한 번만 남는다.
         */
        const val PUMP_FAILED = "PUMP_FAILED"

        /**
         * 발신자(어댑터 호스트·에뮬레이터) 시계가 미들웨어 시계와 임계 넘게 어긋났다는 실행 트레이스의 종류(환경 전제 K). 스냅숏 왕복에서 잰 차의
         * 구간 전체가 임계 밖일 때 남고, 같은 방향·같은 크기 등급이면 한 번만 남는다. 판정은 바꾸지 않는다.
         */
        const val CLOCK_SKEW = "CLOCK_SKEW"

        /** 건 취소를 하위에 보내지 못했다는 실행 트레이스의 종류(§15.237). 같은 까닭은 한 번만 남고, 닿을 때까지 다시 보낸다. */
        const val CANCEL_UNSENT = "CANCEL_UNSENT"

        /** 보내지 못했던 취소가 다시 보내져 하위에 닿았다는 실행 트레이스의 종류(§15.237). */
        const val CANCEL_RESENT = "CANCEL_RESENT"

        /** 감수한 결함이 기체에서 걷혀 그 열쇠를 놓았다는 실행 트레이스의 종류(§15.248). 같은 열쇠로 다시 생기면 다시 막는다. */
        const val ACKNOWLEDGED_FAULT_CLEARED = "ACKNOWLEDGED_FAULT_CLEARED"

        /**
         * 취소 응답의 [CancelReport.refusal] 에 실리는 이 층의 값 — 건 취소가 끝내 하위에 닿지 못한 채 단위가 끝났다(§15.237).
         * 하위가 거부한 사유(계약의 거부 코드)가 아니라 이 층이 말하는 것이다. 단위는 [CancelReport.stoppedAfter] 로 나간다.
         */
        const val CANCEL_NOT_SENT = "CANCEL_NOT_SENT"

        /** 발행 관문이 새 로봇 명령을 막았다는 실행 자취의 종류(ADR 52). 같은 막음은 한 번만 남는다. */
        const val ISSUE_WITHHELD = "ISSUE_WITHHELD"

        /**
         * 발행 관문이 더는 막지 않는다는 실행 자취의 종류. 무엇이 허가했는지(원천·관측 시각)를 싣는다. 결함 관문이 따로 막고 있으면
         * 명령은 아직 안 나간다 — 이 줄은 «허가가 돌아왔다» 이지 «나갔다» 가 아니다.
         */
        const val ISSUE_RESUMED = "ISSUE_RESUMED"

        /**
         * 선행 단위([ExecutionUnit.after])가 완료되지 않아 이 단위를 내지 않았다 — 실패 분류이자 실행 트레이스의 종류(§15.234).
         * 작업 응답의 `incompleteUnits` 에 이 값으로 오른다. 태스크가 없으므로 하위 분류가 아니라 이 계층의 이름이다.
         */
        const val PREDECESSOR_NOT_DONE = "PREDECESSOR_NOT_DONE"

        /** 선행이 이 상태로 종료했으면 뒤 단위를 내지 않는다. `UNVERIFIED` 도 든다 — 이동이 확인되지 않은 자리에서 살피지 않는다. */
        private val ENDED_NOT_DONE = setOf(UnitState.FAILED, UnitState.UNVERIFIED, UnitState.ABORTED)

        /** 아직 낼 것이 있거나 도는 중이거나 사람을 기다리는 단위 상태. 하나라도 있으면 순회는 종결한 것이 아니다. */
        internal val OPEN_UNIT_STATES = setOf(
            UnitState.PENDING, UnitState.RUNNING, UnitState.VERIFYING, UnitState.IN_DOUBT, UnitState.OPERATOR_HOLD,
        )

        /** 재작업 태스크 id 의 접미사. `startUnit` 이 재작업한 단위에 `@rN` 으로 붙인다. */
        internal val RETRY_SUFFIX = Regex("""@r([1-9]\d*)""")
    }
}

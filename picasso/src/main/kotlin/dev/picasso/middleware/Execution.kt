package dev.picasso.middleware

import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Instant
import java.util.Collections

/** 실행 하나 — 주문·기체·단위 열·두 축의 상태. */
class Execution internal constructor(
    val executionId: String,
    order: JobOrder,
    robotId: String,
    val capability: LogicalCapability,
    /**
     * 이 실행을 계획한 임무 버전. 코드 케이퍼빌리티면 `null` 이다. **생성 때 정해지고 바뀌지 않는다** —
     * 리비전도 [capability] 로 계획하므로, 활성 버전이 바뀌어도 이 실행은 이 버전으로 끝난다.
     */
    val missionVersion: Int?,
    units: List<ExecutionUnit>,
    assignedAt: Instant,
    /** 이 실행의 명령 생애를 짓는다. 생성 중에 한 번 부르며, 실행의 멤버를 읽지 않아야 한다(아직 다 서지 않았다). */
    commandOf: (Execution) -> CommandLifecycle,
) {
    /** 지금의 작업 지시. 리비전이 바꾼다. */
    var order: JobOrder = order
        internal set

    /** 지금 이 일을 든 기체. **재할당으로 바뀐다** — 옮긴 이력은 트레이스에 남는다. */
    var robotId: String = robotId
        internal set

    /** 단위 열의 가변 뒷받침. 받은 목록을 복사해 쥔다 — 넘긴 쪽이 원본을 바꿔도 이 실행은 바뀌지 않는다. 리비전이 갈아 끼운다. */
    internal val mutableUnits: MutableList<ExecutionUnit> = units.toMutableList()

    /** 단위 열(계획 순서). 밖에서는 읽기만 한다 — [mutableUnits] 의 살아 있는 읽기 뷰다. */
    val units: List<ExecutionUnit> = Collections.unmodifiableList(mutableUnits)

    var physicalState: PhysicalState = PhysicalState.REQUESTED
        internal set

    /** 지금 기체에 배정된 시각. 최소 유지 시간의 기준이고 재할당 때 다시 찍힌다. */
    internal var assignedAt: Instant = assignedAt

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
    internal val command = commandOf(this)

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
    internal val mutableAcknowledgedFaults: MutableSet<String> = linkedSetOf()

    /** [mutableAcknowledgedFaults] 의 살아 있는 읽기 뷰. */
    val acknowledgedFaults: Set<String> = Collections.unmodifiableSet(mutableAcknowledgedFaults)

    /** 이 실행이 도는 동안 기체가 낸 이벤트와 이 층의 관측 — 감사 자취(보고서 7장 *실행 추적·이벤트 전달·감사 기록*). */
    internal val mutableEventTrail: MutableList<ObservedEvent> = mutableListOf()

    /** [mutableEventTrail] 의 살아 있는 읽기 뷰. */
    val eventTrail: List<ObservedEvent> = Collections.unmodifiableList(mutableEventTrail)

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
    internal val mutableLateEvents: MutableList<LateEvent> = mutableListOf()

    /** [mutableLateEvents] 의 살아 있는 읽기 뷰. */
    val lateEvents: List<LateEvent> = Collections.unmodifiableList(mutableLateEvents)

    internal fun noteLate(unit: ExecutionUnit, update: WatchTaskResponse, at: () -> Instant) {
        if (!noticed.note(Notice.Late(unit.unitId, update.revision, update.state.name), true)) return
        mutableLateEvents += LateEvent(unit.unitId, update.revision, unit.revision, update.state.name, at())
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

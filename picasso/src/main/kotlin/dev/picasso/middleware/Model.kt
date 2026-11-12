package dev.picasso.middleware

import dev.picasso.capability.HoldMismatch
import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.TaskState
import java.time.Duration
import java.time.Instant

/**
 * 정준 모델 — 실행 하나의 상태(설계 §1.1, 보고서 10.3).
 *
 * 축이 둘이고 독립이다. [PhysicalState]는 물리 세계에서 무슨 일이 일어났는가,
 * [UpstreamAck]는 그 사실이 상류에 전달·확정됐는가. `PHYSICALLY_DONE × SENT_UNACKED`
 * 는 오류가 아니라 정상적인 중간 상태이며, 장애 뒤 할 일이 *다시 운반*이 아니라
 * *기존 결과 전달*임을 이 분리가 말한다.
 *
 * 계약(④)의 `TaskState`는 그 아래 **원자 태스크 하나**의 상태다. 둘은 다른
 * 층이고 하나가 다른 하나를 대신하지 않는다.
 */
enum class PhysicalState {
    REQUESTED, ACCEPTED, RUNNING, PARTIAL, IN_DOUBT, OPERATOR_HOLD,
    PHYSICALLY_DONE, UNVERIFIED, FAILED, CANCELING, ABORTED;

    val isSettled: Boolean
        get() = this == PHYSICALLY_DONE || this == UNVERIFIED || this == FAILED ||
            this == ABORTED || this == PARTIAL

    /**
     * **이 실행에 아직 처리가 더 붙을 수 있는가** — 하달·취소·개정판.
     *
     * [isSettled] 와 한 군데가 다르다. `PARTIAL` 은 종착이지만 개정판이 남은 단위를 다시 세울 수 있고([Middleware.cancel]
     * 도 받는다), 그래서 구동이 그 실행을 계속 돌린다. 그 판정을 호출하는 자리마다 풀어 쓰면 한 곳만 고치는 날 어긋난다 —
     * 그래서 이름을 하나 둔다.
     */
    val pumps: Boolean
        get() = !isSettled || this == PARTIAL

    /**
     * **이 실행이 기체를 쥐고 있는가** — 배치하는 쪽의 배정 판단(그 기체에 새 일을 줄 것인가)이 읽는 자리다.
     *
     * 지금은 [pumps] 와 같은 식이다. `PARTIAL` 인 실행은 개정판으로 다시 돌 수 있으므로 그 기체는 아직 비지 않았다 —
     * [isSettled] 만 읽으면 그 기체에 둘째 주문이 간다. 이름을 따로 두는 것은 **둘이 갈라질 수 있어서다** —
     * `OPERATOR_HOLD` 처럼 하달은 멈췄지만 기체는 붙잡아 두는 상태를 더 가르게 되면 한쪽만 바뀐다.
     */
    val occupiesRobot: Boolean
        get() = !isSettled || this == PARTIAL
}

enum class UpstreamAck { NOT_SENT, SENT_UNACKED, ACKED }

/**
 * 완료 근거 등급(설계 §1.3, 보고서 11.3). 순서가 곧 세기다.
 *
 * - E0 로봇 자체 보고 — 계약 종착. 어댑터가 로봇에 직결된 경우
 * - E1 플릿 확인 — 플릿이 자기 완료 조건으로 발행한 완료(어댑터가 플릿에 붙은 경우, 또는 D 수준 위임)
 * - E2 독립 설비 확인 — 인계 설비·셀 검증 장치의 신호([CellSignals])
 * - E3 업무 확인 — 상류의 ack
 */
enum class Evidence { E0, E1, E2, E3 }

/**
 * 시간창 δ(보고서 12.1·12.2) — 하류 보고 시각 `t_r` 을 기준으로 설비 신호 시각 `t_p` 가
 * `[t_r − before, t_r + after]` 안에 있어야 그 신호가 **이 완료의** 근거다.
 *
 * 앞쪽 폭은 *이전 것의 신호*(아직 남아 있는 옛 용기·옛 부품)를 걸러 내고, 뒤쪽 폭은
 * 보고 지연·폴링 지연·네트워크 지연을 합한 것이다. **현장별 설정**이며 능력이 기본값을 든다.
 */
data class EvidenceWindow(val before: Duration, val after: Duration)

/** 원자 단위(슬롯·용기) 하나의 검증 결과 — 하류 보고와 독립 설비 신호의 대조(보고서 12.3). */
enum class Verification {
    /** 요구 등급이 하류 보고 이하라 설비 확인을 묻지 않았다. */
    NOT_REQUESTED,
    /** 신호가 있고 기대와 맞는다. */
    MATCHED,
    /** 시간창 안에 신호가 없다 — 하류는 끝났다는데 설비가 말이 없다. `UNVERIFIED`. */
    ABSENT,
    /** 신호가 있으나 기대와 다르다(B형 슬롯에 A형, 다른 용기). 오인계 의심 — `FAILED` + 운영자. */
    MISMATCH,
}

/**
 * 원자 단위의 상태.
 *
 * - [IN_DOUBT] — 요청은 보냈는데 답을 못 받았다(보고서 13장). 접수됐는지 모른다. 해소 순서(13.2)를 [Middleware]가 집행한다
 * - [VERIFYING] — 하류는 끝났다고 했고, 시간창이 닫힐 때까지 설비 신호를 기다리는 중
 * - [OPERATOR_HOLD] — 하류는 실패라는데 설비에는 있다(보고서 12.3 둘째 행), 또는 `IN_DOUBT` 를 자동으로 못 풀었다(13.2 ③). 운영자가 [OperatorDecision]을 낸다
 */
enum class UnitState { PENDING, IN_DOUBT, RUNNING, VERIFYING, OPERATOR_HOLD, DONE, UNVERIFIED, FAILED, ABORTED }

/**
 * 하류가 **클라이언트 참조로 기존 실행을 찾아 주는가**(보고서 13.2 ①, 16장 *"실행 조회 가능 여부 — 이 하류에서
 * `IN_DOUBT` 가 자동 해소되는가"*).
 *
 * - [CLIENT_REFERENCE] — 같은 참조로 다시 물으면 **같은 실행**이 돌아온다. 계약(④)은 같은 `(task_id, revision)` 재전송이
 *   같은 핸들이고(§4.4), 프로젝트용 플릿 계약은 [TransportOrder.reference] 가 그것이다. `IN_DOUBT` 가 자동으로 풀린다.
 * - [NONE] — 그런 조회가 없다. 조사한 실물 하류 셋이 전부 이쪽이었고(13.2), 어댑터가 매핑을 들어 계약 쪽으로는
 *   [CLIENT_REFERENCE] 로 보이게 한다. 이 값이면 **자동 재실행 금지**가 기본이다 — 다시 보내면 물리 작업이 둘이 될 수 있다.
 */
enum class ExecutionLookup { CLIENT_REFERENCE, NONE }

/**
 * 운영자의 판단 — 12.3 둘째 행 *"운영자 확인 후 PHYSICALLY_DONE 또는 재작업"*, 그리고 13.2 ③.
 *
 * [REWORK] 는 **명시적 재요청**이다(13.3 — 명령 재시도는 미실행이 확인된 경우에만, 사람이 확인하고 낸다). 새 정체성으로 간다.
 * 보류에 선 단위 말고도 순회가 종결한 뒤 건너뛴 대상(이동과 점검 한 쌍)이 받는다. [CONFIRM_DONE] 은 보류에 선 단위만 받는다.
 */
enum class OperatorDecision { CONFIRM_DONE, REWORK }

/**
 * 운영자 판단([Middleware.resolve])의 답 — **왜 안 됐는지를 접지 않는다**(ADR 47).
 *
 * 참·거짓 하나로 내면 «보류 중인 단위가 없다» 와 «에이전트라서 막았다» 가 같은 값이 되고, 부르는 쪽은
 * 다시 눌러야 하는지 사람을 불러야 하는지 모른다.
 */
sealed interface ResolveOutcome {
    /** 판단이 섰다 — 단위가 움직였고 사건에 누가 판단했는지가 남았다. */
    data object Resolved : ResolveOutcome

    /**
     * 그 실행이 없거나, 그 단위가 판단을 받을 모양이 아니다. 아무것도 안 바뀌었다. 판단을 받을 모양은 운영자 보류에 선 단위이거나,
     * 시도된 이동 실패 때문에 내지 않은 점검(건너뛴 대상)이다.
     */
    data object NotHeld : ResolveOutcome

    /**
     * 이 판단을 지금 받지 않는다 — [reason] 이 사유다. 아무것도 안 바뀌었다. 판단할 자격이 없는 에이전트가 오늘의 첫 사유였고,
     * 건너뛴 대상에는 순회가 종결하지 않았음, 취소한 실행, 같은 기체의 다른 활성 실행, 하지 않은 점검의 확인이 더해졌다.
     */
    data class Refused(val reason: String) : ResolveOutcome
}

/**
 * 실행을 놓는 요청([Middleware.forget])의 답. **왜 안 놓았는지를 접지 않는다** — 아직 도는 실행과 상류가 확인하지 않은
 * 통보는 다음 행동이 다르다(앞엣것은 종착이나 취소를 기다리고, 뒤엣것은 나르고 `ack` 한다).
 */
sealed interface ForgetOutcome {
    /** 놓았다 — 실행과 그 통보가 이 층에서 빠졌고, 같은 작업 지시 id 는 이제 받지 않는다. */
    data object Forgotten : ForgetOutcome

    /** 모르는 실행이다 — 선 적이 없거나 이미 놓았다. 아무것도 안 바뀌었다. */
    data object Unknown : ForgetOutcome

    /** 놓지 않았다. 아무것도 안 바뀌었다. */
    data class Refused(val reason: String) : ForgetOutcome
}

/**
 * 지연 이벤트(보고서 15.1) — 단위의 지금 버전보다 **낮은 버전**을 단 하류 종착. 폐기하지 않고 여기 보존한다(감사·사후 분석).
 * v17 을 돌리다 v18 로 바꿨는데 뒤늦게 온 v17 의 완료로 v18 을 닫지 않는다 — 그 완료가 무엇을 뜻하는지는 설비가 v18 의
 * 기대에 대고 다시 본다.
 */
data class LateEvent(
    val unitId: String,
    val revision: Int,
    val currentRevision: Int,
    val downstreamState: String,
    val occurredAt: Instant,
)

/**
 * 단위가 어느 하류로 가는가(보고서 3.2 축 1·2).
 *
 * - [ROBOT] — 계약(④)의 원자 스킬 하나. 기종은 어댑터 뒤에 있다
 * - [FLEET] — D 수준 위임. 운반 전체를 플릿에 맡기고 결과만 받는다([AmrFleetPort], 프로젝트용 계약)
 * - [SIGNAL] — 설비 대기. 하위에 요청을 보내지 않고 이름 있는 설비 신호([CellSignals.signal])가 기대 값이
 *   될 때까지 기다린다. 대기 사양은 단위의 [ExecutionUnit.wait] 에 있다
 *
 * ★**셋째 값이 생겼으므로 «로봇이 아니면 플릿» 으로 가르지 않는다.** 그렇게 가르면 설비 대기가 조용히 플릿으로
 * 간다 — 경로마다 `when` 으로 나눈다.
 */
enum class Route { ROBOT, FLEET, SIGNAL }

/**
 * 설비 대기의 기한이 지났을 때 갈 상태(임무 정의의 `onDeadline`).
 *
 * - [OPERATOR_HOLD] — 대기 단위가 운영자 판단에 선다. 라인이 멈추고 확인(진행)·재작업(다시 기다림)·실행 취소로 푼다
 * - [ABORTED] — 대기 단위는 `FAILED`(`SIGNAL_DEADLINE`)로 남고 실행이 중단된다. 남은 단위는 내보내지 않는다
 *
 * `FAILED` 로만 두고 진행을 맡기지 않는 이유: 엔진은 `FAILED` 단위 뒤에도 다음 단위를 내보낸다. 신호를 못 본 채
 * 다음 로봇 단계를 하면 대기를 둔 뜻이 없다.
 */
enum class DeadlineOutcome { OPERATOR_HOLD, ABORTED }

/**
 * 설비 대기 단위([Route.SIGNAL])의 사양 — 무엇을 어떤 값이 될 때까지, 언제까지 기다리고, 그 뒤에 어디로 가는가.
 *
 * 판정은 신호의 **지금 값**이다. 읽은 값이 [expect] 와 같으면 끝난다. 관측 시각은 자취에만 쓴다 — 상태 신호만
 * 다루므로 시작 전부터 그 값이었다면 그 상태가 이미 성립한 것이다. 짧게 켜졌다 꺼지는 이벤트형 신호는 다루지 않는다.
 *
 * @param deadline 단위가 시작한 시각부터의 기한. 재작업하면 다시 시작한다.
 */
data class WaitSpec(
    val signal: String,
    val expect: String,
    val deadline: Duration,
    val onDeadline: DeadlineOutcome,
) {
    companion object {
        /**
         * 대기 단위의 `skillType`. **계약 카탈로그의 스킬 이름과 겹치지 않는 값이다** — 겹치면 효과·사전 조건·선택
         * 파라미터를 읽는 자리가 이 단위를 그 스킬로 잘못 읽는다. 플릿의 `transport` 와 같은 자리의 이름이다.
         */
        const val SKILL_TYPE = "equipment_wait"

        /** 기한까지 기대 값을 못 봤다 — 대기 단위의 실패 분류. */
        const val SIGNAL_DEADLINE = "SIGNAL_DEADLINE"

        /** 대기 사양을 단위 파라미터에도 싣는 키 — 인시던트의 `intent.unitParameters` 로 나가 무엇을 기다렸는지가 보인다. */
        const val P_SIGNAL = "signal"
        const val P_EXPECT = "expect"
        const val P_DEADLINE_SECONDS = "deadlineSeconds"
        const val P_ON_DEADLINE = "onDeadline"
    }
}

// ── 상류 인터페이스의 모양 — OPC UA ISA-95 Job Control 10031-4 의 타입을 따른다

/** `ISA95EquipmentDataType` 의 모양. `EquipmentUse` 값은 표준이 열어 두었고 우리가 지은 말이다. */
data class EquipmentRequirement(
    val id: String,
    val equipmentUse: String,
    val properties: Map<String, String> = emptyMap(),
)

/** `EquipmentUse` 값 — 표준이 열어 두었고 **우리가 지은 말**이다. 능력 둘이 같은 낱말을 쓴다. */
object EquipmentUse {
    const val DESTINATION = "destination"
    const val SOURCE = "source"
    const val PROP_MATERIAL = "material"
    const val PROP_CONTAINER = "container"

    /** 점검 대상(시나리오 ③). `location` 속성이 그 대상을 살필 자리, `item` 이 점검 항목이다. */
    const val INSPECTION_TARGET = "inspection_target"
    const val PROP_LOCATION = "location"
    const val PROP_ITEM = "item"
}

/** `ISA95MaterialDataType` 의 모양 — 타입(`MaterialDefinitionID`)으로 온다. 인스턴스 id 는 이력 키다(§15.80). */
data class MaterialRequirement(
    val materialDefinitionId: String,
    val quantity: Int,
)

/**
 * `ISA95JobOrderDataType` 의 모양. 상류(MES·WMS — 예상 소비자)가 낸다.
 *
 * @param version 생산 순서 버전. 곧 원자 태스크의 `revision` 이다(§1.6).
 * @param requiredEvidence 상류가 지정하는 요구 근거 등급(보고서 11.3). 재고 확정은 E2, 진행 모니터링은 E0.
 */
data class JobOrder(
    val jobOrderId: String,
    val workMasterId: String,
    val version: Int,
    val requiredEvidence: Evidence = Evidence.E0,
    val parameters: Map<String, String> = emptyMap(),
    val materialRequirements: List<MaterialRequirement> = emptyList(),
    val equipmentRequirements: List<EquipmentRequirement> = emptyList(),
)

/**
 * 원자 단위 하나 — 슬롯 하나, 용기 하나. 하류의 실행 하나에 대응한다.
 *
 * @param unitId 상류가 아는 단위 이름(슬롯 id·용기 id). 중단점과 부분 완료 목록이 이것으로 말한다.
 * @param expectedIdentity 설비가 그 자리에서 읽어야 할 것 — 부품 타입이거나 용기 태그.
 */
data class ExecutionUnit(
    val unitId: String,
    val route: Route,
    val skillType: String,
    /** 아래 넷은 **버전의 것**이다 — 새 버전이 도는 단위에 붙으면(15.3) 기대도 새 버전의 것으로 바뀐다. 검증은 지금 기대에 대고 한다. */
    var parameters: Map<String, String>,
    var expectedIdentity: String?,
    var source: String?,
    var destination: String?,
    var state: UnitState = UnitState.PENDING,
    var taskId: String = "",
    var revision: Int = 0,
    /**
     * 이 인스턴스가 지금 태스크 id 를 처음 보낸 리비전(§15.238). 개정 갱신을 보냈으면 [revision] 과 다르고, 보내지 않은 단위나 재기동 뒤
     * 스냅숏으로 붙은 단위는 널이다. 리비전을 싣지 않는 포트의 스냅숏에 태스크가 보일 때, 이 값이 [revision] 과 같으면 그 태스크는 이
     * 리비전으로만 나간 것이라 붙을 수 있다.
     */
    var startedRevision: Int? = null,
    /** 재작업 횟수. 하류 태스크의 정체성이 갈리는 자리다 — 같은 `task_id` 는 계약이 같은 핸들로 돌려준다. */
    var attempt: Int = 0,
    var reached: Evidence = Evidence.E0,
    var verification: Verification = Verification.NOT_REQUESTED,
    /** 실패의 정준 분류. 어댑터가 벤더 코드에서 옮긴 것이 계약의 `Fault.error_type` 으로 온다. */
    var failureClass: String? = null,
    /**
     * [failureClass] 가 그 값이 된 **결함 원문**(§15.177). 하류가 결함 없이 실패를 알렸으면 널이다.
     *
     * 분류만 들면 사건 번들이 «왜 그 분류가 됐나» 에 분류를 되풀이하는 것 말고 답할 수 없다.
     */
    var fault: dev.picasso.contracts.v1.Fault? = null,
    /** 종착이 아닌 사정 — 인계 대기, 관측한 태그, 신호의 시각 같은 것. 지연 보고와 기록의 내용이다. */
    var note: String? = null,
    var hold: HoldState = HoldState.getDefaultInstance(),
    /** 하류가 끝났다고 한 시각 `t_r`. 시간창의 기준. */
    var downstreamDoneAt: Instant? = null,
    /** `t_r + after`. 이 시각을 지나도 신호가 없으면 `UNVERIFIED`. */
    var evidenceDeadline: Instant? = null,
    /** 근거로 채택한 신호의 시각 `t_p`. */
    var evidenceAt: Instant? = null,
    /** 설비에 몇 번 물었는가 — 12.2 의 재확인 횟수. */
    var rechecks: Int = 0,
    /** 하류에 요청을 보낸 시각. `IN_DOUBT` 관측 창의 기준이다. */
    var requestedAt: Instant? = null,
    /** 마지막으로 **올라간** 진행률과 그 시각. 정체 판정의 기준이다(계약 0.8.0). */
    var progress: Double = 0.0,
    var progressAt: Instant? = null,
    /**
     * 이 기체가 이 단위의 진행률을 **잴 수 있는가.** 널이면 아직 갱신을 못 봤다.
     * 거짓이면 정체 판정을 하지 않는다 — 0 을 멈춤으로 읽지 않는다.
     */
    var progressObservable: Boolean? = null,
    /** 이미 정체로 알렸는가. 한 번만 알린다 — 같은 사실을 되풀이하면 운영자가 곧 무시한다. */
    var progressStalled: Boolean = false,
    /** `IN_DOUBT` 에서 같은 참조로 다시 물은 횟수(13.2 ①). */
    var lookups: Int = 0,
    /**
     * 발신자가 거절하며 지목한 사전 조건의 주어(계약 `KEY_PRECONDITION_SUBJECT`). 사건 번들이 이것을 옮겨 싣는다 —
     * 자유 문자열에서 다시 뽑으면 두 곳의 판정이 어긋난다(설계안 §4.2).
     */
    var preconditionSubjects: List<String> = emptyList(),
    /**
     * 선언된 효과와 마지막 관측이 어긋났는가(설계안 §5). **결함 통지가 없어도** 내는 판정이며, 이 설계가
     * 새로 계산하는 유일한 사실이다. 관측이 없거나 볼 수 없으면 `null` — 선언으로 현실을 단정하지 않는다.
     */
    var holdMismatch: HoldMismatch? = null,
    /**
     * 이 단위의 지금 시도가 도는 동안 **한 번이라도 쥔 것이 관측됐는가.** 중단 시점의 기대를 정한다(설계안 §5) —
     * 쥐었다가 놓지 못한 채 끝났으면 든 채여야 하고, 쥔 적이 없으면 기대할 것이 없다. 재작업이 새 시도를 내면
     * [heldInEarlierAttempt] 로 접혀 비워진다(§15.236).
     */
    var everHeld: Boolean = false,
    /** 끝난 시점에 **기대했던** 파지. 판정의 근거이고, 사건 번들이 판단 경로로 싣는다(설계안 §7.2 셋째). */
    var holdExpected: HoldKind? = null,
    /**
     * 하류가 종착에 실어 준 결과 참조 — 계약의 `partial_result`. 점검(③)의 *측정값 또는 증거 자료 참조*가 올 자리이며
     * 지금은 아무 발신자도 채우지 않는다(§15.76). 비어 있으면 `null`.
     */
    var result: String? = null,
    /** 설비 대기 단위([Route.SIGNAL])의 사양. 다른 경로의 단위는 널이다. */
    val wait: WaitSpec? = null,
    /**
     * 출발 자리의 관측을 요구하는가. 임무 정의의 단위 노드가 선언한 것이고, 코드 케이퍼빌리티의 단위는 기본값
     * [SourceObservation.OPTIONAL] 이다. 배정 관문만 읽는다([AdmissionGate]).
     */
    val sourceObservation: SourceObservation = SourceObservation.OPTIONAL,
    /**
     * 목적지가 비었는지를 설비 관측으로 볼 것인가. 임무 정의의 단위 노드가 선언한 것이고, 코드 케이퍼빌리티의 단위는 기본값
     * [DestinationObservation.OPTIONAL] 이다(보지 않는다). 배정 게이트만 읽고, 근거 윈도우([EvidenceWindow])의 판정은 이 값과 무관하다.
     */
    val destinationObservation: DestinationObservation = DestinationObservation.OPTIONAL,
) {
    /**
     * 이 단위를 맡은 기체. **널이 기본이고 그때는 실행의 기체다**([Middleware.Execution.robotOf]) — 오늘의 계획은 전부 널이다.
     *
     * 기체를 실행이 아니라 단위에 묶어 두는 것은 협업(로봇 간 인계)을 위한 자리다(ADR 51 «대가»). 한 임무를 여러 기체가 나눠
     * 하면 단위마다 기체가 다르고, 그때 실행 하나에 기체 하나인 모양으로는 스키마가 깨진다. 그래서 단위의 기체를 읽는 자리
     * (발행, 관측, 두 관문, 점유, 다시 짓기의 스냅숏)는 전부 이 묶음을 거친다.
     *
     * 널을 기본으로 두는 것은 재할당이 실행의 기체를 바꾸면 묶지 않은 단위가 따라가야 하기 때문이다. 단위마다 값을 복사해 두면
     * 재할당과 개정이 그 값을 하나라도 빠뜨리는 날 단위가 옛 기체에 남는다.
     *
     * `internal` 이다. 다른 기체를 묶는 소비자가 아직 없다(ADR 9). 시험은 이 자리로 단위를 다른 기체에 묶어, 실행의 기체를
     * 읽는 자리가 남아 있으면 그 호출이 엉뚱한 기체에 찍히는지를 본다 — 기본값만으로는 두 값이 같아 아무것도 가르지 못한다.
     * 데이터 클래스의 본문 속성이라 같음 비교와 복사에 들지 않는다.
     */
    internal var robotBinding: String? = null

    /**
     * 미확정 조회([Middleware] 의 `resolveDoubt`)를 마지막으로 보낸 시각. 널이면 아직 안 물었다 — 첫 조회는 바로 나간다. 유예 안의 조회
     * 사이에 [Middleware.LOOKUP_INTERVAL] 을 두려고 미들웨어 시계로 잰다. 판단 기록에 싣지 않으므로 리빌드 뒤에는 널이고, 그때 첫 조회가
     * 바로 한 번 더 나갈 뿐이다(같은 참조의 재전송은 멱등이다). 본문 속성이라 같음 비교와 복사에 들지 않는다.
     */
    internal var lastLookupAt: Instant? = null

    /**
     * 이 시도의 갱신 스트림을 더 따라갈 수 없게 된 시각(§15.228, [RobotPort.unfollowable]). 널이면 그런 일이 없었다. 값이 있으면 단위는
     * 미확정이고, 해소가 같은 참조의 `StartTask` 재전송을 건너뛴다 — 원 요청은 이미 닿았으므로 재전송은 기체가 잊은 일을 새로 낸다. 설비
     * 관측의 유예도 이 시각부터 잰다. 새 시도를 내거나 스냅숏에 붙으면 걷힌다. 판단 기록에 싣지 않는다 — 리빌드는 미확정을 옮기지 않고
     * 스냅숏에서 다시 붙는다. 본문 속성이라 같음 비교와 복사에 들지 않는다.
     */
    internal var streamLostAt: Instant? = null

    /**
     * 이 시도의 태스크에 키로 지은 핸들로 취소를 마지막으로 보낸 시각(§15.219, §15.238). 미확정 중이거나 운영자 보류에 둔 취소를 다시
     * 보낼 때 [Middleware.LOOKUP_INTERVAL] 을 두려고 미들웨어 시계로 잰다. 새 시도를 내거나 취소용으로 붙으면 걷힌다. 판단 기록에 싣지
     * 않으므로 리빌드 뒤에는 널이고, 그때 첫 취소가 바로 한 번 더 나갈 뿐이다(같은 태스크의 취소는 멱등이다). 본문 속성이라 같음 비교와
     * 복사에 들지 않는다.
     */
    internal var keyedCancelAt: Instant? = null

    /**
     * 앞 시도에서 쥔 것이 관측됐는가(§15.236). 재작업이 새 시도를 낼 때 [everHeld] 를 이 값에 접어 넣고 비운다 — [everHeld] 는 이 시도의
     * 중단 시점 기대를 정하므로, 앞 시도의 파지를 들고 가면 쥔 적 없는 새 시도가 «든 채» 를 기대한다. 재할당은 둘을 함께 본다 — 반쯤 한
     * 물리 작업은 시도가 바뀌어도 남는다. 판단 기록에 [everHeld] 와 함께 싣는다(§15.257). 본문 속성이라 같음 비교와 복사에 들지 않는다.
     */
    internal var heldInEarlierAttempt: Boolean = false

    /**
     * 이 단위보다 먼저 `DONE` 이어야 하는 단위의 id(§15.234). **널이 기본이고 그때는 앞 단위의 결과와 상관없이 나간다** — 슬롯끼리
     * 독립인 계획([PrepareSequencedRack])은 앞 슬롯이 실패해도 다음 슬롯을 낸다.
     *
     * 채우는 것은 오늘 [InspectAsset] 하나다. 대상 하나가 단위 둘(이동과 점검)이고, 이동이 완료되지 않은 채 점검을 내면 엉뚱한 자리에서
     * 살핀 결과가 `DONE` 으로 남는다. 출발 직전에 이 값이 가리키는 단위가 종료했는데 `DONE` 이 아니면 실행은 이 단위를 태스크 없이 `FAILED` 로
     * 두고 다음 단위로 간다([Middleware.PREDECESSOR_NOT_DONE]). 순회가 종결한 뒤 사람이 낸 재작업이 이 단위와 선행을 함께 새 시도로 되돌린다
     * ([Middleware.resolve]).
     *
     * `internal` 이다. 계약·작업 응답·내보내기·판단 기록에 싣지 않는다 — 리비전과 리빌드가 [LogicalCapability.plan] 을 다시 불러
     * 이 값을 다시 채운다. 데이터 클래스의 본문 속성이라 같음 비교와 복사에 들지 않는다([robotBinding] 과 같다).
     */
    internal var after: String? = null
}

/**
 * 기체의 **현재값** — 계약 `GetSnapshot`(§4.8). 활성 결함·연결 상태·태스크 상태와 *다음에 올* 이벤트 번호.
 * 이벤트는 발생한 사실이고 이것은 지금이다 — 둘을 함께 써야 신규 소비자가 놓친 전이를 세울 수 있다.
 */
data class RobotSnapshot(
    val sequence: Long,
    val faults: List<Fault>,
    val connection: ConnectionState,
    val tasks: Map<String, TaskState>,
    /**
     * [tasks] 의 태스크마다 기체가 든 리비전(계약 `TaskSnapshot.revision`). 리비전을 안 싣는 포트는 비워 둔다 — 비어 있으면 그 태스크의
     * 리비전을 모르는 것이고, 리비전을 대야 하는 자리([Middleware] 의 미확정 부착)는 붙지 않는다.
     */
    val revisions: Map<String, Int> = emptyMap(),
    /**
     * 응답 헤더의 `session_id`. [sequence] 는 이 세션 안의 번호다 — 세션이 바뀌면 앞 세션의 커서는 이 번호와 잴 수 없다.
     * 빈 문자열은 못 읽었다는 뜻이고 그때는 비교하지 않는다.
     */
    val session: String = "",
    /**
     * 응답 헤더의 `occurred_at` — 발신자(어댑터 호스트·에뮬레이터)가 **자기 시계로** 이 응답을 찍은 시각이다. 미들웨어가 보낸 시각·받은 시각과
     * 맞대 발신자 시계 차의 구간을 잰다([Middleware] 의 `CLOCK_SKEW`). 계약 필드가 아니라 헤더에 이미 있는 값을 읽는 내부 칸이다.
     * 널은 못 읽었다는 뜻이고 그때는 재지 않는다.
     */
    val occurredAt: Instant? = null,
    /**
     * [faults] 가 관측한 것인가(계약 `GetSnapshotResponse.fault_observability`, 0.10.0). 거짓이면 기체의 결함을 못 본 것이고 [faults] 는
     * 비어 있되 *결함 없음* 이 아니다. 나머지 칸(태스크·연결·번호)은 그대로 현재값이다. 옛 발신자는 참이다(`UNSPECIFIED`).
     */
    val faultsObservable: Boolean = true,
)

/**
 * 발신자 시계 차 — 발신자 시계에서 미들웨어 시계를 뺀 값의 **구간**이다. 한 번의 왕복으로는 값 하나를 못 재고, 발신자가 찍은 시각이 보낸 순간과
 * 받은 순간 사이에 있다는 것만 안다. 음수면 발신자가 늦다. [low] 는 `occurred − 받은 시각`, [high] 는 `occurred − 보낸 시각` 이다.
 */
internal data class ClockOffset(val low: Duration, val high: Duration)

/**
 * 구간 전체가 임계 [threshold] 밖인 시계 차 — 방향([behind])과 구간에서 0 에 가까운 쪽([nearest])·먼 쪽([farthest])의 크기다.
 * 사람에게 보이는 문장과 되풀이를 거르는 서명을 만든다.
 */
internal data class ClockSuspicion(val behind: Boolean, val nearest: Duration, val farthest: Duration, val threshold: Duration) {

    /**
     * 방향과 크기 등급. 등급은 0 에 가까운 쪽 크기가 임계의 몇 배인지를 2 의 거듭제곱 구간으로 센 것이다(1~2배 · 2~4배 · 4~8배 ...).
     * 크기가 조금 흔들리는 것을 새 줄로 세지 않고, 배로 벌어지면 다시 남기려는 눈금이다.
     */
    val signature: String
        get() {
            val ratio = (nearest.toMillis() / threshold.toMillis().coerceAtLeast(1)).coerceAtLeast(1)
            val grade = 63 - java.lang.Long.numberOfLeadingZeros(ratio)
            return "${if (behind) "LATE" else "EARLY"}:$grade"
        }

    /** 「발신자 시계가 미들웨어보다 20초 늦다」 — 구간이 넓으면 `10~20초` 로 쓴다. */
    fun describe(): String {
        val near = Math.round(nearest.toMillis() / 1000.0)
        val far = Math.round(farthest.toMillis() / 1000.0)
        val size = if (near == far) "${near}초" else "${near}~${far}초"
        return "발신자 시계가 미들웨어보다 $size ${if (behind) "늦다" else "빠르다"}"
    }
}

/** `ReplayEvents(from)` 의 답 — 이벤트들이거나, 버퍼를 벗어났으니 스냅샷부터 다시 세우라는 말. */
sealed interface Replay {
    data class Events(val events: List<Event>) : Replay
    data object Evicted : Replay
}

/**
 * 실행이 본 이벤트 하나 — 감사 자취. 발행 열의 번호와 시각을 그대로 든다(다시 찍지 않는다 — 30초 전 사건이 방금 것으로
 * 보이면 안 된다). [kind] 는 계약 이벤트의 종류(`FAULT_RAISED`·`FAULT_CLEARED`·`TASK_TRANSITION`·`CAPABILITY_CHANGED`)와
 * 이 층이 낸 것(`RESYNC`·`LINK_BROKEN`·`LINK_RESTORED`)이다.
 */
data class ObservedEvent(
    val sequence: Long,
    val occurredAt: String,
    val kind: String,
    val detail: String,
    /**
     * 이 층이 적은 것인가, 계약의 이벤트 열에서 온 것인가. 계약 이벤트만 발행 번호가 단조 증가하고
     * 겹치지 않는다 — 이 층의 관측(재동기화·연결·설비 신호)은 번호를 가질 자리가 없어 커서 값을 빌린다.
     * 둘을 섞어 번호를 보면 «커서가 안 나아갔다» 로 읽힌다.
     */
    val local: Boolean = false,
)

/**
 * 취소 응답(보고서 14.1) — 원상복구가 아니라 중단점과 잔여 물리 상태의 보고다.
 *
 * 칸은 전부 관측이나 이 층의 판정에서 온다. 하위가 받아들였는가는 [refusal] 이, 어디서 멈췄는가는 [inProgressUnit]·
 * [stoppedAfter] 가 말한다. 앞 판의 `accepted`·`motionStopped` 는 관측 없이 늘 참이던 상수라 걷었다(§15.258).
 */
data class CancelReport(
    val executionId: String,
    val version: Int,
    val completedUnits: List<String>,
    val inProgressUnit: String?,
    val notStartedUnits: List<String>,
    val residualHold: HoldState,
    /** 정리 동작의 결과 — 계약의 `CANCELLED`(done) / `CANCELLED_RECOVERY_FAILED`(failed). */
    val cleanup: String,
    val finalState: PhysicalState,
    /**
     * 하류가 진행 중 단위의 중단을 **거절**한 사유(계약의 거절 코드) — 그 스킬이 취소를 안 든다(`CANCEL_UNSUPPORTED`).
     * 그러면 그 단위는 끝까지 가고 다음 경계에서 멈춘다. 보고서 16장 *"취소 수준 지원 범위와 취소 가능 지점 제약"* 을
     * 드러내는 자리이고, 지원하지 않는 것을 지원하는 것처럼 감추지 않는다(7장). 하류가 받아들였으면 `null`.
     *
     * 건 취소가 하위에 끝내 닿지 못한 채 단위가 끝났으면 이 층의 값 [Middleware.CANCEL_NOT_SENT] 가 실린다. 하위의 거부 코드가 아니라
     * 이 층이 «취소가 닿았는지 모른다» 고 말하는 자리다.
     */
    val refusal: String? = null,
    /**
     * 하류가 중단을 거절해([refusal]) 진행 중 단위가 **끝까지 간 뒤** 멈췄을 때, 그 단위. 이때 [inProgressUnit] 은 `null`
     * 이다 — 중단된 것이 없다. 앞 판은 같은 이름을 [inProgressUnit] 과 [completedUnits] 양쪽에 두어 두 사실을 한 자리로
     * 말했다(§15.93 정직 항목).
     */
    val stoppedAfter: String? = null,
)

/**
 * `ISA95JobResponseDataType` 의 모양에 정준 결과를 얹은 것 — 상류에 내는 결과 통보.
 *
 * 상류에 드러내는 것(보고서 16장): 도달 등급과 요구 등급 충족 여부, 아직 확인되지
 * 않았다는 사실(`UNVERIFIED`), 일부 단위만 완료됐다는 사실과 그 목록, 운영자 판단
 * 필요, 인계 대기 같은 지연. 감추는 것: SDK, 하류 상태 이름, 어댑터가 어느 층에 붙었는지.
 */
data class JobResponse(
    val jobResponseId: String,
    val jobOrderId: String,
    /**
     * 이 통보가 가리키는 실행(ADR 48). **미들웨어 자체 확장**이다 — 표준 응답은 주문만 가리키는데, 승인으로 선
     * 실행을 받은 쪽은 주문이 아니라 실행으로 잇는다. 같은 인스턴스 안에서만 유일하다([Middleware.instanceId]).
     */
    val executionId: String,
    val version: Int,
    val physicalState: PhysicalState,
    val requiredEvidence: Evidence,
    val reachedEvidence: Evidence,
    val completedUnits: List<String>,
    val unverifiedUnits: List<String>,
    /** 미완료 단위와 그 사유 — 정준 분류·검증 결과·지연 사정. 벤더 이름은 없다. */
    val incompleteUnits: Map<String, String>,
    val operatorRequired: Boolean,
    val residualHold: HoldState,
    /** 결과가 아직 확인되지 않은 단위 — 요청은 갔는데 접수됐는지 모른다(16장 *"드러내야 하는 것"*). */
    val inDoubtUnits: List<String> = emptyList(),
    /** 이 실행의 하류가 `IN_DOUBT` 를 자동으로 푸는가(16장 — 실행 조회 가능 여부). 거짓이면 그 상황은 운영자에게 간다. */
    val autoResolvesInDoubt: Boolean = true,
    /** 완료 단위가 실어 온 결과 참조(있는 것만). 점검의 측정값·증거 자료 참조가 올 자리 — 지금은 비어 있다. */
    val results: Map<String, String> = emptyMap(),
    /**
     * 기체가 *새 태스크를 받을 수 없다*(`can_accept_new_task=false`)고 말하는 활성 결함의 정준 분류 — 그래서 다음 단위를
     * 시작하지 않고 세워 두었다. 16장 *"자동 복구가 불가능해 운영자 판단이 필요하다는 사실"*. 비어 있으면 막힌 것이 없다.
     */
    val blockedBy: List<String> = emptyList(),
    /** 기체와의 연결 상태(계약 `ConnectionState` 의 이름). `CONNECTION_BROKEN`·`OFFLINE` 이면 도는 단위의 결과는 미확정이다. */
    val connection: String = ConnectionState.CONNECTION_STATE_ONLINE.name,
    var ack: UpstreamAck = UpstreamAck.SENT_UNACKED,
)

/**
 * 출발 자리의 관측을 단위가 **요구하는가** — 임무 정의가 단위 노드마다 선언한다(`sourceObservation`).
 *
 * 기본을 뒤집지 않고 **암묵을 없앤다.** 말이 없는 셀에서 라인이 통째로 서면 이 관문은 곧 꺼지므로 기본은 그대로
 * [OPTIONAL] 이다. 다만 신원을 대는 출발 자리가 있는 정의는 어느 쪽인지 적어야 활성화된다(`MissionValidator`).
 * 코드 케이퍼빌리티는 검증기를 거치지 않으므로 [ExecutionUnit] 의 기본값 [OPTIONAL] 로 오늘의 거동을 유지한다.
 */
enum class SourceObservation {
    /** 관측이 판정에 모자라면 보내지 않고 확인을 요구한다([SourceUnobserved]). */
    REQUIRED,

    /** 설비가 말이 없으면 판정하지 않고 보낸다 — 오늘의 거동이다. */
    OPTIONAL,
}

/**
 * 출발 자리를 **확인하지 못해** 보내지 않았다 — 관측을 요구한 단위([SourceObservation.REQUIRED])의 답이다.
 *
 * **결품([RemedyOutcome.SourceMissing])과 접지 않는다.** 결품은 «없다» 는 관측이고 다음 행동이 주문을 고치는 것(대체
 * 자리)이다. 이것은 관측이 없다는 것이고 다음 행동이 **사람의 확인**이다 — 접으면 운영자가 재고를 의심하고 멀쩡한 자재를
 * 두고 주문을 고친다. 그래서 대체 자리를 계산하지 않는다.
 *
 * **확인은 이 층의 덮어쓰기 칸으로 들어오지 않는다.** 상위 시스템의 확인, 인계 시점 점검, 기록된 수동 확인 중 무엇이든
 * 셀 포트([CellSignals.observe])가 그 자리의 신원을 답하는 것으로 들어오고, 같은 작업 요청을 다시 내면 관문이 그 관측으로
 * 판정한다. 일반 운영 해제로 이 답을 넘기는 길은 이 층에 없다.
 *
 * **조치 탐색 대장에 적지 않는다.** 대장의 답([RemedyOutcome])에 갈래를 더하면 내보내기 줄의 `outcome` 이 바뀐다.
 *
 * @param material 그 자리에 있어야 할 것. 단위가 자재를 지정하지 않으면 널이다
 * @param source 확인하지 못한 출발 자리
 * @param occupied 설비가 말한 것. 널이면 말이 없었고, 참이면 «있다» 고만 했다(무엇인지는 모른다)
 */
data class SourceUnobserved(
    val material: String?,
    val source: String,
    val occupied: Boolean?,
)

/**
 * 목적지(자재를 둘 자리)를 하달 전에 **설비 관측으로 볼지** — 임무 정의가 단위 노드마다 선언한다(`destinationObservation`).
 *
 * 출발 관측 선언([SourceObservation])을 본뜬 것이지만 **기본이 뜻하는 것이 다르다.** 출발 자리는 선언과 무관하게 결품을 보고
 * 선언은 침묵만 가른다. 목적지는 [OPTIONAL] 이면 **아예 보지 않는다.** 보고서 12.1 과 근거 윈도우([EvidenceWindow])는 작업 전
 * 목적지에 남은 «있다» 를 옛 신호(옛 용기·옛 부품)로 다루기 때문이다. 그 신호로 거부하면 래치·지연으로 옛 값을 남기는 셀에서
 * 멀쩡한 작업 지시가 서고, 그 전제로 목적지를 제출 전에 «있다» 로 프로그램하는 시험들과도 부딪친다.
 *
 * [REQUIRED] 는 그 현장이 **작업 전 목적지 «있다» 를 물리적 점유로 본다**고 선언하는 것이다. 근거 윈도우의 판정(완료 뒤 어느
 * 신호를 이 완료의 근거로 세는가)은 그대로다 — 게이트는 하달 전에 비었는지를 보고, 윈도우는 완료 뒤에 무엇이 들어왔는지를 본다.
 *
 * 기본을 뒤집지 않고 **암묵을 없앤다.** 식별 정보와 목적지를 둘 다 대는 정의는 어느 쪽인지 적어야 활성화된다(`MissionValidator`).
 * 코드 케이퍼빌리티는 검증기를 거치지 않으므로 [ExecutionUnit] 의 기본값 [OPTIONAL] 로 오늘의 동작을 유지한다.
 */
enum class DestinationObservation {
    /** 목적지가 비었다는 관측이 있어야 보낸다. 차 있으면 «목적지 점유», 말이 없으면 «목적지 미관측» 으로 거부한다. */
    REQUIRED,

    /** 목적지를 설비 관측으로 보지 않는다 — 오늘의 동작이다. 진행 중인 실행의 예약만 본다. */
    OPTIONAL,
}

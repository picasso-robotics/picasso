package dev.picasso.middleware

import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import java.security.MessageDigest
import java.time.Instant

/**
 * **무엇을 하려던 일이었나**(§15.178).
 *
 * 분류와 그 근거는 «무엇이 어긋났나» 이고 이것은 «무엇을 하려 했나» 다. 뒤엣것이 없으면 설명이
 * 어긋남의 재진술에 머문다 — 어느 슬롯에 무엇을 놓으려 했는지를 모르면 «잘못 놓았다» 가 무슨 뜻인지
 * 말할 수 없다.
 *
 * **상류가 적은 것과 이 층이 편 것을 함께 든다.** 둘이 갈리는 자리가 곧 이 층의 번역이고, 한쪽만
 * 실으면 읽는 쪽이 그 번역을 볼 수 없다.
 */
data class Intent(
    /** 상류가 지정한 논리적 능력(ISA-95 WorkMaster). */
    val workMasterId: String,
    /** 그 주문의 판. 단위의 개정 번호가 여기서 나온다. */
    val orderVersion: Int,
    /** 상류가 주문에 실은 값. */
    val orderParameters: Map<String, String>,
    /** 주문이 선언한 자재 — 타입과 수량. */
    val materials: List<MaterialRequirement>,
    /** 주문이 지정한 설비와 그 쓰임. */
    val equipment: List<EquipmentRequirement>,
    /**
     * 이 능력이 낼 수 있는 **최고 등급**. 요구 등급이 이것을 넘으면 애초에 접수되지 않으므로,
     * 도달 등급이 낮은 이유가 «능력의 한계» 인지 «이번에 못 받았다» 인지를 이것이 가른다.
     */
    val capabilityMaxEvidence: Evidence,
    /**
     * 유효 시간 윈도우의 폭. `evidenceWindow` 가 **왜 그 범위였는지**가 이 둘이다. ISO-8601.
     * 현장 시간값이 있으면 봉인하는 라운드의 그 값이고, 없으면 케이퍼빌리티 값이다.
     */
    val evidenceWindowBefore: String,
    val evidenceWindowAfter: String,
    /** 이 단위가 하려던 스킬. */
    val skillType: String,
    /** 이 단위에 **실제로 실린** 값. 주문의 값과 다를 수 있고, 그 차이가 이 층이 편 결과다. */
    val unitParameters: Map<String, String>,
    /** 출발 자리·도착 자리. 없으면 널이다 — 빈 문자열로 접지 않는다. */
    val source: String?,
    val destination: String?,
    /** 설비가 그 자리에서 읽어야 할 것. 대조의 기대값이다. */
    val expectedIdentity: String?,
    /**
     * 이 실행을 계획한 **임무 버전**. 코드 케이퍼빌리티로 돈 실행이면 널이다. 작업 지시의 버전([orderVersion])과 다른 축이다 —
     * 같은 작업 지시라도 어느 정의로 펼쳤는지가 «무엇을 하려 했나» 를 바꾼다.
     *
     * **해시에 든다** — 다른 버전으로 펼친 같은 모양의 인시던트는 다른 인시던트다. 내보내기 판 8 부터 의도의 칸(`intent.missionVersion`)으로
     * 실린다(§15.322) — 널은 코드 케이퍼빌리티로 돈 실행이다. 읽는 코드는 세 읽는 쪽 저장소가 인계 노트를 받아 짓는다(ADR 9).
     */
    val missionVersion: Int? = null,
    /**
     * 봉인하는 라운드에 적용한 **현장 설정의 버전**. 현장 시간값 없이 돌면 널이다. 어느 버전의 설정에서 난 인시던트인지
     * 되짚는 자리다(운영 관리 화면 설계 제안 §9).
     *
     * **해시에는 이 값이 있을 때만 줄 하나가 든다** — 그래서 설정 없이 도는 인시던트의 요약은 이 칸이 생기기 전과 같다.
     * 내보내기 판 8 부터 의도의 평평한 칸 셋(`intent.siteSettingsVersion`·`inDoubtGrace`·`stallWindow`)으로 실린다(§15.322).
     *
     * ★**셋은 봉인 라운드의 값이다.** 과거 근거 기한(단위가 완료된 라운드의 뒤 폭)을 정한 설정이라고 읽으면 안 된다 — 그 사이 설정이
     * 바뀌었을 수 있다.
     */
    val siteSettingsVersion: Long? = null,
    /** 봉인하는 라운드의 `IN_DOUBT` 유예. ISO-8601. [siteSettingsVersion] 과 함께 널이거나 함께 있다. */
    val inDoubtGrace: String? = null,
    /** 봉인하는 라운드의 진행 정체 유예. ISO-8601. [siteSettingsVersion] 과 함께 널이거나 함께 있다. */
    val stallWindow: String? = null,
) {
    init {
        // 설정에서 온 셋은 **함께 널이거나 함께 있다**(§15.322). 버전 없이 값만 있으면 해시에 안 들어 같은 요약의 두 인시던트가 생기고,
        // 버전만 있고 값이 빠지면 내보내기 줄이 «설정은 있었는데 그 값은 모른다» 를 지어낸다.
        require((siteSettingsVersion == null) == (inDoubtGrace == null) && (inDoubtGrace == null) == (stallWindow == null)) {
            "현장 설정 셋이 함께 널이거나 함께 있지 않다: siteSettingsVersion=$siteSettingsVersion, inDoubtGrace=$inDoubtGrace, stallWindow=$stallWindow"
        }
    }
}

/**
 * **인시던트의 정체성**(§15.222, §15.322) — 재기동을 넘어 같은 사건을 잇는 열쇠와 그 사건이 처음 선 시각.
 *
 * 인시던트 id(`incident-N`)와 요약은 인스턴스 안의 것이라 재기동마다 바뀐다. 이전 인스턴스의 번들은 메모리라 사라지고, 다시 지은
 * 인스턴스가 같은 보류를 새 인시던트로 연다 — 그래서 인스턴스마다 번들을 모아 세면 같은 보류가 재기동 횟수만큼 더 셈된다. 이 값이
 * 그 둘을 잇는다.
 *
 * **최초 봉인 때마다 하나 선다**([IncidentLog.sealIncidents]). 같은 원인·같은 단위라도 새로 선 보류는 새 열쇠다 — 열쇠는 원인의
 * 분류가 아니라 사건 하나다. 재기동이 다시 연 인시던트는 판단 기록(`JudgmentRecordWire` 판 5)이 보류 판정에 실어 온 값을 그대로 잇는다.
 *
 * @param key **불투명한** 열쇠 — 최초 봉인의 (인스턴스, 인시던트 id)를 적은 문자열이다. 뜯어 읽지 않는다. 같으면 같은 사건이다.
 * @param firstOpenedAt 최초 봉인의 가상 시계([IncidentBundle.at]). 다시 연 인시던트의 `at` 은 다시 연 시각이고 이것은 처음 선 시각이다.
 */
data class IncidentOrigin(val key: String, val firstOpenedAt: Instant)

/**
 * 이 인시던트가 **이 인스턴스에서 처음 선 것인가, 재기동이 다시 연 것인가**(§15.322).
 *
 * 재개 경로가 직접 채운다([IncidentLog.sealIncidents] 의 다시 연 봉인). 근거 윈도우의 `RESUME_HOLD_REOPENED` 줄 시각을 `at` 과 맞대
 * 추정하던 자리를 이 값이 대신한다 — 줄은 다시 연 뒤 근거 윈도우 앞 폭 안에 새로 선 보류의 윈도우에도 실린다.
 */
enum class OpeningKind {
    /** 이 인스턴스가 관측으로 연 인시던트. 정체성([IncidentOrigin])이 이 봉인에서 처음 선다. */
    NEW,

    /** 재기동이 판단 기록의 운영자 보류로 다시 연 인시던트. 정체성은 기록이 실어 온 것을 잇고, 옛 판 기록이면 널(연결 불명)이다. */
    REOPENED,
}

/**
 * **관측을 얼마나 믿을 수 있나**(§15.178).
 *
 * 근거의 세기가 «무엇으로 확인했나» 라면 이것은 «그 확인이 온전했나» 다. 선이 끊긴 채 돈 실행과
 * 내내 붙어 있던 실행은 같은 등급이라도 같은 값이 아니다.
 */
data class ObservationTrust(
    /** 이 실행이 **연결이 끊긴 채** 도는 중인가. 그동안의 결과는 미확정이다. */
    val linkBroken: Boolean,
    /**
     * 옛 판의 종착이 뒤늦게 온 것들. **폐기하지 않는다** — 버리면 «안 왔다» 와 «늦게 왔다» 가
     * 같은 침묵이 된다.
     */
    val lateEvents: List<LateEvent>,
    /**
     * 이 기체가 이 단위의 진행률을 **잴 수 있는가.** ★**널이 「아직 갱신을 못 봤다」다.**
     *
     * 3값이며 널을 거짓으로 접으면 «못 물어봤다» 가 «못 잰다» 가 된다. 진행률의 `0.0` 을 정체로
     * 읽으면 진행률을 안 내는 기종이 언제나 멈춰 있는 것으로 보이고, 그 경보는 곧 무시된다.
     */
    val progressObservable: Boolean?,
    /** 이미 정체로 알렸는가. 한 번만 알린다 — 같은 사실을 되풀이하면 운영자가 곧 무시한다. */
    val progressStalled: Boolean,
)

/**
 * 결함 하나를 **정준 분류와 벤더 원문을 함께** 적은 것(§15.177).
 *
 * 앞 판은 분류 이름만 실었다. 그러면 읽는 쪽이 «왜 그 분류가 됐는지» 를 말할 재료가 없어 **분류를
 * 되풀이하는 것 말고 할 수 있는 것이 없다.** 계약이 칸을 갖고 있고 이 층도 읽는데 번들만 버리고 있었다.
 *
 * **분류는 분기의 입력이고 원문은 진단의 재료다.** 둘을 한 자리에 두는 것이 이 층의 본업인 번역을
 * 읽는 쪽이 따라갈 수 있게 한다 — «이 벤더 코드가 이 분류로 왔다» 를 어댑터 매핑표에 대고 짚을 수 있다.
 */
data class FaultDetail(
    /** 미들웨어가 매긴 정준 분류(접두사 없이). 분류가 없으면 `UNCLASSIFIED` 다. */
    val failureClass: String,
    /** 어댑터가 낸 오류 유형. 벤더 이름공간이면 `X_` 로 시작한다. */
    val errorType: String,
    /** 벤더 원문 — 코드·메시지·상태 이름. **비어 있을 수 있다**(벤더가 없는 경우). */
    val vendorDetail: String,
    /** 사람이 취할 조치. 사람 개입 등급을 실무에서 쓸모 있게 만드는 값이다. */
    val errorHint: String,
    /**
     * 스킬 수준 결함이면 `KEY_SKILL_ID`·`KEY_TASK_ID` 가 들어온다. 로봇 수준이면 **빈 목록**이다 —
     * 그 구분이 «이동은 되는데 조작만 안 된다» 를 표현하는 자리다(§4.6).
     *
     * **맵으로 접지 않는다.** 같은 열쇠가 둘 올 수 있고, 접으면 그중 하나가 조용히 사라진다.
     */
    val references: List<FaultReference>,
    val canContinueCurrentTask: Boolean,
    val canAcceptNewTask: Boolean,
    /** 결함의 수명 종류 — `KIND_UNTIL_CLEARED` · `KIND_UNTIL_NEW_TASK` · `KIND_UNTIL`. */
    val activeUntilKind: String,
    /** 수명이 절대 시각이면 그 시각. 아니면 빈 문자열이다. */
    val activeUntilTime: String,
)

/** 결함이 지목한 대상 하나. 계약 `Reference` 를 옮겨 싣는다. */
data class FaultReference(val key: String, val value: String)

/**
 * **몇 걸음 중 어디서 깨졌나**(§15.177).
 *
 * 앞 판은 `unitId` 하나만 실어 앞뒤가 없었다. 단위 이름만으로는 그것이 첫 걸음인지 마지막 걸음인지
 * 알 수 없고, 그러면 «거의 다 끝났는데 마지막에 깨졌다» 와 «시작하자마자 깨졌다» 가 같은 모양이 된다.
 *
 * 총수는 [plan] 의 크기다. 따로 싣지 않는다 — 같은 사실을 두 칸에 두면 갈릴 자리가 생긴다.
 */
data class StepPosition(
    /** 이 단위가 몇 번째인가. **1 부터.** 계획에 없으면 0 이다 — 없는 것을 1 로 적지 않는다. */
    val at: Int,
    /** 계획된 단위 이름 전부, 순서대로. */
    val plan: List<String>,
    /** 여기까지 끝난 단위 이름. [plan] 의 부분집합이다. */
    val completed: List<String>,
)

/**
 * 하나의 실패를, 사람이 **원인을 말할 수 있는 최소 단위**로 묶은 것(설계안 §4).
 *
 * **새 사실을 만들지 않는다.** 실패 분류도 차단 결함도 잔여 파지도 이미 각자의 자리에서 답한다.
 * 번들이 하는 일은 그 사실들을 한 시점의 한 사건으로 묶고, 그때의 근거 창을 붙이는 것이다 —
 * 운영자가 "이 단위가" 와 "이 기체가" 와 "그때 무슨 일이 있었나" 를 한 화면에서 잇게.
 *
 * 읽기 전용이다. 이 층의 거동은 번들이 있든 없든 같다.
 */
data class IncidentBundle(
    val incidentId: String,
    /**
     * 재기동을 넘는 **정체성**(§15.322). 새로 선 인시던트([OpeningKind.NEW])는 언제나 있다. 다시 연 인시던트는 판단 기록이 실어 온
     * 값을 잇고, 옛 판(1~4) 기록으로 다시 열면 널이다 — **연결 불명**이며 실행·단위·분류로 추측해 잇지 않는다. 연결 불명은 이후의
     * 재기동에서도 널로 이어진다. 정체성은 번들의 보존 상한과 무관하다 — 그 실행이 장부에 있는 동안 [IncidentLog] 의 정체성 표가 든다.
     *
     * 해시에서 빠진다 — 사건의 내용이 아니라 사건 사이의 연결이다.
     */
    val origin: IncidentOrigin?,
    /** 처음 선 것인가 다시 연 것인가. 해시에서 빠진다. */
    val openingKind: OpeningKind,
    /**
     * 봉인할 때 이 단위의 **시도 번호**(재작업이면 하나씩 오른다). 재발 판정의 재료다 — 다시 연 뒤 재작업해 또 깨지면 시도가 오르고
     * 정체성은 새 것이다. **같은 보류를 잇는 데 쓰지 않는다** — 같은 시도에서 미확정과 보류가 각각 인시던트를 열 수 있어 시도만으로는
     * 원본이 모호하다. 잇는 것은 [origin] 이다. 해시에서 빠진다.
     */
    val attempt: Int,
    val jobOrderId: String,
    val executionId: String,
    /**
     * 어느 기체인가. **자기 선언의 3분의 1이다** — 없으면 읽는 쪽이 분류에서 기종을 역추론하게 되고,
     * 그 추론은 도메인 사실이 아니라 **어댑터 매핑 공백**을 읽는 것이라 매핑 한 줄이 늘면 조용히 틀린다
     * (§15.177). 식별자이지 기종 분기가 아니다 — 이 층은 여전히 기종을 모른다.
     */
    val robotId: String,
    val unitId: String,
    /** 가상 시계. 재현 판정의 기준이고 해시에 든다. */
    val at: Instant,
    /** 실 시계. 현장 대조의 기준이고 해시에서 빠진다. */
    val wallClockAt: Instant,
    /** 미들웨어가 이미 매긴 정준 분류. 번들이 다시 판단하지 않는다. */
    val failureClass: String?,
    /**
     * **[failureClass] 가 그 값이 된 근거** — 이 단위를 실패로 만든 결함의 원문(§15.177).
     *
     * 분류만 있으면 읽는 쪽은 분류를 되풀이하는 것 말고 할 수 있는 것이 없다. [blockedBy] 는
     * **다음 단위를 막는** 결함이라 다음 단위가 없으면 비므로, 이 단위 자신의 실패를 설명하는 자리는
     * 따로 있어야 한다.
     *
     * 하류가 결함 없이 실패를 알린 경우(설비 대조 어긋남 같은)에는 널이다 — 없는 원문을 지어내지 않는다.
     */
    val fault: FaultDetail? = null,
    /**
     * 실행 전체를 막는 결함 — 이 사건이 단위의 문제인지 기체의 문제인지 가른다.
     *
     * **분류 이름으로 접지 않는다**(§15.177). 상류 통보(`JobResponse`)는 분류만 내지만 그것은 계약의
     * 소비자가 분기할 값이고, 이 대장은 사람이 원인을 말할 재료다 — 성질이 다르므로 싣는 것도 다르다.
     */
    val blockedBy: List<FaultDetail>,
    /** 마지막 관측. **빈손과 침묵을 접지 않는다**(§15.145). */
    val residualHold: HoldState,
    /** 판정을 내렸는가 보류했는가. */
    val unresolved: Boolean,
    /** 위반된 사전 조건의 주어. 조건 검사의 출력을 옮긴 것이고 다시 계산하지 않는다. */
    val preconditionSubjects: List<String>,
    /** 유효 시간창 안의 설비 신호와 로봇 이벤트. 원인 후보를 좁히는 실질 재료다. */
    val evidenceWindow: List<ObservedEvent>,
    /** 창 밖이라 버린 관측이 있는가. 조용히 자르면 읽는 사람이 창을 완전한 것으로 오해한다. */
    val windowTruncated: Boolean,
    /**
     * 판단 경로(설계안 §7.2 셋째) — **어느 신호가 어떤 값이어서 그렇게 판단했는가.** 분류만 내면 아무도
     * 배우지 않는다. [expectedHold] 가 효과에서 유도한 기대이고 [observedHold] 가 그때의 관측이며,
     * 둘이 어긋난 결과가 [effectMismatch] 다. 근거를 함께 내는 이유는 신뢰가 아니라 **학습**이다.
     */
    val expectedHold: HoldKind?,
    val observedHold: HoldKind,
    /**
     * 효과-관측 어긋남(설계안 §5) — 번들이 **새로 계산하는 유일한 사실**이다. 나머지는 전부 옮겨 싣는 것이다.
     * 관측이 없거나 볼 수 없으면 `null` 이고, 그때 운영자가 받는 것은 여전히 «모른다» 다(§5.2).
     */
    val effectMismatch: String?,
    /**
     * 상류가 **요구한** 근거 등급과 이 단위가 **실제로 닿은** 등급, 그리고 설비 대조의 결과.
     *
     * ★**«이 판정을 얼마나 믿어야 하나» 가 이 셋이다.** 등급은 순서가 곧 세기이므로(E0 로봇 자기 보고 ·
     * E1 플릿 확인 · E2 독립 설비 확인 · E3 업무 ack), 요구와 도달을 맞대면 «이 결론은 로봇 자기 보고
     * 하나에 기대고 있다» 를 말할 수 있다. 없으면 읽는 쪽은 그 문장을 쓸 수 없다.
     *
     * [verification] 은 설비가 무엇을 말했는가다 — 안 물었다(`NOT_REQUESTED`)와 물었는데 말이 없다
     * (`ABSENT`)와 다른 것을 봤다(`MISMATCH`)는 다음 행동이 다 다르다.
     */
    val requiredEvidence: Evidence,
    val reachedEvidence: Evidence,
    val verification: Verification,
    /** 몇 걸음 중 어디서 깨졌나. */
    val step: StepPosition,
    /**
     * 이 단위를 **누가 실행하는가** — `ROBOT` 이면 어댑터 뒤의 기체, `FLEET` 이면 플릿에 맡긴 운반.
     *
     * ★**없으면 책임 소재를 못 가른다.** 같은 «실패» 라도 앞은 기체와 어댑터의 일이고 뒤는 플릿의
     * 일이며, 다음에 누구에게 물을지가 그것으로 갈린다.
     */
    val route: String,
    /** 무엇을 하려던 일이었나. */
    val intent: Intent,
    /** 관측을 얼마나 믿을 수 있나. */
    val observation: ObservationTrust,
    /** 그때 무슨 모델이었나. 모델이 바뀐 뒤에 사건을 읽으면 이것 없이는 오독한다. */
    val profileRevision: Int,
    val contractSemver: String,
    /**
     * 이 사건이 난 실행이 **승인된 조치로 시작됐다면** 그것을 누른 쪽. 아니면 널이다(ADR 43).
     *
     * 해시에 든다 — 다른 쪽이 승인한 같은 모양의 사건은 **다른 사건**이다. 누가 눌렀는지가 이 사건에
     * 대해 할 말을 바꾸기 때문이다.
     */
    val approvedBy: Approver? = null,
    /**
     * 사람이 이 사건을 읽고 남긴 판정(설계안 §7.2). 읽기 전에는 널이다.
     *
     * 해시에서 빠진다 — 같은 사건이 나중의 검토 때문에 다른 사건이 되지는 않는다.
     */
    val review: IncidentReview? = null,
    /**
     * 사람이 이 사건의 단위에 낸 판단(`OperatorDecision`)이나 사람의 걸음(운영자 취소·회복 승인, [IncidentDecision]). 내기 전에는 널이다.
     *
     * ★**이 걸음이 없으면 번들이 빠짐으로 거짓말을 한다.** `run-4` 는 사건 뒤에 탐색이 서는 한 벌인데,
     * 그 사이에 사람이 재작업을 낸 것이 어디에도 안 실려 읽는 쪽이 «자동으로 회복했다» 로 읽는다.
     * 안내문에 산문으로 경고를 적어 두었으나 **데이터 옆에 산문으로 들고 다녀야 하는 사실은 데이터에서
     * 빠진 사실이다**(§15.188).
     *
     * 해시에서 빠진다 — [review] 와 같은 이유다. 나중의 사람 걸음이 이 사건을 **다른 사건**으로 만들지는
     * 않는다. 무엇이 깨졌나는 봉인 시점에 끝났다.
     */
    val resolution: IncidentResolution? = null,
) {
    init {
        // 새로 선 인시던트는 그 봉인이 정체성의 처음이다. 널은 다시 연 인시던트의 연결 불명에만 선다.
        require(openingKind == OpeningKind.REOPENED || origin != null) { "새로 선 인시던트 $incidentId 에 정체성이 없다" }
    }

    /**
     * 같은 시드와 가상 시계면 같은 값이 나온다(설계안 §4.4).
     *
     * **새 칸은 전부 든다**(§15.177·178). 기체·근거 등급·대조 결과·걸음 위치·결함 원문·의도·경로·
     * 관측 신뢰는 봉인 시점에 확정되고 같은 시드면 같은 값이며, 그중 어느 하나가 달라진 사건은
     * **다른 사건**이다. 특히 기체와 의도가 그렇다 — 같은 모양의 실패라도 어느 기체에서 무엇을 하다
     * 났는지가 할 말을 바꾼다.
     *
     * **맵은 키 순서로 정렬해 적는다**([canonicalMap]). 순회 순서가 해시를 바꾸면 같은 사건이 두 값을 갖는다.
     *
     * **해시에서 빼는 것은 이것뿐이다 — [wallClockAt] 과 [review] 와 [resolution](판단자 `decidedBy` 포함), 그리고 사건 사이의
     * 연결 셋([origin]·[openingKind]·[attempt], §15.322).** 앞의 셋은 실 시계와 나중의 사람 걸음이다. 뒤의 셋은 사건의 내용이 아니라 다른
     * 사건과의 연결이라 빼고, 그래서 같은 시드의 요약은 이 칸들이 생기기 전과 같다. 설계안은 식별자도 빼라고 적었는데,
     * 이 층의 식별자는 전부 세는 수에서 나오므로(`exec-N`·`incident-N`·`resp-N`) 같은 시드면 같은 값이다.
     * 뺄 이유가 없고, 넣으면 사건의 순서까지 고정된다. 이 목록이 곧 "무엇이 결정적인가" 의 답이다.
     */
    /** **키 순서로 정렬한다.** 맵의 순회 순서가 해시를 바꾸면 같은 사건이 두 값을 갖는다. */
    private fun canonicalMap(values: Map<String, String>): String =
        values.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    private fun canonicalFault(f: FaultDetail): String =
        "${f.failureClass}|${f.errorType}|${f.vendorDetail}|${f.errorHint}|" +
            f.references.joinToString(";") { "${it.key}=${it.value}" } +
            "|${f.canContinueCurrentTask}|${f.canAcceptNewTask}|${f.activeUntilKind}|${f.activeUntilTime}"

    fun digest(): String {
        val canonical = buildString {
            appendLine(incidentId)
            appendLine(jobOrderId)
            appendLine(executionId)
            appendLine(robotId)
            appendLine(unitId)
            appendLine(at.toString())
            appendLine(failureClass.orEmpty())
            appendLine(fault?.let { canonicalFault(it) }.orEmpty())
            blockedBy.forEach { appendLine(canonicalFault(it)) }
            appendLine("${residualHold.kind.name}|${residualHold.objectRef}|${residualHold.reason}")
            appendLine(unresolved.toString())
            appendLine(preconditionSubjects.joinToString(","))
            appendLine(windowTruncated.toString())
            appendLine(effectMismatch.orEmpty())
            appendLine(expectedHold?.name.orEmpty())
            appendLine(observedHold.name)
            evidenceWindow.forEach { appendLine("${it.sequence}|${it.occurredAt}|${it.kind}|${it.local}|${it.detail}") }
            appendLine("${requiredEvidence.name}|${reachedEvidence.name}|${verification.name}")
            appendLine("${step.at}|${step.plan.joinToString(",")}|${step.completed.joinToString(",")}")
            appendLine(route)
            appendLine(
                "${intent.workMasterId}|${intent.orderVersion}|${canonicalMap(intent.orderParameters)}|" +
                    intent.materials.joinToString(";") { "${it.materialDefinitionId}=${it.quantity}" } + "|" +
                    intent.equipment.joinToString(";") { "${it.id}/${it.equipmentUse}/${canonicalMap(it.properties)}" } + "|" +
                    "${intent.capabilityMaxEvidence.name}|${intent.evidenceWindowBefore}|${intent.evidenceWindowAfter}|" +
                    "${intent.skillType}|${canonicalMap(intent.unitParameters)}|" +
                    "${intent.source.orEmpty()}|${intent.destination.orEmpty()}|${intent.expectedIdentity.orEmpty()}|" +
                    intent.missionVersion?.toString().orEmpty(),
            )
            // 현장 설정이 있을 때만 한 줄. 없을 때 줄을 더하면 설정 없이 도는 모든 요약이 바뀐다(인계 번들 포함).
            intent.siteSettingsVersion?.let {
                appendLine("$it|${intent.evidenceWindowBefore}|${intent.evidenceWindowAfter}|${intent.inDoubtGrace}|${intent.stallWindow}")
            }
            appendLine(
                "${observation.linkBroken}|${observation.progressObservable}|${observation.progressStalled}|" +
                    observation.lateEvents.joinToString(";") {
                        "${it.unitId}/${it.revision}/${it.currentRevision}/${it.downstreamState}/${it.occurredAt}"
                    },
            )
            appendLine(profileRevision.toString())
            appendLine(contractSemver)
            appendLine(approvedBy?.let { "${it.id}|${it.kind.name}" }.orEmpty())
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** 사람이 자동 진단에 동의했는가 이의를 달았는가(설계안 §7.2 둘째). */
enum class ReviewVerdict { AGREED, DISPUTED }

/**
 * 교대 종료에 사람이 사건을 읽고 남긴 것.
 *
 * **실시간이 아니라 사후에 넣는다.** 라인을 멈추지 않으니 고무도장이 될 유인이 적고, 그래서 이 기록이
 * 지표로 쓸 값이 된다. 원인 지목은 가설이고 정답은 정비 실적과 재발 여부로 나중에 나온다 — 되먹이지
 * 않으면 정답 라벨 없는 자동 진단이 영원히 검증되지 않는다(설계안 §7.2 첫째).
 */
/**
 * 사람이 사건의 단위에 낸 판단과 그 시각.
 *
 * **시각을 둘 든다.** 가상 시계는 이 걸음이 사건과 탐색 **사이** 어디에 섰는지를 재는 기준이고,
 * 실 시계는 현장 대조의 기준이다. 하나만 실으면 읽는 쪽이 둘 중 하나를 못 한다.
 */
data class IncidentResolution(
    val decision: IncidentDecision,
    /** 가상 시계. 재현의 기준이다. */
    val at: Instant,
    /** 실 시계. 현장 대조의 기준이다. */
    val wallClockAt: Instant,
    /**
     * 누가 판단했는가(ADR 47). 사람만 판단하므로 종류는 언제나 `PERSON` 이다 — 운영자 취소와 회복 승인도 사람의 입구로만 온다(§15.320).
     *
     * 내보내기 판 8 부터 인시던트 줄의 `resolution.decidedBy` 로 실린다(§15.322) — 같은 줄 `approvedBy` 와 같은 `{id, kind}` 모양이다.
     * **id 는 호출자의 주장이지 인증 증명이 아니다**(ADR 47). 읽는 쪽은 원문 id 를 감사 조회·승인자 연결에서만 읽고, 설명 생성 입력에는
     * 종류만 넘긴다(인계 노트). 해시에서 빠진다([resolution] 과 같은 이유).
     */
    val decidedBy: Approver,
)

/**
 * 사람이 사건의 단위에 낸 걸음의 **종류**(§15.188, §15.320). 운영자 판단 둘([OperatorDecision])에 사람의 걸음 둘이 더해졌다.
 *
 * - [OPERATOR_CANCEL] — 사람이 운영자 취소 입구([Middleware.cancelByOperator])로 그 실행을 닫았다. 상류의 취소([Middleware.cancel])는
 *   누가 했는지가 없어 싣지 않는다 — 승인자 없는 걸음을 싣으면 그 칸이 무기명 판단 경로가 된다.
 * - [REMEDY_APPLIED] — 사람이 회복 제안을 승인해 조치 열이 그 단위 앞에 끼었다([Middleware.approveRecovery]). **«회복 완료» 가 아니다** —
 *   조치가 끝났는지, 원래 단위의 새 시도가 나갔는지는 두 단위의 상태로 읽는다. 사람의 재작업([REWORK])으로도 적지 않는다.
 *
 * 운영자 판단과 다른 타입인 것은 [Middleware.resolve] 가 둘째 둘을 받지 않기 때문이다 — 한 타입이면 «취소» 를 판단 입구로 낼 길이 생긴다.
 */
enum class IncidentDecision {
    CONFIRM_DONE, REWORK, OPERATOR_CANCEL, REMEDY_APPLIED;

    companion object {
        /** 운영자 판단을 같은 이름의 걸음으로. */
        fun of(decision: OperatorDecision): IncidentDecision = valueOf(decision.name)
    }
}

data class IncidentReview(
    val verdict: ReviewVerdict,
    /** 사람이 적은 원인. 이의라면 사람 쪽 판단이 여기 들어간다. */
    val cause: String,
    /** 읽은 시각 — 현장 대조용이므로 실 시계다. */
    val at: Instant,
)

/**
 * 검토가 실제로 일어나는가, 그리고 자동 진단이 맞는가(설계안 §7.2 둘째).
 *
 * **[disputeRate] 만 보면 안 된다.** 아무도 안 읽으면 이의가 0 이고, 그것은 완벽해진 것이 아니라 아무도
 * 안 읽는 것이다. 그래서 읽은 것이 없으면 이의율은 `null` 이다 — 0 을 돌려주면 그 자리에서 «완벽하다» 로
 * 읽힌다. 검토율과 함께 보라는 규율을 타입이 든다.
 */
data class ReviewMetrics(val total: Int, val reviewed: Int, val disputed: Int) {
    val reviewRate: Double? = if (total == 0) null else reviewed.toDouble() / total
    val disputeRate: Double? = if (reviewed == 0) null else disputed.toDouble() / reviewed
}

/**
 * 같은 조치가 거듭 승인된 사실(설계안 §7.3).
 *
 * **임시 대안이 매끄럽게 작동할수록 근본 원인을 고칠 압력이 사라진다.** 이것은 계열이 알려진 실패
 * 양식이다 — 응급 조치가 표준 작업으로 굳는 것, 자동화가 수작업 부담을 가려 근본 수정을 미루는 것.
 * 그래서 반복 자체를 지표로 올린다.
 */
data class RepeatedRemedy(val robotId: String, val steps: List<String>, val approvals: Int)

package dev.picasso.adapter.core

import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.TaskState
import java.time.Instant

/**
 * 어댑터의 **북쪽 모양** — 계약 서버(어댑터 호스트)가 기종을 모른 채 부르는 것.
 *
 * 세 어댑터(Spot·Digit·G1)가 같은 메서드 열을 각자 갖고 있었다. 호스트가 생기면서 그 열이 인터페이스가
 * 됐다 — 호스트는 이것만 알고, 기종은 구현 뒤에 있다(ADR 33). **판단은 여기 없다.** 어댑터는 로봇이 말한
 * 것을 계약의 낱말로 옮길 뿐이고, 무엇을 할지는 계약 소비자(미들웨어)가 정한다.
 *
 * **태스크는 한 번에 하나다.** 실물은 예외 없이 배타적 제어 모델이고(§4.9), 세 어댑터가 모두 그렇게 산다.
 * 동시 태스크가 필요하면 기체마다 어댑터 인스턴스를 둔다(§15.77).
 *
 * 기본 구현이 있는 것들은 **벤더에 그 프리미티브가 없는 것이 흔해서**다 — 재개·재시도는 셋 중 아무도 안 들고,
 * 결과 참조는 Spot 의 취득만 낸다. 기본값은 "없다" 이지 "된다" 가 아니다.
 */
interface RobotAdapter {

    /** 지금 든 태스크의 상태. 아무것도 안 들었으면 `UNSPECIFIED`. */
    val state: TaskState

    /**
     * [taskId] 는 **계약의 `task_id`** 다 — 정체성 열(보고서 15.1: 요청 ID·실행 ID·하류 작업 ID·버전)의 하류 칸이고, 어댑터는
     * 그것을 벤더 쪽 결속 자리(Spot `CaptureActionId.group_name`)에 그대로 쓴다. 어댑터가 자기 식별자를 따로 만들면 넷째
     * 칸이 생기고 결과 참조가 상류의 단위와 안 맞는다 — e2e 시험이 그것을 잡았다(§15.98).
     */
    fun accept(taskId: String, skillType: String, parameters: Map<String, Any>, startedAt: Instant): Acceptance

    /** 상태를 갱신하고 돌려준다. 호스트가 펌프마다 부른다. */
    fun poll(now: Instant): TaskState

    fun pause(): Applied

    /**
     * 재개 — **지금 파라미터로** 다시 시작한다.
     *
     * [parameters] 를 받는 것이 요점이다. §4.4 는 `PAUSED` 에서 온 갱신을 *파라미터만 갈아 두고 재개 때 적용하라*
     * 고 하는데, 이 자리가 없으면 호스트가 그 갱신을 아예 못 받는다(§15.113). 멈춘 동안 갱신이 없었으면 접수 때의
     * 것이 그대로 온다 — 호출자가 두 경우를 가르지 않는다.
     */
    fun resume(parameters: Map<String, Any>): Applied =
        Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "이 어댑터에는 재개가 없다")

    fun cancel(): Applied

    /**
     * **도는 태스크의 갱신** — §4.4 의 `Halt` → `Reset` → 새 파라미터로 `Start`.
     *
     * 태스크의 신원은 그대로다([taskId] 가 같다). 벤더 쪽에서는 대개 *멈추고 다시 시킨다* 이고, 그래서 이것은
     * **합성**이다 — 셋을 다 들어야 갱신이 된다. Spot 은 `StopMission`→`LoadMission`→`PlayMission`, Digit 은
     * `remove-action`→`add-sequential-actions` 로 든다. 플릿(Orbit)에는 도는 미션을 멈추는 문이 없고 G1 에는
     * 멈춤 프리미티브가 없으므로 **기본값이 *수단이 없다*** 이며, 호스트가 그것을 계약의
     * `UPDATE_UNSUPPORTED` 로 옮긴다(§15.109).
     *
     * 스킬 타입이 바뀌는 갱신은 호스트가 먼저 막는다 — 여기 오는 것은 같은 스킬의 새 파라미터뿐이다.
     */
    fun update(taskId: String, skillType: String, parameters: Map<String, Any>, at: Instant): Applied =
        Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "이 어댑터는 도는 태스크를 갱신하지 않는다")

    /** 재시도 — [resume] 과 같은 이유로 **지금 파라미터**를 받는다(§4.4 의 `RETRIABLE` 갱신). */
    fun retry(parameters: Map<String, Any>): Applied =
        Applied.Refused(Refusal.NO_VENDOR_PRIMITIVE, "이 어댑터에는 재시도가 없다")

    /** 잔여 물리 상태(§4.4). */
    fun hold(): HoldObservation

    /**
     * 진행률 — 벤더가 **셀 수 있는 근거**를 줄 때만 낸다.
     *
     * 기본값이 *못 잰다* 인 것이 요점이다. 진행률은 지어낼 수 있는 유일한 값이라(상태와 달리 아무 숫자나 그럴듯하다)
     * 국면을 분수로 바꾸고 싶은 유혹이 있는데, 그 순간 상류가 보는 숫자에 근거가 없어진다.
     */
    fun progress(): ProgressObservation = ProgressObservation.NotObservable("이 어댑터는 진행률을 안 낸다")

    /** 기체 수준 결함(§4.6). */
    fun faults(): FaultObservation

    /** 종착이 실패인 태스크의 `Fault`(정준 분류 + 벤더 원문). 실패가 아니거나 못 옮기면 `null`. */
    fun failure(): Fault? = null

    /** 종착이 성공인 태스크의 결과 참조 — 계약의 `partial_result`. 낼 것이 없으면 `null`. */
    fun result(): String? = null

    /** ADR 35 의 확인 질의. */
    fun knownSiteNames(): SiteNames

    /**
     * 기체가 보고하는 로봇 소프트웨어 식별자 — 생존 보고에 실려 레지스트리가 프로파일의 `derived_from` 과 대조한다(§15.55).
     * **`null` 은 못 읽는다이지 빈 문자열이 아니다.** 셋 중 아직 아무도 안 읽는다(Spot 은 `GetRobotId` 가 있으나 링크에 없다).
     */
    fun robotSoftware(): String? = null

    /**
     * [poll] 이 돌려주는 종착이 **벤더에서 읽은 그 태스크의 종착인가**(ADR 54) — 어댑터가 제 지역 변수로 지은 상태가 아니라. 호스트는 결과를 모르는
     * 조작이 남은 태스크의 종착을 이 관측으로만 «해소» 로 읽어 인수 확인의 막음을 걷는다. 기본은 거짓 — 모르면 걷지 않는다(운영자 확인으로 간다).
     * **어댑터 수명 동안 바뀌지 않는 값이어야 한다** — 호스트는 한 번 참을 본 어댑터를 계속 벤더 관측 어댑터로 여겨 종착 태스크를 다시 읽는다.
     */
    val pollObservesVendor: Boolean get() = false
}

package dev.picasso.mimic.engine

import dev.picasso.capability.PreconditionCheck
import dev.picasso.contracts.v1.Capability
import dev.picasso.contracts.v1.FailureClass
import dev.picasso.contracts.v1.Fault
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.Lifetime
import dev.picasso.contracts.v1.ParameterValue
import dev.picasso.contracts.v1.Reference
import dev.picasso.contracts.v1.RejectionCode
import dev.picasso.contracts.v1.SkillDeclaration
import dev.picasso.profile.ProfileDocument

/** 기체가 들고 있는 태스크 하나. */
class TaskRuntime(
    val taskId: String,
    val skillType: String,
    val machine: TaskMachine,
    /**
     * **접수 시점의 스킬 선언과 문자열 한도**(§8.4의 pinning).
     *
     * `TaskMachine`은 이미 자기 몫을 들고 있는데(전이표·소요시간) **갱신의
     * 파라미터 검사가 빠져 있었다** — 라이브 능력을 봤다. 그래서 개정판이
     * 좁아진 뒤 진행 중 태스크를 갱신하면 자기가 접수될 때 유효했던 값이
     * 거절됐다. 소비자는 자기가 보낸 것이 왜 갑자기 틀렸는지 알 수 없다.
     *
     * 실측으로 찾았다 — 폴링 청크의 결함 주입이 그 자리를 지나는 시험이
     * 없다는 것을 먼저 알려 줬다.
     */
    val pinnedSkill: SkillDeclaration,
    val pinnedMaxStringLength: Int,
) {
    val log = TaskLog()

    /**
     * 마지막으로 적은 잔여 물리 상태(계약의 `HoldState`, §4.4).
     *
     * **상태가 아니라 관측의 기록이다.** [TaskHost.record]가 갱신마다 다시
     * 정한다. 이전 값을 들고 있는 이유는 `FAILED`·`RETRIABLE`·
     * `NEEDS_INTERVENTION`이 파지를 바꾸지 않기 때문이다 — 실패했다고
     * 물건이 내려놓아지지는 않는다. 놓친 것은 [payloadLost]가 따로 말한다.
     */
    var hold: HoldState = HoldState.getDefaultInstance()
        internal set

    /**
     * 이 태스크를 실패 종착으로 보낸 결함. [TaskHost.halt]가 정하고 [TaskHost.record]가
     * 실패 상태의 갱신에만 싣는다 — 재시도로 `RUNNING` 에 돌아가면 안 실린다.
     */
    var failure: Fault? = null
        internal set

    /** `PAYLOAD_LOST`가 섰다 — 들고 있던 것을 놓쳤으므로 그 뒤로는 빈손이다. */
    var payloadLost: Boolean = false
        internal set

    /**
     * §4.4의 래치 위반을 관측했는가.
     *
     * **상태가 아니라 관측이다.** `TaskMachine`에 두면 그것이 열한 번째
     * 상태처럼 보이고, 종착 넷 위에 선 것들(pinning·갱신 규칙·드레인 판정)이
     * 그 값을 보기 시작한다. 태스크는 여전히 종착이며, 달라진 것은
     * **우리가 무엇을 보았는가**뿐이다.
     */
    var terminalViolationSeen: Boolean = false
        private set

    internal fun markTerminalViolation() {
        terminalViolationSeen = true
    }
}

/** §10.5의 `ForceControlAuthorityLoss` 결과. */
sealed interface AuthorityOutcome {
    /** @param terminated 이 상실로 종착한 태스크들. 비어도 결함은 선다. */
    data class Lost(val raised: Boolean, val terminated: List<String>) : AuthorityOutcome

    data class Rejected(val detail: String) : AuthorityOutcome
}

/** §10.5의 `ForceTerminalViolation` 결과. */
sealed interface ViolationOutcome {
    /** @param raised 실제로 새로 선 결함이면 참. */
    data class Seen(val state: TaskState, val raised: Boolean) : ViolationOutcome

    data class NotFound(val taskId: String) : ViolationOutcome

    data class Rejected(val detail: String) : ViolationOutcome
}

/** §10.5의 `ForceFault` 결과. */
sealed interface ForceOutcome {
    /** @param taskState 전파가 보낸 곳. 로봇 수준이면 `null`. */
    data class Raised(val raised: Boolean, val taskState: TaskState?) : ForceOutcome

    data class NotFound(val taskId: String) : ForceOutcome

    data class Rejected(val detail: String) : ForceOutcome
}

/** `StartTask`의 결과. §4.4의 두 표와 §5.3·§10.4 ③의 판정이 여기 모인다. */
sealed interface StartOutcome {
    /** 새 태스크이거나 갱신을 받아들였다. */
    data class Accepted(val task: TaskRuntime) : StartOutcome

    /** 같은 revision을 다시 받았다 — 같은 핸들을 그대로 돌려준다(§4.4). */
    data class Idempotent(val task: TaskRuntime) : StartOutcome

    data class Rejected(
        val code: RejectionCode,
        val detail: String,
        val parameterKeys: List<String> = emptyList(),
        /** 사전 조건 거절이면 어긴 조건의 주어들 — `KEY_PRECONDITION_SUBJECT` 로 나간다. */
        val subjects: List<String> = emptyList(),
    ) : StartOutcome
}

/**
 * 한 기체가 호스팅하는 태스크들. **전송을 모른다** — `transport`가 이 결과를
 * wire로 옮긴다(§10.1).
 */
class TaskHost(
    /**
     * 지금 유효한 능력과 문서를 **매번 읽는다**(§8.4 ④의 반영).
     *
     * 스냅샷으로 잡으면 개정판이 바뀌어도 엔진이 옛것을 계속 쓴다 — 그러면
     * 활성화가 아무 일도 안 하는 것과 같다. 이미 **접수된 태스크**는
     * 자기 `TaskMachine`이 생성 시점의 스킬 선언과 소요시간을 들고 있으므로
     * 여기가 바뀌어도 안 흔들린다. **그것이 §8.4의 pinning이다.**
     */
    private val capabilityOf: () -> Capability,
    private val documentOf: () -> ProfileDocument,
    private val clock: Clock,
    /** 엔진이 전이를 보고할 곳(§4.7). 발행은 전송이 붙인다. */
    private val listener: EngineListener = EngineListener.NONE,
    /** 이 기체의 결함들(§4.6). 수명을 거두는 것은 [tick] 하나다. */
    private val faults: FaultRegistry = FaultRegistry(clock),
    /**
     * §10.4 ①의 추첨. 기체마다 하나이며 인출 순서가 §12.1의 불변식이다.
     *
     * **기본값을 두지 않는다.** `Seeded`의 계약이 "기체마다 하나"인데
     * 기본 인자는 그것을 **조용히 깨는 문**이다 — 넘기는 것을 잊은 호출자가
     * 기체의 스트림과 다른 스트림을 갖게 되고 컴파일은 통과한다.
     */
    private val random: Seeded,
    /**
     * §8.2의 런타임 축소로 지금 못 쓰는 스킬들. **매번 읽는다** — 스냅샷을
     * 잡으면 축소가 엔진에 안 닿는다.
     */
    private val withdrawn: () -> Set<String> = { emptySet() },
) {
    /**
     * 편의 생성자. 프로파일이 안 바뀌는 호출 지점(시험 대부분)이 쓴다.
     */
    constructor(
        capability: Capability,
        document: ProfileDocument,
        clock: Clock,
        listener: EngineListener = EngineListener.NONE,
        faults: FaultRegistry = FaultRegistry(clock),
        random: Seeded,
        withdrawn: () -> Set<String> = { emptySet() },
    ) : this({ capability }, { document }, clock, listener, faults, random, withdrawn)

    private val capability: Capability get() = capabilityOf()
    private val document: ProfileDocument get() = documentOf()

    /**
     * 추첨은 문서에서 온다. 문서가 바뀌면 다시 만든다 — **캐시하지 않으면**
     * 매 tick 마다 실패 모드를 다시 파싱하고, **캐시만 하면** 새 개정판의
     * 실패 모드가 영영 안 걸린다.
     */
    private var drawFor: ProfileDocument? = null
    private var drawCache: FailureDraw? = null
    private val draw: FailureDraw
        get() {
            val current = document
            if (drawFor !== current) {
                drawFor = current
                drawCache = FailureDraw(current)
            }
            return drawCache!!
        }

    private val tasks = LinkedHashMap<String, TaskRuntime>()

    fun find(taskId: String): TaskRuntime? = tasks[taskId]

    /** 호스팅 중인 태스크 전부. 전송이 열린 스트림에 밀 때 쓴다. */
    val all: Collection<TaskRuntime> get() = tasks.values

    fun skillOf(skillType: String): SkillDeclaration? =
        capability.skillsList.firstOrNull { it.skillType == skillType }

    fun start(
        taskId: String,
        revision: Int,
        skillType: String,
        parameters: List<ParameterValue>,
    ): StartOutcome {
        val existing = tasks[taskId]
        if (existing != null) return update(existing, revision, skillType, parameters)

        // **있었는데 사라진 것이 먼저다.** 순서가 바뀌면 축소된 스킬이
        // `SKILL_ABSENT`로 나가고, 계약이 둘을 나눈 뜻이 죽는다 — 소비자는
        // 캐시를 다시 세우면 될 일에 요구 집합을 고치러 간다.
        if (skillType in withdrawn()) {
            return StartOutcome.Rejected(
                RejectionCode.REJECTION_CODE_CAPABILITY_WITHDRAWN,
                "런타임에 축소된 스킬이다: $skillType (GetCapabilities로 다시 세워라)",
            )
        }

        val skill = skillOf(skillType) ?: return StartOutcome.Rejected(
            // 애초에 선언한 적 없는 스킬이다. 있었다가 사라진 것(CAPABILITY_WITHDRAWN)과
            // 소비자의 대응이 다르다 — 저쪽은 캐시를 다시 세우면 되고 이쪽은
            // 요구 집합이 틀린 것이다.
            RejectionCode.REJECTION_CODE_SKILL_ABSENT,
            "선언하지 않은 스킬이다: $skillType (선언된 것: ${capability.skillsList.map { it.skillType }})",
        )

        validate(skill, parameters)?.let { return it }
        precondition(skill)?.let { return it }

        val task = TaskRuntime(
            pinnedSkill = skill,
            pinnedMaxStringLength = capability.protocolLimits.maxStringLength,
            taskId = taskId,
            skillType = skillType,
            machine = TaskMachine(
                skill = skill,
                clock = clock,
                durationSeconds = durationOf(skillType),
                initialRevision = revision,
                initialParameters = parameters,
                taskId = taskId,
                listener = listener,
            ),
        )
        tasks[taskId] = task

        // **접수까지다. 실행 개시는 [tick]이 한다.**
        //
        // 계약에 "이제 실행하라"는 RPC가 따로 없어서 여기서 START까지 해
        // 버리기 쉬운데, 그러면 §4.4가 정의한 `ACCEPTED`가 표면에서 **도달
        // 불가능**해진다 — 어떤 RPC로도 그 상태의 태스크를 만날 수 없으므로
        // §4.4의 갱신 표에서 `ACCEPTED` 행이 영원히 시험되지 않는다(실측:
        // 상태 열 개를 도는 시험이 그 행을 만들지 못했다).
        //
        // `StartTask`는 접수 응답이지 "돌고 있다"가 아니다(§4.4).
        // §4.3의 UNTIL_NEW_TASK — 새 태스크가 접수되면 그 수명의 결함이
        // 사라진다. 부르는 곳이 없으면 그 수명이 영원이 된다.
        faults.onNewTask().forEach { listener.onFault(it, cleared = true) }

        record(task)
        // **접수도 전이다.** 안 알리면 접수만 되고 아직 tick을 안 받은
        // 태스크가 재구성에서 통째로 사라진다 — §4.4가 `ACCEPTED`를 도달
        // 가능하게 둔 이상 가정이 아니다.
        listener.onTaskTransition(taskId, skillType, null, TaskState.ACCEPTED, revision, 0)

        return StartOutcome.Accepted(task)
    }

    /** §4.4의 두 표 — 수신한 `revision` 4행과 갱신을 받은 상태 7행. */
    private fun update(
        task: TaskRuntime,
        revision: Int,
        skillType: String,
        parameters: List<ParameterValue>,
    ): StartOutcome {
        // **키가 먼저다.** 멱등 키는 `(task_id, revision)` 이고 스킬은 키가 아니라 실은 것이다 — 같은 revision 은 무엇을
        // 실었든 같은 핸들이고(파라미터를 안 덮는 것과 같은 이유) 낮은 revision 은 지난 것이다. 스킬 비교는 갱신이 실제로
        // 적용될 높은 revision 에서만 한다. 실물 호스트가 같은 차례이고, 앞에 두면 같은 재전송에 미믹은 거절하고 실물은
        // 핸들을 준다(§15.215).
        if (skillType != task.skillType && revision > task.machine.revision) {
            return StartOutcome.Rejected(
                RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
                "같은 task_id를 다른 스킬로 다시 보냈다: ${task.skillType} → $skillType",
            )
        }

        // **갱신이 실제로 적용될 때만 미리 검사한다.**
        //
        // 뒤에 검사하면 이미 파라미터가 교체된 뒤라 거절하면서도 상태를
        // 바꿔 놓게 된다. 앞에서 무조건 검사하면 낮은 revision이나 종착
        // 태스크에 대해 OUTDATED_REVISION·INVALID_TRANSITION 대신
        // PARAMETER_INVALID가 나가 소비자가 엉뚱한 것을 고친다.
        if (task.machine.willApply(revision)) {
            // **접수 시점의 규칙으로 본다**(§8.4의 pinning). 라이브 능력을
            // 보면 개정판이 좁아진 뒤 갱신이 자기 접수 시점에 유효했던 값으로
            // 거절된다.
            validate(task.pinnedSkill, parameters, task.pinnedMaxStringLength)
                ?.let { return it }
        }

        return when (val outcome = task.machine.update(revision, parameters)) {
            is UpdateOutcome.Idempotent -> StartOutcome.Idempotent(task)

            UpdateOutcome.Outdated -> StartOutcome.Rejected(
                RejectionCode.REJECTION_CODE_OUTDATED_REVISION,
                "이미 지난 revision이다: 받은 값=$revision, 현재=${task.machine.revision}",
            )

            UpdateOutcome.Rejected -> StartOutcome.Rejected(
                RejectionCode.REJECTION_CODE_INVALID_TRANSITION,
                "${task.machine.state} 에서는 갱신을 받지 않는다(§4.4)",
            )

            is UpdateOutcome.ParametersOnly, is UpdateOutcome.RestartedSkill -> {
                record(task)
                StartOutcome.Accepted(task)
            }
        }
    }

    /**
     * 프로파일이 선언한 사전 조건(설계안 §3) — 접수 전에, 엔진에 닿기 전에. 호스트와 같은 평가기다(§15.100).
     *
     * **미믹의 손은 한 쌍이다.** 로봇의 파지는 [handsHold] — **가장 최근에 기록된 쥐는 태스크**의 것이다. 도는
     * `pick_place` 가 `HOLDING` 이면 든 채고, 복구에 실패해 든 채로 끝났어도 그 뒤 다른 `pick_place` 가 놓고 끝났으면
     * 빈손이다. 첫 판은 «어느 태스크든 HOLDING 이면 든 채» 로 로그 전체를 훑어, 종착한 옛 태스크의 파지가 뒤의 빈손
     * 종착을 영원히 가렸다(리뷰 C1). 미믹은 언제나 관측한다: 못 보는 기종이 아니므로 `NOT_OBSERVABLE` 을 내지 않는다.
     */
    private fun precondition(skill: SkillDeclaration): StartOutcome.Rejected? {
        if (skill.preconditionsCount == 0) return null
        val violations = PreconditionCheck.check(skill, currentHold())
        if (violations.isEmpty()) return null
        return StartOutcome.Rejected(
            RejectionCode.REJECTION_CODE_PRECONDITION_UNMET,
            PreconditionCheck.rejectionDetail(violations),
            subjects = PreconditionCheck.subjects(violations),
        )
    }

    /** 가장 최근에 기록된 쥐는 태스크의 파지. 아무것도 쥔 적 없으면 빈손이다 — 미믹은 빈손으로 태어난다. */
    private var handsHold: HoldState = EMPTY_HANDS

    /**
     * 지금 이 기체의 손 — 계약 `GetSnapshotResponse.hold`(0.11.0)가 싣는 값이다. 접수 전 사전 조건이 보는 것([precondition])과 같은 값이라,
     * 소비자가 스냅샷으로 미리 본 손과 접수 때 이 엔진이 보는 손이 갈리지 않는다. 미믹은 언제나 관측한다.
     */
    fun hold(): HoldState = currentHold()

    private fun currentHold(): HoldState =
        // **지금 손을 쓰는** 쥐는 태스크(도는 중·멈춤·취소 중)가 든 채면 그것이 먼저다. 실패해 다시 시도를 기다리는 태스크(RETRIABLE·
        // NEEDS_INTERVENTION)는 비종착이어도 손을 쓰지 않는다 — 그 뒤 다른 pick_place 가 그 물건을 놓고 끝났으면 빈손이다(§15.318).
        // 앞 판은 비종착 전부를 먼저 봐, 실패한 옛 태스크의 HOLDING 이 뒤의 조치 종착을 영원히 가렸다(리뷰 C1 과 같은 꼴, 비종착 쪽).
        tasks.values.firstOrNull { it.machine.state in GRIPPING && it.hold.kind == HoldKind.HOLD_KIND_HOLDING }?.hold
            ?: handsHold

    private fun validate(
        skill: SkillDeclaration,
        parameters: List<ParameterValue>,
        maxStringLength: Int = capability.protocolLimits.maxStringLength,
    ): StartOutcome.Rejected? {
        val problems = ParameterCheck.check(skill, parameters, maxStringLength)
        if (problems.isEmpty()) return null
        return StartOutcome.Rejected(
            RejectionCode.REJECTION_CODE_PARAMETER_INVALID,
            // 어긴 것을 전부 싣는다. 하나만 알려주면 클라이언트가 고칠 때마다
            // 한 번씩 왕복한다.
            problems.joinToString("; ") { "${it.key}: ${it.reason}" },
            problems.map { it.key }.distinct(),
        )
    }

    /**
     * 시계가 흐른 만큼 태스크를 진전시킨다. **RPC 진입에서만 부른다** —
     * 배경 스레드를 두면 가상 시계와 충돌해 §12.1의 결정성이 깨진다.
     *
     * 하는 일은 넷이다 — **수명이 지난 결함을 거두고**(§4.3), **접수한
     * 태스크를 집어 들고**(`ACCEPTED` → `RUNNING`), **소요시간을 채운 태스크의
     * 결말을 뽑고**(`RUNNING` → 성공이면 `SUCCEEDED`, 실패면 §4.5 전파 규칙
     * 1이 보내는 곳), **취소의 복구를 마친다**(`CANCELLING` → `CANCELLED`).
     *
     * **전수 축은 상태 열이다.** 종착 여부만 보는 구현은 `CANCELLING`인
     * 태스크를 `SUCCEEDED`로 만든다 — `CANCELLING`은 종착이 아니고 스킬은
     * 복구를 수행하며 계속 돌기 때문이다(실측). §4.4의 취소 의미론과 완료
     * 기준 6의 "`CANCELLING` → 종착 순서"가 통째로 무너진다.
     */
    fun tick() {
        // **수명을 여기서만 거둔다.** 조회하면서 지우면 소멸 시점이 관측자에
        // 달리고 §12.1이 깨진다. 거둔 것마다 해소를 알린다 — 안 알리면
        // 이벤트를 접는 소비자가 지워진 결함을 영원히 든다.
        faults.expire().forEach { listener.onFault(it, cleared = true) }

        tasks.values.forEach { task ->
            when (task.machine.state) {
                // 접수한 태스크를 로봇이 집어 든다.
                TaskState.ACCEPTED -> {
                    task.machine.apply(TaskCommand.START)
                    record(task)
                }

                // **실패 추첨 지점은 여기 하나다.** 다른 데 두면 뽑는 횟수가
                // 관측 횟수에 달리고(모든 RPC가 tick을 부른다) 소비자가
                // 보는 것이 결과를 바꾼다 — §12.1이 깨지는 자리다.
                //
                // 같은 `Seeded`를 쓰는 인출이 하나 더 있다 — [durationOf]의
                // 지터다. 그쪽은 **태스크 생성 때 한 번**이므로 순서는
                // "태스크마다 지터 한 번, 완주할 때 실패 한 번"이다.
                TaskState.RUNNING -> if (task.machine.progress() >= 1.0) {
                    val failure = draw.drawFor(task.skillType, random)
                    if (failure == null) {
                        task.machine.onSkillComplete()
                    } else {
                        // **결함을 먼저 올리고 알린 뒤에 태스크를 보낸다.**
                        // 원인이 결과보다 먼저 나가야 이벤트를 접는 소비자가
                        // 원인 없는 실패를 보지 않는다.
                        val fault = FailureDraw.faultOf(failure, task.skillType, task.taskId)
                        faults.raise(fault)?.let { listener.onFault(it, cleared = false) }
                        halt(
                            task, FailureDraw.resolutionOf(failure.resolution),
                            payloadLost = failure.errorType == PAYLOAD_LOST,
                            fault = fault,
                        )
                    }
                    record(task)
                }

                // 복구를 마친다. **이것이 없으면 취소한 태스크가 영영
                // CANCELLING에 남는다**(실측: 열린 WatchTask 스트림이 닫히지
                // 않았다) — §4.4의 "복구까지 마치면 CANCELLED"에 도달하는
                // 경로가 아예 없었다. 그러면 §9.3의 드레인 판정이 영영 0이
                // 되지 않아 축소가 영구히 막힌다.
                //
                // 복구에 걸리는 시간은 프로파일이 선언하지 않으므로 다음
                // tick에 끝난다. 관측 순서는 그래도 CANCELLING → 종착이다.
                //
                // **여기서는 추첨하지 않는다 — 그것이 결정이다.** 선언된 실패
                // 모드는 **완주 판정에서만** 뽑는다. `rate`를 "이 스킬 실행당
                // 발생 확률"로 읽은 것이고, 복구는 그 실행이 아니라 그 실행을
                // 되돌리는 구간이다. §4.5의 대응표가 `CANCELLING` 중에도 스킬을
                // 돌게 두므로 자명하지 않아 여기 적는다. 복구 실패
                // (`CANCELLED_RECOVERY_FAILED`)는 §10.5의 `ForceFault`만
                // 만든다 — 완료 기준 8b가 그 경로를 시험한다.
                TaskState.CANCELLING -> {
                    task.machine.onRecoveryComplete()
                    record(task)
                }

                // 나머지 일곱은 시간이 흐른다고 저절로 변하지 않는다.
                // **`when`에 `else`를 쓰지 않는다** — 상태가 늘면 컴파일이
                // 깨져야지, 조용히 "아무 일 없음"으로 접히면 안 된다.
                TaskState.PAUSED, TaskState.RETRIABLE,
                TaskState.NEEDS_INTERVENTION, TaskState.SUCCEEDED, TaskState.FAILED,
                TaskState.CANCELLED, TaskState.CANCELLED_RECOVERY_FAILED,
                -> Unit
            }
        }
    }

    /**
     * §10.5의 `ForceFault`. 프로파일이 선언한 실패 모드 하나를 **지금** 세운다.
     *
     * **선언되지 않은 `error_type`은 거절한다.** §4.5 전파 규칙 1의 입력인
     * `resolution`이 프로파일에서만 오므로, 허용하면 태스크 종착 판정이
     * 미정의가 된다. 어댑터 전용 둘(`TERMINAL_STATE_VIOLATED`·
     * `CONTROL_AUTHORITY_LOST`)은 스키마의 enum에서 빠져 있어 프로파일에
     * 있을 수 없고, 따라서 이 문으로도 못 들어온다 — 전용 RPC가 따로 있다.
     *
     * **등급은 모드가 정하고, `taskId`는 어디로 전파할지를 정한다.** 스킬
     * 수준 모드(`skill_type`이 있다)는 `taskId`가 필수이며 그 태스크의 스킬이
     * 모드가 지목한 스킬이어야 한다 — 아니면 결함의 `skill_id`와 태스크의
     * 스킬이 어긋난 것이 나간다. 로봇 수준 모드는 `taskId`가 선택이다.
     *
     * **정착시키지 않는다.** 여기서 `tick()`을 부르면 `CANCELLING`이던
     * 태스크가 같은 호출 안에서 `CANCELLED`로 넘어가, 완료 기준 8b가 보려는
     * 복구 실패의 창이 닫힌다.
     */
    fun forceFault(errorType: String, taskId: String): ForceOutcome {
        val mode = document.failureModes.firstOrNull { it.errorType == errorType }
            ?: return ForceOutcome.Rejected(
                "프로파일이 선언하지 않은 error_type이다: $errorType " +
                    "(선언된 것: ${document.failureModes.map { it.errorType }})",
            )

        val task = when {
            taskId.isEmpty() && mode.skillType != null -> return ForceOutcome.Rejected(
                "스킬 수준 모드는 task_id가 필요하다: $errorType (${mode.skillType})",
            )
            taskId.isEmpty() -> null
            else -> tasks[taskId] ?: return ForceOutcome.NotFound(taskId)
        }

        if (task != null) {
            if (mode.skillType != null && mode.skillType != task.skillType) {
                return ForceOutcome.Rejected(
                    "모드가 지목한 스킬과 태스크의 스킬이 다르다: " +
                        "${mode.skillType} != ${task.skillType}",
                )
            }
            // **종착 넷은 래치되어 있고**(§4.4), `ACCEPTED`는 스킬 인스턴스가
            // 없어 §4.5의 대응표에 halt가 없다. `RETRIABLE`·
            // `NEEDS_INTERVENTION`도 스킬이 `READY`라 정지시킬 것이 없다.
            if (task.machine.state !in FAULTABLE) {
                return ForceOutcome.Rejected(
                    "${task.machine.state} 에서는 결함을 받지 않는다(받는 상태: $FAULTABLE)",
                )
            }
        }

        // **결함을 먼저 올리고 알린 뒤에 태스크를 보낸다** — tick()과 같은
        // 순서다. 원인이 결과보다 먼저 나가야 소비자가 원인 없는 실패를
        // 보지 않는다.
        val fault = FailureDraw.faultOf(mode, task?.skillType.orEmpty(), taskId)
        val raised = faults.raise(fault)?.also { listener.onFault(it, cleared = false) } != null

        if (task != null) {
            halt(task, FailureDraw.resolutionOf(mode.resolution), payloadLost = mode.errorType == PAYLOAD_LOST, fault = fault)
            record(task)
        }
        return ForceOutcome.Raised(raised, task?.machine?.state)
    }

    /**
     * 스킬을 정지시키고 **기체 수준 뒤처리까지 한다**(완료 기준 8b).
     *
     * **이 파일에서 `onSkillHalted`를 부르는 곳은 여기 하나다.** 뒤처리를
     * 호출자마다 두면 나중에 생기는 halt 경로가 결함 없이 복구 실패를
     * 만들고, 그때는 "동반한다"가 경로에 따라 참이 된다.
     *
     * ## 복구 실패의 결함은 엔진이 낸다
     *
     * §4.4가 *"후자는 거의 언제나 로봇 수준 결함을 동반하며, 그 결함이
     * `can_accept_new_task=false`를 든다"*고 했다. 그것을 밖에서 넣게 두면
     * 완료 기준 8b의 "동반한다"를 **시험이 자기가 넣은 것으로** 확인하게
     * 된다.
     *
     * **순서는 종착이 먼저, 결함이 나중이다.** `tick()`의 원인→결과와
     * 반대로 보이지만 아니다 — 복구 실패를 일으킨 원인은 이미 나간 그
     * 결함이고, 이것은 **복구가 실패했다는 사실의 결과**라 종착보다 먼저
     * 낼 수가 없다.
     */
    private fun halt(task: TaskRuntime, resolution: Resolution, payloadLost: Boolean = false, fault: Fault? = null) {
        // 놓친 것은 되돌아오지 않는다 — 재시도로 다시 집기 전까지는 빈손이다.
        if (payloadLost) task.payloadLost = true
        task.machine.onSkillHalted(resolution)
        // **실패 종착이면 원인을 태스크에 붙인다.** 그래야 `WatchTaskResponse.fault` 가
        // 정준 분류를 싣고 상류에 닿는다 — 결함 이벤트(기체 수준)와 별개의 경로다.
        if (task.machine.state in FAILURE_STATES) task.failure = fault
        if (task.machine.state != TaskState.CANCELLED_RECOVERY_FAILED) return
        faults.raise(recoveryFailed())?.let { listener.onFault(it, cleared = false) }
    }

    /**
     * §10.5의 `ForceControlAuthorityLoss`. §4.9의 제어 권한 상실.
     *
     * **`exclusive_control_required`가 거짓이면 거절한다.** 프로파일이
     * "이 로봇은 배타 제어 모델이 아니다"라고 선언했으면 빼앗길 권한이 없다.
     * 허용하면 프로파일이 부정한 상황을 에뮬레이터가 만들어, 거동이
     * 프로파일에서 온다는 §10.1이 깨진다.
     *
     * **진행 중이던 태스크는 §4.5 전파 규칙 1을 탄다.** `Resolution.TERMINAL`
     * 이므로 `FAILED`로 종착한다 — 다만 `CANCELLING`이던 것은 전파 규칙 3이
     * 이겨 `CANCELLED_RECOVERY_FAILED`가 된다(취소는 이미 결정된 것이고 남은
     * 질문은 되돌리는 데 성공했는가뿐이다).
     *
     * **`CONTROL_AUTHORITY_LOST`는 열한 번째 `TaskState`가 아니다.**
     * `error_type`이며, 8d의 "로봇 고장과 구분된다"는 상태가 아니라 **결함이**
     * 하는 일이다. 상태를 늘리면 §4.4의 종착 넷 위에 선 것들(pinning·갱신
     * 규칙·드레인 판정)이 전부 흔들린다.
     *
     * **진행 중 태스크가 없어도 결함은 선다.** 권한을 잃은 것은 기체이지
     * 태스크가 아니다 — 태스크에만 붙이면 유휴 상태에서 빼앗긴 것을 소비자가
     * 영영 모른다.
     */
    fun forceControlAuthorityLoss(): AuthorityOutcome {
        if (!document.exclusiveControlRequired) {
            return AuthorityOutcome.Rejected(
                "배타 제어 모델이 아니라 빼앗길 권한이 없다 " +
                    "(exclusive_control_required=false)",
            )
        }

        val fault = controlAuthorityLost()
        val raised = faults.raise(fault)?.also { listener.onFault(it, cleared = false) } != null

        // **결함을 먼저 알린 뒤에 태스크를 보낸다** — 원인이 결과보다 먼저
        // 나가야 소비자가 원인 없는 실패를 보지 않는다.
        val terminated = tasks.values
            .filter { it.machine.state in FAULTABLE }
            .onEach {
                halt(it, Resolution.TERMINAL, fault = fault)
                record(it)
            }
            .map { it.taskId }

        return AuthorityOutcome.Lost(raised, terminated)
    }

    /**
     * §10.5의 `ForceTerminalViolation`. §4.4의 래치 위반을 관측한다.
     *
     * **태스크를 건드리지 않는다.** 종착은 래치되며 그것이 계약의
     * 불변식이다 — 여기서 상태를 되돌리면 pinning·갱신 규칙·`RETRIABLE`
     * 구분이 전부 무너진다. 하는 일은 둘뿐이다: 결함을 발행하고, 관측했다는
     * 사실을 태스크에 적는다.
     *
     * **흡수했으면 흡수가 실패했다는 사실을 숨기지 않는다**(§4.4). 계약의
     * 단순함은 지키되, 계약이 실물과 어긋나 있다는 사실은 관측 가능하게
     * 만든다.
     */
    fun forceTerminalViolation(taskId: String): ViolationOutcome {
        val task = tasks[taskId] ?: return ViolationOutcome.NotFound(taskId)

        // **종착이 아니면 위반이 아니다.** 비종착에서 받으면 "래치가 깨졌다"가
        // 아무 뜻도 안 갖는다 — 아직 래치되지 않았기 때문이다.
        if (!task.machine.state.isTerminal) {
            return ViolationOutcome.Rejected(
                "종료가 아니라 래치 위반이 성립하지 않는다: ${task.machine.state}",
            )
        }

        val before = task.machine.state
        task.markTerminalViolation()
        val raised = faults.raise(terminalViolated(task.taskId))
            ?.also { listener.onFault(it, cleared = false) } != null

        return ViolationOutcome.Seen(before, raised)
    }

    /** 현재 상태를 로그에 한 줄 적는다. 잔여 물리 상태도 이때 정한다. */
    fun record(task: TaskRuntime): TaskUpdate {
        task.hold = holdOf(task)
        // 손은 한 쌍이다 — 쥐는 스킬의 기록만이 로봇의 파지를 바꾼다. 참조만 하는 태스크가 끝났다고 든 것이 놓이지 않는다.
        // 접수(ACCEPTED)는 손을 움직이지 않는다 — 그 기록의 빈손이 앞서 든 손을 덮으면 든 채인데 빈손으로 읽힌다.
        if (ObjectReferences.grasps(task.skillType) && task.machine.state != TaskState.ACCEPTED) handsHold = task.hold
        return task.log.record(
            state = task.machine.state,
            revision = task.machine.revision,
            attempt = task.machine.attempt,
            progress = task.machine.progress(),
            occurredAt = clock.now(),
            hold = task.hold,
            fault = task.failure.takeIf { task.machine.state in FAILURE_STATES },
        )
    }

    /**
     * §4.4의 잔여 물리 상태 — 미믹의 규칙.
     *
     * **대상을 쥐는 스킬만 든다.** 쥐는지는 계약이 `grasps_object`로, 든 것의
     * 이름은 `is_object_reference` 파라미터로 말한다([ObjectReferences]).
     * 프로파일이나 스킬 이름을 여기서 보지 않는다 — 보면 미믹이 스킬 어휘를
     * 알게 된다. **참조와 쥠은 다르다** — `inspect(target)`는 대상의 이름을
     * 받지만 빈손이다. 앞 판이 그 둘을 접었고 시나리오 ③이 그것을 잡았다(§15.87).
     *
     * 단순화 하나를 적어 둔다: 도는 동안 **내내** 든 것으로 친다. 실제 기체는
     * 대상까지 걸어가는 구간이 있지만 프로파일이 그 구간을 선언하지 않고,
     * 우리가 정하면 그것이 관측처럼 보인다. 불변식(`CANCELLED` ⇒ 빈손,
     * `CANCELLED_RECOVERY_FAILED` ⇒ 든 채 — 놓친 경우만 빼고)은 이 단순화
     * 아래에서도 성립하며, 그것이 소비자가 기대는 전부다.
     *
     * `RUNNING` 갱신(§4.4의 `Halt → Reset → Start`)도 든 채로 지난다 — 그것이
     * 맞는지는 §15.84 후보 ③으로 열려 있다.
     */
    private fun holdOf(task: TaskRuntime): HoldState {
        if (!ObjectReferences.grasps(task.skillType) || task.payloadLost) return EMPTY_HANDS
        val keys = ObjectReferences.keysOf(task.skillType)

        val ref = task.machine.parameters.firstOrNull { it.key in keys }?.stringValue.orEmpty()
        val holding = HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_HOLDING).setObjectRef(ref).build()

        // **`when`에 `else`를 쓰지 않는다** — 상태가 늘면 여기서 컴파일이 깨져야 한다.
        return when (task.machine.state) {
            TaskState.ACCEPTED, TaskState.SUCCEEDED, TaskState.CANCELLED -> EMPTY_HANDS
            TaskState.RUNNING, TaskState.PAUSED, TaskState.CANCELLING,
            TaskState.CANCELLED_RECOVERY_FAILED -> holding
            // 실패는 파지를 바꾸지 않는다 — 들고 있었으면 든 채다.
            TaskState.FAILED, TaskState.RETRIABLE, TaskState.NEEDS_INTERVENTION ->
                if (task.hold.kind == HoldKind.HOLD_KIND_HOLDING) holding else EMPTY_HANDS
        }
    }

    /**
     * 이 태스크가 얼마나 걸릴지. §10.4 ②의 소요시간 지터가 여기서 붙는다.
     *
     * **태스크 생성 때 한 번만 뽑는다** — [start]가 새 기체를 세울 때만
     * 부르기 때문이다. 진행률을 물을 때마다 뽑으면 **관측이 소요시간을
     * 바꾸고**(§12.1), 갱신마다 뽑으면 같은 revision을 다시 보내는 멱등
     * 재전송이 소요시간을 바꾼다(§4.4가 그것을 같은 핸들이라고 못박았다).
     * 재시도로 스킬이 다시 서도 다시 뽑지 않는다 — 프로파일은 **스킬당**
     * 소요시간을 선언하지 시도당 소요시간을 선언하지 않는다.
     *
     * `jitter_ratio`가 0이면 [Seeded.jitter]가 난수를 건드리지 않는다.
     * 인출 수가 프로파일에 달리는 것은 괜찮다 — 프로파일이 입력이다.
     * 안 되는 것은 인출 수가 **관측**에 달리는 것이다.
     */
    private companion object {

        /**
         * §4.9의 제어 권한 상실 결함.
         *
         * **로봇 수준이다** — `references`가 비어 있다. 권한은 기체의
         * 속성이지 스킬이나 태스크의 것이 아니다. 어느 태스크가 죽었는지는
         * 그 태스크들의 전이가 말한다.
         */
        fun controlAuthorityLost(): Fault = Fault.newBuilder()
            .setErrorType("CONTROL_AUTHORITY_LOST")
            .setFailureClass(FailureClass.FAILURE_CLASS_CONTROL_AUTHORITY_LOST)
            .setCanContinueCurrentTask(false)
            .setCanAcceptNewTask(false)
            .setErrorHint(
                "다른 클라이언트가 제어 권한을 가져갔다. 미션 계층에서 권한을 되찾은 뒤 " +
                    "결함을 해소하십시오.",
            )
            .setActiveUntil(Lifetime.newBuilder().setKind(Lifetime.Kind.KIND_UNTIL_CLEARED))
            .build()

        /**
         * §4.4의 래치 위반 결함.
         *
         * **`skill_id`를 안 단다.** 스킬은 멀쩡하다 — 깨진 것은 "종착하면
         * 끝"이라는 계약의 가정이다. `skill_id`가 실리면 소비자가 "그 스킬만
         * 못 쓴다"로 읽는데(§4.6이 표현하라고 만든 바로 그 구분), 사실은
         * 로봇이 계약과 어긋나게 움직이고 있는 것이므로 로봇 수준이다.
         * **`task_id`는 단다** — 어느 태스크에 대한 관측인지는 알아야 한다.
         */
        fun terminalViolated(taskId: String): Fault = Fault.newBuilder()
            .setErrorType("TERMINAL_STATE_VIOLATED")
            .setCanContinueCurrentTask(false)
            .setCanAcceptNewTask(false)
            .setErrorHint(
                "종료한 태스크를 로봇이 계속 수행 중이다. 계약과 실제 하드웨어가 어긋나 있으니 " +
                    "현장에서 로봇을 정지시키고 어댑터의 래치 처리를 확인하십시오.",
            )
            .addReferences(
                Reference.newBuilder().setKey(Reference.Key.KEY_TASK_ID).setValue(taskId),
            )
            .setActiveUntil(Lifetime.newBuilder().setKind(Lifetime.Kind.KIND_UNTIL_CLEARED))
            .build()

        /**
         * 복구 실패가 동반하는 **로봇 수준** 결함(§4.4).
         *
         * | 필드 | 값 | 왜 |
         * |---|---|---|
         * | `error_type` | `INTERNAL_ERROR` | 코어 여섯 중 "그 밖"이다. 벤더 접두사를 붙이면 기종마다 달라져 소비자가 분기한다 |
         * | 두 불리언 | `false`/`false` | "로봇이 물건을 든 채 멈춰 있다"가 §4.4가 `CANCELLED`와 이것을 나눈 이유다 |
         * | `references` | 없음 | 되돌리기에 실패한 것은 **기체**이지 스킬이 아니다 |
         * | `active_until` | `UNTIL_CLEARED` | `UNTIL_NEW_TASK`는 틀렸다 — 새 태스크를 받는다고 로봇이 물건을 내려놓지 않는다 |
         *
         * **막지는 않는다.** `can_accept_new_task=false`인데 새 태스크가 오면
         * 엔진은 그대로 받는다 — §4.6이 "판단은 밖으로, 사실은 안으로"라
         * 했으므로 그것은 소비자의 판단이고, 막으면 계약이 정책을 갖는다.
         */
        fun recoveryFailed(): Fault = Fault.newBuilder()
            .setErrorType("INTERNAL_ERROR")
            .setCanContinueCurrentTask(false)
            .setCanAcceptNewTask(false)
            .setErrorHint(
                "취소한 태스크의 되돌리기에 실패했다. 로봇이 대상을 든 채 멈춰 있을 수 " +
                    "있으니 현장에서 상태를 확인하고 결함을 해소하십시오.",
            )
            .setActiveUntil(Lifetime.newBuilder().setKind(Lifetime.Kind.KIND_UNTIL_CLEARED))
            .build()

        /**
         * `ForceFault`를 받는 태스크 상태(§4.5의 대응표).
         *
         * **열 중 셋이다.** 종착 넷은 래치되어 있고, `ACCEPTED`는 스킬
         * 인스턴스가 아직 없으며, `RETRIABLE`·`NEEDS_INTERVENTION`은 스킬이
         * `READY`라 정지시킬 것이 없다.
         */
        /** 실패 종착 셋 — `WatchTaskResponse.fault` 가 채워지는 상태. 계약 주석 *"종착이 실패인 경우에만"*. */
        private val FAILURE_STATES = setOf(TaskState.FAILED, TaskState.RETRIABLE, TaskState.NEEDS_INTERVENTION)

        val FAULTABLE = setOf(TaskState.RUNNING, TaskState.PAUSED, TaskState.CANCELLING)

        /** §4.6의 코어 결함 — 들고 있던 것을 놓쳤다. 잔여 물리 상태가 이것만 본다. */
        const val PAYLOAD_LOST = "PAYLOAD_LOST"

        val EMPTY_HANDS: HoldState = HoldState.newBuilder().setKind(HoldKind.HOLD_KIND_EMPTY).build()

        /** 손을 지금 쓰는 상태. [currentHold] 가 [handsHold] 보다 먼저 본다. */
        val GRIPPING = setOf(TaskState.RUNNING, TaskState.PAUSED, TaskState.CANCELLING)
    }

    private fun durationOf(skillType: String): Double {
        val entry = document.durations.firstOrNull { it.skillType == skillType }
        // 프로파일이 소요시간을 선언하지 않은 스킬은 즉시 끝나는 것으로
        // 두지 않는다 — 그러면 진행률이 관측 불가능해진다.
            ?: error("프로파일이 소요시간을 선언하지 않았다: $skillType")
        return random.jitter(entry.seconds, entry.jitterRatio)
    }
}

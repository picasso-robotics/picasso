package dev.picasso.middleware

import dev.picasso.capability.Remedy
import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.v1.HoldState
import dev.picasso.middleware.Middleware.Submission
import java.time.Instant

/**
 * 제안과 승인의 장부(설계안 §6.4, ADR 43·44·45) — 운영자·설명층 접합부.
 *
 * **장부와 판정만 든다.** 승인이 서면 실제로 내리는 것(접수)은 [Middleware] 가 하고, 승인의 기록([settle])은
 * 접수가 된 뒤에만 한다. 관문이 거절하면 [settle] 을 안 부르므로 서 있던 제안을 지우지 않는다. 관문의 거절은
 * 접수가 [record] 에 넘기고, 조치 열·못 찾은 사유·출발 결품 판정이 실려 있으면 여기 적힌다. 승인 시점의 거절은
 * 서 있던 제안을 바꾸지도 않는다([record] 의 `approval`).
 */
internal class RemedyDesk(
    private val robots: RobotPort,
    private val entitlements: Entitlements,
    private val withholdEvery: Int,
    private val now: () -> Instant,
    private val wallClock: () -> Instant,
    /** 기체의 지금 손([AdmissionGate.fillHold] — 도는 단위의 관측, 없으면 신선한 스냅숏). 자동 승인의 값을 채울 때 다시 본다. */
    private val liveHold: (String) -> HoldState?,
    /** 보존 상한(§15.166). */
    private val retention: Retention = Retention(),
    /** 이 실행을 아직 구동이 돌리는가. 그 실행의 소모 기록은 상한을 넘어도 내리지 않는다. */
    private val live: (String) -> Boolean = { false },
    /** 도는 단위 관측이 없을 때 그 기체를 쥔 다른 실행([AdmissionGate.snapshotHolder]). 있으면 스냅숏 근거의 거절에 제안을 세우지 않는다. */
    private val snapshotHolder: (String) -> String? = { null },
) {

    private var searchesEvicted = 0L
    private var proposalsEvicted = 0L
    private var consumedEvicted = 0L

    /** 상한으로 내린 탐색 기록·제안·소모 기록의 수. 번들 수는 [IncidentLog] 가 센다. */
    fun evictions(incidents: Long, executions: Long, forgottenOrders: Long) =
        RetentionEvictions(incidents, searchesEvicted, proposalsEvicted, consumedEvicted, executions, forgottenOrders)

    /**
     * 서 있는 제안 — (기체, 주문) 하나에 하나. **승인은 이 표에 있는 것만 받는다.** 없는 제안을 승인으로
     * 지어내면 «승인 없이 실행되는 경로» 가 그 자리에서 생긴다.
     *
     * **주문을 함께 든다**(ADR 44). 승인하는 쪽이 주문을 들고 오면 그것을 **지어낼** 수 있고, 그 순간
     * 승인 표면이 일반 접수 표면이 된다 — 밖에서 부를 길이 생기고 나서야 보이는 구멍이다.
     */
    private val proposals = mutableMapOf<String, Proposal>()

    /** 서 있는 제안 하나 — 무엇에 대한 제안인지까지. */
    private data class Proposal(val order: JobOrder, val remedy: Remedy.Found)

    /** 가려 둔 제안의 열쇠 — 사람이 먼저 진단해야 풀린다. */
    private val withheld = mutableSetOf<String>()

    /** 사람이 먼저 적은 진단. 가려 둔 제안을 푸는 값이고, 나중에 자동 진단과 대조할 재료다. */
    private val diagnoses = mutableMapOf<String, String>()

    /** 지금까지 낸 제안의 수 — 몇 번째를 가릴지 세는 데 쓴다. 결정적이다. */
    private var proposalsMade = 0

    /** 같은 조치가 승인된 횟수 — (기체, 걸음 열)마다. */
    private val approvals = mutableMapOf<String, Int>()

    /**
     * 소모된 제안의 기록 — (기체, 주문) 하나에 마지막 하나(ADR 46). [settle] 만 쓰고 [judge] 는 **서 있는 제안이
     * 없을 때만** 읽는다. 먼저 읽으면 소모 뒤 같은 열쇠에 선 새 제안이 영영 승인되지 않는다.
     */
    private val consumed = mutableMapOf<String, ConsumedApproval>()

    /** 탐색이 답한 것들(설계안 §6.3). **조회만 한다** — 이 대장이 이 층의 거동을 바꾸지 않는다. */
    private val remedyLog = mutableListOf<RemedySearchRecord>()
    private var remedySeq = 0

    /**
     * 관문이 낸 거절을 **기록으로 만든다** — 제안을 남기고, 가릴 차례면 가린다.
     *
     * 관문에서 뗀 이유는 이것이 부작용이기 때문이다. 후보를 물어보는 것과 그 기체에 내려다 막힌 것은
     * 다른 일이고, 앞엣것에 기록이 붙으면 «물어봤다» 가 «시도했다» 로 쌓인다.
     */
    fun record(
        robotId: String,
        order: JobOrder,
        rejection: Submission.Rejected,
        sourceMissing: RemedyOutcome.SourceMissing? = null,
        /** 이미 승인을 든 실행의 개정(`revise`) 거절인가. 그러면 조치 열을 찾아도 제안을 세우지 않는다(ADR 46, §15.175). */
        carriesApproval: Boolean = false,
        /** 승인한 조치 열을 붙인 접수의 거절인가. 그러면 조치 열을 찾아도 서 있는 제안을 바꾸지 않는다. */
        approval: Boolean = false,
    ): Submission.Rejected {
        val jobOrderId = order.jobOrderId
        val remedy = rejection.remedy

        // **점유 관문의 답도 답이다.** 여기서 버리면 밖에서 «다른 자리가 있다» 와 «아무것도 계산 안 했다» 가
        // 같은 침묵이 된다 — 탐색의 «못 찾았다» 를 버리고 있던 것과 같은 자리다(§15.164).
        // 관문이 첫 거절에서 되돌아가므로 이것과 아래의 탐색 결과가 함께 서는 일은 없다.
        if (sourceMissing != null) {
            note(robotId, jobOrderId, sourceMissing)
            return rejection
        }

        // **못 찾은 것도 답이다.** 여기서 버리면 밖에서 «이 기체로는 안 된다» 와 «아예 안 찾아봤다» 가
        // 같은 침묵이 된다. 계산은 이미 끝났고 남는 일은 옮겨 싣는 것뿐이다(ADR 40).
        if (remedy is Remedy.None) {
            note(robotId, jobOrderId, RemedyOutcome.None(remedy.cause, remedy.unmet))
            return rejection
        }
        if (remedy !is Remedy.Found || remedy.steps.isEmpty()) return rejection

        // **다른 실행이 쥔 기체에는 제안을 세우지 않는다**(§15.316). 거절의 근거가 기체 스냅숏의 손이고 그 손에 든 것이 그 실행의 일일 수
        // 있다 — 세우면 이 작업 지시의 조치가 남의 물건을 처분한다. 소유를 가리지 않고 «사람의 판단이 열린 실행이 있다» 만 읽는다. 찾은 것은 적는다.
        snapshotHolder(robotId)?.let { holder ->
            note(robotId, jobOrderId, RemedyOutcome.Found(remedy.steps))
            return rejection.copy(reason = "${rejection.reason} — 그 기체를 다른 실행 $holder 가 쥐고 있어 조치 제안을 세우지 않았다", remedy = null)
        }

        // **이미 승인을 든 실행의 개정 거절은 제안을 세우지 않는다**(ADR 46, §15.175). 판단 기록이 승인 하나만 실으므로 세워도 승인할
        // 수 없고, 세우면 그 열쇠에 남은 소모 기록을 가린다. 탐색의 답은 그대로 적는다 — 찾은 것도 답이다.
        if (carriesApproval) {
            note(robotId, jobOrderId, RemedyOutcome.Found(remedy.steps))
            return rejection.copy(
                reason = "${rejection.reason} — 이미 승인한 조치 열을 든 실행이라 제안을 세우지 않았다",
                remedy = null,
            )
        }

        // **승인 시점의 거절은 서 있는 제안을 바꾸지 않는다.** 조치 열을 붙인 계획이 사슬 검사에 걸리면 탐색은 그 조치 열의 걸음을 위한
        // 조치 열을 찾는다. 그것을 세우면 사람이 본 적 없는 조치 열이 승인을 기다리고 본 제안은 사라져, 조건이 풀려도 같은 제안이 못 선다.
        // 가림 차례도 세지 않는다. 탐색의 답은 적는다.
        if (approval) {
            note(robotId, jobOrderId, RemedyOutcome.Found(remedy.steps))
            return rejection.copy(
                reason = "${rejection.reason} — 승인 시점의 거부라 활성 제안을 바꾸지 않았다",
                remedy = null,
            )
        }

        val key = proposalKey(robotId, jobOrderId)
        proposals[key] = Proposal(order, remedy)
        trimProposals(key)
        proposalsMade += 1
        // **가릴 차례인가.** 이미 사람이 진단을 적어 둔 건은 다시 가리지 않는다 — 같은 값을
        // 두 번 요구하면 그것은 학습이 아니라 절차다.
        val hide = withholdEvery > 0 && proposalsMade % withholdEvery == 0 && key !in diagnoses
        if (!hide) {
            note(robotId, jobOrderId, RemedyOutcome.Found(remedy.steps))
            return rejection
        }
        withheld += key
        // **걸음은 안 싣는다.** 대장이 가린 것의 내용을 내면 조회 한 번으로 가림이 풀린다.
        note(robotId, jobOrderId, RemedyOutcome.Withheld)
        return Submission.Rejected(rejection.reason, remedy = null, remedyWithheld = true)
    }

    /**
     * 탐색 한 번을 대장에 적는다.
     *
     * **묻기만 한 것은 안 적는다.** 부르는 자리가 [record] 뿐인 것이 그 규율이다 — 관문([AdmissionGate.admits])은 순수
     * 술어라 후보 셋에 물어봐도 아무것도 안 쌓이고, 쌓는 것은 실제로 그 기체에 내려 본 쪽이다(§15.161). [Middleware.submit] 과
     * 순위 목록을 끝까지 시도해 전부 떨어진 [Middleware.adopt] 가 그쪽이다(§15.164).
     * 그래서 이 대장은 «무엇을 물어봤나» 가 아니라 **«무엇을 시도했고 무엇을 답받았나»** 다.
     */
    private fun note(robotId: String, jobOrderId: String, outcome: RemedyOutcome) {
        remedyLog += RemedySearchRecord(
            searchId = "search-${++remedySeq}",
            robotId = robotId,
            jobOrderId = jobOrderId,
            at = now(),
            wallClockAt = wallClock(),
            outcome = outcome,
        )
        // 탐색 기록은 조회만 하는 이력이라 오래된 것부터 그냥 내린다.
        while (remedyLog.size > retention.remedySearches) {
            remedyLog.removeAt(0)
            searchesEvicted += 1
        }
    }

    /**
     * 제안이 상한을 넘으면 **가장 오래 선 것부터** 내린다 — 방금 세운 [keep] 은 빼고. 내린 제안의 가림과 진단도 같이 내린다.
     * 내린 제안을 승인하면 «제안이 없다» 로 거부된다.
     */
    private fun trimProposals(keep: String) {
        val over = proposals.size - retention.proposals
        if (over <= 0) return
        proposals.keys.filter { it != keep }.take(over).forEach { key ->
            proposals.remove(key)
            withheld.remove(key)
            diagnoses.remove(key)
            proposalsEvicted += 1
        }
    }

    /** 소모 기록을 적고 상한을 넘으면 오래된 것부터 내린다. 구동이 돌리는 실행의 기록은 건너뛴다([Retention]). */
    private fun rememberConsumed(key: String, record: ConsumedApproval) {
        consumed[key] = record
        var over = consumed.size - retention.consumedApprovals
        if (over <= 0) return
        val drop = consumed.entries.filter { (k, c) -> k != key && !live(c.executionId) }.map { it.key }
        for (k in drop) {
            if (over <= 0) break
            consumed.remove(k)
            if (k !in proposals) diagnoses.remove(k)
            consumedEvicted += 1
            over -= 1
        }
    }

    /**
     * 지금까지의 탐색 결과 전부, **답한 순서대로.**
     *
     * 순서는 이 층이 보증한다 — 읽는 쪽이 `searchId` 를 뜯어 번호를 꺼내면 그 순간 형식에 묶이고,
     * 그 형식을 대는 시험은 어디에도 없다.
     */
    fun remedySearches(): List<RemedySearchRecord> = remedyLog.toList()

    private fun proposalKey(robotId: String, jobOrderId: String) = "$robotId|$jobOrderId"

    /** 이 (기체, 주문)에 서 있는 제안. 없거나 **가려져 있으면** 널. */
    fun proposal(robotId: String, jobOrderId: String): Remedy.Found? {
        val key = proposalKey(robotId, jobOrderId)
        if (key in withheld) return null
        return proposals[key]?.remedy
    }

    /**
     * 가려 둔 제안이 있는가(설계안 §7.2 넷째). 조회가 널을 내는 이유가 «없다» 인지 «가렸다» 인지를 가른다.
     */
    fun withheldProposal(robotId: String, jobOrderId: String): Boolean = proposalKey(robotId, jobOrderId) in withheld

    /**
     * **사람이 먼저 진단한다.** 가려 둔 제안은 이것을 적어야 풀린다 — 제안을 보고 나서 적으면 그것은
     * 진단이 아니라 동의다. 적은 값은 나중에 자동 진단과 대조할 재료로 남는다.
     *
     * @return 가려 둔 제안이 실제로 있었는가. 없으면 적히지 않는다 — 없는 사건에 진단을 남기지 않는다.
     */
    fun diagnose(robotId: String, jobOrderId: String, cause: String): Boolean {
        val key = proposalKey(robotId, jobOrderId)
        if (key !in withheld) return false
        diagnoses[key] = cause
        withheld.remove(key)
        return true
    }

    /** 사람이 먼저 적은 진단. 안 적었으면 널. */
    fun diagnosis(robotId: String, jobOrderId: String): String? = diagnoses[proposalKey(robotId, jobOrderId)]

    /**
     * 같은 조치가 거듭 승인된 것(설계안 §7.3) — **반복되는 임시 조치는 미해결 근본 원인의 지표다.**
     *
     * 임시 대안이 매끄럽게 작동할수록 근본 원인을 고칠 압력이 사라진다. 그리퍼를 교체해야 하는데 우회
     * 경로가 매번 잘 돌아가면 아무도 교체하지 않는다. 그래서 시스템이 그것을 스스로 고발한다.
     */
    fun repeatedRemedies(atLeast: Int): List<RepeatedRemedy> = approvals
        .filterValues { it >= atLeast }
        .map { (key, count) ->
            val (robotId, steps) = key.split("|", limit = 2)
            RepeatedRemedy(robotId, steps.split(">").filter { it.isNotEmpty() }, count)
        }
        .sortedByDescending { it.approvals }

    /** 승인 시도의 판정. 두 문이 같은 판정을 쓰고 **모양만 다르게 낸다.** */
    sealed interface Judgment {
        data class Go(
            val key: String,
            val robotId: String,
            val order: JobOrder,
            val steps: List<RemedyStep>,
            val prefix: List<ExecutionUnit>,
            /** 누른 쪽. 접수가 되면 실행과 소모 기록이 이것을 든다. */
            val approver: Approver,
        ) : Judgment

        /**
         * 거절. [consumed] 는 [ApprovalRefusal.CONSUMED] 일 때만 있고, 그때는 반드시 있다 —
         * [ApprovalOutcome.Refused] 와 같은 짝 검사다. 사람의 문은 이것을 바로 산문으로 옮기므로 그 길에서는
         * 여기가 유일한 방벽이다.
         */
        data class No(
            val refusal: ApprovalRefusal,
            val reason: String,
            val consumed: ConsumedApproval? = null,
        ) : Judgment {
            init {
                require((refusal == ApprovalRefusal.CONSUMED) == (consumed != null)) {
                    "소모 기록은 CONSUMED 판정에만, 그리고 반드시 실린다: $refusal · ${consumed != null}"
                }
            }
        }
    }

    /**
     * 이 시도가 서는가 — 그리고 서면 무엇이 나가는가.
     *
     * @param given 사람이 그 자리에서 준 값. 널이면 **선언과 관측**에서 채운다([RemedyValues]).
     * @param saw 부르는 쪽이 본 조치 열. 널이면 대조하지 않는다 — 프로세스 안에서 제안을 방금 읽은 쪽이다.
     */
    fun judge(
        robotId: String,
        jobOrderId: String,
        approver: Approver,
        given: List<Map<String, String>>?,
        saw: List<String>?,
    ): Judgment {
        val key = proposalKey(robotId, jobOrderId)
        // **가림이 맨 앞이다.** 가려 둔 것은 자격이 있어도 값이 맞아도 안 눌린다. 뒤로 물리면 자격 없는
        // 시도가 «자격 없음» 을 받고, 가림이 있었다는 사실이 답에서 사라진다.
        if (key in withheld) {
            return Judgment.No(ApprovalRefusal.WITHHELD, "가려 둔 제안이다 — 사람이 먼저 진단해야 한다: $key")
        }
        // **서 있는 제안이 없으면 왜 없는지를 가른다**(ADR 46). 소모된 것과 선 적이 없는 것은 다음 행동이 다르다.
        val standing = proposals[key]
            ?: return consumed[key]?.let { c ->
                Judgment.No(
                    ApprovalRefusal.CONSUMED,
                    "이미 소모된 제안이다: ${c.approver.id}(${c.approver.kind}) · ${c.executionId} · ${c.at}: $key",
                    c,
                )
            }
            ?: Judgment.No(
                ApprovalRefusal.NO_PROPOSAL,
                if (proposalsEvicted + consumedEvicted == 0L) {
                    "승인할 제안이 없다 — 이 프로세스가 뜬 뒤로 선 적이 없다: $key"
                } else {
                    // 상한으로 내린 기록이 있으면 «선 적이 없다» 고 단정하지 못한다(§15.166).
                    "승인할 제안이 없다 — 선 적이 없거나 보존 상한으로 내린 기록이다: $key"
                },
            )
        val steps = standing.remedy.steps

        // 읽은 뒤 제안이 바뀌었으면 부르는 쪽은 **자기가 못 본 것**을 승인하는 중이다.
        val now = steps.map { it.skillType }
        if (saw != null && saw != now) {
            return Judgment.No(ApprovalRefusal.PROPOSAL_CHANGED, "본 조치 열과 지금 제안이 다르다: $saw != $now")
        }

        val declared = robots.capabilities(robotId)?.skillsList?.associateBy { it.skillType }
            ?: return Judgment.No(
                ApprovalRefusal.CAPABILITY_UNKNOWN,
                "케이퍼빌리티를 못 물어봤다 — 조치가 실행 가능한지 확인할 수 없다",
            )

        val parameters: List<Map<String, String>>
        if (given != null) {
            if (given.size != steps.size) {
                return Judgment.No(
                    ApprovalRefusal.VALUE_NOT_DECLARED,
                    "단계 수와 파라미터 수가 다르다: ${steps.size} != ${given.size}",
                )
            }
            steps.forEachIndexed { at, step ->
                val missing = declared[step.skillType]?.parametersList.orEmpty()
                    .filterNot { it.optional }.map { it.key }.filterNot { it in given[at] }
                if (missing.isNotEmpty()) {
                    return Judgment.No(
                        ApprovalRefusal.VALUE_NOT_DECLARED,
                        "조치 ${at + 1}(${step.skillType}) 에 필요한 파라미터가 없다: $missing",
                    )
                }
            }
            parameters = given
        } else {
            val entitlement = entitlements.declaredFor(approver.id)
                ?: return Judgment.No(
                    ApprovalRefusal.NOT_DECLARED,
                    "자동 승인 자격이 선언돼 있지 않다: ${approver.id}",
                )
            scopeRefusal(entitlement, robotId, steps)?.let { return it }
            // **관측은 지금 다시 본다.** 제안이 설 때의 파지를 들고 있으면 그 사이 기체가 놓았거나
            // 다른 것을 들었을 때 낡은 사실로 승인하게 된다.
            parameters = when (val filled = RemedyValues.resolve(steps, declared, entitlement, liveHold(robotId))) {
                is RemedyValues.Resolution.Refused -> return Judgment.No(filled.refusal, filled.reason)
                is RemedyValues.Resolution.Filled -> filled.parameters
            }
        }

        val prefix = steps.mapIndexed { at, step -> remedyUnit("remedy-${at + 1}-${step.skillType}", step.skillType, parameters[at]) }
        return Judgment.Go(key, robotId, standing.order, steps, prefix, approver)
    }

    /**
     * 선언이 좁히는 네 축(ADR 43·45).
     *
     * 거절 사유를 가르는 이유는 다음 행동이 다 다르기 때문이다 — 사후 검토로 가거나, 갱신하거나,
     * 범위를 넓히거나, 사람이 누르거나.
     *
     * ★**철회가 맨 앞이다.** 철회된 선언이 만료까지 지났을 때 `EXPIRED` 를 내면, 받은 쪽은 갱신하면
     * 되는 줄 알고 기간만 늘려 **철회를 조용히 되돌린다.** 틀리는 방향을 정해 두는 자리이며, 여기서는
     * «무언가 바뀌었다» 를 먼저 말하는 쪽이 안전하다.
     */
    private fun scopeRefusal(declared: Entitlement, robotId: String, steps: List<RemedyStep>): Judgment.No? {
        declared.revocation?.let { revoked ->
            return Judgment.No(
                ApprovalRefusal.REVOKED,
                "자동 승인 자격이 철회됐다: ${declared.approverId} (${revoked.at} · ${revoked.by} · ${revoked.reason})",
            )
        }
        if (!now().isBefore(declared.expiresAt)) {
            return Judgment.No(
                ApprovalRefusal.EXPIRED,
                "자동 승인 자격이 만료됐다: ${declared.approverId} (${declared.expiresAt} 까지였다)",
            )
        }
        if (robotId !in declared.robotIds) {
            return Judgment.No(ApprovalRefusal.ROBOT_OUT_OF_SCOPE, "자동 승인 자격의 범위 밖 기체다: $robotId")
        }
        val uncovered = steps.map { it.skillType }.distinct().filterNot { it in declared.skillTypes }
        if (uncovered.isNotEmpty()) {
            return Judgment.No(ApprovalRefusal.SKILL_OUT_OF_SCOPE, "자동 승인 자격이 안 덮는 조치 유형이다: $uncovered")
        }
        return null
    }

    /**
     * 승인된 주문이 **접수된 뒤에만** 부른다 — 서 있던 제안을 내리고, 소모를 기록하고, 같은 조치의 승인 횟수를 센다.
     *
     * 관문 거절과 멱등 접힘은 여기 닿지 않는다. 그래서 그 제안은 지워지지 않고 기록도 안 남으며, 재시도는
     * 서 있는 제안으로 판정된다(ADR 46).
     *
     * @param executionId 그 접수로 선 실행. 소모 기록이 든다.
     */
    fun settle(go: Judgment.Go, executionId: String): ConsumedApproval {
        proposals.remove(go.key)
        val record = ConsumedApproval(
            approver = go.approver,
            at = now(),
            wallClockAt = wallClock(),
            executionId = executionId,
            steps = go.prefix.map { ApprovedStep(it.skillType, it.parameters, it.unitId) },
        )
        rememberConsumed(go.key, record)
        // **승인자 종류로 가르지 않는다**(ADR 43 §4). 이 수가 재는 것은 «같은 조치가 몇 번 반복됐나» 이고
        // 근본 원인은 누가 눌렀는지 모른다. 가르면 사람 다섯 번과 에이전트 다섯 번이 열이 아니라
        // 다섯과 다섯이 되어 한도에 안 걸린다 — 지표가 자기 집계 방식에 진다.
        val signature = "${go.robotId}|" + go.steps.joinToString(">") { it.skillType }
        approvals[signature] = (approvals[signature] ?: 0) + 1
        return record
    }

    /**
     * 재기동 뒤 다시 지은 실행의 소모를 **다시 세운다**(§15.221, [Middleware.resume]). 판단 기록이 넘긴 사실이고 다시 판정하지 않는다.
     *
     * 서 있던 제안은 지운다. 소모된 제안이 승인 장부에 서 있으면 같은 승인이 두 번 쓰인다. 승인 횟수([repeatedRemedies])는 세지
     * 않는다 — 앞 인스턴스에서 이미 센 승인이고, 그 수는 판단 기록이 싣지 않는 지표다.
     */
    fun restore(robotId: String, jobOrderId: String, record: ConsumedApproval) {
        val key = proposalKey(robotId, jobOrderId)
        proposals.remove(key)
        rememberConsumed(key, record)
    }
}

/**
 * 승인된 조치 열의 걸음 하나를 실행 단위로 세운다. 승인([RemedyDesk.judge])과 다시 짓기([Middleware.resume])가 같은 모양을 쓴다 —
 * 두 벌이면 다시 지은 걸음이 처음 승인한 걸음과 다른 단위가 되는 날이 온다.
 */
internal fun remedyUnit(unitId: String, skillType: String, parameters: Map<String, String>) = ExecutionUnit(
    unitId = unitId,
    route = Route.ROBOT,
    skillType = skillType,
    parameters = parameters,
    expectedIdentity = null,
    source = null,
    destination = null,
)

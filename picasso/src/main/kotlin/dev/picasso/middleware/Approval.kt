package dev.picasso.middleware

import com.google.protobuf.Struct
import com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat
import dev.picasso.capability.HoldEffects
import dev.picasso.capability.ObjectReferences
import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.contracts.v1.SkillDeclaration
import dev.picasso.contracts.wire.ContractIdentity
import java.time.Instant

/**
 * 밖에서 들어오는 **승인 시도**(ADR 44, `docs/orchestration.md` §7).
 *
 * ## «무엇을 하라» 가 아니라 «그것을 하라» 다
 *
 * 부르는 쪽은 **조치를 기술하지 못한다.** 주문도 걸음도 값도 싣지 않고, 이미 이 층이 계산해 두고 거절한
 * 제안 하나를 가리킬 뿐이다. 그래서 이 표면이 열려도 부르는 쪽의 권한은 «승인 시도 한 번» 에서 늘지
 * 않는다 — 늘어날 칸이 없다.
 *
 * 앞 판은 주문을 받았다. 주문을 받으면 부르는 쪽이 주문을 **지어낼** 수 있고, 그것은 승인 표면이 아니라
 * 일반 접수 표면이다. 프로세스 안에서는 안 드러났다 — 부르는 쪽이 시험뿐이었기 때문이다.
 */
data class ApprovalAttempt(
    /** 누가 누르는가. **위조 가능하며 부인방지가 아니다**(§15.3) — 조사 단서다. */
    val approver: Approver,
    val robotId: String,
    val jobOrderId: String,
    /**
     * 부르는 쪽이 **본** 조치 열. 그 사이 제안이 바뀌었으면 거절한다.
     *
     * **비워 두는 길을 두지 않는다.** 두면 안 적은 부르는 쪽이 이 대조를 조용히 잃고, 자기가 못 본
     * 조치를 자기 이름으로 승인한다.
     */
    val sawSkillTypes: List<String>,
)

/**
 * 승인 시도가 거절된 **종류**(ADR 44).
 *
 * 사유 산문만 내면 읽는 쪽은 한국어를 문자열로 맞춰야 하고, 그 시험은 «거절됐다» 만 보므로 **아무
 * 거절에나 초록이 된다** — 자격이 없어서인지 제안이 없어서인지 갈리지 않는다. 종류를 가르는 기준은
 * 하나다: **다음 행동이 다른가.**
 */
enum class ApprovalRefusal {
    /** 가려 둔 제안이다. 사람이 먼저 진단해야 한다 — 자격으로 뚫리지 않는다. */
    WITHHELD,

    /**
     * 이 프로세스가 뜬 뒤로 그 (기체, 주문) 에 제안이 선 적이 없다. 그 사이 새 제안이 서지 않는 한 다시 시도해도
     * 같으므로, 탐색 대장에서 그 열쇠의 답을 읽는 것이 다음 행동이다.
     */
    NO_PROPOSAL,

    /**
     * 그 (기체, 주문) 의 제안은 **이미 소모됐다**(ADR 46). 답이 소모 기록([ConsumedApproval])을 싣는다.
     *
     * 기록의 승인자가 부르는 쪽 자기 id 면 앞선 요청이 들어간 것이고, 다음 행동은 그 실행의 근거를
     * 기다리는 것이다. 아니면 누가 무엇을 보냈는지 보는 것이 다음 행동이다. 이 층은 어느 요청이
     * 부르는 쪽의 것인지 모르므로(승인자 id 는 주장이고 재시도마다 같다) 그 비교는 부르는 쪽이 한다.
     */
    CONSUMED,

    /** 부르는 쪽이 본 조치 열과 지금 제안이 다르다. 다시 읽고 다시 시도하는 것이 다음 행동이다. */
    PROPOSAL_CHANGED,

    /** 자동 승인 자격이 선언돼 있지 않다. 올리는 것이 다음 행동이다. */
    NOT_DECLARED,

    /**
     * 선언이 **철회됐다**(ADR 45). 왜 철회됐는지를 보고 **사후 검토로** 가는 것이 다음 행동이다.
     *
     * [NOT_DECLARED] 와 가르는 것이 이 값의 존재 이유다 — 앞은 「무언가 바뀌었다」이고 뒤는 「원래
     * 없었다」이며, 접으면 강등된 사람을 선언 수정하러 보낸다.
     */
    REVOKED,

    /** 선언이 만료됐다. 갱신하는 것이 다음 행동이다. */
    EXPIRED,

    /** 선언의 범위 밖 기체다. 범위를 넓히는 것이 다음 행동이다. */
    ROBOT_OUT_OF_SCOPE,

    /** 선언이 안 덮는 조치 유형이다. 범위를 넓히거나 사람이 누르는 것이 다음 행동이다. */
    SKILL_OUT_OF_SCOPE,

    /** 필수 값이 선언에 없다. 선언에 값을 적는 것이 다음 행동이다. */
    VALUE_NOT_DECLARED,

    /**
     * 든 것의 이름을 못 봤다. 관측이 없으면 **자동으로는 안 누른다** — 사람은 볼 수 있으므로 사람이
     * 누르는 것이 다음 행동이다.
     */
    OBJECT_NOT_OBSERVED,

    /** 선언이 적은 대상과 지금 든 것이 다르다. 이 자격은 이 상황의 것이 아니다. */
    DECLARED_CONTRADICTS_OBSERVED,

    /** 기체의 능력을 못 물어봤다. 조치가 실행 가능한지 확인할 수 없다. */
    CAPABILITY_UNKNOWN,

    /** 에이전트가 값을 실었다. 값은 선언과 관측에서만 온다. */
    VALUES_NOT_ACCEPTED,

    /** 자격은 섰는데 접수 관문이 거절했다(자리 경쟁·구역 겹침 등). 자격의 문제가 아니다. */
    REFUSED_BY_GATE,

    /**
     * 자격은 섰으나 **조치가 안 나갔다** — 그 주문이 이미 실행으로 서 있고 조치 열을 실을 개정이 없다. 판이 같거나
     * 낮거나, 실행이 다른 기체에 있거나, 이미 승인을 들었거나, 끝났으면 이것이다(§15.175, ADR 46). 승인으로 세지 않고
     * 소모로도 남기지 않는다. 접지 않으면 «승인했는데 아무 일도 안 일어났다» 가 성공으로 보인다.
     */
    REMEDY_NOT_APPLIED,

    /**
     * 그 제안은 **사람만 승인한다**(§15.320) — 발신자 거부로 운영자 보류에 선 단위 앞에 끼울 회복 제안이다. 값 없는 문
     * ([Middleware.attemptApproval])과 에이전트가 이것을 받는다. 사람이 회복 입구([Middleware.approveRecovery])로 대상과 본 조치 열을
     * 들고 누르는 것이 다음 행동이다. 에이전트 회복 승인을 열려면 «재작업은 사람» (ADR 47)의 예외를 소유자가 정해야 한다.
     */
    HUMAN_APPROVAL_REQUIRED,
}

/**
 * 제안이 **무엇을 위한 것인가**(§15.320). 장부의 열쇠는 그대로 (기체, 작업 지시)이고, 대상은 그 열쇠에 선 제안 하나가 든다.
 *
 * 실행 id 를 들지 않는다 — 인스턴스 안의 셈이라 다시 뜨면 바뀐다(ADR 48). 대상은 판단 기록과 소모 기록에 실려 재기동을 넘는다.
 */
sealed interface ProposalTarget {
    /** 접수 관문의 거절에 선 제안 — 승인되면 조치 열이 계획 맨 앞에 선다. */
    data object Order : ProposalTarget

    /**
     * 발신자가 `PRECONDITION_UNMET` 으로 거부해 운영자 보류에 선 단위 — 작업 지시 판 [orderVersion] 의 단위 [unitId] 가 시도 [attempt] 에서
     * 거부됐다. 승인되면 조치 열이 그 단위 바로 앞에 서고 그 단위는 다음 시도([attempt] + 1)의 대기로 돌아간다. 판·단위·시도 셋 가운데
     * 하나라도 바뀌었으면(개정, 재작업, 확인, 취소) 그 제안은 낡은 것이다.
     */
    data class Hold(val orderVersion: Int, val unitId: String, val attempt: Int) : ProposalTarget
}

/** 승인 시도의 답. **거절은 정상 경로다** — 오류가 아니다. */
sealed interface ApprovalOutcome {

    /**
     * 승인됐다. **실린 값을 돌려준다** — 부르는 쪽이 값을 고르지 않았으므로, 자기 이름으로 무엇이
     * 나갔는지 아는 길이 이것뿐이다. 안 돌려주면 승인이 부르는 쪽에서도 눈 감고 누르는 단추가 된다.
     */
    data class Approved(val executionId: String, val steps: List<ApprovedStep>) : ApprovalOutcome

    /**
     * 거절됐다. [consumed] 는 [ApprovalRefusal.CONSUMED] 일 때만 있고, 그때는 반드시 있다.
     *
     * **짝을 만들 때 검사한다.** 기록 없는 `CONSUMED` 를 허락하면 인코더가 «소모됐지만 누가인지 모른다» 를
     * 정상 답처럼 낸다(ADR 46).
     */
    data class Refused(
        val refusal: ApprovalRefusal,
        val reason: String,
        val consumed: ConsumedApproval? = null,
    ) : ApprovalOutcome {
        init {
            require((refusal == ApprovalRefusal.CONSUMED) == (consumed != null)) {
                "소모 기록은 CONSUMED 거부에만, 그리고 반드시 실린다: $refusal · ${consumed != null}"
            }
        }
    }
}

/**
 * 승인으로 실제로 나간 걸음 하나.
 *
 * @param unitId 이 걸음이 선 실행 단위(ADR 48). 결과 통보의 `completedUnits` 등이 이 값으로 그 걸음을 가리킨다.
 *   조치 열은 실행 계획의 맨 앞(회복이면 보류였던 단위 바로 앞, §15.320)에 걸음 순서대로 서고, 이름은 `remedy-{n}-{skillType}`(n 은 1부터)이다.
 *   실행 하나가 승인을 하나만 드므로(접수와 회복 합계) 조치 단위끼리는 겹치지 않고, 계획의 단위 id 와 겹치면 `-2`, `-3` … 을 붙인다(§15.320).
 */
data class ApprovedStep(val skillType: String, val parameters: Map<String, String>, val unitId: String)

/**
 * 제안 하나가 **소모된 기록**(ADR 46) — 승인이 접수로 이어져 그 제안이 지워질 때 (기체, 주문)마다 하나 남는다.
 *
 * 같은 열쇠가 다시 소모되면 덮어쓴다. 부르는 쪽이 묻는 것은 «지금 왜 없는가» 이고, 한 열쇠에 서 있는
 * 제안은 많아야 하나다. 보존 상한([Retention.consumedApprovals])을 넘으면 오래된 것부터 내린다(§15.166).
 *
 * @param steps 그 승인의 답이 돌려준 `steps` 와 같은 것 — 걸음마다 조치 유형과 실린 값.
 * @param target 소모된 제안의 대상(§15.320). 회복 입구는 **같은 대상**을 다시 승인할 때만 `CONSUMED` 를 받는다.
 */
data class ConsumedApproval(
    val approver: Approver,
    /** 소모한 순간의 가상 시각. */
    val at: Instant,
    /** 소모한 순간의 실 시각 — 현장 대조용이다. */
    val wallClockAt: Instant,
    /** 그 승인으로 선 실행. 회복이면 그 조치를 끼운 실행(같은 실행)이다. */
    val executionId: String,
    val steps: List<ApprovedStep>,
    val target: ProposalTarget = ProposalTarget.Order,
)

/**
 * 에이전트 승인이 실을 값을 **채운다 — 지어내지 않는다**(ADR 44).
 *
 * 출처가 둘뿐이다. 계약이 `is_object_reference` 로 표시한 칸은 **관측**에서, 나머지 필수 칸은 사람의
 * **선언**에서. 어느 쪽도 못 채우면 거절이고 사람에게 남는다.
 *
 * **순수 함수다.** 미들웨어도 포트도 시계도 모른다 — 관측을 인자로 받는다. 그래서 실물에서 아직 못
 * 만드는 상황(기체가 든 채라면서 이름을 안 주는 경우, §15.173)도 시험이 직접 세울 수 있다.
 */
object RemedyValues {

    sealed interface Resolution {
        data class Filled(val parameters: List<Map<String, String>>) : Resolution
        data class Refused(val refusal: ApprovalRefusal, val reason: String) : Resolution
    }

    /**
     * @param observed **지금** 관측한 파지. 걸음마다 효과로 앞으로 굴린다 — 첫 걸음이 놓으면 둘째 걸음이
     *   들 것의 이름은 아무도 모르고, 그때는 채우지 않고 거절한다(§15.174).
     */
    fun resolve(
        steps: List<RemedyStep>,
        declared: Map<String, SkillDeclaration>,
        entitlement: Entitlement,
        observed: HoldState?,
    ): Resolution {
        var hold = observed
        val filled = mutableListOf<Map<String, String>>()

        steps.forEachIndexed { at, step ->
            val where = "조치 ${at + 1}(${step.skillType})"
            val skill = declared[step.skillType]
                ?: return Resolution.Refused(ApprovalRefusal.CAPABILITY_UNKNOWN, "$where 를 기체가 선언하지 않았다")
            val action = entitlement.actionFor(step.skillType)
                ?: return Resolution.Refused(ApprovalRefusal.SKILL_OUT_OF_SCOPE, "$where 가 선언에 없다")

            val required = skill.parametersList.filterNot { it.optional }.map { it.key }
            val values = action.parameters.toMutableMap()

            for (key in required.filter { it in ObjectReferences.keysOf(step.skillType) }) {
                val held = hold?.takeIf { it.kind == HoldKind.HOLD_KIND_HOLDING }?.objectRef.orEmpty()
                if (held.isEmpty()) {
                    return Resolution.Refused(
                        ApprovalRefusal.OBJECT_NOT_OBSERVED,
                        "$where 의 «$key» 는 지금 든 것의 이름인데 그것을 못 봤다",
                    )
                }
                // **선언은 좁히기만 한다.** 적혀 있으면 관측과 같아야 하고 덮어쓰지는 못한다 —
                // 덮어쓸 수 있으면 선언이 사실을 이기고, 자동 승인이 엉뚱한 것을 집는다.
                val named = values[key]
                if (named != null && named != held) {
                    return Resolution.Refused(
                        ApprovalRefusal.DECLARED_CONTRADICTS_OBSERVED,
                        "$where 의 선언은 «$named» 인데 지금 든 것은 «$held» 다",
                    )
                }
                values[key] = held
            }

            val missing = required.filterNot { it in values }
            if (missing.isNotEmpty()) {
                return Resolution.Refused(
                    ApprovalRefusal.VALUE_NOT_DECLARED,
                    "$where 에 필요한 값이 선언에 없다: $missing",
                )
            }

            filled += values
            hold = hold?.let { HoldEffects.after(step.skillType, it) }
        }
        return Resolution.Filled(filled)
    }
}

/**
 * 승인 표면의 **인코딩**(`docs/orchestration.md` §7). `LedgerExport` 와 같은 성질이다 — 문자열만 다루며
 * 소켓도 파일도 모른다. 담는 쪽이 HTTP 로 내든 무엇으로 내든 이 규약은 그대로다.
 *
 * **거절은 정상 응답이다.** 거절을 오류 코드로 내면 읽는 쪽에서 «자격 없음» 과 «시스템 고장» 이 같은
 * 모양이 된다. 오류로 낼 것은 [decode] 가 던지는 **못 읽는 요청**뿐이다.
 */
object ApprovalWire {

    /**
     * 이 규약의 판. 답마다 싣는다 — 읽는 쪽이 모르는 판을 만나면 멈출 수 있게.
     *
     * `2` 에서 `refusal` 에 [ApprovalRefusal.REVOKED] 가 늘었다(ADR 45). **칸이 느는 것과 다르다** —
     * 모르는 칸은 무시하면 그만이지만 `refusal` 로 분기하는 읽는 쪽은 모르는 값을 만난다.
     *
     * `3` 에서 `refusal` 에 [ApprovalRefusal.CONSUMED] 가 늘고, 거절 답에 `consumed` 칸이 생겼다(ADR 46).
     * `CONSUMED` 면 소모 기록이고, 다른 거절은 키를 빼지 않고 `null` 이다. 승인 답은 이 칸을 안 싣는다.
     *
     * `4` 에서 모든 답에 `instanceId` 가, 걸음마다 `unitId` 가 붙었다(ADR 48). 칸이 는 것뿐이지만 **결과 통보와
     * 잇는 칸이라** 판을 올린다 — 읽는 쪽이 이 판을 보고서야 «통보의 어느 단위가 이 조치인가» 를 이 칸으로 묻는다.
     *
     * `5` 에서 `refusal` 에 [ApprovalRefusal.HUMAN_APPROVAL_REQUIRED] 가 늘고, 소모 기록에 `target`(접수면 `null`, 보류면
     * `orderVersion`·`unitId`·`attempt`) 칸이 생겼다(§15.320). 값 없는 이 문은 회복 제안을 승인하지 못한다 — 그 답이 이 값이다.
     */
    const val SCHEMA_VERSION: String = "5"

    /**
     * 요청 한 줄을 읽는다. **못 읽으면 던진다** — 못 읽는 요청은 거절이 아니라 잘못된 요청이고, 둘을
     * 같은 모양으로 내면 읽는 쪽이 자기 오타와 «자격 없음» 을 구별하지 못한다.
     */
    fun decode(json: String): ApprovalAttempt {
        val fields = try {
            Struct.newBuilder().also { JsonFormat.parser().merge(json, it) }.build().fieldsMap
        } catch (e: Exception) {
            throw IllegalArgumentException("승인 요청을 읽지 못했다: ${e.message}", e)
        }
        val kind = string(fields, "approverKind")
        return ApprovalAttempt(
            approver = Approver(
                id = string(fields, "approverId"),
                kind = ApproverKind.entries.firstOrNull { it.name == kind }
                    ?: throw IllegalArgumentException("모르는 승인자 종류다: $kind"),
            ),
            robotId = string(fields, "robotId"),
            jobOrderId = string(fields, "jobOrderId"),
            sawSkillTypes = strings(fields, "sawSkillTypes"),
        )
    }

    /**
     * @param instanceId 답을 낸 미들웨어 인스턴스([Middleware.instanceId]). 실행 식별자는 그 인스턴스 안의 셈이라 이 값과
     *   짝지어야 다시 뜬 뒤에도 한 시도를 가리킨다.
     */
    fun encode(outcome: ApprovalOutcome, instanceId: String): String {
        val o = Obj()
            .str("schemaVersion", SCHEMA_VERSION)
            .str("contractSemver", ContractIdentity.semver)
            .str("instanceId", instanceId)
        return when (outcome) {
            is ApprovalOutcome.Approved -> o
                .str("outcome", "APPROVED")
                .str("executionId", outcome.executionId)
                .raw("steps", outcome.steps.joinToString(",", "[", "]") { step(it) })
            is ApprovalOutcome.Refused -> o
                .str("outcome", "REFUSED")
                .str("refusal", outcome.refusal.name)
                .str("reason", outcome.reason)
                // **키를 빼지 않는다.** 빼면 «소모가 아니다» 와 «이 판이 그 칸을 모른다» 가 같은 모양이 된다.
                .raw("consumed", outcome.consumed?.let { consumed(it) } ?: "null")
        }.done()
    }

    /** 칸 이름은 이미 있는 것이다 — 승인자는 요청에서, 실행과 걸음은 승인 답에서, 시각 둘은 §6 의 적재에서. */
    private fun consumed(c: ConsumedApproval): String = Obj()
        .str("approverId", c.approver.id)
        .str("approverKind", c.approver.kind.name)
        .str("at", c.at.toString())
        .str("wallClockAt", c.wallClockAt.toString())
        .str("executionId", c.executionId)
        .raw("steps", c.steps.joinToString(",", "[", "]") { step(it) })
        // **키를 빼지 않는다.** 접수 승인의 소모면 `null` 이다.
        .raw("target", (c.target as? ProposalTarget.Hold)?.let { hold(it) } ?: "null")
        .done()

    /** 보류 대상 — 칸 이름은 판단 기록의 위치 칸과 같다. */
    internal fun hold(t: ProposalTarget.Hold): String = Obj()
        .num("orderVersion", t.orderVersion)
        .str("unitId", t.unitId)
        .num("attempt", t.attempt)
        .done()

    private fun step(s: ApprovedStep): String = Obj()
        .str("unitId", s.unitId)
        .str("skillType", s.skillType)
        .raw("parameters", s.parameters.entries.joinToString(",", "{", "}") { Json.quote(it.key) + ":" + Json.quote(it.value) })
        .done()

    private fun string(fields: Map<String, Value>, name: String): String {
        val value = fields[name] ?: throw IllegalArgumentException("승인 요청에 «$name» 이 없다")
        if (!value.hasStringValue()) throw IllegalArgumentException("승인 요청의 «$name» 이 문자열이 아니다")
        return value.stringValue
    }

    private fun strings(fields: Map<String, Value>, name: String): List<String> {
        val value = fields[name] ?: throw IllegalArgumentException("승인 요청에 «$name» 이 없다")
        if (!value.hasListValue()) throw IllegalArgumentException("승인 요청의 «$name» 이 목록이 아니다")
        return value.listValue.valuesList.map {
            if (!it.hasStringValue()) throw IllegalArgumentException("«$name» 의 원소가 문자열이 아니다")
            it.stringValue
        }
    }
}

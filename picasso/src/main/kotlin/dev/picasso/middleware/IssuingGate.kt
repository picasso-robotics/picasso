package dev.picasso.middleware

import java.time.Instant

/**
 * **발행 관문**(ADR 51 결정 6, ADR 52, §15.249) — 새 로봇 명령이 나가기 전에 묻는 자리 하나, 입력 셋.
 *
 * 묻는 자리는 [Middleware] 의 셋이다. 다음 단위를 출발시키는 `startUnit` 앞, 개정이 도는 단위에 갱신을 보내는 `revise`
 * 의 `robots.start` 앞, 그리고 `IN_DOUBT` 의 조회 앞이다. 조회도 `StartTask` 재전송이라 원 요청이 안 닿았으면 새 접수가
 * 된다 — 그 길만 열어 두면 막힌 갱신이 조회로 새어 나간다. **취소·관측·조회성 읽기는 묻지 않는다.** 취소는 언제나 나가야
 * 하고, 읽기는 기체를 움직이지 않는다.
 *
 * 관문을 둘로 나누지 않는 이유는 ADR 51 의 것 그대로다. 권위와 허가가 같은 자리(새 발행)를 막으므로 둘로 나누면 한쪽만
 * 고쳐지는 날 어긋난다.
 *
 * ⚠ **이것은 안전 기능이 아니다**(ADR 32). 비상정지·보호 정지·사람 감지는 계약 아래의 안전 계통이 한다. 여기 들어오는
 * 허가는 그 상태를 **정보로 비춘 것**이고, 이 관문은 그 정보로 이 층이 새 일을 내지 않을 뿐이다. 이 관문이 열려도 기체는
 * 안전 계통이 막으면 서고, 이 관문이 닫혀도 이미 나간 명령은 안전 계통이 아니면 안 선다.
 */
internal class IssuingGate(
    /** 널이면 붙이지 않은 것이다 — 둘째 입력이 아무것도 막지 않는다. 오늘의 거동이다. */
    private val permits: WorkPermitPort?,
    /** 널이면 붙이지 않은 것이다 — 셋째 입력이 아무것도 막지 않는다. */
    private val retirements: RetirementPort?,
    private val now: () -> Instant,
) {

    /**
     * [robots] 전부에 새 명령을 내도 되는가. 한 기체라도 막히면 막는다.
     *
     * **부를 때마다 포트를 새로 읽는다.** 현장 시간값처럼 라운드에 한 번 읽어 두지 않는 것은, 허가는 닫힌 쪽으로 판정하는
     * 값이라 더 새 값이 언제나 낫고, 개정([Middleware.submit])은 라운드 밖에서 오기 때문이다.
     */
    fun check(robots: Set<String>): IssueVerdict {
        authorityRefusal(robots)?.let { return it }
        val retired = robots.sorted().mapNotNull { robot ->
            retirementOf(robot)?.let { reason ->
                RobotPermit(
                    robot, WorkPermit.INHIBITED,
                    listOf(PermitReason(null, PermitCause.RETIRED, reason, "운영자가 이 기체의 퇴역을 취소할 때", null)),
                )
            }
        }
        val permit = permitVerdict(robots - retired.map { it.robotId }.toSet())
        if (retired.isEmpty()) return permit
        val others = (permit as? IssueVerdict.Withheld)?.robots.orEmpty()
        return IssueVerdict.Withheld((retired + others).sortedBy { it.robotId })
    }

    /**
     * **셋째 입력 — 기체 퇴역**(§15.249). 그 기체가 퇴역했으면 사유, 아니면 널이다. 재할당이 후보를 거를 때도 이것을 묻는다.
     *
     * 퇴역한 기체는 허가를 따로 판정하지 않는다 — 퇴역이 풀려야 허가가 의미를 갖는다. 포트를 못 읽으면 널(막지 않음)이다
     * ([RetirementPort]).
     */
    fun retirementOf(robot: String): String? {
        val port = retirements ?: return null
        return try {
            port.retired()[robot]
        } catch (_: Exception) {
            null
        }
    }

    /**
     * **첫째 입력 — 유효 권위.** 지금은 활성 프로세스가 하나(ADR 51 결정 3)라서 언제나 유효하고, 그래서 아무것도 막지 않는다.
     *
     * 자리만 둔다. 소유 이전 규약(ADR 51 결정 4)을 지으면 «이 인스턴스가 그 실행과 그 기체의 권위를 쥐었는가» 가 여기서 답하고,
     * 쥐지 않았으면 허가와 같은 모양([IssueVerdict.Withheld])으로 막는다. 리스·세대는 짓지 않는다 — 소비자가 없다(ADR 9).
     */
    @Suppress("UNUSED_PARAMETER", "FunctionOnlyReturningConstant")
    private fun authorityRefusal(robots: Set<String>): IssueVerdict.Withheld? = null

    /** **둘째 입력 — 작업 허가.** 포트가 없으면 통과, 있으면 기체마다 판정해 하나라도 `PERMITTED` 가 아니면 막는다. */
    private fun permitVerdict(robots: Set<String>): IssueVerdict {
        val port = permits ?: return IssueVerdict.Issue(emptyList())
        // ★**포트가 던지면 모른다(UNKNOWN)이고, 모르면 막는다.** 결함 관문은 못 물으면 통과시키지만(관측 실패로 현장을
        //   세우지 않는다) 이 관문은 반대다 — 허가를 못 읽은 채 내는 것은 «막혀 있을지 모르는 셀에 새 일을 낸다» 이다.
        val readings = try {
            port.read()
        } catch (_: Exception) {
            null
        }
        val each = robots.sorted().map { robot -> permitOf(robot, readings) }
        val blocking = each.filter { it.permit != WorkPermit.PERMITTED }
        return if (blocking.isEmpty()) IssueVerdict.Issue(each) else IssueVerdict.Withheld(blocking)
    }

    /**
     * 기체 하나의 허가. 그 기체를 덮는 판독을 전부 모아 **가장 닫힌 것**을 고른다 — `INHIBITED` 가 `UNKNOWN` 을, `UNKNOWN` 이
     * `PERMITTED` 를 이긴다. 둘 다 막지만 `INHIBITED` 는 원천이 막는 주체를 말한 것이라 그쪽을 보인다.
     *
     * **어느 원천도 덮지 않는 기체는 `UNKNOWN` 이다.** 포트를 붙인 배치에서 말이 없는 것은 허가가 아니다.
     */
    private fun permitOf(robot: String, readings: List<PermitReading>?): RobotPermit {
        if (readings == null) {
            return RobotPermit(
                robot, WorkPermit.UNKNOWN,
                listOf(PermitReason(null, PermitCause.PORT_FAILED, null, "허가 포트를 다시 읽을 수 있을 때", null)),
            )
        }
        val found = readings.flatMap { reading ->
            reading.scopes.filter { scope -> scope.robots?.contains(robot) ?: (robot in reading.governed) }
                .map { scope -> judge(reading, scope) }
        }
        if (found.isEmpty()) {
            return RobotPermit(
                robot, WorkPermit.UNKNOWN,
                listOf(PermitReason(null, PermitCause.NOT_COVERED, null, "이 기체를 덮는 허가 원천이 생길 때", null)),
            )
        }
        val permit = listOf(WorkPermit.INHIBITED, WorkPermit.UNKNOWN, WorkPermit.PERMITTED).first { p -> found.any { it.first == p } }
        return RobotPermit(robot, permit, found.filter { it.first == permit }.map { it.second })
    }

    /**
     * 판독 한 줄을 이 시각의 허가로 옮긴다. 원천이 말한 값을 그대로 쓰는 것은 셋이 아닐 때뿐이다.
     *
     * 1. **원천의 세션이 바뀌었다** — 그 줄은 앞 세션에서 관측한 값이다. 다시 붙은 원천이 옛 값을 들고 있을 수 있으므로 `UNKNOWN`.
     * 2. **유효 기간이 지났다** — 관측 시각 + 유효 기간을 넘긴 값은 지금의 값이 아니다. `UNKNOWN`.
     * 3. **어느 기체인지 모른다**([PermitScope.robots] 가 널) — 원천이 맡은 기체 전부([PermitReading.governed])에 `UNKNOWN`.
     *    마지막으로 확인한 범위만 막으면 넓어진 영향 범위가 새어 나간다. 원천이 `PERMITTED` 라고 해도 같다 — 누구에게의 허가인지
     *    모르는 허가는 허가가 아니다.
     */
    private fun judge(reading: PermitReading, scope: PermitScope): Pair<WorkPermit, PermitReason> {
        val source = reading.source
        fun unknown(cause: PermitCause, basis: String) =
            WorkPermit.UNKNOWN to PermitReason(source, cause, scope.subject, scope.resumeBasis ?: basis, scope.observedAt)
        return when {
            scope.session != reading.session ->
                unknown(PermitCause.SESSION_CHANGED, "원천 $source 가 지금 세션(${reading.session})에서 다시 관측할 때")
            now().isAfter(scope.observedAt.plus(scope.validFor)) ->
                unknown(PermitCause.EXPIRED, "원천 $source 가 유효한 관측을 다시 낼 때")
            scope.robots == null ->
                unknown(PermitCause.SCOPE_UNKNOWN, "원천 $source 가 영향 범위를 기체 집합으로 다시 말할 때")
            else -> scope.permit to PermitReason(
                source, PermitCause.REPORTED, scope.subject,
                scope.resumeBasis ?: "원천 $source 가 PERMITTED 를 낼 때",
                scope.observedAt,
            )
        }
    }
}

/** 관문의 답. */
sealed interface IssueVerdict {
    /** 내도 된다. [basis] 는 그 근거(기체마다의 허가). 허가 포트가 없으면 비어 있다. */
    data class Issue(val basis: List<RobotPermit>) : IssueVerdict

    /** 내지 않는다. [robots] 는 막은 기체와 그 까닭이다 — 통과한 기체는 싣지 않는다. */
    data class Withheld(val robots: List<RobotPermit>) : IssueVerdict
}

/** 관문이 막은 명령의 종류. 셋 다 기체에 `StartTask` 로 나가는 것이다. */
enum class WithheldIssue {
    /** 다음 단위의 첫 출발. */
    START,

    /** 도는 단위에 보내는 개정의 갱신(같은 태스크 id, 새 리비전). */
    REVISION_UPDATE,

    /** `IN_DOUBT` 의 같은 참조 재전송. 원 요청이 안 닿았으면 새 접수가 된다. */
    IN_DOUBT_LOOKUP,
}

/**
 * 이 층이 허가를 그 값으로 판정한 까닭. 원천이 말한 것([REPORTED])과 이 층이 닫힌 쪽으로 바꾼 것을 가른다 — 둘을 접으면
 * «원천이 모른다고 했다» 와 «원천의 말이 낡았다» 가 같아 보여, 현장이 고칠 자리를 못 찾는다.
 */
enum class PermitCause {
    /** 원천이 그 값을 말했다. */
    REPORTED,

    /** 유효 기간이 지났다. */
    EXPIRED,

    /** 원천의 세션이 바뀌었는데 그 줄은 앞 세션의 관측이다. */
    SESSION_CHANGED,

    /** 원천이 어느 기체인지 말하지 못했다 — 원천이 맡은 기체 전부가 모른다. */
    SCOPE_UNKNOWN,

    /** 어느 원천도 이 기체를 덮지 않는다. */
    NOT_COVERED,

    /** 포트를 못 읽었다(던졌다). */
    PORT_FAILED,

    /** 운영자가 원장에서 이 기체를 퇴역으로 내렸다(§15.249). [PermitReason.subject] 가 퇴역 사유다. */
    RETIRED,
}

/**
 * 기체 하나에 대한 판단 하나.
 *
 * @param source 그 값을 낸 원천. 이 층이 정한 것([PermitCause.NOT_COVERED]·[PermitCause.PORT_FAILED])과 퇴역
 *   ([PermitCause.RETIRED])이면 널이다.
 * @param subject 막는 주체 — 원천이 보고한 것(문 열림 신호, 셀 모드 같은). 원천이 말하지 않았으면 널이다.
 * @param resumeBasis 무엇이 바뀌면 풀리는가. 원천이 말했으면 그 말, 아니면 이 층이 까닭에서 정한 말이다.
 * @param observedAt 원천이 그 값을 관측한 시각. 이 층이 정한 것이면 널이다.
 */
data class PermitReason(
    val source: String?,
    val cause: PermitCause,
    val subject: String?,
    val resumeBasis: String,
    val observedAt: Instant?,
)

/** 기체 하나의 허가와 그 까닭. 같은 값을 낸 판독이 여럿이면 [reasons] 도 여럿이다. */
data class RobotPermit(val robotId: String, val permit: WorkPermit, val reasons: List<PermitReason>)

/**
 * **지금 막혀 있는 새 발행 하나**(ADR 52) — 어느 기체가, 무엇 때문에, 무엇이 바뀌면 풀리는지.
 *
 * 실행의 결함 막음([Execution.blockedBy])과 칸이 다르다. 운영자 해제([Middleware.release])는 결함을 감수할 뿐 이것을
 * 못 푼다 — 허가는 사람이 감수할 값이 아니라 원천이 다시 `PERMITTED` 를 낼 때만 풀린다.
 *
 * @param unitId 막힌 명령이 향한 단위.
 * @param since 같은 단위의 같은 종류가 처음 막힌 시각. 까닭이 바뀌어도 다시 찍지 않는다.
 */
data class PermitBlock(
    val unitId: String,
    val withheld: WithheldIssue,
    val robots: List<RobotPermit>,
    val since: Instant,
) {
    /** 막은 기체의 id. */
    val robotIds: List<String> get() = robots.map { it.robotId }

    /** 막는 주체 전부(원천이 보고한 것). */
    val subjects: List<String> get() = robots.flatMap { r -> r.reasons.mapNotNull { it.subject } }.distinct()

    /** 풀릴 근거 전부. */
    val resumeBasis: List<String> get() = robots.flatMap { r -> r.reasons.map { it.resumeBasis } }.distinct()

    /** 관측 시각을 뺀 모양 — 같은 막음을 자취에 되풀이하지 않으려고 대는 열쇠다. 원천은 관측 시각을 계속 새로 찍는다. */
    internal val signature: String
        get() = "$unitId/${withheld.name}:" + robots.joinToString(";") { r ->
            "${r.robotId}=${r.permit.name}(" + r.reasons.joinToString(",") { "${it.source}/${it.cause.name}/${it.subject}" } + ")"
        }
}

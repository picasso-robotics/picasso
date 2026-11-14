package dev.picasso.middleware

import com.google.protobuf.MessageOrBuilder
import com.google.protobuf.util.JsonFormat
import dev.picasso.capability.PreconditionViolation
import dev.picasso.capability.RemedyStep
import dev.picasso.contracts.wire.ContractIdentity
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 두 대장을 밖이 읽는 모양으로 **인코딩한다**(`docs/orchestration.md` §6).
 *
 * ## 전송은 여기 없다
 *
 * 문자열만 만든다 — 파일도 소켓도 자기 시계도 건드리지 않는다. 담는 쪽이 그것을 어디에 쓸지 정하고
 * (§6 의 사다리 C → A), 담는 쪽이 바뀌어도 이 인코딩은 그대로다. 순수 함수라서 **인프라 없이
 * 시험이 문다** — 그것이 v1 에서 파일을 고른 이유의 절반이다.
 *
 * ## 「없음」을 키 누락으로 적지 않는다
 *
 * 값이 없으면 `null` 을, 빈 목록은 `[]` 를 적는다. 키를 빼면 «값이 없다» 와 «이 판이 아직 그 필드를
 * 안 낸다» 가 같은 모양이 되고, 읽는 쪽에는 둘을 가를 방법이 없다.
 *
 * **계약 메시지도 같은 규칙을 받는다.** protobuf JSON 의 기본은 기본값 필드를 빼는 것이라, 그대로 쓰면
 * `HoldState` 가 `HOLD_KIND_UNSPECIFIED` 일 때 통째로 `{}` 가 된다 — **「빈손」과 「말하지 않았다」가
 * 접히는 것**이고 §15.145 가 막은 바로 그 일이다. 그래서 기본값도 적는다.
 */
object LedgerExport {

    /**
     * 이 규약의 판. 읽는 쪽이 모르는 판을 만나면 멈출 수 있게 한 벌마다 싣는다.
     *
     * `2` 에서 사건 줄이 기체·근거 등급·걸음 위치를 싣고 `blockedBy` 가 분류 이름에서 결함 객체로
     * 바뀌었다(§15.177). **칸이 는 것이 아니라 모양이 바뀐 자리가 있으므로** 읽는 쪽이 판을 봐야 한다.
     *
     * `3` 에서 의도(`intent`)·경로(`route`)·관측 신뢰(`observation`)가 붙었다(§15.178). 이쪽은 칸이
     * 는 것뿐이라 모르는 칸을 무시하는 읽는 쪽은 그대로 돈다.
     *
     * `4` 에서 탐색 줄의 `outcome` 에 **갈래가 하나 늘었다**(`SOURCE_MISSING`, §15.183). 사건 줄은 안 바뀌었다.
     * **칸이 는 것과 다르다** — `outcome` 으로 분기하는 읽는 쪽은 모르는 값을 만나므로 판을 봐야 한다.
     *
     * `5` 에서 인시던트 줄에 `resolution`(사람이 그 단위에 낸 판단) 한 칸이 붙었다(§15.188). 칸이 는 것뿐이고, 해시에서
     * 빠지는 칸이라 요약은 안 바뀌었다.
     *
     * `6` 에서 인시던트 줄의 `route` 에 **하위 범주가 하나 늘었다**(`SIGNAL`, 설비 대기). 칸은 안 늘었다. `4` 와 같은 이유로
     * 버전을 올린다 — `route` 로 분기하는 읽는 쪽은 모르는 값을 만난다. 의도의 임무 버전(`Intent.missionVersion`)은
     * 싣지 않는다 — 읽는 쪽이 아직 없다(ADR 9). 다만 그 값이 해시에 들므로 **요약(`digest`)은 모든 줄에서 바뀐다** —
     * 코드 케이퍼빌리티로 돈 줄(임무 버전 널)도 해시의 모양이 바뀌었기 때문이다.
     *
     * `7` 에서 인시던트 줄 `resolution.decision` 에 **값이 둘 늘었다**(`OPERATOR_CANCEL`·`REMEDY_APPLIED`, §15.320). 칸은 안 늘었다.
     * `6` 과 같은 이유로 판을 올린다 — `decision` 으로 분기하는 읽는 쪽은 모르는 값을 만난다. 해시에서 빠지는 칸이라 요약은 그대로다.
     *
     * `8` 은 감사 내보내기 묶음이다(§15.322). 인시던트 줄에 정체성 넷(`incidentKey`·`firstOpenedAt`·`openingKind`·`attempt`), 판단의
     * 판단자(`resolution.decidedBy`), 의도의 임무 버전(`intent.missionVersion`)과 현장 설정 셋(`intent.siteSettingsVersion`·`inDoubtGrace`·
     * `stallWindow`)이 붙었고, 탐색 줄의 `outcome` 에 **갈래가 셋 늘었다**(`SOURCE_UNOBSERVED`·`DESTINATION_OCCUPIED`·`DESTINATION_UNOBSERVED`).
     * 안내(`manifest.json`)에는 인스턴스(`instanceId`)가 붙었다 — 한 벌은 한 인스턴스의 것이다. 새 연결 칸은 전부 해시에서 빠지고 임무·설정의
     * 해시 규칙은 그대로라 **같은 시드의 요약은 판 7 과 같다.**
     *
     * `9` 에서 인시던트 줄 `resolution` 에 재작업 근거 한 칸(`resolution.reworkBasis`)이 붙었다(§15.323). 시도된 실패를 재작업으로 받을 때 읽은
     * 손·자리이고 묶음 뿌리·물리 관문을 지난 단위의 인시던트에 붙은 재작업 판단에만 값이 있다(건너뛴 쌍의 이동 인시던트에는 `{robotId, readAt}` 표지 —
     * «관측 없음»). 이 인코더의 원칙대로 **널도 키를 싣는다** — 판단마다, 그리고 근거 안의 손·자리 칸마다. 값 조합도 는다 — 시도된 실패 인시던트
     * (`unresolved: false`)에 `REWORK` 판단이 서고, 첫 발행 직전 재검사를 못 지난 시도는 태스크 없는 실패 인시던트(`REWORK_RECHECK`)를 열며, 판단
     * 기록에서는 `DONE` 이던 이동에 `REWORK` 판단이 선다. 판단 칸이라 해시에서 빠지고 **같은 시드의 요약은 판 8 과 같다.**
     */
    const val SCHEMA_VERSION: String = "9"

    const val INCIDENTS: String = "incidents.jsonl"
    const val REMEDY_SEARCHES: String = "remedy-searches.jsonl"

    /** **맨 마지막에 나타난다.** 이것이 보이는 것이 «한 벌이 다 나왔다» 는 신호다. */
    const val MANIFEST: String = "manifest.json"

    private val runs = AtomicLong()

    /**
     * 한 번의 구동을 가리키는 값. **이 내보내기에서 유일하게 결정적이지 않다.**
     *
     * 나머지는 전부 같은 시드면 같은 값이다 — `IncidentBundle.digest()` 도 `incident-N` 도 그렇게
     * 설계됐고 그것이 재현의 근거다. 그런데 읽는 쪽이 멱등 열쇠를 그 값들로 들면, 같은 시드로 다시 돌린
     * 두 번째 구동의 사건이 전부 «이미 본 것» 으로 접혀 **아무 신호 없이 처리가 사라진다.** 로그도 예외도
     * 없고, 그 침묵은 «설명이 아직 안 붙었다» 와 구별되지 않는다.
     *
     * 그래서 이 값 하나만 실 시계에서 나오고, 같은 밀리초 안에서도 안 겹치게 세는 수를 뒤에 붙인다.
     * **결정적으로 바꾸지 말 것** — 바꾸는 순간 위의 고장이 조용히 돌아온다. 시험이 그것을 든다.
     */
    fun newRunId(writtenAt: Instant): String = "run-$writtenAt-${runs.incrementAndGet()}"

    /** 사건 번들 한 줄씩. 열린 순서 그대로이며 마지막 줄에도 줄바꿈이 붙는다. */
    fun incidents(bundles: List<IncidentBundle>): String =
        bundles.joinToString("") { line(it) + "\n" }

    /** 탐색 결과 한 줄씩. 사건과 **합치지 않는다** — 합치면 읽는 쪽이 갈래를 다시 나눠야 한다. */
    fun remedySearches(records: List<RemedySearchRecord>): String =
        records.joinToString("") { line(it) + "\n" }

    /**
     * 한 벌의 안내. [instanceId] 는 이 벌을 낸 인스턴스([Middleware.instanceId])다(판 8) — 인시던트 id 와 실행 id 는 그 인스턴스 안의 셈이라
     * 이것과 함께여야 한 사본을 가리킨다. 재기동을 넘어 같은 사건을 잇는 것은 줄의 `incidentKey` 다.
     */
    fun manifest(
        runId: String,
        writtenAt: Instant,
        virtualNow: Instant,
        incidents: Int,
        remedySearches: Int,
        instanceId: String,
    ): String = Obj()
        .str("schemaVersion", SCHEMA_VERSION)
        .str("runId", runId)
        .str("instanceId", instanceId)
        .str("writtenAt", writtenAt.toString())
        .str("virtualNow", virtualNow.toString())
        .str("contractSemver", ContractIdentity.semver)
        .raw("counts", Obj().num("incidents", incidents).num("remedySearches", remedySearches).done())
        .done()

    private fun line(b: IncidentBundle): String = Obj()
        .str("incidentId", b.incidentId)
        // 정체성(§15.322). 열쇠가 같으면 같은 사건이다 — 널은 다시 연 인시던트의 연결 불명이고 추측해 잇지 않는다. `attempt` 는 재발 판정의
        // 재료이지 같은 보류를 잇는 열쇠가 아니다. 넷 다 해시에서 빠진다.
        .str("incidentKey", b.origin?.key)
        .str("firstOpenedAt", b.origin?.firstOpenedAt?.toString())
        .str("openingKind", b.openingKind.name)
        .num("attempt", b.attempt)
        .str("jobOrderId", b.jobOrderId)
        .str("executionId", b.executionId)
        .str("robotId", b.robotId)
        .str("unitId", b.unitId)
        .str("at", b.at.toString())
        .str("wallClockAt", b.wallClockAt.toString())
        .str("failureClass", b.failureClass)
        .raw("fault", b.fault?.let { fault(it) } ?: "null")
        .raw("blockedBy", b.blockedBy.joinToJson { fault(it) })
        .raw("residualHold", proto(b.residualHold))
        .bool("unresolved", b.unresolved)
        .raw("preconditionSubjects", arrayOfStrings(b.preconditionSubjects))
        .raw("evidenceWindow", b.evidenceWindow.joinToJson { event(it) })
        .bool("windowTruncated", b.windowTruncated)
        .str("expectedHold", b.expectedHold?.name)
        .str("observedHold", b.observedHold.name)
        .str("effectMismatch", b.effectMismatch)
        .str("requiredEvidence", b.requiredEvidence.name)
        .str("reachedEvidence", b.reachedEvidence.name)
        .str("verification", b.verification.name)
        .raw("step", step(b.step))
        .str("route", b.route)
        .raw("intent", intent(b.intent))
        .raw("observation", observation(b.observation))
        .num("profileRevision", b.profileRevision)
        .str("contractSemver", b.contractSemver)
        .raw("approvedBy", b.approvedBy?.let { approver(it) } ?: "null")
        .raw("review", b.review?.let { review(it) } ?: "null")
        .raw("resolution", b.resolution?.let { resolution(it) } ?: "null")
        // **이미 계산되는 값이다.** 읽는 쪽이 같은 사건을 두 번 받았는지 가르는 유일한 결정적 열쇠다.
        .str("digest", b.digest())
        .done()

    /**
     * 탐색 결과는 **평평하게** 적는다 — `outcome` 이 갈래이고 나머지는 그 갈래의 값이다.
     *
     * **`WITHHELD` 줄에는 `steps` 키가 없다.** 이것이 적재면의 방벽이고 코드의 방벽과 같은 성질이다
     * (§15.164). 여기서 편의로 제안 표의 걸음을 꺼내 실으면 **조회 한 번으로 가림이 풀린다.**
     *
     * **`SOURCE_MISSING` 줄에도 `steps` 키가 없다.** 이쪽은 가려서가 아니라 **승인할 것이 없어서**다 —
     * 자리를 고르는 것은 주문을 고치는 쪽의 일이다. 키를 맞추려고 빈 배열을 적으면 «승인하면 되는
     * 조치인데 걸음이 비었다» 로 읽힌다. 판 8 의 셋(`SOURCE_UNOBSERVED`·`DESTINATION_OCCUPIED`·`DESTINATION_UNOBSERVED`)도 같다 —
     * 사람의 확인이나 치움을 기다리는 답이지 승인할 조치가 아니다.
     */
    private fun line(r: RemedySearchRecord): String {
        val o = Obj()
            .str("searchId", r.searchId)
            .str("robotId", r.robotId)
            .str("jobOrderId", r.jobOrderId)
            .str("at", r.at.toString())
            .str("wallClockAt", r.wallClockAt.toString())
        return when (val outcome = r.outcome) {
            is RemedyOutcome.Found -> o
                .str("outcome", "FOUND")
                .raw("steps", outcome.steps.joinToJson { step(it) })
            is RemedyOutcome.None -> o
                .str("outcome", "NONE")
                .str("cause", outcome.cause.name)
                .raw("unmet", outcome.unmet.joinToJson { violation(it) })
            RemedyOutcome.Withheld -> o.str("outcome", "WITHHELD")
            is RemedyOutcome.SourceMissing -> o
                .str("outcome", "SOURCE_MISSING")
                .str("material", outcome.material)
                .str("source", outcome.source)
                // **널과 빈 문자열이 다르다.** 널은 빈 자리이고, 값이 있으면 거기 있던 다른 신원이다.
                .str("observed", outcome.observed)
                // **널과 빈 목록이 다르다.** 널은 셀이 그 질문에 답하지 않은 것이다.
                .raw("alternatives", outcome.alternatives?.let { arrayOfStrings(it) } ?: "null")
            is SourceUnobserved -> o
                .str("outcome", "SOURCE_UNOBSERVED")
                .str("material", outcome.material)
                .str("source", outcome.source)
                // **3값이다.** 널은 설비가 말이 없었던 것이고, 참은 «있다» 고만 했던 것이다(무엇인지는 모른다).
                .raw("occupied", outcome.occupied?.toString() ?: "null")
            is RemedyOutcome.DestinationOccupied -> o
                .str("outcome", "DESTINATION_OCCUPIED")
                .str("destination", outcome.destination)
                // 널은 «있다» 고만 한 것이다 — 점유 신원 불명.
                .str("observed", outcome.observed)
            is RemedyOutcome.DestinationUnobserved -> o
                .str("outcome", "DESTINATION_UNOBSERVED")
                .str("destination", outcome.destination)
        }.done()
    }

    private fun event(e: ObservedEvent): String = Obj()
        .num("sequence", e.sequence)
        .str("occurredAt", e.occurredAt)
        .str("kind", e.kind)
        .str("detail", e.detail)
        .bool("local", e.local)
        .done()

    /** 결함 하나 — 정준 분류와 벤더 원문을 함께. 빈 값도 키를 빼지 않고 빈 문자열로 적는다. */
    private fun fault(f: FaultDetail): String = Obj()
        .str("failureClass", f.failureClass)
        .str("errorType", f.errorType)
        .str("vendorDetail", f.vendorDetail)
        .str("errorHint", f.errorHint)
        .raw("references", f.references.joinToJson { reference(it) })
        .bool("canContinueCurrentTask", f.canContinueCurrentTask)
        .bool("canAcceptNewTask", f.canAcceptNewTask)
        .str("activeUntilKind", f.activeUntilKind)
        .str("activeUntilTime", f.activeUntilTime)
        .done()

    private fun reference(r: FaultReference): String = Obj()
        .str("key", r.key)
        .str("value", r.value)
        .done()

    /**
     * 무엇을 하려던 일이었나 — 상류가 적은 것과 이 층이 편 것을 함께.
     *
     * 판 8 부터 임무 버전(`missionVersion`, 널은 코드 정의 — 실행이 쥔 버전)과 현장 설정 셋(`siteSettingsVersion`·`inDoubtGrace`·`stallWindow`)을
     * **평평하게** 싣는다(§15.322). 같은 설정에서 온 근거 윈도우 폭 둘이 이미 평평하게 나가므로 셋만 묶으면 넷 중 둘만 묶인다. 셋은 **함께
     * 널이거나 함께 있다** — 여기서 단언한다. 봉인 라운드의 값이고, 과거 근거 기한을 정한 설정이라고 읽으면 안 된다.
     */
    private fun intent(i: Intent): String = Obj()
        .str("workMasterId", i.workMasterId)
        .num("orderVersion", i.orderVersion)
        .raw("orderParameters", mapOfStrings(i.orderParameters))
        .raw("materials", i.materials.joinToJson { material(it) })
        .raw("equipment", i.equipment.joinToJson { equipment(it) })
        .str("capabilityMaxEvidence", i.capabilityMaxEvidence.name)
        .str("evidenceWindowBefore", i.evidenceWindowBefore)
        .str("evidenceWindowAfter", i.evidenceWindowAfter)
        .str("skillType", i.skillType)
        .raw("unitParameters", mapOfStrings(i.unitParameters))
        .str("source", i.source)
        .str("destination", i.destination)
        .str("expectedIdentity", i.expectedIdentity)
        .raw("missionVersion", i.missionVersion?.toString() ?: "null")
        .also { check(listOf(i.siteSettingsVersion, i.inDoubtGrace, i.stallWindow).map { it == null }.distinct().size == 1) { "현장 설정 셋이 함께 널이거나 함께 있지 않다: $i" } }
        .raw("siteSettingsVersion", i.siteSettingsVersion?.toString() ?: "null")
        .str("inDoubtGrace", i.inDoubtGrace)
        .str("stallWindow", i.stallWindow)
        .done()

    private fun material(m: MaterialRequirement): String = Obj()
        .str("materialDefinitionId", m.materialDefinitionId)
        .num("quantity", m.quantity)
        .done()

    private fun equipment(e: EquipmentRequirement): String = Obj()
        .str("id", e.id)
        .str("equipmentUse", e.equipmentUse)
        .raw("properties", mapOfStrings(e.properties))
        .done()

    /** 관측이 온전했는가. **`progressObservable` 은 3값이라 널을 거짓으로 접지 않는다.** */
    private fun observation(o: ObservationTrust): String = Obj()
        .bool("linkBroken", o.linkBroken)
        .raw("lateEvents", o.lateEvents.joinToJson { lateEvent(it) })
        .raw("progressObservable", o.progressObservable?.toString() ?: "null")
        .bool("progressStalled", o.progressStalled)
        .done()

    private fun lateEvent(l: LateEvent): String = Obj()
        .str("unitId", l.unitId)
        .num("revision", l.revision)
        .num("currentRevision", l.currentRevision)
        .str("downstreamState", l.downstreamState)
        .str("occurredAt", l.occurredAt.toString())
        .done()

    /** 총수는 `plan` 의 길이다 — 따로 적지 않는다. */
    private fun step(s: StepPosition): String = Obj()
        .num("at", s.at)
        .raw("plan", arrayOfStrings(s.plan))
        .raw("completed", arrayOfStrings(s.completed))
        .done()

    private fun approver(a: Approver): String = Obj()
        .str("id", a.id)
        .str("kind", a.kind.name)
        .done()

    /**
     * 사람이 낸 판단. **시각을 둘 다 싣는다** — 가상 시계로 사건과 탐색 사이의 자리를 재고, 실 시계로
     * 현장과 댄다. 하나만 실으면 읽는 쪽이 둘 중 하나를 못 한다.
     *
     * **누가 판단했는지(`decidedBy`)를 판 8 부터 싣는다**(ADR 47, §15.322) — 같은 줄 `approvedBy` 와 같은 `{id, kind}` 모양이고 판단이 있으면
     * 언제나 있다. id 는 호출자의 주장이지 인증 증명이 아니다. 읽는 쪽은 원문 id 를 감사 조회·승인자 연결에서만 읽고 설명 생성 입력에는
     * 종류만 넘긴다(인계 노트).
     */
    private fun resolution(r: IncidentResolution): String = Obj()
        .str("decision", r.decision.name)
        .str("at", r.at.toString())
        .str("wallClockAt", r.wallClockAt.toString())
        .raw("decidedBy", approver(r.decidedBy))
        .raw("reworkBasis", r.reworkBasis?.let { reworkBasis(it) } ?: "null")
        .done()

    /** 재작업 근거(판 9, §15.323). 판단 기록과 달리 **빈 칸도 널로 싣는다** — 이 인코더의 원칙이다(«물었으나 침묵» 은 `occupied: null`). */
    private fun reworkBasis(b: ReworkBasis): String = Obj()
        .str("robotId", b.robotId)
        .raw("hold", b.hold?.let { Obj().str("kind", it.kind.name).str("objectRef", it.objectRef).done() } ?: "null")
        .raw("source", b.source?.let { slot(it) } ?: "null")
        .raw("destination", b.destination?.let { slot(it) } ?: "null")
        .str("readAt", b.readAt.toString())
        .done()

    private fun slot(s: ReworkSlot): String = Obj()
        .str("slotId", s.slotId)
        .raw("occupied", s.occupied?.toString() ?: "null")
        .str("identity", s.identity)
        .str("observedAt", s.observedAt?.toString())
        .done()

    private fun review(r: IncidentReview): String = Obj()
        .str("verdict", r.verdict.name)
        .str("cause", r.cause)
        .str("at", r.at.toString())
        .done()

    private fun step(s: RemedyStep): String = Obj()
        .str("skillType", s.skillType)
        .raw("requires", s.requires.joinToJson { proto(it) })
        .str("expectedHold", s.expectedHold.name)
        .str("onFailureHold", s.onFailureHold.name)
        .done()

    private fun violation(v: PreconditionViolation): String = Obj()
        .str("subject", v.subject.name)
        .str("required", v.required.name)
        .str("observed", v.observed.name)
        .str("detail", v.detail)
        .done()

    /** 계약 메시지는 protobuf JSON 규약으로. **기본값도 적는다** — 위의 이유다. */
    internal fun proto(message: MessageOrBuilder): String = PRINTER.print(message)

    private val PRINTER: JsonFormat.Printer = JsonFormat.printer()
        .alwaysPrintFieldsWithNoPresence()
        .omittingInsignificantWhitespace()

    private fun <T> List<T>.joinToJson(of: (T) -> String): String =
        joinToString(",", "[", "]") { of(it) }

    internal fun arrayOfStrings(values: List<String>): String =
        values.joinToString(",", "[", "]") { Json.quote(it) }

    /** **키 순서로 적는다** — 순회 순서가 줄을 바꾸면 같은 사건이 두 모양으로 나간다. */
    internal fun mapOfStrings(values: Map<String, String>): String = values.entries
        .sortedBy { it.key }
        .joinToString(",", "{", "}") { "${Json.quote(it.key)}:${Json.quote(it.value)}" }

}

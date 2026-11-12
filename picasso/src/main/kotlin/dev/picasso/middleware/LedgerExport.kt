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
     */
    const val SCHEMA_VERSION: String = "6"

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

    fun manifest(
        runId: String,
        writtenAt: Instant,
        virtualNow: Instant,
        incidents: Int,
        remedySearches: Int,
    ): String = Obj()
        .str("schemaVersion", SCHEMA_VERSION)
        .str("runId", runId)
        .str("writtenAt", writtenAt.toString())
        .str("virtualNow", virtualNow.toString())
        .str("contractSemver", ContractIdentity.semver)
        .raw("counts", Obj().num("incidents", incidents).num("remedySearches", remedySearches).done())
        .done()

    private fun line(b: IncidentBundle): String = Obj()
        .str("incidentId", b.incidentId)
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
     * 조치인데 걸음이 비었다» 로 읽힌다.
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
     * **임무 버전(`missionVersion`)은 안 싣는다.** 프로세스 안의 인시던트 번들에만 있다 — 읽는 쪽이 생기면 버전을 올려 싣는다.
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
     * **누가 판단했는지(`decidedBy`)는 안 싣는다**(ADR 47). 읽는 쪽이 아직 없고(ADR 9), 실으면 판이 오르고 인계본이
     * 다시 산출된다 — 읽는 쪽이 생기는 날 사건의 `approvedBy` 와 같은 모양으로 싣는다.
     */
    private fun resolution(r: IncidentResolution): String = Obj()
        .str("decision", r.decision.name)
        .str("at", r.at.toString())
        .str("wallClockAt", r.wallClockAt.toString())
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

package dev.picasso.middleware

import dev.picasso.capability.HoldEffects
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.middleware.MiddlewareCodes.OPEN_UNIT_STATES
import dev.picasso.middleware.MiddlewareCodes.PREDECESSOR_NOT_DONE
import dev.picasso.middleware.MiddlewareCodes.REWORK_NOTED
import dev.picasso.middleware.MiddlewareCodes.REWORK_RECHECK
import dev.picasso.middleware.MiddlewareCodes.REWORK_REFUSED
import java.time.Duration
import java.time.Instant

/**
 * 재작업 후보의 분류(§15.323) — 무엇이 실패해 무엇을 다시 내는가.
 *
 * - [SKIPPED] — 파지 효과 없는 단위(이동)가 시도 뒤 실패해 그 뒤를 기다린 단위가 건너뛰어졌다(ADR 53 의 건너뛴 대상). 대상은 처음 건너뛴 단위다.
 * - [ATTEMPTED_INSPECTION] — 파지 효과 없는 단위(점검·이동)가 시도 뒤 실패했다. 손·자리를 묻지 않는다.
 * - [ATTEMPTED_HANDLING] — 파지 전제·효과가 있는 단위(`pick_place` 계열)가 시도 뒤 실패했거나(첫 발행 직전 재검사를 못 지난 시도 포함), 앞 시도에서
 *   태스크가 나간 그런 단위가 묶음에 있다. 받을 때 물리 관문(손·목적지·출발·예약·구역)을 지나야 한다.
 */
enum class ReworkKind { SKIPPED, ATTEMPTED_INSPECTION, ATTEMPTED_HANDLING }

/**
 * 보류가 아닌 단위 가운데 **지금 재작업을 받을 모양인 것** 하나(§15.323, [Middleware.reworkCandidates]). 메모리 조건만 본 답이다 — 모양, 실행의 종결,
 * 같은 기체의 다른 활성 실행, 묶음 규칙. 포트를 읽지 않으므로 발행 관문과 물리 관문은 [Middleware.resolve] 때에만 답한다. 후보에 올라도 그때
 * `Refused` 일 수 있고, 그 사유 문장이 답이다.
 *
 * @param unitId 판단을 낼 대상. 묶음의 어느 단위 id 로 내도 같은 묶음이다.
 * @param attempt 대상의 지금 시도. 받으면 묶음의 단위마다 하나 오른다.
 * @param bundle 함께 되돌릴 단위 id, 계획 순서.
 * @param incidentUnitId 판단이 붙을 인시던트의 단위. 붙을 인시던트가 없으면 널이다.
 */
data class ReworkCandidate(
    val executionId: String,
    val unitId: String,
    val attempt: Int,
    val bundle: List<String>,
    val kind: ReworkKind,
    val incidentUnitId: String?,
)

/**
 * **재작업 일반화**(§15.323, §15.236 의 닫힘) — 운영자 보류가 아닌 단위의 재작업을 «묶음» 으로 받는다. ADR 53 의 건너뛴 쌍은 그 특수형이다.
 * 제 클래스로 두고 실행 표·장부는 [Middleware] 의 것을 쓴다. 공개 창구는 [Middleware.resolve] 와 [Middleware.reworkCandidates] 이고, 구동은 첫 발행
 * 직전에 [halts] 를, 재기동은 판단 기록을 옮긴 뒤 [restored] 를 부른다.
 *
 * ## 모양
 *
 * 시도된 실패(`FAILED` ∧ 이 시도의 태스크 있음, 로봇 경로)와 첫 발행 직전 재검사를 못 지난 시도(`FAILED` ∧ [REWORK_RECHECK] ∧ 태스크 빔)가 묶음의
 * 뿌리다. 건너뛴 단위(`FAILED` ∧ [PREDECESSOR_NOT_DONE] ∧ 태스크 빔)로 요청하면 선행을 따라 올라가 뿌리를 찾고, 파지 효과 없는 `DONE` 단위로 요청하면
 * 그것을 기다린 시도된 실패가 뿌리다. `UNVERIFIED`(보고서 12.3 — 놓였을 수 있다)·`ABORTED`·승인된 조치 단위(승인 기록의 걸음 id — 회복은 원래 단위의
 * 다음 시도만 본다)는 거부하고, 그 밖(`DONE`, 없는 단위, 열린 단위)은 [ResolveOutcome.NotHeld] 다.
 *
 * ## 묶음
 *
 * 위로는 뿌리의 선행([ExecutionUnit.after])이 파지 효과 없는 단위면 상태와 무관하게(`DONE` 이어도) 함께 되돌린다 — 순회가 이어져 기체가 그 자리에 없다.
 * 자재를 다룬 선행은 건드리지 않는다. 아래로는 묶음을 기다려 건너뛰어진 단위를 전이 폐쇄로 함께 되돌린다. 기다린 후손이 이 시도에서 이미 나갔으면
 * 요청 전체를 거부하고, 태스크 없는 다른 분류의 후손은 묶음 밖이다(리비전 몫). 묶음은 계획에서 이어진 구간이어야 한다. 한 요청 한 묶음이다.
 *
 * ## 판단과 근거
 *
 * 받으면 묶음의 단위마다 판단(증가 전 시도)을 적고 [reworkAsNewAttempt] 로 되돌린다. 뿌리의 판단에는 언제나 사건 정체성([IncidentOrigin])과 근거
 * ([ReworkBasis])가 실린다 — 물리 관문을 지난 단위는 읽은 손·자리를, 그 밖은 `{robotId, readAt}` 표지만. 물리 관문은 묶음에서 «관문이 필요한» `pick_place`
 * 계열 단위(뿌리, 또는 앞 시도에서 태스크가 나간 단위) 가운데 계획상 첫 하나에만 접수 때 걸고, 나머지는 표지 근거를 실어 첫 발행 직전 재검사가 본다.
 * 판단은 묶음 안의 판단을 기다리는 인시던트마다 붙고(근거는 뿌리·관문을 지난 단위의 것만), 붙을 곳이 없으면 자취 한 줄이다. 거부는 아무것도 바꾸지 않고
 * 사유를 [ResolveOutcome.Refused] 에, 자취에 한 줄([REWORK_REFUSED])을 남긴다.
 */
internal class ReworkDesk(
    private val env: CommandEnv,
    private val gate: AdmissionGate,
    private val incidentLog: IncidentLog,
    /** [Middleware] 가 든 실행 표 — 같은 인스턴스로 받아 읽기만 한다. */
    private val executions: Map<String, Execution>,
) {

    /**
     * (실행, 단위) 가운데 **앞 시도에서 태스크가 나간** 단위(§15.323). 재작업이 새 시도로 되돌릴 때 그 시도에 태스크가 있었으면 선다. 판단 기록의 단위 행이
     * 싣고([withIssued]) 재기동이 옮긴다([restored]). 뒤에 건너뛴 묶음으로 되돌려도 물리 관문을 다시 걸기 위한 표시다. 단위의 칸이 아니라 여기 둔다 —
     * 단위의 가변 칸은 구조 예산이 막는다. 장부에서 내린 실행의 줄은 다음에 적을 때 내린다.
     */
    private val issuedBefore = mutableSetOf<Pair<String, String>>()

    /** 첫 발행 직전 재검사가 (실행, 단위, 시도)에서 처음 «아직 못 읽음» 을 본 시각. 메모리이고 재기동하면 다시 센다. */
    private val unreadSince = mutableMapOf<Triple<String, String, Int>, Instant>()

    /** 묶음 하나. [root] 는 이 시도에서 실패한 단위, [target] 은 판단의 대상, [units] 는 함께 되돌릴 단위(계획 순서), [gated] 는 물리 관문이 필요한 단위다. */
    private class Bundle(val root: ExecutionUnit, val target: ExecutionUnit, val units: List<ExecutionUnit>, val kind: ReworkKind, val gated: List<ExecutionUnit>)

    private sealed interface Shape {
        data object None : Shape
        data class Refusal(val reason: String) : Shape
        class Found(val bundle: Bundle) : Shape
    }

    private sealed interface Reading {
        class Passed(val basis: ReworkBasis) : Reading
        class Blocked(val reason: String, val detail: String) : Reading

        /** 기체 스냅숏이나 선언을 아직 못 읽었다 — 기체가 «모른다» 고 답한 것이 아니다. */
        class Unread(val reason: String, val detail: String) : Reading
    }

    /** [Middleware.reworkCandidates] — 묶음 하나에 후보 하나, 실행 표 순서와 계획 순서로. */
    fun candidates(): List<ReworkCandidate> = executions.values.flatMap { execution ->
        val seen = mutableSetOf<String>()
        execution.units.mapNotNull { unit ->
            val bundle = (shapeOf(execution, unit) as? Shape.Found)?.bundle ?: return@mapNotNull null
            if (!seen.add(bundle.root.unitId) || standing(execution, bundle) != null) return@mapNotNull null
            val incident = (listOf(bundle.root) + bundle.units).firstOrNull { incidentLog.awaitsDecision(execution.executionId, it.unitId) }
            ReworkCandidate(execution.executionId, bundle.target.unitId, bundle.target.attempt, bundle.units.map { it.unitId }, bundle.kind, incident?.unitId)
        }
    }

    /**
     * [Middleware.resolve] 의 보류가 아닌 단위 경로. 확인([OperatorDecision.CONFIRM_DONE])은 받지 않는다 — 하지 않은 점검은 확인할 것이 없고, 설비 근거가
     * 없는 실패를 확인으로 완료하지 않는다. 받는 조건은 ADR 53 의 다섯(앞의 둘은 [Middleware] 가 본다)에 발행 관문과, 관문이 필요한 `pick_place` 계열
     * 단위가 있으면 물리 관문이 더해진다. 기체 스냅숏은 기체마다 한 번 읽어 결함과 손이 같은 관측을 쓴다.
     */
    fun resolve(execution: Execution, unitId: String, decision: OperatorDecision, approver: Approver): ResolveOutcome {
        val unit = execution.units.firstOrNull { it.unitId == unitId } ?: return ResolveOutcome.NotHeld
        val bundle = when (val shape = shapeOf(execution, unit)) {
            Shape.None -> return ResolveOutcome.NotHeld
            is Shape.Refusal -> return refuse(execution, unitId, shape.reason)
            is Shape.Found -> shape.bundle
        }
        if (decision == OperatorDecision.CONFIRM_DONE) {
            return refuse(
                execution, unitId,
                if (skipped(unit)) "점검을 하지 않은 단위는 확인할 것이 없다 — 재작업만 받는다: $unitId" else "설비 근거가 없는 실패는 확인으로 완료하지 않는다 — 재작업만 받는다: $unitId",
            )
        }
        standing(execution, bundle)?.let { return refuse(execution, unitId, it) }
        val robots = bundle.units.filter { it.route == Route.ROBOT }.mapTo(sortedSetOf()) { execution.robotOf(it) }
        val snapshots = robots.associateWith { gate.snapshotOf(it) }
        issueRefusal(execution, robots, snapshots)?.let { return refuse(execution, unitId, it) }
        // 근거는 시도를 올리기 전에 단위마다 정한다 — 관문은 관문이 필요한 첫 단위 하나에, 나머지와 뿌리는 표지.
        val bases = linkedMapOf<ExecutionUnit, ReworkBasis>()
        // 관측 없이 아는 거부는 관문이 필요한 단위 전부에 건다 — 뒤 단위가 발행 직전에야 같은 사유로 닫히지 않게. 관측은 첫 단위만 읽는다.
        bundle.gated.firstNotNullOfOrNull { staticRefusal(it) }?.let { return refuse(execution, unitId, it) }
        bundle.gated.firstOrNull()?.let { first ->
            val robot = execution.robotOf(first)
            when (val read = physical(execution, first, bundle.units, snapshots[robot], reissue = true)) {
                is Reading.Blocked -> return refuse(execution, unitId, read.reason, read.detail)
                is Reading.Unread -> return refuse(execution, unitId, read.reason, read.detail)
                is Reading.Passed -> bases[first] = read.basis
            }
        }
        (bundle.gated + bundle.root).forEach { bases.getOrPut(it) { ReworkBasis(execution.robotOf(it), readAt = env.now()) } }
        accept(execution, bundle, bases, approver)
        return ResolveOutcome.Resolved
    }

    /** 보류 판단의 재작업([Middleware.resolve])과 묶음이 쓰는 되돌림. 그 시도에 태스크가 있었으면 «앞 시도에 태스크가 나갔음» 을 적는다. */
    fun renew(execution: Execution, unit: ExecutionUnit) {
        issuedBefore.removeAll { it.first !in executions }
        if (unit.taskId.isNotEmpty()) issuedBefore += execution.executionId to unit.unitId
        env.reworkAsNewAttempt(execution, unit)
    }

    /**
     * **첫 발행 직전 재검사**(§15.323). [next] 가 재작업으로 받은 `pick_place` 계열 단위의 새 시도이고(판단이 `REWORK` ∧ 판단 시도 == 단위 시도−1 ∧
     * 판단에 근거 있음 ∧ 태스크 빔 ∧ `PENDING` — 재기동 뒤에도 같은 식이다) 지금 읽은 손·목적지·출발이 물리 관문을 못 지나면 내지 않고 그 시도를 `FAILED`
     * ([REWORK_RECHECK], 태스크 빔)로 닫아 참을 돌려준다. 실패 인시던트를 사유와 함께 연다. 뒤 단위는 보통 규칙(선행 미완료 건너뜀)대로 가고 실행은
     * 종결한다 — 사람이 다시 [OperatorDecision.REWORK] 를 내면 관문을 다시 지나 시도가 하나 오른다.
     *
     * **스냅숏이나 선언을 아직 못 읽었으면 기다린다**(참, 상태는 그대로). 받은 재작업의 관측을 확인하는 것이지 새 판단이 아니다(ADR 47). 처음 못 읽은
     * 시각부터 진행 정체 유예([CommandEnv.stallWindowOf])를 넘기면 실패로 닫는다. 기체가 손을 «관측 불가·말하지 않음» 으로 답했거나 든 채·자리 점유·결품·
     * 침묵이면 곧바로 실패다. 재기동 뒤 기체가 그 시도의 태스크를 이미 들고 있으면([Execution.unattached]) 보지 않는다 — 재부착·재전송의 규율이 맡는다.
     * 함께 되돌린 점검·이동은 다시 보지 않는다. 자리는 `startUnit` 바로 앞(결함·허가·선언 대기 뒤)이다.
     */
    fun halts(execution: Execution, next: ExecutionUnit): Boolean {
        // 기다리던 단위가 사라졌거나(장부에서 내림) 취소가 걸렸거나 더 기다리지 않는 줄은 내린다 — 메모리가 새지 않게.
        unreadSince.keys.removeAll { (id, unitId, attempt) ->
            val e = executions[id]
            e == null || e.cancelRequested || e.units.none { it.unitId == unitId && it.attempt == attempt && it.state == UnitState.PENDING }
        }
        if (next.route != Route.ROBOT || inert(next) || next.state != UnitState.PENDING || next.taskId.isNotEmpty()) return false
        if (execution.unattached[next.unitId] == next.attempt) return false
        val decided = execution.decisions[next.unitId] ?: return false
        if (decided.decision != OperatorDecision.REWORK || decided.attempt != next.attempt - 1 || decided.reworkBasis == null) return false
        val key = Triple(execution.executionId, next.unitId, next.attempt)
        val read = physical(execution, next, listOf(next), gate.snapshotOf(execution.robotOf(next)), reissue = false)
        val (reason, detail) = when (read) {
            is Reading.Passed -> {
                unreadSince.remove(key)
                return false
            }
            is Reading.Blocked -> read.reason to read.detail
            is Reading.Unread -> {
                val now = env.now()
                val since = unreadSince.getOrPut(key) {
                    with(env) { execution.trail(REWORK_RECHECK, "${next.unitId}: attempt ${next.attempt} — ${read.reason}, 읽힐 때까지 내지 않고 기다린다 [${read.detail}]") }
                    now
                }
                val window: Duration = env.stallWindowOf(execution)
                if (!now.isAfter(since.plus(window))) return true
                "${read.reason} — 기다림 창 $window 를 넘겼다" to read.detail
            }
        }
        unreadSince.remove(key)
        next.state = UnitState.FAILED
        next.failureClass = REWORK_RECHECK
        next.note = "재작업 시도 ${next.attempt} 를 내기 직전에 다시 읽은 손·자리가 관문을 못 지나 내지 않았다 — $reason"
        execution.markIncident(next)
        with(env) { execution.trail(REWORK_RECHECK, "${next.unitId}: attempt ${next.attempt} — $reason [$detail]") }
        return true
    }

    /**
     * 재기동의 몫(§15.323) — [Middleware.resume] 이 스냅숏으로 시도를 정하고(`rebuildFrom`) 판단 기록을 옮긴 **뒤에** 부른다. 둘을 바로잡는다.
     *
     * - 재작업·회복이 낸 시도(판단이 `REWORK` ∧ 판단 시도 < 단위 시도, 또는 회복 승인의 위치 칸이 그 단위이고 시도가 거부된 시도보다 큼)는 «앞선 로봇
     *   단위» 가 아니다. 뒤 단위의 태스크가 기체에 있어 앞선 것으로 두었으면 빼낸다 — 두면 새 시도의 성공이 설비 근거 없이 닫힌다. 재작업 뒤 회복
     *   승인이 시도를 하나 더 올려도(판단 시도 0, 단위 시도 2) 같다.
     * - 기록의 «앞 시도에 태스크가 나갔음» 을 옮긴다. 스냅숏이 기록보다 큰 시도를 보이면 그 시도는 나간 것이라 선다.
     */
    fun restored(execution: Execution, record: JudgmentRecord) {
        val byId = execution.units.associateBy { it.unitId }
        record.units.forEach { r ->
            val unit = byId[r.unitId] ?: return@forEach
            if (r.issuedInEarlierAttempt || r.attempt < unit.attempt) issuedBefore += execution.executionId to unit.unitId
            val decided = execution.decisions[unit.unitId]
            val reworked = decided?.decision == OperatorDecision.REWORK && decided.attempt < unit.attempt
            val recovered = execution.approval?.position?.let { it.unitId == unit.unitId && unit.attempt > it.attempt } == true
            if (reworked || recovered) execution.resumedPrior -= unit.unitId
        }
    }

    /** 판단 기록에 «앞 시도에 태스크가 나갔음» 을 싣는다([Middleware.judgmentRecord]). */
    fun withIssued(execution: Execution, record: JudgmentRecord): JudgmentRecord =
        record.copy(units = record.units.map { it.copy(issuedInEarlierAttempt = (execution.executionId to it.unitId) in issuedBefore) })

    /** 받는다. 판단마다 증가 전 시도를 단다. 작업 응답은 [CommandEnv.notify] 로 직접 낸다 — 주의 대조는 `PARTIAL`·`FAILED` 에서 `RUNNING` 으로를 못 잡는다. */
    private fun accept(execution: Execution, bundle: Bundle, bases: Map<ExecutionUnit, ReworkBasis>, approver: Approver) {
        val at = env.now()
        val root = bundle.root
        val observed = bundle.gated.firstOrNull()
        // 사건 정체성은 되돌리기 전에 뿌리의 것을 떠 둔다 — 요청한 id 와 무관하다.
        val incident = incidentLog.origin(execution.executionId, root.unitId)
        val open = bundle.units.filter { incidentLog.awaitsDecision(execution.executionId, it.unitId) }
        for (unit in bundle.units) {
            execution.decisions[unit.unitId] = RecordedDecision(OperatorDecision.REWORK, approver, at, unit.attempt, bases[unit], incident.takeIf { unit === root })
            renew(execution, unit)
        }
        execution.noticed.clear(Notice.Hold)
        execution.physicalState = PhysicalState.RUNNING
        env.notify(execution)
        open.forEach {
            val basis = bases[it].takeIf { _ -> it === root || it === observed }
            incidentLog.noteResolution(execution.executionId, it.unitId, IncidentDecision.REWORK, approver, reworkBasis = basis)
        }
        if (open.isEmpty()) {
            with(env) { execution.trail(REWORK_NOTED, "${bundle.target.unitId}: ${bundle.units.joinToString { it.unitId }} — 판단을 기다리는 인시던트가 없어 자취에만 남긴다, by ${approver.id}") }
        }
    }

    /** [unit] 이 속한 묶음. 뿌리를 찾고 묶음을 짓는다([bundleOf]). */
    private fun shapeOf(execution: Execution, unit: ExecutionUnit): Shape {
        val byId = execution.units.associateBy { it.unitId }
        var at = unit
        val walked = mutableSetOf<String>()
        while (walked.add(at.unitId)) {
            refusalOf(execution, at)?.let { return Shape.Refusal(it) }
            when {
                attempted(at) -> return bundleOf(execution, at)
                skipped(at) -> at = at.after?.let { byId[it] } ?: return Shape.None
                at === unit && at.state == UnitState.DONE && inert(at) ->
                    return execution.units.firstOrNull { it.after == at.unitId && attempted(it) }?.let { bundleOf(execution, it) } ?: Shape.None
                else -> return Shape.None
            }
        }
        return Shape.None
    }

    private fun bundleOf(execution: Execution, root: ExecutionUnit): Shape {
        val units = execution.units
        val members = linkedSetOf(root)
        units.firstOrNull { it.unitId == root.after }?.takeIf { inert(it) }?.let { members += it }
        val queue = ArrayDeque(members.toList())
        while (queue.isNotEmpty()) {
            val waited = queue.removeFirst()
            for (later in units.filter { it.after == waited.unitId && it !in members }) {
                if (skipped(later)) {
                    members += later
                    queue += later
                } else if (later.taskId.isNotEmpty()) {
                    return Shape.Refusal("${waited.unitId} 를 기다린 ${later.unitId} 가 이번 시도에 이미 나갔다 — 함께 되돌리면 그 단위가 다시 돈다, 새 작업 지시로 낸다")
                }
            }
        }
        val ordered = units.filter { it in members }
        val at = ordered.map { units.indexOf(it) }
        if (at.last() - at.first() + 1 != ordered.size) {
            return Shape.Refusal("함께 되돌릴 단위(${ordered.joinToString { it.unitId }})가 계획에서 이어져 있지 않다 — 사이의 단위가 기체의 자리를 바꾼다, 새 작업 지시로 낸다")
        }
        // 관문이 필요한 단위 — 뿌리이거나 앞 시도에서 태스크가 나간 `pick_place` 계열(그 시도에서 쥐었을 수 있다).
        val gated = ordered.filter { !inert(it) && it.route == Route.ROBOT && (it === root || (execution.executionId to it.unitId) in issuedBefore) }
        val skippedFirst = ordered.firstOrNull { skipped(it) }
        val kind = when {
            gated.isNotEmpty() -> ReworkKind.ATTEMPTED_HANDLING
            skippedFirst != null -> ReworkKind.SKIPPED
            else -> ReworkKind.ATTEMPTED_INSPECTION
        }
        val target = when (kind) {
            ReworkKind.ATTEMPTED_HANDLING -> gated.first()
            ReworkKind.SKIPPED -> skippedFirst!!
            ReworkKind.ATTEMPTED_INSPECTION -> root
        }
        return Shape.Found(Bundle(root, target, ordered, kind, gated))
    }

    /** 모양만으로 거부하는 단위의 사유. 아니면 널이다. 조치 단위는 이름이 아니라 승인 기록의 걸음 id 로 가른다 — 일반 대상도 `remedy-` 로 시작할 수 있다. */
    private fun refusalOf(execution: Execution, unit: ExecutionUnit): String? = when {
        unit.state in ENDED && execution.approval?.steps.orEmpty().any { it.unitId == unit.unitId } ->
            "조치 단위는 재작업하지 않는다 — 회복은 원래 단위의 다음 시도만 본다: ${unit.unitId}"
        unit.state == UnitState.UNVERIFIED -> "확인되지 않은 단위는 재작업하지 않는다 — 놓였을 수 있다, 운영자가 현장을 확인한다(보고서 12.3): ${unit.unitId}"
        unit.state == UnitState.ABORTED -> ABORTED_REFUSAL
        else -> null
    }

    /**
     * 실행 쪽 조건(ADR 53). 취소한 실행은 받지 않는다. 실행이 `PARTIAL`·`FAILED` 로 종결하고 열린 단위가 없어야 한다 — 도는 중에 앞 대상을 되돌리면
     * 다음 단위가 앞 대상 뒤에 나가 다른 대상의 일이 앞 대상의 자리에서 나간다. 묶음의 기체를 쥔 다른 활성 실행이 없어야 한다 — `FAILED` 는 기체를
     * 내주므로 그 사이 다른 실행이 섰을 수 있다.
     */
    private fun standing(execution: Execution, bundle: Bundle): String? {
        val state = execution.physicalState
        if (state == PhysicalState.ABORTED) return ABORTED_REFUSAL
        if ((state != PhysicalState.PARTIAL && state != PhysicalState.FAILED) || execution.units.any { it.state in OPEN_UNIT_STATES }) {
            return "실행이 종결한 뒤 한 대상씩 재작업한다 — 아직 낼 단위나 도는 단위가 있다"
        }
        val robots = bundle.units.mapTo(HashSet()) { execution.robotOf(it) }
        val other = executions.values.firstOrNull {
            it.executionId != execution.executionId && it.physicalState.occupiesRobot && it.robots.any { robot -> robot in robots }
        }
        return other?.let { "같은 기체에 다른 활성 실행이 있다 — 그 실행이 끝난 뒤 재작업한다: ${it.executionId}" }
    }

    /**
     * 발행 관문(§15.323) — 접수 때 [IssuingGate.check] 가 열려 있고, 묶음의 기체에 수용하지 않은 «새 태스크를 못 받는다» 결함이 없어야 한다. 받아 놓고
     * 다음 라운드에 막히면 실행이 `RUNNING` 인 채로 아무것도 안 나간다. 결함은 기체 뷰가 아니라 **이 판단에서 읽은 스냅숏**([snapshots], 물리 관문의 손과
     * 같은 읽기)으로 본다 — 종결한 실행의 기체 뷰는 구동이 다시 읽지 않아 굳어 있을 수 있다. 스냅숏을 못 읽었거나 결함을 못 보는 기체는 막지 않는다 —
     * 받은 뒤의 구동이 결함 차단(`blockedBy`)으로 드러낸다.
     */
    private fun issueRefusal(execution: Execution, robots: Set<String>, snapshots: Map<String, RobotSnapshot?>): String? {
        if (robots.isEmpty()) return null
        (env.issuing.check(robots) as? IssueVerdict.Withheld)?.let { withheld ->
            val permits = withheld.robots.joinToString { it.robotId + "=" + it.permit.name }
            return "발행 관문이 막고 있다 — $permits (재작업하지 않았다, 허가가 돌아온 뒤 다시 낸다)"
        }
        for (robot in robots) {
            val snapshot = snapshots[robot]?.takeIf { it.faultsObservable } ?: continue
            val blocking = snapshot.faults.filter { !it.canAcceptNewTask && faultKey(it) !in execution.acknowledgedFaults }
            if (blocking.isNotEmpty()) return "기체 $robot 가 새 태스크를 못 받는다고 한다 — ${blocking.joinToString { faultKey(it) }} (재작업하지 않았다)"
        }
        return null
    }

    /**
     * **물리 관문**(§15.323) — `pick_place` 계열 대상만. 손은 [snapshot](기체 스냅숏)에서 굴려 `Clear` 이고 쥐는 대상이면 빈손 관측일 때만이다. 스냅숏이나
     * 선언을 못 읽었으면 [Reading.Unread], 기체가 손을 관측 불가·말하지 않음으로 답했으면 거부다(단위의 마지막 관측은 쓰지 않는다). 목적지는 선언이
     * `REQUIRED` 이고 `Free` 관측일 때만(`OPTIONAL` 이면 쥔 이력과 무관하게 거부), 출발은 `Ready` 관측일 때만이다(선언이 `OPTIONAL` 이어도 침묵은 거부 —
     * 다만 `OPTIONAL` 출발의 `Ready` 는 신원 없는 «있다» 일 수 있다). 받을 때([reissue])는 자기를 뺀 예약·구역도 본다([AdmissionGate.reissue]).
     */
    private fun physical(execution: Execution, target: ExecutionUnit, units: List<ExecutionUnit>, snapshot: RobotSnapshot?, reissue: Boolean): Reading {
        val robot = execution.robotOf(target)
        var hand: HoldState? = null
        var source: ReworkSlot? = null
        var destination: ReworkSlot? = null
        fun blocked(reason: String) = Reading.Blocked(reason, describe(hand, source, destination))
        staticRefusal(target)?.let { return blocked(it) }
        if (snapshot == null) return Reading.Unread("기체 $robot 의 스냅숏을 아직 못 읽었다 — 손을 모른다", describe(null, null, null))
        hand = gate.handOf(snapshot) ?: return blocked("기체 $robot 가 손을 말하지 않는다 — 빈손인지 확인할 수 없어 다시 내지 않는다")
        val handed = if (reissue) gate.reissue(robot, units, execution.executionId, hand) else gate.recheck(robot, units, hand, RECHECK_BASIS)
        when (handed) {
            AdmissionGate.Recheck.Unknown -> return Reading.Unread("기체 $robot 의 선언을 아직 못 읽었다 — 손에서 굴려 볼 수 없다", describe(hand, null, null))
            is AdmissionGate.Recheck.Violated -> return blocked(handed.rejection.reason)
            AdmissionGate.Recheck.Clear -> Unit
        }
        // 굴림은 선언한 사전 조건만 본다 — 쥐는 스킬에 손 조건을 선언하지 않은 기체에서는 든 채도 지난다. 쥐었다 실패한 물건이 손에 남았을 수 있으므로
        // 쥐는 대상은 빈손 관측을 따로 요구한다.
        if (HoldEffects.grasps(target.skillType) && hand.kind != HoldKind.HOLD_KIND_EMPTY) {
            return blocked("기체 $robot 의 손이 빈손이 아니다 — 쥐었다 실패한 물건이 남았을 수 있다 (다시 내지 않았다)")
        }
        val where = checkNotNull(target.destination)
        val placed = env.cell.observe(where)
        destination = slotOf(where, placed)
        when (val check = CellOccupancy.destinationCheck(placed)) {
            DestinationCheck.Free -> Unit
            is DestinationCheck.Occupied -> return blocked("목적지 $where 가 비어 있지 않다 — " + seen(check.observed, "설비가 있다고만 했다") + " (다시 내지 않았다)")
            DestinationCheck.Unobserved -> return blocked("목적지 $where 를 확인하지 못했다 — 설비가 말이 없다 (다시 내지 않았다)")
        }
        val from = checkNotNull(target.source)
        val picked = env.cell.observe(from)
        source = slotOf(from, picked)
        when (val check = CellOccupancy.sourceCheck(picked, target.expectedIdentity, target.sourceObservation)) {
            SourceCheck.Ready -> Unit
            is SourceCheck.Missing -> return blocked("출발 자리 $from 에 ${material(target)} 이 없다 — " + seen(check.observed, "비었다") + " (다시 내지 않았다)")
            else -> return blocked("출발 자리 $from 의 ${material(target)} 를 확인하지 못했다 — 설비가 말이 없다 (다시 내지 않았다)")
        }
        val held = ReworkHold(hand.kind, hand.objectRef.ifEmpty { null })
        return Reading.Passed(ReworkBasis(robot, held, source, destination, env.now()))
    }

    /** 관측 없이 아는 물리 관문의 거부 — 목적지 선언이 `REQUIRED` 가 아니거나 목적지·출발 자리가 없다. 없으면 널이다. */
    private fun staticRefusal(target: ExecutionUnit): String? = when {
        target.destinationObservation != DestinationObservation.REQUIRED ->
            "목적지를 물리 점유로 보지 않는 자리 — 쥐었다 실패했을 수 있는 단위는 새 작업 지시로 낸다: ${target.unitId}"
        target.destination == null -> "목적지가 없는 단위라 비었는지 볼 수 없다: ${target.unitId}"
        target.source == null -> "출발 자리가 없는 단위라 자재를 볼 수 없다: ${target.unitId}"
        else -> null
    }

    private fun seen(observed: String?, otherwise: String): String = if (observed == null) otherwise else "'$observed' 이 있다"

    private fun material(unit: ExecutionUnit): String = unit.expectedIdentity ?: "자재"

    private fun slotOf(slotId: String, signal: SlotSignal?) = ReworkSlot(slotId, signal?.occupied, signal?.identity, signal?.observedAt)

    private fun describe(hand: HoldState?, source: ReworkSlot?, destination: ReworkSlot?): String {
        fun slot(s: ReworkSlot?): String = if (s == null) NOT_READ else "${s.slotId} occupied=${s.occupied} identity=${s.identity} at=${s.observedAt}"
        val kind = hand?.kind?.name ?: UNKNOWN_HAND
        return "hand=$kind source=${slot(source)} destination=${slot(destination)} readAt=${env.now()}"
    }

    private fun refuse(execution: Execution, unitId: String, reason: String, detail: String? = null): ResolveOutcome.Refused {
        val read = if (detail == null) "" else " [$detail]"
        with(env) { execution.trail(REWORK_REFUSED, "$unitId: $reason$read") }
        return ResolveOutcome.Refused(reason)
    }

    private companion object {
        const val ABORTED_REFUSAL = "취소한 실행의 대상은 재작업하지 않는다 — 새 작업 지시로 낸다"
        const val RECHECK_BASIS = "발행 직전 기체 스냅숏의 파지"
        const val NOT_READ = "(not read)"
        const val UNKNOWN_HAND = "unknown"
        val ENDED = setOf(UnitState.FAILED, UnitState.UNVERIFIED, UnitState.ABORTED)

        /** 묶음의 뿌리 — 이 시도에서 태스크를 내고 실패한 로봇 단위, 또는 첫 발행 직전 재검사를 못 지나 태스크 없이 닫힌 시도. */
        fun attempted(unit: ExecutionUnit): Boolean = unit.state == UnitState.FAILED && unit.route == Route.ROBOT &&
            (unit.taskId.isNotEmpty() || unit.failureClass == REWORK_RECHECK)

        /** 선행이 완료되지 않아 이 시도에서 내지 않은 단위(ADR 53 의 건너뛴 단위 술어와 한 함수). */
        fun skipped(unit: ExecutionUnit): Boolean = unit.state == UnitState.FAILED && unit.failureClass == PREDECESSOR_NOT_DONE && unit.taskId.isEmpty()

        /** 파지 효과 없는 로봇 단위 — 손 종류를 바꾸지 않고([HoldEffects]) 아는 것을 놓지 않는다(`expectedIdentity` 없음). 점검·이동이다. */
        fun inert(unit: ExecutionUnit): Boolean =
            unit.route == Route.ROBOT && !HoldEffects.grasps(unit.skillType) && !HoldEffects.releases(unit.skillType) && unit.expectedIdentity == null
    }
}

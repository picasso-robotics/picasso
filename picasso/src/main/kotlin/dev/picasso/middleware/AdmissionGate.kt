package dev.picasso.middleware

import dev.picasso.capability.HoldEffects
import dev.picasso.capability.PreconditionCheck
import dev.picasso.capability.RemedySearch
import dev.picasso.contracts.v1.HoldKind
import dev.picasso.contracts.v1.HoldState
import dev.picasso.middleware.Middleware.Submission

/**
 * 배정 관문(설계안 §7) — 피어 시스템 접합부(배정·공간)의 접수 판정이 모이는 자리. 재할당의 판정은
 * [Middleware.reassign] 의 조율에 남는다.
 *
 * **상태를 소유하지 않는다.** [executions] 는 [Middleware] 가 든 표를 같은 인스턴스로 받아 읽기만 한다 —
 * 사본을 받으면 관문은 생성 때 뜬 빈 표만 보고, 자리 경쟁·작업 구역·사슬 검사는 아무것도 막지 않는다.
 * 기체의 선언은 [robots], 자리의 관측은 [cell], 바닥과 구역의 소유는 [floors]·[workspace] 가 답한다.
 */
internal class AdmissionGate(
    private val robots: RobotPort,
    private val cell: CellSignals,
    private val floors: FloorOwnership,
    private val workspace: Workspace,
    private val executions: Map<String, Execution>,
) {

    /**
     * **배정 관문**(설계안 §7) — 이 기체가 이 주문을 받을 수 있는가.
     *
     * **순수 술어다. 상태를 바꾸지 않고 후보를 고르지 않는다.** 부작용이 있으면 후보 셋을 물어보는
     * 것만으로 제안이 셋 쌓이고 가림 차례가 세 칸 돌아간다 — 묻는 것이 곧 결정이 된다. 그래서 제안의
     * 기록은 [RemedyDesk.record] 가 맡고, 채택을 시도한 쪽만 그것을 부른다.
     *
     * 고르지 않는 것도 같은 이유다. 순위는 배정 정책의 것이고, 여기서 고르기 시작하면 이 층이 배정기가 된다.
     *
     * [moving] 은 [Middleware.reassign] 만 준다 — **옮길 실행 자신**의 id 다. 그 실행은 아직 실행 표에 있어서,
     * 빼지 않으면 자기가 잡은 자리와 자기 구역에 걸려 어느 후보로도 못 간다. **실행 id 로만 뺀다.** 기체로 빼면
     * 떠나는 기체에 남는 다른 실행의 자리·구역까지 사라진다. 빼는 것은 묻는 범위이고 상태는 그대로다 — 순수 술어다.
     */
    fun admits(order: JobOrder, robotId: String, planned: List<ExecutionUnit>, moving: String? = null): Admission {
        inconsistent(order, planned.filter { it.unitId.startsWith("remedy-").not() })
            ?.let { return Admission.Refused(Submission.Rejected(it)) }
        chainRefusal(robotId, planned)?.let { return Admission.Refused(it) }
        // **이것만 답을 함께 든다.** 점유 관문은 거절하면서 «다른 자리» 를 계산하므로 그 값을 들려 보낸다.
        occupancyViolation(planned, moving)?.let { return it }
        unownedFloor(planned)?.let { return Admission.Refused(it) }
        workspaceViolation(robotId, planned, moving)?.let { return Admission.Refused(it) }
        return Admission.Passed
    }

    /**
     * **같은 작업 구역에서 두 기체가 동시에 일하지 않는다**(§15.153).
     *
     * 두 기체의 작업 반경이 겹치면 그것도 셀 전용 자원의 경쟁이다. 슬롯 점유가 «같은 자리에 둘을 놓지
     * 않는다» 라면 이것은 «같은 공간에 둘이 들어가지 않는다» 이고, 자원의 성질이 달라 세는 법도 다르다 —
     * 여기서는 `destination` 의 두 뜻(놓을 자리·갈 자리)이 **둘 다 센다.** 어느 쪽이든 기체가 그 공간을
     * 차지하기 때문이다.
     *
     * **같은 기체는 보지 않는다.** 한 기체가 두 자리에 동시에 있을 수 없고, 그 배타는 발신자가 든다.
     * [moving] 은 옮길 실행 자신이다([admits]).
     */
    private fun workspaceViolation(robotId: String, planned: List<ExecutionUnit>, moving: String?): Submission.Rejected? {
        for (unit in planned) {
            val zone = zoneOf(unit) ?: continue
            // 같은 기체는 단위가 묶인 기체다. 묶지 않은 단위는 이 주문을 받을 기체([robotId])다.
            val holder = liveZones(excluding = unit.robotBinding ?: robotId, moving = moving)[zone] ?: continue
            val where = unit.destination
            return Submission.Rejected(
                "작업 구역 $zone 에서 ${holder.robotId}(${holder.jobOrderId}) 가 일하고 있다 — " +
                    "$where 로 보내면 반경이 겹친다",
            )
        }
        return null
    }

    /**
     * 이 단위가 차지하는 구역. **구역을 모르면 `null` 이고 그때는 검사에서 빠진다.**
     *
     * 세는 쪽과 대는 쪽이 같은 함수를 쓴다 — 두 벌로 두면 한쪽만 «모름» 을 다르게 다루는 날이 오고,
     * 그때 구역 밖 자리끼리 서로를 막거나 겹치는 자리가 안 막힌다.
     */
    private fun zoneOf(unit: ExecutionUnit): String? = unit.destination?.let { workspace.zoneOf(it) }

    /**
     * 다른 기체들이 지금 쓰는 구역. 종착한 실행과 끝난 단위는 놓는다 — 자리 점유와 같은 규칙이다.
     * [moving] 은 기체가 아니라 그 실행 하나만 뺀다.
     */
    private fun liveZones(excluding: String, moving: String? = null): Map<String, ZoneUse> = executions.values
        .filter { !it.physicalState.isSettled && it.executionId != moving }
        .flatMap { execution ->
            // 구역을 쓰는 것은 단위가 묶인 기체다 — 실행의 기체로 세면 다른 기체에 묶인 단위의 구역이 엉뚱한 기체의 것이 된다.
            execution.units.filter { it.state != UnitState.DONE && execution.robotOf(it) != excluding }.mapNotNull { unit ->
                val zone = zoneOf(unit) ?: return@mapNotNull null
                ZoneUse(zone, execution.executionId, execution.order.jobOrderId, execution.robotOf(unit))
            }
        }
        .associateBy { it.zone }

    /**
     * **소유자 없는 자원에는 명령을 내지 않는다**(§15.154) — deny by default.
     *
     * 걷는 기체가 셀을 나가면 그 바닥의 소유자가 없고, 플릿은 자기 AMR 만 승인한다. 그러면 이 층이 내는
     * 명령이 아무 관문도 통과하지 않고 물리 세계로 나간다. 무승인 통행을 허용하는 선택지는 없으므로,
     * 소유자가 정해지기 전까지의 올바른 상태는 **그 구역으로 명령을 내지 않는 것**이다.
     *
     * **대장을 안 붙인 배치는 막지 않는다.** 「자리는 아는데 주인이 없다」와 「대장 자체가 없다」는 뜻이
     * 반대다 — 접으면 대장 없는 현장이 통째로 서고, 그러면 이 관문이 곧 꺼진다.
     */
    private fun unownedFloor(planned: List<ExecutionUnit>): Submission.Rejected? {
        for (unit in planned) {
            val where = unit.destination ?: continue
            if (floors.ownerOf(where) != FloorOwner.Unowned) continue
            return Submission.Rejected(
                "자리 $where 의 바닥에 소유자가 없다 — 승인할 쪽이 없으므로 보내지 않는다(자원 소유 레지스터)",
            )
        }
        return null
    }

    /**
     * 계획 시점 사슬 검사(설계안 §4) — 로봇이 선언한 사전 조건을 **접수 요청을 보내기 전에** 단위 사슬에 대고 본다.
     *
     * 출발점은 **지금 도는 단위의 관측**([liveHold])이고, 단위마다 카탈로그의 효과([HoldEffects])로 다음 파지를
     * 계산한다. 어느 단위의 조건이 그 시점의 파지와 어긋나면 주문을 받지 않는다 — 받아 놓고 돌리면 그 단위가 발신자에서
     * `PRECONDITION_UNMET` 으로 거절되기까지 앞 단위들이 물리적으로 움직인다.
     *
     * **권위는 발신자다.** 여기서 막는 것은 *알고도 보내는* 일뿐이다. 도는 단위가 없으면 관측이 없는 것이고 —
     * 종착한 단위의 파지는 낡을 수 있으며 다시 볼 길이 없으므로(리뷰 C2) 쓰지 않는다 — 발신자가 접수 때 판정한다.
     * 능력을 못 물은 로봇도, 계약에 없는 주어([PreconditionCheck.Unknown.DEFER])도 같다: 모르는 조건을 지어내지 않는다.
     *
     * **개정이면 그 실행 자신의 도는 단위가 사슬의 0번째 고리다**(§15.315). [revising] 은 개정(`Revision.kt` 의 `revise`)만 준다. 그 실행에서
     * 지금 도는 단위가 [planned] 에 없으면(개정이 다시 재단하지 않는 단위) 그 단위의 관측에 그 단위의 효과를 굴린 값 — 그 단위가
     * 끝난 뒤의 파지 — 에서 출발한다. 남은 단위에 이미 하는 성공 가정을 빠졌던 고리에도 하는 것이다. [planned] 에 든 단위(보내는
     * 중인 첫 출발)의 관측은 쓰지 않는다 — 그 단위는 사슬이 직접 굴리고, 남은 관측은 앞 시도의 것일 수 있다. 다른 실행의 관측은
     * 날것 그대로다. 실패해 든 채 남는 현실은 여전히 발신자가 판정한다.
     *
     * **도는 단위가 없으면 기체 스냅숏의 손에서 출발한다**(§15.316, 계약 0.11.0). 새 접수에만 쓰고 개정에는 쓰지 않는다. 스냅숏의 손은 발신자가
     * 주기적으로 다시 읽은 값이라 C2 가 버린 «다시 볼 길 없는 종착 파지» 가 아니다. 든 채·빈손으로 답했을 때만 쓰고, 관측 불가·말하지 않음·못 물음이면 지금처럼
     * 출발점이 없다(발신자가 판정). 그 기체를 **다른 실행이 쥐고 있으면**([PhysicalState.occupiesRobot]) 거절은 하되 조치 제안은 세우지 않고
     * ([Submission.Rejected.heldBy]), 조치 걸음을 실은 계획(승인 접수)은 위반이 없어도 받지 않는다 — 그 손에 든 것이 그 실행의 일일 수 있다.
     */
    fun chainRefusal(robotId: String, planned: List<ExecutionUnit>, revising: Execution? = null): Submission.Rejected? {
        // **사슬은 기체마다다.** 손은 기체에 달렸으므로 단위가 묶인 기체의 파지로 그 기체의 단위들을 굴린다. 묶지 않은 단위는
        // [robotId] 의 사슬이다. 다른 기체의 사슬에서 찾은 조치 열은 싣지 않는다 — 제안은 (주문을 받을 기체, 주문)으로 서므로
        // 실으면 그 조치가 [robotId] 에서 돈다.
        val replanned = planned.mapTo(HashSet()) { it.unitId }
        for ((robot, units) in planned.groupBy { it.robotBinding ?: robotId }) {
            chainOf(robot, units, revising, replanned)?.let { return if (robot == robotId) it else it.copy(remedy = null) }
        }
        return null
    }

    /** 기체 하나의 사슬 검사. [chainRefusal] 이 기체마다 부른다. */
    private fun chainOf(
        robotId: String,
        planned: List<ExecutionUnit>,
        revising: Execution?,
        replanned: Set<String>,
    ): Submission.Rejected? {
        val declared = robots.capabilities(robotId)?.skillsList ?: return null
        val byType = declared.associateBy { it.skillType }
        val carriesRemedy = planned.any { it.unitId.startsWith("remedy-") }
        val live = startHold(robotId, revising, replanned)
        val observed = live?.let { Start(it.first, if (it.second) Basis.ROLLED else Basis.LIVE) }
            ?: revising?.let { return null }
            // 스냅숏은 볼 까닭이 있을 때만 묻는다 — 조건을 선언한 단위나 조치 열이 없으면 읽을 것이 없다(남쪽 읽기는 비용이다).
            ?: (if (carriesRemedy || planned.any { (byType[it.skillType]?.preconditionsCount ?: 0) > 0 }) snapshotHold(robotId) else null)
                ?.let { Start(it, Basis.SNAPSHOT) }
            ?: return null
        val start = observed.hold
        var hold = start
        // 남이 쥔 기체의 든 것으로 조치 열을 보내지 않는다. 승인 접수는 조치 열을 앞에 붙여 다시 이 검사를 지나므로, 제안이 선 뒤 점유가 생겨도 여기서 막힌다.
        val holder = if (observed.basis == Basis.SNAPSHOT && start.kind == HoldKind.HOLD_KIND_HOLDING) occupant(robotId) else null
        if (holder != null && carriesRemedy) {
            return Submission.Rejected(
                "그 기체를 다른 실행 ${holder.executionId} 가 쥐고 있어 조치 열을 보내지 않는다 (기체 스냅숏의 파지, 보내지 않았다)",
            )
        }
        for (unit in planned) {
            if (unit.route != Route.ROBOT) continue
            val target = byType[unit.skillType]
            val violations = target
                ?.let { PreconditionCheck.check(it, hold, PreconditionCheck.Unknown.DEFER) }
                .orEmpty()
            if (violations.isNotEmpty()) {
                val basis = when {
                    hold !== start -> "지금 도는 단위의 관측"
                    observed.basis == Basis.ROLLED -> "지금 도는 단위가 끝난 뒤의 파지"
                    observed.basis == Basis.SNAPSHOT -> "기체 스냅숏의 파지"
                    else -> "지금 도는 단위의 관측"
                }
                val reason = "단위 ${unit.unitId}(${unit.skillType}) 의 사전 조건이 어긋난다 — " +
                    PreconditionCheck.rejectionDetail(violations) + " ($basis, 보내지 않았다)"
                // **대안은 여기서만 계산할 수 있다.** 관측(도는 단위 또는 기체 스냅숏)과 선언이 둘 다 있는 자리가 여기뿐이다.
                // **계산은 여기서, 기록은 밖에서.** 탐색 자체는 부작용이 없으므로 관문 안에 둘 수 있다.
                val remedy = target?.let { RemedySearch.search(it, declared, hold) }
                return Submission.Rejected(reason, remedy)
            }
            hold = HoldEffects.after(unit.skillType, hold)
        }
        return null
    }

    /**
     * 셀 자리의 점유를 하달 전에 본다(§15.153) — **자리 경쟁**과 **출발 결품** 둘.
     *
     * 파지가 «이 기체가 무엇을 들었나» 라면 이것은 «저 자리가 지금 쓰이는가» 다. 축이 하나뿐일 때는 두
     * 주문이 같은 슬롯을 목적지로 삼아도 둘 다 접수됐고, 출발 자리가 비어 있어도 하달한 뒤에야 실패했다.
     *
     * **권위가 둘이라 순서가 있다.** 자리 경쟁은 이 층이 아는 사실(진행 중 실행)이라 단정하고, 결품은
     * 설비 관측이라 **말이 없으면 판정하지 않는다.** 없는 관측을 위반으로 세면 신호가 죽은 셀이 통째로 선다.
     * 단, 임무 정의가 출발 관측을 요구한 단위([SourceObservation.REQUIRED])는 말이 없으면 보내지 않고 결품과 다른
     * 확인 요구([SourceUnobserved])로 답한다.
     *
     * 목적지 관측을 요구한 단위([DestinationObservation.REQUIRED])는 예약 다음에 목적지의 **지금 관측**을 본다([destinationViolation]).
     */
    private fun occupancyViolation(planned: List<ExecutionUnit>, moving: String?): Admission.Refused? {
        val claimed = liveClaims(moving)

        for (unit in planned) {
            val where = unit.destination ?: continue
            // [Middleware.submit] 과 [Middleware.adopt] 는 이미 도는 `jobOrderId` 를 `revise` 로 갈라 이 관문을 안 지난다.
            // 공개 창구 [Middleware.admits] 는 새 작업 지시로 물으므로 같은 주문의 실행이 있으면 제 자리에 걸린다.
            // 재할당은 옮길 실행이 아직 표에 있으므로 [moving] 으로 그 실행의 자리를 뺀다.
            val holder = claimed[where] ?: continue
            // **자리 경쟁은 대장에 안 남는다**(§15.183). 이 층이 계산한 답이 없기 때문이다 — 잡고 있는
            // 쪽이 놓기를 기다리는 것 말고 제시할 것이 없다. 한계 대장에 열어 두었다.
            return Admission.Refused(
                Submission.Rejected(
                    "자리 $where 를 ${holder.jobOrderId}(${holder.executionId}) 가 이미 잡고 있다 — " +
                        "같은 자리에 둘을 놓지 않는다",
                ),
            )
        }

        destinationViolation(planned)?.let { return Admission.Refused(it) }

        for (unit in planned) {
            // **이 층이 집으러 보내는 단위만 본다.** 플릿 위임은 플릿이 인수 시점에 대조하고 불일치를
            // 보고한다 — 여기서 막으면 그 경로가 영영 안 밟히고, 계약이 약속한 보고가 사라진다.
            if (unit.route != Route.ROBOT) continue
            val from = unit.source ?: continue
            val check = CellOccupancy.sourceCheck(cell.observe(from), unit.expectedIdentity, unit.sourceObservation)
            if (check is SourceCheck.Unobserved) {
                // **확인 요구는 결품이 아니다.** 대체 자리를 계산하지 않고 결품의 답도 싣지 않는다 — 접으면 운영자가
                // 멀쩡한 자재를 두고 주문을 고친다. 확인은 셀의 관측으로 들어오고, 그때 같은 작업 요청이 이 판정을 다시 지난다.
                val unobserved = SourceUnobserved(material = unit.expectedIdentity, source = from, occupied = check.occupied)
                val seen = if (check.occupied == true) "있다고만 하고 무엇인지 말하지 않았다" else "말이 없다"
                return Admission.Refused(
                    Submission.Rejected(
                        "출발 자리 $from 의 ${unit.expectedIdentity ?: "자재"} 를 확인하지 못했다 — 설비가 $seen " +
                            "(이 단위는 출발 관측을 요구한다, 확인 전에는 보내지 않는다)",
                        sourceUnobserved = unobserved,
                    ),
                )
            }
            if (check !is SourceCheck.Missing) continue

            val material = unit.expectedIdentity
            // **이 주문이 채울 자리도 뺀다.** 지금은 그 자재가 놓여 있어도 이 주문이 쓸 자리이고,
            // 제시하면 자기 목적지에서 집어 자기 목적지에 놓으라는 말이 된다.
            val taken = claimed.keys + planned.mapNotNull { it.destination }
            val alternatives = material?.let { CellOccupancy.alternatives(cell.holding(it), taken, exclude = from) }
            val seen = check.observed?.let { "'$it' 이 있다" } ?: "비었다"
            return Admission.Refused(
                Submission.Rejected(
                    "출발 자리 $from 에 ${material ?: "요구한 것"} 이 없다 — $seen (설비 관측, 보내지 않았다)",
                    alternativeLocations = alternatives,
                ),
                // **계산한 것을 그 자리에서 버리지 않는다**(§15.164 와 같은 자리). 산문의 사유만 내면
                // 읽는 쪽이 한국어를 문자열로 뜯어야 하고, 그 대조는 문구를 고치는 날 조용히 깨진다.
                sourceMissing = RemedyOutcome.SourceMissing(
                    material = material,
                    source = from,
                    observed = check.observed,
                    alternatives = alternatives,
                ),
            )
        }
        return null
    }

    /**
     * 목적지의 **지금 관측**(§15.235) — 목적지 관측을 요구한 단위([DestinationObservation.REQUIRED])만 본다.
     *
     * 예약([liveClaims])은 진행 중인 실행만 센다. 그래서 넣다가 실패해 자재가 남은 채 종료한 실행의 자리, 다 채워진 채 아직 나가지
     * 않은 랙의 자리는 예약으로는 비어 보인다. 그 자리를 셀이 «있다» 로 보는 한 이 단위를 보내지 않는다. 실행이 종료했는지와 상관없다.
     *
     * **예약 다음, 출발 자리 앞이다.** 예약은 이 계층이 아는 사실이고 누가 잡았는지까지 적으므로 먼저 본다. 목적지가 차 있으면 출발
     * 자리를 고쳐도 보낼 수 없으므로 출발 결품보다 먼저 본다 — 대체 출발 자리를 제시하면 운영자가 소용없는 수정을 한다.
     *
     * **이 계층이 보내는 단위만 본다.** 플릿 위임은 플릿이 목적지에 앞 용기가 남은 것을 보고한다(`WAITING_HANDOVER`). 식별 정보가
     * 없는 단위(갈 자리)도 보지 않는다 — 예약과 같은 기준이다. 선언이 [DestinationObservation.OPTIONAL] 이면 설비에 묻지도 않는다.
     *
     * **거부는 텍스트 사유로만 낸다.** 조치 탐색 레지스터에 적지 않는다 — 이 계층이 계산한 응답이 없다(§15.183 의 자리 경쟁과 같은
     * 처리). 구조 칸도 두지 않는다 — 읽을 소비자가 생기면 더한다(ADR 9). 확인은 셀의 관측으로 들어오고, 그때 같은 작업 요청이 이
     * 판정을 다시 지난다.
     */
    private fun destinationViolation(planned: List<ExecutionUnit>): Submission.Rejected? {
        for (unit in planned) {
            if (unit.destinationObservation != DestinationObservation.REQUIRED) continue
            if (unit.route != Route.ROBOT || unit.expectedIdentity == null) continue
            val where = unit.destination ?: continue
            when (val check = CellOccupancy.destinationCheck(cell.observe(where))) {
                DestinationCheck.Free -> continue
                is DestinationCheck.Occupied -> {
                    val seen = check.observed?.let { "'$it' 이 있다" } ?: "설비가 있다고만 했다"
                    return Submission.Rejected(
                        "목적지 $where 가 비어 있지 않다 — $seen (목적지 점유, 이 단위는 목적지 관측을 요구한다, 보내지 않았다)",
                    )
                }
                DestinationCheck.Unobserved -> return Submission.Rejected(
                    "목적지 $where 를 확인하지 못했다 — 설비가 말이 없다 " +
                        "(목적지 미관측, 이 단위는 목적지 관측을 요구한다, 확인 전에는 보내지 않는다)",
                )
            }
        }
        return null
    }

    /**
     * 진행 중인 실행들이 잡고 있는 자리. **종착한 실행은 놓는다** — 끝난 주문이 자리를 영원히 물고 있으면
     * 그 자리는 다시 못 쓴다. 유효 기간을 시각으로 두지 않고 실행의 생애로 두는 이유는, 시각으로 두면
     * 만료된 예약이 아직 도는 실행의 목적지를 남에게 내주기 때문이다(§15.159).
     *
     * [moving] 은 옮길 실행 하나의 자리를 뺀다([admits]).
     */
    private fun liveClaims(moving: String? = null): Map<String, SlotClaim> = executions.values
        .filter { !it.physicalState.isSettled && it.executionId != moving }
        .flatMap { execution ->
            execution.units.filter { it.state != UnitState.DONE }.mapNotNull { unit ->
                // ★**`destination` 은 두 뜻을 진다** — «놓을 자리» 와 «갈 자리». 점검 순회의 `navigate_to`
                //   도 목적지를 들지만 그 자리를 채우지는 않는다. 점유는 **아는 것을 놓는 자리**뿐이고,
                //   그것을 가르는 표시가 `expectedIdentity` 다. 목적지만 보면 같은 설비를 두 번 살피는
                //   주문이 서로를 막는다(§15.159).
                val what = unit.expectedIdentity ?: return@mapNotNull null
                unit.destination?.let { SlotClaim(it, execution.executionId, execution.order.jobOrderId, what) }
            }
        }
        .associateBy { it.location }

    /**
     * 사슬 검사의 출발 파지와, 그것이 개정 실행의 도는 단위 효과를 굴린 값인가([chainRefusal] 의 개정 규칙). 고를 것이 없으면 널.
     * [revising] 이 없으면 [liveHold] 와 같은 답이다.
     */
    private fun startHold(robotId: String, revising: Execution?, replanned: Set<String>): Pair<HoldState, Boolean>? {
        val holds = executions.values
            .filter { !it.physicalState.isSettled }
            .mapNotNull { execution ->
                val unit = execution.active?.takeIf { execution.robotOf(it) == robotId } ?: return@mapNotNull null
                when {
                    execution !== revising -> unit.hold to false
                    unit.unitId in replanned -> null
                    unit.hold.kind != HoldKind.HOLD_KIND_HOLDING && unit.hold.kind != HoldKind.HOLD_KIND_EMPTY -> null
                    else -> HoldEffects.after(unit.skillType, unit.hold).let { it to (it.kind != unit.hold.kind) }
                }
            }
        return holds.firstOrNull { it.first.kind == HoldKind.HOLD_KIND_HOLDING }
            ?: holds.firstOrNull { it.first.kind == HoldKind.HOLD_KIND_EMPTY }
    }

    /** 사슬 검사의 출발점이 어디서 왔나 — 거절 사유와 점유 규칙이 가른다. */
    private enum class Basis { LIVE, ROLLED, SNAPSHOT }

    private class Start(val hold: HoldState, val basis: Basis)

    /**
     * 기체 스냅숏의 손(계약 `GetSnapshotResponse.hold`, 0.11.0) — **든 채·빈손으로 답했을 때만.** 관측 불가·말하지 않음·못 물음이면 널이다:
     * 모르는 손을 빈손으로 지어내지 않는다([PreconditionCheck] 는 관측 불가도 위반으로 세므로 여기서 거른다). 신선함은 포트가 진다 —
     * 미리 읽는 포트는 신선한 창 밖이면 스냅숏을 주지 않는다.
     */
    private fun snapshotHold(robotId: String): HoldState? =
        // 읽기가 던지면 못 본 것이다 — 접수 판단을 예외로 무너뜨리지 않는다(발신자가 판정한다).
        runCatching { robots.snapshot(robotId) }.getOrNull()?.hold
            ?.takeIf { it.kind == HoldKind.HOLD_KIND_HOLDING || it.kind == HoldKind.HOLD_KIND_EMPTY }

    /**
     * 도는 단위 관측이 없을 때 이 기체를 쥔 다른 실행 id. [RemedyDesk] 가 스냅숏 근거의 거절에 제안을 세울지 가른다 — 도는 단위 관측이 없는데
     * 조치가 찾아졌다면 출발점은 스냅숏이다(개정은 도는 단위가 없으면 사슬을 안 본다).
     */
    fun snapshotHolder(robotId: String): String? = if (liveHold(robotId) == null) occupant(robotId)?.executionId else null

    /** 이 기체를 쥔 실행(정착하지 않았거나 부분 완료로 다시 돌 수 있는 것). 단위가 묶인 기체도 센다. */
    private fun occupant(robotId: String): Execution? =
        executions.values.firstOrNull { it.physicalState.occupiesRobot && robotId in it.robots }

    /**
     * 승인 값을 채울 손(§15.316) — 도는 단위의 관측([liveHold])이 먼저이고, 없으면 신선한 기체 스냅숏의 손이다. 그 기체를 다른 실행이 쥐고 있으면
     * 스냅숏을 쓰지 않는다: 제안을 세운 규칙([chainRefusal])과 같은 규칙이어야 선 조건과 채우는 조건이 갈리지 않는다.
     */
    fun fillHold(robotId: String): HoldState? =
        liveHold(robotId) ?: snapshotHold(robotId)?.takeIf { occupant(robotId) == null }

    /**
     * 이 기체에서 **지금 도는** 단위들의 파지 관측. 든 것이 하나라도 있으면 든 채(손은 한 쌍이다), 아니면 빈손 관측이
     * 있으면 빈손, 도는 단위가 없으면 모른다(널). 종착한 실행의 관측은 쓰지 않는다.
     */
    fun liveHold(robotId: String): HoldState? {
        // 도는 단위가 묶인 기체로 가른다 — 실행의 기체로 가르면 다른 기체에 묶인 단위의 파지가 엉뚱한 기체의 손으로 읽힌다.
        val live = executions.values
            .filter { !it.physicalState.isSettled }
            .mapNotNull { execution -> execution.active?.takeIf { execution.robotOf(it) == robotId } }
        return live.firstOrNull { it.hold.kind == HoldKind.HOLD_KIND_HOLDING }?.hold
            ?: live.firstOrNull { it.hold.kind == HoldKind.HOLD_KIND_EMPTY }?.hold
    }

    /**
     * 주문이 **스스로 어긋나는가** — 선언한 자재 수량과 배정된 단위 수가 타입마다 같은가.
     *
     * 상류가 *A형 둘* 이라 선언했는데 슬롯이 셋을 요구하면 그 주문은 자기 안에서 모순이다. **정책이 아니라
     * 정합성**이다 — 우리가 재고를 판단하는 것이 아니라(그것은 WMS 의 일이다) 서로 안 맞는 주문을 안 받는 것이다.
     *
     * 받아 놓고 돌리면 어느 슬롯이 계획 밖이었는지가 **로봇이 실패한 뒤에야** 보인다. 그때는 이미 물리적으로
     * 움직인 뒤다.
     *
     * `MaterialRequirements` 를 안 싣는 주문(운반 ①)은 검사하지 않는다 — 없는 것과 어긋나는 것은 다르다.
     */
    private fun inconsistent(order: JobOrder, planned: List<ExecutionUnit>): String? {
        if (order.materialRequirements.isEmpty()) return null
        val declared = order.materialRequirements.associate { it.materialDefinitionId to it.quantity }
        val assigned = planned.mapNotNull { it.expectedIdentity }.groupingBy { it }.eachCount()
        if (declared == assigned) return null
        return "자재 선언과 배정이 어긋난다: 선언=$declared, 배정=$assigned — 주문이 자기 안에서 안 맞는다"
    }
}

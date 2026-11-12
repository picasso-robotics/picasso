package dev.picasso.middleware

// 경로 드라이버(재설계 R4, 설계 일지 §15.304). 명령 생애가 경로마다 다르게 하는 일 — 출발, 미확정 조회, 조회 앞의 해소, 붙기 전 취소,
// 라운드 진행 — 을 경로마다 한 곳에 둔다. 생애(Lifecycle*.kt)와 Middleware 는 [RouteDriver.of] 로 드라이버를 얻어 부르고, 경로를
// 갈라 묻지 않는다. 로봇·플릿 짝(출발 정산, 조회 정산, 취소 송신과 폴링, 붙기 전 취소, 단위 구동)은 여기서만 갈린다.

/** 단위의 경로([Route]) 하나가 명령 생애에 내놓는 연산. */
internal sealed interface RouteDriver {
    /** 같은 참조로 기존 실행을 찾을 수 있는가(13.2 ①). */
    fun lookupKind(mw: Middleware): ExecutionLookup

    /** 미확정 단위의 취소를 키로 지은 핸들로 보내는 경로인가(§15.219). 로봇만 그렇다. */
    val cancelsByKey: Boolean get() = false

    /** 개정의 갱신을 도는 단위에 보내는 경로인가(§4.4). 로봇만 그렇다 — 플릿 계약에는 갱신이 없고, 설비 대기는 바꿀 것이 없다. */
    val takesUpdate: Boolean get() = false

    /** 단위의 첫 출발을 보낸다. 단위와 실행의 준비([startUnit])는 부르는 쪽이 했다. */
    fun start(cl: CommandLifecycle, unit: ExecutionUnit, restore: StartRestore)

    /** 이 단위의 미확정 조회가 잠금 밖에서 나가는 중이면 그 결과를 받는다. 나가는 중이 아니면 널이다. */
    fun settlePendingLookup(cl: CommandLifecycle, unit: ExecutionUnit): Boolean? = null

    /** 조회 앞에서 경로가 혼자 푸는 길(스냅숏 부착, 리비전 앞섬). 풀었으면 그 결과, 아니면 널이다. */
    fun resolveBeforeLookup(cl: CommandLifecycle, unit: ExecutionUnit): Boolean? = null

    /** 조회가 새 발행과 같은 관문을 지나야 하면 그 관문을 묻는다(ADR 52). */
    fun lookupPermitted(cl: CommandLifecycle, unit: ExecutionUnit): Boolean = true

    /** 같은 참조로 다시 묻는다. 조회 횟수와 시각은 부르는 쪽이 적었다. @return 단위가 이 라운드에 종료했는가. */
    fun sendLookup(cl: CommandLifecycle, unit: ExecutionUnit): Boolean

    /** 라운드에서 도는 단위를 한 걸음 민다. @return 단위가 이 진행에서 종료(운영자 보류 포함)했는가. */
    fun pump(cl: CommandLifecycle, unit: ExecutionUnit): Boolean

    /** 다시 지은 실행에 붙기 전에 건 취소를 이 경로의 하위에서 찾아 붙는다(§15.231·§15.254). @return 붙었거나 기다린다. */
    fun attachForCancel(cl: CommandLifecycle): Boolean = false

    data object Robot : RouteDriver {
        override fun lookupKind(mw: Middleware) = mw.robots.executionLookup
        override val cancelsByKey get() = true
        override val takesUpdate get() = true

        override fun start(cl: CommandLifecycle, unit: ExecutionUnit, restore: StartRestore) = with(cl) {
            with(mw) {
                val flight = InFlight(
                    InFlight.Kind.START, unit.unitId, execution.robotOf(unit), unit.taskId, unit.revision, unit.skillType,
                    withOptionals(unit, unit.parameters), previous = unit.revision, restore = restore,
                )
                settleIssue(flight, issueStart(flight))
                Unit
            }
        }

        override fun settlePendingLookup(cl: CommandLifecycle, unit: ExecutionUnit): Boolean? = with(cl) {
            with(mw) {
                inFlight?.takeIf { it.kind == InFlight.Kind.LOOKUP && it.unitId == unit.unitId }?.let { flight ->
                    val settled = settleIssue(flight, issueStart(flight))
                    if (inFlight != null) execution.physicalState = PhysicalState.IN_DOUBT
                    settled
                }
            }
        }

        override fun resolveBeforeLookup(cl: CommandLifecycle, unit: ExecutionUnit): Boolean? = with(cl) {
            // 기체가 더 높은 리비전을 들었으면 붙지도 묻지도 않고 운영자에게 간다. 스냅숏에 붙었으면 이 라운드는 여기까지다.
            when {
                !execution.cancelRequested && holdRevisionAhead(unit) -> true
                attachFromSnapshot(unit) -> false
                else -> null
            }
        }

        override fun lookupPermitted(cl: CommandLifecycle, unit: ExecutionUnit) =
            cl.mw.issuable(cl.execution, unit, WithheldIssue.IN_DOUBT_LOOKUP)

        override fun sendLookup(cl: CommandLifecycle, unit: ExecutionUnit): Boolean = with(cl) {
            with(mw) {
                val flight = InFlight(
                    InFlight.Kind.LOOKUP, unit.unitId, execution.robotOf(unit), unit.taskId, unit.revision, unit.skillType,
                    withOptionals(unit, unit.parameters), previous = unit.revision, restore = null,
                )
                val settled = settleIssue(flight, issueStart(flight))
                if (inFlight != null) execution.physicalState = PhysicalState.IN_DOUBT
                settled
            }
        }

        override fun pump(cl: CommandLifecycle, unit: ExecutionUnit) =
            cl.holdUnreachedCancel(unit) || cl.mw.pumpRobotUnit(cl.execution, unit)

        override fun attachForCancel(cl: CommandLifecycle) = cl.attachForCancel()
    }

    data object Fleet : RouteDriver {
        override fun lookupKind(mw: Middleware) = mw.fleet.executionLookup

        override fun start(cl: CommandLifecycle, unit: ExecutionUnit, restore: StartRestore) = with(cl) {
            with(mw) {
                val flight = FleetFlight(FleetFlight.Kind.START, unit.unitId, transportOrderOf(unit), restore)
                settleDispatch(flight, issueDispatch(flight.order))
                Unit
            }
        }

        override fun settlePendingLookup(cl: CommandLifecycle, unit: ExecutionUnit): Boolean? = with(cl) {
            with(mw) {
                fleetInFlight?.takeIf { it.kind == FleetFlight.Kind.LOOKUP && it.unitId == unit.unitId }?.let { flight ->
                    val settled = settleDispatch(flight, issueDispatch(flight.order))
                    if (fleetInFlight != null) execution.physicalState = PhysicalState.IN_DOUBT
                    settled
                }
            }
        }

        override fun sendLookup(cl: CommandLifecycle, unit: ExecutionUnit): Boolean = with(cl) {
            with(mw) {
                val flight = FleetFlight(FleetFlight.Kind.LOOKUP, unit.unitId, transportOrderOf(unit), restore = null)
                val settled = settleDispatch(flight, issueDispatch(flight.order))
                if (fleetInFlight != null) execution.physicalState = PhysicalState.IN_DOUBT
                settled
            }
        }

        override fun pump(cl: CommandLifecycle, unit: ExecutionUnit) = cl.mw.pumpFleetUnit(cl.execution, unit)

        override fun attachForCancel(cl: CommandLifecycle) = cl.attachTransportForCancel()
    }

    /** 설비 대기. 하위에 보낼 요청이 없다 — 시작이 곧 기다림의 시작이고, 읽기는 [pumpSignalUnit] 이 한다. */
    data object Signal : RouteDriver {
        /** 요청을 보내지 않으므로 미확정이 생기지 않는다 — 실행의 미확정 자동 해소를 막지 않는 쪽으로 답한다. */
        override fun lookupKind(mw: Middleware) = ExecutionLookup.CLIENT_REFERENCE

        override fun start(cl: CommandLifecycle, unit: ExecutionUnit, restore: StartRestore) {
            unit.state = UnitState.RUNNING
            cl.execution.physicalState = PhysicalState.RUNNING
        }

        override fun sendLookup(cl: CommandLifecycle, unit: ExecutionUnit) = cl.lookupFound(unit, true)

        override fun pump(cl: CommandLifecycle, unit: ExecutionUnit) = cl.mw.pumpSignalUnit(cl.execution, unit)
    }

    companion object {
        fun of(route: Route): RouteDriver = when (route) {
            Route.ROBOT -> Robot
            Route.FLEET -> Fleet
            Route.SIGNAL -> Signal
        }

        /** 붙기 전 취소를 찾는 차례. 로봇 태스크를 먼저 멈추고, 그다음 플릿에 맡긴 운반이다. */
        val ATTACH_ORDER: List<RouteDriver> = listOf(Robot, Fleet)
    }
}

package dev.picasso.middleware

import dev.picasso.contracts.v1.ConnectionState
import dev.picasso.contracts.v1.Event
import dev.picasso.contracts.v1.Fault
import dev.picasso.middleware.MiddlewareCodes.ACKNOWLEDGED_FAULT_CLEARED

// 기체 동기화(재설계 R5, 설계 일지 §15.305): 스냅숏과 재생을 읽어 기체 뷰를 세우고, 이벤트를 관계있는 실행의 자취에 남기며, 걷힌
// 결함의 감수 열쇠를 놓는다. 상태(기체 뷰, 실행)는 Middleware 에 있다. 라운드의 순서([Middleware.pump])가 이것을 부른다.

/**
 * 기체 하나를 동기화한다 — 스냅샷(현재값) + 재생(그 사이의 사실). 이벤트는 관계있는 실행의 자취에 남긴다:
 * 태스크 전이는 그 태스크를 든 실행에, 결함·능력 변경은 그 기체의 실행 전부에.
 *
 * 미리 읽는 포트([RobotPort.prefetched])면 그 읽기를 받는다(§15.267). 시각은 그 읽기가 실제로 보낸·받은 시각이다 — 받아 든 시각으로
 * 찍으면 낡은 값이 방금 본 값이 되고, 끊김 유예와 시계 차가 그만큼 어긋난다. 읽는 중이면 뷰를 그대로 둔다.
 */
internal fun CommandEnv.sync(robotId: String, live: List<Execution>) {
    val view = views.getOrPut(robotId) { RobotView() }
    val read = robots.prefetched(robotId) ?: readNow(robotId)
    val (snapshot, sentAt, receivedAt) = when (read) {
        PrefetchedRead.Pending -> return
        PrefetchedRead.Unread -> {
            view.observable = false
            return
        }
        // 던지는 포트도 «못 물어봤다» 다 — 낡은 값을 관측한 값으로 두지 않는다. 던진 사실은 부른 쪽이 트레이스에 남긴다.
        is PrefetchedRead.Failed -> {
            view.observable = false
            throw read.failure
        }
        is PrefetchedRead.Read -> Triple(read.snapshot, read.sentAt, read.receivedAt)
    }
    if (snapshot == null) {
        view.observable = false
        return
    }
    view.observable = true
    view.lastObservedAt = receivedAt
    measureClock(robotId, view, snapshot, sentAt, receivedAt, live)
    // **결함을 못 본 스냅숏은 결함이 걷혔다는 관측이 아니다**(§15.263). 수용을 놓지 않고, 낡은 결함도 현재값으로 두지 않는다 —
    // 막는 관문은 [blockingFaults] 가 못 본 것으로 다룬다.
    view.faultsObservable = snapshot.faultsObservable
    view.faults = if (snapshot.faultsObservable) snapshot.faults.associateBy { faultKey(it) } else emptyMap()
    if (snapshot.faultsObservable) {
        live.forEach { e -> e.acknowledgedFaults.filter { it !in view.faults }.forEach { forgetAcknowledged(e, robotId, it, "robot=$robotId 의 스냅숏에 없다") } }
    }
    view.connection = snapshot.connection
    view.tasks = snapshot.tasks
    view.taskRevisions = snapshot.revisions
    val previousSession = view.session
    if (snapshot.session.isNotEmpty()) view.session = snapshot.session

    val cursor = view.cursor
    if (cursor == null) {
        view.cursor = snapshot.sequence
        view.resyncs += 1
        val faults = if (snapshot.faultsObservable) snapshot.faults.size.toString() else "not observable"
        live.forEach { it.trail("RESYNC", "snapshot: next sequence=${snapshot.sequence}, faults=$faults, connection=${snapshot.connection.name}") }
        return
    }
    // **세션이 바뀌었으면 커서를 잴 수 없다.** 번호는 세션 안의 것이고 다시 뜬 하위는 번호를 처음부터 센다. 앞 세션의
    // 커서로 재생을 물으면 발신자는 축출이 아니라 빈 답을 주고, 그 번호에 닿을 때까지 새 세션의 이벤트를 건너뛴다(§15.215).
    if (snapshot.session.isNotEmpty() && previousSession != null && previousSession != snapshot.session) {
        view.cursor = snapshot.sequence
        view.resyncs += 1
        live.forEach { it.trail("RESYNC", "session changed at cursor=$cursor ($previousSession -> ${snapshot.session}); rebuilt from snapshot sequence=${snapshot.sequence}") }
        return
    }
    when (val replay = robots.replay(robotId, cursor)) {
        null -> Unit // 못 물어봤다 — 다음 펌프에 같은 cursor 로 다시 묻는다
        Replay.Evicted -> {
            // 버퍼를 벗어났다 — 놓친 이벤트가 있다. 스냅샷은 이미 세웠으니 거기서부터 이어 붙인다(17장 9번).
            view.cursor = snapshot.sequence
            view.resyncs += 1
            live.forEach { it.trail("RESYNC", "replay evicted at cursor=$cursor; rebuilt from snapshot sequence=${snapshot.sequence}") }
        }
        is Replay.Events -> {
            replay.events.forEach { event -> record(robotId, event, live); view.eventsSeen += 1 }
            replay.events.lastOrNull()?.let { view.cursor = it.header.sequence + 1 }
        }
    }
}

private fun CommandEnv.record(robotId: String, event: Event, live: List<Execution>) {
    val at = event.header.occurredAt
    val seq = event.header.sequence
    when {
        event.hasTaskTransition() -> {
            val tr = event.taskTransition
            live.filter { e -> e.units.any { it.taskId == tr.taskId } }.forEach {
                it.mutableEventTrail += ObservedEvent(seq, at, "TASK_TRANSITION", "${tr.taskId} ${tr.from.name}->${tr.to.name} rev=${tr.revision} attempt=${tr.attempt}")
            }
        }
        event.hasFaultEvent() -> {
            val f = event.faultEvent
            val kind = if (f.cleared) "FAULT_CLEARED" else "FAULT_RAISED"
            live.forEach { it.mutableEventTrail += ObservedEvent(seq, at, kind, "${f.fault.errorType} class=${canonicalClassOf(f.fault)} can_accept_new_task=${f.fault.canAcceptNewTask}") }
            // 스냅숏 둘 사이에 걷혔다가 다시 생긴 결함은 스냅숏만으로는 같은 결함으로 보인다 — 걷힘 이벤트가 그것을 가른다.
            if (f.cleared) faultKey(f.fault).let { key -> live.forEach { forgetAcknowledged(it, robotId, key, "robot=$robotId 가 걷혔다고 알렸다(seq=$seq)") } }
        }
        event.hasCapabilityChanged() -> {
            val c = event.capabilityChanged
            live.forEach { it.mutableEventTrail += ObservedEvent(seq, at, "CAPABILITY_CHANGED", "epoch=${c.capabilityEpoch} added=${c.addedList} removed=${c.removedList} cause=${c.cause.name}") }
        }
        event.hasSkillTransition() -> {
            // 스킬 인스턴스의 전이(READY→ACTIVE→HALTED…). 태스크 전이가 결과를 말하지만 이것이 그 아래의 걸음이고,
            // **재생을 이어 붙이는 검사가 이것에 기댄다** — 마지막으로 읽은 이벤트가 스킬 전이일 때 커서가 안 나아가면 여기서 중복이 보인다.
            val st = event.skillTransition
            live.filter { e -> e.units.any { it.taskId == st.taskId } }.forEach {
                it.mutableEventTrail += ObservedEvent(seq, at, "SKILL_TRANSITION", "${st.taskId} ${st.skillType} ${st.from.name}->${st.to.name}")
            }
        }
        else -> Unit
    }
}

/**
 * 감수한 결함이 [robotId] 에서 걷혔으면 그 열쇠를 놓는다(§15.248). 감수는 그 결함이 남아 있는 동안의 것이다 — 같은 열쇠로 다시
 * 생기면 새 결함이라 다시 막는다. 부르는 쪽은 스냅숏을 읽은 라운드와 걷힘 이벤트뿐이다. 못 읽은 것은 걷혔다는 관측이 아니다.
 *
 * **이 실행의 다른 기체가 지금 그 열쇠를 보이면 놓지 않는다.** 열쇠에 기체가 없어서, 놓으면 그 결함이 없는 기체의 스냅숏 하나가
 * 다른 기체에서 감수한 결함을 라운드마다 다시 막는다.
 */
private fun CommandEnv.forgetAcknowledged(execution: Execution, robotId: String, key: String, why: String) {
    with(execution) {
        if (key !in acknowledgedFaults) return
        if (robots.any { it != robotId && views[it]?.let { v -> v.observable && v.faultsObservable && key in v.faults } == true }) return
        mutableAcknowledgedFaults -= key
        trail(ACKNOWLEDGED_FAULT_CLEARED, "$key: $why — 다시 생기면 새 결함으로 막는다")
    }
}

/** 결함의 정체 — 모드 이름과 참조. 미믹의 `FaultRegistry` 가 같은 열쇠로 중복을 막는다. */
internal fun faultKey(fault: Fault): String =
    fault.errorType + fault.referencesList.joinToString(prefix = "[", postfix = "]") { "${it.key}=${it.value}" }

/**
 * **연결이 끊기면 도는 단위의 결과는 미확정이다.** 계약이 OFFLINE 과 CONNECTION_BROKEN 을 가른다(완료 기준 5);
 * HIBERNATING 은 침묵하지만 정상이다(§4.7). 되돌아오면 스냅샷으로 태스크 상태를 다시 세워 이어 간다 —
 * 재실행이 아니다. 어느 쪽도 상류에 한 번씩 알린다(16장 — 결과가 아직 확인되지 않았다는 사실).
 * **스냅숏을 못 읽는 것도 유예가 지나면 끊김이다**(§15.242). 하위가 다시 뜨지 않고 끊기면 포트가 던지지도 끊김을 말하지도 않는다.
 *
 * 라운드 머리([Middleware.pumpRound])가 송신 결과와 취소 다시 보내기 뒤, 미룬 갱신 앞에서 부른다(§15.306).
 */
internal fun CommandEnv.trackLink(execution: Execution) {
    val runningRobotUnit = execution.active?.takeIf { it.route == Route.ROBOT && it.state == UnitState.RUNNING }
    val view = runningRobotUnit?.let { views[execution.robotOf(it)] }
    val unseenSince = view?.takeIf { !it.observable }?.lastObservedAt
        ?.takeIf { !now().isBefore(it.plus(execution.capability.linkLossWindow)) }
    if (unseenSince != null && runningRobotUnit != null && !execution.linkBroken) {
        execution.linkBroken = true
        execution.trail("LINK_BROKEN", "snapshot unreadable since $unseenSince while ${runningRobotUnit.unitId} is running — result unconfirmed")
        execution.physicalState = PhysicalState.IN_DOUBT
        notify(execution)
    }
    if (view != null && view.observable && runningRobotUnit != null) {
        val down = view.connection == ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN ||
            view.connection == ConnectionState.CONNECTION_STATE_OFFLINE
        if (down && !execution.linkBroken) {
            execution.linkBroken = true
            execution.trail("LINK_BROKEN", "${view.connection.name} while ${runningRobotUnit.unitId} is running — result unconfirmed")
            execution.physicalState = PhysicalState.IN_DOUBT
            notify(execution)
        } else if (!down && execution.linkBroken) {
            execution.linkBroken = false
            val downstream = view.tasks[runningRobotUnit.taskId]?.name ?: "(not in snapshot)"
            execution.trail("LINK_RESTORED", "snapshot says ${runningRobotUnit.taskId}=$downstream — resuming tracking, no re-execution")
            runningRobotUnit.annotate("link restored; snapshot state $downstream")
            execution.physicalState = if (execution.cancelRequested) PhysicalState.CANCELING else PhysicalState.RUNNING
            notify(execution)
        }
    }
    // **끊긴 채 단위가 미확정으로 갔어도 링크가 돌아오면 끊김을 걷는다**(§15.306 (c)). 결과의 확정은 미확정 해소가 한다 — 실행
    // 상태는 그대로 둔다. 걷지 않으면 끊김 표시가 실행 끝까지 남아 사건 번들이 그것을 싣는다.
    val doubtRobotUnit = execution.active?.takeIf { it.route == Route.ROBOT && it.state == UnitState.IN_DOUBT }
    val doubtView = doubtRobotUnit?.let { views[execution.robotOf(it)] }
    if (execution.linkBroken && doubtView != null && doubtView.observable &&
        doubtView.connection != ConnectionState.CONNECTION_STATE_CONNECTION_BROKEN &&
        doubtView.connection != ConnectionState.CONNECTION_STATE_OFFLINE
    ) {
        execution.linkBroken = false
        execution.trail("LINK_RESTORED", "snapshot readable again while ${doubtRobotUnit.unitId} is in doubt — doubt resolution continues")
    }
}

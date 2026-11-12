package dev.picasso.middleware

import dev.picasso.contracts.v1.TaskHandle
import dev.picasso.contracts.v1.TaskState

// 명령 생애의 포트 보조(재설계 R3, 설계 일지 §15.303): 실행의 상태를 읽지 않는 하위 호출과 그 답의 모양. 실행마다의 생애는
// CommandLifecycle(Lifecycle*.kt) 에 있고, 여기 것은 그 생애와 Middleware 가 함께 부른다.

/** 계약의 종착 — 기체가 더는 그 태스크를 하지 않는다. [Middleware.isTerminal] 과 달리 사람을 기다리는 태스크는 아직 기체가 붙들고 있다. */
internal fun TaskState.endedOnRobot(): Boolean = this == TaskState.TASK_STATE_SUCCEEDED ||
    this == TaskState.TASK_STATE_FAILED || this == TaskState.TASK_STATE_CANCELLED ||
    this == TaskState.TASK_STATE_CANCELLED_RECOVERY_FAILED

internal fun Middleware.transportOrderOf(unit: ExecutionUnit) = TransportOrder(
    reference = unit.taskId,
    containerId = unit.expectedIdentity ?: unit.unitId,
    source = unit.source ?: "",
    destination = unit.destination ?: "",
)

/**
 * 로봇 단위의 출발(`StartTask`)을 보낸다 — 첫 출발·개정 갱신·미확정 조회가 함께 쓴다(§15.237). 포트가 잠금 밖에서 보내면
 * ([RobotPort.issue]) 기다리지 않고 그 처지를 돌려주고, 아니면 [RobotPort.start] 를 지금 불러 그 결과를 같은 넷으로 감싼다. 보내는
 * 중인 출발의 결과를 다시 물을 때도 이것을 부른다 — 같은 키면 포트가 같은 송신의 처지를 준다.
 */
internal fun Middleware.issueStart(flight: InFlight): Issue =
    robots.issue(flight.robot, flight.taskId, flight.revision, flight.skillType, flight.parameters) ?: try {
        Issue.Answered(robots.start(flight.robot, flight.taskId, flight.revision, flight.skillType, flight.parameters), sentAt = null)
    } catch (e: CommandNotSent) {
        Issue.NotSent(e.message ?: "not sent")
    } catch (e: RuntimeException) {
        Issue.InDoubt(e, sentAt = null)
    }

/**
 * 플릿 맡기기를 보낸다 — 첫 맡기기와 미확정 조회가 함께 쓴다(§15.237). 포트가 잠금 밖에서 보내면([AmrFleetPort.issueDispatch])
 * 기다리지 않고 그 처지를 돌려주고, 아니면 [AmrFleetPort.dispatch] 를 지금 불러 그 결과를 같은 넷으로 감싼다. 보내는 중인 맡기기의
 * 결과를 다시 물을 때도 이것을 부른다.
 */
internal fun Middleware.issueDispatch(order: TransportOrder): DispatchIssue =
    fleet.issueDispatch(order) ?: try {
        DispatchIssue.Answered(fleet.dispatch(order), sentAt = null)
    } catch (e: CommandNotSent) {
        DispatchIssue.NotSent(e.message ?: "not sent")
    } catch (e: RuntimeException) {
        DispatchIssue.InDoubt(e, sentAt = null)
    }

/** 스냅숏으로 짓는 태스크 핸들 — [attachForCancel] 과 [attachFromSnapshot] 이 같은 값을 짓는다. */
internal fun Middleware.handleFor(robot: String, taskId: String, revision: Int): TaskHandle =
    TaskHandle.newBuilder().setTaskId(taskId).setRevision(revision).setRobotId(robot).build()

internal fun Middleware.unansweredAttach(taskId: String) = "$taskId 의 취소에 답이 없다 — 다음 라운드에 스냅숏부터 다시 본다"

/** 미리 읽지 않는 플릿에는 지금 찾는다. 던지면 [FleetRead.Failed] 다. */
internal fun Middleware.findNow(reference: String): FleetRead<TransportHandle?> = try {
    FleetRead.Read(fleet.find(reference), now(), now())
} catch (e: RuntimeException) {
    FleetRead.Failed(e)
}

/** 미리 읽지 않는 플릿에는 지금 읽는다. 던지면 [FleetRead.Failed] 다. */
internal fun Middleware.statusNow(handle: TransportHandle): FleetRead<TransportStatus> = try {
    FleetRead.Read(fleet.status(handle), now(), now())
} catch (e: RuntimeException) {
    FleetRead.Failed(e)
}

# 발신자 거부 뒤 제자리 회복과 운영자 취소 입구(§15.320)

받는 쪽: mission-ops 실행 호스트. 무엇을 언제 반입할지는 받는 쪽이 결정한다.

## 무엇이 생겼나
- 작업 수락된 실행의 단위가 기체에서 `PRECONDITION_UNMET`(거부 주어 `HOLD`)으로 거부되어 운영자 보류에 서면, 미들웨어가 라운드 끝에 신선한 기체 스냅숏의 손으로 그 보류를 (단위, 시도)마다 한 번 다시 본다. 어긋나면 같은 조회 키 (기체, 작업 지시)에 **회복 제안**이 선다. 둘째 인시던트는 열리지 않는다.
- 회복 제안은 **사람만** 승인한다. 새 입구:
  `Middleware.approveRecovery(robotId, jobOrderId, target: ProposalTarget.Hold, saw: List<RemedyStep>, parameters: List<Map<String, String>>, approver: Approver): Submission`
  - `target` 은 `Middleware.proposalTarget(robotId, jobOrderId)` 가 응답한 값 그대로(작업 지시 버전, 단위, 거부된 시도). `saw` 는 `Middleware.proposal(robotId, jobOrderId).steps` 그대로(유형·값·순서). 다르면 `PROPOSAL_CHANGED`.
  - 에이전트면 `HUMAN_APPROVAL_REQUIRED`. 대상이 더는 그 보류가 아니면(재작업·확인·취소·개정) 제안을 내리고 `REMEDY_NOT_APPLIED`. 같은 대상을 다시 누르면 `CONSUMED`.
  - 서면 `Submission.Accepted` 의 실행은 **같은 실행**(실행 id 그대로)이다. 조치 단위가 그 단위 바로 앞에 끼고 그 단위는 새 시도(`@rN`)의 대기로 돌아간다. 조치가 `DONE` 아닌 채 끝나면 원래 단위는 나가지 않고 운영자 보류(`failureClass = REMEDY_NOT_DONE`)에 선다.
- **운영자 취소 입구**: `Middleware.cancelByOperator(executionId, operator: Approver): ResolveOutcome`. 사람 식별자가 필수다(에이전트면 `Refused`). 거는 일은 `cancel` 과 같고, 그 실행의 운영자 보류·미확정 단위의 인시던트 판단 칸에 `OPERATOR_CANCEL` 이 승인자와 함께 실린다. 기존 `cancel(executionId)` 는 상위의 취소로 그대로이며 판단 칸에 싣지 않는다.

## 받는 쪽이 고칠 것(컴파일)
- `IncidentResolution.decision` 의 타입이 `OperatorDecision` 에서 `IncidentDecision`(`CONFIRM_DONE`·`REWORK`·`OPERATOR_CANCEL`·`REMEDY_APPLIED`)으로 바뀌었다. `decision.name` 은 그대로 컴파일된다. `OperatorDecision` 값과 견주는 자리(`IncidentViews.kt` 의 `bundle.resolution?.decision == OperatorDecision.CONFIRM_DONE`)는 `IncidentDecision.CONFIRM_DONE` 으로 고쳐야 컴파일된다. `IncidentDecision.of(OperatorDecision)` 이 같은 이름의 값을 준다.
- 화면·시험이 판단 값을 `REWORK`·`CONFIRM_DONE` 둘로만 가정하면 새 값 둘을 만난다.

## 운영자 취소를 새 입구로
- 실행 호스트의 운영자 취소(사람이 화면에서 누르는 취소)는 `cancelByOperator` 를 부르도록 바꾼다. 상위(MES 등)에서 온 취소만 `cancel` 로 남긴다. 그래야 «인시던트 → 사람이 닫음 → 탐색» 이 인시던트 데이터로 남는다.

## 저장 계약
- **승인 적용 뒤 다음 `pump` 전에 판단 기록(`judgmentRecord`)을 저장한다.** `approveRemedy`·`attemptApproval`·`approveRecovery` 모두 같다. 그 사이에 죽으면 다시 지은 실행이 그 승인을 모른다. 이 틈을 막는 장치는 아직 없다(picasso 한계 레지스터 «§15.320 · 저장 틈»).
- 판단 기록 버전이 4 로 올랐다. 승인 칸에 `position`(회복이면 대상, 작업 수락 승인이면 키 없음), 판정 칸에 `preconditionSubjects` 가 붙었다. 두 칸은 비면(위치 없음·주어 없음) 키를 싣지 않는다 — 다른 칸은 지금처럼 널도 키를 싣는다. 버전 1·2·3 기록도 읽는다. `resume` 은 위치를 읽어 조치를 그 단위 앞에 다시 세운다.

## 버전이 오른 바깥 형식
- 내보내기(`LedgerExport`) 6 → 7: 인시던트 `resolution.decision` 값 둘 추가. 칸은 그대로.
- 승인 호스트(`ApprovalWire`) 4 → 5: 거부 값 `HUMAN_APPROVAL_REQUIRED`, 소모 기록의 `target` 칸.
- 판단 기록(`JudgmentRecordWire`) 3 → 4: 위 저장 계약의 두 칸.

## 받는 쪽이 정할 것
- 회복 승인 화면의 모양(대상과 본 조치 열을 사람에게 보이고 그대로 넘기는 방식).
- 운영자 취소 화면이 사람 식별자를 어디서 얻을지.
- 저장 계약을 지키는 방식(승인 처리와 판단 기록 저장을 한 트랜잭션으로 묶는 등).

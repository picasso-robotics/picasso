# 자동 승인자 id 와 선언 파일 안내

## 결정
- incident-recovery의 자동 승인자 id는 `recovery-1`로 결정되었다(2026-11-14 소유자 결정 및 자문 합의).
- recovery는 narrator와 구분되는 고유 id를 사용한다. 환경 변수 `RECOVERY_PICASSO_AGENT_ID`에 이 값을 지정하며 기본값은 없다.
- 승인자 id 이름 규칙의 정본은 picasso 용어 사전 §6이다.

## 선언 파일은 복사 원본이 아니다
- `handoff/narrator/entitlements.json`은 picasso 시험과 `ScenarioHost` 시연을 위한 픽스처다.
- 이 픽스처를 복사해 운영자 id를 덧붙이라는 기존 계획 문서(`docs/plans/2026-10-04-plan-c3-picasso-client.md` 527-534행)의 안내는 더 이상 권장하지 않는다.
- recovery의 시험 환경은 자체 선언 파일을 직접 작성하여 `--entitlements`로 전달한다.

## 사람 승인도 선언이 필요하다
- recovery는 사람 승인도 값 없는 문(`POST /approvals` → `attemptApproval`)으로 보낸다.
- 이 경로는 승인자 종류를 가리지 않고 선언을 확인하므로, 운영자 id도 선언에 등록되어 있어야 통과한다(`RemedyDesk.judge`의 `given == null` 분기).
- 미선언, 철회, 만료 상태인 운영자 id는 거절된다.

## 전환 순서
1. 승인 호스트가 `recovery-1`과 운영자 id가 포함된 새 선언 파일을 기동 시 읽도록 배포한다.
2. 이후 recovery 측의 `RECOVERY_PICASSO_AGENT_ID` 환경 변수 값을 변경한다.
- 순서가 뒤바뀌면 자동 승인 요청이 모두 `NOT_DECLARED`로 거절된다.
- 선언 파일은 기동 시 한 번만 읽으므로 선언 철회나 범위 변경은 프로세스를 재기동해야 반영된다. 만료 일시는 판정마다 현재 시각과 비교한다.

## 받는 쪽이 정할 것
- 무엇을 언제 반영할지는 받는 쪽이 스스로 결정한다는 원칙을 따른다.
- 본 안내에 따른 선언 파일 작성과 환경 변수 전환 일정은 incident-recovery 가 결정한다.

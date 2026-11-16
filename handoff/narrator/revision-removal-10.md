# 리비전 제거 사건 — 내보내기 버전 10(§15.329)

받는 쪽: incident-narrator. 무엇을 언제 반입할지는 받는 쪽이 결정한다. 이 노트는 picasso 가 낸 사실과 읽는 쪽이 할 일이다.

## 바뀐 것
- 내보내기 버전 **9 → 10**. 인시던트 줄·탐색 줄의 바이트는 그대로이고 같은 시드의 `digest` 도 버전 9 와 같다. 버전 10 을 `SCHEMA_VERSIONS` 에 더하면 기존 읽는 코드는 그대로 돈다.
- 한 벌에 셋째 레지스터 `revision-removals.jsonl` 이 놓인다(빈 파일일 수 있다). `manifest.json` 의 `counts` 에 `revisionRemovals`(정수)가 붙었다.
- 한 줄 = 리비전이 작업 지시에서 항목을 빼며 **손대지 않은 단위**(아직 내지 않은 `PENDING`, 태스크 없는 계획 실패 `FAILED`)를 실행에서 지운 사건 하나:
  - `executionId` · `jobOrderId` · `robotId` — 실행 id 는 인스턴스 안의 셈이라 `manifest.json` 의 `instanceId` 와 짝지어 읽는다.
  - `fromVersion` · `toVersion`(정수) — 지우기 전·지운 리비전의 작업 지시 버전. 리비전이 버전을 건너뛰면 차가 1 이 아니다.
  - `removed` — `{unitId, state}` 의 배열(실행 열의 순서). `state` 는 `PENDING` 또는 `FAILED` 다.
  - `at`(가상 시계) · `wallClockAt`(실 시계).
- 발행했거나 끝난 단위가 든 항목을 빼는 리비전은 picasso 가 거부한다 — 그 단위는 이 파일에 나오지 않고 실행에 그대로 남는다.

## 읽는 쪽이 할 일
- **이 사건은 실패·취소·완료가 아니다.** 인시던트를 열지 않고 사람의 판단도 아니다. 설명 문장에서 «취소됨»·«실패» 로 접지 않는다 — «리비전 N 이 범위에서 뺌» 이다.
- 같은 실행의 인시던트 줄에서 단계 위치(`step.plan`)가 리비전 전후로 줄어든 것을 이 사건으로 설명할 수 있다. 사건 없이 계획에서 단위가 사라진 것처럼 읽지 않는다.
- 버전 9 이하의 벌에는 이 파일이 없다 — 없음과 빈 파일을 가르지 않아도 된다(버전 9 에서는 사건이 생기지 않았다. 대신 옛 picasso 는 뺀 항목의 단위를 남겨 발행했다).

## 인계 번들
- `handoff/narrator/run-1`~`run-4`·`run-5a`·`run-5b` 를 버전 10 으로 다시 산출했다. 시나리오는 그대로라 `revision-removals.jsonl` 은 빈 파일이고 `counts.revisionRemovals` 는 0 이다. 줄 모양은 `LedgerExport.revisionRemovalLine` 과 `RevisionRemovalTest` 의 고정 줄이 기준이다.

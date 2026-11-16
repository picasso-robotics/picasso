# 리비전 제거 사건 — 내보내기 버전 10(§15.329)

받는 쪽: incident-recovery. 반입 시점과 방법은 받는 쪽이 결정한다. 칸의 정의는 `handoff/narrator/revision-removal-10.md` 와 같으며, 여기에는 recovery 가 할 일만 적는다.

## 바뀐 것(요약)
- 내보내기 버전 **9 → 10**. 인시던트·탐색 줄과 `digest` 는 그대로다 — `SCHEMA_VERSIONS` 에 `"10"` 을 더하면 기존 증상 읽기는 그대로 돈다.
- 새 레지스터 `revision-removals.jsonl`(`executionId` · `jobOrderId` · `robotId` · `fromVersion` · `toVersion` · `removed[{unitId, state}]` · `at` · `wallClockAt`)과 `manifest.json` 의 `counts.revisionRemovals`.

## 할 일
- **증상으로 세지 않는다.** 리비전이 범위에서 뺀 손대지 않은 단위다 — 실패·취소·완료가 아니고, 회복 조치의 대상이 아니다. 재발 셈에도 넣지 않는다.
- 이 사건으로 이미 열린 인시던트를 «해결됨» 으로 닫지 않는다. picasso 는 손댄 단위(발행·끝남·보류)가 든 항목을 빼는 리비전을 거부하지만, 선행이 끝나지 않아 건너뛴 단위(태스크 없는 `FAILED`, `PREDECESSOR_NOT_DONE`)는 계획 실패와 같이 지워질 수 있다. 그 단위는 선행의 인시던트·재작업 묶음과 이어져 있을 수 있다 — 지워진 것은 그 단위가 범위에서 빠졌다는 사실이지 선행의 인시던트가 풀렸다는 뜻이 아니다. 선행 인시던트는 그대로 두고, 재작업 묶음을 셀 때 지워진 단위를 빼고 센다.

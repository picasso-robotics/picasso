# 재작업 일반화 — 내보내기 버전 9(§15.323)

받는 쪽: incident-recovery. 반입 시점과 방법은 받는 쪽이 결정한다. 칸의 정의는 `handoff/narrator/rework-generalized.md` 와 같으며, 여기에는 recovery 가 지을 읽는 코드만 적는다.

## 바뀐 것(요약)
- 내보내기 버전 **8 → 9**. 인시던트 줄 `resolution.reworkBasis`(널 또는 `{robotId, hold, source, destination, readAt}`) 한 칸. 시도된 실패를 재작업으로 받은 그 대상의 인시던트 판단에만 값이 있다. `digest` 는 그대로다.
- 새 값 조합: 시도 실패 인시던트(`unresolved: false`)에 `resolution.decision = REWORK`.
- 받은 `pick_place` 재작업이 첫 발행 직전 재검사를 못 지나면 같은 단위에 태스크 없는 실패 인시던트(`failureClass = REWORK_RECHECK`, `unresolved: false`, `fault` 널)가 열린다. 운영자 보류가 아니고 실행은 종결한다.

## 지을 읽는 코드
- `Snapshot.kt`·`ExportSymptoms.kt` 의 `SCHEMA_VERSIONS` 에 `"9"` 를 더한다(버전 7·8 과 함께).
- **재작업으로 이어진 인시던트를 접는다.** `resolution.decision = REWORK` 인 인시던트는 사람이 그 단위를 다시 내기로 한 인시던트이다 — 그 인시던트로 연 회복 에피소드는 «사람이 재작업으로 닫음» 으로 접고, 같은 단위의 다음 시도(인시던트 `attempt` 가 더 큰 줄)는 새 인시던트로 본다. «재작업 전 빈손·자리 확인됨» 은 `reworkBasis` 의 `hold`·`source`·`destination` 칸이 있고 그 **관측값**이 확인을 말할 때만(빈손, 출발 `occupied: true`, 목적지 `occupied: false`) 닫힘 근거로 싣는다. `occupied: null` 은 확인이 아니고, `{robotId, readAt}` 만 든 근거(점검·이동·건너뛴 쌍의 이동)는 «관측 없음» 이다. 한 묶음에 판단을 기다리던 인시던트가 여럿이면 각각 `REWORK` 가 붙는다.
- `REWORK_RECHECK` 인시던트는 기체의 실패가 아니라 «받은 재작업을 내기 직전에 손·자리가 달라 내지 않았다» 이다. 회복은 사람이 다시 재작업하는 것이고 승인 가능한 조치를 찾지 않는다 — 조치 탐색 레지스터에도 줄이 서지 않는다.
- 결정자 원문 id 의 규칙은 버전 8 그대로다.

## 이 노트가 닫지 않는 것
- 받지 않은 재작업의 근거는 내보내지 않는다(실행 트레이스 한 줄, 재기동·내보내기를 넘지 않음).

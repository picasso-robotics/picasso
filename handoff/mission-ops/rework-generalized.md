# 재작업 일반화 — 재작업 후보 입구, 판단 기록 버전 6, 내보내기 버전 9(§15.323)

받는 쪽: mission-ops. 반입 시점과 방법은 받는 쪽이 결정한다. mission-ops 는 picasso 를 프로세스 안에 세우므로 라이브러리의 값과 판단 기록 글자를 읽는다. 내보내기 칸의 정의는 `handoff/narrator/rework-generalized.md` 와 같다.

## 바뀐 것
- `Middleware.resolve(…, REWORK, approver)` 가 **보류가 아닌 단위의 재작업을 «묶음» 으로 받는다.** 건너뛴 쌍(ADR 53)은 그 특수형이다. 받는 모양은 셋이다.
  - 건너뛴 단위(`FAILED` ∧ `PREDECESSOR_NOT_DONE` ∧ 태스크 빔) — 지금과 같다.
  - 시도된 실패 점검·이동(`FAILED` ∧ 태스크 있음, 파지 효과 없는 스킬) — 손·자리를 묻지 않는다.
  - 시도된 실패 `pick_place` 계열(`SKILL_EXECUTION_FAILED` 의 잔여 파지 포함) — **물리 게이트**를 지날 때만이다. 기체 스냅숏의 손이 빈손, 목적지 선언이 `REQUIRED` 이고 설비가 비었다고 봄, 출발 자리에 그 자재가 있다고 봄, 자기 말고 다른 실행의 예약·작업 구역과 충돌 없음. 목적지 `OPTIONAL` 이면 언제나 `Refused` 다(새 작업 지시로 낸다).
- `UNVERIFIED`·`ABORTED`·승인된 조치 단위(승인 기록의 단계 id — 이름이 `remedy-` 로 시작하는 일반 대상은 조치 단위가 아니다)는 `Refused`. `DONE`·없는 단위·열린 단위는 `NotHeld` 그대로.
- **묶음.** 위로는 대상의 선행이 파지 효과 없는 단위면 `DONE` 이어도 함께 되돌리고, 아래로는 기다려 건너뛰어진 단위를 함께 되돌린다. 묶음의 어느 단위 id 로 요청해도 같은 묶음이다 — **앞 버전에서 `NotHeld` 였던 이동 id 요청과 시도 뒤 실패한 점검이 이제 받힌다.** 계획에서 이어지지 않은 묶음, 기다린 후손이 이미 나간 묶음은 `Refused`.
- 받을 때 **발행 게이트**도 본다 — 작업 허가·퇴역이 막거나 수용하지 않은 «새 태스크를 못 받는다» 결함이 있으면 `Refused`.
- `ResolveOutcome` 은 셋 그대로다. `Refused.reason` 의 문장이 늘었다.
- **첫 발행 직전 재검사.** 받은 `pick_place` 계열 단위는 첫 발행 직전에 손·자리를 다시 읽는다. 기체 스냅숏을 아직 못 읽었으면(미리 읽는 포트의 «읽는 중») 그 라운드에 내지 않고 기다리며(트레이스 한 줄), 처음 못 읽은 때부터 진행 정체 유예를 넘기면 실패로 닫는다. 기체가 손을 관측 불가·말하지 않음으로 답했거나 든 채·자리 점유·결품·침묵이면 곧바로 실패로 닫는다 — 그 시도는 **`FAILED` ∧ `failureClass = "REWORK_RECHECK"` ∧ 태스크 빔**이고 실패 인시던트가 열리며, 뒤 단위는 보통 규칙대로 가고 실행은 종결한다 — `FAILED` 면 기체를 놓고 `PARTIAL` 이면 앞선 규칙대로 쥔 채다. 리비전은 이 실패를 다시 계획하지 않는다(사람의 새 `REWORK` 만). **운영자 보류가 아니다.** 사람이 다시 낸 `REWORK` 는 물리 게이트를 다시 지나 시도가 하나 오르고(그래서 태스크 없는 시도 번호 하나가 소모된다 — `@r1` 없이 `@r2` 가 나갈 수 있다), `CONFIRM_DONE` 은 시도 실패 규칙대로 `Refused` 다. 재기동 뒤 기체가 그 시도의 태스크를 이미 들고 있으면 재검사하지 않는다. 재기동 뒤에도 판단 기록에서 같은 식으로 유도된다.

## 새 입구 — `Middleware.reworkCandidates(): List<ReworkCandidate>`
- 메모리 조건만 본다(모양, 실행의 종결, 같은 기체의 다른 활성 실행, 묶음 규칙). 포트를 읽지 않는다 — 발행 게이트·물리 게이트의 응답은 `resolve` 의 `Refused` 사유로만 온다. 후보에 올라도 `Refused` 일 수 있다.
- 칸: `executionId` · `unitId`(판단을 낼 대상) · `attempt`(대상의 지금 시도) · `bundle`(함께 되돌릴 단위 id, 계획 순서) · `kind`(`ReworkKind.SKIPPED` · `ATTEMPTED_INSPECTION` · `ATTEMPTED_HANDLING`) · `incidentUnitId`(판단이 붙을 인시던트의 단위, 없으면 널). 묶음 하나에 후보 하나다. 건너뛴 쌍은 `SKIPPED` 이고 대상은 건너뛴 점검(지금 화면의 대상과 같다)이며 `incidentUnitId` 는 실패한 이동이다.

## 지을 코드
- `MissionHost.reworkableTargets`(`PREDECESSOR_NOT_DONE` 술어를 사본으로 든 목록)를 **`reworkCandidates()` 로 바꾸고** 보존 만료 시각(`retainedUntil`)만 호스트가 덧붙인다. 술어를 두 벌로 두면 picasso 의 묶음 규칙과 갈린다.
- 화면의 재작업 목록에 **시도 실패 대상과 묶음**(`bundle`, `kind`)을 보인다. `ATTEMPTED_HANDLING` 은 «받을 때 손·자리를 본다» 를 함께 적는다. 건너뛴 쌍 모양이어도 앞 시도에서 태스크가 나간 `pick_place` 가 묶음에 있으면 `ATTEMPTED_HANDLING` 이다.
- **`REWORK_RECHECK` 실패의 화면 처리**: 시도 실패와 같은 줄로 보이되 «받은 재작업을 내기 직전에 손·자리가 달라 내지 않았다» 로 적고, 같은 대상을 다시 재작업할 수 있게 후보로 보인다(후보 입구가 그대로 낸다).
- **접수 첫 요청의 모름 거부**: 미리 읽는 포트는 처음 묻는 스냅숏을 아직 못 줄 수 있어 첫 `REWORK` 가 «스냅숏을 아직 못 읽었다» 로 `Refused` 일 수 있다. 사유 문장을 그대로 보이고, 다시 누를 수 있게 둔다(이 계층은 다음 요청이 통과한다고 약속하지 않는다).
- `Refused` 사유 문장은 **그대로 보인다** — 문자열을 뜯어 하위 범주를 나누지 않는다.
- `HoldResolutions.recheck` 의 «그 단위의 인시던트가 없으면 같은 실행의 인시던트로 넓힌다» 분기(`unit.ifEmpty { ofExecution }`)를 **지운다.** 판단은 묶음의 단위마다 서고 인시던트는 묶음 안의 판단을 기다리던 것마다 **각각** `REWORK` 가 붙으며(근거 `reworkBasis` 는 뿌리·물리 게이트를 지난 단위의 것만 — 건너뛴 쌍이면 이동의 인시던트에 `{robotId, readAt}` 표지), 응답을 잃은 재조회는 **대상·시도·판단**(판단 기록의 그 단위 `decision` 의 승인자·결정·시도·시각)으로 대조한다. 대응하는 판단이 없으면 성공으로 추정하지 않는다.
- **판단 사본 저장 틈**(`MissionHost.recordAfterPump`). 재작업은 `resolve` 안에서 판단을 적고 시도를 올린다. 지금은 다음 펌프 뒤의 기록에서야 바뀐 판단 기록을 적으므로, 그 펌프가 새 시도를 낸 뒤 저장 전에 죽으면 다시 지은 실행은 재작업을 모른 채 앞 실패로 서고 기체에는 새 시도가 나가 있다. `resolve` 가 `Resolved` 를 돌려준 그 호출 안에서(다음 펌프 전에) 판단 기록을 적는다 — 승인 적용의 저장 계약(§15.320)을 판단에도 넓히는 것이다.
- 판단 기록 버전 6 수용 — 아래.
- 내보내기 버전 9 수용 — mission-ops 는 파일을 읽지 않지만 `IncidentResolution.reworkBasis` 가 붙었다. 인시던트 사본(`CopyResolutionRow`)에 근거를 싣는다면 칸 모양은 narrator 노트를 따른다.
- **화면의 표 열이 바뀌면 `ui/e2e` 의 `nth(N)`·문구 단언을 먼저 고친다.**

## 판단 기록 버전 6 — 구조 변경 없음(확인)
- `JudgmentRecordWire.SCHEMA_VERSION` 이 **"5" → "6"**. 판단 칸(`decision`)에 선택 칸 둘이 붙었다 — `reworkBasis {robotId, hold?{kind, objectRef?}, source?{slotId, occupied?, identity?, observedAt?}, destination?{같은 모양}, readAt}` 과 `incident {incidentKey, firstOpenedAt}`. **시도된 실패 대상의 재작업 판단에만** 서고 없으면 키를 싣지 않는다(버전 4·5 규칙) — 그런 재작업이 없는 기록은 버전 5 와 같은 모양이다.
- 기록 글자를 그대로 저장하므로 DB 구조 변경은 없다. 적는 행의 `wireVersion` 이 `"6"` 이 되고 버전 1~5 행도 그대로 읽힌다(두 칸 널).
- 새 값 조합: `DONE` 이던 이동에 `REWORK` 판단이 선다(묶음의 위로 되돌린 단위). 판단 기록을 «보류였던 단위만 판단을 든다» 로 읽는 자리가 있으면 고친다.
- **건너뛴 쌍의 판단도 바뀌었다**: 묶음 뿌리(실패한 이동)의 판단에 요청한 id 와 무관하게 `incident` 와 `reworkBasis {robotId, readAt}` 표지가 실린다. 표지는 «관측 없음» 이다 — 손·자리를 확인했다는 뜻이 아니다.
- 단위 행에 선택 칸 `issuedInEarlierAttempt`(참일 때만, 앞 시도에서 태스크가 나갔음)가 붙었다. 판 1~5 행은 `attempt > 0` 이면 참으로 읽는다.
- `RecordedDecision` 의 생성 인자가 둘 늘었다(기본값 널) — 소스 호환이다.

## 이 노트가 닫지 않는 것
- 손을 못 읽는 기체·목적지 `OPTIONAL` 현장·출발 자리 침묵 현장의 시도된 `pick_place` 실패는 재작업 길이 없다(새 작업 지시만). 출발 선언이 `OPTIONAL` 이면 `Ready` 는 신원 없는 «있다» 일 수 있다. 한계 레지스터 행.
- 일괄 재작업은 여전히 없다(한 요청 한 묶음).
- 거부 근거 트레이스(`REWORK_REFUSED`)는 재기동과 내보내기를 넘지 않는다.

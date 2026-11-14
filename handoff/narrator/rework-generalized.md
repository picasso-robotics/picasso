# 재작업 일반화 — 내보내기 버전 9(§15.323)

받는 쪽: incident-narrator. 무엇을 언제 반입할지는 받는 쪽이 결정한다. 이 노트는 picasso 가 낸 사실과, 그 칸마다 지어야 할 읽는 코드다(ADR 9 — 읽는 코드 없는 칸은 싣지 않는다).

## 바뀐 것
- 내보내기 버전 **8 → 9**. 버전 8 의 칸은 그대로다.
- 인시던트 줄 `resolution` 에 한 칸: `reworkBasis` — 판단이 있으면 언제나 키가 있고, 값은 널이거나 아래 객체다. 이 인코더의 원칙대로 **안의 빈 칸도 널로 싣는다.**
  - `robotId`(문자열) — 손을 읽은 기체.
  - `hold`(널 또는 `{kind, objectRef}`) — 받을 때 읽은 기체 스냅숏의 손. `kind` 는 protobuf 열거 이름(`HOLD_KIND_EMPTY` 등), `objectRef` 는 널 또는 손이 말한 물건 이름. 손에는 원천 시각이 없다(계약 `task.proto`). 널이면 손을 묻지 않았다(점검·이동).
  - `source`·`destination`(널 또는 `{slotId, occupied, identity, observedAt}`) — 받을 때 읽은 셀 신호. `occupied` 널은 **물었으나 설비가 말이 없었다**, `observedAt` 널은 설비가 원천 시각을 주지 않았다(읽은 순간). 객체 자체가 널이면 묻지 않았다.
  - `readAt`(ISO-8601) — 읽기를 마친 시각(가상 시계).
- `reworkBasis` 는 **보류가 아닌 재작업을 받을 때** 묶음 뿌리(시도된 실패)와 물리 게이트를 지난 단위의 인시던트에 붙은 `REWORK` 판단에만 값이 있다. 물리 게이트를 지난 `pick_place` 계열은 `hold`·`source`·`destination` 을 싣고, 시도된 점검·이동과 건너뛴 쌍의 이동은 `robotId`·`readAt` 만 싣는다(표지 — **관측 없음**). 보류 판단·다른 단계(`CONFIRM_DONE`·`OPERATOR_CANCEL`·`REMEDY_APPLIED`)는 널이다. 한 묶음에 판단을 기다리던 인시던트가 여럿이면 각각 `REWORK` 가 붙고 근거는 뿌리·게이트를 지난 단위의 것에만 있다.
- **새 값 조합**: 시도 실패 인시던트(`unresolved: false`, 예: `GRASP_FAILED`·점검 실패)에 `resolution.decision = REWORK` 가 선다. 앞 버전에는 건너뛴 쌍의 이동 인시던트에서만 이 조합이 나왔다.
- **재검사 실패**: 받은 `pick_place` 재작업의 첫 발행 직전에 손·자리가 게이트를 못 지나면(또는 스냅숏을 못 읽은 채 기다림 창을 넘기면) 그 시도를 내지 않고 **태스크 없는 실패 인시던트**가 열린다 — `failureClass = REWORK_RECHECK`, `unresolved: false`, `openingKind: NEW`, `attempt` 는 재작업 시도, `fault` 는 널. 앞 인시던트의 `REWORK` 판단은 그대로다. 사람이 다시 재작업하면 이 인시던트에 `REWORK` 가 붙는다.
- `digest` 는 그대로다 — `resolution` 은 해시에서 빠진다. 같은 시드의 요약은 버전 8 과 같다.

## 인계 번들
- `run-1`~`run-4`·`run-5a`·`run-5b` 를 버전 9 로 다시 산출했다. 그 시나리오에는 시도된 실패의 재작업이 없어 `resolution` 은 널이거나 `reworkBasis: null` 이다. `digest`·가상 시각·탐색 줄은 버전 8 과 같다.

## 지을 읽는 코드
- `receiver/export.py` 의 `SCHEMA_VERSIONS` 에 `"9"` 를 더한다(버전 8 수용과 함께).
- `resolution.reworkBasis` 를 설명에 쓴다면: **`hold`·`source`·`destination` 칸이 있고 그 관측값이 확인을 말할 때만** «재작업 전에 기체의 빈손(`hold.kind = HOLD_KIND_EMPTY`)·출발의 자재(`occupied: true`)·빈 목적지(`occupied: false`)를 확인했다» 와 그 **관측 시각**(`readAt`, 자리의 `observedAt` 이 있으면 그것)으로 말한다. `occupied: null` 은 확인이 아니고, `{robotId, readAt}` 만 든 근거는 «관측 없음» 이다 — 이때 «확인했다» 고도 «확인하지 않고 재작업했다» 고도 말하지 않는다(점검·이동과 건너뛴 쌍은 묻지 않는 것이 규칙이다). 출발 선언이 `OPTIONAL` 이면 `occupied: true` 와 `identity: null` 은 «무엇인지 모르는 것이 있다» 이지 그 자재의 확인이 아니다.
- `occupied: null` 은 «설비가 말이 없었다» 이고 «비었다» 가 아니다. 다만 받은 재작업에는 이 값이 나오지 않는다(침묵은 거부한다).
- 재검사 실패(`REWORK_RECHECK`)는 «재작업을 받았으나 내기 직전에 손·자리가 달라(또는 읽지 못해) 내지 않았다» 로 설명한다. 기체가 실패한 것이 아니고 태스크가 없다.
- 결정자 원문 id 의 규칙(설명 입력에는 `kind` 만)은 버전 8 그대로다.

## 이 노트가 닫지 않는 것
- 받지 않은 재작업(`Refused`)의 근거는 내보내지 않는다 — 실행 트레이스의 한 줄이고 재기동·내보내기를 넘지 않는다(한계 레지스터).

# 감사 내보내기 묶음 — 내보내기 버전 8 과 재기동 장면 한 벌(§15.322)

받는 쪽: incident-narrator. 무엇을 언제 반입할지는 받는 쪽이 결정한다. 이 노트는 picasso 가 낸 사실과, 그 칸마다 지어야 할 읽는 코드다(ADR 9 — 읽는 코드 없는 칸은 싣지 않는다는 원칙은 그대로이며, 이번에는 읽는 쪽이 함께 짓는다).

## 바뀐 것
- 내보내기 버전 **7 → 8**. `receiver/export.py` 의 `SCHEMA_VERSIONS` 는 지금 `("5", "6")` 이라 판 7 도 못 받는다 — 판 8 수용 때 판 7 의 `resolution.decision` 값 둘(`OPERATOR_CANCEL`·`REMEDY_APPLIED`, `hold-refusal-recovery.md`)도 함께 처리한다.
- 인시던트 줄에 정체성 넷:
  - `incidentKey`(문자열 또는 널) — **불투명한** 열쇠. 최초 봉인마다 하나 서고, 같으면 같은 사건이다. 뜯어 읽지 않는다.
  - `firstOpenedAt`(ISO-8601 또는 널) — 그 사건이 처음 선 가상 시각. 다시 연 인시던트의 `at` 은 다시 연 시각이고 이것은 처음 선 시각이다.
  - `openingKind`(`NEW` · `REOPENED`, 필수) — `REOPENED` 는 재기동이 판단 기록의 운영자 보류로 다시 연 인시던트. picasso 의 재개 경로가 직접 채운다.
  - `attempt`(정수, 필수) — 봉인 때 그 단위의 시도 번호.
  - `incidentKey`·`firstOpenedAt` 의 널은 옛 판 판단 기록으로 다시 연 인시던트의 **연결 불명**이다(이 경우 `openingKind` 는 `REOPENED`). 새로 선 인시던트(`NEW`)에는 언제나 값이 있다.
- `resolution.decidedBy` — `{id, kind}`, 같은 줄 `approvedBy` 와 같은 모양. 판단(`resolution`)이 있으면 언제나 있다.
- `intent.missionVersion`(정수 또는 널, 널은 코드 정의 — 실행이 쥔 버전).
- `intent.siteSettingsVersion`(정수 또는 널) · `intent.inDoubtGrace` · `intent.stallWindow`(ISO-8601 기간 또는 널) — **셋이 함께 널이거나 함께 있다**(picasso 인코더가 단언). 봉인 라운드의 값이며, 과거 근거 기한을 정한 설정이라고 설명하면 안 된다.
- 탐색 줄 `outcome` 에 셋: `SOURCE_UNOBSERVED {material?, source, occupied?}`(`occupied` 널은 설비 침묵, 참은 «있다» 고만 했음), `DESTINATION_OCCUPIED {destination, observed?}`(`observed` 널은 점유 신원 불명), `DESTINATION_UNOBSERVED {destination}`. 셋 다 `steps` 키가 없다.
- `manifest.json` 에 `instanceId`. 한 벌은 한 인스턴스의 것이다. 인시던트 id 는 인스턴스 안의 셈이라 재기동 뒤 같은 `incident-1` 이 다시 나온다(`run-5a`·`run-5b` 가 실물).
- **`digest` 는 그대로다.** 새 칸은 전부 해시에서 빠졌다. 멱등 열쇠 `(runId, digest)` 도 그대로 쓴다.

## 인계 번들
- `run-1`~`run-4` 를 버전 8 로 다시 산출했다. `digest`·가상 시각·탐색 줄은 버전 7 과 같고, 실 시계·`runId`·새 칸만 다르다. 넷 다 코드 케이퍼빌리티로 돌아 임무 버전과 설정 셋이 널이고 모든 인시던트가 `NEW` 다. `ground-truth.jsonl` 은 그대로다.
- 새 한 벌 **`run-5a`·`run-5b`** — 재기동 장면. `run-5a` 는 앞 인스턴스(인시던트 하나 `NEW`, 탐색 셋 `SOURCE_UNOBSERVED`·`DESTINATION_OCCUPIED`·`DESTINATION_UNOBSERVED`), `run-5b` 는 그 판단 기록으로 다시 지은 인스턴스(같은 보류를 다시 연 인시던트 하나 `REOPENED`). 두 벌의 인시던트는 같은 `incidentKey`·`firstOpenedAt` 으로 이어지고 `digest`·`instanceId`·`at` 은 다르다. 이 벌만 임무 정의와 현장 설정으로 돌아 `missionVersion` 과 설정 셋이 값을 싣는다. 재실행 쌍이 아니다.

## 지을 읽는 코드
- **재발 셈**(`receiver/history.py`): 같은 `incidentKey` 를 가진 줄은 하나의 사건으로 접는다 — **현재 사건과 과거 목록 모두에서**. 지금은 다시 연 과거 행만 빼서, 다시 연 쪽이 현재 사건이면 원래 보류를 한 번 센다(그 모듈 머리말의 ⛔ 2026-10-18 절). 근거 창의 `RESUME_HOLD_REOPENED` 줄 시각을 `at` 과 맞대던 추정(`_reopened`)은 버전 8 줄에서는 `openingKind`·`incidentKey` 로 바꾼다(버전 5·6·7 줄에는 그 추정이 여전히 유일한 근거다).
- **연결 불명은 접지 않는다.** `incidentKey` 가 널인 `REOPENED` 줄을 실행·단위·분류로 추측해 원래 보류에 잇지 않는다.
- **연결 불명(키 널)은 이후 재기동에서도 널로 이어진다.** «널이면 매번 새 사건» 으로 세지 말고 «연결 불명» 으로 따로 다룬다 — 같은 보류가 재기동마다 널 키 줄을 하나씩 더 낼 수 있다.
- **조치 실패로 선 미발행 보류(§15.320, `failureClass = REMEDY_NOT_DONE`)는 거부 때 인시던트의 키를 싣는다.** 둘째 인시던트를 열지 않기 때문이며, 그래서 그 키의 `attempt` 와 단위의 지금 시도가 한 칸 어긋날 수 있다 — `attempt` 가 잇는 열쇠가 아닌 사례다.
- **`attempt` 는 재발 판정에만 쓴다.** 같은 보류를 잇는 데 쓰지 않는다 — 같은 시도에서 미확정과 보류가 각각 인시던트를 열 수 있다. 다시 연 뒤 재작업해 또 깨진 보류는 시도가 오르고 새 열쇠(`NEW`)다.
- **결정자**: `decidedBy.id` 원문은 감사 조회와 승인자 연결(같은 사람의 승인과 판단 잇기)에서만 읽는다. **설명 생성 입력에는 `kind` 만 넘긴다** — id 는 호출자의 주장이지 인증 증명이 아니다(picasso ADR 47).
- 질의 사실에 임무 버전과 설정 셋을 싣는다면 셋의 함께 널 규칙을 지키고, «봉인 라운드의 값» 으로만 설명한다.
- **탐색 새 갈래 셋은 승인 가능한 조치로 승격하지 않는다.** 모르는 `outcome` 을 버리지 않고, 셋은 «사람이 확인하거나 치울 일» 로 설명한다(출발 미관측은 결품이 아니라 확인 요구).

## 이 노트가 닫지 않는 것
- 허가 차단(§15.219)은 이 판에 없다 — 별도 이벤트 파일과 작업 응답의 다음 판으로 따로 온다.
- 사람이 목적지를 확인·덮어쓰는 입구는 없다(§15.235 행).

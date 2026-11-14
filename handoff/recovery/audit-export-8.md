# 감사 내보내기 묶음 — 내보내기 버전 8(§15.322)

받는 쪽: incident-recovery. 반입 시점과 방법은 받는 쪽이 결정한다. 칸의 정의는 `handoff/narrator/audit-export-8.md` 와 같으며, 여기에는 recovery 가 지을 읽는 코드만 적는다.

## 바뀐 것(요약)
- 내보내기 버전 **7 → 8**. `core/.../Snapshot.kt` 와 `runtime/.../ExportSymptoms.kt` 의 `SCHEMA_VERSIONS` 가 지금 `setOf("5", "6")` 이다 — 판 7 의 `resolution.decision` 값 둘과 함께 판 8 을 받는다.
- 인시던트 줄: `incidentKey`(불투명, 널은 연결 불명) · `firstOpenedAt` · `openingKind`(`NEW`·`REOPENED`) · `attempt` · `resolution.decidedBy {id, kind}` · `intent.missionVersion` · `intent.siteSettingsVersion`·`inDoubtGrace`·`stallWindow`(함께 널이거나 함께 있음).
- 탐색 줄 `outcome`: `SOURCE_UNOBSERVED` · `DESTINATION_OCCUPIED` · `DESTINATION_UNOBSERVED`(셋 다 `steps` 없음).
- `manifest.json`: `instanceId`. `digest` 는 그대로다.
- 인계 번들: `handoff/narrator/run-1`~`run-4`(판 8 재산출, `digest` 그대로)와 재기동 장면 `run-5a`·`run-5b`(같은 `incidentKey` 로 이어지는 두 인스턴스의 벌).

## 지을 읽는 코드
- **새 회복 에피소드를 같은 `incidentKey` 로 접는다.** 지금 사건 신호의 이벤트 id 는 `incident:<runId>:<digest>`(`ExportSymptoms.eventId`)라, 재기동이 다시 연 인시던트(`REOPENED`, 다른 `digest`)가 같은 보류에 회복을 또 시작한다. 같은 열쇠의 줄은 지금 도는 에피소드와 이미 끝난 에피소드 **모두에서** 같은 사건으로 본다. 이벤트 id 자체(수신 멱등)는 그대로 두고, 에피소드를 여는 판정에서 열쇠를 본다.
- **연결 불명은 접지 않는다.** `incidentKey` 널인 `REOPENED` 줄은 새 에피소드로 다루되, 실행·단위·분류로 추측해 앞 에피소드에 잇지 않는다.
- **연결 불명(키 널)은 이후 재기동에서도 널로 이어진다.** «널이면 매번 새 사건» 으로 세지 말고 «연결 불명» 으로 따로 다룬다 — 같은 보류가 재기동마다 널 키 줄을 하나씩 더 낼 수 있다.
- **조치 실패로 선 미발행 보류(§15.320, `failureClass = REMEDY_NOT_DONE`)는 거부 때 인시던트의 키를 싣는다.** 둘째 인시던트를 열지 않기 때문이며, 그래서 그 키의 `attempt` 와 단위의 지금 시도가 한 칸 어긋날 수 있다 — `attempt` 가 잇는 열쇠가 아닌 사례다.
- `attempt` 는 재발 판정에만 쓰고 같은 보류의 연결에는 쓰지 않는다.
- **탐색 새 갈래 셋은 승인 가능한 조치로 승격하지 않는다.** 승인 시도(`POST /approvals`)의 대상은 여전히 `FOUND` 뿐이다. 셋은 진단 스냅숏 경계까지 운반하되 «사람의 확인·치움 대기» 로 둔다.
- 결정자 `decidedBy.id` 는 감사 기록·승인자 연결(같은 사람의 승인과 판단)에서만 읽고, 설명이나 진단 문장 생성 입력에는 `kind` 만 넘긴다.
- 설정 셋을 진단에 쓴다면 함께 널 규칙을 지키고 «봉인 라운드의 값» 으로만 읽는다.

## 이 노트가 닫지 않는 것
- 허가 차단(§15.219)은 이 판에 없다. 미관측·허가 차단을 회복 대상으로 삼는 일도 이 판의 몫이 아니다.

# 감사 내보내기 묶음 — 판단 기록 버전 5 와 인시던트 정체성(§15.322)

받는 쪽: mission-ops. 반입 시점과 방법은 받는 쪽이 결정한다. mission-ops 는 picasso 를 프로세스 안에 세우므로 내보내기 파일이 아니라 라이브러리의 값과 판단 기록 글자를 읽는다. 칸의 정의는 `handoff/narrator/audit-export-8.md` 와 같다.

## 판단 기록 버전 5 — 구조 변경 없음(확인)
- `JudgmentRecordWire.SCHEMA_VERSION` 이 **"4" → "5"**. 판정 칸(`verdict`)에 `incidentKey`·`firstOpenedAt` 두 칸이 붙었다. 운영자 보류 판정에만 서고, 없으면 두 키를 싣지 않는다(판 4 의 `position`·`preconditionSubjects` 와 같은 규칙) — 보류가 없는 기록은 판 4 와 같은 모양이다.
- mission-ops 는 기록 글자를 그대로 저장하고(V6 `execution_judgment_record`, `HostRecords.judgmentRecord`) picasso 로 읽으므로 **DB 구조 변경은 없다.** 적는 행의 `wireVersion` 이 `"5"` 가 될 뿐이고, 이미 적어 둔 판 1~4 행도 그대로 읽힌다(판 4 로 다시 연 인시던트는 정체성 널 — 연결 불명).
- ★**키는 봉인과 같은 라운드에 기록에 실린다.** 보류가 선 `pump` 뒤에 받은 `judgmentRecord` 에 그 보류의 정체성이 있다. 지금처럼 그 `pump` 의 기록을 같은 트랜잭션에 적으면 된다 — 미루면 그 사이 죽은 인스턴스의 기록으로 다시 연 인시던트가 원래 보류와 이어지지 않는다.

## 인시던트 값
- ★**`Middleware.instanceId` 가 인시던트 키의 유일성을 진다.** 키는 최초 봉인의 (인스턴스 id, 인시던트 id)이고 인시던트 id 는 재기동마다 `incident-1` 부터 다시 센다. 인스턴스 id 는 재기동마다 새 값이어야 한다 — **고정하지 말 것**(호스트가 생성 인자로 넘긴다면 기동마다 새로 짓는다). 고정하면 다른 사건이 같은 키를 얻어 읽는 쪽이 서로 다른 보류를 한 사건으로 접는다.
- 정체성은 번들의 보존 상한과 무관하다 — 그 실행이 실행 표에 있는 동안은 번들이 내려가도 판단 기록이 키를 싣는다. 실행을 `forget` 한 뒤의 기록은 없다(지금과 같음).
- `IncidentBundle` 에 `origin: IncidentOrigin?`(`key`·`firstOpenedAt`) · `openingKind: OpeningKind`(`NEW`·`REOPENED`) · `attempt: Int` 가 붙었다. `Intent` 는 설정 셋이 함께 널이거나 함께 있지 않으면 서지 않는다.
- `IncidentResolution.decidedBy` 는 그대로다(mission-ops 는 이미 저장한다 — `CopyResolutionRow`).

## 지을 읽는 코드
- 인시던트 사본(`IncidentViews.detail` → `copyIncident`)에 `incidentKey`·`firstOpenedAt`·`openingKind`·`attempt` 를 싣고, 판단 화면에서 이전 인스턴스의 사본과 **같은 `incidentKey`** 로 잇는다(«재기동이 다시 연 보류» 표시). 열쇠가 널인 `REOPENED` 는 연결 불명으로 표시하고 추측해 잇지 않는다.
- `e2e` 의 `RestartRecoveryTest` 가 근거 윈도우의 `RESUME_HOLD_REOPENED` 줄 시각을 `at` 과 맞대 다시 연 것을 가르는 자리는 `openingKind`·`incidentKey` 단언으로 바꿀 수 있다.
- `attempt` 는 재발 판정에만 쓴다. 같은 보류의 연결에는 쓰지 않는다.
- **연결 불명(키 널)은 이후 재기동에서도 널로 이어진다.** «널이면 매번 새 사건» 으로 세지 말고 «연결 불명» 으로 따로 다룬다 — 같은 보류가 재기동마다 널 키 줄을 하나씩 더 낼 수 있다.
- **조치 실패로 선 미발행 보류(§15.320, `failureClass = REMEDY_NOT_DONE`)는 거부 때 인시던트의 키를 싣는다.** 둘째 인시던트를 열지 않기 때문이며, 그래서 그 키의 `attempt` 와 단위의 지금 시도가 한 칸 어긋날 수 있다 — `attempt` 가 잇는 열쇠가 아닌 사례다.
- 결정자 원문 id 는 감사 조회·승인자 연결에서만 읽는다(이미 그렇다). 설명 문장을 만드는 자리가 생기면 `kind` 만 넘긴다.

## 소스 호환
- `BundleWriter` 가 인스턴스 id 를 생성 인자로 받는다(`BundleWriter(dir, instanceId)`). mission-ops 는 쓰지 않는다.
- `Admission.Refused` 의 칸 `sourceMissing` 이 넷을 드는 `occupancy` 로 넓어졌다(`sourceMissing` 은 계산 값으로 남음). `RemedyOutcome` 에 갈래가 셋 늘어 그 위의 `when` 은 셋을 더해야 한다 — 출발 미관측은 기존 최상위 타입 `SourceUnobserved` 가 그 갈래다. mission-ops 는 `as? RemedyOutcome.SourceMissing` 만 써서 영향이 없다(확인).

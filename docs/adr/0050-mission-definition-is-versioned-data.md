# ADR 50 — 임무 정의를 검증을 지난 데이터 버전으로 두고 실행은 생성 때 쥔 임무 버전으로 끝낸다

- 상태: 확정 (2026-10-08)
- 관련: [ADR 9](0009-no-declaration-without-consumer.md) 소비자 존재 원칙, [ADR 32](0032-safety-boundary.md) 안전 경계, [ADR 36](0036-work-first-assignment-vs-execution.md), [ADR 38](0038-mission-layer-schema-is-ours.md)
- 변경 이력: 설계 문서 §15.209

## 맥락

41beedb 까지 임무 정의는 코드였다. `PrepareSequencedRack.plan()`(`LogicalCapability.kt` 101~127행)이 작업 지시의 destination 마다 `pick_place` 단위 하나를 내고, 제시 자리는 material 로 짝을 지어 같은 material 이 둘이면 뒤엣것이 이기며, 짝이 없으면 계획 때 `FAILED`(`NO_SOURCE_FOR_MATERIAL`)로 끝냈다. `Middleware` 는 케이퍼빌리티 목록을 workMasterId 로 묶은 맵을 생성 때 쥐었고 실행 중에 바꿀 길이 없었다. 임무 버전 칸은 없었다. 실행은 생성 때 케이퍼빌리티 객체를 쥐었으나 `submit` 이 리비전으로 가기 전에 그 맵을 다시 읽고 최고 근거 등급을 검사했다.

경로는 `ROBOT`·`FLEET` 둘이었고 진행 루프의 분기는 «로봇이 아니면 플릿» 이었다. 셀 신호 포트는 자리별 점유(`observe`)와 `holding` 뿐이라 이름으로 읽는 신호가 없었다. 안전 신호라는 개념은 코드에 없었다(ADR 32 는 안전 기능이 이 소프트웨어 계약을 거치지 않는다고 정한다).

바깥 소비자는 picasso-ops(운영 화면 PoC)다. 운영 관리 화면 설계 제안(`docs/superpowers/specs/2026-10-07-ops-console-lifecycle-design.md`) §10 의 입증 항목 3은 임무 하나를 데이터 버전으로 두고 모의 실행 → 활성화 → 진행 중인 실행이 있는 동안 새 버전 전환 → 옛 실행은 옛 버전으로 끝남을 보이는 것이며 «이것이 핵심» 이라 적었다. 항목 4는 잘못된 변경 하나(신호 사양에 없는 신호 참조)가 활성화에서 거부되는 것이다. §6 은 노드 2종(단위·설비 대기), 검사 5가지, 직선만, 단위 안 9상태는 엔진이 고정, 실행이 임무 버전 번호를 드는 것을 정했다. §9 는 인시던트 기록에 버전 번호를 적게 했다. 2026-10-08 사용자 결정은 임무 버전을 먼저 picasso 에 짓고 picasso 시험 위에서 입증하며, 노드형 스키마와 설비 대기까지 엔진에 구현하는 것이다. 소비자가 생겨서 지었다(ADR 9).

## 결정

**임무 정의는 노드형 JSON 이다.** 최상위 5칸(`schemaVersion` 1, `workMasterId`, `maxEvidence` E0~E2, `preferredOptionals`, `steps` 1개 이상)은 모두 필수다. 노드는 `kind` 로 `unit`·`wait` 를 나눈다. 단위 노드는 `id`·`skill`·`forEach`·`unitId`·`parameters` 가 필수이고 `expectedIdentity`·`source`·`destination`·`sourceObservation`(`REQUIRED`·`OPTIONAL`)·`destinationObservation`(`REQUIRED`·`OPTIONAL`) 과 `pairWith`+`whenUnpaired`(둘이 함께)가 선택이다. 값 출처는 `{"from":"ITEM_ID"}`·`{"from":"ITEM_PROPERTY","property":…}`·`{"from":"PAIRED_ID"}` 셋이고 선택 대체값 `otherwise` 를 둘 수 있다. `unitId` 는 `ITEM_ID` 만 받는다. 속성이 없으면 null 이고(코드의 `expectedIdentity` 와 같다) 값이 없는 파라미터는 빠진다. 대기 노드는 `id`·`signal`·`expect`·`deadlineSeconds`·`onDeadline` 이다. 표기는 `MissionDefinitionParser` 의 KDoc 과 시험 `MissionFixtures` 에 있다.

**파서는 모양과 형만 본다.** 모르는 키, 빠진 칸, 형 틀림, 중복 키, 문법 오류를 모두 모아 한 번에 낸다. 새 의존은 없다. protobuf-java-util 의 `JsonFormat` 으로 `Struct` 를 만들고 엄격 매핑은 손으로 한다. `StrictJson` 이 문법과 중복 키를 본다(`JsonFormat` 은 중복 키에 말없이 뒤엣것을 쓴다). 기한 값과 기한 뒤 상태의 판정은 검증기의 일이다.

**해석기 `DefinedCapability` 가 정의를 `LogicalCapability` 로 바꾼다.** 실행·리비전·인시던트는 지금 경로를 그대로 탄다. 데이터로 옮긴 것은 `PrepareSequencedRack` 하나이고 코드와 같은 계획을 칸마다 낸다(동등성 시험). 코드 클래스는 동등성 기준으로 남는다. `DeliverContainer`·`InspectAsset` 은 코드 정의로 남아 같은 카탈로그에 함께 선다.

**설비 대기는 셋째 경로 `Route.SIGNAL` 이다.** 판정은 신호의 지금 값이다. 기대 값이면 `DONE`·E2 로 끝나고 못 읽으면 계속 기다린다. 기한을 넘으면 인시던트(`SIGNAL_DEADLINE`)를 내고 `onDeadline` 대로 간다. `OPERATOR_HOLD` 는 대기 단위가 운영자 보류로 서서 라인이 멈추고 확인(`CONFIRM_DONE`)·재작업(`REWORK`)·취소로 풀린다. `ABORTED` 는 대기 단위가 `FAILED` 로 남고 남은 단위를 `ABORTED` 로 적은 뒤 실행을 중단하며 취소 응답은 남기지 않는다. 두 경우 모두 인시던트다. 진행 루프의 경로 분기를 «로봇이 아니면 플릿» 에서 경로별 `when` 으로 바꿨다.

**셀 신호 포트에 `signal(name): NamedSignal?` 을 더한다.** 기본 구현은 null 이라 기존 구현체는 깨지지 않는다. 어느 주소가 어느 신호인지는 드라이버의 일이다.

**검증기는 라이브러리다.** 화면과 활성화가 같은 규칙으로 막는다. 노드 id 중복과 기한·신호·자원·스킬·안전·출발 관측·목적지 관측 일곱 검사다. 거부는 운영 관리 화면 설계 제안 §8.4 의 6칸(부족한 조건은 종류로, 관측값·기대값, 마지막 확인 시각은 활성화 시도 시각, 근거 버전은 해당 없음, 해결 담당, 바로 갈 작업)에 노드 id 를 더한 것이다. 종류는 `UNREADABLE`·`DUPLICATE_NODE_ID`·`DEADLINE_INVALID`·`SIGNAL_NOT_IN_SPEC`·`SIGNAL_VALUE_INVALID`·`FLOOR_UNOWNED`·`SKILL_NOT_IN_CONTRACT`·`SKILL_NOT_ON_SITE`·`SAFETY_SIGNAL_WAIT`·`SOURCE_OBSERVATION_UNDECLARED`·`SOURCE_OBSERVATION_UNUSED`·`DESTINATION_OBSERVATION_UNDECLARED`·`DESTINATION_OBSERVATION_UNUSED` 열셋이고, 해결 담당은 바닥 소유만 화면 밖(`OUTSIDE_CONSOLE`), 출발 관측과 목적지 관측의 각 두 종류(미선언, 쓰이지 않는 선언)는 엔지니어(`ENGINEER`)다.

**포트 `MissionCatalog` 와 메모리 구현을 둔다.** 포트는 middleware 패키지에, `InMemoryMissionCatalog` 는 mission 패키지에 있어 의존은 한 방향이다. 활성화는 검증을 통과하면 그 WorkMaster 의 다음 버전(1부터)이 되고 거부면 활성 버전이 그대로이며 거부된 시도는 번호를 쓰지 않는다. 검증기 입력(신호 사양·바닥 소유·현장 기체 스킬)은 활성화 호출의 인자다. 구현은 `synchronized` 로 스레드 안전하다. `Middleware` 생성자 맨 뒤에 `missions` 를 두었다. 기본은 코드 케이퍼빌리티 셋이고 버전이 없다. `capabilities` 와 함께 주면 생성이 실패한다.

**실행은 생성 때 임무 버전을 쥔다.** `Execution.missionVersion` 이다. 이미 있는 실행이면 `submit` 은 카탈로그를 보지 않고 리비전으로 간다. 리비전은 실행이 쥔 케이퍼빌리티로 계획하고 근거 등급을 검사하며, WorkMaster 를 바꾸는 리비전은 거부한다. `adopt` 는 카탈로그를 한 번 읽은 쌍(케이퍼빌리티·버전)을 작업 수락까지 넘긴다.

**인시던트는 실행이 쥔 버전을 싣는다.** `Intent.missionVersion` 이고 해시에 든다(코드 케이퍼빌리티 실행은 null 이라 빈 문자열). 내보내기는 싣지 않는다 — (갱신, §15.322) 내보내기 버전 8 이 의도의 칸 `intent.missionVersion` 으로 싣는다(널은 코드 정의). 조회 버전 5→6 은 `route` 의 하위 범주 `SIGNAL` 때문이다. 로봇 계약과 작업 응답에는 임무 버전 칸을 더하지 않는다.

## 왜 이 모양인가

- 파서와 검증기를 나눴다. 기한 검사를 파서가 하면 그 검사를 빼는 결함이 검증기 시험에 안 잡힌다. 결함 주입 I4a 는 검증기 시험이 잡았다.
- 판정은 지금 값이다. P3 의 신호는 상태 신호뿐이다. 시작 전부터 그 값이면 그 상태는 이미 성립한 것이다. 이벤트형 신호는 범위 밖이다(한계 §15.209).
- 기한 뒤 `ABORTED` 를 `FAILED` 단위 하나로 두지 않았다. 엔진은 `FAILED` 단위 뒤에도 다음 단위를 내보낸다. 다만 §15.234부터 선행 단위를 선언한 단위는 예외이며, 실패한 이동 단위 뒤의 점검 단위는 내보내지 않는다. 신호를 못 본 채 로봇이 움직이면 대기를 둔 뜻이 없다. 정산을 기존 규칙에 맡기면 `[FAILED, …]` 가 부분 완료(`PARTIAL`)가 되어 리비전으로 다시 열린다. 남은 단위를 먼저 `ABORTED` 로 적고 같은 라운드에서 반환해야 다음 단위가 출발하지 않는다(결함 주입 I3a·I3b). 취소 응답은 남기지 않는다. 아무도 취소하지 않았는데 취소 응답이 있으면 «누가 멈췄나» 에 거짓으로 응답한다.
- 자원 검사는 게이트와 같은 규칙이다(`Unowned` 만 거부). 레지스터를 안 붙인 배치에서는 모든 자리가 `NotDeclared` 라, 선언 없음을 거부로 읽으면 자리가 적힌 신호를 쓰는 정의가 모두 거부된다.
- 스킬 검사는 둘이다. 계약에 있는 스킬인가와 현장이 제공하는 스킬인가는 다른 물음이고 후속 행동이 다르다.
- 리비전은 실행이 쥔 케이퍼빌리티로 계획한다. 카탈로그를 다시 보면 활성 버전이 바뀐 뒤의 리비전이 새 버전의 단위(예: 대기 단위)를 옛 실행에 들인다(결함 주입 I5 를 시나리오 시험이 잡았다). `adopt` 가 읽은 쌍을 넘기는 것도 같은 이유다. 게이트에 댄 계획과 실행이 쥘 계획이 같은 버전이어야 한다.
- 임무 버전은 해시에 든다. `Incident.kt` KDoc 의 해시 원칙 «새 칸은 전부 든다» 를 따른다. `review`·`resolution` 이 빠지는 것은 나중의 사람 단계라서다.
- 모듈을 떼지 않고 패키지로 두었다. 변경의 절반이 엔진 안쪽이라 떼면 한 변경이 둘로 갈라진다. ADR 38 과 `LogicalCapability` KDoc 의 «조합은 미션 계층의 일이고 그것은 이 모듈 안이다» 와 같다. 엔진 없이 스키마·검증기만 필요한 둘째 소비자가 생기면 다시 본다.

## 고르지 않은 것

- 세 정의를 다 데이터로 옮기는 길. 반복 한 번에 노드 여럿, 단위 id 접미사, 작업 지시 파라미터 값, 조건부 포함, 플릿 경로가 스키마에 더 있어야 한다.
- 분기·병렬. 운영 관리 화면 설계 제안 §6 이 5가지 의미(동시 전이 우선순위, 반복 횟수, 자원 획득·반납, 취소·재시작, 재시도 때 부작용 중복)를 먼저 정한 뒤 연다.
- JSON 에 Jackson·kotlinx 를 들이는 길. picasso main 의존은 계약과 계약 소비자뿐이다(P3 스펙의 결정 (차)).
- 작업 응답·로봇 계약에 임무 버전 칸을 두는 길과 `missionVersion` 을 내보내기에 싣는 길. 읽는 쪽이 없다(ADR 9, §15.202 와 같은 처리). 내보내기 쪽은 감사 내보내기 묶음(§15.322)이 버전 8 에서 실었다.
- 별도 저장소·모듈(`picasso-mission`). 위 «왜 이 모양인가» 의 마지막 항목이다.

## 대가

- 엔진이 바뀌었다. `Route` 의 셋째 값, 진행·리비전·미확정 해소·작업 응답의 경로 분기, 중단 반환 가드다. `InspectAsset` 때의 «엔진 무수정» 은 단위의 종류가 늘면 성립하지 않는다(scenarios·README·architecture 의 문장을 고쳤다).
- 조회 버전이 6 이 되어 인계 번들 네 세트를 다시 산출했고 모든 인시던트 줄의 `digest` 가 바뀌었다. 읽는 쪽이 `digest` 를 멱등성 키로 들면 같은 인시던트가 새로 들어온다. 알림이 필요하다.
- 저장과 이력은 메모리 구현뿐이다. picasso-ops 가 S3 에서 붙인다.
- 작업 지시의 설비 id 가 대기 노드 id 와 같으면 단위 id 가 겹친다. 검증기는 정의만 보므로 여전히 못 잡는다. 대신 작업 수락(`submit`)과 리비전(`revise`)이 계획의 단위 id 가 겹치면 `DUPLICATE_UNIT_ID` 로 거부한다(§15.227). 작업 지시가 와야 설비 id 를 알기 때문에 그 자리에서 막는다. 이 겹침은 정의를 활성화할 때가 아니라 작업 지시를 받을 때 드러난다. 파라미터 이름 오타도 검증기가 못 잡는다(실행 때 `PARAMETER_INVALID`).
- 설비 대기를 사람이 `CONFIRM_DONE` 하면 근거가 E0 로 남아 작업 응답의 근거 등급이 E0 로 내려간다.
- 기한 뒤 `ABORTED` 의 작업 응답은 `operatorRequired=false` 다. 정해 둔 중단이라 사람의 판단을 기다리지 않는다.
- ADR 47 은 «실으면 조회 버전이 6 으로 오르고» 라고 적었으나 6 은 이번에 `route` 의 `SIGNAL` 이 썼다. 결정자를 실을 때는 7 이 된다. ADR 47 본문은 그때의 기록이라 고치지 않는다.

## 대는 것

| 주장 | 시험 |
|---|---|
| 작업 지시 모양마다 데이터 정의가 코드 `PrepareSequencedRack` 과 같은 계획을 칸마다 낸다 | `DefinedCapabilityEquivalenceTest` · `작업 지시 모양마다 데이터 정의가 코드와 같은 계획을 칸마다 낸다` |
| 신호가 기대 값을 읽으면 대기가 E2 로 끝나고 그 뒤에 로봇 단위가 출발한다 | `EquipmentWaitTest` · `신호가 기대 값을 읽으면 대기가 E2 로 끝나고 그 뒤에 로봇 단위가 출발한다` |
| 신호를 못 읽으면 계속 기다리고 기한 시각에는 아직 서 있다가 넘으면 운영자 보류로 선다 | `EquipmentWaitTest` · `신호를 못 읽으면 계속 기다리고 기한 시각에는 아직 서 있다가 넘으면 운영자 보류로 선다` |
| 기한 뒤 `ABORTED` 면 대기 단위는 `FAILED` 로 남고 실행이 중단되며 남은 단위는 나가지 않는다 | `EquipmentWaitTest` · `기한 뒤 ABORTED 면 대기 단위는 FAILED 로 남고 실행이 중단되며 남은 단위는 안 나간다` |
| 보류를 재작업하면 기한이 다시 시작하고 확인하면 근거 E0 로 진행한다 | `EquipmentWaitTest` · `보류를 재작업하면 기한이 다시 시작하고 확인하면 근거 E0 로 진행한다` |
| 대기 중에 취소하면 대기 단위는 `ABORTED` 이고 취소 경로로 끝난다 | `EquipmentWaitTest` · `대기 중에 취소하면 대기 단위는 ABORTED 이고 취소 경로로 끝난다` |
| 대기 중의 리비전은 기다림을 그대로 잇는다 | `EquipmentWaitTest` · `대기 중의 리비전은 기다림을 그대로 잇는다` |
| 검증기는 거부를 전부 모으고 각 거부가 여섯 칸과 노드 id 를 든다 | `MissionValidatorTest` · `거부를 전부 모으고 각 거부가 여섯 칸과 노드를 든다` |
| 정의 안에서 노드 id 가 겹치면 거부한다 | `MissionValidatorTest` · `노드 id 가 겹치면 거부한다` |
| 버전 1 실행 중 버전 2 를 활성화하면 새 작업 지시만 버전 2 로 대기를 거치고 옛 실행은 버전 1 로 끝난다 | `MissionVersionScenarioTest` · `버전 1 실행 중 버전 2 를 활성화하면 새 작업 지시만 버전 2 로 대기를 거치고 옛 실행은 버전 1 로 끝난다` |
| 인시던트는 실행이 쥔 임무 버전을 싣고 그 값이 해시에 들며 내보내기에도 실린다(버전 8, §15.322) | `MissionVersionScenarioTest` · `버전 2 의 대기 기한 인시던트가 그 버전을 싣고 해시에 들며 내보내기에도 실린다` |
| 배정 사이에 활성화가 끼어도 실행은 게이트에 댄 버전을 쥔다 | `MissionVersionScenarioTest` · `배정 사이에 활성화가 끼어도 실행은 관문에 댄 버전을 쥔다` |
| WorkMaster 를 바꾸는 리비전은 거부하고 실행은 그대로다 | `MissionVersionScenarioTest` · `리비전이 WorkMaster 를 바꾸면 거부하고 실행은 그대로다` |
| 활성화를 통과하면 1 부터 오르는 버전이 활성이 되고 거부된 시도는 번호를 쓰지 않는다 | `InMemoryMissionCatalogTest` · `통과하면 1 부터 오르는 버전이 활성이 되고 거부된 시도는 번호를 쓰지 않는다` |
| 파서는 모르는 키와 빠진 칸과 형이 틀린 값을 한 번에 모은다 | `MissionDefinitionParserTest` · `모르는 키와 빠진 칸과 형이 틀린 값을 한 번에 모은다` |

이 표는 게이트가 이름으로 대조하지 않는다. 이름은 시험과 손으로 맞춘다.

> 마지막 대조: 2026-11-14 · sha256:1a0d60a29b6f · 열림: §15.209 · 정의 둘, §15.209 · 분기, §15.209 · 스키마 파일, §15.209 · 이벤트형 신호, §15.209 · 주소

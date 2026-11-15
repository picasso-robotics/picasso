# 임무 정의의 반복 그룹·조건부 포함·플릿 경로·선행(§15.325)

받는 쪽: mission-ops. 반입 시점과 방법은 받는 쪽이 결정한다. **필수 코드 변경 없음** — 아래 «지을 코드» 는 모두 선택이고, picasso 버전을 올린 뒤의 회귀 확인만 필수다.

## 바뀐 것
- 임무 정의 문서는 그대로 `"schemaVersion": 1` 이다. 아래 칸은 버전 1 에 **더한 선택 칸**이며, 그 칸을 안 쓰는 문서(저장된 문서·예제·템플릿 전부)의 파싱·검증·계획은 한 글자도 바뀌지 않는다. `MissionDefinitionParser.SCHEMA_VERSION` 도 1 그대로라 호스트의 오류 문구(`MissionJudgment.DOCUMENT_SHAPE`)는 그대로다.
- 새 칸
  - 반복 그룹 노드 `{"kind": "each", "id", "forEach", "requires"?, "whenMissing"?, "steps": [단위 노드…]}` — 설비마다 안쪽 노드를 차례로 펼친다(A 의 이동, A 의 점검, B 의 이동, B 의 점검). 안쪽 노드는 `forEach`·`once`·`pairWith`·`whenUnpaired`·`requires`·`whenMissing` 을 갖지 않고, 그룹 안에는 대기 노드와 그룹이 오지 않는다
  - 단위 노드의 `once: true`(`forEach` 대신, 작업 지시마다 한 번), `route`(`ROBOT` 기본·`FLEET`), `after`(같은 그룹의 앞 노드 id), `requires`+`whenMissing`(입력이 없으면 단위 하나를 `FAILED` 로 접음)
  - 값 출처 `ORDER_ID`·`ORDER_PARAMETER{parameter}`·`FIRST_ID{equipmentUse}`·`FIRST_PROPERTY{equipmentUse, property}`, `unitId` 에만 오는 `suffix`·`else`(한 단계, `ITEM_ID`·`ORDER_ID`)
  - 단위 id 규칙을 «`ITEM_ID` 만» 에서 «반드시 값을 냄»(`ITEM_ID`±`suffix`, `ORDER_ID`, 또는 `else` 가 그 둘) 으로 넓혔다. 기존 문서는 그대로 통과한다
  - 늘 겹치거나 영영 안 접히는 모양은 파서가 막는다 — 반복 노드(forEach 노드·그룹 안 노드)의 `unitId` 는 `ITEM_ID`·`ITEM_PROPERTY` 로 시작하고 `else` 는 `ITEM_ID` 뿐, `unitId` 에 `else` 가 있으면 `otherwise` 금지, `requires` 에 `ITEM_ID`·`ORDER_ID` 와 `otherwise` 금지, 그룹 안 노드의 `route: FLEET` 금지(`UNREADABLE`)
- 새 거부 종류 넷(`MissionRefusalKind`, 열셋 → 열일곱): `AFTER_INVALID`(선행이 같은 그룹의 앞 노드가 아님), `UNIT_ID_SHAPE_COLLISION`(같은 그룹 두 노드의 단위 id 모양이 같음), `FLEET_UNIT_INVALID`(플릿 노드가 `transport`·출발·목적지·기대 식별 정보의 모양이 아니거나 관측 선언을 듦), `WHEN_MISSING_INVALID`(접힘이 남길 노드·파라미터를 모름). 해결 담당은 넷 다 `ENGINEER` 다. 새 칸을 안 쓰는 문서에는 걸리지 않는다. 그 밖의 모양 오류는 전처럼 `UNREADABLE` 이다.
- 기존 모양 오류 문구 넷이 바뀌었다 — 모르는 출처의 허용 목록(일곱 개를 든다), 모르는 노드 종류(`unit·wait·each 중 하나여야 한다`), 단위 id 출처(`ITEM_ID 나 ORDER_ID 여야 한다 …`), 속성 칸의 허용 출처(`ITEM_PROPERTY·FIRST_PROPERTY 에만 온다`). 이 문구를 단언하는 호스트 시험이 있으면 고친다.
- 모형: `MissionStep` 의 셋째 하위형 `EachStep`, `WhenMissing`, `UnitStep` 의 기본값 있는 끝 칸 다섯(`once`·`route`·`requires`·`whenMissing`·`after`), `ValueSource` 의 기본값 있는 끝 칸 넷(`equipmentUse`·`parameter`·`suffix`·`orElse`), `UnitStep.forEach` 의 형이 `String?` 이 됐다(`once` 노드와 그룹 안쪽 노드는 널). `MissionValidator.needsSourceObservation`·`needsDestinationObservation` 은 로봇 경로 노드만 참이다. `MissionValidator.unitNodes(definition)` 이 최상위·그룹 안쪽 단위 노드를 함께 낸다.

## 지을 코드(선택)
- **편집기 템플릿.** `ui/src/missionTemplates.ts` 에 «대상마다 이동 뒤 점검» 그룹 템플릿을 더할 수 있다. picasso 시험 고정물 `MissionFixtures.INSPECT_ASSET`·`DELIVER_CONTAINER` 가 그 모양이다. 더하지 않아도 운영자는 textarea 로 새 칸을 쓸 수 있다.
- **거부 이름표.** `ui/src/labels.ts` 에 새 종류 넷의 한국어 이름표를 더한다(없으면 화면이 종류 이름 그대로 보인다). `ops-service` `MissionFindings` KDoc 의 «13종» 을 17종으로 고친다.
- **모의 실행.** `MockRunner` 의 «최상위 단위 노드의 `forEach` 가 표본에 없으면 정의 실패» 검사는 `once` 노드(`forEach` 가 널)에서 늘 참이 되고, 그룹 노드(`EachStep`)는 보지 않는다. 또 모의 실행 하네스에는 플릿 포트가 없어 `route: FLEET` 노드는 돌 곳이 없다. 새 칸을 쓴 정의를 모의 실행으로 통과시키려면 이 검사를 `MissionValidator.unitNodes` 와 그룹의 `forEach` 로 다시 쓰고, 플릿 노드의 처리를 정한다.
- **저장 문서 보정.** `StoredMissionCatalog.undeclaredAsOptional` 은 최상위 단위 노드만 고친다. 관측 선언이 생기기 전에 저장된 문서에는 그룹이 없으므로 지금은 그대로 맞다.

## 확인할 것(필수)
- picasso 버전을 올린 뒤 호스트 시험 전체를 돌린다. 특히 저장된 버전 1 문서를 기동 때 다시 파싱해 세우는 길(`StoredMissionCatalog`)과 예제 `ManufacturingInspection`(forEach `mission_target` 의 `inspect` 하나)의 활성화·모의 실행이 전과 같은지 본다. picasso 쪽은 같은 모양의 고정물(`MissionFixtures.MANUFACTURING_INSPECTION`)로 파싱·검증·계획이 그대로임을 시험으로 댄다.
- **롤백 주의.** 새 칸을 쓴 문서를 저장한 뒤 picasso 를 옛 버전으로 내리면, 옛 파서는 모르는 키·모르는 노드 종류로 그 문서를 거부한다 — 기동 때 재파싱이 실패한다. 내리기 전에 새 칸을 쓴 활성 버전을 새 칸 없는 버전으로 바꾸거나 비활성으로 둔다.

## 영향 없음(확인)
- narrator·recovery: 임무 정의 문서와 `ExecutionUnit.after` 는 내보내기·판단 기록·작업 응답에 실리지 않는다. 노트 없음.
- 계약 proto·내보내기·판단 기록·작업 응답의 형식은 그대로다.

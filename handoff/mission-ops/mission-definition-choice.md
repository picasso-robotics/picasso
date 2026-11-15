# 임무 정의의 작업 수락 시점 분기(§15.328)

받는 쪽: mission-ops. 반입 시점과 방법은 받는 쪽이 결정한다. **필수 변경은 모의 실행의 가지 표시 하나**다. 분기를 쓴 정의를 모의 실행으로 통과시키려면 아래 «모의 실행» 을 지어야 하고, 그 결과에 돈 가지와 안 돈 가지를 보여야 한다. 그 밖의 «지을 코드» 는 선택이고, picasso 버전을 올린 뒤의 회귀 확인은 필수다.

## 바뀐 것
- 임무 정의 문서는 그대로 `"schemaVersion": 1` 이다. 분기는 버전 1 에 **더한 선택 칸**이다. 그 칸을 안 쓰는 문서(저장된 문서·예제·템플릿 전부)의 파싱·검증·계획은 바뀌지 않는다(`DefinedCapabilityEquivalenceTest`·기존 검증기 시험 통과). `MissionDefinitionParser.SCHEMA_VERSION` 도 1 그대로다.
- 새 노드 `{"kind": "choice", "id", "on", "arms": [{"equals": 값 | "in": [값…], "steps": [...]}], "otherwise": {"steps": [...]}, "whenMissing": {"failureClass", "unit", "parameters"}}` — 다섯 칸 모두 필수다.
  - `on` 은 작업 지시 값만이다. 최상위 분기는 `ORDER_PARAMETER`·`FIRST_PROPERTY`(작업 지시마다 한 번), 반복 그룹 안 분기는 `ORDER_PARAMETER`·`ITEM_PROPERTY`(항목마다)다. 대체값(`otherwise`)은 오지 않는다.
  - 고르는 규칙: 선언 순서 첫 일치 가지 하나 → 어느 가지와도 안 맞으면 `otherwise`(빈 배열이면 단위 없음) → 값이 없으면(널 기준, 빈 문자열은 값) `whenMissing.unit` 의 단위 하나가 `FAILED`(파라미터는 목록의 것만, 선행 없음). 고르지 않은 가지는 단위를 내지 않는다(`FAILED` 로 세지 않는다).
  - 최상위 분기의 가지에는 단위 노드와 대기 노드, 그룹 안 분기의 가지에는 그룹 안 단위 노드만 온다. 분기 중첩·가지 안 그룹은 `UNREADABLE` 이다.
- **새 거부 종류는 없다.** 기존 종류의 조건이 새 칸에만 넓어졌다 — `DUPLICATE_NODE_ID`(분기와 가지 안까지), `AFTER_INVALID`(가지를 넘는 선행), `UNIT_ID_SHAPE_COLLISION`(가지 사이), `WHEN_MISSING_INVALID`(분기가 남길 노드가 그 분기의 가지 안에 없음, 그 노드의 단위 id 가 조건 값 없이 값을 못 냄 — 최상위 분기는 `ORDER_ID` 여야 한다). 정적 검사(스킬·신호·자원·안전·관측 선언·플릿·기한)는 **고르지 않을 가지까지 모든 가지**에 건다.
- 바뀐 거부 문구 둘 — 모르는 노드 종류(`unit·wait·each·choice 중 하나여야 한다`), 그룹 안 노드 종류(`그룹 안에는 unit·choice 만 온다`). 이 문구를 단언하는 호스트 시험이 있으면 고친다.
- 모형: `MissionStep` 의 넷째 하위형 `ChoiceStep`(`branches`·`pick`·`steps`), `ChoiceArm`, 그룹 안 노드의 상위형 `GroupStep`. **`EachStep.steps` 의 형이 `List<UnitStep>` 에서 `List<GroupStep>` 이 되었다** — 그룹 안쪽 단위 노드가 필요하면 `MissionValidator.unitNodes(definition)` 을 쓴다(분기의 모든 가지까지 든다). 대기 노드 전부는 새 `MissionValidator.waitNodes(definition)` 이다(최상위 분기의 가지 안 대기까지). `LogicalCapability.choices(order)`(기본 빈 맵)가 더해졌다.
- 엔진: **분기가 든 정의의 실행에서 리비전은 분기 자리를 더하기만 한다.** 아래는 `Submission.Rejected.reason` 문장으로만 온다(기존 거부 사유처럼).
  - 옛 리비전의 분기 자리를 하나라도 없애는 리비전 — 그룹 분기의 설비를 빼거나 그룹이 조건부 포함으로 접혀 자리가 사라지는 것. 그 자리의 단위가 끝났어도 거부한다(«리비전이 분기 자리를 없앤다: … — 분기가 든 실행의 리비전은 자리를 더하기만 한다. 새 작업 지시로 낸다»). 두 걸음 우회(빼고 다음 리비전에 같은 id 를 다른 가지로)와, 뺀 완료 단위 때문에 리빌드가 판단 기록 대조로 거부되는 것을 막는다.
  - 남은 자리의 가지를 바꾸는 리비전(«리비전이 분기의 가지를 바꾼다: …»). 값이 없어 접힌 분기에 값이 생기는 것도 든다.
  - 남은 자리가 내는 단위 id 집합을 바꾸는 리비전(«리비전이 분기 자리의 단위 id 를 바꾼다: …») — 단위 id 가 설비 속성에서 오는 노드의 그 속성을 바꾸는 것.
  - 분기가 든 반복 그룹의 쓰임에 같은 설비 id 가 둘인 작업 지시(«분기 자리가 겹쳐 받지 않는다: …») — 작업 수락과 리비전 둘 다다.
  - 받는 것은 새 자리를 더하는 리비전과 분기와 무관한 칸만 바꾸는 리비전이다. 화면은 설비를 빼거나 가지를 바꾸려는 운영자에게 «새 작업 지시로» 를 안내한다.
- 트레이스: 실행을 세울 때(작업 수락·리빌드) `BRANCH_CHOSEN` 한 줄(자리마다 고른 가지 `arms[i]`·`otherwise`·`whenMissing`, 작업 지시 리비전, 임무 버전)이 남고, 리비전이 설비를 더해 새 자리가 생기면 그 새 자리만 한 줄 더 남는다.
- 모형 API: `LogicalCapability.choices(order)`·`choiceUnits(order)`(자리마다 낸 단위 id)·`planRefusal(order)` — 기본은 빈 맵·널이다.

## 지을 코드
- **모의 실행(필수).** `MockRunner` 는 `DefinedCapability(definition).plan(order)` 로 표본 하나의 고른 가지만 돌린다. 결과에 **분기마다 돈 가지와 안 돈 가지를 표시**한다 — 안 돈 가지를 검증 성공으로 보이지 않는다. 고른 가지는 같은 표본으로 `DefinedCapability.choices(order)` 가 준다(`분기 id` 또는 `분기 id[설비 id]` → `arms[i]`·`otherwise`·`whenMissing`). 가지 목록은 `ChoiceStep.arms`·`otherwise` 다. 또 지금 코드의 세 자리가 분기를 모른다.
  - «최상위 단위 노드의 `forEach` 가 표본에 없으면 정의 실패» 검사는 최상위 `UnitStep` 만 보므로 분기 안 단위 노드를 건너뛴다.
  - 고른 가지가 다 비어 계획이 비면 «실행 단계가 비거나 …» 로 정의 실패가 된다.
  - 이상적 셀의 신호(`idealSignals`, `MockRunner.kt` 197행 근처)와 신호 충돌 판정(`conflict()`, 202행 근처)은 `definition.steps.filterIsInstance<WaitStep>()` 이라 최상위 분기 가지 안의 대기를 모른다. 그 가지를 고른 표본이 대기에서 멈춘다.
  - **고칠 방향: 정의의 대기 노드가 아니라 계획된 단위의 대기만으로.** `MissionValidator.waitNodes` 로 모든 가지의 대기를 모으면 안 된다 — 배타 가지가 같은 신호를 다른 기대 값으로 기다리면(예: 가지 A `rack_present=true`, 가지 B `rack_present=false`) 한 계획에 함께 나지 않는데도 충돌로 오판된다. 표본 작업 지시로 `DefinedCapability(definition).plan(order)` 를 부르고, 그 단위 가운데 `route == SIGNAL` 인 것의 `wait`(`WaitSpec` 의 신호·기대 값)로 이상적 신호를 짓고 충돌을 판정한다. 고칠 자리는 넷이다 — `idealSignals`, `conflict()`, 그 둘을 부르는 `run` 의 정의 실패 분기(계획을 한 번만 짓고 넘긴다), 최상위 `UnitStep` 만 보는 `forEach` 표본 검사(계획된 단위·`MissionValidator.unitNodes` 로).
- **가지별 표본(선택).** 가지마다 표본을 강제하는 활성화 조건은 넣지 않았다. 조건이 `ORDER_PARAMETER` 인 분기는 가지의 값(`equals`·`in` 의 첫 값)과 부재로 표본 작업 지시를 저절로 지을 수 있다. 속성 조건(`ITEM_PROPERTY`·`FIRST_PROPERTY`)은 표본 설비의 속성을 바꿔야 해서 자동으로 짓기 어렵다.
- **편집기.** `ui/src/missionTemplates.ts` 에 분기 템플릿(예: 대상의 `item` 으로 점검을 고르는 그룹 안 분기)을 더할 수 있다. picasso 시험 고정물 `MissionFixtures.INSPECT_BY_ITEM`(그룹 안)·`CARRY_BY_MODE`(최상위, 가지 안 대기)·`FILL_BY_LANE`(가지마다 목적지 관측이 다름)이 그 모양이다. `ui/src/labels.ts` 의 거부 이름표는 종류가 늘지 않아 그대로다. 화면에 `BRANCH_CHOSEN` 트레이스 종류의 이름표를 더할 수 있다.

## 확인할 것(필수)
- picasso 버전을 올린 뒤 호스트 시험 전체를 돌린다. 저장된 버전 1 문서를 기동 때 다시 파싱하는 길(`StoredMissionCatalog`)과 예제 `ManufacturingInspection` 이 전과 같은지 본다. `EachStep.steps` 의 형이 넓어져, 그룹 안쪽 노드를 `UnitStep` 으로 바로 쓰던 호스트 코드가 있으면 컴파일이 깨진다(지금 호스트 코드에는 `EachStep` 사용처가 없다).
- **롤백 주의.** 분기를 쓴 문서를 저장한 뒤 picasso 를 옛 버전으로 내리면, 옛 파서가 모르는 노드 종류(`choice`)로 그 문서를 거부한다 — 기동 때 재파싱이 실패한다. 내리기 전에 분기를 쓴 활성 버전을 분기 없는 버전으로 바꾸거나 비활성으로 둔다. 도는 실행은 옛 picasso 로 리빌드할 때 같은 이유로 정의를 못 읽는다.

## 영향 없음(확인)
- narrator·recovery: 분기와 고른 가지는 판단 기록·내보내기·작업 응답·계약에 칸으로 실리지 않는다(형식 버전 그대로). 인시던트 번들의 근거 윈도우에는 다른 트레이스 줄처럼 `BRANCH_CHOSEN` 줄이 들 수 있다 — 종류는 자유 문자열이라 읽는 쪽이 고칠 것이 없다. 노트 없음.

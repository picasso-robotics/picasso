# 플릿 미믹 출하 모듈(fleet-mimic) 안내

## 무엇이 바뀌었나
`picasso` 시험 소스에 있던 `AmrFleetMimic` 이 출하 모듈 `fleet-mimic`(패키지 `dev.picasso.fleetmimic`)으로 옮겨졌다(설계 변경 이력 §15.326). 프로젝트용 플릿 계약 `AmrFleetPort` 의 인프로세스 더블이며 벤더 플릿 API 의 재현이 아니다. `picasso` 의 동작과 공개 API 는 바뀌지 않았다.

## 가져다 쓰려면
`mission-ops` 의 `settings.gradle.kts` 치환 목록에 한 줄을 더한다.

```kotlin
substitute(module("dev.picasso:fleet-mimic")).using(project(":fleet-mimic"))
```

```kotlin
implementation("dev.picasso:fleet-mimic")
```

`fleet-mimic` 은 `api(project(":picasso"))` 라 `AmrFleetPort`·`TransportOrder`·`TransportStatus` 가 함께 보인다.

## 공개 API
- 생성: `AmrFleetMimic(now: () -> Instant = { Instant.now() }, stepEvery: Duration? = null)`. `stepEvery` 가 널이면 수동 진행, 양수면 시계 구동.
- 세계 채우기: `place(location, containerId, at: Instant? = null)`(시각이 널이면 그 자리의 놓인 시각을 지움), `clear(location)`, `block(location)`, `unblock(location)`.
- 진행: `tick()` — 모든 운반이 한 단계(접수 → 인수 → 이동 → 막혔으면 인계 대기 → 인계). 시계 구동이어도 부를 수 있음.
- 관측(권장): `snapshot()` — 자물쇠 한 번에 뜬 사본, 자리 → `Placed(containerId, placedAt?)`. `observe(location)` — 자리 하나의 용기와 놓인 시각을 한 번에(비었으면 널). 셀 대역의 `live` 같은 읽기는 이것으로.
- 관측(그 밖): `containerAt`, `placedAtOf`, `placedAt`·`blocked`(사본), `dispatches`(같은 참조 재전송은 세지 않음), `orderOf(handle)`(모르는 핸들이면 널). `containersAt` 은 옛 표기 호환용 창이라 `toMap()` 이 원자 사본이 아님 — 쓰지 말 것. 쓰기도 `containersAt[자리] = 용기` 대신 `place`.
- 플릿 계약: `dispatch`(같은 참조는 같은 핸들), `find`(찾기만 함), `status`, `cancel`, `executionLookup` = `CLIENT_REFERENCE`.
- 모든 호출이 한 자물쇠를 지남 — `IssuingFleetPort`·`PrefetchingFleetPort` 로 감싸 다른 스레드에서 불러도 됨. 주입한 시계는 자물쇠 밖에서 읽으므로 시계가 다른 잠금을 잡아도 교착하지 않음. 시계 구동이면 시계 안에서는 `blocked`·`dispatches`·`orderOf` 만 부를 것(나머지는 시계를 다시 읽음).
- 모르는 핸들: `status` 는 `FAILED`·사유 «모르는 운반», `cancel` 은 `false`. 던지지 않음.
- 시계 구동: 스레드를 띄우지 않음. 공개 호출마다 `now` 를 읽어 지난 걸음을 따라잡으므로, 아무도 묻지 않으면 세계도 멈춤. 놓인 시각은 그 걸음의 시각.

## 1·2단계 합의 요지
- 1단계(PR 1, 혼자서 정합): 서브모듈·복합 빌드 치환. 모의 실행 하네스가 인프로세스 플릿 미믹을 씀(용기 속성 표본, 이상적 셀의 플릿 목적지 관측). 플릿 포트 없는 호스트는 플릿 정의 활성화·새 접수를 명시 거절. 요청 파서의 `DeliverContainer` UNKNOWN_WORK_MASTER 입구 정리. UnitView 에 route·화면 이름표(모의 실행 보고·인시던트). 플릿 FAILED 에 일괄 재작업 금지. guarantees «모의 실행의 범위» 다시 쓰기.
- 2단계(PR 2): 플릿 세계를 site 프로세스에 두고 루프백 HTTP. 호스트 설정 `host.fleet.url`(빈 값 = 플릿 없음, `mimic` 값 없음), `IssuingFleetPort`·`PrefetchingFleetPort` 로 감쌈. site 셀 대역이 플릿 인계를 셀 신호로 올림.
- 2단계 플릿 전용 계획: 요청 후보가 정확히 하나일 때만(여럿·비면 전용 사유 코드로 거절, 미등록 기체 거절). 등록·점유·기존 제한 검사 유지, 스킬 판정 «해당 없음», 선언 못 읽음은 거절 사유 아님. 화면·응답에 «묶인 기체(실행 주체 아님)». 끝나지 않은 플릿 단위가 있으면 운영자 종결·빈손 확인을 막음.
- 2단계 완료 기준: 접수 → 위임 → 완료(플릿 인계가 site 셀 신호로 올라가 E2)·거절·취소·호스트만 재기동 뒤 같은 운반 추적·중복 운반 없음 e2e. Playwright 대표 한 건 이하.
- 플릿 proto 는 만들지 않음(ADR 9). picasso 의 «§15.219 · 플릿 경로» 행은 유지.

## 보장 아님
- 취소가 싣고 있던 용기를 출발지로 되돌려 놓는 것은 이 미믹의 약속이며 실 플릿의 보장이 아님. 되돌림은 출발지를 조건 없이 덮어씀(인수 뒤 그 자리에 둔 다른 용기는 사라짐).
- site 만 재기동해 플릿 장부가 비면 호스트가 든 핸들은 «모르는 운반» 이 되어 `status` 가 `FAILED`(holding=false)를 줌. 미들웨어는 그것을 운반 실패 종결로 읽지만 실제로는 운반이 어떻게 됐는지 모름 — 이 구분은 미믹이 주지 않음.
- 접수 시점의 출발지 확인, 막힌 목적지 앞의 인계 대기, 한 걸음에 한 단계 진행은 이 미믹의 모델이며 실 플릿의 순서·시간이 아님.
- 세계는 프로세스 메모리에만 있음. site 재기동 복구와, 플릿 장부 소실과 빈 장부의 구분 불가는 mission-ops 쪽 «보장 아님» 한계 행.
- 플릿 전용 계획도 picasso 접수가 기체 하나를 요구하고 그 기체가 실행·인시던트의 `robotId` 에 실림(picasso 한계 «§15.326 · 기체 묶임»). 해소 전까지 «묶인 기체(실행 주체 아님)» 표기가 필요.

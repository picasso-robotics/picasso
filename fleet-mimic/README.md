# fleet-mimic — 프로젝트용 플릿 계약의 인프로세스 더블

## 무엇이 들었나

| 부품 | 하는 일 | 지키는 것 |
| --- | --- | --- |
| `AmrFleetMimic` | `AmrFleetPort` 구현. 자리 → 용기 세계 하나를 들고 운반을 한 걸음에 한 단계씩 진행(접수 → 인수 → 이동 → 인계 대기 → 인계) | 같은 참조는 같은 운반, 모든 호출이 한 자물쇠 통과, 모르는 핸들에 던지지 않음 |

## 무엇이 아닌가
- 벤더 플릿 API 나 VDA 5050 의 재현이 아니다. 플릿 proto 도 없다(ADR 9).
- 취소 때 싣고 있던 용기를 출발지로 되돌려 놓는 것은 이 미믹의 약속이며 실 플릿의 보장이 아니다.
- 세계는 프로세스 메모리에만 있다.

## 쓰는 법
Gradle 의존성에 `fleet-mimic` 을 추가한다. `picasso` 를 `api` 로 들어 포트 타입이 함께 보인다.

```kotlin
implementation(project(":fleet-mimic"))
```

```kotlin
val fleet = AmrFleetMimic(now = clock::now)                 // 수동 진행: tick() 을 부를 때만
val driven = AmrFleetMimic(stepEvery = Duration.ofSeconds(1)) // 시계 구동: 호출마다 지난 걸음을 따라잡음
fleet.place("WH-OUT-01", "HU-0001")
fleet.block("CELL-IN-01")
```

세계 채우기는 `place`·`clear`·`block`·`unblock`, 진행은 `tick` 이다. 관측은 자물쇠 한 번에 뜬 사본 `snapshot()`(자리 → `Placed(용기, 놓인 시각)`)과 자리 하나의 짝을 한 번에 읽는 `observe(자리)` 를 쓴다. `containerAt`·`placedAtOf`·`placedAt`·`blocked`·`dispatches`·`orderOf` 도 있다. 내부 가변 맵은 내주지 않는다. `containersAt` 은 옛 표기 호환용 창이라 그 위의 `toMap()` 은 원자 사본이 아니다.

주입한 시계는 자물쇠 밖에서 읽는다. 시계 구동이면 시계 안에서는 시계를 안 읽는 `blocked`·`dispatches`·`orderOf` 만 부른다.

## 시험
`fleet-mimic` 모듈의 단위 시험 11개(`AmrFleetMimicTest`)로 세계 채우기·사본과 자리 관측·수동 걸음·시계 구동·모르는 핸들·취소 되돌림·여러 스레드·시계 재진입 무교착을 검증한다. 미들웨어와 엮은 플릿 위임 시험은 `picasso` 시험 소스(`DeliverContainerTest` 등)에 있다. 설계 변경 이력 §15.326.

> 마지막 대조: 2026-11-15 · sha256:3e210ec816aa · 열림: 없음

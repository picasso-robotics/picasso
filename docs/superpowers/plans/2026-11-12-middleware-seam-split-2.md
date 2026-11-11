# Middleware.kt 접합부 재분리 계획서 (2차)

**Goal:** 9/30 분할(`2026-09-26-middleware-seam-split.md`, PR #63~#66) 뒤 다시 4,338줄로 불어난 `Middleware.kt` 에서 구동의 하위 절차를 KDoc 째 아홉 파일로 옮긴다. 그리고 다시 불어나면 빌드가 빨개지게 한다. 거동은 바뀌지 않는다. 이 분할은 재설계(한계 대장 «§15.298»)의 0단계다. 파일 경계를 재설계가 바꿔 끼울 경계에 맞춘다.

**결과:** `Middleware.kt` 4,338 → 2,100줄. 실코드(빈 줄과 주석 줄을 뺀 것)는 1,036줄이다.

---

## 1. 무엇이 붙었나

첫 부모 기준 줄 수: 9/30 1,532 → 10-15 2,324 → 10-19 3,159 → 11-04 3,589 → 11-11 4,338. KDoc 은 1,229줄이다.

`git blame -w -M HEAD` 에서 9/30 병합(`8dddb2d`)의 조상이 아닌 커밋이 쓴 줄을 «새 줄»로 셌다. 4,338줄 가운데 2,896줄이다. 다음에 셀 때는 `-C -C` 를 쓴다. `-M` 은 파일 사이의 이동을 안 쫓으므로, 이 분할로 옮긴 줄이 전부 새 줄로 잡힌다.

| 접합부 (새 파일) | 줄 | 새 줄 | 주로 쓴 PR |
|---|---:|---:|---|
| 재개와 판단 기록 (`Resume.kt`) | 174 | 169 | #98 #86 #100 #141 |
| 리비전 개정 (`Revision.kt`) | 269 | 176 | #96 #158 #172 #128 |
| 명령 발행 (`LifecycleIssue.kt`) | 약 313 | 약 254 | #158 #160 #111 |
| 미확정 해소와 조회 (`LifecycleDoubt.kt`) | 305 | 228 | #158 #119 #163 #133 |
| 취소용 붙이기 (`LifecycleCancelAttach.kt`) | 339 | 319 | #111 #125 #135 #159 #136 |
| 취소 송신과 폴링 (`LifecycleCancel.kt`) | 약 204 | 약 200 | #159 #160 #118 |
| 시계 차 (`ClockSkew.kt`) | 51 | 51 | #121 #148 |
| 경로별 단위 구동 (`UnitDrivers.kt`) | 309 | | |
| 근거 결합과 운영자 판단 보조 (`EvidenceBinding.kt`) | 260 | | |

## 2. 왜 비대해지나 (분할로 안 고쳐지는 것)

이 분할은 파일 정리다. 클래스의 표면은 그대로다. 성장의 근원은 다음과 같고, 재설계가 다룬다.

1. **명령 하나의 생애가 명시적 상태 없이 실행의 널·불리언 조합으로 짜여 있다.** `Execution` 의 `var` 은 9/30 18개 → 32개다. 그중 취소 관련이 11개, «한 번만 알림» 표시(`noted*`·`lastNotified`)가 7개다. 9/30 이후 이 파일을 바꾼 PR 58개 가운데 fix 29개가 957줄을 순증했다. 버그 하나는 대개 취소·미확정·링크 끊김·재시작·개정 가운데 아무도 생각하지 않은 조합 하나다. 그 조합마다 필드 하나와 분기 하나가 붙는다.
2. **상태의 주인이 `Middleware` 하나다.** 실행 표와 `Execution` 이 여기 있으므로, 실행을 만지는 코드는 갈 곳이 여기뿐이다. 함수는 66개에서 155개가 되었다. 확장 함수 분할도 이 중력을 그대로 둔다.
3. **로봇과 플릿 두 경로가 같은 생애를 따로 구현한다.** 플릿 쪽 짝이 9개다(`pumpFleetUnit`·`settleDispatch`·`attachTransportForCancel`·`cancelFleet`·`pollFleetCancel` 등).
4. **비동기 송신 기록이 도메인 상태 옆에 붙어 있다.** `InFlight`·`FleetFlight`·`CancelFlight`·`FleetCancelFlight` 가 생애 상태의 일부가 아니라 실행의 필드다.

## 3. 방식: 같은 패키지의 최상위 확장

옮긴 코드는 제 상태가 없다. `Middleware` 의 실행 표·포트·시계·트레이스를 그대로 쓴다. 1차처럼 클래스로 떼면 본문 곳곳에 `mw.` 가 붙고, 떼어 낸 클래스가 핵심을 되부르는 순환이 생긴다. 그래서 `fun Middleware.name(` 확장으로 옮겼다.

- 본문은 들여쓰기만 바뀐다. 부르는 쪽(`pumpRound` 등)은 암묵 수신자로 그대로 부른다.
- 선언 줄은 `private fun name(` 에서 `internal fun Middleware.name(` 또는 `private fun Middleware.name(` 이 된다. 같은 파일 안에서만 불리는 23개는 파일 `private` 이다. 1차 규칙(«`private` → `internal` 만»)에서 벗어나는 유일한 지점이다.
- `private fun ExecutionUnit.replannedAs` 는 `Middleware` 상태를 안 쓰고 `revise` 만 부르므로 `Revision.kt` 의 파일 `private` 확장으로 옮겼다. 나머지 멤버 확장(`Execution.trail` 등)은 남긴다. 수신자가 둘이라 최상위로 못 간다.
- 옮긴 코드가 쓰는 `Middleware` 의 멤버 37개를 `private` 에서 `internal` 로 열었다. 컴파일러가 댄 것만 열었다. 가변 상태(`executions`·`views`·`droppedOptionals`)와 장부(`desk`·`gate`·`issuing`·`incidentLog`)가 들어 있다. 이 모듈의 시험이 그것을 직접 고칠 수 있게 되었으므로, 재설계에서 다시 닫는다.
- `internal` 은 JVM 에서 이름이 뭉개진 `public` 이 되고, 확장은 파일 클래스의 정적 메서드가 된다. 기존 공개 진입점의 서명은 그대로지만 클래스 파일 표면은 바뀐다.
- 남긴 것: 상태(`Execution`·필드), 라운드 순서(`pump`·`pumpRound`·`abort`), 관측(`sync`·`record`), 통보(`notify`·`noteAttention`), 공개 창구, 조율(`adopt`·`assign`·`reassign`·`approveRemedy`·`attemptApproval`·`commit`), 컴패니언 상수. 통보와 종착 전이를 `pumpRound` 와 떼면 «봉인, 종착 뒤 통보» 불변식이 두 파일에 걸린다.
- 하지 않은 것: 공개 함수의 본문을 위임 한 줄로 바꾸는 3단계. KDoc 과 본문이 다른 파일로 갈리고, 얻는 것은 줄 수뿐이다.

## 4. 문서 게이트의 제약

- `DocumentClaimsTest` 의 `자원 소유 대장이 대는 관문이 코드에 실재한다` 는 `docs/orchestration.md` §2 표의 점 기호를 주인과 멤버가 같은 파일에 선언돼 있는지로 댄다. 표가 대는 `Middleware.` 기호(`admits` `submit` `attemptApproval` `approveRemedy` `pump`)는 모두 공개라 남는다.
- 그 검사의 정규식은 `fun Middleware.x(` 를 «`Middleware` 를 선언한다»로 오인한다. 지금은 해가 없다. 하지만 표가 앞으로 옮긴 함수를 `Middleware.x` 로 대면 소속을 잘못 읽을 수 있는 알려진 근사다.
- 설계 일지와 `handoff/picasso-ops/prefetching-robot-port.md` 는 `Middleware.sync` 같은 비공개 이름을 산문으로 댄다. 이름이 그대로이므로 고치지 않는다.

## 5. 다시 안 불어나게 하는 장치

규칙은 시험 한 곳에만 둔다. 접합부 목록은 시험 소스의 `MiddlewareSeams` 한 벌이다.

1. **`SourceSizeBudgetTest`.** 실코드 줄을 센다. 앞 공백을 뗀 줄이 비었거나 `/**`·`*`·`//` 로 시작하면 안 센다. KDoc 을 세면 예산이 문서를 깎는 쪽으로 민다.

   | 대상 | 실측 | 상한 |
   |---|---:|---:|
   | `Middleware.kt` | 1,036 | 1,080 |
   | 접합부 파일 각 | 최대 241 | 500 |
   | `Middleware.kt` + 접합부 파일 합 | 2,650 | 2,780 |
   | 그 밖의 출하 파일 각 | 최대 291 | 600 |
   | `Execution` 의 `var` | 32 | 32 |
   | `pumpRound` (물리 줄) | 179 | 180 |

   합계 예산은 성장이 접합부 파일로 자리만 옮기는 것을 막는다. 가변 필드 수는 재설계의 진행을 재는 숫자다. 상한을 올리려면 상수와 설계 일지 항목을 같은 PR 에 두고 소유자 승인을 받는다. 줄었을 때 상한을 따라 내리는 것은 강제하지 않는다.
2. **`SeamPlacementTest` 의 새 시험.** 접합부 파일마다 확장 목록이 `MiddlewareSeams.homes` 와 같아야 한다. `fun Middleware.` 는 그 파일들 밖에 없어야 한다. `Middleware.kt` 에는 옮긴 절차와 같은 이름의 `fun` 이 없어야 한다(음성 바늘). 확장은 같은 서명의 멤버에 조용히 지고, 이 빌드는 그 경고를 실패로 안 본다.
3. **`CLAUDE.md` §5 의 규칙.** 상태 없는 하위 절차는 접합부 파일의 확장으로, 제 상태가 있는 새 규약은 제 클래스로 둔다. 상한은 `SourceSizeBudgetTest` 가 지킨다.

결함 주입으로 확인한 것: 상한 셋을 실측보다 낮추기(`Middleware.kt` 1,035, 그 밖의 파일 290, 가변 필드 31)와 `Middleware.kt` 에 `private fun resolveDoubt(...)` 두기. 네 시험이 XML 에서 이름을 대며 실패했다. 같은 이름의 멤버를 둔 빌드는 컴파일이 초록이었고, 1차의 배치 시험도 초록이었다.

## 6. 검증

1차 계획서 부록의 스크립트 넷에 하나를 더했다.

- **A. 표준 빌드.** `./gradlew cleanTest build -x :harness:test -x :registry:test` 뒤 XML 판정 실패 0.
- **B. 번들 동치.** `handoff/narrator/run-1..4` 의 12개 파일이 실시계와 `runId` 를 가리면 같다. 네 시나리오의 회귀 증거이지, 드문 경로(취소·미확정·재개)의 동치 증명은 아니다.
- **C. 옮긴 줄 대조(개수).** 못 찾은 줄은 선언 줄뿐이다. 새로 생긴 줄은 선언 줄과 파일 머리(package, import, 머리 주석)뿐이다.
- **C'. 순서 있는 이동 대조(새로 더함).** 빈 줄을 뺀 옛 `Middleware.kt` 를 정규화해 둔다(선언 줄의 `private`·`internal` 과 수신자 `Middleware.` 를 지움). 새 파일의 덩어리마다 그 안의 연속 구간과 한 번만 맞아야 하고, 남은 `Middleware.kt` 는 옛 파일에서 그 구간들을 뺀 것과 순서까지 같아야 한다. 개수만 세는 C 는 줄을 재배열해도 통과하므로 더했다. 본문 두 줄을 맞바꾸는 주입을 잡았다. 남는 차이는 지운 import 7줄뿐이다.
- **D. 게이트 검사 7.** `PASS  7`.
- **E. 안 쓰이게 된 import.** `Middleware.kt` 에서 7줄을 지웠다.
- `:gate:test` 전체 실패 0, CI 초록.

## 7. 지킨 것

1차 «지킬 것» 1~10 을 잇는다. 이번에 다른 점: 선언 줄에 수신자를 붙인 것(§3), 시험 수가 4 늘어난 것(2,436 → 2,440). 늘어난 시험은 `SourceSizeBudgetTest` 셋과 `SeamPlacementTest` 하나다. 커밋은 1단계 이동(일곱 파일, 연 멤버 25개), 2단계 이동(두 파일, 연 멤버 12개), 가시성 좁히기와 import 정리, 시험, 문서 순이다. 이동 커밋은 그 단계가 연 멤버를 함께 든다. 그래야 왜 열었는지가 이동과 한 자리에서 보인다.

## 8. 못 막는 것

- 줄 수와 필드 수는 대리 지표다. 상태를 맵 하나에 숨기거나 의미 없는 보조 함수로 쪼개면 예산을 피할 수 있다. 막는 것은 검토와 재설계다.
- 명령 생애 네 파일은 서로를 부른다. 그 안의 조합 폭발은 재설계 전까지 그대로다.
- 실코드 계수는 여러 줄 문자열 안의 `*`·`//` 줄을 주석으로 센다.

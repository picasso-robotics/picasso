# 공개 세터·가변 컬렉션 닫기(S3) 안내

무엇을 반입할지는 받는 쪽이 결정한다.

## 무엇이 바뀌었나

의존 정비 S3 작업이다. S2(PR #14, §15.308) 다음이며 동작 변경은 없다.

소유자 결정 ②를 C 안으로 채택했다(2026-11-13). 공개 읽기 프로퍼티를 계약으로 유지하고, 쓰기를 `internal set`과 읽기 전용 컬렉션으로 닫는다. 읽기 인터페이스(`ExecutionView`·`ExecutionUnitView`)는 두지 않는다. 자문(Codex·Fable)이 둘 다 이 안을 권했다. 인터페이스 안(B)은 mission-ops 약 20줄과 공개 `LogicalCapability.plan()`의 단위 타입 재설계가 들고 지금 얻는 것이 없다. 뷰가 필요해지면 S4에서 이 위에 얹는다.

`Execution`은 생성자의 `var order`·`var robotId`를 생성자 인자로 바꾸고 본문 `var … internal set`으로 옮겼다. 공개 컬렉션 넷(`units`·`acknowledgedFaults`·`eventTrail`·`lateEvents`)은 `List`·`Set` 타입의 읽기 전용 뷰(`Collections.unmodifiableList/Set`)로 제공한다. 쓰기는 internal 뒷받침 `mutableUnits` 등으로 하며 `units`는 받은 목록을 복사해 쥔다.

`ExecutionUnit`은 `data class`에서 일반 `class`로 변경했다. 생성자 프로퍼티에는 `internal set`을 지정할 수 없어서다. 생성자의 공개 `var` 30개를 생성자 인자와 본문 `var … internal set`으로 옮겼다. 인자의 이름·순서·기본값은 그대로라 호출부가 바뀌지 않는다. 자동 생성되던 `copy`·`componentN`·`equals`·`hashCode`·`toString`이 사라진다. `copy`와 구조 분해를 쓰던 곳은 picasso와 mission-ops 모두 0이다. `copy(state = …)`로 세터를 우회하던 공개 길도 함께 닫혔다. KDoc의 «데이터 클래스의 본문 속성이라 같음 비교와 복사에 들지 않는다» 문구 다섯 곳을 지웠다.

## mission-ops 에서 깨지는 곳

mission-ops(`fcb1a30`)를 picasso `a1162e3`과 이 변경으로 컴파일하면 프로덕션 소스는 깨지지 않는다.

시험 코드 한 곳이 깨진다. `mission-host/src/test/kotlin/dev/picasso/ops/host/ExecutionControlTest.kt:333` 한 줄이다. 오류는 `Cannot access 'hold': it is internal`이다.

## 고치는 법

`mission-host/src/test/kotlin/dev/picasso/ops/host/ExecutionControlTest.kt`의 332-334행을 아래 코드로 바꾼다.

```kotlin
        fun unit(id: String, route: Route, hold: HoldKind?) = ExecutionUnit(
            id, route, "s", emptyMap(), null, null, null,
            hold = hold?.let { HoldState.newBuilder().setKind(it).setObjectRef("C-1").build() } ?: HoldState.getDefaultInstance(),
        )
```

## 확인

picasso는 `./gradlew cleanTest build -x :harness:test -x :registry:test` 실행 결과 시험 1,831건 실패 0, 번들 동치 12/12를 확인했다.

mission-ops는 해당 줄을 고친 임시 클론으로 전체 빌드했다. site 41·mission-host 226·e2e 77 은 실패 0 이다. ops-service 498 은 첫 전체 빌드에서 시험 둘이 시험용 DB 연결 실패로 떨어졌고, 다시 돌려 실패 0 이었다(ops-service 는 picasso 미들웨어를 컴파일하지 않는다).

## 남는 것

생애·드라이버의 `Middleware` 역참조(S4)가 남는다.

층 시험은 S3 뒤 별도 단계로 진행한다.

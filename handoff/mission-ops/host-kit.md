# 담는 쪽의 부품 모듈(host-kit) 안내

## 무엇이 바뀌었나
`picasso` 시험 소스에 있던 담는 쪽 부품 셋(`ApprovalHost`, `FileEntitlements`, `BundleWriter`)이 독립 프로덕션 모듈 `host-kit` 으로 분리되었다. `picasso` 자체의 동작이나 공개 API 표면, HTTP 규약, 번들 파일 형식은 변경되지 않았다.

## 가져다 쓰려면
`mission-ops` 의 `settings.gradle.kts` 치환 목록에 모듈을 등록하고 의존성을 선언한다.

```kotlin
includeBuild("picasso") {
    dependencySubstitution {
        substitute(module("dev.picasso:host-kit")).using(project(":host-kit"))
    }
}
```

```kotlin
implementation("dev.picasso:host-kit")
```

## 콜백 계약
`ApprovalHost` 의 주 생성자는 콜백 `(ApprovalAttempt) -> ApprovalOutcome` 을 받는다. 승인 시도를 미들웨어에 반영할 때는 구동 루프와 동일한 자물쇠를 통과해야 한다. 호스트 환경의 잠금 방식(`synchronized`, `ReentrantLock`, 저장 트랜잭션 등)에 맞추어 콜백 안에서 잠금을 획득하고 `attemptApproval` 을 호출한다.

## 지킬 것
- `BundleWriter` 는 호출자가 직렬화하여 호출해야 한다.
- 파일 이동은 파일마다 원자적으로 수행되며 여러 파일의 일괄 원자적 변경이나 재시작 복구는 보장하지 않는다.
- `carry` 로 내는 작업 응답은 인스턴스 시작부터의 전체 이력이어야 하며 이빅션이나 `ack` 로 축소된 아웃박스로 덮어쓰지 않는다.

## 결정할 것
반입 여부와 범위는 받는 쪽이 스스로 결정한다. `mission-ops` 의 실행 호스트(`mission-host`)에 이 부품들을 얹을지 여부와 일정은 소유자가 판단하여 결정한다(H2).

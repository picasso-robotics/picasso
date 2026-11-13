# host-kit — 미들웨어를 담는 쪽의 부품

## 무엇이 들었나

| 부품 | 하는 일 | 지키는 것 |
| --- | --- | --- |
| `ApprovalHost` | `POST /approvals` 처리, 루프백 수신 | 콜백 안에서 미들웨어 구동 루프와 같은 자물쇠 통과 |
| `FileEntitlements` | 자동 승인 자격 선언 파일 읽기 | 유효한 선언 형식 준수 |
| `BundleWriter` | 인시던트 번들·조치 탐색 레지스터·안내·작업 응답 파일 쓰기 | 호출자 직렬화 호출 및 파일별 원자적 이동 |

## 무엇이 아닌가
- 배포 가능한 독립 프로세스가 아니다.
- 인증 기능이 없다. 루프백 전용이며 식별 정보를 검증하지 않는다.
- 포트 결선이 없다.
- `picasso` 는 여전히 파일이나 네트워크 소켓을 알지 못한다.

## 쓰는 법
Gradle 의존성에 `host-kit` 을 추가한다.

```kotlin
implementation(project(":host-kit"))
```

`ApprovalHost` 는 콜백 생성자를 통해 자물쇠 안에서 승인을 적용하도록 초기화한다.

```kotlin
val host = ApprovalHost(instanceId, port) { attempt ->
    lock.withLock {
        middleware.attemptApproval(attempt)
    }
}
```

## 담는 쪽이 지킬 것
- `BundleWriter` 는 호출자가 직렬화하여 호출한다.
- 파일 원자적 이동은 파일마다 적용되며 여러 파일의 일괄 원자적 변경이나 재시작 복구를 보장하지 않는다.
- `carry` 로 내는 작업 응답은 해당 인스턴스의 시작부터 축적된 전체 이력이어야 한다. 이빅션이나 `ack` 로 잘린 아웃박스를 통째로 덮어쓰지 않는다.
- 승인을 미들웨어에 적용할 때는 구동 루프와 동일한 자물쇠를 거쳐야 한다.

## 시험
`host-kit` 모듈의 단위 시험 14개(`BundleWriterTest` 6, `FileEntitlementsTest` 6, `ApprovalHostWireTest` 2)로 동작을 검증한다.

> 마지막 대조: 2026-11-13 · sha256:775d1679d795 · 열림: 없음

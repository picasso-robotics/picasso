# Execution 최상위 이동(S2) 안내

반입할 대상과 시점은 받는 쪽이 스스로 결정한다.

## 무엇이 바뀌었나
- `Execution` 이 `Middleware` 의 `inner class` 에서 새 파일 `Execution.kt` 의 최상위 클래스로 옮겨졌다. 동작 변경은 없다.
- `assignedAt` 은 생성자 인자로 받으며 `Middleware.create` 가 `now()` 를 넘긴다.
- `command` 는 생성자 팩터리 `commandOf: (Execution) -> CommandLifecycle` 로 짓는다. 팩터리는 저장하지 않고 생성 중 1회 호출하며 실행 멤버를 읽지 않는다.
- `noteLate(unit, update, at: () -> Instant)` 로 시각을 게으르게 받아 중복 지연 이벤트에서 시계를 읽지 않는다.
- 생성자를 `internal` 로 닫았다. 공개 API 표면을 좁히는 유일한 변경이다.

## 소스 호환
- `Middleware` 안에 `typealias Execution = dev.picasso.middleware.Execution` 을 두었다.
- mission-ops 의 `Middleware.Execution` 16곳은 수정 없이 컴파일된다.
- picasso 시험의 `Middleware.Execution` 표기도 별칭 유효성을 검증하기 위해 유지했다.
- main 소스 8개 파일의 `import dev.picasso.middleware.Middleware.Execution` 을 제거하고 KDoc 링크를 `[Execution.x]` 로 갱신했다.

## 바이너리
- JVM 클래스 이름이 `Middleware$Execution` 에서 `Execution` 으로 바뀌었다.
- `Execution` 을 인자나 반환값으로 사용하는 공개 메서드와 `Middleware.Submission.Accepted`, `Middleware.Submission.Idempotent` 의 JVM 서명이 바뀌었다.
- 소스는 호환된다. picasso 저장소에 출하(maven-publish) 설정이 없고 mission-ops 는 소스로 컴파일한다.
- 다른 바이너리 소비자의 부재는 확인되지 않았으며, 이번 병합은 기존 바이너리 재사용을 요구하지 않는다는 소유자 결정에 따른다.

## 확인
- `./gradlew cleanTest build -x :harness:test -x :registry:test` 실행 결과 시험 1,831건 통과(실패 0건), 번들 동치 12/12를 확인했다.
- mission-ops(`fcb1a30`) 대상 재빌드 결과: 842 시험(site 41, mission-host 226, ops-service 498, e2e 77) 실패 0. `:mission-host:compileKotlin` 이 다시 돌아 별칭으로 컴파일됨을 확인했다.
- 시험 단언 변경은 없으며 예산 시험 대상 파일, 정규식, 상한만 조정했다.

## 남는 것
- `Execution → CommandLifecycle → Middleware` 참조는 유지된다.
- 파일 그래프의 큰 성분을 해소하는 작업은 S4 로 남긴다.

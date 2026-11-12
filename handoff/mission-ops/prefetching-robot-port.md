# PrefetchingRobotPort 결선 안내

## 무엇이 바뀌었나
잠금 밖에서 미리 읽은 값을 받는 포트 경로가 구현되었다. `RobotPort`에 선택 메서드 `prefetched(robotId)`가 추가되었으며, 널을 반환하면 기존처럼 `snapshot`을 지금 부른다. 새 데코레이터 `PrefetchingRobotPort`는 기체마다 읽기 하나를 자기 스레드에서 실행하고 완료된 값을 한 번만 전달한다. `Middleware.sync`는 미리 읽은 값이 `Read`이면 그 읽기의 보낸 시각과 받은 시각을 기록하고, `Pending`이면 뷰를 유지하며, `Unread`이면 관측 안 함으로 둔다. 명령(`start`·`cancel`·`watch`·`capabilities`)과 취소 재전송(`pumpRound`)은 위임 포트로 전달되는 동기 호출을 유지한다.

## 결선
기본 결선 `MissionHostApplication.robotPort`를 `PrefetchingRobotPort(ClientRobotPort(...), now = 미들웨어와 같은 시계)`로 감싸고 종료 때 `close`를 호출하면 된다. `maxAge`는 생성자 인자이며 기본값은 2초이다.

## 확인할 것
실행을 다시 세우는 `Middleware.resume`의 재개 경로인 `snapshot`은 지금 묻지 않고 `maxAge` 안의 캐시된 값이 없으면 읽기를 걸고 널을 반환하므로, 첫 시도에서 `RESUME_SNAPSHOT_UNREADABLE`로 거부되어 `MissionHost`가 DEFERRED로 받아 다음 pump에서 다시 시도할 수 있다. 하네스(`Harness`)의 in-process 전송은 직접 실행기이고 에뮬레이터 서버(`MimicServer`)는 단일 잠금으로 동작하므로, 하네스 기반 `ClientRobotPort`를 쓰는 `MockRunner`를 이 데코레이터로 감싸면 전송과 서버 간 잠금 순서가 엇갈려 멎는 하네스 제약을 만난다. picasso의 통합 시험(`PrefetchedSyncTest`)은 하위 호출을 하나씩 보내는 선(`OneAtATime`)을 두었다. 아울러 답하지 않는 기체를 둔 채 HTTP 엔드포인트가 대기하지 않는지 확인하는 picasso-ops 쪽 HTTP 통합 시험의 추가 여부와 구성은 picasso-ops가 정한다.

## 남는 것
한계 레지스터 행 §15.237은 닫히지 않고 범위가 좁혀졌으며 셋이 남는다. 첫째, 명령(`start`·`cancel`·취소 재전송)은 여전히 소비자 잠금 아래 동기 호출이라 답하지 않는 기체에 기한만큼 라운드를 늘린다. 둘째, 선읽기는 담는 쪽이 이 데코레이터로 감쌀 때만 적용되며 picasso-ops 기본 결선은 아직 아니다. 셋째, 적정 기한 예산과 `maxAge`는 현장 실측 전까지 UNKNOWN이다. 이 데코레이터의 반입 여부와 시점은 picasso-ops가 정한다.

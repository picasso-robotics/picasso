# picasso 소비자 확인 — mission-ops (2026-11-12)

이 문서는 mission-ops 저장소 소유자와 작업자를 대상으로 하며, picasso 쪽이 mission-ops를 수정하지 않고 무엇을 반입할지는 mission-ops가 정한다.

## 무엇을 했나
- picasso 재설계(R0~R5, PR #5~#10, main `27a517b`)와 열린 fix PR #11(끝 커밋 `30f602c`, 취소·미확정·끊김·리비전 조합의 의심 동작 다섯 가지 수정, 동작 변경 있음)이 mission-ops를 깨는지 미리 확인했다.
- 방법: mission-ops main(`fcb1a30`)을 임시 폴더에 복제하고 서브모듈 `picasso`를 두 커밋으로 바꿔 CI와 같은 `./gradlew build`를 돌렸다. Testcontainers용 Docker 환경에서 수행했다. 공유 checkout과 mission-ops 원격은 건드리지 않았다.
- 지금 mission-ops가 고정한 picasso 커밋은 `51d8230`이다(main보다 22~23 커밋 뒤).

## 결과
| 기준 | site | mission-host | ops-service | e2e | 합계 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| picasso main `27a517b` | 41 | 226 | 498 | 77 | 842 |
| PR #11 끝 `30f602c` | 41 | 226 | 498 | 77 | 842 |

- picasso main `27a517b`: 실패 0, 건너뜀 0, BUILD SUCCESSFUL.
- PR #11 끝 `30f602c`: 같은 수, 실패 0, 건너뜀 0. `:picasso:picasso:compileKotlin`과 mission-host·e2e 시험을 다시 돌렸음을 로그로 확인했다.
- 판정은 종료 코드가 아니라 시험 결과 XML의 실패 수로 했다.
- 따라서 서브모듈을 main이나 #11 병합 뒤 커밋으로 올려도 현재 시험으로는 깨지는 곳이 없다. 올릴지와 시점은 mission-ops가 정한다.
- 한계: #11의 동작 변경((c′): 링크가 돌아왔을 때 취소 요청이 있으면 RUNNING이 아니라 CANCELING으로 복원 등)을 mission-ops 시험이 직접 겨누지는 않는다. 따라서 «실패 0»이 «영향 0»을 뜻하지는 않는다.

## mission-ops 가 쓰는 picasso API 표면
- import 기준 97개 심볼(같은 저장소 패키지 `dev.picasso.ops.*` 제외).
- 모듈별: picasso(미들웨어) 67, contracts 생성 코드(proto `dev.picasso.contracts.v1.*`) 14, `mimic`(에뮬레이터) 5, `harness` 4, `capability` 3, `client` 1, `registry` 1, `registry` testFixtures 1(`PostgresSupport`, 시험 49 파일), `uplink` 1.
- 전체 표는 아래 «부록»에 붙인다.
- 중첩 이름(이름 기반으로 센 등장 수): `Middleware.Execution` 16, `Middleware.Submission.Rejected` 14, `Middleware.FORGOTTEN` 14, `Middleware.RESUME` 5, `Middleware.Submission.Idempotent` 3, `Middleware.Submission.Accepted` 3, `Middleware.PREDECESSOR` 3, `Middleware.UNCLASSIFIED` 2.
- `Middleware` 멤버 호출(이름 기반 근사): `executions` 22, `incidents` 7, `responses` 6, `instanceId` 5, `pending` 3, `remedySearches` 2, `pump` 2, `cancel` 2, 그 밖에 `submit`·`resume`·`resolve`·`release`·`judgmentRecord`·`incident`·`forget`·`assign`·`ack` 각 1.
- `Execution`에서 읽는 멤버(근사): `executionId`, `order`, `physicalState`, `robotId`, `units`, `blockedBy`, `completedUnits`, `missionVersion`, `permitBlock`, `capability`, `robots`, `version`.
- 쓰기: mission-ops의 프로덕션 소스는 `Execution`·`ExecutionUnit`의 가변 필드에 쓰지 않는다. 시험 한 곳만 쓴다: `mission-host/src/test/kotlin/dev/picasso/ops/host/ExecutionControlTest.kt:333`이 새로 만든 `ExecutionUnit`에 `apply { this.hold = ... }`로 값을 넣는다.
- 파일 경로 의존: `picasso/profile/profiles`, `picasso/profile/schema`를 mission-host·site 빌드 입력과 시험(`HostBench.kt`, `MockRunnerTest.kt`), `SiteConfig.kt:32`가 읽는다.
- 프로덕션 소스가 `harness`(`Harness`, `RevisionTestRunner`, `RevisionSuites`, `HttpTestDesk`)와 에뮬레이터 엔진 타입(`ForceOutcome`, `AuthorityOutcome`, `TaskState`)을 쓴다.

## picasso 다음 단계에서 이 표면에 닿을 것
(picasso 쪽 계획이다. 아직 하지 않았다. 바뀔 때마다 이 폴더에 따로 알린다.)
- S1 내부 타입 이사: 공개 이름과 패키지는 그대로 둔다. `Middleware.Submission.*`은 중첩 그대로 둔다(Kotlin typealias로는 중첩 분류자 경로를 이어 줄 수 없음을 확인했다).
- S2 `Execution`을 `Middleware` 밖 최상위 클래스로 옮긴다. `Middleware` 안에 중첩 typealias `Execution`을 두어 `Middleware.Execution`이라는 이름은 컴파일된다(Kotlin 2.4.20에서 확인).
- S3 공개 세터와 가변 컬렉션을 닫는 단계: 위 시험 한 곳(`ExecutionControlTest.kt:333`)이 깨진다. 닫는 방식은 picasso 소유자가 S3 전에 정한다. 바이너리 호환은 S2 에서 먼저 깨진다 — `execution-top-level.md` 를 본다.

## 남는 것
- 이번 확인은 한 번이다. picasso main이 바뀔 때 mission-ops를 자동으로 돌리는 조기 경보는 없다. 둘지, 어디에(picasso CI인지 mission-ops CI인지) 둘지는 정하지 않았다.
- 이 폴더의 옛 이름 `handoff/picasso-ops/`는 저장소 이름이 바뀐 뒤라 `handoff/mission-ops/`로 옮겼다. 안의 두 노트(`issuing-robot-port.md`, `prefetching-robot-port.md`)는 그때의 스냅숏이라 본문은 고치지 않았다.

## 부록: import 한 picasso 심볼 97개

같은 저장소 패키지 `dev.picasso.ops.*` 를 뺀 `import dev.picasso.*` 줄을 모았다. 모듈은 picasso `27a517b` 에서 그 이름을 선언한 곳이다. 선언을 찾지 못한 14개는 proto 생성 코드다.

| picasso 모듈(소스 세트) | 심볼 | 쓰는 곳(mission-ops 모듈/소스 세트 파일 수) |
|---|---|---|
| contracts(생성 코드) | `dev.picasso.contracts.v1.CancelTaskResponse` | mission-host/test 1 |
| contracts(생성 코드) | `dev.picasso.contracts.v1.Capability` | mission-host/main 1; mission-host/test 1 |
| contracts(생성 코드) | `dev.picasso.contracts.v1.ConnectionState` | mission-host/test 1; site/main 1; site/test 1 |
| contracts(생성 코드) | `dev.picasso.contracts.v1.FailureClass` | mission-host/main 1 |
| contracts(생성 코드) | `dev.picasso.contracts.v1.Fault` | mission-host/main 2 |
| contracts(생성 코드) | `dev.picasso.contracts.v1.HoldKind` | mission-host/main 1; mission-host/test 2 |
| contracts(생성 코드) | `dev.picasso.contracts.v1.HoldState` | mission-host/test 2 |
| contracts(생성 코드) | `dev.picasso.contracts.v1.ParameterValue` | mission-host/test 2; site/test 2 |
| contracts(생성 코드) | `dev.picasso.contracts.v1.Precondition` | mission-host/test 1 |
| contracts(생성 코드) | `dev.picasso.contracts.v1.PreconditionSubject` | mission-host/test 1 |
| contracts(생성 코드) | `dev.picasso.contracts.v1.StartTaskResponse` | mission-host/test 1 |
| contracts(생성 코드) | `dev.picasso.contracts.v1.TaskHandle` | mission-host/test 1 |
| contracts(생성 코드) | `dev.picasso.contracts.v1.TaskState` | mission-host/main 1 |
| contracts(생성 코드) | `dev.picasso.contracts.v1.WatchTaskResponse` | mission-host/test 1 |
| capability(main) | `dev.picasso.capability.PreconditionViolation` | mission-host/main 1; mission-host/test 1 |
| capability(main) | `dev.picasso.capability.Remedy` | mission-host/main 1; mission-host/test 1 |
| capability(main) | `dev.picasso.capability.RemedyStep` | mission-host/main 1; mission-host/test 1 |
| client(main) | `dev.picasso.client.PicassoClient` | mission-host/main 1; mission-host/test 1; site/test 2 |
| harness(main) | `dev.picasso.harness.Harness` | mission-host/main 1 |
| harness(main) | `dev.picasso.harness.revision.HttpTestDesk` | site/main 1 |
| harness(main) | `dev.picasso.harness.revision.RevisionSuites` | site/main 1 |
| harness(main) | `dev.picasso.harness.revision.RevisionTestRunner` | site/main 1 |
| mimic(main) | `dev.picasso.mimic.cli.MimicCli` | load/load 1; mission-host/test 1; site/main 3; site/test 2 |
| mimic(main) | `dev.picasso.mimic.engine.AuthorityOutcome` | site/main 1 |
| mimic(main) | `dev.picasso.mimic.engine.ForceOutcome` | mission-host/test 10; site/main 1 |
| mimic(main) | `dev.picasso.mimic.engine.StartOutcome` | mission-host/test 2 |
| mimic(main) | `dev.picasso.mimic.engine.TaskState` | mission-host/test 10; site/main 2; site/test 2 |
| picasso(main) | `dev.picasso.middleware.ActiveMission` | mission-host/main 2 |
| picasso(main) | `dev.picasso.middleware.ApprovedStep` | mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.Approver` | mission-host/main 4; mission-host/test 7 |
| picasso(main) | `dev.picasso.middleware.ApproverKind` | mission-host/main 3; mission-host/test 7 |
| picasso(main) | `dev.picasso.middleware.CellSignals` | mission-host/main 2 |
| picasso(main) | `dev.picasso.middleware.ClientRobotPort` | mission-host/main 2; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.DestinationObservation` | mission-host/main 1; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.EquipmentRequirement` | mission-host/main 2 |
| picasso(main) | `dev.picasso.middleware.EquipmentUse` | mission-host/main 1; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.Evidence` | mission-host/main 3; mission-host/test 2 |
| picasso(main) | `dev.picasso.middleware.ExecutionUnit` | mission-host/main 1; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.FaultDetail` | mission-host/main 1 |
| picasso(main) | `dev.picasso.middleware.FloorOwnership` | mission-host/main 1 |
| picasso(main) | `dev.picasso.middleware.ForgetOutcome` | mission-host/main 1; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.IncidentBundle` | mission-host/main 2 |
| picasso(main) | `dev.picasso.middleware.IncidentResolution` | mission-host/main 1 |
| picasso(main) | `dev.picasso.middleware.InspectAsset` | mission-host/main 1 |
| picasso(main) | `dev.picasso.middleware.IssuingRobotPort` | mission-host/main 2 |
| picasso(main) | `dev.picasso.middleware.JobOrder` | mission-host/main 3; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.JobResponse` | mission-host/main 1; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.JudgmentRecord` | mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.JudgmentRecordWire` | mission-host/main 1; mission-host/test 3 |
| picasso(main) | `dev.picasso.middleware.LogicalCapability` | mission-host/main 1 |
| picasso(main) | `dev.picasso.middleware.MaterialRequirement` | mission-host/main 2 |
| picasso(main) | `dev.picasso.middleware.Middleware` | load/load 2; mission-host/main 3; mission-host/test 5 |
| picasso(main) | `dev.picasso.middleware.MissionCatalog` | mission-host/main 2 |
| picasso(main) | `dev.picasso.middleware.NamedSignal` | mission-host/main 2; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.OperatorDecision` | mission-host/main 3; mission-host/test 4 |
| picasso(main) | `dev.picasso.middleware.PermitBlock` | mission-host/main 1 |
| picasso(main) | `dev.picasso.middleware.PermitCause` | mission-host/main 1; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.PermitReading` | mission-host/main 1 |
| picasso(main) | `dev.picasso.middleware.PermitReason` | mission-host/main 2 |
| picasso(main) | `dev.picasso.middleware.PermitScope` | mission-host/main 1 |
| picasso(main) | `dev.picasso.middleware.PhysicalState` | e2e/test 1; mission-host/main 3; mission-host/test 3 |
| picasso(main) | `dev.picasso.middleware.PrefetchedRead` | mission-host/main 1; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.PrefetchingRobotPort` | mission-host/main 2 |
| picasso(main) | `dev.picasso.middleware.PrepareSequencedRack` | mission-host/main 3; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.RecordedApproval` | mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.RemedyOutcome` | mission-host/main 1; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.Replay` | mission-host/main 1; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.ResolveOutcome` | mission-host/main 1 |
| picasso(main) | `dev.picasso.middleware.RetirementPort` | mission-host/main 2 |
| picasso(main) | `dev.picasso.middleware.RobotPermit` | mission-host/main 2; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.RobotPort` | mission-host/main 3; mission-host/test 2 |
| picasso(main) | `dev.picasso.middleware.RobotSnapshot` | mission-host/main 1; mission-host/test 2 |
| picasso(main) | `dev.picasso.middleware.Route` | mission-host/main 1; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.SiteTimings` | e2e/test 1; mission-host/main 2 |
| picasso(main) | `dev.picasso.middleware.SiteTimingsSource` | mission-host/main 2 |
| picasso(main) | `dev.picasso.middleware.SlotSignal` | mission-host/main 2; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.SourceObservation` | mission-host/main 1; mission-host/test 2 |
| picasso(main) | `dev.picasso.middleware.SourceUnobserved` | mission-host/main 1; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.Unassigned` | mission-host/main 2 |
| picasso(main) | `dev.picasso.middleware.UnitState` | mission-host/main 3; mission-host/test 2 |
| picasso(main) | `dev.picasso.middleware.Verification` | mission-host/main 2 |
| picasso(main) | `dev.picasso.middleware.WorkPermit` | mission-host/main 1; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.WorkPermitPort` | mission-host/main 1 |
| picasso(main) | `dev.picasso.middleware.mission.DefinedCapability` | mission-host/main 3; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.mission.MissionDefinition` | mission-host/main 3; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.mission.MissionDefinitionParser` | mission-host/main 2; mission-host/test 3 |
| picasso(main) | `dev.picasso.middleware.mission.MissionParse` | mission-host/main 2; mission-host/test 3 |
| picasso(main) | `dev.picasso.middleware.mission.MissionRefusal` | mission-host/main 2 |
| picasso(main) | `dev.picasso.middleware.mission.MissionRefusalKind` | mission-host/main 1 |
| picasso(main) | `dev.picasso.middleware.mission.MissionValidator` | mission-host/main 2 |
| picasso(main) | `dev.picasso.middleware.mission.SignalKind` | mission-host/main 1; mission-host/test 2 |
| picasso(main) | `dev.picasso.middleware.mission.SignalSpec` | mission-host/main 3; mission-host/test 1 |
| picasso(main) | `dev.picasso.middleware.mission.UnitStep` | mission-host/main 1; mission-host/test 2 |
| picasso(main) | `dev.picasso.middleware.mission.WaitStep` | mission-host/main 1 |
| registry(main) | `dev.picasso.registry.web.RegistryApplication` | site/main 1 |
| registry(testFixtures) | `dev.picasso.registry.PostgresSupport` | e2e/test 7; mission-host/test 20; ops-service/test 20; site/test 2 |
| uplink(main) | `dev.picasso.uplink.report.RegistryLink` | site/main 1 |

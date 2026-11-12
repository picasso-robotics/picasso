# Middleware 재설계 R0 — 상태 지도와 조합 그물 (한계 대장 §15.298, 일지 §15.300)

**목표:** 재설계(`2026-11-12-middleware-lifecycle-redesign.md`)의 첫 단계. 동작은 바꾸지 않는다. 셋을 둔다.

1. **상태 지도.** `Middleware.Execution` 과 `ExecutionUnit` 의 가변 필드마다 어느 함수(파일)가 쓰고 읽는지(§2).
2. **조합 그물.** 취소 × 미확정 × 링크 끊김 × 재기동 × 개정 조합의 지금 동작을 고정하는 특성화 시험(§3, `LifecycleCombinationTest`).
3. **예산 보강.** `SourceSizeBudgetTest` 에 `Execution` 의 가변 컬렉션 수와 `ExecutionUnit` 의 가변 필드 수 상한(§4).

---

## 1. 측정값 (2026-11-12, 브랜치 `refactor/middleware-r0-state-map`)

| 대상 | 계획서가 적은 값 | 실측 | 예산 상수 |
|---|---|---|---|
| `Execution` 의 `var` | 32 | 32 | `EXECUTION_VARS = 32` (그대로) |
| `Execution` 의 가변 컬렉션(생성기로 초기화한 `val`) | 11 | **14** | `EXECUTION_COLLECTIONS = 14` (새로) |
| `Execution` 의 가변 생성자 인자 | — | 1 (`units: MutableList`) | 세지 않음 |
| `ExecutionUnit` 의 `var` | 약 30 | **36** (생성자 30, 본문 6) | `UNIT_VARS = 36` (새로) |

계획서의 11 은 타입 표기 없이 선언한 `seenLate`, `linkedMapOf` 로 만든 `decisions`, 판단 기록이 넘긴 `restoredRequests`·`restoredWindows` 쪽을 덜 센 값으로 보인다. 약 30 은 생성자의 `var` 만 센 값이다. 본문에 `robotBinding` `lastLookupAt` `streamLostAt` `keyedCancelAt` `heldInEarlierAttempt` `after` 여섯이 더 있다. 예산은 실측에 맞춘다.

## 2. 상태 지도

### 2.1 만든 방법

**컴파일러로 뽑았다.** 필드 선언을 고유한 이름(`이름__r0`)으로 바꾼 뒤 `:picasso:compileKotlin` 을 증분 없이(`-Pkotlin.incremental=false --rerun-tasks`) 돌리면, 그 필드를 쓰는 자리마다 미해결 참조 오류가 난다. 오류의 파일·줄에서 감싸는 함수를 찾고, 그 줄의 모양(대입, `+=`·`-=`, 첨자 대입, `add`·`remove`·`put`·`getOrPut`·`clear` 같은 변경 호출, 생성자·`copy` 의 이름 인자)으로 쓰기와 읽기를 가렸다. 선언은 끝에 `git checkout` 으로 되돌렸다. `Execution` 과 `ExecutionUnit` 은 따로 돌렸다. 함께 바꾸면 `execution.active?.state` 처럼 앞 필드가 풀리지 않은 자리에서 뒤 필드의 오류가 안 난다.

**grep 과 맞대 손으로 봤다.** 필드 이름을 grep 으로 찾은 줄 가운데 컴파일러가 안 댄 줄은 세 갈래뿐이었다. 선언 줄, 같은 이름을 가진 다른 클래스의 필드(`JudgmentRecord` 의 `UnitRecord`, `Incident`·`LedgerExport` 의 행), 그리고 지역 변수다. 컴파일러는 받는 쪽의 타입으로 가리므로 이름이 흔한 필드(`state` `note` `result` `source`)도 섞이지 않는다. 증분 컴파일을 켜 두면 `Middleware.kt` 만 다시 컴파일해 다른 파일의 쓰임이 빠진다. 처음 돌렸을 때 그렇게 빠졌다.

**못 보는 자리.** 시험 소스는 세지 않았다(출하 코드만). 넘겨받은 컬렉션을 받는 쪽 함수가 바꾸는 자리는 넘긴 쪽이 읽기로 잡힌다. 같은 함수가 읽고 쓰면 두 칸에 다 든다. `(속성)` 은 계산 속성의 게터다.

### 2.2 `Middleware.Execution` — 47 (`var` 32, 컬렉션 14, 가변 인자 1)

묶음은 계획서의 R 단계가 다루는 축이다. 손으로 붙였다. «취소(R1)» 아홉에 송신 묶음의 `cancelInFlight`·`fleetCancelInFlight` 를 더하면 계획서의 취소 관련 11 이다.

| 묶음 | 필드 | 종류 | 쓰는 곳 | 읽는 곳 |
|---|---|---|---|---|
| 계획 | `order` | `var` | `Middleware`: create; `Revision`: revise | `AdmissionGate`: liveClaims, liveZones; `EvidenceBinding`: beginVerify, failWithEvidenceCheck; `IncidentLog`: sealIncidents; `LifecycleDoubt`: resolveDoubt; `LifecycleIssue`: taskIdOf; `Middleware`: abort, adopt, commit, forget, judgmentRecord, notify, pumpRound, reassign, resume, submit, version (속성); `Resume`: rebuildFrom, restore; `Revision`: revise |
| 계획 | `robotId` | `var` | `Middleware`: create, reassign | `LifecycleCancel`: sendCancel; `Middleware`: Execution.trail, commit, notApplied, notify, reassign, robotOf, robots (속성); `Revision`: revise |
| 계획 | `units` | 가변 인자 | `Middleware`: create; `Revision`: revise | `AdmissionGate`: liveClaims, liveZones; `EvidenceBinding`: resolveSkipped, skippedPair, skippedReworkRefusal; `IncidentLog`: sealIncidents; `LifecycleCancel`: settleCancel; `LifecycleCancelAttach`: attachForCancel, attachTransportForCancel, cancelWaitsOnHeldDoubt; `LifecycleIssue`: settleDispatch, settleIssue; `Middleware`: abort, completedUnits (속성), create, judgmentRecord, noteAttention, notify, pumpRound, reassign, record, resolve, resume, robots (속성), settleExecution, skipAfterPredecessor; `Resume`: rebuildFrom, restore; `Revision`: revise; `UnitDrivers`: pumpSignalUnit |
| 계획 | `assignedAt` | `var` | `Middleware`: reassign | `Middleware`: reassign |
| 물리·막음 | `physicalState` | `var` | `EvidenceBinding`: checkEvidence, resolveSkipped; `LifecycleCancelAttach`: attachByKey, attachTransportForCancel, attachUnit, waitUnattached; `LifecycleDoubt`: attachFromSnapshot, lookupFound, lookupNotSent, reattachFromSnapshot, resolveDoubt; `LifecycleIssue`: settleFleetStart, settleStart, startInDoubt, startUnit; `Middleware`: abort, cancel, create, pumpRound, release, resolve, settleExecution; `Resume`: restore; `Revision`: revise, settleUpdate; `UnitDrivers`: loseStream, pumpFleetUnit, pumpRobotUnit, pumpSignalUnit | `AdmissionGate`: liveClaims, liveHold, liveZones; `EvidenceBinding`: skippedReworkRefusal; `LifecycleCancel`: pollFleetCancel, pollRobotCancel; `Middleware`: cancel, commit, forget, liveExecutionCount, noteAttention, notify, pump, pumpRound, pumping, reassign, settleExecution; `Revision`: revise |
| 물리·막음 | `linkBroken` | `var` | `Middleware`: pumpRound | `IncidentLog`: sealIncidents; `LifecycleCancelAttach`: holdUnreachedCancel; `Middleware`: pumpRound; `UnitDrivers`: pumpRobotUnit |
| 물리·막음 | `blockedBy` | `var` | `Middleware`: pumpRound, release | `IncidentLog`: sealIncidents; `Middleware`: blockingFaults, noteAttention, notify, pumpRound, release |
| 물리·막음 | `permitBlock` | `var` | `Middleware`: abort, pumpRound; `Revision`: issuable | `Middleware`: pumpRound; `Revision`: issuable |
| 물리·막음 | `acknowledgedFaults` | 컬렉션 | `Middleware`: Execution.forgetAcknowledged, release; `Resume`: restore | `Middleware`: Execution.forgetAcknowledged, blockingFaults, judgmentRecord, sync |
| 붙이기·재기동 | `active` | `var` | `LifecycleCancelAttach`: attachTransportForCancel, attachUnit; `LifecycleDoubt`: reattachFromSnapshot; `LifecycleIssue`: settleFleetStart, settleStart, startNotSent, startUnit; `Middleware`: pumpRound | `AdmissionGate`: liveHold; `LifecycleCancel`: cancelFleet, sendCancel, settleCancel; `Middleware`: notify, pumpRound, reassign |
| 붙이기·재기동 | `handle` | `var` | `LifecycleCancelAttach`: attachByKey, attachUnit; `LifecycleDoubt`: applyStartResponse, attachFromSnapshot, reattachFromSnapshot; `Middleware`: pumpRound; `Revision`: settleUpdate; `UnitDrivers`: loseStream | `LifecycleCancel`: sendCancel; `LifecycleDoubt`: resolveDoubt; `Middleware`: pumpRound; `UnitDrivers`: pumpRobotUnit |
| 붙이기·재기동 | `transport` | `var` | `LifecycleCancelAttach`: attachTransportForCancel; `LifecycleIssue`: acceptTransport; `Middleware`: pumpRound | `LifecycleCancel`: sendCancel; `LifecycleDoubt`: resolveDoubt; `UnitDrivers`: pumpFleetUnit |
| 붙이기·재기동 | `unattached` | 컬렉션 | `LifecycleCancelAttach`: attachForCancel, attachUnit, waitUnattached; `LifecycleDoubt`: reattachFromSnapshot; `LifecycleIssue`: startNotSent, startUnit; `Resume`: rebuildFrom | `LifecycleCancel`: settleCancel; `LifecycleCancelAttach`: attachForCancel; `LifecycleDoubt`: reattachFromSnapshot; `LifecycleIssue`: startUnit |
| 붙이기·재기동 | `inherited` | 컬렉션 | `Middleware`: resume | `LifecycleIssue`: startUnit |
| 붙이기·재기동 | `resumedPrior` | 컬렉션 | `Resume`: rebuildFrom, restore | `UnitDrivers`: pumpRobotUnit |
| 붙이기·재기동 | `restoredRequests` | 컬렉션 | `LifecycleIssue`: requestedAtOf, startNotSent; `Resume`: restore | `LifecycleCancelAttach`: attachTransportForCancel; `LifecycleIssue`: startUnit; `Middleware`: judgmentRecord |
| 붙이기·재기동 | `restoredWindows` | 컬렉션 | `Resume`: restore; `UnitDrivers`: pumpRobotUnit | `Middleware`: judgmentRecord |
| 송신(R3) | `inFlight` | `var` | `LifecycleIssue`: settleIssue | `LifecycleCancel`: sendCancel; `LifecycleDoubt`: resolveDoubt; `Middleware`: pumpRound; `Revision`: revise |
| 송신(R3) | `fleetInFlight` | `var` | `LifecycleIssue`: settleDispatch | `LifecycleCancel`: sendCancel; `LifecycleDoubt`: resolveDoubt; `Middleware`: pumpRound; `Revision`: revise |
| 송신(R3) | `cancelInFlight` | `var` | `LifecycleCancel`: cancelRobot, pollRobotCancel | `LifecycleCancel`: cancelRobot, markOtherFailed, pollFleetCancel, pollRobotCancel; `LifecycleDoubt`: resolveDoubt; `Middleware`: forget, pumpRound |
| 송신(R3) | `fleetCancelInFlight` | `var` | `LifecycleCancel`: cancelFleet, pollFleetCancel | `LifecycleCancel`: cancelFleet, markOtherFailed, pollFleetCancel, settleCancel; `Middleware`: forget, pumpRound |
| 송신(R3) | `deferredUpdate` | `var` | `LifecycleDoubt`: settleLookup; `LifecycleIssue`: settleStart; `Middleware`: abort, pumpRound; `Revision`: revise, settleUpdate | `Middleware`: pumpRound; `Revision`: revise |
| 취소(R1) | `cancelRequested` | `var` | `Middleware`: cancel; `Resume`: restore | `LifecycleCancelAttach`: holdUnreachedCancel; `LifecycleDoubt`: attachFromSnapshot, lookupFound, resolveDoubt; `LifecycleIssue`: settleFleetStart, settleStart; `Middleware`: judgmentRecord, pumpRound; `Revision`: revise, settleUpdate; `UnitDrivers`: pumpFleetUnit, pumpRobotUnit, pumpSignalUnit, settleLate |
| 취소(R1) | `cancelRefusal` | `var` | `LifecycleCancel`: noteRefusal; `LifecycleCancelAttach`: attachByKey; `Middleware`: cancel | `Middleware`: abort |
| 취소(R1) | `cancelUnsent` | `var` | `LifecycleCancel`: cancelNotReached, cancelReached; `LifecycleCancelAttach`: attachTransportForCancel, heldCancelAnswered, holdUnsentCancel, waitUnattached | `LifecycleCancel`: cancelReached, settleCancel; `LifecycleCancelAttach`: holdUnreachedCancel; `Middleware`: abort, pumpRound |
| 취소(R1) | `notedCancelUnsent` | `var` | `LifecycleCancel`: cancelNotReached, cancelReached; `LifecycleCancelAttach`: attachTransportForCancel, heldCancelAnswered, holdUnreachedCancel, holdUnsentCancel, waitUnattached | `LifecycleCancel`: cancelNotReached |
| 취소(R1) | `doubtCancelHold` | `var` | `LifecycleCancelAttach`: attachTransportForCancel, holdUnreachedCancel, holdUnsentCancel, waitUnattached | `LifecycleCancel`: settleCancel; `LifecycleCancelAttach`: cancelWaitsOnHeldDoubt; `Middleware`: pumpRound |
| 취소(R1) | `unreachedCancelSince` | `var` | `LifecycleCancelAttach`: holdUnreachedCancel | `LifecycleCancelAttach`: holdUnreachedCancel |
| 취소(R1) | `unattachedCancelSince` | `var` | `LifecycleCancelAttach`: attachTransportForCancel, attachUnit, waitUnattached | `LifecycleCancelAttach`: waitUnattached |
| 취소(R1) | `notedUnattached` | `var` | `LifecycleCancelAttach`: attachTransportForCancel, attachUnit, waitUnattached | `LifecycleCancelAttach`: waitUnattached |
| 취소(R1) | `lastCancel` | `var` | `Middleware`: abort | `Middleware`: lastCancel, notify |
| 한 번만 알림(R2) | `notedDelay` | `var` | `UnitDrivers`: pumpFleetUnit | `UnitDrivers`: pumpFleetUnit |
| 한 번만 알림(R2) | `notedHold` | `var` | `EvidenceBinding`: resolveSkipped; `Middleware`: pumpRound, resolve | `Middleware`: pumpRound |
| 한 번만 알림(R2) | `notedDoubt` | `var` | `LifecycleCancelAttach`: attachByKey; `LifecycleDoubt`: attachFromSnapshot, holdRevisionAhead, lookupFound, resolveDoubt; `LifecycleIssue`: startInDoubt; `Revision`: settleUpdate; `UnitDrivers`: loseStream | `LifecycleIssue`: startInDoubt; `UnitDrivers`: loseStream |
| 한 번만 알림(R2) | `notedBlock` | `var` | `Middleware`: pumpRound, release | `Middleware`: pumpRound |
| 한 번만 알림(R2) | `lastNotified` | `var` | `Middleware`: notify | `Middleware`: noteAttention |
| 한 번만 알림(R2) | `pumpFailures` | 컬렉션 | `Middleware`: Execution.notePumpFailure, pump | — |
| 한 번만 알림(R2) | `clockSkewNoted` | 컬렉션 | `Middleware`: Execution.noteClockSkew | — |
| 한 번만 알림(R2) | `signalSeen` | 컬렉션 | `UnitDrivers`: pumpSignalUnit | `UnitDrivers`: pumpSignalUnit |
| 한 번만 알림(R2) | `seenLate` | 컬렉션 | `Middleware`: noteLate | — |
| 기록·통보 | `approvedBy` | `var` | `Middleware`: create; `Resume`: restore; `Revision`: revise | `IncidentLog`: sealIncidents |
| 기록·통보 | `approval` | `var` | `Middleware`: commit; `Resume`: restore | `Middleware`: commit, judgmentRecord; `Revision`: revise |
| 기록·통보 | `decisions` | 컬렉션 | `EvidenceBinding`: resolveSkipped; `Middleware`: resolve; `Resume`: restore | `Middleware`: judgmentRecord |
| 기록·통보 | `eventTrail` | 컬렉션 | `Middleware`: Execution.trail, record | `IncidentLog`: sealIncidents |
| 기록·통보 | `lateEvents` | 컬렉션 | `Middleware`: noteLate | `IncidentLog`: sealIncidents |
| 기록·통보 | `pendingIncidents` | 컬렉션 | `IncidentLog`: sealIncidents; `Middleware`: Execution.markIncident | `IncidentLog`: sealIncidents |
| 기록·통보 | `upstreamAck` | `var` | `Middleware`: ack, notify | `Middleware`: settleExecution |

파일마다 만지는 `Execution` 필드 수:

| 파일 | 쓰는 필드 수 | 읽는 필드 수 |
|---|---|---|
| `Middleware.kt` | 30 | 29 |
| `LifecycleCancelAttach.kt` | 13 | 10 |
| `LifecycleCancel.kt` | 5 | 14 |
| `LifecycleIssue.kt` | 9 | 7 |
| `Revision.kt` | 8 | 10 |
| `LifecycleDoubt.kt` | 6 | 8 |
| `Resume.kt` | 10 | 2 |
| `UnitDrivers.kt` | 6 | 9 |
| `IncidentLog.kt` | 1 | 8 |
| `EvidenceBinding.kt` | 3 | 3 |
| `AdmissionGate.kt` | 0 | 4 |

### 2.3 `ExecutionUnit` — `var` 36

| 묶음 | 필드 | 종류 | 쓰는 곳 | 읽는 곳 |
|---|---|---|---|---|
| 계획 | `parameters` | `var` | `DefinedCapability`: units, waitUnit; `LogicalCapability`: plan; `RemedyDesk`: remedyUnit; `Revision`: revise | `IncidentLog`: sealIncidents; `LifecycleDoubt`: lookupDownstream, resolveDoubt; `LifecycleIssue`: startUnit; `Middleware`: attemptApproval; `RemedyDesk`: settle; `Revision`: revise, sendRevisionUpdate |
| 계획 | `expectedIdentity` | `var` | `DefinedCapability`: units, waitUnit; `LogicalCapability`: plan; `RemedyDesk`: remedyUnit; `Revision`: revise | `AdmissionGate`: destinationViolation, inconsistent, liveClaims, occupancyViolation; `EvidenceBinding`: checkEvidence, failWithEvidenceCheck; `IncidentLog`: sealIncidents; `LifecycleDoubt`: resolveDoubt; `LifecycleIssue`: transportOrderOf; `Revision`: revise |
| 계획 | `source` | `var` | `DefinedCapability`: units, waitUnit; `LogicalCapability`: plan; `RemedyDesk`: remedyUnit; `Revision`: revise | `AdmissionGate`: occupancyViolation; `IncidentLog`: sealIncidents; `LifecycleIssue`: transportOrderOf; `Revision`: revise; `UnitDrivers`: pumpFleetUnit |
| 계획 | `destination` | `var` | `DefinedCapability`: units, waitUnit; `LogicalCapability`: plan; `RemedyDesk`: remedyUnit; `Revision`: revise | `AdmissionGate`: destinationViolation, liveClaims, occupancyViolation, unownedFloor, workspaceViolation, zoneOf; `EvidenceBinding`: checkEvidence, failWithEvidenceCheck; `IncidentLog`: sealIncidents; `LifecycleDoubt`: resolveDoubt; `LifecycleIssue`: transportOrderOf; `Middleware`: Execution.observeCell; `Revision`: revise |
| 계획 | `revision` | `var` | `Middleware`: create; `Revision`: revise | `LifecycleCancel`: settleCancel; `LifecycleCancelAttach`: attachByKey, attachForCancel, cancelByKey, resendHeldCancel; `LifecycleDoubt`: attachFromSnapshot, holdRevisionAhead, lookupDownstream, reattachFromSnapshot, resolveDoubt; `LifecycleIssue`: startUnit; `Middleware`: noteLate, pumpRound; `Revision`: revise, sendRevisionUpdate; `UnitDrivers`: pumpRobotUnit, settleLate |
| 계획 | `attempt` | `var` | `EvidenceBinding`: reworkAsNewAttempt; `Resume`: rebuildFrom, restore; `Revision`: ExecutionUnit.replannedAs | `EvidenceBinding`: resolveSkipped; `LifecycleCancel`: settleCancel; `LifecycleCancelAttach`: attachForCancel, attachTransportForCancel; `LifecycleDoubt`: reattachFromSnapshot; `LifecycleIssue`: requestedAtOf, startUnit, taskIdOf; `Middleware`: judgmentRecord, resolve, resume; `Resume`: restore; `Revision`: ExecutionUnit.replannedAs; `UnitDrivers`: pumpRobotUnit |
| 계획 | `after` | `var` | `LogicalCapability`: plan | `EvidenceBinding`: skippedPair; `Middleware`: skipAfterPredecessor |
| 계획 | `robotBinding` | `var` | — | `AdmissionGate`: chainRefusal, workspaceViolation; `Middleware`: resume, robotOf |
| 생애 | `state` | `var` | `DefinedCapability`: units; `EvidenceBinding`: beginVerify, checkEvidence, failWithEvidenceCheck, reworkAsNewAttempt; `LifecycleCancelAttach`: attachByKey, attachTransportForCancel, attachUnit, holdUnreachedCancel, holdUnrequestedCancel, waitUnattached; `LifecycleDoubt`: applyStartResponse, attachFromSnapshot, holdRevisionAhead, lookupFound, reattachFromSnapshot, resolveDoubt; `LifecycleIssue`: acceptTransport, settleFleetStart, settleStart, startInDoubt, startUnit; `LogicalCapability`: plan; `Middleware`: resolve, skipAfterPredecessor; `Resume`: rebuildFrom, restore; `Revision`: settleUpdate; `UnitDrivers`: loseStream, pumpFleetUnit, pumpRobotUnit, pumpSignalUnit, settleLate | `AdmissionGate`: liveClaims, liveZones; `EvidenceBinding`: skippedPair, skippedReworkRefusal; `IncidentLog`: sealIncidents; `LifecycleCancel`: settleCancel; `LifecycleCancelAttach`: attachForCancel, attachTransportForCancel, cancelWaitsOnHeldDoubt; `LifecycleDoubt`: lookupFound; `Middleware`: abort, completedUnits (속성), judgmentRecord, noteAttention, notify, pumpRound, reassign, resolve, settleExecution, skipAfterPredecessor; `Revision`: revise, settleUpdate; `UnitDrivers`: pumpFleetUnit, pumpRobotUnit, pumpSignalUnit, settleLate |
| 생애 | `taskId` | `var` | `EvidenceBinding`: reworkAsNewAttempt; `LifecycleCancelAttach`: attachTransportForCancel, attachUnit, waitUnattached; `LifecycleDoubt`: reattachFromSnapshot; `LifecycleIssue`: startNotSent, startUnit; `Resume`: restore | `EvidenceBinding`: skippedPair; `LifecycleCancel`: cancelFleet, settleCancel; `LifecycleCancelAttach`: attachByKey, cancelByKey, heldCancelAnswered, resendHeldCancel; `LifecycleDoubt`: attachFromSnapshot, holdRevisionAhead, lookupDownstream, resolveDoubt; `LifecycleIssue`: startUnit, transportOrderOf; `Middleware`: judgmentRecord, pumpRound, record; `Revision`: revise, sendRevisionUpdate; `UnitDrivers`: loseStream, pumpSignalUnit |
| 생애 | `startedRevision` | `var` | `EvidenceBinding`: reworkAsNewAttempt; `LifecycleIssue`: startUnit | `LifecycleDoubt`: attachFromSnapshot |
| 생애 | `requestedAt` | `var` | `EvidenceBinding`: reworkAsNewAttempt; `LifecycleCancelAttach`: attachTransportForCancel, attachUnit, waitUnattached; `LifecycleDoubt`: reattachFromSnapshot; `LifecycleIssue`: settleFleetStart, settleStart, startNotSent, startUnit; `Resume`: restore; `Revision`: settleUpdate | `LifecycleDoubt`: resolveDoubt; `LifecycleIssue`: startUnit; `Middleware`: judgmentRecord; `UnitDrivers`: pumpSignalUnit |
| 생애 | `lookups` | `var` | `LifecycleCancelAttach`: attachUnit; `LifecycleDoubt`: lookupNotSent, reattachFromSnapshot, resolveDoubt; `LifecycleIssue`: startUnit; `Revision`: settleUpdate | `LifecycleDoubt`: lookupFound, resolveDoubt |
| 생애 | `lastLookupAt` | `var` | `LifecycleCancelAttach`: attachUnit; `LifecycleDoubt`: reattachFromSnapshot, resolveDoubt; `LifecycleIssue`: startUnit; `Revision`: settleUpdate | `LifecycleDoubt`: resolveDoubt |
| 생애 | `streamLostAt` | `var` | `LifecycleCancelAttach`: attachByKey; `LifecycleDoubt`: attachFromSnapshot, reattachFromSnapshot; `LifecycleIssue`: startUnit; `UnitDrivers`: loseStream | `LifecycleDoubt`: resolveDoubt |
| 생애 | `keyedCancelAt` | `var` | `LifecycleCancelAttach`: attachUnit, cancelByKey, resendHeldCancel; `LifecycleDoubt`: reattachFromSnapshot; `LifecycleIssue`: startUnit | `LifecycleCancelAttach`: cancelByKey, resendHeldCancel |
| 근거 | `reached` | `var` | `EvidenceBinding`: beginVerify, checkEvidence, reworkAsNewAttempt; `Middleware`: resolve; `Resume`: restore; `UnitDrivers`: pumpSignalUnit | `IncidentLog`: sealIncidents; `Middleware`: judgmentRecord, notify |
| 근거 | `verification` | `var` | `EvidenceBinding`: beginVerify, checkEvidence, failWithEvidenceCheck, reworkAsNewAttempt; `LifecycleDoubt`: resolveDoubt; `Resume`: restore; `UnitDrivers`: pumpSignalUnit | `IncidentLog`: sealIncidents; `Middleware`: judgmentRecord, notify, resolve |
| 근거 | `downstreamDoneAt` | `var` | `EvidenceBinding`: beginVerify, failWithEvidenceCheck, reworkAsNewAttempt | `EvidenceBinding`: checkEvidence; `Middleware`: judgmentRecord |
| 근거 | `evidenceDeadline` | `var` | `EvidenceBinding`: beginVerify, reworkAsNewAttempt | `EvidenceBinding`: checkEvidence; `Middleware`: judgmentRecord |
| 근거 | `evidenceAt` | `var` | `EvidenceBinding`: checkEvidence, failWithEvidenceCheck, reworkAsNewAttempt; `LifecycleDoubt`: resolveDoubt; `Resume`: restore; `UnitDrivers`: pumpSignalUnit | `Middleware`: judgmentRecord |
| 근거 | `rechecks` | `var` | `EvidenceBinding`: checkEvidence, reworkAsNewAttempt | `EvidenceBinding`: checkEvidence |
| 근거 | `result` | `var` | `EvidenceBinding`: reworkAsNewAttempt; `Resume`: restore; `UnitDrivers`: pumpRobotUnit | `Middleware`: judgmentRecord, notify |
| 실패 | `failureClass` | `var` | `DefinedCapability`: units; `EvidenceBinding`: checkEvidence, failWithEvidenceCheck, reworkAsNewAttempt; `LifecycleCancelAttach`: holdUnrequestedCancel; `LifecycleDoubt`: applyStartResponse; `LifecycleIssue`: acceptTransport; `LogicalCapability`: plan; `Middleware`: skipAfterPredecessor; `Resume`: restore; `Revision`: settleUpdate; `UnitDrivers`: pumpFleetUnit, pumpSignalUnit | `EvidenceBinding`: skippedPair; `IncidentLog`: sealIncidents; `Middleware`: judgmentRecord, notify |
| 실패 | `fault` | `var` | `EvidenceBinding`: failWithEvidenceCheck, reworkAsNewAttempt; `LifecycleCancelAttach`: holdUnrequestedCancel | `IncidentLog`: sealIncidents |
| 실패 | `note` | `var` | `EvidenceBinding`: failWithEvidenceCheck, reworkAsNewAttempt; `LifecycleDoubt`: applyStartResponse; `Middleware`: ExecutionUnit.annotate, resolve, skipAfterPredecessor; `Resume`: restore; `UnitDrivers`: pumpFleetUnit | `Middleware`: ExecutionUnit.annotate, ExecutionUnit.annotateOnce, blockingFaults, judgmentRecord, notify, resolve, skipAfterPredecessor |
| 실패 | `preconditionSubjects` | `var` | `EvidenceBinding`: reworkAsNewAttempt; `LifecycleDoubt`: applyStartResponse | `IncidentLog`: sealIncidents; `LifecycleDoubt`: applyStartResponse |
| 파지 | `hold` | `var` | `Middleware`: ExecutionUnit.observeHold | `AdmissionGate`: liveHold; `Canonical`: residualHoldOf; `EvidenceBinding`: judgeEffectMismatch; `IncidentLog`: sealIncidents; `Middleware`: abort, pumpRound, reassign; `UnitDrivers`: pumpFleetUnit |
| 파지 | `holdMismatch` | `var` | `EvidenceBinding`: judgeEffectMismatch, reworkAsNewAttempt | `EvidenceBinding`: judgeEffectMismatch; `IncidentLog`: sealIncidents |
| 파지 | `everHeld` | `var` | `EvidenceBinding`: reworkAsNewAttempt; `Middleware`: ExecutionUnit.observeHold; `Resume`: restore | `EvidenceBinding`: judgeEffectMismatch, reworkAsNewAttempt; `Middleware`: judgmentRecord, reassign |
| 파지 | `holdExpected` | `var` | `EvidenceBinding`: judgeEffectMismatch, reworkAsNewAttempt | `IncidentLog`: sealIncidents |
| 파지 | `heldInEarlierAttempt` | `var` | `EvidenceBinding`: reworkAsNewAttempt; `Resume`: restore; `Revision`: ExecutionUnit.replannedAs | `EvidenceBinding`: reworkAsNewAttempt; `Middleware`: judgmentRecord, reassign; `Revision`: ExecutionUnit.replannedAs |
| 진행 | `progress` | `var` | `EvidenceBinding`: reworkAsNewAttempt; `UnitDrivers`: noteProgress | `UnitDrivers`: noteProgress |
| 진행 | `progressAt` | `var` | `EvidenceBinding`: reworkAsNewAttempt; `UnitDrivers`: noteProgress | `UnitDrivers`: noteProgress |
| 진행 | `progressObservable` | `var` | `EvidenceBinding`: reworkAsNewAttempt; `UnitDrivers`: noteProgress | `IncidentLog`: sealIncidents; `UnitDrivers`: noteProgress |
| 진행 | `progressStalled` | `var` | `EvidenceBinding`: reworkAsNewAttempt; `UnitDrivers`: noteProgress | `IncidentLog`: sealIncidents; `UnitDrivers`: noteProgress |

파일마다 만지는 `ExecutionUnit` 필드 수:

| 파일 | 쓰는 필드 수 | 읽는 필드 수 |
|---|---|---|
| `EvidenceBinding.kt` | 24 | 14 |
| `Middleware.kt` | 7 | 20 |
| `LifecycleDoubt.kt` | 12 | 13 |
| `UnitDrivers.kt` | 12 | 11 |
| `IncidentLog.kt` | 0 | 15 |
| `LifecycleIssue.kt` | 9 | 8 |
| `Revision.kt` | 12 | 9 |
| `Resume.kt` | 12 | 1 |
| `LifecycleCancelAttach.kt` | 9 | 5 |
| `LogicalCapability.kt` | 7 | 0 |
| `AdmissionGate.kt` | 0 | 6 |
| `DefinedCapability.kt` | 6 | 0 |
| `LifecycleCancel.kt` | 0 | 4 |
| `RemedyDesk.kt` | 4 | 1 |
| `Canonical.kt` | 0 | 1 |

### 2.4 지도에서 읽히는 것

- `Middleware.kt` 가 `Execution` 필드 47 가운데 30 을 쓴다. 생애 쪽 접합부 넷(`Lifecycle*`)이 쓰는 필드는 19 이다. 상태의 주인이 하나라는 근원이 숫자로 보인다.
- 취소 묶음 아홉 가운데 여섯(`cancelUnsent` `notedCancelUnsent` `doubtCancelHold` `unreachedCancelSince` `unattachedCancelSince` `notedUnattached`)은 `LifecycleCancel.kt`·`LifecycleCancelAttach.kt` 밖에서 쓰지 않는다. 밖의 읽기는 셋뿐이다(`Middleware` 의 `abort`·`pumpRound` 가 `cancelUnsent` 를, `pumpRound` 가 `doubtCancelHold` 를). R1 이 이 둘을 한 생애 타입 뒤로 옮길 수 있다는 근거다. `cancelRequested` 는 `Middleware.cancel` 과 `Resume.restore` 만 쓰고 열네 함수가 읽는다.
- `ExecutionUnit` 은 `EvidenceBinding.kt` 가 가장 많이 쓴다(24). 24 전부를 `reworkAsNewAttempt` 가 쓴다. 새 시도가 단위의 필드를 하나씩 비우는 모양이라, 단위 필드가 늘 때마다 이 함수를 같이 고쳐야 한다.
- `robotBinding` 은 출하 코드에서 쓰는 곳이 없다. 시험만 쓴다(KDoc 이 적은 대로).

## 3. 조합 그물

축은 다섯이다. **취소**(`cancel` 이 걸림), **미확정**(단위가 `IN_DOUBT` 이거나 붙기 전 대기), **링크 끊김**(`linkBroken` 이거나 스냅숏을 못 읽음), **재기동**(`resume` 으로 다시 지은 실행), **개정**(같은 실행에 높은 버전). 링크가 끊긴 채 개정 갱신을 보내면 그 갱신은 미확정이 된다(포트가 `CommandNotSent` 가 아닌 예외를 던질 때). 그래서 «링크 × 개정» 은 홀로 서지 않고 «미확정» 을 끼고 온다.

### 3.1 덮는 시험

| 조합 | 덮는 시험 | 비고 |
|---|---|---|
| 취소 | `OffLockCancelTest` 전부 | |
| 취소 × 미확정 | `InDoubtTest` 의 «미확정 중의 취소는 핸들이 생기는 순간…», «미확정 중에 건 취소는 조회가 안 돼도…», «스냅숏도 조회도 안 되면…», 키 취소 다섯. `OffLockCancelTest` «키로 만든 핸들의 잠금 밖 취소…», «유예가 지난 미확정 단위도…» | |
| 취소 × 링크 끊김 | `InDoubtTest` «끊긴 기체에 건 취소가 유예 안에 닿지 않으면…», «끊긴 기체의 취소가 유예 안에 닿으면…» | 끊김은 실행을 미확정에 두고 단위는 `RUNNING` 그대로 |
| 취소 × 재기동 | `ResumeCancelTest` 전부, `ResumeFleetCancelTest` 전부, `PumpSafetyTest` «미들웨어가 취소를 건 뒤 다시 뜨면…» | |
| 취소 × 개정 | `CancelUnderRevisionTest` 첫째·셋째·넷째, `OffLockStartTest` «개정 갱신을 보내는 중에 걸린 취소는…» | |
| 취소 × 미확정 × 개정 | `CancelUnderRevisionTest` «핸들 없이 취소가 걸린 미확정 단위에 개정이 오면…» | |
| 취소 × 미확정 × 재기동 | `ResumeCancelTest` «스냅숏을 못 읽은 채 미확정 유예가 지나면…» | 붙기 전 대기가 미확정 유예를 쓴다 |
| 취소 × 링크 끊김 × 재기동 | `ResumeCancelTest` «스냅숏을 못 읽으면 정착하지 않고…», «스냅숏을 못 읽은 채 미확정 유예가 지나면…» | 스냅숏을 못 읽는 쪽 |
| 취소 × 재기동 × 개정 | **새** `LifecycleCombinationTest` «다시 지은 실행에 붙기 전 개정이 오고 취소가 걸리면…», «…붙기 전 취소를 건 뒤 개정이 와도…» | 표시 (d)(e) |
| 취소 × 미확정 × 재기동 × 개정 | **새** «다시 지은 실행에서 개정 갱신의 답을 잃은 채 건 취소는…» | |
| 취소 × 미확정 × 링크 끊김 × 개정 | **새** «끊긴 채 개정 갱신을 잃은 단위에 건 취소는…» | 표시 (a)(b)(c) |
| 다섯 축 전부 | **새** «다시 지은 실행이 끊긴 채 개정 갱신을 잃고 취소가 걸리면…» | 표시 (a)(b)(c) |

취소가 없는 조합은 이 단계의 그물 밖이다. 이미 덮는 시험만 적는다. 미확정 × 개정은 `PumpSafetyTest` 의 개정 갱신 셋과 `CommandNotSentTest`, 링크 끊김 × 미확정은 `EventStreamTest`, 재기동 × 개정은 `ResumeTest` «이미 선 실행이 있으면 리비전 경로로…», 재기동 × 링크 끊김은 `ResumeTest` «스냅숏을 못 읽으면 거부하고…» 가 덮는다.

### 3.2 R0 표시 — 틀려 보이는 지금 동작

시험은 아래 동작을 그대로 단언한다. 고치는 것은 별도 fix 이고, 그 fix 가 `LifecycleCombinationTest` 의 해당 단언을 같은 PR 에서 바꾼다. 시험 소스에 `R0 표시(x)` 주석으로 자리를 남겼다.

| 표시 | 지금 동작 | 왜 틀려 보이나 | 시험 |
|---|---|---|---|
| (a) | 끊긴 채 개정 갱신을 잃은(`IN_DOUBT`) 단위에 취소가 걸리면, 미확정 유예 뒤 **단위는 운영자 보류인데 실행은 `ABORTED` 로 닫힌다**. 인시던트는 열린다 | 끊긴 채 닿지 않은 취소의 보류(§15.243)는 실행도 보류에 두고 운영자 판단을 기다린다. 이 길의 보류는 미확정 해소(`resolveDoubt`)의 유예 만료에서 오고, 그 함수는 `doubtCancelHold` 를 쓰지 않는다(§2.2 표). 그래서 `cancelWaitsOnHeldDoubt` 가 닫힘을 막지 않는다 | 끊김 시험, 다섯 축 시험 |
| (b) | 링크가 돌아온 뒤 키 취소가 기체에 닿아 `INVALID_TRANSITION`(태스크가 이미 `SUCCEEDED`)으로 답받았는데, 취소 응답은 `CANCEL_NOT_SENT` 를 싣는다 | 닿았고 거절됐다. «닿지 못했다» 가 아니다 | 같음 |
| (c) | 링크가 돌아와도 `linkBroken` 이 참으로 남는다 | 걷는 자리(`pumpRound`)가 `RUNNING` 인 로봇 단위만 본다. 단위가 `IN_DOUBT` 로 가면 걷힐 기회가 없다. 인시던트 번들이 이 값을 싣는다 | 같음 |
| (d) | 다시 지은 실행에 붙기 전 개정이 오면 붙기 전 단위(`PENDING`)의 리비전이 오른다. 기체는 리비전 1 을 하는데 단위는 2 이고, 취소 핸들도 2 다 | 붙기 전 단위는 기체에서 돌고 있다. 미믹이 높은 리비전의 취소를 받아 주어 끝이 맞을 뿐이다 | 붙기 전 개정 시험 둘 |
| (e) | 취소를 먼저 건 뒤 개정이 와도 (d) 와 같다 | §15.256 의 «취소가 걸렸으면 그대로 둔다» 는 `RUNNING`·`IN_DOUBT` 만 본다. 붙기 전 단위는 `PENDING` 이라 새 계획으로 바뀐다 | «…붙기 전 취소를 건 뒤 개정이 와도…» |

### 3.3 숨은 조합

- **물리 상태 하나가 두 축을 진다.** 단위가 `RUNNING` 인 끊김 실행에 취소를 걸면 실행은 `CANCELING` 이 된다(`InDoubtTest`). 단위가 `IN_DOUBT` 이면 `IN_DOUBT` 그대로다(새 끊김 시험). 같은 «취소 요청됨 + 결과 모름» 이 단위 상태에 따라 다른 물리 상태로 읽힌다. 계획서 원칙 3(«동시에 성립하는 축을 가른다»)이 R1 에서 다룰 자리다.
- **취소는 건 순간의 핸들로 나간다.** 개정 갱신의 답을 잃은 단위에 취소를 걸면 붙어 있던 핸들(리비전 1)로 바로 나가 `OUTDATED_REVISION` 으로 거절되고, 다음 라운드에 스냅숏에 붙은 뒤 리비전 2 로 다시 나간다(재기동 × 미확정 × 개정 시험). 끝은 맞다. 틀린 동작으로 보지 않고 적어만 둔다.

## 4. 예산 보강

`SourceSizeBudgetTest` 에 둘을 더했다.

- `실행의 가변 필드와 라운드 함수가 구조 예산 안에 있다` — 같은 블록(`inner class Execution`)에서 여덟 칸 들여 쓴 `val` 가운데 `mutableMapOf`·`mutableSetOf`·`linkedSetOf`·`mutableListOf`·`linkedMapOf` 로 초기화한 것을 센다. 게터(`get() =`)는 계산 값이라 뺀다. 처음 판은 `val robots: Set<String> get() = linkedSetOf(…)` 를 세어 15 가 나왔다. 상한 `EXECUTION_COLLECTIONS = 14`.
- `실행 단위의 가변 필드가 구조 예산 안에 있다` — `Model.kt` 의 `data class ExecutionUnit(` 부터 첫 `}` 줄까지, 네 칸 들여 쓴 `var` 를 센다. 상한 `UNIT_VARS = 36`.

**결함 주입.** 두 상한을 실측보다 1 씩 낮춰 돌렸다. XML 의 실패 이름이 `실행의 가변 필드와 라운드 함수가 구조 예산 안에 있다()`(«실행의 가변 컬렉션 14 > 13: [pumpFailures, …]»)와 `실행 단위의 가변 필드가 구조 예산 안에 있다()`(«실행 단위의 가변 필드 36 > 35: [parameters, …]»)였다. 같은 빌드에 `Revision.kt` 의 붙기 전 단위 갈래에 «취소가 걸렸으면 그대로 둔다» 를 넣었더니(표시 (e) 를 고친 모양) `LifecycleCombinationTest` 의 «…붙기 전 취소를 건 뒤 개정이 와도…» 가 `expected: <2> but was: <1>` 로 빨개졌다. 셋 다 되돌렸다.

## 5. 다음

- R1(취소 축)은 §2.2 의 «취소(R1)» 묶음과 송신 묶음의 취소 둘을 옮긴다. 표시 (a)(b)(e) 는 취소 축의 전이 표에 칸으로 드러날 것이다. R1 안에서 고치지 않는다(계획서 §5).
- 표시 (c) 는 취소와 무관한 끊김 축의 일이다. 별도 fix 로 둔다.
- 재설계 PR 은 `LifecycleCombinationTest` 를 바꾸지 않고 초록으로 지나야 한다. 바꿔야 하면 그 PR 은 동작을 바꾼 것이다.

## 6. R1 뒤 (§15.301)

R1 이 «취소(R1)» 묶음 아홉과 송신 묶음의 취소 둘을 `CancelAxes.kt` 의 네 축으로 바꿨다. §2.2 표의 행은 아래 축의 데이터가 됐다. 옛 이름은 시험이 읽도록 계산 속성(`get()`)으로 남았다.

| 옛 필드 | 축 | 값 |
|---|---|---|
| `cancelRequested` `cancelRefusal` `lastCancel` | `cancelIntent: CancelIntent` | `None` · `Requested` · `Refused(code)` · `Reported(report)` |
| `cancelUnsent` `notedCancelUnsent` `unreachedCancelSince` | `cancelDelivery: CancelDelivery` | `Clear` · `Unsent(cause, linkSince)` |
| `doubtCancelHold` `unattachedCancelSince` `notedUnattached` | `cancelWait: CancelWait` | `None` · `Attaching(since, noted)` · `Held(unitId)` |
| `cancelInFlight` `fleetCancelInFlight` | `cancelSending: CancelSending` | `Idle` · `Robot` · `Fleet` · `Both` |

함께 서는 축의 조합(그래서 축이 넷이다):

| 조합 | 서는 자리 |
|---|---|
| 보내는 중 × 닿지 않음 | 도는 단위의 취소를 로봇·플릿에 함께 보내고 한쪽이 던졌는데 다른 쪽이 나가는 중(`markOtherFailed`) |
| 보내는 중 × 보류에 묶임 | 보류 중 다시 보내기(`resendHeldCancel`, 자리 `HELD`) |
| 보내는 중 × 붙기 전 대기 | 붙기 전 취소가 잠금 밖에서 나가는 중(`attachForCancel`, 자리 `ATTACH`) |
| 닿지 않음 × 보류에 묶임 | 보류에 묶이는 네 자리 전부(`holdUnsentCancel` `holdUnreachedCancel` `attachTransportForCancel` `waitUnattached`) |
| 이상 없음 × 보류에 묶임 | 보류 중 다시 보낸 취소가 답을 받은 뒤(`heldCancelAnswered`) |
| 보내는 중 × 응답 냄 | 끝난 실행에 남은 잠금 밖 취소(`forget` 이 이빅션을 막는다) |

숨은 조합(옛 코드에서만 서고 새 타입은 걷는 것)은 §15.301 에 적었다. 걷힌 표시 `doubtCancelHold` 가 붙기 전 대기와 함께 서던 것, 끊김 시각이 닿은 뒤에도 남던 것이다.

## 7. R2 뒤 (§15.302)

| 옛 필드 | 새 자리 |
|---|---|
| `notedDelay` `notedHold` `notedDoubt` `notedBlock` | `noticed` 의 `Notice.Delay` `Hold` `Doubt` `Block` |
| `lastNotified` | `Notice.Attention` (주의 세 칸만) |
| `pumpFailures` `clockSkewNoted` `signalSeen` `seenLate` | `Notice.PumpFailure(source)` `ClockSkew(robot)` `Signal(taskId)` `Late(unit, revision, state)` |

쓰는 함수와 읽는 함수는 §2.2 표의 그 행과 같다. 필드 대신 `execution.noticed.note·clear·set` 을 부른다.

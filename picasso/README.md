# picasso — 지능형 미들웨어 코어 (Middleware Core Engine)

상위 제조·물류 실행 시스템(MES/WMS)과 하위 이종 로봇 및 현장 자동화 설비 간의 작업을 조율하는 **아키텍처 제3계층(배정 및 완료 결정 엔진)**의 핵심 구현 모듈입니다 (ADR 38).

상위로부터 인입된 복합 작업 지시(JobOrder)를 원자적 실행 단위(`ExecutionUnit`) 시퀀스로 분해하고, 하위 계약 계층(제4계층)을 통해 로봇에 하달하며, 로봇의 자체 완료 보고와 **현장 물리 설비(Cell/PLC)의 센서 신호**를 결합하여 최종 작업 완료 여부를 결정론적으로 승인합니다.

---

## 1. 핵심 컴포넌트 구성

| 소스 파일 | 핵심 도메인 모델 및 아키텍처 역할 |
|---|---|
| `Model.kt` | **정준 도메인 모델**: 2차원 상태기계(`PhysicalState` 및 `TaskState`), 4단계 근거 등급(`E0`~`E3`), `ExecutionUnit`, ISA-95 대응 `JobOrder`/`JobResponse`, `CancelReport` |
| `LogicalCapability.kt` | 상위 논리적 케이퍼빌리티 인터페이스 및 3대 구현체 (`PrepareSequencedRack`, `DeliverContainer`, `InspectAsset`), 케이퍼빌리티 단위 타임아웃 구성 (유효 시간 윈도우 δ, `IN_DOUBT` 유예 기간, 진행 정체 유예) |
| `Middleware.kt` | 코어 상태 엔진: 작업 수락 → 분해 및 조합 → 실행 디스패치 → 근거 결합 판정 → 상위 통보. 단일 틱 구동 함수 `pump()` 기반의 결정론적 전이. 상태(실행 표·`Execution`), 라운드 순서(`pump`·`pumpRound`·`abort`), 관측·통보, 공개 API 와 접합부를 잇는 조율은 여기 남고, 상태 없는 하위 절차는 아래 아홉 파일에 같은 클래스의 확장(`fun Middleware.`)으로 둡니다. 배정 게이트의 판정과 제안·인시던트 두 장부는 아래 셋에 위임합니다 |
| `Resume.kt` | 재개와 판단 기록의 비공개 절차(`Middleware` 확장) |
| `Revision.kt` | 리비전 개정과 개정 갱신의 송신·정산(`Middleware` 확장) |
| `LifecycleIssue.kt` · `LifecycleDoubt.kt` · `LifecycleCancelAttach.kt` · `LifecycleCancel.kt` | 명령 생애: 발행, 미확정 해소, 취소용 붙이기, 취소 송신·폴링. 서로를 부르는 하위 상태기계 하나를 넷으로 나눈 것(`Middleware` 확장) |
| `ClockSkew.kt` | 지금 읽기와 발신자 시계 차 구간(`Middleware` 확장) |
| `UnitDrivers.kt` | 경로별 단위 구동: 로봇 스킬, 설비 신호, 플릿 운반(`Middleware` 확장) |
| `EvidenceBinding.kt` | 근거 결합과 운영자 판단의 보조(`Middleware` 확장) |
| `AdmissionGate.kt` | 배정 게이트(설계안 §7): `admits` 와 그 아래 술어 다섯. 피어 시스템 접합부(배정·공간)의 작업 수락 판정 |
| `RemedyDesk.kt` | 탐색 기록·제안·보류·진단·승인 장부와 승인 판정(설계안 §6.4, ADR 43·44·45). 승인 뒤의 작업 수락은 `Middleware` 가 조율 |
| `IncidentLog.kt` | 인시던트 번들의 봉인·조회, 사후 검토의 기록과 지표, 운영자 판단 부착(설계안 §4) |
| `Canonical.kt` | 작업 응답·인시던트 번들·이벤트 트레이스가 나눠 쓰는 정준 프로젝션 셋 |
| `Ports.kt` | 하위 시스템 연동 추상화 포트: `RobotPort` (인터페이스 계약 소비자), `CellSignals` (현장 설비 센서 신호, E2), `AmrFleetPort` (이송 플릿 연동, E1), `MissionCatalog` (WorkMaster 마다 지금 활성인 케이퍼빌리티와 임무 버전을 주는 포트, 새 작업 지시만 읽음), `SiteTimingsSource` (현장 시간값 한 세트 `SiteTimings` 를 주는 포트, pump 라운드마다 한 번 읽음, 허용 범위 상수와 검사 함수 포함) |
| `PrefetchingRobotPort.kt` | 스냅숏과 재생을 소비자의 잠금 밖에서 미리 읽는 `RobotPort` 데코레이터(§15.267): 기체마다 읽기 하나, 다 읽은 값은 한 번만 주고 실제 왕복 시각을 함께 줌, `maxAge` 넘은 값은 버림. 결과 형 `PrefetchedRead`(`Pending`·`Unread`·`Read`·`Failed`) |
| `mission/` | **임무 정의 패키지** (`dev.picasso.middleware.mission`): 임무 정의 스키마(`MissionDefinition`)와 엄격 파서(`MissionDefinitionParser`), 신호 사양(`SignalSpec`), 해석기(`DefinedCapability`), 검증기(`MissionValidator`, 5검사 + 노드 id), 메모리 카탈로그(`InMemoryMissionCatalog`, 활성화 게이트). 설비 대기 진행 · 버전 고정 · 인시던트 칸 등 엔진 쪽 변경은 기존 파일에 있음 (ADR 50) |

---

## 2. 아키텍처 경계 및 관심사 분리

- **기종 비의존 원칙 (Vendor-Agnostic)**: 게이트 검사 7번을 통해 본 모듈 소스 코드 내 특정 벤더나 기종 명칭의 유입을 엄격히 차단합니다. 벤더별 고유 코드를 표준 분류로 변환하는 책임은 어댑터 계층(ADR 33)에 있으며, 미들웨어 코어 내에 기종별 분기(`if robot == ...`)가 존재해서는 안 됩니다.
- **케이퍼빌리티 추가 시 코어 엔진 불변성**: 엔진 내부에 개별 케이퍼빌리티별 하드코딩 분기가 없습니다. 케이퍼빌리티는 `LogicalCapability.plan()` 으로만 엔진에 닿으며, 임무 정의(데이터)도 `DefinedCapability` 가 같은 길로 들입니다. `InspectAsset` 추가 당시에는 코어 엔진의 로직 수정 없이 **3대 일반 규칙**(등급 상한 거부, 결과 참조 처리, 취소 거부 노출)의 매개변수화만으로 먼저 수용되었습니다 (§15.93). 이후 엔진은 선언된 선행 단위가 `DONE`으로 끝나지 않은 단위를 건너뛰는 하나의 일반 규칙을 추가로 갖추게 되었습니다 (§15.234). 단위의 **종류**를 늘리는 것은 다릅니다. 설비 대기(`Route.SIGNAL`)는 엔진에 경로 하나와 그 진행을 더했습니다 (ADR 50).
- **글로벌 배차/경로 계획의 스코프 외 분리**: 공장 전체 단위의 AMR 라우팅, 배차 최적화, 전역 자원 선점은 상위 플릿 관제 시스템의 책임이며, 본 모듈은 단일 워크셀 내 작업 분해 및 상태 보증에 집중합니다.

---

## 3. 제3계층 특화 아키텍처 메커니즘

- **검증 불가 상태 (`UNVERIFIED`)**: 로봇은 하드웨어 완료를 보고했으나 현장 설비 센서의 확인 신호가 도달하지 않은 경우 불확실성을 명시적 상태로 보존합니다.
- **상위 승인 상태 분리 (`upstream_ack`)**: 물리적 작업 완료와 상위 시스템으로의 통보 성공 여부를 독립적인 차원으로 관리하여, 네트워크 단절에 따른 통보 재시도와 물리 명령 재시도를 엄격히 분리합니다.
- **다중 소스 근거 등급 결합 (Evidence Aggregation)**: `E0`(로봇 자체 보고), `E1`(플릿 확인), `E2`(설비 센서 신호), `E3`(작업자 수동 확인)을 결합합니다. 요구되는 신뢰 등급을 충족할 수 없는 기체 조합은 작업 수락 단계에서 선제 거절합니다.
- **지연 이벤트 보존 및 재평가**: 구성 버전 변경 중 도착한 이전 버전의 종료 이벤트는 유실되지 않고 안전하게 보존되어 신규 버전의 기대 조건에 맞춰 재평가됩니다 (§15.1).

---

## 4. 자동화 테스트 및 검증 매트릭스

| 테스트 클래스 | 검증 시나리오 및 보증 사항 |
|---|---|
| `SequencingRackTest` | 시나리오 ② — 4개 슬롯 순차 적재, 런타임 버전 갱신, 중간 취소 및 설비 신호 결합 · 계획 시점 사전 조건 연쇄 검사 · 적재 유실의 세 차원 통보 |
| `DeliverContainerTest` | 시나리오 ① — 이송 플릿 위임(`E1`) 및 자재 공정 간 인계 신호 검증 |
| `InspectAssetTest` | 시나리오 ③ — 순회 · 단계 둘 · E0 상한 검증. 선행 단위 미완료 시 점검 건너뜀 (§15.234) |
| `EvidenceWindowTest` | 센서 응답 유효 시간 윈도우 δ 초과, 재확인 루프, `UNVERIFIED` 및 `VERIFICATION_MISMATCH` 처리 |
| `InDoubtTest` · `LateEventTest` | 명령 작업 수락 불확실 상태 해소 절차 및 버전 전환 간 지연 이벤트 처리 무결성 |
| `EventStreamTest` | 상태 스냅샷 권위, 이벤트 재생 및 장애 복구 후 엔진 재동기화 |
| `ProgressStallTest` | 작업 정체 가시화 — 정량 측정이 불가능한 기체에 대한 허위 판정 배제 (계약 0.8.0 사양) |
| `EquipmentWaitTest` | 설비 대기(`Route.SIGNAL`): 신호의 지금 값으로 판정해 `DONE`(E2) · 기한 시각까지 대기하고 넘으면 운영자 보류(라인 멈춤) · 재작업과 확인 · 기한 뒤 `ABORTED`(대기 `FAILED`, 남은 단위 미출발, 취소 응답 없음) · 대기 중 취소 · 대기 중 리비전 |
| `MissionVersionScenarioTest` | 임무 버전 시나리오: 버전 1 실행 중 버전 2 활성화 시 새 작업 지시만 버전 2 로 대기를 거치고 옛 실행과 그 리비전은 버전 1 로 끝남 · 없는 신호를 참조하는 버전 3 은 거부되고 활성 버전 2 유지 · 인시던트는 실행이 쥔 버전을 실음 · 배정은 게이트에 댄 버전을 쥠 · WorkMaster 를 바꾸는 리비전은 거부 |
| `mission/*Test` | 임무 정의 패키지 4종: 파서(모든 오류를 한 번에 모음, 중복 키 거부) · 동등성(작업 지시 모양 9가지에서 코드 `PrepareSequencedRack` 과 칸마다 같음) · 검증기(5검사와 노드 id 각각) · 메모리 카탈로그(버전 번호, 거부, 스레드 겹침) |

---

## 5. 알려진 한계 및 엔지니어링 고려 사항

본 모듈의 단위/통합 테스트는 외부 상위 시스템과 현장 설비를 모사한 테스트 대역(`CellMimic`, `AmrFleetMimic`)을 기반으로 동작합니다. 따라서 테스트 스위트의 성공은 설계 가정 하에서의 시스템 일관성을 증명하며, 물리 실제 하드웨어 환경과의 실제 연동 검증 등급은 [`docs/verification.md`](../docs/verification.md)의 10·11번 항목에 명시되어 있습니다.

> 마지막 대조: 2026-11-12 · sha256:7d16baa8f142 · 열림: §15.126

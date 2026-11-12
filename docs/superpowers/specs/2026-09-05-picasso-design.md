# picasso — 이기종 로봇 표준 I/F 계약과 운영 변경 체계

설계 사양서 · 2026-09-05

## 1. 목적과 범위

### 1.1 목적

이기종 모바일 로봇(휴머노이드 및 4족 보행 로봇)을 공장 운영 시스템에 통합할 때 요구되는 **표준 인터페이스 계약**을 정의하고, 해당 계약을 **실물 기체 없이 독립적으로 검증할 수 있는 가상화 환경**을 구축하며, **운영 환경에서의 변경 파급 범위를 결정론적으로 계산**하는 아키텍처를 수립한다.

본 아키텍처는 다음 두 가지 핵심 명제를 기반으로 설계되었다:

1. **이기종 로봇 대응은 소스 코드 수정이 아닌 프로파일 교체로 완결되어야 한다.**
2. **운영 환경 변경의 파급 범위는 사전에 계산 가능해야 한다.** 영향도를 예측할 수 없는 변경은 운영 리스크를 초래하며, 변경에 대한 심리적 저항은 시스템의 기술 부채와 경직성을 심화시킨다.

두 명제 모두 단순한 개념 증명에 그치지 않고, CI 실패 조건 또는 운영 제어 거부 조건으로 시스템에 엄격히 강제한다.

**핵심 설계 원칙: 확장은 안전하지만 축소는 파급을 수반한다.** §9의 모든 운영 변경 규칙은 이러한 비대칭성을 기반으로 도출된다.

### 1.2 범위

근거 문서 `이기종-로봇-공장-연계-설계노트` §5 작업 백로그 기준:

| 항목 | 내용 |
|---|---|
| A-1 | 스킬 모델의 Protocol Buffers 이식 |
| A-2 | 이벤트 계약 (상태/이벤트 분리, 스냅샷, 순서 보장, 멱등성, 결함 모델) |
| A-4 | 장기 실행 작업 모델 (goal/progress/result/cancel/retry) |
| C-1 | 능력 프로파일 스키마 |
| C-2 | 프로파일 주도 가상화 에뮬레이터 (MiMic) |
| D-1 | 스키마 호환성 검증 게이트 |
| D-2 | 아키텍처 결정 기록 (ADR) — 목록은 §14 |

설계 과정에서 확장된 4개 항목:

| 항목 | 사유 |
|---|---|
| 능력 호환성 체계 | 제조사 및 펌웨어 버전에 따른 능력의 확장·축소·세분화에 대응하기 위한 API, 헤더, 토픽, 파라미터 키 설계 및 통신 검증 전략 수립 |
| 레지스트리 및 런타임 갱신 | 프로파일은 설계노트 §4.5 기준 데이터로 취급되며, 갱신을 위해 프로세스를 재시작하지 않는 핫 리로드 구조 확립 |
| **운영 변경 체계** (§9) | 어댑터 추가·변경 및 기능 추가·변경·삭제 시 파급 범위와 반영 순서를 정립하고, 상위 연계 시스템까지의 정합성 보장 |
| **상위 연계 계층** (§9.6) | **2026-09-08 추가.** 인터페이스 표면만 정의하고 소비 계층을 배제할 경우 소비자 없는 무효한 선언이 됨([ADR 9](../../adr/0009-no-declaration-without-consumer.md)). 하위 어댑터와 대칭되는 형태로 아키텍처 경계 내에 수용 (계층 프레임워크는 프로젝트 범위, 특정 현장 인스턴스는 배포 환경 소유) |

구현은 §13의 4단계 마일스톤으로 분할하여 진행한다.

### 1.3 비목표 (Non-Goals)

- **A-3 원자적 명령 전달의 대안 비교**: 파라미터 전달과 실행 트리거를 단일 RPC로 통합하여 경쟁 조건을 근본적으로 차단하는 방식을 채택한다.
- **A-5 이동·내비게이션 스킬 타입 확장**: 구체적 스킬 타입 어휘 확장 작업이며, 기본 구조는 A-1에서 포괄한다.
- **A-6 위치 레지스트리**: 스키마 차원에서 확장을 저해하지 않도록 인터페이스만 유지하고, 구체적 구현은 배제한다.
- **B-1 무선 단절 대응의 지속성 집행**: **2026-09-09 범위 조정([ADR 38](../../adr/0038-mission-layer-schema-is-ours.md)): 프로세스 재기동 후 저장소 기반 상태 복원만 제외하며, 접수 불명(`IN_DOUBT`) 상태의 해소 경로는 범위 내에 포함한다.** 단, **멱등성 키는 계약 사양에 현 시점에 포함한다.**
- **B-2 텔레메트리 경로 분리**: 고주파수 텔레메트리 스트림 분리는 제외하며, 프로파일은 텔레메트리 항목을 정의하지 않는다.
- **B-3 브리지 정책**: 외부 네트워크 브리지 구성 정책은 배제한다.
- **C-3 적합성 역검증 실행**: 실물 하드웨어 도입 시 수행되는 검증이며, 본 프로젝트에서는 워크플로우 상의 상태 전이 및 인터페이스 자리를 사전에 구축한다(§9.7 ④).
- **D-3 예외 구역 경계 집행**: 사이트 분기 정적 검사는 배제한다.
- **미션 계층**: 배차, 동적 라우팅, 자원 중재, 다중 로봇 간 경합 제어는 비목표로 둔다. 따라서 **동시성 정책 어휘(blocking type)도 계약에 포함하지 않는다.** **2026-09-09 범위 조정([ADR 38](../../adr/0038-mission-layer-schema-is-ours.md)): 제외 대상은 전역 배차·경로 제어·자원 중재에 한정되며, 논리적 능력의 합성 및 실행 상태 전이 관리는 범위 내에 포함한다. 해당 스키마 및 PoC 엔진은 [미들웨어 코어 설계](2026-09-09-middleware-core-design.md)에서 정의한다.**
- **즉시 명령(Instant Action)**: 단일 단계 태스크로 표현 가능하므로 별도의 전용 인터페이스 표면을 두지 않는다.
- **상위 시스템 어댑터(ACL) 구현**: **2026-09-08 비목표 목록에서 제외 및 경계 내 수용.** 코어 계층 프레임워크는 프로젝트 아키텍처 경계 내로 편입하고 인스턴스 소유는 배포 환경으로 분리한다(§9.6, [ADR 9](../../adr/0009-no-declaration-without-consumer.md)).
- **물리 시뮬레이션**: 도달 가능성(reachability), 기하학적 충돌, 파지 역학 시뮬레이션은 배제한다.
- **안전 기능**: 비상정지(E-Stop), 안전 정격 감속, 인체 감지 보호 정지 등 안전 계통 신호는 본 계약을 경유하지 않으며, 안전 회로는 본 시스템 가용성과 독립적으로 동작한다([ADR 32](../../adr/0032-safety-boundary.md)). 즉시 명령 배제 논리는 작업 제어 명령에만 유효하다.
- **지연 예산**: 종단 간 명령 왕복 지연의 상한은 규정하지 않는다. `publish_interval`의 30초 규정은 **상태 발행 주기**이며 지연 상한이 아니다.
- **인증·인가 체계**: §6.3의 신뢰 네트워크 전제를 적용한다.
- **정책 설정 저장소**: 임계값 및 상한 설정의 데이터베이스화는 실제 소비자가 정의된 이후로 유예한다.

### 1.4 완료 기준

§12.2에 **23개 행**으로 체계화하여 관리한다. 백로그 14개 행(A-1 1건, A-2 4건, A-4 6건, C-1 2건, C-2 1건), D-1 1건, 능력 호환성 1건, 레지스트리 및 런타임 갱신 2건, 운영 변경 5건으로 구성된다.

A-4 항목이 6개로 확장된 것은 실물 로봇 인터페이스 조사(§2.3) 결과에 근거한다. 취소 시의 물리적 복구 절차, 운영자 개입 요구 상태, 터미널 상태 비래치 기종 대응, 제어권 탈취 등 실물 제어 환경의 제약 조건들이 태스크 수명주기 모델에 직접 반영되어 6·7·8·8b·8c·8d로 구체화되었다.

*(2026-09-09 정정 — 이전 문서에서 24개 행·A-4 7개 행으로 기술되었으나, §12.2 표의 실제 A-4 행 수는 6개이며 총 23개 행으로 정합성을 일치시켰다.)*

## 2. 배경과 근거

### 2.1 근거 문서

`이기종-로봇-공장-연계-설계노트`(38쪽): L3~L5 계층 구조, 6개 운영 국면, 변경 수용 전략, 기술 스택 선정 기준, API 규격 및 페이로드 카탈로그, §5 작업 백로그가 정의되어 있다.

### 2.2 벤치마킹 분석

오픈소스 표준 저장소 분석: `Labs/[oss]/opentcs`, `Labs/[oss]/VDA5050`(v3.0.0, MIT 라이선스). 코드를 직접 복제하지 않고 검증된 아키텍처 패턴만을 선별 수용하였으며, 각 설계 결정의 근거는 ADR에 명시한다.

| 차용 패턴 | 출처 | 적용 위치 |
|---|---|---|
| 실패 등급을 "잔여 역량"으로 정의 | VDA5050 `state.schema` `errorLevel` | §4.6 |
| `RETRIABLE` 상태 모델 | VDA5050 `actionStatus` | §4.4 |
| 결함 수명주기, `errorHint`, `errorReferences` | VDA5050 에러 카탈로그 | §4.6 |
| 연결 상태 4값 모델 (통신 침묵 원인의 유형화) | VDA5050 `connection.schema` | §4.7 |
| 상태 발행 상한 주기 (최대 30초) | VDA5050 §6.6 (`VDA5050_EN.md:1034`) | §7.2 |
| `(orderId, orderUpdateId)` 단조 시퀀스 쌍 = 멱등성 키, 재수신 4케이스 분기 | VDA5050 §6.1.4 | §4.4 |
| 능력 프로파일 구조 전반 | VDA5050 `factsheet.schema` | §7.2 |
| `pauseAllowed` / `cancelAllowed` 필수 선언 체계 | VDA5050 `mobileRobotActions` | §7.2 |
| `optionalParameters {parameter, support}` 구조 | VDA5050 `protocolFeatures` | §5.3 |
| `protocolLimits` — 제약 한계의 선언 데이터화 | VDA5050 `protocolFeatures` | §7.2, §10.4 |
| 토픽 경로에 Major 버전 포함 | VDA5050 §4 | §5.5 |
| 거절 사유를 명시하는 `ExplainedBoolean` 패턴 | openTCS SPI | §5.4 |
| 비동기 취소 전이 모델 (`WITHDRAWN`) | openTCS `TransportOrder.State` | §4.4 |
| 에뮬레이터 제어 채널 및 단계적 실행 제어 | openTCS loopback | §10.5 |
| 계약 모듈 무의존 원칙 | openTCS `opentcs-api-base` | §3.2 |
| 송신 메시지 검증 게이트 | openTCS | §6.2 |

**안티패턴 및 반례 분석:**

- openTCS의 VDA5050 어댑터는 factsheet를 수신하지만 이를 실제 제어에 반영하지 않고 폐기한다(`v2_0/CommAdapterImpl.java:529`). 능력 정의는 수동 입력 문자열에 의존하며, 미선언 항목에 대해 fail-open으로 동작한다. "선언 메커니즘이 존재하더라도 런타임에서 소비되지 않으면 무효하다"는 교훈에 따라, 본 설계에서는 정적 검증(§11.2 검사 4)과 **런타임 투영 일치 검증(§12.2, `GetCapabilities` 응답과 프로파일 파생 능력의 완전 일치 검증)**을 이중으로 배치한다.
- openTCS의 스키마 검증 플래그 `VALIDATE_INCOMING_MESSAGES`를 비활성화하면 송신 검증까지 일괄 비활성화되는 결함이 존재한다. 본 설계는 §6.2에서 수신 및 송신 검증 플래그를 완전히 분리한다.
- openTCS loopback은 단 6개의 제어 파라미터만 제공하며 구체적 거동이 코드로 하드코딩되어 있어 기종 확장이 코드 수정으로 이어진다. 본 설계의 프로파일 주도 가상화(C-1/C-2)는 이를 극복하기 위해 설계되었다.

### 2.3 실물 로봇 인터페이스 조사 (2026-09-05)

공개 SDK 및 공식 기술 문서가 확보된 3개 기종을 전수 조사하여 본 인터페이스 계약의 포용성을 검증하였다.

**Boston Dynamics Spot** — 4족 보행 로봇. gRPC + Protocol Buffers 기반. 본 설계의 표준 참조 모델 역할을 한다. `DirectoryService.ListServiceEntries`를 통한 동적 서비스 탐색, `RobotIdService.GetRobotId`를 통한 고유 식별자(`serial_number`, `species`, `software_release`) 조회, `GetRobotHardwareConfiguration`을 통한 불리언 기반 하드웨어 기능 판별 체계를 제공한다. 결함 진단은 `SystemFault{severity, dtc, attributes}` 및 `BehaviorFault{Cause ∈ FALL|HARDWARE|LEASE_TIMEOUT, Status ∈ CLEARABLE|UNCLEARABLE}`로 구성되어 로봇 스스로 복구 가능성을 판정한다. 제어권 소유권은 `Lease{resource, epoch, sequence[]}` 벡터 클럭으로 관리된다.
⚠️ **라이선스 제약**: `20191101-BDSDK-SL` §2(c)는 BD 하드웨어 전용 사용을 명시하며 §2(b)는 재라이선스를 금지한다. 따라서 Spot protobuf 스키마 및 생성 코드를 벤더 중립 공통 모듈에 포함할 수 없으며, 모든 코드는 전용 Spot 어댑터 내부로 완전 격리한다.

**Unitree G1** — 휴머노이드 로봇. BSD-3-Clause 라이선스로 개방된 SDK를 제공하며, CycloneDDS 기반으로 ROS2 RMW 레이어에 직접 참여한다. 그러나 고수준 인터페이스의 완성도가 낮다. API 대다수가 정수형 FSM ID 기반의 원시 래퍼(`Damp()=SetFsmId(1)`, `Start()=SetFsmId(500)`)이며, **로봇 신원 질의 인터페이스가 부재**하고 결함 상태를 능동 보고하지 않아 클라이언트 측에서 과열 및 자세 이상을 자체 판정(`terminations.hpp`)해야 한다. 관절 한계 정보는 SDK가 아닌 URDF 파일(`<limit lower upper effort velocity>`)에만 기술되어 있으며, 인증 메커니즘이 없어 네트워크 연결 자체가 전권을 의미한다.

**Agility Digit / Arc** — 휴머노이드 로봇. 로봇 기체는 WebSocket JSON API(`ws://<ip>:8080`, 서브프로토콜 `json-v1-agility`, 메시지 포맷 `["type", {...}, refnum]`)를 제공한다. 본 표준 계약과 충돌하는 4가지 제약 조건:
- `change-action-command` 권한은 전체 시스템에서 단 하나의 클라이언트만 독점하며, 권한 상실 시 로봇은 즉시 `action-idle` 상태로 리셋된다 (→ §4.9 제어권 상실 대응).
- 태스크 취소 및 일시정지 프리미티브가 부재하며, 중단은 신규 액션 덮어쓰기 방식으로만 가능하다 (→ §7.2의 `Support` 3값 모델).
- 공식 매뉴얼에 명시된 대로 종착 상태가 영구 고정되지 않는다(*"This status does not latch once reached"*). `success` 상태에서 다시 `running`으로 역전될 수 있다 (→ §4.4 터미널 래치 불변식 및 `TERMINAL_STATE_VIOLATED` 결함).
- 실패 사유가 비구조화된 텍스트(`info`)로만 제공되어 어댑터 레이어에서 정형화된 `error_type` 어휘로의 변환이 필수적이다.

**Agility Arc(클라우드 오케스트레이션)**는 본 아키텍처와 동일한 설계 결론을 채택하고 있다. `organization → facility → workcell → device` 계층과 `workflow`/`skill`/`intervention` 도메인 모델을 공유하며, 능력 선언을 `(deviceModelIds, oasVersion) → 블록 집합`으로 해석하고 워크플로우를 특정 버전에 고정(pinning)한다. UI 상의 "No model guarantee" 경고는 §15.1의 한계와 일치하며, 워크플로우 상태로 `CANCELED_WITH_RECOVERY`, `CANCELED_RUNNING_RECOVERY`, `CANCELED_FAILED_RECOVERY` 및 1급 `INTERVENTION` 개념을 채택하고 있어 본 설계의 `NEEDS_INTERVENTION` 및 `CANCELLED_RECOVERY_FAILED` 규격과 정합한다.

**프로파일 요구 항목과 실물 기종 지원 현황 대조:**

| §7.2 항목 | Spot | Unitree G1 | Digit |
|---|---|---|---|
| 기종 좌표 | ✅ `RobotId` | ❌ 신원 질의 부재 | △ `robot-info` 4개 필드 |
| 지원 스킬 및 버전 | ✅ `ListServiceEntries` | △ 상지 전용 `GetActionList` | ✅ 고정 액션 어휘 |
| 취소·일시정지 가능 여부 | △ 선언 메커니즘 부재 | ❌ 미지원 | ❌ 개념 부재 |
| 파라미터 범위 및 단위 | △ `Skeleton` | ✅ URDF `<limit>` | △ 문서상 정적 5 kg |
| 프로토콜 한계 | ❌ 미선언 | ❌ 미선언 | ❌ 미선언 |
| 실패 모드 분류 | ✅ 정형화된 결함 보고 | ❌ 클라이언트 자체 판정 | ❌ 비정형 문자열 |
| 상태 발행 주기 | △ `liveness_timeout_secs` | ❌ 미지원 | ✅ `query-group{period}` |

어떤 실물 로봇도 프로파일을 완전하게 채우지 못하며, 이는 프로파일이 기체의 능동 보고가 아닌 벤더 사양 문서로부터 분석·파생되는 데이터 모델이기 때문이다(§15.1). 이러한 대조를 통해 "미지원(NO)"과 "근거 부재로 인한 미확인(UNKNOWN)"을 명확히 분리하는 `Support` 3값 체계가 도출되었다.

**산업 표준 동향:**
- **ISO 21423** (산업용 모바일 로봇 상호운용 표준, MQTT+JSON, MassRobotics와 VDA5050 통합): Stage 60.00(2026-07) 단계로 발행되었으며 `capabilities` 객체를 정의하고 있다.
- **IDTA 02020 Capability Description** (AAS 능력 서브모델, 2026-04 발행): §1.8.4에서 범용 스킬 서브모델의 표준화 미비점을 명시하고 있어, 능력 메타데이터와 구체적 스킬 실행 규격 간의 간극을 드러내고 있다.
- **NVIDIA `isaac_mission_control`**: VDA5050 규격 외 확장을 통해 AGV 클래스에 `MANIPULATOR`와 `HUMANOID`를 자체 추가하였다. 이는 기존 표준이 휴머노이드 및 다관절 로봇을 온전히 수용하지 못하고 있음을 실증적으로 입증한다.

## 3. 아키텍처

### 3.1 모듈 구성

```
picasso/
  contracts/          proto. 스킬·태스크·이벤트·결함           A-1 A-2 A-4
  profile/
    schema/           능력 프로파일 JSON Schema                C-1
    profiles/         기종 프로파일 문서들 (*.json)
    fixtures/         게이트·시험 전용 픽스처 (프로파일·요구 집합)
  profile-model/      프로파일 문서의 읽기 전용 모델 — gate·mimic 공유 (ADR 29)
  capability/         능력의 투영과 판정 — 프로파일 → Capability, 요구 집합 대 Capability 협상.
                      mimic·어댑터 호스트 공유 (§15.98·§15.100). 전송 계층 무의존
  uplink/             발신자 상향 결선 — 브로커 발행(§3.5)과 레지스트리 적재(핸드셰이크·태스크·생존·폴백). mimic·어댑터 호스트 공유 (§15.99)
  registry/           개정판·어댑터·원장·변경 계획·카탈로그    §8, §9
  mimic/              프로파일 주도 가상화 에뮬레이터 + 제어 채널 C-2
  client/             계약 소비자 — 완료 기준 증명용
  picasso/            미들웨어 코어 — 정준 모델 및 공통 실행 엔진. 기종 무의존 (ADR 38)
  adapter-core/       어댑터 공유 계약 어휘 및 RobotAdapter. 기종 무의존 (ADR 33)
  adapter-host/       개별 어댑터를 gRPC 계약 서비스로 노출하는 서버. 기종 무의존 (ADR 39)
  adapter-<vendor>-<model>/
                      실물 기종 어댑터. 기종별 지식이 격리되는 유일한 영역 (ADR 33)
                      adapter-boston-dynamics-orbit/ 만 단일 기체가 아닌 플릿 제어에 연동 (§15.102)
  gate/
    src/              검증 라이브러리. CI 및 registry 공통 호출 D-1
    negative/         네거티브 테스트 케이스 데이터 (비정상 proto 조각, 합성 diff)
  harness/            계약 검증 스위트 실행기                  §12
  docs/adr/           아키텍처 결정 기록                        D-2
```

`profile/profiles/**`와 `profile/fixtures/**`의 분리는 엄격한 규약이다. §11.2 검사 8이 전자의 경로만으로 "프로파일만 변경된 PR"을 판정하므로, 테스트용 픽스처가 혼입되면 변경 판정 로직이 왜곡된다.

### 3.2 의존 규칙

**`contracts/`는 프로젝트 내 모듈에 대한 의존성을 전혀 갖지 않는다(의존성 0).** 이는 openTCS `opentcs-api-base`의 아키텍처 원칙과 동일하며, §11.2 검사 5가 CI 빌드 파이프라인에서 이를 강제한다.

**빌드 의존성**: 정본 사양은 `docs/architecture.md` §4b에 기술되어 있으며, `DocumentClaimsTest`가 각 모듈의 `build.gradle.kts`와 지속적으로 정합성을 기계 검증한다. 주요 비대칭성 및 격리 원칙은 다음과 같다:

- `capability`: gRPC 라이브러리에 직접 의존하지 않으며 판정 불가 상태는 순수 도메인 타입으로 표현된다. 전송 매핑은 서비스 계층이 담당한다.
- `uplink`: `registry`에 빌드 의존하지 않으며 상향 적재는 표준 HTTP 인터페이스를 경유한다.
- `adapter-host`: 개별 기종별 어댑터 모듈에 의존하지 않으며 런타임 조립은 기종 인식 계층에서 수행된다(ADR 39).
- `mimic`: 투영 및 협상 판정 로직은 `capability`로, 메시지 발행 및 레지스트리 적재는 `uplink`로 위임 분리되었다(2026-09-10).
- `client` 및 `adapter-<vendor>-<model>`: 하위 격리 원칙을 준수한다.

**`adapter-*`는 기종마다 모듈 하나다(ADR 33).** 게이트 7번이 보는 열 (`client` · `mimic` · `harness` · `adapter-core` · `picasso` · `capability` · `adapter-host` · `uplink` · `registry` · `profile-model`) **밖**에 두는 것이 요점이며, 기종 지식이 갈 곳이 정확히 거기라서 나머지가 기종을 모를 수 있다. 로봇 어댑터 셋(`adapter-agility-digit`·`adapter-boston-dynamics-spot`·`adapter-unitree-g1`)의 의존성이 `adapter-core`·`contracts`뿐인 이유도 동일하다: 어댑터가 `registry`를 알면 순환 회피 규칙이 위반되고, `profile-model`을 알면 구현과 선언의 원천이 결합되기 때문이다. 반면 `adapter-boston-dynamics-orbit`는 플릿 단위 연동 및 배치 런처 특성상 `adapter-core`, `adapter-host`, `contracts`, `profile-model`, `uplink`를 참조한다(ADR 37, 39).

**`adapter-core`의 태동**: ADR 33에서 예고한 바와 같이, 다수 어댑터 간의 공통 어휘 추출 요구에 따라 신설되었다. 추출된 공통 모듈은 기종 무의존성을 유지해야 하므로 게이트 7번 검사 목록에 편입되었다. 검사 7번 대상 모듈은 현재 총 10개이다 — `client` · `mimic` · `harness` · `adapter-core` · `picasso` · `capability` · `adapter-host` · `uplink` · `registry` · `profile-model`. 기종별 어댑터 모듈 자체는 기종 지식을 캡슐화하는 목적을 가지므로 이 목록에서 제외된다. 정본 목록은 코드(`Check07ModelBranching.MODULES`)에 선언되어 있으며 `DocumentClaimsTest`에 의해 지속 검증된다.

**`profile-model` 신설 (ADR 29)**: 프로파일 문서 파싱 로직이 `gate`와 `mimic` 양쪽에 필요함에 따라, `mimic`이 `gate`의 buf 실행기 및 9개 검사 규칙에 불필요하게 결합되는 것을 차단하기 위해 공통 모듈로 분리하였다.

**`client → profile-model` 의존성 (Chunk 3a)**: 요구 사양이 코드가 아닌 설정 파일로 취급됨에 따라, 설정 파싱을 위해 클라이언트 클래스패스에 `profile-model`이 추가되었다. `ClientBoundaryTest`를 통해 클라이언트 계층이 기종 전용 형식을 직접 참조하지 않도록 경계를 통제한다.

**`gate`와 `contracts`의 의존성 분리**: `gate`는 `contracts`에 직접 빌드 의존하지 않으며, `buf build` 및 Gradle protoc 태스크가 생성한 `FileDescriptorSet` 바이너리 바이트(`Resource.CONTRACT_DESCRIPTOR`, `picasso.desc`)를 런타임 리소스로 수신한다. 태스크 실행 순서는 Gradle 의존성(`:gate:test` depends on `:contracts:generateProto`)으로 보장되며, 클래스패스 오염 없는 순수 검증을 유지한다.

**런타임 통신 경로 (HTTP/MQTT. 독립 기동 지원):**

| 방향 | 내용 | 비가용 시 거동 |
|---|---|---|
| `mimic` ⇢ `registry` | 프로파일 로드 및 변경 폴링 (§10.2, §10.3) | 로컬 파일 모드로 자립 동작 |
| `mimic` ⇢ `registry` | 핸드셰이크 결과 보고 (§5.4) | 로컬 파일 기록 후 정상 진행 |
| `mimic` ⇢ 브로커 | 상태·이벤트·연결 발행 | 브로커 버퍼링 또는 재시도 |
| `adapter-host` ⇢ `registry` | 핸드셰이크 결과, 하트비트, 태스크 관측 적재 (`uplink` 공통 결선) | 발행만 수행하고 무적재 동작 |
| `adapter-host` ⇢ 브로커 | 상태·이벤트·연결 발행 (토픽, 헤더, 시퀀스 규격 통일) | 브로커 버퍼링 또는 재시도 |
| `registry` ⇠ 브로커 | 이벤트 구독 — 능력 변경, 태스크 상태 전이 적재, 결함 | 관측 테이블 누락 및 드레인 판정 불가 |
| `registry` ⇢ 브로커 | 사이트 단위 능력 카탈로그 스트림 발행 (§9.6) | 상위 시스템이 폴링 엔드포인트로 대체 |
| `harness` ⇢ `registry` | 시험 요청 폴링 및 결과 보고 (§8.4 ②) | 로컬 직접 실행 모드로 대체 |
| `client` ⇢ `mimic` | gRPC 명령/질의 및 MQTT 상태 구독 | 통신 실패 처리 |
| `client` ⇢ `registry` | 요구 등록 (§9.2) 및 클라이언트 측 결함 보고 (§6.2) | 원장 누락 (변경 영향 계산 유예) |

**순환 방지 아키텍처 규칙:**
1. **`registry`는 `mimic`과 `harness`를 직접 호출하지 않는다.** 시험 요청은 `revision_test_request` 레코드로 적재되며, `harness`가 비동기 폴링으로 작업을 인출하여 실행한 후 결과를 보고한다.
2. **`registry`는 하위 모듈에 직접 푸시하지 않는다.** 갱신 사항은 각 기체가 능동적으로 폴링하며, 관측 데이터는 브로커 토픽 구독을 통해 비동기 수신한다.

### 3.3 모듈별 핵심 책임

- **`contracts/`**: 런타임 로봇 인터페이스의 메시지 스키마 및 헤더 규격, 계약 식별자(`contract_digest`, `contract_semver`)를 정의한다. 구체적 수치 제약이나 비즈니스 로직은 배제한다.
- **`profile/`**: 기종별 하드웨어 사양 및 제약 조건을 표현하는 선언 형식과 실제 기종 프로파일 문서를 보관한다.
- **`capability/`**: 프로파일의 `Capability` 투영 및 클라이언트 요구 사양과의 협상 판정을 수행한다. 전송 계층과 완전히 독립적이다.
- **`uplink/`**: 발신 기체의 상향 인터페이스를 통합하여 브로커 메시지 발행 및 레지스트리 비동기 적재를 처리한다.
- **`registry/`**: 개정판 및 어댑터 생명주기 관리, 바인딩 매핑, 의존 원장, 변경 계획 실행 엔진, 사이트 카탈로그 및 진단 API를 제공한다.
- **`mimic/`**: 프로파일을 동적으로 해석하여 표준 계약을 구현하는 가상화 에뮬레이터 서비스를 제공한다.
- **`client/`**: 표준 계약을 구동하여 완료 기준을 실증하는 경량 클라이언트 라이브러리다.
- **`picasso/`**: 미들웨어 코어 엔진으로서 정준 모델, 실행 상태머신, 증적 결합, 작업 취소 및 결과 처리를 총괄한다.
- **`adapter-core/`**: 기종 독립적인 공통 어댑터 인터페이스(`RobotAdapter`) 및 표준 어휘를 정의한다.
- **`adapter-host/`**: 임의의 어댑터 인스턴스를 표준 gRPC 계약 서버로 호스팅하는 런타임 셸이다.
- **`gate/`**: CI 파이프라인 및 레지스트리 등록 시 공통으로 사용되는 단일 검증 라이브러리다.
- **`harness/`**: 가상화 환경(`mimic`) 및 클라이언트를 조율하여 자동화된 계약 검증 스위트를 실행한다.

### 3.4 구현 기술 스택

설계노트 §4.9를 준수한다. 전체 모듈은 **Kotlin** 기반으로 구현되며 Spring Boot 프레임워크를 활용하되, 도메인 핵심 로직은 프레임워크 비종속적으로 설계한다. Spring MVC + Virtual Threads 조합을 채택하며 WebFlux 리액티브 스택은 배제한다.

`mimic` 또한 Kotlin으로 구현하여 sealed class 및 exhaustive `when` 패턴 매칭을 통해 스킬 상태머신과 실패 분류 처리를 컴파일 타임에 철저히 검증한다.

영속화 저장소로는 **PostgreSQL**(JSONB 지원)을 표준으로 사용한다.

### 3.5 전송 계층 경계

| 전송 프로토콜 | 담당 영역 |
|---|---|
| **gRPC** | 동기식 명령 및 질의 — RPC 전체, `Negotiate`, `GetSnapshot`, `GetCapabilities` |
| **MQTT** | 비동기 발행 — 로봇 상태, 이벤트 스트림, 연결 상태 라이프사이클 |

§4.7에 정의된 모든 이벤트는 MQTT `event` 토픽으로 브로드캐스트된다. 태스크 상태 전이 또한 이벤트 스트림에 포함되며, `WatchTask` 스트림은 이에 더해 미세 진행률 및 부분 결과를 요청 클라이언트에 독점 스트리밍한다. 따라서 제3의 관측자는 `event` 스트림과 스냅샷 결합만으로 전체 상태를 재구성할 수 있다.

**이중 시퀀스 카운터 분리:**

| 카운터 | 식별 범위 | 주요 목적 |
|---|---|---|
| `sequence` | **단일 기체 단위**, 세션 내 단조 증가 | MQTT `state` 및 `event` 스트림의 결손 감지 및 순서 재정렬 |
| `update_index` | **단일 태스크 단위**, 0부터 단조 증가 | gRPC `WatchTask` 스트림의 재접속 시 스트리밍 재개 지점 식별 |

카운터를 분리함으로써 gRPC 스트림 전용 메시지가 MQTT 소비자의 결손 감지 로직에 오탐을 유발하는 문제를 근본적으로 차단한다.

토픽 스트림 유형(`{stream}`)은 `state`, `event`, `connection` 3종으로 엄격히 제한된다.

**순서 및 재정렬 보장**: MQTT 프로토콜 자체는 파티션 기반 전역 순서를 보장하지 않으므로, 기체별 전용 토픽과 `sequence` 번호를 통해 수신 측에서 계약 기반 재정렬을 수행한다. 소비자는 불연속 시퀀스 감지 시 **재정렬 윈도우**(기본값: 후속 이벤트 8건 수신 또는 2초 경과 중 선착) 동안 판정을 유예하여 네트워크 지터에 대응한다.

## 4. 계약 (`contracts/`)

### 4.1 파일 구성 및 의존성

| 파일명 | 정의 대상 | 백로그 연계 |
|---|---|---|
| `common.proto` | **공통 헤더 규격**, `Reference`, `Lifetime`, `ProfileRef`, `Support`, `Resolution`, `RejectionCode`, `Rejection`. 외부 의존성 없음 | — |
| `fault.proto` | 결함 상태 및 진단 모델 | A-2 |
| `skill.proto` | 스킬 상태머신, `Capability`, `GetCapabilities`, `Negotiate` | A-1 |
| `skill_catalog.proto` | **계약 소유의 표준 스킬 어휘 사전.** `since_minor`, `is_optional`, `skill_type_max_minor`, 파라미터 네임스페이스 식별용 `is_site_reference`(위치) 및 `is_object_reference`(대상) 커스텀 옵션 정의 (§15.78) | A-1 |
| `task.proto` | 장기 실행 태스크 제어 RPC 및 상태 수명주기 | A-4 |
| `event.proto` | 이벤트 모델, `StateMessage`, `GetSnapshot`, `ReplayEvents`, `CapabilityChanged`, 연결 수명주기 | A-2 |

공통 헤더를 `common.proto`로 격리한 것은 protobuf import 간의 순환 참조를 방지하기 위함이다. 헤더가 특정 도메인 메시지와 결합될 경우 전체 프로토콜 계층에 걸쳐 컴파일 순환이 발생한다.

`skill_catalog.proto`의 분리는 §5.2의 스킬 어휘 진화 규칙(Major별 독립 정의 및 Minor 도입 버전 추적)과 §8.3의 읽기 전용 스키마 투영 요구사항을 충족하기 위한 설계다. 스킬 어휘를 Protobuf에 정형화함으로써 `buf breaking` 도구를 통해 파라미터의 무단 삭제나 타입 변조를 컴파일 단계에서 차단한다.

`max_minor`와 `optional`은 유도 계산이 아닌 명시적 선언 방식을 취한다. 파라미터 추가 없는 Minor 개정판의 발행이나, 최초 버전부터 선택적인 파라미터의 존재성을 정밀하게 표현하기 위해 전용 옵션을 부여한다.

### 4.2 스킬 상태머신

OPC UA 기반 Fortiss / VDMA·OPC Foundation SOArc 스킬 상태 모델의 구조를 언어 중립적 사양으로 이식하였다.

| 상태 | 정의 |
|---|---|
| `READY` | 실행 대기 상태. 초기 기본 상태이자 정상 완료 후 복귀 상태 |
| `RUNNING` | 스킬 동작 실행 중 |
| `SUSPENDED` | 일시정지 상태 |
| `HALTED` | 비정상 중단 상태. 재기동을 위해서는 `Reset` 전이가 필수 |

| 전이 | 시작 상태 | 전이 상태 | 유발 조건 |
|---|---|---|---|
| `Start` | `READY` | `RUNNING` | `StartTask` 또는 `RetryTask` (실행 파라미터 전달) |
| `Suspend` | `RUNNING` | `SUSPENDED` | `PauseTask` (`pause_support ≠ NO`인 스킬에 한함) |
| `Resume` | `SUSPENDED` | `RUNNING` | `ResumeTask` |
| `Halt` | `RUNNING`, `SUSPENDED` | `HALTED` | 취소 확정, 또는 `can_continue_current_task=false` 결함 발생 |
| `Complete` | `RUNNING` | `READY` | 정상 완료 |
| `Reset` | `HALTED` | `READY` | **엔진 내부 자동 전이.** 태스크 종착 진입, `RETRIABLE` 전이, 갱신에 따른 스킬 재시작 시 자동 수행 (§4.4) |

상기 정의 외의 모든 상태 전이는 엄격히 거부되며 `INVALID_TRANSITION` 오류를 반환한다. 이는 Kotlin sealed class와 exhaustive `when` 매칭에 의해 컴파일 타임 및 런타임에 이중 보장된다.

`Reset`을 외부 RPC로 노출하지 않는 것은 시스템의 상위 정책을 계약에 침투시키지 않기 위함이다. 리셋의 인가 권한은 미션 제어 계층의 책임이며 본 계약의 비목표다.

파라미터 전달과 실행 트리거는 단일 RPC 호출로 원자적으로 결합하여 동시성 경쟁 조건을 방지한다.

### 4.3 값 어휘 체계

| 어휘 | 정의 및 허용 값 |
|---|---|
| `SkillState` | `READY`, `RUNNING`, `SUSPENDED`, `HALTED` |
| `TaskState` | §4.4 수명주기 상태 참조 |
| `ConnectionState` | `ONLINE`, `OFFLINE`, `HIBERNATING`, `CONNECTION_BROKEN` |
| `ValueType` | `BOOL`, `INTEGER`, `NUMBER`, `STRING`, `ENUM` |
| `Reference` | `{key, value}` 형태의 구조체 (`key` ∈ `task_id`, `skill_id`, `robot_id`, `parameter_key`) |
| `Lifetime` | `UNTIL_CLEARED`, `UNTIL_NEW_TASK`, `UNTIL(timestamp)` |
| `Support` | `YES`, `NO`, `UNKNOWN` (실물 제약 반영 3값 모델) |
| `Resolution` | `SELF_RETRIABLE`, `NEEDS_INTERVENTION`, `TERMINAL` (결함 해결 절차 분류) |
| `RejectionCode` | 하위 거절 코드 체계 참조 |

Protobuf wire 전송 시에는 `ENUM_VALUE_PREFIX` 규칙에 따라 접두사가 부여되며, 미지정 기본값 `_UNSPECIFIED`가 0번에 배치된다 (예: `CONNECTION_STATE_CONNECTION_BROKEN`, `SUPPORT_YES`).

`RejectionCode`는 인터페이스 표면별로 유형화된다:

| 인터페이스 표면 | 거절 코드 |
|---|---|
| `Negotiate` (5개) | `MAJOR_MISMATCH`, `SKILL_ABSENT`, `REQUIRED_OPTIONAL_MISSING`, `LIMIT_EXCEEDED`, `IDENTITY_MISMATCH` |
| 태스크 RPC (9개) | `CAPABILITY_WITHDRAWN`, `CANCEL_UNSUPPORTED`, `PAUSE_UNSUPPORTED`, `OUTDATED_REVISION`, `INVALID_TRANSITION`, `SKILL_ABSENT`, `IDENTITY_MISMATCH`, `PARAMETER_INVALID`, `UPDATE_UNSUPPORTED` (0.7.0 반영, §15.109) |
| `ReplayEvents` (1개) | `SEQUENCE_EVICTED` |

`CAPABILITY_WITHDRAWN`은 핸드셰이크 이후 런타임에 능력이 상실된 스킬에 대해 `StartTask`가 호출되었을 때 반환되며, 응답에 최신 `capability_epoch`를 동반하여 소비자가 캐시를 갱신하도록 유도한다. 반면 최초부터 선언되지 않은 스킬의 호출은 `SKILL_ABSENT`로 구분된다.

`PARAMETER_INVALID`는 파라미터가 프로파일 사양을 위반한 경우 발생한다(미인식 코어 키의 fail-closed 처리, 필수 파라미터 누락, 값 범위 및 허용치 초과 등).

**판정 위치 원칙**: 요청 자체를 해석할 수 없는 경우 gRPC 전송 계층 상태 코드(`INVALID_ARGUMENT`, `NOT_FOUND` 등)로 응답하며, 요청은 유효하지만 비즈니스 규칙에 의해 거절된 경우 응답 본문의 `Rejection` 메시지로 명시적 반환한다. 유일한 예외는 `GetCapabilities` 응답의 구조적 한계로 인한 `INVALID_ARGUMENT` 반환뿐이다.

**`error_type` 명명 규칙**: `SCREAMING_SNAKE_CASE` 포맷을 따르며, 표준 코어 값은 계약이 소유하고 벤더 확장은 `X_<VENDOR>_` 접두사를 의무적으로 사용한다. 표준 코어 8종:

| `error_type` | 원인 및 정의 |
|---|---|
| `LOCALIZATION_LOST` | 로봇 위치 추정(Localization) 실패 |
| `PAYLOAD_LOST` | 이송 중인 물리 화물(Payload) 이탈 또는 분실 |
| `SKILL_EXECUTION_FAILED` | 스킬 내부 로직 수행 중 오류 발생 |
| `PARAMETER_OUT_OF_RANGE` | 프로파일 선언 제약 범위를 벗어난 파라미터 전달 |
| `CONTRACT_REVISION_MISMATCH` | 계약 개정판 불일치 경보 (§5.5) |
| `INTERNAL_ERROR` | 기타 내부 런타임 결함 |
| **`TERMINAL_STATE_VIOLATED`** | **종착 확정 후 기체의 무단 재기동 감지 (래치 불변식 위반)** |
| **`CONTROL_AUTHORITY_LOST`** | **제어권이 외부 클라이언트에 의해 탈취됨 (§4.9)** |

뒤의 2종은 프로파일에 정적으로 선언되는 실패 모드가 아니며 어댑터가 런타임에 발행하는 전용 상태이다.

### 4.4 장기 실행 태스크

```
StartTask(TaskRequest)                   -> TaskHandle    // 접수 응답 (비종착)
WatchTask(TaskHandle, from_update_index) -> stream TaskUpdate
RetryTask(TaskHandle)                    -> Ack           // RETRIABLE 상태에서만 인가
PauseTask(TaskHandle)                    -> Ack           // pause_support=NO 시 PAUSE_UNSUPPORTED
ResumeTask(TaskHandle)                   -> Ack
CancelTask(CancelRequest)                -> CancelAck     // cancel_support=NO 시 CANCEL_UNSUPPORTED
GetSnapshot(robot_id)                    -> Snapshot      // 현재 기체 상태 및 당시 sequence
ReplayEvents(robot_id, from_sequence)    -> stream Event  // 재생 버퍼 기반 이벤트 재전송
GetCapabilities(robot_id)                -> Capability    // 유효 능력 투영 스키마 (§7.3)
```

| 상태 | 종착 여부 | 정의 |
|---|---|---|
| `ACCEPTED` | 아니오 | 작업 접수 완료, 실행 대기 |
| `RUNNING` | 아니오 | 스킬 실행 진행 중 |
| `PAUSED` | 아니오 | `PauseTask`에 의해 일시정지됨 |
| `CANCELLING` | 아니오 | 취소 요청 접수 후 물리적 안전 복구 진행 중 |
| `RETRIABLE` | 아니오 | 실행 실패했으나 로봇 자율 재시도가 가능한 상태 |
| `NEEDS_INTERVENTION` | 아니오 | 실행 실패했으며 작업자 개입이 필수적인 상태 |
| `SUCCEEDED` | **예** | 작업 정상 완결 |
| `FAILED` | **예** | 회복 불가능한 최종 실패 |
| `CANCELLED` | **예** | 취소 요청에 따른 물리 복구가 정상 완료됨 |
| `CANCELLED_RECOVERY_FAILED` | **예** | 취소 절차 중 물리 복구에 최종 실패함 |

취소 절차는 즉각적 완료가 아니며 반드시 물리적 복구 시퀀스를 동반한다. `CancelTask` 요청 시 태스크는 `CANCELLING` 상태로 진입하여 하드웨어를 안전한 상태(예: 휴머노이드의 화물 안전 거치)로 전이시킨다. 복구 완료 시 `CANCELLED`, 복구 실패 시 `CANCELLED_RECOVERY_FAILED`로 종착한다.

**잔여 물리 상태 (`WatchTaskResponse.hold`, v0.4.0)**: 로봇의 물리적 파지 상태를 `HoldState{kind, object_ref, reason}`로 명시한다. `kind`는 4가지 상태를 가진다: `UNSPECIFIED`, `NOT_OBSERVABLE`, `EMPTY`, `HOLDING`.
- 불변식 1: `CANCELLED` ⇒ `kind ≠ HOLDING` (정상 취소는 빈손 상태를 요구한다).
- 불변식 2: `CANCELLED_RECOVERY_FAILED` ⇏ `HOLDING` (화물 낙하 분실로 인한 실패 시 손은 빈 상태일 수 있다).

**`CancelTask`는 비종착 6개 상태 전역에서 유효하다.** 특히 `RETRIABLE` 및 `NEEDS_INTERVENTION` 상태에서도 취소가 가능해야 작업 포기 태스크가 시스템에 영구 체류하여 축소 드레인(Drain)을 영구 차단하는 문제를 방지할 수 있다.

`RetryTask`는 `RETRIABLE` 및 `NEEDS_INTERVENTION` 상태에서만 인가되며, 스킬을 동일 파라미터로 재기동하고 시도 횟수(`attempt`)를 증가시킨다. 자동 재시도와 작업자 수동 개입의 분리는 불필요한 자원 낭비를 차단하는 핵심 기준이다.

**터미널 상태의 영구 래치(Latch) 불변식**: 종착 상태에 도달한 태스크는 어떤 상황에서도 비종착 상태로 역전될 수 없다. 비래치 기종(예: Agility Digit)의 경우 어댑터 계층에서 래치 책임을 전담하며, 기체의 부적절한 추가 상태 보고는 `TERMINAL_STATE_VIOLATED` 결함으로 감지하여 시스템 무결성을 방어한다.

진행률은 0.0부터 1.0까지의 부동소수점이며, `progress_basis`(`ProgressBasis{kind, basis, reason}`)를 동반하여 단순 미측정 상태와 정체 상태를 엄격히 구분한다. 단조 비감소 불변식은 동일 `(task_id, revision, attempt)` 구간 내에서만 유지된다.

**멱등성 키: `(task_id, revision)` 단조쌍**:

| 수신된 revision | 처리 정책 |
|---|---|
| 신규 `task_id` | 신규 태스크 핸들 발급 및 접수 |
| 현재와 동일 | 기존 핸들 반환 (네트워크 재전송에 대한 멱등성 보장) |
| 현재보다 낮음 | `OUTDATED_REVISION` 거절 |
| 현재보다 높음 | 런타임 동적 파라미터 갱신 수행 |

**파라미터 갱신 규칙**: 실행 중인 태스크에 대한 파라미터 변경 시 태스크 자체를 재기동하지 않고 상태별 정밀 전이를 수행한다 (`RUNNING` 상태의 경우 스킬 `Halt` → `Reset` → 새 파라미터 `Start`, 불가 시 `UPDATE_UNSUPPORTED` 반환). 갱신은 태스크 개정판 고정(Pinning)을 변경하지 않는다.

### 4.5 두 상태머신의 관계

태스크는 순차적 스킬 호출의 상위 수명주기이며, 스킬 FSM은 현재 활성화된 하위 실행 상태를 대변한다.

| 태스크 상태 | 현재 스킬 FSM 상태 |
|---|---|
| `ACCEPTED` | 스킬 미할당 |
| `RUNNING` | `RUNNING` |
| `PAUSED` | `SUSPENDED` |
| `CANCELLING` | `RUNNING` 또는 `SUSPENDED` (복구 동작 진행 중) |
| `RETRIABLE`, `NEEDS_INTERVENTION` | `HALTED` → 자동 `Reset` → `READY` (`RetryTask` 대기) |
| `FAILED`, `CANCELLED`, `CANCELLED_RECOVERY_FAILED` | `HALTED` → 자동 `Reset` → `READY` |
| `SUCCEEDED` | `READY` (`Complete` 완료 후) |

**전파 규칙 3대 원칙**:
1. 스킬 `Halt` 발생 시 태스크 종착 상태는 결함의 `Resolution`에 의해 결정된다 (`SELF_RETRIABLE` → `RETRIABLE`, `NEEDS_INTERVENTION` → `NEEDS_INTERVENTION`, `TERMINAL` → `FAILED`).
2. 복합 단계 태스크에서 특정 하위 스킬의 `Halt`는 태스크 전체의 종착으로 전파된다.
3. `CANCELLING` 진행 중 발생하는 스킬 `Halt`는 물리 복구 실패로 간주되어 `CANCELLED_RECOVERY_FAILED`로 확정된다.

### 4.6 결함 모델

처방적 3분류(재시도/이관/개입)를 계약에서 배제하고, 잔여 하드웨어 역량을 직접 기술하는 객관적 모델을 구축한다.

```protobuf
message Fault {
  string   error_type                 = 1; // §4.3 표준 명명 규칙
  bool     can_continue_current_task  = 2;
  bool     can_accept_new_task        = 3;
  repeated Reference references       = 4;
  string   error_hint                 = 5; // 작업자 조치 안내
  Lifetime active_until               = 6;
  FailureClass failure_class          = 7; // 정준 실패 분류 (상위 라우팅 기준)
  string   vendor_detail              = 8; // 벤더 원시 진단 문자열
}
```

잔여 역량 불리언 2종을 통해 상위 오케스트레이터가 결정론적으로 후속 조치를 도출할 수 있도록 지원하며, `references`에 특정 `skill_id`를 명시하여 기체 전체가 아닌 개별 스킬 단위의 결함을 표현한다.

### 4.7 상태와 이벤트

상태(현재 스냅샷)와 이벤트(상태 전이 사실)를 분리하여 독립 발행한다.

발행 이벤트 4종:
- `SKILL_TRANSITION`: `{skill_type, from, to, task_id?}`
- `TASK_TRANSITION`: `{task_id, skill_type, from, to, revision, attempt}` (레지스트리의 `task` 테이블 적재 및 드레인 판정에 필수)
- `FAULT_RAISED` / `FAULT_CLEARED`: 결함 발생 및 해소
- `CAPABILITY_CHANGED`: `{robot_id, capability_epoch, added[], removed[], cause}`

연결 상태 스트림 (MQTT Retain 및 Last Will 활용):
- `ONLINE`: 연결 수립 및 정상 통신 중
- `OFFLINE`: 정상적인 셧다운 종료
- `HIBERNATING`: **연결을 유지하되 전력 절감 등을 위해 상태 발행을 의도적으로 중단한 상태**
- `CONNECTION_BROKEN`: 비정상 통신 단절 감지

### 4.8 세션, 시퀀스, 재생 버퍼

모든 통신 단위는 기체 식별자(`robot_id`)를 기준으로 격리된다.
- `session_id`(ULID)는 기체 온라인 연결 시 신규 발급되며, `sequence`는 세션 내에서 0부터 단조 증가한다.
- 세션 전환 시 소비자는 기존 시퀀스를 폐기하고 `GetSnapshot`을 통해 상태를 재동기화한다.
- 발신 기체는 최근 N개의 이벤트를 인메모리 순환 버퍼에 보관하며, 범위 초과 요청 시 `SEQUENCE_EVICTED`로 응답한다.

### 4.9 제어 권한 상실 (CONTROL_AUTHORITY_LOST)

실물 모바일 로봇은 예외 없이 단일 클라이언트에 대한 배타적 제어권을 요구한다. 제어권의 정책적 협상 및 스케줄링은 미션 계층의 비목표이지만, **권한 상실이라는 물리적 사건은 태스크의 실패를 직접 유발하므로 계약 내부로 수용**한다.

제어권 상실 감지 시 `CONTROL_AUTHORITY_LOST` 결함(`can_continue_current_task=false`, `can_accept_new_task=false`)을 발행하여 작업 실패가 로봇 하드웨어 고장과 혼동되지 않도록 명확한 관측성을 제공한다.

## 5. 능력 호환성

### 5.1 문제 정의

능력은 고정된 속성이 아닌 `(제조사 × 모델 × 펌웨어 버전)`의 동적 함수이며, 능력의 확장 및 정밀화 과정에서 하위 호환성을 유지하기 위한 엄격한 버전 규율이 요구된다.

### 5.2 동일성 규율

식별자는 `(skill_type, major.minor)` 구조를 취하며, 기능적 호환성은 Major 버전이 보장한다.
- **Minor 증가**: 하위 호환 가능한 선택적 파라미터 추가에 한정. 기존 클라이언트는 수정 없이 동작 보장.
- **Major 증가**: 필수 파라미터 추가, 기존 파라미터의 의미·단위 변경, 성공/실패 판정 기준의 근본적 변경.
- **파라미터 키의 불변성**: 파라미터의 정의가 변경될 경우 기존 키를 폐기하고 신규 키를 신설한다.

### 5.3 파라미터 키 설계

| 유형 | 예시 | 미인식 키 수신 시 거동 |
|---|---|---|
| 코어 파라미터 | `grip_force` | **실패 반환** (fail-closed) |
| 벤더 확장 파라미터 | `x-<vendor>.<key>` | **무시 후 정상 진행** (fail-open) |
| 필수 요구 선택 필드 | `{parameter, support: REQUIRED}` | 핸드셰이크 단계에서 사전 차단 |

### 5.4 핸드셰이크 메커니즘

```protobuf
message CapabilityRequirement {
  string client_id               = 1;
  string robot_id                = 2;
  repeated string requirements   = 3; // 예: "pick_place@^1.2"
  repeated string optional_fields_used = 4;
  ProtocolLimits limits_needed   = 5;
}
```

핸드셰이크(`Negotiate`)를 통해 클라이언트 요구 사양과 로봇의 선언 능력을 대조하여 호환성을 검증한다. 거절 시 `ExplainedBoolean` 패턴에 따라 명확한 `RejectionCode`를 반환한다. 핸드셰이크 성공 데이터는 레지스트리의 의존 원장에 실시간 적재된다.

### 5.5 경로 및 헤더 체계

토픽 경로 포맷:
```
기체 스트림:  picasso/{major}/{site}/robot/{robot_id}/{stream}
             {stream} ∈ state | event | connection
사이트 스트림: picasso/{major}/{site}/site/catalog
```

메시지 헤더 전송 사양:

| 필드 | MQTT 발행 | gRPC 요청 | gRPC 응답 |
|---|---|---|---|
| `schema_id` | ● | ● | ● |
| `contract_digest` | ● | ● | ● |
| `contract_semver` | ● | ● | ● |
| `robot_id` | ● | ● | ● |
| `capability_epoch` | ● | — | ● |
| `sequence` | ● | — | — |
| `session_id` | ● | — | ● |
| `update_index` | — | — | ● (`WatchTask`) |
| `profile_ref {id, revision}` | ● | — | ● |
| `event_id` / `occurred_at` / `state_as_of` | ● | — | ● |
| `client_id` | — | ● | — |

`contract_revision`은 Major 불일치 시 통신을 즉시 차단하고, 단순 패치 불일치 시에는 경보 이벤트를 발행한다. `capability_epoch`는 기체의 유효 능력이 변경될 때마다 증가하는 ETag 역할을 수행한다.

## 6. 검증 전략

### 6.1 보장 수준과 비용 분격

| 시점 | 검증 대상 | 수행 주기 및 비용 | 한계 |
|---|---|---|---|
| 빌드 타임 | 정적 구조 (필드, 타입, 태그 번호) | CI 파이프라인 1회 수행 | 런타임 의미 검증 불가 |
| 핸드셰이크 타임 | 의미적 호환성 (스킬 버전, 필수 파라미터) | 세션 연결 시 1회 수행 | 기체의 정직한 선언에 의존 |
| 메시지 전송 타임 | 런타임 정합성 (개정판 일치, 시퀀스 단조성, 에포크) | 매 메시지마다 O(1) 검증 | 페이로드 심층 검증 불가 |

검증 비용을 런타임으로 전가하지 않고 상위 단계에서 선제 차단하는 것이 아키텍처 원칙이다.

### 6.2 검증 수행 체계

| 검증 단계 | 검사 대상 | 실패 시 조치 |
|---|---|---|
| CI 게이트 | §11.2 9개 검사 규칙 | PR 머지 차단 |
| 레지스트리 등록 | 프로파일 JSON Schema, proto 교차검증, 어휘 파괴 검증 | `VALIDATED` 상태 진입 거부 |
| 핸드셰이크 | 클라이언트 요구 사양과 선언 능력 대조 | 연결 거부 및 사유 반환 |
| 메시지 수신 | 토픽 Major 버전, 시퀀스 단조성, 세션 일치 | 패킷 드롭 및 결함 기록 |
| 송신 직전 | 프로파일 기반 파라미터 제약 검증 | 발행 차단 |

검증 플래그는 `validate.inbound` 및 `validate.outbound`로 독립 분리되며, `mimic` 가상화 환경에서는 두 플래그 모두 `1.0`(100% 전수 검증)으로 고정된다.

### 6.3 보안 및 접근 전제

- 레지스트리 API는 내부 신뢰 네트워크 환경을 전제하며, 호출 주체는 헤더의 `actor` 필드로 식별한다 (부인 방지 감사 로그는 범위 외).
- `mimic`의 원격 제어 채널(§10.5)은 루프백 인터페이스(`127.0.0.1`)에만 바인딩된다.
- 현장 통신 암호화 및 TLS 인증은 배포 인프라스트럭처의 책임으로 분리한다.

## 7. 능력 프로파일 (`profile/`)

### 7.1 JSON Schema 채택 사유

프로파일이 다루는 핵심은 단순한 메시지 구조가 아닌 **물리적 제약 조건**(값 허용 범위, ENUM 열거셋, 최대 문자열 길이, 발행 주기 등)이다. Protocol Buffers는 데이터 구조 정의 언어이므로 제약 조건을 표현하려면 커스텀 옵션이나 주석에 의존해야 하며, 이는 검증 로직이 애플리케이션 코드로 누출되는 결과를 초래한다. JSON Schema를 채택함으로써 선언적 제약 검증을 분리하고, 스키마와 Protobuf 간의 정합성은 §11.2 검사 4의 양방향 교차 검증 게이트로 엄격히 관리한다.

### 7.2 프로파일 명세 구조

VDA5050 `factsheet.schema`의 계층 구조를 참조하여 설계하였다. 소비자의 의사결정에 필요한 **공개 투영 항목**과 발신 기체 내부에서만 소비되는 **내부 설정 항목**을 엄격히 분리한다.

**공개 투영 항목 (`Capability` 메시지로 노출):**

| 항목 | 상세 내용 |
|---|---|
| 기종 식별 좌표 | `vendor`, `model`, `revision` |
| 지원 스킬 목록 | 스킬 타입 및 `major.minor` 버전 |
| 스킬 제어 플래그 | `pause_support`, `cancel_support` — **`Support` 3값 체계** (`YES` / `NO` / `UNKNOWN`), 필수 선언 |
| 배타 제어 요구 여부 | `exclusive_control_required` — 배타적 단일 제어권 필요 여부 (§4.9) |
| 파라미터 제약 선언 | 키 식별자, `ValueType`, 필수 여부, 수치 범위 및 단위, ENUM 허용 목록, 최대 문자열 길이 |
| 필수 요구 선택 필드 | `{parameter: 점표기 경로, support: SUPPORTED\|REQUIRED}` |
| 상태 발행 주기 규격 | 최소 및 최대 발행 주기 (기본 최대 30초, 소비자의 통신 신선도 판정 기준) |
| 프로토콜 제약 한계 | 문자열 및 배열의 최대 허용 길이 (`protocolLimits`) |
| 폐기 예고 정보 | 스킬별 `deprecated_after` 선언 (운영 변경 예고 단계에서 활용) |

**발신 기체 내부 설정 항목 (외부 비투영):**

| 항목 | 격리 사유 |
|---|---|
| `schema_version` | 프로파일 문서 자체의 메타데이터이며 기체의 능력 속성이 아님 |
| 동작 시간 상수 및 지터 | 가상화 환경의 진행률 계산용 파라미터이며 소비자는 실제 진행률만을 관측함 |
| 실패 모드 및 발생 확률, Resolution | 가상화 시뮬레이션용 수치이며 실제 런타임 결함은 `Fault` 이벤트로 객관화됨 |
| 이벤트 재생 버퍼 크기 N | 발신 기체 내부의 자원 할당 파라미터 |

`Capability` 메시지는 상기 투영 항목만을 정확히 변환하여 전달하며, §12.2의 투영 일치 테스트를 통해 구현의 임의 하드코딩 여부를 검증한다.

### 7.3 계약과의 상호 검증 관계

프로파일은 정적 저작 형식이며 `Capability`는 런타임 투영 모델이다. 프로파일은 계약(Protobuf)에 정의되지 않은 스킬 타입이나 파라미터를 임의로 확장할 수 없으며(참조 무결성), 계약에 정의된 필수 파라미터를 누락해서도 안 된다.

### 7.4 정의 대상 프로파일 목록

초기 가상 기종 2종에서 출발하여 현재 총 **6종의 기종 프로파일**이 정의되어 있다:
- 가상 참조 기종 3종: `humanoid-a`, `quadruped-b`, `quadruped-c` (`vendor: picasso-ref`)
- 실물 벤더 파생 기종 3종: `agility-digit`, `spot-arm`, `unitree-g1` (공식 1차 사양 분석 기반 파생, ADR 31 연계)

| 기종 간 차이 요인 | 검증 목적 |
|---|---|
| 공통 스킬 (`major.minor` 일치) | 동일한 클라이언트 코드로 이기종 로봇 제어 검증 |
| 특정 기종 전용 스킬 | 미지원 스킬 호출 시 `SKILL_ABSENT` 거절 검증 |
| 스킬의 Minor 버전 차이 | 신규 선택 파라미터를 인지하지 못하는 구형 클라이언트 호환성 검증 |
| `cancel_support` 차이 (`YES` vs `NO`) | 취소 미지원 기종에 대한 `CANCEL_UNSUPPORTED` 거절 검증 |
| `pause_support` 차이 (`YES` vs `UNKNOWN`) | `UNKNOWN` 상태에서의 일시정지 시도 및 기체 자율 거절 처리 검증 |
| 프로토콜 제약 한계 차이 | 버퍼 초과 요청에 대한 `LIMIT_EXCEEDED` 거절 검증 |
| 필수 선택 필드 선언 차이 | 필수 옵션 누락 시 `REQUIRED_OPTIONAL_MISSING` 거절 검증 |

상기 7가지 이종성이 코드 수정 없이 순수 데이터 프로파일 교체만으로 시스템에 완전히 수용된다.

## 8. 레지스트리와 저장 체계 (`registry/`)

### 8.1 데이터와 코드의 경계 기준

설계노트 §4.5의 원칙을 적용한다: **단일 값의 유효성을 다른 문맥 참조 없이 판정할 수 있다면 데이터, 복합적인 순서·조건 분기·실행 결과를 평가해야 한다면 코드로 분류한다.**

| 런타임 데이터 영역 (DB) | 배포 코드 영역 (바이너리) |
|---|---|
| 능력 프로파일 정의 문서 | 계약 Protobuf 사양 |
| 기체·어댑터 등록 및 바인딩 매핑 | 스킬 및 태스크 상태머신 해석 엔진 |
| 의존 원장 및 변경 계획서 | 조건 평가, 파급 계산, 파이프라인 엔진 |

### 8.2 권한 소재 및 상태 채번

능력 축소를 유발하는 2가지 이벤트:

| 이벤트 | 발생 주체 | 전달 경로 | 원인 코드 (`cause`) |
|---|---|---|---|
| 기체 하드웨어 장애 (예: 그리퍼 고장) | 로봇/어댑터 | `CapabilityChanged` 발행 → 레지스트리 비동기 적재 | `RUNTIME_DEGRADED` |
| 운영자에 의한 능력 인위적 차단 | 운영자 | 레지스트리 API → 어댑터 폴링 반영 → `CapabilityChanged` 발행 | `OPERATOR_BLOCKED` |

`registry`는 `runtime_capability_override`의 유일한 승인 기록자 역할을 수행한다. 단, `capability_epoch`의 채번 주체는 발신 기체 자신이며, 레지스트리는 수신된 에포크를 관측 로그(`capability_epoch_log`)로 영속화한다. 이를 통해 레지스트리 일시 장애 시에도 기체 자체의 에포크 전이는 정상 유지된다.

### 8.3 관계형 데이터베이스 스키마 (PostgreSQL)

```sql
-- 계약 축 — proto 디스크립터에서 동기화되는 읽기 전용 뷰
skill_type(skill_type_id PK, name, major, max_minor,
           introduced_in_semver, contract_revision, synced_at,
           removed_from_contract BOOL DEFAULT false,
           UNIQUE(name, major))

skill_type_deprecation(skill_type_id PK FK, deprecated_after,
                       announced_by, announced_at, note)

skill_type_param(skill_type_id FK, key, value_type, optional, since_minor,
                  PK(skill_type_id, key))

-- 프로파일 축
capability_profile(profile_id PK, vendor, model, UNIQUE(vendor, model))

profile_revision(profile_revision_id PK, profile_id FK, revision INT,
                 document JSONB, document_hash, schema_version,
                 status, created_by, created_at,
                 activated_by NULL, activated_at NULL,
                 UNIQUE(profile_id, revision))

profile_skill(profile_revision_id FK, skill_type_id FK,
              minor, pause_support, cancel_support, deprecated_after NULL,
              PK(profile_revision_id, skill_type_id))

profile_skill_param(profile_revision_id, skill_type_id, key,
                    value_type, optional,
                    min_value, max_value, unit,
                    allowed_values JSONB, max_length,
                    PK(profile_revision_id, skill_type_id, key))

profile_optional_field(profile_revision_id, parameter_path,
                       support, PK(profile_revision_id, parameter_path))

-- 어댑터 축
adapter(adapter_id PK, vendor, name, UNIQUE(vendor, name))

adapter_version(adapter_version_id PK, adapter_id FK, version,
                contract_semver, conformance_status,
                registered_by, registered_at,
                UNIQUE(adapter_id, version))

-- 바인딩 축
robot(robot_id PK, site_id, serial_number, display_name,
      UNIQUE(site_id, serial_number))

robot_binding(robot_id FK, adapter_version_id FK, profile_revision_id FK,
              bound_at, unbound_at NULL, bound_by, reason)
CREATE UNIQUE INDEX ON robot_binding(robot_id) WHERE unbound_at IS NULL;

-- 시험 실행 축
revision_test_request(request_id PK, profile_revision_id FK,
                      requested_by, requested_at,
                      claimed_by NULL, claimed_at NULL, claim_expires_at NULL)

revision_test_run(run_id PK, profile_revision_id FK, request_id FK NULL,
                  suite, result, ran_by, ran_at, detail JSONB)

-- 관측 로그
capability_epoch_log(robot_id, epoch, cause, profile_ref JSONB,
                     detail JSONB, occurred_at)

runtime_capability_override(robot_id, skill_type_id,
                            state, cause, reason, occurred_at)

handshake_rejection(rejection_id PK, robot_id, client_id,
                    requirement JSONB, reason_code, detail JSONB, at)

-- 의존 원장
consumer(consumer_id PK, kind, site, display_name, registered BOOL, first_seen)

consumer_requirement(consumer_id FK, skill_type_name, source, version_range,
                     first_seen, last_seen, active,
                     PK(consumer_id, skill_type_name, source))

-- 변경 계획
change_plan(plan_id PK, intent, target JSONB, target_key, site,
            status, created_by, created_at, applied_at NULL)
CREATE UNIQUE INDEX ON change_plan(target_key, site)
  WHERE status NOT IN ('APPLIED','ABANDONED');

change_plan_step(plan_id FK, seq, kind, precondition JSONB,
                 satisfied BOOL, satisfied_at NULL,
                 PK(plan_id, seq))

task(task_id PK, robot_id FK, profile_revision_id FK,
     skill_type_id FK, revision INT, attempt INT,
     state, started_at, ended_at)

audit_log(actor, action, target_type, target_id, plan_id NULL,
          before JSONB, after JSONB, at)
```

**핵심 데이터 모델링 결정 6가지:**
1. **프로파일 개정판의 불변성**: `VALIDATED` 진입 이후 레코드는 절대 수정할 수 없다. 상태 전이는 `DRAFT → VALIDATED → TESTED → ACTIVE → SUPERSEDED / REVOKED` 흐름을 준수한다. 롤백은 과거 `SUPERSEDED` 개정판의 재활성화를 통해 수행된다.
2. **원본 JSONB와 정규화 테이블의 공존**: 진실의 원천(Single Source of Truth)은 원본 `document`이며, 정규화 테이블은 인덱싱 및 빠른 조회를 위한 투영 뷰다.
3. **런타임 능력 축소의 비침습성**: 프로파일 자체를 변조하지 않고 `runtime_capability_override` 오버레이를 적용하여 유효 능력(`Capability = Profile - Override`)을 동적 산출한다.
4. **계약 메타데이터의 읽기 전용 동기화**: `skill_catalog.proto`의 커스텀 옵션에서 스킬 메타데이터를 추출하여 초기 구동 시 upsert한다.
5. **어댑터와 프로파일 바인딩의 다대다 분리**: 어댑터 업그레이드와 프로파일 변경을 독립적인 차원으로 통제한다.
6. **의존 원장의 이중 소스 체계**: 선언적 등록(`DECLARED`)과 런타임 협상 관측(`OBSERVED`)을 복합 키로 관리하여 원장의 누락을 방지한다.

### 8.4 프로파일 개정판 런타임 갱신 절차

```
① 등록:   DRAFT 접수 → JSON Schema + proto 교차검증 + 어휘 파괴 검증 → VALIDATED
② 시험:   시험 요청 적재 → harness 폴링 인출 → 가상화 계약 검증 스위트 완주 → TESTED
③ 활성화: 바인딩 전환 승인 (카나리 배포 가능)
④ 반영:   기체가 레지스트리 폴링으로 바인딩 변경 감지 → capability_epoch 증가 및 CapabilityChanged 발행
⑤ 전파:   소비자가 헤더의 epoch 변경을 감지하고 능력 캐시 무효화 및 재동기화
⑥ 롤백:   필요 시 이전 SUPERSEDED 개정판 재활성화 (추가 시험 불필요)
```

활성화 승인 3대 조건: `status ∈ {TESTED, SUPERSEDED}`, 3개 시험 스위트(`CONTRACT`, `NEGATIVE`, `DETERMINISM`) 최신 실행 결과 전체 `PASS`, 유효한 `activated_by` 서명.

진행 중인 태스크는 접수 시점의 프로파일 개정판에 영구 고정(Pinning)되어 동작하며, 신규 접수 태스크부터 새 개정판이 적용된다.

### 8.5 운영 인터페이스

모든 조작은 단순 DB 수정을 넘어 트랜잭션과 감사 로그가 보장되는 16개 표준 운영 엔드포인트로 제공된다:
- 기종 등록, 기체 등록(`POST /operations/robots`), 어댑터 인스턴스 등록(`POST /operations/adapter-instances`), 어댑터 등록, 어댑터 버전 등록, 개정판 제출, 시험 요청, 바인딩 전환, 롤백, 개정판 폐기, 능력 차단/해제, 계약 폐기 예고 등록, 소비자 요구 등록(`POST /requirements`), 변경 계획 수립, 변경 계획 단계 실행, 사이트 이름 등록 기록(`POST /operations/site-names`).

**진단 조회 전용 JSON 엔드포인트 10종:**

| # | 엔드포인트 | 제공 정보 | 데이터 소스 |
|---|---|---|---|
| 1 | `GET /diag/bindings` | 기체별 어댑터 및 프로파일 개정판 바인딩 현황, 개정판별 기체 수 | `robot_binding` |
| 2 | `GET /diag/diff?from=&to=` | 두 개정판 간의 세부 능력 diff 분석 | `profile_skill*`, `profile_optional_field` |
| 3 | `GET /diag/epochs?robot_id=` | 기체의 에포크 전이 이력, 사유, 관측된 개정판 | `capability_epoch_log` |
| 4 | `GET /diag/rejections` | 클라이언트별 핸드셰이크 거절 이력 및 사유 코드 | `handshake_rejection` |
| 5 | `GET /diag/dependents?skill=` | 특정 스킬을 의존하는 활성 소비자 목록 | `consumer_requirement` |
| 6 | `GET /diag/plans` | 활성 변경 계획 목록 및 각 단계별 전제 조건 충족 여부 | `change_plan*` |
| 7 | `GET /diag/software` | 프로파일 선언 펌웨어와 기체 실측 펌웨어 간의 정합성 판정 (일치/불일치/미보고) | `robot_liveness`, `profile_revision.document` |
| 8 | `GET /diag/stalled` | 종료되지 않아 축소 드레인을 차단하고 있는 정체 태스크 감시 | `task`, `skill_type` |
| 9 | `GET /diag/robots` | 기체 등록 출처 및 상태 수명주기 (`CLAIMED`, `DISCOVERED`, `CONFIRMED`, `UNREGISTERED`) | `robot`, `robot_liveness` |
| 10 | `GET /diag/adapter-instances` | 현장에 배포된 어댑터 인스턴스 현황 및 실기체 연결 수, 적합성 상태 | `adapter_instance`, `adapter_version`, `robot` |

## 9. 운영 변경 체계

변경 파급 범위를 사전 계산하여 운영 위험을 제어하는 결정론적 메커니즘을 정의한다.

### 9.1 독립적인 4대 변경 축

| 변경 축 | 대상 | 변경 메커니즘 | 롤백 정책 |
|---|---|---|---|
| **계약 축** | `contracts/` Protobuf | 신규 릴리스 배포 (SemVer) | **비가역 (소비자 코드 기생성)** |
| **프로파일 축** | 기종별 능력 선언 사양서 | 개정판 발행 및 런타임 활성화 | `SUPERSEDED` 재활성화 |
| **어댑터 축** | 벤더 하드웨어 연동 드라이버 | 드라이버 바이너리 재배포 | 구형 버전 재배포 |
| **바인딩 축** | 기체 매핑 (`기체 = 어댑터 + 프로파일`) | 런타임 동적 매핑 전환 | 이전 바인딩 맵으로 복귀 |

바인딩 시점의 계약 호환성 검증 수식:
```
required = max( skill_type.introduced_in_semver for 프로파일 선언 스킬 전체 )
is_legal = (adapter_version.contract_semver.major == required.major)
        && (adapter_version.contract_semver >= required)
```
어댑터가 프로파일에 요구되는 모든 스킬 어휘를 수용할 수 있는 계약 버전으로 빌드되었는지를 엄격히 검증하여 런타임 호환성을 보장한다.

### 9.2 의존 원장 체계

소비자가 자신의 스킬 요구 사양을 능동 등록(`POST /requirements`)하거나 핸드셰이크 성공 시 런타임 관측(`OBSERVED`)하여 원장을 구축한다.

이를 통해 특정 능력의 삭제 시 영향받는 상위 시스템을 사전에 정확히 식별한다. 30일 이상 비활성 상태인 관측 항목은 `active=false`로 전환되되, 이력 보존을 위해 물리 삭제하지 않는다.

### 9.3 방향성 규율: 확장·이행·축소

**확장은 하향식(어댑터 → 미들웨어 → 카탈로그 → 상위), 축소는 상향식(상위 → 카탈로그 → 미들웨어 → 어댑터)으로 진행한다.**

| 변경 국면 | 주요 활동 | 전파 방향 |
|---|---|---|
| **확장 (Expansion)** | 신규 능력을 배포하되 기존 능력과의 병행 제공 유지 | 어댑터 → 미들웨어 → 카탈로그 → 상위 시스템 |
| **이행 (Migration)** | 상위 시스템이 신규 능력으로 전환. 구형 능력 의존도 0 관측 | 상위 시스템 주도 |
| **축소 (Contraction)** | 의존성 및 실행 태스크 부재 확인 후 구형 능력 제거 | 상위 시스템 → 카탈로그 → 미들웨어 → 어댑터 |

**축소 단계 진입을 위한 2대 필수 조건 (조회 기반 판정):**
1. `consumer_requirement`에 해당 능력을 요구하는 활성(`active`) 소비자가 **0건**일 것.
2. `task` 테이블에 해당 스킬을 실행 중인 비종착 태스크가 **0건**일 것 (드레인 완료).

추가 전제 조건: 해당 능력을 제공하는 바인딩된 기체 전체가 현재 정상 하트비트를 보고하고 있어야만 상기 조회가 유효하게 인가된다. 단 하나의 기체라도 통신 침묵 상태라면 상태 불명으로 처리되어 축소가 안전하게 차단된다.

### 9.4 6대 운영 변경 시나리오

| 시나리오 | 계약 축 | 프로파일 축 | 어댑터 축 | 파급 영향도 | 적용 절차 |
|---|---|---|---|---|---|
| **어댑터 최초 추가** | 무변경 | 신규 프로파일 | 신규 등록 | 없음 | §9.7 신규 절차 |
| **어댑터 패치 버전업** | 무변경 | 무변경 | 재배포 + 신규 버전 | 없음 | 바인딩 전환 |
| **선택 파라미터 추가** | Minor 증가 | 신규 개정판 | 재빌드 | 없음 (하위 호환) | §8.4 절차 |
| **신규 스킬 타입 신설** | 신규 정의 | 신규 개정판 | 재빌드 | 없음 | §8.4 + 카탈로그 |
| **스킬 의미론 변경** | **Major 증가** | 신규 개정판 | 재빌드 | **원장 기반 영향 분석** | **확장·이행·축소** |
| **기존 기능 완전 삭제** | 폐기 예고 | 개정판에서 제외 | 무변경 가능 | **원장 조회 + 드레인** | **축소 절차 강제** |

앞의 4개 시나리오는 파급 영향이 없어 즉시 진행되며, 뒤의 2개 시나리오는 원장 검증 및 단계적 변경 계획 집행이 필수적이다.

### 9.5 1급 객체로서의 변경 계획 (Change Plan)

운영자의 인위적 개입에 따른 실수를 방지하기 위해 변경 계획을 데이터베이스 1급 엔티티로 관리한다.

```json
POST /change-plans
{
  "intent": "REMOVE_CAPABILITY",
  "target": { "skill": "pick_place", "major": 1 },
  "site": "A"
}
```

단계 유형(`kind` 5종): `ANNOUNCE`, `OBSERVE_MIGRATION`, `DRAIN`, `APPLY`, `VERIFY_WITHDRAWAL`.
전제 조건 검사(`precondition` 6종): `NO_ACTIVE_CONSUMERS`, `NO_INFLIGHT_TASKS`, `DEPRECATION_PUBLISHED`, `CAPABILITY_WITHDRAWN`, `NO_ACTIVE_BINDINGS`, `SUCCESSOR_ACTIVE`.

전제 조건은 실행 시점에 원자적으로 재평가되며, 조건 불충족 시 작업 집행이 엄격히 거절된다.

### 9.6 상위 연계 계층 (ACL) 아키텍처

상위 MES/WMS 시스템과의 연계를 담당하는 Anti-Corruption Layer(ACL)는 아키텍처 경계 내에 정식 편입된다([ADR 9](../../adr/0009-no-declaration-without-consumer.md)). 하위 어댑터 계층과 완전한 대칭 구조를 형성한다:

| 계층 구분 | 프레임워크 코어 | 인스턴스 모듈 | 소유권 경계 |
|---|---|---|---|
| 상향 연계 (Northbound) | `acl-core` (시스템 무의존) | `acl-{system}` × N | 배포 현장 소유 |
| 하향 연계 (Southbound) | `adapter-core` (기종 무의존) | `adapter-{vendor}-{model}` × 3 | 제품 코어 소유 (ADR 31) |

상위 시스템 노출 인터페이스:
- `GET /catalog?site=`: 사이트 단위 가용 능력 카탈로그 조회
- `POST /requirements`: 상위 시스템의 의존 사양 능동 등록
- `picasso/{major}/{site}/site/catalog`: 사이트 카탈로그 변경 알림 스트림 (MQTT Retain)

### 9.7 어댑터 수명주기 단계

```
① 어댑터 등록:    vendor, name, version, 대상 계약 semver 메타데이터 기입
② 프로파일 제출:  벤더 사양 기반 프로파일 파생 및 스키마 검증 (§8.4 ①)
③ 계약 시험:      가상화 환경(mimic) 기반 계약 적합성 스위트 통과 (§8.4 ②)
④ 적합성 시험:    실물 하드웨어 어댑터 적합성 검증 (C-3 자리, 비목표)
⑤ 바인딩:         기체 등록 및 단계적 카나리 배포
⑥ 카탈로그 반영:  사이트 카탈로그 갱신 및 상위 시스템 스트림 통지
```

④번 적합성 검증은 실물 도입 전까지 `UNTESTED` 상태로 유지되며, 진단 1번 엔드포인트에 투명하게 관측된다. ADR 35에 따라 로봇 내부의 지리 공간 좌표 및 사이트 명칭 등록 상태 또한 진단 표면에 함께 표시된다.

## 10. `mimic` 내부 아키텍처

### 10.1 핵심 원칙

**거동은 데이터 프로파일에서 주입되며 소스 코드는 범용 인터프리터로 동작한다.** 기종별 전용 클래스를 일체 작성하지 않으며, 신규 기종 추가는 프로파일 JSON 1건 추가로 완결된다.

```
mimic/
  profile/     프로파일 로드(파일 또는 레지스트리) · 유효성 검증 · 능력 투영 · 폴링
  engine/      스킬 및 태스크 상태머신 실행기 · 논리 시계 · 이벤트 재생 버퍼
  fault/       선언된 결함 주입 · 전송 장애 시뮬레이션 · 프로토콜 한계 집행
  control/     원격 제어 채널 (루프백 전용 포트)
  transport/   gRPC 서버 인터페이스 및 MQTT 비동기 발행기
```

### 10.2 프로파일 로드 및 구동 모드

| 구동 모드 | 기동 인자 | 주요 활용 영역 |
|---|---|---|
| 로컬 파일 모드 | `--profile <path>` (기체별 지정) | §13 2단계 마일스톤, 단위 및 계약 적합성 테스트 |
| 레지스트리 연동 모드 | `--registry <url> --robot <id>...` | 3단계 이후, 실운영 환경 통합 |
| 후보 개정판 검증 모드 | `--registry <url> --profile-revision <id>` | `harness`에 의한 신규 개정판 CI 자동 시험 (§8.4 ②) |

단일 가상화 프로세스가 다수의 가상 기체를 동시 호스팅하며, 요청 헤더의 `robot_id`로 개별 기체를 라우팅한다.

### 10.3 동적 폴링, 가상 시계, 결정론적 시드

- **동적 폴링**: 레지스트리 모드에서 바인딩 및 오버라이드 상태를 주기적(기본 5초)으로 조회하여 유효 능력을 재계산하고 `capability_epoch`를 증가시킨다. 진행 중인 태스크는 기존 개정판을 유지한다.
- **가상 시계**: `REAL`(실시간) 및 `VIRTUAL`(가상 시계) 모드를 지원한다. `VIRTUAL` 모드에서는 `AdvanceClock(duration)` RPC를 통해서만 시계가 전진하여 테스트의 완벽한 결정론을 보장한다.
- **의사 난수 시드**: 실패 모드의 확률적 발현과 작업 소요 지터를 시드 기반으로 통제하여 재현 가능성을 보장한다.

### 10.4 3대 실패 주입 경로

1. **선언된 결함 발현**: 프로파일에 명시된 실패 모드와 발생 확률에 따른 결정론적 주입.
2. **전송 계층 장애 모사**: 패킷 지연, 연결 단절, 이벤트 유실, 중복 수신, 순서 역전 등 네트워크 장애 주입.
3. **프로토콜 한계 집행**: 프로파일 선언 수치 범위 및 허용치 위반 시 `PARAMETER_INVALID` 등의 거절 코드 발현.

### 10.5 원격 제어 채널 API

루프백 인터페이스(`127.0.0.1`) 전용 포트로 격리 노출된다.

| RPC 메서드 | 주요 기능 및 용도 |
|---|---|
| `SetSeed(seed)` | 난수 생성기 시드 고정 (결정론적 재현) |
| `SetClockMode(REAL\|VIRTUAL)` / `AdvanceClock(duration)` | 가상 시계 모드 전환 및 시계 전진 |
| `ForceFault(robot_id, error_type, task_id?)` | 특정 결함 강제 주입 (`task_id` 생략 시 기체 레벨 결함) |
| `SetSingleStep(bool)` / `Step()` | 상태 전이의 단계별 단일 스텝 실행 제어 |
| `InjectTransportFault(kind)` | `DISCONNECT`, `DELAY`, `EVENT_LOSS`, `DUPLICATE`, `REORDER` 주입 |
| `SetConnection(robot_id, ConnectionState)` | 기체의 연결 수명주기 상태 강제 전환 |
| `RemoveCapability(robot_id, skill)` / `RestoreCapability(...)` | 런타임 능력 상실 및 복원 시뮬레이션 |
| `ForceTerminalViolation(task_id)` | 종착 확정 후 비인가 재기동 상황 강제 주입 (래치 불변식 검증) |
| `ForceControlAuthorityLoss(robot_id)` | 외부 제어권 탈취 상황 주입 (§4.9) |
| `DumpInternalState(robot_id)` | 엔진 내부 상태 덤프 (테스트 검증 오라클 제공) |

### 10.6 외부 의존성 결손 시 거동

| 장애 상황 | 시스템 대응 거동 |
|---|---|
| 레지스트리 연결 불가 (기동 시) | 즉시 기동 실패 (비정상 상태 표면 노출 차단) |
| 레지스트리 연결 불가 (폴링 중) | 직전 캐시된 유효 능력을 유지하고 경보 로그 기록 |
| MQTT 브로커 단절 | 기존 세션을 유지하며 최대 N개까지 인메모리 버퍼링 후 재연결 시 순차 재생. 버퍼 초과 시 신규 `session_id` 발급 |
| `WatchTask` 수신 클라이언트 지연 | gRPC 백프레셔 적용 후 임계 초과 시 해당 스트림만 강제 종료 (태스크 본체는 무영향) |

### 10.7 가상화 한계

물리적 기하 충돌, 파지 역학, 도달 가능성 시뮬레이션은 배제한다. 본 아키텍처는 프로토콜 및 인터페이스 계약의 적합성을 검증하는 데 집중한다.

## 11. 게이트 체계 (`gate/`)

### 11.1 아키텍처 원칙

검증 게이트는 단순한 CI 셸 스크립트가 아닌 **재사용 가능한 단일 검증 라이브러리**로 구현된다. CI 빌드 환경과 레지스트리 런타임 등록 파이프라인이 동일한 검증 엔진을 호출한다.

| 호출 맥락 | 비교 기준선 (Baseline) |
|---|---|
| CI 빌드 게이트 | 메인 브랜치의 원본 프로파일 (`git show origin/main:<path>`) |
| 레지스트리 등록 게이트 | 동일 `profile_id`의 직전 `ACTIVE` 개정판 문서 |

### 11.2 11대 검사 규칙

| # | 검사 항목 | 방어 대상 | 판정 메커니즘 |
|---|---|---|---|
| 1 | `buf lint` | Protobuf 명명 규칙 및 스타일 위반 | Buf 린터 |
| 2 | `buf breaking` | 필드 번호 변경, 타입 불일치 등 파괴적 변경 | Buf 하위 호환성 분석 |
| 3 | 프로파일 JSON Schema + 구조 규칙 | 비정상 프로파일, 미등록 `error_type`, 역전된 범위 | JSON Schema 및 프로그램적 정적 분석 |
| 4 | 프로파일 ↔ Protobuf 교차검증 | 미정의 스킬 선언, 버전 초과, 필수 파라미터 누락 | 양방향 참조 무결성 분석 |
| 5 | `contracts/` 무의존성 | 계약 모듈의 내부 프로젝트 의존성 유입 차단 | 빌드 의존성 그래프 분석 (의존수 == 0) |
| 6 | 능력 어휘 파괴 검사 및 변경 분류 | §5.2 버전 규칙 위반 및 미인가 능력 축소 | 프로파일 diff 분석 및 SemVer 정합성 평가 |
| 7 | 기종 분기 금지 | 기종별 코드 분기 하드코딩 유입 차단 | 기종 무의존 모듈에 대한 벤더/모델 문자열 검색 |
| 8 | 프로파일 전용 변경 검증 | 프로파일 외 코드 변경 혼입 차단 | git diff 파일 목록 분석 |
| 9 | 결속 타입의 어댑터 경계 | 자리 이름 결속 타입이 공통 계층으로 유출되는 것을 차단 | 결속 선언 파일에서 유도한 타입명 문자열 검색 |
| 10 | 네거티브 스위트 | 검사 규칙 전체의 탐지 유효성 지속 검증 | 격리된 결함 케이스 주입 테스트 |
| 11 | 아웃바운드 경계 | 출하 소스가 선언되지 않은 자리에서 밖으로 나가는 것을 차단 (설명 층 호출 경로의 유입, AGENT-06 G1.4) | 모듈별 `src/main` 에서 HTTP 클라이언트 생성 지점을 세어 닫힌 목록과 대조 |

**검사 3 상세**: JSON Schema 검증 후 프로그램적 4대 추가 검증을 수행한다:
- `min_value ≤ max_value` 수치 범위 역전 검증
- `publish_interval.min_seconds ≤ max_seconds` 주기 역전 검증
- 동일 프로파일 내 `(skill_type, major)` 중복 선언 차단
- 스킬 내 파라미터 `key` 중복 선언 차단

**검사 4 양방향 교차 검증**: 프로파일이 참조하는 스킬이 Protobuf에 실재하는지 검증할 뿐만 아니라, 반대로 계약 카탈로그에 선언된 `since_minor ≤ 선언 minor`인 필수 파라미터(`is_optional=false`)가 프로파일에 누락 없이 존재하는지를 엄격히 검증한다.

**검사 7 기종 분기 차단**: 공통 계층 모듈(`client` · `mimic` · `harness` · `adapter-core` · `picasso` · `capability` · `adapter-host` · `uplink` · `registry` · `profile-model`)의 소스 코드 내에 특정 벤더명이나 모델 식별자 문자열이 하드코딩되는 것을 원천 차단한다. 정본 목록은 코드베이스의 `Check07ModelBranching.MODULES`에 정의되어 있으며 기계적으로 검증된다.

**검사 9 결속 경계**: 자리 이름이 어느 기종의 무엇에 묶이는지는 어댑터 경계의 지식이다(ADR 34 · 35). 검사 7이 기종 *좌표*를 막는다면 이 검사는 기종 *결속*을 막는다. **탐색어는 결속 선언 파일이 대는 최상위 타입명에서 유도**하므로 타입이 늘면 금지도 함께 는다 — 훑는 모듈 목록은 코드가 정본이다(`Check09BindingScope.MODULES`).

**검사 10 네거티브 테스트 데이터셋 (`gate/negative/`):**

| 대상 검사 | 주입 결함 케이스 |
|---|---|
| 검사 1 | 명명 규칙을 위반한 메시지 및 필드명 |
| 검사 2 | 필드 번호 재사용 및 호환 불가능한 타입 변경 |
| 검사 3 | 미등록 `error_type` 및 역전된 수치 범위 |
| 검사 4 | proto에 정의되지 않은 스킬 선언 및 필수 파라미터 누락 |
| 검사 5 | `contracts/`에 내부 모듈 프로젝트 의존성 추가 |
| 검사 6 | 파라미터 삭제 후 Minor만 증가, 소비자가 존재하는 능력 임의 삭제 |
| 검사 7 | 기종 무의존 공통 모듈에 기종명 문자열 삽입 |
| 검사 8 | 프로파일 변경 커밋에 소스 코드 변경 혼입 |
| 검사 9 | 기종 무의존 공통 모듈에 자리 결속 타입 참조 삽입 |

각 네거티브 케이스가 CI 실패를 유발하지 못할 경우 해당 테스트 케이스 자체가 실패로 처리된다.

## 12. 시험 전략

### 12.1 테스트 계층 구조

단위 테스트(상태머신 전이 망라성) → 계약 테스트(`mimic` ↔ `client`) → 결정론적 검증 → 네거티브 스위트 → 운영 시나리오 통합 검증.

**결정론 최우선 원칙**: 난수 시드와 가상 시계를 결합하여 동일 이벤트 시퀀스의 재현성을 100% 보장한다.

### 12.2 완료 기준, 검증 방법, 아키텍처 메커니즘 (23개 검증 항목)

| # | 완료 기준 | 증명하는 테스트 | 실현 메커니즘 |
|---|---|---|---|
| 1 | **A-1** 이기종 로봇의 동일 클라이언트 제어 | 2개 기종 프로파일의 `mimic`에 대해 동일 클라이언트 코드로 태스크 완주 | 요구 사양 설정 파일화 (§5.4) 및 게이트 7번 |
| 2 | **A-2** 중간 구독자의 상태 재구성 | 임의 시점 신규 구독 시 스냅샷과 이벤트 결합으로 내부 상태 완벽 복원 | `GetSnapshot`, `DumpInternalState`, 이벤트 재생 버퍼 |
| 3 | **A-2** 전송 장애 복원력 | 결손 감지, 중복 메시지 무시, 역전 패킷 재정렬을 통해 최종 일관성 달성 | `InjectTransportFault`, 시퀀스 번호, 재정렬 윈도우 (§3.5) |
| 4 | **A-2** 멱등성 및 개정판 규칙 | 동일 revision 재전송 시 멱등 응답, 구형 revision 거절, 신규 revision 파라미터 갱신 | 4-케이스 멱등성 처리 표, `ReplayEvents`, 재생 버퍼 |
| 5 | **A-2** 통신 침묵 원인 판별 | `OFFLINE`, `HIBERNATING`, `CONNECTION_BROKEN` 강제 주입 시 정확한 분기 판정 | `SetConnection`, 연결 상태 스트림, 최대 발행 주기 |
| 6 | **A-4** 장기 실행 태스크 제어 | 가상 시계 기반 30초 이상 태스크의 단조 증가 진행률 및 취소-복구 시퀀스 검증 | `AdvanceClock`, 진행률 정합성 불변식 (§4.4) |
| 7 | **A-4** 취소·일시정지 불가 기종 제어 | 미지원 기능 호출 시 `CANCEL_UNSUPPORTED`, `PAUSE_UNSUPPORTED` 명시적 거절 | 프로파일 `Support` 플래그 선언 (§7.4) |
| 8 | **A-4** 자율 재시도 및 수동 개입 분기 | 결함 `Resolution`에 따라 `RETRIABLE`, `NEEDS_INTERVENTION`, `FAILED` 전이 검증 | `RetryTask`, 프로파일 `Resolution`, 전파 규칙 1 |
| 8b | **A-4** 취소와 물리 복구 검증 | 복구 성공 시 `CANCELLED`, 복구 실패 시 `CANCELLED_RECOVERY_FAILED` 전이 | `CancelTask`, 전파 규칙 3, `ForceFault` |
| 8c | **A-4** 터미널 래치 불변식 검증 | 종착 확정 후 기체 무단 재기동 시 `TERMINAL_STATE_VIOLATED` 발행 및 상태 유지 | `ForceTerminalViolation`, 래치 불변식 (§4.4) |
| 8d | **A-4** 제어권 상실 격리 검증 | 제어권 탈취 시 `CONTROL_AUTHORITY_LOST` 발행 및 하드웨어 고장과의 분리 관측 | `ForceControlAuthorityLoss`, §4.9 규격 |
| 9 | **C-1** 단일 스키마 기반 이종성 표현 | 2개 프로파일이 동일 JSON Schema를 통과하며 7대 차이점이 데이터로 표현됨 | 게이트 3번 및 7번 검사 |
| 10 | **C-1** 런타임 투영 일치 검증 | `GetCapabilities` 응답이 프로파일 투영 규칙 파생값과 100% 일치 | 투영 함수 기계 대조 (하드코딩 원천 차단) |
| 11 | **C-2** 신규 기종의 무수정 추가 | 제3의 기종 `quadruped-c` 추가 시 소스 코드 변경 0건으로 전체 스위트 통과 | 게이트 8번 검사 (프로파일 외 변경 차단) |
| 12 | **D-1** 자동화된 비호환 변경 차단 | 네거티브 스위트 주입 시 무단 호환성 파괴 PR이 CI에서 자동 차단됨 | 게이트 9번 네거티브 스위트 |
| 13 | 능력 호환성 협상 검증 | 핸드셰이크 시 5대 거절 코드가 사유와 함께 반환되고 레지스트리에 적재됨 | `Negotiate`, 거절 코드 체계, 의존 원장 적재 |
| 14 | 런타임 능력 동적 축소 검증 | 장애 주입 시 해당 스킬만 `CAPABILITY_WITHDRAWN` 반환되고 타 스킬 정상 유지 | `CapabilityChanged`, 에포크 무효화 |
| 15 | 런타임 무중단 개정판 갱신 | 활성화 시 기존 태스크는 완주(Pinning), 신규 태스크는 새 개정판으로 실행 | 동적 폴링, 개정판 고정 불변식 (§8.4) |
| 16 | 어댑터 신규 등록 시나리오 | 6단계 등록 절차 통과 후 카탈로그에 반영되고 미검증 상태가 투명 노출됨 | 어댑터 수명주기 파이프라인 (§9.7) |
| 17 | 다대다 변경 축 분리 검증 | 어댑터 버전업 시 프로파일 유지 관측, 비호환 계약 조합에 대한 바인딩 거절 | 바인딩 호환성 검증 수식 (§9.1) |
| 18 | 의존 원장 실시간 구축 검증 | 미등록 클라이언트의 핸드셰이크 성공 데이터가 원장에 자동 적재되고 유지됨 | `consumer_requirement` 이중 소스 체계 (§9.2) |
| 19 | 비인가 기능 축소 차단 검증 | 활성 소비자 또는 실행 태스크 체류 시 `REMOVE_CAPABILITY` 계획 승인 거부 | 변경 계획 전제 조건 검증 엔진 (§9.5) |
| 20 | 카나리 점진 배포 검증 | 일부 기체에 대한 신규 개정판 배포 시 이종 개정판의 안정적 병행 구동 관측 | 기체 단위 바인딩 매핑 및 `profile_ref` 헤더 |

### 12.3 시험 비목표

실물 하드웨어 역검증, 물리 역학 시뮬레이션, 다중 로봇 트래픽 중재 등은 본 시험 스위트의 범위에서 제외된다.

## 13. 구현 마일스톤

| 마일스톤 | 핵심 개발 범위 | 달성 검증 목표 |
|---|---|---|
| **1단계** | `contracts/`, `profile/schema/`, 픽스처, `gate/` (검사 1~6, 9 일부), CI 파이프라인 | 계약 및 프로파일 정합성 게이트 확립 (D-1 기반) |
| **2단계** | `mimic` (파일 모드), `client`, `harness`, 기종 프로파일 3종, 게이트 7~8 및 9 완결 | **완료 기준 1~12 및 13번 절반 달성.** 레지스트리 없이 핵심 아키텍처 명제 입증 |
| **3a단계** | `registry` 코어 — DB 스키마, 어댑터 수명주기, 바인딩 검증, 시험 오케스트레이션, 카탈로그 읽기 API, 진단 표면 | 완료 기준 13~17, 20 달성 |
| **3b단계** | 의존 원장, 변경 계획 엔진, 사이트 카탈로그 스트림, 진단 5~6, 게이트 6번 축소 게이트 | 완료 기준 18~19 달성 및 운영 변경 체계 완결 |

## 14. 아키텍처 결정 기록 (ADR · D-2)

| 번호 | 아키텍처 결정 내용 | 한계 및 후속 과제 |
|---|---|---|
| 1 | 계약은 Protobuf, 프로파일은 JSON Schema로 이원화하고 정합성은 게이트 4번으로 통제 | — |
| 2 | 결함 처방 3분류를 계약에서 배제하고 잔여 역량 불리언 2종으로 객관화 | — |
| 3 | 능력 동일성을 Major 버전으로 결정하는 규율 확립 | §15.1 |
| 4 | 토픽 경로·헤더·페이로드의 관심사 분리 및 SemVer 병기 | §15.4 |
| 5 | 검증 시점을 3단계로 분격하고 런타임 검증 오버헤드 최소화 | — |
| 6 | gRPC와 MQTT의 전송 경계 분리 및 독립 시퀀스 카운터 채택 | — |
| 7 | 세션 단위 시퀀스 단조성과 인메모리 순환 재생 버퍼 채택 | §15.8 |
| 8 | `Reset`을 외부 RPC로 노출하지 않고 엔진 내부 자동 전이로 캡슐화 | — |
| [9](../../adr/0009-no-declaration-without-consumer.md) | 즉시 명령 및 동시성 어휘 배제 — 소비자가 정의되지 않은 선언은 배제 | — |
| [10](../../adr/0010-projection-boundary.md) | 공개 투영 항목과 발신자 내부 설정 항목의 명확한 분리 기준 수립 | — |
| 11 | 프로파일 개정판의 불변성 보장, `SUPERSEDED` 재활성화 롤백, 활성본 직접 삭제 금지 | — |
| 12 | 실행 중 태스크의 개정판 고정(Pinning) 및 동적 파라미터 갱신의 무영향성 | — |
| 13 | 능력 축소의 이원화 경로 수립 및 발신 기체 중심의 에포크 단조 채번 | — |
| 14 | 4대 운영 변경 축 분리 및 비가역 축(계약)의 최소화 격리 | — |
| 15 | 의존 원장 구축 — 선언과 관측의 병행 수집 및 비활성 레코드의 논리 삭제 원칙 | §15.10 |
| 16 | 기능 축소의 진입 장벽을 시간 경과가 아닌 관측 데이터(소비자 0, 태스크 0)로 통제 | — |
| 17 | 변경 계획을 1급 엔티티로 격리하고 단계별 전제 조건 원자적 재평가 보장 | — |
| 18 | 카탈로그를 개별 기체가 아닌 사이트 단위의 논리적 능력 집합으로 추상화 | — |
| 19 | 기체의 레지스트리 단방향 폴링 구조 채택을 통한 양방향 결합 순환 차단 | §15.5 |
| 20 | `harness` 분리 및 시험 요청의 비동기 레코드 적재를 통한 결합도 해소 | — |
| 21 | 단일 프로세스 다중 가상 로봇 호스팅 채택 (PoC 목적의 가상화 구조) | — |
| [22](../../adr/0022-gate-as-library.md) | 게이트를 독립 라이브러리로 구축하여 CI 및 레지스트리에서 공통 호출 | — |
| [23](../../adr/0023-string-checks-over-ast.md) | 게이트의 구조적 검사(5·7번)를 복잡한 AST 대신 결정론적 문자열 매칭으로 구현 | §15.6, §15.11 |
| 24 | 보안 인증·인가를 배포 인프라스트럭처로 위임하고 내부 신뢰 네트워크 전제 | §15.3 |
| 25 | 실물 도입 전 가상화 기반 계약 우선 확정 방법론 채택 | §15.7 |
| 26 | 사이트 축을 경로 및 카탈로그 스키마에만 반영하고 네트워크 브리지는 비목표 처리 | §15.9 |
| 27 | 물리적 도달성 및 기하 충돌 판정을 프로파일 및 가상화 범위에서 배제 | §15.2 |
| 28 | openTCS 및 VDA5050 표준의 선별적 패턴 차용 및 반례 분석 근거화 | — |
| [29](../../adr/0029-shared-profile-model.md) | `profile-model` 공통 모듈 추출을 통한 게이트와 에뮬레이터 간 결합도 해소 | — |
| [30](../../adr/0030-codegen-outside-buf.md) | Gradle protoc 기반 코드 생성 체계 단일화 | §15.20 |
| [31](../../adr/0031-adapter-ownership.md) | 어댑터 모듈의 코어 제품군 편입 및 벤더 SDK 라이선스 격리 방안 수립 | — |
| [32](../../adr/0032-safety-boundary.md) | 안전 회로 계통의 표준 계약 경유 배제 및 비상정지 독립성 보장 | — |
| [33](../../adr/0033-adapter-module-shape.md) | 기종별 전용 지식을 캡슐화하는 어댑터 모듈의 격리 구조 확립 | §15.55, §15.56 |
| [34](../../adr/0034-semantic-binding-unowned.md) | 시맨틱 결속의 어댑터 외주화 차단 및 미들웨어 계층 편입 | §15.59, §15.60 |
| [35](../../adr/0035-site-names-live-in-the-robot.md) | 사이트 명칭 및 지리 공간 정보의 기체 내부 저장 원칙 수립 | §15.68 |
| [36](../../adr/0036-work-first-assignment-vs-execution.md) | 일감 배정 어휘와 실행 계약 인터페이스의 명확한 분리 | — |
| [37](../../adr/0037-registration-is-discovery-or-declaration.md) | 기체 등록 경로의 이원화: 선언적 등록과 런타임 발견 수용 | — |
| [38](../../adr/0038-mission-layer-schema-is-ours.md) | 미션 계층 스키마 및 코어 오케스트레이션 엔진의 프로젝트 범위 수용 | — |
| [39](../../adr/0039-adapter-host.md) | 기종 무의존 공통 어댑터 호스트(`adapter-host`) 아키텍처 확립 | — |
| [40](../../adr/0040-remedy-is-a-query-not-a-contract.md) | 사건 번들과 대안 탐색을 계약이 아닌 조회 결과로 배치, 탐색 깊이 세 걸음 제한 | §15.149, §15.150 |
| [41](../../adr/0041-assignment-policy-outside-decision-inside.md) | 배정 정책의 외부 배치 허용, 채택 결정은 소유자가 tick 경계 안에서 수행 | §15.164 |
| [42](../../adr/0042-no-command-without-an-owner.md) | 소유자 미확정 자원에 대한 명령 금지, 자리는 자원 소유 대장에 존치 | §15.154, §15.162 |
| [43](../../adr/0043-entitlement-declared-outside-judged-inside.md) | 자동 승인 자격의 외부 선언과 내부 판정, 선언 목록의 위치는 배치가 결정 | §15.165 |
| [44](../../adr/0044-approval-names-a-proposal-not-an-action.md) | 승인은 조치를 기술하지 않고 제안을 지시, 조치에 실릴 값은 선언과 관측에서만 취득 | §15.173, §15.174, §15.175 |
| [45](../../adr/0045-revocation-is-marked-not-deleted.md) | 자격 강등은 선언의 주인이 수행하고, 철회는 삭제가 아닌 표시로 기록 | §15.184 |
| [46](../../adr/0046-consumed-approval-is-recorded.md) | 승인이 소모한 제안을 (기체, 주문)마다 기록하고, 다시 온 승인에 `CONSUMED` 로 그 기록을 돌려줌 | §15.201 |
| [47](../../adr/0047-operator-decision-is-made-by-a-person.md) | 운영자 판단은 사람만 내고 누가 냈는지 사건에 남기며, 판단자는 읽는 쪽이 생길 때까지 내보내지 않음 | §15.202 |
| [48](../../adr/0048-result-notice-has-an-outside-shape.md) | 결과 통보에 판이 있는 바깥 형식을 두고, 승인 답과 실행 · 걸음 단위 · 인스턴스 식별자로 이음 | §15.203 |
| [49](../../adr/0049-revision-tests-have-a-runner.md) | 개정판 시험 3종의 뜻을 정하고, 실행기를 harness 에 두어 registry 의 문 3개(요청 · 집기 · 보고)에 HTTP 로만 닿게 함 | §15.207 |
| [50](../../adr/0050-mission-definition-is-versioned-data.md) | 임무 정의를 검증을 지난 데이터로 두어 판 번호를 매기고, 실행은 생성 때 쥔 임무 판으로 끝냄. 설비 대기를 셋째 경로로 둠 | §15.209 |
| [51](../../adr/0051-three-authority-boundaries.md) | 권위를 임무 실행, 로봇 제어, 공유 자원 셋으로 나누고 소유 이전 순서와 발행 관문을 정해 분산 배포를 조건부로 열어 둠 | §15.216 |
| [52](../../adr/0052-work-permit-is-a-projection.md) | 안전 계통 상태의 정보 투영인 작업 허가를 계약 밖 포트로 기체 id 집합마다 읽어 유효 권위와 결합한 단일 발행 관문에서 새 로봇 명령 발행만 막도록 정해 둠 | §15.219 |
| [53](../../adr/0053-rework-of-a-skipped-target.md) | 건너뛴 대상의 재작업을 종결한 순회에서 사람이 한 쌍씩 내도록 정해 둠 | §15.236 |

기록은 `docs/adr/`에 있고 번호가 이 표의 행 번호다. **이미 내려서 코드에 박힌 것만 쓴다** — 3a·3b가 만들 것(11~21)은 그때 쓴다. 결정하지 않은 것을 미리 적어 두면 그것이 결정처럼 보인다.

> 마지막 대조: 2026-11-08 · sha256:fb54b50855c6 · 열림: C-3, §15.7, §15.126, ADR 32 · 시나리오 5, §15.4, §15.5, §15.6 · §15.11 · §15.28, §15.8, §15.9, §15.10, §15.1, §15.2, §15.33, §15.87

## 15. 알려진 한계

> **이 절은 대장이 아니라 일지다.** 앞쪽(1~45)이 한계 목록이고 뒤쪽은 무엇을 지었고 무엇이 드러났는지의 기록이라,
> *"지금 무엇이 열려 있나"* 를 물으면 처음부터 읽어야 한다. 그 답은 [`docs/limits.md`](../../limits.md) 가 한 장으로 준다
> — **닫힌 것은 거기 없고**, 열린 것마다 *무엇이 있어야 닫히나* 가 한 줄로 적혀 있다. 새 한계는 여기 먼저 적히고 대장은 그것을 색인한다.

1. **버전 규칙은 벤더가 지킬 때만 작동한다.** 선언을 그대로 두고 거동만 바꾸면 게이트 6번 diff에 아무것도 나오지 않는다. `Pick`이 "잡았다"에서 "잡고 들어올렸다"로 바뀌는 경우다. 적합성 역검증에서만 드러나며, 그때까지의 방어는 "프로파일을 벤더 문서에서 파생시키고 괴리가 발견되면 고친다" 하나다.
2. **프로파일의 표현력에 한계가 있다.** 가반하중과 도달 범위는 담기지만 형상별 파지 가능성은 담기지 않는다. 어디까지를 선언으로 두고 어디부터를 시도해봐야 아는 것으로 남길지가 이 트랙의 진짜 설계 문제다.
3. **감사 로그가 부인방지 근거가 아니다.** 행위자 신원을 요청 헤더에서 그대로 받으므로 위조 가능하다. 조사 단서로만 쓴다.
4. **`contract_revision`의 다이제스트 부분은 경보 전용이다.** semver로 major 불일치는 차단하지만, 같은 semver 안에서 실제로 호환되지 않는 변경이 있었다면 사후 조사에만 쓰인다.
5. **런타임 갱신에 폴링 지연이 있다.** 기본 5초만큼 반영이 늦는다. push로 줄일 수 있으나 `registry → mimic` 간선이 생겨 §3.2가 깨지므로 지연을 받아들였다.
6. **게이트 7번이 문자열 검사다.** 기종 식별자를 계산해 만들면 우회된다. 우회를 막는 것이 아니라 실수를 막는 장치다.
7. **`mimic`이 계약을 정의해버릴 위험이 있다 — 그리고 그것이 관측되었다.**

    원래 문장은 *"실물이 없는 동안 계약이 에뮬레이터에 맞춰 굳을 수 있다"* 였다. 2026-09-08 Unitree G1 어댑터를 설계하면서 **이미 굳었다는 증거가 나왔다.**

    벤더의 `sport` 서비스(`LOCO_SERVICE_NAME`, API 7001~7111)와 계약의 스킬 셋을 대조하니 셋 중 하나도 맞지 않았다 — `navigate_to`는 이름→자세 해석을 요구하는데 SDK가 주는 것은 `SetVelocity(vx, vy, omega, duration)`이고, `pick_place`·`inspect`는 대상 시맨틱을 요구하는데 그 자리가 없다. **첫 실물이 계약 어휘를 하나도 만족하지 않는다.** 짐작이 아니라 `AllModelsTest`가 붙들고 있다 — `unitree-g1`의 면제를 지우면 스위트가 빨개진다(아래 58번).

    계약이 실물 쪽으로 한 칸 갔다(`move_relative@1`). 그것으로 갭이 닫힌 것은 아니다 — **나머지 셋은 그대로 실물과 먼 채로 있고**, 그것을 좁히는 것은 두 번째 기종의 어댑터다.

    방어책이었던 *"프로파일을 벤더 문서에서 파생시키는 규율"* 은 습관이라 지켜지는지 확인할 방법이 없었다. `profile/provenance/`가 그것을 검사로 바꿨다(아래 57번). 남는 것은 여전히 적합성 역검증이며 열려 있다.
8. **재생 버퍼가 프로세스 메모리에 있다.** 재기동하면 세션이 바뀌고 소비자는 스냅샷부터 다시 세운다.
9. **다중 사이트는 스키마·토픽 경로·카탈로그에만 반영돼 있다.** 사이트별 배포 격리와 브리지 정책은 비목표다.
10. **원장은 소비자가 우리를 거쳐 갈 때만 정확하다.** `OBSERVED`는 핸드셰이크를 하는 소비자만 잡는다. 우리를 우회해 로봇에 직접 붙는 경로가 생기면 원장이 조용히 거짓말을 하고, 그 위에 선 축소 판정도 함께 틀린다. **원장의 정확성은 "모든 접근이 이 계약을 지난다"는 조직적 합의에 의존하며 기술로 강제되지 않는다.**

11. **게이트 5번도 문자열 검사다.** 7번과 같은 이유이며(Gradle을 부르면 `gate → Gradle → gate`가 된다) 대가가 셋이다. 관례 플러그인이나 별도 `.gradle.kts`가 주입하는 프로젝트 의존은 **못 잡고**, 블록 주석(`/* … */`) 안의 `project(` 와 루트가 **자기 자신을 위해** 갖는 의존은 **거짓 실패**다. 잡는 형태는 `project(":x")`·`project(path = ":x")`·`projects.x` 셋이며, 넷째 형태가 생기면 이 검사를 고쳐야 한다.
12. ~~**`gate`는 `contracts`에 빌드 의존을 걸지 않아 `buf build`가 먼저 돌아야 한다.**~~ **(닫힘, §15.111)** 라이브러리 의존은 여전히 없지만 `:gate:test` 가 `:contracts:generateProto` 에 **태스크로** 매달렸고, 디스크립터를 만드는 것이 buf 가 아니라 protoc 이라 순서가 Gradle 안에 있다.
13. **proto 파일 하나가 나쁘면 `ContractIndex`가 통째로 실패한다.** 게이트는 빨간불이 되므로 안전한 방향이지만, 나머지 스킬들의 대조 결과를 함께 잃는다.

    **(닫힘, §15.284)** 항목 284가 저작 실수를 최상위 메시지 하나에 가두어 나머지 스킬은 색인되고 검사 4번이 대조 결과를 함께 낸다. 디스크립터 자체가 깨진 것은 전처럼 실패한다. 행 §15.13은 닫혔다.

14. **게이트 6번이 분류하지 않는 파괴가 있다.** 허용값 축소·문자열 길이 축소·수치 범위 축소는 §5.2가 열거하지 않아 **경고로만** 낸다. 실제로 클라이언트를 깰 수 있으므로 사양이 이를 분류하면 그때 오류로 올린다. 반대로 §5.2의 "성공 판정 기준 변경"은 프로파일에 담기지 않아 6번이 볼 수 없다 — 그것은 위 1번과 같은 성격이다.

    **(닫힘, §15.293)** 항목 14의 행은 항목 195가 "원장을 못 읽으면 축소가 경고로 지나감"으로 다시 적었다. 항목 293이 원장이 붙어 있는데 모른다고 답하면 축소를 거부하게 바꾸어 그 행을 닫는다. 원장이 없는 CI는 경고 그대로다.

15. **음성 하네스의 케이스 1·2는 Docker가 있어야 돈다.** 없으면 건너뛰며, `picasso.negative.strict=true`(CI)일 때만 실패한다. 로컬은 Docker 없이 나머지 일곱을 돈다.
16. **`buf.yaml`의 `breaking:` 절이 파싱되는지는 PR 빌드에서만 확인된다.** push 빌드에는 기준선이 없어 검사 2번이 건너뛴다. 자기 자신과 비교하는 `--against .` 스텝을 지운 대가다.
17. ~~**CI와 CLI 기본값이 `profile/fixtures/`를 본다.**~~ **(닫힘)** CI 가 `--profiles` 를 둘 다 주고(`profile/fixtures`·`profile/profiles`) 기준선도 디렉터리별로 나눠 뽑는다 — 평탄화로 덮이던 자리가 사라졌다. 아래는 그때의 기록이다.

    **CI와 CLI 기본값이 `profile/fixtures/`를 본다.** 2단계에서 `profile/profiles/`가 생기면 **둘 다 고쳐야 하며**, 안 고치면 진짜 프로파일이 게이트를 지나지 않는다. 기준선 파일도 `basename`으로 평탄화하므로 디렉터리가 여럿이 되면 동명 파일이 조용히 덮인다.
18. **검사 6의 `optional_fields` 매칭이 접미사 일치다.** 같은 파라미터 key를 가진 다른 스킬에도 소견이 붙는다. 1단계 프로파일에는 스킬이 둘뿐이라 드러나지 않는다.

    **(닫힘, §15.285)** 항목 285가 경로를 정확히 `task.parameters.<key>` 꼴일 때만 파라미터로 읽게 했다. 같은 key를 가진 여러 스킬에 소견이 붙는 것은 경로에 스킬이 없는 계약대로라 바꾸지 않았다. 행 §15.18은 닫혔다.

19. **`mimic`이 게이트가 거절할 프로파일로 기동할 수 있다.** `ProfileSource`는 JSON Schema만 보고 게이트 검사 3번의 구조 규칙 넷(발행 간격 뒤집힘, `(skill_type, major)` 중복, 스킬 내 `key` 중복, 어댑터 전용 `error_type`)은 보지 않는다. §10.2가 요구하는 것이 스키마 검증뿐이라 사양 위반은 아니지만, `--profile <path>`가 임의 경로를 받으므로 실제로 가능한 비대칭이다.

    **(닫힘, §15.286)** 항목 286이 구조 규칙 넷을 `profile-model`로 옮겨 게이트 검사 3번과 mimic 기동이 같은 규칙을 보게 했다. mimic은 스키마를 통과해도 구조 규칙을 어긴 프로파일로 기동하지 않는다. 옛 항목이 든 어댑터 전용 `error_type`은 처음부터 스키마가 막고 있었다. 행 §15.19는 닫혔다.

20. **proto 코드 생성과 디스크립터를 서로 다른 도구가 만든다**(ADR 30). Gradle protobuf 플러그인의 protoc와 `buf` 내장본이 버전이 달라 생성 코드와 디스크립터가 미세하게 다를 수 있다. 쓰는 것이 메시지 구성이라 실질 영향은 없다. 그리고 protobuf-gradle-plugin 0.9.4는 **Gradle 10에서 깨진다** — legacy `Usage` 속성과 다중 문자열 의존 표기가 플러그인 내부에서 나오므로 우리가 못 고친다. Gradle 10 이전에 플러그인 버전을 올려야 한다.
21. **`session_id`가 §5.5의 ULID가 아니라 기동 카운터다.** ULID의 난수부를 시드에서 뽑으면 §12.1의 결정성 규율("시드 + 가상 시계 고정 = 동일 이벤트 시퀀스")과 "재기동하면 새 세션"이 충돌하고, `UUID.randomUUID()`를 쓰면 결정성이 깨진다. 세션의 요건은 "온라인이 될 때마다 새것"이므로 프로세스 내 카운터로 족하다. **대가는 프로세스를 재기동하면 카운터가 0으로 돌아간다는 것**이다 — 같은 밀리초에 재기동하면 세션이 겹칠 수 있다. `mimic`은 PoC이므로 감수한다. **어댑터 호스트도 같은 카운터를 쓴다**(`host-N`) — 계획은 실물에서 ULID 였으나 그렇게 하지 않았고, 그래서 이 한계는 발신자 **둘**의 것이다(`docs/limits.md`).

    **(좁힘, §15.259)** 어댑터 호스트의 세션도 에뮬레이터처럼 기동 시각(밀리초)과 카운터를 들게 되어(`host-<밀리초>-<카운터>`) 다시 띄울 때마다 같은 값이 되풀이되던 것은 사라졌다. 같은 밀리초에 다시 뜨거나 시계가 되감기거나 에뮬레이터를 가상 시계로 띄울 때 겹칠 수 있는 것은 남았다.

    **(좁힘, §15.288)** 항목 288이 두 발신자가 아직 나지 않은 번호의 재생 요청을 `SEQUENCE_EVICTED`로 답하게 해, 세션이 되풀이되어도 옛 커서가 새 프로세스의 다음 번호보다 크면 미들웨어가 스냅숏부터 다시 세운다. 새 프로세스의 번호가 옛 커서를 지난 뒤의 건너뜀과 세션 되풀이 자체는 남는다.

22. **`contract_digest`가 `buf` 모듈 다이제스트가 아니라 디스크립터 셋의 SHA-256이다.** `buf`는 Docker 래퍼이고 게이트는 CI가 `buf build`를 먼저 돌리는 순서에 기대고 있는데, 런타임 헤더까지 거기 매달면 `mimic`이 Docker 없이 기동하지 못한다. `includeSourceInfo = false`라 주석만 고친 커밋에서는 변하지 않는다(실측). **역은 성립하지 않는다** — `includeImports = true`가 `descriptor.proto`를 끌고 오므로 protobuf나 플러그인 버전을 올리면 계약이 그대로여도 다이제스트가 바뀐다. **그리고 그 순서 의존이 로컬에서 물었다 (2026-09-09).** 디스크립터가 **둘**이었다 — Gradle 이 `contract_digest` 용으로 만드는 `picasso.desc` 와, 게이트 시험이 읽는 `contracts/build/descriptor.binpb`. 뒤의 것은 `tools/buf build` 를 **손으로** 돌려야 갱신됐고, `skill_catalog.proto` 에 옵션을 더하고 게이트 시험을 돌리니 빨개지지 않고 **옛 판정**이 나왔다 — 낡은 디스크립터는 낡은 코드와 사이좋게 초록이다. **→ 디스크립터가 하나가 되면서 닫혔다(§15.111).** 남은 것은 다이제스트의 계산 방법이 `buf` 모듈 다이제스트가 아니라는 것뿐이고, 그것은 그대로다.

    **(닫힘, §15.291)** 항목 291이 다이제스트를 `picasso/` 파일만 이름 순으로 세게 바꾸어 protobuf 버전을 올려도 계약 신원이 안 바뀐다. `buf` 모듈 다이제스트는 주석에도 바뀌어 쓰지 않는다. 남는 것이 없어 닫는다.

23. **`MAJOR_MISMATCH`가 minor 부족까지 덮는다.** §12.2의 13번이 협상 거절을 다섯으로 못박았고 버전 불만족을 뜻하는 코드가 그것뿐이다. `detail`이 무엇이 부족한지 말하지만 코드 이름은 실제보다 좁다.
24. **`GetCapabilitiesResponse`에 `Rejection` 자리가 없다.** 그래서 그 RPC만 신원 불일치가 응답 `oneof`가 아니라 gRPC 상태로 나간다 — "요청을 해석하지 못했으면 gRPC 상태, 해석했는데 거절하면 `oneof`"라는 규칙의 유일한 예외다.
25. **`update_index`가 0부터 시작해 헤더에서 미설정과 구별되지 않는다.** `capability_epoch`는 1에서 시작해 피했지만 `task.proto`가 `update_index`를 0부터라고 못박았다. `schema_id`가 방향을 말하므로 실질 문제는 없다. **`sequence`도 같다**(§4.8이 0부터를 못박았다).
26. **일시정지 중에도 진행률이 흐른다.** `Progress`가 경과만 세므로 길게 멈췄다 재개하면 진행률이 튄다. §4.4는 `(task_id, revision, attempt)` 구간 안의 단조 비감소만 요구하므로 계약 위반은 아니다.

    **(닫힘, §15.282)** 항목 282가 `Progress`에서 멈춘 구간을 빼 일시정지 중 진행률이 흐르지 않게 했다. 재개 직후 완료로 가던 것도 함께 사라졌다. 행 §15.26은 닫혔다.

27. **`profile/profiles/`의 모든 기종이 공통 요구 집합을 만족해야 한다.** 완료 기준 11을 공허하지 않게 하려고 하네스가 그 디렉터리를 훑기 때문이다(A-1의 일반화). 제약은 다섯이며 `AllModelsTest`의 KDoc이 정본이다.
28. **검사 7이 능력 기반 분기를 못 본다.** `capability.skillsList.size > 2` 같은 분기는 기종 이름을 한 글자도 안 쓴다. 문자열 검사의 한계이며(§11.2가 의도라고 밝혔다) `harness`의 요청 바이트 동일성 시험이 그 자리를 메우지만 그것은 게이트가 아니다. 반대로 **탐색어가 약하면 거짓 실패**가 나므로 `profile/profiles/`의 좌표만 쓰고 8자 미만이거나 구분 기호가 없으면 실패시킨다.
29. **검사 8이 `git diff`의 상태 문자에 기댄다.** `--diff-filter=A --no-renames`로 뽑지 않으면 `git mv`가 "소스 변경 0으로 새 기종"으로 읽힌다. 그리고 `CHANGED_FILES`가 `--require`에 없으면 diff 생성이 깨져도 SKIP으로 넘어가 종료 코드가 0이다 — PR 빌드에서만 요구하므로 push 빌드에서는 여전히 건너뛴다.
30. **MQTT 발행이 실제 브로커로 검증된다(닫힘). 기본 스위트는 여전히 in-process다.** `MqttPublisher`(Paho v5)가 발행자 추상을 채우고, `mimic --broker <url>` 이 그것을 실제로 문다 — 기체마다 하나씩 접속해 Last Will을 그 기체의 `connection` 토픽으로 건다(§4.7). `MqttBrokerTest`가 컨테이너 브로커(Mosquitto)로 네 가지를 다 본다:

    - `retain` — 발행 뒤에 붙은 구독자가 받는가. 반대쪽(retain이 아니면 못 받는다)도 함께.
    - Last Will이 `CONNECTION_BROKEN`이라는 것 — 강제 절단에서 나오고 정상 종료에서는 안 나온다.
    - 토픽 문자열의 브로커 수준 합법성 — `Topics`가 막는 집합과 브로커가 거부하는 집합이 같은지.
    - **CLI가 브로커를 주면 발행이 실제로 나가는가** — `MqttPublisher`가 브로커와 말한다는 것과 `mimic`이 그것을 쓴다는 것은 다른 얘기다.

    **기본 스위트는 안 바뀐다.** §12.1의 결정성 — "시드 + 가상 시계 고정 = 동일 이벤트 시퀀스" — 이 브로커를 붙이면 깨지므로 `--broker` 없이는 아무 데도 안 나가고, `mimic`의 시험은 하나도 컨테이너를 안 쓴다. 브로커 시험을 `harness`에 둔 것도 그래서다(아래 39번).
31. **이벤트를 접는 소비자가 잠시 §4.5의 표에 없는 조합을 볼 수 있다.** 원인을 결과보다 먼저 내보내지만(스킬 `HALTED` → 태스크 `FAILED`), 두 사실을 원자적으로 발행할 수단이 없으므로 그 사이의 접힌 상태는 표 밖이다. 정착한 상태만이 §4.5의 대상이다.
32. **제어 채널의 proto가 `buf lint`·`buf breaking` 밖이다.** `contracts/buf.yaml`의 모듈이 `proto/` 하나이고, 제어 채널은 §10.5에 따라 `mimic/` 안에 있다. 필드 번호를 재사용해도 게이트 1·2번이 잡지 않으므로 **RPC 이름과 번호를 지금 다 예약해 둔다.** 두 번째 buf 모듈을 더하면 닫히지만 Docker 호출이 하나 는다.

    **(닫힘, §15.287)** 항목 287이 제어 채널을 두 번째 buf 모듈(`mimic/buf.yaml`)로 삼아 게이트 검사 1·2번이 계약과 같은 규칙으로 그 lint와 breaking을 보게 했다. 그러려고 두 RPC가 함께 쓰던 응답 타입을 나눴으며 번호를 reserved로 남기는 관례는 그대로다. 행 §15.32는 닫혔다.

33. **제어 채널이 인증하지 않는다.** 루프백 바인딩이 유일한 방어이며(§6.3), 같은 호스트의 다른 프로세스는 막지 못한다. `mimic`은 시험 도구이므로 감수한다.
34. **관측 적재에 구독기가 없다.** 3a-3은 `ObservationService`(헤더 하나를 세대 이력에, 거절 하나를 `handshake_rejection`에)까지 만들고 **그것을 부르는 MQTT 구독기는 만들지 않았다.** 이유는 위 30번과 같다 — 브로커를 붙이면 §12.1의 결정성이 깨진다. 그래서 운영에서 이 표를 채우는 것은 아직 아무도 아니며, 진단 3·4번은 **시험과 `harness`가 넣은 것만 본다.** 붙는 날 구독기가 `recordHeader`/`recordRejection`을 부르면 되고, 입력이 `MessageHeader`인 것이 그것을 가능하게 하는 경계다(`registry`가 `mimic`을 몰라도 된다).

    **(닫힘, §15.290)** 항목 290이 `registry`에 MQTT 관측 구독기를 두어 `recordHeader`의 운영 경로를 만들었다. `recordRejection`은 이미 HTTP 적재가 부르고 있다. 남는 것이 없어 닫는다.

35. **진단 2번이 선택 필드 차이를 본다(닫힘).** `profile_optional_field`가 없어 `optionalFields`가 `"not_tracked"`로 나갔다. §9.6의 상위 표면이 필수 선택 필드를 요구해 V7로 표를 만들었고, 진단 2번도 같은 표를 읽는다. 빈 배열로 내지 않았던 것이 옳았다 — 그랬으면 "선택 필드 차이가 없다"는 거짓말 위에서 축소가 승인됐을 것이다.
36. **세대 이력의 접기가 사유를 키에 넣는다.** `(robot_id, epoch, profile_ref, cause)`가 같으면 한 줄로 접히므로, **같은 사유로 두 번 일어난 같은 전이는 한 번으로 보인다.** 되풀이 횟수가 진단에 필요해지면 카운터를 더해야 하며, 지금은 마지막 시각만 남는다.

    **(닫힘, §15.283)** 항목 283이 세대 이력에 받은 횟수 칸을 더해 접힐 때마다 올리고 진단 3번이 그 수를 싣는다. 처음 받은 시각은 여전히 남지 않는다. 행 §15.36은 닫혔다.

37. **`capability_epoch_log.robot_id`에는 FK가 있고 `handshake_rejection.robot_id`에는 없다.** 후자는 등록되지 않은 기체에도 거절이 날아올 수 있고 그 사실 자체가 운영자가 알아야 할 것이기 때문이다. 대가는 오타 난 `robot_id`가 조용히 적재된다는 것이다.
38. **HTTP 표면에 인증이 없다 — 있는 것은 역할을 가르는 거친 문 둘이다.** 진단은 read-only지만 바인딩·거절·요구 문자열이 그대로 나가며, 배포에서 망 경계로 막는 것을 전제한다.

    쓰기에는 문이 둘이고 **토큰이 서로 다르다.** 적재(`ingest` 이하·`requirements`)는 [`IngestToken`], 조작(`operations` 이하)은 [`OperatorToken`]이다. **합치면 안 되는 이유가 있다** — 적재 토큰은 어댑터마다 배포되어 현장에 나가 있으므로, 한 토큰이면 **기체 하나가 운영자 조작을 수행할 수 있다.** 그 승격은 코드 어디에도 안 적히므로 안 보이며, `OperationsEndpointTest`의 *"적재 토큰으로는 조작하지 못한다"* 가 그것을 붙든다.

    **그래도 인증은 아니다.** §6.3이 신원과 접근을 범위 밖으로 두었고, 이것은 역할 둘을 가르는 공유 비밀일 뿐이다 — 누가 조작했는지는 여전히 `X-Actor`의 자기 신고이고 위조 가능하다(§15.3). 감사 로그가 부인방지 근거가 아니라 조사 단서인 이유다.

    원래 이 항목은 *"조작 API가 붙는 순간 이것은 한계가 아니라 결함이 된다"* 로 끝났다. 조작 API가 붙었고, 결함이 되지 않게 문을 나눴다.
39. **`:harness:test`가 이제 Docker를 요구한다.** 완료 기준 20의 카나리 시험이 진짜 레지스트리를 쓰기 때문이다(반으로 쪼개면 두 표면을 서로 비교하게 된다). `:registry:test`는 이미 그랬으므로 경계가 새로 생긴 것은 아니지만, **Docker 없는 로컬에서 돌던 모듈 하나가 줄었다.** 음성 하네스(§15.15)처럼 건너뛰기로 만들지 않은 것은 의도다 — 건너뛴 카나리는 초록으로 보이고, 완료 기준 20은 초록이 아니라 관측이어야 한다.
40. **원장의 두 관측선을 `mimic` 프로세스가 든다(닫힘). 태스크 쪽은 브로커가 아니다.** `mimic --registry <url> --ingest-token <t> [--site] [--fallback-dir]` 가 넷을 한 번에 조립한다 — 핸드셰이크 보고(§5.4), 태스크 적재, 각각의 파일 폴백, 기동 시 재적재. **토큰 없이 `--registry`만 주면 기동을 거부한다**: 적재가 전부 401을 낼 텐데 그러면 관측이 통째로 폴백으로 가면서 "붙었다"고 보고된다.

    태스크 전이는 §3.2가 규정한 `registry ⇠ 브로커` 구독이 **아니라** `mimic ⇢ registry` HTTP다(위 30번의 이유로 브로커를 안 붙였다). 브로커가 붙는 날 사라지는 것은 `HttpTaskObservations` 하나이며, 레지스트리의 적재 표면과 계약 타입 입력은 그대로 남아 구독기가 같은 서비스를 부른다. 남는 것: **레지스트리가 돌아온 것을 감지하는 장치는 없다** — 재적재의 계기는 프로세스 재기동뿐이다.
41. **관측선 신선도 창이 24시간 상수이고 판정 범위가 기체 단위다.** 사이트별·스트림별 설정이 없고 코드에서만 주입된다. 짧아서 생기는 오답은 "모른다"라 축소를 **막고**, 길어서 생기는 오답은 죽은 구독을 살아 있다고 보아 축소를 **연다** — 그래서 애매하면 짧은 쪽으로 잡았다. 야간 정지가 24시간을 넘는 라인에서는 매번 막히며, 그때 필요한 것은 창을 늘리는 것이 아니라 **기체가 `HIBERNATING`을 정직하게 보고하는 것**이다 — 그러면 창의 배수까지 면제된다. 판정은 전역 워터마크가 아니라 **그 능력을 제공하는 바인딩된 기체 각각**을 보며(V8의 `robot_liveness`), 하나라도 조용하면 사유에 그 `robot_id`를 담아 막는다.
42. **사이트 카탈로그가 HTTP로 선다(닫힘). retain 스트림은 아직 없다.** §9.6의 `GET /catalog?site=`가 능력 단위로 답하며 다섯을 다 싣는다 — 스킬 타입, 최소·최대 minor, 가용 기체 수, `deprecated_after`(두 축 중 이른 쪽), 필수 선택 필드(합집합). `picasso/{major}/{site}/site/catalog` retain 스트림은 브로커가 없어(위 30번) 만들지 않았고, §9.6이 적어 둔 대로 **상위가 폴링으로 대체한다.** 폴링은 능력 변경을 즉시 알리지 못하므로 상위의 반응이 폴링 주기만큼 늦는다.
43. **`skill_type_deprecation`에 쓰기 경로가 생겼다(닫힘).** `DeprecationService.announce`·`withdraw`가 그 자리이며 §8.5의 조작 열넷 중 *"계약 축 폐기 예고 설정"*이 이제 실제로 있다.

    **동기화 잡이 아니라 운영자 조작으로 만들었다.** 처음에는 *"`skill_type`이 읽기 전용인 것과 같은 이유로 계약 소유자가 채우는 것이 옳다"*고 적었는데, 그 유추가 틀렸다. §8.1이 `skill_type`을 읽기 전용으로 둔 이유는 **계약이 그 값을 소유**하기 때문이고 — 이름·major·`introduced_in_semver`는 전부 proto에서 나온다 — **`deprecated_after`는 proto에서 나오지 않는다.** 계약에 그것을 적을 자리가 없고(`SkillDeclaration.deprecated_after`는 프로파일 축이다), 있다 해도 "언제까지 쓸 수 있는가"는 릴리스 사실이 아니라 운영 판단이다. 표가 `announced_by`와 `note`를 든 것이 그 판단의 흔적이며, 동기화 잡은 그 둘에 채울 값이 없다.

    **예고는 여전히 게이트가 아니다**(§9.3). 이 조작이 여는 것은 `DEPRECATION_PUBLISHED` 하나뿐이고 축소를 여는 것은 두 조회다. `withdraw`가 있는 것은 §9.5가 `ANNOUNCE`의 가역성을 *"예고를 지우면 된다"*로 규정했기 때문이다 — 지울 방법이 없으면 그 표의 "가역"이 거짓말이 된다.
44. **드레인 관측이 전이 이벤트를 함께 받는다(닫힘).** 처음에는 `state` 스트림의 주기 발행(§7.2의 `publish_interval`)만 적재했고, 그래서 **주기보다 짧은 태스크는 비종착으로 한 번도 안 실렸다** — 픽스처의 `navigate_to`가 20초이고 발행 상한이 30초라 실제로 그랬다. 틀리는 방향이 축소를 **여는** 쪽이라 위 41번의 보수성과 반대였다. `event` 스트림의 `TaskTransition`을 함께 적재해 닫았고, **계약이 그 경로를 예정해 두었다** — `event.proto`가 `skill_type`을 싣는 이유를 *"registry가 이 이벤트를 구독해 task 테이블을 적재하며 드레인 판정이 스킬 단위로 서려면"*이라 적어 두었다. 스냅샷 경로도 남긴다: 이벤트가 유실돼도 다음 주기에 상태가 맞춰지므로 둘은 서로의 반대 실패를 메운다(이벤트 유실은 축소를 막고, 주기 누락은 축소를 연다).
45. **적재 실패가 폴백 파일에 남는다(닫힘).** §5.4가 *"보고 실패는 핸드셰이크 결과에 영향을 주지 않는다"*고 정했으므로 삼키는데, **삼킨 것을 버리면 그 사이의 전이가 통째로 사라진다** — `task` 표는 그 태스크를 못 본 채로 남고, 뒤늦게 스냅샷이 와도 이미 종착한 태스크는 다시 안 실려 드레인이 조용히 틀린다. `FallbackTaskObservations`가 실패한 관측을 JSONL로 남기고 **사유를 함께 적는다** — 적재가 멈추면 워터마크가 늙어 축소가 막히는데(§15.41의 안전한 실패) 막힌 이유가 어디에도 없으면 운영자는 원장이 아니라 엉뚱한 것을 뒤진다. 파일이 존재한다는 사실 자체가 신호이며, 적재가 잘 되면 안 생긴다.
46. **폴백 파일을 다시 밀어 넣는다(닫힘).** `FallbackReplay`가 두 형식(`FileHandshakeReporter`·`FileTaskObservations`)을 읽어 적재 표면으로 되민다. **커서를 안 둔다** — 어디까지 밀었는지를 따로 적으면 그것이 세 번째 진실이 되고, 어긋난 날 밀지 않은 구간이 조용히 생긴다. 대신 적재가 멱등이라는 성질(요구는 upsert, 태스크는 upsert + 종착 래치)에 기대며, **그 성질이 이 도구의 전제이므로 시험이 그것을 붙든다.** 파일은 안 지운다 — 데이터를 지우는 판단을 도구가 대신하면 적재가 실은 실패했는데 파일이 사라진 날 되돌릴 것이 없다.

    **부르는 쪽은 CLI가 기동할 때다**(위 40번). 프로세스가 다시 뜨는 것이 가장 흔한 복구 계기이며, 그보다 이른 계기 — 레지스트리가 살아난 순간을 감지하는 것 — 는 없다. 그래서 레지스트리가 돌아와도 `mimic`을 재기동하기 전까지 그 구간은 안 밀린다.
47. **관측선을 붙이기 전에는 축소가 열리지 않는다(보이게 만듦).** V8의 `robot_liveness`가 판정 앞에 서므로, 바인딩만 되고 한 번도 보고한 적 없는 기체가 하나라도 있으면 §9.3의 두 조회가 `NOT_OBSERVABLE`이다. **의도한 방향이다**(§15.41의 보수성) — 하지만 대가가 있다: 하트비트를 보내지 않는 어댑터를 붙이면 그 사이트의 축소 기능이 통째로 멈춘다.

    **막는 것은 그대로 두고 보이게 했다.** 진단 1번(`GET /diag/bindings`)이 기체마다 `liveness`를 함께 낸다 — `REPORTING` / `SILENT` / `HIBERNATING` / `DISCONNECTED` / `NEVER`. `NEVER`가 그 상태이며, 붙이는 순간 화면에 나오므로 축소를 시도할 때에야 아는 일이 없다. §9.7 ④의 `UNTESTED`와 같은 판단이다 — *"우리는 실물 검증을 아직 안 했다가 화면에 보여야 정직하다."* 신선도 창은 `RobotObservability.DEFAULT_FRESHNESS`를 **그대로 쓴다**: 두 곳이 갈라지면 화면이 `REPORTING`이라 적은 기체 때문에 축소가 막히고, 운영자는 원장이 아니라 엉뚱한 것을 뒤진다.
48. **`HIBERNATING`으로 들어간 뒤 죽은 어댑터가 창의 배수만큼 면제된다.** §4.7이 정의한 정상 침묵을 차단자로 만들지 않으려는 것인데, 그 구간의 오답은 축소를 **여는** 쪽이다. 그래서 배수를 3으로 잡았고 크게 늘리지 않는다.
49. **생존 관측이 폴백에 안 남는다.** 태스크 관측(§15.45)과 달리 되밀 수 없다 — 시각의 주인이 registry이므로 나중에 밀면 **죽은 기체를 살아 있다고 적는다.** 실패하면 버리고 다음 발행에서 다시 온다. 그동안 워터마크가 늙어 축소가 막히며, 그것이 안전한 실패다.
50. **두 경로의 major 판정 범위가 달랐다(닫힘).** `RegistryLedgerQuery`(CI)는 `skill_type@major`를 짚는데 `Preconditions`(운영)는 이름만 봤다. 그래서 `@2`로 옮기라고 예고해 놓고 `@1`의 제거가 **옮긴 쪽 때문에** 막혔다. 개수 세는 것을 `LedgerService.activeConsumerCount(skill, major)`·`inflightTaskCount(skill, major)`로 내려 **두 경로가 공유한다** — 관측선(`RobotObservability`)에 이어 두 번째다. `major`가 없으면 조용히 "안 가림"으로 접지 않고 **던진다**: 접으면 이 한계가 다시 열리고 열린 것이 안 보인다. `DEPRECATION_PUBLISHED`도 함께 닫았다 — `skill_type_deprecation`의 PK가 `skill_type_id`이고 그것이 `(name, major)` 단위이므로 표는 이미 그 해상도를 갖고 있었다. 이름만 보면 `@2`에 낸 예고가 `@1`의 제거를 열어 준다: **아직 아무도 옮기라고 듣지 못한 판을 지우는 것이다.**
51. **펌웨어 대조가 문자열 동일성이다.** 벤더마다 표기가 달라 `4.1`과 `4.1.0`을 다르게 본다. 의미 비교는 벤더별 파서를 요구하며 그것은 어댑터 안의 일이다. 다르면 띄우고 판단은 사람이 한다. **막지도 않는다** — 어느 정도 차이까지 허용하는가는 정책이고 정책 저장소가 비목표다(§1.3).
52. **실물 기종의 능력 프로파일은 아직 만들 수 없다.** 처음에는 이 항목을 *"`derived_from`이 참조 프로파일의 합성 값이라 진단 7번이 아직 아무것도 안 잡는다"*고 적었는데, 실제로 만들어 보니 이유가 그보다 앞에 있었다.

    **§2.3 수준의 조사로는 프로파일을 채울 수 없다.** 스키마가 `skills`를 `minItems: 1`로, `protocol_limits`와 `publish_interval`을 필수로 요구하는데 §2.3의 대조표는 프로토콜 한계를 세 기종 모두 ❌로 적었고 스킬 어휘는 아예 조사하지 않았다. 그리고 "이 로봇이 우리 계약의 `pick_place`를 지원하는가"는 벤더 문서에서 직접 나오는 값이 아니라 **어댑터 설계 판단**이다. 억지로 채우면 §15.7이 방어책으로 든 *"벤더 문서에서 파생시키는 규율"*이 거짓말이 된다.

    그래서 **조사를 데이터로만 옮겼다** — `profile/vendors/`(스키마는 `vendor-survey.schema.json`)에 셋을 두고 `VendorSurveyTest`가 읽는다. 능력 프로파일이 아니므로 하네스가 `profile/profiles/`에 거는 제약(§15.27)을 안 받고, 그래서 못 채우는 항목이 `coverage.*.level = NONE`으로 **정직하게 남는다.** 이 문서들이 붙드는 것은 §4의 결정들이 선 근거다 — `Support` 3값(Spot `UNKNOWN` vs Digit `NO`), 래치 불변식(Digit만 `terminal_latches: NO`), §4.9의 배타 제어(셋 다 `YES`), 그리고 **프로토콜 한계를 주는 실물이 하나도 없다는 것.**

    **2026-09-08에 첫 실물 프로파일이 섰다** — `profile/profiles/unitree-g1.json`. 어댑터 설계가 먼저라던 것이 맞았고, 설계하면서 스킬 어휘(`move_relative`)와 프로토콜 한계와 발행 주기가 정해졌다.

    **`derived_from.software_version`은 채워졌으나 펌웨어가 아니다.** `unitree_sdk2_python master (2026-09-08)` 이며, 우리가 본 것이 SDK 소스이지 어느 기체의 펌웨어가 아니기 때문이다. 그래서 진단 7번의 대조는 어떤 실물 G1과도 불일치를 낼 것이고 **그것이 옳은 거동이다**(§15.51은 띄우기만 하고 막지 않는다). 실물을 한 번도 안 봤다는 사실이 화면에 남는다.

    못 채운 것이 남는다. `min_value`·`max_value`가 비어 있다 — URDF가 주는 것은 조인트 한계인데 `move_relative`의 파라미터는 기저 속도이고 그 상한은 벤더가 어디에도 선언하지 않는다. `failure_modes`도 비어 있다 — 스키마가 `rate`를 필수로 요구하는데 **우리는 어떤 비율도 모르고**, 지어낸 비율은 `mimic`의 추첨을 지나 시험 결과에 직접 들어간다. 비어 있는 것이 기록이며 그 사유가 출처 문서에 있다.

    조사 문서도 이 과정에서 하나 고쳐졌다. `parameter_ranges`를 `FULL`이라 적었던 것이 `PARTIAL`이 됐다 — **있는 근거와 필요한 근거가 달랐다.**
53. **생존 보고가 브로커 구독이 아니다.** §4.7의 `connection` 스트림이 같은 사실을 나르지만 브로커를 안 붙였으므로(위 30번) `mimic ⇢ registry` HTTP로 민다. 브로커가 붙는 날 사라지는 것은 `HttpLiveness` 하나이며, 그때 구독기는 `ConnectionMessage`만 갖고 `robot_software`는 비운 채 부른다 — `COALESCE`가 이미 읽은 값을 지키므로 원장이 퇴행하지는 않지만, **펌웨어를 어느 경로로 받을지는 그때 다시 정해야 한다.**
54. **`profile/vendors/`는 게이트 검사가 아니라 시험이 붙든다.** `VendorSurveyTest`가 스키마 검증과 §2.3의 사실 여섯을 단언하며 CI가 그것을 돌린다.

    **그 시험이 태스크 입력 없이 먼저 들어왔다(`431649f`).** `gate/build.gradle.kts`가 `profile/vendors`를 선언하지 않아 조사 문서를 고쳐도 `:gate:test`가 UP-TO-DATE로 넘어가 초록이 났다 — 그 파일의 주석이 *"이 저장소가 같은 방식으로 두 번 물렸다"* 고 적어 둔 바로 그것이며 세 번째다. 2026-09-08에 `profile/vendors`와 `profile/provenance`를 함께 배선했다. 게이트 검사를 열로 늘리지 않은 것은 §11.2의 "검사 아홉"과 그 음성 케이스 표가 함께 커지기 때문이고, 이 문서들은 배포물이 아니라 **설계 근거의 기록**이라 파괴 검사의 대상이 아니다.

55. **어댑터를 시뮬레이터에 붙여 돌리지 못한다.** 조사 문서가 처음에 *"이 기종만 공식 시뮬레이터가 SDK2 인터페이스를 그대로 노출한다"* 고 적었고 그것이 이 기종을 고른 이유였는데, **절반만 참이었다.**

    | 사실 | 출처 |
    |---|---|
    | `unitree_mujoco`는 **저수준만** 흉내낸다 — `LowCmd_`/`LowState_` | README: *"Current version only supports low-level development"* |
    | 스킬이 올라탈 `sport` 서비스는 `ai_sport`가 도는 실물에서만 답한다 | 같은 문서 |
    | CycloneDDS에 JVM 바인딩이 없다(C·C++·Python뿐) | CycloneDDS 문서 |
    | 시뮬레이터가 Ubuntu 전용이다 | README 설치 절차 |

    그래서 `G1Link.sport`가 **널일 수 있는 타입**이고, 널이면 어댑터가 태스크를 거절한다. 받아 놓고 아무것도 안 하는 것이 가장 나쁜 결과이며 그 상태는 초록으로 보인다.

    이것을 닫으려면 실물이거나, JVM이 그 wire에 서는 길(OpenDDS-Java RTPS 상호운용)을 뚫어야 한다. 후자는 그 자체가 미검증이고 RTPS 판이 다르다(OpenDDS 2.4 · CycloneDDS 2.1).

56. **어댑터의 남쪽 포트가 실물과 갈릴 수 있다.** `G1Link`는 SDK 소스를 읽고 만든 것이고 실제 wire와 같은지는 붙여 봐야 안다. **메서드 이름을 SDK 그대로 둔 것**이 그 대조를 싸게 하려는 것이다 — 우리 어휘로 옮기면 다음 조사에서 한 줄씩 맞대 보지 못한다.

    `FsmProfile.start`에 기본값을 두지 않은 것도 같은 자리다. 같은 뜻의 상태가 기체마다 다른 번호이며(500 · 501 · 801) 어느 것이 맞는지는 우리가 아는 것이 아니다. 기본값을 두면 잘못된 기체에서 조용히 다른 상태로 들어간다.

57. **출처 문서는 선언이 어디서 왔는지만 본다.** `profile/provenance/`와 `ProfileProvenanceTest`가 §15.7의 규율 — *"벤더 문서에서 파생시킨다"* — 을 약속에서 검사로 바꿨다. 조사가 `level: NONE`이라 적은 항목을 프로파일이 `ROBOT`·`VENDOR_DOC` 출처라 주장하면 실패한다.

    **값을 채우는 것은 막지 않는다.** 벤더가 안 주는 값을 어댑터가 정하는 것은 정당하고 불가피하다(§9.7). 막는 것은 어댑터가 정한 값이 로봇의 선언인 척하는 것이다.

    **선언대로 로봇이 행동하는지는 안 본다.** 그것이 §9.7 ④이고 C-3이며 열려 있다. 그리고 위 54번과 같은 이유로 게이트 검사가 아니라 시험이 붙든다.

58. **실물 기종이 공통 요구 집합을 못 만족한다.** §15.27이 `profile/profiles/`의 여섯 제약을 적으며 *"이 우리가 좁아 보이면 그것이 맞다"* 고 했는데, 첫 실물이 1번부터 못 넘었다 — `navigate_to@^1.0`이 없다.

    **면제를 `profile/common-set-exemptions.json`에 열거한다.** 조건으로 유도하면(예: *"`navigate_to`를 선언한 기종만 본다"*) `humanoid-a`에서 그 스킬을 지워도 스위트가 초록으로 남고 완료 기준 11이 조용히 공허해진다. 반대 방향은 `AllModelsTest`가 막는다 — **면제된 기종이 정말로 못 만족하는지까지 확인하므로** 통과할 수 있는 기종을 목록에 넣어 숨길 수 없다.

    대가는 `profile/profiles/`가 이제 두 가지를 담는다는 것이다 — 스위트가 도는 기종과 안 도는 기종. 면제 파일이 그 구분의 정본이며, 그것을 안 읽으면 디렉터리만 보고 잘못 짐작하게 된다.

59. **계약 카탈로그에 아무 실물도 선언하지 않는 스킬이 하나 있다.** `inspect`다. (원래 *"둘 있다 — `pick_place`와 `inspect`"* 였고, 2026-09-08에 Digit이 `pick_place`를 선언하면서 하나로 줄었다. 아래 65번의 정정에서 나왔다.) 게이트 4번은 **선언된** 스킬만 양방향으로 대조하므로 쓰이지 않는 카탈로그 항목을 잡지 않고, 잡을 이유도 없다 — 어휘가 실물보다 앞서는 것 자체는 정상이다.

    조용해지지 않게 하는 것이 `profile/distance/`다. 실물마다 **모든** 카탈로그 스킬에 판정과 근거를 요구하므로, 안 쓰이는 스킬이 *"아무도 안 쓴다"* 가 아니라 **"누구에게 무엇이 없어서 못 쓴다"** 로 남는다. `VocabularyDistanceTest`가 `ContractIndex`로 카탈로그를 훑어 빠진 측정을 잡는다.

60. **시맨틱 결속 층에 주인이 없다**([ADR 34](../../adr/0034-semantic-binding-unowned.md)). 계약의 파라미터 넷이 사이트 수준의 이름인데(`location`·`object_id`·`destination`·`target`) 벤더는 녹화된 웨이포인트 id·픽셀·3D 점·카메라 이름으로 말한다. `navigate_to`만 맞는 것은 GraphNav이 그 결속을 이미 갖고 있어서다.

    ADR 34가 후보 셋 중 어댑터를 뺐고, [ADR 35](../../adr/0035-site-names-live-in-the-robot.md)가 나머지를 정했다 — **계약은 사이트의 이름을 나르고 사이트가 그것을 로봇 안에 등록한다.**

    *"그래서 `pick_place`는 지금 어느 경로로도 서지 않는다"* 고 적었던 것은 **틀렸다.** Digit이 `action-pick{object: ObjectSelector}`·`action-place{reference_frame: ObjectSelector}`로 그것을 팔며 셋 중 그 기종에서 선다. 남는 것은 Spot·G1이고 이유는 그대로다 — 픽셀·3D 점, 또는 아무것도 없음.

61. **거리 측정은 관측이 아니라 판단이다.** `profile/vendors/`는 벤더 문서의 전사이고 `profile/provenance/`는 프로파일 한 장의 계보인데, `profile/distance/`는 **계약 쪽에서 내리는 판정**이다 — *"이 벤더 원시로 우리 스킬을 정직하게 들 수 있는가."* 다른 사람이 다르게 잴 수 있고, 그래서 `evidence`가 필수다.

    `reachable: YES`가 프로파일 선언과 양방향으로 묶여 있는 것이 이 판단의 유일한 기계적 제동이다. 판단이 느슨해지면 선언이 함께 틀리고, 그때는 하네스가 잡는다.

62. **Spot 어댑터도 붙여 보지 못했다.** 남쪽 포트가 미검증인 것은 G1과 같으나(§15.56) **이유가 다르다.** G1은 JVM이 그 wire에 설 길 자체가 없었다(CycloneDDS에 Java 바인딩 없음). Spot은 gRPC라 기술적 장벽이 없고 — 막는 것은 **실물과 라이선스**다. `20191101-BDSDK-SL` §2(c)가 BD 하드웨어 전용이므로 시뮬레이터를 만들어 붙이는 길도 벤더가 안 판다.

    그래서 §15.55의 *"시뮬레이터로 적합성을 못 본다"* 가 두 기종에서 서로 다른 이유로 참이며, **조사한 셋 어디에도 우리에게 필요한 API 시뮬레이터가 없다**는 것이 지금까지의 결론이다.

65. **Digit 측정을 한 번 통째로 다시 했다 — 근거 등급을 적어 두고도 그 위에 판정을 얹었다.**

    **아래는 그 실수의 기록이며, 이어지는 문단이 원래 적었던 것이다.** 2026-09-08 같은 날 벤더 SDK 원문(`agility/messages/json.py`, 릴리스 `2021.06.01`)을 찾아 전수 대조했고 **판정 셋이 뒤집혔다** — `navigate_to` PARTIAL→YES, `pick_place` NO→YES, 계층 COMMAND→MISSION. `cancel_support`도 NO→YES였다.

    원인이 하나다. 제3자 래퍼가 벤더 API의 **부분집합**이라 `action-duration`·`action-pick`·`action-place`·`remove-action`·`ObjectSelector.name`을 안 썼고, 안 쓰인 것을 **없는 것으로 읽었다.** 근거가 약하면 `NO`가 아니라 `UNKNOWN`이어야 했다 — §7.2의 `Support` 3값이 말하는 바로 그것이고, **조사 문서에서는 지키던 규율을 거리 문서에서 안 지켰다.**

    `VendorSurveyTest`의 시험 하나가 이 교훈을 든다(*"1차 근거를 읽자 취소 판정이 둘이나 뒤집혔다"*). Spot은 `UNKNOWN`에서, Digit은 `NO`에서 각각 `YES`로 갔다 — **접는 방향이 달랐을 뿐 둘 다 1차 근거를 안 읽어 생긴 것이다.**

    남는 한계는 릴리스가 2021년판이라는 것이다. **"있다"는 확실하고 "없다"는 그 시점 기준이며**, `inspect`의 `NO`가 그 영향을 가장 크게 받는다.

    ---

    *(아래는 정정 전 원문이다.)* **Digit 측정의 근거 등급이 다른 둘보다 낮다.** Spot은 벤더의 공개 proto 원문을 읽었고 G1은 공개 SDK 소스를 읽었는데, Digit은 그러지 못했다 — `docs.agilityrobotics.com`이 닿지 않고 공식 SDK가 공개 저장소에 없다. 대신 **원시 JSON을 그대로 쓰는 제3자 코드**(서브프로토콜 `json-v1-agility`)와 2026-09-05 조사(매뉴얼 직접 인용 포함)를 썼다.

    그래서 이 기종에 대해 *"없다"* 고 적은 것 중 일부는 ***"공개된 것에서 못 찾았다"***이다. 둘을 접으면 안 되는 것이 §7.2의 `Support` 3값과 같은 이유이며, 거리 문서의 `evidence`가 그 구분을 진다. 특히 `navigate_to`의 `PARTIAL`은 확인만 되면 `YES`로 갈 수 있는 자리다.

66. **철회 — `move_relative`의 벤더 중립 근거는 틀리지 않았다.** 한나절 동안 이 항목은 *"근거의 절반이 틀렸다: Digit의 `action-move`는 지속시간을 안 받는다"* 였고, 그에 맞춰 어댑터에 **정지 워치독**까지 만들었다. 둘 다 잘못이었다.

    `ActionDuration{action, duration, after_success}`가 아무 액션이나 감싸며 **로봇이 집행한다.** 지속시간이 필드가 아니라 **합성**일 뿐이고, 셋 다 그 값을 받는다. 어댑터가 시계를 들 이유가 없고 실패 방향도 다른 둘과 같다 — 워치독은 코드와 시험에서 걷어냈다.

    **남는 것은 회귀 방어 하나다.** `어댑터가 시계를 들지 않는다` 시험이 그 자리이며, 지속시간이 지나도 상태 스트림이 `success`를 주기 전에는 성공으로 안 적는 것을 붙든다. 없던 위험을 만들어 낸 코드가 다시 기어들어오면 거기서 빨개진다.

    `skill_catalog.proto`의 주석도 원래대로 되돌렸다 — 다만 *"셋 다 갖는다"*가 **필드로 셋, 합성으로 하나**라는 것은 적어 두었다.

67. **`vendor_layer`의 가운데 값(`COMMAND`)을 지금 쓰는 기종이 없다.** 한나절 동안 Digit이 그 자리라고 적었는데, 벤더 SDK를 읽으니 `add-sequential-actions`·`replace-action`·`remove-action`·`get-execution-state`와 합성 다섯과 지속 세션이 있었다. **액션이 별도 층으로 서 있고 생명주기 조작이 붙어 있으므로 `MISSION`이다** — 없는 것은 일시정지 하나이며 그것은 층의 부재가 아니라 능력의 부재다.

    그래서 지금 분포는 G1 `NONE` · Digit `MISSION` · Spot `MISSION`이고, **가운데는 비어 있다.** 값을 지우지 않는 이유는 명령 계층에 상태만 얹은 기종이 실재할 수 있어서인데, **아직 못 봤다는 것을 여기 적어 둔다** — 안 적으면 다음 사람이 그 값을 근거 있는 분류로 읽는다.

    이 열거의 쓸모는 기종을 분류하는 것이 아니라 *계약이 어느 층에 있는가*를 재는 것이다. 그 답은 세 기종에서 그대로다 — **계약은 명령 계층이 아니라 그 위에 있다.**

68. **사이트 이름을 로봇에 등록했는지가 이제 보인다(닫힘).**([ADR 35](../../adr/0035-site-names-live-in-the-robot.md)). 그 결정이 §9.7의 여섯 단계 옆에 새 운영 단계를 하나 만든다 — *"사이트 이름을 이 기체에 등록했는가."* 안 하고 바인딩하면 소비자가 `dock-3`을 보냈을 때 **로봇이 모르는 이름으로 실패**하고, 그 실패는 계약 위반처럼 보이지 원인처럼 보이지 않는다.

    **V10이 그 상태를 만들었다.** `robot_binding.site_names_registered_at`·`_by`가 그 자리이며, 진단 1번이 기체마다 `siteNames`(`NOT_REQUIRED`/`UNREGISTERED`/`REGISTERED`)와 `siteNameKeys`를 함께 낸다. **막지 않고 보이게 한다** — §9.7 ④의 `UNTESTED`, §15.47의 `NEVER`와 같은 판단이다.

    **`NOT_REQUIRED`를 따로 둔 것이 요점이다.** 접으면 이름을 쓸 일이 없는 기종(G1처럼 `move_relative`만 드는 경우)이 영원히 *"안 했다"*로 보이고, 그러면 화면이 언제나 빨개서 아무도 안 본다.

    미뤘던 이유는 *"무엇을 등록했다고 적을 것인가"* 였다. 집합이 기종마다 다르므로(Spot은 장소, Digit은 장소와 집을 물체와 놓을 곳, G1은 없음) 불리언 하나로는 *"등록했다"*가 기종마다 다른 것을 뜻하게 된다.

    **답은 유도였다.** 계약이 `is_site_reference`로 어느 파라미터가 사이트 이름인지 말하고(`skill_type_param.site_reference`), 프로파일이 어느 스킬을 드는지 말한다. 둘을 곱하면 그 바인딩이 알아야 하는 이름들이 나오고 **손으로 적는 목록이 없다** — 프로파일이 스킬을 하나 더하면 등록 대상이 저절로 는다.

    **어느 파라미터가 사이트 이름인지는 계약이 소유한다.** `registry`가 그 목록을 두 번째로 적으면 스킬을 더하는 날 갈리며, `TaskStates`를 계약에 둔 것과 같은 이유다. 값 타입으로 유도할 수도 없다 — `STRING`이 전부 사이트 이름은 아니다.

    `skill_type_param`은 V1이 *"요구하는 기준이 생길 때"* 만들기로 미뤄 둔 표였고, 이것이 그 기준이었다. `skill_type`과 같이 읽기 전용이며 `SkillTypeSync`가 채운다(§8.1).

    **HTTP 표면이 붙었다.** `POST /operations/site-names?robot=`가 기록하고 `GET`이 미리 본다. §8.5의 조작이 열다섯이 됐고, **적재와 다른 문·다른 토큰**이다(§15.38). 답이 셋으로 갈린다 — 기록됨(200) / 등록할 것이 없음(409) / 활성 바인딩 없음(404). 불리언 하나로 접으면 운영자가 왜 안 됐는지 모른 채 재시도한다.

    **그 질의가 이제 계약에 있다.** `SkillService.GetKnownSiteNames`가 그것이며, 기체가 아는 이름의 목록과 *"호스팅 못 한다"*를 구별해 답한다. 답은 생존 보고에 요약으로 실려 온다(§15.72).

    그래서 상태가 **다섯**이 됐다 — `NOT_REQUIRED` / `UNREGISTERED` / `CLAIMED`(사람은 했다는데 기체가 아직 답한 적 없다) / `CONFIRMED`(둘 다) / **`CONTRADICTED`(사람은 했다는데 기체는 아는 이름이 없다)**. 마지막이 이 확장의 이유이며, 질의가 생기기 전까지는 **그 상태를 표현할 수단 자체가 없었다.**

    **잡는 것과 못 잡는 것이 갈린다.** 통째로 안 했거나 엉뚱한 기체에 했으면 개수가 0이라 잡힌다. **이름 하나를 오타 냈으면 개수가 같아 못 잡는다** — 잡으려면 무엇을 등록했어야 하는지의 목록이 필요하고, 그 목록을 `registry`가 드는 순간 ADR 35의 결정(이름의 주인은 사이트다)이 깨진다. 그래서 레지스트리는 **개수와 "못 함"만** 받고 이름은 안 받는다.

69. **근거 등급이 이제 필드이고 시험이 강제한다 — 그리고 그것으로도 못 막는 것이 있다.**

    위 65번의 실수(근거 등급을 산문에 적어 두고 그 위에 `NO`를 얹었다)를 되풀이하지 않으려고 `evidence_grade`를 조사·거리 스키마에 넣었다. 값이 넷이다 — `VENDOR_PRIMARY`(벤더가 배포한 기계가독 정의) · `VENDOR_DOC`(벤더 문서 산문) · `THIRD_PARTY`(벤더 아닌 코드·글) · `INFERRED`(짐작).

    **규칙 하나다: 부재를 단정하려면 앞의 둘 중 하나여야 한다.** 거리의 `reachable: NO`와 조사의 `coverage.level: NONE`·3값 필드의 `NO`가 그 대상이며, 등급이 낮으면 새로 생긴 `UNKNOWN`까지만 적을 수 있다. 문서 단위 기본값에 항목이 덮어쓸 수 있다 — 실제로 Digit의 `model_identity`·`parameter_ranges`는 매뉴얼에서 왔으므로 `VENDOR_DOC`으로 낮춰 두었다.

    **못 막는 것은 등급을 잘못 적는 것이다.** `VENDOR_PRIMARY`라고 써 놓고 실제로는 안 읽으면 아무도 못 잡는다 — §15.6·§15.11의 문자열 검사와 같은 성질이며, 이 장치도 우회를 막는 것이 아니라 **실수를 막는다.** 다만 이번 실수는 우회가 아니라 정확히 그 실수였으므로 값이 있다.

    그리고 **`INFERRED`를 쓰는 문서가 지금 하나도 없다.** 그것이 좋은 상태는 아니다 — 넷 중 둘만 쓰이면 다음 사람이 나머지 둘을 죽은 값으로 읽고, 그러면 짐작을 `VENDOR_DOC`으로 올려 적게 된다. 조사 문서의 `UNKNOWN`에 같은 방어를 걸어 둔 것(*"아직 모르는 것이 남아 있다"*)처럼 여기도 필요해지면 그때 건다.

70. **범위는 옮겼는데 층은 아직 설계되지 않았다 — 그리고 그 중간 상태가 비목표보다 위험하다.**

    2026-09-08에 §1.2의 범위 표에 행이 하나 늘고 §1.3의 비목표에서 한 줄이 빠지고 §9.6이 다시 쓰였다. 그것으로 정해진 것은 **어디에 두는가**뿐이고, 그 층을 실제로 세우는 결정은 다섯 자리에서 비어 있다.

    | 비어 있는 자리 | 무엇이 없나 |
    |---|---|
    | §3.1 모듈 | `acl-core`·`acl-{system}`이 없다. `settings.gradle.kts`에도 없다 |
    | §3.2 의존 규칙 | 방향이 미정이다. `acl-core → contracts`만으로 되는지, `profile-model`이 필요한지, `registry`를 알아도 되는지 |
    | §11.2 게이트 | 쌍둥이 검사(코어에 시스템 문자열 금지)가 없다. 10번으로 세울지 7번의 대상 목록을 넓힐지도 정하지 않았다 |
    | §12.2 · §13 | 완료 기준에 행이 없고, 어느 단계에서 만드는지도 없다 |
    | ADR | 결정 기록이 없다. §9.6의 정정 문단은 근거를 적은 것이지 결정 기록이 아니다 |

    **비목표였을 때가 오히려 안전한 상태였다.** 비목표는 명시적 부재이므로 다음 사람이 "없다"로 읽는다. 지금은 범위 표에 행이 있고 §9.6에 배치가 적혀 있어서 **"있다"로 읽히는데 코드도 검사도 없다.** ADR 35가 *"(a)를 안 정하면 (b)가 공허하다"* 로 지적한 것과 같은 형태이며, 이 문서가 §11.2의 4번과 §12.2의 10번으로 막으려는 것 — 선언과 구현이 갈라지는 것 — 을 문서 자신이 하고 있는 셈이다.

    ADR 31·32가 **결정이 비어 있다는 사실 자체**를 설계 갭 점검에서 찾아 §14에 행을 더한 전례가 있다. 이 항목은 그 점검을 미리 적어 둔 것이고, **닫는 것은 위 다섯 자리를 채우는 일이지 이 문단을 지우는 일이 아니다.**

72. **사이트 이름 보고가 생존 보고에 얹혀 온다.** `GetKnownSiteNames`의 답이 별도 적재 경로가 아니라 `POST /ingest/liveness`의 쿼리로 실린다 — `robot_software`가 같은 길로 가는 것과 같은 자리이며(§15.53) 이유도 같다: 둘 다 *"기체가 자기에 대해 보고하는 사실"*이고, 경로를 늘리면 트리거와 폴백을 한 벌 더 관리해야 한다.

    **대가가 둘이다.** ① 보고 시점이 연결 전이·상태 발행에 묶인다 — 사이트 이름은 그 사이에도 바뀔 수 있고, 바뀐 것이 다음 발행까지 안 온다. ② 브로커가 붙는 날(§15.30) `HttpLiveness`가 사라지면 이 값도 함께 갈 곳을 잃는다. §15.53이 `robot_software`에 대해 적어 둔 것이 그대로 하나 더 늘었다.

    **둘이 함께 와야 답으로 친다.** `site_names_unsupported`와 `site_names_count` 중 하나만 오면 잘린 보고이므로 무시한다 — 반쪽으로 상태를 올리면 *"기체가 답했다"*가 거짓이 된다. 그리고 **안 물어본 보고가 이미 받은 답을 지우지 않는다**(`COALESCE`): 옛 어댑터가 섞여 도는 동안 그 보고마다 상태가 되돌아가면 확인이 영원히 안 선다.

73. **~~`GetKnownSiteNames`를 실물 어댑터 셋 중 아무도 구현하지 않았다.~~ 닫힘 (2026-09-08).** 셋 다 `knownSiteNames()`를 든다. 각자 다른 곳에 묻는다 — Spot은 `GraphNavService.DownloadGraph`의 `Waypoint.annotations.name`, Digit은 `notify-objects`로 id를 받고 `get-object`로 이름을 하나씩(**질의가 두 단계인 유일한 기종**이며 매뉴얼이 그 흐름을 직접 적어 두었다), G1은 세계 모델도 지도도 없어 `Unsupported`.

    **답이 셋으로 갈린다** — `Unsupported`(둘 자리가 없다) / `Known`(물어봤다, 빈 목록 포함) / `Unavailable`(**물어보지 못했다**). 마지막이 어휘의 이유다: 그래프 서비스가 죽었을 때 0으로 답하면 관측 실패가 사람의 태만처럼 보이고 원장이 `CONTRADICTED`를 띄운다 — 사실은 아무것도 관측되지 않았는데. 계약의 응답에는 그 자리가 없으므로 전송이 오류로 옮긴다. Digit에서는 **부분 실패**까지 이 자리로 접는다: 객체 하나를 못 읽었을 때 나머지로 답하면 개수가 작게 올라가 같은 거짓 경보가 된다.

    **닫으면서 Spot 어댑터의 오독 하나가 같이 드러났다.** `navigate_to`가 계약의 `location`을 `NavigateToRequest.destination_waypoint_id`로 **그대로** 넘기고 있었다. 벤더 원문은 둘을 나눈다 — `Waypoint.id`는 *"Unique across all maps"* 인 **로봇 생성 id**이고, 사람이 붙인 이름은 `Waypoint.Annotations.name`(*"Human-friendly name … For example, `Kitchen Fridge`"*)이다. 그대로 넘기면 **상위 시스템이 Spot이 만든 id를 들어야 하고 그것이 A-1 위반**이다. 어댑터가 매 호출 그래프를 받아 옮기도록 고쳤고, 모르는 이름과 동명 둘을 각각 거절한다(`SITE_NAME_UNKNOWN`·`SITE_NAME_AMBIGUOUS`). ADR 35의 결정 1과 근거 2, `profile/distance/spot-arm.json`, 그리고 뒤집힌 시험 하나가 함께 고쳐졌다.

    **ADR 34를 어긴 것이 아니다.** 표를 **드는 것**과 로봇의 표에 **묻는 것**은 다르다 — 어댑터는 아무것도 저장하지 않고, 대응을 정한 것도 지도를 녹화한 사이트다. 앞 판이 그 둘을 가르지 않아 *"통과시키는 것"* 을 결정의 내용으로 적었다.

    **남는 것.** ① 이름 조회 RPC가 없어 매번 그래프 **전체**를 받는다 — 캐시하면 사이트가 지도를 다시 올렸을 때 어댑터가 옛 이름으로 로봇을 몬다. ② `annotations.name`은 유일하지 않다(사람이 적는 문자열이다). ③ **셋 다 실물에 붙여 보지 못했다**(§9.7 ④·C-3) — 페이크를 상대로만 돈다. ④ 어댑터에는 gRPC 서버가 없으므로 이 답이 계약의 RPC로 나가는 배선은 아직 없다.

74. **벤더 원문을 짚은 것이 이제 시험이다 — 다만 이름까지만이다.** 남쪽 포트의 각 멤버가 `@VendorSurface`로 벤더의 이름을 짚고, 어댑터마다 `*VendorSurfaceTest`가 그것을 `src/test/resources/vendor-manifest.txt`와 대조한다. 매니페스트는 `tools/vendor-manifest/`의 추출기가 **벤더 원문에서 뽑은 것**이며 원본의 sha256이 헤더에 함께 적힌다.

    **원문은 저장소에 안 들어온다.** `adapter-boston-dynamics-spot/build.gradle.kts`가 적어 둔 *"벤더 SDK가 여기 없고 … SDK 없이 컴파일되고 시험이 돈다"*가 그대로 유지되며, 들어오는 것은 이름과 해시뿐이다(ADR 31의 격리를 다시 논하지 않아도 된다).

    **왜 생겼나 — 같은 층에서 사고가 셋 났다.** 전부 사람이 벤더 문서를 읽고 KDoc에 옮기는 단계였고 틀려도 아무것도 빨개지지 않았다: ① Digit 측정이 제3자 래퍼 때문에 통째로 뒤집힘, ② Spot이 `Waypoint.id`(로봇 생성)와 `annotations.name`(사람이 붙임)을 접음, ③ **Digit `ActionStatus`에 `failure`가 없어 어댑터가 로봇이 신고한 실패에 도달할 수 없었다** — 권한을 잃을 때만 태스크가 죽었고 액션이 막히면 영원히 `RUNNING`이었다. 셋째는 이 검사를 붙이는 중에 나왔고 코드와 시험을 함께 고쳤다. 같이 나온 작은 것 둘: Spot의 `GetStateResponse.Status`는 없는 이름이고(`State.status`다), `MissionStatus`에 벤더의 `STATUS_UNKNOWN`이 빠져 있었다.

    **검사가 둘인 것이 요점이다** — 짚은 이름이 매니페스트에 있는가, 그리고 **멤버마다 짚은 것이 있는가**. 앞의 것만 있으면 애너테이션을 안 붙이는 것으로 언제나 통과한다.

    **원본이 기종마다 다르고 그 차이가 곧 측정 결과다.** Spot은 `.proto` 12개로 메시지·필드·열거값·RPC를 전부 덮는다. Digit은 **원본이 둘**이다 — SDK `json.py`는 *보내는* 메시지의 빌더만 들어서 우리가 *읽는* `privileges`·`action-status-changed`는 매뉴얼이 1차 출처다. G1은 스키마 언어가 아예 없어 원본이 셋으로 흩어진다 — API ID 상수, 요청 본문의 JSON 키(헤더의 `Jsonize*`), IDL 필드. **응답의 모양은 어디에도 없다.**

    > **정정 (2026-09-08).** 이 문단은 *"인자 이름은 못 덮는다 — 스키마 언어가 없다"* 고 적혀 있었고 틀렸다. 벤더는 `json["velocity"]`·`json["duration"]` 으로 주고 있었고 **우리 추출기가 `const` 줄만 읽고 있었다.** 도구의 한계를 벤더의 부재로 적은 것이며 §15.65 가 경고한 그 실수다. 추출기를 고쳐 `setVelocity` 가 그 둘을 짚는다.

    **그래서 "못 덮는다" 를 세 종류로 갈라 적는다** — ① 추출기가 못 읽는다(고칠 수 있다) ② 벤더가 타입으로 안 적었다(Digit 의 일반 오류 봉투가 그것이고, 실물이나 시뮬레이터에서 받아 봐야 안다) ③ 검사의 성질(있는 이름이면 통과, 거동, 낡음). **①을 ②로 적는 것이 이 저장소가 반복해 물린 실수다.**

    **못 하는 것 넷.** ① **거동을 안 본다** — 그 메시지를 보냈을 때 로봇이 무엇을 하는지는 여전히 §9.7 ④·C-3이다. ② **있는 이름이면 통과한다**: `Waypoint.id` 대신 `Waypoint.snapshot_id`를 짚어도 둘 다 실재하므로 초록이다(주입으로 확인했고, 이 검사가 못 잡는 자리다). ③ **낡은 매니페스트는 낡은 코드와 사이좋게 초록이다** — 헤더의 릴리스와 해시가 그것을 드러내는 유일한 장치이고, 갱신은 사람이 절차대로 돌린다. ④ 검사할 타입 목록이 시험에 **손으로** 적혀 있다. ⑤ **인용의 완전성은 못 본다** — 멤버가 아무것도 안 짚으면 잡지만, 다섯을 짚어야 할 자리에 넷만 짚은 것은 통과한다(주입으로 확인).

    **추출기가 틀리는 두 방향은 위험이 다르다.** 이름을 빠뜨리면 시험이 빨개진다(시끄럽지만 안전하다). 이름을 더 만들면 없는 것을 짚어도 통과한다(조용히 검사가 약해진다). 실제로 안전한 쪽으로 두 번 틀렸고 둘 다 빨강으로 드러났다 — `.proto`의 `oneof`가 이름 있는 스코프를 조기에 닫아 `Graph.waypoints`가 사라졌고, `re.M`이 없어 G1의 API 상수가 통째로 안 나왔다.

    **이것은 C-3의 첫 층을 닫은 것이지 C-3을 닫은 것이 아니다.** 나머지 둘은 여전히 열려 있다 — 거동(기종마다 도달 가능한 층이 다르다: Digit은 벤더가 자기 제어 프로그램을 시뮬레이터로 배포하고, G1은 공식 시뮬레이터가 저수준만 흉내내며, Spot은 공개된 것을 못 찾았다 — 마지막은 **부재 주장이고 근거 등급이 낮다**)와 배치(네트워크·인증·시계·재접속).

75. **Spot을 서비스 54개 중 3개만 읽고 쟀다 (2026-09-09 전수 조사로 드러남).** 앞 판의 `profile/distance/spot-arm.json`은 `robot_command`·`mission`·`graph_nav`만 보고 작성됐고, 그 위에서 *"이 벤더에 무엇이 없다"*를 적었다. BD는 공개 SDK에 **서비스 54개, proto 152개**를 판다.

    **뒤집힌 것 셋.**

    | 앞 판 | 실제 |
    |---|---|
    | 층이 둘(명령·미션)이고 우리 계약은 미션 계층에 있다 | **넷이다** — 명령 · 미션(행동트리) · **워크(`AutowalkService`)** · 태블릿. 우리 계약과 가장 닮은 것은 미션이 아니라 Autowalk의 `Element{name, target, target_failure_behavior, action, action_wrapper, action_failure_behavior, is_skipped, battery_monitor, action_duration, id}`이며, 그것이 미션으로 **컴파일**된다. 우리는 미션과 워크 **사이**에 있다 |
    | Spot에는 대상 시맨틱이 없다 (픽셀·3D점뿐) | **`WorldObjectService`가 있다** — `WorldObject.name`이 *"A human readable name"*, `ListWorldObjects`로 열거, `MutateWorldObjects(ACTION_ADD)`로 클라이언트가 등록. Digit의 세계 모델과 구조가 같다 |
    | 취득 결과를 대상에 결속할 자리가 없다 | **`CaptureActionId{action_name, group_name, timestamp}`가 있다.** Autowalk이 *"replaces the action_name … with the element name"*로 채운다. 생명주기도 온전하다 — `AcquireData`·`GetStatus`·`CancelAcquisition`·`GetServiceInfo` |

    **`pick_place`는 하루에 두 번 고쳤다 — NO(이유 틀림) → NO(이유 고침) → PARTIAL.** 두 번째도 과했다. 고수준 놓기 *액션*이 없는 것은 맞지만(피드백 열거에 `MANIP_STATE_PLACE_*`가 있는데 요청 `oneof`에는 대응이 없고 `reserved 3, 6, 9`만 남았다) **놓기 자체는 `ArmCartesianCommand` + `ClawGripperCommand`로 되고, 그것은 우리가 Digit에서 이미 YES로 인정한 어댑터 합성과 같은 종류다.** 일관성상 NO로 둘 수 없다. `inspect`는 PARTIAL 그대로.

    **프레임 결속의 실제 모양(벤더 예제로 확인).** 객체 프레임 이름은 명령에 **안 들어간다** — `fiducial_follow.py`가 `get_a_tform_b(snapshot, VISION_FRAME_NAME, frame_name_fiducial)`로 스냅샷에서 꺼내 `vision`으로 옮기고 명령은 그 관성 프레임으로 나간다. **그래도 결속의 주인은 로봇이다** — 어댑터는 로봇이 준 프레임 트리를 읽을 뿐 표를 갖지 않으므로 ADR 34가 지켜진다. 그리고 사이트가 이름을 등록할 수 있다(`mutate_world_objects.py`가 `WorldObject(name='red_sphere_ball')`를 `ACTION_ADD`로 넣는다).

    **남는 비대칭이 진짜다.** Digit은 `ObjectSelector{name}`을 넘겨 **파지 시점에 로봇이 다시 풀고**, Spot은 질의 시점의 좌표로 굳는다. 움직이는 대상에서 둘의 행동이 갈리며 그것은 배관이 아니라 의미의 차이다.

    **그래서 `pick_place`는 이제 표본 하나가 아니다** — YES 하나(Digit) + PARTIAL 하나(Spot).

    **팔 유무 확인을 붙였다 — 다만 처음 든 이유가 틀렸었다.** 나는 *"팔 없는 기체에 팔 명령을 보내면 조용히 아무 일도 안 일어난다"*고 적었는데, **그 문장은 `BodyAssistForManipulation` 하나에만 붙어 있다**(proto 152개 전체에서 팔 부재를 말하는 유일한 자리다). 명령 일반에는 `RobotCommandResponse.Status.STATUS_UNSUPPORTED` — *"The robot does not understand this command"* — 가 있다. 한 문장을 표면 전체로 넓힌 것이었고, 같은 종류의 성급함이 이 항목에서만 세 번째다.

    **진짜 이유는 결속이 어긋난 것을 보이게 하는 것이다.** 어댑터가 `spot-arm` 프로파일에 묶였는데 기체가 팔을 보고하지 않으면 그 아래 선언이 전부 거짓 전제 위에 선다. 신호는 벤더가 준다 — `RobotState.manipulator_state`가 *"only populated if an arm is attached to the robot"*이다(`HardwareConfiguration.skeleton.links[].name`으로도 셀 수 있지만 **어느 링크가 팔인지 우리가 정해야** 해서 안 쓴다. 벤더가 `has_audio_visual_system`은 파는데 `has_arm`은 안 판다).

    **막지 않고 보이게 한다.** 팔이 없어도 `move_relative`·`navigate_to`는 돈다. 그래서 거절이 아니라 결함이며, **팔이 필요한 스킬이 들어오는 날 그 스킬이 이 답을 보고 거절해야 한다** — 지금은 그런 스킬이 없어 거절 경로를 안 만든다(ADR 9). 그리고 **"없다"와 "못 물어봤다"를 가른다**: 읽기 실패를 팔 없음으로 접으면 관측 실패가 결속 오류로 보이고 운영자가 멀쩡한 기체의 배포를 뒤진다. `X_BOSTONDYNAMICS_ARM_ABSENT`와 `X_BOSTONDYNAMICS_HARDWARE_UNKNOWN`으로 따로 낸다. 전제는 모델 이름을 뜯어 짐작하지 않고 **배포하는 쪽이 명시한다**(`expectsArm`).

    **매니페스트도 전수로 바꿨다.** 앞 판은 손으로 고른 proto 12개만 덮어서 `survey_scope`의 *"서비스 54개 전수"*와 앞뒤가 안 맞았고, 실제로 `robot_state.proto`를 안 덮어 이 인용을 못 붙일 뻔했다. 이제 152개 전부에서 8089개 심볼을 뽑는다.

76. **한 로봇이 서비스 54개를 파는데, 그것들은 독립적이지 않다 — 그리고 미션 계층은 그것들을 재정의하지 않고 참조한다.** 2026-09-09 조사의 결과이며, 여기 적는 것은 **사실뿐**이다. 우리 계약을 어떻게 할지는 아래 "열린 질문"이고 아직 안 정했다.

    **① 세 겹의 공통 골격.**

    | 층 | 측정 |
    |---|---|
    | 봉투 | 최상위 `*Request` 메시지 **256개 중 250개(98%)**가 `RequestHeader header`를 든다 |
    | 디렉터리 | 전부 `DirectoryService`에 등록되고 `service_name`으로 찾아진다 |
    | 구동 권한 | **34개만** `Lease`를 싣는다 |

    마지막 줄이 진짜 경계선이다. **`Lease`를 싣느냐가 "움직이는 것"과 "묻는 것"을 가르는 유일한 선**이며, 배타 제어(§4.9)의 대상은 서비스 54개가 아니라 그 34개 요청이다. 나머지는 질의·등록·저장이다.

    **② 미션 노드 49개 = 서비스 참조 29 + 미션 소유 20.**

    참조 29개는 **전부 같은 모양**이다 — `(string service_name, string host, <그 서비스의 요청 메시지 원본>)`. 예:

    ```proto
    message BosdynRobotCommand {
        string service_name = 1;
        string host = 2;
        RobotCommand command = 3;   // 벤더 요청을 그대로 싣는다
    }
    ```

    **노드가 의미를 하나도 안 더한다.** `RemoteGrpc`도 같은 모양을 일반화한 것이다(`host`·`service_name`·`timeout`·`lease_resources`). 미션 계층이 실제로 소유한 것은 20개뿐이다 — **조합 10**(`Node`·`Sequence`·`Selector`·`Switch`·`Repeat`·`Retry`·`ForDuration`·`SimpleParallel`·`ParallelAnd`·`Condition`), **상태 6**(블랙보드 넷 + `ConstantResult`·`CreateMissionText`), **나머지 4**(`Prompt`=사람 · `Sleep`=시간 · `RestartWhenPaused`·`DataAcquisitionOnInterruption`=정책).

    **③ 명령은 이미 벤더가 합성해 두었다.** `RobotCommand` → `SynchronizedCommand{arm_command, mobility_command, gripper_command}`. 그래서 팔 노드가 따로 없고 `BosdynRobotCommand` 하나가 팔·이동·그리퍼를 다 덮는다.

    **④ 노드를 안 받은 서비스들**(완전 열거에서의 부재이므로 근거가 세다) — `ManipulationApiService`(집기 자체는 노드가 없다. `SetGraspOverride`만 있다) · `WorldObjectService` · `ImageService` · `DoorService` · `InverseKinematicsService` · `ArmSurfaceContactService` · `AutowalkService` · `NetworkComputeBridge`. **BD는 조작을 미션 노드로 만들지 않았다.** 그것들은 `RemoteGrpc`로만 미션에 들어온다.

    **그래서 BD의 답은 이렇게 읽힌다 — 미션 계층은 무엇을 할 수 있는지 열거하지 않는다. 참조하고, 조합하고, 상태를 갖는다.**

    **열린 질문 (아직 안 정함).** 우리 계약은 정반대로 능력을 **열거**한다(`move_relative`·`navigate_to`·`pick_place`·`inspect`). 이 배치로 보면 `inspect`가 실물 셋 어디에도 안 닿는 것이 벤더 결손이 아니라 **그것이 원자가 아니라 조합**(`Element{target, action}`)이기 때문으로 읽히고, `pick_place`도 Digit에서는 벤더가(`action-sequential`) Spot에서는 어댑터가 조합한다.

    **다만 BD 배치를 그대로 베낄 수는 없다.** BD가 `(service_name, host, 벤더 요청 원본)`을 나를 수 있는 것은 **자기 로봇만 상대하기 때문**이다. 같은 것을 우리가 하면 소비자가 벤더 요청 메시지를 알아야 하고 그것은 A-1 정면 위반이며, 벤더 중립이 이 계약의 존재 이유다. 그러므로 질문은 *"베낄까"*가 아니라 **"벤더 중립을 지키면서 열거에서 참조+조합으로 갈 수 있는가"**이다. §15.74의 `pick_place` 1건·`inspect` 0건 문제와 같은 뿌리다.

    > **닫힘 (2026-09-09) — [ADR 36](../../adr/0036-work-first-assignment-vs-execution.md).** 답은 *"베낀다/안 베낀다"*가 아니라 **어휘가 두 일을 겸업하고 있었다**는 것이었다. 배정(추상)과 실행(정밀)을 한 벌로 두면 하나가 다른 하나를 망친다. 층을 넷으로 가르고(사이트 일감 / 능력·자격 어휘 / 배정과 결정 / 실행과 벤더 조합), **실행 계층을 상류에 비노출**로 두며, 등재 기준을 **발명 금지**(상류나 벤더 중 한쪽이라도 이미 갖고 있는 것만)로 잡았다. 조합은 계약이 갖는 것이 아니라 **능력 안에서 로봇마다 다르게** 산다 — ADR 34·35가 이름 없이 정해 둔 부패 방지 계층이 그 자리다.

    **ADR 35는 오히려 강해진다** — 표본 하나가 아니라 독립적인 벤더 둘이 같은 배치(이름은 로봇 안에 산다)를 골랐다. 약해지는 것은 *"Digit이 유일하다"*는 서술뿐이다.

    **`inspect`에 대해 이 조사가 새로 제기하는 것.** BD는 점검을 *"어디에 서서 어느 이름의 액션을 돌리는가"*(`Element{target, action}`)로 모델링하는데 우리 계약은 `inspect(target)`으로 **물체 신원**을 묻는다. `inspect`가 실물 셋 어디에도 안 닿는 것이 벤더의 결손이 아니라 **우리 형식이 틀렸을 가능성**이 처음으로 근거를 얻었다.

    **이름 공간이 둘이다.** 장소는 `Waypoint.annotations.name`, 대상은 `WorldObject.name`. 계약의 `is_site_reference`도 장소(`location`·`destination`)와 대상(`object_id`·`target`)으로 갈린다. ~~**그러므로 `GetKnownSiteNames`가 그래프만 보는 것은 반만 보는 것이다**(§15.73에 더한다).~~ **이 결론이 틀렸다 — §15.78 참조.** 공간이 둘이라는 관찰은 맞는데 그것을 **한 그릇의 결손**으로 읽었다. 장소를 묻는 질의가 장소만 답하는 것은 옳다. 틀린 것은 질의가 아니라 **불리언 하나가 넷을 한 덩어리로 묶고 있는 것**이다.

    **막는 장치.** 거리 스키마에 `survey_scope`를 **필수**로 더했다 — *"이 측정이 벤더 표면의 얼마를 봤는가"*. `evidence_grade`가 *출처가 얼마나 1차인가*라면 이것은 *얼마나 넓게 봤는가*이고, **둘은 다르다**: 1차 원문 세 개만 읽어도 등급은 `VENDOR_PRIMARY`다. 이 항목의 오류가 정확히 그 틈에서 났다. 셋 다 범위를 적었다.


77. **어댑터가 벤더의 *어느 층*에 붙든 상관없어야 한다 — 그리고 그것이 지켜지는지 우리는 모른다.**

    실물 어댑터 셋이 전부 **로봇에 직접** 붙는다(Spot gRPC · Digit WebSocket · G1 DDS). 붙는 자리가 다를 수 있다는 것은 추측이 아니다 — BD의 **Orbit**이 플릿 매니저로 실재하고 그 표면을 재어 `docs/vendors/orbit.md`에 적었다.

    **그런데 그것은 계약이 알 일이 아니다.** 어댑터가 할 일은 **원자적 명령으로 번역하는 것뿐**이고, 어느 층에 붙었는지는 어댑터 안에서 끝나야 한다. 그것이 ADR 34·35가 이름 없이 정해 둔 부패 방지 계층의 뜻이다. 계약에 `ROBOT_DIRECT | FLEET_MANAGER` 같은 필드를 더하는 것은 **벤더 배치를 상류로 새게 하는 것**이며(ADR 36 결정 5의 정면 위반), 이 항목의 앞 판이 정확히 그것을 제안했다가 되물렸다.

    층이 다를 때 흘러드는 것들은 **이미 있는 어휘로 흡수된다**:

    | 층 차이 | 흡수하는 자리 |
    |---|---|
    | 리스가 없다(Orbit) | 프로파일의 `exclusive_control_required` — 이미 기종마다 다르게 선언한다 |
    | 인스턴스 하나가 기체 N대를 든다 | 어댑터 인스턴스를 기체마다 두고 안에서 다중화한다. 계약은 이미 기체 단위다 |
    | 결과가 정수 하나로 온다 | 결함 어휘로 옮긴다. **못 옮기면 그것은 계약의 결손이 아니라 능력 판정이다** — 그 층으로는 이 계약을 만족하지 못한다 |

    **그러므로 여기 적는 한계는 "선언이 없다"가 아니라 "새는지 모른다"이다.** 플릿에 붙는 어댑터를 만들어 본 적이 없으므로, 위 표의 흡수가 실제로 되는지 확인된 바 없다. 확인되면 §15의 이 항목이 닫히고, 흡수 안 되는 것이 나오면 그때는 필드를 더할 것이 아니라 **그 층은 이 계약을 못 만족한다**고 적는다.

78. **공간을 구분할 수 있으면 구분한다 — 지금 계약은 이름과 주소를 뭉개고 있다.**

    셋이 다르다.

    | 공간 | 무엇 | 누가 소유 | 예 |
    |---|---|---|---|
    | **이름 공간** | 사람이 붙인 이름 | 사이트가 저작하고 로봇에 등록 | `dock-3`, `valve-A` |
    | **주소 공간** | 기계가 만든 식별자 | 로봇·플릿이 생성 | `destination_waypoint_id`, `WorldObject.id`(배터리 수명), Orbit `uuid`·`robotIndex` |
    | **논리적 공간** | 능력·일감 수준의 개념 | 도메인 | ADR 36의 층 ② |

    ADR 34·35가 *"계약은 이름을 나르고 어댑터가 주소로 옮긴다"*를 이미 정했다. **그런데 계약의 이름이 그것을 배반한다** — `object_id`는 *id*(주소)라고 불리면서 실제로는 **이름**을 나른다. Spot 어댑터가 사람 이름을 `destination_waypoint_id`로 그대로 넘겨 틀렸던 것(§15.73)이 그 혼동의 실물이고, 이름이 그렇게 붙어 있는 한 같은 실수가 다시 난다.

    **이름 공간 안에서도 또 갈린다.** 장소(`location`·`destination`)와 대상(`object_id`·`target`)은 **쓰임과 의도가 다르다**:

    - 장소는 사이트가 지도에 **저작**한 것이고 지속한다. 등록의 대상이다.
    - 대상은 **인지가 필요**하고(§1.3 비목표) 만료된다. Spot의 `WorldObject`를 벤더는 *"the currently perceived world objects"*라 부른다.

    그런데 `is_site_reference`가 **불리언이라 넷을 한 덩어리로 만든다.** 그 결과가 두 곳에서 이미 새고 있다 — 레지스트리가 `object_id`까지 **등록 대상으로 유도**하고(등록할 수 없는 것을 등록하라고 한다), `GetKnownSiteNames`가 평면 목록이라 **어느 공간의 답인지 말하지 못한다**.

    > **왜 지금 중요한가.** 여기서 잡탕이 되면 ADR 36의 **논리적 능력 매핑이 성립하지 않는다.** 층 ②의 자격 조건은 *"이 기체가 이 장소를 아는가"*, *"이 대상을 집을 수 있는가"* 같은 것을 말해야 하는데, 이름과 주소와 능력이 한 그릇에 있으면 그 문장을 쓸 수가 없다.

    **벤더가 하나로 두는 경우와 혼동하지 않는다.** Digit은 장소로 가는 것도 `object-selector.name`을 쓴다 — 이름 공간이 **기종에서** 하나인 것이며, 그것은 어댑터가 흡수할 벤더 사실이지 계약이 뭉갤 이유가 아니다. Spot은 둘로 갈려 있다(GraphNav 웨이포인트 / `WorldObject`).

    **앞 판의 결론을 뒤집는다.** §15.76은 *"그러므로 `GetKnownSiteNames`가 그래프만 보는 것은 반만 보는 것이다"*라고 적었는데 틀렸다. 공간이 둘이라는 관찰은 맞고, 그것을 **한 그릇의 결손으로 읽은 것**이 틀렸다. 장소를 묻는 질의가 장소만 답하는 것은 옳다.


79. **충분함은 계약이 알 것이 아니라 로봇이 답할 것이다 — 그런데 답할 어휘가 없다.**

    `GetKnownSiteNames`는 *"이 이름을 기체가 아는가"*까지 답한다(ADR 35). 일감은 더 요구한다 — *"1번 창고 6번 자리에 놓아라"*는 그 자리의 **높이·접근 방향**을 요구하고, 이름이 있다는 것과 그 이름이 그만큼을 문다는 것은 다르다.

    **벤더 원문이 그 간극을 보여 준다.** 이름은 `Waypoint.Annotations.name`에 붙고 벤더 예시가 *"Kitchen Fridge"*다. 웨이포인트가 무는 자세 `waypoint_tform_ko`(`SE3Pose`)는 **로봇이 서서 측위하는 자리**이며, `Annotations`의 나머지 스물몇 필드는 전부 항법·측위·GPS·루프 클로저·충돌 설정이다. **조작 대상의 자세는 없다.** 그러므로 같은 이름이 `navigate_to`에는 충분하고(웨이포인트가 정확히 그것을 위해 있다) `pick_place`의 `destination`에는 모자랄 수 있다.

    ### 앞 판이 이것을 우리 결손으로 적었고, 그것이 틀렸다

    *"충분함을 확인하지 못한다"*고 적었는데 **확인해서는 안 되는 것이다.** 확인하려면 계약이나 미들웨어가 6번 자리의 물리를 알아야 하고, 그 순간 §15.78이 어댑터 아래 가둔 주소 공간이 위로 샌다.

    선이 여기다 — **계약은 환경을 *참조*하되 *해석*하지 않는다.**

    | 계약이 나르는 것 | | |
    |---|---|---|
    | **참조** — 사이트가 저작한 이름 | ○ | 나르기만 하고 뜻을 묻지 않는다 |
    | **속성** — 높이·좌표·자세 | ✗ | 상류가 환경의 물리를 알아야 한다 |
    | **충분함 판정** | ✗ | 판정하려면 환경을 알아야 한다 |

    ### 그러면 누가 답하는가 — 로봇이다. 자리도 이미 있다

    `Negotiate`(§5.4, *"태스크를 보내보고 실패하는 것이 아니라 사전 협상이다"*)와 접수 시 `Rejection`이 그 자리다. **판정은 로봇이 하고 계약은 판정을 나른다** — 그러면 환경 정보가 한 톨도 안 올라온다.

    ### 진짜 빈 칸은 어휘다

    `RejectionCode` 열둘 중 **그 말을 할 값이 없다.** `SKILL_ABSENT`는 스킬이 없다는 뜻이고 `PARAMETER_INVALID`는 값이 선언을 어겼다는 뜻인데, 여기서 필요한 것은 *"파라미터도 스킬도 멀쩡한데 **이 이름으로는 이 스킬을 못 한다**"*이다. 없으면 어댑터가 둘 중 하나로 접게 되고, 그러면 운영자가 **이름을 고치려 든다 — 고칠 것은 등록이다.**

    ### 그 어휘를 더하려다 멈췄다 — 발신자가 없다 (2026-09-09)

    `RejectionCode`에 값을 하나 더하려 했는데 **ADR 9**(*"소비 표면이 없는 선언은 두지 않는다"*)에 걸린다. 반대 방향으로도 걸린다 — **낼 쪽이 없다.**

    - **Spot 어댑터는 `move_relative`와 `navigate_to` 둘만 든다.** `pick_place`가 없으니 `destination`의 결속을 물을 자리 자체가 없다.
    - **Digit은 `pick_place`를 드는데** 벤더 표면이 수락 시점에 *"이름은 아는데 이 스킬엔 못 쓴다"*를 말해 주지 않는다. 있는 것은 실행 후의 `action-status-changed.info`다.
    - **어댑터의 `Acceptance.Refused`는 아직 계약으로 가는 길이 없다.** 어댑터는 라이브러리이고 계약 뒤에 세워 주는 것이 없다. 지금 계약의 `RejectionCode`를 내는 것은 미믹뿐이며, 미믹은 이름은 갖되 자세를 안 갖는다 — 충분함을 모델링하게 만들면 **미믹이 환경을 갖게 되고** 그것이 이 항목이 막으려던 바로 그것이다.

    ### 그리고 더 중요한 것 — 벤더는 이것을 **런타임에** 말한다

    Spot의 `GRASP_PLANNING_NO_SOLUTION`·`GRASP_FAILED_TO_RAYCAST_INTO_MAP`은 `ManipulationFeedbackState`의 값이다. **피드백이지 수락 거절이 아니다.** 로봇은 시도해 보고 나서 안다.

    > ⇒ **"이름은 아는데 못 쓴다"는 대체로 거절이 아니라 실패다.** 그러면 이 항목의 빈 칸은 `RejectionCode`가 아니라 **결과 어휘**로 옮겨간다 — `Fault.error_type`이 자유 문자열이라 Spot의 열여덟 값을 정준화하지 못하는 그 문제와 **같은 칸**이다. 항목이 하나 줄고, 결과 어휘를 세워야 할 이유가 하나 늘었다.

    > **환경 참조를 가진 것들만 기종마다 갈린다.** `move_relative`의 파라미터 넷에는 환경 참조가 하나도 없고 그것만 3/3이다(§15.76). `navigate_to`·`pick_place`·`inspect`는 전부 환경을 참조하고 전부 기종마다 다르게 닿는다. **환경 결속이 곧 이식성의 경계**라는 뜻이며, ADR 36이 `move_relative`를 *"너무 저수준"*이라 판정한 것의 다른 얼굴이다.

    > **곁가지.** `Waypoint.Annotations.waypoint_source`가 `ROBOT_PATH`(녹화 중 자동 생성) · `USER_REQUEST`(사람이 놓음) · `ALTERNATE_ROUTE_FINDING`을 가른다. **벤더가 저작 여부를 선언해 준다.** 지금 Spot 어댑터는 *"이름이 비지 않았는가"*로 저작물을 짐작하는데(§15.73), 이 필드가 그 짐작을 관측으로 바꾼다. 아직 안 든다.


80. **대상을 무엇으로 지시하는가 — 그리고 분류는 원자가 아니다.**

    2026-09-09 조사. `pick_place(object_id, destination)`에서 `object_id`가 어떻게 물리적 대상에 닿는지를 물었더니 형식 자체가 흔들렸다.

    ### 신원 수단이 셋이고 값이 다르다

    | | 수단 | 벤더 근거 | 대가 |
    |---|---|---|---|
    | ① | **자리가 보증한다** | 없어도 된다 | 공정이 종류별로 제시해야 한다 |
    | ② | **AprilTag** | Spot `WorldObject.apriltag_properties`, Digit `ObjectSelector.april_tag_id` | 로트마다 붙이는 것은 비현실적. 재사용되는 용기·팔레트·지그에는 현실적 |
    | ③ | **외부 인지** | Spot `NetworkComputeBridge` (심볼 72) | 워커를 배포하고 유지해야 한다 |

    **바코드·QR·RFID는 Spot 공개 표면 8089 심볼에 0건이다.** *"제품에 QR을 붙인다"*는 애초에 벤더가 못 받는다. 마킹은 AprilTag 하나다.

    ③의 사슬은 끝까지 있다 — `NetworkComputeRequest{image, model_name, min_confidence}` → 외부 워커 → `NetworkComputeResponse.object_in_image`(`repeated WorldObject`, *"May include bounding boxes, image coordinates, 3D pose information"*) → `PickObjectInImage{pixel_xy}`.

    ### 조작이 요구하는 것은 인스턴스가 아니다

    `PickObject`는 **3D 점**(`Vec3 object_rt_frame`)이나 **픽셀**만 받는다. 이름도 타입도 안 받고, `GraspParams`는 `grasp_palm_to_fingertip`·`allowable_orientation` 같은 **전략**이며 *"아무것도 안 주면 로봇이 알아서 좋은 파지 방향을 찾는다"*고 적혀 있다. **로봇에 제품 타입이라는 개념이 없다.**

    | 일감 | 대상을 무엇으로 지시하나 | 결속 |
    |---|---|---|
    | 순서공급 | `source` — **자리** | 등록 (ADR 35) |
    | 분류 | **타입 라벨** | 워커가 선언 (`ModelLabels.available_labels`) |
    | 추적·이력 | `AA-1234` — 인스턴스 | **조작 밖.** 상류가 사후 결속 |

    > **`pick_place(object_id, destination)`은 이력 키를 조작 파라미터 자리에 놓고 있다.** `object_id`가 결속 경로를 못 갖는 이유가 *"아직 안 만들어서"*가 아니라 **거기 들어갈 것이 아니라서**일 수 있다. §15.76이 `inspect(target)`에 대해 제기한 *"우리 형식이 틀렸을 가능성"*이 `pick_place`에도 걸린다.

    ### 분류는 원자가 아니다 — 배치가 넷이고 기종이 갈린다

    분류는 `인지 → 라벨 판정 → 규칙 적용 → 집어 놓기 → 반복`이다. **누가 그 루프를 돌고 누가 규칙을 갖느냐**가 결정이다.

    | | 루프 | **규칙** | Spot | Digit |
    |---|---|---|---|---|
    | **A** 자리 기반 | 없음 | 상류 | ✓ | ✓ |
    | **B** 로봇이 판단 | 로봇 | 로봇의 미션 | **✓** | **✗** |
    | **C** 관제가 판단 | 관제 | 관제 | ✓ | **✓** |
    | **D** 로봇 루프 + 외부 판단 | 로봇 | 외부 호출 | **✓** | ✗ |

    **실측이 이 표를 만들었다.** Spot의 미션 노드에 `Repeat`·`Retry`·`Switch`·`Selector`·`Condition{EQ,NE,LT,LE,GT,GE}`·`DefineBlackboard`/`SetBlackboard`·`ParallelAnd`·`SimpleParallel`이 다 있고, `RemoteGrpc`·`RemoteMissionService`로 외부에 물을 수도 있다(D). **Digit은 `action-loop{action, count}`로 횟수 반복만 되고 조건·분기가 0건이다**(`condition`·`branch`·`switch`·`while` 전부 0). 대신 `add-sequential-actions{actions, append, reference-number}`가 있어 **실행 중인 순열에 액션을 밀어 넣을 수 있다** — 그것이 C다.

    > **둘 다 되는 유일한 배치가 C다.** 그리고 **ADR 36의 층 구분이 같은 답을 가리킨다** — 규칙은 층 ③(배정과 결정)이고 로봇은 층 ④(실행)다. 이식성과 층 구분이 일치하는 드문 경우이며, 그래서 이것은 취향이 아니라 근거 있는 방향이다.

    ### 그런데 계약이 C를 못 나른다

    - **관측을 올릴 길이 없다.** 위로 가는 것은 태스크 상태·결함·능력 변경뿐이고, 구조화된 관측을 실을 자리가 `WatchTaskResponse.partial_result` **문자열 하나**다. *"라벨 valve, 신뢰도 0.91, 픽셀 (412,308)"*을 실을 데가 없다.
    - **B를 고르더라도 나를 어휘가 없다.** *"분류해라"*가 카탈로그에 없고, 규칙을 로봇에 배포하는 경로도 없다.
    - C의 대가는 **왕복 지연**이다. 물건 하나마다 인지→상행→판단→하행이 돈다.

    ### 능력 어휘의 첫 벤더 근거

    `ModelLabels.available_labels`(`repeated string`, *"List of class labels returned by this model"*)가 **이 기체가 지시받을 수 있는 대상 종류**다. 워커가 스스로 선언하고, 프로파일의 `allowed_values`(§10.4 ③)가 그것을 나를 그릇이다. **ADR 36 층 ②의 첫 구체적 사례이며 발명이 아니다.**

    ### 안 정한 것

    **혼재로 오느냐 종류별 용기로 오느냐는 공정 설계의 결과**이고 우리는 그 라인을 모른다. 시연 영상은 연출이라 근거가 못 된다. 확실한 것은 **둘 다 지원해야 하고 둘이 요구하는 것이 다르다**는 데까지다 — 순서공급은 자리 결속만 있으면 되고, 분류는 인지 라벨·규칙·반복이 필요하다.


81. **능력에는 전제가 딸린다 — 능력은 기체의 성질이 아니라 (기체 × 현장)의 성질이다.**

    2026-09-09에 이것을 **네 번 다른 이름으로 다시 발견했다.** 그래서 여기 이름을 붙여 둔다.

    *"물건 나르기"*는 한 능력이 아니다. 세부 요구에 따라 갈리고, **갈린 것 중 일부는 현장이 뒷받침해 주어야만 성립한다** — 지정된 팔레트나 워크셀에 물건을 놓아 두는 공정이 있어야 한다는 식이다. **같은 기체가 현장의 제시 방식에 따라 되기도 하고 안 되기도 한다.**

    ### 오늘 나온 것 다섯이 전부 이것이었다

    | 어디서 | 그때 부른 이름 | 실제 정체 |
    |---|---|---|
    | ADR 35 | 사이트 이름 등록 | 환경 전제 |
    | §15.79 | *"등록의 충분함"* | 환경 전제 (그 자리가 놓기에 족한가) |
    | §15.80 ① | *"자리가 신원을 보증한다"* | 환경 전제 (종류별로 제시하는가) |
    | §15.80 ② | AprilTag | 환경 전제 (붙였는가) |
    | §15.80 ③ | 외부 인지 | 환경 전제 (워커를 배포했는가) |

    ### 확인 장치는 이미 있다 — 이름을 잘못 불렀을 뿐이다

    `GetKnownSiteNames`가 **환경 전제를 확인하는 첫 장치**다. 우리는 그것을 *"이름 확인"*이라 불렀지만 하는 일은 *"이 기체가 이 일감의 전제를 갖췄는가"*이고, CLAIMED / CONFIRMED / CONTRADICTED가 그 답이다. **일반화할 자리가 이미 열려 있다.**

    ### 프로파일이 전제를 말할 수 없다

    `capability-profile.schema.json`의 `skills`는 **무조건**이다 — `derived_from`·`publish_interval`·`protocol_limits`·`exclusive_control_required`·`durations`·`failure_modes` 어디에도 전제를 적을 자리가 없다. 그래서 프로파일은 *"이 기체는 `pick_place`를 든다"*까지만 말하고 *"종류별 용기로 제시되면"*을 못 말한다.

    > **그 침묵의 대가가 이 항목의 이유다.** 배정이 통과하고 현장에서 실패한다. 그리고 그 실패가 **기체 탓으로 보인다** — 실제로는 공정이 전제를 안 만든 것인데, 화면에는 그 로봇이 못 한 것으로 남는다. §9.7 ④가 *"안 했다가 보여야 정직하다"*고 한 것과 같은 자리이며, 여기서는 안 한 쪽이 사람이다.

    ### 배정이 두 쪽이 아니라 세 쪽 맞춤이 된다

    ADR 36은 `요구 ⊆ 제공` 두 쪽으로 적었다. 실제로는 셋이다 — **일감이 요구하는 것 / 기체가 제공하는 것 / 현장이 보증하는 것.** 셋째를 빼면 앞의 둘이 맞아도 일이 안 된다.

    ### 안 정한 것

    - **전제를 누가 선언하나.** 프로파일(기체마다)인가 사이트(현장마다)인가. 둘 다 아닐 수도 있다 — 전제는 **일감과 현장의 짝**에 붙는 것이라 어느 한쪽의 문서에 안 들어갈 수 있다.
    - **확인 못 하는 전제가 있다.** *"공정이 종류별로 제시하는가"*는 로봇이 관측할 수 없다. 그런 전제는 **영원히 CLAIMED**이며 CONFIRMED가 될 길이 없다. 그것을 CONFIRMED와 같은 칸에 두면 확인된 것과 사람이 말한 것이 구별되지 않는다.
    - **목록을 모으기 시작했다** — [`docs/environment-preconditions.md`](../../environment-preconditions.md). 거기서 이 항목의 목적이 하나 더 드러났다: **전제는 검사 명세가 아니라 설계 입력이다.** 로봇 쓰는 공장·창고를 짓는 쪽이 *"이런 일을 시키려면 물리 세계에 이런 것이 있어야 하는구나"*를 읽어 가는 문서이며, 제약이자 제안이다. 가장 선명한 사례가 `DoorCommand`다 — **문을 어느 쪽으로 달지가 로봇 능력을 바꾼다**(`hinge_side`·`swing_direction`, 그리고 미는 문이냐 잡는 문이냐가 팔의 필요를 가른다).
    - 이 항목은 **결정이 아니라 관찰**이다. 결정하려면 ADR이어야 하고, 그 전에 전제의 종류를 몇 개 더 모아야 한다 — 지금은 다섯이고 전부 오늘 나왔다.

82. **범위를 안 보고 단정했다 — 하루에 두 번째, 그리고 침묵을 부재로 읽을 뻔한 것이 셋이었다.**

    G1 의 벤더 매니페스트가 **44 심볼**이었다. `g1_loco_api.hpp` 와 `unitree_hg` IDL 둘(`LowState_`·`MotorState_`)이 전부였고, 그 위에서 *"G1 은 능력을 선언하지 않고, 결함도 안 알린다"* 를 적었다. 공개 SDK 에 서비스가 **넷**이다 — `loco`·`arm_action`·`agv`·`audio`. §15.75 가 Spot 을 54 개 서비스 중 3 개만 읽고 쟀다고 적은 바로 그 모양이고, 그 항목을 적은 다음 날 같은 실수를 다른 기종에서 했다. 넷 전부와 `common/terminations.hpp`, `unitree_hg` IDL 열하나, 그리고 C++ 전용 IDL 셋을 넣어 **161 심볼**이 됐다(`53aae87`·`fcd0884`).

    ### 침묵 셋 — 벤더가 안 준 것이 아니라 추출기가 못 읽은 것

    범위를 넓히자 원문 파일이 늘었는데 그중 심볼이 **하나도 안 나오는** 파일들이 있었다. 셋 다 *"이 벤더는 그것을 안 준다"* 로 적힐 뻔했다.

    | 원문 | 벤더가 준 모양 | 앞 판이 읽던 것 | 드러난 것 |
    |---|---|---|---|
    | `*_error.hpp` 넷 | `UT_DECL_ERR(NAME, 7303, "...")` 매크로 | `const` 줄만 | **에러 코드 11개** — `LOCO_ERR_{INVALID_FSM_ID, INVALID_TASK_ID, LOCOSTATE_NOT_AVAILABLE}` · `ARM_ACTION_ERR_{INVALID_ACTION_ID, HOLDING, ARMSDK, INVALID_FSM_ID}` · `G1_AGV_ERR_{NOT_INIT, EXEC_MOVE, EXEC_HEIGHT_ADJUST}` · `AUDIO_ERR_COMM` |
    | `terminations.hpp` | `inline bool bad_orientation(...)` 함수 | 상수·클래스만 | **종료 조건 7개를 코드로 준다** — `lost_connection`·`low_battery`·`bad_orientation`·`joint_vel_out_of_limit`·`ang_vel_out_of_limit`, 그리고 과열을 `motor_casing_overheat`/`motor_winding_overheat` 둘로 가른다 |
    | Cyclone DDS C++ IDL 셋 | `class SportModeState_ { … int32_t task_id_; }` | 파이썬 생성본만 | **`SportModeState_{fsm_id, fsm_mode, task_id, task_time}`** — 무엇이 도는지를 토픽으로 발행한다 |

    셋 다 결함 주입으로 확인했다 — 매크로 하나를 깨뜨리면 141→140, 필드 하나를 깨뜨리면 161→160, 클래스 줄을 깨뜨리면 161→156. **주입이 없었으면 "읽었다" 와 "0 개가 맞다" 를 구분할 길이 없었다.**

    > **추출기의 침묵을 벤더의 부재로 읽지 말 것.** `unitree_symbols.py` 의 머리말이 이미 한 번 그 실수를 적어 두고 있었고(`Jsonize*` 키), 그 문단이 있는 파일에 같은 실수가 셋 더 있었다. 침묵이 나오면 벤더를 의심하기 전에 추출기부터 의심하고, 판정은 주입으로 잠근 뒤에 적는다. `tools/vendor-manifest/README.md` 의 *"이름을 빠뜨리면 시험이 빨개진다"* 는 **누군가 그 이름을 짚고 있을 때만** 참이다 — 아무도 안 짚은 표면의 침묵은 아무것도 빨갛게 만들지 않는다. 오늘 셋이 정확히 그것이었다.

    ### 부재 판정은 벤더의 낱말로 다시 물어야 한다

    같은 날 다른 자리에서 같은 종류의 실수를 한 번 더 할 뻔했다. Digit 매니페스트에서 `nogo`·`no-go` 를 찾아 0 건이 나왔고 *"출입 금지 구역이 없다"* 고 적으려 했다. 벤더는 그것을 **`keep-out`** 이라 부른다. 우리 낱말로 안 나온 것은 부재가 아니라 **검색 실패**이며, 부재를 적으려면 벤더의 낱말로 한 번 더 묻고 나서다.

    ### 그래서 보인 것 — 그리고 판정이 어떻게 됐나

    - **G1 이 에러 어휘를 갖고 있다.** 호출의 반환값으로 11개. 조사 문서(`profile/vendors/unitree-g1.json`)의 `failure_modes: NONE` 은 *"결함 목록을 발행하지 않는다"* 로는 여전히 참이지만 *"결함을 안 알린다"* 로는 틀렸다 — `PARTIAL` 로 고쳤다. 이 11개는 §15.79 가 합쳐진 **결과 어휘 정준화**의 세 번째 입력이다(Spot 18값 · Digit 상태 · G1 11개).
    - **`arm_action` 에 `GET_ACTION_LIST` 와 `STOP_CUSTOM_ACTION` 이 있다.** 능력 열거와 정지가 팔에는 있다. `vendor_layer: NONE` 은 유지하되 읽는 법이 바뀐다 — *기종에 태스크 개념이 없다* 가 아니라 *우리가 쓰는 표면(`loco`)에 없다* 다.
    - **`go2` 네임스페이스는 G1 것이 아니다.** `HeightMap_`·`VoxelMapCompressed_`·`LidarState_`·`UwbState_` 는 `unitree_go` 이고 `hg` 에 대응물이 없으며 G1 예제 열여덟 어디에도 안 쓰인다. 그러므로 *"G1 에 지도가 없다"* 는 범위 인공물이 아니라 표면의 사실이다 — 범위를 넓혔는데도 그대로인 판정이 하나 있다는 것이 이번 재측정의 반대쪽 결과다.

    ### 어댑터가 `SportModeState_` 를 듣는다 (`66bc2f0`)

    G1 어댑터는 성공을 **시계로** 적는다(`SetVelocity` 의 `duration` 이 근거). `TERMINAL_STATE_VIOLATED` 가 *"끝났다고 적었는데 계속 걷는"* 쪽 구멍을 막고 있었는데, 반대쪽 구멍이 열려 있었다 — **명령은 받아들여졌는데 로봇이 `damp` 나 `sit` 에 있어 아무 일도 안 일어나고, 시간만 지나면 성공이 되는** 경우. `SportModeState_.fsm_id` 가 그 관측이다. 태스크가 도는 동안 `fsm_id` 가 기대(`fsm.start`)와 다르면 `X_UNITREE_FSM_UNEXPECTED` 를 낸다. **막지는 않는다** — 어느 FSM 에서 속도 명령이 듣는지를 벤더가 열거해 주지 않으므로 *"못 움직인다"* 고 단정할 근거가 없고, 아는 것은 기대와 관측이 어긋났다는 사실뿐이다.

    두 가지를 정직하게 적어 둔다. ① **이 관측이 `lowstate` 뒤에 갇혀 있다** — 저수준 상태를 못 받으면 `faults()` 가 통째로 `NotObservable` 을 내므로 운동 상태만 오는 대상에서는 이 결함이 안 보인다. 채널 둘이 독립인데 관측 가능성을 하나로 접은 것이며, 고치려면 `FaultObservation` 이 부분 관측을 표현해야 한다. ② **결함 주입 셋 중 하나를 시험이 못 잡았다.** `running.state == RUNNING` 조건을 지워도 초록이었다 — 종착한 태스크에 결함을 내는 경우를 겨냥한 시험이 없었다. 시험을 하나 더 만들고(`종착한 태스크에는 FSM 결함을 안 낸다`) 다시 주입해 잡히는 것을 확인했다. 못 잡음은 시험 집합의 구멍이고, 그것을 찾는 것이 주입의 용도다.

83. **부재를 적기 전에 벤더의 1 차 표면을 다 세었는가 — 스펙 하나가 전부가 아니었다.**

    Orbit 측정 노트(`docs/vendors/orbit.md`)가 *"즉시 실행 경로가 없다 — 작업을 넣는 유일한 문이 캘린더 항목이다"* 라고 적었다. 게시 OpenAPI 35 경로를 **전수로** 읽고 적은 문장이라 범위 인공물이 아니라고 믿었다. 틀렸다. 벤더가 배포하는 공식 파이썬 클라이언트 `bosdyn-orbit` 에 `post_dispatch_mission_to_robot()` 이 있고, 그것이 스펙에 없는 `POST calendar/mission/dispatch/{nickname}` 을 친다. 몸통에 **walk 를 인라인**으로 실을 수 있어 *"파라미터 자리 없음"* 도 그 경로에서는 거짓이다.

    §15.82 가 세운 규율 둘(추출기의 침묵을 벤더의 부재로 읽지 말 것 · 부재 판정은 벤더의 낱말로 다시 물을 것)과 같은 뿌리이고, 셋째 문장이 필요하다 — **부재를 적으려면 벤더가 준 1 차 표면을 먼저 다 세어야 한다.** 스펙 · 산문 문서 · **클라이언트 코드** · 예제. Orbit 은 그중 셋째를 빼고 쟀다. G1 때는 서비스 넷 중 하나만, Spot 때는 서비스 54 중 셋만 — 세 번 다 *"범위 안에서 전수"* 였고 범위가 틀렸다.

    이 정정은 외부 대조에서 나왔다. 같은 날 별도로 작성된 하류 API 조사 문서가 그 경로를 짚고 있었고, 벤더 클라이언트 원문(파일 해시는 측정 노트에)으로 확인했다. **독립적으로 잰 둘이 어긋나는 자리가 가장 싸게 틀림을 찾는 자리다** — 그 문서가 틀린 곳(Digit 의 JSON API 를 "공개 문서 없음" 으로 적음)도 같은 대조에서 보였다.

84. **시나리오 셋을 들였다 — 그리고 AMR 이 그 안에 있다. 경계를 그어서.**

    저장소 밖에서 별도로 작성된 설계 문서 셋(팩트체크 · 미들웨어 설계 보고서 · 하류 API 조사)과 대조해 일감 셋을 [`docs/scenarios.md`](../../scenarios.md) 로 들였다 — ① 용기 공급(AMR) · ② 부품 시퀀싱(휴머노이드, `pick_place`) · ③ 설비 점검(4족, `inspect`). 들인 것은 시나리오의 뼈대와 상황표이고, 근거는 ISA-95 Job Control 의 타입과 벤더 1 차 자료(전부 매니페스트와 대조됨)만이다. 외부 문서가 붙인 층 ② 의 이름들은 들이지 않았다(ADR 36).

    ### AMR 이 있는 이유와 경계

    **AMR 은 이 저장소의 주력이 아니다.** 그런데 시퀀싱 셀에 부품이 도착하는 경로를 빼면 ② 가 허공에 뜬다. 현실적인 시나리오에서 빠질 수 없으면 넣되 경계를 긋는다 — 규칙 셋. (1) picasso 는 AMR 을 어댑터로 감싸지 않는다 — 그 자리는 표준과 관제가 이미 채웠고, ADR 36 의 발명 금지는 *이미 있는 것을 다시 만들지 않는다* 로도 읽힌다. (2) AMR 구간의 결과가 들어오는 문은 둘뿐 — ② 의 **환경 전제**(§15.81, *"용기가 그 자리에 있다"*)이거나 층 ③ 이 읽는 **인계 설비 신호**. (3) 휴머노이드 일감의 파라미터에 AMR 식별자가 오지 않는다 — 장소의 이름과 대상의 이름뿐(§15.78). 이 셋을 지키면 시나리오에 AMR 이 있어도 계약과 어댑터에는 그 낱말이 없고, 지금 저장소가 그 상태다.

    ### 들이면서 보인 것

    - **완료 근거 등급(E0 로봇 보고 / E1 플릿 확인 / E2 독립 설비 / E3 업무)이 ADR 37 과 맞물린다.** E0 은 어댑터가 로봇에 직결된 경우이고 E1 은 플릿에 붙은 경우다. 계약은 지금 E0/E1 까지를 나르되 **둘 중 어느 것인지 결과에 싣지 않는다.** §15.81 이 *"현장이 보증하는 셋째 쪽이 계약 어디에도 없다"* 고 적은 그 자리의 이름이 E2 이고, 그것은 **계약 밖이 맞다** — 층 ③ 이 로봇 보고와 시간창 안에서 결합한다.
    - **외부 문서의 검증 시나리오 열 개 중 여섯을 `mimic` 위에서 이미 증명하고 셋은 의도적으로 계약 밖이며 하나만 반쯤 답한다** — 부분 완료 후 취소에서 **중단점과 잔여 파지 상태**가 없다. `partial_result` 자유 문자열뿐이다.
    - **접수 여부 불명은 계약 층에서 답한다.** 벤더 셋 다 클라이언트 참조 키를 안 받지만(Spot 은 `CaptureActionId` 만 예외) 같은 `(task_id, revision)` 재전송이 같은 핸들을 돌려주므로, 그 매핑을 드는 것이 어댑터의 일이 된다. 다만 어댑터가 재시작하면 메모리의 매핑이 사라지고, 그것은 §1.3 B-1 비목표와 맞닿는다.
    - **어댑터 가용성이 로봇 거동이다** — Spot `KeepaliveService.Policy.ActionAfter{AutoReturn, ControlledMotorsOff, ImmediateRobotOff, LeaseStale}`, E-Stop `timeout` → `SETTLE_THEN_CUT`, `cut_power_timeout` → CUT(전부 매니페스트에 있다). ADR 32 는 유지되지만 배치의 전제로 적어 둔다.

    ### 후보 다섯 — 결정이 아니다

    ① 도달 근거 등급을 결과에 · ② 취소·실패 시 잔여 물리 상태(발신자 있음 — Spot `is_gripper_holding_item`·`CarryState`, G1 `press_sensor_state`, Digit `get-execution-state`) · ③ `RUNNING` 갱신이 물리 상태를 만날 때(부품을 든 채 `Halt → Reset → Start`) · ④ 이벤트 쪽 옛 revision 규칙 · ⑤ 어댑터 재시작 후 매핑. ②는 발신자가 있어 ADR 9 를 통과하므로 가장 먼저 열 수 있고, ①과 함께 **결과 어휘 정준화**의 일부다. 계약에 넣지 않은 이유는 ADR 9 다. **→ ②는 §15.85에서 열었다**(`WatchTaskResponse.hold`, 0.4.0).

85. **잔여 물리 상태를 계약에 열었다 — 그리고 "취소가 내려놓기가 아니다"가 두 어댑터에서 드러났다.**

    §15.84 후보 ②. `WatchTaskResponse.hold`(`HoldState`, 계약 0.4.0)이 갱신마다 로봇이 무엇을 들고 있는지를 나른다. 발신자 넷(mimic · Spot · Digit · G1)과 소비자(하네스 `HoldOnCancelTest`, 완료 기준 8b의 나머지 반)가 함께 생겼으므로 ADR 9를 통과한다. 불변식은 §4.4에 있다 — `CANCELLED`와 `HOLDING`은 함께 오지 않는다.

    ### 발신자마다 답이 다르고, 그 차이를 값으로 남긴다

    | 발신자 | 근거 | 답 |
    |---|---|---|
    | Spot | `ManipulatorState.is_gripper_holding_item` — 벤더가 불리언을 준다. 세 기종 중 유일하다 | `HOLDING`/`EMPTY`. 팔 없음(`manipulator_state` 비어 있음)은 쥘 것이 없으니 `EMPTY`, 읽기 실패는 `NOT_OBSERVABLE`. `object_ref`는 늘 비어 있다 — 대상의 이름을 받는 스킬을 안 든다 |
    | Digit | `get-execution-state`의 노드 상태 — 벤더는 파지를 **발행하지 않는다**(SDK 메시지 전수) | **추론**: 최근 `action-pick`이 `success`이고 뒤에 `success`인 `action-place`가 없으면 `HOLDING`(지금 태스크가 `pick_place`면 그 `object_id`). 트리에 `action-pick`이 없으면 `NOT_OBSERVABLE` — 빈손이 아니다. 이전 시퀀스가 놓기에 실패한 채 트리가 비워졌을 수 있고 그것을 볼 표면이 없다 |
    | G1 | 없음 — `HandState_.press_sensor_state`는 원시 압력값 | 언제나 `NOT_OBSERVABLE`. 문턱을 우리가 정해 불리언으로 만들면 로봇의 답처럼 보이는 우리의 짐작이다(§15.65) |
    | mimic | 계약의 `grasps_object`(쥐는가)와 `is_object_reference`(든 것의 이름) — 생성 디스크립터에서 읽는다(`ObjectReferences`). 스킬 이름은 미믹 어디에도 없다. ~~대상의 이름을 받는 스킬~~ → 쥐는 스킬(§15.87 정정) | 쥐는 스킬이 `RUNNING`·`PAUSED`·`CANCELLING`·`CANCELLED_RECOVERY_FAILED`면 `HOLDING`, `ACCEPTED`·`SUCCEEDED`·`CANCELLED`면 `EMPTY`, 실패 셋은 직전 값 유지(실패가 물건을 내려놓지는 않는다), `PAYLOAD_LOST`가 서면 `EMPTY` |

    ### 열면서 드러난 것 — 멈추는 것과 내려놓는 것은 다르다

    Spot의 `StopMission`·`StopCommand`와 Digit의 `remove-action`은 **멈추는** 프리미티브이지 내려놓는 프리미티브가 아니다. 앞 판의 두 어댑터는 멈춤이 성공하면 `CANCELLED`를 적었다 — 계약이 `CANCELLED`에 건 뜻(복구까지 마쳤다)을 확인하지 않고. 이제 멈춘 뒤 `hold()`를 보고 들고 있으면 `CANCELLED_RECOVERY_FAILED`를 적는다. 못 봤으면 `CANCELLED`로 적되 `NOT_OBSERVABLE`을 함께 나른다 — **모름을 실패로 접지 않는다**(§15.65의 다른 얼굴). G1은 볼 수 없으므로 언제나 뒤의 경우다.

    ### 단순화와 미결

    - mimic은 도는 동안 **내내** 든 것으로 친다. 대상까지 걸어가는 구간을 프로파일이 선언하지 않고, 우리가 정하면 그것이 관측처럼 보인다. 불변식은 이 단순화 아래에서도 성립하며 소비자가 기대는 것은 그것뿐이다.
    - `RUNNING` 갱신(`Halt → Reset → Start`)도 든 채로 지난다 — 부품을 든 채의 `Reset`이 무엇인지는 §15.84 후보 ③ 그대로 열려 있다. 달라진 것은 그 질문을 **값으로** 볼 수 있게 됐다는 것뿐이다.
    - 스냅샷(`GetSnapshot`)에는 넣지 않았다. 잔여 상태가 필요한 자리는 종착이고 태스크 로그는 축출이 없어 `WatchTask(from_update_index=0)`로 되짚을 수 있다. 중간에 들어온 소비자가 *지금* 들고 있는지를 스냅샷 하나로 알아야 하는 날 다시 본다.
    - 후보 ①(도달 근거 등급)은 그대로다. `kind`는 E0/E1을 가르지 않는다 — Spot 직결의 `HOLDING`과 플릿 경유의 `HOLDING`이 같은 값이다.
    - Digit의 추론은 **근거 등급이 한 단계 낮다.** `action-pick` 성공이 곧 쥐고 있음이라는 것은 벤더 매뉴얼의 액션 의미에서 온 것이지 파지 센서에서 온 것이 아니다. 실물이 오면 `press`·`end-effector` 계열 표면에 묻는 것이 먼저다.

86. **시나리오 ②가 하네스 시험이 됐다 — 그리고 "모든 RPC가 tick을 부른다"가 처음으로 시험을 물었다.**

    `docs/scenarios.md` §4의 부품 시퀀싱을 `SequencingCellTest`(harness)로 옮겼다. 슬롯 넷 = `pick_place` 태스크 넷, 생산 순서 버전 = `revision`(17 → 18), 기체 하나라 차례로. 상황표에서 계약이 답하는 것을 전부 단언한다 — 완료 슬롯 보존(같은 revision 재발행은 같은 핸들, 새 태스크 없음) · 버전 18 세 갈래(종착 = `INVALID_TRANSITION`, 미시작 = 파라미터만 교체하고 `ACCEPTED` 유지·돌면 새 대상을 든다, 도는 중 = 다시 서며 진행률 0·`hold` 유지) · S03 파지 중 취소(복구 = 빈손, 같은 `task_id`의 v18은 거절, 재작업은 새 `task_id`) · 든 채 단절(같은 요청이 같은 핸들, `WatchTask(0)` 되짚기에 `HOLDING`, 복구 뒤 이어서 완주). 프로파일은 `CancellablePickPlace` — 픽스처의 `pick_place`가 취소 불가라 값 하나만 바꾼 사본이고, 그 값이 하나뿐임을 확인한다.

    ### 시험이 물어본 것 둘

    - **`ACCEPTED`에 머물게 할 수가 없었다.** §10.4의 *"모든 RPC가 tick을 부른다"*가 `WatchTask` 진입과 `DumpInternalState`에도 걸려, 접수한 태스크를 들여다보는 순간 `RUNNING`이 된다. *"미시작 슬롯은 파라미터만 교체"*를 보려면 §10.5의 단일 스텝(`SetSingleStep`)을 켜야 했다. 관측이 상태를 바꾸지 않는다는 §12.1의 규율은 **소요시간**에 대해서만 지켜지고 있고, 전이의 시점은 관측이 당긴다 — 결정적이라 문제는 아니지만 시험을 쓰는 사람이 알아야 한다.
    - **갱신 자체가 로그 한 줄이다.** `RUNNING` 갱신은 `Halt → Reset → Start`를 한 갱신 안에서 마치고 로그에 `RUNNING` 한 줄을 더 적는다(ACCEPTED · RUNNING · 다시 선 RUNNING). 시간이 더 흘러야 나오는 것이 아니다.

    ### 시험이 고정하지 않고 드러내는 것

    도는 슬롯의 버전 갱신(§15.84 후보 ③). 계약은 지금 든 채로 다시 세운다 — 진행률은 0부터, `hold`는 `HOLDING` 그대로. 그것이 맞는지는 열려 있고, 이 시험은 그 거동이 **조용히 바뀌지 않게** 붙들 뿐이다. 바꾸는 날 이 시험의 그 단언을 함께 고쳐야 하며, 그것이 의도다.

87. **시나리오 ③이 하네스 시험이 됐다 — 그리고 "참조하는 것과 쥐는 것은 다르다"를 첫 시험이 잡았다.**

    `docs/scenarios.md` §5의 설비 점검 순회를 `InspectionPatrolTest`(harness, 4족 픽스처 `quadruped-b`)로 옮겼다. 지점 셋 = `navigate_to` + `inspect` 여섯. 점검 일시정지·재개, 위치 상실 뒤 사람 개입과 재시도, 점검 취소, 그리고 **점검 결과를 실을 자리가 없다는 사실**(§15.76 — `partial_result` 문자열뿐, 아무도 안 채운다)을 `LongRunningTaskTest`와 같은 방식으로 고정했다.

    ### 첫 시험이 빨갰다 — 점검 중인 로봇이 대상을 든 채로 보고됐다

    §15.85의 미믹 규칙이 *"대상의 이름을 받는 스킬은 도는 동안 든 채"* 였다. `inspect(target)`의 `target`은 `is_object_reference`이므로 그 규칙대로면 점검 중인 4족이 펌프를 들고 있다. **참조와 쥠을 접은 것이다.** 카탈로그에 `grasps_object`(MessageOptions, 계약 0.5.0)를 두고 `PickPlaceV1`에만 켰다. 쥐는지는 계약이 말하고, 든 것의 이름은 여전히 `is_object_reference` 파라미터에서 온다. 미믹은 스킬 이름을 여전히 모른다.

    이것은 §15.78의 공간 셋과 같은 종류의 실수다 — 이름 공간 하나(`is_object_reference`)에 뜻 둘(참조/쥠)을 얹었다. 구분할 수 있으면 구분한다. 그리고 §15.84 후보 ②를 열 때 미믹 시험을 `pick_place`로만 써서 놓쳤다 — **한 스킬로 만든 규칙은 다른 스킬에 대 봐야 규칙이다.**

    ### 시험이 물어본 것

    - **점검 결과.** 시나리오 ③의 결과는 *항목별 수행 상태와 측정값 또는 증거 자료 참조*인데 계약에는 그 자리가 없다. Spot에서는 `DataAcquisition`의 `CaptureActionId`가 결속 자리이고(§15.75) Orbit에서는 `RunCapture`인데, 계약이 그것을 위로 올릴 길은 `partial_result` 문자열 하나다. **결과 어휘 정준화(후보 ①과 함께)의 범위에 "관측을 올리는 자리"가 들어간다.**
    - **결함은 인터록이 아니다.** 기체 수준 `LOCALIZATION_LOST`(`can_accept_new_task=false`)가 서도 계약은 다음 지점의 `navigate_to`를 받는다 — §4.6 *"판단은 밖으로, 사실은 안으로"*. 시나리오 ③이 점검 결과를 안전 인터록으로 쓰지 않는다고 그은 경계(ADR 32)가 계약 거동으로 확인된다.
    - **소비자가 점검 지점의 등록을 물을 수 없다.** 순회의 `location`들은 사이트가 로봇에 등록한 이름이어야 하는데(ADR 35), `client`에 `GetKnownSiteNames`를 부르는 메서드가 없다 — 레지스트리만 든다. 얇은 소비자가 그것을 들어야 하는지는 정하지 않았다. 지금은 시험에서 뺐다.

88. **미들웨어의 가운데가 생겼다 — 모듈 `picasso`, 그리고 "층 ③은 없다"가 끝났다.**

    ADR 38 과 [미들웨어 중앙 설계](2026-09-09-middleware-core-design.md)에 따라 모듈 `picasso` 를 세우고 시나리오 ②를 **미들웨어 층**에서 돌렸다(`SequencingRackTest` 여섯). 상류(예상 소비자 = 시험)가 ISA-95 모양의 JobOrder 를 넣으면 `PrepareSequencedRack` 이 슬롯마다 `pick_place` 로 조합하고, `Middleware` 가 계약(④)으로 mimic 에 돌리며, 셀 검증 장치(PLC/WCS Mimic)의 신호와 결합해 근거 등급을 매기고, JobResponse 를 아웃박스에 낸다. 두 축(`physical_state` × `upstream_ack`)이 코드가 됐다.

    ### 이 층이 생기고 나서야 답이 된 것

    - **부분 완료.** C형 부족은 계획 단계에서 드러난다(`NO_SOURCE_FOR_MATERIAL`) — 완료 슬롯은 보존되고 실행은 `PARTIAL` 로 통보되며, v18 이 공급을 채우면 **그 슬롯만** 다시 계획된다. 계약 층에서는 "슬롯마다 태스크 하나" 까지였고 그것을 모아 말하는 자리가 없었다(§15.86).
    - **근거 미달의 완료.** 로봇은 `SUCCEEDED` 인데 설비가 말이 없으면 `UNVERIFIED` 다 — 재작업이 아니라 운영자 확인. 설비가 다른 자재를 봤으면 `VERIFICATION_MISMATCH` 로 `FAILED`(보고서 12.3). 계약 층에서 "답하지 않는 것이 맞다" 고 적었던 E2 가 여기서 답이 된다.
    - **결과 통보와 그 확정.** JobResponse 는 아웃박스에 남고 `ack` 가 둘째 축을 닫는다. 통보 재전송이 명령 재전송이 아니라는 것(보고서 13.3)이 구조로 갈렸다.
    - **취소 응답.** 완료 단위·진행 중 단위·미시작 단위·잔여 `hold`·정리 결과. 미시작 단위는 자동으로 돌지 않는다.

    ### 정직하게 적어 둘 것

    - 근거 결합에 **시간창 δ 가 없다.** 지금은 "묻는 순간의 신호" 다. 보고서 12 의 시간창과 래치 비트는 계획의 단계 3 이다.
    - **어댑터가 아직 벤더 코드를 정준 분류로 안 옮기고**, mimic 은 `WatchTaskResponse.fault` 를 안 채운다. 그래서 단위의 `failureClass` 는 지금 상태 이름으로 남는다 — 지어내지 않고 비워 둔 것이며, 단계 5 다.
    - **IN_DOUBT 는 열거값만 있다.** 해소 순서(13.2)의 집행은 단계 6.
    - 구동이 당기기(`pump()`)라 시험은 비동기 팔로워를 잠깐 기다린다. 결정성은 지켰지만(가상 시계) 시험 코드에 `sleep(40ms)` 이 있다 — 미믹의 tick 과 같은 종류의 관측 구동이며, 운영 배치에서는 스케줄러가 그 자리다.
    - 결함 주입 다섯(불일치를 일치로 · 신호 없음을 완료로 · 완료 슬롯 재계획 · 취소 뒤 계속 · 통보 누락) 전부 잡혔다.

89. **시나리오 ①이 미들웨어 층에서 돈다 — 하류가 둘이 됐고, 둘을 가르는 것은 기종이 아니라 종류다.**

    `DeliverContainer` 와 두 번째 하류 포트 `AmrFleetPort` 를 더했다(계획 단계 4). 운반 전체를 **플릿에 D 수준으로 위임**하고 결과만 받는다 — 보고서 5장이 *"Fleet 시스템이 용기 운반 전체를 제공하면 하나의 작업으로 위임한다"* 고 적은 그 배치다. `AmrFleetMimic` 은 **프로젝트용 계약의 더블**이지 벤더 API 의 재현이 아니며(`scenarios.md` §1 규칙 1 — 벤더 AMR API·VDA 5050 은 구현하지 않는다), 세계 하나(자리 → 용기, 막힌 목적지)를 들고 `tick` 마다 접수 → 인수 → 이동 → (대기) → 인계로 나아간다. 인계 설비(`CellMimic`)는 플릿이 실제로 내려놓은 것을 **본다** — 시험이 침묵시키기 전까지.

    ### 이 시나리오가 계약에 요구한 것

    - **단위에 `route` 가 생겼다** — `ROBOT`(계약 ④의 원자 스킬) / `FLEET`(D 수준 위임). 미들웨어가 하류의 **종류**로 갈리는 것은 기종 분기가 아니다. 모듈에 기종 이름은 여전히 없고 게이트 7번이 그것을 지킨다.
    - **플릿의 완료는 E1 이다.** `DELIVERED` 의 뜻을 플릿 계약이 정한다 — 도착 ∧ 하역 ∧ 인수 ∧ **AMR 이 계속 보유하지 않음**. 그것이 보고서 10.2 의 *"Fleet C처럼 하류의 완료 조건이 계약과 일치하면 그대로 근거로 쓴다"* 이고, 그래서 요구가 E1 이면 설비를 묻지 않는다. 그 용기가 **그** 용기인지는 E2 다.
    - **플릿 계약은 클라이언트 참조를 받는다.** 같은 참조로 다시 맡기면 같은 운반이다 — 조사한 실물 하류 셋이 하나도 안 주는 것(보고서 13.2)을 우리 계약에는 넣었다. 접수 직후 단절의 `IN_DOUBT` 가 이것으로 풀린다(단계 6).
    - **`SlotSignal.material` 을 `identity` 로 고쳤다.** 설비가 읽는 것은 부품 타입 라벨이기도 하고 용기 태그이기도 하다 — §15.80 의 신원 수단 그대로다.

    ### 상황표 셋이 답이 됐다 (보고서 5장)

    | 상황 | 미들웨어 |
    |---|---|
    | 목적지에 이전 용기가 남아 있음 | 플릿 `WAITING_HANDOVER` → 단위 `note`, 실행은 `RUNNING`(실패가 아니다), **지연 보고 한 번**. 기다리는 동안 아무 데도 내려놓지 않는다. 목적지가 비면 이어서 인계 |
    | 출발 위치의 용기 ID 가 요청과 다름 | 플릿이 **접수 시점에** `REJECTED_AT_SOURCE` — 인수하지 않는다. 단위 `FAILED` `SOURCE_CONTAINER_MISMATCH`, 관측한 태그를 `note` 에, 운영자 필요. 출발지는 그대로다 |
    | 물리적 인계 뒤 WMS 응답 유실 | 같은 요청 재발행은 멱등이고 운반은 다시 맡기지 않는다(`dispatches` 1). 같은 통보가 아웃박스에 그대로 있다 — 그것을 다시 건네는 것이 통보 재시도다(보고서 13.3) |

    ### 정직하게 적어 둘 것

    - 실행 종착 규칙이 하나 늘었다 — 단위가 **전부** 실패면 `FAILED`, 일부면 `PARTIAL`. 앞 판은 단위 하나짜리 실행이 실패해도 `PARTIAL` 이었다.
    - 플릿에 맡긴 운반은 **도중에 갱신하지 않는다** — 플릿 계약에 갱신이 없다. 끝난 뒤 새 주문이다. 계약 ④의 `RUNNING` 갱신과 다른 점이며 그것을 코드에 적었다.
    - 취소의 정리 동작(출발지로 되돌림)은 **이 플릿 계약이 정한 것**이다. 실물 플릿이 그렇게 한다는 근거는 없다 — Mock 구간이다.
    - 결함 주입 다섯(대기를 완료로 · 불일치를 중단으로 · E2 요구를 E1 로 · 취소 미전달 · 지연 보고 반복) 결과는 아래 커밋에.

90. **근거 결합에 시간이 들어갔다 — 시간창 δ, PLC 래치, 불일치 표 12.3 의 세 행.**

    앞 판(§15.88)의 정직 항목 첫째가 *"시간창 δ 없음 — 묻는 순간의 신호"* 였다. 그것을 닫았다(계획 단계 3). 보고서 12.1 의 식 그대로다 — `physically_done := robot_report(FINISHED, t_r) ∧ plc_signal(present, tag, t_p) ∧ t_p ∈ [t_r − δ_before, t_r + δ_after]`. `Middleware` 는 시각을 `now: () -> Instant` 로만 읽고(시험은 하네스의 가상 시계를 넘긴다), 능력이 `EvidenceWindow(before, after)` 기본값을 든다 — **현장별 설정**이지 상수가 아니다. 하류 보고 시각 `t_r` 은 계약 헤더의 `state_as_of`(§5.5) 에서 읽고 못 읽으면 지금이다. 설비 신호는 `SlotSignal.observedAt` 을 얻었고, 시각을 안 주는 설비(폴링 PLC)는 **읽은 순간**이 `t_p` 다.

    ### 단위 상태가 둘 늘었다

    - **`VERIFYING`** — 하류는 끝났다는데 설비는 아직 말이 없다. 창이 닫힐 때(`t_r + after`)까지 `pump()` 마다 다시 묻고(`rechecks`), 그 안에 들어온 신호는 **보고보다 늦게 왔어도** 이 완료의 근거다. 앞 판은 한 번 묻고 `UNVERIFIED` 였다 — 폴링 지연 몇 초를 오인계와 같은 무게로 다룬 셈이다.
    - **`OPERATOR_HOLD`** — 12.3 둘째 행. 하류는 실패라는데 설비에는 기대한 것이 창 안에 있다. `FAILED` 로 적지 않고 세운다. 자동으로 다시 돌리지도 않는다(*물리 완료 가능성*). 운영자가 `resolve(CONFIRM_DONE | REWORK)` 를 내면 각각 E2 완료 / **새 정체성**(`attempt` 가 오르고 `task_id` 에 `@r1` 이 붙는다 — 같은 `task_id` 는 계약이 같은 종착 핸들로 돌려주므로 정체성이 갈려야 다시 돈다)으로 간다. 실행 상태 `OPERATOR_HOLD` 는 이때 상류에 한 번 통보된다.

    ### 12.3 의 네 행 중 셋

    | 하류 | 설비 | 앞 판 | 이 판 |
    |---|---|---|---|
    | 완료 | 없음 | 즉시 `UNVERIFIED` | 창이 닫힐 때까지 재확인 후 `UNVERIFIED`, 몇 번 물었는지 남김. 재작업 금지, 운영자 |
    | 실패 | 있음(창 안, 태그 일치) | `FAILED` | **`OPERATOR_HOLD`** → 운영자 판단 |
    | 무응답 | 있음 | — | **단계 6**(`IN_DOUBT`, 실행 조회로 확정) |
    | 완료 | 태그 불일치 | `FAILED` `VERIFICATION_MISMATCH` | 같음 + `note` 에 **무엇을 어디서** 봤는지(`observed=A at RACK-204.S02`) — 잘못 놓인 것의 위치 기록 |

    ### 12.2 가 왜 래치를 요구하라고 했는지를 재현했다

    `CellMimic` 에 신호 모양이 셋이다 — `program`(현재값, 읽는 순간이 `t_p`) · `pulse(at, lasting)`(짧게 켜졌다 꺼짐) · `latch(location)`(PLC 쪽 래치 비트 — 지나간 펄스가 **시각과 함께** 남음). 5초 폴링에 1초 펄스는 **놓친다** → `UNVERIFIED`. 같은 펄스에 래치를 두면 잡고, 근거의 시각은 폴링 시각이 아니라 **펄스의 시각**이다. 그리고 창 앞쪽 밖의 옛 신호(10분 전부터 있던 이전 부품)는 이 완료의 근거가 아니다 — `stale` 로 적고 `UNVERIFIED`. 이 셋이 시험(`EvidenceWindowTest` 8)이고, 결함 주입 여섯(앞쪽 경계 제거 · 마감 없음 · 실패 시 설비 안 물음 · 한 번만 물음 · 재작업 정체성 유지 · 불일치 위치 누락) 전부 **겨냥한 시험이** 잡았다.

    ### 정직하게 적어 둘 것

    - **`failureClass` 가 아직 상태 이름이다**(`TASK_STATE_RETRIABLE`) — 미믹이 `WatchTaskResponse.fault` 를 안 실어서다. 16장이 감추라는 하류 상태 이름이 지금은 새고 있고, 단계 5 가 정준 분류 15개로 바꾼다. 시험이 그 사실을 그대로 단언한다 — 바뀌는 날 빨개지게.
    - 실패 시 설비 확인은 **요구 등급이 E2 이상일 때만** 묻는다. E0·E1 요구에서 로봇이 실패라 하면 설비가 무엇을 보든 `FAILED` 다 — 상류가 설비를 안 믿기로 한 것이니 우리가 대신 믿지 않는다.
    - 시간창 안에서 폴링을 잘게 하려고 시험의 걸음이 5초다(앞 판 30초). 플릿 시험은 틱 하나를 10초로 친다. 둘 다 시험의 약속이지 설비의 값이 아니다.
    - `t_r` 을 헤더의 `state_as_of` 에서 읽는 것은 미믹이 그 자리에 응답 시각을 넣기 때문에 성립한다. 실물 어댑터가 무엇을 넣는지는 어댑터마다 확인할 일이다(§5.5).

91. **정준 실패 분류가 계약을 탄다 — 어댑터가 벤더 코드를 옮기고, 상류는 분류로만 분기한다(계약 0.6.0).**

    정준 모델의 넷(태스크·상태·**실패 분류**·능력 표현) 중 실패 분류가 마지막까지 비어 있었다. `Fault.error_type` 은 실패 *모드*의 이름(프로파일이 선언, 수명과 두 불리언, `X_` 벤더 이름공간)이지 *상류에 무슨 뜻인가* 가 아니었고, README 가 *"가장 큰 남은 공백은 결과 어휘다"* 라고 적고 있었다. 미들웨어 중앙 설계 §1.4 의 열다섯(2026-09-09 결정, 그대로)을 계약에 넣었다(계획 단계 5).

    ### 계약 — 두 필드, 한 열거

    `FailureClass` 열다섯 + `UNSPECIFIED`, `Fault.failure_class`, `Fault.vendor_detail`. **모드와 분류는 다른 것이라 두 필드다** — `LOCALIZATION_LOST`·`PAYLOAD_LOST`·`CONTROL_AUTHORITY_LOST` 셋은 이름이 같고 같은 뜻이며 미믹은 그 모드에서 분류를 유도한다. 원문(코드·상태 이름·메시지)은 `vendor_detail` 에 **동반**한다 — 로그와 사후 분석의 것이지 분기의 입력이 아니다(보고서 16장). ADR 9: 발신자 = 어댑터 셋 + 미믹, 소비자 = `picasso` 의 `ExecutionUnit.failureClass`. buf 파괴 검사는 추가라 통과.

    ### 옮기는 자리는 어댑터다 — 기종마다 코드가 **나오는 자리**가 달랐다

    | 기종 | 코드가 나오는 자리 | 새 남쪽 표면 | 옮김 |
    |---|---|---|---|
    | Spot | 미션 `State.status` 는 `FAILURE` 까지만 — **이유는 항법 서비스에 있다** | `GraphLayer.navigationFeedback()`(`NavigationFeedbackResponse.Status` 열넷) · `SpotLink.behaviorFaults()`(`BehaviorFault.Cause` 넷) | `STUCK`·`AREA_CALLBACK_ERROR`·`CONSTRAINT_FAULT` → `ROUTE_BLOCKED` · `NO_ROUTE` · `LOST`·`NO_LOCALIZATION`·`NOT_LOCALIZED_TO_ROUTE` → `LOCALIZATION_LOST` · `LEASE_ERROR` → `CONTROL_AUTHORITY_LOST` · `COMMAND_TIMED_OUT`/`OVERRIDDEN` · `ROBOT_IMPAIRED` → `HARDWARE_FAULT` · `CAUSE_FALL` → `ROBOT_FELL` · `CAUSE_HARDWARE` → `HARDWARE_FAULT` |
    | Digit | `action-status.failure` 하나 — 이유는 사람이 읽는 `info` 뿐 | (기존) 실행 트리 | **액션 종류로 가를 수 있는 데까지만** — `pick_place` 는 실패한 마디가 `action-pick` 이면 `GRASP_FAILED`, `action-place` 면 `PLACE_FAILED`; 이동은 벤더의 정의(*blocked from making progress*) 그대로 `ROUTE_BLOCKED`; 트리를 못 읽으면 `UNCLASSIFIED` |
    | G1 | 태스크는 시계로 성공한다 — 코드는 **보낼 때**만 나온다 | `UnitreeError`(`UT_DECL_ERR` 열하나) · `UnitreeApiException` | 종착이 아니라 **거절**에 붙는다: `Refusal.VENDOR_REJECTED` + `Refused.failureClass`. `LOCO_ERR_INVALID_FSM_ID`·`LOCOSTATE_NOT_AVAILABLE`·`ARM_ACTION_ERR_INVALID_FSM_ID`·`ARM_ACTION_ERR_HOLDING`·`G1_AGV_ERR_NOT_INIT` → `PRECONDITION_FAILED`, 그 밖 → `UNCLASSIFIED`. 기체 결함은 과열 → `HARDWARE_FAULT`, FSM 불일치 → `PRECONDITION_FAILED` |
    | 미믹 | 프로파일 모드 | 스키마에 `failure_class` 선택 필드 | 선언이 이긴다 · 이름이 같은 코어 셋만 유도 · 나머지 `UNCLASSIFIED`(스킬 이름으로 추측하지 않는다). 태스크를 실패로 보낸 결함을 `TaskRuntime.failure` 에 붙여 **실패 상태의 갱신에만** 실으므로 `WatchTaskResponse.fault` 가 채워진다 |

    새 남쪽 타입 셋(`NavigationStatus`·`BehaviorFaultCause`·`UnitreeError`)은 매니페스트 시험 목록에 들어갔다 — 짚은 이름 전부 원문에 있고 멤버마다 짚은 것이 있다.

    ### 미들웨어

    `Middleware` 는 `fault.failure_class` 로만 분기한다. 없으면 `UNCLASSIFIED` 이지 상태 이름이 아니다 — 앞 판(§15.90 정직 항목)이 새고 있던 `TASK_STATE_RETRIABLE` 이 상류 통보에서 사라졌다. 하류 상태 이름·모드 이름·벤더 원문은 단위의 `note` 에만 남는다(`downstream=TASK_STATE_RETRIABLE error_type=SKILL_EXECUTION_FAILED`). `EvidenceWindowTest` 가 그 둘을 나눠 단언한다.

    ### 정직하게 적어 둘 것

    - **거동은 여전히 안 봤다.** 매니페스트가 보증하는 것은 이름의 실재뿐이다(C-3). 특히 Spot `NavigationFeedback` 의 `command_id` 를 비우면 최근 명령의 것이라는 진술은 벤더 proto 주석에서 온 것이고 실물에서 확인한 바 없다.
    - Spot 명령 계층(`move_relative`)의 실패는 여전히 관측하지 않는다 — 시계로 성공한다. `RobotCommandFeedbackStatus` 가 있으니 자리는 있고, 소비하는 스킬이 그 표면을 요구하는 날 연다(ADR 9).
    - `PERCEPTION_FAILED`·`GRASP_PLANNING_FAILED` 는 열거에 있으나 **지금 발신자가 없다** — Spot 의 `ManipulationFeedbackState` 는 `pick_place` 를 이 어댑터가 아직 안 들어 읽을 자리가 없고, Digit 은 대상 부재를 보낼 때 오류 봉투로 답한다(모양이 어디에도 안 적혀 있다). 열거에 둔 근거는 §1.4 의 측정(벤더가 그 값을 낸다)이지 우리 어댑터가 낸다는 것이 아니다. ADR 9 의 발신자는 벤더이고 소비자는 미들웨어이며, 어댑터 경로가 비어 있음을 여기 적는다.
    - 플릿 계약(`AmrFleetPort`)은 분류를 안 싣는다 — 프로젝트용 계약에 남은 자리이고 지금은 `UNCLASSIFIED` 다.
    - 결함 주입 아홉(분류 무시 · 로그 미부착 · 선언 무시 · Spot 둘 · Digit 둘 · G1 둘) 전부 겨냥한 시험이 잡았다 — 처음 두 번은 주입 자체가 안 돌았다(CRLF 를 가로지른 앵커, 컴파일 안 되는 주입). **못 잡음이면 주입부터 의심**이 이번에도 맞았다.

92. **결과 미확정의 해소 순서와 옛 버전의 종착 — 13.2 를 집행하고 15.1 을 보존한다.**

    계획 단계 6. 보고서 17장의 3(접수 여부 불명)·6(버전 변경 중 지연 이벤트)·9(단절 후 재동기화)이 이 층에서 단언된다. 둘 다 계약이 이미 **재료**를 주고 있었고 — 같은 `(task_id, revision)` 재전송은 같은 핸들(§4.4), 갱신마다 `revision` 을 싣고 되짚어 보내는 `WatchTask(0)` — 없던 것은 그것을 **순서와 규칙으로** 쓰는 자리였다. `scenarios.md` §4.5 가 *"이벤트 쪽 규칙은 적혀 있지 않다 — §7 후보 ④"* 라 적었던 그것이며, 답은 계약이 아니라 실행 층에 산다.

    ### `IN_DOUBT` — 요청은 갔고 답만 없다

    응답 유실은 미들웨어와 하류 사이의 선에서 난다. 미믹의 gRPC 에는 장애 주입이 없고(`InjectTransportFault` 는 발행 축) 그것이 맞다 — 하류는 정상 접수했다. 그래서 시험은 그 선의 더블(`LossyRobotPort`·`LossyFleet`)로 **위임한 뒤에 던진다.** 미들웨어는 다시 보내지 않고 단위를 `IN_DOUBT` 로 세우며 상류에 그 사실을 통보한다(16장 — `JobResponse.inDoubtUnits`). 해소는 13.2 의 순서 그대로다:

    | 순서 | 무엇 | 이 층에서 |
    |---|---|---|
    | ① 클라이언트 참조로 기존 실행 조회 | 하류가 `ExecutionLookup.CLIENT_REFERENCE` 를 선언했으면 **같은 참조로 다시 묻는다** — 계약은 같은 핸들, 플릿은 같은 운반을 돌려준다. 돌아오면 이어서 추적한다(새 실행이 아니다). 처음부터 되짚으므로 중간 전이가 유실되지 않는다(17장 9번) | `resolveDoubt`, `pump()` 한 번에 한 번, 상한 `lookupRetries`(기본 3) |
    | ② 물리 상태 관측 | 하류에 물을 수 없으면 설비를 본다. 요청 시각부터 `inDoubtGrace`(능력 기본 60초) 안에 기대한 것이 목적지에 나타나면 **잠정 완료**(12.3 셋째 행) | `verification=MATCHED`, note 에 `provisional done` |
    | ③ 운영자 | `OPERATOR_HOLD`. **자동 재실행은 없다.** 재요청은 사람이 `REWORK` 로 명시적으로 낸다 — 13.3 이 가른 *명령 재시도*이고, 새 정체성(`@r1`)이다. 근거 있이 `CONFIRM_DONE` 이면 E2, 없이면 등급을 올리지 않고 그 사실을 남긴다 | |

    **포트가 조회 능력을 선언한다** — `RobotPort.executionLookup`(계약 ④는 언제나 `CLIENT_REFERENCE`) · `AmrFleetPort.executionLookup`. 조사한 실물 하류 셋이 전부 참조 키를 안 받으므로(13.2) `NONE` 인 플릿이 현실에 가깝고, 그 값이 상류에 `JobResponse.autoResolvesInDoubt=false` 로 드러난다 — 16장 *"실행 조회 가능 여부"*. 시험 다섯: 로봇 응답 유실 → 조회 한 번에 해소·재실행 0회·E2 완료 / 조회가 계속 안 되면 상한 뒤 운영자·태스크 1개 / 참조 있는 플릿은 같은 운반 / 참조 없는 플릿에서 용기가 도착해 있으면 잠정 완료 → 운영자 → `CONFIRM_DONE` E2, `dispatches` 1 / 참조도 신호도 없으면 운영자 → `REWORK` 가 두 번째 운반.

    ### 지연 이벤트 — v17 의 완료는 v18 을 닫지 않는다

    만드는 법이 곧 발견이었다. 슬롯 하나를 v17 로 돌려 미믹에서 종착시키되 미들웨어는 아직 안 읽고, 그 사이 v18 이 온다. 미들웨어는 그 슬롯을 도는 중으로 알고 계약의 갱신을 보내는데 **이미 종착한 태스크라 `INVALID_TRANSITION`** 이다 — 앞 판은 이것을 단위 `FAILED` 로 적었다. 그러면 로봇이 실제로 놓은 부품이 사라진다. 이제 갱신 거절 중 그것만은 실패가 아니라 *"옛 버전 아래 종착함"* 으로 남기고, `pump` 가 갱신을 **버전으로 가른다**: 단위의 지금 버전보다 낮은 종착은 `Execution.lateEvents` 에 **보존**(폐기하지 않는다 — 15.1)하고, 이 버전의 갱신만 상태로 옮긴다.

    옛 버전의 완료만 있고 새 버전의 갱신은 오지 않을 때 — 그것이 17장 6번이다 — 새 버전의 완료로 적지 않는다. 물리적으로 일어난 일은 옛 파라미터로 한 일이므로 **설비가 새 버전의 기대에 대고** 본다(`strict` 검증: 요구 등급이 E0 라도 묻는다). v18 이 그 슬롯에 B 를 원하는데 로봇은 v17 대로 A 를 놓았다 → `VERIFICATION_MISMATCH` + 운영자. 같은 A 를 원했다 → E2 로 닫힌다(지연 이벤트는 그래도 남는다). 설비가 말이 없다 → `UNVERIFIED`.

    ★ 그 시험이 구멍을 하나 더 찾았다 — **도는 단위에 새 버전을 붙일 때 단위의 기대(파라미터·기대 신원·출발·목적지)는 옛 것 그대로였다.** 하류에는 새 파라미터를 보내면서 우리 쪽 기대는 안 바꿨으니, v17 의 완료가 v17 의 기대에 맞아 v18 의 완료로 적혔다. `ExecutionUnit` 의 그 넷을 버전의 것으로 두고 갱신 때 함께 바꾼다. 15.3 *"새 버전은 확정된 단위 이후에만"* 의 반대편 — 확정 안 된 단위에는 새 버전이 **통째로** 붙어야 한다.

    ### 정직하게 적어 둘 것

    - `IN_DOUBT` 중의 취소는 핸들이 없어 하류에 못 닿는다 — 해소돼 핸들이 생기는 순간 보낸다. 조회가 끝내 안 되면 운영자에게 가고 취소 요청은 거기 함께 있다. 시험은 없다.
    - `inDoubtGrace`·`lookupRetries` 는 시험의 약속이지 현장의 값이 아니다(시간창 δ 와 같은 종류).
    - 플릿 쪽 지연 이벤트는 없다 — 플릿 계약에 갱신이 없어(§15.89) 옛 버전이 생기지 않는다.
    - 어댑터가 재시작하면 참조↔하류 ID 매핑이 사라진다는 것은 여전히 밖이다(§1.3 B-1).
    - 결함 주입 아홉(조회 없는데 재전송 · 상한 없음 · 잠정 근거 반전 · 유실을 실패로 · 버전 미분리 · strict 무시 · 갱신 거절을 실패로 · 옛 기대 유지 · 상류에 언제나 자동 해소) 전부 겨냥한 시험이 잡았다. 하나는 첫 형태가 컴파일되지 않아(상수 거짓이 스마트 캐스트를 깼다) 다시 놓았다.

93. **③ 회귀 — 세 번째 능력군이 공통 엔진에 분기 없이 들어갔고, 이 기체가 다르게 답하는 취소가 명세에 드러났다.**

    계획 단계 7, 보고서 17장 10번 — *"공통 실행 엔진 변경 없음, 시나리오 1~9 회귀 통과, 미지원 취소·조회 차이가 명세에 노출"*. 보고서 7장이 합격 기준을 정확히 적어 두었다: *'아무 코드도 수정하지 않는다'가 아니다. 새로운 점검 의미는 확장 지점에 추가하되, 공통 실행 엔진에 `if robot == SPOT` 같은 분기가 퍼지지 않고 기존 두 업무의 의미가 유지되는지를 본다.*

    ### 무엇이 어디에 더해졌나

    | 자리 | 변경 | 분기인가 |
    |---|---|---|
    | 확장 지점 `LogicalCapability.kt` | `InspectAsset`(+95줄): 점검 대상 = `EquipmentUse = inspection_target`(`location`·`item` 속성), 대상 하나 = `<대상>.travel`(`navigate_to`) + `<대상>`(`inspect`), 목록 순서가 순회 순서, `mode` 는 주문 파라미터에서. 최고 등급 **E0**. 살필 자리가 없으면 계획에서 `NO_LOCATION_FOR_TARGET` | 아니다 — 능력 하나가 늘었다 |
    | 엔진 `Middleware.kt` | 21줄 더하고 2줄 뺐다 — **일반 규칙 셋**: ① 요구 등급 > 능력 최고 등급이면 접수 거절(보고서 11.3 *확인 수단이 없으면 제공 불가* — 앞 판에는 이 검사가 없었고 E2 를 받아 UNVERIFIED 로 끝냈을 것이다) ② 종착의 `partial_result` 를 `ExecutionUnit.result` 로 (E0 의 내용) ③ 취소 거절 코드를 `CancelReport.refusal` 로. 기본 능력 목록에 `InspectAsset` 한 줄 | 아니다 — 셋 다 능력·기종을 모른다. ①은 ③이 드러낸 일반 결손이다 |
    | 모델 `Model.kt` | `ExecutionUnit.result` · `CancelReport.refusal` · `JobResponse.results` | 아니다 |

    기존 시험 전부(①②·시간창·IN_DOUBT·지연 이벤트) 손대지 않고 초록이다 — 17장 1~9 회귀.

    ### 취소가 다르게 답한다 — 그리고 그것이 응답에 있다

    4족 픽스처는 `navigate_to` 취소 `NO`, `inspect` 취소 `YES` 다. 이동 중 취소하면 하류가 `CANCEL_UNSUPPORTED` 로 거절하고 — 앞 판은 그 응답을 **버렸다** — 진행 중 단위는 끝까지 가서 다음 경계에서 멈춘다. 이제 `CancelReport.refusal = REJECTION_CODE_CANCEL_UNSUPPORTED`, `inProgressUnit = PUMP-01.travel`, `completedUnits` 에 그 단위, `notStartedUnits` 다섯 — 어디서 왜 멈췄는지가 취소 응답에 있다. 점검 중 취소는 `refusal = null`, `cleanup = done`, 빈손. 16장 *"취소 수준 지원 범위와 취소 가능 지점 제약"* 이 처음으로 실행 층의 값이 됐다. 지원하지 않는 것을 지원하는 것처럼 감추지 않는다(7장).

    ### 정직하게 적어 둘 것

    - **점검 결과를 실을 자리가 비어 있다.** 시나리오 ③의 결과는 *항목별 수행 상태와 측정값 또는 증거 자료 참조*인데, 상태는 오고 참조는 계약의 `partial_result` 하나뿐이며 아무 발신자도 채우지 않는다(§15.76). `JobResponse.results` 는 그 자리이고 지금 빈 채로 **시험이 고정한다** — 채우는 쪽이 생기면 빨개진다(하네스 `InspectionPatrolTest` 와 같은 모양). 결함 주입이 그 시험이 살아 있음을 확인했다(엔진이 참조를 지어내면 잡힌다).
    - **공통 엔진은 기체 수준 결함을 아직 안 읽는다.** 점검 중 `LOCALIZATION_LOST`(can_accept_new_task=false)가 서도 엔진은 다음 단위로 간다 — 그 항목은 `LOCALIZATION_LOST` 로 실패하고 나머지 다섯은 완료해 `PARTIAL` 로 끝난다. 결함은 인터록이 아니라는 계약 거동(§15.87·ADR 32)과 일관되지만, *"이 기체가 지금 새 태스크를 받을 수 있는가"* 를 실행 층이 보는 것은 열려 있다(이벤트·스냅샷 소비).
    - 이동 중 취소가 거절될 때의 `inProgressUnit` 은 그 단위가 **끝난 뒤**의 값이라 `completedUnits` 에도 같은 이름이 있다 — 중복이 아니라 *"이 단위에서 멈추려 했고 그것이 끝났다"* 는 뜻이다. 자리 하나로 두 사실을 말하고 있으니, 상류가 헷갈리면 `stopPoint` 를 따로 낼 일이다.
    - 경로 ③(Orbit)은 여기 없다 — 취소가 없는 하류이므로 어댑터가 `CANCEL_UNSUPPORTED` 로 답해야 하고, 그러면 위의 거절 경로가 그대로 그 차이를 드러낸다. 실물 없이 적는 예상이다.
    - 결함 주입 여섯(이동을 점검으로 · 능력이 E2 주장 · 자리 없음 미분류 · 엔진이 상한 무시 · 거절 기록 반전 · 결과 지어냄) 전부 겨냥한 시험이 잡았다. 새 시험 일곱이 첫 실행에 전부 초록이었던 것이 주입을 돌린 이유다.

    **계획 일곱 단계가 다 닫혔다.** 열린 것: 기체 수준 결함의 실행 층 소비 · 점검 결과 참조의 발신자 · `client` 의 `GetKnownSiteNames` · registry 와 실행 상태의 관계 · RB-Y1 별도 트랙 · 어댑터 재시작 후 매핑(B-1).

94. **기체 수준 결함을 실행 층이 읽는다 — "새 태스크를 받을 수 없다" 는 말을 듣고 다음 단위를 보내지 않는다.**

    §15.93 의 첫 정직 항목을 닫았다. 계약은 결함을 인터록으로 쓰지 않는다 — 기체 수준 `LOCALIZATION_LOST`(`can_accept_new_task=false`)가 서도 다음 `StartTask` 를 받는다(§15.87, ADR 32). 그것은 계약(④)이 정책을 갖지 않아서이고(§4.6 *"판단은 밖으로, 사실은 안으로"*), **그 밖이 실행 층(③)이다.** 앞 판의 엔진은 그 사실을 읽지 않고 다음 지점으로 로봇을 보냈다 — 위치를 잃은 4족에게 다음 방으로 가라고 한 셈이다.

    ### 문 하나, 일반 규칙

    다음 단위를 시작하기 전에 `RobotPort.faults()`(계약 `GetSnapshot.faults`, `client.snapshot()` 신설)로 활성 결함을 묻고, `can_accept_new_task=false` 인 것이 있으면 보내지 않는다. 실행은 `OPERATOR_HOLD`, 상류에는 `JobResponse.blockedBy`(정준 분류)와 `operatorRequired` — 16장 *"자동 복구가 불가능해 운영자 판단이 필요하다는 사실"*. **자동으로 넘어가지 않는다.** 결함이 사라지거나 사람이 `release()` 로 감수해야 다음 단위가 나간다. 감수는 결함을 지우지 않는다(계약에 지우는 표면이 없고, 그것은 기체 쪽 일이다) — 감수한 결함의 열쇠가 `acknowledgedFaults` 에 남아 같은 결함은 다시 막지 않고 **새 결함은 다시 막는다.** 능력도 기종도 모르는 규칙이라 `PrepareSequencedRack`·`DeliverContainer`(플릿 단위는 문을 안 거친다 — 기체 결함이 아니다)·`InspectAsset` 에 같은 문이 선다.

    **`null` 은 없음이 아니다.** 스냅샷을 못 받으면 막지 않는다 — 관측 실패로 현장을 세우지는 않는다 — 그러나 그 단위에 *"못 물어봤다"* 를 남긴다. 원장의 `Observed`/`NotObservable` 과 같은 규율이며, 시험은 결함을 못 보는 포트(`FaultBlindRobotPort`)로 그 경로를 따로 돈다.

    ### 정직하게 적어 둘 것

    - 문은 **시작 직전에 한 번** 묻는다. 도는 단위 중에 선 결함은 그 단위의 종착(대개 `NEEDS_INTERVENTION` → 실패)이 말하고, 다음 단위 앞에서 문이 다시 묻는다. 유휴 중에 선 결함은 다음 주문의 첫 단위 앞에서 본다 — 그 사이를 이벤트로 듣지는 않는다(스냅샷만, 이벤트 스트림 소비는 열림).
    - `can_continue_current_task=false` 는 여기서 안 쓴다 — 도는 태스크를 세우는 것은 하류(어댑터·미믹)가 이미 하는 일이고(§4.5 전파 규칙), 실행 층이 그 위에 또 세우면 두 층이 같은 판단을 한다.
    - 미믹에서 `UNTIL_CLEARED` 결함은 영영 남는다(지우는 제어 RPC 가 없다). 그래서 시험의 재개는 언제나 `release()` 다 — *결함이 사라져서* 풀리는 경로는 시험이 없다.
    - 결함 주입 다섯(문 제거 · 문 반전 · 감수 무시 · 상류 미보고 · 관측 불가 흔적 삭제) 전부 겨냥한 시험이 잡았다. 새 시험 둘이 첫 실행에 초록이었다.

95. **실행 층이 이벤트 스트림을 소비한다 — 스냅샷으로 세우고 재생으로 이어 붙이고 벗어나면 다시 세운다.**

    §15.94 의 첫 정직 항목(*시작 직전 스냅샷 한 번*)을 닫았다. 계약 §4.8 이 소비자에게 요구한 패턴 그대로다: `GetSnapshot` 으로 현재값(활성 결함·연결 상태·태스크 상태)과 **다음에 올 번호**를 받고, `ReplayEvents(from)` 로 그 사이의 사실을 이어 붙이며, `SEQUENCE_EVICTED` 면 스냅샷부터 다시 세운다. 이것이 보고서 17장 9번 *단절 후 재동기화 — 스냅샷 조회로 놓친 전이를 복원, 중간 상태 유실 없음* 이다.

    ### 무엇이 어디로 가나

    | 원천 | 쓰임 |
    |---|---|
    | 스냅샷의 결함 | 다음 단위 앞의 문(§15.94)의 **권위** — 현재값이다 |
    | 스냅샷의 연결 상태 | `CONNECTION_BROKEN`·`OFFLINE` 이면 도는 로봇 단위의 결과를 **미확정**(`IN_DOUBT`)으로 두고 상류에 알린다. `HIBERNATING` 은 침묵하지만 정상이다(§4.7). 돌아오면 스냅샷의 태스크 상태로 다시 세워 이어 간다 — 재실행이 아니다 — 그리고 다시 알린다 |
    | 재생의 이벤트 | 실행의 **자취**(`Execution.eventTrail`) — 태스크 전이는 그 태스크를 든 실행에, 결함 발생·해소와 능력 변경은 그 기체의 실행 전부에. 발행 열의 번호와 시각을 **다시 찍지 않고** 그대로 든다 |
    | 축출 | 스냅샷은 이미 세웠으니 거기서부터 — `RobotView.resyncs` 가 센다 |

    `JobResponse.connection` 이 늘었다(계약 `ConnectionState` 의 이름). 기체를 못 봤으면 `ONLINE` 이라 하지 않고 `UNSPECIFIED` 다 — 없음과 모름을 접지 않는다(§15.94 와 같은 규율).

    ### 미믹의 구멍 하나

    첫 연결 단절 시험이 바로 빨개졌다 — 미들웨어가 아니라 **미믹이** 틀려 있었다. `GetSnapshotResponse.connection_state` 는 계약에 있는데(필드 6) `EventServiceImpl.getSnapshot` 이 채우지 않아 소비자가 언제나 `UNSPECIFIED` 를 봤다. 연결 상태는 MQTT connection 스트림으로만 나가고 있었다. 채웠다(`events.connectionState`, 같은 값). **계약에 자리를 두고 채우지 않는 것은 자리가 없는 것보다 나쁘다** — 소비자가 그 자리를 믿고 분기를 짠다(ADR 9 의 반대편). 이 층이 처음 그 자리를 읽어서 드러났다.

    ### 정직하게 적어 둘 것

    - 펌프마다 RPC 둘(스냅샷·재생)이다. 운영 주기(5초)에서 문제될 크기는 아니지만, 이벤트만으로 현재값을 세우고 스냅샷을 축출 뒤에만 쓰는 쪽이 더 가볍다 — 결함의 발생·해소가 전부 이벤트로 나온다는 것을 믿을 수 있을 때의 일이다. 지금은 스냅샷을 권위로 둔다.
    - 연결 단절 중에도 `WatchTask` 는 흐른다(미믹의 단절은 발행 축의 사실이고 gRPC 는 산다). 그래서 미확정은 실행 상태의 표시이지 단위의 정지가 아니며, 단절 중 종착하면 그 종착은 선다. 실물에서 단절이 gRPC 까지 끊으면 `WatchTask` 스트림의 오류가 함께 오고, 그 경로는 `IN_DOUBT` 해소(§15.92)가 맡는다 — 둘을 합쳐 본 시험은 없다.
    - 연결 상태의 원천은 스냅샷이다. MQTT connection 스트림(retain, Last Will)은 이 층이 아직 안 듣는다 — 브로커 결선은 열려 있다.
    - 결함 주입 여섯(단절 판정 반전 · 축출 무시 · 커서 미전진 · 복귀 뒤 `linkBroken` 미해제 · 못 봤는데 ONLINE · **미믹이 다시 연결 상태를 안 채움**) 전부 겨냥한 시험이 잡았다. 둘은 처음에 빠져나갔다 — ① *복귀 뒤 상태를 IN_DOUBT 로 두기* 는 바로 뒤의 `pumpRobotUnit` 이 상태를 다시 정하므로 **동치 변이**였고, 진짜 결함(`linkBroken` 미해제)으로 바꿔 놓으니 잡혔다. ② *커서 미전진* 은 마지막으로 읽은 이벤트가 **스킬 전이**라 자취에 안 남아 중복이 안 보였다 — 스킬 전이도 자취에 남기니(감사로도 맞다) 같은 시험이 잡았다. **주입이 빠져나가면 시험이 아니라 관측을 먼저 의심한다** — 이번 둘 다 그랬다.

96. **정리 여섯 — 미결로 적어 두었던 작은 것들을 닫는다.**

    | 항목 | 어디서 미결이 됐나 | 무엇을 했나 |
    |---|---|---|
    | `client` 에 `GetKnownSiteNames` 없음 | §15.87 | `PicassoClient.knownSiteNames()`. 판단하지 않는다 — `unsupported` 와 빈 목록을 가르는 것도, `total_count` 로 잘림을 아는 것도 소비자의 일. 하네스 `KnownSiteNamesTest` 3(아는 이름 / 0개 vs 못 함 / 40개 중 잘림) |
    | buf 디스크립터 재생성이 빌드 배선에 없음 | §15.85 부터 손으로 | `./gradlew :contracts:bufDescriptor` — `tools/buf` 와 같은 컨테이너·인자로 Docker 를 **직접** 부른다(bash 래퍼는 Windows 의 CreateProcess 로 못 부른다). `build` 에는 안 건다 — 게이트가 `contracts` 에 빌드 의존을 안 거는 것은 설계의 결정이라(§3.2) 순서를 Gradle 이 강제하지 않되, proto 를 고친 뒤 어디서든 한 명령이면 되게 했다. README 에 적음 |
    | `EquipmentUse` 어휘가 코드 상수뿐 | §15.88·§15.93 | 중앙 설계 §3 에 표 — 값·속성·뜻·읽는 능력. 능력은 자기 열의 낱말만 본다 |
    | 프로파일 넷에 `failure_class` 미선언 | §15.91 | `humanoid-a`·`no-pause`(`SKILL_EXECUTION_FAILED`→`GRASP_FAILED`) · `quadruped-c`(`X_PICASSOREF_NAVIGATION_BLOCKED`→`ROUTE_BLOCKED`) · `spot-arm`(`X_BOSTONDYNAMICS_FALL`→`ROBOT_FELL`, `X_BOSTONDYNAMICS_HARDWARE`→`HARDWARE_FAULT`). `LOCALIZATION_LOST` 는 유도된다 |
    | `inProgressUnit` 이 완료 단위와 겹침 | §15.93 | `CancelReport.stoppedAfter` — 단위가 끝까지 갔으면(거절됐든 취소가 닿기 전에 끝났든) 중단된 단위가 아니라 **그 뒤에서 멈춘 경계**다. 그때 `inProgressUnit` 은 `null` |
    | `IN_DOUBT` 중 취소 시험 없음 | §15.92 | 둘 — 취소 가능한 프로파일 사본: 핸들이 생기는 순간 취소가 닿아 `CANCELLED`·재실행 0 / 픽스처(취소 `NO`): 거절이 기록되고 `stoppedAfter` 가 그 단위 |

    시험 하나가 앞 판의 빈틈을 하나 더 보였다 — 미확정이 풀리며 보낸 취소의 **거절을 기록하지 않았다**(`cancel()` 경로만 기록했다). 이제 두 경로가 같은 자리에 적는다.

    ### 정직하게 적어 둘 것

    - ~~`bufDescriptor` 는 Docker 가 있어야 돈다. Docker 없는 대안(protoc 가 이미 만드는 `contract-descriptor/picasso.desc` 를 쓰는 것)은 게이트의 입력을 바꾸는 일이라 여기서 안 했다.~~ → **그 대안이 답이었다**(§15.111). 태스크는 지웠다.
    - `KnownSiteNamesTest` 의 잘림 시험은 `protocol_limits.max_array_length` 가 40 보다 클 수도 있어 조건부 단언이다 — 잘리면 `total_count` 가 말한다는 것만 고정한다.

97. **Spot 이 `inspect` 를 든다 — 취득 계층 위에서, 대상의 이름은 세계 모델에 묻고, 결과는 `DataIdentifier` 참조로.**

    ③의 실물 경로 ①(보고서 7장 — *`DataAcquisitionService`(C 수준, `request_id`·`STATUS_COMPLETE`·`data_saved[]`)*)을 어댑터가 실제로 든다. 지금까지 시나리오 ③은 미믹 위에서만 돌았고 결과 참조를 채우는 발신자가 없었다(§15.93 정직 항목). 이제 발신자가 하나 있다.

    ### 판정을 바꿨다 — PARTIAL → YES, 근거와 함께

    `distance/spot-arm.json` 은 `inspect` 를 PARTIAL 로 적고 *"진짜 결손은 모양의 어긋남 — 우리 계약은 물체 신원을 묻는데 BD 는 어디에 서서 어느 이름의 액션을 돌리는가"* 라 했다. 어댑터를 실제로 들면서 그 어긋남을 두 자리가 메운다는 것이 보였다.

    | 계약이 묻는 것 | Spot 의 자리 | 어댑터 |
    |---|---|---|
    | 대상의 신원(`target`) | `WorldObject.name` — 사이트가 `MutateWorldObjects` 로 등록한 사람의 이름. Digit 의 객체 모델에서 우리가 이미 결속으로 인정한 것과 같은 구조 | `ListWorldObjects` 로 **있는지 묻는다**(`navigate_to` 가 지도에 묻는 것과 같은 규율, ADR 34·35). 없으면 `SITE_NAME_UNKNOWN`, 둘이면 `SITE_NAME_AMBIGUOUS` — 취득하지 않는다 |
    | 관측을 대상에 묶기 | `CaptureActionId{action_name, group_name}` — 이 표면에서 클라이언트가 이름을 정해 넣는 유일한 자리(§15.75) | `action_name = target`, `group_name = 태스크`. 결과 `DataIdentifier.action_id` 에 그대로 돌아온다 |
    | 결과 참조 | `GetStatusResponse.data_saved[]` | `channel/data_name#id@action_name/group_name` 을 `;` 로 이어 `result()` 로 — 계약의 `partial_result` 가 될 것 |
    | 생명주기 | `AcquireData` → `GetStatus` 열하나 → `CancelAcquisition` 넷 | `ACQUIRING`·`SAVING`→RUNNING · `COMPLETE`→SUCCEEDED · `ACQUISITION_CANCELLED`→CANCELLED(쥔 것이 없다) · `CANCEL_ACQUISITION_FAILED`→CANCELLED_RECOVERY_FAILED · `TIMEDOUT`→`COMMAND_TIMED_OUT` · 데이터·내부 오류·요청 분실→`UNCLASSIFIED`+원문. 취소는 **답이 온다** — `FAILED_TO_CANCEL` 이면 거절이고 취득은 계속된다. 일시정지는 표면이 없다 |

    남는 것 — **어느 카메라가 그 대상을 보느냐** — 는 벤더가 못 주는 것이 아니라 현장이 정하는 것이다. `AcquisitionRequestList` 는 센서를 받지 대상을 안 받는다. 그래서 어댑터는 광고된 영상 원천 **전부**로 찍고, *그 자리(`navigate_to` 의 웨이포인트)에 서면 카메라가 대상을 본다* 는 것을 `missing` 이 아니라 **환경 전제**로 옮겼다(`environment-preconditions.md` B 에 둘 — 등록은 관측 가능, 시야는 보증). `pick_place` 의 *"이 자리에는 A형만 있다"* 와 같은 종류다(§15.81 — 능력은 기체×현장의 성질).

    **스킬 셋이 세 층에 올라탄다** — `move_relative` 명령, `navigate_to` 미션, `inspect` 취득. 층마다 일시정지·취소·피드백이 다르고 그 차이가 프로파일의 스킬 단위 선언으로 올라간다(`inspect`: 취소 YES, 일시정지 NO). 취득 계층이 없는 기체에서는 `inspect` 만 죽는다.

    ### 저장소가 함께 움직인 것

    `spot-arm.json` 에 `inspect`(revision 2) · `distance/spot-arm.json` YES + 재측정 근거 · `provenance/spot-arm.json` 두 항목 · `environment-preconditions.md` B 두 행 · 매니페스트 시험에 새 남쪽 타입 열(`WorldLayer`·`AcquisitionLayer`·열거 셋·데이터 클래스 넷) — 짚은 이름 전부 원문에 있다. 거리 시험의 *"닿는다고 적은 것과 선언한 것이 같다"* 가 프로파일과 거리 문서를 함께 움직이게 했다.

    ### 정직하게 적어 둘 것

    - **실물에서 확인한 바 없다**(C-3). 이름의 실재만 대조했다. 특히 `AcquireData` 가 리스 없이 받는지, 광고된 원천 전부를 한 요청에 넣어도 되는지는 벤더 주석의 진술이다.
    - **북쪽이 없다.** `result()`·`failure()` 는 어댑터의 답이지 아직 `WatchTaskResponse` 가 아니다. 미들웨어의 `JobResponse.results` 가 비어 있음을 고정한 시험(§15.93)은 그대로다 — 미믹은 안 채우고 Spot 어댑터는 계약 서버가 없다. 결선은 어댑터 인스턴스(§15.77 이후 열린 것)의 일이다.
    - 결과 참조의 문자열 모양(`channel/data_name#id@action/group`)은 우리가 정한 것이다. 계약의 `partial_result` 가 자유 문자열이라서이며, 구조화하려면 계약이 자리를 내야 한다(§15.76).
    - `WorldObject.name` 으로 대상을 찾는 것과 `knownSiteNames()`(그래프의 웨이포인트 이름)는 다른 이름 공간이다(§15.78) — 확인 질의는 장소만 답하고 대상은 아직 안 답한다.
    - 결함 주입 다섯(모르는 대상 수락 · 취득에 대상 이름 안 붙임 · 결과 참조 누락 · 시간 초과 미분류 · 거절된 취소가 상태 변경) 전부 겨냥한 시험이 잡았다. 앞 판의 *"드는 스킬이 아니면 받지 않는다"* 시험은 `inspect` 를 빼고 `pick_place` 만 남겼다.

98. **어댑터의 북쪽 — 계약 서버 하나가 어댑터 셋을 세우고, ③이 실물 어댑터 위에서 미들웨어까지 처음 이어졌다.**

    §15.77 이 *"어댑터 인스턴스가 없다"* 로 적어 둔 것을 닫는 첫 걸음이다(ADR 39). 어댑터 셋의 같은 메서드 열이 `RobotAdapter` 가 됐고, 기종을 모르는 모듈 `adapter-host` 가 그것 하나를 계약의 gRPC 서비스 셋 뒤에 세운다. 프로파일 → `Capability` 투영은 공유 모듈로 나가 미믹과 호스트가 같은 함수를 쓴다(그 모듈은 같은 날 `capability` 로 넓어졌다 — §15.100).

    ### 소비자가 보는 모양은 미믹과 같다

    | 계약 면 | 호스트 |
    |---|---|
    | `StartTask` | 프로파일 선언 대조(스킬·필수 파라미터·문자열 길이) → `adapter.accept` → 로그에 `ACCEPTED` 와 어댑터가 지금 말하는 상태. 같은 `(task_id, revision)` 은 같은 핸들이고 로그를 안 늘린다. 종착은 래치 |
    | `WatchTask` | 되짚기(`from_update_index`) + 열어 두고 밀기. `partial_result` 는 어댑터의 결과 참조, `fault` 는 실패 종착의 정준 분류, `hold` 는 잔여 물리 상태 |
    | 조작 넷 | 어댑터가 *수단이 없다* 하면 그 조작의 코드(`PAUSE_UNSUPPORTED`·`CANCEL_UNSUPPORTED`). 종착이면 `INVALID_TRANSITION` |
    | `GetCapabilities` | 프로파일의 투영 그대로. `robot_software` 는 아직 안 싣는다 |
    | `GetKnownSiteNames` | `Known`/`Unsupported` 는 계약대로, `Unavailable` 은 `UNAVAILABLE` — 0 개와 못 함을 접지 않는다 |
    | `GetSnapshot`·`ReplayEvents` | 태스크 전이·결함 발생/해소를 `sequence` 축에 쌓고, 버퍼 크기는 프로파일의 것, 벗어나면 `SEQUENCE_EVICTED`. 결함을 못 봤으면 스냅샷이 `UNAVAILABLE` |

    ### 끝에서 끝까지

    `SpotHostEndToEndTest`(Spot 모듈 — 기종을 아는 쪽이 조립한다): 상류 JobOrder(점검 대상 둘) → `InspectAsset` → 계약 → 호스트 → `SpotAdapter` → 가짜 벤더 표면(미션·지도·세계 모델·취득). 이동 둘은 미션으로, 점검 둘은 취득으로 갔고, `DataIdentifier` 가 대상의 이름을 달고 `partial_result` → `ExecutionUnit.result` → **`JobResponse.results`** 에 닿았다. §15.93 이 *비어 있다* 고 고정한 그 칸이 실물 어댑터 경로에서는 찬다 — 미믹 경로의 시험은 그대로 비어 있고, 그것이 맞다(미믹은 결과를 안 채운다).

    ### 정직하게 적어 둘 것

    - ~~**MQTT 발행·레지스트리 적재·`Negotiate` 가 없다.** 계약의 gRPC 면만 세웠다.~~ → 발행과 적재는 §15.99, 협상은 §15.100 에서 닫혔다. ADR 37 의 등록 절차는 열려 있다.
    - ~~도는 태스크의 갱신을 안 한다.~~ → §15.109 에서 닫혔다. **그 진술의 전제도 틀렸다** — 어댑터 셋 중 셋이 그 합성을 든다.
    - 어댑터의 거절 중 *로봇이 지금 못 받는다*(`VENDOR_REJECTED`·`CONTROL_AUTHORITY_LOST`·`ALREADY_RUNNING`)는 계약에 자리가 없어 `INVALID_TRANSITION` 에 분류와 원문을 붙인다 — 거절 코드 하나가 후보다(ADR 9: 발신자가 이제 있다).
    - 진행률은 종착 전 0, 성공 1 이다. 어댑터가 진행률을 안 낸다.
    - 결함 주입 일곱(결과 참조 누락 · 멱등 재수신을 새 요청으로 · 못 본 결함을 없음으로 · 축출 망각 · 취소 거절 코드 뒤바꿈 · 종착 래치 제거 · 실패에 fault 미부착) 전부 겨냥한 시험이 잡았다. 첫 실행에서 하나가 *"모든 겨냥 시험이 잡지는 않음"* 으로 나왔는데 — e2e 시험이 컴파일되지 않은 채였고 러너가 *시험 0건* 을 초록으로 읽었다. 러너의 초록 판정에 *시험이 하나라도 돌았는가* 를 더했다.

99. **호스트의 위쪽 결선 — 레지스트리가 어댑터 호스트의 기체를 본다.**

    §15.98 이 *없다* 고 적은 셋 중 둘(발행·적재)을 닫았다. 방법은 새로 짓는 것이 아니라 **미믹의 것을 빼서 같이 쓰는 것**이었다: `Publisher`·`Publication`·`Topics`·`MqttPublisher` 와 `report/*`(핸드셰이크 보고·태스크 관측·생존 보고·파일 폴백·재적재·`RegistryLink`)가 새 모듈 **`uplink`** 로 나갔다. 투영을 뺀 이유와 같다(ADR 29) — 토픽 형식·발행 열 헤더·적재 경로가 두 벌이면 미믹과 실물이 *같은 모양* 이라는 주장이 발행 쪽에서 깨진다. 기종을 모르므로 게이트 7번 목록에 들어갔다(이제 여덟).

    ### 호스트가 내는 것

    | 스트림 | 언제 | 무엇 |
    |---|---|---|
    | `connection` | 포트가 열린 뒤 `ONLINE`, 정상 종료 때 `OFFLINE` — 둘 다 retain | 끊기면 브로커의 Last Will 이 `CONNECTION_BROKEN` 을 대신 낸다(그것은 `MqttPublisher` 의 일이고 호스트는 모른다) |
    | `event` | 전이·결함이 적힐 때마다 | 재생 버퍼의 것과 같은 객체 — 발행이 막히면 버퍼에 남았다가 다음 발행 때 **순서대로** 밀린다(§10.6 과 같은 규칙, 따로 큐 없음) |
    | `state` | 프로파일의 최대 발행 간격마다, **펌프가 판정**(스케줄러 없음) | 태스크 스냅샷과 활성 결함. 스킬 상태기계가 없어 `skills` 는 비운다 |

    **`sequence` 축은 기체 단위 하나이고 셋이 함께 쓴다**(§5.5 의 발행 열). 그래서 앞 판의 축출 시험이 가정한 *"이벤트가 0 번"* 이 틀렸다 — 0 번은 기동 발행 `ONLINE` 이다. 시험은 번호를 가정하지 않고 호스트가 *실제로 버린 번호* 로 다시 물었다. `GetSnapshot.connection_state` 도 이 값이다(§15.95 의 미믹 정정과 같은 자리).

    ### 레지스트리가 보는 것

    적재는 호스트가 모른다 — 발행자를 `IngestBridge` 로 감싼 쪽(조립하는 쪽)의 일이고, 그것이 미믹 CLI 의 `RegistryLink.wrap` 과 같은 자리다. 생존 보고에 실리는 둘은 **어댑터가 답한다**: 로봇 소프트웨어(`RobotAdapter.robotSoftware()`, 기본 `null` = 못 읽는다 — 셋 중 아직 아무도 안 읽는다)와 사이트 이름 요약(`HostUplink.siteNames` — `Known`=개수 · `Unsupported`=못 함 · `Unavailable`=**널**, 셋을 접지 않는다; 레지스트리가 널을 *이미 받은 답을 지우지 않는다* 로 다루므로 못 물어본 펌프가 앞의 답을 되돌리지 않는다).

    `HostIngestEndToEndTest`(harness, Postgres): 바인딩된 기체를 원장이 *한 번도 보고한 적 없다* 로 보다가 → 호스트가 뜨자 `Live`(소프트웨어·이름 개수 2 가 `robot_liveness` 에 앉음) → 태스크가 돌자 `task` 표에 비종착 → 종착하자 드레인 → 호스트가 닫히자 `OFFLINE` 이 남고 원장이 *연결이 끊긴 기체* 로 판정한다. `LedgerIngestEndToEndTest` 가 미믹으로 본 것과 같은 판정이 실물 어댑터 경로에서 난다 — 갈리는 것은 발행의 출처뿐이다.

    ### 정직하게 적어 둘 것

    - **등록 절차(ADR 37)는 여전히 없다.** 이 시험도 기체 행을 SQL 로 넣는다. 증명한 것은 *등록된* 기체의 관측이 호스트에서 흘러온다는 것까지다.
    - **배치 런처가 없다.** 실 포트·프로파일 파일·어댑터 조립·펌프 스케줄러·MQTT 접속을 한 자리에서 엮는 CLI 는 만들 수 있으나, **띄울 실물 링크가 저장소에 없다** — 어댑터 셋의 남쪽(`SpotLink`·`DigitLink`·`G1Link`)은 인터페이스이고 구현은 시험의 가짜뿐이다(벤더 원문을 저장소에 안 들이는 규칙, §15.55). 아무것도 못 띄우는 런처를 만들지 않았다. 런처는 첫 실물 링크와 함께 온다.
    - ~~못 보낸 구간이 버퍼에서 밀려나도 세션을 새로 안 낸다.~~ → §15.107 에서 닫혔다.
    - `HIBERNATING`·`CONNECTION_BROKEN` 은 호스트가 안 낸다. 남쪽 링크의 단절은 어댑터가 결함·거절로 말한다.
    - ~~핸드셰이크 보고는 `Negotiate` 가 없어 없다.~~ → §15.100 에서 닫혔다.
    - 결함 주입 여섯(드레인 뒤 이중 발행 · 발행 간격 무시 · 기동 발행 누락 · 이벤트 발행 누락 · 종료 OFFLINE 누락 · 사이트 이름 요약의 셋 접기) 중 **첫째는 주입이 아니라 구현에서 나왔다** — 드레인이 이미 민 이벤트를 `send` 가 또 냈고, 새 시험이 잡았다. 미믹의 `EventStream.send` 주석이 *"둘 다 하면 마지막 하나가 두 번 나간다 — 실측으로 걸렸다"* 고 적어 둔 바로 그것을 같은 자리에서 다시 밟았다. 같은 규칙을 두 벌로 갖는 대가가 이렇게 나타나므로, 이 부분도 다음에 `uplink` 로 올릴 후보다(§10.6 의 단절 중 버퍼링을 미믹과 호스트가 각자 든다).

100. **협상 — 판정 함수 하나를 미믹과 호스트가 나눠 쓰고, 그 둘이 같은 답을 낸다는 것을 밖에서 확인한다.**

    §15.98 이 남긴 마지막 계약 면이다. 호스트의 `Negotiate` 는 `UNIMPLEMENTED` 였고, 그동안 **협상하는 소비자는 실물 경로에서 아무것도 못 했다** — `client` 가 이미 그 RPC 를 부르므로 *"엔드포인트만 바꿔 미믹과 실물을 오간다"* 가 그 자리에서 거짓이었다.

    ### 판정은 옮기고, 전송은 두고 왔다

    미믹의 `Negotiator` 가 `profile-projection` 으로 갔고 그 모듈은 **`capability`** 로 이름이 넓어졌다 — 이제 프로파일 → `Capability` 투영과 요구 집합 대 `Capability` 판정 둘을 든다. 같은 질문의 두 면이고, 모듈을 또 만드는 것보다 이름이 정확해지는 편이 낫다(게이트 7번 목록도 안 길어진다).

    옮기면서 **gRPC 의존을 걷어냈다.** 앞 판은 요구 문자열을 못 읽으면 `Status.INVALID_ARGUMENT` 를 던졌는데, 그러면 판정 모듈이 전송을 안다. 이제 `Negotiation` 이 둘이다 — `Judged(rejections)` 와 `Unparseable(problems)`. **거절과 판정 불가는 다른 것이다**: 앞은 *로봇이 요구를 못 맞춘다* 는 답이고 뒤는 *요구를 읽지 못해 답 자체가 없다* 이다. 접으면 오타 난 설정 파일이 "능력 부족" 으로 보인다. 계약으로 옮기는 것(`INVALID_ARGUMENT`)은 서비스 둘이 각자 하되 **문구는 `Unparseable.detail` 하나**다 — 서비스마다 지으면 같은 오류가 미믹과 호스트에서 다른 말로 나온다. 발행 추상이 MQTT 라이브러리를 모르는 것과 같은 규율이다.

    ### 같은 답이라는 것을 무엇으로 아는가

    `HostParityTest`(harness): 같은 프로파일로 미믹과 호스트를 나란히 세우고 같은 요구 집합 여섯을 양쪽에 물어 **`accepted` 와 거절 목록 전체**(코드·사유·참조)를 댄다. 코드만 대면 사유가 갈리는 것을 놓치고, 사유는 운영자가 읽는 것이다. 마지막 경우가 여럿을 한꺼번에 어긴다 — 한쪽만 첫 거절에서 끊는 구현이면 하나씩 어기는 시험은 전부 통과하고 거기서만 갈린다.

    **`harness` 에 있는 이유**: 둘을 동시에 아는 모듈이 그것뿐이다. 미믹도 호스트도 서로를 모르는 것이 §3.2 이고, 그래서 나란히 세워 보는 자리는 계약 스위트의 주인이다.

    ### 원장의 세 번째 관측선

    호스트가 `uplink` 의 `HandshakeReporter` 를 받는다(`AdapterHost(robot, builder, reporter)` — 미믹의 `MimicServer` 와 같은 자리). 그래서 §9.3 이 세는 소비자 요구가 실물 어댑터 경로에서도 원장에 앉는다. `HostIngestEndToEndTest` 의 둘째 시험이 그것을 본다: 협상 전 0 → 수락하면 요구 둘이 `consumer_requirement` 에 빠짐없이 → **거절된 협상은 원장이 아니라 거절 표로**(오르면 그 소비자 때문에 축소가 영영 안 열린다). 성공·실패를 **모두** 보고하는 것이 §5.4 이고, 수락만 보내는 구현은 거절 표가 비는 것으로만 드러난다.

    ### 정직하게 적어 둘 것

    - **판정 기준은 프로파일의 투영이다.** 어댑터가 그 선언을 실제로 다 드는지는 협상이 확인하지 않는다. 갈리면 협상을 통과한 소비자가 `StartTask` 에서 `SKILL_ABSENT` 를 받는다 — 선언과 구현의 거리(C-3)가 그대로 남아 있고, 계약에는 *"어댑터에 물어보라"* 는 질의가 없다.
    - **동치 시험의 픽스처는 하나다**(`profile/fixtures/minimal.json`). 기종 프로파일 셋으로 돌리지 않았다 — 판정 함수가 하나이므로 갈릴 자리가 배선뿐이고, 배선은 픽스처 하나로 드러난다.
    - ★**동치 시험은 판정 자체의 결함을 못 잡는다.** 주입으로 확인했다: 공유 함수를 망가뜨리면 미믹과 호스트가 **함께** 틀리고 두 답은 여전히 같다. 동치가 지키는 것은 *갈리지 않는다* 이지 *맞다* 가 아니며, 맞는지는 양쪽의 자기 시험(`NegotiateTest`·`AdapterHostTest`)이 지킨다. 판정을 하나로 모으면서 얻은 것과 잃은 것이 이 한 줄이다.
    - 결함 주입 여섯(수락 여부를 거절 목록과 따로 계산 · 거절이 하나 생기면 나머지를 안 모음 · 판정 불가를 빈 거절로 · 거절은 보고 안 함 · 보고에 site 누락 · 호스트가 자기 능력 대신 빈 `Capability` 로 판정) 전부 겨냥한 시험이 잡았다. 뒤엣것만 동치 시험이 잡았고 — 그것이 배선의 결함이라서다 — 앞의 다섯은 양쪽의 자기 시험이 잡았다.
    - ★**첫 판의 주입 둘이 등가 변이였다.** *수락 여부를 `SKILL_ABSENT` 유무로 계산* 은 내 시험의 거절이 전부 그 코드를 포함해 답이 안 갈렸고, *신원 거절 뒤 조기 반환* 은 신원이 늘 맞아 그 줄에 닿지 않았다. **못 잡으면 주입부터 의심한다**는 규율이 또 맞았다. 앞엣것은 시험을 하나 더해 닫았다(한계만 어긴 요구 — 거절의 종류와 무관하게 거절이 있으면 수락이 아니다).

101. **기체를 들이는 문 둘 — 등록 절차의 절반(ADR 37).**

    설계 §8.5의 조작 목록에 *기체 등록* 이 처음부터 있었는데 **엔드포인트가 없었다.** 그래서 기체 행은 시험이 `INSERT INTO robot` 으로 직접 넣었고 미믹의 기체 목록은 기동 인자로 손으로 적었다 — ADR 37 이 *"우리 하네스가 스스로 결정 5의 비싼 사분면에 앉아 있다"* 고 적어 둔 그 상태다. 이제 발신자가 둘이니(미믹·어댑터 호스트) 그 문을 쓸 쪽이 실재한다.

    ### 문이 곧 출처다

    | 출처 | 문 | 토큰 | 뜻 |
    |---|---|---|---|
    | **선언** | `POST /operations/robots` | 조작 | *"사람이 판단을 기록한다"* |
    | **발견** | `POST /ingest/robots?site=` | 적재 | *"기체가 자기 관측을 보고한다"* |

    **본문에 `origin` 이 없다**(결정 3). 컨트롤러가 자기 경로를 보고 정하며, 요청이 그것을 실어 보내도 아무 일도 일어나지 않는다. 본문이 정하게 두면 **적재 토큰을 든 현장의 기체가 스스로 *"사람이 선언했다"* 고 적을 수 있고**, 그 승격은 `OperatorToken` 이 막으려던 바로 그것이다(§15.38). 서비스 시험은 메서드를 직접 부르므로 이 구멍을 못 본다 — HTTP 시험이 그 자리를 지킨다.

    **다른 문으로 다시 들이지 않는다.** 선언된 기체를 발견으로, 발견된 기체를 선언으로 올리면 거절한다(조작 문은 409, 적재 문은 목록의 사유). 출처가 두 번째 진실이 되면 *"이 기체가 왜 여기 있는가"* 에 답할 수 없다. 반대 방향을 함께 보는 것이 요점이다 — 한쪽만 보면 문을 하나로 합쳐도 통과한다.

    ### 사람의 말은 기체가 답할 때까지 CLAIMED다

    ADR 35가 사이트 이름에서 세운 규율을 그대로 쓴다. 상태가 넷이다.

    | 상태 | 뜻 |
    |---|---|
    | `CLAIMED` | 사람이 선언했고 **기체가 우리 계약으로 답한 적이 없다** |
    | `DISCOVERED` | 어댑터가 플릿에서 봤고, 아직 우리 계약으로 닿은 적은 없다 |
    | `CONFIRMED` | **그 기체가 생존을 보고했다.** 출처와 무관하다 |
    | `UNREGISTERED` | 행은 있는데 **어느 문도 안 지났다** |

    **확인을 열로 저장하지 않는다** — `robot_liveness` 행이 있으면 확인이다. 열로 두면 두 번째 진실이 되고, 생존 보고가 끊긴 기체를 누가 되돌려 놓을지가 정해져 있지 않다. `UNREGISTERED` 는 기본값을 안 준 결과다: 주면 SQL 로 넣은 픽스처가 *"사람이 선언했다"* 고 거짓말하고 진짜 선언과 구별되지 않는다. 스물다섯 자리가 지금 그 상태이며 **보이는 편이 덮는 것보다 낫다.**

    `HostIngestEndToEndTest` 가 그 전이를 끝에서 끝까지 본다 — 선언 직후 `CLAIMED`, 어댑터 호스트가 뜨자 `CONFIRMED`. **오타 난 `robot_id` 로 선언했다면 영영 `CLAIMED` 이고**, 진단 9번(`GET /diag/robots`)이 그것을 보여 준다. ADR 37 이 대가의 마지막 줄로 적어 둔 *"화면은 초록인데 기체가 안 붙는다"* 가 그 자리에서 닫힌다.

    ### 미결 둘을 닫았다

    - **발견된 기체의 사이트 귀속** — 어댑터가 배포된 사이트다. 플릿은 우리 `site_id` 를 모르지만 어댑터는 안다. `/ingest/handshake` 의 `?site=` 와 같은 자리이고 같은 성질(어댑터의 신고)이다.
    - **접속 설정의 비밀** — 안 받는다. `robot.endpoint` 는 **주소만**이고 §6.3이 신원·접근을 범위 밖에 둔 이상 이 표를 자격증명 저장소로 만들지 않는다.

    발견된 기체의 접속 정보는 **받아서 거절한다**(결정 4 — 플릿이 갖는다). 필드를 아예 없애면 보낸 쪽은 우리가 저장했다고 믿는다. 스키마에도 같은 제약을 둔다(`CHECK`) — 서비스를 한 줄 고쳐 새는 것을 표가 막는다.

    ### 정직하게 적어 둘 것

    - **문은 생겼지만 그 문으로 올리는 어댑터는 없다.** 발견 경로에서 시험이 어댑터 노릇을 한다. 어댑터의 남쪽에 플릿 흉내가 없다는 ADR 37 의 미결이 그대로 남는다.
    - **어댑터 인스턴스를 안 만들었다**(결정 2). 인스턴스는 접속 설정을 갖는 것인데, 직결의 설정은 기체의 것이라 `robot.endpoint` 가 들고 플릿 경유의 설정은 들 어댑터가 없다. 그래서 `robot_binding` 은 여전히 **빌드**를 가리킨다(미결 1 유지).
    - **픽스처 스물다섯 자리는 여전히 SQL 이다.** 닫은 것은 `HostIngestEndToEndTest` 하나다 — ADR 37 이 이름을 집어 적은 자리가 그것이고, 나머지는 등록이 시험의 주제가 아니라 배경이다. 그 행들은 진단에서 `UNREGISTERED` 로 보인다.
    - 결함 주입 여섯(출처를 본문에서 읽음 · 다른 문 거절을 갱신으로 · 확인을 선언 시점에 켬 · 발견의 접속 정보를 저장 · 부분 성공을 전부 실패로 · 감사 로그 누락) 전부 겨냥한 시험이 잡았다.

102. **플릿의 남쪽 — Orbit 을 재고 그 인용을 검사 아래 뒀다(트랙 A 의 A1·A2).**

    어댑터 넷째다. 앞의 셋(Spot·Digit·G1)은 전부 **기체 하나**에 직결하고, 이것만 **기체 여럿을 아는 서버**에 붙는다. ADR 37 의 사분면 넷 중 *플릿 경유* 가 표본이 없어서 발견 경로를 실증할 수 없었고(§15.101 이 *"그 문으로 올리는 어댑터는 없다"* 로 남겼다), 이 모듈이 그 자리다.

    **Orbit 을 고른 이유 셋** — 발견이 게시 스펙 안에 있고(`GET /robots`), REST 라 **벤더 SDK 를 안 들여도 붙고**(Spot proto·G1 DDS 와 다르다 — ADR 31 의 격리가 여기서는 저절로 지켜진다), 이미 재어 둔 표면이 있다(`docs/vendors/orbit.md`).

    ### 출처가 재현됐다

    2026-09-08 에 적어 둔 두 원본을 다시 받아 **sha256 이 같았다**(문서 페이지·클라이언트). 조사 노트에 취득 시각과 해시를 적어 둔 것이 값을 한 첫 사례다 — 같은 판을 다시 재고 있다는 것을 증명 없이 믿지 않아도 됐다.

    ### 추출기가 하나 더 생겼다

    `tools/vendor-manifest/openapi_symbols.py`. 원본의 성질이 앞의 셋과 또 다르다 — **스펙이 파일로 배포되지 않는다.** 문서 페이지에 JS 객체로 인라인돼 있고 키에 따옴표가 없어 JSON 파서가 안 먹는다. **정규식으로 키에 따옴표를 씌우지 않았다**: 그 방법은 문자열 안의 `foo:` 도 키로 바꿔 **없는 이름을 만들어 내고**, 그것이 README 가 적은 두 방향 중 조용한 쪽이다. 작은 파서를 직접 두면 문자열을 문자열로 알기 때문에 그 실수를 할 수 없고, 모르는 문법에서는 **터진다**(시끄러운 쪽). 실제로 수 키(`responses: { 200: … }`)에서 한 번 터졌고 그것이 옳은 실패였다.

    **클라이언트 경로는 AST 로 읽는다.** 첫 판이 정규식이었고 **한 판에서 두 방향으로 틀렸다** — 벤더가 쿼리까지 한 문자열에 담아 쓰는 파견 경로를 놓쳤고(`f'…/dispatch/{nickname}?currentDriverId={id}'`), 동시에 `application/json` 을 경로로 읽었다. **놓친 쪽은 §15.83 이 적어 둔 사실과 안 맞아서 드러났다** — 새 추출기의 첫 시험은 *이미 아는 이름 하나가 나오는가* 다.

    ### 이름 공간이 근거 등급을 나른다

    게시 스펙의 것은 `GET /robots`, 클라이언트에만 있는 것은 `bosdyn-orbit:…` 이다. **벤더의 표기를 그대로 쓰는 원칙(§15.56)의 유일한 예외**이며 이유가 근거 등급이다: 후자는 벤더가 공개 API 로 약속한 적이 없어 언제 사라져도 벤더 잘못이 아니고, 같은 이름 공간에 섞으면 그 차이가 사라진다.

    ### 재면서 나온 것 셋

    1. **게시본이 불완전하다는 판정이 숫자를 얻었다.** 앞 판은 사례 하나(파견)였는데, 클라이언트가 치는 경로 40 개를 전부 세니 **아홉**이 게시 스펙에 없다.
    2. **`Robot` 에 일련번호가 없다** — 발견으로 얻는 신원이 주소와 별명뿐이다. Orbit 이 모르는 것은 아니다(`Run.robotSerial`). **우리 원장의 `robot.serial_number` 가 필수라 발견 경로와 부딪친다**(§15.101 이 만든 문과 이 벤더가 주는 것이 안 맞는다 — A4 에서 다룬다).
    3. **`Run.missionStatus` 가 값 집합 없는 자유 문자열이다.** 같은 스키마의 `Run.runType` 에는 `enum` 이 있으므로 추출기가 못 읽는 것이 아니다. Spot 에 직결하면 `MissionStatus` 열거를 받는데 같은 로봇을 Orbit 뒤에서 보면 문자열 하나이고, 실패는 `RunEvent.error` 정수 하나다 — **층이 하나 늘 때 결과 어휘가 얼마나 얇아지는지**가 이 측정의 값이다(ADR 36 의 실행 층).

    ### 정직하게 적어 둘 것

    - **어댑터가 아직 없다.** 이번 것은 남쪽 포트(`OrbitLink`)와 그 인용의 검사까지다. `RobotAdapter` 구현과 발견의 결선은 A3·A4 다.
    - **HTTP 구현이 없다.** 포트는 인터페이스이고 실제로 Orbit 에 붙는 클라이언트는 안 지었다. 붙여 본 적도 없다.
    - **층 셋이 다 널일 수 있게 뒀다.** 특히 파견은 게시 스펙에 없어 배포본에 있으리라는 보장이 없다 — 있는 척하면 못 붙는 인스턴스에서 어댑터가 아무것도 안 하면서 초록으로 보인다(G1 의 `sport` 가 시뮬레이터에서 널인 것과 같은 자리).
    - 추출기 결함 주입 일곱 전부 잡혔다(중첩 필드 상실 · 열거값 상실 · 클라이언트 POST 경로 누락 · 호스트 접두사 잔류 · 자리표시자만인 경로 · 스펙 검색 실패 · 문자열을 문자열로 안 봄). 셋은 남쪽 시험이 함께 빨개졌고 둘은 매니페스트 diff 로만 보였다 — **아무도 안 짚는 이름**이라 그렇다. ★그 라운드가 **죽은 가지**를 드러냈다: 열거값을 두 곳에서 모으고 있었는데 재귀가 이미 같은 이름을 만들어, 한쪽을 지워도 매니페스트가 그대로였다. 주입이 안 잡히면 주입을 의심하되 **코드가 죽었을 수도 있다.**

103. **발견이 실증됐다 — 플릿에서 본 기체가 사람의 말 없이 원장에 앉는다(트랙 A3·A4).**

    ADR 37 이 미결로 남긴 것이 *"발견 경로를 무엇으로 실증하는가 — 어댑터의 남쪽에 플릿 흉내가 필요한데 공용 도구가 없다"* 였고, §15.101 이 문을 만들면서도 *"그 문으로 올리는 어댑터는 없다"* 고 적었다. `OrbitAdapter`(A3)와 `OrbitDiscovery`(A4)가 그 둘을 잇는다.

    ### 어댑터가 드는 것과 안 드는 것

    계약의 스킬 넷 중 **`navigate_to` 하나만**, 그것도 `PARTIAL` 이다. 계약의 `location` 을 **저작된 미션의 이름**으로 읽어 그 미션을 파견한다 — **ADR 35 를 한 겹 옮긴 것**이며(*사이트 이름은 로봇 안에 산다* 가 여기서는 *플릿 안에 산다*), 주인이 사이트라는 것은 그대로다. 나머지 셋은 프리미티브가 없다. **억지로 옮기지 않았다**: `pick_place` 를 미션 이름으로 흉내내면 상류는 조작을 시켰다고 믿는데 실제로는 누가 저작해 둔 무엇이 돈다. §15.77 이 *"못 옮기면 그 층으로는 계약을 못 만족한다고 적는다"* 고 정한 그 적음이 `profile/distance/orbit.json` 이다 — **프로파일을 안 가리키는 첫 측정**이고(Orbit 은 기종이 아니라 층이다), 스키마가 `profile` 을 선택으로 둔 자리가 여기서 처음 쓰였다.

    ★**`move_relative` 가 처음으로 `NO` 가 됐다.** 실물 셋에서 3/3 이던 유일한 스킬이다. ADR 36 이 *"3/3 인 것은 좋은 계약요소가 아니라 너무 저수준이라는 증거"* 라 적었고, **층이 하나 오르자 그 문장의 반대쪽 증거가 나왔다** — 낮은 층에서만 보편적인 어휘였다.

    ### 종착을 자유 문자열로 판정하지 않는다

    `Run.missionStatus` 는 값 집합 없는 문자열이다. 어떤 문자열이 성공인지 우리가 짐작해야 하고, 짐작한 매핑은 벤더가 문구를 바꾸는 날 조용히 틀린다. 그래서 **스펙이 뜻을 적어 둔 둘만** 쓴다 — `Run.endTime` 이 종착을, `RunEvent.error` 가 성패를 정한다. 원문은 버리지 않고 진단에 싣는다. 사건을 못 읽으면 **성공으로 접지 않고** `NEEDS_INTERVENTION` 이다(§15.41 의 틀리는 방향).

    ### 발견 — 별명이 신원이다

    플릿이 주는 후보가 둘이고 **별명**을 골랐다. `hostname` 은 주소라 바뀌고, 계약의 `robot_id` 는 기체의 신원이라 바뀌면 안 된다(§15.78 의 이름과 주소). 파견도 별명으로 지목하므로 벤더 안에서도 그것이 신원 노릇을 한다. **대가**: 별명은 Orbit 인스턴스 안에서만 유일하다 — 사이트가 여럿이고 별명이 겹치면 `robot_id` 가 부딪친다. 접두사를 붙여 피하지 않는 것은 그러면 파견이 쓰는 이름과 계약이 쓰는 이름이 갈리기 때문이다.

    `OrbitDiscoveryEndToEndTest`(harness, Postgres): 가짜 Orbit → `OrbitDiscovery` → 적재 문 → 원장. **사람이 아무것도 안 적었는데 기체가 있고, 상태가 `CLAIMED` 가 아니라 `DISCOVERED` 다.** 다시 훑어도 안 쌓이고(주소가 바뀌어도 같은 기체다), **사람이 선언한 기체를 발견이 덮지 않는다**(출처는 두 번째 진실이 되지 않는다 — 일련번호도 접속 정보도 그대로다).

    ### 스키마가 한 칸 열렸다 — 플릿은 일련번호를 안 준다

    Orbit 의 `Robot` 에 일련번호가 없다. Orbit 이 모르는 것은 아니고(`Run.robotSerial`) **기체 자원에 안 싣는다.** 우리 `robot.serial_number` 가 필수라 부딪쳤고, 고를 수 있는 것이 셋이었다 — ① 호스트명을 그 자리에 넣는다(**거짓말**: 주소는 바뀌고 일련번호는 안 바뀐다) ② 발견을 거절한다(이 벤더에서는 발견이 아예 안 된다) ③ 없을 수 있게 한다. **③ 을 골랐다**(V13). 선언 경로에는 여전히 요구한다 — 사람이 적는 자리에는 그것을 아는 사람이 있고, 표의 `CHECK` 가 그것을 든다. `robot_liveness` 가 *"안 물어봤다"* 를 널로 가른 것과 같은 규율이다: **모르는 것은 모른다고 적는다.**

    ### 정직하게 적어 둘 것

    - **HTTP 구현이 없다.** `HttpRobotDiscovery` 는 적재 문으로 보내는 쪽이고, **Orbit 에 붙는 클라이언트는 안 지었다.** 남쪽은 여전히 인터페이스이고 시험의 가짜뿐이다 — 진짜 Orbit 에 붙여 본 적이 없다.
    - **주기가 없다.** `sweep()` 은 한 번 훑는다. 스케줄러는 배치(A6)의 일이며, 여기서 스레드를 돌리면 시험이 벽시계에 매인다.
    - **플릿에서 사라진 기체를 아직 안 다룬다.** ADR 37 의 미결 그대로다 — 훑을 때마다 있는 것만 올리고, 없어진 것은 원장에 남는다.
    - **어댑터 인스턴스는 여전히 없다**(A5). 그래서 *"이 발견이 어느 어댑터의 것인가"* 에 원장이 못 답한다.
    - 결함 주입 A3 여섯(자유 문자열로 종착 판정 · 오류 0 을 실패로 · 겹치는 이름을 첫 번째로 · 못 읽은 사건을 성공으로 · 모든 스킬 접수 · 못 보는 결함을 없음으로)과 A4 여섯(신원을 주소에서 · 주소를 일련번호 자리에 · 못 닿은 플릿을 빈 목록으로 · 연계 없음을 조용한 성공으로 · 널 일련번호 거절 · 거절을 답에서 버림) 전부 겨냥한 시험이 잡았다.

104. **남쪽이 와이어를 지난다 — 그리고 게시 스펙대로 짜면 거절당한다(트랙 A5).**

    §15.102~§15.103 동안 `OrbitLink` 의 구현은 **시험의 가짜뿐**이었다. 직렬화도 상태 코드도 인증 헤더도 경로 규약도 검사받은 적이 없었고, 그 사실이 *"진짜 서버에 붙여 본 적 없다"* 는 문장 안에 뭉뚱그려져 있었다. 그 문장을 쪼개면 셋이고 성질이 다르다 — **HTTP 클라이언트가 없다**(안 지은 것) · **가짜조차 와이어를 안 지난다**(안 지은 것) · **배포된 인스턴스에 못 붙는다**(못 짓는 것). 앞의 둘을 닫았다.

    ★**이 단계가 계획에 없었다.** 트랙 A 의 런처 항목이 증명으로 *"가짜 Orbit HTTP"* 를 적어 놓고, 그 클라이언트를 **만드는 단계를 안 넣었다.** 사용자가 *"이제 만들어야지, 계획에 없었어?"* 라고 물어서 드러났다. 원인은 계획을 세울 때 **증명 칸이 전제하는 것이 만드는 칸에 있는지 안 본 것**이고, 그 확인이 계획 문서의 새 규율이다.

    ### 스펙과 클라이언트가 어긋나고, 어긋나면 클라이언트를 따른다

    벤더가 배포한 파이썬 클라이언트가 **서버가 실제로 받는 모양의 증거**다. 게시 스펙은 그것과 네 자리에서 다르다.

    | | 게시 스펙 | 벤더 클라이언트 |
    |---|---|---|
    | 파견 경로 | **없다** | `calendar/mission/dispatch/{nickname}?currentDriverId=…` |
    | `schedule.timeMs` | `type: integer` | **`{low, high, unsigned}`** |
    | 미션 지목 | `task.missionId` | **`task.dispatchTarget.missionId`** |
    | GET 경로 | `/robots` | **`/api/v0/{path}/`** — 언제나 뒤에 슬래시 |

    **뒤의 셋은 스펙만 보고 짰으면 전부 틀렸을 것이고, 아무 시험도 안 빨개졌을 것이다.** 그래서 추출기를 두 번 넓혔다 — 클라이언트가 보내는 **본문 키**(`payload` 딕셔너리)와 경로에 **인라인으로 적힌 응답 봉투**(`GET /runs/#resources`)를 이름으로 들인다. 심볼이 251 → 323 이 됐고, 이제 그 넷을 짚을 수 있다. 앞 판은 `components.schemas` 만 읽어서 **봉투의 `resources` 를 짚을 수 없었고**, 짚을 수 없으면 구현이 그 이름을 하드코딩하게 되며 벤더가 바꿔도 아무것도 안 빨개진다.

    ### 인증이 두 단계다

    토큰만으로는 안 된다. ① 루트에 GET 해서 **`x-csrf-token` 쿠키**를 받아 같은 이름의 헤더로 되돌리고 ② `Authorization: Bearer` 를 얹고 ③ `api_token/authenticate` 로 확인한다. **①을 빼면 토큰이 맞아도 거절당하는데 그 실패가 인증 실패처럼 보인다.** ③ 이 실패하면 **링크를 아예 안 만든다** — 모든 요청이 401 로 답하는 링크를 쥐여 주는 것보다 붙는 시점에 실패하는 편이 낫다.

    ### 안 접는 것 셋

    - **응답 봉투가 둘이다.** `/robots`·`/missions` 는 배열 그대로, `/runs/`·`/run_events/` 는 `{limit, offset, total, resources}`. 모르는 모양이 오면 **빈 목록으로 접지 않고 실패**다 — 접으면 *"플릿에 미션이 없다"* 가 되고 그 위에서 이름 조회가 조용히 실패한다.
    - **오류 코드 0 과 없는 것이 다르다.** 0 은 벤더가 *오류 없음* 으로 쓰는 값이고 없는 것은 안 실린 것이다.
    - **404 는 답이다.** 이 배포본에 그 경로가 없다는 플릿의 말이지 못 닿은 것이 아니다 — 거절이고, 전송 실패만 못 닿음이다.

    ### 정직하게 적어 둘 것

    - **여전히 진짜 인스턴스가 아니다.** 스텁이 답하는 모양은 우리가 벤더 문서에서 읽어 적은 것이다. 배포본이 그와 같은지, 파견 경로가 실재하는지, `missionStatus` 의 실제 값이 무엇인지는 붙어 봐야 안다(C-3).
    - **정렬을 벤더에게 안 맡긴다.** `orderBy` 질의 파라미터가 있지만 값 집합이 스펙에 없어 무엇을 넣어야 하는지 모른다. 별명으로 거르고 창 20 개를 받아 `startTime` 으로 우리가 고른다 — **창 안에 최신 실행이 없으면 못 본다.**
    - **`https` 를 강요하지 않는다.** 벤더 클라이언트는 `https://{hostname}` 을 박아 두고, 우리는 기저 URL 을 받는다. 스텁 서버를 상대로 와이어를 지나기 위해서이며 **벤더와 다른 유일한 자리**다.
    - **TLS 검증·클라이언트 인증서를 안 다룬다.** 벤더 클라이언트에는 `verify`·`cert` 가 있다. 기본 신뢰 저장소를 쓰며, 사설 CA 를 쓰는 배포본에는 그대로 못 붙는다.
    - 결함 주입 아홉(스펙대로 `timeMs` 정수 · 스펙대로 `task.missionId` · GET 뒤 슬래시 제거 · CSRF 헤더 누락 · 봉투 안 벗김 · 모르는 모양을 빈 목록으로 · 거절을 못 닿음으로 · 나쁜 토큰에도 링크 발급 · 오류 0 을 없음으로) 전부 겨냥한 시험이 잡았다.

105. **배포가 모델에 들어왔다 — 그리고 바인딩은 빌드를 계속 가리킨다(트랙 A6).**

    ADR 37 결정 2 를 지었다. 축이 셋이 된다: `adapter`(제품) · `adapter_version`(빌드) · **`adapter_instance`(배포된 것 — 접속 설정을 갖는 것)**.

    **왜 이제야 만드나.** §15.101 이 이 표를 미뤘고 이유를 적어 뒀다 — *"직결의 설정은 기체의 것이라 `robot.endpoint` 가 들고, 플릿 경유의 설정은 들 어댑터가 없다. 아무도 안 쓰는 표를 먼저 만들지 않는다."* 이제 플릿에 붙는 어댑터가 있다(§15.102~§15.104). **소비자가 생겨서 만들었다.**

    ### 미결 1 을 정했다 — 바인딩은 빌드다

    ADR 37 이 *"바인딩이 빌드를 가리키는가 인스턴스를 가리키는가"* 를 미결로 뒀다. **빌드 그대로 둔다.**

    바인딩이 답하는 질문은 *"이 기체가 어느 능력으로 도는가"* 이고 그것은 **빌드의 성질**이다(계약 semver·적합성). 인스턴스는 **배포의 사실**이라 같은 빌드로 프로세스를 다시 띄우기만 해도 바뀐다. 둘을 한 열로 접으면 **재배포마다 바인딩 이력이 한 줄씩 늘고 진단 1번이 그것을 *"능력이 바뀌었다"* 로 보여 준다** — 아무 능력도 안 바뀌었는데.

    대신 *"이 발견이 누구의 것인가"* 는 **기체 쪽**에 적는다(`robot.discovered_by`). 그 질문의 주어가 바인딩이 아니라 기체이기 때문이다. §15.103 이 *"어댑터 인스턴스가 없어서 원장이 못 답한다"* 로 남긴 자리가 그것으로 닫힌다.

    ### 인스턴스가 실재해야 그 발견을 받는다

    발견이 자기 인스턴스를 신고한다(`?instance=`, 자기 신고 — `site`·`X-Actor` 와 같은 성질). **밝히면 실재해야 하고, 모르는 이름이면 목록 전체를 거절한다.** 기체마다 거절하면 사유가 N 번 반복되고 **원인이 기체가 아니라 발신자라는 것이 안 보인다.** 그것이 ADR 37 의 절차(③ 접속 정보를 입력하고 ④ 띄우면 로봇이 흘러 들어온다)를 표가 드는 방법이다.

    **안 밝혀도 받는다.** 옛 배포가 섞여 돌 수 있고, 그때는 널로 남아 진단이 *"어느 어댑터인지 모른다"* 로 보여 준다. 그리고 **안 밝힌 보고가 이미 아는 것을 지우지 않는다**(`COALESCE`) — 생존 보고의 사이트 이름 요약이 같은 규율이다.

    ### 접속 설정은 한 갈래만 여기 있다

    플릿 주소만 든다(결정 4). 직결이면 로봇의 주소가 `robot.endpoint` 에 있고 인스턴스의 그 칸은 비어 있다 — 같은 사실을 두 곳에 두면 하나가 바뀐 날 어느 쪽이 맞는지 정해져 있지 않다. **자격증명은 어느 쪽도 안 받는다**(§6.3).

    ### 정직하게 적어 둘 것

    - **인스턴스가 살아 있는지는 모른다.** 등록은 사람이 적은 것이고(조작 문), 그 프로세스가 지금 떠 있는지 원장은 안 본다. 기체의 생존은 `robot_liveness` 가 보지만 어댑터의 생존을 보는 것은 없다.
    - **플릿에서 사라진 기체를 여전히 안 다룬다**(ADR 37 미결). 훑을 때마다 있는 것만 올리고 없어진 것은 원장에 남는다.
    - **진단 열 번이 배포를 보여 주지만 그것을 고치는 문은 없다.** 잘못 적은 인스턴스를 지우는 경로가 없다 — 재등록으로 덮을 수만 있다.
    - 결함 주입 여섯(모르는 인스턴스 수락 · 안 밝힌 보고가 아는 것을 지움 · 올린 쪽을 아예 안 적음 · 모르는 빌드로 배포 기록 · 재배포를 새 등록으로 · 어댑터가 자기를 안 밝힘) 전부 겨냥한 시험이 잡았다.

106. **배치가 선다 — 런처가 플릿에 붙어 발견하고, 발견한 기체들을 실 포트의 계약 뒤에 세운다(트랙 A7).**

    §15.98 이후 *"배치 런처 없음"* 이 계속 열려 있었다. 조립은 시험 안에만 있었고 — `HostedRobot` 을 손으로 만들어 in-process 전송으로 붙였다 — **운영에서 그것을 세우는 코드가 없었다.** 트랙 A 의 마지막이 그것이다.

    ### 조립 순서가 곧 ADR 37 의 절차다

    1. **플릿에 붙는다.** 못 붙으면 거기서 끝난다 — **붙지도 못하면서 포트를 열면 소비자가 아무것도 못 하는 기체를 본다.**
    2. **발견한다.** 플릿이 아는 목록을 얻어 적재 문으로 올린다. 실패하면 세우지 않는다: **무엇을 세울지가 그 답에서 온다.**
    3. **기체마다 어댑터를 세운다.** 계약은 기체 단위이므로 어댑터도 기체마다 하나이고 링크(플릿 서버)만 공유한다.
    4. **포트를 열고 그 다음에 `ONLINE` 이 나간다**(§10.2).

    ### 호스트가 기체 여럿을 든다

    플릿 뒤에는 기체가 여럿이다. 라우팅이 `robot_id` 로 그중 하나를 지목하며, **미믹이 한 프로세스에 여럿을 호스팅하는 것과 같은 편의이고 같은 단서가 붙는다** — PoC 의 편의이지 아키텍처 주장이 아니다(ADR 21). 기체 하나짜리 배치(직결 어댑터·시험)는 편의 생성자로 그대로 남는다.

    ### 무엇이 처음 지나는가

    `OrbitLauncherTest`: 런처가 조립하고 **실 TCP 포트가 열린다.** 남쪽만 스텁이고 그 사이 전부가 진짜다 — `PicassoClient` → gRPC/netty → `AdapterHost` → `OrbitAdapter` → `OrbitHttpLink` → HTTP. 소비자가 능력을 읽고, 일을 시키고(계약의 `location` 이 저작된 미션 이름으로 읽혀 파견된다), 종착을 `WatchTask` 로 받는다.

    ★**그 시험이 계약 면의 사실 하나를 드러냈다** — 플릿 뒤의 기체는 **`GetSnapshot` 이 `UNAVAILABLE`** 이다. 플릿이 기체 결함을 안 나르므로 어댑터가 *못 본다* 고 답하고, 호스트가 그것을 *없다* 로 접지 않기로 한 결정(§15.99)이 여기서 계약 면에 그대로 나타난다. **소비자는 이 기체의 스냅샷을 영영 못 받는다** — 그것이 층이 하나 는 대가다.

    ### 주입이 규칙 하나를 시험 밖에서 찾아냈다

    ★*"포트를 열고 그 다음에 `ONLINE`"* 이라는 §10.2 의 순서를 **아무 시험도 안 보고 있었다.** 순서를 뒤집는 주입이 안 잡혀서 알았다. 그 순서를 묻는 유일한 방법이 실 포트다 — `Server.getPort()` 가 기동 전에는 던지고 기동 뒤에 답하므로, 발행 시점에 포트를 물어 답이 오는지로 판정한다. in-process 전송에는 포트가 없어 물을 수 없다.

    ### 정직하게 적어 둘 것

    - **프로파일이 하나다.** 발견된 기체 전부에 같은 프로파일을 적용한다. 플릿 뒤의 기종이 섞여 있으면 틀리며, **우리가 그것을 알아낼 방법이 없다** — Orbit 의 `Robot` 자원에 기종을 말하는 칸이 없다(일련번호가 없는 것과 같은 자리).
    - **발견이 한 번이다.** `sweep()` 은 기동 때 한 번 돌고, 뒤에 플릿에 기체가 늘어도 이 프로세스는 모른다. 주기 발견은 안 지었다.
    - **펌프 스레드가 데몬인 것을 시험이 안 본다.** 프로세스 밖에서만 관측되는 성질이라 주입이 잡지 못했고, 그것을 안 잡힌 채로 적어 둔다.
    - **CLI 가 아직 없다.** `OrbitLauncher` 는 라이브러리이고 `main` 함수와 인자 파싱은 없다. 미믹의 `MimicCli` 가 하는 것(인자 검증·기동 거부 메시지·프로세스 유지)이 여기 없다.
    - **여전히 진짜 Orbit 인스턴스가 아니다**(C-3).
    - 결함 주입 여섯 중 다섯을 겨냥한 시험이 잡았다(플릿 거절에도 포트 개방 · 빈 플릿에도 포트 개방 · 첫 기체만 호스팅 · 포트보다 먼저 알림 · 라우팅이 아무 기체나 답함). 여섯째는 위의 데몬 스레드이며 **의도적으로 시험이 없다.**

    **(닫힘, §15.292)** 항목 292가 `OrbitCli`로 런처를 프로세스로 띄우고 `--rediscover-seconds`로 주기 발견을 더해 새 기체를 호스트에 들인다. 남는 기종 문제는 행 §15.106 · 기종이 든다. CLI 행은 닫는다.

107. **못 보낸 구간을 버렸으면 세션을 새로 낸다 — 그리고 같은 규칙을 두 벌로 가진 대가를 또 치렀다(트랙 B1).**

    §15.99 가 남긴 정직 항목이다. 호스트의 세션이 불변이라, 재생 버퍼가 넘쳐 **아직 브로커에 못 보낸** 이벤트를 버려도 소비자가 그 사실을 알 방법이 없었다 — gRPC 로 되짚는 소비자는 `SEQUENCE_EVICTED` 로 알지만 **발행만 듣는 소비자는 조용한 결손을 본다.** 세션을 바꿔 스냅샷부터 다시 세우게 하는 것이 그것을 알리는 유일한 방법이고(§10.6), 그래서 [`HostedRobot.sessionId`] 가 `val` 을 그만두었다.

    **바꾸는 조건이 좁은 것이 요점이다.** 단절만으로, 또는 **다 나간** 구간을 버린 것으로 바꾸면 버퍼링이 무의미해진다 — 소비자가 매번 스냅샷부터 다시 세우게 되고 버퍼가 값을 하지 않는다. 넘칠 때, 그리고 버린 것이 **못 보낸 것일 때만** 바꾼다. 반대쪽 시험(`이미 나간 구간이 밀려나는 것으로는 세션을 안 바꾼다`)이 없으면 *언제나 새 세션* 이 앞 시험을 통과한다.

    ### 미믹에서 같은 자리의 결함 둘이 나왔다

    ★호스트에 붙이려고 미믹의 `EventStream.evict` 를 읽었더니 **거기서 두 가지가 틀려 있었다.** 규칙을 두 벌로 갖는 대가가 §15.99 의 이중 발행에 이어 두 번째로 나타난 것이다.

    - **비우는 것도 버리는 것이다.** 세션을 새로 내면 옛 구간을 다시 밀 뜻이 없으므로 버퍼를 통째로 비우는데, 축출 경계는 **첫 하나**에만 올려 두고 있었다. 그래서 그 뒤의 번호들은 버퍼에도 없고 축출로도 안 적힌 채 사라졌다 — 되짚기가 그 번호에 대고 *잃은 것 없다* 고 답한다. 새 시험이 이것을 **번호를 하나씩 세어** 잡는다: 버퍼에도 없고 브로커에도 안 갔고 축출 경계보다 뒤인 번호가 하나도 없어야 한다.
    - **비운 버퍼에 손을 넣고 있었다.** 축출 거절의 사유 문구가 `버퍼=[처음, 마지막]` 을 적는데, 세션 재발급이 버퍼를 비우고 간 직후에는 *마지막* 이 없다. 그 자리에서 예외가 나므로 소비자는 거절이 아니라 **정체**를 본다 — 스트림이 안 닫히면 결함이 실패가 아니라 멈춤으로 나타난다는 규율(§15.87)의 반대쪽 사례다.

    ### 그리고 미믹은 브로커 없이는 기동조차 못 했다

    ★위 시험을 세우려 발행을 막아 보니 **기동이 실패했다.** §10.6 은 *"발행 실패가 태스크 실행을 막으면 브로커가 죽은 날 로봇이 함께 멈춘다"* 고 적고 이벤트 경로가 그 규율을 지키는데, **기동 발행(`announceOnline`)과 상태 발행만 예외를 그대로 올려보내고 있었다.** 현재값 발행 하나로 모아 삼킨다 — 이벤트와 달리 상태·연결은 *현재값* 이라 못 보내면 버리고 다음 것이 대신한다(큐에 쌓으면 재연결 순간 낡은 현재값이 줄줄이 나가고 마지막 것만 참이다). 호스트는 처음부터 이 규율을 지키고 있었다.

    ### 정직하게 적어 둘 것

    - **세션 번호는 여전히 카운터다**(미믹과 같은 이유 — §5.5 는 ULID 라 한다).
    - **소비자 쪽 대응은 시험하지 않는다.** 세션이 바뀌면 스냅샷부터 다시 세우는 것은 소비자의 일이고, 이 저장소의 소비자는 미들웨어다 — 그 경로(§15.95 의 재세움)는 단절로 트리거되고 **세션 변경으로 트리거되는 재세움은 아직 없다.** 다음 후보다.
    - 결함 주입 일곱을 겨냥한 시험이 전부 잡았다(세션 안 냄 · 항상 냄 · 옛 구간 안 비움 · 경계를 첫 하나에 둠[호스트·미믹 각각] · 빈 버퍼에 손 넣기 · 현재값 발행이 예외를 올려보냄).

108. **진행률 — 셀 수 있는 기종만 낸다. 갈리는 자리는 *국면이냐 개수냐* 가 아니라 *분모가 서느냐* 였다(트랙 B2).**

    호스트는 `progress` 를 `종착이면 1.0, 아니면 0.0` 으로 내고 있었다. 미믹은 상태기계가 있어 분수를 내므로, 소비자가 미믹에서 움직이는 막대를 보고 실물에서 얼어붙는 막대를 본다 — *"엔드포인트만 바꿔 오간다"* 가 이 칸에서 거짓이었다.

    ### 벤더 넷을 다시 세었다

    | 기종 | 근거 | 판정 |
    |---|---|---|
    | **Orbit** | `Run.actionCount` · `Run.pendingActionCount` | **YES** — 벤더가 개수 둘을 준다 |
    | **Digit** | `execution-state-node.status` 가 마디마다 온다 | **YES** — 잎을 센다 |
    | **Spot** | 명령 피드백 `STATUS_IN_PROGRESS`/`STATUS_COMPLETE`, 취득 `GetStatusResponse.Status` 열한 값 | **NO** — 국면이지 분수가 아니다 |
    | **G1** | 심볼 161 전수에 없다(퍼센트는 배터리뿐) | **NO** |

    ★**계획이 두 칸을 틀리게 적어 뒀다.** 증명 칸에 *"Spot 취득의 11 상태, Orbit `RunEvent`"* 라 써 있었는데, 조사해 보니 **Spot 은 못 내고**(11 은 국면이다) **Orbit 은 낼 수 있되 `RunEvent` 가 아니라 `Run` 이 준다.** 계획을 세울 때 근거를 이름까지 확인하지 않고 *"있을 법한 자리"* 를 적은 것이 원인이다.

    ### 갈리는 자리

    Spot 도 마디 상태를 준다 — 미션 층의 `State.NodeStatesAtTick.node_states` 다. 그런데 판정이 Digit 과 갈린다. **`Repeat`·`Switch`·`Condition` 이 있어 분모가 안 서기 때문이다**(§15.80 이 *"Digit 은 조건·분기 0 건"* 이라 적은 그 차이). Digit 의 트리는 평평한 순서열이라 잎의 수가 곧 분모다. 그래서 이 조사의 축은 *벤더가 진척을 말하는가* 가 아니라 **분모가 고정되는가** 이고, 그것을 `progress_support` 로 조사 스키마에 넣었다(필수 4번째 3값).

    ### 규칙은 호스트에 모았다

    성공 종착은 1.0, 값은 `(task_id, revision, attempt)` 안에서 **단조 비감소**(앞의 값이 바닥이다), 범위 밖은 자른다. 바닥이 필요한 이유가 실측으로 나왔다 — 실행이 끝나면 플릿은 셀 것을 잃고 어댑터가 *못 잰다* 로 돌아가는데, 바닥이 없으면 **실패 종착에서 진행률이 0 으로 되감긴다.** 실패는 *거기서 멈춘 것*이지 아무것도 안 한 것이 아니다.

    ### 계약이 *못 잰다* 를 못 싣는다 — 그리고 지금은 안 고친다

    `WatchTaskResponse.progress` 는 맨 `double` 이라 [ProgressObservation.NotObservable] 이 호스트에서 `0.0` 으로 접힌다. 못 재는 기체와 아직 아무것도 안 한 기체가 계약 면에서 같아 보이고, **진행 정체로 개입을 판단하는 소비자는 그 둘을 갈라야 한다.** 그런 소비자가 아직 없으므로 계약을 안 고친다 — §15.79 가 발신자 없는 거절 코드를 안 만든 것과 같은 규율의 반대쪽(여기는 발신자가 둘 있고 소비자가 없다). **닫는 조건**: 진행률로 무언가를 판단하는 소비자가 생기면 `HoldState` 와 같은 모양의 자리를 계약에 만든다.

    ### 정직하게 적어 둘 것

    - **Digit 의 분모는 우리 것이 아닐 수 있다.** [DigitAdapter.hold] 가 적어 둔 것과 같은 한계다 — 로봇이 든 실행 트리가 우리가 보낸 것 전부인지 확인할 표면이 없어, 이전 시퀀스가 남아 있으면 그 마디까지 센다. 그래서 `basis` 에 *실행 트리 잎* 이라 적지 *이 태스크의 행동* 이라 하지 않는다.
    - **Orbit 의 개수는 실행이 생긴 뒤에만 있다.** 파견 직후 첫 폴 전까지는 *못 잰다* 이고, 그것을 0 으로 접지 않는다.
    - Spot·G1 의 `progress()` 는 기본값과 같은 답을 내므로 **거동이 아니라 사유만 다르다.** 그래서 시험이 사유의 벤더 심볼을 확인한다 — 그러지 않으면 재어 본 판정과 안 재고 기본값에 기댄 것이 구별되지 않는다.
    - 결함 주입 열을 겨냥한 시험이 전부 잡았다. ★그중 둘이 **시험 하나가 약하다는 것을 먼저 알려 줬다** — 각본 어댑터가 실패 뒤에도 같은 분수를 계속 답해서, 바닥을 없애는 주입이 통과했다. 실물의 거동(실행이 끝나면 셀 것이 없어진다)을 각본에 넣고서야 그 시험이 일을 했다.

109. **도는 태스크의 갱신 — 조사부터 했더니 계획의 전제가 반대였다(트랙 B3, 계약 0.7.0).**

    계획은 이 단계를 *"먼저 조사다. 셋(넷) 중 그 합성을 드는 기종이 있는지 확인하고, **없으면 짓지 않고** 계약 쪽 제안으로 돌린다"* 로 적어 뒀다. 세어 보니 **셋이 든다.**

    | 기종 | 갱신을 만드는 방법 | 판정 |
    |---|---|---|
    | **Spot**(미션) | `StopMission` → `LoadMission` → `PlayMission` | **YES** — 벤더가 셋을 이름으로 다 준다 |
    | **Spot**(명령) | `se2Velocity` 새 값 하나 | **YES** — 아래 |
    | **Spot**(취득) | `CancelAcquisition` 이 **거절할 수 있다** | **NO** |
    | **Digit** | `remove-action` → `add-sequential-actions` | **YES** |
    | **G1** | `SetVelocity` 새 값 하나 | **YES** |
    | **Orbit** | 도는 미션을 멈추는 문이 없다 | **NO** — 넷 중 하나 |

    ### 갈리는 자리는 *멈춤이 보장되는가* 다

    §4.4 는 갱신을 `Halt` → `Reset` → `Start` 의 **합성**으로 적었다. 그 첫 칸이 없으면(Orbit) 갱신이 성립하지 않고, 첫 칸이 **거절될 수 있으면**(Spot 취득의 `STATUS_FAILED_TO_CANCEL`) 갱신이 둘을 돌린다. 그래서 조사 스키마의 `update_support` 가 묻는 것은 *멈출 수 있는가* 이지 *다시 시킬 수 있는가* 가 아니다.

    ★**그리고 지시값 층에서는 셋이 한 칸으로 접힌다.** `se2Velocity`·`SetVelocity` 는 지시값이라 새 값이 곧 지금의 지시다 — 멈췄다 다시 시킬 필요가 없고, 거절되면 앞 지시가 그대로 산다. §4.4 의 세 칸은 **미션·액션 층의 모양**이었고 계약이 그것을 일반 규칙처럼 적어 둔 것이다(ADR 36 의 층 차이가 또 나왔다). G1 에서는 이 사실이 이미 코드에 있었다 — 그 어댑터의 **취소가 `SetVelocity(0,0,0)`** 이다. 갈아서 멈출 수 있으면 갈아서 다르게 갈 수도 있다.

    ### 계약이 자란다 — `UPDATE_UNSUPPORTED`(0.7.0)

    계획은 *아무도 안 들면* 거절 코드를 제안하려 했는데, 실제로는 **드는 기체가 있어서** 코드가 필요해졌다. 못 드는 기체(Orbit)의 거절을 `INVALID_TRANSITION` 에 접으면 소비자가 *지금 상태가 안 받는다*(정리 중·종착)와 *이 로봇은 원래 못 한다* 를 구별하지 못한다 — 앞은 기다렸다 다시 보내면 되고 뒤는 취소한 뒤 새 `task_id` 로 가야 한다. `CANCEL_UNSUPPORTED`·`PAUSE_UNSUPPORTED` 가 따로 있는 것과 같은 이유이고, ADR 9 를 통과한다: 발신자는 Orbit 어댑터이고 소비자는 미들웨어다(§15.92 가 갱신 거절을 *이미 종착* 으로 읽고 지연 이벤트를 기다리는데, 원인이 *못 하는 기체* 이면 그 이벤트는 영원히 안 온다).

    ### 호스트가 규칙을 든다

    `ACCEPTED`·`RUNNING` 만 어댑터로 보낸다. **실물에서 `ACCEPTED` 는 이미 명령이 나간 상태다** — 어댑터의 `accept` 가 곧 벤더 호출이라, 미믹의 *아직 시작 전* 과 다르다. 갱신은 같은 핸들의 `revision` 을 올리고 **로그에 한 줄을 더 적으며**(상태는 그대로), 진행률은 새 구간에서 0 부터 다시 센다 — 단조 비감소의 바닥이 `(task_id, revision, attempt)` 안에서만 유효하다는 §4.4 를 B2 의 바닥 규칙과 맞물리게 고쳤다. 스킬 교체는 막는다(그것은 다른 태스크다).

    **미믹과 답이 같은지 밖에서 확인한다** — `HostParityTest` 에 협상 밖의 첫 동치 시험이 생겼다(갱신·낮은 개정판·스킬 교체 셋의 답).

    ### 합성은 원자가 아니다

    ★멈춤은 됐는데 적재나 재시작이 거절되면 **로봇은 멈춰 있고 아무것도 안 돈다.** 조용히 `RUNNING` 으로 두면 상류가 가고 있다고 믿으므로 `NEEDS_INTERVENTION` + `X_*_UPDATE_HALF_APPLIED` 로 사람을 부른다(취소가 반만 될 때 `CANCELLED_RECOVERY_FAILED` 를 적는 것과 같은 규율). 순서도 규칙이다 — Spot 은 **이름을 먼저 옮기고** 그 다음에 멈춘다. 반대로 하면 모르는 이름 하나에 로봇이 멈춰 선다. Digit 은 지운 뒤 `action-status` 가 아직 `running` 이면 새 액션을 **안 얹는다**(매뉴얼이 예고한 컨테이너 거동 — 얹으면 둘이 겹쳐 돈다).

    ### 정직하게 적어 둘 것

    - **`PAUSED`·`RETRIABLE`·`NEEDS_INTERVENTION` 의 갱신은 아직 안 든다.** §4.4 는 *파라미터만 갈아 두고 재개·재시도 때 적용하라* 고 하는데, 그러려면 호스트가 대기 파라미터를 들고 어댑터의 `resume`·`retry` 에 실어야 하고 그 자리가 포트에 없다. **로봇이 못 하는 것이 아니므로 `UPDATE_UNSUPPORTED` 로 답하지 않는다** — `INVALID_TRANSITION` 에 그 사정을 적는다. **닫는 조건**: 포트의 `resume`·`retry` 가 파라미터를 받고, 그것을 쓰는 소비자가 생기면.
    - **`Applied.Refused` 에는 정준 분류 칸이 없다.** 조작의 거절은 계약에서 코드와 사정 문자열로만 나가므로 G1 이 로봇에게 받은 에러 코드를 사정에 붙인다. 접수 쪽 거절(`Acceptance.Refused`)과 비대칭이고, 그것을 고칠 소비자가 아직 없다.
    - **Spot 명령 계층의 갱신은 실물에서 확인한 바 없다** — 지시값이 대체된다는 것은 API 의 모양에서 읽은 것이다.
    - 결함 주입 열일곱을 겨냥한 시험이 전부 잡았다. ★그중 하나가 **시험 하나가 약하다는 것을 먼저 알려 줬다** — G1 의 시계 시험이 갱신에서 *지속시간을 늘려* 있었고, 그러면 시각을 안 다시 세도 늘어난 길이만으로 통과한다. 길이를 같게 두고서야 그 시험이 일을 했고, 같은 구멍이 Spot 명령 계층에도 있어 함께 메웠다.

110. **마감 — 세어서 적은 것을 다시 세고, 열린 것을 한 장으로 모았다(트랙 C).**

    ### C1 — 시나리오 검증 열을 다시 셌다

    §15.84 가 *"6 증명 · 3 의도적 밖 · 1 반"* 이라 적은 뒤로 여덟 단계가 지났다. 다시 대 보니 **아홉이 증명된다** — 계약 층에서 일곱, **층 ③ 에서 둘**. 올라간 셋의 이유가 다르다: 6 번(버전 변경 중 지연 이벤트)은 §15.92 가 규칙을 실행 층에 지어 반에서 온전이 됐고, 7·8 번(설비 신호)은 **계약 밖인 채로** 증명됐다.

    ★그 구분이 이 재대조의 요점이다 — **계약이 안 나르는 것과 아무도 안 하는 것은 다르다.** 앞의 표는 둘을 같은 칸(*계약 밖 — 정상*)에 적고 있었고, 그래서 ADR 38 이 한 일이 표에서 안 보였다.

    후보 다섯 중 **넷째가 닫혔다** — *이벤트 쪽 옛 revision 규칙* 의 답은 계약이 아니라 실행 층이었다(§15.92). 셋째(든 채의 갱신)는 갱신 자체가 지어져 **질문이 좁아졌다**: 이제 발신자가 갈린다는 것이 보인다 — 지시값 층은 멈춤이 따로 없어 든 것을 놓을 자리도 없고, 액션 층은 지우기가 곧 멈춤이다.

    ### C2 — 낡은 진술은 사람이 훑지 않는다

    README 가 검사를 아홉이라 적고(여덟이다) 어댑터를 셋이라 적고(넷이다) 있었다. 설계 문서는 게이트 7번이 **넷**을 본다고 적었는데 코드는 **여덟**을 본다. 고치는 것은 쉽고 **다시 낡는 것이 문제**라, `DocumentClaimsTest` 가 코드에서 세어 문서와 댄다 — 모듈 나무 · 검사 수 · 어댑터 수 · 거리 문서 수 · 계약 개정판 · 진단 수 · 게이트 7번 목록.

    **게이트 검사로 만들지 않았다.** 게이트는 PR 을 막는 것이고, 산문의 숫자 하나로 남의 PR 을 막으면 그 검사는 곧 꺼진다. 그리고 `gate` 는 `contracts` 에도 `registry` 에도 의존하지 않으므로(§3.2) **소스를 문자열로 읽어** 센다 — 의존을 만들어 시험을 편하게 하면 그것이 §3.2 의 첫 예외가 된다.

    같은 훑기에서 **거짓이 된 진술 둘**을 잡았다: §15.21 이 *"실물 어댑터는 ULID 를 쓴다"* 고 적었는데 어댑터 호스트도 카운터를 쓴다(계획이 사실로 적혀 있었다), §15.17 은 CI 가 이미 두 디렉터리를 다 보게 되어 닫혔다. ADR 39 의 *"어댑터 셋 중 아무도 갱신을 안 든다"* 와 *"어댑터가 진행률을 안 낸다"* 도 이번 트랙이 뒤집은 것이라 후속 표시를 붙였다.

    ### C3 — 한계 대장

    §15 는 번호가 106 까지 갔고 앞은 한계 목록, 뒤는 일지다. *"지금 무엇이 열려 있나"* 를 물으면 처음부터 읽어야 했다. [`docs/limits.md`](../../limits.md) 가 그 답을 한 장으로 준다 — **의도적 밖** / **안에서 닫는다** / **밖에서 닫는다** 로 갈리고, 열린 것마다 **무엇이 있어야 닫히나** 가 한 줄이다.

    갈래를 셋으로 나눈 것이 요점이다. 앞의 §15 는 *"게이트 7번이 문자열 검사다"*(닫지 않기로 한 것)와 *"디스크립터 재생성이 빌드 밖이다"*(지으면 닫히는 것)를 같은 목록에 나란히 적고 있었고, 그러면 읽는 사람이 **무엇을 해야 하는지** 를 못 고른다.

    ★대장의 첫 줄이 **`buf` 디스크립터**인 것은 우연이 아니다 — 이 저장소가 실측으로 두 번 물린 자리이고(*낡은 디스크립터는 낡은 코드와 사이좋게 초록이다*), 닫는 조건과 그 대가(`:gate:test` 에 Docker 의존이 번진다)가 둘 다 분명하다. 그것은 결정이 필요한 일이라 이 트랙에서 짓지 않았다.

    ### 정직하게 적어 둘 것

    - **대장이 §15 를 대신하지 않는다.** 일지가 정본이고 대장은 색인이다 — 두 곳에 같은 상태를 적으면 곧 어긋난다. 그 규칙을 대장 마지막 절에 적어 뒀다.
    - **대장에 없는 한계가 있을 수 있다.** §15 뒤쪽 일지의 *정직하게 적어 둘 것* 불릿을 전수로 옮기지는 않았다 — 옮긴 것은 지금도 참인 것으로 확인한 것들이다.
    - `DocumentClaimsTest` 는 **셀 수 있는 것만** 본다. *"어느 어댑터도 실물에 붙여 보지 못했다"* 같은 진술은 여전히 사람이 지킨다.

111. **디스크립터가 하나가 됐다 — 그리고 게이트 시험이 proto 를 다시 본다(대장 1순위).**

    `docs/limits.md` 의 첫 줄이었다. 게이트 시험이 읽는 `contracts/build/descriptor.binpb` 는 `tools/buf build` 를 **손으로** 돌려야 갱신됐고, 안 돌리면 **낡은 디스크립터가 낡은 코드와 사이좋게 초록이었다**(§15.22 의 실측: `skill_catalog.proto` 에 옵션을 더하고 시험을 돌렸는데 옛 판정이 나왔다).

    ### 대가를 치를 줄 알았는데 안 쳐도 됐다

    대장은 닫는 법을 *"Gradle 태스크가 만들고 `:gate:test` 가 거기 의존하면"* 이라 적고 대가를 *"Docker 의존이 `:gate:test` 로 번진다"* 라 적어 뒀다. **그 대가가 필요 없었다.** 디스크립터가 **둘**이라는 것이 문제의 절반이었기 때문이다 — `protoc`(Gradle)이 만드는 `picasso.desc` 는 이미 있었고, 런타임 신원(`contract_digest`)과 `registry` 의 교차검증이 그것을 읽고 있었다. 게이트만 buf 의 것을 읽었다.

    그래서 **protoc 의 출력을 `contracts/build/descriptor.binpb` 로 내보내고 buf 로 만들던 태스크를 지웠다.** 이제 디스크립터가 하나이고, 셋(런타임 신원 · `registry` · 게이트)이 같은 바이트를 본다. `:gate:test` 는 `:contracts:generateProto` 에 매달린다 — **Docker 는 늘지 않았다.**

    ★**§3.2 를 어긴 것이 아니다.** 태스크 의존은 `project(":contracts")` 의존이 아니다 — `gate` 의 클래스패스에는 아무것도 안 들어오고 게이트 5번이 그 자리를 계속 지킨다. §3.2 가 막는 것은 *게이트가 계약의 생성 코드를 쓰는 것* 이고, 여기서 생긴 것은 *게이트 시험이 최신 바이트를 본다* 는 순서 하나다.

    ### 닫혔다는 것을 어떻게 아나

    §15.22 가 적어 둔 **그 실측을 그대로 다시 했다** — `skill_catalog.proto` 에 아무 프로파일도 선언하지 않는 스킬을 하나 더하고 `:gate:test` 만 돌렸다. 예전에는 초록이었다. 지금은 **셋이 빨개진다**: `ContractIndexTest`(카탈로그 스킬 넷 → 다섯) · `VocabularyDistanceTest` 둘(계약의 모든 스킬을 실물마다 재는데 안 잰 것이 생겼다, 대조 횟수 20 → 16). 손으로 돌린 명령은 없다.

    ### 정직하게 적어 둘 것

    - **`buf` 가 필요 없어진 것이 아니다.** 게이트 1·2 번(lint·breaking)과 음성 하네스가 여전히 쓴다. 사라진 것은 *디스크립터를 buf 로 만드는 자리* 하나다.
    - **`contract_digest` 의 계산 방법은 그대로다**(디스크립터 셋의 SHA-256, `buf` 모듈 다이제스트가 아님, §15.22 의 앞부분). 바이트가 바뀌지 않았으므로 다이제스트도 안 바뀌었다 — 소비자에게 경보가 안 간다.
    - **protoc 과 buf 의 미세한 차이(§15.20)가 게이트 경로에서 사라졌다.** 두 도구가 만든 디스크립터를 서로 다른 곳이 읽던 자리가 없어졌기 때문이고, 그 차이가 실제로 문제였던 적은 없다.

112. **진행률에 근거가 붙었다 — 그리고 그것을 읽는 소비자를 함께 지었다(계약 0.8.0).**

    §15.108 이 열어 둔 자리다. 계약의 `progress` 는 맨 `double` 이라 **못 재는 기체와 아직 아무것도 안 한 기체가 `0.0` 으로 같아 보였다.** 그때 안 고친 이유는 ADR 9 였다 — 그 구분을 읽고 **다르게 행동하는** 소비자가 없었다.

    ### 소비자를 먼저 정했다

    §15.108 이 닫는 조건으로 적어 둔 것이 *"진행 정체로 개입을 판단하는 소비자"* 였고, 그것을 지었다. 미들웨어가 도는 단위의 진행률을 보다가 능력 단위 설정(`stallWindow`, 기본 5 분)만큼 안 움직이면 **자취에 적고 상류에 알린다.**

    **정체는 실패가 아니다.** 상태를 안 바꾸고 자동 조치도 없다 — 느린 것과 멈춘 것을 이 층이 못 가르기 때문이고, 그래서 하는 일은 *보이게* 하는 것까지다.

    ★**그리고 못 재는 기체에는 그 판정을 아예 안 한다.** 이것이 계약의 새 필드가 있는 이유 전부다 — `0.0` 을 정체로 읽으면 진행률을 안 내는 기종(Spot·G1)이 **언제나 멈춰 있는 것으로** 보이고, 그러면 운영자가 이 경보를 곧 무시한다. 대신 *왜 판정이 없는지* 를 자취에 **한 번** 적는다.

    ### 값을 두 곳에 두지 않았다

    분수는 `progress`(필드 5) 하나이고, 새 `progress_basis`(필드 9)는 그 분수를 **자격 짓는다** — `HoldState.kind` 가 `object_ref` 를 자격 짓는 것과 같은 모양이다. 같은 값을 두 필드로 내면 어긋나는 날 무엇이 참인지 아무도 모른다.

    `ProgressKind` 는 셋이다. `UNSPECIFIED`(옛 발신자) · `NOT_OBSERVABLE`(근거가 없다, `reason`) · `MEASURED`(유효하다, `basis` 가 *무엇을 세었는지*). **옛 발신자를 측정으로 읽지 않는 것**이 요점이고 그것이 막는 방향이다(§15.41) — 그러지 않으면 0.8.0 이전 발신자 전부가 정체 경보를 받는다.

    ### `basis` 가 문자열인 이유

    *"행동 3/7"* · *"실행 트리 잎 1/2"* · *"경과 시간 비율"*. **기종마다 세는 단위가 다르다는 사실을 숨기지 않으려는 것**이고, 그것을 열거로 만들면 계약이 벤더의 세는 법을 알게 된다(ADR 36 결정 5). 사람이 읽는다.

    ### 정직하게 적어 둘 것

    - **미믹에는 *못 잰다* 가 없다.** 프로파일이 선언한 소요시간 대비 경과 비율이 언제나 근거이므로 언제나 `MEASURED` 다. 그 값은 실물에서만 나오고, 그래서 미들웨어 시험은 **계약의 자격 필드를 갈아 끼우는 포트 더블**(`ProgressPort`)로 두 갈래를 만든다 — 값을 지어내는 것이 아니라 실물이 낼 모양을 미믹 위에 씌우는 것이다.
    - **정체가 `operatorRequired` 를 켜지 않는다.** 통보의 미완 단위에 사정으로 실릴 뿐이다. 켜면 *사람이 반드시 판단해야 하는 것* 과 *봐 두면 좋은 것* 이 같은 칸에 들어간다.
    - **`stallWindow` 의 기본값 5 분에 근거가 없다.** 현장별 설정 항목이며 여기는 자리만 만들었다(시간창 δ 와 같은 성격).
    - 결함 주입 여섯 중 **다섯을 겨냥한 시험이 잡았다.** 여섯째(*호스트가 모든 진행률을 측정으로 낸다*)는 **주입할 자리가 없었다** — `when` 이 `ProgressObservation` 둘을 다 덮으므로 한쪽을 지우면 컴파일이 안 된다. 구조로 막은 것은 주입이 안 되고, 그것 자체가 신호다(§15.101 과 같은 자리).

113. **멈춘 태스크도 파라미터를 받는다 — §4.4 의 갱신 표가 다 찼다.**

    §15.109 가 남긴 마지막 칸이다. `PAUSED`·`RETRIABLE`·`NEEDS_INTERVENTION` 의 갱신을 호스트가 거절하고 있었고, 이유는 로봇이 못 해서가 아니라 **우리 포트에 자리가 없어서**였다 — 어댑터의 `resume`·`retry` 가 파라미터를 안 받으니 *갈아 두고 나중에 적용* 을 할 수가 없었다.

    ### 포트를 한 칸 넓혔다

    `resume(parameters)` · `retry(parameters)`. 호스트가 태스크의 **지금 파라미터**를 들고 그것을 실어 준다. 갱신이 없었으면 접수 때의 것이 그대로 가므로 **호출자가 두 경우를 가르지 않는다** — 가르게 두면 그 분기가 곧 틀린다.

    어댑터 넷 중 아무도 `resume`·`retry` 를 안 든다(전부 기본 거절). 그래서 **이 확장은 지금 아무 벤더 코드도 안 바꿨다** — 넓힌 것은 계약과 호스트 사이의 자리이고, 그 자리가 없어서 계약의 규칙을 못 지키고 있었다는 것이 요점이다.

    ### 상태에 따라 보내거나 갈아만 둔다

    | 상태 | 갱신을 받으면 |
    |---|---|
    | `ACCEPTED`·`RUNNING` | 어댑터의 갱신 합성으로 **보낸다**(§15.109) |
    | `PAUSED`·`RETRIABLE`·`NEEDS_INTERVENTION` | **갈아만 둔다.** 로봇에 아무것도 안 간다 — 그래서 **갱신 합성을 못 드는 기체도 이 경로는 된다** |
    | `CANCELLING`·종착 | 자리가 없다. `INVALID_TRANSITION` |

    ★**`NEEDS_INTERVENTION` 이 여기 있는 것이 요점이다.** §4.4 가 *"개입한 사람이 파라미터를 고쳐 넣는 경로가 이것이다 — 막으면 이 상태의 존재 이유와 어긋난다"* 고 적어 뒀는데, 호스트가 정확히 그것을 막고 있었다. 사람이 현장을 고치고 파라미터를 고쳐 다시 시키는 것이 그 상태의 쓸모 전부다.

    갈아만 두는 경로도 **로그 한 줄을 적는다**(같은 상태, 오른 `revision`). 미믹이 그 자리에서 자기 자신으로의 전이를 알리는 것과 같은 이유다 — 안 알리면 이벤트를 접는 소비자가 옛 `revision` 을 영원히 든다.

    ### 정직하게 적어 둘 것

    - **재개·재시도를 드는 어댑터가 아직 없다.** 이 경로는 계약 면과 호스트까지 검사받고, 벤더 쪽은 그 프리미티브를 드는 기종이 나올 때 열린다. 넓힌 포트가 **되는 척하지는 않는다** — 기본값은 그대로 *없다* 다.
    - **파라미터의 주인이 호스트다.** 어댑터는 받은 것을 쓸 뿐 저장하지 않는다. 두 곳에 두면 갱신 뒤 재개에서 어느 쪽이 참인지 알 수 없다.
    - 결함 주입 여섯 전부 겨냥한 시험이 잡았다.

114. **읽는 사람의 문 — 검증 근거 표와 아키텍처, 그리고 모듈의 문 다섯.**

    ### 검증 근거 표 (`docs/verification.md`)

    미들웨어 중앙 설계 §5 가 *"실제 API 에 연결한 구간과 Mock 구간을 표로 구분한다"* 고 적어 두고 **안 만든 것**이다. 지금 만든 이유는 기능이 아니라 **시간** 때문이다 — 어느 구간이 실물이고 어느 구간이 대역이었는지를 아는 것은 이 저장소를 지은 쪽뿐이고, 그 사실은 §15 의 백 몇 개 항목에 흩어져 있다. 나중에 붙이는 값이 같은 다른 항목들과 정반대로 **이것만 지금 아니면 비싸진다.**

    등급 다섯(실물 · 실 와이어·세운 상대 · 실 코드·전송 없음 · 대역 · 없음)으로 열세 구간을 갈랐다. ★그리고 **계약을 누가 지었는가**를 열로 따로 뒀다 — 등급이 높아도 **상대 계약이 우리 것이면 그 구간의 증명은 우리 가정 안에서만 참**이기 때문이다.

    표가 드러낸 것: **아래로 갈수록 진짜다.** 레지스트리(실 HTTP + 실 PostgreSQL)·게이트(실 buf)·발행(실 mosquitto)은 실물에 대고 돌고, 로봇 계약은 실 코드이되 전송이 대부분 in-process 이며, 벤더는 넷 중 **하나만** 와이어를 지난다. 그리고 위쪽 둘 — **설비와 플릿은 계약을 우리가 지었다.** 가장 조심해서 읽어야 할 줄이 그 둘이다.

    ### 아키텍처와 모듈의 문

    `docs/architecture.md` 는 모듈이 무엇인지가 아니라 **경계가 왜 거기인지**를 적는다 — 층 넷, 데이터의 두 방향(질의는 당기고 발행은 민다), 상태기계 둘이 서로를 대신하지 않는 것, 의존 규칙, 그리고 **여기 없는 것**.

    모듈 문 다섯(`picasso`·`registry`·`mimic`·`adapter-host`·`gate`)은 **KDoc 을 옮기지 않는다.** 옮기면 두 곳에 같은 상태가 생기고 곧 어긋난다. 대신 KDoc 이 못 답하는 것만 적는다 — *이 모듈의 규칙 하나* · *경계* · *여기 없는 것* · *어느 시험이 무엇을 증명하나*. 나머지 모듈은 작아서 KDoc 으로 족하고, 그 판단도 적어 둔다.

    ### ★같은 구멍에 네 번째로 물렸다

    문서 링크가 실재하는지 보는 시험을 더하고 **깨진 링크를 넣어 봤더니 초록이었다.** `:gate:test` 의 `inputs` 에 문서가 없어 **UP-TO-DATE 로 넘어갔기 때문**이다 — `gate/build.gradle.kts` 의 주석이 *"이 저장소가 같은 방식으로 두 번 물렸다"* 고 적어 둔 그것이고, 조사 문서에서 세 번째였고, 이번이 네 번째다.

    **세는 시험을 만들어 놓고 그 시험이 안 도는 상태였다.** §15.110 에서 만든 `DocumentClaimsTest` 는 다른 입력(계약 개정판·모듈 목록)이 바뀔 때만 함께 돌고 있었다 — 문서만 고친 변경에서는 아무 일도 안 일어났다. 입력에 문서를 넣고 같은 주입을 다시 하니 빨개진다.

    이 구멍의 모양이 매번 같다: **검사를 만들면 그 검사가 도는지를 따로 확인해야 한다.** 결함 주입이 그 확인이고, 주입이 안 잡히면 먼저 의심할 것은 시험이 아니라 **시험이 돌았는가** 다.

115. **문지기 — 시험이 선언 안 한 파일을 읽으면 그 자리에서 멈춘다. 그리고 만들자마자 둘을 찾았다.**

    §15.114 가 *"검사를 만들면 그 검사가 도는지를 따로 확인해야 한다"* 로 끝났다. 그것을 **습관이 아니라 코드**로 옮긴다.

    ### 왜 습관으로는 안 되나

    이 구멍의 모양이 네 번 같았다 — 시험이 저장소 파일을 읽는데 그 경로가 `:gate:test` 의 `inputs` 에 없어서, 그 파일만 고친 변경에서 **시험이 UP-TO-DATE 로 안 돌고 빌드가 초록**이었다. 픽스처에서 한 번, 검사 7번의 모듈 목록에서 한 번, 조사 문서에서 한 번, 문서 링크에서 한 번. **매번 고친 방법이 *`build.gradle.kts` 에 줄을 하나 더한다* 였고, 매번 다음 사람이 그것을 잊었다.**

    훅으로 막는 것은 더 약하다. 훅이 볼 수 있는 것은 `UP-TO-DATE` 줄인데 **그 줄은 대부분 정상**이고, 어느 것이 문제인지는 *그 태스크가 선언 안 한 파일을 읽는가* 에 달렸다 — 훅은 그것을 모른다. 오탐이 섞이면 곧 무시되고, 그것은 이 저장소가 경보에 대해 반복해 적은 실패 방식이다.

    ### 기억할 것을 없앴다

    `gate/build.gradle.kts` 의 **`repoInputs` 하나**가 두 곳에 쓰인다 — Gradle 의 입력 선언과, 시험 쪽으로 넘어가는 목록(시스템 속성, **저장소 상대 경로**로만). 시험은 `Repo.read`/`Repo.path`/`Repo.list` 로만 저장소를 읽고, **그 문이 읽는 순간 선언 안인지 확인한다.** 선언 안 한 파일을 읽으면 그 자리에서 던진다.

    새 시험이 새 파일을 읽어도 잡히고, 누가 선언 줄을 지워도 잡힌다. `upToDateWhen { false }`(항상 다시 돌리기)와 비교해 **48 초를 매 빌드에 물지 않는다** — 그 값이 음성 하네스가 Docker 로 buf 를 도는 값이다.

    **게이트 자신이 읽는 것은 이 문을 안 지난다.** `Check05`·`Check07`·음성 하네스는 저장소 트리를 넘겨받아 훑는 것이 일이고(그것이 검사의 내용이다), 그 경로를 막으면 검사를 못 돌린다. 이 문은 **시험이 단언하려고 읽는 파일**의 것이다.

    ### ★만들자마자 다섯째를 찾았다

    문을 붙이고 처음 돌리자 **선언 밖에서 읽던 파일이 둘** 나왔다 — `settings.gradle.kts`(모듈 수를 세는 자리)와 `registry/…/DiagController.kt`(진단 수를 세는 자리). 둘 다 **§15.110 에서 내가 만든 `DocumentClaimsTest` 가 읽던 것**이고, 그래서 **모듈을 더하거나 진단을 더해도 그 시험이 안 도는 상태**였다. 두 커밋 전에 만든 *세는 시험* 이 절반만 배선돼 있었다.

    같은 구멍의 다섯째이고, **이번에는 사람이 아니라 문지기가 찾았다.** 그것이 이 장치의 값이다.

    ### 정직하게 적어 둘 것

    - **`gate` 밖의 모듈에는 이 문이 없다.** 다른 모듈의 시험이 저장소 파일을 읽는 자리가 생기면 같은 구멍이 열린다. 지금은 게이트가 그런 시험을 거의 다 들고 있어서 여기부터 막았다.
    - **선언 목록은 여전히 손으로 쓴다.** 없어진 것은 *목록과 시험이 어긋나는 것* 이지 목록 자체가 아니다. 다만 어긋나면 **빌드가 빨개진다** — 그것이 전부다.
    - 결함 주입 셋 전부 겨냥한 시험이 잡았다(선언 하나 제거 ×2 · 목록 전달을 끊음).

116. **`verify_grasp` 를 붙였다 — 그리고 그 한 줄이 셋을 드러냈다.**

    시나리오 ② 를 명세에 대고 세어 본 결과 딱 하나가 어긋나 있었다. §4.2 의 요청 예시에 `verify_grasp: true` 가 있는데 `PrepareSequencedRack` 은 `object_id`·`destination` 둘만 보내고 있었다. 붙이라는 지시였고, **한 줄이면 될 줄 알았다.**

    ### ① 선택 파라미터는 아무에게나 보내면 안 된다

    `verify_grasp` 는 계약의 **선택** 파라미터다. 그리고 §5.3 이 *모르는 코어 키는 fail-closed* 로 못 박았다 — 안 드는 기종에 보내면 **태스크가 통째로 `PARAMETER_INVALID` 로 죽는다.** 실측으로 조사한 실물 중에 `pick_place` 를 들면서 이 필드는 선언하지 않는 기종이 있다.

    그래서 *언제나 보낸다* 가 아니라 **능력이 원하고, 로봇이 선언한 것만 보낸다** 가 됐다. `LogicalCapability.preferredOptionals` 가 원하는 것을 적고, 미들웨어가 `GetCapabilities` 로 확인해 거른다(`RobotPort.capabilities`, 널이면 **안 붙인다** — 모름은 없음이 아니다).

    ★**뺐다는 사실을 적는다.** 조용히 빼면 *파지 확인을 요구했다* 와 *못 해서 안 했다* 가 같아 보인다 — 진행률(§15.112)·결함·파지에서 반복해 거절한 접기다. 기체마다 **한 번만** 적는다.

    ### ② 미들웨어의 파라미터가 전부 문자열이었다

    붙이고 돌리니 **`picasso` 시험 열여덟이 빨개졌다.** `RobotPort.start` 가 `Map<String, String>` 이고 `ClientRobotPort` 가 전부 `string_value` 로 보내는데, `verify_grasp` 는 계약이 `BOOL` 로 선언한 첫 파라미터였다. 타입이 다르니 로봇이 거절하고 태스크가 죽었다.

    문자열인 것 자체는 이유가 있다 — **상류가 문자열이다**(ISA-95 의 `Value`). 없던 것은 **그 경계에서 계약의 타입으로 옮기는 자리**였고, 이제 `ClientRobotPort` 가 선언된 `ValueType` 을 보고 옮긴다. 타입을 모르면 문자열로 보낸다 — 선언에 없는 코어 키는 어차피 로봇이 거절하고, 그 거절이 조용한 변환보다 낫다.

    ### ③ 게이트 7번이 나를 잡았다

    측정 결과를 주석에 적으면서 **기종 이름을 `picasso/src/main` 에 썼다.** 게이트 7번(기종 분기 금지)이 그 문자열을 잡아 `:gate:test` 가 빨개졌다 — **주석이라도 안 된다.** 검사가 문자열 검사인 것이 무디다고 적어 뒀는데(ADR 23), 그 무딤이 여기서는 정확했다: 기종 이름이 이 모듈에 있으면 다음 사람이 그 옆에 분기를 쓴다.

    이름은 시험과 문서로 갔다. 시험은 **실물 프로파일 둘로 두 갈래를 만든다** — 선언하는 쪽과 안 하는 쪽. 지어낸 픽스처가 아니다.

    ### 정직하게 적어 둘 것

    - **아무도 `verify_grasp` 를 안 읽는다.** 미믹도 어댑터 넷도 이 필드를 안 본다 — 계약이 *물어볼 수 있다* 고 정한 자리이고 발신자가 생겼을 뿐이다. **미믹이 이것에 반응하게 만들지 않았다**: 그러면 미믹이 계약을 정의하게 된다(§15.7).
    - **협상을 안 지난다.** §5.4 의 `optionalFieldsUsed` 로 핸드셰이크에서 가르는 것이 원래 자리인데, 미들웨어는 협상을 안 하고 런타임에 능력을 보고 판정한다. 같은 사실을 다른 시점에 쓰는 것이며, 협상을 붙이는 날 둘 중 하나는 지워야 한다.
    - 결함 주입 일곱 전부 겨냥한 시험이 잡았다(안 붙임 · 아무에게나 붙임 · 모름을 지원으로 · 조용히 뺌 · 되풀이해 적음 · 필수를 덮어씀 · BOOL 에 문자열).

117. **안 읽히는 필드 셋과 이어지지 않던 시나리오 둘 — 시나리오 대조가 남긴 나머지.**

    §15.116 이 시나리오 ②의 어긋난 칸 하나(`verify_grasp`)를 닫았고, 대조가 남긴 것이 둘이었다.

    ### ① 값만 있고 아무도 안 읽던 것 셋

    `JobOrder` 가 상류의 모양을 흉내내면서 **쓰지 않는 값을 셋** 들고 있었다 — `request_id` · `due_by` · `materialRequirements`. 이 저장소가 계약에는 ADR 9 로 엄격히 막아 온 것을 **우리 상류 모델 안에서는 하고 있었다.**

    셋의 처분이 갈렸다.

    | 값 | 처분 | 왜 |
    |---|---|---|
    | `materialRequirements` | **쓴다** | 선언한 수량과 배정된 단위 수가 타입마다 같은지 본다. 어긋나면 **접수 자체를 거절**한다 |
    | `request_id` | **뺀다** | 재전송 구분은 `(jobOrderId, version)` 이 이미 한다. **두 키로 같은 질문에 답하면 어긋나는 날이 온다** |
    | `due_by` | **뺀다** | 납기는 배차의 입력이고 배차는 밖이다(§1.3). 쓰려면 정책을 발명해야 한다 |

    ★**자재 검사는 정책이 아니라 정합성이다.** 재고 판단은 WMS 의 일이고, 여기서 거절하는 것은 *스스로 어긋난 주문* 뿐이다 — 상류가 A형 둘이라 해 놓고 슬롯이 셋을 요구하는 경우. 받아 놓고 돌리면 그 어긋남이 **로봇이 실패한 뒤에야** 보이고, 그때는 이미 물리적으로 움직인 뒤다. 자재 선언이 아예 없는 주문(운반 ①)은 검사하지 않는다 — **없는 것과 어긋나는 것은 다르다.**

    ### ② 문서는 이었는데 코드는 안 이어져 있었다

    `scenarios.md` 가 *"① 이 남기는 것은 한 줄이다 — `SEQ-IN-02` 에 `HU-1042` 가 있다, ② 의 환경 전제"* 라고 이어 적었는데, **시험은 각각 독립이었고 ② 는 그 전제를 픽스처로 다시 세웠다.** 두 시나리오가 이름 규약(`SEQ-IN-02` → `SEQ-IN-02.BIN-A`)으로만 이어져 있었다.

    `ScenarioChainTest` 가 그것을 잇는다. 한 미들웨어가 ① 을 돌려 용기를 셀에 넣고, **② 의 출발 자리를 ① 의 목적지에서 유도해** 이어 돌린다. 그리고 반대쪽을 같이 본다 — **① 없이 ② 를 돌리면 로봇은 여전히 성공이라 말하고(미믹은 세계를 모른다) 설비가 확인할 것이 없어 `UNVERIFIED` 로 끝난다.** 갈리는 것이 E2 이고, 그것이 둘이 **근거로** 이어져 있다는 증거다.

    ### 정직하게 적어 둘 것

    - **연결 시험의 세계 모형은 거칠다.** 설비 대역이 *입고 자리에 용기가 있으면 슬롯에서 그 부품을 본다* 로 단순화돼 있다. 실제로는 로봇이 놓아야 보인다. **여기서 이은 것은 물리가 아니라 전제**이고, 물리를 흉내내면 그것은 우리가 지은 또 하나의 세계가 된다.
    - **`request_id` 를 뺀 것은 시나리오 표와 어긋난다.** §3 의 표에는 그 값이 있다. 표는 상류가 **보내는** 것을 적고, 우리는 그중 **쓰는** 것만 든다 — 그 차이를 `scenarios.md` 에 적었다.
    - 결함 주입 셋 전부 겨냥한 시험이 잡았다(어긋난 주문 접수 · 빈 선언을 불일치로 · 전제 없이도 설비가 봄).

118. **모듈마다 문을 세우고, 시나리오마다 시퀀스를 그렸다.**

    §15.114 에서 모듈 문을 **다섯만** 쓰고 *"나머지는 작아서 KDoc 으로 족하다"* 고 넘어갔다. 열여섯 중 다섯이었다. 사용자가 물어서 나머지 열하나를 마저 썼다.

    ### 문을 열여섯으로 만들고, 그것을 세는 것을 코드에 맡겼다

    각 문은 같은 네 칸이다 — *이 모듈의 규칙 하나* · *경계* · *여기 없는 것* · *어느 시험이 무엇을 증명하나*. **KDoc 을 옮기지 않는다.**

    ★기종별 어댑터 넷의 문은 **판정을 옮겨 적지 않는다.** 거리·조사 문서를 가리키고 요점만 적는다 — 숫자를 두 곳에 두면 어긋나고, 그 숫자는 `DocumentClaimsTest` 가 셀 수 없는 종류다.

    그리고 목록을 **손에서 뺐다.** `gate/build.gradle.kts` 가 모듈 문 목록을 `rootProject.subprojects` 에서 만들고, `DocumentClaimsTest` 가 *모듈마다 문이 있는가* 를 본다. 모듈이 늘면 문이 없어서 빨개진다 — 사람이 기억할 것이 하나 줄었다(§15.115 와 같은 규율).

    ### 시퀀스 다이어그램 넷

    `scenarios.md` 에 ①·②·③ 과 **①→② 가 만나는 자리**를 그렸다. mermaid 로 그린 이유는 하나다 — **텍스트라 낡지 않는다.** 이미지로 두면 코드가 바뀌어도 그림이 그대로이고, 아무도 그것을 안 잡는다.

    그림이 드러내는 것이 각각 다르다. ①은 **플릿의 완료가 곧 물리 완료가 아니라는 것**(E1 → 설비 → E2)과 통보 재시도가 운반 재실행이 아니라는 것. ②는 **슬롯 하나가 지나는 길**과 설비의 세 갈래(맞음·다름·침묵). ③은 **결과를 실을 자리가 없다**는 사실. 그리고 ①→②는 ★**로봇이 두 경우에 똑같이 성공이라 말하고 갈리는 것은 설비뿐**이라는 것 — 두 시나리오가 근거로 이어져 있다는 그림이다.

    ### 정직하게 적어 둘 것

    - **다이어그램은 시험이 아니다.** 그림이 코드와 어긋나도 빨개지지 않는다 — 문서 링크와 숫자는 `DocumentClaimsTest` 가 보지만 **그림의 내용은 사람이 지킨다.**
    - 모듈 문 열여섯 중 **적을 것이 적은 것도 있다**(`profile-model`·`capability`). 짧은 것이 낡은 것보다 낫다고 보고 짧게 뒀다.

119. **그림을 손으로 훑었다 — 셋이 틀렸고, 그중 하나가 그림의 요지를 배반하고 있었다.**

    §15.118 이 *"다이어그램은 시험이 아니다 — 그림이 코드와 어긋나도 빨개지지 않는다. 그건 사람이 지킨다"* 로 끝났다. **그 사람의 몫을 바로 했다.** 네 그림의 주장을 한 줄씩 코드에 대고 봤다.

    | 무엇 | 그림이 적었던 것 | 코드 | 왜 틀렸나 |
    |---|---|---|---|
    | **③ 점검 결과** | `SUCCEEDED + partial_result(결과 참조)` | **비어 있다** — `InspectionPatrolTest` 가 *아무도 안 채운다* 를 단언한다 | ★**그림이 제일 하고 싶었던 말을 그림이 뒤집고 있었다.** 이 시나리오의 요지가 *결과를 실을 자리가 없다* 인데 그림은 실려 온다고 그렸다 |
    | **② 능력 질의** | 슬롯 루프 **앞에서** 한 번 | 첫 `StartTask` **안에서**(그 뒤로는 세대 단위 캐시) | 효과는 같지만 위치가 다르다. 읽는 사람이 *접수 때 미리 묻는다* 로 읽는다 |
    | **① 통보 재시도** | 미들웨어가 다시 **민다** | 아웃박스에 **남아 있고** 상류가 `ack` 로 닫는다 | 방향이 반대다. 미는 것과 남아 있는 것은 재시도의 주인이 다르다 |

    ### 무엇을 배웠나

    **텍스트 다이어그램이라 낡지 않는다는 것과, 처음부터 맞다는 것은 다른 문제다.** §15.118 은 앞의 것만 보고 mermaid 를 골랐는데, 정작 이번에 나온 셋은 전부 **낡음이 아니라 처음부터 틀린 것**이었다. 그림은 기억으로 그렸고 코드는 안 봤다.

    ★그리고 세 오류의 성격이 갈린다. ②·①은 **정밀도**의 문제이고(위치·방향), ③은 **주장**의 문제다 — 그 그림을 그린 이유 자체를 부정한다. 산문이었으면 눈에 띄었을 텐데 **그림에서는 화살표 하나에 묻혔다.**

    ### 이 자리를 어떻게 지키나

    자동으로는 못 지킨다 — 시퀀스의 순서와 방향을 코드에서 뽑아 대조하는 것은 이 저장소가 지을 물건이 아니다. 대신 `scenarios.md` §5.1 에 **이 검토를 했다는 사실과 무엇이 틀렸었는지**를 적어 뒀다. 다음 사람은 최소한 *이 그림은 한 번 검토받았고 그때 셋이 틀려 있었다* 는 것을 안다.

120. **교체 지점을 문서로 세우고 검사로 묶었다 — 그리고 그 검사를 시험하다 *잡았다는 착시* 를 먼저 잡았다.**

    "상류와 하류가 서로를 모르고 각각 갈아 끼워진다" 는 주장은 **그 자리가 소스에 구체적으로 있고 빠짐없이 적혀 있을 때만** 참이다. `docs/seams.md` 가 그 답이다 — 자리 아홉(상류 · 설비 · 플릿 · 로봇 소비자 · 로봇 발신자 · 벤더 링크 · 발행 · 적재 · 프로파일 출처)에 각각 **인터페이스 / 지금 꽂힌 것 / 바꾸려면 / 안 고치는 것** 을 적고, 끝에 기계가 읽는 색인 열다섯 줄을 뒀다.

    ★**여기만 인터페이스가 아니다** — 상류의 교체 지점은 `Middleware` 의 공개 API 다. 포트가 없는 이유는 **상류가 우리를 부르기 때문**이고(ADR 36 의 방향), 그래서 인바운드 ACL 은 우리 코드가 아니라 그쪽 코드다.

    ### 검사가 문서를 코드에 묶는다

    이름을 바꾼 사람은 문서를 안 본다. `DocumentClaimsTest` 가 색인의 모든 줄에 대해 **파일이 있는지** 와 **그 파일에 그 인터페이스가 있는지** 를 본다.

    ### ★러너가 안 도는데 전부 "잡음" 으로 보였다

    결함 주입 넷을 돌렸더니 **네 개 다 잡혔다.** 그런데 실제로는 `gradlew.bat` 을 `cmd` 가 못 찾아 **시험이 한 번도 안 돌았고**, 내가 종료 코드만 보고 있었다 — 안 도는 러너의 exit 1 과 빨간 시험의 exit 1 이 같은 숫자다.

    §15.115 의 규율은 *"안 잡히면 시험이 돌았는지부터 의심하라"* 였는데, **거울상이 하나 더 있다: 잡혔다고 나올 때도 그것을 잡은 것이 시험인지 봐야 한다.** 고친 방법은 판정을 종료 코드에서 **XML 결과의 실패한 시험 이름**으로 옮긴 것이다. 주입 전에 러너를 한 번 맨몸으로 돌려 *돈 시험 수 > 0 · 실패 0* 을 확인하는 것도 같이 넣었다.

    ### 진짜 결과는 3/5 였고, 구멍 둘이 같은 뿌리였다

    - **하한이 검사 범위를 가렸다.** 앞 판은 정규식에 맞는 줄만 골라 세고 *"12줄 이상"* 만 봤다. 한 줄에서 **백틱만 빼면** 그 줄이 조용히 빠지고 하한은 그대로 넘는다. 고침 = 색인 구역의 **모든 표 줄이 읽혀야 한다**; 못 읽은 줄은 건너뛰는 것이 아니라 실패다.
    - **한쪽만 맞대고 있었다.** 본문이 이름 지은 인터페이스가 색인에 있는지만 봤으므로, **색인에만 있는 줄은 지워도 안 걸린다.** 실제로 `RobotAdapter` 한 줄이 그랬다 — 색인에 있는데 본문이 설명을 안 하고 있었다. 고침 = 본문에 그 자리를 적고(발신자 교체와 기종 추가는 다른 자리다), 대조를 **양방향**으로 바꿨다.

    둘 다 **하한은 내가 손으로 적은 숫자이고 목록은 문서가 스스로 댄다** 는 차이에서 나온다. 손으로 적은 숫자는 문서가 줄어드는 것을 못 본다.

    다시 돌린 주입 일곱 중 여섯을 겨냥한 단언이 잡았다(없는 타입 · 없는 파일 · 백틱 빠짐 · 코드 쪽 개명 · 본문이 이름 지은 줄 삭제 · 색인에만 있던 줄 삭제). 일곱째는 표 머리글 문구 교체이고 **등가 변이다** — 머리글은 아무것도 지탱하지 않는다.

121. **계약이 무엇을 담보하는지 적고, 담보마다 그것을 지키는 시험을 대게 했다.**

    *"여기가 계약이다"* 는 말은 두 질문에 답이 있을 때만 값이 있다 — **무엇이 이 면에 들어오는가**, **들어온 것이 무엇을 약속하는가.** `docs/contract.md` 가 그 답이다.

    ### 들어오는 문이 둘이고 둘 다 지나야 한다

    **소비자가 있어야 하고**(ADR 9), **발명이 아니어야 한다**(ADR 36 — 상류나 벤더 중 한쪽이 이미 가진 것만). 두 관문이 실제로 무언가를 막은 기록이 있다: 파지 실패를 거절 코드로 더하려다 멈춘 것은 첫째 관문(벤더가 그것을 선언이 아니라 런타임 피드백으로 말한다), 스킬 카탈로그가 못 살아남고 생명주기·Lease 가 살아남은 것은 둘째 관문이다.

    ★**`move_relative` 가 세 기종 모두에서 닿는다는 사실은 합격 신호가 아니다** — 너무 저수준이라 어디에나 있는 것이고, 그것은 층을 잘못 골랐다는 신호일 수 있다. 이 문장을 문서에 남긴 이유는 *"전부에서 되니 좋은 계약 요소"* 라는 추론이 자연스럽고 틀리기 때문이다.

    ### '아직 안 함' 과 '여기가 아님' 을 갈랐다

    안 나르는 것 여섯(안전 · 지연 상한 · 환경 전제 · 배차 · 실행 정밀 · 시맨틱 결속)마다 **왜** 와 **그럼 어디서** 를 적었다. 안전이 그 경계에서 가장 날카롭다 — 비상정지를 이 면에 얹으면 §4.8 의 세션·시퀀스와 §10.6 의 버퍼링이 그 아래 깔린다. **정지가 재생 버퍼를 지나게 된다.**

    ### ★담보 표의 오른쪽 칸

    담보 열여덟 줄마다 **그것을 지키는 시험** 을 `모듈 · 클래스 · 시험 이름` 으로 적었다. **시험이 없는 담보는 담보가 아니라 희망이다.** 그리고 그 칸이 낡지 않도록 `DocumentClaimsTest` 가 둘을 본다 — ① 담보 표의 **모든 줄**이 시험을 대는가(증명 칸을 비우면 주장만 남는다), ② 문서가 댄 이름이 **코드에 실재하는가.**

    대조가 시험 소스를 읽으므로 `:gate:test` 의 입력에 `<모듈>/src/test` 가 들어왔다. 아무 시험을 고쳐도 게이트가 다시 도는 대가를 또 한 번 감수한다 — **안 도는 검사보다 늦게 도는 검사가 낫다.**

    마지막 줄이 이 면의 요지다: **같은 `robot_id` 뒤에 다른 기종을 놓아도 오간 요청이 바이트 동일하다**(`harness · A1Test`). 기종을 갈아도 와이어가 안 바뀌는 것이 '계약' 이라는 말의 값이다.

    ### 담보하지 않는 것도 적었다

    전달(유한 버퍼 — 담보하는 것은 전달이 아니라 **잃었다는 사실**) · 시각의 정확도 · 진행률의 뜻(`ProgressBasis` 가 근거를 같이 나르는 이유) · 그리고 **종착 래치는 우리 것이지 로봇의 것이 아니다**(`gate · VendorSurveyTest · 종착이 래치되지 않는 실물이 있다` — 계약은 집행하지 않고 어긋남을 **결함으로 보이게** 한다).

    ### 어댑터를 쓰기 전에 기종을 재는 절차

    표면 전수(`survey_scope` 포함) → 거리 문서 → 프로파일 → **미믹으로 소비자 시험**(어댑터 0줄) → 협상 판정. 다섯 단계가 이미 저장소에 다 있고 문서는 그 순서를 적은 것이다. 답하지 못하는 것 셋도 같이 적었다 — 벤더 문서가 불완전할 수 있고(Orbit 이 그랬다), 타이밍·현장 제약은 종이에 없고, 부재는 **벤더의 낱말로 다시 물어야** 한다.

    결함 주입 다섯을 겨냥한 단언이 전부 잡았다(없는 시험 이름 · 없는 클래스 · 틀린 모듈 · 빈 증명 칸 · 시험 쪽 개명). §15.120 에서 배운 대로 주입 전에 러너를 맨몸으로 돌려 *돈 시험 11 · 실패 0* 을 먼저 확인했다.

122. **ISA-95 대조표 — 그리고 `UNKNOWN` 을 칸에 그대로 남겼다.**

    *"상류 모델은 표준에서 왔다"* 는 주장이 문장으로만 있으면 값이 없다. `docs/isa95.md` 가 **필드 단위로** 대조한다. 구분이 실무에서 중요한 이유는 하나다 — **표준의 칸은 상대 시스템이 이미 채울 줄 알고, 우리가 지은 칸은 붙이는 쪽이 뭔가를 해야 한다.**

    ### 근거를 등급으로 적었다

    정본(IEC 62264 / ANSI·ISA-95)은 **유료라 절 번호로 못 짚는다.** 그래서 그런 칸은 `UNKNOWN` 이다 — `version` 이 표준의 어느 칸에 대응하는지, 표준의 상태 어휘가 우리의 두 축(`physical_state` × `upstream_ack`)을 가르는지 둘이 그렇다.

    ★**`UNKNOWN` 은 '표준에 없다' 가 아니다.** 정본을 못 읽었다는 뜻이다. Digit 에서 근거 등급이 낮은데 `NO` 라 적었다가 판정 넷이 한 번에 뒤집힌 적이 있고(§15.65), 같은 규칙을 여기에 적용한다. 대신 **공개 표면은 이름으로 짚는다** — OPC UA 10031-4 의 `ISA95JobOrderDataType` · `ISA95JobResponseDataType` · `ISA95MaterialDataType` · `ISA95EquipmentDataType` 넷을 코드 주석이 이미 짚고 있었다.

    ### 빈칸을 채우는 것과 발명하는 것은 다르다

    표준이 *"does not define any standardized entries for EquipmentRequirements"* 라고 **명시적으로 비워 둔** 자리가 있고, 그 빈칸이 정확히 ADR 36 의 층 ② 다. `source`·`destination` 은 그 자리를 채운 것이지 새 어휘를 지은 것이 아니다. **표준이 *"여기는 도메인이 정한다"* 라고 말한 자리를 채우는 것과, 표준에도 벤더에도 없는 어휘를 짓는 것은 다르다.**

    ### ★§4 의 논거가 한 줄에 매달려 있다

    근거 등급 `E0`~`E3` 은 표준에 없다. 그것이 ADR 36 에 안 걸리는 이유는 **발명 금지가 계약(`picasso/v1`)의 등재 기준이지 미들웨어 내부 모델의 것이 아니기** 때문인데, 그 구분은 **등급이 계약 면으로 안 나갈 때만** 말이 된다.

    그래서 시험이 그것을 지킨다 — `contracts/proto/picasso/v1/` 어디에도 근거 등급이 없어야 한다. 등급을 계약에 넣으면 로봇이 자기 완료의 신뢰도를 자기가 선언하게 되고, 그것은 **확인하려는 대상에게 확인을 맡기는 것**이다. 문서의 논거가 코드의 성질에 매달릴 때 그 성질을 시험으로 못 박아 두는 것이 §15.121 과 같은 규율이다.

    ### 뺀 것도 적었다

    `due_by` 와 별도 요청 id 는 처음부터 안 쓴 것이 아니라 **들고 있다가 뺀 것**이다(§15.117). 인원·물리 자산 요구는 소비자가 없어(ADR 9) 안 들였다.

    결함 주입 셋을 겨냥한 단언이 전부 잡았다(문서가 없는 타입을 댐 · 코드가 타입을 잃음 · 근거 등급이 계약 proto 로 샘).

123. **현장 적용과 설정 관리 — 그리고 ADR 37 이 안 세운 나가는 문을 세웠다.**

    로봇을 공장에 넣는 일은 **한 번 넣는 일과 넣은 뒤로 계속 바꾸는 일** 둘이고, 이 둘이 섞이면 처음 적용할 때 무엇을 준비해야 하는지 아무도 모르고 가동 중에 바꿔도 되는 것과 라인을 세워야 하는 것이 구별되지 않는다. `docs/commissioning.md` 가 그 둘을 가른다.

    ### 축은 이미 업계가 그어 두었다

    ISA-95 의 객체 모델이 **정의(마스터 데이터)** 와 **그것으로 도는 일(런타임)** 을 다른 것으로 두고, VDA5050 이 AMR 쪽에서 같은 자리에 둔 것이 **Factsheet** 다 — 기체가 능력을 한 번 선언하고 그 뒤의 주문이 그 선언을 참조한다. **우리 능력 프로파일이 정확히 그 자리의 물건이다.**

    우리 쪽에서 갈리는 기준은 *"바꾸면 판정이 바뀌는가"* 다. 마스터(계약 semver · 카탈로그 · 어댑터 빌드 · 프로파일 · 설비 배선 · 시간창 δ)는 바꾸면 협상과 능력 대조가 달라지고, 런타임(인스턴스 · 기체 등록과 퇴역 · 이름 등록 기록 · 개정 · 소비자 요구)은 목록만 바뀐다.

    ★**셋째 칸이 있다 — 런타임에 바뀌는데 우리가 안 갖는 것.** 지도와 세계 모델(로봇 안에 산다) · 로봇 상태(관측이지 설정이 아니다) · 플릿의 기체 목록 · 자격증명. **들면 같은 사실이 두 곳에 있게 된다.**

    ### 순서에서 가장 자주 빠지는 칸

    열 단계를 적었고, **3번이 그것이다 — 로봇 안에 사이트 이름을 등록하는 일.** 소프트웨어 설치가 아니라 현장에서 로봇을 끌고 다니는 일이라 배포 체크리스트에 안 들어간다. 안 하면 계약이 나르는 이름을 로봇이 몰라 작업이 `PARAMETER_INVALID` 로 거절된다.

    ### ★기체가 현장을 떠나는 길이 없었다

    ADR 37 이 **들어오는 문 둘을 세우고 나가는 문은 안 세웠다.** 그래서 원장은 *한 번 들어온 기체가 영원히 있는 곳* 이었다 — 팔린 기체도 폐기된 기체도 목록에 남는다. **현장에 처음 적용할 때는 안 보이고 두 번째 해부터 보이는 구멍이다.**

    `V15` 와 `retire`/`reinstate` 가 그것을 닫는다. 결정 넷:

    - **지우지 않는다.** 행은 남고 목록에서만 빠진다 — 태스크 관측·감사 로그·바인딩 이력이 그 기체에 매달려 있고, 지난달 그 라인에서 무엇이 돌았는지 물으면 답이 있어야 한다.
    - **불리언이 아니라 시각이다.** 이 표는 이미 `registered_at`·`registered_by` 로 들어온 쪽을 적으므로 나가는 쪽도 같은 어휘여야 두 사건이 같이 읽힌다. 불리언은 *"언제부터 없는가"* 에 못 답하고 그 질문은 이력을 볼 때 반드시 온다.
    - ★**조작 문에만 있다.** 어댑터가 *"플릿에서 안 보인다"* 고 해서 퇴역이 되면 **네트워크 단절이 퇴역이 된다.** 안 보이는 것은 관측이고 떠난 것은 판단이며, ADR 37 이 들어오는 문을 가른 것과 같은 이유로 나가는 문은 하나다. 그래서 발견은 퇴역한 기체를 **되살리지 않고 사유와 함께 거절한다** — 자동 복귀면 운영자의 판단을 현장 프로세스가 매번 덮는다.
    - ★**퇴역한 뒤에도 보고가 오면 그것이 목록에 뜬다**(`reportingAfterRetirement`). 원장에서 내렸다고 현장에서 사라지지 않으며, 그 어긋남은 숨길 것이 아니라 볼 것이다 — 사이트 이름의 `CONTRADICTED` 와 같은 자리다. 그래서 `RETIRED` 가 다른 상태를 덮되 `lastReportedAt` 은 안 지운다.

    ### 설정 표면 전부를 적고, 그 *전부* 를 시험이 지킨다

    §4 가 표면 열넷을 적고 *"전부"* 라고 말한다. **전부가 아니면 그 문장이 거짓이고, 문서에 없는 문으로 현장 설정을 바꿀 수 있다는 뜻이 된다.** `DocumentClaimsTest` 가 양방향으로 본다 — 문서가 없는 표면을 적었는가, 그리고 **바꾸는 문(`/operations`·`/ingest`·`/requirements`)이 빠짐없이 적혔는가.** 열람(`/diag`)까지 요구하지는 않는다: 진단 하나 더할 때마다 표가 커지면 아무도 안 읽는다.

    결함 주입 열을 겨냥한 시험이 전부 잡았다 — 퇴역 일곱(첫 사유 덮기 · 상태 안 덮기 · 현역 목록에 섞임 · 퇴역한 기체 재등록 · 퇴역 뒤 보고 숨김 · 복귀가 사유 안 지움 · 모르는 기체를 성공으로 답함)과 설정 표면 셋(문서에서 문 빼기 · 없는 표면 적기 · 코드에 문 더하고 안 적기).

    (닫힘, §15.249) 이 항목이 남긴 한계 행 «§15.123 · 기체 퇴역 시 작업 즉시 중단 미집행»은 §15.249에서 닫혔다. 해소 조건이 적었던 활성 태스크 자동 취소는 택하지 않았고, 퇴역한 기체로 새 로봇 명령을 내지 않는 쪽으로 닫았다. 도는 단위는 끊지 않는다.


124. **저장소가 담지 않기로 한 것을 시험이 지킨다 — 그리고 표지를 온전히 적을 수 없다는 것이 검사의 모양을 정했다.**

    이 저장소의 설계 판단 중에는 **저장소 밖의 사적 문서**를 읽고 내린 것이 있다. 규율은 처음부터 하나였다 — *판단은 그대로 하되 저장소에는 벤더 1차 자료로 독립 확인되는 근거만 적고, 확인이 안 되면 단정을 지우고 비워 둔다.* 2026-09-10 에 **242 커밋을 전수로** 훑어 그 규율이 **한 번 샌 것**을 찾았다: §15.91 의 첫 문장과 **같은 커밋의 메시지**가 사적 문서를 근거로 댔다. 조직·사업장·연도 계열은 전 히스토리에 0 건이었다 — 규율 자체는 서 있었고 샌 것은 한 자리다.

    **문장을 고친 것이 아니라 히스토리에서 걷어냈다.** §15.91 의 커밋(지금은 `d65567b`)부터 tip 까지 **37 커밋을 다시 썼고**, 옛 tip 대비 차이는 **한 파일 한 줄**이다(커밋 수 242 그대로, 줄바꿈 손상 없음). **옛 SHA 는 문서에 안 남긴다** — 걷어낸 뒤에는 없는 객체를 가리키는 인용이 된다. 출처만 빼고 기술 내용은 남겼다 — **넷이 무엇인지는 이 저장소가 스스로 말할 수 있다.**

    ★**금지하려는 낱말을 온전히 적을 수 없다는 것이 설계가 됐다.** `PrivateContextTest` 는 `:gate:test` 의 **선언된 입력 417 개**를 훑는다(§15.115 이 세운 문에 `Repo.declaredFiles` 를 하나 더했다 — 이름을 아는 파일만 훑으면 **새로 생긴 파일은 안 훑는다**). 그런데 표지를 온전한 낱말로 적으면 **이 파일 자신이 걸리고, 그 전에 금지하려는 문자열을 저장소에 들여놓는 것**이 된다. 그래서 반쪽으로 나눠 이어 붙인다.

    **정직**: 그래서 잡히는 것은 **사적 문서의 갈래를 가리키는 낱말 여덟**뿐이다. **조직과 사업장의 이름은 못 잡는다** — 같은 이유로 여기 적을 수 없고 그 목록은 저장소 밖에 있다. 이 검사는 사람의 규율을 대신하지 않고 **되풀이되는 실수 한 갈래**만 막는다.

    주입 둘 다 잡혔다 — 걷어낸 문장을 다른 문서에 되돌려 놓으니 **파일·줄·표지를 짚어** 빨개졌고, 문턱을 올리니 **훑은 개수 417** 이 드러났다. 뒤엣것이 요점이다: **빈 목록을 훑는 시험은 잡을 것이 없는 것과 안 본 것이 같은 색이다.**

125. **감사가 문서 셋을 훑었다 — 그리고 "참이지만 아무도 안 받치는 문장"이 무더기로 나왔다.**

    완료 조건의 첫 감사 묶음(`verification.md`·`limits.md`·`architecture.md`). **틀린 여섯**은 고쳐서 넷을 시험으로 받쳤다(같은 커밋). 그런데 남은 갈래가 하나 더 있었다 — **틀리지는 않았는데 그것을 지키는 것이 아무것도 없는 문장들.**

    ★가장 큰 덩이는 `architecture.md` 의 **의존 규칙 그림**이다. 아홉 줄 중 게이트가 집행하는 것은 **둘뿐**이다(검사 5번의 `contracts` 의존 0, 검사 7번의 여덟 모듈 기종 문자열). 나머지 일곱 — `profile-model`·`capability`·`uplink`·`adapter-core` 를 누가 공유하는가, `registry` 가 아무 데도 안 민다는 것 — 은 **전부 산문일 뿐이다.** 같은 성질이 `verification.md` 에도 있다(기본 스위트가 in-process 발행자를 쓴다는 것, 디스크립터가 protoc 산출이라 `:gate:test` 가 그 태스크에 매달려 있다는 것).

    **지어서 닫을 수 있다.** 모듈 의존 그래프는 `settings.gradle.kts` 와 각 `build.gradle.kts` 에서 읽어 그림과 댈 수 있고, 검사 7번이 그 모양을 이미 보여 준다. 지금 안 짓는 이유는 하나다 — **이 라운드의 일은 감사이지 새 검사를 짓는 것이 아니고**, 열어 적으면 그 사실이 대장에 남아 다음 사람이 고를 수 있다.

    **(닫힘, §15.195 — 2026-09-23 재측: 이 자리는 그 뒤에 지어졌고 대장만 안 따라갔다. 「모듈 의존 그림이 빌드와 같다」와 `ComponentMapTest` 가 그것이다.)**

    ★**그래서 열림으로 적는다. 그것이 값을 치르는 방식이다** — 완료 조건 §4 가 강등에 값을 매겼고, 밖을 향한 문서가 드는 열림 수가 늘면 `limits.md` 를 고쳐야 하며 그 편집이 diff 에 보인다. **안 비는 대장으로 끝나는 것이 이 조건의 요점이다.**

126. **배차·라우팅이 비목표라는 것을 대장이 안 적고 있었다.**

    `architecture.md` §5 가 *"여기 없는 것"* 으로 **배차·라우팅·자원 중재·다중 로봇 경합**을 들면서 *"비목표"* 라고 적는데, `limits.md` 의 갈래 1(의도적 밖)에 그 행이 없었다. 가장 가까운 것이 §15.9(다중 사이트)인데 다른 것이다.

    ★**대장에 없으면 열림으로 댈 수 없고, 댈 수 없으면 그 문장은 완료 조건에서 삭제 대상이 된다.** 지우는 것이 맞는 답일 리 없다 — 그것은 이 저장소가 실제로 내린 범위 결정이다. 그래서 대장에 행을 연다. **감사가 문서를 고치는 만큼 대장도 고친다.**

127. **감사가 대장의 빈칸을 드러냈다 — 일지에 있는데 색인에 없는 한계들.**

    감사 둘째·셋째 묶음에서 문서들이 근거로 댄 §15 항목 중 **대장에 행이 없는 것이 여럿**이었다. `limits.md` 는 스스로 *"일지가 정본이고 이 문서는 색인이다"* 라 적는데, **색인이 정본을 다 싣지 않고 있었다.**

    ★그것이 왜 문제인가 — 완료 조건에서 **대장에 없으면 열림으로 댈 수 없고, 댈 수 없으면 그 문장은 삭제 대상이 된다.** 실제로 참인 한계를 적은 문장이 *근거를 못 대서* 지워질 뻔했다. 색인의 누락이 문서를 지우게 만드는 구조였다.

    §15.7(미믹이 계약을 정의해버릴 위험) · §15.22(`contract_digest` 가 디스크립터 셋의 SHA-256) · §15.38(HTTP 표면에 인증이 없다) 셋을 대장에 색인했다. **새 한계가 아니라 이미 일지에 있던 것이다.**

    ★**반대 방향도 나왔다.** 닫힌 항목(§15.111·§15.113)을 근거로 댄 문서가 있었다. 닫힌 것을 열림으로 대면 대장이 그것을 되살려야 하므로, 그 자리는 인용을 지우는 것이 맞다.

128. **자격증명을 어디에 두는지 안 정했다.**

    `commissioning.md` §5 가 *"자격증명을 어디 두는지 안 정했다(설계 §6.3). 접속 설정은 **주소만** 받는다"* 고 적는데 대장에 행이 없었다. 접속 설정이 주소만 받는 것은 §15.101 에서 내린 결정이고(비밀은 안 받는다), **그 결정의 나머지 절반 — 그러면 비밀은 어디 사나 — 이 안 정해진 채로 남아 있다.**

129. **사이트 이름 대조가 오타 하나는 못 잡는다.**

    레지스트리는 이름 대신 **개수만** 받는다(ADR 35 — 이름은 로봇 안에 산다). 그래서 *"셋을 등록했다"* 와 *"셋을 안다"* 가 맞으면 통과하고, 그중 하나가 오타여도 수가 같으면 안 걸린다. `CONTRADICTED` 가 잡는 것은 *못 한다면서 개수를 냈다* 는 모순이지 이름의 내용이 아니다.

    ★**닫으려면 이름을 받아야 하는데, 그것은 ADR 35 를 뒤집는 것이다.** 그래서 이 행은 값이 싸지 않다 — 적어 두는 것이 지금 할 수 있는 전부다.

130. **완료 조건을 만족한다 — 그리고 그 값이 얼마였는지도 함께 적는다.**

    자리 **47** 전부가 도장을 갖고, 밖을 향한 문서가 드는 열림은 **117** 이며, 셋 중 어느 것도 아닌 문장이 **0** 이다. 게이트 시험 242, 실패 0.

    **감사가 실제로 잡은 것: 문서가 코드와 어긋난 자리 스물 몇.** 무거운 순서로 셋만 —
    ① `architecture.md` 의 상태기계 표가 **태스크 상태 칸에 실패 분류를 섞어** 놓고 있었다. 그 표의 요지가 *"둘은 다른 층이고 하나가 다른 하나를 대신하지 않는다"* 인데 표 자신이 셋째 어휘를 들여놨다.
    ② `seams.md` 가 *"`mimic --registry` 로 프로파일 출처를 바꾼다"* 고 적었는데 **방향이 반대**였다 — 교체 지점 문서가 교체하는 방법을 틀리게 적고 있었다.
    ③ `README.md` 가 *"어댑터는 `contracts` 하나에만 의존한다"* 를 `adapter-core` 가 생긴 뒤에도 들고 있었다.

    ★**§4.2 가 실제로 물었다.** 셋째 것은 갈래 2 열림으로만 정당화되는 문장이었고, README 는 그런 열림을 못 든다. 그래서 **지어야 했다** — 각 모듈의 `build.gradle.kts` 에서 출하 의존을 읽어 `architecture.md` §4b 의 표와 대는 시험. 지어 보니 **그 문장은 애초에 거짓이었다.** 규칙이 없었으면 열림으로 남아 계속 거짓이었을 것이다.

    ★**왜 이것들이 살아 있었나 — 구조가 나왔다.** 도장 없는 문서는 해시 검사도 유령 id 검사도 `?: return@mapNotNull null` 로 **조용히 건너뛴다.** 그래서 `environment-preconditions.md` 가 대장에 없는 `§15.79` 를 근거로 대고도 초록이었다. 그리고 링크 검사가 `Files.list`(비재귀)라 **`docs/adr/` 를 아예 안 봤다** — 재귀로 바꾸자마자 죽은 링크를 정확히 하나 잡았다.

    ★**대장은 색인인데 정본을 다 싣지 않고 있었다.** §15.4·7·22·38·79 가 일지에 살아 있는 한계인데 행이 없었고, **대장에 없으면 열림으로 못 대고 못 대면 삭제 대상이 된다.** 참인 문장이 색인 누락 때문에 지워질 뻔했다. 행 열둘을 더했다(색인 다섯 + 신설 일곱).

    **정직하게 적는 값.** 이 조건의 유지비는 싸지 않다 — 문서를 고칠 때마다 도장을 다시 찍어야 하고, 열림 하나를 옮기면 `limits.md` 의 수가 따라와야 한다. 사용자가 진행 중에 *"작업을 위한 작업을 하고 있는 것 같다"* 고 물었고 **그것은 맞는 지적이었다.** 값이 있던 것은 **결함 스물 몇과 안티로트 시험 아홉**이고, 도장·해시·117 을 맞추는 사슬은 그 자체로는 저장소를 좋게 만들지 않는다. ★**다음에 이런 조건을 세울 때는 조건의 유지비가 조건이 잡는 결함보다 싼지를 먼저 본다.**

    **닫히지 않은 채로 끝난다.** `limits.md` 는 117 개의 열림 표기를 든 채 남고, 그것이 이 조건의 요점이다 — **안 비는 대장으로 끝날 수 있다.** C-3(실물 미검증)은 지어서 못 닫으므로 열린 채 선언한다.

131. **공개하자마자 CI 가 빨개졌다 — 한 번도 돌아 본 적 없는 검사가 거기 있었다.**

    원격을 만들고 push 하니 **검사 2번(`buf breaking`)이 실패했다.** 파괴가 아니라 인프라였다 — 컨테이너 안의 git 이 마운트된 `/workspace/.git` 을 *dubious ownership* 으로 거부한다. 러너에서 파일 소유자가 컨테이너 사용자와 다르기 때문이다.

    ★**원격이 없어서 CI 가 한 번도 안 돌았다.** 워크플로 파일은 처음부터 있었고 `--require` 로 *건너뛴 것이 초록으로 보이지 않게* 까지 해 뒀는데, **그 워크플로 자체가 실행된 적이 없었다.** §15.15·16·29·39 가 *"CI 에서만 도는 검사들"* 을 열림으로 적어 둔 그 자리이며, 이번에 그 열림이 **실제로 물었다.**

    ★**로컬에서는 재현되지 않는다.** Windows·Docker Desktop 에서는 마운트가 root 소유로 보여 git 이 안 멈춘다. 이 저장소가 *"CI 에서만 도는 검사는 환경의 성질"* 이라 적어 둔 것의 구체적인 얼굴이다.

    ★★**첫 판이 틀렸고 CI 가 그것을 잡았다.** 환경변수(`GIT_CONFIG_COUNT` 규약)로 `safe.directory` 를 주었는데 **아무 일도 안 했다** — git 은 그 설정을 *protected configuration*(시스템·전역)에서만 읽고, **명령줄과 환경변수로 준 것은 의도적으로 무시한다**(그러지 않으면 공격자가 주입할 수 있다). 같은 자리에 두 번 걸렸다.

    고친 방법은 **임시 파일을 만들어 `/etc/gitconfig`(시스템 설정)로 마운트**하는 것이다. `exec` 를 버리고 종료코드를 받아 넘기며 임시 파일을 지운다.

    ★**이것이 CI 가 사는 이유다.** 로컬에서 `bash -n` 도 `--version` 도 통과했고 겉보기 증상도 같았다. **틀린 고침과 맞은 고침이 로컬에서 구별되지 않았다** — 러너만이 그 둘을 가른다.

    ★**공개의 첫 수확이 이것이다.** 실물에 붙여 보지 않으면 안 드러나는 것이 있다는 것이 이 저장소의 상한(C-3)인데, **CI 도 같은 성질이었다.** 원격이라는 "실물" 에 붙이자 한 번도 안 돌던 검사가 바로 빨개졌다.

132. **그림 셋을 다시 그렸다 — 그리고 도장 도구가 근거를 조용히 지우는 것을 봤다.**

    사용자가 그림을 보고 셋을 지적했다: 배경이 희다 · 품질이 떨어진다 · 글이 번역투다. 첫째는 내 잘못이 아니라 **내가 보여준 방식의 잘못**이었다 — 검사용으로 띄운 PNG 가 라이트 변형이었고 다크 짝은 멀쩡히 있었다. 나머지 둘은 맞았다.

    ★**참조본과 나란히 놓고서야 차이가 보였다.** `yggdrasil-iiot` 의 `activation-ladder` 는 칸마다 *더한 것 · 막은 구멍 · 그것을 지키는 스크립트 이름* 이 들어 있다. 내 그림은 칸마다 **제목 하나와 `·` 로 이어 붙인 명사 목록** 뿐이었다 — arch-diagram 스킬이 실패 유형 표에 이름까지 적어 둔 바로 그것이다. 여백이 넓은 것이 세련된 것이 아니라 **댈 근거가 없었던 것**이다.

    **근거는 이미 문서에 있었고 그림이 그것을 안 들고 있었다.** `seams.md` 는 자리마다 *면 / 바꾸려면 / 안 고치는 것* 세 줄을 다 갖고 있다. 그림을 아홉 줄짜리로 다시 짜서 그 셋을 그대로 세우고, 오른쪽에 그 면이 사는 모듈을 붙였다. 그러자 **"안 고치는 것" 이 초록 한 줄로 아홉 칸을 관통**한다 — 코어를 안 뜯는다는 주장이 문장이 아니라 배치로 읽힌다.

    ★★**두 번째 그림은 사실관계가 틀려 있었다.** `ChangePlanService` 는 **의도 넷마다 단계 목록이 다른데**(`RETIRE_ADAPTER_VERSION` 은 예고가 아예 없고, `RETIRE_PROFILE_REVISION` 은 드레인도 없다) 그림은 `REMOVE_CAPABILITY` 하나만 그려 놓고 그것을 *"변경 계획"* 이라 불렀다. 조건 여섯을 먼저 세우고 그 아래 의도 넷 × 단계 다섯 표를 놓는 형태로 바꿨다. 그리고 dry run 의 핵심인 `Observed.NOT_OBSERVABLE` — **못 세면 충족은 언제나 거짓이다** — 가 빠져 있던 것을 넣었다.

    ★★★**`tools/stamp.py` 가 열림 인용을 조용히 지웠다.** 그림만 고치고 세 문서를 다시 찍었는데 `--open` 을 안 줬더니 기본값이 `[]` 라 **`열림: 없음` 으로 덮어썼다** — 실측 11 개가 한 번에 사라졌다. §15.130 이 세운 조건의 *강등의 값* 이 통째로 무력해지는 자리다.

    **기계는 잡았을 것이다.** 열림 표기 수와 `limits.md` 가 적은 수를 대조하는 시험이 117 ≠ 106 으로 빨개졌을 것이다. 그러나 그때는 **어느 문서의 어느 id 였는지가 이미 없다** — 잡히는 것과 되돌릴 수 있는 것은 다르다. 그래서 도구를 고쳐 **`--open` 을 안 주면 원래 도장의 열림을 그대로 옮기게** 했고, 지우려면 `--open` 을 빈 채로 주어야 한다.

    ★**규율.** 증거를 쓰는 도구의 기본값은 *비움* 이 아니라 *보존* 이다. 지우는 것은 명시적으로 시켜야 하는 일이다.

    **얼굴의 문장도 고쳤다.** README 그림이 *"실물이 없다. 통합 시험이 예상 소비자다"* 와 *"로봇이 없다"* 를 달고 있었다. 사실이지만 그 두 줄이 그림의 요지를 배반한다 — 이 그림이 하는 말은 *계약 하나가 이음매다* 이지 *우리는 실물이 없다* 가 아니다. §15.119 가 같은 자리에서 배운 것이고 이번이 두 번째다.

133. **거리 판정을 산문에서 칸으로 옮겼다 — 그리고 안 본 칸 일곱이 드러났다.**

    사용자가 짚은 것이 요점이었다: **이 매핑 단계에서 잘못 판단하면 그 아래 프로파일 검증이 통째로 무의미해진다.** 기계가 보는 것은 일관성이지 정확성이 아니고, 사슬의 뿌리인 거리 판정만 사람이 쥔다.

    ★**산문 `evidence` 는 빠진 것을 안 보여 준다.** *"파라미터 넷이 하나씩 대응한다"* 는 넷을 세어 보지 않고도 통과한다. `parameter_map` 이 계약 파라미터를 **전부** 요구하게 하자 **27 칸 중 7 칸이 조사된 적 없다**는 것이 드러났다 — 선택 파라미터 넷(`verify_grasp`·`grip_force`·`grasp_attempts`·`mode`)이다. 장치가 처음 돌면서 바로 빚을 찾았다.

    ★★**그래서 `NOT_SURVEYED` 가 생겼다.** 그 넷을 `UNMAPPED`(대응이 없다)로 적으면 **안 해 본 조사를 했다고 주장하는 것**이 된다. *부재를 단정하려면 1 차 근거가 있어야 한다*(§15.65 가 만든 `evidence_grade`)는 규율이 칸 단위로 내려온 것이다. 앞은 주장이고 뒤는 빚이며, 그 구분이 없으면 빚이 주장으로 위장된다.

    **`limitations` 는 Open-RMF 에서 가져왔다.** 통합 등급에 `Full Control`·`Traffic Light` 라는 **이름**을 준 자리인데, **우리는 기종이 아니라 칸마다 붙인다** — 한 기종 안에서 스킬마다 다르다는 것이 Spot 에서 드러났기 때문이다(명령 계층만 있으면 `move_relative` 는 돌고 `navigate_to` 는 안 돈다). `YES` 인 칸에도 붙는다: G1 `move_relative` 가 `NO_TERMINAL_SIGNAL` 이다.

    ★★★**`MACHINE_DRAFT` 는 AI 초안을 들이기 위한 문이 아니라 자물쇠다.** 기계가 1 차로 훑으면 이 단계의 괴로운 절반(전수 훑기)이 싸진다 — 그리고 그 값의 근거가 이 저장소 안에 있다. Spot 첫 판이 서비스 54 개 중 3 개만 읽고 쓰였다가 전수 조사에서 결론 셋이 뒤집혔다.

    그런데 **진짜 위험은 틀린 제안이 아니라 앵커링이다.** 백지에서 시작하면 사람은 *"이게 되나"* 를 묻고 초안을 받으면 *"이게 맞나"* 를 묻는데, 뒤엣것이 훨씬 잘 통과한다. 그래서 **초안은 `reachable` 을 못 채운다** — `MACHINE_DRAFT` 인 칸은 `UNKNOWN` 이어야 하고 스키마와 시험이 둘 다 막는다. `UNKNOWN` 은 `YES` 집합에 안 들어가므로 프로파일 선언과 묶이지 않는다. **사람의 성실함이 아니라 기계가 지킨다.**

    **환각 방어는 이미 있었는데 자리가 틀렸다.** 벤더 매니페스트 대조(이름 + 원본 sha256)가 어댑터 코드의 `@VendorSurface` 만 보고 있었고 **거리 문서의 인용은 밖이었다.** `adapter` 필드로 어느 매니페스트에 댈지 정하고 같은 대조를 받게 했다 — 없는 심볼을 짚으면 커밋 전에 빨개진다. 27 칸 중 20 칸을 실제 심볼로 짚었다.

    **결함 넷을 넣어 넷이 각자 잡히는 것을 봤다** — 대응표에서 파라미터 하나 빼기 · 필수 파라미터를 안 본 것으로 바꾸기 · 없는 벤더 심볼 짚기 · 초안에 판정 채우기. 넷 다 **겨냥한 시험이** 잡았고, 마지막 것은 스키마와 시험이 함께 잡았다.

    ★**남의 것 셋을 보고 왔다.** Open-RMF(기종 하나에 등급 하나) · openTCS(`canProcess` 가 이유를 단 참/거짓) · VDA 5050(`factsheet.agvActions[]` 로 AGV 가 선언). **셋 다 이 단계를 피하지 못했고, 셋 다 판단의 근거를 안 남긴다** — 등급 선택도 `canProcess` 구현도 factsheet 작성도 코드와 설정에 녹는다. 표준을 세운 VDA 5050 조차 *"AGV 의 능력이 액션 설명에 대응하면 그것을 쓴다"* 로 끝나며, **판단은 사라지지 않고 벤더 쪽으로 옮겨갈 뿐이다.** 근거를 문서로 남기는 것이 이 저장소가 지켜야 할 차별점이다.

    **못 하는 것은 그대로다.** 이 장치 어느 것도 **잘못된 판정 자체**는 못 잡는다. 잡는 것은 빈칸·모순·범위 누락이고 *"닿는다고 썼는데 실은 안 닿는다"* 는 실물에 붙는 날 드러난다(C-3). 사용자의 표현이 정확했다 — **레시피가 맛을 보장하지는 않지만, 순서가 있으면 빠뜨릴 자리가 줄어든다.**

134. **프로파일과 어댑터 사이가 끊겨 있었다 — 그리고 미믹의 초록이 그것을 가리고 있었다.**

    §15.133 으로 사슬을 훑다가 나왔다. 거리 판정 ↔ 프로파일은 묶여 있고(*"닿는다고 적은 것과 선언한 것이 같다"*) 프로파일 ↔ 계약 카탈로그도 묶여 있는데(`Check04CrossRef`), **프로파일 ↔ 어댑터만 대조가 없었다.**

    ★★**그 자리를 미믹이 가린다.** 미믹은 **프로파일이 곧 거동**이라 프로파일과 절대 안 어긋난다. 그래서 `AllModelsTest` 의 *"모든 기종이 공통 태스크를 완주한다"* 가 초록인 것은 **어댑터에 대해 아무 말도 하지 않는다.** 선언한 스킬을 어댑터가 안 들면 협상은 통과하고, 실물에서 `UNSUPPORTED_SKILL` 로 죽을 때까지 모든 검사가 초록이다. 사용자가 *"잘못 판단하면 프로파일 검증도 무의미해질 수 있다"* 고 한 것의 **기계적인 형태**가 이것이다.

    ★**대조가 없으면 모양도 안 모인다.** 어댑터 넷의 dispatch 가 제각각이었다 — Spot·Digit 은 `when(skillType)`, G1 은 `if (skillType != SKILL)`, Orbit 은 `if (skillType != NAVIGATE)`. 통일될 이유가 없었기 때문이다.

    **스킬 목록을 손으로 안 든다.** 어댑터 출하 소스의 문자열 상수를 뽑아 **계약 카탈로그와 교집합**을 낸다 — 파라미터 키도 같은 모양이라(`P_LOCATION = "location"`) 값만으로는 못 가르는데 카탈로그가 가려 준다. 어휘가 늘면 이 시험이 저절로 따라온다.

    결함 둘을 양방향으로 넣어 둘 다 잡혔다 — 어댑터가 스킬을 개명한 것과, 프로파일이 어댑터가 안 드는 스킬을 선언한 것. 뒤엣것은 **시험 둘이** 잡았다(§15.133 의 판정 대조까지 걸린다).

    **문자열 검사다**(ADR 23 · 게이트 5·7 번과 같은 가족). 주석에 스킬 이름을 적어도 통과한다 — 실수를 막는 장치이지 우회를 막는 장치가 아니다.

135. **VDA 5050 에서 물음만 가져오고 값은 안 가져왔다 — 그리고 소비자는 어댑터 안에 갇혀 있었다.**

    §15.133 의 §7 이 열림으로 세워 둔 `actionScopes` 축을 짓기로 하고, 먼저 관문 둘을 댔다. **ADR 36(발명 아님)은 통과한다** — VDA 5050 과 Spot Autowalk 가 둘 다 가진 것이다. **ADR 9(소비자)에서 걸렸다.**

    ★**값을 그대로 들이면 못 쓴다.** VDA 5050 의 `node`·`edge` 는 **주문이 그래프**라서 생긴 값이고 우리 주문은 태스크 열이라 앉을 자리가 없다. 계약 카탈로그에 넣어 봐야 읽는 곳이 없는데, 기존 카탈로그 옵션은 전부 실제 소비자가 있다 — `grasps_object`·`is_object_reference` 는 미믹의 `ObjectReferences` 가, `is_site_reference` 는 `SkillTypeSync` 가 읽는다. **소비자 없는 선언은 ADR 9 가 막는 바로 그것이다.**

    ★★**그래서 물음만 가져왔다.** *어디서 실행되는가* 를 이 저장소의 낱말로 답하면 **그 스킬이 어느 층 위에서 도는가** 이고, 어휘는 이미 `layer.vendor_layer` 에 있었다. 문서마다 하나이던 것을 **칸마다** 붙였다.

    ★★★**소비자는 지은 것이 아니라 찾은 것이다.** `SpotAdapter` 안에 `private enum class Layer { COMMAND, MISSION, ACQUISITION }` 가 이미 있고 태스크마다 층을 기억하며 **`poll`·`cancel`·`update`·종착 판정이 전부 거기서 갈린다** — 명령 계층은 **시계로** 종착을 적고 미션 계층은 벤더가 상태를 준다. 한 어댑터 안에 갇힌 사실이었고, 문서가 그것을 안 들고 있었다.

    **문서의 한 줄을 칸들이 떠받치게 했다.** `layer.vendor_layer` 는 이제 칸들의 **최댓값**이어야 한다(`NONE` < `COMMAND` < `MISSION`). G1 문서가 스스로 적어 둔 애매함 — *"이 값은 이제 **기종에 태스크 개념이 없다** 가 아니라 **우리가 쓰는 표면에 없다** 로 읽어야 한다"* — 이 그 관계로 닫힌다. **실측으로 넷 다 이미 일치했다**: Spot MISSION(COMMAND·MISSION·MISSION·COMMAND) · Digit MISSION · G1 NONE · Orbit MISSION. 관계가 데이터에 이미 참이었다는 것이 이 모양이 맞다는 증거다.

    결함 둘을 넣어 둘 다 잡혔다 — 칸의 층을 낮춰 문서 값과 어긋나게 한 것과, 못 닿는 스킬에 층을 단 것(뒤엣것은 시험 **둘이** 잡았다).

    ★**열림 하나를 지어서 닫았다.** §15.133 이 세운 *실행 범위* 가 대장에서 내려간다. 남은 것은 골격 템플릿 하나다.

136. **골격을 지었다 — 모듈로 만들지 않았고, 그래서 시험이 유일한 보증이다.**

    §15.133 이 세운 마지막 열림이다. 매번 남의 어댑터를 베꼈고, **베끼면 그 기종의 사정까지 온다** — Spot 의 리스 처리나 Digit 의 권한 게이트가 상관없는 기종에 남는다.

    ★**모듈로 안 만들었다.** `adapter-template` 을 진짜 모듈로 세우면 벤더도 프로파일도 없는 모듈이 빌드에 서고, 게이트 검사 여덟이 전부 그것을 대상으로 삼는다 — 매니페스트도 프로파일도 없으므로 검사마다 예외를 파야 하고, **예외가 여덟이면 그 검사들이 무엇을 막는지 아무도 모르게 된다.** 그래서 `tools/adapter-template/` 의 `.kt.txt` 로 뒀다.

    ★★**대가는 컴파일러를 잃는 것이다.** `RobotAdapter` 의 면이 늘어도 골격은 **조용히 낡는다** — 이 저장소가 반복해 물린 바로 그 모양이다. 그 자리를 `AdapterTemplateTest` 넷이 대신 본다: 반드시 채우는 자리를 빠짐없이 드는가 · 기본값 있는 것을 갈라 적는가 · README 가 세는 수가 면과 같은가 · **README 의 파일 일곱을 어댑터 넷이 전부 갖는가.**

    마지막 것이 요점이다 — **목록을 발명이 아니라 실측이게 한다.** 손으로 적은 파일 목록은 관례가 바뀌면 낡는데, 실제 모듈 넷에 대면 먼저 빨개진다.

    결함 다섯을 넣어 넷이 각자 물었다 — 면에 자리 더하기(시험 둘이 잡았다) · 골격에서 자리 지우기 · README 의 수 틀리기 · 파일 목록에서 한 줄 빼기 · 선택 목록에서 하나 빼기.

    **골격이 거리 문서를 가리킨다.** 순서 1~8 은 `docs/vocabulary-distance.md` 가 정하고 9 번부터가 골격이다. `parameter_map` 의 `CONVERTED`·`SYNTHESIZED` 줄이 `accept` 안의 코드이고, `adapter_must_own` 이 코드량 견적이고, `execution_scope` 가 `poll` 의 모양을 정한다 — `COMMAND` 면 시계로 적어야 한다. **재는 것이 먼저이고 채우는 것이 나중이라는 순서를 파일 배치가 말하게 했다.**

    ★**안 채워도 되는 일곱을 굳이 적었다.** 기본값은 *없다* 이지 *된다* 가 아니라서 **그대로 두는 것이 정직한 선택인 경우가 많은데**, 그 선택지가 안 보이면 다음 사람이 억지로 채운다. 특히 진행률은 지어낼 수 있는 유일한 값이라 기본이 *못 잰다* 다.

    ★**§15.133 의 열림 둘이 다 닫혔다.** 실행 범위(§15.135)와 골격(여기). 리서치에서 세운 열림을 둘 다 지어서 닫았다.

137. **§15.20 이 반만 맞았다 — 경고 넷 중 둘은 다른 플러그인의 것이었다.**

    `protobuf-gradle-plugin` 을 0.9.4 → 0.10.0 으로 올렸다. 먼저 **§15.20 이 적은 것을 그대로 재현했다** — `--warning-mode all` 로 경고 넷이 나오고 전부 *"This will fail with an error in Gradle 10"* 이다: legacy `Usage` 값 둘(`java-api-jars`·`java-runtime-jars`)과 다중 문자열 의존 표기 둘(`protoc`·`protoc-gen-grpc-java`).

    0.10.0 이 **다중 문자열 둘을 없앴다**(0.9.6 의 변경이다). 그런데 **legacy `Usage` 둘은 그대로 남았다.**

    ★★**§15.20 의 진단이 틀렸다.** *"legacy `Usage` 속성과 다중 문자열 의존 표기가 플러그인 내부에서 나오므로 우리가 못 고친다"* 고 한 문장이 **둘을 한 플러그인의 것으로 접었다.** 스택을 떠 보니 `Usage` 쪽은 `org.jetbrains.kotlin.gradle.plugin.AbstractKotlinPlugin$Companion.configureAttributes` 다 — **Kotlin Gradle 플러그인(2.0.21)이다.** 증상이 같고 같은 빌드에서 같이 나와서 한 원인으로 읽혔다.

    ★**먼저 댄 것은 계약 다이제스트다.** 헤더의 `contract_digest` 가 `descriptor.binpb` 의 SHA-256 이라, 플러그인이 디스크립터 바이트를 한 바이트라도 바꾸면 **전 소비자가 `CONTRACT_REVISION_MISMATCH` 경보를 받는다.** 올리기 전에 기준값을 떠 두고(27084 바이트 · `76dc026d…`) 올린 뒤 다시 떴다 — **같다.** 도구 판을 올릴 때 이 저장소가 가장 먼저 확인해야 하는 값이다.

    0.10.0 의 파괴적 변경(`generatedFilesBaseDir` 읽기 전용)은 안 맞는다 — 우리는 `descriptorSetOptions.path` 를 직접 준다. 0.9.5 가 바꾼 기본 출력 폴더도 안 맞는다(하드코딩한 생성 경로가 없다).

    **마감은 실재하되 임박하지 않았다(2026-09-11 확인).** Gradle 10 은 아직 안 나왔고 일정 공지도 없다 — 최신이 9.7.1(2026-08-19)이고 우리가 그것을 쓴다. §15.20 의 *"Gradle 10 이전에 해야 한다"* 가 대장에서 무기한 급한 줄로 읽히던 것을 여기서 끊는다.

    ★**남은 절반은 갈라서 세운다.** KGP 2.0.21 → 2.4.x 는 컴파일러 판이 바뀌는 일이라 protobuf 플러그인 한 줄과 값이 다르다. 한 PR 에 둘을 묶으면 무엇이 무엇을 깼는지 못 가린다.

138. **Kotlin 2.0.21 → 2.4.20 — 경고가 0 이 됐고, 조합이 이미 지원 밖이었다는 것도 알았다.**

    §15.137 이 갈라 세운 절반이다. 올리자 **legacy `Usage` 둘이 사라졌다** — 빌드 전체의 deprecation 이 0 건이다(`--warning-mode all` 전수). Kotlin 경고도 없고, 155 클래스 1,526 시험이 그대로 초록이다.

    ★**올리는 김에 더 나쁜 것을 봤다.** KGP 의 Gradle 호환표에 **2.0.21 이 아예 없다** — 표가 2.1.0 부터 시작하고 그 줄의 Gradle 최대가 8.10 이다. 우리는 Gradle 9.7.1 을 쓰고 있었으므로 **이미 지원 범위 밖에서 돌고 있었다.** 경고 둘은 그 사실의 겉면이었고, 그것만 보고 *"플러그인이 legacy 값을 쓴다"* 로 읽었던 것이다. 2.4.20 의 최대가 9.7.0 이라 9.7.1 은 여전히 한 칸 위지만, 판 하나 차이와 마이너 여섯 차이는 다르다.

    **여기서도 계약 디스크립터를 먼저 댔다**(§15.137 의 규율). Kotlin 은 protoc 을 안 건드리므로 안 변하는 것이 당연한데, *당연한데 안 대는 것* 이 §15.22 가 물린 자리다. 27084 바이트 · `76dc026d…` 로 같다.

    ★**언어 버전을 고정하지 않는다는 사실이 이때 드러난다.** `languageVersion` 을 어디에도 안 적었으므로 **플러그인 판을 올리면 언어 판도 함께 올라간다.** 이번에는 아무것도 안 깨졌지만, 그것은 운이 좋았다는 뜻이지 안전하다는 뜻이 아니다. 고정하지 않기로 **정한 적이 없고 그냥 안 적혀 있었던 것**이라 여기 적어 둔다.

139. **언어 판을 적었다 — 그리고 그것을 확인하는 주입이 처음에 안 돌았다.**

    §15.138 이 *"고정하지 않기로 정한 적이 없고 그냥 안 적혀 있었다"* 고 적어 둔 자리를 닫는다. 먼저 **실측했다** — 빌드에 탐침을 잠깐 넣어 `languageVersion=null`·`apiVersion=null`·`KotlinVersion.DEFAULT=KOTLIN_2_4` 를 떴다. 즉 2.0.21 → 2.4.20 에서 언어 판이 **2.0 → 2.4 로 움직인 것이 수치로 확인됐다.**

    ★**적기만 하면 이번에는 반대로 낡는다.** 플러그인을 올려도 이 줄이 그대로면 새 컴파일러로 옛 언어 판을 쓰게 되고 그것도 아무도 안 본다. 그래서 `KotlinLanguageVersionTest` 가 카탈로그의 `kotlin` 판과 `build.gradle.kts` 의 고정을 **묶는다.** 이 시험이 빨개지는 뜻은 *언어 판이 틀렸다* 가 아니라 ***플러그인을 올렸으니 언어 판을 어떻게 할지 정하라*** 다 — 막는 장치가 아니라 **사람이 봐야 하는 순간에 빨개지는 장치**이고, 도장의 해시가 문서에 대해 하는 일과 같다.

    ★★**결함 주입이 처음에 안 돌았고, 규율이 그것을 잡았다.** 카탈로그를 `2.5.0` 으로 올려 주입했는데 *실패 0* 이 나왔다. 이 저장소의 순서(① 시험이 돌았나 ② 주입이 들어갔나 …)대로 먼저 ①을 봤더니 — **2.5.0 은 존재하지 않아 플러그인 해소 단계에서 빌드가 죽었고, 시험이 아예 안 돌았다.** 내 스크립트는 그때 **낡은 XML** 을 읽어 *실패 0* 을 보고했다. `feedback-verify-the-check-runs` 가 적어 둔 것의 정확한 재현이다.

    방향을 바꿔 **고정을 낮추는 쪽**(`KOTLIN_2_4` → `KOTLIN_2_2`, 플러그인은 그대로)으로 다시 넣으니 잡혔다. 컴파일러는 자기보다 **새로운** 언어 판을 못 받으므로 주입은 이 방향으로만 성립한다 — 옛 판으로 낮추는 것은 되고 새 판으로 올리는 것은 안 된다.

    ★**판정은 종료 코드가 아니라 XML 의 시각으로 했다.** 두 번째 주입에서 결과 파일이 1 초 전 것임을 확인하고서야 *잡혔다* 고 적었다.

140. **저장소 밖에 있던 그림 아홉 중 셋을 들였다 — 들이기 전에 주장부터 댔다.**

    저장소 밖에 이 프로젝트의 그림 아홉이 **더 넓은 폭**으로 그려져 있었고 저장소에는 없었다. **복사가 아니다** — README 열이 837, `docs/*.md` 가 1012 라 그보다 넓은 캔버스를 그대로 넣으면 글자가 바닥(9.5px) 밑으로 내려간다. 그래서 **폭을 인자로 받는 생성기**로 다시 그렸다 — 넓은 판이 다시 필요해지면 같은 파일에서 뽑으면 되고, 손으로 두 벌 두면 어느 날 한쪽만 고쳐진다.

    ★**옮기기 전에 아홉의 주장을 코드에 댔다.** 오늘 `change-plan.svg` 가 사실관계가 틀린 채로 공개 문서에 걸려 있던 것을 봤기 때문이다. 1군 셋은 전부 참이었다 — `RevisionValidator` 가 `gate.GateRunner` 를 실제로 부르고(검사 8 + 음성), `BindingService` 가 `SUPERSEDED` 재활성화를 들고, 상태 열 열 개와 `Resolution` 셋이 계약과 일치한다. `startup` 은 `Main.kt:132` 의 문장과 **글자까지 같았다.**

    ★**한 장은 낡아 있었다.** `three-layers` 가 기체를 **셋만** 그린다 — Orbit 이 생기기 전 판이다. 주장(*"경계는 두 곳뿐이다"*) 자체는 참이고 `consumer.kind = CLIENT | UPSTREAM_SYSTEM` 도 맞지만, 그림이 세는 수가 낡았다. 2군으로 미뤘다. **그리고 그 주장은 오늘 README 에 넣은 `seam.svg` 와 같은 말이라** 둘 다 두면 얼굴에 같은 주장이 두 번 선다 — 자리를 정리하고 옮겨야 한다.

    ★★**다시 그리는 중에 내가 같은 결함을 두 번 냈다.** ① 부제가 *"노란 선은 상위가 부르는 명령"* 이라고 적혀 있는데 **그 선을 안 그렸다.** ② `cross` 배열에 라벨 넷을 정의해 놓고 **한 번도 안 그렸다** — 선이 무엇이었는지가 그림의 절반인데 없었다. 둘 다 렌더를 눈으로 보고서야 잡혔고, 검증기는 못 잡는 종류다(§15.119 가 적어 둔 그 자리다).

    ★**검증기가 잡은 것은 따로 있었다.** 라벨 칩 폭을 `label.length * 10.5` 로 어림했는데 한글과 라틴이 섞이면 한쪽이 반드시 틀린다. 칩이 옆 지대의 칸 위로 넘어갔고 *edge label over node* 가 둘 걸렸다. 거터를 넓히고 글자 종류별로 곱했다.

141. **2군 셋도 옮겼다 — 그리고 둘이 수를 잘못 세고 있었다.**

    §15.140 의 나머지다. 옮기기 전에 주장을 댄 것이 또 값을 했다.

    ★**`three-layers` 가 기체를 셋만 그렸다.** Orbit 이 생기기 전 판이다. 주장(*"경계는 두 곳뿐이다"*)도 `consumer.kind = CLIENT | UPSTREAM_SYSTEM` 도 참인데 **그림이 세는 수가 낡았다.** 넷으로 고치면서 Orbit 은 **로봇이 아니라 플릿 관리자**라고 적었다 — 다른 셋과 같은 칸에 두되 같은 것으로 읽히면 안 된다.

    ★★**`capability-projection` 이 「나머지 넷」이라 적었는데 다섯이다.** 세어 봤다 — 프로파일 최상위가 **열셋**, `Capability` 메시지가 **여덟**(`vendor`·`model`·`profile_revision`·`skills`·`optional_fields`·`protocol_limits`·`publish_interval`·`exclusive_control_required`)이므로 남는 것은 **다섯**이다. 빠진 것은 `derived_from` 이고, 그것은 스키마 첫 커밋부터 있었다 — 즉 그림이 **처음부터 하나를 빠뜨린 채** 수를 적었다. 이 저장소가 산문의 수를 기계로 세게 만든 이유가 그림에서도 같다.

    **자리를 정했다.** `three-layers` 는 `architecture.md` 의 새 §0 으로 갔고, README 히어로는 `seam.svg` 가 그대로 지킨다 — README 첫 문단이 `seam.svg` 의 주장을 그대로 풀어 쓰고 있어서 그림과 글이 붙어 있다. 두 그림은 **다른 질문에 답한다**: 하나는 *무엇이 붙는가*, 하나는 *왜 계약 하나로 붙는가*.

    ★**그러면서 `architecture.md` 안의 낱말 충돌을 하나 끊었다.** 새 §0 이 「경계 셋」을 말하고 §1 이 「층 넷」을 말한다 — 같은 낱말로 다른 것을 센다. 한 줄을 넣어 갈랐다: **§0 은 누가 무엇을 구현하는가(배치), §1 은 어휘가 어디서 갈리는가(ADR 36).**

    `startup` 은 `mimic/README.md` 로 갔다. 여덟 칸 전부에서 같은 곳으로 내려가는 화살표가 *"어느 칸에서 걸려도 결과는 하나"* 를 배치로 말한다.

    ★**검증기가 또 하나 잡았다.** *"하나라도 걸리면 ↓"* 라벨 위로 첫 칸의 화살표가 지나갔다. 라벨을 지우고 그 말을 아래 칸의 제목에 넣었다 — 화살표가 전부 붉고 아래 칸이 붉으면 그 말은 이미 그려져 있다.

142. **전체를 한 눈에 보는 자료가 없었다 — 그림 열한 장이 전부 단면이었다.**

    사용자가 *"전체 아키텍처를 한 눈에 볼 수 있는 자료가 들어가는 곳은 어디인가"* 를 물었고, 세어 보니 **없었다.** 그림 열한 장이 각자 한 주제씩 자른 단면이고 **모듈 열여섯이 서로 어떻게 붙어 있는지를 보여 주는 것이 하나도 없었다.** 가장 가까운 것이 `architecture.md` §4b 인데 그것은 표다.

    ★**손으로 안 그린다.** 각 `build.gradle.kts` 의 출하 의존을 읽어 칸과 간선을 뽑는다. 간선 서른여섯 중 **스무 개는 바닥 둘(`contracts`·`profile-model`)로 가는 것이라 안 그리고 수로 적었다** — 다 그리면 스파게티이고, 바닥이 바닥이라는 사실은 한 줄로 말하는 편이 강하다.

    ★★**그림이 자기가 그린 간선을 주석으로 싣는다.** 그리고 그 주석은 **표시 라벨이 아니라 실제 모듈 이름**으로 나온다 — 기종 어댑터 셋을 한 칸으로 묶은 것은 그림의 사정이고, 주석은 그 칸이 뜻하는 간선 셋을 각각 적는다. 그래서 `ComponentMapTest` 가 **묶음 규칙도 숨김 규칙도 한 벌 안 옮기고** *"빌드에 있는가"* 만 묻는다. §15.115 가 남긴 규율(규칙을 두 벌로 두면 한쪽만 는다)을 그림과 시험 사이에도 적용한 것이다.

    ★★★**첫 판에 구멍이 있었고 결함 주입이 그것을 찾았다.** 처음에는 *"그린 간선이 전부 실재하는가"* 만 봤다. 그래서 **빌드에 의존을 하나 더해도 초록이었다** — 실재하는데 안 그린 것을 아무도 안 봤다. XML 시각을 확인해 시험이 돌았다는 것부터 확인하고(1초 전) 구멍이라 판정했다. 주석이 실제 모듈 이름이라 수로 막을 수 있었다: **바닥으로 가지 않는 출하 간선은 전부 주석에 있어야 한다.**

    **자리는 둘이다.** README 의 「무엇이 있나」 절 머리(디렉터리 나무 위)와 `architecture.md` §4b(표 위). 같은 사실을 그림과 표로 두 번 보여 주는 것이고, 둘 다 같은 빌드 파일에서 나온다.

    ★★**첫 판을 통째로 버렸다 — 사용자 판정 「모두가 평등해 보인다」.** 의존 깊이로 줄을 세우고 같은 크기의 칸을 늘어놓았더니 `contracts` 와 `gate` 가 같아 보였다. **위계도 포함 관계도 없었다.**

    ★**그렇게 만든 것이 내 «스파게티를 피한다» 는 결정이었다.** 바닥 둘로 가는 간선 스무 개를 지우고 수로만 적었는데, **그 스무 개가 바로 위계였다** — `contracts` 가 열다섯 중 열셋에 붙어 있다는 사실이 그 선들이다. 읽기 쉽게 만든다고 읽을 것을 지웠다.

    **다시 그릴 때 바꾼 것 셋.** ① 어휘 둘을 가운데에 **가장 크게** 두고 *몇이 쓰는지* 를 칸 안에 적었다(13 · 7). ② `adapter-host` 가 기종 어댑터 넷과 `adapter-core` 를 **담아서** 그렸다 — 포함이 배치로 보인다. ③ `mimic` 과 `adapter-host` 를 **「같은 자리」로 묶었다.** 이 저장소의 가장 중요한 구조적 대칭인데 첫 판에는 흔적도 없었다.

    ★**검증기가 포함을 겹침으로 읽는다.** 담는 칸을 노드로 그리니 *node overlap* 이 둘 났다. 집 방언에서 **담는 것은 지대(zone)** 이므로 그 스타일로 바꾸자 사라졌다 — 검증기가 틀린 것이 아니라 내가 문법을 안 쓴 것이다.

    ★**받침 선을 그렸다가 지웠다.** 「위아래가 전부 여기 얹힌다」를 괄호 모양 선으로 그렸더니 **빈 상자로 읽혔다.** 캡션 두 줄(↑ · ↓)이 같은 말을 더 잘한다.

143. **사전 조건과 효과를 능력 선언에 붙였다 — 계약 0.9.0, 그리고 «추가 = major» 를 하루 만에 물렸다.**

    비직교 능력(이동 × 적재) 진단(`CAPABILITY_MODEL_ASSESSMENT.md`)의 판정 (나)에 따라 두 자리를 만들었다. **효과는 계약 카탈로그에** — `grasps_object` 옆에 `releases_object` 메시지 옵션 하나, `PickPlaceV1` 만 참. **사전 조건은 프로파일에** — `skills[].preconditions[{subject, requires}]`, v1 의 주어는 `HOLD` 하나이고 `HoldKind` 와 같은 어휘로 비교한다. `SkillDeclaration.preconditions = 8` 로 투영에 들어가며, 평가기 `PreconditionCheck` 는 `Negotiator` 와 같은 이유로 `capability` 에 둔다(미믹과 호스트가 같은 함수를 부른다). 관측 불가는 통과가 아니다. 거절 코드 `PRECONDITION_UNMET` 와 `KEY_PRECONDITION_SUBJECT` 를 예비했다(§15.98 의 자리).

    ★**버전 규칙을 틀리게 적었다가 음성 사례가 잡았다.** 첫 판은 «조건 추가 = major» 였다 — 필수 파라미터 추가와 같은 실패라는 논리였다. 그런데 프로파일의 `major` 는 계약 카탈로그의 major 에 묶여 있어(검사 4번) 프로파일이 혼자 올릴 수 없다. `humanoid-a` 에 조건을 더하는 순간 검사 6번이 «major 를 올려라» 를 요구하고, 올리면 검사 4번이 «카탈로그에 없는 major» 로 막는다. 이 저장소가 능력 철회를 통과시키는 길은 major 가 아니라 **축소 + §9.3 원장 조회**였다(스킬 제거가 그 길이다). 규칙을 `shrink()` 로 바꿨다 — 원장이 없으면 WARNING 과 건너뜀, 소비자가 남았으면 ERROR. 음성 사례 `06-precondition-added-with-consumers` 가 `active_consumers: 2` 로 «축소가 거부됐다» 를 기대한다. 제거는 확장이라 WARNING 으로만 남긴다.

    **실물 넷에는 조건을 하나도 적지 않았다.** 벤더 1차 자료로 «든 채로 이동 불가»가 확인된 기종이 없어 `UNKNOWN` 이고, `UNKNOWN` 은 선언하지 않는 것이다. G1 은 파지를 관측하지 못해 선언하면 영구 거절이다. 참고 프로파일 `humanoid-a` 만 `navigate_to` 에 `HOLD requires EMPTY` 를 든다 — `grip_force` 의 `min_value` 가 그 문서에만 있는 것과 같은 자리다. `ModelProfilesTest` 두 건이 이 둘을 고정한다.

    **적지 않은 것.** 적재 질량·무게중심·자세 조건. 네 어댑터 중 질량을 관측하는 것이 없어 ADR 9 에 걸린다. `PreconditionSubject` 를 열거로 둔 이유가 그것이고, 대장에 §15.143 로 올렸다.

144. **사전 조건이 거절이 됐다 — 남쪽 호출 전에, 엔진에 닿기 전에, 미믹과 호스트가 같은 말로.**

    §15.143 의 선언에 거절 경로를 붙였다. 호스트는 `HostedRobot.start` 에서 파라미터 검증 뒤·`adapter.accept` 전에 `PreconditionCheck` 를 부르고, 관측은 `RobotAdapter.hold()` 다. 미믹은 `TaskHost.start` 에서 `ParameterCheck` 옆에 같은 함수를 부른다. 거절 문장과 `KEY_PRECONDITION_SUBJECT` 참조는 `capability` 가 한 곳에서 만든다 — 각자 문장을 지으면 `HostParityTest` 가 잡는다. 코드는 `PRECONDITION_UNMET` 이고, 벤더가 `PRECONDITION_FAILED` 로 거절한 것(G1 의 `LOCOSTATE_NOT_AVAILABLE`·`ARM_ACTION_ERR_HOLDING`)도 같은 코드로 나간다 — §15.98 이 «계약에 그 자리가 없다»고 적어 두었던 정직 메모가 사전 조건에 한해 닫혔다. 다른 분류의 벤더 거절은 그대로 `INVALID_TRANSITION` 이다.

    ★**패리티 시나리오를 한 번 고쳤다.** 첫 판은 «도는 `pick_place` 뒤에 `navigate_to`» 를 양쪽에 보냈다. 미믹은 그것으로 `HOLDING` 이 되지만 **호스트는 배타 실행이라**(§4.9) 도는 동안의 새 요청은 사전 조건에 닿기도 전에 `INVALID_TRANSITION`(«이미 도는 태스크가 있다») 이다. 호스트 쪽은 «앞 태스크가 든 채로 `FAILED` 로 끝난 뒤» 여야 한다 — 실패는 파지를 바꾸지 않는다. 그래서 가짜 어댑터가 접수 뒤 첫 폴링에서 종착하게 했고, 양쪽이 다른 길로 같은 물리 상태에 이르러 같은 거절을 낸다. 이 차이 자체가 기록할 가치가 있다: 호스트에서 «든 채로 다음 요청»은 언제나 *앞 태스크가 끝난 뒤* 의 이야기다.

    **미믹의 손은 한 쌍이다.** 로봇 수준의 파지는 «어느 태스크든 `HOLDING` 이면 든 채» 로 정했다 — 도는 것도, 복구 실패로 든 채 끝난 것도. 미믹은 못 보는 기종이 아니므로 `NOT_OBSERVABLE` 을 내지 않는다. 관측 불가의 거절(설계안 §3.2)은 호스트 시험이 가짜 어댑터로 본다.

    실물 넷의 거동은 이번에도 바뀌지 않았다 — 조건을 든 프로파일은 참고용뿐이고, 벤더 `PRECONDITION_FAILED` 의 코드 변경은 G1 어댑터 시험이 보는 분류 매핑(§15.128 무렵)을 건드리지 않는다.

145. **미들웨어가 알고도 보내지 않는다 — 계획 시점 사슬 검사, 그리고 통보의 잔여 파지가 "빈손"을 말하게 됐다.**

    설계안의 마지막 조각이다. `Middleware.submit` 이 이 기체에서 **마지막으로 관측한 파지**(`RobotView.lastHold`, `WatchTask` 갱신에서 적는다)에서 출발해 카탈로그 효과(`HoldEffects` — `grasps_object`·`releases_object`)로 단위 사슬을 따라가며 로봇이 선언한 조건과 대조한다. 어긋나면 접수 요청조차 보내지 않는다 — 받아 놓고 돌리면 그 단위가 발신자에서 `PRECONDITION_UNMET` 으로 거절되기까지 앞 단위들이 물리적으로 움직인다. **권위는 발신자다**: 관측이 없거나 관측 불가면 미리 재단하지 않고, 능력을 못 물은 로봇도 같다. 이 검사가 `picasso → capability` 간선을 만들었다(구성도 재생성, 간선 하나).

    ★**시험이 규칙 하나를 가르쳐 줬다.** «적재를 잃은 실패는 세 축에 다 적힌다» 를 처음 쓸 때 셀 장치를 `perfect()` 로 두었더니 `blockedBy` 가 비었다. 미들웨어가 다음 단위에 가지 않았기 때문이다 — **하류는 잃었다는데 셀엔 증거가 있으면** 그 슬롯을 운영자 판단(`OPERATOR_HOLD`)으로 세운다(관측의 충돌, 결함이 아니다). 잃은 적재는 슬롯에 없어야 맞으므로 시험에서 증거를 뺐다. 그리고 둘째 축은 조건부다: `blockedBy` 는 기체가 `can_accept_new_task=false` 라 한 결함만 들므로, `PAYLOAD_LOST` 를 그렇게 선언하는 것이 프로파일의 몫이다(픽스처 `precondition.json` 이 그렇게 한다; `minimal` 은 아니다). 설계안 §4 가 이 둘을 빠뜨렸고 정정했다. 어댑터 골격 README 「적재를 잃었을 때」에 규약 셋을 적었다.

    **통보를 하나 고쳤다.** `notify()` 의 `residualHold` 는 든 단위가 없으면 기본값을 실어 «빈손» 이 «말하지 않았다» 로 접혔다. 이제 든 단위가 없으면 마지막으로 관측한 파지를 싣는다.

    결함 주입: 사슬 검사 제거 · 관측 기록 제거 · 효과 계산을 «그대로» 로 고정 · `residualHold` 보정 되돌림 — 넷이 각각 시험을 빨갛게 했다.

153. **자원 소유 대장을 문서로 올렸다 — 그리고 빈 칸이 셋 드러났다.**

    «무엇을 누가 승인하는가» 가 코드에는 있고 문서에는 없었다. `submit` 이 `chainViolation` 과 `liveHold` 로 배정을 막고, `AmrFleetPort.dispatch` 의 `null` 이 이송을 막고, `CellSignals.observe` 의 `null` 이 «말이 없다» 를 말하는데, **자원을 열거하고 그 관문을 적은 표가 없었다.** 표가 없으면 빠진 자원을 아무도 못 찾는다 — 빈 칸이 사고가 나는 자리인데 빈 칸을 볼 자리가 없다. `docs/orchestration.md` 가 그 표이고, 관문 칸의 기호는 `DocumentClaimsTest` 가 코드와 댄다. 이름을 바꾼 사람은 그 문서를 안 보므로.

    **표를 그리자마자 셋이 나왔다.** 셀 내 점유 축이 없어 재할당이 구조적으로 불가능한 것(§15.153), 걷는 기체의 통로에 소유자 자체가 없는 것(§15.154), 자리 결속의 기준 지도 버전을 아무도 안 보는 것(§15.155). 앞의 둘은 이 층이 이미 아는 사실이었는데 대장에 행이 없었다 — **문서로 올리는 일이 곧 그것을 찾는 일이었다.**

    그리고 **「없음」과 「구멍」을 갈랐다.** 소유자가 있고 관문만 없는 것은 만들면 닫히고, 소유자가 없는 것은 먼저 소유를 정해야 한다. 접으면 «만들면 되겠네» 로 읽혀 통로 문제가 개발 항목으로 오해된다. 같은 김에 README 가 손으로 적어 둔 미결 항목 수가 낡아 있는 것(37 대 40)을 찾아 세는 시험을 붙였다.

154. **셀을 벗어나면 바닥에 주인이 없다.**

    플릿은 자기 AMR 만 승인하므로, 걷는 기체가 셀 밖 통로를 쓸 때 이 층이 내는 명령은 **아무 관문도 통과하지 않는다.** 선택지는 둘뿐이다 — 소유자를 만들거나, 그 자원을 쓰지 않거나. 무승인 통행을 허용하는 셋째는 없으므로 소유자가 정해지기 전까지의 올바른 상태는 **그 구역으로 명령을 내지 않는 것**이다. 소유자 신설이 시스템 신설은 아니다 — 구간을 잘라 기존 소유자에게 주면 시스템이 하나도 안 늘고 소유자가 생긴다. 절차와 승격 조건은 아직 안 적었고 §15.154 로 등록한다.

156. **자리 이름이 어느 판에서 배운 것인지 묻기 시작했다 — 그리고 좌표는 끝내 안 들였다.**

    §15.155 가 연 구멍을 지도 판 축부터 닫는다. 계약 카탈로그의 `is_site_reference` 가 어느 칸이 자리 이름인지 말하고(`SiteReferences`), 결속 정본이 그 이름을 어느 판에서 배웠는지 말하며, 호스트가 **어댑터가 이름을 풀기 전에** 둘을 맞댄다. 뒤에 두면 이미 옛 좌표로 움직인 뒤에 판을 보게 된다.

    **좌표 칸을 두지 않았다.** 설계 문서는 결속 표에 «식별자 또는 좌표+프레임» 을 적었는데, 이 저장소에는 좌표·프레임 개념이 하나도 없고(계약의 `location` 은 `is_site_reference` 인 문자열이고 카탈로그가 *"좌표가 아니다"* 라고 적어 뒀다) 미믹에 기하가 없어 **좌표 결속을 틀리게 만들 방법이 없다.** 항상 통과하는 시험은 아무것도 보증하지 않는다. 판 이름은 다르다 — 맞대 볼 토큰이라 어긋나게 만들 수 있고, 그래서 시뮬레이션에서 진짜로 검증된다. ADR 34 와 충돌하지 않는다는 것도 함께 확인했다: §2 의 금지는 **어댑터가 보유하는 것**이고 §4 결론이 상위 사이트 레지스트리를 소유자로 명시 허용한다.

    **답을 셋으로 갈랐다.** 옛 판에서 배웠다(`PRECONDITION_UNMET` — 재등록을 기다린다) · 정본이 모르는 이름이다(`PARAMETER_INVALID` — 등록해야 한다) · 정본에 못 물어봤다(gRPC `UNAVAILABLE` — 요청의 잘못이 아니다). 운영자가 할 일이 셋 다 다르므로 접으면 무엇을 해야 할지 알 수 없다. **새 계약 코드는 만들지 않았다** — 늘리면 내부 판정 규칙이 계약으로 샌다.

    **정본을 안 붙인 배치는 안 막는다.** `ActiveMap.NotConfigured` 가 「검사할 정본이 없다」이고 `Unavailable` 이 「못 물어봤다」이며, 뒤엣것만 멈춘다. 둘을 접으면 정본 없는 현장이 통째로 서고 그러면 이 검사가 곧 꺼진다 — `SiteNames` 가 `Unsupported` 와 `Unavailable` 을 가른 것과 같은 규율이다.

    **검사 9번이 결속을 어댑터 경계 안에 가둔다.** 탐색어는 결속 파일이 선언한 최상위 이름에서 유도하므로 타입을 더하면 금지도 저절로 는다. 훑는 모듈에 `registry` 와 `profile-model` 을 넣었다 — 검사 7의 목록에 그 둘이 없어서, 없다는 이유로 결속까지 새면 같은 구멍이 두 번째로 열린다.

    (의도적 제외로 옮김, §15.265) «§15.156» 행은 picasso 와 picasso-ops 어디에도 그 값을 쓰는 면이 없어 의도적 제외의 새 표 «수요 없음» 으로 옮겼다.

308. **의존 정비 S2 — `Execution` 을 `Middleware` 밖 최상위 클래스로 옮긴다.**

    의존·구조 정비 S2 작업이다. S0 소비자 검증(PR #12)과 S1 타입 이사(PR #13, §15.307) 다음 단계다. 동작 변경은 없다. 기존 `Execution` 은 `Middleware` 의 `inner class` 였다. 바깥 `Middleware` 를 붙잡는 곳이 `assignedAt = now()`, `command = CommandLifecycle(this@Middleware, this)`, `noteLate` 안의 `stateTime(update)` 세 곳이었다. 이 때문에 실행 상태가 조율자 클래스 안에 갇혀 있었고 파일 그래프의 큰 성분이 `Middleware` 를 지나야 했다.

    새 파일 `Execution.kt` 의 최상위 `class Execution internal constructor(...)` 로 본문을 옮기고 세 줄만 고쳤다. `assignedAt` 은 생성자 인자로 받고 `Middleware.create` 가 `now()` 를 넘기며 호출 횟수와 시점은 같다. `command` 는 생성자의 팩터리 `commandOf: (Execution) -> CommandLifecycle` 로 짓는다. `create` 가 `{ CommandLifecycle(this, it) }` 를 넘기며, 팩터리는 저장하지 않고 생성 중 한 번 부르며 지을 때 실행의 멤버를 읽지 않는다. `noteLate(unit, update, at: () -> Instant)` 는 시각을 게으르게 받아 호출부 `UnitDrivers.kt` 가 `{ stateTime(it) }` 를 넘기며 중복 지연 이벤트는 전처럼 시계를 읽지 않는다.

    `Middleware` 안에 `typealias Execution = dev.picasso.middleware.Execution` 을 두었다. mission-ops 의 `Middleware.Execution` 16곳과 이 저장소 시험의 `Middleware.Execution` 은 고치지 않고 컴파일된다. 시험의 이름을 그대로 둔 것은 별칭을 컴파일로 지키기 위해서다. main 소스 8개 파일의 `import dev.picasso.middleware.Middleware.Execution` 을 지우고 KDoc 링크는 `[Execution.x]` 로 갱신했다. 생성자는 `internal` 로 닫았다. `CommandLifecycle` 이 internal 이고 표 밖에서 만든 실행은 쓸 곳이 없다. 밖에서 `Execution` 을 만드는 곳은 picasso 시험과 mission-ops 에 없었다. 공개 API 표면을 좁히는 유일한 변경이다.

    ★**자문 두 라운드를 거쳐 `Execution` 결합을 팩터리 방식으로 풀기로 결정했다.** 생성자에 `Middleware` 를 넘기는 안, `lateinit` 으로 꽂는 안, 명령 생애를 실행 밖 표로 빼는 안을 버렸다. 각각 이름 의존이 남고, 가변 필드가 늘어 미초기화 구간이 생기며, S4 범위라는 문제가 있었다. 읽기 계약(`ExecutionView`)은 S2 에 넣지 않고 S3 에서 결정 ②(공개 세터 닫기)와 함께 정한다. 지금 쓸 곳이 없고 `AdmissionGate` 는 공개 12 멤버 밖의 `active` 를 읽는다.

    바이너리에서 JVM 클래스가 `Middleware$Execution` 에서 `Execution` 으로 바뀌고 공개 서명도 바뀐다. 소스는 호환된다. 이 저장소에 출하(maven-publish) 설정이 없고 mission-ops 는 소스로 컴파일한다. 다른 바이너리 소비자가 없다는 점은 확인하지 못했으나, 이 PR 의 병합은 기존 바이너리 재사용을 요구하지 않는다는 소유자 결정이다. `SourceSizeBudgetTest` 는 `Execution.kt` 에서 가변 필드와 컬렉션을 센다(13·10 유지). `MIDDLEWARE` 817 → 765, `TOTAL` 1,786 → 1,728 은 실측치 반영이며 코드가 줄어든 것이 아니다. 상한 넷을 1씩 낮춘 결함 주입이 모두 실패로 잡혔다. 시험 1,831건 실패 0, 번들 동치 12/12 이고, mission-ops(`fcb1a30`)를 이 변경으로 다시 빌드해 842 시험(site 41, mission-host 226, ops-service 498, e2e 77) 실패 0 이었다. `:mission-host:compileKotlin` 이 다시 돌아 별칭으로 컴파일됨을 확인했다. `Execution → CommandLifecycle → Middleware` 는 남아 파일 그래프의 큰 성분은 S4 에서 다룬다.

307. **의존 정비 S1 — 낮은 층 타입을 제자리로 옮겨 포트·모델 순환을 없앤다.**

    R0~R5 뒤 의존 방향을 검토했다(Codex·Fable 자문 두 라운드). 계획은 S0 소비자 검증(PR #12) → S1 타입 이사 → S2 `Execution` 최상위 → S3 공개 세터·가변 컬렉션 폐쇄 → 층 시험 → S4 내부 핵심과 좁은 생애 환경, 호스트 부품 모듈 분리 순이다. 이 항목은 S1 이다(번호 306 은 PR #11 이 썼다). 동작 변경, 공개 이름·패키지 변경, 시험 수 변경은 없다. 파일 사이 이름 기반 참조(주석·문자열·private 제외) 측정 결과, 기존 6개 파일(`Model`, `Occupancy`, `Ports`, `LogicalCapability`, `PrefetchingFleetPort`, `PrefetchingRobotPort`)과 21개 파일 성분 중 6개 파일 성분이 완전히 해소되었다. 21개 파일 성분은 19개 파일(`CancelAxes`·`Canonical` 제외)로 줄었다. 기존 순환 원인은 `Model`의 `ExecutionUnit`이 `Occupancy`의 관측 값을 쓰고, `Occupancy`가 `Ports`의 `SlotSignal`을 쓰며, 포트 인터페이스 반환 타입(`PrefetchedRead`·`FleetRead`)이 구현 파일에 있던 점이다.

    선언 본문은 그대로 둔 채 같은 패키지 안에서 타입을 옮겼다. `PrefetchedRead`와 `FleetRead`는 `Ports.kt`의 각 포트 인터페이스 옆으로 옮겼다. `SourceObservation`·`SourceUnobserved`·`DestinationObservation`은 `Model.kt`로 옮겼다. 이 이사가 순환을 끊었다. `SourceCheck`·`DestinationCheck`는 `Occupancy.kt`에 남겼다. 포트 계약 파일에 섞여 있던 gRPC 어댑터 `ClientRobotPort`는 새 `ClientRobotPort.kt`로 분리했다. 승인·판단 기록·결과 내보내기가 번들 내보내기 파일에 기대던 문제는 `Obj`와 `Json`을 새 `Json.kt`로 분리해 해소했다. 구체 업무 `PrepareSequencedRack`·`DeliverContainer`·`InspectAsset`는 새 `CodeCapabilities.kt`로 옮겨 `LogicalCapability.kt`에는 계약만 남겼다. mission-ops가 쓰는 공개 companion 멤버 `MissionCatalog.codeCapabilities()`는 같은 패키지라 고치지 않고 컴파일된다. 이로 인해 남은 «포트 → 구체 업무» 간선 하나는 조립용 예외이며, 이 분리가 아닌 관측 값 이사로 순환을 끊었다.

    어댑터 시험과 mission-ops가 작업 지시를 구성할 때 쓰는 입력 어휘인 `EquipmentUse` 상수는 구체 업무 파일 대신 `Model.kt`의 `EquipmentRequirement` 옆에 두었다. `CancelAxes`가 행위 파일 `LifecycleCancel.kt`에 기대고 있던 비행 기록 다섯(`CancelFlight`·`FleetCancelFlight`·`InFlight`·`FleetFlight`·`StartRestore`)은 모두 `Execution`의 필드 타입이므로 새 `Flights.kt`로 옮겼다. 상수 `UNCLASSIFIED`와 `DUPLICATE_UNIT_ID`의 값은 하위 파일의 홀더(`Canonical.kt`의 `FailureClassNames`, `UnitFacts.kt`의 `RefusalReasons`)로 내렸다. `Middleware`의 공개 상수는 이름을 유지하고 해당 값을 가리키도록 연결했다. 같은 이름의 최상위 상수는 companion 안에서 자기 자신을 참조하게 되므로 두지 않았다.

    ★**`Middleware.Submission`은 옮기지 않고 공개 결과 계약으로 인정한다.** 하위 파일(`AdmissionGate`·`RemedyDesk`·`UnitFacts`)이 `Submission.Rejected`를 직접 만들고, 공개 `Admission.Refused.rejection`과 공개 `Unassigned : Middleware.Submission`이 이 타입을 쓴다. 내부 거부 값을 따로 두고 창구에서 바꾸는 안은 버렸다. `Rejected`의 필드 7개 중 6개가 기본값이라 변환에서 하나를 빠뜨려도 컴파일되고, 공개 타입 쪽 간선은 어차피 남기 때문이다. `typealias`로는 `Middleware.Submission.Rejected` 같은 중첩 경로를 이어 줄 수 없음을 컴파일로 확인했다. S3 뒤 층 시험에서는 하위 파일이 `Middleware.kt`에서 쓸 수 있는 이름을 `Submission`과 그 하위로만 허용한다(S2 전에는 `Execution`도 허용).

    19개 파일 성분에 남은 원인은 `Execution`이 `Middleware` 안 inner class인 점(S2)과 생애·드라이버가 `Middleware`의 포트·시계·트레이스·통보를 부르는 점(S4)이다. 생애 파일들의 companion 상수 import 약 45줄은 해당 성분 내부라 옮기지 않았다. `./gradlew cleanTest build -x :harness:test -x :registry:test` 실행 결과 시험 XML 실패 0건이다. mission-ops(`fcb1a30`)를 이 변경으로 다시 빌드했다. picasso 미들웨어를 쓰는 mission-host·site·e2e 344 시험은 실패 0 이다. ops-service 498 은 앞 두 번의 실행에서 서로 다른 시험 하나씩이 시험용 DB 의 연결 실패로 떨어졌고, 세 번째 실행에서 실패 0 이었다. ops-service 는 picasso 미들웨어를 컴파일하지 않는다. 바뀐 시험은 한 곳이다. `DocumentClaimsTest` 가 그림의 실패 분류 이름을 찾을 때 `LogicalCapability.kt` 와 `CodeCapabilities.kt` 를 함께 읽는다.
306. **R0 이 표시한 의심 동작 다섯((a)~(e), §15.300)을 고친다.**

    - **(b) 이미 끝난 태스크라는 거절도 기체가 그 태스크를 안다는 답이다.** 키로 지은 핸들의 취소에 기체가 `INVALID_TRANSITION` 으로 답하면, `CANCEL_UNSUPPORTED` 처럼 그 핸들로 붙어 끝까지 따라가 단위를 그 종료로 판정한다(`KNOWN_TASK_REFUSALS`). 답을 받았으니 취소는 닿았다. «닿지 않음» 을 걷고, 취소 응답의 사유는 그 거절 코드다. 앞에서는 붙지 않아 미확정 유예 끝에 운영자 보류로 갔고, 취소 응답은 `CANCEL_NOT_SENT` 를 실었다. 끊긴 채 개정 갱신을 잃은 조합 시험에서는 기체가 옛 리비전으로 점검을 마쳤다. 그래서 이제 지연 이벤트로 새 판의 기대에 대고 설비가 확인하고(확인 못 하면 `UNVERIFIED`), 실행은 «그 뒤에서 멈춤» 으로 끝난다. 기체가 그 태스크를 모른다는 쪽은 그대로다. 그것은 기체가 쉰다는 증거가 아니다(§15.219).
    - **(a) 핸들이 있어도 취소가 닿지 않은 채면 보류에 묶는다.** 미확정 유예가 끝나 단위를 운영자 보류에 둘 때, 앞에서는 핸들이 없을 때만 취소를 보류에 묶었다. 이제는 핸들로 보낸 취소가 닿지 않은 채일 때도 묶는다. 그래야 실행이 중단으로 닫히지 않고 운영자 판단을 기다린다(§15.243 과 같은 까닭). 새 시험 «…링크가 돌아와도 닿지 않으면 단위와 실행을 운영자 보류에 둔다» 가 지킨다.
    - **(c) 미확정 단위의 끊김도 링크가 돌아오면 걷는다.** 끊김 추적은 `RobotSync.kt` 의 `trackLink` 로 옮겼다(라운드 안의 자리는 그대로). 같은 자리에서 **(c′)** 를 함께 고쳤다. 링크가 돌아올 때 취소가 걸려 있으면 실행 상태를 `RUNNING` 이 아니라 `CANCELING` 으로 되돌린다. 앞에서는 걸린 취소가 있는데도 상위에 `RUNNING` 을 알렸다.
    - **(d) 붙기 전 취소의 핸들은 기체가 든 리비전으로 짓는다.** 스냅숏의 태스크 리비전을 쓰고, 없을 때만 단위의 리비전을 쓴다.
    - **(e) 취소가 걸리면 붙기 전 단위를 개정이 바꾸지 않는다.** 붙기 전 단위는 기체에서 돌고 있으므로 §15.256 의 도는 단위와 같은 규칙을 탄다.

    ★**조합 시험의 단언을 고친 동작으로 바꿨다.** `LifecycleCombinationTest` 에 (a) 시험 하나를 더했다(2,448 → 2,449). 고침을 하나씩 되돌리면 각자의 조합 시험이 이름으로 빨개진다. (c) 를 끈 첫 주입은 스마트 캐스트를 깨 컴파일이 실패했다. 그때 XML 은 앞 실행의 것이었다. 컴파일되는 변이로 다시 넣어 확인했다. 나머지 picasso 시험은 예산 둘(이 PR 에서 고침)을 빼고 그대로 초록이었다. 의심 동작 다섯은 어느 기존 시험도 고정하고 있지 않았다.

    ★**`TOTAL` 을 올린다.** 고침이 접합부에 17줄을 더해 합이 1,786 이 됐다. 같은 고침이 `Middleware.kt` 를 843 에서 817 로, `pumpRound` 를 173 줄에서 143 줄로 줄였다. 그래서 `MIDDLEWARE` 와 `PUMP_ROUND` 는 내렸다. 접합부에 둔 것은 상태 없는 절차라 CLAUDE.md §5 의 자리다. 소유자 승인 대상이다.

305. **재설계 R5 — `Execution` 과 실행 표는 `Middleware` 안에 두고, 상태 없는 절차를 밖으로 옮겨 실코드를 843 으로 내린다(한계 대장 «§15.298»).**

    ★**`Execution` 을 빼지 않기로 했다.** 계획서(§15.299)는 «빼지 않기로 하면 그 까닭을 적는다» 고 했다. 까닭은 다른 저장소다. `mission-ops` 의 실행 호스트(`MissionHost.kt`·`MockRunner.kt`)가 `includeBuild` 로 이 저장소를 물고 출하 코드 열여섯 곳(두 파일)에서 `Middleware.Execution` 을 타입으로 쓴다. 이 저장소의 시험도 일흔한 곳에서 그 이름을 잡는다. 빼면 그 저장소의 빌드가 깨진다. 이 저장소는 다른 저장소 파일을 고치지 않는다(CLAUDE.md §5). 그러니 옮기려면 `handoff/` 로 넘기고 받는 쪽이 정해야 한다. 상태의 주인이 하나인 근원은 R3 이 생애 상태를 `CommandLifecycle` 로 옮겨 이미 덜었다. 실행 표를 밖의 저장소로 빼는 것은 상태의 모양을 바꾸지 않고 이름만 옮긴다.

    대신 `Middleware` 에 있을 까닭이 없는 것을 옮겼다. 기체 동기화(`sync`·`record`·`forgetAcknowledged`, 88줄)는 새 접합부 `RobotSync.kt` 로 갔다. 결함 열쇠 `faultKey` 는 순수 함수라 그 파일의 최상위로 갔다. 취소 응답을 짓는 `abort` 는 취소 생애(`CommandLifecycle`, `LifecycleCancel.kt`)로 갔다. 응답이 취소 의도 축(`CancelIntent.Reported`)을 쓰기 때문이다. 순수 함수 일곱(`evidenceRefusal` `duplicateUnitIdRefusal` `annotate` `annotateOnce` `canonicalClass` `downstreamDetail` `isTerminal`)은 `UnitFacts.kt` 의 최상위로 갔다. `Middleware.kt` 실코드는 1,005 에서 843 이 되어 목표 900 아래다. `MIDDLEWARE` 상한은 1,080 에서 843 으로, `TOTAL` 은 1,829 에서 1,769 로, `OPEN_MEMBERS` 는 30 에서 22 로 내렸다. 셋 다 1 낮추는 주입에서 빨개졌다.

    ★**남은 `internal` 22 는 저마다 까닭이 있다.** 포트와 시계는 접합부·생애·드라이버가 하위를 부르는 길이다(`robots` `cell` `fleet` `now`). 공유 상태와 부품은 실행을 가로지른다(`executions` `views` `droppedOptionals` `desk` `gate` `issuing` `incidentLog`). 라운드 값은 `private` 인 현장 값을 열지 않으려고 함수로 연 것이다(`windowOf` `inDoubtGraceOf` `stallWindowOf`). 기록 창구는 통보 열(`outbox`)과 주의 칸을 `private` 로 두려고 연 것이다(`trail` `markIncident` `notify` `noteAttention` `noteClockSkew`). 관측과 시계 읽기도 있다(`observeCell` `observeHold` `stateTime`). 멤버마다의 부르는 파일은 `docs/superpowers/plans/2026-11-12-middleware-r0-state-map.md` §10 에 있다. 더 닫으려면 포트·시계·기록 창구를 든 문맥 객체를 접합부에 넘기는 모양이 필요하다. 그것은 재설계 다음의 일이다.

304. **재설계 R4 — 경로마다 다른 일을 `RouteDriver` 의 구현으로 모은다(한계 대장 «§15.298»).**

    `RouteDrivers.kt` 의 봉인 타입 `RouteDriver`(`Robot`·`Fleet`·`Signal`)가 명령 생애의 경로별 연산을 든다. 출발, 보내는 중인 조회 받기, 조회 앞의 해소(스냅숏 부착, 리비전 앞섬), 조회의 관문과 송신, 라운드 진행, 붙기 전 취소, 조회 종류, 키 취소 여부, 갱신 수용이다. `resolveDoubt`·`startUnit`·`holdUnsentCancel`·`resendHeldCancel` 과 `pumpRound`·`notify`·`revise` 는 `RouteDriver.of(unit.route)` 로 드라이버를 얻어 부른다. 생애 네 파일과 `CommandLifecycle.kt` 에는 경로 `when` 갈래가 없다. 새 시험 «명령 생애 파일에 경로별 when 갈래가 없다» 가 그것을 지키고, 갈래 하나를 넣으면 빨개진다(주입으로 확인). `lookupOf` 는 `RouteDriver.lookupKind` 가 됐다.

    ★**닿지 않는 갈래를 찾았다.** `lookupDownstream` 의 로봇·플릿 갈래(로봇은 `StartTask` 를 같은 참조로 바로 부르고, 플릿은 `dispatch` 를 바로 부른다)는 부르는 두 자리(`startUnit`·`resolveDoubt`)가 그 경로를 먼저 돌려보내므로 한 번도 안 불렸다. 설비 대기 갈래만 살아 있었고 늘 참이었다. 죽은 갈래와 함께 그 함수를 지웠다. 설비 대기의 출발은 곧 `RUNNING` 이고 조회는 `lookupFound(unit, true)` 다. 동작은 같다.

    ★**짝은 없어지지 않고 구현이 됐다.** 로봇·플릿 짝(`settleIssue`/`settleDispatch`, `cancelRobot`/`cancelFleet`, `pollRobotCancel`/`pollFleetCancel`, `attachForCancel`/`attachTransportForCancel`, `pumpRobotUnit`/`pumpFleetUnit`)은 두 계약이 달라 하나로 합치지 않았다. 로봇은 갱신과 키 취소가 있고, 플릿은 운반 핸들과 조회 없는 플릿이 있다. 계획서가 말한 «짝 0» 은 부르는 자리에서 짝을 고르는 갈래가 0 이라는 뜻으로 읽었다. 이제 넷째 경로는 `RouteDriver` 구현 하나로 붙는다. 경로가 정해진 함수 안의 거르기 셋(`it.route == Route.FLEET` 따위)은 남았다.

    `TOTAL` 은 1,880 에서 실측 1,829 로 내렸다. 시험은 하나 늘었다(2,447 → 2,448).

303. **재설계 R3 — 명령 생애를 실행마다의 객체 `CommandLifecycle` 로 옮긴다(한계 대장 «§15.298»).**

    명령 생애 넷(`LifecycleIssue` `LifecycleDoubt` `LifecycleCancelAttach` `LifecycleCancel`)의 `fun Middleware.x(execution, …)` 43개를 `fun CommandLifecycle.x(…)` 로 바꿨다. `CommandLifecycle` 은 실행마다 하나이고(`execution.command`) 제 상태를 든다. 취소의 네 축(§15.301)과 송신 기록 셋(보내는 중인 출발·맡기기, 미룬 갱신)이다. 확장은 포트와 자취·통보를 `with(mw)` 로 부른다. 실행을 읽지 않는 포트 보조 여덟(`transportOrderOf` `issueStart` `issueDispatch` `handleFor` `lookupOf` `unansweredAttach` `findNow` `statusNow`)은 새 접합부 `CommandPorts.kt` 로 갔다. 생애 넷은 접합부 목록을 떠났다. 거기에 `fun Middleware.` 가 다시 서면 `SeamPlacementTest` 가 빨개진다(주입으로 확인). 라운드 안의 순서는 `pumpRound` 에 그대로 있다(계획서 원칙 5).

    `EXECUTION_VARS` 는 20 에서 13 으로 내려 계획서 목표에 닿았다. `Middleware.kt` 와 접합부의 실코드 합은 2,650(2차 분할 실측)에서 1,840 이 되어 `TOTAL` 을 2,780 에서 1,880 으로 내렸다. 새 상한 `OPEN_MEMBERS = 30` 이 `Middleware` 의 `internal` 멤버를 센다. 생애 쪽에서만 쓰던 셋 가운데 `endedOnRobot` 은 순수 함수라 `CommandPorts.kt` 의 최상위로 옮겨 닫았다. `inDoubtGraceOf` 는 `Middleware` 의 라운드 값을, `droppedOptionals` 는 기체를 가로지르는 표시를 들어 남겼다.

    ★**변환은 기계로 했다.** 본문은 `with(mw)` 로 감쌌고 한 줄도 고치지 않았다. 부르는 자리의 `x(execution, …)` 를 `execution.command.x(…)` 로 바꿨다. 남는 위험은 이름 해석이다. `with(mw)` 안에서는 `Middleware` 의 멤버가 `CommandLifecycle` 의 것을 가린다. 지금 두 쪽에 겹치는 속성 이름은 없다. `Middleware.execution(id)` 는 함수라 속성 `execution` 을 가리지 않는다. 시험 소스는 접합부 목록과 예산 말고는 고치지 않았다. 시험이 읽던 `inFlight`·`fleetInFlight`·`deferredUpdate` 는 실행의 읽기 속성으로 남겼다. 세 상한을 1 씩 낮추면 각자의 예산 시험이 빨개진다. 시험은 하나 늘었다(2,446 → 2,447).

302. **재설계 R2 — «한 번만 알림» 아홉을 열쇠 하나로 붙는 장부로 모은다(한계 대장 «§15.298»).**

    `Execution` 에서 같은 사실을 한 번만 내려고 든 표시 아홉을 `Notices.kt` 의 `Noticed` 하나로 모았다. `var` 다섯(`notedDelay` `notedHold` `notedDoubt` `notedBlock` `lastNotified`)과 컬렉션 넷(`pumpFailures` `clockSkewNoted` `signalSeen` `seenLate`)이다. 자리마다 `Notice` 의 열쇠가 하나씩 있고, `note(열쇠, 값)` 이 앞과 같은 값이면 거짓을 돌려준다. 새 알림은 열쇠 하나를 더해 붙는다. 필드는 늘지 않는다. `EXECUTION_VARS` 는 25 에서 20 으로, `EXECUTION_COLLECTIONS` 는 14 에서 10 으로 내렸다.

    계획서(§15.299)의 R2 목표는 «17 이하» 였다. R1 이 23 이 아니라 25 에서 끝났고(§15.301), `notedCancelUnsent`·`notedUnattached` 는 R1 이 취소 축의 데이터로 가져갔다. 그래서 R2 가 덜어 낼 `var` 는 다섯이었고 20 에서 멈췄다. `lastNotified` 는 작업 응답 전체를 들고 있었지만 읽는 자리는 `noteAttention` 하나였고, 거기서도 주의 세 칸(보류·미확정인 실행 상태, 막는 결함, 미확정 단위)만 봤다. 그래서 응답 대신 그 세 칸을 `Notice.Attention` 에 둔다.

    ★**지연 이벤트의 중복 막기를 지키는 시험이 없다.** `note` 가 늘 참을 돌려주게 하면 `PumpSafetyTest`·`ClockSkewTest`·`PumpIsolationTest`·`EquipmentWaitTest` 의 열이 빨개진다. 같은 지연 이벤트를 두 번 적는 쪽(`Notice.Late`)은 아무 시험도 안 잡는다. `LateEventTest` 셋이 초록이었다. 고치지 않고 적는다. 상한을 1 씩 낮추면 «실행의 가변 필드와 라운드 함수가 구조 예산 안에 있다()» 가 `var`·컬렉션 각각으로 빨개진다. 시험 소스는 예산 상수 말고는 고치지 않았다.

301. **재설계 R1 — 취소 필드 열하나를 네 축의 봉인 타입으로 가른다(한계 대장 «§15.298»).**

    `Execution` 의 취소 필드 열하나를 `CancelAxes.kt` 의 네 타입으로 바꿨다. 계획서(§15.299)의 출발안은 의도와 진행 두 축이었다. 상태 지도(§15.300)를 따라 함께 서는 조합을 세어 보니 넷이어야 했다.

    - **의도**(`CancelIntent`: 없음, 요청, 거절, 응답 냄)가 `cancelRequested`·`cancelRefusal`·`lastCancel` 을 대신한다. 셋은 함께 바뀌지 않는다. 거부는 요청 뒤에만 오고, 취소 응답은 실행이 중단으로 닫힐 때 한 번만 선다. 그 뒤에는 라운드가 돌지 않는다.
    - **닿음**(`CancelDelivery`: 이상 없음, 닿지 않음)이 `cancelUnsent`·`notedCancelUnsent`·`unreachedCancelSince` 를 대신한다. 까닭과 끊김 시각은 «닿지 않음» 의 데이터다.
    - **기다림**(`CancelWait`: 없음, 붙기 전 대기, 보류에 묶임)이 `doubtCancelHold`·`unattachedCancelSince`·`notedUnattached` 를 대신한다.
    - **보내는 중**(`CancelSending`: 없음, 로봇, 플릿, 둘 다)이 `cancelInFlight`·`fleetCancelInFlight` 를 대신한다.

    ★**두 축으로는 안 된다.** 보내는 중은 닿지 않음과 함께 선다. 로봇·플릿에 함께 보낸 취소의 한쪽이 던지고 다른 쪽이 아직 나가는 중일 때다. 보류 중 다시 보내기(`HELD`)는 보류에 묶인 채 나가고, 붙기 전 취소(`ATTACH`)는 붙기 전 대기와 함께 나간다. 닿음과 기다림도 함께 선다. 보류에 묶이는 순간 취소는 닿지 않은 채이고, 보류 중 다시 보낸 취소가 답을 받으면 보류는 남고 닿음만 걷힌다. 배타인 값끼리만 한 타입에 넣으면 넷이다. 그래서 `EXECUTION_VARS` 는 32 에서 25 로 내렸고 목표 23 에는 둘이 모자란다. 넷을 한 타입의 곱으로 펼치면 숫자는 맞지만 전이 표가 곱만큼 커진다.

    ★**숨은 조합 둘을 찾았다.** 옛 코드는 `doubtCancelHold` 를 걷지 않았다. 운영자가 판단한 뒤에도 남아, 다른 단위의 붙기 전 대기와 함께 섰다. 새 타입에서는 붙기 전 대기가 시작되면 보류 표시가 대기로 바뀐다. 남은 표시는 그 단위가 다시 운영자 보류일 때만 무엇을 막는데, 취소가 걸린 실행은 새 단위를 내지 않는다. 그래서 그 단위는 다시 보류가 되지 않는다. 둘째로, 끊김 시각(`unreachedCancelSince`)은 닿은 뒤에도 다음 판정까지 남았다. 새 타입은 닿는 순간 걷는다. 다음 판정은 같은 라운드에 오므로 그 사이에 다시 닿지 않음이 설 수 없다. 둘 다 시험으로 가를 수 있는 차이가 아니라 추론으로 적는다.

    시험 소스는 고치지 않았다. 시험이 읽던 이름(`cancelUnsent`·`cancelInFlight`·`cancelRequested` 같은 것)은 네 축에서 계산하는 읽기 속성으로 남겼다. `LifecycleCancel.kt`·`LifecycleCancelAttach.kt` 의 취소 분기는 `CancelDelivery`·`CancelWait` 의 `when` 이다. 결함 주입은 둘이다. 상한을 24 로 낮추면 «실행의 가변 필드와 라운드 함수가 구조 예산 안에 있다()» 가 빨개진다. 끊김 시각을 저장하지 않게 하면 `InDoubtTest` «끊긴 기체에 건 취소가 유예 안에 닿지 않으면…» 이 빨개진다. 조합 시험(§15.300)은 그대로 초록이다.

300. **재설계 R0 — `Execution`·`ExecutionUnit` 의 상태 지도, 생애 조합의 특성화 시험, 두 구조 예산을 둔다(한계 대장 «§15.298»).**

    계획서 `docs/superpowers/plans/2026-11-12-middleware-r0-state-map.md` 가 셋을 적는다. 동작은 바꾸지 않았다.

    **상태 지도.** 필드 선언을 고유한 이름으로 바꿔 컴파일하면 쓰임마다 미해결 참조가 난다. 그 자리를 함수 단위의 쓰기·읽기로 접고 grep 과 맞대 손으로 봤다. `Execution` 47(`var` 32, 가변 컬렉션 14, 가변 인자 1)과 `ExecutionUnit` 의 `var` 36 이 전부 표에 든다. 계획서(§15.299)가 적은 «가변 컬렉션 11» 과 «`var` 약 30» 은 덜 센 값이었다. 실측은 14 와 36 이다(본문의 `var` 여섯이 빠졌다). `Middleware.kt` 가 `Execution` 필드 30 을 쓰고, 생애 접합부 넷이 19 를 쓴다.

    **특성화 시험.** `LifecycleCombinationTest` 다섯이 취소 × 미확정 × 링크 끊김 × 재기동 × 개정 가운데 기존 시험이 안 덮던 조합을 덮는다(재기동 × 개정 둘, 미확정 × 재기동 × 개정, 미확정 × 끊김 × 개정, 다섯 축 전부). 덮던 조합은 계획서 §3.1 에 시험 이름을 댔다. ★**지금 동작을 그대로 적었다.** 틀려 보이는 것 다섯을 표시만 했다. (a) 끊긴 채 개정 갱신을 잃은 단위에 취소가 걸리면 유예 뒤 단위는 운영자 보류인데 실행은 `ABORTED` 로 닫힌다. (b) 링크가 돌아온 뒤 기체에 닿아 거절된 키 취소를 `CANCEL_NOT_SENT` 로 적는다. (c) 링크가 돌아와도 단위가 `IN_DOUBT` 이면 `linkBroken` 이 안 걷힌다. (d) 다시 지은 실행에 붙기 전 개정이 오면 붙기 전 단위의 리비전이 기체보다 앞선다. (e) 취소를 먼저 걸어도 (d) 와 같다. 고치는 것은 별도 fix 다. 재설계 PR 은 이 시험을 바꾸지 않고 지나야 한다.

    **구조 예산.** `SourceSizeBudgetTest` 가 `Execution` 의 가변 컬렉션(`EXECUTION_COLLECTIONS = 14`)과 `ExecutionUnit` 의 가변 필드(`UNIT_VARS = 36`)를 센다. `var` 만 세면 생애 상태가 맵·집합이나 단위 쪽으로 옮겨 가도 숫자가 준다. 첫 판의 컬렉션 정규식은 게터 `robots` 를 세어 15 를 냈다. 게터를 빼고 14 에 맞췄다. 두 상한을 1 씩 낮추면 XML 에서 두 시험이 이름으로 빨개진다. `Revision.kt` 에 (e) 를 고친 모양을 넣으면 조합 시험 하나가 빨개진다.

299. **명령 생애 재설계의 설계서와 PR 순서를 정한다(한계 대장 «§15.298»).**

    2차 분할(§15.298)은 파일 정리라 성장의 근원을 고치지 않았다. 설계서 `docs/superpowers/plans/2026-11-12-middleware-lifecycle-redesign.md` 가 근원 넷(명령 생애가 플래그 조합인 것, 상태의 주인이 하나인 것, 로봇·플릿 경로의 중복, 송신 기록이 도메인 상태 옆에 있는 것)을 PR 여섯(R0~R5)으로 다룬다. 목표일은 11-13 부터 12-08 까지이며, 앞 PR 의 병합을 전제한다.

    ★**PR 마다 예산 숫자 하나를 내린다.** 각 단계는 `SourceSizeBudgetTest` 의 상한을 실측으로 내린다. 내리지 못한 PR 은 재설계 PR 이 아니다. 완료 기준은 줄 수가 아니라 상태의 모양이다. `Execution` 의 `var` 약 12개, 플릿 쪽 짝 0, 분할이 연 `internal` 재봉인이 그것이다. 성공 여부는 그 뒤의 fix PR 이 필드를 늘리지 않고 전이 표의 한 칸을 고쳐 끝나는지로 판단한다.

    설계서를 쓰다 2차 분할의 예산에서 빈틈을 하나 찾았다. 예산은 `Execution` 의 `var` 만 센다. 그런데 `Execution` 에는 가변 컬렉션 11개가 따로 있고 `ExecutionUnit` 에도 `var` 가 약 30개 있다. 두 수의 상한은 R0 에서 더한다.

298. **Middleware.kt 를 4,338줄에서 2,107줄로 다시 나누고, 실코드 예산 시험으로 재비대를 빌드에서 보이게 한다.**

    9/30 분할(§15.197~200, 2,149 → 1,532줄) 뒤 막는 장치가 없어 첫 부모 커밋 58개를 거치며 4,338줄(KDoc 1,229줄)이 되었다. `git blame -w -M` 으로 9/30 병합의 조상이 아닌 커밋이 쓴 줄을 세면 2,896줄이다. 붙은 것은 구동의 하위 절차다. 재개와 판단 기록, 리비전 개정, 명령 발행, 미확정 해소, 취소용 붙이기, 취소 송신과 폴링, 시계 차가 그렇다.

    옮긴 코드는 제 상태가 없고 `Middleware` 의 실행 표·포트·시계·트레이스를 그대로 쓴다. 그래서 클래스로 떼지 않고 같은 패키지의 최상위 확장(`fun Middleware.name(`)으로 아홉 파일에 옮겼다. 본문은 들여쓰기만 바뀌고 부르는 쪽은 그대로다. 같은 파일 안에서만 불리는 23개는 파일 `private`, 나머지는 `internal` 이다. 옮긴 코드가 쓰는 `Middleware` 의 멤버 37개를 `private` 에서 `internal` 로 열었다. 1단계 일곱 파일(`Resume`·`Revision`·`LifecycleIssue`·`LifecycleDoubt`·`LifecycleCancelAttach`·`LifecycleCancel`·`ClockSkew`)과 2단계 두 파일(`UnitDrivers`·`EvidenceBinding`)이다. 라운드 순서(`pump`·`pumpRound`·`abort`), 관측(`sync`·`record`), 통보(`notify`·`noteAttention`), 상태, 공개 창구, 조율은 남겼다. 공개 함수의 본문을 위임 한 줄로 바꾸는 3단계는 하지 않았다. KDoc 과 본문이 다른 파일로 갈리고 줄 수만 맞추기 때문이다.

    ★**이것은 모듈화가 아니라 파일 정리다.** 함수 155개가 실행의 가변 필드 32개를 함께 쓰는 것은 그대로다. 명령 생애 네 파일(`Lifecycle*`)은 서로를 양방향으로 부르는 하위 상태기계 하나다. 성장의 근원은 셋이다. 명령 하나의 생애가 명시적 상태 없이 실행의 널·불리언 조합으로 짜여 있다(9/30 18개 → 32개, 취소 관련 11개, «한 번만 알림» 표시 7개). 그래서 fix PR 29개가 957줄을 순증했다. 상태의 주인이 `Middleware` 하나라 실행을 만지는 코드는 갈 곳이 여기뿐이다. 로봇과 플릿 두 경로가 같은 생애를 따로 구현한다(플릿 쪽 짝 9개). 재설계는 한계 대장 내부 오픈 항목 «§15.298» 이 다룬다.

    재비대 방지. `SourceSizeBudgetTest` 가 실코드 줄(빈 줄과 `/**`·`*`·`//` 로 시작하는 줄을 뺀 것)을 센다. 상한은 `Middleware.kt` 1,080(실측 1,036), 접합부 파일 각 500(최대 241), 둘의 합 2,780(2,650 + 5%), 그 밖의 출하 파일 600(최대 291)이다. 구조 예산은 실행의 가변 필드 32와 `pumpRound` 180줄(실측 179)이다. 상한을 올리려면 그 상수와 이 일지의 항목을 같은 PR 에 두고 소유자 승인을 받는다. `SeamPlacementTest` 에는 확장 89개의 자리와 음성 바늘을 더했다. 확장은 같은 서명의 멤버에 조용히 지고 이 빌드는 경고를 실패로 안 보기 때문이다. 접합부 목록은 시험 소스의 `MiddlewareSeams` 한 벌이다.

    검증. 표준 빌드는 실패 0 이다. 인계본 네 벌과의 번들 동치는 12개 파일이 같다. 다만 번들은 `run-1..4` 가 밟는 경로만 보고 옮긴 코드의 다수는 드문 경로다. 그래서 순서를 보는 이동 대조를 더했다. 빈 줄을 빼고 정규화한 옛 `Middleware.kt` 를 연속 덩어리로 잘라, 옮긴 2,108줄과 남은 줄이 원래 순서 그대로 맞는지 본다. 허용하는 차이는 선언 줄의 가시성 한정자와 수신자 `Middleware.` 뿐이다. 결함 주입: 예산 상한을 실측보다 1 낮추기, 가변 필드 상한 31, `Middleware.kt` 에 같은 이름의 멤버 두기를 해서 각 시험이 이름을 대며 빨개지는 것을 봤다.

    다음 blame 은 `git blame -C -C` 로 센다. `-M` 은 파일 사이의 이동을 안 쫓아, 옮긴 줄이 전부 이 변경의 새 줄로 잡힌다.

297. **adapter-host 세션 id 끝에 프로세스 기동 난수를 붙여 세션 겹침을 해소한다.**

    두 발신자(에뮬레이터, `adapter-host`)의 이벤트 세션 id는 기동 시각(밀리초)과 프로세스 카운터로 만든다. 같은 밀리초에 다시 뜨거나 시계가 되감기면 앞 세션이 되풀이되고, 새 프로세스의 번호가 옛 커서를 지났으면 미들웨어가 재시작을 못 봐 낡은 커서가 새 세션의 앞 이벤트를 건너뛴다(한계 레지스터 행 §15.21). 배포 가능성 점검(새 공장 PoC 시나리오)에서 현장에 올라가는 쪽은 `adapter-host`라 그쪽이 실제 위험으로 드러났다. 소유자가 내부 문제를 차례로 모두 해소하라고 지시했다.

    `adapter-host`의 `HostedRobot` 세션 id 끝에 프로세스마다 한 번 `SecureRandom`으로 뽑는 64비트 난수(16자리 16진수)를 붙였다. 형식은 `host-<기동 밀리초 13자리>-<카운터 6자리>-<난수 16자리>`다. 다시 뜬 프로세스는 다른 값을 뽑으므로 같은 밀리초 재기동과 시계 되감기에도 앞 세션과 겹치지 않는다. 한 프로세스 안에서는 그 값이 같아 주입한 시계를 고정한 시험은 여전히 기동 시각과 카운터로 세션을 가른다.

    에뮬레이터는 가상 시계로 띄웠을 때의 결정성(§12.1)을 지키려고 난수를 붙이지 않았다. 그래서 행 §15.21은 에뮬레이터만 남기고 좁힌다. 에뮬레이터는 현장에 올라가지 않는다. 세션 id를 읽는 쪽은 그 값을 불투명한 문자열로 비교하므로 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았고 오픈 항목은 50개 그대로다.

    `adapter-host` · `AdapterHostTest`에 `세션은 프로세스 기동 난수를 들어 같은 밀리초에 다시 띄워도 앞 세션과 겹치지 않는다` 1건을 더했다. 전체 2,435건에서 2,436건이다. 세션 id에서 난수를 뺀 결함 주입을 그 시험이 잡았다.

296. **보존 상한에 실행 표와 이빅션한 작업 지시 표시를 더하고 오래된 항목을 이빅션한다.**

    실행 표(`executions`)에서 실행을 빼는 곳은 `Middleware.forget` 하나뿐이고, 담는 쪽이 부르지 않으면 실행과 그 통보(아웃박스)가 인스턴스 수명 동안 남는다. 이빅션한 작업 지시의 표시(`forgotten`, 같은 작업 지시 id 재전송을 거부하는 표시)는 지우는 곳이 없어 작업 지시마다 하나씩 늘었다. 항목 294의 보존 상한(`Retention`)은 네 목록만 덮었고 한계 레지스터 행 §15.166 이 이 표시를 상한 밖으로 적었다. 배포 가능성 점검(새 공장 PoC 시나리오)에서 오래 도는 인스턴스의 메모리가 끝없이 느는 자리로 드러났다. 소유자가 내부 문제를 차례로 모두 해소하라고 지시했다.

    `Retention` 에 `executions`(기본 10,000)와 `forgottenOrders`(기본 100,000)를 더했다. `pump` 가 라운드 끝에 실행 표가 상한을 넘으면 오래된 실행부터 `forget` 과 같은 규칙으로 이빅션한다. 구동이 아직 돌리거나, 보내는 중인 취소가 있거나, 상위가 확인하지 않은 통보가 있는 실행은 `forget` 이 거부하므로 남고, 그만큼 상한을 넘을 수 있다. 이빅션한 작업 지시 표시는 넣은 순서를 지키고 상한을 넘으면 오래된 것부터 내린다. 내린 수는 `RetentionEvictions` 의 `executions`·`forgottenOrders` 로 센다(담는 쪽이 직접 부른 `forget` 은 실행 수에 세지 않는다).

    표시를 내린 작업 지시 id 는 이 계층이 더는 거부하지 못하고, 그 재전송은 다시 뜬 인스턴스의 경우와 같이 담는 쪽의 기록이 막는다. 아웃박스는 실행과 함께 빠지므로 따로 상한을 두지 않았고, 상위가 끝내 확인하지 않는 통보가 있는 실행은 남는다(`pending` 으로 보인다). 영속화가 없다는 점은 그대로라 행 §15.166 은 좁히고 남긴다. 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았고 오픈 항목은 50개 그대로다.

    `picasso` · `ForgetTest` 에 `실행 표가 상한을 넘으면 끝나고 확인된 실행부터 이빅션한다`, `확인 안 된 통보가 있는 실행은 상한을 넘어도 이빅션하지 않는다`, `이빅션한 작업 지시 표시가 상한을 넘으면 오래된 것부터 내린다` 3건을 더했다. 전체 2,432건에서 2,435건이다. 라운드 끝 정리 호출 제거, `forget` 규칙을 건너뛰는 제거, 표시 상한 제거의 결함 주입 셋을 각각 해당 시험이 잡았다.

295. **인시던트 봉인과 선택 파라미터 판정의 선언 조회를 감싸고 포트 KDoc에 의무를 적는다.**

    인시던트 봉인(`IncidentLog.sealIncidents`)은 봉인할 단위마다 번들을 바로 목록에 붙이고, 단위 표시(`pendingIncidents`)는 끝에서 비운다. 번들의 `profileRevision`을 채우려고 단위마다 `RobotPort.capabilities`를 부르는데, 그 KDoc은 널이 못 물어봤다는 뜻이라고만 적고 던지지 말라고는 적지 않았다. 미들웨어는 포트를 미리 읽는 포트(`PrefetchingRobotPort`)로 저절로 감싸지 않으므로, 배치가 넘긴 포트가 던지면 앞 단위의 번들만 들어가고 표시가 안 비어 다음 봉인이 같은 인시던트를 또 연다. 리팩터링 중 드러난 기존 문제였고 한계 레지스터 행은 없었다. 소유자가 내부 문제를 차례로 모두 해소하라고 지시했다.

    봉인이 선언 조회를 `runCatching`으로 감싸 던지면 널과 같은 0으로 적는다. 같은 문제가 선택 파라미터 판정(`withOptionals`)에도 있었다. 그것은 출발 요청을 만드는 도중에 선언을 읽어, 던지면 그 예외가 나갔는지 모르는 출발로 읽혀 나가지도 않은 명령이 미확정으로 적혔다. 그 자리도 감싸 던지면 못 물어본 것으로 읽고 선택 파라미터를 붙이지 않은 채 보낸다. `RobotPort.capabilities`의 KDoc에 못 물어봤으면 던지지 말고 널을 주라는 의무를 적었다.

    접수·재할당·개정의 배정 판정과 승인 판정에서 부르는 선언 조회는 감싸지 않았다. 그 판정들은 상태를 바꾸기 전에 돌아서, 던지면 그 호출이 실패로 드러나고 반쯤 된 상태가 남지 않는다. 함께 본 다른 기존 문제 하나, `RemedyDesk.record`가 보류한 제안의 거부를 새로 만드는 자리는 정보를 잃지 않음을 확인했다. 조치 열을 든 거부를 만드는 곳이 `AdmissionGate` 한 곳이고 거기서는 사유와 조치 열만 채우기 때문이다. 그래서 고치지 않았다. 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았고 한계 레지스터의 오픈 항목은 50개 그대로다.

    `picasso` · `IncidentBundleTest`에 `선언 조회가 던져도 번들은 봉해지고 다음 라운드에 겹치지 않는다` 1건, `OptionalParameterTest`에 `선언 조회가 던져도 못 물어본 것으로 읽고 출발을 보낸다` 1건을 더했다. 전체 2,430건에서 2,432건이다. 두 감싸기를 각각 지운 결함 주입을 해당 시험이 잡았다.

294. **미들웨어 네 목록에 보존 상한을 두어 한계 레지스터 내부 행 §15.166을 좁힌다.**

    미들웨어가 메모리에 드는 네 목록, 인시던트 번들(`IncidentLog`), 탐색 기록(`RemedySearchRecord`), 서 있는 제안, 승인 소모 기록(ADR 46)에 상한이 없어 오래 도는 인스턴스에서 끝없이 늘었다. 한계 레지스터 내부 행 §15.166이 영속화와 상한이 둘 다 없다고 적었고, 해소 조건은 적재의 소유자가 정해지는 것이었다. 소유자가 내부 오픈 항목을 차례로 모두 해소하라고 지시했다.

    새 `Retention`(기본값 인시던트 번들 2,000, 탐색 기록 10,000, 제안 10,000, 소모 기록 10,000, 모두 양수여야 함)을 `Middleware` 생성자 인자 `retention`으로 받는다. 넘치면 가장 오래된 것부터 내린다. 아직 구동이 돌리는 실행(`PhysicalState.pumps`)의 인시던트 번들과 소모 기록은 내리지 않아, 상한은 그만큼 넘을 수 있다. 내린 제안의 가림 표시와 진단도 같이 내린다. 내린 제안을 승인하면 `NO_PROPOSAL`로 거부되므로 승인 없이 실행되는 길은 생기지 않는다. 내린 기록이 하나라도 있으면 `NO_PROPOSAL`의 사유가 '이 프로세스가 뜬 뒤로 선 적이 없다' 대신 '선 적이 없거나 보존 상한으로 내린 기록이다'가 된다. 내린 수는 `Middleware.retentionEvictions()`가 `RetentionEvictions`로 센다.

    영속화는 여전히 없다. 네 목록은 프로세스 수명과 같고, 상한은 메모리를 묶는 것이지 보존 규약이 아니다. 오래 둘 기록은 담는 쪽이 내보내기(§6)로 가져가며, 적재의 소유자가 정해지면 보존 기간은 그쪽 규약이 정한다. 놓은 작업 지시의 표시(`forgotten`)는 같은 작업 지시 id의 재전송을 거부하는 데 쓰여 작업 지시마다 하나씩 남고 상한 밖이다. 그래서 행 §15.166은 닫지 않고 좁힌다(오픈 항목 수 50개 그대로). 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았다.

    시험은 `picasso` · 새 `RetentionTest` 7건(번들이 상한을 넘으면 구동이 끝난 실행의 것부터 내린다, 구동이 돌리는 실행의 번들은 상한을 넘어도 남긴다, 제안이 상한을 넘으면 가장 오래 선 것을 내리고 그 승인은 제안이 없다고 거부한다, 탐색 기록은 상한을 넘으면 오래된 것부터 내린다, 소모 기록이 상한을 넘으면 오래된 것을 내리고 다시 온 승인은 제안이 없다고 받는다, 구동이 돌리는 실행의 소모 기록은 상한을 넘어도 남긴다, 상한이 양수가 아니면 만들지 않는다)이다. 전체 2,423건에서 2,430건이다. 결함 주입 10건을 각각 해당 시험이 잡았다.

293. **게이트 검사 6번이 원장의 관측 불가를 거부하게 바꾸어 한계 레지스터 내부 행 §15.14를 닫는다.**

    게이트 검사 6번(`Check06Vocabulary`)은 프로파일 축소(스킬 제거 등)를 만나면 의존 원장(`LedgerQuery`)에 설계 §9.3의 두 조회(active 소비자 수, 비종료 태스크 수)를 한다. 둘 다 0으로 관측되면 승인하고, 하나라도 남으면 `Severity.ERROR`로 거부한다. 그런데 원장이 붙어 있으면서 관측하지 못했다(`LedgerAnswer.NotObservable`, 예: 브로커 구독이 끊겨 관측선이 신선하지 않음)고 답하면 경고 하나만 남기고 통과했다. 경고는 종료 코드를 바꾸지 않으므로 레지스트리의 개정 검증(`RevisionValidator`)이 그 축소 개정을 검증됨으로 받았다. 원장 쪽 `RegistryLedgerQuery`의 KDoc은 창이 짧아 생기는 오답 "모른다"가 축소를 막는다고 적고 이를 근거로 짧은 창을 골랐는데, 검사 6번이 그 "모른다"를 막지 않아 서로 어긋났다. 항목 195가 한계 레지스터 내부 행 §15.14를 이 내용으로 다시 적었다. 소유자가 내부 오픈 항목을 차례로 모두 해소하라고 지시했다.

    원장이 붙어 있는데 두 조회 중 하나라도 관측하지 못했다고 답하면 검사 6번이 `Severity.ERROR`로 "축소가 거부됐다 … 원장이 관측하지 못했다: <사유>"를 낸다. 조회를 끝내지 못했다는 사실은 그대로 부분 건너뜀(`REGISTRY`)으로도 남는다. 레지스트리에서는 그 개정이 DRAFT에 머물고 사유에 그 문장이 남는다. 원장이 아예 없는 자리(CI, 설계 §11.1)는 전처럼 경고와 부분 건너뜀이다. 그것은 조회를 하지 않은 것이지 조회가 모른다고 답한 것이 아니고, CI에는 원장이 없어 거기서 거부하면 모든 축소가 막힌다. 아무 기체도 그 스킬을 제공하지 않고 원장 표가 한 번도 채워지지 않은 새 레지스트리에서는 전역 워터마크가 없어 답이 "모른다"이므로, 라인이 돌아 협상이나 태스크가 한 번 적재될 때까지 축소가 거부된다. 이것은 안전한 쪽으로 틀리는 것이다.

    남는 것은 없다. 행 §15.14를 닫고 레지스터에서 지운다. 오픈 항목은 51개에서 50개(내부 21개에서 20개)다. 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았다.

    `gate` · `Check06VocabularyTest`의 `관측 불가를 0으로 읽지 않는다`가 이제 실패와 ERROR 소견을 요구하고, `원장이 없으면 거부하지 않고 건너뜀으로 남긴다` 1건을 더했다. `registry` · `GateLedgerWiringTest`에 `원장이 모른다고 답하면 축소 개정판이 거부된다` 1건을 더했다. 전체 2,421건에서 2,423건이다. 결함 주입(ERROR를 WARNING으로 되돌림)을 두 시험이 잡았다.

292. **Orbit 배치 런처에 `OrbitCli`와 주기 발견을 더해 한계 레지스터 내부 행 §15.106 · CLI를 닫는다.**

    옛 항목 106은 Orbit 배치 런처(`OrbitLauncher`)를 두면서 두 가지를 남겼다. `main` 함수와 인자 파싱이 없고, mimic의 `MimicCli`가 하는 인자 검증, 기동 거부 메시지, 프로세스 유지가 없어 CLI가 없다. 또 `sweep()`이 기동 때 한 번 돌고, 뒤에 플릿에 기체가 늘어도 프로세스가 몰라 발견이 한 번이다. 한계 레지스터 내부 행 §15.106 · CLI가 이 둘을 적었다. 소유자가 내부 오픈 항목을 차례로 모두 해소하라고 지시했다.

    `adapter-boston-dynamics-orbit`에 `OrbitCli`와 `main`을 두고 모듈에 `application` 플러그인을 더했다. 필수 인자는 `--orbit-url`·`--orbit-token`·`--site`·`--instance`·`--profile`이고, 선택은 `--port`·`--pump-ms`·`--rediscover-seconds`·`--registry`·`--ingest-token`·`--fallback-dir`이다. 모르는 인자, 같은 인자 두 번, 값 없음, 정수가 아닌 값, 필수 인자 누락은 사용법과 함께 종료 코드 2다. `--registry`를 주면 `--ingest-token`이 있어야 한다(mimic과 같은 규칙). 레지스트리를 주면 관측은 `RegistryLink.http`로, 발견은 `HttpRobotDiscovery`로 그 적재 문에 올라간다. 런처가 기동을 거부하면 사유를 적고 종료 코드 1이다. 기동하면 서버가 내려갈 때까지 프로세스를 살려 두고(`AdapterHost.awaitTermination`을 더함), 내려갈 때 `OFFLINE`을 남기고 적재 큐를 비운다. `OrbitLauncher.Options.rediscoverMillis`가 0보다 크면 그 주기마다 데몬 스레드가 `Running.rediscover()`를 부른다. 다시 발견은 기동 때와 같은 적재 문으로 발견을 올리고, 플릿에 새로 나타난 기체만 같은 프로파일로 세워 호스트에 더한다. 0이면 전처럼 기동 때 한 번이다. 다시 발견이 플릿에 못 물으면 실패로 내고 이미 든 기체는 그대로 둔다. `AdapterHost.robots`가 동시 맵이 되었고 `admit(robot)`을 더했다. 이미 든 `robot_id`면 거짓을 낸다. 생존 보고 내용은 호스트 잠금 밖에서 읽어 두고, 맵에 넣기와 `WatchTask` 미는 쪽 매달기(`HostTaskService.attach`)와 `ONLINE`은 같은 잠금 아래에서 한다. 맵에 들어가 RPC가 지목할 수 있게 된 뒤에 살아 있다고 알린다(§10.2의 순서). 기동 앞이면 맵에만 넣고 기동이 알린다.

    플릿에서 빠진 기체는 내리지 않는다. 계약에 기체가 사라졌다고 말하는 자리가 없고, 그 기체의 RPC는 플릿의 답을 그대로 따른다. 기체를 퇴역으로 적는 것은 레지스트리의 조작이다. 발견된 기체 전부에 같은 프로파일을 적용하는 것은 별도 외부 행 §15.106 · 기종으로 그대로 남는다. 행 §15.106 · CLI를 닫고 레지스터에서 지운다. 오픈 항목은 52개에서 51개(내부 22개에서 21개)다. 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았다.

    `adapter-boston-dynamics-orbit` · `OrbitLauncherTest` 2건(다시 발견이 플릿에 새로 나타난 기체를 세워 실 포트의 계약 뒤에 더한다, 다시 발견이 플릿에 못 물으면 실패로 내고 이미 든 기체는 그대로 둔다), 새 `OrbitCliTest` 4건(인자가 맞으면 실 포트를 열고 발견한 기체를 적는다, 필수 인자가 없거나 값이 틀리면 기동하지 않는다, 레지스트리를 주면서 적재 토큰이 없으면 기동하지 않는다, 플릿에 기체가 없으면 기동을 거부하고 사유를 적는다), `adapter-host` · `AdapterHostTest` 1건(기동 뒤에 들인 기체도 ONLINE을 알리고 WatchTask 스트림에 갱신을 민다)을 더해 전체 2,414건에서 2,421건이다. 결함 주입 6건(미는 쪽 매달기 생략, 들인 기체의 ONLINE 생략, 중복 검사 둘을 함께 제거, 다시 발견 실패를 삼킴, 적재 토큰 검사 생략, 주기 인자 무시)을 각각 해당 시험이 잡았다. 중복 검사 하나만 지운 주입은 다른 하나가 같은 일을 해 등가 변이였다.

291. **`contractIdentity` 태스크가 `picasso/` 파일만 이름 순으로 다이제스트를 굽도록 바꾸어 행 §15.22를 닫는다.**

    옛 항목 22와 한계 레지스터 내부 행 §15.22는 헤더의 `contract_digest`가 `buf` 모듈 다이제스트가 아니라 디스크립터 셋(`descriptor.binpb`)의 SHA-256이라고 적었고, 해소 조건을 `buf` 모듈 다이제스트 생성 파이프라인과의 통합으로 적었다. 항목 22는 그 셈법의 결함 하나를 적어 두었다. `includeImports = true`가 `google/protobuf/descriptor.proto`를 끌고 오므로 protobuf나 플러그인 버전을 올리면 계약이 그대로여도 다이제스트가 바뀐다. 다이제스트만 달라도 헤더 대조가 `Divergent`로 나와 경보가 간다. 소유자가 내부 오픈 항목을 차례로 모두 해소하라고 지시했다. 해소 조건을 다시 쟀다. `buf` 모듈 다이제스트는 .proto 원문 바이트를 세므로 주석 한 줄에도 바뀐다. 이 저장소는 주석만 고친 커밋에 다이제스트가 안 바뀌게 하려고 `includeSourceInfo = false`를 두었으므로 그 조건은 이 결정을 되돌린다. 또 `buf`는 Docker 래퍼라 mimic이 Docker 없이 기동하지 못하게 된다(항목 22가 처음 적은 이유). 그래서 `buf`로 옮기지 않고, 항목 22가 적은 결함만 없앤다.

    `contracts/build.gradle.kts`의 `contractIdentity` 태스크가 디스크립터 셋을 원소(필드 1, `FileDescriptorProto`)로 가르고, 이름이 `picasso/`로 시작하는 파일만 이름 순으로 다시 이어 붙인 바이트의 SHA-256을 다이제스트로 굽는다. 끌려 들어온 `google/` 파일은 세지 않는다. 이어 붙인 바이트열도 그대로 `FileDescriptorSet`이다. 이름 순으로 고정하는 것은 protoc이 내는 순서가 버전마다 같다는 보장이 없어서다. 런타임 리소스 `picasso.desc`와 게이트가 읽는 디스크립터는 그대로 셋 전체다. 새 의존은 없다(빌드 스크립트가 varint를 직접 읽는다). `ContractIdentity`의 KDoc을 새 셈법으로 고쳤다. 다이제스트 값이 한 번 바뀐다(`dba283e6…`에서 `627eaa4c…`로). 이 저장소의 발신자와 소비자는 모두 같은 `ContractIdentity`를 읽으므로 같은 빌드끼리는 같다. 바뀌기 전 빌드와 섞어 띄우면 한 번 `Divergent` 경보가 간다.

    protoc이 `picasso/` 파일 자체의 기술자를 다르게 쓰면 다이제스트가 바뀐다. 그것은 계약의 기술자가 바뀐 것이라 신원이 바뀌는 것이 맞다. 행 §15.22를 닫고 레지스터에서 지운다. 오픈 항목은 53개에서 52개(내부 23개에서 22개)다. 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았다.

    `contracts` · `ContractIdentityTest`에 1건(다이제스트는 끌려 들어온 google 파일을 빼고 picasso 파일만 이름 순으로 센다. 함께 구운 `picasso.desc`를 원소로 갈라 셈법을 다시 대고, 셋 통째의 SHA-256과도 달라야 한다)을 더해 전체 2,413건에서 2,414건이다. 결함 주입 1건(빌드 스크립트의 `picasso/` 거르기를 뺌)을 이 시험이 잡았다.

290. **`registry`에 `MqttObservationSubscriber`를 두어 기체 스트림 헤더를 `recordHeader`로 관측 적재하고 행 §15.34를 닫는다.**

    옛 항목 34와 한계 레지스터 내부 행 §15.34는 관측 적재(`ObservationService`, 헤더 하나를 세대 이력 `capability_epoch_log`에, 핸드셰이크 거부 하나를 `handshake_rejection`에)를 부르는 MQTT 구독기가 없다고 적었다. 브로커를 붙이면 §12.1 결정성이 깨진다는 것이 이유였고, 해소 조건은 브로커 구독기가 `recordHeader`/`recordRejection`을 부르는 것이었다. 소유자가 내부 오픈 항목을 차례로 모두 해소하라고 지시하여 전제를 다시 쟀다. 발행 쪽은 이미 브로커에 붙어 있다. `uplink`의 `MqttPublisher`가 운영 배포와 브로커 통합 시험에서 쓰이고, `harness`의 `MqttBrokerTest`가 Mosquitto 컨테이너(`eclipse-mosquitto:2`)로 돈다. 결정성은 시험이 in-process 경로를 쓰는 것으로 지켜지고 있었다. 핸드셰이크 거부는 MQTT 스트림 셋(`state`·`event`·`connection`)에 실리지 않고 HTTP 적재(`HandshakeIngestService`)가 부르므로 `recordRejection`은 이미 운영 경로가 있었다. 운영 경로가 없던 것은 `recordHeader` 하나였다.

    `registry`에 `MqttObservationSubscriber`(`registry/observe`)를 두었다. 이 계층이 읽는 메이저(`ContractIdentity.major`)의 기체 스트림 전부(`picasso/{major}/+/robot/+/+`)를 QoS 1로 구독하고, 스트림마다 메시지(`StateMessage`·`Event`·`ConnectionMessage`)를 읽어 헤더를 `recordHeader`에 적는다. 사유는 기체가 말한 것만 옮긴다: `event` 스트림의 `CapabilityChanged`가 싣는 사유 넷이 `EpochCause` 넷과 하나씩 맞고, 나머지는 `BINDING_CHANGED`다. 읽지 못한 것은 적지 않고 `dropped`로만 센다(토픽이 기체 스트림 꼴이 아님, 다른 메이저, 페이로드가 그 스트림의 메시지가 아님, 헤더 없음, 헤더의 `robot_id`가 토픽의 것과 다름, 적재 실패). 넷째 것을 적으면 세대 이력이 다른 기체의 줄이 된다. 적재 실패(등록되지 않은 기체는 세대 이력의 FK에 걸린다)도 예외를 밖으로 내지 않고 센다. 예외가 콜백 밖으로 새면 Paho가 연결을 끊고 구독이 조용히 멈추기 때문이다. 끊기면 자동으로 다시 붙고 구독을 다시 건다. `RegistryApplication`은 `picasso.mqtt.url`이 있을 때만 구독을 붙이고(없으면 붙지 않음, 시험과 브로커 없는 배포), `registry`에 `paho-mqttv5` 의존을 더했다. `registry`는 `uplink`를 모르므로(§3.2) 토픽 꼴을 따로 읽는다. 시험은 여전히 `ObservationService`를 직접 부르고 `handle`은 브로커 없이 시험하여 결정성을 지킨다. 브로커는 `harness`의 통합 시험 하나만 쓴다.

    남는 것은 없다. 행 §15.34를 닫고 레지스터에서 지운다. 오픈 항목은 54개에서 53개(내부 24개에서 23개)다. 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았다.

    `registry`의 `MqttObservationSubscriberTest` 6건(기체 스트림 셋의 헤더를 적고 같은 사실은 접는다, 능력 변경 이벤트의 사유를 세대 이력의 사유로 옮긴다, 토픽이 기체 스트림 꼴이 아니면 적지 않고 센다, 헤더의 기체가 토픽의 기체와 다르면 적지 않는다, 등록되지 않은 기체의 헤더는 적재가 실패해도 예외 없이 센다, 읽지 못하는 페이로드와 헤더 없는 메시지는 적지 않는다)과 `harness`의 `ObservationSubscriptionTest` 1건(발행자의 토픽으로 낸 헤더가 세대 이력에 적힌다: `uplink`의 `MqttPublisher`가 `Topics`로 만든 토픽에 발행하면 실제 브로커를 지나 구독기가 적는다)을 더해 전체 시험 수는 2,406건에서 2,413건이다. 결함 주입 5건(기체 대조를 끔, 메이저 대조를 끔, 구독 필터의 `robot`을 `robots`로, 이벤트 사유를 늘 `BINDING_CHANGED`로, 적재 실패를 밖으로 냄)을 각각 해당 시험이 잡았다.

289. **`revise`가 승인된 조치 시퀀스와 승인자를 받아 다시 계획하는 첫 단위 앞에 세우도록 배선하고 개정의 연쇄 거부가 제안을 세우게 한다.**

    옛 항목 175와 한계 레지스터 내부 행 §15.175는 개정 경로(`revise`)가 조치 시퀀스 인자를 아예 안 받는다고 적었다. 그래서 개정의 연쇄 거부는 조치 탐색 기록만 남기고 제안을 세우지 않았고(ADR 46), 이미 실행으로 선 작업 지시의 승인은 버전이 같든 다르든 밖의 문에서 `REMEDY_NOT_APPLIED`, 사람의 문에서 같은 사유로 거부되었다. 해소 조건은 `revise`가 조치 시퀀스를 받아 앞에 세우도록 배선하고, 그날 개정의 거부가 다시 제안을 세울 수 있으므로 ADR 46의 소모 판정과 멱등성 키를 함께 다시 보는 것이었다. 소유자가 내부 오픈 항목을 차례로 모두 해소하라고 지시했다.

    `revise`가 승인된 조치 시퀀스(prefix)와 승인자를 받는다. 사슬 검사는 조치 시퀀스와 다시 계획할 단위를 이어서 굴리고, 조치 시퀀스는 다시 계획하는 첫 단위 앞에 선다(보내는 중인 단위는 그 결과를 기다리므로 그 뒤, 다시 계획할 단위가 없으면 끝). 첫 접수가 계획 맨 앞에 세우는 것과 같은 모양이다. `commit`은 이미 실행이 선 작업 지시라도 네 조건이 모두 서면 개정으로 내린다: 승인의 버전이 실행의 버전보다 높다, 실행이 제안의 기체에 서 있다, 실행이 아직 승인을 들지 않았다, 실행이 끝나지(`ABORTED`, `PHYSICALLY_DONE`) 않았다. 하나라도 어긋나면 전처럼 `REMEDY_NOT_APPLIED`로 접는다. 개정의 연쇄 거부가 이제 제안을 세운다. 단 이미 승인을 든 실행의 개정이면 세우지 않는다(`RemedyDesk.record`의 인자 이름이 `revision`에서 `carriesApproval`로 바뀌었다). 이유는 둘이다. 판단 기록(`JudgmentRecord`)의 `approval` 칸이 승인 하나만 싣고, 그 실행의 열쇠에는 소모 기록이 서 있어 제안을 세우면 재시도가 `CONSUMED`를 못 본다. 그래서 ADR 46이 닫은 재시도 창(소모 뒤 같은 조회 키에 새 제안이 서는 것)은 그대로 닫혀 있고 멱등성 키는 여전히 필요 없다. 소모 기록은 승인으로 실행에 승인이 실릴 때만 생기고, 그 실행의 개정은 제안을 세우지 않기 때문이다.

    남는 것은 없다. 승인을 든 적 없는 실행의 개정이 사슬에 걸려 선 제안을 승인하면 그 실행이 승인의 버전으로 개정되고 조치 시퀀스가 실린다. 소모 기록이 그 실행 id로 남고 재시도는 `CONSUMED`를 받는다. 행 §15.175를 닫고 레지스터에서 지운다. 오픈 항목은 55개에서 54개(내부 25개에서 24개)다.

    `picasso` · `EntitlementTest` · `개정의 거절이 세운 제안을 승인하면 조치 열이 다시 계획하는 첫 단위 앞에 선다` 1건을 더해 전체 시험 수는 2,405건에서 2,406건이다(순회 도중 점검 대상을 하나 더한 개정이 든 채라 사슬에 걸려 제안이 서고, 승인하면 같은 실행이 버전 2가 되며 조치 시퀀스가 `PUMP-01` 앞에 순서대로 서고, 재시도는 `CONSUMED`). 결함 주입 2건(개정에 조치 시퀀스를 안 넘김, 승인을 든 실행에도 제안을 세움)을 각각 이 시험과 `EntitlementTest` · `개정 거절은 소모를 가리지 않는다`가 잡았다. 승인 호스트 규약 버전 3, 판단 기록 형식, 내보내기 형식, 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았다.

288. **mimic과 `adapter-host`가 아직 나지 않은 번호의 재생 요청에 `SEQUENCE_EVICTED`로 답하고 스트림을 닫도록 바꾼다.**

    옛 항목 21과 한계 레지스터 내부 행 §15.21은 두 발신자(에뮬레이터 mimic, `adapter-host`)의 세션이 기동 시각(밀리초)과 프로세스 카운터라 같은 밀리초에 다시 뜨거나 시계가 되감기거나 에뮬레이터를 가상 시계로 띄우면(§12.1 결정성) 앞 세션이 되풀이될 수 있고, 그때 미들웨어가 재시작을 못 봐 낡은 커서가 새 세션의 앞 이벤트를 건너뛴다고 적었다. 재기동 뒤 옛 커서(새 프로세스의 다음 번호보다 큰 값)로 `ReplayEvents`를 물으면 두 발신자 모두 빈 응답을 주었다. 해소 조건은 결정론적 고유 식별자 생성기를 두거나 옛 커서를 축출로 응답하는 규칙을 정하는 것 중 하나였다. 소유자가 내부 오픈 항목을 차례로 모두 해소하라고 지시했다.

    뒤의 조건을 택했다. 두 발신자가 `from_sequence`가 다음에 낼 번호보다 크면 `SEQUENCE_EVICTED`로 답하고 스트림을 닫는다(mimic `EventServiceImpl.replayEvents`, `adapter-host` `HostedRobot.replay`). 거부 문구는 '아직 나지 않은 번호다: 요청=…, 다음=… - 발신자가 다시 떴을 수 있다. GetSnapshot부터 다시 세워라'다. 다음 번호와 같은 커서는 전처럼 빈 응답이다. 그런 커서는 이 프로세스가 준 것이 아니라 다시 뜨기 전의 것이다. 계약의 `ReplayEventsResponse` 주석은 이미 '요청한 sequence가 버퍼를 벗어나면 SEQUENCE_EVICTED'라 적고 있고 아직 나지 않은 번호는 버퍼 밖이므로 proto와 계약 SemVer 0.10.0은 바뀌지 않았다. 세션이 되풀이되어도 옛 커서가 새 프로세스의 다음 번호보다 크면 미들웨어가 축출을 받아 이미 있는 경로로 스냅숏부터 다시 세운다(`picasso` · `EventStreamTest` · `재생 버퍼를 벗어나면 스냅샷부터 다시 세운다`).

    남는 것이 있다. 세션이 되풀이되고 새 프로세스의 번호가 이미 옛 커서를 지났으면 그 커서는 버퍼 안의 정상 번호로 보이므로, 옛 커서가 새 세션의 그 앞 이벤트를 여전히 건너뛴다. 세션 되풀이 자체(기동 시각과 카운터로 만드는 방식)도 그대로다. 그래서 행 §15.21은 닫지 않고 좁힌다. 해소 조건은 이제 고유 식별자 생성기 하나다. 오픈 항목 수는 그대로 55개다.

    새 시험으로 mimic `EventServiceTest` 1건과 `adapter-host` `AdapterHostTest` 1건(둘 다 이름 `아직 나지 않은 번호를 물으면 SEQUENCE_EVICTED 다`, 다음 번호는 축출이 아니고 그다음 번호는 축출이며 거부 문구가 '아직 나지 않은 번호'를 담는다)을 더해 전체 시험 수는 2,403건에서 2,405건이 되었다. 결함 주입 2건(각 발신자의 새 검사를 끔)을 각 시험이 잡았다. 같은 기체로 프로세스를 두 번 세우는 시험은 없다(두 발신자 모두 같은 프로세스 안에서 커서를 앞질러 물어 흉내 낸다).

287. **제어 채널 proto를 두 번째 buf 모듈로 삼아 게이트 검사 1·2번의 lint와 breaking 대상에 넣고 공유 응답 타입을 둘로 나눈다.**

    옛 항목 32와 한계 레지스터 내부 행 §15.32는 제어 채널의 proto(`mimic/src/main/proto/picasso/control/v1/control.proto`, §10.5)가 `buf lint`와 `buf breaking` 밖이라고 적었다. `contracts/buf.yaml`의 모듈이 `proto/` 하나이고 제어 채널은 계약 표면이 아니어서 `mimic/` 안에 있기 때문이다. 그래서 필드 번호를 재사용해도 게이트 1·2번이 잡지 않았고 번호 규율을 사람이 지켰다. 옛 항목은 두 번째 buf 모듈을 더하면 닫히지만 Docker 호출이 하나 는다고 적었다. 해소 조건은 제어 채널용 buf 빌드 파이프라인 추가였다. 소유자가 내부 오픈 항목을 차례로 모두 해소하라고 지시했다(특정 방식을 지시하지 않았다).

    `mimic/buf.yaml`을 두어 `src/main/proto`를 두 번째 buf 모듈로 삼았다. 규칙은 계약 모듈과 같다(lint STANDARD, breaking FILE). 계약과 한 모듈로 묶지 않은 것은 제어 채널이 계약 표면이 아니기 때문이다. 처음 돌린 lint가 위반 넷을 냈다. 모두 한 가지로, `RemoveCapability`와 `RestoreCapability` 두 RPC가 응답 타입 `CapabilityChangeResponse`를 함께 쓴 것이다. 같은 칸(`capability_epoch` 1, `changed` 2, `available_skills` 3)을 가진 `RemoveCapabilityResponse`와 `RestoreCapabilityResponse`로 나눴다. 메시지 이름은 전송 형식에 들지 않으므로 바이트는 같다. 다만 buf breaking의 FILE 규칙으로는 이 이름 바꿈 자체가 파괴다(메시지 삭제, RPC 응답 타입 변경, 실측). 이 변경의 베이스에는 `mimic/buf.yaml`이 없어 CI가 제어 채널 기준선을 넘기지 않으므로 이 변경은 그것에 걸리지 않으며, 이 뒤로는 같은 바꿈이 걸린다. 검사 1번은 `contracts`와 `mimic` 두 디렉터리에서 `buf lint`를 돌리고, 소견 위치를 각 모듈 기준으로 적는다. 검사 2번은 새 입력 `controlBaseline`(CLI `--control-baseline`, 자원 `CONTROL_BASELINE`)이 있으면 `mimic`에서도 `buf breaking`을 돌린다. 없으면 계약만 비교하고 그쪽을 부분 건너뜀으로 낸다(통과로 숨기지 않는다). CI는 베이스에 `mimic/buf.yaml`이 있을 때 `../.git#ref=<베이스 SHA>,subdir=mimic`을 넘긴다. Docker 호출은 검사마다 하나씩 는다.

    남는 것은 없다. 행 §15.32를 닫고 레지스터에서 지운다. 오픈 항목은 56개에서 55개(내부 26개에서 25개)다. 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았다.

    음성 하네스에는 케이스 둘을 더했다. `01-control-bad-message-name`(제어 채널 메시지 이름을 snake_case로, 검사 1번이 PascalCase 소견)과 `02-control-field-number-reuse`(`AdvanceClockRequest`의 필드 1번 타입을 int64에서 string으로, 검사 2번이 changed type 소견)다. 하네스가 제어 채널 기준선을 넘긴다. 이 기계에서는 Docker 바인드 마운트가 실패해 하네스의 buf 케이스를 로컬로 못 돌렸고, buf를 컨테이너에 파일을 복사하는 방식으로 직접 돌려 두 소견이 나오는 것을 확인했다. CI가 엄격 모드로 두 케이스를 돈다. `Check01BufLintTest` 1건 바꿈(두 모듈에서 lint를 부른다)과 1건 추가(제어 채널의 lint 위반을 그 모듈 자리로 짚는다), `Check02BufBreakingTest` 3건 추가(제어 채널 기준선이 있으면 mimic 모듈에서도 breaking을 부른다, 기준선이 없으면 그쪽만 부분 건너뜀이다, 제어 채널의 파괴를 그 모듈 자리로 짚는다)다. 전체 시험 수는 2,399건에서 2,403건이다. 결함 주입 5건(모듈 목록에서 제어 채널을 뺌, 위치 접두사를 늘 contracts로, 제어 채널 기준선 무시, 부분 건너뜀을 안 냄, 부분 건너뜀을 늘 냄)을 지정 시험이 모두 잡았다.

286. **구조 규칙 넷을 `profile-model`의 `StructuralRules.kt`로 옮겨 게이트 검사 3번과 mimic이 함께 부르게 하고 한계 레지스터 행 §15.19를 닫는다.**

    옛 항목 19와 한계 레지스터 내부 행 §15.19는 mimic의 `FileProfileSource`가 JSON Schema만 보고 게이트 검사 3번의 구조 규칙을 보지 않아 mimic이 게이트가 거부할 프로파일로 기동할 수 있다고 적었으며, 해소 조건은 기동 파이프라인에서 검사 3번의 구조 규칙을 호출하는 것이었다. 소유자가 내부 오픈 항목을 차례로 모두 해소하라고 지시했다. 다시 재어 보니 옛 항목 19는 구조 규칙 넷을 발행 간격 뒤집힘, `(skill_type, major)` 중복, 스킬 내 `key` 중복, 어댑터 전용 `error_type`이라 적었으나, 지금 검사 3번의 구조 규칙 넷은 발행 간격 뒤집힘, `(skill_type, major)` 중복, 스킬 내 파라미터 `key` 중복, 파라미터 범위 뒤집힘(`min_value > max_value`)이다. 어댑터 전용 `error_type` 둘은 스키마의 enum이 빼고 있어 스키마 검증이 거부하며, mimic도 같은 스키마를 돌리므로 그 하나는 이미 막혀 있었다. mimic은 gate에 의존할 수 없고(§3.2) 둘이 함께 의존하는 모듈은 `profile-model`이다(ADR 29).

    구조 규칙 넷을 `profile-model`의 새 파일 `StructuralRules.kt`로 옮겼다. 확장 함수 `ProfileDocument.structuralViolations()`가 `StructuralViolation(pointer, message)` 목록을 돌려준다. 게이트 검사 3번은 이것을 소견으로 옮기기만 하며 소견의 메시지와 위치는 전과 같다. mimic의 `FileProfileSource.load`는 스키마를 통과한 뒤 같은 함수를 불러 위반이 있으면 '프로파일이 구조 규칙을 통과하지 못했다' 메시지와 `포인터: 메시지` 꼴 소견으로 `ProfileRejected`를 던져 기동하지 않는다.

    남는 것은 없다. 행 §15.19를 닫고 레지스터에서 지운다. 오픈 항목 수는 57개에서 56개(내부 27개에서 26개)다. 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았다.

    새 시험은 `ProfileSourceTest` 1건(스키마는 통과해도 구조 규칙을 어기면 기동을 거부한다. 참조 픽스처 `minimal.json`의 `grip_force` 범위를 0~-1로 뒤집은 문서)이다. 검사 3번의 기존 시험 14건이 옮긴 규칙을 그대로 덮으며, 결함 주입 1건(mimic이 구조 규칙 결과를 버림)을 그 시험이 잡았다. 전체 시험 수는 2,398건에서 2,399건이 되었다.

285. **`ProfileDocument.OptionalFieldEntry`에 `parameterKeyOf`와 `parameterKey`를 두고 게이트 검사 4와 6의 `optional_fields` 경로 매칭을 `task.parameters.<key>` 정확 일치로 바꾼다.**

    옛 항목 18과 한계 레지스터 내부 행 §15.18은 게이트 검사 6의 `optional_fields` 매칭이 접미사 일치(`path.endsWith(".$key")`)라 같은 파라미터 key를 가진 다른 스킬에도 소견이 붙는다고 적었고 해소 조건은 스킬 타입 복합 키 기반 매칭이었다. 소유자가 내부 오픈 항목을 차례로 모두 해소하라고 지시했다. 계약(`skill.proto`의 `OptionalFieldDeclaration.parameter_path`, 예 `task.parameters.approach_vector`)과 프로파일 스키마의 경로에는 스킬이 없다. 선택 필드 선언은 프로파일 전체의 태스크 파라미터 경로를 가리키므로, 그 경로가 REQUIRED가 되면 그 key를 가진 모든 스킬의 태스크가 영향을 받고 검사 6이 그 스킬들 모두에 major 증가를 요구하는 것은 계약대로다. 스킬 타입 복합 키로 맞추려면 경로 형식에 스킬을 넣어야 하는데 그것은 소비자 없는 계약 변경이라 하지 않는다(ADR 9). 실제 부정확은 접미사 일치였다. 끝 마디만 보므로 `task.metadata.grip_force` 같은 파라미터 밖의 경로도 파라미터 `grip_force`로 읽혔다. 검사 4의 `optional_fields` 참조 검사도 끝 마디(`substringAfterLast('.')`)만 봐서 같은 경로를 통과시켰다.

    `ProfileDocument.OptionalFieldEntry`에 `parameterKeyOf`(경로가 정확히 `task.parameters.<key>` 꼴이면 key, 아니면 널)와 `parameterKey`를 두고, 검사 6의 두 자리(같은 major 안의 변화, 어느 스킬 파라미터와도 대응하지 않는 필수화)와 검사 4의 참조 검사가 이것을 쓴다. 그래서 파라미터 밖의 경로가 REQUIRED가 되면 스킬 major로 흡수할 수 없다는 ERROR가 나고, 검사 4는 그런 선언을 선언되지 않은 파라미터를 가리킨다고 실패시킨다. 지금 저장소의 프로파일은 모두 `task.parameters.` 아래 경로만 쓰므로 결과가 바뀌는 프로파일은 없다.

    남는 것은 없다. 행 §15.18을 닫고 레지스터에서 지운다. 오픈 항목 수는 58개에서 57개(내부 28개에서 27개)로 줄었다. 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았다.

    `Check06VocabularyTest` 1건(끝 마디만 같은 다른 경로의 필수화를 스킬 major로 흡수하지 않는다)과 `Check04CrossRefTest` 1건(optional_fields가 파라미터 밖의 경로를 가리키면 끝 마디가 같아도 실패한다)을 더해 전체 시험 수는 2,396건에서 2,398건이 되었다. 결함 주입 4건(검사 6의 두 자리를 각각 접미사로 되돌림, 검사 4를 끝 마디로 되돌림, 도우미가 접두사를 무시)을 지정 시험이 모두 잡았다.

284. **`ContractIndex`가 계약 저작 실수를 최상위 메시지 단위로 가두어 나머지 스킬의 색인과 검사 4번 대조를 유지하고 `SkillTypeSync`는 깨진 정의가 있으면 실패하도록 한다.**

    옛 항목 13과 한계 레지스터 내부 행 §15.13은 proto 파일 하나가 나쁘면 게이트의 `ContractIndex`가 통째로 실패한다고 적었다. 게이트는 빨간불이 되므로 안전하지만 검사 4번(프로파일과 계약의 양방향 대조)이 나머지 스킬들의 대조 결과를 함께 잃는다. 해소 조건은 파일 단위 오류 격리였다. 실패의 원인은 계약 저작 실수(repeated 파라미터, `skill_type_major` 미선언, 지원하지 않는 타입, 중첩된 스킬 메시지)이며 전부 최상위 메시지 하나에서 난다. 소유자가 내부 오픈 항목을 차례로 모두 해소하라고 지시했다.

    `ContractIndex.from`이 최상위 메시지마다 저작 실수를 가둔다. 실수가 난 메시지는 새 `BrokenSkill`(`protoMessage`, `skillName`, `reason`)로 `broken`에 남고 나머지 스킬은 그대로 색인된다. 격리 단위는 해소 조건의 파일보다 좁은 최상위 메시지다. 디스크립터 자체가 깨진 것(파싱 실패, 없는 의존 파일, 스킬 옵션 확장의 누락)은 메시지에 가둘 수 없어 전처럼 실패한다. 옵션 확장 누락 검사는 메시지마다가 아니라 한 번만 한다. 검사 4번은 깨진 정의를 계약의 자리(proto 메시지)로 짚는 `ERROR` 소견을 내고 대조를 멈추지 않는다. 깨진 정의의 스킬을 선언한 프로파일 항목에는 "계약에 없는 케이퍼빌리티"가 아니라 계약의 정의가 깨져 대조하지 못했다는 소견을 낸다. 도달 불가능한 파라미터도 이제 대조를 멈추지 않는다. 중복 (`name`, `major`)가 있으면 `find`가 실패하므로 그때만 전처럼 대조 전에 멈춘다. 빈 카탈로그 소견은 깨진 정의도 없을 때만 낸다. 색인을 그대로 옮겨 적는 `SkillTypeSync`는 새 `requireIntact()`를 불러 깨진 정의가 있으면 전처럼 실패한다. 깨진 메시지를 빼고 적으면 그 스킬이 계약에서 사라진 것처럼 보이기 때문이다.

    남는 것은 한 가지다. `SkillTypeSync`의 호출 자리를 직접 시험하지는 않는다(계약 변형 도구가 gate 시험 소스에만 있다). `requireIntact` 자체는 시험한다. 행 §15.13을 닫고 레지스터에서 지운다. 오픈 항목은 59개에서 58개(내부 29에서 28)다.

    `ContractIndexTest`의 기존 3건(repeated, major 미선언, 중첩)을 예외 대신 깨진 정의 기록을 보도록 고치고 2건(깨진 정의 하나가 다른 스킬의 색인을 막지 않는다, 깨진 정의가 있으면 그대로 옮겨 적는 쪽은 실패한다)을 더했으며 `Check04CrossRefTest`에 1건(깨진 계약 정의가 있어도 나머지 스킬의 대조 결과를 함께 낸다)을 더해 전체 2,393건에서 2,396건이다. 결함 주입 6건을 지정 시험이 모두 잡았다. 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았다.

283. **`capability_epoch_log`에 `seen_count` 칸을 더해 접힐 때마다 받은 횟수를 올리고 진단 세대 이력 `EpochRow`에 싣는다.**

    옛 항목 36과 한계 레지스터 내부 행 §15.36은 세대 이력(`capability_epoch_log`)의 접기가 `(robot_id, epoch, profile_ref, cause)`가 같으면 한 줄로 접으므로 같은 사실을 몇 번 받았는지가 사라지고 마지막 시각만 남는다고 적었다. 해소 조건은 카운터 칸 추가였다. 세대는 유효 capability가 바뀔 때마다 오르므로 같은 열쇠로 다시 오는 것은 같은 사실의 재보고(폴링과 발행의 반복)다. 소유자가 내부 오픈 항목을 차례로 모두 해소하라고 지시했다.

    새 마이그레이션 `V17__epoch_log_seen_count.sql`이 `capability_epoch_log`에 `seen_count INTEGER NOT NULL DEFAULT 1`을 더한다(기존 줄은 1). `ObservationService.recordHeader`의 접기(`ON CONFLICT ... DO UPDATE`)가 시각과 함께 `seen_count`를 1 올린다. 진단 3번(`DiagnosticsService.epochs`, `GET /diag/epochs`)의 한 줄 `EpochRow`에 `seenCount`를 더해 싣는다. 필드를 더한 것이라 기존 읽는 쪽은 그대로 읽는다.

    처음 받은 시각은 여전히 남지 않는다(`occurredAt`은 마지막 시각). 해소 조건이 카운터였으므로 행 §15.36을 닫고 레지스터에서 지운다. 오픈 항목은 60개에서 59개(내부 30에서 29)다. 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았다.

    `ObservationTest` 1건(접힐 때마다 받은 횟수가 오르고 새 줄은 1에서 센다)과 `DiagnosticsTest` 1건(세대 이력이 같은 사실을 받은 횟수를 싣는다)을 더해 전체 2,391건에서 2,393건이다. 로컬 Docker의 Postgres로 registry 시험을 돌렸고 결함 주입 3건(접을 때 올리지 않음, 진단이 칸 대신 1을 읽음, 기본값 0)을 지정 시험이 모두 잡았다.

282. **mimic의 `Progress`에 `pause`와 `resume`을 두어 멈춘 구간을 빼고 `TaskMachine.transitionTo`에서 이를 부른다.**

    옛 항목 26과 한계 레지스터 내부 행 §15.26은 mimic의 `Progress`가 경과만 세므로 일시정지 중에도 진행률이 흘러 길게 멈췄다 재개하면 진행률이 튄다고 적었다(§4.4는 (task_id, revision, attempt) 구간 안의 단조 비감소만 요구해 계약 위반은 아니었다). 이번에 코드를 읽으며 `TaskHost.tick`이 `RUNNING` 태스크의 진행률 1.0 이상을 완료로 읽으므로, 소요시간보다 길게 멈췄다 재개한 태스크는 재개 직후 다음 틱에 곧바로 완료(또는 실패 추첨)로 간다는 점을 더 확인했다. 소유자가 내부 오픈 항목을 차례로 모두 해소하라고 지시했다.

    `Progress`에 `pause`와 `resume`을 두고 멈춘 시각(`pausedAt`)과 멈춘 구간의 합(`pausedTotal`)을 들어 경과에서 멈춘 구간을 뺀다. 멈춰 있는 동안 값은 멈춘 시각에서 고정된다. `restart`는 합을 0으로 되돌리고 멈춰 있으면 지금부터 멈춘 채로 다시 센다. `TaskMachine.transitionTo`가 상태를 쓰는 같은 자리에서 `PAUSED`로 들어가면 `pause`를, `PAUSED`에서 나가면(재개든 취소든) `resume`을 부른다. 시험용 `seed`가 `PAUSED`로 세우면 멈춘 채로 시작한다.

    남는 것은 없다. 행 §15.26을 닫고 레지스터에서 지운다. 오픈 항목은 61개에서 60개(내부 31에서 30)가 된다. 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았다.

    `TaskMachineTest`에 2건(일시정지 동안 진행률이 흐르지 않고 재개하면 멈춘 자리부터 센다, 멈춘 채 갱신하면 0에서 멈춘 채로 다시 센다)을 더해 전체 시험 수는 2,389건에서 2,391건이 되었다. 결함 주입 3건(값이 멈춘 시각을 무시, restart가 옛 멈춘 시각을 유지, 재개 고리 제거)을 지정 시험이 모두 잡았다. `TaskHost`의 즉시 완료 자체를 재현하는 시험은 없고 진행률 값으로만 확인한다.

281. **`HeartbeatFreshness.kt`의 최상위 상수 `HEARTBEAT_FRESHNESS`로 하트비트 신선도 상수를 한 자리로 모으고 `RegistryLedgerQuery`와 `RobotObservability`의 동반 객체 상수를 지운다.**

    한계 레지스터 행 §15.41은 하트비트 신선도 유효기간이 24시간 상수로 고정되어 있고, 그 상수가 `RegistryLedgerQuery`와 `RobotObservability`의 동반 객체에 각각 `DEFAULT_FRESHNESS`(`Duration.ofHours(24)`)로 따로 선언되어 있으며 `DiagnosticsService`의 `LIVENESS_WINDOW`는 뒤엣것을 건너가 읽는다고 적었다(2026-09-23 재측에서 나옴). 한쪽만 바뀌면 진단이 «보고 중»이라 적은 기체 때문에 축소가 막힌다. 소유자가 2026-11-07에 이 상수를 한 자리로 모으는 조각을 골랐다.

    `registry` 모듈 ledger 패키지에 새 파일 `HeartbeatFreshness.kt`를 두고 최상위 상수 `HEARTBEAT_FRESHNESS`(`Duration.ofHours(24)`)를 선언했다. 24시간의 이유(가동 중인 라인이 하루 한 번도 협상하지 않거나 태스크 하나 안 움직이는 일은 없다)를 그 KDoc에 옮겼다. `RegistryLedgerQuery`와 `RobotObservability`의 동반 객체 상수 `DEFAULT_FRESHNESS` 둘을 지우고 두 생성자의 `freshness` 기본값을 이 상수로 바꾸었으며, `DiagnosticsService.LIVENESS_WINDOW`도 이 상수를 읽는다. 값과 동작은 바뀌지 않았다. 변경 이력의 앞 항목들이 적은 `RobotObservability.DEFAULT_FRESHNESS`라는 이름은 이제 없다.

    남는 것은 한 가지다. 기종이나 사이트마다 다른 만료 값은 아직 받지 않고 각 생성자의 `freshness` 인자로만 바꾼다. 소비자가 없어 두지 않는다(ADR 9). 그래서 행 §15.41은 닫지 않고 상수가 두 자리에 있다는 부분만 지워 좁힌다. 오픈 항목 수는 그대로 61개다.

    새 시험은 없다. 상수가 한 자리에만 선언되어 컴파일러가 두 자리의 어긋남을 막으며, 값과 동작이 그대로라 기존 시험이 덮는다. 전체 시험 수는 2,389건 그대로다. `registry` 모듈 시험은 Docker가 필요해 로컬 표준 빌드에서 빠지고 CI가 돌린다. 계약 proto와 계약 SemVer 0.10.0, 작업 응답 형식, 판단 기록 형식은 바뀌지 않았다.

280. **기체의 높은 리비전을 `holdRevisionAhead`로 운영자 보류에 두고, 리비전을 싣지 않는 포트는 `startedRevision`이 단위 리비전과 같을 때만 스냅숏에 붙인다.**

    항목 238(§15.238)이 미확정 로봇 단위의 스냅숏 부착을 들이며 스냅숏 리비전이 단위 리비전과 같을 때만 붙게 했고, 내부 행 «§15.238 · 같은 리비전 부착»을 열었다. 소유자가 2026-11-07에 확장 둘을 정했다: 기체가 단위보다 높은 리비전을 들면 운영자 보류로 보내고, 리비전을 싣지 않는 포트에서는 그 태스크를 한 리비전으로만 보낸 단위만 붙인다.

    `resolveDoubt`가 스냅숏 부착 앞에서 새 함수 `holdRevisionAhead`를 부른다. 기체의 스냅숏이 이 단위의 태스크를 단위보다 높은 리비전으로 들면 이 인스턴스가 보낸 적 없는 개정이 기체에 닿은 것이고, 다른 명령자가 그 태스크를 고친 것이다. 전에는 부착을 건너뛰고 조회가 낮은 리비전의 `StartTask`를 다시 보냈다. 하위가 `OUTDATED_REVISION`으로 답하면 단위가 실패로 적히고, 기체가 그 태스크를 도는 동안 실행이 다음 단위를 낼 수 있었다. 이제는 붙지도 묻지도 않고 단위를 운영자 보류에 두며, 새 트레이스 종류 `DOUBT_REVISION_AHEAD`를 남기고 인시던트를 표시한다. 취소가 걸린 실행은 이 길을 타지 않고 전처럼 키로 만든 핸들의 취소가 맡는다.

    내부 칸 `ExecutionUnit.startedRevision`을 추가했다. 이 인스턴스가 지금 태스크 id를 처음 보낸 리비전이며 `startUnit`이 적고 재작업이 비운다. 재기동 뒤 다시 세운 시도는 앞 인스턴스가 어느 리비전으로 보냈는지 모르므로, `resume`이 로봇 단위마다 그때의 시도를 새 내부 칸 `Execution.inherited`에 두고 `startUnit`은 그 시도에 `startedRevision`을 적지 않는다(널). 포트가 그 태스크의 리비전을 싣지 않으면 `attachFromSnapshot`은 `startedRevision`이 단위 리비전과 같을 때만 붙는다. 보이는 태스크가 곧 그 리비전으로만 나간 것이기 때문이다. 갱신을 보냈거나(리비전이 다름) 내력을 모르면(널) 붙지 않고 전처럼 조회가 맡는다. 리비전을 싣는 포트의 규칙은 그대로다.

    계약 proto와 계약 SemVer 0.10.0, 작업 응답 형식, 판단 기록 형식은 바뀌지 않았다. `ClientRobotPort`는 이미 리비전을 채우므로 리비전 없는 길은 다른 포트 구현에 해당한다.

    남는 것은 세 가지다. 첫째, 리비전을 싣지 않는 포트는 갱신을 보낸 뒤나 재기동 뒤(재기동 뒤 붙는 `reattachFromSnapshot` 포함)에는 여전히 붙지 못하고 조회와 시간 예산 경로로 가므로 행 §15.238은 닫지 않고 좁힌다. 둘째, 취소가 걸린 실행에서 기체가 더 높은 리비전을 드는 경우는 키로 만든 핸들의 취소 경로를 타며 전용 시험이 없다. 셋째, 시험 더블은 스냅숏의 리비전만 올리고 에뮬레이터는 낮은 리비전을 그대로 들므로, 시험은 하위의 `OUTDATED_REVISION` 답 자체를 재현하지 않는다.

    `InDoubtTest`에 3건을 추가해 전체 시험이 2,386건에서 2,389건이 되었다(«리비전을 싣지 않는 포트에서도 한 리비전으로만 보낸 미확정 태스크에는 StartTask 없이 붙는다», «리비전을 싣지 않는 포트에서 갱신을 보낸 미확정 단위는 붙지 않고 조회로 푼다», «기체가 단위보다 높은 리비전을 들면 붙지도 묻지도 않고 운영자 보류에 둔다»). 결함 주입 5건을 각각 지정 시험이 잡았다(재기동 뒤 시도를 모르는 것으로 두는 조건을 뺀 주입은 기존 `JudgmentRecordTest`의 «근거 윈도우의 요청 시각이 넘어와 처음 요청 뒤의 설비 신호를 잠정 근거로 본다»가 잡았다). 기존 `UnitRobotBindingTest`의 «미확정 단위의 조회는 묶인 단위의 기체로 다시 묻는다»는 리비전을 싣지 않는 더블이라 이제 조회 없이 붙으므로, 조회의 기체를 보려고 그 기체의 스냅숏을 닫도록 고쳤다. 한계 레지스터 행 §15.238은 닫지 않고 좁혔으며 오픈 항목 수는 그대로 61개다.

279. **`PrefetchingRobotPort`로 로봇 선언을 미리 읽고 `capabilitiesPending`이 참이면 출발을 미루며 `CellSignals`에 막지 않는 읽기 계약을 명시한다.**

    항목 278(§15.278)이 플릿 읽기 둘을 잠금 밖으로 빼고 출발 요청을 만들 때 묻는 로봇의 `capabilities`와 셀 신호 읽기(`CellSignals`)를 동기로 남겼다. 소유자가 2026-11-07에 이 조각의 범위를 `capabilities` 선읽기와 셀 신호 계약 명시로 정했고, 선언을 아직 읽는 중이면 출발을 미루기로 정했다. 이 항목이 여섯째 조각이며 행 §15.237을 닫는다.

    `PrefetchingRobotPort`가 스냅숏을 읽을 때마다 재생에 이어 기체의 선언(`capabilities`)도 한 번 묻는다. `capabilities`는 지금 묻지 않고 받은 지 `maxAge` 안의 마지막 선언을 준다(소비하지 않는다). 없으면 읽기를 걸고 널을 주며, 미들웨어는 선언을 모를 때처럼 선택 파라미터를 붙이지 않는다(§15.41). 선언을 못 물어봤으면 앞서 받은 선언을 덮지 않고 기한이 그것을 버린다. 널로 덮으면 한 번의 실패가 곧 «모른다»가 되기 때문이다. 읽기 한 번에 하위 호출이 하나 늘어난다. `RobotPort`에 선택 메서드 `capabilitiesPending(robotId)`를 추가했다. 기본값은 거짓이고 지금 묻는 포트는 언제나 거짓이다. `PrefetchingRobotPort`는 받은 지 `maxAge` 안의 선언이 없고 그 사이 선언을 물은 적도 없을 때만 참을 주며, 기다림은 처음 참을 준 때부터 `maxAge`까지다. 스냅숏 읽기가 던지면 선언을 묻지 않으므로 기한이 없으면 출발이 끝없이 밀리기 때문이다.

    로봇 단위의 출발 직전에, 실행의 요구가 선택 파라미터(`preferredOptionals`)를 바라고 `capabilitiesPending`이 참이면 이번 라운드에는 출발을 내지 않는다. 허가가 막을 때처럼 단위는 `PENDING` 그대로 기다린다. 지금 내면 «모른다»로 접혀 선택 파라미터가 빠지기 때문이다. 끝내 못 읽으면 기한 뒤 출발은 «모른다»로 나간다. 선택 파라미터를 바라지 않는 실행과 플릿 단위는 미루지 않는다.

    `CellSignals`의 KDoc에 읽기(`observe`, `holding`, `signal`)는 막지 않아야 한다는 계약을 적었다. 미들웨어는 소비자 잠금 아래에서 이 읽기를 부르고 이 포트에는 미리 읽는 경로를 따로 두지 않는다. 설비를 원격으로 읽는 구현은 그 읽기를 잠금 밖에서 돌려 두고 여기서는 마지막으로 읽은 값만 주며, 못 읽었으면 기다리지 않고 널을 준다. 이 계약은 문서뿐이고 구현이 지키는지를 보는 시험은 없다(picasso에는 원격 구현이 없다). 선읽기 포트를 쓰지 않는 결선은 동기 경로 그대로이고 기존 시험은 바뀌지 않았다. 계약 proto와 계약 SemVer 0.10.0, 작업 응답 형식, 판단 기록 형식은 바뀌지 않았다.

    남는 것은 세 가지다. 첫째, 셀 신호 계약을 지키는지는 구현의 몫이고 시험이 집행하지 않는다. 둘째, picasso-ops 기본 결선은 아직 이 데코레이터들을 쓰지 않으며 결선 안내 `handoff/picasso-ops/issuing-robot-port.md`에 선언 선읽기와 셀 신호 계약을 더했다. 셋째, 적정 기한 예산과 `maxAge`는 현장 실측 전까지 UNKNOWN이다. 소비자 잠금 아래 동기로 남은 하위 호출이 없어졌으므로 한계 레지스터 내부 행 §15.237을 닫고, 남은 UNKNOWN(기한 예산과 `maxAge`의 현장 실측)을 외부 행 §15.279로 새로 두었다. 내부 오픈 항목은 32개에서 31개, 외부는 29개에서 30개가 되었고 합계는 그대로 61개다.

    4건을 추가해 전체 시험이 2,382건에서 2,386건이 되었다. `PrefetchingRobotPortTest`에 3건(«기체의 선언은 지금 묻지 않고 스냅숏 읽기에 이어 받은 값을 준다», «못 물어본 선언은 앞 선언을 덮지 않고 받은 지 maxAge 넘은 선언은 주지 않는다», «선언을 아직 못 받았으면 기한 안에서만 읽는 중이라고 답하고 못 물어본 선언은 기다리지 않는다»), `OptionalParameterTest`에 1건(«미리 읽는 포트면 선언을 라운드 안에서 묻지 않고 스냅숏 읽기에 이어 받은 선언으로 붙인다»)을 두었다. 결함 주입 8건을 각각 지정 시험이 잡았다.

278. **`AmrFleetPort.prefetchedStatus`와 `prefetchedFind`, `PrefetchingFleetPort`로 플릿 상태와 찾기 읽기를 소비자 잠금 밖으로 빼고 선읽기 데코레이터로 돌린다.**

    항목 277(§15.277)이 플릿 명령 둘(`dispatch`, `cancel`)을 잠금 밖으로 빼고 플릿 읽기 둘(`status`, `find`)을 동기로 남겼다. 소유자가 2026-11-06에 읽기 둘을 선읽기 데코레이터 방식으로 다음 조각에서 옮기기로 정했다. 이 항목이 다섯째 조각이다.

    `AmrFleetPort`에 선택 메서드 `prefetchedStatus(handle)`와 `prefetchedFind(reference)`를 추가했다. 기본값은 널이고 널이면 미들웨어가 전처럼 `status`와 `find`를 지금 부른다. 둘이 돌려주는 `FleetRead`는 `Pending`(읽는 중이고 기한 안), `Overdue`(읽기가 나간 시각, 기한 넘게 답 없음), `Read`(값과 실제로 보낸 시각·받은 시각), `Failed`(읽기가 던짐)의 넷이다. 로봇 쪽 `PrefetchedRead`와 달리 «읽은 적 없음»을 따로 두지 않는다: 처음 물으면 읽기를 걸고 `Pending`을 준다. 기한 넘게 답 없는 읽기를 `Overdue`로 따로 주는 것은 동기로 부를 때 답하지 않는 플릿이 기한 끝에 던지던 자리를 그대로 두기 위해서다. 새 `PrefetchingFleetPort(delegate, now, maxAge, executor)`가 운반 상태는 핸들마다, 찾기는 참조마다 읽기를 하나씩 자기 실행기에서 돌린다. 규칙은 `PrefetchingRobotPort`와 같다: 하나가 끝나기 전에는 다음 읽기를 걸지 않고, 답하지 않는 운반 하나가 다른 운반의 읽기를 늦추지 않으며, 다 읽은 값은 한 번만 주고 준 뒤에는 다음 읽기를 걸고, 받은 지 `maxAge` 넘은 값은 버린다(기본 `maxAge` 2초). 끝난 운반은 다시 묻지 않으므로 읽기가 돌지 않고 `maxAge` 넘게 받아 가지 않은 자리는 다음에 물을 때 지운다. 명령과 `status`·`find` 자체는 위임 포트로 바로 가며, `IssuingFleetPort`와 어느 쪽으로 겹쳐 감싸도 된다. 도는 운반 단위를 보는 `pumpFleetUnit`이 `prefetchedStatus`를 먼저 묻는다. `Pending`이면 이번 라운드에는 단위를 그대로 두고, `Overdue`와 `Failed`는 동기 읽기가 던지던 것과 같이 다룬다(라운드 진행의 펌프 실패로 자취에 남기고 상태는 그대로). 끝난 운반의 하류 완료 시각과 실패 시각은 그 읽기가 받은 시각이다. 받아 든 시각으로 찍으면 근거 창이 그만큼 밀린다. 다시 만든 실행에 붙기 전에 건 취소의 플릿 쪽(`attachTransportForCancel`)은 `prefetchedFind`와 `prefetchedStatus`를 차례로 묻고, `Pending`이면 답을 기다리는 것과 같이 같은 미확정 유예를 센다. `prefetchedStatus`와 `prefetchedFind`를 쓰지 않는 포트는 동기 경로 그대로이고 기존 시험은 바뀌지 않았다. 계약 proto와 계약 SemVer 0.10.0, 작업 응답 형식, 판단 기록 형식은 바뀌지 않았다.

    남는 것은 네 가지다. 첫째, 로봇과 플릿의 명령과 읽기는 담는 쪽이 데코레이터로 감싸면 모두 잠금 밖에서 나가지만, 출발 요청을 만들 때 묻는 로봇의 `capabilities`와 셀 신호 읽기(`CellSignals`의 `observe`, `signal`)는 여전히 소비자 잠금 아래 동기이므로 행 §15.237은 닫지 않고 좁힌다. 둘째, picasso-ops 기본 결선은 아직 이 데코레이터를 쓰지 않으며 지금은 `Middleware`에 플릿 포트를 넘기지 않는다. 결선 안내 `handoff/picasso-ops/issuing-robot-port.md`에 이 데코레이터를 더했다. 셋째, 적정 `maxAge`와 기한 예산은 현장 실측 전까지 UNKNOWN이다. 넷째, 위임 포트가 두 스레드(이 데코레이터의 읽기와 명령)에서 불리는 것을 견디는지는 위임 포트의 몫이며 picasso 시험은 읽기를 시험 스레드에서 손으로 돌렸다.

    8건을 추가해 전체 시험이 2,374건에서 2,382건이 되었다. 새 시험 클래스 `PrefetchingFleetPortTest`에 4건(«운반 상태는 잠금 밖에서 읽고 다 읽은 값을 한 번만 준 뒤 다음 읽기를 건다», «찾기와 상태는 따로 읽고 한 운반의 읽기가 다른 운반을 막지 않는다», «답하지 않는 읽기는 기한이 지나면 답 없음으로 주고 받아 가지 않아 낡은 값은 버린다», «읽기가 돌지 않고 받아 가지 않은 자리는 기한이 지나면 다음에 물을 때 지운다»), 새 시험 클래스 `OffLockFleetReadTest`에 3건(«잠금 밖 운반 상태는 라운드 안에서 묻지 않고 다 읽은 값을 다음 라운드에 받는다», «끝난 운반은 받아 든 시각이 아니라 그 읽기가 받은 시각으로 끝낸다», «기한 넘게 답 없는 운반 상태 읽기는 펌프 실패로 남기고 단위를 그대로 둔다»), 기존 `ResumeFleetCancelTest`에 1건(«미리 읽는 플릿이면 붙기 전 취소는 찾기와 상태를 잠금 밖에서 읽고 다음 라운드에 붙는다»)을 두었다. 결함 주입 13건을 각각 지정 시험이 잡았다. 한계 레지스터 행 §15.237은 닫지 않고 좁혔으며 오픈 항목 수는 그대로 61개다.

277. **`AmrFleetPort.issueDispatch`와 `issueCancel`, `IssuingFleetPort`로 플릿 맡기기와 취소를 소비자 잠금 밖으로 빼고 운반 참조마다 한 차선에서 보낸다.**

    항목 276(§15.276)이 로봇 취소 네 자리를 잠금 밖으로 빼고 플릿 포트를 동기로 남겼다. 소유자가 2026-11-06에 플릿 포트의 범위를 명령 둘(맡기기 `dispatch`, 취소 `cancel`) 먼저로, 읽기 둘(`status`, `find`)은 다음 조각에서 선읽기 데코레이터 방식으로, 플릿 명령의 차선을 운반 참조마다 하나로 정했다. 이 항목이 넷째 조각이다.

    `AmrFleetPort`에 선택 메서드 `issueDispatch(order)`와 `issueCancel(reference, handle)`을 추가했다. 기본값은 널이고 널이면 미들웨어가 전처럼 `dispatch`와 `cancel`을 지금 부른다. `issueDispatch`가 돌려주는 `DispatchIssue`는 `Pending`, `Answered`(핸들과 보낸 시각, 핸들이 널이면 플릿이 맡지 않음), `NotSent`, `InDoubt`(실패와 보낸 시각)의 넷이고, `issueCancel`이 돌려주는 `FleetCancelIssue`는 `Pending`, `Answered`(`cancel`의 반환값), `Failed`의 셋이다. 운반 취소도 로봇 취소와 같은 까닭으로 미확정을 따로 두지 않는다. `issueCancel`이 참조를 받는 것은 운반 핸들(`TransportHandle`)이 id만 들고 있어 포트가 핸들만으로 그 운반의 차선을 고를 수 없기 때문이다. 기체마다 차선을 두던 `IssuingRobotPort`의 차선 처리를 새 내부 클래스 `CommandLanes`로 떼어 냈다. 로봇 데코레이터의 규칙(차선마다 명령 하나, 결과를 한 번만 주고 받아 간 뒤 차선을 푼다, 보낸 시각은 실제로 보낸 시각)은 바뀌지 않았다. 새 데코레이터 `IssuingFleetPort`가 같은 `CommandLanes`로 맡기기와 취소를 운반 참조마다 한 차선에서 보낸다. 한 명령이 차선을 쥔 동안 같은 운반의 다른 명령은 걸지 않고 맡기기는 `NotSent`, 취소는 `Failed`로 답하며, 답하지 않는 운반 하나가 다른 운반의 명령을 늦추지 않는다. 위임 포트가 `CommandNotSent`를 던지면 맡기기는 `NotSent`, 그 밖의 예외는 `InDoubt`다. `status`와 `find`는 위임 포트로 바로 간다. 첫 맡기기(`startUnit`)와 플릿 미확정 조회(`resolveDoubt`, 같은 참조의 재전송)가 `issueDispatch`로 보내고, 보내는 중이면 실행의 `fleetInFlight`에 둔다. 첫 맡기기의 결과는 `pumpRound` 머리에서, 미확정 조회의 결과는 `resolveDoubt` 머리에서 받는다. 결과 뒤 처리는 로봇 출발과 같은 함수(`startNotSent`, `startInDoubt`, `lookupNotSent`, `lookupFound`)와 운반 핸들을 잡는 `acceptTransport`를 쓴다. 맡기는 중에 걸린 취소는 접수를 본 뒤 그 운반에 보낸다. 맡기는 중인 운반 단위는 개정이 와도 바꾸지 않는데, 플릿 계약에 갱신이 없어 도는 운반과 같은 규칙이고 바꾸면 그 맡기기의 결과가 받을 단위를 잃기 때문이다. 운반 취소(`sendCancel`의 플릿 쪽)는 `cancelFleet`으로 보내고, 보내는 중이면 `fleetCancelInFlight`에 둔다. 결과는 로봇 취소와 함께 `pump` 머리의 `pollCancel`이 받고, 보내는 중인 동안 그 실행의 라운드는 진행하지 않으며, `pumpRound` 머리에서 다시 보낸 운반 취소가 보내는 중이 되면 그 라운드는 거기서 끝난다. 끝난 실행에 남은 운반 취소도 결과를 받아서 버리고, 그 전에는 `forget`이 이빅션을 거부한다. 한 `sendCancel`에서 로봇 쪽과 플릿 쪽이 함께 나가면 한쪽이 실패했을 때 보내는 중인 다른 쪽의 답만으로 닿았다고 내리지 않으며, 둘 다 답한 뒤에야 내린다(`CancelFlight`의 표시 이름을 `fleetFailed`에서 `otherFailed`로 바꾸었다). `issueDispatch`와 `issueCancel`을 쓰지 않는 포트는 동기 경로 그대로이고 기존 시험은 바뀌지 않았다. 계약 proto와 계약 SemVer 0.10.0, 작업 응답 형식, 판단 기록 형식은 바뀌지 않았다.

    남는 것은 네 가지다. 첫째, 플릿 읽기(`status`, `find`)는 여전히 소비자 잠금 아래 동기이므로 행 §15.237은 닫지 않고 좁힌다. 다음 조각에서 선읽기 데코레이터로 옮긴다. 둘째, picasso-ops 기본 결선은 아직 이 데코레이터를 쓰지 않으며 결선 안내 `handoff/picasso-ops/issuing-robot-port.md`에 플릿 쪽을 더했다. 셋째, 적정 기한 예산은 현장 실측 전까지 UNKNOWN이다. 넷째, 한 `sendCancel`에서 로봇 취소와 운반 취소가 함께 잠금 밖으로 나가는 경우에는 전용 시험이 없다.

    9건을 추가해 전체 시험이 2,365건에서 2,374건이 되었다. 새 시험 클래스 `IssuingFleetPortTest`에 2건(«맡기기와 취소는 운반마다 한 차선을 쓰고 다른 운반은 막지 않는다», «보내지 않았다는 실패와 답 없는 실패를 가르고 보낸 시각을 준다»), 새 시험 클래스 `OffLockFleetTest`에 7건(«잠금 밖 맡기기는 결과를 기다리지 않고 다음 라운드에 받는다», «맡기는 중에 걸린 취소는 접수를 본 뒤 그 운반에 보낸다», «닿지 못한 잠금 밖 운반 취소는 다음 라운드에 다시 보낸다», «보내는 중인 플릿 미확정 조회는 다음 라운드에 결과를 받아 풀린다», «맡기는 중에 온 개정은 운반 단위를 바꾸지 않는다», «다시 보내는 운반 취소가 나가는 중에 끝난 운반은 그 답을 받은 뒤 중단을 적는다», «끝난 실행에 남은 운반 취소는 이빅션을 막고 다음 라운드에 차선을 푼다»)을 두었다. 결함 주입 10건을 각각 지정 시험이 잡았다. 한계 레지스터 행 §15.237은 닫지 않고 좁혔으며 오픈 항목 수는 그대로 61개다.

276. **`RobotPort.issueCancel`과 `IssuingRobotPort`로 로봇 취소 네 자리를 소비자 잠금 밖으로 빼고 보내는 중에는 그 실행의 라운드 진행을 멈춘다.**

    항목 275(§15.275)는 §15.237의 둘째 조각으로 `StartTask` 경로를 잠금 밖으로 뺐고, 취소와 플릿 포트를 동기로 남겼다. 소유자가 2026-11-06에 다음 조각의 범위를 로봇 취소 네 자리로(플릿 포트는 그다음 조각), 송신 자리를 출발과 같은 차선으로 정했다. 이 항목이 그 셋째 조각이다.

    `RobotPort`에 선택 메서드 `issueCancel(robotId, handle)`을 추가했다. 기본값은 널이고 널이면 미들웨어가 전처럼 `cancel`을 지금 부른다. 돌려주는 `CancelIssue`는 `Pending`(보내는 중), `Answered`(하위가 답함, 상태든 거부든), `Failed`(닿았는지 모름)의 셋이다. 출발과 달리 미확정을 따로 두지 않는데, 취소는 두 번 나가도 물리 작업이 둘이 되지 않아 미들웨어가 실패를 닿지 않은 것으로 읽고 다시 보내기 때문이다. 미들웨어가 `cancel`을 동기로 부른 결과도 같은 셋으로 감싼다. `IssuingRobotPort`는 출발과 취소를 기체마다 한 차선으로 보낸다. 차선의 키는 (종류, 태스크 id, 리비전)이다. 한 명령이 차선을 쥔 동안 같은 기체의 다른 명령은 걸지 않고, 출발은 `NotSent`, 취소는 `Failed`(`CommandNotSent`)로 답한다. 같은 기체에 명령 둘이 함께 나가 순서가 뒤바뀌지 않게 하려는 것이다. 결과를 한 번만 주고 받아 간 뒤 차선을 놓는 규칙은 출발과 같다. 미들웨어에서는 도는 단위의 취소(`sendCancel`), 키로 만든 핸들의 취소(`cancelByKey`), 운영자 보류 중 다시 보내기(`resendHeldCancel`), 재기동 뒤 붙기 전의 취소(`attachForCancel`)가 함께 쓰는 `cancelRobot`으로 보낸다. 보내는 중이면 그 송신을 실행의 `cancelInFlight`에 둔다. 결과는 다음 라운드 `pump` 머리의 `pollCancel`이 같은 키로 다시 물어 받고, 받은 결과로 그 자리의 뒷일을 `settleCancel`이 한다. 그 사이 단위가 다른 자리로 갔으면 결과만 버린다. 각 자리의 뒷일은 동기 경로의 응답 뒤 처리와 같은 함수(`attachByKey`, `heldCancelAnswered`, `attachUnit`, `cancelReached`, `cancelNotReached`)를 쓴다. 보내는 중인 취소가 있는 동안 그 실행의 라운드는 진행하지 않는다. 단위의 종료와 중단 보고(`CancelReport`의 거부 사유)가 취소의 답에 달렸기 때문이다. 같은 이유로 `pumpRound` 머리에서 닿지 못한 취소를 다시 보내 보내는 중이 되면 그 라운드는 거기서 끝난다(같은 라운드에 단위가 끝나 중단을 적으면 그 답 없이 닿지 못했다고 적는다). 키로 만든 핸들의 취소가 보내는 중이면 `resolveDoubt`는 그 답을 받을 때까지 단위를 미확정인 채 둔다(아래 관측이 먼저 종결하면 붙을 자리가 사라진다). 붙기 전의 취소가 보내는 중이면 `waitUnattached`로 기다린다. `sendCancel`에서 로봇 쪽이 보내는 중이고 같은 호출의 플릿 취소가 실패했으면, 로봇이 답해도 닿았다고 내리지 않는다(`CancelFlight.fleetFailed`). 끝난 실행에 남은 취소도 `pollCancel`이 결과를 받아서 버린다. 받아 가지 않으면 포트의 그 기체 차선이 풀리지 않기 때문이다. `forget`은 보내는 중인 취소가 있으면 이빅션을 거부한다. `issueCancel`을 쓰지 않는 포트는 동기 경로 그대로다. 기존 시험 중 `IssuingRobotPort`를 쓰는 `OffLockStartTest`의 2건(«보내는 중에 걸린 취소는 접수를 본 뒤 그 태스크에 보낸다», «개정 갱신을 보내는 중에 걸린 취소는 그 결과 뒤에 보낸다»)은 취소도 잠금 밖으로 나가게 되어 송신을 한 번 더 돌린 뒤 보낸 취소를 보도록 고쳤다. 계약 proto와 계약 SemVer 0.10.0, 작업 응답 형식, 판단 기록 형식은 바뀌지 않았다.

    남는 것은 다섯 가지다. 첫째, 플릿 포트(`dispatch`, 상태 조회, `find`, `cancel`)는 여전히 소비자 잠금 아래 동기이므로 행 §15.237은 닫지 않고 좁힌다. 둘째, picasso-ops 기본 결선은 아직 이 데코레이터를 쓰지 않으며 결선 안내 `handoff/picasso-ops/issuing-robot-port.md`에 취소를 더했다. 셋째, 적정 기한 예산은 현장 실측 전까지 UNKNOWN이다. 넷째, 취소를 보내는 중에는 그 실행의 라운드가 결과를 받을 때까지 멈추며 그 시간은 위임 포트의 기한이 묶는다. 동기 경로도 같은 시간 동안 라운드 전체를 막았다. 다섯째, 운영자 보류 중 다시 보내기와 붙기 전의 취소의 잠금 밖 경로에는 전용 시험이 없다(두 자리는 동기 경로와 같은 뒷일 함수를 쓰고 그 동기 경로 시험은 그대로 통과한다).

    시험 8건을 추가해 전체 시험이 2,357건에서 2,365건이 되었다. `IssuingRobotPortTest`에 2건(«취소는 출발과 같은 차선을 쓰고 결과를 한 번 준다», «답 없는 취소는 실패로 준다»), 새 시험 클래스 `OffLockCancelTest`에 6건(«잠금 밖 취소는 답을 기다리지 않고 다음 라운드에 받는다», «닿지 못한 잠금 밖 취소는 다음 라운드에 다시 보낸다», «다시 보내는 취소가 나가는 중에 끝난 단위는 그 답을 받은 뒤 중단을 적는다», «끝난 실행에 남은 취소는 이빅션을 막고 다음 라운드에 차선을 놓는다», «키로 만든 핸들의 잠금 밖 취소는 답을 받은 뒤 미확정 단위를 붙인다», «유예가 지난 미확정 단위도 키로 만든 핸들의 취소가 보내는 중이면 운영자 보류에 두지 않는다»)을 두었다. 결함 주입 9건을 각각 지정 시험이 잡았다. 그중 키로 만든 핸들의 취소가 보내는 중일 때 미확정 단위를 그대로 두는 멈춤에 넣은 1건은 유예 안의 시험으로는 잡히지 않아, 유예가 지난 경우의 시험을 더한 뒤 잡았다. 한계 레지스터 행 §15.237은 닫지 않고 좁혔으며 오픈 항목 수는 그대로 61개다.

275. **`RobotPort.issue`와 `IssuingRobotPort`로 `StartTask` 경로를 소비자 잠금 밖으로 빼고 보내는 중의 취소와 개정을 결과 뒤로 미룬다.**

    항목 274(§15.274)는 §15.237 검토가 정한 첫 조각으로, 동기 경로에서 송신 결과를 보내지 않음, 나갔는지 모름, 답함의 셋으로 나누었다. 그 항목이 남긴 것은 명령이 여전히 소비자 잠금 아래 동기로 나간다는 것과, 송신을 어디서 돌릴지와 조각 범위를 정하지 않았다는 것이었다. 소유자가 2026-11-06에 송신 자리를 포트 메서드와 데코레이터로, 범위를 `StartTask` 경로 먼저로 정했다. 이 항목이 그 둘째 조각이다.

    `RobotPort`에 선택 메서드 `issue(robotId, taskId, revision, skillType, parameters)`를 추가했다. 기본값은 널이고, 널이면 미들웨어는 전처럼 `start`를 지금 부른다. 돌려주는 `Issue`는 `Pending`(보내는 중), `Answered`(응답과 보낸 시각), `NotSent`(보내지 않음), `InDoubt`(나갔는지 모름, 실패와 보낸 시각)의 넷이다. 새 데코레이터 `IssuingRobotPort`가 받은 출발을 자기 스레드(기본은 송신마다 가상 스레드, 실행기를 넘겨받을 수도 있음)에서 위임 포트의 `start`로 보낸다. 기체마다 차선이 하나라 한 출발이 차선을 쥔 동안 같은 기체의 다른 출발은 걸지 않고 `NotSent`로 답하며, 답하지 않는 기체는 제 차선만 붙든다. 결과는 한 번만 주고 받아 갈 때까지 들며, 받아 간 뒤 같은 키로 다시 물으면 새 송신이다. 위임 포트가 `CommandNotSent`를 던지면 `NotSent`, 그 밖의 예외는 `InDoubt`이고, 보낸 시각은 실제로 보낸 시각이다. 취소, 감시(`watch`), 스냅숏 같은 나머지 호출은 위임 포트로 바로 간다. 미들웨어의 첫 출발(`startUnit`), 개정 갱신(`sendRevisionUpdate`), 미확정 조회(`resolveDoubt`) 세 자리가 `issue`로 보낸다. 보내는 중이면 그 시도를 실행의 `inFlight`에 두고 라운드를 끝내며, 다음 라운드에 같은 키로 다시 물어 결과를 받는다. 첫 출발과 개정 갱신은 `pumpRound` 머리에서, 미확정 조회는 `resolveDoubt` 머리에서 받는다. 결과를 받은 뒤의 처리는 항목 274의 세 경우 그대로이고, 단위 상태는 새로 두지 않았다. 요청 시각은 포트가 준 보낸 시각으로 고치되, 재기동 전 요청 시각을 넘겨받은 시도는 고치지 않으며(§15.221), 동기 경로는 보낸 시각이 없어 전처럼 예약한 시각이다. `sendCancel`은 보내는 중인 출발이 있으면 보내지 않고 취소 요청만 남긴다. 보내면 기체에 아직 없는 태스크나 앞 단위의 핸들로 나가고 같은 기체에 명령 둘이 함께 나가기 때문이다. 취소는 결과를 받은 쪽이 보내는데, 첫 출발이 접수되면 그 태스크에, 개정 갱신의 결과 뒤 단위가 도는 중이면 그 태스크에, 조회로 찾으면 기존처럼 보내고, 미확정이면 `resolveDoubt`의 취소 경로로 간다. 보내지 않은 출발은 지울 것이 없다. 첫 출발을 보내는 중인 `PENDING` 단위는 개정이 오면 새 단위로 바꾸지 않고 그 자리에서 리비전, 파라미터, 기대 식별, 출발지, 목적지를 고친다. 바꾸면 그 출발의 결과가 받을 단위를 잃기 때문이다. 갱신은 접수 뒤로 미루어 `deferredUpdate`에 보내는 중인 리비전을 둔다. 도는 단위나 미확정 단위도 그 단위의 송신이 보내는 중이면 갱신을 미루며, 취소가 걸렸으면 고치지 않는다. 판단 기록은 재기동 뒤 같은 태스크를 다시 내지 않게 하려고 보내는 중인 출발을 태스크 id와 요청 시각이 있는 낸 것으로 적는다. `PrefetchingRobotPort`의 KDoc에는 출발을 잠금 밖으로 보내는 것이 `IssuingRobotPort`라고 적었으며, 두 데코레이터 모두 나머지 호출을 위임하므로 어느 쪽으로 겹쳐 감싸도 된다. `issue`를 쓰지 않는 포트는 동기 경로 그대로이고 기존 시험은 바뀌지 않았다. 계약 proto와 계약 SemVer 0.10.0, 작업 응답 형식, 판단 기록 형식은 바뀌지 않았다. 미룬 갱신을 `pumpRound`에서 보내 보내는 중이 되면 그 라운드는 거기서 끝내고 단위를 더 읽지 않는다. 이어서 단위를 읽으면 끝난 단위가 그 라운드에 바로 종결하는 경우 다음 단위의 출발이 나가 보내는 중인 갱신의 `inFlight`를 덮어 그 결과를 받을 자리가 사라지기 때문이다(자동 보안 검토가 지적한 자리다). 다만 이 멈춤은 보내는 중인 것이 개정 갱신일 때만 하는데, 보내는 중인 미확정 조회는 `resolveDoubt` 머리에서 결과를 받으므로 이를 막으면 그 실행이 미확정에서 풀리지 않기 때문이다(같은 자동 보안 검토가 뒤이어 지적했다).

    첫째, 취소(`cancel`, 취소 재전송, 키로 만든 핸들의 취소)와 플릿 포트는 여전히 소비자 잠금 아래 동기이므로 행 §15.237은 닫지 않고 좁힌다. 둘째, picasso-ops 기본 결선은 아직 이 데코레이터를 쓰지 않는다. 결선 안내는 `handoff/picasso-ops/issuing-robot-port.md`에 두었고 반입 여부와 시점은 picasso-ops가 정한다. 셋째, 적정 기한 예산은 현장 실측 전까지 UNKNOWN이다. 넷째, `IssuingRobotPort`의 차선은 결과를 받아 가야 풀리는데 결과를 버리고 차선을 풀면 같은 출발이 두 번 나갈 수 있어 풀지 않았으므로, 담는 쪽이 송신 중에 미들웨어 인스턴스를 버리고 포트를 계속 쓰면 같은 키로 다시 묻기 전까지 그 기체의 차선이 막힌다. 다섯째, 출발이나 갱신을 보내는 중에는 그 실행의 취소 송신과 연결 끊김 검사가 결과를 받을 때까지 미뤄지며, 그 시간은 위임 포트의 기한이 묶는다(기한이 지나면 `InDoubt`). 동기 경로도 같은 시간 동안 라운드 전체를 막았다.

    시험 11건을 추가해 전체 시험이 2,346건에서 2,357건이 되었다. 새 시험 클래스 `IssuingRobotPortTest`에 3건(«결과는 한 번만 주고 받아 간 뒤 같은 키는 새 송신이다», «차선을 쥔 동안 같은 기체의 다른 출발은 보내지 않고 다른 기체는 막지 않는다», «보내지 않았다는 실패와 답 없는 실패를 가르고 보낸 시각을 준다»), 새 시험 클래스 `OffLockStartTest`에 8건(«잠금 밖 출발은 결과를 기다리지 않고 다음 라운드에 받는다», «보내는 중인 출발은 판단 기록에 낸 것으로 남는다», «보내는 중에 걸린 취소는 접수를 본 뒤 그 태스크에 보낸다», «개정 갱신을 보내는 중에 걸린 취소는 그 결과 뒤에 보낸다», «보내는 중에 온 개정은 단위를 바꾸지 않고 접수 뒤 갱신으로 보낸다», «미룬 갱신을 보내는 라운드는 단위를 더 진행하지 않는다», «보내는 중인 미확정 조회는 다음 라운드에 결과를 받아 풀린다», «답 없는 출발의 요청 시각은 실제로 보낸 시각이다»)을 두었다. 결함 주입 13건을 각각 지정 시험이 잡았다. 한계 레지스터 행 §15.237은 닫지 않고 좁혔으며 오픈 항목 수는 그대로 61개다.

274. **동기 경로에서 하위로 보내지 않은 명령을 `CommandNotSent`로 갈라 미확정과 구분한다.**

    항목 245(§15.245)는 `ClientRobotPort`에 기체별 차단 창을 두어, 차단 창 안이면 `StartTask`와 `CancelTask` 같은 단건 호출을 하위로 보내지 않고 바로 gRPC `UNAVAILABLE`로 실패시켰다. 그러나 미들웨어(`Middleware`)는 `StartTask`의 예외를 하나도 가르지 않고 모두 나갔는지 모르는 것으로 읽었다. 이 때문에 차단 창 안이라 하위로 아예 나가지 않은 첫 출발이 미확정(`IN_DOUBT`)이 되어 상류에 미확정으로 알려졌고, 같은 `task_id`와 `revision`의 `StartTask`를 다시 보내는 미확정 조회도 차단 창 안에서는 나가지 않았는데 조회 횟수로 셌으며, 개정 갱신도 나가지 않았는데 그 단위를 미확정으로 옮겼다. 이 결함은 명령을 소비자 잠금 밖으로 보내는 방향을 Claude, Fable, Codex에게 물은 §15.237 검토에서 Fable이 찾았다. 이 항목은 그 검토가 정한 첫 조각으로, 동기 경로에서 송신 결과를 보내지 않음, 나갔는지 모름, 답함의 셋으로 가른다.

    `RobotPort` 계약 옆에 예외 `CommandNotSent`를 추가해 요청이 하위로 나가지 않았음을 알 때만 던지게 하고, `RobotPort.start`의 KDoc에 두 읽기를 적었다. 연결이 요청을 보낸 뒤 끊겨도 같은 코드가 오므로 gRPC `UNAVAILABLE`이라는 것만으로는 보내지 않은 것이 아니다. `ClientRobotPort`의 차단 창은 이제 `UNAVAILABLE` 대신 `CommandNotSent`를 던지며, 스냅숏, 재생, 케이퍼빌리티 읽기는 전처럼 예외를 널로 접으므로 달라지지 않는다. 첫 출발(`startUnit`)에서 `CommandNotSent`가 나면 단위를 `PENDING` 그대로 두고 도는 단위를 비우며, 태스크 id, 요청 시각, 재기동 뒤 다시 세운 실행이 넘겨받은 요청 시각(`restoredRequests`), 붙지 않은 태스크(`unattached`)를 이 시도 앞의 값으로 되돌린다. 판단 기록은 태스크 id가 있으면 낸 것으로 읽어 요청 시각을 남기므로, 되돌리지 않으면 보내지 않은 시각이 재기동 뒤 미확정 유예의 기준이 되기 때문이다. 다음 라운드가 결함·허가 게이트를 다시 지나 처음부터 내고 요청 시각은 그때 실제로 보낸 시각으로 찍히며, 단위 사정(note)에는 `START_NOT_SENT`를 한 번만 남긴다. 개정 갱신(`sendRevisionUpdate`)에서 `CommandNotSent`가 나면 단위가 `RUNNING`일 때 갱신을 미루고(`deferredUpdate`) 사정 `UPDATE_NOT_SENT`를 남기며, 허가가 막은 갱신과 같은 경로라 다음 라운드에 발행 게이트를 다시 지나 보낸다. 미확정 조회(`resolveDoubt`)에서 `CommandNotSent`가 나면 허가가 막은 조회와 같은 규칙으로 조회 간격(2초)은 쓰되 조회 횟수는 쓰지 않고 미확정인 채 기다린다. 그 밖의 예외는 전처럼 미확정이고, 이미 던지면 다음 라운드에 다시 보내는 취소 경로(`cancelUnsent`)와 플릿 포트는 바꾸지 않았다. 계약 proto와 계약 SemVer 0.10.0, 작업 응답 형식, 판단 기록 형식도 바뀌지 않았다.

    남는 것은 둘이다. 첫째, 명령은 여전히 소비자 잠금 아래 동기로 나가므로 한계 레지스터 행 §15.237은 닫지 않으며, 송신을 어디서 돌릴지와 조각 범위는 아직 정하지 않았다. 둘째, 어댑터 호스트가 보내지 못한 접수(`LINK_ERROR`)에 답하는 gRPC `UNAVAILABLE`은 클라이언트에서 요청을 보낸 뒤 끊긴 연결의 `UNAVAILABLE`과 가를 수 없어 미들웨어는 여전히 미확정으로 읽는다. 이를 가르려면 계약 수준의 표식이 필요하다.

    시험 5건을 추가해 전체 시험이 2,341건에서 2,346건이 되었다. 새 시험 클래스 `CommandNotSentTest`에 «포트가 출발을 보내지 않았다고 하면 미확정으로 두지 않고 다음 라운드에 다시 낸다», «보내지 않은 출발은 판단 기록에 낸 것으로 남지 않는다», «포트가 개정 갱신을 보내지 않았다고 하면 미확정으로 두지 않고 다음 라운드에 보낸다», «미확정 조회가 나가지 않으면 조회 횟수를 쓰지 않고 유예가 지나도 조회를 기다린다» 4건을 두었고, `JudgmentRecordTest`에 «재기동 뒤 첫 출발을 보내지 못해도 넘겨받은 요청 시각을 잃지 않는다» 1건을 추가했다. `PortBreakerTest`는 차단 창 안의 취소와 출발이 `CommandNotSent`로 실패하는지 보도록 고쳤고, 결함 주입 10건을 각각 지정 시험이 잡았다. 한계 레지스터 행 §15.237은 닫지 않았고 오픈 항목 수는 그대로 61개다.

273. **Spot, Digit, G1 어댑터의 사우스바운드 쓰기 호출에서도 나갔는지 모르는 실패를 `SouthboundInDoubt`와 `sendFailure`를 통해 `LINK_IN_DOUBT`로 구분하도록 바꿨다.**

    항목 272(§15.272)는 Orbit 파견만 나갔는지 모르는 실패를 `LINK_IN_DOUBT`로 구분했다. 반면 Spot, Digit, G1 어댑터는 사우스바운드 쓰기 호출의 모든 실패를 `LINK_ERROR`로 냈고, 호스트는 `LINK_ERROR`를 보내지 못한 것으로 읽어 gRPC `UNAVAILABLE`로 답하고 같은 접수가 다시 오면 다시 보낸다. 응답 시간 초과나 받는 도중 끊긴 연결처럼 요청이 나간 뒤 답을 못 받은 실패도 그 안에 들어 있어, 같은 접수가 다시 오면 같은 지시를 두 번 보낼 수 있었다. 한계 레지스터 행 §15.220은 이 셋 가운데 Spot과 Digit만 적었고 G1은 빠져 있었으나 같은 자리가 있었다. Orbit은 HTTP 링크 구현(`OrbitHttpLink`)이 저장소에 있어 예외 종류로 구분할 수 있었지만, Spot, Digit, G1의 링크는 인터페이스(`SpotLink`, `DigitLink`, `G1Link`)뿐이고 실 전송 구현이 저장소에 없어 구분하는 일을 링크 구현에 맡기고 그 표식을 공통 모듈에 두었다.

    먼저 `adapter-core`에 예외 `SouthboundInDoubt`와 함수 `sendFailure(cause)`를 추가했다. 링크 구현은 요청을 보낸 뒤 답을 못 받으면 `SouthboundInDoubt`로 실패를 알리고, 연결을 못 맺은 것처럼 요청이 나가기 전의 실패는 이 예외를 쓰지 않는다. `sendFailure(cause)`는 `SouthboundInDoubt`면 `LINK_IN_DOUBT`를 주고 그 밖은 `LINK_ERROR`를 주며, 세계 모델이나 취득 케이퍼빌리티, 그래프 받기 같은 읽기 호출은 다시 물어도 로봇이 두 번 움직이지 않으므로 늘 `LINK_ERROR`로 두고 쓰기 호출에만 이 함수를 쓴다. 이에 따라 `SpotAdapter`는 취득 요청(`AcquireData`), 이동 지시(`se2Velocity`)와 미션 재생(`PlayMission`)의 접수, 멈춤·일시정지·갱신 등 조작이 쓰는 리스 결과(`LeaseResult.Failed`), 취득 취소(`CancelAcquisition`)의 실패를 `sendFailure`로 옮겼다. 미션 적재(`LoadMission`)만으로는 로봇이 움직이지 않고 다시 적재하면 같은 미션을 다시 올릴 뿐이라 접수의 적재 실패는 나갔는지 몰라도 `LINK_ERROR`로 두었으며, 미션 갱신이 반만 적용된 경우의 안내 문구는 나갔는지 모르면 새 미션이 돌고 있을 수 있다고 적고 아무 미션도 돌지 않는다고 적지 않는다. `DigitAdapter`는 액션 전송(`moveFor`, `gotoNamed`, `pickAndPlace`)과 `remove-action`의 실패를 `sendFailure`로 옮겼고, 갱신에서 앞 액션을 지운 뒤 새 액션이 나갔는지 모르면 `NEEDS_INTERVENTION`으로 두고 안내 문구에 새 액션이 돌고 있을 수 있다고 적으며 로봇은 아무것도 하지 않습니다라고 적지 않는다. `G1Adapter`는 `SetVelocity`의 접수, 갱신, 멈춤(`StopMove`)의 실패를 `sendFailure`로 옮겼고, 로봇이 에러 코드로 답한 것(`UnitreeApiException`)은 전처럼 `VENDOR_REJECTED`로 둔다. 호스트 쪽은 바뀌지 않아 항목 272가 넣은 `HostedRobot`의 `inDoubt`가 어댑터를 가리지 않고 `LINK_IN_DOUBT`로 끝난 접수를 남기며 같은 접수가 다시 와도 다시 보내지 않고 gRPC `UNKNOWN`으로 답하며, 조작의 `LINK_IN_DOUBT`도 전처럼 `UNKNOWN`이다. 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았다.

    남는 것은 세 가지다. 첫째, Spot, Digit, G1의 실 링크 구현이 저장소에 없어 그 구현이 요청을 보낸 뒤의 실패를 `SouthboundInDoubt`로 내는지는 링크 인터페이스의 KDoc에 적힌 의무일 뿐 시험으로 확인되지 않는다. 둘째, 계약에 칸이 없어 ADR 51 결정 5의 세대 버전 대조가 남는다. 셋째, 같은 링크를 쓰는 호출의 현장 대기 한도는 미확인이다.

    시험은 15건을 추가해 전체 시험이 2,326건에서 2,341건이 되었다. `SpotAdapterTest`에 이동 지시, 미션 재생, 취득 요청이 나갔는지 모르면 미상으로 거부하는 시험, 이동 지시를 보내지 못했으면 링크 오류로 내는 시험, 미션 적재는 나갔는지 몰라도 링크 오류로 내는 시험, 멈춤과 취득 취소가 나갔는지 모르면 미상으로 답하는 시험, 갱신의 재생이 나갔는지 모르면 미션이 안 돈다고 적지 않는 시험까지 8건을 더했다. `DigitAdapterTest`에 액션이 나갔는지 모르면 미상으로 거부하는 시험, 보내지 못했으면 링크 오류로 내는 시험, 지우기가 나갔는지 모르면 미상으로 내는 시험, 지운 뒤 새 액션이 나갔는지 모르면 아무것도 안 한다고 적지 않는 시험까지 4건을 더했고, `G1AdapterTest`에 속도 지시, 멈춤, 갱신이 나갔는지 모르면 미상으로 내는 시험 3건을 더했다. 결함 주입 17건을 각각 지정 시험이 잡았다. 한계 레지스터 행 §15.220은 닫지 않고 좁혔으며 오픈 항목 수는 그대로 61개다.

272. **사우스바운드 호출이 나갔는지 모르는 실패를 `LINK_IN_DOUBT`로 구분해 호스트가 같은 접수를 다시 보내지 않도록 바꿨다.**

    한계 레지스터 행 §15.220이 남긴 항목 가운데 하나는 보냈는지 모르는 실패의 구분이었다. 어댑터 호스트는 접수 송신이 어댑터의 링크 오류(`LINK_ERROR`)로 끝나면 보내지 못한 것으로 읽어 자리를 비우고 gRPC `UNAVAILABLE`로 답하며, 같은 `task_id`와 `revision`을 가진 같은 접수가 다시 오면 다시 보낸다. 그런데 Orbit 어댑터는 파견 요청의 모든 예외를 `DispatchResult.Unreachable`로 접어 `LINK_ERROR`로 냈고, 그 안에는 응답 시간 초과처럼 요청이 나간 뒤의 실패도 들어 있었다. 이 때문에 같은 접수가 다시 오면 같은 미션을 두 번 파견할 수 있었다.

    어댑터 포트의 거부 이유에 사우스바운드 호출이 나갔는지 모른다는 뜻의 `LINK_IN_DOUBT`를 추가했다. 다시 보내도 되는 `LINK_ERROR`와 달리 `LINK_IN_DOUBT`는 다시 보내면 같은 일을 두 번 시킬 수 있어 다음 행동이 다르다. Orbit에서는 `DispatchResult`에 `InDoubt`를 추가해, 파견 요청이 답 없이 실패했을 때 연결을 못 맺은 예외(`ConnectException`, `HttpConnectTimeoutException`, `UnknownHostException`)만 `Unreachable`로 두고 응답 시간 초과나 받는 도중 끊김 같은 나머지는 `InDoubt`로 내며, `OrbitAdapter`는 `InDoubt`를 `LINK_IN_DOUBT`로 거부하고 도는 태스크로 적지 않는다. 호스트에서는 `HostedRobot`이 호스트 잠금 아래에서만 만지는 `inDoubt`에 `LINK_IN_DOUBT`로 끝난 접수와 그 답을 남겨 같은 접수가 다시 오면 어댑터에 보내지 않고 같은 답을 주며, 받았는지 모르는 것을 도는 태스크로 적지 않기 위해 장부에는 올리지 않는다. 이때 답은 다시 보내도 된다는 뜻으로 읽히는 `UNAVAILABLE` 대신 gRPC `UNKNOWN`으로 내고 조작 경로(`HostServices`)도 `LINK_IN_DOUBT`를 `UNKNOWN`으로 내며, 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않고 소비자가 받는 gRPC 상태 하나만 달라진다.

    남는 것은 세 가지다. 첫째, Orbit 파견 밖의 사우스바운드 송신인 Spot과 Digit의 명령 송신 및 조작은 여전히 링크 오류를 보내지 못한 것으로 읽는다. 둘째, 계약에 칸이 없어 ADR 51 결정 5의 세대 버전 대조가 남는다. 셋째, 같은 링크를 쓰는 호출은 여전히 서로 기다리며 현장에 맞는 대기 한도는 미확인이다.

    시험 4건을 추가해 전체 시험은 2,322건에서 2,326건이 되었다. 실 포트를 쓰는 `HostInDoubtSendTest`에 «나갔는지 모르는 접수는 UNKNOWN 으로 답하고 같은 접수가 다시 와도 보내지 않는다»와 «보내지 못한 접수는 UNAVAILABLE 로 답하고 같은 접수가 다시 오면 다시 보낸다» 2건, 스텁 서버가 요청을 받고 응답 시간을 넘기는 `OrbitHttpLinkTest`에 «파견 요청이 나간 뒤 답이 없으면 못 닿음이 아니라 미상이다» 1건, `OrbitAdapterTest`에 «파견이 나갔는지 모르면 링크 오류로 접지 않는다» 1건을 더했다. HTTP 링크가 모든 실패를 못 닿음으로 접음, 어댑터가 미상을 링크 오류로 접음, 어댑터가 미상을 도는 태스크로 적음, 호스트가 미상을 `UNAVAILABLE`로 답함, 호스트가 미상을 남기지 않아 다시 보냄, 호스트가 미상을 장부에 적음, 호스트가 링크 오류도 남겨 다시 보내지 않음의 결함 주입 7건을 각각 지정 시험이 잡았다. 한계 레지스터 행 §15.220은 닫지 않고 좁혔으며 오픈 항목 수는 그대로 61개다.

    (좁힘, §15.273) 이 항목이 첫째로 남긴 Spot과 Digit의 사우스바운드 명령 송신 및 조작을 G1과 함께 다뤄, 쓰기 호출에서 나갔는지 모르는 실패를 `SouthboundInDoubt`와 `sendFailure`를 통해 `LINK_IN_DOUBT`로 내도록 바꿨다. 실 링크 구현이 저장소에 없어 요청이 나간 뒤의 실패를 `SouthboundInDoubt`로 내는지는 링크 인터페이스의 KDoc에 적힌 의무로 남는다.

271. **상태 발행 때 생존 보고에 싣는 로봇 소프트웨어와 사이트 이름 요약을 펌프의 읽기 단계에서 링크 잠금만 쥐고 미리 읽어 두도록 바꿨다.**

    한계 레지스터 행 §15.220이 남긴 항목 가운데 하나이자 §15.269가 넷째 남은 일로 적은 것은 상태 발행 때 생존 보고가 묻는 사이트 이름 조회였다. 상태 발행은 펌프의 `apply` 끝에 호출되는 `publishStateIfDue`를 통해 호스트 잠금 아래에서 일어나는데, 발행을 감싼 `IngestBridge`가 그 자리에서 생존 보고의 로봇 소프트웨어와 사이트 이름 요약을 어댑터에 물었다. Spot은 그래프를 받고 Digit은 물체 목록을 읽으며 Orbit은 플릿의 미션 목록 HTTP를 부르는 등 사이트 이름 조회는 사우스바운드 왕복이므로, 링크가 답하지 않는 동안 호스트 잠금이 잡혀 같은 프로세스에서 링크를 쓰지 않는 스냅숏 등의 RPC까지 기다려야 했다.

    이번 §15.271에서는 `HostedRobot`이 생존 보고의 내용인 `LivenessFacts`(로봇 소프트웨어, 사이트 이름 요약)를 `liveness`로 들고 있게 했다. 호스트 잠금 아래에서 펌프가 읽을 대상을 정하는 `plan` 단계 때 상태 발행 차례이거나 아직 한 번도 읽지 않았으면 생존 보고의 내용도 함께 읽기로 정하고, 링크 잠금만 쥐는 `read`가 어댑터에 물은 뒤, 호스트 잠금 아래의 `apply`가 그 값을 `liveness`에 적고 상태를 발행한다. 발행 차례가 아니면 묻지 않으며, 상태 발행과 `ONLINE`·`OFFLINE` 연결 보고는 어댑터에 묻지 않고 읽어 둔 값을 싣는다. 기동 때는 `AdapterHost.start`가 포트를 열기 전에 호스트 잠금 밖에서 `primeLiveness`로 한 번 읽어 두어 첫 `ONLINE` 연결 보고도 비지 않게 했고, 어댑터를 받아 발행 때 묻던 `HostUplink.wrap`의 두 오버로드를 지우고 기체를 받아 `liveness`를 넘기는 람다 하나로 정리해 `OrbitLauncher`가 쓰도록 바꿨다. 대가로 생존 보고에 실리는 요약은 그 상태 발행 앞 읽기의 값이며, 아직 읽지 못했으면 두 값 모두 못 물어본 상태를 뜻하는 `null`이 된다.

    남는 것은 세 가지다. 첫째, 어댑터의 링크 오류는 보내지 못한 것으로 읽히지만 Orbit의 `Unreachable`은 파견 POST를 보낸 뒤의 실패도 포함하므로 보냈는지 모르는 실패를 구분하지 않는다. 둘째, 계약에 칸이 없어 ADR 51 결정 5의 세대 버전 대조가 남아 있다. 셋째, 같은 플릿 뒤 다른 기체의 명령이나 펌프의 읽기처럼 같은 링크를 쓰는 호출은 여전히 서로 기다리며 현장에 맞는 대기 한도는 미확인 상태다. 계약 proto와 계약 SemVer 0.10.0은 바뀌지 않았다.

    시험은 `HostLivenessReadTest` 2건을 추가해 전체 2,320건에서 2,322건이 되었다. 실 포트와 펌프 스레드가 있는 배치 모양에서 상태 발행 차례의 사이트 이름 조회를 붙든 채 스냅숏이 500ms 안에 답하는지 재는 시험과, 시계를 쥐고 기동 때 생존 보고의 내용을 읽어 두고 상태 발행 차례에만 다시 읽는지 조회 횟수와 읽어 둔 요약으로 확인하는 시험이다. 발행할 때 어댑터에 다시 묻고 펌프는 읽지 않는 결함은 스냅숏 시험이 잡았고, 기동 때 읽어 두지 않는 결함과 펌프마다 읽는 결함 및 발행 차례에 다시 읽지 않는 결함 등 총 4건의 결함 주입을 각각 지정 시험이 잡았다. 레지스터 행 §15.220은 닫지 않고 범위를 좁혔으며 오픈 항목 수는 그대로 61개다.

270. **설계 문서 변경 이력과 현재 상태 설명 문서의 낡은 계약 SemVer 표기를 바로잡았다.**

    항목 263(§15.263)은 스냅숏 응답에 결함 관측 열거형 `FaultObservability`를 추가하며 계약 SemVer를 0.9.0에서 0.10.0으로 올렸다. 그러나 그 뒤 머지된 항목 267·268·269는 초안 자료에 0.10.0 이전의 값이 그대로 들어간 탓에, 계약 proto와 계약 SemVer를 바꾸지 않은 변경임에도 각 항목 끝에 계약 SemVer를 0.9.0으로 잘못 적었다. 문서-코드 대조 시험 `DocumentClaimsTest`는 README의 계약 버전만 대조하고 설계 문서와 다른 문서의 버전 표기는 대조하지 않아 이 오류가 잡히지 않았다.

    이번 정정에서는 항목 267·268·269의 원문을 지우지 않고 각 항목 끝에 정정 문단을 붙여 0.10.0이 맞다고 적었다. 현재 상태를 말하는 `docs/contract.md`의 판단 기록 설명 끝 문장과 `docs/orchestration.md`의 판단 기록 절 소비자 문단에서는 계약을 올려도 문장이 다시 낡지 않도록 버전 숫자 0.9.0을 뺐다. 또한 한계 레지스터 `docs/limits.md` 머리말의 변경 이력 번호를 269에서 270으로 올렸다. 반면 코드와 시험, 계약 proto, 계약 SemVer 0.10.0, 내보내기 버전 6, 한계 레지스터의 행(오픈 항목 61)은 바꾸지 않았으며, 항목 263 이전의 0.9.0 표기는 그때 맞던 값이므로 그대로 두었다. 검증은 `./gradlew :gate:test`와 picasso 모듈 시험(정답 누수 검사 `GroundTruthTest` 포함)을 돌려 실패 0을 JUnit XML로 확인했다.

269. **명령을 예약·송신·확정으로 나누어 호스트 잠금 밖에서 송신하도록 분리했다.**

    호스트 잠금을 쥔 채 링크 잠금과 사우스바운드 응답을 기다리는 기존 구조로 인해 느린 플릿 호출 시 같은 프로세스의 다른 RPC가 대기하는 문제가 발생했다. §15.266이 남긴 두 작업 중 앞선 펌프의 사우스바운드 읽기는 §15.268에서 잠금 밖으로 옮겼으며, 이번 §15.269에서는 남은 작업인 명령의 예약·송신·확정 분리를 진행했다. 이번 변경은 Codex와 Fable의 교차 검토가 권한 방향이다.

    인터셉터에서 `AdapterHost`의 `RELEASED` 목록을 두어 명령 RPC(`StartTask`·`PauseTask`·`ResumeTask`·`CancelTask`·`RetryTask`)와 어댑터에 묻는 `GetKnownSiteNames`의 본문을 호스트 잠금 아래로 줄 세우지 않도록 변경했다. 본문은 `HostedRobot.recorded`로 기록을 읽고 적는 자리만 호스트 잠금으로 감싸고 어댑터를 부르는 동안은 링크 잠금만 쥐며, 다른 RPC와 콜백은 전과 같이 호스트 잠금 아래에 둔다. 접수(`HostedRobot.start`)는 세 단계로 동작한다. 첫째, 호스트 잠금 아래에서 장부와 배타를 보고 기체마다 하나인 보낼 자리(`sending`)를 잡는다. 둘째, 그 잠금을 놓고 링크 잠금만 쥔 채 어댑터에 보내며, 받았으면 접수의 관측 및 어댑터가 지금 말하는 상태가 접수와 다를 때의 상태와 관측을 같은 링크 잠금 구간에서 읽는다. 셋째, 다시 호스트 잠금 아래에서 그 자리가 이 명령의 것인지 보고 적은 뒤 자리를 비운다. 보내는 중에 같은 `(task_id, revision)`이 또 오면 같은 결과를 기다려 합류하여 멱등 응답하고, 다른 접수는 사우스바운드 왕복 대기를 유발하는 사전 조건 관측(`hold`) 조회를 피하고자 사전 조건 검사보다 먼저 `INVALID_TRANSITION`으로 거부한다(보내는 중이 아니면 리뷰 C9에 따라 사전 조건 검사가 배타 실행 검사보다 선행한다). 도는 태스크의 갱신과 파라미터만 갈아 두는 갱신(`PAUSED`·`RETRIABLE`·`NEEDS_INTERVENTION`) 역시 적을 관측을 링크로 읽기 위해 자리를 잡는다. 일시정지·재개·취소·재시도의 조작 넷은 서비스에서 `HostedRobot.manipulate`로 옮겼으며, 취소를 일시적인 사정으로 거부하지 않기 위해 보내는 중인 명령이 있으면 끝날 때까지 호스트 잠금과 링크 잠금을 쥐지 않고 기다린 뒤 펌프를 거쳐 같은 세 단계를 돈다(조작 뒤 어댑터의 지금 상태를 적는 일은 기존 호스트 잠금 아래의 `syncCurrent` 대신 같은 링크 잠금 구간에서 읽는다). 펌프의 `apply`는 확정 시 갱신이 기록되기 전에 종료가 먼저 기록되는 역전을 막기 위해 보내는 중인 명령이 있으면 태스크 전이를 적지 않고 버려 다음 읽기에서 처리하며, `pumpReleased`는 링크 대기를 피하고자 해당 기체에 보내는 중인 명령이 있으면 읽지 않는다. 잠금 순서는 기존과 동일하게 호스트 다음 링크다.

    바뀌지 않은 것과 남는 항목은 네 가지다. 첫째, 송신이 예외를 던지거나 어댑터가 링크 오류로 답하면 자리를 비우고 같은 요청이 다시 오면 다시 보내므로, 보냈는지 모르는 실패를 구분하지 않는다(어댑터의 링크 오류는 보내지 못한 것으로 읽히지만 Orbit의 `Unreachable`은 파견 `POST`를 보낸 뒤의 실패도 포함한다). 둘째, 계약에 그 칸이 없어 ADR 51 결정 5의 세대 버전 대조가 남았다. 셋째, §15.106의 단일 링크 구조상 같은 플릿 뒤 다른 기체의 명령이나 펌프의 읽기처럼 같은 링크를 쓰는 호출은 여전히 선행 명령을 기다리며, 현장에 맞는 대기 한도는 미확인이다. 넷째, 상태 발행 때 생존 보고가 묻는 사이트 이름 조회는 호스트 잠금 아래 사우스바운드 호출로 남는다. 계약 proto와 계약 SemVer 0.9.0은 바뀌지 않았다.

    시험 7건을 추가해 전체 시험은 2,313건에서 2,320건이 되었다. `HostCommandSendTest` 6건(«명령이 남쪽에 서 있는 동안에도 스냅숏은 그 명령을 기다리지 않는다», «보내는 중인 접수에 같은 태스크와 리비전이 오면 합류하고 남쪽에는 한 번만 보낸다», «보내는 중에 온 다른 태스크는 그 답을 기다리지 않고 거부한다», «보내는 중인 명령의 태스크 전이는 펌프가 적지 않는다», «조작은 보내는 중인 명령의 링크가 아니라 그 자리를 기다린다», «사이트 이름을 묻는 동안에도 스냅숏은 그 물음을 기다리지 않는다»)을 추가했으며, 앞의 셋과 마지막 것은 실 포트에서 어댑터 호출을 붙든 채 500ms 안에 응답하는지 잰다. `OrbitLauncherTest` 1건(«플릿이 파견에 답하지 않는 동안에도 스냅숏은 그 파견을 기다리지 않는다»)은 런처 배치에서 플릿 스텁의 파견 `POST` 응답을 붙든 채, 그 기체와 같은 플릿 뒤 다른 기체의 스냅숏이 500ms 안에 답하는지 잰다. 결함 주입 8건(`StartTask`를 다시 호스트 잠금 아래로, 합류 제거, 보내는 중 거부 제거, 명령의 펌프가 보내는 중에도 읽음, 펌프가 보내는 중에도 전이를 적음, 조작이 자리를 안 기다림, `GetKnownSiteNames`를 다시 호스트 잠금 아래로, 런처 배치에서 `StartTask`를 다시 호스트 잠금 아래로)은 각각 지정 시험이 잡아냈다. 레지스터 행 §15.220은 닫지 않고 좁혔으며 오픈 항목 수는 그대로 61개다.

    (정정, §15.270) 이 항목은 §15.263이 계약을 0.10.0으로 올린 뒤의 변경이므로, 적은 계약 SemVer 0.9.0은 0.10.0이 맞다. 계약 proto와 계약 SemVer를 바꾸지 않았다는 뜻은 그대로다.

268. **펌프의 사우스바운드 읽기를 호스트 잠금 밖으로 분리했다.**

    어댑터 호스트의 단일 잠금 아래에서 사우스바운드 호출인 `OrbitHttpLink`의 플릿 HTTP(연결 제한 5초)가 실행되어 플릿 응답 지연 또는 단절 시 같은 프로세스의 모든 기체 RPC와 펌프가 제한 시간 동안 대기하는 문제가 있었다(§15.220). §15.266에서 핸드셰이크 보고를 큐로 옮긴 뒤 남은 작업은 사우스바운드 읽기인 `OrbitAdapter.poll`을 잠금 밖에 두는 것과 명령을 예약·송신·확정으로 나누는 것 둘이었다. 이번 변경(§15.268)은 앞의 사우스바운드 읽기를 잠금 밖으로 분리하는 작업이다. 교차 검토(Codex·Fable)가 권한 방향이다.

    새 껍질 `SouthboundSerialized`가 `RobotAdapter`의 모든 멤버(기본 구현이 있는 `resume`·`update`·`retry`·`progress`·`failure`·`result`·`robotSoftware` 포함)를 링크 잠금 아래에서 호출하도록 했다. 기본 구현이 있는 멤버를 넘기지 않으면 껍질의 기본 구현이 돌아 감싼 어댑터의 구현을 건너뛰기 때문에 전부 넘기며, `HostedRobot.adapter`가 이 껍질이다. 플릿 뒤 기체들은 링크 하나를 같이 쓰므로 `AdapterHost`는 자기가 든 기체 전부에 링크 잠금 하나를 같이 건다(§15.106). 펌프는 호스트 잠금 아래에서 도는 태스크와 로그 길이를 정하는 `plan`, 링크 잠금만 쥐고 어댑터에 상태·함께 적을 실패·결과·진행률·파지·결함을 묻되 기록을 만지지 않는 `read`, 호스트 잠금 아래에서 적는 `apply`의 세 단계로 나눴다. `AdapterHost.pump`는 기체마다 이 셋을 돌며 `read` 동안 호스트 잠금을 쥐지 않고, RPC 안에서 부르는 `HostedRobot.pump`는 셋을 한 번에 돈다. 잠금 순서는 언제나 호스트 다음 링크다. 기록할 때 함께 읽는 실패·결과·진행률·파지는 한 번에 읽으며, 진행률은 전에는 기록 한 번에 두 번 물었고 이제 한 번 묻는다. `apply`는 더 뒤에 매긴 읽기를 이미 적었으면 앞 읽기를 통째로 버린다(읽기마다 링크 잠금 아래에서 차례 번호를 매김). 이는 링크 잠금 밖에서 끝난 읽기가 호스트 잠금을 기다리는 동안 명령 경로의 펌프가 더 새 것을 적을 수 있고, 앞 것을 그 뒤에 적으면 결함 표가 옛 값으로 돌아가기 때문이다. 또한 취소가 종착을 적었는데 그 앞에 읽은 전이를 뒤에 붙이면 종착 뒤에 줄이 생기므로, 읽는 사이 그 태스크의 로그가 늘었으면 그 상태 전이를 버린다. 버린 전이는 어댑터가 같은 상태를 들고 있어 다음 읽기가 다시 본다. 새 선택 `AdapterHost.pumpOnRead`(기본 참)를 두어 거짓이면 링크를 안 쓰는 RPC(`GetSnapshot`·`ReplayEvents`·`WatchTask`·`GetCapabilities`·`Negotiate`)는 펌프를 건너뛰고 펌프가 마지막으로 적은 것으로 답하게 했다. 명령(`StartTask`·`PauseTask`·`ResumeTask`·`CancelTask`·`RetryTask`)은 끝난 태스크를 아직 안 읽은 채 새 접수를 «도는 태스크가 있다»로 거절하지 않기 위해, 어댑터에 묻는 `GetKnownSiteNames`는 링크를 쓰므로 언제나 펌프를 먼저 지난다. 참이면 전과 같이 모든 RPC가 펌프를 지난다(펌프 스레드가 없는 배치에서는 그것이 구동이다).

    `OrbitLauncher`는 펌프 스레드가 있을 때(`pumpMillis > 0`, 기본 1000) `pumpOnRead`를 끈다. 생존 보고가 묻는 로봇 소프트웨어와 사이트 이름(`HostUplink.wrap`)은 원본 어댑터가 아니라 기체의 껍질 어댑터로 묻게 바꿨다(새 오버로드가 발행할 때 어댑터를 찾음). 전에는 원본 어댑터를 불러 펌프의 읽기와 같은 링크를 동시에 부를 수 있었다. 기존 시험인 `OrbitLauncherTest`의 «런처의 펌프 스레드는 호스트의 잠금을 지난다» 단언을 바꿨다. 전에는 호스트 잠금을 쥔 200ms 동안 펌프가 플릿 실행 조회(`GET /runs/`)를 0번 해야 했고, 이제 최대 1번이다. 읽기는 호스트 잠금 밖이지만 읽을 자리를 정하고 읽은 것을 적는 일은 잠금 아래라, 이미 들어선 읽기 하나만 끝나고 펌프가 선다.

    시험 6건을 추가해 전체 시험은 2,307건에서 2,313건이 되었다. `HostLinkReadTest` 5건(«펌프가 남쪽을 읽는 동안에도 스냅숏은 그 읽기를 기다리지 않는다», «펌프가 남쪽을 읽는 동안에도 협상은 그 읽기를 기다리지 않는다»(이 둘은 실 포트에서 펌프의 `poll`을 붙든 채 RPC가 500ms 안에 답하는지 잰다), «읽는 동안 취소가 종착을 적으면 그 읽기의 전이는 버린다», «늦게 끝난 앞 읽기는 뒤 읽기가 적은 결함 표를 되돌리지 않는다», «어댑터 호출은 기본 구현이 있는 것까지 전부 링크 잠금을 쥐고 간다»)과 `OrbitLauncherTest` 1건(«펌프 스레드를 세우는 배치만 읽기 RPC의 펌프를 끈다»)을 추가했다. 결함 주입 7건을 각각 지정 시험이 잡았다(펌프를 통째로 호스트 잠금 아래로 둔 결함은 스냅숏 시험, 읽기 RPC도 언제나 펌프를 지나는 결함은 협상 시험, 로그 길이 대조 제거는 취소 시험, 차례 대조 제거는 결함 표 시험, `poll`이 링크 잠금을 안 쥐는 결함은 링크 잠금 시험, 런처가 읽기 펌프를 안 끄는 결함은 런처 시험, 펌프 스레드가 기체의 펌프를 직접 부르는 결함은 바꾼 런처 시험). 레지스터 행 §15.220은 닫지 않고 좁혔으며 오픈 항목 수는 그대로 61개다. 명령이 여전히 호스트 잠금을 쥔 채 링크 잠금과 사우스바운드 응답을 기다리는 문제(명령 하나가 느린 플릿 호출에 서 있는 동안 같은 프로세스의 다른 RPC가 대기함), `GetKnownSiteNames`와 상태 발행 때 생존 보고가 묻는 사이트 이름(Orbit은 플릿의 미션 목록 HTTP)이 호스트 잠금 아래 사우스바운드 호출로 남은 문제, 명령의 예약·송신·확정 분리와 ADR 51 결정 5의 세대 버전 대조의 셋이 남는다. 계약 proto와 계약 SemVer 0.9.0은 바뀌지 않았다.

    (정정, §15.270) 이 항목은 §15.263이 계약을 0.10.0으로 올린 뒤의 변경이므로, 적은 계약 SemVer 0.9.0은 0.10.0이 맞다. 계약 proto와 계약 SemVer를 바꾸지 않았다는 뜻은 그대로다.

267. **스냅숏과 재생을 소비자의 잠금 밖에서 미리 읽는 포트 경로를 구현했다.**

    picasso는 자체 스레드가 없고 소비자의 잠금 아래에서 실행된다. 기체별 차단 창(§15.245)을 두었으나 첫 기한 초과와 창이 끝날 때마다의 다시 묻기는 여전히 소비자 잠금 아래에서 기한(10초)만큼 라운드를 늘린다. 답하지 않는 기체가 여럿이면 각자의 첫 기한이 한 라운드에 쌓이며, picasso-ops에서는 호스트 잠금 아래에서 실행되어 HTTP 엔드포인트도 함께 대기한다(pump 주기 250ms). 현장 실측 전까지 적정 기한 예산은 UNKNOWN이다. 한계 레지스터 행 §15.237(사우스바운드 동기 호출 막힘)의 해소 조건 중 잠금 밖에서 미리 읽은 값을 받는 포트 경로를 picasso 쪽에 구현했다. 교차 검토(Codex·Fable)가 권한 방향이다.

    `RobotPort`에 선택 메서드 `prefetched(robotId)`를 더했다. 기본은 널이고, 널이면 미들웨어가 전처럼 `snapshot`을 지금 부르고 자기 시계로 보낸 시각과 받은 시각을 재므로 기존 포트의 동작은 바뀌지 않는다. 결과 형 `PrefetchedRead`는 `Pending`(읽는 중이고 마지막 읽기가 아직 낡지 않음, 뷰를 그대로 둠), `Unread`(쓸 읽기가 없음, 못 물어봤다와 같음), `Read`(답이 왔음, 실제로 보낸 시각과 받은 시각을 듦), `Failed`(읽기가 던짐, 동기 호출이 던진 것과 같이 다룸)의 넷이다. 아직 새 값이 없음과 못 물어봤음을 접으면 정상적인 읽기 간격이 관측 실패가 되고, 그대로 두면 답하지 않는 기체의 낡은 값이 현재값으로 남기 때문에 둘을 나눴다. 새 데코레이터 `PrefetchingRobotPort`는 기체마다 읽기 하나를 자기 스레드(기본은 기체마다 가상 스레드)에서 돌린다. 다 읽은 값은 한 번만 주고, 준 뒤 다음 읽기를 걸며, 받은 지 `maxAge`(기본 2초) 넘은 값은 버린다. 읽는 중인데 마지막 읽기가 `maxAge` 넘게 지났으면 `Unread`다. 재생은 스냅숏 바로 뒤에 지난번에 물은 번호부터 묻는다. 미들웨어가 다른 번호를 물으면 미리 읽은 번호가 그 번호 이하이고 이벤트를 받았을 때만 그 번호 이후로 걸러 주고, 아니면 널을 주고 다음 읽기에서 그 번호로 묻는다(재생은 버퍼 끝까지 읽는다). 세션이 다르면 묻지 않는다. 실행을 다시 세우는 `Middleware.resume`이 부르는 `snapshot`은 지금 묻지 않는다. 받은 지 `maxAge` 안의 값이 있으면 그것을 주고, 없으면 읽기를 걸고 널을 준다. 그러면 `resume`은 `RESUME_SNAPSHOT_UNREADABLE`로 거부하고, picasso-ops의 `MissionHost`는 그 거부를 DEFERRED로 받아 다음 pump에서 다시 시도한다. 명령(`start`·`cancel`·`watch`·`capabilities`)은 접수 직후 단절의 `IN_DOUBT`가 동기 호출의 예외에 기대고 있어서 위임 포트로 지금 간다. 실행 중 취소 재전송(`pumpRound`)도 그대로 잠금 아래 동기다. `Middleware.sync`는 `prefetched`를 먼저 묻고 `Read`면 그 읽기의 보낸 시각과 받은 시각으로 관측 시각(`lastObservedAt`)과 발신자 시계 차를 잰다. `Pending`이면 뷰를 그대로 두고, `Unread`면 관측 안 함으로 둔다. 계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6은 바뀌지 않았다.

    위임 포트가 데코레이터의 읽기와 잠금 아래의 명령이라는 두 스레드에서 불리므로, `ClientRobotPort`의 차단 창 표를 동시 맵으로, `PicassoClient`의 케이퍼빌리티 캐시와 세대 표를 동시 맵으로, 케이퍼빌리티 RPC 횟수를 원자 정수로 바꿨다. 하네스(`Harness`)의 in-process 전송은 직접 실행기이고 에뮬레이터 서버(`MimicServer`)는 호출 전체를 잠금 하나 아래에서 돈다. 두 스레드가 같은 채널로 동시에 부르면 전송의 잠금과 서버의 잠금을 반대 순서로 잡아 멎는다(시험 중 실제로 멎었다). 이는 네트워크 채널에는 없는 제약이다. 그래서 통합 시험은 하위 호출을 하나씩 보내는 선(`OneAtATime`)을 두었다. picasso-ops의 `MockRunner`도 하네스 위의 `ClientRobotPort`를 쓰므로 이 데코레이터로 감싸면 같은 제약을 만난다. picasso-ops의 기본 결선(`MissionHostApplication.robotPort`)은 `ClientRobotPort` 하나이며, 이 데코레이터로 감싸고 종료 때 `close`를 부르는 것은 picasso-ops가 정한다. 그 절차는 `handoff/picasso-ops/prefetching-robot-port.md`에 둔다.

    시험 10건을 추가하여 전체 시험은 2,297건에서 2,307건이 되었다. `PrefetchingRobotPortTest` 8건(다 읽은 값은 한 번만 준다, 받은 시각은 읽기가 실제로 받은 시각이다, 받은 지 `maxAge` 넘은 값은 주지 않는다, 읽는 중인데 마지막 읽기가 낡으면 못 물어봤다이다, 재생은 미리 물은 번호 이후로 걸러 준다, 세션이 바뀌면 앞 세션의 번호로 재생을 묻지 않는다, 재개 경로의 스냅숏은 낡지 않은 값만 주고 없으면 읽기를 건다, 답하지 않는 기체의 읽기가 다른 기체의 읽기를 늦추지 않는다)과 `PrefetchedSyncTest` 2건(관측 시각은 미리 읽은 값을 받은 시각이다, 답하지 않는 기체가 있어도 pump는 기다리지 않고 유예가 지나면 끊김이다: 에뮬레이터 위에서 스냅숏을 붙든 동안 pump가 매번 1초 안에 끝나고, 관측 안 함이 되고, 끊김 유예가 지나면 `LINK_BROKEN`)을 추가했다. 결함 주입 12건(준 값을 지우지 않음, 받은 시각을 소비 시각으로, 낡은 값 검사 제거, 읽는 중이면 늘 `Pending`, 재생을 거르지 않음, 미리 읽은 번호가 더 클 때도 줌, 세션 대조 제거, 재개 스냅숏을 지금 물음, 읽기를 한 스레드에 줄 세움, 미들웨어가 받은 시각 대신 지금 시각을 씀, 읽기를 부른 스레드에서 돌림, `Unread`에서 관측 안 함으로 두지 않음)을 각각 지정 시험이 잡았다. 한계 레지스터 행 §15.237은 닫지 않고 좁혔으며, 오픈 항목 수는 그대로 61개다. 명령(`start`·`cancel`·취소 재전송)은 여전히 소비자 잠금 아래 동기라 답하지 않는 기체에 기한만큼 라운드를 늘리는 점, 선읽기는 담는 쪽이 이 데코레이터로 감쌀 때만 적용되며 picasso-ops 기본 결선은 아직 아니라는 점, 적정 기한 예산과 `maxAge`는 현장 실측 전까지 UNKNOWN이라는 점의 셋이 남는다.

    (정정, §15.270) 이 항목은 §15.263이 계약을 0.10.0으로 올린 뒤의 변경이므로, 적은 계약 SemVer 0.9.0은 0.10.0이 맞다. 계약 proto와 계약 SemVer를 바꾸지 않았다는 뜻은 그대로다.

266. **핸드셰이크 보고를 기존 적재 큐 `QueuedIngest`에 실었다.**

    설계 문서 §5.4는 보고 실패가 핸드셰이크 결과에 영향을 주지 않는다고 정했으나, 기존 동기 보고는 레지스트리가 멈춘 동안 호스트 잠금을 쥔 채 제한 시간(연결 2초·요청 3초 제한)만큼 기다렸다. `adapter-host`의 `Negotiate`와 에뮬레이터의 `Negotiate` 모두 이 보고자를 쓰며, 에뮬레이터 CLI와 Orbit 런처가 `RegistryLink.reporter`를 넘긴다. 이로 인해 사우스바운드 호출과 레지스트리 동기 HTTP가 단일 호스트 잠금 아래에서 실행되어 플릿 응답 지연 또는 단절 시 같은 프로세스의 모든 기체 RPC와 펌프가 제한 시간 동안 대기했다. 이번 변경은 교차 검토(Codex와 Fable)가 권한 §15.220 처리 순서의 1단계 가운데 첫 조각이다.

    핸드셰이크 보고를 기존 적재 큐 `QueuedIngest`에 실었다. `QueuedIngest`에 핸드셰이크 보고 항목과 `handshakeSink`를 더했고, 협상에 답하는 스레드는 큐에 넣기만 하도록 했다. 레지스트리로 보내는 것은 기존 작업 스레드 하나이며 태스크 관측과 같은 순서 큐를 쓴다. 레지스트리 실패는 작업 스레드에서 `FallbackHandshakeReporter`가 폴백 파일(`handshake-fallback.jsonl`)로 넘긴다. 큐가 차거나 닫힌 뒤에 온 보고는 부른 스레드에서 같은 폴백 파일로 넘기며(로컬 파일 쓰기), 폴백 자리가 없는 모드에서는 넘친 보고를 버린다. 넘긴 수는 `overflowedHandshakeCount`로 센다. 이제 폴백 파일을 작업 스레드와 부른 스레드가 함께 쓰므로 `FileHandshakeReporter.report`를 동기화했다(태스크 폴백 `FileTaskObservations`와 같은 방식). `RegistryLink.http`의 `reporter`가 이제 큐에 넣는 보고자이므로 폴백 자리가 없는 모드에서도 부른 쪽에 예외를 던지지 않는다. 기존 시험 `폴백 자리가 없으면 보고자가 던진다`를 `폴백 자리가 없으면 보고는 큐에서 실패하고 부른 쪽에 던지지 않는다`로 바꿨다.

    시험 4건을 추가하여 전체 시험은 2,293건에서 2,297건이 되었다. 추가한 시험은 `QueuedIngestTest`의 `핸드셰이크 보고는 넣기만 하고 레지스트리의 답을 기다리지 않는다`와 `큐가 차면 핸드셰이크 보고는 폴백으로 넘어간다`, `RegistryLinkTest`의 `레지스트리가 답하지 않는 동안에도 핸드셰이크 보고는 그 답을 기다리지 않는다`, `AdapterHostTest`의 `레지스트리가 답하지 않는 동안에도 협상과 다른 RPC 가 보고를 기다리지 않는다`(답하지 않는 레지스트리 HTTP 서버를 세우고 협상과 스냅숏이 500ms 안에 답하는지 잰다)이다. 결함 주입 4건을 각각 지정 시험이 잡았다. 폴백 모드 보고자를 동기로 되돌린 결함은 `RegistryLinkTest`와 `AdapterHostTest`의 새 시험이, 큐가 찼을 때 폴백으로 넘기지 않은 결함은 `큐가 차면 핸드셰이크 보고는 폴백으로 넘어간다`가, 큐에 넣지 않고 부른 스레드에서 보낸 결함은 `QueuedIngestTest`의 새 시험 2건이, 폴백 없는 모드 보고자를 동기로 되돌린 결함은 바꾼 `RegistryLinkTest` 시험이 잡았다. 한계 레지스터 행 §15.220은 닫지 않고 좁혔으며 오픈 항목 수는 그대로(61개)이다. 남은 작업은 사우스바운드 읽기(`OrbitAdapter.poll`)를 링크마다 스레드 하나로 잠금 밖에 두는 것과 명령을 예약·송신·확정으로 나누는 것이다.

265. **소비자 대기 하위 범주를 폐지하고 한계 레지스터와 관련 시험을 정비했다.**

    한계 레지스터 `docs/limits.md` 의 소비자 대기는 구현할 수는 있으나 쓸 소비자가 없어 소비자 존재 원칙(ADR 9)에 따라 막혀 있던 하위 범주였다. 2026-11-04 소유자가 그러한 외부 소비자는 생기지 않는다고 결정함에 따라, 소유자 지시로 소비자 대기 24행을 전수 재판정했다. picasso 나 picasso-ops 에 해당 값을 쓰는 면이 있으면 내부로 구현해 닫고, 없으면 의도적 제외로 옮기기로 했다. 판정 결과, 24행 가운데 1행(§15.208, `/catalog` 가용 수에 퇴역 기체가 세어짐)은 소비자와 무관한 이미 있는 표면의 틀린 값이라 §15.264 에서 고쳐 닫았다. 21행은 그 값을 쓰는 면이 picasso 와 picasso-ops 어디에도 없어 의도적 제외의 새 표 «수요 없음» 으로 옮겼으며, 조사 범위는 벤더 1차 자료 조사가 아닌 소유자 진술로 명시했다. 여러 행이 읽는 쪽으로 설명층 narrator 를 들었으나 행이 열려 있는 동안 그 칸을 요청한 읽는 쪽은 없었다. 2행(§15.181 골든셋 설계가 코퍼스의 설계 일지에 있음, §15.180 정답표가 «하나만 단정하면 오답» 을 표현 못 함)은 해소의 주어가 받는 쪽 채점기라 본 저장소가 구현할 것이 없으므로 의도적 제외의 기존 표에 아키텍처 경계로 옮겼고, 저장소가 막을 수 있는 몫인 문서 누수는 `GroundTruthTest` 가 막도록 했다.

    소비자 대기 하위 범주는 폐지했다. 다른 절의 번호를 지키기 위해 절 번호 3은 남겨 두되 표는 없앴으며, 옮긴 행은 원래의 해소 조건을 «다시 여는 조건» 칸에 그대로 남겼다. 원래 항목 열일곱(214, 212, 209, 202, 181, 180, 178, 184, 156, 192, 150, 218, 235, 219, 222, 224, 236)에 이전 문단을 붙였다. 시험에서는 `DocumentClaimsTest` 의 README 수 시험이 소비자 대기 수 대신 수요 없음 수를 세고, 열린 하위 범주를 내부와 외부 둘로 세도록 변경했다. 또한 새 시험 `폐지한 소비자 대기 하위 범주에 행이 없다` 를 추가하고, 하위 범주와 주어 대조 시험에서 소비자 대기 분기를 제거했다. 결함 주입 2건(소비자 대기 절에 행 하나를 되돌림, 수요 없음 행 하나의 표시를 지움)을 각각 해당 시험이 잡아냄을 검증했다. 전체 시험은 2,292건에서 2,293건으로 늘었다.

    미결 한계는 84개(내부 32, 소비자 대기 23, 외부 29)에서 61개(내부 32, 외부 29)로 줄었다. 의도적 제외는 14개에서 37개(그중 수요 없음 21개)로 늘었다. README 에는 수요 없음 행 수를 적고 `DocumentClaimsTest` 가 그 수를 센다.

264. **사이트 카탈로그 질의에서 퇴역 기체를 제외하고 한계 레지스터 행을 닫았다.**

    한계 레지스터의 소비자 대기 행 «§15.208»에 이 문제가 있었다. 퇴역(`POST /operations/robots/{robotId}/retirement`, `robot.retired_at`을 채움)은 바인딩을 풀지 않는다(복귀하면 그 바인딩으로 돌아온다). 사이트 카탈로그 `SiteCatalog.capabilities`(`GET /catalog`가 그대로 냄)는 활성 바인딩(`unbound_at IS NULL`)만 걸러 세어서, 퇴역 기체가 가용 기체 수 `availableRobots`에 세어졌고 같은 질의가 내는 `minor` 범위와 폐기 예고, 별도 질의가 내는 필수 선택 필드(`requiredOptionalFields`)에도 퇴역 기체의 리비전이 들어갔다. 해소 조건은 «`/catalog`로 배정을 판단하는 소비자가 생길 때(ADR 9)»였다. 2026-11-04에 소유자가 밖의 소비자는 생기지 않는다고 했고, 이것은 새 표면이 아니라 이미 있는 읽기 표면이 틀린 값을 내던 것이라 소비자와 무관하게 고쳤다.

    두 질의(케이퍼빌리티 집계, 필수 선택 필드)의 조건에 `r.retired_at IS NULL`을 더했다. 퇴역이 바인딩을 풀게 하는 쪽은 복귀가 그 바인딩으로 돌아오는 동작이 바뀌므로 택하지 않았다. KDoc에 그 사유를 적었다. 응답 모양과 표면은 그대로 두었다.

    `SiteCatalogTest`에 `퇴역 기체는 바인딩이 남아 있어도 안 센다`(두 대 중 한 대 퇴역이면 1, 모두 퇴역이면 케이퍼빌리티 줄 없음), `퇴역 기체의 필수 선택 필드는 안 싣는다` 2건을 더했다. 전체 시험은 2,290건에서 2,292건이 되었다. 결함 주입 2건(케이퍼빌리티 집계 질의의 퇴역 조건 제거, 필수 선택 필드 질의의 퇴역 조건 제거)을 각각 해당 시험이 잡았다. 레지스트리 시험은 로컬 Docker로 돌렸다.

    한계 레지스터에서 소비자 대기 행 «§15.208»을 닫았다. 미결 한계는 85개에서 84개(내부 32, 소비자 대기 23, 외부 29)가 되었고, 의도적 제외 14개는 그대로 두었다. `docs/commissioning.md`의 퇴역 설명에 카탈로그 사실을 더했다. `docs/seams.md` 기체 퇴역 접합부의 불변 유지 대상에서 낡은 계약 버전 표기(0.9.0)를 뺐다(계약은 §15.263에서 0.10.0). 항목 208에 «(닫힘, §15.264)» 문단을 붙였다.

263. **계약 0.10.0에 결함 관측 열거형을 더해 결함을 못 보는 기체의 스냅숏과 리빌드 제약을 다루었다.**

    한계 레지스터의 소비자 대기 행 «§15.241 · 결함 못 보는 기체의 스냅숏»에서 결함 표면이 없는 기체(Orbit 어댑터, `FaultObservation.NotObservable`)에 대해 어댑터 호스트의 `getSnapshot`이 `Status.UNAVAILABLE`을 던졌고 `ClientRobotPort.snapshot`이 이를 null로 접었다. 그래서 `Middleware.resume`이 `RESUME_SNAPSHOT_UNREADABLE`로 늘 거부했고, 리빌드 뒤 취소 부착(§15.231)은 기한 없이 기다렸으며, 연결 끊김 검사는 뷰가 관측 가능할 때만 돌아 `LINK_BROKEN`이 나지 않았다. 2026-10-19 소유자 결정(항목 11)으로 «소비자가 실제 배포를 요청할 때» 계약 0.10.0에 결함 관측 열거형을 넣기로(옵션 A) 미뤄 두었다. 2026-11-04 소유자가 그런 밖의 소비자는 생기지 않는다고 했고, Codex·Fable 자문을 거쳐 지금 짓기로 했다. ADR 9의 소비자라는 용어는 실제로 읽는 표면이며 여기서는 이 저장소 안의 `Middleware.resume`, 리빌드 뒤 취소 부착, 연결 끊김 검사였다. 10/19 결정은 지우지 않고 이 항목에 다시 정한 것을 쌓았다.

    계약 `contracts/proto/picasso/v1/event.proto`에 열거형 `FaultObservability`(`FAULT_OBSERVABILITY_UNSPECIFIED = 0`은 옛 발신자 또는 결함을 봤다는 뜻, `FAULT_OBSERVABILITY_NOT_OBSERVABLE = 1`)를 더하고 `GetSnapshotResponse`에 `fault_observability = 7`을 더했다. 관측됨 값을 따로 두지 않았다(`UNSPECIFIED`가 그 뜻이고, bool이면 옛 발신자의 기본값이 못 봤다가 된다). 계약 SemVer를 0.9.0에서 0.10.0으로 올렸다. 추가뿐이라 major는 그대로였다. `StateMessage`에는 넣지 않았다(그 결함 목록을 읽는 코드가 없다, ADR 9).

    어댑터 호스트의 `HostEventService.getSnapshot`은 결함을 못 보면 던지지 않고 태스크, 연결, `sequence`를 그대로 싣고 결함은 비운 채 `FAULT_OBSERVABILITY_NOT_OBSERVABLE`을 실었다. 마지막으로 본 낡은 결함(`HostedRobot.faults`)은 싣지 않았다. 포트 내부 타입 `RobotSnapshot`에는 `faultsObservable`(기본값 참)을 더했고 `ClientRobotPort.snapshot`이 `NOT_OBSERVABLE`을 거짓으로 읽었다. 기체 뷰 `RobotView`에 `faultsObservable`을 더했고, `sync`는 그런 스냅숏을 읽은 것으로 두었다(태스크, 연결, 세션, 재생은 전처럼). 결함은 비우고, 수용한 결함은 놓지 않았다(못 본 것은 걷혔다는 관측이 아니다). 결함 확인(`blockingFaults`)은 그런 스냅숏을 못 읽은 라운드와 같이 다루어, 이미 막고 있으면 그대로 막고, 아니면 단위에 메모를 한 번 남기고 지나갔다. 다른 기체가 같은 결함을 보이는지 볼 때(`forgetAcknowledged`)도 결함을 본 뷰만 세었다. `RESYNC` 트레이스는 결함 수 대신 `not observable`을 적었다. 이로써 결함을 못 보는 기체에서도 `resume`이 스냅숏의 태스크로 리빌드하고, 리빌드 뒤 취소 부착과 연결 상태에 따른 `LINK_BROKEN`이 도는 동작을 갖추었다. 작업 응답 형식, 내보내기 버전 6, `JudgmentRecordWire` 3은 바뀌지 않았다. 외부 행 «§15.241 · Orbit 기체 연결·결함 표면»은 그대로 열려 있었고(미상, UNKNOWN), 어댑터 호스트는 여전히 `CONNECTION_BROKEN`을 내지 않았다.

    `AdapterHostTest`의 `스냅샷은 결함과 태스크와 ONLINE 을 싣고, 못 봤으면 UNAVAILABLE 이다`를 `스냅샷은 결함과 태스크와 ONLINE 을 싣고, 결함을 못 봤으면 결함만 비우고 NOT_OBSERVABLE 이다`로 바꿨다. `OrbitLauncherTest`의 `런처가 플릿에서 발견한 기체들을 실 포트의 계약 뒤에 세운다`는 `UNAVAILABLE` 대신 `NOT_OBSERVABLE`이고 결함이 비고 태스크 `t-1`이 든 스냅숏을 단언했다. 새 시험 클래스 `FaultBlindSnapshotTest`에 4건(`계약 포트는 NOT_OBSERVABLE 스냅숏을 결함 미관측으로 읽고 태스크는 그대로 든다`, `결함을 못 보는 기체도 연결이 끊기면 LINK_BROKEN 이다`, `결함을 못 본다는 스냅숏은 막고 있던 결함을 걷지 않는다`, `결함을 못 본다는 스냅숏으로는 수용을 놓지 않는다`), `ResumeTest`에 1건(`결함을 못 보는 기체의 스냅숏으로도 다시 짓고 그 태스크에 붙는다`)을 더했다. 계약 버전을 고정한 `GrpcCodegenTest`, `WireTest`의 값을 고쳤다. 전체 시험은 2,285건에서 2,290건이 되었다. 결함 주입 7건(호스트가 다시 `UNAVAILABLE`을 던짐, 못 본 결함 자리에 낡은 결함을 실음, 포트가 `NOT_OBSERVABLE`을 무시함, 결함을 못 본 스냅숏을 못 읽은 것으로 둠, 결함 확인이 결함 미관측을 안 봄, 결함을 못 본 스냅숏으로 수용을 놓음, 리빌드가 결함 미관측 스냅숏을 거부함)을 넣어 각각 해당 시험이 잡았다. 첫 주입은 `AdapterHostTest`와 `OrbitLauncherTest` 둘 다 잡았다. `HandoffFixtureTest`의 `현장 설정 없이 만든 번들의 요약이 설정 칸을 더하기 전과 같다`가 만드는 번들의 계약 버전을 그 요약값을 잰 때의 0.9.0으로 고정했다. 계약 버전이 요약의 재료라서 지금 값을 넣으면 계약을 올릴 때마다 설정 칸과 무관하게 요약이 바뀌기 때문이었다.

    한계 레지스터에서 소비자 대기 행 «§15.241 · 결함 못 보는 기체의 스냅숏»을 닫았다. 미결 한계는 86개에서 85개(내부 32, 소비자 대기 24, 외부 29)가 되었고 의도적 제외 14개는 그대로였다. README의 계약 버전, `docs/contract.md`의 `GetSnapshot` 상세, Orbit 어댑터 README, `docs/vendors/orbit.md`를 고쳤다. 항목 241에 «(닫힘, §15.263)» 문단을 붙였다. 인계 번들 네 세트(`handoff/narrator/run-1..4`)를 `ExportFixtureTest`로 다시 산출했으며, 매니페스트와 인시던트 줄의 `contractSemver`가 0.10.0이 되어 인시던트 요약 해시가 바뀌었다. 실제 시계(`wallClockAt`, `writtenAt`), 런 식별자(`runId`), 계약 버전, 요약 해시를 가리고 맞대면 12개 파일이 모두 같았고 내보내기 버전 6은 그대로였다.

262. **작업 응답 확인 단위를 고치고 시각을 의도적 제외로 옮겼다.**

    소비자 대기 행 «§15.242 · 작업 응답의 시각과 확인 단위»에는 둘이 묶여 있었다. (1) 작업 응답(`JobResponse`, 프로세스 안 타입, 계약 proto에 없음)에 시각 칸이 없고 `jobResponseId`는 인스턴스별 카운터(`resp-N`)였으며, 결과 내보내기 줄에도 시각이 없었다. (2) `Middleware.ack`가 응답 하나를 확인하면 더 새 응답이 확인 전이어도 실행의 표시 `Execution.upstreamAck`를 `ACKED`로 바꿨다(`ForgetTest`가 이를 고정했다). 해소 조건이 «소비자가 요구할 때(ADR 9)»였는데, 소유자가 2026-11-04에 그런 밖의 소비자는 생기지 않는다고 했고, Codex·Fable 자문을 거쳐 쪼개기로 했다.

    (2)는 소비자와 무관한 이 층의 틀린 표시라 고쳤다. `ack`는 그 응답을 확인하고, 그 실행의 통보가 전부 확인됐을 때만 `Execution.upstreamAck`를 `ACKED`로 두었다. 실행은 응답의 `executionId`로 찾았다(전에는 `jobOrderId`). `upstreamAck`에 KDoc을 남겼다. `forget`은 전처럼 응답마다의 확인을 보고 `settleExecution`은 `NOT_SENT`만 보므로 그 둘의 동작은 그대로였다. (1) 시각은 짓지 않고 의도적 제외로 옮겼다. 받은 시각의 정본을 나르는 쪽으로 보았다(picasso-ops는 `job_response_log.recorded_at`에 DB `clock_timestamp()`로 찍었다). 이 층은 아웃박스 순서만 내며 `jobResponseId`가 인스턴스별 카운터라 재기동을 넘는 전순서는 없었다. 시각을 실으면 결과 내보내기 버전을 올려야 하는데 그 칸을 읽는 코드가 없었다(ADR 9). 계약 proto, SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, `JudgmentRecordWire` 3은 그대로였다.

    `ForgetTest`의 `확인되지 않은 통보가 하나라도 있으면 놓지 않는다`를 고쳐, 앞 통보만 확인했을 때 `SENT_UNACKED`, 마지막까지 확인하면 `ACKED`를 단언하게 했다. 시험 수는 그대로였다(2,285건). 결함 주입 1건(응답 하나의 확인으로 `ACKED`를 두던 옛 동작으로 되돌림)을 그 시험이 잡았다.

    한계 레지스터에서 소비자 대기 행 «§15.242 · 작업 응답의 시각과 확인 단위»를 닫고 의도적 제외에 «§15.242» 행을 더했다. 미결 한계는 87개에서 86개(내부 32, 소비자 대기 25, 외부 29)가 되었고, 의도적 제외는 13개에서 14개가 되었다. 항목 242에 «(닫힘, §15.262)» 문단을 붙였다.

261. **적재를 작업 스레드 하나의 큐로 옮겼다.**

    한계 행 «§15.210 · 적재 잠금»이 배경이었다. 에뮬레이터의 태스크 관측 적재(`IngestBridge`, 레지스트리로 가는 동기 HTTP, 연결 2초·요청 3초 제한)가 `MimicServer`의 엔진 잠금 안에서 돌아, 레지스트리가 느리거나 죽으면 그동안 다른 RPC와 시간 흘리기가 그 제한만큼 기다렸다. 어댑터 호스트도 같은 `RegistryLink.http`로 적재해 호스트 잠금 아래에서 같은 일이 있었다(행 «§15.220»).

    `uplink`에 `QueuedIngest`를 더했다. 발행한 스레드는 큐에 넣기만 하고, 레지스트리로 보내는 것은 작업 스레드 하나로 했다(태스크 관측의 순서를 지키려고). `RegistryLink.http`의 태스크 적재와 생존 보고가 이 큐를 지났다. 큐 용량은 4096이었다. 큐가 차면 태스크 관측은 폴백 파일(`task-fallback.jsonl`, 사유 «적재 큐가 찼다»)로 넘겼고, 폴백 디렉터리가 없으면 버렸다. 생존 보고는 레지스트리가 받은 시각으로 적으므로 큐에 5초(연결 2초 + 요청 3초) 넘게 머물렀으면 보내지 않았고, 큐가 차면 버렸다. 안 보낸 생존은 축소를 막는 쪽으로 틀렸다(§15.41). `software`·`siteNames` 람다는 전처럼 발행한 스레드에서 평가했다. `FileTaskObservations.onFailure`를 동기화했다(작업 스레드와 발행 스레드가 같은 파일에 썼다). `RegistryLink`에 `drain`·`close`를 더했고, 에뮬레이터 CLI의 `MimicCli.Started`가 연계를 들고 `close`에서 서버를 내린 뒤 큐를 비웠으며, `main`이 종료 훅에서 그것을 불렀다. `close`가 10초 안에 큐를 못 비우거나 프로세스가 강제로 죽으면 큐에 남은 관측은 레지스트리에도 폴백에도 없었다(동기로 보낼 때는 없던 손실). 핸드셰이크 보고(`Negotiate`)는 여전히 부른 스레드에서 보냈다. 어댑터 호스트의 남쪽 호출(`OrbitHttpLink`)은 그대로 호스트 잠금 아래에 있었다. 계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 3)은 바뀌지 않았다. 하네스의 종단 시험은 `IngestBridge`에 싱크를 직접 붙이므로 동기 동작 그대로였다.

    `QueuedIngestTest` 6건을 신설하고, `RegistryLinkTest`에 `레지스트리가 답하지 않는 동안에도 발행은 그 답을 기다리지 않는다` 1건(진짜 소켓, 응답을 붙든 레지스트리)을 더했다. 기존 `RegistryLinkTest` 두 시험과 `MainTest`의 연계 기동 도우미는 큐를 비운 뒤 폴백 파일을 읽게 고쳤다. 전체 시험은 2,278건에서 2,285건이 되었다. 결함 주입 7건(넣기를 동기로 보냄, 작업 스레드 둘, 넘친 태스크 관측을 버림, 버린 생존을 안 셈, 오래 머문 생존 검사 제거, 닫을 때 안 비움, 연계가 큐를 비켜 감)을 넣어 각각 해당 시험이 잡았다.

    한계 레지스터에서는 내부 행 «§15.210 · 적재 잠금»을 닫고 «§15.220 · 호스트 잠금 아래 남쪽 호출»을 좁혔다. 미결 한계는 88개에서 87개(내부 32, 소비자 대기 26, 외부 29)가 되었고 의도적 제외 13은 그대로였다. 항목 210에 «(닫힘, §15.261)», 항목 220에 «(좁힘, §15.261)» 문단을 붙였다. `uplink/README.md` 표에 `QueuedIngest` 행을 더했다.

260. **`Middleware.resume`에 마지막보다 낮은 버전을 넘겨 리빌드할 때 거부하도록 동작을 바꾸고 미검증 계약 행의 시험을 보강했다.**

    `docs/contract.md` §7.2의 질문 표에 시험 없이 «미검증»으로 남은 행 둘이 있었다. (b) «`Middleware.resume`에 마지막보다 낮은 버전을 넘긴 경우 기존 태스크에 미부착»은 KDoc에만 있었다. (e) «취소 요청 시 하위가 이미 `SUCCEEDED`인 경합에서 거부 코드(`INVALID_TRANSITION`) 기록 및 다음 pump 에서 중단»은 코드는 있었으나 그 거부 코드를 단언하는 시험이 없었다(있는 단언은 `CANCEL_UNSUPPORTED`와 거부 없음뿐이었다). (b)를 에뮬레이터 위에서 관측하니 결함이 있었다. 앞 인스턴스가 버전 2를 받아 도는 이동 태스크를 리비전 2로 갱신한 뒤, 버전 1을 넘겨 리빌드하면 리빌드한 실행이 그 이동의 재전송을 `OUTDATED_REVISION` 거부로 받아 단위를 실패로 적었고, 기체가 아직 그 이동을 도는 동안 다음 지점의 이동 태스크를 새로 냈다. 기체에 태스크가 둘 섰다.

    `resume`이 스냅숏을 읽은 뒤 기체가 넘긴 작업 지시의 버전보다 높은 리비전으로 그 작업 지시의 태스크(태스크 id가 `<작업 지시 id>#`로 시작)를 들면 실행을 만들지 않고 새 상수 `Middleware.RESUME_STALE_VERSION`으로 시작하는 사유로 거부했다(새 private 함수 `staleVersionRefusal`). 사유에는 기체, 태스크, 그 리비전이 실렸다. 스냅숏을 못 읽을 때의 `RESUME_SNAPSHOT_UNREADABLE`과 달리 다시 시도해도 같으므로 담는 쪽이 마지막 버전을 넘겨야 했다. 리비전을 싣지 않는 포트에서는 이 대조를 할 수 없었다(§15.238). 마지막 버전을 넘기면 전처럼 도는 태스크에 다시 붙었다. (e)는 코드를 바꾸지 않았다. 계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 3)은 바뀌지 않았다. 새 공개 면은 상수 `RESUME_STALE_VERSION` 하나였다.

    `InspectAssetTest`에 2건(`취소가 닿기 전에 하위가 이미 끝냈으면 거부 코드와 그 뒤에서 멈춘 단위가 취소 응답에 있다`, `마지막보다 낮은 버전으로 다시 지으려 하면 거부하고 기체에 새 태스크를 내지 않는다`)을 더했다(29건에서 31건). 전체 시험은 2,276건에서 2,278건이 되었다. 결함 주입 2건(버전 대조 호출 제거, 취소 응답의 거부 코드를 버림)을 넣어 각각 해당 시험이 잡았다. 둘째 주입은 기존 시험 `이동 중 취소는 하류가 거절하고 다음 경계에서 멈춘다 — 그 차이가 취소 응답에 드러난다`도 잡았다.

    `docs/contract.md` §7.2의 두 «미검증» 행을 새 시험을 인용한 행으로 바꿨다((b)는 «검증됨(재기동, 같은 소유자; 미들웨어만)», (e)는 «검증됨(같은 프로세스)»). 한계 레지스터의 행은 바뀌지 않았다(미결 한계 88개 그대로였다).

259. **`adapter-host` 세션에 기동 시각을 넣어 프로세스를 넘어 겹치지 않는 세션을 내게 했다.**

    `adapter-host`의 세션(`HostedRobot.sessionId`, 응답 헤더의 `session_id`)이 프로세스 카운터(`host-N`)였다. 프로세스를 다시 띄우면 카운터가 처음으로 돌아가 같은 세션이 되풀이되었다. 미들웨어는 스냅숏의 세션이 앞서 읽은 세션과 다르면 커서를 그 스냅숏의 번호로 다시 세우는데(§15.228), 세션이 같아 보여 재시작을 못 보고 낡은 커서가 새 세션의 앞 이벤트를 건너뛰었다. 이벤트는 실행 트레이스에만 쓰이고 상태 판정은 `WatchTask`에서 오므로 영향은 트레이스에 그쳤다. 행 «§15.215 · 옛 커서»의 해소 조건은 `adapter-host`가 프로세스를 넘어 겹치지 않는 세션을 내는 것(§15.21)이었다. 에뮬레이터의 세션은 이미 `<기체 id>-<기동 시각 밀리초>-<카운터>`였다.

    `adapter-host`의 세션을 `host-<기동 시각 밀리초 13자리>-<카운터 6자리>`로 지었다. 시각은 호스트에 주입한 시계(`clock`)에서 읽었다. 재생 버퍼가 넘쳐 못 보낸 이벤트를 버릴 때 새로 내는 세션도 같은 규칙(그때의 시각)이었다. 난수를 쓰지 않은 것은 에뮬레이터와 같은 이유로, 주입한 시계를 고정하면 같은 값이 났다. 실시계에서는 다시 띄운 프로세스가 앞 세션과 다른 세션을 내어 미들웨어가 재시작을 보고 커서를 다시 세웠다. 미들웨어 코드는 바꾸지 않았다. 같은 밀리초에 다시 뜨거나 시계가 되감기거나 에뮬레이터를 가상 시계로 띄우면(§12.1 결정성) 세션이 겹칠 수 있었다. 재기동 뒤 옛 커서(새 프로세스의 다음 번호보다 큰 값)로 `ReplayEvents`를 물으면 두 발신자 모두 빈 응답을 주며 계약에 그것을 거부하는 규칙이 없었다. 이 둘을 행 «§15.21»로 옮겨 그 행을 좁혔다(해소 조건: 결정론적 시드 기반 고유 식별자 생성기, 또는 옛 커서를 축출로 응답하는 계약 규칙). 바뀌지 않은 것은 계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 3)이었다. 세션 문자열의 모양만 바뀌었고 `event_id`(세션 뒤에 번호를 붙인 것)도 따라 길어졌다. 문서에서는 `docs/contract.md`의 `ReplayEvents` 표 칸, `GetSnapshot`·`ReplayEvents` 상세, §7.4 공통 재기동 신호 행을 새 모양에 맞게 고쳤다.

    `AdapterHostTest`에 `세션은 기동 시각을 들어 다시 띄운 호스트가 카운터를 처음부터 세어도 앞 세션과 겹치지 않는다`를 더했다(45건에서 46건). 한 JVM에서는 카운터가 처음으로 돌아가지 않아 재시작을 흉내 낼 수 없으므로 세션이 기동 시각을 드는지 보았다. 전체 시험은 2,275건에서 2,276건이 되었다. 결함 주입 1건(세션을 카운터만으로 되돌림)을 넣어 그 시험이 잡았다.

    한계 레지스터에서는 내부 행 «§15.215 · 옛 커서»를 닫고 남은 것을 «§15.21»에 옮겼다. 미결 한계는 89개에서 88개(하위 범주별로 내부 33, 소비자 대기 26, 외부 29)가 되었고 의도적 제외 13은 그대로였다. 밖을 향한 문서가 드는 열림은 141개에서 140개가 되었다. 항목 215에는 «(닫힘, §15.259)», 항목 21에는 «(좁힘, §15.259)» 문단을 더했다.

258. **취소 보고에서 상수로 채우던 `accepted`와 `motionStopped` 칸을 걷어냈다.**

    미들웨어가 취소 보고(`CancelReport`, 실행이 취소로 멈출 때 `Middleware.abort`가 만들고 `Middleware.lastCancel`로 읽힘)를 만들 때 `accepted = true`, `motionStopped = true`를 늘 채웠다. 하위가 취소를 거부했든 받아들였든, 종료를 관측했든 같았다. 두 칸을 단언하는 시험이 없었고 작업 응답 내보내기에도 실리지 않았다. 쓰는 곳은 그 생성 한 곳뿐이었다. 다른 칸(`stoppedAfter`·`residualHold`·`cleanup`·`refusal`)은 관측에서 왔다. 행 «§15.215 · 취소 보고 상수»의 해소 조건은 두 칸을 하위의 수락과 종료 관측에서 채우거나, 쓰는 곳이 없으면 칸을 걷어내고 그때 시험으로 고정하는 것이었다. 두 칸을 걷어내는 쪽을 골랐다. 설계 문서의 취소 응답 정의(완료 단위, 진행 중 단위, 미시작 단위, 잔여 `hold`, 정리 결과)에 두 칸이 없었다. 담는 쪽 picasso-ops의 코드는 두 칸을 읽지 않았다. 관측에서 채우려 해도 두 칸이 말할 것은 이미 다른 칸이 말했다: 하위가 받아들였는가는 `refusal`(하위의 거부 코드, 또는 닿지 못했을 때 이 계층의 `CANCEL_NOT_SENT`, 받아들였으면 널)이, 어디서 멈췄는가는 `inProgressUnit`·`stoppedAfter`가, 최종 물리 상태는 `finalState`가 말했다.

    `CancelReport`에서 `accepted`·`motionStopped`를 지웠고 `Middleware.abort`가 채우던 두 줄을 지웠다. `CancelReport`의 KDoc에 칸이 전부 관측이나 이 계층의 판정에서 온다는 것과 걷은 까닭을 적었다. 취소 동작, 취소 보고의 다른 칸 값은 바뀌지 않았다. 계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 3)은 바뀌지 않았다. 새 트레이스 종류는 없었다. 공개 면은 `CancelReport`의 두 칸만큼 줄었다. 라이브러리를 쓰는 쪽이 그 칸을 읽었다면 컴파일이 깨지며, picasso-ops는 읽지 않았다. `docs/contract.md`의 `CancelTask` 완료 근거 문장과 §7.2 (e)의 미검증 행(두 칸이 상수)을 새 시험을 인용한 검증됨 행으로 바꿨다.

    `InspectAssetTest`에 `취소 응답은 관측과 판정에서 온 칸만 든다`를 더해(28건에서 29건) 취소 보고의 칸 이름 집합을 고정했다. 전체 시험은 2,274건에서 2,275건이 되었다. 결함 주입 1건(`accepted` 칸을 기본값 참으로 되살림)을 넣어 그 시험이 잡았다. `InDoubtTest` 한 시험의 주석이 든 `motionStopped`를 `ABORTED`로 고쳤다.

    한계 레지스터에서는 내부 행 «§15.215 · 취소 보고 상수»를 닫았다. 미결 한계는 90개에서 89개(하위 범주별로 내부 34, 소비자 대기 26, 외부 29)가 되었고 의도적 제외 13은 그대로였다. 밖을 향한 문서가 드는 열림은 142개에서 141개가 되었다. 항목 215에는 «(닫힘, §15.258)» 문단을 더했다.

257. **판단 기록의 단위 행에 파지 이력 두 칸을 싣고 리빌드에서 시도에 맞춰 옮기도록 했다.**

    단위의 파지 이력 두 칸 `everHeld`(이 시도에서 쥔 것이 관측됐는가)와 `heldInEarlierAttempt`(앞 시도에서 쥔 것이 관측됐는가, §15.236)는 판단 기록(`JudgmentRecord`, 단위 행 `UnitRecord`)에 실리지 않아 `resume`으로 리빌드한 단위는 둘 다 거짓으로 시작했다. 끝난 시도의 파지는 계약 스냅숏에 실리지 않고, 재기동 사이에 놓친 파지는 다시 관측해도 빈손만 보여 «쥔 적 있다»가 되지 않았다. 쓰는 곳은 둘이었다. 재할당(`Middleware.reassign`)은 둘 중 하나라도 참인 로봇 단위가 있으면 «자재를 다룬 단위가 있다»는 사유로 거부했는데, 지금 운영 호출자가 없었다. 효과-관측 대조(`judgeEffectMismatch`)는 단위가 완료하지 못하고 끝날 때 `everHeld`가 참이면 «든 채»를 기대해(`HoldEffects.expectedAtEnd`) 빈손을 `PAYLOAD_LOST`로 판정했다. 이 길은 운영 경로(재기동마다 타는 `resume`, 실패나 중단으로 끝나는 단위)라서, 재기동 직전에 쥐었다가 재기동 사이에 놓친 단위가 리빌드 뒤 실패나 중단으로 끝나면 그 판정이 빠졌다. 행 «§15.250 · 리빌드 뒤 파지 이력»은 «당장 드러나는 동작 영향은 없음»이라 적었으나 이 둘째 쓰임을 빠뜨렸다. 해소 조건은 판단 기록의 단위 행이 파지 이력 두 칸을 싣는 것(`JudgmentRecordWire` 버전 올림과 담는 쪽 기록 영속 갱신이 따름)이었다.

    `UnitRecord`에 `everHeld`와 `heldInEarlierAttempt`(둘 다 참거짓)를 더했다. `Middleware.judgmentRecord`가 단위의 두 값을 싣고, `resume`이 시도에 맞춰 옮기도록 했다. 기록의 시도가 스냅숏의 시도와 같거나 크면 두 값을 그대로 옮겼다. 스냅숏이 더 큰 시도를 보이면(기록이 그 재작업보다 먼저 적혔다) 기록의 두 값은 앞 시도의 것이라 `heldInEarlierAttempt`에 접고 `everHeld`는 거짓으로 두었다. 재작업이 새 시도를 낼 때(`reworkAsNewAttempt`)와 같은 규칙이었다. 새 시도의 파지로 옮기면 쥔 적 없는 시도가 «든 채»를 기대하기 때문이었다. 지금의 파지(`hold`)는 옮기지 않고 리빌드한 인스턴스가 기체에서 보았다.

    `JudgmentRecordWire`를 2에서 3으로 올렸다. 단위 행에 두 칸이 늘었다. 읽기는 버전 1, 2, 3을 모두 받았다. 버전 1과 2 기록은 두 칸 없이 읽고 쥔 것을 본 적 없는 단위가 되었다(버전 3 전의 리빌드와 같았다). 버전마다 칸 목록이 정확해야 해서, 버전 2 기록에 그 칸이 있거나 버전 3 기록에 그 칸이 없으면 거부했다. 담는 쪽(picasso-ops)은 기록 글자를 그대로 적고 이 라이브러리로 읽으므로, 이미 적어 둔 버전 1·2 행도 그대로 읽혔다. 계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6은 바뀌지 않았다. 새 트레이스 종류도 새 공개 면도 없었다(`UnitRecord`의 두 칸은 기본값 거짓이었다).

    전체 시험은 2,270건에서 2,274건이 되었다. `JudgmentRecordWireTest`에 `버전 2 기록은 단위의 파지 이력 칸 없이 읽고 쥔 것을 본 적 없는 단위가 된다`를 더하고, 왕복 시험과 버전 1 시험이 두 칸을 싣도록 고쳤다. `JudgmentRecordTest`에 3건을 더했다: `쥐었다가 재기동 사이에 놓친 단위가 다시 지은 뒤 실패로 끝나면 빈손을 효과 어긋남으로 판정한다`, `쥔 것을 본 단위가 보류에 선 채 다시 지으면 재할당이 자재를 다룬 단위로 거른다`, `기록이 재작업보다 먼저 적혔으면 그 시도의 파지는 앞 시도의 파지로 접는다`. 앞의 두 시험은 기록 없이 리빌드한 대조를 함께 두었다. `ResumeCancelTest`의 버전 1 시험은 버전 1 모양을 지을 때 두 칸을 지우도록 고쳤다. 결함 주입 6건(같은 시도에서 `everHeld`를 옮기지 않음, 앞 시도로 접지 않음, 앞 시도의 파지를 새 시도에 옮김, 기록이 `everHeld`를 싣지 않음, 읽기가 두 칸을 버림, 버전 2를 못 읽음)을 넣어 모두 해당 시험이 잡았다.

    한계 레지스터에서는 내부 행 «§15.250 · 리빌드 뒤 파지 이력»을 닫았다. 미결 한계는 91개에서 90개(하위 범주별로 내부 35, 소비자 대기 26, 외부 29)가 되었고 의도적 제외 13은 그대로였다. 행 «§15.221 · 기록이 들지 않는 것»의 파지 이력 예외 문장을 판단 기록이 버전 3부터 싣는다는 내용으로 고쳤다. 항목 250에는 «(닫힘, §15.257)» 문단을 더했다.

256. **취소가 걸린 실행의 개정에서 도는 로봇 단위와 미확정 로봇 단위를 바꾸지 않고 갱신을 보내지 않도록 했다.**

    개정(`revise`)은 취소가 걸린 실행에도 도는 로봇 단위와 미확정(`IN_DOUBT`) 로봇 단위의 리비전과 기대(파라미터, 기대 식별, 출발지와 목적지)를 새 버전 것으로 바꾸고 계약의 갱신(같은 태스크 id의 `StartTask`)을 보냈다. 허가가 돌아온 뒤 라운드 머리에서 보내는 미룬 갱신은 취소가 걸렸으면 보내지 않았다(멈추라는 말 뒤에 새 버전을 보내면 기체가 그 버전으로 다시 움직일 수 있다는 규칙). 원 요청이 기체에 닿지 않은 미확정 단위면 그 갱신이 새 태스크로 접수됐다. 행 «§15.255 · 취소 뒤 개정 갱신»의 해소 조건은 취소가 걸린 실행의 개정에서 도는 단위와 미확정 단위에 갱신을 보내지 않는 규칙(미확정 단위의 리비전과 키로 짓는 취소 핸들의 리비전이 어긋나지 않게)과 그 시험이었다.

    `revise`에서 로봇 단위의 갱신 여부를 취소 요청에 묶었다. 취소가 걸렸으면 도는 로봇 단위와 미확정 로봇 단위는 플릿 운반 단위, 설비 대기 단위처럼 그대로 두었다. 갱신을 보내지 않고 리비전과 기대도 올리지 않았다. 기체에서 도는 것이 옛 리비전이라 올리면 스냅숏 부착(`attachFromSnapshot`, 리비전이 같아야 붙음)과 키로 짓는 취소 핸들(`cancelByKey`)의 리비전이 기체와 어긋나기 때문이었다. 미확정 단위는 그 리비전 그대로 스냅숏 부착이나 키 취소로 붙어 취소가 나갔다. 실행의 주문은 새 버전을 받고(`version` 2), 아직 시작하지 않은 단위는 전처럼 새 계획으로 바뀌었다. 개정이 취소보다 먼저 와서 갱신이 미뤄졌거나 끝난 태스크에 닿은 경우는 전과 같이 옛 리비전의 종료가 지연 이벤트(`settleLate`)로 왔다.

    이제 `sendRevisionUpdate`를 부르는 두 자리(`revise`, 라운드 머리의 미룬 갱신)가 모두 취소가 걸린 실행에서 부르지 않게 되었다. 그래서 §15.255가 그 함수의 미확정 해소 분기에 더한 취소 보내기(`sendCancel`)와 취소 여부에 따른 `CANCELING` 복원을 걷고 `RUNNING`으로 되돌리게 했다. KDoc에 «취소가 걸린 실행에는 둘 다 부르지 않는다(§15.256)»를 적었다. 계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 2)은 바뀌지 않았다. 새 트레이스 종류도 새 공개 면도 없었다.

    `CancelUnderRevisionTest`를 고쳐 4건이 되었고 전체 시험은 2,269건에서 2,270건이 되었다. 새 시험 `건 취소가 걸린 채 개정이 오면 도는 단위에 갱신을 보내지 않고 그 리비전 그대로 중단으로 종결한다`를 더했다. §15.255의 시험 `핸들 없이 취소가 걸린 미확정 단위가 갱신으로 붙으면 그 핸들로 취소를 보내고 중단으로 종결한다`를 `핸들 없이 취소가 걸린 미확정 단위에 개정이 오면 갱신을 보내지 않고 같은 리비전의 태스크에 붙어 중단으로 종결한다`로 바꾸었다. §15.255의 지연 취소 시험 `건 취소가 갱신보다 먼저 태스크를 끝냈으면 그 지연 이벤트를 요청된 취소로 읽어 중단으로 종결한다`는 그 순서(취소 뒤 개정)가 더는 지연 이벤트를 만들지 않아, 작업 허가를 닫아 갱신을 미룬 뒤 취소를 거는 `미룬 갱신 뒤 건 취소가 옛 리비전의 태스크를 끝냈으면 그 지연 이벤트를 요청된 취소로 읽어 중단으로 종결한다`로 바꾸었다. 결함 주입 3건(취소 중 갱신, 지연 요청 취소를 보류로, 지연 비요청 취소를 중단으로)을 넣어 모두 해당 시험이 잡았다.

    한계 레지스터에서는 내부 행 «§15.255 · 취소 뒤 개정 갱신»을 닫았다. 미결 한계는 92개에서 91개(하위 범주별로 내부 36, 소비자 대기 26, 외부 29)가 되었고 의도적 제외 13은 그대로였다. 항목 255에는 «(닫힘, §15.256)» 문단을 더했다.

255. **리빌드한 실행이 붙기 전에 건 취소의 대기에 미확정 유예 기한을 두어 운영자 보류에 인시던트를 열고 개정 응답에서 걸린 취소를 바로 보내도록 했다.**

    리빌드한 실행에 붙기 전에 건 취소(§15.231의 로봇 태스크, §15.254의 플릿 운반)는 기체 스냅숏을 못 읽거나, 취소나 플릿 찾기나 운반 상태 읽기가 던지면 종결하지 않고 실행을 `CANCELING`으로 둔 채 다음 라운드에 다시 보았다(트레이스 `CANCEL_UNATTACHED_WAIT`). 기한이 없어 그동안 작업 응답이 나가지 않았다. 행 «§15.230 · 재기동 뒤 하위 취소»의 해소 조건은 남은 미검증 분기(`settleLate`의 지연 취소, 개정 응답 분기)를 시험으로 다루고 기한 없는 `CANCELING` 대기에 기한을 두는 것이었다.

    새 private 함수 `Middleware.waitUnattached`가 `attachForCancel`과 `attachTransportForCancel`의 대기를 맡아 두 함수 안의 지역 함수를 대신했다. 처음 기다린 시각을 실행 칸 `Execution.unattachedCancelSince`(internal, 판단 기록에 싣지 않음)에 적었다. 그 시각부터 미확정 유예(`inDoubtGrace`, 기본 60초)가 지나도 답이 없으면 기다리던 단위를 운영자 보류(`OPERATOR_HOLD`)에 두었다. 단위에 지금 시도의 태스크 id와 요청 시각을 적고, 실행 칸 `cancelUnsent`와 `doubtCancelHold`를 세우며, 트레이스 `CANCEL_UNSENT`(기존 종류)에 까닭 «붙기 전에 건 취소가 미확정 유예 안에 답을 받지 못했다»를 남기고 인시던트를 열었다. 붙으면 시각을 걷었다. 실행은 중단으로 닫지 않고 운영자 판단을 기다렸다(기존 `cancelWaitsOnHeldDoubt` 규칙). 로봇 단위는 보류 중 스냅숏에 그 태스크가 보이면 기존 `resendHeldCancel`이 키로 지은 핸들로 취소를 다시 보내고 보류는 유지했다. 이는 §15.251의 `holdUnreachedCancel`(끊긴 기체에 건 취소)과 같은 기한, 같은 보류였다. 유예 안의 대기 트레이스는 같은 까닭이면 한 번만 남겼다(전과 같음).

    개정 응답 동작에서 미확정(`IN_DOUBT`) 로봇 단위에 핸들 없이 취소가 걸리면 그 취소는 하위로 나가지 않았다. 그 뒤 새 버전이 오면 개정 갱신(`sendRevisionUpdate`)이 핸들을 돌려주어 단위가 붙고 실행은 `CANCELING`으로 돌아갔으나, 걸린 취소는 보내지 않았다. 시험으로 재현하자 기체는 그 태스크를 성공까지 했고 취소 응답은 거절 없이 그 단위 뒤에서 멈췄다고(`stoppedAfter`) 말했다. 이에 따라 그 분기에서 취소가 걸려 있으면 붙은 핸들로 바로 보내도록 했다(`sendCancel`). 이는 스냅숏 부착(`attachFromSnapshot`)이 붙으면서 걸린 취소를 보내는 동작과 같았다. 지연 취소(`settleLate`)는 갱신이 닿기 전에 태스크가 취소로 끝나면 그 종료는 옛 리비전의 지연 이벤트로 왔다. 건 취소의 종료면 중단으로 종결하고, 아니면 요청 없는 취소라 운영자 보류에 두고 인시던트를 열었다. 이 분기는 코드를 바꾸지 않고 시험으로 다루었다.

    대기 기한 동작을 처리하는 private 함수 `Middleware.waitUnattached`와 실행 칸 `Execution.unattachedCancelSince`가 추가되고 개정 응답 분기에서 걸린 취소를 바로 보내도록 변경했다. 계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 2)은 바뀌지 않았다. 새 트레이스 종류도 새 공개 면도 없었다.

    새 시험 클래스 `CancelUnderRevisionTest`(4족 픽스처, 점검 순회)에 3건(`건 취소가 갱신보다 먼저 태스크를 끝냈으면 그 지연 이벤트를 요청된 취소로 읽어 중단으로 종결한다`, `요청하지 않은 취소 종료가 갱신 뒤 지연 이벤트로 오면 운영자 보류에 두고 인시던트를 연다`, `핸들 없이 취소가 걸린 미확정 단위가 갱신으로 붙으면 그 핸들로 취소를 보내고 중단으로 종결한다`), `ResumeCancelTest`에 1건(`스냅숏을 못 읽은 채 미확정 유예가 지나면 붙기 전에 건 취소를 운영자 보류에 두고 다시 읽히면 취소를 보낸다`, 다시 뜬 인스턴스의 시계만 유예 너머로 밈), `ResumeFleetCancelTest`에 1건(`플릿 찾기가 미확정 유예 동안 답하지 않으면 건 취소를 운영자 보류에 두고 새로 맡기지 않는다`) 등 5건을 더해 전체 시험은 2,264건에서 2,269건이 되었다. 결함 주입 11건(개정 응답 취소 안 보냄, 지연 요청 취소를 보류로, 지연 비요청 취소를 중단으로, 기한 없음 두 클래스, 인시던트 없음 두 클래스, 미전송 표시 없음, 미전송 트레이스 없음, 대기 트레이스 매번, 보류에 태스크 id 안 적음)을 넣어 모두 해당 시험이 잡았다.

    한계 레지스터에서는 내부 행 «§15.230 · 재기동 뒤 하위 취소»를 닫았다. 판단 기록 없이 또는 버전 1 기록으로 리빌드한 실행은 되살릴 건 취소가 없어 하위 취소를 요청 없는 취소로 읽어 운영자 보류에 두며, 기체를 내주지 않는 쪽이라 행으로 남기지 않았다. 개정 응답을 고치며 드러난 공백을 새 내부 행 «§15.255 · 취소 뒤 개정 갱신»으로 열었다. 미결 한계 92개(내부 37, 소비자 대기 26, 외부 29)와 의도적 제외 13은 그대로였다. 항목 230에는 «(닫힘, §15.255)» 문단을 더했다.

    (닫힘, §15.256) 이 항목이 연 행 «§15.255 · 취소 뒤 개정 갱신»은 취소가 걸린 실행의 개정이 도는 로봇 단위와 미확정 로봇 단위를 바꾸지 않도록 하여 §15.256에서 닫혔다. 이 항목이 개정 갱신(`sendRevisionUpdate`)의 미확정 해소 분기에 더한 취소 보내기는 그 분기가 취소가 걸린 실행에서 더는 불리지 않아 §15.256에서 걷었다.

254. **플릿 운반 조회 경로를 마련해 리빌드한 실행이 맡긴 운반을 찾아 취소하고 조회가 없으면 운영자 보류에 인시던트를 열도록 했다.**

    다시 짓기(`resume`)는 기체의 스냅숏만 읽고 플릿은 읽지 않아 재기동 전에 플릿에 맡긴 운반은 다시 지은 실행에 붙지 않았다. 그 실행에 취소가 걸려 있으면(담는 쪽이 다시 걸거나 §15.253의 판단 기록이 들고 오면) 첫 pump가 붙을 로봇 태스크를 못 찾고 «지울 것 없음»(`nothing_to_clean`)으로 중단에 종결했고, 플릿의 운반은 계속 돌았다. 같은 참조의 `dispatch` 재전송은 플릿이 그 운반을 모르면 새 운반을 내므로 쓰지 않았다. 행 «§15.230 · 재기동 뒤 하위 취소»의 해소 조건은 플릿 운반 단위의 기존 이송 조회 경로였다.

    `AmrFleetPort`에 `find(reference): TransportHandle?`를 더했다. 맡긴 운반을 찾기만 하고 없으면 `null`을 돌려주며 새로 맡기지 않았다. `executionLookup`이 `NONE`이면 부르지 않았고, 기본 구현 `None`은 `null`을 돌려주었으며 시험 더블 `AmrFleetMimic`(클라이언트 참조 표에서 찾음)과 `LossyFleet`(위임)이 구현했다. 취소 동작에서 새 private 함수 `Middleware.attachTransportForCancel`은 라운드에서 도는 단위 없이 취소가 걸렸을 때 기존 `attachForCancel`(로봇) 다음에 불렀다. 대기 중인 플릿 운반 단위의 지금 시도 참조로 `find`를 불러 찾으면 붙었다(단위 `RUNNING`, 실행 `CANCELING`, 실행의 운반 핸들). 끝나지 않은 운반이면 취소를 보내고 끝났으면 보내지 않았으며, 그 종료로 단위를 판정하는 것은 다음 라운드의 `pumpFleetUnit`이었다. 트레이스는 기존 `CANCEL_ATTACHED`였다. 찾기나 상태 읽기가 던지면 종결하지 않고 다음 라운드에 다시 찾았다(기존 `CANCEL_UNATTACHED_WAIT`). 찾았는데 없으면 대상이 아니고 지금처럼 «지울 것 없음»으로 종결했다. 플릿에 조회가 없으면(`ExecutionLookup.NONE`) 판단 기록이 지금 시도를 맡겼다고 적은(요청 시각이 있는) 단위를 운영자 보류에 두고 인시던트를 열며 `CANCEL_UNSENT` 트레이스에 까닭 «플릿에 맡긴 운반을 찾을 조회가 없다»를 남겼다. 기록이 맡겼다고 적지 않은 단위는 대상이 아니었다.

    바뀐 것은 공개 포트 `AmrFleetPort`의 메서드 `find` 하나였다(구현체는 이 저장소 안에만 있었다). 취소와 읽기뿐이라 발행 여부는 묻지 않았다(플릿 운반은 원래 묻지 않았다). `Middleware.cancel` KDoc과 접합부 문서 `docs/seams.md`의 `AmrFleetPort` 인터페이스 목록에 반영했다. 계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 2)은 바뀌지 않았다. 새 트레이스 종류도 없었다.

    새 시험 클래스 `ResumeFleetCancelTest`에 4건(`판단 기록이 건 취소를 들고 오면 맡긴 운반을 새로 맡기지 않고 찾아 멈춘 뒤 중단으로 종결한다`, `판단 기록이 건 취소를 들고 와도 맡기지 않은 운반은 찾기만 하고 새로 맡기지 않은 채 중단으로 종결한다`, `조회가 없는 플릿에 맡긴 운반은 건 취소를 들고 다시 지으면 운영자 보류에 두고 새로 맡기지 않는다`, `조회가 없는 플릿이라도 판단 기록이 맡겼다고 적지 않은 운반 단위는 보류에 두지 않고 중단으로 종결한다`)을 더해 전체 시험은 2,260건에서 2,264건이 되었다. 앞 인스턴스의 취소는 플릿에 닿지 않게 했다(`FlakyCancelFleet`). 결함 주입 8건(부착 안 함, 찾기 대신 재전송, 조회 없음 무시, 취소 안 보냄, 인시던트 없음, 미전송 트레이스 없음, 부착 트레이스 없음, 기록 안 봄)을 넣어 모두 해당 시험이 잡았다.

    한계 레지스터에서는 내부 행 «§15.230 · 재기동 뒤 하위 취소»에서 플릿 운반 단위를 빼고 좁혔다(남은 것은 판단 기록 없이 또는 버전 1 기록으로 리빌드한 실행, `settleLate` 지연 취소와 개정 응답의 미검증 분기, 기한 없는 `CANCELING` 대기). 미결 한계 92개(내부 37, 소비자 대기 26, 외부 29)와 의도적 제외 13은 그대로였다.

253. **판단 기록이 건 취소를 보존하도록 버전을 올려 리빌드한 실행이 하위 취소를 요청된 취소로 정착시키고 인시던트를 열지 않도록 했다.**

    이 층이 취소를 건 뒤 하위 태스크가 `CANCELLING`인 동안 프로세스가 죽으면 판단 기록이 건 취소를 보존하지 않아, `resume`으로 리빌드한 실행은 그 사이 취소로 끝난 하위 태스크를 요청 없는 취소로 읽고 운영자 보류와 인시던트를 열었다(§15.230). §15.231이 담는 쪽이 보존한 취소를 첫 pump 전에 다시 걸면 요청된 취소로 정착하도록 좁혔으나, 담는 쪽이 다시 걸지 않거나 첫 pump가 붙은 뒤에 걸면 같은 운영자 보류가 남았다. 행 «§15.230 · 재기동 뒤 하위 취소»의 해소 조건은 판단 기록이 취소 요청을 보존하는 새 버전이었다.

    `JudgmentRecord`에 칸 `cancelRequested`(실행에 취소가 걸렸는가, 하위에 닿았는지는 들지 않음)를 더했다. `Middleware.judgmentRecord`가 실행의 취소 표시를 실었다. `resume`이 기록을 옮길 때 그 칸이 참이면 실행을 취소가 걸린 채(`CANCELING`)로 짓고 트레이스 `RESUME_RECORD`에 남겼다. 하위로 보내는 것은 첫 pump이며 붙지 않은 태스크는 기존 `attachForCancel`이 `StartTask` 없이 붙어 멈췄다. 그래서 담는 쪽이 취소를 다시 걸지 않아도 그 하위 취소는 요청된 취소로 중단에 정착하고 인시던트가 열리지 않았다. 담는 쪽이 다시 걸어도 같았다.

    `JudgmentRecordWire.SCHEMA_VERSION`을 1에서 2로 올렸다. 최상위 칸 `cancelRequested`(참거짓)가 늘었고, 읽기는 버전 1과 2를 받았다. 버전 1 기록은 그 칸 없이 읽고 취소가 걸리지 않은 기록이 되며, 그 실행이 하위의 취소를 만나면 지금처럼 요청 없는 취소로 운영자 보류에 서므로 모르는 사실이 진행 쪽으로 접히지 않았다. 버전마다 칸 목록이 정확해야 하고(버전 1에 그 칸이 있으면 모르는 칸, 버전 2에 없으면 칸이 없다), 값이 참거짓이 아니면 거부했다. 담는 쪽(picasso-ops)이 이미 적어 둔 버전 1 기록으로도 리빌드할 수 있었다. 판단 기록 `JudgmentRecordWire` 버전이 1에서 2로 바뀌었으나, 계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6은 바뀌지 않았다. 새 트레이스 종류도 없었다(기존 `RESUME_RECORD`).

    `ResumeCancelTest`에 2건(`앞 인스턴스가 건 취소를 판단 기록이 들고 오면 다시 지은 실행이 그 하위 취소를 요청된 취소로 종결한다`, `건 취소 칸이 없는 버전 1 기록으로 다시 지으면 그 하위 취소를 요청 없는 취소로 읽어 운영자 보류에 세운다`), `JudgmentRecordWireTest`에 1건(`버전 1 기록은 건 취소 칸 없이 읽고 취소가 걸리지 않은 기록이 된다`)을 더해 전체 시험은 2,257건에서 2,260건이 되었다. 기존 `JudgmentRecordWireTest`의 버전 거부 시험은 바꾼 문자열을 버전 2 기준으로 고쳤다. 결함 주입 7건(복원 안 함, 상태 안 바꿈, 기록에 안 실음, 인코딩 거짓, 버전 1 거부, 버전 1 칸 목록, 참거짓 검사 없음)을 넣어 모두 해당 시험이 잡았다.

    한계 레지스터에서는 내부 행 «§15.230 · 재기동 뒤 하위 취소»를 닫지 않고 좁혔다(남은 것은 판단 기록 없이 또는 버전 1 기록으로 리빌드한 실행, 플릿 운반 단위, `settleLate` 지연 취소와 개정 응답의 미검증 분기, 기한 없는 `CANCELING` 대기). 미결 한계 92개(내부 37, 소비자 대기 26, 외부 29)와 의도적 제외 13은 그대로였다. 행 «§15.250 · 리빌드 뒤 파지 이력»의 해소 조건 낱말 «판 올림»을 «버전 올림»으로 고쳤다. `docs/orchestration.md` §9, `docs/glossary.md`, `docs/contract.md`의 판단 기록 버전과 칸을 고쳤다.

252. **리빌드한 실행이 스냅숏의 태스크를 확인해 `StartTask` 없이 재부착하도록 하여 게이트 차단 없이 태스크를 따라가도록 했다.**

    `resume`으로 리빌드한 실행의 첫 pump는 붙지 않은 로봇 단위를 보통의 출발 경로로 보내 같은 태스크 id로 `StartTask`를 다시 보냈다(기체의 기존 핸들에 붙는 계약의 멱등). 이 재전송을 새 접수와 가를 수 없어 발행 게이트(작업 허가, 그리고 §15.249 뒤로는 퇴역)가 차단했고, 결함 게이트도 그 앞에서 차단했다. 그동안 기체는 그 태스크를 계속했고 이 인스턴스는 따라가지 않았다(단위는 `PENDING`). 이 행의 해소 조건은 리빌드가 스냅숏의 태스크 존재로 재부착을 새 발행과 가르는 것이었다(«§15.219 · 리빌드 재부착»).

    새 함수 `Middleware.reattachFromSnapshot`(private)을 라운드에서 다음 대기 단위(`PENDING`)가 로봇 경로이면 결함 게이트와 발행 게이트보다 먼저 부르도록 했다. 그 단위가 리빌드가 붙지 않은 채 둔 단위(`Execution.unattached`의 시도가 단위의 시도와 같음)이고, 기체 뷰를 관측할 수 있고, 스냅숏에 그 단위의 지금 시도의 태스크 id가 있고, 스냅숏의 그 태스크 리비전이 단위의 리비전과 같으면 `StartTask` 없이 태스크 id와 리비전으로 핸들을 지어 붙도록 했다. 단위는 `RUNNING`, 실행 물리 상태는 `RUNNING`이 되었다. 붙는 데 새 로봇 명령이 없어 발행 게이트를 묻지 않는 동작이었다. 요청 시각은 기존 규칙대로 판단 기록이 넘긴 이 시도의 요청 시각을 쓰고 없으면 지금으로 했다(`requestedAtOf`). 새 트레이스 종류 `RESUME_REATTACHED`(`Middleware.RESUME_REATTACHED`, 내용 `<단위>: 스냅숏의 <태스크>=<상태>(리비전 N)에 StartTask 없이 붙었다`)를 남겼고, 단위 메모에는 남기지 않았다(리빌드의 다른 사실과 같이 자취에 남김). 이미 끝난 태스크에도 붙었으며 그 종료는 다음 라운드의 `pumpRobotUnit`이 `WatchTask(0)`으로 읽도록 했다. 스냅숏을 못 읽었거나, 태스크가 없거나, 리비전이 다르거나 비어 있으면(리비전을 싣지 않는 포트) 붙지 않고 지금처럼 같은 태스크 id의 재전송이 게이트를 지나도록 했다. 리비전이 다르면 그 재전송은 개정 갱신이라 새 명령이었다. 리비전을 싣지 않는 포트의 남은 한계는 기존 행 «§15.238 · 같은 리비전 부착»이 맡도록 했다. `Middleware.resume`의 KDoc에는 첫 pump의 재부착 순서를 적었다.

    계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 1)은 바뀌지 않았다. 새 공개 면은 트레이스 종류 `RESUME_REATTACHED` 하나였다.

    `ResumeTest`에 3건을 더해 전체 시험은 2,254건에서 2,257건이 되었다. 시험 이름은 `작업 허가가 막아도 스냅숏에 보이는 지금 시도의 태스크에는 StartTask 없이 다시 붙는다`, `퇴역한 기체라도 다시 붙은 태스크는 끝까지 따라가 끝을 읽는다`, `스냅숏의 리비전이 단위와 다르면 다시 붙지 않고 재전송이 게이트에 막힌다`이었다. `ResumeTest`의 기체 더블이 스냅숏에 리비전을 실을 수 있게 했다(`hold`의 `revision` 인자, 비우면 리비전을 안 싣는 포트). `ResumeTest`의 기존 시험은 바꾸지 않았다(기존 더블은 리비전을 싣지 않아 지금처럼 재전송으로 붙는다). `ResumeCancelTest`의 `작업 허가가 막아도 다시 지은 실행의 취소는 기체에 나간다`는 시험의 전제(발행 게이트가 다시 붙는 `StartTask`를 막는다)를 새 동작(게이트를 묻지 않고 붙고 `StartTask`가 나가지 않는다)으로 바꾸었고, 취소가 기체에 나가는 단언은 그대로 두었다. 결함 주입 5건(재부착 안 함, 리비전 안 봄, 자취 없음, 핸들 안 지음, 재부착을 게이트 뒤로 옮김)을 넣어 모두 해당 시험이 잡았고, 바꾼 `ResumeCancelTest`의 전제도 재부착 안 함 1건으로 잡혔다.

    한계 레지스터에서는 내부 행 «§15.219 · 리빌드 재부착»을 닫았다. 미결 한계는 93개에서 92개(내부 37, 소비자 대기 26, 외부 29)가 되었고 의도적 제외 13은 그대로였다. 항목 219에는 «(닫힘, §15.252)» 문단을 더했다. §15.219의 다른 행(플릿 경로 등)은 열려 있었다.

251. **연결이 끊긴 기체에 닿지 않는 취소를 미확정 유예 뒤 운영자 보류로 넘겨 기체를 내주지 않도록 했다.**

    연결 끊김으로 판정한 뒤에도(§15.243의 `LINK_BROKEN`) 그동안 건 취소는 매 라운드 다시 보내고(`CANCEL_UNSENT`) 실행은 시한 없이 `CANCELING`에 머물렀으며, 기체가 멈췄는지 확인할 수 없어 기체를 내주지 않는 쪽이었다(«§15.243 · 끊긴 동안의 취소»). 해소 조건은 끊긴 기체에 건 취소를 운영자 보류로 넘기는 규칙(§15.238의 미전송 취소 보류처럼)이나 취소 대기의 시한을 정하는 것이었다. 2026-11-02 소유자는 기체가 멈췄는지 모르는 채 기체를 내주게 되는 취소 대기 시한 뒤 중단으로 닫는 안을 고르지 않고, 운영자 보류로 넘기는 쪽을 택했다.

    새 함수 `Middleware.holdUnreachedCancel`(private)을 라운드에서 도는 로봇 경로 단위를 진행하기(`pumpRobotUnit`) 전에 부르도록 했다. 취소가 걸렸고(`cancelRequested`), 그 취소가 하위에 닿지 않았고(`cancelUnsent`), 연결이 끊긴 채(`linkBroken`)인 것을 처음 본 라운드의 시각을 실행 칸 `Execution.unreachedCancelSince`(internal)에 적었다. 셋 중 하나라도 풀리면 걷었으며, 판단 기록에는 싣지 않았다. 그 시각부터 미확정 유예(`inDoubtGrace`, 기본 60초, 미확정 해소와 같은 값)가 지나면 그 단위를 운영자 보류(`OPERATOR_HOLD`)에 두었다. 실행 칸 `doubtCancelHold`에 그 단위를 적어 실행을 중단으로 닫지 않고 운영자 판단을 기다리게 했다(기존 `cancelWaitsOnHeldDoubt` 규칙). 트레이스 `CANCEL_UNSENT`(기존 종류)에 까닭 «연결이 끊긴 채 미확정 유예 안에 닿지 않았다»를 남겼고, 단위 메모는 `cancel requested but never reached the downstream: link broken since <시각>, not answered within grace`로 적었으며 인시던트로 봉했다. 핸들은 다른 종착 경로처럼 포트에서 놓았다. 유예 안에 취소가 닿으면(라운드 머리의 기존 다시 보내기, `CANCEL_RESENT`) 끊김 판정이 그대로여도 보류하지 않고 따라갔으며, 연결이 돌아오면 기존 경로대로 중단으로 끝났다. 보류 중에는 기존 §15.244 규칙(`resendHeldCancel`)이 그대로 쓰였다. 스냅숏에 그 태스크가 같은 리비전으로 끝나지 않은 채 보이면 키로 지은 핸들로 취소를 다시 보내고 보류는 유지했다. 운영자가 판단하면 실행은 중단으로 끝났고, 취소가 끝내 닿지 않았으면 취소 응답 거부 사유에 `CANCEL_NOT_SENT`가 실렸다.

    계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 1)은 바뀌지 않았다. 새 트레이스 종류도 새 공개 면도 없었다.

    `InDoubtTest`에 2건을 더해 전체 시험은 2,252건에서 2,254건이 되었다. 시험 이름은 `끊긴 기체에 건 취소가 유예 안에 닿지 않으면 운영자 보류에 두고 기체를 내주지 않는다`, `끊긴 기체의 취소가 유예 안에 닿으면 보류 없이 따라가 중단으로 끝난다`(스냅숏은 계속 못 읽고 끊김 판정도 그대로인데 취소만 닿는 경우)이었다. 시험 더블 `LinkDownRobotPort`에 `cancelThrough`(끊긴 동안에도 취소만은 닿음)를 더했다. 결함 주입 8건(유예 무시, 보류 안 함, 트레이스 없음, 메모 없음, 인시던트 없음, 보류 표식 없음, 닿지 않은 취소를 닿았다고 지움, 닿은 취소도 보류)을 넣어 모두 해당 시험이 잡았다.

    한계 레지스터에서는 내부 행 «§15.243 · 끊긴 동안의 취소»를 닫았다. 미결 한계는 94개에서 93개(하위 범주별로 내부 38, 소비자 대기 26, 외부 29)가 되었고 의도적 제외 13은 그대로였다. 항목 243에는 «(닫힘, §15.251)» 문단을 더했다.

250. **리빌드 뒤 재할당이 지난 파지 이력을 잃어 자재를 다룬 단위를 거르지 못하는 공백을 한계 레지스터 내부 행으로 남겼다.**

    코드를 고치지 않고 공백 하나를 한계 레지스터 내부 행 `§15.250 · 리빌드 뒤 파지 이력`으로 기록한 문서 작업이었다. 리비전이 재작업 단위의 시도와 앞 시도 파지 정보를 옮기는 §15.247 작업을 진행하며 드러난 공백이었다. 단위에는 이번 시도에서 쥔 것이 관측됐는지를 나타내는 `everHeld`와 앞 시도에서 쥔 것이 관측됐는지를 나타내는 `heldInEarlierAttempt`(§15.236이 둠)라는 두 파지 이력 칸이 있었다. 재작업이 새 시도를 낼 때 `everHeld`를 여기에 접어 넣고 비웠으며, 재할당(`Middleware.reassign`)은 반쯤 한 물리 작업의 인계를 보장하지 않으므로 로봇 경로 단위 가운데 둘 중 하나라도 참인 단위가 있으면 자재를 다룬 단위가 있다는 거부 사유로 재할당을 차단했다.

    판단 기록(`JudgmentRecord`, 단위 행 `UnitRecord`)은 시도, 태스크 id, 요청 시각, 근거 창, 판정, 운영자 판단만 싣고 두 칸을 싣지 않았다. 그래서 `resume`으로 리빌드한 단위는 두 칸이 거짓으로 시작했다. `everHeld`는 그 단위의 태스크 상태나 갱신에서 든 채를 다시 관측해야 참이 되었고, 끝난 시도의 파지는 계약 스냅숏에 실리지 않아 다시 관측되지 않았다. 그 결과 재기동 전에 쥐었다가 놓은 단위나 재작업으로 앞 시도의 파지를 접은 단위가 있어도, 리빌드 뒤에는 재할당이 그 실행을 자재를 다룬 단위가 있다는 거부 사유로 거르지 못했다. 다만 종료한 실행, 도는 단위, 끝난 단위, 최소 유지 시간, 부하 차를 검사하는 다른 게이트는 그대로 동작했다.

    한계 레지스터의 기존 행 `§15.221 · 기록이 들지 않는 것`은 `everHeld`를 포함한 파지 관측이 판단이 아니라 관측이라 리빌드한 실행이 기체에서 다시 관측한다고 적고 있었으나, 파지 이력은 지금의 상태가 아니라 지난 관측이라 다시 관측되지 않았다. 이에 따라 해당 행에 이 예외를 적고 §15.250을 가리키게 했다. 지금 `reassign`을 부르는 운영 호출자는 없었으며 `picasso-ops`도 부르지 않아 당장 드러나는 동작은 없었다.

    한계 레지스터 미결 항목은 93개에서 94개(내부 39, 소비자 대기 26, 외부 29)로 늘었고, 의도적 제외 13은 그대로였다. 계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 1), 코드와 시험은 바뀌지 않았다.

    (닫힘, §15.257) 이 항목이 연 행 «§15.250 · 리빌드 뒤 파지 이력»은 판단 기록의 단위 행이 파지 이력 두 칸을 싣도록 `JudgmentRecordWire`를 버전 3으로 올려 §15.257에서 닫혔다. 이 항목이 «당장 드러나는 동작 영향은 없음»이라 적은 것은 틀렸었다: 재할당 말고도 효과-관측 대조(`judgeEffectMismatch`)가 `everHeld`를 읽어, 리빌드 뒤 쥐었다 놓친 단위가 완료하지 못하고 끝나면 어긋남 판정이 빠졌다.

249. **퇴역한 기체로는 새 로봇 명령을 내지 않고 재할당 후보에서도 빼게 했다.**

    한계 레지스터 내부 행 `§15.123 · 기체 퇴역 시 작업 즉시 중단 미집행`(해소 조건: 퇴역 명령 시 해당 기체의 런타임 바인딩 및 활성 태스크 자동 취소 연계)을 닫는 작업이었다. 퇴역은 registry 모듈의 원장에만 있었고(`POST /operations/robots/{robotId}/retirement`), 미들웨어는 퇴역을 몰라서 퇴역한 기체에도 새 단위와 재할당이 그대로 나갔다. 해소 조건의 자동 취소는 `docs/commissioning.md` §5(퇴역은 목록 비활성화만 뜻하고 도는 동작을 강제로 멈추지 않음)와 picasso-ops 운영 관리 화면 생애주기 설계(퇴역이 도는 작업의 자동 취소라는 뜻이 되면 안 됨)와 어긋났다. 2026-11-02 소유자 결정으로 새 작업만 막는 쪽을 택했다(자동 취소, 코드 없이 의도적 제외로 옮기는 안은 고르지 않음).

    새 포트 `RetirementPort`(`fun retired(): Map<String, String>`, 지금 퇴역한 기체 id 와 사유)를 추가했다. `Middleware(retirements = ...)`로 붙이고 기본은 널(붙이지 않음, 오늘의 동작 그대로)이며, 구현은 담는 쪽이 원장에서 짓게 했다. 이는 발행 게이트(`IssuingGate`)의 셋째 입력이 되었다(첫째 유효 권위, 둘째 작업 허가). 새 로봇 명령이 나가는 세 자리(첫 출발, 개정의 갱신, 미확정 조회) 모두 같은 게이트를 지나게 했다. 퇴역한 기체는 `INHIBITED`로 막히고 까닭은 `PermitCause.RETIRED`(새 값), 주체 칸에 퇴역 사유, 풀릴 근거는 운영자가 이 기체의 퇴역을 취소할 때였다. 차단은 `Execution.permitBlock`에 보이고 실패도 인시던트도 아닌 기다림이었다. 퇴역을 취소(`DELETE /operations/robots/{robotId}/retirement`)하면 다음 라운드에 막힌 단위가 나가게 했다. 퇴역한 기체는 작업 허가를 따로 판정하지 않았다. 재할당(`Middleware.reassign`)은 퇴역한 후보를 거르게 했다.

    도는 단위는 끊지 않았다. 취소도 관측도 막지 않았으며, 그 기체에서 도는 일을 멈추려면 운영자가 취소하게 했다. 포트가 예외를 던지면 아무것도 막지 않았다. 작업 허가(모르면 차단)와 반대 쪽이었다. 퇴역은 안전 상태가 아니라 운영자 판단이라 원장을 못 읽은 것으로 현장 전체를 세우지 않았다. 포트는 호스트 잠금 아래에서 불리므로 막히지 않고 이미 읽어 둔 스냅숏을 돌려주어야 했다(작업 허가와 같은 전제였다).

    계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 1), registry의 퇴역 API는 바뀌지 않았다. 새 시험 클래스 `RobotRetirementTest` 5건으로 전체 시험은 2,247건에서 2,252건이 되었다. 새 시험 이름은 `퇴역한 기체에는 첫 단위를 내지 않고 퇴역 사유와 풀릴 근거가 차단으로 보인다`, `퇴역해도 도는 단위는 끊지 않고 다음 단위부터 막는다`, `퇴역을 취소하면 막힌 단위가 그때 나간다`, `원장을 못 읽으면 퇴역으로 막지 않는다`, `재할당은 퇴역한 후보를 거르고 다음 후보로 옮긴다`였다. 결함 주입 4건(게이트가 퇴역을 안 봄, 원장을 못 읽으면 차단, 재할당이 후보를 안 거름, 퇴역 사유를 안 실음)을 넣어 모두 해당 시험이 잡았다.

    한계 레지스터에서 내부 행 `§15.123 · 기체 퇴역 시 작업 즉시 중단 미집행`을 닫았다. 미결 한계는 94개에서 93개(내부 38, 소비자 대기 26, 외부 29)가 되었고 의도적 제외 13은 그대로였다.

248. **결함 수용이 결함이 걷히면 그 키를 놓아 같은 키로 다시 생긴 결함을 다시 막게 했다.**

    한계 레지스터 내부 행 `§15.242 · 결함 수용의 범위`를 닫는 작업이다. 결함 수용(`Middleware.release`)은 차단 중인 결함의 키(결함 유형과 참조)를 `Execution.acknowledgedFaults`에 더하고 결함 게이트(`blockingFaults`)는 이 집합의 결함을 건너뛴다. 이 집합은 늘기만 해서, 결함이 사라졌다가 같은 키로 다시 생겨도 그 실행이 끝날 때까지 다시 막지 않았다. 용어집 정의(같은 결함은 다시 막지 않고 새 결함만 막음)와 어긋났다. 2026-11-02 소유자 결정으로 결함이 걷히면 키를 놓는 쪽을 택했다(단위 범위로 좁히는 안은 고르지 않음).

    기체 동기화(`sync`)가 스냅숏을 읽은 라운드에 그 기체의 스냅숏에 없는 수용 키를 놓는다. 재생 이벤트 가운데 결함 걷힘(`FAULT_CLEARED`)이 오면 스냅숏에 같은 키가 다시 보여도 놓는다. 스냅숏 둘 사이에 걷혔다가 다시 생긴 결함은 스냅숏만으로는 같은 결함으로 보이기 때문이다. 키를 놓으면 실행 트레이스 `ACKNOWLEDGED_FAULT_CLEARED`를 남긴다(키와 까닭, 다시 생기면 새 결함으로 막는다는 문구). 놓는 일은 내부 함수 `forgetAcknowledged` 하나가 했다.

    스냅숏을 못 읽은 라운드에는 놓지 않는다. 못 읽은 것은 걷혔다는 관측이 아니기 때문이다. 실행이 기체 둘 이상을 쓰면(단위의 기체 묶음, `robotBinding`) 그 실행의 다른 기체가 지금 관측 가능하고 그 키를 보이는 동안은 놓지 않는다. 키에 기체가 없어서, 놓으면 그 결함이 없는 기체의 스냅숏 하나가 다른 기체에서 수용한 결함을 라운드마다 다시 막기 때문이다. 결함이 계속 남아 있으면 지금처럼 뒤 단위도 다시 막지 않는다(`InspectAssetTest` 그대로). 걷힘 이벤트가 운영자가 수용하기 전에 일어난 걷힘이어도 놓으므로, 그 경우 다시 막는 쪽으로 기운다(발신자 시계와 비교하지 않는다).

    판단 기록은 그대로 `acknowledgedFaults`를 싣고 `resume`이 복원하며, 리빌드 뒤 첫 스냅숏에 그 키가 없으면 놓는다. 계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 1)은 바뀌지 않았다. 에뮬레이터에는 다음 태스크를 막는 결함을 걷는 표면이 없어서(그런 모드는 모두 `UNTIL_CLEARED`) 결함을 손으로 세우고 걷는 포트로 시험했다. 새 시험 클래스 `AcknowledgedFaultTest` 4건으로 전체 시험은 2,243건에서 2,247건이 되었다. 새 시험 이름은 `수용한 결함이 스냅숏에서 사라졌다가 다시 생기면 다음 단위를 다시 막는다`, `걷힘 이벤트가 오면 스냅숏에 같은 결함이 다시 있어도 새 결함으로 다시 막는다`, `스냅숏을 못 읽은 라운드에는 수용을 놓지 않는다`, `실행의 다른 기체가 그 결함을 보이는 동안은 결함이 없는 기체의 스냅숏으로 수용을 놓지 않는다`이다. 결함 주입 6건(스냅숏으로 놓지 않음, 걷힘 이벤트로 놓지 않음, 다른 기체 예외 없음, 못 읽은 라운드에 놓음, 키를 지우지 않음, 트레이스를 남기지 않음)을 넣어 모두 해당 시험이 잡았다.

    한계 레지스터에서 내부 행 `§15.242 · 결함 수용의 범위`를 닫았다. 미결 한계는 95개에서 94개(내부 39, 소비자 대기 26, 외부 29)가 되었고 의도적 제외 13은 그대로였다.

247. **리비전이 재작업된 단위의 시도와 앞 시도 파지 정보를 새 단위에 옮기게 했다.**

    §15.246(재작업 시도 관측 칸)을 하며 코드를 읽다 찾았다. 한계 레지스터에 행이 없던 결함이다. 리비전(같은 작업 지시의 더 높은 버전, `Middleware.revise`)은 아직 내지 않은 `PENDING` 단위와 태스크 없이 실패한 `FAILED` 단위를 새 계획의 단위로 바꾼다. 새 계획의 단위는 시도 0이다. 운영자 재작업(`resolve`의 `REWORK`)은 단위를 시도 +1의 `PENDING`으로 되돌리므로, 재작업 뒤 다음 라운드가 그 단위를 내기 전에 리비전이 오면 재작업이 올린 시도가 0으로 돌아갔다. 태스크 id는 시도로 짓는다(`<작업 지시>#<단위>`, 시도가 1 이상이면 `@r<시도>`를 붙임). 그래서 다음 출발이 첫 시도의 태스크 id로 나가고, 같은 태스크 id로 보내면 기체에 있던 첫 시도의 태스크에 붙었다. §15.246이 둔 `heldInEarlierAttempt`(앞 시도에서 쥔 것을 본 사실)도 새 단위에서 사라져, 재할당이 반쯤 한 물리 작업을 거르지 못했다.

    `revise`가 단위를 새 계획의 단위로 바꿀 때 내부 확장 함수 `replannedAs`로 시도(`attempt`)와 `heldInEarlierAttempt`를 새 단위에 옮겼다. `PENDING` 갈래와 태스크 없는 `FAILED` 갈래가 함께 쓴다. 새 계획에 그 단위가 없으면 전처럼 그대로 두었다. 태스크 없는 `FAILED` 갈래는 시험이 따로 없다. 그 갈래에서 시도가 1 이상인 단위는 건너뛴 점검(`PREDECESSOR_NOT_DONE`)을 재작업한 뒤 선행 이동이 다시 실패한 경우뿐이고, 선행 이동은 시도된 실패로 남아 리비전 뒤에도 점검은 다시 건너뛰어지므로 결과가 같기 때문이다. 일관성을 위해 같은 함수를 썼다.

    계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 1), 리비전의 래치 규칙(끝났거나 시도된 실패인 단위는 바꾸지 않음)은 바뀌지 않았다.

    새 시험 클래스 `ReworkRevisionTest` 2건을 더해 전체 시험은 2,241건에서 2,243건이 되었다. 새 시험 이름은 «재작업 직후 온 리비전도 새 시도로 낸다», «재작업 직후 온 리비전 뒤에도 앞 시도에서 쥔 단위는 재할당하지 않는다»이다. 첫 시험은 main에서 «리비전이 재작업의 시도를 지웠다»(기대 1, 실제 0)로 실패했다. 결함 주입 3건(리비전이 `replannedAs`를 안 씀, 시도를 안 옮김, 앞 시도 파지를 안 옮김)을 넣어 모두 해당 시험이 잡았다.

    한계 레지스터에서는 행을 열거나 닫지 않았다(전체 95, 내부 40, 소비자 대기 26, 외부 29, 의도적 제외 13은 그대로).

246. **운영자 재작업이 새 시도를 낼 때 단위의 시도별 관측 칸을 계획이 처음 지은 값으로 비우게 했다.**

    한계 레지스터 내부 행 «§15.236 · 재작업 관측 칸»은 §15.236(건너뛴 대상 재작업 확장)이 열었으며, 행의 해소 조건은 «새 시도를 낼 때 단위의 시도별 관측 칸을 초기화하도록 `reworkAsNewAttempt`를 개편할 때»였다. 운영자 재작업(`Middleware.resolve`의 `REWORK`, 보류 단위와 건너뛴 대상 두 경로가 함께 쓰는 `reworkAsNewAttempt`)은 시도 번호, 상태(`PENDING`), 태스크 id, 실패 분류, 대조 결과(`verification`), 메모만 새로 두고 단위의 시도별 관측 칸은 앞 시도 값 그대로 두었다. 인시던트 번들(`IncidentBundle`)은 단위의 `holdMismatch`(`effectMismatch`), `holdExpected`, `fault`, `reached`, `preconditionSubjects`, `progressStalled`를 옮겨 싣기 때문에 새 시도가 실패하면 그 번들에 앞 시도의 어긋남과 사전 조건 주어가 실릴 수 있었다. `judgeEffectMismatch`는 같은 어긋남이면 다시 적지 않으므로 앞 시도의 어긋남이 남아 있으면 새 시도의 같은 어긋남에 인시던트 표시가 서지 않았다. 정체는 한 번만 알리므로(`progressStalled`) 앞 시도에서 정체를 알렸으면 새 시도에서는 다시 알리지 않았다. `everHeld`는 중단 시점의 파지 기대를 정하므로 앞 시도에서 쥐었으면 쥔 적 없는 새 시도도 든 채를 기대했다.

    `reworkAsNewAttempt`가 새 시도를 낼 때 시도별 관측 칸 16개를 계획이 처음 지은 단위의 값으로 비웠다. 비운 칸은 `everHeld`(false), `holdExpected`, `holdMismatch`, `fault`, `reached`(E0), `downstreamDoneAt`, `evidenceDeadline`, `evidenceAt`, `rechecks`(0), `requestedAt`, `progress`(0.0), `progressAt`, `progressObservable`, `progressStalled`(false), `preconditionSubjects`(빈 목록), `result`이다. 미확정 조회 횟수와 시각들(`lookups`, `lastLookupAt`, `streamLostAt`, `keyedCancelAt`)은 전처럼 출발(`startUnit`)에서 비웠다. `hold`(기체의 지금 파지 관측)는 비우지 않았다.

    재할당(`Middleware.reassign`)은 로봇 단위가 한 번이라도 쥔 것이 관측됐으면 반쯤 한 물리 작업의 인계를 보장하지 않으므로 «자재를 다룬 단위가 있다»로 옮기지 않는다. `everHeld`만 비우면 이 보호가 재작업 뒤에 풀리므로, `ExecutionUnit`에 내부 본문 속성 `heldInEarlierAttempt`를 두어 재작업이 `everHeld`를 접어 넣고 재할당은 `everHeld`와 `heldInEarlierAttempt`를 함께 보게 했다. `everHeld`와 마찬가지로 `heldInEarlierAttempt`는 판단 기록에 싣지 않았다. 계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 1), 인시던트 번들의 칸, `ResolveOutcome`의 세 결과는 바꾸지 않았다.

    새 시험 클래스 `ReworkAttemptTest` 3건을 더해 전체는 2,238건에서 2,241건이 되었다. 운영자 보류에 선 단위의 칸 가운데 에뮬레이터이 늘 채우지 않는 것(파지, 정체, 진행률, 근거 등급 등)은 시험이 앞 시도 값처럼 채워 두었다. 시험 이름은 «재작업은 새 시도의 관측 칸을 계획이 처음 지은 값으로 비운다», «재작업한 시도의 인시던트 번들은 앞 시도의 어긋남과 결함을 싣지 않는다», «앞 시도에서 쥔 것을 본 단위는 재작업 뒤에도 재할당하지 않는다»이다. 결함 주입 18건(비우는 줄 16건, 재할당이 앞 시도를 안 보게 1건, 접어 넣지 않게 1건)을 넣어 모두 해당 시험이 잡았다. 근거 등급 주입은 보류 단위의 근거 등급이 원래 E0이라 처음에 등가 변이였으나, 시험이 E1을 채우게 고친 뒤 잡혔다.

    한계 레지스터에서는 내부 행 «§15.236 · 재작업 관측 칸»을 닫았다(내부 41→40, 전체 96→95, 소비자 대기 26, 외부 29, 의도적 제외 13은 그대로). 같은 항목이 연 소비자 대기 행 «§15.236 · 재작업 일반화»는 열려 있다. 항목 236에 «(닫힘, §15.246)» 문단을 더했다.

245. **응답 없는 사우스바운드 포트 호출 지연을 줄이기 위해 기체별 차단 창을 두었다.**

    picasso는 자체 스레드가 없고 소비자의 잠금 아래에서 실행되어, 응답 없는 하위 포트 호출 시 `PicassoClient`의 기본 기한(10초) 동안 라운드가 지연되고 기체별 호출이 누적되는 문제가 있었다(«§15.237 · 사우스바운드 동기 호출 막힘»). 2026-11-01 소유자는 네 방식(기체별 차단기, 라운드 안 호출 기한 단축, 라운드 시간 예산, 잠금 밖 미리 읽기) 가운데 기체별 차단기를 골랐다. 기한 값(10초)은 그대로 두어 현장 실측 전까지 UNKNOWN인 적정 예산을 새로 정하지 않았다.

    `ClientRobotPort` 안에 기체별 차단 창을 두었다. 단건 호출(`GetCapabilities`, `StartTask`, `CancelTask`, `GetSnapshot`, `ReplayEvents`)이 기한을 다 쓰고 `DEADLINE_EXCEEDED`로 끝나면 그 기체의 단건 호출을 차단 창(`ClientRobotPort.backoff`, 기본 30초) 동안 하위로 보내지 않고 바로 `UNAVAILABLE`로 실패시켰다. 스냅숏과 재생은 전처럼 null(못 읽음)이 되고, 출발과 취소는 던지도록 했다. 창이 지나면 한 번 다시 묻고, 답이 오면(상태든 거부든 gRPC 오류든) 창을 걷었다. 다시 기한을 넘기면 창을 새로 걸었다. 생성자는 `ClientRobotPort(client, now, backoff)`로 두어 `now`는 창을 재는 시계(기본 벽시계), `backoff`는 기본 30초로 지정했으며, 기존 호출 `ClientRobotPort(PicassoClient(...))`는 그대로 컴파일되도록 했다. 시험용 문으로 `ClientRobotPort.backingOffUntil(robotId)`(internal)를 두었다. 30초는 `LogicalCapability.linkLossWindow`(30초)와 같게 두어, 스냅숏을 못 읽는 창이 끝날 무렵 다시 묻고 끝내 답하지 않는 기체는 미들웨어가 끊김(`LINK_BROKEN`, §15.243)으로 판정하게 했다. 현장마다 바꿀 소비자가 아직 없어 이 창은 현장 시간값(`SiteTimings`)에 넣지 않았다(ADR 9).

    차단 창은 기한 초과에만 걸었다. `UNAVAILABLE`은 어댑터 호스트가 바로 돌려주는 답이기도 하므로(벤더 링크 오류, 결함을 못 보는 스냅숏) 빨리 온 답은 라운드를 늘리지 않기 때문이다. 차단 범위는 기체마다 따로 두어 한 기체의 창이 다른 기체의 호출을 막지 않게 했으며, 갱신 스트림(`WatchTask`)은 비동기라 라운드를 막지 않으므로 창과 상관없이 두었다. 이로써 답하지 않는 기체 하나가 라운드에 더하는 지연이 매 라운드 기한(10초)에서 창 하나(30초)마다 기한 한 번으로 줄었고, 취소 재전송도 창 안에서는 하위로 나가지 않게 했다.

    계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 1), `PicassoClient`의 기한은 바뀌지 않았다. 미들웨어(`Middleware.kt`)도 바뀌지 않았다.

    새 시험 클래스 `PortBreakerTest`에 4건을 추가하여 전체 시험은 2,234건에서 2,238건이 되었다. 시험 안에 답하지 않는 in-process gRPC 하위를 세우고(기한 1초) picasso 시험 의존성에 `grpc-inprocess`를 더했다. 시험 이름은 «기한을 다 쓴 기체는 차단 창 동안 하위로 묻지 않고 바로 실패한다», «차단 창이 지나면 한 번 다시 묻고 답이 오면 창을 걷는다», «한 기체의 차단 창은 다른 기체를 막지 않는다», «바로 온 오류에는 차단 창을 걸지 않는다»이다. 결함 주입 9건을 넣어 모두 해당 시험이 잡았다.

    한계 레지스터에서는 행 «§15.237 · 사우스바운드 동기 호출 막힘»을 닫지 않고 남은 범위로 좁혔다(전체 96, 내부 41, 소비자 대기 26, 외부 29, 의도적 제외 13은 그대로).

    (정정, §15.274) 이 항목은 차단 창 안의 호출을 `UNAVAILABLE`로 실패시켰고 미들웨어는 그것을 나갔는지 모르는 것으로 읽었다. 이제 차단 창은 `CommandNotSent`를 던지고 미들웨어는 보내지 않은 명령으로 읽는다.

244. **미확정 단위의 취소를 키로 지은 핸들로 보내고 운영자 보류에 둔 취소는 스냅숏에 태스크가 보이면 다시 보내도록 했다.**

    한계 레지스터 내부 행 «§15.219 · 막힌 미확정 취소»에서는 스냅숏으로 태스크를 확인하지 못하는데(스냅숏 판독 불가이거나 태스크가 안 보임) 작업 허가가 조회를 막으면 단위가 미확정(`IN_DOUBT`)에 머물러 취소가 하위에 닿지 못했다. 미확정 단위에는 핸들이 없고, 핸들은 스냅숏 부착이나 같은 참조의 `StartTask` 재전송(조회)으로만 생겼다. 내부 행 «§15.238 · 보류 뒤 취소 자동 재전송»에서는 취소가 미전송 표식(`cancelUnsent`)과 함께 운영자 보류에 머문 뒤에는 뒤늦게 스냅숏에 태스크가 보여도 기체에 취소를 다시 보내지 않고 운영자 해소를 기다렸다. 2026-11-01 소유자는 미확정 단위의 취소는 태스크 id와 리비전으로 지은 핸들로 보내고, 보류에 둔 취소는 스냅숏에 태스크가 보이면 자동으로 다시 보내되 운영자 보류는 유지하기로 결정했다.

    `resolveDoubt`에서 스냅숏 부착(⓪) 다음, 로봇 경로이고 취소가 걸렸으면 `handleFor(robot, taskId, revision)`로 핸들을 지어 `CancelTask`를 보내도록 했다(`Middleware.cancelByKey`). 작업 허가는 묻지 않았다. 취소는 새 발행이 아니며(ADR 52: 허가는 새 발행을 막고 취소와 관측은 막지 않는다), 그래서 허가가 조회를 막은 동안에도 나간다. 간격은 기존 `LOOKUP_INTERVAL`(2초)이다. 단위 본문 속성 `ExecutionUnit.keyedCancelAt`(internal)이 마지막으로 보낸 시각을 들며, 새 시도를 내거나(`startUnit`) 취소용으로 붙으면(`attachForCancel`) 걷히고 판단 기록에는 싣지 않는다. 기체가 상태로 답하면 그 태스크가 있는 것이고 그 핸들로 붙는다. 단위는 `RUNNING`, 실행 물리 상태는 `CANCELING`, 트레이스는 `DOUBT_ATTACHED`(기존 종류), 단위 메모는 `IN_DOUBT resolved by keyed cancel: <태스크> revision <N>`이 된다. 다음 라운드의 `pumpRobotUnit`이 `WatchTask(0)`으로 처음부터 되짚어 취소의 끝을 읽는다. 기체가 `CANCEL_UNSUPPORTED`로 거부하면 그것도 기체가 그 태스크를 안다는 답이라 거부 사유를 싣고(`cancelRefusal`) 붙어 끝까지 따라간다. 던지거나 다른 거부면 붙지 않는다. 기체가 그 태스크를 모른다는 답(`NOT_FOUND`, 미믹은 gRPC 상태로 던진다)은 기체가 쉬고 있다는 증거가 아니며, 원 요청이 아직 오는 중일 수 있다.

    로봇 경로에서 취소가 걸리면 같은 참조의 `StartTask` 재전송(조회, ①)을 건너뛰도록 했다. 멈추려는 태스크를 다시 보내면 원 요청이 안 닿았던 기체가 그 일을 새로 시작하기 때문이다. 대신 설비 관측(유예 `inDoubtGrace`)과 운영자 보류로 간다. 운영자 보류로 넘어갈 때 로봇 경로의 `CANCEL_UNSENT` 까닭은 «미확정이라 태스크 핸들이 없고 키로 보낸 취소도 기체가 받지 않았다»이고 단위 메모에 `keyed cancel not accepted`가 붙는다. 플릿 경로는 옛 까닭(«미확정이라 태스크 핸들이 없다»)을 그대로 쓴다.

    운영자 보류 단위가 미전송 취소로 서 있는 동안(`doubtCancelHold`, `cancelUnsent`) 라운드마다 기체 스냅숏을 보도록 했다(`Middleware.resendHeldCancel`). 스냅숏을 읽을 수 있고 그 단위의 태스크가 같은 리비전으로 끝나지 않은 채 보이면 키로 지은 핸들로 `CancelTask`를 보낸다(간격 2초). 운영자 보류는 그대로 두어 단위의 결과는 여전히 운영자가 판단하고 실행은 그 판단을 기다린다. 기체가 상태든 거부든 답하면 `CANCEL_RESENT`(기존 종류)를 남기고 `cancelUnsent`를 내린다. 그래서 운영자가 판단해 실행이 끝날 때 취소 응답에 `CANCEL_NOT_SENT`가 실리지 않는다. 거부면 그 코드를 거부 사유로 싣고 단위 메모에는 `held cancel re-sent by key`가 붙는다. 던지면 간격 뒤에 스냅숏부터 다시 보며, 스냅숏을 못 읽었거나 태스크가 끝났으면 보내지 않는다.

    계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 1)은 바뀌지 않았다. 새 트레이스 종류는 없으며(기존 `DOUBT_ATTACHED`, `CANCEL_RESENT`, `CANCEL_UNSENT`를 씀), 새 공개 면도 없다.

    새 시험 6건을 더해 전체는 2,228건에서 2,234건이 되었다. `InDoubtTest`에 5건(«스냅숏도 조회도 안 되는 미확정 단위의 취소는 키로 지은 핸들로 기체에 닿는다 — 재전송 없이 중단으로 끝난다», «스냅숏이 안 열린 미확정 단위의 키 취소를 하류가 거절해도 붙어 끝까지 따라간다 — 거절이 응답에 있다», «기체가 모르는 태스크의 키 취소는 쉬고 있다는 증거가 아니다 — 재전송 없이 운영자 보류다», «키로 보내는 취소는 간격 안에서 다시 보내지 않고 간격이 지나면 보낸다», «운영자 보류에 둔 취소는 스냅숏에 태스크가 보이면 키로 다시 보내고 보류는 그대로 둔다»)을 더했고, `WorkPermitTest`에 1건(«허가가 막고 스냅숏도 안 열려도 미확정 단위의 취소는 키로 지은 핸들로 기체에 나간다»)을 더했다. 기존 시험 2건(`InDoubtTest` «스냅숏도 조회도 안 되면 취소가 걸린 실행을 중단으로 닫지 않고 운영자 보류에 둔다», «운영자 보류에 둔 취소는 운영자가 판단하면 닿지 못했다는 사유를 싣고 끝난다»)은 키 취소도 닿지 않아야 전제가 서므로, 취소가 던지는 더블 `FlakyCancelRobotPort`로 감쌌다. 이름과 단언은 그대로 두었다. 시험 더블 `LinkDownRobotPort`는 끊긴 동안 취소도 닿지 않고 던진다. 새 `UnsentStartRobotPort`는 `StartTask`가 하위에 닿기 전에 던져 기체가 그 태스크를 모른다. 결함 주입 11건을 넣어 모두 해당 시험이 잡았다.

    한계 레지스터에서는 내부 오픈 항목 행 둘 «§15.219 · 막힌 미확정 취소»와 «§15.238 · 보류 뒤 취소 자동 재전송»을 닫았다(내부 43→41, 전체 98→96, 소비자 대기 26, 외부 29, 의도적 제외 13은 그대로). 항목 219, 238에 «(닫힘, §15.244)» 문단을 더했다. §15.219의 다른 행(플릿 경로, 리빌드 재부착, 허가 차단 내보내기)과 §15.238의 «같은 리비전 부착»은 열려 있다.

243. **스냅숏 미관측 시간으로 연결 끊김을 판정하고 영구 스트림 실패를 미확정으로 처리했다.**

    2026-10-17 결함 검토 17번을 2026-10-20에 한계 레지스터 내부 행 «§15.242 · 하위 연결 끊김»으로 기록했었다. `ClientRobotPort.snapshot`과 `replay`는 실패를 null로 바꾸고 `watch`는 `UNAVAILABLE`·`DEADLINE_EXCEEDED`에서 조용히 다시 구독해 미들웨어로 예외가 나가지 않았다. 연결 끊김 검사는 기체 뷰가 관측 가능할 때만 돌아, 재시작 없이 끊기면 `LINK_BROKEN`이 남지 않고 단위가 시한 없이 `RUNNING`에 남았다. 내부 행 «§15.228 · 영구 스트림 실패»에서는 `ClientRobotPort.watch`가 `WatchTask` 스트림이 `NOT_FOUND`·`OUT_OF_RANGE`로 닫히면 다시 붙지 않으나 그 실패를 미들웨어에 넘기지 않았다(`watch`의 반환이 갱신 목록뿐). 그래서 그 단위는 `RUNNING`에 머물고 `IN_DOUBT`로 가지 않았으며, `adapter-host` 재시작 뒤 재구독은 늘 `NOT_FOUND`였다. 2026-11-01 소유자는 스냅숏을 못 읽는 것을 시간 창 30초로 판정하고, 영구 스트림 실패는 단위를 미확정으로 옮기되 같은 참조의 `StartTask` 재전송 없이 해소하기로 결정했다.

    `Middleware.RobotView.lastObservedAt`은 미들웨어 시계 기준 마지막으로 스냅숏을 읽은 시각이며, 한 번도 못 읽었으면 null이다. 새 케이퍼빌리티 기본값으로 `LogicalCapability.linkLossWindow`를 30초로 두었다. 도는 로봇 단위가 있고 기체 뷰가 관측 불가이며 마지막으로 읽은 뒤 `linkLossWindow` 이상 지났으면 기존 연결 끊김과 같게 처리하도록 했다. 이에 따라 내용이 `snapshot unreadable since <시각> while <단위> is running — result unconfirmed`인 `LINK_BROKEN` 트레이스를 남기고, 실행 물리 상태를 `IN_DOUBT`로 두며, 상위에 작업 응답을 통지했다. 이때 단위는 `RUNNING`을 유지했다. 다시 읽히고 연결이 끊김 상태가 아니면 기존 `LINK_RESTORED` 경로로 이어서 추적하며 재실행하지 않았다. 호출 기한 한 번에도 판정과 복구가 오락가락하며 상위 알림이 되풀이되므로 한 번 못 읽은 것으로 판정하지 않았다. 또한 스냅숏을 늘 못 내는 기체(§15.241)는 유예가 언제나 지나 있어 모든 단위가 미확정이 되기 때문에 한 번도 못 읽은 기체는 판정하지 않았다. 기존 시험 `EventStreamTest` «기체를 못 보면 문도 자취도 없이 간다 — 그러나 못 봤다는 것은 남는다»(스냅숏이 늘 null인 포트로 45초 스킬을 끝까지 돌리고 트레이스가 비어 있음을 단언)가 이 규칙을 고정했다. 현장마다 바꿀 소비자가 아직 없어 이 유예는 현장 시간값(`SiteTimings`)에 넣지 않았다(ADR 9).

    `RobotPort.unfollowable(robotId, handle): String?`을 추가했다(기본 구현은 null). 갱신 스트림이 종료 없이 닫혔고 다시 열어도 같은 실패로 닫히면 그 까닭을 돌려주도록 했다. `ClientRobotPort.unfollowable`은 팔로워가 끝났고 오류 코드가 `NOT_FOUND`·`OUT_OF_RANGE`(상수 `PERMANENT`)이며 마지막 갱신이 종료 상태가 아니면 그 코드 이름(`"NOT_FOUND"` 등)을 반환하고, 기한 초과로 닫힌 스트림이나 종료를 본 태스크에는 null을 돌려주도록 했다. 미들웨어 `pumpRobotUnit`에서는 도는 단위의 마지막 갱신이 종료가 아닐 때(갱신이 없고 옛 리비전의 종료도 없을 때 포함) 포트가 따라갈 수 없다고 하면 `loseStream`이 포트에서 그 태스크를 놓고(`release`), 핸들을 버리며, 단위를 `IN_DOUBT`로 옮기고, 새 트레이스 `WATCH_CLOSED`를 남기고 상위에 한 번 통지하게 했다. 해소는 기존 `resolveDoubt` 순서를 따르되 같은 참조의 `StartTask` 재전송(①)을 건너뛰었다. 원 요청은 이미 하위에 닿았고 하위가 그것을 잊었으므로 재전송은 같은 일을 새로 내기 때문이었다. 스냅숏에 그 태스크가 보이면 붙고(`DOUBT_ATTACHED`), 아니면 설비를 관측하며, 유예(`inDoubtGrace`, 기본 60초)가 지나면 운영자 보류로 넘어가게 했다. 요청 시각부터 재면 긴 태스크는 잃은 순간 이미 유예 밖이므로, 유예는 스트림을 잃은 시각부터 쟀다. 운영자 보류 메모는 `IN_DOUBT: task stream closed; ...; downstream forgot the task, lookup not re-sent` 꼴이었다. 단위 본문 속성 `ExecutionUnit.streamLostAt`(internal)이 잃은 시각을 들게 했고, 새 시도를 내거나 스냅숏에 붙으면 걷히게 했으며 판단 기록에는 싣지 않았다.

    계약 proto, 계약 SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 6, 판단 기록(`JudgmentRecordWire` 1)은 바뀌지 않았다. 새 공개 면은 `RobotPort.unfollowable`(기본 구현 있음, 기존 구현체가 깨지지 않음), `LogicalCapability.linkLossWindow`(기본값 있음), `Middleware.RobotView.lastObservedAt`, 트레이스 종류 `WATCH_CLOSED`(`Middleware.WATCH_CLOSED`)였다.

    새 시험 5건을 더해 전체 시험은 2,223개에서 2,228개가 되었고 기존 시험은 고치지 않았다. `EventStreamTest`에 «스냅숏을 유예 넘게 못 읽으면 도는 단위는 미확정이고, 다시 읽히면 이어 간다 — 재실행 없음», «갱신 스트림이 영구히 닫히면 단위는 미확정이고 같은 참조로 다시 보내지 않는다», «영구히 닫힌 스트림의 태스크가 스냅숏에 보이면 붙어 이어 간다» 3건을 더했다. `StreamResumeTest`에 «기체가 모르는 태스크면 따라갈 수 없다고 답한다», «기한으로 닫힌 스트림은 따라갈 수 없다고 하지 않는다» 2건을 더했다. 시험 더블 둘을 `LossyPorts.kt`에 더했다: `LinkDownRobotPort`(끊긴 동안 스냅숏·재생은 null, 갱신은 끊기기 전까지 받은 것만), `ForgettingRobotPort`(태스크를 잊은 하위, 스냅숏에서 그 태스크를 숨길 수 있고 `StartTask` 횟수를 셈). 결함 주입 10건을 넣어 모두 해당 시험이 잡았다.

    한계 레지스터에서는 내부 오픈 항목 행 둘 «§15.242 · 하위 연결 끊김»과 «§15.228 · 영구 스트림 실패»를 닫았고 행 하나 «§15.243 · 끊긴 동안의 취소»를 열었다(내부 44→43, 전체 99→98, 소비자 대기 26, 외부 29, 의도적 제외 13은 그대로). 항목 228, 242에 «(닫힘, §15.243)» 문단을 더했다.

    (닫힘, §15.251) 이 항목이 연 행 «§15.243 · 끊긴 동안의 취소»는 연결이 끊긴 채 하위에 닿지 않은 취소가 미확정 유예(`inDoubtGrace`)를 지나면 단위를 운영자 보류(`OPERATOR_HOLD`)에 두어 기체를 내주지 않고 운영자 판단을 기다리게 하여 닫혔다.

242. **2026-10-17 결함 검토의 17, 18, 21번을 코드 수정 대신 한계 레지스터에 기록했다.**

    `ClientRobotPort.snapshot`과 `replay`는 모든 실패를 null로 바꾸고 `watch`는 `UNAVAILABLE`이나 `DEADLINE_EXCEEDED`에서 조용히 다시 구독하므로(`NOT_FOUND`나 `OUT_OF_RANGE`에서만 멈춤, §15.228), 어느 것도 미들웨어로 예외를 던지지 않아 `PUMP_FAILED`(§15.237)가 나지 않았다. 스냅숏이 null이면 `sync`는 기체 뷰를 관측 불가로 두고 바로 돌아가며, 연결 끊김 검사는 뷰가 관측 가능할 때만 돌아 `LINK_BROKEN`을 기록하고 실행의 물리 상태를 미확정(`IN_DOUBT`)으로 옮기되 단위는 설계상 `RUNNING`을 유지했다. 그래서 미들웨어와 어댑터 호스트 사이가 재시작 없이 끊기면 연결 끊김이 기록되지 않고 단위가 끝없이 `RUNNING`으로 남았다. 단위 시한은 없었으며(있는 시한은 신호 대기, 근거 시간 윈도우, `IN_DOUBT` 단위에만 걸리는 `inDoubtGrace`뿐이다), 진행률을 재는 기체만 기본 300초 뒤 정체 알림 `PROGRESS_STALLED`를 받을 뿐 상태는 바뀌지 않았고 진행률을 재지 않는 기체는 `PROGRESS_NOT_OBSERVABLE` 기록 1회만 받았다. 끊긴 동안 요청한 취소는 매 라운드 재전송되고(`CANCEL_UNSENT`) 실행은 한도 없이 `CANCELING`에 머물렀다. 어댑터 호스트 재시작(`NOT_FOUND`)은 §15.228, 스냅숏을 늘 읽지 못하는 기체는 §15.241이 다루고, 이번 행은 그 일반 경우이다.

    결함 수용(`Middleware.release`)은 차단 중인 결함의 키(결함 유형과 참조)를 `Execution.acknowledgedFaults`에 더하고, 결함 게이트는 이 집합에 든 결함을 건너뛰었다. 이 집합은 결함이 사라져도 비워지지 않고 늘기만 하며, 판단 기록에 저장된 뒤 `resume`에서 복원되었다. 그 결과 결함이 사라졌다가 같은 키로 다시 생겨도 뒤 단위, 다음 리비전, 리빌드 이후를 포함해 그 실행이 끝날 때까지 다시 차단되지 않았다. 용어집은 결함 수용을 같은 결함은 다시 차단하지 않고 새 결함만 차단하는 것으로 정의하며, 시험은 남아 있는 수용한 결함을 다시 차단하지 않음(`InspectAssetTest`)과 리빌드 뒤에도 수용이 유지됨(`JudgmentRecordTest`)을 고정했으나 결함이 사라졌다 다시 생기는 경로는 시험하지 않았다(§15.94에 결함이 사라지는 경로의 시험 부재를 적었다).

    작업 응답(`JobResponse`, 프로세스 안 타입이며 계약 proto에 없음)에는 시각 칸이 없고, `jobResponseId`는 인스턴스별 카운터(`resp-N`)이며 `version`은 주문 버전이라 시도를 가르지 못했다. 결과 내보내기 줄에도 시각이 없어 소비자는 응답을 시간순으로 정렬하거나 어떤 상태에 언제 도달했는지 알 수 없었다. 또한 `ack`는 아웃박스의 해당 응답을 바르게 확인 처리하지만, 더 새로운 응답이 확인 전이어도 실행의 표시 `Execution.upstreamAck`를 `ACKED`로 바꿨다(`jobOrderId`로 찾는다). `ForgetTest`가 이를 의도적으로 고정하고 있었고, `forget`은 응답별 확인을 보며 다른 유일한 판독처인 `settleExecution`은 `NOT_SENT`만 검사하므로 지금 이 거친 표시에 기대는 판단은 없었다.

    2026-10-20 소유자 결정(2026-10-17 결함 검토 17, 18, 21번)에 따라 세 건을 고치지 않고 한계 레지스터에 기록했다. `docs/limits.md`에 내부 행 둘(«§15.242 · 하위 연결 끊김», «§15.242 · 결함 수용의 범위»)과 소비자 대기 행 하나(«§15.242 · 작업 응답의 시각과 확인 단위»)를 더했다. 문서만 바꾼 작업으로 코드, 계약 proto, SemVer 0.9.0, 작업 응답 형식, 내보내기 버전은 그대로이며, 미결 한계는 96개에서 99개(내부 44개, 소비자 대기 26개, 외부 29개)가 되었다.

    (닫힘, §15.243) 이 항목이 남긴 행 가운데 «§15.242 · 하위 연결 끊김»은 §15.243에서 닫혔고 «§15.242 · 결함 수용의 범위»와 «§15.242 · 작업 응답의 시각과 확인 단위»는 열려 있으며, 끊긴 동안의 취소는 «§15.243 · 끊긴 동안의 취소»로 옮겼다.

    (닫힘, §15.248) 이 항목이 남긴 행 가운데 «§15.242 · 결함 수용의 범위»는 §15.248에서 닫혔고 «§15.242 · 작업 응답의 시각과 확인 단위»는 아직 열려 있었다.

    (닫힘, §15.262) 이 항목이 남긴 소비자 대기 행 «§15.242 · 작업 응답의 시각과 확인 단위»는 확인 단위를 고치고 시각을 의도적 제외로 옮겨 §15.262에서 닫혔다.

241. **결함을 관측하지 못하는 Orbit 기체의 스냅숏 부재 영향과 계약 한계를 정리하고 관련 문서를 보강했다.**

    Orbit 어댑터는 플릿 API에 기체 결함 표면이 없어 결함을 항상 관측 불가(`FaultObservation.NotObservable`)로 보고했으며, 이는 `OrbitAdapterTest` `취소도 일시정지도 없고, 결함과 잔여 파지는 못 본다` 시험으로 고정되어 있었다. 어댑터 호스트는 `faultsObservable = false`로 설정하지만 계약 스냅숏(`GetSnapshotResponse`, 6개 필드)에는 알 수 없음을 표현할 자리가 없어 `getSnapshot` 호출 시 `Status.UNAVAILABLE`을 던졌고, `ClientRobotPort.snapshot`은 이를 null로 축약했다. 이에 따라 미들웨어의 `sync`는 기체 뷰를 관측 불가로 표시하고 재생를 건너뛰었으며, 연결 끊김 검사는 뷰가 관측 가능할 때만 수행되어 `LINK_BROKEN`이 절대 나타나지 않았다. 결함 게이트는 실행을 차단하지 않고 시작 전 결함 미관측 사실을 1회 기록하도록 의도되었으나, 리빌드(`resume`)는 스냅숏이 null이면 `RESUME_SNAPSHOT_UNREADABLE`로 거부하므로 Orbit 기체에 바인딩된 모든 실행은 리빌드에서 거부되었다. picasso-ops는 이를 `DEFERRED`로 기록하고 매 pump마다 끝없이 재시도했으며, 리빌드 후 취소 부착(§15.231) 역시 스냅숏을 읽지 못하면 기한 없이 대기하는 상태였다. 실행 중 플릿 링크가 끊어지더라도 `poll`은 마지막 상태만 반환하므로 진행 정체 알림(`PROGRESS_STALLED`, 기본 300초)을 통해서만 문제 상황이 드러났다.

    연결 끊김 감지가 누락되는 원인의 일부는 일반적인 구조적 문제였다. 어댑터 호스트는 스냅숏에서 `ONLINE`/`OFFLINE`만 방출하고 `CONNECTION_BROKEN`은 아직 방출하지 않았으며, 사우스바운드 연결 끊김은 어댑터의 결함이나 거부 보고에 의존하는데 Orbit에는 결함 표면이 존재하지 않았다. 저장소 내 벤더 근거로 공개된 Orbit Web API 5.0.0 사양과 공식 Python 클라이언트(323개 심볼)를 전수 조사한 결과, `Robot` 스키마에는 `hostname`, `nickname`, `robotIndex`, `username`만 정의되어 있어 기체 결함이나 연결 심볼이 전혀 식별되지 않았다. 다만 실제 배포된 인스턴스가 추가 엔드포인트를 노출할 가능성이 있으므로 결함 및 연결 표면의 부재는 확정(NO)이 아닌 미상(UNKNOWN)으로 판단했다(조사 범위: 공개 OpenAPI 5.0.0 및 공식 Python 클라이언트). 현재 picasso-ops는 에뮬레이터를 통해서만 기체를 구동하므로 실제 배포 환경에서 이 문제가 발현되지 않았으며, Orbit의 실제 배포 여부 또한 미상(UNKNOWN)이었다.

    소유자는 2026-10-19 결정(항목 11)을 통해 이를 소비자 대기 한계 행으로 기록하고 당장은 계약 버전을 올리지 않기로 합의했다. 연기된 해결책인 옵션 A는 차기 계약 0.10.0의 `GetSnapshotResponse`에 결함 관측 열거형을 추가하되, 기존 발신자의 의미를 보존하기 위해 `UNSPECIFIED`를 관측됨으로 해석하도록 정의했다(bool을 사용하면 기존 발신자의 의미가 반전된다). 이를 통해 어댑터 호스트가 `Status.UNAVAILABLE` 예외를 던지는 대신 결함 목록을 비우고 `NOT_OBSERVABLE` 상태로 태스크, 연결, 수치 정보를 전송하게 하여 리빌드, 취소 부착, 연결 검사가 태스크와 연결 정보를 바탕으로 수행되도록 조율했다. 반면 어댑터가 "관측됨, 결함 없음"으로 보고하는 옵션 C는 결함이 없는 기체로 허위 보고하게 되므로 기각했다.

    이번 변경은 코드, 계약 proto, SemVer 0.9.0, 작업 응답 형식, 내보내기 버전 변경이 일절 없는 순수 문서 작업이었다. `docs/limits.md`에 결함 미관측 기체의 스냅숏을 다루는 소비자 대기 한계 행(§15.241)과 Orbit 기체 연결·결함 표면을 다루는 외부 한계 행(§15.241)을 신설하고, `docs/vendors/orbit.md`의 미결 항목을 갱신하여 관련 제약을 명확히 기록했다.

    (닫힘, §15.263) 이 항목이 남긴 소비자 대기 행 «§15.241 · 결함 못 보는 기체의 스냅숏»은 2026-11-04에 다시 정해 옵션 A대로 계약 0.10.0을 지어 §15.263에서 닫혔고, 외부 행 «§15.241 · Orbit 기체 연결·결함 표면»은 열려 있다.

240. **스냅숏 왕복 기반의 발신자 시계 차 감지와 트레이스 및 메모 보고 체계를 구축했다.**

    PR #120(환경 전제 K와 그 허용 폭, 한계 행 §15.239 현장 시계 동기화)의 후속 작업이다. 2026-10-19 소유자 결정(12번 항목)에 따라 시계 차는 보정 없이 감지만 수행하기로 확정했다. 이에 따라 미들웨어는 시계 차를 보정하지 않는다는 계약 §4의 원칙을 그대로 유지했다.

    측정은 `sync` 과정의 스냅숏 왕복에서 `GetSnapshot` 호출 전후로 미들웨어 시계를 읽어 수행한다. 발신자(어댑터 호스트 또는 에뮬레이터)가 기록한 응답 헤더 `occurred_at`은 계약 변경 없이 내부의 `RobotSnapshot.occurredAt`(`ClientRobotPort.snapshot`이 채움)으로 전달된다. 발신자의 시계 차 구간은 `[occurred - received, occurred - sent]`에 위치하며, 음수는 발신자가 늦음을 의미한다. 임계는 그 실행의 근거 시간 윈도우에서 좁은 쪽의 절반(`min(before, after) / 2`)으로 정하며, 기본값에서는 7.5초, 현장 설정으로 뒤 폭이 5초이면 2.5초가 된다. 환경 전제 K의 허용 폭에서 미들웨어 코드가 알 수 없는 지연 L(셀 폴링 주기, pump 주기, 전달 지연의 합)을 차감해야 하므로 절반을 취했다. 측정 구간 전체가 임계를 엄격히 벗어날 때만 감지하며, 임계에 걸치는 긴 왕복은 보고하지 않는다. 동일한 임계가 양방향 모두에 적용된다.

    해당 기체에서 실행 중인 모든 실행의 트레이스에 «robot=hum-02 발신자 시계가 미들웨어보다 20초 늦다 (임계 7.5초)» 로 시작하는 `CLOCK_SKEW` 1행을 남긴다. 중복 보고는 방향과 임계의 2의 거듭제곱 크기 등급(1~2배, 2~4배 등)별로 억제되고, 시계 차가 다시 임계 안으로 들어오면 표식이 지워져 다시 벗어날 때 새로 보고된다. 스냅숏 예외나 누락, 헤더 시간 부재 시에는 상태를 변경하지 않는다. 신호가 존재하지만 근거 시간 윈도우를 벗어나 `UNVERIFIED`로 닫힌 실행 단위에는 마지막 측정 시계 차가 임계 밖일 때 시계 차 의심 메모를 부여하지만, 신호가 아예 없는 단위에는 메모를 남기지 않는다. 시계 차 보정이 없으므로 판정은 결코 바뀌지 않으며, 서버 시간이 응답에 없는 셀 시계는 측정할 수 없어 대상에서 제외했다. 계약 proto, SemVer 0.9.0, 작업 응답 형식, `JudgmentRecordWire` 1, 내보내기 버전 6은 변경되지 않았고, 에뮬레이터 시계를 1초 이내로 유지하는 picasso-ops의 `HostClock` 덕분에 picasso-ops 코드 수정도 불필요하다. 한계 행 §15.239(현장 시계 동기화)는 미확인(UNKNOWN) 상태를 유지하되, 위반이 조용한 `UNVERIFIED` 대신 `CLOCK_SKEW`와 `UNVERIFIED` 메모로 드러나게 했다.

    `ClockSkewTest` 15개를 새로 추가하여 전체 시험 수는 2,208개에서 2,223개가 되었다. 특성 시험인 `발신자 시계가 뒤 폭보다 늦으면 첫 확인에서 UNVERIFIED 다`와 4개의 가드 시험(허용 폭 이내, 시계 일치 시 메모 없음, 양방향 걸침 구간)은 main에서 통과했고, 나머지 신규 시험은 main에서 실패하던 것들이다. 결함 주입이 초기에 살아남았던 `신호가 아예 없어 닫힌 UNVERIFIED 의 메모에는 시계 차 의심을 싣지 않는다` 시험도 보강했다. 시험 대역으로는 `ClockShiftRobotPort`와 `SlowSnapshotRobotPort`를 활용했다. 12건의 결함 주입을 모두 잡아냈으며, 빌드 대상 모듈의 1,617개 시험이 모두 통과(실패 0건)하고 번들, 골든 세트, 내보내기 픽스처 검사도 변경 없이 통과했다.

239. **분산 시스템 시계 불일치에 따른 동기화 환경 전제와 허용 폭을 정의하고 문서화했다.**

    시스템에서는 세 가지 시계가 절대 시각으로 비교된다. 완료 시각 `t_r`은 로봇 장치 내부 시각이 아닌 발신자 프로세스(어댑터 호스트 또는 에뮬레이터)가 상태 전이를 기록할 때 자체 시계로 타임스탬프를 찍은 계약 헤더 `state_as_of`이고, 셀 관측 시각 `t_p`는 설비가 제공한 `SlotSignal.observedAt`이며, 기한은 미들웨어 시계 `now()`를 기준으로 판정한다. 작업 허가는 허가 원천의 `PermitScope.observedAt` + `validFor`와 미들웨어 시계를 비교한다. 검증 대기 시 `t_p`가 `[t_r - before, t_r + after]` 구간에 포함되어야 유효한데, 발신자 시계가 뒤 폭(`after`) 이상 늦으면 첫 확인 시점에 이미 기한이 만료되어 즉시 `UNVERIFIED`가 되고, 앞 폭(`before`) 이상 앞서면 신호가 근거 시간 윈도우 시작점보다 앞서 도착하여 대기 후 `UNVERIFIED`로 귀결된다. 아울러 셀 제어기 시계가 미들웨어보다 앞 폭 이상 늦으면 미확정 잠정 완료 요건(`t_p >= requestedAt - before`)이 차단되며, 허가 원천 시계가 미들웨어보다 늦으면 수신 값이 만료(`EXPIRED`)되고 앞서면 유효기간이 늘어나는 개방 편향이 발생하여 더 위험하다. picasso-ops는 허가 어댑터가 호스트의 읽기 시각(`HostClock`)을 기록하므로 작업 허가 시계 차 문제는 발생하지 않는다.

    기존 계약 §4에는 헤더 타임스탬프가 각 발신자의 로컬 시계 기준이며 분산 환경의 시계 오차를 미들웨어가 일괄 보정하지 않는다는 원칙만 명시되어 있었고, 동기화 환경 전제나 구체적인 허용 폭은 어떤 문서에도 정의되어 있지 않았다. 반면 picasso-ops 구현에서는 시계 불일치 시 셀 신호가 근거 시간 윈도우를 벗어난다는 사실을 `HostClock` 주석에 명시해 두고 있었으며, 에뮬레이터 시계를 실시간에 맞춰 전진시킴으로써 시계 차를 1초 이내로 유지하도록 이미 관리하고 있었다.

    이에 코드 분석을 바탕으로 동기화 허용 폭(L = 셀 폴링 주기 + `pump` 주기 + 전달 지연)을 도출하여 문서에 반영했다. 발신자 시계가 미들웨어보다 늦은 경우의 시계 차는 `after - L` 미만이어야 하고, 앞선 경우의 시계 차는 `before - L` 미만이어야 한다. 셀 제어기가 미들웨어보다 늦은 경우의 시계 차는 `before` 미만이어야 하며, 자체 시각을 제공하는 허가 원천의 시계 차는 `validFor` 미만이어야 하되 앞선 시계는 유효기간을 연장하므로 더욱 엄격하게 억제해야 한다. 기본 설정 시 가장 좁은 허용 폭은 뒤 폭 기준인 15초이며 현장 타이밍을 단축하면 허용 폭도 축소된다. 이번 변경은 순수 문서 작업으로 코드, 계약 proto, SemVer 0.9.0, 작업 응답 형식, 내보내기 버전의 변경은 일절 포함하지 않았다.

    2026-10-19 소유자 결정(항목 12)에 따라 계약 §4의 무보정 원칙을 유지한 채 환경 전제와 허용 폭을 우선 명문화하고, 시계 불일치 탐지(트레이스와 메모, 보정 없음)는 별도의 후속 변경으로 진행하기로 합의했다. 실제 현장의 어댑터 호스트, 셀 제어기, PLC 장치들이 단일 시각원을 공유하는지 여부와 실제 시계 차 수치는 현장 실측 전까지 미확인(UNKNOWN) 상태이므로 한계 문서의 외부 제약 항목으로 등록했다.

238. **미확정 로봇 단위의 조회 예산을 시간 기반으로 전환하고 스냅숏 부착과 미전송 취소 보류 처리를 도입했다.**

    전에는 미확정(`IN_DOUBT`) 로봇 단위에 대해 동일한 `StartTask`를 재발송하는 조회를 라운드마다 계산하여 최대 `lookupRetries`회(기본 3회)까지만 시도했다. picasso-ops는 250ms마다 라운드를 실행하므로, 1초 미만의 짧은 단절만으로도 재시도 예산이 소진되어 유예 후 기체가 실제로 작업 중임에도 단위가 운영자 보류로 넘어가 추적도 취소도 불가능했다. 또한 미확정 중에 취소가 요청되고 조회가 응답하지 않으면 기체가 여전히 동작 중일 수 있음에도 실행이 `motionStopped=true`인 `ABORTED`로 성급히 종결되었다.

    소유자는 2026-10-19 결정에서 횟수 기반 예산을 시간 기반 예산과 스냅숏 부착으로 전환하고, 미전송 취소 처리와 최소 조회 간격을 같은 변경에 포함하기로 했다. 이에 따라 `lookupRetries`를 제거하고 기존 현장 타이밍 `inDoubtGrace`(10~600초, 변경 없음) 범위 안에서 재조회를 수행하되, 미들웨어 시계로 측정한 마지막 조회 시점 기준 `LOOKUP_INTERVAL`(2초)보다 자주 묻지 않도록 제한했다. 2초 간격은 250ms 라운드 대비 호출을 8배 줄여 응답 없는 엔드포인트이 호스트 잠금을 점유하는 빈도를 낮추며, 최소 유예 10초 안에서도 5회 조회가 가능하여 약 1초의 단절을 다음 조회에서 곧바로 해소한다. 작업 허가로 차단된 라운드는 조회로 치지 않으며, 허가 차단으로 유예 동안 한 번도 묻지 못했더라도 조회 없이 운영자로 넘기지 않고 유예 만료 뒤 1회 조회를 보장한다. 리빌드 후에는 간격 기억을 유지하지 않고 첫 조회를 즉각 발송하며 동일 참조 재전송은 멱등이다.

    로봇 경로에서는 조회를 시도하기 전에 스냅숏 부착을 먼저 수행한다. 기체 뷰 관측이 가능하고 스냅숏에 단위의 태스크 ID가 보이며 스냅숏 리비전이 단위 리비전과 일치하면, `StartTask` 호출 없이 태스크 ID와 리비전으로 핸들을 즉시 구성하여 부착하고 `RUNNING` 상태로 전이한다. 부착은 읽기 전용이므로 발행 게이트인 작업 허가에 질의하지 않으며, 보류 중이던 취소는 생성된 핸들을 통해 하위 기체로 즉시 발송된다. 이 전이에는 새로운 트레이스 종류인 `DOUBT_ATTACHED`가 기록된다. 리비전 전달을 위해 `RobotSnapshot`에 `revisions`를 추가하고 `ClientRobotPort.snapshot`이 기존 `TaskSnapshot.revision` 필드를 채우도록 구현하여 계약 proto 변경 없이 정합성을 유지했다. 스냅숏을 읽지 못하거나 태스크가 없거나 리비전이 다르면 부착하지 않고 시간 예산 조회 경로를 적용한다.

    미확정 종료 시 설비 관측 뒤에도 핸들과 전송 수단이 모두 없는 상태에서 취소가 요청된 경우, 실행을 `ABORTED`로 닫지 않고 운영자 보류 상태를 유지하도록 변경했다. 이때 미전송 취소 표식(`cancelUnsent`, PR #118과 동일 표식)을 설정하고 `CANCEL_UNSENT`를 트레이스하며 실행은 보류를 유지한다. 운영자가 판단을 내려 해소(`resolve`)하면 보류가 해제되고 정상 취소 경로를 밟아 `ABORTED`로 종료되며, 취소 보고에는 `refusal` = `CANCEL_NOT_SENT` 사유가 실린다. 플릿 경로도 동일하게 동작한다. 계약 proto, SemVer 0.9.0, 작업 응답 형식, `JudgmentRecordWire` 1, 내보내기 버전 6, `SiteTimings` 범위는 바뀌지 않았다. picasso-ops 측 코드 수정은 불필요하며, 실행이 취소 요청 상태에서 운영자 보류로 표시되더라도 운영자의 `resolve`를 통해 정상 종결된다.

    시험 검증에서는 11개 신규 시험을 추가하여 전체 시험이 2,197개에서 2,208개가 되었다. 가드 시험 2개를 제외한 새 시험은 main 에서 실패하거나 결함 주입으로 탐지됨을 확인했고, 9건의 결함 주입을 시도하여 모두 검출했다. 빌드 대상 모듈의 1,602개 시험이 실패 없이 통과했다. 기존의 미확정 운영자 전이 시험은 횟수 단언을 유예 경과 후 검증으로 대체하여 이름을 갱신했으며, 기존 3개 시험은 스냅숏을 읽지 못하는 로봇을 사용하도록 변경했다. 한계 목록에서는 §15.219 막힌 미확정 취소 행을 스냅숏 미관측 조건으로 축소 갱신하고, §15.238 보류 뒤 취소 자동 재전송과 §15.238 같은 리비전 부착의 내부 한계 2행을 새로 추가했다.

    (닫힘, §15.244) 이 항목이 연 행 «§15.238 · 보류 뒤 취소 자동 재전송»은 스냅숏에 태스크가 보이면 운영자 보류를 유지한 채 키로 다시 보내도록 하여 §15.244에서 닫혔으며, «§15.238 · 같은 리비전 부착»은 열려 있다.

    (좁힘, §15.280) 2026-11-07에 기체가 단위보다 높은 리비전을 들면 운영자 보류로 보내고, 리비전을 싣지 않는 포트에서도 그 태스크를 한 리비전으로만 보낸 단위는 붙게 했다. 리비전을 싣지 않는 포트가 갱신을 보낸 뒤나 재기동 뒤에 붙지 못하는 것은 남아 행 «§15.238 · 같은 리비전 부착»은 열려 있다.

237. **포트 예외 격리와 취소 재전송을 도입해 pump 중단과 취소 유실을 방지했다.**

    전에는 `Middleware.pump()`가 아무런 격리 없이 각 실행을 순차적으로 수행했다. 특정 실행 진행 중 포트(`CellSignals`, `RobotPort`, `AmrFleetPort`)에서 `RuntimeException`이 발생하면 `pump()` 자체가 예외를 던졌고, 해당 포트가 예외를 던지는 동안에는 뒤이어 생성된 실행들이 해당 라운드에서 전혀 전진하지 못했다. 또한 `Middleware.cancel`은 취소 전송 시 발생하는 예외를 그대로 밖으로 흘려보냈으며, 취소 전송이 실패했음에도 취소가 다시 전송되지 않아 마치 기체에 정상 도달한 것처럼 보였다. 즉, 취소 보고는 `motionStopped=true`, `refusal=null`로 나갔다. 미확정 단위가 조회(`resolveDoubt`)를 통해 해결된 직후 전송된 취소에서도 동일한 문제가 발생했다.

    2026-10-19 소유자 결정에 따라 포트 예외를 맞닥뜨린 실행은 트레이스 한 줄만 남기고 다음 라운드에서 다시 살펴보며, 운영자 보류로 전환하지 않기로 했다. 이에 따라 기체별 `sync`와 각 실행의 `pumpRound`를 각각 감쌌다. `RuntimeException`이 발생하면 해당 실행(동기화의 경우 해당 기체의 모든 실행)은 발생 위치와 원인별로 `PUMP_FAILED` 트레이스를 1회 기록하고, 상태를 그대로 유지한 채 다음 라운드에서 처음부터 다시 시작한다. 해당 위치가 한 번이라도 성공하면 표식이 초기화되므로 이후 다시 실패하면 트레이스가 새로 기록된다. 동기화 중 `robots.snapshot`에서 예외가 발생하면 기체 뷰를 관측 불가(`observable = false`)로 설정해 낡은 값이 관측값으로 오인되지 않도록 했다. 예외 포착 이후에도 `noteAttention`과 인시던트 봉인은 정상적으로 실행된다.

    취소 전송은 단일 함수 `sendCancel`을 거치도록 일원화했다. 전송 중 예외가 발생하면 실행에 미전송 표식(`cancelUnsent`)을 남기고 원인별로 `CANCEL_UNSENT`를 1회 기록하며, 실행 단위가 `RUNNING` 상태인 동안 이후 라운드 시작 시점마다 이를 재전송한다. 취소가 하위에 도달하면 `CANCEL_RESENT`를 기록한다. 하위로부터의 응답(상태 응답이든 거부이든)은 수신된 것으로 간주하여 다시 전송하지 않는다. `Middleware.cancel`은 더 이상 예외를 던지지 않으며 반환값 `true`는 하위 전달이 아닌 접수를 의미한다. 취소가 끝내 도달하지 못한 채 단위가 종료되면 취소 보고는 `refusal` = `CANCEL_NOT_SENT`를 담으며, 단위 자체는 여전히 `stoppedAfter`로 종료된다. 플릿 취소 경로(`fleet.cancel`)도 동일한 함수를 거치며, 검증 대기 중인 단위처럼 이미 종료된 작업에는 재전송하지 않는다.

    계약 proto, SemVer 0.9.0, 작업 응답 형식, `JudgmentRecordWire` 1, 내보내기 버전 6은 바뀌지 않았다(취소 보고는 내보내거나 기록되지 않는다). 격리할 실행이 없는 `siteTimings.current()`는 감싸지 않았으며, 모든 `RuntimeException`을 포착하므로 불변식 위반(`!!`, `error()`) 역시 트레이스 한 줄로 처리된다. 반면 예외를 던지지 않고 멈추는 포트의 막힘 문제는 여전히 남아 있다. picasso는 자체 스레드가 없어 소비자의 잠금 아래에서 실행되며, `PicassoClient`의 기본 기한(10초)이 `snapshot`, `start`, `cancel`에 적용되고 `sync`가 기체를 순차 방문하므로 무응답 엔드포인트 하나가 기체당 최소 10초씩 라운드를 연장시키며 호출이 누적된다. picasso-ops에서는 호스트 잠금 아래에서 돌아 HTTP 엔드포인트도 함께 대기하게 되며(pump 주기 250ms), 새로 추가된 재전송으로 인해 무응답 엔드포인트에 대한 라운드당 기한이 1회 더 늘어날 수 있다. 적정 예산은 현장 실측 전까지 UNKNOWN이며, 이는 신규 한계 행인 `§15.237 · 사우스바운드 동기 호출 막힘`으로 남겨 두었다.

    검증을 위해 `PumpIsolationTest`에 시험은 12개가 새로 추가되어 2,185개에서 2,197개가 되었다. main 브랜치 기준 11건이 실패했고 1건은 하위의 취소 거부 응답 시 재전송을 방지하는 보호 시험이었다. 시험 더블은 `LossyPorts.kt`에 구현했다. 14건의 결함 주입 시험은 전건 감지되었으며, 기체 뷰를 관측 불가로 표시하지 않는 결함 주입 1건이 기존에 기체를 읽지 않아 통과했던 문제를 확인하고 5번 시험을 추가해 해결했다. 빌드 대상 모듈의 1,591건 시험 전체를 통과(실패 0건)했으며, picasso-ops는 서브모듈 올림만 반영하면 된다.

    (좁힘, §15.267) 잠금 밖에서 미리 읽은 값을 받는 포트 경로와 `PrefetchingRobotPort`를 구현하여 스냅숏과 재생을 선읽기로 옮겼다. 다만 명령 동기 호출, picasso-ops 기본 결선 미적용, 현장 실측 전 기한 예산과 `maxAge`의 UNKNOWN 상태가 남아 행을 닫지 않고 문제 범위를 좁혔다.

    (닫힘, §15.279) 2026-11-07에 출발 요청을 만들 때 묻는 로봇의 `capabilities`를 `PrefetchingRobotPort`의 선읽기로 옮기고 셀 신호 읽기(`CellSignals`)에 막지 않는다는 계약을 적어, 소비자 잠금 아래 동기로 남은 하위 호출이 없어져 닫혔다. 남은 기한 예산과 `maxAge`의 현장 실측은 외부 행 §15.279로 옮겼다.

236. **종결한 순회의 건너뛴 대상을 운영자가 재작업할 수 있도록 `resolve` 를 확장했다.**

    전에는 PR #114(§15.234)에서 `ExecutionUnit.after`가 도입되어 `InspectAsset`의 점검 단위 `<대상>`이 `<대상>.travel`에 의존했다. 이동이 FAILED로 끝나면 `skipAfterPredecessor`가 태스크 없이 `PREDECESSOR_NOT_DONE`으로 점검을 FAILED 처리하고 순회를 계속했다. `resolve`는 `OPERATOR_HOLD` 단위만 받았으므로 건너뛴 점검과 시도된 이동 실패 모두 `NotHeld`를 반환했다. `revise`는 시도된 실패를 래치로 보존하고 점검만 다시 계획했으므로 점검이 다시 건너뛰어졌다. 이로 인해 건너뛴 대상은 새 작업 지시로만 다시 수행할 수 있었다. 소유자 결정(2026-10-18, §15.234)에 따라 리비전이 아닌 운영자 재작업을 확장하기로 했다.

    이번 변경으로 `Middleware.resolve(executionId, unitId, REWORK, approver)`는 건너뛴 점검 단위(FAILED, `PREDECESSOR_NOT_DONE`, 태스크 식별자 없음, 선행 이동은 태스크 식별자를 지닌 FAILED)도 수락한다. 보류 경로와 동일한 블록(`reworkAsNewAttempt`: 시도 +1, `PENDING`, 태스크 식별자 초기화, `operator ordered rework` 메모)을 적용하여 해당 단위와 선행 이동을 새 시도로 되돌리며, 두 단위는 `<대상>.travel@r1` 및 `<대상>@r1`로 다시 나간다. 이 재작업은 다섯 가지 조건을 모두 요구한다. 승인자가 사람이어야 하고(에이전트는 종전처럼 `Refused`), 실행이 메모리에 남아 있어야 하며(이빅션 후는 `NotHeld`), 단위가 건너뛴 형상이어야 하고(이동 식별자나 자체 시도 후 실패한 점검은 `NotHeld`), 실행이 `PARTIAL` 또는 `FAILED`로 종결하여 대기나 실행 중인 단위가 없어야 하며(미종결 시 `Refused` 사유 «순회가 종결한 뒤 한 대상씩 재작업한다 — 아직 낼 단위나 도는 단위가 있다», 취소된 `ABORTED` 실행은 `Refused` 사유 «취소한 실행의 대상은 재작업하지 않는다 — 새 작업 지시로 낸다»), 동일 로봇을 점유하는(`occupiesRobot`) 다른 활성 실행이 없어야 한다(있을 경우 `Refused` 사유 «같은 기체에 다른 활성 실행이 있다 — 그 실행이 끝난 뒤 재작업한다: <executionId>»). 건너뛴 단위의 `CONFIRM_DONE`은 `Refused` 사유 «점검을 하지 않은 단위는 확인할 것이 없다 — 재작업만 받는다: <unitId>»로 거부된다. 소유자 결정(2026-10-18)에 따라 재작업 범위는 건너뛴 한 쌍으로 한정하며, 모든 이동이 실패하여 로봇을 해제한 `FAILED` 실행도 위 조건 아래 수락한다.

    두 단위 모두 판단 기록에 `RecordedDecision(REWORK, approver, at, 증가 전 시도)`를 남긴다. 건너뛴 점검은 인시던트를 열지 않았으므로 이동 식별자로 `noteResolution`을 호출하여 선행 이동의 인시던트에 판단을 부착한다. 실행은 `RUNNING` 상태로 되돌아가며 `notify`를 통해 단일 `RUNNING` 작업 응답을 발행한다. 보류 경로의 `noteAttention`은 `OPERATOR_HOLD`와 `IN_DOUBT`만 비교하므로 `PARTIAL`이나 `FAILED`에서 `RUNNING`으로 바뀔 때 아무것도 보내지 못하기 때문이다. 리비전과 달리 상위가 요청하지 않았으므로 변경을 알려야 하며, 다시 요청해도 추가 작업 응답은 나가지 않는다. 동일 작업 지시, 동일 실행 식별자, 동일 작업 지시 버전을 유지한다. 이 조건들을 둔 이유는 계획 순서대로 다음 단위를 선택하므로 순회 중에 재작업하면 다른 대상의 점검이 엉뚱한 위치에서 발행될 수 있기 때문이며, 종결 후 한 번에 한 쌍씩만 처리하여 이를 방지한다. `FAILED` 실행은 로봇을 해제했으므로 다른 실행이 시작되었을 수 있고, `liveExecutionCount`는 종결된 `PARTIAL` 실행을 놓치므로 `occupiesRobot`으로 점유를 확인한다. 이동 재발행(`navigate_to`)은 파지물이나 슬롯 점유가 없어 물리적 구속이 없으며, 실패 원인 해소 여부는 현장의 사람만이 판단할 수 있다.

    바뀌지 않았다. 계약 proto, SemVer 0.9.0, 작업 응답 형식 1, `JudgmentRecordWire` 1, 내보내기 버전 6, 인시던트 조회 버전 5(ADR 47에 따라 결정자는 미내보내기), `ResolveOutcome`의 세 결과(넷째 결과는 picasso-ops의 `when` 분기를 깨뜨림), `revise`의 래치 규칙은 모두 그대로 유지했다. 새로워진 점은 `Refused`가 ADR 47의 권한 부족뿐 아니라 사유를 동반한 현재 접수 거부를 뜻하게 된 것이다. 내보내는 인시던트에 이동의 FAILED 인시던트가 결정을 얻음에 따라 `unresolved: false`와 `resolution`이 함께 나타나는 새 값 조합이 추가되었으며, narrator와 «보강» 소비자에게 버전 변경 없이 안내하기로 했다.

    시험은 `InspectAssetTest` 에 12개가 새로 추가되어 2,173개에서 2,185개가 되었다. 그중 10개는 main 에서 실패했고 2개는 가드(에이전트 요청 차단, 건너뛴 모양이 아닌 단위)다. 기존 시험인 `리비전이 그 점검을 다시 계획해도 이동이 시도된 실패로 남아 있으면 다시 내지 않는다`도 정상 통과했다. 결함 주입 13곳과 `notify` 대신 `noteAttention` 을 쓰는 주입 1곳은 모두 잡혔고, 전체 빌드는 빌드한 모듈의 시험 1,579개가 실패 0 으로 통과했다.

    남은 한계로 자체 실패 점검이나 점유가 남은 `pick_place` 등 시도된 실패 일반으로의 재작업 확장과 여러 대상 일괄 재작업은 소비자를 대기한다. `FAILED` 실행은 담는 쪽(picasso-ops)의 보존 기간(기본 5분) 안에서만 재작업할 수 있고 이빅션 후에는 `NotHeld`를 반환한다. 보류 경로와 이번 경로 모두 단위의 시도별 관측 필드(`everHeld`, `rechecks`, `progressStalled`, `holdMismatch`, `requestedAt`)를 비우지 않으며, `fault`와 `downstreamDoneAt`만 다음 시도 종료 시 다시 쓰고 나머지는 이전 시도 값이 이월된다. `DefinedCapability`가 향후 `after`를 사용할 경우(한계 «§15.209 · 정의 둘») 계획 순서가 시간 순서라는 리빌드 가정의 재측정이 필요하나 `InspectAsset`은 설비 대기가 없어 무해하다(최고 근거 등급 E0). picasso-ops에는 아직 건너뛴 대상 버튼이 없고, `MissionHost.resolve`와 `HoldResolutions.recheck`가 선행 이동 식별자로 인시던트를 조회해야 하며, 새로운 `Refused` 사유를 표시해야 한다. 이번 작업으로 ADR 53을 추가하고 ADR 47을 연결했으며, §15.234 한계 행을 닫고 한계 레지스터에 4개 행(내부 1개, 소비자 대기 3개)을 추가했다.

    (닫힘, §15.246) 이 항목이 연 행 «§15.236 · 재작업 관측 칸»은 새 시도를 낼 때 단위의 시도별 관측 칸을 초기화하도록 `reworkAsNewAttempt`를 개편하여 §15.246에서 닫혔으며, «§15.236 · 재작업 일반화»는 열려 있다.

    (의도적 제외로 옮김, §15.265) «§15.236 · 재작업 일반화», «§15.236 · 여러 대상 재작업», «§15.236 · 보존 기간 안 재작업» 행은 picasso 와 picasso-ops 어디에도 그 값을 쓰는 면이 없어 의도적 제외의 새 표 «수요 없음» 으로 옮겼다.

235. **목적지 점유로 인한 충돌을 방지하기 위해 목적지 관측 선언(`destinationObservation`)과 게이트 검사를 추가했다.**

    전에는 `VERIFICATION_MISMATCH` 같은 배치 실패가 발생해 목적지 슬롯에 다른 부품이 남더라도 실행이 종료되면 예약이 해제되어 동일 슬롯으로의 다음 작업 지시가 그대로 수용되었다. 진입 게이트(`occupancyViolation`)는 목적지에 대해 종결하지 않은 실행의 예약만 확인했을 뿐 설비에 목적지가 비어 있는지 묻지 않았다. 보고서 12.1과 `EvidenceWindow`는 배치 전의 목적지 '재하' 신호를 과거 신호(잡음)로 취급했고, 약 23개의 시험 픽스처가 제출 전에 목적지를 재하 상태로 프로그래밍하고 있어 모든 단위에 대해 목적지 관측을 일괄 활성화할 수는 없었다.

    이에 따라 2026-10-18 소유자 결정(§15.218의 출발 관측 선언을 모델로 함)으로 임무 정의의 단위 노드에 선택적 키 `destinationObservation`(`REQUIRED`·`OPTIONAL`)을 추가하고 `ExecutionUnit.destinationObservation`(기본값 `OPTIONAL`)으로 전달하도록 했다. 게이트의 새로운 `destinationViolation`은 `occupancyViolation` 내부에서 예약 확인 뒤이자 출발 확인 전에 호출되며, `expectedIdentity`와 `destination`을 모두 갖춘 `REQUIRED` 로봇 단위만 판정한다. 설비가 재하로 응답하면(동일 부품을 포함해 식별자와 무관하게) «목적지 X 가 비어 있지 않다 … (목적지 점유, 이 단위는 목적지 관측을 요구한다, 보내지 않았다)»로 거부하고, 설비가 무응답이면 «목적지 X 를 확인하지 못했다 … (목적지 미관측, …)»로 거부하며, 비어 있으면 통과한다. `OPTIONAL`은 설비에 전혀 묻지 않는 기존 동작을 유지한다. 배치 전 재하 신호는 현장이 선언하지 않는 한 과거 신호로 취급되므로 출발 측과 달리 `OPTIONAL`은 목적지에 대해 아무것도 판정하지 않으며, `REQUIRED`는 배치 전 재하 신호가 물리적 점유임을 현장이 선언하는 것이다. 검사 순서는 예약 → 목적지 → 출발이므로 가득 찬 목적지로 인해 대체 출발 자리를 계산하거나 결품 기록을 남기지 않는다. 거부는 텍스트 사유일 뿐이며 §15.183 및 출발 미관측의 경우와 마찬가지로 조치 탐색 레지스터에 기록되지 않는다. 파서는 `destinationObservation`(`REQUIRED`·`OPTIONAL`, 다른 값은 문제)을 읽고, 검증기는 엔지니어(`ENGINEER`) 담당의 `DESTINATION_OBSERVATION_UNDECLARED`(단위 노드가 `destination`과 `expectedIdentity`를 모두 갖추었으나 선언 없음)와 `DESTINATION_OBSERVATION_UNUSED`(선언되었으나 둘 다 갖추지는 않음) 두 거부 종류를 추가해 검사를 6개에서 7개로, 거부 종류를 11개에서 13개로 확장했다. `DefinedCapability`는 필요한 곳에서 선언을 요구하고 이를 단위로 전달한다.

    근거 윈도우(`EvidenceWindow`)는 바뀌지 않았다. 게이트는 디스패치 전에 슬롯이 비어 있는지 확인하고, 윈도우는 완료 후에 어떤 신호를 근거로 인정할지 확인하므로 서로 무관하다. 계약 proto, SemVer 0.9.0, 임무 정의 `schemaVersion` 1(§15.218과 마찬가지로 선택적 키 추가), 내보내기 버전 6(`RemedyOutcome` 불변), 판단 기록 버전도 모두 바뀌지 않았다. 단위 계획 필드는 판단 기록에 포함되지 않으며, 리빌드는 정의로부터 다시 계획을 수립하므로 선언이 다시 복원된다.

    시험은 18개가 새로 추가되어 2,155개에서 2,173개가 되었다. `DestinationObservationTest` 10개, `OccupancyTest` 2개, `MissionValidatorTest` 3개, `MissionDefinitionParserTest` 1개, `DefinedCapabilityEquivalenceTest` 1개, `InMemoryMissionCatalogTest` 1개가 포함되며 `SeamPlacementTest`에 1개 항목이 반영되었다. `MissionFixtures` 단위 노드는 `OPTIONAL`을 선언하도록 변경되었고 기존 `MissionValidatorTest` 입력 2개가 조정되었다(단언 불변). 신규 시험는 모두 main에서 실패했으며, 20개의 결함 주입이 모두 검출되었다. 빌드된 모듈 내 1,567개 시험이 0개 실패로 전체 빌드를 통과했다.

    실제 PLC 슬롯 센서가 배치 전 점유를 물리적 사실로 보고하는지(래칭이나 지연으로 과거 값을 남기지 않는지)는 알 수 없으며(UNKNOWN) 현장 PLC 시운전이 필요하다. 벤더의 `pick_place`가 채워진 슬롯으로의 배치를 거부하는지도 알 수 없다(Digit JSON API와 Spot arm 1차 출처 미조사). 목적지 점유나 미관측에 대한 구조화된 필드(`sourceUnobserved` 같은)나 레지스터 항목은 없으며 사람이 목적지가 비어 있음을 확인하는 방법도 제공되지 않아 소비자의 요구를 기다린다(ADR 9). 리비전, 재작업, 리빌드는 게이트를 통과하지 않으므로 목적지를 확인하지 않는다(출발 측과 동일). picasso-ops가 서브모듈을 올릴 때 저장된 카탈로그 호환성 채우기에서 `destinationObservation`을 채워야 하고(그렇지 않으면 호스트가 시작 시 정지함), 세 가지 임무 템플릿의 배치 노드에 선언을 추가해야 하며, 편집기와 검증 화면에 키 및 신규 두 종류의 라벨을 반영해야 한다. 모의 실행기의 이상적 셀은 목적지를 재하 상태로 응답하므로 모의 실행에서 REQUIRED 정의가 거부될 수 있고, 현장 에뮬레이터는 성공적인 `pick_place` 후 슬롯을 채우며 재시작해야 비워진다. §15.218과 마찬가지로 ADR 50이 갱신되었고 limits.md에 외부 요인(§15.235)과 소비자 요구 대기(§15.235)의 두 한계 행이 추가되었다.

    (의도적 제외로 옮김, §15.265) «§15.235 · 목적지 구조 칸» 행은 picasso 와 picasso-ops 어디에도 그 값을 쓰는 면이 없어 의도적 제외의 새 표 «수요 없음» 으로 옮겼다.

234. **InspectAsset 대상 순회 중 이동 실패 시 뒤따르는 점검 단위를 PREDECESSOR_NOT_DONE으로 건너뛰도록 실행 단위 선행 의존 필드를 추가했다.**

    전에는 대상 순회 케이퍼빌리티인 `InspectAsset`(각 대상을 `<대상>.travel`(`navigate_to`) 후 `<대상>`(`inspect`)으로 계획)에서, 엔진이 이전 단위의 판정과 무관하게 차단 결함과 작업 허가 여부만 확인한 채 다음 `PENDING` 단위를 시작했다. 이에 따라 특정 대상으로의 이동이 차단 결함(`can_accept_new_task=false`) 없이 `FAILED`로 끝나더라도 해당 대상의 점검 태스크가 그대로 전송되었고, 잘못된 위치에서 수행된 점검이 `DONE`으로 돌아올 수 있었다. ADR 50은 단위들이 독립적이라는 전제(`PrepareSequencedRack`의 슬롯들) 아래 엔진이 `FAILED` 단위 뒤에도 다음 단위를 전송하도록 의도하여 작성되었으나, `InspectAsset`을 추가할 당시(§15.93) 두 종속 단위를 지닌 대상에 대해 이 전제를 재측정하지 않았고 §15.94 또한 위치 상실 결함 사례만 차단했다. 이번 변경은 §15.93(«공통 엔진 무수정»)과 §15.94가 결함 없는 이동 실패에 대해 열어 두었던 문제를 닫았다.

    `ExecutionUnit`에 선행 단위 식별자를 담는 내부 필드 `after`(`robotBinding`과 같은 본문 속성)를 추가했다. `InspectAsset.plan`은 각 점검 단위에 `after = "<대상>.travel"`을 설정하고, 이동 단위, `NO_LOCATION` 단위, `PrepareSequencedRack`, `DeliverContainer`, `DefinedCapability`는 이를 null로 두어 기존 동작을 유지했다. `pumpRound`에서는 결함 및 작업 허가 확인 직후이자 `startUnit` 바로 앞에서 `skipAfterPredecessor`가 선행 단위의 종료 상태가 `FAILED`, `UNVERIFIED`, `ABORTED`인지 확인한다. 선행 단위가 완료되지 않았다면 태스크 없이(`taskId=""`) 실패 분류 `PREDECESSOR_NOT_DONE`으로 단위를 `FAILED` 처리하고 메모와 트레이스 한 줄을 남겼다. 인시던트는 열지 않으며(계획 실패와 동일한 형태), `pumpRound`는 같은 순서로 다음 단위를 살폈다. 이 검사를 결함 확인 뒤에 둔 이유는 의도적인 설계였다. 이동이 차단 결함과 함께 실패했을 때는 §15.94의 결함 보류가 먼저 성립해야 하며, 점검 건너뜀은 운영자가 결함을 수용(release)한 뒤에야 수행되어야 하기 때문이다. 검사를 앞서 진행하면 결함 보류와 `blockedBy`가 사라져 `ExportFixtureTest`가 실패했다.

    소유자 결정(2026-10-18): 운영 관점에서는 새 작업 지시보다 동일 작업 지시 내에서 다시 수행하는 편이 낫다고 보았다. 선택된 방식은 순회 종료 후 건너뛴 대상(이동과 점검 쌍)을 재작업할 수 있도록 운영자 재작업(`resolve` REWORK, 동일 실행 내 새 시도 `@rN`)을 확장하는 것이었다. 현재 `resolve`는 운영자 보류(`OPERATOR_HOLD`) 상태의 단위만 수용하므로 이는 후속 설계(ADR 필요)로 남겨 두었으며, 그때까지 대상을 다시 실행하려면 상위에서 새 작업 지시를 보내야 했다. 건너뛴 대상은 리비전으로 다시 실행하지 않기로 했다. 리비전이 건너뛴 점검을 다시 계획하더라도 이미 시도되어 실패한 이동은 실패 상태로 래치되어 있으므로 점검 단위는 다시 건너뛰어진다. 시도 후 실패한 단위를 다시 내보내면 실패 후 부품이 손에 남아 있을 수 있는 `pick_place` 같은 동작에도 영향을 주므로 종료 래치 규칙은 변경하지 않았다.

    바뀌지 않았다: 계약 proto, SemVer 0.9.0, 내보내기 버전 6, 인계 번들, `JudgmentRecordWire` 버전 1, 작업 응답 형식 버전 1은 모두 유지되었다. 건너뜀 처리는 기존 필드인 `incompleteUnits` (`PUMP-01: PREDECESSOR_NOT_DONE`) 및 판단 기록의 빈 `taskId`와 판정의 실패 분류를 통해 드러났다.

    테스트는 `InspectAssetTest` 9부터 16까지 7건을 추가하여, 점검 단위만 선행자를 지니는지, 결함 없는 이동 실패 시 해당 점검을 건너뛰고 다음 대상의 이동을 내보내는지(결과 `PARTIAL`), 결함 있는 이동 실패 시 먼저 보류되고 수용 후 건너뛰는지, 작업 응답·인시던트·트레이스·판단 기록 내용, 전 대상 이동 실패 시 3개 이동 태스크만 발행되고 `FAILED`가 되는지, 리비전 재계획 시 다시 건너뛰는지, 기록 없이 리빌드해도 동일 규칙을 따르는지를 검증했다. 이들은 main에서 모두 실패했던 테스트였으며, 12건의 결함 주입을 모두 잡아냈다. 전체 테스트 수는 2,148건에서 2,155건으로 증가했고, `./gradlew build -x :harness:test -x :registry:test` 실행 결과 대상 모듈 1,549건 테스트를 0건 실패로 통과했다. 남은 미지수는 Spot `Sequence` 노드 의미론이나 ISA-95 `SegmentDependency`가 어휘적 근거를 제공하는지(내부 필드이므로 ADR 36에 저촉되지 않음), 실제 로봇이 경로 차단으로 `navigate_to` 실패 시 차단 결함을 보고하는지, `inspect`가 자체 위치를 검증하는지다. 소비자인 picasso-ops의 `FaultReleaseHostTest`는 수용된 이동 결함 후 점검이 실행될 것을 가정하고 있으므로 ops에서 서브모듈을 갱신할 때 테스트 수정이 필요하다.

233. **사용자 및 상위 시스템에 노출되는 코드 문자열 리터럴의 구 용어를 표준 용어로 교체했다.**

    PR #112에서 진행한 문서 한정 용어 정리의 후속 작업으로, 운영자와 상위 시스템에 노출되는 거부 사유, 예외 메시지, 오류 힌트, 상수 텍스트, 게이트 리포트 문구에 남아 있던 구 용어를 표준 용어로 교체했다. `*/src/main/**/*.kt`의 14개 파일 29개 라인에 걸쳐 `종착`을 종료로, `비종착`을 비종료로, `정착`을 종결로, `관문`을 게이트로, `놓는다`를 이빅션한다로, `다시 짓지`를 리빌드하지로, `다시 돌거나`를 재실행하거나로, `상류`를 상위로 변경하고 문맥에 맞게 조사를 조정했다. 아울러 `FORGOTTEN_JOB_ORDER`(이미 끝나 이빅션한 작업 지시라 다시 받지 않는다), `RESUME_SNAPSHOT_UNREADABLE`, `RESUME_RECORD_MISMATCH` 상수의 텍스트를 갱신했으며, 상수를 비교하는 기존 코드의 동작은 동일하게 유지했다.

    식별자, 코드 주석, KDoc, 테스트 함수명, 단언 실패 메시지, proto 및 SQL 주석은 변경하지 않고 기존 명칭을 유지했다. 이번 결정에 들지 않은 `도는 태스크`와 `도는 단위`, 그리고 레지스트리 HTTP 오류 등에서 20여 곳 이상 쓰이는 `개정판`과 `거절` 같은 §8 대응표의 옛 명사는 별도 후속 작업으로 다루기로 하여 이번 변경 범위에서 제외했다. 인계 번들(`handoff/narrator`)에는 변경된 문자열이 포함되지 않아 내보내기 버전과 골든셋는 영향을 받지 않았으며, 작업 응답으로 전달되는 거부 사유 텍스트만 실제 변경 사항이 반영되도록 했다.

    변경된 텍스트를 검증하는 ReassignmentTest, LedgerIngestEndToEndTest, GateLedgerWiringTest 단언을 함께 수정하여 전체 2,148개 테스트가 정상 유지되도록 했다. 소비자인 picasso-ops는 이들 상수를 코드상 참조로 비교하므로 로직 변경은 없으나, UI 라벨인 FORGOTTEN_JOB_ORDER_TEXT와 일부 테스트 픽스처 및 문서가 구 텍스트를 인용하고 있어 서브모듈 갱신 시점에 맞추어 수정하도록 정리했다. 아울러 변경 이력 추적을 위해 docs/limits.md의 변경 이력 번호를 232에서 233으로 올렸다.

232. **억지로 한글화했던 용어 14개를 외래어와 일상어로 바꾸고 문서와 용어집을 정비했다.**

    2026-10-18 소유자 결정으로 억지로 한글화한 용어 14개를 외래어나 일상어로 바꿨다. 이름만 바꿨고 뜻은 그대로이다. 대응은 다시 짓기 → 리빌드, 관문 → 게이트(배정 게이트, 발행 게이트, 결함 게이트), 자취 → 트레이스, 묘비 → 툼스톤, 승인 창구 → 승인 호스트, 기체 묶음 → 기체 바인딩, 설명된 불리언 → `ExplainedBoolean`, 막음 → 차단, 도는 실행 → 진행 중인 실행, 다시 돎 → 재실행, 정착 → 종결, 결함 감수 → 결함 수용, 놓기 → 이빅션, 접수 불명 → 미확정이다. 용어집 §8 대응표에 이미 있던 옛 이름 가운데 최근 현행 문서에 다시 들어간 사슬 → 연쇄, 종착 → 종료, 대장 → 레지스터도 바로잡았다. 용어집(`docs/glossary.md`)은 §8 대응표에 14행을 더하고 비고 칸에 원어를 적었으며 머리글에 문장을 덧붙였다. 표제어 5개의 이름을 승인 호스트, 미확정, 단위의 기체 바인딩, `ExplainedBoolean`, 발행 게이트로 바꿨다. 새 표제어 4개인 리빌드(`Middleware.resume`, `rebuildFrom`), 종결(`PhysicalState.isSettled`), 이빅션(`Middleware.forget`), 결함 수용(`Middleware.release`)을 더했다.

    바꾼 범위는 `docs/`의 현행 문서(ADR 포함), `README.md`, 모듈 README, 용어집이다. 20개 파일의 230곳이다. 많은 것은 게이트 86곳, 차단 22곳, 리빌드 19곳, 트레이스 18곳, 승인 호스트 16곳, 수용 15곳, 이빅션 13곳이다. 승인 호스트는 문서 밖 세 곳도 같이 바꿨다. 시험 «승인 창구를 부르는 이름이 하나이고 용어집에 있다»가 용어집의 `ApprovalHost` 표제어 이름을 그 물건을 부르는 자리(`CLAUDE.md`, `picasso/build.gradle.kts`의 Gradle 태스크 설명, `ScenarioHost` 기동 배너, `docs/limits.md`)마다 요구하기 때문이다. 바꾼 것은 Gradle 태스크 설명과 기동 배너의 문자열 둘, `CLAUDE.md`의 명령 설명 한 줄이고, 그 파일들의 주석과 KDoc은 그대로이다.

    바꾸지 않은 범위는 코드 식별자, 코드 주석과 KDoc, 시험 이름과 문서가 인용한 시험 이름(백틱이나 «» 안), 스탬프 줄의 «열림», `docs/superpowers/`(설계 명세와 이 변경 이력의 지난 항목), `handoff/`, 다이어그램 그림 안의 글자이다. 뜻이 다른 자리도 그대로 두었다. ADR 9의 «컴포넌트로 정착», ADR 9와 ADR 30의 «감수»(비용을 받아들인다는 뜻), 적재물을 «놓다» 같은 물리 동작, «못 막음» 같은 동사 «막음»이 이에 해당한다.

    외부 규약과 버전 체계는 바뀌지 않았다. 계약 proto, SemVer 0.9.0, 내보내기 버전 6, `JudgmentRecordWire` 버전 1, 작업 응답 형식 버전 1, 승인 호스트 규약 버전 4를 유지한다. 시험은 더하지 않았고 전체 시험 수는 2,148개 그대로이다. `./gradlew :gate:test :picasso:test --continue`로 게이트 시험 277건과 `GroundTruthTest` 10건이 무실패로 통과했다. 한계 레지스터(`docs/limits.md`)는 행 본문의 옛 이름만 새 이름으로 바꿨고 행 수는 그대로이다. 변경 이력 번호를 231에서 232로 올렸다. 바꾼 문서는 `python tools/stamp.py`로 스탬프를 다시 찍었다.

231. **다시 지은 실행에 첫 pump 전 취소가 걸릴 때 스냅숏의 기체 태스크에 붙어 취소를 전달하도록 바로잡았다.**

    전에는 `resume`으로 다시 짓기를 마친 실행의 `execution.active`와 `handle`이 비어 있어 첫 pump에서 `pumpRound`가 같은 태스크 식별자의 다음 단위에 대해 `startUnit`을 호출할 때 비로소 하위에 다시 붙었다. 이 때문에 `resume` 직후 첫 pump 전에 `cancel`이 불리면 `cancelRequested=true`와 `CANCELING`이 설정되었으나 `handle`이 없어 하위에 취소를 보내지 못했고, 첫 `pumpRound`가 `active == null`을 확인하고 `nothing_to_clean`과 함께 실행을 `ABORTED`로 정착시키면서 기체는 태스크를 계속 수행하고 기체도 반납되는 문제가 있었다. 담는 쪽(picasso-ops 실행 호스트)은 이를 피하고자 첫 pump가 하위에 다시 붙은 뒤에야 복원된 취소를 다시 걸었으나, 그사이 `CANCELLED`로 끝난 하위 태스크는 §15.230에 따라 요청 없는 취소로 해석되어 거짓 운영자 보류와 인시던트 및 작업 응답을 유발했다. 이제는 `rebuildFrom`이 스냅숏에서 확인한 로봇 단위 태스크와 당시 시도를 새 필드 `Execution.unattached`에 기록한다(재작업을 지나 시도가 달라진 기록은 버리고 `startUnit`이 해당 단위를 발행하면 제거한다). 그리고 `pumpRound`에서 `active == null`이고 취소가 요청되었을 때 `nothing_to_clean`으로 정착하기에 앞서 `attachForCancel`을 수행한다. 이때 포트의 별도 조회 호출 없이 스냅숏의 태스크 식별자로부터 `TaskHandle(taskId, unit.revision, robot)`을 직접 조립한다(`StartTask` 재전송 형태의 미확정 조회는 사용하지 않는다). 계약상 아직 종료되지 않은 태스크(사람 대기 포함)에는 `robots.cancel`을 보내며, 거절은 `cancelRefusal`에 기록하고 예외가 발생하면 미부착 상태로 두어 다음 라운드에서 스냅숏을 다시 읽는다. 태스크가 이미 종료되었으면 취소를 보내지 않고 부착만 수행하여 기존 판정 규칙에 따라 단위 결과를 확정한다(취소 종료는 요청된 취소 `ABORTED`, 성공은 `DONE` 후 stoppedAfter, 실패는 `FAILED`). 스냅숏을 읽지 못하면 `CANCEL_UNATTACHED_WAIT` 자취를 사유당 한 번 남기며 실행은 `CANCELING` 상태로 대기하고, 스냅숏에 태스크가 없으면 기존처럼 `nothing_to_clean`으로 정착한다. 부착이 완료되면 단위는 `RUNNING`이 되고 `active`와 `handle`이 설정되며 실행은 `CANCELING`, 자취는 `CANCEL_ATTACHED`가 기록된다. 이후 하위가 `CANCELLED`로 끝날 때 `cancelRequested`가 참이므로 정상 중단 경로를 밟아 거짓 운영자 보류가 발생하지 않는다.

    `attachForCancel`은 `WorkPermitPort`의 `issuable`을 묻지 않는다(§15.219). 스냅숏 조회와 취소 전송 및 관측만 수행하며, ADR 52에 따라 작업 허가는 새 단위 발행을 막을 뿐 취소나 관측은 차단하지 않으므로 작업 허가가 `UNKNOWN`이어도 취소는 정상 발행되어 허가 부재 중에 기체가 작업을 계속하는 결함을 방지했다. 부수적으로 `startUnit`에서 `taskIdOf`와 `requestedAtOf`를 추출해 중복을 정리했으며 동작상 변경은 없다. `resume`과 `cancel`의 KDoc에도 다시 짓기 직후 취소를 호출할 수 있음을 명시했다.

    외부 규약과 버전 체계는 바뀌지 않았다. 계약 proto, SemVer 0.9.0, 내보내기 버전 6, `JudgmentRecordWire` 버전 1, 작업 응답 형식 버전 1, 승인 창구 규약 버전 4를 유지한다.

    시험은 새 시험 클래스 `ResumeCancelTest`에 4개 시험을 추가했다(main 970867d에서 모두 실패). 추가된 시험은 «다시 지은 실행에 곧바로 취소를 걸면 첫 pump 가 정착하지 않고 기체의 태스크를 취소한 뒤 중단으로 정착한다», «작업 허가가 막아도 다시 지은 실행의 취소는 기체에 나간다», «기체의 태스크가 이미 취소로 끝났으면 새 태스크를 내지 않고 그 종료로 단위를 판정한다», «스냅숏을 못 읽으면 정착하지 않고 기다렸다가 다시 읽히면 취소를 보낸다»이다. 결함 주입 9건을 수행하여 모두 검출했다. 시험 개수 갱신 후 `./gradlew build -x :harness:test -x :registry:test` 빌드를 마쳤으며 전체 시험 수는 2,144개에서 2,148개로 늘었고 게이트 277건과 GroundTruthTest 10건 모두 무실패로 통과했다.

    한계 레지스터(`docs/limits.md`)의 «§15.230 · 재기동 뒤 하위 취소» 항목은 닫지 않고 범위를 좁혔다. 담는 쪽이 기록된 자체 취소를 첫 pump 전에 다시 적용할 때 그사이 끝난 하위 취소가 요청된 취소로 정착하는 경로는 해소되었으나, 담는 쪽이 취소를 다시 걸지 않거나 취소 기록이 없는 경우(판단 기록이 취소 요청을 보존하지 않음), 첫 pump가 이미 부착해 `CANCELLED`를 확인한 뒤 취소가 걸리는 경우, 플릿 운반 단위(로봇 단위만 해소되었으며 플릿 운반은 `dispatch` 재전송 외 기존 태스크 조회 경로 부재), 항목에 명시된 `settleLate` 및 개정 응답 갈래의 미검증 분기는 열려 있다. 또한 스냅숏을 읽지 못하는 동안 실행이 기한 없이 `CANCELING`에서 대기하고(`CANCELING`은 `noteAttention` 필드가 아니므로 작업 응답 미발송) 이는 기체를 확인하지 못하는 실행 중 단위와 같다. 한계 레지스터 항목 수(전체 84건, 내부 38건)는 변동이 없으며 이력 번호를 230에서 231로 갱신하고 `docs/orchestration.md` §8과 `CLAUDE.md`, `docs/verification.md`의 시험 수를 갱신하고 도장을 다시 찍었다.

230. **하위의 요청 없는 취소를 운영자 보류와 인시던트로 세우고, 막음·보류·미확정이 풀릴 때 작업 응답을 내며, 스냅숏을 못 읽어도 기존 결함 막음을 유지하도록 했다.**

    전에는 이 계층이 취소를 요청하지 않았는데도 하위가 스스로 태스크 취소(`TASK_STATE_CANCELLED` 또는 `TASK_STATE_CANCELLED_RECOVERY_FAILED`)를 보고하면, `Middleware` 가 단위를 `ABORTED` 로 표시하고 `pumpRound` 가 다음 `PENDING` 단위를 시작했다. 그래서 현장 작업자가 로봇의 태블릿에서 로봇을 멈추어도 다음 단위가 나갔고 인시던트도 열리지 않았다. 플릿 자체의 취소도 실행을 `ABORTED` 로 끝냈다. 재기동 뒤에도 마찬가지여서, 이 계층이 취소를 요청한 상태에서 로봇이 `CANCELLING` 인 동안 프로세스가 죽으면 `resume` 뒤 다시 지은 실행에는 취소 요청 표시가 없어(판단 기록이 이를 보존하지 않음) 하위의 취소를 요청 없는 취소로 보았다. 지금은 요청 없는 하위 취소가 들어오면 단위를 운영자 보류(`OPERATOR_HOLD`)로 두고 인시던트(`holdUnrequestedCancel`, `pumpRobotUnit`, `settleLate`, `pumpFleetUnit` 에서 `cancelRequested` 가 거짓일 때만 사용)를 연다. 새 분류를 임의로 만들지 않고 하위가 결함을 붙였으면 그 정준 분류를 쓰며, 그렇지 않으면 `failureClass` 는 null 로 두고 메모에 하위의 보고 내용을 남긴다. 기존 보류 통지가 이를 알리며, 기존 `resolve`(REWORK, CONFIRM_DONE)나 `cancel` 로 닫힌다. 재기동 뒤에도 같은 규칙이 적용되어 실행이 다음 단위로 나아가지 않고 운영자 보류로 멈춘다. `OPERATOR_HOLD` 는 판단 기록에 이미 존재하는 판정 상태이므로 `JudgmentRecordWire` 는 버전 1을 유지한다.

    전에는 막음, 보류 또는 미확정 상태가 풀려도 작업 응답을 내지 않았고, 오직 결함 감수(`release`, §15.229)와 `LINK_RESTORED` 만 작업 응답을 보냈다. 막는 결함이 스스로 사라지거나, 운영자 판단(`resolve` CONFIRM_DONE 또는 REWORK)이 내려지거나, 미확정 단위가 조회로 풀리거나(`resolveDoubt`), 개정 갱신 응답(`sendRevisionUpdate`)으로 미확정 단위가 풀리거나 새로 생겨도 작업 응답이 나가지 않았다. 그래서 상위의 마지막 작업 응답에는 `OPERATOR_HOLD`, `operatorRequired=true`, `blockedBy` 가 그대로 남았다. 또한 마지막으로 통지한 막음 키(`notedBlock`)와 미확정(`notedDoubt`)이 재설정되지 않아 같은 결함이 다시 서도 다시 알리지 않았다. 지금은 `Execution.lastNotified` 가 마지막으로 보낸 작업 응답을 들고 있으며, `noteAttention` 이 상위가 사람이 확인해야 하는 상태로 읽는 세 필드(실행 상태 IN_DOUBT 또는 OPERATOR_HOLD, `blockedBy` 의 분류들, `inDoubtUnits`)를 마지막 작업 응답과 비교하여 달라졌을 때만 알린다. 이는 라운드 끝(`pump(execution)`), `resolve` 끝, `revise` 끝에서 실행된다. 같은 내용은 다시 보내지 않으므로 `release`, `LINK_*` 또는 기존 통지와 겹치지 않는다. 결함 관문이 풀리면 `blockedBy` 를 비우고 `notedBlock` 을 재설정하며 상태를 `RUNNING` 으로 돌린다. 착수 직후 미확정으로 바뀌는 단위가 한 라운드에 응답을 두 번 내지 않도록 통지는 라운드 끝에 보낸다. `notedDoubt` 는 세 `resolveDoubt` 갈래와 개정 응답 갈래에서 재설정되며, 이 갈래는 실행 상태도 RUNNING(취소가 계류 중이면 CANCELING)으로 되돌린다. `resolve` 는 다른 단위가 여전히 보류 중이면 실행을 `OPERATOR_HOLD` 에 머물게 한다(전에는 RUNNING 으로 갔다가 다음 라운드에 보류로 돌아오며 응답을 두 번 냈다).

    전에는 스냅숏을 한 번 읽지 못했을 때(`!view.observable`) «관측 실패로 현장을 세우지 않는다»는 규칙에 따라 `blockingFaults` 가 막음을 돌려주지 않아, 결함으로 이미 막혀 있던 실행이 다음 단위를 시작했다. 지금은 실행에 이미 `blockedBy` 가 채워져 있다면 관측할 수 없는 동안에도 그 막음을 유지한다. 관측할 수 없는 스냅숏에서 막음을 새로 만들지는 않으므로, 규칙은 이제 «새로 막지 않는다»로 해석된다.

    계약 proto, 계약 SemVer 0.9.0, 내보내기 버전 6, 인계 번들(`handoff/`), `JudgmentRecordWire` 버전 1, 승인 창구 규약 버전 4, 작업 응답 형식(`ResultExport`) 버전 1은 바뀌지 않았다.

    시험는 9개가 새로 추가되어 총 2,135개에서 2,144개로 늘어났다. 새 시험 클래스인 `PumpSafetyTest` 에 7개(«하위가 스스로 취소하면 다음 단위를 내지 않고 운영자 보류로 세우고 인시던트를 연다», «미들웨어가 취소를 건 뒤 다시 뜨면 하위의 취소를 요청 없는 취소로 읽어 다음 단위를 내지 않는다», «운영자가 재작업을 고르면 보류가 걷힌 작업 응답이 곧바로 하나 나가고 다음 라운드가 같은 응답을 또 내지 않는다», «결함이 저절로 사라지면 막음이 걷힌 작업 응답이 하나 나가고 같은 결함이 다시 서면 다시 알린다», «미확정이 조회로 풀리면 작업 응답이 곧바로 나간다», «개정 갱신의 답을 못 받으면 미확정을 알리고 조회로 풀리면 다시 알린다», «스냅숏을 한 번 못 읽어도 이미 막던 결함은 계속 막는다»), `DeliverContainerTest` 에 1개(«플릿이 스스로 취소하면 실행을 중단으로 끝내지 않고 운영자 보류로 세운다»), `JudgmentRecordTest` 에 1개(«보류 둘로 다시 지은 실행에서 하나를 판단하면 실행은 보류 그대로이고 작업 응답이 더 나가지 않는다»)가 추가되었다. 기존 시험는 변경되지 않았으며, 9개 새 시험 모두 수정 전 main 에서 실패했다. 결함 주입 11건 중 10건을 탐지했고 1건은 등가 변이였다(해제 시 `notedBlock` 을 재설정하는 항목으로, 라운드 끝 비교 로직도 재통지를 포괄함).

    한계 레지스터에서 내부 행은 37건에서 38건으로, 전체 오픈 과제는 83건에서 84건으로 늘었고, 소비자 대기 20건, 외부 26건, 의도적 제외 13건과 오픈 항목 수 144건은 그대로 유지되었다. 새 내부 행으로 재기동 뒤 하위 취소의 원인(현장 작업자의 취소인지, 재기동 전 이 계층이 요청한 취소인지)을 가르지 못해 둘 다 운영자 보류가 되고 사람이 다시 판단해야 하는 제약을 기록했다(이를 가르려면 판단 기록에 취소 요청을 담는 새 버전이 필요함. `settleLate` 의 지연 취소와 개정 응답 갈래의 상태 복구 분기도 미시험로 남음). `docs/limits.md` 의 이력 번호를 229에서 230으로 올리고, `docs/orchestration.md` §8 에 단락을 추가했으며, `README.md` 수치, `CLAUDE.md` 와 `docs/verification.md` 의 시험 수를 갱신하고 스탬프를 다시 찍었다.

    (닫힘, §15.255) 이 항목이 연 행 «§15.230 · 재기동 뒤 하위 취소»는 판단 기록이 건 취소를 보존하고(§15.253) 플릿 운반을 찾아 붙으며(§15.254) 붙기 전에 건 취소의 대기에 미확정 유예 기한을 두고 남은 분기를 시험으로 다루어 §15.255에서 닫혔다. 판단 기록 없이 또는 버전 1 기록으로 리빌드한 실행은 되살릴 건 취소가 없어 여전히 하위 취소를 요청 없는 취소로 읽어 운영자 보류에 두었다.

229. **순위 목록 전부가 관문에서 떨어진 `adopt` 가 미배정 후보마다 기록하게 했고, 사람의 문 `approveRemedy` 가 거부 종류를 싣게 했으며, `release` 가 작업 응답을 내게 했다.**

    전에는 `adopt`(순위 목록으로 배정)에서 후보가 전부 관문(`admits`)에서 떨어져 `Unassigned` 가 되면, 조치 탐색 결과(조치 열, 찾음·없음·가림)가 조치 탐색 레지스터에도 제안 장부에도 남지 않았다. 적는 자리를 `RemedyDesk.record` 하나로 두어 관문의 순수성을 지킨 대가였으며(§15.161, §15.164), 출발 결품의 응답만 `Unassigned.sourceMissing` 으로 남았다. picasso-ops 실행 호스트(`MissionHost`)가 작업 지시를 `assign`(곧 `adopt`)으로만 내므로, 미배정이 아무것도 적지 않으면 그 호스트는 승인할 제안을 볼 수 없었다. 지금은 순위 목록 전부가 관문에서 떨어졌을 때만, 후보마다 `desk.record(robotId, order, rejection, sourceMissing)` 를 부른다. `submit` 이 한 기체에서 막혔을 때와 같은 함수이고 열쇠도 같은 (기체, 작업 지시)다. 순위 목록 차례대로 적고, 같은 후보가 두 번 있으면 한 번 적는다. 하나라도 관문을 통과하면 채택된 작업 지시의 제안은 승인해도 조치 열이 못 나가기 때문에(ADR 46) 앞에서 떨어진 후보를 포함해 적지 않는다. `submit` 이 관문 앞에서 거부했을 작업 지시(근거 등급 초과 `evidenceRefusal`, 단위 id 중복 `duplicateUnitIdRefusal`)는 그 제안을 승인하면 같은 거부를 받기 때문에 적지 않는다. 놓은 작업 지시의 거부(`forgottenRefusal`)와 이미 실행이 선 작업 지시의 개정(`revise`) 분기는 그대로 맨 앞이다. 이에 따라 조치 열을 찾은 후보마다 제안이 서고, 조치 탐색 레지스터에 `FOUND`·`NONE`·`WITHHELD`·`SOURCE_MISSING` 줄이 선다. 출발 미관측(`sourceUnobserved`)은 `submit` 과 같이 줄이 없다. 가림 차례(`withholdEvery`)는 제안 하나마다 세므로 후보 N 개가 조치 열을 찾으면 N 칸이 간다. `Unassigned` 의 모양(`refusals`·`sourceMissing`·`sourceUnobserved`)과 배정 결과는 그대로다. 관문 `admits` 는 여전히 순수하다. 승인 수(`repeatedRemedies`)와 소모(ADR 46)의 의미도 그대로여서 승인한 열쇠만 소모되고 세어진다. 같은 작업 지시의 다른 후보 제안은 남고, 누르면 `REMEDY_NOT_APPLIED` 로 거부되며 소모도 셈도 없다. `Unassigned` 에는 여전히 조치 열이 없으며 후보마다 `proposal`·`withheldProposal`·`remedySearches` 로 읽는다.

    전에는 사람의 문이 거부를 사유 텍스트(`Submission.Rejected.reason`)로만 냈다. 밖의 문(`attemptApproval`)은 `ApprovalOutcome.Refused.refusal` 로 종류(`ApprovalRefusal`)를 냈으므로, 사람의 문을 부르는 쪽은 문자열로 갈라야 했다. 지금은 `Middleware.Submission.Rejected` 끝에 칸 둘을 더했다. `approvalRefusal: ApprovalRefusal?` 와 `consumedApproval: ConsumedApproval?` 이며 기본값은 널이라 기존 호출이 그대로 컴파일된다. 승인이 아닌 작업 수락의 거부면 둘 다 널이다. `approveRemedy` 가 채우는 값은 승인자 종류가 `AGENT` 면 `VALUES_NOT_ACCEPTED` 다(이 열거값이 실제로 나가는 첫 자리). 판정 거부(`Judgment.No`)면 판정의 종류 그대로이고, `CONSUMED` 면 소모 기록도 싣는다(종류와 기록의 짝은 판정이 이미 검사한다). 작업 수락이 멱등으로 접히면 `REMEDY_NOT_APPLIED` 다. 작업 수락이 거부하면 놓은 작업 지시일 때 `REMEDY_NOT_APPLIED`, 아니면 `REFUSED_BY_GATE` 다. 이 마지막 갈림은 밖의 문과 같은 비공개 함수 `commitRefusal` 로 뽑아 두 문이 같은 값을 낸다. 사유 텍스트, 승인 성공 경로, 승인 창구(`ApprovalWire`) 규약 버전 4(`Approval.kt` 는 고치지 않았다)는 그대로다.

    전에는 `release`(운영자가 막는 결함을 감수)가 실행의 `blockedBy` 를 비우고 물리 상태를 `RUNNING` 으로 돌려도 작업 응답을 내지 않았다. 그래서 마지막 작업 응답이 실행이 다음에 알릴 때까지 `OPERATOR_HOLD` 와 감수한 결함의 `blockedBy` 를 들었다. 지금은 `release` 가 참을 돌려줄 때 `notify` 를 한 번 불러 작업 응답 하나(`RUNNING`, 빈 `blockedBy`)를 낸다. 거짓을 돌려줄 때(모르는 실행, 막는 결함 없음, 작업 허가만 막고 있음)는 내지 않으므로 다시 불러도 응답이 더 나가지 않는다. 작업 응답 형식(`ResultExport`, 버전 1)은 그대로다. 늘어나는 것은 아웃박스의 줄 하나다. 작업 허가의 막음(`permitBlock`)은 여전히 건드리지 않는다(ADR 52).

    계약 proto, 계약 SemVer 0.9.0, 내보내기 버전 6, 인계 번들(`handoff/`), 판단 기록 인코딩 `JudgmentRecordWire` 버전 1, 승인 창구 규약 버전 4, 작업 응답 형식(`ResultExport`) 버전 1은 바뀌지 않았다. `contracts/`와 `Approval.kt`는 손대지 않았다. 내보내기 시나리오(참조 실행기와 인계 생성 시험)는 `adopt`·`assign`·`release`를 부르지 않으므로 인계 번들과 내보내기 내용도 그대로다. 새 공개 면은 `Middleware.Submission.Rejected`의 칸 둘(`approvalRefusal`, `consumedApproval`, 기본값 널)이다. 바뀐 코드 파일은 `Middleware.kt`이고 `Assignment.kt`(`Unassigned` KDoc)와 `RemedyDesk.kt`(`note` KDoc)는 KDoc만 고쳤다.

    시험은 새 시험 6건이 추가되어 전체 시험 수가 2,129개에서 2,135개로 늘어났고, 이름을 바꾼 시험 1건(증감 없음)과 단언이나 메시지를 고친 기존 시험 7건이 있다. 새 시험은 `AssignmentTest` 4건(«전부 떨어지면 후보마다 제안이 서고 순위 차례대로 대장에 남는다», «하나라도 통과하면 앞에서 떨어진 후보는 적지 않는다», «작업 수락이 관문 앞에서 거부할 작업 지시는 미배정이어도 적지 않는다», «미배정에서 선 제안의 승인은 작업 수락의 제안과 같이 소모되고 세어진다»), `EntitlementTest` 1건(«소모가 아닌 사람의 문 거절은 종류만 싣고 소모 기록은 없다»), `InspectAssetTest` 1건(«감수하면 막음이 걷힌 작업 응답이 곧바로 하나 나가고 다시 감수해도 더 나가지 않는다»)이다. `RemedyLedgerTest`의 «관문에 물어보기만 한 것은 대장에 안 쌓인다»를 «미배정으로 끝난 채택도 시도한 것으로 대장에 남는다»로 이름을 바꾸고, 빈 레지스터 단언을 `FOUND` 줄 하나 단언으로 바꿨다. `OccupancyTest` «미배정이어도 후보마다 출발 결품의 답이 구조로 남는다»의 마지막 단언은 빈 레지스터에서 `SOURCE_MISSING` 줄 하나(미배정의 결품 응답과 같은 값)로 바뀌었다. `SourceObservationTest` «REQUIRED 이면 미배정의 후보마다 확인 요구가 남고 결품 답에는 없다»는 단언 그대로(레지스터가 비어 있음)이고 실패 메시지와 주석만 바꿨다. `EntitlementTest`에서는 «에이전트는 값을 실어 승인하지 못한다»(`VALUES_NOT_ACCEPTED`), «관문이 거절해도 제안은 남는다»(사람의 문도 `REFUSED_BY_GATE`), «그 주문이 다른 기체에서 끝나 놓였으면 승인으로 안 센다»(사람의 문 `REMEDY_NOT_APPLIED`), «소모된 제안을 사람의 문으로 다시 누르면 소모를 사유로 받는다»(`CONSUMED`와 소모 기록의 승인자·`executionId`), «주문이 이미 실행으로 서 있으면 승인은 조치 열을 싣지 않고 소모로도 남지 않는다»(두 문의 종류가 같음)에 단언을 추가했다. 결함 주입은 14건을 진행했고 주입 회차마다 해당 시험 클래스만 돌렸으며, 복원 뒤 sha256 대조가 일치했다. 판정은 JUnit XML의 실패 시험 이름이다. 14건 모두 잡혔다. `adopt` 기록 줄을 제거한 주입(J01)에서는 `RemedyLedgerTest` «미배정으로 끝난 채택도 시도한 것으로 대장에 남는다»가 실패했고, `release`의 `notify`를 제거한 주입(J13)과 막는 결함이 없을 때도 `notify`를 한 주입(J14)은 `InspectAssetTest` «감수하면 막음이 걷힌 작업 응답이 곧바로 하나 나가고 다시 감수해도 더 나가지 않는다»가 잡았다. 나머지 주입은 `AssignmentTest`(J02~J05), `OccupancyTest`(J06), `EntitlementTest`(J07~J12)의 해당 시험이 잡았다. 단위 id 중복 가드 갈래 하나만 빼는 주입(J04가 같은 조건문 전체를 대신한다)과 `SourceObservationTest`의 기존 단언(새 단언이 아니다)은 주입하지 않았다.

    한계 레지스터에서는 소비자 대기 행 «§15.164»를 닫았고(소비자 대기 21→20, 전체 84→83, 내부 37, 외부 26, 의도적 제외 13은 그대로, 새로 연 행은 없다), 항목 164에 «(닫힘, §15.229)» 문단을 더했다. 그 행의 해소 조건(배정 정책이 붙어 그 경로의 소비자가 생기면 적는 자리를 `adopt`까지 넓힘)이 충족됐다. 소비자는 picasso-ops 실행 호스트다. 어느 도장의 오픈 항목 목록에도 §15.164가 없었으므로 오픈 항목 수는 144개 그대로다. `docs/limits.md`의 변경 이력 번호를 228에서 229로 올렸다. `docs/orchestration.md`에서는 §6 적재 규약의 «레지스터의 하위 범주» 행(채택 한 번이 후보마다 줄을 낼 수 있음), §7.3(사람의 문도 거부 종류와 소모 기록을 냄, 승인 창구 규약 버전 4 그대로), §8(감수가 작업 응답 하나를 냄)을 고쳤다. ADR 44에서는 «문을 둘로 나눕니다» 표에 «거부의 모양» 칸을 더하고 에이전트 줄에 `VALUES_NOT_ACCEPTED`를 적었다. ADR 46에서는 «대가»의 «사람의 문은 텍스트로 낸다» 문단을 고치고, «이미 실행이 선 작업 지시의 승인은 접는다» 문단과 «대는 것»의 머리 문단·표를 고쳤다. `README.md`의 집계, `CLAUDE.md`와 `docs/verification.md`의 시험 수를 갱신했다. 도장을 다시 찍었다.

228. **취소된 `WatchTask` 스트림을 어댑터 호스트가 표에서 빼게 하고, 미들웨어 포트가 영구 실패에 다시 붙지 않고 닫힌 단위의 태스크를 표에서 내리게 했으며, 미들웨어가 스냅샷 세션 변화를 보고 커서를 다시 세우고 에뮬레이터가 같은 멱등 키의 다른 스킬을 호스트와 같은 차례로 응답하게 했다.**

    `HostTaskService` 의 `watchers` 표가 취소된 `WatchTask` 스트림을 지우지 않았다. 다음 전이를 미는 `push` 가 그 스트림의 `onNext` 를 부르면 gRPC 가 `CANCELLED: call already cancelled` 를 던지고, 그 예외가 갱신을 적은 쪽의 호출(펌프를 지난 무관한 RPC)의 실패로 나갔다. 같은 태스크에서 뒤에 있는 열린 스트림은 그 갱신을 받지 못했으며, 새 시험을 수정 전 코드에서 돌리면 `GetSnapshot` 이 `StatusRuntimeException: CANCELLED: call already cancelled` 로 실패했다. 이에 `HostServices.kt` 의 `watchTask` 첫머리에서 `ServerCallStreamObserver.setOnCancelHandler` 를 달았다. 처리기는 호출의 취소 콜백에서 돌고 `AdapterHost` 의 `Serialized` 인터셉터가 그 콜백을 호스트 잠금 아래로 줄 세우며, 같은 태스크의 다른 스트림은 둔 채 그 스트림 하나만 표에서 뺀다(`forget`). 또 `push` 가 밀기 전에 취소된 관측자(`Watcher.departed`, `isCancelled`)를 먼저 걷어 내고, 남은 것이 없으면 그 태스크 항목을 지우도록 에뮬레이터의 `TaskServiceImpl.publish` 와 같은 모양으로 맞추었다. 처리기와 `departed` 걸러내기는 각각 혼자서도 새 시험을 통과시킨다. 처리기는 표 정리와 `onNext` 예외 억제를, `departed` 는 취소 플래그가 선 뒤 처리기가 돌기 전 구간을 맡는다.

    `ClientRobotPort.watch` 는 스트림 실패를 종류 없이 다시 붙었다. 기체가 모르는 태스크(`NOT_FOUND`)는 `watch` 마다 스트림을 다시 열었고(첫 `watch` 뒤 서버가 받은 `WatchTaskRequest` 2건, 3번 더 부른 뒤 5건), 태스크별 누적 목록과 팔로워 표(`followed`)는 종료 뒤에도 지우지 않았다(실행이 `PHYSICALLY_DONE` 으로 닫힌 뒤에도 표에 태스크 `SEQ-208#RACK-208.S01` 이 남음). 이에 `Ports.kt` 에서 팔로워의 오류가 `NOT_FOUND`·`OUT_OF_RANGE` 이면 다시 붙지 않게 했는데, 이 둘은 contract.md §7.1 이 `WatchTask` 의 실패로 적은 것이며 상수 `PERMANENT` 에 두었고 그 밖의 실패(기한 초과, 연결 단절)에는 전처럼 다시 붙는다. 또한 `RobotPort` 에 `release(robotId, handle)` 를 더해(기본 구현은 아무것도 안 함) `ClientRobotPort.release` 가 그 태스크 항목을 표에서 지우도록 했으며, 시험용으로 `internal val following` 을 두었고 `Middleware` 는 단위가 닫히는 자리(`execution.active = null` 직후)에서 `execution.handle` 이 있으면 `robots.release` 를 부르게 했다. 포트가 종료를 본 순간 스스로 지우는 방식은 다음 `watch` 가 스트림을 처음부터 다시 열어 기존 시험 «종료를 본 뒤에는 다시 붙지 않는다» 와 충돌하기 때문에 택하지 않았다. `RobotPort by delegate` 로 위임하는 시험 더블은 그대로 따라가고, 명시 구현인 `LossyRobotPort` 는 `release` 를 위임하지 않는다(기본 no-op, 시험 영향 없음). 다만 영구 실패를 미들웨어가 알 길은 여전히 없어(`watch` 반환형 그대로) 그 단위는 `RUNNING` 에 머물고 `IN_DOUBT` 로 가지 않는다. 정체 판정(`noteProgress`)이 진행률을 재는 기체에서만 알림을 내는 것도 그대로이며, 단위가 닫히는 경로를 지나지 않고 버려진 실행의 항목은 남는다.

    contract.md §7.1 `GetSnapshot` 상세가 키를 `session_id` + `sequence` 로 적고 계약은 재기동 시 신규 세션을 전제하므로, 이벤트 번호는 세션 안의 번호이고 세션이 바뀌면 스냅샷부터 다시 세운다. 이 동작에 proto 변경은 필요 없었다. 시험 더블 `RestartingRobotPort`(`LossyPorts.kt`)가 하위 재기동을 흉내 내어 세션을 바꾸고 이벤트 번호를 0부터 다시 세며 재생은 요청 번호 이상만 걸러 돌려줄 때, 수정 전 새 시험은 세션 변화를 보지 못해 `resyncs` 기대 2, 실제 1로 실패했다. 이를 고치고자 `Model.kt` 의 `RobotSnapshot` 에 빈 값은 못 읽었다는 뜻이고 비교하지 않는 `session: String = ""` 칸을 더하고 `ClientRobotPort.snapshot` 이 응답 헤더의 `session_id` 를 채우게 했으며, `Middleware.RobotView` 에도 `session` 을 더했다. `sync` 는 스냅샷의 세션이 앞서 읽은 세션과 다르면 커서를 그 스냅샷의 번호로 다시 세우고 `resyncs` 를 하나 올리며 실행 자취에 `RESYNC` 줄(«session changed at cursor=… (A -> B); rebuilt from snapshot sequence=…»)을 남긴다. 다만 행의 해소 조건에 «`adapter-host` 가 프로세스를 넘어 겹치지 않는 세션을 낼 때(§15.21)» 가 함께 있어 행을 닫지 못했다. `HostedRobot.sessionId` 는 여전히 프로세스 카운터(`host-N`)라 재시작마다 `host-1` 이 되풀이될 수 있고, 그러면 미들웨어의 새 비교가 재시작을 보지 못한다. §15.21 의 해소 조건(결정론적 시드 기반 고유 식별자 생성기)은 설계 결정이라 이번에 손대지 않았으며, 행 스스로 계약 변경이라 적었으므로 옛 커서를 축출로 응답하는 규칙은 두지 않았고 두 발신자가 옛 커서에 빈 응답을 주는 것도 그대로다.

    같은 `(task_id, revision)` 에 다른 `skill_type` 이 오면 `adapter-host`(`HostedRobot.start`)는 revision 을 먼저 보아 기존 핸들을 돌려주고(멱등), 에뮬레이터(`TaskHost.update`)는 스킬을 먼저 보아 `INVALID_TRANSITION` 으로 거부했다. 낮은 revision 에 다른 스킬이 와도 호스트는 `OUTDATED_REVISION`, 에뮬레이터는 `INVALID_TRANSITION` 으로 갈렸다. 수정 전 `HostParityTest` 새 시험에서 에뮬레이터 `[같은키=false|0|INVALID_TRANSITION, 낮음=false|INVALID_TRANSITION]`, 호스트 `[같은키=true|2|UNSPECIFIED, 낮음=false|OUTDATED_REVISION]` 이었고, `TaskRpcTest` 새 시험에서는 같은 키 재전송이 `INVALID_TRANSITION` 이었다. contract.md §3 «멱등» 행(같은 `task_id`·`revision` 을 다시 보내면 같은 핸들이 오고 파라미터도 안 덮인다)과 proto `TaskHandle` 주석(멱등성 키는 `(task_id, revision)` 단조쌍)에 따라 스킬은 키가 아니라 실린 값이므로 키를 먼저 보며, 이는 «키만 본다»는 소유자 결정에 따른 응답이다. 이에 따라 `TaskHost.kt` 의 `update` 에서 스킬 비교 조건에 `revision > task.machine.revision` 을 더했다. 같은 revision 은 스킬과 무관하게 `Idempotent`, 낮은 revision 은 스킬과 무관하게 `OUTDATED_REVISION` 이 먼저 나간다. 높은 revision 의 스킬 교체는 그대로 `INVALID_TRANSITION` 이며 기존 시험 «도는 태스크의 갱신에 둘이 같은 답을 낸다» 가 그것을 덮고, `adapter-host` 는 바꾸지 않았다. 거부 사유 문구(`detail`)는 두 발신자가 원래 다르므로, 대조는 코드, 핸들 유무, revision 만 맞댄다(기존 갱신 대조 시험과 같은 수준).

    계약 proto, 계약 SemVer 0.9.0은 바뀌지 않았다. `contracts/`는 손대지 않았다. `adapter-host`의 `HostedRobot`은 바꾸지 않았다(고친 것은 `HostTaskService`). 새 공개 면은 `RobotPort.release(robotId, handle)`(기본 구현 있음, 기존 구현체가 깨지지 않음), `RobotSnapshot.session`(기본값 빈 문자열), `Middleware.RobotView.session`이다.

    시험은 새 시험 6건이 추가되어 전체 시험 수가 2,123개에서 2,129개로 늘어났고, 기존 시험은 고치지 않았다. 새 시험은 `AdapterHostTest` 1건(«떠난 WatchTask 스트림에는 밀지 않고, 그 일로 다른 RPC 가 깨지지 않는다»), `StreamResumeTest` 1건(«기체가 모르는 태스크면 다시 붙지 않는다»), `EventStreamTest` 2건(«하위가 다시 떠 세션이 바뀌면 옛 커서를 버리고 스냅샷부터 다시 세운다», «끝난 단위의 태스크는 포트의 갱신 표에서 내린다»), `TaskRpcTest` 1건(«같은 키는 스킬이 달라도 같은 핸들이고, 낮은 revision 은 스킬이 달라도 OUTDATED_REVISION 이다»), `HostParityTest` 1건(«같은 키에 다른 스킬이 와도 둘이 같은 답을 낸다»)이다. 재현에서는 새 시험 6건이 모두 수정 전 코드에서 실패했다. `AdapterHostTest` 45건 중 1건(«떠난 스트림 때문에 무관한 RPC 가 깨졌다»), `StreamResumeTest`의 새 시험(기대 2, 실제 5), `EventStreamTest`의 «끝난 단위의 태스크는 포트의 갱신 표에서 내린다»(표에 태스크가 남음)와 세션 시험(`resyncs` 기대 2, 실제 1, 7건 중 1건), `HostParityTest` 10건 중 1건, `TaskRpcTest` 29건 중 1건이다. 결함 주입은 6건을 진행했고 주입마다 해당 시험 클래스만 돌렸으며, 복원 뒤 `cmp`로 바이트 일치를 확인했다. 판정은 JUnit XML의 실패 시험 이름이다. 취소 처리기가 `watchers.remove(taskId)`로 그 태스크의 스트림 전부를 지우게 바꾼 주입에서는 `AdapterHostTest` 45건 중 새 시험 1건이 실패(«남은 스트림이 종착을 못 받았다: [ACCEPTED, RUNNING]»)했다. `PERMANENT`에서 `NOT_FOUND`를 뺀 주입에서는 `StreamResumeTest` 5건 중 새 시험 1건이 실패(기대 2, 실제 5)했다. `Middleware`의 `release` 호출 줄을 `Unit`으로 바꾼 주입에서는 `EventStreamTest`에서 «끝난 단위의 태스크는 포트의 갱신 표에서 내린다» 1건이 실패(표에 태스크가 남음)했다. `following`을 늘 빈 집합으로 바꾼 주입에서는 같은 시험이 전제 단언(«도는 태스크를 따라가지 않는다»)에서 실패했다. 세션 변화 갈래에서 커서를 옛 값으로 둔(`view.cursor = cursor`) 주입에서는 `EventStreamTest` 7건 중 새 시험 1건이 실패(«새 세션의 전이를 건너뛰었다»)했다. 에뮬레이터 조건을 `revision != task.machine.revision`으로 바꾼(같은 키는 통과, 낮은 revision은 스킬로 거부) 주입에서는 `TaskRpcTest` 29건 중 새 시험 1건이 실패(«낮은 revision 을 스킬로 먼저 갈랐다»)했다. 처리기 하나만 빼거나 `departed` 걸러내기 하나만 빼는 주입은 하지 않았다(둘 다 혼자서 이 시험을 통과시키므로).

    한계 레지스터에서는 내부 오픈 항목 행 셋인 «§15.220 · 취소된 스트림에 밀기», «§15.210 · 재부착», «§15.215 · 같은 키 다른 스킬»을 닫았고(내부 39→37, 전체 86→84, 소비자 대기 21, 외부 26, 의도적 제외 13은 그대로), 항목 210, 215, 220에 «(닫힘, §15.228)» 문단을 더했다. 내부 오픈 항목 행 하나를 새로 열었다: «§15.228 · 영구 스트림 실패». «§15.210 · 재부착»의 본문에만 있던 빈자리(영구 스트림 실패가 실행 상태에 반영되지 않아 단위가 `RUNNING`에 머물고 `IN_DOUBT`로 가지 않음)가 그 행을 닫으며 추적 행을 잃었기 때문이다. «§15.215 · 옛 커서» 행은 열린 채 두고 본문과 해소 조건을 남은 것(§15.21의 프로세스를 넘는 세션, 옛 커서를 축출로 응답하는 계약 규칙)으로 좁혔다. «§15.210 · 적재 잠금», «§15.215 · 취소 보고 상수», «§15.220 · 호스트 잠금 아래 남쪽 호출» 행은 열린 채 둔다. `docs/contract.md`에서는 §3 «멱등 — 발신자 둘»에 `HostParityTest` 새 시험을 더했고, §7.1의 `WatchTask`·`ReplayEvents` 문장과 표, §7.2의 (a)·(d)·(f) 행, §7.4의 7번 행을 고쳤다. §7.2 (a)의 같은 키·다른 스킬 행은 «미검증»에서 «검증됨(같은 프로세스)»이 됐다. 도장의 열림 목록에서 «§15.210 · 재부착», «§15.215 · 같은 키 다른 스킬»을 뺐다. `docs/contract.md`의 §7.2 (d) 둘째 행과 §7 끝의 추적 행 목록이 새 행을 가리키고, 도장의 열림 목록에 «§15.228 · 영구 스트림 실패»를 더했다. 오픈 항목(열림) 수는 145개에서 144개로 줄었다. `docs/limits.md`의 변경 이력 번호를 227에서 228로 올렸다. `README.md`의 집계, `CLAUDE.md`와 `docs/verification.md`의 시험 수를 갱신했다. 도장을 다시 찍었다.

    (닫힘, §15.243) 이 항목이 연 행 «§15.228 · 영구 스트림 실패»는 `RobotPort.unfollowable`을 추가해 갱신 스트림의 영구 실패를 보고하고 단위를 미확정(`IN_DOUBT`)으로 옮겨 `StartTask` 재전송 없이 해소하도록 하여 §15.243에서 닫혔다.

227. **작업 지시의 설비 id 가 대기 노드 id 와 겹치는 계획을 작업 수락과 리비전이 거부하게 했고, 출발 자리나 기대 신원이 없는 노드의 출발 관측 선언을 검증기가 거부하게 했다.**

    대기 단위의 id 는 임무 정의의 노드 id 이고 반복 단위의 id 는 작업 지시의 설비 id 이며, 둘이 같은지는 작업 지시가 와야 안다. 대기 노드 `rack-arrival` 을 든 정의(`withArrivalWait`)를 활성화하고 첫 슬롯 id 를 `rack-arrival` 로 둔 작업 지시를 냈을 때, 수정 전 `submit` 과 버전 2 리비전은 모두 `Accepted` 로 계획에 단위 id `rack-arrival` 이 대기 단위와 슬롯 단위 둘 섰고, `revise` 가 새 계획을 단위 id 로 접기(`associateBy { unitId }`) 때문에 대기 단위 자리에 슬롯 단위가 들어가 대기가 사라졌다. 리비전은 단위 id 로 접고 인시던트는 단위 id 로 찾으며 작업 응답은 단위 id 로 완료와 미완료를 적으므로 단위 id 가 겹치면 한쪽이 가려진다. 이에 따라 계획의 단위 id 가 겹치면 `Submission.Rejected` 를 내는 비공개 함수 `Middleware.duplicateUnitIdRefusal(planned)` 를 두어, `submit` 에서는 계획 직후이자 관문 `admits` 앞, `revise` 에서는 새 계획 직후이자 사슬 검사 앞에 두었다. 거부 사유는 새 공개 상수 `Middleware.DUPLICATE_UNIT_ID`(«계획의 단위 id 가 겹쳐 받지 않는다»)로 시작하고 뒤에 겹친 id 가 붙으며, 근거 등급 거부(`evidenceRefusal`)와 같은 처리로 조치 탐색 레지스터(`desk.record`)에 남기지 않는다. 태스크 id `jobOrderId#unitId` 가 단위 id 를 싣고 겹치지 않는 작업 지시의 단위 id 와 태스크 id 는 그대로여야 하기 때문에 id 를 바꿔 피하지 않았으며, 코드 케이퍼빌리티가 같은 설비 id 를 거듭 낼 때도 같은 검사에 걸리나 기존 시험 중 여기에 걸린 것은 없다. 검증기는 정의만 보므로 설비 id 를 몰라 바꾸지 않았고 `MissionValidator` KDoc 의 «보지 않는 것» 항목에 작업 수락과 리비전이 막는다는 줄을 더했다. `adopt` 에는 관문을 지난 후보가 있으면 `submit` 이 같은 사유로 거부하고 후보가 전부 관문에서 떨어지면 겹침 사유가 아니라 `Unassigned` 라서 따로 검사를 두지 않았으며, `resume` 역시 이 수정 뒤로는 겹친 작업 지시가 접수되지 않아 수정 전에 받은 실행을 다시 지을 때만 해당하므로 검사를 두지 않았다.

    `source` 나 `expectedIdentity` 를 뺀 단위 노드에 `sourceObservation` 을 적은 정의를 수정 전 검증기가 거부하지 않아 거부 목록이 비는 결함이 재현되었다. 출발 자리(`source`)가 없으면 관문이 그 선언을 읽지 않고 기대 신원(`expectedIdentity`)이 없으면 `REQUIRED` 가 신원 대조 없이 설비의 침묵만 막으므로 어느 쪽이든 적은 뜻과 도는 뜻이 달랐다. 이를 고치고자 해결 담당은 `ENGINEER`, 바로 갈 작업은 «출발 관측 선언을 지우거나 출발 자리와 기대 신원을 함께 적는다» 로 둔 새 거부 종류 `MissionRefusalKind.SOURCE_OBSERVATION_UNUSED` 를 더했다. 이제 출발 관측 검사는 신원 대조가 있는데 선언이 없으면 기존의 `SOURCE_OBSERVATION_UNDECLARED` 를 내고, 신원 대조가 없는데 선언이 있으면 새 `SOURCE_OBSERVATION_UNUSED` 를 낸다. 새 거부의 관측값은 `sourceObservation=REQUIRED` 또는 `sourceObservation=OPTIONAL`, 기대값은 «선언 없음(출발 자리와 기대 신원이 둘 다 있는 노드만 선언한다)» 이다. 이 수정으로 검사 수는 여섯 그대로 유지하면서 거부 종류는 10 에서 11 이 됐다. 다만 이미 활성화된 버전을 저장본으로 다시 지을 때(picasso-ops 의 `StoredMissionCatalog`) 넘어지지 않게 하려고, 생성 때도 막는 `SOURCE_OBSERVATION_UNDECLARED` 와 달리 해석기 `DefinedCapability` 의 생성 검사에는 더하지 않았으며 이 차이를 KDoc 에 적었다. picasso 픽스처와 picasso-ops 템플릿 셋(`DATA_V1`·`ARRIVAL_WAIT`·`ARRIVAL_WAIT_HOLD`)의 선언은 전부 `source` 와 `expectedIdentity` 가 있는 `place` 노드에 있어 픽스처와 예시 정의는 영향이 없고, picasso-ops 의 거부 종류 라벨표(`ui/src/labels.ts`)와 화면 설계서 표(`docs/screens/screen-design.md`)에 새 종류를 반영할지는 받는 쪽이 정한다.

    셋째로 살핀 재할당의 `AdmissionGate.inconsistent` 부분 대조는 재현되지 않았고 이미 닫혀 있었으므로 코드와 시험은 바꾸지 않았다. `reassign` 은 §15.214(`2e2518d`)부터의 거동에 따라 관문 앞에서 `DONE` 단위가 하나라도 있으면 «끝난 단위가 있다» 사유의 `Kept` 로 돌려보낸다. 그래서 관문에 넘기는 남은 단위(`DONE` 제외)는 늘 전체 단위이고, `AdmissionGate.inconsistent` 의 자재 대조도 작업 지시 전체로 맞다. 배치 작업 지시는 단위 하나가 끝나면 그 전에 파지도 관측되므로 «자재를 다룬 단위» 막음도 함께 걸린다. 기존 시험 `ReassignmentTest` 의 «끝난 단위가 있으면 안 옮긴다» 와 «든 채로 멈춘 실행은 안 옮긴다» 가 이 거동을 고정한다. 다른 관문 호출 자리에도 부분 대조 경로가 없어 `submit` 과 `adopt` 는 전체 계획을 넘기고 `revise` 는 `chainRefusal` 만 부르며 `resume` 은 관문을 거치지 않는다.

    계약 proto, 계약 SemVer 0.9.0, 내보내기 버전 6, 임무 정의 `schemaVersion` 1, 인계 번들은 바뀌지 않았다. 작업 지시의 단위 id 형식과 태스크 id(`jobOrderId#unitId`)는 겹치지 않는 작업 지시에서 그대로다. 새 공개 상수는 `Middleware.DUPLICATE_UNIT_ID` 하나, 새 거부 종류는 `MissionRefusalKind.SOURCE_OBSERVATION_UNUSED` 하나다.

    시험은 새 시험 3건이 추가되어 전체 시험 수가 2,120개에서 2,123개로 늘어났고, 기존 시험은 고치지 않았다. 새 시험은 `MissionVersionScenarioTest` 2건(«작업 지시의 설비 id 가 대기 노드 id 와 같으면 받지 않는다», «리비전이 대기 노드 id 와 같은 설비 id 를 들이면 받지 않고 실행은 그대로다»)과 `MissionValidatorTest` 1건(«출발 관측 검사 — 출발 자리나 기대 신원이 없는 노드의 선언은 거부한다»)이다. 결함 주입은 3건을 진행했고 주입마다 해당 시험 클래스만 돌렸으며, 복원 뒤 `cmp`로 바이트 일치를 확인했다. 판정은 JUnit XML의 실패 시험 이름이다. `submit`의 `duplicateUnitIdRefusal(planned)` 호출 줄을 지운 주입(I1)에서는 `MissionVersionScenarioTest` 8건 중 «작업 지시의 설비 id 가 대기 노드 id 와 같으면 받지 않는다» 1건이 실패(`Rejected` 기대, `Accepted` 반환)했다. `revise`의 `duplicateUnitIdRefusal(fresh)` 호출 줄을 지운 주입(I2)에서는 `MissionVersionScenarioTest` 8건 중 «리비전이 대기 노드 id 와 같은 설비 id 를 들이면 받지 않고 실행은 그대로다» 1건이 실패(`Rejected` 기대, `Accepted` 반환)했다. `SOURCE_OBSERVATION_UNUSED` 갈래 조건에 `&& unit.id.isEmpty()`를 덧붙인 주입(I3)에서는 `MissionValidatorTest` 16건 중 새 시험 1건이 실패(`[SOURCE_OBSERVATION_UNUSED]` 기대, `[]` 반환)했다. I3의 첫 시도(조건 앞에 `false &&`)는 상수 조건 경고가 오류가 되어 컴파일되지 않아 시험이 돌지 않았으므로 무효로 보고 위 형태로 다시 했다.

    한계 레지스터에서는 내부 오픈 항목 행 둘인 «§15.209 · id 겹침», «§15.218 · 쓰이지 않는 선언»을 닫았고(내부 41→39, 전체 88→86, 소비자 대기 21, 외부 26, 의도적 제외 13은 그대로, 새로 연 행은 없다), 항목 209와 218에 «(닫힘, §15.227)» 문단을 더했다. 항목 218 본문의 «거부 종류 10개»는 그때의 기록이라 고치지 않고 닫힘 문단에 11로 적었다. 다른 §15.209 행(정의 둘·분기·스키마 파일·이벤트형 신호·임무 버전 내보내기·주소)과 «§15.218 · 미관측 레지스터» 행은 열린 채 둔다. ADR 50에서는 거부 종류 목록에 `SOURCE_OBSERVATION_UNUSED`를 더해 열하나로 고쳤고, «대가»의 id 겹침 문장을 작업 수락과 리비전이 `DUPLICATE_UNIT_ID`로 거부한다는 내용으로 고쳤다. ADR 50 도장의 열림 목록에서 «§15.209 · id 겹침»을 뺐다. 오픈 항목(열림) 수는 146개에서 145개로 줄었다. `docs/limits.md`의 변경 이력 번호를 226에서 227로 올렸다. `README.md`의 집계, `CLAUDE.md`와 `docs/verification.md`의 시험 수를 갱신했다. 도장을 다시 찍었다.

226. **조치 승인 시점의 사슬 거절에서 제안 덮어쓰기를 방지하고 이미 실행 중인 작업 지시의 채택을 멱등 응답과 개정으로 처리하도록 수정했다.**

    문제는 두 가지 경로에서 발생했다. 첫째는 조치 승인 시점에 사슬 거절이 일어날 때 서 있는 제안이 덮어써지는 결함이었다. `approveRemedy`나 `attemptApproval`이 `commit`을 거쳐 `submit(prefix, approvedBy)`을 호출하면 `admits`를 지나 `AdmissionGate.chainRefusal`의 `Found`로 빠지고, 이것이 `RemedyDesk.record(revision = false)`를 호출하여 `proposals[key]`를 교체했다. 계약 카탈로그 환경에서는 파지를 바꾸는 스킬이 `pick_place`(grasps와 releases 둘 다 참, 완료 시 EMPTY) 하나뿐이어서 조치 열의 걸음이 늘 `[pick_place]`이고, 승인 시점의 탐색은 HOLDING으로 향하는 조치를 찾지 못해 `None`만 반환하므로 정상 카탈로그로는 이 경로에 도달할 수 없었다. 이를 재현하기 위해 시험 전용으로 `HoldEffects`의 효과 표(`byName$delegate`의 LinkedHashMap)에 `grip`(grasps 참, releases 거짓)을 추가하고 종료 시 제거하는 방식으로 검증했다. 운영 코드 변경 없이 결함이 재현되었으며 수정 전 재현 결과는 `expected: <[pick_place]> but was: <[grip]>`의 실패였다. 둘째는 이미 도는 작업 지시를 채택할 때 발생하는 거부였다. 이미 실행이 선 `SEQ-1`을 대상으로 `adopt`를 호출하면 `Unassigned(자리 RACK-204.S01 를 SEQ-1(exec-1) 가 이미 잡고 있다)`를 반환했다. 동일한 주문의 `submit`이 `Idempotent`로 응답하는 것과 비교할 때 일관되지 않은 동작이었다.

    수정은 두 건에 대해 각각 이루어졌다. 첫째 건에서는 `RemedyDesk.record`에 `approval: Boolean = false` 인자를 추가했다. 이 값이 참이면 탐색 결과(`RemedyOutcome.Found`)를 탐색 레지스터에 기록하되 제안을 새로 세우거나 바꾸지 않고, 가림 차례인 `proposalsMade`를 세지 않으며, 거절의 `remedy`를 널로 두고 사유에 "승인 시점의 거절이라 서 있는 제안을 바꾸지 않았다"를 덧붙인다. 비공개 함수인 `Middleware.submit`은 `approval = approvedBy != null`로 값을 넘기며 `approvedBy`는 `commit`만 채운다. 이로써 사람의 문 응답은 `remedy`가 널인 `Rejected`가 되고 밖의 문 응답은 `ApprovalOutcome.Refused(REFUSED_BY_GATE)`를 그대로 유지한다. `RemedyDesk` 클래스와 `record` 인자, `Middleware.commit`에 KDoc을 보강했다. 둘째 건에서는 `adopt`가 묘비 거부(`FORGOTTEN_JOB_ORDER`) 뒤 카탈로그를 읽기 전에 동일한 `jobOrderId`의 실행이 있으면 `revise(existing, order)`로 이동하도록 수정했다. 이는 `submit` 및 `resume`과 동일한 분기이다. 그 결과 동일한 버전은 `Submission.Idempotent`로 응답하고, 낮은 버전은 거부하며, 높은 버전은 해당 실행의 개정(`Accepted`)으로 응답한다. 이때 순위 목록은 읽지 않으며 개정된 실행은 지금 기체에 남는다. 분기 순서상 묘비 거부가 먼저이므로 놓은 작업 지시는 실행 표에 없어 기존 시험인 `ForgetTest`의 "놓은 작업 지시 id 는 같은 판도 높은 판도 다시 받지 않는다"가 `adopt`를 대입하여 통과함을 확인했다. `Middleware.admits`는 거동을 바꾸지 않고 KDoc에 "새 작업 지시로 묻는다"를 기록했으며, `AdmissionGate.occupancyViolation` 주석은 `submit`과 `adopt`가 관문을 지나지 않고 공개 창구인 `admits`만 제 자리에 걸린다는 내용으로 정정했다. 소비자인 picasso-ops는 `admits`를 호출하지 않고 `assign`(내부에서 `adopt` 호출)만 사용하므로 `MissionHost.submitCurrent`가 `Idempotent`와 기존 실행의 `Accepted`를 이미 처리한다.

    계약 proto, 계약 SemVer 0.9.0, 내보내기 버전 6, 승인 창구 버전 4, `JudgmentRecordWire` 버전 1은 바뀌지 않았다. 새로운 공개 API는 없으며 `adopt`의 반환 타입도 불변이다. 승인 횟수(`approvals`, `settle`에서만 계수), ADR 46 소모 기록(`settle`에서만 기록), 개정 거절(`revision = true`) 거동, `None` 및 출발 결품 거절 거동도 기존대로 유지된다. 밖의 문 응답인 `ApprovalOutcome.Refused(REFUSED_BY_GATE)`와 `Middleware.admits`의 거동 역시 변경되지 않았다.

    시험은 신규 3건이 추가되어 전체 시험 수가 2,117개에서 2,120개로 늘어났다. 신규 시험은 `ApprovalChainRefusalTest` 1건("승인 시점의 사슬 거절은 서 있는 제안을 바꾸지 않고 조건이 풀리면 같은 제안으로 승인된다")과 `OccupancyTest` 2건("이미 도는 작업 지시를 다시 채택하면 그 실행을 멱등으로 돌려준다", "이미 도는 작업 지시의 높은 판을 채택하면 그 실행이 개정된다", `rack` 도우미에 `version` 인자 반영)이다. 결함 주입은 3건을 진행했고 주입마다 해당 클래스만 실행한 뒤 복원하여 `cmp` 바이트 일치를 확인했다. 첫째로 `approval = approvedBy != null`을 `approval = false`로 바꾼 주입(I1)에서는 `ApprovalChainRefusalTest`가 실패(`[pick_place]` 기대였으나 `[grip]` 반환)했다. 둘째로 `adopt`의 기존 실행 분기를 제거한 주입(I2)에서는 `OccupancyTest` 2건이 모두 `Unassigned`로 실패했다. 셋째로 기존 실행 분기를 버전과 무관하게 `Idempotent`로 바꾼 주입(I3)에서는 높은 버전 시험만 실패했다.

    한계 레지스터에는 새로 연 행도 닫은 행도 없다. 1번 문제는 재현과 수정을 거쳐 해소되었으므로 새 행을 열지 않았고, 한계 문서 `docs/limits.md` 5행의 번호 줄만 225에서 226으로 올렸다. 시험 수 변경을 반영하여 `CLAUDE.md` 11행과 `docs/verification.md` 3행의 수치를 2,117에서 2,120으로 고치고 도장 문서 도구인 `python tools/stamp.py`를 실행하여 도장을 다시 찍었다. `docs/orchestration.md` 276행의 `REFUSED_BY_GATE` 설명인 "조건이 해소되면 같은 제안이 그대로 섭니다"는 승인 시점 사슬 거절에서도 성립하게 되었으나 문구 수정 없이 유지했다. `docs/adr/0050-mission-definition-is-versioned-data.md` 31행의 "이미 있는 실행이면 `submit` 은 카탈로그를 보지 않고 리비전으로 간다"와 마찬가지로 `adopt`도 동일한 거동을 따르게 되었다.

225. **첫 소비자가 정착한 실행을 놓도록 연계하고 실행 일지을 통한 재제출 거부와 추가 전용 송신 기록를 반영했다.**

    첫 소비자인 picasso-ops의 PR #43(머지 커밋 `2f6f111`, 2026-10-15)이 머지되어 실행 놓기가 연계되었다. 서브모듈은 `forget`을 추가한 picasso `7d0fa8f`(PR #103)에 고정되었다. 실행 호스트인 `MissionHost`는 `pump` 뒤 기록 트랜잭션(송신 기록, 인시던트 사본, 판단 기록, 정착 이벤트)이 커밋되고 대기 중인 작업 응답에 `ack`을 마친 뒤에만 동일한 호스트 잠금 안의 `forgetSettled`에서 `Middleware.forget`을 호출한다. 트랜잭션이 실패하면 `ack`도 수행하지 않고 놓지도 않으며 다음 `pump`에서 재시도한다. 대상 후보는 현재 정착이 기록되었으며 활성 상태가 아닌 실행이다. 활성은 `pump`가 그 실행을 여전히 돌리거나(`PARTIAL` 포함), 어느 단위라도 `OPERATOR_HOLD` 또는 `IN_DOUBT`이거나, 실행 일지에 막힌 상태를 뜻한다. `PHYSICALLY_DONE`과 `ABORTED`로 종료된 실행은 즉시 놓는다. `FAILED`와 `UNVERIFIED`는 정착 기록의 커밋 시점부터 호스트 시계 기준으로 `host.settled.revisable-retention`(기본값 `PT5M`, 소유자 결정, 설정 가능) 동안 유지한다. 이 기간 안에는 동일한 버전에 `IDEMPOTENT`로 응답하고 더 높은 버전은 리비전으로 받으며, 기간이 지난 뒤 실행을 놓고 해당 작업 지시 id는 모든 버전에서 거부한다. `Forgotten` 결과에는 호스트가 해당 실행에 대한 자체 맵을 비우고, `Refused`에는 모든 것을 유지하며 경고를 기록하고 다음 `pump`에서 재시도하며, `Unknown`에는 아무 작업도 하지 않는다.

    재제출 방어와 관련하여 호스트는 picasso에 작업 지시를 제출하기 전에 실행 일지(`execution_journal`)에서 작업 지시 id를 조회한다. `SETTLED` 이벤트가 있는 행은 `Middleware.FORGOTTEN_JOB_ORDER`로 시작하는 사유와 함께 `REJECTED`로 거부하고, 정착 이벤트가 없는 행은 `JOURNALED_JOB_ORDER`로 시작하는 사유로 거부한다. 이는 재기동 전후에 모두 성립하므로 재기동 시 picasso의 메모리 묘비가 유실되는 한계가 소비자 쪽에서 보완된다. `docs/orchestration.md` §8의 의무와 관련하여 picasso-ops는 `responses()`로부터 `job-responses.jsonl`을 통째로 다시 쓰지 않는다. 각 작업 응답을 송신 기록 테이블인 `job_response_log`의 한 행으로 추가 적재하고, 정착마다 1개의 `SETTLED` 이벤트를 `execution_journal_event`에 추가한다. 두 테이블은 트리거가 UPDATE, DELETE, TRUNCATE를 차단하는 추가 전용이며, 정착 이력 화면은 미들웨어 메모리가 아닌 이 DB 기록을 읽는다.

    picasso-ops 쪽 근거는 `ForgetSettledTest`의 10개 시험과 picasso-ops 저장소의 측정 문서 `docs/measurements/2026-10-15-load-after-forget.md`이다. 정착된 실행 2,000건 및 10,000건을 대상으로 측정한 결과 실행 후 picasso의 실행 테이블과 아웃박스, 호스트 맵의 크기가 모두 0이었다(기준선은 실행 수와 동일). 한계 레지스터에서는 «§15.224 · 놓기 소비자» 행이 해소 조건을 채워 닫혔고(소비자 대기 22→21, 전체 89→88), 항목 224에 «(닫힘, §15.225)» 문단을 더했다. «§15.224 · 놓은 작업 지시 묘비» 행은 열린 채 둔다. picasso 쪽에는 여전히 묘비를 지우는 입구가 없고 인스턴스 수명 동안 놓은 작업 지시 id마다 묘비가 1개씩 계속 늘어나며, picasso-ops가 실행 일지로 작업 지시 id 유일성을 보장하지만 지우는 입구를 요청하지 않아 해소 조건의 절반만 만족되었기 때문이다. 해당 행의 설명은 이를 반영하여 다시 썼다. «§15.166» 행은 변경 없이 유지된다.

    문서에서는 `docs/orchestration.md` §8에 첫 소비자인 picasso-ops가 `forget`을 사용하며 통째로 다시 쓰는 대신 추가 적재한다는 한 문장을 더했다. `docs/limits.md`, `README.md`의 집계를 갱신했고, `README.md`의 문서 지도 행에 87로 남아 있던 수치를 88로 바로잡았다. 스탬프를 다시 찍었고 오픈 항목(열림) 수는 146개로 유지되었으며 한계 문서의 변경 이력 번호는 224에서 225로 올렸다. 항목 224의 문장 «첫 소비자 picasso-ops의 실행 호스트(`MissionHost`)는 아직 `forget`을 부르지 않는다»는 삭제하지 않고 이번 항목으로 바로잡는다. 계약 proto, 계약 SemVer 0.9.0, 내보내기 버전 6, 인계 번들, `JudgmentRecordWire` 버전 1, 승인 창구 버전 4는 바뀌지 않았다. picasso 코드나 시험은 변경되지 않았으며 전체 시험 수는 2,117개 그대로 유지된다.

224. **실행 표와 아웃박스에서 끝난 실행을 명시적으로 비우는 놓기 API를 추가하고 묘비와 단조 실행 번호로 식별자 재사용과 작업 지시 혼선을 막도록 변경했다.**

    지금까지 `Middleware`의 실행 표(`executions`)는 실행마다, 작업 응답 아웃박스(`outbox`)는 작업 응답마다 늘었고 지우지 않았다. 아웃박스의 작업 응답은 상위가 확인(`ack`)한 뒤에도 남아 둘 다 인스턴스 수명만큼 남았다. 이에 새 공개 API `Middleware.forget(executionId)`를 추가했다. 응답은 새 공개 타입 `ForgetOutcome`의 셋 중 하나로 `Forgotten`, `Unknown`, `Refused(reason)`다. 프로세스 안 API라 ADR은 두지 않았다(§15.221·§15.223과 같은 종류). 놓기는 명시적으로만 한다. 그 실행의 사실을 다 옮겨 적었는지는 담는 쪽만 알기 때문에, 본 계층을 세우는 담는 쪽이 부를 때만 놓고 개수 상한이나 시간에 따른 자동 축출은 두지 않았다. 모르는 실행 id나 이미 놓은 실행 id는 `Unknown`이다.

    놓는 조건 첫째는 실행 상태가 `PhysicalState.pumps`가 거짓인 상태, 곧 `PHYSICALLY_DONE`·`UNVERIFIED`·`FAILED`·`ABORTED` 중 하나여야 한다는 점이다. `pump`는 실행 표 전부를 돌기 때문에 도는 실행을 표에서 빼면 그 실행이 조용히 멈춘다. `PARTIAL`은 종료 상태(`isSettled`)지만 `pump`가 계속 돌리고 리비전과 `cancel`을 받으므로 거부하며, 거부 사유에 «리비전으로 다시 돌거나 취소로 끝낸 뒤 놓는다»는 안내가 실린다. 그 밖의 상태는 «끝나지 않은 실행» 사유로 거부한다. 둘째는 그 실행 id의 작업 응답 중 상위가 확인하지 않은 것이 아웃박스에 하나도 없어야 한다는 점이다. 미확인 작업 응답과 함께 빼면 `pending()`에서 사라져 아무도 다시 나르지 않기 때문이다. `ack`은 작업 응답 하나를 확인하면서 같은 작업 지시의 실행 표시를 `ACKED`로 두므로 앞 작업 응답만 확인해도 표시가 바뀐다. 그래서 판정은 실행 표시가 아니라 작업 응답마다 보며, 거부 사유에는 미확인 `jobResponseId`(`resp-N`)가 실린다.

    실행을 놓으면 실행 표에서 그 실행을 빼고, 그 실행 id의 작업 응답(전부 확인된 것)을 아웃박스에서 빼며, 작업 지시 id마다 묘비 하나(그 실행의 실행 id, 작업 지시 버전, 종료 상태)를 남긴다. 아울러 실행 id 셈을 바꿨다. 전에는 `exec-(실행 표 크기 + 1)`이었고 이제 단조 셈 `executionSeq`로 `exec-N`을 낸다. 놓기가 없으면 두 식이 같은 값을 내므로 기존 시험의 `exec-N` 기대는 그대로다. 크기로 세면 놓은 실행의 id가 새 실행에 다시 붙어, 그 id를 칸으로 든 인시던트 번들·승인 소모 기록·작업 응답이 새 실행을 가리키게 된다. 또 `IncidentLog.noteResolution`은 (실행 id, 단위 id)로 가장 최근의 판단 없는 번들을 찾으므로 id를 다시 쓰면 새 실행의 판단이 옛 번들에 붙을 수 있다.

    묘비가 필요한 이유는 `submit`·`resume`·`commit`·`ack`이 같은 작업 지시 id의 실행을 실행 표에서 찾기 때문이다. 표에서 빼기만 하면 같은 작업 지시 id가 새 실행으로 서고, 태스크 id가 `작업지시id#단위id` 모양이라 이미 끝난 태스크에 붙거나 같은 일이 두 번 돈다. 묘비의 효과로 `submit`·`adopt`·`resume`이 놓은 작업 지시 id를 버전과 무관하게(같은 버전도 더 높은 버전도) `Submission.Rejected`로 거부한다. 사유는 새 공개 상수 `Middleware.FORGOTTEN_JOB_ORDER`로 시작하고 작업 지시 id, 실행 id, 버전, 종료 상태와 «다시 할 일은 새 작업 지시 id 로 낸다»가 실린다. `adopt`는 배정 관문에 대기 전에 거부하므로 후보가 없거나 전부 떨어져도 `Unassigned`가 아니라 이 거부다. 외부 승인 창구의 승인(`attemptApproval`)은 기존 값 `ApprovalRefusal.REMEDY_NOT_APPLIED`로 거부하고(새 값은 없으며 전에는 관문 거부 `REFUSED_BY_GATE`로 나갔을 자리다), 사람의 승인(`approveRemedy`)은 같은 사유의 `Rejected`다. 이때 활성 제안은 지우지 않고 소모로도 남기지 않는다. 한편 `revise`는 `ABORTED`·`PHYSICALLY_DONE`만 거부하고 `FAILED`·`UNVERIFIED`는 더 높은 버전을 받아 `RUNNING`으로 되돌리므로, `FAILED`·`UNVERIFIED` 실행을 놓으면 그 리비전 경로도 닫힌다. 이는 설계 선택이며 다시 할 일은 새 작업 지시 id로 낸다.

    묘비의 대가는 놓은 작업 지시 수만큼 늘고(작업 지시마다 문자열 값 셋) 지우는 입구가 없다는 점이다. 상한을 두면 상한 밖에서 같은 id가 조용히 새 실행으로 서므로 두지 않았다. 묘비 없이 담는 쪽의 의무로만 두면 «같은 작업 지시 id의 같은 버전은 멱등»이 담는 쪽에 따라 깨지므로 묘비를 두었다. 묘비는 메모리라 재기동 뒤에는 없다. 재기동 전후의 재전송은 담는 쪽의 기록이 막고(picasso-ops는 저널 기록과 `SETTLED` 이벤트), 놓기가 없던 때도 재기동 뒤에는 같은 구멍이 있었다(§15.166과 같은 성질). 반면 인시던트 번들, 조치 탐색 레지스터, 활성 제안·보류·진단, 승인 소모 기록(ADR 46), 같은 조치의 승인 횟수(`repeatedRemedies`)는 남긴다. 인시던트 번들은 검토 지표와 내보내기가 읽고 picasso-ops의 `MissionHost.recordAfterPump`가 인시던트 목록을 앞에서부터의 개수 `copiedIncidents`로 이어 읽으므로 빼면 그 셈이 어긋나기 때문이며, 조치 탐색 레지스터는 실행을 들지 않고, 활성 제안·보류·진단은 작업 지시 조회 키이고 승인 판정이 읽으며, 승인 소모 기록은 빼면 다시 온 승인이 `CONSUMED` 대신 `NO_PROPOSAL`을 받기 때문이다. 이것들의 적재는 §15.166이 계속 추적한다. 놓은 뒤 `execution`·`judgmentRecord`·`lastCancel`은 널, `resolve`는 `NotHeld`, `release`·`cancel`은 `false`, `reassign`은 모르는 실행이라는 `Kept`, 놓은 작업 응답의 `ack`은 `false`를 낸다. 따라서 판단 기록은 놓기 전에 받아 둬야 한다. 배정 관문(`AdmissionGate`)은 같은 실행 표에서 `!isSettled`인 실행만 읽으므로 놓는 실행은 이미 거기서 빠져 있어 배정 판정은 바뀌지 않는다.

    `docs/orchestration.md` §8은 담는 측이 지킬 조건으로 `job-responses.jsonl`이 그 인스턴스의 처음부터의 전체 이력이어야 한다고 적는다. 놓기(`forget`)가 확인된 작업 응답을 아웃박스에서 빼므로 아웃박스(`responses()`)는 더 이상 전체 이력이 아니다. 그래서 놓기를 쓰는 담는 측은 `responses()`로 파일을 통째로 다시 쓰지 말고 이어 붙여야 한다. 이 의무를 §8에 적었다. 참조 실행기(`BundleWriter.carry`, 시험 소스)는 `responses()`를 개수가 늘 때만 통째로 다시 쓰지만 `ack`을 하지 않으므로 놓기가 거부되어 조건이 그대로 지켜진다. 첫 소비자 picasso-ops의 실행 호스트(`MissionHost`)는 아직 `forget`을 부르지 않는다. 그래서 그 배치에서는 실행 표와 아웃박스가 여전히 인스턴스 수명만큼 남는다. picasso-ops가 정착 기록과 `ack` 뒤에 `forget`을 부르면 닫힌다. picasso-ops의 정착 판정(종료 상태이면서 `PARTIAL`이 아님)은 놓기 조건과 같은 집합이다.

    한계 레지스터에서는 소비자 대기 행 둘을 열었다(소비자 대기 20→22, 전체 87→89). «§15.224 · 놓기 소비자» 행은 입구는 섰으나 picasso-ops가 아직 부르지 않는다는 내용이다. «§15.224 · 놓은 작업 지시 묘비» 행은 묘비가 놓은 작업 지시 id마다 남고 지우는 입구가 없으며 재기동 뒤에는 없다는 내용이며, 담는 쪽이 작업 지시 id 유일성을 자기 기록으로 보증하고 묘비를 놓을 입구를 요구할 때(ADR 9) 닫힌다. 기존 «§15.166» 행에는 놓기가 인시던트·조치 탐색·제안·승인 소모 기록을 건드리지 않고 놓은 실행의 인시던트 번들도 남는다는 사실을 더했다. 닫은 행은 없다.

    문서에서는 `docs/orchestration.md` §8(위 의무)과 §9 적재 행(놓은 실행은 `judgmentRecord`가 널이라 놓기 전에 받아 둔다)을 손질했다. `docs/seams.md` §1 주요 메서드 목록에 `forget(executionId)`를 더했다. `docs/contract.md`는 고치지 않았다(놓기는 계약 보장도 계약 RPC도 아니다). ADR은 두지 않았다. `docs/limits.md`, `README.md`의 집계, `CLAUDE.md`와 `docs/verification.md`의 시험 수를 갱신했다. 스탬프를 다시 찍었고 오픈 항목(열림) 수는 146개 그대로이며 한계 문서의 변경 이력 번호는 223에서 224로 올렸다.

    계약 proto, 계약 SemVer 0.9.0, 내보내기 버전 6, 작업 응답 형식 버전 1(`ResultExport`), 인계 번들, `JudgmentRecordWire` 버전 1, 승인 창구 버전 4, `ApprovalRefusal` 값 목록(새 값 없음), 인시던트 번들의 칸은 불변이다. 시험은 새 파일 `ForgetTest`에 7개, `EntitlementTest`에 2개를 더해 전체 시험 수가 2,108개에서 2,117개로 늘었다. 추가된 시험은 `ForgetTest`의 `끝나고 통보가 확인된 실행을 놓으면 실행 표와 아웃박스에서 빠진다`, `끝나지 않은 실행은 놓지 않는다`, `확인되지 않은 통보가 하나라도 있으면 놓지 않는다`, `구동이 아직 돌리는 PARTIAL 은 놓지 않고 취소로 끝낸 뒤 놓으며 사건 번들은 남는다`, `놓은 뒤 선 실행은 놓은 실행의 id 를 다시 쓰지 않는다`, `놓은 작업 지시 id 는 같은 판도 높은 판도 다시 받지 않는다`, `끝나고 놓기를 거듭해도 실행 표와 아웃박스가 비어 있다`와 `EntitlementTest`의 `그 주문이 다른 기체에서 끝나 놓였으면 승인으로 안 센다`, `승인으로 선 실행을 놓아도 다시 온 승인은 그 소모 기록을 받는다`이다. 기존 시험 메서드는 고치지 않았다(`EntitlementTest`에 헬퍼 하나를 더했다). 결함 주입 12건을 `Middleware.kt`에 한 줄씩 넣어 `ForgetTest`·`EntitlementTest` 50개를 돌렸고, JUnit XML의 실패 시험 이름으로 전부 탐지를 확인했다.

    **(닫힘, §15.225)** 이 항목이 남긴 한계 행 가운데 «§15.224 · 놓기 소비자»는 §15.225에서 닫혔고, «§15.224 · 놓은 작업 지시 묘비»는 열려 있다. picasso-ops PR #43(머지 커밋 `2f6f111`)에서 실행 호스트 `MissionHost`는 `pump` 뒤 기록 트랜잭션이 커밋되고 대기 중인 작업 응답에 `ack`을 마친 뒤 `forgetSettled`에서 `Middleware.forget`을 호출한다. `PHYSICALLY_DONE`과 `ABORTED`로 종료된 실행은 즉시 놓고, `FAILED`와 `UNVERIFIED`는 `host.settled.revisable-retention`(기본값 `PT5M`) 동안 유지하여 동일한 버전은 `IDEMPOTENT`로 답하고 더 높은 버전은 리비전으로 받으며 기한이 지나면 놓는다. 재기동 전후의 작업 지시 재제출은 실행 일지(`execution_journal`)을 조회하여 `SETTLED` 이벤트가 있으면 `Middleware.FORGOTTEN_JOB_ORDER`로 시작하는 사유로 `REJECTED`로 거부하므로 picasso 쪽 메모리 묘비 유실이 소비자 쪽에서 보완된다.

    (의도적 제외로 옮김, §15.265) «§15.224 · 이빅션한 작업 지시 툼스톤» 행은 picasso 와 picasso-ops 어디에도 그 값을 쓰는 면이 없어 의도적 제외의 새 표 «수요 없음» 으로 옮겼다.

223. **다시 연 인시던트의 번들 시각을 자취 줄 시각과 일치시켜 새 보류와의 구분을 바로잡고 첫 소비자의 판단 기록 영속을 반영했다.**

    §15.222는 다시 연 인시던트가 근거 윈도우(`evidenceWindow`)에 실리는 실행 자취 종류 `RESUME_HOLD_REOPENED` 줄로 표시되어 읽는 쪽이 이를 구분할 수 있다고 보았으나, 줄의 존재만으로는 구별되지 않는다. 다시 연 뒤 근거 윈도우 앞 폭(기본 30초, 현장 시간값 범위 5~120초, §15.212) 안에 새로 발생한 보류(가령 마감 시각이 다시 지난 재작업 뒤 같은 인스턴스의 보류) 또한 그 윈도우 안에 해당 줄을 싣기 때문이다. picasso-ops 종단간 시험 `RestartRecoveryTest` 5단계는 이를 보이며, 줄의 시각이 번들의 `at`과 같은지로 구분한다. 이번 수정 전에는 자취가 `now()`로 적은 줄 시각과 `IncidentLog.sealIncidents`가 `now()`로 다시 읽은 번들의 `at`이 한 번의 `resume` 호출 안에서 서로 다른 두 번의 시계 읽기였다. picasso 시험의 고정 시계와 picasso-ops 종단간 시험의 현장 가상 시계처럼 읽기 사이에 시계가 흐르지 않을 때만 두 시각이 일치했고, 실제 벽시계(picasso-ops 기본 배포)에서는 `resume` 1회 수행 시간만큼 줄 시각이 `at`보다 앞설 수 있었다. 이를 바로잡아 `Middleware.resume`은 보류를 처음 다시 열 때(`restore`) 시계를 한 번만 읽고, 그 동일한 시각을 모든 `RESUME_HOLD_REOPENED` 줄과 해당 봉인 번들의 `at`으로 삼아 `IncidentLog.sealIncidents(..., reopenedAt)`에 넘긴다. 근거 윈도우는 이 `at`을 기준으로 계산된다. `pump`의 봉인은 이전처럼 `now()`를 읽어 변경이 없으며, `wallClockAt`은 실제 시계에서 따로 읽혀 대상이 아니므로 구분 비교는 `at`으로 한다. §15.222의 본문은 지우지 않고 이 항목이 정정한다.

    첫 소비자인 picasso-ops의 PR #38(머지 커밋 `8e53e4e`, 2026-10-15)이 머지되어 판단 기록 영속이 연계되었다. 서브모듈은 picasso `8ec3534`(PR #100)에 고정되었고 설계 문서는 picasso-ops 저장소의 `docs/superpowers/specs/2026-10-14-ha-authority-design.md` §8 단계 0이다. 실행 호스트는 `JudgmentRecordWire`로 부호화된 `Middleware.judgmentRecord`의 전문을 `wire_version` "1"과 함께 `mission.execution_judgment_record` 테이블(마이그레이션 V6)에 추가 적재한다. 이 테이블은 추가 전용으로 트리거가 UPDATE, DELETE, TRUNCATE를 차단하며 수신된 전문을 있는 그대로 저장한다. 기록 시점은 접수 직후, 매 `pump` 뒤 작업 응답 전송 기록과 같은 트랜잭션, 운영자 판단 직후(`resolve`가 `Resolved` 반환)의 셋이며 같은 전문을 거듭 쓰지 않는다. 재기동 시에는 해당 작업 지시의 최신 기록을 `Middleware.resume(order, robotId, mission, record)`에 넘긴다. 기록을 읽을 수 없으면 `resume`을 부르지 않고 기록 행 번호와 함께 `GAVE_UP`을 보고하며, picasso가 `RESUME_RECORD_MISMATCH`로 거부해도 기록 행 번호와 함께 `GAVE_UP`을 보고한다. 기록이 존재하나 쓸 수 없을 때는 기록 없이 다시 짓는 일이 없다. V6 이전에 수신된 저널 행은 기록이 없어 이전 동작을 받는다. picasso-ops 쪽 근거는 `JudgmentRecordHostTest`의 8개 시험과 `RestartRecoveryTest`의 1, 4, 5단계이다.

    소비자 쪽에 남은 사항은 레지스터 행으로 올리지 않고 여기에만 적는다. 호스트는 결함 감수(`release`)나 조치 승인(`approveRemedy`)를 호출하지 않으므로 이 호스트가 짓는 실행에서는 기록의 해당 칸들이 늘 비어 있으며, 기록에 실려 오면 그대로 넘긴다. 영속은 최선형(best effort)이어서 쓰기가 실패해도 호스트는 경고 로그만 남기고 다음 성공한 `pump`에서 같은 전문을 다시 쓰며, 그전에 프로세스가 종료되면 그사이에 내려진 판단은 넘어오지 않는다. 둘 다 picasso가 새로 지을 것이 없어 레지스터 행으로 삼지 않는다. `judgmentRecord`와 `resume`은 이미 해당 칸들을 나르고 있고, 영속 저장소와 그 가용성은 담는 쪽의 전제(ADR 51 «미룬 것»)이기 때문이다. 소비자 대기 행은 소비자가 요구할 때 picasso가 지어 줄 대상이 있어야 성립한다.

    한계 레지스터에서는 «§15.221 · 판단 기록 영속» 행이 해소 조건을 채워 닫혔고(소비자 대기 21→20, 전체 88→87), 항목 221에 «(닫힘, §15.223)» 문단을 더했다. «§15.221 · 기록이 들지 않는 것» 행은 열린 채 둔다. «§15.222 · 다시 연 인시던트 재발 셈» 행은 «근거 윈도우의 `RESUME_HOLD_REOPENED` 자취 줄로 가를 수 있다»는 문장을, 존재만이 아니라 번들의 `at`과 시각이 일치하는 줄로 구분한다는 내용으로 바로잡았으며 해소 조건을 유지한 채 소비자 대기로 남는다. «§15.219 · 허가 막음 내보내기» 행은 picasso-ops PR #35(머지 커밋 `dd48061`)가 프로세스 안에서 `Execution.permitBlock`을 읽어 호스트 실행 목록과 화면에 표시하나 작업 응답과 내보내기 버전 6에는 여전히 실리지 않고 상류의 요구도 없다는 사실을 설명에 더했으며, 해소 조건에서 «picasso-ops 화면이나»를 빼 상류 요구로 좁혔다. 이 행은 열린 채 유지된다.

    문서에서는 `docs/contract.md` §7.3(다시 연 인시던트의 `at`이 줄 시각과 같다는 명시와 새 시험 둘, «기록 없이 다시 지을 때»의 맺음 문장)과 §7.4 6행 및 종결 목록을 손질했다. `docs/orchestration.md` §9의 소비자 문장에는 picasso-ops가 연계되었음을 적었다. ADR 51에서는 «대가»의 첫 글머리와 관계 목록의 마지막 글머리를 고쳤으며, A≠B는 여전히 열지 않고 그 사유를 «영속 없음»에서 «결정 4의 규약(세대 대조, 옛 경로 차단) 부재»로 옮겼다. `docs/limits.md`, `README.md`의 집계, `CLAUDE.md`와 `docs/verification.md`의 시험 수를 갱신했다. 스탬프를 다시 찍었고 오픈 항목(열림) 수는 149개에서 146개로 줄었으며 한계 문서의 변경 이력 번호는 222에서 223으로 올렸다.

    계약 proto, 계약 SemVer 0.9.0, 내보내기 버전 6, 인계 번들, `JudgmentRecordWire` 버전 1, 인시던트 번들의 칸은 불변이다. 시험은 `JudgmentRecordTest`에 둘을 추가해 전체 시험 수가 2,106개에서 2,108개로 늘었다. 추가된 시험은 `시계가 읽을 때마다 흘러도 다시 연 인시던트마다 at 이 그 단위의 다시 연 줄 시각과 같다`, `다시 연 뒤 근거 윈도우 앞 폭 안에 새로 선 보류는 그 줄을 싣지만 at 이 줄 시각과 달라 다시 연 것으로 읽히지 않는다`이다. 시험 픽스처는 읽을 때마다 흐르는 시계를 갖추었으며(기본 간격 0으로 기존 시험은 고정 시계를 유지), 기존 시험 메서드는 변경하지 않았다. 결함 주입 6건을 전부 탐지했다.

222. **판단 기록의 운영자 보류 판정으로 다시 지을 때 인시던트를 새로 열어 화면 판단 연계를 복구하도록 변경했다.**

    §15.221이 판단 기록을 도입해 `OPERATOR_HOLD` 판정을 옮겼으나 인시던트는 기록에 들지 않아 재기동 뒤 새 인스턴스에 열린 인시던트가 없었다. 담는 쪽(picasso-ops HA 단계 0 통합)의 판단 화면은 열린 인시던트에서 판단을 받으므로 그 보류를 화면에서 풀 수 없었고, 인스턴스 id를 든 판단 REST로만 풀렸다(§15.221 이전에는 다시 지은 단위가 다시 기다려 보류와 인시던트를 다시 열었다). §15.221의 «넘어오지 않는다» 문장은 지우지 않고 이 항목이 정정한다. 이전 인스턴스의 번들 자체(그때의 근거 윈도우·결함 원문·검토·판단)는 여전히 넘어오지 않으나(§15.166: 인시던트 적재는 메모리), `Middleware.resume`의 여덟째 단계에서 기록의 판정이 `OPERATOR_HOLD`(운영자 보류)인 단위마다 이 인스턴스에서 인시던트를 새로 연다. 봉인은 `resume` 끝에서 한 번 하므로 첫 `pump` 전에도 조회된다.

    다시 연 인시던트는 실행 자취 종류 `RESUME_HOLD_REOPENED`로 표시되며, 이 줄이 해당 인시던트의 근거 윈도우(`evidenceWindow`)에 실린다. 세부에는 단위 id, 기체, 시도, 기록의 실패 분류, 요청 시각(`requestedAt`), 근거 시각(`evidenceAt`), 보류 사유(`note`)가 들고 인시던트 번들에 새 칸은 없다. 결함 원문(`fault`)은 널이고 파지 관측과 막는 결함은 빈 값이며, 실패 분류·도달 근거 등급·설비 대조 결과는 기록에서 옮긴 단위 값으로 채워 지어내지 않는다. 보류가 처음 선 시각은 판단 기록에 없어 다시 연 인시던트 번들의 `at`·`wallClockAt`은 다시 연 시각이며, 요청 시각과 근거 시각은 `RESUME_HOLD_REOPENED` 자취 줄에만 둔 채 `JudgmentRecordWire`(버전 1)를 올리지 않았다. 판단은 보통의 보류와 같아 `resolve`가 보류를 풀고(확인이면 `DONE`, 재작업이면 새 시도), 판단(`resolution`)이 다시 연 인시던트에 붙고 판단 기록의 `decision`에 실린다. 다른 판정(`DONE`·`UNVERIFIED`·`FAILED`·`ABORTED`)으로 옮긴 단위, 스냅숏이 더 큰 시도를 보여 판정을 옮기지 않은 단위(자취 `RESUME_RECORD_SUPERSEDED`), 같은 작업 지시로 `resume`을 다시 부른 경우(리비전 경로, 같은 버전이면 `Idempotent`)에는 인시던트를 열지 않으며 `pump`도 다시 열지 않는다.

    계약 proto, 계약 SemVer 0.9.0, 내보내기 버전 6, 인계 번들, `JudgmentRecordWire` 버전 1, 인시던트 번들의 칸은 불변이다. 시험은 `JudgmentRecordTest`에 넷을 추가해 전체 시험 수가 2,102개에서 2,106개로 늘었다. 추가된 시험은 `기록의 운영자 보류로 다시 지은 단위는 다시 열었다고 적은 인시던트 하나를 이 인스턴스에 연다`, `다시 연 인시던트에 낸 판단이 보류를 풀고 그 인시던트와 판단 기록에 실린다`, `운영자 보류가 아닌 판정으로 다시 지은 단위는 인시던트를 열지 않는다`, `이미 다시 지은 실행에 resume 을 다시 불러도 인시던트를 겹쳐 열지 않는다`이다. 또한 기존 `기록의 시도가 스냅숏보다 앞서면 그 단위의 판정을 옮기지 않는다`에 «인시던트를 열지 않는다» 단언을 더했고, 결함 주입 10건을 전부 탐지했다. 한계 레지스터에서는 기존 «§15.221 · 기록이 들지 않는 것» 행에 다시 여는 사실과 시각 한계를 적었고, 재발 셈을 다룬 «§15.222 · 다시 연 인시던트 재발 셈» 행을 소비자 대기로 열었다.

    (의도적 제외로 옮김, §15.265) «§15.222 · 다시 연 인시던트 재발 셈» 행은 picasso 와 picasso-ops 어디에도 그 값을 쓰는 면이 없어 의도적 제외의 새 표 «수요 없음» 으로 옮겼다.

221. **단위별 기체 묶음과 재기동용 판단 기록을 추가해 다중 기체 준비와 다시 짓기 판단 유지를 지원하도록 변경했다.**

    ADR 51 단계 0의 최소 자리 둘을 마련했다. 첫째로 단위마다 기체 묶음인 `ExecutionUnit.robotBinding`(내부 속성이며 기본 널이면 실행의 기체)과 단일 읽기 자리 `Execution.robotOf`를 추가했다. 발행(`startUnit`, 개정 갱신, 미확정 조회의 `StartTask`), 관측(`WatchTask`), 취소(공개 취소, 미확정 뒤 취소), 발행 관문 입력, 결함 관문의 기체 관측, 동기화 대상(`Execution.robots`), 단절 판정, 작업 응답의 연결 상태, 선택 파라미터의 기체 선언, 배정 부담 셈, 배정 관문의 파지 관측(`liveHold`)·기체마다의 사슬 검사·작업 구역(`ZoneUse.robotId`), 인시던트 번들의 `robotId`·`profileRevision`, `resume`의 스냅숏(묶인 기체마다)과 재작업 횟수가 모두 이 자리를 거치도록 변경했다. 제안 열쇠가 작업 지시를 받는 기체이므로 다른 기체 사슬에서 찾은 조치 시퀀스는 싣지 않는다. 묶음은 데이터 클래스 본문 속성이라 같음 비교와 복사에 들지 않고, 재할당하면 묶지 않은 단위가 따라간다. ADR 9에 따라 다른 기체를 묶는 공개 표면은 없으며 오늘 거동은 바뀌지 않았다.

    둘째로 판단 기록 `JudgmentRecord`와 인코딩 `JudgmentRecordWire` 버전 1을 추가하고, 이를 꺼내는 `Middleware.judgmentRecord(executionId)`와 받는 `Middleware.resume(order, robotId, mission, record)`를 구현했다. 판단 기록에는 운영자 판단, 감수한 결함, 승인 소모(걸음과 값 포함), 근거 윈도우 요청 시각과 설비 대기 기한 시작(`requestedAt` 한 칸), 검증 대기 창(`verifyWindow`), 이전 판정(`verdict`), 단위마다 시도 번호와 태스크 id를 싣는다. 다시 짓기는 다시 판정하지 않고 판정이 있는 단위는 다시 돌리지 않으며, 앞선 로봇 단위도 기록의 근거 등급을 쓴다. 기한과 관측 창은 처음 시작한 시각부터 이어 간다. 작업 지시 id·버전이 다르거나 계획에 없는 단위가 있으면 `RESUME_RECORD_MISMATCH`로 거부하고 실행을 만들지 않는다. 스냅숏보다 앞선 시도의 기록은 그 단위의 시각과 판정을 옮기지 않고 자취 `RESUME_RECORD_SUPERSEDED`를 남긴다. 기록을 넘기면 플릿 단위의 재작업 횟수도 기록의 `attempt`로 선다. 읽기는 모르는 버전·칸·값, 빠진 칸, 정착하지 않은 판정 상태, 정수가 아닌 수를 전부 거부한다. `RemedyDesk.settle`이 소모 기록을 돌려주고 `RemedyDesk.restore`가 다시 세우며(서 있던 제안은 지우고 승인 횟수는 세지 않는다), 조치 시퀀스의 단위는 `remedyUnit` 한 벌로 승인과 다시 짓기가 같이 쓴다.

    계약 proto, 계약 SemVer 0.9.0, 내보내기 버전 6, 인계 번들은 바뀌지 않았으며 판단 기록에 세대 칸은 두지 않았다. 시험 수는 `UnitRobotBindingTest` 16개, `JudgmentRecordTest` 12개, `JudgmentRecordWireTest` 4개를 더해 2,070개에서 2,102개로 32개 늘어났고 기존 시험은 고치지 않았다. 묶음 시험은 단위를 다른 기체에 묶어 실행의 기체를 읽는 자리가 남았는지 가르며(기본값만으로는 두 값이 같아 가르지 못함), 판단 기록 시험은 기체 시험 더블 위에서 미들웨어만 재기동하고 승인 소모 시험 하나만 에뮬레이터 위에서 돌렸다.

    한계 레지스터에서는 내보내고 다시 들이는 입구가 서 해소 조건을 만족한 «§15.213 · 재기동 뒤 다시 짓기» 행을 닫았고, 새 행 둘을 열었다. 첫 소비자 picasso-ops가 아직 기록을 영속하지 않아 기록 없는 `resume`은 옛 거동 그대로임을 기술하는 `§15.221 · 판단 기록 영속`(소비자 대기)과, 인시던트·조치 탐색·승인 횟수·작업 응답 아웃박스와 관측값은 넘어오지 않고 `GetSnapshot`이 종료 태스크를 싣는다고 정하지 않는 문제는 기록에 판정이 없는 단위로 좁혀짐을 기술하는 `§15.221 · 기록이 들지 않는 것`(내부)을 추가했다.

    **(닫힘, §15.223)** 이 항목이 남긴 한계 행 가운데 «§15.221 · 판단 기록 영속»은 §15.223에서 닫혔고, «§15.221 · 기록이 들지 않는 것»은 열려 있다. picasso-ops PR #38(머지 커밋 `8e53e4e`)에서 판단 기록(`JudgmentRecordWire`로 부호화한 `Middleware.judgmentRecord`)을 영속하며, 재기동할 때 작업 지시의 최신 기록을 `resume`에 넘긴다. 기록 없이 부르면 위의 거동 그대로다.

220. **어댑터 호스트에 단일 재진입 잠금을 도입하여 동시 RPC와 펌프 스레드 간 상태 경합을 직렬화하고 같은 기체에 태스크가 중복 접수되는 결함을 수정했다.**

    `adapter-host`의 `HostedRobot` 상태 기록(태스크 장부 `book`, 현재 태스크 `current`, 재생 버퍼 `buffer`, 결함 표 `faults`)과 `HostTaskService.watchers` 청취자 레지스터에 잠금이 없었다. `AdapterHost`가 서비스 셋을 그대로 `ServerBuilder`에 등록한 구조로 인해, 실 포트(`ServerBuilder.forPort`, 기본 실행기) 환경에서 각 gRPC 스레드로 유입되는 RPC와 `OrbitLauncher`의 데몬 스레드 `orbit-pump`가 `pumpMillis` 주기로 호출하는 `AdapterHost.pump()`가 동일한 기록을 동시에 수정했다. main 브랜치(`4d6d768`)에서 5회 반복 검증한 결과 3개 시험 모두 실패했다(15/15 실패). 동시에 들어온 `StartTask` 8개가 배타 검사인 `current`를 모두 통과하여 거부되지 않고 전부 핸들을 발급받아 어댑터 `accept`가 8회 불렸고, 1ms 펌프 주기의 Orbit 런처 배치에서는 동시에 들어온 4개의 `StartTask`가 모두 수락되어 플릿 스텁에 동일 기체 미션 중복 파견(`POST /calendar/mission/dispatch/spot-a`)이 나갔다. 또한 펌프 스레드와 4개 RPC 스레드가 1.5초간 겹쳤을 때 재생 버퍼 및 장부 순회 중 변경으로 인한 `java.util.ConcurrentModificationException`이 발생하며 18~47건의 `UNKNOWN: Application error processing RPC` 오류가 발생했다.

    이를 수정하기 위해 `AdapterHost`에 단일 재진입 잠금(`synchronized`)을 두고, 서비스 셋을 `ServerInterceptors.intercept(…, Serialized(lock))`로 감싸 호출 시작과 모든 콜백이 잠금 아래에서 실행되도록 변경했다. `pump()` 호출과 기동(`start()`) 시의 `ONLINE` 발행, 종료(`shutdown()`) 시의 `OFFLINE` 발행도 동일한 잠금 아래에 배치했으며, 외부에서 기록을 다룰 수 있도록 `exclusive {}` 블록을 열어 `MimicServer`와 동일한 형태를 갖췄다. 플릿 뒤 기체들이 남쪽 링크 하나를 공유하므로(§15.106) 기체 단위로 잠그면 링크 동시 호출이 다시 발생하므로 호스트 단위 잠금을 적용했다. 5개 런처 위치를 점검한 결과 운영 런처는 Orbit 하나뿐이며, Spot·Digit·G1은 `src/main`에 런처(`cli`), `main`, `ServerBuilder`가 없고 Spot은 시험에서만 in-process `directExecutor`로 `adapter-host`를 사용하며 Digit·G1은 의존성이 없고 하네스 또한 in-process `directExecutor`를 사용함을 확인했다. 따라서 `adapter-host`에 잠금을 두어 향후 추가될 런처도 동시성 제어를 받도록 했다. 이 잠금은 향후 ADR 51 결정 5에 따른 발신자 세대 버전 대조와 벤더 송신이 한 덩어리로 실행되는 기반이 된다. 미믹과 `adapter-host`의 `Serialized` 인터셉터는 모듈 간 의존성이 없어 두 벌로 유지하며 공용 모듈화 여부는 결정을 보류했다.

    회귀 방지를 위해 `AdapterHostConcurrencyTest`(동시에 들어온 `StartTask` 단일 수락 및 남쪽 1회 지시 검증, 펌프 스레드와 RPC 경합 시 발행 순번 연속 증가 및 결함 기록 검증) 2건과 `OrbitLauncherTest`(펌프 스레드 배치 환경의 동시 `StartTask` 단일 수락 및 플릿 1회 파견 검증, 런처 펌프 스레드의 호스트 잠금 통과 검증) 2건을 추가했다. 결함 주입 10건 중 7건은 지정된 시험에서 정상 검출되었고, 기동 시 `ONLINE` 발행과 종료 시 `OFFLINE` 발행의 잠금 제거 2건은 해당 구간의 동시 트래픽 부재로 현재 시험에서 미검출로 남겼으며, 호출 시작(`startCall`)의 잠금 제거 1건은 단항 및 서버 스트림 본문이 `onHalfClose`에서 실행되어 검출되지 않는 등가 변이임을 확인했다. 전체 시험 수는 2,066개에서 2,070개로 갱신했다. 아울러 호스트 잠금 아래 남쪽 동기 호출로 인한 대기 지연 문제와 코드 읽기로 확인된 취소된 `WatchTask` 스트림 예외 전파 문제를 `docs/limits.md`의 내부 오픈 항목으로 등록했다.

    **(닫힘, §15.228)** 이 항목이 남긴 한계 행 가운데 «§15.220 · 취소된 스트림에 밀기»는 §15.228에서 닫혔고, «§15.220 · 호스트 잠금 아래 남쪽 호출»은 열려 있다. 항목 220 본문은 이 문제를 «코드 읽기로 확인된» 것으로 등록했으나, §15.228에서 취소된 스트림 뒤의 `GetSnapshot`이 `CANCELLED: call already cancelled`로 실패하는 시험으로 재현했다. 이제 `HostTaskService.watchTask`가 취소 처리기(`setOnCancelHandler`)를 달아 취소된 스트림 하나만 `watchers` 표에서 빼고, `push`는 밀기 전에 취소된 관측자를 걸러 낸다. 처리기는 `Serialized` 인터셉터로 호스트 잠금 아래에서 돈다.

    **(좁힘, §15.261)** 행 «§15.220 · 호스트 잠금 아래 남쪽 호출»에서 적재(태스크 관측·생존 보고)는 큐로 옮겨 호스트 잠금 아래에서 레지스트리를 기다리지 않게 되었고, 남쪽 호출(`OrbitHttpLink`의 플릿 HTTP)과 `Negotiate` 핸드셰이크 보고는 여전히 잠금 아래에 있다.

    (좁힘, §15.266) `Negotiate` 핸드셰이크 보고를 기존 적재 큐 `QueuedIngest`에 실어 호스트 잠금 아래에서 레지스트리를 기다리지 않게 되었다. 사우스바운드 호출은 여전히 잠금 아래에 남아 있으며 오픈 항목 수는 그대로(61개)이다.

    (좁힘, §15.268) 펌프의 사우스바운드 읽기를 호스트 잠금 밖으로 분리하고 읽기 RPC가 펌프를 건너뛰는 `pumpOnRead` 선택을 도입했으나, 명령 및 사이트 이름 조회가 호스트 잠금 아래 사우스바운드 호출로 남았다. 명령 처리 중 다른 RPC가 대기하는 문제와 명령의 예약·송신·확정 분리 및 ADR 51 결정 5의 세대 버전 대조는 오픈 항목으로 남았다.

    (좁힘, §15.269) 명령 RPC와 `GetKnownSiteNames`의 본문을 호스트 잠금 밖으로 옮기고 명령을 예약·송신·확정으로 나누어 송신 중 링크를 안 쓰는 RPC의 대기를 없앴다. 상태 발행 때 생존 보고의 사이트 이름 조회와 ADR 51 결정 5의 세대 버전 대조 등은 오픈 항목으로 남아 해당 레지스터 행을 닫지 않고 범위를 좁혔다.

    (좁힘, §15.271) 상태 발행과 연결 보고에 싣는 생존 보고의 로봇 소프트웨어 및 사이트 이름 요약 조회는 펌프의 `read` 단계(링크 잠금만 쥠)와 포트 개방 전 기동 단계(`primeLiveness`)로 옮겨 호스트 잠금 아래의 사우스바운드 왕복에서 덜어냈다. 보냈는지 모르는 실패 구분, ADR 51 결정 5의 세대 버전 대조, 같은 링크를 쓰는 호출 사이의 대기 한도 확인 등 세 가지는 남는다.

    (좁힘, §15.272) 사우스바운드 호출이 나갔는지 모르는 실패를 `LINK_IN_DOUBT`로 구분해, Orbit 파견 요청에서 연결을 못 맺은 실패만 `Unreachable`로 두고 응답 시간 초과나 받는 도중 끊김은 `InDoubt`로 내어 호스트가 `inDoubt`에 남기고 gRPC `UNKNOWN`으로 답하며 같은 접수가 다시 와도 다시 보내지 않도록 바꿨다. 다만 Spot과 Digit의 명령 송신 및 조작은 여전히 링크 오류를 보내지 못한 것으로 읽고, 계약에 칸이 없어 ADR 51 결정 5의 세대 버전 대조가 남으며, 같은 링크를 쓰는 호출의 대기 한도는 미확인이다.

    (좁힘, §15.273) 이 항목이 남긴 송신 결과 미상 가운데 Spot, Digit, G1의 쓰기 호출도 `SouthboundInDoubt`와 `sendFailure`로 `LINK_IN_DOUBT`를 내게 했다. 남는 것은 실 링크 구현이 저장소에 없어 그 표식을 내는지 시험으로 확인되지 않는 점, ADR 51 결정 5의 세대 버전 대조, 같은 링크를 쓰는 호출의 대기 한도다.

219. **작업 허가 포트와 발행 관문을 추가해 새 로봇 명령 발행을 허가 상태에 따라 제어하도록 변경했다.**

    안전 계통의 상태를 기체 집합별 정보 투영으로 읽어 새 로봇 명령의 발행 여부를 판정할 필요가 있었다. 안전 기능 자체는 계약 아래 안전 계통이 담당하므로 picasso가 이를 대체하지 않으며, 작업 허가는 이미 나간 명령을 세우지 않는 정보 투영으로서 ADR 51 결정 6에서 규정한 발행 관문의 둘째 입력으로 도입되었다.

    계약 proto 변경 없이 계약 밖 포트인 `WorkPermitPort`를 추가하고, 유효 권위와 작업 허가를 검사하는 단일 `IssuingGate`를 구현해 로봇 경로의 `startUnit`, `revise`, `resolveDoubt` 세 자리에서 새 로봇 명령 발행을 검사하도록 했다. 작업 허가가 없거나 `UNKNOWN`이면 새 명령을 내지 않고 단위를 `PENDING`으로 유지하며, `Execution.permitBlock`과 `Execution.deferredUpdate`로 막힘과 지연 갱신을 보관하고 실행 자취에 `ISSUE_WITHHELD`와 `ISSUE_RESUMED`를 기록하도록 했다. 허가가 막는 동안 라운드마다 다시 불리는 결함 관문의 못 물음 메모는 한 번만 적도록 변경했다. 반면 계약 0.9.0, 내보내기 버전 6, 인계 번들, 작업 응답 형식은 그대로 유지했다.

    시험 수는 `WorkPermitTest` 19개와 gate 검증 1개를 더해 2,046개에서 2,066개로 늘어났으며, 결함 주입 21건을 전부 지정한 시험 이름으로 검출했다. 기존 picasso 시험 411개도 변경 없이 통과했다. limits 문서에는 막힌 미확정 취소 지연, 허가 막음 외부 미노출, 플릿 경로 관문 미적용, 다시 짓기 재부착 차단과 관련된 한계 행 네 건을 추가했다.

    (닫힘, §15.244) 이 항목이 연 행 «§15.219 · 막힌 미확정 취소»는 키로 지은 핸들로 작업 허가 없이 취소를 보내도록 하여 §15.244에서 닫혔다.

    (닫힘, §15.252) 이 항목이 연 행 «§15.219 · 리빌드 재부착»은 리빌드가 스냅숏의 태스크 존재로 재부착을 새 발행과 가르고 `StartTask` 없이 태스크 id와 리비전으로 핸들을 지어 붙도록 하여 §15.252에서 닫혔다.

    (의도적 제외로 옮김, §15.265) «§15.219 · 허가 차단 내보내기» 행은 picasso 와 picasso-ops 어디에도 그 값을 쓰는 면이 없어 의도적 제외의 새 표 «수요 없음» 으로 옮겼다.

218. **임무 정의에 출발 관측 선언을 추가하여 신원 미확인 자재의 출발을 차단하고 운영 모호성을 제거했다.**

    재할당과 안전 상태 연계 검토(그룹 2 B 단계)에서 소유자 기준 «운영 모호성 제거»에 따라 출발 관측 방식을 결정했다. 설비 신호가 없는 셀에서 라인이 멈추는 문제를 방지하기 위해 설비 침묵 시 그대로 통과시키는 기존 동작(`OPTIONAL`)을 기본값으로 유지하되, 신원 확인이 필요한 임무는 암묵에 의존하지 않고 명시적으로 선언하도록 했다. `REQUIRED` 선언 시 설비가 침묵하거나 신원 없이 재하만 보고하면 관문이 출발을 차단하고 «출발 미관측»(`SourceUnobserved`)을 반환한다. 미관측은 출발 결품과 달리 대체 자리를 계산하지 않으며 작업 지시 수정이 아닌 사람의 확인을 요구한다. 상위 시스템 확인, 인계 시점 점검, 수동 자재 확인 등의 근거는 우회 통로가 아니라 셀 포트의 신원 관측으로 수신되어 재시도 시 통과하도록 했다.

    임무 정의 단위 노드에 선택 키 `sourceObservation`(`REQUIRED`·`OPTIONAL`)을 추가하고 파서가 유효값을 검증하도록 했다. 단위 노드가 `source`와 `expectedIdentity`를 모두 포함하면서 선언이 없으면 검증기가 `SOURCE_OBSERVATION_UNDECLARED`(`ENGINEER` 담당)로 활성화를 막으며, `DefinedCapability` 생성 시에도 동일하게 차단하여 검사 6개와 거부 종류 10개를 확립했다. 선언은 `ExecutionUnit.sourceObservation`(기본 `OPTIONAL`)에 실리며, 코드 케이퍼빌리티와 데이터판 픽스처는 `OPTIONAL`로 일치시켰다. 출발 미관측은 `Submission.Rejected.sourceUnobserved`와 `Unassigned.sourceUnobserved`로 표출되나, 조치 탐색 레지스터(`RemedyOutcome`)에는 추가하지 않아 내보내기 버전 6을 유지했다. 계약 proto, 계약 버전 0.9.0, 인계 번들, 임무 정의 `schemaVersion` 1은 변경하지 않았으며, 이미 활성화된 임무 버전은 그대로 동작하되 비활성 정의는 다음 활성화 시 선언을 요구한다.

    시험은 총 15개를 추가하여 2,031개에서 2,046개로 증가했다(`OccupancyTest` 2개, 신규 `SourceObservationTest` 7개, `MissionValidatorTest` 3개, `MissionDefinitionParserTest` 1개, `DefinedCapabilityEquivalenceTest` 1개, `InMemoryMissionCatalogTest` 1개). 결함 주입 17건을 모두 지정된 시험 이름으로 검출함을 확인했다. 한계 레지스터에는 조치 탐색 레지스터 및 내보내기 누락을 다루는 소비자 대기 항목(§15.218)과, 신원 대조 없는 노드의 선언 미거부를 다루는 내부 항목(§15.218)의 두 행을 추가했다.

    **(닫힘, §15.227)** 이 항목이 남긴 한계 행 가운데 «§15.218 · 쓰이지 않는 선언»은 §15.227에서 닫혔고, «§15.218 · 미관측 레지스터»는 열려 있다. 이제 검증기가 출발 자리(`source`)나 기대 신원(`expectedIdentity`)이 없는 단위 노드의 `sourceObservation` 선언을 `SOURCE_OBSERVATION_UNUSED`(`ENGINEER` 담당)로 거부한다. 그래서 위 본문의 «거부 종류 10개»는 11개가 됐고, 검사 수는 6개 그대로다. 해석기 `DefinedCapability` 생성 때는 이 선언을 막지 않는데, 이미 활성화된 버전을 다시 지을 때 넘어지지 않게 하려는 것이다.

    (의도적 제외로 옮김, §15.265) «§15.218 · 미관측 레지스터» 행은 picasso 와 picasso-ops 어디에도 그 값을 쓰는 면이 없어 의도적 제외의 새 표 «수요 없음» 으로 옮겼다.

217. **`Unassigned` 에 출발 결품 정보를 추가해 대체 자리 구조를 보존하고 관문 순수성을 유지했다.**

    미들웨어 `adopt` 는 순위 목록의 후보마다 배정 관문(`admits`)을 확인해 첫 통과를 채택하고 전부 떨어지면 `Unassigned` 를 낸다. 배정 관문은 출발 자리가 비었을 때 이미 `RemedyOutcome.SourceMissing` 을 계산해 `Admission.Refused.sourceMissing` 에 실었으나, `adopt` 는 사유 문장만 남기고 이 값을 버려 `Unassigned` 에는 `refusals` 만 남았다. 이로 인해 picasso-ops 실행 호스트는 `assign` 과 `adopt` 로 배정하면서 출발 결품을 REJECTED 가 아닌 UNASSIGNED 로 받고, 대체 자리를 사유 문장에서만 확인하며 구조화된 값으로 받지 못했다.

    `Unassigned` 에 `sourceMissing: Map<String, RemedyOutcome.SourceMissing>`(기본값 빈 맵)을 추가해, `adopt` 가 출발 결품으로 거부된 후보마다 관문이 계산한 답을 그대로 담도록 했다. 다른 사유로 거부된 후보는 이 맵에 담지 않으며, 후보 조회가 누적되어 결정으로 굳어지는 것을 방지하고자 조치 탐색 대장(`remedySearches`)에는 기록하지 않아 관문의 순수성(§15.161)을 지켰다. 계약 proto, 계약 버전 0.9.0, 내보내기 버전 6, 인계 번들과 `submit` 경로 및 대장 동작은 바꾸지 않았다. 한계 레지스터 §15.164에는 출발 결품의 답이 남게 된 점과 능력 사슬의 조치 탐색 결과가 여전히 남지 않는 점을 반영하고 해소 조건은 유지했다.

    `OccupancyTest` 에 미배정 시 후보마다 출발 결품 답이 구조로 남고 대장이 비어 있음을 확인하는 시험과 출발 결품이 아닌 거부는 결품 답에 포함되지 않음을 확인하는 시험 둘을 추가했다. 구조 누락, 모든 거부에 빈 답 채움, 대체 자리를 널로 변경, 대장 기록 등 네 가지 결함 주입을 새 시험이 모두 이름으로 검출했다. 전체 시험 수는 2,029개에서 2,031개로 늘었다. `CLAUDE.md`와 `docs/verification.md`의 시험 수를 갱신하고 스탬프를 다시 찍었다.

216. **세 권위 경계를 채택하며 분산 배포는 조건부로 열어 두는 ADR 51을 확정했다.**

    2026-10-13 소유자가 HA 다중 인스턴스 운영 가능성을 물었다. 로봇과 설비가 늘면 단일 프로세스는 부하가 크고 단일 실패 지점이 되므로, 당장 바꿀 것은 아니지만 점진적 변화 가능성을 품는 구조를 원해 검토 뒤 같은 날 소유자가 결정했다. 바로 앞 PR(#92)이 docs/contract.md §7에 재기동(같은 소유자)과 다른 프로세스가 넘겨받는 소유 이전의 전제를 적었고, ADR 51은 그것을 사실 원본으로 가리킨다.

    권위를 임무 실행(키: 작업 지시 id, 실행자 하나), 로봇 제어(키: robot_id 또는 겹치지 않는 플릿 제어 도메인, 제어자 하나), 공유 자원(키: 자원 id 또는 묶음, 조정자가 예약) 셋으로 나눈다. 리스의 뜻을 프로세스 권위 리스, 진입 허가, 물리 점유 셋으로 가르고 물리 점유에는 만료를 두지 않으며, 실행자 세대, 제어자 세대, 예약 버전, 태스크 리비전 넷 값을 가르고 결정 행에는 세대를 두지 않는다. 지금은 역할마다 활성 프로세스 하나를 두는 단일 활성을 전제하고, 소유 이전은 권위 획득, 옛 발행 경로 차단, 재대조, 발행 재개 순서이며 획득은 곧바로 발행이 아니라 재대조 상태다. 옛 경로 차단은 받는 쪽(adapter-host)이 세대로 대조하고, 미들웨어의 새 발행 관문은 하나(startUnit, revise)이며 입력이 유효 권위와 작업 허가 둘이다. 자동 제어권 이양은 벤더가 옛 경로를 끊는 방식(거부, 리셋, 없음)으로 정하고 강제가 없는 기종은 기본 금지하며 옛 인스턴스의 자기 차단 기록이나 독립 확인이 있을 때만 넘긴다.

    코드는 바꾸지 않았다. 시험 2,029 개 그대로 새 시험 없이 이름 바꾸기 하나만 반영했다. gate의 CompletionCriterionTest 시험 이름을 주장의 자리가 예순여섯이다에서 주장의 자리가 예순일곱이다로 바꾸어 수를 66에서 67로 올렸다. docs/adr/0051-three-authority-boundaries.md를 더하고 docs/adr/README.md 색인 행 51, 설계 문서 §14 ADR 표 행 51, 설계 문서 변경 이력 §15.216을 반영했다. docs/limits.md의 변경 이력 번호를 215에서 216으로 올렸다. ADR 51 스탬프가 오픈 항목 §15.213(재기동 뒤 다시 짓기)과 §15.162를 들어 열림 수가 145에서 147로 올랐다. README.md의 ADR 범위를 29~50에서 29~51로 갱신했다. ADR 51, docs/adr/README.md, 설계 문서, docs/limits.md, README.md의 스탬프를 다시 찍었다.

215. **계약 문서에 재기동과 소유 이전의 전제를 명령별로 적고, 시험이 확인하는 범위와 빈자리를 나눴다.**

    소유자가 승인한 검토 범위는 «F1: 소유 이전 규약의 전제 검토»다. contract.md 에는 재기동·소유 이전·`IN_DOUBT`·취소 경계·완료 근거에 대한 문장이 없었고 §3 의 보장은 전부 한 프로세스 안의 시험이었다. 설계 검토가 질문 여섯(같은 키 중복, revision 변경, 순서 역전, 스트림 재구독, 취소와 완료의 경합, 재기동 뒤 이력 손실)과 넘겨받기 순서 넷(권한 획득, 옛 발행 경로 차단, 진행 중 명령과 물리 상태 대조, 발행 재개)을 물었다.

    contract.md 에 §7 을 더했다. 하위 절 넷은 명령별 전제 표, 질문 여섯 표, 같은 소유자 재기동, 다른 프로세스의 넘겨받기다. 표시는 «검증됨(같은 프로세스)», «검증됨(재기동, 같은 소유자)», «미검증» 셋이다. 시험 인용은 §3 과 같은 모양이라 `DocumentClaimsTest` 의 인용 대조를 받고, 새 표의 머리는 `| 담보 |` 로 시작하지 않아 §3 의 행 전수 규칙과 섞이지 않는다. 새 문서 파일로 두지 않은 것은 새 파일이 인용 대조를 못 받고 주장 문서 수를 박은 시험(66)의 수와 이름을 바꿔야 하기 때문이다.

    미들웨어·`adapter-host`·에뮬레이터의 상태는 전부 프로세스 메모리다. 같은 소유자 재기동 중 «미들웨어만 재기동, 하위는 살아 있음» 만 `ResumeTest` 가 확인하며 기체는 시험 더블이다. `adapter-host` 재시작은 장치도 시험도 없고 범위 밖으로 둔 채다. 처음 드러난 것 셋을 한계 레지스터 §2 에 행으로 남겼다(옛 커서, 같은 키 다른 스킬, 취소 보고 상수). 행을 두지 않고 기록만 한 것은 `adapter-host` 의 `attempt` 0 고정(재시도를 재정의한 어댑터가 없음)과 미들웨어가 `PauseTask`·`ResumeTask`·`RetryTask` 를 부르지 않음이다. 다른 프로세스의 넘겨받기 빈자리(권한 획득, 수신자 쪽 세대 대조, 보내기 전 의도 기록, 벤더 매핑 영속, 재시작 뒤 진행 중 벤더 태스크 들이기, 판단과 승인 소모 이관)는 보장이 아니라고만 적었다.

    코드는 바꾸지 않았다. 시험 2,029 개, 계약 버전 0.9.0, 내보내기 버전 6, 인계 번들은 그대로다. contract.md·limits.md·README.md 의 스탬프를 다시 찍었고 열림 수는 139 에서 145 이 됐다. 인용 37 건을 코드와 대조했고, 인용 하나를 일부러 틀리게 해 `DocumentClaimsTest` 가 그 시험 이름으로 실패하는 것을 확인했다.

    **(닫힘, §15.228)** 이 항목이 남긴 한계 행 가운데 «§15.215 · 같은 키 다른 스킬»은 §15.228에서 닫혔고, «§15.215 · 옛 커서»와 «§15.215 · 취소 보고 상수»는 열려 있다. 이제 에뮬레이터(`TaskHost.update`)도 `adapter-host`처럼 키를 먼저 본다. 같은 `(task_id, revision)`은 스킬이 달라도 기존 핸들(멱등)이고, 낮은 `revision`은 스킬이 달라도 `OUTDATED_REVISION`이며 스킬 비교는 높은 `revision`에서만 하고 `HostParityTest`가 두 발신자를 맞댄다. «옛 커서» 행은 미들웨어가 이제 스냅샷의 `session_id`를 들고(`RobotSnapshot.session`) 세션이 바뀌면 커서를 스냅샷 번호로 다시 세우도록 좁혀졌으나, `adapter-host`의 세션이 프로세스 카운터라 재시작마다 되풀이될 수 있는 것(§15.21)과 두 발신자가 옛 커서에 빈 응답을 주는 것은 남아 있다.

    **(닫힘, §15.258)** 이 항목이 남긴 한계 행 «§15.215 · 취소 보고 상수»는 취소 보고의 두 상수 칸 `accepted`·`motionStopped`를 걷어 §15.258에서 닫혔다. 이 항목이 남긴 행 가운데 «§15.215 · 옛 커서»만 열려 있었다.

    **(닫힘, §15.259)** 이 항목이 남긴 마지막 한계 행 «§15.215 · 옛 커서»는 `adapter-host`의 세션이 기동 시각을 들게 되어 §15.259에서 닫혔고, 남은 것(같은 밀리초 재기동·시계 되감기·가상 시계에서의 세션 되풀이, 옛 커서에 대한 두 발신자의 빈 응답)은 행 «§15.21»로 옮겼다.

214. **`PhysicalState` 에 추가 처리와 기체 점유 속성을 더하고, `reassign` 의 자기 배제와 재할당 제약을 정리했다.**

    `PhysicalState` 에 두 속성을 더했다. `pumps` 는 추가 처리 가능성(이 실행에 아직 발행·취소·개정이 올 수 있는가)이고 식은 `!isSettled || this == PARTIAL` 이다. `occupiesRobot` 는 기체 점유(이 실행이 기체를 쥐어 다른 주문이 못 오는가)이고 지금은 같은 식이다. 이름을 따로 둔 것은 `OPERATOR_HOLD` 처럼 기체를 쥐지만 발행하지 않는 상태가 생기면 둘이 갈라질 수 있기 때문이다. 미들웨어 안에서 같은 판정이 세 곳(`pump` 의 동기화 대상, `pumpRound`, `cancel`)에 인라인으로 적혀 있었고 이를 `pumps` 로 바꿨다. 거동은 같다. `isSettled` 는 `PARTIAL` 을 종착으로 센다. 담는 쪽이 `!isSettled` 로 «기체가 바쁘다» 를 판정하면, 미들웨어가 계속 처리하는 `PARTIAL` 실행이 남은 기체에 둘째 주문이 간다. 담는 쪽은 `occupiesRobot` 를 읽는다.

    `reassign` 은 옮길 실행 자신이 든 실행 표로 관문을 돌렸다. 그래서 자재를 나르는 실행(목적지 자리를 잡은 실행)은 자기 자리에, 작업 구역을 붙인 배치에서는 자기 구역에 걸려 모든 후보가 거부되어 `Kept` 였다. 이제 `admits` 에 옮길 실행 하나를 빼는 인자를 더했고, 실행 id 로 뺀다. 기체로 빼면 떠나는 기체의 다른 실행까지 빠지기 때문이다. `admits` 는 순수 술어로 남는다. 반쯤 한 물리 작업의 인계(물품·자리)는 재할당이 보장하지 않으므로 `reassign` 의 의미를 좁혔다. 끝난 단위(`DONE`)가 있거나, `Route.ROBOT` 단위 중 자재를 한 번이라도 쥔 단위(`everHeld`)가 있으면 `Kept` 다. 이것은 «지금 쥐고 있다» 를 판정하는 것이 아니라 «자재를 다룬 이력» 을 근거로 옮기지 않는 쪽으로 보수적으로 둔 것이다. 종착한 단위의 파지는 정보이지 판정이 아니라는 §15.146 의 결정과 미들웨어 관문의 파지 판정(`liveHold`)은 바꾸지 않았다. 후보의 실재·연결·스킬은 여전히 보지 않는다(권위는 발신자다). 운영 호출자는 아직 없다. 실행 수준 `OPERATOR_HOLD` 는 종착이 아니고 도는 단위도 없어 원래부터 재할당 대상이다. 쥐지 않고 실패한 단위나 하류가 끝났다는데 설비가 확인 못 한 단위가 있는 실행은 여전히 옮겨진다.

    시험은 재할당 6개(자리를 잡은 실행이 옮겨짐, 작업 구역에서 옮겨짐, 떠나는 기체의 다른 실행은 여전히 막음, 끝난 단위가 있으면 안 옮김, 든 채로 멈춘 실행은 안 옮김, 두 속성의 상태 집합)와 `PARTIAL` 실행 2개(취소를 받고 다음 펌프가 중단을 적음, 개정 버전을 기다리는 동안에도 기체의 능력 변경이 자취에 남음)를 더했다. 시험 수는 2,021 에서 2,029 다. 기존 시험의 단언은 고치지 않았다. 결함 주입에서는 새 거동마다 되돌리면 해당 시험이 이름으로 실패했다. 예외 하나로 `pumpRound` 의 `PARTIAL` 허용은 지금 코드에서 관측 차이가 없는 등가 변이다. `PARTIAL` 은 다음 단위가 없을 때만 만들어지고, 그 실행을 건드리는 길(개정·취소·단위 재시작)은 모두 상태를 먼저 바꾸기 때문이다.

    (의도적 제외로 옮김, §15.265) «§15.214 · 재할당 후보» 행은 picasso 와 picasso-ops 어디에도 그 값을 쓰는 면이 없어 의도적 제외의 새 표 «수요 없음» 으로 옮겼다.

213. **재기동한 담는 쪽이 자기 기록으로 실행을 다시 짓는 입구 `Middleware.resume` 을 더했다.**

    미들웨어는 실행, 작업 응답 아웃박스, 인시던트, 운영자 판단을 메모리에만 들고 저장 포트가 없다. 승인 소모 기록도 메모리에 있으며 그 영속은 담는 쪽의 몫이다(ADR 46). 담는 쪽이 재기동하면 새 인스턴스는 도는 실행을 모르고, 기체 쪽 태스크는 끝까지 가므로 판정이 고아 태스크가 도는 기체를 비었다고 본다. 담는 쪽이 받은 작업 지시를 `submit` 으로 다시 넣으면 세 가지가 어긋났다. 지금 활성 임무 버전으로 계획하고, 배정 관문이 이미 집어 간 자재를 출발 결품으로 읽어 거부할 수 있으며, 재작업 횟수가 0 에서 시작해 첫 pump 가 `@rN` 없는 옛 태스크 id 로 가서 끝난 첫 태스크에 붙고 도는 재작업 태스크는 고아가 된다. picasso-ops 의 S4b 설계 스펙(picasso-ops 저장소)의 기술 결정 T1 이 이 입구를 요구하며, 운영 관리 화면 설계 제안(`docs/superpowers/specs/2026-10-07-ops-console-lifecycle-design.md`) §10 입증 항목 5(재시작 뒤 중복 명령과 중복 작업 응답을 막는다)의 picasso 쪽이다. 소비자가 생겼으므로 지었다(ADR 9).

    `resume(order, robotId, mission: ActiveMission): Submission` 은 같은 작업 지시 id 의 실행이 있으면 `submit` 과 같이 `revise` 로 간다. 넘기는 작업 지시는 이전 인스턴스가 받은 마지막 버전이어야 한다. 태스크의 멱등은 같은 `task_id`·`revision` 에만 서기 때문이다. 같은 실행이 없으면 넘겨받은 정의의 WorkMaster 가 작업 지시와 같은지 먼저 보고, 기체 스냅숏을 한 번 읽는다. 못 읽으면 `RESUME_SNAPSHOT_UNREADABLE` 로 시작하는 사유로 거부하고 실행을 만들지 않는다. 읽었으면 넘겨받은 정의로 계획하고 근거 등급 검사만 거치며, 배정 관문을 걸지 않고 조치 탐색 기록을 남기지 않는다. 로봇 단위마다 스냅숏 태스크 id 가운데 `jobOrderId#unitId` 이거나 `jobOrderId#unitId@rN`(N 은 `startUnit` 이 붙이는 1 이상의 수, 앞자리 0 없음) 인 것의 가장 큰 N 을 재작업 횟수로 맞춘다. 계약 스냅숏의 태스크 `attempt` 칸은 발신자의 재시도 횟수라 읽지 않는다. 스냅숏에 태스크가 있는 마지막 로봇 단위(플릿 단위는 세지 않는다)보다 앞선 설비 대기 단위는 근거 등급 E0 인 채 끝난 것으로 둔다. 실행은 단위를 차례로 돌므로 뒤 로봇 단위가 기체에 있으면 앞 대기는 이미 통과했고, 지금 신호로 다시 판정하면 그 뒤 바뀐 신호로 보류나 중단이 나기 때문이다. 같은 까닭으로 그보다 앞선 로봇 단위는 pump 가 성공을 다시 관측해도 설비 근거를 다시 묻지 않고 근거 등급 E0 인 채 끝난 것으로 둔다. 이전 인스턴스가 그 완료를 이미 확인했거나 못 했고, 설비 값은 그 뒤 바뀌었을 수 있다. 실패로 다시 관측되면 보통의 실패 경로를 타고, 마지막 로봇 단위는 보통대로 확인한다. 세 사실은 단위 메모가 아니라 실행 자취에 `RESUME_ATTEMPT`·`RESUME_SIGNAL_PASSED`·`RESUME_PRIOR_ROBOT` 으로 남긴다. 단위 메모는 작업 응답의 미완 단위 값으로 나간다. 실행을 세우는 부분은 `submit` 과 함께 쓰는 `create` 로 옮겼고 `submit` 의 동작은 그대로다. 계약과 계약 버전 0.9.0, 내보내기 버전 6, 인계 번들은 바뀌지 않는다.

    시험은 2,004개에서 2,021개로 17개 늘었고 기존 시험은 고치지 않았다. `ResumeTest` 17개는 넘겨받은 버전으로 계획함, 관문과 조치 탐색 기록 없음, WorkMaster 대조와 그 순서, 근거 등급 거부, 접미사의 가장 큰 재작업 횟수와 기존 핸들에 붙음, 두 자리와 앞자리 0 의 접미사, 접미사 없는 태스크, 앞머리가 같은 단위 id, 앞선 로봇 단위의 성공과 실패, 설비 대기의 경계 셋, 통과로 둔 대기 뒤 로봇 단위 시작과 작업 응답의 도달 근거 등급 E0, 스냅숏을 못 읽을 때의 거부, 같은 작업 지시의 멱등, 리비전 경로를 본다. 기체는 재기동 전 태스크를 들고 같은 태스크 id 에 기존 핸들을 돌려주는 더블이며, 새 태스크를 만든 수가 0 인지로 새 명령이 없음을 본다. 관문, 탐색 기록, 정의, 근거 등급, 검사 순서, 재작업 횟수와 접미사 해석, 설비 대기의 경계와 근거 등급, 앞선 로봇 단위, 스냅숏 거부, 자취, 리비전 경로, WorkMaster 대조에 넣은 결함 23건을 모두 탐지했다. 내보내기 버전 6 과 요약을 박는 시험(`HandoffFixtureTest`)은 고치지 않고 통과했다.

    한계 레지스터에 §15.213 · 재기동 뒤 다시 짓기 행을 남겼다. 운영자 판단, 감수한 결함, 승인 소모 기록, 근거 윈도우의 요청 시각, 설비 대기의 기한 시작 시각, 앞선 로봇 단위에 이전 인스턴스가 낸 근거 확인 결과는 넘어오지 않는다. 마지막 로봇 태스크 뒤의 설비 대기는 다시 기다리므로 재기동 사이에 바뀐 신호로 곧바로 보류나 중단이 날 수 있다. 계약의 `GetSnapshot` 은 종료한 태스크를 싣는다고 정하지 않으므로, 그것을 빼는 발신자에서는 마지막 로봇 태스크 앞의 로봇 단위가 새 태스크로 다시 나간다. mimic 의 스냅숏은 종료한 태스크까지 싣는다.

    **(닫힘, §15.221)** 이 항목이 남긴 한계 행 «§15.213 · 재기동 뒤 다시 짓기»는 §15.221에서 닫혔다. 위에서 «기체 스냅숏을 한 번 읽는다»고 적은 단계는 이제 단위가 묶인 기체마다 한 번 읽으며(오늘은 한 기체라 한 번) 하나라도 못 읽으면 거부한다. 판단 기록을 받으면 그것을 옮기는 일곱째 단계가 더해졌다. 기록 없이 부르면 위의 거동 그대로다.

212. **현장 시간값을 라운드마다 한 번 읽어 일곱 자리에 적용하고 봉인한 인시던트의 의도와 조건부 해시에 담았다.**

    미들웨어의 시간 판정(정체 판정, `IN_DOUBT` 의 근거 윈도우와 유예, 완료 뒤 근거 기한, 완료와 실패 보고 뒤의 근거 판정)은 전부 케이퍼빌리티 getter 의 기본값(앞 30초, 뒤 15초, 유예 60초, 정체 300초)을 읽었고, 생성자에는 다른 값을 넘길 인자가 없었다. 인시던트의 `Intent` 에도 어느 설정으로 판정했는지 적는 칸이 없었다. picasso-ops 의 S3c 스펙(`docs/superpowers/specs/2026-10-09-s3c-site-timings-design.md`, picasso-ops 저장소) T1~T4 가 현장 값 한 세트를 바꾸면 다음 라운드부터 적용되고 인시던트에 값과 버전이 실리는 것을 요구한다. 운영 관리 화면 설계 제안(`docs/superpowers/specs/2026-10-07-ops-console-lifecycle-design.md`) §10 입증 항목 2 와 §11 결정 2(허용 범위는 라이브러리가 쥔다)의 picasso 쪽이다. 읽는 소비자가 처음 생겼으므로 지었다(ADR 9).

    `Ports.kt` 에 `SiteTimingsSource.current()` 와 `NONE`, 설정 버전과 시간값 넷을 담는 `SiteTimings` 를 두고, `Middleware` 생성자의 마지막 인자로 `siteTimings: SiteTimingsSource = SiteTimingsSource.NONE` 을 받는다. `pump()` 첫 줄에서 실행 수와 무관하게 소스를 한 번 읽어 라운드 스냅숏을 만들며, 값이 없으면 케이퍼빌리티 getter 를 쓴다. 정체 판정, `IN_DOUBT` 근거 윈도우와 유예, 완료 뒤 근거 기한, 완료 근거 판정, 실패 보고 뒤 근거 판정, 인시던트 봉인의 일곱 자리가 이 값을 쓴다. 의도에 `siteSettingsVersion`·`inDoubtGrace`·`stallWindow` 를 더하고 기존 앞·뒤 폭도 봉인 라운드의 값으로 채우며, 유예와 정체 값은 기존 폭처럼 `Duration.toString()` 으로 싣는다. 설정 버전이 있을 때만 의도 줄 바로 뒤에 버전·앞·뒤·유예·정체의 해시 줄을 더하므로 설정 없이 만든 요약은 그대로이며, 계약과 계약 버전 0.9.0, 내보내기 버전 6, 인계 번들은 바뀌지 않는다. 허용 범위는 초 단위로 앞·뒤 각각 5~120, 유예 10~600, 정체 30~3600 이며, 상한은 기본값의 4·8·10·12배이고 정체 하한은 가장 긴 발행 간격 30초, 유예 하한은 같은 참조로 다시 묻는 상한 뒤에도 설비를 여러 번 읽을 폭에 근거한다. 범위는 `LongRange` 넷으로 두고 `problems()` 가 범위 밖 값·초 단위가 아닌 값·1 미만의 버전을 보고하며, 생성자와 미들웨어는 검사하지 않고 소스가 걸러 주도록 한다.

    시험은 1,982개에서 2,004개로 22개 늘었고 기존 시험은 고치지 않았다. 전체 빌드와 harness·registry 실행 뒤 소스의 `@Test` 와 결과 XML 의 `<testcase>` 수가 모두 2,004개였으며 실패와 건너뜀은 없었다. `SiteTimingsTest` 14개는 일곱 자리, 값 없음, 라운드당 한 번 읽기, 다음 라운드의 유예 적용, 저장된 근거 기한 불변, 저장된 기한과 그 라운드 앞 폭의 함께 쓰임, 해시와 내보내기를 보고, `SiteTimingsRangeTest` 7개는 범위와 단위·버전·기본값을 본다. 각본대로 응답하는 `ScriptedRobot` 과 케이퍼빌리티 기본값과 칸마다 다른 시간값을 써 적용 자리를 확인했고, 일곱 자리와 적용 시점·범위·인시던트·해시·내보내기에 넣은 결함 17건(일곱 자리 7, 적용 시점 6, 범위 1, 인시던트·해시·내보내기 3)을 모두 탐지했다. `HandoffFixtureTest` 에 설정 없는 번들의 요약을 고정하는 시험 하나를 더해 변경 전 `2ca7cd6` 에서 잰 `d511e96df06e6be67f0b4af8d73bef4a23aedab6253870c30908768358801682` 와 같음을 확인했다. 설정 없는 번들의 요약을 박는 시험은 이것이 처음이다.

    한계 레지스터에 §15.212 · 현장 설정 버전 내보내기 행을 남겼으며, 새 의도 칸 셋은 프로세스 안 인시던트와 해시에만 있고 내보내는 줄에는 없다. 저장하는 시간값은 완료 뒤 근거 기한 하나이며 나머지는 라운드마다 읽으므로, 완료된 단위의 근거 판정은 저장된 기한과 그 라운드의 앞·뒤 폭을 함께 쓴다. 판정 도중 설정을 바꾸면 두 버전의 값이 한 판정에 섞일 수 있는 의미를 정하고 시험으로 고정했다. picasso-ops 통합 시험의 설비 대기 기한 인시던트는 시간값으로 판정하지 않아도 같은 봉인 함수에서 라운드의 버전과 값을 받지만, picasso 시험은 이를 `IN_DOUBT` 운영자 대기 인시던트로 확인하며 설비 대기 경로의 설정 버전은 직접 보지 않는다.

    (의도적 제외로 옮김, §15.265) «§15.212 · 현장 설정 버전 내보내기» 행은 picasso 와 picasso-ops 어디에도 그 값을 쓰는 면이 없어 의도적 제외의 새 표 «수요 없음» 으로 옮겼다.

211. **소비자의 S3b 결정에 맞춰 스키마 파일 오픈 항목의 설명과 해소 조건을 고쳤다.**

    한계 §15.209 의 행은 편집기용 JSON Schema 파일을 S3 의 picasso-ops JSON 편집기에서 정하고, 그 편집기가 S3 에서 스키마 파일을 요구할 때 해소한다고 적고 있었다. picasso-ops 의 S3b 설계 스펙(`docs/superpowers/specs/2026-10-08-s3b-mission-versions-design.md`, picasso-ops 저장소)의 기술 결정 T4 가 편집기를 새 의존 없는 `textarea` 와 서버 검증으로 두고 편집기용 JSON Schema 파일은 만들지 않기로 정했다. 실행 호스트가 picasso 의 `MissionDefinitionParser` 로 파싱해 중복 키와 칸 문제를 JSON 경로로 한 번에 돌려주고, 문법 오류는 첫 자리에서 멈춘다. S3b 는 picasso-ops PR #11 로 머지됐다(머지 커밋 `b2cce34`, 2026-10-09). S3 이 스키마 파일 없이 편집기를 지었으므로 S3 에서 정한다는 예정은 낡았다.

    `docs/limits.md` 의 그 행에서 설명의 예정 문장과 해소 조건의 S3 시점을 걷어내고, 머리의 변경 이력 번호를 211 로 올려 도장을 다시 찍었다. 소비자가 아직 스키마 파일을 요구하지 않으므로 행은 열린 채이고, 해소 조건의 주어(소비자, ADR 9)와 스키마 파일을 만들면 파서와 같은 규칙인지 대조하는 시험을 함께 두는 문장은 그대로다. 코드는 바꾸지 않았고 계약 버전과 내보내기 버전, 시험 1,982 는 그대로다.

210. **기한으로 닫힌 태스크 스트림을 이어 붙이고 팔로워의 받은 목록과 mimic 엔진을 잠금으로 지켜, 실제 시간과 Netty 경로에서도 종료를 관측하게 했다.**

    `PicassoClient.follow` 는 `WatchTask` 스트림에도 `withDeadlineAfter(deadlineSeconds)` 를 걸었고 기본값은 10초였다. 기체 스킬의 소요 시간은 12~45초다(`navigate_to` 20초, `pick_place` 45초, `inspect` 12초). `ClientRobotPort.watch` 는 태스크마다 팔로워를 한 번 만들고(`getOrPut`, `from = 0`) 그 뒤로 다시 붙지 않았으므로, 기한이 지나 스트림이 닫히면 그 뒤에 온 종료를 미들웨어가 영영 못 봤다. `TaskFollower` 의 받은 목록은 일반 리스트였는데 네트워크 채널에서는 gRPC 스레드가 쓰고 미들웨어 pump 가 읽는다. mimic 엔진(태스크 표, 로그, 열린 스트림 표, 가상 시계)에는 잠금이 없었고 모든 태스크 RPC 가 들어올 때 정착(`settle`)을 부른다. 지금까지 시험은 전부 in-process 채널의 `directExecutor` 로 한 스레드에서 돌아 시간을 흘리는 스레드와 RPC 를 받는 스레드가 같았으므로 셋 다 드러나지 않았다.

    picasso-ops 의 S3a 설계 스펙(`docs/superpowers/specs/2026-10-08-s3a-execution-host-design.md`, picasso-ops 저장소)이 미들웨어를 별도 프로세스(`mission-host`)에서 실제 시간으로 돌리며 mimic 에 Netty 로 붙고, 현장 프로세스는 다른 스레드에서 mimic 시계를 민다. 이 경로가 처음으로 실제로 쓰이므로 고쳤다(ADR 9).

    `TaskFollower` 는 받은 목록을 잠금으로 지키고 `updates` 는 잠금 안에서 복사해 돌려주며 `completed`·`error` 를 `@Volatile` 로 두고, 스트림이 닫혔는지 묻는 `ended` 를 더했다. `ClientRobotPort.watch` 는 태스크마다 누적 목록과 지금 팔로워를 들고, 부를 때마다 새로 받은 것을 누적 목록에 옮긴다. 팔로워가 닫혔고 마지막 갱신이 종료(`SUCCEEDED`·`FAILED`·`CANCELLED`·`CANCELLED_RECOVERY_FAILED`)가 아니면 마지막 갱신의 `update_index + 1`(받은 것이 없으면 0)부터 다시 `follow` 하고, 새 팔로워가 곧바로 받은 것도 같은 호출에서 옮긴다. 돌려주는 것은 누적 목록 전체의 복사다. 미들웨어(`Middleware.kt`)는 고치지 않았다. 미들웨어는 `watch` 가 매번 누적 전체 목록을 준다고 전제하고 마지막 원소만 상태로 옮기므로 그 전제가 그대로 선다. 계약의 `from_update_index` 를 쓰며 새 계약 칸은 없다. 스트림의 실패 종류(기한 초과, `NOT_FOUND`, `OUT_OF_RANGE`)는 가르지 않는다. `WatchTaskResponse` 에 거부 칸이 없고 실패는 gRPC 상태로만 온다. `MimicServer` 는 잠금 하나를 둔다. 계약 서비스 셋(Skill·Task·Event)과 제어 채널(`ControlServer`)을 `ServerInterceptor` 로 감싸 호출의 시작과 모든 콜백(요청·반쯤 닫힘·취소·완료·준비)이 그 잠금 아래에서 돌고, `advance`·`settle`·`step`·`push` 도 같은 잠금 아래에서 돈다. 같은 프로세스에서 엔진 상태를 읽는 쪽을 위해 `exclusive { }` 와 `serialized(service)` 를 공개했다. 잠금은 재진입되므로 in-process 시험은 그대로 돈다. `Harness.client` 에 `deadlineSeconds` 인자(기본 10)를 더했고 기본값이 그대로라 기존 호출은 안 바뀐다. 계약 버전과 내보내기 버전은 그대로다.

    시험 5개를 더해 총수가 1,977 에서 1,982 가 됐다. `StreamResumeTest` 4개는 기한으로 닫힌 스트림 뒤의 종료를 다음 `watch` 가 돌려주는지, 이어 붙인 목록의 갱신 번호가 늘기만 하는지, 종료를 본 뒤에는 다시 붙지 않는지(서버가 받은 `WatchTaskRequest` 수로 센다), 여러 스레드가 동시에 넣어도 팔로워가 하나도 안 잃는지를 본다. 스트림 기한을 1초로 주고 실제로 1초 넘게 기다려 스트림을 닫은 뒤 가상 시계를 밀어 `pick_place` 를 종료시킨다. `ConcurrentEngineTest` 1개는 `MimicCli` 로 Netty 서버를 띄우고 한 스레드가 10ms 씩 3,000번 시간을 흘리는 동안 다른 스레드가 같은 태스크에 스트림을 계속 열어도 엔진이 안 깨지는지를 본다. 기존 시험은 고치지 않고 통과했다. 결함 주입 7건이 모두 이름 있는 시험으로 잡혔다: 이어 붙이기 제거, `+1` 제거, 종료 집합 비우기, 새 팔로워의 옮긴 수를 0 으로 안 돌림, `onNext` 의 잠금 제거, 서비스 가로채기 제거, `advance` 의 잠금 제거. 가로채기 제거는 세 번 돌려 세 번 잡혔다. 처음 짠 주입 실행기는 시험을 아예 못 돌리고 «놓침» 만 냈다(파이썬 `subprocess` 의 `bash` 가 다른 셸로 풀렸다). 결과 XML 이 없으면 «시험 결과 없음» 으로 멈추게 고쳐 다시 돌렸다. 1차 설계 검토가 엔진 잠금 부재를 막는 것으로 잡았고, 첫 스파이크는 스트림 이어 붙이기만 고쳤었다.

    남긴 것은 한계 §15.210 의 행 둘이다. 실패 종류를 안 가르므로 늘 실패하는 스트림(기체가 태스크를 모름)은 `watch` 마다 다시 열리고, 그 태스크는 미들웨어의 진행 정체 판정(`stallWindow`)이 잡을 때까지 남는다. 태스크별 누적 목록과 팔로워 표를 지우지 않아 프로세스 수명 동안 는다. mimic 의 태스크 관측 적재(`IngestBridge`, registry 로 가는 동기 HTTP, 연결 2초·요청 3초 제한)는 엔진 잠금 아래에서 돌아 registry 가 느리거나 죽으면 그동안 다른 RPC 와 시간 흘리기가 기다린다. §15.176(승인 창구와 실행기가 시험 소스에 있음)은 열린 채다. picasso-ops 의 실행 호스트는 기체 포트와 셀 신호만 들고 승인 창구·플릿 포트가 없어 포트 여섯을 다 드는 실제 호스트라는 해소 조건에 못 미친다.

    **(닫힘, §15.228)** 이 항목이 남긴 한계 행 가운데 «§15.210 · 재부착»은 §15.228에서 닫혔고, «§15.210 · 적재 잠금»은 열려 있다. 항목 210 본문은 남긴 것으로 «스트림의 실패 종류(기한 초과, `NOT_FOUND`, `OUT_OF_RANGE`)는 가르지 않는다»와 «태스크별 누적 목록과 팔로워 표를 지우지 않아 프로세스 수명 동안 는다»를 들었다. 이제 `ClientRobotPort.watch`는 팔로워가 `NOT_FOUND`·`OUT_OF_RANGE`로 닫히면 다시 붙지 않고, 기한 초과와 연결 단절에는 전처럼 다시 붙는다. 단위가 닫히면 `Middleware`가 `RobotPort.release`를 불러 포트가 그 태스크의 누적 목록과 팔로워를 지우지만, 영구 실패를 미들웨어가 알 길은 없어 그 단위는 `RUNNING`에 머문다.

    **(닫힘, §15.261)** 이 항목이 남긴 행 가운데 열려 있던 «§15.210 · 적재 잠금»은 적재를 작업 스레드 하나의 큐로 옮겨 §15.261에서 닫혔다.

209. **임무 정의를 검증을 지나야 활성화되는 데이터 판으로 옮기고, 설비 대기를 셋째 경로로 더했으며, 실행이 생성 때 쥔 임무 판으로 개정판까지 끝나게 했다.**

    임무 정의는 코드였다. `PrepareSequencedRack.plan()` 이 주문의 destination 마다 `pick_place` 단위 하나를 내고, 제시 자리는 material 로 짝을 지어 뒤엣것이 이기며, 짝이 없으면 계획 때 `FAILED`(`NO_SOURCE_FOR_MATERIAL`)였다. `Middleware` 는 케이퍼빌리티 목록을 workMasterId 로 묶은 맵을 생성 때 쥐었고 실행 중에 바꿀 길이 없었다. 임무 판 칸은 없었다. 실행은 케이퍼빌리티 객체를 쥐었으나 `submit` 이 개정판으로 가기 전에 그 맵을 다시 읽었다. 경로는 `ROBOT`·`FLEET` 둘이었고 진행 루프의 분기는 «로봇이 아니면 플릿» 이었다. 셀 신호 포트에는 이름으로 읽는 신호가 없었다. 바깥 첫 소비자 picasso-ops 의 운영 관리 화면 설계 제안 §10 입증 항목 3(임무 하나를 데이터 판으로: 모의 실행 → 활성화 → 도는 실행 중 새 판 전환 → 옛 실행은 옛 판으로 끝남)·4(신호 사양에 없는 신호를 참조하는 변경이 활성화에서 거절됨)와 P3 스펙(`docs/superpowers/specs/2026-10-08-p3-mission-definition-versions-design.md`, picasso-ops 저장소)이 이것을 정했다. 2026-10-08 사용자 결정은 임무 판을 먼저 picasso 에 짓고 picasso 시험 위에서 입증하며 노드형 스키마와 설비 대기까지 엔진에 구현하는 것이다. 소비자가 생겨서 지었다(ADR 9, ADR 50).

    패키지 `dev.picasso.middleware.mission` 을 두었다. 정의 스키마 `MissionDefinition`(노드 `unit`·`wait`, 직선만, `schemaVersion` 1), 엄격 파서 `MissionDefinitionParser`(모르는 키·빠진 칸·형 틀림을 모두 모아 한 번에, `JsonFormat` → `Struct`, 수는 정수 검사), `StrictJson`(손으로 쓴 문법·중복 키 검사, `JsonFormat` 은 중복 키에 말없이 뒤엣것을 쓴다), 신호 사양 `SignalSpec`(이름·자리·종류·안전 여부), 해석기 `DefinedCapability`(정의 → `LogicalCapability`), 검증기 `MissionValidator`(노드 id 중복 + 기한·신호·자원·스킬·안전), 거절 `MissionRefusal` 9종(종류가 해결 담당과 바로 갈 작업을 정하고 바닥 소유만 화면 밖), 메모리 카탈로그 `InMemoryMissionCatalog`(코드 케이퍼빌리티와 데이터 정의가 함께, `synchronized`, 활성화는 검증 통과 시 WorkMaster 마다 1부터 오르는 판, 거절은 번호를 안 씀)다. 데이터로 옮긴 것은 `PrepareSequencedRack` 하나이고 코드 클래스는 동등성 기준으로 남는다. 설비 대기는 셋째 경로 `Route.SIGNAL` 이다. `WaitSpec(signal, expect, deadline, onDeadline)` 이 단위에 붙고 `pumpSignalUnit` 이 돈다. 판정은 신호의 지금 값이다. 기대 값이면 `DONE`·E2, null 이면 계속 기다리고, 기한을 넘으면 사건(`SIGNAL_DEADLINE`)을 낸 뒤 `OPERATOR_HOLD` 는 대기 단위가 운영자 보류로 서고 `ABORTED` 는 대기 단위 `FAILED`·남은 단위 `ABORTED`·실행 중단이며 취소 답은 남기지 않는다. 진행 루프의 경로 분기를 경로별 `when` 으로 바꾸고 중단 뒤 같은 라운드 반환 가드를 두었다. `CellSignals.signal(name)` 을 기본 null 로 더했고(기존 구현체 안 깨짐) `CellMimic` 에 `setSignal`·`clearSignal` 을 두었다.

    포트 `MissionCatalog` 를 middleware 패키지에 두고 `Middleware` 생성자 맨 뒤에 `missions` 를 더했다(기본은 코드 케이퍼빌리티 셋, 판 없음, `capabilities` 와 함께 주면 `require` 실패). `Execution.missionVersion` 을 생성 때 고정한다. 이미 있는 실행이면 `submit` 은 카탈로그를 안 보고 개정판으로 가고, `revise` 는 실행이 쥔 케이퍼빌리티로 계획·근거 등급을 검사하며 WorkMaster 를 바꾸면 거절한다. `adopt` 는 카탈로그를 한 번 읽은 쌍을 접수까지 넘긴다. 사건의 `Intent.missionVersion` 은 실행이 쥔 판이고 해시에 든다. 내보내기에는 싣지 않는다. 내보내기 판은 5 에서 6 이 됐고 6 의 변경은 사건 줄 `route` 의 `SIGNAL` 하나다(칸은 안 늘었다). ADR 47 이 «실으면 조회 판이 6 으로 오르고» 라고 적은 6 을 이번에 `route` 가 썼다. 인계본 run-1..4 를 다시 산출했다. manifest `schemaVersion` 6, 새 runId, 해시에 임무 판 자리가 생겨 모든 사건 줄의 digest 가 바뀌었고 run-1·run-2 의 digest 는 서로 같으며 나머지 칸은 `wallClockAt`·`resolution.wallClockAt` 말고 같다.

    시험 46개를 더해 총수가 1,931 에서 1,977 이 됐다. `MissionDefinitionParserTest` 9, `DefinedCapabilityEquivalenceTest` 6(주문 모양 9가지 × 10칸 + 통째), `MissionValidatorTest` 12, `InMemoryMissionCatalogTest` 5, `EquipmentWaitTest` 8, `MissionVersionScenarioTest` 6 다. 기존 시험 중 셋을 고쳤다. `EvidenceWindowTest` 의 «5» 를 «6» 으로, `LedgerExportTest` 의 해시 칸 목록에 «임무 버전» 행을, `CompletionCriterionTest` 의 65 를 66 으로다. 그 밖의 기존 시험은 고치지 않고 통과했다. 결함 주입 22건이 모두 이름 있는 시험으로 잡혔다. 해석기: 짝 짓기 규칙을 앞엣것이 이기게. 설비 대기: 기한 비교를 기한 시각에 넘기게, ABORTED 반환 가드 제거, 남은 PENDING 을 ABORTED 로 안 바꿈, 기한 중단이 취소 답을 남김, 개정판이 설비 대기를 로봇처럼 갱신, 대기 중 취소를 안 봄, 신호 자취를 진행마다 남김. 검증기: 기한·신호·자원·스킬·안전·노드 id 검사를 하나씩 뺌. 판: 개정판이 카탈로그를 다시 봄, 개정판의 WorkMaster 변경 거부를 뺌, 실행이 판을 안 쥠, 번들에 판을 안 실음, 번들이 활성 판을 실음, 해시에서 임무 판 빼기. 파서: 중복 키를 안 봄. 배정: 읽은 쌍을 안 넘김. 처음 돌린 주입에서 하나가 안 잡혔다. 배정이 읽은 쌍을 안 넘겨도 한 스레드 시험에서는 카탈로그를 두 번 읽어 같은 답이 와 등가 변이였다. 첫 읽기와 둘째 읽기에 다른 판을 주는 카탈로그로 시험을 더해 잡았다. 번들이 활성 판을 실게 하는 주입은 처음 코드가 컴파일되지 않았다. 생성자 인자 `missions` 가 같은 이름의 속성을 가려 속성 초기화식 안에서는 인자가 잡히므로 `this@Middleware.missions` 로 넣어 잡았다.

    남긴 것은 한계 §15.209 의 행들이다. 나머지 두 정의(`DeliverContainer`·`InspectAsset`)의 데이터 이전, 분기·병렬, 주소 매핑, 정의 JSON Schema 파일, 이벤트형 신호, 주문 설비 id 와 대기 노드 id 겹침, `missionVersion` 내보내기다. 운영 실행 호스트(§15.176)와 저장·이력은 S3(picasso-ops)다.

    **(닫힘, §15.227)** 이 항목이 남긴 한계 행 가운데 «§15.209 · id 겹침»은 §15.227에서 닫혔고, 나머지 §15.209 행(«정의 둘», «분기», «스키마 파일», «이벤트형 신호», «임무 버전 내보내기», «주소»)은 열려 있다. 항목 209 본문은 남긴 것으로 «주문 설비 id 와 대기 노드 id 겹침»을 들었다. 이제 작업 수락(`submit`)과 리비전(`revise`)이 계획의 단위 id가 겹치면 `Middleware.DUPLICATE_UNIT_ID`로 시작하는 사유로 거부한다. 검증기는 그대로 정의만 보고 설비 id를 모른다.

    (의도적 제외로 옮김, §15.265) «§15.209 · 정의 둘», «§15.209 · 분기», «§15.209 · 스키마 파일», «§15.209 · 이벤트형 신호», «§15.209 · 임무 버전 내보내기» 행은 picasso 와 picasso-ops 어디에도 그 값을 쓰는 면이 없어 의도적 제외의 새 표 «수요 없음» 으로 옮겼다.

208. **계약 스킬 종류를 기동 때 채우고 개정판 제출·활성화와 기체 바인딩의 조작 문 5개를 열었다.**

    `SkillTypeSync` 를 부르는 곳이 시험 소스뿐이었다. 운영 registry 의 `skill_type` 은 비어 있었다. `skill_type` 이 비면 개정판 제출(`RevisionService` 의 `insertSkills`)이 선언 스킬을 조용히 건너뛴다. 그러면 사이트 명칭 요구 집합이 비고 바인딩의 계약 semver 검사도 비교할 것이 없다. 주석의 «그 부재는 바인딩이 보고 막는다» 는 사실이 아니었다. 개정판 제출·활성화·바인딩에 REST 가 없었고 `RevisionService` 는 빈(bean)도 없었다. 옛 `BindingService.bind` 는 없는 기체를 FK 위반 500 으로 냈고, 퇴역 기체도 묶었고, 같은 조합을 다시 묶으면 새 행을 만들어 사이트 명칭 등록 기록이 «미등록» 으로 돌아갔다. 같은 기체의 동시 첫 바인딩은 유일 색인 `robot_binding_one_active` 위반 500 이었다. `/diag/bindings` 행은 어댑터 이름과 버전만 내서 «요청한 빌드로 묶였는가» 를 id 로 판정할 수 없었다. mimic CLI 로 띄운 기체에는 기체가 아는 사이트 명칭(`knownSiteNames`)을 넣을 길이 없었다(기본값이 빈 목록이라 명칭을 기록하면 `CONTRADICTED`). 바깥 첫 소비자 picasso-ops 의 P2·S1d 설계 스펙(`docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md`, picasso-ops 저장소) §6 이 이것들을 P2b 로 정했다. 소비자가 생겨서 열었다(ADR 9).

    기동 동기화(설계 §8.3 ④)를 지었다. registry 가 기동할 때 `ApplicationRunner` 가 자기 계약 기술자(`/picasso.desc`)와 `ContractIdentity.semver` 로 `SkillTypeSync` 를 부른다(`SkillTypeCatalog.syncAtBoot`). 감사 `SKILL_TYPE_SYNC` 의 행위자는 `registry` 다. 기술자를 못 읽으면 기동을 거부한다. 빈 카탈로그로 뜨면 위의 «조용히 건너뜀» 상태로 돌아가기 때문이다. 스키마가 없으면(`skill_type` 표가 없음) 경고를 남기고 건너뛴다. 마이그레이션은 런처의 몫이라 registry 가 돌리지 않고, 스키마 없는 registry 는 어느 조작도 못 하므로 조용히 건너뛸 스킬도 없다. 기존 표면 시험은 컨텍스트가 뜬 뒤 시험마다 스키마를 지우므로 영향이 없었다.

    registry 에 조작 문 5개를 열었다(모두 운영자 토큰, 조작 POST 는 `X-Actor` 필수). `GET /operations/skill-types` 는 지금 계약 semver 와 스킬 종류 목록(이름, major, 처음 본 계약 semver, 사이트 명칭 키)을 낸다. `POST /operations/profile-revisions`(본문은 프로파일 문서 JSON 그대로)는 201 저장(개정판 id·번호·상태·사유, 검증 실패도 `DRAFT` 로 201), 200 같은 기종·같은 번호·같은 문서 해시의 재제출(같은 개정판), 409 번호가 단조 증가하지 않음(같은 번호의 다른 문서 포함, 본문 `received`·`highest`), 400 문서로 읽을 수 없음이다. `GET /operations/profile-revisions` 는 개정판마다 상태, 사유, 문서 해시, 제출·활성화한 이와 시각, 스위트별 최신 결과(결과·시각·실행 주체·상세), 최신 시험 요청 1건(요청·집기·만료·끝난 시각)을 낸다. `POST /operations/profile-revisions/{profileRevisionId}/activation` 은 200 활성화(내려간 옛 활성 id), 200 이미 `ACTIVE`(바꾸지 않음, 본문 `already`), 409 상태가 `TESTED`·`SUPERSEDED` 가 아니거나 세 스위트 최신 결과가 모두 PASS 가 아님(본문 `status`·`suites`), 404 없는 개정판이다. `POST /operations/robots/{robotId}/binding`(본문 `adapter_version_id`·`profile_revision_id`, 선택 `reason`)은 201 새 바인딩(해제한 이전 바인딩 id), 200 같은 조합이 이미 활성(행과 사이트 명칭 기록 유지), 404 `reason` 이 `UNKNOWN_ROBOT`·`UNKNOWN_REVISION`·`UNKNOWN_BUILD`, 409 `reason` 이 `ROBOT_RETIRED`·`REVISION_NOT_ACTIVE`·`CONTRACT_TOO_OLD`, 400 두 id 중 하나가 빠짐이다. 바인딩 검사 순서는 기체 있음 → 퇴역 아님 → 개정판 있음 → 빌드 있음 → 개정판 활성 → 계약 semver → 같은 조합이다. 같은 조합 검사를 맨 뒤에 둔 것은 요청이 지금 유효할 때만 «이미 됨» 이라 답하려는 것이다. 그래서 묶인 뒤 개정판이 대체됐으면 같은 조합이어도 409 `REVISION_NOT_ACTIVE` 다. 바인딩은 기체 행을, 제출과 활성화는 기종(`capability_profile`) 행을 잠그고 진행한다. 같은 요청이 동시에 두 번 오면 500 대신 하나는 만들고 하나는 그것을 돌려준다. 결과 타입은 P1 과 같은 방식이다. `RevisionService.submitDocument` → `Submitted`, `BindingService.activateRevision` → `Activation`, `BindingService.bindRobot` → `Binding` 이다. 옛 메서드와 그 시험은 그대로다. 옛 `activate` 는 새 메서드에 위임하며 동작이 바뀌지 않았다(이미 `ACTIVE` 면 전처럼 거부). 옛 `submit`·`bind` 는 남기고 저장과 바인딩 기록 부분만 새 메서드와 공유한다. 멱등은 새 메서드에만 있다. 감사는 새로 만들 때만 남는다.

    `/diag/bindings` 행에 칸을 더했다(기존 칸은 그대로). `adapterVersionId`, `boundBy`·`boundAt`, `siteNamesRegisteredBy`·`siteNamesRegisteredAt`(사람의 기록), `siteNamesReportedAt`·`siteNamesCount`·`siteNamesUnsupported`(기체의 답, 널은 아직 안 물어봄)다. mimic 은 `MimicCli.start` 가 돌려주는 `Started` 에 `instance(robotId)` 를 더해 담는 쪽이 기체가 아는 명칭을 넣을 수 있다(현장의 명칭 티칭 흉내, 제어 서버 없이). `RobotInstance.knownSiteNames` 를 `@Volatile` 로 했다(쓰는 스레드와 생존 보고를 발행하는 스레드가 다름). 함께 고친 것은 넷이다. `RevisionService` 주석(«바인딩이 보고 막는다» 가 사실이 아님), `/diag/bindings` 행 KDoc 의 명칭 상태(3값으로 적혀 있었고 실제는 5값), 퇴역 기체 재등록 거절 메시지가 기체 id 와 퇴역 시각 대신 `$robotId`·`${existing.retiredAt}` 를 글자 그대로 찍던 것, 모르는 기체의 복귀 거절이 `${outcome.robotId}` 를 글자 그대로 찍던 것이다.

    시험 26개를 더해 총수가 1,905 에서 1,931 이 됐다. 서비스 `RevisionOperationsTest` 14, 표면 `RevisionEndpointTest` 10, 기동 `CatalogBootEndpointTest` 1(기동 전에 스키마를 세우고 기동 뒤 손으로 넣지 않음), mimic `StartedInstanceTest` 1 이다. 기존 시험은 고치지 않고 통과했다. 결함 주입 25건이 모두 이름 있는 시험으로 잡혔다. 바인딩 6: 기체 행 잠금 제거, 기체 있음 검사 제거, 퇴역 검사 제거, 같은 조합 검사 제거, 대체된 개정판을 활성으로 봄, 개정판 있음 검사 제거. 제출 3: 해시 같음 판정 제거, 기종 행 잠금 제거, 재제출이 사유를 버림. 활성화 2: 이미 활성 판정 제거, 거절 본문의 스위트 결과 비움. 카탈로그 4: 기동 동기화 호출 제거, 감사 행위자 바꿈, 스키마 없음 판정 제거, 기술자 없음을 거부 대신 건너뜀. 문 9: 같은 조합 200 을 201 로, `UNKNOWN_BUILD` 를 `UNKNOWN_REVISION` 으로, 같은 문서 재제출 200 을 201 로, 진단 행의 빌드 id 를 다른 칸으로, 목록의 최신 결과를 가장 옛것으로, 퇴역 메시지를 글자 그대로로 되돌림, 모르는 기체 메시지를 글자 그대로로 되돌림, 진단 행의 명칭 보고 시각을 등록 시각 칸으로, 호스팅 불가 칸을 널로. mimic 1: 인스턴스를 넘기지 않음. 처음 돌린 주입에서 둘이 안 잡혔다. 기종의 첫 제출은 기종 행의 `INSERT … ON CONFLICT DO NOTHING` 이 둘째를 기다리게 해 잠금이 없어도 차례가 지켜졌고, 빈 DB 에서는 빌드 id 와 개정판 id 가 둘 다 1 이라 진단 칸을 바꿔 읽어도 같았다. 시험을 기종이 이미 있는 동시 제출과 두 id 가 갈리는 바인딩으로 고쳐 둘 다 잡았다. 남긴 것은 셋이다. 바인딩의 major 일치 검사(설계 §9.1)는 더하지 않았다(지금처럼 개정판 스킬의 최초 semver 와 빌드의 계약 semver 크기만 비교). 퇴역은 바인딩을 풀지 않아 퇴역 기체가 `GET /catalog` 의 가용 수에 들어간다(한계 대장 소비자 대기). 핸드셰이크와 바인딩의 대조, mimic 이 바인딩을 가져가는 것은 없다.

    (닫힘, §15.264) 2026-11-05에 카탈로그 질의가 퇴역 기체를 빼서 닫혔다. 퇴역은 여전히 바인딩을 풀지 않는다.

207. **개정판 시험 3종의 뜻을 정하고 실행기를 지어 요청 → 집기 → 3종 → 보고 → `TESTED` 고리를 닫았다.**

    설계 §8.4 ② 가 적은 «시험 요청 적재 → harness 폴링 인출 → 가상화 계약 검증 스위트 완주 → TESTED» 고리는 지어지지 않은 채였다. `V2__testing.sql` 주석이 «폴링 고리(harness 쪽)는 3a-2다» 로 미뤘고, `harness` 의 `Suite` 열거형을 쓰는 코드가 없었으며, `TestRequestService.claim` 과 `BindingService.recordTestRun` 은 시험 소스만 불렀다. 시험 요청에 «끝남» 칸이 없어 집은 요청은 15분 만료 뒤 다시 집혔다. 바깥 첫 소비자 picasso-ops 의 P2·S1d 설계 스펙(`docs/superpowers/specs/2026-10-08-p2-s1d-runner-binding-commissioning-design.md`, picasso-ops 저장소)이 화면에서 개정판을 시험 요청하고 진짜 실행기가 시험하게 정했다(2026-10-07 사용자 결정). 실행기는 picasso-ops 의 가짜 현장(`site/`) 프로세스가 띄운다. 소비자가 생겨서 고리를 지었다(ADR 9).

    registry 에 문 3개를 열었다. `POST /operations/profile-revisions/{profileRevisionId}/test-requests`(운영자 토큰, `X-Actor` 필수)는 201 새 요청, 200 끝나지 않은 요청이 이미 있음(그 요청, 멱등), 404 없는 개정판, 409 `DRAFT`·`REVOKED`(본문 `status`)다. `POST /ingest/test-requests/claim`(적재 토큰, 본문 `worker`)은 200 요청 id·개정판 id·집은 시각(`claimed_at`, DB 에 적힌 값)·후보 문서, 204 집을 것 없음이고 끝난 요청은 집지 않는다. `POST /ingest/test-requests/{requestId}/results`(적재 토큰, 본문 `worker`·`claimed_at`·`results` 3개)는 200 보고 뒤 상태, 400 스위트가 셋이 아니거나 모르는 결과·시각 형식 오류, 404 없는 요청, 409 는 본문 `reason` 으로 가른다(`COMPLETED`·`NOT_CLAIMER`). 실행 3행, 요청의 «끝남», 승격이 한 트랜잭션이다. 스키마 `V16__test_request_completion.sql` 이 `revision_test_request.completed_at` 칸과 부분 유일 색인 `revision_test_request_one_open` 을 더했다. 집은 시각까지 대는 것은 실행기 이름이 같은 이름으로 다시 뜰 수 있어 이름만으로는 죽은 실행기의 늦은 보고를 못 가르기 때문이다. 집은 시각은 DB 값(마이크로초)을 그대로 돌려준다 — 메모리 값(나노초)을 내주면 모든 보고가 거절된다.

    시험 3종의 뜻을 정했다(ADR 49). `CONTRACT` 는 선언 스킬 전부와 `REQUIRED` 선택 필드로 협상하면 수락되고, 능력 조회가 `CapabilityProjection.of(문서)` 와 같으며, 선언 스킬마다 새 기체에서 태스크가 수락되고 성공이거나 선언한 결함으로 멈춘다. `NEGATIVE` 는 프로파일이 못 한다고 적은 것마다 탐침 1개를 보내 정해진 거절 코드(`SKILL_ABSENT`·`PARAMETER_INVALID`·`CANCEL_UNSUPPORTED`·`PAUSE_UNSUPPORTED`·`REQUIRED_OPTIONAL_MISSING`)가 오는지 본다. `DETERMINISM` 은 `CONTRACT` 의 태스크 시나리오를 같은 시드로 두 번, 각각 새 `Harness` 에서 돌려 발행 전부와 태스크 갱신이 같은지 댄다. 스위트가 검사를 하나도 안 돌렸으면 통과가 아니고, 상세 JSON(`checks`·`failures`)이 `revision_test_run.detail` 에 그대로 들어간다. `MinimalParameters` 가 선언에서 최소 유효값과 어긴 값을 만든다. 실행기 `RevisionTestRunner` 는 `harness` 의 운영 코드(`dev.picasso.harness.revision`)에 두고, 후보 문서를 임시 파일로 써서 기존 `Harness` 에 넘기며, registry 에는 HTTP 로만 닿는다(`HttpTestDesk`). `harness` 운영 코드는 `:registry` 에 의존하지 않고 `:capability` 의존이 하나 늘었다. 적재에서 거절된 문서는 세 스위트를 모두 FAIL(`LOAD`)로 보고하고, 보고의 응답을 못 받으면 최대 3번 다시 보내며 409 `COMPLETED` 면 앞 보고가 반영된 것으로 본다. 게이트 검사 11 목록에 `RevisionTestRunner.kt` 를 더하고 `docs/diagrams/components.svg` 에 `harness → capability` 간선을 그렸다.

    시험 34개를 더해 총수가 1,871 에서 1,905 가 됐다. registry 서비스 `RevisionTestRequestTest` 12, 표면 `TestRequestEndpointTest` 8, harness `RevisionSuitesTest` 4, `RevisionTestRunnerTest` 7, `RevisionRunnerEndToEndTest` 3(registry 를 같은 JVM 에 띄우고 실행기는 HTTP 로만 닿음)이다. `humanoid-a` 와 `quadruped-b` 가 요청 → 집기 → 3종 PASS → `TESTED` 로 간다. 한 프로파일의 3종은 1초 안팎에 끝난다(humanoid-a 약 0.3초). 결함 주입 19건이 모두 이름 있는 시험으로 잡혔다. registry 9: 열린 요청 재사용 제거, 집기의 끝남 조건 제거, 보고의 집은 시각 대조 제거, 집은 시각을 메모리 값으로, `DRAFT`·`REVOKED` 거절 제거, «끝남» 기록 제거, 409 `reason` 바꿈, 멱등 200 을 201 로, 보고의 승격 제거. harness·mimic 9: mimic 의 취소 미지원 거절 제거(`NEGATIVE`), 능력 응답의 개정판 번호 바꿈(`CONTRACT`), 두 번째 실행의 시드를 먼 값으로(`DETERMINISM`, humanoid-a), 정규화 제거(`DETERMINISM`), 보고 재전송 제거, 적재 거절을 예외로 던짐, 보고의 집은 시각을 현재 시각으로, 선언 안 한 스킬 탐침 제거, 409 `COMPLETED` 판별 제거. 게이트 1: 검사 11 목록에서 실행기 제거.

    실측한 덫이 둘이다. `java.util.Random` 은 이웃한 작은 시드의 첫 난수가 거의 같다. 시드 0~6 으로 humanoid-a `pick_place`(작업 시간 45초, 지터 비율 0.1)를 새 기체에서 돌리면 모두 48초째 성공하고 자취가 같았다. 그래서 «두 번째 실행의 시드를 1 올린다» 는 주입은 등가 변이였고, 먼 시드(987654321)로 넣었다. 지터를 선언하지 않은 `quadruped-b` 는 시드를 바꿔도 같은 자취다. harness 시험 클래스패스에서 registry(Spring)를 띄우면 slf4j 구현 충돌(mimic 쪽 NOP 와 Spring 의 logback)로 기동이 거부된다. 시험이 Spring 의 로깅 초기화만 끈다(`org.springframework.boot.logging.LoggingSystem=none`). harness 시험 의존에 Spring Boot(starter-web)를 더했다. 남긴 것은 셋이다. 실행기를 상주시키는 `main`(picasso 에 없고 첫 소비자가 프로세스 안에서 `start(interval)` 로 띄운다), 시험 요청의 취소(만료만 있음), mimic 의 후보 개정판 검증 모드(설계 §10.2 `--registry --profile-revision`).

206. **소비자의 시운전 Step 2 를 위해 어댑터 제품·빌드 등록과 조회 조작 문을 열었다.**

    registry 에 어댑터 제품·빌드를 등록·조회하는 조작 문 3개를 열었다. 바깥 첫 소비자 picasso-ops(운영 화면, S1 스펙 §5)가 시운전 Step 2 를 화면에서 하려 했지만 빌드를 넣는 문이 없었다. `AdapterService.registerAdapter`·`registerVersion` 은 있었으나 부르는 컨트롤러가 없어 시험만 직접 불렀다. 어댑터 인스턴스 등록은 실재하는 `adapter_version_id` 를 요구한다. `/diag/software` 는 바인딩된 기체의 소프트웨어 대조라 빌드 목록이 아니며, `/diag/adapter-instances` 는 인스턴스가 있어야 빌드가 보였다. 소비자가 생겨서 문을 열었다(ADR 9).

    `POST /operations/adapters` 는 새 제품이면 201, 이미 있으면 200, vendor·name 이 빈 값이면 400 이다. `POST /operations/adapters/{adapterId}/versions` 는 새 빌드이면 201, 같은 내용의 재요청이면 200, 같은 버전에 다른 계약 SemVer 이면 409 이다. SemVer 형식 오류 또는 빈 version 은 400, 없는 제품은 404 이다. `GET /operations/adapters` 는 제품과 빌드 목록 및 빌드마다 적합성을 돌려준다. 운영자 토큰 관문(`/operations/**`)과 `X-Actor` 필수는 기존 조작 문과 같다. 응답 키는 snake_case 이고 컨트롤러가 정한다. 서비스에는 결과를 구분하는 `declareAdapter`·`declareVersion`·`list` 와 결과 타입 `AdapterDeclared`·`VersionDeclared` 를 더했다. 옛 `registerAdapter` 는 `Long` 하나만 돌려줘 새로 만들었는지 알 수 없었고, 옛 `registerVersion` 은 SemVer 형식 오류와 중복을 `Rejected` 하나로 접었으며 없는 제품은 FK 위반 예외가 났다.

    새 등록은 같은 내용의 재요청에 같은 id 를 돌려주는 멱등이다. 같은 버전에 다른 계약값은 덮어쓰지 않고 409 로 응답한다. 감사 기록은 새로 만들 때만 남기며 빌드 목록은 등록 순서다. 옛 `registerVersion` 은 새 메서드에 위임해 SQL 경로를 하나로 했다. 옛 호출자는 멱등을 기대하지 않으므로 같은 버전 재등록 거절은 그대로 두었다. 위임으로 동작이 바뀐 곳은 3곳이다. 없는 제품은 예외 대신 거절하고, 빈 version 과 빈 actor 는 저장 대신 거절한다. `registerAdapter` 는 호출 18곳이 시험 파일 14개에 있고 그중 harness 3개가 있어 그대로 두었다.

    새 시험은 18개로, 서비스 `AdapterDeclarationTest` 10개와 표면 `AdapterEndpointTest` 8개다. 결함 주입 8건은 모두 이름 있는 시험으로 잡혔다. 빌드 감사 기록 위치를 옮긴 경우, Conflict 앞에서 저장값을 덮어쓴 경우, 409 를 400 으로 바꾼 경우, 이미 있는 제품의 200 을 201 로 바꾼 경우, 같은 내용 판정을 지운 경우, 없는 제품의 404 를 500 으로 바꾼 경우, 목록 응답에서 제품 이름을 vendor 칸에 넣은 경우, 빈 version 의 400 을 409 로 바꾼 경우다. 계약 SemVer 문자열은 정규화하지 않았다. 따라서 뜻이 같은 다른 표기라도 앞뒤 공백이나 앞자리 0 이 있으면 409 가 된다. 정규화하면 옛 경로의 저장값도 바뀌므로 이번에는 하지 않았다.

205. **승인 창구 HTTP 규약과 구동기 인자를 문서화하고 실물 담는 측 조건을 적어 §15.203을 외부 미결로 옮긴다 — 바깥 루프(koshei) 세션이 창구 명세를 물었고 실물 담는 측의 자리를 정했다.**

    바깥 루프(koshei) 세션이 승인 창구의 HTTP 상태, 오류 본문, `Content-Type`, 경로, 기동할 때 알 것을 물었다. 답은 코드 주석과 인계본(`handoff/narrator/INDEX.txt`)에만 있었고 `docs/orchestration.md` §7.5에는 명령 두 줄뿐이었다. 그래서 §7.5에 HTTP 규약 표와 구동기 인자 표를 넣었다. HTTP 규약의 행은 `ApprovalHostTest` 의 시험 이름을 댄다. ★**`Content-Type` 두 가지는 시험이 없어 보증이 아니라 지금 구현의 사실이라고 적었다.**

    §15.203을 소비자 대기에서 외부 미결로 옮겼다. 2026-10-04에 koshei 쪽이 실물 담는 측의 자리를 정했다(현장 운영 측 소유의 별도 배치 저장소). 소비자가 요구를 말했으므로 남은 해소 조건의 주어는 현장이다. 한계 대장의 갈래 대조 시험(`한계 대장의 갈래가 해소의 주어와 맞는다`)은 소비자 대기 행의 해소 조건에 "현장"이 들면 막는다. README 의 수가 소비자 대기 9개, 외부 25개가 됐다(미결 총 62개 그대로).

    담는 측이 지킬 조건(앞을 자르지 않는 전체 이력)을 §8에 적었다. 2026-10-04 연결 시험에서 koshei 가 구동기를 상대로 승인(`APPROVED`)을 받고, 결과 통보를 (`instanceId`, `executionId`)로 그 시도에 이었다.

204. **출하 소스를 훑는 범위를 선언된 모듈로 바꿨다 — §15.189 가 워크트리 사본을 거른 경로 문자열이 워크트리 안에서 도는 시험의 범위를 통째로 비웠다.**

    §15.189 는 `mainSource` 가 `.claude/worktrees/` 의 사본까지 읽는 것을 경로에 `/.claude/` 가 든 파일을 빼는 것으로 막았다. 워크트리 밖에서 돌 때만 맞는 막음이었다. 시험이 워크트리 안에서 돌면 저장소 뿌리부터 모든 경로에 `/.claude/` 가 들어 있어 범위가 비고, `자원 소유 대장이 대는 관문이 코드에 실재한다` 는 대장의 기호 열둘을 전부 «코드에 없다» 로, `용어집이 승인 표면에 대는 이름과 수가 코드와 같다` 는 `ApprovalRefusal 을 출하 소스에서 못 찾았다` 로 빨개진다. 둘 다 범위가 아니라 문서나 코드가 틀렸다고 가리키는 모양이고, `mainSource` 의 KDoc 이 경계하던 실패 그대로다 — 멀쩡한 기호가 «코드에 없다» 로 나오면 고쳐지는 것은 검사가 아니라 문서다.

    ★**같은 파일에 맞는 모양이 이미 있었다.** §15.189 가 함께 만든 시험 수 세기는 `modules().distinct()` 의 모듈마다 `src/test` 를 `Repo.path` 로 걷고, 그래서 워크트리 안에서도 멀쩡했다. `mainFiles` 를 같은 모양으로 바꿨다. 경로 문자열 거르기 셋(`/src/main/`·`/build/`·`/.claude/`)이 없어졌고 `WORKTREES` 는 쓸 곳이 없어 지웠다. 앞 판은 `Repo` 의 문을 안 거치고 저장소를 통째로 걸었는데, 이제는 선언된 입력 밖을 못 읽는다.

    **음성 하네스의 overlay 도 범위에서 빠졌다.** 앞 판은 `gate/negative/` 의 07·09·11 overlay 에 든 `.kt` 셋을 출하 소스로 읽었다. §15.192 에서 검사 11 이 저장소를 통째로 걷다가 제 음성 케이스의 overlay 에 물려 모듈을 하나씩 열게 된 것과 같은 구멍이다. 지금 든 것은 대장이 대는 기호를 선언하지 않아 결과가 같지만, 진짜 파일을 통째로 복사한 overlay 가 생기면 진짜 선언을 지워도 사본이 그 기호를 살려 둔다. 워크트리 밖의 사본에서 `Middleware.kt` 를 overlay 로 복사하고 진짜 `occupancyViolation` 선언을 지우는 주입이 그것을 보였다. 앞 판은 초록이고 이 판은 그 기호를 댄다.

    주입 넷이 물었다(진짜 선언 삭제, overlay 사본의 가림, 점 기호의 멤버 삭제, 용어집의 없는 기호). 워크트리 안에서 앞 판은 두 시험이 빨갛고 이 판은 둘 다 초록이며, 워크트리 밖에서는 두 판 다 초록이라 CI 가 보는 결과는 그대로다.

203. **결과 통보에 바깥 형식을 두고, 승인 답과 실행 · 걸음 단위 · 인스턴스로 잇는다 — 바깥 루프가 결과 통보만으로는 «끝났다» 에 닿지 못했다(ADR 48).**

    바깥 루프(koshei) 세션이 직접 물었다. 그쪽은 승인 답의 `executionId` 로 결과 통보를 잇고 대상 단위가 `completedUnits` 에 있으면 끝났다고 판정하는데, 결과 통보에는 실행이 없었고 걸음이 어느 단위인지는 규약이 아니었으며 `exec-N` 은 다시 뜨면 되풀이됐다. 그리고 묻고 나서야 드러난 것이 하나 더 있었다 — **결과 통보를 밖으로 내는 형식 자체가 없었다.** 프로세스 안의 `pending()` · `responses()` · `ack()` 뿐이었고, 그쪽은 picasso 를 품지 않는다.

    `ResultExport` 가 결과 통보를 판 1 의 줄로 짓는다(순수 함수, 쓰기는 담는 쪽 — §6 의 대장과 같은 가름). `JobResponse.executionId` 를 두고, `Middleware.instanceId` 를 승인 답과 결과 통보 줄에 같은 값으로 싣는다. 승인 창구는 판 4 가 되어 모든 답에 `instanceId` 가, 걸음마다 `unitId` 가 붙었고 `remedy-{n}-{skillType}` 은 이제 규약이다. 구동기가 두 대장 옆에 `job-responses.jsonl` 참조 한 벌을 쓴다.

    ★**이름을 `runId` 로 안 지었다.** 대장 내보내기의 구동 식별자가 그 이름이고 용어집에 다른 뜻으로 서 있다. 같은 낱말에 두 뜻을 얹으면 §15.185 의 낱말 비켜 감이 다시 난다. `version` 도 빌려 쓰지 않았다 — ISA-95 응답의 대응 칸이다.

    시험이 열 늘었고, 겨눈 결함 아홉(통보의 실행 바꿔치기 · 승인 걸음과 소모 걸음의 단위 · 답의 인스턴스 · 걸음의 단위 칸 · 빈 목록 키 빼기 · 결정적 인스턴스 · 판 되돌리기 · 확인 상태 싣기)을 하나씩 넣어 각각 그 시험이 이름을 대며 빨개지는 것을 봤다. 실물 담는 쪽은 아직 없고 소비자 대기에 적었다(§15.203).

202. **운영자 판단은 사람만 내고, 누가 냈는지 사건에 남긴다 — 사람만 판단한다는 것이 문이 없어서 지켜지고 있었다(ADR 47).**

    설명 층의 자문(R8 · R6)에서 나온 자리다. `resolve` 는 승인자를 받지 않았고, 사건의 판단 기록에도 누가 판단했는지가 없었다. 승인면은 누가 눌렀는지 없이는 승인이 안 되는데(ADR 43) 판단은 그 규율 밖에 있었다. 이제 `resolve` 가 승인자를 기본값 없이 받고, 에이전트는 확인도 재작업도 거절하며 거절은 아무것도 안 바꾼다. 답은 `ResolveOutcome` 셋(`Resolved` · `NotHeld` · `Refused`)이다 — 참 · 거짓이면 «보류 단위가 없다» 와 «에이전트라서 막았다» 가 접힌다.

    ★**판단자는 내보내지 않는다.** 설명 층에 먼저 물었고, 판단 기록에서 결정과 가상 시각만 읽으며 누가 판단했는지를 쓸 자리가 없다는 답을 받았다. 실으면 조회 판이 6 으로 오르고 인계본 네 벌과 그쪽 골든셋 픽스처를 다시 맞춰야 하는데 읽을 칸이 없다(ADR 9). 그래서 `IncidentResolution.decidedBy` 는 프로세스 안의 사건 조회로만 보이고, 한계 대장의 소비자 대기에 적었다(§15.202).

    시험이 넷 늘었고, 겨눈 결함 넷(에이전트 검사 삭제 · 판단자 바꿔치기 · 없는 실행을 `Resolved` 로 답하기 · 내보내기에 판단자 싣기)을 하나씩 넣어 각각 그 시험이 이름을 대며 빨개지는 것을 봤다.

    (의도적 제외로 옮김, §15.265) «§15.202» 행은 picasso 와 picasso-ops 어디에도 그 값을 쓰는 면이 없어 의도적 제외의 새 표 «수요 없음» 으로 옮겼다.

201. **승인이 소모한 제안을 기록하고, 다시 온 승인에 `CONSUMED` 로 그 기록을 돌려준다 — `NO_PROPOSAL` 이 «이미 소모됨» 과 «선 적 없음» 을 한 값으로 접고 있었다(ADR 46).**

    설명 층이 바깥 루프의 설계를 대조하다 짚었다. 같은 (기체, 주문)에 승인이 두 번 오면 둘째는 `NO_PROPOSAL` 이었고, 그 값은 앞선 요청이 소모한 경우 · 다른 승인자가 소모한 경우 · 처음부터 제안이 없던 경우를 접었다. 셋은 다음 행동이 다르다. `orchestration.md` §7.3 표가 `NO_PROPOSAL` 에 적은 후속 조치 «다시 읽고 다시 시도» 도 셋 어디에도 맞지 않았다 — 다시 시도하면 셋 다 다시 `NO_PROPOSAL` 이다. 같은 제안이 두 번 나가는 일은 이미 없었고(소모는 한 번, 창구는 자물쇠 하나), 모자란 것은 부르는 쪽이 아는 길이었다.

    `settle` 이 제안을 지울 때 (기체, 주문)마다 소모 기록(`ConsumedApproval`)을 남긴다 — 승인자, 가상 시각과 실 시각, `executionId`, 승인 답과 같은 걸음. `judge` 는 서 있는 제안이 없을 때 그 기록이 있으면 `CONSUMED` 로 낸다. 승인 창구 규약은 판 3 이 됐고 거절 답에 `consumed` 칸이 생겼다. `CONSUMED` 와 기록은 짝으로만 선다 — 거절 답과 판정을 만들 때 둘 다 검사한다. 사람의 문은 판정을 바로 산문으로 옮기므로 그 길에서는 판정 쪽 검사가 유일한 방벽이다.

    ★**처음 적은 담보 둘이 반증할 수 없는 문장이었다.** «관문 거절과 멱등 접힘은 소모로 남지 않는다» 를 재시도로 대려 했는데, 기록은 서 있는 제안이 없을 때만 읽히고 그 둘은 제안을 지우지 않으므로 «기록만 남기는» 결함은 어떤 재시도에도 안 보인다. 설계 검토가 그것을 짚었다. 담보를 관측할 수 있는 것(재시도는 서 있는 제안으로 판정되고 제안이 남는다)으로 바꿨다.

    ★★**독립 검토가 이 기록의 전제 하나를 거짓으로 짚었고, 시험이 그것을 확인했다.** «소모 뒤 같은 열쇠에 새 제안은 안 선다» 는 §15.175 의 «개정 경로는 v1 에서 도달 불가» 에 기댄 것이었는데, 그 주장이 거짓이었다. 소모 뒤 개정판이 오고 기체가 아직 든 채면 개정의 사슬 거절이 `pick_place` 제안을 세웠고(§15.175 는 막힌 단위가 `pick_place` 인 경우만 봤다 — 순회의 `navigate_to` 가 막히면 `pick_place` 가 그 해다), 재시도는 `CONSUMED` 대신 `REFUSED_BY_GATE` 를 받았다. 그리고 버전이 거꾸로 도착하면(다른 기체에 앞 판이 먼저 서면) 승인이 개정으로 들어가 `Approved` 를 내고 조치는 안 나갔으며, 다른 기체에 선 실행이 승인의 판으로 바뀌었고, 그 거짓이 새 소모 기록에 남았다. 둘 다 여기서 막았다 — 이미 실행이 선 주문의 승인은 판과 무관하게 접고(밖의 문은 `REMEDY_NOT_APPLIED`, 사람의 문은 같은 사유의 거절), 개정의 거절은 탐색 줄만 남기고 제안을 세우지 않는다. 한계 대장의 §15.175 행을 그에 맞게 고쳤다(175 항목은 그대로 두고 정정 표시만 단다).

    막은 뒤 다시 본 검토가 낮은 것들을 더 짚었다. 사람의 문은 접힘을 사유 없는 멱등으로 받고 있었다 — 앞 판에서 지난 판과 종착한 실행은 사유를 든 거절이었으니 그만큼 말을 잃은 것이라, 밖의 문과 같은 사유의 거절로 바꿨다. 개정의 거절은 제안이 아니므로 가림 차례에도 들지 않게 됐고, 같은 프로세스에서 한 열쇠에 `FOUND` 줄과 `NO_PROPOSAL` 이 함께 보일 수 있게 됐다 — 그 줄은 개정의 거절에서 난 것이며 `orchestration.md` §6 · §7.3 에 적었다. 둘 다 장부 수준 시험으로 댄다. 그리고 «다르면 개정으로 간다» 고 적은 자리를 «더 높으면» 으로 고쳤다 — 낮은 판은 개정이 지난 판으로 거절한다.

    그래서 소모 뒤 같은 열쇠에 새 제안이 서는 길은 이제 공개 경로에 없고, 판정 순서는 장부를 직접 부르는 `RemedyDeskTest` 로 댄다. 시험이 열일곱 늘었고, 겨눈 결함을 하나씩 넣어 그 시험이 이름을 대며 빨개지는 것을 봤다. 사유 산문의 «승인할 제안이 없다» 를 대는 기존 시험이 있어, 좁아진 뜻을 덧붙이면서 그 말은 살렸다.

    기록도 프로세스 수명과 같다(§15.166). 다시 뜨면 소모됐던 열쇠도 `NO_PROPOSAL` 을 받는다.

200. **사건 번들의 장부를 `IncidentLog` 로 뗐다 — 이로써 이 계획이 떼기로 한 판정과 장부는 다 뗐고, 남은 것은 핵심 상태기계와 공개 창구, 그리고 그것을 접합부에 잇는 조율이다.**

    봉인(`sealIncidents`)·조회·검토 지표와, `resolve` 가 사건에 운영자의 판단을 붙이던 줄(`noteResolution`)을 옮겼다. 봉인은 여전히 `pump` 가 라운드 끝에 부른다. 순서는 핵심에 남는다. 주인에게서 떨어져 있던 KDoc 하나를 `reviewMetrics` 위로 돌려놓았다. 떼어 낸 셋(`gate`·`desk`·`incidentLog`)은 `Middleware` 에서 한 자리에 선언된다.

    `SeamPlacementTest` 에 사건 장부를 더한 뒤, `Middleware.kt` 에 `incidentSeq` 를 다시 선언하는 주입으로 그 시험이 이름을 대며 빨개지는 것을 봤다.

    결과: `Middleware.kt` 2,149줄에서 1,512줄. ★**남은 것이 다 조용한 코드는 아니다.** §15.197 에서 잰 대로 구동·근거 결합·취소·통보는 9/17 이후 6% 만 바뀌었지만, 남긴 조율(`adopt`·`assign`·`liveExecutionCount`·`isHolding`·`reassign`·`approveRemedy`·`attemptApproval`·`commit`, KDoc 째 170여 줄)은 거의 전부 그 뒤에 쓰였다. 여섯은 `submit` 을 부르거나 실행의 상태를 바꾸고, 둘(`liveExecutionCount`·`isHolding`)은 `assign` 이 쓰는 비용 항이다(`reassign` 도 앞엣것을 쓴다). 앞의 여섯을 떼면 떼어 낸 클래스가 핵심을 되부른다. 그래서 순서를 파사드에 두었다. 재할당의 판정도 그 조율 안에 남았다. 피어 시스템 설계가 구현되면 다음 성장은 관문(`AdmissionGate`)과 이 조율 양쪽에 온다. 구동 루프는 쪼개지 않았다. 순서가 불변식이고 거의 안 바뀐다.

    push 전 정리 커밋이 공개 창구의 KDoc 에, 내부 장부로만 옮겨 갔던 계약 줄(탐색 순서 보증, `diagnose` 의 반환, 검토의 수동성, `since` 의 실 시계 기준)을 되살렸다. 그래서 `Middleware.kt` 는 1,532줄이다. 장부 안의 사건 목록은 `Middleware` 의 필드 `incidentLog` 와 이름이 겹쳐 `bundles` 로 바꿨다.

199. **제안과 승인의 장부를 `RemedyDesk` 로 뗐다 — 장부만 옮기고 조율은 남겼다.**

    상태 일곱(`proposals`·`withheld`·`diagnoses`·`proposalsMade`·`approvals`·`remedyLog`·`remedySeq`)과, 접수의 거절 경로에서 제안을 적던 `record` 를 함께 옮겼다. 그 일곱을 제안 절 밖에서 쓰는 자리가 `record` 하나뿐이었다.

    ★**승인 뒤의 접수는 `Middleware` 에 남겼다.** `approveRemedy`·`attemptApproval` 은 장부에 판정(`judge`)을 묻고, 서면 접수를 내고, 접수가 된 뒤에만 `settle` 로 장부에 적는다. 장부가 접수를 부르게 하면 떼어 낸 클래스가 핵심을 되부르는 순환이 생긴다. 콜백을 넘기는 대신 순서를 바깥에 둔 이유다. 관문이 거절하면 `settle` 을 안 부르므로 서 있던 제안을 지우지 않는 성질(ADR 44)도 그 순서에서 나온다. `settle` 이 기대는 것은 **주문의** 접수다 — 개정 경로는 조치 열 없이도 받아 주므로(§15.175, v1 도달 불가) 머리 주석을 «조치 열이 접수된 뒤» 에서 «주문이 접수된 뒤» 로 고쳤다. → «v1 도달 불가» 는 §15.201 에서 정정됐다.

    ★**장부는 관문의 `liveHold` 를 함수형 `val` 로 받는다 — 같은 이름의 `fun` 이 장부에 끼면 호출이 조용히 그쪽으로 간다.** 함수가 `invoke` 를 가진 속성보다 먼저 풀리기 때문이다. 늘 널을 돌려주는 `fun liveHold` 를 장부에 끼워 넣는 주입에서 `EntitlementTest` 는 자동 승인 갈래의 열 건이 빨개졌지만 `RemedyApprovalTest` 는 여섯 건 전부 초록이었다 — 사람의 승인은 값을 들고 오므로 관측을 다시 보지 않는다. 어느 갈래를 시험하든 잡도록 `SeamPlacementTest` 에 `fun liveHold(` 의 자리(`AdmissionGate.kt`)를 더했고, 같은 주입에서 그것이 빨개지는 것을 봤다.

    본문은 그대로다. 새 자리에서 못 찾은 줄은 계획서가 적어 둔 열둘이고, 인계본 네 벌은 실시계와 구동 식별자를 가리면 바이트까지 같다.

198. **배정 관문을 `AdmissionGate` 로 뗐다 — 피어 시스템 설계가 넓힐 관문의 판정이 이제 핵심 파일 밖에 있다.**

    `admits` 와 그 아래 술어 다섯(`inconsistent`·`chainRefusal`·`occupancyViolation`·`unownedFloor`·`workspaceViolation`), 보조 넷(`liveZones`·`zoneOf`·`liveClaims`·`liveHold`)을 옮겼다. 관문은 상태를 소유하지 않는다. 실행 표를 `Middleware` 가 든 **같은 인스턴스**로 받아 읽기만 한다. 사본을 받으면 관문은 생성 때 뜬 빈 표만 보고, 자리 경쟁·작업 구역·사슬 검사는 아무것도 막지 않는다. 재할당(`reassign`)의 판정과 채택(`adopt`·`assign`)의 순서는 조율이라 `Middleware` 에 남았다. 피어 시스템 설계가 들어오면 그쪽도 는다.

    `Middleware.admits` 는 파사드로 남았다. `orchestration.md` §2 와 ADR 42 가 그 이름으로 대고, §15.197 에서 조인 게이트가 §2 표의 그 이름을 주인과 같은 파일에서 찾는다. 이름과 본문은 그대로다. 새 자리에서 못 찾은 줄은 가시성이 바뀐 서명 둘, 관문으로 돌린 호출 셋, 생성자에서 `private val` 을 뗀 둘, 죽는 KDoc 링크 하나뿐이고, 인계본 네 벌은 실시계와 구동 식별자를 가리면 바이트까지 같다. 옮긴 `admits` 의 KDoc 에 있는 `[record]` 는 `record` 가 제안 장부(`RemedyDesk`)로 옮겨 갈 때까지 풀리지 않는다.

    ★**떼어 낸 것이 다시 모이는 것을 막는 시험을 먼저 세웠다.** 다시 한 파일로 합쳐도 다른 시험은 전부 초록이라 갈라 둔 것이 조용히 사라진다(§15.193 이 내보내기 세 벌에서 본 모양). `SeamPlacementTest` 가 출하 소스를 훑어 관문 다섯의 선언이 `AdmissionGate.kt` 에만, 정준 투영 셋이 `Canonical.kt` 에만 있는지를 댄다. 옮기기 전에 이 시험이 빨개지는 것을 보고 옮겼고, 같은 이름의 투영을 `Middleware` 에 다시 두는 주입과 `admits` 파사드를 지우는 주입으로 두 검사가 무는 것을 봤다. 못 보는 자리: 이 대조는 `picasso` 의 출하 소스만 훑고 `fun 이름(` 을 철자 그대로 찾는다. 제네릭·확장 함수·함수형 `val` 로 다시 적은 같은 이름은 못 보고, 보조 넷은 바늘에 없다.

197. **`Middleware.kt` 를 접합부로 나누기 전에 세 가지를 먼저 했다 — 게이트가 점 기호의 주인과 멤버를 같은 파일에서 찾게 했고, 여러 절이 나눠 쓰는 투영을 둘의 바깥으로 뺐고, 사건 절에 섞인 구동 보조를 구동 절로 옮겼다.**

    2026-09-26 에 절마다 줄이 언제 들어왔는지를 쟀다. 핵심 절(구동·근거 결합·취소·결과 통보, 857줄)은 9/17 이후 50줄만 바뀌었고, 새 접합부 절(접수 관문·제안과 승인·사건 번들, 1,036줄)은 604줄이 그 뒤에 쓰였다. 그래서 크기가 아니라 바뀌는 속도로 나눈다. 계획은 `docs/superpowers/plans/2026-09-26-middleware-seam-split.md` 에 있다.

    ★**게이트의 구멍이 먼저였다.** `orchestration.md` §2 가 `Middleware.admits` 를 대는데, 검사는 `Middleware` 와 `admits` 가 각각 어딘가에 있는지만 봤다. 관문을 떼고 나면 `admits` 가 새 클래스에도 있으므로 파사드를 지워도 초록이었을 것이다. 표의 `Middleware.submit` 을 `Middleware.dispatch` 로 바꿔(`dispatch` 는 `AmrFleetPort` 와 Orbit 어댑터에만 선언돼 있다) 초록인 것을 먼저 보였고, 주인과 멤버가 같은 파일에 선언돼야 통과하게 조인 뒤 같은 주입이 빨개지는 것을 봤다. 못 보는 자리는 검사의 주석에 적었다 — 한 파일에 선언이 여럿이면(`Ports.kt` 의 포트들) 멤버가 그중 어느 것의 것인지는 안 본다.

    정준 투영 셋(`canonicalClassOf`·`faultDetailOf`·`residualHoldOf`)을 `Canonical.kt` 로 뺐다. 분류는 통보·사건 번들·이벤트 자취가 다 쓰고 잔여 파지는 통보와 번들이 쓰며, 결함 투영은 번들만 쓰지만 분류를 `canonicalClassOf` 에서 받는다. 새 파일의 머리 주석은 처음에 셋을 세 곳이 다 쓰는 것처럼 적었다가 호출 자리를 세어 고쳤다.

    ★**절 머리를 절 끝 함수 위에 끼우면 그 함수가 조용히 새 절로 넘어간다.** 구동 보조 다섯 가운데 `settleExecution` 은 9/9 에 구동 절 끝에 쓰였는데, 그 위에 `// ── 근거 결합`(9/10)과 `// ── 사건 번들`(9/16) 머리가 차례로 들어오면서 두 번 딸려 갔다. 나머지 넷은 9/16 에 사건 절 안에 쓰였다. 다섯을 구동 절 끝으로 옮겼고, 이제 사건 절에는 사건의 것만 남아 사건 장부를 뗄 때 절을 통째로 옮길 수 있다.

    본문은 그대로다. 지운 줄 가운데 새 자리에서 못 찾은 것은 가시성이 바뀐 서명 셋뿐이고, 인계본 네 벌은 실시계와 구동 식별자를 가리면 바이트까지 같다.

196. **피어 시스템과 주고받을 것을 적었다 — 소유자를 못 정해도 계약까지 비워 둘 이유는 없었다.**

    `orchestration.md` 는 L1 과 L3 의 자리와 방향을 확정해 두었지만 **무엇을 어떤 순서로 주고받는지**는 비어 있었다. 비어 있는 경계는 «범위 밖» 이 아니라 미정의 경계이고, 연동하는 날 배치마다 다르게 채워진다. [피어 시스템과의 통신](2026-09-25-peer-systems-dispatch-and-space-design.md)이 그것을 적는다. 짓지는 않는다 — 소비자가 없다(ADR 9).

    **L1 은 새 규칙이 아니라 ADR 41 의 세 규칙을 선에 올린 것이다.** 순위는 밖, 채택은 tick 경계의 `adopt`, 전량 거부면 미배정이고 다시 묻지 않는다. 더한 것은 그 규칙이 프로세스 사이에서 새지 않게 하는 칸들이다 — 기한 없는 제안을 안 받고, 닫힌 질의의 늦은 제안을 적용하지 않고, **관련된 상태 변화를 가리키지 않는 재제안을 받지 않는다.** «아무 사건이든 뒤에 나왔으면 된다» 로 두면 바쁜 라인에서는 무관한 사건이 늘 있어 같은 순위가 tick 마다 되풀이된다. ADR 41 §5 가 배제한 것은 중재자 역할만이 아니라 그 고리 자체다.

    **L3 은 VDA 5050 3.0.0 의 구역 요청을 빌렸고, 한 자리에서 원문과 갈랐다.** 요청은 요청자 상태의 스냅샷 안에만 두고 반납은 항목이 빠지는 것이다. 원문에서 `STOP` 은 멈추는 순간 행동이 끝나 항목이 빠지지만, **구역 안에 멈춰 선 걷는 기체의 항목을 빼면 소유자는 그 자리를 비었다고 본다.** 그래서 `REVOKED` 보고는 «알았다» 이고 «비웠다» 는 항목이 빠지는 것 하나뿐이며, `STOP` 으로 선 기체의 항목은 운영자가 풀 때까지 남는다.

    ★**독립 리뷰가 다섯 바퀴 돌았고 첫 바퀴가 셋을 막았다.** 첫째가 위의 반납 규칙이다 — 처음 판은 «멈춘 것을 관측하면 `REVOKED`» 로 적어 원문 §6.9 가 확인과 반납을 가른 것을 접었다. 둘째는 «관문이 유일한 방벽» 이라는 문장이었다. **`admits` 는 그 기체가 스킬을 가졌는지 보지 않는다** — 사전 조건·점유·바닥·작업 구역을 볼 뿐이다. 그 거름은 후보를 만드는 자리에 있어야 하는데 **코어에는 그 자리가 없다**(`Negotiator` 는 `capability` · `registry` 에만 있다). 그래서 목록 밖 기체가 든 제안을 받는 즉시 거절하는 대조를 두고, 기체별 후보 거름을 구현 PR 의 첫 일로 적었다. 셋째는 인계 지점의 소유자를 «대개 플릿» 으로 적은 것이다. `FloorOwnership` 이 적은 대로 플릿은 자기 AMR 만 승인하므로 그 자리는 통로와 같은 구멍이다.

    **나머지 바퀴가 잡은 것은 모두 «코드가 실제로 돌려주는 모양» 이었다.** `adopt` 는 첫 통과에서 돌아가 앞 후보의 거절을 남기지 않는다 — 결과 모양을 그대로 적었다. 점유 거절은 기체를 바꿔도 답이 같지만 자리가 비면 풀린다 — 그래서 질의를 닫는 것은 **범위가 아니라 후속 조치**다. `adopt` 경로의 사슬 거절은 조치 제안을 기록하지 않는다 — 그 자리에는 기다리는 사람이 없다. 대장을 붙이지 않은 배치(`NotDeclared`)에서 `unownedFloor` 는 아무것도 막지 않는다 — 통로에는 이미 내린 결정이지만, 새로 여는 직접 인계는 소유자가 **선언되지 않으면** 접수하지 않는 쪽으로 갈랐다.

    **못 한 것을 적는다.** 소유자는 여전히 현장의 결정이다(§15.154). 본 저장소가 구간 소유자가 될 때 내놓을 관문은 이 문서의 대상이 아니므로 §15.162 도 그대로 열려 있다. L1 을 떼는 날에는 제안 창구라는 **밖에서 들어오는 쓰기**가 생기므로 §7.4 대로 본 계층을 세우는 측이 그 창구와 상태 피드를 함께 낸다.

195. **대장의 묵은 행을 다시 쟀다 — 셋이 거짓이었고, 그중 하나는 열다섯 문서가 떠받치고 있었다.**

    §15.194 를 닫으며 적은 권고의 첫 항목이다. 같은 날 연 셋이 **전부** 거짓 전제였으니 나머지에도 있다고 보는 것이 맞았고, 예순한 행 중 **이 저장소의 코드로 판정되는 것**을 전부 훑었다.

    ★★**§15.125 는 이미 닫혀 있었다.** 행은 *"대외 문서의 구조적 진술 자동 검증 테스트 부재"* 라 적고 조건으로 *"모듈 의존성 선언을 파싱하여 아키텍처 문서와 상호 대조하는 테스트 구현"* 을 걸어 두었는데, `DocumentClaimsTest` 의 «모듈 의존 그림이 빌드와 같다» 가 각 `build.gradle.kts` 의 출하 의존을 파싱해 `architecture.md` §4b 의 표와 대고, `ComponentMapTest` 가 같은 그래프를 구성도의 간선과 **양방향으로** 댄다. 조건의 문장 그대로다. 그리고 `architecture.md` §4b 자신이 *"본 다이어그램과 의존성 표는 임의 작성된 산문이 아니며 … `DocumentClaimsTest`가 직접 파싱하여 정합성을 검증합니다"* 라고 적고 있었다 — **문서가 자기를 받치는 시험을 이름으로 부르는 동안 대장은 그 시험이 없다고 적고 있었다.**

    ★**값이 큰 까닭은 인용 수에 있다.** 열림 표기는 「이 문서가 이 한계 위에 서 있다」는 선언인데 §15.125 를 든 문서가 **열다섯**이었다. 닫힌 한계를 인용하는 문서는 **낼 수 있는 주장을 스스로 깎고 있는 것**이다. 걷어 내자 열림 표기가 **137 에서 122 로** 내려갔다 — 저장소가 밖에 대고 「아직 못 받친다」고 말하던 것의 11% 가 근거 없는 것이었다.

    **둘은 절반이 거짓이었다.** §15.38 은 *"레지스트리 HTTP API 엔드포인트의 인증 체계 부재"* 라 적혀 있었는데 쓰기에는 문이 둘이고 토큰이 서로 다르며 빈 토큰은 401 이다(`IngestToken`·`OperatorToken`). **일지 §15.38 자체는 그것을 정확히 적고 있었고 대장만 안 따라갔다** — 남은 것은 읽기 전용 진단 여섯뿐이다. §15.14 는 *"범위 축소를 경고 수준으로만 처리"* 라 적혀 있었는데 §9.3 의 진입 조건을 못 채운 축소는 **이미 `Severity.ERROR`** 다. 경고로 남는 것은 원장이 없거나 원장이 관측하지 못했을 때뿐이고, 그것은 「막을 근거가 없다」이지 「괜찮다」가 아니므로 다른 한계로 다시 적었다.

    **다시 재는 동안 대장에 없던 것이 하나 나왔다.** 하트비트 신선도 24시간 상수가 `RegistryLedgerQuery` 와 `RobotObservability` 에 **각각** 선언돼 있고 `DiagnosticsService` 는 뒤엣것을 건너가 읽는다. §15.115 가 적은 「두 벌로 두면 어느 날 한쪽만 는다」이고 §15.193 에서 오늘 닫은 것과 같은 모양이라 §15.41 행에 적었다.

    ★**다시 재는 일에도 같은 병이 있다.** §15.18(검사 6 의 필드 매칭)을 한 번 거짓으로 판정했었다 — 검사 6 의 주석이 *"(skill_type, major)로 짝지으면 major 증가를 «삭제 + 추가»로 오해한다"* 고 적어 둔 것을 보고 **조건이 코드가 거부한 방향을 가리킨다**고 읽었다. 다른 코드 경로였다. 그 주석은 개정 사이의 스킬 짝짓기이고 §15.18 이 말하는 것은 `optional_fields` 의 **접미사 일치**(`path.endsWith(".$it")`)이며, 그것은 지금도 두 자리에 그대로 있다. **이름이 비슷한 자리를 보고 다 봤다고 하는 것** — 다시 재기가 막아야 할 바로 그 모양이다.

    **못 댄 자리를 적는다.** 소비자 대기 여덟 중 둘(§15.181·§15.180)은 받는 쪽 채점기의 사실이고, 외부 스물넷은 대부분 실물의 사실이라 이쪽에서 잴 것이 없다. 이번에 댄 것은 **코드로 판정되는 행**뿐이며, 참으로 남은 것들은 실측으로 확인했다 — `revise` 는 여전히 조치 열 인자를 안 받고, 구독기는 없고(`uplink` 의 것은 발행자다), `SiteBindingSource` 구현은 `None` 뿐이고, `Entitlement` 에 범위 변경 이력이 없고, `IncidentBundle` 에 `attempt`·`rechecks` 가 없으며 `lateEvents` 는 인계본 네 벌에서 전부 비어 있다.

194. **대장의 갈래가 여드레 동안 조용히 어긋나 있었다 — 갈래의 뜻이 「누가 닫는가」인데 그것을 아무도 안 댔다.**

    한계 대장은 갈래를 셋으로 두고 있었다. 의도적 제외, 내부 미결(이 저장소가 지으면 닫힌다), 외부 미결(실물이 와야 닫힌다). 그런데 내부 미결 마흔한 줄 중 **열셋의 해소 조건이 밖의 주어를 대고 있었다** — 「받는 쪽이 지표에서 제외할 때」, 「읽는 쪽이 그 칸으로 무엇을 말할지 정해질 때」, 「소비자가 생기기 전에는 짓지 않음」. 지으면 닫힌다고 적은 칸에 **지어도 안 닫히는 것**이 앉아 있었다.

    ★**그 열셋이 한 갈래가 아니었다.** 일곱은 밖의 소비자가 무엇을 원하는지 말해야 열리고(ADR 9 가 막고 있는 자리다 — 지을 수는 있는데 쓸 곳이 없어 안 지은 것), 다섯은 실물·벤더가 와야 열리며, 하나는 애초에 미결이 아니라 ADR 35 의 트레이드오프였다. 앞엣것은 기존의 두 갈래 어디에도 안 맞는다 — 「현장 실증 및 상위 연계 프로젝트 진행 시 종결」이 아니라 **밖이 요구를 들고 오는 순간** 열리고, 그때 소비자와 구현체가 같은 변경에 들어온다. 그래서 갈래를 넷으로 늘리고 이름을 이미 있는 어휘에서 가져왔다(ADR 9 의 소비자 존재 원칙). 재편 뒤 내부 29 · 소비자 대기 8 · 외부 24 · 의도적 제외 13 이다.

    ★★**갈래를 다시 나누는 것만으로는 안 끝난다.** 어긋남이 생긴 까닭은 **주어가 산문에만 있고 갈래는 절 머리에만 있어서** 둘이 따로 적히기 때문이다. 다시 나눠 놓아도 다음 행이 같은 자리로 돌아온다. 그러니 해소 조건의 낱말을 읽어 댄다 — 내부 미결이 「받는 쪽·읽는 쪽·소비자·상류·실물·벤더·현장」을 부르면 실패하고, 소비자 대기가 「실물·벤더·현장」을 기다리면 실패한다. 대장을 한 줄도 못 읽으면 그것도 실패다.

    **낱말을 세 줄 고쳤다.** 갈래는 맞는데 낱말이 걸리는 행이 둘 있었다 — §15.156 은 조건의 주어가 소비자인데 붙을 자리를 「현장 정본」이라 불렀고, §15.190 은 이 저장소가 안내문을 다시 추적하면 닫히는데 대안 절에 「받는 쪽」이 들어 있었다. 대조에 걸린 김에 주어가 문장 앞에 오도록 고쳤다. **대조가 산문을 또렷하게 만든 것이지 산문을 이긴 것이 아니다.**

    **못 잡는 쪽을 적어 둔다**(§15.194 가 대장에 남는 이유다). 낱말 없이 밖에 기대는 행은 이 대조가 못 본다 — §15.174 처럼 「§15.143 계열」이라고 다른 항목만 가리키는 행이 그렇다. 그런 행의 갈래는 사람이 계열을 따라가야 안다. §15.185 가 용어집에서 적은 것과 같은 모양이고, 같은 답을 적는다: **반을 막는 대조가 아무것도 안 막는 것보다 낫고, 그 반이 지금까지 시끄러웠던 쪽이다.**

193. **내보내기 절차를 한 벌로 합쳤다 — 막고 있던 「요약이 흔들린다」는 재 보니 사실이 아니었다.**

    §15.186 이 대장에 오르며 미루는 값을 적어 뒀다: 합치면 **인계본의 아홉 요약이 바이트로 흔들릴 위험**이 있으니 인계본을 어차피 다시 낼 때 하자는 것. 그래서 엿새를 묵었다.

    ★★**그 위험이 없었다.** 요약은 `IncidentBundle.digest()` 가 내고 줄은 `LedgerExport` 가 적는데, 갈려 있던 것은 **그 뒤에 오는 담는 절차**다 — 디렉터리를 만들고, 임시 이름으로 쓰고, 이름을 바꾸고, 안내를 마지막에 둔다. 인코더를 한 글자도 안 건드리므로 요약이 흔들릴 경로가 애초에 없다. 합치기 전에 네 벌의 요약을 받아 두고 합친 뒤에 다시 받아 댔다 — 9·9·4·3 이 전부 같고, 커밋된 인계본의 것과도 같다. **엿새를 막은 것은 대가가 아니라 안 재 본 전제였다**(§15.191 과 같은 병이 오늘만 두 번째다).

    `LiveExport` 를 `dev.picasso.middleware.BundleWriter` 로 옮기고 세 자리가 그것을 부른다. 픽스처 쪽이 창구 쪽의 고침 둘(식별자 고정·안 늘면 안 씀)을 함께 얻는데 **둘 다 픽스처의 산출을 안 바꾼다** — 한 벌마다 쓰개를 새로 만들면 건너뛰기가 걸릴 일이 없고 식별자는 원래 벌마다 달랐다. 자리와 식별자가 **생성 인자**라 한 쓰개가 두 벌을 쓸 길이 없는 것이 그 성질의 근거다.

    ★**합치는 것만으로는 안 끝난다.** 셋이 갈려 있어도 각자 제 시험으로 초록이었고, 그래서 **갈림 자체에는 아무도 안 빨개졌다.** 합치기만 하면 다음 사람이 급할 때 또 적는다. 그러니 소스를 훑어 **절차가 한 자리뿐인지를 댄다.** 바늘은 둘이다 — 원자적 이름 바꾸기와 안내를 짓는 부름. 앞엣것만 보면 원자적이지 않게 다시 적은 쓰개가 안 보이고, 한 벌을 내려면 안내는 반드시 지어야 하므로 그쪽이 새는 것을 막는다. 목록은 양방향으로 대고, 아무것도 못 찾으면 실패다. 바늘은 조각으로 이어 적었다 — 통째로 적으면 이 시험이 저를 잡는다(검사 11 과 같은 덫).

    주입 둘이 다 물었다. `ExportFixtureTest` 에 절차를 다시 적으니 그 파일 이름을 대며 빨개졌고, 원자적 이름 바꾸기를 뺀 쓰개도 안내를 짓는 바늘에 걸렸다.

    **못 보는 자리를 적어 둔다.** 이 대조는 **이 모듈의 소스만** 훑는다. 다른 모듈이 한 벌을 내는 날에는 훑는 자리를 넓혀야 하고, 그 순간은 바늘이 아니라 사람이 알아챈다.

192. **G1.4 를 게이트로 들었다 — 막을 것은 이름이 아니라 밖으로 나가는 문이었다.**

    「picasso 는 설명 층을 모른다」의 음성 시험이 9/17 부터 「정할 것」으로 남아 있었다. 미루던 근거는 §15.175(만들 수 없는 상황을 막는 검사는 두지 않는다)였는데, **그 근거가 여기엔 안 맞았다.** 출하 소스에 아웃바운드 HTTP 가 이미 있고 주소는 전부 밖에서 주입받는다 — 설명 층을 부르는 코드를 **지을 수 있고**, 지금 그것을 잡는 것이 하나도 없었다.

    ★★**이름으로 막으려던 첫 설계를 버렸다.** 금지어를 `narrator` 로 두면 `uplink` 처럼 `baseUrl` 을 받는 순간 그 낱말이 소스에 한 번도 안 나타난다. **현실적인 위반을 정확히 못 잡는 검사**가 된다. 막아야 하는 것은 이름이 아니라 **문이 열리는 지점**이고, 그것은 셀 수 있다 — 지금 다섯이다(`uplink` 넷이 제 `registry` 로, `adapter-orbit` 하나가 벤더 플릿으로).

    그래서 **닫힌 목록**으로 두고 양방향으로 댄다. 목록에 없는 자리가 생기면 실패하고, **목록에 있는데 더는 문을 안 여는 자리가 있어도 실패한다** — 뒤쪽이 없으면 목록이 낡아 옛 이름 아래 새 호출이 조용히 들어올 자리가 남는다. 목록을 손으로 적는 것이 여기서는 의도다(검사 7·9 는 반대로 유도한다). 아웃바운드는 스스로 늘 이유가 없는 종류라, 늘리려면 사람이 그 줄을 고치게 두는 것이 목적이다.

    ★**짓는 동안 덫 셋을 밟았고 셋 다 저장소가 이미 적어 둔 것이었다.**

    첫째, KDoc 에 `/ingest` 뒤에 별표를 붙였다가 **중첩 주석이 열려** 파일이 안 닫혔다. `ClaimSurface` 의 KDoc 이 그 덫을 글로 적어 두고 있다 — 적어 둔 것을 읽고도 밟았다.

    둘째, **검사가 저를 잡았다.** 바늘 문자열이 제 소스에 그대로 있어서다. 예외 목록을 두는 길이 있었지만 그 자리가 곧 구멍이라, 바늘을 조각으로 이었다. 누가 다시 붙이면 검사가 저를 잡아 빨개지므로 조용히 안 깨진다.

    셋째, 저장소를 통째로 걸었더니 **음성 하네스의 겹침 파일이 같이 잡혀 「원본 트리는 모든 검사를 통과한다」가 빨개졌다.** 제 음성 케이스에 제가 걸린 것이다. 검사 7 이 모듈을 하나씩 여는 까닭이 이것이었고, 같은 방식으로 고쳤다(최상위에서 `src/main` 을 든 디렉터리가 곧 모듈이라 목록은 안 적는다).

    **id 는 11 이다.** 10 은 음성 하네스가 쓴다. `InputCollectorTest` 가 「검사가 열을 넘는 날 문자열 정렬이 먼저 빨개진다」고 예고해 뒀고 그대로 빨개졌다 — 수로 세우게 고쳤다.

    (의도적 제외로 옮김, §15.265) «§15.192» 행은 picasso 와 picasso-ops 어디에도 그 값을 쓰는 면이 없어 의도적 제외의 새 표 «수요 없음» 으로 옮겼다.

191. **검사 7 의 사각지대를 닫았다 — 엿새 동안 막고 있던 것은 거짓 전제였다.**

    검사 7(기종 좌표가 공용 계층에 새는 것을 막는다)이 모듈 여덟만 보고 `registry` 와 `profile-model` 을 안 봤다. AGENT-03 이 9/17 에 지적했고 그 뒤로 「정할 것」으로만 남아 있었다. 막고 있던 이유는 **「registry 본 소스에 Spot·Digit·Unitree·Orbit 이 여섯 파일에 있어 넣으면 빨개진다」**였다.

    ★**그 전제가 틀렸다.** 검사 7 의 바늘은 프로파일의 `vendor`·`model` 에서 파생되고, **하이픈이나 숫자가 없는 것은 바늘이 되지 못한다**(그 자체로 실패시킨다) — `spot`·`digit` 같은 일반어는 애초에 찾는 대상이 아니다. 실제 바늘(`unitree-g1`·`boston-dynamics`·`agility-digit` 등 열)로 다시 세니 **두 모듈 다 0 이었다.** 여섯 파일은 `digits`·`spotted` 같은 산문에 넓은 grep 이 걸린 것이다.

    **엿새를 막은 것은 고칠 일의 크기가 아니라 잘못 센 수였다.** 넣는 데 든 것은 목록 한 줄과 설계 문서 세 자리이고 고칠 소스는 없었다. 그리고 나도 처음 셀 때 같은 넓은 grep 으로 10 을 얻었다 — 같은 덫을 같은 날 두 번 밟았다.

    ★**주입으로 실물을 확인했다.** 단위 시험(`Check07ModelBranchingTest`)은 임시 골격을 쓰므로 진짜 저장소를 안 본다. 저장소를 훑는 것은 게이트 CLI 이고, `registry/src/main` 에 `unitree-g1` 을 넣고 CLI 를 돌려 파일과 행까지 짚는 것을 봤다. 넣기 전에는 그 줄이 보이지 않던 자리다. 처음 민 주입은 **시험 이름을 틀려 아무것도 안 돌았다** — 규칙 ①이 ②보다 먼저인 이유다.

190. **인계 지점의 산문을 추적에서 뺐다 — 그리고 「없음」이 조용히 초록이 되지 않게 했다.**

    사용자가 인계 지점의 산문을 공개하지 않기로 정했다. 왕복 편지 다섯과 안내문이 추적에서 빠지고, **기계가 읽는 한 벌은 남는다** — `run-*/*.jsonl` 과 `entitlements.json` 과 `ground-truth.jsonl` 은 시험 다섯 클래스가 대조하기 때문이다.

    ⛔**이미 공개돼 있다.** `09d6891` 이후 22 커밋에 걸쳐 `origin/main` 에 올라가 있고, 추적을 끊는 것은 **앞으로를 막을 뿐 이미 나간 것을 되돌리지 않는다.** 이력 재작성은 안 하기로 했다 — 병합된 PR 의 링크가 깨지고, 이미 클론한 쪽에는 어차피 남으며, 내용이 합성 데모 데이터와 작업 기록이라 긴급하지 않다.

    ★**물을 때 내가 사실을 틀렸다.** 「시험이 읽는 것은 데이터뿐」이라고 적고 물었는데 **안내문도 시험 하나가 읽는다**(`안내문이 대는 구동 식별자가 커밋된 한 벌의 것과 같다`). 하필 그 시험은 내가 낸 runId 오류를 잡았던 자리다. 선택지를 내면서 그 선택지의 대가를 덜 적은 셈이고, 결정 뒤에 정정했다.

    ★★**「없다」를 그냥 통과시키지 않는다.** 안내문이 없는 기계에서 시험을 건너뛰게 하면 **실수로 지운 것과 모양이 같아져** 그 시험이 조용히 참이 된다. 이 저장소에는 조건부 건너뜀의 전례가 없어서, 건너뛰는 자리에 **무시 목록이 그 파일을 이름으로 드는지** 보는 단언을 뒀다. 세 경우를 다 돌려 확인했다 — 있으면 대조하고, 없고 목록에 있으면 건너뛰고, **없는데 목록에도 없으면 빨개진다.**

    남는 것은 대장에 적었다(§15.190). 안내문이 낡는 것은 이제 **고치는 기계에서만** 잡힌다. 낡음이 나는 자리가 곧 고치는 자리라 그 자리에서는 잡히지만, CI 는 못 본다.

189. **대외 문서가 삼백 넘게 틀린 수를 들고 나가고 있었다 — 세는 자리를 붙였다.**

    `verification.md` 가 「1,480여 개 자동화 테스트」에서 멈춰 있었다. 실측은 1,817 이다. 9/19 상태 점검이 적은 권고 넷 중 유일하게 넘어온 미완이고, **이 파일의 머리가 «이 저장소가 반복해 물린 자리» 라 적은 그것이 대외 문서에서 또 난 것**이다. 수를 고치고 **세는 대조를 붙였다.**

    ★**`CLAUDE.md` 도 같이 걸었다.** 그쪽은 값이 맞았지만 **맞는 이유가 내가 그때그때 손으로 고쳤기 때문**이지 대조가 있어서가 아니었다. 맞는 수와 지켜지는 수는 다르다.

    ★★**소스의 `@Test` 를 센다 — 실행 결과가 아니다.** 결과(XML)로 세면 Docker 가 없는 기계에서 `harness`·`registry` 가 통째로 빠져 527 이 준다. 그러면 **같은 문서가 기계마다 다른 값을 요구한다.** 두 방법이 지금은 같은 값(1,817)을 내지만 같은 값을 내는 것과 같은 것을 재는 것은 다르다.

    ★**만들면서 덫 둘을 밟았다.**

    첫째, 저장소를 통째로 걸으면 `.claude/worktrees/` 의 사본이 같이 세어진다 — 시험 소스만 157 파일이다. 그 사본은 CI 에 없으므로 **기계마다 다른 답을 내는 대조**가 된다. 같은 구멍에 `mainSource` 가 이미 물려 있었고(기호 실재 검사가 그만큼 헐거웠다) 같이 막았다.

    둘째, `modules()` 가 `picasso` 를 **두 번** 돌려준다. `substringAfter("rootProject.name")` 가 그 값까지 남기기 때문이다. 집합으로 대는 쪽은 안 물리지만 **더하는 쪽은 그 모듈이 통째로 두 번 세어진다** — 2,081 이 나왔다. 호출하는 자리에서 `distinct()` 로 막았다. 같은 헬퍼가 어떤 쓰임에서는 멀쩡하고 어떤 쓰임에서는 틀린다.

    주입 넷 전부 물었다(두 문서의 수, 낡은 정규식, `distinct()` 제거). 정규식 주입이 처음엔 안 들어갔는데 치환 문자열의 `` 가 파이썬 이스케이프로 먹혀서였다 — 규칙 ②가 ④보다 먼저인 이유다.

188. **사람의 걸음을 사실로 실었다 — 데이터 옆에 산문으로 들고 다녀야 하는 사실은 데이터에서 빠진 사실이다.**

    읽는 쪽이 물었다. `run-4` 의 길은 사건 → 사람의 재작업 → 탐색인데 번들도 탐색 줄도 그 걸음을 안 실어, 설명이 재작업을 말하면 지어내는 것이 된다고. 모양은 안 골랐고 「안 실어도 답이다」라고 적어 왔다.

    ★**먼저 읽는 쪽의 전제 하나가 틀렸다.** 「탐색 줄에 주문과 시각이 없다」고 했는데 `jobOrderId` 와 `at` 은 **네 벌 모두의 모든 탐색 줄에 이미 나가고 있다.** 없는 것은 그쪽 질의의 투영이지 인계본의 칸이 아니었다. 「어느 사건 뒤에」도 기체와 시각으로 이미 이어진다 — 그쪽 골든셋 시험이 바로 그 규칙으로 짝을 잡게 고쳐진 참이었다.

    ★★**그리고 재작업은 이미 나가고 있었다 — 조건부로.** `resolve` 가 `attempt` 를 올리고 `note` 에 적고, 다시 도는 단위는 **새 정체성** `@r1` 을 받아 근거 창에 그대로 나간다. `run-3` 의 둘째 사건이 그 자리다. **다만 재작업 뒤에 그 단위가 다시 깨졌을 때만 보인다.** `run-4` 는 재작업 뒤에 아무 일도 안 나서 안 보인다. 그리고 `@r1` 의 뜻은 설계 §13.3 에 적혀 있는데 **인계 안내문에는 없었다** — 읽는 쪽이 읽는 자리에 없으면 없는 것이다(§15.185 와 같은 모양, 넷째).

    **그래서 실었다.** `IncidentResolution`(판단·가상 시계·실 시계)을 사건에 붙이고 조회 판을 4 에서 5 로 올렸다. ADR 40 이 막는 것은 **새 사실을 짓는 것**인데 이 걸음은 이미 이 층의 값이라 옮겨 싣는 것이고, ADR 9 의 소비자도 이름을 대고 왔다.

    ★★**요약은 안 바뀐다.** `review` 와 같은 규칙으로 해시에서 뺐다 — 나중의 사람 걸음이 그 사건을 **다른 사건으로 만들지는 않는다.** 무엇이 깨졌나는 봉인 시점에 끝났다. 덕분에 네 벌 스물다섯 요약이 한 자도 안 변했고, 읽는 쪽이 판 4 로 고정해 둔 멱등 열쇠가 그대로 산다. 해시에서 빠지는 것이 둘에서 셋이 됐고 그 목록은 KDoc 이 든다.

    ★**이것을 짓게 한 문장은 내가 이미 쓴 경고였다.** `run-4` 를 내면서 인계 안내문에 「이 짝은 사건 → 사람의 재작업 → 탐색이다, 설명에서 그 걸음을 빼면 안 된다」를 산문으로 적었다. **데이터 옆에 산문으로 들고 다녀야 하는 사실은 데이터에서 빠진 사실이다.** 경고를 적는 손이 곧 빠진 칸을 가리키고 있었는데 그때는 안 보였다.

    곁다리로 용어집에 §7 을 세웠다. 읽는 쪽 답들이 **사건이 거듭 난 수**를 용어집의 **반복 승인 지표**(같은 걸음 열이 거듭 승인된 사실)로 읽고 있었다. 용어집이 그 쌍의 한쪽만 들고 있었기 때문이다 — 승인이 한 번도 없는 기체에서 재발 수가 3 으로 나오자 그 접힘이 드러났다. 재발은 **읽는 쪽이 세고 이 층은 세지 않는다**(ADR 40)는 것까지 같이 적었다.

187. **사건 뒤에 탐색이 서는 한 벌을 냈다 — 그리고 그 길에 사람이 한 걸음 들어 있었다.**

    읽는 쪽이 §15.186 의 답을 받고 **쓰겠다**고 했다. 회복 설명을 표본으로 재려면 사건 옆에 탐색이 있어야 하는데, 지금 한 벌들은 순서가 반대라 사건 아홉에 짝이 하나였다. `run-4` 가 그 자리다 — 사건 셋, 탐색 둘, 그중 하나는 가려진 답이고 사건 하나는 짝이 없다.

    ★★**내가 앞서 적은 길이 그대로는 안 섰다.** §15.186 에 「실행 중 실패가 잔여 파지를 남기고 그 기체에 새 주문이 들어가면 사슬 검사가 막는다」고 적었는데, 그대로 해 보니 주문이 **접수됐다.** 이유는 §15.150 이 이미 적어 둔 것이다 — `OPERATOR_HOLD` 에는 **도는 단위가 없어 이 층이 파지를 못 본다.** 조건을 못 재니 막을 근거가 없고, 그래서 통과한다. 내가 그 절을 근거로 답을 적으면서 그 절이 말하는 바로 그 제약을 안 적용했다.

    사람이 **재작업**을 내면 새 태스크가 서고 잔여 파지가 다시 관측에 들어온다. 그때 빈손을 요구하는 주문이 막히며 제안이 선다. 그러니 이 짝은 「사건 → 탐색」이 아니라 **「사건 → 사람의 재작업 → 탐색」**이다. 인계 안내문에 그 걸음을 적었다 — 빼고 설명하면 읽는 쪽이 자동으로 도는 회복이라 읽는다.

    **§15.150 은 여전히 안 열었다.** 탐색은 접수 관문에서만 돈다. 바뀐 것은 시나리오의 순서뿐이고 계약도 발신자 거절 경로도 안 건드렸다.

    ★**주입 넷 중 하나가 등가 변이였다.** 사건과 탐색이 같은 초에 앉지 않게 시계를 미는 줄을 빼 봤는데 아무것도 안 빨개졌다 — 재작업을 기다리는 구동이 이미 한 걸음 이상 시계를 밀기 때문이다(실측: 뺀 판에서도 사건 `00:00:01`, 탐색 `00:00:02`). **시험의 구멍이 아니라 그 줄이 안 짊어지고 있었다.** 그래도 남겼다 — `drive` 는 조건이 첫 pump 에 참이면 **시계를 한 번도 안 밀고 돌아오므로**, 미믹이 빨라지면 둘이 같은 초에 앉을 수 있다. 커밋된 한 벌은 재현이 담보여야 한다. 나머지 셋(탐색을 사건 앞으로, 짝 없는 사건 제거, 가린 답을 선 답으로)은 다 물었다.

186. **한 벌과 창구가 같은 제안을 들려면 같은 미들웨어가 둘 다 냈어야 한다 — 그리고 구동 식별자가 만 개였다.**

    읽는 쪽이 승인 시도를 한 바퀴에 물린 뒤 편지를 쓰다 대 보고 **자기가 틀린 자리를 찾았다.** 인계본 `run-1` 의 `search-1` 은 `hum-02` / `PATROL-1` 인데 창구에 서 있는 자리는 `hum-02` / `PATROL-APPROVES` 다. 인계본을 그대로 창구에 대면 첫 줄부터 `NO_PROPOSAL` 이 온다 — **거짓이 아니다.** 창구에 그 제안이 없는 것이 사실이다. 그러나 「알려진 조치로 자동 회복」을 실물로 보이지는 못한다.

    ★**다리를 놓을 일이 아니었다.** 창구가 한 벌의 탐색 대장을 읽어 그 자리를 세우는 길이 떠오르지만, **제안은 이 층이 계산한 것이지 밖에서 넣는 것이 아니다**(§15.150·ADR 44). 창구에 「이 제안이 있다고 쳐라」를 말할 수 있으면 그 순간 제안의 출처가 밖이 되고, 승인 표면이 일반 접수 표면으로 바뀐다. 한 벌과 창구가 같은 제안을 드는 조건은 하나뿐이다 — **같은 미들웨어가 둘 다 냈을 것.** 그 한 벌은 이미 있었다. 창구가 도는 내내 `build/export/live` 에 내보내고 있었고, 아무도 그것이 되돌려 댈 자리라고 안 적었을 뿐이다. 배너와 인계 안내에 적었다.

    ★★**그런데 그 한 벌이 읽는 쪽의 거름망을 죽이고 있었다.** 읽는 쪽은 「같은 구동의 같은 줄에는 한 번만 부른다」로 승인을 거르는데, 구동 식별자를 **내보낼 때마다 새로 찍고 있었다** — 30분 돌린 판에서 `-10930` 까지 갔다(실측). 같은 한 벌을 다시 훑을 때마다 다른 구동으로 보이므로 승인을 또 시도하고, 승인은 제안을 소모하므로 두 번째는 거절로 온다. 그러면 **일어나지 않은 거절이 대장에 쌓인다.** 식별자를 바퀴마다 하나로 고정했다.

    같은 자리에서 하나 더. 두 대장이 안 늘었는데도 150밀리초마다 세 파일을 다시 썼다. append-only 라 **줄 수가 곧 변경 신호**이므로 안 늘면 안 쓰게 했다 — 읽는 쪽이 세 파일을 한 벌로 집을 때 파일마다 다른 순간을 보지 않는다. `LiveExport` 로 떼어 시험 셋을 붙였고 주입 셋이 다 물었다.

    ★**「누가 되돌려 댈 한 벌인가」를 아무도 안 적은 것이 이 건의 뿌리다.** 인계본은 인계본이라 적혀 있고 살아 있는 한 벌은 「두 대장」이라고만 적혀 있었다. 읽는 쪽이 인계본을 고른 것은 **그것만 이름이 붙어 있었기 때문**이다. §15.185 와 같은 모양이다 — 있는데 안 적힌 것은 없는 것과 같이 쓰인다.

    **회복 설명의 표본은 여전히 하나다**(읽는 쪽 관측). 탐색이 접수 관문에서만 돌아(§15.150) 사건 아홉 중 짝이 하나다. 이것은 §15.150 을 여는 일이 아니다 — 여는 것은 계약에 관측을 실을 자리가 생겨야 하고 소비자가 없다. **다만 짝을 하나 더 만드는 것은 그것을 안 열고도 된다**: 실행 중 실패가 잔여 파지를 남기고, 그 기체에 새 주문이 들어가 사슬 검사에 걸리면 사건 **뒤에** 탐색이 선다. 지금 한 벌들은 그 순서가 반대일 뿐이다(탐색이 먼저, 사건이 나중). 요청이 아니라 관측으로 왔으므로 짓지 않고 적어 둔다.

    내보내기 절차가 이제 **세 벌**이다(`ExportFixtureTest`·`LedgerExportTest`·`LiveExport`). 창구 쪽만 식별자 고정과 건너뛰기를 얻었으니 **이미 한 벌이 갈렸다.** 합치면 인계본의 아홉 요약이 흔들릴 위험이 있어 지금 안 합치고 대장에 적었다(§15.186). **(닫힘, §15.193 — 그 위험은 재 보니 없었다.)**

185. **용어집이 자기에게 안 물어본 낱말을 못 막았다 — 「승인 입」을 「승인 창구」로 고치고 옛 이름은 남겼다.**

    읽는 쪽에서 관측이 왔다. 사람이 그쪽 문서를 읽다가 **«입이 뭐냐»** 고 물었고, 답하려다 이 낱말이 `docs/glossary.md` 를 한 번도 안 거쳤다는 것이 나왔다. 그 문서는 스스로를 «용어 불일치를 막는 단일 진실의 원천» 이라 적어 둔 문서다.

    ★**실측이 양쪽에서 갈렸고, 그 갈림 자체가 증거다.** 이쪽에서 이 말은 일곱 자리에 있고 전부 「승인 입」 꼴이라 셀 수 있었다. 저쪽은 홀로 선 「입」으로 써서 세다가 `입력`·`도입`·`삽입`·`입니다` 가 함께 걸린 수를 얻었다. **같은 낱말이 붙여 쓰느냐 떼어 쓰느냐로 수가 열 배로 갈린다면 그 낱말은 한 음절로 값을 못 한다.** 못 세는 낱말은 나중에 바꾸기로 해도 어디를 바꿀지 모른다.

    ★★**그런데 한 낱말이 아니었다.** 세어 보니 용어집의 표제어 스물여덟 중 **승인 표면의 것이 하나도 없었다** — `ADR 43`(자격은 밖에서 선언하고 안에서 판정) · `ADR 44`(승인은 제안을 가리킨다) · `ADR 45`(철회는 표시다) 가 고정한 어휘가 통째로 그 문서를 비켜 갔다. 낱말 하나만 고쳤으면 **같은 결함을 더 작은 규모로 되풀이하는 것**이었다. §6 을 세워 창구·자격·철회·거절 사유를 함께 올렸다.

    ★★**그리고 용어집은 SSOT 라고 스스로 적은 유일한 문서이면서 대조가 하나도 없던 유일한 문서였다.** `CLAUDE.md` §3 의 표에 있는 나머지 — 교체 지점·계약·ISA-95·설정 표면·README — 는 전부 `DocumentClaimsTest` 가 코드에서 다시 센다. 용어집만 안 셌다. **막는 물건을 두는 것과 그 물건을 지나가게 하는 것은 다른 일이다.**

    그래서 대조 둘을 세웠다. 하나는 **낱말이 빠져나간 바로 그 길** 에 선다 — 기동 배너와 Gradle 태스크 설명과 빌드 안내와 한계 대장이 용어집의 표제어와 같은 말을 쓰는지 보고, 옛 이름이 그 자리에 남았는지 본다. 다른 하나는 `ApprovalRefusal` 의 값을 출하 소스에서 세어 산문이 적은 수와 댄다. 표제어를 하나 더 넣는 것만으로는 **다음 낱말이 같은 길로 또 나간다.**

    ★**옛 이름은 지우지 않고 용어집에 적었다.** 이미 밖으로 나간 편지들이 그 낱말을 쓰고 있어서, 지우면 「다른 것을 가리킨다」와 「같은 것의 옛 이름이다」가 접힌다. **바로 앞 항에서 철회에 적용한 규율과 같은 모양이다**(§15.184) — 그때는 자격이었고 이번엔 낱말이다. 다만 **부르는 자리에는 남기지 않는다.** 거기 남으면 이름이 하나가 아니라 둘이다.

    곁다리로 하나 더 나왔다. 이름을 고치며 훑다가 `ScenarioHost` 의 KDoc 예시가 **`--port 877`** 이었다 — 한 자리가 빠져 그대로 붙여 넣으면 안 선다. 아무도 안 걸린 이유는 다들 `CLAUDE.md` 의 줄을 복사했기 때문이고, **읽히지만 안 돌아가는 산문은 틀린 채로 오래 산다.**

    **못 막는 자리가 남는다.** 이 대조는 *이미 이름 지은 것* 이 한 말을 쓰는지만 본다. 새로 지은 낱말이 새 자리에 처음 나타나는 것은 못 막는다 — 조어를 기계로 알아볼 방법이 없다. 대장에 적었다(§15.185).

184. **강등의 주인을 정했다 — 밖이 들고, 철회는 지우지 않고 표시한다. §15.169 닫음.**

    읽는 쪽이 결정을 요청해 왔다. **양쪽이 서로를 기다리고 있었다**는 지적이 맞다 — 이 자리는 §15.169 로 열린 뒤 요청이 온 적이 없었고, 그쪽은 이 결정이 자기 한 축을 막는다고 적으면서도 보내지 않았다. **열어 둔 미결에는 주인이 없다.** 대장에 적는 것으로 그 자리를 들고 있다고 여겼는데, 들고 있는 것과 누가 언제 그것을 닫는지는 다른 문제다.

    ★**먼저 나온 것은 코드가 아니라 이미 있는 것을 아무도 못 봤다는 사실이다.** `ApprovalRefusal` 에는 열넷이 있고 `NOT_DECLARED`(원래 없었다)와 `EXPIRED`(전에 됐다가 만료)가 **이미 와이어로 나가며** 단위 시험도 둘 다 댄다. 그런데 구동기가 승인자를 하나만 세워 두어 밖에서 보이는 것은 셋뿐이었다. **칸을 만들어도 시나리오가 안 채우면 빈 칸이다**(§15.177)가 이번에는 **거절 어휘**에서 났다 — 그때는 번들의 칸이었고 이번엔 열거의 값이다. 구동기가 같은 자리에 승인자를 넷 세우게 고쳤다(그 자리는 어느 승인자로도 거절이라 제안이 안 소모된다).

    ★★**§15.169 이 적어 둔 대가 하나는 이미 치러져 있었다.** «밖이 들면 실패 통보의 규약이 들어온다» 고 적었는데, 자동 승인한 조치가 실패한 사실은 **사건 번들의 `approvedBy` 로 이미 밖에 나간다**(§15.168). 밖은 그 대장을 읽어 철회할지 정한다. 이 결정이 새로 끌고 오는 규약은 **철회 표기 하나뿐**이고, 그래서 밖이 드는 쪽이 적어 둘 때보다 쌌다. **미결을 오래 두면 그 사이에 대가가 바뀐다** — 열어 둔 이유를 다시 읽을 때 그 목록도 다시 세야 하고, 안 그러면 옛 가격표로 결정한다.

    ★★**철회는 지우는 것이 아니라 표시하는 것이다.** 지우면 선언 조회가 널을 돌려주고 그것은 «아직 안 올렸다» 와 같은 모양이다. 운영자가 받는 다음 행동이 정반대인데도 그렇다 — 철회는 «무언가 바뀌었다» 라 사후 검토로 가야 하고 미선언은 «원래 없었다» 라 선언을 올리러 가야 한다. 접으면 **강등된 사람을 선언 수정하러 보낸다.** 널과 침묵, 0 과 모름, 빈 목록과 미응답을 가른 것과 같은 규율이고, 이번에는 **삭제와 부재**다.

    ★**철회가 만료보다 먼저 답한다.** 둘 다일 때 만료를 내면 받은 쪽은 갱신하면 되는 줄 알고 기간만 늘려 **철회를 조용히 되돌린다.** 틀리는 방향을 정해 두는 자리이고, 여기서는 «무언가 바뀌었다» 를 먼저 말하는 쪽이 안전하다.

    ★**철회가 사람의 승인을 막지 않는다.** 사람은 선언 없이 누르므로 라인이 안 선다. 막으면 아무도 철회를 쓰지 않게 되고 그때 이 장치가 죽는다 — 만료를 짧게 걸 수 있는 이유(§15.169 의 뒷절)와 같은 논증이다.

    ★**주입 하나가 안 잡혔고 그것이 시험의 구멍이었다.** 철회 표기의 세 칸을 파서가 다 요구하는지 보는 시험이 `reason` 하나만 빼 보고 초록이었다 — `at` 이나 `by` 가 느슨해져도 안 빨개진다는 뜻이다. **한 칸을 대표로 삼으면 그 시험이 재는 것은 그 칸뿐이다.** 칸마다 따로 빼도록 고치니 셋 다 물었다. 규칙 ②(주입이 실제로 들어갔는가)를 지나 ④(시험의 구멍)까지 간 자리다.

    ★**설계 §14 의 ADR 표가 세 번째로 낡았다.** §15.165 가 «39 에서 멈춰 있었다» 를 잡아 43 까지 채웠는데 44 가 들어오면서 또 멈췄다. 색인이 «번호는 §14 의 행 번호» 라 적고 있는데도 그렇다. 이번에는 **ADR 파일마다 그 표에 행이 있는지 대는 시험**을 붙였다 — 같은 표를 세 번 손으로 고쳤으면 그것은 사람이 할 일이 아니다.

    남는 것은 **범위 축소**다(§15.184). 선언에 이력이 없으므로 «범위에서 빠졌다» 와 «원래 범위 밖이었다» 가 같은 답으로 온다. 철회처럼 표시로 풀 수 있으나 요청한 소비자가 없어 짓지 않았다(ADR 9).

    (의도적 제외로 옮김, §15.265) «§15.184» 행은 picasso 와 picasso-ops 어디에도 그 값을 쓰는 면이 없어 의도적 제외의 새 표 «수요 없음» 으로 옮겼다.

183. **점유 축이 계산한 답을 대장에 냈다 — §15.164 와 같은 결함이 다른 축에 하나 더 있었다.**

    §15.164 에서 «탐색이 못 찾았다는 답을 계산한 자리에서 버리고 있었다» 를 고쳤는데, **같은 일이 점유 축에서 그대로 일어나고 있었다.** `occupancyViolation` 은 출발 자리가 비면 그 자재를 든 다른 자리를 계산해 `alternativeLocations` 에 담는데, `record` 가 `Remedy.None` 과 `Remedy.Found` 만 보고 되돌아가므로 그 값은 **본 코드 어디서도 안 읽혔다** — 실측으로 본 소스의 참조가 계산하는 자리 하나뿐이었고 나머지는 시험이다. 밖에서는 «다른 자리가 있다» 와 «아무것도 계산 안 했다» 가 같은 침묵이었다.

    읽는 쪽이 코드를 읽고 짚어 왔다. **그쪽이 원하는 모양을 적지 않고 «자리는 그쪽이 정하라» 로 보낸 것이 맞았다** — 처음 가정했던 모양(탐색 줄에 목적지를 든 걸음)은 이 저장소의 구조가 거부하는 것이었고, 그대로 요청했으면 거부되는 모양을 강요하는 일이 됐다.

    ★**탐색을 넓히지 않았다.** 점유는 계약의 `PreconditionSubject` 가 아니고(그 주어는 «조건이 보는 로봇 상태» 이며 값이 늘려면 관측하는 어댑터가 있어야 한다), 자리를 보는 것은 어댑터가 아니라 셀 설비다(§15.159). 탐색의 상태 공간은 넷 그대로이고, 점유가 낸 답은 **탐색의 답이 아니라 관문의 답**이다.

    ★**그런데 한 대장에 둔다.** 접수 관문은 **첫 거절에서 되돌아가므로** 한 번의 접수는 많아야 한 번 거절되고 그 거절에 대한 답은 하나뿐이다. 대장을 갈래마다 따로 두면 읽는 쪽이 «이 주문이 왜 안 들어갔나» 를 알려고 여러 파일을 합쳐 봐야 하고, 한 거절이 어느 파일에 있는지도 미리 알 수 없다. 대장의 열쇠가 이미 (기체, 주문)인 것도 같은 이유에서 나온 것이다.

    ★★**승인할 수 있는 답은 `Found` 뿐이고, 그 차이가 칸의 모양으로 서야 한다.** `SourceMissing` 은 조치 열이 아니라 «주문을 고치면 통과한다» 는 답이고 어느 자리를 쓸지는 주문을 고치는 쪽의 결정이다. 그래서 `steps` 키를 안 싣는다 — `Withheld` 와 같은 모양이되 **이유가 다르다.** 앞은 가려서이고 이것은 승인이라는 행위가 성립하지 않아서다. 키를 맞추려고 빈 배열을 적으면 «승인하면 되는 조치인데 걸음이 비었다» 로 읽힌다. 규율이 아니라 **칸의 모양**이 드는 것이 §15.172 에서 배운 것이고 여기서 다시 쓴다.

    ★**널과 빈 목록을 인코딩에서도 가른다.** `alternatives` 의 널은 셀이 그 질문에 답하지 않은 것이고 빈 목록은 «든 자리가 하나도 없다» 는 답이다. 코드는 이미 가르고 있었는데 **키 집합 대조로는 이 둘이 같은 모양**이라(§15.177) 값을 보는 시험을 따로 뒀다. `observed` 도 같다 — 널이 빈 자리이고 값이 있으면 거기 있던 다른 신원이다.

    ★**판을 4 로 올렸다.** 사건 줄은 안 바뀌었고 탐색 줄의 `outcome` 에 값이 하나 늘었을 뿐인데, **칸이 느는 것과 다르다** — 모르는 칸은 무시하면 그만이지만 모르는 `outcome` 은 분기가 못 받는다. 3 을 유지하면 읽는 쪽이 판을 보고도 안전하다고 판단하게 된다.

    ★**한 벌에 미치는 흔들림을 맨 끝으로 밀었다.** 거절은 시계를 안 돌리고 실행도 제안도 안 만들므로, 이 경우를 시나리오 맨 끝에 두면 **앞선 사건 아홉의 시각과 해시가 그대로다.** 실측으로 확인했다 — 받는 쪽이 그 해시로 회귀를 박아 두었으므로 여기서 흔들었으면 그쪽 시험이 이유 없이 빨개진다. 늘어난 것은 탐색 줄 하나와 판 표기뿐이다.

    **자리 경쟁은 안 남긴다**(§15.183). 목적지를 남이 잡고 있어 막힌 거절에는 이 층이 계산한 답이 없다 — 잡은 쪽이 놓기를 기다리는 것 말고 제시할 것이 없고, 없는 답을 위해 칸을 만들면 그 칸이 늘 빈다. 소비자도 그것을 요청하지 않았다(ADR 9). 대장에 적는 것이 그 자리를 들고 있는 방법이다.

182. **재발을 세는 쪽에 셀 것을 줬다 — 값을 낼 줄 아는 것과 실물에서 그 값이 나오는 것은 다르다.**

    재발은 **읽는 쪽이 센다.** 이 층은 사실만 내고 새 사실을 만들지 않는다(ADR 40) — 재발은 이미 실린 칸(기체·분류·자리·시각)의 함수다. 그런데 인계한 두 벌은 **같은 시드의 재실행**이라 한 벌 안에 같은 기체·같은 분류가 하나도 없다. 그쪽 셈법이 시험으로는 서고 **실물로는 한 번도 안 밟혔다.** `DEPTH_LIMIT`(§15.170)·이름 없는 파지(§15.173)와 같은 모양인데, 이번 것은 **만들 수 있었다.**

    `run-3` 을 더했다. 재실행이 아니라 **다른 시나리오**다. 같은 기체가 같은 분류로 셋이고 그중 둘이 같은 자리에서다 — 읽는 쪽이 (기체, 분류)와 (자리, 분류)를 따로 세므로 축이 둘이어야 한다. 번들의 칸 집합과 `schemaVersion` 은 그대로다.

    **자리 축은 재작업으로만 생긴다.** 같은 단위가 두 번 사건을 열려면 사람이 `REWORK` 를 내야 한다. 그 전까지 단위는 `OPERATOR_HOLD` 에 서 있고 그것은 종착이 아니라 점유도 파지도 그대로다(§15.98·§15.159) — 자리를 놓기 전에는 그 기체가 다음 주문을 못 받는다. **사람의 손이 없으면 같은 자리의 재발은 이 층에서 안 생긴다**는 것이 이 한 벌을 짜면서 나온 사실이다.

    ★**파지로는 그 자리를 못 기다린다.** 재작업 뒤 새 태스크가 돌기를 파지로 기다렸더니 **앞 사건의 잔여 파지가 이미 `HOLDING`** 이라 조건이 즉시 참이 됐고, 그때 민 결함을 미믹이 «`ACCEPTED` 에서는 결함을 받지 않는다» 로 되돌렸다. 기다릴 것은 파지가 아니라 **새 태스크가 도는 것**이다. 같은 값이 두 시점을 뜻하는 자리이고, 결함을 넣는 시점이 한 번 틀렸던 §15.170 과 같은 병이다.

    ★**벤더 원문을 든 결함은 재작업을 못 탄다.** `X_FIXTURE_GRIPPER_SLIP` 은 `can_accept_new_task: false` · `UNTIL_CLEARED` 라 그 뒤로 새 태스크가 안 나간다. 그래서 재작업을 타는 둘은 `SKILL_EXECUTION_FAILED` 로 깨뜨리고 — 분류는 똑같이 `GRASP_FAILED` 라 재발의 축은 그대로다 — 벤더 원문은 재작업이 필요 없는 셋째가 든다. **픽스처의 선언이 시나리오의 모양을 정한다.**

    ★**재발이 없는 사건을 하나 넣었다.** 전부 재발이면 그 셈이 늘 켜져 있어 아무것도 안 가르고, 읽는 쪽은 자기 셈이 도는지조차 모른다. 경로가 한 갈래뿐이던 자리(§15.177·§15.178)와 같은 병이고 이번에는 먼저 적용했다.

    ★**같은 초에 둘이 들어오면 안 세어진다.** 가상 시계가 초 단위라 한 초 안의 두 사건은 앞뒤가 없고, 읽는 쪽은 **덜 세는 쪽으로** 틀린다 — 한 벌이 재발을 싣고도 재발로 안 읽힌다. 산출 쪽과 커밋된 한 벌 쪽 **둘 다** 그 성질을 든다. 한쪽만 들면 시나리오를 고칠 때와 한 벌을 갈아 끼울 때 중 하나가 빈다.

    ★★**안내문이 없는 구동을 가리키고 있었다.** `INDEX.txt` 가 두 벌의 `runId` 를 9/18 것으로 적어 뒀는데 그 한 벌은 9/19 에 다시 산출됐다. 칸과 판을 대는 검사는 **값을 안 보므로**(그것이 설계다) 아무도 안 빨개졌다. 값만 고치지 않고 안내문과 명세를 대는 시험을 붙였고, **그 시험이 붙자마자 내가 방금 적은 `run-3` 의 값도 틀렸다고 잡았다** — 두 번 산출한 것 중 앞의 것을 적어 뒀다. 같은 파일에서 §15.165 의 «산문의 수는 세는 시험이 붙기 전까지 반드시 낡는다» 가 두 번 났다. 둘 다 커밋된 파일이라 같이 움직이므로 유지비는 «한 벌을 갱신하면 안내문도 갱신한다» 하나이고, 그건 어차피 해야 하는 일이다.

181. **정답표를 답에 맞춰 넓히고 있었다 — 그리고 정답의 이유가 코퍼스로 새고 있었다.**

    읽는 쪽이 둘을 짚어 왔다. 둘 다 맞고 둘 다 내 쪽 결함이다.

    ★★**첫째 — §15.180 에서 내가 한 것은 「답을 보고 정답을 넓힌 것」이었다.** 네 판의 답을 보고 낱말 열둘을 만들었는데, 그중 **여덟이 우리 문서에 없는 말**이었다. 「좁히기 어렵」·「단정할 수 없」·「판별할 수 없」 같은 것들은 모델이 그렇게 답했기 때문에 들어간 것이고, 그런 표는 다음 판에서 또 새면 또 넓히게 된다. 그때 그 표가 재는 것은 모델이 아니라 **그동안 본 답들**이다. 읽는 쪽이 「정답표는 내가 안 고친다. 고치면 그때부터 내 답에 맞춘 정답표가 된다」고 한 규율을 나는 안 지키고 있었다.

    **유래로 바꿨다.** 정답표의 말은 우리가 코퍼스로 내는 문서에 실제로 있어야 한다. 그 하나로 넓히기가 구조적으로 불가능해진다 — 새 표현을 넣으려면 먼저 문서가 그 말을 써야 하고, 그러면 그것은 답을 따라간 것이 아니라 우리가 그렇게 적은 것이다. 시험이 그 유래를 든다. 열둘이 넷으로 줄었고 못 걸리는 표현이 남는 것은 이 방식의 대가다.

    ★그 시험이 켜지자마자 **다른 사건에서도 하나 나왔다.** 「사람 진입」이라고 적었는데 문서는 「구역에 진입했거나」라고 쓴다. 압축형을 내가 지어낸 것이고, 아무도 안 보고 있었으면 그대로 남았을 자리다.

    ★★**둘째 — 정답의 이유가 코퍼스로 샌다.** 이 일지가 골든셋을 왜 그렇게 만들었는지를 적으면서 **그 답을 다시 말한다.** 읽는 쪽은 코퍼스를 검색하므로, 벤더 문서를 안 읽고 이 일지를 읽어도 같은 답이 나온다 — 근거로 추린 것과 설계 문서를 되읽은 것이 구별되지 않고, 그 둘을 가르려고 만든 사건에서 그 구분이 사라진다.

    내가 걸어 둔 기계 조건은 **번들**만 봤다. 새는 것은 낱말이 아니라 이유였고 새는 자리는 코퍼스였다. 축이 달라서 안 보였다.

    **일지는 지울 수 없다**(§5 의 누적 기록). 그래서 뺄 수 없는 것으로 적고, **넷째 문서가 생기는 것**을 시험이 막는다. 그 시험을 켜자 대장 자신도 새고 있었다 — 내가 §15.180 을 적으면서 정답의 말을 산문에 썼다. 같은 뜻을 다른 말로 고쳐 대장을 목록에서 뺐다.

    판정은 읽는 쪽이 한다. 출처를 보고 «설계 일지를 근거로 든 답은 이 지표에서 뺀다» 는 채점기의 일이고, 이 저장소에서는 못 막는다. 대장에 적었다(§15.181).

    **못 재는 것을 못 잰다고 적는 편이 낫다는 판단을 받았다.** 채점 규칙을 늘려 한 건의 잡음을 없애면 그 규칙이 다음에 무엇을 가릴지 모른다 — 지표 하나를 살리려고 지표 전체의 해석을 흐리는 거래다.

    (의도적 제외로 옮김, §15.265) «§15.181» 행은 해소의 주어가 받는 쪽 채점기라 본 저장소가 구현할 것이 없으므로 의도적 제외 기존 표의 아키텍처 경계로 옮겼다.

180. **정답표가 어미를 들고 있었다 — 우리 문서를 그대로 인용한 답을 우리가 오답으로 셌다.**

    읽는 쪽이 `X_FIXTURE_E9001` 을 네 판 돌렸다. **넷 다 옳은 답이었는데 걸린 것은 한 판뿐이었다.** 정답표의 `cause` 가 «하나로 안 좁혀진다»·«안 좁혀진다» 같은 **완결 종결형**만 들고 있었고, 답들은 «좁히는 것이 불가능합니다»·«안 좁혀짐» 으로 왔다.

    ★★**가장 나쁜 한 판은 우리 문서를 그대로 인용한 것이다.** `fixture-stop-codes.md` 의 표가 «안 좁혀짐» 이라고 적어 놨는데 우리 정답표에 그 말이 없었다. 코퍼스를 인용하라고 만든 사건에서 코퍼스를 인용한 답에 벌점을 줬다.

    **어미가 아니라 줄기로 적는다.** «안 좁혀» 가 진다·짐·집니다를 다 덮고, «좁혀지지 않» 이 않는다·않습니다·않음을 덮는다. 부정에 묶인 줄기라 긍정형(«좁혀진다»)에는 안 걸린다 — 그냥 «좁혀» 로 적으면 반대 뜻의 답까지 정답이 된다.

    ★그리고 **문서의 표현과 정답표를 시험이 묶었다.** 문서가 쓰는 세 표현을 인용한 답이 표에 걸리는지 보고, 문서가 표현을 바꾸면 그 시험이 먼저 빨개져 정답표를 같이 고치게 한다. 둘이 따로 움직이면 같은 일이 또 난다. 실제로 돌려 본 네 답도 박아 뒀다 — 목록을 다시 좁히면 거기서 걸린다.

    ★★**둘째 결함이 하나 더 있었다. `distractors` 에 진짜 후보를 넣어 뒀다.** 배터리 셀 불균형과 모터 드라이버 과열은 «그럴듯하지만 아닌 것» 이 아니라 **둘 다 살아 있는 후보**이고, 정답은 그 둘을 이름으로 부른다. 오답 후보로 세는 채점기라면 **정답이 벌점을 받는다.** 틀린 것은 둘을 부르는 것이 아니라 **하나만 단정하는 것**이라, 그 구분을 `candidates` 라는 칸으로 갈랐다.

    **낱말 목록으로는 여기까지다.** «있어야 할 말» 은 적히지만 «단정하면 안 되는 것» 은 못 적는다. 후보 하나를 단정하면서 «정확히는 모른다» 를 덧붙인 답은 낱말 대조만으로 정답이 된다. 칸과 사유로 규칙을 적어 두었으나 그것을 음성 조건으로 읽는 것은 받는 쪽의 몫이며, 대장에 적었다(§15.180).

    셋은 안 건드렸다. 좁혀지는 사건들의 정답은 내용어(관절 토크·진공·인터록)라 어미가 바뀌어도 걸린다 — 이 병은 **부정 명제에만** 있었다.

    (의도적 제외로 옮김, §15.265) «§15.180» 행은 해소의 주어가 받는 쪽 채점기라 본 저장소가 구현할 것이 없으므로 의도적 제외 기존 표의 아키텍처 경계로 옮겼다.

179. **원인을 번들에서 빼고 코퍼스로 옮겼다 — 되읽기와 근거로 좁히기가 그제야 갈린다.**

    읽는 쪽이 「1순위 원인 일치율」의 정답을 세 번 짜고 세 번 버렸다. 분류 이름을 정답으로 뒀더니 그 낱말이 질의에 이미 있어 **복창이 만점**이었고, 분류에서 기종을 되짚었더니 그것은 도메인 사실이 아니라 어댑터 매핑 공백이었으며, 미분류 사건으로 옮겼더니 그 번들의 `vendorDetail` 이 `X_FIXTURE_SIMULATED_HARDWARE_FAULT` 라 **원인이 옆 칸에 적혀 있었다.** 셋 다 같은 자리다 — 정답이 입력 안에 있으면 그 지표는 모델이 아니라 자기 입력을 잰다.

    ★★**그래서 결함 이름이 원인을 말하지 않게 했다.** 실물 기체와 플릿이 내는 것은 불투명한 코드다 — 코드 자체는 아무것도 말하지 않고 뜻은 벤더 문서에만 있다. 픽스처를 그 모양으로 맞췄다: `X_FIXTURE_E4412` 처럼 보고는 알 수 없는 코드를 내고, 뜻은 `docs/vendors/fixture-stop-codes.md` 에만 둔다. `X_FIXTURE_` 접두사는 그대로다 — 합성 표시는 규율이고 뗄 것이 아니다.

    **이것이 이 층과 설명 층의 경계를 값으로 만든다.** 미들웨어가 매핑 못 한 코드는 `UNCLASSIFIED` 로 나가고, 거기서부터가 설명 층의 일이다. 번들을 되읽으면 «미분류다» 밖에 못 말하고, 코퍼스의 벤더 문서를 인용해야 «관절 토크 한계 초과다» 가 나온다. 앞 판에서는 둘이 같은 답을 냈기 때문에 그 차이를 잴 수가 없었다.

    **정답은 번들 밖으로.** `handoff/narrator/ground-truth.jsonl` 이 사건마다 정답 낱말·사람이 읽을 사유·오답 후보를 든다. 번들에 넣으면 복창이 다시 만점이 되므로 인계 자리의 **다른 파일**이다. 읽는 쪽은 그것을 채점에서만 읽고 질의를 만드는 경로에는 안 들인다.

    ★**시험이 조건 하나를 기계로 든다.** 정답 낱말과 오답 후보가 그 사건의 번들 줄에 **한 낱말도 없어야** 한다. 사람이 훑어서 막을 수 없는 종류의 누수다 — 결함 이름을 고치면서 `error_hint` 에 «토크 한계를 확인하십시오» 라고 적었으면 그 자리에서 다시 샜을 것이고, 실제로 힌트는 «정지 코드를 벤더 문서에서 조회하십시오» 로 썼다. 그리고 주입한 코드마다 문서에 근거가 하나는 있는지도 함께 든다 — 없으면 그 사건은 «안 좁혀지는 것이 정상» 이 아니라 **재료가 없는 것**이고 둘은 다른 실패다.

    **안 좁혀지는 것이 정상인 사건을 하나 넣었다.** `X_FIXTURE_E9001` 은 한 코드에 원인이 둘이고 코드가 그 둘을 안 가른다 — 문서가 그렇게 적었고, 둘 중 하나를 지목하는 답은 근거가 없다. 전부 좁혀지면 «안 좁혀진다» 가 한 번도 정답이 되지 않아 그 답을 낼 줄 아는지를 잴 수가 없다. 과잉 단정을 잡는 자리다.

    **번호가 밀리는 것도 막았다.** 정답표가 `incident-N` 을 가리키는데 시나리오가 늘면 그 번호가 딴 사건을 가리킨다. 정답표가 정지 코드를 함께 들고 시험이 둘을 맞대므로, 밀리면 조용히 틀린 채로 채점되는 대신 빨개진다.

    한 벌이 사건 아홉이 됐고 그중 넷이 골든이다. 나머지는 그대로다 — 승인자·경로 둘·끊긴 선·매핑된 벤더 코드 하나는 각자 다른 것을 보여 주는 자리라 건드리지 않았다.

178. **의도와 경로와 관측 신뢰까지 실었다 — §15.177 의 급 2 는 닫힘.**

    §15.177 이 급 1 을 실으면서 *"그만큼 설명이 분류의 재진술에 머무는 자리가 남는다"* 를 대장에 남겼는데, 그 자리를 닫는다. 셋 다 이미 각자가 답해 둔 값이고 옮겨 싣는 것이다.

    **의도 — «무엇이 어긋났나» 와 «무엇을 하려 했나» 는 다른 질문이다.** 앞엣것만 있으면 설명이 어긋남의 재진술에 머문다: 어느 자리에 무엇을 놓으려 했는지를 모르면 «잘못 놓았다» 가 무슨 뜻인지 말할 수가 없다. **상류가 적은 것과 이 층이 편 것을 함께 든다** — 둘이 갈리는 자리가 곧 이 층의 번역이고, 한쪽만 실으면 읽는 쪽이 그 번역을 볼 수 없다. 능력의 최고 등급과 시간창 폭도 같이 넣었다. 요청에는 `Execution.capability` 라고만 적혀 있었는데, 그 객체에서 밖이 쓸 수 있는 것이 그 둘이다 — 근거 창이 **왜 그 범위였는지**가 그것으로 답해진다.

    **경로 — 없으면 책임 소재를 못 가른다.** 같은 «실패» 라도 `ROBOT` 은 기체와 어댑터의 일이고 `FLEET` 은 플릿의 일이며, 다음에 누구에게 물을지가 그것으로 갈린다. 한 칸짜리 값인데 그 한 칸이 다음 행동을 정한다.

    **관측 신뢰 — `progressObservable` 의 널이 요점이다.** 3값이고 널이 «아직 갱신을 못 봤다» 다. 거짓으로 접으면 «못 물어봤다» 가 «못 잰다» 가 되고, 진행률의 0.0 을 정체로 읽으면 진행률을 안 내는 기종이 언제나 멈춰 있는 것으로 보여 그 경보가 곧 무시된다 — 계약이 `ProgressBasis` 를 만든 이유가 그것인데 번들이 그 구분을 떨어뜨리고 있었다.

    ★**한 갈래뿐인 칸은 값이 있어도 아무것도 안 가른다.** §15.177 에서 배운 것을 이번에는 먼저 적용했다. 시나리오에 **플릿의 일**(출발지에 요청한 용기가 없어 인수되지 않는 운반)과 **선이 끊긴 채 난 사건**을 넣어 `route` 와 `linkBroken` 이 한 벌 안에서 갈리게 했다. 플릿 단위는 하달 자체가 없어 `progressObservable` 이 널로 나오므로 3값의 널도 실물에서 보인다. 남은 둘(`lateEvents`·`progressStalled`)은 한 벌에서 여전히 기본값이고, 그것은 대장에 적었다(§15.178).

    **맵을 키 순서로 정렬해 해시에 넣는다.** 의도가 맵 둘(`orderParameters`·`unitParameters`)을 들고 오는데, 순회 순서가 해시를 바꾸면 같은 사건이 두 값을 갖는다. 새 칸이 맵을 데려올 때마다 물릴 자리라 인코딩도 같은 규칙으로 맞췄다.

    `schemaVersion` 은 3 이다. **2 와 달리 이번은 칸이 는 것뿐**이라 모르는 칸을 무시하는 읽는 쪽은 그대로 돈다 — 2 는 `blockedBy` 의 모양이 바뀌어서 판을 봐야 했다.

    급 3(시간창 경계·시도 횟수·`physicalState`·`taskId`·`revision`)은 안 실었다. 그쪽이 급을 갈라 왔고 섞으면 무엇이 본질인지 안 보인다는 말이 맞다. 대장을 그 내용으로 바꿔 뒀다.

    (의도적 제외로 옮김, §15.265) «§15.178» 행은 picasso 와 picasso-ops 어디에도 그 값을 쓰는 면이 없어 의도적 제외의 새 표 «수요 없음» 으로 옮겼다.

177. **번들이 자기 선언을 못 지키고 있었고, 읽을 것이 없으니 읽는 쪽이 되짚었다.**

    `IncidentBundle` 의 doc 이 *"«이 단위가» 와 «이 기체가» 와 «그때 무슨 일이 있었나» 를 한 화면에서 잇게"* 라고 적어 두고 **셋 중 «이 기체가» 를 안 싣고 있었다.** `Execution.robotId` 가 봉인 시점에 바로 있는데도.

    ★★**그것을 발견한 경로가 증거다.** 읽는 쪽이 평가 골든셋을 짜는데 기체에 대해 말할 재료가 없어 **`FailureClass` 에서 기종을 거꾸로 팠다.** 「`ROBOT_FELL` 이면 Spot」은 도메인 사실이 아니라 **어댑터 매핑 공백**이다 — Digit 도 휴머노이드라 넘어질 수 있는데 그 어댑터가 안 매핑했을 뿐이고(Spot 10종·Digit 5종·G1 3종은 벤더 API 노출량이다), 매핑 한 줄이 늘면 조용히 틀린 답이 된다. **기종 비인지를 거꾸로 돌린 것**이고, 원인은 읽는 쪽이 아니라 **읽을 것을 안 준 이쪽**이다.

    넷을 실었다. **기체** · **근거의 세기**(요구 등급·도달 등급·설비 대조 결과) · **분류의 근거**(결함 원문) · **걸음 위치**. 전부 이미 각자의 자리가 답해 둔 값이라 옮겨 싣는 것이고, 새 사실을 만들지 않는다는 번들의 성질은 그대로다.

    **가장 큰 것은 근거의 세기다.** 등급은 순서가 곧 세기인데(E0 자기 보고 · E1 플릿 · E2 독립 설비 · E3 업무 ack) 셋 다 없어서, 이 층은 *"이 판정은 로봇 자기 보고 하나에 기대고 있어 약하다"* 를 **말할 수 없었다.** 이 시스템에서 가장 1급인 진단 문장이 그것이다.

    ★**요청보다 한 칸 더 갔다.** 받는 쪽은 `blockedBy` 의 일곱 칸을 요청했는데, 그것은 **다음 단위를 막는** 결함이라 다음 단위가 없으면 빈 목록이다 — 실물 한 벌에서 사건 넷 중 둘이 그랬다. 그래서 «왜 그 분류가 됐나» 는 대부분의 사건에서 여전히 답이 없었을 것이다. 단위가 자기 결함 원문을 들게 해서(`ExecutionUnit.fault`) 번들이 `fault` 로 낸다. 요청의 글자는 `blockedBy` 였지만 목적은 *"분류를 재진술하지 않는 것"* 이었고, 글자만 채우면 목적이 안 선다.

    **칸을 만들어도 시나리오가 안 채우면 빈 칸이다.** 실물 한 벌의 결함 유형 자리에 정준 분류 이름이 그대로 들어 있어 벤더 원문이 통째로 비어 있었다. 픽스처에 벤더 이름공간(`X_`) 모드를 더하고 주입을 그것으로 바꿨다. 안 좁혀지는 사건(`UNCLASSIFIED`)과 **둘째 걸음에서 깨진 사건**도 한 벌에 넣었다 — 전부 «1 / N» 이면 걸음 위치가 값으로는 있어도 아무것도 가려 주지 않는다.

    ★★**공용 픽스처에 모드를 더했다가 `WithholdingTest` 둘이 빨개졌다.** 추첨이 *해당하는 모드마다 한 번씩* 뽑으므로 모드가 하나 늘면 같은 시드가 다른 순서를 낸다. `FailureDraw` 가 그 자리를 *"골든이 통째로 흔들리는 자리"* 라고 적어 뒀는데 그것을 읽고도 물렸다. 새 모드는 `vendor-fault.json` 으로 갈라 두 기체만 쓴다.

    **해시는 새 칸을 전부 든다.** 봉인 시점에 확정되고 같은 시드면 같은 값이며, 그중 하나가 다른 사건은 다른 사건이다 — 특히 기체가 그렇다. 빠지는 것은 여전히 실 시계와 나중의 판정 둘뿐이다. `schemaVersion` 은 2 다: `blockedBy` 가 칸이 는 것이 아니라 **모양이 바뀌었으므로** 읽는 쪽이 판을 봐야 한다.

    급 2(의도·경로·관측 신뢰)와 급 3 은 안 실었다. 요청이 급을 갈라 왔고, 섞으면 무엇이 본질인지 안 보인다는 것이 그쪽 말이며 맞다. 대장에 적어 둔다(§15.177). **급 2 는 §15.178 에서 닫혔다(닫힘).**

176. **입을 열었더니, 떠난 소비자 하나가 세계를 멈추고 있었다.**

    표면을 정하고 규약을 적어도 부르는 쪽의 권한은 여전히 0 이었다 — 소켓을 여는 손이 없었다. 그래서 구동기를 오래 도는 것으로 만들고 루프백에 `POST /approvals` 를 붙였다. `picasso` 는 그대로 라이브러리다. 여는 코드가 시험 소스에 사는 것이 그 성질을 지키는 방법이고, 파일 내보내기가 같은 자리에 있는 이유와 같다(§15.176).

    **제안을 넷 세운다.** 승인되는 것 하나와 거절되는 것 셋(가려짐·범위 밖·제안 없음). 거절을 종류로 가른 이유가 «다음 행동이 다르다» 인데, 하나만 세우면 그 차이를 값으로 볼 수가 없다. 그리고 첫 승인이 설 때까지 가상 시계를 세워 둔다 — 성공하는 승인은 **지금 든 것의 이름**을 관측에서 가져오므로 기체가 먼저 내려놓으면 그 출처가 사라진다. 거절 셋은 값 채우기 앞에서 판정되므로 시계와 무관하다.

    ★★**그리고 오래 돌리자마자 미믹이 죽었다.** 첫 승인 뒤 시계를 돌리는 순간 `CANCELLED: call already cancelled` 가 터졌다. 취소된 `WatchTask` 스트림에 밀면 gRPC 가 던지는데, 그 예외가 `settleAll` 을 통째로 끊어 **다른 기체의 시간까지 멈춘다.** 한 소비자가 떠났다는 사실이 세계를 세우는 것이고, 그 정지는 로그도 예외도 없이 «아무 일도 안 일어난다» 로 보인다 — `MimicServer.advance` 가 전진·반영·밀어내기 셋을 한 함수로 묶은 이유와 정확히 같은 자리다.

    시험에서는 안 보였다. **기다리던 것이 오면 시험은 곧 끝나므로 그 다음 전진이 없었다.** 오래 도는 것이 없으면 이 부류의 결함은 영원히 안 보인다. 떠난 관측자를 밀기 전에 지우도록 고쳤고, 기체 둘로 «떠난 쪽 때문에 남은 쪽의 갱신이 끊기는가» 를 든다.

    **선언 목록의 첫 구현체도 여기서 나왔다.** ADR 43 이 «배치의 결정» 이라 한 자리를 파일로 채웠고, 못 읽으면 **던진다** — 빈 목록으로 접으면 오타 난 파일이 «아무것도 선언 안 했다» 와 같은 모양이 되고, 그 배치는 자동 승인이 전부 거절되는 것을 정상으로 읽는다.

175. **승인이 조치를 조용히 떨어뜨릴 수 있는 자리 둘 — 하나는 막았고 하나는 못 만든다.**

    `commit` 이 `submit` 을 부르는데, 그 주문이 **이미 다른 기체에서 돌고 있으면** 접수가 멱등으로 접히고 조치 열은 안 나간다. 앞 판은 그것을 성공으로 냈다 — 승인했는데 아무 일도 안 일어난 것이 초록으로 보이는 모양이고, 이 저장소가 반복해 물린 조용한 통과 그 자체다. `REMEDY_NOT_APPLIED` 로 거절하고 기체 둘로 시험이 든다.

    **둘째 자리는 막지 않았다.** `revise` 는 조치 열 인자를 아예 안 받으므로, 개정 거절에서 난 제안을 승인하면 조치가 소리 없이 사라진다. 방벽을 하나 썼다가 **지웠다** — v1 에서 그 제안이 설 수 없기 때문이다. 파지를 바꾸는 스킬이 `pick_place` 하나뿐이라 그것이 막히면 풀 수단이 없고, 그래서 개정 거절은 `Found` 를 못 낸다. 만들 수 없는 상황을 막는 검사는 결함을 주입해도 아무 시험도 빨개지지 않는다 — `HoldEffects.compare` 에서 기대 쪽 검사를 안 든 것과 같은 규율이다. 대장(§15.175)에 적는 것이 그 자리를 들고 있는 방법이다. → §15.201 에서 정정됐다 — 순회의 `navigate_to` 가 막히면 `pick_place` 가 그 해라서 개정 거절도 `Found` 를 낸다.

    **(닫힘, §15.289)** 항목 289가 `revise`가 승인된 조치 시퀀스를 받아 다시 계획하는 첫 단위 앞에 세우게 했다. 이미 승인을 든 실행의 개정만 제안을 세우지 않는다. 남는 것이 없어 닫는다.

174. **둘째 걸음이 들 것의 이름은 아무도 모른다.**

    값 채우기가 걸음마다 효과로 파지를 굴린다. 첫 걸음이 놓으면 그 다음 파지는 빈손이고, 빈손에는 이름이 없으므로 대상 참조를 요구하는 둘째 걸음은 거절된다. 굴리지 않으면 **지금 든 것의 이름이 둘째 걸음에까지 실려 나간다** — 그 값은 관측이 아니라 낡은 관측의 재사용이다. v1 의 조치 열은 전부 한 걸음이라 아직 안 물리지만, 물리는 날 그것이 조용히 틀리는 대신 거절이 되게 해 뒀다.

173. **«든 채다» 와 «무엇을 들었다» 는 다른 사실인데, 미믹으로는 그 차이를 못 만든다.**

    자동 승인이 대상의 이름을 관측에서 가져오므로, 기체가 «든 채» 라고만 답하고 이름을 안 주면 거절해야 한다. 그런데 이 층을 통해서는 그 상황이 안 나온다 — 하달된 단위가 대상 참조 파라미터를 싣고 미믹이 그것을 그대로 되울리기 때문이다. 실물 기체는 그렇게 답할 수 있다.

    그래서 **판정을 순수 함수로 떼어 냈다**(`RemedyValues`). 미들웨어도 포트도 시계도 안 보고 관측을 인자로 받으므로, 실물에서 못 만드는 관측을 시험이 직접 세운다. §15.170 의 `DEPTH_LIMIT` 과 같은 모양의 한계이고, 같은 방식으로 대장에 남긴다.

172. **자동 승인에 실을 값이 없었다 — 그리고 «어느 스킬» 은 범위가 아니었다.**

    받는 쪽이 자동 승인을 세우다 두 번 막혔다. 표면이 없고(프로세스 밖에서 부를 길이 없다), 부를 값이 없다. 뒤엣것이 깊었다. 실측하면 `profile/` 의 모든 스킬이 필수 파라미터를 받고, 조치의 걸음이 될 수 있는 것은 파지를 바꾸는 스킬뿐이라 전 카탈로그에서 `pick_place` 하나이며, 그것이 `object_id` 와 `destination` 을 받는다. 값은 사람이 준다는 것이 규율이므로 자동 승인은 **구조적으로 성립하지 않는 상태**였다.

    받는 쪽은 갈래 셋을 주면서 «파라미터를 받는 조치는 자동 승인 대상이 아니다» 와 «선언이 값을 함께 든다» 를 **대안으로** 놓았다. 대안이 아니다. 앞엣것의 기준(조치가 완전히 기술되는가)이 맞고, 뒤엣것이 **그 기준을 충족시키는 방법**이다. 선언된 효과만으로는 `pick_place` 가 어디에 놓는지를 말하지 못하지만, 효과와 선언된 값을 함께 두면 말한다.

    ★**결정의 이유는 그쪽이 든 것과 다르다.** 그쪽은 «값도 결국 사람이 준 것» 이라고 했다. 맞지만 약하다. 진짜 이유는 이것이다 — **`skillTypes` 는 어느 스킬인지를 묶을 뿐 무엇으로인지를 안 묶는다.** `pick_place` 자격 하나가 실제로 주는 권한은 «내려놓아도 된다» 가 아니라 «무엇이든 어디에든 놓아도 된다» 이고, 선언이 적은 범위와 실제 권한의 그 차이가 곧 이 자격이 존재하는 이유다. 프로세스 안에서는 안 드러났다. 부르는 쪽이 시험뿐이었기 때문이다.

    **값의 출처를 계약이 이미 가르고 있었다.** `is_object_reference` 가 붙은 칸은 **관측**에서(지금 든 것의 이름), 나머지 필수 칸은 **선언**에서. 지어내는 갈래는 없고, 어느 쪽도 못 채우면 거절이며 사람에게 남는다. 이 결합은 추정이 아니다 — 어느 칸이 대상의 이름인지는 계약이 말하고, 든 것의 이름은 기체가 말한다. 미믹이 같은 사실을 반대 방향으로 쓰고 있었고(파라미터에서 파지로), 그 읽는 자리를 `capability` 로 옮겨 한 곳으로 만들었다.

    ★★**그래서 문이 둘이 됐다.** 사람의 문은 값을 들고, 밖의 문에는 값을 실을 칸이 **없다.** 주문도 없다 — 제안 표가 주문을 함께 들게 해서, 승인은 «무엇을 하라» 가 아니라 «그것을 하라» 가 됐다. 앞 판은 주문을 인자로 받았고, 그 말은 부르는 쪽이 주문을 지어낼 수 있었다는 뜻이다. 승인 표면이 아니라 일반 접수 표면이었던 것이고, 밖에서 부를 길이 생기고 나서야 보였다. **부르는 쪽의 권한이 하나로 묶이는 것은 규율이 아니라 표면의 모양 때문이어야 한다** — 규율은 지켜지는 동안만 유효하고, 칸이 없는 것은 언제나 유효하다.

    **거절을 값으로 만들었다.** 사유 산문만 내면 읽는 쪽이 한국어를 문자열로 맞춰야 하고, 그 시험은 «거절됐다» 만 보므로 아무 거절에나 초록이 된다. 가르는 기준은 다음 행동이 다른가 하나뿐이다. 그리고 **가림을 맨 앞에 뒀다** — 뒤로 물리면 선언 없는 에이전트가 «자격 없음» 을 받고, 사람이 먼저 진단해야 한다는 지시가 답에서 사라진다.

    **관문 거절이 제안을 소모하던 것도 고쳤다.** 자리 경쟁으로 못 들어간 것은 자격의 문제가 아닌데 앞 판은 그 자리에서 제안을 지웠다. 조건이 풀려도 누를 것이 없다.

    지나가며 둘을 더 봤다. `revise` 가 조치 열을 떨어뜨리는 자리(§15.175)와, 실물에서 못 만드는 관측(§15.173) 및 둘째 걸음의 대상(§15.174).

171. **넘기려고 남의 저장소에 파일을 썼다 — 그 한 번이 경계의 증명을 지운다.**

    한 벌을 인계하면서 받는 쪽 저장소의 `tests/fixtures/` 아래에 파일을 직접 넣었다. 받는 쪽이 그것을 발견해 알려 왔고, 맞는 지적이다. **두 계층이 다른 저장소에 있고 서로를 쓰지 않는다는 것이 이 설계의 산출물 중 하나**인데, 편의로 한 번 넘어가면 그 증명이 사라진다.

    **더 나쁜 것은 그 판단의 경로다.** 「직접 넣으라는 거냐」는 말을 «내가 넣어도 된다» 로 읽었다. 그것은 편의에 대한 불만이었지 경계 규칙을 지우는 허락이 아니었고, **문서로 적힌 제약은 편의를 이유로 물러서지 않는다** — 물러설 수 있으면 애초에 제약이 아니다. 받는 쪽 문서가 이 유혹에 이름을 붙여 뒀다: *"여기서 바로 보내면 편한데"*.

    **자리를 만들었다.** `handoff/<받는 쪽>/` 에 두고 경로만 알린다. 넘기는 쪽은 자기 저장소만 건드리고, 무엇을 들일지는 받는 쪽이 정한다 — 다른 층이 이미 그렇게 하고 있었고 그 모양이 맞다.

    **인계본은 스냅샷이라 낡는다.** 인코더가 칸을 늘리거나 줄여도 커밋된 한 벌은 그대로 남고, 그러면 받는 쪽은 **없는 계약에 맞춘 수신기**를 든다. 그래서 커밋된 한 벌을 지금의 인코더로 다시 재서 **칸의 집합**만 대조한다 — `runId` 와 `wallClockAt` 은 구동마다 다른 것이 설계이므로 값은 안 본다. 값을 고정하면 그 시험이 설계와 싸운다.

    **주입 하나가 안 들어갔다.** 두 벌의 구동 식별자를 같게 만들려고 꼬리 숫자만 바꿨는데 앞의 시각이 달라 여전히 다른 값이었다. GREEN 을 시험 탓으로 읽을 뻔했다 — 규칙 ②(주입이 실제로 반영됐는가)가 먼저다.

170. **한 벌을 실물로 내놨더니, 규약이 아니라 도달 가능성이 걸렸다.**

    읽는 쪽이 인코더 소스를 읽어 수신기를 맞추고 있었다 — 성실히 읽어도 봉투 한 겹을 놓치면 아무도 모른다. 그래서 디스크에 한 벌을 놓고, **같은 시나리오를 두 번** 돌렸다. 두 벌의 해시가 같고 `runId` 만 다른 것이 그 값이 존재하는 이유 자체다.

    **기체를 넷으로 갈라야 했다.** 한 기체는 태스크를 하나씩만 드는데(§15.98) 처음엔 한 기체에 다 몰아 앞 실행이 뒤를 막았다. 시나리오가 «가능한 일들» 이 아니라 «동시에 가능한 일들» 이어야 했다.

    **`DEPTH_LIMIT` 은 못 만들었고, 그게 이번의 소득이다.** 탐색은 그 값을 낼 줄 아는데 이 층을 통해서는 도달하지 못한다 — 파지 값이 넷인데 효과가 내는 것은 빈손과 든 채 둘뿐이라, 너비 우선이 상한(세 걸음)에 닿기 전에 볼 것이 없어진다. **선언된 값이 실물에서 한 번도 안 나오는 자리**이고, 읽는 쪽이 그 갈래를 다뤄도 밟히지 않는다. §15.170 으로 걸었고 `AGENT-06` G3.2 에도 적었다.

    **잔여 파지 `UNSPECIFIED` 도 시나리오로는 안 나왔다.** 하달된 단위는 기체가 파지를 답하고, 하달 전에 실패한 것(출발 결품)은 사건을 열지 않는다. 다만 **기본값 출력이 서는지는 실물에서 확인됐다** — `objectRef` 와 `reason` 이 빈 문자열로 남아 있고, 표준 printer 였으면 그 키들이 통째로 빠진다. 단언을 그쪽으로 옮겼다.

    **결함을 넣는 시점이 한 번 틀렸다.** 이 층이 단위를 `RUNNING` 으로 적는 시점은 하달한 순간이고 기체가 실제로 도는 것은 그 다음 tick 이다. 그 창에 결함을 넣으면 기체가 받지 않는다 — 기체 쪽 상태를 봐야 했다. **두 시계가 다른 자리가 여기에도 있었다.**

169. **G2 를 대 봤더니 내 매핑이 후했고, 뒷절 둘이 안 걸려 있었다.**

    `AGENT-06` G2 의 여덟 경우를 picasso 쪽 시험에 대고 맞춰 봤다. **먼저 걸린 것은 코드가 아니라 내 매핑이었다** — 2.6 의 판정 기준이 «거부 + 에스컬레이션» 인데 내가 댄 시험은 반복이 지표로 나오는 것까지만 증명한다. 세어 보면 초록인데 그 초록이 다른 명제의 초록이었다. **대는 시험을 고르는 일도 주장이다.**

    **뒷절 둘이 비어 있었다.** 2.1 은 «거부. **사람에게 남는다**» 이고 2.8 은 «거부. **사람 승인으로 복귀**» 인데, 나는 앞절(거부)만 걸어 뒀다. 거부가 제안을 소모하면 자격이 없다는 이유로 사람까지 누를 것을 잃고, 만료가 라인을 세우면 아무도 만료를 짧게 걸지 않는다 — **자격은 좁히기만 하고 없애지 않는다** 가 뒷절의 내용이다. 둘을 시험으로 걸었고 주입이 문다.

    **2.4 는 안 지었고, 안 지은 데 이유가 있다.** 자동 승인한 조치가 실패하면 자격이 강등돼야 하는데, 관문은 선언을 **읽기만** 하고 선언의 주인은 밖이다(ADR 43). 강등을 이 층이 쓰면 소유하지 않은 값을 고치는 것이고, 억제를 이 층에 따로 두면 **자격의 원천이 둘**이 된다. 어느 쪽이 드는가는 결정이 필요한 자리라 짓지 않고 §15.169 로 걸었다. **(닫힘 — §15.184. 밖이 들고, 철회는 지우지 않고 표시한다.)**

168. **자격 관문을 지었고, 주입 아홉 중 둘이 시험 쪽 결함을 꺼냈다.**

    §15.165 가 적은 셋이 한 PR 로 들어왔다 — 승인 API 의 신원 인자, 선언 목록 포트, 승인자 종류별 이의율. ADR 43 이 이미 판정을 고정해 뒀으므로 지은 것은 그 판정이다.

    **사람에게는 선언을 요구하지 않는다.** 선언 목록이 있는 이유는 «사람 대신» 누르는 것을 허락하는 것이고, 사람까지 대조하면 그 목록이 운영자 명부가 된다 — 명부가 한 명 빠진 날 라인이 선다. 반대로 **선언 안 된 에이전트는 전부 거절**이고(deny by default), 그래도 라인이 안 서는 이유는 사람이 누를 수 있기 때문이다. 바닥 소유 대장이 반대 방향이었던 것과 대조된다 — 거기서는 «대장 없음» 을 막으면 라인이 서고, 여기서는 안 선다. **같은 규율이 자원마다 다른 기본값을 낸다.**

    **빈 집합을 «전부» 로 읽지 않는다.** 선언을 반쯤 적은 배치가 무제한 자격을 얻으면 그것이 가장 나쁜 실패다. 넓히려면 적어야 한다.

    **반복 카운터는 한 통, 이의율은 두 통.** ADR 43 §4 의 결정을 코드가 든다. 둘 다 시험이 고정하므로, 나중에 «일관성» 을 이유로 한쪽을 다른 쪽에 맞추려 하면 빨개진다.

    **승인자가 해시에 든다.** 다른 쪽이 승인한 같은 모양의 사건은 **다른 사건**이다 — 누가 눌렀는지가 그 사건에 대해 할 말을 바꾼다. 해시의 필드 목록이 «무엇이 결정적인가» 의 답이므로 늘리는 데는 이유가 필요했고, 이것이 그 이유다.

    **주입 아홉 중 둘이 GREEN 이었고 둘 다 시험이 틀린 것이었다.** 하나는 빈 집합 시험이 기체 범위와 조치 유형을 **한 번에** 비워 앞 관문이 뒤 관문을 가렸다 — 축마다 떼서 다시 지었다. 다른 하나는 «승인 없는 사건은 어느 통에도 안 들어간다» 를 **사건이 하나도 없는 세계**에서 물어 공짜로 통과했다 — 결함을 강제해 사건을 만든 뒤 다시 물었다. **전제가 빈 단언은 주입이 없으면 영원히 초록이다.**

    그리고 시험 전제가 한 번 더 틀렸다. 가려 둔 제안을 세우는 헬퍼가 `proposal()` 이 널이 아니라고 단정했는데, **가린 제안은 조회로 안 보이는 것이 설계다.** 코드가 맞았고 헬퍼가 틀렸다.

    신원이 위조 가능한 것은 §15.3 이 이미 의도적 제외로 적어 둔 자리다 — 새 한계를 만들지 않고 그것을 댄다.

167. **내보내기를 지었더니 규약의 한 줄이 규약만으로는 안 되는 것이었다.**

    §6 이 «계약 메시지는 protobuf JSON 규약» 이라 적었고 그것은 저장소의 기존 적재 표면과 같은 규약이다. 그런데 그 규약의 **기본 설정은 기본값 필드를 빼는 것**이다. 그대로 쓰면 `HoldState` 가 `HOLD_KIND_UNSPECIFIED` 일 때 통째로 `{}` 가 되어 **「빈손」과 「말하지 않았다」가 접힌다** — §15.145 가 막은 바로 그 일이 직렬화 한 겹 아래에서 되살아난다. 기본값도 적도록 바꿨다. **같은 규약을 쓰면서 설정 하나가 반대 방향이면, 문서의 「같은 규약」은 지켜진 채로 규율이 깨진다.**

    **구동 식별자 하나만 결정적이지 않다.** `digest()` 도 `incident-N` 도 같은 시드면 같은 값이고 그것이 재현의 근거인데, 읽는 쪽이 멱등 열쇠를 그 값들로 들면 같은 시드로 다시 돌린 두 번째 구동의 사건이 전부 «이미 본 것» 으로 접힌다. **그 고장에는 아무 신호가 없다** — 로그도 예외도 없이 처리 결과만 비고, 그것은 «아직 안 붙었다» 와 구별되지 않는다. 그래서 이 값 하나가 실 시계에서 나온다. 결정성이 규율인 저장소에서 **의도적으로 비결정적인 값**이라, 나중에 누가 «이것도 결정적으로» 라고 고칠 자리다. 시험이 그것을 든다.

    **인코딩과 전송을 갈랐다.** `LedgerExport` 는 문자열만 만들고 파일도 소켓도 자기 시계도 모른다. 그래서 순수 함수이고 **인프라 없이 시험이 문다** — 그것이 §6 에서 C 를 고른 이유의 절반이었고, 지어 보니 실제로 그렇다. 열 시험 중 아홉이 하네스 없이 돈다.

    **JSON 인지 눈으로 보지 않는다.** 줄마다 계약의 JSON 파서로 되읽어 구조로 단언한다. 그래야 따옴표 하나가 새는 것과 키가 조용히 빠지는 것을 같은 시험이 문다 — 사람이 적은 진단 사유가 그 줄로 나가므로 이것은 가정이 아니라 실제 경로다.

    **가림의 방벽이 적재면에도 있다.** `outcome` 이 `WITHHELD` 인 줄에는 `steps` 키가 **없다**. 값이 비는 것이 아니라 키가 없는 것이 답이고, 여기서 편의로 제안 표의 걸음을 꺼내 실으면 조회 한 번으로 가림이 풀린다. 코드의 방벽(§15.164)과 같은 성질을 한 겹 밖에서 든다.

166. **두 대장을 누가 담는가 — 「아직 호스트할 것이 없다」가 답을 정했다.**

    §6 이 «어느 쪽이 담을지는 배치 결정» 이라 적고 자리를 비워 뒀는데, 읽는 쪽이 별도 프로세스라 **규약은 있는데 실어 나를 주체가 없는** 상태가 됐다. 셋을 놓고 봤다 — 파일 내보내기(C) · 얇은 호스트 모듈(B) · `registry` 가 담기(A).

    **실측이 답을 좁혔다.** `src/main` 어디에서도 `Middleware` 를 세우지 않는다. 세우는 것은 시험 스물둘뿐이고, 포트 여섯 중 실물 구현체를 든 것은 `ClientRobotPort` 하나이며 나머지 다섯은 `None` 이다. **B 를 지금 세우면 다섯 개의 `None` 을 든 프로세스가 된다** — 호스트가 아니라 하네스의 다른 이름이다. 그러면 이미 그 일을 하는 것을 쓰는 것이 맞다.

    **A 를 미룬 이유는 폭발 반경이다.** `registry` 는 설정 평면이고 그 정지는 «새 어댑터 판을 못 올린다» 다. 거기에 실행 계층의 적재를 얹으면 정지가 «사건을 못 읽는다» 로 바뀐다. ADR 43 §3-1 이 `picasso → registry` 를 막은 논증의 거울상인데, **조회가 멈추는 것과 판정이 멈추는 것은 다르므로 같은 논증이 그대로 서지는 않는다.** 그래서 금지가 아니라 승격으로 뒀고, 조건 셋을 미리 적었다.

    **B 는 중간 계단이 아니다.** 진짜 배치가 생기면 그 호스트는 포트 여섯을 다 들어야 하고 그것은 배치 프로젝트다. 두 대장만 내는 프로세스는 존재 이유가 하나뿐이라 그날 폐기된다. 사다리는 C → A 이고 B 는 거기 없다.

    **규약에서 제일 무거운 줄은 가림이다.** `outcome` 이 `WITHHELD` 인 줄에는 `steps` 키가 없다 — 코드의 방벽(§15.164)과 같은 성질을 적재면에서도 든다. 내보내는 쪽이 편의로 제안 표에서 걸음을 꺼내 실으면 **조회 한 번으로 가림이 풀린다.** 구현 PR 이 이 줄을 주입으로 확인한다.

    **「없음」을 키 누락으로 적지 않는다.** 빼면 «값이 없다» 와 «이 판이 아직 그 필드를 안 낸다» 가 같은 모양이 된다. 이 저장소가 `null` 과 침묵을 가르는 것과 같은 규율이고, 빈 목록도 `[]` 로 적는다.

    **그리고 §15.166 이 일지 없이 대장에만 있었다.** PR 5 때 §15.160 이 그랬던 것과 같은 실수를 되풀이했다 — 대장의 id 가 일지 번호를 가리키는데 그 번호의 항목이 없었다. **이것을 잡는 시험은 여전히 없다.** 이 항목이 그 자리다. 그리고 파일 내보내기는 **사본이지 주인이 아니어서** §15.166 을 닫지 않는다 — 닫히는 것은 승격 때다.

    **(좁힘, §15.294)** 항목 294가 네 목록에 보존 상한을 두어 메모리가 끝없이 늘던 것은 사라졌다. 영속화가 없는 것과 놓은 작업 지시의 표시가 상한 밖인 것은 남는다.

165. **자격의 자리를 물었더니 포트였다 — 그리고 대장에 자원 하나가 빠져 있었다.**

    설계 문서가 자동 승인 선언 목록의 자리를 한쪽은 설정 파일로, 다른 쪽은 `registry` 로 적었다. 둘 다 아직 없어 어느 쪽도 선례가 아니었고, 실재하는 선례는 생성자 인자 하나였다 — `withholdEvery` 에서 시작해 이번 배선에서 결속 정본·비용 함수·재할당 정책·바닥 소유 대장·작업 구역까지 다섯이 같은 모양으로 들어왔다. **답은 「둘 중 하나」가 아니라 「둘 다 구현체다」였다.** 설정 파일이든 `registry` 든 이 층의 코드는 같으므로, 그 선택은 미뤄진 결정이 아니라 **배치의 결정**이다. ADR 43 이 그것이다.

    **§B5 의 근거는 살아남았다** — 프로파일은 기종의 사실이고 자동 승인 허용은 현장 정책이라 한 문서에 두면 기종 교체가 정책 변경을 동반한다. 실측도 같은 방향이다: 이 모듈은 `profile/` 을 직접 읽지 않는다.

    **포트 대장이 자격 조회 포트에 「반복 카운터」를 적은 것은 틀렸다.** 반복 횟수는 이 층이 이미 센다(`approvals`). 밖에 물으면 같은 수의 원천이 둘이 되고, 둘이 갈릴 때 반복 한도가 어느 값으로 걸리는지 판정할 수 없다. 포트가 답하는 것은 선언(유형·범위·만료)뿐이다.

    **반복 카운터는 승인자별로 가르지 않고 이의율은 가른다 — 반대인 것이 맞다.** 반복 카운터가 재는 것은 자원의 상태(같은 조치가 몇 번 반복됐나)이고 근본 원인은 누가 눌렀는지 모른다. 가르면 사람 다섯 번과 에이전트 다섯 번이 열이 아니라 다섯과 다섯이 되어 한도에 안 걸린다 — **지표가 자기 집계 방식에 진다.** 이의율이 재는 것은 판단 주체의 성능이고 에이전트 승인이 바로 그 시험 대상이라, 같은 통에 담으면 사람 승인이 희석한다. **가를지는 재는 대상이 자원인지 주체인지로 갈린다.**

    **그리고 자원 소유 대장에 자원 하나가 빠져 있었다.** §15.162 에서 «빈 칸이 없다» 라고 적었는데 그것은 **표에 올린 일곱 자원에 대해서만** 참이었다. 조치의 자동 승인 권한도 자원이고 소유자는 현장이며, 지금 `approveRemedy` 는 호출자 신원을 받지 않아 누구의 승인인지 남지 않는다 — 무승인 경로가 아니라 **무기명 승인 경로**다. 대장의 규율이 «표에 없는 자원으로 나가는 명령은 아무 관문도 통과하지 않은 명령» 이었으니 이것이 그 경우다. 행을 올리고 §15.165 로 걸었다.

    **조회면은 규약만 적었다.** 이 층은 라이브러리라 진입점도 영속화도 없고, 그것은 결핍이 아니라 경계다 — 프로세스를 들면 그 프로세스의 가용성이 실행 보증의 가용성이 된다. 이 층이 보증할 수 있는 것은 순서와 식별자뿐이며, 커서는 **마지막으로 읽은 식별자**이고 불투명한 값이다. `incident-N` 을 뜯어 번호를 꺼내는 코드는 대조 시험이 없는 형식에 묶인다. 적재의 무한 증가와 영속화 부재는 §15.166 으로 걸었다.

    **설계 §14 의 ADR 표가 39 에서 멈춰 있었다.** 40·41·42 가 `docs/adr/` 에는 있고 표에는 없었다 — 색인이 «번호는 §14 의 행 번호» 라 적고 있는데도. 대는 시험이 없는 표는 낡는다(README 의 한계 수가 그랬던 것과 같다). 네 행을 채웠다.

164. **탐색이 답한 것을 대장으로 냈다 — 「못 찾았다」를 계산한 자리에서 버리고 있었다.**

    `RemedySearch` 의 답은 셋인데(`Found` · `None(NO_CAPABILITY)` · `None(DEPTH_LIMIT)`) 제안 표에 남는 것은 `Found` 뿐이었고 나머지는 계산된 자리에서 사라졌다. 가림까지 세면 밖에서 갈라야 할 것이 셋인데 밖으로 나가는 값은 «제안이 없다» 하나였다 — 「이 기체로는 안 된다」와 「더 찾아보면 있을 수 있다」와 「사람이 먼저 진단하라」가 같은 침묵이 됐다. **새 사실을 만들지 않았다.** 이미 계산된 답을 그때의 두 시각과 함께 옮겨 실었을 뿐이고, ADR 40 의 파생값과 같은 모양이다.

    **거절은 사건 번들이 될 수 없다.** 탐색은 접수 관문 안에서 돌고, 관문이 막으면 실행이 안 생긴다 — 실행 식별자도 근거 창도 막는 결함도 없다. 그런 번들을 열면 빈 창이 «창은 완전한데 아무 일도 없었다» 로 읽히고(§4.3 이 금지한 바로 그것), 검토율의 분모가 거절 수만큼 늘어 §7.2 의 지표가 희석된다. 그래서 번들에 칸을 더하지 않고 대장을 따로 뒀다. 열쇠도 (기체, 주문)이다 — 그것이 탐색의 입력이기 때문이다.

    **가린 것의 걸음은 대장에 안 싣는다.** 조회면이 가린 내용을 내면 조회 한 번으로 가림이 풀리고 의도적 비자동화가 그 자리에서 무의미해진다. `Withheld` 가 값을 안 갖는 것은 누락이 아니라 성질이다.

    **적는 자리는 `record` 하나다.** 관문은 순수 술어라 후보 셋에 물어봐도 아무것도 안 쌓이고(§15.161), 쌓는 것은 실제로 그 기체에 내려 본 쪽이다. 그래서 이 대장은 «무엇을 물어봤나» 가 아니라 «무엇을 시도했고 무엇을 답받았나» 다. 대가는 미배정으로 끝난 순위 목록의 탐색 결과가 안 남는다는 것이고(§15.164), 넓히는 것은 L1 이 실제로 붙을 때다.

    **미믹에서 「없다」를 만들 수 있게 픽스처를 하나 더 뒀다.** 기존 픽스처는 `pick_place` 에 전제가 없어 든 채로도 늘 대안이 나온다 — 그것만 있으면 「없다」의 경로가 시험에서 한 번도 안 밟히고, 그러면 이 변경이 담보하는 것의 절반이 검증되지 않는다. 새 픽스처는 `pick_place` 도 빈손을 요구해서 든 채로는 딛을 스킬이 없다.

    **(닫힘, §15.229)** 이 항목이 남긴 한계 행 «§15.164»(소비자 대기)는 §15.229에서 닫혔다. 작업 지시를 `assign`(곧 `adopt`)으로만 내는 소비자(picasso-ops 실행 호스트)를 위해 `record`를 부르는 쪽에 `adopt`가 더해져, 후보가 전부 관문에서 떨어진 `Unassigned`에서도 후보마다 조치 탐색 레지스터에 줄을, 조치 열을 찾은 후보에는 제안을 남긴다. 적는 자리는 여전히 `record` 하나이고 관문은 순수하며, `Unassigned`에는 여전히 조치 열이 없다.

163. **작업 공간을 구역 이름으로 다뤘다 — 반경으로 지었다면 항상 통과했을 것이다.**

    두 기체의 작업 반경이 겹치면 그것도 셀 전용 자원의 경쟁이다. 그런데 이 저장소에는 좌표도 반경도 없고 미믹에 기하가 없어 **반경 겹침을 틀리게 만들 방법이 없다** — 그렇게 지으면 항상 통과하는 시험이 된다. 공간을 구역 이름으로 다루면 맞대 볼 토큰이 되어 시뮬레이션에서 진짜로 검증된다. 자리 결속의 좌표 칸(§15.156)과 바닥 소유자(§15.162)를 같은 이유로 이름으로 다뤘다. **세 번째다.**

    **`destination` 의 두 뜻이 여기서는 둘 다 센다.** 슬롯 점유는 «아는 것을 놓는 자리» 만 셌는데(§15.159), 공간은 놓을 자리든 갈 자리든 기체가 차지한다. 같은 필드를 자원마다 다르게 세는 것이고, 자원의 성질이 다르기 때문이다. 점검 순회의 `navigate_to` 가 그 경계에 있다 — 자재를 안 나르지만 구역은 차지한다.

    **같은 기체는 안 본다.** 한 기체가 두 자리에 동시에 있을 수 없고 그 배타는 발신자가 든다. 여기서 또 막으면 한 기체가 같은 구역에서 이어 일하는 것이 불가능해진다.

    **모름을 값으로 두지 않았다.** 구역 밖 자리를 «어느 구역도 아니다» 라는 하나의 값으로 묶으면 **구역 밖 자리들이 서로 하나의 구역이 되어** 대장에 없는 두 자리가 서로를 막는다. 세는 쪽과 대는 쪽이 같은 함수(`zoneOf`)를 쓰게 해서, 한쪽만 «모름» 을 다르게 다루는 날이 오지 않게 했다.

    남은 것은 성김이다(§15.163) — 같은 구역 안에서 실제로 겹치는지는 안 본다. 잘게 나누는 것은 현장의 수단이고, 기하로 가르는 것은 실물이 붙을 때다.

162. **안 만든 것에도 자리를 줬다 — 소유자 없는 바닥으로는 명령이 안 나간다.**

    걷는 기체가 셀을 나가면 그 바닥의 소유자가 없다. 플릿은 자기 AMR 만 승인하므로, 이 층이 내는 명령이 **아무 관문도 통과하지 않고** 물리 세계로 나갔다. 선택지는 둘뿐이다 — 소유자를 만들거나, 그 자원을 쓰지 않거나. 무승인 통행을 허용하는 셋째는 없으므로 `unownedFloor` 가 접수에서 막는다.

    **기하가 아니라 이름으로 다룬다.** 이 저장소에는 좌표도 반경도 없다. 바닥을 구역 이름으로 다루면 맞대 볼 토큰이 되어 시뮬레이션에서 진짜로 검증된다 — 좌표였다면 미믹에 기하가 없어 항상 통과하는 시험이 됐을 것이다. 자리 결속의 좌표 칸을 안 들인 것과 같은 판단이다(§15.156).

    **「주인이 없다」와 「대장이 없다」를 갈랐다.** 뜻이 반대다. 접으면 대장을 안 붙인 현장이 통째로 서고, 그러면 이 관문이 곧 꺼진다. 대장에 없는 자리도 «선언 안 됨» 이다 — 모르는 것을 무소유로 단정하지 않는다.

    **한 단위만 무소유여도 주문 전체를 막는다.** 앞 단위가 나가고 뒤에서 막히면 기체가 도중에 선다.

    통로 소유자를 정하는 절차(D 물리 분리 → C 구간 배정 → B 조정층 → A 벤더 흡수)와 승격 트리거(겹치는 구간 셋 초과 또는 바닥 공유 벤더 셋 이상)를 문서로 확정했다. **조건을 먼저 적어 두면 나중에 즉흥적으로 결정되지 않는다.** 구간 예약 포트는 규약만 적고 코드로 두지 않았다 — 소비자가 없으면 짓지 않는다(ADR 9). ADR 42 가 그 결정이다.

161. **재할당에 진동 방지를 붙였다 — 문턱만으로는 왕복을 못 막는다.**

    더 싼 기체가 보일 때마다 옮기면 비용이 1 흔들릴 때마다 두 기체 사이를 왕복하고, 그동안 아무 일도 진행되지 않는다. **라인을 멈추지 않는 것이 최적성보다 우선한다.** 막는 장치가 셋이다 — 도는 단위, 최소 유지 시간, 이득의 문턱.

    **문턱 하나로는 부족하다.** 일이 A 를 떠나면 A 의 부담이 그만큼 줄어 **반대 방향의 이득이 곧바로 생긴다** — 문턱을 넘어 옮긴 직후에는 되돌아오는 쪽도 문턱을 넘는다. 최소 유지 시간이 그 자리를 막고, 시험이 두 장치를 따로 고정한다(옮긴 직후 · 시간이 지난 뒤).

    **동점에서는 안 옮긴다.** `<` 가 아니라 `<=` 다 — 동점에서 움직이는 것이 진동의 시작이다.

    **옮길 일 자신을 양쪽에서 뺀다.** 안 빼면 지금 든 쪽이 그 일 하나만큼 늘 불리해 보여, 이득이 없어도 옮기는 쪽으로 기운다. 결함 주입이 그 자리를 잡았다.

    **도는 단위는 안 옮긴다.** 물리적으로 움직이는 중인 일을 옮기면 한 물건에 소유자가 둘이 된다. 트리거가 사건 번들이 생기는 자리(실패 판정·깨진 전제·잔여 파지)라 실제로는 그때 도는 단위가 없다.

    **언제 물을지는 이 층이 정하지 않는다**(ADR 41). 여기가 답하는 것은 «옮겨도 되는가» 뿐이고, 부르는 것은 배정 정책의 몫이다. 부담을 실행 수로만 재는 것은 한계로 남겼다(§15.161) — 단위 열 개짜리 실행과 하나짜리 실행이 같은 무게로 센다.

    시험을 쓰다가 하나를 잘못 짚었다. **관문은 모르는 기체를 막지 않는다** — *"권위는 발신자다. 여기서 막는 것은 알고도 보내는 일뿐"* 이라 능력을 못 물은 기체의 조건은 지어내지 않는다. 그 성질을 시험으로 고정했다.

160. **배정 관문을 순수 술어로 떼고 제안 포트를 붙였다 — 관문이 부작용을 갖고 있었다.**

    설계안 §7 이 관문의 규칙으로 *"상태를 바꾸지 않고 후보를 고르지 않는다"* 를 적었는데, **그때 관문은 이미 상태를 바꾸고 있었다.** 사슬 검사가 거절을 만들면서 제안을 기록하고(`proposals[key]`), 제안 수를 올리고(`proposalsMade += 1`), 가림 차례를 계산해 가려 뒀다. 후보 셋에 물어보면 제안이 셋 쌓이고 가림 차례가 세 칸 돌아간다 — **묻는 것이 곧 결정이었다.**

    계산과 기록을 갈랐다. `admits` 는 순수하고(탐색 자체는 부작용이 없으므로 안에 남는다), `record` 가 제안을 남기며 **채택을 시도한 쪽만** 그것을 부른다. 물어본 것과 내려다 막힌 것은 다른 일이다.

    **채택 시점에 관문을 다시 묻는다.** 순위가 만들어진 뒤 그 기체의 상태가 바뀌었을 수 있고, 그 창은 통신 지연이 아니라 읽는 자와 쓰는 자가 다르기 때문에 생긴다(같은 프로세스에서 스레드만 나눠도 남는다). 낡은 제안은 여기서 걸린다.

    **전부 떨어지면 미배정으로 남긴다.** 재계산을 요청하면 이 층이 배정기와 관문 사이의 중재자가 되고, 그 자리에 재시도 고리가 생긴다. 대신 후보마다의 사유를 함께 낸다 — 없으면 배정기가 무엇을 고쳐야 하는지 모른 채 같은 목록을 다시 보낸다.

    **비용의 항은 이 층이 아는 것뿐이다.** 진행 중 실행 수와 파지 여부 둘이다. 이동 거리나 잔여 전력은 여기 없다 — 이 층은 좌표도 기체 내부도 모르고(기종 비인지), 지어낸 항을 넣으면 가중치가 아무 의미 없는 수를 곱한다. 같은 비용이면 이름으로 가른다: **재생이 결정적이어야 한다.**

    ADR 41 로 남겼다 — *"배정 정책은 밖에 둘 수 있으나 채택 결정은 소유자가 tick 안에서 내린다."* 나중에 반드시 다시 물어볼 결정이다.

159. **셀 자리의 점유를 축으로 올렸다 — 그리고 그것이 계약의 주어가 아님을 확인했다.**

    상태 축이 파지 하나뿐이라 이 층은 «저 자리가 지금 쓰이는가» 를 몰랐다. 두 주문이 같은 슬롯을 목적지로 삼아도 둘 다 접수됐고, 출발 자리가 비어 있어도 하달한 뒤 로봇이 빈 자리에서 집으려다 실패했다. `occupancyViolation` 이 **자리 경쟁**과 **출발 결품** 둘을 하달 전에 본다.

    **점유는 계약의 사전 조건이 될 수 없다.** 설계안은 이 축이 대안 탐색을 깊게 할 것으로 적었는데, 탐색이 도는 것은 **계약이 선언한 능력 전이**다. 계약의 `PreconditionSubject` 는 *"조건이 보는 로봇 상태"* 이고 값이 늘려면 *"관측하는 어댑터"* 가 있어야 한다고 계약 자신이 적어 뒀다. 슬롯 점유를 보는 것은 어댑터가 아니라 셀 설비(E2)다. 그래서 점유는 **이 층의 관문**이고, 탐색의 상태 공간은 넷 그대로다. 재할당의 전제가 이 축이라는 설계안의 판단은 그대로 맞다 — 배정 관문이 읽을 축이기 때문이다.

    **`destination` 이 두 뜻을 지고 있었다.** «놓을 자리» 와 «갈 자리» 다. 점검 순회의 `navigate_to` 도 목적지를 드는데 그 자리를 채우지는 않는다. 목적지만 보고 점유로 세니 **같은 설비를 두 번 살피는 주문이 서로를 막았고**(기존 시험 둘이 그 자리에서 빨개졌다), 점유를 «아는 것을 놓는 자리» 로 좁혀 갈랐다. 가르는 표시는 `expectedIdentity` 다.

    **플릿 위임은 이 관문이 안 본다.** 처음에는 모든 단위의 출발 자리를 봤는데 용기 인수의 불일치 보고 시험이 빨개졌다 — 플릿이 인수 시점에 대조해 불일치를 보고하는 것이 계약의 약속이고, 여기서 막으면 그 경로가 영영 안 밟힌다. `Route.ROBOT` 으로 좁혔다.

    **결품 판정은 «말이 없으면 판정하지 않는다».** 「비었다」와 「신호 없음」을 접으면 관측 경로가 죽은 셀이 통째로 선다. 다른 자리 제시도 같은 규율이다 — 셀이 그 질문에 답하지 않으면 빈 목록이 아니라 널이 흐른다. 빈 목록으로 접으면 못 물어본 것이 재고 부족으로 읽힌다.

    **점유에 유효 기간을 두지 않았다**(§15.159 로 등록). 실행의 생애로 대신한다 — 시각으로 만료를 두면 아직 도는 실행의 목적지를 남에게 내줄 수 있기 때문이다. 대가는 오래 끄는 실행이 그만큼 자리를 잠그는 것이고, 그것을 가르려면 «이 자리는 곧 쓰인다» 와 «잡아만 뒀다» 를 구별할 진척도 관측이 있어야 한다.

    **등가 변이가 또 하나 있었다.** 자리를 놓는 조건이 실행의 종착과 단위의 완료 둘이었는데, 시험이 «성공으로 끝난 실행» 만 보고 있어 **실행 필터를 빼도 아무것도 빨개지지 않았다.** 실패로 종착하는 경우(계획 단계에서 `NO_SOURCE` 로 죽는 주문)를 시험에 더하니 그때부터 물었다. 필터 둘 다 뜻이 있고, 없던 것은 시험 쪽이었다.

158. **파지 값에 방벽을 세웠다 — 값이 늘면 컴파일이 깨진다.**

    미믹의 `holdOf` 는 태스크 상태에 대해 `else` 없이 분기하며 *"상태가 늘면 여기서 컴파일이 깨져야 한다"* 고 적어 두었는데, **파지 값에는 같은 방벽이 없었다.** 파지를 보는 자리가 전부 `if` 비교라 값이 늘면 조용히 빠진다 — `compare` 의 마지막 `else -> null`, 대안 탐색의 시작 상태 검사, 시험이 손으로 적은 `listOf(NOT_OBSERVABLE, UNSPECIFIED)` 가 그것이다.

    부류를 정하는 자리를 하나 뒀다(`HoldKinds.kt` 의 `isConcreteObservation`, `else` 없는 `when`). `compare` 의 어긋남 모양도 `else` 를 버렸다 — 구체 관측이 셋이 되는 날 `HoldMismatch` 둘로는 모자라고, 그 사실이 거기서 컴파일 오류로 선다. 대안 탐색과 시험은 이름을 다시 적지 않고 분류에서 파생한다.

    **실측으로 확인했다.** 계약에 `HOLD_KIND_PARTIAL = 4` 를 임시로 넣으니 두 자리가 *"'when' expression must be exhaustive"* 로 깨졌다. 같은 값을 **이 변경 이전 코드**에 넣으면 빌드가 초록이고, `compare(HOLDING, PARTIAL)` 이 «어긋남 없음» 으로 나간다 — 이 PR 이 닫는 조용한 통과가 그것이다.

    **첫 시도는 초록이었다.** 주입을 넣고 `:capability:compileKotlin` 을 돌렸는데 오류가 없었다. 생성된 `HoldKind.java` 에는 새 값이 있었지만 `:contracts:compileJava` 가 UP-TO-DATE 로 넘어가 **컴파일러가 보는 클래스에는 주입이 도달하지 않았다.** 이 저장소의 추적 순서(① 시험이 돌았나 ② 주입이 들어갔나)가 그대로 적용되는 자리이고, `--rerun-tasks` 로 다시 돌려 확인했다.

    **관문 절반이 등가 변이였다.** 처음에는 `compare` 가 기대와 관측을 둘 다 막았는데, 기대 쪽을 빼도 아래 `when` 이 같은 답을 내어 **아무 시험도 빨개지지 않았다.** 아무도 구별할 수 없는 검사는 들고 있지 않는다 — 빼고, 그 자리를 `when` 의 비구체 가지가 맡는다.

    `PreconditionCheck.holdViolation` 은 그대로 뒀다. 거기 손으로 적힌 두 값은 **사정 문구를 고르는 데 쓰이고 `else` 가 위반을 내므로**, 값이 늘어도 조용히 통과하지 않는다. 막을 것이 없는 자리에 방벽을 세우면 방벽이 흔해지고 흔해지면 안 본다.

157. **축을 둘로 늘렸다 — 지도가 그대로여도 개체가 바뀌면 같은 이름이 다른 자세다.**

    §15.155 를 닫는다. 지도 판만 보던 검사가 **기체 교체를 통과시키고 있었다** — 세계 모델을 복원해도 티칭의 기준이 달라지는데, 같은 지도에서 배운 결속이라 판이 일치했다. `SiteBinding` 에 캘리브레이션 판을 싣고 같은 규칙으로 판정한다.

    **거절을 축으로 갈랐다**(`SITE_BINDING_STALE_MAP` · `SITE_BINDING_STALE_CALIBRATION`). 접으면 운영자가 지도를 다시 찍을지 개체를 다시 티칭할지 고를 수 없다 — 계약의 자리는 둘 다 `PRECONDITION_UNMET` 이지만 사람이 할 일이 다르므로 이 층의 어휘는 갈라야 한다.

    **둘 다 어긋나면 지도를 먼저 낸다.** 지도를 다시 찍으면 티칭도 따라 하게 되므로 상류부터 고치게 하는 것이 순서다. 다만 사정에는 **어긋난 축을 전부 적는다** — 캘리브레이션이 어긋난 사실을 감추면 지도만 고치고 같은 거절을 한 번 더 받는다.

    **축 하나만 붙은 정본을 정상으로 둔다.** 캘리브레이션 판을 내놓는 정본과 지도 판을 내놓는 정본이 같으리라는 보장이 없어서, `activeCalibration()` 의 기본값이 `NotConfigured` 다. 순서 때문이 아니라 배치의 사실이다.

    `ActiveMap` 을 `ActiveRevision` 으로 고쳤다 — 축 둘이 같은 세 갈래를 쓰는데 이름이 지도만 가리키고 있었다. 검사 9번의 탐색어는 선언 파일에서 유도하므로 개명이 저절로 따라왔다.

155. ~~**자리 이름이 어느 지도에서 배운 것인지 아무도 안 본다.**~~ **(닫힘 — §15.157)** 아래는 그때의 기록이다.

    ADR 35 가 결속을 기체 세계 모델에 맡긴 뒤로 어댑터는 이름을 그대로 넘기고 기체가 푼다. 그런데 **그 이름이 언제 어느 지도에서 등록된 것인지는 어디에도 없다** — `mapId`·`mapVersion` 류의 낱말이 저장소 전체에 0 건이다. 지도가 갱신되거나 개체가 교체되면 같은 이름이 다른 자리를 가리키는데 상위가 그것을 모른 채 명령을 낸다. VDA 5050 이 좌표마다 기준 지도를 달게 한 것이 정확히 이 경로를 막기 위해서다. 판정 로직만이라 시뮬레이션에서 완전히 검증되는 종류이고, §15.155 로 등록한다.

152. **일부러 답을 가렸고, 같은 우회가 반복되는 것을 시스템이 스스로 고발하게 했다.**

    설계안 §7.2 넷째와 §7.3. 남은 장치 둘이고, 이로써 §7 에서 코드가 할 수 있는 것은 다 했다.

    **의도적 비자동화.** 몇 번에 한 번 제안을 가리고 사람이 먼저 진단하게 둔다. 가리는 것은 비용이다 — 그 사건에서 운영자가 더 오래 걸린다. 비용임을 알고 지불하는 결정이어야 유지되므로 기본값을 두지 않고 배치가 명시하게 했다. **0 을 고르는 것도 결정이다.** 그리고 «가렸다» 와 «대안이 없다» 를 다른 답으로 낸다 — 접으면 의도적 비자동화가 능력 부재와 구별되지 않아 운영자가 시스템을 고장으로 읽는다. 상한 초과와 능력 부재를 가른 것(§15.149)과 같은 자리다.

    순서가 뒤집히지 않게 했다. 제안을 보고 나서 적으면 그것은 진단이 아니라 동의다. 그래서 가려 둔 건은 진단을 적어야 풀리고, 이미 적어 둔 건은 다시 가리지 않는다 — 같은 값을 두 번 요구하면 학습이 아니라 절차다.

    **반복 승인은 결함 신호다.** 임시 대안이 매끄럽게 작동할수록 근본 원인을 고칠 압력이 사라진다. 그리퍼를 교체해야 하는데 우회 경로가 매번 잘 돌아가면 아무도 교체하지 않는다. 같은 걸음 열이 같은 기체에서 거듭 승인되면 그 사실을 올린다. 한 번은 조치이고 두 번부터가 양식이라 문턱을 둘에 뒀다 — 한 번에 고발하면 지표가 곧 소음이 되고, 소음이 된 지표는 아무도 안 본다.

151. **자동 진단을 사람이 감사하기 시작했다 — 그리고 이의율 0 이 거짓말이 되지 않게 타입으로 막았다.**

    설계안 §7.2 의 첫째·둘째·셋째. §7 은 기능이 아니라 정책이고, 없으면 앞의 기능이 시간이 지나며 해로워진다 — 같은 분류가 오백 번 나왔는데 그중 오십 번이 오판이어도 아무도 모른다. PR 1 이 자리만 냈던 «사후 확인» 을 **판정(동의·이의)** 으로 바꾸고, 그 위에 지표를 얹었다.

    **이의율만 내면 안 된다.** 아무도 안 읽으면 이의가 0 이고, 그것은 완벽해진 것이 아니라 아무도 안 읽는 것이다. 그래서 읽은 것이 없을 때 이의율은 `null` 이다 — 0 을 돌려주면 그 자리에서 «완벽하다» 로 읽힌다. 검토율과 함께 보라는 규율을 주석이 아니라 타입이 든다. 결함 주입에서 이 한 줄을 0.0 으로 바꾸는 것만으로 지표 전체가 거짓이 됐다.

    분모도 마찬가지다. 이의율의 분모는 전체가 아니라 **읽은 것**이다. 전체로 나누면 안 읽을수록 이의율이 낮아져, 읽지 않는 것이 좋은 성적으로 보인다.

    **판단 경로를 함께 싣는다.** 분류만 내면 아무도 배우지 않는다. 번들이 «무엇을 기대했고 무엇을 봤는가» 를 든다 — 어긋나지 않았을 때도 기대를 남긴다. 왜 아무 판정도 안 났는지까지 따라갈 수 있어야 한다. 근거를 내는 이유는 신뢰가 아니라 학습이다.

    남은 장치 둘(의도적 비자동화, 반복 승인 지표)과 코드가 셀 수 없는 계량 하나는 아래에 적는다.

150. **제안이 승인을 거쳐야만 돈다 — 그리고 제안이 붙을 자리는 하나뿐이었다.**

    설계안 §6.4. 대안은 제안이고, 승인 없이 실행되는 경로는 만들지 않는다. 승인은 사람의 책임 있는 행위이므로 **조치가 요구하는 값도 사람이 준다** — 탐색기는 어느 스킬을 딛을지까지만 계산한다. 어디에 놓을 것인가를 지어내면 승인은 무엇을 승인하는지 모르는 채 누르는 단추가 된다. 승인은 서 있는 제안이 있을 때만 받고, 받은 제안은 지운다.

    **제안이 붙는 자리는 계획 시점 거절이다.** 설계안은 운영자 판단 상태에 붙는다고 적었는데, 그 상태에 이르는 길(발신자의 `PRECONDITION_UNMET`)에는 이 층의 **관측이 없다.** 계약의 스냅샷은 파지를 싣지 않고 `WatchTaskResponse` 만 싣는다 — 도는 단위가 없으면 파지를 볼 길이 없고, 거절의 참조는 주어만 주지 관측값을 주지 않는다. 관측과 선언이 둘 다 있는 자리는 계획 시점 사슬 검사 하나다. 그래서 제안은 `Submission.Rejected` 에 실리고, 승인은 그 주문을 조치 열과 함께 다시 접수한다.

    이것이 «관측 없이 판정하지 않는다» 의 네 번째 적용이다(§15.144 접수, §15.148 어긋남, §15.149 탐색, 그리고 여기). 발신자 거절 경로에 제안을 붙이려면 관측을 실을 자리가 계약에 있어야 하고, 그것은 소비자가 생긴 뒤의 일이다(ADR 9).

    조치 열을 앞에 세우는 길은 `approveRemedy` 하나뿐이다 — `submit` 의 앞자리 인자는 private 이라 밖에서 채울 수 없다. 담보를 시험 하나가 아니라 **구조**가 든다.

    (의도적 제외로 옮김, §15.265) «§15.150» 행은 picasso 와 picasso-ops 어디에도 그 값을 쓰는 면이 없어 의도적 제외의 새 표 «수요 없음» 으로 옮겼다.

149. **대안을 계산하기 시작했다 — 그리고 «못 찾았다» 와 «없다» 를 갈랐다.**

    설계안 §6. 사전 조건과 효과가 선언에 들어온 뒤로 «이 조건을 참으로 만들려면 무엇을 해야 하나» 는 상태 공간의 문제가 됐다. 능력 하나가 전이 하나이고, 조건이 적용 가능성이고, 효과가 결과 상태다. 계획 시점 사슬 검사의 역방향이며, 적용 가능성은 그 검사를 그대로 부른다 — 두 곳이 다른 답을 내면 계획과 제안이 갈린다.

    **호출자 없이 병합한다.** 규칙을 단위 시험으로 고정한 뒤 경로를 붙인다(§9). 순서를 바꾸면 탐색의 오류와 경로의 오류가 섞인다.

    **상한 초과와 능력 부재를 다른 사유로 답한다.** 첫 판은 둘을 한 사유로 접었고, 상한을 0 으로 준 시험이 그것을 «능력 없음» 으로 받았다. 운영자에게 이 둘은 다른 말이다 — 하나는 «범위를 넓히면 있을 수 있다» 이고 하나는 «이 기체로는 안 된다» 다. 못 채운 조건도 함께 싣는다. 빈 목록으로 접으면 둘이 또 같아진다.

    **못 보는 것은 조치로 못 덮는다.** 관측이 `NOT_OBSERVABLE` 인 상태에서 탐색하면, 선언된 효과가 «이제 빈손이다» 라고 답해 관측 불가를 한 걸음으로 지워 버린다. 첫 판이 그렇게 답했고 시험이 잡았다. 효과는 파지를 바꾸지 관측 가능성을 바꾸지 못한다 — §5.2 와 같은 자리다.

    깊이 상한은 성능이 아니라 책임의 문제다. 셋을 넘는 조치 열은 승인 시점에 검토할 수 없고, 검토되지 않는 제안은 제안이 아니라 자동 실행이다. 계약을 안 올리는 이유와 함께 ADR 40 에 적었다.

148. **효과가 관측을 만나 «모른다» 를 줄였다 — 기대는 종료 시점이 아니라 중단 시점의 것이다.**

    설계안 §5. 결함 통지는 «스킬이 실패했다» 까지만 말하고 적재가 어디 있는지는 말하지 않는다. 카탈로그의 효과가 있으면 그 자리를 채울 수 있다.

    **첫 판은 기대를 종료 시점의 효과로 잡았다가 물렸다.** `after(pick_place)` 는 빈손이므로, 쥐고 실패했는데 빈손인 경우가 «정상» 으로 읽혔다 — 설계안 §1.1 이 든 예와 §10.1 둘째 시나리오가 겨냥한 바로 그 경우다. 표가 규범이라고 단정한 것이 근거 없는 판단이었다: 문서 안에서 §5 를 말하는 자리가 셋인데 둘이 중단 시점을 가리킨다. 기대는 **어디까지 갔는지**가 정한다 — 놓기까지 마쳤으면 효과대로, 쥐었다가 놓지 못한 채 끝났으면 든 채, 쥔 적이 없으면 기대할 것이 없다(`expectedAtEnd`). 그래서 단위마다 «한 번이라도 쥔 것을 봤는가» 를 든다.

    이 기준에서 표의 두 줄이 모두 `pick_place` 하나로 도달한다. 쥐었다가 빈손으로 끝나면 적재 유실이고, 놓았다는데 들고 있으면 미완료 파지다. 첫 판이 «첫째 줄은 카탈로그에 대상이 없다» 며 한계 대장에 올린 항목은 해석이 틀려서 생긴 것이라 지운다 — 남겨 두면 없는 한계를 문서가 주장한다.

    **성공으로 끝났는데 어긋난 경우에도 사건을 연다.** 하류는 끝났다는데 손에 남아 있다. 사건을 안 열면 그 사실이 어디에도 안 실린다. 미믹은 성공에 언제나 빈손으로 두므로 그리퍼가 걸린 기체를 시험 쪽 포트로 세웠다.

    **관측이 없으면 판정하지 않는다.** `NOT_OBSERVABLE` 과 침묵은 둘 다 판정 대상이 아니다. 여기서 뚫리면 선언을 근거로 현실을 단정하는 것이 되고, 관측 경로가 죽었을 때 고장난 기체가 멀쩡해 보인다 — 이 저장소가 파지·진행률·결함에서 반복해 거절한 접기다.

147. **사건 하나를 한 자리에 묶었다 — 그리고 설비 신호가 그동안 버려지고 있었다.**

    `INCIDENT_AND_REMEDY_PROPOSAL.md` §4 의 첫 조각. 실패의 사실은 이미 각자의 자리에서 답하고 있었다 — 어느 단위가 왜 멈췄나, 무엇이 실행을 막나, 로봇이 무엇을 들고 있나. 없던 것은 **한 시점의 한 사건으로 묶인 형태**다. `IncidentBundle` 이 그 열 가지 사실에 그때의 근거 창을 붙인다. 새 판단은 하나도 안 한다 — 실패 분류도 잔여 파지도 조건 위반의 주어도 전부 옮겨 싣는 것이고, 다시 계산하면 두 곳의 판정이 어긋난다.

    **설계안 §12 가 물은 여덟 중 둘이 문서의 가정과 달랐다.** 근거 창을 새로 만들어야 하는 줄 알았는데 로봇 이벤트는 이미 `Execution.eventTrail` 이 들고 있었다 — 버려지던 것은 **설비 신호**뿐이었다. `cell.observe()` 를 세 자리에서 부르고 판정에 쓴 뒤 그대로 버려, 사건을 읽을 때 "그때 설비는 무엇을 봤나" 가 영영 없었다. `observeCell()` 이 그 조회를 자취에 남긴다. 그리고 운영자 판단 상태가 계약 밖(미들웨어 모델)이라, 제안 경로가 계약 마이너를 올릴 필요가 없다는 것도 여기서 확인됐다.

    **번들은 전이 순간이 아니라 라운드 끝에 봉한다.** 단위가 닫히는 그 자리에서는 «무엇이 다음 단위를 막는가» 가 아직 안 정해져 있다 — `blockingFaults` 는 다음 단위를 재려 할 때 돈다. 그 자리에서 묶으면 차단 축이 늘 비어, 번들이 «단위의 문제인가 기체의 문제인가» 를 가르지 못한다. 그래서 닫는 여덟 자리는 표시만 하고 `sealIncidents()` 가 봉한다.

    해시는 실 시계와 사후 확인만 뺀다. 설계안은 식별자도 빼라고 했는데 이 층의 식별자는 전부 세는 수라(`exec-N`·`incident-N`) 같은 시드면 같은 값이고, 넣으면 사건의 순서까지 고정된다. `ObservedEvent` 에 `local` 을 더해 계약 이벤트와 이 층의 관측을 갈랐다 — 섞어 번호를 보면 «커서가 안 나아갔다» 로 읽힌다(`EventStreamTest` 가 그렇게 읽고 있었다).

146. **코드 리뷰가 잡은 열 — 낡은 관측이 권위가 되어 있었다.**

    §15.143~145 를 여덟 관점으로 리뷰해 후보 41, 검증 28, 확정 10. 결함 주입 16건이 전부 «잡았다» 였는데도 남은 것들이다 — 주입은 시험이 *묻는* 질문만 확인한다. 가장 무거운 둘이 같은 모양이었다. 미믹의 `currentHold()` 는 «어느 태스크든 HOLDING 이면 든 채» 로 로그 전체를 훑어, 복구에 실패해 든 채로 끝난 옛 태스크가 뒤에 놓고 끝난 태스크를 영원히 가렸다(지울 RPC 도 없었다). 미들웨어의 `RobotView.lastHold` 는 자기 실행의 `WatchTask` 갱신으로만 쓰이고 다시 볼 길이 없어, 사람이 그리퍼를 비운 뒤에도 낡은 HOLDING 이 제출 거절의 권위가 됐다 — KDoc 은 «권위는 발신자» 라 적어 놓고. 미믹은 **가장 최근에 기록된 쥐는 태스크의 파지**(`handsHold`, `record()` 에서 갱신)로, 미들웨어는 **지금 도는 단위의 관측**만으로 바꿨고 `lastHold` 는 지웠다. 종착한 단위의 파지는 정보이지 판정이 아니다.

    나머지: `revise()` 가 사슬 검사를 건너뛰었다 — 이 개정이 실제로 교체·추가할 단위에만 건다. 발신자의 `PRECONDITION_UNMET` 을 FAILED 로 접고 다음 단위로 넘어갔다 — 이제 그 단위는 운영자 판단(`OPERATOR_HOLD`)에 서고 사유·주어가 단위에 남는다. 평가기가 모르는 주어를 위반으로 접어 소비자 쪽 마이너 호환을 깼다 — `Unknown.REFUSE|DEFER` 로 갈라 발신자는 fail-closed, 소비자는 유보. 호스트가 배타 실행 검사를 사전 조건보다 먼저 두어 «이미 도는 태스크» 와 «무엇이 걸렸나» 가 미믹과 갈렸다 — 순서를 바꿨고 패리티 시험이 그 경우를 든다. 벤더 `PRECONDITION_FAILED` 가 주어 참조 없는 `PRECONDITION_UNMET` 으로 나가면서 계약 주석과 모순됐다 — 원천이 둘임을 주석에 적었다. `CancelReport.residualHold` 가 빈손을 «말하지 않았다» 로 접었다 — `JobResponse` 와 같은 규칙으로. `WireTest` 가 계약을 올리며 «상대가 더 새로워도 차단하지 않는다» 사례를 잃었다 — 우리 버전에서 파생한다. `adapter-host` 시험이 픽스처를 읽으면서 Gradle 입력 선언이 없었다. `contract.md` 의 담보 행이 «관측 불가는 통과가 아니다» 를 증명하지 않는 시험만 인용했다. 그리고 카탈로그 효과를 미믹과 미들웨어가 따로 읽던 것을 한 곳(`HoldEffects`)으로.

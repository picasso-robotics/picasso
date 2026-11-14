# 시스템 검증 충실도 및 환경 신뢰도 매트릭스 (Verification & Fidelity Matrix)

본 문서는 `picasso` 미들웨어 시스템의 2,475개 자동화 테스트가 **어느 구간에서 실제 외부 시스템/하드웨어와 연동되고, 어느 구간에서 모의 대역(Mock/In-process)에 의존하는지**를 명확히 구분하여 기술적 검증 신뢰도(Verification Fidelity)를 투명하게 공개하기 위해 작성되었습니다.

---

## 1. 6단계 검증 충실도(Fidelity) 등급 체계

| 등급 | 정의 및 기술적 특성 |
|---|---|
| **실제 하드웨어 (Physical/Real)** | 실제 데이터베이스(PostgreSQL), 메시지 브로커(Mosquitto), 검증 도구(buf) 인스턴스와 직접 통신하여 검증 |
| **실 회선 · 세운 상대 (Real Wire / Emulated Target)** | 실제 네트워크 직렬화 및 HTTP/TCP 전송을 거치며, 응답 엔드포인트만 모의 서버(Stub)로 구동 |
| **실 코드 · 전송 없음 (Real Code / In-Process)** | 실제 운영 런타임 코드를 실행하되 네트워크 소켓 대신 In-process 채널로 바인딩 |
| **대역 (Mock / Test Double)** | 외부 시스템의 사양을 가정한 자체 테스트 더블 구현체 (예: `CellMimic`, `AmrFleetMimic`) |
| **없음 (Absent / Test-Assumed)** | 해당 인터페이스의 실제 대상 시스템이 부재하며, 단위/통합 테스트 코드가 소비자 역할을 대행 |
| **문서 (Documented Spec)** | 코드 레벨 전송 없이 벤더 1차 문서(Proto, IDL, OpenAPI) 기반 정적 매니페스트 대조만 수행 |

> **검증 규칙:** 단일 구간 내에 복수의 검증 경로가 공존할 경우(예: In-process 경로와 실소켓 경로 혼재), 상위 등급으로 임의 일반화하지 않고 복합 등급을 명시합니다. 또한 **계약의 출처(Source of Contract)**를 별도로 명시하여, 자체 정의 규격에 기반한 검증의 한계 범위를 명확히 규정합니다.

---

## 2. 구간별 상세 검증 매트릭스

| # | 구간 | 등급 | 계약의 출처 | 실제 연동 및 모의 범위 상세 | 검증 테스트 매핑 |
|---|---|---|---|---|---|
| 1 | 상위(MES·WMS·SCADA) ↔ `picasso` | **없음** | **자체 정의** (ISA-95 작업 제어 기반) | 실제 상위 시스템과의 통신은 부재하며, 통합 테스트가 소비자로 기능하여 `JobOrder` 발주 및 `JobResponse` 수신 검증. 실패 시 운영자에게 나가는 인시던트 번들(생성 시점·근거 윈도우·해시 결정성)도 이 구간에서 대조 | `SequencingRackTest` · `DeliverContainerTest` · `InspectAssetTest` · `IncidentBundleTest` · `EffectMismatchTest` · `RemedyApprovalTest` · `IncidentReviewTest` · `WithholdingTest` |
| 2 | `picasso` ↔ 계약(gRPC) | **실 코드 · 전송 없음** | **자체 계약** (proto 0.11.0) | 직렬화, 서비스 스텁, 헤더 파이프라인 전체를 실코드로 구동하되 In-process 채널 사용 | 상동 + `EventStreamTest` |
| 3 | 계약 ↔ 에뮬레이터 | **실 코드 · 전송 없음** + **실 회선 다섯** | 자체 계약 | `mimic`은 기종 프로파일 기반 결정론적 에뮬레이터임. 대부분 In-process이며 5개 경로는 실제 TCP 포트(`ServerBuilder.forPort`)를 통해 통신 | `ContractSuite` 전체 · 실 포트 테스트(`mimic/MainTest`, `mimic/StartedInstanceTest`, `harness/MqttBrokerTest`, `harness/ConcurrentEngineTest`) |
| 4 | 계약 ↔ 어댑터 호스트 | **실 코드 · 전송 없음** + **실 TCP 둘** | 자체 계약 | Netty 기반 실소켓 통신을 검증하는 경로(`OrbitLauncherTest`, 포트 오픈 후 ONLINE 발행 순서 검증, `AdapterHostConcurrencyTest`의 실 포트 동시 RPC 및 펌프 스레드 겹침 검증) 외에는 In-process 구동 | `AdapterHostTest` · `AdapterHostConcurrencyTest` · `HostLinkReadTest` · `HostCommandSendTest` · `HostLivenessReadTest` · `HostInDoubtSendTest` · `HostParityTest` · `OrbitLauncherTest` |
| 5 | 어댑터 ↔ 벤더 — **Orbit** | **실 회선 · 세운 상대** | **벤더 공식 명세** (OpenAPI + SDK) | 실제 HTTP/HTTPS 프로토콜(쿠키, Bearer 토큰, 상태코드)을 통과하며, 응답 서버는 `com.sun.net.httpserver` 기반 스텁 연동 | `OrbitHttpLinkTest` · `OrbitLauncherTest` |
| 6 | 어댑터 ↔ 벤더 — **Spot · Digit · G1** | **없음** | **벤더 공식 명세** (SDK Proto·IDL·매뉴얼) | 저장소 내 벤더 독점 SDK 배제 원칙에 따라 네트워크 전송은 수행하지 않으며, `@VendorSurface` 선언과 `vendor-manifest.txt` 간의 심볼 대조 검증 수행 | `*VendorSurfaceTest` 넷 |
| 7 | 발행(MQTT) | **실제 하드웨어** | 자체 토픽·헤더 규격 (§5.5) | Docker 컨테이너 기반 실제 Mosquitto 브로커와 연동하여 토픽 발행/구독, QoS, Last Will 정상 동작 검증 | `MqttBrokerTest` |
| 8 | 레지스트리 HTTP API | **실제 하드웨어** | 자체 REST API | Spring Boot 임의 포트(`RANDOM_PORT`)에 실제 구동하여 `TestRestTemplate` 기반 HTTP 통합 검증 | `*EndpointTest` 여덟 |
| 9 | 레지스트리 ↔ DB | **실제 하드웨어** | 자체 DB 스키마 | Testcontainers 기반 PostgreSQL 16 컨테이너에 대해 Flyway 마이그레이션 및 외래키/CHECK 제약조건 검증 | `registry` 테스트 스위트 전체 |
| 10 | 설비(PLC/WCS) ↔ `picasso` | **대역** | **자체 정의** | `CellMimic`을 통해 시간 윈도우 δ 기반 신호 수신 로직을 검증하나, 신호 스펙은 공장 표준 사례 기반의 자체 모델링임 | `EvidenceWindowTest` |
| 11 | AMR 플릿 ↔ `picasso` | **대역** | **자체 정의** | `AmrFleetMimic` 기반 멱등 이송 작업 지시(Dispatch) 및 취소 정리를 검증하나, 상용 플릿 규격(VDA5050 등)과의 직접 연동은 미수행 | `DeliverContainerTest` |
| 12 | 게이트 ↔ `buf` | **실제 하드웨어** | 벤더 개발 도구 | Docker 환경의 공식 `buf` 도구 및 `protoc` 빌드 아티팩트를 통한 Proto 린트 및 호환성 검증 | `gate` 테스트 전체 |
| 13 | 벤더 판정 (거리·조사) | **문서** | 벤더 1차 자료 | 벤더 공식 문서 및 스펙 기준 정적 적합성 분석 결과이며, 물리적 실기체 구동 확인은 오픈 항목 상태(C-3) | `VocabularyDistanceTest` · `VendorSurveyTest` · `ProfileProvenanceTest` |

---

## 3. 검증 매트릭스 종합 분석

1. **하부 인프라의 실제 하드웨어 검증성**: 레지스트리(Spring Boot + PostgreSQL), 메시지 브로커(Mosquitto MQTT), 빌드 도구(buf) 계층은 완전한 실제 하드웨어 환경에서 통합 테스트됩니다.
2. **로봇 인터페이스 계층의 격리성**: 어댑터 계층은 벤더 SDK 격리 원칙에 따라 매니페스트 대조를 통해 정합성을 검증하며, 실기체 직접 연동(C-3)은 환경적 제약으로 인해 오픈 항목 상태로 명시 관리됩니다.
3. **상위 및 설비 연계 계층의 가정 기반성**: 설비(PLC) 및 AMR 플릿과의 연동 규격은 시스템적 일관성을 입증하기 위한 자체 설계 모델이며, 실제 현장 도입 시 대상 설비에 맞춘 Seam 어댑터 구현이 요구됩니다.

> 마지막 대조: 2026-11-14 · sha256:b760c096bfaf · 열림: C-3

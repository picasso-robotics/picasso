# contracts — 인터페이스 계약 사양 (Interface Contracts)

상위 비즈니스 시스템(MES/WMS)과 이종 로봇 기체 간의 명령·질의(gRPC) 및 비동기 상태 발행(MQTT) 규격, 그리고 메시지 헤더와 계약 식별 정보 체계(§5.5)를 정의하는 핵심 인터페이스 모듈입니다.

계약의 정본(Single Source of Truth)은 `proto/picasso/v1/` 디렉터리 아래의 **여섯** 파일이고, 각 필드별 상세 주석을 통해 필드 도입 목적과 유효 값 도메인을 명시합니다. 본 문서는 전체 계약 구조를 조망하는 아키텍처 개요입니다.

---

## 1. 아키텍처 설계 원칙: 프로젝트 내부 의존성 제로 (Zero In-Project Dependencies)

- **프로젝트 내부 의존성 0 강제**: 게이트 검사 5번을 통해 프로젝트 내 타 모듈에 대한 의존을 엄격히 차단합니다. 계약 계층이 내부 컴포넌트에 의존하는 순간 외부 소비자가 불필요한 내부 종속성을 강제받게 되기 때문입니다.
- **물리적 수치 제약의 계약 배제**: 특정 기체의 수치 제약(예: "최대 적재 하중 5kg")은 계약이 아닌 기종별 프로파일에 격리 정의합니다. 계약에 물리 사양을 포함하면 기종 추가 시마다 계약 스펙이 변경되는 결합도가 발생합니다.

---

## 2. 프로토콜 파일 구성

| 프로토콜 파일 | 정의 사양 및 역할 |
|---|---|
| `common.proto` | 공통 엔벨로프 헤더, 요청 거부 코드(`RejectionReason`), 3값 지원 플래그(`Support`), 참조 식별자 |
| `task.proto` | 태스크 수명주기 상태 10종, 상태 감시 스트림(`WatchTaskResponse`), 대기 상태(`HoldState`), 진행률 근거(`ProgressBasis`) |
| `skill.proto` | 케이퍼빌리티 프로젝션 인터페이스 (`Capability`), 파라미터 메타데이터 선언, 런타임 협상(`Negotiation`) |
| `skill_catalog.proto` | 4대 표준 스킬 파라미터 사양 (`pick_place`, `navigate_to`, `inspect`, `move_relative`) |
| `event.proto` · `fault.proto` | 비동기 3대 이벤트 스트림 및 결함 진단 — 15종 정준 실패 분류 체계 (`FailureClass`) |
| `src/main/kotlin/…/wire/` | 공통 헤더 13개 필드 주입 및 직렬화 규칙 (클라이언트/서버 간 드리프트 방지) |

계약의 공식 버전은 `contracts/build.gradle.kts`의 `contractSemver`에 명시되어 있으며, 버전별 변경 내역은 상단 주석에 누적 관리됩니다.

---

## 3. 스펙 진화 및 거버넌스 규칙

- **발신자-소비자 동시 존재 원칙 (ADR 9)**: 발신자와 소비자가 동시에 구체화될 때만 스키마를 확장합니다. `HoldState`(0.4.0), `FailureClass`(0.6.0), `ProgressBasis`(0.8.0), `Precondition`(0.9.0), `FaultObservability`(0.10.0), 스냅샷의 `HoldState`(0.11.0) 모두 실제 사용처가 확정된 후 도입되었습니다.
- **버전 호환성 거버넌스**: 필드 추가는 Minor 버전, 하위 호환성을 파괴하는 변경은 Major 버전 상향을 요구하며, 게이트 검사 2번(`buf breaking`)을 통해 기계적으로 집행합니다.

---

## 4. 알려진 한계 및 엔지니어링 고려 사항

- **인덱스 기본값 0의 미설정 혼동 가능성 (§15.25)**: `update_index` 및 `sequence`가 0부터 시작하여 Protobuf 기본값(미설정 상태)과 형태상 구분되지 않으나, `schema_id`를 통해 전송 방향을 식별하므로 런타임 오류는 발생하지 않습니다.
- **`GetCapabilitiesResponse` 전용 Rejection 필드 부재 (§15.24)**: 해당 RPC만 식별 정보 불일치 오류를 메시지 본문이 아닌 gRPC 상태 코드(Status)로 전달합니다.

> 마지막 대조: 2026-11-14 · sha256:dd780313b9a6 · 열림: §15.24, §15.25

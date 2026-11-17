# 신규 어댑터 스캐폴딩 템플릿 (Adapter Scaffolding Template)

신규 로봇 기종 연동 시 타 기종의 어댑터 코드를 직접 복제함에 따라 발생하는 특정 기종 편향(Spot의 리스 처리, Digit의 권한 게이트 등)을 방지하고, 표준 구조를 제공하기 위한 스캐폴딩 템플릿입니다.

> 본 디렉터리의 `.kt.txt` 파일들은 컴파일 대상이 아니며, `AdapterTemplateTest`를 통해 `RobotAdapter` 인터페이스의 필수 시그니처를 누락 없이 준수하는지 정적 검증합니다.

---

## 1. 신규 기종 추가 표준 절차 (SOP)

신규 기종 연동은 선행 1~8단계 분석이 완료된 후 본 템플릿을 기반으로 9단계부터 착수합니다.

### 선행 단계: 어휘 거리 분석 ([`docs/vocabulary-distance.md`](../../docs/vocabulary-distance.md))
- `parameter_map`의 `CONVERTED` / `SYNTHESIZED`: `accept` 내부의 변환 및 합성 로직 사양 도출
- `adapter_must_own`: 어댑터가 직접 구현해야 할 책임 사양 (코드량 산출 기준)
- `execution_scope`: 상태 폴링 및 종료 판정 주기 설계 기준
- `limitations`: 거부(`Refusal`) 반환 정책

### 후속 단계: 어댑터 모듈 구현 (단계 9~14)

| 단계 | 수행 작업 내용 | 산출 파일 |
|---|---|---|
| **9** | 신규 어댑터 모듈 디렉터리 생성 및 7대 필수 파일 배치 (`settings.gradle.kts` 등록) | 모듈 루트 |
| **10** | 사우스바운드 포트 선언: 벤더 API 심볼 명시 (`@VendorSurface` 어노테이션 부여) | `<기종>Link.kt` |
| **11** | 벤더 매니페스트 추출: [`tools/vendor-manifest/`](../vendor-manifest/README.md) 도구 활용 | `src/test/resources/vendor-manifest.txt` |
| **12** | 어댑터 핵심 로직 구현: `RobotAdapter`의 필수 메서드 구현 | `<기종>Adapter.kt` |
| **13** | 기종 프로파일 작성: 어휘 거리 분석에서 `YES`로 판정된 스킬만 선언 | `profile/profiles/<기종>.json` |
| **14** | 모듈 문서 작성: 계약 적합성 요약 및 거리 문서 링크 명시 | `README.md` |

---

## 파일 일곱

모든 어댑터 모듈은 아래 7개 구성 파일을 예외 없이 구비해야 합니다 (`AdapterTemplateTest` 검증):

- `README.md`
- `build.gradle.kts`
- `src/test/resources/vendor-manifest.txt`
- `…Link.kt`
- `…Adapter.kt`
- `…AdapterTest.kt`
- `…VendorSurfaceTest.kt`

---

## 반드시 채우는 여덟

`RobotAdapter` 인터페이스에서 기본 구현이 제공되지 않으며 반드시 구체화해야 하는 메서드입니다:

`state` · `accept` · `poll` · `pause` · `cancel` · `hold` · `faults` · `knownSiteNames`

---

## 안 채워도 되는 여덟

벤더 API의 미지원 가능성을 고려하여 '미지원/거부'를 기본값으로 제공하는 메서드입니다:

`resume` · `update` · `retry` · `progress` · `failure` · `result` · `robotSoftware` · `pollObservesVendor`

`pollObservesVendor` 는 `poll` 의 종착이 벤더에서 읽은 그 태스크의 종착일 때만 참으로 둡니다(ADR 54). 지역 변수로 지은 상태면 그대로 거짓입니다 — 호스트가 결과를 모르는 조작을 그 종착으로 걷지 않습니다.

명확한 벤더 API 근거 없이 진행률이나 재시도를 무리하게 구현할 경우 상위 시스템에 허위 완료 및 비정상 진행률을 전달할 위험이 있으므로, 미지원 시 기본값을 유지하는 것이 권장됩니다.

---

## 적재를 잃었을 때 — 세 차원의 규약

미들웨어의 작업 응답(`JobResponse`)는 상관 실패를 새 모양으로 적지 않고 세 차원의 결합으로 적습니다 — 어느 단위가 왜 멈췄나(`incompleteUnits`), 무엇이 실행을 막나(`blockedBy`), 로봇이 무엇을 들고 있나(`residualHold`). 어댑터와 프로파일이 지킬 규약은 셋입니다 (`SequencingRackTest` · `적재를 잃은 실패는 단위·차단·잔여 파지 세 축에 다 적힌다`).

- **단위 실패가 적재를 망가뜨렸으면 `PAYLOAD_LOST` 결함을 함께 발행합니다.** 단위의 실패 분류만으로는 물체가 어디 있는지 알 수 없습니다.
- **그 뒤 새 태스크를 막아야 하면 프로파일 실패 모드에 `can_accept_new_task: false` 를 선언합니다.** 미들웨어는 다음 단위를 보내기 전에 기체가 새 태스크를 못 받는다고 한 결함만 `blockedBy` 에 싣습니다 — 결함은 인터록이 아니고 판단은 밖에 있습니다(§4.6). 선언하지 않으면 유실은 `residualHold` 로만 드러납니다.
- **`hold()` 는 놓친 뒤 `Empty` 를 답합니다.** 잃은 것은 든 것이 아닙니다. 미들웨어는 든 단위가 없으면 마지막 관측(빈손)을 `residualHold` 에 싣지, "말하지 않았다" 로 접지 않습니다.

셀 장치의 증거가 그 슬롯에 **있으면** 미들웨어는 다른 길을 갑니다 — 하위는 잃었다는데 셀엔 있으므로 그 슬롯을 운영자 판단(`OPERATOR_HOLD`)으로 세우고 다음 단위까지 가지 않습니다. 이것은 결함이 아니라 관측의 충돌이며, 사람이 `resolve` 로 가릅니다.

> 마지막 대조: 2026-11-17 · sha256:f5b84ff64de3 · 열림: §15.6

# 환경 전제 사양 — 작업 실행을 위한 현장 인프라 필수 조건

**본 문서는 기계적 적합성 검사 명세가 아닌 현장 설비 및 공정 엔지니어링을 위한 설계 입력 사양(Design Input)입니다.** 로봇을 도입하는 제조 공장 및 물류 창고 구축 시, 특정 태스크를 정상 수행하기 위해 물리 환경 측면에 요구되는 필수 조건과 인프라 제약 사항을 정의합니다.

설계 문서 §15.81에 기술된 바와 같이, **로봇의 실질적 작업 케이퍼빌리티는 단일 기체의 고유 스펙이 아닌 (기체 사양 × 현장 인프라)의 결합 특성으로 결정됩니다.** 동일한 기체라도 현장의 인프라 지원 수준과 자재 제시 방식에 따라 가동 여부가 좌우됩니다.

**근거 등급.** 본 분석의 근거는 벤더 1차 자료(Spot 공개 SDK Proto 152개 8,089 심볼, Digit 공식 매뉴얼 409 심볼)를 전수 분석하여 도출되었습니다. 인용된 모든 심볼 명칭은 `adapter-*/src/test/resources/vendor-manifest.txt`의 공식 매니페스트와 일치합니다.

---

## 1. 전제 검증 가능성 분류: 관측 가능 전제 vs 보증 전제

| 분류 | 정의 및 검증 성격 | 전제 상태 전이 |
|---|---|---|
| **관측 가능 (Observable)** | 로봇 센서 및 어댑터 API 질의를 통해 런타임에 기계적 판정 가능 | CLAIMED → **CONFIRMED / CONTRADICTED** |
| **인프라 보증 (Guaranteed)** | 로봇의 센서/인지 범위를 벗어나 관리 주체(작업자/설비)가 보증해야 함 | **영구 CLAIMED** |

벤더 API 역시 이 차원을 명시적으로 반영하고 있습니다. 예를 들어 `Staircase.KnowledgeType`은 `KNOWLEDGE_TYPE_MAPPED`(지도 기반) · `KNOWLEDGE_TYPE_TRACKED_ONGOING`(실시간 감지 중) · `KNOWLEDGE_TYPE_TRACKED_COMPLETED` · `KNOWLEDGE_TYPE_UNKNOWN`으로 세분화하여 "전제 충족 여부"를 열거형으로 응답합니다.

두 전제 차원은 결코 동일한 검증 상태로 통합되어서는 안 됩니다. 시스템 관측값과 인간의 사전 보증이 분리되지 않으면, 대시보드 상태가 정상(Green)임에도 현장 작업이 실패하고, 그 원인이 인프라 결함임에도 기체 결함으로 오인될 수 있습니다.

---

## A. 지도 및 정렬 (Commissioning & Anchoring)

| 환경 전제 | 벤더 근거 심볼 | 검증 가능성 |
|---|---|---|
| GraphNav 지도가 사전에 녹화되어 있음 | `GraphNavService.DownloadGraph` | 관측 가능 |
| 지점마다 고유 식별 명칭이 부여됨 | `Waypoint.Annotations.name` (*"Kitchen Fridge"*) | 관측 가능 (`GetKnownSiteNames`) |
| 해당 명칭이 운영자에 의해 명시적으로 등록됨 | `Annotations.waypoint_source` ∈ {`ROBOT_PATH`, **`USER_REQUEST`**, `ALTERNATE_ROUTE_FINDING`} | 관측 가능 |
| 지도를 물리 세계 좌표계에 결속하기 위한 앵커 구비 | `graph_nav.Anchoring{anchors, objects}` · `AnchoringHint{waypoint_anchors, world_objects}` | 관측 가능 |
| **녹화 시점과 운영 시점의 물리 환경 배치 일치** | — | **보증** |

> **배치 비용 특성**: Spot의 GraphNav 지도는 실제 하드웨어 로봇을 현장에서 직접 주행시키며 기록해야 생성됩니다. 제조 라인 재배치 시 재매핑 주행이 필수적이며 도면 데이터로부터 자동 생성되지 않습니다.

## B. 위치 결속도 (Location Binding Resolution)

| 환경 전제 | 벤더 근거 심볼 | 검증 가능성 |
|---|---|---|
| 이동 목적지 — 2D 웨이포인트 수준으로 충분 | `Waypoint.waypoint_tform_ko`(`SE3Pose`)가 **로봇 정지 위치** 정의 | 관측 가능 |
| **적재/파지 위치 — 웨이포인트 좌표만으로 불충분** | `Annotations` 내에 조작 대상의 6DoF 자세 정보 부재 | §15.79 오픈 항목 |
| 조작 위치를 6DoF 자세와 함께 저작 가능 | `MutateWorldObjects`(`ACTION_ADD`) · `WorldObject.transforms_snapshot` | 관측 가능 |
| **점검 대상이 세계 모델(World Model)에 등록되어 있음** | `WorldObject.name` (`ListWorldObjects`) — `inspect(target)` 질의 기반 | 관측 가능 |
| **점검 위치 정지 시 대상이 카메라 화각 내에 포함** | 화상 취득은 센서 지정 방식이며 타깃 지향 방식이 아님 (`AcquisitionRequestList`) — 웨이포인트 녹화 시 대상 가시성을 확보하는 것은 작업자 책임 | **보증** |

> 동일한 위치 명칭이라도 단순 이동(`navigate_to`)에는 충분하지만 정밀 조작(`pick_place`)에는 부족할 수 있습니다. 예를 들어 2차원 웨이포인트 좌표는 선반 단수나 적재 높이 정보를 포함하지 않습니다.

## C. 자재 제시 방식 (Material Presentation)

| 자재 제시 방식 | 엔지니어링 결과 및 특성 | 검증 분류 |
|---|---|---|
| **품종별 전용 용기 및 지정 구역 제시** | 위치 자체가 자재의 식별 정보를 보증하므로 마킹 및 복잡한 시각 인지 불필요 | **보증** |
| 혼재 적재 제시 (Bin Picking / Mixed) | 런타임 객체 식별 인지 필요 (D 마킹 또는 E 비전 모델 배포 전제 수반) | **보증** |

> 공정이 품종별 전용 구역 제시를 지원하면 객체 식별 복잡도가 획기적으로 낮아집니다. 고정형 워크셀 기반의 Pick-and-Place 공정이 현장에서 선제적으로 상용화된 배경입니다.

## D. 마킹 인프라 (Fiducial Markers)

| 환경 전제 | 벤더 근거 심볼 | 검증 가능성 |
|---|---|---|
| AprilTag(36h11) 부착 인프라 구비 | Spot `WorldObject.apriltag_properties.tag_id` · Digit `ObjectSelector.april_tag_id` | 관측 가능 |
| **바코드·QR·RFID 네이티브 미지원** | Spot 8,089개 심볼 기준 **0건** | — |

> 개별 부품 단위의 태깅은 운영 비용상 비현실적입니다. 재사용 가능한 용기, 파렛트, 지그, 고정 앵커에 태그를 부착하는 방식이 권장되며, 이 경우 태그는 컨테이너 식별 정보를 식별하고 내용물 정보는 상위 WMS/MES가 연계 관리합니다.

## E. 시각 인지 모델 배포 (Vision Inference Worker)

| 환경 전제 | 벤더 근거 심볼 | 검증 가능성 |
|---|---|---|
| 외부 ML 인퍼런스 워커 프로세스 기동 | `NetworkComputeBridgeWorker.WorkerCompute` | 관측 가능 (`ListAvailableModels`) |
| 모델이 요구되는 클래스 라벨셋을 반환 | `ModelLabels.available_labels`(`repeated string`) | 관측 가능 |
| 신뢰도 문턱값을 초과하는 객체 가시성 | `NetworkComputeInputData.min_confidence` | 부분적 |
| **조명, 카메라 화각, 작업 거리가 모델 전제 충족** | — | **보증** |

## F. 충전 및 도킹 인프라 (Charging & Docking Station)

| 환경 전제 | 벤더 근거 심볼 | 검증 가능성 |
|---|---|---|
| 전용 도크 설치 및 식별자(ID) 배정 완료 | `DockProperties{dock_id, type, frame_name_dock}` · `docking.ConfigRange{id_start, id_end, type}` | 관측 가능 |
| 도킹 스테이션이 현재 사용 가능한 유휴 상태 | `DockProperties.unavailable` | 관측 가능 |
| 단일 배터리 용량을 초과하는 작업에 대한 자동 복귀 정책 설정 | Autowalk `Element.battery_monitor` · Orbit `SiteWalk.batteryMonitor` | 관측 가능 |
| **단일 도크에 대한 다중 기체 점유 충돌 방지** | 벤더 공식 문서: *"as of 4.1.0, users are responsible for ensuring the physical dock is not taken"* 명시 | **보증** |

## G. 통행 가능성 (Passability — Floor, Stairs, Doors)

| 환경 전제 | 벤더 근거 심볼 | 검증 가능성 |
|---|---|---|
| 통행 경로 상의 계단이 지도에 기등록됨 | `Staircase.KnowledgeType.KNOWLEDGE_TYPE_MAPPED` | 관측 가능 |
| 바닥 API 표면 마찰 계수의 적합성 확보 | `FootState.TerrainState.ground_mu_est` | 관측 가능 (사후 추정) |
| **문의 경첩 위치 및 개폐 방향(Swing Direction) 사전 구성** | `DoorCommand.AutoGraspCommand{hinge_side, swing_direction}` | **보증** (설정 파일 주입) |
| 도어 메커니즘 유형 (푸시형 vs 핸들 래치형) | `AutoPushCommand` vs `AutoGraspCommand` (후자는 매니퓰레이터 필수) | **보증** |
| 문 가시성 확보 여부 | `DoorCommand.Feedback.Status.STATUS_NOT_DETECTED` | 관측 가능 (사후 감지) |

> "도어 통과" 케이퍼빌리티는 물리 문의 구조에 따라 실행 전략이 분기됩니다. 단순 푸시 도어는 팔 없이 몸체 추진으로 개방 가능하나, 래치형 손잡이는 매니퓰레이터가 필수적입니다. 경첩 방향 등의 구조 정보는 로봇이 자율 판단하지 않으며 엔지니어가 설정 파라미터로 명시해야 합니다.

## H. 통행 금지 구역 (Keep-out / No-Go Regions)

| 환경 전제 | 벤더 근거 심볼 | 검증 가능성 |
|---|---|---|
| 진입 불가 영역(No-Go) 지오메트리 정의 | `NoGoRegionProperties` (벤더 주석: *"Property for a **user** no-go"*) | 관측 가능 |

## I. 기체 하드웨어 구성 (Hardware Configuration)

| 환경 전제 | 벤더 근거 심볼 | 검증 가능성 |
|---|---|---|
| 매니퓰레이터 암 장착 | `RobotState.manipulator_state` (*"only populated if an arm is attached"*) | 관측 가능 |
| 추가 페이로드 프로파일 등록 완료 | `Payload*` (관련 심볼 140개) | 관측 가능 |

## J. 무선 네트워크 인프라 (Wireless Connectivity)

| 환경 전제 | 벤더 근거 심볼 | 검증 가능성 |
|---|---|---|
| **실시간 명령 제어 경로의 무단절 보증** | BD 공식 가이드: *"send short-lived commands and **continuously resend** them so that the robot stops in the event of a client-side issue"* 요구 | 부분적 |

> 제어 패킷이 단절되면 로봇은 안전을 위해 즉시 동작을 정지합니다. 이는 통신 결함이 아니라 페일세이프(Fail-safe) 설계 원칙입니다. 따라서 통신 음영 구역은 단순한 네트워크 지연이 아니라 **작업 수행이 성립할 수 없는 영역**으로 다루어져야 합니다.

## K. 시계 동기화 (Clock Synchronization)

| 환경 전제 | 벤더 근거 심볼 | 검증 가능성 |
|---|---|---|
| 발신자(어댑터 호스트 및 에뮬레이터), 셀 제어기, 미들웨어, 허가 원천 간 시계 차가 근거 시간 윈도우 및 작업 허가 유효기간 허용 폭 이내로 유지되어야 합니다. | 벤더 제공 표면 없음 (`t_r`은 로봇 장치 내부 시각이 아닌 발신자 프로세스의 자체 시계 기준) | 현장 실측 필요 |

> 시스템 전반에서 세 가지 시계가 절대 시각으로 상호 비교됩니다. 완료 시각 `t_r`은 로봇 설비 자체 시각이 아니라 발신자 프로세스(어댑터 호스트 또는 에뮬레이터)가 상태 전이를 기록할 때 자체 시계로 타임스탬프를 부여한 계약 헤더 `state_as_of`입니다. 셀 관측 시각 `t_p`는 설비가 제공하거나 미들웨어가 수신한 `SlotSignal.observedAt`이며, 기한 판정에는 미들웨어 시계 `now()`가 사용됩니다. 작업 허가는 허가 원천이 부여한 `PermitScope.observedAt` + `validFor`와 미들웨어 시계를 비교합니다. 검증 대기 시 `t_p`가 `[t_r - before, t_r + after]` 구간에 포함되어야 유효한 근거로 인정되며, 기한인 `t_r + after`는 미들웨어 시계와 비교됩니다(기본 설정값: 앞 폭 30초, 뒤 폭 15초, 현장 타이밍 `evidenceWindowBefore`/`evidenceWindowAfter` 설정 범위 각 5~120초). 발신자 시계가 뒤 폭 이상 늦으면 첫 확인 시점에 이미 기한이 지나 신호가 근거 시간 윈도우 바깥으로 밀려 즉시 `UNVERIFIED`로 처리됩니다. 반대로 발신자 시계가 앞 폭 이상 앞서면 신호가 윈도우 시작점보다 앞서 도착하여 불필요한 대기를 거친 뒤 `UNVERIFIED`가 됩니다. 또한 미확정 잠정 완료 요건으로 `t_p >= requestedAt - before`를 만족해야 하므로, 셀 제어기 시계가 미들웨어보다 앞 폭 이상 늦으면 잠정 완료가 서지 않습니다. 허가 원천 시계가 미들웨어보다 `validFor` 이상 늦으면 모든 수신 값이 `EXPIRED`로 처리되며, 앞설 경우 만료가 지연되어 작업 허가가 부당하게 연장되는 개방 편향이 발생하므로 더 위험한 방향입니다(mission-ops 환경에서는 허가 어댑터가 미들웨어와 동일한 `HostClock` 기반 읽기 시각을 `observedAt`에 기록하므로 해당 시계 차 문제가 발생하지 않습니다).

- **발신자 시계 지연 허용 폭**: `시계 차 < after - L` (발신자 시계가 미들웨어보다 늦은 경우, 뒤 폭에서 지연 합 L을 뺀 값 미만이어야 합니다).
- **발신자 시계 선행 허용 폭**: `시계 차 < before - L` (발신자 시계가 미들웨어보다 앞선 경우, 앞 폭에서 지연 합 L을 뺀 값 미만이어야 합니다).
- **셀 제어기 시계 지연 허용 폭**: `시계 차 < before` (셀 제어기 시계가 미들웨어보다 늦은 경우로, 미확정 잠정 완료 요건 충족을 위해 앞 폭 미만이어야 합니다).
- **허가 원천 시계 허용 폭**: `시계 차 < validFor` (자체 시계를 제공하는 허가 원천의 경우 유효기간 미만이어야 하며, 시계가 앞서면 유효기간이 연장되므로 더 엄격한 관리가 필요합니다).

기본 설정 기준 가장 좁은 허용 폭은 뒤 폭 기준인 15초이며, 현장 타이밍을 낮추면 허용 폭은 더욱 좁아집니다. (여기서 L은 셀 폴링 주기, `pump` 주기, 전달 지연의 합을 의미합니다.)

미들웨어는 스냅숏 왕복을 통해 측정한 발신자 시계 차 구간 전체가 근거 시간 윈도우의 좁은 쪽 절반인 임계를 엄격히 벗어나면 트레이스에 `CLOCK_SKEW`를 기록합니다. 또한 신호가 존재하나 윈도우 밖이어서 `UNVERIFIED`로 끝난 단위에는 시계 차 의심 메모를 남기되, 미들웨어가 시계를 직접 보정하지는 않으므로 판정은 바꾸지 않습니다.

---

# Digit — 기종 간 환경 전제 모델 대비

기종이 다르면 요구되는 인프라 전제와 배치 비용의 구조도 달라집니다. 아래는 Digit 공식 매뉴얼 및 SDK 409개 심볼 전수 조사를 바탕으로 Spot(A~J)과 대비 분석한 결과입니다.

## 3대 기종 환경 전제 대비표

| 비교 항목 | Spot (8,089 심볼) | Digit (409 심볼) | G1 (161 심볼) |
|---|---|---|---|
| **지도 생성 방식** | **실기 주행 기반 녹화 (Walk-through)** | **2D 평면도 이미지 업로드** | **자체 지도 계층 부재** |
| 정렬 (Alignment) | `Anchoring` (선택적) | **랜드마크 3차원 자세 실측 등록 필수** | 지원 없음 |
| 위치 결속의 충분성 | **선언 없음** (§15.79 오픈 항목) | **`object-attribute.pickable` 명시** | 위치/오브젝트 개념 부재 |
| 정적 vs 일시적 수명주기 | 구분 속성 없음 | **`transient` · `timeout` · `persistent`** | 지원 없음 |
| 충전 자동 복귀 | `DockProperties` · `batteryMonitor` | 공개 API 표면에 도크 관련 인터페이스 부재 | 지원 없음 |
| 통행 금지 구역 | `NoGoRegionProperties` | `object-attribute.keep-out` | 지원 없음 |
| 도어 / 계단 모델 | `DoorCommand` · `Staircase` | 공개 API 표면에 부재 | 지원 없음 |
| 배타적 제어 권한 | `Lease` (epoch · `STATUS_OLDER`) | `request-privilege{**priority**}` | `SWITCH_TO_USER_CTRL` / `SWITCH_TO_INTERNAL_CTRL` |
| **지형 제약 표현** | `ground_mu_est` · `Staircase` | `step-clearance` · `steppable` | `SET_SWING_HEIGHT` (원시 값 제어) |
| 가용 케이퍼빌리티 런타임 열거 | `ModelLabels.available_labels` | `object-attribute` | **`ARM_ACTION_GET_ACTION_LIST`** |
| 세션 종료 조건 제공 | 자연어 권고 (*"continuously resend"*) | — | **`terminations.hpp` 판정 함수 7종** |

## A′. 지도 커미셔닝 패러다임 차이

```
set-floorplan-map{ image-data, resolution, origin, initial-pose, landmarks, name }
landmark{ id, pose, std-dev }
tag-measurement{ id, corners, base-to-tag-pose, update-time }
```

**Digit은 도면(CAD/이미지)을 기반으로 시작합니다.** 그러나 이에 따른 3대 전제가 수반됩니다:
1. 고해상도 평면도 이미지 필수
2. 정확한 축척 해상도(m/px)와 기준 원점 정의
3. 현장에 물리 랜드마크(AprilTag)를 실측 부착하고 좌표 등록 (`landmark.std-dev`를 통한 측정 오차 반영)

> **배치 비용 모델의 차이**: Spot은 작업자가 로봇을 현장에서 직접 조종해야 하며 라인 변경 시 재주행이 필요합니다. Digit은 도면이 있으면 초기 주행은 불필요하나 현장 마커 측위 작업이 요구됩니다. 도면 관리가 엄격한 최신 공장에서는 Digit 방식이 유리하며, 현장 실제 하드웨어와 도면의 불일치가 큰 레거시 현장에서는 반대입니다.

## B′. 위치 명칭의 결속도 — Digit의 객체 속성 체계

```
object-attribute{ name, pose, box-geometry, polygon, mass, april-tag-id,
                  pickable, steppable, keep-out,
                  parent, children, transient, timeout, velocity }
```

**`pickable` 속성이 핵심입니다.** §15.79에서 제기된 "위치 이름이 파지 조작에 충분한 결속을 갖는가"에 대해 Spot은 선언 속성이 부재하지만, **Digit은 현장 엔지니어가 객체마다 `pickable` 여부를 사전 저작합니다.** `steppable`, `keep-out`도 동일한 컨텍스트입니다.

또한 `transient`, `timeout`, `notify-objects.persistent` 속성을 통해 벤더 차원에서 영속 객체와 일시 객체를 명확히 분리합니다.

## C′. 충전 인프라 — 공개 API 표면의 한계

`dock` 관련 심볼은 0건이며 충전 상태는 `battery-status.charge-percent` 단일 속성만 제공됩니다. Spot과 달리 공식 API에는 도크 자동 등록, 도킹 명령, 배터리 임계치 기반 복귀 로직이 노출되어 있지 않습니다.

> **근거 등급 주의**: "하드웨어적으로 불가능하다"가 아니라 "공개 조사 표면(`survey_scope`)에 해당 API가 노출되지 않았다"는 의미입니다. 엔지니어링 배치 관점에서는 현시점에서 자체 구현만으로 자동 충전 복귀 일감을 구성할 수 없으며 작업자 개입 또는 외부 프로토콜이 요구됩니다.

## D′. 기동 방식에 요구되는 물리 인프라

```
start-mode{ assisted, auto, push-up, rope }
```

**`rope` 모드가 존재합니다.** 기동 시 로프나 갠트리 크레인 같은 기구적 지지 설비가 필수적인 모드가 포함되어 있으며, 이는 기체 기동 자체에 현장 물리 인프라가 전제될 수 있음을 시사합니다.

## E′. 이동 파라미터 기반의 환경 전제

```
mobility-parameters{ avoid-obstacles, obstacle-threshold,
                     step-clearance, feet-avoid-unsteppable-regions }
```

`feet-avoid-unsteppable-regions`는 통행 불가 영역이 사전에 저작되어 있어야 유효하며, `step-clearance`는 허용 단차 높이에 대한 인프라 전제를 수치로 노출합니다.

## F′. 배타 제어 및 세션 관리

`request-privilege{privilege, priority}` 기반의 정수형 우선순위 선점 메커니즘을 사용합니다. 반면 Spot은 임대권(Lease) epoch과 `STATUS_OLDER` 기반의 검증을 사용합니다. Digit은 `start-persistent-session` · `resume-persistent-session` · `persistent-session-token`을 지원하여 끊긴 세션의 복구를 지원합니다.

## G′. 도어 및 계단 제어 API 표면

`door` 0건, `stair` 0건, `elevator` 0건입니다. `steppable`과 `step-clearance`가 제공되므로 단차 극복은 가능하지만, 문의 개폐 조작이나 계단 전용 주행 모델은 공개 API 표면에 포함되어 있지 않습니다.

## 도메인 어휘 조사 규율

초기 조사에서 `nogo` / `no-go` 키워드로 검색하여 0건임을 확인하고 "금지구역 기능 부재"로 속단할 위험이 있었습니다. Digit 벤더는 동일 개념을 `keep-out`으로 정의합니다. 부재 여부를 판정할 때는 자사 고유 어휘가 아닌 **벤더 고유 도메인 어휘를 전수 조사**해야 하며, 검색 실패를 기능 부재로 혼동해서는 안 됩니다 (§15.65).

---

# G1 — 전수 조사 확장에 따른 전제 도출

> 초기 분석에서는 44개 심볼만을 기반으로 전제를 3개로 축약 보고했으나, 이는 G1 4개 서비스 중 `loco` 단일 서비스만 조사한 오류였습니다. 서비스 4종 전체와 `terminations.hpp`, `unitree_hg` IDL 11개를 포함하여 **141개 심볼**로 재측정하였으며, C++ IDL 전용 파서를 보강하여 `hg/SportModeState_`, `hg/AgvBmsState_`, `hg_doubleimu/doubleIMUState_`를 추가 추출한 최종 분석값은 **161개 심볼**입니다 (`profile/distance/unitree-g1.json`).

## G1 제공 4대 서비스 구성

```
loco        SET_VELOCITY · SET_FSM_ID · SET_STAND_HEIGHT · SET_SWING_HEIGHT · SET_BALANCE_MODE
arm_action  EXECUTE_ACTION · EXECUTE_CUSTOM_ACTION · GET_ACTION_LIST · STOP_CUSTOM_ACTION
agv         AGV_MOVE · AGV_HEIGHT_ADJUST
audio       TTS · ASR · START_PLAY · SET_VOLUME · SET_RGB_LED
```

**`GET_ACTION_LIST`의 제공**: 기체가 실행 가능한 상지 조작 액션 목록을 런타임에 동적으로 열거합니다. 이는 Spot의 `ModelLabels.available_labels`와 유사하며 ADR 36 2계층의 어휘 출처로 기능합니다.

## 결정론적 종료 조건 (Terminations)

`common/terminations.hpp` 헤더에 안전 정지 및 제어 모드 해제 조건 함수 7종이 명시되어 있습니다.

| 종료 조건 함수 | 검증 대상 환경 전제 | 검증 가능성 |
|---|---|---|
| `bad_orientation` | 자세 각도가 안전 임계치 이내 유지 | 관측 가능 |
| `joint_vel_out_of_limit` · `ang_vel_out_of_limit` | 관절 속도 및 각속도가 한계치 이내 유지 | 관측 가능 |
| **`lost_connection`** | **명령 통신 채널의 지속성 유지** | 관측 가능 |
| **`low_battery`** | **배터리 잔량이 최소 구동치 이상 유지** | 관측 가능 |
| **`motor_casing_overheat` · `motor_winding_overheat`** | **구동 모터의 케이싱 및 권선 온도가 과열 한계 미만** | 관측 가능 |

Spot이 자연어 가이드(*"continuously resend"*)로 권고한 통신 안전 전제를 G1은 명시적 판정 함수(`lost_connection`)로 구현하며, 모터 과열 역시 케이싱과 권선을 분리하여 하드웨어 상태를 엄밀하게 노출합니다.

## 벤더 에러 어휘 체계

```
UT_ROBOT_LOCO_ERR_{ LOCOSTATE_NOT_AVAILABLE, INVALID_FSM_ID, INVALID_TASK_ID }
UT_ROBOT_ARM_ACTION_ERR_{ INVALID_ACTION_ID, HOLDING, ARMSDK, INVALID_FSM_ID }
UT_ROBOT_G1_AGV_ERR_{ NOT_INIT, EXEC_MOVE, EXEC_HEIGHT_ADJUST }
UT_ROBOT_AUDIO_ERR_COMM
```

## 파지 압력 센서 지원

`HandState_{motor_state, press_sensor_state, imu_state, error, power_v, power_a}` 및 `PressSensorState_{pressure, temperature, lost}`가 제공됩니다. 이는 인터페이스 계약의 파지 검증(`verify_grasp`)을 위한 실제 하드웨어 근거가 되며, `lost` 플래그는 센서 자체 통신 단절을 분리 감지합니다.

## 저작형 환경 모델 부재

지도, 위치 명칭, 객체 월드 모델, 도킹, 비전 인퍼런스 인터페이스가 G1 공개 API에는 포함되지 않습니다. 따라서 G1은 현장 환경 구조를 내재화한 고수준 작업을 직접 처리할 수 없습니다. 이는 기체 제어기 설계가 저작형 환경 계층을 배제하고 하위 모빌리티 제어에 집중된 결과이며, 상위 제어기가 해당 환경 모델을 전담해야 함을 의미합니다.

---

## 공정 간 인계점 (Handover Point — 이종 실행기 연계)

**공정 간 인계 전제.** 로봇이 피킹할 자재가 지정 위치에 이미 배치되어 있어야 합니다. 통상 시퀀싱 워크셀에서는 AMR이 컨테이너를 지정 위치에 반입·하역하고, 휴머노이드 로봇의 `pick_place` 작업이 그 이후 시작됩니다 ([`scenarios.md`](scenarios.md) §1·§3).

**관측 가능성의 이원화 구조:**

| 검증 항목 | 로봇 자체 관측 가능 여부 | 벤더 근거 |
|---|---|---|
| 해당 위치에 **물체가 존재하는가** | 기종별 상이 — Digit: 월드 모델(`notify-objects`, `object-attribute.pickable`), Spot: `WorldObjectService`, G1: 지원 없음 | 매니페스트 |
| 해당 물체가 **요청된 지정 컨테이너인가** | **불가.** 용기 식별은 공정 간 인계 설비(게이트 리더, 바코드 센서) 또는 상위 시스템의 책임 | — |

따라서 자재 공정 간 인계 전제는 '물체 존재 여부'의 관측 가능 영역과 '품목 일치 여부'의 보증 영역으로 양분되며, 후자는 영구 CLAIMED 상태로 유지됩니다. 계약 레이어는 물체 부재 시 실패 응답(자재 없음)을 반환하고, 식별 정보 판정은 상위 3계층이 설비 신호를 통해 최종 승인합니다.

---

## 오픈 항목 과제 (Known Open Issues)

- **전제 선언 위치 확정**: 기종 프로파일은 기체 단위이나 환경 전제는 (작업 × 현장) 조합에 바인딩되므로 적절한 메타데이터 배치 구조 결정 필요 (§15.81).
- **보증 항목 기록 표준화**: 영구 CLAIMED 항목과 런타임 CONFIRMED 항목의 격리 기록 체계 수립.
- **분류 체계(A~J) 확장성**: Digit 분석 시 기동 설비(D′)가 신규 도출된 바와 같이 신규 기종 추가에 따른 분류 체계 확장 검증 필요.
- ~~**G1 조사 범위 제한**~~: 서비스 4종 및 IDL 전수 분석을 통해 161개 심볼 재측정 완료로 종결 처리.
- **안전 구역 격리 명세**: 작업자-로봇 물리 분리 방안과 [ADR 32](adr/0032-safety-boundary.md)(안전 계통의 계약 외 격리) 간의 상호작용 명세 필요.

> 마지막 대조: 2026-11-11 · sha256:1e5ea49d09af · 열림: §15.81, §15.79, §15.2, C-3

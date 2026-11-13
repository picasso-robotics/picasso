rootProject.name = "picasso"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

include("contracts", "profile-model", "gate", "mimic", "client", "harness", "registry")

// 능력의 투영과 판정 — 프로파일 → Capability, 그리고 요구 집합 대 Capability 협상.
// mimic 과 어댑터 호스트가 공유한다(ADR 29 의 이유 그대로).
include("capability")

// 발신자의 위쪽 결선 — 브로커 발행과 레지스트리 적재. mimic 과 어댑터 호스트가 공유한다.
include("uplink")

// 어댑터의 북쪽 — RobotAdapter 하나를 계약의 gRPC 서비스 뒤에 세운다. 기종을 모른다.
include("adapter-host")

// 미들웨어의 가운데 — 정준 모델과 공통 실행 구조(ADR 38). 이름이 picasso 인 것은
// 이것이 곧 이 저장소가 만드는 것이기 때문이다. 나머지는 그 부품이다.
include("picasso")

// 미들웨어를 담는 쪽의 부품(승인 호스트·선언 파일·번들 쓰기). picasso 의 공개 API 만 쓴다(§15.314).
include("host-kit")

// 어댑터는 기종마다 모듈 하나다(ADR 33). 게이트 7번의 대상 셋
// (client·mimic·harness) 밖에 두는 것이 요점이며, 기종을 아는 코드가 갈
// 곳이 여기라서 나머지가 기종을 모를 수 있다.
include(
    "adapter-core",
    "adapter-unitree-g1",
    "adapter-boston-dynamics-spot",
    "adapter-agility-digit",
    // 기체가 아니라 **플릿**에 붙는 첫 어댑터 — ADR 37 의 발견 경로가 여기서만 실증된다.
    "adapter-boston-dynamics-orbit",
)

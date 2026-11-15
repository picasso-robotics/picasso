// 플릿 미믹 — 프로젝트용 플릿 계약(`AmrFleetPort`)의 인프로세스 더블(설계 변경 이력 §15.326).
//
// picasso 시험 소스에만 있던 `AmrFleetMimic` 을 출하 모듈로 옮겼다. 담는 쪽(mission-ops 의 모의 실행 하네스·실행 호스트)이
// 실 플릿 없이 플릿 위임 계획을 돌리게 하는 것이 목적이다. **벤더 플릿 API 의 재현이 아니다** — 플릿 proto 는 만들지 않는다(ADR 9).
// 쓰는 것은 picasso 의 공개 API(포트 계약) 뿐이며, picasso 는 이 모듈을 시험에서만 쓴다(순환 없음).
dependencies {
    // 공개 서명이 picasso 의 타입(`AmrFleetPort`·`TransportOrder`·`TransportStatus` 등)을 드러낸다.
    api(project(":picasso"))

    testImplementation(kotlin("test"))
}

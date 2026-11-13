import org.gradle.api.tasks.PathSensitivity

// 미들웨어를 **담는 쪽**의 부품 — 승인 호스트(`POST /approvals`, 루프백만), 자동 승인 자격 선언 파일 읽기,
// 인시던트 번들·작업 응답 파일 쓰기. `picasso` 는 라이브러리라 진입점·파일·소켓을 모르므로(ADR 44, 설계 §6)
// 그 일은 여기 있다. 의존 정비 H1(설계 변경 이력 §15.314)이 picasso 시험 소스에서 이 출하 모듈로 옮겼다.
//
// **결선하지 않는다.** 포트 여섯을 들고 이 부품을 얹는 실제 호스트는 아직 없다(`docs/limits.md` §15.176) — 참조 실행기
// `ScenarioHost` 는 picasso 시험 소스에 남아 `:picasso:runApprovalHost` 로 돈다. 쓰는 것은 picasso 의 공개 API 뿐이다.
dependencies {
    // 공개 서명이 picasso 의 타입(`IncidentBundle`·`Entitlements`·`ApprovalAttempt` 등)을 드러낸다.
    api(project(":picasso"))
    // 선언 파일과 오류 본문을 protobuf JSON 으로 읽고 쓴다. picasso 가 `implementation` 으로 들어 전이되지 않는다.
    implementation(libs.protobuf.java.util)

    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    inputs.files(
        // `FileEntitlementsTest` 가 출하된 선언 파일(`handoff/narrator/entitlements.json`)을 읽는다.
        rootProject.file("handoff"),
        // `BundleWriterTest` 가 두 모듈의 소스를 훑어 «한 벌을 담는 절차가 하나뿐» 을 댄다. 선언 안 하면 사본을 더해도
        // 태스크가 UP-TO-DATE 로 건너뛴다(CLAUDE.md 2-4).
        project.file("src"),
        rootProject.file("picasso/src"),
    ).withPropertyName("hostKitInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

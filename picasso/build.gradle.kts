import org.gradle.api.tasks.PathSensitivity

// 미들웨어의 가운데 — 정준 모델과 공통 실행 구조.
// ADR 38 · docs/superpowers/specs/2026-09-09-middleware-core-design.md
//
// **기종을 모른다.** 게이트 7번(기종 분기 금지)의 대상이며, 여기에 `if robot == …`
// 가 생기면 게이트가 막는다. 벤더 코드에서 정준 분류로 옮기는 일은 어댑터의
// 것이고(ADR 33), 이 모듈은 계약(④)이 나르는 정준 어휘만 읽는다.
//
// 의존이 계약과 계약 소비자뿐인 것이 성격이다 — `registry`(개정판·원장)도
// `mimic`(로봇 더블)도 모른다. 시험만 하네스로 mimic 을 띄운다.
dependencies {
    api(project(":contracts"))
    implementation(project(":capability"))
    implementation(project(":client"))

    // 계약 메시지를 protobuf JSON 으로 적는다(`LedgerExport`). 기존 적재 표면이 이미 그 규약이라
    // 다른 규약을 쓰면 읽는 쪽이 갈린다. **전송이 아니라 인코딩이다** - 이 모듈은 여전히 파일도
    // 소켓도 모른다.
    implementation(libs.protobuf.java.util)

    testImplementation(project(":harness"))
    // 참조 실행기(`ScenarioHost`)와 내보내기 시험이 담는 쪽의 부품을 쓴다. 출하 의존이 아니다 — picasso 는 여전히 파일도 소켓도 모른다.
    testImplementation(project(":host-kit"))
    testImplementation(project(":mimic"))
    // 답하지 않는 하위를 시험 안에 세운다(`PortBreakerTest`). 미믹은 늘 답한다.
    testImplementation(libs.grpc.inprocess)
    testImplementation(kotlin("test"))
}

/**
 * **실행 계층을 세워 두고 승인 창구를 연다**(`docs/orchestration.md` §7.4).
 *
 *     ./gradlew :picasso:runApprovalHost
 *     ./gradlew :picasso:runApprovalHost --args="--port 8770 --seconds 1800"
 *
 * 시험 소스에서 돈다. `picasso` 는 라이브러리라 진입점을 안 들고(§6), v1 에서 실행 계층을 실제로
 * 구동하는 주체는 시나리오 구동기뿐이라 그것이 담는 쪽이다. 승인 호스트·선언 파일·번들 쓰기는 프로덕션 모듈
 * `host-kit` 의 부품이고(§15.314), 실행기는 시험 전용 설비 더블을 써서 시험 곁에 남는다.
 */
tasks.register<JavaExec>("runApprovalHost") {
    group = "application"
    description = "미들웨어를 세워 두고 루프백에 승인 호스트를 연다"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.picasso.middleware.host.ScenarioHost")
    // Windows 콘솔 코드페이지에서 한글 안내가 깨진다. 기동 안내가 유일한 산출물이다.
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.file("profile/fixtures"),
        rootProject.file("profile/schema"),
        // 인계 지점의 한 벌을 `HandoffFixtureTest` 가 읽는다. 선언 안 하면 그 파일이 바뀌어도
        // 태스크가 UP-TO-DATE 로 건너뛰고, 낡은 한 벌이 초록인 채로 나간다(CLAUDE.md 2-4).
        rootProject.file("handoff"),
        // 정답표가 가리키는 정지 코드를 코퍼스가 푸는지 `GroundTruthTest` 가 읽는다. 선언 안 하면
        // 문서에서 코드를 지워도 태스크가 UP-TO-DATE 로 건너뛰고 «코퍼스가 푼다» 가 초록으로 남는다.
        // 정답의 말이 어느 문서로 새는지를 `GroundTruthTest` 가 `docs` 전부에서 훑는다.
        // 좁게 선언하면 새 문서가 생겨도 태스크가 UP-TO-DATE 로 건너뛰어 누수가 안 보인다.
        rootProject.file("docs"),
        // 인계 안내문을 추적하지 않으므로 `HandoffFixtureTest` 가 «없음» 을 무시 목록으로 확인한다.
        // 선언 안 하면 목록에서 그 줄을 빼도 태스크가 UP-TO-DATE 로 건너뛴다.
        rootProject.file(".gitignore"),
        // 이 모듈의 소스를 `BundleWriterTest`(한 벌을 담는 절차가 한 자리뿐인지)와 `SeamPlacementTest`(떼어 낸
        // 선언이 제 파일에만 있는지)가 훑는다. 선언 안 하면 절차를 한 벌 더 적어도 태스크가 UP-TO-DATE 로
        // 건너뛸 수 있다 — 주석만 바뀐 파일은 컴파일 산출이 같아 클래스패스가 안 움직인다(CLAUDE.md 2-4).
        project.file("src"),
    ).withPropertyName("profileInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

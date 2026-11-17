package dev.picasso.contracts.wire

import dev.picasso.contracts.v1.MessageHeader

/**
 * 요청이 실어 온 계약 개정판을 이 계약과 대조한 결과(§5.5).
 *
 * > semver가 있으면 **major 불일치는 차단**, 그 외 불일치는 **경보**로 갈린다.
 *
 * 전송 계층의 예외를 여기서 던지지 않는다 — `contracts`가 gRPC 상태 코드를
 * 아는 순간 이 판정이 gRPC 전용이 되고, 같은 판정을 해야 하는 MQTT 소비자가
 * 쓸 수 없게 된다. 호출자가 자기 전송의 언어로 옮긴다.
 */
sealed interface ContractCompatibility {

    /** 다이제스트까지 같다. */
    data object Same : ContractCompatibility

    /**
     * 실어 오지 않았다. **차단하지 않는다** — §5.5가 "semver가 있으면"이라고
     * 조건을 달았다. 경보 대상이지만 발신할 스트림이 필요하므로 Chunk 6이다.
     */
    data object Unstated : ContractCompatibility

    /**
     * semver를 해석할 수 없다. **차단한다** — major를 알 수 없으면 호환
     * 여부를 판정할 수 없고, 모르는 것을 통과시키면 §6.2의 빌드 시점 보장이
     * 런타임에서 조용히 무너진다.
     */
    data class Unparseable(val theirs: String) : ContractCompatibility

    /** major가 다르다. **차단한다.** */
    data class MajorMismatch(val theirs: String, val ours: String) : ContractCompatibility

    /** major는 같고 그 밖이 다르다. **차단하지 않고 경보한다**(Chunk 6). */
    data class Divergent(val theirs: String, val ours: String) : ContractCompatibility

    /** 차단해야 하는가. */
    val blocking: Boolean
        get() = this is Unparseable || this is MajorMismatch
}

/**
 * gRPC 요청 헤더를 만들고 읽는다. §5.5의 [HeaderColumns.REQUEST]가 명세다.
 *
 * **`mimic`이 아니라 `contracts`에 있다.** 요청 헤더를 만드는 것은 소비자이고,
 * 발신자 모듈에 두면 `client`가 같은 표를 두 번째로 옮겨 적게 된다.
 */
object RequestHeaders {

    /**
     * 계약 신원은 인자가 아니다 — 호출자가 자기 마음대로 채우면 §6.2의
     * 개정판 대조가 무의미해진다. 시험이 어긋난 값을 만들 때만
     * [buildWithContract]를 쓴다.
     */
    /**
     * @param controllerEpoch 발신자의 제어자 세대(0.12.0, ADR 54). 기본 0 은 «싣지 않음» 이다 — 대조하는 수신자는 그 명령을 거절한다.
     */
    fun build(schemaId: String, robotId: String, clientId: String, controllerEpoch: Long = 0): MessageHeader =
        buildWithContract(schemaId, robotId, clientId, ContractIdentity.semver, ContractIdentity.digest, controllerEpoch)

    /** 시험 전용 — 계약 개정판을 일부러 어긋나게 만들 때. */
    fun buildWithContract(
        schemaId: String,
        robotId: String,
        clientId: String,
        semver: String,
        digest: String,
        controllerEpoch: Long = 0,
    ): MessageHeader = MessageHeader.newBuilder()
        .setSchemaId(schemaId)
        .setContractDigest(digest)
        .setContractSemver(semver)
        .setRobotId(robotId)
        .setClientId(clientId)
        .setControllerEpoch(controllerEpoch)
        .build()

    fun compatibility(header: MessageHeader): ContractCompatibility {
        val theirs = header.contractSemver
        if (theirs.isBlank()) return ContractCompatibility.Unstated

        val major = ContractIdentity.majorOf(theirs)
            ?: return ContractCompatibility.Unparseable(theirs)

        if (major != ContractIdentity.major) {
            return ContractCompatibility.MajorMismatch(theirs, ContractIdentity.semver)
        }
        // 다이제스트만 달라도 경보 대상이다 — 같은 semver로 다른 계약을
        // 빌드한 상황이고, 그것이 §15의 4번이 말하는 자리다.
        return if (theirs == ContractIdentity.semver && header.contractDigest == ContractIdentity.digest) {
            ContractCompatibility.Same
        } else {
            ContractCompatibility.Divergent(theirs, ContractIdentity.semver)
        }
    }
}

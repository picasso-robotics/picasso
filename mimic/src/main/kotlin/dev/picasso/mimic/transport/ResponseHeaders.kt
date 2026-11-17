package dev.picasso.mimic.transport

import com.google.protobuf.Descriptors
import dev.picasso.contracts.wire.ContractIdentity
import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.v1.ProfileRef
import dev.picasso.mimic.RobotInstance
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * gRPC 응답 헤더를 만든다. **§5.5의 표가 이 클래스의 명세다.**
 *
 * | 필드 | 응답 | 출처 |
 * |---|---|---|
 * | `schema_id` | ● | 응답 메시지의 full name |
 * | `contract_digest`·`contract_semver` | ● | [ContractIdentity] |
 * | `robot_id` | ● | 지정된 기체 |
 * | `capability_epoch` | ● | 기체의 세대 |
 * | `session_id` | ● | 기체의 세션 |
 * | `update_index` | `WatchTask`만 | 태스크 갱신 로그의 색인 |
 * | `profile_ref` | ● | 프로파일의 `(vendor/model, revision)` |
 * | `event_id`·`occurred_at`·`state_as_of` | ● | 기체 카운터 + 시계 |
 * | `sequence` | **✕** | MQTT `state`·`event` 전용 |
 * | `client_id` | **✕** | gRPC 요청 전용 |
 *
 * **요청 헤더를 복사해 돌려주지 않는다.** 그것이 가장 자연스러운 구현이고
 * 그러면 `client_id`가 응답에 새어 나가고 `capability_epoch`·`session_id`가
 * 비어 나간다 — 소비자의 캐시 판정(§5.5)과 세션 판정(§4.8)이 둘 다 죽는다.
 *
 * **기체마다 하나다.** `event_id` 카운터가 기체 단위여야 소비자의 멱등 키가
 * 다른 기체와 섞이지 않는다.
 */
class ResponseHeaders(
    private val instance: RobotInstance,
    /** 이 기체의 제어자 세대 바닥값(ADR 54) — 응답 `controller_epoch`. 0 은 «대조 안 함». 요청값을 되울리지 않는다. */
    private val floor: () -> Long = { 0L },
) {

    /**
     * §5.5는 ULID라 하지만 [RobotInstance.sessionId]와 같은 근거로 카운터를
     * 쓴다 — 난수를 쓰면 §12.1의 결정성이 깨진다. 요건은 "소비자 측 멱등
     * 처리 키"이고 세션 안에서 유일하면 족하다. 한계는 §15에 있다.
     */
    private val eventCounter = AtomicLong()

    /**
     * @param stateAsOf 되짚어 보내는 갱신은 **그때의 시각**을 싣는다. 지금
     *   시각을 실으면 30초 전 전이가 방금 일어난 것으로 보이고 소비자의
     *   신선도 판정(§5.5)이 거짓말을 한다.
     */
    fun forResponse(
        response: Descriptors.Descriptor,
        updateIndex: Long? = null,
        stateAsOf: String? = null,
    ): MessageHeader {
        val now = DateTimeFormatter.ISO_INSTANT.format(instance.clock.now())

        return MessageHeader.newBuilder()
            .setSchemaId(response.fullName)
            .setContractDigest(ContractIdentity.digest)
            .setContractSemver(ContractIdentity.semver)
            .setRobotId(instance.robotId)
            .setCapabilityEpoch(instance.capabilityEpoch)
            .setSessionId(instance.sessionId)
            .setProfileRef(
                ProfileRef.newBuilder()
                    .setProfileId("${instance.document.vendor}/${instance.document.model}")
                    .setRevision(instance.document.revision),
            )
            .setEventId("${instance.sessionId}-%08d".format(eventCounter.incrementAndGet()))
            .setOccurredAt(now)
            .setStateAsOf(stateAsOf ?: now)
            .also { builder -> updateIndex?.let(builder::setUpdateIndex) }
            .setControllerEpoch(floor())
            .build()
    }
}

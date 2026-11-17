package dev.picasso.contracts.wire

import dev.picasso.contracts.v1.MessageHeader

/**
 * §5.5의 헤더 표. **세 방향마다 채우는 필드가 다르다.**
 *
 * 표가 `contracts`에 있는 이유는 그것이 계약의 규칙이기 때문이다. 발신자
 * 쪽(`mimic`)에만 두면 `client`가 요청 열을 두 번째로 옮겨 적게 되고, 두
 * 벌로 쓰는 순간 이 프로젝트가 막으려는 드리프트를 우리가 낸다.
 *
 * 비어 있는 필드는 **"그 방향에서는 쓰지 않는다"**를 뜻한다. 수신자가 그것을
 * 거절 사유로 삼지는 않는다 — 표는 무엇이 의미를 갖는지의 명세이지 검증
 * 규칙이 아니다. 다만 **자기가 만들 때는 표대로 채운다.**
 */
object HeaderColumns {

    /** MQTT `state`·`event`·`connection` 발행. Chunk 6이 쓴다. */
    val PUBLISH: Set<String> = setOf(
        "schema_id", "contract_digest", "contract_semver", "robot_id",
        "capability_epoch", "sequence", "session_id", "profile_ref",
        "event_id", "occurred_at", "state_as_of",
    )

    /** gRPC 요청. `client_id`가 여기에만 있다. `controller_epoch` 는 발신자의 세대다(0.12.0). */
    val REQUEST: Set<String> = setOf(
        "schema_id", "contract_digest", "contract_semver", "robot_id", "client_id", "controller_epoch",
    )

    /** gRPC 응답. `sequence`와 `client_id`가 빠진다. */
    val RESPONSE: Set<String> = setOf(
        "schema_id", "contract_digest", "contract_semver", "robot_id",
        "capability_epoch", "session_id", "profile_ref",
        "event_id", "occurred_at", "state_as_of", "controller_epoch",
    )

    /**
     * **0 이 값인 칸**(0.12.0). 그 방향에서 쓰는 칸이지만 0 이 뜻을 가져 비어 보일 수 있다 — 요청의 `controller_epoch` 0 은 «세대를
     * 싣지 않음», 응답의 0 은 «대조 안 함» 이다. 표대로 채웠는지 보는 쪽은 이 칸의 비어 있음을 누락으로 읽지 않는다.
     */
    val ZERO_IS_VALUE: Set<String> = setOf("controller_epoch")

    /** `WatchTask` 스트림 응답. 응답 열에 `update_index`가 더해진다. */
    val WATCH_RESPONSE: Set<String> = RESPONSE + "update_index"

    /** 표 전체가 [MessageHeader]의 필드를 덮는지 확인하는 데 쓴다. */
    val ALL: Set<String> = PUBLISH + REQUEST + WATCH_RESPONSE

    /** 이름으로 조회한 필드가 채워졌는가. */
    fun isSet(header: MessageHeader, field: String): Boolean {
        val descriptor = requireNotNull(MessageHeader.getDescriptor().findFieldByName(field)) {
            "MessageHeader에 '$field' 필드가 없다"
        }
        // proto3의 암묵 존재 스칼라는 hasField가 던진다. 메시지 필드만 존재를 갖는다.
        return if (descriptor.hasPresence()) {
            header.hasField(descriptor)
        } else {
            header.getField(descriptor) != descriptor.defaultValue
        }
    }
}

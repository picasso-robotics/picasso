package dev.picasso.mimic.transport

import dev.picasso.contracts.v1.MessageHeader
import dev.picasso.contracts.wire.ControllerFence
import dev.picasso.contracts.wire.RequestHeaders
import dev.picasso.mimic.RobotInstance
import io.grpc.Status

/**
 * 한 프로세스가 호스팅하는 기체들. **기체는 요청 헤더의 `robot_id`로 지정한다**(§10.2).
 *
 * 포트는 하나다. "엔드포인트만 바꿔 실물과 교체"는 호스트·포트만 바뀌고
 * `robot_id`는 그대로라는 뜻이며, PoC의 편의이지 아키텍처 주장이 아니다(ADR 21).
 */
class RobotRegistry(
    instances: List<RobotInstance>,
    /**
     * §10.3의 폴링이 당기는 곳. **없으면 파일 모드다**(§3.2의 "없을 때") —
     * 레지스트리가 안 떠 있어도 에뮬레이터는 돈다.
     */
    val registrySource: dev.picasso.mimic.RegistrySource =
        dev.picasso.mimic.RegistrySource.NONE,
    /**
     * 제어자 세대 대조(ADR 54). **기본은 대조하지 않는다** — 오늘 거동. 대조할 때 미믹은 보통 메모리 저장을 쓴다(시험·일회성 전용) — 미믹이
     * 다시 뜨면 태스크 장부까지 비는 새 기체 세션이라 보존할 대조 대상이 없다. 인수 확인은 받은 저장의 실제 내구성대로 답한다(메모리면
     * `VOLATILE`). 저장이 던지면 `FAILED_PRECONDITION` 이다 — `adapter-host` 와 같다.
     */
    val fence: ControllerFence = ControllerFence(),
) {

    /** 바닥값을 올리는 [block] 을 돈다. 저장소가 던지면 `FAILED_PRECONDITION` 으로 옮긴다 — 수신자 둘이 같은 상태로 답한다. */
    fun <T> fenced(block: () -> T): T = try {
        block()
    } catch (e: io.grpc.StatusRuntimeException) {
        throw e
    } catch (e: Exception) {
        throw Status.FAILED_PRECONDITION.withDescription("제어자 세대 바닥값을 저장하지 못했다: ${e.message}").withCause(e).asRuntimeException()
    }

    /** 기체 하나와 그 기체 전용 헤더 발급기. */
    class Hosted(val instance: RobotInstance, floor: () -> Long = { 0L }) {
        /**
         * **기체마다 따로다.** `event_id` 카운터가 기체 단위여야 소비자의
         * 멱등 키가 다른 기체와 섞이지 않는다.
         */
        val headers = ResponseHeaders(instance, floor)
    }

    private val byId: Map<String, Hosted> = buildMap {
        instances.forEach { instance ->
            require(instance.robotId.isNotBlank()) { "robot_id가 비어 있는 기체는 호스팅할 수 없다" }
            // 조용히 덮으면 두 프로파일 중 하나가 사라진 채 기동한다.
            require(put(instance.robotId, Hosted(instance) { fence.floor(instance.robotId) }) == null) {
                "같은 robot_id를 두 번 호스팅할 수 없다: ${instance.robotId}"
            }
        }
    }

    val robotIds: Set<String> get() = byId.keys

    val hosted: Collection<Hosted> get() = byId.values

    /** 헤더 없이 기체를 찾는다. 제어 채널이 쓴다(§10.5는 헤더를 안 싣는다). */
    fun byId(robotId: String): Hosted? = byId[robotId]

    /**
     * 기체들이 보는 서로 다른 시계. **같은 시계를 공유하면 하나다** —
     * 참조 동일성으로 거른다. 두 번 전진시키면 소요시간 판정이 어긋난다.
     */
    val clocks: List<dev.picasso.mimic.engine.Clock>
        get() = byId.values.map { it.instance.clock }.distinct()

    /**
     * 요청 헤더가 지정한 기체를 찾는다.
     *
     * **빈 `robot_id`를 첫 기체로 접지 않는다.** 접으면 기체가 하나인 시험은
     * 전부 통과하고 둘이 되는 순간 조용히 엉뚱한 기체로 간다.
     *
     * 라우팅 실패는 능력 판정이 아니므로 gRPC 상태다 — 요청을 해석하지
     * 못한 것이지 해석하고 거절한 것이 아니다.
     */
    fun require(header: MessageHeader): Hosted {
        // 계약 개정판 차단이 라우팅보다 먼저다(§5.5) — 해석할 수 없는
        // 요청의 robot_id를 믿고 기체를 고르는 것이 이미 틀렸다.
        val compatibility = RequestHeaders.compatibility(header)
        if (compatibility.blocking) {
            throw Status.FAILED_PRECONDITION
                .withDescription("계약 리비전이 호환되지 않는다: $compatibility")
                .asRuntimeException()
        }

        val robotId = header.robotId
        if (robotId.isBlank()) {
            throw Status.INVALID_ARGUMENT
                .withDescription("요청 헤더에 robot_id가 없다 — §5.5는 gRPC 요청에 싣는다")
                .asRuntimeException()
        }

        return byId[robotId] ?: throw Status.NOT_FOUND
            .withDescription("호스팅하지 않는 기체다: $robotId (있는 것: ${byId.keys.sorted()})")
            .asRuntimeException()
    }
}

package dev.picasso.contracts.wire

import dev.picasso.contracts.v1.FenceStatus
import dev.picasso.contracts.v1.Rejection
import dev.picasso.contracts.v1.RejectionCode
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * **수신 측 제어자 세대 대조**(ADR 54, ADR 51 결정 5) — 기체(`robot_id`)마다 바닥값 하나를 들고, 명령 다섯(`StartTask`·`RetryTask`·
 * `PauseTask`·`ResumeTask`·`CancelTask`)의 요청 헤더 `controller_epoch` 를 그 바닥값과 맞댄다. 수신자 둘(미믹·`adapter-host`)이 같은
 * 판정을 써야 하므로 [RequestHeaders] 와 같은 까닭으로 `contracts` 에 한 벌 둔다.
 *
 * ## 모드 둘
 *
 * - [FenceMode.UNFENCED](기본) — 오늘 거동. 비교·상승·저장이 없고 응답 헤더의 바닥값은 0 이다. 0 은 «대조 안 함» 이고, 이 칸을 모르는
 *   옛 수신자의 0 과 같은 뜻이다.
 * - [FenceMode.FENCED] — 세대 0 명령을 거부하고, `0 < e < F` 를 거부하고, `e = F` 를 통과시키고, `e > F` 는 바닥값을 **저장한 뒤** 올리고
 *   처리한다. 저장소가 던지면 그 요청은 실패다(차단 쪽).
 *
 * ## 읽기
 *
 * 명령 다섯과 `GetSnapshot` 말고는 바닥값을 올리지도 거부하지도 않는다. 낮은 세대의 단건 읽기와 새 구독은 통과하고 응답 헤더에
 * 바닥값이 실린다. `GetSnapshot` 은 인수 확인이다([confirm]).
 *
 * ## 자격
 *
 * 무엇이 바닥값을 올릴 수 있는가는 [EpochAdmission] 이 정한다. 이 판정은 라우팅·계약 대조·식별 정보 대조를 지난 요청에만 불린다.
 * 인증은 없다 — 숫자 하나로는 발신자를 확인하지 못한다(한계 대장 «§15.330 · 세대 오염»).
 *
 * ## 이미 통과한 명령
 *
 * 입구를 지난 명령은 [Admission.Admitted] 를 닫을 때까지 «통과한 것» 으로 센다. 인수 확인([confirm])은 바닥값을 올린 뒤 그보다 낮은
 * 세대로 통과한 명령이 다 닫히기를 기다린다 — 그 뒤로 낮은 세대 명령이 벤더 송신을 새로 시작하지 못하고, 앞서 시작한 송신은 장부에
 * 반영됐다는 것이 확인 응답의 뜻이다. 입구 판정과 통과 등록은 기체마다 한 잠금 아래에서 한다 — 그 사이에 바닥값이 오르면 등록되지 않은
 * 명령을 놓친다. 수신자는 벤더 호출 직전에 [current] 로 한 번 더 맞댄다(방어).
 */
class ControllerFence(
    val mode: FenceMode = FenceMode.UNFENCED,
    private val store: FenceStore = FenceStore.memory(),
    private val admission: EpochAdmission = EpochAdmission.VERIFIED_COMMANDS_AND_SNAPSHOT,
) {

    /** 기체마다의 바닥값과 통과해 아직 안 닫힌 명령의 세대. 기체마다 잠금 하나가 둘을 지킨다. */
    private class Robot(@Volatile var floor: Long) {
        val passed = mutableListOf<Long>()
        val lock = ReentrantLock()
        val closed = lock.newCondition()
    }

    private val robots = ConcurrentHashMap<String, Robot>()

    init {
        // **손상이면 여기서 던진다** — 바닥값을 모르는 채 받으면 옛 세대가 통과한다. 수신자가 뜨지 않는 것이 차단이다.
        if (mode == FenceMode.FENCED) store.load().forEach { (robot, floor) -> robots[robot] = Robot(floor) }
    }

    /** 저장의 내구성. 인수 확인의 답([FenceConfirmation])이 이것으로 갈린다. */
    val durability: FenceDurability get() = store.durability

    private fun robot(robotId: String): Robot = robots.computeIfAbsent(robotId) { Robot(0) }

    /** 지금 앞서 통과한 명령을 기다리는 인수 확인의 수. 시험이 «기다리는 중» 을 잠자기 없이 확정하는 문이다. */
    private val waiting = AtomicInteger()
    val confirmsWaiting: Int get() = waiting.get()

    /**
     * 이 기체의 바닥값. `UNFENCED` 면 0 — «대조 안 함». 응답 헤더 `controller_epoch` 에 이것을 싣는다.
     *
     * `FENCED` 인데 아직 한 번도 안 오른 기체도 0 이다 — 응답 머리말만으로는 «대조 안 함» 과 가를 수 없다. 새 소유자의 첫 `GetSnapshot` 이
     * 바닥값을 올리고 `fence_status` 로 대조 여부를 말하므로, 판정은 그 둘을 함께 본다.
     */
    fun floor(robotId: String): Long = if (mode == FenceMode.UNFENCED) 0 else robots[robotId]?.floor ?: 0

    /**
     * 명령 하나를 입구에서 맞댄다. 통과면 [Admission.Admitted] 를 돌려주고, 부른 쪽은 그 명령의 처리(벤더 호출과 장부 반영)가 끝나면
     * 닫는다. 거부면 [Admission.Stale] — 수신자는 `REJECTION_CODE_STALE_CONTROLLER` 로 답하고 벤더도 장부도 건드리지 않는다.
     *
     * @throws Exception 바닥값을 올리다 저장소가 던지면 그대로 낸다 — 그 요청은 실패다.
     */
    fun command(robotId: String, epoch: Long, call: FencedCall): Admission {
        require(call != FencedCall.SNAPSHOT) { "GetSnapshot 은 confirm 으로 맞댄다" }
        if (mode == FenceMode.UNFENCED) return Admission.Admitted(0) {}
        val r = robot(robotId)
        r.lock.withLock {
            // 0 과 범위 밖(uint64 의 위 절반 — Long 으로 음수)은 바닥값과 견주지 않고 거부한다.
            if (epoch <= 0L || epoch < r.floor) return Admission.Stale(epoch, r.floor)
            if (epoch > r.floor) {
                if (!admission.mayRaise(robotId, epoch, call)) return Admission.Stale(epoch, r.floor)
                raise(robotId, r, epoch)
            }
            r.passed += epoch
            return Admission.Admitted(r.floor) {
                r.lock.withLock {
                    r.passed.remove(epoch)
                    r.closed.signalAll()
                }
            }
        }
    }

    /**
     * 벤더 호출 직전의 재대조 — 이 세대가 아직 그 기체의 바닥값인가. `UNFENCED` 면 언제나 참이다. 입구에서 통과한 명령이 그 사이 오른
     * 바닥값 아래로 내려갔으면 거짓이고, 수신자는 벤더를 부르지 않고 `STALE_CONTROLLER` 로 답한다.
     */
    fun current(robotId: String, epoch: Long): Boolean =
        mode == FenceMode.UNFENCED || (epoch > 0 && epoch >= (robots[robotId]?.floor ?: 0))

    /**
     * **인수 확인** — `GetSnapshot` 의 세대가 바닥값 이상이면 바닥값을 (저장한 뒤) 그 세대로 올리고, 그보다 낮은 세대로 통과한 명령이
     * 다 닫히기를 [wait] 만큼 기다린다. 인수 확인이 아니면(`UNFENCED`, 세대 0, 바닥값보다 낮음, 자격 없음) 널이다 — 그 읽기는 그냥 읽기다.
     *
     * **부르는 쪽은 수신자의 기록 잠금을 쥐지 않고 부른다.** 통과한 명령이 장부에 적으려면 그 잠금이 있어야 한다.
     *
     * @throws Exception 저장소가 던지면 그대로 낸다 — 인수 확인은 실패다.
     */
    fun confirm(robotId: String, epoch: Long, wait: Duration): FenceConfirmation? {
        if (mode == FenceMode.UNFENCED || epoch <= 0L) return null
        val r = robot(robotId)
        r.lock.withLock {
            if (epoch < r.floor) return null
            if (epoch > r.floor) {
                if (!admission.mayRaise(robotId, epoch, FencedCall.SNAPSHOT)) return null
                raise(robotId, r, epoch)
            }
            var left = wait.toNanos()
            waiting.incrementAndGet()
            try {
                while (r.passed.any { it < epoch }) {
                    if (left <= 0) break
                    left = r.closed.awaitNanos(left)
                }
            } finally {
                waiting.decrementAndGet()
            }
            // **기다린 뒤 다시 본다** — 그 사이 더 높은 세대가 바닥값을 올렸으면 이 세대의 확인이 아니다.
            if (r.floor != epoch) return null
            return FenceConfirmation(drained = r.passed.none { it < epoch })
        }
    }

    /** 저장한 **뒤에** 올린다(적용 전 기록). 저장이 던지면 메모리 바닥값은 그대로다. */
    private fun raise(robotId: String, r: Robot, epoch: Long) {
        store.save(robotId, epoch)
        r.floor = epoch
    }

    /** 입구 판정. */
    sealed interface Admission {
        /** 통과. [floor] 는 통과한 뒤의 바닥값. 처리가 끝나면 닫는다 — 닫지 않으면 인수 확인이 기한까지 기다린다. */
        class Admitted(val floor: Long, private val release: () -> Unit) : Admission, AutoCloseable {
            private val closed = AtomicBoolean(false)

            /** 한 번만 닫힌다 — 겹쳐 닫혀도 같은 세대의 다른 명령 표지를 지우지 않는다. */
            override fun close() {
                if (closed.compareAndSet(false, true)) release()
            }
        }

        /** 거부. 까닭([reason])은 요청 세대와 바닥값으로 갈린다 — 거절 응답의 두 칸만으로 받는 쪽도 같은 판정을 한다([StaleReason.of]). */
        data class Stale(val requested: Long, val floor: Long) : Admission {
            val missing: Boolean get() = requested == 0L
            val reason: StaleReason get() = StaleReason.of(requested, floor)
            val detail: String
                get() = when (reason) {
                    StaleReason.EPOCH_MISSING -> "제어자 세대를 싣지 않았다 — 이 기체는 세대 대조를 한다(바닥값=$floor)"
                    StaleReason.OUT_OF_RANGE -> "제어자 세대 ${java.lang.Long.toUnsignedString(requested)} 가 받는 범위(1..${Long.MAX_VALUE}) 밖이다"
                    StaleReason.BELOW_FLOOR -> "제어자 세대 $requested 이(가) 이 기체의 바닥값 $floor 보다 낮다 — 다른 제어자가 넘겨받았다"
                    StaleReason.NOT_ADMITTED -> "제어자 세대 $requested 로 바닥값 $floor 을(를) 올릴 자격이 없다"
                }

            /** 계약의 거절 — 수신자 둘이 같은 모양으로 낸다. */
            fun rejection(): Rejection = Rejection.newBuilder()
                .setCode(RejectionCode.REJECTION_CODE_STALE_CONTROLLER)
                .setDetail(detail)
                .setRequestedControllerEpoch(requested)
                .setControllerEpochFloor(floor)
                .build()
        }
    }
}

/**
 * `STALE_CONTROLLER` 거절의 까닭. 거절 응답의 요청 세대·바닥값 두 칸으로 갈린다 — 계약에 칸을 더하지 않고 받는 쪽이 같은 판정을 한다.
 * **밀려남은 [BELOW_FLOOR] 하나다.** 나머지 셋은 그 명령이 받아들여지지 않았을 뿐 다른 제어자가 넘겨받았다는 뜻이 아니다.
 */
enum class StaleReason {
    /** 요청 세대 0 — 세대를 싣지 않았다(설정 오류). */
    EPOCH_MISSING,

    /** 요청 세대가 받는 범위(1..2^63-1) 밖이다 — `uint64` 의 위 절반. */
    OUT_OF_RANGE,

    /** 요청 세대가 바닥값보다 낮다 — 더 높은 세대가 넘겨받았다. */
    BELOW_FLOOR,

    /** 요청 세대가 바닥값 이상인데 올릴 자격이 없다([EpochAdmission]). */
    NOT_ADMITTED,
    ;

    companion object {
        fun of(requested: Long, floor: Long): StaleReason = when {
            requested == 0L -> EPOCH_MISSING
            requested < 0L -> OUT_OF_RANGE
            requested < floor -> BELOW_FLOOR
            else -> NOT_ADMITTED
        }
    }
}

/** 수신자의 대조 모드. */
enum class FenceMode {
    /** 오늘 거동 — 비교·상승·저장 없음. 응답 바닥값 0. */
    UNFENCED,

    /** 세대 0 명령 거부, 낮은 세대 거부, 높은 세대는 저장 뒤 상승. 저장 필수. */
    FENCED,
}

/** 대조하는 호출. 명령 다섯과 인수 확인(`GetSnapshot`). 그 밖의 읽기는 여기 없다 — 대조하지 않는다. */
enum class FencedCall { START, RETRY, PAUSE, RESUME, CANCEL, SNAPSHOT }

/**
 * **무엇이 바닥값을 올릴 수 있는가.** 라우팅·계약 대조·식별 정보 대조를 지난 요청에만 불린다. 거짓이면 올리지 않는다 — 명령은 거부되고
 * `GetSnapshot` 은 인수 확인이 아닌 읽기가 된다.
 */
fun interface EpochAdmission {
    fun mayRaise(robotId: String, epoch: Long, call: FencedCall): Boolean

    companion object {
        /** 검증을 지난 명령 다섯과 `GetSnapshot` 만 올린다 — [FencedCall] 이 곧 그 목록이라 언제나 참이다. 저장소가 주는 구현은 이것 하나다. */
        val VERIFIED_COMMANDS_AND_SNAPSHOT = EpochAdmission { _, _, _ -> true }
    }
}

/** 인수 확인의 답. [drained] 가 거짓이면 기한 안에 앞서 통과한 명령이 다 닫히지 않았다. */
data class FenceConfirmation(val drained: Boolean) {
    /**
     * 계약의 답. 메모리 저장이면 정리 여부와 무관하게 `VOLATILE` 이다(자동 재개의 자격이 없으므로 정리 안 됨을 따로 가르지 않는다).
     * [inDoubt] 는 그 기체에 나갔는지 모르는 접수가 남았는가 — 장부에 없어 스냅샷에 안 보이므로 확인이 아니다.
     */
    fun status(durability: FenceDurability, inDoubt: Boolean): FenceStatus = when {
        durability == FenceDurability.VOLATILE -> FenceStatus.FENCE_STATUS_VOLATILE
        !drained || inDoubt -> FenceStatus.FENCE_STATUS_DURABLE_PENDING
        else -> FenceStatus.FENCE_STATUS_DURABLE_CONFIRMED
    }
}

/** 바닥값 저장의 내구성. */
enum class FenceDurability {
    /** 프로세스 메모리 — 다시 뜨면 0 부터다. 시험·일회성 배치 전용. */
    VOLATILE,

    /** 프로세스 재시작을 넘는다. */
    DURABLE,
}

/**
 * 바닥값 저장소. 수신자 배치가 고른다 — `adapter-host` 는 파일 구현을, 미믹은 메모리 구현을 쓴다.
 *
 * [save] 는 **돌아온 뒤에야** 그 값이 남았다는 뜻이다. 대조는 저장이 돌아온 뒤에 메모리 바닥값을 올린다(적용 전 기록). 던지면 그 요청은
 * 실패다. [load] 가 던지면(손상) 대조가 서지 않는다.
 */
interface FenceStore {
    val durability: FenceDurability

    /** 기체마다 저장된 바닥값. */
    fun load(): Map<String, Long>

    fun save(robotId: String, floor: Long)

    companion object {
        /** 메모리 구현 — **시험·일회성 배치 전용이다.** 다시 뜨면 바닥값을 잃는다. */
        fun memory(): FenceStore = object : FenceStore {
            private val floors = ConcurrentHashMap<String, Long>()
            override val durability = FenceDurability.VOLATILE
            override fun load(): Map<String, Long> = floors.toMap()
            override fun save(robotId: String, floor: Long) {
                floors[robotId] = floor
            }
        }
    }
}

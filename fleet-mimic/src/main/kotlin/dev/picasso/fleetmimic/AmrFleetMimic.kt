package dev.picasso.fleetmimic

import dev.picasso.middleware.AmrFleetPort
import dev.picasso.middleware.TransportHandle
import dev.picasso.middleware.TransportOrder
import dev.picasso.middleware.TransportState
import dev.picasso.middleware.TransportStatus
import java.time.Duration
import java.time.Instant

/**
 * AMR 플릿 미믹 — 운반 전체를 D 수준으로 위임받는 하류의 인프로세스 더블(보고서 4.1·5장, 설계 변경 이력 §15.326).
 *
 * **프로젝트용 계약([AmrFleetPort])의 구현이지 특정 벤더 API 의 재현이 아니다.**
 * 벤더 AMR API 나 VDA 5050 은 여기 없다(`scenarios.md` §1 규칙 1). 플릿 proto 도 만들지 않는다(ADR 9).
 *
 * 세계 하나를 든다 — 어느 자리에 어느 용기가 있는가, 어느 목적지가 막혀 있는가. 채우는 것은 [place]·[clear]·[block]·[unblock]
 * 이다. 보는 것은 [snapshot](세계 전체를 자물쇠 한 번에 뜬 사본)과 [observe](자리 하나의 용기와 놓인 시각을 한 번에)가 권하는 읽기이고,
 * [containerAt]·[placedAtOf]·[placedAt]·[blocked]·[dispatches]·[orderOf] 도 있다. 내부 가변 맵은 밖에 내주지 않는다.
 * [containersAt] 은 옛 표기 호환용 창이다 — 그 창 위의 `toMap()` 처럼 여러 번 읽는 연산은 원자 사본이 아니다([World]).
 *
 * 운반은 한 걸음마다 한 단계 나아간다: 접수 → 인수 → 이동 → (목적지가 막혀 있으면 인계 대기) → 인계. 인계는 세계를 바꾼다 —
 * 용기가 목적지에 놓이고 AMR 은 빈다. 그것이 이 계약이 말하는 [TransportState.DELIVERED] 의 뜻이다.
 *
 * **걸음은 둘 중 하나로 간다.**
 * - 수동(기본, [stepEvery] 가 널): [tick] 을 부를 때만 간다. picasso 시험의 결정성이 이것에 기댄다.
 * - 시계 구동([stepEvery] 가 있음): 공개 호출마다 [now] 를 읽어, 마지막 걸음에서 [stepEvery] 가 지난 만큼 걸음을 따라잡는다. 스레드를
 *   띄우지 않는다 — 시계가 가짜면 여전히 결정적이고, 아무도 묻지 않으면 세계도 멈춰 있다. 따라잡는 걸음이 놓는 용기의 놓인 시각은
 *   그 걸음의 시각이다(묻는 순간이 아니다). 더 바뀔 것이 없으면 따라잡기를 거기서 그친다. [tick] 도 함께 쓸 수 있다.
 *
 * **스레드 안전하다.** 잠금 밖에서 보내고 읽는 데코레이터(`IssuingFleetPort`·`PrefetchingFleetPort`)가 다른 스레드에서 부르므로 모든
 * 공개 호출이 한 자물쇠를 지난다. 바깥 코드는 주입된 시계 [now] 하나뿐이고, **그것은 자물쇠를 잡기 전에 부른다** — 읽은 값을 안에 넘겨
 * 걸음과 놓인 시각에 쓴다. 그래서 시계가 다른 잠금을 잡거나 이 미믹을 다시 불러도 교착하지 않는다. 다만 시계 구동에서 [now] 를 읽는
 * 호출을 시계 안에서 부르면 시계를 다시 부르게 되므로, 시계 안에서는 [now] 를 안 읽는 [blocked]·[dispatches]·[orderOf] 만 부른다
 * (수동 진행이면 관측은 [now] 를 읽지 않는다).
 *
 * **모르는 핸들에 던지지 않는다.** [status] 는 [TransportState.FAILED] 와 «모르는 운반» 사유를, [cancel] 은 `false` 를 준다.
 *
 * 같은 참조로 다시 맡기면 같은 운반이다 — 클라이언트 참조 기반 멱등(보고서 13.2). [find] 는 찾기만 하고 새로 맡지 않으며, 참조로 찾아
 * 주므로 [executionLookup] 은 계약의 기본([dev.picasso.middleware.ExecutionLookup.CLIENT_REFERENCE])이다.
 *
 * @param now 놓인 시각과 시계 구동의 원천.
 * @param stepEvery 시계 구동의 걸음 간격. 널이면 수동이다. 0 이하는 받지 않는다.
 */
/** 자리 하나의 관측 — 그 자리의 용기와 놓인 시각(모르면 널). [AmrFleetMimic.observe]·[AmrFleetMimic.snapshot] 이 준다. */
data class Placed(val containerId: String, val placedAt: Instant?)

class AmrFleetMimic(
    private val now: () -> Instant = { Instant.now() },
    private val stepEvery: Duration? = null,
) : AmrFleetPort {

    init {
        require(stepEvery == null || (!stepEvery.isNegative && !stepEvery.isZero)) { "걸음 간격은 양수여야 한다: $stepEvery" }
    }

    private val lock = Any()

    /** 세계 — 자리 → 용기. */
    private val world = mutableMapOf<String, String>()

    /** 자리 → 그 용기가 놓인 시각. 인계 설비가 읽는 `t_p` 의 원천. */
    private val placed = mutableMapOf<String, Instant>()

    /** 이전 용기가 남아 있어 인계할 수 없는 목적지들. */
    private val closed = mutableSetOf<String>()

    private class Transport(val order: TransportOrder) {
        var state = TransportState.ACCEPTED
        var observed: String? = null
        var holding = false
        var detail: String? = null
    }

    private val transports = linkedMapOf<String, Transport>()
    private val byReference = mutableMapOf<String, String>()
    private var seq = 0
    private var dispatchCount = 0

    /** 시계 구동에서 마지막으로 셈한 걸음의 시각. 수동이면 쓰지 않는다. */
    private var lastStep: Instant? = stepEvery?.let { now() }

    /**
     * 걸음이 쓴 가장 늦은 시각. 시계는 자물쇠 밖에서 읽으므로, 먼저 읽고 늦게 들어온 호출의 시각이 이미 걸은 시각보다 이를 수 있다 —
     * 그때 걸음은 이 시각을 쓴다. 놓인 시각이 거꾸로 가지 않는다.
     */
    private var latestStepAt: Instant? = null

    /**
     * 자물쇠 안에서 [block] 을 돈다. 시계는 **자물쇠 밖에서** 먼저 읽는다 — 시계 구동이거나 [always] 일 때만. 읽은 값으로 지난 걸음을
     * 따라잡고 그 값을 [block] 에 넘긴다(수동이고 [always] 가 아니면 널).
     */
    private inline fun <T> guarded(always: Boolean = false, block: (Instant?) -> T): T {
        val at = if (always || stepEvery != null) now() else null
        return synchronized(lock) {
            if (at != null) catchUp(at)
            block(at)
        }
    }

    // ── 세계 채우기 ────────────────────────────────────────────────────────────────────────────────────

    /**
     * [location] 에 [containerId] 를 둔다(있던 것은 바뀐다). [at] 이 널이면 놓인 시각을 모르는 것으로 둔다 — 그 자리의 이전 시각도 지운다.
     */
    fun place(location: String, containerId: String, at: Instant? = null) {
        guarded {
            world[location] = containerId
            if (at == null) placed.remove(location) else placed[location] = at
        }
    }

    /** [location] 을 비운다. 놓인 시각도 지운다. */
    fun clear(location: String) {
        guarded {
            world.remove(location)
            placed.remove(location)
        }
    }

    /** [location] 에 인계할 수 없게 막는다 — 그리로 가는 운반은 [TransportState.WAITING_HANDOVER] 에 선다. */
    fun block(location: String) {
        guarded {
            closed += location
        }
    }

    /** [block] 을 푼다. */
    fun unblock(location: String) {
        guarded {
            closed -= location
        }
    }

    // ── 진행 ──────────────────────────────────────────────────────────────────────────────────────────

    /** 세계의 걸음 한 칸 — 모든 운반이 한 단계 나아간다. 시계 구동이어도 부를 수 있다(먼저 따라잡는다). */
    fun tick() {
        guarded(always = true) { at -> step(at!!) }
    }

    // ── 관측 ──────────────────────────────────────────────────────────────────────────────────────────

    /** [location] 에 있는 용기. 없으면 널. */
    fun containerAt(location: String): String? = guarded { world[location] }

    /** [location] 의 용기가 놓인 시각. 모르거나 비었으면 널. */
    fun placedAtOf(location: String): Instant? = guarded { placed[location] }

    /** [location] 의 용기와 놓인 시각을 **한 번에** 읽는다. 비었으면 널. 둘을 따로 읽으면 그 사이에 걸음이 끼어 짝이 어긋날 수 있다. */
    fun observe(location: String): Placed? = guarded { world[location]?.let { Placed(it, placed[location]) } }

    /** 세계 전체의 사본 — 자리 → (용기, 놓인 시각). 자물쇠 한 번에 뜬다. 읽은 뒤의 변화는 안 비친다. */
    fun snapshot(): Map<String, Placed> = guarded { world.mapValues { (location, id) -> Placed(id, placed[location]) } }

    /**
     * 세계 — 자리 → 용기. **옛 표기 호환용 창**이며 내부 맵 자체가 아니다: 호출 하나마다 자물쇠 안에서 그 순간을 읽는다. `toMap()`·크기와
     * 순회처럼 여러 번 읽는 연산은 원자 사본이 아니니 사본은 [snapshot] 으로 뜬다. `containersAt[자리] = 용기` 쓰기도 받는다([World.set]).
     */
    val containersAt: World = World()

    /** 놓인 시각의 사본 — 자리 → 시각. */
    val placedAt: Map<String, Instant> get() = guarded { placed.toMap() }

    /** 막힌 목적지의 사본. 시계를 읽지 않는다(시계 안에서 불러도 된다). */
    val blocked: Set<String> get() = synchronized(lock) { closed.toSet() }

    /** 맡긴 횟수 — 같은 참조의 재전송은 세지 않는다. 재전송이 재실행이 되지 않았는지를 이것으로 본다. 시계를 읽지 않는다. */
    val dispatches: Int get() = synchronized(lock) { dispatchCount }

    /** [handle] 이 받은 주문 — 그 운반의 출발지·목적지를 본다. 모르는 핸들이면 널. 시계를 읽지 않는다. */
    fun orderOf(handle: TransportHandle): TransportOrder? = synchronized(lock) { transports[handle.id]?.order }

    /**
     * [containersAt] 의 창 — **옛 표기 호환용**이다. 읽기는 [Map] 의 모양이되 호출마다 따로 자물쇠를 지나므로 여러 번 읽는 연산이 원자가
     * 아니다. 쓰기는 [set] 하나다. 새 소비자는 읽기에 [snapshot]·[observe], 쓰기에 [place] 를 쓴다.
     */
    inner class World internal constructor() : AbstractMap<String, String>() {
        override val entries: Set<Map.Entry<String, String>>
            get() = guarded { world.toMap() }.entries

        override fun get(key: String): String? = containerAt(key)

        override fun containsKey(key: String): Boolean = containerAt(key) != null

        /**
         * [location] 에 [containerId] 를 둔다 — [place] 와 같되 **놓인 시각을 건드리지 않는다**. 옛 표기 호환용이다: 출하 전 시험 소스
         * 시절의 쓰기 표기(`fleet.containersAt[자리] = 용기`)를 그 뜻 그대로 잇는다. 새 소비자는 [place] 를 쓴다.
         */
        operator fun set(location: String, containerId: String) {
            guarded { world[location] = containerId }
        }
    }

    // ── 플릿 계약 ──────────────────────────────────────────────────────────────────────────────────────

    override fun dispatch(order: TransportOrder): TransportHandle? = guarded {
        byReference[order.reference]?.let { return TransportHandle(it) }
        dispatchCount += 1
        val id = "tr-${++seq}"
        val transport = Transport(order)
        // 출발지 확인은 접수 시점이다 — 요청한 용기가 거기 없으면 **인수하지 않는다**.
        val there = world[order.source]
        if (there != order.containerId) {
            transport.state = TransportState.REJECTED_AT_SOURCE
            transport.observed = there
            transport.detail = "요청 ${order.containerId}, 출발지에는 ${there ?: "없음"}"
        }
        transports[id] = transport
        byReference[order.reference] = id
        TransportHandle(id)
    }

    override fun find(reference: String): TransportHandle? = guarded {
        byReference[reference]?.let { TransportHandle(it) }
    }

    override fun status(handle: TransportHandle): TransportStatus = guarded {
        val t = transports[handle.id] ?: return TransportStatus(TransportState.FAILED, null, false, "모르는 운반: ${handle.id}")
        TransportStatus(t.state, t.observed, t.holding, t.detail)
    }

    /**
     * 운반을 거둔다. 인계했거나 출발지에서 거절했거나 모르는 핸들이면 `false`. 이미 거둔 운반을 다시 거두면 `true` 다(멱등).
     * 아는 운반에 대해서는 이 미믹이 [TransportState.FAILED] 를 내지 않는다(모르는 핸들의 [status] 만 낸다).
     *
     * **싣고 있던 용기를 출발지로 되돌려 놓는 것은 이 미믹의 약속이지 실 플릿의 보장이 아니다.** 실물 플릿은 취소된 용기를 다른 자리에
     * 내려놓거나 실은 채 멈출 수 있고, 그때 세계가 어디에 있는지는 플릿 어댑터와 현장이 말해야 한다. 이 미믹으로 초록인 시험은 «취소 뒤
     * 용기가 출발지에 있다» 를 실 플릿에 대해 말하지 않는다.
     *
     * **되돌림은 출발지를 조건 없이 덮어쓴다.** 인수 뒤 [place] 로 출발지에 다른 용기를 두었다면 취소가 그 용기를 지우고 실려 있던 용기를
     * 놓는다 — 이 미믹은 한 자리에 용기 하나만 든다.
     */
    override fun cancel(handle: TransportHandle): Boolean = guarded {
        val t = transports[handle.id] ?: return false
        if (t.state == TransportState.DELIVERED || t.state == TransportState.REJECTED_AT_SOURCE) return false
        if (t.holding) {
            world[t.order.source] = t.order.containerId
            t.holding = false
        }
        t.state = TransportState.CANCELLED
        true
    }

    // ── 안쪽 ──────────────────────────────────────────────────────────────────────────────────────────

    /** 시계 구동이면 [current](자물쇠 밖에서 읽은 시계)까지 지난 걸음을 따라잡는다. 자물쇠 안에서만 부른다. */
    private fun catchUp(current: Instant) {
        val every = stepEvery ?: return
        var next = lastStep!!.plus(every)
        while (!next.isAfter(current)) {
            if (!step(next)) {
                // 더 바뀔 것이 없다 — 나머지 걸음은 아무것도 안 바꾸므로 시각만 옮긴다.
                val skipped = Duration.between(next, current).toNanos() / every.toNanos()
                next = next.plus(every.multipliedBy(skipped))
                lastStep = next
                return
            }
            lastStep = next
            next = next.plus(every)
        }
    }

    /** 걸음 하나. [requested] 는 이 걸음에 놓이는 용기의 시각이다(이미 걸은 시각보다 이르면 그 시각을 쓴다). 무엇이 바뀌었으면 `true`. 자물쇠 안에서만 부른다. */
    private fun step(requested: Instant): Boolean {
        val at = latestStepAt?.takeIf { it.isAfter(requested) } ?: requested
        latestStepAt = at
        var changed = false
        transports.values.forEach { t ->
            when (t.state) {
                TransportState.ACCEPTED -> {
                    world.remove(t.order.source)
                    t.observed = t.order.containerId
                    t.holding = true
                    t.state = TransportState.PICKED_UP
                    changed = true
                }
                TransportState.PICKED_UP -> {
                    t.state = TransportState.IN_TRANSIT
                    changed = true
                }
                TransportState.IN_TRANSIT, TransportState.WAITING_HANDOVER -> {
                    if (t.order.destination in closed) {
                        if (t.state != TransportState.WAITING_HANDOVER) changed = true
                        t.state = TransportState.WAITING_HANDOVER
                    } else {
                        world[t.order.destination] = t.order.containerId
                        placed[t.order.destination] = at
                        t.holding = false
                        t.state = TransportState.DELIVERED
                        changed = true
                    }
                }
                TransportState.DELIVERED, TransportState.REJECTED_AT_SOURCE,
                TransportState.CANCELLED, TransportState.FAILED -> Unit
            }
        }
        return changed
    }
}

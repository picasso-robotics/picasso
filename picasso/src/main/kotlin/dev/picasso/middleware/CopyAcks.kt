package dev.picasso.middleware

/**
 * 담는 쪽의 사본 선언(§15.327, [Retention.copyAck]). 담는 쪽(mission-ops 실행 호스트)이 인시던트 줄·탐색 줄을 제 영속 기록에 적고
 * 커밋을 확인할 때까지([Middleware.ackCopiedIncidents]·[Middleware.ackCopiedSearches]) 보존 상한이 그 줄을 내리지 않는다 — 구동 중
 * 실행의 번들 예외와 같은 자리다. 확인 대기 한도가 대장 상한보다 크면 상한은 그만큼 넘을 수 있다.
 *
 * **확인한 줄도 내용이 바뀌면 다시 확인을 기다린다** — 인시던트의 검토([Middleware.reviewIncident])·판단(`resolution`)이 같은 id 의 줄을
 * 바꾸므로, 담는 쪽이 개정 줄을 다시 적고 확인할 때까지 상한이 그 줄을 내리지 않는다. 탐색 기록은 적힌 뒤 바뀌지 않는다.
 *
 * **보장은 «프로세스가 사는 동안 수집 전에 잃지 않는다» 까지다.** 재기동을 넘는 보관·조회는 담는 쪽의 몫이고, 확인한 줄을 picasso 로 되돌려
 * 넣는 입구는 없다.
 */
data class CopyAck(
    /**
     * 인시던트 번들의 확인 대기 한도. 널이면 [Retention.incidents] 와 같다 — 번들이 가장 무거우므로 기본에서는 선언이 메모리를 상한 위로
     * 올리지 않고, 내릴 때 확인한 줄을 먼저 고르게만 한다. 넘으면 **가장 오래 기다린 줄부터** 보호를 풀고 [CopyLedgerState.exposed] 로
     * 센다. 풀린 줄은 보통 줄처럼 상한이 내리고, 확인 전에 내려가면 [CopyLedgerState.evictedBeforeCopy] 로 센다.
     */
    val pendingIncidents: Int? = null,
    /** 탐색 기록의 확인 대기 한도. 널이면 [Retention.remedySearches] 와 같다. 규칙은 [pendingIncidents] 와 같다. */
    val pendingSearches: Int? = null,
) {
    init {
        require((pendingIncidents ?: 1) > 0 && (pendingSearches ?: 1) > 0) { "확인 대기 한도는 양수여야 한다: $this" }
    }
}

/**
 * 사본 선언의 지금 상태(§15.327) — [Middleware.copyAcks]. 선언이 꺼져 있으면 [declared] 가 널이고 두 대장의 `pending`·`exposed`·
 * `evictedBeforeCopy` 는 0 이다. `overCap` 은 선언과 무관하게 재므로 꺼져 있어도 구동 중 실행의 번들이 상한을 넘긴 만큼 0 이 아닐 수 있다.
 */
data class CopyAckState(
    val declared: CopyAck?,
    /** 인시던트 번들 — 인시던트 id 로 확인한다. */
    val incidents: CopyLedgerState,
    /** 탐색 기록 — 탐색 id 로 확인한다. */
    val remedySearches: CopyLedgerState,
)

/** 한 대장의 사본 확인 상태. 셈은 인스턴스가 뜬 뒤로다. */
data class CopyLedgerState(
    /** 확인을 기다리며 보호받는 줄의 수. */
    val pending: Int,
    /** 대장이 보존 상한을 넘은 정도 — 든 수 − 상한, 넘지 않았으면 0. 확인 대기와 구동 중 실행이 남긴 몫이다. */
    val overCap: Int,
    /** 확인 대기 한도를 넘어 보호가 풀린 줄의 수(누계). */
    val exposed: Long,
    /** 확인 전에 내린 줄의 수(누계) — 보호가 풀린 뒤 상한이 내린 것. 0 이 아니면 담는 쪽 사본에 그만큼 빠졌을 수 있다. */
    val evictedBeforeCopy: Long,
)

/**
 * 한 대장의 확인 표. 선언이 꺼져 있으면([limit] 널) 아무것도 들지 않고 [protects] 는 늘 거짓이라 대장의 거동이 선언 전과 같다.
 * 새 줄은 [opened] 로, 내용이 바뀐 줄은 [revised] 로 들이고, 대장이 상한으로 줄을 내릴 때 [evicted] 를 부른다.
 *
 * **메모리를 묶는 것은 확인 대기 한도까지다.** 구동 중 실행의 번들은 이 표와 무관하게 상한을 건너뛰므로(기존 예외) 한도와 상한 밖으로 계속
 * 늘 수 있다 — 그 몫은 [CopyLedgerState.overCap] 에 보인다.
 */
internal class CopyWatch(private val limit: Int?) {

    /** 확인을 기다리는 id — 들인 순서대로. */
    private val pending = linkedSetOf<String>()

    /** 한도를 넘어 보호가 풀렸고 아직 확인되지 않은 id. 내려가면 «확인 전 내림» 이다. */
    private val unprotected = mutableSetOf<String>()

    private var exposed = 0L
    private var evictedBeforeCopy = 0L

    fun opened(id: String) {
        if (limit == null) return
        pending += id
        while (pending.size > limit) {
            val oldest = pending.first()
            pending.remove(oldest)
            unprotected += oldest
            exposed += 1
        }
    }

    /**
     * 대장에 든 줄의 내용이 바뀌었다 — 확인했거나 보호가 풀렸어도 맨 뒤의 확인 대기로 다시 올린다(한도·셈 규칙은 [opened] 와 같다).
     * 이미 확인을 기다리면 그대로 둔다. 대장에 없는 id 로는 부르지 않는다.
     */
    fun revised(id: String) {
        if (limit == null || id in pending) return
        unprotected.remove(id)
        opened(id)
    }

    /** 이 줄을 상한이 건너뛰어야 하는가. */
    fun protects(id: String): Boolean = id in pending

    fun evicted(id: String) {
        if (unprotected.remove(id)) evictedBeforeCopy += 1
    }

    /** 확인한다 — 멱등이고 모르는 id 는 무시한다. 새로 확인한 수를 돌려준다(보호가 풀렸던 줄도 센다). */
    fun ack(ids: Collection<String>): Int = ids.toSet().count { pending.remove(it) || unprotected.remove(it) }

    fun state(size: Int, cap: Int) = CopyLedgerState(pending.size, maxOf(0, size - cap), exposed, evictedBeforeCopy)
}

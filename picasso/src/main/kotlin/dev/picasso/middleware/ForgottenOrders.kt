package dev.picasso.middleware

/**
 * 놓은 작업 지시 표시의 지금 상태(§15.324) — [Middleware.forgottenOrders]. [RetentionEvictions] 와 달리 실제로 내린 수가 아니라
 * 표가 얼마나 찼는지와 그 때문에 무엇을 거부했는지를 본다.
 */
data class ForgottenOrdersState(
    /** 지금 든 표시 수. */
    val held: Int,
    /** 상한([Retention.forgottenOrders]). */
    val cap: Int,
    /** 넘침 선언([Retention.forgottenOverflow]). */
    val overflow: ForgottenOverflow,
    /**
     * 표가 차서 거부한 [Middleware.forget] 의 수 — 인스턴스가 뜬 뒤로 센다. [ForgottenOverflow.EVICT] 면 늘 0 이다. 찼다는 신호는 이 수가
     * 아니라 [held] == [cap] 이다 — 라운드 끝 정리가 다른 까닭(구동 중·확인 안 된 통보)으로 거부된 실행을 만나고 멈추면 이 수는 오르지 않는다.
     */
    val refusedForgets: Long,
    /** [Middleware.releaseForgotten] 이 표시를 지운 수 — 인스턴스가 뜬 뒤로 센다. */
    val released: Long,
)

/**
 * 놓은 작업 지시 — 작업 지시 id 마다 그 실행의 id·판·종착 상태 하나([Middleware.forget]). 같은 작업 지시 id 가 다시 오면 이것으로
 * 거부한다(§15.296, §15.324).
 *
 * 실행을 통째로 들지 않고 이 셋만 든다. 넣은 순서를 지킨다. 상한에 닿으면 넘침 선언이 정한다 — 기본([ForgottenOverflow.REFUSE])은
 * 더 놓지 않고 거부하며, 담는 쪽이 [ForgottenOverflow.EVICT] 를 선언했으면 오래된 것부터 내리고 센다. 내린 id 의 재전송은 그 선언대로
 * 담는 쪽의 기록이 막는다.
 */
internal class ForgottenOrders(private val cap: Int, private val overflow: ForgottenOverflow) {

    private val marks = linkedMapOf<String, ForgottenOrder>()

    /** 상한으로 내린 표시 수([RetentionEvictions.forgottenOrders]). */
    var evicted = 0L
        private set

    private var refusedForgets = 0L
    private var released = 0L

    private data class ForgottenOrder(val executionId: String, val version: Int, val state: PhysicalState)

    operator fun contains(jobOrderId: String): Boolean = jobOrderId in marks

    /**
     * 표가 차서 더 놓지 못하는가 — [ForgottenOverflow.REFUSE] 이고 든 표시가 상한에 닿았다. 이미 표시가 있는 id 는 새 자리를 쓰지
     * 않으므로 [fullRefusal] 이 따로 본다.
     */
    val full: Boolean get() = overflow == ForgottenOverflow.REFUSE && marks.size >= cap

    /**
     * 이 작업 지시를 표시하면 상한을 넘는데 넘침을 선언하지 않았으면 놓기 거부, 아니면 널. 거부하면 센다. **실행·아웃박스를 건드리기
     * 전에** 부른다 — 표시를 못 남기는 놓기는 같은 id 를 막을 근거 없이 실행만 지운다.
     */
    fun fullRefusal(jobOrderId: String, executionId: String): ForgetOutcome.Refused? {
        if (!full || jobOrderId in marks) return null
        refusedForgets++
        return ForgetOutcome.Refused(
            "$FULL: ${marks.size}/$cap — 담는 쪽이 끝난 작업 지시를 제 기록에 적고 releaseForgotten 으로 비우거나, " +
                "그 기록이 같은 id 를 막는다면 Retention.forgottenOverflow 를 EVICT 로 선언한다: $executionId",
        )
    }

    /** 놓은 실행의 작업 지시를 표시한다. [ForgottenOverflow.EVICT] 면 상한을 넘은 만큼 오래된 것부터 내리고 센다. */
    fun mark(jobOrderId: String, executionId: String, version: Int, state: PhysicalState) {
        marks[jobOrderId] = ForgottenOrder(executionId, version, state)
        while (overflow == ForgottenOverflow.EVICT && marks.size > cap) {
            marks.remove(marks.keys.first())
            evicted++
        }
    }

    /** 놓은 작업 지시면 접수 거부, 아니면 널. */
    fun refusal(jobOrderId: String): Middleware.Submission.Rejected? {
        val gone = marks[jobOrderId] ?: return null
        return Middleware.Submission.Rejected(
            "${MiddlewareCodes.FORGOTTEN_JOB_ORDER}: $jobOrderId · ${gone.executionId} · 판 ${gone.version} · ${gone.state} — " +
                "다시 할 일은 새 작업 지시 id 로 낸다",
        )
    }

    /** 표시를 지웠으면 참, 지금 들고 있지 않으면 거짓([Middleware.releaseForgotten]). */
    fun release(jobOrderId: String): Boolean {
        if (marks.remove(jobOrderId) == null) return false
        released++
        return true
    }

    fun state() = ForgottenOrdersState(marks.size, cap, overflow, refusedForgets, released)

    private companion object {
        /** 찬 표 때문에 놓지 않을 때 사유의 앞머리. */
        const val FULL = "놓은 작업 지시 표시가 상한에 찼다"
    }
}

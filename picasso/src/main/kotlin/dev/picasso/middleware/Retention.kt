package dev.picasso.middleware

/**
 * 이 층이 메모리에 드는 기록의 보존 상한(§15.166, §15.296).
 *
 * 인시던트 번들, 탐색 기록, 서 있는 제안, 승인 소모 기록, 실행 표, 놓은 작업 지시 표시는 프로세스 안의 목록이고, 상한이 없으면 오래 도는 인스턴스에서 끝없이
 * 는다. **상한은 메모리를 묶는 것이지 보존 규약이 아니다** — 오래 둘 기록은 담는 쪽이 내보내기(§6)로 가져가고, 적재의 소유자가
 * 정해지면 보존 기간은 그쪽 규약이 정한다.
 *
 * 넘치면 **가장 오래된 것부터** 내린다. 단 아직 구동이 돌리는 실행([PhysicalState.pumps])의 인시던트 번들과 소모 기록은 내리지
 * 않는다 — 운영자가 그 실행을 다루는 동안 근거가 사라지면 안 된다. 그래서 상한은 그만큼 넘을 수 있다.
 *
 * **놓은 작업 지시 표시는 기본으로 내리지 않는다**(§15.324). 그 표시가 같은 작업 지시 id 의 재전송을 막는 이 층의 근거라, 내리면
 * 그 id 가 조용히 새 실행으로 선다. 차면 더 놓지 않고([Middleware.forget] 이 거부), 내리는 것은 담는 쪽이 [forgottenOverflow] 로
 * 그 막음을 넘겨받겠다고 선언했을 때뿐이다.
 *
 * 내린 기록은 되돌리지 않는다. 내린 제안을 승인하면 «제안이 없다» 로 거부되고(승인 없이 실행되는 길은 생기지 않는다), 내린
 * 소모 기록의 열쇠에 다시 온 승인도 같은 답을 받는다. 내린 수는 [RetentionEvictions] 로 센다.
 *
 * **담는 쪽이 사본을 선언하면([copyAck]) 확인 전의 인시던트 번들·탐색 기록도 내리지 않는다**(§15.327) — 구동 중 실행의 예외와 같은 자리다.
 */
data class Retention(
    /** 인시던트 번들. 번들 하나가 근거 윈도우를 통째로 들어 가장 무거우므로 상한이 가장 낮다. */
    val incidents: Int = 2_000,
    /** 탐색 기록([RemedySearchRecord]). */
    val remedySearches: Int = 10_000,
    /** 서 있는 제안 — (기체, 주문)마다. 함께 든 가림 표시와 진단도 같이 내린다. */
    val proposals: Int = 10_000,
    /** 승인 소모 기록(ADR 46) — (기체, 주문)마다. 함께 든 진단도 같이 내린다. */
    val consumedApprovals: Int = 10_000,
    /**
     * 실행 표(§15.296). 넘치면 라운드 끝에 **놓을 수 있는 실행**([Middleware.forget] 이 받는 실행 — 구동이 더는 돌리지 않고 통보가
     * 전부 확인된 것)을 오래된 것부터 놓는다. 놓을 수 없는 실행은 남기므로 상한은 그만큼 넘을 수 있다. 놓은 작업 지시 표시가
     * [forgottenOrders] 에 찼고 [forgottenOverflow] 가 기본(`REFUSE`)이면 놓기가 모두 거부되므로, 담는 쪽이 표시를 비우거나
     * 넘침을 선언할 때까지 실행 표도 상한을 넘어 는다(§15.324).
     */
    val executions: Int = 10_000,
    /**
     * 놓은 작업 지시 표시(§15.296). 차면 [forgottenOverflow] 가 정한다 — 기본은 더 놓지 않고, `EVICT` 면 오래된 표시부터 내린다.
     * 지금 든 수와 찬 표 때문에 거부한 놓기는 [Middleware.forgottenOrders] 가 보여 준다.
     */
    val forgottenOrders: Int = 100_000,
    /** 놓은 작업 지시 표시가 [forgottenOrders] 에 찼을 때의 선언(§15.324). 기본은 거부다. */
    val forgottenOverflow: ForgottenOverflow = ForgottenOverflow.REFUSE,
    /**
     * 담는 쪽의 사본 선언(§15.327). 기본은 꺼짐(널) — 거동이 선언 전과 같다. 켜면 인시던트 번들·탐색 기록은 담는 쪽이 커밋을 확인할 때까지
     * ([Middleware.ackCopiedIncidents]·[Middleware.ackCopiedSearches]) [incidents]·[remedySearches] 상한이 내리지 않는다. 확인 대기 한도와
     * 지금 상태는 [CopyAck]·[Middleware.copyAcks] 가 말한다.
     */
    val copyAck: CopyAck? = null,
    /**
     * 리비전 제거 사건([RevisionRemoval], §15.329). 사건이 작아 상한이 탐색 기록과 같다. 사본 선언([copyAck])의 보호는 받지 않는다 — 담는 쪽은
     * [Middleware.revisionRemovals] 를 실행의 판단 기록과 같은 자리에서 옮겨 적는다(인계 노트).
     */
    val revisionRemovals: Int = 10_000,
) {
    init {
        require(
            incidents > 0 && remedySearches > 0 && proposals > 0 && consumedApprovals > 0 && executions > 0 && forgottenOrders > 0 && revisionRemovals > 0,
        ) {
            "보존 상한은 양수여야 한다: $this"
        }
    }
}

/** 놓은 작업 지시 표시가 상한에 찼을 때 무엇을 하는가(§15.324, [Retention.forgottenOverflow]). */
enum class ForgottenOverflow {
    /**
     * 더 놓지 않는다 — [Middleware.forget] 이 실행과 통보를 건드리기 전에 거부하고, 라운드 끝의 정리도 그 라운드에 멈춘다. 담는 쪽이
     * 끝난 작업 지시를 제 기록에 적고 [Middleware.releaseForgotten] 으로 표시를 비우면 다시 놓는다.
     */
    REFUSE,

    /**
     * 오래된 표시부터 내리고 [RetentionEvictions.forgottenOrders] 로 센다. 내린 작업 지시 id 는 이 층이 더는 거부하지 못하고 다시 오면
     * 새 실행으로 받는다. **이 선언은 약속이다** — 담는 쪽이 작업 지시 id 의 유일성을 제 영속 기록으로 보증하고, 같은 id 의
     * 접수([Middleware.submit]·[Middleware.adopt]·[Middleware.resume])와 승인을 이 층을 부르기 전에 거부한다.
     */
    EVICT,
}

/** 보존 상한으로 내린 기록의 수 — 인스턴스가 뜬 뒤로 센다(§15.166). 0 이 아니면 그만큼 조회에서 빠졌다. */
data class RetentionEvictions(
    val incidents: Long,
    val remedySearches: Long,
    val proposals: Long,
    val consumedApprovals: Long,
    /** 상한으로 놓은 실행 — 담는 쪽이 부른 [Middleware.forget] 은 세지 않는다. */
    val executions: Long,
    /** 내린 놓은 작업 지시 표시 — [ForgottenOverflow.EVICT] 일 때만 는다. 찬 표 때문에 거부한 놓기는 [Middleware.forgottenOrders] 가 센다. */
    val forgottenOrders: Long,
    /** 내린 리비전 제거 사건([Retention.revisionRemovals], §15.329). */
    val revisionRemovals: Long = 0,
)

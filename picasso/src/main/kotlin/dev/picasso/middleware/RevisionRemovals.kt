package dev.picasso.middleware

import java.time.Instant

/** 리비전이 뺀 단위 하나와 뺄 때의 상태(§15.329). 받는 판정은 손대지 않은 단위만 빼므로 상태는 `PENDING` 이거나 계획 실패 `FAILED` 다. */
data class RemovedUnit(val unitId: String, val state: UnitState)

/**
 * **리비전이 작업 지시에서 항목을 빼며 실행에서 지운 단위의 사건 하나**(§15.329) — 감사 내보내기의 레지스터 `revision-removals.jsonl` 의 한 줄
 * (내보내기 버전 10). 실행 자취의 [MiddlewareCodes.REVISION_REMOVED] 줄과 같은 사실이고, 자취는 판단 기록에 실리지 않아 리빌드 뒤 사라지므로 이
 * 사건이 «무엇이 어느 리비전에서 빠졌나» 를 담는 쪽으로 나르는 자리다. 실패·취소·완료가 아니다 — 지운 단위는 작업 응답에서 빠진다.
 *
 * @property fromVersion 지우기 전 실행이 든 작업 지시 버전. 리비전이 버전을 건너뛰면 `toVersion - 1` 이 아니다.
 * @property toVersion 지운 리비전의 작업 지시 버전.
 * @property at 가상 시계. [wallClockAt] 은 실 시계다 — 인시던트 줄과 같은 두 시각이다.
 */
data class RevisionRemoval(
    val executionId: String,
    val jobOrderId: String,
    val robotId: String,
    val fromVersion: Int,
    val toVersion: Int,
    val removed: List<RemovedUnit>,
    val at: Instant,
    val wallClockAt: Instant,
)

/**
 * 리비전 제거 사건의 레지스터 — 적힌 순서대로 덧붙이기만 한다. 보존 상한([Retention.revisionRemovals])을 넘으면 가장 오래된 것부터 내리고
 * [evicted] 로 센다. 사본 선언([Retention.copyAck])의 보호는 받지 않는다 — 사건이 작고 드물어 한도를 따로 두지 않았다(`docs/limits.md`).
 */
internal class RevisionRemovals(private val wallClock: () -> Instant, private val cap: Int = Retention().revisionRemovals) {
    private val records = ArrayDeque<RevisionRemoval>()

    /** 상한으로 내린 사건 수 — 인스턴스가 뜬 뒤로 센다. */
    var evicted = 0L
        private set

    /** 사건 하나를 적는다. 실 시계는 여기서 읽는다. */
    fun record(executionId: String, jobOrderId: String, robotId: String, fromVersion: Int, toVersion: Int, removed: List<RemovedUnit>, at: Instant) {
        records.addLast(RevisionRemoval(executionId, jobOrderId, robotId, fromVersion, toVersion, removed, at, wallClock()))
        while (records.size > cap) {
            records.removeFirst()
            evicted += 1
        }
    }

    fun records(): List<RevisionRemoval> = records.toList()
}

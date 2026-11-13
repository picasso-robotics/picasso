package dev.picasso.middleware

import dev.picasso.contracts.v1.WatchTaskResponse
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * 명령 생애·드라이버·접합부가 부르는 미들웨어의 좁은 환경(의존 정비 S4b, 설계 변경 이력 §15.312) — 포트, 시계, 기체 뷰, 이번 라운드의
 * 현장 시간값, 트레이스·설비 관측·통보. 실행 표·게이트·장부는 들지 않는다 — 그것을 쓰는 창구 절차는 `Middleware` 에 남는다.
 * 상태 층이라 전이 층의 절차를 부르지 않는다(층 시험). 인스턴스는 `Middleware` 하나가 하나를 쥔다.
 */
internal class CommandEnv(
    val robots: RobotPort,
    val cell: CellSignals,
    val fleet: AmrFleetPort,
    val now: () -> Instant,
    /** 발행 게이트(작업 허가·퇴역). [issuable] 이 묻는다. */
    val issuing: IssuingGate,
    private val notifier: (Execution) -> Unit,
) {
    val views = mutableMapOf<String, RobotView>()

    /**
     * 이번 라운드의 현장 시간값. [pump] 가 시작할 때 [siteTimings] 를 한 번 읽어 둔다. 널이면 케이퍼빌리티 값이다.
     *
     * 라운드 안에서는 다시 읽지 않는다. 판정마다 읽으면 한 라운드 안에서 두 버전의 값이 섞인다.
     */
    var round: SiteTimings? = null

    /** 이 라운드의 근거 시간 윈도우. 현장 값이 있으면 그것, 없으면 케이퍼빌리티 값이다. */
    fun windowOf(execution: Execution): EvidenceWindow = round?.evidenceWindow ?: execution.capability.evidenceWindow

    /** 이 라운드의 `IN_DOUBT` 유예. 단위에 저장하지 않는다. */
    fun inDoubtGraceOf(execution: Execution): Duration = round?.inDoubtGrace ?: execution.capability.inDoubtGrace

    /** 이 라운드의 진행 정체 유예. */
    fun stallWindowOf(execution: Execution): Duration = round?.stallWindow ?: execution.capability.stallWindow

    /** [at] 은 이 줄의 시각이다. 기본은 지금이고, 같은 시각을 다른 사실과 나눠 써야 하는 자리만 넘긴다([reopenHold]). */
    fun Execution.trail(kind: String, detail: String, at: Instant = now()) {
        mutableEventTrail += ObservedEvent(views[robotId]?.cursor ?: 0, at.toString(), kind, detail, local = true)
    }

    /** 이미 적은 (기체, 선택 키). 같은 사실을 되풀이하면 운영자가 곧 무시한다. */
    val droppedOptionals = mutableSetOf<Pair<String, String>>()

    /** 설비에 묻고 **그 사실을 자취에 남긴다** — 조회하고 버리면 번들의 근거 창에서 설비 쪽이 빈다. */
    fun Execution.observeCell(unit: ExecutionUnit): SlotSignal? {
        val where = unit.destination ?: return null
        val signal = cell.observe(where)
        val what = if (signal == null) {
            "no signal"
        } else {
            "occupied=${signal.occupied} identity=${signal.identity ?: "(none)"} at=${signal.observedAt?.toString() ?: "(read now)"}"
        }
        trail("CELL_SIGNAL", "$where: $what")
        return signal
    }

    /** 하류 갱신의 시각 — 헤더의 `state_as_of`(§5.5). 없거나 못 읽으면 지금. */
    fun stateTime(update: WatchTaskResponse): Instant = try {
        update.header.stateAsOf.takeIf { it.isNotBlank() }?.let { Instant.parse(it) } ?: now()
    } catch (_: DateTimeParseException) {
        now()
    }

    /** 실행의 작업 응답을 낸다(`Middleware.notify`). 응답 표와 운영자 주의는 창구가 쥔다. */
    fun notify(execution: Execution) = notifier(execution)
}

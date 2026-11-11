package dev.picasso.middleware

import dev.picasso.middleware.Middleware.Execution
import dev.picasso.middleware.Middleware.RobotView
import java.time.Duration
import java.time.Instant

// 지금 읽기와 발신자 시계 차 구간(환경 전제 K). 상태(뷰, 실행)는 Middleware 에 있다.

/** 미리 읽지 않는 포트에는 지금 묻는다. 보낸 시각과 받은 시각은 미들웨어 시계다. */
internal fun Middleware.readNow(robotId: String): PrefetchedRead {
    val sentAt = now()
    val snapshot = try {
        robots.snapshot(robotId)
    } catch (e: RuntimeException) {
        return PrefetchedRead.Failed(e)
    }
    return PrefetchedRead.Read(snapshot, sentAt, now())
}

/**
 * 발신자 시계 차의 구간을 재고, 구간 전체가 임계 밖이면 그 기체의 진행 중인 실행에 [CLOCK_SKEW] 를 남긴다(환경 전제 K).
 *
 * 응답 헤더의 `occurred_at` 은 발신자가 자기 시계로 찍은 시각이고, 그 시각은 우리가 보낸 순간과 받은 순간 사이의 어딘가에 해당한다. 그래서
 * 차(발신자 − 미들웨어)는 한 값이 아니라 `[occurred − 받은 시각, occurred − 보낸 시각]` 구간이다. 왕복이 길수록 구간이 넓어지고, 구간이
 * 임계에 걸치면 시계 차인지 왕복 지연인지 가를 수 없으므로 남기지 않는다. **판정은 바꾸지 않는다** — 보정은 계약 §4 가 하지 않는다고 한 일이다.
 * 셀 시계는 잴 수단이 없어 다루지 않는다.
 */
internal fun Middleware.measureClock(
    robotId: String,
    view: RobotView,
    snapshot: RobotSnapshot,
    sentAt: Instant,
    receivedAt: Instant,
    live: List<Execution>,
) {
    val occurred = snapshot.occurredAt ?: return
    val offset = ClockOffset(low = Duration.between(receivedAt, occurred), high = Duration.between(sentAt, occurred))
    view.clockOffset = offset
    live.forEach { it.noteClockSkew(robotId, offset) }
}

/**
 * 이 실행의 근거 시간 윈도우로 본 발신자 시계 차 의심. 구간 전체가 임계 밖일 때만 값이 있다.
 *
 * 임계는 **좁은 쪽 허용 폭의 절반**이다 — 윈도우의 앞·뒤 폭 중 좁은 것의 절반(기본값이면 15초의 절반인 7.5초, 현장이 줄이면 따라 줄어든다).
 * 허용 폭은 발신자가 늦는 쪽이 `뒤 폭 − L`, 빠른 쪽이 `앞 폭 − L` 이고 L(셀 폴링 주기·pump 주기·전달 지연의 합)은 현장 값이라 여기서 모른다.
 * 그래서 폭의 절반에서 알린다 — 허용 폭에 닿기 전에 사람이 보게 하는 여유다(`docs/environment-preconditions.md` 절 K).
 */
internal fun Middleware.clockSuspicionOf(execution: Execution, offset: ClockOffset?): ClockSuspicion? {
    offset ?: return null
    val window = windowOf(execution)
    val threshold = minOf(window.before, window.after).dividedBy(2)
    return when {
        offset.high < threshold.negated() -> ClockSuspicion(behind = true, nearest = offset.high.negated(), farthest = offset.low.negated(), threshold = threshold)
        offset.low > threshold -> ClockSuspicion(behind = false, nearest = offset.low, farthest = offset.high, threshold = threshold)
        else -> null
    }
}

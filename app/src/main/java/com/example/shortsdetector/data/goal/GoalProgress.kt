package com.example.shortsdetector.data.goal

import com.example.shortsdetector.data.usage.SessionRecord
import java.time.LocalDate
import java.time.ZoneId

enum class GoalStatus {
    /** 지키는 중. 아직 하루가 끝나지 않았다. */
    ON_TRACK,
    /** 하루 종료 후 지켰음이 확정. SELF_STOPS 는 횟수를 채운 즉시. */
    ACHIEVED,
    /** 제한형 목표를 넘겼다. 하루 중에도 확정이다. */
    EXCEEDED,
    /** 기간이 끝났는데 횟수형 목표를 못 채웠다. 넘긴 것이 아니어서 [EXCEEDED] 와 구분한다. */
    MISSED,
    /** 판정할 기준이 없다(지난주 기록이 없어 "지난주보다 줄이기"를 계산할 수 없음). 달성도 실패도 아니다. */
    UNAVAILABLE
}

/** 하루치 측정값. 시간은 ms. */
data class GoalDayMetrics(
    val watchMs: Long,
    val longestSessionMs: Long,
    val sessionCount: Int,
    val selfStopCount: Int,
    /** TIME_WINDOW 제한 구간 안의 추정 시청시간. [TimeWindowUsage] 로 계산한다. */
    val restrictedWatchMs: Long,
    /** Lv.1 개입에서 바로 멈춘 횟수 */
    val lv1StopCount: Int = 0,
    /** 스스로 멈춘 뒤 30분 동안 쇼츠로 돌아오지 않았음이 확정된 횟수 */
    val noReturnStopCount: Int = 0,
    /** 최대 단계 개입을 한 번이라도 보여 줬다 */
    val maxLevelReached: Boolean = false,
    /** MORNING_START 구간(05:00~기준 시각) 안의 추정 시청시간 */
    val morningWatchMs: Long = 0L
)

/**
 * 이번 주(월~일) 측정값. 주간 목표에만 쓴다.
 * @param lastWeekWatchMs 지난주 월~일 합계. 기록이 없으면 null(줄이기 목표를 판정할 수 없다).
 * @param shortsFreeDays 이번 주 끝난 날 중 시청이 0 인 날 수(오늘은 하루가 끝나야 센다)
 */
data class GoalWeekMetrics(
    val weekWatchMs: Long,
    val lastWeekWatchMs: Long?,
    val shortsFreeDays: Int,
    val weekFinished: Boolean
)

/**
 * @param currentValue [Goal.value] 와 같은 단위의 현재 값. TIME_WINDOW 는 제한 구간에서 본 분. 시간은 올림이라 넘겼으면 항상 목표보다 크다.
 *   WEEKLY_REDUCTION 은 예외로 이번 주 시청 분이다.
 * @param target [currentValue] 와 비교할 한도가 [Goal.value] 와 다를 때. WEEKLY_REDUCTION 의 이번 주 허용 분.
 */
data class GoalProgress(val goal: Goal, val currentValue: Int, val status: GoalStatus, val target: Int? = null)

/**
 * 목표별 달성 상태. 순수 함수.
 *
 * 제한형(시간·횟수 상한)은 하루가 끝나야 지켰다고 말할 수 있다. 그래서 그날이 진행 중이면 넘기기 전까지
 * [GoalStatus.ON_TRACK] 이고, [GoalStatus.ACHIEVED] 는 dayFinished = true 일 때만 나온다.
 * SELF_STOPS 는 횟수를 채우는 즉시 ACHIEVED 다. 상한과 정확히 같은 값은 넘긴 것이 아니다.
 */
object GoalProgressCalculator {

    private const val MS_PER_MINUTE = 60_000L

    private const val PERCENT = 100

    /**
     * @param week 주간 목표에 필요하다. null 이면 주간 목표는 측정값 0 인 진행 중으로 본다.
     */
    fun evaluate(
        goal: Goal,
        metrics: GoalDayMetrics,
        dayFinished: Boolean = false,
        week: GoalWeekMetrics? = null
    ): GoalProgress =
        when (goal.type) {
            GoalType.DAILY_TOTAL -> limitByMinutes(goal, metrics.watchMs, dayFinished)
            GoalType.PER_SESSION -> limitByMinutes(goal, metrics.longestSessionMs, dayFinished)
            GoalType.TIME_WINDOW -> {
                val status = limitStatus(metrics.restrictedWatchMs > 0L, dayFinished)
                GoalProgress(goal, ceilMinutes(metrics.restrictedWatchMs), status)
            }
            GoalType.DAILY_OPENS ->
                GoalProgress(goal, metrics.sessionCount, limitStatus(metrics.sessionCount > goal.value, dayFinished))
            GoalType.SELF_STOPS -> GoalProgress(
                goal, metrics.selfStopCount,
                when {
                    metrics.selfStopCount >= goal.value -> GoalStatus.ACHIEVED
                    dayFinished -> GoalStatus.MISSED
                    else -> GoalStatus.ON_TRACK
                }
            )
            GoalType.MORNING_START -> GoalProgress(
                goal, ceilMinutes(metrics.morningWatchMs), limitStatus(metrics.morningWatchMs > 0L, dayFinished)
            )
            GoalType.NO_RETURN_30M -> atLeast(goal, metrics.noReturnStopCount, dayFinished)
            GoalType.LV1_STOPS -> atLeast(goal, metrics.lv1StopCount, dayFinished)
            GoalType.NO_MAX_LEVEL -> GoalProgress(
                goal, if (metrics.maxLevelReached) 1 else 0, limitStatus(metrics.maxLevelReached, dayFinished)
            )
            GoalType.WEEKLY_TOTAL -> {
                val w = week ?: EMPTY_WEEK
                limitByMinutes(goal, w.weekWatchMs, w.weekFinished)
            }
            GoalType.WEEKLY_REDUCTION -> reduction(goal, week ?: EMPTY_WEEK)
            GoalType.SHORTS_FREE_DAYS -> {
                val w = week ?: EMPTY_WEEK
                atLeast(goal, w.shortsFreeDays, w.weekFinished)
            }
        }

    private val EMPTY_WEEK = GoalWeekMetrics(0L, null, 0, false)

    /** 채우는 즉시 달성, 기간이 끝났는데 못 채웠으면 놓침 */
    private fun atLeast(goal: Goal, count: Int, finished: Boolean) = GoalProgress(
        goal, count,
        when {
            count >= goal.value -> GoalStatus.ACHIEVED
            finished -> GoalStatus.MISSED
            else -> GoalStatus.ON_TRACK
        }
    )

    /**
     * 이번 주 허용 = 지난주 × (100 - N)%. 주 중간에도 허용을 넘기면 더 줄일 수 없으므로 바로 초과다.
     * 지난주 기록이 없으면 비교할 수 없어 UNAVAILABLE 이다.
     */
    private fun reduction(goal: Goal, w: GoalWeekMetrics): GoalProgress {
        val minutes = ceilMinutes(w.weekWatchMs)
        val last = w.lastWeekWatchMs?.takeIf { it > 0L } ?: return GoalProgress(goal, minutes, GoalStatus.UNAVAILABLE)
        val allowedMs = last * (PERCENT - goal.value) / PERCENT
        return GoalProgress(
            goal, minutes, limitStatus(w.weekWatchMs > allowedMs, w.weekFinished),
            target = (allowedMs / MS_PER_MINUTE).toInt()
        )
    }

    private fun limitByMinutes(goal: Goal, ms: Long, dayFinished: Boolean) =
        GoalProgress(goal, ceilMinutes(ms), limitStatus(ms > goal.value * MS_PER_MINUTE, dayFinished))

    private fun limitStatus(exceeded: Boolean, dayFinished: Boolean) = when {
        exceeded -> GoalStatus.EXCEEDED
        dayFinished -> GoalStatus.ACHIEVED
        else -> GoalStatus.ON_TRACK
    }

    internal fun ceilMinutes(ms: Long): Int = ((ms + MS_PER_MINUTE - 1) / MS_PER_MINUTE).toInt()
}

/**
 * TIME_WINDOW 제한 구간의 시청시간. 제한은 그날 05:00 에 풀린다(이른 아침 시청은 위반이 아니다).
 *
 *  - 달력 날짜 하나에는 구간이 둘 생긴다: 자정~05:00(전날 밤에서 이어짐), 기준 시각~자정.
 *  - 기준이 자정을 넘기면(예: 새벽 1:00) 01:00~05:00 하나뿐이다.
 *  - 세션 안의 정확한 시청 위치는 저장하지 않으므로, 일별 집계와 같이 겹친 비율만큼 나눈 근사값이다.
 */
object TimeWindowUsage {

    private const val MINUTES_PER_DAY = 24 * 60
    private const val RELEASE_HOUR = 5

    fun restrictedWatchMs(sessions: List<SessionRecord>, cutoffValue: Int, date: LocalDate, zone: ZoneId): Long {
        val intervals = restrictedIntervals(cutoffValue, date, zone)
        return sessions.sumOf { s -> intervals.sumOf { (from, to) -> shareOf(s, from, to) } }
    }

    /** 아침 구간 [05:00, 기준 시각) 의 시청시간. 기준이 05:00 이하면 0 이다. */
    fun morningWatchMs(sessions: List<SessionRecord>, cutoffMinute: Int, date: LocalDate, zone: ZoneId): Long {
        if (cutoffMinute <= GoalRules.MORNING_FROM_MINUTE) return 0L
        val from = date.atStartOfDay(zone).plusMinutes(GoalRules.MORNING_FROM_MINUTE.toLong()).toInstant().toEpochMilli()
        val to = date.atStartOfDay(zone).plusMinutes(cutoffMinute.toLong()).toInstant().toEpochMilli()
        return watchMsBetween(sessions, from, to)
    }

    /** [from, to) 와 겹친 비율만큼의 시청시간 합 */
    fun watchMsBetween(sessions: List<SessionRecord>, from: Long, to: Long): Long = sessions.sumOf { shareOf(it, from, to) }

    internal fun restrictedIntervals(cutoffValue: Int, date: LocalDate, zone: ZoneId): List<Pair<Long, Long>> {
        fun at(minuteOfDay: Int) =
            date.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(zone).toInstant().toEpochMilli()

        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val nextDayStart = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val release = at(RELEASE_HOUR * 60)
        return if (cutoffValue >= MINUTES_PER_DAY) {
            listOf(at(cutoffValue - MINUTES_PER_DAY) to release)
        } else {
            listOf(dayStart to release, at(cutoffValue) to nextDayStart)
        }
    }

    private fun shareOf(s: SessionRecord, from: Long, to: Long): Long {
        val end = maxOf(s.endedAtMs, s.startedAtMs)
        val span = end - s.startedAtMs
        if (span == 0L) return if (s.startedAtMs in from until to) s.estimatedWatchMs else 0L
        val overlap = minOf(end, to) - maxOf(s.startedAtMs, from)
        return if (overlap <= 0L) 0L else s.estimatedWatchMs * overlap / span
    }
}

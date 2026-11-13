package com.example.shortsdetector.data.rewards

import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.goal.GoalPeriod
import com.example.shortsdetector.data.goal.GoalStatus
import com.example.shortsdetector.data.goal.TimeWindowUsage
import com.example.shortsdetector.data.stats.UsageStatsCalculator
import com.example.shortsdetector.data.usage.DailyUsageCalculator
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.UsageStore
import java.time.LocalDate
import java.time.ZoneId

/** 판정 결과. [newBadges] 는 이번에 실제로 DB 에 들어간 것만이다(다른 스레드가 먼저 넣었으면 빠진다). */
data class RewardUpdate(val closedDays: List<DayResult>, val newBadges: List<EarnedBadge>)

/**
 * 하루 마감과 배지 판정. 서비스(자정·개입 반응 뒤)와 화면(앱을 열 때) 양쪽에서 부른다. 저장 스레드에서 호출한다.
 *
 *  1. 아직 판정하지 않은 끝난 날을 몰아서 판정한다([trackingStart] 이후, 최대 [MAX_CATCH_UP_DAYS] 일). 앱이 꺼져 있던 날도 여기서 채운다.
 *  2. 끝난 날 행의 합 + 오늘 지금까지(멈춤 횟수, 즉시 확정되는 목표 달성)로 배지를 판정한다.
 *
 * 실제 판정에는 시연(is_demo)·시드(is_seed) 기록을 쓰지 않는다. 시드 배지는 [com.example.shortsdetector.data.usage.DemoSeedData] 가 따로 넣는다.
 *
 * 목표 변경 이력은 저장하지 않으므로 지난 날도 지금 목표로 판정한다. 몰아서 판정할 때 그사이 목표를 바꿨다면 실제와 다를 수 있다.
 */
class RewardsUpdater(
    private val records: UsageStore,
    private val rewards: RewardStore,
    private val zone: ZoneId
) {
    /**
     * @param maxLevel 지금 최대 개입 단계(NO_MAX_LEVEL 목표)
     * @param baselineMs 아낀 시간 기준값(첫 7일 평균 또는 설문). 없으면 아낀 시간은 0 으로 센다.
     * @param trackingStart 판정을 시작한 날. 이전 날은 판정하지 않는다(설치·업데이트 전 날을 "안 본 날"로 세지 않기 위함).
     */
    fun run(nowMs: Long, goals: List<Goal>, maxLevel: Int, baselineMs: Long?, trackingStart: LocalDate): RewardUpdate =
        synchronized(LOCK) {
            val today = DailyUsageCalculator.dateOf(nowMs, zone)
            val closed = closeDays(today, nowMs, goals, maxLevel, baselineMs, trackingStart)
            val realRows = rewards.dayResults().filterNot { it.isSeed }
            val totals = BadgeTotals.fromDays(realRows) +
                if (today.isBefore(trackingStart)) BadgeTotals() else todayTotals(today, nowMs, goals, maxLevel, trackingStart)
            val earned = rewards.badges().filterNot { it.isSeed }.map { it.id to it.tier }.toSet()
            val added = BadgeEngine.newlyEarned(totals, earned, nowMs).filter(rewards::insertBadge)
            RewardUpdate(closed, added)
        }

    private fun closeDays(
        today: LocalDate,
        nowMs: Long,
        goals: List<Goal>,
        maxLevel: Int,
        baselineMs: Long?,
        trackingStart: LocalDate
    ): List<DayResult> {
        val yesterday = today.minusDays(1)
        val last = rewards.dayResults().filterNot { it.isSeed }.maxOfOrNull { it.date }
        var date = maxOf(last?.plusDays(1) ?: trackingStart, trackingStart, yesterday.minusDays(MAX_CATCH_UP_DAYS - 1))
        val out = ArrayList<DayResult>()
        while (!date.isAfter(yesterday)) {
            out += closeDay(date, nowMs, goals, maxLevel, baselineMs, trackingStart).also(rewards::upsertDayResult)
            date = date.plusDays(1)
        }
        return out
    }

    /** 끝난 하루 하나. 주간 목표·쇼츠 안 본 날을 위해 지난주 월요일부터 읽는다. */
    internal fun closeDay(
        date: LocalDate,
        nowMs: Long,
        goals: List<Goal>,
        maxLevel: Int,
        baselineMs: Long?,
        trackingStart: LocalDate
    ): DayResult {
        val (sessions, interventions) = realRecords(UsageStatsCalculator.weekStart(date).minusWeeks(1), date)
        val (stats, m) = UsageStatsCalculator.closedDay(sessions, interventions, goals, zone, date, nowMs, maxLevel, trackingStart)
        val daily = stats.goals.filter { it.goal.type.period == GoalPeriod.DAILY }
        val weekly = stats.goals.filter { it.goal.type.period == GoalPeriod.WEEKLY }
        val sunday = date.dayOfWeek == java.time.DayOfWeek.SUNDAY
        return DayResult(
            date = date,
            dailyGoals = daily.size,
            dailyAchieved = daily.count { it.status == GoalStatus.ACHIEVED },
            weeklyGoals = if (sunday) weekly.count { it.status != GoalStatus.UNAVAILABLE } else 0,
            weeklyAchieved = if (sunday) weekly.count { it.status == GoalStatus.ACHIEVED } else 0,
            watchMs = m.watchMs,
            selfStops = m.selfStopCount,
            lv1Stops = m.lv1StopCount,
            noReturnStops = m.noReturnStopCount,
            nightClean = TimeWindowUsage.restrictedWatchMs(sessions, DayResult.NIGHT_CUTOFF_VALUE, date, zone) == 0L,
            shortsFree = m.watchMs == 0L,
            savedMs = baselineMs?.let { (it - m.watchMs).coerceAtLeast(0L) } ?: 0L
        )
    }

    /** 오늘 지금까지: 멈춤 횟수와 이미 확정된 목표 달성. 하루가 끝나야 아는 값(밤·안 본 날·아낀 시간)은 넣지 않는다. */
    private fun todayTotals(today: LocalDate, nowMs: Long, goals: List<Goal>, maxLevel: Int, trackingStart: LocalDate): BadgeTotals {
        val (sessions, interventions) = realRecords(UsageStatsCalculator.weekStart(today).minusWeeks(1), today)
        val m = UsageStatsCalculator.dayMetrics(sessions, interventions, goals, zone, today, nowMs, maxLevel)
        val stats = UsageStatsCalculator.today(sessions, interventions, goals, zone, nowMs, maxLevel = maxLevel, countFrom = trackingStart)
        return BadgeTotals(
            stops = m.selfStopCount,
            goalsAchieved = stats.goals.count { it.status == GoalStatus.ACHIEVED },
            lv1Stops = m.lv1StopCount,
            noReturnStops = m.noReturnStopCount
        )
    }

    private fun realRecords(from: LocalDate, toInclusive: LocalDate): Pair<List<SessionRecord>, List<InterventionRecord>> {
        val fromMs = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val toMs = toInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return records.sessionsOverlapping(fromMs, toMs).filterNot { it.isDemo || it.isSeed } to
            records.interventionsBetween(fromMs, toMs).filterNot { it.isDemo || it.isSeed }
    }

    companion object {
        /** 오래 꺼져 있었을 때 몰아서 판정하는 최대 일수. 보관 기간보다 짧게 둔다. */
        const val MAX_CATCH_UP_DAYS = 31L

        /** 서비스와 화면이 같은 프로세스에서 동시에 판정하지 않게 한다. */
        private val LOCK = Any()
    }
}

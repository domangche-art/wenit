package com.example.shortsdetector.data.stats

import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.profile.UserProfile
import com.example.shortsdetector.data.rewards.DayResult
import com.example.shortsdetector.data.usage.AllowanceUseRecord
import com.example.shortsdetector.data.rewards.StreakCalculator
import java.time.LocalDate
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.PolicyChangeRecord
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.intervention.InterventionPolicy
import java.time.ZoneId

/**
 * 나중에 AI 입력으로 직렬화할 묶음. 집계값·설문 응답·목표·정책만 담는다.
 *
 * 세션/개입 원본 기록, 패키지 이름, 화면 원문·노드·영상 정보는 넣지 않는다. 시연 모드 기록도 뺀다.
 * 새 필드를 추가하면 AgentInputPrivacyTest 의 허용 타입 검사를 통과해야 한다.
 * 프로필은 설문 미완료면 null 이다. 외부로 보내기 전에 명세 7장의 고지·동의가 필요하다.
 */
data class AgentInput(
    val generatedAtMs: Long,
    val profile: UserProfile?,
    val goals: List<Goal>,
    val policy: InterventionPolicy,
    val today: TodayStats,
    val week: WeekStats,
    val recent: RecentUsageStats,
    /** 새것부터, 최대 [AgentInputBuilder.RECENT_CHANGES] 건 */
    val recentPolicyChanges: List<PolicyChangeRecord>,
    /** 입력에 발표용 시드가 섞였다. 앱 안에서만 쓰고 외부로 보내지 않는다. */
    val usedSeedData: Boolean = false,
    /** 최근 7일 목표 달성률과 연속 달성(13단계). 끝난 날 판정 행에서 센 집계값이다. */
    val goalAchievement: GoalAchievementSummary = GoalAchievementSummary.EMPTY,
    /** 하루 허용 시간(14단계) 집계값. 사용 기록 원본은 담지 않는다. */
    val allowance: AllowanceSummary = AllowanceSummary.EMPTY
)

/**
 * @param dailyMinutes 사용자가 정한 하루 허용 시간(0 = 끔). 에이전트가 바꿀 수 없는 값이다
 * @param averageUsedMs 최근 7일(어제까지) 하루 평균 사용 시간(쓰지 않은 날은 0 으로 평균)
 * @param usedUpDays 그 7일 중 허용 시간을 다 쓴 날 수
 */
data class AllowanceSummary(val dailyMinutes: Int, val averageUsedMs: Long, val usedUpDays: Int) {
    companion object {
        val EMPTY = AllowanceSummary(0, 0L, 0)
        const val DAYS = 7L

        /** 시연 모드 기록은 뺀다. 시드는 다른 집계처럼 실제 기록으로 읽는다. */
        fun of(dailyMinutes: Int, uses: List<AllowanceUseRecord>, zone: ZoneId, nowMs: Long): AllowanceSummary {
            val today = com.example.shortsdetector.data.usage.DailyUsageCalculator.dateOf(nowMs, zone)
            val from = today.minusDays(DAYS)
            val recent = uses.filterNot { it.isDemo }.filter {
                val d = com.example.shortsdetector.data.usage.DailyUsageCalculator.dateOf(it.startedAtMs, zone)
                !d.isBefore(from) && d.isBefore(today)
            }
            val usedUp = recent.filter { it.exhausted }
                .map { com.example.shortsdetector.data.usage.DailyUsageCalculator.dateOf(it.startedAtMs, zone) }.toSet().size
            return AllowanceSummary(dailyMinutes, recent.sumOf { it.usedMs } / DAYS, usedUp)
        }
    }
}

/**
 * @param judgedDays 최근 7일(어제까지) 중 하루 목표가 있던 날
 * @param achievedDays 그중 하루 목표를 모두 지킨 날
 * @param achievementRate achievedDays / judgedDays. 판정한 날이 없으면 null.
 */
data class GoalAchievementSummary(
    val judgedDays: Int,
    val achievedDays: Int,
    val achievementRate: Double?,
    val currentStreak: Int,
    val bestStreak: Int
) {
    companion object {
        val EMPTY = GoalAchievementSummary(0, 0, null, 0, 0)
        const val DAYS = 7L

        /** @param rows 화면과 같은 행(시드가 있으면 시드 행 우선, [StreakCalculator.mergedForDisplay]) */
        fun of(rows: List<DayResult>, today: LocalDate): GoalAchievementSummary {
            val recent = rows.filter { !it.date.isBefore(today.minusDays(DAYS)) && it.date.isBefore(today) && it.achievedDay != null }
            val achieved = recent.count { it.achievedDay == true }
            val streak = StreakCalculator.of(rows)
            return GoalAchievementSummary(
                recent.size, achieved, if (recent.isEmpty()) null else achieved.toDouble() / recent.size,
                streak.current, streak.best
            )
        }
    }
}

object AgentInputBuilder {

    const val RECENT_CHANGES = 3

    /** [sessions], [interventions] 는 [UsageStatsCalculator.dataWindow] 범위의 기록. */
    fun build(
        profile: UserProfile?,
        goals: List<Goal>,
        policy: InterventionPolicy,
        sessions: List<SessionRecord>,
        interventions: List<InterventionRecord>,
        recentPolicyChanges: List<PolicyChangeRecord>,
        zone: ZoneId,
        nowMs: Long,
        dayResults: List<DayResult> = emptyList(),
        allowanceMinutes: Int = 0,
        allowanceUses: List<AllowanceUseRecord> = emptyList()
    ) = AgentInput(
        generatedAtMs = nowMs,
        profile = profile,
        goals = goals,
        policy = policy,
        today = UsageStatsCalculator.today(sessions, interventions, goals, zone, nowMs, maxLevel = policy.maxLevel),
        week = UsageStatsCalculator.week(sessions, interventions, zone, nowMs),
        recent = UsageStatsCalculator.recent(sessions, interventions, zone, nowMs),
        recentPolicyChanges = recentPolicyChanges.filterNot { it.isDemo }.take(RECENT_CHANGES),
        usedSeedData = sessions.any { it.isSeed } || interventions.any { it.isSeed } || dayResults.any { it.isSeed } ||
            allowanceUses.any { it.isSeed },
        allowance = AllowanceSummary.of(allowanceMinutes, allowanceUses, zone, nowMs),
        goalAchievement = GoalAchievementSummary.of(
            StreakCalculator.mergedForDisplay(dayResults),
            com.example.shortsdetector.data.usage.DailyUsageCalculator.dateOf(nowMs, zone)
        )
    )
}

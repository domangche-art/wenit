package com.example.shortsdetector.data.usage

import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.goal.GoalRules
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.rewards.BadgeEngine
import com.example.shortsdetector.data.rewards.BadgeTotals
import com.example.shortsdetector.data.rewards.DayResult
import com.example.shortsdetector.data.rewards.EarnedBadge
import com.example.shortsdetector.data.rewards.InMemoryRewardStore
import com.example.shortsdetector.data.rewards.RewardsUpdater
import java.time.LocalDate
import java.time.ZoneId

/**
 * 발표용 2주치 사용 기록. 실제 기록처럼 통계·에이전트가 읽도록 is_demo=0 이고 is_seed=1 로 구분한다.
 * 값은 고정 표에서 만들어 같은 날에 넣으면 같은 결과가 나온다. 화면 내용은 들어 있지 않다.
 *
 * 8~14일 전은 두 시나리오가 같은 평범한 패턴이고, 1~7일 전이 시나리오별 패턴이다. 에이전트는 최근 7일만 보고,
 * 앞 7일은 지난주 비교와 아낀 시간 기준값(첫 7일 평균)에 쓰인다.
 * 통계의 "이번 주"는 월요일에 시작하므로 월요일에 시연해도 비교가 나오도록 오늘 몫도 넣는다. 오늘 행에 개입은 없다.
 */
object DemoSeedData {

    enum class Scenario { LATE_NIGHT_SKIPPER, EASY_STOPPER }

    /**
     * @param dayResults 예시 연속 기록(1~14일 전). 시드 기록을 [seedGoals] 로 실제와 같은 규칙으로 판정해 만든다.
     * @param badges 예시 배지. 위 행을 날짜순으로 쌓아 기준을 넘은 날 저녁에 얻은 것으로 둔다. 축하·알림·새 배지 표시는 하지 않는다.
     */
    data class Batch(
        val sessions: List<SessionRecord>,
        val interventions: List<InterventionRecord>,
        val dayResults: List<DayResult> = emptyList(),
        val badges: List<EarnedBadge> = emptyList(),
        /** 예시 허용 시간 사용 기록(14단계). A 는 매일 다 쓰고, B 는 거의 쓰지 않는다. */
        val allowanceUses: List<AllowanceUseRecord> = emptyList()
    )

    /** 예시 허용 시간: 하루 10분 기준으로 만든 기록. 사용자의 허용 시간 설정은 바꾸지 않는다. */
    private const val SEED_ALLOWANCE_MS = 10 * 60_000L

    /** (며칠 전, 시작 시, 쓴 분, 다 씀) */
    private fun allowancePlan(scenario: Scenario): List<Triple<Long, Int, Long>> = when (scenario) {
        Scenario.LATE_NIGHT_SKIPPER -> (1L..7L).map { Triple(it, 23, 10L) }
        Scenario.EASY_STOPPER -> listOf(Triple(6L, 19, 2L), Triple(2L, 20, 3L))
    }

    private fun seedAllowance(scenario: Scenario, today: LocalDate, zone: ZoneId, nowMs: Long): List<AllowanceUseRecord> =
        allowancePlan(scenario).mapIndexedNotNull { i, (ago, hour, minutes) ->
            val start = today.minusDays(ago).atTime(hour, 20).atZone(zone).toInstant().toEpochMilli()
            val used = minutes * MS_PER_MINUTE
            if (start + used >= nowMs) return@mapIndexedNotNull null
            AllowanceUseRecord(
                id = "seed-allowance-${scenario.name}-$i", startedAtMs = start, endedAtMs = start + used, usedMs = used,
                budgetMs = SEED_ALLOWANCE_MS, exhausted = used >= SEED_ALLOWANCE_MS, level = 1, isSeed = true
            )
        }

    /** 예시 연속 기록을 만들 때 쓰는 목표. 사용자의 실제 목표는 바꾸지 않는다. */
    fun seedGoals(scenario: Scenario): List<Goal> = when (scenario) {
        Scenario.LATE_NIGHT_SKIPPER -> listOf(
            GoalRules.userGoal(GoalType.DAILY_TOTAL, 40),
            GoalRules.userGoal(GoalType.TIME_WINDOW, GoalRules.timeWindowValue(23, 0))
        )
        Scenario.EASY_STOPPER -> listOf(
            GoalRules.userGoal(GoalType.DAILY_TOTAL, 20),
            GoalRules.userGoal(GoalType.SELF_STOPS, 1)
        )
    }

    /** 예시 아낀 시간 기준값(하루 45분) */
    private const val SEED_BASELINE_MS = 45 * 60_000L
    private const val SEED_DAYS = 14L
    private const val BADGE_HOUR = 21

    private const val PACKAGE = "com.google.android.youtube"
    private const val THRESHOLD_MS = 180_000L
    private const val MS_PER_MINUTE = 60_000L

    /**
     * 개입 하나의 반응.
     * @param backMinutes 멈춘 뒤 쇼츠로 돌아오기까지 걸린 분
     * @param appMinutes 멈춘 뒤 쇼츠 없이 앱만 열기까지 걸린 분
     */
    private class Reaction(
        val level: Int,
        val outcome: InterventionOutcome,
        val reason: InterventionUserReason? = null,
        val backMinutes: Long? = null,
        val appMinutes: Long? = null
    ) {
        val returnStatus: InterventionReturn?
            get() = when {
                outcome != InterventionOutcome.STOPPED -> null
                backMinutes != null -> InterventionReturn.SHORTS
                appMinutes != null -> InterventionReturn.APP_ONLY
                else -> InterventionReturn.NONE
            }
    }

    private class Row(
        val daysAgo: Long,
        val hour: Int,
        val minute: Int,
        val watchMinutes: Long,
        val reactions: List<Reaction>
    )

    private val BORED = InterventionUserReason.BORED
    private val TIRED = InterventionUserReason.TIRED
    private val PROCRASTINATING = InterventionUserReason.PROCRASTINATING

    private fun c(level: Int, reason: InterventionUserReason? = null) = Reaction(level, InterventionOutcome.CONTINUED, reason)
    private fun i(level: Int) = Reaction(level, InterventionOutcome.IGNORED)
    private fun s(level: Int, reason: InterventionUserReason? = null, back: Long? = null, app: Long? = null) =
        Reaction(level, InterventionOutcome.STOPPED, reason, back, app)

    /** 지난주: 저녁에 하루 30분 안팎 */
    private val ordinaryWeek = listOf(
        Row(14, 19, 30, 22, listOf(s(1, back = 120))),
        Row(13, 20, 10, 28, listOf(c(1))),
        Row(12, 12, 40, 9, listOf()),
        Row(12, 21, 0, 18, listOf(s(1, app = 200))),
        Row(11, 19, 50, 30, listOf(c(1))),
        Row(10, 20, 30, 25, listOf(i(1))),
        Row(9, 14, 20, 12, listOf()),
        Row(9, 21, 10, 22, listOf(s(1, back = 90))),
        Row(8, 20, 0, 27, listOf(c(1)))
    )

    /** 밤에 길게 보고 알림을 자주 넘긴다. 멈춰도 금방 돌아오고, 이유는 심심해서·피곤해서가 많다 */
    private val lateNight = listOf(
        Row(7, 23, 10, 38, listOf(i(1), c(1), s(1, back = 6))),
        Row(6, 23, 40, 52, listOf(c(1), c(2, BORED), s(2, BORED, back = 4))),
        Row(6, 21, 30, 14, listOf(s(1, back = 8))),
        Row(5, 23, 5, 44, listOf(i(2), c(2, PROCRASTINATING), s(2, TIRED, back = 7))),
        Row(4, 22, 50, 61, listOf(i(1), c(1), c(2, BORED))),
        Row(3, 23, 30, 47, listOf(c(1), i(2), s(2, BORED, back = 12))),
        Row(2, 23, 15, 55, listOf(i(1), c(2, TIRED), s(2, TIRED, back = 5))),
        Row(2, 20, 40, 11, listOf(c(1))),
        Row(1, 23, 25, 49, listOf(c(1), s(2, TIRED, back = 25))),
        Row(0, 0, 30, 55, listOf())
    )

    /** 알림만 받아도 잘 멈추고, 멈춘 뒤 잘 돌아오지 않는다 */
    private val easyStopper = listOf(
        Row(7, 12, 20, 6, listOf(s(1))),
        Row(7, 19, 45, 8, listOf(s(1))),
        Row(6, 18, 30, 5, listOf(s(1, app = 240))),
        Row(5, 12, 40, 7, listOf(s(1))),
        Row(4, 20, 10, 9, listOf(c(1), s(1))),
        Row(3, 13, 5, 4, listOf()),
        Row(3, 21, 0, 6, listOf(s(1, back = 420))),
        Row(2, 19, 20, 7, listOf(s(1))),
        Row(1, 12, 50, 5, listOf(s(1, app = 300))),
        Row(0, 7, 30, 4, listOf())
    )

    fun build(scenario: Scenario, today: LocalDate, zone: ZoneId, nowMs: Long): Batch {
        val rows = ordinaryWeek + if (scenario == Scenario.LATE_NIGHT_SKIPPER) lateNight else easyStopper
        val sessions = ArrayList<SessionRecord>()
        val interventions = ArrayList<InterventionRecord>()
        rows.forEachIndexed { index, row ->
            val id = "seed-${scenario.name}-$index"
            val start = today.minusDays(row.daysAgo).atTime(row.hour, row.minute).atZone(zone).toInstant().toEpochMilli()
            val watch = row.watchMinutes * MS_PER_MINUTE
            if (start + watch >= nowMs) return@forEachIndexed      // 오늘 몫이 아직 오지 않은 시각이면 넣지 않는다
            sessions += SessionRecord(
                id, PACKAGE, "seed", start, minOf(start + watch, nowMs - 1), SessionStatus.CLOSED,
                watch, 0, 0, 0, "HIGH", EndReason, "ROOT_UNAVAILABLE", 0, isSeed = true
            )
            row.reactions.forEachIndexed { n, r ->
                val watched = THRESHOLD_MS * (n + 1)
                val stopped = r.outcome == InterventionOutcome.STOPPED
                interventions += InterventionRecord(
                    id = "$id-$n",
                    sessionId = id,
                    occurredAtMs = start + watched,
                    method = InterventionMethod.POPUP,
                    watchMs = watched,
                    thresholdMs = THRESHOLD_MS,
                    userAction = InterventionAction.OPEN_APP.takeIf { stopped },
                    actionAtMs = (start + watched).takeIf { stopped },
                    level = r.level,
                    userReason = r.reason,
                    outcome = r.outcome,
                    isSeed = true,
                    returnStatus = r.returnStatus,
                    shortsReturnMs = r.backMinutes?.times(MS_PER_MINUTE),
                    appReturnMs = r.appMinutes?.times(MS_PER_MINUTE)
                )
            }
        }
        val days = seedDays(scenario, sessions, interventions, today, zone, nowMs)
        return Batch(
            sessions, interventions, days, withShowcaseTiers(scenario, seedBadges(days, zone), today, zone),
            seedAllowance(scenario, today, zone, nowMs)
        )
    }

    /**
     * 진열장 시연용으로 덧붙이는 등급(배지 id → 가장 높은 등급 0~4). 시드 14일치로는 다이아·마스터 기준에 닿지 않아
     * 모든 등급 모양이 보이도록 예시 등급을 넣는다. 한 시나리오에서 브론즈~마스터가 한 번씩 보인다.
     * 시드 기록에서 계산한 값과 다를 수 있다(진열장 진행률은 등급 기준값 이상으로 보인다). 실제 판정에는 쓰지 않는다.
     */
    internal fun showcaseTiers(scenario: Scenario): Map<String, Int> = when (scenario) {
        Scenario.LATE_NIGHT_SKIPPER -> mapOf("time_rich" to 4, "steady" to 3, "self_stop" to 2, "night_guard" to 1, "first_signal" to 0)
        Scenario.EASY_STOPPER -> mapOf("no_return" to 4, "time_rich" to 3, "shorts_free" to 2, "steady" to 1, "first_signal" to 0)
    }

    /**
     * 계산된 시드 배지에 [showcaseTiers] 의 빠진 등급을 더한다. 얻은 날은 같은 배지의 이미 얻은 가장 늦은 날(없으면 14일 전)
     * 다음 날부터 등급 순서대로 하루씩, 어제를 넘지 않는다(높은 등급이 낮은 등급보다 앞서지 않게).
     */
    private fun withShowcaseTiers(scenario: Scenario, computed: List<EarnedBadge>, today: LocalDate, zone: ZoneId): List<EarnedBadge> {
        val have = computed.map { it.id to it.tier }.toSet()
        val yesterday = today.minusDays(1)
        val extra = showcaseTiers(scenario).flatMap { (id, top) ->
            val after = computed.filter { it.id == id }.maxOfOrNull { it.earnedAtMs }
                ?.let { DailyUsageCalculator.dateOf(it, zone) } ?: today.minusDays(SEED_DAYS + 1)
            (0..top).filter { (id to it) !in have }.mapIndexed { k, tier ->
                val day = minOf(after.plusDays(k + 1L), yesterday)
                EarnedBadge(id, tier, day.atTime(BADGE_HOUR, tier).atZone(zone).toInstant().toEpochMilli(), isSeed = true, seen = true, celebrated = true, notified = true)
            }
        }
        return computed + extra
    }

    /** 시드 기록을 실제 기록처럼 넣은 메모리 저장소에서 실제와 같은 하루 판정을 돌리고, 결과에 시드 표시를 붙인다. */
    private fun seedDays(
        scenario: Scenario,
        sessions: List<SessionRecord>,
        interventions: List<InterventionRecord>,
        today: LocalDate,
        zone: ZoneId,
        nowMs: Long
    ): List<DayResult> {
        val store = InMemoryUsageStore()
        sessions.forEach { store.upsertSession(it.copy(isSeed = false)) }
        interventions.forEach { store.insertIntervention(it.copy(isSeed = false)) }
        val updater = RewardsUpdater(store, InMemoryRewardStore(), zone)
        val first = today.minusDays(SEED_DAYS)
        return (SEED_DAYS downTo 1L).map { ago ->
            updater.closeDay(today.minusDays(ago), nowMs, seedGoals(scenario), MAX_LEVEL, SEED_BASELINE_MS, first)
                .copy(isSeed = true)
        }
    }

    /** 날짜순으로 쌓으며 등급 기준을 처음 넘은 날 [BADGE_HOUR] 시에 얻은 것으로 둔다. */
    private fun seedBadges(days: List<DayResult>, zone: ZoneId): List<EarnedBadge> {
        val out = ArrayList<EarnedBadge>()
        days.indices.forEach { i ->
            val at = days[i].date.atTime(BADGE_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
            val earned = out.map { it.id to it.tier }.toSet()
            out += BadgeEngine.newlyEarned(BadgeTotals.fromDays(days.take(i + 1)), earned, at)
                .map { it.copy(isSeed = true, seen = true, celebrated = true, notified = true) }
        }
        return out
    }

    private const val MAX_LEVEL = 3

    private const val EndReason = "EXIT_CONFIRMED"
}

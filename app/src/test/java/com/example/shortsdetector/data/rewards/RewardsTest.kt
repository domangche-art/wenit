package com.example.shortsdetector.data.rewards

import com.example.shortsdetector.ai.AgentInputJson
import com.example.shortsdetector.data.goal.GoalRules
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.stats.AgentInputBuilder
import com.example.shortsdetector.data.stats.GoalAchievementSummary
import com.example.shortsdetector.data.usage.DemoSeedData
import com.example.shortsdetector.data.usage.InMemoryUsageStore
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.InterventionReturn
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.SessionStatus
import com.example.shortsdetector.data.usage.UsageSchema
import com.example.shortsdetector.intervention.InterventionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 13단계 [3]~[5]: 연속 달성, 하루 마감(몰아서 판정), 배지 획득·등급 상승, 시드 분리, 삭제, 에이전트 집계. */
class RewardsTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val start = LocalDate.of(2026, 11, 2)   // 월요일
    private val minute = 60_000L

    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun session(start: Long, watchMinutes: Long, demo: Boolean = false, seed: Boolean = false) = SessionRecord(
        "s$start", "pkg", "yt-2", start, start + watchMinutes * minute, SessionStatus.CLOSED, watchMinutes * minute,
        0, 0, 0, "HIGH", null, null, 0, isDemo = demo, isSeed = seed
    )

    private fun stop(atMs: Long, level: Int = 1, demo: Boolean = false, seed: Boolean = false) = InterventionRecord(
        "i$atMs$level", null, atMs, InterventionMethod.POPUP, 0, 0, level = level, outcome = InterventionOutcome.STOPPED,
        actionAtMs = atMs, returnStatus = InterventionReturn.NONE, isDemo = demo, isSeed = seed
    )

    private fun day(d: LocalDate, goals: Int, achieved: Int, seed: Boolean = false) = DayResult(d, goals, achieved, isSeed = seed)

    // --- 연속 달성 ---

    @Test
    fun `streak counts days with every daily goal kept and skips days without goals`() {
        val rows = listOf(
            day(start, 2, 2), day(start.plusDays(1), 2, 2), day(start.plusDays(2), 0, 0), // 목표 없는 날: 건너뜀
            day(start.plusDays(3), 1, 1), day(start.plusDays(4), 2, 1),                    // 하나 놓침: 끊김
            day(start.plusDays(5), 2, 2), day(start.plusDays(6), 0, 0)
        )
        assertEquals(Streak(current = 1, best = 3), StreakCalculator.of(rows))
        assertNull(day(start, 0, 0).achievedDay)
        assertEquals(Streak(0, 0), StreakCalculator.of(emptyList()))
        // 빠진 날(행 없음)도 끊지 않는다
        assertEquals(2, StreakCalculator.of(listOf(day(start, 1, 1), day(start.plusDays(3), 1, 1))).current)
    }

    @Test
    fun `display rows prefer seed rows on the same date but judging keeps them apart`() {
        val rows = listOf(day(start, 1, 0), day(start, 1, 1, seed = true), day(start.plusDays(1), 1, 1))
        val shown = StreakCalculator.mergedForDisplay(rows)
        assertEquals(2, shown.size)
        assertTrue(shown.first { it.date == start }.isSeed)
        // 시드가 없으면 실제 행 그대로
        val real = rows.filterNot { it.isSeed }
        assertEquals(real, StreakCalculator.mergedForDisplay(real))
        // 실제 판정은 실제 행만 본다: 월요일을 놓쳐 화요일 하루만 연속
        assertEquals(Streak(1, 1), StreakCalculator.of(real))
    }

    // --- 하루 마감 ---

    private class Env(val zone: ZoneId) {
        val records = InMemoryUsageStore()
        val rewards = InMemoryRewardStore()
        val updater = RewardsUpdater(records, rewards, zone)
    }

    @Test
    fun `days missed while the app was off are judged together on the next run`() {
        val e = Env(zone)
        val goals = listOf(GoalRules.userGoal(GoalType.DAILY_TOTAL, 30))
        e.records.upsertSession(session(at(start, 20), 20))                 // 월: 지킴
        e.records.upsertSession(session(at(start.plusDays(1), 20), 40))     // 화: 넘김
        e.records.upsertSession(session(at(start.plusDays(2), 20), 10))     // 수: 지킴
        // 목요일 아침에 처음 연다 → 월·화·수 세 날을 판정
        val first = e.updater.run(at(start.plusDays(3), 9), goals, 3, null, start)
        assertEquals(listOf(start, start.plusDays(1), start.plusDays(2)), first.closedDays.map { it.date })
        assertEquals(listOf(true, false, true), first.closedDays.map { it.achievedDay })
        // 같은 날 다시 열어도 다시 판정하지 않는다
        assertTrue(e.updater.run(at(start.plusDays(3), 21), goals, 3, null, start).closedDays.isEmpty())
        // 판정 시작일 이전 날은 판정하지 않는다
        val e2 = Env(zone)
        assertEquals(listOf(start.plusDays(2)), e2.updater.run(at(start.plusDays(3), 9), goals, 3, null, start.plusDays(2)).closedDays.map { it.date })
    }

    @Test
    fun `day without goals is recorded but neither counts nor breaks the streak`() {
        val e = Env(zone)
        val r = e.updater.run(at(start.plusDays(1), 9), emptyList(), 3, null, start)
        assertEquals(0, r.closedDays.single().dailyGoals)
        assertNull(r.closedDays.single().achievedDay)
    }

    @Test
    fun `closing a day records counts night and shorts free and saved time`() {
        val e = Env(zone)
        e.records.upsertSession(session(at(start, 23, 30), 20))     // 밤 시청
        e.records.insertIntervention(stop(at(start, 23, 40), level = 1))
        val r = e.updater.run(at(start.plusDays(2), 9), emptyList(), 3, 60 * minute, start).closedDays
        val mon = r.first { it.date == start }
        assertFalse(mon.nightClean)
        assertFalse(mon.shortsFree)
        assertEquals(1, mon.selfStops)
        assertEquals(1, mon.lv1Stops)
        assertEquals(1, mon.noReturnStops)
        assertEquals(40 * minute, mon.savedMs)
        val tue = r.first { it.date == start.plusDays(1) }
        assertTrue(tue.nightClean && tue.shortsFree)
        assertEquals(60 * minute, tue.savedMs)
    }

    @Test
    fun `weekly goals are judged on sunday rows only`() {
        val e = Env(zone)
        val goals = listOf(GoalRules.userGoal(GoalType.WEEKLY_TOTAL, 100), GoalRules.userGoal(GoalType.DAILY_TOTAL, 60))
        e.records.upsertSession(session(at(start, 20), 30))
        val rows = e.updater.run(at(start.plusDays(7), 9), goals, 3, null, start).closedDays
        assertEquals(7, rows.size)
        assertTrue(rows.dropLast(1).all { it.weeklyGoals == 0 })
        assertEquals(1, rows.last().weeklyGoals)
        assertEquals(1, rows.last().weeklyAchieved)
        assertTrue(rows.all { it.dailyGoals == 1 })            // 주간 목표는 연속 달성에 넣지 않는다
    }

    // --- 배지 ---

    @Test
    fun `first stop badge comes right away from today's records`() {
        val e = Env(zone)
        val now = at(start, 21)
        e.records.insertIntervention(stop(at(start, 20)))
        val added = e.updater.run(now, emptyList(), 3, null, start).newBadges
        assertTrue(added.any { it.id == "first_stop" && it.tier == 0 })
        // 다시 판정해도 같은 배지를 또 주지 않는다
        assertTrue(e.updater.run(now + minute, emptyList(), 3, null, start).newBadges.none { it.id == "first_stop" })
    }

    @Test
    fun `tiers go up as totals grow and several tiers can arrive at once`() {
        val def = BadgeCatalog.byId("steady")!!
        assertEquals(-1, def.reachedIndex(2))
        assertEquals(1, def.reachedIndex(7))
        val first = BadgeEngine.newlyEarned(BadgeTotals(bestStreak = 7), emptySet(), 1L).filter { it.id == "steady" }
        assertEquals(listOf(0, 1), first.map { it.tier })
        val earned = first.map { it.id to it.tier }.toSet()
        assertTrue(BadgeEngine.newlyEarned(BadgeTotals(bestStreak = 13), earned, 2L).none { it.id == "steady" })
        assertEquals(listOf(2), BadgeEngine.newlyEarned(BadgeTotals(bestStreak = 14), earned, 3L).filter { it.id == "steady" }.map { it.tier })
        // 시간 부자: 시간 단위로 내림
        assertEquals(0, BadgeTotals(savedMs = 59 * minute).valueOf(BadgeMetric.SAVED_HOURS))
        assertEquals(1, BadgeTotals(savedMs = 60 * minute).valueOf(BadgeMetric.SAVED_HOURS))
    }

    @Test
    fun `streak badge from closed days and goals badge from today`() {
        val e = Env(zone)
        val goals = listOf(GoalRules.userGoal(GoalType.DAILY_TOTAL, 30), GoalRules.userGoal(GoalType.SELF_STOPS, 1))
        (0L..2L).forEach { e.records.insertIntervention(stop(at(start.plusDays(it), 12))) }
        val added = e.updater.run(at(start.plusDays(3), 9), goals, 3, null, start).newBadges.map { it.id to it.tier }
        assertTrue("steady" to 0 in added)          // 3일 연속
        assertTrue("first_goal" to 0 in added)
        assertTrue("shorts_free" to 0 in added)     // 시청 0 분인 날
        assertTrue("night_guard" to 0 in added)     // 밤에 안 본 날 3일
    }

    @Test
    fun `demo and seed records never give real badges`() {
        val e = Env(zone)
        e.records.insertIntervention(stop(at(start, 20), demo = true))
        e.records.insertIntervention(stop(at(start, 20, 30), seed = true))
        e.records.upsertSession(session(at(start, 10), 5, seed = true))
        val r = e.updater.run(at(start, 21), emptyList(), 3, null, start)
        assertTrue(r.newBadges.none { it.id == "first_stop" })
        // 시드 배지가 있어도 실제 배지를 막지 않는다
        e.rewards.insertBadge(EarnedBadge("first_stop", 0, 1L, isSeed = true))
        e.records.insertIntervention(stop(at(start, 22)))
        assertTrue(e.updater.run(at(start, 23), emptyList(), 3, null, start).newBadges.any { it.id == "first_stop" && !it.isSeed })
    }

    @Test
    fun `earned badges stay until records are deleted`() {
        val e = Env(zone)
        e.records.insertIntervention(stop(at(start, 20)))
        e.updater.run(at(start, 21), emptyList(), 3, null, start)
        e.updater.run(at(start.plusDays(1), 9), emptyList(), 3, null, start)   // 월요일 마감
        e.records.deleteBefore(at(start.plusDays(1), 0))           // 보관 기간 정리로 기록이 사라져도
        e.updater.run(at(start.plusDays(1), 10), emptyList(), 3, null, start)
        assertTrue(e.rewards.badges().any { it.id == "first_stop" })  // 배지는 남는다
        assertEquals(1, e.rewards.dayResults().first { it.date == start }.selfStops)
        e.rewards.deleteAll()                                        // 기록 전체 삭제
        assertTrue(e.rewards.badges().isEmpty() && e.rewards.dayResults().isEmpty())
    }

    @Test
    fun `badge rows are inserted once and marks only touch real badges`() {
        val s = InMemoryRewardStore()
        assertTrue(s.insertBadge(EarnedBadge("x", 0, 1L)))
        assertFalse(s.insertBadge(EarnedBadge("x", 0, 2L)))
        assertTrue(s.insertBadge(EarnedBadge("x", 0, 3L, isSeed = true)))
        s.markBadgeCelebrated("x", 0)
        assertEquals(listOf(true, false), s.badges().sortedBy { it.isSeed }.map { it.celebrated })
        s.markBadgesSeen()
        assertTrue(s.badges().all { it.seen })
    }

    @Test
    fun `badge catalog has unique ids ascending tiers and every metric`() {
        val ids = BadgeCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(9, ids.size)
        assertEquals(BadgeMetric.entries.toSet(), BadgeCatalog.ALL.map { it.metric }.toSet())
        assertTrue(BadgeCatalog.ALL.all { it.thresholds.size == 1 || it.thresholds.size == 5 })
        assertEquals("3일 연속으로 목표를 지켜요", BadgeCatalog.byId("steady")!!.conditionAt(0))
    }

    // --- [7] 5등급 ---

    @Test
    fun `five tiers with the requested thresholds and two untiered badges`() {
        assertEquals(listOf("BRONZE", "SILVER", "GOLD", "DIAMOND", "MASTER"), BadgeTier.entries.map { it.name })
        val expected = mapOf(
            "steady" to listOf(3, 7, 14, 30, 60),
            "time_rich" to listOf(1, 5, 10, 30, 100),
            "self_stop" to listOf(10, 30, 100, 300, 1000),
            "night_guard" to listOf(3, 7, 14, 30, 60),
            "first_signal" to listOf(5, 20, 50, 100, 200),
            "no_return" to listOf(5, 20, 50, 100, 200),
            "shorts_free" to listOf(1, 3, 7, 14, 30)
        )
        expected.forEach { (id, t) -> assertEquals(id, t, BadgeCatalog.byId(id)!!.thresholds) }
        assertEquals(setOf("first_stop", "first_goal"), BadgeCatalog.ALL.filterNot { it.tiered }.map { it.id }.toSet())
        // 마스터까지 한 번에, 다시 주지 않음
        val all = BadgeEngine.newlyEarned(BadgeTotals(stops = 1000), emptySet(), 1L)
        assertEquals(listOf(0, 1, 2, 3, 4), all.filter { it.id == "self_stop" }.map { it.tier })
        assertEquals(listOf(0), all.filter { it.id == "first_stop" }.map { it.tier })
        assertEquals(3, BadgeCatalog.byId("steady")!!.reachedIndex(59))
        assertEquals(4, BadgeCatalog.byId("steady")!!.reachedIndex(60))
    }

    @Test
    fun `art picks tier image then badge image then frame plus symbol then common`() {
        fun files(vararg n: String) = n.toSet()::contains
        val r = BadgeArtResolver
        assertEquals(BadgeArtSpec.Image("badge_time_rich_t3"), r.resolve("time_rich", 3, files("badge_time_rich_t3", "badge_time_rich", "badge_frame_t3", "badge_symbol_time_rich")))
        assertEquals(BadgeArtSpec.Image("badge_time_rich"), r.resolve("time_rich", 2, files("badge_time_rich_t3", "badge_time_rich")))
        assertEquals(BadgeArtSpec.Composite("badge_frame_t2", "badge_symbol_steady"), r.resolve("steady", 2, files("badge_frame_t2", "badge_symbol_steady")))
        // 틀이나 기호 하나만 있으면 합성하지 않는다
        assertEquals(BadgeArtSpec.Common("badge_common"), r.resolve("steady", 2, files("badge_frame_t2")))
        assertEquals(BadgeArtSpec.Common("badge_common"), r.resolve("steady", 2, files("badge_symbol_steady")))
        // 범위 밖 등급은 잘린다
        assertEquals(BadgeArtSpec.Image("badge_x_t4"), r.resolve("x", 9, files("badge_x_t4")))
    }

    @Test
    fun `art in the app resources gives time rich its own five tiers and others the common image`() {
        val dir = java.io.File("src/main/res/drawable-nodpi")
        val names = dir.listFiles()!!.map { it.nameWithoutExtension }.toSet()
        for (t in 0..4) assertEquals(BadgeArtSpec.Image("badge_time_rich_t$t"), BadgeArtResolver.resolve("time_rich", t, names::contains))
        for (def in BadgeCatalog.ALL.filter { it.id != "time_rich" }) {
            assertEquals(def.id, BadgeArtSpec.Common("badge_common"), BadgeArtResolver.resolve(def.id, 0, names::contains))
        }
    }

    @Test
    fun `each seed scenario shows every tier from bronze to master`() {
        val today = LocalDate.of(2026, 11, 13)
        for (scenario in DemoSeedData.Scenario.entries) {
            val b = DemoSeedData.build(scenario, today, zone, at(today, 20))
            val top = b.badges.groupBy { it.id }.mapValues { (_, v) -> v.maxOf { it.tier } }
            val tieredTops = top.filterKeys { BadgeCatalog.byId(it)!!.tiered }.values.toSet()
            assertEquals(scenario.name, setOf(0, 1, 2, 3, 4), tieredTops)
            // 등급은 빠짐없이 아래부터 채운다
            b.badges.groupBy { it.id }.forEach { (id, v) -> assertEquals(id, (0..v.maxOf { it.tier }).toList(), v.map { it.tier }.sorted()) }
            // 높은 등급이 낮은 등급보다 먼저 얻은 것으로 보이지 않고, 모두 오늘 전
            b.badges.groupBy { it.id }.forEach { (id, v) ->
                val byTier = v.sortedBy { it.tier }.map { it.earnedAtMs }
                assertEquals(id, byTier.sorted(), byTier)
            }
            assertTrue(b.badges.all { it.earnedAtMs < today.atStartOfDay(zone).toInstant().toEpochMilli() })
            assertTrue(b.badges.all { it.isSeed })
        }
    }

    @Test
    fun `progress shows top tier next threshold and merges seed badges`() {
        val badges = listOf(EarnedBadge("steady", 0, 10L), EarnedBadge("self_stop", 1, 20L, isSeed = true))
        val p = BadgeProgress.of(badges, BadgeTotals(bestStreak = 5, stops = 3))
        val steady = p.first { it.def.id == "steady" }
        assertEquals(0, steady.tierIndex)
        assertEquals(7, steady.nextThreshold)
        assertEquals(5, steady.value)
        assertTrue(steady.isNew)
        val stops = p.first { it.def.id == "self_stop" }
        assertEquals(1, stops.tierIndex)
        assertEquals(30, stops.value)                  // 시드 등급 기준값 이상으로 보여 준다
        assertTrue(stops.fromSeed && !stops.isNew)
        val locked = p.first { it.def.id == "first_signal" }
        assertFalse(locked.earned)
        assertEquals(5, locked.nextThreshold)
    }

    // --- 시드 ---

    @Test
    fun `seed scenarios carry example streaks and badges marked as seed`() {
        val today = LocalDate.of(2026, 11, 13)
        val now = at(today, 20)
        for (scenario in DemoSeedData.Scenario.entries) {
            val b = DemoSeedData.build(scenario, today, zone, now)
            assertEquals(14, b.dayResults.size)
            assertTrue(b.dayResults.all { it.isSeed })
            assertTrue(b.badges.isNotEmpty() && b.badges.all { it.isSeed && it.seen && it.celebrated })
            assertTrue(b.badges.all { it.earnedAtMs < now })
        }
        val a = DemoSeedData.build(DemoSeedData.Scenario.LATE_NIGHT_SKIPPER, today, zone, now)
        val easy = DemoSeedData.build(DemoSeedData.Scenario.EASY_STOPPER, today, zone, now)
        assertEquals(Streak(0, 7), StreakCalculator.of(a.dayResults))      // 지난주는 지켰고 이번 주 밤마다 놓침
        assertEquals(7, StreakCalculator.of(easy.dayResults).current)
        assertTrue(easy.badges.any { it.id == "night_guard" && it.tier == 2 })
    }

    @Test
    fun `deleting seed rewards leaves real rewards`() {
        val s = InMemoryRewardStore()
        s.upsertDayResult(day(start, 1, 1))
        s.upsertDayResult(day(start, 1, 0, seed = true))
        s.insertBadge(EarnedBadge("first_stop", 0, 1L))
        s.insertBadge(EarnedBadge("steady", 0, 1L, isSeed = true))
        s.deleteSeed()
        assertEquals(listOf(false), s.dayResults().map { it.isSeed })
        assertEquals(listOf("first_stop"), s.badges().map { it.id })
    }

    @Test
    fun `schema 8 adds the two tables without touching old ones`() {
        assertEquals(9, UsageSchema.VERSION)
        assertEquals(2, UsageSchema.MIGRATE_7_TO_8.size)
        assertTrue(UsageSchema.MIGRATE_7_TO_8.all { it.startsWith("CREATE TABLE") })
        assertTrue(UsageSchema.MIGRATE_7_TO_8[0].contains("day_results") && UsageSchema.MIGRATE_7_TO_8[0].contains("PRIMARY KEY (epoch_day, is_seed)"))
        assertTrue(UsageSchema.MIGRATE_7_TO_8[1].contains("badges") && UsageSchema.MIGRATE_7_TO_8[1].contains("PRIMARY KEY (badge_id, tier, is_seed)"))
    }

    // --- 에이전트 입력 ---

    @Test
    fun `agent input gets last 7 days achievement rate and streak as aggregates`() {
        val today = start.plusDays(9)
        val rows = (1L..9L).map { ago ->
            val d = today.minusDays(ago)
            when (ago) {
                2L -> day(d, 0, 0)            // 목표 없는 날: 분모에서 빠진다
                4L -> day(d, 2, 1)
                else -> day(d, 2, 2)
            }
        }
        val s = GoalAchievementSummary.of(rows, today)
        assertEquals(6, s.judgedDays)        // 1~7일 전 중 목표 있던 날
        assertEquals(5, s.achievedDays)
        assertEquals(5.0 / 6, s.achievementRate!!, 1e-9)
        assertEquals(2, s.currentStreak)     // 1·3일 전(2일 전은 목표 없음), 4일 전에 끊김
        assertEquals(5, s.bestStreak)        // 9~5일 전
        assertNull(GoalAchievementSummary.of(emptyList(), today).achievementRate)

        val input = AgentInputBuilder.build(null, emptyList(), InterventionPolicy(), emptyList(), emptyList(), emptyList(), zone, at(today, 12), rows)
        val json = AgentInputJson.toJson(input).getJSONObject("goalAchievement")
        assertEquals(6, json.getInt("judgedDays"))
        assertEquals(0.83, json.getDouble("achievementRate"), 1e-9)
        assertEquals(2, json.getInt("currentStreakDays"))
        assertFalse(json.toString().contains("2026-"))       // 날짜 원본은 보내지 않는다
    }
}

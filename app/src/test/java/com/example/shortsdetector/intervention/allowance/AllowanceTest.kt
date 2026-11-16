package com.example.shortsdetector.intervention.allowance

import com.example.shortsdetector.ai.AgentInputJson
import com.example.shortsdetector.data.goal.GoalRules
import com.example.shortsdetector.data.goal.GoalStatus
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.stats.AgentInputBuilder
import com.example.shortsdetector.data.stats.AllowanceSummary
import com.example.shortsdetector.data.stats.UsageStatsCalculator
import com.example.shortsdetector.data.usage.AllowanceUseRecord
import com.example.shortsdetector.data.usage.DemoSeedData
import com.example.shortsdetector.data.usage.InMemoryUsageStore
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.SessionStatus
import com.example.shortsdetector.data.usage.UsageSchema
import com.example.shortsdetector.detection.DetectionEngine
import com.example.shortsdetector.detection.adapters.youtube.YouTubeShortsAdapter
import com.example.shortsdetector.detection.state.DetectionTuning
import com.example.shortsdetector.intervention.InterventionLevelRules
import com.example.shortsdetector.intervention.InterventionPolicy
import com.example.shortsdetector.intervention.LevelStreaks
import com.example.shortsdetector.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 14단계: 재생 안내 조건, 하루 허용 시간(차감·자정·소진·단계·기록·시드·에이전트 집계). */
class AllowanceTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 13)
    private val minute = 60_000L

    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    // --- [1] 재생 안내 ---

    @Test
    fun `resume hint shows only after continue allowance or timeout with a video paused by the app`() {
        fun show(o: InterventionOutcome, popup: Boolean = true, paused: Boolean = true, session: Boolean = true, playing: Boolean = false) =
            ResumeHintRule.shouldShow(o, popup, paused, session, playing)
        assertTrue(show(InterventionOutcome.CONTINUED))
        assertTrue(show(InterventionOutcome.ALLOWANCE))
        assertTrue(show(InterventionOutcome.IGNORED))            // 숨 고르기 무응답으로 닫힘
        assertFalse(show(InterventionOutcome.STOPPED))           // 그만 볼게
        assertFalse(show(InterventionOutcome.INTERRUPTED))       // 이탈로 닫힘
        assertFalse(show(InterventionOutcome.CONTINUED, popup = false))   // 알림 방식
        assertFalse(show(InterventionOutcome.CONTINUED, paused = false))  // 앱이 멈추지 않았음
        assertFalse(show(InterventionOutcome.CONTINUED, playing = true))  // 이미 다시 재생
        assertFalse(show(InterventionOutcome.CONTINUED, session = false)) // 쇼츠를 떠남
        assertEquals(3_000L, ResumeHintRule.SHOW_MS)
    }

    // --- [2] 설정·예산 ---

    @Test
    fun `budget choices, demo 30 seconds and midnight refill without carry over`() {
        assertEquals(listOf(0, 5, 10, 15, 20, 30), AllowanceBudget.CHOICES_MINUTES)
        assertEquals(0, AllowanceBudget.normalizeMinutes(-3))
        assertEquals(15, AllowanceBudget.normalizeMinutes(17))
        assertEquals(30, AllowanceBudget.normalizeMinutes(99))
        assertEquals(10 * minute, AllowanceBudget.budgetMs(10, demoMode = false, demo30s = true))  // 시연 모드가 아니면 30초 옵션 무시
        assertEquals(30_000L, AllowanceBudget.budgetMs(10, demoMode = true, demo30s = true))
        assertEquals(0L, AllowanceBudget.budgetMs(0, demoMode = false, demo30s = false))
        assertEquals(0L, AllowanceBudget.budgetMs(0, demoMode = true, demo30s = true))   // 끔이면 시연 30초도 없음

        var s = AllowanceState()
        assertEquals(10 * minute, AllowanceBudget.remainingMs(10 * minute, s, today))
        s = AllowanceBudget.charge(s, today, 4 * minute)
        s = AllowanceBudget.charge(s, today, 4 * minute)
        assertEquals(2 * minute, AllowanceBudget.remainingMs(10 * minute, s, today))
        s = AllowanceBudget.charge(s, today, 9 * minute)
        assertEquals(0L, AllowanceBudget.remainingMs(10 * minute, s, today))
        // 자정이 지나면 다시 가득, 어제 남은 시간은 넘어오지 않는다
        assertEquals(10 * minute, AllowanceBudget.remainingMs(10 * minute, s, today.plusDays(1)))
        val nextDay = AllowanceBudget.charge(s, today.plusDays(1), minute)
        assertEquals(today.plusDays(1).toEpochDay(), nextDay.epochDay)
        assertEquals(minute, nextDay.usedMs)
    }

    @Test
    fun `remaining time is shown in minutes rounded up or seconds under a minute`() {
        assertEquals(AllowanceFormat.Left(10, true), AllowanceFormat.left(10 * minute))
        assertEquals(AllowanceFormat.Left(2, true), AllowanceFormat.left(61_000))
        assertEquals(AllowanceFormat.Left(30, false), AllowanceFormat.left(30_000))
        assertEquals(AllowanceFormat.Left(1, false), AllowanceFormat.left(1))
    }

    // --- 차감·소진·이탈 ---

    private val tuning = DetectionTuning()

    @Test
    fun `allowance counts only watched time, not paused time, and blocks interventions while active`() {
        val e = DetectionEngine(tuning, listOf(YouTubeShortsAdapter(tuning)), 10_000, watchThresholdMs = 10_000)
        var t = 1_000L
        var run: AllowanceRun? = null
        // 첫 개입까지
        while (t <= 60_000L) {
            val out = e.onSnapshot(t, Fixtures.youtubeShorts())
            if (out.notifySession != null) {
                e.onNotificationPosted(t)
                val p = e.sessionProgress()!!
                run = AllowanceRun(p.sessionNumber, level = 2, startWatchMs = p.estimatedWatchMs, budgetLeftMs = 30_000, startedAtMs = 0, day = today, demo = false)
                e.setAllowanceActive(true)
                t += 1_000
                break
            }
            t += 1_000
        }
        val r = run!!
        var last = r.startWatchMs
        // 10초 시청
        repeat(10) { e.onSnapshot(t, Fixtures.youtubeShorts()); t += 1_000 }
        var tick = r.tick(e.sessionProgress()!!.sessionNumber, e.sessionProgress()!!.estimatedWatchMs, last)
        assertTrue(tick is AllowanceRun.Tick.Using)
        val usedAfterWatch = (tick as AllowanceRun.Tick.Using).usedMs
        assertTrue("10초 안팎 사용: $usedAfterWatch", usedAfterWatch in 9_000..11_000)
        last = e.sessionProgress()!!.estimatedWatchMs
        // 영상이 멈춘 20초(시청 보류)는 줄지 않는다
        e.setWatchHold(true)
        repeat(20) { e.onSnapshot(t, Fixtures.youtubeShorts()); t += 1_000 }
        tick = r.tick(e.sessionProgress()!!.sessionNumber, e.sessionProgress()!!.estimatedWatchMs, last)
        assertEquals(usedAfterWatch, (tick as AllowanceRun.Tick.Using).usedMs)
        e.setWatchHold(false)
        // 쓰는 동안은 쿨다운·기준을 지나도 개입이 없다
        var interventions = 0
        repeat(25) {
            if (e.onSnapshot(t, Fixtures.youtubeShorts()).notifySession != null) interventions++
            t += 1_000
        }
        assertEquals(0, interventions)
        tick = r.tick(e.sessionProgress()!!.sessionNumber, e.sessionProgress()!!.estimatedWatchMs, last)
        assertEquals(AllowanceRun.Tick.Exhausted(30_000), tick)
        // 다 쓰면 서비스가 시작 때 단계(2)로 바로 개입한다. 엔진은 허용이 풀리면 다시 판정한다.
        assertEquals(2, r.level)
        e.setAllowanceActive(false)
    }

    @Test
    fun `leaving shorts stops the run and keeps what was not used`() {
        val r = AllowanceRun(3, 1, startWatchMs = 40_000, budgetLeftMs = 60_000, startedAtMs = 0, day = today, demo = false)
        assertEquals(AllowanceRun.Tick.Left(15_000), r.tick(null, 0, lastWatchMs = 55_000))
        assertEquals(AllowanceRun.Tick.Left(15_000), r.tick(4, 1_000, lastWatchMs = 55_000))   // 다른 세션
        var s = AllowanceBudget.charge(AllowanceState(), today, 15_000)
        assertEquals(45_000, AllowanceBudget.remainingMs(60_000, s, today))
        // 이어서 다음 팝업에서 다시 고르면 남은 시간부터
        val again = AllowanceRun(4, 1, 0, AllowanceBudget.remainingMs(60_000, s, today), 0, today, false)
        assertEquals(AllowanceRun.Tick.Exhausted(45_000), again.tick(4, 50_000, 0))
        s = AllowanceBudget.charge(s, today, 45_000)
        assertEquals(0L, AllowanceBudget.remainingMs(60_000, s, today))
    }

    // --- 단계·통계 ---

    @Test
    fun `choosing allowance never raises the level or touches the streaks`() {
        val lv1 = InterventionPolicy(level = 1, escalateAfterSkips = 1, maxLevel = 3)
        val step1 = InterventionLevelRules.next(lv1, LevelStreaks(skips = 0, stops = 1), InterventionOutcome.ALLOWANCE)
        assertEquals(1, step1.policy.level)
        assertEquals(LevelStreaks(0, 1), step1.streaks)
        assertFalse(step1.levelChanged)
        // Lv.2 는 CONTINUED 한 번에 오르지만 ALLOWANCE 는 아니다
        val lv2 = InterventionPolicy(level = 2, maxLevel = 3)
        assertEquals(2, InterventionLevelRules.next(lv2, LevelStreaks(), InterventionOutcome.ALLOWANCE).policy.level)
        assertEquals(3, InterventionLevelRules.next(lv2, LevelStreaks(), InterventionOutcome.CONTINUED).policy.level)
    }

    private fun session(start: Long, watchMinutes: Long) = SessionRecord(
        "s$start", "pkg", "yt-2", start, start + watchMinutes * minute, SessionStatus.CLOSED, watchMinutes * minute,
        0, 0, 0, "HIGH", null, null, 0
    )

    @Test
    fun `watching on allowance still counts for watch time and goals, and allowance is left out of stop rates`() {
        val now = at(today, 22)
        val s = session(at(today, 20), 25)              // 그중 10분은 허용 시간으로 봤다
        val ivs = listOf(
            InterventionRecord("a", null, at(today, 20, 5), InterventionMethod.POPUP, 0, 0, level = 1, outcome = InterventionOutcome.ALLOWANCE),
            InterventionRecord("b", null, at(today, 20, 20), InterventionMethod.POPUP, 0, 0, level = 1, outcome = InterventionOutcome.STOPPED)
        )
        val t = UsageStatsCalculator.today(listOf(s), ivs, listOf(GoalRules.userGoal(GoalType.DAILY_TOTAL, 20)), zone, now)
        assertEquals(25 * minute, t.estimatedWatchMs)
        assertEquals(GoalStatus.EXCEEDED, t.goals.single().status)
        val rate = UsageStatsCalculator.week(listOf(s), ivs, zone, now).levelStopRates.first { it.level == 1 }
        assertEquals(1, rate.answered)                // ALLOWANCE 는 분모에 없다
        assertEquals(1.0, rate.stopRate!!, 1e-9)
        assertEquals(2, rate.delivered)
    }

    // --- 기록·시드·삭제 ---

    @Test
    fun `seed A uses it up daily, B barely, both removed with the seed and by delete all`() {
        val now = at(today, 20)
        val a = DemoSeedData.build(DemoSeedData.Scenario.LATE_NIGHT_SKIPPER, today, zone, now).allowanceUses
        val b = DemoSeedData.build(DemoSeedData.Scenario.EASY_STOPPER, today, zone, now).allowanceUses
        assertEquals(7, a.size)
        assertTrue(a.all { it.exhausted && it.usedMs == it.budgetMs && it.isSeed })
        assertEquals(2, b.size)
        assertTrue(b.none { it.exhausted } && b.all { it.isSeed })
        val store = InMemoryUsageStore()
        (a + AllowanceUseRecord("real", at(today, 9), at(today, 9, 5), 5 * minute, 10 * minute, false, 1)).forEach(store::insertAllowanceUse)
        store.deleteSeedRecords()
        assertEquals(listOf("real"), store.allowanceUses(0, Long.MAX_VALUE).map { it.id })
        store.deleteAll()
        assertTrue(store.allowanceUses(0, Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun `schema 9 adds the allowance table without touching old tables`() {
        assertEquals(10, UsageSchema.VERSION)     // 15단계: dismiss_method 추가
        assertEquals(2, UsageSchema.MIGRATE_8_TO_9.size)
        assertTrue(UsageSchema.MIGRATE_8_TO_9[0].startsWith("CREATE TABLE allowance_uses"))
        assertTrue(UsageSchema.MIGRATE_8_TO_9[1].startsWith("CREATE INDEX"))
        assertTrue(UsageSchema.MIGRATE_8_TO_9.none { it.contains("ALTER") || it.contains("DROP") })
    }

    // --- 에이전트 입력 ---

    @Test
    fun `agent gets the setting, 7 day average use and used up days, without demo rows`() {
        val now = at(today, 12)
        val uses = listOf(
            AllowanceUseRecord("1", at(today.minusDays(1), 21), 0, 10 * minute, 10 * minute, true, 1),
            AllowanceUseRecord("2", at(today.minusDays(2), 21), 0, 4 * minute, 10 * minute, false, 1),
            AllowanceUseRecord("3", at(today.minusDays(3), 21), 0, 10 * minute, 10 * minute, true, 1),
            AllowanceUseRecord("demo", at(today.minusDays(1), 22), 0, 30_000, 30_000, true, 1, isDemo = true),
            AllowanceUseRecord("old", at(today.minusDays(9), 21), 0, 10 * minute, 10 * minute, true, 1),
            AllowanceUseRecord("today", at(today, 9), 0, 10 * minute, 10 * minute, true, 1)
        )
        val s = AllowanceSummary.of(10, uses, zone, now)
        assertEquals(AllowanceSummary(10, 24 * minute / 7, 2), s)
        val input = AgentInputBuilder.build(null, emptyList(), InterventionPolicy(), emptyList(), emptyList(), emptyList(), zone, now,
            allowanceMinutes = 10, allowanceUses = uses)
        val json = AgentInputJson.toJson(input).getJSONObject("allowance")
        assertEquals(10, json.getInt("dailyMinutes"))
        assertEquals(2, json.getInt("usedUpDays"))
        assertEquals(24 * minute / 7, json.getLong("averageUsedMs"))
        assertNull(json.optJSONArray("uses"))           // 기록 원본은 보내지 않는다
    }
}

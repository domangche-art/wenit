package com.example.shortsdetector.data.stats

import com.example.shortsdetector.ai.AgentInputJson
import com.example.shortsdetector.data.DetectionPreferences
import com.example.shortsdetector.data.goal.GoalRules
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.usage.DailyUsageCalculator
import com.example.shortsdetector.data.usage.DemoSeedData
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionOutcome.CONTINUED
import com.example.shortsdetector.data.usage.InterventionOutcome.STOPPED
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.SessionStatus
import com.example.shortsdetector.data.usage.ShortformSource
import com.example.shortsdetector.data.usage.UsageSchema
import com.example.shortsdetector.detection.adapters.instagram.InstagramReelsAdapter
import com.example.shortsdetector.detection.adapters.youtube.YouTubeViewIds
import com.example.shortsdetector.detection.model.ScreenSnapshot
import com.example.shortsdetector.detection.model.UiNode
import com.example.shortsdetector.intervention.InterventionPolicy
import com.example.shortsdetector.platform.StructureDump
import com.example.shortsdetector.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** 17단계: 앱별 집계(쇼츠/릴스), 두 앱 합산, 에이전트 입력, 감지 앱 설정, DB, 시드, 구조 덤프. */
class Step17AppSplitTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 18)      // 수요일
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(today, 22)
    private val min = 60_000L

    private fun session(id: String, pkg: String, start: Long, watchMin: Long) = SessionRecord(
        id, pkg, "r", start, start + watchMin * min, SessionStatus.CLOSED, watchMin * min, 0, 0, 0, "HIGH", null, null, 0
    )

    private fun iv(id: String, sessionId: String?, atMs: Long, outcome: InterventionOutcome) =
        InterventionRecord(id, sessionId, atMs, InterventionMethod.POPUP, 0, 0, level = 1, outcome = outcome)

    private val yt = YouTubeViewIds.PACKAGE
    private val ig = InstagramReelsAdapter.PACKAGE

    @Test
    fun `packages map to shorts and reels, anything else is unknown`() {
        assertEquals(ShortformSource.SHORTS, ShortformSource.of(yt))
        assertEquals(ShortformSource.REELS, ShortformSource.of(ig))
        assertNull(ShortformSource.of("com.example.other"))
        assertNull(ShortformSource.of(null))
        assertEquals(listOf("shorts", "reels"), ShortformSource.entries.map { it.agentKey })
    }

    // --- 두 앱 합산 ---

    @Test
    fun `daily watch, goals and the week total combine both apps`() {
        val sessions = listOf(
            session("a", yt, at(today, 9), 20),
            session("b", ig, at(today, 12), 15),
            session("c", ig, at(today.minusDays(1), 20), 10)
        )
        assertEquals(35 * min, DailyUsageCalculator.aggregate(sessions, zone, today, today).single().estimatedWatchMs)
        val goal = GoalRules.userGoal(GoalType.DAILY_TOTAL, 30)
        val metrics = UsageStatsCalculator.dayMetrics(sessions, emptyList(), listOf(goal), zone, today, now, 4)
        assertEquals("목표는 두 앱 합계(35분)로 본다", 35 * min, metrics.watchMs)

        val week = UsageStatsCalculator.week(sessions, emptyList(), zone, now)
        assertEquals(45 * min, week.totalWatchMs)
        assertEquals(listOf(ShortformSource.SHORTS, ShortformSource.REELS), week.appTotals.map { it.source })
        assertEquals(20 * min, week.appTotals.single { it.source == ShortformSource.SHORTS }.watchMs)
        assertEquals(25 * min, week.appTotals.single { it.source == ShortformSource.REELS }.watchMs)
        assertEquals(week.totalWatchMs, week.appTotals.sumOf { it.watchMs })
    }

    @Test
    fun `seed and unknown packages - unknown counts in the total but in no app`() {
        val sessions = listOf(session("a", yt, at(today, 9), 20), session("x", "com.example.other", at(today, 10), 5))
        val week = UsageStatsCalculator.week(sessions, emptyList(), zone, now)
        assertEquals(25 * min, week.totalWatchMs)
        assertEquals(20 * min, week.appTotals.sumOf { it.watchMs })
    }

    // --- 에이전트 입력용 앱별 집계 ---

    @Test
    fun `recent stats split watch share and stop rate by app through the session id`() {
        val sessions = listOf(
            session("s1", yt, at(today.minusDays(1), 20), 30),
            session("r1", ig, at(today.minusDays(2), 21), 10)
        )
        val ivs = List(4) { iv("y$it", "s1", at(today.minusDays(1), 20, it + 1), STOPPED) } +
            iv("y4", "s1", at(today.minusDays(1), 20, 10), CONTINUED) +
            List(2) { iv("r$it", "r1", at(today.minusDays(2), 21, it + 1), STOPPED) } +
            iv("orphan", null, at(today.minusDays(1), 9), STOPPED)
        val apps = UsageStatsCalculator.recent(sessions, ivs, zone, now).apps
        assertEquals(listOf(ShortformSource.SHORTS, ShortformSource.REELS), apps.map { it.source })
        val shorts = apps[0]
        val reels = apps[1]
        assertEquals(0.75, shorts.watchShare!!, 1e-9)
        assertEquals(0.25, reels.watchShare!!, 1e-9)
        assertEquals(5, shorts.samples)
        assertEquals(0.8, shorts.stopRate!!, 1e-9)
        assertEquals("세션 없는 개입은 어느 앱에도 안 들어감", 2, reels.samples)
        assertNull("표본이 적으면 비율 없음", reels.stopRate)
    }

    @Test
    fun `no watching means no share instead of zero`() {
        val apps = UsageStatsCalculator.recent(emptyList(), emptyList(), zone, now).apps
        assertTrue(apps.all { it.watchShare == null && it.samples == 0 })
    }

    @Test
    fun `agent input sends shorts and reels keys with aggregates only, never package names`() {
        val sessions = listOf(session("secret-yt", yt, at(today.minusDays(1), 20), 30), session("secret-ig", ig, at(today.minusDays(1), 22), 10))
        val input = AgentInputBuilder.build(null, emptyList(), InterventionPolicy(), sessions, emptyList(), emptyList(), zone, now)
        val json = AgentInputJson.toJson(input)
        val byApp = json.getJSONObject("recent").getJSONObject("byApp")
        assertEquals(setOf("shorts", "reels"), byApp.keys().asSequence().toSet())
        assertEquals(0.75, byApp.getJSONObject("shorts").getDouble("watchShare"), 1e-3)
        assertEquals("INSUFFICIENT_DATA", byApp.getJSONObject("reels").getString("status"))
        val text = json.toString()
        assertFalse("com.instagram" in text)
        assertFalse("com.google" in text)
        assertFalse("secret" in text)
    }

    // --- 감지 앱 설정 ---

    @Test
    fun `each app can be switched on and off, instagram stays off by default`() {
        val p = FakeSharedPreferences()
        assertTrue(DetectionPreferences.isYouTubeEnabled(p))
        assertFalse("실기기에서 소리 멈춤 실패 → 기본 꺼짐", DetectionPreferences.isInstagramEnabled(p))
        assertEquals(setOf(yt), DetectionPreferences.enabledPackages(p))
        DetectionPreferences.setInstagramEnabled(p, true)
        assertEquals(setOf(yt, ig), DetectionPreferences.enabledPackages(p))
        DetectionPreferences.setYouTubeEnabled(p, false)
        assertEquals(setOf(ig), DetectionPreferences.enabledPackages(p))
        DetectionPreferences.setInstagramEnabled(p, false)
        assertTrue(DetectionPreferences.enabledPackages(p).isEmpty())
    }

    @Test
    fun `a user who already switched instagram on keeps it on`() {
        val p = FakeSharedPreferences().apply { values[DetectionPreferences.KEY_INSTAGRAM_ENABLED] = true }
        assertTrue(DetectionPreferences.isInstagramEnabled(p))
    }

    // --- DB ---

    @Test
    fun `the app is already stored per session since version 1, so the schema stays at 11`() {
        assertEquals(11, UsageSchema.VERSION)
        assertTrue(UsageSchema.CREATE_V1.joinToString("\n").contains("package_name TEXT NOT NULL"))
        val db = File("src/main/java/com/example/shortsdetector/data/usage/UsageDatabase.kt").readText()
        assertTrue(db.contains("put(\"package_name\""))
    }

    // --- 시드 ---

    @Test
    fun `seed A mixes in a few reels sessions with interventions, seed B has none, totals are unchanged`() {
        val a = DemoSeedData.build(DemoSeedData.Scenario.LATE_NIGHT_SKIPPER, today, zone, now)
        val b = DemoSeedData.build(DemoSeedData.Scenario.EASY_STOPPER, today, zone, now)
        val reels = a.sessions.filter { it.packageName == ig }
        assertEquals(3, reels.size)
        assertTrue("시드 삭제 때 함께 지워진다", reels.all { it.isSeed })
        assertTrue(a.interventions.any { i -> reels.any { it.id == i.sessionId } && i.outcome == STOPPED })
        assertTrue(b.sessions.none { it.packageName == ig })
        assertTrue(a.sessions.all { ShortformSource.of(it.packageName) != null })
    }

    // --- 개발용 구조 덤프 ---

    @Test
    fun `structure dump lines carry only id, class, bounds, visibility and area`() {
        val s = ScreenSnapshot(
            ig, 1000, 2000,
            listOf(UiNode(null, "android.widget.FrameLayout", 0, 0, 1000, 2000, true), UiNode("$ig:id/x", null, 0, 0, 500, 400, false, scrollable = true))
        )
        assertEquals(listOf("0\t-\tFrameLayout\t0,0,1000,2000\tv\t-\t1.000", "1\t$ig:id/x\t-\t0,0,500,400\th\ts\t0.100"), StructureDump.lines(s))
        assertEquals("reels_tab1", StructureDump.label("reels_tab1 <캡션>!"))
    }
}

package com.example.shortsdetector.intervention

import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.usage.InMemoryUsageStore
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.SystemUsageClock
import com.example.shortsdetector.detection.DetectionEngine
import com.example.shortsdetector.detection.EngineOutput
import com.example.shortsdetector.detection.adapters.youtube.YouTubeShortsAdapter
import com.example.shortsdetector.detection.model.DetectionState
import com.example.shortsdetector.detection.state.DetectionTuning
import com.example.shortsdetector.testutil.FakeSharedPreferences
import com.example.shortsdetector.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 한 세션 안의 개입 반복. 엔진, 단계 규칙, 재개입 규칙을 서비스처럼 이어 붙인다. */
class InterventionRepeatTest {

    private val tuning = DetectionTuning()
    private val prefs = FakeSharedPreferences()
    private val store = InMemoryUsageStore()

    private val copy = object : InterventionCopyProvider {
        override fun copyFor(input: InterventionCopyInput) = InterventionCopy("t")
        override fun levelChangeReason(from: Int, to: Int, policy: InterventionPolicy) = "r"
    }
    private var ids = 0
    private val coordinator = InterventionCoordinator(store, PreferencesPolicyStore(prefs), copy, SystemUsageClock)

    private fun engine(cooldownMs: Long, thresholdMs: Long) =
        DetectionEngine(tuning, listOf(YouTubeShortsAdapter(tuning)), cooldownMs, watchThresholdMs = thresholdMs)

    /**
     * 1초마다 쇼츠를 본다. 개입이 뜨면 [answerAfterMs] 뒤에 [answer] 로 답한다.
     * @return 개입마다 (시청시간, 단계)
     */
    private fun watch(
        e: DetectionEngine,
        seconds: Int,
        answerAfterMs: Long = 0,
        answer: (Int) -> InterventionOutcome
    ): List<Pair<Long, Int>> {
        val shown = ArrayList<Pair<Long, Int>>()
        var t = 0L
        var open: Pair<Long, Int>? = null
        repeat(seconds) {
            t += 1_000
            val out = e.onSnapshot(t, Fixtures.youtubeShorts())
            out.notifySession?.let { n ->
                val resolved = InterventionPolicyPreferences.resolve(prefs, emptyList())
                val level = resolved.policy.level
                shown += out.notifyWatchMs!! to level
                val id = "i${++ids}"
                store.insertIntervention(InterventionRecord(id, null, t, InterventionMethod.POPUP, 0, 0, level = level))
                coordinator.begin(id, n, level)
                e.onNotificationPosted(t)
                // 서비스와 같다: 최대 단계를 띄우면 다음 개입까지 최소 간격
                e.setRepeatFloor(MaxLevelRepeatRule.gapAfterShowing(level, resolved))
                e.setInterventionOpen(true, t)
                open = t to level
                wallShownAt += t
            }
            open?.let { (shownAt, level) ->
                if (t - shownAt >= answerAfterMs) {
                    val r = coordinator.respond(answer(level))
                    if (r != null && !MaxLevelRepeatRule.keepsGap(r.nextLevel, InterventionPolicyPreferences.resolve(prefs, emptyList()))) {
                        e.setRepeatFloor(0)
                    }
                    r?.reinterveneAfterWatchMs?.let { after ->
                        e.sessionProgress()?.let { p -> e.delayIntervention(p.sessionNumber, p.estimatedWatchMs + after) }
                    }
                    e.setInterventionOpen(false, t)
                    open = null
                }
            }
        }
        return shown
    }

    private fun levels(shown: List<Pair<Long, Int>>) = shown.map { it.second }

    /** [watch] 에서 개입이 뜬 벽시계 시각 */
    private val wallShownAt = ArrayList<Long>()

    private fun gaps(values: List<Long>) = values.zipWithNext { a, b -> b - a }

    @Test
    fun `one session climbs level 1, 2, 3 and then keeps repeating level 3 outside demo mode`() {
        // 15단계: 기본 최대 단계가 4 가 됐다. 최대 단계를 3 으로 고른 사용자의 기존 동작을 지킨다(Lv.4 는 아래 15단계 테스트)
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, cooldownMs = 30_000, maxLevel = 3))
        val shown = watch(engine(30_000, 30_000), 1_500) { InterventionOutcome.CONTINUED }

        // Lv.1 은 넘김 2번에 오른다(기본 정책). Lv.3 뒤에는 "5분만 더" 간격으로 반복한다.
        assertEquals(listOf(1, 1, 2, 3, 3, 3, 3), levels(shown).take(7))
        assertTrue(shown.size >= 7)
    }

    @Test
    fun `demo mode climbs one step per answer and keeps repeating level 3 at its short interval`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, maxLevel = 3))  // 최대 3 사용자(15단계 전 기본값)
        InterventionPolicyPreferences.writeDemoMode(prefs, true)
        val shown = watch(engine(DemoMode.COOLDOWN_MS, DemoMode.THRESHOLD_MS), 400) { InterventionOutcome.CONTINUED }

        assertEquals(listOf(1, 2, 3, 3, 3), levels(shown).take(5))
        val gaps = shown.drop(2).zipWithNext { a, b -> b.first - a.first }
        assertTrue(gaps.isNotEmpty() && gaps.all { it in DemoMode.MORE_TIME_MS..(DemoMode.MORE_TIME_MS + 2_000) })
        assertTrue("상한 없이 계속 반복해야 한다", shown.size > 10)
    }

    @Test
    fun `stopping at level 3 and watching on still repeats, at least 2 minutes apart`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, level = 3, deescalateAfterStops = 10, maxLevel = 3))
        val shown = watch(engine(30_000, 30_000), 1_100) { InterventionOutcome.STOPPED }

        assertEquals(List(shown.size) { 3 }, levels(shown))
        assertTrue(shown.size >= 8)
        assertTrue(gaps(shown.map { it.first }).all { it in MaxLevelRepeatRule.MIN_GAP_MS..(MaxLevelRepeatRule.MIN_GAP_MS + 2_000) })
    }

    @Test
    fun `no answer at the top level repeats at the top level, at least 2 minutes apart`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, level = 3, maxLevel = 3))
        val shown = watch(engine(30_000, 30_000), 1_200, answerAfterMs = 5_000) { InterventionOutcome.IGNORED }

        assertEquals(List(shown.size) { 3 }, levels(shown))
        assertTrue(shown.size >= 7)
        assertTrue(gaps(shown.map { it.first }).all { it >= MaxLevelRepeatRule.MIN_GAP_MS })
        assertTrue(gaps(wallShownAt).all { it >= MaxLevelRepeatRule.MIN_GAP_MS })
    }

    @Test
    fun `max level 2 repeats level 2, at least 2 minutes apart`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, level = 2, maxLevel = 2))
        val shown = watch(engine(30_000, 30_000), 1_200) { InterventionOutcome.CONTINUED }

        assertEquals(List(shown.size) { 2 }, levels(shown))
        assertTrue(shown.size >= 8)
        assertTrue(gaps(wallShownAt).all { it >= MaxLevelRepeatRule.MIN_GAP_MS })
    }

    @Test
    fun `there is no daily ceiling on repeats`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, level = 1))
        val shown = watch(engine(30_000, 30_000), 3_600) { InterventionOutcome.IGNORED }
        assertTrue(shown.size > 100)
    }

    // --- 최대 단계 반복 간격 (10-2단계 [1]) ---

    @Test
    fun `a long cooldown is kept at the top level`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, level = 3, cooldownMs = 300_000))
        val shown = watch(engine(300_000, 30_000), 1_500, answerAfterMs = 5_000) { InterventionOutcome.IGNORED }
        assertTrue(shown.size >= 3)
        assertTrue(gaps(wallShownAt).all { it >= 300_000 })
    }

    @Test
    fun `the first arrival at the top level follows the cooldown, the repeat waits 2 minutes`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, level = 2, maxLevel = 3))
        val shown = watch(engine(30_000, 30_000), 600, answerAfterMs = 5_000) { level ->
            if (level == 2) InterventionOutcome.CONTINUED else InterventionOutcome.IGNORED
        }
        assertEquals(listOf(2, 3, 3), levels(shown).take(3))
        val wall = gaps(wallShownAt)
        assertTrue("Lv.2 → Lv.3 은 쿨다운 간격", wall[0] < MaxLevelRepeatRule.MIN_GAP_MS)
        assertTrue("Lv.3 → Lv.3 은 2분 이상", wall.drop(1).all { it >= MaxLevelRepeatRule.MIN_GAP_MS })
    }

    @Test
    fun `dropping below the top level returns to the cooldown`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, level = 3, deescalateAfterStops = 1))
        val shown = watch(engine(30_000, 30_000), 400, answerAfterMs = 5_000) { level ->
            if (level == 3) InterventionOutcome.STOPPED else InterventionOutcome.IGNORED
        }
        assertEquals(listOf(3, 2), levels(shown).take(2))
        assertTrue("Lv.3 멈춤 뒤 Lv.2 는 쿨다운 간격", gaps(wallShownAt)[0] < MaxLevelRepeatRule.MIN_GAP_MS)
    }

    @Test
    fun `demo mode keeps repeating the top level at its short interval`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, level = 3))
        InterventionPolicyPreferences.writeDemoMode(prefs, true)
        prefs.edit().putInt("demo_level", 3).apply()
        val shown = watch(engine(DemoMode.COOLDOWN_MS, DemoMode.THRESHOLD_MS), 300, answerAfterMs = 2_000) { InterventionOutcome.IGNORED }

        assertEquals(List(shown.size) { 3 }, levels(shown))
        assertTrue(shown.size >= 10)
        assertTrue(gaps(wallShownAt).all { it < 20_000 })
    }

    @Test
    fun `the 2 minute gap holds across sessions`() {
        val e = engine(30_000, 10_000)
        e.frames(0, 12_000)
        e.setRepeatFloor(MaxLevelRepeatRule.MIN_GAP_MS)
        (13_000L..30_000L step 1_000L).forEach { e.onRootUnavailable(it) }
        val next = e.frames(31_000, 200_000)
        val firstAt = 31_000L + next.indexOfFirst { it.notifySession != null } * 1_000L
        assertTrue(next.any { it.step.startedSession != null })
        assertTrue("새 세션의 첫 개입도 2분 뒤", firstAt - 11_000L >= MaxLevelRepeatRule.MIN_GAP_MS)
    }

    // --- 간격 ---

    private fun DetectionEngine.frames(from: Long, to: Long): List<EngineOutput> =
        (from..to step 1_000L).map { t ->
            onSnapshot(t, Fixtures.youtubeShorts()).also { if (it.notifySession != null) onNotificationPosted(t) }
        }

    @Test
    fun `a long cooldown only delays the next intervention and does not use it up`() {
        val e = engine(600_000, 30_000)
        val outs = e.frames(0, 1_400_000)
        assertTrue(outs.count { it.notifySession != null } >= 2)
    }

    @Test
    fun `a chance held back by the cooldown is reported once and fires when the cooldown ends`() {
        val e = engine(120_000, 30_000)
        val first = e.frames(0, 40_000)
        assertEquals(1, first.count { it.notifySession != null })

        // 다음 세션: 쿨다운이 남은 채 기준에 닿는다.
        (41_000L..44_000L step 1_000L).forEach { e.onRootUnavailable(it) }
        val second = (60_000L..170_000L step 1_000L).map { t ->
            e.onSnapshot(t, Fixtures.youtubeShorts()).also { if (it.notifySession != null) e.onNotificationPosted(t) }
        }
        assertEquals(1, second.count { it.suppressedByCooldown })
        assertTrue(second.count { it.notifySession != null } >= 1)
    }

    @Test
    fun `a repeat waiting for the cooldown leaves no skipped record`() {
        val e = engine(30_000, 30_000)
        val outs = e.frames(0, 200_000)
        assertTrue(outs.count { it.notifySession != null } >= 5)
        assertEquals(0, outs.count { it.suppressedByCooldown })
    }

    @Test
    fun `nothing fires while a popup is open and the cooldown restarts when it closes`() {
        val e = engine(30_000, 30_000)
        val outs = e.frames(0, 40_000)
        val shownAt = outs.indexOfFirst { it.notifySession != null } * 1_000L
        e.setInterventionOpen(true, shownAt)

        assertEquals(0, e.frames(41_000, 120_000).count { it.notifySession != null })

        e.setInterventionOpen(false, 121_000)
        assertEquals(0, e.frames(121_000, 145_000).count { it.notifySession != null })
        assertEquals(1, e.frames(146_000, 155_000).count { it.notifySession != null })
    }

    @Test
    fun `paused playback does not bring the next intervention closer`() {
        val e = engine(30_000, 30_000)
        e.frames(0, 40_000)
        e.setWatchHold(true)
        assertEquals(0, e.frames(41_000, 200_000).count { it.notifySession != null })
    }

    @Test
    fun `repeats wait until the shorts screen is fully visible again`() {
        val e = engine(30_000, 30_000)
        e.frames(0, 40_000)
        val partial = (41_000L..100_000L step 1_000L).map { e.onSnapshot(it, Fixtures.youtubeShortsCommentsOpen()) }
        assertEquals(DetectionState.TEMPORARILY_OBSCURED, partial.last().step.current)
        assertEquals(0, partial.count { it.notifySession != null })
        assertEquals(1, e.frames(101_000, 104_000).count { it.notifySession != null })
    }

    @Test
    fun `leaving and coming back into the same session keeps the rhythm`() {
        val e = engine(30_000, 30_000)
        e.frames(0, 40_000)
        (41_000L..44_000L step 1_000L).forEach { e.onRootUnavailable(it) }
        val back = e.frames(46_000, 110_000)
        assertTrue(back.any { it.step.resumedSession != null })
        assertTrue(back.count { it.notifySession != null } >= 1)
        assertFalse(back.any { it.step.startedSession != null })
    }

    // --- 15단계: Lv.4 ---

    @Test
    fun `with the default max level one session climbs to level 4 and repeats it at least 2 minutes apart`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, cooldownMs = 30_000))
        val shown = watch(engine(30_000, 30_000), 2_400) { InterventionOutcome.CONTINUED }

        assertEquals(listOf(1, 1, 2, 3, 4, 4, 4), levels(shown).take(7))
        val atLv4 = shown.withIndex().filter { it.value.second == 4 }.map { wallShownAt[it.index] }
        assertTrue(atLv4.size >= 3)
        assertTrue("Lv.4 반복은 2분 이상", gaps(atLv4).all { it >= MaxLevelRepeatRule.MIN_GAP_MS })
        // Lv.3 "5분만 더 보기" 뒤 Lv.4 는 시청시간 5분 뒤
        val three = shown.indexOfFirst { it.second == 3 }
        assertTrue(shown[three + 1].first - shown[three].first >= ReinterventionRule.FIVE_MORE_MINUTES_MS)
    }

    @Test
    fun `demo mode climbs 1 to 4 at short intervals and keeps repeating level 4`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000))
        InterventionPolicyPreferences.writeDemoMode(prefs, true)
        val shown = watch(engine(DemoMode.COOLDOWN_MS, DemoMode.THRESHOLD_MS), 400) { InterventionOutcome.CONTINUED }

        assertEquals(listOf(1, 2, 3, 4, 4), levels(shown).take(5))
        assertTrue("시연은 짧은 간격", gaps(wallShownAt).all { it <= DemoMode.MORE_TIME_MS + 2_000 })
        assertTrue(shown.size > 8)
    }

    @Test
    fun `leaving level 4 unanswered does not change the level`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000, level = 4))
        val shown = watch(engine(30_000, 30_000), 900, answerAfterMs = 5_000) { InterventionOutcome.INTERRUPTED }
        assertTrue(shown.size >= 2)
        assertEquals(List(shown.size) { 4 }, levels(shown))
    }
}

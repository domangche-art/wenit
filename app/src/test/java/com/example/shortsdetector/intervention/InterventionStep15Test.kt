package com.example.shortsdetector.intervention

import com.example.shortsdetector.R
import com.example.shortsdetector.ai.agent.AgentPolicyBounds
import com.example.shortsdetector.ai.agent.AgentPolicyRules
import com.example.shortsdetector.ai.agent.AgentProposal
import com.example.shortsdetector.ai.agent.PolicyAgent
import com.example.shortsdetector.data.DetectionPreferences
import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.goal.GoalRules
import com.example.shortsdetector.data.goal.GoalStatus
import com.example.shortsdetector.data.goal.GoalType
import com.example.shortsdetector.data.stats.UsageStatsCalculator
import com.example.shortsdetector.data.usage.CharacterDismissal
import com.example.shortsdetector.data.usage.DemoSeedData
import com.example.shortsdetector.data.usage.InMemoryUsageStore
import com.example.shortsdetector.data.usage.InterventionAction
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionOutcome.ALLOWANCE
import com.example.shortsdetector.data.usage.InterventionOutcome.CONTINUED
import com.example.shortsdetector.data.usage.InterventionOutcome.IGNORED
import com.example.shortsdetector.data.usage.InterventionOutcome.STOPPED
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.SystemUsageClock
import com.example.shortsdetector.data.usage.UsageSchema
import com.example.shortsdetector.detection.DetectionEngine
import com.example.shortsdetector.detection.adapters.youtube.YouTubeShortsAdapter
import com.example.shortsdetector.detection.model.WindowStatus
import com.example.shortsdetector.detection.state.DetectionTuning
import com.example.shortsdetector.intervention.character.Box
import com.example.shortsdetector.intervention.character.Characters
import com.example.shortsdetector.intervention.character.Lv4CharacterRules
import com.example.shortsdetector.intervention.character.Lv4Entrance
import com.example.shortsdetector.intervention.character.Lv4TapReaction
import com.example.shortsdetector.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** 15단계: 개입 Lv.4 "캐릭터 방해", 최대 단계 1~4(기본 4), 캐릭터 정의, 결과 기록, 시드. */
class InterventionStep15Test {

    private val prefs = FakeSharedPreferences()
    private val store = InMemoryUsageStore()
    private val copy = object : InterventionCopyProvider {
        override fun copyFor(input: InterventionCopyInput) = InterventionCopy("t")
        override fun levelChangeReason(from: Int, to: Int, policy: InterventionPolicy) = "r"
    }
    private val coordinator = InterventionCoordinator(store, PreferencesPolicyStore(prefs), copy, SystemUsageClock)

    private fun next(p: InterventionPolicy, vararg outcomes: InterventionOutcome): LevelStep {
        var step = LevelStep(p, LevelStreaks(), false)
        for (o in outcomes) step = InterventionLevelRules.next(step.policy, step.streaks, o)
        return step
    }

    // --- [1] 단계 추가 ---

    @Test
    fun `level 4 is the character block, full screen, paused on show, and the new top`() {
        val four = InterventionLevels.of(4)
        assertEquals(4, InterventionLevels.MAX)
        assertEquals(InterventionKind.CHARACTER_BLOCK, four.kind)
        assertEquals(OverlayWindow.FULL_SCREEN, four.window)
        assertEquals(R.layout.overlay_intervention_lv4, four.layout)
        assertTrue(four.pausesOnShow)
        assertFalse("시간을 약속하지 않는다", four.promisesMoreTime)
        assertEquals(EscalationRule.NONE, four.escalation)
    }

    @Test
    fun `continuing at level 3 climbs to level 4, other answers do not`() {
        val p = InterventionPolicy(level = 3)
        val up = next(p, CONTINUED)
        assertEquals(4, up.policy.level)
        assertTrue(up.levelChanged)
        assertEquals(3, next(p, IGNORED).policy.level)          // 숨 고르기가 답 없이 닫힘
        assertEquals(3, next(p, ALLOWANCE).policy.level)
        assertEquals(3, next(p, InterventionOutcome.INTERRUPTED).policy.level)
    }

    @Test
    fun `level 4 stays on continue and drops after stops`() {
        val p = InterventionPolicy(level = 4)
        assertEquals(4, next(p, CONTINUED, CONTINUED, IGNORED).policy.level)
        assertEquals(3, next(p, STOPPED, STOPPED).policy.level)
    }

    @Test
    fun `whole climb with the default policy goes 1, 2, 3, 4`() {
        val levels = ArrayList<Int>()
        var step = LevelStep(InterventionPolicy(), LevelStreaks(), false)
        repeat(6) {
            levels += step.policy.level
            step = InterventionLevelRules.next(step.policy, step.streaks, CONTINUED)
        }
        assertEquals(listOf(1, 1, 2, 3, 4, 4), levels)
    }

    @Test
    fun `max level choices are 1 to 4 and each one caps the climb`() {
        assertEquals(1..4, InterventionPolicyBounds.MAX_LEVEL_SETTING)
        assertEquals(listOf(1, 2, 3, 4), InterventionLevels.maxLevelChoices.map { it.number })
        for (max in 1..4) {
            val top = next(InterventionPolicy(maxLevel = max, escalateAfterSkips = 1), *Array(8) { CONTINUED }).policy.level
            assertEquals("최대 $max", max, top)
        }
        assertEquals(4, InterventionPolicy().maxLevel)
    }

    @Test
    fun `an old default max level 3 becomes 4 once, a chosen 2 is kept`() {
        val old = FakeSharedPreferences().apply { values["policy"] = "thresholdMs=180000;level=2;maxLevel=3" }
        assertTrue(InterventionPolicyPreferences.migrateMaxLevelDefault(old))
        assertEquals(4, InterventionPolicyPreferences.read(old).maxLevel)
        assertEquals("단계와 다른 값은 그대로", 2, InterventionPolicyPreferences.read(old).level)

        val chose2 = FakeSharedPreferences().apply { values["policy"] = "level=1;maxLevel=2" }
        assertFalse(InterventionPolicyPreferences.migrateMaxLevelDefault(chose2))
        assertEquals(2, InterventionPolicyPreferences.read(chose2).maxLevel)
        assertTrue(InterventionPolicyPreferences.isMaxLevelUserSet(chose2))
    }

    @Test
    fun `a max level the user picked is kept, and the change runs only once`() {
        val p = FakeSharedPreferences().apply { values["policy"] = "level=1;maxLevel=4" }
        InterventionPolicyPreferences.writeUserMaxLevel(p, 3)
        assertFalse(InterventionPolicyPreferences.migrateMaxLevelDefault(p))
        assertEquals(3, InterventionPolicyPreferences.read(p).maxLevel)

        // 기본값을 4 로 올린 뒤 사용자가 3 을 고르면 다음 실행에도 3 이다
        val q = FakeSharedPreferences().apply { values["policy"] = "level=1;maxLevel=3" }
        InterventionPolicyPreferences.migrateMaxLevelDefault(q)
        q.edit().putString("policy", "level=1;maxLevel=3").apply()
        assertFalse(InterventionPolicyPreferences.migrateMaxLevelDefault(q))
        assertEquals(3, InterventionPolicyPreferences.read(q).maxLevel)
    }

    @Test
    fun `a fresh install has nothing to migrate and uses 4`() {
        val p = FakeSharedPreferences()
        assertFalse(InterventionPolicyPreferences.migrateMaxLevelDefault(p))
        assertEquals(4, InterventionPolicyPreferences.read(p).maxLevel)
        assertFalse(p.contains("policy"))
    }

    private fun proposal(startLevel: Int) = AgentProposal(false, 180, 60, startLevel, "이유", "가", "나")

    @Test
    fun `the agent can start at most at level 3`() {
        assertEquals(3, AgentPolicyBounds.MAX_START_LEVEL)
        assertEquals(3, AgentPolicyRules.apply(InterventionPolicy(level = 3), proposal(4)).level)
        assertEquals(3, AgentPolicyRules.apply(InterventionPolicy(level = 4), proposal(4)).level)
        assertEquals(3, AgentPolicyRules.apply(InterventionPolicy(level = 4), proposal(5)).level)
        assertEquals(2, AgentPolicyRules.apply(InterventionPolicy(level = 2, maxLevel = 2), proposal(3)).level)
        assertEquals(2, AgentPolicyRules.apply(InterventionPolicy(level = 3), proposal(2)).level)
        assertTrue("startLevel: 1~3" in PolicyAgent.SYSTEM_PROMPT)
    }

    // --- [2] 결과 기록 ---

    private fun begin(id: String, level: Int = 4) {
        store.insertIntervention(InterventionRecord(id, null, 1L, InterventionMethod.POPUP, 0, 0, level = level))
        coordinator.begin(id, 1, level)
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(level = level))
    }

    private fun record(id: String) = store.allInterventions().single { it.id == id }

    @Test
    fun `clearing by tap is a continue with the method`() {
        begin("tap")
        val r = coordinator.respond(CONTINUED, CharacterDismissal.TAP)!!
        assertEquals(4, r.nextLevel)
        assertEquals(CONTINUED, record("tap").outcome)
        assertEquals(CharacterDismissal.TAP, record("tap").dismissal)
        assertEquals(InterventionAction.DISMISS, record("tap").userAction)
    }

    @Test
    fun `clearing by drag is a continue with the method`() {
        begin("drag")
        coordinator.respond(CONTINUED, CharacterDismissal.DRAG)
        assertEquals(CONTINUED, record("drag").outcome)
        assertEquals(CharacterDismissal.DRAG, record("drag").dismissal)
    }

    @Test
    fun `stop opens the app and has no dismissal`() {
        begin("stop")
        coordinator.respond(STOPPED, CharacterDismissal.TAP)       // 방법은 계속 보기에만 남긴다
        assertEquals(STOPPED, record("stop").outcome)
        assertEquals(InterventionAction.OPEN_APP, record("stop").userAction)
        assertNull(record("stop").dismissal)
    }

    @Test
    fun `allowance is recorded as allowance and keeps the level`() {
        begin("allow")
        val r = coordinator.respond(ALLOWANCE)!!
        assertEquals(ALLOWANCE, record("allow").outcome)
        assertNull(record("allow").dismissal)
        assertEquals(4, r.nextLevel)
    }

    @Test
    fun `leaving the app interrupts and keeps the level and streaks`() {
        begin("leave")
        InterventionPolicyPreferences.writeLevelState(prefs, 4, LevelStreaks(stops = 1))
        val r = coordinator.interrupt()!!
        assertEquals(InterventionOutcome.INTERRUPTED, record("leave").outcome)
        assertEquals(4, r.nextLevel)
        assertEquals(LevelStreaks(stops = 1), InterventionPolicyPreferences.readLevelStreaks(prefs))
        assertNull(record("leave").userAction)
    }

    @Test
    fun `level 4 continue waits for the cooldown and the 2 minute top repeat, demo stays short`() {
        assertNull("시간 약속이 없어 쿨다운을 따른다", ReinterventionRule.afterWatchMs(4, CONTINUED, null))
        assertEquals(DemoMode.THRESHOLD_MS, ReinterventionRule.afterWatchMs(4, CONTINUED, DemoMode.THRESHOLD_MS))
        val real = ResolvedPolicy(InterventionPolicy(level = 4), 180_000, demoMode = false)
        assertEquals(MaxLevelRepeatRule.MIN_GAP_MS, MaxLevelRepeatRule.gapAfterShowing(4, real))
        assertEquals("Lv.3 은 이제 최대 단계가 아니다", 0L, MaxLevelRepeatRule.gapAfterShowing(3, real))
        val demo = ResolvedPolicy(InterventionPolicy(level = 4), 10_000, demoMode = true)
        assertEquals(0L, MaxLevelRepeatRule.gapAfterShowing(4, demo))
    }

    @Test
    fun `leave guard closes level 4 on any window that is not the target app`() {
        assertTrue(LeaveGuard.closeBlocking(true, WindowStatus.FOREIGN))
        assertTrue(LeaveGuard.closeBlocking(true, WindowStatus.SYSTEM_PANEL))
        assertFalse(LeaveGuard.closeBlocking(true, WindowStatus.TARGET))
        assertFalse("창을 못 얻은 순간은 이탈 신호에 맡긴다", LeaveGuard.closeBlocking(true, WindowStatus.UNAVAILABLE))
        assertFalse("Lv.1~3 은 기존 규칙 그대로", LeaveGuard.closeBlocking(false, WindowStatus.FOREIGN))
    }

    @Test
    fun `quick leave check closes level 4 when the active window is not a target app`() {
        val tuning = DetectionTuning()
        val engine = DetectionEngine(tuning, listOf(YouTubeShortsAdapter(tuning)), 30_000)
        fun quick(pkg: String?) = LeaveGuard.closeOnQuickCheck(true, pkg, engine.isTarget(pkg))

        assertFalse("YouTube 안에서는 닫지 않는다", quick("com.google.android.youtube"))
        assertTrue("홈(런처)", quick("com.sec.android.app.launcher"))
        assertTrue("알림창·빠른 설정·잠금 화면(시스템 UI)", quick(DetectionEngine.SYSTEM_UI_PACKAGE))
        assertTrue("다른 앱", quick("com.android.settings"))
        assertTrue("WENIT 앱 화면도 대상 앱이 아니다", quick("com.example.shortsdetector"))
        assertFalse("활성 창을 못 얻으면 닫지 않는다", quick(null))
        assertFalse("Lv.1~3 은 빠른 확인을 하지 않는다", LeaveGuard.closeOnQuickCheck(false, "com.sec.android.app.launcher", false))
    }

    @Test
    fun `quick leave check agrees with the per-observation leave guard`() {
        // 대상 앱이 아닌 창(FOREIGN·SYSTEM_PANEL)은 둘 다 닫고, 대상 앱 창은 둘 다 닫지 않는다.
        assertEquals(
            LeaveGuard.closeBlocking(true, WindowStatus.FOREIGN),
            LeaveGuard.closeOnQuickCheck(true, "com.sec.android.app.launcher", isTargetPackage = false)
        )
        assertEquals(
            LeaveGuard.closeBlocking(true, WindowStatus.TARGET),
            LeaveGuard.closeOnQuickCheck(true, "com.google.android.youtube", isTargetPackage = true)
        )
        assertEquals(
            LeaveGuard.closeBlocking(true, WindowStatus.UNAVAILABLE),
            LeaveGuard.closeOnQuickCheck(true, null, isTargetPackage = false)
        )
    }

    @Test
    fun `quick leave check runs well inside the one second watchdog`() {
        assertTrue(LeaveGuard.FAST_CHECK_MS in 50L..300L)
        assertTrue(LeaveGuard.FAST_CHECK_MS < com.example.shortsdetector.DetectionConfig.WATCHDOG_INTERVAL_MS)
    }

    // --- [3] 캐릭터 구조 ---

    @Test
    fun `one character is registered with the requested values`() {
        val kind = Characters.byId("kind")
        assertEquals(listOf("kind"), Characters.ALL.map { it.id })
        assertEquals("다정이", kind.name)
        assertTrue(kind.personality.isNotBlank())
        assertEquals(R.drawable.wenit_character, kind.baseArt)
        assertNull(kind.lv4Art)
        assertEquals(R.drawable.wenit_character, kind.lv4ArtOrBase)
        assertEquals(3, kind.lv4.tapsToClear)
        assertEquals(Lv4Entrance.DROP, kind.lv4.entrance)
        assertEquals(Lv4TapReaction.SHAKE, kind.lv4.tapReaction)
        assertTrue(kind.lines.isEmpty())
    }

    @Test
    fun `ids are unique and an unknown or missing choice reads as the default`() {
        assertEquals(Characters.ALL.size, Characters.ALL.map { it.id }.toSet().size)
        assertEquals(Characters.DEFAULT, Characters.byId(null))
        assertEquals(Characters.DEFAULT, Characters.byId("removed"))
        val p = FakeSharedPreferences()
        assertNull(DetectionPreferences.selectedCharacterId(p))
        DetectionPreferences.setSelectedCharacterId(p, "kind")
        assertEquals("kind", DetectionPreferences.selectedCharacterId(p))
    }

    @Test
    fun `a separate level 4 art is used when there is one`() {
        val withArt = Characters.DEFAULT.copy(lv4Art = R.drawable.badge_common)
        assertEquals(R.drawable.badge_common, withArt.lv4ArtOrBase)
    }

    @Test
    fun `taps count down to zero and at least one tap is needed`() {
        val b = Characters.DEFAULT.lv4
        assertEquals(listOf(3, 2, 1, 0, 0), (0..4).map { Lv4CharacterRules.tapsLeft(b, it) })
        assertFalse(Lv4CharacterRules.cleared(b, 2))
        assertTrue(Lv4CharacterRules.cleared(b, 3))
        assertEquals(1, Lv4CharacterRules.tapsNeeded(b.copy(tapsToClear = 0)))
    }

    @Test
    fun `the bubble is hidden without lines and cycles through them otherwise`() {
        assertNull(Lv4CharacterRules.lineAt(emptyList(), 0))
        val lines = listOf("가", "나")
        assertEquals(listOf("가", "나", "가"), (0..2).map { Lv4CharacterRules.lineAt(lines, it) })
    }

    @Test
    fun `the character is half the screen wide and shrinks when too tall`() {
        assertEquals(540 to 720, Lv4CharacterRules.size(1080, 2340, 1248, 1664))
        val (w, h) = Lv4CharacterRules.size(1080, 1200, 1248, 1664)!!
        assertEquals(480, h)                  // 화면 높이의 40%
        assertTrue(w < 540)
        assertNull(Lv4CharacterRules.size(0, 2340, 10, 10))
        assertNull(Lv4CharacterRules.size(1080, 2340, 0, 10))
    }

    @Test
    fun `dragging out means the center left the screen`() {
        val screen = Box(0, 0, 1080, 2340)
        assertFalse(Lv4CharacterRules.draggedOut(Box(270, 800, 810, 1520), screen))
        assertFalse("절반 안 나감", Lv4CharacterRules.draggedOut(Box(-200, 800, 340, 1520), screen))
        assertTrue(Lv4CharacterRules.draggedOut(Box(-300, 800, 240, 1520), screen))
        assertTrue(Lv4CharacterRules.draggedOut(Box(270, 2000, 810, 2720), screen))
        assertTrue(Lv4CharacterRules.draggedOut(Box(900, 800, 1440, 1520), screen))
    }

    // --- [4] 데이터와 연결 ---

    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 5)
    private fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()

    private fun shown(atMs: Long, level: Int, outcome: InterventionOutcome = CONTINUED) =
        InterventionRecord("c$atMs$level", null, atMs, InterventionMethod.POPUP, 0, 0, level = level, outcome = outcome)

    @Test
    fun `stats list level 4 next to the others`() {
        val ivs = listOf(shown(at(today, 20), 4), shown(at(today, 21), 4, STOPPED), shown(at(today, 22), 3))
        val recent = UsageStatsCalculator.recent(emptyList(), ivs, zone, at(today.plusDays(1), 1))
        assertEquals(listOf(1, 2, 3, 4), recent.levelOutcomes.map { it.level })
        val four = recent.levelOutcomes.single { it.level == 4 }
        assertEquals(1, four.continued)
        assertEquals(1, four.stopped)
        assertEquals(listOf(1, 2, 3, 4), recent.behavior.levelRates.map { it.level })
        assertEquals(2, recent.behavior.levelRates.single { it.level == 4 }.samples)   // 비율은 표본이 적으면 null(기존 규칙)
    }

    @Test
    fun `no max level day follows the default max level 4`() {
        val g = listOf(GoalRules.userGoal(GoalType.NO_MAX_LEVEL, 1))
        val lv3 = listOf(shown(at(today, 20), 3))
        fun status(ivs: List<InterventionRecord>, max: Int) =
            UsageStatsCalculator.closedDay(emptyList(), ivs, g, zone, today, at(today.plusDays(2), 0), max, null).first.goals.single().status
        assertEquals("기본(4)에서는 Lv.3 까지만 본 날은 달성", GoalStatus.ACHIEVED, status(lv3, InterventionPolicyBounds.MAX_LEVEL))
        assertEquals(GoalStatus.EXCEEDED, status(lv3 + shown(at(today, 21), 4), 4))
        assertEquals("최대 3 을 고른 사용자는 전과 같다", GoalStatus.EXCEEDED, status(lv3, 3))
    }

    @Test
    fun `seed A has a few level 4 records, mostly cleared by tap, and B has none`() {
        val now = at(today, 20)
        val a = DemoSeedData.build(DemoSeedData.Scenario.LATE_NIGHT_SKIPPER, today, zone, now).interventions
        val b = DemoSeedData.build(DemoSeedData.Scenario.EASY_STOPPER, today, zone, now).interventions
        val four = a.filter { it.level == 4 }
        assertTrue(four.size in 3..6)
        assertTrue(four.all { it.outcome == CONTINUED && it.dismissal != null })
        assertTrue(four.count { it.dismissal == CharacterDismissal.TAP } > four.size / 2)
        assertTrue("Lv.4 앞에 같은 세션의 Lv.3 계속 보기가 있다", four.all { f ->
            a.any { it.sessionId == f.sessionId && it.level == 3 && it.outcome == CONTINUED && it.occurredAtMs < f.occurredAtMs }
        })
        assertTrue(a.filter { it.level != 4 }.all { it.dismissal == null })
        assertTrue(b.none { it.level == 4 || it.dismissal != null })
    }

    @Test
    fun `schema 10 only adds the dismissal column, the first value is kept`() {
        assertEquals(10, UsageSchema.VERSION)
        assertEquals(listOf("ALTER TABLE interventions ADD COLUMN dismiss_method TEXT"), UsageSchema.MIGRATE_9_TO_10)
        store.insertIntervention(InterventionRecord("x", null, 1, InterventionMethod.POPUP, 0, 0, level = 4))
        store.setInterventionDismissal("x", CharacterDismissal.DRAG)
        store.setInterventionDismissal("x", CharacterDismissal.TAP)
        assertEquals(CharacterDismissal.DRAG, record("x").dismissal)
    }

    // --- 화면 구조(실기기 확인 전 되돌아가지 않게 지킨다) ---

    private val main = File("src/main")
    private fun source(path: String) = File(main, "java/com/example/shortsdetector/$path").readText()

    @Test
    fun `level 4 character appears by window animation and never starts transparent`() {
        val overlay = source("intervention/character/CharacterOverlay.kt")
        assertFalse(overlay.contains(".animate()"))
        assertFalse(Regex("""\.alpha\s*=\s*0f""").containsMatchIn(overlay))
        assertTrue(overlay.contains("windowAnimations = spec.lv4.entrance.windowAnimation"))
        assertTrue("다크 모드 강제 다크 끔", overlay.contains("isForceDarkAllowed = false"))
        val drop = File(main, "res/anim/character_drop_in.xml").readText()
        assertTrue(drop.contains("""android:fromYDelta="-100%p"""") && !drop.contains("fromAlpha"))
        val styles = File(main, "res/values/styles_popup.xml").readText()
        assertTrue(styles.contains("CharacterLv4DropWindowAnimation") && styles.contains("@anim/character_fling_out"))
    }

    @Test
    fun `level 4 scrim blocks touches, lets the video show through, and has the stop button`() {
        val layout = File(main, "res/layout/overlay_intervention_lv4.xml").readText()
        assertTrue(layout.contains("""android:clickable="true""""))
        assertTrue(layout.contains("@color/lv4_scrim"))
        for (id in listOf("popupTitle", "lv4Bubble", "lv4CharacterSlot", "lv4TapsLeft", "popupStop", "popupAllowance", "popupContinue")) {
            assertTrue(id, layout.contains("@+id/$id"))
        }
        val colors = File(main, "res/values/colors.xml").readText()
        val alpha = Regex("""name="lv4_scrim">#([0-9A-Fa-f]{2})""").find(colors)!!.groupValues[1].toInt(16)
        assertTrue("반투명(영상이 비침)", alpha in 0x60..0xB0)
        val popup = source("intervention/InterventionPopup.kt")
        assertTrue("막 창도 강제 다크 끔", popup.contains("v.isForceDarkAllowed = false"))
        assertTrue("캐릭터를 못 띄우면 계속 보기 버튼으로 끝낼 수 있다", popup.contains("R.id.popupContinue).visibility = View.VISIBLE"))
    }
}

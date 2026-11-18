package com.example.shortsdetector.intervention.character

import com.example.shortsdetector.data.DetectionPreferences
import com.example.shortsdetector.data.stats.UsageStatsCalculator
import com.example.shortsdetector.data.usage.DemoSeedData
import com.example.shortsdetector.data.usage.InMemoryUsageStore
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionOutcome.CONTINUED
import com.example.shortsdetector.data.usage.InterventionOutcome.IGNORED
import com.example.shortsdetector.data.usage.InterventionOutcome.STOPPED
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.SessionRecorder
import com.example.shortsdetector.data.usage.UsageClock
import com.example.shortsdetector.data.usage.UsageSchema
import com.example.shortsdetector.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** 16단계: 캐릭터 4종, 대사 규칙, 선택 저장, 탭 수, 캐릭터별 기록·집계, DB 10→11, 시드, 터덜이 말풍선 위치. */
class CharacterStep16Test {

    // --- 캐릭터 정의 ---

    @Test
    fun `four characters with the requested tap counts`() {
        assertEquals(listOf("kind", "chatty", "fierce", "trudge"), Characters.ALL.map { it.id })
        assertEquals(listOf("다정이", "수다쟁이", "표독이", "터덜이"), Characters.ALL.map { it.name })
        assertEquals(listOf(2, 3, 5, 3), Characters.ALL.map { it.lv4.tapsToClear })
        assertEquals(Characters.ALL.size, Characters.ALL.map { it.id }.toSet().size)
        assertTrue(Characters.ALL.all { it.personality.isNotBlank() })
    }

    @Test
    fun `each character moves its own way`() {
        val (kind, chatty, fierce, trudge) = Characters.ALL
        assertEquals(Lv4Idle.WAVE, kind.lv4.idle)
        assertEquals(Lv4Entrance.FLY_IN, chatty.lv4.entrance)
        assertEquals(2_000L, chatty.lv4.lineIntervalMs)
        assertEquals(1.2f, fierce.lv4.maxScale, 0.0001f)
        assertEquals(Lv4Entrance.WALK_IN, trudge.lv4.entrance)
        assertEquals(Lv4Idle.WALK, trudge.lv4.idle)
        assertEquals("터덜이만 걷는다", listOf("trudge"), Characters.ALL.filter { it.lv4.idle == Lv4Idle.WALK }.map { it.id })
    }

    @Test
    fun `every line is short, has no banned words, even with a three digit minute count`() {
        for (c in Characters.ALL) {
            assertTrue("${c.id} 대사 있음", c.lines.isNotEmpty())
            for (line in c.lines) {
                val filled = CharacterLineRules.fill(line, 999 * 60_000L)
                assertTrue("${c.id}: $filled", filled.length <= CharacterLineRules.MAX_LENGTH)
                assertTrue("${c.id}: $line", CharacterLineRules.BANNED.none { it in line })
                assertTrue(CharacterLineRules.isAllowed(line))
            }
        }
    }

    @Test
    fun `extra lines that break the rules are dropped and duplicates kept once`() {
        val spec = Characters.byId("fierce")
        val merged = CharacterLineRules.merged(spec, listOf("바보야 그만 봐", "가".repeat(26), "", spec.lines[0], "물 마시자"))
        assertEquals(spec.lines + "물 마시자", merged)
    }

    @Test
    fun `minutes token is replaced with the watch time and never says zero`() {
        assertEquals("벌써 7분이야!", CharacterLineRules.fill("벌써 {분}분이야!", 7 * 60_000L + 59_000L))
        assertEquals("1분", CharacterLineRules.fill("{분}분", 10_000L))
        assertEquals("125분", CharacterLineRules.fill("{분}분", 125 * 60_000L))
        assertEquals("대사 그대로", CharacterLineRules.fill("대사 그대로", 0L))
    }

    @Test
    fun `picker preview lines skip the minutes token`() {
        for (c in Characters.ALL) {
            val previews = (0 until c.lines.size * 2).mapNotNull { CharacterLineRules.previewLine(c, it) }
            assertTrue(c.id, previews.isNotEmpty())
            assertTrue(c.id, previews.none { CharacterLineRules.MINUTES_TOKEN in it })
        }
    }

    // --- 선택 저장·기본값 ---

    @Test
    fun `existing users without a choice get the default, a choice is saved, unknown ids are not`() {
        val p = FakeSharedPreferences()
        assertNull("16단계 전 사용자: 저장값 없음", DetectionPreferences.selectedCharacterId(p))
        assertEquals("kind", Characters.byId(DetectionPreferences.selectedCharacterId(p)).id)
        DetectionPreferences.setSelectedCharacterId(p, "fierce")
        assertEquals("fierce", DetectionPreferences.selectedCharacterId(p))
        DetectionPreferences.setSelectedCharacterId(p, "removed")
        assertEquals("모르는 id 는 저장하지 않음", "fierce", DetectionPreferences.selectedCharacterId(p))
        DetectionPreferences.clearSelectedCharacter(p)
        assertEquals("온보딩 다시 시작은 다정이로", "kind", Characters.byId(DetectionPreferences.selectedCharacterId(p)).id)
    }

    @Test
    fun `restarting onboarding clears the choice and seeding never touches it`() {
        val repo = File("src/main/java/com/example/shortsdetector/ui/AppRepository.kt").readText()
        val restart = repo.substringAfter("override fun restartOnboarding()").substringBefore("override fun")
        assertTrue(restart.contains("clearSelectedCharacter"))
        val seed = repo.substringAfter("override fun insertSeed(").substringBefore("override fun")
        assertFalse(seed.contains("SelectedCharacter"))
        val seedData = File("src/main/java/com/example/shortsdetector/data/usage/DemoSeedData.kt").readText()
        assertFalse(seedData.contains("DetectionPreferences"))
    }

    // --- 탭 수별 닫힘 ---

    @Test
    fun `each character clears exactly at its own tap count`() {
        for (c in Characters.ALL) {
            val n = c.lv4.tapsToClear
            assertFalse("${c.id} ${n - 1}번", Lv4CharacterRules.cleared(c.lv4, n - 1))
            assertTrue("${c.id} ${n}번", Lv4CharacterRules.cleared(c.lv4, n))
            assertEquals(n - 1, Lv4CharacterRules.tapsLeft(c.lv4, 1))
        }
    }

    @Test
    fun `fierce grows evenly to its max before the last tap, others stay the same size`() {
        val f = Characters.byId("fierce").lv4
        val scales = (0..5).map { Lv4CharacterRules.scaleAfter(f, it) }
        assertEquals(1f, scales[0], 0.0001f)
        assertEquals(1.2f, scales[4], 0.0001f)
        assertEquals("치워진 뒤에도 더 커지지 않음", 1.2f, scales[5], 0.0001f)
        assertTrue(scales.zipWithNext().all { (a, b) -> b >= a })
        for (id in listOf("kind", "chatty", "trudge")) {
            assertEquals(id, 1f, Lv4CharacterRules.scaleAfter(Characters.byId(id).lv4, 2), 0.0001f)
        }
    }

    // --- 기록과 집계 ---

    @Test
    fun `the recorder keeps the character id given at intervention time`() {
        val store = InMemoryUsageStore()
        val recorder = SessionRecorder(store, object : UsageClock {
            override fun epochNowMs() = 1_000L
            override fun elapsedNowMs() = 0L
        })
        val a = recorder.onIntervention(null, InterventionMethod.POPUP, 1, 1, level = 4, characterId = "trudge")
        val b = recorder.onIntervention(null, InterventionMethod.POPUP, 1, 1, level = 2)
        assertEquals("trudge", store.allInterventions().single { it.id == a }.characterId)
        assertNull(store.allInterventions().single { it.id == b }.characterId)
    }

    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 11, 5)
    private fun at(h: Int, m: Int = 0) = today.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private var seq = 0
    private fun iv(level: Int, outcome: InterventionOutcome, characterId: String?) =
        InterventionRecord("r${seq++}", null, at(20, seq), InterventionMethod.POPUP, 0, 0,
            level = level, outcome = outcome, characterId = characterId)

    @Test
    fun `level 4 stop rate is counted per character, only answered level 4 records with known ids`() {
        val ivs = List(3) { iv(4, STOPPED, "fierce") } + List(2) { iv(4, CONTINUED, "fierce") } +
            iv(4, IGNORED, "kind") + iv(4, STOPPED, "kind") +
            iv(4, InterventionOutcome.INTERRUPTED, "chatty") +   // 답하지 않음: 표본 아님
            iv(3, STOPPED, "chatty") +                           // Lv.4 아님
            iv(4, STOPPED, null) + iv(4, STOPPED, "removed")     // 모르는 캐릭터
        val rates = UsageStatsCalculator.recent(emptyList(), ivs, zone, at(23)).behavior.lv4ByCharacter
        assertEquals(Characters.ALL.map { it.id }, rates.map { it.characterId })
        val byId = rates.associateBy { it.characterId }
        assertEquals(5, byId.getValue("fierce").samples)
        assertEquals(0.6, byId.getValue("fierce").stopRate!!, 0.0001)
        assertTrue(byId.getValue("fierce").enough)
        assertEquals(2, byId.getValue("kind").samples)
        assertNull("표본이 적으면 비율 없음", byId.getValue("kind").stopRate)
        assertEquals(0, byId.getValue("chatty").samples)
        assertEquals(0, byId.getValue("trudge").samples)
    }

    // --- DB 10 → 11 ---

    @Test
    fun `schema 11 adds a nullable character column on upgrade and on fresh install`() {
        assertEquals(11, UsageSchema.VERSION)
        assertEquals(listOf("ALTER TABLE interventions ADD COLUMN character_id TEXT"), UsageSchema.MIGRATE_10_TO_11)
        val db = File("src/main/java/com/example/shortsdetector/data/usage/UsageDatabase.kt").readText()
        assertTrue("업그레이드", db.contains("if (oldVersion < 11) UsageSchema.MIGRATE_10_TO_11.forEach(db::execSQL)"))
        val onCreate = db.substringAfter("override fun onCreate(").substringBefore("override fun onUpgrade(")
        assertTrue("새 설치도 같은 열", onCreate.contains("MIGRATE_10_TO_11"))
        assertTrue(db.contains("put(\"character_id\", record.characterId)"))
        assertTrue(db.contains("characterId = str(\"character_id\")"))
    }

    // --- 시드 ---

    @Test
    fun `seed records carry the scenario character, A is fierce and B is kind`() {
        val now = at(20)
        val a = DemoSeedData.build(DemoSeedData.Scenario.LATE_NIGHT_SKIPPER, today, zone, now).interventions
        val b = DemoSeedData.build(DemoSeedData.Scenario.EASY_STOPPER, today, zone, now).interventions
        assertTrue(a.isNotEmpty() && a.all { it.characterId == "fierce" })
        assertTrue(b.isNotEmpty() && b.all { it.characterId == "kind" })
        assertTrue(Characters.isKnown(DemoSeedData.seedCharacterId(DemoSeedData.Scenario.LATE_NIGHT_SKIPPER)))
    }

    // --- 터덜이 말풍선 ---

    @Test
    fun `the bubble follows the walker and stops at the screen edges`() {
        // 막 안쪽 40..1040, 말풍선 폭 300
        assertEquals("가운데", 390, Lv4CharacterRules.bubbleLeft(540, 300, 40, 1040))
        assertEquals("왼쪽 끝에서 멈춤", 40, Lv4CharacterRules.bubbleLeft(-100, 300, 40, 1040))
        assertEquals("오른쪽 끝에서 멈춤", 740, Lv4CharacterRules.bubbleLeft(1300, 300, 40, 1040))
        assertEquals("막보다 넓으면 왼쪽에 붙임", 40, Lv4CharacterRules.bubbleLeft(540, 1200, 40, 1040))
    }

    @Test
    fun `only the walking character moves the bubble`() {
        val popup = File("src/main/java/com/example/shortsdetector/intervention/InterventionPopup.kt").readText()
        assertTrue(popup.contains("override fun onMoved(centerX: Int)"))
        assertTrue(popup.contains("spec.lv4.idle != Lv4Idle.WALK) return"))
        assertTrue("레이아웃 없이 옮긴다", popup.contains("bubble.translationX ="))
    }
}

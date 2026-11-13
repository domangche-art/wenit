package com.example.shortsdetector.data.goal

import com.example.shortsdetector.ai.AiGoalRecommender
import com.example.shortsdetector.data.profile.AgeBand
import com.example.shortsdetector.data.profile.UserProfile
import com.example.shortsdetector.data.profile.WatchTimeSlot
import com.example.shortsdetector.data.stats.UsageStatsCalculator
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.InterventionReturn
import com.example.shortsdetector.data.usage.SessionRecord
import com.example.shortsdetector.data.usage.SessionStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** 13단계 [2]: 새 목표 유형의 판정, 하루·주간 경계, 설정 규칙, 추천. */
class GoalTypesStep13Test {

    private val zone = ZoneId.of("Asia/Seoul")
    private val monday = LocalDate.of(2026, 11, 2)
    private val thursday = monday.plusDays(3)
    private val sunday = monday.plusDays(6)
    private val minute = 60_000L

    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private fun session(start: Long, watchMinutes: Long, id: String = "s$start") = SessionRecord(
        id, "pkg", "yt-2", start, start + watchMinutes * minute, SessionStatus.CLOSED, watchMinutes * minute,
        0, 0, 0, "HIGH", null, null, 0
    )

    private fun stop(atMs: Long, level: Int = 1, back: Long? = null, status: InterventionReturn? = null) = InterventionRecord(
        "i$atMs$level", null, atMs, InterventionMethod.POPUP, 0, 0, level = level, outcome = InterventionOutcome.STOPPED,
        actionAtMs = atMs, returnStatus = status ?: back?.let { InterventionReturn.SHORTS }, shortsReturnMs = back
    )

    private fun shown(atMs: Long, level: Int, outcome: InterventionOutcome = InterventionOutcome.CONTINUED) = InterventionRecord(
        "c$atMs$level", null, atMs, InterventionMethod.POPUP, 0, 0, level = level, outcome = outcome
    )

    private fun goal(type: GoalType, value: Int) = GoalRules.userGoal(type, value)

    private fun today(goals: List<Goal>, now: Long, sessions: List<SessionRecord> = emptyList(), ivs: List<InterventionRecord> = emptyList(), countFrom: LocalDate? = null) =
        UsageStatsCalculator.today(sessions, ivs, goals, zone, now, maxLevel = 3, countFrom = countFrom).goals.single()

    private fun closed(goals: List<Goal>, date: LocalDate, sessions: List<SessionRecord> = emptyList(), ivs: List<InterventionRecord> = emptyList(), countFrom: LocalDate? = null) =
        UsageStatsCalculator.closedDay(sessions, ivs, goals, zone, date, at(date.plusDays(2), 0), 3, countFrom).first.goals.single()

    // --- 설정 규칙 ---

    @Test
    fun `new types have periods groups ranges and labels with their numbers`() {
        assertEquals(GoalPeriod.WEEKLY, GoalType.WEEKLY_TOTAL.period)
        assertEquals(GoalPeriod.WEEKLY, GoalType.WEEKLY_REDUCTION.period)
        assertEquals(GoalPeriod.WEEKLY, GoalType.SHORTS_FREE_DAYS.period)
        assertEquals(GoalPeriod.DAILY, GoalType.MORNING_START.period)
        assertEquals(GoalGroup.TIME_OF_DAY, GoalType.TIME_WINDOW.group)
        for (g in GoalGroup.entries) assertTrue(GoalType.entries.any { it.group == g })
        assertEquals("이번 주 300분 이하로 보기", GoalRules.defaultLabel(GoalType.WEEKLY_TOTAL, 300))
        assertEquals("오전 8:00 전에는 보지 않기", GoalRules.defaultLabel(GoalType.MORNING_START, 480))
        assertEquals("지난주보다 20% 줄이기", GoalRules.defaultLabel(GoalType.WEEKLY_REDUCTION, 20))
        assertFalse(GoalRules.hasValue(GoalType.NO_MAX_LEVEL))
        assertEquals(1, GoalRules.userGoal(GoalType.NO_MAX_LEVEL, 99).value)
        assertEquals(7, GoalRules.userGoal(GoalType.SHORTS_FREE_DAYS, 10).value)
        assertEquals(6 * 60, GoalRules.morningValue(3, 0))       // 범위 밖 시각은 잘린다
        assertEquals(24 * 60, GoalRules.timeValue(GoalType.TIME_WINDOW, 0, 0)) // 자정 프리셋 = 이어지는 분 1440
    }

    @Test
    fun `still at most three goals and no duplicate type`() {
        var list = listOf(goal(GoalType.WEEKLY_TOTAL, 300), goal(GoalType.MORNING_START, 480), goal(GoalType.LV1_STOPS, 2))
        assertNull(GoalRules.put(list, goal(GoalType.SHORTS_FREE_DAYS, 2)))
        list = GoalRules.put(list, goal(GoalType.WEEKLY_TOTAL, 200))!!
        assertEquals(3, list.size)
        assertEquals(200, list.first { it.type == GoalType.WEEKLY_TOTAL }.value)
    }

    // --- 하루 목표 ---

    @Test
    fun `morning goal counts only watching from 5am to the cutoff`() {
        val g = listOf(goal(GoalType.MORNING_START, 480))
        val early = session(at(thursday, 4, 0), 30)          // 05:00 전: 밤 목표 몫
        assertEquals(GoalStatus.ACHIEVED, closed(g, thursday, listOf(early)).status)
        val inside = session(at(thursday, 7, 50), 20)        // 07:50~08:10 중 10분이 구간 안
        val p = closed(g, thursday, listOf(inside))
        assertEquals(GoalStatus.EXCEEDED, p.status)
        assertEquals(10, p.currentValue)
        assertEquals(GoalStatus.ON_TRACK, today(g, at(thursday, 7, 0)).status)
    }

    @Test
    fun `lv1 stops count only level one stops and are achieved at once`() {
        val g = listOf(goal(GoalType.LV1_STOPS, 2))
        val now = at(thursday, 15)
        val ivs = listOf(stop(at(thursday, 9), 1), stop(at(thursday, 10), 2), shown(at(thursday, 11), 1))
        assertEquals(GoalStatus.ON_TRACK, today(g, now, ivs = ivs).status)
        assertEquals(1, today(g, now, ivs = ivs).currentValue)
        assertEquals(GoalStatus.ACHIEVED, today(g, now, ivs = ivs + stop(at(thursday, 12), 1)).status)
        assertEquals(GoalStatus.MISSED, closed(g, thursday, ivs = ivs).status)
    }

    @Test
    fun `not returning for 30 minutes counts only when confirmed`() {
        val stoppedAt = at(thursday, 14)
        val g = listOf(goal(GoalType.NO_RETURN_30M, 1))
        // 10분 뒤 돌아옴 → 세지 않음
        assertEquals(0, today(g, stoppedAt + 60 * minute, ivs = listOf(stop(stoppedAt, back = 10 * minute))).currentValue)
        // 45분 뒤 돌아옴 → 30분은 참았다
        assertEquals(1, today(g, stoppedAt + 60 * minute, ivs = listOf(stop(stoppedAt, back = 45 * minute))).currentValue)
        // 아직 결과 없음: 20분 지났으면 미확정, 31분 지났으면 확정
        assertEquals(0, today(g, stoppedAt + 20 * minute, ivs = listOf(stop(stoppedAt))).currentValue)
        assertEquals(GoalStatus.ACHIEVED, today(g, stoppedAt + 31 * minute, ivs = listOf(stop(stoppedAt))).status)
        // 하루 동안 안 돌아옴(NONE) → 셈
        assertEquals(1, today(g, stoppedAt + 5 * minute, ivs = listOf(stop(stoppedAt, status = InterventionReturn.NONE))).currentValue)
    }

    @Test
    fun `no max level day breaks as soon as the max level is shown`() {
        val g = listOf(goal(GoalType.NO_MAX_LEVEL, 1))
        val ivs = listOf(shown(at(thursday, 20), 2))
        assertEquals(GoalStatus.ON_TRACK, today(g, at(thursday, 21), ivs = ivs).status)
        assertEquals(GoalStatus.ACHIEVED, closed(g, thursday, ivs = ivs).status)
        assertEquals(GoalStatus.EXCEEDED, today(g, at(thursday, 22), ivs = ivs + shown(at(thursday, 21), 3)).status)
        // 최대 단계를 2로 낮췄다면 Lv.2 도 최대 단계다
        val lowered = UsageStatsCalculator.today(emptyList(), ivs, g, zone, at(thursday, 21), maxLevel = 2).goals.single()
        assertEquals(GoalStatus.EXCEEDED, lowered.status)
    }

    // --- 주간 목표 ---

    @Test
    fun `weekly total runs monday to sunday and is achieved only when sunday ends`() {
        val g = listOf(goal(GoalType.WEEKLY_TOTAL, 100))
        val week = listOf(session(at(monday, 20), 30), session(at(thursday, 20), 30))
        val lastSunday = session(at(monday.minusDays(1), 20), 90)      // 지난주 일요일은 이번 주에 들어가지 않는다
        val mid = today(g, at(thursday, 22), week + lastSunday)
        assertEquals(GoalStatus.ON_TRACK, mid.status)
        assertEquals(60, mid.currentValue)
        assertEquals(GoalStatus.ON_TRACK, closed(g, thursday, week).status) // 목요일이 끝나도 주는 아직이다
        assertEquals(GoalStatus.ACHIEVED, closed(g, sunday, week).status)
        assertEquals(GoalStatus.EXCEEDED, today(g, at(sunday, 22), week + session(at(sunday, 20), 50)).status)
    }

    @Test
    fun `weekly reduction compares with the whole last week and needs last week records`() {
        val g = listOf(goal(GoalType.WEEKLY_REDUCTION, 20))
        val last = listOf(session(at(monday.minusDays(6), 20), 60), session(at(monday.minusDays(2), 20), 40)) // 지난주 100분
        val noBase = today(g, at(thursday, 22), listOf(session(at(monday, 20), 10)))
        assertEquals(GoalStatus.UNAVAILABLE, noBase.status)
        val ok = today(g, at(thursday, 22), last + session(at(monday, 20), 70))
        assertEquals(GoalStatus.ON_TRACK, ok.status)
        assertEquals(80, ok.target)                        // 100분의 80%
        assertEquals(70, ok.currentValue)
        assertEquals(GoalStatus.EXCEEDED, today(g, at(thursday, 22), last + session(at(monday, 20), 81)).status)
        assertEquals(GoalStatus.ACHIEVED, closed(g, sunday, last + session(at(monday, 20), 70)).status)
    }

    @Test
    fun `shorts free days count finished days and not days before tracking began`() {
        val g = listOf(goal(GoalType.SHORTS_FREE_DAYS, 2))
        val sessions = listOf(session(at(monday, 20), 10), session(at(thursday, 20), 10))
        // 목요일 저녁: 끝난 날은 월·화·수 중 화·수가 0분 → 2일
        assertEquals(GoalStatus.ACHIEVED, today(g, at(thursday, 22), sessions).status)
        // 수요일부터 셌다면 수요일 하루뿐
        val late = today(g, at(thursday, 22), sessions, countFrom = monday.plusDays(2))
        assertEquals(1, late.currentValue)
        assertEquals(GoalStatus.ON_TRACK, late.status)
        // 일요일이 끝났는데 못 채웠으면 놓침
        val busy = (0L..6L).map { session(at(monday.plusDays(it), 20), 5) }
        assertEquals(GoalStatus.MISSED, closed(g, sunday, busy).status)
    }

    @Test
    fun `a session over midnight splits between the two days`() {
        val g = listOf(goal(GoalType.DAILY_TOTAL, 20))
        val s = session(at(thursday, 23, 40), 40)   // 23:40~00:20 → 목 20분, 금 20분
        assertEquals(GoalStatus.ACHIEVED, closed(g, thursday, listOf(s)).status)
        assertEquals(20, closed(g, thursday.plusDays(1), listOf(s)).currentValue)
    }

    // --- 추천 ---

    @Test
    fun `presets use new types where the survey points to them`() {
        val morning = PresetGoalRecommender.recommend(UserProfile(AgeBand.TWENTIES, watchTimeSlots = setOf(WatchTimeSlot.MORNING)))
        assertEquals(GoalType.MORNING_START, morning.first().type)
        assertEquals(9 * 60, morning.first().value)
        val fewDays = PresetGoalRecommender.recommend(UserProfile(AgeBand.TWENTIES, watchDays = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)))
        assertEquals(5, fewDays.first { it.type == GoalType.SHORTS_FREE_DAYS }.value)
        assertTrue(PresetGoalRecommender.recommend(null).any { it.type == GoalType.LV1_STOPS })
    }

    @Test
    fun `ai output with new types is accepted and clamped, schema lists them`() {
        val json = JSONObject().put(
            "goals", JSONArray()
                .put(JSONObject().put("type", "WEEKLY_TOTAL").put("value", 5000).put("label", "주간").put("reason", "이유"))
                .put(JSONObject().put("type", "NO_MAX_LEVEL").put("value", 3).put("label", "최대 단계").put("reason", "이유"))
                .put(JSONObject().put("type", "MORNING_START").put("value", 480).put("label", "오전 8:00 전에는 보지 않기").put("reason", "이유"))
        )
        val goals = AiGoalRecommender.parseGoals(json)
        assertEquals(listOf(GoalType.WEEKLY_TOTAL, GoalType.NO_MAX_LEVEL, GoalType.MORNING_START), goals.map { it.type })
        assertEquals(1680, goals[0].value)
        assertEquals(1, goals[1].value)
        val enum = AiGoalRecommender.SCHEMA.toString()
        for (t in GoalType.entries) assertTrue(t.name in enum)
    }
}

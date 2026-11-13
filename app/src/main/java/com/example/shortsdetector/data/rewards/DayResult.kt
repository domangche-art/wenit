package com.example.shortsdetector.data.rewards

import java.time.LocalDate

/**
 * 끝난 하루의 판정 결과. 자정이 지난 뒤 한 번 만들고([RewardsUpdater]) DB 에 남긴다.
 * 사용 기록은 보관 기간이 지나면 지워지지만, 연속 달성·누적 배지는 이 행으로 계속 셀 수 있다.
 * 원본 기록·화면 내용은 담지 않는다. 날짜와 숫자·참거짓만 있다.
 *
 * @param dailyGoals 그날 판정한 하루 목표 수. 0 이면 "목표 없는 날"이라 연속 달성에 세지도 끊지도 않는다.
 * @param weeklyGoals 일요일 행에만 있다. 그 주의 주간 목표 판정 결과.
 * @param nightClean 밤 시간대(23:00~05:00, [NIGHT_CUTOFF_VALUE])에 보지 않았다
 * @param savedMs 기준값 - 그날 시청시간(0 미만은 0). 기준값이 없으면 0.
 * @param isSeed 발표용 시드 행. 실제 배지·연속 판정에 쓰지 않는다.
 */
data class DayResult(
    val date: LocalDate,
    val dailyGoals: Int,
    val dailyAchieved: Int,
    val weeklyGoals: Int = 0,
    val weeklyAchieved: Int = 0,
    val watchMs: Long = 0L,
    val selfStops: Int = 0,
    val lv1Stops: Int = 0,
    val noReturnStops: Int = 0,
    val nightClean: Boolean = false,
    val shortsFree: Boolean = false,
    val savedMs: Long = 0L,
    val isSeed: Boolean = false
) {
    /** 하루 목표를 모두 지킨 날. 목표가 없던 날은 null. */
    val achievedDay: Boolean? get() = if (dailyGoals == 0) null else dailyAchieved >= dailyGoals

    companion object {
        /** 밤 시간대의 시작(23:00). TIME_WINDOW 와 같은 값 체계이고 05:00 에 풀린다. */
        const val NIGHT_CUTOFF_VALUE = 23 * 60
    }
}

data class Streak(val current: Int, val best: Int)

/**
 * 연속 달성. 순수 함수.
 *  - 하루 목표를 모두 지킨 날만 센다. 목표가 없던 날(행이 있지만 dailyGoals = 0)과 행이 없는 날은 건너뛴다(끊지 않음).
 *  - [Streak.current] 는 가장 최근 판정한 날부터 거꾸로 센다. 오늘은 아직 끝나지 않아 들어가지 않는다.
 */
object StreakCalculator {

    fun of(rows: List<DayResult>): Streak {
        val judged = rows.filter { it.achievedDay != null }.sortedBy { it.date }
        var best = 0
        var run = 0
        for (r in judged) {
            run = if (r.achievedDay == true) run + 1 else 0
            best = maxOf(best, run)
        }
        return Streak(current = run, best = best)
    }

    /**
     * 화면·에이전트용: 시드 행이 있으면 같은 날짜의 실제 행 대신 시드 행을 쓴다(시연 때 진열장이 비지 않게).
     * 실제 배지 판정에는 쓰지 않는다.
     */
    fun mergedForDisplay(rows: List<DayResult>): List<DayResult> {
        val seed = rows.filter { it.isSeed }
        if (seed.isEmpty()) return rows
        val seedDates = seed.map { it.date }.toSet()
        return rows.filter { it.isSeed || it.date !in seedDates }
    }
}

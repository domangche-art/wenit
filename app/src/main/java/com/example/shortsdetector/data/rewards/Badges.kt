package com.example.shortsdetector.data.rewards

/** 배지 등급. 기준값 목록의 순서와 같다(첫 기준 = 브론즈). */
enum class BadgeTier { BRONZE, SILVER, GOLD }

/** 배지가 세는 값. 모두 규칙으로 계산하고 AI 를 쓰지 않는다. */
enum class BadgeMetric {
    /** "그만 볼게"(스스로 멈춤) 누적 횟수 */
    STOPS,
    /** 목표를 달성한 횟수(하루 목표·주간 목표) */
    GOALS_ACHIEVED,
    /** 최고 연속 달성 일수 */
    BEST_STREAK,
    /** 아낀 시간 누적(시간 단위, 내림) */
    SAVED_HOURS,
    /** 밤 시간대에 보지 않은 날 수 */
    NIGHT_CLEAN_DAYS,
    /** Lv.1 에서 바로 멈춘 횟수 */
    LV1_STOPS,
    /** 멈춘 뒤 30분 안에 다시 켜지 않은 횟수 */
    NO_RETURN_STOPS,
    /** 쇼츠를 전혀 보지 않은 날 수 */
    SHORTS_FREE_DAYS
}

/**
 * 배지 하나. 기준값이 하나면 등급이 하나(브론즈)뿐인 배지다.
 * @param unit 진행률 문구의 단위
 */
data class BadgeDef(
    val id: String,
    val name: String,
    val metric: BadgeMetric,
    val thresholds: List<Int>,
    val condition: String,
    val unit: String
) {
    init {
        require(thresholds.isNotEmpty() && thresholds.size <= BadgeTier.entries.size) { "thresholds" }
        require(thresholds.zipWithNext().all { (a, b) -> a < b }) { "ascending" }
    }

    fun tierOf(index: Int): BadgeTier = BadgeTier.entries[index]

    /** [value] 로 얻을 수 있는 가장 높은 등급 번호. 하나도 못 얻으면 -1. */
    fun reachedIndex(value: Int): Int = thresholds.indexOfLast { value >= it }

    /** 등급 [index] 의 조건 문구 */
    fun conditionAt(index: Int): String = condition.replace("{n}", thresholds[index].toString())
}

/**
 * 배지 목록. 새 배지는 여기에만 추가한다. id 는 DB 와 그림 연결([com.example.shortsdetector.ui.BadgeArt])에 쓰므로 바꾸지 않는다.
 * 기준값은 임시값이다(사용자 연구로 다시 정한다).
 */
object BadgeCatalog {

    val ALL: List<BadgeDef> = listOf(
        BadgeDef("first_stop", "첫걸음", BadgeMetric.STOPS, listOf(1), "처음으로 \"그만 볼게\"를 골라요", "회"),
        BadgeDef("first_goal", "첫 목표", BadgeMetric.GOALS_ACHIEVED, listOf(1), "처음으로 목표를 달성해요", "회"),
        BadgeDef("steady", "꾸준함", BadgeMetric.BEST_STREAK, listOf(3, 7, 14), "{n}일 연속으로 목표를 지켜요", "일"),
        BadgeDef("time_rich", "시간 부자", BadgeMetric.SAVED_HOURS, listOf(1, 5, 10), "아낀 시간이 모두 {n}시간이 돼요", "시간"),
        BadgeDef("self_stop", "스스로 멈춤", BadgeMetric.STOPS, listOf(10, 30, 100), "\"그만 볼게\"를 모두 {n}번 골라요", "회"),
        BadgeDef("night_guard", "밤 지킴이", BadgeMetric.NIGHT_CLEAN_DAYS, listOf(3, 7, 14), "밤 11시~새벽 5시에 안 본 날이 {n}일이 돼요", "일"),
        BadgeDef("first_signal", "첫 신호에 멈춤", BadgeMetric.LV1_STOPS, listOf(5, 20, 50), "첫 알림(Lv.1)에서 바로 멈춘 횟수가 {n}번이 돼요", "회"),
        BadgeDef("no_return", "돌아오지 않기", BadgeMetric.NO_RETURN_STOPS, listOf(5, 20, 50), "멈춘 뒤 30분 안에 다시 켜지 않은 횟수가 {n}번이 돼요", "회"),
        BadgeDef("shorts_free", "쇼츠 없는 날", BadgeMetric.SHORTS_FREE_DAYS, listOf(1, 3, 7), "쇼츠를 아예 안 본 날이 {n}일이 돼요", "일")
    )

    fun byId(id: String): BadgeDef? = ALL.firstOrNull { it.id == id }
}

/** 배지 판정에 쓰는 누적값 */
data class BadgeTotals(
    val stops: Int = 0,
    val goalsAchieved: Int = 0,
    val bestStreak: Int = 0,
    val savedMs: Long = 0L,
    val nightCleanDays: Int = 0,
    val lv1Stops: Int = 0,
    val noReturnStops: Int = 0,
    val shortsFreeDays: Int = 0
) {
    fun valueOf(metric: BadgeMetric): Int = when (metric) {
        BadgeMetric.STOPS -> stops
        BadgeMetric.GOALS_ACHIEVED -> goalsAchieved
        BadgeMetric.BEST_STREAK -> bestStreak
        BadgeMetric.SAVED_HOURS -> (savedMs / MS_PER_HOUR).toInt()
        BadgeMetric.NIGHT_CLEAN_DAYS -> nightCleanDays
        BadgeMetric.LV1_STOPS -> lv1Stops
        BadgeMetric.NO_RETURN_STOPS -> noReturnStops
        BadgeMetric.SHORTS_FREE_DAYS -> shortsFreeDays
    }

    operator fun plus(o: BadgeTotals) = BadgeTotals(
        stops + o.stops, goalsAchieved + o.goalsAchieved, maxOf(bestStreak, o.bestStreak), savedMs + o.savedMs,
        nightCleanDays + o.nightCleanDays, lv1Stops + o.lv1Stops, noReturnStops + o.noReturnStops, shortsFreeDays + o.shortsFreeDays
    )

    companion object {
        const val MS_PER_HOUR = 3_600_000L

        /** 끝난 날 행들의 합. 연속 달성은 행 전체에서 다시 계산한다. */
        fun fromDays(rows: List<DayResult>) = BadgeTotals(
            stops = rows.sumOf { it.selfStops },
            goalsAchieved = rows.sumOf { it.dailyAchieved + it.weeklyAchieved },
            bestStreak = StreakCalculator.of(rows).best,
            savedMs = rows.sumOf { it.savedMs },
            nightCleanDays = rows.count { it.nightClean },
            lv1Stops = rows.sumOf { it.lv1Stops },
            noReturnStops = rows.sumOf { it.noReturnStops },
            shortsFreeDays = rows.count { it.shortsFree }
        )
    }
}

/**
 * 얻은 배지 한 등급. 등급마다 행 하나라 등급별 얻은 날짜가 남는다.
 * @param seen 진열장에서 봤다(탭의 새 배지 점 표시)
 * @param celebrated 축하 화면을 보여 줬다
 * @param notified 앱이 닫혀 있을 때 알림을 보냈다
 */
data class EarnedBadge(
    val id: String,
    val tier: Int,
    val earnedAtMs: Long,
    val isSeed: Boolean = false,
    val seen: Boolean = false,
    val celebrated: Boolean = false,
    val notified: Boolean = false
)

/**
 * 배지 판정. 순수 함수. 한 번 얻은 배지는 기록을 지우기 전까지 남는다(판정이 거두어 가지 않는다).
 */
object BadgeEngine {

    /** [totals] 로 새로 얻는 등급들. [earned] 는 이미 얻은 (id, 등급) 이다. 여러 등급을 한꺼번에 얻을 수 있다. */
    fun newlyEarned(totals: BadgeTotals, earned: Set<Pair<String, Int>>, nowMs: Long): List<EarnedBadge> =
        BadgeCatalog.ALL.flatMap { def ->
            (0..def.reachedIndex(totals.valueOf(def.metric)))
                .filter { (def.id to it) !in earned }
                .map { EarnedBadge(def.id, it, nowMs) }
        }
}

/**
 * 진열장 한 칸.
 * @param tierIndex 얻은 가장 높은 등급. 못 얻었으면 -1.
 * @param value 지금 값
 * @param nextThreshold 다음 등급 기준. 모두 얻었으면 null.
 * @param earnedAt 등급 번호 → 얻은 시각
 */
data class BadgeProgress(
    val def: BadgeDef,
    val tierIndex: Int,
    val value: Int,
    val nextThreshold: Int?,
    val earnedAt: Map<Int, Long>,
    val isNew: Boolean,
    val fromSeed: Boolean
) {
    val earned: Boolean get() = tierIndex >= 0

    companion object {
        /**
         * @param badges 실제·시드 배지 모두. 화면에는 둘을 합쳐 보여 준다(시연 때 진열장이 비지 않게).
         * @param totals 실제 기록의 누적값. 시드는 얻은 등급만 있고 값이 없으므로, 시드 배지는 값을 그 등급 기준값 이상으로 보여 준다.
         */
        fun of(badges: List<EarnedBadge>, totals: BadgeTotals): List<BadgeProgress> = BadgeCatalog.ALL.map { def ->
            val mine = badges.filter { it.id == def.id && it.tier in def.thresholds.indices }
            val top = mine.maxOfOrNull { it.tier } ?: -1
            val value = maxOf(totals.valueOf(def.metric), if (top >= 0) def.thresholds[top] else 0)
            BadgeProgress(
                def = def,
                tierIndex = top,
                value = value,
                nextThreshold = def.thresholds.getOrNull(top + 1),
                earnedAt = mine.groupBy { it.tier }.mapValues { (_, v) -> v.minOf { it.earnedAtMs } },
                isNew = mine.any { !it.seen && !it.isSeed },
                fromSeed = mine.isNotEmpty() && mine.all { it.isSeed }
            )
        }
    }
}

package com.example.shortsdetector.data.goal

/** 목표를 판정하는 기간. 하루 목표만 연속 달성에 들어간다. */
enum class GoalPeriod { DAILY, WEEKLY }

/** 목표 설정 화면의 묶음. 고르기 쉽게 나눌 뿐 판정에는 쓰지 않는다. */
enum class GoalGroup { REDUCE_TIME, TIME_OF_DAY, BREAK_HABIT, INTERVENTION_RESPONSE }

/**
 * 앱이 측정할 수 있는 목표 유형. [Goal.value] 의 단위가 유형마다 다르다.
 * 모두 지금 모으는 데이터(세션 시각·길이, 개입 단계·반응, 복귀 간격)만으로 판정한다.
 *
 * TIME_WINDOW 의 값은 "자정을 넘겨 이어지는 분"이다: 23:00 = 1380, 다음 날 01:00 = 1500.
 * 저녁부터 새벽까지가 한 줄로 이어져 범위 자르기가 단순해진다([GoalRules.timeWindowValue] 로 만든다).
 * "밤 N시 이후 안 보기"는 같은 판정이라 새 유형을 만들지 않고 TIME_WINDOW 에 취침 전 프리셋을 둔다(13단계).
 * MORNING_START 의 값은 그날 0시부터의 분이다: 08:00 = 480.
 */
enum class GoalType(val period: GoalPeriod, val group: GoalGroup) {
    /** 하루 총 시청 분 이하 */
    DAILY_TOTAL(GoalPeriod.DAILY, GoalGroup.REDUCE_TIME),
    /** 한 번에 보는 분 이하 */
    PER_SESSION(GoalPeriod.DAILY, GoalGroup.REDUCE_TIME),
    /** 이 시각 이후 시청 안 함(밤) */
    TIME_WINDOW(GoalPeriod.DAILY, GoalGroup.TIME_OF_DAY),
    /** 하루 시청 세션 수 이하 */
    DAILY_OPENS(GoalPeriod.DAILY, GoalGroup.BREAK_HABIT),
    /** 하루 스스로 멈춘 횟수 이상 */
    SELF_STOPS(GoalPeriod.DAILY, GoalGroup.INTERVENTION_RESPONSE),

    // --- 13단계 ---

    /** 주간(월~일) 총 시청 분 이하 */
    WEEKLY_TOTAL(GoalPeriod.WEEKLY, GoalGroup.REDUCE_TIME),
    /** 지난주(월~일) 합계보다 N% 이상 줄이기 */
    WEEKLY_REDUCTION(GoalPeriod.WEEKLY, GoalGroup.REDUCE_TIME),
    /** 05:00 부터 이 시각 전까지 시청 안 함(아침) */
    MORNING_START(GoalPeriod.DAILY, GoalGroup.TIME_OF_DAY),
    /** 스스로 멈춘 뒤 30분 동안 쇼츠로 돌아오지 않은 횟수 이상 */
    NO_RETURN_30M(GoalPeriod.DAILY, GoalGroup.BREAK_HABIT),
    /** Lv.1 개입에서 바로 멈춘 횟수 이상 */
    LV1_STOPS(GoalPeriod.DAILY, GoalGroup.INTERVENTION_RESPONSE),
    /** 그날 최대 개입 단계까지 가지 않기. 값은 쓰지 않는다(항상 1) */
    NO_MAX_LEVEL(GoalPeriod.DAILY, GoalGroup.INTERVENTION_RESPONSE),
    /** 이번 주 쇼츠를 전혀 보지 않은 날이 N일 이상 */
    SHORTS_FREE_DAYS(GoalPeriod.WEEKLY, GoalGroup.BREAK_HABIT)
}

enum class GoalSource { AI, PRESET, USER }

/** 목표 하나. 유형당 하나만 둘 수 있어 [type] 이 곧 식별자다([GoalRules.put]). */
data class Goal(
    val type: GoalType,
    val value: Int,
    /** 화면 표시 문구 */
    val label: String,
    /** 추천 이유. 직접 만든 목표는 null. */
    val reason: String? = null,
    val source: GoalSource
)

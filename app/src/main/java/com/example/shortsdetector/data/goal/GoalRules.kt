package com.example.shortsdetector.data.goal

/**
 * 목표 값 범위와 검증. 순수 함수. AI 출력도 저장 전에 [sanitize] / [normalize] 를 지난다.
 *
 * 범위 값은 임시값이다(사용자 연구로 다시 정한다). 문구는 AI·저장값과 같은 층에서 다뤄야 해서 코드에 둔다.
 */
object GoalRules {

    const val MAX_GOALS = 3
    const val MAX_LABEL_LENGTH = 40
    const val MAX_REASON_LENGTH = 120

    private const val MINUTES_PER_DAY = 24 * 60

    val DAILY_TOTAL_RANGE = 5..240
    val PER_SESSION_RANGE = 1..60

    /** 18:00 ~ 다음 날 03:00 */
    val TIME_WINDOW_RANGE = (18 * 60)..(27 * 60)
    val DAILY_OPENS_RANGE = 1..30
    val SELF_STOPS_RANGE = 1..10

    // --- 13단계 ---
    val WEEKLY_TOTAL_RANGE = 30..1680
    val WEEKLY_REDUCTION_RANGE = 5..50

    /** 06:00 ~ 12:00. 아침 구간은 05:00 부터다([MORNING_FROM_MINUTE]). */
    val MORNING_START_RANGE = (6 * 60)..(12 * 60)
    val NO_RETURN_30M_RANGE = 1..10
    val LV1_STOPS_RANGE = 1..10
    val NO_MAX_LEVEL_RANGE = 1..1
    val SHORTS_FREE_DAYS_RANGE = 1..7

    /** 아침 목표 구간의 시작(05:00). 밤 목표(TIME_WINDOW)의 제한이 풀리는 시각과 같다. */
    const val MORNING_FROM_MINUTE = 5 * 60

    /** 설정 화면 프리셋: 취침 전(밤 N시 이후 안 보기) */
    val NIGHT_PRESET_HOURS = listOf(22, 23, 24)

    /** 설정 화면 프리셋: 아침(오전 N시 전에는 안 보기) */
    val MORNING_PRESET_HOURS = listOf(7, 8, 9)

    /** 0~5시는 전날에서 이어지는 시각으로 본다. */
    private const val EARLY_MORNING_END_HOUR = 6

    fun rangeOf(type: GoalType): IntRange = when (type) {
        GoalType.DAILY_TOTAL -> DAILY_TOTAL_RANGE
        GoalType.PER_SESSION -> PER_SESSION_RANGE
        GoalType.TIME_WINDOW -> TIME_WINDOW_RANGE
        GoalType.DAILY_OPENS -> DAILY_OPENS_RANGE
        GoalType.SELF_STOPS -> SELF_STOPS_RANGE
        GoalType.WEEKLY_TOTAL -> WEEKLY_TOTAL_RANGE
        GoalType.WEEKLY_REDUCTION -> WEEKLY_REDUCTION_RANGE
        GoalType.MORNING_START -> MORNING_START_RANGE
        GoalType.NO_RETURN_30M -> NO_RETURN_30M_RANGE
        GoalType.LV1_STOPS -> LV1_STOPS_RANGE
        GoalType.NO_MAX_LEVEL -> NO_MAX_LEVEL_RANGE
        GoalType.SHORTS_FREE_DAYS -> SHORTS_FREE_DAYS_RANGE
    }

    /** 값을 입력받지 않는 유형(값이 하나뿐) */
    fun hasValue(type: GoalType): Boolean = rangeOf(type).let { it.first != it.last }

    /** 시각으로 입력받는 유형 */
    fun isTimeOfDay(type: GoalType): Boolean = type == GoalType.TIME_WINDOW || type == GoalType.MORNING_START

    /** 시각 입력(0~23시) → MORNING_START 값. 범위 밖 시각은 잘린다. */
    fun morningValue(hour: Int, minute: Int): Int =
        clampValue(GoalType.MORNING_START, hour.coerceIn(0, 23) * 60 + minute.coerceIn(0, 59))

    /** 시각 입력 → 유형에 맞는 값 */
    fun timeValue(type: GoalType, hour: Int, minute: Int): Int =
        if (type == GoalType.MORNING_START) morningValue(hour, minute) else timeWindowValue(hour, minute)

    fun clampValue(type: GoalType, value: Int): Int = rangeOf(type).let { value.coerceIn(it.first, it.last) }

    /** 시각 입력(0~23시) → TIME_WINDOW 값. 범위 밖 시각은 잘린다. */
    fun timeWindowValue(hour: Int, minute: Int): Int {
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val value = h * 60 + m + if (h < EARLY_MORNING_END_HOUR) MINUTES_PER_DAY else 0
        return clampValue(GoalType.TIME_WINDOW, value)
    }

    /**
     * 값을 범위 안으로 자르고 문구를 정리한다.
     * 값이 잘렸으면 원래 문구는 이제 틀린 숫자를 말하므로 버리고 기본 문구로 바꾼다.
     */
    fun sanitize(goal: Goal): Goal {
        val value = clampValue(goal.type, goal.value)
        val label = goal.label.trim()
        return goal.copy(
            value = value,
            label = if (value != goal.value || label.isEmpty()) defaultLabel(goal.type, value) else label.take(MAX_LABEL_LENGTH),
            reason = goal.reason?.trim()?.take(MAX_REASON_LENGTH)?.ifEmpty { null }
        )
    }

    /** 목록 전체 검증: 값 자르기, 같은 유형은 앞의 것만, 최대 [MAX_GOALS] 개. */
    fun normalize(goals: List<Goal>): List<Goal> =
        goals.map(::sanitize).distinctBy { it.type }.take(MAX_GOALS)

    /** 직접 입력: 유형 + 숫자/시각. 자유 문장은 받지 않는다. */
    fun userGoal(type: GoalType, value: Int): Goal {
        val v = clampValue(type, value)
        return Goal(type, v, defaultLabel(type, v), source = GoalSource.USER)
    }

    /** 같은 유형이 있으면 바꾸고, 없으면 추가한다. 이미 [MAX_GOALS] 개면 null. */
    fun put(goals: List<Goal>, goal: Goal): List<Goal>? {
        val clean = sanitize(goal)
        return when {
            goals.any { it.type == clean.type } -> goals.map { if (it.type == clean.type) clean else it }
            goals.size >= MAX_GOALS -> null
            else -> goals + clean
        }
    }

    /** [oldType] 목표를 [goal] 로 바꾼다(유형 변경 포함). 새 유형의 기존 목표는 사라진다. 없는 목표면 [put] 과 같다. */
    fun replace(goals: List<Goal>, oldType: GoalType, goal: Goal): List<Goal>? {
        if (goals.none { it.type == oldType }) return put(goals, goal)
        val clean = sanitize(goal)
        return goals.filter { it.type == oldType || it.type != clean.type }
            .map { if (it.type == oldType) clean else it }
    }

    fun remove(goals: List<Goal>, type: GoalType): List<Goal> = goals.filterNot { it.type == type }

    fun defaultLabel(type: GoalType, value: Int): String = when (type) {
        GoalType.DAILY_TOTAL -> "하루 ${value}분 이하로 보기"
        GoalType.PER_SESSION -> "한 번에 ${value}분까지만 보기"
        GoalType.TIME_WINDOW -> "${formatTimeWindow(value)} 이후에는 보지 않기"
        GoalType.DAILY_OPENS -> "하루 ${value}번까지만 보기"
        GoalType.SELF_STOPS -> "하루 ${value}번 이상 스스로 멈추기"
        GoalType.WEEKLY_TOTAL -> "이번 주 ${value}분 이하로 보기"
        GoalType.WEEKLY_REDUCTION -> "지난주보다 ${value}% 줄이기"
        GoalType.MORNING_START -> "오전 ${formatTimeWindow(value)} 전에는 보지 않기"
        GoalType.NO_RETURN_30M -> "멈춘 뒤 30분 참기 하루 ${value}번"
        GoalType.LV1_STOPS -> "첫 알림에서 바로 멈추기 하루 ${value}번"
        GoalType.NO_MAX_LEVEL -> "마지막 단계 개입까지 가지 않기"
        GoalType.SHORTS_FREE_DAYS -> "쇼츠 안 보는 날 주 ${value}일"
    }

    /** 1380 → "23:00", 1500 → "새벽 1:00" */
    fun formatTimeWindow(value: Int): String {
        val time = "${value / 60 % 24}:${(value % 60).toString().padStart(2, '0')}"
        return if (value >= MINUTES_PER_DAY) "새벽 $time" else time
    }
}

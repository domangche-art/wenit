package com.example.shortsdetector.intervention.allowance

import com.example.shortsdetector.data.usage.InterventionOutcome
import java.time.LocalDate

/**
 * 하루 허용 시간(14단계): 사용자가 스스로 골라 쓰는 "개입 없이 볼 수 있는 시간". 결정론적이고 Android 의존성이 없다.
 *
 *  - 설정값은 사용자만 바꾼다(0 = 끔 / 5 / 10 / 15 / 20 / 30분). 에이전트 정책([com.example.shortsdetector.intervention.InterventionPolicy])과
 *    따로 저장하므로 에이전트가 바꾸는 경로가 없다.
 *  - 매일 자정(기기 현지)에 다시 채워지고 남은 시간은 넘어가지 않는다. 날짜가 바뀌면 쓴 시간을 0 으로 본다.
 *  - 시연 모드는 따로 센다([AllowanceCounter.DEMO]). 실제 사용량과 섞이지 않는다.
 */
object AllowanceBudget {
    val CHOICES_MINUTES = listOf(0, 5, 10, 15, 20, 30)
    const val DEMO_BUDGET_MS = 30_000L
    private const val MS_PER_MINUTE = 60_000L

    /** 범위 밖 값은 가장 가까운 아래 선택지로(0 미만은 0). */
    fun normalizeMinutes(minutes: Int): Int = CHOICES_MINUTES.lastOrNull { it <= minutes } ?: 0

    /**
     * 오늘 쓸 수 있는 전체 시간. 설정이 0(끔)이면 시연 옵션과 상관없이 0 이다.
     * @param demo30s 시연 모드에서 개발자 화면의 "시연용 허용 시간 30초"를 켰다(켜진 설정값 대신 30초)
     */
    fun budgetMs(minutes: Int, demoMode: Boolean, demo30s: Boolean): Long {
        val m = normalizeMinutes(minutes)
        return when {
            m == 0 -> 0L
            demoMode && demo30s -> DEMO_BUDGET_MS
            else -> m * MS_PER_MINUTE
        }
    }

    fun remainingMs(budgetMs: Long, state: AllowanceState, today: LocalDate): Long =
        (budgetMs - state.usedOn(today)).coerceAtLeast(0L)

    /** 쓴 시간을 더한다. 날짜가 바뀌었으면 새 날로 시작한다. */
    fun charge(state: AllowanceState, day: LocalDate, ms: Long): AllowanceState =
        if (state.epochDay == day.toEpochDay()) state.copy(usedMs = state.usedMs + ms.coerceAtLeast(0L))
        else AllowanceState(day.toEpochDay(), ms.coerceAtLeast(0L))
}

/** 하루 사용량. [epochDay] 가 오늘이 아니면 오늘 쓴 시간은 0 이다. */
data class AllowanceState(val epochDay: Long = Long.MIN_VALUE, val usedMs: Long = 0L) {
    fun usedOn(day: LocalDate): Long = if (epochDay == day.toEpochDay()) usedMs else 0L
}

enum class AllowanceCounter { REAL, DEMO }

/**
 * 허용 시간을 쓰는 중. 시작할 때의 세션·단계·시청시간을 기억하고, 그 세션의 확정 시청시간이 늘어난 만큼 쓴 것으로 본다.
 * 확정 시청시간은 멈춘 영상·가려진 시간·이탈 대기 시간을 이미 뺀 값이라(시청 보류) 실제로 본 시간만 줄어든다.
 *
 * @param budgetLeftMs 시작할 때 남은 시간
 * @param level 다 썼을 때 띄울 개입 단계(시작할 때의 단계)
 */
data class AllowanceRun(
    val sessionNumber: Int,
    val level: Int,
    val startWatchMs: Long,
    val budgetLeftMs: Long,
    val startedAtMs: Long,
    val day: LocalDate,
    val demo: Boolean
) {
    sealed interface Tick {
        /** 계속 쓰는 중 */
        data class Using(val usedMs: Long, val leftMs: Long) : Tick

        /** 다 썼다 → 바로 [level] 개입 */
        data class Exhausted(val usedMs: Long) : Tick

        /** 쇼츠를 떠났다(세션이 끝났거나 다른 세션) → 사용을 멈추고 남은 시간은 남긴다 */
        data class Left(val usedMs: Long) : Tick
    }

    /**
     * @param session 지금 열린 세션 번호(없으면 null)
     * @param watchMs 그 세션의 확정 시청시간
     * @param lastWatchMs 이 실행이 마지막으로 본 시청시간. 세션이 끝난 뒤에는 이 값으로 쓴 시간을 정한다
     */
    fun tick(session: Int?, watchMs: Long, lastWatchMs: Long): Tick {
        if (session != sessionNumber) return Tick.Left(used(lastWatchMs))
        val used = used(watchMs)
        return if (used >= budgetLeftMs) Tick.Exhausted(budgetLeftMs) else Tick.Using(used, budgetLeftMs - used)
    }

    private fun used(watchMs: Long) = (watchMs - startWatchMs).coerceIn(0L, budgetLeftMs)
}

/**
 * 팝업이 닫힌 뒤 "영상을 탭하면 이어서 볼 수 있어요" 안내를 띄울지.
 * 계속 보기·허용 시간·숨 고르기 무응답으로 닫혔고, 앱이 멈춘 영상이 아직 멈춰 있고, 쇼츠 세션이 열려 있을 때만.
 * "그만 볼게"(앱으로 이동), 이탈로 닫힘, 알림 방식(영상을 멈추지 않음)은 띄우지 않는다.
 */
object ResumeHintRule {
    private val SHOW_AFTER = setOf(InterventionOutcome.CONTINUED, InterventionOutcome.ALLOWANCE, InterventionOutcome.IGNORED)
    const val SHOW_MS = 3_000L

    /**
     * 안내 위치: 화면 세로 중앙에서 화면 높이의 이 비율만큼 아래(14-2단계). 처음에는 아래에서 120dp 였는데 YouTube 쇼츠의
     * 채널 이름·설명 줄과 겹쳤다. 쇼츠의 글자는 주로 아래쪽 약 25% 와 맨 위에 있다.
     */
    const val CENTER_OFFSET_RATIO = 0.12f

    /** 중앙 기준(Gravity.CENTER) 아래쪽 거리(px) */
    fun centerOffsetPx(screenHeightPx: Int): Int = (screenHeightPx.coerceAtLeast(0) * CENTER_OFFSET_RATIO).toInt()

    fun shouldShow(outcome: InterventionOutcome, viaPopup: Boolean, pausedByApp: Boolean, sessionOpen: Boolean, playing: Boolean): Boolean =
        viaPopup && outcome in SHOW_AFTER && pausedByApp && sessionOpen && !playing
}

/** 남은 시간 표시: 1분 이상은 분(올림), 1분 미만은 초(올림). */
object AllowanceFormat {
    data class Left(val value: Int, val minutes: Boolean)

    fun left(ms: Long): Left {
        val s = (ms.coerceAtLeast(0L) + 999) / 1000
        return if (s >= 60) Left(((s + 59) / 60).toInt(), true) else Left(s.toInt(), false)
    }
}

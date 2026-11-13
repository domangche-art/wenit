package com.example.shortsdetector.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.shortsdetector.intervention.allowance.AllowanceBudget
import com.example.shortsdetector.intervention.allowance.AllowanceCounter
import com.example.shortsdetector.intervention.allowance.AllowanceState
import java.time.LocalDate

/**
 * 하루 허용 시간 설정과 오늘 쓴 양(14단계). 사용자 설정이라 에이전트 정책([InterventionPolicyPreferences])과 다른 파일에 둔다.
 * 에이전트는 이 파일을 쓰지 않는다. 클라우드 백업에서 제외한다(res/xml).
 *
 * 쓴 양은 실제·시연 칸을 따로 둔다. 시연 모드의 사용량이 실제 남은 시간을 줄이지 않는다.
 */
object AllowancePreferences {

    private const val FILE = "allowance"
    private const val KEY_MINUTES = "daily_minutes"
    private const val KEY_DEMO_30S = "demo_30s"
    private const val KEY_DAY = "used_day"
    private const val KEY_USED = "used_ms"
    private const val KEY_DEMO_DAY = "demo_used_day"
    private const val KEY_DEMO_USED = "demo_used_ms"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun minutes(context: Context): Int = minutes(prefs(context))
    fun setMinutes(context: Context, minutes: Int) = prefs(context).edit { putInt(KEY_MINUTES, AllowanceBudget.normalizeMinutes(minutes)) }
    fun isDemo30s(context: Context): Boolean = prefs(context).getBoolean(KEY_DEMO_30S, false)
    fun setDemo30s(context: Context, on: Boolean) = prefs(context).edit { putBoolean(KEY_DEMO_30S, on) }

    internal fun minutes(p: SharedPreferences): Int = AllowanceBudget.normalizeMinutes(p.getInt(KEY_MINUTES, 0))

    fun counterFor(demoMode: Boolean) = if (demoMode) AllowanceCounter.DEMO else AllowanceCounter.REAL

    internal fun state(p: SharedPreferences, counter: AllowanceCounter): AllowanceState = when (counter) {
        AllowanceCounter.REAL -> AllowanceState(p.getLong(KEY_DAY, Long.MIN_VALUE), p.getLong(KEY_USED, 0L))
        AllowanceCounter.DEMO -> AllowanceState(p.getLong(KEY_DEMO_DAY, Long.MIN_VALUE), p.getLong(KEY_DEMO_USED, 0L))
    }

    internal fun charge(p: SharedPreferences, counter: AllowanceCounter, day: LocalDate, ms: Long) {
        val next = AllowanceBudget.charge(state(p, counter), day, ms)
        p.edit {
            when (counter) {
                AllowanceCounter.REAL -> { putLong(KEY_DAY, next.epochDay); putLong(KEY_USED, next.usedMs) }
                AllowanceCounter.DEMO -> { putLong(KEY_DEMO_DAY, next.epochDay); putLong(KEY_DEMO_USED, next.usedMs) }
            }
        }
    }

    /** 지금 모드의 오늘 전체 시간 */
    fun budgetMs(context: Context, demoMode: Boolean): Long =
        AllowanceBudget.budgetMs(minutes(context), demoMode, isDemo30s(context))

    /** 지금 모드의 오늘 남은 시간. 꺼져 있으면 0. */
    fun remainingMs(context: Context, demoMode: Boolean, today: LocalDate): Long =
        AllowanceBudget.remainingMs(budgetMs(context, demoMode), state(prefs(context), counterFor(demoMode)), today)

    fun charge(context: Context, demoMode: Boolean, day: LocalDate, ms: Long) = charge(prefs(context), counterFor(demoMode), day, ms)

    /** 오늘 쓴 양만 지운다(설정값은 남긴다). 기록 전체 삭제·시연 준비 초기화 때. */
    fun clearUsage(context: Context) = prefs(context).edit {
        remove(KEY_DAY); remove(KEY_USED); remove(KEY_DEMO_DAY); remove(KEY_DEMO_USED)
    }
}

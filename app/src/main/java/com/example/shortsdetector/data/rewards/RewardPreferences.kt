package com.example.shortsdetector.data.rewards

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.goal.GoalPreferences
import com.example.shortsdetector.data.profile.UserProfilePreferences
import com.example.shortsdetector.data.stats.SavedTimeBaselinePreferences
import com.example.shortsdetector.data.usage.UsageDatabase
import java.time.LocalDate
import java.time.ZoneId

/**
 * 판정 시작일. 처음 판정할 때 그날로 정한다. "기록 전체 삭제" 때 함께 지운다.
 * 클라우드 백업에서 제외한다(res/xml/backup_rules.xml).
 */
object RewardPreferences {

    private const val FILE = "rewards_state"
    private const val KEY_TRACKING_START = "tracking_start_epoch_day"

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun clear(context: Context) = prefs(context).edit { clear() }

    /** 저장된 시작일. 없으면 실제 하루 결과 행 중 가장 이른 날, 그것도 없으면 [today] 로 정해 저장한다. */
    internal fun trackingStart(p: SharedPreferences, today: LocalDate, earliestRow: LocalDate?): LocalDate {
        if (p.contains(KEY_TRACKING_START)) return LocalDate.ofEpochDay(p.getLong(KEY_TRACKING_START, today.toEpochDay()))
        val start = earliestRow ?: today
        p.edit { putLong(KEY_TRACKING_START, start.toEpochDay()) }
        return start
    }

    fun trackingStart(context: Context, today: LocalDate, earliestRow: LocalDate?): LocalDate =
        trackingStart(prefs(context), today, earliestRow)
}

/** Android 쪽 연결: 저장된 목표·정책·기준값을 읽어 [RewardsUpdater] 를 돌린다. 저장 스레드에서 부른다. */
object Rewards {

    fun update(context: Context, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): RewardUpdate {
        val app = context.applicationContext
        val db = UsageDatabase.get(app)
        val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
        val start = RewardPreferences.trackingStart(app, today, db.dayResults().filterNot { it.isSeed }.minOfOrNull { it.date })
        return RewardsUpdater(db, db, zone).run(
            nowMs = nowMs,
            goals = GoalPreferences.load(app),
            maxLevel = InterventionPolicyPreferences.load(app).maxLevel,
            baselineMs = baselineMs(app),
            trackingStart = start
        )
    }

    /** 고정된 첫 7일 평균이 있으면 그것, 없으면 설문의 평소 시청 시간. 여기서는 첫 7일 평균을 새로 계산하지 않는다. */
    fun baselineMs(context: Context): Long? =
        SavedTimeBaselinePreferences.peek(context)
            ?: UserProfilePreferences.load(context)?.usualDailyWatch?.let { it.estimateMinutes * MS_PER_MINUTE }

    private const val MS_PER_MINUTE = 60_000L
}

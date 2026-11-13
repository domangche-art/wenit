package com.example.shortsdetector.intervention.allowance

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.shortsdetector.R
import com.example.shortsdetector.ShortsDetectionService
import com.example.shortsdetector.ShortsNotifier
import com.example.shortsdetector.ui.WenitActivity

/**
 * 허용 시간을 쓰는 동안의 알림(14단계). 채널 중요도 LOW 라 소리·진동이 없다. 내용만 조용히 바꾸고, 다 쓰거나 쇼츠를 떠나면 지운다.
 * 사용자가 밀어 지울 수 있다(사용에는 영향 없음).
 */
object AllowanceNotifier {

    private const val CHANNEL_ID = "allowance"
    private const val NOTIFICATION_ID = 1003

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.allowance_channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.allowance_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun text(context: Context, leftMs: Long): String {
        val left = AllowanceFormat.left(leftMs)
        return context.getString(
            if (left.minutes) R.string.allowance_notification_minutes else R.string.allowance_notification_seconds, left.value
        )
    }

    /** @return 권한이 없거나 실패하면 false(허용 시간 사용은 그대로 이어진다) */
    fun show(context: Context, leftMs: Long): Boolean {
        if (!ShortsNotifier.hasPostPermission(context)) return false
        return try {
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shorts_detected)
                .setContentTitle(text(context, leftMs))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context, NOTIFICATION_ID,
                        Intent(context, WenitActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
                .build()
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
            true
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "허용 시간 알림 실패: ${e.javaClass.simpleName}")
            false
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}

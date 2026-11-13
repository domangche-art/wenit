package com.example.shortsdetector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.shortsdetector.data.rewards.BadgeCatalog
import com.example.shortsdetector.data.rewards.EarnedBadge
import com.example.shortsdetector.ui.WenitActivity

/**
 * 앱이 닫혀 있을 때 새 배지를 알린다. 누르면 앱이 열리고 축하 화면이 뜬다(아직 축하하지 않은 배지).
 * 개입 알림과 채널을 나눠 사용자가 따로 끌 수 있다. 쇼츠를 보는 중에는 부르지 않는다(서비스가 미룬다).
 */
object BadgeNotifier {

    private const val CHANNEL_ID = "badges"
    private const val NOTIFICATION_ID = 1002

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.badge_channel_name), NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.badge_channel_description) }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    /** 여러 개를 한 알림으로 묶는다. 권한이 없으면 false(배지는 다음에 앱을 열 때 축하 화면으로 보여 준다). */
    fun notify(context: Context, badges: List<EarnedBadge>): Boolean {
        if (badges.isEmpty() || !ShortsNotifier.hasPostPermission(context)) return false
        val names = badges.mapNotNull { b ->
            BadgeCatalog.byId(b.id)?.let { if (it.tiered) "${it.name}(${tierName(context, b.tier)})" else it.name }
        }
        if (names.isEmpty()) return false
        ensureChannel(context)
        val intent = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, WenitActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = context.getString(R.string.badge_notification_body, names.joinToString(", "))
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_showcase)
            .setContentTitle(context.getString(R.string.badge_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        return try {
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, n)
            true
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "배지 알림 실패: ${e.javaClass.simpleName}")
            false
        }
    }

    fun tierName(context: Context, tier: Int): String = context.getString(
        when (tier) {
            0 -> R.string.badge_tier_bronze
            1 -> R.string.badge_tier_silver
            2 -> R.string.badge_tier_gold
            3 -> R.string.badge_tier_diamond
            else -> R.string.badge_tier_master
        }
    )
}

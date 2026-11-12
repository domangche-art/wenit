package com.example.shortsdetector.intervention

import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.usage.InMemoryUsageStore
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionRecord
import com.example.shortsdetector.data.usage.SystemUsageClock
import com.example.shortsdetector.detection.DetectionEngine
import com.example.shortsdetector.detection.adapters.youtube.YouTubeShortsAdapter
import com.example.shortsdetector.detection.state.DetectionTuning
import com.example.shortsdetector.testutil.FakeSharedPreferences
import com.example.shortsdetector.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 10-2단계 [3]: 답하지 않은 알림은 새로 쌓지 않고 고치며, 단계 상승에는 한 번만 반영한다. */
class NotificationRefreshTest {

    private val prefs = FakeSharedPreferences()
    private val store = InMemoryUsageStore()
    private val copy = object : InterventionCopyProvider {
        override fun copyFor(input: InterventionCopyInput) = InterventionCopy("t")
        override fun levelChangeReason(from: Int, to: Int, policy: InterventionPolicy) = "r"
    }
    private val coordinator = InterventionCoordinator(store, PreferencesPolicyStore(prefs), copy, SystemUsageClock)
    private val tuning = DetectionTuning()

    private fun records() = store.interventionsBetween(0, Long.MAX_VALUE)
    private fun level() = InterventionPolicyPreferences.resolve(prefs, emptyList()).policy.level
    private fun streaks() = InterventionPolicyPreferences.readLevelStreaks(prefs)

    private fun insert(id: String, level: Int) =
        store.insertIntervention(InterventionRecord(id, null, 0, InterventionMethod.NOTIFICATION, 0, 0, level = level))

    @Test
    fun `an unanswered notification is refreshed only when popups are off and it is still showing`() {
        insert("a", 1)
        coordinator.begin("a", 1, 1, viaNotification = true)
        assertEquals("a", coordinator.notificationToRefresh(1, popupEnabled = false, notificationShowing = true))
        assertNull("팝업을 띄울 수 있으면 새 개입", coordinator.notificationToRefresh(1, true, true))
        assertNull("사용자가 밀어 지움", coordinator.notificationToRefresh(1, false, false))
        assertNull("다른 세션", coordinator.notificationToRefresh(2, false, true))
    }

    @Test
    fun `a popup intervention is never refreshed`() {
        insert("a", 1)
        coordinator.begin("a", 1, 1)
        assertNull(coordinator.notificationToRefresh(1, false, true))
    }

    /** 서비스의 intervene() 와 같은 순서: 고칠 알림이 있으면 고치고, 없으면 앞선 개입을 끝내고 새로 만든다. */
    @Test
    fun `ignoring notifications for a long session counts once`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000))
        val e = DetectionEngine(tuning, listOf(YouTubeShortsAdapter(tuning)), 30_000, watchThresholdMs = 30_000)
        var ids = 0
        var refreshed = 0
        var dues = 0
        for (t in 1_000L..600_000L step 1_000L) {
            val n = e.onSnapshot(t, Fixtures.youtubeShorts()).notifySession ?: continue
            dues++
            if (coordinator.notificationToRefresh(n, popupEnabled = false, notificationShowing = true) != null) {
                refreshed++
            } else {
                coordinator.abandon()
                val id = "i${++ids}"
                insert(id, level())
                coordinator.begin(id, n, level(), viaNotification = true)
            }
            e.onNotificationPosted(t)
            e.setInterventionOpen(false, t)
        }
        assertEquals("기록은 하나", 1, records().size)
        assertTrue("쿨다운마다 개입 시점이 온다", dues >= 18)
        assertEquals("첫 개입 뒤로는 모두 고침", dues - 1, refreshed)
        assertEquals("아직 끝나지 않아 단계 그대로", 1, level())

        coordinator.abandon() // 세션 종료
        assertEquals(InterventionOutcome.IGNORED, records().single().outcome)
        assertEquals(1, streaks().skips)
        assertEquals(1, level())
    }

    @Test
    fun `a swiped away notification is a reaction and the next one is new`() {
        InterventionPolicyPreferences.write(prefs, InterventionPolicy(thresholdMs = 30_000))
        insert("a", 1)
        coordinator.begin("a", 1, 1, viaNotification = true)
        // 다음 개입 때 알림이 없다: 고치지 않고 앞선 개입을 끝낸 뒤 새로 만든다.
        assertNull(coordinator.notificationToRefresh(1, false, notificationShowing = false))
        coordinator.abandon()
        insert("b", level())
        coordinator.begin("b", 1, level(), viaNotification = true)
        assertEquals(1, streaks().skips)
        assertEquals("b", coordinator.notificationToRefresh(1, false, true))
    }

    @Test
    fun `tapping a refreshed notification answers the one intervention`() {
        insert("a", 1)
        coordinator.begin("a", 1, 1, viaNotification = true)
        coordinator.notificationToRefresh(1, false, true)
        coordinator.notificationToRefresh(1, false, true)
        val r = coordinator.respondTo("a", InterventionOutcome.STOPPED)
        assertEquals(InterventionOutcome.STOPPED, r?.outcome)
        assertEquals(InterventionOutcome.STOPPED, records().single().outcome)
        assertEquals(1, streaks().stops)
    }
}

package com.example.shortsdetector.intervention

import com.example.shortsdetector.detection.DetectionEngine
import com.example.shortsdetector.detection.adapters.youtube.YouTubeShortsAdapter
import com.example.shortsdetector.detection.state.DetectionTuning
import com.example.shortsdetector.detection.state.UserPauseDetector
import com.example.shortsdetector.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 12단계 [2]: Lv.1·Lv.2 팝업을 띄우면 영상을 멈추고, 그동안은 시청시간·사용자 멈춤에 넣지 않는다. */
class AppHoldTest {

    @Test
    fun `cards at Lv1 and Lv2 pause on show, Lv3 pauses at its countdown instead`() {
        assertTrue(InterventionLevels.of(1).pausesOnShow)
        assertTrue(InterventionLevels.of(2).pausesOnShow)
        assertFalse(InterventionLevels.of(3).pausesOnShow)
    }

    @Test
    fun `an open card is an app pause and holds watch time`() {
        val h = AppHold()
        assertFalse(h.appPaused(pausedByApp = false))
        h.cardOpen = true
        assertTrue(h.appPaused(false))
        assertTrue(h.watchHeld(pausedByApp = false, userPaused = false))
    }

    @Test
    fun `after continue the video stays paused by the app until the user plays it`() {
        val h = AppHold()
        h.cardOpen = true
        h.cardOpen = false // 계속 볼래
        assertTrue("앱이 멈춘 영상은 계속 보류", h.watchHeld(pausedByApp = true, userPaused = false))
        assertFalse("사용자가 다시 재생", h.watchHeld(pausedByApp = false, userPaused = false))
    }

    @Test
    fun `closing clears both card and countdown`() {
        val h = AppHold()
        h.cardOpen = true
        h.breathing = true
        h.closed()
        assertFalse(h.appPaused(false))
    }

    // --- 엔진·사용자 멈춤 판정과 함께 (서비스처럼 1초마다) ---

    private val tuning = DetectionTuning()

    @Test
    fun `a card paused by the app is neither watch time nor a user pause`() {
        val e = DetectionEngine(tuning, listOf(YouTubeShortsAdapter(tuning)), 30_000, watchThresholdMs = 30_000)
        val user = UserPauseDetector(holdAfterMs = 5_000)
        val h = AppHold()
        var pausedByApp = false
        var shownAt = -1L
        for (t in 1_000L..120_000L step 1_000L) {
            val audio = !pausedByApp
            val userPaused = user.update(t, audio, appPaused = h.appPaused(pausedByApp))
            assertFalse("앱이 멈춘 것을 사용자 멈춤으로 보지 않는다 t=$t", userPaused)
            e.setWatchHold(h.watchHeld(pausedByApp, userPaused))
            val out = e.onSnapshot(t, Fixtures.youtubeShorts())
            if (out.notifySession != null && shownAt < 0) {
                shownAt = t
                e.onNotificationPosted(t)
                e.setInterventionOpen(true, t)
                h.cardOpen = true // 팝업 표시
                pausedByApp = true // 일시정지 성공
            }
        }
        assertTrue(shownAt > 0)
        val watch = e.sessionProgress()!!.estimatedWatchMs
        assertTrue("팝업이 뜬 뒤로 시청시간이 늘지 않는다: $watch", watch <= 32_000)

        // 계속 볼래: 팝업은 닫히지만 앱이 멈춘 영상이라 아직 보류. 사용자가 재생하면 이어서 센다.
        h.cardOpen = false
        e.setInterventionOpen(false, 121_000)
        for (t in 121_000L..130_000L step 1_000L) {
            e.setWatchHold(h.watchHeld(pausedByApp, false))
            e.onSnapshot(t, Fixtures.youtubeShorts())
        }
        assertEquals(watch, e.sessionProgress()!!.estimatedWatchMs)
        pausedByApp = false
        for (t in 131_000L..140_000L step 1_000L) {
            e.setWatchHold(h.watchHeld(pausedByApp, false))
            e.onSnapshot(t, Fixtures.youtubeShorts())
        }
        assertTrue(e.sessionProgress()!!.estimatedWatchMs >= watch + 9_000)
    }
}

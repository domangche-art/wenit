package com.example.shortsdetector.detection.state

import com.example.shortsdetector.detection.DetectionEngine
import com.example.shortsdetector.detection.adapters.youtube.YouTubeShortsAdapter
import com.example.shortsdetector.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 10-2단계 [2]: 사용자가 직접 멈춘 영상은 시청시간에 넣지 않고 개입하지 않는다. */
class UserPauseDetectorTest {

    private val d = UserPauseDetector(holdAfterMs = 5_000)

    @Test
    fun `silence after audio becomes a pause only after the hold time`() {
        d.update(0, audioActive = true, appPaused = false)
        assertFalse(d.update(1_000, false, false))
        assertFalse(d.update(5_999, false, false))
        assertTrue(d.update(6_000, false, false))
    }

    @Test
    fun `short gaps such as buffering stay watching`() {
        d.update(0, true, false)
        d.update(1_000, false, false)
        d.update(4_000, false, false)
        d.update(4_500, true, false)
        assertFalse(d.update(9_000, false, false))
        assertTrue(d.update(14_000, false, false))
    }

    @Test
    fun `playing again ends the pause`() {
        d.update(0, true, false)
        d.update(1_000, false, false)
        assertTrue(d.update(7_000, false, false))
        assertFalse(d.update(8_000, true, false))
    }

    @Test
    fun `a video that was never heard is counted as watching`() {
        (0L..60_000L step 1_000L).forEach { assertFalse(d.update(it, false, false)) }
    }

    @Test
    fun `unknown audio state is counted as watching and drops the evidence`() {
        d.update(0, true, false)
        assertFalse(d.update(1_000, null, false))
        assertFalse(d.update(20_000, false, false))
    }

    @Test
    fun `a new video drops the evidence, so a silent next video is counted as watching`() {
        d.update(0, true, false)
        d.onContentChanged()
        (1_000L..30_000L step 1_000L).forEach { assertFalse(d.update(it, false, false)) }
    }

    @Test
    fun `a pause by the app is not counted as a user pause`() {
        d.update(0, true, false)
        (1_000L..30_000L step 1_000L).forEach { assertFalse(d.update(it, false, appPaused = true)) }
        // 앱 멈춤이 풀린 뒤에는 처음부터 다시 잰다.
        assertFalse(d.update(31_000, false, false))
        assertTrue(d.update(36_000, false, false))
    }

    // --- 엔진과 함께 ---

    private val tuning = DetectionTuning()
    private fun engine() = DetectionEngine(tuning, listOf(YouTubeShortsAdapter(tuning)), 30_000, watchThresholdMs = 30_000)

    /** 서비스처럼: 1초마다 오디오 상태를 갱신하고 시청 보류를 맞춘 뒤 화면을 관찰한다. */
    private fun DetectionEngine.run(from: Long, to: Long, audio: (Long) -> Boolean?, notified: MutableList<Long>) {
        for (t in from..to step 1_000L) {
            setWatchHold(d.update(t, audio(t), appPaused = false))
            val out = onSnapshot(t, Fixtures.youtubeShorts())
            if (out.notifySession != null) {
                notified += t
                onNotificationPosted(t)
            }
        }
    }

    @Test
    fun `a paused video gets no intervention and watch time resumes after playing again`() {
        val e = engine()
        val notified = ArrayList<Long>()
        e.run(0, 15_000, { true }, notified)
        val beforePause = e.sessionProgress()!!.estimatedWatchMs

        // 직접 멈춤: 기준 30초를 넘겨 2분 동안 둔다.
        e.run(16_000, 136_000, { false }, notified)
        assertTrue("멈춘 동안 개입 없음", notified.isEmpty())
        val paused = e.sessionProgress()!!.estimatedWatchMs
        assertTrue("멈춘 뒤 판정 전 몇 초만 더 셌다", paused - beforePause <= 6_000)

        // 다시 재생: 이어서 센다. 남은 시간만큼 보면 개입한다.
        e.run(137_000, 180_000, { true }, notified)
        assertTrue(notified.isNotEmpty())
        val firstAt = notified.first()
        assertTrue("이어서 세어 기준에 닿음", firstAt - 137_000 <= 30_000 - paused + 2_000)
    }

    @Test
    fun `an interrupted intervention is not retried while the video is paused`() {
        val e = engine()
        val notified = ArrayList<Long>()
        e.run(0, 40_000, { true }, notified)
        assertEquals(1, notified.size)
        e.retryIntervention(e.sessionProgress()!!.sessionNumber)
        e.run(41_000, 200_000, { false }, notified)
        assertEquals("멈춘 영상에 재시도하지 않음", 1, notified.size)
    }
}

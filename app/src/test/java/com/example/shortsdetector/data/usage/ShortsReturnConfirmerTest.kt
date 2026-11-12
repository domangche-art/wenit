package com.example.shortsdetector.data.usage

import com.example.shortsdetector.detection.DetectionEngine
import com.example.shortsdetector.detection.adapters.youtube.YouTubeShortsAdapter
import com.example.shortsdetector.detection.state.DetectionTuning
import com.example.shortsdetector.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 10-2단계 [4]: 쇼츠 시청이 확정된 경우만 쇼츠 복귀로 남긴다. */
class ShortsReturnConfirmerTest {

    private val c = ShortsReturnConfirmer()

    @Test
    fun `the threshold is the shortest intervention threshold, 10 seconds`() {
        assertEquals(10_000L, ShortsReturnConfirmer.MIN_WATCH_MS)
    }

    @Test
    fun `return is confirmed once after 10 seconds of watch, dated at arrival`() {
        c.onEntered(1, 0, nowMs = 5_000)
        assertNull(c.onProgress(1, 9_999))
        assertEquals(5_000L, c.onProgress(1, 10_000))
        assertNull(c.onProgress(1, 20_000))
        assertNull(c.onEnded(1))
    }

    @Test
    fun `a resumed session measures only the watch after arriving`() {
        c.onEntered(3, watchMs = 50_000, nowMs = 1_000)
        assertNull(c.onProgress(3, 55_000))
        assertEquals(1_000L, c.onProgress(3, 60_000))
    }

    @Test
    fun `passing through ends as app only at the arrival time`() {
        c.onEntered(1, 0, nowMs = 7_000)
        assertNull(c.onProgress(1, 2_000))
        assertEquals(7_000L, c.onEnded(1))
        assertNull(c.onProgress(1, 20_000))
    }

    @Test
    fun `other sessions are ignored`() {
        c.onEntered(1, 0, 0)
        assertNull(c.onProgress(2, 60_000))
        assertNull(c.onEnded(2))
    }

    // --- 엔진과 저장소를 서비스처럼 잇는다 ---

    private val tuning = DetectionTuning()
    private val store = InMemoryUsageStore()

    private fun stopAt(id: String, atMs: Long) {
        store.insertIntervention(InterventionRecord(id, null, atMs, InterventionMethod.POPUP, 0, 0, level = 1))
        store.setInterventionOutcome(id, InterventionOutcome.STOPPED, null)
        store.setInterventionAction(id, InterventionAction.OPEN_APP, atMs)
    }

    /** 서비스의 recordReturn() 과 같은 순서. 엔진 시각과 벽시계를 같게 둔다. */
    private fun visit(e: DetectionEngine, from: Long, shortsUntil: Long, to: Long) {
        val tracker = ReturnTracker(store)
        var t = from
        while (t <= to) {
            val out = if (t <= shortsUntil) e.onSnapshot(t, Fixtures.youtubeShorts()) else e.onSnapshot(t, Fixtures.youtubeHome())
            (out.step.startedSession ?: out.step.resumedSession)?.let { c.onEntered(it, e.sessionProgress()?.estimatedWatchMs ?: 0L, t) }
            val p = e.sessionProgress()
            c.onProgress(p?.sessionNumber, p?.estimatedWatchMs ?: 0L)?.let(tracker::onShortsReturned)
            (out.endedSession?.let { c.onEnded(it.sessionNumber) } ?: t.takeIf { out.openedTargetApp })
                ?.let(tracker::onTargetAppOpened)
            t += 250
        }
    }

    private fun record() = store.interventionsBetween(0, Long.MAX_VALUE).single()

    @Test
    fun `briefly passing through shorts is not a return to shorts`() {
        stopAt("s", 0)
        val e = DetectionEngine(tuning, listOf(YouTubeShortsAdapter(tuning)), 30_000)
        visit(e, from = 60_000, shortsUntil = 62_000, to = 70_000)
        val r = record()
        assertNull("쇼츠 복귀 아님", r.returnStatus)
        assertEquals("앱을 연 시각은 도착 시각", 60_500L, r.appReturnMs)
    }

    @Test
    fun `watching shorts again is a return dated at arrival`() {
        stopAt("s", 0)
        val e = DetectionEngine(tuning, listOf(YouTubeShortsAdapter(tuning)), 30_000)
        visit(e, from = 60_000, shortsUntil = 80_000, to = 85_000)
        val r = record()
        assertEquals(InterventionReturn.SHORTS, r.returnStatus)
        assertEquals(60_500L, r.shortsReturnMs)
    }

    @Test
    fun `a stop made after arriving is not marked by that arrival`() {
        val tracker = ReturnTracker(store)
        stopAt("late", 10_000)
        tracker.onShortsReturned(5_000)
        assertNull(record().returnStatus)
    }
}

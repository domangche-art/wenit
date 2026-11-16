package com.example.shortsdetector.intervention

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 15단계 실기기 검증: Lv.4 막 뒤에서 자동 재생이 시작되면 다시 멈춘다. */
class BlockingPlaybackGuardTest {

    @Test
    fun `playback that starts behind the level 4 block is paused again`() {
        val g = BlockingPlaybackGuard()
        g.onShown()
        assertTrue(g.shouldRepause(blockingShown = true, playing = true, pausing = false))
    }

    @Test
    fun `nothing to do when silent, already pausing, or no level 4 block`() {
        val g = BlockingPlaybackGuard()
        g.onShown()
        assertFalse("소리가 없으면 할 일 없음", g.shouldRepause(true, playing = false, pausing = false))
        assertFalse("멈추는 중이면 기다린다", g.shouldRepause(true, playing = true, pausing = true))
        assertFalse("Lv.1~3 은 대상 아님", g.shouldRepause(blockingShown = false, playing = true, pausing = false))
        assertTrue("위 경우는 횟수를 쓰지 않는다", (1..BlockingPlaybackGuard.MAX_REPAUSES).all { g.shouldRepause(true, true, false) })
    }

    @Test
    fun `repauses are capped per block and reset for the next block`() {
        val g = BlockingPlaybackGuard()
        g.onShown()
        repeat(BlockingPlaybackGuard.MAX_REPAUSES) { assertTrue(g.shouldRepause(true, true, false)) }
        assertFalse("다른 앱 소리와 싸우지 않게 횟수 제한", g.shouldRepause(true, true, false))
        g.onShown()
        assertTrue("새 막은 다시 센다", g.shouldRepause(true, true, false))
    }

    /** 실기기 순서: 막을 띄울 때 아직 재생 전(NOT_PLAYING) → 0.7초 뒤 자동 재생 → 빠른 확인이 다시 멈춤. */
    @Test
    fun `device sequence - not playing at show, autoplay later, paused again`() {
        var playing = false
        val controls = object : PlaybackControls {
            override fun sendPauseKey() = Unit
            override fun isPlaying() = playing
            override fun requestFocus(): Boolean {
                playing = false
                return true
            }
            override fun releaseFocus() = Unit
        }
        val queue = ArrayList<() -> Unit>()
        val pauser = PlaybackPauser(controls) { _, action -> queue += action }
        val guard = BlockingPlaybackGuard()

        var first: PauseResult? = null
        pauser.pause { first = it }
        guard.onShown()
        assertEquals(PauseResult.NOT_PLAYING, first)

        playing = true // YouTube 자동 재생
        assertTrue(guard.shouldRepause(true, controls.isPlaying(), pauser.isPausing))
        var second: PauseResult? = null
        pauser.pause { second = it }
        assertTrue(pauser.isPausing)
        assertFalse("멈추는 동안 겹쳐 부르지 않는다", guard.shouldRepause(true, controls.isPlaying(), pauser.isPausing))
        while (queue.isNotEmpty()) queue.removeAt(0)()

        assertEquals(PauseResult.FOCUS, second)
        assertFalse(controls.isPlaying())
        assertTrue(pauser.pausedByApp)
        assertFalse(pauser.isPausing)
    }
}

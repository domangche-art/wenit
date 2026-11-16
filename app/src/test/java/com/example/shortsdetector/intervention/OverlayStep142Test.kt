package com.example.shortsdetector.intervention

import com.example.shortsdetector.intervention.allowance.ResumeHintRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 14-2단계: 다크/라이트 전환 뒤 캐릭터가 보이지 않던 문제와 재생 안내 위치.
 * 원인: 캐릭터 뷰를 투명·0.3배로 그린 뒤 앱 쪽 애니메이터로 키웠는데, 서비스 프로세스가 화면 프레임을 받지 못하면 첫 그림이 남았다.
 * 고친 방법: 뷰는 최종 상태로 두고 등장·퇴장을 창 애니메이션으로 한다. 아래는 이 구조가 되돌아가지 않게 지킨다.
 */
class OverlayStep142Test {

    private val main = File("src/main")
    private fun source(path: String) = File(main, "java/com/example/shortsdetector/$path").readText()

    @Test
    fun `character view is never drawn transparent waiting for an app animator`() {
        val s = source("intervention/character/CharacterOverlay.kt")
        assertFalse("앱 쪽 애니메이터를 쓰지 않는다", s.contains(".animate()"))
        assertFalse("뷰를 투명하게 시작하지 않는다", Regex("""\.alpha\s*=\s*0f""").containsMatchIn(s))
        assertTrue(s.contains("windowAnimations = R.style.CharacterWindowAnimation"))
    }

    @Test
    fun `character window animation keeps the step 11 shape`() {
        val inXml = File(main, "res/anim/character_pop_in.xml").readText()
        assertTrue(inXml.contains("""android:duration="420""""))
        assertTrue(inXml.contains("overshoot_interpolator"))
        assertTrue(inXml.contains("""android:fromXScale="0.3"""") && inXml.contains("""android:pivotY="100%""""))
        assertTrue(inXml.contains("""android:fromAlpha="0.0""""))
        val outXml = File(main, "res/anim/character_pop_out.xml").readText()
        assertTrue(outXml.contains("""android:duration="160"""") && outXml.contains("""android:toXScale="0.6""""))
        val styles = File(main, "res/values/styles_popup.xml").readText()
        assertTrue(styles.contains("CharacterWindowAnimation"))
        assertTrue(styles.contains("@anim/character_pop_in") && styles.contains("@anim/character_pop_out"))
    }

    @Test
    fun `frame delay is reported only after the limit`() {
        assertFalse(FrameDelay.stalled(postedAt = 100, firedAt = 116, now = 1_100, limitMs = 1_000))
        assertTrue(FrameDelay.stalled(postedAt = 100, firedAt = -1, now = 1_100, limitMs = 1_000))     // 오지 않음
        assertFalse(FrameDelay.stalled(postedAt = 100, firedAt = -1, now = 900, limitMs = 1_000))      // 아직 기다리는 중
        assertTrue(FrameDelay.stalled(postedAt = 100, firedAt = 2_700, now = 3_000, limitMs = 1_000))  // 2.6초 늦게 옴
    }

    @Test
    fun `resume hint sits a little below the vertical center, clear of the bottom text`() {
        val h = 2_340
        val offset = ResumeHintRule.centerOffsetPx(h)
        assertEquals(280, offset)
        val centerY = h / 2 + offset
        assertTrue("중앙보다 아래", centerY > h / 2)
        assertTrue("아래 25%(채널 이름·설명 줄)보다 위", centerY < h * 3 / 4)
        assertEquals(0, ResumeHintRule.centerOffsetPx(-1))
        val hint = source("intervention/allowance/ResumeHintOverlay.kt")
        assertTrue("터치를 받지 않는 설정은 그대로", hint.contains("FLAG_NOT_TOUCHABLE"))
        assertTrue(hint.contains("Gravity.CENTER"))
    }
}

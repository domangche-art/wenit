package com.example.shortsdetector.intervention.character

import com.example.shortsdetector.data.DetectionPreferences
import com.example.shortsdetector.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 11단계: 개입 때 캐릭터의 위치. 1080x2340 화면, 상태 표시줄 아래부터를 화면으로 본다. */
class CharacterPlacementTest {

    private val screen = Box(0, 80, 1080, 2340)

    // --- Lv.1·2: 카드 바로 위 ---

    @Test
    fun `sits right on top of the card, centered, feet overlapping the card edge`() {
        val card = Box(76, 900, 1004, 1500)
        val p = CharacterPlacement.place(screen, CharacterAnchor.AboveCard(card, overlapPx = 21), 294, 265)!!
        assertEquals(294, p.width)
        assertEquals(265, p.height)
        assertEquals(card.top + 21, p.box.bottom)
        assertEquals((card.left + card.right) / 2, p.x + p.width / 2)
        assertTrue("카드와는 발이 걸치는 만큼만 겹친다", p.box.bottom - card.top == 21)
    }

    @Test
    fun `shrinks when the room above the card is short`() {
        val card = Box(76, 280, 1004, 2000)
        val p = CharacterPlacement.place(screen, CharacterAnchor.AboveCard(card, 0), 294, 265)!!
        assertEquals(200, p.height) // 280 - 80
        assertEquals(screen.top, p.y)
        assertEquals(294 * 200 / 265, p.width)
    }

    @Test
    fun `does not show when it would have to shrink below half`() {
        val card = Box(76, 180, 1004, 2000)
        assertNull(CharacterPlacement.place(screen, CharacterAnchor.AboveCard(card, 0), 294, 265))
    }

    @Test
    fun `never leaves the screen sideways`() {
        val card = Box(-200, 900, 100, 1500)
        val p = CharacterPlacement.place(screen, CharacterAnchor.AboveCard(card, 0), 294, 265)!!
        assertTrue(p.x >= screen.left)
        assertTrue(p.box.right <= screen.right)
    }

    // --- Lv.3: 숨 고르기 문구를 피한 곳 ---

    @Test
    fun `stays clear of the breathing text and buttons`() {
        val content = Box(63, 800, 1017, 1600)
        val p = CharacterPlacement.place(screen, CharacterAnchor.ClearOf(content, marginPx = 42), 394, 355)!!
        assertFalse(p.box.intersects(Box(content.left, content.top - 42, content.right, content.bottom + 42)))
        assertTrue(p.box.top >= screen.top)
        assertEquals(394, p.width)
    }

    @Test
    fun `uses the larger gap, below when the content sits high`() {
        val content = Box(63, 200, 1017, 900)
        val p = CharacterPlacement.place(screen, CharacterAnchor.ClearOf(content, 42), 394, 355)!!
        assertTrue(p.y >= content.bottom + 42)
    }

    @Test
    fun `gives up when there is no room around the content`() {
        val content = Box(0, 100, 1080, 2320)
        assertNull(CharacterPlacement.place(screen, CharacterAnchor.ClearOf(content, 42), 394, 355))
    }

    @Test
    fun `bad sizes give no placement`() {
        assertNull(CharacterPlacement.place(screen, CharacterAnchor.AboveCard(Box(0, 900, 100, 1000), 0), 0, 10))
        assertNull(CharacterPlacement.place(Box(0, 0, 0, 0), CharacterAnchor.AboveCard(Box(0, 900, 100, 1000), 0), 10, 10))
    }

    @Test
    fun `never grows past the requested size`() {
        val p = CharacterPlacement.place(screen, CharacterAnchor.ClearOf(Box(0, 2000, 1080, 2100), 0), 100, 90)
        assertNotNull(p)
        assertEquals(100, p!!.width)
    }

    // --- 설정 ---

    @Test
    fun `the character setting is on by default and can be turned off`() {
        val prefs = FakeSharedPreferences()
        assertTrue(DetectionPreferences.isCharacterEnabled(prefs))
        DetectionPreferences.setCharacterEnabled(prefs, false)
        assertFalse(DetectionPreferences.isCharacterEnabled(prefs))
    }
}

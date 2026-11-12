package com.example.shortsdetector.intervention.character

/** 화면 좌표의 사각형(px). right·bottom 은 포함하지 않는다. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun intersects(o: Box): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
}

/** 캐릭터 창의 위치와 크기(px) */
data class Placement(val x: Int, val y: Int, val width: Int, val height: Int) {
    val box: Box get() = Box(x, y, x + width, y + height)
}

/** 캐릭터를 어디에 둘지. 개입 화면 종류와 무관한 모양만 담는다. */
sealed interface CharacterAnchor {
    /** 카드 바로 위. 발이 카드 윗변에 [overlapPx] 만큼 걸친다. */
    data class AboveCard(val card: Box, val overlapPx: Int) : CharacterAnchor

    /** [avoid] 와 겹치지 않는 곳. 위아래 중 넓은 쪽 가운데, [marginPx] 만큼 띄운다. */
    data class ClearOf(val avoid: Box, val marginPx: Int) : CharacterAnchor
}

/**
 * 캐릭터 위치 계산. 결정론적이고 Android 의존성이 없다.
 *
 * 자리가 모자라면 비율을 지킨 채 줄인다. [minScale] 보다 더 줄여야 하면 띄우지 않는다(null).
 * 가로는 항상 [screen] 안에 들어오게 한다.
 */
object CharacterPlacement {

    const val DEFAULT_MIN_SCALE = 0.5f

    fun place(
        screen: Box,
        anchor: CharacterAnchor,
        width: Int,
        height: Int,
        minScale: Float = DEFAULT_MIN_SCALE
    ): Placement? {
        if (width <= 0 || height <= 0 || screen.width <= 0 || screen.height <= 0) return null
        return when (anchor) {
            is CharacterAnchor.AboveCard -> aboveCard(screen, anchor, width, height, minScale)
            is CharacterAnchor.ClearOf -> clearOf(screen, anchor, width, height, minScale)
        }
    }

    private fun aboveCard(screen: Box, a: CharacterAnchor.AboveCard, w: Int, h: Int, minScale: Float): Placement? {
        val bottom = a.card.top + a.overlapPx
        val room = bottom - screen.top
        val size = fit(w, h, minOf(screen.width, a.card.width), room, minScale) ?: return null
        val x = centeredX(screen, (a.card.left + a.card.right) / 2, size.first)
        return Placement(x, bottom - size.second, size.first, size.second)
    }

    private fun clearOf(screen: Box, a: CharacterAnchor.ClearOf, w: Int, h: Int, minScale: Float): Placement? {
        val above = Box(screen.left, screen.top, screen.right, a.avoid.top - a.marginPx)
        val below = Box(screen.left, a.avoid.bottom + a.marginPx, screen.right, screen.bottom)
        val gap = if (above.height >= below.height) above else below
        if (gap.height <= 0) return null
        val size = fit(w, h, screen.width, gap.height, minScale) ?: return null
        val x = centeredX(screen, (a.avoid.left + a.avoid.right) / 2, size.first)
        val y = gap.top + (gap.height - size.second) / 2
        return Placement(x, y, size.first, size.second)
    }

    /** 비율을 지키며 [maxW]×[maxH] 안에 넣는다. 원래보다 키우지 않는다. */
    private fun fit(w: Int, h: Int, maxW: Int, maxH: Int, minScale: Float): Pair<Int, Int>? {
        if (maxW <= 0 || maxH <= 0) return null
        val scale = minOf(1f, maxW.toFloat() / w, maxH.toFloat() / h)
        if (scale < minScale) return null
        return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
    }

    private fun centeredX(screen: Box, centerX: Int, width: Int): Int =
        (centerX - width / 2).coerceIn(screen.left, maxOf(screen.left, screen.right - width))
}

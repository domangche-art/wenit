package com.example.shortsdetector.intervention.character

import androidx.annotation.DrawableRes
import androidx.annotation.StyleRes
import com.example.shortsdetector.R

/** Lv.4 등장 애니메이션 종류. 시스템이 창에 적용하는 창 애니메이션이다(14-2단계: 앱 쪽 프레임이 멈춰도 캐릭터가 보이게). */
enum class Lv4Entrance(@StyleRes val windowAnimation: Int) {
    /** 위에서 떨어져 통통 튄다 */
    DROP(R.style.CharacterLv4DropWindowAnimation)
}

/** Lv.4 탭 반응 종류. 쉬는 모습(회전 0)에서 시작해 쉬는 모습으로 끝난다. 프레임이 멈추면 반응만 빠지고 캐릭터는 보인다. */
enum class Lv4TapReaction {
    /** 좌우로 흔들린다 */
    SHAKE
}

/**
 * Lv.4 에서의 행동 값.
 * @param tapsToClear 치우는 데 필요한 탭 수
 */
data class Lv4Behavior(
    val tapsToClear: Int,
    val entrance: Lv4Entrance,
    val tapReaction: Lv4TapReaction
)

/**
 * 캐릭터 하나의 정의.
 * @param id 저장값에 쓰는 고정 id. 바꾸지 않는다
 * @param personality 성격 설명. 고르는 화면(아직 없음)에 쓴다
 * @param baseArt 기본 그림. 개입 옆 캐릭터(Lv.1~3)와, Lv.4 그림이 없을 때 Lv.4 에 쓴다
 * @param lv4Art Lv.4 전용 그림. 없으면 null
 * @param lines 말풍선 대사. 비어 있으면 말풍선을 숨긴다. 기기에서 보여 줄 뿐 저장·전송하지 않는다
 */
data class CharacterSpec(
    val id: String,
    val name: String,
    val personality: String,
    @DrawableRes val baseArt: Int,
    @DrawableRes val lv4Art: Int?,
    val lv4: Lv4Behavior,
    val lines: List<String>
) {
    /** Lv.4 에 쓸 그림: Lv.4 그림이 있으면 그것, 없으면 기본 그림 */
    @get:DrawableRes
    val lv4ArtOrBase: Int get() = lv4Art ?: baseArt
}

/**
 * 캐릭터 목록. 캐릭터 정보는 여기에만 둔다(15단계).
 *
 * 캐릭터를 추가하려면 [ALL] 에 한 줄을 더하고 그림 파일(res/drawable-nodpi)을 넣는다. Lv.4 화면, 개입 옆 캐릭터, 저장된 선택은
 * 이 목록을 따른다. 고르는 화면은 아직 없다(선택 저장값만 있음). 앞으로 캐릭터 4개 중 사용자가 고르게 할 예정이다.
 */
object Characters {

    const val DEFAULT_ID = "kind"

    val ALL: List<CharacterSpec> = listOf(
        CharacterSpec(
            id = DEFAULT_ID,
            name = "다정이",
            personality = "다정하게 말을 거는 캐릭터",
            baseArt = R.drawable.wenit_character,
            lv4Art = null,
            lv4 = Lv4Behavior(tapsToClear = 3, entrance = Lv4Entrance.DROP, tapReaction = Lv4TapReaction.SHAKE),
            lines = emptyList()
        )
    )

    val DEFAULT: CharacterSpec get() = ALL.first { it.id == DEFAULT_ID }

    /** 저장된 id 로 찾는다. 없거나 모르는 id(지운 캐릭터, 손상된 값)면 기본 캐릭터 */
    fun byId(id: String?): CharacterSpec = ALL.firstOrNull { it.id == id } ?: DEFAULT
}

/** Lv.4 캐릭터 규칙. 결정론적이고 Android 의존성이 없다. */
object Lv4CharacterRules {

    /** 캐릭터 너비 = 화면 너비 × 이 비율 */
    const val WIDTH_RATIO = 0.5f

    /** 캐릭터 높이가 화면 높이의 이 비율을 넘으면 줄인다(문구·버튼 자리를 남긴다) */
    const val MAX_HEIGHT_RATIO = 0.4f

    /** 탭 수를 1 미만으로 정의해도 한 번은 눌러야 치워진다 */
    fun tapsNeeded(behavior: Lv4Behavior): Int = behavior.tapsToClear.coerceAtLeast(1)

    fun tapsLeft(behavior: Lv4Behavior, taps: Int): Int = (tapsNeeded(behavior) - taps).coerceAtLeast(0)

    fun cleared(behavior: Lv4Behavior, taps: Int): Boolean = tapsLeft(behavior, taps) == 0

    /**
     * 말풍선 대사. 등장할 때 [index] 0, n 번째 탭 뒤 n. 대사를 차례로 돌려 쓴다. 대사가 없으면 null(말풍선 숨김).
     */
    fun lineAt(lines: List<String>, index: Int): String? =
        if (lines.isEmpty()) null else lines[Math.floorMod(index, lines.size)]

    /**
     * 캐릭터 크기(px): 너비는 화면의 [WIDTH_RATIO], 높이는 그림 비율. 너무 높으면 [MAX_HEIGHT_RATIO] 에 맞춰 줄인다.
     * 값이 잘못되면 null.
     */
    fun size(screenW: Int, screenH: Int, artW: Int, artH: Int): Pair<Int, Int>? {
        if (screenW <= 0 || screenH <= 0 || artW <= 0 || artH <= 0) return null
        var w = (screenW * WIDTH_RATIO).toInt()
        var h = (w.toLong() * artH / artW).toInt()
        val maxH = (screenH * MAX_HEIGHT_RATIO).toInt()
        if (h > maxH) {
            h = maxH
            w = (h.toLong() * artW / artH).toInt()
        }
        return if (w > 0 && h > 0) w to h else null
    }

    /**
     * 끌기를 놓았을 때 화면 밖으로 밀어낸 것인가: 캐릭터 가운데가 화면 밖으로 나갔다(절반 넘게 나감).
     * 창 위치가 아니라 손가락으로 옮긴 위치로 판정한다(시스템이 창을 화면 안으로 잡아 둘 수 있다).
     */
    fun draggedOut(box: Box, screen: Box): Boolean {
        val cx = (box.left + box.right) / 2
        val cy = (box.top + box.bottom) / 2
        return cx < screen.left || cx >= screen.right || cy < screen.top || cy >= screen.bottom
    }
}

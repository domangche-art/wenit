package com.example.shortsdetector.intervention.character

import androidx.annotation.DrawableRes
import androidx.annotation.StyleRes
import com.example.shortsdetector.R

/**
 * Lv.4 등장 종류. 시스템이 창에 적용하는 창 애니메이션이다(14-2단계: 앱 쪽 프레임이 멈춰도 캐릭터가 보이게).
 * 퇴장은 치운 방법(탭·끌기)에 따라 [CharacterOverlay] 가 고른다.
 */
enum class Lv4Entrance(@StyleRes val windowAnimation: Int) {
    /** 위에서 떨어져 통통 튄다 */
    DROP(R.style.CharacterLv4DropWindowAnimation),

    /** 옆 위쪽에서 날아 들어온다 */
    FLY_IN(R.style.CharacterLv4FlyInWindowAnimation),

    /** 제자리에 쿵 하고 선다 */
    STAND(R.style.CharacterLv4StandWindowAnimation),

    /** 화면 한쪽 끝에서 걸어 들어온다. 자리는 가운데가 아니라 화면 끝이다([Lv4Idle.WALK]) */
    WALK_IN(R.style.CharacterLv4WalkInWindowAnimation)
}

/** Lv.4 에서 떠 있는 동안의 움직임. 그림 한 장을 코드로 움직인다. 쉬는 모습(회전 0, 보임)에서 벗어났다 돌아온다. */
enum class Lv4Idle {
    /** 가만히 버틴다 */
    NONE,

    /** 손 흔들듯 천천히 좌우로 기운다 */
    WAVE,

    /** 날갯짓하듯 빠르게 좌우로 흔들린다 */
    FLUTTER,

    /** 화면 한쪽에서 반대쪽으로 천천히 걷고, 끝에 닿으면 돌아서 다시 걷는다(창을 옮긴다) */
    WALK
}

/** Lv.4 탭 반응 종류. 쉬는 모습에서 시작해 쉬는 모습으로 끝난다. 프레임이 멈추면 반응만 빠지고 캐릭터는 보인다. */
enum class Lv4TapReaction {
    /** 좌우로 흔들린다 */
    SHAKE,

    /** 위아래로 통통 튄다 */
    BOUNCE,

    /** 발 구르듯 짧게 흔들린다 */
    STOMP,

    /** 걸음을 멈추고 잠깐 돌아본 뒤 다시 걷는다 */
    LOOK_BACK
}

/**
 * Lv.4 에서의 행동 값.
 * @param tapsToClear 치우는 데 필요한 탭 수
 * @param lineIntervalMs 탭하지 않아도 말풍선이 다음 대사로 바뀌는 간격. null 이면 탭할 때만 바뀐다
 * @param maxScale 탭할수록 커지는 캐릭터의 마지막 크기 배율. 1 이면 커지지 않는다
 */
data class Lv4Behavior(
    val tapsToClear: Int,
    val entrance: Lv4Entrance,
    val tapReaction: Lv4TapReaction,
    val idle: Lv4Idle = Lv4Idle.NONE,
    val lineIntervalMs: Long? = null,
    val maxScale: Float = 1f
)

/**
 * 캐릭터 하나의 정의.
 * @param id 저장값(선택, 개입 기록의 character_id)에 쓰는 고정 id. 바꾸지 않는다
 * @param personality 성격 설명. 고르는 화면에 쓴다
 * @param baseArt 기본 그림. 개입 옆 캐릭터(Lv.1~3), 홈·축하 화면과, Lv.4 그림이 없을 때 Lv.4 에 쓴다
 * @param lv4Art Lv.4 전용 그림. 없으면 null. 코드에 적지 않아도 res/drawable-nodpi 에 [Characters.lv4ArtName] 이름의
 *   파일을 넣으면 그 그림을 쓴다([CharacterArt])
 * @param lines 말풍선 대사. 비어 있으면 말풍선을 숨긴다. `{분}` 은 지금 세션 시청 시간(분)으로 바뀐다.
 *   기기에서 보여 줄 뿐 저장·전송하지 않는다. 규칙은 [CharacterLineRules]
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
 * 캐릭터 목록. 캐릭터 정보(이름·성격·그림·Lv.4 행동·대사)는 여기에만 둔다(15단계, 16단계에 4종으로 늘림).
 *
 * 캐릭터를 추가하려면 [ALL] 에 한 줄을 더하고 그림 파일(res/drawable-nodpi)을 넣는다. 고르는 화면, Lv.4 화면, 개입 옆 캐릭터,
 * 홈·축하 화면, 저장된 선택, 캐릭터별 집계는 이 목록을 따른다.
 */
object Characters {

    const val DEFAULT_ID = "kind"

    val ALL: List<CharacterSpec> = listOf(
        CharacterSpec(
            id = DEFAULT_ID,
            name = "다정이",
            personality = "따뜻하고 응원해 줘요",
            baseArt = R.drawable.wenit_character,
            lv4Art = null,
            lv4 = Lv4Behavior(
                tapsToClear = 2, entrance = Lv4Entrance.DROP, tapReaction = Lv4TapReaction.BOUNCE, idle = Lv4Idle.WAVE
            ),
            lines = listOf(
                "많이 봤다~ 잠깐 쉬어 볼까?",
                "벌써 {분}분이야! 기지개 켜자",
                "오늘도 잘하고 있어",
                "물 한 잔 마시고 올래?",
                "우리 여기서 멈춰 볼까?"
            )
        ),
        CharacterSpec(
            id = "chatty",
            name = "수다쟁이",
            personality = "말이 많고 시끄러워요",
            baseArt = R.drawable.character_chatty,
            lv4Art = null,
            lv4 = Lv4Behavior(
                tapsToClear = 3, entrance = Lv4Entrance.FLY_IN, tapReaction = Lv4TapReaction.SHAKE, idle = Lv4Idle.FLUTTER,
                lineIntervalMs = 2_000L
            ),
            lines = listOf(
                "어? 또 쇼츠야?",
                "벌써 {분}분째래! 진짜야!",
                "그거 알아? 방금 그거 세 번째야",
                "나랑 얘기하자! 쇼츠 말고!",
                "잠깐잠깐! 쉬었다 보자!",
                "오늘 할 일은 다 했어?"
            )
        ),
        CharacterSpec(
            id = "fierce",
            name = "표독이",
            personality = "까칠하고 고집이 세요",
            baseArt = R.drawable.character_fierce,
            lv4Art = null,
            lv4 = Lv4Behavior(
                tapsToClear = 5, entrance = Lv4Entrance.STAND, tapReaction = Lv4TapReaction.STOMP, idle = Lv4Idle.NONE,
                maxScale = 1.2f
            ),
            lines = listOf(
                "...또야?",
                "안 비켜.",
                "{분}분. 이제 됐잖아.",
                "딱 한 번만 봐준다.",
                "흥, 쉬고 와.",
                "고집은 내가 더 세."
            )
        ),
        CharacterSpec(
            id = "trudge",
            name = "터덜이",
            personality = "지치고 축 처져 있어요",
            baseArt = R.drawable.character_trudge,
            lv4Art = null,
            lv4 = Lv4Behavior(
                tapsToClear = 3, entrance = Lv4Entrance.WALK_IN, tapReaction = Lv4TapReaction.LOOK_BACK, idle = Lv4Idle.WALK
            ),
            lines = listOf(
                "하아... 나 좀 졸린데...",
                "같이 쉴래...?",
                "{분}분이나 봤네... 눈 아프다...",
                "나 먼저 갈게... 너도 와...",
                "터덜터덜... 쉬러 가자..."
            )
        )
    )

    val DEFAULT: CharacterSpec get() = ALL.first { it.id == DEFAULT_ID }

    /** 저장된 id 로 찾는다. 없거나 모르는 id(지운 캐릭터, 손상된 값)면 기본 캐릭터 */
    fun byId(id: String?): CharacterSpec = ALL.firstOrNull { it.id == id } ?: DEFAULT

    /** 아는 캐릭터 id 인가. 개입 기록의 캐릭터별 집계에서 모르는 값을 거른다 */
    fun isKnown(id: String?): Boolean = ALL.any { it.id == id }

    /** Lv.4 전용 그림의 리소스 이름 규칙: character_<id>_lv4 (예: character_fierce_lv4). 파일을 넣으면 쓰인다 */
    fun lv4ArtName(id: String): String = "character_${id}_lv4"
}

/**
 * 말풍선 대사 규칙. 결정론적이고 Android 의존성이 없다.
 *
 * 지금 대사는 [CharacterSpec.lines] 의 고정 대사뿐이다. 나중에 AI 가 캐릭터 말투로 만든 대사를 더할 수 있게
 * [merged] 가 추가 대사를 받는다(16단계에는 AI 를 쓰지 않아 늘 빈 목록이다). 추가 대사는 [isAllowed] 를 통과한 것만 쓴다.
 * 판정·단계·기록에는 관여하지 않는다.
 */
object CharacterLineRules {

    /** 대사 길이 상한. `{분}` 은 세 자리 숫자까지 같은 길이로 본다 */
    const val MAX_LENGTH = 25

    const val MINUTES_TOKEN = "{분}"

    /**
     * 쓰지 않는 표현: 욕설, 사용자를 비난하거나 창피하게 만드는 말, 외모·능력 비하. 까칠한 캐릭터도 같다.
     * 목록에 있는 말이 들어간 대사는 버린다(부분 일치).
     */
    val BANNED: List<String> = listOf(
        "바보", "멍청", "한심", "쓰레기", "찌질", "루저", "실패자", "쓸모없", "못난", "못생", "뚱뚱", "돼지",
        "게으름", "게으른", "중독자", "폐인", "창피", "부끄러운 줄", "수치", "최악", "꼴", "주제에",
        "시발", "씨발", "ㅅㅂ", "병신", "ㅂㅅ", "미친", "꺼져", "닥쳐", "죽어", "지랄", "새끼", "놈"
    )

    fun isAllowed(line: String): Boolean =
        line.isNotBlank() && line.length <= MAX_LENGTH && BANNED.none { it in line }

    /** 고정 대사 뒤에 규칙을 통과한 추가 대사를 붙인다. 같은 대사는 한 번만 */
    fun merged(spec: CharacterSpec, extra: List<String> = emptyList()): List<String> =
        (spec.lines + extra.filter(::isAllowed)).distinct()

    /** `{분}` 을 지금 세션 시청 시간(분)으로 바꾼다. 1분이 안 됐으면 1 로 쓴다("0분" 이라고 말하지 않는다) */
    fun fill(line: String, watchMs: Long): String =
        line.replace(MINUTES_TOKEN, (watchMs / MS_PER_MINUTE).coerceAtLeast(1L).toString())

    /** 고르는 화면에서 보여 줄 대사: 시청 시간이 들어가지 않는 대사를 차례로 돌려 쓴다. 없으면 null */
    fun previewLine(spec: CharacterSpec, index: Int): String? =
        Lv4CharacterRules.lineAt(spec.lines.filterNot { MINUTES_TOKEN in it }, index)

    private const val MS_PER_MINUTE = 60_000L
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
     * [taps] 번 탭한 뒤의 크기 배율. 마지막 탭(치워짐) 바로 전에 [Lv4Behavior.maxScale] 에 닿도록 고르게 커진다.
     * 커지지 않는 캐릭터는 늘 1.
     */
    fun scaleAfter(behavior: Lv4Behavior, taps: Int): Float {
        val steps = tapsNeeded(behavior) - 1
        if (behavior.maxScale <= 1f || steps <= 0) return 1f
        val t = taps.coerceIn(0, steps).toFloat() / steps
        return 1f + (behavior.maxScale - 1f) * t
    }

    /**
     * 걷기 한 걸음: [x] 에서 [direction](-1 왼쪽, +1 오른쪽)으로 [distance] 만큼 간다. [min]..[max] 끝에 닿으면 돌아선다.
     * @return 새 위치와 방향
     */
    fun walk(x: Int, direction: Int, distance: Int, min: Int, max: Int): Pair<Int, Int> {
        if (max <= min) return min to direction
        val next = x + direction * distance
        return when {
            next <= min -> min to 1
            next >= max -> max to -1
            else -> next to direction
        }
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

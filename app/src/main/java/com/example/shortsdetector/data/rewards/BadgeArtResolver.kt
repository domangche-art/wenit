package com.example.shortsdetector.data.rewards

/** 배지 한 칸에 무엇을 그릴지. 리소스 이름만 정하고 실제 그리기는 화면([com.example.shortsdetector.ui.BadgeMedal])이 한다. */
sealed interface BadgeArtSpec {
    /** 그 자체로 완성된 그림(배지·등급 전용 또는 배지 전용). 모양이 원이 아닐 수 있어 둥근 고리를 그리지 않는다. */
    data class Image(val name: String) : BadgeArtSpec

    /** 등급 틀 위에 배지 기호를 겹친다. 틀이 등급을 나타내므로 둥근 고리를 그리지 않는다. */
    data class Composite(val frame: String, val symbol: String) : BadgeArtSpec

    /** 공통 시험 그림. 등급은 바깥 고리 색으로 나타낸다. */
    data class Common(val name: String) : BadgeArtSpec
}

/**
 * 배지 그림 고르기. 순수 함수. 디자인 팀 파일을 drawable-nodpi 에 이 이름으로 넣으면 코드를 바꾸지 않고 바뀐다.
 *
 * 우선순위(위에서부터 처음 있는 것):
 *  1. 배지·등급 전용 `badge_<id>_t<등급 0~4>` (예: badge_time_rich_t3 = 시간 부자 다이아)
 *  2. 배지 전용 `badge_<id>` (등급 구분 없이 같은 그림, 13단계 규칙)
 *  3. 등급 틀 `badge_frame_t<등급>` + 배지 기호 `badge_symbol_<id>` 합성. 둘 다 있어야 한다
 *  4. 공통 `badge_common`
 *
 * 등급 없는 배지(첫걸음·첫 목표)는 등급 0 으로 찾는다. 틀 합성도 등급 0(브론즈) 틀을 쓴다.
 */
object BadgeArtResolver {

    const val COMMON = "badge_common"

    fun tierImage(id: String, tier: Int) = "badge_${id}_t$tier"
    fun badgeImage(id: String) = "badge_$id"
    fun frame(tier: Int) = "badge_frame_t$tier"
    fun symbol(id: String) = "badge_symbol_$id"

    /** @param exists 그 이름의 그림이 있는지 */
    fun resolve(id: String, tier: Int, exists: (String) -> Boolean): BadgeArtSpec {
        val t = tier.coerceIn(0, BadgeTier.entries.lastIndex)
        tierImage(id, t).takeIf(exists)?.let { return BadgeArtSpec.Image(it) }
        badgeImage(id).takeIf(exists)?.let { return BadgeArtSpec.Image(it) }
        if (exists(frame(t)) && exists(symbol(id))) return BadgeArtSpec.Composite(frame(t), symbol(id))
        return BadgeArtSpec.Common(COMMON)
    }
}

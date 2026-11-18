package com.example.shortsdetector.intervention.character

import android.content.Context
import androidx.annotation.DrawableRes

/**
 * 캐릭터 그림 찾기(16단계).
 *
 * Lv.4 그림은 순서대로: 정의에 적힌 [CharacterSpec.lv4Art] → res/drawable-nodpi 의 `character_<id>_lv4`
 * ([Characters.lv4ArtName], 예: character_fierce_lv4) → 기본 그림. 그래서 Lv.4 전용 그림 파일을 넣기만 하면 쓰인다.
 * 이름으로 찾는 방식은 배지 등급 그림(13단계)과 같다.
 */
object CharacterArt {

    @DrawableRes
    fun lv4(context: Context, spec: CharacterSpec): Int {
        spec.lv4Art?.let { return it }
        val byName = try {
            @Suppress("DiscouragedApi")
            context.resources.getIdentifier(Characters.lv4ArtName(spec.id), "drawable", context.packageName)
        } catch (e: RuntimeException) {
            0
        }
        return if (byName != 0) byName else spec.baseArt
    }
}

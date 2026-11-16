package com.example.shortsdetector.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.shortsdetector.detection.adapters.instagram.InstagramReelsAdapter
import com.example.shortsdetector.detection.adapters.youtube.YouTubeViewIds
import com.example.shortsdetector.intervention.character.CharacterSpec
import com.example.shortsdetector.intervention.character.Characters

/**
 * 사용자 감지 설정: 대상 앱 on/off, 화면 중앙 팝업 사용 여부. 개입 임계값은 개입 정책으로 옮겼다.
 *
 * YouTube 는 기존 동작과 같게 기본 켜짐, Instagram 은 규칙이 실기기 미검증이라 기본 꺼짐.
 */
object DetectionPreferences {

    private const val FILE = "detection_settings"
    const val KEY_YOUTUBE_ENABLED = "youtube_enabled"
    const val KEY_INSTAGRAM_ENABLED = "instagram_enabled"
    /** 예전 임계값 저장 키. [InterventionPolicyPreferences] 가 정책으로 한 번 옮기고 지운다. */
    const val LEGACY_KEY_WATCH_THRESHOLD_MS = "watch_threshold_ms"
    const val KEY_POPUP_ENABLED = "popup_enabled"
    const val KEY_CHARACTER_ENABLED = "character_enabled"
    const val KEY_SELECTED_CHARACTER = "selected_character"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isYouTubeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_YOUTUBE_ENABLED, true)

    fun isInstagramEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_INSTAGRAM_ENABLED, false)

    fun setYouTubeEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_YOUTUBE_ENABLED, enabled) }

    fun setInstagramEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_INSTAGRAM_ENABLED, enabled) }

    /** 끄면 팝업 없이 시스템 알림만 쓴다. */
    fun isPopupEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_POPUP_ENABLED, true)

    fun setPopupEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_POPUP_ENABLED, enabled) }

    /**
     * 개입(Lv.1~3) 옆에 캐릭터를 함께 띄운다(테스트용, 개발자 화면). 기본 켜짐. 끄면 캐릭터 없이 지금과 같다.
     * Lv.4(15단계)는 캐릭터가 개입 자체라 이 설정과 상관없이 캐릭터를 띄운다.
     */
    fun isCharacterEnabled(context: Context): Boolean = isCharacterEnabled(prefs(context))

    fun setCharacterEnabled(context: Context, enabled: Boolean) = setCharacterEnabled(prefs(context), enabled)

    internal fun isCharacterEnabled(p: SharedPreferences): Boolean = p.getBoolean(KEY_CHARACTER_ENABLED, true)

    internal fun setCharacterEnabled(p: SharedPreferences, enabled: Boolean) =
        p.edit { putBoolean(KEY_CHARACTER_ENABLED, enabled) }

    /**
     * 사용자가 고른 캐릭터 id(15단계). 고르는 화면은 아직 없어 기본 캐릭터다.
     * 모르는 id 는 [Characters.byId] 가 기본 캐릭터로 읽는다.
     */
    fun selectedCharacter(context: Context): CharacterSpec = Characters.byId(selectedCharacterId(prefs(context)))

    fun setSelectedCharacterId(context: Context, id: String) = setSelectedCharacterId(prefs(context), id)

    internal fun selectedCharacterId(p: SharedPreferences): String? = p.getString(KEY_SELECTED_CHARACTER, null)

    internal fun setSelectedCharacterId(p: SharedPreferences, id: String) = p.edit { putString(KEY_SELECTED_CHARACTER, id) }

    fun enabledPackages(context: Context): Set<String> = buildSet {
        if (isYouTubeEnabled(context)) add(YouTubeViewIds.PACKAGE)
        if (isInstagramEnabled(context)) add(InstagramReelsAdapter.PACKAGE)
    }
}

package com.example.shortsdetector.data.usage

import com.example.shortsdetector.detection.adapters.instagram.InstagramReelsAdapter
import com.example.shortsdetector.detection.adapters.youtube.YouTubeViewIds

/**
 * 세션이 어느 숏폼인지(17단계). 세션 기록의 package_name(DB 버전 1부터 저장)으로 정한다. DB 를 바꾸지 않는다.
 * 에이전트에는 패키지 이름 대신 [agentKey] 만 보낸다.
 */
enum class ShortformSource(val agentKey: String) {
    SHORTS("shorts"),
    REELS("reels");

    companion object {
        /** 모르는 패키지면 null(집계에서 뺀다. 하루 합계에는 그대로 들어간다) */
        fun of(packageName: String?): ShortformSource? = when (packageName) {
            YouTubeViewIds.PACKAGE -> SHORTS
            InstagramReelsAdapter.PACKAGE -> REELS
            else -> null
        }
    }
}

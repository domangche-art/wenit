package com.example.shortsdetector.data.usage

import com.example.shortsdetector.intervention.InterventionPolicyBounds

/**
 * 쇼츠 복귀를 시청이 확정된 경우만 인정한다. 결정론적이고 Android 의존성이 없다. 메인 스레드에서만 호출한다.
 *
 * 기준: 멈춤 뒤 숏폼 세션이 시작되거나 이어지고(감지 확정: 연속 3회 판정), 그 세션에서 도착한 뒤의 확정 시청시간이
 * [minWatchMs] 에 닿으면 쇼츠로 돌아온 것이다. 걸린 시간은 도착한 시각으로 잰다.
 *  - 확정 시청시간은 숏폼 화면이 온전히 보이거나 댓글창으로 줄어든 시간만 센다. 가림·이탈 대기·멈춘 영상은 빠진다.
 *  - 이어진 세션은 앞선 시청시간을 안고 오므로 도착할 때의 값을 빼고 잰다.
 *  - 기준에 닿기 전에 세션이 끝나면 쇼츠를 거쳐 간 것으로 보고, 도착 시각에 앱만 연 것으로 남긴다.
 *
 * [minWatchMs] 기본값은 개입 기준 시간의 하한(10초)이다. 앱이 "본 것" 으로 다루는 가장 짧은 시청시간이다.
 */
class ShortsReturnConfirmer(private val minWatchMs: Long = MIN_WATCH_MS) {

    private var session: Int? = null
    private var arrivedAtMs = 0L
    private var watchAtArrivalMs = 0L

    /** 숏폼 세션이 시작되거나 이어졌다. */
    fun onEntered(sessionNumber: Int, watchMs: Long, nowMs: Long) {
        session = sessionNumber
        arrivedAtMs = nowMs
        watchAtArrivalMs = watchMs
    }

    /** @return 이번에 시청이 확정됐으면 도착 시각. 한 번만 돌려준다. */
    fun onProgress(sessionNumber: Int?, watchMs: Long): Long? {
        val waiting = session ?: return null
        if (sessionNumber != waiting || watchMs - watchAtArrivalMs < minWatchMs) return null
        session = null
        return arrivedAtMs
    }

    /** @return 확정 전에 세션이 끝났으면 도착 시각(앱만 연 것으로 남긴다). 아니면 null. */
    fun onEnded(sessionNumber: Int): Long? {
        if (session != sessionNumber) return null
        session = null
        return arrivedAtMs
    }

    companion object {
        val MIN_WATCH_MS: Long = InterventionPolicyBounds.THRESHOLD_MS.first
    }
}

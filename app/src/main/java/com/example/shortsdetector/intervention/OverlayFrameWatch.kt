package com.example.shortsdetector.intervention

import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.View
import com.example.shortsdetector.ShortsDetectionService

/**
 * 오버레이를 띄운 뒤 화면 프레임이 오는지 본다(14-2단계). 진단 로그만 남기고 동작은 바꾸지 않는다.
 *
 * SM-S906N 에서, 앱 화면이 만들어진 적 있는 프로세스가 UI 모드(다크/라이트) 전환을 받은 뒤 서비스 메인 스레드의
 * Choreographer 프레임이 오지 않거나 크게 늦어지는 것을 확인했다(입력이 오면 풀리는 경우가 많음). 그동안 오버레이는 첫 그림만
 * 보이고 이후 갱신(카운트다운 숫자, 선택 표시 등)이 늦는다. 원인(OS 내부)은 확인하지 못했다.
 * 같은 일이 다시 생기면 이 로그로 알 수 있다. 화면 내용은 남기지 않는다.
 */
object OverlayFrameWatch {
    const val LIMIT_MS = 1_000L

    fun watch(view: View, what: String) {
        val posted = SystemClock.uptimeMillis()
        var firedAt = -1L
        try {
            Choreographer.getInstance().postFrameCallback { firedAt = SystemClock.uptimeMillis() }
        } catch (e: RuntimeException) {
            return
        }
        view.postDelayed({
            if (FrameDelay.stalled(posted, firedAt, SystemClock.uptimeMillis(), LIMIT_MS)) {
                Log.w(ShortsDetectionService.TAG, "$what: 화면 프레임이 ${LIMIT_MS}ms 넘게 오지 않음(오버레이 갱신이 늦어질 수 있음)")
            }
        }, LIMIT_MS)
    }
}

/** 순수 판정: 프레임 콜백이 [limitMs] 안에 오지 않았다. [firedAt] 이 음수면 아직 오지 않았다. */
object FrameDelay {
    fun stalled(postedAt: Long, firedAt: Long, now: Long, limitMs: Long): Boolean =
        if (firedAt < 0) now - postedAt >= limitMs else firedAt - postedAt >= limitMs
}

package com.example.shortsdetector.intervention.allowance

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.example.shortsdetector.R
import com.example.shortsdetector.ShortsDetectionService

/**
 * "영상을 탭하면 이어서 볼 수 있어요" 안내(14단계). 화면 세로 중앙보다 조금 아래에 [ResumeHintRule.SHOW_MS] 동안 띄운다.
 *  - FLAG_NOT_TOUCHABLE: 터치를 받지 않아 아래 영상 탭이 그대로 영상으로 간다.
 *  - 다시 재생되면 서비스가 [dismiss] 를 부른다. 이탈·세션 종료 때도 지운다.
 *  - 색은 팝업 색(values / values-night)을 쓰고, 서비스 창의 강제 다크(11단계에서 캐릭터가 사라진 원인)를 끈다.
 * 메인 스레드에서만 호출한다. 실패하면 조용히 넘어간다(안내가 없어도 동작은 같다).
 */
class ResumeHintOverlay(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null

    val isShowing: Boolean get() = view != null

    fun show() {
        dismiss()
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        try {
            val v = LayoutInflater.from(service).inflate(R.layout.overlay_resume_hint, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) v.isForceDarkAllowed = false
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                // 쇼츠의 채널 이름·설명 줄(아래쪽)과 겹치지 않게 화면 세로 중앙보다 조금 아래(14-2단계)
                gravity = Gravity.CENTER
                y = ResumeHintRule.centerOffsetPx(service.resources.displayMetrics.heightPixels)
                windowAnimations = android.R.style.Animation_Toast
            }
            wm.addView(v, params)
            view = v
            handler.postDelayed(::dismiss, ResumeHintRule.SHOW_MS)
            Log.i(ShortsDetectionService.TAG, "재생 안내 표시")
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "재생 안내 표시 실패: ${e.javaClass.simpleName}")
            dismiss()
        }
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        val v = view ?: return
        view = null
        try {
            service.getSystemService(WindowManager::class.java)?.removeViewImmediate(v)
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "재생 안내 제거 실패: ${e.javaClass.simpleName}")
        }
    }
}

package com.example.shortsdetector.intervention

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager

/** 개입 오버레이 창 설정 중 여러 곳에서 쓰는 것. 전부 TYPE_ACCESSIBILITY_OVERLAY 라 별도 권한이 필요 없다. */
internal object OverlayWindows {

    /**
     * [OverlayWindow.FLOATING]: 내용 크기의 창. FLAG_NOT_TOUCH_MODAL 이라 창 바깥 터치는 아래 앱으로 간다.
     * 위치는 화면 왼쪽 위 기준(FLAG_LAYOUT_IN_SCREEN)이라 끌기나 계산한 좌표로 옮기기 쉽다.
     */
    fun floatingParams(
        width: Int = WindowManager.LayoutParams.WRAP_CONTENT,
        height: Int = WindowManager.LayoutParams.WRAP_CONTENT
    ) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }
}

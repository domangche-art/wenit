package com.example.shortsdetector.intervention.character

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import com.example.shortsdetector.R
import com.example.shortsdetector.ShortsDetectionService
import com.example.shortsdetector.intervention.OverlayWindow
import com.example.shortsdetector.intervention.OverlayWindows

/**
 * 캐릭터 한 마리를 [OverlayWindow.FLOATING] 창으로 띄운다. 개입 단계·문구·기록과 무관하게 "어디에, 어떻게 나타나고
 * 사라지는가" 만 맡는다. 지금은 개입 팝업 옆에 붙지만(테스트용), 화면을 방해하는 캐릭터 단계(Lv.4)를 만들 때도
 * 이 클래스에 새 [CharacterAnchor] 나 동작을 더해 다시 쓴다.
 *
 *  - 창은 캐릭터 이미지 크기다. 창 위 터치만 받고 나머지는 아래 화면으로 간다(FLAG_NOT_TOUCH_MODAL).
 *    한 창 안에서 투명한 부분만 통과시키는 공개 API 는 없어서, 이미지의 사각 영역이 터치 영역이다.
 *  - 나타날 때 작게 시작해 튀어오른다. 탭하면 캐릭터만 사라진다. [dismiss] 는 바로 지운다(개입이 닫힐 때).
 *  - 포커스를 가져가지 않는다. 예외는 여기서 격리한다. 캐릭터가 실패해도 개입은 그대로다.
 *
 * 메인 스레드에서만 호출한다. TODO: VERIFY ON DEVICE — 제조사 ROM 의 창 순서와 애니메이션.
 */
class CharacterOverlay(
    private val service: AccessibilityService,
    /** 띄울 때마다 확인한다. false 면 아무것도 하지 않는다. */
    private val enabled: () -> Boolean
) {
    private var view: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var anchor: CharacterAnchor? = null
    private var baseWidth = 0
    private var baseHeight = 0

    val isShowing: Boolean get() = view != null

    /**
     * @param widthPx 캐릭터 기본 너비. 높이는 이미지 비율로 정한다. 자리가 모자라면 줄인다.
     * @return 띄웠으면 true
     */
    fun show(anchor: CharacterAnchor, widthPx: Int): Boolean {
        dismiss()
        if (!isEnabled()) return skipped("설정 꺼짐")
        val wm = service.getSystemService(WindowManager::class.java) ?: return skipped("WindowManager 없음")
        return try {
            val drawable = service.getDrawable(R.drawable.wenit_character) ?: return skipped("그림 없음")
            baseWidth = widthPx
            baseHeight = (widthPx.toLong() * drawable.intrinsicHeight / drawable.intrinsicWidth.coerceAtLeast(1)).toInt()
            val screen = screenBox(wm)
            val placement = CharacterPlacement.place(screen, anchor, baseWidth, baseHeight)
                ?: return skipped("자리 없음 screen=$screen anchor=$anchor size=${baseWidth}x$baseHeight")
            val v = ImageView(service).apply {
                setImageDrawable(drawable)
                scaleType = ImageView.ScaleType.FIT_CENTER
                // 튀어오를 때 1 을 넘는 확대분이 창 밖으로 잘리지 않게 안쪽 여백을 둔다.
                val padX = (placement.width * OVERSHOOT_ROOM).toInt()
                val padY = (placement.height * OVERSHOOT_ROOM).toInt()
                setPadding(padX, padY, padX, padY)
                contentDescription = service.getString(R.string.popup_character_description)
                setOnClickListener { hideByTap() }
                // 서비스 Context 의 테마는 앱 테마가 아니라 기기 기본 밝은 테마라, 시스템 다크 모드에서 이 창이 강제 다크로
                // 그려진다. 그러면 캐릭터 그림이 보이지 않았다(SM-S906N, 11단계). 그림은 다시 칠하지 않는다.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
            }
            val p = OverlayWindows.floatingParams(placement.width, placement.height).apply {
                x = placement.x
                y = placement.y
            }
            wm.addView(v, p)
            view = v
            params = p
            this.anchor = anchor
            popIn(v, placement)
            Log.i(ShortsDetectionService.TAG, "캐릭터 표시: ${placement.width}x${placement.height} at (${placement.x}, ${placement.y})")
            true
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "캐릭터 표시 실패: ${e.javaClass.simpleName}")
            dismiss()
            false
        }
    }

    /** 피할 곳이 움직였다(개입 화면이 다시 배치됨). 애니메이션 없이 자리만 옮긴다. 자리가 없으면 숨긴다. */
    fun moveTo(anchor: CharacterAnchor) {
        val v = view ?: return
        val p = params ?: return
        if (anchor == this.anchor) return
        this.anchor = anchor
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        val placement = CharacterPlacement.place(screenBox(wm), anchor, baseWidth, baseHeight)
        if (placement == null) {
            skipped("옮길 자리 없음 anchor=$anchor")
            dismiss()
            return
        }
        p.x = placement.x
        p.y = placement.y
        p.width = placement.width
        p.height = placement.height
        try {
            wm.updateViewLayout(v, p)
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "캐릭터 이동 실패: ${e.javaClass.simpleName}")
        }
    }

    /** 바로 지운다. 개입이 닫힐 때 부른다. */
    fun dismiss() {
        val v = view ?: return
        view = null
        params = null
        anchor = null
        v.animate().cancel()
        try {
            service.getSystemService(WindowManager::class.java)?.removeViewImmediate(v)
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "캐릭터 제거 실패: ${e.javaClass.simpleName}")
        }
    }

    /** 띄우지 않은 이유를 남긴다. 좌표·크기 같은 구조 값만 쓴다. */
    private fun skipped(reason: String): Boolean {
        Log.i(ShortsDetectionService.TAG, "캐릭터 표시 안 함: $reason")
        return false
    }

    private fun isEnabled(): Boolean = try {
        enabled()
    } catch (e: RuntimeException) {
        false
    }

    /** 작게(아래쪽 기준) 시작해서 튀어오른다. */
    private fun popIn(v: View, placement: Placement) {
        v.pivotX = placement.width / 2f
        v.pivotY = placement.height.toFloat()
        v.scaleX = START_SCALE
        v.scaleY = START_SCALE
        v.alpha = 0f
        v.animate()
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(POP_IN_MS)
            .setInterpolator(OvershootInterpolator(OVERSHOOT))
            .start()
    }

    /** 탭: 캐릭터만 줄어들며 사라진다. 개입은 그대로다. */
    private fun hideByTap() {
        val v = view ?: return
        v.isClickable = false
        v.animate()
            .scaleX(TAP_END_SCALE).scaleY(TAP_END_SCALE).alpha(0f)
            .setDuration(TAP_OUT_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { if (view === v) dismiss() }
            .start()
    }

    /** 상태 표시줄 아래부터 화면 끝까지 */
    @Suppress("DEPRECATION")
    private fun screenBox(wm: WindowManager): Box {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = wm.currentWindowMetrics
            val insets = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            val b = m.bounds
            return Box(b.left, b.top + insets.top, b.right, b.bottom - insets.bottom)
        }
        val dm = service.resources.displayMetrics
        val status = (FALLBACK_STATUS_BAR_DP * dm.density).toInt()
        return Box(0, status, dm.widthPixels, dm.heightPixels)
    }

    private companion object {
        const val START_SCALE = 0.3f
        /** 최대 약 1.07배까지 튄다. [OVERSHOOT_ROOM] 여백 안에 들어온다. */
        const val OVERSHOOT = 2.0f
        const val OVERSHOOT_ROOM = 0.06f
        const val POP_IN_MS = 420L
        const val TAP_END_SCALE = 0.6f
        const val TAP_OUT_MS = 160L
        const val FALLBACK_STATUS_BAR_DP = 24
    }
}

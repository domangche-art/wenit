package com.example.shortsdetector.intervention.character

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.ObjectAnimator
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import com.example.shortsdetector.R
import androidx.annotation.StyleRes
import com.example.shortsdetector.ShortsDetectionService
import com.example.shortsdetector.data.usage.CharacterDismissal
import com.example.shortsdetector.intervention.OverlayFrameWatch
import com.example.shortsdetector.intervention.OverlayWindow
import com.example.shortsdetector.intervention.OverlayWindows

/**
 * 캐릭터 한 마리를 [OverlayWindow.FLOATING] 창으로 띄운다. 개입 단계·문구·기록과 무관하게 "어디에, 어떻게 나타나고
 * 사라지는가" 만 맡는다. 개입(Lv.1~3) 팝업 옆 캐릭터(테스트용)와, 화면을 막는 캐릭터 단계(Lv.4, 15단계 [showBlocking])를 띄운다.
 * 그림은 고른 캐릭터([Characters])의 것을 쓴다.
 *
 *  - 창은 캐릭터 이미지 크기다. 창 위 터치만 받고 나머지는 아래 화면으로 간다(FLAG_NOT_TOUCH_MODAL).
 *    한 창 안에서 투명한 부분만 통과시키는 공개 API 는 없어서, 이미지의 사각 영역이 터치 영역이다.
 *  - 나타날 때 작게 시작해 튀어오른다. 탭하면 캐릭터만 사라진다. [dismiss] 는 개입이 닫힐 때 지운다.
 *  - 등장·퇴장은 창 애니메이션([R.style.CharacterWindowAnimation])이다(14-2단계). 시스템이 창에 적용하므로, 서비스 프로세스가
 *    화면 프레임을 받지 못해도 캐릭터 뷰는 처음부터 최종 상태(보임)로 그려진다. 예전에는 뷰를 투명·0.3배로 그린 뒤 앱 쪽
 *    애니메이터로 키웠는데, 다크/라이트 전환 뒤 프레임이 멈추면 투명한 첫 그림이 남아 "창은 있는데 안 보이는" 상태가 됐다.
 *  - 포커스를 가져가지 않는다. 예외는 여기서 격리한다. 캐릭터가 실패해도 개입은 그대로다.
 *
 * 메인 스레드에서만 호출한다. TODO: VERIFY ON DEVICE — 제조사 ROM 의 창 순서와 애니메이션.
 */
class CharacterOverlay(
    private val service: AccessibilityService,
    /** 띄울 때마다 확인한다. false 면 개입 옆 캐릭터를 띄우지 않는다. Lv.4([showBlocking])는 이 값과 상관없다. */
    private val enabled: () -> Boolean,
    /** 띄울 캐릭터(사용자가 고른 캐릭터, 15단계). 띄울 때마다 읽는다. */
    private val character: () -> CharacterSpec = { Characters.DEFAULT }
) {
    /** Lv.4 캐릭터의 탭·끌기 결과 */
    interface BlockingListener {
        /** 탭할 때마다. [tapsLeft] 가 0 이면 곧 [onCleared] 가 온다. [taps] 는 지금까지 탭 수 */
        fun onTap(taps: Int, tapsLeft: Int)

        /** 캐릭터가 치워졌다. 캐릭터 창은 이미 지웠다(퇴장 창 애니메이션). 한 번만 온다. */
        fun onCleared(how: CharacterDismissal)
    }

    private var view: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var anchor: CharacterAnchor? = null
    private var baseWidth = 0
    private var baseHeight = 0
    private var shake: Animator? = null

    val isShowing: Boolean get() = view != null

    /**
     * Lv.4: [slot](화면 좌표) 자리에 캐릭터를 띄운다. 위에서 떨어지듯 나타나고(창 애니메이션), 탭할 때마다 흔들리며,
     * 필요한 횟수만큼 탭하거나 끌어서 화면 밖으로 밀어내면 사라지고 [listener] 에 알린다.
     *
     * 개입 옆 캐릭터 설정([enabled])과 상관없다. 실패하면 false 이고, 호출 측은 캐릭터 없이 끝낼 수 있는 버튼을 보여 줘야 한다.
     * 뷰는 처음부터 보이는 모습(투명 아님, 회전 0)으로 그린다. 흔들림은 쉬는 모습에서 시작해 쉬는 모습으로 끝나므로 화면 프레임이
     * 멈춰도 캐릭터는 보인다(14-2단계).
     */
    fun showBlocking(slot: Box, spec: CharacterSpec, listener: BlockingListener): Boolean {
        dismiss()
        val wm = service.getSystemService(WindowManager::class.java) ?: return skipped("Lv.4 WindowManager 없음")
        if (slot.width <= 0 || slot.height <= 0) return skipped("Lv.4 자리 없음 slot=$slot")
        return try {
            val drawable = service.getDrawable(spec.lv4ArtOrBase) ?: return skipped("Lv.4 그림 없음")
            val padX = (slot.width * SHAKE_ROOM).toInt()
            val padY = (slot.height * SHAKE_ROOM).toInt()
            val home = Placement(slot.left - padX, slot.top - padY, slot.width + padX * 2, slot.height + padY * 2)
            val screen = screenBox(wm)
            var taps = 0
            var cleared = false
            lateinit var v: ImageView
            fun clear(how: CharacterDismissal) {
                if (cleared) return
                cleared = true
                v.isClickable = false
                leaveWith(
                    if (how == CharacterDismissal.TAP) R.style.CharacterLv4FlingWindowAnimation
                    else R.style.CharacterLv4DragOutWindowAnimation
                )
                Log.i(ShortsDetectionService.TAG, "Lv.4 캐릭터 치움: $how 탭=$taps")
                listener.onCleared(how)
            }
            v = ImageView(service).apply {
                setImageDrawable(drawable)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(padX, padY, padX, padY)
                contentDescription = service.getString(R.string.popup_lv4_character_description, spec.name)
                // 접근성(TalkBack 두 번 탭)도 같은 탭으로 센다. 손가락 탭은 아래 터치 처리에서 performClick 으로 온다.
                setOnClickListener {
                    if (cleared) return@setOnClickListener
                    taps++
                    val left = Lv4CharacterRules.tapsLeft(spec.lv4, taps)
                    listener.onTap(taps, left)
                    if (left == 0) clear(CharacterDismissal.TAP) else react(this, spec.lv4.tapReaction)
                }
                setOnTouchListener(BlockingDrag(home, screen, onOut = { clear(CharacterDismissal.DRAG) }))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
            }
            val p = OverlayWindows.floatingParams(home.width, home.height).apply {
                x = home.x
                y = home.y
                // 끌 때 화면 밖으로 나갈 수 있게 한다. TODO: VERIFY ON DEVICE — 제조사 ROM 이 창을 화면 안으로 잡아 두는지
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                windowAnimations = spec.lv4.entrance.windowAnimation
            }
            wm.addView(v, p)
            view = v
            params = p
            anchor = null
            Log.i(ShortsDetectionService.TAG, "Lv.4 캐릭터 표시: ${home.width}x${home.height} at (${home.x}, ${home.y}) 탭=${Lv4CharacterRules.tapsNeeded(spec.lv4)}")
            OverlayFrameWatch.watch(v, "Lv.4 캐릭터")
            true
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "Lv.4 캐릭터 표시 실패: ${e.javaClass.simpleName}")
            dismiss()
            false
        }
    }

    /** 퇴장 창 애니메이션을 바꾼 뒤 지운다. */
    private fun leaveWith(@StyleRes animation: Int) {
        val v = view ?: return
        val p = params ?: return
        p.windowAnimations = animation
        try {
            service.getSystemService(WindowManager::class.java)?.updateViewLayout(v, p)
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "캐릭터 퇴장 설정 실패: ${e.javaClass.simpleName}")
        }
        dismiss()
    }

    /** 탭 반응. 회전 0 에서 시작해 0 으로 끝난다. 앞선 반응은 멈추고 쉬는 모습으로 되돌린 뒤 다시 시작한다. */
    private fun react(v: View, reaction: Lv4TapReaction) {
        shake?.cancel()
        v.rotation = 0f
        shake = when (reaction) {
            Lv4TapReaction.SHAKE -> ObjectAnimator.ofFloat(v, View.ROTATION, 0f, -14f, 12f, -8f, 5f, 0f).setDuration(SHAKE_MS)
        }.also { it.start() }
    }

    /**
     * Lv.4 캐릭터 끌기. 손가락이 터치 슬롭보다 움직이면 끌기로 보고 창을 따라 옮긴다. 놓았을 때 가운데가 화면 밖이면 치운 것,
     * 아니면 제자리로 돌아간다. 움직이지 않고 놓으면 탭(performClick)이다.
     */
    private inner class BlockingDrag(
        private val home: Placement,
        private val screen: Box,
        private val onOut: () -> Unit
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var dragging = false
        private var x = home.x
        private var y = home.y

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && dx * dx + dy * dy > slop * slop) dragging = true
                    if (dragging) moveTo(home.x + dx.toInt(), home.y + dy.toInt())
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        v.performClick()
                    } else if (Lv4CharacterRules.draggedOut(Placement(x, y, home.width, home.height).box, screen)) {
                        onOut()
                    } else {
                        moveTo(home.x, home.y)
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) moveTo(home.x, home.y)
                    dragging = false
                }
            }
            return true
        }

        private fun moveTo(nx: Int, ny: Int) {
            x = nx
            y = ny
            val v = view ?: return
            val p = params ?: return
            p.x = nx
            p.y = ny
            try {
                service.getSystemService(WindowManager::class.java)?.updateViewLayout(v, p)
            } catch (e: RuntimeException) {
                Log.w(ShortsDetectionService.TAG, "Lv.4 캐릭터 이동 실패: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * @param widthPx 캐릭터 기본 너비. 높이는 이미지 비율로 정한다. 자리가 모자라면 줄인다.
     * @return 띄웠으면 true
     */
    fun show(anchor: CharacterAnchor, widthPx: Int): Boolean {
        dismiss()
        if (!isEnabled()) return skipped("설정 꺼짐")
        val wm = service.getSystemService(WindowManager::class.java) ?: return skipped("WindowManager 없음")
        return try {
            val drawable = service.getDrawable(currentCharacter().baseArt) ?: return skipped("그림 없음")
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
                windowAnimations = R.style.CharacterWindowAnimation
            }
            wm.addView(v, p)
            view = v
            params = p
            this.anchor = anchor
            Log.i(ShortsDetectionService.TAG, "캐릭터 표시: ${placement.width}x${placement.height} at (${placement.x}, ${placement.y})")
            OverlayFrameWatch.watch(v, "캐릭터")
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
        shake?.cancel()
        shake = null
        val v = view ?: return
        view = null
        params = null
        anchor = null
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

    private fun currentCharacter(): CharacterSpec = try {
        character()
    } catch (e: RuntimeException) {
        Characters.DEFAULT
    }

    /** 작게(아래쪽 기준) 시작해서 튀어오른다. */
    /** 탭: 캐릭터만 사라진다(퇴장 창 애니메이션). 개입은 그대로다. 앱 쪽 애니메이터를 기다리지 않는다. */
    private fun hideByTap() {
        val v = view ?: return
        v.isClickable = false
        dismiss()
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
        /** 등장 애니메이션(res/anim/character_pop_in.xml, overshoot 2.0)이 최대 약 1.07배까지 튄다. 그 여백(11단계와 같은 크기). */
        const val OVERSHOOT_ROOM = 0.06f
        const val FALLBACK_STATUS_BAR_DP = 24

        /** Lv.4 흔들림(최대 약 14도)이 창 밖으로 잘리지 않게 두는 안쪽 여백 비율 */
        const val SHAKE_ROOM = 0.12f
        const val SHAKE_MS = 360L
    }
}

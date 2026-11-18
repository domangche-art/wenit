package com.example.shortsdetector.intervention.character

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.Build
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import kotlin.math.PI
import kotlin.math.sin
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

        /** 캐릭터 창이 가로로 움직였다(걷기·끌기·크기 변경, 처음 표시 포함). [centerX] 는 캐릭터 가운데의 화면 x 좌표 */
        fun onMoved(centerX: Int) {}
    }

    private var view: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var anchor: CharacterAnchor? = null
    private var baseWidth = 0
    private var baseHeight = 0
    private var blocking: Blocking? = null

    val isShowing: Boolean get() = view != null

    /**
     * Lv.4: [slot](화면 좌표) 자리에 캐릭터를 띄운다. 캐릭터마다 정해진 방식으로 나타나고(창 애니메이션) 움직이며([Lv4Idle]),
     * 탭할 때마다 반응하고([Lv4TapReaction]), 필요한 횟수만큼 탭하거나 끌어서 화면 밖으로 밀어내면 사라지고 [listener] 에 알린다.
     *
     * 개입 옆 캐릭터 설정([enabled])과 상관없다. 실패하면 false 이고, 호출 측은 캐릭터 없이 끝낼 수 있는 버튼을 보여 줘야 한다.
     * 뷰는 처음부터 보이는 모습(투명 아님, 회전 0)으로 그린다. 움직임과 반응은 쉬는 모습에서 시작해 쉬는 모습으로 돌아오므로
     * 화면 프레임이 멈춰도 캐릭터는 보인다(14-2단계). 그림 한 장을 코드로 움직인다(16단계).
     */
    fun showBlocking(slot: Box, spec: CharacterSpec, listener: BlockingListener): Boolean {
        dismiss()
        val wm = service.getSystemService(WindowManager::class.java) ?: return skipped("Lv.4 WindowManager 없음")
        if (slot.width <= 0 || slot.height <= 0) return skipped("Lv.4 자리 없음 slot=$slot")
        return try {
            val drawable = service.getDrawable(CharacterArt.lv4(service, spec)) ?: return skipped("Lv.4 그림 없음")
            val b = Blocking(spec, screenBox(wm), listener)
            val home = b.place(slot)
            val v = ImageView(service).apply {
                setImageDrawable(drawable)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(b.padX, b.padY, b.padX, b.padY)
                contentDescription = service.getString(R.string.popup_lv4_character_description, spec.name)
                // 접근성(TalkBack 두 번 탭)도 같은 탭으로 센다. 손가락 탭은 아래 터치 처리에서 performClick 으로 온다.
                setOnClickListener { b.tapped(this) }
                setOnTouchListener(BlockingDrag(b))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) isForceDarkAllowed = false
            }
            val p = OverlayWindows.floatingParams(home.width, home.height).apply {
                x = b.x
                y = b.y
                // 끌 때(그리고 걸을 때) 화면 밖으로 나갈 수 있게 한다
                flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                windowAnimations = spec.lv4.entrance.windowAnimation
            }
            wm.addView(v, p)
            view = v
            params = p
            anchor = null
            blocking = b
            b.startIdle(v)
            b.notifyMoved()
            Log.i(
                ShortsDetectionService.TAG,
                "Lv.4 캐릭터 표시: ${spec.id} ${home.width}x${home.height} at (${b.x}, ${b.y}) 탭=${Lv4CharacterRules.tapsNeeded(spec.lv4)}"
            )
            OverlayFrameWatch.watch(v, "Lv.4 캐릭터")
            true
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "Lv.4 캐릭터 표시 실패: ${e.javaClass.simpleName}")
            dismiss()
            false
        }
    }

    /**
     * Lv.4 캐릭터 자리가 바뀌었다(탭할수록 커지는 캐릭터: 막 안의 자리가 커진다). 창과 그림을 새 자리에 맞춘다.
     * 걷는 캐릭터는 지금 걷던 가로 위치를 유지한다.
     */
    fun resizeBlocking(slot: Box) {
        val b = blocking ?: return
        val v = view ?: return
        val p = params ?: return
        if (slot.width <= 0 || slot.height <= 0) return
        val home = b.place(slot, keepX = true)
        v.setPadding(b.padX, b.padY, b.padX, b.padY)
        p.width = home.width
        p.height = home.height
        p.x = b.x
        p.y = b.y
        try {
            service.getSystemService(WindowManager::class.java)?.updateViewLayout(v, p)
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "Lv.4 캐릭터 크기 변경 실패: ${e.javaClass.simpleName}")
        }
        b.notifyMoved()
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

    private fun moveBlockingWindow(nx: Int, ny: Int) {
        val v = view ?: return
        val p = params ?: return
        if (p.x == nx && p.y == ny) return
        val movedX = p.x != nx
        p.x = nx
        p.y = ny
        try {
            service.getSystemService(WindowManager::class.java)?.updateViewLayout(v, p)
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "Lv.4 캐릭터 이동 실패: ${e.javaClass.simpleName}")
        }
        if (movedX) blocking?.notifyMoved()
    }

    /**
     * 떠 있는 Lv.4 캐릭터 하나의 상태: 자리, 탭 수, 대기 움직임, 탭 반응.
     * 대기 움직임과 탭 반응은 뷰의 회전·이동·좌우 뒤집기만 쓴다(투명도·크기 0 은 쓰지 않는다).
     */
    private inner class Blocking(val spec: CharacterSpec, val screen: Box, val listener: BlockingListener) {
        /** 캐릭터 창의 제자리. 걷는 캐릭터는 세로 위치와 크기만 쓴다 */
        var home = Placement(0, 0, 0, 0)
            private set
        var padX = 0
            private set
        var padY = 0
            private set

        /** 창의 지금 위치(끌기·걷기로 바뀐다) */
        var x = 0
        var y = 0
        private var taps = 0
        private var cleared = false
        private var idle: Animator? = null
        private var reaction: Animator? = null
        private var direction = -1
        private var holds = 0
        private var lastFrameNs = 0L
        private var carry = 0f
        private val walks get() = spec.lv4.idle == Lv4Idle.WALK

        /** 자리 [slot] 에 맞춰 창 크기와 위치를 정한다. 그림 둘레에 움직임이 잘리지 않을 여백을 둔다. */
        fun place(slot: Box, keepX: Boolean = false): Placement {
            padX = (slot.width * MOTION_ROOM).toInt()
            padY = (slot.height * MOTION_ROOM).toInt()
            home = Placement(slot.left - padX, slot.top - padY, slot.width + padX * 2, slot.height + padY * 2)
            // 걷는 캐릭터는 화면 오른쪽 끝에서 시작한다(그림이 왼쪽을 보고 있어 왼쪽으로 걷는다)
            x = when {
                !walks -> home.x
                keepX -> x.coerceIn(walkMin, walkMax)
                else -> walkMax
            }
            y = home.y
            return home
        }

        /** 지금 캐릭터 가운데 x 를 알린다. 알림이 실패해도 캐릭터는 그대로다 */
        fun notifyMoved() {
            if (cleared) return
            try {
                listener.onMoved(x + home.width / 2)
            } catch (e: RuntimeException) {
                Log.w(ShortsDetectionService.TAG, "Lv.4 캐릭터 위치 알림 실패: ${e.javaClass.simpleName}")
            }
        }

        private val walkMin get() = screen.left - padX
        private val walkMax get() = screen.right - home.width + padX

        fun tapped(v: View) {
            if (cleared) return
            taps++
            val left = Lv4CharacterRules.tapsLeft(spec.lv4, taps)
            listener.onTap(taps, left)
            if (left == 0) clear(v, CharacterDismissal.TAP) else react(v)
        }

        fun clear(v: View, how: CharacterDismissal) {
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

        fun startIdle(v: View) {
            idle = when (spec.lv4.idle) {
                Lv4Idle.NONE -> null
                Lv4Idle.WAVE -> swing(v, WAVE_DEGREES, WAVE_MS)
                Lv4Idle.FLUTTER -> swing(v, FLUTTER_DEGREES, FLUTTER_MS)
                Lv4Idle.WALK -> ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = WALK_BOB_MS
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { step(v, it.animatedFraction) }
                }
            }?.also { it.start() }
        }

        private fun swing(v: View, degrees: Float, ms: Long): Animator =
            ObjectAnimator.ofFloat(v, View.ROTATION, 0f, -degrees, 0f, degrees, 0f).apply {
                duration = ms
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
            }

        /** 걷기 한 프레임: 지난 시간만큼 창을 옮기고 좌우로 살짝 기운다. 멈춰 있는 동안(끌기·돌아보기)은 옮기지 않는다. */
        private fun step(v: View, fraction: Float) {
            val now = System.nanoTime()
            val dtMs = if (lastFrameNs == 0L) 0L else ((now - lastFrameNs) / NS_PER_MS).coerceAtMost(MAX_FRAME_MS)
            lastFrameNs = now
            if (holds > 0 || cleared) return
            v.rotation = WALK_DEGREES * sin(fraction * 2.0 * PI).toFloat()
            // 한 프레임에 1px 이 안 되는 거리는 모아 두었다가 옮긴다(주사율과 상관없이 같은 속도)
            carry += (walkMax - walkMin).toFloat() * dtMs / WALK_CROSS_MS
            val distance = carry.toInt()
            if (distance == 0) return
            carry -= distance
            val (nx, nd) = Lv4CharacterRules.walk(x, direction, distance, walkMin, walkMax)
            x = nx
            if (nd != direction) {
                direction = nd
                face(v)
            }
            moveBlockingWindow(x, y)
        }

        /** 그림은 왼쪽을 보고 있다. 오른쪽으로 걸을 때 좌우를 뒤집는다 */
        private fun face(v: View, lookingBack: Boolean = false) {
            val right = (direction > 0) != lookingBack
            v.scaleX = if (right) -1f else 1f
        }

        /** 걷기를 잠깐 세운다(끌기, 돌아보기). [release] 로 푼다 */
        fun hold(v: View) {
            holds++
            if (walks) v.rotation = 0f
        }

        fun release() {
            if (holds > 0) holds--
        }

        /** 탭 반응. 대기 움직임을 잠깐 멈추고 쉬는 모습에서 시작해 쉬는 모습으로 끝난다. */
        private fun react(v: View) {
            reaction?.cancel()
            val h = home.height.toFloat()
            val a: Animator? = when (spec.lv4.tapReaction) {
                Lv4TapReaction.SHAKE ->
                    ObjectAnimator.ofFloat(v, View.ROTATION, 0f, -14f, 12f, -8f, 5f, 0f).setDuration(SHAKE_MS)
                Lv4TapReaction.BOUNCE ->
                    ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, -h * 0.09f, 0f, -h * 0.05f, 0f).setDuration(BOUNCE_MS)
                Lv4TapReaction.STOMP -> AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(v, View.ROTATION, 0f, -6f, 5f, -3f, 0f),
                        ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, h * 0.03f, 0f, h * 0.03f, 0f)
                    )
                    duration = STOMP_MS
                }
                Lv4TapReaction.LOOK_BACK -> {
                    // 걸음을 멈추고 잠깐 돌아본 뒤 다시 걷는다. 뷰 애니메이션이 아니라 좌우 뒤집기와 타이머다(프레임이 멈춰도 풀린다)
                    hold(v)
                    face(v, lookingBack = true)
                    v.postDelayed({
                        if (view === v && !cleared) face(v)
                        release()
                    }, LOOK_BACK_MS)
                    null
                }
            }
            if (a == null) return
            idle?.pause()
            v.rotation = 0f
            v.translationY = 0f
            a.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    v.rotation = 0f
                    v.translationY = 0f
                    if (reaction === animation) {
                        reaction = null
                        idle?.resume()
                    }
                }
            })
            reaction = a
            a.start()
        }

        fun stop() {
            reaction?.cancel()
            reaction = null
            idle?.cancel()
            idle = null
        }
    }

    /**
     * Lv.4 캐릭터 끌기. 손가락이 터치 슬롭보다 움직이면 끌기로 보고 창을 따라 옮긴다. 놓았을 때 가운데가 화면 밖이면 치운 것,
     * 아니면 끌기 전 자리로 돌아간다. 움직이지 않고 놓으면 탭(performClick)이다. 걷는 캐릭터는 끄는 동안 걸음을 멈춘다.
     */
    private inner class BlockingDrag(private val b: Blocking) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var baseX = 0
        private var baseY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    baseX = b.x
                    baseY = b.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && dx * dx + dy * dy > slop * slop) {
                        dragging = true
                        b.hold(v)
                    }
                    if (dragging) moveTo(baseX + dx.toInt(), baseY + dy.toInt())
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        v.performClick()
                    } else if (Lv4CharacterRules.draggedOut(Placement(b.x, b.y, b.home.width, b.home.height).box, b.screen)) {
                        b.clear(v, CharacterDismissal.DRAG)
                    } else {
                        moveTo(baseX, baseY)
                        b.release()
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        moveTo(baseX, baseY)
                        b.release()
                    }
                    dragging = false
                }
            }
            return true
        }

        private fun moveTo(nx: Int, ny: Int) {
            b.x = nx
            b.y = ny
            moveBlockingWindow(nx, ny)
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
        blocking?.stop()
        blocking = null
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

        /** Lv.4 움직임(흔들림 최대 약 14도, 통통 튀기)이 창 밖으로 잘리지 않게 두는 안쪽 여백 비율 */
        const val MOTION_ROOM = 0.12f
        const val SHAKE_MS = 360L
        const val BOUNCE_MS = 420L
        const val STOMP_MS = 240L
        const val LOOK_BACK_MS = 700L

        /** 다정이: 손 흔들듯 천천히 기운다 */
        const val WAVE_DEGREES = 5f
        const val WAVE_MS = 1_600L

        /** 수다쟁이: 날갯짓하듯 빠르게 흔들린다 */
        const val FLUTTER_DEGREES = 8f
        const val FLUTTER_MS = 420L

        /** 터덜이: 화면 끝에서 끝까지 걷는 시간과, 걸으며 기우는 각도·주기 */
        const val WALK_CROSS_MS = 7_000L
        const val WALK_DEGREES = 3f
        const val WALK_BOB_MS = 900L
        const val NS_PER_MS = 1_000_000L

        /** 프레임이 멈췄다 돌아왔을 때 한 번에 건너뛰지 않게 한 프레임의 시간을 자른다 */
        const val MAX_FRAME_MS = 100L
    }
}

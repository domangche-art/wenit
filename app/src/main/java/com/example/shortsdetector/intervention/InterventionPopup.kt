package com.example.shortsdetector.intervention

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import com.example.shortsdetector.R
import com.example.shortsdetector.ShortsDetectionService
import com.example.shortsdetector.data.usage.CharacterDismissal
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionUserReason
import com.example.shortsdetector.intervention.character.Box
import com.example.shortsdetector.intervention.character.CharacterArt
import com.example.shortsdetector.intervention.character.CharacterLineRules
import com.example.shortsdetector.intervention.character.CharacterAnchor
import com.example.shortsdetector.intervention.character.CharacterOverlay
import com.example.shortsdetector.intervention.character.CharacterSpec
import com.example.shortsdetector.intervention.character.Characters
import com.example.shortsdetector.intervention.character.Lv4CharacterRules
import com.example.shortsdetector.intervention.character.Lv4Idle
import com.example.shortsdetector.ui.WenitActivity

/**
 * 단계별 개입 팝업. 접근성 서비스의 TYPE_ACCESSIBILITY_OVERLAY 창으로 띄운다
 * (SYSTEM_ALERT_WINDOW 권한 불필요. 이 창 타입은 접근성 서비스 Context 의 WindowManager 로만 추가할 수 있다).
 *
 * 단계별 레이아웃·창 모양은 [InterventionLevels] 가 정하고, 여기에는 종류별 화면 동작([Screen])만 있다.
 *
 *  - 가벼운 알림 / 성찰 질문: 화면 중앙 카드. 바깥 터치는 아래 앱으로 전달한다.
 *  - 숨 고르기: 화면 전체를 덮는다. 시간 흐름은 [BreathingTimeline] 이 정한다. 카운트다운 동안은 버튼이 없고
 *    터치를 막으며, 끝나면 버튼을 보여 주고, 답이 없으면 스스로 닫는다.
 *    홈·뒤로 같은 시스템 탐색은 막지 않으며, 이탈 신호가 잡히면 서비스가 바로 닫는다. 사용자가 끌 수 없는 상태는 없다.
 *  - 포커스를 가져가지 않는다(FLAG_NOT_FOCUSABLE). 키보드·뒤로가기 등은 아래 앱이 계속 받는다.
 *  - 멈춤을 고르면 영상을 멈춘 뒤 앱 화면으로 이동한다(사용자 탭이 있을 때만 Activity 를 연다).
 *    재생 중에 앱을 벗어나면 YouTube 가 작은 창(PiP)으로 넘어간다.
 *
 * 메인 스레드에서만 호출한다. 예외는 여기서 격리하고 실패하면 false 를 돌려준다(호출 측이 알림으로 대체).
 *  - [character] 가 있으면 개입과 함께 캐릭터를 띄운다(카드 위 / 숨 고르기 문구를 피한 곳). 캐릭터는 별도 창이고
 *    개입이 닫히면 같이 사라진다. 캐릭터가 꺼져 있거나 실패해도 개입 동작은 같다.
 * TODO: VERIFY ON DEVICE — 제조사 ROM 에서 전체 화면 오버레이 표시와 시스템 탐색 제스처.
 */
class InterventionPopup(
    private val service: AccessibilityService,
    private val character: CharacterOverlay? = null,
    /** Lv.4 에 띄울 캐릭터(사용자가 고른 캐릭터, 15단계). 띄울 때마다 읽는다. */
    private val characterSpec: () -> CharacterSpec = { Characters.DEFAULT },
    /**
     * 고정 대사에 더할 대사(16단계). 나중에 AI 가 캐릭터 말투로 만든 대사를 넣는 자리다. 지금은 늘 비어 있다.
     * [CharacterLineRules.isAllowed] 를 통과한 것만 쓴다.
     */
    private val extraLines: (CharacterSpec) -> List<String> = { emptyList() }
) {

    interface Listener {
        fun onReasonChosen(reason: InterventionUserReason)

        /**
         * 사용자가 버튼을 눌렀을 때(또는 Lv.4 캐릭터를 치웠을 때) 한 번 호출한다.
         * @param dismissal Lv.4 캐릭터를 치워서 계속 볼 때 치운 방법. 그 밖에는 null
         */
        fun onResponse(outcome: InterventionOutcome, dismissal: CharacterDismissal?)

        /** Lv.3 카운트다운 시작. 이 시간은 시청시간에서 뺀다. */
        fun onBreathingStarted()

        /** Lv.3 카운트다운이 끝나 버튼이 보일 때 */
        fun onBreathingFinished()

        /** Lv.3 이 답 없이 스스로 닫혔을 때. */
        fun onTimedOut()

        /** 앱으로 이동하기 직전. 준비가 끝나면 [proceed] 를 부른다. */
        fun beforeLeaving(proceed: () -> Unit)
    }

    /**
     * 종류별 화면 동작. 새 [InterventionKind] 는 여기에 구현을 더한다.
     * 이미지·애니메이션은 레이아웃과 [attached] 에서 시작하고, 탭·끌기로 치우는 화면은 [Host] 를 쓴다.
     */
    private interface Screen {
        /** 창을 붙이기 전. 버튼을 연결한다. */
        fun bind(v: View, host: Host) = Unit

        /** 창을 붙인 뒤 */
        fun attached(v: View, host: Host) = Unit

        /** 개입 옆에 캐릭터(11단계)를 붙인다. 캐릭터가 화면의 주인공인 Lv.4 는 false */
        val sideCharacter: Boolean get() = true
    }

    /** 화면이 쓰는 창 조작 */
    private interface Host {
        val listener: Listener

        /** 사용자의 반응으로 개입을 끝낸다. 버튼이 없는 화면도 이것으로 끝낸다. */
        fun answer(outcome: InterventionOutcome, dismissal: CharacterDismissal? = null)

        /** [OverlayWindow.FLOATING] 창을 옮긴다. 끌기에 쓴다. */
        fun moveWindowBy(dx: Int, dy: Int)
    }

    private val noticeScreen = object : Screen {}

    private val questionScreen = object : Screen {
        override fun bind(v: View, host: Host) = bindReasons(v, host.listener)
    }

    private val breathingScreen = object : Screen {
        override fun attached(v: View, host: Host) = startBreathing(v, host.listener)
    }

    private val characterBlockScreen = object : Screen {
        override val sideCharacter = false
        override fun bind(v: View, host: Host) = bindCharacterBlock(v)
        override fun attached(v: View, host: Host) {
            // 막의 배치가 끝나야 캐릭터 자리의 화면 좌표를 안다
            v.post { if (view === v) startCharacterBlock(v, host) }
        }
    }

    private fun screenFor(kind: InterventionKind): Screen = when (kind) {
        InterventionKind.NOTICE -> noticeScreen
        InterventionKind.QUESTION -> questionScreen
        InterventionKind.BREATHING -> breathingScreen
        InterventionKind.CHARACTER_BLOCK -> characterBlockScreen
    }

    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var timeline: BreathingTimeline? = null
    private var kind: InterventionKind? = null

    /** 이번 Lv.4 에 띄운 캐릭터 */
    private var blockSpec: CharacterSpec? = null

    /** 이번 Lv.4 에만 쓸 캐릭터(개발자 미리보기). null 이면 고른 캐릭터 */
    private var specOverride: CharacterSpec? = null

    /** 이번 Lv.4 말풍선: 대사 목록, 지금까지 보여 준 순서, 자리의 처음 크기, 개입 시점 시청시간 */
    private var blockLines: List<String> = emptyList()
    private var lineIndex = 0
    private var slotBase: Pair<Int, Int>? = null
    private var blockWatchMs = 0L

    /** 걷는 캐릭터의 지금 가운데 x(화면 좌표). 말풍선이 따라간다. null 이면 말풍선은 제자리(가운데) */
    private var bubbleFollowX: Int? = null

    /** 이번 개입에서 허용 시간 버튼을 보여 줄지(남은 시간). 0 이면 숨긴다. */
    private var allowanceLeftMs = 0L

    val isShowing: Boolean get() = view != null

    /** Lv.4 처럼 화면 전체의 터치를 막고 시간 제한 없이 떠 있는 화면이다. 대상 앱을 떠나면 바로 닫아야 한다. */
    val isBlockingCharacter: Boolean get() = view != null && kind == InterventionKind.CHARACTER_BLOCK

    /**
     * @param watchMs 이 개입 시점의 시청시간. 멈춘 뒤 홈 화면에 보여 준다.
     * @param allowanceLeftMs 오늘 남은 허용 시간(14단계). 0 보다 크면 "허용 시간으로 보기 (남은 N분)" 버튼을 기존 버튼 아래에 둔다.
     * @return 실제로 창을 붙였으면 true
     */
    fun show(
        level: Int, copy: InterventionCopy, watchMs: Long, listener: Listener, allowanceLeftMs: Long = 0L,
        /** Lv.4 에 고른 캐릭터 대신 띄울 캐릭터(개발자 미리보기, 16단계) */
        characterOverride: CharacterSpec? = null
    ): Boolean {
        dismiss()
        this.allowanceLeftMs = allowanceLeftMs
        specOverride = characterOverride
        blockWatchMs = watchMs
        val wm = service.getSystemService(WindowManager::class.java) ?: return false
        return try {
            val spec = InterventionLevels.of(level)
            val screen = screenFor(spec.kind)
            val v = LayoutInflater.from(service).inflate(spec.layout, null)
            v.findViewById<TextView>(R.id.popupTitle).text = copy.title
            v.findViewById<TextView?>(R.id.popupBody)?.apply {
                text = copy.body.orEmpty()
                visibility = if (copy.body.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            val params = paramsFor(spec.window)
            val host = hostFor(v, params, watchMs, listener)
            kind = spec.kind
            bindButtons(v, host)
            screen.bind(v, host)
            wm.addView(v, params)
            view = v
            screen.attached(v, host)
            if (screen.sideCharacter) attachCharacter(v, spec.window)
            OverlayFrameWatch.watch(v, "개입 팝업")
            true
        } catch (e: RuntimeException) {
            // BadTokenException, InflateException 등. 원문 없이 예외 종류만 남긴다.
            Log.w(ShortsDetectionService.TAG, "개입 팝업 표시 실패: ${e.javaClass.simpleName}")
            dismiss()
            false
        }
    }

    private fun hostFor(v: View, params: WindowManager.LayoutParams, watchMs: Long, listener: Listener): Host {
        val owner = listener
        return object : Host {
            private var answered = false
            override val listener get() = owner

            override fun answer(outcome: InterventionOutcome, dismissal: CharacterDismissal?) {
                if (answered) return
                answered = true
                try {
                    owner.onResponse(outcome, dismissal)
                } catch (e: RuntimeException) {
                    Log.w(ShortsDetectionService.TAG, "개입 반응 기록 실패: ${e.javaClass.simpleName}")
                }
                if (outcome == InterventionOutcome.STOPPED) owner.beforeLeaving { openApp(watchMs) } else dismiss()
            }

            override fun moveWindowBy(dx: Int, dy: Int) {
                params.x += dx
                params.y += dy
                try {
                    service.getSystemService(WindowManager::class.java)?.updateViewLayout(v, params)
                } catch (e: RuntimeException) {
                    Log.w(ShortsDetectionService.TAG, "개입 창 이동 실패: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** 버튼이 없는 화면도 있으므로 있을 때만 연결한다. */
    private fun bindButtons(v: View, host: Host) {
        v.findViewById<View?>(R.id.popupContinue)?.setOnClickListener { host.answer(InterventionOutcome.CONTINUED) }
        v.findViewById<View?>(R.id.popupStop)?.setOnClickListener { host.answer(InterventionOutcome.STOPPED) }
        v.findViewById<TextView?>(R.id.popupAllowance)?.let { b ->
            b.text = allowanceText(allowanceLeftMs)
            b.setOnClickListener { host.answer(InterventionOutcome.ALLOWANCE) }
            // 숨 고르기는 카운트다운이 끝나 버튼이 나올 때 함께 보인다
            val immediate = v.findViewById<View?>(R.id.popupButtons) == null
            b.visibility = if (allowanceLeftMs > 0L && immediate) View.VISIBLE else View.GONE
        }
    }

    private fun allowanceText(leftMs: Long): String {
        val left = com.example.shortsdetector.intervention.allowance.AllowanceFormat.left(leftMs)
        return service.getString(if (left.minutes) R.string.popup_allowance_minutes else R.string.popup_allowance_seconds, left.value)
    }

    /** 이유를 하나 고르면 답 버튼이 켜진다. 팝업 바깥은 막지 않으므로 고르지 않아도 영상을 볼 수 있다. */
    private fun bindReasons(v: View, listener: Listener) {
        val options = listOf(
            R.id.reasonFun to InterventionUserReason.FUN,
            R.id.reasonBored to InterventionUserReason.BORED,
            R.id.reasonTired to InterventionUserReason.TIRED,
            R.id.reasonProcrastinating to InterventionUserReason.PROCRASTINATING
        ).map { (id, reason) -> v.findViewById<View>(id) to reason }
        // 허용 시간 버튼도 다른 답처럼 이유를 고른 뒤에 켠다
        val buttons = listOfNotNull(
            v.findViewById<View>(R.id.popupContinue), v.findViewById<View>(R.id.popupStop), v.findViewById<View?>(R.id.popupAllowance)
        )
        buttons.forEach { it.isEnabled = false; it.alpha = DISABLED_ALPHA }
        for ((option, reason) in options) {
            option.setOnClickListener {
                options.forEach { (o, _) -> o.isSelected = o === option }
                buttons.forEach { it.isEnabled = true; it.alpha = 1f }
                listener.onReasonChosen(reason)
            }
        }
    }

    private fun startBreathing(v: View, listener: Listener) {
        val count = v.findViewById<TextView>(R.id.breathingCount)
        val buttons = v.findViewById<View>(R.id.popupButtons)
        timeline = BreathingTimeline(
            post = { delayMs, action -> handler.postDelayed(action, delayMs) },
            onCount = { left -> count.text = service.getString(R.string.popup_lv3_countdown, left) },
            onButtons = {
                count.visibility = View.GONE
                buttons.visibility = View.VISIBLE
                if (allowanceLeftMs > 0L) v.findViewById<View?>(R.id.popupAllowance)?.visibility = View.VISIBLE
                listener.onBreathingFinished()
            },
            onTimedOut = listener::onTimedOut
        ).also {
            listener.onBreathingStarted()
            it.start()
        }
    }

    // --- Lv.4 캐릭터 방해(15단계) ---

    /**
     * 창을 붙이기 전: 캐릭터 자리 크기(화면 너비의 절반, 그림 비율), 남은 탭 수, 첫 말풍선.
     * 막 창도 강제 다크를 끈다(말풍선·글자가 밝은 고정색이고, 서비스 Context 의 테마는 앱 테마가 아니다. 11단계 참고).
     */
    private fun bindCharacterBlock(v: View) {
        val spec = specOverride ?: try {
            characterSpec()
        } catch (e: RuntimeException) {
            Characters.DEFAULT
        }
        blockSpec = spec
        blockLines = try {
            CharacterLineRules.merged(spec, extraLines(spec))
        } catch (e: RuntimeException) {
            spec.lines
        }
        lineIndex = 0
        bubbleFollowX = null
        // 대사가 바뀌어 말풍선 폭이 달라지면 걷는 캐릭터 위치에 다시 맞춘다
        v.findViewById<View>(R.id.lv4Bubble).addOnLayoutChangeListener { _, l, _, r, _, oldL, _, oldR, _ ->
            if (view === v && (r - l) != (oldR - oldL)) followBubble(v)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) v.isForceDarkAllowed = false
        val metrics = service.resources.displayMetrics
        val art = service.getDrawable(CharacterArt.lv4(service, spec))
        val size = art?.let { Lv4CharacterRules.size(metrics.widthPixels, metrics.heightPixels, it.intrinsicWidth, it.intrinsicHeight) }
        val slot = v.findViewById<View>(R.id.lv4CharacterSlot)
        slotBase = size
        if (size != null) {
            slot.layoutParams = slot.layoutParams.apply {
                width = size.first
                height = size.second
            }
        }
        v.findViewById<TextView>(R.id.lv4TapsLeft).text =
            service.getString(R.string.popup_lv4_taps_left, Lv4CharacterRules.tapsNeeded(spec.lv4))
        showLine(v)
    }

    /** 막의 배치가 끝난 뒤 캐릭터 자리에 Lv.4 캐릭터를 띄운다. 띄우지 못하면 캐릭터 없이 끝낼 수 있게 "계속 보기" 버튼을 보인다. */
    private fun startCharacterBlock(v: View, host: Host) {
        val spec = blockSpec ?: Characters.DEFAULT
        val slot = boxOnScreen(v.findViewById(R.id.lv4CharacterSlot))
        val c = character
        val shown = c != null && slot.width > 1 && slot.height > 1 && c.showBlocking(slot, spec, object : CharacterOverlay.BlockingListener {
            override fun onTap(taps: Int, tapsLeft: Int) {
                if (view !== v) return
                if (tapsLeft > 0) {
                    v.findViewById<TextView>(R.id.lv4TapsLeft).text = service.getString(R.string.popup_lv4_taps_left, tapsLeft)
                    growSlot(v, spec, taps)
                }
                nextLine(v)
            }

            override fun onCleared(how: CharacterDismissal) {
                if (view === v) host.answer(InterventionOutcome.CONTINUED, how)
            }

            override fun onMoved(centerX: Int) {
                if (view !== v || spec.lv4.idle != Lv4Idle.WALK) return
                bubbleFollowX = centerX
                followBubble(v)
            }
        })
        if (shown) scheduleLines(v, spec)
        if (!shown) {
            Log.w(ShortsDetectionService.TAG, "Lv.4 캐릭터 없이 막만 표시: 계속 보기 버튼으로 대신함")
            v.findViewById<View>(R.id.lv4TapsLeft).visibility = View.GONE
            v.findViewById<View>(R.id.lv4Bubble).visibility = View.GONE
            v.findViewById<View>(R.id.popupContinue).visibility = View.VISIBLE
        }
    }

    /** 말풍선: 대사가 있으면 지금 순서의 대사(돌려 씀, `{분}` 은 시청 시간)를 보이고, 없으면 숨긴다. */
    private fun showLine(v: View) {
        val bubble = v.findViewById<TextView>(R.id.lv4Bubble)
        val line = Lv4CharacterRules.lineAt(blockLines, lineIndex)?.let { CharacterLineRules.fill(it, blockWatchMs) }
        bubble.text = line.orEmpty()
        bubble.visibility = if (line == null) View.GONE else View.VISIBLE
    }

    /**
     * 걷는 캐릭터(터덜이)의 말풍선을 캐릭터 가운데로 옮긴다(가로만, 레이아웃 없이 translationX).
     * 막의 안쪽 여백 안에서 좌우 끝에 닿으면 멈춘다([Lv4CharacterRules.bubbleLeft]). 대사가 바뀌어 폭이 달라지면 다시 맞춘다.
     */
    private fun followBubble(v: View) {
        val cx = bubbleFollowX ?: return
        val bubble = v.findViewById<View>(R.id.lv4Bubble)
        if (bubble.width <= 0 || v.width <= 0) return
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        val minLeft = at[0] + v.paddingLeft
        val maxRight = at[0] + v.width - v.paddingRight
        bubble.getLocationOnScreen(at)
        val naturalLeft = at[0] - bubble.translationX.toInt()
        val left = Lv4CharacterRules.bubbleLeft(cx, bubble.width, minLeft, maxRight)
        bubble.translationX = (left - naturalLeft).toFloat()
    }

    private fun nextLine(v: View) {
        lineIndex++
        showLine(v)
    }

    /** 탭하지 않아도 말풍선이 바뀌는 캐릭터(수다쟁이): [Lv4Behavior.lineIntervalMs] 마다 다음 대사. 막이 닫히면 멈춘다. */
    private fun scheduleLines(v: View, spec: CharacterSpec) {
        val every = spec.lv4.lineIntervalMs ?: return
        if (every <= 0L || blockLines.size < 2) return
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (view !== v) return
                nextLine(v)
                handler.postDelayed(this, every)
            }
        }, every)
    }

    /**
     * 탭할수록 커지는 캐릭터(표독이): 막 안의 캐릭터 자리를 키우고, 배치가 끝나면 캐릭터 창을 새 자리에 맞춘다.
     * 자리를 키우므로 문구·버튼이 캐릭터에 가려지지 않고 밀려난다.
     */
    private fun growSlot(v: View, spec: CharacterSpec, taps: Int) {
        val base = slotBase ?: return
        val scale = Lv4CharacterRules.scaleAfter(spec.lv4, taps)
        val slot = v.findViewById<View>(R.id.lv4CharacterSlot)
        val w = (base.first * scale).toInt()
        val h = (base.second * scale).toInt()
        if (slot.layoutParams.width == w && slot.layoutParams.height == h) return
        slot.layoutParams = slot.layoutParams.apply {
            width = w
            height = h
        }
        v.post { if (view === v) character?.resizeBlocking(boxOnScreen(slot)) }
    }

    /** 개입 창의 배치가 끝난 뒤 캐릭터를 띄우고, 배치가 바뀌면(Lv.3 버튼 등장) 자리를 옮긴다. */
    private fun attachCharacter(v: View, window: OverlayWindow) {
        val c = character ?: return
        val widthPx = service.resources.getDimensionPixelSize(
            if (window == OverlayWindow.FULL_SCREEN) R.dimen.character_overlay_width_full else R.dimen.character_overlay_width_card
        )
        v.post { if (view === v) c.show(anchorFor(v, window), widthPx) }
        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (view === v && c.isShowing) c.moveTo(anchorFor(v, window))
        }
    }

    /** 카드형은 카드 바로 위, 화면 전체형은 내용(캐릭터 자리·문구·버튼)을 피한 곳 */
    private fun anchorFor(v: View, window: OverlayWindow): CharacterAnchor {
        val res = service.resources
        return when (window) {
            OverlayWindow.FULL_SCREEN -> CharacterAnchor.ClearOf(
                contentBox(v), res.getDimensionPixelSize(R.dimen.character_overlay_margin)
            )
            OverlayWindow.CARD, OverlayWindow.FLOATING -> CharacterAnchor.AboveCard(
                boxOnScreen(v), res.getDimensionPixelSize(R.dimen.character_overlay_card_overlap)
            )
        }
    }

    private fun boxOnScreen(v: View): Box {
        val at = IntArray(2)
        v.getLocationOnScreen(at)
        return Box(at[0], at[1], at[0] + v.width, at[1] + v.height)
    }

    /** 보이는 자식들을 합친 영역. 자식이 없으면 창 전체. */
    private fun contentBox(v: View): Box {
        val group = v as? ViewGroup ?: return boxOnScreen(v)
        val boxes = (0 until group.childCount).map(group::getChildAt)
            .filter { it.visibility == View.VISIBLE && it.height > 0 }
            .map(::boxOnScreen)
        if (boxes.isEmpty()) return boxOnScreen(v)
        return Box(boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }

    private fun paramsFor(window: OverlayWindow) = when (window) {
        OverlayWindow.CARD -> cardParams()
        OverlayWindow.FULL_SCREEN -> fullScreenParams()
        OverlayWindow.FLOATING -> floatingParams()
    }

    private fun cardParams(): WindowManager.LayoutParams {
        val metrics = service.resources.displayMetrics
        val maxWidth = service.resources.getDimensionPixelSize(R.dimen.popup_max_width)
        val width = minOf((metrics.widthPixels * POPUP_WIDTH_RATIO).toInt(), maxWidth)
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            windowAnimations = android.R.style.Animation_Dialog
        }
    }

    private fun fullScreenParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply { windowAnimations = android.R.style.Animation_Dialog }

    private fun floatingParams() = OverlayWindows.floatingParams()

    fun dismiss() {
        character?.dismiss()
        timeline?.cancel()
        timeline = null
        handler.removeCallbacksAndMessages(null)
        kind = null
        blockSpec = null
        specOverride = null
        blockLines = emptyList()
        slotBase = null
        val v = view ?: return
        view = null
        try {
            service.getSystemService(WindowManager::class.java)?.removeViewImmediate(v)
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "개입 팝업 제거 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun openApp(watchMs: Long) {
        dismiss()
        try {
            // 접근성 서비스가 바인딩된 앱은 백그라운드 Activity 시작 제한의 예외다. 그래도 사용자 탭일 때만 연다.
            service.startActivity(
                Intent(service, WenitActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(WenitActivity.EXTRA_FROM_INTERVENTION, true)
                    .putExtra(WenitActivity.EXTRA_STOPPED_WATCH_MS, watchMs)
            )
        } catch (e: RuntimeException) {
            Log.w(ShortsDetectionService.TAG, "앱 열기 실패: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val POPUP_WIDTH_RATIO = 0.86f
        const val DISABLED_ALPHA = 0.4f
    }
}

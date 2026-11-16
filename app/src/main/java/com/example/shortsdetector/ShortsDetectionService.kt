package com.example.shortsdetector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.SharedPreferences
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.shortsdetector.data.DetectionPreferences
import com.example.shortsdetector.data.InterventionPolicyPreferences
import com.example.shortsdetector.data.usage.BackgroundUsageStore
import com.example.shortsdetector.data.usage.CharacterDismissal
import com.example.shortsdetector.data.goal.Goal
import com.example.shortsdetector.data.goal.GoalPreferences
import com.example.shortsdetector.data.usage.DailyUsageCalculator
import com.example.shortsdetector.data.usage.InterventionMethod
import com.example.shortsdetector.data.usage.InterventionOutcome
import com.example.shortsdetector.data.usage.InterventionUserReason
import com.example.shortsdetector.data.usage.ReturnTracker
import com.example.shortsdetector.data.usage.ShortsReturnConfirmer
import com.example.shortsdetector.data.usage.SessionRecorder
import com.example.shortsdetector.data.usage.SystemUsageClock
import com.example.shortsdetector.data.usage.UsageDatabase
import com.example.shortsdetector.detection.DetectionEngine
import com.example.shortsdetector.detection.EngineOutput
import com.example.shortsdetector.detection.adapters.instagram.InstagramReelsAdapter
import com.example.shortsdetector.detection.adapters.youtube.YouTubeShortsAdapter
import com.example.shortsdetector.detection.adapters.youtube.YouTubeViewIds
import com.example.shortsdetector.detection.features.AccessibilitySnapshotter
import com.example.shortsdetector.detection.state.DetectionTuning
import com.example.shortsdetector.detection.state.UserPauseDetector
import com.example.shortsdetector.ai.agent.AgentPreferences
import com.example.shortsdetector.intervention.AgentCopyProvider
import com.example.shortsdetector.intervention.AppHold
import com.example.shortsdetector.intervention.InterventionLevels
import com.example.shortsdetector.intervention.AudioPlaybackControls
import com.example.shortsdetector.intervention.GoalRemaining
import com.example.shortsdetector.intervention.InterventionCoordinator
import com.example.shortsdetector.intervention.InterventionCopyInput
import com.example.shortsdetector.intervention.InterventionPopup
import com.example.shortsdetector.intervention.character.CharacterOverlay
import com.example.shortsdetector.intervention.InterventionResult
import com.example.shortsdetector.intervention.BlockingPlaybackGuard
import com.example.shortsdetector.intervention.LeaveGuard
import com.example.shortsdetector.intervention.MaxLevelRepeatRule
import com.example.shortsdetector.intervention.LiveRecordFilter
import com.example.shortsdetector.intervention.NotificationTaps
import com.example.shortsdetector.intervention.PlaybackPauser
import com.example.shortsdetector.intervention.PreferencesPolicyStore
import com.example.shortsdetector.intervention.ResourceInterventionCopyProvider
import com.example.shortsdetector.data.rewards.Rewards
import com.example.shortsdetector.data.AllowancePreferences
import com.example.shortsdetector.data.usage.AllowanceUseRecord
import com.example.shortsdetector.intervention.allowance.AllowanceNotifier
import com.example.shortsdetector.intervention.allowance.AllowanceRun
import com.example.shortsdetector.intervention.allowance.ResumeHintOverlay
import com.example.shortsdetector.intervention.allowance.ResumeHintRule
import com.example.shortsdetector.platform.AppForeground
import com.example.shortsdetector.platform.DetectionStatus
import com.example.shortsdetector.platform.ScrollEventMapper
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 숏폼(YouTube Shorts, 실험적으로 Instagram Reels) 재생 화면을 감지해서 로컬 알림을 띄우는 접근성 서비스.
 *
 * 이 클래스는 플랫폼 신호를 모으고 결과를 실행하는 역할만 한다. 판정은 [DetectionEngine]:
 *  1) 패키지  : packageNames 로 OS 레벨에서 1차 차단 (설정에서 켠 앱만)
 *  2) 스냅샷  : 활성 창 트리를 구조 값만 담은 스냅샷으로 복사 (화면 원문은 복사하지 않음)
 *  3) 어댑터  : 앱별 규칙 → SHORTFORM / PARTIAL / NOT_SHORTFORM / UNKNOWN
 *  4) 상태    : 공통 상태 머신 (연속 확인, 일시 가림, 이탈 대기)
 *  5) 개입    : 세션 안 확정 시청시간이 정책의 기준 시간에 닿으면 개입하고, 계속 보면 쿨다운마다 반복한다.
 *              현재 단계의 팝업(TYPE_ACCESSIBILITY_OVERLAY)을 띄우고, 팝업을 끄거나 실패하면 알림으로 대신한다.
 *              세션이 끝나면 팝업·알림을 지운다. 반응은 [InterventionCoordinator] 가 기록하고 단계에 반영한다.
 *
 * 감시 타이머: 확인 중이거나 세션이 열린 동안에는 이벤트를 기다리지 않고
 * [DetectionConfig.WATCHDOG_INTERVAL_MS] 마다 직접 화면을 확인한다.
 * 대상 앱을 벗어나면 packageNames 필터 때문에 이벤트가 오지 않고, 숏폼을 가만히 볼 때도
 * 이벤트가 끊기기 때문에, 이벤트만으로는 이탈/확정을 제때 알 수 없다.
 *
 * 컴포넌트 이름(패키지/클래스)은 바꾸지 않는다. 바꾸면 사용자가 켜 둔 접근성 설정이 풀린다.
 */
class ShortsDetectionService : AccessibilityService() {

    /** 마지막으로 트리를 실제 순회한 시각 (앱 레벨 스로틀용) */
    private var lastAnalysisAt = 0L

    private var screenWidth = 0
    private var screenHeight = 0

    private val tuning = DetectionTuning()
    private val engine = DetectionEngine(
        tuning,
        listOf(YouTubeShortsAdapter(tuning), InstagramReelsAdapter(tuning)),
        DetectionConfig.MIN_RENOTIFY_INTERVAL_MS
    )
    private val snapshotter = AccessibilitySnapshotter(DetectionConfig.MAX_NODES_VISITED)
    private val popup = InterventionPopup(
        this,
        CharacterOverlay(this, { DetectionPreferences.isCharacterEnabled(this) }, { DetectionPreferences.selectedCharacter(this) }),
        { DetectionPreferences.selectedCharacter(this) }
    )

    /** 팝업이 닫힌 뒤 재생 안내(14단계) */
    private val resumeHint by lazy { ResumeHintOverlay(this) }

    /** 하루 허용 시간을 쓰는 중이면 그 실행. 메인 스레드에서만 읽고 쓴다. */
    private var allowanceRun: AllowanceRun? = null
    private var allowanceLastWatchMs = 0L
    private var allowanceNoticeText: String? = null
    private val playbackControls by lazy { AudioPlaybackControls(this) }
    private val pauser by lazy {
        PlaybackPauser(playbackControls) { delayMs, action -> pauseHandler.postDelayed(action, delayMs) }
    }

    /** 사용자가 직접 멈춘 영상 판정. 앱이 멈춘 경우([PlaybackPauser.pausedByApp])와 따로 둔다. */
    private val userPause = UserPauseDetector()

    /** 앱이 개입하면서 영상을 멈추게 했거나 화면을 덮은 상태(Lv.1·2·4 팝업, Lv.3 카운트다운) */
    private val appHold = AppHold()

    /** 쇼츠 복귀를 시청이 확정된 경우만 기록한다. */
    private val returnConfirmer = ShortsReturnConfirmer()

    /** 사용 기록(세션·개입). onServiceConnected 에서 만든다. 저장 실패는 감지에 영향을 주지 않는다. */
    private var usageStore: BackgroundUsageStore? = null
    private var recorder: SessionRecorder? = null
    private var coordinator: InterventionCoordinator? = null
    private val copyProvider by lazy {
        AgentCopyProvider(ResourceInterventionCopyProvider(this)) { AgentPreferences.activeMessages(this) }
    }

    /** 팝업 문구용 캐시. 메인 스레드에서만 읽고 쓴다. */
    private var goals: List<Goal> = emptyList()
    private var todayWatchBeforeSessionMs = 0L

    private val handler = Handler(Looper.getMainLooper())

    /** 팝업이 닫혀도 일시정지 절차가 끊기지 않게 따로 둔다. */
    private val pauseHandler = Handler(Looper.getMainLooper())
    private var watchdogScheduled = false

    private val watchdog = Runnable {
        watchdogScheduled = false
        lastAnalysisAt = SystemClock.elapsedRealtime()
        analyzeCurrentScreen(SOURCE_TIMER)
        scheduleWatchdogIfNeeded()
    }

    private var leaveCheckScheduled = false
    private val blockingPlayback = BlockingPlaybackGuard()

    /**
     * Lv.4 가 떠 있는 동안만 도는 빠른 확인. 막이 닫히면 스스로 멈춘다. [LeaveGuard.FAST_CHECK_MS]
     * 대상 앱을 떠났으면 막을 닫고, 막 뒤에서 재생이 시작됐으면 다시 멈춘다([BlockingPlaybackGuard]).
     */
    private val leaveCheck = Runnable {
        leaveCheckScheduled = false
        if (!popup.isBlockingCharacter) return@Runnable
        try {
            val pkg = activeWindowPackage()
            if (LeaveGuard.closeOnQuickCheck(true, pkg, engine.isTarget(pkg))) {
                Log.i(TAG, "[빠른 확인] 대상 앱이 아닌 창: Lv.4 막 닫음")
                interruptIntervention()
                return@Runnable
            }
            if (blockingPlayback.shouldRepause(true, playbackControls.isPlaying(), pauser.isPausing)) {
                Log.i(TAG, "[빠른 확인] Lv.4 막 뒤에서 재생이 시작됨: 다시 멈춤")
                pausePlayback()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "빠른 확인 실패: ${e.javaClass.simpleName}")
        }
        scheduleLeaveCheck()
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            DetectionPreferences.KEY_POPUP_ENABLED ->
                if (!DetectionPreferences.isPopupEnabled(this)) closeIntervention()
            else -> applyEnabledPackages()
        }
    }

    /** 정책·시연 모드·목표가 바뀌면 다음 판정부터 반영한다. */
    private val policyListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyPolicy() }

    override fun onServiceConnected() {
        super.onServiceConnected()

        ShortsNotifier.ensureChannel(this)
        startUsageRecording()
        cacheScreenSize()
        DetectionPreferences.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        InterventionPolicyPreferences.prefs(this).registerOnSharedPreferenceChangeListener(policyListener)
        GoalPreferences.prefs(this).registerOnSharedPreferenceChangeListener(policyListener)
        applyEnabledPackages()
        applyPolicy()
        DetectionStatus.onConnected()
        NotificationTaps.listener = ::onNotificationTapped
        BadgeNotifier.ensureChannel(this)
        AllowanceNotifier.ensureChannel(this)
        updateRewards("연결")
        scheduleMidnightRewards()

        Log.i(
            TAG,
            "서비스 연결됨 / targets=${engine.enabledPackages} " +
                "screen=${screenWidth}x${screenHeight} " +
                "기준(yt=${YouTubeShortsAdapter.RULE_VERSION}, ig=${InstagramReelsAdapter.RULE_VERSION}, " +
                "areaRatio>=${tuning.minPlayerAreaRatio}, confirm=${tuning.confirmationCount}, " +
                "exitGrace=${tuning.exitGraceMs}ms, 개입임계=${engine.watchThresholdMs}ms)"
        )
    }

    private fun startUsageRecording() {
        if (recorder != null) return
        try {
            val store = BackgroundUsageStore(UsageDatabase.get(this))
            val r = SessionRecorder(
                store, SystemUsageClock,
                isDemoMode = { InterventionPolicyPreferences.isDemoMode(this) }
            )
            r.onServiceStarted(TimeUnit.DAYS.toMillis(DetectionConfig.USAGE_RETENTION_DAYS))
            usageStore = store
            recorder = r
            coordinator = InterventionCoordinator(
                store, PreferencesPolicyStore(InterventionPolicyPreferences.prefs(this)), copyProvider, SystemUsageClock
            )
            store.read { s -> ReturnTracker(s).sweep(SystemUsageClock.epochNowMs()) }
        } catch (e: RuntimeException) {
            Log.w(TAG, "기록 저장소 시작 실패: ${e.javaClass.simpleName}")
        }
    }

    /** 세션이 시작될 때 오늘 앞선 세션의 시청시간을 읽어 둔다("목표까지 M분" 표시용 근사값). */
    private fun refreshTodayWatch(startedSession: Int) {
        val (from, to) = todayRange()
        usageStore?.read { s ->
            val zone = ZoneId.systemDefault()
            val today = DailyUsageCalculator.dateOf(from, zone)
            val earlier = LiveRecordFilter.finishedRealSessions(s.sessionsOverlapping(from, to))
            val watch = DailyUsageCalculator.aggregate(earlier, zone, today, today).single().estimatedWatchMs
            handler.post { if (engine.sessionProgress()?.sessionNumber == startedSession) todayWatchBeforeSessionMs = watch }
        }
    }

    private fun todayRange(): Pair<Long, Long> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return today.atStartOfDay(zone).toInstant().toEpochMilli() to
            today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun stopUsageRecording() {
        recorder = null
        coordinator = null
        usageStore?.shutdown()
        usageStore = null
    }

    /** 기록 실패가 감지·개입을 막지 않게 격리한다. */
    private inline fun record(block: (SessionRecorder) -> Unit) {
        val r = recorder ?: return
        try {
            block(r)
        } catch (e: RuntimeException) {
            Log.w(TAG, "기록 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun applyPolicy() {
        goals = GoalPreferences.load(this)
        val r = InterventionPolicyPreferences.resolve(this)
        engine.applyPolicy(r.thresholdMs, r.policy.cooldownMs)
        Log.i(
            TAG,
            "개입 정책: 기준=${r.thresholdMs}ms 단계=${r.policy.level} 쿨다운=${r.policy.cooldownMs}ms " +
                "시연=${r.demoMode}"
        )
    }

    /**
     * 설정에서 켠 앱만 이벤트를 받도록 serviceInfo 를 갱신한다.
     *
     * 런타임에 대상 앱을 바꿀 수 있어서 XML 에 더해 코드에서도 세팅한다.
     */
    private fun applyEnabledPackages() {
        val now = SystemClock.elapsedRealtime()
        val enabled = DetectionPreferences.enabledPackages(this)
        engine.setEnabledPackages(enabled, now)?.let { handleOutput(it, "설정") }
        scheduleWatchdogIfNeeded()

        // packageNames 가 비면 "모든 앱"이 되므로 절대 비우지 않는다.
        // 전부 꺼진 경우 YouTube 이벤트만 받되 엔진이 대상 아님으로 무시한다.
        val filter = enabled.ifEmpty { setOf(YouTubeViewIds.PACKAGE) }
        try {
            serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
                eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                packageNames = filter.toTypedArray()
                notificationTimeout = NOTIFICATION_TIMEOUT_MS
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "serviceInfo 갱신 실패: ${e.javaClass.simpleName}")
        }
        Log.i(TAG, "감지 대상 앱: ${engine.enabledPackages.ifEmpty { setOf("(없음)") }}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            handleEvent(event)
        } catch (e: RuntimeException) {
            // 어떤 경우에도 서비스 전체가 죽지 않게 한다. 원문 없이 예외 종류만 남긴다.
            Log.w(TAG, "이벤트 처리 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun handleEvent(event: AccessibilityEvent) {
        // packageNames 로 이미 걸러지지만, 방어적으로 한 번 더 확인한다.
        val pkg = event.packageName?.toString()
        if (!engine.isTarget(pkg)) return

        val now = SystemClock.elapsedRealtime()

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            ScrollEventMapper.toSignal(event, now, screenHeight)?.let { s ->
                val flip = engine.onScroll(s)
                // 다른 영상으로 넘어갔을 수 있다. 소리 없는 영상을 멈춘 영상으로 오해하지 않게 근거를 버린다.
                if (flip != null) userPause.onContentChanged()
                // 페이지 넘김 가설(TODO: VERIFY ON DEVICE) 확인용. 구조 값만 남긴다.
                Log.d(
                    TAG,
                    "스크롤 cls=${s.className?.substringAfterLast('.')} from=${s.fromIndex} to=${s.toIndex} " +
                        "n=${s.itemCount} dx=${s.deltaX} dy=${s.deltaY} flip=$flip"
                )
            }
        }

        // notificationTimeout(OS) 에 더한 앱 레벨 스로틀. 잦은 트리 순회를 막는다.
        if (now - lastAnalysisAt < DetectionConfig.MIN_ANALYSIS_INTERVAL_MS) return
        lastAnalysisAt = now

        analyzeCurrentScreen(SOURCE_EVENT)
        scheduleWatchdogIfNeeded()
    }

    override fun onInterrupt() {
        endSession("onInterrupt")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        endSession("onUnbind")
        finishAllowance(allowanceRun?.let { allowanceLastWatchMs - it.startWatchMs } ?: 0L, exhausted = false)
        resumeHint.dismiss()
        stopWatchdog()
        stopLeaveCheck()
        closeIntervention()
        releasePlaybackControl()
        unregisterPreferenceListeners()
        DetectionStatus.onDisconnected()
        stopUsageRecording()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(midnightRewards)
        finishAllowance(allowanceRun?.let { allowanceLastWatchMs - it.startWatchMs } ?: 0L, exhausted = false)
        resumeHint.dismiss()
        stopWatchdog()
        stopLeaveCheck()
        closeIntervention()
        releasePlaybackControl()
        stopUsageRecording()
        unregisterPreferenceListeners()
        DetectionStatus.onDisconnected()
        super.onDestroy()
    }

    private fun releasePlaybackControl() {
        NotificationTaps.listener = null
        pauseHandler.removeCallbacksAndMessages(null)
        pauser.release()
    }

    private fun unregisterPreferenceListeners() {
        DetectionPreferences.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        InterventionPolicyPreferences.prefs(this).unregisterOnSharedPreferenceChangeListener(policyListener)
        GoalPreferences.prefs(this).unregisterOnSharedPreferenceChangeListener(policyListener)
    }

    /** 팝업을 닫는다. 답을 받지 못한 개입은 IGNORED 로 끝낸다. */
    private fun closeIntervention() {
        popup.dismiss()
        appHold.closed()
        syncWatchHold()
        coordinator?.let { c -> guarded { onFinished(c.abandon()) } }
    }

    /** 이탈 신호로 오버레이를 닫는다. 사용자의 반응이 아니므로 INTERRUPTED 로 남기고, 세션이 이어지면 다시 개입한다. */
    private fun interruptIntervention() {
        popup.dismiss()
        appHold.closed()
        syncWatchHold()
        coordinator?.let { c ->
            guarded { c.interrupt()?.let { engine.retryIntervention(it.sessionNumber) } }
        }
    }

    /** 알림을 눌러 앱을 열었다. 멈춘 것으로 끝내고, PiP 로 이어지는 소리도 멈춘다. */
    private fun onNotificationTapped(interventionId: String): Boolean {
        val c = coordinator ?: return false
        var handled = false
        guarded {
            val result = c.respondTo(interventionId, InterventionOutcome.STOPPED) ?: return@guarded
            handled = true
            onFinished(result)
            Log.i(TAG, "개입 반응(알림 탭): Lv.${result.level} ${result.outcome} → Lv.${result.nextLevel}")
            pausePlayback()
        }
        return handled
    }

    /** 영상을 멈춘다. 멈춘 동안의 시간은 시청시간에 넣지 않는다. */
    private fun pausePlayback(done: () -> Unit = {}) {
        pauser.pause { result ->
            Log.i(TAG, "재생 일시정지: $result")
            syncWatchHold()
            done()
        }
    }

    /** 앱이 멈춘 영상을 사용자가 다시 재생하면 시청시간을 다시 센다. */
    private fun syncPausedPlayback() {
        if (pauser.checkResumed()) {
            Log.i(TAG, "재생 다시 시작됨(앱이 멈춘 영상)")
            resumeHint.dismiss()
        }
    }

    /**
     * 사용자가 직접 멈춘 영상인지 오디오 상태로 갱신한다. 열린 세션이 있을 때만 판단한다.
     * 앱이 멈췄거나 숨 고르기 중이면 사용자 멈춤으로 세지 않는다(두 경우를 섞지 않는다).
     */
    private fun updateUserPause(now: Long) {
        if (engine.sessionPackage == null) {
            userPause.onContentChanged()
            return
        }
        val before = userPause.paused
        val audio = try {
            playbackControls.isPlaying()
        } catch (e: RuntimeException) {
            null
        }
        val paused = userPause.update(now, audio, appPaused = appHold.appPaused(pauser.pausedByApp))
        if (paused != before) {
            Log.i(TAG, if (paused) "사용자 멈춤으로 봄: 시청시간·개입 멈춤" else "사용자 멈춤 풀림: 시청시간 이어서 셈")
        }
    }

    /** 시청 보류 = 앱 때문(팝업·카운트다운·앱이 멈춘 영상) || 사용자가 멈춘 영상. 보류 중에는 시청시간에 넣지 않고 개입하지 않는다. */
    private fun syncWatchHold() {
        engine.setWatchHold(appHold.watchHeld(pauser.pausedByApp, userPause.paused))
    }

    /** 개입 하나가 끝났다. 최대 단계 아래로 내려가면 최대 단계 반복 간격을 푼다. */
    private fun onFinished(result: InterventionResult?) {
        if (result == null) return
        if (!MaxLevelRepeatRule.keepsGap(result.nextLevel, InterventionPolicyPreferences.resolve(this))) {
            engine.setRepeatFloor(0L)
        }
        updateRewards("개입")
    }

    // --- 연속 달성·배지(13단계) ---

    private val midnightRewards = Runnable {
        updateRewards("자정")
        scheduleMidnightRewards()
    }

    /** 다음 자정 1분 뒤에 끝난 날을 판정한다. 서비스가 꺼져 있던 날은 다음 판정 때 몰아서 채운다. */
    private fun scheduleMidnightRewards() {
        handler.removeCallbacks(midnightRewards)
        val zone = ZoneId.systemDefault()
        val next = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).plusMinutes(1).toInstant().toEpochMilli()
        handler.postDelayed(midnightRewards, (next - System.currentTimeMillis()).coerceAtLeast(MIN_REWARD_DELAY_MS))
    }

    /**
     * 끝난 날 판정과 배지 판정을 저장 스레드에서 돌린다(앞선 기록 쓰기가 끝난 뒤).
     * 새 배지는 앱 화면이 보이면 화면의 축하에 맡기고, 쇼츠를 보는 중이면 알림을 미룬다(세션이 끝날 때 다시 시도).
     */
    private fun updateRewards(reason: String) {
        usageStore?.read { s ->
            try {
                val update = Rewards.update(this)
                val waiting = (s as? UsageDatabase)?.badges()
                    ?.filter { !it.isSeed && !it.celebrated && !it.notified }.orEmpty()
                if (update.closedDays.isNotEmpty() || update.newBadges.isNotEmpty()) {
                    Log.i(TAG, "보상 판정($reason): 끝난 날 ${update.closedDays.size}일, 새 배지 ${update.newBadges.map { "${it.id}#${it.tier}" }}")
                }
                handler.postDelayed({ notifyBadges(waiting, s as? UsageDatabase) }, BADGE_NOTIFY_DELAY_MS)
            } catch (e: RuntimeException) {
                Log.w(TAG, "보상 판정 실패($reason): ${e.javaClass.simpleName}")
            }
        }
    }

    private fun notifyBadges(waiting: List<com.example.shortsdetector.data.rewards.EarnedBadge>, db: UsageDatabase?) {
        if (waiting.isEmpty() || db == null) return
        if (AppForeground.visible) return
        if (engine.sessionPackage != null) {
            Log.i(TAG, "새 배지 알림 미룸: 쇼츠 보는 중")
            return
        }
        if (!BadgeNotifier.notify(this, waiting)) return
        usageStore?.read { waiting.forEach { b -> db.markBadgeNotified(b.id, b.tier) } }
    }

    /** 기록 실패가 감지·개입을 막지 않게 격리한다. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.w(TAG, "개입 기록 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun endSession(reason: String) {
        handleOutput(engine.forceEnd(SystemClock.elapsedRealtime()), reason)
        scheduleWatchdogIfNeeded()
    }

    /** 확인 중이거나 세션이 열려 있을 때만 타이머를 돌린다. 평소(숏폼 아님)에는 이벤트만으로 충분하다. */
    private fun scheduleWatchdogIfNeeded() {
        val needed = engine.state.needsWatchdog
        if (needed && !watchdogScheduled) {
            handler.postDelayed(watchdog, DetectionConfig.WATCHDOG_INTERVAL_MS)
            watchdogScheduled = true
        } else if (!needed && watchdogScheduled) {
            stopWatchdog()
        }
    }

    private fun scheduleLeaveCheck() {
        if (popup.isBlockingCharacter && !leaveCheckScheduled) {
            handler.postDelayed(leaveCheck, LeaveGuard.FAST_CHECK_MS)
            leaveCheckScheduled = true
        }
    }

    private fun stopLeaveCheck() {
        handler.removeCallbacks(leaveCheck)
        leaveCheckScheduled = false
    }

    /** 활성 창의 패키지 이름만 읽는다. 창을 못 얻으면 null. */
    private fun activeWindowPackage(): String? {
        val root = getRootInActiveWindow() ?: return null
        try {
            return root.packageName?.toString()
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }
    }

    private fun stopWatchdog() {
        handler.removeCallbacks(watchdog)
        watchdogScheduled = false
    }

    // ---------------------------------------------------------------------

    /** @param source 판정을 실행한 쪽. 로그에만 쓴다(타이머가 제 역할을 하는지 확인용). */
    private fun analyzeCurrentScreen(source: String) {
        if (screenWidth <= 0 || screenHeight <= 0) cacheScreenSize()
        syncPausedPlayback()
        val now = SystemClock.elapsedRealtime()
        updateUserPause(now)
        syncWatchHold()
        engine.setInterventionOpen(popup.isShowing, now)

        val output = try {
            observe(now)
        } catch (e: RuntimeException) {
            // 노드가 사라지는 등 예상 못 한 예외: 세션을 바로 끊지 않고 "모름"으로 넘긴다.
            Log.w(TAG, "[$source] 관찰 실패: ${e.javaClass.simpleName}")
            engine.onObservationError(now, engine.sessionPackage)
        }
        handleOutput(output, source)
    }

    private fun observe(now: Long): EngineOutput {
        // 루트를 못 얻는 경우(전환 중, 홈 등)는 원인을 구분할 수 없다. 이탈 대기로 넘긴다.
        val root: AccessibilityNodeInfo = getRootInActiveWindow() ?: return engine.onRootUnavailable(now)

        try {
            val pkg = root.packageName?.toString()
            // 활성 창이 대상 앱이 아니면(시스템 다이얼로그 등) 판정하지 않는다.
            val adapter = engine.adapterFor(pkg) ?: return engine.onForeignWindow(now, pkg, isUserPresent())

            val started = SystemClock.elapsedRealtime()
            val snapshot = snapshotter.capture(
                root, adapter.packageName, screenWidth, screenHeight,
                adapter.lookupViewIds, adapter.needsFullTree
            )
            val output = engine.onSnapshot(now, snapshot)
            val elapsed = SystemClock.elapsedRealtime() - started
            val rootRatio = snapshot.root?.let(snapshot::areaRatio) ?: 0f
            Log.d(
                TAG,
                "측정 nodes=${snapshot.nodes.size}${if (snapshot.truncated) "(잘림)" else ""} / ${elapsed}ms " +
                    String.format(Locale.US, "창면적비=%.3f", rootRatio)
            )
            return output
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }
    }

    /** 화면이 켜져 있고 잠금이 풀렸는지. 확인할 수 없으면 false 로 두어 이탈 판정을 그대로 탄다. */
    private fun isUserPresent(): Boolean {
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive == true
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false
        return interactive && !locked
    }

    private fun handleOutput(output: EngineOutput, source: String) {
        val step = output.step
        val verdict = output.observation.verdict
        DetectionStatus.update(step.current, output.observation.packageName, verdict?.reason)
        record { it.onOutput(output, engine.sessionProgress()) }
        recordReturn(output)

        // 판정 로그: 원문 없이 상태 / 근거 코드 / 신호 플래그 / 면적비만 남긴다.
        if (!step.ignored) {
            val v = verdict?.let {
                String.format(
                    Locale.US, "%s/%s/%s 면적비=%.3f %s",
                    it.kind, it.confidence, it.reason, it.playerAreaRatio, it.signals
                )
            } ?: output.observation.window.name
            val hits = if (step.candidateHits > 0) " hits=${step.candidateHits}/${tuning.confirmationCount}" else ""
            Log.d(TAG, "[$source] ${step.previous}→${step.current}$hits $v")
        }

        output.endedSession?.let { s ->
            Log.i(
                TAG,
                "세션 #${s.sessionNumber} 종료: ${s.endReason}${s.exitCause?.let { "($it)" } ?: ""} " +
                    "pkg=${s.packageName} 추정시청=${s.estimatedWatchMs}ms(댓글창 ${s.partialWatchMs}ms 포함) 가림=${s.obscuredMs}ms " +
                    "불확실=${s.uncertainMs}ms 최대신뢰도=${s.maxConfidence} rule=${s.ruleVersion}"
            )
            updateRewards("세션 종료")
        }
        if (output.exitSignalStarted || output.cancelNotification) resumeHint.dismiss()
        // 종료 판정(grace)을 기다리지 않고 오버레이만 먼저 닫는다. 알림은 세션이 끝날 때 지운다.
        if (output.exitSignalStarted && popup.isShowing) {
            Log.i(TAG, "[$source] 이탈 신호: 개입 오버레이 닫음")
            interruptIntervention()
        } else if (LeaveGuard.closeBlocking(popup.isBlockingCharacter, output.observation.window)) {
            // Lv.4 는 시간 제한 없이 터치를 막는다. 이탈 신호(상태 전환)를 놓쳐도 활성 창이 대상 앱이 아니면 바로 닫는다.
            Log.i(TAG, "[$source] 대상 앱이 아닌 창(${output.observation.window}): Lv.4 막 닫음")
            interruptIntervention()
        }
        if (output.cancelNotification) {
            ShortsNotifier.cancel(this)
            closeIntervention()
        }

        step.startedSession?.let { n ->
            todayWatchBeforeSessionMs = 0L
            refreshTodayWatch(n)
            InterventionPolicyPreferences.startDemoSession(this)
            userPause.onContentChanged()
            syncWatchHold()
            Log.i(
                TAG,
                "[$source] ★ 숏폼 감지 #$n pkg=${output.observation.packageName} 신뢰도=${step.startedConfidence} " +
                    "(개입은 ${engine.watchThresholdMs}ms 시청 후)"
            )
        }
        step.resumedSession?.let { n ->
            userPause.onContentChanged()
            syncWatchHold()
            Log.i(TAG, "[$source] ★ 세션 #$n 이어짐 pkg=${output.observation.packageName} (누적 시청시간 유지)")
        }
        if (output.suppressedByCooldown) {
            Log.i(TAG, "[$source] 개입 임계 도달 (쿨다운 — 생략)")
            val suppressed = InterventionPolicyPreferences.resolve(this)
            record {
                it.onIntervention(
                    engine.sessionProgress()?.sessionNumber, InterventionMethod.SUPPRESSED_BY_COOLDOWN,
                    engine.sessionProgress()?.estimatedWatchMs ?: 0L, engine.watchThresholdMs,
                    level = suppressed.policy.level, isDemo = suppressed.demoMode
                )
            }
        }
        output.notifySession?.let { n ->
            intervene(n, output.observation.packageName, output.notifyWatchMs ?: 0L, output.observation.atMs, source)
        }
        tickAllowance(output.observation.atMs)
    }

    // --- 하루 허용 시간(14단계) ---

    /** 팝업에서 허용 시간을 골랐다. 지금 세션의 시청시간부터 센다. 그동안 엔진은 개입을 판정하지 않는다. */
    private fun startAllowance(level: Int) {
        val p = engine.sessionProgress() ?: return
        val demo = InterventionPolicyPreferences.isDemoMode(this)
        val today = LocalDate.now(ZoneId.systemDefault())
        val left = AllowancePreferences.remainingMs(this, demo, today)
        if (left <= 0L) return
        allowanceRun = AllowanceRun(p.sessionNumber, level, p.estimatedWatchMs, left, SystemUsageClock.epochNowMs(), today, demo)
        allowanceLastWatchMs = p.estimatedWatchMs
        allowanceNoticeText = null
        engine.setAllowanceActive(true)
        showAllowanceNotice(left)
        Log.i(TAG, "허용 시간 시작: 세션 #${p.sessionNumber} 남은 ${left}ms Lv.$level 시연=$demo")
    }

    /** 시청시간이 늘어난 만큼 쓴다. 다 쓰면 시작 때 단계로 바로 개입하고, 쇼츠를 떠나면 멈춘다. */
    private fun tickAllowance(atMs: Long) {
        val run = allowanceRun ?: return
        val p = engine.sessionProgress()
        when (val t = run.tick(p?.sessionNumber, p?.estimatedWatchMs ?: allowanceLastWatchMs, allowanceLastWatchMs)) {
            is AllowanceRun.Tick.Using -> {
                allowanceLastWatchMs = p?.estimatedWatchMs ?: allowanceLastWatchMs
                showAllowanceNotice(t.leftMs)
            }
            is AllowanceRun.Tick.Exhausted -> {
                finishAllowance(t.usedMs, exhausted = true)
                Log.i(TAG, "허용 시간 다 씀: 바로 Lv.${run.level} 개입")
                intervene(run.sessionNumber, engine.sessionPackage, p?.estimatedWatchMs ?: 0L, atMs, "허용 시간", run.level, allowanceUsedUp = true)
            }
            is AllowanceRun.Tick.Left -> {
                finishAllowance(t.usedMs, exhausted = false)
                Log.i(TAG, "허용 시간 멈춤: 쇼츠를 떠남(쓴 ${t.usedMs}ms, 남은 시간은 그대로)")
            }
        }
    }

    private fun showAllowanceNotice(leftMs: Long) {
        val text = AllowanceNotifier.text(this, leftMs)
        if (text == allowanceNoticeText) return
        allowanceNoticeText = text
        AllowanceNotifier.show(this, leftMs)
    }

    /** 사용을 끝내고 쓴 양을 그날 몫에 더한 뒤 기록한다. 쓰는 중이 아니면 아무 일도 없다. */
    private fun finishAllowance(usedMs: Long, exhausted: Boolean) {
        val run = allowanceRun ?: return
        allowanceRun = null
        allowanceNoticeText = null
        engine.setAllowanceActive(false)
        AllowanceNotifier.cancel(this)
        val used = usedMs.coerceIn(0L, run.budgetLeftMs)
        try {
            AllowancePreferences.charge(this, run.demo, run.day, used)
        } catch (e: RuntimeException) {
            Log.w(TAG, "허용 시간 저장 실패: ${e.javaClass.simpleName}")
        }
        usageStore?.insertAllowanceUse(
            AllowanceUseRecord(
                id = java.util.UUID.randomUUID().toString(), startedAtMs = run.startedAtMs, endedAtMs = SystemUsageClock.epochNowMs(),
                usedMs = used, budgetMs = run.budgetLeftMs, exhausted = exhausted, level = run.level, isDemo = run.demo
            )
        )
    }

    /** 팝업이 닫힌 뒤, 앱이 멈춘 영상이 그대로 멈춰 있으면 재생 안내를 띄운다. 일시정지가 끝나기를 잠깐 기다린다. */
    private fun scheduleResumeHint(outcome: InterventionOutcome) {
        handler.postDelayed({
            val show = ResumeHintRule.shouldShow(
                outcome, viaPopup = true, pausedByApp = pauser.pausedByApp, sessionOpen = engine.sessionPackage != null,
                playing = try { playbackControls.isPlaying() } catch (e: RuntimeException) { false }
            )
            if (show && !popup.isShowing) resumeHint.show()
        }, RESUME_HINT_DELAY_MS)
    }

    /**
     * 멈춘 뒤 다시 돌아왔는지 남긴다. 앞선 쓰기가 끝난 뒤 저장 스레드에서 처리한다.
     * 쇼츠 복귀는 시청이 확정됐을 때만([ShortsReturnConfirmer]), 걸린 시간은 도착 시각으로 남긴다.
     */
    private fun recordReturn(output: EngineOutput) {
        val now = SystemUsageClock.epochNowMs()
        val step = output.step
        (step.startedSession ?: step.resumedSession)?.let { n ->
            returnConfirmer.onEntered(n, engine.sessionProgress()?.estimatedWatchMs ?: 0L, now)
        }
        val progress = engine.sessionProgress()
        val shortsAt = returnConfirmer.onProgress(progress?.sessionNumber, progress?.estimatedWatchMs ?: 0L)
        val passedAt = output.endedSession?.let { returnConfirmer.onEnded(it.sessionNumber) }
        val appAt = passedAt ?: now.takeIf { output.openedTargetApp }
        if (shortsAt == null && appAt == null) return
        usageStore?.read { s ->
            val tracker = ReturnTracker(s)
            appAt?.let(tracker::onTargetAppOpened)
            shortsAt?.let(tracker::onShortsReturned)
        }
    }

    /**
     * 현재 단계의 팝업을 우선 띄우고, 꺼져 있거나 실패하면 알림으로 대신한다. 둘 다 실패하면 쿨다운을 걸지 않는다.
     * @param levelOverride 허용 시간을 다 썼을 때: 허용 시간을 고를 때의 단계
     * @param allowanceUsedUp 허용 시간을 다 써서 띄운다. 문구 맨 앞에 "허용 시간을 다 썼어요"를 붙인다
     */
    private fun intervene(
        session: Int,
        pkg: String?,
        watchMs: Long,
        atMs: Long,
        source: String,
        levelOverride: Int? = null,
        allowanceUsedUp: Boolean = false
    ) {
        resumeHint.dismiss()
        if (refreshUnansweredNotification(session, pkg, watchMs, atMs, source)) return
        // 알림으로 개입 중이면 답 없는 앞선 개입이 남아 있다. 먼저 끝내야 단계가 그 결과를 반영한다.
        coordinator?.let { c -> guarded { onFinished(c.abandon()) } }
        val interventionId = recorder?.newInterventionId()
        val resolved = InterventionPolicyPreferences.resolve(this)
        val level = levelOverride ?: resolved.policy.level
        val base = copyProvider.copyFor(
            InterventionCopyInput(level, watchMs, GoalRemaining.minutes(goals, todayWatchBeforeSessionMs, watchMs))
        )
        val copy = if (allowanceUsedUp) base.copy(title = getString(R.string.allowance_used_up_prefix, base.title)) else base
        val allowanceLeft = if (allowanceUsedUp) 0L else AllowancePreferences.remainingMs(this, resolved.demoMode, LocalDate.now(ZoneId.systemDefault()))
        val popupShown = DetectionPreferences.isPopupEnabled(this) && popup.show(level, copy, watchMs, popupListener, allowanceLeft)
        if (popupShown && popup.isBlockingCharacter) {
            blockingPlayback.onShown()
            scheduleLeaveCheck()
        }
        val notified = !popupShown &&
            ShortsNotifier.notifyWatchThreshold(this, session, pkg, watchMs, interventionId)
        val shown = popupShown || notified
        Log.i(
            TAG,
            "[$source] ★ 개입 #$session Lv.$level 시청=${watchMs}ms 방식=${if (popupShown) "팝업" else if (shown) "알림" else "실패"}"
        )
        if (interventionId != null) record {
            val method = when {
                popupShown -> InterventionMethod.POPUP
                notified -> InterventionMethod.NOTIFICATION
                else -> InterventionMethod.FAILED
            }
            it.onIntervention(
                session, method, watchMs, engine.watchThresholdMs,
                level = level, isDemo = resolved.demoMode, id = interventionId
            )
            if (shown) coordinator?.begin(interventionId, session, level, viaNotification = notified)
        }
        if (shown) {
            engine.onNotificationPosted(atMs)
            // 최대 단계를 띄웠으면 다음(같은 최대 단계) 개입까지 최소 간격을 둔다. 시연 모드는 0.
            engine.setRepeatFloor(MaxLevelRepeatRule.gapAfterShowing(level, resolved))
        }
        engine.setInterventionOpen(popupShown, atMs)
        // Lv.1·2·4 팝업은 띄우는 순간 영상을 멈춘다(12·15단계). 알림 방식은 멈추지 않는다. 다시 재생은 사용자가 한다.
        if (popupShown && InterventionLevels.of(level).pausesOnShow) {
            appHold.cardOpen = true
            syncWatchHold()
            pausePlayback()
        }
    }

    /**
     * 답하지 않은 알림이 떠 있으면 새 개입을 만들지 않고 그 알림의 내용만 조용히 고친다. 앞선 개입은 끝내지 않으므로
     * 단계 규칙에는 그 개입이 끝날 때 한 번만 반영된다. @return 고쳤으면 true
     */
    private fun refreshUnansweredNotification(session: Int, pkg: String?, watchMs: Long, atMs: Long, source: String): Boolean {
        val c = coordinator ?: return false
        val id = c.notificationToRefresh(
            session, DetectionPreferences.isPopupEnabled(this), ShortsNotifier.isShowing(this)
        ) ?: return false
        if (!ShortsNotifier.notifyWatchThreshold(this, session, pkg, watchMs, id, refresh = true)) return false
        Log.i(TAG, "[$source] 답 없는 알림 갱신 #$session 시청=${watchMs}ms (새 개입 아님)")
        engine.onNotificationPosted(atMs)
        engine.setInterventionOpen(false, atMs)
        return true
    }

    private val popupListener = object : InterventionPopup.Listener {
        override fun onReasonChosen(reason: InterventionUserReason) {
            coordinator?.let { c -> guarded { c.chooseReason(reason) } }
        }

        override fun onResponse(outcome: InterventionOutcome, dismissal: CharacterDismissal?) {
            val c = coordinator ?: return
            guarded {
                appHold.cardOpen = false
                syncWatchHold()
                val result = c.respond(outcome, dismissal) ?: return@guarded
                onFinished(result)
                Log.i(TAG, "개입 반응: Lv.${result.level} $outcome${dismissal?.let { "($it)" }.orEmpty()} → Lv.${result.nextLevel}")
                if (outcome == InterventionOutcome.ALLOWANCE) startAllowance(result.level)
                if (outcome != InterventionOutcome.STOPPED) scheduleResumeHint(outcome)
                val again = result.reinterveneAfterWatchMs ?: return@guarded
                engine.sessionProgress()?.takeIf { it.sessionNumber == result.sessionNumber }?.let {
                    engine.delayIntervention(it.sessionNumber, it.estimatedWatchMs + again)
                }
            }
        }

        override fun onBreathingStarted() {
            appHold.breathing = true
            syncWatchHold()
            pausePlayback()
        }

        override fun onBreathingFinished() {
            appHold.breathing = false
            syncWatchHold()
        }

        override fun beforeLeaving(proceed: () -> Unit) = pausePlayback(proceed)

        override fun onTimedOut() {
            closeIntervention()
            scheduleResumeHint(InterventionOutcome.IGNORED)
        }
    }

    @Suppress("DEPRECATION")
    private fun cacheScreenSize() {
        val wm = getSystemService(WindowManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
        } else {
            val size = Point()
            wm.defaultDisplay.getRealSize(size)
            screenWidth = size.x
            screenHeight = size.y
        }
    }

    companion object {
        const val TAG = "ShortsDetector"

        private const val MIN_REWARD_DELAY_MS = 60_000L

        /** 팝업이 닫힌 뒤 일시정지 확인을 기다리는 시간(재생 안내) */
        private const val RESUME_HINT_DELAY_MS = 300L

        /** "그만 볼게"로 앱이 열리는 중일 수 있어, 화면이 보이는지 조금 기다린 뒤 알림 여부를 정한다. */
        private const val BADGE_NOTIFY_DELAY_MS = 1_500L

        /** OS 레벨 디바운스. accessibility_service_config.xml 의 값과 맞춘다. */
        private const val NOTIFICATION_TIMEOUT_MS = 300L

        private const val SOURCE_EVENT = "이벤트"
        private const val SOURCE_TIMER = "타이머"
    }
}

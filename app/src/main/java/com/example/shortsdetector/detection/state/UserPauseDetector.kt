package com.example.shortsdetector.detection.state

/**
 * 사용자가 직접 멈춘 영상인지 오디오 재생 상태로 추정한다. 결정론적이고 Android 의존성이 없다. 앱별 UI 특징은 쓰지 않는다.
 *
 * 멈춤으로 보는 조건(모두 충족):
 *  - 지금 영상에서 소리가 재생되는 것을 한 번 이상 봤다. 소리 없는 영상(오디오 트랙 없음 등)은 처음부터 조용하므로
 *    멈춘 것과 구분할 수 없어 시청으로 센다.
 *  - 그 뒤 소리가 [holdAfterMs] 이상 계속 멎어 있다. 버퍼링·영상 넘김 같은 짧은 끊김은 시청으로 센다.
 *  - 앱이 멈춘 상태가 아니다. 앱이 멈춘 경우는 따로 처리하므로 여기서 사용자 멈춤으로 세지 않는다.
 *
 * 화면이 바뀌면(스크롤, 세션 시작·이어짐) 소리를 봤다는 근거를 버린다. 다음 영상이 소리 없는 영상일 수 있어서다.
 * 오디오 상태를 알 수 없으면(null) 근거를 버리고 시청으로 센다. 판단이 애매하면 시청으로 세는 쪽을 택한다.
 *
 * 오디오 상태는 기기 전체 값이다. 다른 앱이 음악을 틀고 있으면 영상을 멈춰도 시청으로 센다(애매하면 시청).
 * TODO: VERIFY ON DEVICE — Instagram Reels 의 음소거, 다른 기기의 AudioManager 동작.
 */
class UserPauseDetector(private val holdAfterMs: Long = DEFAULT_HOLD_AFTER_MS) {

    private var heardAudio = false
    private var silentSinceMs: Long? = null

    /** 마지막 [update] 결과 */
    var paused = false
        private set

    /** 화면이 다른 영상으로 바뀌었을 수 있다. 소리를 봤다는 근거를 버린다. */
    fun onContentChanged() {
        heardAudio = false
        silentSinceMs = null
        paused = false
    }

    /**
     * @param audioActive 미디어 소리가 재생 중이면 true. 알 수 없으면 null
     * @param appPaused 앱이 개입하면서 영상을 멈췄거나 화면을 덮고 있다
     * @return 사용자가 멈춘 상태로 보면 true
     */
    fun update(nowMs: Long, audioActive: Boolean?, appPaused: Boolean): Boolean {
        paused = when {
            audioActive == null -> {
                onContentChanged()
                false
            }
            appPaused -> {
                silentSinceMs = null
                false
            }
            audioActive -> {
                heardAudio = true
                silentSinceMs = null
                false
            }
            !heardAudio -> false
            else -> {
                val since = silentSinceMs ?: nowMs.also { silentSinceMs = it }
                nowMs - since >= holdAfterMs
            }
        }
        return paused
    }

    companion object {
        /** 소리가 이만큼 멎어 있으면 멈춘 것으로 본다. TODO: VERIFY ON DEVICE */
        const val DEFAULT_HOLD_AFTER_MS = 5_000L
    }
}

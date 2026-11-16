package com.example.shortsdetector.intervention

/**
 * Lv.4 막 뒤에서 영상이 다시 재생되면 한 번 더 멈추는 규칙. 결정론적이고 Android 의존성이 없다. 메인 스레드에서만 쓴다.
 *
 * 15단계 실기기 검증(SM-S906N)에서 찾았다: YouTube 를 새로 열어 세션이 이어지는 순간 Lv.4 가 바로 뜨면, 멈추려는 시점에는
 * 아직 재생 전이라(NOT_PLAYING) 아무것도 하지 않고, 0.7초 뒤 YouTube 가 자동 재생을 시작했다. 막이 화면 전체의 터치를 막아
 * 사용자는 영상을 직접 멈출 수 없으므로, 막이 떠 있는 동안 시작된 재생은 사용자가 한 것이 아니다.
 *
 * 다른 앱의 소리도 같은 "재생 중" 으로 보이므로(오디오 상태만 안다) 막 하나에 [MAX_REPAUSES] 번까지만 다시 멈춘다.
 * Lv.1~3 은 대상이 아니다(Lv.1·2 는 카드 밖 영상을 사용자가 직접 다시 재생할 수 있다).
 */
class BlockingPlaybackGuard(private val maxRepauses: Int = MAX_REPAUSES) {
    private var used = 0

    /** 새 Lv.4 막을 띄웠다. 횟수를 다시 센다. */
    fun onShown() {
        used = 0
    }

    /**
     * @param pausing 멈추는 절차가 이미 진행 중
     * @return 지금 다시 멈춰야 하면 true(횟수를 하나 쓴다)
     */
    fun shouldRepause(blockingShown: Boolean, playing: Boolean, pausing: Boolean): Boolean {
        if (!blockingShown || !playing || pausing || used >= maxRepauses) return false
        used++
        return true
    }

    companion object {
        const val MAX_REPAUSES = 3
    }
}

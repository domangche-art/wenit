package com.example.shortsdetector.intervention

/**
 * 앱이 개입하면서 영상을 멈추게 하거나 화면을 덮은 상태. 결정론적이고 Android 의존성이 없다. 메인 스레드에서만 쓴다.
 *
 *  - [cardOpen]: Lv.1·Lv.2 처럼 표시할 때 영상을 멈추는 팝업이 떠 있다(12단계).
 *  - [breathing]: Lv.3 숨 고르기 카운트다운 중.
 *
 * 둘 다 앱이 한 일이므로 "사용자가 직접 멈춤"(10-2단계) 판정에 넣지 않는다. 그동안은 시청시간에도 넣지 않는다.
 */
class AppHold {
    var cardOpen = false
    var breathing = false

    /** 앱 때문에 멈췄거나 덮였다. 사용자 멈춤 판정을 하지 않는다. */
    fun appPaused(pausedByApp: Boolean): Boolean = pausedByApp || breathing || cardOpen

    /** 시청 보류 = 앱 때문 || 사용자가 직접 멈춤 */
    fun watchHeld(pausedByApp: Boolean, userPaused: Boolean): Boolean = appPaused(pausedByApp) || userPaused

    /** 개입 화면이 닫혔다. */
    fun closed() {
        cardOpen = false
        breathing = false
    }
}

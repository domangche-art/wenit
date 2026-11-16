package com.example.shortsdetector.intervention

import com.example.shortsdetector.detection.model.WindowStatus

/**
 * Lv.4 막이 다른 앱을 가리지 않게 하는 규칙(15단계). 결정론적이고 Android 의존성이 없다.
 *
 * Lv.4 는 시간 제한 없이 화면 전체의 터치를 막는다. 기존 개입은 이탈 신호(상태가 EXIT_PENDING 으로 바뀌는 순간)로 닫는데,
 * 개입이 뜰 때 이미 그 상태였다면 그 신호가 다시 오지 않는다. 그래서 Lv.4 는 관찰마다 활성 창을 보고,
 * 대상 앱 창이 아니면(홈·다른 앱·잠금 화면·알림창·빠른 설정) 바로 닫는다.
 *
 * 창을 얻지 못한 관찰(UNAVAILABLE)은 대상 앱 안에서도 잠깐 생길 수 있어 이 규칙에서는 닫지 않는다.
 * 그 경우는 기존 이탈 신호가 닫는다.
 *
 * 빠른 확인(15단계 실기기 검증 뒤 추가): 서비스는 packageNames 필터로 대상 앱 이벤트만 받아서, 홈·다른 앱으로 가도
 * 이벤트가 오지 않는다. 관찰은 1초 감시 타이머에서만 일어나 막이 홈 화면 위에 최대 2초가량 남았다(SM-S906N 실측 1.9초).
 * 그래서 Lv.4 가 떠 있는 동안만 [FAST_CHECK_MS] 마다 활성 창의 패키지 이름만 확인한다(트리 순회 없음).
 */
object LeaveGuard {
    /** Lv.4 가 떠 있는 동안 활성 창을 확인하는 간격. 패키지 이름만 읽어 비용이 작다. */
    const val FAST_CHECK_MS = 150L

    fun closeBlocking(blockingShown: Boolean, window: WindowStatus): Boolean =
        blockingShown && (window == WindowStatus.FOREIGN || window == WindowStatus.SYSTEM_PANEL)

    /**
     * 빠른 확인의 판정. 활성 창이 대상 앱이 아니면(다른 앱·홈·시스템 UI) 닫는다. [closeBlocking] 의 FOREIGN·SYSTEM_PANEL 과 같다.
     * 활성 창을 얻지 못했으면([activePackage] null) 닫지 않는다(UNAVAILABLE 과 같은 이유).
     */
    fun closeOnQuickCheck(blockingShown: Boolean, activePackage: String?, isTargetPackage: Boolean): Boolean =
        blockingShown && activePackage != null && !isTargetPackage
}

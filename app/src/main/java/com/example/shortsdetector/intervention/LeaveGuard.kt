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
 * 그 경우는 기존 이탈 신호가 닫는다. TODO: VERIFY ON DEVICE — 홈 버튼·최근 앱·알림창에서 막이 바로 사라지는지.
 */
object LeaveGuard {
    fun closeBlocking(blockingShown: Boolean, window: WindowStatus): Boolean =
        blockingShown && (window == WindowStatus.FOREIGN || window == WindowStatus.SYSTEM_PANEL)
}

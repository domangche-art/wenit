package com.example.shortsdetector.intervention

/**
 * 화면 → 서비스로 "이 캐릭터의 Lv.4 를 미리 띄워 달라" 를 넘기는 프로세스 내 통로(16단계, 개발자 화면).
 * 오버레이는 접근성 서비스만 띄울 수 있어서 서비스에 부탁한다. 저장하지 않는다. 메인 스레드에서만 호출한다.
 *
 * 미리보기는 시연·디자인 확인용이다: 기록·단계·재생·시청시간에 영향을 주지 않고, 감지 여부와 상관없이 앱 화면 위에 뜬다.
 * 앱 화면을 떠나면 실제 Lv.4 와 같은 규칙으로 바로 닫힌다.
 */
object Lv4Preview {

    /** @return 띄웠으면 true */
    var handler: ((characterId: String) -> Boolean)? = null

    /** @return 띄웠으면 true. 서비스가 연결되지 않았거나 다른 개입이 떠 있으면 false */
    fun show(characterId: String): Boolean = handler?.invoke(characterId) == true
}

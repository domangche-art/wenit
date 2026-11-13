package com.example.shortsdetector.platform

/**
 * 앱 화면(WenitActivity)이 사용자에게 보이는지. 같은 프로세스의 서비스가 배지를 알림으로 보낼지,
 * 화면의 축하 화면에 맡길지 정할 때 쓴다. Activity 의 onResume/onPause 에서만 바꾼다.
 */
object AppForeground {
    @Volatile var visible: Boolean = false
}

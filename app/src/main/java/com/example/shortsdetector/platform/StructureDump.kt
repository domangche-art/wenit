package com.example.shortsdetector.platform

import com.example.shortsdetector.detection.model.ScreenSnapshot
import java.util.Locale

/**
 * 개발용 화면 구조 덤프(17단계). 서비스가 실제로 보는 [ScreenSnapshot] 을 한 노드 한 줄로 바꾼다.
 *
 * [com.example.shortsdetector.detection.model.UiNode] 에는 text·contentDescription 이 없으므로 화면 글자(캡션, 사용자 이름,
 * 댓글)가 들어갈 수 없다. 쓰는 값은 view id, 클래스, 위치·크기, 보임, 스크롤 가능, 화면 대비 면적비뿐이다.
 * 디버그 빌드에서 adb(권한 DUMP)로만 부른다. 기기 밖으로 보내지 않는다.
 */
object StructureDump {

    const val ACTION = "com.example.shortsdetector.debug.DUMP_STRUCTURE"

    /** 켜면 "접근성상 중요하지 않은" 뷰까지 담는다(그 순간만 서비스 플래그를 바꾼다) */
    const val EXTRA_ALL = "all"

    /** 로그에 함께 남길 짧은 이름표(예: "reels_tab"). 영문·숫자·밑줄만 남긴다 */
    const val EXTRA_LABEL = "label"

    /** 실기기 시험용: WENIT 개입 화면의 버튼을 누른다(stop/continue/allowance/character/status). 다른 앱은 건드리지 않는다 */
    const val EXTRA_PRESS = "press"

    fun label(raw: String?): String = raw.orEmpty().filter { it.isLetterOrDigit() && it.code < 128 || it == '_' }.take(40)

    fun header(snapshot: ScreenSnapshot, label: String, includeAll: Boolean): String =
        "# label=$label pkg=${snapshot.packageName} screen=${snapshot.screenWidth}x${snapshot.screenHeight} " +
            "nodes=${snapshot.nodes.size} truncated=${snapshot.truncated} all=$includeAll"

    /** 열: 순번, viewId(없으면 -), 클래스(패키지 줄임), left,top,right,bottom, 보임 v/h, 스크롤 s/-, 면적비 */
    fun lines(snapshot: ScreenSnapshot): List<String> = snapshot.nodes.mapIndexed { i, n ->
        listOf(
            i.toString(),
            n.viewId ?: "-",
            n.className?.substringAfterLast('.') ?: "-",
            "${n.left},${n.top},${n.right},${n.bottom}",
            if (n.visibleToUser) "v" else "h",
            if (n.scrollable) "s" else "-",
            String.format(Locale.US, "%.3f", snapshot.areaRatio(n))
        ).joinToString("\t")
    }
}

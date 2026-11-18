package com.example.shortsdetector.detection.adapters.instagram

import com.example.shortsdetector.detection.adapters.RecentActivity
import com.example.shortsdetector.detection.adapters.ShortformAdapter
import com.example.shortsdetector.detection.model.AdapterVerdict
import com.example.shortsdetector.detection.model.Confidence
import com.example.shortsdetector.detection.model.ReasonCode
import com.example.shortsdetector.detection.model.ScreenSnapshot
import com.example.shortsdetector.detection.model.Signal
import com.example.shortsdetector.detection.model.VerdictKind
import com.example.shortsdetector.detection.state.DetectionTuning

/**
 * Instagram Reels 어댑터(17단계, 규칙 ig-1). 실기기 구조 덤프로 정했다:
 * Instagram 456.0.0.0.42, SM-S906N, Android 16, 2026-11-18. fixture: test/resources/fixtures/instagram.
 *
 * 시청으로 세는 화면은 전체화면 릴스 뷰어 하나다(릴스 탭, 탐색·피드 등에서 열어 전체화면으로 보는 릴스).
 *  - [VIEWER_PAGER] 세로 ViewPager 가 보이고 화면의 [DetectionTuning.minContainerAreaRatio] 이상(실측 0.884~0.898)
 *  - [VIDEO_CONTAINER] 영상 영역이 같은 크기로 보이면 HIGH/MEDIUM
 *  - 영상 영역이 작거나 없어도(사진형·가로 비율 릴스 0.615, 이미지 광고) [UFI] 좋아요·댓글 버튼 열이 보이면 MEDIUM
 *  - 보조: 최근 세로 페이지 넘김(다음 릴스로 넘김)
 *
 * 세지 않는 화면: 홈 피드(자동 재생 영상은 video_container, 최대 실측 0.73, 뷰어 pager 없음), 스토리(reel_viewer_*),
 * 일반 게시물, 프로필 릴스 목록(clips_grid_recyclerview), DM. 뷰어 pager 는 다른 탭에서도 트리에 남지만 숨김·크기 0 이다.
 *
 * 주의(실측):
 *  - Instagram id 의 "reel" 은 **스토리**다(reel_viewer_root, reel_empty_badge). 릴스는 "clips" 다.
 *  - "clips_" 로 시작해도 릴스 화면이 아닌 id 가 있다(clips_grid_recyclerview, clips_tab). **정확히 일치**로만 비교한다.
 *  - 홈 피드도 화면 대부분을 덮는 ViewPager·RecyclerView 가 있다. 예전 규칙(ig-0, 큰 pager + 넘김)은 피드를 릴스로 셌다.
 *  - 댓글창·공유 시트는 활성 창 root 를 덮는 [SHEET_CONTAINER] 로 뜬다. ID 검색은 시트 아래 뷰어도 찾아 준다.
 *    공유 시트 아래 뷰어는 원래 크기(0.898)로 나오므로 **시트를 먼저** 본다. 시트는 새 진입 근거가 아니고 열린 세션을
 *    이어 가는 데만 쓰인다(상태 머신: SHORTFORM_PARTIAL·OVERLAY 는 진입하지 않음).
 *      공유 시트([SHARE_RECIPIENTS] 보임): OVERLAY(잠깐 가려짐, 시청으로 세지 않음)
 *      댓글창: 영상이 시트 위쪽으로 줄어든다(실측 218,74~862,1140, 0.272). 줄어든 영상이 [DetectionTuning.minHoldAreaRatio]
 *              이상 보이면 SHORTFORM_PARTIAL(시청으로 셈, 유튜브 댓글창과 같음)
 *      그 밖(영상이 안 줄어든 시트, 영상이 아주 작음, 영상 못 찾음): OVERLAY
 *
 * 성능: 처음에는 전체 트리를 순회했는데 Instagram 트리가 커서(약 400 노드) 한 번 측정에 중앙값 304ms, 최대 2.6초가 걸렸다.
 * 판정에 쓰는 노드는 모두 리소스 ID 가 있으므로 YouTube 처럼 ID 검색만 한다([needsFullTree] = false).
 *
 * 텍스트·contentDescription 은 쓰지 않는다. TODO: VERIFY ON DEVICE — DM 으로 받은 릴스를 연 화면(이번에 재현 못 함),
 * 다른 Instagram 버전, 댓글창을 끝까지 펼친 상태.
 */
class InstagramReelsAdapter(private val tuning: DetectionTuning = DetectionTuning()) : ShortformAdapter {

    override val packageName: String = PACKAGE
    override val ruleVersion: String = RULE_VERSION

    override val lookupViewIds: Set<String> = setOf(VIEWER_PAGER, VIDEO_CONTAINER, UFI, SHEET_CONTAINER, SHARE_RECIPIENTS)
    override val needsFullTree: Boolean = false

    override fun evaluate(snapshot: ScreenSnapshot, recent: RecentActivity): AdapterVerdict {
        if (snapshot.nodes.isEmpty()) return AdapterVerdict.unknown(ReasonCode.TREE_EMPTY)

        fun visible(id: String) = snapshot.nodes.filter { it.visibleToUser && it.viewId == id && it.area > 0 }
        val videos = visible(VIDEO_CONTAINER)
        val viewer = visible(VIEWER_PAGER).any { snapshot.areaRatio(it) >= tuning.minContainerAreaRatio }
        val fullVideo = videos.filter { snapshot.areaRatio(it) >= tuning.minContainerAreaRatio }
        val ufi = visible(UFI).isNotEmpty()
        val paging = recent.pageFlips > 0
        val videoRatio = videos.maxOfOrNull(snapshot::areaRatio) ?: 0f

        val signals = buildSet {
            if (viewer) add(Signal.FULLSCREEN_PAGER)
            if (fullVideo.isNotEmpty()) add(Signal.SHORTS_PLAYER_FULLSCREEN)
            if (videos.isNotEmpty()) add(Signal.SHORTS_PLAYER_VISIBLE)
            if (ufi) add(Signal.REEL_CONTAINER_VISIBLE)
            if (paging) add(Signal.VERTICAL_PAGING)
        }

        fun verdict(kind: VerdictKind, confidence: Confidence, reason: ReasonCode) =
            AdapterVerdict(kind, confidence, reason, signals, videoRatio)

        val sheet = visible(SHEET_CONTAINER).any { snapshot.areaRatio(it) >= MIN_SHEET_WINDOW_RATIO }
        if (sheet) {
            val share = visible(SHARE_RECIPIENTS).isNotEmpty()
            val shrunk = videos.any {
                val r = snapshot.areaRatio(it)
                r >= tuning.minHoldAreaRatio && r < tuning.minContainerAreaRatio
            }
            return if (!share && shrunk) {
                verdict(VerdictKind.SHORTFORM_PARTIAL, Confidence.MEDIUM, ReasonCode.PLAYER_PARTIAL)
            } else {
                verdict(VerdictKind.OVERLAY, Confidence.LOW, ReasonCode.OVERLAY_WINDOW)
            }
        }

        return when {
            viewer && fullVideo.isNotEmpty() -> verdict(
                VerdictKind.SHORTFORM,
                if (ufi || paging) Confidence.HIGH else Confidence.MEDIUM,
                ReasonCode.PLAYER_FULLSCREEN
            )
            // 사진형·가로 비율 릴스(영상 영역 실측 0.615), 이미지 광고(영상 영역 없음)도 같은 뷰어다: 버튼 열이나 작은 영상이 보이면 진입
            viewer && (ufi || videos.any { snapshot.areaRatio(it) >= tuning.minHoldAreaRatio }) ->
                verdict(VerdictKind.SHORTFORM, Confidence.MEDIUM, ReasonCode.REEL_CONTAINERS_ONLY)
            // 다음 릴스로 넘어가는 중 등 뷰어만 보일 때: 진입하지 않고 열린 세션만 잇는다
            viewer -> verdict(VerdictKind.SHORTFORM, Confidence.LOW, ReasonCode.REEL_CONTAINERS_ONLY)
            snapshot.truncated -> verdict(VerdictKind.UNKNOWN, Confidence.LOW, ReasonCode.TREE_TRUNCATED)
            else -> verdict(VerdictKind.NOT_SHORTFORM, Confidence.LOW, ReasonCode.NO_SHORTFORM_SIGNAL)
        }
    }

    companion object {
        const val PACKAGE = "com.instagram.android"
        const val RULE_VERSION = "ig-1"

        private const val ID = "$PACKAGE:id/"

        /** 릴스 뷰어의 세로 pager(릴스 탭·다른 곳에서 연 릴스 모두) */
        const val VIEWER_PAGER = "${ID}clips_viewer_view_pager"

        /** 릴스 한 편의 영상 영역. 댓글창을 열면 위쪽으로 줄어든다 */
        const val VIDEO_CONTAINER = "${ID}clips_video_container"

        /** 오른쪽 좋아요·댓글·공유 버튼 열 */
        const val UFI = "${ID}clips_ufi_component"

        /** 댓글창·공유 시트가 뜨는 시트 컨테이너 */
        const val SHEET_CONTAINER = "${ID}bottom_sheet_container"

        /** 공유 시트의 받는 사람 목록 */
        const val SHARE_RECIPIENTS = "${ID}direct_private_share_recipients_recycler_view"

        /** 시트 컨테이너가 화면을 이만큼 덮으면 시트가 떠 있다고 본다(실측 0.952) */
        const val MIN_SHEET_WINDOW_RATIO = 0.80f
    }
}

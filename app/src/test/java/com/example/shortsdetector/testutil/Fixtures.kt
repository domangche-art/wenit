package com.example.shortsdetector.testutil

import com.example.shortsdetector.detection.adapters.instagram.InstagramReelsAdapter
import com.example.shortsdetector.detection.adapters.youtube.YouTubeViewIds
import com.example.shortsdetector.detection.model.ScreenSnapshot
import com.example.shortsdetector.detection.model.ScrollSignal
import com.example.shortsdetector.detection.model.UiNode

/**
 * 테스트 fixture.
 *
 * - YouTube: 저장소의 실제 uiautomator 덤프에서 text/content-desc 를 제거한 구조 TSV (실측)
 * - Instagram: 실기기 구조 덤프(17단계, [loadDump] 형식). 화면 글자 없음
 * - 그 밖의 YouTube 화면(댓글창, Clear Screen, 홈 등): 개인정보 없는 **가상 데이터**.
 *   실측값이 아니므로 테스트 이름/주석에 "synthetic" 으로 표시한다.
 */
object Fixtures {

    /** 덤프의 루트 bounds [0,0][1080,2424] */
    const val W = 1080
    const val H = 2424

    const val YT = YouTubeViewIds.PACKAGE
    const val IG = InstagramReelsAdapter.PACKAGE

    fun id(name: String, pkg: String = YT) = "$pkg:id/$name"

    /** 실측: 쇼츠 재생 화면 (2026-09-27) */
    fun youtubeShorts(): ScreenSnapshot = load("fixtures/youtube/shorts_player_2026-09-27.tsv", YT)

    /** 실측: 일반 시청 화면에서 프리롤 광고 재생 중 (2026-09-27) */
    fun youtubeWatchAd(): ScreenSnapshot = load("fixtures/youtube/watch_ad_preroll_2026-09-27.tsv", YT)

    /**
     * uiautomator 덤프는 화면에 보이는 노드만 담는 것으로 알려져 있어 visibleToUser=true 로 읽는다.
     * (덤프에 visible-to-user 속성이 없음 — 이 가정은 미확인)
     */
    fun load(path: String, pkg: String): ScreenSnapshot {
        val stream = Fixtures::class.java.classLoader!!.getResourceAsStream(path)
            ?: error("fixture not found: $path")
        val nodes = stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }
                .map { line ->
                    val c = line.split('\t')
                    UiNode(
                        viewId = c[1].takeIf { it != "-" },
                        className = c[2].takeIf { it != "-" },
                        left = c[3].toInt(), top = c[4].toInt(),
                        right = c[5].toInt(), bottom = c[6].toInt(),
                        visibleToUser = true,
                        scrollable = c[7] == "1"
                    )
                }.toList()
        }
        return ScreenSnapshot(pkg, W, H, nodes)
    }

    fun node(
        viewId: String?,
        className: String = "android.widget.FrameLayout",
        left: Int = 0, top: Int = 0, right: Int = W, bottom: Int = H,
        visible: Boolean = true
    ) = UiNode(viewId, className, left, top, right, bottom, visible)

    fun snapshot(pkg: String, vararg nodes: UiNode, truncated: Boolean = false) =
        ScreenSnapshot(pkg, W, H, listOf(node(null)) + nodes, truncated)

    /** 플레이어(reel_watch_player) bounds 만 바꾼 쇼츠 화면 */
    fun youtubeShortsWithPlayer(top: Int, bottom: Int): ScreenSnapshot =
        youtubeShorts().mapNodes {
            if (it.viewId == YouTubeViewIds.SHORTS_PLAYER) it.copy(top = top, bottom = bottom) else it
        }

    /** synthetic: 댓글창을 연 쇼츠. 기존 코드 주석의 실측 면적비 0.292 를 재현하도록 플레이어를 줄였다. */
    fun youtubeShortsCommentsOpen(): ScreenSnapshot {
        // 0.292 * 1080 * 2424 / 1080 ≈ 708 px 높이
        val shrink: (UiNode) -> UiNode = { n ->
            if (n.viewId == YouTubeViewIds.SHORTS_PLAYER || n.viewId in YouTubeViewIds.REEL_CONTAINERS) {
                n.copy(top = 142, bottom = 142 + 708)
            } else n
        }
        return youtubeShorts().mapNodes(shrink) +
            node(id("engagement_panel"), "android.widget.RelativeLayout", top = 850, bottom = 2361)
    }

    /**
     * 댓글창을 끝까지 펼친 쇼츠. 에뮬레이터(YouTube 21.18.164, 2026-10-31) 구조 덤프의 bounds 를 옮겼다(텍스트 없음).
     * 플레이어·overlay_root 는 [0,142][1080,175](면적비 0.014)로 줄고, reel_recycler·fragment_root·
     * page_container 는 [0,142][1080,2235](0.863) 그대로다. 사용자 테스트 로그에서는 플레이어가 0.001 까지 줄었다.
     */
    fun youtubeShortsCommentsExpanded(playerBottom: Int = 175): ScreenSnapshot {
        val shrink: (UiNode) -> UiNode = { n ->
            if (n.viewId == YouTubeViewIds.SHORTS_PLAYER || n.viewId == id("reel_player_overlay_root")) {
                n.copy(top = 142, bottom = playerBottom)
            } else n
        }
        return youtubeShorts().mapNodes(shrink) +
            node(id("app_engagement_panel"), "android.widget.RelativeLayout", top = 0, bottom = 2361)
    }

    /**
     * synthetic: Clear Screen 모드 가정. 오버레이(버튼·제목·푸터) 노드가 사라지고 플레이어만 남는다고 가정.
     * 실제 Clear Screen 트리는 미확인(TODO: VERIFY ON DEVICE).
     */
    fun youtubeShortsClearScreen(): ScreenSnapshot = youtubeShorts().copy(
        nodes = youtubeShorts().nodes.filterNot { n ->
            val v = n.viewId ?: return@filterNot false
            v.contains("reel_player_overlay") || v.contains("reel_video_interactions") ||
                v.contains("reel_player_footer") || v.contains("reel_scrim")
        }
    )

    /** synthetic: 업데이트로 플레이어 ID 만 바뀐 경우(보조 컨테이너는 유지) */
    fun youtubeShortsPlayerIdRenamed(): ScreenSnapshot = youtubeShorts().mapNodes {
        if (it.viewId == YouTubeViewIds.SHORTS_PLAYER) it.copy(viewId = id("reel_watch_player_v2")) else it
    }

    /** synthetic: YouTube 홈 피드 (pivot_bar + 결과 리스트, 쇼츠 신호 없음) */
    fun youtubeHome(): ScreenSnapshot = snapshot(
        YT,
        node(id("results"), "android.support.v7.widget.RecyclerView", top = 142, bottom = 2235),
        node(id("pivot_bar"), "android.widget.HorizontalScrollView", top = 2235, bottom = 2361),
        // 일반 영상에도 있는 ID. 부분 일치하면 안 된다.
        node(id("reel_time_bar"), "android.view.ViewGroup")
    )

    /** synthetic: 홈 피드의 쇼츠 선반 미리보기처럼 작은 플레이어가 보이는 경우 */
    fun youtubeHomeWithSmallShortsPreview(): ScreenSnapshot = youtubeHome().let {
        it.copy(nodes = it.nodes + node(YouTubeViewIds.SHORTS_PLAYER, "android.view.ViewGroup", top = 400, bottom = 1000))
    }

    /**
     * 에뮬레이터 관찰 형태(YouTube 21.18.164): 쇼츠 위에 로그인 대화상자가 별도 창으로 뜸.
     * root bounds [162,1094][918,1409] (면적비 약 0.09), 플레이어·reel 컨테이너 없음.
     */
    fun youtubeDialogWindow(): ScreenSnapshot = ScreenSnapshot(
        YT, W, H,
        listOf(
            node(null, left = 162, top = 1094, right = 918, bottom = 1409),
            node("android:id/content", left = 162, top = 1094, right = 918, bottom = 1409),
            node(id("account_list"), "android.widget.ListView", left = 162, top = 1241, right = 918, bottom = 1409)
        )
    )

    /** 에뮬레이터 관찰 형태: 홈 버튼 후 플레이어 면적비 0.077 (PiP 로 추정, 창 구조는 미확인 — synthetic) */
    fun youtubeShortsMiniature(): ScreenSnapshot = ScreenSnapshot(
        YT, W, H,
        listOf(
            node(null, left = 480, top = 1700, right = 1080, bottom = 2036),
            node(YouTubeViewIds.SHORTS_PLAYER, "android.view.ViewGroup", left = 480, top = 1700, right = 1080, bottom = 2036)
        )
    )

    // --- Instagram: 실기기 구조 덤프(17단계, Instagram 456.0.0.0.42, SM-S906N, 2026-11-18, 화면 1080x2340) ---

    /** 실측: 릴스 탭 전체화면 */
    fun instagramReels(): ScreenSnapshot = ig("reels_tab")

    /** 실측: 탐색 탭에서 연 릴스(전체화면 뷰어, 아래 댓글 입력 줄) */
    fun instagramExploreReel(): ScreenSnapshot = ig("explore_reel")

    /** 실측: 릴스에서 댓글창을 연 상태(시트가 root 를 덮고, ID 검색으로 찾은 영상은 위쪽으로 줄어 0.272) */
    fun instagramReelsComments(): ScreenSnapshot = ig("reels_comments")

    /** 실측: 같은 댓글창을 ID 검색 없이 순회만 한 것(시트 내용 윗변 y=1034, 위쪽 약 41%) */
    fun instagramReelsCommentsNoLookup(): ScreenSnapshot = ig("reels_comments_no_lookup")

    /** 실측: 릴스에서 공유 시트를 연 상태(ID 검색은 시트 아래 뷰어를 원래 크기 0.898 로 찾는다) */
    fun instagramReelsShare(): ScreenSnapshot = ig("reels_share")

    /** 실측: 릴스 탭의 사진형 릴스(영상 영역 0.615) */
    fun instagramReelsPhoto(): ScreenSnapshot = ig("reels_photo")

    /** 실측: 릴스 탭의 이미지 광고(영상 영역 없음) */
    fun instagramReelsImageAd(): ScreenSnapshot = ig("reels_image_ad")

    /** 실측: 홈 피드 맨 위(스토리 줄 + 자동 재생 영상 게시물, 화면 대부분을 덮는 ViewPager·RecyclerView 있음) */
    fun instagramFeed(): ScreenSnapshot = ig("home")

    /** 실측: 홈 피드 스크롤 중 — 영상 게시물 / 캐러셀 / 사진 게시물 */
    fun instagramFeedScrolled(): List<ScreenSnapshot> = listOf(ig("feed2"), ig("feed3"), ig("feed4"))

    /** 실측: 스토리 보기 */
    fun instagramStory(): ScreenSnapshot = ig("story")

    /** 실측: 프로필의 릴스 목록(clips_grid_recyclerview) */
    fun instagramProfileReels(): ScreenSnapshot = ig("profile_reels")

    /** 실측: 탐색 그리드(숨은 릴스 뷰어 pager 가 트리에 남아 있음) */
    fun instagramExplore(): ScreenSnapshot = ig("explore")

    /** 실측: DM 목록 */
    fun instagramDirect(): ScreenSnapshot = ig("inbox")

    /** 실측: DM 대화방(릴스 없음) */
    fun instagramDirectThread(): ScreenSnapshot = ig("dm_thread")

    private fun ig(name: String) = loadDump("fixtures/instagram/${name}_2026-11-18.tsv", IG)

    /**
     * [com.example.shortsdetector.platform.StructureDump] 형식(순번, viewId, 클래스, l,t,r,b, v/h, s/-, 면적비)을 읽는다.
     * 화면 크기는 "# label=... screen=WxH" 줄에서 읽는다. 숨은 노드(h)는 그대로 숨김이다.
     */
    fun loadDump(path: String, pkg: String): ScreenSnapshot {
        val stream = Fixtures::class.java.classLoader!!.getResourceAsStream(path) ?: error("fixture not found: $path")
        var w = W
        var h = H
        val nodes = ArrayList<UiNode>()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                if (line.startsWith("#")) {
                    Regex("""screen=(\d+)x(\d+)""").find(line)?.let { m ->
                        w = m.groupValues[1].toInt()
                        h = m.groupValues[2].toInt()
                    }
                    continue
                }
                val c = line.split('\t')
                val b = c[3].split(',').map(String::toInt)
                nodes += UiNode(
                    viewId = c[1].takeIf { it != "-" }, className = c[2].takeIf { it != "-" },
                    left = b[0], top = b[1], right = b[2], bottom = b[3],
                    visibleToUser = c[4] == "v", scrollable = c[5] == "s"
                )
            }
        }
        return ScreenSnapshot(pkg, w, h, nodes)
    }

    fun scroll(
        atMs: Long, pkg: String, from: Int, to: Int = from, dx: Int? = 0, dy: Int? = H, screenHeight: Int = 0
    ) = ScrollSignal(atMs, pkg, "androidx.recyclerview.widget.RecyclerView", from, to, 100, dx, dy, screenHeight)

    /** 에뮬레이터(YouTube 21.18.164)에서 관찰한 쇼츠 스와이프 이벤트 형태: 인덱스 없음, dy 만 있음 */
    fun youtubeShortsSwipe(atMs: Long, dy: Int = 1162) =
        ScrollSignal(atMs, YT, "android.support.v7.widget.RecyclerView", -1, -1, -1, 0, dy, H)
}

fun ScreenSnapshot.mapNodes(transform: (UiNode) -> UiNode): ScreenSnapshot = copy(nodes = nodes.map(transform))

operator fun ScreenSnapshot.plus(extra: UiNode): ScreenSnapshot = copy(nodes = nodes + extra)

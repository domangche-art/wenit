package com.example.shortsdetector.detection.adapters.instagram

import com.example.shortsdetector.detection.adapters.RecentActivity
import com.example.shortsdetector.detection.model.Confidence
import com.example.shortsdetector.detection.model.ReasonCode
import com.example.shortsdetector.detection.model.ScreenSnapshot
import com.example.shortsdetector.detection.model.VerdictKind
import com.example.shortsdetector.testutil.Fixtures
import com.example.shortsdetector.testutil.mapNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 17단계 규칙 ig-1. fixture 는 실기기 구조 덤프다(Instagram 456.0.0.0.42, SM-S906N, 2026-11-18, 화면 글자 없음).
 * DM 으로 받은 릴스를 연 화면은 재현하지 못해 fixture 가 없다(TODO: VERIFY ON DEVICE).
 */
class InstagramReelsAdapterTest {

    private val adapter = InstagramReelsAdapter()
    private fun eval(s: ScreenSnapshot, flips: Int = 0) = adapter.evaluate(s, RecentActivity(pageFlips = flips))

    // --- 시청으로 세는 화면 ---

    @Test
    fun `reels tab full screen enters with high confidence without any swipe`() {
        val v = eval(Fixtures.instagramReels())
        assertEquals(VerdictKind.SHORTFORM, v.kind)
        assertEquals(Confidence.HIGH, v.confidence)       // 뷰어 + 영상 + 버튼 열
        assertEquals(ReasonCode.PLAYER_FULLSCREEN, v.reason)
        assertTrue(v.playerAreaRatio in 0.85f..0.95f)
    }

    @Test
    fun `a reel opened from explore is the same full screen viewer`() {
        val v = eval(Fixtures.instagramExploreReel())
        assertEquals(VerdictKind.SHORTFORM, v.kind)
        assertTrue(v.confidence >= Confidence.MEDIUM)
    }

    @Test
    fun `viewer without video or buttons only holds a session`() {
        val s = Fixtures.instagramReels().mapNodes {
            if (it.viewId == InstagramReelsAdapter.VIDEO_CONTAINER || it.viewId == InstagramReelsAdapter.UFI) it.copy(visibleToUser = false) else it
        }
        val v = eval(s)
        assertEquals(VerdictKind.SHORTFORM, v.kind)
        assertEquals("새 진입 안 함(진입은 MEDIUM 이상)", Confidence.LOW, v.confidence)
    }

    @Test
    fun `photo reels with a smaller media area and image ads in the reels viewer can start a session`() {
        for (s in listOf(Fixtures.instagramReelsPhoto(), Fixtures.instagramReelsImageAd())) {
            val v = eval(s)
            assertEquals(VerdictKind.SHORTFORM, v.kind)
            assertEquals(Confidence.MEDIUM, v.confidence)
        }
        assertTrue(Fixtures.instagramReelsImageAd().nodes.none {
            it.viewId == InstagramReelsAdapter.VIDEO_CONTAINER && it.visibleToUser && it.area > 0
        })
    }

    // --- 세지 않는 화면 ---

    @Test
    fun `home feed autoplay video, scrolled feed, carousels and photos are not reels even while scrolling`() {
        for (s in listOf(Fixtures.instagramFeed()) + Fixtures.instagramFeedScrolled()) {
            val v = eval(s, flips = 5)
            assertEquals(VerdictKind.NOT_SHORTFORM, v.kind)
        }
    }

    @Test
    fun `stories, profile reels grid, explore grid and DMs are not reels`() {
        for (s in listOf(
            Fixtures.instagramStory(), Fixtures.instagramProfileReels(), Fixtures.instagramExplore(),
            Fixtures.instagramDirect(), Fixtures.instagramDirectThread()
        )) {
            assertEquals(VerdictKind.NOT_SHORTFORM, eval(s, flips = 3).kind)
        }
    }

    @Test
    fun `hidden viewer pager left in the tree on other tabs is ignored`() {
        val explore = Fixtures.instagramExplore()
        assertTrue(explore.nodes.any { it.viewId == InstagramReelsAdapter.VIEWER_PAGER && !it.visibleToUser })
        assertEquals(VerdictKind.NOT_SHORTFORM, eval(explore).kind)
    }

    @Test
    fun `ids are matched exactly, so clips_grid and the clips tab do not count`() {
        val profile = Fixtures.instagramProfileReels()
        assertTrue(profile.nodes.any { it.viewId?.endsWith("clips_grid_recyclerview") == true })
        assertEquals(VerdictKind.NOT_SHORTFORM, eval(profile).kind)
    }

    // --- 댓글창·공유 시트 ---

    @Test
    fun `comment sheet with the shrunk video above counts as watching`() {
        val v = eval(Fixtures.instagramReelsComments())
        assertEquals(VerdictKind.SHORTFORM_PARTIAL, v.kind)
        assertEquals(ReasonCode.PLAYER_PARTIAL, v.reason)
        assertEquals(0.272f, v.playerAreaRatio, 0.005f)
    }

    @Test
    fun `comment sheet that leaves almost no video is a brief cover`() {
        // 영상이 아주 작게 줄어든 경우(실측 아님: 끝까지 펼친 상태는 재지 못함)
        val s = Fixtures.instagramReelsComments().mapNodes {
            if (it.viewId == InstagramReelsAdapter.VIDEO_CONTAINER) it.copy(top = 74, bottom = 300, left = 440, right = 640) else it
        }
        assertEquals(VerdictKind.OVERLAY, eval(s).kind)
    }

    @Test
    fun `a sheet without the video found is a brief cover, never watching`() {
        // 실측: ID 검색 없이 순회만 한 댓글창(영상 노드가 없다) — 영상을 확인할 수 없으면 세지 않는다
        assertEquals(VerdictKind.OVERLAY, eval(Fixtures.instagramReelsCommentsNoLookup()).kind)
    }

    @Test
    fun `lookup only snapshots (what the service captures) give the same verdicts as the full dumps`() {
        // 서비스는 needsFullTree=false: root 한 개 + lookupViewIds 검색 결과만 담는다
        assertFalse(adapter.needsFullTree)
        val screens = listOf(
            Fixtures.instagramReels(), Fixtures.instagramExploreReel(), Fixtures.instagramReelsComments(),
            Fixtures.instagramReelsShare(), Fixtures.instagramFeed(), Fixtures.instagramStory(),
            Fixtures.instagramProfileReels(), Fixtures.instagramExplore(), Fixtures.instagramDirect(),
            Fixtures.instagramDirectThread(), Fixtures.instagramReelsPhoto(), Fixtures.instagramReelsImageAd()
        ) + Fixtures.instagramFeedScrolled()
        for (s in screens) {
            val lookupOnly = s.copy(nodes = listOf(s.nodes.first()) + s.nodes.drop(1).filter { it.viewId in adapter.lookupViewIds })
            assertEquals(eval(s).kind, eval(lookupOnly).kind)
            assertEquals(eval(s).confidence, eval(lookupOnly).confidence)
        }
    }

    @Test
    fun `share sheet is a brief cover even though the viewer below is found at full size`() {
        val share = Fixtures.instagramReelsShare()
        assertTrue(share.nodes.any {
            it.viewId == InstagramReelsAdapter.VIDEO_CONTAINER && it.visibleToUser && share.areaRatio(it) > 0.85f
        })
        val v = eval(share)
        assertEquals(VerdictKind.OVERLAY, v.kind)
        assertEquals(ReasonCode.OVERLAY_WINDOW, v.reason)
    }

    @Test
    fun `no sheet container is left in the normal reels screens`() {
        for (s in listOf(Fixtures.instagramReels(), Fixtures.instagramExploreReel())) {
            assertTrue(s.nodes.none { it.viewId == InstagramReelsAdapter.SHEET_CONTAINER && it.visibleToUser })
        }
    }

    // --- 트리 상태 ---

    @Test
    fun `empty tree is unknown`() {
        val v = eval(ScreenSnapshot(Fixtures.IG, Fixtures.W, Fixtures.H, emptyList()))
        assertEquals(VerdictKind.UNKNOWN, v.kind)
    }

    @Test
    fun `truncated tree without the viewer is unknown, not a confident no`() {
        assertEquals(VerdictKind.UNKNOWN, eval(Fixtures.instagramDirect().copy(truncated = true)).kind)
    }

    @Test
    fun `all decision nodes are looked up by id and the rule version changed`() {
        assertEquals("ig-1", InstagramReelsAdapter.RULE_VERSION)
        assertTrue(InstagramReelsAdapter.VIEWER_PAGER in adapter.lookupViewIds)
        assertTrue(InstagramReelsAdapter.VIDEO_CONTAINER in adapter.lookupViewIds)
        assertTrue(InstagramReelsAdapter.SHEET_CONTAINER in adapter.lookupViewIds)
        assertTrue(InstagramReelsAdapter.SHARE_RECIPIENTS in adapter.lookupViewIds)
    }
}

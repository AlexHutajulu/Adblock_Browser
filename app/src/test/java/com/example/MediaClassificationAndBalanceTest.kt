package com.example

import com.example.devtools.DevToolsResourceClassifier
import com.example.devtools.ResourceType as DevToolsResourceType
import com.example.manager.AbpRuleEngine
import com.example.manager.AbpRuleEngine.ResourceType
import com.yourbrowser.adblock.AdBlockWebViewClient
import com.yourbrowser.adblock.PrivacyMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaClassificationAndBalanceTest {

    @Test
    fun `inferType classifies media files, manifests, segments, and dynamic stream URLs with query tokens`() {
        assertEquals(
            ResourceType.MEDIA,
            AbpRuleEngine.inferType("https://cdn.example/video.mp4")
        )
        assertEquals(
            ResourceType.MEDIA,
            AbpRuleEngine.inferType("https://cdn.example/video.mp4?token=abc")
        )
        assertEquals(
            ResourceType.MEDIA_MANIFEST,
            AbpRuleEngine.inferType("https://cdn.example/hls/master.m3u8?signature=xyz")
        )
        assertEquals(
            ResourceType.MEDIA_MANIFEST,
            AbpRuleEngine.inferType("https://cdn.example/dash/manifest.mpd?token=123")
        )
        assertEquals(
            ResourceType.MEDIA_SEGMENT,
            AbpRuleEngine.inferType("https://cdn.example/segment/00001.m4s?key=abc")
        )
        assertEquals(
            ResourceType.MEDIA_SEGMENT,
            AbpRuleEngine.inferType("https://cdn.example/segment/00002.ts?expire=999")
        )
        assertEquals(
            ResourceType.MEDIA,
            AbpRuleEngine.inferType("https://cdn.example/stream?id=123")
        )
        assertEquals(
            ResourceType.SUBTITLE,
            AbpRuleEngine.inferType("https://cdn.example/subs/episode-01.id.vtt?token=sub123")
        )
    }

    @Test
    fun `inferType classifies thumbnails, player API requests, and main frame documents accurately`() {
        assertEquals(
            ResourceType.IMAGE,
            AbpRuleEngine.inferType("https://samehadaku.example/wp-content/uploads/2026/05/poster.jpg?resize=240,340")
        )
        assertEquals(
            ResourceType.IMAGE,
            AbpRuleEngine.inferType("https://cdn.example/thumb/cover.webp?token=xyz")
        )
        assertEquals(
            ResourceType.IMAGE,
            AbpRuleEngine.inferType("https://cdn.example/thumb/cover.avif?v=2")
        )
        assertEquals(
            ResourceType.XMLHTTPREQUEST,
            AbpRuleEngine.inferType(
                url = "https://player.example/player/config.json?id=42",
                acceptHeader = "application/json, text/plain, */*"
            )
        )
        assertEquals(
            ResourceType.XHR_FETCH,
            AbpRuleEngine.inferType(
                url = "https://player.example/api/v1/sources?id=42",
                requestHeaders = mapOf("Sec-Fetch-Mode" to "cors", "Sec-Fetch-Dest" to "empty")
            )
        )
        assertEquals(
            ResourceType.DOCUMENT,
            AbpRuleEngine.inferType(
                url = "https://samehadaku.example/anime/episode-1/",
                acceptHeader = "text/html,application/xhtml+xml",
                isForMainFrame = true
            )
        )
    }

    @Test
    fun `DevToolsResourceClassifier classifies mpeg-ts segments as MEDIA instead of SCRIPT`() {
        assertEquals(
            DevToolsResourceType.MEDIA,
            DevToolsResourceClassifier.classify("https://cdn.example/segment/00002.ts?expire=999")
        )
        assertEquals(
            DevToolsResourceType.SCRIPT,
            DevToolsResourceClassifier.classify("https://cdn.example/assets/player.js?v=1.2")
        )
    }

    @Test
    fun `AbpRuleEngine blocks explicit video ads but protects legitimate media from overly broad unanchored rules`() {
        val rules = sequenceOf(
            // Overly broad unanchored generic rules that previously broke video playback
            "/stream",
            "/video/",
            "/segment/",
            "||cdn.example^\$~media",
            "||cdn.example^\$popup",
            // Explicit ad rules that MUST still block video ads
            "||ads.example.com^\$media",
            "||doubleclick.net^"
        )
        val ruleSet = AbpRuleEngine.build(rules)

        // 1. Legitimate media URLs with query parameters matching broad unanchored rules MUST NOT be blocked
        val mp4Url = "https://cdn.example/video/episode1.mp4?token=abc"
        assertNull(
            "Legitimate MP4 with query token should not be blocked by unanchored /video/ or \$~media / \$popup rules",
            ruleSet.shouldBlock(mp4Url, "samehadaku.example", AbpRuleEngine.inferType(mp4Url))
        )

        val hlsManifestUrl = "https://cdn.example/hls/master.m3u8?signature=xyz"
        assertNull(
            "Legitimate HLS manifest should not be blocked",
            ruleSet.shouldBlock(hlsManifestUrl, "samehadaku.example", AbpRuleEngine.inferType(hlsManifestUrl))
        )

        val tsSegmentUrl = "https://cdn.example/segment/00002.ts?expire=999"
        assertNull(
            "Legitimate MPEG-TS segment should not be blocked by unanchored /segment/ rule",
            ruleSet.shouldBlock(tsSegmentUrl, "samehadaku.example", AbpRuleEngine.inferType(tsSegmentUrl))
        )

        val dynamicStreamUrl = "https://cdn.example/stream?id=123"
        assertNull(
            "Legitimate dynamic stream endpoint should not be blocked by unanchored /stream rule",
            ruleSet.shouldBlock(dynamicStreamUrl, "samehadaku.example", AbpRuleEngine.inferType(dynamicStreamUrl))
        )

        // 2. Explicit video ad URLs MUST still be blocked
        val adMediaUrl = "https://ads.example.com/preroll/spot.mp4?token=abc"
        assertNotNull(
            "Video ad on domain with explicit \$media rule must be blocked",
            ruleSet.shouldBlock(adMediaUrl, "samehadaku.example", AbpRuleEngine.inferType(adMediaUrl))
        )

        val doubleClickVastVideo = "https://doubleclick.net/video/preroll.mp4?token=abc"
        assertNotNull(
            "Video ad on doubleclick.net must be blocked",
            ruleSet.shouldBlock(doubleClickVastVideo, "samehadaku.example", AbpRuleEngine.inferType(doubleClickVastVideo))
        )
    }

    @Test
    fun `wildcard rules do not truncate suffix and do not block legitimate WordPress thumbnails`() {
        val rules = sequenceOf(
            "/wp-content/uploads/*-ad-banner.jpg",
            "||cdn.example/uploads/*/sponsor-*.webp"
        )
        val ruleSet = AbpRuleEngine.build(rules)

        val legitimateThumbnail = "https://samehadaku.example/wp-content/uploads/2026/05/anime-key-visual.jpg?resize=300,420"
        assertNull(
            "Wildcard rule /wp-content/uploads/*-ad-banner.jpg must not truncate after '*' and block normal uploads",
            ruleSet.shouldBlock(legitimateThumbnail, "samehadaku.example", ResourceType.IMAGE)
        )

        val legitimateCdnThumb = "https://cdn.example/uploads/2026/05/cover-art.webp?w=400"
        assertNull(
            "Wildcard rule on CDN must not block non-matching cover-art.webp",
            ruleSet.shouldBlock(legitimateCdnThumb, "samehadaku.example", ResourceType.IMAGE)
        )

        val matchingAdBanner = "https://thirdparty.example/wp-content/uploads/2026/05/top-ad-banner.jpg"
        assertNotNull(
            "Actual ad banner matching full wildcard sequence must be blocked",
            ruleSet.shouldBlock(matchingAdBanner, "samehadaku.example", ResourceType.IMAGE)
        )

        val matchingSponsorWebp = "https://cdn.example/uploads/2026/05/sponsor-slot.webp"
        assertNotNull(
            "Actual sponsor image matching full wildcard sequence must be blocked",
            ruleSet.shouldBlock(matchingSponsorWebp, "samehadaku.example", ResourceType.IMAGE)
        )
    }

    @Test
    fun `AdBlockWebViewClient Balance mode allows legitimate tokenized streams and blocks ad streams`() {
        val client = AdBlockWebViewClient(mode = PrivacyMode.BALANCED)

        assertNull(
            client.evaluateRequestDecision(
                rawUrl = "https://cdn.example/video.mp4?token=abc",
                pageHost = "samehadaku.example",
                mode = PrivacyMode.BALANCED
            )
        )
        assertNull(
            client.evaluateRequestDecision(
                rawUrl = "https://cdn.example/hls/master.m3u8?signature=xyz",
                pageHost = "samehadaku.example",
                mode = PrivacyMode.BALANCED
            )
        )
        assertNull(
            client.evaluateRequestDecision(
                rawUrl = "https://cdn.example/segment/00002.ts?expire=999",
                pageHost = "samehadaku.example",
                mode = PrivacyMode.BALANCED
            )
        )
        assertNull(
            client.evaluateRequestDecision(
                rawUrl = "https://cdn.example/stream?id=123",
                pageHost = "samehadaku.example",
                mode = PrivacyMode.BALANCED
            )
        )

        // Known ad domain or explicit ad path must still be blocked in Balance mode
        assertEquals(
            "ad-domain",
            client.evaluateRequestDecision(
                rawUrl = "https://doubleclick.net/video/preroll.mp4?token=abc",
                pageHost = "samehadaku.example",
                mode = PrivacyMode.BALANCED
            )
        )
        assertEquals(
            "ad-keyword",
            client.evaluateRequestDecision(
                rawUrl = "https://adcdn.example/ads/preroll.mp4?token=abc",
                pageHost = "samehadaku.example",
                mode = PrivacyMode.BALANCED
            )
        )

        // Sanitized diagnostic log strips query tokens
        assertEquals(
            "https://cdn.example/hls/master.m3u8?[REDACTED]",
            client.sanitizeUrlForDiagnosticLog("https://cdn.example/hls/master.m3u8?signature=secret_token_123")
        )
        assertTrue(client.isCleanStreamingPath("https://cdn.example/segment/00002.ts?expire=999"))
        assertFalse(client.isCleanStreamingPath("https://cdn.example/assets/app.js?ts=999"))
    }

    @Test
    fun `Tahap 2 - Samehadaku and Jetpack Photon proxied thumbnails are protected from generic rules`() {
        val rules = sequenceOf(
            // Overly broad unanchored rules that match words in anime titles or resize parameters
            "-banner-",
            "/promo-",
            "300,250",
            "-poster-\$image",
            // Explicit domain-anchored ad rule that MUST still block ad images
            "||ads.example.com^\$image"
        )
        val ruleSet = AbpRuleEngine.build(rules)

        // 1. Jetpack Photon same-site proxied thumbnail (i0.wp.com/samehadaku.care/wp-content/uploads/...)
        val photonThumb = "https://i0.wp.com/samehadaku.care/wp-content/uploads/2026/05/one-piece-banner-poster-1.jpg?resize=300,250&ssl=1"
        assertTrue(
            "i0.wp.com proxying samehadaku.care must be recognized as same-site image proxy",
            AbpRuleEngine.isSameSiteImageProxy("i0.wp.com", "samehadaku.care", photonThumb.lowercase())
        )
        assertFalse(
            "i0.wp.com proxying unrelated domain must not be treated as same-site proxy",
            AbpRuleEngine.isSameSiteImageProxy("i0.wp.com", "other-site.example", photonThumb.lowercase())
        )
        assertNull(
            "Samehadaku Photon thumbnail must not be blocked by unanchored generic rules",
            ruleSet.shouldBlock(photonThumb, "samehadaku.care", ResourceType.IMAGE)
        )

        // 2. Third-party CDN content thumbnail (/wp-content/uploads/, /thumbnails/, /poster/)
        val thirdPartyContentThumb = "https://cdn.animeimg.example/wp-content/uploads/2026/05/promo-key-poster-2.webp?w=240"
        assertTrue(AbpRuleEngine.isLikelyContentImagePath(thirdPartyContentThumb.lowercase()))
        assertNull(
            "Third-party CDN content thumbnail in /wp-content/uploads/ must not be blocked by unanchored rules",
            ruleSet.shouldBlock(thirdPartyContentThumb, "samehadaku.care", ResourceType.IMAGE)
        )

        // 3. Explicit ad image on ad domain or with explicit ad indicator MUST still be blocked
        val explicitAdImage = "https://ads.example.com/creatives/top-slot.jpg"
        assertNotNull(
            "Explicit domain-anchored \$image rule must block ad image",
            ruleSet.shouldBlock(explicitAdImage, "samehadaku.care", ResourceType.IMAGE)
        )

        val explicitAdBannerInUploads = "https://thirdparty.example/wp-content/uploads/2026/05/header-ad-banner.jpg"
        assertTrue(AbpRuleEngine.hasExplicitImageAdIndicator(explicitAdBannerInUploads.lowercase()))
    }

    @Test
    fun `Tahap 2 - emptyResponse and DOM cleanup scripts preserve content thumbnails and lazy images`() {
        val client = AdBlockWebViewClient(mode = PrivacyMode.BALANCED)

        // Media segment with query param containing .gif or /pixel must NOT be given a GIF or JS surrogate
        val mediaUri = android.net.Uri.parse("https://cdn.example/segment/00001.ts?fallback=pixel.gif")
        val mediaResp = client.emptyResponse(mediaUri, "video/mp2t")
        assertEquals("text/plain", mediaResp.mimeType)
        assertEquals(204, mediaResp.statusCode)

        // Genuine blocked ad image still receives 1x1 GIF surrogate to prevent anti-adblock onerror traps
        val adGifUri = android.net.Uri.parse("https://doubleclick.net/ads/banner.gif?cb=123")
        val adGifResp = client.emptyResponse(adGifUri, "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        assertEquals("image/gif", adGifResp.mimeType)
        assertEquals(200, adGifResp.statusCode)

        // Lazy image recovery and VisualAdKiller container protection in injected scripts
        val baseCleanup = AdBlockWebViewClient.buildBaseCleanupScript(aggressive = false)
        assertTrue(
            "BASE_CLEANUP_SCRIPT must include recoverLazyImages for data-src/data-lazy-src thumbnails",
            baseCleanup.contains("function recoverLazyImages(")
        )
        val visualKiller = AdBlockWebViewClient.buildVisualAdKiller(aggressive = false)
        assertTrue(
            "VisualAdKiller must protect containers with multiple images (.querySelectorAll('img').length>1)",
            visualKiller.contains("querySelectorAll('img').length>1")
        )
        assertTrue(
            "VisualAdKiller step2 must only climb to closest('a') rather than hiding shared div/section containers",
            visualKiller.contains("var c=el.closest('a')||el;")
        )
    }

    @Test
    fun `Tahap 3 - YouTube explicit ad endpoints are blocked in Balance mode while core player and streams remain allowed`() {
        val client = AdBlockWebViewClient(mode = PrivacyMode.BALANCED)
        client.updateCurrentPageUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        // 1. Explicit YouTube ad & ad-telemetry endpoints MUST be blocked even on first-party youtube.com
        val explicitAdUrls = listOf(
            "https://www.youtube.com/api/stats/ads?ver=2&adformat=15_2_1",
            "https://m.youtube.com/pagead/adview?ai=C12345&sigh=abc",
            "https://www.youtube.com/ptracking?html5=1&video_id=ad123&ptk=youtube_single",
            "https://www.youtube.com/youtubei/v1/log_event?alt=json&key=AIzaSy",
            "https://www.youtube.com/youtubei/v1/player/ad_break?key=AIzaSy",
            "https://www.youtube.com/get_midroll_info?video_id=dQw4w9WgXcQ",
            "https://www.youtube.com/pcs/activeview?xai=AKAOjs",
            "https://www.google.com/pagead/lvz?ai=C12345"
        )
        for (adUrl in explicitAdUrls) {
            val decision = client.evaluateRequestDecision(
                rawUrl = adUrl,
                pageHost = "www.youtube.com",
                mode = PrivacyMode.BALANCED
            )
            assertEquals("Expected $adUrl to be blocked as yt-ad-endpoint", "yt-ad-endpoint", decision)

            val surrogate = client.emptyResponse(android.net.Uri.parse(adUrl), "application/json")
            assertEquals("Expected 200 OK surrogate for $adUrl", 200, surrogate.statusCode)
            assertEquals("application/json", surrogate.mimeType)
        }

        // 2. Core YouTube player, video streams, navigation APIs, and thumbnails MUST be allowed
        val corePlayerAndMediaUrls = listOf(
            "https://www.youtube.com/youtubei/v1/player?key=AIzaSy&prettyPrint=false",
            "https://www.youtube.com/youtubei/v1/next?key=AIzaSy&prettyPrint=false",
            "https://www.youtube.com/youtubei/v1/browse?key=AIzaSy",
            "https://www.youtube.com/youtubei/v1/search?key=AIzaSy",
            "https://www.youtube.com/s/player/91234567/player_ias.vflset/en_US/base.js",
            "https://rr2---sn-npoe7nez.googlevideo.com/videoplayback?expire=1715000000&mime=video%2Fmp4&itag=137",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        )
        for (safeUrl in corePlayerAndMediaUrls) {
            val decision = client.evaluateRequestDecision(
                rawUrl = safeUrl,
                pageHost = "www.youtube.com",
                mode = PrivacyMode.BALANCED
            )
            assertNull("Expected core YouTube URL $safeUrl to be allowed", decision)
        }
    }

    @Test
    fun `Tahap 3 - YT_PAGE_SCRIPT guards player state and restores mute after ad finishes`() {
        val script = AdBlockWebViewClient.YT_PAGE_SCRIPT
        assertTrue(
            "YT_PAGE_SCRIPT must verify active .ad-showing or .ad-interrupting on the player container",
            script.contains("#movie_player.ad-showing,#movie_player.ad-interrupting")
        )
        assertFalse(
            "YT_PAGE_SCRIPT must not trigger video seek solely from residual .ytp-ad-text selector",
            script.contains("querySelector('.ad-showing,.ytp-ad-player-overlay,.ytp-ad-simple-ad-badge,.ytp-ad-text')")
        )
        assertTrue(
            "YT_PAGE_SCRIPT must track __asYtMutedByAd and restore mainV.muted when ad ends",
            script.contains("__asYtMutedByAd") && script.contains("mainV.muted=!!window.__asYtPrevMuted")
        )
        assertTrue(
            "YT_PAGE_SCRIPT must preserve debounced MutationObserver",
            script.contains("new MutationObserver(function(){skip();})")
        )
    }

    @Test
    fun `Tahap 4 - AbpRuleEngine handles exception rules, negated resource types, domain scoping, and third-party modifiers`() {
        val rules = sequenceOf(
            "||ads.example.com^",
            "||cdn-mixed.example.com^\$~image",
            "||stream-mixed.example.com^\$~media",
            "||thirdparty-ad.example.com^\$third-party",
            "||scoped-ad.example.com^\$domain=anime.example|manga.example",
            "||blocked-but-allowed.example.com^\$media",
            "@@||blocked-but-allowed.example.com/hls/\$media"
        )
        val ruleSet = AbpRuleEngine.build(rules)

        // 1. Domain anchor ||ads.example.com^ blocks exact and subdomain, but not prefix-collision host
        assertNotNull(ruleSet.shouldBlock("https://ads.example.com/banner.js", "anime.example", ResourceType.SCRIPT))
        assertNotNull(ruleSet.shouldBlock("https://sub.ads.example.com/slot.jpg", "anime.example", ResourceType.IMAGE))
        assertNull(ruleSet.shouldBlock("https://notads.example.com/app.js", "anime.example", ResourceType.SCRIPT))

        // 2. Negated ~image modifier blocks scripts on cdn-mixed.example.com but allows IMAGE thumbnails
        assertNotNull(
            "Script on cdn-mixed.example.com must be blocked by \$~image rule",
            ruleSet.shouldBlock("https://cdn-mixed.example.com/tracker.js", "anime.example", ResourceType.SCRIPT)
        )
        assertNull(
            "Thumbnail image on cdn-mixed.example.com must be exempted by \$~image modifier",
            ruleSet.shouldBlock("https://cdn-mixed.example.com/poster.jpg?w=300", "anime.example", ResourceType.IMAGE)
        )

        // 3. Negated ~media modifier blocks scripts on stream-mixed.example.com but allows MEDIA, MEDIA_MANIFEST, MEDIA_SEGMENT, SUBTITLE
        assertNotNull(
            "Script on stream-mixed.example.com must be blocked by \$~media rule",
            ruleSet.shouldBlock("https://stream-mixed.example.com/pop.js", "anime.example", ResourceType.SCRIPT)
        )
        assertNull(
            "HLS manifest must be exempted by \$~media modifier",
            ruleSet.shouldBlock("https://stream-mixed.example.com/live/master.m3u8?token=1", "anime.example", ResourceType.MEDIA_MANIFEST)
        )
        assertNull(
            "MPEG-TS segment must be exempted by \$~media modifier",
            ruleSet.shouldBlock("https://stream-mixed.example.com/live/seg01.ts?token=1", "anime.example", ResourceType.MEDIA_SEGMENT)
        )
        assertNull(
            "Subtitle file must be exempted by \$~media modifier",
            ruleSet.shouldBlock("https://stream-mixed.example.com/subs/id.vtt", "anime.example", ResourceType.SUBTITLE)
        )

        // 4. $third-party modifier blocks on third-party page but allows on first-party page
        assertNotNull(
            ruleSet.shouldBlock("https://thirdparty-ad.example.com/ad.js", "anime.example", ResourceType.SCRIPT)
        )
        assertNull(
            ruleSet.shouldBlock("https://thirdparty-ad.example.com/ad.js", "thirdparty-ad.example.com", ResourceType.SCRIPT)
        )

        // 5. $domain=anime.example|manga.example blocks only on listed domains
        assertNotNull(
            ruleSet.shouldBlock("https://scoped-ad.example.com/ad.js", "anime.example", ResourceType.SCRIPT)
        )
        assertNull(
            ruleSet.shouldBlock("https://scoped-ad.example.com/ad.js", "other-site.example", ResourceType.SCRIPT)
        )

        // 6. Exception rule @@||blocked-but-allowed.example.com/hls/$media overrides $media block rule
        assertNull(
            "Exception rule @@ must override blocking rule for HLS manifest",
            ruleSet.shouldBlock(
                "https://blocked-but-allowed.example.com/hls/master.m3u8?token=abc",
                "anime.example",
                ResourceType.MEDIA_MANIFEST
            )
        )
        assertNotNull(
            "Non-exempt path on blocked-but-allowed.example.com must still be blocked",
            ruleSet.shouldBlock(
                "https://blocked-but-allowed.example.com/ads/preroll.mp4",
                "anime.example",
                ResourceType.MEDIA
            )
        )
    }

    @Test
    fun `Tahap 4 - Mode switching OFF vs BALANCED vs AGGRESSIVE and CleanAdEngine-HeuristicsEngine parity`() {
        val client = AdBlockWebViewClient(mode = PrivacyMode.BALANCED)
        client.updateCurrentPageUrl("https://samehadaku.care/anime/one-piece/")

        val adScriptUrl = "https://doubleclick.net/pagead/ads.js"
        val aggressiveAdUrl = "https://amazon-adsystem.com/aax2/apstag.js"
        val animeStreamUrl = "https://cdn-stream.example/hls/episode-1100/seg-004.ts?token=xyz"
        val animeThumbUrl = "https://i0.wp.com/samehadaku.care/wp-content/uploads/2026/05/poster.jpg?resize=240,340"

        // 1. BALANCED mode: blocks known ad domain & former aggressive ad domain, allows legitimate stream & thumbnail
        assertEquals("ad-domain", client.evaluateRequestDecision(adScriptUrl, "samehadaku.care", PrivacyMode.BALANCED))
        assertEquals("aggressive-ad", client.evaluateRequestDecision(aggressiveAdUrl, "samehadaku.care", PrivacyMode.BALANCED))
        assertNull(client.evaluateRequestDecision(animeStreamUrl, "samehadaku.care", PrivacyMode.BALANCED))
        assertNull(client.evaluateRequestDecision(animeThumbUrl, "samehadaku.care", PrivacyMode.BALANCED))

        // 2. AGGRESSIVE mode (Ultra-Aggressive): blocks standard, aggressive, ultra-aggressive & first-party ad paths, STILL protects anime stream & content thumbnail
        val ultraAggressiveUrl = "https://tsyndicate.com/sdk/v1/pop.js"
        val firstPartyBannerSlot = "https://samehadaku.care/banner-slot/gacor.gif"
        client.setMode(PrivacyMode.AGGRESSIVE)
        assertEquals(
            com.yourbrowser.adblock.AdblockMode.AGGRESSIVE,
            com.yourbrowser.adblock.CleanAdEngine.currentMode
        )
        assertEquals("ad-domain", client.evaluateRequestDecision(adScriptUrl, "samehadaku.care", PrivacyMode.AGGRESSIVE))
        assertEquals("aggressive-ad", client.evaluateRequestDecision(aggressiveAdUrl, "samehadaku.care", PrivacyMode.AGGRESSIVE))
        assertEquals("ultra-aggressive-ad", client.evaluateRequestDecision(ultraAggressiveUrl, "samehadaku.care", PrivacyMode.AGGRESSIVE))
        assertEquals("first-party-aggressive-ad", client.evaluateRequestDecision(firstPartyBannerSlot, "samehadaku.care", PrivacyMode.AGGRESSIVE))
        assertTrue(
            "Judol popup URL must be auto-blocked even with user gesture",
            client.shouldBlockNavigationRedirect("https://situs-resmi.example/daftar-slot?rtp=99", "https://samehadaku.care", hasGesture = true)
        )
        assertNull(
            "Anime stream segment must remain unblocked even in AGGRESSIVE mode",
            client.evaluateRequestDecision(animeStreamUrl, "samehadaku.care", PrivacyMode.AGGRESSIVE)
        )
        assertNull(
            "Samehadaku Photon thumbnail must remain unblocked even in AGGRESSIVE mode",
            client.evaluateRequestDecision(animeThumbUrl, "samehadaku.care", PrivacyMode.AGGRESSIVE)
        )

        // 3. OFF mode: disables ad blocking
        client.setMode(PrivacyMode.OFF)
        client.setTrackerBlockingEnabled(enabled = false, activeWhenAdBlockOff = false)
        assertNull(client.evaluateRequestDecision(adScriptUrl, "samehadaku.care", PrivacyMode.OFF, trackerEnabled = false))
        assertNull(
            client.evaluateRequestDecision(
                "https://www.youtube.com/api/stats/ads?ver=2",
                "www.youtube.com",
                PrivacyMode.OFF,
                trackerEnabled = false
            )
        )

        // 4. HeuristicsEngine & CleanAdEngine parity: never false-positive on media segments or content thumbnails
        assertNull(
            com.yourbrowser.adblock.HeuristicsEngine.heuristicReason(
                host = "cdn-stream.example",
                lowerUrl = animeStreamUrl.lowercase(),
                isFirstParty = false,
                resType = ResourceType.MEDIA_SEGMENT,
                aggressive = false
            )
        )
        assertFalse(
            com.yourbrowser.adblock.CleanAdEngine.shouldBlock(
                url = animeStreamUrl,
                pageHost = "samehadaku.care",
                isThirdParty = true
            )
        )
    }

    @Test
    fun `Tahap 4 - youtube_guard asset and CustomWebView Data Saver image settings are verified`() {
        var guardFile = java.io.File("src/main/assets/adblock/youtube_guard.js")
        if (!guardFile.exists()) {
            guardFile = java.io.File("app/src/main/assets/adblock/youtube_guard.js")
        }
        if (!guardFile.exists()) {
            guardFile = java.io.File("../app/src/main/assets/adblock/youtube_guard.js")
        }
        assertTrue("youtube_guard.js asset must exist", guardFile.exists())
        val guardSource = guardFile.readText()
        assertTrue(guardSource.contains("adPlacements") && guardSource.contains("playerAds"))
        assertTrue(guardSource.contains("stripFeedItems"))
        assertFalse("youtube_guard.js must never delete streamingData", guardSource.contains("delete node.streamingData"))
        assertFalse("youtube_guard.js must never delete videoDetails", guardSource.contains("delete node.videoDetails"))

        var customWebViewFile = java.io.File("src/main/java/com/example/ui/components/CustomWebView.kt")
        if (!customWebViewFile.exists()) {
            customWebViewFile = java.io.File("app/src/main/java/com/example/ui/components/CustomWebView.kt")
        }
        if (!customWebViewFile.exists()) {
            customWebViewFile = java.io.File("../app/src/main/java/com/example/ui/components/CustomWebView.kt")
        }
        assertTrue("CustomWebView.kt must exist", customWebViewFile.exists())
        val customWebViewSource = customWebViewFile.readText()
        assertFalse(
            "CustomWebView must never disable loadsImagesAutomatically when Data Saver is toggled",
            customWebViewSource.contains("loadsImagesAutomatically = !isDataSaver")
        )
    }
}



package com.example.manager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.webkit.WebView
import com.example.MainActivity
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Lightweight bridge between the active HTML5 media WebView and [TabPersistenceService]
 * so Xiaomi HyperOS HyperIsland, Samsung One UI Live Media Capsule, and Android MediaStyle
 * notifications can control Play/Pause/Seek in real time and display real video/music thumbnails
 * while the app runs in the background or switches across multiple tabs.
 */
object BackgroundMediaController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val artworkExecutor = Executors.newCachedThreadPool()
    private val inFlightUrls = ConcurrentHashMap.newKeySet<String>()

    private val artworkCache = object : LruCache<String, Bitmap>(24) {}

    @Volatile private var activeMediaWebViewRef: WeakReference<WebView>? = null
    @Volatile var currentTitle: String = "AlexBrowser Media"
        private set
    @Volatile var currentArtist: String = "Pemutaran Latar Belakang Aktif"
        private set
    @Volatile var currentArtworkUrl: String = ""
        private set
    @Volatile var isPlaying: Boolean = false
        private set
    @Volatile var positionMs: Long = 0L
        private set
    @Volatile var durationMs: Long = 0L
        private set

    fun attachMediaWebView(webView: WebView) {
        val currentActive = activeMediaWebViewRef?.get()
        if (currentActive == null || !isPlaying || currentActive === webView) {
            activeMediaWebViewRef = WeakReference(webView)
        }
    }

    fun getCachedArtwork(url: String): Bitmap? {
        if (url.isBlank()) return null
        return synchronized(artworkCache) {
            artworkCache.get(url)
        }
    }

    fun fetchArtworkAsync(
        context: Context,
        artworkUrl: String,
        onLoaded: (Bitmap) -> Unit
    ) {
        val trimmed = artworkUrl.trim()
        if (trimmed.isBlank() || (!trimmed.startsWith("https://", ignoreCase = true) && !trimmed.startsWith("http://", ignoreCase = true))) {
            return
        }
        getCachedArtwork(trimmed)?.let {
            onLoaded(it)
            return
        }
        if (!inFlightUrls.add(trimmed)) return

        artworkExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(trimmed)
                connection = (url.openConnection() as? HttpURLConnection)?.apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                    )
                    setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                }
                val code = connection?.responseCode ?: -1
                if (code in 200..299) {
                    val rawBitmap = connection?.inputStream?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                    if (rawBitmap != null && rawBitmap.width > 0 && rawBitmap.height > 0) {
                        val framed = createRoundedCenterCropArtwork(rawBitmap, 256)
                        if (framed !== rawBitmap) {
                            rawBitmap.recycle()
                        }
                        synchronized(artworkCache) {
                            artworkCache.put(trimmed, framed)
                        }
                        mainHandler.post {
                            onLoaded(framed)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("BackgroundMediaCtrl", "Artwork download skipped for $trimmed: ${e.message}")
            } finally {
                inFlightUrls.remove(trimmed)
                try {
                    connection?.disconnect()
                } catch (_: Exception) {}
            }
        }
    }

    private fun createRoundedCenterCropArtwork(src: Bitmap, targetSize: Int): Bitmap {
        val out = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale: Float
        val dx: Float
        val dy: Float
        if (src.width * targetSize > targetSize * src.height) {
            scale = targetSize.toFloat() / src.height.toFloat()
            dx = (targetSize - src.width * scale) * 0.5f
            dy = 0f
        } else {
            scale = targetSize.toFloat() / src.width.toFloat()
            dx = 0f
            dy = (targetSize - src.height * scale) * 0.5f
        }
        val shaderMatrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        val bitmapShader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(shaderMatrix)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = bitmapShader
        }
        val radius = targetSize * 0.14f
        canvas.drawRoundRect(
            RectF(0f, 0f, targetSize.toFloat(), targetSize.toFloat()),
            radius,
            radius,
            paint
        )
        return out
    }

    @Volatile
    private var lastSyncedPositionMs: Long = 0L

    @Volatile
    private var lastSyncTimeMs: Long = 0L

    fun updateMediaMetadata(
        context: Context,
        webView: WebView?,
        playing: Boolean,
        title: String,
        artist: String,
        positionSec: Double = 0.0,
        durationSec: Double = 0.0,
        artworkUrl: String = ""
    ) {
        val existingActiveWv = activeMediaWebViewRef?.get()
        // Multi-Tab Protection: If Tab 1 is currently playing audio/video and the user switches to Tab 2
        // (which is NOT playing), never let Tab 2's idle/paused state overwrite Tab 1's active MediaSession!
        if (!playing && this.isPlaying && webView != null && existingActiveWv != null && webView !== existingActiveWv) {
            return
        }

        if (webView != null && (playing || existingActiveWv == null || webView === existingActiveWv)) {
            activeMediaWebViewRef = WeakReference(webView)
        }

        val cleanTitle = title.trim().ifBlank { "AlexBrowser Media" }
        val cleanArtist = artist.trim().ifBlank { "AlexBrowser" }
        val resolvedArtworkUrl = resolveBestArtworkUrl(artworkUrl, webView?.url, cleanArtist)
        val posMs = (positionSec.coerceAtLeast(0.0) * 1000.0).toLong()
        val durMs = (durationSec.coerceAtLeast(0.0) * 1000.0).toLong()

        val finalArtworkUrl = if (resolvedArtworkUrl.isNotBlank()) {
            resolvedArtworkUrl
        } else if (this.currentTitle == cleanTitle && this.currentArtworkUrl.isNotBlank()) {
            this.currentArtworkUrl
        } else {
            ""
        }

        val nowMs = System.currentTimeMillis()
        val expectedPosMs = if (this.isPlaying && lastSyncTimeMs > 0L) {
            lastSyncedPositionMs + (nowMs - lastSyncTimeMs)
        } else {
            lastSyncedPositionMs
        }
        val seekDetected = kotlin.math.abs(posMs - expectedPosMs) > 6000L && (nowMs - lastSyncTimeMs) > 1500L
        val durationChanged = durMs > 0L && kotlin.math.abs(durMs - this.durationMs) > 2000L

        val stateChanged = (this.isPlaying != playing) ||
            (this.currentTitle != cleanTitle) ||
            (this.currentArtist != cleanArtist) ||
            (this.currentArtworkUrl != finalArtworkUrl) ||
            durationChanged

        this.isPlaying = playing
        this.currentTitle = cleanTitle
        this.currentArtist = cleanArtist
        this.currentArtworkUrl = finalArtworkUrl
        this.positionMs = posMs
        this.durationMs = durMs

        if (stateChanged || seekDetected) {
            lastSyncedPositionMs = posMs
            lastSyncTimeMs = nowMs
            TabPersistenceService.syncMediaSession(
                context = context,
                isPlaying = playing,
                title = cleanTitle,
                artist = cleanArtist,
                positionMs = posMs,
                durationMs = durMs,
                artworkUrl = finalArtworkUrl
            )
        }
    }

    private fun resolveBestArtworkUrl(rawArtworkUrl: String, pageUrl: String?, artistHost: String): String {
        val cleanArt = rawArtworkUrl.trim()
        if (cleanArt.startsWith("https://", ignoreCase = true) || cleanArt.startsWith("http://", ignoreCase = true)) {
            return cleanArt
        }
        val candidatePage = pageUrl?.trim().orEmpty()
        if (candidatePage.contains("youtube.com", ignoreCase = true) || candidatePage.contains("youtu.be", ignoreCase = true)) {
            val ytId = extractYouTubeVideoId(candidatePage)
            if (ytId != null) {
                return "https://i.ytimg.com/vi/$ytId/hqdefault.jpg"
            }
        }
        return ""
    }

    private fun extractYouTubeVideoId(url: String): String? {
        return try {
            val uri = android.net.Uri.parse(url)
            val vParam = uri.getQueryParameter("v")
            if (!vParam.isNullOrBlank() && vParam.length >= 10) {
                return vParam
            }
            val path = uri.path.orEmpty()
            val match = Regex("/(?:shorts|embed|live|v)/([a-zA-Z0-9_-]{11})").find(path)
            if (match != null) {
                return match.groupValues[1]
            }
            if (uri.host?.contains("youtu.be", ignoreCase = true) == true) {
                val seg = path.trim('/').takeIf { it.length == 11 }
                if (seg != null) return seg
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun togglePlayPause(context: Context) {
        mainHandler.post {
            val wv = activeMediaWebViewRef?.get() ?: return@post
            wv.onResume()
            wv.resumeTimers()
            wv.evaluateJavascript(
                """
                (function() {
                    var mediaList = Array.from(document.querySelectorAll('video, audio'));
                    var active = mediaList.find(function(m) { return !m.paused && !m.ended; }) || mediaList[0];
                    if (!active) return;
                    if (active.paused) {
                        active.play().catch(function(){});
                    } else {
                        active.pause();
                    }
                })();
                """.trimIndent(),
                null
            )
        }
    }

    fun play(context: Context) {
        mainHandler.post {
            val wv = activeMediaWebViewRef?.get() ?: return@post
            wv.onResume()
            wv.resumeTimers()
            wv.evaluateJavascript(
                """
                (function() {
                    var mediaList = Array.from(document.querySelectorAll('video, audio'));
                    var active = mediaList[0];
                    if (active && active.paused) {
                        active.play().catch(function(){});
                    }
                })();
                """.trimIndent(),
                null
            )
        }
    }

    fun pause(context: Context) {
        mainHandler.post {
            val wv = activeMediaWebViewRef?.get() ?: return@post
            wv.evaluateJavascript(
                """
                (function() {
                    document.querySelectorAll('video, audio').forEach(function(m) {
                        if (!m.paused) m.pause();
                    });
                })();
                """.trimIndent(),
                null
            )
        }
    }

    fun seekBySeconds(deltaSeconds: Int) {
        mainHandler.post {
            val wv = activeMediaWebViewRef?.get() ?: return@post
            wv.evaluateJavascript(
                """
                (function() {
                    var mediaList = Array.from(document.querySelectorAll('video, audio'));
                    var active = mediaList.find(function(m) { return !m.paused && !m.ended; }) || mediaList[0];
                    if (active && isFinite(active.currentTime)) {
                        active.currentTime = Math.max(0, active.currentTime + ($deltaSeconds));
                    }
                })();
                """.trimIndent(),
                null
            )
        }
    }

    fun seekToMs(targetMs: Long) {
        val targetSec = (targetMs.coerceAtLeast(0L) / 1000.0)
        mainHandler.post {
            val wv = activeMediaWebViewRef?.get() ?: return@post
            wv.evaluateJavascript(
                """
                (function() {
                    var mediaList = Array.from(document.querySelectorAll('video, audio'));
                    var active = mediaList.find(function(m) { return !m.paused && !m.ended; }) || mediaList[0];
                    if (active) {
                        active.currentTime = $targetSec;
                    }
                })();
                """.trimIndent(),
                null
            )
        }
    }
}

class TabPersistenceService : Service() {

    companion object {
        private const val TAG = "TabPersistenceService"
        private const val CHANNEL_ID = "alexbrowser_hyperisland_media_channel"
        private const val NOTIFICATION_ID = 4088

        const val ACTION_SYNC_MEDIA = "com.example.action.SYNC_MEDIA"
        const val ACTION_TOGGLE_PLAY = "com.example.action.TOGGLE_PLAY"
        const val ACTION_REWIND_10S = "com.example.action.REWIND_10S"
        const val ACTION_FORWARD_10S = "com.example.action.FORWARD_10S"
        const val ACTION_STOP_MEDIA = "com.example.action.STOP_MEDIA"

        private const val EXTRA_IS_PLAYING = "extra_is_playing"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_ARTIST = "extra_artist"
        private const val EXTRA_POSITION_MS = "extra_position_ms"
        private const val EXTRA_DURATION_MS = "extra_duration_ms"
        private const val EXTRA_ARTWORK_URL = "extra_artwork_url"

        fun syncMediaSession(
            context: Context,
            isPlaying: Boolean,
            title: String,
            artist: String,
            positionMs: Long = 0L,
            durationMs: Long = 0L,
            artworkUrl: String = BackgroundMediaController.currentArtworkUrl
        ) {
            try {
                val intent = Intent(context, TabPersistenceService::class.java).apply {
                    action = ACTION_SYNC_MEDIA
                    putExtra(EXTRA_IS_PLAYING, isPlaying)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_ARTIST, artist)
                    putExtra(EXTRA_POSITION_MS, positionMs)
                    putExtra(EXTRA_DURATION_MS, durationMs)
                    putExtra(EXTRA_ARTWORK_URL, artworkUrl)
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to sync media session: ${e.message}")
            }
        }
    }

    private var mediaSession: MediaSession? = null
    private var cachedFallbackArtwork: Bitmap? = null
    private var isNotificationShown = false
    private var lastNotifiedPlaying: Boolean? = null
    private var lastNotifiedTitle: String = ""
    private var lastNotifiedArtist: String = ""
    private var lastNotifiedArtworkUrl: String = ""
    private var lastNotifiedArtworkBitmap: Bitmap? = null
    private var lastNotifiedDurationMs: Long = -1L

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        initMediaSession()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PLAY -> {
                val nextPlaying = !BackgroundMediaController.isPlaying
                BackgroundMediaController.togglePlayPause(applicationContext)
                updateMediaSessionAndNotification(
                    isPlaying = nextPlaying,
                    title = BackgroundMediaController.currentTitle,
                    artist = BackgroundMediaController.currentArtist,
                    positionMs = BackgroundMediaController.positionMs,
                    durationMs = BackgroundMediaController.durationMs,
                    artworkUrl = BackgroundMediaController.currentArtworkUrl
                )
            }
            ACTION_REWIND_10S -> {
                BackgroundMediaController.seekBySeconds(-10)
            }
            ACTION_FORWARD_10S -> {
                BackgroundMediaController.seekBySeconds(10)
            }
            ACTION_STOP_MEDIA -> {
                BackgroundMediaController.pause(applicationContext)
                cancelMediaNotification()
                mediaSession?.isActive = false
            }
            ACTION_SYNC_MEDIA -> {
                val playing = intent.getBooleanExtra(EXTRA_IS_PLAYING, BackgroundMediaController.isPlaying)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: BackgroundMediaController.currentTitle
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: BackgroundMediaController.currentArtist
                val posMs = intent.getLongExtra(EXTRA_POSITION_MS, BackgroundMediaController.positionMs)
                val durMs = intent.getLongExtra(EXTRA_DURATION_MS, BackgroundMediaController.durationMs)
                val artworkUrl = intent.getStringExtra(EXTRA_ARTWORK_URL) ?: BackgroundMediaController.currentArtworkUrl
                updateMediaSessionAndNotification(
                    isPlaying = playing,
                    title = title,
                    artist = artist,
                    positionMs = posMs,
                    durationMs = durMs,
                    artworkUrl = artworkUrl
                )
            }
            else -> {
                Log.d(TAG, "Persistent lightweight background service active (START_STICKY)")
            }
        }
        // START_STICKY ensures Android keeps the browser alive in background until user clears RAM / swipes from Recents
        return START_STICKY
    }

    private fun initMediaSession() {
        if (mediaSession != null) return
        try {
            val session = MediaSession(this, "AlexBrowserHyperIslandSession")
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val sessionActivityPendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            session.setSessionActivity(sessionActivityPendingIntent)
            session.setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    BackgroundMediaController.play(applicationContext)
                    updateMediaSessionAndNotification(
                        isPlaying = true,
                        title = BackgroundMediaController.currentTitle,
                        artist = BackgroundMediaController.currentArtist,
                        positionMs = BackgroundMediaController.positionMs,
                        durationMs = BackgroundMediaController.durationMs,
                        artworkUrl = BackgroundMediaController.currentArtworkUrl
                    )
                }

                override fun onPause() {
                    BackgroundMediaController.pause(applicationContext)
                    updateMediaSessionAndNotification(
                        isPlaying = false,
                        title = BackgroundMediaController.currentTitle,
                        artist = BackgroundMediaController.currentArtist,
                        positionMs = BackgroundMediaController.positionMs,
                        durationMs = BackgroundMediaController.durationMs,
                        artworkUrl = BackgroundMediaController.currentArtworkUrl
                    )
                }

                override fun onFastForward() {
                    BackgroundMediaController.seekBySeconds(10)
                }

                override fun onRewind() {
                    BackgroundMediaController.seekBySeconds(-10)
                }

                override fun onSeekTo(pos: Long) {
                    BackgroundMediaController.seekToMs(pos)
                }

                override fun onStop() {
                    BackgroundMediaController.pause(applicationContext)
                    cancelMediaNotification()
                    session.isActive = false
                }
            })
            mediaSession = session
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize MediaSession: ${e.message}")
        }
    }

    private fun updateMediaSessionAndNotification(
        isPlaying: Boolean,
        title: String,
        artist: String,
        positionMs: Long,
        durationMs: Long,
        artworkUrl: String = ""
    ) {
        initMediaSession()
        val session = mediaSession ?: return

        try {
            session.isActive = true
            val resolvedArtwork = resolveMediaArtwork(artist, artworkUrl)
            if (artworkUrl.isNotBlank() && BackgroundMediaController.getCachedArtwork(artworkUrl) == null) {
                BackgroundMediaController.fetchArtworkAsync(applicationContext, artworkUrl) {
                    updateMediaSessionAndNotification(
                        isPlaying = BackgroundMediaController.isPlaying,
                        title = BackgroundMediaController.currentTitle,
                        artist = BackgroundMediaController.currentArtist,
                        positionMs = BackgroundMediaController.positionMs,
                        durationMs = BackgroundMediaController.durationMs,
                        artworkUrl = BackgroundMediaController.currentArtworkUrl
                    )
                }
            }

            val metadataChanged = (lastNotifiedTitle != title) ||
                (lastNotifiedArtist != artist) ||
                (lastNotifiedArtworkUrl != artworkUrl) ||
                (lastNotifiedArtworkBitmap !== resolvedArtwork) ||
                (lastNotifiedDurationMs != durationMs)

            if (metadataChanged) {
                val metadataBuilder = MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, artist)
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, resolvedArtwork)
                    .putBitmap(MediaMetadata.METADATA_KEY_ART, resolvedArtwork)
                    .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, resolvedArtwork)
                if (durationMs > 0L) {
                    metadataBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                }
                if (artworkUrl.isNotBlank()) {
                    metadataBuilder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, artworkUrl)
                    metadataBuilder.putString(MediaMetadata.METADATA_KEY_ART_URI, artworkUrl)
                    metadataBuilder.putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI, artworkUrl)
                }
                session.setMetadata(metadataBuilder.build())
                lastNotifiedTitle = title
                lastNotifiedArtist = artist
                lastNotifiedArtworkUrl = artworkUrl
                lastNotifiedArtworkBitmap = resolvedArtwork
                lastNotifiedDurationMs = durationMs
            }

            val playbackState = PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_REWIND or
                        PlaybackState.ACTION_FAST_FORWARD or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP
                )
                .setState(
                    if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    positionMs.coerceAtLeast(0L),
                    if (isPlaying) 1.0f else 0.0f
                )
                .build()
            session.setPlaybackState(playbackState)

            val shouldNotify = metadataChanged || (lastNotifiedPlaying != isPlaying) || !isNotificationShown
            if (shouldNotify && (isPlaying || isNotificationShown)) {
                val notification = buildHyperIslandMediaNotification(
                    session = session,
                    isPlaying = isPlaying,
                    title = title,
                    artist = artist,
                    artwork = resolvedArtwork
                )
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                nm?.notify(NOTIFICATION_ID, notification)
                isNotificationShown = true
                lastNotifiedPlaying = isPlaying
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update HyperIsland / One UI media notification: ${e.message}")
        }
    }

    private fun resolveMediaArtwork(artistHost: String, artworkUrl: String): Bitmap {
        // 1. Primary: Real video/music thumbnail (YouTube thumbnail, Spotify album/artist photo, etc.)
        BackgroundMediaController.getCachedArtwork(artworkUrl)?.let { return it }

        // 2. Secondary: Real site favicon from FaviconStore while high-res thumbnail downloads
        val favicon = try {
            FaviconStore.get(artistHost) ?: FaviconStore.get(FaviconStore.hostOf("https://$artistHost"))
        } catch (_: Exception) {
            null
        }
        if (favicon != null && !favicon.isRecycled && favicon.width > 0 && favicon.height > 0) {
            return createFaviconCoverCard(favicon)
        }

        // 3. Fallback: Sleek Vinyl Media Disc artwork (never "A")
        return getOrCreateMediaDiscArtwork()
    }

    private fun createFaviconCoverCard(favicon: Bitmap): Bitmap {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                0xFF1E293B.toInt(),
                0xFF0F172A.toInt(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 36f, 36f, bgPaint)
        val iconSize = 148f
        val left = (size - iconSize) / 2f
        val top = (size - iconSize) / 2f
        val destRect = RectF(left, top, left + iconSize, top + iconSize)
        val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(favicon, null, destRect, bmpPaint)
        return bmp
    }

    private fun buildHyperIslandMediaNotification(
        session: MediaSession,
        isPlaying: Boolean,
        title: String,
        artist: String,
        artwork: Bitmap
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rewindPendingIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, TabPersistenceService::class.java).setAction(ACTION_REWIND_10S),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val togglePendingIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, TabPersistenceService::class.java).setAction(ACTION_TOGGLE_PLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val forwardPendingIntent = PendingIntent.getService(
            this,
            103,
            Intent(this, TabPersistenceService::class.java).setAction(ACTION_FORWARD_10S),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            104,
            Intent(this, TabPersistenceService::class.java).setAction(ACTION_STOP_MEDIA),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val rewindAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_rew),
            "-10s",
            rewindPendingIntent
        ).build()

        val playPauseAction = Notification.Action.Builder(
            Icon.createWithResource(
                this,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            ),
            if (isPlaying) "Pause" else "Play",
            togglePendingIntent
        ).build()

        val forwardAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_media_ff),
            "+10s",
            forwardPendingIntent
        ).build()

        val mediaStyle = Notification.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        // Xiaomi HyperOS HyperIsland & Samsung One UI Live Media Capsule extras
        val cleanTicker = title.replace("\"", "'")
        val hyperOsExtras = Bundle().apply {
            putBoolean("miui.showAction", true)
            putString(
                "miui.focus.param",
                "{\"protocol\":1,\"scene\":\"media\",\"ticker\":\"$cleanTicker\",\"enableFloat\":false,\"updatable\":true}"
            )
            putInt("android.ongoingActivityNoti.style", 1)
        }

        builder
            .setSmallIcon(
                if (isPlaying) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
            )
            .setLargeIcon(artwork)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText(artist)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(isPlaying)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setStyle(mediaStyle)
            .addExtras(hyperOsExtras)
            .addAction(rewindAction)
            .addAction(playPauseAction)
            .addAction(forwardAction)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setColorized(true)
            builder.setColor(0xFF0F172A.toInt())
        }

        return builder.build()
    }

    private fun cancelMediaNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        } finally {
            isNotificationShown = false
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "HyperIsland & Pemutaran Latar Belakang",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Kontrol musik & video untuk HyperOS HyperIsland, One UI Media Capsule, dan Layar Kunci"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun getOrCreateMediaDiscArtwork(): Bitmap {
        cachedFallbackArtwork?.let { return it }
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                0xFF1E293B.toInt(),
                0xFF0F172A.toInt(),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 36f, 36f, bgPaint)

        // Outer vinyl disc
        val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF334155.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, 86f, discPaint)

        val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x4494A3B8
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(center, center, 68f, groovePaint)
        canvas.drawCircle(center, center, 50f, groovePaint)

        // Center play triangle
        val playBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF38BDF8.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, 32f, playBgPaint)

        val trianglePath = Path().apply {
            moveTo(center - 9f, center - 14f)
            lineTo(center + 15f, center)
            lineTo(center - 9f, center + 14f)
            close()
        }
        val playIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawPath(trianglePath, playIconPaint)

        cachedFallbackArtwork = bmp
        return bmp
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved: App explicitly swiped away from recent apps / RAM cleaner")
        try {
            cancelMediaNotification()
            mediaSession?.isActive = false
            mediaSession?.release()
            mediaSession = null

            val settings = SettingsManager(applicationContext)
            if (settings.clearCookiesOnExit) {
                settings.clearPersistedTabs()
                try {
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    val cookieManager = android.webkit.CookieManager.getInstance()
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()
                    Log.d(TAG, "Cleared cookies, web storage, and persisted tabs on exit as configured")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear cookies on exit: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle task removal cleanup", e)
        }
        stopSelf()
    }

    override fun onDestroy() {
        try {
            mediaSession?.release()
            mediaSession = null
        } catch (_: Exception) {}
        super.onDestroy()
    }
}

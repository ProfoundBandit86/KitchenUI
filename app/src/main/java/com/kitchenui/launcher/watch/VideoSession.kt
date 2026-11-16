package com.kitchenui.launcher.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import com.fluxtv.app.App
import com.fluxtv.app.Iptv
import com.fluxtv.app.PlayInfo
import com.fluxtv.app.Sources
import com.fluxtv.app.Store
import com.fluxtv.app.Ui
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one video KitchenUI is playing. It lives outside any screen, so the video keeps
 * going while you move between the split-screen player, the home screen and the recipe book.
 * Saves progress every 10 seconds (also back to Plex/Jellyfin/Emby). Live TV gets channel up/down.
 */
@OptIn(UnstableApi::class)
object VideoSession {
    private val scope = MainScope()
    private var app: Context? = null
    var player: ExoPlayer? = null
        private set
    private var loops: Job? = null

    /** True while a video is loaded (playing or paused). */
    var active by mutableStateOf(false)
        private set
    /** Goes up each time a new video starts, so the launcher can switch to the player. */
    var opened by mutableIntStateOf(0)
        private set
    var fullScreen by mutableStateOf(false)
    var title by mutableStateOf("")
        private set
    var subtitle by mutableStateOf("")
        private set
    var poster by mutableStateOf("")
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var live by mutableStateOf(false)
        private set

    private var info = PlayInfo()
    private var url = ""
    private var triedHls = false
    private var liveSource: Iptv.Source? = null
    private var liveChannels: List<Iptv.Channel> = emptyList()
    private var liveIndex = 0

    /** Plays a movie or episode. */
    fun play(ctx: Context, info: PlayInfo, url: String, source: String) {
        ensure(ctx)
        this.info = info
        live = false
        liveSource = null
        liveChannels = emptyList()
        title = info.title()
        subtitle = source
        poster = info.poster
        start(url, resume = true)
    }

    /** Plays a live channel; channel up/down moves through [channels]. */
    fun playLive(ctx: Context, src: Iptv.Source, channels: List<Iptv.Channel>, index: Int) {
        ensure(ctx)
        val c = channels.getOrNull(index) ?: return
        info = PlayInfo.of("tv", "", "", c.name, "", "")
        live = true
        liveSource = src
        liveChannels = channels
        liveIndex = index
        poster = ""
        updateLiveTitle()
        start(Iptv.liveUrl(src, c, false), resume = false)
    }

    private fun ensure(ctx: Context) {
        if (player != null) return
        val a = ctx.applicationContext
        app = a
        App.init(a)
        val p = buildPlayer(a)
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) = onError(e)
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !live) {
                    save(ended = true)
                    close()
                }
            }
        })
        player = p
        loops = scope.launch {
            var tick = 0
            while (true) {
                delay(500)
                val pl = player ?: continue
                position = pl.currentPosition.coerceAtLeast(0)
                duration = pl.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
                if (++tick % 20 == 0 && pl.isPlaying) save()
            }
        }
    }

    private fun start(u: String, resume: Boolean) {
        val p = player ?: return
        url = u
        error = null
        triedHls = false
        val item = MediaItem.Builder()
            .setUri(Uri.parse(u))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        p.setMediaItem(item)
        if (resume) {
            val pr = Store.progress(info.videoId)
            if (pr != null && !Store.isFinished(pr) && pr.optLong("pos") > 30_000) {
                p.seekTo(pr.optLong("pos"))
                subtitle = "Resuming from " + Ui.time(pr.optLong("pos"))
            }
        }
        p.prepare()
        p.playWhenReady = true
        active = true
        opened++
    }

    private fun updateLiveTitle() {
        val c = liveChannels.getOrNull(liveIndex) ?: return
        title = (if (c.num.isNotBlank()) "${c.num}  " else "") + c.name
        subtitle = "Live TV"
        val src = liveSource ?: return
        scope.launch {
            val now = withContext(Dispatchers.IO) { runCatching { Iptv.nowNext(src, c).firstOrNull()?.title }.getOrNull() }
            if (!now.isNullOrBlank()) subtitle = "Live · $now"
        }
    }

    fun zap(delta: Int) {
        val src = liveSource ?: return
        if (liveChannels.isEmpty()) return
        liveIndex = Math.floorMod(liveIndex + delta, liveChannels.size)
        updateLiveTitle()
        val keepOpen = opened
        start(Iptv.liveUrl(src, liveChannels[liveIndex], false), resume = false)
        opened = keepOpen   // changing channel shouldn't pull you back to the player
    }

    private fun onError(e: PlaybackException) {
        // Xtream live: if MPEG-TS fails, try the HLS version of the channel once.
        val src = liveSource
        val c = liveChannels.getOrNull(liveIndex)
        if (live && !triedHls && src != null && c != null && src.xtream()) {
            triedHls = true
            val p = player ?: return
            p.setMediaItem(MediaItem.fromUri(Iptv.liveUrl(src, c, true)))
            p.prepare()
            p.playWhenReady = true
            return
        }
        error = "Can't play this: ${e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()}"
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
    }

    fun pause() { player?.pause() }

    fun seekBy(ms: Long) {
        val p = player ?: return
        val d = p.duration
        var t = p.currentPosition + ms
        if (d != C.TIME_UNSET && d > 0) t = t.coerceAtMost(d - 1000)
        p.seekTo(t.coerceAtLeast(0))
        position = p.currentPosition
    }

    fun seekTo(fraction: Float) {
        val p = player ?: return
        val d = p.duration
        if (d == C.TIME_UNSET || d <= 0) return
        p.seekTo((d * fraction.coerceIn(0f, 1f)).toLong())
        position = p.currentPosition
    }

    fun openExternal(ctx: Context) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").putExtra("title", title)
        runCatching { ctx.startActivity(Intent.createChooser(i, "Play with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        pause()
    }

    /** Stops the video and frees the decoder. */
    fun close() {
        save()
        loops?.cancel()
        loops = null
        player?.release()
        player = null
        active = false
        isPlaying = false
        fullScreen = false
        error = null
        position = 0
        duration = 0
    }

    private fun save(ended: Boolean = false) {
        val p = player ?: return
        if (live || info.videoId.isBlank()) return
        val dur = p.duration
        if (dur <= 0 || dur == C.TIME_UNSET) return
        val pos = if (ended) dur else p.currentPosition
        Store.saveProgress(info, pos, dur)
        Sources.report(info.videoId, pos, dur)
    }

    fun buildPlayer(c: Context): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("KitchenUI/1.0 (Android)")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val data = DefaultDataSource.Factory(c, http)
        // Device decoders first; FFmpeg for audio the device can't play (DTS, TrueHD, E-AC3…).
        val renderers = DefaultRenderersFactory(c)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
        return ExoPlayer.Builder(c, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(data, extractors))
            .build()
    }
}

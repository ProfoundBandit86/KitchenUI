package com.kitchenui.launcher.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
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
import androidx.media3.ui.PlayerView
import com.fluxtv.app.App
import com.fluxtv.app.Iptv
import com.fluxtv.app.PlayInfo
import com.fluxtv.app.Sources
import com.fluxtv.app.Store
import com.fluxtv.app.Ui
import com.kitchenui.launcher.ui.Btn
import com.kitchenui.launcher.ui.Glyph
import com.kitchenui.launcher.ui.K
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen player with touch controls (tap to show the bar, drag to seek,
 * gear for audio/subtitle tracks). Resumes where you stopped and saves progress
 * every 10 seconds, also back to Plex/Jellyfin/Emby. Live TV gets channel up/down.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private lateinit var info: PlayInfo
    private var url = ""
    private var live = false
    private var triedHls = false

    // Live TV channel switching
    private var liveSource: Iptv.Source? = null
    private var liveChannels: List<Iptv.Channel> = emptyList()
    private var liveIndex = 0

    private var title by mutableStateOf("")
    private var subtitle by mutableStateOf("")
    private var controlsVisible by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        App.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()

        info = PlayInfo.from(intent)
        url = intent.getStringExtra("url") ?: ""
        live = intent.getBooleanExtra("live", false)
        title = if (live) info.name else info.title()
        subtitle = intent.getStringExtra("source") ?: ""

        player = buildPlayer(this)
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) = onError(e)
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && !live) {
                    save(ended = true)
                    finish()
                }
            }
        })

        setContent { PlayerUi() }

        if (live) setupLive()
        start(url, resume = !live)

        // Save progress every 10 s
        lifecycleScope.launch {
            while (true) {
                delay(10_000)
                if (player.isPlaying) save()
            }
        }
    }

    private fun setupLive() {
        val srcId = intent.getStringExtra("live_source") ?: return
        val cat = intent.getStringExtra("live_cat")
        liveIndex = intent.getIntExtra("live_index", 0)
        lifecycleScope.launch {
            val src = Iptv.source(srcId) ?: return@launch
            liveSource = src
            liveChannels = withContext(Dispatchers.IO) { runCatching { Iptv.channelsIn(src, cat) }.getOrDefault(emptyList()) }
            updateLiveTitle()
        }
    }

    private fun updateLiveTitle() {
        val c = liveChannels.getOrNull(liveIndex) ?: return
        title = (if (c.num.isNotBlank()) "${c.num}  " else "") + c.name
        val src = liveSource ?: return
        lifecycleScope.launch {
            val now = withContext(Dispatchers.IO) { runCatching { Iptv.nowNext(src, c).firstOrNull()?.title }.getOrNull() }
            subtitle = now ?: ""
        }
    }

    private fun zap(delta: Int) {
        val src = liveSource ?: return
        if (liveChannels.isEmpty()) return
        liveIndex = Math.floorMod(liveIndex + delta, liveChannels.size)
        triedHls = false
        error = null
        updateLiveTitle()
        start(Iptv.liveUrl(src, liveChannels[liveIndex], false), resume = false)
    }

    private fun start(u: String, resume: Boolean) {
        url = u
        val item = MediaItem.Builder()
            .setUri(Uri.parse(u))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        player.setMediaItem(item)
        if (resume) {
            val p = Store.progress(info.videoId)
            if (p != null && !Store.isFinished(p) && p.optLong("pos") > 30_000) {
                player.seekTo(p.optLong("pos"))
                subtitle = "Resuming from " + Ui.time(p.optLong("pos"))
            }
        }
        player.prepare()
        player.playWhenReady = true
    }

    private fun onError(e: PlaybackException) {
        // Xtream live: if MPEG-TS fails, try the HLS version of the channel once.
        val src = liveSource
        val c = liveChannels.getOrNull(liveIndex)
        if (live && !triedHls && src != null && c != null && src.xtream()) {
            triedHls = true
            start(Iptv.liveUrl(src, c, true), resume = false)
            return
        }
        error = "Can't play this: ${e.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase()}"
    }

    private fun save(ended: Boolean = false) {
        if (live || info.videoId.isBlank()) return
        val dur = player.duration
        if (dur <= 0 || dur == C.TIME_UNSET) return
        val pos = if (ended) dur else player.currentPosition
        Store.saveProgress(info, pos, dur)
        Sources.report(info.videoId, pos, dur)
    }

    private fun openExternal() {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").putExtra("title", title)
        runCatching { startActivity(Intent.createChooser(i, "Play with")) }
    }

    override fun onStop() {
        super.onStop()
        save()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    @androidx.compose.runtime.Composable
    private fun PlayerUi() {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = this@PlayerActivity.player
                        useController = true
                        controllerShowTimeoutMs = 4000
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                            controlsVisible = v == View.VISIBLE
                        })
                        if (live) {
                            setShowFastForwardButton(false)
                            setShowRewindButton(false)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (controlsVisible || error != null) {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = 28.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Btn("Back", { finish() }, height = 48.dp, fontSize = 17, icon = Glyph.BACK)
                    Column(Modifier.weight(1f)) {
                        Text(title, color = K.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle.isNotBlank()) Text(subtitle, color = K.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (live) {
                        Btn("Channel −", { zap(-1) }, height = 48.dp, fontSize = 17)
                        Btn("Channel +", { zap(1) }, height = 48.dp, fontSize = 17)
                    } else {
                        Btn("Other player", { openExternal() }, height = 48.dp, fontSize = 17)
                    }
                }
            }
            error?.let { msg ->
                Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.width(560.dp).clip(RoundedCornerShape(22.dp)).background(K.Surface).padding(28.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(msg, color = K.Text, fontSize = 20.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Btn(if (live) "Back to channels" else "Pick another source", { finish() }, primary = true, height = 50.dp)
                            if (live) {
                                Btn("Next channel", { zap(1) }, height = 50.dp)
                            } else {
                                Btn("Open in another player", { openExternal() }, height = 50.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
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
}

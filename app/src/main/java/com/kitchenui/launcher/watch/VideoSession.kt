package com.kitchenui.launcher.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** One audio or subtitle choice; id -1 means subtitles off. */
data class Track(val id: Int, val name: String)

/**
 * The one video KitchenUI is playing, on a built-in VLC engine so nearly every codec and
 * container plays (HEVC, AV1, DTS, TrueHD, MKV, MPEG-TS…). It lives outside any screen, so the
 * video keeps going while you move between the split-screen player, home and the recipe book.
 *
 * Defaults: English audio when there is an English track, subtitles off. Picking a track in
 * the player overrides that for the current video.
 */
object VideoSession {
    private val scope = MainScope()
    private var libVlc: LibVLC? = null
    private var mp: MediaPlayer? = null
    private var layout: VLCVideoLayout? = null
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
    var buffering by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var live by mutableStateOf(false)
        private set
    /** Bumps when the track lists change, so pickers refresh. */
    var tracksVersion by mutableIntStateOf(0)
        private set

    private var info = PlayInfo()
    private var url = ""
    private var triedHls = false
    private var userAudio = false
    private var userSubs = false
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
        opened++
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
        opened++
    }

    private fun ensure(ctx: Context) {
        if (mp != null) return
        val a = ctx.applicationContext
        App.init(a)
        val vlc = LibVLC(
            a,
            arrayListOf(
                "--audio-time-stretch",
                "--audio-language=en,eng,english",
                "--sub-language=none",
                "--no-sub-autodetect-file",
                "--http-reconnect",
                "--network-caching=2000",
                "--live-caching=2000",
            ),
        )
        val p = MediaPlayer(vlc)
        p.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> { isPlaying = true; buffering = false }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> isPlaying = false
                MediaPlayer.Event.Buffering -> buffering = ev.buffering < 100f
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.Vout -> { applyDefaults(); tracksVersion++ }
                MediaPlayer.Event.EndReached -> if (!live) { save(ended = true); close() }
                MediaPlayer.Event.EncounteredError -> onError()
            }
        }
        libVlc = vlc
        mp = p
        layout?.let { p.attachViews(it, null, true, false) }
        loops = scope.launch {
            var tick = 0
            while (true) {
                delay(500)
                val pl = mp ?: continue
                position = pl.time.coerceAtLeast(0)
                duration = pl.length.coerceAtLeast(0)
                if (++tick % 20 == 0 && pl.isPlaying) save()
            }
        }
    }

    private fun start(u: String, resume: Boolean) {
        val p = mp ?: return
        val vlc = libVlc ?: return
        url = u
        error = null
        triedHls = false
        userAudio = false
        userSubs = false
        val m = Media(vlc, Uri.parse(u))
        m.setHWDecoderEnabled(true, false)   // hardware when it can, VLC's software decoders when it can't
        m.addOption(":http-user-agent=KitchenUI/1.0 (Android)")
        if (resume) {
            val pr = Store.progress(info.videoId)
            if (pr != null && !Store.isFinished(pr) && pr.optLong("pos") > 30_000) {
                m.addOption(":start-time=" + pr.optLong("pos") / 1000)
                subtitle = "Resuming from " + Ui.time(pr.optLong("pos"))
            }
        }
        p.setMedia(m)
        m.release()
        p.play()
        active = true
        buffering = true
    }

    /** English audio if there is one, and subtitles off, unless picked by hand. */
    private fun applyDefaults() {
        val p = mp ?: return
        if (!userAudio) {
            val tracks = p.audioTracks ?: emptyArray()
            val english = tracks.firstOrNull { it.id >= 0 && isEnglish(it.name) }
            val current = tracks.firstOrNull { it.id == p.audioTrack }
            if (english != null && (current == null || !isEnglish(current.name))) p.setAudioTrack(english.id)
        }
        if (!userSubs && p.spuTrack != -1) p.setSpuTrack(-1)
    }

    private val englishName = Regex("(?i)(english|\\beng\\b|\\[en]|\\ben\\b|en-us|en-gb)")
    private fun isEnglish(name: String?) = name != null && englishName.containsMatchIn(name)

    fun audioTracks(): List<Track> =
        (mp?.audioTracks ?: emptyArray()).filter { it.id >= 0 }.map { Track(it.id, it.name ?: "Track ${it.id}") }

    fun subtitleTracks(): List<Track> =
        listOf(Track(-1, "Off")) + (mp?.spuTracks ?: emptyArray()).filter { it.id >= 0 }.map { Track(it.id, it.name ?: "Subtitles ${it.id}") }

    val audioTrack: Int get() = mp?.audioTrack ?: -1
    val subtitleTrack: Int get() = mp?.spuTrack ?: -1

    fun pickAudio(id: Int) { userAudio = true; mp?.setAudioTrack(id); tracksVersion++ }
    fun pickSubtitles(id: Int) { userSubs = true; mp?.setSpuTrack(id); tracksVersion++ }

    /** Shows the video in [v]; only one view shows it at a time. */
    fun attach(v: VLCVideoLayout) {
        if (layout === v) return
        val p = mp
        if (p != null && layout != null) p.detachViews()
        layout = v
        p?.attachViews(v, null, true, false)
    }

    fun detach(v: VLCVideoLayout) {
        if (layout !== v) return
        mp?.detachViews()
        layout = null
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
        start(Iptv.liveUrl(src, liveChannels[liveIndex], false), resume = false)
    }

    private fun onError() {
        // Xtream live: if MPEG-TS fails, try the HLS version of the channel once.
        val src = liveSource
        val c = liveChannels.getOrNull(liveIndex)
        if (live && !triedHls && src != null && c != null && src.xtream()) {
            start(Iptv.liveUrl(src, c, true), resume = false)
            triedHls = true
            return
        }
        isPlaying = false
        error = "This video couldn't be played. The link may have expired; try another source."
    }

    fun togglePlay() {
        val p = mp ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun pause() { mp?.takeIf { it.isPlaying }?.pause() }

    fun seekBy(ms: Long) {
        val p = mp ?: return
        var t = p.time + ms
        val d = p.length
        if (d > 0) t = t.coerceAtMost(d - 1000)
        p.setTime(t.coerceAtLeast(0))
        position = p.time
    }

    fun seekTo(fraction: Float) {
        val p = mp ?: return
        val d = p.length
        if (d <= 0) return
        p.setTime((d * fraction.coerceIn(0f, 1f)).toLong())
        position = p.time
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
        mp?.let { p ->
            runCatching { p.stop() }
            runCatching { p.detachViews() }
            p.setEventListener(null)
            p.release()
        }
        mp = null
        libVlc?.release()
        libVlc = null
        active = false
        isPlaying = false
        buffering = false
        fullScreen = false
        error = null
        position = 0
        duration = 0
    }

    private fun save(ended: Boolean = false) {
        val p = mp ?: return
        if (live || info.videoId.isBlank()) return
        val dur = p.length
        if (dur <= 0) return
        val pos = if (ended) dur else p.time
        Store.saveProgress(info, pos, dur)
        Sources.report(info.videoId, pos, dur)
    }
}

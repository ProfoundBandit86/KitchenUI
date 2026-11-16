package com.kitchenui.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android only shares other apps' media sessions with apps that have notification access,
 * so KitchenUI declares this (empty) listener. Turn it on in Settings › Notification access.
 */
class MediaListener : NotificationListenerService()

/** What's playing in any app (Spotify, YouTube, Plex…) with play/pause and skip. */
class MediaState(private val ctx: Context) {
    var granted by mutableStateOf(false)
        private set
    var title by mutableStateOf("")
        private set
    var artist by mutableStateOf("")
        private set
    var appName by mutableStateOf("")
        private set
    var art by mutableStateOf<ImageBitmap?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var active by mutableStateOf(false)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component = ComponentName(ctx, MediaListener::class.java)
    private var controller: MediaController? = null
    private var listening = false

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list ?: emptyList()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = update()
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onSessionDestroyed() {
            main.post { pick(currentSessions()) }
        }
    }

    /** Call from onResume: picks up access granted in Settings meanwhile. */
    fun start() {
        val list = try {
            msm.getActiveSessions(component)
        } catch (e: SecurityException) {
            granted = false
            clear()
            return
        }
        granted = true
        if (!listening) {
            runCatching { msm.addOnActiveSessionsChangedListener(sessionsChanged, component, main) }
            listening = true
        }
        pick(list)
    }

    fun stop() {
        if (listening) runCatching { msm.removeOnActiveSessionsChangedListener(sessionsChanged) }
        listening = false
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun currentSessions(): List<MediaController> =
        try { msm.getActiveSessions(component) } catch (e: SecurityException) { emptyList() }

    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.metadata != null }
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        update()
    }

    private fun update() {
        val c = controller
        val md = c?.metadata
        if (c == null || md == null) {
            clear()
            return
        }
        title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val bmp: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        art = bmp?.let { runCatching { Bitmap.createScaledBitmap(it, 160, 160, true).asImageBitmap() }.getOrNull() }
        appName = runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault("")
        playing = c.playbackState?.state == PlaybackState.STATE_PLAYING || c.playbackState?.state == PlaybackState.STATE_BUFFERING
        active = title.isNotBlank()
    }

    private fun clear() {
        title = ""; artist = ""; appName = ""; art = null; playing = false; active = false
    }

    fun playPause() {
        val t = controller?.transportControls ?: return
        if (playing) t.pause() else t.play()
    }

    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }

    fun openApp() {
        val pkg = controller?.packageName ?: return
        controller?.sessionActivity?.let { runCatching { it.send() }.onSuccess { return } }
        ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
            ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun requestAccess() {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

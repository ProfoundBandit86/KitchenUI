package com.kitchenui.launcher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import androidx.activity.result.contract.ActivityResultContracts
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.kitchenui.launcher.watch.LiveScreen
import com.kitchenui.launcher.watch.PlayerScreen
import com.kitchenui.launcher.watch.VideoSession
import com.kitchenui.launcher.watch.WatchModel
import com.kitchenui.launcher.watch.WatchScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.kitchenui.launcher.ui.AppDrawer
import com.kitchenui.launcher.ui.CookScreen
import com.kitchenui.launcher.ui.DesignCanvas
import com.kitchenui.launcher.ui.HomeScreen
import com.kitchenui.launcher.ui.RecipesScreen
import com.kitchenui.launcher.ui.SearchOverlay
import com.kitchenui.launcher.ui.SettingsPanel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var state: KitchenState

    // System speech recognizer (the Google app on Bliss OS with GApps). No mic permission needed here.
    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val heard = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (res.resultCode == RESULT_OK && !heard.isNullOrBlank()) {
            state.voice.query = heard
            state.runVoiceCommand(heard)?.let { state.voice.message = it }
        }
    }

    private fun listen(): Boolean = try {
        speech.launch(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search or ask KitchenUI")
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    // Reload the app list only when an app is installed, removed or updated.
    private val packages = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context?, i: Intent?) { state.refreshApps(force = true) }
    }

    private val voiceActions = setOf(
        Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND, Intent.ACTION_SEARCH_LONG_PRESS, "android.intent.action.WEB_SEARCH",
    )

    /** The keyboard or remote search/assistant key opens voice search. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_SEARCH || keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KeyEvent.KEYCODE_ASSIST) {
            state.voice.start()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        state = KitchenState(applicationContext, lifecycleScope)
        state.refreshApps()
        state.applyTheme()
        state.voice.listen = ::listen
        androidx.core.content.ContextCompat.registerReceiver(this, packages, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        setContent { KitchenApp(state) }
        if (intent?.action in voiceActions) state.voice.start()
    }

    override fun onResume() {
        super.onResume()
        state.playing = false
        state.media.start()
        hideSystemBars()
        state.refreshApps()
    }

    override fun onPause() {
        super.onPause()
        state.playing = true   // another app is in front: don't install updates now
    }

    override fun onStop() {
        super.onStop()
        VideoSession.pause()   // another app took the screen; resume from the now playing card
    }

    override fun onDestroy() {
        super.onDestroy()
        state.media.stop()
        runCatching { unregisterReceiver(packages) }
        if (isFinishing) VideoSession.close()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // Pressing Home while the launcher is open returns to the home screen.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        state.showSettings = false
        if (intent.action in voiceActions) {
            state.voice.start()
            return
        }
        state.voice.close()
        VideoSession.fullScreen = false
        if (state.screen !is Screen.Cook) state.screen = Screen.Home
    }

    private fun hideSystemBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
fun KitchenApp(s: KitchenState) {
    // Clock + timers
    LaunchedEffect(Unit) {
        while (true) {
            val t = System.currentTimeMillis()
            s.timers.tick(t)
            // The clock only shows minutes: updating it once a minute stops the whole home
            // screen redrawing twice a second.
            if (t / 60_000 != s.now / 60_000) {
                s.now = t
                s.applyTheme()
            }
            delay(500)
        }
    }
    // Weather + calendar every 15 minutes
    LaunchedEffect(Unit) {
        while (true) {
            s.refreshWeather()
            s.refreshCalendar()
            delay(15 * 60_000L)
        }
    }
    // Recipes + app update check every 3 hours
    LaunchedEffect(Unit) {
        delay(10_000)
        while (true) {
            s.refreshRecipes()
            s.checkForUpdate(manual = false)
            delay(3 * 3_600_000L)
        }
    }
    // A downloaded update waits until the kitchen is idle, then installs.
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            if (s.update is UpdateState.Ready) s.checkForUpdate(manual = false)
        }
    }

    // A video started in Watch or Live TV: show it split with the kitchen.
    val seen = remember { intArrayOf(VideoSession.opened) }
    LaunchedEffect(VideoSession.opened) {
        if (VideoSession.opened != seen[0]) {
            seen[0] = VideoSession.opened
            VideoSession.fullScreen = false
            s.screen = Screen.Player
        }
    }

    BackHandler(enabled = true) {
        when {
            s.voice.open -> s.voice.close()
            s.showSettings -> s.showSettings = false
            s.screen == Screen.Player && VideoSession.fullScreen -> VideoSession.fullScreen = false
            s.screen != Screen.Home -> s.screen = Screen.Home
        }
    }

    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val watch = remember { WatchModel(ctx, scope) }
    // Watch and Live TV load in the background while the home screen is up.
    LaunchedEffect(watch) {
        delay(4_000)
        while (true) {
            watch.preload()
            delay(30 * 60_000L)
        }
    }
    LaunchedEffect(watch) {
        s.voice.watchSearch = { q -> watch.searchFor(q); s.screen = Screen.Watch; s.voice.close() }
    }

    DesignCanvas {
        when (val sc = s.screen) {
            Screen.Home -> HomeScreen(s)
            is Screen.Cook -> CookScreen(s, sc.recipeId)
            Screen.Drawer -> AppDrawer(s)
            Screen.Watch -> WatchScreen(s, watch)
            Screen.Live -> LiveScreen(s, watch)
            Screen.Recipes -> RecipesScreen(s)
            Screen.Player -> PlayerScreen(s)
        }
        if (s.showSettings) SettingsPanel(s)
        if (s.voice.open) SearchOverlay(s)
    }
}

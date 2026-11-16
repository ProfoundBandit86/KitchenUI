package com.kitchenui.launcher

import android.os.Bundle
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
import com.kitchenui.launcher.ui.SettingsPanel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var state: KitchenState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        state = KitchenState(applicationContext, lifecycleScope)
        state.refreshApps()
        setContent { KitchenApp(state) }
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
        state.playing = true   // another screen (player or app) is in front: don't install updates now
    }

    override fun onDestroy() {
        super.onDestroy()
        state.media.stop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // Pressing Home while the launcher is open returns to the home screen.
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        state.showSettings = false
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
            s.now = System.currentTimeMillis()
            s.timers.tick(s.now)
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

    BackHandler(enabled = true) {
        when {
            s.showSettings -> s.showSettings = false
            s.screen != Screen.Home -> s.screen = Screen.Home
        }
    }

    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val watch = remember { WatchModel(ctx, scope) }

    DesignCanvas {
        when (val sc = s.screen) {
            Screen.Home -> HomeScreen(s)
            is Screen.Cook -> CookScreen(s, sc.recipeId)
            Screen.Drawer -> AppDrawer(s)
            Screen.Watch -> WatchScreen(s, watch)
            Screen.Live -> LiveScreen(s, watch)
        }
        if (s.showSettings) SettingsPanel(s)
    }
}

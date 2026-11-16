package com.kitchenui.launcher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class Cook(val recipeId: String) : Screen
    data object Drawer : Screen
    data object Watch : Screen
    data object Live : Screen
}

class KitchenState(private val ctx: Context, private val scope: CoroutineScope) {
    private val prefs = Prefs(ctx)

    var now by mutableLongStateOf(System.currentTimeMillis())
    var settings by mutableStateOf(prefs.load())
        private set
    var screen by mutableStateOf<Screen>(Screen.Home)
    var showSettings by mutableStateOf(false)

    var weather by mutableStateOf<WeatherData?>(null)
    var weatherError by mutableStateOf<String?>(null)
    var events by mutableStateOf<List<CalEvent>>(emptyList())
    var calendarError by mutableStateOf<String?>(null)

    var paprikaEmail by mutableStateOf(prefs.paprikaEmail)
        private set
    var paprikaSignedIn by mutableStateOf(prefs.paprikaToken.isNotEmpty())
        private set
    var paprikaStatus by mutableStateOf("")

    var recipes by mutableStateOf(
        if (prefs.paprikaToken.isNotEmpty()) Paprika.loadCached(ctx).ifEmpty { RecipeRepo.loadLocal(ctx) } else RecipeRepo.loadLocal(ctx)
    )
    var tonightId by mutableStateOf(prefs.tonight)
        private set

    val shopping = mutableStateListOf<ShopItem>().apply { addAll(prefs.loadShop()) }
    val timers = TimerManager()

    var apps by mutableStateOf<List<AppEntry>>(emptyList())
    var update by mutableStateOf<UpdateState>(UpdateState.Idle)

    val tonight: Recipe?
        get() = recipes.firstOrNull { it.id == tonightId } ?: recipes.firstOrNull()

    fun recipe(id: String): Recipe? = recipes.firstOrNull { it.id == id }

    fun saveSettings(s: Settings) {
        val weatherChanged = s.lat != settings.lat || s.lon != settings.lon || s.fahrenheit != settings.fahrenheit
        val calChanged = s.icalUrl != settings.icalUrl
        settings = s
        prefs.save(s)
        if (weatherChanged) scope.launch { refreshWeather() }
        if (calChanged) scope.launch { refreshCalendar() }
    }

    fun setTonight(id: String) {
        tonightId = id
        prefs.tonight = id
    }

    fun pickAnother() {
        val pool = recipes.filter { it.id != tonight?.id }
        val dinners = pool.filter { it.category.equals("Dinner", true) }
        (dinners.ifEmpty { pool }).randomOrNull()?.let { setTonight(it.id) }
    }

    // Shopping list
    fun toggleShop(i: Int) {
        if (i !in shopping.indices) return
        shopping[i] = shopping[i].copy(done = !shopping[i].done)
        prefs.saveShop(shopping)
    }

    fun addShop(name: String) {
        if (name.isBlank()) return
        shopping.add(ShopItem(name.trim(), false))
        prefs.saveShop(shopping)
    }

    fun clearCheckedShop() {
        shopping.removeAll { it.done }
        prefs.saveShop(shopping)
    }

    // Apps
    fun refreshApps() {
        apps = Apps.load(ctx)
        if (!prefs.hasPinned() && apps.isNotEmpty()) {
            saveSettings(settings.copy(pinned = Apps.defaultPins(apps)))
        }
    }

    val pinnedApps: List<AppEntry>
        get() = settings.pinned.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }

    fun togglePin(app: AppEntry) {
        val p = settings.pinned
        saveSettings(settings.copy(pinned = if (app.pkg in p) p - app.pkg else (p + app.pkg).take(10)))
    }

    fun launch(app: AppEntry) = Apps.launch(ctx, app)

    // Network refreshes
    suspend fun refreshWeather() {
        val s = settings
        if (!s.hasLocation) { weather = null; weatherError = null; return }
        try {
            weather = Weather.fetch(s.lat, s.lon, s.fahrenheit)
            weatherError = null
        } catch (e: Exception) {
            weatherError = "Weather unavailable"
        }
    }

    suspend fun refreshCalendar() {
        val url = settings.icalUrl
        if (url.isBlank()) { events = emptyList(); calendarError = null; return }
        try {
            events = ICal.fetch(url)
            calendarError = null
        } catch (e: Exception) {
            calendarError = "Calendar unavailable"
        }
    }

    /** Paprika when signed in, otherwise the recipes.json in the GitHub repo. */
    suspend fun refreshRecipes() {
        val token = prefs.paprikaToken
        if (token.isEmpty()) {
            RecipeRepo.refresh(ctx)?.let { recipes = it }
            return
        }
        paprikaStatus = "Syncing with Paprika…"
        try {
            val list = Paprika.sync(ctx, token)
            if (list.isNotEmpty()) recipes = list
            paprikaStatus = "${list.size} recipes from Paprika"
            val today = java.time.LocalDate.now().toString()
            val planned = Paprika.todaysMeal(ctx)
            if (planned != null && prefs.plannedDay != today && recipes.any { it.id == planned }) {
                setTonight(planned)
                prefs.plannedDay = today
            }
        } catch (e: Paprika.AuthException) {
            paprikaStatus = e.message ?: "Paprika sign-in expired"
        } catch (e: Exception) {
            paprikaStatus = "Couldn't reach Paprika"
        }
    }

    suspend fun paprikaLogin(email: String, password: String): Boolean {
        paprikaStatus = "Signing in…"
        return try {
            val token = Paprika.login(email, password)
            prefs.paprikaToken = token
            prefs.paprikaEmail = email.trim()
            paprikaEmail = email.trim()
            paprikaSignedIn = true
            refreshRecipes()
            true
        } catch (e: Exception) {
            paprikaStatus = "Sign-in failed: ${e.message ?: "check your email and password"}"
            false
        }
    }

    fun paprikaSignOut() {
        prefs.paprikaToken = ""
        paprikaSignedIn = false
        paprikaStatus = ""
        Paprika.clear(ctx)
        recipes = RecipeRepo.loadLocal(ctx)
        scope.launch { refreshRecipes() }
    }

    fun syncRecipesNow() {
        scope.launch { refreshRecipes() }
    }

    /** Checks GitHub for a newer release and downloads it. With auto-update on, installs when the kitchen is idle. */
    suspend fun checkForUpdate(manual: Boolean) {
        if (!manual && !settings.autoUpdate) return
        val st = update
        if (st is UpdateState.Downloading) return
        if (st is UpdateState.Installing && !manual) return
        if (st is UpdateState.Ready) {
            if (!manual && isIdle()) install()
            return
        }
        update = UpdateState.Checking
        val info = try {
            Updater.check()
        } catch (e: Exception) {
            update = if (manual) UpdateState.Failed("Couldn't reach GitHub") else UpdateState.Idle
            return
        }
        if (info == null) {
            update = UpdateState.UpToDate
            return
        }
        try {
            update = UpdateState.Downloading(info, 0f)
            val file = Updater.download(ctx, info) { p -> update = UpdateState.Downloading(info, p) }
            update = UpdateState.Ready(info, file)
            if (settings.autoUpdate && isIdle()) install()
        } catch (e: Exception) {
            update = UpdateState.Failed("Download failed")
        }
    }

    private fun isIdle() = screen == Screen.Home && !timers.anyRunning && !showSettings && !playing

    /** True while the video player is open, so updates never interrupt a movie. */
    var playing = false

    fun install() {
        val (info, file) = when (val st = update) {
            is UpdateState.Ready -> st.info to st.file
            is UpdateState.Installing -> st.info to st.file
            else -> return
        }
        try {
            if (Updater.install(ctx, file)) {
                update = UpdateState.Installing(info, file)
            }
        } catch (e: Exception) {
            update = UpdateState.Failed("Install failed")
        }
    }

    fun refreshAll() {
        scope.launch { refreshWeather() }
        scope.launch { refreshCalendar() }
    }

    fun checkNow() {
        scope.launch { checkForUpdate(manual = true) }
    }

    suspend fun geocode(name: String): Place? = try {
        Weather.geocode(name)
    } catch (e: Exception) {
        null
    }
}

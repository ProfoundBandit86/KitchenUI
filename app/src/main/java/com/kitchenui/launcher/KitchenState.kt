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
    data object Recipes : Screen
    /** KitchenUI's own video player: split with the kitchen, or full screen. */
    data object Player : Screen
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

    var members by mutableStateOf(prefs.loadMembers())
        private set
    var mail by mutableStateOf(prefs.mail)
        private set
    /** Shown under the shopping list after "Send to …". */
    var mailStatus by mutableStateOf("")
    var mailSending by mutableStateOf(false)

    var apps by mutableStateOf<List<AppEntry>>(emptyList())
    var update by mutableStateOf<UpdateState>(UpdateState.Idle)

    val tonight: Recipe?
        get() = recipes.firstOrNull { it.id == tonightId } ?: recipes.firstOrNull()

    fun recipe(id: String): Recipe? = recipes.firstOrNull { it.id == id }

    /** Applies the theme setting; called on start, on save and every clock tick (for Auto). */
    fun applyTheme() {
        val hour = java.time.LocalTime.now().hour
        com.kitchenui.launcher.ui.K.light = when (settings.theme) {
            "light" -> true
            "auto" -> hour in 7 until 19
            else -> false
        }
    }

    fun saveSettings(s: Settings) {
        val weatherChanged = s.lat != settings.lat || s.lon != settings.lon || s.fahrenheit != settings.fahrenheit
        val calChanged = s.icalUrl != settings.icalUrl
        settings = s
        prefs.save(s)
        applyTheme()
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

    fun saveMembers(list: List<Member>) {
        val clean = list.filter { it.name.isNotBlank() && it.email.contains('@') }
            .map { Member(it.name.trim(), it.email.trim()) }
        members = clean
        prefs.saveMembers(clean)
    }

    fun saveMail(a: MailAccount) {
        // Gmail shows app passwords in groups of four; the spaces aren't part of it.
        val pw = if (a.smtpHost.contains("gmail", true)) a.password.replace(" ", "") else a.password
        mail = a.copy(email = a.email.trim(), user = a.user.trim(), password = pw, smtpHost = a.smtpHost.trim(), inHost = a.inHost.trim())
        prefs.mail = mail
    }

    /** Emails the items still to get to one household member. */
    fun sendList(to: Member) {
        val items = shopping.filter { !it.done }.map { it.name }
        if (items.isEmpty()) { mailStatus = "Nothing to get, so nothing was sent"; return }
        if (!mail.ready) { mailStatus = "Set up email in Settings › Household first"; return }
        mailSending = true
        mailStatus = "Sending to ${to.name}…"
        scope.launch {
            mailStatus = try {
                Mailer.sendList(mail, to, items)
                "Sent ${items.size} item${if (items.size == 1) "" else "s"} to ${to.name}"
            } catch (e: Exception) {
                "Couldn't send: ${e.message ?: "check the email settings"}"
            }
            mailSending = false
        }
    }

    suspend fun verifyMail(): String = try {
        Mailer.verify(mail)
    } catch (e: Exception) {
        "Couldn't sign in: ${e.message ?: "check the settings"}"
    }

    suspend fun sendTest(to: Member): String = try {
        Mailer.sendTest(mail, to)
        "Test sent to ${to.email}"
    } catch (e: Exception) {
        "Couldn't send: ${e.message ?: "check the settings"}"
    }

    // Apps
    fun refreshApps() {
        apps = Apps.load(ctx)
        if (!prefs.hasPinned() && apps.isNotEmpty()) {
            saveSettings(settings.copy(pinned = Apps.defaultPins(apps)))
            prefs.dockV2 = true
        } else if (!prefs.dockV2) {
            // Watch and Live TV became movable dock items.
            saveSettings(settings.copy(pinned = listOf(DOCK_WATCH, DOCK_LIVE) + settings.pinned.filter { !it.startsWith("@") }))
            prefs.dockV2 = true
        }
        if (!prefs.dockV3) {
            // The recipe book became its own dock item.
            val p = settings.pinned
            if (DOCK_RECIPES !in p) {
                val at = (p.indexOf(DOCK_LIVE) + 1).coerceAtLeast(0).coerceAtMost(p.size)
                saveSettings(settings.copy(pinned = (p.take(at) + DOCK_RECIPES + p.drop(at)).take(12)))
            }
            prefs.dockV3 = true
        }
        if (!prefs.dockV4) {
            // Voice search moved from the home screen into the dock, first in line.
            val p = settings.pinned
            if (DOCK_VOICE !in p) saveSettings(settings.copy(pinned = (listOf(DOCK_VOICE) + p).take(12)))
            prefs.dockV4 = true
        }
    }

    /** Dock items in order: DOCK_WATCH, DOCK_LIVE, DOCK_RECIPES or an installed app's package. */
    val dockItems: List<String>
        get() = settings.pinned.filter { it.startsWith("@") || apps.any { a -> a.pkg == it } }

    fun app(pkg: String): AppEntry? = apps.firstOrNull { it.pkg == pkg }

    var editDock by mutableStateOf(false)

    fun togglePin(id: String) {
        val p = settings.pinned
        saveSettings(settings.copy(pinned = if (id in p) p - id else (p + id).take(12)))
    }

    fun togglePin(app: AppEntry) = togglePin(app.pkg)

    var hiddenApps by mutableStateOf(prefs.hidden)
        private set

    fun setHidden(pkg: String, hide: Boolean) {
        hiddenApps = if (hide) hiddenApps + pkg else hiddenApps - pkg
        prefs.hidden = hiddenApps
    }

    /** Moves a dock item left (-1) or right (+1). */
    fun moveDock(id: String, delta: Int) {
        val visible = dockItems
        val i = visible.indexOf(id)
        val j = i + delta
        if (i < 0 || j !in visible.indices) return
        val other = visible[j]
        val all = settings.pinned.toMutableList()
        val a = all.indexOf(id)
        val b = all.indexOf(other)
        if (a < 0 || b < 0) return
        all[a] = other
        all[b] = id
        saveSettings(settings.copy(pinned = all))
    }

    val media = MediaState(ctx)
    val voice = VoiceSearch()

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

    private fun isIdle() = screen == Screen.Home && !timers.anyRunning && !showSettings && !playing &&
        !com.kitchenui.launcher.watch.VideoSession.active

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

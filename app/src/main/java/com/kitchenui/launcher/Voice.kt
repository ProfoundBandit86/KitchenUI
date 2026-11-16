package com.kitchenui.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Voice and typed search for the kitchen. Understands a few kitchen commands
 * (timers, the shopping list, opening apps, sending the list); anything else becomes a
 * search across recipes, apps, and movies and shows.
 */
class VoiceSearch {
    var open by mutableStateOf(false)
    var query by mutableStateOf("")
    /** What a command did ("Timer set for 10 minutes"), or a problem with the microphone. */
    var message by mutableStateOf("")
    /** Set by MainActivity: starts the system speech recognizer. Returns false when there isn't one. */
    var listen: (() -> Boolean)? = null
    /** Set by the app: opens Watch search for a title. */
    var watchSearch: ((String) -> Unit)? = null

    fun start() {
        open = true
        message = ""
        query = ""
        if (listen?.invoke() != true) {
            message = "Voice input isn't available on this device. Type instead, or install the Google app for speech."
        }
    }

    fun close() {
        open = false
        query = ""
        message = ""
    }
}

private val numberWords = mapOf(
    "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
    "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fifteen" to 15,
    "twenty" to 20, "twenty five" to 25, "thirty" to 30, "forty" to 40, "forty five" to 45, "fifty" to 50,
    "sixty" to 60, "ninety" to 90,
)

/** Minutes in "set a timer for 10 minutes", "timer 1 hour 30 minutes", "half an hour timer". Null if it isn't a timer. */
fun timerMinutes(q: String): Int? {
    val t = q.lowercase()
    if (!t.contains("timer")) return null
    if (t.contains("half an hour") || t.contains("half hour")) return 30
    var total = 0.0
    var found = false
    val num = "(\\d+(?:\\.\\d+)?|" + numberWords.keys.sortedByDescending { it.length }.joinToString("|") + ")"
    Regex("\\b$num\\s*(hours?|hrs?|minutes?|mins?|seconds?|secs?)").findAll(t).forEach { m ->
        val n = m.groupValues[1].toDoubleOrNull() ?: numberWords[m.groupValues[1]]?.toDouble() ?: return@forEach
        val unit = m.groupValues[2]
        total += when {
            unit.startsWith("h") -> n * 60
            unit.startsWith("s") -> n / 60
            else -> n
        }
        found = true
    }
    if (!found) Regex("timer (?:for )?(\\d+)").find(t)?.let { total = it.groupValues[1].toDouble(); found = true }
    return if (found) Math.max(1, Math.round(total).toInt()) else null
}

/** Runs a command if the words are one. Returns what happened, or null to show search results. */
fun KitchenState.runVoiceCommand(raw: String): String? {
    val q = raw.trim()
    val t = q.lowercase().trimEnd('.', '!', '?')

    timerMinutes(t)?.let { m ->
        val label = Regex("timer (?:for|called) (?:the )?([a-z ]+?)(?: for| \\d|$)").find(t)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() && numberWords[it] == null && !it.contains("minute") }
        timers.add(label?.replaceFirstChar { it.uppercase() } ?: "$m-minute timer", m)
        return "Timer set for ${formatMinutes(m)}"
    }

    Regex("^(?:add|put)\\s+(.+?)\\s+(?:to|on)\\s+(?:the\\s+|my\\s+)?(?:shopping|grocery|groceries)(?:\\s+list)?$").find(t)?.let { m ->
        val items = m.groupValues[1].split(Regex(",\\s*|\\s+and\\s+")).map { it.trim() }.filter { it.isNotBlank() }
        items.forEach { addShop(it.replaceFirstChar { c -> c.uppercase() }) }
        return "Added ${items.joinToString(", ")} to the shopping list"
    }

    Regex("^(?:send|email)\\s+(?:the\\s+)?(?:shopping\\s+)?list\\s+to\\s+(.+)$").find(t)?.let { m ->
        val name = m.groupValues[1].trim()
        val who = members.firstOrNull { it.name.equals(name, true) } ?: members.firstOrNull { it.name.lowercase().startsWith(name) }
            ?: return "No household member called ${name.replaceFirstChar { it.uppercase() }}. Add them in Settings › Household."
        sendList(who)
        return "Sending the shopping list to ${who.name}"
    }

    Regex("^(?:open|launch|start)\\s+(.+)$").find(t)?.let { m ->
        val name = m.groupValues[1].trim()
        when (name) {
            "recipes", "recipe book", "the recipe book" -> { screen = Screen.Recipes; return "Opening the recipe book" }
            "live tv", "tv" -> { screen = Screen.Live; return "Opening Live TV" }
            "watch", "movies" -> { screen = Screen.Watch; return "Opening Watch" }
            "settings" -> { showSettings = true; return "Opening Settings" }
        }
        val app = apps.firstOrNull { it.label.equals(name, true) } ?: apps.firstOrNull { it.label.lowercase().contains(name) }
        if (app != null) { launch(app); return "Opening ${app.label}" }
    }

    Regex("^(?:play|watch|put on)\\s+(.+)$").find(t)?.let { m ->
        val title = m.groupValues[1].trim()
        voice.watchSearch?.invoke(title)
        return "Searching movies and shows for \"$title\""
    }

    Regex("^(?:(?:how (?:do i|to) )?(?:cook|make)|recipe for|recipes? with)\\s+(.+)$").find(t)?.let { m ->
        voice.query = m.groupValues[1].trim()
        return null
    }
    return null
}

package com.kitchenui.launcher.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.fluxtv.app.Addons
import com.fluxtv.app.App
import com.fluxtv.app.Bridge
import com.fluxtv.app.Iptv
import com.fluxtv.app.PlayInfo
import com.fluxtv.app.RealDebrid
import com.fluxtv.app.Sources
import com.fluxtv.app.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One home row of posters. */
data class WRow(val title: String, val note: String, val items: List<JSONObject>, val wide: Boolean = false)

/** Pages stacked on top of the Watch home screen. */
sealed interface Page {
    data object Search : Page
    data class Detail(val type: String, val id: String, val preview: JSONObject) : Page
    data class Streams(val info: PlayInfo) : Page
}

/** State for the Watch tab: home rows, page stack and playback starts. Uses Flux TV's sources underneath. */
class WatchModel(ctx: Context, private val scope: CoroutineScope) {
    private val app = ctx.applicationContext

    init {
        App.init(app)
    }

    var rows by mutableStateOf<List<WRow>>(emptyList())
    var loading by mutableStateOf(false)
    val stack = mutableStateListOf<Page>()
    var showSources by mutableStateOf(false)
    var busy by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    private var loadedKey = ""

    val top: Page? get() = stack.lastOrNull()

    fun open(p: Page) { stack.add(p) }

    fun back(): Boolean {
        if (showSources) { showSources = false; refresh(); return true }
        if (stack.isEmpty()) return false
        stack.removeAt(stack.size - 1)
        return true
    }

    fun openItem(item: JSONObject) {
        val type = item.optString("type").ifBlank { "movie" }
        val id = item.optString("id")
        if (id.isBlank()) return
        open(Page.Detail(type, id, item))
    }

    /** Rebuilds the home rows when sources or progress changed (or when forced). */
    fun refresh(force: Boolean = false) {
        val key = Store.addonsVersionKey() + "|" + Store.changes() + "|" + Iptv.vodEnabled()
        if (!force && key == loadedKey && rows.isNotEmpty()) return
        loadedKey = key
        loading = true
        scope.launch {
            val local = withContext(Dispatchers.IO) { localRows() }
            rows = local
            val remote = withContext(Dispatchers.IO) { addonRows() }
            rows = local + remote
            loading = false
        }
    }

    private fun localRows(): List<WRow> {
        val out = mutableListOf<WRow>()
        Store.continueWatching().takeIf { it.isNotEmpty() }?.let { out.add(WRow("Continue watching", "", it, wide = true)) }
        Store.library().takeIf { it.isNotEmpty() }?.let { out.add(WRow("My watchlist", "", it)) }
        for (s in Store.servers()) {
            Sources.deck(s).takeIf { it.isNotEmpty() }?.let { out.add(WRow("On deck", s.name, it, wide = true)) }
            Sources.recent(s).takeIf { it.isNotEmpty() }?.let { out.add(WRow("Recently added", s.name, it)) }
        }
        return out
    }

    private fun addonRows(): List<WRow> {
        val out = mutableListOf<WRow>()
        for (r in Bridge.catalogRows()) out.add(WRow(rowTitle(r), r.note, r.items, r.wide))
        if (Iptv.vodEnabled()) {
            for (r in Iptv.vodRows("movie", 3) + Iptv.vodRows("series", 3)) {
                if (r.items.isNotEmpty()) out.add(WRow(r.title, r.note, r.items))
            }
        }
        return out
    }

    private fun rowTitle(r: Addons.Row): String {
        val t = when (r.type) {
            "movie" -> "Movies"
            "series" -> "Series"
            "" -> ""
            else -> r.type.replaceFirstChar { it.uppercase() }
        }
        return if (t.isEmpty() || r.title.contains(t, ignoreCase = true)) r.title else "${r.title} · $t"
    }

    suspend fun meta(type: String, id: String): JSONObject = withContext(Dispatchers.IO) { Addons.meta(type, id) }

    suspend fun streams(type: String, videoId: String): List<Addons.Stream> =
        withContext(Dispatchers.IO) { Addons.streams(type, videoId) }

    suspend fun search(q: String): List<WRow> = withContext(Dispatchers.IO) {
        val out = mutableListOf<WRow>()
        for (s in Store.servers()) {
            Sources.search(s, q).takeIf { it.isNotEmpty() }?.let { out.add(WRow(s.name, s.kindLabel(), it)) }
        }
        if (Iptv.vodEnabled()) Iptv.search(q).takeIf { it.isNotEmpty() }?.let { out.add(WRow("IPTV", "", it)) }
        for (r in Bridge.searchRows(q)) out.add(WRow(rowTitle(r), r.note, r.items))
        out
    }

    /** How a stream will play, for its label: "Your copy", "Real-Debrid ✓ ready", "Direct"… */
    fun via(st: Addons.Stream): String {
        val j = st.json
        val name = (j.optString("name") + " " + j.optString("title")).uppercase()
        val url = j.optString("url").lowercase()
        return when {
            st.library -> "Your copy"
            name.contains("[RD+]") -> "Real-Debrid ✓ ready"
            name.contains("[RD DOWNLOAD]") || name.contains("[RD ") -> "Real-Debrid · not ready"
            url.contains("/realdebrid/") || url.contains("real-debrid.com") || url.contains("rdeb.io") -> "Real-Debrid"
            j.optString("infoHash").isNotEmpty() && url.isEmpty() ->
                if (RealDebrid.isSignedIn()) "Real-Debrid" else "Needs Real-Debrid"
            j.optString("ytId").isNotEmpty() -> "YouTube"
            j.optString("externalUrl").isNotEmpty() -> "Opens another app"
            else -> "Direct"
        }
    }

    fun play(ctx: Context, info: PlayInfo, st: Addons.Stream, external: Boolean = false) {
        val j = st.json
        val url = j.optString("url")
        when {
            url.isNotEmpty() -> start(ctx, info, url, st.source, external)
            j.optString("infoHash").isNotEmpty() -> {
                if (!RealDebrid.isSignedIn()) {
                    message = "This source needs Real-Debrid. Sign in under Sources."
                    return
                }
                val hints = j.optJSONObject("behaviorHints")
                val filename = hints?.optString("filename") ?: ""
                val fileIdx = if (j.has("fileIdx")) j.optInt("fileIdx", -1) else -1
                busy = "Starting Real-Debrid…"
                scope.launch {
                    try {
                        val resolved = withContext(Dispatchers.IO) {
                            RealDebrid.resolve(j.optString("infoHash"), fileIdx, filename) { msg ->
                                App.MAIN.post { if (busy != null) busy = msg }
                            }
                        }
                        val wasCancelled = busy == null
                        busy = null
                        if (!wasCancelled) start(ctx, info, resolved, "Real-Debrid", external)
                    } catch (e: Exception) {
                        busy = null
                        message = App.message(e)
                    }
                }
            }
            j.optString("ytId").isNotEmpty() -> view(ctx, "https://www.youtube.com/watch?v=" + j.optString("ytId"))
            j.optString("externalUrl").isNotEmpty() -> view(ctx, j.optString("externalUrl"))
            else -> message = "This source can't be played"
        }
    }

    private fun start(ctx: Context, info: PlayInfo, url: String, source: String, external: Boolean) {
        if (external) {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").putExtra("title", info.title())
            runCatching { ctx.startActivity(Intent.createChooser(i, "Play with")) }
            return
        }
        VideoSession.play(ctx, info, url, source)
    }

    private fun view(ctx: Context, url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { message = "No app can open that link" }
    }
}

package com.kitchenui.launcher.watch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fluxtv.app.Addons
import com.fluxtv.app.App
import com.fluxtv.app.PlayInfo
import com.fluxtv.app.Store
import com.kitchenui.launcher.KitchenState
import com.kitchenui.launcher.Screen
import com.kitchenui.launcher.ui.Btn
import com.kitchenui.launcher.ui.Glyph
import com.kitchenui.launcher.ui.Icon
import com.kitchenui.launcher.ui.IconBtn
import com.kitchenui.launcher.ui.Input
import com.kitchenui.launcher.ui.K
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun WatchScreen(s: KitchenState, m: WatchModel) {
    LaunchedEffect(Unit) { m.refresh() }
    BackHandler(enabled = m.stack.isNotEmpty() || m.showSources) { m.back() }

    Box(Modifier.fillMaxSize()) {
        when (val p = m.top) {
            null -> WatchHome(s, m)
            Page.Search -> SearchPage(m)
            is Page.Detail -> DetailPage(m, p)
            is Page.Streams -> StreamsPage(m, p.info)
        }
        if (m.showSources) SourcesPanel(m)
        m.busy?.let { msg ->
            Overlay {
                Text(msg, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Btn("Cancel", { m.busy = null }, height = 50.dp)
            }
        }
        m.message?.let { msg ->
            Overlay {
                Text(msg, fontSize = 22.sp)
                Btn("OK", { m.message = null }, primary = true, height = 50.dp)
            }
        }
    }
}

@Composable
fun Overlay(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }, contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(640.dp).clip(RoundedCornerShape(24.dp)).background(K.Surface).border(1.dp, K.Border, RoundedCornerShape(24.dp)).padding(33.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 47.dp, end = 47.dp, top = 30.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Btn("Back", onBack, height = 50.dp, fontSize = 18, icon = Glyph.BACK)
        Text(title, fontFamily = K.Serif, fontSize = 38.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

// ------------------------------------------------------------------ home

@Composable
private fun WatchHome(s: KitchenState, m: WatchModel) {
    Column(Modifier.fillMaxSize()) {
        TopBar("Watch", { s.screen = Screen.Home }) {
            Btn("Search", { m.open(Page.Search) }, height = 50.dp, fontSize = 18, icon = Glyph.SEARCH)
            Btn("Live TV", { s.screen = Screen.Live }, height = 50.dp, fontSize = 18, icon = Glyph.TV)
            Btn("Sources", { m.showSources = true }, height = 50.dp, fontSize = 18, icon = Glyph.GEAR)
        }
        if (m.rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Text(if (m.loading) "Loading…" else "Nothing to show yet", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    if (!m.loading) {
                        Text("Add a Plex, Jellyfin or Emby server, or a Stremio add-on, under Sources.", color = K.Muted, fontSize = 20.sp)
                        Btn("Open Sources", { m.showSources = true }, primary = true, height = 54.dp)
                    }
                }
            }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            items(m.rows) { row -> PosterRow(row) { m.openItem(it) } }
        }
    }
}

@Composable
fun PosterRow(row: WRow, onOpen: (JSONObject) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.padding(horizontal = 47.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(row.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            if (row.note.isNotBlank()) Text(row.note, color = K.Muted, fontSize = 16.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 47.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(row.items) { item -> PosterCard(item, row.wide) { onOpen(item) } }
        }
    }
}

@Composable
fun PosterCard(item: JSONObject, wide: Boolean, onClick: () -> Unit) {
    val w = if (wide) 300.dp else 160.dp
    val h = if (wide) 169.dp else 240.dp
    val img = if (wide) {
        item.optString("still").ifBlank { item.optString("background") }.ifBlank { item.optString("poster") }
    } else item.optString("poster")
    val pos = item.optLong("pos")
    val dur = item.optLong("dur")
    val progress = when {
        dur > 0 -> pos.toFloat() / dur
        item.has("progress") -> item.optDouble("progress").toFloat()
        else -> 0f
    }
    val sub = item.optString("sub").ifBlank { item.optString("label") }.ifBlank { item.optString("releaseInfo") }
    Column(Modifier.width(w).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(w, h).clip(RoundedCornerShape(14.dp)).background(K.Surface2), contentAlignment = Alignment.BottomStart) {
            Text(item.optString("name"), color = K.Muted, fontSize = 16.sp, modifier = Modifier.align(Alignment.Center).padding(10.dp))
            if (img.isNotBlank()) AsyncImage(model = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (progress > 0.01f) {
                Box(Modifier.fillMaxWidth().height(6.dp).background(Color(0x99000000))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(K.Accent))
                }
            }
        }
        Text(item.optString("name"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub.isNotBlank()) Text(sub, color = K.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ------------------------------------------------------------------ search

@Composable
private fun SearchPage(m: WatchModel) {
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WRow>?>(null) }
    var searching by remember { mutableStateOf(false) }
    val go = {
        if (q.isNotBlank()) {
            searching = true
            scope.launch {
                results = runCatching { m.search(q.trim()) }.getOrDefault(emptyList())
                searching = false
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Search", { m.back() })
        Row(Modifier.padding(horizontal = 47.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Input(q, { q = it }, "Movies, shows, people…", Modifier.weight(1f), onDone = { go() })
            Btn("Search", { go() }, primary = true, height = 48.dp, icon = Glyph.SEARCH)
        }
        Spacer(Modifier.height(20.dp))
        val r = results
        when {
            searching -> Text("Searching…", color = K.Muted, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 47.dp))
            r != null && r.isEmpty() -> Text("No results", color = K.Muted, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 47.dp))
            r != null -> LazyColumn(contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
                items(r) { row -> PosterRow(row) { m.openItem(it) } }
            }
        }
    }
}

// ------------------------------------------------------------------ details

private data class Ep(val id: String, val season: Int, val episode: Int, val title: String, val thumb: String, val overview: String)

@Composable
private fun DetailPage(m: WatchModel, p: Page.Detail) {
    var meta by remember(p.id) { mutableStateOf<JSONObject?>(null) }
    var error by remember(p.id) { mutableStateOf<String?>(null) }
    var inLibrary by remember(p.id) { mutableStateOf(Store.inLibrary(p.id)) }
    LaunchedEffect(p.id) {
        try {
            meta = m.meta(p.type, p.id)
        } catch (e: Exception) {
            error = App.message(e)
        }
    }
    val mm = meta ?: p.preview
    val name = mm.optString("name")
    val background = mm.optString("background").ifBlank { p.preview.optString("background") }
    val poster = mm.optString("poster").ifBlank { p.preview.optString("poster") }
    val type = mm.optString("type").ifBlank { p.type }

    val episodes = remember(meta) {
        val v = meta?.optJSONArray("videos")
        if (v == null) emptyList() else (0 until v.length()).mapNotNull { i ->
            val o = v.optJSONObject(i) ?: return@mapNotNull null
            Ep(
                o.optString("id"), o.optInt("season", 0), o.optInt("episode", o.optInt("number", 0)),
                o.optString("title").ifBlank { o.optString("name") }, o.optString("thumbnail"),
                o.optString("overview").ifBlank { o.optString("description") },
            )
        }.sortedWith(compareBy({ if (it.season == 0) 9999 else it.season }, { it.episode }))
    }
    val seasons = episodes.map { it.season }.distinct()
    val resume = remember(p.id, meta) { Store.continueWatching().firstOrNull { it.optString("id") == p.id } }
    var season by remember(p.id, seasons.size) {
        mutableStateOf(resume?.optString("videoId")?.let { vid -> episodes.firstOrNull { it.id == vid }?.season } ?: seasons.firstOrNull() ?: 1)
    }

    fun info(videoId: String, label: String): PlayInfo =
        PlayInfo.of(type, p.id, videoId, name, poster, label).also { it.background = background }

    Box(Modifier.fillMaxSize()) {
        if (background.isNotBlank()) {
            AsyncImage(model = background, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(K.Bg, K.Bg.copy(alpha = 0.92f), K.Bg.copy(alpha = 0.55f)))))
        Column(Modifier.fillMaxSize()) {
            TopBar("", { m.back() }) {
                Btn(if (inLibrary) "In watchlist" else "Watchlist", {
                    meta?.let { inLibrary = Store.toggleLibrary(it) }
                }, height = 50.dp, fontSize = 18, icon = Glyph.HEART)
            }
            Row(Modifier.weight(1f).padding(horizontal = 47.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                Box(Modifier.width(260.dp).height(390.dp).clip(RoundedCornerShape(18.dp)).background(K.Surface2)) {
                    if (poster.isNotBlank()) AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(name, fontFamily = K.Serif, fontSize = 50.sp, lineHeight = 56.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val facts = listOfNotNull(
                        mm.optString("releaseInfo").ifBlank { null },
                        mm.optString("runtime").ifBlank { null },
                        mm.optString("imdbRating").ifBlank { null }?.let { "★ $it" },
                        mm.optJSONArray("genres")?.let { g -> (0 until minOf(3, g.length())).joinToString(", ") { g.optString(it) } }?.ifBlank { null },
                    )
                    if (facts.isNotEmpty()) Text(facts.joinToString("   ·   "), color = Color(0xFFC9CCD0), fontSize = 19.sp)
                    val desc = mm.optString("description")
                    if (desc.isNotBlank()) Text(desc, fontSize = 19.sp, lineHeight = 27.sp, color = Color(0xFFDADCDF), maxLines = if (episodes.isEmpty()) 7 else 3, overflow = TextOverflow.Ellipsis)
                    error?.let { Text(it, color = K.Accent, fontSize = 17.sp) }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (resume != null) {
                            val label = resume.optString("label")
                            Btn("Resume" + if (label.isNotBlank()) " $label" else "", {
                                m.open(Page.Streams(info(resume.optString("videoId"), label)))
                            }, primary = true, height = 58.dp, fontSize = 20, icon = Glyph.PLAY)
                        }
                        if (episodes.isEmpty() && meta != null) {
                            val vid = meta?.optJSONObject("behaviorHints")?.optString("defaultVideoId")?.ifBlank { null } ?: p.id
                            Btn(if (resume != null) "Play from start" else "Play", {
                                m.open(Page.Streams(info(vid, "")))
                            }, primary = resume == null, height = 58.dp, fontSize = 20, icon = Glyph.PLAY)
                        }
                    }

                    if (episodes.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(seasons) { sn ->
                                val sel = sn == season
                                val pill = RoundedCornerShape(20.dp)
                                Box(
                                    Modifier.height(40.dp).clip(pill)
                                        .then(if (sel) Modifier.background(K.Text) else Modifier.border(1.dp, K.Border2, pill))
                                        .clickable { season = sn }.padding(horizontal = 18.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(if (sn == 0) "Specials" else "Season $sn", color = if (sel) K.Bg else K.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
                            items(episodes.filter { it.season == season }) { ep ->
                                val prog = Store.progress(ep.id)
                                val frac = if (prog != null && prog.optLong("dur") > 0) prog.optLong("pos").toFloat() / prog.optLong("dur") else 0f
                                val label = "S${ep.season} E${ep.episode}"
                                Row(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Surface.copy(alpha = 0.85f))
                                        .clickable { m.open(Page.Streams(info(ep.id, label))) }.padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.width(176.dp).height(99.dp).clip(RoundedCornerShape(10.dp)).background(K.Surface2), contentAlignment = Alignment.BottomStart) {
                                        if (ep.thumb.isNotBlank()) AsyncImage(model = ep.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                        if (frac > 0.01f) Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(5.dp).background(K.Accent))
                                    }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("${ep.episode}. ${ep.title.ifBlank { "Episode ${ep.episode}" }}", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (ep.overview.isNotBlank()) Text(ep.overview, color = K.Muted, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    if (Store.isFinished(prog)) Icon(Glyph.CHECK, K.Sage, 26.dp)
                                    Icon(Glyph.PLAY, K.Text, 26.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ streams

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StreamsPage(m: WatchModel, info: PlayInfo) {
    val ctx = LocalContext.current
    var list by remember(info.videoId) { mutableStateOf<List<Addons.Stream>?>(null) }
    var error by remember(info.videoId) { mutableStateOf<String?>(null) }
    LaunchedEffect(info.videoId) {
        try {
            list = m.streams(info.type, info.videoId)
        } catch (e: Exception) {
            error = App.message(e)
            list = emptyList()
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(info.title(), { m.back() }) {
            Btn("Refresh", { Addons.forgetStreams(); list = null; error = null; m.back(); m.open(Page.Streams(info)) }, height = 50.dp, fontSize = 18)
        }
        Text(
            "Tap a source to play. Press and hold to open it in another player app.",
            color = K.Muted, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 47.dp, vertical = 6.dp),
        )
        val l = list
        when {
            l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Finding sources…", color = K.Muted, fontSize = 22.sp) }
            l.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error ?: "No sources found. Add a stream add-on (like Torrentio) or a media server under Sources.", color = K.Muted, fontSize = 20.sp)
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 47.dp, end = 47.dp, top = 8.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(l) { st ->
                    val via = m.via(st)
                    val j = st.json
                    val name = j.optString("name").replace('\n', ' ').trim()
                    val title = j.optString("title").ifBlank { j.optString("description") }.trim()
                    val good = st.library || via.contains("✓") || via == "Direct" || (via == "Real-Debrid")
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(K.Surface).border(1.dp, K.Border, RoundedCornerShape(16.dp))
                            .combinedClickable(onClick = { m.play(ctx, info, st) }, onLongClick = { m.play(ctx, info, st, external = true) })
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Column(Modifier.width(230.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(st.source, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(via, color = if (good) K.Sage else K.Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (name.isNotBlank()) Text(name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (title.isNotBlank()) Text(title, color = K.Muted, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        IconBtn(Glyph.PLAY, { m.play(ctx, info, st) }, primary = true, size = 50.dp)
                    }
                }
            }
        }
    }
}

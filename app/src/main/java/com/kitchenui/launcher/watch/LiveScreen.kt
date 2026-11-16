package com.kitchenui.launcher.watch

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.fluxtv.app.App
import com.fluxtv.app.Iptv
import com.kitchenui.launcher.KitchenState
import com.kitchenui.launcher.Screen
import com.kitchenui.launcher.ui.Btn
import com.kitchenui.launcher.ui.Glyph
import com.kitchenui.launcher.ui.K
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ALL = "\u0000all"

/** Live TV: IPTV providers' channel groups and channels, with what's on now. */
@Composable
fun LiveScreen(s: KitchenState, m: WatchModel) {
    val ctx = LocalContext.current
    var sources by remember { mutableStateOf<List<Iptv.Source>?>(null) }
    var srcId by remember { mutableStateOf("") }
    var cats by remember { mutableStateOf<List<Iptv.Category>>(emptyList()) }
    var cat by remember { mutableStateOf(ALL) }
    var channels by remember { mutableStateOf<List<Iptv.Channel>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = m.showSources) { m.back() }

    LaunchedEffect(m.showSources) {
        if (m.showSources) return@LaunchedEffect
        val list = withContext(Dispatchers.IO) { Iptv.sources() }
        sources = list
        if (list.none { it.id == srcId }) srcId = list.firstOrNull()?.id ?: ""
    }
    val src = sources?.firstOrNull { it.id == srcId }
    LaunchedEffect(srcId) {
        cats = emptyList()
        cat = ALL
        val sv = src ?: return@LaunchedEffect
        try {
            cats = withContext(Dispatchers.IO) { Iptv.liveCategories(sv) }
        } catch (e: Exception) {
            error = App.message(e)
        }
    }
    LaunchedEffect(srcId, cat) {
        channels = null
        val sv = src ?: return@LaunchedEffect
        try {
            channels = withContext(Dispatchers.IO) { Iptv.channelsIn(sv, if (cat == ALL) null else cat) }
            error = null
        } catch (e: Exception) {
            error = App.message(e)
            channels = emptyList()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Live TV", { s.screen = Screen.Home }) {
                Btn("Movies & shows", { s.screen = Screen.Watch }, height = 50.dp, fontSize = 18, icon = Glyph.PLAY)
                Btn("Sources", { m.showSources = true }, height = 50.dp, fontSize = 18, icon = Glyph.GEAR)
            }
            val list = sources
            if (list != null && list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text("No Live TV yet", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                        Text("Add an IPTV provider (Xtream Codes login or M3U playlist) under Sources.", color = K.Muted, fontSize = 20.sp)
                        Btn("Open Sources", { m.showSources = true }, primary = true, height = 54.dp)
                    }
                }
            } else {
                if (list != null && list.size > 1) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 47.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list) { sv -> Pill(sv.name, sv.id == srcId) { srcId = sv.id } }
                    }
                }
                Row(Modifier.fillMaxSize().padding(start = 47.dp, end = 47.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    LazyColumn(Modifier.width(300.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
                        item { CatRow("All channels", cat == ALL) { cat = ALL } }
                        items(cats) { c -> CatRow(c.name, cat == c.id) { cat = c.id } }
                    }
                    val ch = channels
                    when {
                        ch == null -> Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading channels…", color = K.Muted, fontSize = 20.sp) }
                        ch.isEmpty() -> Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) { Text(error ?: "No channels here", color = K.Muted, fontSize = 20.sp) }
                        else -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(270.dp),
                            modifier = Modifier.weight(1f).fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 30.dp),
                        ) {
                            itemsIndexed(ch) { i, c ->
                                ChannelCard(src!!, c) {
                                    ctx.startActivity(
                                        Intent(ctx, PlayerActivity::class.java)
                                            .putExtra("live", true)
                                            .putExtra("live_source", src.id)
                                            .putExtra("live_cat", if (cat == ALL) null else cat)
                                            .putExtra("live_index", i)
                                            .putExtra("url", Iptv.liveUrl(src, c, false))
                                            .putExtra("pi_name", c.name)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (m.showSources) SourcesPanel(m)
    }
}

@Composable
private fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val pill = RoundedCornerShape(20.dp)
    Box(
        Modifier.height(40.dp).clip(pill)
            .then(if (selected) Modifier.background(K.Text) else Modifier.border(1.dp, K.Border2, pill))
            .clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) K.Bg else K.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun CatRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) K.Surface2 else K.Bg)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Text(name, color = if (selected) K.Accent else K.Text, fontSize = 18.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChannelCard(src: Iptv.Source, c: Iptv.Channel, onClick: () -> Unit) {
    var now by remember(c.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(src.id, c.id) {
        now = withContext(Dispatchers.IO) {
            runCatching {
                val p = Iptv.nowNext(src, c).firstOrNull() ?: return@runCatching null
                val f = SimpleDateFormat("h:mm", Locale.getDefault())
                "${p.title} · until ${f.format(Date(p.end))}"
            }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(16.dp)).background(K.Surface).border(1.dp, K.Border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(K.Surface2), contentAlignment = Alignment.Center) {
            Text(c.num, color = K.Muted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (c.logo.isNotBlank()) AsyncImage(model = c.logo, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(c.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(now ?: c.num.let { if (it.isBlank()) "" else "Channel $it" }, color = K.Muted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

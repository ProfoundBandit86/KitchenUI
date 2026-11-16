package com.kitchenui.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kitchenui.launcher.DOCK_LIVE
import com.kitchenui.launcher.DOCK_RECIPES
import com.kitchenui.launcher.DOCK_WATCH
import com.kitchenui.launcher.KitchenState
import com.kitchenui.launcher.Screen
import com.kitchenui.launcher.UpdateState
import com.kitchenui.launcher.Weather
import com.kitchenui.launcher.formatClock
import com.kitchenui.launcher.formatMinutes
import com.kitchenui.launcher.watch.VideoSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

fun clockText(t: ZonedDateTime, use24h: Boolean): Pair<String, String> =
    if (use24h) t.format(DateTimeFormatter.ofPattern("HH:mm")) to ""
    else t.format(DateTimeFormatter.ofPattern("h:mm")) to (if (t.hour < 12) "AM" else "PM")

@Composable
fun HomeScreen(s: KitchenState) {
    Column(
        Modifier.fillMaxSize().padding(start = 47.dp, end = 47.dp, top = 37.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(27.dp),
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            LeftColumn(s, Modifier.width(417.dp).fillMaxHeight())
            CenterColumn(s, Modifier.weight(1f).fillMaxHeight())
            RightColumn(s, Modifier.width(367.dp).fillMaxHeight())
        }
        Dock(s)
    }
}

@Composable
private fun LeftColumn(s: KitchenState, modifier: Modifier) {
    val t = Instant.ofEpochMilli(s.now).atZone(ZoneId.systemDefault())
    val (time, ampm) = clockText(t, s.settings.use24h)
    val greeting = when (t.hour) {
        in 0..4 -> "Good night"
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.padding(start = 3.dp)) {
            Text(greeting, color = K.Muted, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(time, fontFamily = K.Serif, fontSize = 124.sp, lineHeight = 124.sp)
                if (ampm.isNotEmpty()) {
                    Text(ampm, color = K.Muted, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 20.dp))
                }
            }
            Text(t.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), fontSize = 25.sp, fontWeight = FontWeight.Medium)
        }
        WeatherCard(s)
        TimersCard(s, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun WeatherCard(s: KitchenState) {
    Card(Modifier.fillMaxWidth().clickable { s.showSettings = true }, padding = PaddingValues(horizontal = 23.dp, vertical = 20.dp), spacing = 16.dp) {
        val w = s.weather
        if (w == null) {
            Text(
                if (!s.settings.hasLocation) "Tap to set your city for the weather" else (s.weatherError ?: "Loading weather…"),
                color = K.Muted, fontSize = 20.sp,
            )
            return@Card
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            WeatherIcon(Weather.sky(w.code), 70.dp)
            Column {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${w.temp}°", fontSize = 53.sp, lineHeight = 53.sp, fontWeight = FontWeight.SemiBold)
                    Text("H ${w.hi}° · L ${w.lo}°", color = K.Muted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
                Text(
                    listOf(Weather.describe(w.code), s.settings.city).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(K.Border))
        Row(Modifier.fillMaxWidth()) {
            w.days.forEach { d ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(d.date.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.getDefault()), color = K.Muted, fontSize = 15.sp)
                    WeatherIcon(Weather.sky(d.code), 28.dp)
                    Text("${d.hi}°", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
internal fun TimersCard(s: KitchenState, modifier: Modifier) {
    val tm = s.timers
    Card(modifier, padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp), spacing = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Kitchen timers", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val n = tm.timers.count { it.running }
            Text(if (tm.timers.isEmpty()) "Tap a preset" else "$n running", color = K.Muted, fontSize = 15.sp)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tm.timers, key = { it.id }) { t ->
                val shape = RoundedCornerShape(15.dp)
                Row(
                    Modifier.fillMaxWidth().clip(shape)
                        .background(if (t.done) Color(0xFF3A2A1E) else K.Surface2)
                        .border(2.dp, if (t.done) K.Accent else Color.Transparent, shape)
                        .clickable { tm.silence() }
                        .padding(start = 13.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (t.done) "${t.label} · done" else t.label, color = K.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatClock(t.remainingMs), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Btn("+1m", { tm.addMinute(t) }, height = 40.dp, fontSize = 15)
                    if (!t.done) IconBtn(if (t.running) Glyph.PAUSE else Glyph.PLAY, { tm.toggle(t) }, primary = true, size = 42.dp)
                    IconBtn(Glyph.CLOSE, { tm.remove(t) }, size = 38.dp)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 10, 15, 30).forEach { m ->
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(K.Surface2).border(1.dp, K.Border2, RoundedCornerShape(12.dp))
                        .clickable { tm.add("$m-minute timer", m) },
                    contentAlignment = Alignment.Center,
                ) { Text("$m min", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun CenterColumn(s: KitchenState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(23.dp)) {
        val r = s.tonight
        val shape = RoundedCornerShape(23.dp)
        Row(Modifier.fillMaxWidth().height(350.dp).clip(shape).background(K.Surface).border(1.dp, K.Border, shape)) {
            Box(
                Modifier.width(300.dp).fillMaxHeight().background(if (r != null) Color(r.color) else K.Surface2),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Glyph.POT, Color.White.copy(alpha = 0.55f), 110.dp)
                if (r?.photoUrl != null) {
                    AsyncImage(model = r.photoUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 33.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("TONIGHT'S DINNER", color = K.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                Text(r?.name ?: "Add recipes to recipes.json", fontFamily = K.Serif, fontSize = 46.sp, lineHeight = 50.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (r != null) {
                    Text(
                        "Prep ${formatMinutes(r.prepMin)}   ·   Cook ${formatMinutes(r.cookMin)}   ·   Serves ${r.serves}   ·   ${r.ingredients.size} ingredients",
                        color = Color(0xFFC9CCD0), fontSize = 18.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (r != null) Btn("Start cooking", { s.screen = Screen.Cook(r.id) }, primary = true, height = 54.dp, fontSize = 20, icon = Glyph.PLAY)
                    Btn("Pick another", { s.pickAnother() }, height = 54.dp, fontSize = 18)
                }
            }
        }

        if (VideoSession.active || s.media.active) {
            NowPlayingCard(s, Modifier.weight(1f).fillMaxWidth())
        } else {
            RecipeShelf(s, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** The recipe book strip on the home screen; it gives way to the now playing card while media plays. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecipeShelf(s: KitchenState, modifier: Modifier) {
    var tab by remember { mutableStateOf("All") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(15.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Recipe book", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).clickable { s.screen = Screen.Recipes })
            val cats = listOf("All") + s.recipes.map { it.category }.distinct().take(3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                cats.forEach { c ->
                    val sel = c == tab
                    val pill = RoundedCornerShape(19.dp)
                    Box(
                        Modifier.height(37.dp).clip(pill)
                            .then(if (sel) Modifier.background(K.Text) else Modifier.border(1.dp, K.Border2, pill))
                            .clickable { tab = c }
                            .padding(horizontal = 17.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(c, color = if (sel) K.Bg else K.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        val shown = s.recipes.filter { tab == "All" || it.category == tab }
        LazyRow(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(17.dp)) {
            items(shown, key = { it.id }) { rec ->
                val cs = RoundedCornerShape(18.dp)
                Column(
                    Modifier.width(212.dp).fillMaxHeight().clip(cs).background(K.Surface).border(1.dp, K.Border, cs)
                        .combinedClickable(
                            onClick = { s.screen = Screen.Cook(rec.id) },
                            onLongClick = { s.setTonight(rec.id) },
                        ),
                ) {
                    Box(Modifier.fillMaxWidth().weight(1f).background(Color(rec.color)), contentAlignment = Alignment.Center) {
                        if (rec.photoUrl != null) {
                            AsyncImage(model = rec.photoUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                    Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(rec.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${formatMinutes(rec.totalMin)} · Serves ${rec.serves}", color = K.Muted, fontSize = 15.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun RightColumn(s: KitchenState, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        CalendarCard(s)
        ShoppingCard(s, Modifier.weight(1f).fillMaxWidth())
    }
}

@Composable
private fun CalendarCard(s: KitchenState) {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(s.now).atZone(zone).toLocalDate()
    Card(Modifier.fillMaxWidth(), padding = PaddingValues(20.dp), spacing = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(today.format(DateTimeFormatter.ofPattern("MMMM yyyy")), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(Glyph.CALENDAR, K.Muted, 22.dp)
        }
        val sunday = today.minusDays((today.dayOfWeek.value % 7).toLong())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 0..6) {
                val d = sunday.plusDays(i.toLong())
                val isToday = d == today
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(if (isToday) K.Accent else Color.Transparent).padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val fg = if (isToday) K.Bg else K.Text
                    Text(d.dayOfWeek.getDisplayName(JTextStyle.NARROW, Locale.getDefault()), color = fg.copy(alpha = 0.8f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(d.dayOfMonth.toString(), color = fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        val events = s.events.take(4)
        when {
            s.settings.icalUrl.isBlank() -> Text("Add your Google Calendar link in Settings", color = K.Muted, fontSize = 16.sp, modifier = Modifier.clickable { s.showSettings = true })
            s.calendarError != null && events.isEmpty() -> Text(s.calendarError ?: "", color = K.Muted, fontSize = 16.sp)
            events.isEmpty() -> Text("Nothing coming up", color = K.Muted, fontSize = 16.sp)
        }
        val colors = listOf(K.Accent, K.Blue, K.Sage, K.Blue)
        events.forEachIndexed { i, e ->
            Column {
                Box(Modifier.fillMaxWidth().height(1.dp).background(K.Border))
                Row(Modifier.padding(top = 8.dp, bottom = 2.dp).height(46.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.width(5.dp).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(colors[i % colors.size]))
                    Column {
                        Text(eventWhen(e.start.toLocalDate(), e.start, e.allDay, today, s.settings.use24h), color = K.Muted, fontSize = 14.sp)
                        Text(e.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun eventWhen(date: LocalDate, start: ZonedDateTime, allDay: Boolean, today: LocalDate, use24h: Boolean): String {
    val day = when (date) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> date.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.getDefault()) + " " + date.dayOfMonth
    }
    if (allDay) return "$day · All day"
    val (t, ap) = clockText(start, use24h)
    return "$day · $t${if (ap.isEmpty()) "" else " $ap"}"
}

@Composable
internal fun ShoppingCard(s: KitchenState, modifier: Modifier) {
    var draft by remember { mutableStateOf("") }
    Card(modifier, padding = PaddingValues(horizontal = 20.dp, vertical = 18.dp), spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Shopping list", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            val left = s.shopping.count { !it.done }
            if (s.shopping.any { it.done }) {
                Text("Clear done", color = K.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { s.clearCheckedShop() }.padding(4.dp))
            } else {
                Text("$left to get", color = K.Muted, fontSize = 15.sp)
            }
        }
        SendToRow(s)
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(s.shopping) { i, item ->
                Row(
                    Modifier.fillMaxWidth().clickable { s.toggleShop(i) }.padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CheckBox(item.done)
                    Text(
                        item.name, fontSize = 18.sp,
                        color = if (item.done) K.Dim else K.Text,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                    )
                }
            }
        }
        if (s.mailStatus.isNotBlank()) {
            Text(s.mailStatus, color = if (s.mailStatus.startsWith("Couldn't")) Color(0xFFE58F8F) else K.Sage, fontSize = 14.sp, maxLines = 2)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val add = { s.addShop(draft); draft = "" }
            Box(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp)).background(K.Surface2).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (draft.isEmpty()) Text("Add item…", color = K.Dim, fontSize = 16.sp)
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = TextStyle(color = K.Text, fontSize = 16.sp),
                    cursorBrush = SolidColor(K.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            IconBtn(Glyph.PLUS, { add() }, primary = true, size = 40.dp)
        }
    }
}

/** "Send to Tom" buttons, one per household member. */
@Composable
private fun SendToRow(s: KitchenState) {
    if (s.members.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(s.members) { m ->
            val shape = RoundedCornerShape(12.dp)
            Row(
                Modifier.height(40.dp).clip(shape).background(K.Surface2).border(1.dp, K.Border2, shape)
                    .clickable(enabled = !s.mailSending) { s.sendList(m) }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Glyph.MAIL, K.Accent, 20.dp)
                Text("Send to ${m.name}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Dock(s: KitchenState) {
    val shape = RoundedCornerShape(23.dp)
    val updating = s.update is UpdateState.Ready || s.update is UpdateState.Installing || s.update is UpdateState.Downloading
    // Now playing lives in the middle of the home screen; the dock only asks for access.
    val showMedia = !s.editDock && !s.media.granted
    // Room for 9 tiles; the update badge takes 3 and the media player 4.
    val room = if (s.editDock) 12 else 9 - (if (updating) 3 else 0) - (if (showMedia) 4 else 0)
    val items = s.dockItems
    Row(
        Modifier.fillMaxWidth().clip(shape).background(K.Surface).border(1.dp, if (s.editDock) K.Accent else K.Border, shape)
            .padding(horizontal = 23.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (s.editDock) 6.dp else 16.dp),
    ) {
        items.take(room).forEachIndexed { i, id ->
            val label: String
            val icon: @Composable () -> Unit
            val open: () -> Unit
            when (id) {
                DOCK_WATCH -> {
                    label = "Watch"; open = { s.screen = Screen.Watch }
                    icon = { Box(Modifier.size(70.dp).clip(RoundedCornerShape(18.dp)).background(K.Accent), contentAlignment = Alignment.Center) { Icon(Glyph.PLAY, K.Bg, 34.dp) } }
                }
                DOCK_LIVE -> {
                    label = "Live TV"; open = { s.screen = Screen.Live }
                    icon = { Box(Modifier.size(70.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF3D5A80)), contentAlignment = Alignment.Center) { Icon(Glyph.TV, K.Text, 36.dp) } }
                }
                DOCK_RECIPES -> {
                    label = "Recipe book"; open = { s.screen = Screen.Recipes }
                    icon = { Box(Modifier.size(70.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF6B4C7A)), contentAlignment = Alignment.Center) { Icon(Glyph.BOOK, K.Text, 36.dp) } }
                }
                else -> {
                    val app = s.app(id) ?: return@forEachIndexed
                    label = app.label; open = { s.launch(app) }
                    icon = { Image(app.icon, contentDescription = null, modifier = Modifier.size(70.dp).clip(RoundedCornerShape(18.dp))) }
                }
            }
            if (s.editDock) {
                EditTile(label, icon, first = i == 0, last = i == minOf(items.size, room) - 1,
                    onLeft = { s.moveDock(id, -1) }, onRight = { s.moveDock(id, 1) }, onRemove = { s.togglePin(id) })
            } else {
                DockTile(label, open, onLongClick = { s.editDock = true }, icon = icon)
            }
        }
        Spacer(Modifier.weight(1f))
        if (s.editDock) {
            Text("Use the arrows to move · X removes · add apps from All apps", color = K.Muted, fontSize = 15.sp)
            Btn("Done", { s.editDock = false }, primary = true, height = 54.dp)
            return@Row
        }
        if (showMedia) MediaWidget(s)
        UpdateBadge(s)
        DockTile("Settings", { s.showSettings = true }, onLongClick = { s.editDock = true }) {
            Box(Modifier.size(70.dp).clip(RoundedCornerShape(18.dp)).background(K.Border), contentAlignment = Alignment.Center) { Icon(Glyph.GEAR, K.Text, 34.dp) }
        }
        DockTile("All apps", { s.screen = Screen.Drawer }, onLongClick = { s.editDock = true }) {
            Box(Modifier.size(70.dp).clip(RoundedCornerShape(18.dp)).background(K.Border).border(2.dp, Color(0xFF4A4F55), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) { Icon(Glyph.GRID, K.Text, 30.dp) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DockTile(label: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, icon: @Composable () -> Unit) {
    Column(
        Modifier.width(93.dp).clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        icon()
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A dock tile in edit mode: arrows to move it, X to take it off the dock. */
@Composable
private fun EditTile(
    label: String,
    icon: @Composable () -> Unit,
    first: Boolean,
    last: Boolean,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier.width(104.dp).clip(RoundedCornerShape(14.dp)).background(K.Surface2).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            Box(Modifier.padding(6.dp)) { icon() }
            Box(
                Modifier.align(Alignment.TopEnd).size(30.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFF8A4F5E)).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) { Icon(Glyph.CLOSE, Color.White, 16.dp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconBtn(Glyph.BACK, onLeft, size = 40.dp, modifier = Modifier.then(if (first) Modifier.background(Color.Transparent) else Modifier))
            IconBtn(Glyph.NEXT, onRight, size = 40.dp)
        }
        Text(label, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Asks for notification access so the home screen can show what other apps are playing. */
@Composable
private fun MediaWidget(s: KitchenState) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.width(420.dp).height(96.dp).clip(shape).background(K.Surface2).border(1.dp, K.Border, shape).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text("Media controls", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("Allow notification access to see what's playing", color = K.Muted, fontSize = 14.sp, maxLines = 2)
        }
        Btn("Allow", { s.media.requestAccess() }, primary = true, height = 46.dp, fontSize = 16)
    }
}

@Composable
private fun UpdateBadge(s: KitchenState) {
    val (text, action) = when (val u = s.update) {
        is UpdateState.Ready -> "Update ${u.info.versionName} ready · Install" to { s.install() }
        is UpdateState.Installing -> "Installing ${u.info.versionName}… tap to retry" to { s.install() }
        is UpdateState.Downloading -> "Downloading update ${(u.progress * 100).toInt()}%" to {}
        else -> return
    }
    Row(
        Modifier.height(54.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF3A2A1E)).border(2.dp, K.Accent, RoundedCornerShape(16.dp))
            .clickable(onClick = action).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Glyph.DOWNLOAD, K.Accent, 22.dp)
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

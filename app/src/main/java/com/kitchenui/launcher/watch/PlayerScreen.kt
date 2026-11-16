package com.kitchenui.launcher.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.videolan.libvlc.util.VLCVideoLayout
import kotlinx.coroutines.delay
import com.kitchenui.launcher.KitchenState
import com.kitchenui.launcher.Screen
import com.kitchenui.launcher.Weather
import com.kitchenui.launcher.formatMinutes
import com.kitchenui.launcher.ui.Btn
import com.kitchenui.launcher.ui.Card
import com.kitchenui.launcher.ui.Glyph
import com.kitchenui.launcher.ui.Icon
import com.kitchenui.launcher.ui.K
import com.kitchenui.launcher.ui.Progress
import com.kitchenui.launcher.ui.RecipeBookCompact
import com.kitchenui.launcher.ui.ShoppingCard
import com.kitchenui.launcher.ui.TimersCard
import com.kitchenui.launcher.ui.Transport
import com.kitchenui.launcher.ui.WeatherIcon
import com.kitchenui.launcher.ui.clockText
import java.time.Instant
import java.time.ZoneId

/** Shows the session's video (VLC draws into this view). */
@Composable
fun VideoSurface(modifier: Modifier) {
    AndroidView(
        factory = { c -> VLCVideoLayout(c).also { VideoSession.attach(it) } },
        update = { VideoSession.attach(it) },
        onRelease = { VideoSession.detach(it) },
        modifier = modifier,
    )
}

/** KitchenUI's video player: split with the kitchen (home or recipe book), or full screen. */
@Composable
fun PlayerScreen(s: KitchenState) {
    val v = VideoSession
    if (!v.active) {
        LaunchedEffect(Unit) { s.screen = Screen.Home }
        return
    }
    if (v.fullScreen) FullPlayer() else SplitPlayer(s)
}

@Composable
private fun FullPlayer() {
    val v = VideoSession
    val ctx = LocalContext.current
    var bar by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf<String?>(null) }
    // The controls fade after 4 seconds of no touches while playing.
    LaunchedEffect(bar, touched, v.isPlaying, picker) {
        if (bar && v.isPlaying && picker == null) { delay(4000); bar = false }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { bar = !bar; touched++ })
        if (v.buffering && v.error == null) {
            Text("Loading…", color = K.Text, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
        }
        if (bar || v.error != null) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 28.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Btn("Split screen", { v.fullScreen = false }, height = 50.dp, fontSize = 17, icon = Glyph.SPLIT)
                Column(Modifier.weight(1f)) {
                    Text(v.title, color = K.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (v.subtitle.isNotBlank()) Text(v.subtitle, color = K.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!v.live) Btn("Other player", { v.openExternal(ctx) }, height = 50.dp, fontSize = 17)
                Btn("Stop", { v.close() }, height = 50.dp, fontSize = 17, icon = Glyph.CLOSE)
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 32.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!v.live) Progress()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Transport(72.dp)
                    Spacer(Modifier.weight(1f))
                    Btn("Audio", { picker = "audio"; touched++ }, height = 52.dp, fontSize = 17)
                    Btn("Subtitles", { picker = "subs"; touched++ }, height = 52.dp, fontSize = 17)
                }
            }
        }
        picker?.let { TrackPicker(it) { picker = null } }
        ErrorBox()
    }
}

/** Lists audio or subtitle tracks; English audio and subtitles off are the defaults. */
@Composable
fun TrackPicker(kind: String, onClose: () -> Unit) {
    val v = VideoSession
    @Suppress("UNUSED_VARIABLE") val version = v.tracksVersion   // refresh when tracks change
    val tracks = if (kind == "audio") v.audioTracks() else v.subtitleTracks()
    val current = if (kind == "audio") v.audioTrack else v.subtitleTrack
    Box(
        Modifier.fillMaxSize().background(Color(0xAA000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            Modifier.width(520.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            radius = 24.dp, padding = PaddingValues(24.dp), spacing = 8.dp,
        ) {
            Text(if (kind == "audio") "Audio" else "Subtitles", fontFamily = K.Serif, fontSize = 30.sp)
            if (tracks.isEmpty()) Text("No other tracks in this video", color = K.Muted, fontSize = 17.sp)
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(tracks) { t ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (t.id == current) K.Surface2 else Color.Transparent)
                            .clickable {
                                if (kind == "audio") v.pickAudio(t.id) else v.pickSubtitles(t.id)
                                onClose()
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(24.dp)) { if (t.id == current) Icon(Glyph.CHECK, K.Accent, 24.dp) }
                        Text(t.name, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Btn("Close", onClose, Modifier.fillMaxWidth(), height = 50.dp)
        }
    }
}

@Composable
private fun ErrorBox() {
    val v = VideoSession
    val ctx = LocalContext.current
    val msg = v.error ?: return
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(22.dp)).background(K.Surface).padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(msg, color = K.Text, fontSize = 19.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Btn("Close", { v.close() }, primary = true, height = 48.dp)
                if (v.live) Btn("Next channel", { v.zap(1) }, height = 48.dp)
                else Btn("Another player", { v.openExternal(ctx) }, height = 48.dp)
            }
        }
    }
}

@Composable
private fun SplitPlayer(s: KitchenState) {
    val v = VideoSession
    val ctx = LocalContext.current
    var pane by rememberSaveable { mutableStateOf("home") }
    var picker by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 26.dp), horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        // Video on the left at a true 16:9
        Column(Modifier.width(1004.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.width(1004.dp).height(565.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
                VideoSurface(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().clickable { v.fullScreen = true })
                if (v.buffering && v.error == null) Text("Loading…", color = K.Text, fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
                Row(Modifier.align(Alignment.TopEnd).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OverlayBtn("Full screen", Glyph.EXPAND) { v.fullScreen = true }
                    OverlayBtn(null, Glyph.CLOSE) { v.close() }
                }
                ErrorBox()
            }
            Card(Modifier.fillMaxWidth().weight(1f), padding = PaddingValues(horizontal = 26.dp, vertical = 18.dp), spacing = 10.dp) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(v.title, fontFamily = K.Serif, fontSize = 30.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (v.subtitle.isNotBlank()) Text(v.subtitle, color = Color(0xFFC9CCD0), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!v.live) Progress()
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Transport(68.dp)
                    Spacer(Modifier.weight(1f))
                    Btn("Audio", { picker = "audio" }, height = 52.dp, fontSize = 16)
                    Btn("Subtitles", { picker = "subs" }, height = 52.dp, fontSize = 16)
                    Btn("Home", { s.screen = Screen.Home }, height = 52.dp, fontSize = 16)
                }
            }
        }

        // The kitchen on the right
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(K.Surface).padding(4.dp),
                ) {
                    Seg("Home", pane == "home", Modifier.weight(1f)) { pane = "home" }
                    Seg("Recipe book", pane == "recipes", Modifier.weight(1f)) { pane = "recipes" }
                }
                val t = Instant.ofEpochMilli(s.now).atZone(ZoneId.systemDefault())
                val (time, ap) = clockText(t, s.settings.use24h)
                Text(time, fontFamily = K.Serif, fontSize = 38.sp)
                if (ap.isNotEmpty()) Text(ap, color = K.Muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            if (pane == "home") HomePane(s) else RecipeBookCompact(s, Modifier.weight(1f).fillMaxWidth())
        }
    }
    picker?.let { TrackPicker(it) { picker = null } }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.HomePane(s: KitchenState) {
    val w = s.weather
    Card(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (w != null) {
                WeatherIcon(Weather.sky(w.code), 44.dp)
                Text("${w.temp}°", fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
                Column(Modifier.weight(1f)) {
                    Text(Weather.describe(w.code), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("H ${w.hi}° · L ${w.lo}°", color = K.Muted, fontSize = 15.sp)
                }
            } else {
                Text(s.weatherError ?: "Weather loading…", color = K.Muted, fontSize = 16.sp, modifier = Modifier.weight(1f))
            }
        }
    }
    TimersCard(s, Modifier.fillMaxWidth().height(232.dp))
    val r = s.tonight
    if (r != null) {
        Card(Modifier.fillMaxWidth(), padding = PaddingValues(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Color(r.color)), contentAlignment = Alignment.Center) {
                    if (r.photoUrl != null) coil.compose.AsyncImage(model = r.photoUrl, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(Glyph.POT, Color.White.copy(alpha = 0.6f), 36.dp)
                }
                Column(Modifier.weight(1f)) {
                    Text("TONIGHT'S DINNER", color = K.Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
                    Text(r.name, fontFamily = K.Serif, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${formatMinutes(r.totalMin)} · Serves ${r.serves}", color = K.Muted, fontSize = 14.sp)
                }
                Btn("Cook", { s.screen = Screen.Cook(r.id) }, primary = true, height = 48.dp, fontSize = 16)
            }
        }
    }
    ShoppingCard(s, Modifier.fillMaxWidth().weight(1f))
}

@Composable
private fun Seg(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(46.dp).clip(RoundedCornerShape(12.dp)).background(if (on) K.Text else Color.Transparent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (on) K.Bg else Color(0xFFC9CCD0), fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun OverlayBtn(text: String?, glyph: Glyph, onClick: () -> Unit) {
    Row(
        Modifier.height(46.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xB3121416)).clickable(onClick = onClick)
            .padding(horizontal = if (text == null) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(glyph, K.Text, 20.dp)
        if (text != null) Text(text, color = K.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

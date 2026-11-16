package com.kitchenui.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxtv.app.Ui
import com.kitchenui.launcher.KitchenState
import com.kitchenui.launcher.Screen
import com.kitchenui.launcher.watch.VideoSession
import com.kitchenui.launcher.watch.VideoSurface

/**
 * Takes the recipe book's place on the home screen while something plays:
 * KitchenUI's own video (with a live picture) or music/video from another app.
 */
@Composable
fun NowPlayingCard(s: KitchenState, modifier: Modifier) {
    val v = VideoSession
    Card(modifier, radius = 23.dp, padding = PaddingValues(horizontal = 24.dp, vertical = 20.dp), spacing = 14.dp) {
        if (v.active) InAppVideo(s) else OtherApp(s)
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(K.Accent))
        Text(text.uppercase(), color = K.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InAppVideo(s: KitchenState) {
    val v = VideoSession
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("Now playing · ${if (v.live) "Live TV" else "KitchenUI"}", Modifier.weight(1f))
        Btn("Open player", { v.fullScreen = false; s.screen = Screen.Player }, height = 44.dp, fontSize = 16, icon = Glyph.SPLIT)
        IconBtn(Glyph.CLOSE, { v.close() }, size = 44.dp)
    }
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        // A live, tappable picture of the video
        Box(Modifier.width(250.dp).height(141.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
            VideoSurface(Modifier.fillMaxSize(), controls = false)
            Box(Modifier.fillMaxSize().clickable { v.fullScreen = false; s.screen = Screen.Player })
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(v.title, fontFamily = K.Serif, fontSize = 32.sp, lineHeight = 36.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (v.subtitle.isNotBlank()) Text(v.subtitle, color = Color(0xFFC9CCD0), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Transport(56.dp)
        }
    }
    if (!v.live) Progress()
}

/** Back 10 s / play-pause / forward 30 s, or channel down / play-pause / channel up for live TV. */
@Composable
fun Transport(big: Dp) {
    val v = VideoSession
    val small = big * 0.8f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (v.live) {
            Btn("Ch −", { v.zap(-1) }, height = small, fontSize = 16)
            IconBtn(if (v.isPlaying) Glyph.PAUSE else Glyph.PLAY, { v.togglePlay() }, primary = true, size = big)
            Btn("Ch +", { v.zap(1) }, height = small, fontSize = 16)
        } else {
            RoundBtn(Glyph.REWIND, small) { v.seekBy(-10_000) }
            IconBtn(if (v.isPlaying) Glyph.PAUSE else Glyph.PLAY, { v.togglePlay() }, primary = true, size = big)
            RoundBtn(Glyph.FORWARD, small) { v.seekBy(30_000) }
        }
    }
}

@Composable
fun RoundBtn(glyph: Glyph, size: Dp, onClick: () -> Unit) {
    val shape = RoundedCornerShape(size / 2)
    Box(
        Modifier.size(size).clip(shape).border(1.dp, K.Border2, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(glyph, K.Text, size * 0.46f) }
}

/** Progress bar you can tap to jump, with elapsed and remaining time. */
@Composable
fun Progress(modifier: Modifier = Modifier) {
    val v = VideoSession
    val frac = if (v.duration > 0) (v.position.toFloat() / v.duration).coerceIn(0f, 1f) else 0f
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(Ui.time(v.position), color = K.Muted, fontSize = 15.sp)
        Box(
            Modifier.weight(1f).height(28.dp).pointerInput(Unit) {
                detectTapGestures { o -> VideoSession.seekTo(o.x / size.width) }
            },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(K.Border))
            Box(Modifier.fillMaxWidth(frac).height(8.dp).clip(RoundedCornerShape(4.dp)).background(K.Accent))
        }
        Text(if (v.duration > 0) "−" + Ui.time(v.duration - v.position) else "", color = K.Muted, fontSize = 15.sp)
    }
}

@Composable
private fun OtherApp(s: KitchenState) {
    val m = s.media
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("Now playing · ${m.appName}", Modifier.weight(1f))
        Btn("Open ${m.appName.ifBlank { "app" }}", { m.openApp() }, height = 44.dp, fontSize = 16)
    }
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(
            Modifier.size(170.dp).clip(RoundedCornerShape(16.dp)).background(K.Border).clickable { m.openApp() },
            contentAlignment = Alignment.Center,
        ) {
            val art = m.art
            if (art != null) Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Glyph.PLAY, K.Muted, 48.dp)
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(m.title.ifBlank { "Something's playing" }, fontFamily = K.Serif, fontSize = 34.sp, lineHeight = 38.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (m.artist.isNotBlank()) Text(m.artist, color = Color(0xFFC9CCD0), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                RoundBtn(Glyph.SKIP_BACK, 52.dp) { m.previous() }
                IconBtn(if (m.playing) Glyph.PAUSE else Glyph.PLAY, { m.playPause() }, primary = true, size = 68.dp)
                RoundBtn(Glyph.SKIP_NEXT, 52.dp) { m.next() }
            }
        }
    }
}

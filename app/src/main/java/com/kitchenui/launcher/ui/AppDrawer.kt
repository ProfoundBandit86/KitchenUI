package com.kitchenui.launcher.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kitchenui.launcher.AppEntry
import com.kitchenui.launcher.DOCK_LIVE
import com.kitchenui.launcher.DOCK_RECIPES
import com.kitchenui.launcher.DOCK_VOICE
import com.kitchenui.launcher.DOCK_WATCH
import com.kitchenui.launcher.KitchenState
import com.kitchenui.launcher.Screen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawer(s: KitchenState) {
    var showHidden by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    val hiddenCount = s.apps.count { it.pkg in s.hiddenApps }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 47.dp, vertical = 37.dp), verticalArrangement = Arrangement.spacedBy(23.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Btn("Home", { s.screen = Screen.Home }, height = 50.dp, fontSize = 18, icon = Glyph.BACK)
                Text(if (showHidden) "Hidden apps" else "All apps", fontFamily = K.Serif, fontSize = 40.sp, modifier = Modifier.weight(1f))
                if (!showHidden) {
                    if (DOCK_WATCH !in s.settings.pinned) Btn("Add Watch to dock", { s.togglePin(DOCK_WATCH) }, height = 46.dp, fontSize = 16)
                    if (DOCK_LIVE !in s.settings.pinned) Btn("Add Live TV to dock", { s.togglePin(DOCK_LIVE) }, height = 46.dp, fontSize = 16)
                    if (DOCK_VOICE !in s.settings.pinned) Btn("Add Search to dock", { s.togglePin(DOCK_VOICE) }, height = 46.dp, fontSize = 16)
                    if (DOCK_RECIPES !in s.settings.pinned) Btn("Add Recipe book to dock", { s.togglePin(DOCK_RECIPES) }, height = 46.dp, fontSize = 16)
                }
                if (hiddenCount > 0 || showHidden) {
                    Btn(if (showHidden) "Back to all apps" else "Hidden apps ($hiddenCount)", { showHidden = !showHidden }, height = 46.dp, fontSize = 16)
                }
            }
            Text(
                if (showHidden) "Press and hold an app to show it in All apps again" else "Press and hold an app to add it to the dock or hide it",
                color = K.Muted, fontSize = 17.sp,
            )
            val shown = s.apps.filter { (it.pkg in s.hiddenApps) == showHidden }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(if (showHidden) "No hidden apps" else "No apps", color = K.Muted, fontSize = 20.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    items(shown, key = { it.pkg + "/" + it.cls }) { app ->
                        val pinned = app.pkg in s.settings.pinned
                        Column(
                            Modifier.clip(RoundedCornerShape(17.dp))
                                .combinedClickable(
                                    onClick = { s.launch(app); s.screen = Screen.Home },
                                    onLongClick = { menuFor = app },
                                )
                                .padding(10.dp)
                                .alpha(if (showHidden) 0.6f else 1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box {
                                Image(app.icon, contentDescription = null, modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)))
                                if (pinned) {
                                    Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) { Icon(Glyph.STAR, K.Accent, 22.dp) }
                                }
                            }
                            Text(
                                app.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        menuFor?.let { app -> AppMenu(s, app) { menuFor = null } }
    }
}

/** Press-and-hold menu: dock, hide or unhide, app info. */
@Composable
private fun AppMenu(s: KitchenState, app: AppEntry, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val hidden = app.pkg in s.hiddenApps
    val pinned = app.pkg in s.settings.pinned
    Box(
        Modifier.fillMaxSize().background(Color(0xAA000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            Modifier.width(460.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            radius = 24.dp, padding = PaddingValues(26.dp), spacing = 12.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(app.icon, contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
                Text(app.label, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (!hidden) {
                Btn(if (pinned) "Remove from dock" else "Add to dock", { s.togglePin(app); onClose() }, Modifier.fillMaxWidth(), height = 54.dp)
            }
            Btn(
                if (hidden) "Show in All apps" else "Hide from All apps",
                {
                    s.setHidden(app.pkg, !hidden)
                    if (!hidden && pinned) s.togglePin(app)   // hidden apps leave the dock too
                    onClose()
                },
                Modifier.fillMaxWidth(), height = 54.dp,
            )
            Btn("App info", {
                runCatching {
                    ctx.startActivity(
                        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                onClose()
            }, Modifier.fillMaxWidth(), height = 54.dp)
            Btn("Cancel", onClose, Modifier.fillMaxWidth(), primary = true, height = 54.dp)
        }
    }
}

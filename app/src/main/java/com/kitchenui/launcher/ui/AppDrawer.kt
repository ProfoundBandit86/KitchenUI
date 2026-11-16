package com.kitchenui.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kitchenui.launcher.KitchenState
import com.kitchenui.launcher.Screen

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawer(s: KitchenState) {
    Column(Modifier.fillMaxSize().padding(horizontal = 47.dp, vertical = 37.dp), verticalArrangement = Arrangement.spacedBy(23.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Btn("Home", { s.screen = Screen.Home }, height = 50.dp, fontSize = 18, icon = Glyph.BACK)
            Text("All apps", fontFamily = K.Serif, fontSize = 40.sp, modifier = Modifier.weight(1f))
            Text("Press and hold an app to add it to or remove it from the dock", color = K.Muted, fontSize = 17.sp)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            items(s.apps, key = { it.pkg + "/" + it.cls }) { app ->
                val pinned = app.pkg in s.settings.pinned
                Column(
                    Modifier.clip(RoundedCornerShape(17.dp))
                        .combinedClickable(
                            onClick = { s.launch(app); s.screen = Screen.Home },
                            onLongClick = { s.togglePin(app) },
                        )
                        .padding(10.dp),
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

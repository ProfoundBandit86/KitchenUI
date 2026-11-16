package com.kitchenui.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kitchenui.launcher.KitchenState
import com.kitchenui.launcher.Screen
import com.kitchenui.launcher.formatMinutes
import com.kitchenui.launcher.runVoiceCommand

/** The "Search or ask…" button on the home screen. */
@Composable
fun VoiceButton(s: KitchenState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier.height(60.dp).clip(shape).background(K.Surface).border(1.dp, K.Border, shape)
            .clickable { s.voice.start() }.padding(start = 8.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(K.Accent), contentAlignment = Alignment.Center) {
            Icon(Glyph.MIC, K.OnAccent, 24.dp)
        }
        Text("Search or ask…", color = K.Text2, fontSize = 19.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Results for a spoken or typed search: a command result, or recipes, apps, and movies and shows. */
@Composable
fun SearchOverlay(s: KitchenState) {
    val v = s.voice
    fun submit() {
        val q = v.query.trim()
        if (q.isEmpty()) return
        val done = s.runVoiceCommand(q)
        if (done != null) v.message = done
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { v.close() },
        contentAlignment = Alignment.TopCenter,
    ) {
        Card(
            Modifier.padding(top = 60.dp).width(1180.dp).heightIn(max = 760.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            radius = 28.dp, padding = PaddingValues(30.dp), spacing = 20.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)).background(K.Accent).clickable { v.start() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Glyph.MIC, K.OnAccent, 32.dp) }
                Input(v.query, { v.query = it; v.message = "" }, "Search recipes, apps, movies… or say a command", Modifier.weight(1f), onDone = { submit() })
                Btn("Go", { submit() }, primary = true, height = 48.dp)
                IconBtn(Glyph.CLOSE, { v.close() }, size = 48.dp)
            }
            if (v.message.isNotBlank()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Surface2).padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Glyph.CHECK, K.Accent, 24.dp)
                    Text(v.message, fontSize = 20.sp, modifier = Modifier.weight(1f))
                    Btn("Done", { v.close() }, height = 44.dp, fontSize = 16)
                }
            }
            val q = v.query.trim().lowercase()
            if (q.isEmpty()) {
                Text("Try saying", color = K.Muted, fontSize = 16.sp)
                listOf(
                    "Set a timer for 10 minutes",
                    "Add milk and eggs to the shopping list",
                    "Send the list to ${s.members.firstOrNull()?.name ?: "Sam"}",
                    "Chicken soup",
                    "Play The Great British Bake Off",
                    "Open YouTube",
                ).forEach { ex ->
                    Text(
                        "“$ex”", fontSize = 19.sp, color = K.Text2,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { v.query = ex; submit() }.padding(vertical = 4.dp, horizontal = 6.dp),
                    )
                }
                return@Card
            }
            if (v.message.isNotBlank()) return@Card

            val recipes = s.recipes.filter { r ->
                r.name.lowercase().contains(q) || r.category.lowercase() == q || r.ingredients.any { it.name.lowercase().contains(q) }
            }.take(12)
            val apps = s.apps.filter { it.label.lowercase().contains(q) && it.pkg !in s.hiddenApps }.take(8)

            Btn("Search movies and shows for “${v.query.trim()}”", { s.voice.watchSearch?.invoke(v.query.trim()); v.close() },
                Modifier.fillMaxWidth(), height = 54.dp, fontSize = 18, icon = Glyph.PLAY)

            if (recipes.isNotEmpty()) {
                SectionTitle("Recipes")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(recipes, key = { it.id }) { r ->
                        Column(
                            Modifier.width(200.dp).clip(RoundedCornerShape(16.dp)).background(K.Surface2)
                                .clickable { s.screen = Screen.Cook(r.id); v.close() },
                        ) {
                            Box(Modifier.fillMaxWidth().height(120.dp).background(Color(r.color)), contentAlignment = Alignment.Center) {
                                Icon(Glyph.POT, Color.White.copy(alpha = 0.5f), 44.dp)
                                if (r.photoUrl != null) AsyncImage(model = r.photoUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            }
                            Column(Modifier.padding(12.dp)) {
                                Text(r.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(formatMinutes(r.totalMin), color = K.Muted, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
            if (apps.isNotEmpty()) {
                SectionTitle("Apps")
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    apps.forEach { app ->
                        Column(
                            Modifier.width(110.dp).clip(RoundedCornerShape(14.dp)).clickable { s.launch(app); v.close() }.padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Image(app.icon, contentDescription = null, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
                            Text(app.label, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            if (recipes.isEmpty() && apps.isEmpty()) {
                Text("No recipes or apps match. Try movies and shows above.", color = K.Muted, fontSize = 17.sp)
            }
        }
    }
}

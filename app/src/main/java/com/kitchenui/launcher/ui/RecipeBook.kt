package com.kitchenui.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kitchenui.launcher.KitchenState
import com.kitchenui.launcher.Recipe
import com.kitchenui.launcher.Screen
import com.kitchenui.launcher.formatMinutes

private fun matches(r: Recipe, q: String, cat: String): Boolean {
    if (cat != "All" && r.category != cat) return false
    if (q.isBlank()) return true
    val t = q.trim().lowercase()
    return r.name.lowercase().contains(t) || r.category.lowercase().contains(t) ||
        r.ingredients.any { it.name.lowercase().contains(t) }
}

private fun syncLine(s: KitchenState): String = when {
    s.paprikaSignedIn && s.paprikaStatus.isNotBlank() -> s.paprikaStatus
    s.paprikaSignedIn -> "${s.recipes.size} recipes from Paprika"
    else -> "${s.recipes.size} recipes · sign in to Paprika in Settings to sync yours"
}

@Composable
private fun Chips(s: KitchenState, cat: String, onPick: (String) -> Unit) {
    val cats = listOf("All") + s.recipes.map { it.category }.filter { it.isNotBlank() }.distinct().sorted()
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(cats) { c ->
            val sel = c == cat
            val pill = RoundedCornerShape(20.dp)
            Box(
                Modifier.height(40.dp).clip(pill)
                    .then(if (sel) Modifier.background(K.Text) else Modifier.border(1.dp, K.Border2, pill))
                    .clickable { onPick(c) }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) { Text(c, color = if (sel) K.Bg else K.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun Thumb(r: Recipe, modifier: Modifier, iconSize: Int) {
    Box(modifier.background(Color(r.color)), contentAlignment = Alignment.Center) {
        Icon(Glyph.POT, Color.White.copy(alpha = 0.5f), iconSize.dp)
        if (r.photoUrl != null) AsyncImage(model = r.photoUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** The full-screen recipe book, filled from Paprika when signed in. Tap to cook, hold to make it tonight's dinner. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecipesScreen(s: KitchenState) {
    var q by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf("All") }
    Column(
        Modifier.fillMaxSize().padding(start = 47.dp, end = 47.dp, top = 30.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Btn("Home", { s.screen = Screen.Home }, height = 52.dp, fontSize = 17, icon = Glyph.BACK)
            Column(Modifier.weight(1f)) {
                Text("Recipe book", fontFamily = K.Serif, fontSize = 46.sp, lineHeight = 48.sp)
                Text(syncLine(s), color = if (s.paprikaSignedIn) K.Sage else K.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Input(q, { q = it }, "Search recipes or ingredients", Modifier.width(440.dp))
            if (s.paprikaSignedIn) Btn("Sync now", { s.syncRecipesNow() }, primary = true, height = 50.dp, fontSize = 17)
        }
        Chips(s, cat) { cat = it }
        val shown = s.recipes.filter { matches(it, q, cat) }
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(if (s.recipes.isEmpty()) "No recipes yet" else "No recipes match", color = K.Muted, fontSize = 20.sp)
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 30.dp),
        ) {
            items(shown, key = { it.id }) { r ->
                val cs = RoundedCornerShape(18.dp)
                Column(
                    Modifier.fillMaxWidth().clip(cs).background(K.Surface).border(1.dp, K.Border, cs)
                        .combinedClickable(onClick = { s.screen = Screen.Cook(r.id) }, onLongClick = { s.setTonight(r.id) }),
                ) {
                    Box {
                        Thumb(r, Modifier.fillMaxWidth().height(170.dp), 64)
                        if (r.id == s.tonight?.id) {
                            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp).clip(RoundedCornerShape(18.dp)).background(K.Bg.copy(alpha = 0.75f)), contentAlignment = Alignment.Center) {
                                Icon(Glyph.STAR, K.Accent, 22.dp)
                            }
                        }
                    }
                    Column(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(r.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.height(44.dp))
                        Text("${formatMinutes(r.totalMin)} · Serves ${r.serves}", color = K.Muted, fontSize = 15.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The recipe book in the split-screen side pane: a list, and the recipe itself inline so you can cook beside the video. */
@Composable
fun RecipeBookCompact(s: KitchenState, modifier: Modifier) {
    var q by rememberSaveable { mutableStateOf("") }
    var cat by rememberSaveable { mutableStateOf("All") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = openId?.let { s.recipe(it) }
    if (open != null) {
        RecipeInline(s, open, modifier) { openId = null }
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Input(q, { q = it }, "Search ${s.recipes.size} recipes", Modifier.fillMaxWidth())
        Chips(s, cat) { cat = it }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.recipes.filter { matches(it, q, cat) }, key = { it.id }) { r ->
                val cs = RoundedCornerShape(16.dp)
                Row(
                    Modifier.fillMaxWidth().clip(cs).background(K.Surface).border(1.dp, K.Border, cs).clickable { openId = r.id }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Thumb(r, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)), 28)
                    Column(Modifier.weight(1f)) {
                        Text(r.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${formatMinutes(r.totalMin)} · Serves ${r.serves}", color = K.Muted, fontSize = 14.sp)
                    }
                    Icon(Glyph.NEXT, K.Muted, 20.dp)
                }
            }
        }
        Text(syncLine(s), color = K.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RecipeInline(s: KitchenState, r: Recipe, modifier: Modifier, onBack: () -> Unit) {
    Card(modifier, padding = PaddingValues(18.dp), spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Btn("Recipes", onBack, height = 44.dp, fontSize = 15, icon = Glyph.BACK)
            Column(Modifier.weight(1f))  { }
            Btn("Cook mode", { s.screen = Screen.Cook(r.id) }, primary = true, height = 44.dp, fontSize = 15)
        }
        Text(r.name, fontFamily = K.Serif, fontSize = 28.sp, lineHeight = 32.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("Prep ${formatMinutes(r.prepMin)} · Cook ${formatMinutes(r.cookMin)} · Serves ${r.serves}", color = K.Muted, fontSize = 15.sp)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { SectionTitle("Ingredients") }
            items(r.ingredients) { ing ->
                Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(ing.qty, color = K.Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(96.dp))
                    Text(ing.name, fontSize = 16.sp)
                }
            }
            item {
                Btn("Add ingredients to shopping list", { r.ingredients.forEach { s.addShop(it.name) } }, height = 42.dp, fontSize = 15, icon = Glyph.PLUS,
                    modifier = Modifier.padding(vertical = 6.dp))
            }
            item { SectionTitle("Steps") }
            itemsIndexed(r.steps) { i, st ->
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(K.Surface2), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(st.text, fontSize = 17.sp, lineHeight = 23.sp)
                }
            }
        }
    }
}

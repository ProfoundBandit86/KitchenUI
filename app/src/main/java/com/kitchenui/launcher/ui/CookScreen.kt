package com.kitchenui.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kitchenui.launcher.KitchenState
import com.kitchenui.launcher.Screen
import com.kitchenui.launcher.formatClock
import com.kitchenui.launcher.formatMinutes
import java.time.Instant
import java.time.ZoneId

@Composable
fun CookScreen(s: KitchenState, recipeId: String) {
    val r = s.recipe(recipeId)
    if (r == null || r.steps.isEmpty()) {
        LaunchedEffect(recipeId) { s.screen = Screen.Home }
        return
    }
    var step by remember(recipeId) { mutableIntStateOf(0) }
    val checked = remember(recipeId) { mutableStateListOf<Int>() }
    val st = r.steps[step.coerceIn(0, r.steps.size - 1)]
    val last = step >= r.steps.size - 1
    val timerKey = "${r.id}#$step"
    val timer = s.timers.byKey(timerKey)
    val now = Instant.ofEpochMilli(s.now).atZone(ZoneId.systemDefault())
    val (clock, ampm) = clockText(now, s.settings.use24h)

    Column(
        Modifier.fillMaxSize().padding(start = 47.dp, end = 47.dp, top = 33.dp, bottom = 37.dp),
        verticalArrangement = Arrangement.spacedBy(27.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(23.dp)) {
            Btn("Home", { s.screen = Screen.Home }, height = 50.dp, fontSize = 18, icon = Glyph.BACK)
            Column(Modifier.weight(1f)) {
                Text("COOK MODE", color = K.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                Text(r.name, fontFamily = K.Serif, fontSize = 37.sp, lineHeight = 42.sp, maxLines = 1)
            }
            Text(if (ampm.isEmpty()) clock else "$clock $ampm", fontFamily = K.Serif, fontSize = 47.sp)
        }

        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            // Ingredients
            Card(Modifier.width(383.dp).fillMaxHeight(), padding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 12.dp), spacing = 6.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ingredients", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("Serves ${r.serves}", color = K.Muted, fontSize = 15.sp)
                }
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(r.ingredients) { i, ing ->
                        val done = i in checked
                        Column(Modifier.fillMaxWidth().clickable { if (done) checked.remove(i) else checked.add(i) }) {
                            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CheckBox(done, 27.dp)
                                Text(
                                    if (ing.qty.isBlank()) ing.name else "${ing.qty}  ${ing.name}",
                                    fontSize = 18.sp,
                                    color = if (done) K.Dim else K.Text,
                                    textDecoration = if (done) TextDecoration.LineThrough else null,
                                )
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(K.Border))
                        }
                    }
                }
            }

            // Current step
            Card(Modifier.weight(1f).fillMaxHeight(), radius = 23.dp, padding = PaddingValues(horizontal = 40.dp, vertical = 33.dp), spacing = 23.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                    Text("Step ${step + 1} of ${r.steps.size}", color = K.Muted, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        r.steps.indices.forEach { k ->
                            Box(
                                Modifier.weight(1f).height(18.dp).clickable { step = k },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(if (k <= step) K.Accent else K.Border2))
                            }
                        }
                    }
                }
                Text(st.text, fontSize = 42.sp, lineHeight = 56.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (st.tip.isNotBlank()) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(K.Surface2).padding(horizontal = 18.dp, vertical = 15.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Tip", color = K.Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(st.tip, color = K.Text2, fontSize = 18.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    Btn("Back", { if (step > 0) step-- }, height = 70.dp, fontSize = 22, icon = Glyph.BACK)
                    Btn(
                        if (last) "Finish" else "Next step",
                        { if (last) s.screen = Screen.Home else step++ },
                        modifier = Modifier.weight(1f), primary = true, height = 70.dp, fontSize = 23,
                    )
                }
            }

            // Step timer + facts
            Column(Modifier.width(350.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Card(Modifier.fillMaxWidth(), padding = PaddingValues(23.dp), spacing = 15.dp) {
                    Text("Step timer", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    val total = timer?.totalMs ?: (st.timerMin * 60_000L)
                    val remaining = timer?.remainingMs ?: total
                    val frac = if (total > 0) 1f - remaining.toFloat() / total else 0f
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(210.dp)) {
                            val sw = 14.dp.toPx()
                            drawArc(K.Border, 0f, 360f, false, style = Stroke(sw))
                            drawArc(K.Accent, -90f, 360f * frac, false, style = Stroke(sw, cap = StrokeCap.Round))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (total > 0) formatClock(remaining) else "–:––", fontSize = 50.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                when {
                                    total <= 0L -> "No timer for this step"
                                    timer == null -> "Ready"
                                    timer.done -> "Time's up"
                                    timer.running -> "Running"
                                    else -> "Paused"
                                },
                                color = K.Muted, fontSize = 16.sp,
                            )
                        }
                    }
                    if (st.timerMin > 0) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Btn(
                                when {
                                    timer == null -> "Start ${st.timerMin} min"
                                    timer.done -> "Done"
                                    timer.running -> "Pause"
                                    else -> "Resume"
                                },
                                {
                                    when {
                                        timer == null -> s.timers.add("${r.name.take(18)} · step ${step + 1}", st.timerMin, timerKey)
                                        timer.done -> s.timers.silence()
                                        else -> s.timers.toggle(timer)
                                    }
                                },
                                modifier = Modifier.weight(1f), primary = true, height = 53.dp, fontSize = 18,
                            )
                            if (timer != null) Btn("Reset", { s.timers.reset(timer) }, height = 53.dp, fontSize = 18)
                        }
                    }
                }
                Card(Modifier.fillMaxWidth().weight(1f), padding = PaddingValues(horizontal = 23.dp, vertical = 20.dp), spacing = 4.dp) {
                    Text("Good to know", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
                    val remainingMin = r.steps.drop(step).sumOf { it.timerMin }
                    val ready = now.plusMinutes(remainingMin.toLong())
                    val (rc, rap) = clockText(ready, s.settings.use24h)
                    val rows = r.facts + listOf(
                        "Total time" to formatMinutes(r.totalMin),
                        "Ready about" to (if (rap.isEmpty()) rc else "$rc $rap"),
                    )
                    rows.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text(k, color = K.Muted, fontSize = 17.sp, modifier = Modifier.width(120.dp))
                            Text(v, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

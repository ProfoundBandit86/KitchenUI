package com.kitchenui.launcher.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kitchenui.launcher.BuildConfig
import com.kitchenui.launcher.KitchenState
import com.kitchenui.launcher.UpdateState
import kotlinx.coroutines.launch

@Composable
fun SettingsPanel(s: KitchenState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(s.settings) }
    var cityInput by remember { mutableStateOf(s.settings.city) }
    var cityStatus by remember { mutableStateOf("") }

    fun close() {
        s.saveSettings(draft)
        s.showSettings = false
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
        contentAlignment = Alignment.Center,
    ) {
        Card(
            Modifier.width(900.dp).height(780.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
            radius = 26.dp, padding = PaddingValues(33.dp), spacing = 20.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", fontFamily = K.Serif, fontSize = 40.sp, modifier = Modifier.weight(1f))
                Btn("Done", { close() }, primary = true, height = 50.dp, fontSize = 18)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Section("Weather location") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field(cityInput, { cityInput = it }, "City, e.g. Chicago", Modifier.weight(1f))
                        Btn("Find", {
                            cityStatus = "Searching…"
                            scope.launch {
                                val p = s.geocode(cityInput)
                                if (p == null) {
                                    cityStatus = "Couldn't find that place"
                                } else {
                                    draft = draft.copy(city = p.label, lat = p.lat, lon = p.lon)
                                    cityInput = p.label
                                    cityStatus = "Found %.2f, %.2f".format(p.lat, p.lon)
                                }
                            }
                        }, height = 48.dp)
                    }
                    if (cityStatus.isNotEmpty()) Text(cityStatus, color = K.Muted, fontSize = 15.sp)
                }
                Section("Display") {
                    Toggle("Fahrenheit (°F)", draft.fahrenheit) { draft = draft.copy(fahrenheit = it) }
                    Toggle("24-hour clock", draft.use24h) { draft = draft.copy(use24h = it) }
                }
                Section("Paprika 3 recipes") {
                    if (s.paprikaSignedIn) {
                        Text("Signed in as ${s.paprikaEmail}. Your Paprika recipes fill the recipe book, and today's meal plan sets tonight's dinner.", color = K.Muted, fontSize = 15.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Btn("Sync now", { s.syncRecipesNow() }, height = 48.dp)
                            Btn("Sign out", { s.paprikaSignOut() }, height = 48.dp)
                            Text(s.paprikaStatus, color = K.Muted, fontSize = 16.sp)
                        }
                    } else {
                        Text("Sign in with the email and password you use for Paprika Cloud Sync.", color = K.Muted, fontSize = 15.sp)
                        var email by remember { mutableStateOf(s.paprikaEmail) }
                        var pw by remember { mutableStateOf("") }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Input(email, { email = it }, "Email", Modifier.weight(1f))
                            Input(pw, { pw = it }, "Password", Modifier.weight(1f), password = true)
                            Btn("Sign in", { scope.launch { if (s.paprikaLogin(email, pw)) pw = "" } }, primary = true, height = 48.dp)
                        }
                        if (s.paprikaStatus.isNotEmpty()) Text(s.paprikaStatus, color = K.Muted, fontSize = 15.sp)
                    }
                }
                Section("Google Calendar") {
                    Text(
                        "In Google Calendar on a computer: Settings › your calendar › Integrate calendar › Secret address in iCal format. Paste it here.",
                        color = K.Muted, fontSize = 15.sp,
                    )
                    Field(draft.icalUrl, { draft = draft.copy(icalUrl = it.trim()) }, "https://calendar.google.com/calendar/ical/…/basic.ics", Modifier.fillMaxWidth())
                }
                Section("Updates") {
                    Toggle("Update automatically from GitHub", draft.autoUpdate) { draft = draft.copy(autoUpdate = it) }
                    val status = when (val u = s.update) {
                        UpdateState.Idle -> ""
                        UpdateState.Checking -> "Checking…"
                        UpdateState.UpToDate -> "You're on the latest version."
                        is UpdateState.Downloading -> "Downloading ${u.info.versionName}… ${(u.progress * 100).toInt()}%"
                        is UpdateState.Ready -> "Version ${u.info.versionName} is ready to install."
                        is UpdateState.Installing -> "Installing ${u.info.versionName}…"
                        is UpdateState.Failed -> u.message
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Btn("Check now", { s.checkNow() }, height = 48.dp)
                        if (s.update is UpdateState.Ready || s.update is UpdateState.Installing) {
                            Btn("Install", { s.install() }, primary = true, height = 48.dp)
                        }
                        Text(status, color = K.Muted, fontSize = 16.sp)
                    }
                    Text("Installed version ${BuildConfig.VERSION_NAME} · github.com/${BuildConfig.UPDATE_REPO}", color = K.Dim, fontSize = 14.sp)
                }
                Section("Home screen") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Btn("Rearrange dock", { s.showSettings = false; s.editDock = true }, height = 48.dp)
                        Btn(if (s.media.granted) "Media controls: on" else "Turn on media controls", { s.media.requestAccess() }, height = 48.dp)
                    }
                    Text("Tip: press and hold any dock icon to rearrange the dock. Media controls need notification access for \"Kitchen media controls\".", color = K.Dim, fontSize = 14.sp)
                }
                Section("Android") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Btn("Choose home app", {
                            ctx.startActivity(Intent(AndroidSettings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }, height = 48.dp)
                        Btn("Android settings", {
                            ctx.startActivity(Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }, height = 48.dp)
                        Btn("Refresh now", { s.refreshAll() }, height = 48.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title.uppercase(), color = K.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
        content()
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).background(K.Surface2).border(1.dp, K.Border2, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, color = K.Dim, fontSize = 17.sp, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = K.Text, fontSize = 17.sp),
            cursorBrush = SolidColor(K.Accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!on) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 19.sp, modifier = Modifier.weight(1f))
        Box(
            Modifier.width(60.dp).height(34.dp).clip(RoundedCornerShape(17.dp)).background(if (on) K.Accent else K.Border2).padding(4.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).background(if (on) K.Bg else K.Text))
        }
    }
}

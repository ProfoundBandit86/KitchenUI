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
import com.kitchenui.launcher.MailAccount
import com.kitchenui.launcher.Member
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Theme", fontSize = 19.sp, modifier = Modifier.weight(1f))
                        val names = listOf("dark" to "Dark", "light" to "Light", "auto" to "Auto")
                        Choice(names.map { it.second }, names.first { it.first == draft.theme }.second) { label ->
                            val t = names.first { it.second == label }.first
                            draft = draft.copy(theme = t)
                            s.saveSettings(draft)   // preview right away
                        }
                    }
                    Text("Auto is light from 7 AM to 7 PM and dark at night.", color = K.Dim, fontSize = 14.sp)
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
                Section("Household") { HouseholdSettings(s) }
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

/** Household members get a "Send to …" button on the shopping list; the account below sends the email. */
@Composable
private fun HouseholdSettings(s: KitchenState) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var addStatus by remember { mutableStateOf("") }
    Text("Everyone here gets a \"Send to\" button on the shopping list, which emails them what's left to get.", color = K.Muted, fontSize = 15.sp)
    s.members.forEach { m ->
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(K.Surface2).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(m.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(180.dp))
            Text(m.email, color = K.Muted, fontSize = 17.sp, modifier = Modifier.weight(1f))
            IconBtn(Glyph.CLOSE, { s.saveMembers(s.members - m) }, size = 40.dp)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Input(name, { name = it }, "Name", Modifier.width(220.dp))
        Input(email, { email = it }, "Email address", Modifier.weight(1f))
        Btn("Add", {
            when {
                name.isBlank() -> addStatus = "Enter a name"
                !email.contains('@') || !email.substringAfter('@').contains('.') -> addStatus = "Enter a valid email address"
                else -> {
                    s.saveMembers(s.members + Member(name, email))
                    name = ""; email = ""; addStatus = ""
                }
            }
        }, primary = true, height = 48.dp)
    }
    if (addStatus.isNotEmpty()) Text(addStatus, color = K.Danger, fontSize = 15.sp)

    MailAccountSettings(s)
    Text(
        "So the list is never buried: it's sent from your own account, flagged high priority, and the subject always starts " +
            "\"KitchenUI shopping list\". Make one rule in your mail app for that subject: never send to spam, " +
            "mark as important, and star or flag it. The email reminds everyone how.",
        color = K.Dim, fontSize = 14.sp,
    )
}

/** Any provider: SMTP to send, IMAP or POP3 to check the sign-in (IMAP also saves a copy to Sent). */
@Composable
private fun MailAccountSettings(s: KitchenState) {
    val scope = rememberCoroutineScope()
    var a by remember { mutableStateOf(s.mail) }
    var smtpPort by remember { mutableStateOf(s.mail.smtpPort.toString()) }
    var inPort by remember { mutableStateOf(s.mail.inPort.toString()) }
    var status by remember { mutableStateOf("") }
    fun current() = a.copy(smtpPort = smtpPort.toIntOrNull() ?: 465, inPort = inPort.toIntOrNull() ?: 993)

    Text("Send from", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Send with", fontSize = 17.sp)
        Choice(listOf("Email app", "This screen"), if (a.method == "app") "Email app" else "This screen") { c ->
            a = a.copy(method = if (c == "Email app") "app" else "smtp")
            s.saveMail(current())
            status = ""
        }
    }
    if (a.method == "app") {
        Text(
            "Send to opens your email app (Gmail, Yahoo Mail, Outlook…) with the person, subject and list filled in. " +
                "Tap Send there. It uses the account signed in to that app, so no app password is needed.",
            color = K.Muted, fontSize = 15.sp,
        )
        return
    }
    Text(
        "Works with any email provider. Enter your address and tap Find settings. Gmail, Yahoo, iCloud and Outlook need an " +
            "app password when 2-step verification is on.",
        color = K.Muted, fontSize = 15.sp,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Input(a.email, { a = a.copy(email = it) }, "you@example.com", Modifier.weight(1f))
        Input(a.password, { a = a.copy(password = it) }, "Password or app password", Modifier.weight(1f), password = true)
        Btn("Find settings", {
            status = "Looking up ${a.email.substringAfter('@', "your provider")}…"
            scope.launch {
                val f = com.kitchenui.launcher.Mailer.detect(a.email.trim())
                if (f == null) {
                    status = "Couldn't find settings for that address. Enter them from your provider's help page."
                } else {
                    a = a.copy(
                        smtpHost = f.smtpHost, smtpSecurity = f.smtpSecurity,
                        inType = f.inType, inHost = f.inHost, inSecurity = f.inSecurity,
                        user = if (f.userIsLocalPart) a.email.substringBefore('@') else "",
                    )
                    smtpPort = f.smtpPort.toString()
                    inPort = f.inPort.toString()
                    status = "Found the settings. " + com.kitchenui.launcher.Mailer.appPasswordHelp(f.smtpHost) + " Then tap Test sign-in."
                }
            }
        }, height = 48.dp)
    }
    Input(a.user, { a = a.copy(user = it) }, "Username (leave empty to use the email address)", Modifier.fillMaxWidth())

    Text("Outgoing (SMTP)", color = K.Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Input(a.smtpHost, { a = a.copy(smtpHost = it) }, "smtp.example.com", Modifier.weight(1f))
        Input(smtpPort, { smtpPort = it.filter { c -> c.isDigit() }.take(5) }, "465", Modifier.width(100.dp))
        Choice(listOf("SSL", "STARTTLS"), a.smtpSecurity) { a = a.copy(smtpSecurity = it); smtpPort = if (it == "SSL") "465" else "587" }
    }

    Text("Incoming", color = K.Muted, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Choice(listOf("IMAP", "POP3", "None"), a.inType.ifBlank { "None" }) { t ->
            a = a.copy(inType = if (t == "None") "" else t)
            inPort = when {
                t == "IMAP" -> if (a.inSecurity == "SSL") "993" else "143"
                t == "POP3" -> if (a.inSecurity == "SSL") "995" else "110"
                else -> inPort
            }
        }
        if (a.inType.isNotBlank()) {
            Input(a.inHost, { a = a.copy(inHost = it) }, if (a.inType == "POP3") "pop.example.com" else "imap.example.com", Modifier.weight(1f))
            Input(inPort, { inPort = it.filter { c -> c.isDigit() }.take(5) }, "993", Modifier.width(100.dp))
            Choice(listOf("SSL", "STARTTLS"), a.inSecurity) { sec ->
                a = a.copy(inSecurity = sec)
                inPort = if (a.inType == "POP3") (if (sec == "SSL") "995" else "110") else (if (sec == "SSL") "993" else "143")
            }
        }
    }
    if (a.inType == "IMAP") Toggle("Save a copy in my Sent folder", a.saveSent) { a = a.copy(saveSent = it) }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Btn("Save", { s.saveMail(current()); status = "Saved" }, height = 48.dp)
        Btn("Test sign-in", {
            s.saveMail(current())
            status = "Signing in…"
            scope.launch { status = s.verifyMail() }
        }, height = 48.dp)
        val first = s.members.firstOrNull()
        Btn(if (first != null) "Send test to ${first.name}" else "Send test to me", {
            s.saveMail(current())
            val to = first ?: Member("Me", a.email.trim())
            status = "Sending…"
            scope.launch { status = s.sendTest(to) }
        }, primary = true, height = 48.dp)
    }
    if (status.isNotEmpty()) Text(status, color = if (status.startsWith("Couldn't")) K.Danger else K.Muted, fontSize = 15.sp)
    if (status.startsWith("Couldn't")) {
        Text("Or switch Send with to Email app above: it uses the email app on this screen and needs no app password.", color = K.Muted, fontSize = 15.sp)
    }
}

@Composable
private fun Choice(options: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(12.dp)).background(K.Surface2).padding(3.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.height(42.dp).clip(RoundedCornerShape(10.dp)).background(if (on) K.Text else Color.Transparent)
                    .clickable { onPick(o) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (on) K.Bg else K.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

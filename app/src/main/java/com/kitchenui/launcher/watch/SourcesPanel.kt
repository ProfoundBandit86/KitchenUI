package com.kitchenui.launcher.watch

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.fluxtv.app.Addon
import com.fluxtv.app.Addons
import com.fluxtv.app.App
import com.fluxtv.app.Bridge
import com.fluxtv.app.Iptv
import com.fluxtv.app.MediaServer
import com.fluxtv.app.RealDebrid
import com.fluxtv.app.Store
import com.kitchenui.launcher.ui.Btn
import com.kitchenui.launcher.ui.Input
import com.kitchenui.launcher.ui.K
import com.kitchenui.launcher.ui.SectionTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Media servers, Stremio add-ons, Real-Debrid and IPTV providers. */
@Composable
fun SourcesPanel(m: WatchModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }   // bump to re-read lists
    var status by remember { mutableStateOf("") }
    var form by remember { mutableStateOf("") }        // which add form is open

    var servers by remember { mutableStateOf<List<MediaServer>>(emptyList()) }
    var addons by remember { mutableStateOf<List<Addon>>(emptyList()) }
    var iptv by remember { mutableStateOf<List<Iptv.Source>>(emptyList()) }
    var rdUser by remember { mutableStateOf("") }
    LaunchedEffect(version) {
        withContext(Dispatchers.IO) {
            servers = Store.servers()
            addons = Addons.all()
            iptv = Iptv.sources()
            rdUser = if (RealDebrid.isSignedIn()) runCatching { RealDebrid.user().optString("username") }.getOrDefault("") else ""
        }
    }

    fun run(label: String, work: suspend () -> String) {
        status = label
        scope.launch {
            status = try { work() } catch (e: Exception) { App.message(e) }
            version++
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { m.back() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(1000.dp).height(820.dp).clip(RoundedCornerShape(26.dp)).background(K.Surface)
                .border(1.dp, K.Border, RoundedCornerShape(26.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(33.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sources", fontFamily = K.Serif, fontSize = 40.sp, modifier = Modifier.weight(1f))
                Btn("Done", { m.back() }, primary = true, height = 50.dp, fontSize = 18)
            }
            if (status.isNotEmpty()) {
                Text(status, color = K.Accent, fontSize = 17.sp, modifier = Modifier.clickable { status = "" })
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(26.dp)) {

                // ---------------------------------------------------------- media servers
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Media servers")
                    servers.forEach { sv ->
                        ItemRow(sv.name, "${sv.kindLabel()} · ${sv.url}") {
                            Bridge.removeServer(sv); version++
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Btn("Plex: sign in with code", { form = "plex" }, height = 46.dp, fontSize = 16)
                        Btn("Plex address + token", { form = "plexManual" }, height = 46.dp, fontSize = 16)
                        Btn("Jellyfin", { form = MediaServer.JELLYFIN }, height = 46.dp, fontSize = 16)
                        Btn("Emby", { form = MediaServer.EMBY }, height = 46.dp, fontSize = 16)
                    }
                    when (form) {
                        "plex" -> PlexCodeLogin { msg -> status = msg; form = ""; version++ }
                        "plexManual" -> {
                            var url by remember { mutableStateOf("") }
                            var token by remember { mutableStateOf("") }
                            FormRow {
                                Input(url, { url = it }, "http://192.168.1.10:32400", Modifier.weight(1f))
                                Input(token, { token = it }, "X-Plex-Token", Modifier.weight(1f))
                                Btn("Add", { run("Connecting…") { val s = withContext(Dispatchers.IO) { Bridge.plexManual(url, token) }; form = ""; "Added ${s.name}" } }, primary = true, height = 48.dp)
                            }
                        }
                        MediaServer.JELLYFIN, MediaServer.EMBY -> {
                            val kind = form
                            var url by remember(kind) { mutableStateOf("") }
                            var user by remember(kind) { mutableStateOf("") }
                            var pass by remember(kind) { mutableStateOf("") }
                            FormRow {
                                Input(url, { url = it }, "http://192.168.1.10:8096", Modifier.weight(1.3f))
                                Input(user, { user = it }, "Username", Modifier.weight(1f))
                                Input(pass, { pass = it }, "Password", Modifier.weight(1f), password = true)
                                Btn("Sign in", { run("Signing in…") { val s = withContext(Dispatchers.IO) { Bridge.jellyfinLogin(kind, url, user, pass) }; form = ""; "Added ${s.name}" } }, primary = true, height = 48.dp)
                            }
                        }
                    }
                }

                // ---------------------------------------------------------- add-ons
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Stremio add-ons")
                    addons.forEach { a ->
                        ItemRow(a.name(), a.description().ifBlank { a.url }, configureUrl = Addons.configureUrl(a)) {
                            Addons.uninstall(a.url); version++
                        }
                    }
                    var url by remember { mutableStateOf("") }
                    FormRow {
                        Input(url, { url = it }, "Add-on link (…/manifest.json or stremio://…)", Modifier.weight(1f))
                        Btn("Install", {
                            run("Installing…") { val a = withContext(Dispatchers.IO) { Addons.install(url) }; url = ""; "Installed ${a.name()}" }
                        }, primary = true, height = 48.dp)
                    }
                    Text(
                        "Configure opens the add-on's settings page in the browser. Copy the install link it gives you and paste it here; it replaces the old one.",
                        color = K.Muted, fontSize = 14.sp,
                    )
                }

                // ---------------------------------------------------------- Real-Debrid
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Real-Debrid")
                    if (RealDebrid.isSignedIn()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Signed in" + if (rdUser.isNotBlank()) " as $rdUser" else "", fontSize = 18.sp, modifier = Modifier.weight(1f))
                            Btn("Sign out", { RealDebrid.signOut(); version++ }, height = 44.dp, fontSize = 16)
                        }
                    } else {
                        Text("Turns torrent results from stream add-ons into direct streams.", color = K.Muted, fontSize = 15.sp)
                        var key by remember { mutableStateOf("") }
                        FormRow {
                            Btn("Sign in with a code", { form = "rd" }, height = 48.dp)
                            Input(key, { key = it }, "or paste API token (real-debrid.com/apitoken)", Modifier.weight(1f))
                            Btn("Save", { RealDebrid.setApiKey(key.trim()); key = ""; version++ }, primary = true, height = 48.dp)
                        }
                        if (form == "rd") RdCodeLogin { msg -> status = msg; form = ""; version++ }
                    }
                }

                // ---------------------------------------------------------- IPTV
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("Live TV (IPTV)")
                    iptv.forEach { src ->
                        ItemRow(src.name, if (src.xtream()) "Xtream Codes · ${src.url}" else "M3U playlist" + if (src.epg.isBlank()) "" else " + guide") {
                            Iptv.remove(src.id); version++
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Btn("Xtream Codes login", { form = "xtream" }, height = 46.dp, fontSize = 16)
                        Btn("M3U playlist", { form = "m3u" }, height = 46.dp, fontSize = 16)
                        var vod by remember { mutableStateOf(Iptv.vodEnabled()) }
                        Btn(if (vod) "Provider movies & series: on" else "Provider movies & series: off", {
                            vod = !vod; Iptv.setVodEnabled(vod); version++
                        }, height = 46.dp, fontSize = 16)
                    }
                    when (form) {
                        "xtream" -> {
                            var url by remember { mutableStateOf("") }
                            var user by remember { mutableStateOf("") }
                            var pass by remember { mutableStateOf("") }
                            FormRow {
                                Input(url, { url = it }, "Server (http://host:port)", Modifier.weight(1.3f))
                                Input(user, { user = it }, "Username", Modifier.weight(1f))
                                Input(pass, { pass = it }, "Password", Modifier.weight(1f), password = true)
                                Btn("Add", { run("Logging in…") { val s = withContext(Dispatchers.IO) { Iptv.loginXtream(url, user, pass).also { Iptv.save(it) } }; form = ""; "Added ${s.name}" } }, primary = true, height = 48.dp)
                            }
                        }
                        "m3u" -> {
                            var m3u by remember { mutableStateOf("") }
                            var epg by remember { mutableStateOf("") }
                            FormRow {
                                Input(m3u, { m3u = it }, "Playlist (M3U) link", Modifier.weight(1.3f))
                                Input(epg, { epg = it }, "Guide (XMLTV) link, optional", Modifier.weight(1f))
                                Btn("Add", { run("Loading playlist…") { val s = withContext(Dispatchers.IO) { Iptv.addM3u(m3u, epg).also { Iptv.save(it) } }; form = ""; "Added ${s.name}" } }, primary = true, height = 48.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Surface2).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun ItemRow(title: String, sub: String, configureUrl: String? = null, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, K.Border, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = K.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (configureUrl != null) {
            Btn("Configure", { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(configureUrl))) } }, height = 40.dp, fontSize = 15)
        }
        Btn("Remove", onRemove, height = 40.dp, fontSize = 15)
    }
}

/** Plex: show a code to enter at plex.tv/link, then add every server on the account. */
@Composable
private fun PlexCodeLogin(done: (String) -> Unit) {
    var code by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            val c = withContext(Dispatchers.IO) { Bridge.plexStart() }
            code = c.code
            val deadline = System.currentTimeMillis() + 10 * 60_000
            while (System.currentTimeMillis() < deadline) {
                delay(3000)
                val found = withContext(Dispatchers.IO) { Bridge.plexPoll(c) } ?: continue
                done(if (found.isEmpty()) "No reachable Plex servers on this account" else "Added ${found.size} Plex server" + if (found.size == 1) "" else "s")
                return@LaunchedEffect
            }
            err = "The code expired. Try again."
        } catch (e: Exception) {
            err = App.message(e)
        }
    }
    CodeBox("On your phone, go to plex.tv/link and enter:", code, err)
}

/** Real-Debrid: device code at real-debrid.com/device. */
@Composable
private fun RdCodeLogin(done: (String) -> Unit) {
    var code by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf("real-debrid.com/device") }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try {
            val dc = withContext(Dispatchers.IO) { RealDebrid.startDeviceLogin() }
            code = dc.userCode
            if (!dc.verificationUrl.isNullOrBlank()) url = dc.verificationUrl
            val deadline = System.currentTimeMillis() + (if (dc.expiresIn > 0) dc.expiresIn * 1000L else 600_000L)
            while (System.currentTimeMillis() < deadline) {
                delay(maxOf(dc.interval, 3) * 1000L)
                if (withContext(Dispatchers.IO) { RealDebrid.pollDeviceLogin(dc) }) {
                    done("Signed in to Real-Debrid")
                    return@LaunchedEffect
                }
            }
            err = "The code expired. Try again."
        } catch (e: Exception) {
            err = App.message(e)
        }
    }
    CodeBox("On your phone, go to $url and enter:", code, err)
}

@Composable
private fun CodeBox(prompt: String, code: String?, err: String?) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(K.Surface2).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (err != null) {
            Text(err, color = K.Accent, fontSize = 18.sp)
        } else {
            Text(prompt, fontSize = 18.sp)
            Text(code ?: "…", color = K.Accent, fontSize = 52.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
            Text("Waiting for you to enter the code…", color = K.Muted, fontSize = 15.sp)
        }
    }
}

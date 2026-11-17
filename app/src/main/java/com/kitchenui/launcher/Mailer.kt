package com.kitchenui.launcher

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Emails the shopping list from any provider. Outgoing mail goes over SMTP (SSL or STARTTLS,
 * AUTH PLAIN or LOGIN). The incoming server is used to check the sign-in (IMAP or POP3),
 * and with IMAP a copy is saved to the Sent folder so it shows up in your mail app.
 *
 * To keep the email from getting buried it is sent from your real account (so it passes
 * spam checks), flagged high priority, and always uses the same subject start,
 * "KitchenUI shopping list", so a one-time filter can star it and mark it important.
 */
object Mailer {
    const val SUBJECT_TAG = "KitchenUI shopping list"

    class MailException(msg: String) : Exception(msg)

    // ---------- Message ----------

    fun subject(items: List<String>): String {
        val day = LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
        return "🛒 $SUBJECT_TAG · $day · ${items.size} item${if (items.size == 1) "" else "s"}"
    }

    suspend fun sendList(acct: MailAccount, to: Member, items: List<String>) =
        send(acct, to, subject(items), plain(to, items), html(to, items))

    suspend fun sendTest(acct: MailAccount, to: Member) =
        send(
            acct, to, "🛒 $SUBJECT_TAG · test",
            "This is a test from your kitchen screen. Shopping lists will arrive like this.\n\n" + filterTip(),
            page("Test from your kitchen", "<p style=\"font-size:16px\">Shopping lists will arrive like this.</p>"),
        )

    /**
     * Opens the email app (Gmail, Yahoo Mail, Outlook…) with the person, subject and list filled
     * in; you tap Send. Uses the account already signed in to that app, so no app password.
     */
    fun composeInApp(ctx: android.content.Context, to: Member, items: List<String>): Boolean {
        val i = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("mailto:" + android.net.Uri.encode(to.email.trim(), "@")))
            .putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(to.email.trim()))
            .putExtra(android.content.Intent.EXTRA_SUBJECT, subject(items))
            .putExtra(android.content.Intent.EXTRA_TEXT, plain(to, items))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            false
        }
    }

    private fun plain(to: Member, items: List<String>): String = buildString {
        append("Hi ${to.name}, here's the shopping list from the kitchen:\n\n")
        items.forEach { append("[ ] ").append(it).append('\n') }
        append("\n").append(filterTip())
    }

    private fun filterTip() =
        "Tip: so these never get filtered, add a rule in your mail app for subject \"$SUBJECT_TAG\": " +
            "never send to spam, mark as important, and star or flag it."

    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun html(to: Member, items: List<String>): String {
        val rows = items.joinToString("") {
            "<tr><td style=\"padding:10px 0;border-bottom:1px solid #eee;font-size:18px\">" +
                "<span style=\"display:inline-block;width:18px;height:18px;border:2px solid #F4A259;border-radius:5px;" +
                "vertical-align:-3px;margin-right:12px\"></span>${esc(it)}</td></tr>"
        }
        return page(
            "Shopping list",
            "<p style=\"font-size:16px;color:#555\">Hi ${esc(to.name)}, here's the list from the kitchen.</p>" +
                "<table style=\"width:100%;border-collapse:collapse\">$rows</table>",
        )
    }

    private fun page(title: String, body: String) =
        "<!doctype html><html><body style=\"margin:0;background:#f6f4ef;font-family:Helvetica,Arial,sans-serif;color:#121416\">" +
            "<div style=\"max-width:520px;margin:0 auto;padding:24px\">" +
            "<div style=\"background:#ffffff;border-radius:14px;padding:24px 26px;border-top:6px solid #F4A259\">" +
            "<h1 style=\"margin:0 0 6px;font-family:Georgia,serif;font-weight:normal;font-size:28px\">${esc(title)}</h1>" +
            body + "</div>" +
            "<p style=\"font-size:12px;color:#888;line-height:1.5\">${esc(filterTip())}</p>" +
            "</div></body></html>"

    private fun header(t: String) = "=?UTF-8?B?" + Base64.encodeToString(t.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "?="

    private fun b64(t: String) = Base64.encodeToString(t.toByteArray(Charsets.UTF_8), Base64.DEFAULT)
        .replace("\r\n", "\n").replace("\n", "\r\n").trimEnd()

    private fun message(acct: MailAccount, to: Member, subject: String, text: String, html: String): String {
        val boundary = "kui-" + UUID.randomUUID().toString()
        val domain = acct.email.substringAfter('@', "kitchenui.local")
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date())
        return listOf(
            "From: ${header("Kitchen")} <${acct.email}>",
            "To: ${header(to.name)} <${to.email.trim()}>",
            "Reply-To: <${acct.email}>",
            "Subject: ${header(subject)}",
            "Date: $date",
            "Message-ID: <${UUID.randomUUID()}@$domain>",
            "MIME-Version: 1.0",
            // High priority, understood by Outlook, Apple Mail and most phone mail apps
            "X-Priority: 1 (Highest)",
            "X-MSMail-Priority: High",
            "Importance: High",
            "Priority: urgent",
            "Content-Type: multipart/alternative; boundary=\"$boundary\"",
            "",
            "--$boundary",
            "Content-Type: text/plain; charset=UTF-8",
            "Content-Transfer-Encoding: base64",
            "",
            b64(text),
            "--$boundary",
            "Content-Type: text/html; charset=UTF-8",
            "Content-Transfer-Encoding: base64",
            "",
            b64(html),
            "--$boundary--",
            "",
        ).joinToString("\r\n")
    }

    // ---------- Connections ----------

    private class Conn(var socket: Socket) {
        var r = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        var w: OutputStream = socket.getOutputStream()
        fun write(line: String) { w.write((line + "\r\n").toByteArray(Charsets.UTF_8)); w.flush() }
        fun read(): String = r.readLine() ?: throw MailException("The mail server closed the connection")
        fun upgrade(host: String, port: Int) {
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, host, port, true) as SSLSocket
            tls.startHandshake()
            socket = tls
            r = BufferedReader(InputStreamReader(tls.getInputStream(), Charsets.UTF_8))
            w = tls.getOutputStream()
        }
        fun close() { runCatching { socket.close() } }
    }

    private fun open(host: String, port: Int, ssl: Boolean): Conn {
        if (host.isBlank()) throw MailException("Enter the server name")
        val s: Socket = if (ssl) {
            (SSLSocketFactory.getDefault().createSocket() as SSLSocket).apply { connect(InetSocketAddress(host, port), 15_000) }
        } else {
            Socket().apply { connect(InetSocketAddress(host, port), 15_000) }
        }
        s.soTimeout = 20_000
        return Conn(s)
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    /** What to do when a provider refuses the password, by the server it came from. */
    fun appPasswordHelp(host: String): String {
        val h = host.lowercase()
        return when {
            "gmail" in h || "google" in h ->
                "Google only accepts an app password here, never your normal password. Turn on 2-Step Verification at " +
                    "myaccount.google.com/security, then create one at myaccount.google.com/apppasswords and enter its 16 letters."
            "yahoo" in h ->
                "Yahoo only accepts an app password here, never your normal password. At login.yahoo.com/account/security " +
                    "turn on 2-step verification, then tap Generate app password and enter it here."
            "aol" in h ->
                "AOL needs an app password: login.aol.com/account/security › Generate app password."
            "me.com" in h || "icloud" in h ->
                "iCloud needs an app-specific password from account.apple.com › Sign-In and Security › App-Specific Passwords."
            "outlook" in h || "office365" in h || "hotmail" in h || "live" in h ->
                "Outlook.com has turned off password sign-in for mail apps on many accounts. If an app password doesn't work, " +
                    "use the Email app sending option below instead."
            else -> "Check the username and password. Providers with 2-step verification need an app password."
        }
    }

    private fun refusedMsg(server: String, host: String, reply: String) =
        "$server ($host) refused the sign-in: ${reply.trim().take(160)}\n" + appPasswordHelp(host)

    // ---------- SMTP ----------

    private class Smtp(val c: Conn, val host: String) {
        fun cmd(line: String?, expect: Int): String {
            if (line != null) c.write(line)
            val sb = StringBuilder()
            while (true) {
                val l = c.read()
                sb.append(l).append('\n')
                if (l.length < 4 || l[3] != '-') break
            }
            val code = sb.take(3).toString().toIntOrNull() ?: 0
            if (code != expect) {
                c.close()
                val text = sb.toString()
                throw MailException(
                    when {
                        code == 535 || code == 534 || code == 530 -> refusedMsg("Outgoing server", host, text.lines().first())
                        else -> "Outgoing server said: " + sb.lines().first().drop(4).trim()
                    }
                )
            }
            return sb.toString()
        }
    }

    private fun smtpLogin(acct: MailAccount): Smtp {
        val sm = Smtp(open(acct.smtpHost, acct.smtpPort, acct.smtpSecurity == "SSL"), acct.smtpHost)
        sm.cmd(null, 220)
        var caps = sm.cmd("EHLO kitchenui.local", 250)
        if (acct.smtpSecurity != "SSL") {
            if (!caps.contains("STARTTLS")) { sm.c.close(); throw MailException("The outgoing server doesn't offer STARTTLS on port ${acct.smtpPort}") }
            sm.cmd("STARTTLS", 220)
            sm.c.upgrade(acct.smtpHost, acct.smtpPort)
            caps = sm.cmd("EHLO kitchenui.local", 250)
        }
        val authLine = caps.lines().firstOrNull { it.drop(4).startsWith("AUTH") }.orEmpty().uppercase()
        if (authLine.contains("PLAIN") || !authLine.contains("LOGIN")) {
            sm.cmd("AUTH PLAIN " + b64("\u0000${acct.login}\u0000${acct.password}".toByteArray(Charsets.UTF_8)), 235)
        } else {
            sm.cmd("AUTH LOGIN", 334)
            sm.cmd(b64(acct.login.toByteArray(Charsets.UTF_8)), 334)
            sm.cmd(b64(acct.password.toByteArray(Charsets.UTF_8)), 235)
        }
        return sm
    }

    private fun smtpSend(acct: MailAccount, to: String, data: String) {
        val sm = smtpLogin(acct)
        try {
            sm.cmd("MAIL FROM:<${acct.email}>", 250)
            sm.cmd("RCPT TO:<$to>", 250)
            sm.cmd("DATA", 354)
            // Dot-stuffing: a line starting with "." gets another "."
            sm.c.w.write(data.replace("\r\n.", "\r\n..").toByteArray(Charsets.UTF_8))
            sm.cmd("\r\n.", 250)
            runCatching { sm.cmd("QUIT", 221) }
        } finally {
            sm.c.close()
        }
    }

    // ---------- IMAP ----------

    private fun imapQuote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private class Imap(val c: Conn, val host: String) {
        private var n = 0
        /** Sends a tagged command and returns the untagged lines; throws unless OK. */
        fun cmd(line: String, literal: ByteArray? = null): List<String> {
            val tag = "k" + (++n)
            c.write("$tag $line")
            val out = mutableListOf<String>()
            while (true) {
                val l = c.read()
                if (literal != null && l.startsWith("+")) {
                    c.w.write(literal); c.w.write("\r\n".toByteArray()); c.w.flush()
                    continue
                }
                if (l.startsWith("$tag ")) {
                    val rest = l.substring(tag.length + 1)
                    if (!rest.startsWith("OK")) throw MailException(if (line.startsWith("LOGIN")) refusedMsg("Incoming server", host, rest) else "Incoming server said: $rest")
                    return out
                }
                out.add(l)
            }
        }
    }

    private fun imapLogin(acct: MailAccount): Imap {
        val c = open(acct.inHost, acct.inPort, acct.inSecurity == "SSL")
        val greet = c.read()
        if (!greet.startsWith("* OK") && !greet.startsWith("* PREAUTH")) { c.close(); throw MailException("Unexpected IMAP greeting") }
        val im = Imap(c, acct.inHost)
        if (acct.inSecurity != "SSL") {
            im.cmd("STARTTLS")
            c.upgrade(acct.inHost, acct.inPort)
        }
        im.cmd("LOGIN ${imapQuote(acct.login)} ${imapQuote(acct.password)}")
        return im
    }

    /** Finds the Sent folder (special-use \Sent first, then common names). */
    private fun sentFolder(im: Imap): String {
        val lines = runCatching { im.cmd("LIST \"\" \"*\"") }.getOrDefault(emptyList())
        fun name(l: String) = l.substringAfterLast(' ').trim().trim('"')
        lines.firstOrNull { it.contains("\\Sent", ignoreCase = true) }?.let { return name(it) }
        val names = lines.map { name(it) }
        return listOf("Sent", "Sent Items", "Sent Messages", "[Gmail]/Sent Mail", "INBOX.Sent")
            .firstOrNull { want -> names.any { it.equals(want, true) } } ?: "Sent"
    }

    private fun imapSaveSent(acct: MailAccount, data: String) {
        val im = imapLogin(acct)
        try {
            val folder = sentFolder(im)
            val bytes = data.toByteArray(Charsets.UTF_8)
            im.cmd("APPEND ${imapQuote(folder)} (\\Seen) {${bytes.size}}", bytes)
            runCatching { im.cmd("LOGOUT") }
        } finally {
            im.c.close()
        }
    }

    // ---------- POP3 ----------

    private fun pop3Check(acct: MailAccount) {
        val c = open(acct.inHost, acct.inPort, acct.inSecurity == "SSL")
        try {
            fun ok(line: String?, what: String) {
                if (line != null) c.write(line)
                val l = c.read()
                if (!l.startsWith("+OK")) throw MailException(if (what == "pass") refusedMsg("Incoming server", acct.inHost, l) else "Incoming server said: $l")
            }
            ok(null, "greet")
            if (acct.inSecurity != "SSL") {
                ok("STLS", "stls")
                c.upgrade(acct.inHost, acct.inPort)
            }
            ok("USER ${acct.login}", "user")
            ok("PASS ${acct.password}", "pass")
            runCatching { ok("QUIT", "quit") }
        } finally {
            c.close()
        }
    }

    // ---------- Public ----------

    private suspend fun send(acct: MailAccount, to: Member, subject: String, text: String, html: String) =
        withContext(Dispatchers.IO) {
            if (!acct.ready) throw MailException("Set up the email account in Settings first")
            val data = message(acct, to, subject, text, html)
            smtpSend(acct, to.email.trim(), data)
            // A copy in Sent, so it shows in your mail app. Best effort: the email already went out.
            if (acct.inType == "IMAP" && acct.saveSent && acct.inHost.isNotBlank()) runCatching { imapSaveSent(acct, data) }
        }

    /** Signs in to the outgoing server and the incoming one, without sending anything. */
    suspend fun verify(acct: MailAccount): String = withContext(Dispatchers.IO) {
        if (!acct.ready) throw MailException("Enter the email, password and outgoing server")
        val sm = smtpLogin(acct)
        runCatching { sm.cmd("QUIT", 221) }
        sm.c.close()
        when (acct.inType) {
            "IMAP" -> { val im = imapLogin(acct); runCatching { im.cmd("LOGOUT") }; im.c.close(); "Outgoing (SMTP) and incoming (IMAP) sign-in work" }
            "POP3" -> { pop3Check(acct); "Outgoing (SMTP) and incoming (POP3) sign-in work" }
            else -> "Outgoing (SMTP) sign-in works"
        }
    }

    // ---------- Finding the servers ----------

    data class Servers(
        val smtpHost: String, val smtpPort: Int, val smtpSecurity: String,
        val inType: String, val inHost: String, val inPort: Int, val inSecurity: String,
        val userIsLocalPart: Boolean = false,
    )

    private val known = mapOf(
        "gmail.com" to Servers("smtp.gmail.com", 465, "SSL", "IMAP", "imap.gmail.com", 993, "SSL"),
        "googlemail.com" to Servers("smtp.gmail.com", 465, "SSL", "IMAP", "imap.gmail.com", 993, "SSL"),
        "outlook.com" to Servers("smtp-mail.outlook.com", 587, "STARTTLS", "IMAP", "outlook.office365.com", 993, "SSL"),
        "hotmail.com" to Servers("smtp-mail.outlook.com", 587, "STARTTLS", "IMAP", "outlook.office365.com", 993, "SSL"),
        "live.com" to Servers("smtp-mail.outlook.com", 587, "STARTTLS", "IMAP", "outlook.office365.com", 993, "SSL"),
        "msn.com" to Servers("smtp-mail.outlook.com", 587, "STARTTLS", "IMAP", "outlook.office365.com", 993, "SSL"),
        "yahoo.com" to Servers("smtp.mail.yahoo.com", 465, "SSL", "IMAP", "imap.mail.yahoo.com", 993, "SSL"),
        "aol.com" to Servers("smtp.aol.com", 465, "SSL", "IMAP", "imap.aol.com", 993, "SSL"),
        "icloud.com" to Servers("smtp.mail.me.com", 587, "STARTTLS", "IMAP", "imap.mail.me.com", 993, "SSL", userIsLocalPart = true),
        "me.com" to Servers("smtp.mail.me.com", 587, "STARTTLS", "IMAP", "imap.mail.me.com", 993, "SSL", userIsLocalPart = true),
        "mac.com" to Servers("smtp.mail.me.com", 587, "STARTTLS", "IMAP", "imap.mail.me.com", 993, "SSL", userIsLocalPart = true),
        "fastmail.com" to Servers("smtp.fastmail.com", 465, "SSL", "IMAP", "imap.fastmail.com", 993, "SSL"),
        "zoho.com" to Servers("smtp.zoho.com", 465, "SSL", "IMAP", "imap.zoho.com", 993, "SSL"),
        "gmx.com" to Servers("mail.gmx.com", 587, "STARTTLS", "IMAP", "imap.gmx.com", 993, "SSL"),
        "comcast.net" to Servers("smtp.comcast.net", 587, "STARTTLS", "IMAP", "imap.comcast.net", 993, "SSL"),
        "att.net" to Servers("smtp.mail.att.net", 465, "SSL", "IMAP", "imap.mail.att.net", 993, "SSL"),
    )

    /** Looks up the servers for an address: built-in list, then Thunderbird's provider database, then the domain's own autoconfig. */
    suspend fun detect(email: String): Servers? = withContext(Dispatchers.IO) {
        val domain = email.substringAfter('@', "").trim().lowercase()
        if (domain.isEmpty()) return@withContext null
        known[domain]?.let { return@withContext it }
        val urls = listOf(
            "https://autoconfig.thunderbird.net/v1.1/$domain",
            "https://autoconfig.$domain/mail/config-v1.1.xml?emailaddress=$email",
            "https://$domain/.well-known/autoconfig/mail/config-v1.1.xml",
        )
        for (u in urls) {
            val xml = runCatching { Net.get(u) }.getOrNull() ?: continue
            parseAutoconfig(xml)?.let { return@withContext it }
        }
        null
    }

    private fun parseAutoconfig(xml: String): Servers? {
        fun blocks(tag: String) = Regex("<$tag[^>]*type=\"(\\w+)\"[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).findAll(xml).toList()
        fun field(body: String, f: String) = Regex("<$f>\\s*(.*?)\\s*</$f>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1).orEmpty()
        fun sec(v: String) = if (v.equals("SSL", true)) "SSL" else "STARTTLS"
        val out = blocks("outgoingServer").firstOrNull { it.groupValues[1].equals("smtp", true) } ?: return null
        val inc = blocks("incomingServer")
        val imap = inc.firstOrNull { it.groupValues[1].equals("imap", true) }
        val pop = inc.firstOrNull { it.groupValues[1].equals("pop3", true) }
        val inBlock = imap ?: pop
        val ob = out.groupValues[2]
        val ib = inBlock?.groupValues?.get(2).orEmpty()
        return Servers(
            smtpHost = field(ob, "hostname"), smtpPort = field(ob, "port").toIntOrNull() ?: 587, smtpSecurity = sec(field(ob, "socketType")),
            inType = if (imap != null) "IMAP" else if (pop != null) "POP3" else "",
            inHost = field(ib, "hostname"), inPort = field(ib, "port").toIntOrNull() ?: 993, inSecurity = sec(field(ib, "socketType")),
            userIsLocalPart = field(ob, "username").contains("%EMAILLOCALPART%"),
        )
    }
}

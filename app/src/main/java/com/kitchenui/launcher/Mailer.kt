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
 * Sends the shopping list by email over SMTP (implicit TLS on 465, STARTTLS otherwise).
 * Built for Gmail with an app password, but any SMTP server works.
 *
 * To keep the email from getting buried it is sent from your real account (so it passes
 * Gmail's spam checks), flagged high priority, and always uses the same subject start,
 * "KitchenUI shopping list", so a one-time filter can star it and mark it important.
 */
object Mailer {
    const val SUBJECT_TAG = "KitchenUI shopping list"

    class MailException(msg: String) : Exception(msg)

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

    private fun plain(to: Member, items: List<String>): String = buildString {
        append("Hi ${to.name}, here's the shopping list from the kitchen:\n\n")
        items.forEach { append("[ ] ").append(it).append('\n') }
        append("\n").append(filterTip())
    }

    private fun filterTip() =
        "Tip: so these never get filtered, add a filter in Gmail for subject \"$SUBJECT_TAG\" and choose " +
            "\"Never send it to Spam\", \"Always mark it as important\" and \"Star it\"."

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

    private suspend fun send(acct: MailAccount, to: Member, subject: String, text: String, html: String) =
        withContext(Dispatchers.IO) {
            if (!acct.ready) throw MailException("Set up the sending account in Settings first")
            val socket: Socket = if (acct.port == 465) {
                (SSLSocketFactory.getDefault().createSocket() as SSLSocket).apply { connect(InetSocketAddress(acct.host, acct.port), 15_000) }
            } else {
                Socket().apply { connect(InetSocketAddress(acct.host, acct.port), 15_000) }
            }
            socket.soTimeout = 20_000
            var s = socket
            try {
                var r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                var w: OutputStream = s.getOutputStream()
                fun cmd(line: String?, expect: Int): String {
                    if (line != null) { w.write((line + "\r\n").toByteArray(Charsets.UTF_8)); w.flush() }
                    val sb = StringBuilder()
                    while (true) {
                        val l = r.readLine() ?: throw MailException("Mail server closed the connection")
                        sb.append(l).append('\n')
                        if (l.length < 4 || l[3] != '-') break
                    }
                    val code = sb.take(3).toString().toIntOrNull() ?: 0
                    if (code != expect) {
                        val msg = sb.lines().first().drop(4).trim()
                        throw MailException(
                            if (code == 535 || code == 534) "Sign-in refused. Use a Gmail app password, not your normal password."
                            else "Mail server said: $msg"
                        )
                    }
                    return sb.toString()
                }
                cmd(null, 220)
                val ehlo = cmd("EHLO kitchenui.local", 250)
                if (acct.port != 465) {
                    if (!ehlo.contains("STARTTLS")) throw MailException("Server doesn't offer encryption on port ${acct.port}")
                    cmd("STARTTLS", 220)
                    val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(s, acct.host, acct.port, true) as SSLSocket
                    tls.startHandshake()
                    s = tls
                    r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    w = s.getOutputStream()
                    cmd("EHLO kitchenui.local", 250)
                }
                val auth = Base64.encodeToString("\u0000${acct.email}\u0000${acct.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                cmd("AUTH PLAIN $auth", 235)
                cmd("MAIL FROM:<${acct.email}>", 250)
                cmd("RCPT TO:<${to.email.trim()}>", 250)
                cmd("DATA", 354)
                w.write(message(acct, to, subject, text, html).toByteArray(Charsets.UTF_8))
                cmd("\r\n.", 250)
                runCatching { cmd("QUIT", 221) }
            } finally {
                runCatching { s.close() }
                runCatching { socket.close() }
            }
        }

    private fun header(t: String) = "=?UTF-8?B?" + Base64.encodeToString(t.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + "?="

    private fun b64(t: String) = Base64.encodeToString(t.toByteArray(Charsets.UTF_8), Base64.CRLF or Base64.DEFAULT)
        .replace("\r\n", "\n").replace("\n", "\r\n")

    private fun message(acct: MailAccount, to: Member, subject: String, text: String, html: String): String {
        val boundary = "kui-" + UUID.randomUUID().toString()
        val domain = acct.email.substringAfter('@', "kitchenui.local")
        val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date())
        val lines = listOf(
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
        )
        return lines.joinToString("\r\n")
    }
}

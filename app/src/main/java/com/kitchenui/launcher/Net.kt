package com.kitchenui.launcher

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Net {
    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "KitchenUI/${BuildConfig.VERSION_NAME}")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** POSTs a url-encoded form. Returns the body even for error codes, so API error messages can be shown. */
    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): String {
        val body = form.entries.joinToString("&") {
            java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8")
        }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setRequestProperty("User-Agent", "KitchenUI/${BuildConfig.VERSION_NAME}")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val stream = if (c.responseCode in 200..299) c.inputStream else (c.errorStream ?: throw IOException("HTTP ${c.responseCode}"))
            return stream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

package com.kitchenui.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Settings(
    val city: String = "",
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val fahrenheit: Boolean = true,
    val use24h: Boolean = false,
    val icalUrl: String = "",
    val autoUpdate: Boolean = true,
    val pinned: List<String> = emptyList(),
) {
    val hasLocation: Boolean get() = !lat.isNaN() && !lon.isNaN()
}

data class ShopItem(val name: String, val done: Boolean)

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("kitchen", Context.MODE_PRIVATE)

    fun hasPinned(): Boolean = sp.contains("pinned")

    fun load(): Settings = Settings(
        city = sp.getString("city", "") ?: "",
        lat = sp.getString("lat", null)?.toDoubleOrNull() ?: Double.NaN,
        lon = sp.getString("lon", null)?.toDoubleOrNull() ?: Double.NaN,
        fahrenheit = sp.getBoolean("fahrenheit", true),
        use24h = sp.getBoolean("use24h", false),
        icalUrl = sp.getString("ical", "") ?: "",
        autoUpdate = sp.getBoolean("autoUpdate", true),
        pinned = (sp.getString("pinned", "") ?: "").split(",").filter { it.isNotBlank() },
    )

    fun save(s: Settings) {
        sp.edit()
            .putString("city", s.city)
            .putString("lat", if (s.lat.isNaN()) null else s.lat.toString())
            .putString("lon", if (s.lon.isNaN()) null else s.lon.toString())
            .putBoolean("fahrenheit", s.fahrenheit)
            .putBoolean("use24h", s.use24h)
            .putString("ical", s.icalUrl)
            .putBoolean("autoUpdate", s.autoUpdate)
            .putString("pinned", s.pinned.joinToString(","))
            .apply()
    }

    var tonight: String?
        get() = sp.getString("tonight", null)
        set(v) { sp.edit().putString("tonight", v).apply() }

    var paprikaEmail: String
        get() = sp.getString("paprikaEmail", "") ?: ""
        set(v) { sp.edit().putString("paprikaEmail", v).apply() }

    var paprikaToken: String
        get() = sp.getString("paprikaToken", "") ?: ""
        set(v) { sp.edit().putString("paprikaToken", v).apply() }

    var plannedDay: String
        get() = sp.getString("plannedDay", "") ?: ""
        set(v) { sp.edit().putString("plannedDay", v).apply() }

    fun loadShop(): List<ShopItem> {
        val raw = sp.getString("shop", null) ?: return listOf(
            ShopItem("Lemons", false), ShopItem("Fresh thyme", false), ShopItem("Butter", false),
        )
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { val o = a.getJSONObject(it); ShopItem(o.getString("n"), o.optBoolean("d")) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveShop(items: List<ShopItem>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("n", it.name).put("d", it.done)) }
        sp.edit().putString("shop", a.toString()).apply()
    }
}

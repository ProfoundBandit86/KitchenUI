package com.kitchenui.launcher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * Paprika 3 cloud sync (the same account the Paprika apps sync with).
 * Paprika doesn't publish this API; it's the v2 sync API the apps use.
 */
object Paprika {
    private const val API = "https://www.paprikaapp.com/api/v2"
    private fun cache(ctx: Context) = File(ctx.filesDir, "paprika.json")

    class AuthException(msg: String) : IOException(msg)

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")

    private fun result(text: String): Any {
        val j = JSONObject(text)
        j.optJSONObject("error")?.let { throw AuthException(it.optString("message", "Paprika error")) }
        return j.get("result")
    }

    private fun get(path: String, token: String): Any = try {
        result(Net.get("$API$path", auth(token)))
    } catch (e: IOException) {
        if (e.message?.contains("401") == true || e.message?.contains("403") == true) throw AuthException("Paprika sign-in expired")
        throw e
    }

    suspend fun login(email: String, password: String): String = withContext(Dispatchers.IO) {
        val r = result(Net.postForm("$API/account/login/", mapOf("email" to email.trim(), "password" to password)))
        (r as JSONObject).getString("token")
    }

    /** Downloads new and changed recipes (by hash), keeps a local copy, and returns the whole book. */
    suspend fun sync(ctx: Context, token: String): List<Recipe> = withContext(Dispatchers.IO) {
        val old = runCatching { JSONObject(cache(ctx).readText()) }.getOrDefault(JSONObject())
        val oldRecipes = old.optJSONObject("recipes") ?: JSONObject()

        val cats = JSONObject()
        val catArr = get("/sync/categories/", token) as JSONArray
        for (i in 0 until catArr.length()) {
            val c = catArr.getJSONObject(i)
            cats.put(c.optString("uid"), c.optString("name"))
        }

        val list = get("/sync/recipes/", token) as JSONArray
        val wanted = (0 until list.length()).map { list.getJSONObject(it) }
        val gate = Semaphore(6)
        val fetched = coroutineScope {
            wanted.map { entry ->
                async {
                    val uid = entry.getString("uid")
                    val hash = entry.optString("hash")
                    val prev = oldRecipes.optJSONObject(uid)
                    if (prev != null && prev.optString("hash") == hash) {
                        uid to prev
                    } else {
                        gate.withPermit {
                            runCatching { get("/sync/recipe/$uid/", token) as JSONObject }.getOrNull()
                        }?.let { uid to it }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        val recipes = JSONObject()
        fetched.forEach { (uid, r) -> recipes.put(uid, r) }

        val meals = runCatching { get("/sync/meals/", token) as JSONArray }.getOrNull() ?: JSONArray()

        val all = JSONObject().put("categories", cats).put("recipes", recipes).put("meals", meals)
        cache(ctx).writeText(all.toString())
        toRecipes(all)
    }

    fun loadCached(ctx: Context): List<Recipe> =
        runCatching { toRecipes(JSONObject(cache(ctx).readText())) }.getOrDefault(emptyList())

    fun clear(ctx: Context) {
        cache(ctx).delete()
    }

    /** The recipe planned in Paprika's meal planner for today (dinner first). */
    fun todaysMeal(ctx: Context): String? = runCatching {
        val meals = JSONObject(cache(ctx).readText()).optJSONArray("meals") ?: return null
        val today = LocalDate.now().toString()
        val todays = (0 until meals.length()).map { meals.getJSONObject(it) }
            .filter { it.optString("date").startsWith(today) && it.optString("recipe_uid").isNotBlank() }
        val pick = todays.firstOrNull { it.optInt("type", -1) == 2 } ?: todays.lastOrNull()
        pick?.let { "paprika:" + it.getString("recipe_uid") }
    }.getOrNull()

    private val palette = listOf("#5A3328", "#6A2E22", "#3F4F3A", "#7A4A22", "#6B5330", "#4E3A2E", "#3D4560", "#5B4A2A")

    private fun toRecipes(all: JSONObject): List<Recipe> {
        val cats = all.optJSONObject("categories") ?: JSONObject()
        val recipes = all.optJSONObject("recipes") ?: return emptyList()
        val out = mutableListOf<Recipe>()
        for (uid in recipes.keys()) {
            val r = recipes.getJSONObject(uid)
            if (r.optBoolean("in_trash")) continue
            val catNames = r.optJSONArray("categories")?.let { a ->
                (0 until a.length()).mapNotNull { cats.optString(a.optString(it)).takeIf { n -> n.isNotBlank() } }
            } ?: emptyList()
            val prep = minutes(r.optString("prep_time"))
            val cook = minutes(r.optString("cook_time"))
            val total = minutes(r.optString("total_time"))
            val facts = mutableListOf<Pair<String, String>>()
            r.optString("source").takeIf { it.isNotBlank() }?.let { facts.add("Source" to it) }
            if (r.optInt("rating") > 0) facts.add("Rating" to "★".repeat(r.optInt("rating")))
            r.optString("notes").takeIf { it.isNotBlank() }?.let { facts.add("Notes" to it.trim().take(160)) }
            out.add(
                Recipe(
                    id = "paprika:$uid",
                    name = r.optString("name", "Untitled"),
                    category = catNames.firstOrNull() ?: "Recipes",
                    prepMin = prep,
                    cookMin = if (cook == 0 && total > prep) total - prep else cook,
                    serves = r.optString("servings").trim(),
                    color = android.graphics.Color.parseColor(palette[Math.floorMod(uid.hashCode(), palette.size)]),
                    ingredients = r.optString("ingredients").lines().map { it.trim() }.filter { it.isNotEmpty() }
                        .map { Ingredient("", it) },
                    steps = directions(r.optString("directions")),
                    facts = facts,
                    photoUrl = r.optString("photo_url").ifBlank { r.optString("image_url") }.ifBlank { null },
                )
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /** "1 hr 15 mins", "45 minutes", "1:30" → minutes. */
    fun minutes(s: String): Int {
        val t = s.lowercase()
        Regex("^\\s*(\\d+):(\\d{2})\\s*$").find(t)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        val h = Regex("(\\d+(?:\\.\\d+)?)\\s*(h|hr|hrs|hour|hours)\\b").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val m = Regex("(\\d+)\\s*(m|min|mins|minute|minutes)\\b").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (h == 0.0 && m == 0) return Regex("^\\s*(\\d+)\\s*$").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return (h * 60).toInt() + m
    }

    private fun directions(text: String): List<Step> {
        val parts = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val lines = if (parts.size >= 2) parts else text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.map { raw ->
            val clean = raw.replace(Regex("^(step\\s*)?\\d+[.):]\\s*", RegexOption.IGNORE_CASE), "").replace('\n', ' ')
            Step(clean, "", stepTimer(clean))
        }.ifEmpty { listOf(Step("No directions in Paprika for this recipe.", "", 0)) }
    }

    /** First "10 minutes" / "1 hour" in a step becomes its timer. */
    private fun stepTimer(s: String): Int {
        val t = s.lowercase()
        Regex("(\\d+)\\s*(?:to|-|–)?\\s*(\\d+)?\\s*(hours?|hrs?)\\b").find(t)?.let {
            val n = (it.groupValues[2].ifEmpty { it.groupValues[1] }).toInt()
            return (n * 60).coerceAtMost(600)
        }
        Regex("(\\d+)\\s*(?:to|-|–)?\\s*(\\d+)?\\s*(minutes?|mins?)\\b").find(t)?.let {
            val n = (it.groupValues[2].ifEmpty { it.groupValues[1] }).toInt()
            return n.coerceAtMost(600)
        }
        return 0
    }
}

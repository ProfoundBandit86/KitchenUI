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
 * Paprika doesn't publish this API; it's the sync API the apps use (v1 with Basic auth, or v2).
 */
object Paprika {
    private const val HOST = "https://www.paprikaapp.com/api"
    private fun cache(ctx: Context) = File(ctx.filesDir, "paprika.json")
    private fun photos(ctx: Context) = File(ctx.filesDir, "paprika-photos").apply { mkdirs() }

    /** optString turns JSON null into the text "null"; this doesn't. */
    private fun JSONObject.str(k: String): String = if (!has(k) || isNull(k)) "" else optString(k).trim()

    private fun photoFile(ctx: Context, uid: String, r: JSONObject): File {
        val tag = r.str("photo_hash").ifBlank { r.str("photo") }.ifBlank { "p" }.replace(Regex("[^A-Za-z0-9._-]"), "")
        return File(photos(ctx), "$uid-$tag.jpg")
    }

    private fun hasPhoto(r: JSONObject) = r.str("photo_url").startsWith("http") || r.str("photo").isNotBlank()

    /** Paprika's photo links expire, so photos are downloaded once and kept on the device. */
    private fun download(url: String, to: File): Boolean = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "KitchenUI/1.0 (Android)")
        try {
            if (c.responseCode !in 200..299) return@runCatching false
            val tmp = File(to.path + ".part")
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.length() > 0 && tmp.renameTo(to)
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    class AuthException(msg: String) : IOException(msg)

    /**
     * The saved credential is "<api version>|<Authorization header>", e.g. "v1|Basic …" or "v2|Bearer …".
     * Paprika now answers "Unrecognized client" to v2 logins from anything but its own apps,
     * so v1 with Basic auth is tried first.
     */
    private fun parts(cred: String): Pair<String, String> {
        val i = cred.indexOf('|')
        return if (i < 0) "v2" to "Bearer $cred" else cred.substring(0, i) to cred.substring(i + 1)
    }

    private fun result(text: String): Any {
        val j = JSONObject(text)
        j.optJSONObject("error")?.let { throw AuthException(it.optString("message", "Paprika error")) }
        return j.get("result")
    }

    private fun get(path: String, cred: String): Any {
        val (ver, header) = parts(cred)
        return try {
            result(Net.get("$HOST/$ver$path", mapOf("Authorization" to header)))
        } catch (e: IOException) {
            if (e.message?.contains("401") == true || e.message?.contains("403") == true) throw AuthException("Paprika sign-in failed or expired")
            throw e
        }
    }

    /** Returns the credential to save. */
    suspend fun login(email: String, password: String): String = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        // 1. v1 + Basic auth (what Paprika still accepts from third-party apps)
        val basic = "v1|Basic " + android.util.Base64.encodeToString(
            "${email.trim()}:$password".toByteArray(), android.util.Base64.NO_WRAP,
        )
        try {
            get("/sync/categories/", basic)
            return@withContext basic
        } catch (e: Exception) { errors.add("v1: ${e.message}") }
        // 2. v1 token login, 3. v2 token login
        for (ver in listOf("v1", "v2")) {
            try {
                val r = result(Net.postForm("$HOST/$ver/account/login/", mapOf("email" to email.trim(), "password" to password)))
                val cred = "$ver|Bearer " + (r as JSONObject).getString("token")
                get("/sync/categories/", cred)
                return@withContext cred
            } catch (e: Exception) { errors.add("$ver: ${e.message}") }
        }
        throw AuthException(errors.joinToString("; "))
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
        // Photos: download any that aren't on the device yet. A cached recipe's link may have
        // expired, so it is fetched again for a fresh one.
        val withPhotos = coroutineScope {
            fetched.map { (uid, r0) ->
                async {
                    gate.withPermit {
                        var r = r0
                        if (hasPhoto(r) && !r.optBoolean("in_trash")) {
                            val f = photoFile(ctx, uid, r)
                            if (!f.exists()) {
                                var ok = r.str("photo_url").startsWith("http") && download(r.str("photo_url"), f)
                                if (!ok) {
                                    runCatching { get("/sync/recipe/$uid/", token) as JSONObject }.getOrNull()?.let { fresh ->
                                        r = fresh
                                        val url = fresh.str("photo_url")
                                        if (url.startsWith("http")) ok = download(url, photoFile(ctx, uid, fresh))
                                    }
                                }
                            }
                        }
                        uid to r
                    }
                }
            }.awaitAll()
        }
        val recipes = JSONObject()
        withPhotos.forEach { (uid, r) -> recipes.put(uid, r) }
        // Remove photos of deleted or changed recipes
        val keep = withPhotos.map { (uid, r) -> photoFile(ctx, uid, r).name }.toSet()
        photos(ctx).listFiles()?.forEach { if (it.name !in keep) it.delete() }

        val meals = runCatching { get("/sync/meals/", token) as JSONArray }.getOrNull() ?: JSONArray()

        val all = JSONObject().put("categories", cats).put("recipes", recipes).put("meals", meals)
        cache(ctx).writeText(all.toString())
        toRecipes(ctx, all)
    }

    fun loadCached(ctx: Context): List<Recipe> =
        runCatching { toRecipes(ctx, JSONObject(cache(ctx).readText())) }.getOrDefault(emptyList())

    fun clear(ctx: Context) {
        cache(ctx).delete()
        photos(ctx).deleteRecursively()
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

    private fun toRecipes(ctx: Context, all: JSONObject): List<Recipe> {
        val cats = all.optJSONObject("categories") ?: JSONObject()
        val recipes = all.optJSONObject("recipes") ?: return emptyList()
        val out = mutableListOf<Recipe>()
        for (uid in recipes.keys()) {
            val r = recipes.getJSONObject(uid)
            if (r.optBoolean("in_trash")) continue
            val catNames = r.optJSONArray("categories")?.let { a ->
                (0 until a.length()).mapNotNull { cats.optString(a.optString(it)).takeIf { n -> n.isNotBlank() } }
            } ?: emptyList()
            val prep = minutes(r.str("prep_time"))
            val cook = minutes(r.str("cook_time"))
            val total = minutes(r.str("total_time"))
            val facts = mutableListOf<Pair<String, String>>()
            r.str("source").takeIf { it.isNotBlank() }?.let { facts.add("Source" to it) }
            if (r.optInt("rating") > 0) facts.add("Rating" to "★".repeat(r.optInt("rating")))
            r.str("notes").takeIf { it.isNotBlank() }?.let { facts.add("Notes" to it.trim().take(160)) }
            out.add(
                Recipe(
                    id = "paprika:$uid",
                    name = r.str("name").ifBlank { "Untitled" },
                    category = catNames.firstOrNull() ?: "Recipes",
                    prepMin = prep,
                    cookMin = if (cook == 0 && total > prep) total - prep else cook,
                    serves = r.str("servings").trim(),
                    color = android.graphics.Color.parseColor(palette[Math.floorMod(uid.hashCode(), palette.size)]),
                    ingredients = r.str("ingredients").lines().map { it.trim() }.filter { it.isNotEmpty() }
                        .map { Ingredient("", it) },
                    steps = directions(r.str("directions")),
                    facts = facts,
                    photoUrl = photoFile(ctx, uid, r).takeIf { it.exists() }?.let { "file://" + it.absolutePath }
                        ?: r.str("image_url").takeIf { it.startsWith("http") },
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

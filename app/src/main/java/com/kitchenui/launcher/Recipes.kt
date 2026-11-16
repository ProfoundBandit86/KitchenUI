package com.kitchenui.launcher

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class Ingredient(val qty: String, val name: String)
data class Step(val text: String, val tip: String, val timerMin: Int)
data class Recipe(
    val id: String,
    val name: String,
    val category: String,
    val prepMin: Int,
    val cookMin: Int,
    val serves: String,
    val color: Int,
    val ingredients: List<Ingredient>,
    val steps: List<Step>,
    val facts: List<Pair<String, String>>,
    val photoUrl: String? = null,
) {
    val totalMin: Int get() = prepMin + cookMin
}

fun formatMinutes(m: Int): String = when {
    m < 60 -> "$m min"
    m % 60 == 0 -> "${m / 60} hr"
    else -> "${m / 60} hr ${m % 60} min"
}

/**
 * Recipes live in app/src/main/assets/recipes.json in the GitHub repo.
 * The app ships with that file, and also re-downloads it from the repo's main branch,
 * so editing recipes on GitHub updates the kitchen screen without a new APK.
 */
object RecipeRepo {
    private val remote = "https://raw.githubusercontent.com/${BuildConfig.UPDATE_REPO}/main/app/src/main/assets/recipes.json"
    private fun cache(ctx: Context) = File(ctx.filesDir, "recipes.json")

    fun parse(json: String): List<Recipe> {
        val arr = JSONObject(json).getJSONArray("recipes")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ing = o.getJSONArray("ingredients")
            val steps = o.getJSONArray("steps")
            val facts = o.optJSONArray("facts")
            Recipe(
                id = o.getString("id"),
                name = o.getString("name"),
                category = o.optString("category", "Other"),
                prepMin = o.optInt("prep"),
                cookMin = o.optInt("cook"),
                serves = o.optString("serves"),
                color = runCatching { android.graphics.Color.parseColor(o.optString("color", "#4E3A2E")) }
                    .getOrDefault(0xFF4E3A2E.toInt()),
                ingredients = (0 until ing.length()).map {
                    val a = ing.getJSONArray(it)
                    Ingredient(a.optString(0), a.optString(1))
                },
                steps = (0 until steps.length()).map {
                    val s = steps.getJSONObject(it)
                    Step(s.getString("text"), s.optString("tip"), s.optInt("timer"))
                },
                facts = if (facts == null) emptyList() else (0 until facts.length()).map {
                    val a = facts.getJSONArray(it)
                    a.optString(0) to a.optString(1)
                },
                photoUrl = o.optString("photo").ifBlank { null },
            )
        }
    }

    fun loadLocal(ctx: Context): List<Recipe> {
        val f = cache(ctx)
        if (f.exists()) {
            runCatching { parse(f.readText()) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return runCatching { parse(ctx.assets.open("recipes.json").bufferedReader().use { it.readText() }) }
            .getOrDefault(emptyList())
    }

    suspend fun refresh(ctx: Context): List<Recipe>? = withContext(Dispatchers.IO) {
        try {
            val text = Net.get(remote)
            val list = parse(text)
            if (list.isEmpty()) return@withContext null
            cache(ctx).writeText(text)
            list
        } catch (_: Exception) {
            null
        }
    }
}

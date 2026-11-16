package com.kitchenui.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

data class AppEntry(val label: String, val pkg: String, val cls: String, val icon: ImageBitmap)

/** Dock entries that aren't apps. */
const val DOCK_WATCH = "@watch"
const val DOCK_LIVE = "@live"

object Apps {
    private val preferred = listOf(
        "com.google.android.youtube.tv", "com.google.android.youtube",
        "com.netflix.ninja", "com.netflix.mediaclient",
        "com.plexapp.android",
        "com.spotify.tv.android", "com.spotify.music",
        "com.android.chrome", "org.mozilla.firefox", "org.chromium.chrome",
        "com.google.android.apps.photos", "com.android.gallery3d",
        "com.android.vending",
    )

    fun load(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val list: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        return list
            .filter { it.activityInfo.packageName != ctx.packageName }
            .mapNotNull { ri ->
                runCatching {
                    AppEntry(
                        label = ri.loadLabel(pm).toString(),
                        pkg = ri.activityInfo.packageName,
                        cls = ri.activityInfo.name,
                        icon = ri.loadIcon(pm).toBitmap(128, 128).asImageBitmap(),
                    )
                }.getOrNull()
            }
            .distinctBy { it.pkg + "/" + it.cls }
            .sortedBy { it.label.lowercase() }
    }

    fun defaultPins(apps: List<AppEntry>): List<String> {
        val have = apps.map { it.pkg }.toSet()
        val first = preferred.filter { it in have }
        return listOf(DOCK_WATCH, DOCK_LIVE) + (first + apps.map { it.pkg }).distinct().take(7)
    }

    fun launch(ctx: Context, app: AppEntry) {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.pkg, app.cls)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }
}

package com.kitchenui.launcher

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class KitchenTimer(val id: Int, val label: String, val key: String?, totalMs: Long, now: Long) {
    var totalMs by mutableLongStateOf(totalMs)
    var remainingMs by mutableLongStateOf(totalMs)
    var running by mutableStateOf(true)
    var endAt by mutableLongStateOf(now + totalMs)
    var doneAt by mutableLongStateOf(0L)
    val done: Boolean get() = remainingMs <= 0L
}

/** Kitchen countdown timers with a repeating beep when one finishes (for up to 3 minutes). */
class TimerManager {
    val timers = mutableStateListOf<KitchenTimer>()
    private var nextId = 1
    private var lastBeep = 0L
    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()

    fun add(label: String, minutes: Int, key: String? = null, now: Long = System.currentTimeMillis()): KitchenTimer {
        val t = KitchenTimer(nextId++, label, key, minutes * 60_000L, now)
        timers.add(t)
        while (timers.size > 4) timers.removeAt(0)
        return t
    }

    fun byKey(key: String): KitchenTimer? = timers.lastOrNull { it.key == key }

    fun toggle(t: KitchenTimer, now: Long = System.currentTimeMillis()) {
        if (t.done) return
        if (t.running) {
            t.remainingMs = (t.endAt - now).coerceAtLeast(0L)
            t.running = false
        } else {
            t.endAt = now + t.remainingMs
            t.running = true
        }
    }

    fun addMinute(t: KitchenTimer, now: Long = System.currentTimeMillis()) {
        if (t.done) {
            t.remainingMs = 60_000L
            t.endAt = now + 60_000L
            t.running = true
            t.doneAt = 0L
        } else {
            t.remainingMs += 60_000L
            t.endAt += 60_000L
        }
        t.totalMs = maxOf(t.totalMs, t.remainingMs)
    }

    fun reset(t: KitchenTimer) {
        t.remainingMs = t.totalMs
        t.running = false
        t.doneAt = 0L
    }

    fun remove(t: KitchenTimer) { timers.remove(t) }

    val anyRunning: Boolean get() = timers.any { it.running }

    fun tick(now: Long) {
        var ringing = false
        for (t in timers) {
            if (t.running) {
                t.remainingMs = (t.endAt - now).coerceAtLeast(0L)
                if (t.remainingMs == 0L) {
                    t.running = false
                    t.doneAt = now
                }
            }
            if (t.done && t.doneAt > 0L && now - t.doneAt < 180_000L) ringing = true
        }
        if (ringing && now - lastBeep > 2_000L) {
            lastBeep = now
            tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1_200)
        }
    }

    fun silence() {
        timers.forEach { if (it.done) it.doneAt = 0L }
    }
}

fun formatClock(ms: Long): String {
    val sec = (ms + 999) / 1000
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

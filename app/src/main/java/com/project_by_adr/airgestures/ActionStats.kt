package com.project_by_adr.airgestures

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Counts every action (saved on the phone) and remembers the latest ones (this session). */
object ActionStats {

    private const val FILE = "air_gestures_stats"
    private const val MAX_RECENT = 15

    private val recent = ArrayList<String>()

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun record(context: Context, text: String) {
        val p = prefs(context)
        p.edit().putInt("total", p.getInt("total", 0) + 1).apply()
        val time = SimpleDateFormat("h:mm:ss a", Locale.getDefault()).format(Date())
        recent.add(0, "$time   $text")
        while (recent.size > MAX_RECENT) recent.removeAt(recent.size - 1)
    }

    fun total(context: Context): Int = prefs(context).getInt("total", 0)

    fun recent(): List<String> = recent.toList()

    fun reset(context: Context) {
        prefs(context).edit().putInt("total", 0).apply()
        recent.clear()
    }
}
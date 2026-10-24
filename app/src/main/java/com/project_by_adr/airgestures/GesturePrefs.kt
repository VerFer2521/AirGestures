package com.project_by_adr.airgestures

import android.content.Context

object GesturePrefs {

    const val DEFAULT_HOLD_MS = 400
    const val DEFAULT_REPEAT_MS = 900

    private const val FILE = "air_gestures_prefs"

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun holdMs(context: Context): Int = prefs(context).getInt("hold_ms", DEFAULT_HOLD_MS)

    fun repeatMs(context: Context): Int = prefs(context).getInt("repeat_ms", DEFAULT_REPEAT_MS)

    fun setHoldMs(context: Context, value: Int) {
        prefs(context).edit().putInt("hold_ms", value).apply()
    }

    fun setRepeatMs(context: Context, value: Int) {
        prefs(context).edit().putInt("repeat_ms", value).apply()
    }

    // ---- which action each gesture triggers ----

    fun actionFor(context: Context, pose: String): GestureEngine.Action {
        val saved = prefs(context).getString("map_$pose", null)
        // actions that were removed from the Settings list (volume is now pinch + drag) fall back to the default
        return GestureEngine.Action.values().firstOrNull { it.name == saved && it.shown }
            ?: GestureEngine.DEFAULT_MAPPING.getValue(pose)
    }

    fun setAction(context: Context, pose: String, action: GestureEngine.Action) {
        prefs(context).edit().putString("map_$pose", action.name).apply()
    }

    fun resetMapping(context: Context) {
        val editor = prefs(context).edit()
        for (pose in GestureEngine.DEFAULT_MAPPING.keys) editor.remove("map_$pose")
        editor.apply()
    }

    // ---- sound + first-launch tour ----

    fun soundOn(context: Context): Boolean = prefs(context).getBoolean("sound_on", true)

    fun setSoundOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("sound_on", value).apply()
    }

    fun onboardingDone(context: Context): Boolean = prefs(context).getBoolean("onboarding_done", false)

    fun setOnboardingDone(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("onboarding_done", value).apply()
    }

    // ---- game ----

    fun bestScore(context: Context): Int = prefs(context).getInt("best_score", 0)

    fun setBestScore(context: Context, value: Int) {
        prefs(context).edit().putInt("best_score", value).apply()
    }

    /** Copies the saved settings into an engine. */
    fun applyTo(context: Context, engine: GestureEngine) {
        engine.holdMs = holdMs(context).toLong()
        engine.repeatMs = repeatMs(context).toLong()
        engine.mapping = GestureEngine.DEFAULT_MAPPING.keys.associateWith { actionFor(context, it) }
    }
}
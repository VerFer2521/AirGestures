package com.project_by_adr.airgestures

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator

/** A short, quiet tick when an action happens. Controlled by the switch in Settings. */
object SoundFx {

    private var tone: ToneGenerator? = null

    fun tick(context: Context) {
        if (!GesturePrefs.soundOn(context)) return
        try {
            if (tone == null) tone = ToneGenerator(AudioManager.STREAM_SYSTEM, 45)
            tone?.startTone(ToneGenerator.TONE_PROP_ACK, 90)
        } catch (e: Exception) {
            // sound is a bonus: never let it crash the app
        }
    }

    fun release() {
        tone?.release()
        tone = null
    }
}
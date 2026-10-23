package com.project_by_adr.airgestures

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

object Speaker {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun speak(context: Context, text: String) {
        val engine = tts
        if (engine != null && ready) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "airgestures")
            return
        }
        pending = text
        if (engine == null) {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    ready = true
                    pending?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "airgestures") }
                    pending = null
                }
            }
        }
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
        pending = null
    }
}
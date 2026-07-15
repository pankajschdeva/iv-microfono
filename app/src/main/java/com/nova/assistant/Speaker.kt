package com.nova.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Small shared text-to-speech helper usable from anywhere. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val hi = tts?.isLanguageAvailable(Locale("hi", "IN"))
                tts?.language = if (hi == TextToSpeech.LANG_AVAILABLE ||
                    hi == TextToSpeech.LANG_COUNTRY_AVAILABLE
                ) Locale("hi", "IN") else Locale.US
                ready = true
                pending.forEach { speak(it) }
                pending.clear()
            }
        }
    }

    fun say(context: Context, text: String) {
        if (tts == null) init(context)
        if (ready) speak(text) else pending.add(text)
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt")
    }
}

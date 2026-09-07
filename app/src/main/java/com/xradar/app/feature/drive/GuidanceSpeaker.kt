package com.xradar.app.feature.drive

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Thin French text-to-speech wrapper for turn-by-turn voice. Owned by the
 * [DriveViewModel]; call [shutdown] when the ViewModel is cleared. Speaks over
 * the navigation-guidance audio channel so it ducks music instead of pausing it.
 */
class GuidanceSpeaker(context: Context) {

    @Volatile private var ready = false
    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = engine?.setLanguage(Locale.FRENCH)
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                engine?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
        }
    }

    /** Speak now, interrupting any in-progress instruction. */
    fun speak(text: String) {
        val e = engine ?: return
        if (!ready || text.isBlank()) return
        e.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
    }

    fun stop() {
        engine?.stop()
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }
}

package com.eona.app.feature.drive

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Thin French text-to-speech wrapper for turn-by-turn voice. Owned by the
 * [DriveViewModel]; call [shutdown] when the ViewModel is cleared. Speaks over
 * the navigation-guidance audio channel so it ducks music instead of pausing it.
 */
class GuidanceSpeaker(context: Context) {

    @Volatile private var ready = false
    private var engine: TextToSpeech? = null
    /** A phrase that must be heard to the end (its utterance id): the next ones wait behind it. */
    @Volatile private var wholeId: String? = null
    private var said = 0L

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val tts = engine
                val r = tts?.setLanguage(Locale.FRANCE)
                ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                if (tts != null) {
                    softFrenchVoice(tts)?.let { tts.voice = it }
                    // A touch slower than the default, for a calmer voice.
                    tts.setSpeechRate(SPEECH_RATE)
                }
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = ended(utteranceId)
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = ended(utteranceId)
                    override fun onStop(utteranceId: String?, interrupted: Boolean) = ended(utteranceId)
                })
                engine?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
            }
        }
    }

    /**
     * Speak now at [volume] (0..1: "Volume Guidage" or "Volume alertes", as the phrase is),
     * interrupting any in-progress instruction, unless a phrase said [whole] is still going:
     * then this one waits behind it.
     */
    fun speak(text: String, volume: Float, whole: Boolean = false) {
        val e = engine ?: return
        if (!ready || text.isBlank()) return
        val id = "${if (whole) "whole" else "say"}-${said++}"
        val mode = if (wholeId != null && e.isSpeaking) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        if (whole) wholeId = id
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f)) }
        e.speak(text, mode, params, id)
    }

    private fun ended(utteranceId: String?) {
        if (utteranceId != null && utteranceId == wholeId) wholeId = null
    }

    /** A phrase is being said: the proximity beeps wait. */
    val isSpeaking: Boolean get() = engine?.isSpeaking == true

    fun stop() {
        wholeId = null
        engine?.stop()
    }

    /**
     * A soft French woman's voice: an installed voice that works offline, France first, a known
     * female one preferred (Android exposes no gender; Google's French voices "fra", "frc" and
     * "vlf" are women), then the best quality. Null keeps the engine's default voice.
     */
    private fun softFrenchVoice(tts: TextToSpeech): Voice? {
        val voices = runCatching { tts.voices }.getOrNull().orEmpty().filter { voice ->
            voice.locale.language == "fr" &&
                !voice.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
        }
        return voices.maxWithOrNull(
            compareBy<Voice>(
                { it.locale.country == "FR" },
                { voice -> FEMALE_HINTS.any { voice.name.lowercase().contains(it) } },
                { it.quality },
            ),
        )
    }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    private companion object {
        const val SPEECH_RATE = 0.94f
        val FEMALE_HINTS = listOf("female", "-fra-", "-frc-", "-vlf-")
    }
}

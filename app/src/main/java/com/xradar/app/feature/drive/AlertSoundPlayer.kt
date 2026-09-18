package com.xradar.app.feature.drive

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.xradar.app.R

/** The alert sounds, radar-detector style (the same files as the iOS app). */
enum class AlertSound(val res: Int) {
    /** A speed-enforcement alert shows up: the detector's K/Ka band chirps. */
    Detector(R.raw.alert_detector),
    /** One proximity beep; they come faster as the radar nears. */
    Beep(R.raw.alert_beep),
    /** Right at the radar: the laser burst. */
    Laser(R.raw.alert_laser),
    /** A road hazard shows up: a two-note chime. */
    Hazard(R.raw.alert_hazard),
    /** Over the speed limit: a rising "bi-bip", lower and buzzier than the proximity beep. */
    Overspeed(R.raw.alert_overspeed),
}

/**
 * Plays [AlertSound]s over the music, lowered meanwhile (transient ducking focus, let go a
 * moment after the last sound so a run of beeps does not make the music pump), with a short
 * vibration when the driver wants it. Call from the main thread; [release] when done.
 */
class AlertSoundPlayer(context: Context) {
    private val app = context.applicationContext
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build()
    private val ids = AlertSound.entries.associateWith { pool.load(app, it.res, 1) }
    private val durations = AlertSound.entries.associateWith { durationMs(it.res) }
    private val audio = app.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()
    private var holding = false
    private val handler = Handler(Looper.getMainLooper())
    private val letGo = Runnable {
        holding = false
        audio?.abandonAudioFocusRequest(focus)
    }
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        app.getSystemService(Vibrator::class.java)
    }

    /** [volume] (0..1): "Volume alertes". */
    fun play(sound: AlertSound, vibrate: Boolean, volume: Float) {
        val id = ids[sound] ?: return
        handler.removeCallbacks(letGo)
        if (!holding) {
            holding = true
            audio?.requestAudioFocus(focus)
        }
        val level = volume.coerceIn(0f, 1f)
        pool.play(id, level, level, 1, 0, 1f)
        handler.postDelayed(letGo, (durations[sound] ?: DEFAULT_DURATION_MS) + RELEASE_DELAY_MS)
        if (vibrate) {
            vibrator?.vibrate(
                if (sound == AlertSound.Beep) {
                    VibrationEffect.createOneShot(30, 180)
                } else {
                    VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1)
                },
            )
        }
    }

    fun release() {
        handler.removeCallbacks(letGo)
        if (holding) letGo.run()
        pool.release()
    }

    private fun durationMs(res: Int): Long = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(app, Uri.parse("android.resource://${app.packageName}/$res"))
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } finally {
                release()
            }
        }
    }.getOrNull() ?: DEFAULT_DURATION_MS

    private companion object {
        const val DEFAULT_DURATION_MS = 1_000L
        const val RELEASE_DELAY_MS = 1_200L
    }
}

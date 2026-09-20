package com.eona.app.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Music already playing in any player (Spotify, Apple Music, Deezer, a local files player...),
 * through the Android media APIs only (no SDK, no account). [state] watches the sessions only while someone collects it:
 * the listener and every controller callback are registered on the first collector and
 * removed when the last one leaves. App-scoped; init once from a Context.
 */
object MediaRepository {

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val rechecks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The session the controls act on; null when nothing is shown. Main thread. */
    @Volatile
    private var controller: MediaController? = null

    /** The last of the three apps seen with a session, in memory only. */
    private var lastActiveApp: MusicApp? = null

    /** The last player of any kind seen with a session, in memory only. */
    private var lastActivePackage: String? = null

    /**
     * The last track shown and its player, in memory only. Many players (local files ones
     * especially) hide their session or empty it on pause: the banner keeps this track,
     * paused, instead of going blank until play.
     */
    internal var lastTrack: MediaPlaybackState.Active? = null
    internal var lastTrackPackage: String? = null

    /** Opens a music app if a play with no session started nothing. */
    private var resumeFallback: Job? = null

    val state: StateFlow<MediaPlaybackState> by lazy {
        sessions().stateIn(scope, SharingStarted.WhileSubscribed(), initialState())
    }

    /** Check notification access again (e.g. back from Android's settings). */
    fun refresh() {
        rechecks.tryEmit(Unit)
    }

    /**
     * Play or pause the session shown. With none shown: pause when audio still plays (a player
     * whose session cannot be read), else start the last player like a headset would.
     */
    fun playPause() {
        if (controller == null) {
            val audio = appContext?.getSystemService(AudioManager::class.java)
            if (audio?.isMusicActive == true) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE) else resumeLastPlayer()
            return
        }
        control { c ->
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                c.transportControls.pause()
            } else {
                c.transportControls.play()
            }
        }
    }

    fun next() {
        if (controller == null) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) else control { it.transportControls.skipToNext() }
    }

    fun previous() {
        if (controller == null) dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) else control { it.transportControls.skipToPrevious() }
    }

    /** Open a music app; silently nothing when it is not installed. */
    fun launch(app: MusicApp) {
        val context = appContext ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Android's notification access screen, where the driver grants the permission. */
    fun openAccessSettings() {
        val context = appContext ?: return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Nothing to control: send PLAY the way a Bluetooth headset does, so Android restarts
     * the last media player in the background. If no player shows a session and
     * no audio plays after [RESUME_FALLBACK_MS], open the last one used (or the first
     * installed) so the driver can start it there.
     */
    private fun resumeLastPlayer() {
        val context = appContext ?: return
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        resumeFallback?.cancel()
        resumeFallback = scope.launch {
            delay(RESUME_FALLBACK_MS)
            val audio = context.getSystemService(AudioManager::class.java)
            if (controller != null || audio?.isMusicActive == true) return@launch
            // The player of the track still on the banner (a local files one too) comes first.
            val lastPlayer = lastActivePackage?.let { context.packageManager.getLaunchIntentForPackage(it) }
            if (lastPlayer != null) {
                runCatching { context.startActivity(lastPlayer.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return@launch
            }
            val installed = installedApps(context)
            val app = lastActiveApp?.takeIf { it in installed } ?: installed.firstOrNull()
            app?.let(::launch)
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        val audio = appContext?.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    private inline fun control(action: (MediaController) -> Unit) {
        val c = controller ?: return
        try {
            action(c)
        } catch (e: SecurityException) {
            // Access revoked while the banner was open: show the permission state again.
            refresh()
        }
    }

    private fun initialState(): MediaPlaybackState {
        val context = appContext ?: return MediaPlaybackState.PermissionMissing
        if (!hasAccess(context)) return MediaPlaybackState.PermissionMissing
        return lastTrack?.copy(isPlaying = false) ?: MediaPlaybackState.Idle(installedApps(context))
    }

    private fun sessions(): Flow<MediaPlaybackState> = callbackFlow {
        val context = appContext
        val manager = context?.getSystemService(MediaSessionManager::class.java)
        if (context == null || manager == null) {
            trySend(MediaPlaybackState.PermissionMissing)
            awaitClose()
        } else {
            val watcher = SessionWatcher(
                context = context,
                manager = manager,
                scope = this,
                publishState = { trySend(it) },
                onController = { shown ->
                    controller = shown
                    if (shown != null) {
                        lastActivePackage = shown.packageName
                        lastActiveApp = MusicApp.of(shown.packageName) ?: lastActiveApp
                        resumeFallback?.cancel()
                    }
                },
            )
            watcher.connect()
            val recheckJob = launch { rechecks.collect { watcher.connect() } }
            awaitClose {
                recheckJob.cancel()
                watcher.disconnect()
            }
        }
    }

    internal fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    internal fun installedApps(context: Context): List<MusicApp> =
        MusicApp.entries.filter { context.packageManager.getLaunchIntentForPackage(it.packageName) != null }

    internal val artClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /** How long a play with no session waits for a player before opening a music app. */
    private const val RESUME_FALLBACK_MS = 3_000L
}

/**
 * One subscription's watch over the media sessions. Every call happens on the main thread:
 * the collector runs there and every listener is registered with a main-looper handler.
 */
private class SessionWatcher(
    private val context: Context,
    private val manager: MediaSessionManager,
    private val scope: CoroutineScope,
    private val publishState: (MediaPlaybackState) -> Unit,
    private val onController: (MediaController?) -> Unit,
) {
    private val component = ComponentName(context, MediaListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val watched = LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var installed: List<MusicApp> = emptyList()
    private var artKey: String? = null
    private var art: Bitmap? = null
    private var artJob: Job? = null

    /** (Re)read access and the sessions; safe to call again at any time. */
    fun connect() {
        if (!MediaRepository.hasAccess(context)) {
            revoked()
            return
        }
        installed = MediaRepository.installedApps(context)
        try {
            if (sessionsListener == null) {
                val listener = MediaSessionManager.OnActiveSessionsChangedListener { controllers -> watch(controllers) }
                manager.addOnActiveSessionsChangedListener(listener, component, handler)
                sessionsListener = listener
            }
            watch(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            revoked()
        }
    }

    /** Remove the sessions listener and every controller callback. */
    fun disconnect() {
        sessionsListener?.let { listener -> runCatching { manager.removeOnActiveSessionsChangedListener(listener) } }
        sessionsListener = null
        watched.values.forEach { (controller, callback) -> controller.unregisterCallback(callback) }
        watched.clear()
        artJob?.cancel()
        artJob = null
        artKey = null
        art = null
        onController(null)
    }

    private fun revoked() {
        disconnect()
        publishState(MediaPlaybackState.PermissionMissing)
    }

    /** Follow the sessions of every player but EONA itself, in the order the system gives them. */
    private fun watch(controllers: List<MediaController>?) {
        val wanted = controllers.orEmpty().filter { it.packageName != context.packageName }
        val tokens = wanted.mapTo(HashSet()) { it.sessionToken }
        watched.entries.filter { it.key !in tokens }.forEach { (token, entry) ->
            entry.first.unregisterCallback(entry.second)
            watched.remove(token)
        }
        val ordered = LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
        for (controller in wanted) {
            ordered[controller.sessionToken] = watched[controller.sessionToken]
                ?: (controller to callbackFor(controller).also { controller.registerCallback(it, handler) })
        }
        watched.clear()
        watched.putAll(ordered)
        publish()
    }

    private fun callbackFor(controller: MediaController) = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()

        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()

        override fun onSessionDestroyed() {
            watched.remove(controller.sessionToken)?.let { (c, callback) -> c.unregisterCallback(callback) }
            publish()
        }
    }

    /**
     * The session that plays, else the most recently active one with a track, as a banner
     * state. When none has a track, the last track shown stays on the banner, paused.
     */
    private fun publish() {
        if (!MediaRepository.hasAccess(context)) {
            revoked()
            return
        }
        val controllers = watched.values.map { it.first }
        val lastUpdate = { c: MediaController -> c.playbackState?.lastPositionUpdateTime ?: Long.MIN_VALUE }
        val chosen = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.filter { titleOf(it.metadata) != null }.maxByOrNull(lastUpdate)
            ?: controllers.maxByOrNull(lastUpdate)
        val metadata = chosen?.metadata
        val description = metadata?.description
        val title = titleOf(metadata)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).nonBlank()
            ?: description?.subtitle?.toString().nonBlank()
        val playing = chosen?.playbackState?.state == PlaybackState.STATE_PLAYING

        if (chosen == null || (title == null && !playing)) {
            artJob?.cancel()
            artKey = null
            art = null
            val remembered = MediaRepository.lastTrack
            // The controls still reach that track's player while its session is there, even
            // empty; without it, play goes out as a media key, which wakes the last player.
            onController(chosen?.takeIf { remembered != null && it.packageName == MediaRepository.lastTrackPackage })
            publishState(remembered?.copy(isPlaying = false) ?: MediaPlaybackState.Idle(installed))
            return
        }
        onController(chosen)

        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val uri = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)?.takeIf { it.isNotBlank() }
        // Apps often send the track first and its art a moment later: that is a new key.
        val key = listOf(chosen.packageName, title, artist, uri, bitmap != null).joinToString("|")
        if (key != artKey) {
            artKey = key
            art = null
            artJob?.cancel()
            artJob = if (bitmap == null && uri == null) {
                null
            } else {
                scope.launch {
                    val loaded = withContext(Dispatchers.IO) {
                        if (bitmap != null) scaleArt(bitmap) else uri?.let { loadArt(context, it) }
                    }
                    if (artKey == key) {
                        art = loaded
                        publish()
                    }
                }
            }
        }
        val active = MediaPlaybackState.Active(labelOf(chosen.packageName), title, artist, art, playing)
        if (title != null) {
            MediaRepository.lastTrack = active
            MediaRepository.lastTrackPackage = chosen.packageName
        }
        publishState(active)
    }

    /** Player names already looked up, null included (an app Android keeps hidden). */
    private val labels = HashMap<String, String?>()

    /** Our name for the three apps, else Android's name for the player when it is visible. */
    private fun labelOf(packageName: String): String? {
        if (packageName in labels) return labels[packageName]
        val label = MusicApp.of(packageName)?.label ?: runCatching {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(info).toString()
        }.getOrNull().nonBlank()
        labels[packageName] = label
        return label
    }
}

private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }

/** The track's title. Local players often fill only the display fields, and name the track after its file. */
private fun titleOf(metadata: MediaMetadata?): String? =
    (metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).nonBlank()
        ?: metadata?.description?.title?.toString().nonBlank())?.let(::withoutAudioExtension)

/** A track named after its file ("Artiste - Titre.mp3") is shown without the extension. */
private fun withoutAudioExtension(title: String): String = AUDIO_EXTENSION.replace(title, "").ifBlank { title }

private val AUDIO_EXTENSION = Regex("""\.(mp3|m4a|aac|flac|ogg|oga|opus|wav|wma|amr|mid|midi)$""", RegexOption.IGNORE_CASE)

/** Longest side of the album art kept in memory, in pixels. */
private const val ART_PX = 144

private fun scaleArt(source: Bitmap): Bitmap {
    val longest = maxOf(source.width, source.height)
    if (longest <= ART_PX) return source
    val ratio = ART_PX.toFloat() / longest
    return Bitmap.createScaledBitmap(
        source,
        (source.width * ratio).roundToInt().coerceAtLeast(1),
        (source.height * ratio).roundToInt().coerceAtLeast(1),
        true,
    )
}

/** Album art behind METADATA_KEY_ALBUM_ART_URI: a content URI, or a web address. */
private fun loadArt(context: Context, value: String): Bitmap? = runCatching {
    val uri = Uri.parse(value)
    val bytes = when (uri.scheme?.lowercase()) {
        "content", "android.resource" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        "http", "https" -> MediaRepository.artClient.newCall(Request.Builder().url(value).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.bytes() else null
        }
        else -> null
    } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= ART_PX && bounds.outHeight / (sample * 2) >= ART_PX) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    decoded?.let(::scaleArt)
}.getOrNull()

package org.manfredmueller.budsplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver

class PlayerService : Service() {

    companion object {
        const val CHANNEL_ID = "playback_channel"
        const val NOTIFICATION_ID = 1
        const val DEFAULT_EQ_PRESET = 2  // Index von "Entspannung" in eqPresets

        // Keep-Buds-on: hochfrequenter Ton hält Bluetooth-Buds wach
        const val KEEP_ALIVE_FREQ_HZ = 16196.0
        const val KEEP_ALIVE_DURATION_S = 5
        const val KEEP_ALIVE_INTERVAL_MS = 60_000L
        const val KEEP_ALIVE_SAMPLE_RATE = 44100
        // So niedrig wie möglich, so hoch wie nötig: Bei den meisten BT-Buds hält
        // schon der aktive Stream die Verbindung – Amplitude nur erhöhen, falls
        // die Buds trotzdem abschalten (Schwelle ist gerätespezifisch).
        const val KEEP_ALIVE_AMPLITUDE = 0.02
    }

    inner class LocalBinder : Binder() {
        fun getService(): PlayerService = this@PlayerService
    }

    private val binder = LocalBinder()
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private var mediaPlayer: MediaPlayer? = null
    private var equalizer: Equalizer? = null
    val heartbeatGenerator = HeartbeatGenerator()

    // Aktuell gewählter Preset-Index (Standard = Entspannung)
    var eqPresetIndex: Int = DEFAULT_EQ_PRESET

    // Lautstärke = Systemlautstärke Musik (0.0–1.0), keine eigene App-Lautstärke mehr
    val volume: Float
        get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    // Nach transientem Fokusverlust nur fortsetzen, wenn vorher gespielt wurde
    private var resumeOnFocusGain = false

    // ── Nur-Bluetooth-Ausgabe ────────────────────────────────────────
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pauseForOutputLoss()
        }
    }

    /** True, wenn ein Bluetooth-Gerät als Audioausgabe aktiv ist */
    fun isBluetoothOutputActive(): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

    /** Herzschlag ein-/ausschalten und sofort synchronisieren */
    fun setHeartbeatEnabled(on: Boolean) {
        heartbeatGenerator.isEnabled = on
        syncHeartbeat()
    }

    /** Herzschlag läuft nur bei aktiver Wiedergabe und Bluetooth-Ausgabe */
    private fun syncHeartbeat() {
        val shouldRun = heartbeatGenerator.isEnabled && isPlaying() && isBluetoothOutputActive()
        if (shouldRun) {
            if (!heartbeatGenerator.isPlaying) heartbeatGenerator.start()
        } else if (heartbeatGenerator.isPlaying) {
            heartbeatGenerator.stop()
        }
    }

    /** Hinweis, wenn Wiedergabe ohne Bluetooth-Kopfhörer angefragt wird */
    private fun bluetoothOutputOrHint(): Boolean {
        if (isBluetoothOutputActive()) return true
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(noOutput = true))
        Toast.makeText(this, R.string.no_output_hint, Toast.LENGTH_SHORT).show()
        return false
    }

    /** Ausgabe ist weggefallen (Buds raus, BT getrennt): laufende Wiedergabe pausieren */
    private fun pauseForOutputLoss() {
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            onPlaybackChanged?.invoke(false)
        }
    }

    // ── Keep-Buds-on ─────────────────────────────────────────────────
    private val keepAliveHandler = Handler(Looper.getMainLooper())
    private var keepAliveTrack: AudioTrack? = null
    private val keepAliveSamples: ShortArray by lazy { buildKeepAliveTone() }

    var keepBudsOn: Boolean = false
        set(value) {
            field = value
            keepAliveHandler.removeCallbacksAndMessages(null)
            if (value) keepAliveHandler.post(keepAliveRunnable) else stopKeepAliveTone()
        }

    private val keepAliveRunnable = object : Runnable {
        override fun run() {
            if (!keepBudsOn) return
            // Nur senden, wenn gerade keine andere Audioquelle aktiv ist
            if (!audioManager.isMusicActive && isBluetoothOutputActive()) playKeepAliveTone()
            keepAliveHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
        }
    }

    /** Erzeugt den 16,2-kHz-Sinuston mit Fade-In/Out (verhindert Knackser) */
    private fun buildKeepAliveTone(): ShortArray {
        val numSamples = KEEP_ALIVE_SAMPLE_RATE * KEEP_ALIVE_DURATION_S
        val fadeSamples = KEEP_ALIVE_SAMPLE_RATE / 20  // 50 ms
        val amplitude = Short.MAX_VALUE * KEEP_ALIVE_AMPLITUDE
        return ShortArray(numSamples) { i ->
            val fade = when {
                i < fadeSamples -> i.toDouble() / fadeSamples
                i > numSamples - fadeSamples -> (numSamples - i).toDouble() / fadeSamples
                else -> 1.0
            }
            (amplitude * fade * sin(2.0 * PI * KEEP_ALIVE_FREQ_HZ * i / KEEP_ALIVE_SAMPLE_RATE))
                .toInt().toShort()
        }
    }

    private fun playKeepAliveTone() {
        stopKeepAliveTone()
        keepAliveTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(KEEP_ALIVE_SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            keepAliveSamples.size * 2,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        ).apply {
            write(keepAliveSamples, 0, keepAliveSamples.size)
            play()
        }
        // Track nach Abspielende freigeben (Runnable bleibt unberührt)
        keepAliveHandler.postDelayed({ stopKeepAliveTone() }, (KEEP_ALIVE_DURATION_S + 1) * 1000L)
    }

    private fun stopKeepAliveTone() {
        keepAliveTrack?.let {
            try { it.stop() } catch (e: Exception) { /* bereits gestoppt */ }
            it.release()
        }
        keepAliveTrack = null
    }

    // Vordefinierte Presets: Name → Gain-Werte in mB pro Band (5 Bänder: 60Hz, 230Hz, 910Hz, 3.6kHz, 14kHz)
    val eqPresets: List<Pair<Int, ShortArray>> = listOf(
        R.string.eq_bass_boost  to shortArrayOf(600, 400, 0, 0, 0),
        R.string.eq_vocal       to shortArrayOf(-200, 0, 400, 300, 0),
        R.string.eq_relax       to shortArrayOf(200, 100, 0, -100, 200),
        R.string.eq_flat        to shortArrayOf(0, 0, 0, 0, 0)   // Aus – am Ende
    )

    var track0: Uri? = null
        set(value) {
            val changed = field != value
            field = value
            if (changed && currentSlot == 0) currentSlot = -1
        }
    var track1: Uri? = null
        set(value) {
            val changed = field != value
            field = value
            if (changed && currentSlot == 1) currentSlot = -1
        }
    var track2: Uri? = null
        set(value) {
            val changed = field != value
            field = value
            if (changed && currentSlot == 2) currentSlot = -1
        }
    var currentSlot: Int = 1
        private set

    private var isAutoRepeating = false  // Flag: verhindert Race Condition bei Repeat
    private var announcementPlayer: MediaPlayer? = null  // Für Sprachansagen

    var onSlotChanged: ((slot: Int, title: String) -> Unit)? = null
    var onPlaybackChanged: ((playing: Boolean) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        setupMediaSession()
        setupAudioFocus()
        ContextCompat.registerReceiver(
            this, noisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        try { unregisterReceiver(noisyReceiver) } catch (e: IllegalArgumentException) { /* nicht registriert */ }
        keepAliveHandler.removeCallbacksAndMessages(null)
        stopKeepAliveTone()
        heartbeatGenerator.release()
        abandonAudioFocus()
        equalizer?.release()
        mediaPlayer?.release()
        announcementPlayer?.release()
        mediaSession.release()
        super.onDestroy()
    }

    /** Spielt Ansage ab und startet Track danach */
    private fun playAnnouncementThenPlay(announcementResId: Int) {
        if (!isBluetoothOutputActive()) return
        announcementPlayer?.release()
        announcementPlayer = MediaPlayer.create(this, announcementResId)
        announcementPlayer?.setVolume(0.25f, 0.25f)  // 25% Lautstärke (~-12dB)
        announcementPlayer?.setOnCompletionListener {
            it.release()
            announcementPlayer = null
            // Nach Ansage: Track starten
            stopKeepAliveTone()
            requestAudioFocus()
            mediaPlayer?.setVolume(1f, 1f)
            mediaPlayer?.start()
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForeground(NOTIFICATION_ID, buildNotification())
            onPlaybackChanged?.invoke(true)
        }
        announcementPlayer?.start()
    }

    /** Spielt Ansage-MP3 ab (nur Ansage, kein Auto-Play) */
    private fun playAnnouncement(resourceId: Int) {
        if (!isBluetoothOutputActive()) return
        announcementPlayer?.release()
        announcementPlayer = MediaPlayer.create(this, resourceId)
        announcementPlayer?.setVolume(0.25f, 0.25f)  // 25% Lautstärke (~-12dB)
        announcementPlayer?.setOnCompletionListener {
            it.release()
            announcementPlayer = null
        }
        announcementPlayer?.start()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "BudsPlay").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (mediaPlayer?.isPlaying != true && !bluetoothOutputOrHint()) return
                    // Single Tap → Play/Pause des aktuellen Slots
                    if (mediaPlayer?.isPlaying == true) {
                        mediaPlayer?.pause()
                        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                        onPlaybackChanged?.invoke(false)
                    } else if (currentSlot in 0..2) {
                        // Wenn nichts vorbereitet → aktuellen Slot spielen (mit prepareAsync)
                        if (mediaPlayer == null) {
                            playSlot(currentSlot)
                        } else {
                            // Track ist bereits vorbereitet → Ansage + direkt starten
                            val announcementResId = when (currentSlot) {
                                0 -> R.raw.track1
                                1 -> R.raw.track2
                                else -> R.raw.track3
                            }
                            playAnnouncementThenPlay(announcementResId)
                        }
                    }
                }
                override fun onPause() {
                    mediaPlayer?.pause()
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    onPlaybackChanged?.invoke(false)
                }
                override fun onStop() {
                    mediaPlayer?.stop()
                    setActive(false)
                    updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    onPlaybackChanged?.invoke(false)
                }
                // Double Tap → vorheriger Slot (zyklisch: 0→2→1→0)
                override fun onSkipToPrevious() {
                    val nextSlot = when (currentSlot) {
                        0 -> 2
                        1 -> 0
                        else -> 1
                    }
                    playSlot(nextSlot)
                }
                // Triple Tap → nächster Slot (zyklisch: 0→1→2→0)
                override fun onSkipToNext() {
                    val nextSlot = when (currentSlot) {
                        0 -> 1
                        1 -> 2
                        else -> 0
                    }
                    playSlot(nextSlot)
                }
            })
        }
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        mediaSession.isActive = true
    }

    private fun updatePlaybackState(state: Int) {
        val position = mediaPlayer?.currentPosition?.toLong() ?: 0L
        val ps = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, position, 1.0f)
            .build()
        mediaSession.setPlaybackState(ps)
        syncHeartbeat()
    }

    private fun updateMetadata(title: String) {
        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, mediaPlayer?.duration?.toLong() ?: -1L)
            .build()
        mediaSession.setMetadata(meta)
    }

    private fun setupAudioFocus() {
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        resumeOnFocusGain = false
                        mediaSession.controller.transportControls.pause()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        resumeOnFocusGain = mediaPlayer?.isPlaying == true
                        mediaPlayer?.pause()
                        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                        onPlaybackChanged?.invoke(false)
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (resumeOnFocusGain) {
                            resumeOnFocusGain = false
                            mediaPlayer?.start()
                            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                            onPlaybackChanged?.invoke(true)
                        }
                    }
                }
            }
            .build()
    }

    private fun requestAudioFocus() { audioManager.requestAudioFocus(focusRequest) }
    private fun abandonAudioFocus() { audioManager.abandonAudioFocusRequest(focusRequest) }

    fun playSlot(slot: Int) {
        if (!bluetoothOutputOrHint()) return
        // Benutzer hat einen anderen Track gewählt → Repeat-Autoplay unterbrechen
        if (slot != currentSlot) {
            isAutoRepeating = false
        }

        val uri = (if (slot == 0) track0 else if (slot == 1) track1 else track2) ?: return

        // Track bereits vorbereitet → direkt starten
        if (currentSlot == slot && mediaPlayer != null) {
            stopKeepAliveTone()
            requestAudioFocus()
            mediaPlayer?.setVolume(1f, 1f)
            mediaPlayer?.start()
            applyEqualizer(mediaPlayer!!.audioSessionId)
            val title = titleFromUri(uri)
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForeground(NOTIFICATION_ID, buildNotification())
            onSlotChanged?.invoke(slot, title)
            onPlaybackChanged?.invoke(true)
            return
        }

        // Sonst neu laden
        currentSlot = slot
        mediaPlayer?.reset() ?: run { mediaPlayer = MediaPlayer() }

        mediaPlayer?.apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(applicationContext, uri)
            setOnPreparedListener {
                stopKeepAliveTone()
                requestAudioFocus()
                setVolume(1f, 1f)
                start()
                // Equalizer an neue AudioSession hängen
                applyEqualizer(audioSessionId)
                mediaSession.isActive = true
                val title = titleFromUri(uri)
                updateMetadata(title)
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                startForeground(NOTIFICATION_ID, buildNotification())

                // Sprachansage: "Track 1", "Track 2" oder "Track 3"
                val announcementResId = when (slot) {
                    0 -> R.raw.track1
                    1 -> R.raw.track2
                    else -> R.raw.track3
                }
                playAnnouncement(announcementResId)

                onSlotChanged?.invoke(slot, title)
                onPlaybackChanged?.invoke(true)
            }
            setOnCompletionListener {
                // Repeat-Einstellung prüfen
                val repeatMode = TrackPrefs.loadRepeatMode(applicationContext, slot)
                if (repeatMode == 1 && currentSlot == slot && !isAutoRepeating) {
                    // Repeat aktiv & Slot nicht gewechselt → Track erneut abspielen
                    isAutoRepeating = true
                    playSlot(slot)
                } else {
                    updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                    onPlaybackChanged?.invoke(false)
                }
            }
            setOnErrorListener { _, _, _ ->
                // Defekte/nicht lesbare Datei: Player zurücksetzen statt hängen bleiben
                currentSlot = -1
                reset()
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                onPlaybackChanged?.invoke(false)
                true
            }
            prepareAsync()
        }
    }

    /**
     * Lädt einen Track vor ohne ihn abzuspielen.
     * Wird beim App-Start aufgerufen damit Play sofort verfügbar ist.
     */
    fun prepareSlot(slot: Int) {
        val uri = (if (slot == 0) track0 else if (slot == 1) track1 else track2) ?: return
        currentSlot = slot

        mediaPlayer?.reset() ?: run { mediaPlayer = MediaPlayer() }

        mediaPlayer?.apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setDataSource(applicationContext, uri)
            setOnPreparedListener {
                // Kein start() — aber Audio Focus anfordern!
                requestAudioFocus()
                setVolume(1f, 1f)
                applyEqualizer(audioSessionId)
                val title = titleFromUri(uri)
                updateMetadata(title)
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                mediaSession.isActive = true  // ← wichtig!
                startForeground(NOTIFICATION_ID, buildNotification())  // ← Notification zeigen
                onSlotChanged?.invoke(slot, title)
                onPlaybackChanged?.invoke(false)
            }
            setOnCompletionListener {
                // Repeat-Einstellung prüfen
                val repeatMode = TrackPrefs.loadRepeatMode(applicationContext, slot)
                if (repeatMode == 1 && currentSlot == slot && !isAutoRepeating) {
                    // Repeat aktiv & Slot nicht gewechselt → Track erneut vorbereiten
                    isAutoRepeating = true
                    prepareSlot(slot)
                } else {
                    updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                    onPlaybackChanged?.invoke(false)
                }
            }
            setOnErrorListener { _, _, _ ->
                // Defekte/nicht lesbare Datei: Player zurücksetzen statt hängen bleiben
                currentSlot = -1
                reset()
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                onPlaybackChanged?.invoke(false)
                true
            }
            prepareAsync()
        }
    }

    fun togglePlayPause() {
        if (mediaPlayer?.isPlaying != true && !bluetoothOutputOrHint()) return
        if (mediaPlayer?.isPlaying == true) {
            mediaPlayer?.pause()
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            onPlaybackChanged?.invoke(false)
        } else if (currentSlot in 0..2) {
            // Ansage abspielen, dann Track starten
            val announcementResId = when (currentSlot) {
                0 -> R.raw.track1
                1 -> R.raw.track2
                else -> R.raw.track3
            }
            playAnnouncementThenPlay(announcementResId)
        }
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true
    fun getDuration(): Int = mediaPlayer?.duration ?: 0
    fun getPosition(): Int = mediaPlayer?.currentPosition ?: 0
    fun seekTo(ms: Int) { mediaPlayer?.seekTo(ms) }

    fun stopPlayback() {
        mediaSession.controller.transportControls.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Setzt die Systemlautstärke Musik (0.0–1.0) */
    fun setVolume(v: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC, (v.coerceIn(0f, 1f) * max).roundToInt(), 0
        )
    }

    fun setEqPreset(index: Int) {
        eqPresetIndex = index
        mediaPlayer?.audioSessionId?.let { applyEqualizer(it) }
    }

    private fun applyEqualizer(sessionId: Int) {
        equalizer?.release()
        equalizer = Equalizer(0, sessionId).apply {
            enabled = true
            val gains = eqPresets[eqPresetIndex.coerceIn(0, eqPresets.size - 1)].second
            for (band in 0 until numberOfBands.toInt()) {
                if (band < gains.size) setBandLevel(band.toShort(), gains[band])
            }
        }
    }

    fun titleFromUri(uri: Uri): String = try {
        (uri.lastPathSegment?.substringAfterLast('/') ?: "?")
            .removeSuffix(".mp3").removeSuffix(".MP3")
            .removeSuffix(".wav").removeSuffix(".WAV")
            .removeSuffix(".flac").removeSuffix(".FLAC")
            .removeSuffix(".ogg").removeSuffix(".OGG")
            .removeSuffix(".aac").removeSuffix(".AAC")
            .removeSuffix(".m4a").removeSuffix(".M4A")
    } catch (e: Exception) { "?" }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(noOutput: Boolean = false): Notification {
        val title = when {
            currentSlot == 0 && track0 != null -> titleFromUri(track0!!)
            currentSlot == 1 && track1 != null -> titleFromUri(track1!!)
            currentSlot == 2 && track2 != null -> titleFromUri(track2!!)
            else -> getString(R.string.app_name)
        }

        val actIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (noOutput) getString(R.string.notification_no_output) else if (currentSlot >= 0 && currentSlot < 3) getString(R.string.track_index, currentSlot + 1) else "")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(actIntent)
            .addAction(android.R.drawable.ic_media_previous,
                getString(R.string.notification_track_prev),
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(
                if (isPlaying()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(R.string.notification_play_pause),
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE))
            .addAction(android.R.drawable.ic_media_next,
                getString(R.string.notification_track_next),
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
}

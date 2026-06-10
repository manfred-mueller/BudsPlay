package org.manfredmueller.budsplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver

class PlayerService : Service() {

    companion object {
        const val CHANNEL_ID = "playback_channel"
        const val NOTIFICATION_ID = 1
        const val DEFAULT_EQ_PRESET = 2  // Index von "Entspannung" in eqPresets
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

    // Aktuell gewählter Preset-Index (Standard = Entspannung)
    var eqPresetIndex: Int = DEFAULT_EQ_PRESET

    // App-interne Lautstärke (0.0–1.0)
    var volume: Float = 1f
        private set

    // Nach transientem Fokusverlust nur fortsetzen, wenn vorher gespielt wurde
    private var resumeOnFocusGain = false

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
            // Aktiver Slot bekam eine neue URI → vorbereiteten Player invalidieren,
            // damit beim nächsten Play der neue Track geladen wird
            if (changed && currentSlot == 0) currentSlot = -1
        }
    var track1: Uri? = null
        set(value) {
            val changed = field != value
            field = value
            if (changed && currentSlot == 1) currentSlot = -1
        }
    var currentSlot: Int = -1
        private set

    var onSlotChanged: ((slot: Int, title: String) -> Unit)? = null
    var onPlaybackChanged: ((playing: Boolean) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        setupMediaSession()
        setupAudioFocus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        abandonAudioFocus()
        equalizer?.release()
        mediaPlayer?.release()
        mediaSession.release()
        super.onDestroy()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "BudsPlay").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    requestAudioFocus()
                    mediaPlayer?.start()
                    setActive(true)
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    startForeground(NOTIFICATION_ID, buildNotification())
                    onPlaybackChanged?.invoke(true)
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
                // Doppeltippen rechts → Track 1
                override fun onSkipToNext() {
                    track0?.let { playSlot(0) }
                }
                // Dreifachtippen rechts → Track 2
                override fun onSkipToPrevious() {
                    track1?.let { playSlot(1) }
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
        val uri = (if (slot == 0) track0 else track1) ?: return

        // Track bereits vorbereitet → direkt starten
        if (currentSlot == slot && mediaPlayer != null) {
            requestAudioFocus()
            mediaPlayer?.setVolume(volume, volume)
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
                requestAudioFocus()
                setVolume(volume, volume)
                start()
                // Equalizer an neue AudioSession hängen
                applyEqualizer(audioSessionId)
                mediaSession.isActive = true
                val title = titleFromUri(uri)
                updateMetadata(title)
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                startForeground(NOTIFICATION_ID, buildNotification())
                onSlotChanged?.invoke(slot, title)
                onPlaybackChanged?.invoke(true)
            }
            setOnCompletionListener {
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                onPlaybackChanged?.invoke(false)
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
        val uri = (if (slot == 0) track0 else track1) ?: return
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
                setVolume(volume, volume)
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
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                onPlaybackChanged?.invoke(false)
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
        val controls = mediaSession.controller.transportControls
        if (mediaPlayer?.isPlaying == true) controls.pause() else controls.play()
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

    /** Setzt die app-interne Lautstärke (0.0–1.0) und wendet sie sofort an */
    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        mediaPlayer?.setVolume(volume, volume)
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

    private fun buildNotification(): Notification {
        val title = if (currentSlot >= 0) {
            val uri = if (currentSlot == 0) track0 else track1
            uri?.let { titleFromUri(it) } ?: getString(R.string.app_name)
        } else getString(R.string.app_name)

        val actIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(if (currentSlot >= 0) getString(R.string.track_index, currentSlot + 1) else "")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(actIntent)
            .addAction(android.R.drawable.ic_media_previous,
                getString(R.string.notification_track_prev),
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(
                if (isPlaying()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(R.string.notification_play_pause),
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE))
            .addA
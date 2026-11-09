package org.manfredmueller.budsplay

import android.animation.ObjectAnimator
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.manfredmueller.budsplay.databinding.ActivityMainBinding
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var playerService: PlayerService? = null
    private var bound = false
    private var currentSlot = 1  // Default Slot 1
    private var pendingPickSlot = 0

    private val tracks = arrayOfNulls<Uri>(3)  // Ein Track pro Slot

    private val eqCheckBoxes = mutableListOf<android.widget.CheckBox>()
    private var announcementPlayer: android.media.MediaPlayer? = null
    private var announcementQueue = mutableListOf<Int>()

    private var backPressedOnce = false
    private val backHandler = Handler(Looper.getMainLooper())
    private var pulseAnimatorX: ObjectAnimator? = null
    private var pulseAnimatorY: ObjectAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() { updateStatusBar(); handler.postDelayed(this, 500) }
    }

    // -------------------------------------------------------------------------
    // Service Connection
    // -------------------------------------------------------------------------

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            playerService = (service as PlayerService.LocalBinder).getService()
            bound = true

            // Gespeicherten EQ-Preset laden
            val savedPreset = getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getInt("eq_preset", PlayerService.DEFAULT_EQ_PRESET)
                .coerceIn(0, playerService!!.eqPresets.size - 1)
            playerService?.eqPresetIndex = savedPreset
            setupEqCheckboxes(savedPreset)

            // Gespeicherte Lautstärke laden
            binding.volumeSlider.progress = ((playerService?.volume ?: 0f) * 100).toInt()

            // Keep-Buds-on laden
            val keepOn = getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("keep_buds_on", false)
            binding.switchKeepAlive.isChecked = keepOn
            playerService?.keepBudsOn = keepOn

            // Herzschlag laden
            val hbPrefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val hbVol = hbPrefs.getInt("heartbeat_vol", 30).coerceIn(0, 100)
            val hbOn = hbPrefs.getBoolean("heartbeat_on", false)
            binding.sliderHeartbeat.progress = hbVol
            playerService?.heartbeatGenerator?.volume = hbVol / 100f
            val hbBpm = hbPrefs.getInt("heartbeat_bpm", 40).coerceIn(30, 60)
            playerService?.heartbeatGenerator?.bpm = hbBpm
            binding.sliderHeartbeatBpm.progress = hbBpm - 30
            binding.tvHeartbeatBpm.text = getString(R.string.heartbeat_bpm_value, hbBpm)
            binding.switchHeartbeat.isChecked = hbOn
            playerService?.setHeartbeatEnabled(hbOn)

            // 3 Tracks laden
            for (slot in 0..2) {
                tracks[slot] = TrackPrefs.loadTrack(this@MainActivity, slot)
                when (slot) {
                    0 -> playerService?.track0 = tracks[slot]
                    1 -> playerService?.track1 = tracks[slot]
                    else -> playerService?.track2 = tracks[slot]
                }
            }
            currentSlot = TrackPrefs.loadCurrentSlot(this@MainActivity)
            updateUI()
            updateButtonStates()

            // Aktuellen Slot vorbereiten
            if (currentSlot in 0..2 && tracks[currentSlot] != null) {
                playerService?.prepareSlot(currentSlot)
            }

            playerService?.onSlotChanged = { slot, title ->
                runOnUiThread {
                    currentSlot = slot
                    binding.tvNowPlaying.text = title
                    binding.tvSlot.text = getString(R.string.track_index, slot + 1)
                    binding.btnPlayPause.isEnabled = true
                    binding.tvStatusTitle.text = title
                    binding.seekBar.isEnabled = true
                }
            }
            playerService?.onPlaybackChanged = { playing ->
                runOnUiThread {
                    binding.btnPlayPause.text = getString(
                        if (playing) R.string.btn_pause else R.string.btn_play
                    )
                    if (playing) { startUpdating(); startPulse() }
                    else { stopUpdating(); stopPulse() }
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { bound = false }
    }

    // -------------------------------------------------------------------------
    // Permission + File Picker
    // -------------------------------------------------------------------------

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openPicker()
        else binding.tvNowPlaying.text = getString(R.string.permission_denied)
    }

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val slot = pendingPickSlot
        tracks[slot] = uri
        TrackPrefs.saveTrack(this, slot, uri)
        when (slot) {
            0 -> playerService?.track0 = uri
            1 -> playerService?.track1 = uri
            else -> playerService?.track2 = uri
        }
        updateUI()
        updateButtonStates()
        if (playerService?.isPlaying() != true) playerService?.prepareSlot(slot)
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupButtons()
        setupTrackClickListeners()
        setupBackHandling()
        val intent = Intent(this, PlayerService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()
        if (playerService?.isPlaying() == true) startUpdating()
    }
    override fun onPause()  {
        super.onPause()
        stopUpdating()
        // Aktuellen Slot speichern
        if (currentSlot in 0..2) {
            TrackPrefs.saveCurrentSlot(this, currentSlot)
        }
    }

    override fun onDestroy() {
        stopPulse()
        backHandler.removeCallbacksAndMessages(null)
        announcementPlayer?.release()
        if (bound) { unbindService(serviceConnection); bound = false }
        super.onDestroy()
    }

    private fun playAnnouncement(resourceId: Int) {
        announcementPlayer?.release()
        announcementPlayer = android.media.MediaPlayer.create(this, resourceId)
        announcementPlayer?.setVolume(0.25f, 0.25f)  // 25% Lautstärke (~-12dB)
        announcementPlayer?.setOnCompletionListener {
            it.release()
            announcementPlayer = null
        }
        announcementPlayer?.start()
    }

    private fun playSequentialAnnouncements(resourceIds: List<Int>) {
        announcementQueue.clear()
        announcementQueue.addAll(resourceIds)
        playNextAnnouncement()
    }

    private fun playNextAnnouncement() {
        if (announcementQueue.isEmpty()) return
        val resourceId = announcementQueue.removeAt(0)
        announcementPlayer?.release()
        announcementPlayer = android.media.MediaPlayer.create(this, resourceId)
        announcementPlayer?.setVolume(0.25f, 0.25f)
        announcementPlayer?.setOnCompletionListener {
            it.release()
            announcementPlayer = null
            playNextAnnouncement()  // Nächste Ansage abspielen
        }
        announcementPlayer?.start()
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!backPressedOnce) {
                    backPressedOnce = true
                    Toast.makeText(this@MainActivity, getString(R.string.back_press_hint), Toast.LENGTH_SHORT).show()
                    backHandler.postDelayed({ backPressedOnce = false }, 2000)
                } else {
                    stopPulse()
                    playerService?.let {
                        it.stopPlayback()
                        stopService(Intent(this@MainActivity, PlayerService::class.java))
                    }
                    if (bound) { unbindService(serviceConnection); bound = false }
                    finishAndRemoveTask()  // Entfernt auch aus Recent Apps
                }
            }
        })
    }
    // -------------------------------------------------------------------------
    // Menu
    // -------------------------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu); return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_info) { showInfoDialog(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun showInfoDialog() {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "—" }
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val openThetaVoice = {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.thetavoice_url))))
            } catch (e: Exception) { /* kein Browser/Store verfügbar */ }
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)

            addView(android.widget.TextView(this@MainActivity).apply {
                text = getString(R.string.info_message, v)
            })

            // Klickbares ThetaVoice-Logo
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.thetavoice)
                val size = (64 * density).toInt()
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    topMargin = pad
                }
                contentDescription = getString(R.string.dev_credit_button)
                isClickable = true
                isFocusable = true
                setOnClickListener { openThetaVoice() }
            })

            addView(android.widget.TextView(this@MainActivity).apply {
                text = getString(R.string.dev_credit)
                textSize = 12f
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(0, pad / 4, 0, pad / 2)
                setOnClickListener { openThetaVoice() }
            })
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.info_title))
            .setView(android.widget.ScrollView(this).apply { addView(layout) })
            .setPositiveButton(getString(R.string.info_ok), null)
            .show()
    }

    /**
     * Baut die EQ-Checkboxen unter dem Lautstärke-Slider auf.
     * Exklusiv: nur ein Preset gleichzeitig aktiv, Auswahl wird gespeichert.
     */
    private fun setupEqCheckboxes(selected: Int) {
        val service = playerService ?: return
        binding.eqContainer.removeAllViews()
        eqCheckBoxes.clear()
        service.eqPresets.forEachIndexed { index, preset ->
            val cb = android.widget.CheckBox(this).apply {
                text = getString(preset.first)
                isChecked = index == selected
                setOnClickListener {
                    if (!isChecked) {
                        // Abwählen nicht erlaubt – ein Preset bleibt immer aktiv
                        isChecked = true
                        return@setOnClickListener
                    }
                    eqCheckBoxes.forEachIndexed { i, other ->
                        if (i != index) other.isChecked = false
                    }
                    playerService?.setEqPreset(index)
                    getSharedPreferences("settings", Context.MODE_PRIVATE)
                        .edit().putInt("eq_preset", index).apply()
                }
            }
            eqCheckBoxes.add(cb)
            binding.eqContainer.addView(cb)
        }
    }


    private fun setupButtons() {
        // Slot 0
        binding.btnAddTrack0.setOnClickListener { pendingPickSlot = 0; checkPermissionAndPick() }
        binding.btnRemoveTrack0.setOnClickListener { removeTrack(0) }

        // Slot 1
        binding.btnAddTrack1.setOnClickListener { pendingPickSlot = 1; checkPermissionAndPick() }
        binding.btnRemoveTrack1.setOnClickListener { removeTrack(1) }

        // Slot 2
        binding.btnAddTrack2.setOnClickListener { pendingPickSlot = 2; checkPermissionAndPick() }
        binding.btnRemoveTrack2.setOnClickListener { removeTrack(2) }

        // Play/Pause (global)
        binding.btnPlayPause.setOnClickListener { playerService?.togglePlayPause() }
        binding.btnPlayPause.isEnabled = false

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) playerService?.seekTo(p) }
            override fun onStartTrackingTouch(s: SeekBar) { stopUpdating() }
            override fun onStopTrackingTouch(s: SeekBar) { if (playerService?.isPlaying() == true) startUpdating() }
        })

        // Keep-Buds-on: anwenden und speichern
        binding.switchKeepAlive.setOnCheckedChangeListener { _, checked ->
            playerService?.keepBudsOn = checked
            getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putBoolean("keep_buds_on", checked).apply()
        }

        // Lautstärke-Slider: live anwenden, beim Loslassen speichern
        binding.volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) playerService?.setVolume(p / 100f)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {
                getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit().putInt("volume", s.progress).apply()
            }
        })

        // Herzschlag: Schalter und Lautstärke
        binding.switchHeartbeat.setOnCheckedChangeListener { _, checked ->
            playerService?.setHeartbeatEnabled(checked)
            getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putBoolean("heartbeat_on", checked).apply()
        }
        binding.sliderHeartbeat.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) playerService?.heartbeatGenerator?.volume = p / 100f
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {
                getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit().putInt("heartbeat_vol", s.progress).apply()
            }
        })
        binding.sliderHeartbeatBpm.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val bpm = p + 30
                binding.tvHeartbeatBpm.text = getString(R.string.heartbeat_bpm_value, bpm)
                if (fromUser) playerService?.heartbeatGenerator?.bpm = bpm
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {
                getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .edit().putInt("heartbeat_bpm", s.progress + 30).apply()
            }
        })
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun setupTrackClickListeners() {
        // Tap auf Track-Name → aktivieren + abspielen
        binding.tvTrack0.setOnClickListener { playTrack(0) }
        binding.tvTrack1.setOnClickListener { playTrack(1) }
        binding.tvTrack2.setOnClickListener { playTrack(2) }

        // Repeat-Buttons
        binding.btnRepeat0.setOnClickListener { cycleRepeatMode(0) }
        binding.btnRepeat1.setOnClickListener { cycleRepeatMode(1) }
        binding.btnRepeat2.setOnClickListener { cycleRepeatMode(2) }
    }

    private fun cycleRepeatMode(slot: Int) {
        val current = TrackPrefs.loadRepeatMode(this, slot)
        val next = (current + 1) % 2  // 0↔1
        TrackPrefs.saveRepeatMode(this, slot, next)
        updateRepeatButtonUI(slot)
        showRepeatModeToast(next)
    }

    private fun updateRepeatButtonUI(slot: Int) {
        val mode = TrackPrefs.loadRepeatMode(this, slot)
        val btn = when (slot) {
            0 -> binding.btnRepeat0
            1 -> binding.btnRepeat1
            else -> binding.btnRepeat2
        }
        btn.alpha = if (mode == 0) 0.5f else 1.0f
    }

    private fun showRepeatModeToast(mode: Int) {
        val text = if (mode == 0) getString(R.string.repeat_off) else getString(R.string.repeat_on)
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

        // Sprachansage: "Track X" + "Repeat ein/aus"
        val trackResId = when (currentSlot) {
            0 -> R.raw.track1
            1 -> R.raw.track2
            else -> R.raw.track3
        }
        val repeatResId = if (mode == 0) R.raw.repeat_aus else R.raw.repeat_ein
        playSequentialAnnouncements(listOf(trackResId, repeatResId))
    }

    private fun playTrack(slot: Int) {
        if (tracks[slot] == null) return
        playerService?.playSlot(slot)
    }

    private fun removeTrack(slot: Int) {
        tracks[slot] = null
        TrackPrefs.saveTrack(this, slot, null)
        when (slot) {
            0 -> playerService?.track0 = null
            1 -> playerService?.track1 = null
            else -> playerService?.track2 = null
        }
        updateUI()
        updateButtonStates()
    }

    private fun updateUI() {
        val track0Name = tracks[0]?.let { displayName(it) } ?: "—"
        val track1Name = tracks[1]?.let { displayName(it) } ?: "—"
        val track2Name = tracks[2]?.let { displayName(it) } ?: "—"

        binding.tvTrack0.text = "Track 1: $track0Name"
        binding.tvTrack1.text = "Track 2: $track1Name"
        binding.tvTrack2.text = "Track 3: $track2Name"

        // Repeat-Button UIs aktualisieren
        updateRepeatButtonUI(0)
        updateRepeatButtonUI(1)
        updateRepeatButtonUI(2)
    }

    private fun updateButtonStates() {
        binding.btnRemoveTrack0.isEnabled = tracks[0] != null
        binding.btnRemoveTrack1.isEnabled = tracks[1] != null
        binding.btnRemoveTrack2.isEnabled = tracks[2] != null
    }

    private fun checkPermissionAndPick() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED)
            openPicker() else permissionLauncher.launch(perm)
    }

    private fun openPicker() { pickerLauncher.launch(arrayOf("audio/*")) }

    private fun displayName(uri: Uri): String {
        val name = try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                c.moveToFirst(); c.getString(col)
            } ?: uri.lastPathSegment ?: "?"
        } catch (e: Exception) { uri.lastPathSegment ?: "?" }
        return name.removeSuffix(".mp3").removeSuffix(".MP3")
            .removeSuffix(".wav").removeSuffix(".WAV")
            .removeSuffix(".ogg").removeSuffix(".OGG")
            .removeSuffix(".flac").removeSuffix(".FLAC")
            .removeSuffix(".aac").removeSuffix(".AAC")
            .removeSuffix(".m4a").removeSuffix(".M4A")
    }

    // -------------------------------------------------------------------------
    // Statusleiste
    // -------------------------------------------------------------------------

    private fun startUpdating() { handler.post(updateRunnable) }
    private fun stopUpdating()  { handler.removeCallbacks(updateRunnable) }

    private fun updateStatusBar() {
        val service = playerService ?: return
        val duration = service.getDuration()
        val position = service.getPosition()
        if (duration <= 0) return
        binding.seekBar.max = duration
        binding.seekBar.progress = position
        binding.tvPosition.text  = formatTime(position)
        binding.tvDuration.text  = formatTime(duration)
        binding.tvRemaining.text = formatTime(duration - position)
    }

    private fun formatTime(ms: Int): String {
        val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60)
    }

    // -------------------------------------------------------------------------
    // Pulsier-Animation
    // -------------------------------------------------------------------------

    private fun startPulse() {
        stopPulse()
        val anim = { prop: String ->
            ObjectAnimator.ofFloat(binding.imgLogo, prop, 1.0f, 1.08f).apply {
                duration = 1200
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
        pulseAnimatorX = anim("scaleX")
        pulseAnimatorY = anim("scaleY")
    }

    private fun stopPulse() {
        pulseAnimatorX?.cancel(); pulseAnimatorX = null
        pulseAnimatorY?.cancel(); pulseAnimatorY = null
        binding.imgLogo.scaleX = 1.0f
        binding.imgLogo.scaleY = 1.0f
    }
}

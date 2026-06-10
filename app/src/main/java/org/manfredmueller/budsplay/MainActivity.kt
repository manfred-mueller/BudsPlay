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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var playerService: PlayerService? = null
    private var bound = false
    private var pendingPickSlot = 0

    private val trackLists  = arrayOf(mutableListOf<Uri>(), mutableListOf<Uri>())
    private val activeIndex = intArrayOf(0, 0)
    // Spinner-Adapter pro Slot
    private val adapters = arrayOfNulls<ArrayAdapter<String>>(2)
    private val trackNames = arrayOf(mutableListOf<String>(), mutableListOf<String>())

    private val eqCheckBoxes = mutableListOf<android.widget.CheckBox>()

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

            // Gespeicherte Lautstärke laden und anwenden
            val savedVolume = getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getInt("volume", 100).coerceIn(0, 100)
            playerService?.setVolume(savedVolume / 100f)
            binding.volumeSlider.progress = savedVolume

            for (slot in 0..1) {
                trackLists[slot] = TrackPrefs.loadList(this@MainActivity, slot)
                activeIndex[slot] = TrackPrefs.loadActive(this@MainActivity, slot)
                    .coerceIn(0, maxOf(0, trackLists[slot].size - 1))
                rebuildNames(slot)
                syncAdapter(slot)
                syncServiceSlot(slot)
            }
            updateButtonStates()
            // Ersten verfügbaren Track automatisch vorauswählen
            for (slot in 0..1) {
                if (trackLists[slot].isNotEmpty()) {
                    playerService?.prepareSlot(slot)
                    break  // Slot 0 wenn vorhanden, sonst Slot 1
                }
            }

            playerService?.onSlotChanged = { slot, title ->
                runOnUiThread {
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
        trackLists[slot].add(uri)
        TrackPrefs.saveList(this, slot, trackLists[slot])
        activeIndex[slot] = trackLists[slot].size - 1
        TrackPrefs.saveActive(this, slot, activeIndex[slot])
        rebuildNames(slot)
        syncAdapter(slot)
        // Spinner auf neuen Eintrag setzen (kein weiteres onItemSelected auslösen)
        spinnerForSlot(slot).setSelection(activeIndex[slot], false)
        syncServiceSlot(slot)
        updateButtonStates()
        // Neuen Track sofort vorbereiten, damit Buds-Steuerung ohne Anspielen funktioniert
        if (playerService?.isPlaying() != true) playerService?.prepareSlot(slot)
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupSpinners()
        setupButtons()
        setupBackHandling()
        val intent = Intent(this, PlayerService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() { super.onResume(); if (playerService?.isPlaying() == true) startUpdating() }
    override fun onPause()  { super.onPause(); stopUpdating() }

    override fun onDestroy() {
        stopPulse()
        backHandler.removeCallbacksAndMessages(null)
        if (bound) { unbindService(serviceConnection); bound = false }
        super.onDestroy()
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (playerService?.isPlaying() == true && !backPressedOnce) {
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
                    finish()
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

    // -------------------------------------------------------------------------
    // Spinner Setup
    // -------------------------------------------------------------------------

    private fun setupSpinners() {
        for (slot in 0..1) {
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, trackNames[slot]).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            adapters[slot] = adapter
            spinnerForSlot(slot).apply {
                this.adapter = adapter
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: android.view.View?, pos: Int, id: Long) {
                        activeIndex[slot] = pos
                        TrackPrefs.saveActive(this@MainActivity, slot, pos)
                        syncServiceSlot(slot)
                        // Auswahl sofort vorbereiten, damit Buds-Steuerung direkt greift
                        if (playerService?.isPlaying() != true) playerService?.prepareSlot(slot)
                    }
                    override fun onNothingSelected(parent: AdapterView<*>) {}
                }
            }
        }
    }

    private fun setupButtons() {
        binding.btnAddTrack0.setOnClickListener { pendingPickSlot = 0; checkPermissionAndPick() }
        binding.btnAddTrack1.setOnClickListener { pendingPickSlot = 1; checkPermissionAndPick() }
        binding.btnRemoveTrack0.setOnClickListener { removeActive(0) }
        binding.btnRemoveTrack1.setOnClickListener { removeActive(1) }
        binding.btnPlay0.setOnClickListener { playerService?.playSlot(0) }
        binding.btnPlay1.setOnClickListener { playerService?.playSlot(1) }
        binding.btnPlayPause.setOnClickListener { playerService?.togglePlayPause() }
        binding.btnPlayPause.isEnabled = false

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) playerService?.seekTo(p) }
            override fun onStartTrackingTouch(s: SeekBar) { stopUpdating() }
            override fun onStopTrackingTouch(s: SeekBar) { if (playerService?.isPlaying() == true) startUpdating() }
        })

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
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun removeActive(slot: Int) {
        val list = trackLists[slot]
        if (list.isEmpty()) return
        val idx = activeIndex[slot].coerceIn(0, list.size - 1)
        list.removeAt(idx)
        TrackPrefs.saveList(this, slot, list)
        activeIndex[slot] = if (list.isEmpty()) 0 else idx.coerceIn(0, list.size - 1)
        TrackPrefs.saveActive(this, slot, activeIndex[slot])
        rebuildNames(slot)
        syncAdapter(slot)
        if (list.isNotEmpty()) spinnerForSlot(slot).setSelection(activeIndex[slot], false)
        syncServiceSlot(slot)
        updateButtonStates()
    }

    private fun rebuildNames(slot: Int) {
        trackNames[slot].clear()
        trackLists[slot].forEach { trackNames[slot].add(displayName(it)) }
    }

    private fun syncAdapter(slot: Int) {
        adapters[slot]?.notifyDataSetChanged()
    }

    private fun syncServiceSlot(slot: Int) {
        val list = trackLists[slot]
        val uri = if (list.isEmpty()) null
                  else list[activeIndex[slot].coerceIn(0, list.size - 1)]
        if (slot == 0) playerService?.track0 = uri else playerService?.track1 = uri
    }

    private fun updateButtonStates() {
        val has0 = trackLists[0].isNotEmpty()
        val has1 = trackLists[1].isNotEmpty()
        binding.btnPlay0.isEnabled = has0
        binding.btnPlay1.isEnabled = has1
        binding.btnRemoveTrack0.isEnabled = has0
        binding.btnRemoveTrack1.isEnabled = has1
    }

    private fun spinnerForSlot(slot: Int) =
        if (slot == 0) binding.spinnerSlot0 else binding.spinnerSlot1

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

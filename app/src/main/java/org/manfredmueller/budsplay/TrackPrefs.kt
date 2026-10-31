package org.manfredmueller.budsplay

import android.content.Context
import android.net.Uri

/**
 * Speichert pro Slot (0, 1, 2) einen einzelnen Track + dessen Settings.
 */
object TrackPrefs {

    private const val PREFS_NAME = "track_prefs"
    private fun keyTrack(slot: Int) = "track_$slot"
    private fun keyRepeat(slot: Int) = "repeat_$slot"
    private fun keySleep(slot: Int) = "sleep_$slot"
    private fun keyEq(slot: Int) = "eq_$slot"
    private const val KEY_CURRENT_SLOT = "current_slot"

    // ── Speichern ────────────────────────────────────────────────────

    fun saveTrack(context: Context, slot: Int, uri: Uri?) {
        val value = uri?.toString() ?: ""
        prefs(context).edit().putString(keyTrack(slot), value).apply()
    }

    fun saveRepeatMode(context: Context, slot: Int, mode: Int) {
        prefs(context).edit().putInt(keyRepeat(slot), mode).apply()
    }

    fun saveSleepTimer(context: Context, slot: Int, minutes: Int) {
        prefs(context).edit().putInt(keySleep(slot), minutes).apply()
    }

    fun saveEqPreset(context: Context, slot: Int, preset: Int) {
        prefs(context).edit().putInt(keyEq(slot), preset).apply()
    }

    fun saveCurrentSlot(context: Context, slot: Int) {
        prefs(context).edit().putInt(KEY_CURRENT_SLOT, slot).apply()
    }

    // ── Laden ────────────────────────────────────────────────────────

    fun loadTrack(context: Context, slot: Int): Uri? {
        val raw = prefs(context).getString(keyTrack(slot), "") ?: ""
        if (raw.isEmpty()) return null
        val uri = Uri.parse(raw)
        return if (isReadable(context, uri)) uri else null
    }

    fun loadRepeatMode(context: Context, slot: Int): Int =
        prefs(context).getInt(keyRepeat(slot), 0)  // 0 = No Repeat

    fun loadSleepTimer(context: Context, slot: Int): Int =
        prefs(context).getInt(keySleep(slot), 0)  // 0 = off

    fun loadEqPreset(context: Context, slot: Int): Int =
        prefs(context).getInt(keyEq(slot), 2)  // 2 = Relax (default)

    fun loadCurrentSlot(context: Context): Int =
        prefs(context).getInt(KEY_CURRENT_SLOT, 1)  // Default = Slot 1

    // ── Helpers ──────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun isReadable(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.close()
        true
    } catch (e: Exception) { false }
}

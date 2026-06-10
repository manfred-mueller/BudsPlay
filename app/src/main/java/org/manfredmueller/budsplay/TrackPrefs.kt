package org.manfredmueller.budsplay

import android.content.Context
import android.net.Uri
import org.json.JSONArray

/**
 * Speichert pro Slot eine Liste von URIs und den Index des aktiven Tracks.
 * Slot 0 = Doppeltippen rechts, Slot 1 = Dreifachtippen rechts
 */
object TrackPrefs {

    private const val PREFS_NAME = "track_prefs"
    private fun keyList(slot: Int)   = "track_list_$slot"
    private fun keyActive(slot: Int) = "track_active_$slot"

    // ── Speichern ────────────────────────────────────────────────────

    fun saveList(context: Context, slot: Int, uris: List<Uri>) {
        val arr = JSONArray().apply { uris.forEach { put(it.toString()) } }
        prefs(context).edit().putString(keyList(slot), arr.toString()).apply()
    }

    fun saveActive(context: Context, slot: Int, index: Int) {
        prefs(context).edit().putInt(keyActive(slot), index).apply()
    }

    // ── Laden ────────────────────────────────────────────────────────

    fun loadList(context: Context, slot: Int): MutableList<Uri> {
        val raw = prefs(context).getString(keyList(slot), null) ?: return mutableListOf()
        val arr = try { JSONArray(raw) } catch (e: Exception) { return mutableListOf() }
        val result = mutableListOf<Uri>()
        for (i in 0 until arr.length()) {
            val uri = Uri.parse(arr.getString(i))
            // URI-Berechtigung prüfen
            if (isReadable(context, uri)) result.add(uri)
        }
        // Liste bereinigt zurückspeichern falls URIs weggefallen sind
        if (result.size < arr.length()) saveList(context, slot, result)
        return result
    }

    fun loadActive(context: Context, slot: Int): Int =
        prefs(context).getInt(keyActive(slot), 0)

    /** Gibt die aktuell aktive URI zurück, oder null wenn Liste leer */
    fun loadActiveUri(context: Context, slot: Int): Uri? {
        val list = loadList(context, slot)
        if (list.isEmpty()) return null
        val idx = loadActive(context, slot).coerceIn(0, list.size - 1)
        return list[idx]
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun isReadable(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.close()
        true
    } catch (e: Exception) { false }
}

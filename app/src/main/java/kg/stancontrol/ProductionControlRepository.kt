package kg.stancontrol

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns persistence for the current ProControl production entries.
 *
 * The JSON keys and SharedPreferences key are intentionally kept identical
 * to the legacy format so existing installations remain fully compatible.
 */
internal class ProductionControlRepository(
    private val preferences: SharedPreferences
) {
    fun loadEntries(): MutableList<ProductionEntry> {
        val loadedEntries = mutableListOf<ProductionEntry>()

        try {
            val serializedEntries = preferences.getString(ENTRIES_KEY, EMPTY_JSON_ARRAY)
                ?: EMPTY_JSON_ARRAY
            val entriesArray = JSONArray(serializedEntries)

            for (index in 0 until entriesArray.length()) {
                val entryObject = entriesArray.getJSONObject(index)
                loadedEntries += ProductionEntry(
                    date = entryObject.getString(DATE_KEY),
                    clean = entryObject.optDouble(CLEAN_KEY),
                    scrap = entryObject.optDouble(SCRAP_KEY),
                    note = entryObject.optString(NOTE_KEY)
                )
            }
        } catch (exception: Exception) {
            // Preserve successfully parsed records, matching the previous recovery behavior.
            Log.w(TAG, "Could not fully read saved production entries", exception)
        }

        return loadedEntries.apply {
            sortByDescending(ProductionEntry::date)
        }
    }

    fun saveEntries(entries: List<ProductionEntry>) {
        val entriesArray = JSONArray()

        entries.forEach { entry ->
            entriesArray.put(
                JSONObject()
                    .put(DATE_KEY, entry.date)
                    .put(CLEAN_KEY, entry.clean)
                    .put(SCRAP_KEY, entry.scrap)
                    .put(NOTE_KEY, entry.note)
            )
        }

        preferences.edit()
            .putString(ENTRIES_KEY, entriesArray.toString())
            .apply()
    }

    private companion object {
        const val TAG = "ProControlRepository"
        const val ENTRIES_KEY = "entries"
        const val EMPTY_JSON_ARRAY = "[]"

        const val DATE_KEY = "date"
        const val CLEAN_KEY = "clean"
        const val SCRAP_KEY = "scrap"
        const val NOTE_KEY = "note"
    }
}

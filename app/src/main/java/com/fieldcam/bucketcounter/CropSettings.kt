package com.fieldcam.bucketcounter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * One crop preset: a display name and the weight (kg) of a single full bucket
 * for that crop. Bucket weight depends on bucket volume and crop bulk density,
 * so these are just editable starting points, not fixed constants.
 */
data class CropPreset(val name: String, val weightKg: Double)

/**
 * Persists the list of crop presets + which one is currently selected in
 * SharedPreferences (same prefs file MainActivity already uses for the line
 * position), encoded as a small JSON array so no extra dependency is needed.
 */
object CropSettingsStore {
    private const val PREFS = "bucket_counter"
    private const val KEY_PRESETS = "crop_presets"
    private const val KEY_SELECTED = "crop_selected_index"

    // Rough starting defaults for a ~1.5-2 m^3 front-loader bucket - edit freely in Settings.
    val defaults = listOf(
        CropPreset("Пшеница", 800.0),
        CropPreset("Ячмень", 700.0),
        CropPreset("Кукуруза", 750.0),
        CropPreset("Подсолнечник", 450.0),
        CropPreset("Рапс", 600.0),
        CropPreset("Нут", 750.0)
    )

    fun load(context: Context): Pair<MutableList<CropPreset>, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PRESETS, null)
        val presets: MutableList<CropPreset> = if (json == null) {
            defaults.toMutableList()
        } else {
            try {
                val arr = JSONArray(json)
                val list = ArrayList<CropPreset>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(CropPreset(o.getString("name"), o.getDouble("weightKg")))
                }
                if (list.isEmpty()) defaults.toMutableList() else list
            } catch (t: Throwable) {
                defaults.toMutableList()
            }
        }
        val selected = prefs.getInt(KEY_SELECTED, 0).coerceIn(0, presets.size - 1)
        return presets to selected
    }

    fun save(context: Context, presets: List<CropPreset>, selectedIndex: Int) {
        val arr = JSONArray()
        for (p in presets) {
            val o = JSONObject()
            o.put("name", p.name)
            o.put("weightKg", p.weightKg)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRESETS, arr.toString())
            .putInt(KEY_SELECTED, selectedIndex)
            .apply()
    }
}

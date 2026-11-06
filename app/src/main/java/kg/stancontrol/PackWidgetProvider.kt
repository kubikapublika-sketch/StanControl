package kg.stancontrol

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.round

class PackWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val prefs = context.getSharedPreferences("stancontrol", Context.MODE_PRIVATE)
        val current = prefs.getString("widget_weight_input", "") ?: ""
        when (intent.action) {
            ACTION_KEY -> {
                val key = intent.getStringExtra("key") ?: return
                val next = when (key) {
                    "DEL" -> current.dropLast(1)
                    "CLR" -> ""
                    else -> if (current.length < 7) current + key else current
                }
                prefs.edit().putString("widget_weight_input", next).apply()
            }
            ACTION_ROWS -> {
                val profile = prefs.getString("profile", "Уголок 50") ?: "Уголок 50"
                val key = profile.substringAfterLast(" ")
                val defaultRows = if (key == "40") 20 else 15
                val prefKey = "profile_${key}_rows"
                val rows = (prefs.getString(prefKey, defaultRows.toString())?.toIntOrNull() ?: defaultRows)
                val delta = intent.getIntExtra("delta", 0)
                prefs.edit().putString(prefKey, (rows + delta).coerceIn(1, 99).toString()).apply()
            }
            ACTION_ADD -> {
                val weight = current.toIntOrNull()
                val active = prefs.getBoolean("active", false)
                if (weight != null && weight > 0 && active) {
                    val profile = prefs.getString("profile", "Уголок 50") ?: "Уголок 50"
                    val key = profile.substringAfterLast(" ")
                    val defaultWidth = when (key) { "40" -> 7; "30" -> 6; else -> 5 }
                    val defaultRows = if (key == "40") 20 else 15
                    val width = prefs.getInt("width", prefs.getString("profile_${key}_width", defaultWidth.toString())?.toIntOrNull() ?: defaultWidth)
                    val rows = prefs.getString("profile_${key}_rows", defaultRows.toString())?.toIntOrNull() ?: defaultRows
                    val length = prefs.getString("profile_${key}_length", "6")?.replace(',', '.')?.toDoubleOrNull() ?: 6.0
                    val count = (width * 2 - 1) * rows - (width - 1)
                    if (count > 0 && length > 0) {
                        val coefficient = round(weight.toDouble() / (count * length) * 10.0) / 10.0
                        val packs = try { JSONArray(prefs.getString("packs", "[]")) } catch (_: Exception) { JSONArray() }
                        packs.put(JSONObject().put("w", weight).put("r", rows).put("n", count).put("k", coefficient).put("width", width).put("length", length))
                        prefs.edit().putString("packs", packs.toString()).putString("widget_weight_input", "").apply()
                    }
                }
            }
        }
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PackWidgetProvider::class.java))
        ids.forEach { update(context, manager, it) }
    }

    companion object {
        const val ACTION_KEY = "kg.stancontrol.WIDGET_KEY"
        const val ACTION_ADD = "kg.stancontrol.WIDGET_ADD"
        const val ACTION_ROWS = "kg.stancontrol.WIDGET_ROWS"

        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences("stancontrol", Context.MODE_PRIVATE)
            val views = RemoteViews(context.packageName, R.layout.widget_pack)
            val packs = try { JSONArray(prefs.getString("packs", "[]")) } catch (_: Exception) { JSONArray() }
            val last = packs.optJSONObject(packs.length() - 1)
            val lastText = if (last == null) "Пока нет пачек" else {
                val coefficient = last.optDouble("w", 0.0) / (last.optInt("n", 1).coerceAtLeast(1) * last.optDouble("length", 6.0).coerceAtLeast(0.1))
                "${packs.length()}) ${last.optInt("w")} кг  ·  К ${DecimalFormat("0.0", DecimalFormatSymbols(Locale("ru"))).format(coefficient)}"
            }
            val profile = prefs.getString("profile", "Уголок 50") ?: "Уголок 50"
            val key = profile.substringAfterLast(" ")
            val defaultRows = if (key == "40") 20 else 15
            val rows = prefs.getString("profile_${key}_rows", defaultRows.toString())?.toIntOrNull() ?: defaultRows
            views.setTextViewText(R.id.widget_last_pack, lastText)
            views.setTextViewText(R.id.widget_weight, prefs.getString("widget_weight_input", "")?.ifBlank { "Вес, кг" } ?: "Вес, кг")
            views.setTextViewText(R.id.widget_rows, "СЛОИ  ·  $rows")
            views.setTextViewText(R.id.widget_status, if (prefs.getBoolean("active", false)) "СМЕНА ОТКРЫТА" else "Сначала начни смену в приложении")

            val keys = listOf(
                R.id.key_7 to "7", R.id.key_8 to "8", R.id.key_9 to "9",
                R.id.key_4 to "4", R.id.key_5 to "5", R.id.key_6 to "6",
                R.id.key_1 to "1", R.id.key_2 to "2", R.id.key_3 to "3",
                R.id.key_del to "DEL", R.id.key_0 to "0", R.id.key_clear to "CLR"
            )
            keys.forEach { (viewId, keyValue) ->
                val intent = Intent(context, PackWidgetProvider::class.java).setAction(ACTION_KEY).putExtra("key", keyValue)
                val pi = PendingIntent.getBroadcast(context, viewId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(viewId, pi)
            }
            listOf(R.id.row_minus to -1, R.id.row_plus to 1).forEach { (viewId, delta) ->
                val intent = Intent(context, PackWidgetProvider::class.java).setAction(ACTION_ROWS).putExtra("delta", delta)
                val pi = PendingIntent.getBroadcast(context, viewId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(viewId, pi)
            }
            val addIntent = Intent(context, PackWidgetProvider::class.java).setAction(ACTION_ADD)
            val addPi = PendingIntent.getBroadcast(context, 1001, addIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_add, addPi)
            manager.updateAppWidget(id, views)
        }
    }
}

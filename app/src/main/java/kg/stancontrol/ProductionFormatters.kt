package kg.stancontrol

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

/**
 * Locale-aware formatting helpers shared by production screens and exports.
 */
internal fun fmtWhole(value: Double): String = String.format(Locale.getDefault(), "%.0f", value)

internal fun drawHandoverSignature(canvas: android.graphics.Canvas, paint: Paint) {
    val value = prefs.getString("shift_handover_text", "")?.trim().orEmpty()
    if (value.isBlank()) return
    paint.color = android.graphics.Color.BLACK
    paint.textSize = 8f
    paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    val maxWidth = 540f
    val shown = if (paint.measureText(value) <= maxWidth) value else {
        var shortened = value
        while (shortened.isNotEmpty() && paint.measureText("…" + shortened) > maxWidth) {
            shortened = shortened.drop(1)
        }
        "…" + shortened
    }
    canvas.drawText(shown, 577f - paint.measureText(shown), 832f, paint)
}

internal fun today(): String = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
internal fun fmt(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
internal fun fmtWeight(value: Double): String = if (kotlin.math.abs(value - round(value)) < 0.000001) String.format(Locale.getDefault(), "%.0f", value) else fmt(value)
internal fun fmtCoefficient(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

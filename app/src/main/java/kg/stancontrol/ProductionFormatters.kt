package kg.stancontrol

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.round

/**
 * Locale-aware formatting helpers shared by production screens and exports.
 */
internal fun fmtWhole(value: Double): String = String.format(Locale.getDefault(), "%.0f", value)

internal fun today(): String = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
internal fun fmt(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
internal fun fmtWeight(value: Double): String = if (kotlin.math.abs(value - round(value)) < 0.000001) String.format(Locale.getDefault(), "%.0f", value) else fmt(value)
internal fun fmtCoefficient(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

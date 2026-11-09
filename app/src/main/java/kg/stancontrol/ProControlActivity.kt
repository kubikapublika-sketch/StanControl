package kg.stancontrol

import android.app.Activity
import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.view.ScaleGestureDetector
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.CRC32
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProControlActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("procontrol", MODE_PRIVATE) }
    private val repository by lazy { ProductionControlRepository(prefs) }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayDate = SimpleDateFormat("dd.MM.yyyy", Locale("ru"))
    private var cleanRate = 80.0
    private var scrapRate = 75.0
    private var entries = mutableListOf<ProductionEntry>()
    private var periodStart = ""
    private var archiveEditing = false
    private var selectedArchiveIndex = -1
    private var visibleMonth = java.util.Calendar.getInstance().apply { set(java.util.Calendar.DAY_OF_MONTH, 1) }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cleanRate = prefs.getString("clean_rate", "80")?.toDoubleOrNull() ?: 80.0
        scrapRate = prefs.getString("scrap_rate", "75")?.toDoubleOrNull() ?: 75.0
        periodStart = prefs.getString("period_start", null) ?: dateFormat.format(Date()).also {
            prefs.edit().putString("period_start", it).apply()
        }
        archiveEditing = prefs.getBoolean("archive_editing", false)
        entries = repository.loadEntries()
        // Legacy migration is allowed only before the first period is archived.
        // Never move the current period boundary backwards after a user closes a period:
        // doing so pulls old shifts back into the current totals on the next app launch.
        val existingPeriods = try { JSONArray(prefs.getString("archive", "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val earliestCurrentEntry = entries.minOfOrNull { it.date }
        if (existingPeriods.length() == 0 && earliestCurrentEntry != null && earliestCurrentEntry < periodStart) {
            periodStart = earliestCurrentEntry
            prefs.edit().putString("period_start", periodStart).commit()
        }
        render()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundColor(0xFFF3F5F8.toInt())
        }
        root.addView(label("ProControl", 25f, 0xFF17243A.toInt(), true))
        val navRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val navRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun navButton(caption: String, action: () -> Unit): Button = Button(this).apply {
            text = caption
            isAllCaps = false
            textSize = 13f
            setTextColor(0xFF24344D.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE1E8F1.toInt())
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                setMargins(dp(3), dp(2), dp(3), dp(5))
            }
        }
        navRow1.addView(navButton("Итоги", ::showOverallStats))
        navRow1.addView(navButton("Графики", ::showCharts))
        navRow2.addView(navButton("Периоды", ::showArchive))
        navRow2.addView(navButton("Настройки", ::showRatesDialog))
        root.addView(navRow1)
        root.addView(navRow2)
        root.addView(label("Учёт тоннажа и расчёт заработка", 13f, 0xFF68758A.toInt()))

        val cleanTotal = entries.sumOf { it.clean }
        val scrapTotal = entries.sumOf { it.scrap }
        val totalTonnage = cleanTotal + scrapTotal
        val scrapPercent = if (totalTonnage > 0.0) scrapTotal / totalTonnage * 100.0 else 0.0
        val earnings = cleanTotal * cleanRate + scrapTotal * scrapRate
        val today = dateFormat.format(Date())
        val days = calendarDays()
        val averagePerDay = if (days > 0) earnings / days else 0.0
        val archive = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        val currentNumber = nextPeriodNumber(archive)
        val summary = card()
        summary.addView(label("ТЕКУЩИЙ ПЕРИОД $currentNumber", 12f, 0xFF68758A.toInt(), true))
        summary.addView(label(formatDate(periodStart) + " - " + formatDate(today) + " · открыт", 14f, 0xFF17243A.toInt(), true))
        summary.addView(label("ЧИСТАЯ ПРОДУКЦИЯ", 12f, 0xFF68758A.toInt(), true))
        summary.addView(label(qty(cleanTotal) + " кг", 25f, 0xFF17243A.toInt(), true))
        summary.addView(label("Брак: " + qty(scrapTotal) + " кг", 15f, 0xFFB45A32.toInt(), true))
        summary.addView(label("Процент брака от общего веса: " + String.format(Locale("ru"), "%.1f", scrapPercent) + "%", 14f, 0xFFB45A32.toInt(), true))
        summary.addView(label("Начислено всего", 13f, 0xFF68758A.toInt()))
        summary.addView(label(money(earnings) + " сом", 25f, 0xFF087B58.toInt(), true))
        summary.addView(label("Средний заработок в календарный день", 13f, 0xFF68758A.toInt()))
        summary.addView(label(money(averagePerDay) + " сом/день", 19f, 0xFF1557A6.toInt(), true))
        summary.addView(label("Календарных дней: $days · Смен внесено: " + entries.size, 12f, 0xFF68758A.toInt()))
        root.addView(summary)
        val calendarCard = card()
        val monthHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        monthHeader.addView(Button(this).apply {
            text = "‹"
            setOnClickListener { visibleMonth.add(java.util.Calendar.MONTH, -1); render() }
        })
        val monthTitle = java.text.SimpleDateFormat("LLLL yyyy", Locale("ru")).format(visibleMonth.time)
            .replaceFirstChar { it.uppercase(Locale("ru")) }
        monthHeader.addView(label(monthTitle, 19f, 0xFF17243A.toInt(), true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, -2, 1f))
        monthHeader.addView(Button(this).apply {
            text = "›"
            setOnClickListener { visibleMonth.add(java.util.Calendar.MONTH, 1); render() }
        })
        calendarCard.addView(monthHeader)

        val weekdays = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        val weekHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        weekdays.forEach { dayName ->
            weekHeader.addView(TextView(this).apply {
                text = dayName; gravity = Gravity.CENTER; textSize = 12f
                setTextColor(0xFF68758A.toInt())
            }, LinearLayout.LayoutParams(0, dp(30), 1f))
        }
        calendarCard.addView(weekHeader)

        val firstDay = visibleMonth.clone() as java.util.Calendar
        firstDay.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val offset = (firstDay.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
        val daysInMonth = firstDay.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        val rows = (offset + daysInMonth + 6) / 7
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (week in 0 until rows) {
            val weekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (column in 0 until 7) {
                val dayNumber = week * 7 + column - offset + 1
                if (dayNumber in 1..daysInMonth) {
                    val dayCalendar = firstDay.clone() as java.util.Calendar
                    dayCalendar.set(java.util.Calendar.DAY_OF_MONTH, dayNumber)
                    val dayDate = dateFormat.format(dayCalendar.time)
                    val saved = findCalendarEntry(dayDate)
                    val previousCalendar = (dayCalendar.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_MONTH, -1) }
                    val previousEntry = findCalendarEntry(dateFormat.format(previousCalendar.time))
                    val trend = if (saved != null && previousEntry != null) when {
                        saved.clean + saved.scrap > previousEntry.clean + previousEntry.scrap -> " 📈"
                        saved.clean + saved.scrap < previousEntry.clean + previousEntry.scrap -> " 📉"
                        else -> " ➖"
                    } else ""
                    val cell = TextView(this).apply {
                        text = dayNumber.toString() + if (saved != null) "  •" + trend else ""
                        gravity = Gravity.CENTER
                        textSize = 14f
                        setTextColor(if (saved != null) 0xFF087B58.toInt() else 0xFF17243A.toInt())
                        setBackgroundColor(when {
                            dayDate == today -> 0xFFDCEBFF.toInt()
                            saved != null -> 0xFFE4F4EC.toInt()
                            else -> 0xFFFFFFFF.toInt()
                        })
                        setOnClickListener { showCalendarEntry(dayDate) }
                    }
                    weekRow.addView(cell, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    })
                } else {
                    weekRow.addView(View(this), LinearLayout.LayoutParams(0, dp(48), 1f))
                }
            }
            grid.addView(weekRow)
        }
        calendarCard.addView(grid)
        calendarCard.addView(label("Нажми на любую дату, чтобы внести или изменить данные. Точка означает, что запись уже есть.", 12f, 0xFF68758A.toInt()))
        root.addView(calendarCard)

        root.addView(Button(this).apply {
            text = "Закрыть период"
            setOnClickListener { closePeriod() }
            layoutParams = matchWrap()
        })

        val listHeader = label("Записи текущего периода", 19f, 0xFF17243A.toInt(), true)
        listHeader.setPadding(0, dp(14), 0, dp(8))
        root.addView(listHeader)
        if (entries.isEmpty()) {
            root.addView(label("Пока нет записей в текущем периоде.", 14f, 0xFF68758A.toInt()))
        } else {
            entries.sortedByDescending { it.date }.forEach { entry ->
                val row = card()
                row.addView(label(formatDate(entry.date), 15f, 0xFF17243A.toInt(), true))
                row.addView(label("Чистая: " + qty(entry.clean) + " кг · Брак: " + qty(entry.scrap) + " кг", 13f, 0xFF34445B.toInt()))
                row.addView(label(money(entry.clean * cleanRate + entry.scrap * scrapRate) + " сом", 16f, 0xFF087B58.toInt(), true))
                if (entry.note.isNotBlank()) row.addView(label(entry.note, 12f, 0xFF68758A.toInt()))
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                actions.addView(Button(this).apply {
                    text = "Изменить"
                    isAllCaps = false
                    setOnClickListener { showCalendarEntry(entry.date) }
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(5) })
                actions.addView(Button(this).apply {
                    text = "Поделиться"
                    isAllCaps = false
                    setOnClickListener { shareProductionReceipt(entry.date) }
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(5) })
                row.addView(actions)
                root.addView(row)
            }
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(root, android.view.ViewGroup.LayoutParams(-1, -2))
        })
    }

    private fun findCalendarEntry(date: String): ProductionEntry? {
        entries.firstOrNull { it.date == date }?.let { return it }
        val history = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            if (item.optString("date") == date) return ProductionEntry(date, item.optDouble("clean"), item.optDouble("scrap"), item.optString("note"))
        }
        val archive = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        for (i in 0 until archive.length()) {
            val records = archive.optJSONObject(i)?.optJSONArray("records") ?: continue
            for (j in 0 until records.length()) {
                val item = records.optJSONObject(j) ?: continue
                if (item.optString("date") == date) return ProductionEntry(date, item.optDouble("clean"), item.optDouble("scrap"), item.optString("note"))
            }
        }
        return null
    }

    private fun shareProductionReceipt(selectedDate: String) {
        if (Build.VERSION.SDK_INT < 29) {
            toast("Для отправки картинки требуется Android 10 или новее")
            return
        }
        val selectedEntry = findCalendarEntry(selectedDate)
        if (selectedEntry == null) { toast("За выбранную дату нет записи"); return }
        val previousCal = java.util.Calendar.getInstance().apply {
            time = dateFormat.parse(selectedDate) ?: Date()
            add(java.util.Calendar.DAY_OF_MONTH, -1)
        }
        val previousEntry = findCalendarEntry(dateFormat.format(previousCal.time))
        val selectedWeight = selectedEntry.clean + selectedEntry.scrap
        val previousWeight = previousEntry?.let { it.clean + it.scrap }
        val isGreen = previousWeight == null || selectedWeight >= previousWeight

        var periodClean = 0.0
        var periodScrap = 0.0
        if (selectedDate >= periodStart && selectedDate <= dateFormat.format(Date())) {
            periodClean = entries.sumOf { it.clean }
            periodScrap = entries.sumOf { it.scrap }
        } else {
            val archive = JSONArray(prefs.getString("archive", "[]") ?: "[]")
            var matchingRecords: JSONArray? = null
            for (i in 0 until archive.length()) {
                val period = archive.optJSONObject(i) ?: continue
                val records = period.optJSONArray("records") ?: continue
                val inRange = selectedDate >= period.optString("start") && selectedDate <= period.optString("end")
                val hasDate = (0 until records.length()).any { records.optJSONObject(it)?.optString("date") == selectedDate }
                if (inRange || hasDate) { matchingRecords = records; break }
            }
            if (matchingRecords != null) {
                for (i in 0 until matchingRecords.length()) {
                    val item = matchingRecords.optJSONObject(i) ?: continue
                    periodClean += item.optDouble("clean")
                    periodScrap += item.optDouble("scrap")
                }
            } else {
                periodClean = selectedEntry.clean
                periodScrap = selectedEntry.scrap
            }
        }
        val periodWeight = periodClean + periodScrap
        val periodScrapPercent = if (periodWeight > 0.0) periodScrap * 100.0 / periodWeight else 0.0
        val background = if (isGreen) Color.rgb(235, 249, 237) else Color.rgb(255, 238, 238)
        val accent = if (isGreen) Color.rgb(34, 150, 75) else Color.rgb(205, 55, 55)

        val bitmap = Bitmap.createBitmap(900, 520, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = background
        canvas.drawRoundRect(18f, 18f, 882f, 502f, 34f, 34f, paint)
        fun drawText(text: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
            paint.color = color
            paint.textSize = size
            paint.typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            canvas.drawText(text, x, y, paint)
        }
        val dark = Color.rgb(35, 48, 42)
        val muted = Color.rgb(100, 115, 105)
        drawText("PROCONTROL", 58f, 82f, 34f, dark, true)
        drawText(if (isGreen) "▲ РОСТ" else "▼ СНИЖЕНИЕ", 670f, 82f, 25f, accent, true)
        drawText(formatDate(selectedDate), 58f, 124f, 22f, muted)
        // Главный показатель чека — общий вес за день. Чистая продукция и брак идут ниже.
        drawText(qty(selectedWeight) + " кг", 58f, 220f, 82f, accent, true)
        drawText("ОБЩИЙ ВЕС ЗА ДЕНЬ", 60f, 258f, 24f, muted, true)
        drawText("Чистая: " + qty(selectedEntry.clean) + " кг", 60f, 315f, 26f, dark, true)
        drawText("Брак: " + qty(selectedEntry.scrap) + " кг", 470f, 315f, 26f, dark, true)
        paint.color = if (isGreen) Color.rgb(199, 230, 202) else Color.rgb(244, 203, 203)
        canvas.drawLine(58f, 350f, 842f, 350f, paint)
        drawText("ЗА ПЕРИОД", 58f, 395f, 22f, muted, true)
        drawText("Чистая: " + qty(periodClean) + " кг", 58f, 445f, 26f, dark, true)
        drawText("Брак: " + qty(periodScrap) + " кг", 430f, 445f, 26f, dark, true)
        drawText("Общий вес: " + qty(periodWeight) + " кг", 58f, 482f, 22f, dark, true)
        drawText("Брак " + String.format(Locale("ru"), "%.1f", periodScrapPercent) + "%", 650f, 482f, 22f, muted)

        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "ProControl_${selectedDate}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ProControl")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Не удалось создать файл")
            contentResolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw Exception("Не удалось сохранить картинку")
            } ?: throw Exception("Не удалось открыть файл")
            bitmap.recycle()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Отправить чек"))
        } catch (e: Exception) {
            bitmap.recycle()
            toast("Не удалось подготовить чек: ${e.message}")
        }
    }

    private fun showCalendarEntry(date: String) {
        if (date > dateFormat.format(Date())) { toast("Будущие даты пока нельзя заполнять"); return }
        val old = findCalendarEntry(date)
        val cleanInput = numberInput("Чистая продукция, кг").apply {
            if (old != null) setText((old.clean * 1000.0).toLong().toString())
        }
        val scrapInput = numberInput("Брак, кг").apply {
            if (old != null) setText((old.scrap * 1000.0).toLong().toString())
        }
        val noteInput = EditText(this).apply {
            hint = "Комментарий (необязательно)"; textSize = 15f
            if (old != null) setText(old.note)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(Button(this@ProControlActivity).apply {
                text = "Поделиться чеком"
                isAllCaps = false
                setOnClickListener {
                    shareProductionReceipt(date)
                }
            }, matchWrap())
            addView(fieldCaption("Чистая продукция, кг")); addView(cleanInput, matchWrap())
            addView(fieldCaption("Брак, кг")); addView(scrapInput, matchWrap())
            addView(fieldCaption("Комментарий")); addView(noteInput, matchWrap())
        }
        val builder = AlertDialog.Builder(this).setTitle(formatDate(date)).setView(box)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null)
        if (old != null) builder.setNeutralButton("Удалить", null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val cleanKg = cleanInput.text.toString().replace(',', '.').toDoubleOrNull()
                val scrapKg = scrapInput.text.toString().replace(',', '.').toDoubleOrNull()
                if (cleanKg == null || scrapKg == null || cleanKg < 0 || scrapKg < 0) {
                    toast("Введи оба значения в кг. Ноль тоже можно."); return@setOnClickListener
                }
                saveCalendarEntry(ProductionEntry(date, cleanKg / 1000.0, scrapKg / 1000.0, noteInput.text.toString().trim()))
                dialog.dismiss()
                toast("Запись за " + formatDate(date) + " сохранена")
                render()
            }
            if (old != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                    AlertDialog.Builder(this).setMessage("Удалить запись за " + formatDate(date) + "?")
                        .setNegativeButton("Отмена", null)
                        .setPositiveButton("Удалить") { _, _ ->
                            deleteCalendarEntry(date); dialog.dismiss(); render()
                        }.show()
                }
            }
        }
        dialog.show()
    }

    private fun saveCalendarEntry(entry: ProductionEntry) {
        val date = entry.date
        val archive = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        var archiveIndex = -1
        for (i in 0 until archive.length()) {
            val period = archive.optJSONObject(i) ?: continue
            val start = period.optString("start"); val end = period.optString("end")
            val inRange = start.isNotBlank() && end.isNotBlank() && date >= start && date <= end
            val hasRecord = (period.optJSONArray("records") ?: JSONArray()).let { records ->
                (0 until records.length()).any { records.optJSONObject(it)?.optString("date") == date }
            }
            if (inRange || hasRecord) { archiveIndex = i; break }
        }
        if (archiveIndex >= 0) {
            val period = archive.getJSONObject(archiveIndex)
            val records = period.optJSONArray("records") ?: JSONArray()
            val index = (0 until records.length()).firstOrNull { records.optJSONObject(it)?.optString("date") == date }
            val saved = JSONObject().put("date", date).put("clean", entry.clean).put("scrap", entry.scrap).put("note", entry.note)
            if (index == null) records.put(saved) else records.put(index, saved)
            period.put("records", records).put("clean", sumRecords(records, "clean")).put("scrap", sumRecords(records, "scrap"))
            archive.put(archiveIndex, period)
            entries.removeAll { it.date == date }
            repository.saveEntries(entries)
            prefs.edit().putString("archive", archive.toString()).apply()
            return
        }

        val history = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
        val historicalIndex = (0 until history.length()).firstOrNull { history.optJSONObject(it)?.optString("date") == date }
        if (historicalIndex != null) {
            history.put(historicalIndex, JSONObject().put("date", date).put("clean", entry.clean)
                .put("scrap", entry.scrap).put("note", entry.note))
            prefs.edit().putString("historical_entries", history.toString()).apply()
            return
        }

        val currentIndex = entries.indexOfFirst { it.date == date }
        if (currentIndex >= 0) entries[currentIndex] = entry else entries.add(entry)
        entries.sortByDescending { it.date }
        repository.saveEntries(entries)
    }

    private fun deleteCalendarEntry(date: String) {
        val archive = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        for (i in 0 until archive.length()) {
            val period = archive.optJSONObject(i) ?: continue
            val records = period.optJSONArray("records") ?: JSONArray()
            val index = (0 until records.length()).firstOrNull { records.optJSONObject(it)?.optString("date") == date }
            if (index != null) {
                records.remove(index)
                period.put("records", records).put("clean", sumRecords(records, "clean")).put("scrap", sumRecords(records, "scrap"))
                archive.put(i, period)
                prefs.edit().putString("archive", archive.toString()).apply()
                return
            }
        }
        val history = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
        val historicalIndex = (0 until history.length()).firstOrNull { history.optJSONObject(it)?.optString("date") == date }
        if (historicalIndex != null) {
            history.remove(historicalIndex)
            prefs.edit().putString("historical_entries", history.toString()).apply()
            return
        }
        entries.removeAll { it.date == date }
        repository.saveEntries(entries)
    }

    private fun editEntry(entry: ProductionEntry) {
        val dateInput = EditText(this).apply { setText(entry.date); hint = "ГГГГ-ММ-ДД" }
        val cleanInput = numberInput("Чистая продукция, кг").apply { setText((entry.clean * 1000).toLong().toString()) }
        val scrapInput = numberInput("Брак, кг").apply { setText((entry.scrap * 1000).toLong().toString()) }
        val noteInput = EditText(this).apply { setText(entry.note); hint = "Комментарий" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(fieldCaption("Дата")); addView(dateInput)
            addView(fieldCaption("Чистая продукция, кг")); addView(cleanInput)
            addView(fieldCaption("Брак, кг")); addView(scrapInput)
            addView(fieldCaption("Комментарий")); addView(noteInput)
        }
        AlertDialog.Builder(this).setTitle("Изменить запись").setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val date = dateInput.text.toString().trim()
                val clean = cleanInput.text.toString().replace(',', '.').toDoubleOrNull()
                val scrap = scrapInput.text.toString().replace(',', '.').toDoubleOrNull()
                if (!validDate(date) || clean == null || scrap == null || clean < 0 || scrap < 0) {
                    toast("Проверь дату и значения в кг"); return@setPositiveButton
                }
                if (date > dateFormat.format(Date())) {
                    toast("Нельзя поставить будущую дату"); return@setPositiveButton
                }
                if (allKnownDates().contains(date) && date != entry.date) {
                    toast("За эту дату запись уже существует"); return@setPositiveButton
                }
                entries.remove(entry)
                entries.add(ProductionEntry(date, clean / 1000.0, scrap / 1000.0, noteInput.text.toString().trim()))
                entries.sortByDescending { it.date }
                repository.saveEntries(entries)
                render()
            }.show()
    }

    private fun closePeriod() {
        val today = dateFormat.format(Date())
        val now = java.util.Calendar.getInstance()
        val picker = DatePickerDialog(this, { _, year, month, day ->
            val calendar = java.util.Calendar.getInstance().apply { set(year, month, day) }
            val end = dateFormat.format(calendar.time)
            if (end < periodStart || end > today) {
                toast("Дата закрытия должна быть внутри текущего периода")
                return@DatePickerDialog
            }
            confirmClosePeriod(end)
        }, now.get(java.util.Calendar.YEAR), now.get(java.util.Calendar.MONTH),
            now.get(java.util.Calendar.DAY_OF_MONTH))
        picker.datePicker.minDate = dateFormat.parse(periodStart)?.time ?: Date().time
        picker.datePicker.maxDate = Date().time
        picker.setTitle("Выбери последний день периода")
        picker.show()
    }

    private fun confirmClosePeriod(end: String) {
        val closingEntries = entries.filter { it.date >= periodStart && it.date <= end }
        val remainingEntries = entries.filter { it.date > end }.toMutableList()
        val clean = closingEntries.sumOf { it.clean }
        val scrap = closingEntries.sumOf { it.scrap }
        val pay = clean * cleanRate + scrap * scrapRate
        val days = calendarDaysBetween(periodStart, end)
        val average = if (days > 0) pay / days else 0.0
        AlertDialog.Builder(this)
            .setTitle("Закрыть расчётный период?")
            .setMessage("Период: " + formatDate(periodStart) + " – " + formatDate(end) +
                "\nЧистая продукция: " + qty(clean) + " кг\nБрак: " + qty(scrap) + " кг" +
                "\nНачислено: " + money(pay) + " сом\nСреднее за день: " + money(average) +
                " сом ($days календарных дней)\n\nЗаписи до выбранной даты попадут в архив. Более поздние записи останутся в новом периоде.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Закрыть") { _, _ ->
                val archive = JSONArray(prefs.getString("archive", "[]") ?: "[]")
                val recordArray = JSONArray()
                closingEntries.sortedBy { it.date }.forEach { e ->
                    recordArray.put(JSONObject().put("date", e.date).put("clean", e.clean)
                        .put("scrap", e.scrap).put("note", e.note))
                }
                val number = nextPeriodNumber(archive)
                archive.put(JSONObject().put("number", number).put("name", "Период $number")
                    .put("start", periodStart).put("end", end)
                    .put("clean", clean).put("scrap", scrap).put("records", recordArray))
                val nextStart = nextCalendarDate(end)
                entries = remainingEntries
                repository.saveEntries(entries)
                prefs.edit().putString("archive", archive.toString())
                    .putString("period_start", nextStart).commit()
                periodStart = nextStart
                selectedArchiveIndex = -1
                toast("Период закрыт по выбранную дату. Более поздние записи сохранены.")
                render()
            }.show()
    }

    private fun allEntries(): List<ProductionEntry> {
        val result = entries.toMutableList()
        val history = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
        for (i in 0 until history.length()) {
            val o = history.optJSONObject(i) ?: continue
            result.add(ProductionEntry(o.optString("date"), o.optDouble("clean"), o.optDouble("scrap"), o.optString("note")))
        }
        val a = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        for (i in 0 until a.length()) {
            val records = a.optJSONObject(i)?.optJSONArray("records") ?: continue
            for (j in 0 until records.length()) {
                val o = records.optJSONObject(j) ?: continue
                result.add(ProductionEntry(o.optString("date"), o.optDouble("clean"), o.optDouble("scrap"), o.optString("note")))
            }
        }
        return result.distinctBy { it.date }.sortedBy { it.date }
    }

    private fun showOverallStats() {
        val all = allEntries().sortedBy { it.date }
        if (all.isEmpty()) { toast("Пока нет данных для статистики"); return }
        val today = dateFormat.format(Date())
        var from = all.first().date
        var to = today
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val rangeLabel = label("", 13f, 0xFF68758A.toInt(), true)
        val summary = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val daily = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun refresh() {
            val selected = all.filter { it.date >= from && it.date <= to }
            val clean = selected.sumOf { it.clean }
            val scrap = selected.sumOf { it.scrap }
            val pay = clean * cleanRate + scrap * scrapRate
            val days = calendarDaysBetween(from, to)
            val workDays = selected.size
            val noShiftDays = (days - workDays).coerceAtLeast(0)
            val averageCalendar = if (days > 0) pay / days else 0.0
            val averageShift = if (workDays > 0) pay / workDays else 0.0
            val scrapPercent = if (clean + scrap > 0) scrap * 100.0 / (clean + scrap) else 0.0
            rangeLabel.text = "Период: ${formatDate(from)} - ${formatDate(to)}"
            summary.removeAllViews()
            summary.addView(label("ОБЩИЕ ИТОГИ", 12f, 0xFF68758A.toInt(), true))
            summary.addView(label("Заработок: ${money(pay)} сом", 22f, 0xFF087B58.toInt(), true))
            summary.addView(label("Чистая продукция: ${qty(clean)} кг", 15f, 0xFF26364D.toInt()))
            summary.addView(label("Брак: ${qty(scrap)} кг · ${String.format(Locale("ru"), "%.2f", scrapPercent)}%", 15f, 0xFF26364D.toInt()))
            summary.addView(label("Календарных дней: $days · Смен: $workDays · Без смен: $noShiftDays", 13f, 0xFF34445B.toInt()))
            summary.addView(label("Среднее: ${money(averageCalendar)} сом/день · ${money(averageShift)} сом/смену", 13f, 0xFF1557A6.toInt(), true))
            daily.removeAllViews()
            daily.addView(label("ПО ДНЯМ", 15f, 0xFF17243A.toInt(), true))
            if (selected.isEmpty()) daily.addView(label("За этот диапазон смен нет.", 13f, 0xFF68758A.toInt()))
            selected.sortedByDescending { it.date }.forEach { e ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(7), dp(8), dp(7))
                    setBackgroundColor(0xFFF3F5F8.toInt())
                }
                row.addView(label(formatDate(e.date) + " · " + money(e.clean * cleanRate + e.scrap * scrapRate) + " сом", 14f, 0xFF17243A.toInt(), true))
                row.addView(label("Чистая: ${qty(e.clean)} кг · Брак: ${qty(e.scrap)} кг", 12f, 0xFF536176.toInt()))
                if (e.note.isNotBlank()) row.addView(label(e.note, 12f, 0xFF68758A.toInt()))
                daily.addView(row)
            }
            val monthly = selected.groupBy { it.date.substring(0, 7) }.toSortedMap()
            if (monthly.isNotEmpty()) {
                daily.addView(label("ПО МЕСЯЦАМ", 15f, 0xFF17243A.toInt(), true))
                monthly.toSortedMap(compareByDescending { it }).forEach { (month, records) ->
                    val monthPay = records.sumOf { it.clean * cleanRate + it.scrap * scrapRate }
                    daily.addView(label("$month · ${records.size} смен · ${money(monthPay)} сом", 13f, 0xFF34445B.toInt()))
                }
            }
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun preset(labelText: String, start: String) {
            controls.addView(Button(this).apply {
                text = labelText
                setOnClickListener {
                    from = start
                    to = today
                    refresh()
                }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        preset("Всё", all.first().date)
        preset("Месяц", today.substring(0, 8) + "01")
        preset("Год", today.substring(0, 4) + "-01-01")
        controls.addView(Button(this).apply {
            text = "Даты"
            setOnClickListener {
                showDatePicker(from) { pickedFrom ->
                    showDatePicker(to) { pickedTo ->
                        if (pickedFrom > pickedTo) toast("Начало не может быть позже окончания")
                        else { from = pickedFrom; to = pickedTo; refresh() }
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(controls)
        content.addView(rangeLabel)
        content.addView(summary)
        content.addView(daily)
        refresh()
        val scroll = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this).setTitle("Общая статистика").setView(scroll)
            .setPositiveButton("Закрыть", null).show()
    }

    private fun showCharts() {
        val data = allEntries()
        if (data.isEmpty()) { toast("Пока нет данных для графиков"); return }
        val chart = ProductionChartView(
            context = this,
            data = data,
            cleanRate = cleanRate,
            scrapRate = scrapRate,
            formatDate = ::formatDate,
            calendarDaysBetween = ::calendarDaysBetween,
            onEntrySelected = { entry ->
                AlertDialog.Builder(this)
                    .setTitle(formatDate(entry.date))
                    .setMessage(
                        "Заработок: " + money(entry.clean * cleanRate + entry.scrap * scrapRate) +
                            " сом\\nЧистая продукция: " + qty(entry.clean) +
                            " кг\\nБрак: " + qty(entry.scrap) + " кг"
                    )
                    .setPositiveButton("ОК", null)
                    .show()
            }
        )
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(4)) }
        box.addView(label("Показатели по дням", 15f, 0xFF17243A.toInt(), true))
        box.addView(label("Легенда графика", 12f, 0xFF68758A.toInt(), true))
        val legend = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(8)) }
        fun legendRow(color: Int, caption: String) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(View(this@ProControlActivity).apply { setBackgroundColor(color) },
                LinearLayout.LayoutParams(dp(12), dp(12)).apply { rightMargin = dp(8) })
            row.addView(label(caption, 13f, 0xFF34445B.toInt()))
            legend.addView(row)
        }
        legendRow(Color.rgb(37, 99, 235), "Заработок, сом")
        legendRow(Color.rgb(22, 163, 74), "Чистая продукция, кг")
        legendRow(Color.rgb(220, 38, 38), "Брак, кг")
        box.addView(legend)
        box.addView(label("Увеличение двумя пальцами, перемещение одним пальцем. Нажми на точку, чтобы увидеть значения.", 11f, 0xFF68758A.toInt()))
        box.addView(chart, LinearLayout.LayoutParams(-1, dp(340)))
        box.addView(Button(this).apply { text = "Сбросить масштаб"; setOnClickListener { chart.reset() } })
        AlertDialog.Builder(this).setTitle("Графики за всё время").setView(box).setPositiveButton("Закрыть", null).show()
    }

    private fun showArchive() {
        val archive = try {
            JSONArray(prefs.getString("archive", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val history = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
        if (history.length() > 0 || archiveEditing) {
            val historicalCard = card()
            historicalCard.addView(label("Исторические смены", 15f, 0xFF17243A.toInt(), true))
            historicalCard.addView(label("Записи за даты до начала работы с программой. Они не входят в текущий расчётный период.", 12f, 0xFF68758A.toInt()))
            for (j in 0 until history.length()) {
                val e = history.optJSONObject(j) ?: continue
                val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                line.addView(label(formatDate(e.optString("date")) + " · " + qty(e.optDouble("clean")) + " кг · брак " + qty(e.optDouble("scrap")) + " кг", 12f, 0xFF34445B.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
                if (archiveEditing) line.addView(Button(this).apply {
                    text = "⋮"
                    setOnClickListener {
                        AlertDialog.Builder(this@ProControlActivity).setItems(arrayOf("Изменить запись", "Удалить запись")) { _, choice ->
                            if (choice == 0) editHistoricalRecord(j)
                            else AlertDialog.Builder(this@ProControlActivity).setMessage("Удалить историческую смену?")
                                .setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ ->
                                    val updated = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
                                    updated.remove(j)
                                    prefs.edit().putString("historical_entries", updated.toString()).apply()
                                    showArchive()
                                }.show()
                        }.show()
                    }
                })
                historicalCard.addView(line)
            }
            if (archiveEditing) historicalCard.addView(Button(this).apply {
                text = "Добавить историческую смену"
                setOnClickListener { addHistoricalRecord() }
            })
            box.addView(historicalCard)
        }
        if (archive.length() == 0) {
            if (history.length() == 0) box.addView(label("Архив пока пуст. Закрой расчётный период, и его результат появится здесь.", 14f, 0xFF68758A.toInt()))
        } else {
            selectedArchiveIndex = if (selectedArchiveIndex < 0) archive.length() - 1 else selectedArchiveIndex.coerceIn(0, archive.length() - 1)
            val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            navigation.addView(Button(this).apply {
                text = "‹ Старее"
                isEnabled = selectedArchiveIndex > 0
                setOnClickListener { selectedArchiveIndex--; showArchive() }
            })
            navigation.addView(label("Период " + (selectedArchiveIndex + 1) + " из " + archive.length(), 14f, 0xFF17243A.toInt(), true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
            navigation.addView(Button(this).apply {
                text = "Новее ›"
                isEnabled = selectedArchiveIndex < archive.length() - 1
                setOnClickListener { selectedArchiveIndex++; showArchive() }
            })
            box.addView(navigation)
            for (i in selectedArchiveIndex..selectedArchiveIndex) {
                val item = archive.getJSONObject(i)
                val start = item.optString("start")
                val end = item.optString("end")
                val days = calendarDaysBetween(start, end)
                val clean = item.optDouble("clean")
                val scrap = item.optDouble("scrap")
                val pay = clean * cleanRate + scrap * scrapRate
                val average = if (days > 0) pay / days else 0.0
                val record = card()
                val periodNumber = item.optInt("number", i + 1)
                record.addView(label(item.optString("name", "Период $periodNumber") + if (item.optBoolean("open", false)) " · ОТКРЫТ" else "", 17f, 0xFF17243A.toInt(), true))
                record.addView(label(formatDate(start) + " - " + formatDate(end), 13f, 0xFF68758A.toInt()))
                record.addView(label("Прошло дней: " + days, 13f, 0xFF68758A.toInt()))
                record.addView(label("Заработано: " + money(pay) + " сом", 19f, 0xFF087B58.toInt(), true))
                record.addView(label("В среднем: " + money(average) + " сом/день", 15f, 0xFF1557A6.toInt(), true))
                record.addView(label("Чистая продукция: " + qty(clean) + " кг · Брак: " + qty(scrap) + " кг", 12f, 0xFF68758A.toInt()))
                val saved = item.optJSONArray("records") ?: JSONArray()
                record.addView(label("Смен внесено: " + saved.length(), 12f, 0xFF68758A.toInt()))
                if (saved.length() > 0) {
                    for (j in 0 until saved.length()) {
                        val e = saved.optJSONObject(j) ?: continue
                        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                        line.addView(label(formatDate(e.optString("date")) + " · " + qty(e.optDouble("clean")) + " кг · брак " + qty(e.optDouble("scrap")) + " кг", 12f, 0xFF34445B.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
                        if (archiveEditing || item.optBoolean("open", false)) line.addView(Button(this).apply {
                            text = "⋮"
                            setOnClickListener {
                                AlertDialog.Builder(this@ProControlActivity)
                                    .setItems(arrayOf("Изменить запись", "Удалить запись")) { _, choice ->
                                        if (choice == 0) editArchivedRecord(i, j)
                                        else AlertDialog.Builder(this@ProControlActivity).setMessage("Удалить эту запись?")
                                            .setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ ->
                                                saved.remove(j)
                                                item.put("records", saved)
                                                var csum = 0.0; var ssum = 0.0
                                                for (k in 0 until saved.length()) {
                                                    csum += saved.getJSONObject(k).optDouble("clean")
                                                    ssum += saved.getJSONObject(k).optDouble("scrap")
                                                }
                                                item.put("clean", csum).put("scrap", ssum)
                                                archive.put(i, item)
                                                prefs.edit().putString("archive", archive.toString()).apply()
                                                showArchive()
                                            }.show()
                                    }.show()
                            }
                        })
                        record.addView(line)
                    }
                }
                if (archiveEditing || item.optBoolean("open", false)) record.addView(Button(this).apply {
                    text = "Добавить смену в этот период"
                    setOnClickListener { addArchivedRecord(i) }
                })
                if (item.optBoolean("open", false)) record.addView(Button(this).apply {
                    text = "Закрыть этот период"
                    setOnClickListener { closeSeparatePeriod(i) }
                })
                box.addView(record)
            }
        }
        val scroll = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle("История расчётных периодов").setView(scroll)
            .setPositiveButton("Закрыть", null).show()
    }

    private fun createOpenArchivePeriod() {
        var start = dateFormat.format(Date())
        var end = start
        val startButton = Button(this).apply { text = "Начало: " + formatDate(start) }
        val endButton = Button(this).apply { text = "Окончание: " + formatDate(end) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(fieldCaption("Период с")); addView(startButton, matchWrap())
            addView(fieldCaption("Период по")); addView(endButton, matchWrap())
        }
        startButton.setOnClickListener { showDatePicker(start) { start = it; startButton.text = "Начало: " + formatDate(it) } }
        endButton.setOnClickListener { showDatePicker(end) { end = it; endButton.text = "Окончание: " + formatDate(it) } }
        AlertDialog.Builder(this).setTitle("Открыть отдельный период").setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Открыть") { _, _ ->
                if (!validDate(start) || !validDate(end) || start > end) {
                    toast("Проверь даты начала и окончания")
                    return@setPositiveButton
                }
                val today = dateFormat.format(Date())
                if (end > today) {
                    toast("Открытый период можно задать только до сегодняшней даты")
                    return@setPositiveButton
                }
                if (start <= today && end >= periodStart) {
                    toast("Этот диапазон пересекается с текущим периодом. Пропущенные смены текущего периода внеси прямо в него.")
                    return@setPositiveButton
                }
                val archive = JSONArray(prefs.getString("archive", "[]") ?: "[]")
                for (i in 0 until archive.length()) {
                    val existing = archive.optJSONObject(i) ?: continue
                    if (start <= existing.optString("end") && end >= existing.optString("start")) {
                        toast("Этот диапазон пересекается с " + existing.optString("name", "другим периодом"))
                        return@setPositiveButton
                    }
                }
                val number = nextPeriodNumber(archive)
                archive.put(JSONObject().put("number", number).put("name", "Период $number")
                    .put("start", start).put("end", end).put("clean", 0.0).put("scrap", 0.0)
                    .put("records", JSONArray()).put("open", true))
                prefs.edit().putString("archive", archive.toString()).apply()
                selectedArchiveIndex = archive.length() - 1
                showArchive()
            }.show()
    }

    private fun closeSeparatePeriod(index: Int) {
        val archive = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        val period = archive.optJSONObject(index) ?: return
        if (!period.optBoolean("open", false)) return
        AlertDialog.Builder(this).setTitle("Закрыть " + period.optString("name"))
            .setMessage("Период будет закрыт и останется в архиве. Записи сохранятся.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Закрыть") { _, _ ->
                period.put("open", false)
                archive.put(index, period)
                prefs.edit().putString("archive", archive.toString()).apply()
                showArchive()
            }.show()
    }

    private fun addHistoricalRecord() {
        val date = EditText(this).apply { hint = "ГГГГ-ММ-ДД" }
        val clean = numberInput("Чистая продукция, кг").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val scrap = numberInput("Брак, кг").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val note = EditText(this).apply { hint = "Комментарий (необязательно)" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0)
            addView(fieldCaption("Дата")); addView(date); addView(fieldCaption("Чистая, кг")); addView(clean)
            addView(fieldCaption("Брак, кг")); addView(scrap); addView(fieldCaption("Комментарий")); addView(note) }
        AlertDialog.Builder(this).setTitle("Добавить историческую смену").setView(box)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить") { _, _ ->
                val d = date.text.toString().trim()
                val c = clean.text.toString().toLongOrNull()
                val b = scrap.text.toString().toLongOrNull()
                if (!validDate(d) || c == null || b == null || c < 0 || b < 0) { toast("Проверь дату и значения в кг"); return@setPositiveButton }
                if (allKnownDates().contains(d)) { toast("За эту дату запись уже существует"); return@setPositiveButton }
                val h = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
                h.put(JSONObject().put("date", d).put("clean", c / 1000.0).put("scrap", b / 1000.0).put("note", note.text.toString().trim()))
                prefs.edit().putString("historical_entries", h.toString()).apply()
                showArchive()
            }.show()
    }

    private fun editHistoricalRecord(index: Int) {
        val h = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
        val old = h.optJSONObject(index) ?: return
        val date = EditText(this).apply { setText(old.optString("date")) }
        val clean = numberInput("Чистая продукция, кг").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }.apply { setText((old.optDouble("clean") * 1000).toLong().toString()) }
        val scrap = numberInput("Брак, кг").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }.apply { setText((old.optDouble("scrap") * 1000).toLong().toString()) }
        val note = EditText(this).apply { setText(old.optString("note")) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0)
            addView(fieldCaption("Дата")); addView(date); addView(fieldCaption("Чистая, кг")); addView(clean)
            addView(fieldCaption("Брак, кг")); addView(scrap); addView(fieldCaption("Комментарий")); addView(note) }
        AlertDialog.Builder(this).setTitle("Изменить историческую смену").setView(box)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить") { _, _ ->
                val d = date.text.toString().trim()
                val c = clean.text.toString().toLongOrNull()
                val b = scrap.text.toString().toLongOrNull()
                if (!validDate(d) || c == null || b == null || c < 0 || b < 0) { toast("Проверь дату и значения в кг"); return@setPositiveButton }
                if (allStoredDatesExcept(old.optString("date")).contains(d)) { toast("За эту дату уже есть запись"); return@setPositiveButton }
                h.put(index, JSONObject().put("date", d).put("clean", c / 1000.0).put("scrap", b / 1000.0).put("note", note.text.toString().trim()))
                prefs.edit().putString("historical_entries", h.toString()).apply()
                showArchive()
            }.show()
    }

    private fun editArchivedRecord(periodIndex: Int, recordIndex: Int) {
        val a = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        val period = a.optJSONObject(periodIndex) ?: return
        val records = period.optJSONArray("records") ?: JSONArray()
        val old = records.optJSONObject(recordIndex) ?: return
        val date = EditText(this).apply { setText(old.optString("date")) }
        val clean = numberInput("Чистая продукция, кг").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }.apply { setText((old.optDouble("clean") * 1000).toLong().toString()) }
        val scrap = numberInput("Брак, кг").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }.apply { setText((old.optDouble("scrap") * 1000).toLong().toString()) }
        val note = EditText(this).apply { setText(old.optString("note")); hint = "Комментарий" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0)
            addView(fieldCaption("Дата ГГГГ-ММ-ДД")); addView(date); addView(fieldCaption("Чистая, кг")); addView(clean)
            addView(fieldCaption("Брак, кг")); addView(scrap); addView(fieldCaption("Комментарий")); addView(note) }
        AlertDialog.Builder(this).setTitle("Изменить запись архива").setView(box)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить") { _, _ ->
                val d = date.text.toString().trim()
                val cv = clean.text.toString().toLongOrNull()
                val sv = scrap.text.toString().toLongOrNull()
                if (!validDate(d) || cv == null || sv == null || cv < 0 || sv < 0) { toast("Проверь дату и значения в кг"); return@setPositiveButton }
                if (d < period.optString("start") || d > period.optString("end") || d > dateFormat.format(Date())) {
                    toast("Дата должна оставаться внутри этого периода и не позже сегодняшнего дня"); return@setPositiveButton
                }
                if (allStoredDatesExcept(old.optString("date")).contains(d)) { toast("За эту дату уже есть запись"); return@setPositiveButton }
                records.put(recordIndex, JSONObject().put("date", d).put("clean", cv / 1000.0).put("scrap", sv / 1000.0).put("note", note.text.toString().trim()))
                period.put("records", records)
                period.put("clean", sumRecords(records, "clean")).put("scrap", sumRecords(records, "scrap"))
                a.put(periodIndex, period)
                prefs.edit().putString("archive", a.toString()).apply()
                showArchive()
            }.show()
    }

    private fun addArchivedRecord(periodIndex: Int) {
        val a = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        val period = a.optJSONObject(periodIndex) ?: return
        var selectedDate = period.optString("start")
        val date = Button(this).apply { text = formatDate(selectedDate) }
        date.setOnClickListener { showDatePicker(selectedDate) { selectedDate = it; date.text = formatDate(it) } }
        val clean = numberInput("Чистая продукция, кг").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val scrap = numberInput("Брак, кг").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val note = EditText(this).apply { hint = "Комментарий" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0)
            addView(fieldCaption("Дата смены")); addView(date); addView(clean); addView(scrap); addView(note) }
        AlertDialog.Builder(this).setTitle("Добавить смену в " + period.optString("name", "период")).setView(box)
            .setNegativeButton("Отмена", null).setPositiveButton("Добавить") { _, _ ->
                val d = selectedDate
                val cv = clean.text.toString().toLongOrNull()
                val sv = scrap.text.toString().toLongOrNull()
                if (!validDate(d) || d < period.optString("start") || d > period.optString("end") ||
                    d > dateFormat.format(Date()) || cv == null || sv == null || cv < 0 || sv < 0) {
                    toast("Дата должна быть внутри периода и не позже сегодняшнего дня. Проверь значения в кг"); return@setPositiveButton
                }
                if (allStoredDatesExcept("").contains(d)) { toast("За эту дату уже есть запись"); return@setPositiveButton }
                val records = period.optJSONArray("records") ?: JSONArray()
                records.put(JSONObject().put("date", d).put("clean", cv / 1000.0).put("scrap", sv / 1000.0).put("note", note.text.toString().trim()))
                period.put("records", records).put("clean", sumRecords(records, "clean")).put("scrap", sumRecords(records, "scrap"))
                a.put(periodIndex, period)
                prefs.edit().putString("archive", a.toString()).apply()
                showArchive()
            }.show()
    }

    private fun sumRecords(a: JSONArray, key: String): Double {
        var total = 0.0
        for (i in 0 until a.length()) total += a.optJSONObject(i)?.optDouble(key, 0.0) ?: 0.0
        return total
    }

    private fun allStoredDatesExcept(except: String): Set<String> {
        val dates = entries.map { it.date }.filter { it != except }.toMutableSet()
        val history = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
        for (i in 0 until history.length()) {
            val d = history.optJSONObject(i)?.optString("date") ?: ""
            if (d != except) dates.add(d)
        }
        val a = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        for (i in 0 until a.length()) {
            val records = a.optJSONObject(i)?.optJSONArray("records") ?: continue
            for (j in 0 until records.length()) {
                val d = records.optJSONObject(j)?.optString("date") ?: ""
                if (d != except) dates.add(d)
            }
        }
        return dates
    }

    private fun calendarDaysBetween(start: String, end: String): Int {
        return try {
            val cal = java.util.Calendar.getInstance()
            cal.time = dateFormat.parse(start) ?: return 0
            val finish = java.util.Calendar.getInstance()
            finish.time = dateFormat.parse(end) ?: return 0
            var count = 0
            while (!cal.after(finish) && count < 50000) {
                count++
                cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
            count
        } catch (_: Exception) { 0 }
    }

    private fun exportTransferCode() {
        val root = JSONObject().put("v", 1).put("currentStart", periodStart)
        val current = JSONArray()
        entries.sortedBy { it.date }.forEach { e ->
            current.put(JSONObject().put("d", e.date).put("c", e.clean).put("s", e.scrap).put("n", e.note))
        }
        root.put("current", current)
        val periods = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        val safePeriods = JSONArray()
        for (i in 0 until periods.length()) {
            val p = periods.optJSONObject(i) ?: continue
            val records = p.optJSONArray("records") ?: JSONArray()
            val safeRecords = JSONArray()
            for (j in 0 until records.length()) {
                val e = records.optJSONObject(j) ?: continue
                safeRecords.put(JSONObject().put("d", e.optString("date")).put("c", e.optDouble("clean"))
                    .put("s", e.optDouble("scrap")).put("n", e.optString("note")))
            }
            safePeriods.put(JSONObject().put("a", p.optString("start")).put("b", p.optString("end"))
                .put("n", p.optInt("number", i + 1)).put("m", p.optString("name", "Период " + (i + 1)))
                .put("o", p.optBoolean("open", false)).put("r", safeRecords))
        }
        root.put("periods", safePeriods)
        val safeHistory = JSONArray()
        val historical = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
        for (i in 0 until historical.length()) {
            val e = historical.optJSONObject(i) ?: continue
            safeHistory.put(JSONObject().put("d", e.optString("date")).put("c", e.optDouble("clean"))
                .put("s", e.optDouble("scrap")).put("n", e.optString("note")))
        }
        root.put("history", safeHistory)
        try {
            val raw = root.toString().toByteArray(Charsets.UTF_8)
            val zipped = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(raw) } }.toByteArray()
            val crc = CRC32().apply { update(raw) }.value.toString(16)
            val code = "PC1:$crc:" + Base64.encodeToString(zipped, Base64.NO_WRAP or Base64.URL_SAFE)
            val field = EditText(this).apply { setText(code); minLines = 4; setTextIsSelectable(true) }
            AlertDialog.Builder(this).setTitle("Код переноса")
                .setMessage("Скопируй весь текст и отправь на другой телефон. Тарифы и заработок не передаются.")
                .setView(field).setPositiveButton("Копировать") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ProControl", code))
                    toast("Код скопирован")
                }.setNegativeButton("Закрыть", null).show()
        } catch (_: Exception) { toast("Не удалось создать код") }
    }

    private fun importTransferCode() {
        val field = EditText(this).apply { hint = "Вставь код PC1:..."; minLines = 4 }
        AlertDialog.Builder(this).setTitle("Импорт данных").setView(field)
            .setNegativeButton("Отмена", null).setPositiveButton("Проверить") { _, _ ->
                try {
                    val parts = field.text.toString().trim().split(':', limit = 3)
                    if (parts.size != 3 || parts[0] != "PC1") throw Exception("Неверный формат кода")
                    val zipped = Base64.decode(parts[2], Base64.NO_WRAP or Base64.URL_SAFE)
                    val raw = InflaterInputStream(ByteArrayInputStream(zipped)).use { it.readBytes() }
                    val crc = CRC32().apply { update(raw) }.value.toString(16)
                    if (!crc.equals(parts[1], true)) throw Exception("Контрольная сумма не совпала")
                    val root = JSONObject(String(raw, Charsets.UTF_8))
                    if (root.optInt("v") != 1) throw Exception("Версия кода не поддерживается")
                    val newCurrent = mutableListOf<ProductionEntry>()
                    val ca = root.optJSONArray("current") ?: JSONArray()
                    for (i in 0 until ca.length()) {
                        val e = ca.getJSONObject(i); newCurrent.add(ProductionEntry(e.getString("d"), e.optDouble("c"), e.optDouble("s"), e.optString("n")))
                    }
                    val newPeriods = root.optJSONArray("periods") ?: JSONArray()
                    val newHistory = root.optJSONArray("history") ?: JSONArray()
                    var count = 0
                    val known = allKnownDates()
                    newCurrent.forEach { if (!known.contains(it.date)) count++ }
                    for (i in 0 until newPeriods.length()) {
                        val p = newPeriods.getJSONObject(i); val rs = p.optJSONArray("r") ?: JSONArray()
                        for (j in 0 until rs.length()) if (!known.contains(rs.getJSONObject(j).optString("d"))) count++
                    }
                    for (i in 0 until newHistory.length()) if (!known.contains(newHistory.getJSONObject(i).optString("d"))) count++
                    AlertDialog.Builder(this).setTitle("Подтвердить импорт")
                        .setMessage("Новых записей: $count. Существующие даты не будут перезаписаны.")
                        .setNegativeButton("Отмена", null).setPositiveButton("Импортировать") { _, _ ->
                            val archive = JSONArray(prefs.getString("archive", "[]") ?: "[]")
                            val freshPhone = entries.isEmpty() && archive.length() == 0
                            newCurrent.forEach { if (known.add(it.date)) entries.add(it) }
                            if (freshPhone && validDate(root.optString("currentStart"))) periodStart = root.optString("currentStart")
                            for (i in 0 until newPeriods.length()) {
                                val p = newPeriods.getJSONObject(i)
                                val start = p.getString("a"); val end = p.getString("b")
                                var target: JSONObject? = null
                                for (j in 0 until archive.length()) {
                                    val existing = archive.optJSONObject(j)
                                    if (existing?.optString("start") == start && existing.optString("end") == end) target = existing
                                }
                                if (target == null) {
                                    val newNumber = nextPeriodNumber(archive)
                                    target = JSONObject().put("number", newNumber).put("name", "Период $newNumber")
                                        .put("start", start).put("end", end).put("records", JSONArray())
                                    archive.put(target)
                                }
                                val records = target.optJSONArray("records") ?: JSONArray()
                                val rs = p.optJSONArray("r") ?: JSONArray()
                                for (j in 0 until rs.length()) {
                                    val e = rs.getJSONObject(j); val d = e.getString("d")
                                    if (known.add(d)) records.put(JSONObject().put("date", d).put("clean", e.optDouble("c"))
                                        .put("scrap", e.optDouble("s")).put("note", e.optString("n")))
                                }
                                target.put("records", records)
                                if (p.optBoolean("o", false)) target.put("open", true)
                                var cleanSum = 0.0; var scrapSum = 0.0
                                for (j in 0 until records.length()) { cleanSum += records.getJSONObject(j).optDouble("clean"); scrapSum += records.getJSONObject(j).optDouble("scrap") }
                                target.put("clean", cleanSum).put("scrap", scrapSum)
                            }
                            val history = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
                            for (i in 0 until newHistory.length()) {
                                val e = newHistory.getJSONObject(i); val d = e.getString("d")
                                if (known.add(d)) history.put(JSONObject().put("date", d).put("clean", e.optDouble("c"))
                                    .put("scrap", e.optDouble("s")).put("note", e.optString("n")))
                            }
                            prefs.edit().putString("archive", archive.toString())
                                .putString("historical_entries", history.toString()).apply()
                            entries.sortByDescending { it.date }; repository.saveEntries(entries)
                            toast("Импорт завершён"); render()
                        }.show()
                } catch (e: Exception) { toast("Ошибка импорта: ${e.message}") }
            }.show()
    }

    private fun allKnownDates(): MutableSet<String> {
        val dates = entries.map { it.date }.toMutableSet()
        val history = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
        for (i in 0 until history.length()) dates.add(history.optJSONObject(i)?.optString("date") ?: "")
        val a = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        for (i in 0 until a.length()) {
            val records = a.optJSONObject(i)?.optJSONArray("records") ?: continue
            for (j in 0 until records.length()) dates.add(records.optJSONObject(j)?.optString("date") ?: "")
        }
        return dates
    }

    private fun showRatesDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val clean = numberInput("Сом за тонну").apply { setText(cleanRate.toString()) }
        val scrap = numberInput("Сом за тонну").apply { setText(scrapRate.toString()) }
        val archiveSwitch = Switch(this).apply {
            text = "Разрешить редактирование закрытых периодов"
            isChecked = archiveEditing
        }
        box.addView(fieldCaption("Тариф чистой продукции, сом/т")); box.addView(clean, matchWrap())
        box.addView(fieldCaption("Тариф брака, сом/т")); box.addView(scrap, matchWrap())
        box.addView(archiveSwitch)
        box.addView(Button(this).apply { text = "Скопировать код переноса"; setOnClickListener { exportTransferCode() } })
        box.addView(Button(this).apply { text = "Вставить код переноса"; setOnClickListener { importTransferCode() } })
        AlertDialog.Builder(this).setTitle("Настройки").setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val c = clean.text.toString().replace(',', '.').toDoubleOrNull()
                val s = scrap.text.toString().replace(',', '.').toDoubleOrNull()
                if (c == null || s == null || c < 0 || s < 0) toast("Некорректный тариф")
                else {
                    cleanRate = c; scrapRate = s
                    archiveEditing = archiveSwitch.isChecked
                    prefs.edit().putString("clean_rate", c.toString()).putString("scrap_rate", s.toString())
                        .putBoolean("archive_editing", archiveEditing).apply()
                    render()
                }
            }.show()
    }

    private fun nextCalendarDate(value: String): String {
        val calendar = java.util.Calendar.getInstance()
        calendar.time = dateFormat.parse(value) ?: Date()
        calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return dateFormat.format(calendar.time)
    }

    private fun calendarDays(): Int = calendarDaysBetween(periodStart, dateFormat.format(Date()))

    private fun showDatePicker(initial: String, onPicked: (String) -> Unit) {
        val cal = java.util.Calendar.getInstance()
        try { cal.time = dateFormat.parse(initial) ?: Date() } catch (_: Exception) {}
        DatePickerDialog(this, { _, year, month, day ->
            val picked = java.util.Calendar.getInstance().apply { set(year, month, day) }
            onPicked(dateFormat.format(picked.time))
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }

    private fun nextPeriodNumber(archive: JSONArray): Int {
        var max = 0
        for (i in 0 until archive.length()) max = maxOf(max, archive.optJSONObject(i)?.optInt("number", i + 1) ?: 0)
        return max + 1
    }
    private fun validDate(value: String): Boolean {
        return try {
            if (!value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) false
            else {
                val strict = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
                val parsed = strict.parse(value)
                parsed != null && strict.format(parsed) == value
            }
        } catch (_: Exception) { false }
    }
    private fun formatDate(value: String): String {
        val parsedDate = try {
            dateFormat.parse(value)
        } catch (_: Exception) {
            null
        } ?: return value

        return displayDate.format(parsedDate)
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(0xFFFFFFFF.toInt())
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setPadding(0, dp(3), 0, dp(3))
    }
    private fun fieldCaption(value: String) = label(value, 13f, 0xFF536176.toInt(), true)
    private fun numberInput(hintValue: String) = EditText(this).apply {
        hint = hintValue; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine(); textSize = 16f
    }
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun qty(value: Double) = String.format(Locale("ru"), "%,.0f", value * 1000.0)
    private fun money(value: Double) = String.format(Locale("ru"), "%.2f", value)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
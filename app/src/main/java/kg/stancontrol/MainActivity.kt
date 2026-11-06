package kg.stancontrol

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import kotlin.math.round


class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("stancontrol", MODE_PRIVATE) }
    private val dayChangeHandler = Handler(Looper.getMainLooper())
    private val dayChangeCheck = object : Runnable {
        override fun run() {
            if (archiveStaleActiveShift()) {
                recreate()
                return
            }
            dayChangeHandler.postDelayed(this, 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        archiveStaleActiveShift()
        setContent { MaterialTheme { MainScreen() } }
    }

    override fun onResume() {
        super.onResume()
        dayChangeHandler.removeCallbacks(dayChangeCheck)
        if (archiveStaleActiveShift()) {
            recreate()
            return
        }
        dayChangeHandler.postDelayed(dayChangeCheck, 60_000L)
    }

    override fun onPause() {
        dayChangeHandler.removeCallbacks(dayChangeCheck)
        super.onPause()
    }

    @Composable
    private fun MainScreen() {
        val restoreBackupLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                try {
                    val raw = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("Не удалось прочитать файл")
                    val root = JSONObject(raw)
                    if (root.optString("app") != "StanControl" || root.optInt("backupVersion") != 1) {
                        throw IllegalArgumentException("Это не резервная копия StanControl или версия не поддерживается")
                    }
                    val data = root.optJSONObject("data") ?: throw IllegalArgumentException("В файле нет данных")

                    // Finish a stale local shift before merging the backup.
                    archiveStaleActiveShift()
                    val todayDate = today()
                    val keepTodayShift = prefs.getBoolean("active", false) &&
                        (prefs.getString("date", "") ?: "") == todayDate
                    val todayProfile = prefs.getString("profile", "Уголок 50") ?: "Уголок 50"
                    val todayPacks = prefs.getString("packs", "[]") ?: "[]"
                    val todayDefects = prefs.getString("defects", "[]") ?: "[]"
                    val todayPallets = prefs.getString("pallets", "[]") ?: "[]"

                    val currentArchive = try { JSONArray(prefs.getString("shifts_archive", "[]")) } catch (_: Exception) { JSONArray() }
                    val currentProfileNames = try { JSONArray(prefs.getString("profile_list", "[]")) } catch (_: Exception) { JSONArray() }
                    val currentProfileSettings = mutableMapOf<String, Triple<String?, String?, String?>>()
                    for (i in 0 until currentProfileNames.length()) {
                        val name = currentProfileNames.optString(i)
                        val key = name.substringAfterLast(" ")
                        currentProfileSettings[name] = Triple(
                            prefs.getString("profile_${key}_width", null),
                            prefs.getString("profile_${key}_rows", null),
                            prefs.getString("profile_${key}_length", null)
                        )
                    }
                    val currentSelectedProfile = prefs.getString("profile", "") ?: ""

                    writeAutomaticBackup()
                    val editor = prefs.edit().clear()
                    val keys = data.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        when (val value = data.get(key)) {
                            is String -> editor.putString(key, value)
                            is Boolean -> editor.putBoolean(key, value)
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Double -> editor.putFloat(key, value.toFloat())
                            is Float -> editor.putFloat(key, value)
                        }
                    }

                    // Merge archive entries by date. Local entries win on conflicts.
                    val restoredArchive = try { JSONArray(data.optString("shifts_archive", "[]")) } catch (_: Exception) { JSONArray() }
                    val mergedArchive = JSONArray()
                    val seenDates = mutableSetOf<String>()
                    fun appendArchive(source: JSONArray, skipToday: Boolean = false) {
                        for (i in 0 until source.length()) {
                            val item = source.optJSONObject(i) ?: continue
                            val itemDate = item.optString("date", "")
                            if (itemDate.isNotBlank() && !(skipToday && itemDate == todayDate) && seenDates.add(itemDate)) {
                                mergedArchive.put(item)
                            }
                        }
                    }
                    appendArchive(currentArchive)
                    appendArchive(restoredArchive, skipToday = keepTodayShift)

                    // An active shift from an older backup is an archived day, never today's active shift.
                    val backupActive = data.optBoolean("active", false)
                    val backupDate = data.optString("date", "")
                    val backupActiveToday = backupActive && backupDate == todayDate
                    if (backupActive && backupDate.isNotBlank() && backupDate != todayDate && !seenDates.contains(backupDate)) {
                        val oldPacks = try { JSONArray(data.optString("packs", "[]")) } catch (_: Exception) { JSONArray() }
                        val oldDefects = try { JSONArray(data.optString("defects", "[]")) } catch (_: Exception) { JSONArray() }
                        val oldPallets = try { JSONArray(data.optString("pallets", "[]")) } catch (_: Exception) { JSONArray() }
                        val oldPackWeight = (0 until oldPacks.length()).sumOf { oldPacks.optJSONObject(it)?.optDouble("w", 0.0) ?: 0.0 }
                        val oldDefectWeight = (0 until oldDefects.length()).sumOf { oldDefects.optJSONObject(it)?.optDouble("w", 0.0) ?: 0.0 }
                        val idle = oldPacks.length() == 0 && oldDefects.length() == 0 && oldPallets.length() == 0
                        mergedArchive.put(JSONObject()
                            .put("date", backupDate)
                            .put("profile", data.optString("profile", "Уголок 50"))
                            .put("packTotal", oldPackWeight)
                            .put("defectTotal", oldDefectWeight)
                            .put("packs", oldPacks)
                            .put("defects", oldDefects)
                            .put("pallets", oldPallets)
                            .put("status", if (idle) "Простой" else "Завершена")
                            .put("autoClosed", true)
                            .put("closedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())))
                        seenDates.add(backupDate)
                    }
                    editor.putString("shifts_archive", mergedArchive.toString())
                        .putString("archive", "Сохранено смен: " + mergedArchive.length())

                    // Merge profiles instead of replacing local configuration with an older backup.
                    val restoredProfileNames = try { JSONArray(data.optString("profile_list", "[]")) } catch (_: Exception) { JSONArray() }
                    val mergedProfileNames = JSONArray()
                    val seenProfileNames = mutableSetOf<String>()
                    for (source in listOf(currentProfileNames, restoredProfileNames)) {
                        for (i in 0 until source.length()) {
                            val name = source.optString(i).trim()
                            if (name.isNotBlank() && seenProfileNames.add(name)) mergedProfileNames.put(name)
                        }
                    }
                    editor.putString("profile_list", mergedProfileNames.toString())
                    currentProfileSettings.forEach { (name, values) ->
                        val key = name.substringAfterLast(" ")
                        values.first?.let { editor.putString("profile_${key}_width", it) }
                        values.second?.let { editor.putString("profile_${key}_rows", it) }
                        values.third?.let { editor.putString("profile_${key}_length", it) }
                    }
                    if (currentSelectedProfile.isNotBlank() && seenProfileNames.contains(currentSelectedProfile)) {
                        editor.putString("profile", currentSelectedProfile)
                    }

                    // A backup may resume today's shift only when there is no local
                    // today's shift and no already-archived record for the same date.
                    val todayAlreadyArchived = (0 until mergedArchive.length()).any {
                        mergedArchive.optJSONObject(it)?.optString("date") == todayDate
                    }
                    when {
                        keepTodayShift -> {
                            val key = todayProfile.substringAfterLast(" ")
                            editor.putBoolean("active", true)
                                .putString("date", todayDate)
                                .putString("profile", todayProfile)
                                .putString("packs", todayPacks)
                                .putString("defects", todayDefects)
                                .putString("pallets", todayPallets)
                            prefs.getString("profile_${key}_width", null)?.let { editor.putString("profile_${key}_width", it) }
                            prefs.getString("profile_${key}_rows", null)?.let { editor.putString("profile_${key}_rows", it) }
                            prefs.getString("profile_${key}_length", null)?.let { editor.putString("profile_${key}_length", it) }
                            editor.putInt("width", prefs.getInt("width", 5))
                        }
                        backupActiveToday && !todayAlreadyArchived -> {
                            editor.putBoolean("active", true).putString("date", todayDate)
                        }
                        else -> {
                            editor.putBoolean("active", false)
                                .putString("date", todayDate)
                                .putString("packs", "[]")
                                .putString("defects", "[]")
                                .putString("pallets", "[]")
                        }
                    }

                    editor.commit()
                    writeAutomaticBackup()
                    android.widget.Toast.makeText(this@MainActivity, "Копия восстановлена. Сегодняшняя смена сохранена, старые дни отправлены в архив.", android.widget.Toast.LENGTH_LONG).show()
                    recreate()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(this@MainActivity, "Ошибка восстановления: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        var active by remember { mutableStateOf(prefs.getBoolean("active", false)) }
        var screen by remember { mutableStateOf("home") }
        var mode by remember { mutableStateOf("Пачка") }
        var profile by remember { mutableStateOf(prefs.getString("profile", "Уголок 50") ?: "Уголок 50") }
        val profileNames = remember {
            mutableStateListOf<String>().apply {
                val saved = try { JSONArray(prefs.getString("profile_list", "[]")) } catch (_: Exception) { JSONArray() }
                for (i in 0 until saved.length()) {
                    val name = saved.optString(i).trim()
                    if (name.isNotEmpty() && !contains(name)) add(name)
                }
            }
        }
        var showProfileManager by remember { mutableStateOf(false) }
        var newProfileName by remember { mutableStateOf("") }
        var newProfileWidth by remember { mutableStateOf("5") }
        var newProfileRows by remember { mutableStateOf("15") }
        var newProfileLength by remember { mutableStateOf("6") }
        var confirmDeleteProfile by remember { mutableStateOf(false) }
        val initialProfileKey = (prefs.getString("profile", "Уголок 50") ?: "Уголок 50").substringAfterLast(" ")
        val initialWidth = when (initialProfileKey) { "40" -> "7"; "30" -> "6"; else -> "5" }
        val initialRows = if (initialProfileKey == "40") "20" else "15"
        var width by remember { mutableStateOf(prefs.getString("profile_${initialProfileKey}_width", initialWidth) ?: initialWidth) }
        var length by remember { mutableStateOf(prefs.getString("profile_${initialProfileKey}_length", "6") ?: "6") }
        var rows by remember { mutableStateOf(prefs.getString("profile_${initialProfileKey}_rows", initialRows) ?: initialRows) }
        var date by remember { mutableStateOf(if (prefs.getBoolean("active", false)) (prefs.getString("date", today()) ?: today()) else today()) }
        var inputWeight by remember { mutableStateOf("") }
        var gross by remember { mutableStateOf("") }
        var tare by remember { mutableStateOf("") }
        var defectType by remember { mutableStateOf("Резка") }
        var palletType by remember { mutableStateOf("Прокат") }
        var showSettings by remember { mutableStateOf(false) }
        var showHandoverDialog by remember { mutableStateOf(false) }
        var handoverText by remember { mutableStateOf(prefs.getString("shift_handover_text", "") ?: "") }
        var pdfTemplate by remember { mutableStateOf(prefs.getString("pdf_template", "classic") ?: "classic") }
        var showPacks by remember { mutableStateOf(true) }
        var showDefects by remember { mutableStateOf(false) }
        var showPallets by remember { mutableStateOf(false) }
        var editPack by remember { mutableStateOf(-1) }
        var editDefect by remember { mutableStateOf(-1) }
        var editPallet by remember { mutableStateOf(-1) }
        var editWeight by remember { mutableStateOf("") }
        var editRows by remember { mutableStateOf("") }
        var editWidth by remember { mutableStateOf("") }
        var editGross by remember { mutableStateOf("") }
        var editTare by remember { mutableStateOf("") }
        var editType by remember { mutableStateOf("") }
        var confirmFinish by remember { mutableStateOf(false) }
        var deletePackIndex by remember { mutableStateOf(-1) }
        var deleteDefectIndex by remember { mutableStateOf(-1) }
        var deletePalletIndex by remember { mutableStateOf(-1) }
        var deleteArchiveEntry by remember { mutableStateOf<String?>(null) }
        var archiveShiftJson by remember { mutableStateOf<String?>(null) }
        var archiveDateFilter by remember { mutableStateOf("") }
        var showPreviousYears by remember { mutableStateOf(false) }
        var archiveYear by remember { mutableStateOf(Calendar.getInstance().get(Calendar.YEAR).toString()) }
        var historicalDate by remember { mutableStateOf(today()) }
        var historicalProfile by remember { mutableStateOf("Уголок 50") }
        var historicalMode by remember { mutableStateOf("Пачка") }
        var historicalWidth by remember { mutableStateOf("5") }
        var historicalRows by remember { mutableStateOf("15") }
        var historicalLength by remember { mutableStateOf("6") }
        var historicalWeight by remember { mutableStateOf("") }
        var historicalDefectType by remember { mutableStateOf("Резка") }
        var historicalPalletType by remember { mutableStateOf("Прокат") }
        var historicalGross by remember { mutableStateOf("") }
        var historicalTare by remember { mutableStateOf("") }
        val historicalPacks = remember { mutableStateListOf<Pack>() }
        val historicalDefects = remember { mutableStateListOf<Defect>() }
        val historicalPallets = remember { mutableStateListOf<Pallet>() }

        val packs = remember { mutableStateListOf<Pack>().apply { addAll(readPacks()) } }
        val defects = remember { mutableStateListOf<Defect>().apply { addAll(readDefects()) } }
        val pallets = remember { mutableStateListOf<Pallet>().apply { addAll(readPallets()) } }
        val packTotal = packs.sumOf { it.weight }
        val defectTotal = defects.sumOf { it.weight }
        val lastPack = packs.lastOrNull()
        val rejectPercent = if (packTotal + defectTotal > 0) defectTotal * 100.0 / (packTotal + defectTotal) else 0.0

        fun archiveHasDate(targetDate: String): Boolean {
            if (targetDate.isBlank()) return false
            val saved = try { JSONArray(prefs.getString("shifts_archive", "[]")) } catch (_: Exception) { JSONArray() }
            return (0 until saved.length()).any { saved.optJSONObject(it)?.optString("date") == targetDate }
        }

        fun saveAll() {
            val p = JSONArray()
            packs.forEach { p.put(JSONObject().put("w", it.weight).put("r", it.rows).put("n", it.pieces).put("k", it.coefficient).put("width", it.width).put("length", it.length)) }
            val d = JSONArray()
            defects.forEach { d.put(JSONObject().put("t", it.type).put("w", it.weight)) }
            val a = JSONArray()
            pallets.forEach { a.put(JSONObject().put("t", it.type).put("g", it.gross).put("a", it.tare)) }
            prefs.edit().putString("packs", p.toString()).putString("defects", d.toString()).putString("pallets", a.toString())
                .putBoolean("active", active).putString("profile", profile).putInt("width", width.toIntOrNull() ?: 5)
                .putString("profile_${profile.substringAfterLast(" ")}_length", length).putString("profile_${profile.substringAfterLast(" ")}_rows", rows).putString("profile_${profile.substringAfterLast(" ")}_width", width).putString("date", date).apply()
            writeAutomaticBackup()
        }

        fun addPack() {
            val w = inputWeight.toIntOrNull()?.toDouble() ?: return
            val r = rows.toIntOrNull() ?: return
            val widthValue = width.toIntOrNull() ?: return
            val lengthValue = length.replace(',', '.').toDoubleOrNull() ?: return
            if (w <= 0 || r <= 0 || widthValue <= 0 || lengthValue <= 0) return
            val count = (widthValue * 2 - 1) * r - (widthValue - 1)
            val coefficient = round(w / (count * lengthValue) * 10.0) / 10.0
            packs.add(Pack(w, r, count, coefficient, widthValue, lengthValue))
            inputWeight = ""
            saveAll()
        }

        fun addDefect() {
            val w = inputWeight.replace(',', '.').toDoubleOrNull() ?: return
            if (w > 0) {
                defects.add(Defect(defectType, w))
                inputWeight = ""
                saveAll()
            }
        }

        fun addPallet() {
            val g = gross.replace(',', '.').toDoubleOrNull() ?: return
            val t = tare.replace(',', '.').toDoubleOrNull() ?: return
            if (g > t && t >= 0) {
                pallets.add(Pallet(palletType, g, t))
                gross = ""
                tare = ""
                saveAll()
            }
        }

        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("StanControl", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (screen != "home") TextButton(onClick = { screen = when (screen) { "settings", "profile_select" -> "home"; "work", "table" -> "home"; else -> "home" } }) { Text("Назад") }
            }
            when (screen) {
                "home" -> {
                    if (active) {
                        Text("Смена $date", style = MaterialTheme.typography.titleLarge)
                        Button(onClick = { screen = "work" }, modifier = Modifier.fillMaxWidth()) { Text("Продолжить смену") }
                        OutlinedButton(onClick = { confirmFinish = true }, modifier = Modifier.fillMaxWidth()) { Text("Завершить смену") }
                    } else {
                        Button(onClick = { screen = "profile_select" }, modifier = Modifier.fillMaxWidth()) { Text("Начать смену") }
                    }
                    OutlinedButton(onClick = { screen = "archive" }, modifier = Modifier.fillMaxWidth()) { Text("Архив смен") }
                    OutlinedButton(onClick = { screen = "settings" }, modifier = Modifier.fillMaxWidth()) { Text("Настройки") }
                    OutlinedButton(onClick = { syncArchivedShiftsToProControl(); startActivity(Intent(this@MainActivity, ProControlActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("CashControl") }
                }
                "profile_select" -> {
                    Text("Выбрать профиль", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (profileNames.isEmpty()) {
                        Text("Профили ещё не созданы. Сначала создай профиль в настройках.")
                        Button(onClick = { screen = "settings"; showProfileManager = true }, modifier = Modifier.fillMaxWidth()) { Text("Создать профиль") }
                    } else {
                        profileNames.forEach { item ->
                            OutlinedButton(onClick = {
                                profile = item
                                val key = item.substringAfterLast(" ")
                                width = prefs.getString("profile_${key}_width", "5") ?: "5"
                                rows = prefs.getString("profile_${key}_rows", "15") ?: "15"
                                length = prefs.getString("profile_${key}_length", "6") ?: "6"
                                date = today()
                                if (archiveHasDate(date)) {
                                    android.widget.Toast.makeText(this@MainActivity, "Смена за сегодня уже есть в архиве.", android.widget.Toast.LENGTH_LONG).show()
                                } else {
                                    packs.clear(); defects.clear(); pallets.clear()
                                    inputWeight = ""; gross = ""; tare = ""
                                    active = true
                                    screen = "work"
                                    saveAll()
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text(item) }
                        }
                    }
                    OutlinedButton(onClick = { screen = "home" }, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
                }
                "settings" -> {
                    Text("Настройки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Профили", fontWeight = FontWeight.SemiBold)
                    Text("Создавай профили и настраивай каждый отдельно.")
                    OutlinedButton(onClick = { showProfileManager = true }, modifier = Modifier.fillMaxWidth()) { Text("Создание и настройка профилей") }
                    Divider()
                    Text("Вариант PDF-бланка", fontWeight = FontWeight.SemiBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = pdfTemplate == "classic", onClick = { pdfTemplate = "classic"; prefs.edit().putString("pdf_template", pdfTemplate).apply() }, label = { Text("Норм PDF") })
                        FilterChip(selected = pdfTemplate == "grid", onClick = { pdfTemplate = "grid"; prefs.edit().putString("pdf_template", pdfTemplate).apply() }, label = { Text("Чмошная хуйня") })
                        FilterChip(selected = pdfTemplate == "original", onClick = { pdfTemplate = "original"; prefs.edit().putString("pdf_template", pdfTemplate).apply() }, label = { Text("Ваще хуйня") })
                    }
                    Divider()
                    Text("Смену сдает", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { handoverText = prefs.getString("shift_handover_text", "") ?: ""; showHandoverDialog = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (handoverText.isBlank()) "Задать текст" else "Изменить текст")
                    }
                    if (handoverText.isNotBlank()) Text(handoverText, style = MaterialTheme.typography.bodySmall)
                    Divider()
                    Text("Резервная копия", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = { shareBackup() }, modifier = Modifier.fillMaxWidth()) { Text("Поделиться резервной копией") }
                    OutlinedButton(onClick = { restoreBackupLauncher.launch(arrayOf("application/json", "text/json", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("Восстановить из файла бекапа") }
                    OutlinedButton(onClick = { screen = "home" }, modifier = Modifier.fillMaxWidth()) { Text("На главную") }
                }
                "work" -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Пачка", "Брак", "Поддоны").forEach { item ->
                            FilterChip(
                                selected = mode == item,
                                onClick = { mode = item },
                                modifier = Modifier.weight(1f),
                                label = { Text(item, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, softWrap = false) }
                            )
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "Пачки ${packs.size} · ${fmtWeight(packTotal)} кг   |   Брак ${defects.size} · ${fmtWeight(defectTotal)} кг   |   ${fmt(rejectPercent)}%",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (mode == "Пачка") Card(Modifier.fillMaxWidth()) {
                        val lp = lastPack
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                "ПОСЛЕДНЯЯ ПАЧКА",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            if (lp == null) {
                                Text("Пока нет записей", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            } else {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${fmtWeight(lp.weight)} кг",
                                        modifier = Modifier.weight(1.2f),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Text(
                                        "К ${fmtCoefficient(lp.coefficient)}",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Text(
                                        "Р ${lp.rows}",
                                        modifier = Modifier.weight(0.75f),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.End,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                    when (mode) {
                        "Пачка" -> {
                            OutlinedTextField(inputWeight, { inputWeight = it }, label = { Text("Вес пачки, кг") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Количество рядов")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { rows = ((rows.toIntOrNull() ?: 1) - 1).coerceAtLeast(1).toString() }) { Text("−") }
                                    Text(rows, modifier = Modifier.padding(top = 12.dp), fontWeight = FontWeight.Bold)
                                    OutlinedButton(onClick = { rows = ((rows.toIntOrNull() ?: 1) + 1).toString() }) { Text("+") }
                                }
                            }
                            Text("Ширина: $width шт. | Длина: $length м", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { addPack() }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("ДОБАВИТЬ ПАЧКУ") }
                        }
                        "Брак" -> {
                            Text("Регистрация брака", fontWeight = FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Резка", "Металлолом").forEach { item ->
                                    if (defectType == item) {
                                        Button(
                                            onClick = { defectType = item },
                                            modifier = Modifier.weight(1f).height(54.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Text(item, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { defectType = item },
                                            modifier = Modifier.weight(1f).height(54.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Text(item, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(inputWeight, { inputWeight = it }, label = { Text("Вес брака, кг") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Button(onClick = { addDefect() }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("ДОБАВИТЬ БРАК") }
                        }
                        else -> {
                            Text("Регистрация поддона", fontWeight = FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Прокат", "Резка").forEach { item ->
                                    if (palletType == item) {
                                        Button(
                                            onClick = { palletType = item },
                                            modifier = Modifier.weight(1f).height(54.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Text(item, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { palletType = item },
                                            modifier = Modifier.weight(1f).height(54.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Text(item, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(gross, { gross = it }, label = { Text("Брутто, кг") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(tare, { tare = it }, label = { Text("Тара, кг") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Text("Нетто: " + fmt(((gross.replace(',', '.').toDoubleOrNull() ?: 0.0) - (tare.replace(',', '.').toDoubleOrNull() ?: 0.0)).coerceAtLeast(0.0)) + " кг", fontWeight = FontWeight.SemiBold)
                            Button(onClick = { addPallet() }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("ДОБАВИТЬ ПОДДОН") }
                        }
                    }
                    OutlinedButton(onClick = { screen = "table" }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Таблица") }
                    OutlinedButton(onClick = { confirmFinish = true }, modifier = Modifier.fillMaxWidth()) { Text("Завершить смену") }
                    OutlinedButton(onClick = { screen = "home" }, modifier = Modifier.fillMaxWidth()) { Text("На главную") }
                }
                "table" -> {
                    Text("Таблица смены", style = MaterialTheme.typography.titleLarge)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            OutlinedButton(onClick = { showPacks = !showPacks }, modifier = Modifier.fillMaxWidth()) { Text("Пачки (" + packs.size + ")") }
                            if (showPacks) packs.forEachIndexed { index, p ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        (index + 1).toString() + ") " + fmtWeight(p.weight) + " кг | " + fmtCoefficient(p.coefficient) + " | " + p.pieces + " шт",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    TextButton(
                                        onClick = { editPack = index; editWeight = p.weight.toString(); editRows = p.rows.toString(); editWidth = p.width.toString() },
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                    ) { Text("Изменить", fontSize = 12.sp, maxLines = 1, softWrap = false) }
                                    TextButton(
                                        onClick = { deletePackIndex = index },
                                        modifier = Modifier.width(32.dp).height(34.dp),
                                        contentPadding = PaddingValues(0.dp)
                                    ) { Text("🗑", color = MaterialTheme.colorScheme.error, fontSize = 16.sp, maxLines = 1) }
                                }
                            }
                        }
                        item {
                            OutlinedButton(onClick = { showDefects = !showDefects }, modifier = Modifier.fillMaxWidth()) { Text("Брак (" + defects.size + ")") }
                            if (showDefects) defects.forEachIndexed { index, d ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(d.type + ": " + fmtWeight(d.weight) + " кг", modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                    TextButton(onClick = { editDefect = index; editWeight = d.weight.toString(); editType = d.type }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("Изменить", fontSize = 12.sp, maxLines = 1) }
                                    TextButton(onClick = { deleteDefectIndex = index }, modifier = Modifier.width(32.dp).height(34.dp), contentPadding = PaddingValues(0.dp)) { Text("🗑", color = MaterialTheme.colorScheme.error, fontSize = 16.sp, maxLines = 1) }
                                }
                            }
                            if (showDefects) Text("Итого брак: " + fmtWeight(defectTotal) + " кг", fontWeight = FontWeight.Bold)
                        }
                        item {
                            OutlinedButton(onClick = { showPallets = !showPallets }, modifier = Modifier.fillMaxWidth()) { Text("Поддоны (" + pallets.size + ")") }
                            if (showPallets) pallets.forEachIndexed { index, p ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.type + ": " + fmtWeight(p.gross) + " - " + fmtWeight(p.tare) + " = " + fmtWeight(p.net) + " кг", modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                                    TextButton(onClick = { editPallet = index; editGross = p.gross.toString(); editTare = p.tare.toString(); editType = p.type }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("Изменить", fontSize = 12.sp, maxLines = 1) }
                                    TextButton(onClick = { deletePalletIndex = index }, modifier = Modifier.width(32.dp).height(34.dp), contentPadding = PaddingValues(0.dp)) { Text("🗑", color = MaterialTheme.colorScheme.error, fontSize = 16.sp, maxLines = 1) }
                                }
                            }
                        }
                    }
                    Text("Пачки: " + fmtWeight(packTotal) + " кг    Брак: " + fmtWeight(defectTotal) + " кг")
                    OutlinedButton(onClick = { sharePdf(date, profile, packs.toList(), defects.toList(), pallets.toList()) }, modifier = Modifier.fillMaxWidth()) { Text("Поделиться PDF") }
                    OutlinedButton(onClick = { screen = "work" }, modifier = Modifier.fillMaxWidth()) { Text("Вернуться к работе") }
                }
                "archive" -> {
                    Text("Архив производства", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = {
                        historicalDate = today()
                        historicalPacks.clear(); historicalDefects.clear(); historicalPallets.clear()
                        historicalWeight = ""; historicalGross = ""; historicalTare = ""
                        screen = "archive_new"
                    }, modifier = Modifier.fillMaxWidth()) { Text("Добавить старую смену") }
                val savedShifts = JSONArray(prefs.getString("shifts_archive", "[]"))
                    val allShifts = (0 until savedShifts.length()).map { savedShifts.getJSONObject(it) }
                    val years = allShifts.mapNotNull { it.optString("date").takeLast(4).toIntOrNull() }.distinct().sortedDescending()
                    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                    val visibleYears = if (showPreviousYears) years else years.filter { it >= currentYear }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (visibleYears.ifEmpty { listOf(currentYear) }).forEach { year ->
                            FilterChip(selected = archiveYear == year.toString(), onClick = { archiveYear = year.toString(); archiveDateFilter = "" }, label = { Text(year.toString()) })
                        }
                        if (years.any { it < currentYear }) {
                            FilterChip(selected = showPreviousYears, onClick = { showPreviousYears = !showPreviousYears }, label = { Text(if (showPreviousYears) "Скрыть старые" else "Предыдущие годы") })
                        }
                    }
                    OutlinedButton(onClick = {
                        val cal = Calendar.getInstance()
                        if (archiveDateFilter.isNotBlank()) {
                            try { cal.time = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).parse(archiveDateFilter) ?: Date() } catch (_: Exception) {}
                        } else {
                            archiveYear.toIntOrNull()?.let { cal.set(Calendar.YEAR, it) }
                        }
                        DatePickerDialog(this@MainActivity, { _, year, month, day ->
                            archiveDateFilter = String.format(Locale.getDefault(), "%02d.%02d.%04d", day, month + 1, year)
                            archiveYear = year.toString()
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (archiveDateFilter.isBlank()) "Выбрать день в календаре" else "Дата: $archiveDateFilter · изменить") }
                    if (archiveDateFilter.isNotBlank()) {
                        TextButton(onClick = { archiveDateFilter = "" }) { Text("Показать весь выбранный год") }
                    }
                    val filtered = allShifts.filter { shift ->
                        val shiftDate = shift.optString("date")
                        if (archiveDateFilter.isNotBlank()) shiftDate == archiveDateFilter
                        else shiftDate.endsWith(archiveYear)
                    }.sortedByDescending { it.optString("date") }
                    Text(if (archiveDateFilter.isNotBlank()) "Смены за $archiveDateFilter" else "Смены за $archiveYear год", fontWeight = FontWeight.SemiBold)
                    if (filtered.isEmpty()) {
                        Text(if (allShifts.isEmpty()) "Архив пока пуст. Завершённые смены появятся здесь." else "За выбранный период смен нет.")
                    } else {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filtered.size) { index ->
                                val shift = filtered[index]
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(shift.optString("date") + " · " + shift.optString("profile"), fontWeight = FontWeight.Bold)
                                        if (shift.optString("status") == "Простой") {
                                            Text("Простой", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.SemiBold)
                                        }
                                        Text("Пачек: " + (shift.optJSONArray("packs")?.length() ?: 0) + " · " + fmtWeight(shift.optDouble("packTotal")) + " кг")
                                        Text("Брак: " + fmtWeight(shift.optDouble("defectTotal")) + " кг")
                                        Button(onClick = { archiveShiftJson = shift.toString(); screen = "archive_detail" }, modifier = Modifier.fillMaxWidth()) { Text("Открыть полный бланк") }
                                        TextButton(onClick = { deleteArchiveEntry = shift.toString() }) { Text("Удалить эту смену из архива") }
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { screen = "home" }, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
                }
                "archive_new" -> {
                    Text("Новый бланк за прошлую дату", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = {
                        val cal = Calendar.getInstance()
                        try { cal.time = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).parse(historicalDate) ?: Date() } catch (_: Exception) {}
                        DatePickerDialog(this@MainActivity, { _, y, m, d ->
                            historicalDate = String.format(Locale.getDefault(), "%02d.%02d.%04d", d, m + 1, y)
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Дата: $historicalDate · выбрать") }
                    Text("Профиль")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        profileNames.forEach { p ->
                            FilterChip(selected = historicalProfile == p, onClick = {
                                historicalProfile = p
                                val key = p.substringAfterLast(" ")
                                val defaultWidth = when (p) { "Уголок 50" -> "5"; "Уголок 40" -> "7"; "Уголок 30" -> "6"; else -> "5" }
                                historicalWidth = prefs.getString("profile_${key}_width", defaultWidth) ?: defaultWidth
                                historicalRows = prefs.getString("profile_${key}_rows", if (p == "Уголок 40") "20" else "15") ?: "15"
                                historicalLength = prefs.getString("profile_${key}_length", "6") ?: "6"
                            }, label = { Text(p) })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("Пачка", "Брак", "Поддоны").forEach { m ->
                            FilterChip(selected = historicalMode == m, onClick = { historicalMode = m }, label = { Text(m) })
                        }
                    }
                    when (historicalMode) {
                        "Пачка" -> {
                            OutlinedTextField(historicalWeight, { historicalWeight = it }, label = { Text("Вес пачки, кг") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(historicalRows, { historicalRows = it }, label = { Text("Количество рядов") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Text("Ширина: $historicalWidth · длина: $historicalLength м")
                            Button(onClick = {
                                val w = historicalWeight.replace(',', '.').toDoubleOrNull()
                                val r = historicalRows.toIntOrNull()
                                val wi = historicalWidth.toIntOrNull()
                                val le = historicalLength.replace(',', '.').toDoubleOrNull()
                                if (w != null && r != null && wi != null && le != null && w > 0 && r > 0 && wi > 0 && le > 0) {
                                    val n = (wi * 2 - 1) * r - (wi - 1)
                                    historicalPacks.add(Pack(w, r, n, round(w / (n * le) * 10.0) / 10.0, wi, le))
                                    historicalWeight = ""
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Добавить пачку") }
                        }
                        "Брак" -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Резка", "Металлолом").forEach { t -> FilterChip(historicalDefectType == t, { historicalDefectType = t }, label = { Text(t) }) }
                            }
                            OutlinedTextField(historicalWeight, { historicalWeight = it }, label = { Text("Вес брака, кг") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Button(onClick = {
                                val w = historicalWeight.replace(',', '.').toDoubleOrNull()
                                if (w != null && w > 0) { historicalDefects.add(Defect(historicalDefectType, w)); historicalWeight = "" }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Добавить брак") }
                        }
                        else -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Прокат", "Резка").forEach { item ->
                                    if (historicalPalletType == item) {
                                        Button(
                                            onClick = { historicalPalletType = item },
                                            modifier = Modifier.weight(1f).height(54.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Text(item, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = { historicalPalletType = item },
                                            modifier = Modifier.weight(1f).height(54.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            Text(item, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(historicalGross, { historicalGross = it }, label = { Text("Брутто, кг") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(historicalTare, { historicalTare = it }, label = { Text("Тара, кг") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Button(onClick = {
                                val g = historicalGross.replace(',', '.').toDoubleOrNull()
                                val t = historicalTare.replace(',', '.').toDoubleOrNull()
                                if (g != null && t != null && g > t && t >= 0) {
                                    historicalPallets.add(Pallet(historicalPalletType, g, t)); historicalGross = ""; historicalTare = ""
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Добавить поддон") }
                        }
                    }
                    Text("Добавлено: пачек ${historicalPacks.size} · ${fmtWeight(historicalPacks.sumOf { it.weight })} кг | брак ${fmtWeight(historicalDefects.sumOf { it.weight })} кг | поддонов ${historicalPallets.size}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        itemsIndexed(historicalPacks) { i, p -> Text("Пачка ${i + 1}: ${fmtWeight(p.weight)} кг · К ${fmtCoefficient(p.coefficient)} · ${p.pieces} шт") }
                        itemsIndexed(historicalDefects) { i, d -> Text("Брак ${i + 1}: ${d.type} · ${fmtWeight(d.weight)} кг") }
                        itemsIndexed(historicalPallets) { i, p -> Text("Поддон ${i + 1}: ${p.type} · нетто ${fmtWeight(p.net)} кг") }
                    }
                    Button(onClick = {
                        val saved = JSONArray(prefs.getString("shifts_archive", "[]"))
                        val exists = archiveHasDate(historicalDate) ||
                            (prefs.getBoolean("active", false) && (prefs.getString("date", "") ?: "") == historicalDate)
                        if (!exists) {
                            val entry = JSONObject()
                                .put("date", historicalDate).put("profile", historicalProfile)
                                .put("packTotal", historicalPacks.sumOf { it.weight })
                                .put("defectTotal", historicalDefects.sumOf { it.weight })
                                .put("packs", JSONArray().also { a -> historicalPacks.forEach { p -> a.put(JSONObject().put("w", p.weight).put("r", p.rows).put("n", p.pieces).put("k", p.coefficient).put("width", p.width).put("length", p.length)) } })
                                .put("defects", JSONArray().also { a -> historicalDefects.forEach { d -> a.put(JSONObject().put("t", d.type).put("w", d.weight)) } })
                                .put("pallets", JSONArray().also { a -> historicalPallets.forEach { p -> a.put(JSONObject().put("t", p.type).put("g", p.gross).put("a", p.tare)) } })
                            saved.put(entry)
                            prefs.edit().putString("shifts_archive", saved.toString()).apply()
                            writeAutomaticBackup()
                            archiveYear = historicalDate.takeLast(4)
                            archiveDateFilter = historicalDate
                            screen = "archive"
                        } else {
                            android.widget.Toast.makeText(this@MainActivity, "За эту дату бланк уже есть. Выберите другую дату.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("СОХРАНИТЬ В АРХИВ") }
                    OutlinedButton(onClick = { screen = "archive" }, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
                }
                "archive_detail" -> {
                    val shift = JSONObject(archiveShiftJson ?: "{}")
                    val ap = shift.optJSONArray("packs") ?: JSONArray()
                    val ad = shift.optJSONArray("defects") ?: JSONArray()
                    val aa = shift.optJSONArray("pallets") ?: JSONArray()
                    Text(shift.optString("date") + " · " + shift.optString("profile"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        item { Text("ПАЧКИ", fontWeight = FontWeight.Bold) }
                        items(ap.length()) { i ->
                            val item = ap.getJSONObject(i)
                            Text("${i + 1}. ${fmtWeight(item.optDouble("w"))} кг | К ${fmtCoefficient(item.optDouble("k"))} | ${item.optInt("n")} шт | ${item.optInt("r")} рядов")
                        }
                        item { Text("БРАК", fontWeight = FontWeight.Bold) }
                        items(ad.length()) { i ->
                            val item = ad.getJSONObject(i)
                            Text(item.optString("t") + ": " + fmtWeight(item.optDouble("w")) + " кг")
                        }
                        item { Text("ПОДДОНЫ", fontWeight = FontWeight.Bold) }
                        items(aa.length()) { i ->
                            val item = aa.getJSONObject(i)
                            val grossValue = item.optDouble("g")
                            val tareValue = item.optDouble("a")
                            Text(item.optString("t") + ": брутто " + fmtWeight(grossValue) + " · тара " + fmtWeight(tareValue) + " · нетто " + fmtWeight(grossValue - tareValue) + " кг")
                        }
                    }
                    OutlinedButton(onClick = {
                        val archivedPacks = (0 until ap.length()).map { i ->
                            val item = ap.getJSONObject(i)
                            Pack(item.optDouble("w"), item.optInt("r"), item.optInt("n"), item.optDouble("k"), item.optInt("width", 5), item.optDouble("length", 6.0))
                        }
                        val archivedDefects = (0 until ad.length()).map { i ->
                            val item = ad.getJSONObject(i); Defect(item.optString("t"), item.optDouble("w"))
                        }
                        val archivedPallets = (0 until aa.length()).map { i ->
                            val item = aa.getJSONObject(i); Pallet(item.optString("t"), item.optDouble("g"), item.optDouble("a"))
                        }
                        sharePdf(shift.optString("date"), shift.optString("profile"), archivedPacks, archivedDefects, archivedPallets)
                    }, modifier = Modifier.fillMaxWidth()) { Text("Поделиться PDF") }
                    OutlinedButton(onClick = { screen = "archive" }, modifier = Modifier.fillMaxWidth()) { Text("К архиву") }
                }
            }
        }

        if (deletePackIndex >= 0) AlertDialog(onDismissRequest = { deletePackIndex = -1 }, title = { Text("Удалить пачку?") }, text = { Text("Запись будет удалена из текущей смены.") }, confirmButton = { TextButton(onClick = { if (deletePackIndex in packs.indices) packs.removeAt(deletePackIndex); deletePackIndex = -1; saveAll() }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { deletePackIndex = -1 }) { Text("Отмена") } })
        if (deleteDefectIndex >= 0) AlertDialog(onDismissRequest = { deleteDefectIndex = -1 }, title = { Text("Удалить брак?") }, text = { Text("Запись будет удалена из текущей смены.") }, confirmButton = { TextButton(onClick = { if (deleteDefectIndex in defects.indices) defects.removeAt(deleteDefectIndex); deleteDefectIndex = -1; saveAll() }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { deleteDefectIndex = -1 }) { Text("Отмена") } })
        if (deletePalletIndex >= 0) AlertDialog(onDismissRequest = { deletePalletIndex = -1 }, title = { Text("Удалить поддон?") }, text = { Text("Запись будет удалена из текущей смены.") }, confirmButton = { TextButton(onClick = { if (deletePalletIndex in pallets.indices) pallets.removeAt(deletePalletIndex); deletePalletIndex = -1; saveAll() }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { deletePalletIndex = -1 }) { Text("Отмена") } })
        if (deleteArchiveEntry != null) AlertDialog(
            onDismissRequest = { deleteArchiveEntry = null },
            title = { Text("Удалить эту смену из архива?") },
            text = { Text("Будет удалён только выбранный бланк. Остальные смены останутся в архиве.") },
            confirmButton = {
                TextButton(onClick = {
                    val selectedEntry = deleteArchiveEntry
                    val saved = JSONArray(prefs.getString("shifts_archive", "[]"))
                    val kept = JSONArray()
                    var removed = false
                    for (i in 0 until saved.length()) {
                        val item = saved.getJSONObject(i)
                        if (!removed && item.toString() == selectedEntry) {
                            removed = true
                        } else {
                            kept.put(item)
                        }
                    }
                    if (removed) {
                        prefs.edit().putString("shifts_archive", kept.toString()).apply()
                        writeAutomaticBackup()
                        val selectedDate = try { JSONObject(selectedEntry ?: "{}").optString("date") } catch (_: Exception) { "" }
                        if (archiveDateFilter == selectedDate) archiveDateFilter = ""
                        if (archiveShiftJson == selectedEntry) { archiveShiftJson = null; screen = "archive" }
                    }
                    deleteArchiveEntry = null
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { deleteArchiveEntry = null }) { Text("Отмена") } }
        )
        if (showHandoverDialog) AlertDialog(
            onDismissRequest = { showHandoverDialog = false },
            title = { Text("Смену сдает") },
            text = {
                OutlinedTextField(
                    value = handoverText,
                    onValueChange = { handoverText = it },
                    label = { Text("Текст для PDF") },
                    placeholder = { Text("Смену сдал Иванов Иван Иванович") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putString("shift_handover_text", handoverText.trim()).apply()
                    handoverText = handoverText.trim()
                    showHandoverDialog = false
                }) { Text("Сохранить") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        handoverText = ""
                        prefs.edit().remove("shift_handover_text").apply()
                        showHandoverDialog = false
                    }) { Text("Очистить") }
                    TextButton(onClick = { showHandoverDialog = false }) { Text("Отмена") }
                }
            }
        )
        if (showProfileManager) AlertDialog(
            onDismissRequest = { showProfileManager = false },
            title = { Text("Управление профилями") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Созданные профили", fontWeight = FontWeight.SemiBold)
                    if (profileNames.isEmpty()) Text("Пока нет созданных профилей.")
                    profileNames.forEach { item ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = {
                                profile = item
                                val key = item.substringAfterLast(" ")
                                width = prefs.getString("profile_${key}_width", "5") ?: "5"
                                rows = prefs.getString("profile_${key}_rows", "15") ?: "15"
                                length = prefs.getString("profile_${key}_length", "6") ?: "6"
                            }) { Text((if (profile == item) "✓ " else "") + item) }
                            TextButton(onClick = {
                                profile = item
                                val key = item.substringAfterLast(" ")
                                width = prefs.getString("profile_${key}_width", "5") ?: "5"
                                rows = prefs.getString("profile_${key}_rows", "15") ?: "15"
                                length = prefs.getString("profile_${key}_length", "6") ?: "6"
                                showProfileManager = false
                                showSettings = true
                            }) { Text("Настроить") }
                        }
                    }
                    Divider()
                    Text("Создать новый профиль", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(newProfileName, { newProfileName = it }, label = { Text("Название профиля") }, singleLine = true)
                    OutlinedTextField(newProfileWidth, { newProfileWidth = it }, label = { Text("Ширина пачки, шт") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    OutlinedTextField(newProfileRows, { newProfileRows = it }, label = { Text("Рядов по умолчанию") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    OutlinedTextField(newProfileLength, { newProfileLength = it }, label = { Text("Длина изделия, м") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    Text("Существующие профили: " + profileNames.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newProfileName.trim()
                    val w = newProfileWidth.toIntOrNull()
                    val r = newProfileRows.toIntOrNull()
                    val l = newProfileLength.replace(',', '.').toDoubleOrNull()
                    if (name.isNotBlank() && !profileNames.contains(name) && w != null && w > 0 && r != null && r > 0 && l != null && l > 0) {
                        profileNames.add(name)
                        val key = name.substringAfterLast(" ")
                        prefs.edit().putString("profile_list", JSONArray(profileNames).toString())
                            .putString("profile_${key}_width", w.toString())
                            .putString("profile_${key}_rows", r.toString())
                            .putString("profile_${key}_length", l.toString()).apply()
                        profile = name
                        width = w.toString()
                        rows = r.toString()
                        length = l.toString()
                        newProfileName = ""
                        showProfileManager = false
                        writeAutomaticBackup()
                    } else {
                        android.widget.Toast.makeText(this@MainActivity, "Проверь название и параметры. Название должно быть уникальным.", android.widget.Toast.LENGTH_LONG).show()
                    }
                }) { Text("Добавить профиль") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { if (profileNames.contains(profile)) confirmDeleteProfile = true else android.widget.Toast.makeText(this@MainActivity, "Выбери профиль для удаления", android.widget.Toast.LENGTH_SHORT).show() }) { Text("Удалить выбранный") }
                    TextButton(onClick = { showProfileManager = false }) { Text("Закрыть") }
                }
            }
        )
        if (confirmDeleteProfile) AlertDialog(
            onDismissRequest = { confirmDeleteProfile = false },
            title = { Text("Удалить профиль $profile?") },
            text = { Text("Профиль исчезнет из списка. Уже сохранённые смены и пачки в архиве останутся без изменений.") },
            confirmButton = { TextButton(onClick = {
                if (profileNames.contains(profile)) {
                    profileNames.remove(profile)
                    prefs.edit().putString("profile_list", JSONArray(profileNames).toString()).apply()
                    profile = profileNames.firstOrNull() ?: ""
                    if (profile.isNotBlank()) {
                        val key = profile.substringAfterLast(" ")
                        width = prefs.getString("profile_${key}_width", "5") ?: "5"
                        rows = prefs.getString("profile_${key}_rows", "15") ?: "15"
                        length = prefs.getString("profile_${key}_length", "6") ?: "6"
                    }
                    writeAutomaticBackup()
                }
                confirmDeleteProfile = false
                showProfileManager = false
            }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDeleteProfile = false }) { Text("Отмена") } }
        )

        if (showSettings) AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("Настройки профиля: $profile") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(width, { width = it }, label = { Text("Ширина пачки, шт") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(length, { length = it }, label = { Text("Длина штуки, м") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(rows, { rows = it }, label = { Text("Высота по умолчанию, рядов") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }, confirmButton = { TextButton(onClick = { showSettings = false; saveAll() }) { Text("Сохранить") } })

        if (editPack >= 0) AlertDialog(onDismissRequest = { editPack = -1 }, title = { Text("Изменить пачку") }, text = {
            Column {
                OutlinedTextField(editWeight, { editWeight = it }, label = { Text("Вес, кг") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(editRows, { editRows = it }, label = { Text("Ряды") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(editWidth, { editWidth = it }, label = { Text("Ширина, шт") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }, confirmButton = { TextButton(onClick = {
            val w = editWeight.replace(',', '.').toDoubleOrNull()
            val r = editRows.toIntOrNull()
            val widthValue = editWidth.toIntOrNull()
            val lengthValue = packs[editPack].length
            if (w != null && r != null && widthValue != null && w > 0 && r > 0 && widthValue > 0) {
                val count = (widthValue * 2 - 1) * r - (widthValue - 1)
                packs[editPack] = Pack(w, r, count, round(w / (count * lengthValue) * 10.0) / 10.0, widthValue, lengthValue)
                saveAll()
            }
            editPack = -1
        }) { Text("Сохранить") } })

        if (editDefect >= 0) AlertDialog(onDismissRequest = { editDefect = -1 }, title = { Text("Изменить брак") }, text = {
            Column {
                OutlinedTextField(editType, { editType = it }, label = { Text("Категория") })
                OutlinedTextField(editWeight, { editWeight = it }, label = { Text("Вес, кг") })
            }
        }, confirmButton = { TextButton(onClick = {
            val w = editWeight.replace(',', '.').toDoubleOrNull()
            if (w != null && w > 0) { defects[editDefect] = Defect(editType, w); saveAll() }
            editDefect = -1
        }) { Text("Сохранить") } })

        if (editPallet >= 0) AlertDialog(onDismissRequest = { editPallet = -1 }, title = { Text("Изменить поддон") }, text = {
            Column {
                OutlinedTextField(editType, { editType = it }, label = { Text("Категория") })
                OutlinedTextField(editGross, { editGross = it }, label = { Text("Общий вес") })
                OutlinedTextField(editTare, { editTare = it }, label = { Text("Тара") })
            }
        }, confirmButton = { TextButton(onClick = {
            val g = editGross.replace(',', '.').toDoubleOrNull()
            val t = editTare.replace(',', '.').toDoubleOrNull()
            if (g != null && t != null && g > t) { pallets[editPallet] = Pallet(editType, g, t); saveAll() }
            editPallet = -1
        }) { Text("Сохранить") } })

        if (confirmFinish) AlertDialog(onDismissRequest = { confirmFinish = false }, title = { Text("Завершить смену?") },
            text = { Text("Смена будет сохранена в архиве.") },
            confirmButton = { TextButton(onClick = {
                if (archiveHasDate(date)) {
                    android.widget.Toast.makeText(this@MainActivity, "Смена за эту дату уже есть в архиве. Текущая смена не сохранена.", android.widget.Toast.LENGTH_LONG).show()
                    confirmFinish = false
                } else {
                val savedShifts = JSONArray(prefs.getString("shifts_archive", "[]"))
                val shift = JSONObject()
                    .put("date", date)
                    .put("profile", profile)
                    .put("packTotal", packTotal)
                    .put("defectTotal", defectTotal)
                    .put("status", if (packs.isEmpty() && defects.isEmpty() && pallets.isEmpty()) "Простой" else "Завершена")
                    .put("packs", JSONArray().also { array -> packs.forEach { p -> array.put(JSONObject().put("w", p.weight).put("r", p.rows).put("n", p.pieces).put("k", p.coefficient).put("width", p.width).put("length", p.length)) } })
                    .put("defects", JSONArray().also { array -> defects.forEach { d -> array.put(JSONObject().put("t", d.type).put("w", d.weight)) } })
                    .put("pallets", JSONArray().also { array -> pallets.forEach { p -> array.put(JSONObject().put("t", p.type).put("g", p.gross).put("a", p.tare)) } })
                // Sync the completed shift before clearing StanControl's current data.
                syncShiftToProControl(date, packTotal, defectTotal)
                savedShifts.put(shift)
                prefs.edit().putString("shifts_archive", savedShifts.toString())
                    .putString("archive", "Сохранено смен: " + savedShifts.length())
                    .putBoolean("active", false)
                    .putString("packs", "[]").putString("defects", "[]").putString("pallets", "[]")
                    .apply()
                packs.clear()
                defects.clear()
                pallets.clear()
                active = false
                saveAll()
                screen = "home"
                confirmFinish = false
                }
            }) { Text("Завершить") } },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Отмена") } })
    }

    /**
     * A shift belongs to one calendar date. When the device date advances,
     * archive the previous active shift and prepare an empty record for today.
     */

    private fun syncArchivedShiftsToProControl() {
        val archived = try { JSONArray(prefs.getString("shifts_archive", "[]")) } catch (_: Exception) { JSONArray() }
        val sourceShifts = mutableListOf<Triple<String, Double, Double>>()
        for (i in 0 until archived.length()) {
            val shift = archived.optJSONObject(i) ?: continue
            val shiftDate = shift.optString("date", "")
            if (displayDateToIso(shiftDate) == null) continue
            sourceShifts.add(Triple(shiftDate, shift.optDouble("packTotal", 0.0), shift.optDouble("defectTotal", 0.0)))
        }

        // StanControl archive is the single source of truth for production records in CashControl.
        // Rebuild synced production data from scratch so edits, date changes and deletions are mirrored.
        val target = getSharedPreferences("procontrol", MODE_PRIVATE)
        val periods = try { JSONArray(target.getString("archive", "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until periods.length()) {
            val period = periods.optJSONObject(i) ?: continue
            period.put("records", JSONArray()).put("clean", 0.0).put("scrap", 0.0)
            periods.put(i, period)
        }
        target.edit()
            .putString("entries", "[]")
            .putString("historical_entries", "[]")
            .putString("archive", periods.toString())
            .commit()

        for ((date, cleanKg, scrapKg) in sourceShifts) {
            syncShiftToProControl(date, cleanKg, scrapKg)
        }
    }

    private fun displayDateToIso(displayDate: String): String? {
        val parts = displayDate.split(".")
        if (parts.size != 3 || parts[0].length != 2 || parts[1].length != 2 || parts[2].length != 4) return null
        if (parts.any { it.any { ch -> !ch.isDigit() } }) return null
        return "${parts[2]}-${parts[1]}-${parts[0]}"
    }

    private fun syncShiftToProControl(displayDate: String, cleanKg: Double, scrapKg: Double) {
        val isoDate = try {
            val parsed = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).parse(displayDate)
                ?: return
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(parsed)
        } catch (_: Exception) { return }

        val target = getSharedPreferences("procontrol", MODE_PRIVATE)
        val cleanTonnes = cleanKg / 1000.0
        val scrapTonnes = scrapKg / 1000.0
        val saved = JSONObject().put("date", isoDate)
            .put("clean", cleanTonnes).put("scrap", scrapTonnes).put("note", "").put("source", "StanControl").put("source", "StanControl")

        val archive = try { JSONArray(target.getString("archive", "[]")) } catch (_: Exception) { JSONArray() }
        for (i in 0 until archive.length()) {
            val period = archive.optJSONObject(i) ?: continue
            val records = period.optJSONArray("records") ?: JSONArray()
            val recordIndex = (0 until records.length()).firstOrNull {
                records.optJSONObject(it)?.optString("date") == isoDate
            }
            val start = period.optString("start")
            val end = period.optString("end")
            if (recordIndex != null || (start.isNotBlank() && end.isNotBlank() && isoDate >= start && isoDate <= end)) {
                if (recordIndex == null) records.put(saved) else records.put(recordIndex, saved)
                var cleanSum = 0.0
                var scrapSum = 0.0
                for (j in 0 until records.length()) {
                    val record = records.optJSONObject(j) ?: continue
                    cleanSum += record.optDouble("clean")
                    scrapSum += record.optDouble("scrap")
                }
                period.put("records", records).put("clean", cleanSum).put("scrap", scrapSum)
                archive.put(i, period)
                target.edit().putString("archive", archive.toString()).apply()
                return
            }
        }

        val history = try { JSONArray(target.getString("historical_entries", "[]")) } catch (_: Exception) { JSONArray() }
        val historicalIndex = (0 until history.length()).firstOrNull {
            history.optJSONObject(it)?.optString("date") == isoDate
        }
        if (historicalIndex != null) {
            history.put(historicalIndex, saved)
            target.edit().putString("historical_entries", history.toString()).apply()
            return
        }

        val entries = try { JSONArray(target.getString("entries", "[]")) } catch (_: Exception) { JSONArray() }
        val existingIndex = (0 until entries.length()).firstOrNull {
            entries.optJSONObject(it)?.optString("date") == isoDate
        }
        if (existingIndex == null) entries.put(saved) else entries.put(existingIndex, saved)
        target.edit().putString("entries", entries.toString()).apply()
    }

    private fun archiveStaleActiveShift(): Boolean {
        if (!prefs.getBoolean("active", false)) return false
        val shiftDate = prefs.getString("date", "") ?: ""
        if (shiftDate.isBlank() || shiftDate == today()) return false

        val packs = try { JSONArray(prefs.getString("packs", "[]")) } catch (_: Exception) { JSONArray() }
        val defects = try { JSONArray(prefs.getString("defects", "[]")) } catch (_: Exception) { JSONArray() }
        val pallets = try { JSONArray(prefs.getString("pallets", "[]")) } catch (_: Exception) { JSONArray() }
        val packTotal = (0 until packs.length()).sumOf { packs.optJSONObject(it)?.optDouble("w", 0.0) ?: 0.0 }
        val defectTotal = (0 until defects.length()).sumOf { defects.optJSONObject(it)?.optDouble("w", 0.0) ?: 0.0 }
        val idle = packs.length() == 0 && defects.length() == 0 && pallets.length() == 0

        val savedShifts = try { JSONArray(prefs.getString("shifts_archive", "[]")) } catch (_: Exception) { JSONArray() }
        val alreadyArchived = (0 until savedShifts.length()).any {
            savedShifts.optJSONObject(it)?.optString("date") == shiftDate
        }
        if (!alreadyArchived) {
            syncShiftToProControl(shiftDate, packTotal, defectTotal)
            savedShifts.put(JSONObject()
                .put("date", shiftDate)
                .put("profile", prefs.getString("profile", "Уголок 50") ?: "Уголок 50")
                .put("packTotal", packTotal)
                .put("defectTotal", defectTotal)
                .put("packs", packs)
                .put("defects", defects)
                .put("pallets", pallets)
                .put("status", if (idle) "Простой" else "Завершена")
                .put("autoClosed", true)
                .put("closedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())))
        }

        prefs.edit()
            .putString("shifts_archive", savedShifts.toString())
            .putString("archive", "Сохранено смен: " + savedShifts.length())
            .putBoolean("active", false)
            .putString("date", today())
            .putString("packs", "[]")
            .putString("defects", "[]")
            .putString("pallets", "[]")
            .commit()
        writeAutomaticBackup()
        return true
    }

    private fun writeAutomaticBackup(): File {
        val backupDir = File(filesDir, "backups").apply { mkdirs() }
        val backupFile = File(backupDir, "StanControl_auto_backup.json")
        val data = JSONObject()
        prefs.all.forEach { (key, value) ->
            when (value) {
                is String, is Boolean, is Int, is Long, is Float, is Double -> data.put(key, value)
            }
        }
        val root = JSONObject()
            .put("app", "StanControl")
            .put("backupVersion", 1)
            .put("createdAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            .put("data", data)
        val temp = File(backupDir, "backup.tmp")
        temp.writeText(root.toString(2), Charsets.UTF_8)
        if (backupFile.exists()) backupFile.delete()
        if (!temp.renameTo(backupFile)) {
            temp.copyTo(backupFile, overwrite = true)
            temp.delete()
        }
        return backupFile
    }

    private fun shareBackup() {
        try {
            val file = writeAutomaticBackup()
            val uri = FileProvider.getUriForFile(this, "kg.stancontrol.fileprovider", file)
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Резервная копия StanControl")
                putExtra(Intent.EXTRA_TEXT, "Резервная копия данных StanControl")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sendIntent, "Отправить резервную копию"))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Не удалось создать бекап: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun sharePdf(date: String, profile: String, packs: List<Pack>, defects: List<Defect>, pallets: List<Pallet>) {
        when (prefs.getString("pdf_template", "classic") ?: "classic") {
            "grid" -> shareGridPdf(date, profile, packs, defects, pallets)
            "original" -> shareOriginalPdf(date, profile, packs, defects, pallets)
            else -> shareClassicPdf(date, profile, packs, defects, pallets)
        }
    }

    private fun shareClassicPdf(date: String, profile: String, packs: List<Pack>, defects: List<Defect>, pallets: List<Pallet>) {
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val left = 30f
        val right = 565f
        val width = right - left
        var y = 30f

        fun text(value: String, x: Float, baseline: Float, size: Float = 9f, bold: Boolean = false, color: Int = android.graphics.Color.BLACK) {
            paint.color = color
            paint.textSize = size
            paint.typeface = if (bold) android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD) else android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            canvas.drawText(value, x, baseline, paint)
        }
        fun line(yLine: Float, color: Int = 0xFFB8B8B8.toInt(), stroke: Float = 0.7f) {
            paint.color = color
            paint.strokeWidth = stroke
            canvas.drawLine(left, yLine, right, yLine, paint)
        }
        fun section(title: String) {
            paint.color = 0xFFEAE6F2.toInt()
            canvas.drawRect(left, y, right, y + 20f, paint)
            text(title, left + 7f, y + 14f, 10f, true)
            y += 20f
        }
        fun tableHeader(headers: List<String>, xs: List<Float>, rowH: Float = 19f) {
            paint.color = 0xFFF4F4F4.toInt()
            canvas.drawRect(left, y, right, y + rowH, paint)
            headers.forEachIndexed { i, h -> text(h, xs[i], y + 13f, 8.5f, true) }
            line(y + rowH, 0xFF777777.toInt(), 0.9f)
            y += rowH
        }
        fun tableRow(values: List<String>, xs: List<Float>, rowH: Float = 18f, bold: Boolean = false) {
            values.forEachIndexed { i, v -> text(v, xs[i], y + 12.5f, 9f, bold) }
            line(y + rowH, 0xFFE0E0E0.toInt(), 0.5f)
            y += rowH
        }
        fun total(label: String, value: String) {
            paint.color = 0xFFF3F1F6.toInt()
            canvas.drawRect(left, y, right, y + 20f, paint)
            text(label, left + 7f, y + 14f, 9f, true)
            text(value, right - paint.measureText(value) - 8f, y + 14f, 9f, true)
            y += 24f
        }

        val goodWeight = packs.sumOf { it.weight }
        val defectWeight = defects.sumOf { it.weight }
        val percent = if (goodWeight + defectWeight > 0) defectWeight * 100.0 / (goodWeight + defectWeight) else 0.0

        // Компактная строка даты и профиля вместо крупного вчерашнего заголовка.
        text("Дата: $date", left + 6f, y + 12f, 9f, true)
        text("Вид: $profile", left + 190f, y + 12f, 9f, true)
        y += 22f
        val metricGap = 8f
        val metricWidth = (width - metricGap * 2f) / 3f
        val metrics = listOf(
            "ЧИСТЫЙ ВЕС" to "${fmtWeight(goodWeight)} кг",
            "ВЕС БРАКА" to "${fmtWeight(defectWeight)} кг",
            "ОБЩИЙ ВЕС" to "${fmtWeight(goodWeight + defectWeight)} кг"
        )
        metrics.forEachIndexed { index, metric ->
            val x = left + index * (metricWidth + metricGap)
            paint.color = 0xFFF3F1F6.toInt()
            canvas.drawRect(x, y, x + metricWidth, y + 25f, paint)
            text(metric.first, x + 7f, y + 10f, 7.5f, true)
            text(metric.second, x + 7f, y + 21f, 9f, true)
        }
        y += 31f

        section("1. ГОТОВАЯ ПРОДУКЦИЯ · ПАЧКИ")

        val packXs = listOf(left + 10f, left + 95f, left + 220f, left + 365f)
        tableHeader(listOf("№", "КОЭФФИЦИЕНТ", "ВЕС, КГ", "КОЛ-ВО, ШТ"), packXs)
        if (packs.isEmpty()) {
            tableRow(listOf("-", "0.0", "0.0", "0"), packXs)
        } else {
            packs.forEachIndexed { i, p ->
                tableRow(listOf((i + 1).toString(), fmtCoefficient(p.coefficient), fmtWeight(p.weight), p.pieces.toString()), packXs)
            }
        }
        total("ИТОГО ГОТОВАЯ ПРОДУКЦИЯ", "${fmtWeight(packs.sumOf { it.weight })} кг     ${packs.sumOf { it.pieces }} шт")

        section("2. БРАК")
        val cutting = defects.filter { it.type == "Резка" }.sumOf { it.weight }
        val scrap = defects.filter { it.type == "Металлолом" }.sumOf { it.weight }
        val defectXs = listOf(left + 10f, left + 220f)
        tableHeader(listOf("КАТЕГОРИЯ", "ВЕС, КГ"), defectXs)
        tableRow(listOf("Резка", fmtWeight(cutting)), defectXs)
        tableRow(listOf("Металлолом", fmtWeight(scrap)), defectXs)
        total("ВСЕГО БРАК · ${fmt(percent)} % ОТ ОБЩЕГО ВЕСА", "${fmtWeight(defectWeight)} кг")

        val palletXs = listOf(left + 10f, left + 125f, left + 260f, left + 395f)
        val rolling = pallets.filter { it.type == "Прокат" }
        val cuttingPallets = pallets.filter { it.type == "Резка" }
        section("3. ПОДДОНЫ ПРОКАТ")
        tableHeader(listOf("№", "БРУТТО, КГ", "ТАРА, КГ", "НЕТТО, КГ"), palletXs)
        if (rolling.isEmpty()) {
            tableRow(listOf("-", "0.0", "0.0", "0.0"), palletXs)
        } else {
            rolling.forEachIndexed { i, p -> tableRow(listOf((i + 1).toString(), fmtWeight(p.gross), fmtWeight(p.tare), fmt(p.net)), palletXs) }
        }
        total("ИТОГО ПОДДОНЫ ПРОКАТ", "${fmtWeight(rolling.sumOf { it.net })} кг")

        section("4. ПОДДОНЫ РЕЗКА")
        tableHeader(listOf("№", "БРУТТО, КГ", "ТАРА, КГ", "НЕТТО, КГ"), palletXs)
        if (cuttingPallets.isEmpty()) {
            tableRow(listOf("-", "0.0", "0.0", "0.0"), palletXs)
        } else {
            cuttingPallets.forEachIndexed { i, p -> tableRow(listOf((i + 1).toString(), fmt(p.gross), fmt(p.tare), fmt(p.net)), palletXs) }
        }
        total("ИТОГО ПОДДОНЫ РЕЗКА", "${fmtWeight(cuttingPallets.sumOf { it.net })} кг")
        total("ВСЕГО ПОДДОНЫ · НЕТТО", "${fmtWeight(pallets.sumOf { it.net })} кг")

        // A compact footer and border make the page read as a finished form.
        y += 3f
        line(y, 0xFF777777.toInt(), 1f)
        text("StanControl  ·  Сменный производственный отчёт", left, y + 13f, 7.5f, false, 0xFF666666.toInt())
        drawHandoverSignature(canvas, paint)

        pdf.finishPage(page)
        val dir = File(cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, "StanControl-$date.pdf")
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        val uri = FileProvider.getUriForFile(this, "kg.stancontrol.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Поделиться PDF"))
    }

    private fun shareGridPdf(date: String, profile: String, packs: List<Pack>, defects: List<Defect>, pallets: List<Pallet>) {
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val left = 18f
        val right = 577f
        val tableWidth = right - left

        fun cell(x: Float, y: Float, w: Float, h: Float, value: String, bold: Boolean = false, size: Float = 6.5f, center: Boolean = false) {
            paint.color = android.graphics.Color.BLACK
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.45f
            canvas.drawRect(x, y, x + w, y + h, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = size
            paint.typeface = if (bold) android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL) else android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            val lines = value.split("\n")
            val lineHeight = size + 1f
            val firstBaseline = y + h / 2f + (lines.size - 1) * lineHeight / 2f + size / 3f
            lines.forEachIndexed { index, line ->
                val tx = if (center) x + (w - paint.measureText(line)) / 2f else x + 2f
                canvas.drawText(line, tx, firstBaseline - index * lineHeight, paint)
            }
        }
        fun drawText(value: String, x: Float, baseline: Float, size: Float = 6.5f, bold: Boolean = false) {
            paint.color = android.graphics.Color.BLACK
            paint.textSize = size
            paint.typeface = if (bold) android.graphics.Typeface.create("sans-serif-black", android.graphics.Typeface.NORMAL) else android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            canvas.drawText(value, x, baseline, paint)
        }

        var y = 18f
        drawText("Дата: $date", left + 4f, y + 8f, 8f, true)
        y += 17f

        // Use the full printable page width for the main grid.
        // Give the 6 m column more room; the long defect label is shortened to "Брак".
        val widths = listOf(26f, 42f, 48f, 95f, 50f, 50f, 50f, 50f, 52f, 96f)
        val headers = listOf("№", "Вид", "Коэф.", "6 м", "5 м", "4 м", "3 м", "2 м", "Брак\nВиды", "Вес, кг")
        val gridWidth = widths.sum()
        val gridLeft = left
        var x = gridLeft
        headers.forEachIndexed { i, h -> cell(x, y, widths[i], 25f, h, true, 7.1f, true); x += widths[i] }
        y += 25f

        val clean = packs.sumOf { it.weight }
        val bad = defects.sumOf { it.weight }
        val total = clean + bad
        val rolling = pallets.filter { it.type == "Прокат" }
        val cuttingPallets = pallets.filter { it.type == "Резка" }
        val palletCount = maxOf(rolling.size, cuttingPallets.size)
        // Packs and defects are parallel lists: each starts at the first numbered row.
        val usedEntryRows = maxOf(packs.size, defects.size)
        val summaryRowHeight = 25f
        val palletGap = 28f
        val summaryHeight = 5f + summaryRowHeight + palletGap
        val palletHeaderHeight = 17f + 13f + 13f
        val palletRowHeight = 13f
        val footerHeight = 7f + 34f + 39f + 14f
        val bottomLimit = 824f
        val availableForPackRows = bottomLimit - y - summaryHeight - palletHeaderHeight - palletCount * palletRowHeight - footerHeight
        // Production never exceeds 30 entries; keep at least 35 numbered rows on the sheet.
        // Use the reclaimed lower margin to give all 35 rows a taller, fixed height.
        val packRowHeight = 15.5f
        val totalRowsSpace = bottomLimit + 56.7f - y - summaryHeight - palletHeaderHeight - palletCount * palletRowHeight - footerHeight
        val rowCount = maxOf(35, usedEntryRows)
        val actualRowHeight = if (rowCount > 0) {
            (totalRowsSpace / rowCount).coerceAtMost(packRowHeight).coerceAtLeast(7.0f)
        } else packRowHeight

        val lengths = listOf(6, 5, 4, 3, 2)
        for (i in 0 until rowCount) {
            val p = packs.getOrNull(i)
            val defect = defects.getOrNull(i)
            val row = mutableListOf(
                (i + 1).toString(),
                profile,
                p?.let { fmtCoefficient(it.coefficient) } ?: "",
                "", "", "", "", "",
                defect?.let { if (it.type == "Металлолом") "Брак" else it.type } ?: "",
                defect?.let { fmtWhole(it.weight) } ?: ""
            )
            if (p != null) {
                val idx = lengths.indexOf(kotlin.math.round(p.length).toInt())
                if (idx >= 0) row[3 + idx] = fmtWeight(p.weight)
            }
            x = gridLeft
            row.forEachIndexed { j, value ->
                // Увеличиваем только коэффициент и цифры веса в верхней таблице.
                // Нижний блок поддонов и вся геометрия таблицы остаются без изменений.
                val numericSize = if (j in 2..7 || j == 9) 15.5f else 9.5f
                cell(x, y, widths[j], actualRowHeight, value, true, numericSize, true)
                x += widths[j]
            }
            y += actualRowHeight
        }

        y += 5f
        val rejectPercent = if (total > 0.0) bad * 100.0 / total else 0.0
        val summaryPackWidth = gridWidth * 0.34f
        val summaryDefectWidth = gridWidth * 0.33f
        val summaryPercentWidth = gridWidth - summaryPackWidth - summaryDefectWidth
        cell(gridLeft, y, summaryPackWidth, summaryRowHeight, "Вес пачек: ${fmtWhole(clean)} кг", true, 7.8f, true)
        cell(gridLeft + summaryPackWidth, y, summaryDefectWidth, summaryRowHeight, "Вес брака: ${fmtWhole(bad)} кг", true, 7.8f, true)
        cell(gridLeft + summaryPackWidth + summaryDefectWidth, y, summaryPercentWidth, summaryRowHeight, "Процент брака: ${String.format(Locale.getDefault(), "%.2f", rejectPercent)}%", true, 7.8f, true)
        y += summaryRowHeight + palletGap

        val palletColGap = 8f
        val palletColWidth = (tableWidth - palletColGap) / 2f
        fun palletSection(title: String, selected: List<Pallet>, startX: Float) {
            cell(startX, y, palletColWidth, 17f, title, true, 7.2f, true)
            var py = y + 17f
            val c1 = 22f
            val c2 = 88f
            val c3 = 72f
            val c4 = palletColWidth - c1 - c2 - c3
            cell(startX, py, c1, 13f, "№", true, 6f, true)
            cell(startX + c1, py, c2, 13f, "Общий вес", true, 6f, true)
            cell(startX + c1 + c2, py, c3, 13f, "Тара", true, 6f, true)
            cell(startX + c1 + c2 + c3, py, c4, 13f, "Чистый вес", true, 6f, true)
            py += 13f
            for (i in 0 until palletCount) {
                val p = selected.getOrNull(i)
                cell(startX, py, c1, 13f, if (p == null) "" else (i + 1).toString(), p != null, 7.2f, true)
                cell(startX + c1, py, c2, 13f, p?.let { fmtWhole(it.gross) } ?: "", p != null, 7.2f, true)
                cell(startX + c1 + c2, py, c3, 13f, p?.let { fmtWhole(it.tare) } ?: "", p != null, 7.2f, true)
                cell(startX + c1 + c2 + c3, py, c4, 13f, p?.let { fmtWhole(it.net) } ?: "", p != null, 7.2f, true)
                py += 13f
            }
            val netTotal = selected.sumOf { it.net }
            cell(startX, py, c1 + c2 + c3, 13f, "Общий чистый вес", true, 6f, true)
            cell(startX + c1 + c2 + c3, py, c4, 13f, fmtWhole(netTotal), true, 7.2f, true)
        }
        palletSection("Поддоны прокат", rolling, left)
        palletSection("Поддоны резка", cuttingPallets, left + palletColWidth + palletColGap)
        y += 17f + 13f + palletCount * 13f + 13f + 7f
        drawText("Примечание: ____________________________________________", left + 4f, y + 10f, 6.5f)
        drawHandoverSignature(canvas, paint)

        pdf.finishPage(page)
        val dir = File(cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, "StanControl-$date-grid.pdf")
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        val uri = FileProvider.getUriForFile(this, "kg.stancontrol.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Поделиться PDF"))
    }

    // Exact blank production sheet. Its original 45 numbered rows and layout stay independent of the other PDF templates.
    private fun shareOriginalPdf(date: String, profile: String, packs: List<Pack>, defects: List<Defect>, pallets: List<Pallet>) {
        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val left = 35f
        val widths = listOf(64f, 36f, 52f, 77f, 50f, 50f, 50f, 50f, 42f, 54f)
        val tableWidth = widths.sum()
        val top = 42f
        val dateHeight = 16f
        val headerHeight = 25f
        val rowHeight = 14.1f
        val headers = listOf("№", "Вид", "Коэф.\nфициент", "6 м", "5 м", "4 м", "3 м", "2 м", "Брак\nВид", "Вес")
        paint.color = android.graphics.Color.BLACK
        paint.strokeWidth = 0.55f
        paint.style = Paint.Style.STROKE
        canvas.drawRect(left, top, left + widths[0], top + dateHeight, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 8f
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
        canvas.drawText("Дата", left + 5f, top + 11f, paint)

        var x = left + widths[0]
        for (i in 1 until widths.size) {
            paint.style = Paint.Style.STROKE
            canvas.drawRect(x, top, x + widths[i], top + dateHeight + headerHeight, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = 8f
            paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            val parts = headers[i].split("\n")
            val lineHeight = 9f
            val base = top + (dateHeight + headerHeight) / 2f + (parts.size - 1) * lineHeight / 2f + 2f
            parts.forEachIndexed { index, part ->
                val tx = x + (widths[i] - paint.measureText(part)) / 2f
                canvas.drawText(part, tx, base - index * lineHeight, paint)
            }
            x += widths[i]
        }
        val bodyTop = top + dateHeight + headerHeight
        for (i in 0 until 45) {
            val yy = bodyTop + i * rowHeight
            x = left
            var xx = x
            paint.style = Paint.Style.STROKE
            for (w in widths) {
                canvas.drawRect(xx, yy, xx + w, yy + rowHeight, paint)
                xx += w
            }
            paint.style = Paint.Style.FILL
            paint.textSize = 8f
            paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            val number = (i + 1).toString()
            canvas.drawText(number, left + (widths[0] - paint.measureText(number)) / 2f, yy + 11f, paint)
        }
        val noteY = bodyTop + 45 * rowHeight + 35f
        paint.textSize = 8f
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        canvas.drawText("Примечание: _________________________________________________", left, noteY, paint)
        drawHandoverSignature(canvas, paint)

        pdf.finishPage(page)
        val dir = File(cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, "StanControl-$date-original.pdf")
        file.outputStream().use { pdf.writeTo(it) }
        pdf.close()
        val uri = FileProvider.getUriForFile(this, "kg.stancontrol.fileprovider", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Поделиться PDF"))
    }

    private fun fmtWhole(value: Double): String = String.format(Locale.getDefault(), "%.0f", value)

    private fun drawHandoverSignature(canvas: android.graphics.Canvas, paint: Paint) {
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

    private fun today(): String = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
    private fun fmt(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
    private fun fmtWeight(value: Double): String = if (kotlin.math.abs(value - round(value)) < 0.000001) String.format(Locale.getDefault(), "%.0f", value) else fmt(value)
    private fun fmtCoefficient(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
    private fun readPacks(): List<Pack> {
        val a = JSONArray(prefs.getString("packs", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Pack(o.getDouble("w"), o.getInt("r"), o.getInt("n"), round(o.getDouble("w") / (o.getInt("n") * o.optDouble("length", 6.0)) * 10.0) / 10.0, o.optInt("width", 5), o.optDouble("length", 6.0)) }
    }
    private fun readDefects(): List<Defect> {
        val a = JSONArray(prefs.getString("defects", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Defect(o.getString("t"), o.getDouble("w")) }
    }
    private fun readPallets(): List<Pallet> {
        val a = JSONArray(prefs.getString("pallets", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Pallet(o.getString("t"), o.getDouble("g"), o.getDouble("a")) }
    }
}

package kg.stancontrol

import android.os.Bundle
import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Calendar
import java.util.Locale
import kotlin.math.round

data class Pack(val weight: Double, val rows: Int, val pieces: Int, val coefficient: Double, val width: Int = 5, val length: Double = 6.0)
data class Defect(val type: String, val weight: Double)
data class Pallet(val type: String, val gross: Double, val tare: Double) { val net: Double get() = gross - tare }

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("stancontrol", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { MainScreen() } }
    }

    @Composable
    private fun MainScreen() {
        var active by remember { mutableStateOf(prefs.getBoolean("active", false)) }
        var screen by remember { mutableStateOf("home") }
        var mode by remember { mutableStateOf("Пачка") }
        var profile by remember { mutableStateOf(prefs.getString("profile", "Уголок 50") ?: "Уголок 50") }
        val initialProfileKey = (prefs.getString("profile", "Уголок 50") ?: "Уголок 50").substringAfterLast(" ")
        val initialWidth = when (initialProfileKey) { "40" -> "7"; "30" -> "6"; else -> "5" }
        val initialRows = if (initialProfileKey == "40") "20" else "15"
        var width by remember { mutableStateOf(prefs.getString("profile_${initialProfileKey}_width", initialWidth) ?: initialWidth) }
        var length by remember { mutableStateOf(prefs.getString("profile_${initialProfileKey}_length", "6") ?: "6") }
        var rows by remember { mutableStateOf(prefs.getString("profile_${initialProfileKey}_rows", initialRows) ?: initialRows) }
        var date by remember { mutableStateOf(prefs.getString("date", today()) ?: today()) }
        var inputWeight by remember { mutableStateOf("") }
        var gross by remember { mutableStateOf("") }
        var tare by remember { mutableStateOf("") }
        var defectType by remember { mutableStateOf("Резка") }
        var palletType by remember { mutableStateOf("Прокат") }
        var showSettings by remember { mutableStateOf(false) }
        var showPacks by remember { mutableStateOf(true) }
        var showDefects by remember { mutableStateOf(false) }
        var showPallets by remember { mutableStateOf(false) }
        var editPack by remember { mutableStateOf(-1) }
        var editDefect by remember { mutableStateOf(-1) }
        var editPallet by remember { mutableStateOf(-1) }
        var editWeight by remember { mutableStateOf("") }
        var editRows by remember { mutableStateOf("") }
        var editGross by remember { mutableStateOf("") }
        var editTare by remember { mutableStateOf("") }
        var editType by remember { mutableStateOf("") }
        var confirmFinish by remember { mutableStateOf(false) }
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
            val w = inputWeight.replace(',', '.').toDoubleOrNull() ?: return
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
                if (screen != "home") TextButton(onClick = { screen = "work" }) { Text("Назад") }
            }
            when (screen) {
                "home" -> {
                    if (!active) {
                        Text("Начать день", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(date, { date = it }, label = { Text("Дата") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Text("Профиль")
                        listOf("Уголок 50", "Уголок 40", "Уголок 30").forEach { item ->
                            FilterChip(selected = profile == item, onClick = {
                                profile = item
                                width = prefs.getString("profile_${item.substringAfterLast(" ")}_width", when (item) { "Уголок 50" -> "5"; "Уголок 40" -> "7"; else -> "6" }) ?: "5"
                                rows = prefs.getString("profile_${item.substringAfterLast(" ")}_rows", if (item == "Уголок 40") "20" else "15") ?: "15"
                                length = prefs.getString("profile_${item.substringAfterLast(" ")}_length", "6") ?: "6"
                            }, label = { Text(item) })
                        }
                        OutlinedButton(onClick = { showSettings = true }, modifier = Modifier.fillMaxWidth()) { Text("Настроить профиль") }
                        Button(onClick = { active = true; screen = "work"; saveAll() }, modifier = Modifier.fillMaxWidth()) { Text("Начать смену") }
                    } else {
                        Text("Смена " + date, style = MaterialTheme.typography.titleLarge)
                        Button(onClick = { screen = "work" }, modifier = Modifier.fillMaxWidth()) { Text("Продолжить смену") }
                        OutlinedButton(onClick = { screen = "table" }, modifier = Modifier.fillMaxWidth()) { Text("Посмотреть таблицу") }
                    OutlinedButton(onClick = { sharePdf(date, profile, packs.toList(), defects.toList(), pallets.toList()) }, modifier = Modifier.fillMaxWidth()) { Text("Поделиться PDF") }
                        OutlinedButton(onClick = { screen = "archive" }, modifier = Modifier.fillMaxWidth()) { Text("Архив смен") }
                        OutlinedButton(onClick = { shareBackup() }, modifier = Modifier.fillMaxWidth()) { Text("Поделиться резервной копией") }
                        OutlinedButton(onClick = { confirmFinish = true }, modifier = Modifier.fillMaxWidth()) { Text("Завершить смену") }
                    }
                }
                "work" -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Пачка", "Брак", "Поддоны").forEach { item ->
                            FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item) })
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "Пачки ${packs.size} · ${fmt(packTotal)} кг   |   Брак ${defects.size} · ${fmt(defectTotal)} кг   |   ${fmt(rejectPercent)}%",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Card(Modifier.fillMaxWidth()) {
                        val lp = lastPack
                        Text(
                            if (lp == null) "Последняя пачка: пока нет записей"
                            else "Последняя: ${fmt(lp.weight)} кг  ·  К ${fmt(lp.coefficient)}  ·  Р ${lp.rows}",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    when (mode) {
                        "Пачка" -> {
                            Text("Пачка проката", fontWeight = FontWeight.SemiBold)
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
                                listOf("Резка", "Металлолом").forEach { item -> FilterChip(defectType == item, { defectType = item }, label = { Text(item) }) }
                            }
                            OutlinedTextField(inputWeight, { inputWeight = it }, label = { Text("Вес брака, кг") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Button(onClick = { addDefect() }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("ДОБАВИТЬ БРАК") }
                        }
                        else -> {
                            Text("Регистрация поддона", fontWeight = FontWeight.SemiBold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Прокат", "Резка").forEach { item -> FilterChip(palletType == item, { palletType = item }, label = { Text(item) }) }
                            }
                            OutlinedTextField(gross, { gross = it }, label = { Text("Брутто, кг") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), singleLine = true)
                            OutlinedTextField(tare, { tare = it }, label = { Text("Тара, кг") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Text("Нетто: " + fmt(((gross.replace(',', '.').toDoubleOrNull() ?: 0.0) - (tare.replace(',', '.').toDoubleOrNull() ?: 0.0)).coerceAtLeast(0.0)) + " кг", fontWeight = FontWeight.SemiBold)
                            Button(onClick = { addPallet() }, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("ДОБАВИТЬ ПОДДОН") }
                        }
                    }
                    OutlinedButton(onClick = { screen = "table" }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("ОБЩИЙ БЛАНК / ТАБЛИЦА") }
                    OutlinedButton(onClick = { screen = "home" }, modifier = Modifier.fillMaxWidth()) { Text("На главную") }
                }
                "table" -> {
                    Text("Таблица смены", style = MaterialTheme.typography.titleLarge)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            OutlinedButton(onClick = { showPacks = !showPacks }, modifier = Modifier.fillMaxWidth()) { Text("Пачки (" + packs.size + ")") }
                            if (showPacks) packs.forEachIndexed { index, p ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text((index + 1).toString() + ". " + fmt(p.weight) + " кг | " + fmt(p.coefficient) + " | " + p.pieces + " шт", modifier = Modifier.weight(1f))
                                    TextButton(onClick = { editPack = index; editWeight = p.weight.toString(); editRows = p.rows.toString() }) { Text("Изменить") }
                                }
                            }
                        }
                        item {
                            OutlinedButton(onClick = { showDefects = !showDefects }, modifier = Modifier.fillMaxWidth()) { Text("Брак (" + defects.size + ")") }
                            if (showDefects) defects.forEachIndexed { index, d ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(d.type + ": " + fmt(d.weight) + " кг")
                                    TextButton(onClick = { editDefect = index; editWeight = d.weight.toString(); editType = d.type }) { Text("Изменить") }
                                }
                            }
                            if (showDefects) Text("Итого брак: " + fmt(defectTotal) + " кг", fontWeight = FontWeight.Bold)
                        }
                        item {
                            OutlinedButton(onClick = { showPallets = !showPallets }, modifier = Modifier.fillMaxWidth()) { Text("Поддоны (" + pallets.size + ")") }
                            if (showPallets) pallets.forEachIndexed { index, p ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(p.type + ": " + fmt(p.gross) + " - " + fmt(p.tare) + " = " + fmt(p.net) + " кг", modifier = Modifier.weight(1f))
                                    TextButton(onClick = { editPallet = index; editGross = p.gross.toString(); editTare = p.tare.toString(); editType = p.type }) { Text("Изменить") }
                                }
                            }
                        }
                    }
                    Text("Пачки: " + fmt(packTotal) + " кг    Брак: " + fmt(defectTotal) + " кг")
                    OutlinedButton(onClick = { sharePdf(date, profile, packs.toList(), defects.toList(), pallets.toList()) }, modifier = Modifier.fillMaxWidth()) { Text("Поделиться PDF") }
                    OutlinedButton(onClick = { screen = "work" }, modifier = Modifier.fillMaxWidth()) { Text("Вернуться к работе") }
                }
                "archive" -> {
                    Text("Архив производства", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Button(onClick = {
                        historicalDate = today()
                        historicalProfile = "Уголок 50"
                        historicalWidth = "5"; historicalRows = "15"; historicalLength = "6"
                        historicalPacks.clear(); historicalDefects.clear(); historicalPallets.clear()
                        screen = "archive_new"
                    }, modifier = Modifier.fillMaxWidth()) { Text("Добавить прошлый день") }
                    Text("Архив производства", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                                        Text("Пачек: " + (shift.optJSONArray("packs")?.length() ?: 0) + " · " + fmt(shift.optDouble("packTotal")) + " кг")
                                        Text("Брак: " + fmt(shift.optDouble("defectTotal")) + " кг")
                                        Button(onClick = { archiveShiftJson = shift.toString(); screen = "archive_detail" }, modifier = Modifier.fillMaxWidth()) { Text("Открыть полный бланк") }
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
                        listOf("Уголок 50", "Уголок 40", "Уголок 30").forEach { p ->
                            FilterChip(selected = historicalProfile == p, onClick = {
                                historicalProfile = p
                                historicalWidth = when (p) { "Уголок 40" -> "7"; "Уголок 30" -> "6"; else -> "5" }
                                historicalRows = if (p == "Уголок 40") "20" else "15"
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
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("Прокат", "Резка").forEach { t -> FilterChip(historicalPalletType == t, { historicalPalletType = t }, label = { Text(t) }) }
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
                    Text("Добавлено: пачек ${historicalPacks.size} · ${fmt(historicalPacks.sumOf { it.weight })} кг | брак ${fmt(historicalDefects.sumOf { it.weight })} кг | поддонов ${historicalPallets.size}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        itemsIndexed(historicalPacks) { i, p -> Text("Пачка ${i + 1}: ${fmt(p.weight)} кг · К ${fmt(p.coefficient)} · ${p.pieces} шт") }
                        itemsIndexed(historicalDefects) { i, d -> Text("Брак ${i + 1}: ${d.type} · ${fmt(d.weight)} кг") }
                        itemsIndexed(historicalPallets) { i, p -> Text("Поддон ${i + 1}: ${p.type} · нетто ${fmt(p.net)} кг") }
                    }
                    Button(onClick = {
                        val saved = JSONArray(prefs.getString("shifts_archive", "[]"))
                        val exists = (0 until saved.length()).any { saved.getJSONObject(it).optString("date") == historicalDate }
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
                            Text("${i + 1}. ${fmt(item.optDouble("w"))} кг | К ${fmt(item.optDouble("k"))} | ${item.optInt("n")} шт | ${item.optInt("r")} рядов")
                        }
                        item { Text("БРАК", fontWeight = FontWeight.Bold) }
                        items(ad.length()) { i ->
                            val item = ad.getJSONObject(i)
                            Text(item.optString("t") + ": " + fmt(item.optDouble("w")) + " кг")
                        }
                        item { Text("ПОДДОНЫ", fontWeight = FontWeight.Bold) }
                        items(aa.length()) { i ->
                            val item = aa.getJSONObject(i)
                            val grossValue = item.optDouble("g")
                            val tareValue = item.optDouble("a")
                            Text(item.optString("t") + ": брутто " + fmt(grossValue) + " · тара " + fmt(tareValue) + " · нетто " + fmt(grossValue - tareValue) + " кг")
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

        if (showSettings) AlertDialog(onDismissRequest = { showSettings = false }, title = { Text("Настройки профиля") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(width, { width = it }, label = { Text("Ширина пачки, шт") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(length, { length = it }, label = { Text("Длина штуки, м") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(rows, { rows = it }, label = { Text("Высота по умолчанию, рядов") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }, confirmButton = { TextButton(onClick = { showSettings = false; saveAll() }) { Text("Сохранить") } })

        if (editPack >= 0) AlertDialog(onDismissRequest = { editPack = -1 }, title = { Text("Изменить пачку") }, text = {
            Column {
                OutlinedTextField(editWeight, { editWeight = it }, label = { Text("Вес, кг") })
                OutlinedTextField(editRows, { editRows = it }, label = { Text("Ряды") })
            }
        }, confirmButton = { TextButton(onClick = {
            val w = editWeight.replace(',', '.').toDoubleOrNull()
            val r = editRows.toIntOrNull()
            val widthValue = packs[editPack].width
            val lengthValue = packs[editPack].length
            if (w != null && r != null && w > 0 && r > 0) {
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
                val savedShifts = JSONArray(prefs.getString("shifts_archive", "[]"))
                val shift = JSONObject()
                    .put("date", date)
                    .put("profile", profile)
                    .put("packTotal", packTotal)
                    .put("defectTotal", defectTotal)
                    .put("packs", JSONArray().also { array -> packs.forEach { p -> array.put(JSONObject().put("w", p.weight).put("r", p.rows).put("n", p.pieces).put("k", p.coefficient).put("width", p.width).put("length", p.length)) } })
                    .put("defects", JSONArray().also { array -> defects.forEach { d -> array.put(JSONObject().put("t", d.type).put("w", d.weight)) } })
                    .put("pallets", JSONArray().also { array -> pallets.forEach { p -> array.put(JSONObject().put("t", p.type).put("g", p.gross).put("a", p.tare)) } })
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
            }) { Text("Завершить") } },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Отмена") } })
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

        text("StanControl", left, y + 16f, 17f, true)
        text("ОТЧЁТ ПРОИЗВОДСТВЕННОЙ СМЕНЫ", left + 112f, y + 15f, 11f, true)
        y += 26f
        text("Дата: $date", left, y + 11f, 9.5f, true)
        text("Профиль: $profile", left + 180f, y + 11f, 9.5f, true)
        y += 18f
        line(y, 0xFF777777.toInt(), 1f)
        y += 9f

        section("1. ГОТОВАЯ ПРОДУКЦИЯ · ПАЧКИ")
        val packXs = listOf(left + 10f, left + 95f, left + 220f, left + 365f)
        tableHeader(listOf("№", "КОЭФФИЦИЕНТ", "ВЕС, КГ", "КОЛ-ВО, ШТ"), packXs)
        packs.forEachIndexed { i, p ->
            tableRow(listOf((i + 1).toString(), fmt(p.coefficient), fmt(p.weight), p.pieces.toString()), packXs)
        }
        total("ИТОГО ГОТОВАЯ ПРОДУКЦИЯ", "${fmt(packs.sumOf { it.weight })} кг     ${packs.sumOf { it.pieces }} шт")

        val goodWeight = packs.sumOf { it.weight }
        val defectWeight = defects.sumOf { it.weight }
        val percent = if (goodWeight + defectWeight > 0) defectWeight * 100.0 / (goodWeight + defectWeight) else 0.0
        section("2. БРАК")
        val cutting = defects.filter { it.type == "Резка" }.sumOf { it.weight }
        val scrap = defects.filter { it.type == "Металлолом" }.sumOf { it.weight }
        val defectXs = listOf(left + 10f, left + 220f)
        tableHeader(listOf("КАТЕГОРИЯ", "ВЕС, КГ"), defectXs)
        tableRow(listOf("Резка", fmt(cutting)), defectXs)
        tableRow(listOf("Металлолом", fmt(scrap)), defectXs)
        total("ВСЕГО БРАК · ${fmt(percent)} % ОТ ОБЩЕГО ВЕСА", "${fmt(defectWeight)} кг")

        val palletXs = listOf(left + 10f, left + 125f, left + 260f, left + 395f)
        val rolling = pallets.filter { it.type == "Прокат" }
        val cuttingPallets = pallets.filter { it.type == "Резка" }
        section("3. ПОДДОНЫ ПРОКАТ")
        tableHeader(listOf("№", "БРУТТО, КГ", "ТАРА, КГ", "НЕТТО, КГ"), palletXs)
        rolling.forEachIndexed { i, p -> tableRow(listOf((i + 1).toString(), fmt(p.gross), fmt(p.tare), fmt(p.net)), palletXs) }
        total("ИТОГО ПОДДОНЫ ПРОКАТ", "${fmt(rolling.sumOf { it.net })} кг")

        section("4. ПОДДОНЫ РЕЗКА")
        tableHeader(listOf("№", "БРУТТО, КГ", "ТАРА, КГ", "НЕТТО, КГ"), palletXs)
        cuttingPallets.forEachIndexed { i, p -> tableRow(listOf((i + 1).toString(), fmt(p.gross), fmt(p.tare), fmt(p.net)), palletXs) }
        total("ИТОГО ПОДДОНЫ РЕЗКА", "${fmt(cuttingPallets.sumOf { it.net })} кг")
        total("ВСЕГО ПОДДОНЫ · НЕТТО", "${fmt(pallets.sumOf { it.net })} кг")

        // A compact footer and border make the page read as a finished form.
        y += 3f
        line(y, 0xFF777777.toInt(), 1f)
        text("StanControl  ·  Сменный производственный отчёт", left, y + 13f, 7.5f, false, 0xFF666666.toInt())

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

    private fun today(): String = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
    private fun fmt(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
    private fun readPacks(): List<Pack> {
        val a = JSONArray(prefs.getString("packs", "[]"))
        return (0 until a.length()).map { val o = a.getJSONObject(it); Pack(o.getDouble("w"), o.getInt("r"), o.getInt("n"), o.getDouble("k"), o.optInt("width", 5), o.optDouble("length", 6.0)) }
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

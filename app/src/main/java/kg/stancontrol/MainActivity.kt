package kg.stancontrol

import android.os.Bundle
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
                        OutlinedButton(onClick = { confirmFinish = true }, modifier = Modifier.fillMaxWidth()) { Text("Завершить смену") }
                    }
                }
                "work" -> {
                    Text("Рабочий бланк", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Пачка", "Брак", "Поддоны").forEach { item ->
                            FilterChip(selected = mode == item, onClick = { mode = item }, label = { Text(item) })
                        }
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
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("ИТОГИ СМЕНЫ", fontWeight = FontWeight.Bold)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column { Text("Чистые пачки", style = MaterialTheme.typography.bodySmall); Text(fmt(packTotal) + " кг", fontWeight = FontWeight.Bold) }
                                Column { Text("Брак", style = MaterialTheme.typography.bodySmall); Text(fmt(defectTotal) + " кг", fontWeight = FontWeight.Bold) }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Пачек: ${packs.size} | Записей брака: ${defects.size}")
                                Text("Брак: ${fmt(rejectPercent)} %", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (mode == "Пачка" && lastPack != null) Text("Последняя пачка: " + fmt(lastPack.weight) + " кг | коэффициент " + fmt(lastPack.coefficient))
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
                    Text("Архив смен", style = MaterialTheme.typography.titleLarge)
                    val savedShifts = JSONArray(prefs.getString("shifts_archive", "[]"))
                    if (savedShifts.length() == 0) {
                        Text(prefs.getString("archive", "Завершённых смен пока нет") ?: "Завершённых смен пока нет")
                    } else {
                        for (i in savedShifts.length() - 1 downTo 0) {
                            val shift = savedShifts.getJSONObject(i)
                            Text(
                                shift.optString("date") + " | " + shift.optString("profile") +
                                    " | пачки " + fmt(shift.optDouble("packTotal")) + " кг" +
                                    " | брак " + fmt(shift.optDouble("defectTotal")) + " кг"
                            )
                        }
                    }
                    Button(onClick = { screen = "home" }) { Text("Назад") }
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

    private fun sharePdf(date: String, profile: String, packs: List<Pack>, defects: List<Defect>, pallets: List<Pallet>) {
        val lines = mutableListOf<String>()
        lines += "StanControl | ОТЧЁТ СМЕНЫ"
        lines += "Дата: $date     Профиль: $profile"
        lines += "ПАЧКИ: № | коэффициент | вес, кг | штук"
        packs.forEachIndexed { i, p -> lines += "${i + 1} | ${fmt(p.coefficient)} | ${fmt(p.weight)} | ${p.pieces}" }
        lines += "ИТОГО ПАЧКИ: ${fmt(packs.sumOf { it.weight })} кг | ${packs.sumOf { it.pieces }} шт"
        lines += "БРАК"
        lines += "Резка: ${fmt(defects.filter { it.type == "Резка" }.sumOf { it.weight })} кг"
        lines += "Металлолом: ${fmt(defects.filter { it.type == "Металлолом" }.sumOf { it.weight })} кг"
        val goodWeight = packs.sumOf { it.weight }
        val defectWeight = defects.sumOf { it.weight }
        val percent = if (goodWeight + defectWeight > 0) defectWeight * 100.0 / (goodWeight + defectWeight) else 0.0
        lines += "ВСЕГО БРАК: ${fmt(defectWeight)} кг | ПРОЦЕНТ БРАКА ОТ ПАЧЕК + БРАКА: ${fmt(percent)} %"
        lines += "ПОДДОНЫ ПРОКАТ: № | брутто | тара | нетто, кг"
        pallets.filter { it.type == "Прокат" }.forEachIndexed { i, p -> lines += "${i + 1} | ${fmt(p.gross)} | ${fmt(p.tare)} | ${fmt(p.net)}" }
        lines += "ИТОГО ПОДДОНЫ ПРОКАТ: ${fmt(pallets.filter { it.type == "Прокат" }.sumOf { it.net })} кг"
        lines += "ПОДДОНЫ РЕЗКА: № | брутто | тара | нетто, кг"
        pallets.filter { it.type == "Резка" }.forEachIndexed { i, p -> lines += "${i + 1} | ${fmt(p.gross)} | ${fmt(p.tare)} | ${fmt(p.net)}" }
        lines += "ИТОГО ПОДДОНЫ РЕЗКА: ${fmt(pallets.filter { it.type == "Резка" }.sumOf { it.net })} кг"
        lines += "ВСЕГО ПОДДОНЫ НЕТТО: ${fmt(pallets.sumOf { it.net })} кг"

        val pdf = PdfDocument()
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val canvas = page.canvas
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val availableHeight = 770f
        val rowHeight = (availableHeight / lines.size).coerceAtMost(18f)
        val fontSize = (rowHeight * 0.72f).coerceIn(5.5f, 13f)
        paint.textSize = fontSize
        var y = 36f
        lines.forEachIndexed { index, line ->
            paint.isFakeBoldText = index == 0 || line.startsWith("ПАЧКИ") || line.startsWith("ИТОГО") || line.startsWith("БРАК") || line.startsWith("ПОДДОНЫ") || line.startsWith("ВСЕГО")
            canvas.drawText(line.take(110), 28f, y, paint)
            y += rowHeight
        }
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

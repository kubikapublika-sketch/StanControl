package kg.stancontrol

import android.os.Bundle
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

data class Pack(val number: Int, val weight: Double, val rows: Int, val rods: Int, val coefficient: Double)
data class Defect(val kind: String, val weight: Double)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("stancontrol", MODE_PRIVATE)
        setContent {
            MaterialTheme {
                var active by remember { mutableStateOf(prefs.getBoolean("active", false)) }
                var angle by remember { mutableStateOf(prefs.getString("angle", "50") ?: "50") }
                var rowsText by remember { mutableStateOf(prefs.getString("rows", "15") ?: "15") }
                var weightText by remember { mutableStateOf("") }
                var defectText by remember { mutableStateOf("") }
                var defectKind by remember { mutableStateOf("Металлолом") }
                var showArchive by remember { mutableStateOf(false) }
                val packs = remember { mutableStateListOf<Pack>().apply {
                    val a = JSONArray(prefs.getString("packs", "[]"))
                    for (i in 0 until a.length()) { val o=a.getJSONObject(i); add(Pack(o.getInt("number"),o.getDouble("weight"),o.getInt("rows"),o.getInt("rods"),o.getDouble("coefficient"))) }
                } }
                val defects = remember { mutableStateListOf<Defect>().apply {
                    val a = JSONArray(prefs.getString("defects", "[]"))
                    for (i in 0 until a.length()) { val o=a.getJSONObject(i); add(Defect(o.getString("kind"),o.getDouble("weight"))) }
                } }
                fun save() {
                    val pa=JSONArray(); packs.forEach { pa.put(JSONObject().put("number",it.number).put("weight",it.weight).put("rows",it.rows).put("rods",it.rods).put("coefficient",it.coefficient)) }
                    val da=JSONArray(); defects.forEach { da.put(JSONObject().put("kind",it.kind).put("weight",it.weight)) }
                    prefs.edit().putString("packs",pa.toString()).putString("defects",da.toString()).putString("angle",angle).putString("rows",rowsText).putBoolean("active",active).apply()
                }
                val total = packs.sumOf { it.weight }
                val defectTotal = defects.sumOf { it.weight }
                Surface(Modifier.fillMaxSize(), color=MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("StanControl", style=MaterialTheme.typography.headlineMedium, fontWeight=FontWeight.Bold)
                            TextButton(onClick={showArchive=!showArchive}) { Text(if(showArchive) "Назад" else "Архив") }
                        }
                        if (showArchive) {
                            Text("Архив смен", style=MaterialTheme.typography.titleLarge)
                            Text("Текущая запись хранится на этом устройстве. Экспорт PDF добавим следующим этапом.")
                            Text("Пачек: ${packs.size} · Вес: ${"%.1f".format(total)} кг")
                            Text("Брак: ${"%.1f".format(defectTotal)} кг")
                        } else if (!active) {
                            Text("Новая смена", style=MaterialTheme.typography.titleLarge)
                            Text("Выбери профиль проката")
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                listOf("50","40","30").forEach { v -> FilterChip(selected=angle==v,onClick={angle=v},label={Text("Уголок $v")}) }
                            }
                            OutlinedTextField(rowsText,{rowsText=it},label={Text("Рядов в пачке")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true)
                            Button(onClick={ active=true; save() },modifier=Modifier.fillMaxWidth()) { Text("Начать сессию") }
                        } else {
                            Text("Уголок $angle · текущая сессия", style=MaterialTheme.typography.titleLarge)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                                Stat("Пачек",packs.size.toString())
                                Stat("Общий вес","%.1f кг".format(total))
                                Stat("Брак","%.1f кг".format(defectTotal))
                            }
                            val lastPack = packs.lastOrNull()
                            Card(modifier=Modifier.fillMaxWidth(), colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
                                Column(Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                    Text("ПОСЛЕДНЯЯ ДОБАВЛЕННАЯ ПАЧКА", style=MaterialTheme.typography.labelMedium, fontWeight=FontWeight.Bold)
                                    if (lastPack == null) {
                                        Text("Пока пачек нет", style=MaterialTheme.typography.titleMedium)
                                    } else {
                                        Text("Пачка №${lastPack.number}", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                                            Stat("Вес", "${"%.1f".format(lastPack.weight)} кг")
                                            Stat("Коэффициент", lastPack.coefficient.toString())
                                            Stat("Ряды", lastPack.rows.toString())
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(rowsText,{rowsText=it},label={Text("Рядов для следующей пачки")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.fillMaxWidth())
                            OutlinedTextField(weightText,{weightText=it},label={Text("Вес пачки, кг")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())
                            Button(onClick={
                                val w=weightText.replace(',','.').toDoubleOrNull()
                                val r=rowsText.toIntOrNull()
                                if(w!=null && w>0 && r!=null && r>0) {
                                    val rodsPerRow=when(angle){"50"->9;"40"->13;else->12}
                                    val correction=when(angle){"50"-> -4;"40"-> -6;else->0}
                                    val rods=(r*rodsPerRow+correction).coerceAtLeast(1)
                                    val coef= w/(rods*6.0)
                                    packs.add(Pack(packs.size+1,w,r,rods, kotlin.math.floor(coef*10+0.5)/10))
                                    weightText=""; save()
                                }
                            },modifier=Modifier.fillMaxWidth()) { Text("＋ ДОБАВИТЬ ПАЧКУ") }
                            Divider()
                            Text("Брак",style=MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                listOf("Металлолом","В резку").forEach { FilterChip(selected=defectKind==it,onClick={defectKind=it},label={Text(it)}) }
                            }
                            OutlinedTextField(defectText,{defectText=it},label={Text("Вес брака, кг")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())
                            OutlinedButton(onClick={
                                val w=defectText.replace(',','.').toDoubleOrNull()
                                if(w!=null && w>0){defects.add(Defect(defectKind,w));defectText="";save()}
                            },modifier=Modifier.fillMaxWidth()) { Text("Добавить брак") }
                            Text("Последние пачки",style=MaterialTheme.typography.titleMedium)
                            LazyColumn(Modifier.weight(1f)) {
                                itemsIndexed(packs.reversed().take(8)) { _,p -> Text("#${p.number}   ${p.weight} кг   ${p.rows} рядов   ${p.rods} прутков   коэф. ${p.coefficient}",modifier=Modifier.padding(vertical=5.dp)) }
                            }
                            OutlinedButton(onClick={active=false;save()},modifier=Modifier.fillMaxWidth()) { Text("Завершить сессию") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Stat(label:String,value:String) {
    Column { Text(label,style=MaterialTheme.typography.labelMedium); Text(value,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold) }
}

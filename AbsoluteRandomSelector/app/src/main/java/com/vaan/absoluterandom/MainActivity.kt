package com.vaan.absoluterandom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DrawMode(val title: String) { LETTERS("Letters"), NUMBERS("Numbers"), BOTH("Both") }
data class DrawEntry(val id: Long, val result: String, val mode: String, val pool: Long, val time: Long)
data class PoolInfo(val size: Long?, val min: Long?, val numberCount: Long, val error: String?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RandomSelector() }
    }
}

@Composable
private fun RandomSelector() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("absolute_random", Context.MODE_PRIVATE) }
    val rng = remember { EntropyEngine() }
    val haptics = LocalHapticFeedback.current
    val custom = remember { mutableStateListOf<String>().apply { addAll(loadStrings(prefs.getString("custom", null))) } }
    val history = remember { mutableStateListOf<DrawEntry>().apply { addAll(loadHistory(prefs.getString("history", null))) } }

    var mode by remember { mutableStateOf(runCatching { DrawMode.valueOf(prefs.getString("mode", "BOTH")!!) }.getOrDefault(DrawMode.BOTH)) }
    var includeCustom by remember { mutableStateOf(prefs.getBoolean("includeCustom", true)) }
    var minText by remember { mutableStateOf(prefs.getString("min", "0") ?: "0") }
    var maxText by remember { mutableStateOf(prefs.getString("max", "9") ?: "9") }
    var customText by remember { mutableStateOf("") }
    var latest by remember { mutableStateOf(prefs.getString("latest", "?") ?: "?") }
    var error by remember { mutableStateOf<String?>(null) }

    val scheme = darkColorScheme(
        primary = Color(0xFF9FE7FF), secondary = Color(0xFFC3B4FF), tertiary = Color(0xFFB9F6CA),
        background = Color(0xFF070A10), surface = Color(0xFF111722), onPrimary = Color(0xFF041018),
        onBackground = Color(0xFFF4F7FB), onSurface = Color(0xFFF4F7FB)
    )

    MaterialTheme(colorScheme = scheme) {
        LazyColumn(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF070A10), Color(0xFF0A1020), Color(0xFF070A10)))).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(12.dp)) }
            item {
                Text("ABSOLUTE RANDOM SELECTOR", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text("Cryptographic entropy • SHA-512 stir • exact unbiased mapping", color = Color(0xFF9AA8B8), fontSize = 12.sp)
            }
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Color(0xFF101A2B)) {
                    Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("SELECTED", color = Color(0xFF8FA2B9), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(latest, color = Color.White, fontSize = if (latest.length <= 3) 76.sp else 42.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
                        Text("WITH REPLACEMENT • selected item immediately returns to the pool", color = Color(0xFFB9F6CA), fontSize = 12.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            item {
                Text("Choose pool", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DrawMode.entries.forEach { choice ->
                        FilterChip(mode == choice, {
                            mode = choice
                            prefs.edit().putString("mode", choice.name).apply()
                        }, { Text(choice.title) }, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (mode != DrawMode.LETTERS) item {
                Text("Number range", fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("Min", minText, Modifier.weight(1f)) {
                        minText = sanitizeNumber(it); prefs.edit().putString("min", minText).apply()
                    }
                    NumberField("Max", maxText, Modifier.weight(1f)) {
                        maxText = sanitizeNumber(it); prefs.edit().putString("max", maxText).apply()
                    }
                }
            }
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color(0xFF0D1420)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(includeCustom, {
                                includeCustom = it; prefs.edit().putBoolean("includeCustom", it).apply()
                            })
                            Column {
                                Text("Include my custom items", fontWeight = FontWeight.Bold)
                                Text("${custom.size} saved • duplicate entries intentionally add extra weight", color = Color(0xFF95A3B6), fontSize = 12.sp)
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(customText, { customText = it }, Modifier.weight(1f), label = { Text("Add anything") }, supportingText = { Text("Comma or new line separates items") })
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                val add = customText.split(',', '\n').map { it.trim().take(80) }.filter { it.isNotEmpty() }
                                if (add.isNotEmpty()) { custom.addAll(add); saveStrings(prefs, custom); customText = "" }
                            }) { Text("ADD") }
                        }
                        if (custom.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                itemsIndexed(custom, key = { index, item -> "$index:$item" }) { index, item ->
                                    OutlinedButton(onClick = { custom.removeAt(index); saveStrings(prefs, custom) }) { Text("×  $item", maxLines = 1) }
                                }
                            }
                        }
                    }
                }
            }
            item {
                val preview = poolInfo(mode, minText, maxText, includeCustom, custom)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Pool size: ${preview.size ?: "—"}", color = Color(0xFFA7B7CA), fontSize = 13.sp)
                    Text("Draws saved: ${history.size}", color = Color(0xFFA7B7CA), fontSize = 13.sp)
                }
                Button(
                    onClick = {
                        val tap = SystemClock.elapsedRealtimeNanos()
                        val pool = poolInfo(mode, minText, maxText, includeCustom, custom)
                        if (pool.error != null || pool.size == null || pool.size <= 0) error = pool.error ?: "Pool is empty."
                        else {
                            error = null
                            val fingerprint = "${mode.name}|$minText|$maxText|$includeCustom|${custom.joinToString("\u001f")}" 
                            val index = rng.pick(pool.size, tap, fingerprint)
                            val result = resolve(index, mode, pool.min, pool.numberCount, includeCustom, custom)
                            latest = result
                            val entry = DrawEntry(System.nanoTime(), result, mode.title, pool.size, System.currentTimeMillis())
                            history.add(0, entry)
                            while (history.size > 500) history.removeAt(history.lastIndex)
                            saveHistory(prefs, history)
                            prefs.edit().putString("latest", result).apply()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9FE7FF))
                ) { Text("DRAW RANDOM ITEM", fontWeight = FontWeight.Black, fontSize = 18.sp) }
                error?.let { Text(it, color = Color(0xFFFF9AA2), fontSize = 13.sp) }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("HISTORY", fontWeight = FontWeight.Black, fontSize = 18.sp); Text("Newest first • repeats are valid", color = Color(0xFF94A4B7), fontSize = 12.sp) }
                    Row {
                        TextButton(onClick = {
                            val text = history.joinToString("\n") { "${formatTime(it.time)}  ${it.result}  [${it.mode}, pool ${it.pool}]" }
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Random history", text))
                            Toast.makeText(context, "History copied", Toast.LENGTH_SHORT).show()
                        }) { Text("COPY") }
                        TextButton(onClick = { history.clear(); saveHistory(prefs, history) }) { Text("CLEAR") }
                    }
                }
            }
            if (history.isEmpty()) item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color(0xFF0D1420)) { Text("Your selections will stack here.", Modifier.padding(18.dp), color = Color(0xFF9AA8B8)) }
            } else items(history, key = { it.id }) { entry ->
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Color(0xFF0D1420)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF17243A), modifier = Modifier.size(52.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text(entry.result.take(4), fontWeight = FontWeight.Black, fontSize = if (entry.result.length <= 2) 24.sp else 14.sp, textAlign = TextAlign.Center) }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column { Text(entry.result, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text("${entry.mode} • pool ${entry.pool} • ${formatTime(entry.time)}", color = Color(0xFF91A0B2), fontSize = 12.sp) }
                    }
                }
            }
            item { Text("Every draw is independent. Nothing is removed after selection.", Modifier.fillMaxWidth().padding(vertical = 24.dp), color = Color(0xFF7F8EA1), fontSize = 11.sp, textAlign = TextAlign.Center) }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, change: (String) -> Unit) =
    OutlinedTextField(value, change, modifier, label = { Text(label) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))

private fun sanitizeNumber(s: String) = s.filterIndexed { i, c -> c.isDigit() || (c == '-' && i == 0) }.take(19)

private fun poolInfo(mode: DrawMode, minText: String, maxText: String, includeCustom: Boolean, custom: List<String>): PoolInfo {
    val letters = if (mode != DrawMode.NUMBERS) 26L else 0L
    var min: Long? = null
    var count = 0L
    if (mode != DrawMode.LETTERS) {
        min = minText.toLongOrNull(); val max = maxText.toLongOrNull()
        if (min == null || max == null) return PoolInfo(null, null, 0, "Enter valid minimum and maximum numbers.")
        if (max < min) return PoolInfo(null, min, 0, "Maximum must be at least the minimum.")
        val distance = max - min
        if (distance < 0 || distance == Long.MAX_VALUE) return PoolInfo(null, min, 0, "That number range is too large.")
        count = distance + 1
    }
    val extras = if (includeCustom) custom.size.toLong() else 0L
    if (letters > Long.MAX_VALUE - count || letters + count > Long.MAX_VALUE - extras) return PoolInfo(null, min, count, "Pool is too large.")
    val total = letters + count + extras
    return PoolInfo(total, min, count, if (total == 0L) "Pool is empty." else null)
}

private fun resolve(raw: Long, mode: DrawMode, min: Long?, count: Long, includeCustom: Boolean, custom: List<String>): String {
    var i = raw
    if (mode != DrawMode.NUMBERS) { if (i < 26) return ('A'.code + i.toInt()).toChar().toString(); i -= 26 }
    if (mode != DrawMode.LETTERS) { if (i < count) return ((min ?: 0) + i).toString(); i -= count }
    if (includeCustom && custom.isNotEmpty()) return custom[i.toInt()]
    return "?"
}

private fun saveStrings(p: android.content.SharedPreferences, values: List<String>) {
    val a = JSONArray(); values.forEach(a::put); p.edit().putString("custom", a.toString()).apply()
}
private fun loadStrings(raw: String?): List<String> = runCatching { if (raw.isNullOrBlank()) emptyList() else JSONArray(raw).let { a -> List(a.length()) { a.optString(it) }.filter(String::isNotBlank) } }.getOrDefault(emptyList())
private fun saveHistory(p: android.content.SharedPreferences, values: List<DrawEntry>) {
    val a = JSONArray(); values.take(500).forEach { e -> a.put(JSONObject().apply { put("id", e.id); put("result", e.result); put("mode", e.mode); put("pool", e.pool); put("time", e.time) }) }; p.edit().putString("history", a.toString()).apply()
}
private fun loadHistory(raw: String?): List<DrawEntry> = runCatching {
    if (raw.isNullOrBlank()) emptyList() else JSONArray(raw).let { a -> List(a.length()) { i -> a.getJSONObject(i).let { o -> DrawEntry(o.optLong("id", i.toLong()), o.optString("result", "?"), o.optString("mode", "?"), o.optLong("pool", 0), o.optLong("time", 0)) } }.take(500) }
}.getOrDefault(emptyList())
private fun formatTime(t: Long) = SimpleDateFormat("MMM d, h:mm:ss a", Locale.getDefault()).format(Date(t))

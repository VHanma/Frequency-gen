package com.vaan.absoluterandom

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DarkColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

private enum class DrawMode(val label: String) {
    LETTERS("Letters"),
    NUMBERS("Numbers"),
    BOTH("Both")
}

private data class HistoryEntry(
    val id: Long,
    val result: String,
    val mode: String,
    val poolSize: Long,
    val timestamp: Long
)

private class EntropyEngine {
    private val secureRandom: SecureRandom = try {
        SecureRandom.getInstanceStrong()
    } catch (_: Exception) {
        SecureRandom()
    }
    private val counter = AtomicLong(0L)

    init {
        val warmup = ByteArray(64)
        secureRandom.nextBytes(warmup)
        secureRandom.setSeed(MessageDigest.getInstance("SHA-512").digest(warmup))
    }

    fun pickIndex(bound: Long, tapNanos: Long, poolFingerprint: String): Long {
        require(bound > 0L)

        val osEntropy = ByteArray(64)
        secureRandom.nextBytes(osEntropy)

        val timing = ByteBuffer.allocate(40)
            .putLong(System.nanoTime())
            .putLong(SystemClock.elapsedRealtimeNanos())
            .putLong(tapNanos)
            .putLong(counter.incrementAndGet())
            .putLong(Runtime.getRuntime().freeMemory())
            .array()

        val digest = MessageDigest.getInstance("SHA-512")
        digest.update(osEntropy)
        digest.update(timing)
        digest.update(poolFingerprint.toByteArray(StandardCharsets.UTF_8))
        secureRandom.setSeed(digest.digest())

        return unbiasedLong(bound)
    }

    private fun unbiasedLong(bound: Long): Long {
        if (bound == 1L) return 0L
        val max = Long.MAX_VALUE
        val limit = max - (max % bound)
        while (true) {
            val candidate = secureRandom.nextLong() and Long.MAX_VALUE
            if (candidate < limit) return candidate % bound
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AbsoluteRandomApp()
        }
    }
}

@Composable
private fun AbsoluteRandomApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("absolute_random", Context.MODE_PRIVATE) }
    val engine = remember { EntropyEngine() }
    val haptics = LocalHapticFeedback.current

    val customItems = remember {
        mutableStateListOf<String>().apply { addAll(loadCustomItems(prefs.getString("custom_items", null))) }
    }
    val history = remember {
        mutableStateListOf<HistoryEntry>().apply { addAll(loadHistory(prefs.getString("history", null))) }
    }

    var mode by remember {
        mutableStateOf(runCatching { DrawMode.valueOf(prefs.getString("mode", DrawMode.BOTH.name) ?: DrawMode.BOTH.name) }.getOrDefault(DrawMode.BOTH))
    }
    var includeCustom by remember { mutableStateOf(prefs.getBoolean("include_custom", true)) }
    var minNumberText by remember { mutableStateOf(prefs.getString("min_number", "0") ?: "0") }
    var maxNumberText by remember { mutableStateOf(prefs.getString("max_number", "9") ?: "9") }
    var customInput by remember { mutableStateOf("") }
    var latestResult by remember { mutableStateOf(prefs.getString("latest_result", "?") ?: "?") }
    var errorText by remember { mutableStateOf<String?>(null) }

    val colors: DarkColorScheme = darkColorScheme(
        primary = Color(0xFF9FE7FF),
        secondary = Color(0xFFC3B4FF),
        tertiary = Color(0xFFB9F6CA),
        background = Color(0xFF070A10),
        surface = Color(0xFF111722),
        onPrimary = Color(0xFF041018),
        onBackground = Color(0xFFF4F7FB),
        onSurface = Color(0xFFF4F7FB)
    )

    MaterialTheme(colorScheme = colors) {
        val gradient = Brush.verticalGradient(
            listOf(Color(0xFF070A10), Color(0xFF0A1020), Color(0xFF070A10))
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(12.dp)) }
            item {
                Text(
                    text = "ABSOLUTE RANDOM SELECTOR",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp
                )
                Text(
                    text = "OS cryptographic entropy • SHA-512 stir • unbiased rejection sampling",
                    color = Color(0xFF9AA8B8),
                    fontSize = 12.sp
                )
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF101A2B)
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("SELECTED", color = Color(0xFF8FA2B9), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = latestResult,
                            color = Color.White,
                            fontSize = if (latestResult.length <= 3) 76.sp else 42.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "WITH REPLACEMENT •  the result goes straight back into the pool",
                            color = Color(0xFFB9F6CA),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            item {
                Text("Choose pool", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DrawMode.entries.forEach { option ->
                        FilterChip(
                            selected = mode == option,
                            onClick = {
                                mode = option
                                prefs.edit().putString("mode", option.name).apply()
                            },
                            label = { Text(option.label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (mode != DrawMode.LETTERS) {
                item {
                    Text("Number range", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = minNumberText,
                            onValueChange = {
                                minNumberText = it.filter { ch -> ch.isDigit() || ch == '-' }.take(20)
                              prefs.edit().putString("min_number", minNumberText).apply()
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("Min") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = maxNumberText,
                            onValueChange = {
                                maxNumberText = it.filter { ch -> ch.isDigit() || ch == '-' }.take(20)
                              prefs.edit().putString("max_number", maxNumberText).apply()
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text("Max") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF0D1420)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = includeCustom,
                                onCheckedChange = {
                                    includeCustom = it
                                    prefs.edit().putBoolean("include_custom", it).apply()
                                }
                            )
                            Column {
                                Text("Include my custom items", fontWeight = FontWeight.Bold)
                                Text("${customItems.size} saved", color = Color(0xFF95A3B6), fontSize = 12.sp)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = customInput,
                              onValueChange = { customInput = it },
                                modifier = Modifier.weight(1f),
                              label = { Text("Add words, symbols, anything") },
                                supportingText = { Text("Separate several with commas or new lines") }
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    val incoming = customInput
                                        .split(',', '\n')
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                      .map { it.take(80) }
                                    if (incoming.isNotEmpty()) {
                                        customItems.addAll(incoming)
                                        saveCustomItems(prefs, customItems)
                                    customInput = ""
                                    }
                                }
                            ) { Text("ADD") }
                        }

                        if (customItems.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(customItems, key = { item -> "${item}_${customItems.indexOf(item)}" }) { item ->
                                    OutlinedButton(
                                          onClick = {
                                            customItems.remove(item)
                                            saveCustomItems(prefs, customItems)
                                        }
                                    ) {
                                        Text("×  $item", maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                val poolPreview = calculatePool(mode, minNumberText, maxNumberText, includeCustom, customItems)
                val poolSizeText = poolPreview.poolSize?.toString() ?: —"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Pool size: $poolSizeText", color = Color(0xFFA7B7CB), fontSize = 13.sp)
                    Text("Draws saved: ${history.size}", color = Color(0xFFA7B7CB), fontSize = 13.sp)
                }

                Button(
                    onClick = {
                        val nowTap = SystemClock.elapsedRealtimeNanos()
                        val pool = calculatePool(mode, minNumberText, maxNumberText, includeCustom, customItems)
                        if (pool.error != null || pool.poolSize == null || pool.poolSize <= 0L) {
                            errorText = pool.error ?: "The pool is empty."
                        } else {
                            errorText = null
                            val fingerprint = buildString {
                                append(mode.name).append('|')
                                append(minNumberText).append'|').append(maxNumberText).append('|')
                                append(includeCustom).append'|')
                                    customItems.forEach { append(it).append('\u001F') }
                            }
                            val index = engine.pickIndex(pool.poolSize, nowTap, fingerprint)
                            val result = resolveIndex(index, mode, pool.minNumber, pool.numberCount, includeCustom, customItems)
                            latestResult = result
                            val entry = HistoryEntry(
                                id = System.currentTimeMillis() * 1000L + (history.size % 1000),
                              result = result,
                                mode = mode.label,
                                poolSize = pool.poolSize,
                              timestamp = System.currentTimeMillis()
                            )
                            history.add(0, entry)
                            while (history.size > 500) history.removeLast()
                            saveHistory(prefs, history)
                            prefs.edit().putString("latest_result", result).apply()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9FE7FF))
                ) {
                    Text("DRAW RANDOM ITEM", fontWeight = FontWeight.Black, fontSize = 18.sp)
                }

                errorText?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFFF9AA2), fontSize = 13.sp)
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("HISTORY", fontWeight = FontWeight.Black, fontSize = 18.sp)
                        Text("Newest first • repeats are expected", color = Color(0xFF94A4B7), fontSize = 12.sp)
                    }
                    Row {
                        TextButton(onClick = {
                            val text = history.joinToString("\n") { entry ->
                                "${formatTime(entry.timestamp)}  ${entry.result}  [${entry.mode}, pool ${entry.poolSize}]"
                            }
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Random history", text))
                            Toast.makeText(context, "History copied", Toast.LENGTH_SHORT).show()
                        }) { Text("COPY") }
                        TextButton(onClick = {
                            history.clear()
                            saveHistory(prefs, history)
                        }) { Text("CLEAR") }
                    }
                }
            }

            if (history.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF0D1420)
                    ) {
                        Text(
                            "Your selected letters, numbers, and custom items will stack here.",
                            modifier = Modifier.padding(18.dp),
                            color = Color(0xFF9AA8B8)
                        )
                    }
                }
            } else {
                items(history, key = { it.id }) { entry ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF0D1420)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(52.dp)
                                .background(Color(0xFF17243A), RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    entry.result.take(4),
                                    fontWeight = FontWeight.Black,
                                    fontSize = if (entry.result.length <= 2) 24.sp else 15.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.result, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Text(
                                    "${entry.mode} • pool ${entry.poolSize} • ${formatTime(entry.timestamp)}",
                                    color = Color(0xFF91A0B2),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Each draw is independent. Nothing is removed from the pool after selection.",
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF7F8EA1),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

private data class PoolInfo(
    val poolSize: Long?,
    val minNumber: Long?,
    val numberCount: Long,
    val error: String?
)

private fun calculatePool(
    mode: DrawMode,
    minText: String,
    maxText: String,
    includeCustom: Boolean,
    customItems: List<String>
): PoolInfo {
    val lettersCount = if (mode == DrawMode.LETTERS || mode == DrawMode.BOTH) 26L else 0L
    var minNumber: Long? = null
    var numberCount = 0L

    if (mode == DrawMode.NUMBERS || mode == DrawMode.BOTH) {
        val min = minText.toLongOrNull()
        val max = maxText.toLongOrNull()
        if (min == null || max == null) {
            return PoolInfo(null, null, 0L, "Enter a valid minimum and maximum number.")
        }
        if (max < min) {
            return PoolInfo(null, null, 0L, "Maximum must be greater than or equal to minimum.")
        }
        val distance = max - min
        if (distance < 0L || distance >= Long.MAX_VALUE - 1L) {
            return PoolInfo(null, null, 0L, "That numeric range is too large for one pool.")
        }
        minNumber = min
        numberCount = distance + 1L
    }

    val customCount = if (includeCustom) customItems.size.toLong() else 0L
    if (lettersCount > Long.MAX_VALUE - numberCount || lettersCount + numberCount > Long.MAX_VALUE - customCount) {
        return PoolInfo(null, minNumber, numberCount, "The pool is too large.")
    }

    val total = lettersCount + numberCount + customCount
    return PoolInfo(total, minNumber, numberCount, if (total == 0L) "The pool is empty." else null)
}

private fun resolveIndex(
    rawIndex: Long,
    mode: DrawMode,
    minNumber: Long?,
    numberCount: Long,
    includeCustom: Boolean,
    customItems: List<String>
): String {
    var index = rawIndex

    if (mode == DrawMode.LETTERS || mode == DrawMode.BOTH) {
        if (index < 26L) return ('A'.code + index.toInt()).toChar().toString()
        index -= 26L
    }

    if (mode == DrawMode.NUMBERS || mode == DrawMode.BOTH) {
        if (index < numberCount) return ((minNumber ?: 0L) + index).toString()
        index -= numberCount
    }

    if (includeCustom && customItems.isNotEmpty()) {
        return customItems[index.toInt().coerceIn(0, customItems.lastIndex)]
    }

    return "?"
}

private fun saveCustomItems(prefs: android.content.SharedPreferences, items: List<String>) {
    val array = JSONArray()
    items.forEach { array.put(it) }
    prefs.edit().putString("custom_items", array.toString()).apply()
}

private fun loadCustomItems(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) add(array.optString(i))
        }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
}

private fun saveHistory(prefs: android.content.SharedPreferences, history: List<HistoryEntry>) {
    val array = JSONArray()
    history.take(500).forEach { entry ->
        array.put(JSONObject().apply {
            put("id", entry.id)
            put("result", entry.result)
            put("mode", entry.mode)
            put("poolSize", entry.poolSize)
            put("timestamp", entry.timestamp)
        })
    }
    prefs.edit().putString("history", array.toString()).apply()
}

private fun loadHistory(raw: String?): List<HistoryEntry> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                add(
                    HistoryEntry(
                        id = obj.optLong("id", i.toLong()),
                        result = obj.optString("result", "?"),
                        mode = obj.optString("mode", "Unknown"),
                        poolSize = obj.optLong("poolSize", 0L),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                )
            }
        }.take(500)
    }.getOrDefault(emptyList())
}

private fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MMM d, h:mm:ss a", Locale.getDefault()).format(Date(timestamp))

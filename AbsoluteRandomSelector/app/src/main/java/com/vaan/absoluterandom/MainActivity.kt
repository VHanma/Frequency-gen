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
import androidx.compose.runtime.snapshots.SnapshotStateList
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

enum class AppSection(val title:String) { SELECTOR("Selector"), BANKS("Custom Banks"), TAROT("Tarot") }
enum class DrawMode(val title: String) { LETTERS("Letters"), NUMBERS("Numbers"), BOTH("Both") }
data class DrawEntry(val id: Long, val result: String, val mode: String, val pool: Long, val time: Long)
data class PoolInfo(val size: Long?, val min: Long?, val numberCount: Long, val error: String?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AbsoluteRandomTarotApp() }
    }
}

@Composable
private fun AbsoluteRandomTarotApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("absolute_random_tarot_clone", Context.MODE_PRIVATE) }
    val rng = remember { EntropyEngine() }
    var section by remember { mutableStateOf(AppSection.SELECTOR) }

    val scheme = darkColorScheme(
        primary = Color(0xFF9FE7FF), secondary = Color(0xFFC3B4FF), tertiary = Color(0xFFFFD6A5),
        background = Color(0xFF070A10), surface = Color(0xFF101722), onPrimary = Color(0xFF041018),
        onBackground = Color(0xFFF4F7FB), onSurface = Color(0xFFF4F7FB)
    )

    MaterialTheme(colorScheme = scheme) {
        Column(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF070A10), Color(0xFF0A1020), Color(0xFF070A10))))
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("ABSOLUTE RANDOM • TAROT CLONE", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, fontSize = 21.sp)
                Text("Independent custom banks + cryptographic tarot engine", color = Color(0xFF91A0B2), fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    AppSection.entries.forEach {
                        FilterChip(section == it, { section = it }, { Text(it.title) }, modifier = Modifier.weight(1f))
                    }
                }
            }
            when(section) {
                AppSection.SELECTOR -> SelectorScreen(prefs, rng)
                AppSection.BANKS -> CustomBanksScreen(prefs, rng)
                AppSection.TAROT -> TarotScreen(prefs, rng)
            }
        }
    }
}

@Composable
private fun SelectorScreen(prefs: android.content.SharedPreferences, rng: EntropyEngine) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val custom = remember { mutableStateListOf<String>().apply { addAll(loadStrings(prefs.getString("selector_custom", null))) } }
    val history = remember { mutableStateListOf<DrawEntry>().apply { addAll(loadHistory(prefs.getString("selector_history", null))) } }
    var mode by remember { mutableStateOf(runCatching { DrawMode.valueOf(prefs.getString("selector_mode", "BOTH")!!) }.getOrDefault(DrawMode.BOTH)) }
    var includeCustom by remember { mutableStateOf(prefs.getBoolean("selector_include_custom", true)) }
    var minText by remember { mutableStateOf(prefs.getString("selector_min", "0") ?: "0") }
    var maxText by remember { mutableStateOf(prefs.getString("selector_max", "9") ?: "9") }
    var customText by remember { mutableStateOf("") }
    var latest by remember { mutableStateOf(prefs.getString("selector_latest", "?") ?: "?") }
    var error by remember { mutableStateOf<String?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ResultHero(latest, "WITH REPLACEMENT • result immediately returns to this pool") }
        item {
            Text("Choose standard pool", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DrawMode.entries.forEach { choice ->
                    FilterChip(mode == choice, {
                        mode = choice
                        prefs.edit().putString("selector_mode", choice.name).apply()
                    }, { Text(choice.title) }, modifier = Modifier.weight(1f))
                }
            }
        }
        if (mode != DrawMode.LETTERS) item {
            Text("Number range", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("Min", minText, Modifier.weight(1f)) { minText = sanitizeNumber(it); prefs.edit().putString("selector_min", minText).apply() }
                NumberField("Max", maxText, Modifier.weight(1f)) { maxText = sanitizeNumber(it); prefs.edit().putString("selector_max", maxText).apply() }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = Color(0xFF0D1420)) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(includeCustom, { includeCustom = it; prefs.edit().putBoolean("selector_include_custom", it).apply() })
                        Column {
                            Text("Include quick custom items", fontWeight = FontWeight.Bold)
                            Text("${custom.size} saved. For custom-only pools use Custom Banks.", color = Color(0xFF95A3B6), fontSize = 12.sp)
                        }
                    }
                    AddItemsRow(customText, { customText = it }, "Add quick items") {
                        val add = splitItems(customText)
                        if (add.isNotEmpty()) { custom.addAll(add); saveStrings(prefs, "selector_custom", custom); customText = "" }
                    }
                    ChipItems(custom) { index -> custom.removeAt(index); saveStrings(prefs, "selector_custom", custom) }
                }
            }
        }
        item {
            val preview = poolInfo(mode, minText, maxText, includeCustom, custom)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Pool: ${preview.size ?: "—"}", color = Color(0xFFA7B7CA), fontSize = 13.sp)
                Text("Saved draws: ${history.size}", color = Color(0xFFA7B7CA), fontSize = 13.sp)
            }
            Button(onClick = {
                val pool = poolInfo(mode, minText, maxText, includeCustom, custom)
                if (pool.error != null || pool.size == null || pool.size <= 0) error = pool.error ?: "Pool is empty."
                else {
                    error = null
                    val fp = "${mode.name}|$minText|$maxText|$includeCustom|${custom.joinToString("\u001f")}"
                    val index = rng.pick(pool.size, SystemClock.elapsedRealtimeNanos(), fp)
                    val result = resolve(index, mode, pool.min, pool.numberCount, includeCustom, custom)
                    latest = result
                    history.add(0, DrawEntry(System.nanoTime(), result, mode.title, pool.size, System.currentTimeMillis()))
                    while(history.size > 500) history.removeAt(history.lastIndex)
                    saveHistory(prefs, "selector_history", history)
                    prefs.edit().putString("selector_latest", result).apply()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }, modifier = Modifier.fillMaxWidth().height(62.dp), shape = RoundedCornerShape(18.dp)) {
                Text("DRAW RANDOM ITEM", fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            error?.let { Text(it, color = Color(0xFFFF9AA2), fontSize = 13.sp) }
        }
        item { HistoryHeader(context, history) { history.clear(); saveHistory(prefs, "selector_history", history) } }
        if(history.isEmpty()) item { EmptyCard("Selections stack here.") } else items(history, key={it.id}) { HistoryRow(it) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun CustomBanksScreen(prefs: android.content.SharedPreferences, rng: EntropyEngine) {
    val haptics = LocalHapticFeedback.current
    val names = remember {
        mutableStateListOf<String>().apply {
            val loaded = loadBankNames(prefs.getString("bank_names", null))
            addAll(if(loaded.size==7) loaded else listOf("Custom 1","Custom 2","Custom 3","Custom 4","Custom 5","Custom 6","Tarot Archetypes"))
        }
    }
    val banks = remember { List(7) { i -> mutableStateListOf<String>().apply { addAll(loadStrings(prefs.getString("bank_$i", null))) } } }
    var selected by remember { mutableIntStateOf(0) }
    var addText by remember { mutableStateOf("") }
    var latest by remember { mutableStateOf("—") }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("CUSTOM-ONLY BANKS", fontWeight=FontWeight.Black, fontSize=19.sp)
            Text("Seven independent pools. Rename each one, fill it with anything, then draw only from that bank. Draws are with replacement.", color=Color(0xFF95A3B6), fontSize=12.sp)
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(7) { i -> FilterChip(selected==i, {selected=i; addText=""}, {Text(if(i==6) "🔮 ${names[i]}" else names[i])}) }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape=RoundedCornerShape(20.dp), color=if(selected==6) Color(0xFF171126) else Color(0xFF0D1420)) {
                Column(Modifier.padding(15.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(names[selected], { names[selected]=it.take(32); saveBankNames(prefs,names) }, Modifier.fillMaxWidth(), label={Text("Bank name")}, singleLine=true)
                    Text(if(selected==6) "This bank also powers the Tarot section's Custom Archetype Deck." else "This pool contains ${banks[selected].size} independent slots.", color=if(selected==6) Color(0xFFC3B4FF) else Color(0xFF95A3B6), fontSize=12.sp)
                    AddItemsRow(addText,{addText=it},"Add items to ${names[selected]}") {
                        val add=splitItems(addText)
                        if(add.isNotEmpty()) { banks[selected].addAll(add); saveStrings(prefs,"bank_$selected",banks[selected]); addText="" }
                    }
                    ChipItems(banks[selected]) { index -> banks[selected].removeAt(index); saveStrings(prefs,"bank_$selected",banks[selected]) }
                    Button(onClick={
                        if(banks[selected].isNotEmpty()) {
                            val index=rng.pick(banks[selected].size.toLong(),SystemClock.elapsedRealtimeNanos(),"bank|$selected|${banks[selected].joinToString("\u001f")}")
                            latest=banks[selected][index.toInt()]
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }, enabled=banks[selected].isNotEmpty(), modifier=Modifier.fillMaxWidth().height(58.dp)){
                        Text("DRAW ONLY FROM ${names[selected].uppercase()}", fontWeight=FontWeight.Black, textAlign=TextAlign.Center)
                    }
                }
            }
        }
        item { ResultHero(latest, "CUSTOM BANK ${selected+1} • WITH REPLACEMENT") }
        item {
            Text("All banks", fontWeight=FontWeight.Black, fontSize=17.sp)
            Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
                names.forEachIndexed { i,n ->
                    Surface(Modifier.fillMaxWidth(), shape=RoundedCornerShape(14.dp), color=Color(0xFF0D1420)) {
                        Row(Modifier.padding(12.dp), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text(if(i==6)"🔮 $n" else n,fontWeight=FontWeight.Bold); Text("${banks[i].size} items",color=Color(0xFF91A0B2),fontSize=12.sp) }
                            TextButton(onClick={selected=i}) { Text("OPEN") }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TarotScreen(prefs: android.content.SharedPreferences, rng: EntropyEngine) {
    val archetypes = remember { mutableStateListOf<String>().apply { addAll(loadStrings(prefs.getString("bank_6", null))) } }
    var deck by remember { mutableStateOf(runCatching { TarotDeck.valueOf(prefs.getString("tarot_deck","THOTH")!!) }.getOrDefault(TarotDeck.THOTH)) }
    var presetIndex by remember { mutableIntStateOf(prefs.getInt("tarot_preset",0).coerceIn(0,ReadingPresets.all.lastIndex)) }
    var question by remember { mutableStateOf(prefs.getString("tarot_question","") ?: "") }
    var reversals by remember { mutableStateOf(prefs.getBoolean("tarot_reversals",true)) }
    var customPositionsText by remember { mutableStateOf(prefs.getString("tarot_custom_positions","Position 1, Position 2, Position 3") ?: "Position 1, Position 2, Position 3") }
    var deckMenu by remember { mutableStateOf(false) }
    var spreadMenu by remember { mutableStateOf(false) }
    var drawn by remember { mutableStateOf<List<DrawnCard>>(emptyList()) }
    var synthesis by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val haptics=LocalHapticFeedback.current
    val preset=ReadingPresets.all[presetIndex]

    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("TAROT READING ENGINE", fontWeight=FontWeight.Black, fontSize=20.sp, color=Color(0xFFC3B4FF))
            Text("Cards do not repeat inside one spread. Every new reading restores the full deck.", color=Color(0xFF95A3B6), fontSize=12.sp)
        }
        item {
            OutlinedTextField(question,{question=it; prefs.edit().putString("tarot_question",it).apply()}, Modifier.fillMaxWidth(),label={Text("Ask a question / reading focus")}, supportingText={Text("Examples: Where is this dating situation going? What should I understand about my next move?")})
        }
        item {
            Text("Deck", fontWeight=FontWeight.Bold)
            Box {
                OutlinedButton(onClick={deckMenu=true}, modifier=Modifier.fillMaxWidth()) { Text(deck.title, Modifier.weight(1f)); Text("▼") }
                DropdownMenu(deckMenu,{deckMenu=false}) {
                    TarotDeck.entries.forEach { d -> DropdownMenuItem(text={Text(d.title)}, onClick={deck=d; deckMenu=false; prefs.edit().putString("tarot_deck",d.name).apply()}) }
                }
            }
            Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),color=Color(0xFF11182A)) { Text(deck.description,Modifier.padding(12.dp),fontSize=12.sp,color=Color(0xFFB7C5D7)) }
            if(deck==TarotDeck.CUSTOM) Text("Custom Archetype cards currently available: ${archetypes.size}. Edit them in Custom Banks → Tarot Archetypes.",color=Color(0xFFC3B4FF),fontSize=12.sp)
        }
        item {
            Text("Reading / spread", fontWeight=FontWeight.Bold)
            Box {
                OutlinedButton(onClick={spreadMenu=true}, modifier=Modifier.fillMaxWidth()) { Text(preset.title, Modifier.weight(1f)); Text("▼") }
                DropdownMenu(spreadMenu,{spreadMenu=false}) {
                    ReadingPresets.all.forEachIndexed { i,p ->
                        DropdownMenuItem(text={Column {Text(p.title,fontWeight=FontWeight.Bold);Text("${p.positions.size} card${if(p.positions.size==1)"" else "s"}",fontSize=11.sp)}}, onClick={presetIndex=i; spreadMenu=false; prefs.edit().putInt("tarot_preset",i).apply()})
                    }
                }
            }
            Text(preset.description,color=Color(0xFF95A3B6),fontSize=12.sp)
        }
        if(preset.title=="Custom Spread") item {
            OutlinedTextField(customPositionsText,{customPositionsText=it; prefs.edit().putString("tarot_custom_positions",it).apply()}, Modifier.fillMaxWidth(),label={Text("Custom positions, comma/new-line separated")}, supportingText={Text("1–10 positions, in exact draw order")})
        }
        item {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Switch(reversals,{reversals=it; prefs.edit().putBoolean("tarot_reversals",it).apply()})
                Spacer(Modifier.width(10.dp))
                Column { Text("Allow reversed cards",fontWeight=FontWeight.Bold); Text("Orientation is independently randomized.",color=Color(0xFF95A3B6),fontSize=12.sp) }
            }
        }
        item {
            val positions = if(preset.title=="Custom Spread") parsePositions(customPositionsText) else preset.positions
            Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=Color(0xFF0D1420)) {
                Column(Modifier.padding(13.dp)) {
                    Text("Positions • exact interpretation order",fontWeight=FontWeight.Bold)
                    positions.forEachIndexed { i,p -> Text("${i+1}. $p",color=Color(0xFFB8C5D5),fontSize=12.sp) }
                }
            }
        }
        item {
            Button(onClick={
                val customPositions=if(preset.title=="Custom Spread") parsePositions(customPositionsText) else emptyList()
                if(preset.title=="Custom Spread" && customPositions.isEmpty()) error="Add at least one custom position."
                else runCatching { TarotInterpreter.draw(rng,deck,preset,question,reversals,archetypes,customPositions) }
                    .onSuccess { (cards,text) -> drawn=cards; synthesis=text; error=null; haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                    .onFailure { error=it.message ?: "Reading could not be drawn." }
            }, modifier=Modifier.fillMaxWidth().height(64.dp), shape=RoundedCornerShape(18.dp), colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFC3B4FF),contentColor=Color(0xFF111018))) {
                Text("DRAW ${if(preset.title=="Custom Spread") parsePositions(customPositionsText).size else preset.positions.size}-CARD READING",fontWeight=FontWeight.Black,fontSize=17.sp)
            }
            error?.let{Text(it,color=Color(0xFFFF9AA2),fontSize=13.sp)}
        }
        if(drawn.isNotEmpty()) {
            item { Text("YOUR READING",fontWeight=FontWeight.Black,fontSize=19.sp,color=Color(0xFFC3B4FF)) }
            itemsIndexed(drawn) { i,d ->
                Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=Color(0xFF111527)) {
                    Column(Modifier.padding(15.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Text("${i+1}. ${d.position}",color=Color(0xFF9AA8B8),fontSize=12.sp,fontWeight=FontWeight.Bold)
                        Text(d.displayName + if(d.reversed) " ↓ REVERSED" else " ↑ UPRIGHT",fontWeight=FontWeight.Black,fontSize=20.sp)
                        Text(TarotInterpreter.cardExplanation(deck,d),color=Color(0xFFD6DCE8),fontSize=13.sp)
                    }
                }
            }
            item {
                Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=Color(0xFF171126)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("HOW THE CARDS WORK TOGETHER",fontWeight=FontWeight.Black,color=Color(0xFFC3B4FF),fontSize=17.sp)
                        Spacer(Modifier.height(7.dp))
                        Text(synthesis,color=Color(0xFFE0DDF0),fontSize=13.sp)
                    }
                }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=Color(0xFF0D1420)) {
                Column(Modifier.padding(14.dp)) {
                    Text("Combination engine",fontWeight=FontWeight.Bold)
                    Text("For multi-card readings the app interprets each adjacent transition in draw order, then checks Major/Minor structure, elemental support or conflict, suit concentration, repeated numbers, court-card density, reversals, and the final spread-wide arc. This means the interpretation is generated for the actual combination you drew rather than looking up isolated definitions.",color=Color(0xFF9EADBF),fontSize=12.sp)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun ResultHero(latest:String, subtitle:String) {
    Surface(Modifier.fillMaxWidth(), shape=RoundedCornerShape(22.dp), color=Color(0xFF101A2B)) {
        Column(Modifier.padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Text("SELECTED",color=Color(0xFF8FA2B9),fontSize=11.sp,fontWeight=FontWeight.Bold)
            Text(latest,color=Color.White,fontSize=if(latest.length<=4) 60.sp else 34.sp,fontWeight=FontWeight.Black,textAlign=TextAlign.Center)
            Text(subtitle,color=Color(0xFFB9F6CA),fontSize=11.sp,textAlign=TextAlign.Center)
        }
    }
}

@Composable
private fun AddItemsRow(value:String, change:(String)->Unit, label:String, add:()->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        OutlinedTextField(value,change,Modifier.weight(1f),label={Text(label)},supportingText={Text("Comma or new line separates items")})
        Spacer(Modifier.width(7.dp))
        Button(onClick=add){Text("ADD")}
    }
}

@Composable
private fun ChipItems(list: SnapshotStateList<String>, remove:(Int)->Unit) {
    if(list.isNotEmpty()) {
        LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            itemsIndexed(list,key={i,item->"$i:$item"}) { i,item -> OutlinedButton(onClick={remove(i)}) { Text("×  $item",maxLines=1) } }
        }
    }
}

@Composable
private fun NumberField(label:String,value:String,modifier:Modifier,change:(String)->Unit) = OutlinedTextField(value,change,modifier,label={Text(label)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))

@Composable
private fun HistoryHeader(context:Context, history:List<DrawEntry>, clear:()->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
        Column {Text("HISTORY",fontWeight=FontWeight.Black,fontSize=17.sp);Text("Newest first",color=Color(0xFF94A4B7),fontSize=11.sp)}
        Row {
            TextButton(onClick={
                val text=history.joinToString("\n"){"${formatTime(it.time)}  ${it.result}  [${it.mode}, pool ${it.pool}]"}
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Random history",text))
                Toast.makeText(context,"History copied",Toast.LENGTH_SHORT).show()
            }){Text("COPY")}
            TextButton(onClick=clear){Text("CLEAR")}
        }
    }
}

@Composable
private fun HistoryRow(entry:DrawEntry) {
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),color=Color(0xFF0D1420)) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(entry.result.take(5),fontWeight=FontWeight.Black,fontSize=20.sp,modifier=Modifier.width(70.dp),textAlign=TextAlign.Center)
            Column {Text(entry.result,fontWeight=FontWeight.Bold);Text("${entry.mode} • pool ${entry.pool} • ${formatTime(entry.time)}",color=Color(0xFF91A0B2),fontSize=11.sp)}
        }
    }
}

@Composable private fun EmptyCard(text:String) = Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),color=Color(0xFF0D1420)){Text(text,Modifier.padding(16.dp),color=Color(0xFF9AA8B8))}

private fun splitItems(s:String)=s.split(',', '\n').map{it.trim().take(100)}.filter{it.isNotEmpty()}
private fun parsePositions(s:String)=splitItems(s).take(10)
private fun sanitizeNumber(s:String)=s.filterIndexed{i,c->c.isDigit()||(c=='-'&&i==0)}.take(19)

private fun poolInfo(mode:DrawMode,minText:String,maxText:String,includeCustom:Boolean,custom:List<String>):PoolInfo {
    val letters=if(mode!=DrawMode.NUMBERS)26L else 0L
    var min:Long?=null
    var count=0L
    if(mode!=DrawMode.LETTERS) {
        min=minText.toLongOrNull(); val max=maxText.toLongOrNull()
        if(min==null||max==null)return PoolInfo(null,null,0,"Enter valid minimum and maximum numbers.")
        if(max<min)return PoolInfo(null,min,0,"Maximum must be at least the minimum.")
        val distance=java.math.BigInteger.valueOf(max).subtract(java.math.BigInteger.valueOf(min))
        if(distance<java.math.BigInteger.ZERO || distance>=java.math.BigInteger.valueOf(Long.MAX_VALUE)) return PoolInfo(null,min,0,"That number range is too large.")
        count=distance.toLong()+1
    }
    val extras=if(includeCustom)custom.size.toLong() else 0L
    if(letters>Long.MAX_VALUE-count || letters+count>Long.MAX_VALUE-extras)return PoolInfo(null,min,count,"Pool is too large.")
    val total=letters+count+extras
    return PoolInfo(total,min,count,if(total==0L)"Pool is empty." else null)
}

private fun resolve(raw:Long,mode:DrawMode,min:Long?,count:Long,includeCustom:Boolean,custom:List<String>):String {
    var i=raw
    if(mode!=DrawMode.NUMBERS){if(i<26)return('A'.code+i.toInt()).toChar().toString();i-=26}
    if(mode!=DrawMode.LETTERS){if(i<count)return(java.math.BigInteger.valueOf(min?:0).add(java.math.BigInteger.valueOf(i))).toString();i-=count}
    if(includeCustom&&custom.isNotEmpty())return custom[i.toInt()]
    return "?"
}

private fun saveStrings(p:android.content.SharedPreferences,key:String,values:List<String>) { val a=JSONArray();values.forEach(a::put);p.edit().putString(key,a.toString()).apply() }
private fun loadStrings(raw:String?):List<String> = runCatching { if(raw.isNullOrBlank())emptyList() else JSONArray(raw).let{a->List(a.length()){a.optString(it)}.filter(String::isNotBlank)} }.getOrDefault(emptyList())
private fun saveBankNames(p:android.content.SharedPreferences,names:List<String>) { val a=JSONArray();names.forEach(a::put);p.edit().putString("bank_names",a.toString()).apply() }
private fun loadBankNames(raw:String?):List<String> = loadStrings(raw)
private fun saveHistory(p:android.content.SharedPreferences,key:String,values:List<DrawEntry>) { val a=JSONArray();values.take(500).forEach{e->a.put(JSONObject().apply{put("id",e.id);put("result",e.result);put("mode",e.mode);put("pool",e.pool);put("time",e.time)})};p.edit().putString(key,a.toString()).apply() }
private fun loadHistory(raw:String?):List<DrawEntry> = runCatching { if(raw.isNullOrBlank())emptyList() else JSONArray(raw).let{a->List(a.length()){i->a.getJSONObject(i).let{o->DrawEntry(o.optLong("id",i.toLong()),o.optString("result","?"),o.optString("mode","?"),o.optLong("pool",0),o.optLong("time",0))}}.take(500)} }.getOrDefault(emptyList())
private fun formatTime(t:Long)=SimpleDateFormat("MMM d, h:mm:ss a",Locale.getDefault()).format(Date(t))

package com.vaan.contactomega

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private lateinit var store: SessionStore
    private lateinit var audio: AudioItcEngine
    private lateinit var spirit: SpiritBoxEngine
    private lateinit var sensors: SensorLab
    private lateinit var visual: VisualLab
    private lateinit var beacon: BeaconEngine
    private var player: MediaPlayer? = null

    private var status by mutableStateOf("OMEGA LAB IDLE")
    private var log by mutableStateOf(listOf<String>())
    private var partial by mutableStateOf("")
    private var finals by mutableStateOf(listOf<String>())
    private var meter by mutableDoubleStateOf(0.0)
    private var spectrum by mutableStateOf(FloatArray(32))
    private var field by mutableStateOf(SensorLab.Snapshot())
    private var entropyZ by mutableDoubleStateOf(0.0)
    private var entropyOnes by mutableIntStateOf(100)
    private var visualLuma by mutableDoubleStateOf(0.0)
    private var visualDiff by mutableDoubleStateOf(0.0)
    private var screenFlash by mutableStateOf(false)
    private var lastPhysicalEventMs = 0L
    private var lastTranscriptMs = 0L
    private var coincidenceCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        spirit = SpiritBoxEngine(store) { s -> ui { status = s } }
        audio = AudioItcEngine(
            this, store,
            onState = { s -> ui { status = s } },
            onTranscript = { text, isPartial, conf, overlap ->
                ui {
                    if (isPartial) partial = text else {
                        partial = ""
                        val tag = if (overlap) "SOURCE OVERLAP" else "CLEAN WINDOW"
                        val c = conf?.let { " ${"%.0f".format(it * 100)}%" } ?: ""
                        finals = (finals + "[$tag$c] $text").takeLast(30)
                        addLog("EVP › $text · $tag$c")
                        lastTranscriptMs = System.currentTimeMillis()
                        if (lastPhysicalEventMs > 0 && abs(lastTranscriptMs - lastPhysicalEventMs) <= 1200) markCoincidence("TRANSCRIPT↔FIELD/VISUAL", text)
                    }
                }
            },
            onMeter = { rms, bands -> ui { meter = rms; spectrum = bands.copyOf() } },
            sourceActive = { spirit.sourceActive || audio.monitorEchoEnabled },
            onEntropyTrial = { ones, z -> ui { entropyOnes = ones; entropyZ = z } }
        )
        sensors = SensorLab(this, store) { snap, event ->
            ui {
                field = snap
                if (event != null) {
                    addLog(event)
                    lastPhysicalEventMs = System.currentTimeMillis()
                    if (lastTranscriptMs > 0 && abs(lastPhysicalEventMs - lastTranscriptMs) <= 1200) markCoincidence("FIELD↔TRANSCRIPT", event)
                }
            }
        }
        visual = VisualLab(this, store,
            onMetrics = { luma, diff -> ui { visualLuma = luma; visualDiff = diff } },
            onEvent = { e -> ui { addLog(e); lastPhysicalEventMs = System.currentTimeMillis(); if(lastTranscriptMs>0 && abs(lastPhysicalEventMs-lastTranscriptMs)<=1200) markCoincidence("VISUAL↔TRANSCRIPT",e) } }
        )
        beacon = BeaconEngine(this, store, { f -> ui { screenFlash = f } }, { s -> ui { status = s } })
        requestNeededPermissions()
        audio.loadModelAsync()
        setContent { OmegaLab() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (audio.isRunning()) audio.stop()
        if (spirit.sourceActive) spirit.stop()
        if (sensors.isRunning()) sensors.stop()
        visual.stop(); beacon.stop()
        player?.release()
    }

    private fun requestNeededPermissions() {
        val p = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) p += Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) p += Manifest.permission.CAMERA
        if (p.isNotEmpty()) requestPermissions(p.toTypedArray(), 77)
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)
    private fun addLog(s: String) { log = (log + "[${store.elapsedMs()/1000.0}s] $s").takeLast(80) }
    private fun markCoincidence(kind:String, detail:String){ coincidenceCount++; store.event("MULTIMODAL_COINCIDENCE", mapOf("kind" to kind,"detail" to detail)); addLog("⚡ COINCIDENCE $kind") }

    private fun newSession(mode: String, config: Map<String, Any?> = emptyMap()) {
        store.start(mode, config); log = emptyList(); finals = emptyList(); partial = ""; coincidenceCount = 0
        addLog("NEW SESSION · $mode · ${store.sessionId}")
    }

    @Composable private fun OmegaLab() {
        val bg = if (screenFlash) Color.White else Color(0xFF02060B)
        MaterialTheme(colorScheme = darkColorScheme(primary=Color(0xFF63FFF0),secondary=Color(0xFFC391FF),background=Color(0xFF02060B),surface=Color(0xFF0A1220))) {
            Box(Modifier.fillMaxSize().background(bg)) {
                if (!screenFlash) MainConsole()
            }
        }
    }

    @Composable private fun MainConsole() {
        var tab by remember { mutableIntStateOf(0) }
        val names=listOf("EVP","BOX","ESTES","FIELD","VISUAL","BEACON","BLIND","REVIEW")
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal=14.dp, vertical=10.dp)) {
                Text("Ω CONTACT LAB v2", style=MaterialTheme.typography.headlineMedium, fontWeight=FontWeight.Black, color=Color(0xFF63FFF0))
                Text("NHI · ITC · EVP · FIELD · OPTICAL · BLIND CONTACT", color=Color(0xFFC391FF), style=MaterialTheme.typography.labelMedium)
                Text(status, style=MaterialTheme.typography.bodySmall)
                Text("Coincident events: $coincidenceCount", color=Color(0xFFFFD06F), style=MaterialTheme.typography.bodySmall)
            }
            ScrollableTabRow(selectedTabIndex=tab, edgePadding=6.dp) { names.forEachIndexed { i,n -> Tab(selected=tab==i,onClick={tab=i},text={Text(n)}) } }
            Box(Modifier.fillMaxSize().padding(12.dp)) {
                when(tab){
                    0->EvpTab(); 1->SpiritBoxTab(); 2->EstesTab(); 3->FieldTab(); 4->VisualTab(); 5->BeaconTab(); 6->BlindTab(); 7->ReviewTab()
                }
            }
        }
    }

    @Composable private fun EvpTab() {
        var echo by remember { mutableStateOf(false) }
        var delay by remember { mutableFloatStateOf(650f) }
        var echoGain by remember { mutableFloatStateOf(.35f) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            SectionTitle("TRUE EVP RECORDER")
            Text("48 kHz WAV is preserved for the full session. A processed copy, subtitles, sensor timeline, entropy trials and candidate clips are saved beside it.", style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={
                    newSession("RAW_EVP"); if(!sensors.isRunning())sensors.start(); audio.monitorEchoEnabled=echo; audio.monitorEchoDelayMs=delay.toInt(); audio.monitorEchoGain=echoGain; audio.start("RAW_EVP")
                }, enabled=!audio.isRunning(), modifier=Modifier.weight(1f)){Text("START EVP")}
                OutlinedButton(onClick={ if(audio.isRunning())audio.stop(); if(sensors.isRunning())sensors.stop(); status="EVP session stopped" }, enabled=audio.isRunning(), modifier=Modifier.weight(1f)){Text("STOP")}
            }
            Toggle("Live portal echo / microphone delay", echo) { echo=it; audio.monitorEchoEnabled=it }
            Text("Echo delay ${delay.toInt()} ms")
            Slider(value=delay,onValueChange={delay=it;audio.monitorEchoDelayMs=it.toInt()},valueRange=80f..5000f)
            Text("Echo return ${(echoGain*100).toInt()}%")
            Slider(value=echoGain,onValueChange={echoGain=it;audio.monitorEchoGain=it},valueRange=.05f..1f)
            MeterCard()
            TranscriptCard()
            EventCard()
        }
    }

    @Composable private fun SpiritBoxTab() {
        var mode by remember { mutableStateOf("PHONEME SWEEP") }
        var dwell by remember { mutableFloatStateOf(120f) }
        var reverse by remember { mutableStateOf(false) }
        var gain by remember { mutableFloatStateOf(.55f) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            SectionTitle("SPIRIT BOX / STATICOM / PHONEME ITC")
            Selector("SOURCE",mode,listOf("PHONEME SWEEP","STATICOM","BROADBAND WHITE NOISE","PORTAL ECHO BED")){mode=it}
            Text("Sweep dwell ${dwell.toInt()} ms · 30–350 ms")
            Slider(value=dwell,onValueChange={dwell=it},valueRange=30f..350f)
            Toggle("Reverse sweep",reverse){reverse=it}
            Text("Source level ${(gain*100).toInt()}%")
            Slider(value=gain,onValueChange={gain=it},valueRange=.05f..1f)
            Button(onClick={
                val m=when(mode){"STATICOM"->SpiritBoxEngine.Mode.STATICOM;"BROADBAND WHITE NOISE"->SpiritBoxEngine.Mode.WHITE_NOISE;"PORTAL ECHO BED"->SpiritBoxEngine.Mode.ECHO_BED;else->SpiritBoxEngine.Mode.PHONEME_SWEEP}
                newSession("SPIRIT_BOX_${m.name}",mapOf("dwellMs" to dwell.toInt(),"reverse" to reverse)); if(!sensors.isRunning())sensors.start(); if(!audio.isRunning())audio.start("SPIRIT_BOX"); spirit.start(m,dwell.toInt(),reverse,gain)
            },enabled=!spirit.sourceActive,modifier=Modifier.fillMaxWidth()){Text("START BOX + RECORD EVERYTHING")}
            OutlinedButton(onClick={spirit.stop();if(audio.isRunning())audio.stop();if(sensors.isRunning())sensors.stop();status="Spirit-box session saved"},enabled=spirit.sourceActive,modifier=Modifier.fillMaxWidth()){Text("STOP + SAVE")}
            Text("Generated-source segments are seed-logged. Subtitles overlapping the app's own source are labeled SOURCE OVERLAP; clean gaps remain distinguishable.", style=MaterialTheme.typography.bodySmall,color=Color(0xFF9FB4C8))
            MeterCard(); TranscriptCard(); EventCard()
        }
    }

    @Composable private fun EstesTab() {
        var question by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        var blind by remember { mutableStateOf(true) }
        var dwell by remember { mutableFloatStateOf(120f) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            SectionTitle("ESTES BLIND RECEIVER")
            Text("Receiver gets the box/audio feed. Questioner timestamps questions here. In blind mode the receiver transcript stays hidden until the session ends.",style=MaterialTheme.typography.bodySmall)
            Toggle("Hide live transcript from receiver",blind){blind=it}
            Text("Sweep ${dwell.toInt()} ms"); Slider(value=dwell,onValueChange={dwell=it},valueRange=30f..350f)
            if(!running) Button(onClick={newSession("ESTES_BLIND",mapOf("dwellMs" to dwell.toInt()));audio.start("ESTES");sensors.start();spirit.start(SpiritBoxEngine.Mode.PHONEME_SWEEP,dwell.toInt(),false,.55f);running=true},modifier=Modifier.fillMaxWidth()){Text("BEGIN ESTES SESSION")}
            else OutlinedButton(onClick={spirit.stop();audio.stop();sensors.stop();running=false;status="Estes session complete"},modifier=Modifier.fillMaxWidth()){Text("END ESTES + REVEAL")}
            OutlinedTextField(value=question,onValueChange={question=it},label={Text("Question / prompt")},modifier=Modifier.fillMaxWidth())
            Button(onClick={if(question.isNotBlank()){store.event("ESTES_QUESTION",mapOf("question" to question));addLog("QUESTION › $question");question=""}},enabled=running,modifier=Modifier.fillMaxWidth()){Text("TIME-STAMP QUESTION")}
            if(!blind || !running) TranscriptCard() else Card(Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("RECEIVER BLIND MODE",fontWeight=FontWeight.Black);Text("Transcript is still being recorded, but hidden during the session.")}}
            EventCard()
        }
    }

    @Composable private fun FieldTab() {
        var calib by remember { mutableStateOf("") }
        val classification=when {
            field.yesScore==null||field.noScore==null->"Teach YES and NO signatures first"
            field.yesScore!! < field.noScore!! -> "Closest learned pattern: YES"
            else -> "Closest learned pattern: NO"
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            SectionTitle("FIELD + ENVIRONMENTAL LEXICON")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick={if(!sensors.isRunning()){newSession("FIELD_ITC");sensors.start()}},enabled=!sensors.isRunning(),modifier=Modifier.weight(1f)){Text("START FIELD")}
                OutlinedButton(onClick={if(sensors.isRunning())sensors.stop()},enabled=sensors.isRunning(),modifier=Modifier.weight(1f)){Text("STOP")}
            }
            SensorCard("MAG",field.mag,"µT magnitude"); SensorCard("ACCEL",field.accel,"m/s² magnitude"); SensorCard("GYRO",field.gyro,"rad/s magnitude"); SensorCard("LIGHT",field.light,"lux"); SensorCard("PRESSURE",field.pressure,"hPa"); SensorCard("PROXIMITY",field.proximity,"sensor units")
            field.word?.let{ Text("ENVIRONMENT WORD → $it",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Black,color=Color(0xFFFFD06F)) }
            Text("The word channel is deterministic from the logged sensor vector. Same quantized field state maps to the same word.",style=MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick={if(!sensors.isRunning()){newSession("YES_NO_TRAINING");sensors.start()};calib="YES";sensors.beginCalibration("YES");Handler(Looper.getMainLooper()).postDelayed({sensors.finishCalibration();calib="";addLog("YES signature learned")},5000)},enabled=calib.isBlank(),modifier=Modifier.weight(1f)){Text(if(calib=="YES")"LEARNING…" else "TEACH YES")}
                Button(onClick={if(!sensors.isRunning()){newSession("YES_NO_TRAINING");sensors.start()};calib="NO";sensors.beginCalibration("NO");Handler(Looper.getMainLooper()).postDelayed({sensors.finishCalibration();calib="";addLog("NO signature learned")},5000)},enabled=calib.isBlank(),modifier=Modifier.weight(1f)){Text(if(calib=="NO")"LEARNING…" else "TEACH NO")}
            }
            Text(classification,fontWeight=FontWeight.Bold)
            field.yesScore?.let{Text("YES distance ${"%.3f".format(it)}")};field.noScore?.let{Text("NO distance ${"%.3f".format(it)}")}
            Divider(); Text("MICROPHONE ENTROPY CHANNEL",fontWeight=FontWeight.Bold); Text("200-bit trial: $entropyOnes ones · z=${"%.2f".format(entropyZ)}"); Text("Entropy trials are recorded whenever the EVP recorder is running.",style=MaterialTheme.typography.bodySmall)
            EventCard()
        }
    }

    @Composable private fun VisualTab() {
        var recording by remember { mutableStateOf(false) }
        var mode by remember { mutableStateOf("SKY / LIGHT WATCH") }
        DisposableEffect(Unit){ onDispose{visual.stop()} }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            SectionTitle("VISUAL / OPTICAL ITC OBSERVATORY")
            Selector("EXPERIMENT",mode,listOf("SKY / LIGHT WATCH","SCHREIBER FEEDBACK","WATER / MIRROR REFLECTION","OPTICAL REPLY WATCH")){mode=it;store.event("VISUAL_MODE",mapOf("mode" to it))}
            AndroidView(factory={ctx->PreviewView(ctx).also{pv-> if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED){store.ensure("VISUAL_ITC");visual.start(pv,this@MainActivity)}}},modifier=Modifier.fillMaxWidth().height(360.dp))
            Text("Luma ${"%.1f".format(visualLuma)} · frame Δ ${"%.1f".format(visualDiff)}")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                Button(onClick={visual.captureStill();addLog("Manual visual snapshot")},modifier=Modifier.weight(1f)){Text("SNAPSHOT")}
                Button(onClick={if(!recording){visual.startRecording();recording=true}else{visual.stopRecording();recording=false}},modifier=Modifier.weight(1f)){Text(if(recording)"STOP VIDEO" else "RECORD VIDEO")}
            }
            Button(onClick={if(!sensors.isRunning())sensors.start();if(!audio.isRunning())audio.start("VISUAL_MULTISENSOR");addLog("Multisensor witness armed")},modifier=Modifier.fillMaxWidth()){Text("ARM AUDIO + FIELD WITNESS")}
            Text("Frame-difference anomalies auto-save stills. Visual events are timestamp-correlated with EVP subtitles and field changes.",style=MaterialTheme.typography.bodySmall)
            EventCard()
        }
    }

    @Composable private fun BeaconTab() {
        var text by remember { mutableStateOf("HELLO. IDENTIFY YOUR PATTERN. REPEAT AFTER THIS MESSAGE.") }
        var protocol by remember { mutableStateOf("MATH+PHYSICS") }
        var torch by remember { mutableStateOf(true) };var screen by remember { mutableStateOf(true) };var sound by remember { mutableStateOf(true) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            SectionTitle("UNIVERSAL CONTACT BEACON")
            Selector("PACKET",protocol,listOf("MATH+PHYSICS","PRIME","FIBONACCI","BINARY")){protocol=it}
            OutlinedTextField(value=text,onValueChange={text=it},label={Text("Outbound message")},minLines=3,modifier=Modifier.fillMaxWidth())
            Toggle("Torch optical pulses",torch){torch=it};Toggle("Full-screen optical pulses",screen){screen=it};Toggle("FSK acoustic packet",sound){sound=it}
            Button(onClick={newSession("CONTACT_BEACON",mapOf("protocol" to protocol));beacon.transmit(text,protocol,torch,screen,sound){status="10-second reply gap · recording";audio.start("POST_BEACON_REPLY");sensors.start();Handler(Looper.getMainLooper()).postDelayed({if(audio.isRunning())audio.stop();if(sensors.isRunning())sensors.stop();status="Beacon reply window saved"},10000)}},modifier=Modifier.fillMaxWidth()){Text("TRANSMIT → AUTO RECORD 10s REPLY")}
            Text("Packet contains sync, mathematical preamble, payload length, UTF-8 payload and CRC. Every transmission is followed by a clean timed reply window.",style=MaterialTheme.typography.bodySmall)
            TranscriptCard();EventCard()
        }
    }

    @Composable private fun BlindTab() {
        var active by remember { mutableStateOf(false) };var commit by remember { mutableStateOf("") };var hidden by remember { mutableStateOf("") };var nonce by remember { mutableStateOf("") };var choice by remember { mutableStateOf("") };var revealed by remember { mutableStateOf(false) }
        val targets=listOf("CIRCLE","WAVES","STAR","SPIRAL")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
            SectionTitle("BLIND / CONSCIOUSNESS CONTACT")
            Text("The target is locked before the trial. You see only its cryptographic commitment until reveal.",style=MaterialTheme.typography.bodySmall)
            if(!active){Button(onClick={newSession("BLIND_TARGET");val r=SecureRandom();hidden=targets[r.nextInt(targets.size)];nonce=ByteArray(16).also{r.nextBytes(it)}.joinToString(""){b->"%02x".format(b)};commit=sha256("$hidden|$nonce");store.event("BLIND_COMMIT",mapOf("commit" to commit));active=true;revealed=false;choice=""},modifier=Modifier.fillMaxWidth()){Text("LOCK NEW BLIND TARGET")}}
            if(active){Text("COMMITMENT",fontWeight=FontWeight.Bold);Text(commit,style=MaterialTheme.typography.bodySmall);Text("Choose only after the contact/impression period:");targets.forEach{t->OutlinedButton(onClick={choice=t},modifier=Modifier.fillMaxWidth()){Text(if(choice==t)"✓ $t" else t)}};Button(onClick={revealed=true;store.event("BLIND_REVEAL",mapOf("target" to hidden,"nonce" to nonce,"choice" to choice,"hit" to (choice==hidden)));addLog("Blind target $hidden · choice $choice")},enabled=choice.isNotBlank()&&!revealed,modifier=Modifier.fillMaxWidth()){Text("REVEAL TARGET")};if(revealed){Text("TARGET: $hidden",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black,color=if(choice==hidden)Color(0xFF63FFF0) else Color(0xFFFFD06F));Text("Nonce: $nonce",style=MaterialTheme.typography.bodySmall)}}
            EventCard()
        }
    }

    @Composable private fun ReviewTab() {
        var sessions by remember { mutableStateOf(store.recentSessions()) }
        var selected by remember { mutableStateOf<File?>(null) }
        var files by remember { mutableStateOf(listOf<File>()) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
            SectionTitle("EVIDENCE REVIEW")
            Button(onClick={sessions=store.recentSessions()},modifier=Modifier.fillMaxWidth()){Text("REFRESH SESSIONS")}
            sessions.take(20).forEach{d->OutlinedButton(onClick={selected=d;files=d.listFiles()?.sortedBy{it.name}?:emptyList()},modifier=Modifier.fillMaxWidth()){Text(d.name)}}
            selected?.let{Text("FILES · ${it.name}",fontWeight=FontWeight.Bold);files.forEach{f->Card(Modifier.fillMaxWidth()){Row(Modifier.padding(9.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(f.name);Text("${f.length()/1024} KB",style=MaterialTheme.typography.bodySmall)};if(f.extension.lowercase() in listOf("wav","mp4"))TextButton(onClick={play(f)}){Text("PLAY")}}}}}
            OutlinedButton(onClick={player?.stop();player?.release();player=null},modifier=Modifier.fillMaxWidth()){Text("STOP PLAYBACK")}
        }
    }

    @Composable private fun MeterCard(){
        Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text("LIVE AUDIO · RMS ${"%.4f".format(meter)}",fontWeight=FontWeight.Bold);Canvas(Modifier.fillMaxWidth().height(90.dp)){val w=size.width/spectrum.size;val maxV=(spectrum.maxOrNull()?:.001f).coerceAtLeast(.001f);spectrum.forEachIndexed{i,v->val h=size.height*(v/maxV).coerceIn(0f,1f);drawLine(Color(0xFF63FFF0),Offset(i*w+w/2,size.height),Offset(i*w+w/2,size.height-h),strokeWidth=max(2f,w*.55f))}};Text("Offline subtitle model: ${if(audio.modelReady)"READY" else "LOADING"}",style=MaterialTheme.typography.bodySmall)}}
    }
    @Composable private fun TranscriptCard(){Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text("LIVE EVP SUBTITLES",fontWeight=FontWeight.Bold);if(partial.isNotBlank())Text("… $partial",color=Color(0xFF9FB4C8));finals.takeLast(10).reversed().forEach{Text(it,style=MaterialTheme.typography.bodySmall)}}}}
    @Composable private fun EventCard(){Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text("SYNCHRONIZED EVENT STREAM",fontWeight=FontWeight.Bold);log.takeLast(14).reversed().forEach{Text(it,style=MaterialTheme.typography.bodySmall)}}}}
    @Composable private fun SensorCard(name:String,value:Double,unit:String){Row(Modifier.fillMaxWidth()){Text(name,Modifier.weight(1f),fontWeight=FontWeight.Bold);Text("${"%.3f".format(value)} $unit")}}
    @Composable private fun SectionTitle(t:String){Text(t,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Black,color=Color(0xFF63FFF0))}
    @Composable private fun Toggle(label:String,v:Boolean,on:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f));Switch(checked=v,onCheckedChange=on)}}
    @Composable private fun Selector(label:String,current:String,items:List<String>,on:(String)->Unit){var open by remember{mutableStateOf(false)};Column{Text(label,style=MaterialTheme.typography.labelMedium,color=Color(0xFF9FB4C8));OutlinedButton(onClick={open=true},modifier=Modifier.fillMaxWidth()){Text(current)};DropdownMenu(expanded=open,onDismissRequest={open=false}){items.forEach{v->DropdownMenuItem(text={Text(v)},onClick={on(v);open=false})}}}}

    private fun play(file:File){try{player?.release();player=MediaPlayer().apply{setDataSource(file.absolutePath);prepare();start()};status="Playing ${file.name}"}catch(t:Throwable){status="Playback error: ${t.message}"}}
    private fun sha256(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
}

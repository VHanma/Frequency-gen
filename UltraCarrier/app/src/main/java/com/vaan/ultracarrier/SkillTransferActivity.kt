package com.vaan.ultracarrier

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vaan.ultracarrier.collective.OmegaRuntime
import java.io.File

private enum class SkillProtocol(val label: String, val evidence: String) {
    MOTOR_CHUNKING(
        "Basal-ganglia motor chunking",
        "Established neuroscience: action selection, sequence chunking, reinforcement and automatization."
    ),
    CAUDATE_TO_PUTAMEN(
        "Caudate → putamen transition",
        "Established learning model: associative/early sequence learning tends to recruit caudate; skilled execution increasingly recruits sensorimotor putamen."
    ),
    PSI_CAUDATE(
        "Psi / caudate-putamen probe",
        "Exploratory hypothesis: Garry Nolan and Kit Green have publicly described unusual caudate-putamen connectivity in an experiencer cohort and a few high-end remote viewers. The specific dataset has not been established as a replicated psi biomarker."
    ),
    NIGHT_CLASS(
        "Night-class / remote-information probe",
        "Fringe historical hypothesis inspired by Through the Curtain: anomalous learning or information access during altered/sleep states, with later caudate associations discussed by Nolan and others."
    )
}

class SkillTransferActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val omega = OmegaRuntime.get(applicationContext)
        setContent {
            SkillTransferTheme {
                SkillTransferScreen(
                    onStageAudio = omega::loadFile,
                    onBeamPlay = omega::play,
                    onBeamStop = omega::stop,
                    onOpenOmega = { startActivity(Intent(this, OmegaActivity::class.java)) }
                )
            }
        }
    }
}

@Composable
private fun SkillTransferTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF76F7D5),
            secondary = Color(0xFFC2A6FF),
            background = Color(0xFF050706),
            surface = Color(0xFF111B18),
            onBackground = Color(0xFFF0FFF9),
            onSurface = Color(0xFFF0FFF9)
        ),
        content = content
    )
}

@Composable
private fun SkillTransferScreen(
    onStageAudio: (Uri) -> Unit,
    onBeamPlay: () -> Unit,
    onBeamStop: () -> Unit,
    onOpenOmega: () -> Unit
) {
    val context = LocalContext.current
    var protocol by remember { mutableStateOf(SkillProtocol.MOTOR_CHUNKING) }
    var skillName by remember { mutableStateOf("Kung-fu / combat skill experiment") }
    var chunkPlan by remember { mutableStateOf("") }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var loopVideo by remember { mutableStateOf(true) }
    var baselineTime by remember { mutableStateOf("") }
    var baselineAccuracy by remember { mutableStateOf("") }
    var postTime by remember { mutableStateOf("") }
    var postAccuracy by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Choose a protocol and build one specific skill packet.") }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audioUri = uri
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onStageAudio(uri)
            status = "Audio staged in the Omega beam engine."
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            videoUri = uri
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            status = "Skill video loaded for synchronized exposure."
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ULTRACARRIER SKILL TRANSFER LAB Ω", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text("Matrix-style procedural-learning experiment • audio beam + video training + before/after measurement", color = Color(0xFFA9CFC4))

        LabCard("Research target") {
            Text("Question: can structured audio/video information accelerate acquisition of a specific motor program beyond ordinary exposure?", fontWeight = FontWeight.Bold)
            Text("The basal ganglia are especially relevant because they help select actions, chunk movement sequences and convert practiced behavior into automatic performance.", color = Color(0xFFD5EAE4))
            Text("The psi branch is treated as a separate hypothesis rather than assumed to be the mechanism.", color = Color(0xFFA9CFC4))
        }

        LabCard("Protocol") {
            SkillProtocol.entries.forEach { item ->
                FilterChip(
                    selected = protocol == item,
                    onClick = { protocol = item },
                    label = { Text(item.label) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(protocol.evidence, color = Color(0xFFCCDDD8))
        }

        LabCard("Skill packet") {
            OutlinedTextField(skillName, { skillName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Skill / technique") })
            OutlinedTextField(
                chunkPlan,
                { chunkPlan = it },
                modifier = Modifier.fillMaxWidth().height(130.dp),
                label = { Text("Movement chunks / cues / timing / decision rules") }
            )
            Text("Example structure: trigger → perception cue → action chunk 1 → transition → action chunk 2 → finish. Keep the tested sequence specific enough to score.", color = Color(0xFFA9CFC4))
        }

        LabCard("Audio information channel") {
            Text(audioUri?.lastPathSegment ?: "No audio selected", color = Color(0xFFD5EAE4))
            Button(onClick = { audioPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text("CHOOSE SKILL AUDIO") }
            Text("The selected audio is immediately staged inside the existing Omega processing engine, so any current Matrix / scalar / beam method can process it.", color = Color(0xFFA9CFC4))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBeamPlay, enabled = audioUri != null, modifier = Modifier.weight(1f)) { Text("PLAY BEAM") }
                OutlinedButton(onClick = onBeamStop, modifier = Modifier.weight(1f)) { Text("STOP") }
            }
            OutlinedButton(onClick = onOpenOmega, modifier = Modifier.fillMaxWidth()) { Text("OPEN FULL OMEGA ENCODER") }
        }

        LabCard("Video information channel") {
            Text(videoUri?.lastPathSegment ?: "No video selected", color = Color(0xFFD5EAE4))
            Button(onClick = { videoPicker.launch(arrayOf("video/*")) }, modifier = Modifier.fillMaxWidth()) { Text("CHOOSE SKILL VIDEO") }
            if (videoUri != null) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(240.dp),
                    factory = { ctx -> VideoView(ctx).also { videoView = it } },
                    update = { view ->
                        val key = videoUri.toString()
                        if (view.tag != key) {
                            view.tag = key
                            view.setVideoURI(videoUri)
                            view.setOnPreparedListener { player -> player.isLooping = loopVideo }
                        }
                    }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { videoView?.start() }, modifier = Modifier.weight(1f)) { Text("PLAY VIDEO") }
                    OutlinedButton(onClick = { videoView?.pause() }, modifier = Modifier.weight(1f)) { Text("PAUSE") }
                    OutlinedButton(onClick = { videoView?.seekTo(0) }, modifier = Modifier.weight(1f)) { Text("RESET") }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Loop video")
                    Switch(checked = loopVideo, onCheckedChange = { loopVideo = it; videoView?.setOnPreparedListener { p -> p.isLooping = it } })
                }
                Button(
                    onClick = {
                        videoView?.start()
                        onBeamPlay()
                        status = "Dual session running: visual skill stream + processed audio stream."
                    },
                    enabled = audioUri != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("START VIDEO + BEAM TOGETHER") }
            }
        }

        LabCard("Before / after experiment") {
            Text("Score one repeatable task before exposure, run the session, then score the same task immediately afterward.", color = Color(0xFFA9CFC4))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(baselineTime, { baselineTime = it }, modifier = Modifier.weight(1f), label = { Text("Baseline time") })
                OutlinedTextField(baselineAccuracy, { baselineAccuracy = it }, modifier = Modifier.weight(1f), label = { Text("Baseline accuracy") })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(postTime, { postTime = it }, modifier = Modifier.weight(1f), label = { Text("Post time") })
                OutlinedTextField(postAccuracy, { postAccuracy = it }, modifier = Modifier.weight(1f), label = { Text("Post accuracy") })
            }
            OutlinedTextField(notes, { notes = it }, modifier = Modifier.fillMaxWidth().height(110.dp), label = { Text("Observations") })
            Button(
                onClick = {
                    val file = File(context.filesDir, "skill-transfer-trials.csv")
                    if (!file.exists()) file.writeText("timestamp,skill,protocol,audio,video,baseline_time,baseline_accuracy,post_time,post_accuracy,chunks,notes\n")
                    file.appendText(
                        listOf(
                            System.currentTimeMillis().toString(), skillName, protocol.name,
                            (audioUri != null).toString(), (videoUri != null).toString(),
                            baselineTime, baselineAccuracy, postTime, postAccuracy, chunkPlan, notes
                        ).joinToString(",") { csv(it) } + "\n"
                    )
                    status = "Trial saved locally: skill-transfer-trials.csv"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("SAVE TRIAL") }
        }

        LabCard("Caudate-putamen lead") {
            Text("Why this region stays in the experiment:", fontWeight = FontWeight.Bold)
            Text("• Mainstream: caudate and putamen are central to learning, sequencing, action selection and automatization.")
            Text("• Nolan/Green lead: unusual caudate-putamen connectivity has been publicly reported in an experiencer cohort and a very small number of remote viewers.")
            Text("• Fringe historical lead: Through the Curtain associated the caudate with anomalous information access decades before the modern discussion.")
            Text("• Current limitation: recent ESP neuroimaging reviews do not identify the basal ganglia as a replicated universal psi signature.", color = Color(0xFFFFD59A))
        }

        LabCard("Status") { Text(status, color = Color(0xFF76F7D5), fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun LabCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

private fun csv(value: String): String = "\"${value.replace("\"", "\"\"").replace("\n", " ").replace("\r", " ")}\""

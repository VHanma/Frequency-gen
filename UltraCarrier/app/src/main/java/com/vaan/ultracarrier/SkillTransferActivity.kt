package com.vaan.ultracarrier

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vaan.ultracarrier.collective.OmegaRuntime
import java.io.File

private enum class SkillProtocol(val label: String, val evidence: String) {
    MOTOR_CHUNKING(
        "Basal-ganglia motor chunking",
        "Established learning model: basal-ganglia circuits help action selection, sequence chunking, reinforcement and automatization."
    ),
    CAUDATE_TO_PUTAMEN(
        "Caudate → putamen transition",
        "Established learning model: associative/early learning often recruits caudate while highly practiced sensorimotor execution increasingly recruits putamen."
    ),
    PSI_CAUDATE(
        "Psi / caudate-putamen probe",
        "Exploratory hypothesis: Nolan and Green have publicly discussed unusual caudate-putamen connectivity in an experiencer cohort and a few high-performing remote viewers. This is not treated as a replicated psi biomarker."
    ),
    NIGHT_CLASS(
        "Night-class / remote-information probe",
        "Fringe historical hypothesis inspired by Through the Curtain: anomalous learning or information access during altered or sleep states."
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
                    onSetText = omega::setText,
                    onPrepareText = omega::prepareText,
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
    onSetText: (String) -> Unit,
    onPrepareText: () -> Unit,
    onBeamPlay: () -> Unit,
    onBeamStop: () -> Unit,
    onOpenOmega: () -> Unit
) {
    val context = LocalContext.current
    var protocol by remember { mutableStateOf(SkillProtocol.MOTOR_CHUNKING) }
    var skillName by remember { mutableStateOf("Kung-fu / combat skill experiment") }
    var informationPayload by remember { mutableStateOf("") }
    var packetNotes by remember { mutableStateOf("") }
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var baselineTime by remember { mutableStateOf("") }
    var baselineAccuracy by remember { mutableStateOf("") }
    var postTime by remember { mutableStateOf("") }
    var postAccuracy by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Build one frequency-only skill-transfer trial.") }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audioUri = uri
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onStageAudio(uri)
            status = "Audio payload staged in the Omega frequency engine."
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ULTRACARRIER SKILL TRANSFER LAB Ω", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text("Frequency-only procedural-information experiment • no camera • no pose tracking • no visual channel", color = Color(0xFFA9CFC4))

        LabCard("Research target") {
            Text("Question: can structured information encoded into audio/frequency patterns measurably improve acquisition or automatic execution of a specific skill?", fontWeight = FontWeight.Bold)
            Text("The experiment keeps conventional motor-learning and psi/nonlocal interpretations separate so the same payload can be tested under different hypotheses.", color = Color(0xFFD5EAE4))
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

        LabCard("Skill information packet") {
            OutlinedTextField(skillName, { skillName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Skill / technique") })
            OutlinedTextField(
                informationPayload,
                {
                    informationPayload = it
                    onSetText(it)
                },
                modifier = Modifier.fillMaxWidth().height(150.dp),
                label = { Text("Information to encode") }
            )
            Text("Keep the payload procedural: cue → decision → movement sequence → timing → transition → finish.", color = Color(0xFFA9CFC4))
            OutlinedTextField(
                packetNotes,
                { packetNotes = it },
                modifier = Modifier.fillMaxWidth().height(90.dp),
                label = { Text("Packet / frequency notes") }
            )
            Button(
                onClick = {
                    onSetText(informationPayload)
                    onPrepareText()
                    status = "Text payload sent to Android TTS and staged for Omega encoding."
                },
                enabled = informationPayload.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("PREPARE TEXT AS AUDIO PAYLOAD") }
        }

        LabCard("Frequency / audio channel") {
            Text(audioUri?.lastPathSegment ?: "No external audio selected", color = Color(0xFFD5EAE4))
            Button(onClick = { audioPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) { Text("CHOOSE AUDIO PAYLOAD") }
            Text("Use the full Omega encoder to choose Matrix, scalar, resonance, carrier, modulation, stacking and looping variables. This screen deliberately does not invent a single 'basal ganglia frequency.'", color = Color(0xFFA9CFC4))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onBeamPlay, modifier = Modifier.weight(1f)) { Text("PLAY ENCODED PAYLOAD") }
                OutlinedButton(onClick = onBeamStop, modifier = Modifier.weight(1f)) { Text("STOP") }
            }
            OutlinedButton(onClick = onOpenOmega, modifier = Modifier.fillMaxWidth()) { Text("OPEN FULL OMEGA FREQUENCY ENCODER") }
        }

        LabCard("Before / after experiment") {
            Text("Use one repeatable test before exposure and the same scoring rule afterward. The app records the trial rather than pretending a subjective feeling proves transfer.", color = Color(0xFFA9CFC4))
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
                    if (!file.exists()) file.writeText("timestamp,skill,protocol,source_audio,baseline_time,baseline_accuracy,post_time,post_accuracy,payload,packet_notes,notes\n")
                    file.appendText(
                        listOf(
                            System.currentTimeMillis().toString(), skillName, protocol.name,
                            (audioUri != null).toString(), baselineTime, baselineAccuracy,
                            postTime, postAccuracy, informationPayload, packetNotes, notes
                        ).joinToString(",") { csv(it) } + "\n"
                    )
                    status = "Trial saved locally: skill-transfer-trials.csv"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("SAVE TRIAL") }
        }

        LabCard("Caudate-putamen lead") {
            Text("Why it remains one hypothesis:", fontWeight = FontWeight.Bold)
            Text("• Mainstream: caudate and putamen are central to learning, sequencing, action selection and automatization.")
            Text("• Nolan/Green lead: unusual caudate-putamen connectivity has been publicly reported in an experiencer cohort and a very small number of remote viewers.")
            Text("• Fringe historical lead: Through the Curtain associated the caudate with anomalous information access decades before the modern discussion.")
            Text("• Frequency question remains open: the lab tests encodings rather than assuming one magic frequency.", color = Color(0xFFFFD59A))
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

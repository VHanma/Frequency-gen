package com.vaan.ultracarrier

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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vaan.ultracarrier.audio.BeamLabMode
import com.vaan.ultracarrier.audio.GodXMode
import com.vaan.ultracarrier.audio.ListeningPath
import com.vaan.ultracarrier.audio.ThoughtMode
import kotlin.math.roundToInt

class BeamLabActivity : ComponentActivity() {
    private lateinit var controller: BeamLabController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = BeamLabController(applicationContext)
        setContent {
            val state by controller.state.collectAsState()
            BeamTheme {
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(controller::loadFile)
                }
                BeamScreen(
                    state = state,
                    onText = controller::setText,
                    onPick = { picker.launch(arrayOf("audio/*", "application/octet-stream")) },
                    onFamily = controller::setFamily,
                    onBeamMode = controller::setBeamMode,
                    onLabXMode = controller::setLabXMode,
                    onClassicMode = controller::setClassicMode,
                    onPath = controller::setPath,
                    onPresence = controller::setPresence,
                    onCarrier = controller::setCarrier,
                    onElfRate = controller::setElfRate,
                    onElfDepth = controller::setElfDepth,
                    onTarget = controller::setTargetAngle,
                    onNull = controller::setNullAngle,
                    onSpacing = controller::setSpacing,
                    onDither = controller::setBeamDither,
                    onDitherRate = controller::setDitherRate,
                    onSpeakerSeparation = controller::setSpeakerSeparation,
                    onListenerDistance = controller::setListenerDistance,
                    onHeadWidth = controller::setHeadWidth,
                    onModHz = controller::setModulationHz,
                    onModDepth = controller::setModulationDepth,
                    onBeat = controller::setBeatHz,
                    onBase = controller::setBaseHz,
                    onDelay = controller::setMicroDelay,
                    onMotion = controller::setMotionRate,
                    onLoop = controller::setLoopEnabled,
                    onPrepare = controller::prepareText,
                    onPlay = controller::play,
                    onStop = controller::stop,
                    onVolume = controller::setListeningVolume
                )
            }
        }
    }

    override fun onDestroy() {
        controller.close()
        super.onDestroy()
    }
}

@Composable
private fun BeamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF6FF7D5),
            secondary = Color(0xFFFFD56A),
            background = Color(0xFF050A09),
            surface = Color(0xFF10201C),
            onBackground = Color(0xFFEFFFFA),
            onSurface = Color(0xFFEFFFFA)
        ),
        content = content
    )
}

@Composable
private fun BeamScreen(
    state: BeamLabUiState,
    onText: (String) -> Unit,
    onPick: () -> Unit,
    onFamily: (BeamFamily) -> Unit,
    onBeamMode: (BeamLabMode) -> Unit,
    onLabXMode: (GodXMode) -> Unit,
    onClassicMode: (ThoughtMode) -> Unit,
    onPath: (ListeningPath) -> Unit,
    onPresence: (Float) -> Unit,
    onCarrier: (Float) -> Unit,
    onElfRate: (Float) -> Unit,
    onElfDepth: (Float) -> Unit,
    onTarget: (Float) -> Unit,
    onNull: (Float) -> Unit,
    onSpacing: (Float) -> Unit,
    onDither: (Float) -> Unit,
    onDitherRate: (Float) -> Unit,
    onSpeakerSeparation: (Float) -> Unit,
    onListenerDistance: (Float) -> Unit,
    onHeadWidth: (Float) -> Unit,
    onModHz: (Float) -> Unit,
    onModDepth: (Float) -> Unit,
    onBeat: (Float) -> Unit,
    onBase: (Float) -> Unit,
    onDelay: (Float) -> Unit,
    onMotion: (Float) -> Unit,
    onLoop: (Boolean) -> Unit,
    onPrepare: () -> Unit,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onVolume: () -> Unit
) {
    val h = state.hardware
    val minCarrier = h?.carrierMinHz ?: 13_500f
    val maxCarrier = h?.carrierMaxHz ?: 22_000f

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("ULTRACARRIER ELF BEAM LAB", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text("Directional-audio experiments first. Lab X and ThoughtBeam remain inside as separate engine families.", color = Color(0xFFA5C8BE))

        InfoCard(h?.label ?: "Detecting audio route…", state.status)

        ControlCard("Output path") {
            ListeningPath.entries.forEach { p -> FilterChip(selected = state.listeningPath == p, onClick = { onPath(p) }, label = { Text(p.label) }) }
        }

        ControlCard("Engine family") {
            BeamFamily.entries.forEach { f -> FilterChip(selected = state.family == f, onClick = { onFamily(f) }, label = { Text(f.label) }) }
        }

        when (state.family) {
            BeamFamily.BEAM_LAB -> ControlCard("Beam experiment") {
                BeamLabMode.entries.forEach { m -> FilterChip(selected = state.beamMode == m, onClick = { onBeamMode(m) }, label = { Text(m.label) }) }
                Text(state.beamMode.description, color = Color(0xFFA5C8BE))
            }
            BeamFamily.LAB_X -> ControlCard("Lab X experiment") {
                GodXMode.entries.forEach { m -> FilterChip(selected = state.labXMode == m, onClick = { onLabXMode(m) }, label = { Text(m.label) }) }
                Text(state.labXMode.description, color = Color(0xFFA5C8BE))
            }
            BeamFamily.THOUGHTBEAM -> ControlCard("ThoughtBeam engine") {
                ThoughtMode.entries.forEach { m -> FilterChip(selected = state.classicMode == m, onClick = { onClassicMode(m) }, label = { Text(m.label) }) }
                Text(state.classicMode.description, color = Color(0xFFA5C8BE))
            }
        }

        OutlinedTextField(
            value = state.text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth().height(130.dp),
            label = { Text("Text to encode") }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPrepare, enabled = !state.isBusy, modifier = Modifier.weight(1f)) { Text("Prepare Text") }
            OutlinedButton(onClick = onPick, enabled = !state.isBusy, modifier = Modifier.weight(1f)) { Text("Choose File") }
        }
        Text(state.loadedName?.let { "Ready: $it" } ?: "Ready: none", color = Color(0xFFCDE4DD))

        ControlCard("Loop") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Loop until Stop", fontWeight = FontWeight.Bold)
                Switch(checked = state.loopEnabled, onCheckedChange = onLoop)
            }
        }
        Button(onClick = onPlay, enabled = state.loadedAudio != null && !state.isBusy, modifier = Modifier.fillMaxWidth().height(58.dp)) {
            Text(if (state.loopEnabled) "PLAY & LOOP" else "PLAY", fontWeight = FontWeight.Black)
        }

        ControlCard("Presence") {
            Text("${(state.presence * 100).roundToInt()}%")
            Slider(value = state.presence, onValueChange = onPresence, valueRange = 0.05f..1f)
        }

        if (state.family == BeamFamily.BEAM_LAB) {
            if (state.beamMode != BeamLabMode.SWEET_SPOT_XTC) {
                ControlCard("Ultrasonic carrier") {
                    Text("${state.carrierHz.roundToInt()} Hz", fontWeight = FontWeight.Bold)
                    Slider(value = state.carrierHz.coerceIn(minCarrier, maxCarrier), onValueChange = onCarrier, valueRange = minCarrier..maxCarrier)
                    Text(if (state.listeningPath == ListeningPath.EXTERNAL_ARRAY) "External array path selected." else "Phone/headset playback is an encoding preview; real narrow-beam behavior requires an ultrasonic array.", color = Color(0xFFA5C8BE))
                }
            }

            if (state.beamMode == BeamLabMode.ELF_BEAM) {
                ControlCard("ELF inside the beam") {
                    Text("Envelope ${"%.2f".format(state.elfRateHz)} Hz", fontWeight = FontWeight.Bold)
                    Slider(value = state.elfRateHz, onValueChange = onElfRate, valueRange = 0.5f..40f)
                    Text("Depth ${(state.elfDepth * 100).roundToInt()}%", fontWeight = FontWeight.Bold)
                    Slider(value = state.elfDepth, onValueChange = onElfDepth, valueRange = 0f..0.95f)
                    Text("The low-rate pattern modulates the encoded voice while the ultrasonic carrier supplies directionality.", color = Color(0xFFA5C8BE))
                }
            }

            if (state.beamMode in setOf(BeamLabMode.ELF_BEAM, BeamLabMode.BRIGHT_DARK_BUBBLE, BeamLabMode.BEAM_LOCK, BeamLabMode.FREY_CODEC_ACOUSTIC)) {
                ControlCard("Beam geometry") {
                    Text("Target ${state.targetAngleDeg.roundToInt()}°", fontWeight = FontWeight.Bold)
                    Slider(value = state.targetAngleDeg, onValueChange = onTarget, valueRange = -60f..60f)
                    Text("Transducer spacing ${"%.1f".format(state.spacingMm)} mm", fontWeight = FontWeight.Bold)
                    Slider(value = state.spacingMm, onValueChange = onSpacing, valueRange = 1f..50f)
                }
            }

            if (state.beamMode == BeamLabMode.BRIGHT_DARK_BUBBLE) {
                ControlCard("Dark-zone direction") {
                    Text("Null ${state.nullAngleDeg.roundToInt()}°", fontWeight = FontWeight.Bold)
                    Slider(value = state.nullAngleDeg, onValueChange = onNull, valueRange = -75f..75f)
                    Text("The two output weights are solved to reinforce the target direction while cancelling the selected null direction.", color = Color(0xFFA5C8BE))
                }
            }

            if (state.beamMode == BeamLabMode.BEAM_LOCK) {
                ControlCard("Beam lock dither") {
                    Text("Dither ±${"%.1f".format(state.beamDitherDeg)}°", fontWeight = FontWeight.Bold)
                    Slider(value = state.beamDitherDeg, onValueChange = onDither, valueRange = 0f..12f)
                    Text("Rate ${"%.2f".format(state.ditherRateHz)} Hz", fontWeight = FontWeight.Bold)
                    Slider(value = state.ditherRateHz, onValueChange = onDitherRate, valueRange = 0.03f..3f)
                }
            }

            if (state.beamMode == BeamLabMode.CROSSED_BEAM_FOCUS) {
                InfoCard("Two-beam output", "Left channel carries the ultrasonic reference carrier. Right channel carries the suppressed-carrier speech sideband. Feed them to two independently aimed ultrasonic emitters and overlap the beams at the listening zone.")
            }

            if (state.beamMode == BeamLabMode.SWEET_SPOT_XTC) {
                ControlCard("Sweet-spot geometry") {
                    Text("Speaker separation ${state.speakerSeparationCm.roundToInt()} cm", fontWeight = FontWeight.Bold)
                    Slider(value = state.speakerSeparationCm, onValueChange = onSpeakerSeparation, valueRange = 4f..200f)
                    Text("Listener distance ${state.listenerDistanceCm.roundToInt()} cm", fontWeight = FontWeight.Bold)
                    Slider(value = state.listenerDistanceCm, onValueChange = onListenerDistance, valueRange = 10f..400f)
                    Text("Head width ${"%.1f".format(state.headWidthCm)} cm", fontWeight = FontWeight.Bold)
                    Slider(value = state.headWidthCm, onValueChange = onHeadWidth, valueRange = 10f..24f)
                }
            }
        }

        if (state.family == BeamFamily.LAB_X) {
            ControlCard("Lab X modulation") {
                Text("Rate ${"%.2f".format(state.modulationHz)} Hz")
                Slider(value = state.modulationHz, onValueChange = onModHz, valueRange = 1f..120f)
                Text("Depth ${(state.modulationDepth * 100).roundToInt()}%")
                Slider(value = state.modulationDepth, onValueChange = onModDepth, valueRange = 0f..0.9f)
                Text("Beat ${"%.1f".format(state.beatHz)} Hz • Base ${state.baseHz.roundToInt()} Hz")
                Slider(value = state.beatHz, onValueChange = onBeat, valueRange = 1f..60f)
                Slider(value = state.baseHz, onValueChange = onBase, valueRange = 120f..900f)
                Text("Delay ${state.microDelayUs.roundToInt()} µs • Motion ${"%.2f".format(state.motionRateHz)} Hz")
                Slider(value = state.microDelayUs, onValueChange = onDelay, valueRange = 0f..650f)
                Slider(value = state.motionRateHz, onValueChange = onMotion, valueRange = 0.03f..4f)
            }
        }

        state.beamReport?.let { r ->
            InfoCard("Active ${r.mode.label}", "${r.sampleRate} Hz output • carrier ${r.carrierHz.roundToInt()} Hz • target ${r.targetAngleDeg.roundToInt()}° • null ${r.nullAngleDeg.roundToInt()}° • route ${r.routeName}")
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onVolume, modifier = Modifier.weight(1f)) { Text("Volume") }
            Button(onClick = onStop, enabled = state.isTransmitting || state.isBusy, modifier = Modifier.weight(1f)) { Text("Stop") }
        }
    }
}

@Composable
private fun ControlCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF10201C))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF10201C))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, color = Color(0xFFA5C8BE))
        }
    }
}

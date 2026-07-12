# LightCode Photophone

A separate Android app for literal music carried by light.

```text
song PCM waveform
→ light-intensity waveform
→ black photoacoustic jar
→ audible pressure waveform
```

The Android app does not create an `AudioTrack` for the song. Screen mode uses only display light. USB mode sends raw PCM over USB bulk transfers, so Android media volume does not control the light data.

## Android package

```text
com.vhanma.lightcode.photophone
```

It installs beside LightCode Jar and LightCode Codex.

## Source modes

- Song or audio file decoded to mono PCM
- Typed speech synthesized to a file internally, then decoded to PCM
- Calibration tone
- Logarithmic jar sweep

## Music processing

- **Direct PCM** keeps the original waveform structure with DC removal and peak normalization.
- **Clarity optical EQ** emphasizes changes and upper-frequency detail.
- **Compressed optical PCM** raises quiet details and controls peaks.

## Light engines

### Scanline PCM screen

Encodes rapid waveform changes across horizontal display rows, then synchronizes each frame through Android `Choreographer`. This is a phone-only experimental route. Results depend on the exact display controller, scan direction, OLED/LCD response and PWM behavior.

Controls include:

- 192 to 768 rows
- normal or reverse scan direction
- white, red, green, blue, amber, cyan or magenta
- full, hollow, central-shaft or twin-beam geometry
- optical modulation gain

### Whole-screen fallback

Changes the complete screen once per physical frame. This can preserve slow rhythm or envelope movement but cannot reproduce full musical bandwidth on an ordinary 60–120 Hz panel.

### USB bulk LED controller

The full-rate path:

```text
Android app
→ 48,000 mono PCM samples/s over USB bulk
→ RP2040 firmware
→ 250 kHz PWM
→ transistor or constant-current LED driver
→ modulated light
→ jar
```

The app automatically searches for a USB CDC-data or vendor-specific interface with a bulk OUT endpoint, requests permission, raises CDC DTR/RTS where applicable, and sends 480-sample packets.

## USB packet format

Configuration packet:

```text
LPC1
uint32 sampleRate = 48000
uint32 channels = 1
uint32 bits = 16
uint32 PWM center = 2048
uint32 PWM carrier = 250000
```

PCM packet:

```text
LPP1
uint32 sequence
uint32 sampleRate
uint16 sampleCount = 480
uint16 flags
uint32 CRC32
uint32 reserved
sampleCount × int16 little-endian PCM
```

## RP2040 firmware

Firmware is located at:

```text
firmware/rp2040_photophone/rp2040_photophone.ino
```

Recommended board setup:

- Raspberry Pi Pico or compatible RP2040 board
- Arduino-Pico core with USB CDC enabled
- PWM output pin 15
- Logic-level MOSFET or LED-current driver
- Shared ground between RP2040 and LED power stage

### Basic low-power LED driver

```text
RP2040 GPIO 15
→ 220 Ω gate resistor
→ logic-level N-channel MOSFET gate

10 kΩ gate-to-ground pulldown
MOSFET source → ground
MOSFET drain → LED cathode
LED anode → current-limited positive supply
RP2040 ground → LED-driver ground
```

Do not power a high-power LED directly from an RP2040 pin. Use a suitable constant-current driver for high-output LEDs.

## Proof Mode

Proof Mode records the physical jar through the phone microphone while the light transmission runs. When stopped, it:

1. Saves a WAV under `Download/LightCode-Photophone/`.
2. Measures the recorded RMS level.
3. Compares the recorded amplitude envelope with the original song envelope across possible timing offsets.
4. Reports the best envelope match.

The percentage is an experimental comparison, not a guarantee of intelligibility. The saved WAV is the important evidence.

## First test

1. Set Android media volume to zero or leave **Force Android media volume to zero** enabled.
2. Choose a short, strongly rhythmic song clip.
3. Select **Compressed optical PCM**.
4. Use **Scanline PCM**, white, full aperture, automatic rows.
5. Place the bright screen directly over the jar opening.
6. Enable Proof Mode.
7. Test normal and reversed scan direction.
8. Test 256, 384, 512 and 640 rows.
9. Compare the saved proof WAV files.
10. For reliable full-rate modulation, use the RP2040 USB light controller.

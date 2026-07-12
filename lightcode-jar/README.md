# LightCode Jar

Android transmitter for a physical black-interior photoacoustic jar. The app does not intentionally play the message through the phone speaker. It converts a chosen source into controlled light output.

## Sources

- Pure tone
- Typed words synthesized silently into a waveform
- Audio files decoded through Android MediaCodec
- Any file encoded as CRC-framed 600-baud AFSK data, not Morse
- Logarithmic resonance sweep

## Optical output engines

### Raster DAC screen

The experimental high-bandwidth mode. Each display frame carries a waveform slice across hundreds of horizontal bands. Differential row luminance is used so aggregate light changes during a top-to-bottom panel refresh can approximate a waveform.

### Whole-screen brightness

A conventional full-screen amplitude modulator synchronized with Android `Choreographer`. It requests the fastest display mode, maximum app brightness, immersive full screen and sustained performance.

### Camera torch pulse/strength

Uses `CameraManager` directly. On Android 13+ phones that advertise multilevel torch control, waveform amplitude is mapped onto available torch strength levels. Otherwise it uses binary pulses.

### USB-C LED DAC, 48 kHz

The hardware-bypass mode. The app sends the waveform only to a connected USB or wired audio route and refuses to start without one. That electrical waveform can drive a transistor-biased LED or LED module, producing audio-rate light modulation while the built-in phone speaker stays unused.

## Building on GitHub

Every push affecting `lightcode-jar/` triggers `.github/workflows/build-lightcode-apk.yml`.

1. Open the repository's **Actions** tab.
2. Open **Build LightCode Jar APK**.
3. Open the newest successful run.
4. Download **LightCode-Jar-debug-apk**.
5. Extract the ZIP and install `app-debug.apk`.

## Test order

1. Run **PHONE HARDWARE DIAGNOSTICS**.
2. Start a 200-800 Hz tone through Raster DAC.
3. Test both raster directions.
4. Run the resonance sweep and note the strongest regions.
5. Try typed words.
6. For the clearest words and music, use USB-C LED DAC with an external LED driver.

## Exact-file packet format

```text
48 x 0x55 preamble
"LJC1" magic
1-byte UTF-8 filename length
filename bytes
4-byte big-endian payload length
payload bytes
4-byte big-endian CRC32
```

Modulation is 600-baud AFSK, 1200 Hz mark and 2400 Hz space, generated at 24 kHz before optical output.

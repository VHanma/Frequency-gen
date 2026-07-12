# Methods used to push past ordinary phone-light limits

## Native frame scheduling

The browser prototype used `requestAnimationFrame`, CSS transitions and an assumed 16 ms timer. LightCode Jar replaces that path with a native `SurfaceView`, `Choreographer`, exact frame timestamps, Android frame-rate requests, maximum app brightness, immersive mode and the fastest physical display mode Android exposes.

## Spatial-to-temporal raster conversion

A display is addressed in rows. Raster DAC encodes many waveform samples spatially in one frame, then uses differential luminance between consecutive frames so each row update changes total emitted light. If the panel scans sequentially, the jar receives a rapid staircase of optical changes during one nominal frame.

The APK provides both row directions, automatic refresh-rate detection, adaptive gain and a whole-screen fallback because the effect depends on the actual panel driver.

## Grayscale and panel PWM

The APK drives grayscale directly rather than using Android's slow system-brightness API as the signal. On panels whose internal brightness control uses PWM, grayscale and frame transitions may interact with that carrier. The exact carrier is vendor-dependent, so the app exposes gain and diagnostic tests instead of hardcoding a claimed PWM frequency.

## Multilevel torch control

Android 13 introduced public multilevel torch-strength APIs when the camera HAL advertises more than one strength level. The app detects this at runtime and uses it automatically. Devices with one level fall back to binary torch pulses.

## USB-C audio-rate LED control

A display or camera HAL cannot be software-forced beyond its physical switching path. USB-C LED DAC changes the path entirely: Android produces a 48 kHz electrical waveform through a connected external route, and an LED driver converts it into light. No acoustic speaker is required.

## Future experiments

- Automatic microphone measurement of the jar's resonance sweep
- AFSK microphone decoder that reconstructs the original file in-app
- Per-device raster calibration using slow-motion camera footage or a photodiode
- OpenGL ES row textures and presentation timestamps for tighter scan alignment
- Root-only vendor torch PWM backends after identifying exact kernel or HAL controls for a specific phone build

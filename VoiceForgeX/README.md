# VoiceForge X
Android 16-oriented offline zero-shot voice clone vault.

Core: sherpa-onnx PocketTTS INT8. Clone references remain on-device. The app auto-downloads the Pocket model on first run, stores many clone WAV references, exposes them as Android system TTS voices, and provides a floating outside-app phrase overlay.

## Build
`gradle :app:assembleDebug`

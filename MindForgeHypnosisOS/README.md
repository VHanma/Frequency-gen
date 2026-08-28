# MindForge Hypnosis OS v2.0

Offline-first Android self-hypnosis/session-design laboratory.

## Core engine

- 10 induction modules
- 9 deepeners
- 11 suggestion architectures
- explicit if/when -> response implementation-intention layer
- cue + reset/cancellation cue programming
- future pacing, mental rehearsal, identity, compounding and optional ideomotor imagination
- editable final script before playback
- 8 quick-program presets plus blank/custom forge
- 10 audio modes: binaural, isochronic, monaural, bilateral sweep, white/pink/brown noise, drone, layered, none
- 8 visual modes: breathing orb, fixation, spiral, tunnel, pendulum, candle, focus text, dark
- Android TTS voice selection, rate and pitch controls
- pause/resume/back/next session controls
- screen brightness, wake lock, immersive player and optional haptic phase cues
- program save/duplicate/delete
- TXT and JSON export
- whole-vault backup/import
- session history and completion tracking
- 3-part post-session ratings
- adaptive tuner that ranks the user's own best-rated induction/deepener/style/audio/visual combinations
- readable-text and reduced-motion settings

All data is stored locally in WebView localStorage unless the user explicitly exports it.

## Build

Requires Java 17, Android SDK 35, and Gradle 8.10.2.

```bash
gradle :app:assembleRelease
```

The branch workflow `.github/workflows/build-mindforge-hypnosis-v2.yml` builds and uploads an installable APK artifact automatically.

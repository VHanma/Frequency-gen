# GhostShot Widget

Tiny Android 11+ floating screenshot control.

- 26dp movable overlay dot
- 2% to 50% opacity
- tap to capture
- drag to move
- long-press for Hide / Close
- reset position from the app
- PNG output to Pictures/Screenshots
- hides itself before capture
- no storage permission required on Android 11+

The screenshot feature is implemented through an AccessibilityService using Android's `takeScreenshot()` API.

# QuietShare Ultrasonic Clone Base

This branch pins two open-source Android projects as the working clone base:

- QuietShare: Android text-sharing interface over audio
- org.quietmodem.Quiet: native Android acoustic modem with audible and near-ultrasonic profiles

Pinned source revisions:
- QuietShare a1367c3039957bf117ace45a37c9b1660bd7217f
- Quiet for Android 88fa49003c7da090308346be7f01e98a6fd05fe4

The clone base can transmit text through the phone speaker and receive it through another phone's microphone. The next adaptation layer is to modernize the Android build and replace the old screens with the UltraCarrier controls for TTS, file input, carrier/profile selection, and transmission status.

Licenses remain those of the upstream projects. QuietShare source should be reviewed for its license before redistribution; Quiet for Android is BSD-3-Clause with BSD/MIT dependencies.

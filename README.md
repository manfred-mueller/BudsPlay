# BudsPlay

Control three audio tracks directly via the tap gestures of your Bluetooth earbuds — without ever touching your phone.
Optionally keep your buds awake.

*Steuere drei Audio-Tracks direkt über die Tipp-Gesten deiner Bluetooth-Ohrhörer — ganz ohne das Handy in die Hand zu nehmen. Halte deine Bluetooth-Ohrhörer optional verbunden.*

## How it works / Funktionsweise

Select three audio files (one per slot), then control playback from your earbuds:

- **Double tap right** (next track) → play Track 1
- **Triple tap right** (previous track) → play Track 2

Works with any Bluetooth earbuds that send AVRCP media button events.

## Features

- Multiple audio files per slot, selectable via dropdown
- Supports MP3, WAV, FLAC, OGG, AAC, M4A and more
- Volume slider with persistent setting
- "Keep Buds on": inaudible 16.2 kHz tone (5 s every 60 s, only while idle) prevents earbuds from auto-powering off
- Equalizer presets (Bass Boost, Vocal, Relax, Off)
- Progress bar with elapsed/remaining time and manual scrubbing
- Foreground service: playback and controls keep working with the screen locked
- Selected files, volume and EQ settings are saved across restarts
- No ads, no in-app purchases, no data collection

## Building

1. Clone the repository
2. Open the project in Android Studio (or use the Gradle wrapper)
3. Build:

   ```
   ./gradlew assembleDebug
   ```

Requirements: Android SDK 34, minSdk 26 (Android 8.0+), Kotlin.

## Privacy / Datenschutz

BudsPlay only stores the file paths of your selected audio files locally on your device. No personal data is collected or shared.

## License / Lizenz

This project is licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE).

**Exception:** The ThetaVoice logo (`app/src/main/res/drawable/thetavoice.png`) is a proprietary trademark/asset of the developer and is **not** covered by the GPL. It may not be reused outside this project.

---

*From the developer of ThetaVoice.*

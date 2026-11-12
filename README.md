# Albums Player (Android, Java)

A simple music player for Android, built on AndroidX Media3. Plays MP3, WAV, FLAC and OGG (Vorbis/Opus).

- Reads every supported music file from the device's music library (MediaStore).
- Shuffle all songs, shuffle by artist, or shuffle by album.
- Play/pause, stop, previous (long-press = rewind 10 s), next (long-press = fast-forward 10 s).
- Shows the cover art embedded in the file (ID3 / FLAC / Vorbis picture).
- Works with Android Auto: browse Artists / Albums / All songs, plus shuffle entries.
- Background playback with a media notification, lock screen and Bluetooth controls.

## Build
Open this folder in Android Studio (Koala or newer), let Gradle sync, then Run on a phone.

## Android Auto testing
Sideloaded apps are hidden in Android Auto by default:
1. Open the Android Auto settings on the phone, tap "Version" about 10 times to unlock developer mode.
2. In the overflow menu, open Developer settings and enable "Unknown sources".
3. Connect to the car (or use the Desktop Head Unit, DHU, from the Android SDK).

Open the app on the phone once first so it can ask for permission to read your music.

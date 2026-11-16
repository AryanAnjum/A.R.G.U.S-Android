# A.R.G.U.S. for Android

The Android twin of the iPhone app (same screens, same buttons, same behaviour). The phone is only
the front end: it sends your voice and pictures to the ARGUS PC on the same network and shows and
speaks the answers. Every model runs on the PC (`src/allnew.py` in Project_A-R-G-U-S).

## Get the APK without Android Studio

The GitHub Action `.github/workflows/android.yml` builds the app on every push that touches `app/`.

1. On GitHub open the repo, **Actions**, **Android app**, and the latest green run (or press **Run workflow**).
2. Download the artifact **ARGUS-Android-apk** and unzip it: you get `ARGUS-android.apk`.
3. Copy it to the phone and open it. Allow "Install unknown apps" for the Files app or browser when Android asks.

With Android Studio instead: open this folder and press Run.

## What it does

| Feature | How |
|---|---|
| Modes: Screen, Live, Assistive, Sign | Chips at the top. Voice commands like "assistive mode" work too |
| Cameras | **Back**, **Front** or **Head-mount**. Head-mount is the USB camera worn on the head and plugged into the PC; the phone shows its picture live and ARGUS answers from it |
| Language | **EN / বাং** chip at the top (or Settings → Language, or say "speak Bengali" / "speak English"). With Bengali, ARGUS speaks and answers in Bangla in every mode; the chat shows the Bangla with the English below it. The PC does the translation and the Bangla voice. Ask your questions in English |
| Screen mode | Uses this phone's own screen, never the camera. Tap **Share screen**, allow it, then open any app and ask "Hey Argus, what's on my screen?" ARGUS keeps listening while you are in other apps; stop from the notification or the app |
| Voice | "Hey Argus" wakes it, then every phrase is a question until "goodbye". "Stop" interrupts ARGUS |
| Replies | Streamed text, reply audio played sentence by sentence |
| OCR | "Read this" button, the OCR toggle, or a reading question takes a sharp 1920 px photo |
| Guide | Walking guidance for a blind user; warnings show in orange with a buzz |
| Sign language | Caption, English translation, language picker, Translate now and Clear |
| Finding the PC | Lists ARGUS PCs on the network by itself, scans the PC app's QR code, or takes a typed address |

When the PC app picks **Phone** as its camera, this phone's camera turns on for the PC as well.

## Files (`app/src/main/java/com/argus/assistant/`)

- `ArgusModel.kt`: app state, server events, voice flow, cameras, prompts and OCR photos
- `ArgusSocket.kt`: WebSocket with auto-reconnect over several PC addresses, frame dropping when busy
- `ServerFinder.kt`: finds ARGUS PCs (Bonjour `_argus._tcp`) and reads pairing links
- `CameraController.kt`: CameraX back/front camera, frame encoding, sharp photos, QR codes
- `ScreenShareService.kt`: Screen mode screen sharing (MediaProjection)
- `SpeechListener.kt`, `ReplyAudioPlayer.kt`, `Commands.kt`: voice in, voice out, wake/stop/sleep words
- `ui/ArgusScreen.kt`, `ui/SettingsDialog.kt`, `ui/ArgusIcons.kt`: the screens

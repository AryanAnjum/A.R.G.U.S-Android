# A.R.G.U.S. for iPhone

A native SwiftUI twin of the Android companion app. The phone is only the front end: it sends your
voice and the camera to the ARGUS PC on the same network and shows and speaks the answers. Every
model runs on the PC (`src/allnew.py` in Project_A-R-G-U-S), over the same WebSocket protocol the
Android app uses.

## What it does

| Feature | How |
|---|---|
| Modes: Screen, Live, Assistive, Sign | Chips at the top (`set_mode`). Voice commands like "assistive mode" work too, because the server handles them. |
| Camera frames | Back camera, upright JPEG. Live: 1024 px every 800 ms. Assistive: every 500 ms. Sign: 640 px at about 14 fps with timestamps (`slt_frame`). Screen mode sends none. |
| Voice in | Apple speech recognition, on-device when the iPhone supports it. "Hey Argus" wakes it, then every phrase is a question until "goodbye". "Stop" interrupts ARGUS. |
| Voice out | Plays the server's `tts_audio` clips (Piper WAV or edge-tts MP3) in order, on the loudspeaker or Bluetooth. Echo cancellation is on so ARGUS doesn't hear itself. |
| Streaming replies | Shows `argus_reply_delta` text as it arrives. |
| OCR | "Read this" button, the OCR toggle, or a reading question ("what does this say", "how much money", "medicine") takes a sharp 1920 px photo and sends it with the question. |
| Guide | Walking guidance for a blind user (`guide` on/off). Warnings show in orange with a haptic buzz. |
| Sign language | Live caption, English translation, signing status, language picker (German now, Bangla when ready), Translate now and Clear. |
| Metrics | VRAM, GPU temperature and last reply latency from the server. |
| Finding the PC | Lists ARGUS PCs on the same network by itself (Bonjour, `_argus._tcp`), scans the QR code from the PC app's **Connect phone** panel, or takes a typed address. It remembers the PC and follows it when its IP address changes. |

## Build it without a Mac

The GitHub Action in `.github/workflows/ios.yml` builds the app on a GitHub-hosted Mac on every push that touches `ios/`.

1. On GitHub, open the repo, go to **Actions**, then **iOS app**, and open the latest green run (or press **Run workflow**).
2. Download the artifact **ARGUS-iPhone-ipa** and unzip it. You get `ARGUS-unsigned.ipa`.

## Install it from Windows with Sideloadly

You need a free Apple ID; a paid developer account is not required.

1. Install **iTunes** (the version from apple.com, not the Microsoft Store one) and **Sideloadly** (sideloadly.io).
2. Plug the iPhone in with a cable, unlock it and tap **Trust**.
3. Open Sideloadly, drag `ARGUS-unsigned.ipa` in, type your Apple ID and press **Start**.
4. On the iPhone: **Settings > General > VPN & Device Management**, tap your Apple ID and trust it.
5. iOS 16 or newer: **Settings > Privacy & Security > Developer Mode**, turn it on, and let the phone restart.

With a free Apple ID the app stops opening after 7 days. Run Sideloadly again to refresh it (your settings are kept).

## Use it

1. Start ARGUS on the PC (`ARGUS.bat` or the desktop app). Allow it through Windows Firewall on **private** networks.
2. Put the iPhone and the PC on the same Wi-Fi, or connect the PC to the iPhone's hotspot.
3. Open the app and allow Local Network when iOS asks. With one ARGUS PC on the network it connects by itself.
4. Otherwise tap the gear and pick the PC under **PCs on this network**, or:
   - press **Connect phone** in the PC app, tap **Scan QR code** on the phone and point it at the code
     (the iPhone's own Camera app also works: it opens ARGUS from the code), or
   - type the address shown in that panel (for example `192.168.1.10`; the app fills in `ws://…:8000/ws`).
5. Allow Camera, Microphone and Speech Recognition when iOS asks. If you tapped "Don't Allow" for Local Network, turn it on in **Settings > ARGUS**.

## With a Mac instead

```
brew install xcodegen
cd ios && xcodegen generate && open ARGUS.xcodeproj
```

Pick your Apple ID under Signing & Capabilities, choose the iPhone and press Run.

## Files

- `ARGUS/ArgusViewModel.swift`: app state, server events, voice flow, prompts and OCR photos
- `ARGUS/ArgusSocket.swift`: WebSocket with auto-reconnect, several addresses per PC, and frame dropping when the link is busy
- `ARGUS/ServerFinder.swift`: finds ARGUS PCs on the network and reads pairing links (`argus://connect?h=…&p=8000&n=…`)
- `ARGUS/CameraManager.swift`: camera preview, frame encoding, sharp photos, QR code reading
- `ARGUS/SpeechListener.swift`: continuous speech recognition and the audio session
- `ARGUS/ReplyAudioPlayer.swift`: plays reply audio in order
- `ARGUS/Commands.swift`: wake word, stop, sleep and reading-question rules (same as Android)
- `ARGUS/ContentView.swift`: the UI and the settings screen
- `project.yml`: XcodeGen project spec (the Xcode project is generated, not committed)

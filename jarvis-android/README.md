# Jarvis — voice assistant for Android

A Jarvis-style assistant that listens for "Jarvis", checks that it's your voice, and controls the phone.

## Get the APK
Every push to `jarvis-android/` builds the app on GitHub Actions.
- **Easiest:** open the repo's **Releases** page → *Jarvis (latest build)* → download `Jarvis.apk`.
- **Or:** **Actions** tab → *Build Jarvis APK* → latest run → *Artifacts* → `Jarvis-APK` (a zip with the APK inside).

Install it on the phone (allow "Install unknown apps" for your browser/file manager).

## First-time setup (inside the app)
1. Grant microphone, contacts, calls, SMS.
2. Enable **Jarvis phone control** in Accessibility settings (needed for tapping, scrolling, typing, screenshots, lock screen, and launching apps from the background).
3. Allow *Display over other apps*.
4. Allow *Modify system settings* (brightness).
5. Turn off battery optimization for Jarvis (keeps the wake word alive).

Optional — **AI brain:** paste a Claude API key (console.anthropic.com → API Keys) into *Preferences* and tap Save. Questions and anything Jarvis doesn't recognize as a phone command go to Claude (`claude-opus-5-5`, low effort, with web search for current facts). Usage is billed to your Anthropic account.

Then: **Record my voice** → **Test a voice** → turn on **Only obey my voice** → turn on **Always listen for "Jarvis"**.

## How it works
| Part | File |
|---|---|
| Wake word + listening loop (foreground service) | `JarvisService.kt` |
| Command understanding and actions | `CommandProcessor.kt` |
| Tapping, scrolling, typing, global actions | `JarvisAccessibilityService.kt` |
| AI answers (Claude API via the Java SDK) | `AiBrain.kt` |
| Offline voiceprint (MFCC + cosine similarity) | `VoicePrint.kt` |
| Speech output | `Speaker.kt` |
| UI | `MainActivity.kt`, `res/layout/activity_main.xml` |

## Honest limits
- Speech recognition uses the phone's Google speech service; commands are English.
- The voice lock is a basic offline voiceprint. It stops other people casually giving orders, but a recording of your voice can fool it. Keep your normal screen lock on.
- Android does not let normal apps toggle Wi-Fi (Android 10+) or mobile data directly; Jarvis uses the Quick Settings tile through Accessibility or opens the right settings panel.
- The always-listening mode restarts Android's recognizer in a loop, so some phones play a short beep each cycle and it uses more battery.

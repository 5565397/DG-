# Voxa — voice assistant for Android

Say "Voxa" (or "ووکسا" / "वोक्सा") and control your phone by voice in Urdu, Hindi or English.

## Get the APK
Every push to `jarvis-android/` runs the command tests and builds the app on GitHub Actions.
- **Easiest:** Releases → *Voxa (latest build)* → `Voxa.apk`
  (direct link: `https://github.com/5565397/DG-/releases/download/voxa-latest/Voxa.apk`)
- **Or:** Actions → *Build Voxa APK* → latest run → Artifacts → `Voxa-APK`.

## What works without any API key
Speech recognition (Google's free recognizer) and speech output (the phone's text-to-speech voice) need no key.
All phone commands are understood offline in Urdu, Hindi, Roman Urdu/Hindi and English:
apps (by Urdu/Hindi name too), calls and SMS/WhatsApp to contacts (names matched across scripts by sound),
alarms with spoken times ("شام ساڑھے پانچ بجے"), timers, reminders, memory, flashlight, volume, brightness,
quick-settings toggles, media, YouTube, navigation, screenshots, lock, notifications, screen reading.
Replies are spoken in the chosen language. Anything else opens a Google search.

An optional Claude API key adds open-ended answers, screen vision and the weather/news briefing.

## First-time setup (in the app → Settings)
Finish the six Setup rows, pick the language, and install the Urdu/Hindi voice if Settings says it's missing.

## Code map
| Part | File |
|---|---|
| Listening loop, wake word, voice lock flow | `JarvisService.kt`, `WakeWord.kt` |
| Command understanding (pure, unit-tested) | `CommandParser.kt`, `Hindustani.kt`, `Translit.kt` |
| Carrying out commands, spoken replies | `CommandProcessor.kt`, `Lang.kt` |
| On-screen control, screenshots | `JarvisAccessibilityService.kt` |
| Notifications | `JarvisNotificationListener.kt` |
| Optional AI | `AiBrain.kt` |
| UI | `MainActivity.kt`, `OrbView.kt`, `SettingsActivity.kt` |
| Tests | `app/src/test/…/CommandParserTest.kt`, `UrduOfflineTest.kt` |

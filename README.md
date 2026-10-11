# Boba — your Jarvis for Android (prototype v0.1)

Voice assistant that opens any app, calls and messages your contacts, plays music,
searches, sets alarms, and controls the whole phone (tap, type, scroll, back, home,
screenshot, lock). Commands run offline and instantly. No API key needed.
An optional free AI key adds open questions and coding help.

## Get the APK — from your phone, no laptop (≈10 minutes)

1. Make a free account at github.com (works in the phone browser).
2. Create a new repository called `Boba`.
3. Tap **Add file → Upload files** and upload everything from this zip
   (keep the folders: `app/`, `.github/`, and the files at the top).
   Tip: the GitHub mobile site can't upload folders well — easiest is the
   **GitHub app** or "Desktop site" mode in Chrome.
4. Open the **Actions** tab. "Build Boba APK" runs by itself (≈4 min).
5. Open the finished run → **Artifacts → Boba-apk** → download, unzip, tap
   `app-debug.apk` → allow "install unknown apps" → Install.

With a laptop instead: open the `Boba` folder in Android Studio → Run.

## First-time setup on the phone (1 minute)

1. Open Boba → allow Microphone, Phone, Contacts, SMS, Notifications.
2. Tap **Turn on phone control** → Installed apps → Boba → On.
   (This is what lets Boba tap Send in WhatsApp and control other apps.)
3. Optional: **AI brain** → paste a free key from aistudio.google.com.
4. Quick access from anywhere:
   - Pull down notifications → tap **Boba is ready**, or
   - Edit Quick Settings tiles → drag **Boba** in → one tap to talk.

## Things to say

| Say | What happens |
|---|---|
| open WhatsApp / camera / YouTube | Opens any installed app (understands mishearings) |
| call Mom · call 98765 43210 | Calls directly |
| message Rahul saying I'm on the way | WhatsApp, asks "yes?" then sends itself |
| text Papa saying call me | SMS, confirms first |
| tell Priya that I'll be late | WhatsApp shortcut |
| play Arijit songs · play lo-fi on Spotify | Starts playing |
| search CNC companies in Vadodara | Google search |
| navigate to Makarpura GIDC | Google Maps directions |
| set alarm for 6:30 am · set timer for 10 minutes | Clock app, no taps |
| flashlight on/off · volume 50 · mute | Hardware |
| wifi · bluetooth · mobile data | Opens the system toggle |
| go home · go back · recent apps · notifications | System navigation |
| scroll down · tap Login · type hello · read the screen | Controls whatever app is open |
| take a screenshot · lock the phone | Android 9+ |
| battery · time · date · help | Info |

Anything else → AI brain (or Google search if no key).

## Files

| File | Job |
|---|---|
| `MainActivity.kt` | Screen, mic, speech-to-text, text-to-speech |
| `CommandEngine.kt` | Understands sentences → picks an action. **Add new commands here.** |
| `Actions.kt` | Calls, WhatsApp, SMS, apps, alarms, torch, volume… |
| `NovaAccessibilityService.kt` | Whole-phone control: tap, type, scroll, auto-press Send |
| `Brain.kt` | Optional AI answers (Gemini free tier) |
| `NovaNotification.kt` | Pinned notification + Quick Settings tile |

## Roadmap (next upgrades)

1. Always-on wake word "Hey Boba" (offline, Vosk/Porcupine) — no tapping at all
2. Hindi + Gujarati commands
3. Read & reply to incoming WhatsApp/SMS by voice
4. Multi-step tasks ("open Instagram and search CNC") via AI planning
5. Link with the laptop Jarvis (send commands phone ↔ PC)
6. Floating bubble over every app

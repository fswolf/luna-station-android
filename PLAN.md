# Luna Android: plan for the agent building it

Started 2026-11-10 from a conversation between Ryan and Claude. This is the outline to work
from; nothing is built yet. Read it all before writing code, and ask Ryan before changing the
plan.

## What this is

An Android app that turns a phone or tablet into a relay station for Luna (the main project:
github.com/fswolf/luna-local-agent, on Ryan's machine at ~/ai-voice). It is its own repo,
published separately (repo name: luna-station-android, MIT licence like Luna).

Luna already has relay stations: a web page she serves on the home network
(web/station.py and web/station.html in the Luna repo). You open it on a phone, tap, talk, and
her reply plays there. The app exists because a web page can't do the things a station in a
room needs:

- **Listen in the background.** Browsers cut the microphone when the screen sleeps or the page
  isn't in front, so a page can't wait for "hey Luna".
- **Start on boot,** so a tablet on a wall or a shelf just works after a power cut.
- **Ring her alarms and reminders** as real notifications, even with the screen off.
- **Use the camera** for presence in that room (later).

Luna does all the thinking on the PC or server. The app stays small: microphone in, voice out,
a screen to read and answer on.

## Direction (Ryan, 2026-11-14)

The station app is the room device: an open-source Echo Show. Her portrait, mic, speaker and,
later, a camera. Build the app we want and let the hardware catch up: a phone or tablet on a
stand runs it today, something like a Pi touchscreen can come later.

## Target devices

- **Amazon Fire tablets** (Fire HD 8 / 10) are the main target: cheap, with a mic, camera and
  speaker, and on a stand they are an Echo Show you control. Fire OS 8 is based on Android 11
  (API 30). Fire tablets have **no Google Play services**, so nothing may depend on Firebase,
  FCM or Play services. Install is a sideloaded APK.
- Old Android phones and tablets: the same APK.
- Echo devices are NOT a target: Amazon locks them (no app installs, the mic belongs to Alexa).

Suggested: minSdk 30 (Fire OS 8), targetSdk current.

## How it talks to Luna (the existing station protocol)

All of this already exists in Luna's web/station.py. Use it as it is first; anything new on
Luna's side is listed further down and goes to Ryan, not straight into the Luna repo.

Two servers on the PC, home-network addresses only (private, link-local, loopback):

- **Setup page, plain HTTP on station.port + 1** (default 8794): `GET /` (setup page),
  `GET /luna-ca.crt` (Luna's own certificate authority, which every station trusts once).
- **Station, HTTPS on station.port** (default 8793), certificate signed by that authority.

Pairing:

- On the PC: settings page, Relay stations, "Add a phone or tablet" shows a QR code with a
  one-time code (single use, 15 minutes). The QR holds the **setup page** link,
  `http://<pc-ip>:8794/?code=<code>` (checked against web/station.py `setup_url()`), which in
  the browser links on to `https://<pc-ip>:8793/pair?code=<code>`. For the app that one scan
  gives everything: the PC's address, the setup port (for `luna-ca.crt`), the station port
  (setup port minus one) and the code.
- `GET /pair?code=...` answers 303 with a `luna_station` cookie: the device's token. The token
  is the device's key from then on (stored hashed on the PC; unpaired from the settings page).
- `POST /resume` with JSON `{"token": "..."}` gives the cookie back if it was lost.
- The app should scan the QR itself (camera) and keep the token in its own encrypted storage.

A turn:

- `POST /talk` with `Content-Type: audio/wav`, a RIFF WAV body (16 kHz mono 16-bit is plenty;
  max 6 MB). Or `POST /say` with JSON `{"text": "..."}` (max 2000 characters).
  Answer: `{"turn": "<id>"}`. 409 if this device's last turn hasn't finished; 503 if stations
  are switched off on the PC.
- Poll `GET /turn/<id>`: JSON with `state`, `heard` (what Whisper heard), `reply` (her words
  so far), `clips` (how many reply audio clips are ready), `ask` (a permission question waiting
  for an answer, or empty), `error`. Poll about every half second while a turn runs.
- `GET /clip/<id>/<n>`: reply audio clip n as WAV. Play them in order as they appear: she
  starts talking a sentence in, so clip 0 arrives while she's still writing.
- `POST /stop/<id>`: stop her (the Stop button).
- `POST /answer/<id>` with JSON `{"allow": true|false}`: her permission questions (writing a
  file, running a command) are answered on the station that asked, never automatically.
  Unanswered questions time out after 120 s as a no.
- Finished turns are forgotten on the PC after 5 minutes.

Other rules that hold today:

- POSTs with an `Origin` header must be same-origin. A native app usually sends no Origin,
  which is fine; don't invent one.
- Typed turns count as the owner's (no voice to check); spoken turns are checked against her
  voice prints like any other, so the recording should be clean, real audio.

## Certificate trust

Luna's certificate is signed by her own small authority, not a public one. Don't ask users to
install a CA into Android's settings (Fire OS makes that awkward, and Android 7+ apps ignore
user CAs anyway). Instead:

- At pairing, fetch `http://<pc-ip>:8794/luna-ca.crt` and trust only that authority for the
  station connection (a custom TrustManager / network security config scoped to the PC's
  address). Show its fingerprint so the user can compare it with the PC if they want to.
- Never fall back to "trust all certificates".

## Architecture (suggested; agent may propose better with reasons)

- Kotlin, Jetpack Compose for the UI, Gradle Kotlin DSL. No Play services.
- **StationService:** a foreground service (type microphone, plus camera later) that owns
  the mic, the wake word, the turn loop and playback. Started from the app UI and on boot.
  Android 14 requires a microphone foreground service to be started while the app is visible;
  the boot path needs checking on each Android version.
- **Audio in:** AudioRecord, 16 kHz mono PCM, echo cancellation / noise suppression effects
  where the device has them. Encode WAV in-app.
- **End of speech:** Silero VAD (ONNX, the same model family Luna uses on the PC) through
  ONNX Runtime for Android, with an energy fallback. Tap-to-talk and tap-to-stop always work.
- **Wake word:** openWakeWord models (ONNX, the same ones Luna's desktop wake word uses) via
  ONNX Runtime. Off by default; "hey Luna" needs a trained model (Luna's docs say how).
  Avoid Porcupine and other services that need an account or a key.
- **Playback:** queue the reply clips; barge-in later (talk over her and she stops, by
  calling /stop).
- **UI:** like the web page: a message list (what you said, her reply filling in), a big talk
  button, a text box, Stop, and permission questions as Allow / Deny cards. A kiosk-style
  "station mode" for tablets: screen dim, always on, big status.
- **Storage:** paired PC address and token, encrypted with a key from the Android Keystore
  (EncryptedSharedPreferences is deprecated). No other data kept;
  no history on the device beyond the current screen.
- **Network:** OkHttp. Handle the PC being off or asleep calmly: a quiet "can't reach Luna"
  state and retries with back-off, never a crash or a stuck spinner.

## Things Luna's side would need (notes for Ryan, NOT to be changed in the Luna repo directly)

1. **A way to push to stations:** alarms, reminders and her own offers currently only reach
   the PC. Simplest: a long-poll or Server-Sent Events endpoint (`GET /events`) that a paired
   device keeps open; the app turns events into notifications and plays the sound. Must work
   without Google's push service (Fire tablets).
2. **A small info endpoint** (`GET /info`: Luna's version, station API version, her name) so
   the app can tell an old Luna from a new one.
3. **Room name / device type** sent at pairing ("kitchen tablet"), shown in Luna's terminal as
   the room, like the web stations.
4. Later: camera frames for presence in that room.

Write these up as an issue or a pull request against luna-local-agent for Ryan to review.

## Phases

1. **Skeleton:** repo, Gradle, CI that builds a debug APK on every push (GitHub Actions),
   README, licence.
2. **Pair and type:** QR pairing, certificate trust, `/say` turns, reply text, Stop.
3. **Talk:** tap-to-talk recording to `/talk`, clip playback as it arrives, permission cards.
4. **Station mode:** foreground service, start on boot, VAD end of speech, kiosk screen.
5. **Wake word:** openWakeWord on device, hands-free.
6. **Notifications:** alarms and reminders, once Luna has the push endpoint.
7. **Release:** signed APK on GitHub Releases. F-Droid and the Amazon Appstore later, maybe.

Each phase should work on its own before the next starts.

## Testing

- A fake station server (a small Python or Kotlin test server speaking the protocol above,
  with a self-made CA) so CI can test pairing, turns, clips, questions and errors without Luna.
- Unit tests for WAV encoding, the turn poller, and the certificate pinning (a wrong CA must
  fail).
- Real-device checks Ryan does: a Fire HD tablet and an old phone; screen off; reboot; PC
  asleep and waking; Wi-Fi dropping.

## Rules for working in this repo

- Ryan's public-text style (README, docs, release notes, comments meant for users): plain
  words, no em dashes, no claims that aren't backed by something that was actually run, plain
  lists with bold labels. Say what was tested and what wasn't.
- Never commit secrets, tokens, keystores or signing passwords. The release keystore stays out
  of git.
- Don't change files in the Luna repo (~/ai-voice) from here; write down what Luna needs (the
  list above) and let Ryan decide.
- Ask before adding a dependency that phones home, needs an account, or needs Play services.
- Ask when something is unclear rather than guessing.

## Decided (2026-11-10)

- **Package name:** `imbored.luna.station`.
- **App name:** Luna. Icon: a lavender crescent with her pink dot, on the site's dark background.
- **Minimum Android version:** 11 (API 30, Fire OS 8). compileSdk 37 (Compose 1.12 needs it),
  targetSdk 36.
- **Build setup:** AGP 9.3.1, Gradle 9.5.0 and AGP's built-in Kotlin, copied from a project
  Ryan's Android Studio generated; Compose compiler plugin 2.4.10, Compose BOM 2026.09.00.
- **QR scanning:** ZXing core, not ML Kit. ML Kit's terms say its APIs send usage metrics to
  Google even in the bundled variant, which breaks the no-phoning-home rule.
- **Phase 2 split:** 2a pairing on its own (scan or type, CA fetch, /pair, Keystore-encrypted
  token, a reachability check), then 2b the conversation screen and /say.
- **Token storage:** AES-GCM with a key held in the Android Keystore (station/StationStore.kt);
  androidx security-crypto is deprecated.
- **HTTP:** HttpURLConnection throughout (no extra dependency); the turn loop polls like the web
  station (350 ms, gives up after 40 failed polls in a row).
- **Typing and talking (2b + 3) built together** on 2026-11-13 while Luna was down, tested against
  tools/fake_station.py instead. Mic: VOICE_RECOGNITION source (no auto gain, cleanest for Whisper
  and voice prints), 16 kHz mono; stops after 1.3 s of quiet following speech, 8 s of nothing, or
  30 s (the web page's rules), with a 0.015 RMS threshold to tune on real devices. Reply clips play
  through AudioTrack on the media volume. No conversation history kept on the device.
- **How we work:** Claude writes the code in ~/luna-station-android, Ryan builds and runs it in Android
  Studio and reports errors or what the device does.

- **Her portrait (2026-11-14):** Luna serves it to paired devices at `/portrait/` on the station
  port (401 otherwise), with a cut-down event stream (only what the portrait acts on). The app
  shows it in a WebView (ui/PortraitView.kt): pairing token set as a cookie, JavaScript and DOM
  storage on, media without a gesture. The WebView has its own TLS checks, so its SSL errors
  go ahead only for her address and port with a server certificate signed by the paired CA
  (LunaCa.vouchesFor); links can't leave her page. The app posts `/playing/<turn>/<n>` as each
  clip starts so her mouth matches the voice on the device. Chat | Portrait switch at the top
  (named Portrait, not Her: not every avatar is female, or gendered at all); one shared turn.
  The WebView is torn down when leaving Portrait, which closes her event stream.

- **Camera (next, not yet):** Luna will get an endpoint that takes one picture when someone talks
  to the station, matches it against her face prints and drops it, so she knows who's in that
  room. The app grabs one frame on talk and shows a visible marker whenever the camera is used.
  Not before the portrait and pairing are tested against a real Luna.

## Still open

- **The PC's address changing.** The app stores the IP it paired with. If the router gives the PC
  a new one, Luna re-issues her certificate for it, but the app can't find her and says she's
  unreachable; pairing again fixes it. Better later: Luna announcing itself on the home network
  (mDNS) or a /info endpoint, and the app following the move. A reserved address on the router
  avoids it today.
- **targetSdk 37.** Check what Android 17 requires before raising it. It may add a permission for
  reaching devices on the local network, which every call to Luna is. The app targets 36 for now.
- How her 3D portrait runs on a cheap Fire tablet (WebGL on a weak GPU). Test early.

# Working on Luna Station

How this code is written, for anyone who picks it up: a person, an AI agent, or the
maintainer a year from now. Read it once before changing anything. Most of the app was written
with AI help and reviewed by hand; the rules below are what keeps it readable either way.

## The idea in one paragraph

The app is a screen, a mic and a speaker for Luna. All the thinking happens on the user's own
PC. The app talks to that one PC on the home network and nothing else. Every change should keep
it that way.

## Where things are

```
app/src/main/java/imbored/luna/station/
  MainActivity.kt        starts the app, picks the screen
  StationViewModel.kt    the app's state: pairing, the conversation, the mic, the link
  station/               talking to Luna
    PairLink.kt            reading the pairing link or a typed address
    LunaCa.kt              Luna's own certificate, and what it may vouch for
    StationClient.kt       the station protocol (pair, say, talk, poll, clips, stop, answer)
    StationStore.kt        the saved pairing, encrypted with a Keystore key
  audio/                 the mic and the speaker
    Recorder.kt            records while you talk
    SpeechGate.kt          decides when you've stopped talking
    ClipPlayer.kt          plays her voice, clip by clip
    Wav.kt                 WAV in and out
  ui/                    one file per screen, plus Common.kt, Theme.kt, EmblemMenu.kt
                         (the top-left menu) and Prefs.kt (this device's settings)
app/src/test/            unit tests (pure logic only, no device needed)
tools/fake_station.py    a stand-in for Luna's station server, for testing
tools/ci/                the GitHub build and release workflow, and how to set it up
```

One file does one job. If a file starts doing two, split it. If a new feature needs a new
area (the camera, say), it gets its own package beside `station/` and `audio/`.

## Rules that aren't up for debate

- **Only the paired PC.** No calls to the internet: no analytics, no crash reporting, no
  Google services (that's why QR codes are read with ZXing and not ML Kit). Fire tablets have
  no Play services, and users were promised privacy.
- **Only Luna's certificate.** HTTPS to the station trusts Luna's own CA and nothing else.
  `LunaCa.vouchesFor` is the one place that decides; don't add a second path that trusts more.
- **Home addresses only.** `PairLink.isHomeAddress` accepts literal private IPs, never a
  name, so pairing can never trigger a DNS lookup.
- **No secrets in git.** Keystores, passwords, `local.properties` and build output stay out.
  `.gitignore` covers them; if you add a new kind of secret, add it there first.
- **The mic is only on while the user is talking, the camera only while scanning.** Anything
  that would change that needs the maintainer's yes first.
- **Nothing stored that doesn't have to be.** The pairing is saved; the conversation is not.

## Writing the code

- **Plain names.** `finish`, `hush`, `forget`, `check`. A name should say what happens, in
  the words a user would use.
- **Comments say why.** The code says what. A class gets a short doc comment saying what it
  is for and, if it mirrors something in Luna, which file there (`web/station.py`). Numbers
  that came from somewhere (the 1.3 s of quiet, the 350 ms poll) say where.
- **Small and direct.** No layers that only pass calls through, no interfaces with one
  implementation, no framework for a thing done once.
- **Dead code is commented out, not deleted,** with a line saying why it stopped being used.
  It's cleaned up later on purpose, not lost by accident.
- **Errors in plain words.** Whatever reaches the screen says what went wrong and what to do
  ("Can't reach the PC. Is Luna running?"), never a stack trace or an exception name.
- **Android 11 (API 30) is the floor.** Check the API level of anything new; the build won't
  always warn you (`InputStream.readNBytes` needs 33, for example, which is why `readCapped`
  exists).
- **New libraries need a reason.** Ask first. Versions live in `gradle/libs.versions.toml`,
  nowhere else.

## Talking to Luna

The station protocol belongs to Luna (`web/station.py` in
[luna-local-agent](https://github.com/fswolf/luna-local-agent)). The app follows it; it
doesn't change it. If the app needs something Luna doesn't offer yet, write down what and why
in an issue for the maintainer to decide on. Don't change both sides in one go.

## Testing

- **Unit tests** for anything that's pure logic: parsing, certificates, audio maths.
  `./gradlew testDebugUnitTest`. A bug fixed there gets a test that would have caught it.
- **The fake station** for everything that talks to Luna: `tools/fake_station.py` (setup is
  in the README). It has words that trigger the awkward cases: `ask`, `slow`, `error`.
- **A real device** before calling it done. Wi-Fi, a real mic and a real speaker find things
  nothing else does.

## Words people will read

The README, release notes, screen text and anything else public:

- Plain and short. Say what it does for the user, not how clever it is.
- No em dashes. Use a full stop, a comma, a colon or brackets.
- No hype words ("seamless", "powerful", "revolutionary").
- Screen text is for someone who has never seen the code.

## Commits and pull requests

- One change per commit, with a message that says what changed for the user or the code
  ("Stop the mic when the app goes to the background"), not "fixes".
- Don't add co-author or attribution lines unless the maintainer asks for them.
- Pull requests: say what changed, why, and how you tested it (which device, fake station or
  real Luna).

## For AI agents

Everything above applies. Also:

- Read the files you're about to change, and the ones that call them, before writing.
- Talk changes through with the maintainer before making them, especially anything that
  changes how the app behaves, what it trusts, or what it stores.
- Match what's here. If something here looks wrong, say so; don't quietly do it differently.
- Never commit or push on your own. The maintainer does that.

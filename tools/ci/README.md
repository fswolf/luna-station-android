# Building on GitHub

`release.yml` here builds the app on GitHub's machines:

- **Every push:** a debug APK and the unit tests, so a broken build shows up right away.
- **A version tag** (`v0.2.0`): a signed release APK on a Release page anyone can download.

## Setting it up (once)

**1. Move the workflow where GitHub looks for it.** `publish-github.sh` does this on its first
run (it moves `tools/ci/release.yml` to `.github/workflows/release.yml`); by hand:

```bash
mkdir -p .github/workflows && mv tools/ci/release.yml .github/workflows/release.yml
```

**2. Make the release key.** Android only installs an update over an older version when both
are signed with the same key, so this key is made once and kept for the life of the app. Lose
it and every user has to uninstall (and pair again) to get the next version.

```bash
keytool -genkeypair -v -keystore luna-station-release.jks -alias luna-station \
  -keyalg RSA -keysize 4096 -validity 10000
```

`keytool` comes with any JDK; Android Studio has one at `<android-studio>/jbr/bin/keytool`.
(Android Studio can also make it: Build, Generate Signed App Bundle or APK, Create new.)

Keep `luna-station-release.jks` and its passwords somewhere safe and backed up, outside the
repo. `.gitignore` already refuses `*.jks`, `*.keystore` and `keystore.properties`.

**3. Give it to GitHub as secrets:** the repo's Settings, Secrets and variables, Actions, New
repository secret. Four of them:

| Secret | What |
|--------|------|
| `LUNA_KEYSTORE_BASE64` | the key file as text: `base64 -w0 luna-station-release.jks` |
| `LUNA_KEYSTORE_PASSWORD` | the keystore password |
| `LUNA_KEY_ALIAS` | `luna-station` (the `-alias` above) |
| `LUNA_KEY_PASSWORD` | the key password (the same as the keystore's, unless you set another) |

Secrets can't be read back, not even by you, and pull requests from forks don't get them.

## Making a release

```bash
./publish-github.sh --tag v0.1.0
```

(or by hand: `git tag v0.1.0 && git push origin v0.1.0`)

The workflow runs the tests, builds and signs the APK, and makes a Release named after the tag
with `luna-station-0.1.0.apk` and its SHA-256 checksum. The version number in the app comes from
the tag; the internal version code from the run number, which only goes up, so each release
installs over the last one.

## Building a signed APK yourself

The same environment variables work locally:

```bash
LUNA_KEYSTORE=/path/to/luna-station-release.jks LUNA_KEYSTORE_PASSWORD=... \
LUNA_KEY_ALIAS=luna-station LUNA_KEY_PASSWORD=... LUNA_VERSION=0.1.0 \
./gradlew assembleRelease
```

Without them, `assembleRelease` makes an unsigned APK (which Android won't install), and debug
builds from Android Studio are unaffected.

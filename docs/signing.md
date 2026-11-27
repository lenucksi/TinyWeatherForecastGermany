# Release signing (TASK-12 / decision D-05)

No keystore, key alias or password is stored in this repository. The release
build type uses a `signingConfig` whose four values are read from environment
variables at build time. CI supplies them from GitHub Environment secrets; the
workflow materializes the keystore only in the runner's temporary directory
(mode `0600`) and deletes it afterwards.

## Environment variables

| Variable | Meaning |
| --- | --- |
| `RELEASE_KEYSTORE_FILE` | Absolute path to the JKS/PKCS#12 keystore file |
| `RELEASE_STORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias inside the keystore |
| `RELEASE_KEY_PASSWORD` | Password for that key |

`app/build.gradle` defines the `release` signing config from these variables.
If they are unset, debug and userdebug builds are unaffected — neither variant
references the release signing config. A release build with missing/invalid
values fails in Gradle (and much earlier, with explicit messages, in CI).

## CI

`.github/workflows/release.yml` runs on `workflow_dispatch` and on `v*` tags.
It uses the `release` GitHub Environment and requires these secrets there:

- `RELEASE_KEYSTORE_BASE64` — base64 of the keystore file (`base64 -w0 key.jks`)
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

The workflow verifies all four secrets are present before building, decodes the
keystore to `$RUNNER_TEMP` with `umask 077`, runs
`:app:assembleRelease -Dorg.gradle.dependency.verification=strict`, verifies the
APK with `apksigner verify --verbose --print-certs`, uploads the signed APK as
an artifact, and removes the temporary keystore in an `if: always()` step.

**TASK-27** creates the `release` environment and stores the four secrets.
**TASK-28** can then consume the workflow's `signed-release-apk` artifact for
publication.

## Local verification (temporary key, outside the repo)

```bash
# Use a cache directory outside the checkout; never add a keystore to git.
TMP=/var/home/j/.cache/tmp/opencode/task12-keys
mkdir -p -m 0700 "$TMP"
keytool -genkeypair -v -keystore "$TMP/verify.jks" -storetype JKS \
  -alias verify -keyalg RSA -keysize 2048 -validity 365 \
  -storepass verify-store -keypass verify-key \
  -dname "CN=Twfg local verification"

export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk
export RELEASE_KEYSTORE_FILE="$TMP/verify.jks"
export RELEASE_STORE_PASSWORD=verify-store
export RELEASE_KEY_ALIAS=verify
export RELEASE_KEY_PASSWORD=verify-key

./gradlew :app:assembleRelease
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-release.apk

# Debug / userdebug need no signing environment at all:
unset RELEASE_KEYSTORE_FILE RELEASE_STORE_PASSWORD RELEASE_KEY_ALIAS RELEASE_KEY_PASSWORD
./gradlew :app:assembleDebug :app:assembleUserdebug

rm -rf "$TMP"
```

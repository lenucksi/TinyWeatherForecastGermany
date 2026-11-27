# Running and adding tests (TASK-16)

The JVM test suite is the PR gate. No emulator, device or network access to
DWD is required.

## Dependencies and configuration

`app/build.gradle` owns the test setup:

- `testImplementation 'junit:junit:4.13.2'` and
  `testImplementation 'org.robolectric:robolectric:4.16'` (the Robolectric
  version matching AGP 8.12.x).
- `android.testOptions.unitTests.includeAndroidResources = true` so Robolectric
  sees the merged manifest, layouts and `res/raw` fixtures.
- `unitTests.all { jvmArgs '--add-opens=...' }` opens the JDK 21 bootstrap
  packages Robolectric reflects into. Do not remove these; without them tests
  fail with `InaccessibleObjectException` before any assertion runs.
- The presence of a `testImplementation` line enables the previously guarded
  unit-test step in `.github/workflows/build.yml`.
- AndroidX Test is pinned to `monitor:1.7.2` /
  `espresso-idling-resource:3.6.1` for test configurations: Robolectric 4.16's
  defaults raise `minSdk` to 21, which breaks the test manifest merge while
  this app still supports API 19.
- Robolectric's four `android-all-instrumented` jars (SDK 26/28/33/36) are
  resolved as ordinary Gradle dependencies into `app/build/robolectric-android-all`
  and handed to Robolectric via `robolectric.dependency.dir`. They are no
  longer downloaded at test runtime, so tests are deterministic and the jars
  are covered by strict dependency verification. A content-restricted mirror
  (Google's read-through mirror of Maven Central, `org.robolectric` group only)
  avoids Maven Central HTTP 429 throttling in CI.

`gradle/verification-metadata.xml` is in strict mode. **Any dependency change
must be followed by regenerating it** (see below), or CI fails with a
dependency-verification error.

## Run locally

The one supported way to build here is `scripts/build.sh` (podman container,
own Gradle cache):

```bash
scripts/build.sh :app:testDebugUnitTest
scripts/build.sh :app:lintDebug
```

From a normal Android toolchain the equivalent is
`./gradlew :app:testDebugUnitTest :app:lintDebug`.

HTML reports: `app/build/reports/tests/testDebugUnitTest/index.html` and
`app/build/reports/lint-results-debug.html`.

## Add a test

Put Java sources under `app/src/test/java/de/kaffeemitkoffein/tinyweatherforecastgermany/`.

- Pure JVM logic (date math, mappings, state machines, parsing of plain
  strings): plain JUnit4 with `@Test` and `org.junit.Assert`. Example:
  `ForecastBlockTimeTest`, `StationNameMappingTest`, `WeatherWarningAlertStateTest`.
- Anything touching Android framework classes (`android.text.TextUtils`,
  `Context`, `Build.VERSION.SDK_INT`, resources): annotate the class with
  `@RunWith(RobolectricTestRunner.class)`. Example:
  `WeatherContentManagerSerializationTest`.
- Version-branch proof: a Robolectric test may pin SDK levels with
  `@Config(sdk = {26, 28, 33, 36})`. `ReceiverRegistrationGuardTest` uses this
  to prove the receiver guard on API 26/28 (where the flagged overload does not
  exist) as well as API 33/36. A live API 37 emulator cannot prove those older
  branches.
- Static input fixtures belong in `app/src/test/resources/fixtures/` and are
  read from the test classpath (`getResourceAsStream("/fixtures/...")`). See
  `TextForecastParserTest`.
- Robolectric's `ShadowCanvas` does not reproduce custom-drawn bitmap colours.
  Assert the extracted math/state instead of pixels.

Keep tests deterministic: fix `TimeZone`/`Locale` in `@Before` and restore them
in `@After` when the code under test is time- or locale-sensitive.

## Lint baseline

`app/lint-baseline.xml` freezes 439 pre-existing findings (424
`MissingTranslation`, 14 `MissingInflatedId`, 1 `WrongConstant`) from before
TASK-16. `app/build.gradle` sets `lint { baseline = file('lint-baseline.xml');
abortOnError = true }`, so **new** findings still fail `:app:lintDebug`.
CI runs that task without `continue-on-error`.

To regenerate the baseline after an intentional change, run from a build with
the lint baseline configured:

```bash
scripts/build.sh :app:lintDebug -Dlint.baselines.continue=true
```

Review the resulting diff before committing: only pre-existing, understood
findings belong in the baseline.

## Regenerating verification metadata

Use the **clean** Gradle home so the sandbox init script (which swaps AGP for
8.13.2) is not applied, and include the test tasks so test dependencies are
captured:

```bash
TWFG_REPO=/var/home/j/kit/mobile/TinyWeatherForecastGermany \
TWFG_GRADLE_HOME=/var/home/j/.gradle-twfg-clean \
bash scripts/build.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  --write-verification-metadata sha256 --rerun-tasks
```

Commit the regenerated `gradle/verification-metadata.xml`, then verify:

```bash
TWFG_REPO=/var/home/j/kit/mobile/TinyWeatherForecastGermany \
TWFG_GRADLE_HOME=/var/home/j/.gradle-twfg-clean \
bash scripts/build.sh :app:testDebugUnitTest :app:lintDebug \
  -Dorg.gradle.dependency.verification=strict
```

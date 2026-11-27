# AndroidX / Material migration epic (TASK-39)

**Status:** tracking only — **not authorized**. This document records a proposed,
multi-sprint migration. It approves no code changes. Proceeding with **any**
phase requires an explicit user approval that names the phase(s); see
[Decision gate](#decision-gate).

## Why an epic, not a 30-day task

The app is a mature, Java-only, framework-UI codebase. Migrating it touches
preferences, four complete theme families, twelve activities, custom
`Preference` subclasses, custom action-bar layouts, dialogs and a hand-drawn
forecast canvas. Each of these is user-visible and must keep working on API 33
and 36, in all themes, and in the home-screen widgets. This is a staged,
multi-sprint effort with a verification gate after every phase; it is **not** a
single 30-day task and no phase is scheduled against the first release.

## Current state (evidence)

- `app/build.gradle`: only AndroidX dependency is
  `compileOnly 'androidx.annotation:annotation:1.9.1'` (TASK-40, javac-only,
  for `nodomain/freeyourgadget/gadgetbridge/model/WeatherSpec.java`).
  `gradle.properties` sets `android.useAndroidX=true`; Jetifier is off.
- Preferences: `Settings extends PreferenceActivity`; custom
  `SeekBarPreference extends Preference`, `NumberPickerPreference extends
  DialogPreference`, `LEDColorPreference extends Preference`; `ThemePicker`;
  XML in `res/xml/preferences.xml` and `res/xml/account_preferences.xml`.
- Theming: `res/values/styles.xml` defines `AppTheme_Light`,
  `AppTheme_Dark`, `AppTheme_Solarized`, `AppTheme_SolarizedDark`, all
  parented to `android:Theme.Holo*` / `Widget.Holo*`, with custom attrs in
  `res/values/attr.xml` (`colorPrimary`, `colorAccent`, icon attrs, ...).
- Activities: twelve classes extend framework `Activity`; eight import
  `android.app.ActionBar` and `MainActivity` calls `getActionBar()`.
  `WeatherAuthenticatorActivity extends AccountAuthenticatorActivity`
  (`android.accounts`, no AndroidX counterpart).
- Home-screen widgets (`ClassicWidget`, `BoldWidget`, `ChartWidget`,
  `ClockWidget`) are `RemoteViews` and cannot use Material widgets.
- Baseline: `app/lint-baseline.xml` freezes 439 pre-existing findings,
  including the `Theme.Holo` overdraw warnings; `abortOnError = true` so any
  *new* finding fails CI.
- `gradle/verification-metadata.xml` is in strict mode: every added artifact
  must be regenerated into it or CI fails dependency verification.
- Test gate: Robolectric 4.16 JVM tests (`docs/testing.md`); no device,
  emulator or DWD network access required.

## Non-goals / explicit scheduling statement

**No Kotlin, Jetpack Compose, Room, coroutines, Hilt, WorkManager or
Navigation rewrite is scheduled before the first published release.** The
first release ships with the current Java/framework UI; these phases must not
block or be bundled into it. All work below is Java-only, incremental and
releasable behind the existing behaviour. A rewrite of any of those areas
would be a separate, later decision with its own approval.

## Phases

### Phase 1 — Preferences and theming (first, independently estimable)

**Goal:** move settings and theme definitions onto AndroidX-compatible APIs
while keeping the four themes and every preference behaviour byte-for-byte in
terms of stored keys.

**Scope:** `Settings` → `androidx.preference` (`PreferenceFragmentCompat` /
`PreferenceActivity` replacement); `SeekBarPreference`,
`NumberPickerPreference`, `LEDColorPreference`, `ThemePicker`; the two
`res/xml` preference screens; `res/values/styles.xml` and `res/values/attr.xml`
(the four Holo parents and `Widget.Holo*` styles → AppCompat/Material theme
parents). Preference keys and defaults are **not** changed.

**Exit criteria:** all four themes render with unchanged colour/icon attrs;
every preference opens, persists and reloads; `:app:testDebugUnitTest` and
`:app:lintDebug` green with no new findings added to the baseline (existing
Holo overdraw entries may be removed once obsolete); dependency metadata
regenerated.

**Dependencies:** explicit approval of this phase; new AndroidX preference
artifact added to strict `verification-metadata.xml`; working build/test gate.
No dependency on Phases 2–3.

**Risks / mitigations:** custom `Preference` subclass APIs differ
(`DialogPreference`, `onBindDialogView`, widget layouts) — migrate one class at
a time; theme attrs are referenced across layouts and code — keep attr names
stable and verify all four themes on API 33 and 36; visual regressions need
manual comparison, not just unit tests.

**Estimation:** this phase is deliberately self-contained and can be estimated
on its own from the file list above; Phases 2–3 are explicitly *not* estimated
until Phase 1 is approved and complete.

### Phase 2 — AppCompat activities

**Goal:** migrate the twelve framework `Activity` classes to
`AppCompatActivity` and replace direct `android.app.ActionBar` usage with the
support action bar.

**Scope:** `MainActivity`, `Settings`, `LoggingActivity`, `WelcomeActivity`,
`LicenseInfo`, `OverviewChartLegendActivity`, `TextForecastListActivity`,
`TextForecastViewActivity`, `WeatherDetailsActivity`, `WeatherLayersActivity`,
`WeatherLayerMapActivity`, `WeatherWarningActivity`; `getActionBar()` →
`getSupportActionBar()`; `res/layout/actionbar_*.xml` custom action-bar views;
`WeatherAuthenticatorActivity` needs a deliberate decision (its
`AccountAuthenticatorActivity` base has no AndroidX equivalent — keep the
framework base or separate the authenticator UI).

**Exit criteria:** all screens launch, navigate via `parentActivityName`, show
menus and custom action-bar views, and handle back/up identically; test and
lint gates green; no new baseline entries.

**Dependencies:** Phase 1 complete (AppCompat/Material theme parents must
exist before activities can use them); dependency metadata regenerated;
approval of this phase.

**Risks / mitigations:** theme mismatch crashes at inflation time if any
activity is left on a Holo parent — migrate themes first and check every
manifest-declared theme; custom action-bar layouts use framework widget attrs;
`AlertDialog`/`PopupWindow`/menu code may need appcompat variants; widgets
stay on framework `RemoteViews` and are out of scope.

### Phase 3 — Material components

**Goal:** replace remaining framework UI widgets with Material Components and
align them with the existing design tokens.

**Scope:** framework `Button`, `Spinner`, `ProgressBar`, dialogs and list-row
widgets where a Material equivalent exists; Material alert dialogs and
touch-target/elevation semantics; attr mapping to the four themes. Excluded:
`RemoteViews` widgets and hand-drawn canvas code (`ForecastBitmap`,
`ForecastIcons`, `RadarMN*`, chart layers) which do not use Material widgets.

**Exit criteria:** all four themes pass a visual and accessibility review;
gates green; APK size/minify/proguard rules unchanged in effect.

**Dependencies:** Phase 2 complete; new Material artifact(s) added to strict
verification metadata; approval of this phase.

**Risks / mitigations:** broad visual churn and lint baseline churn — land in
small slices with review per slice; Material defaults change colour and
elevation — keep custom attrs authoritative; strict dependency verification
and `minifyEnabled`/shrinkResources must be re-checked on the release build.

## Decision gate

This document **does not** authorize implementation. Before any phase starts,
the user must give explicit approval naming the phase (or explicitly approve a
set of phases). If approval is withheld, TASK-39 remains a tracking document
only and the app stays on its current framework UI for the first release.

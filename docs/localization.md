# Localization, RTL and fallback policy

_(TASK-36)_

## Baseline

`app/src/main/res/values/strings.xml` is the canonical English source. Every other
`values-<locale>/` directory is a translation overlay: any key missing from an overlay
falls back to the English string at runtime. This is a deliberate design choice for a
small upstream app: a partially translated screen is preferable to a missing one.

The Indonesian resources were renamed from the legacy qualifier `values-in/` to the
ISO 639-1 qualifier `values-id/` (minSdk 33, so `id` is always understood).

| Locale directory | State |
| --- | --- |
| `values` | canonical English (complete) |
| `values-ar` | **new RTL locale**, 18 curated Arabic strings + explicit English fallback copies for all other keys |
| `values-cs`, `values-da`, `values-de`, `values-es`, `values-fi`, `values-fr`, `values-ga`, `values-hu`, `values-id`, `values-it`, `values-nb-rNO`, `values-nl`, `values-pl`, `values-ru`, `values-sv`, `values-uk` | established translations, fallback for late-added keys |
| `values-zh-rTW` | curated 123-string subset, approved English fallback for the remainder |

### Approved fallback for `zh-rTW`

`values-zh-rTW/strings.xml` contains a curated set of high-visibility Traditional Chinese
strings. The remaining ~430 keys are intentionally **not** translated: weather warnings,
legal text and technical settings strings are safety- and support-relevant, and a machine
translation without a native reviewer is more likely to mislead users than the English
original. Android's per-key resource fallback supplies the English string for those keys.
The existing `MissingTranslation` entries in `app/lint-baseline.xml` record this state; the
baseline must not grow. If a qualified Traditional Chinese reviewer supplies the missing
strings, fill the gaps and remove the matching baseline entries in the same change.

### Approved fallback for `ar`

`values-ar/strings.xml` translates the strings visible on the main screen, the settings
entry point and the overview-chart legend (18 keys). Every other key is deliberately
present with the canonical English text as an explicit fallback copy, so the locale is
formally complete and lint does not report new `MissingTranslation` findings. This is
the approved alternative to shipping unvetted machine translations. Adding Arabic to
the in-app per-app language picker (`res/xml/locales_config.xml`) is a separate
follow-up: the system-locale route already selects these resources.

## Legal and about text

`res/raw/license.txt` and `res/raw/about.txt` are shipped in English only, by policy:

* `license.txt` starts with the canonical, verbatim AGPLv3 text from `COPYING` and is
  followed by the preserved third-party notices (Gadgetbridge/WeatherSpec, Apache 2.0,
  PNPOLY, Solarized, OpenStreetMap). Legal text is only authoritative in its verbatim
  form; translating it would create competing variants, so the License screen
  (`LicenseInfo`) always shows the English original.
* `about.txt` is a version/credits file with proper names and SPDX identifiers; it is
  likewise intentionally untranslated.

The `?`-style help and warning strings are translatable and should be kept in sync with
the English source when the legend or warning vocabulary changes.

## String conventions

* `legend_temp_below` describes the cyan temperature line: English "Cyan line: ...",
  German "Cyan-Linie: ...", Arabic "خط سماوي: ...". Do not reintroduce "blue"/"blau"
  for this key (the blue line is `legend_humidity`/`legend_dewpoint`).
* The host-validation rejection message is the resource
  `invalid_weather_host_message` in every locale. TASK-11 owns the Java side: its
  `INVALID_WEATHER_HOST_MESSAGE` constant in `Settings.java` must be replaced by
  `context.getString(R.string.invalid_weather_host_message)` when that branch lands.
  This task only owns resource files and therefore cannot wire it.
* The orphaned `preference_feedback_*` / `feedback_mail_*` keys were removed from all
  locales (the feedback preference screen no longer exists). Do not re-add them.

## RTL

`android:supportsRtl="true"` is set on `<application>` in `AndroidManifest.xml`. Layout
mirroring is driven by the configuration locale, not by the number of translated keys,
so partial `values-ar` coverage is enough to verify the RTL layout.

## Pseudo-locale test (untranslated / clipped strings)

Android's built-in pseudo-locales expand text and force RTL, which exposes clipped
textViews and hardcoded strings. On the running emulator:

```bash
# On API 33+ use the locale service; persist.sys.locale is often read-only in the emulator.
# Clear a stale per-app override first, otherwise it wins over the device locale:
adb shell cmd locale set-app-locales de.kaffeemitkoffein.tinyweatherforecastgermany --locales ""
adb shell cmd locale set-device-locale ar-XB   # pseudo-Arabic (RTL, bidi markers)
adb shell cmd locale set-device-locale ar-EG   # real RTL locale, curated Arabic strings
adb shell cmd locale set-device-locale de-DE   # real German locale, cyan legend wording
adb shell cmd locale set-device-locale en-US   # restore
```

For each locale, restart the app, open the main screen, the overview-chart legend and
Settings, and check that (a) no string is cut off or ellipsised, (b) no key name such as
`legend_temp_below` leaks into the UI, and (c) RTL screens mirror the navigation and
text alignment. Screenshots from the TASK-36 run are stored under
`/var/home/j/.cache/tmp/opencode/task36-*.png` (mode 0600).

Enabling RTL surfaces 20 pre-existing `RtlCompat` layout findings (`left`/`right` gravity
combined with `textStart`/`textEnd`); these are baselined in `app/lint-baseline.xml` by
this change. Layout files are owned by a follow-up, which should replace those
gravities with `start`/`end` and remove the baseline entries.

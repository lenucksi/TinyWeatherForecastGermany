# Radar animation design, empty-session causes, and real-fixture test integration (TASK-76)

**Status:** research + report only. No app code, no test code, and no binary fixture were
added in this task. Worktree: `tasks/TASK-76-radar-animation-research` at `fa1431f0`
(the TASK-70 playback-controls commit).

**Bottom line:** multiple images are absolutely intended — a 24-frame, 5-minute DWD
radar sequence is downloaded once into the app cache and replayed every 750 ms by
swapping frames into one map overlay. A live session can look empty when the frames
contain no rain, when their background palette entries become transparent, when a
stale/near-empty cached set is reused, when the caption timestamp does not match the
cached pixels, or when the radar layer/style is not active. The real DWD fixture
(`radar-real-20261126T0600Z.png`) should become a classpath test resource and be
asserted through the production `RadarMN2.getBitmap()` palette path in a new
Robolectric test; no screenshot framework exists in this repo, so pixel-count
assertions are the correct substitute.

---

## 1. Intended radar animation design

### 1.1 Download and cache: 24 frames, 5-minute steps, fetched once

* `APIReaders.RadarMNSetGeoserverRunnable` defines
  `TIMESTEP_5MINUTES = 1000*60*5` and `DATASET_SIZE = 24` with the comment
  "there are 23 OR 24 future images available at the GeoServer"
  (`app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/APIReaders.java:1491-1497`).
  The DWD WMS URL requests `dwd:Radar_wn-product_1x1km_ger` over
  `bbox=493000,5861000,1791000,7470000`, EPSG:3857, PNG, at the fixed radar map
  size (`APIReaders.java:1520-1523`; size at `RadarMN2.java:62-68` = 1108 x 1360 at
  scale 1, 2216 x 2720 at scale 2).
* Each frame is requested for `startTime + i * TIMESTEP_5MINUTES`
  (`APIReaders.java:1669-1685`). `startTime` is rounded **up** to the next UTC
  5-minute boundary (`APIReaders.java:1529-1540`), and the WMS `time=` parameter is
  formatted `YYYY-MM-DDTHH:MM:00.000Z` (`APIReaders.java:1542-1593`). For the real
  fixture this is `2026-11-26T06:00:00.000Z`, i.e. epoch `1795672800000`.
* Frames are stored in `Context.getCacheDir()` as `radarMN-0.png` … `radarMN-23.png`
  (`APIReaders.java:1494-1495`, `1606-1609`). A frame "exists and is valid" only if
  the file is **larger than 5000 bytes** (`APIReaders.java:1611-1624`); a complete
  set tolerates two missing/invalid frames, i.e. `count >= DATASET_SIZE-2` = 22
  (`APIReaders.java:1626-1637`).
* All 24 frames are fetched in parallel by 24 threads; `multiFetchRadarSet()`
  **deletes the whole old set first**, stores the new `startTime` in
  `WeatherSettings.PREF_RADAR_LASTDATAPOLL`, and calls `onFinished()`
  (`APIReaders.java:1687-1697`, `1708-1728`). `run()` only fetches when the set is
  incomplete/outdated/forced **and** a suitable network is available; otherwise it
  reuses the cache and reloads `startTime` from that preference
  (`APIReaders.java:1743-1759`; staleness window = 5 min,
  `WeatherSettings.java:786-799`, `Radarmap.java:40`).
* In `WeatherWarningActivity` the downloader is queued once on the single-thread
  scheduler (`WeatherWarningActivity.java:524-527`) and the stored `startTime` is
  restored in `onCreate()` (`WeatherWarningActivity.java:605`). A manual refresh
  clears all 24 cached frames before re-queueing the download
  (`WeatherWarningActivity.java:849-856`; `RadarMN2.clearRadarCache()` at
  `RadarMN2.java:245-259`).

### 1.2 Time label = download start + frame index × 5 minutes

`updateSlideTimeText()` formats
`new Date(rainSlidesStartTime + nextRainSlide * TIMESTEP_5MINUTES)` with
`SimpleDateFormat("HH:mm", Locale.getDefault())`
(`WeatherWarningActivity.java:1428-1443`). Colours: theme text colour for a valid
set, orange when "now" is more than 1.5 h after the start, yellow when no valid set
was obtained. There is no timestamp metadata in the PNGs — the caption is derived
entirely from the single stored `startTime` plus the zero-based frame index. The
separate frame caption uses `nextRainSlide+1` of 24
(`WeatherWarningActivity.java:1733-1738`).

Both drawing paths update the caption for the frame just drawn: in vector mode
`drawRadarSlide()` calls `updateSlideTimeText()` explicitly (this was a TASK-70 fix:
`WeatherWarningActivity.java:1773-1784`), and the legacy path calls it from
`drawMapBitmap()` (`WeatherWarningActivity.java:1822-1825`). In the timer loop the
increment happens *after* `drawRadarSlide(nextRainSlide)` returns
(`WeatherWarningActivity.java:309-316`), so the label describes the drawn frame,
not the next one.

### 1.3 750 ms timer advances the frame index

`RAINSLIDEDELAY = 750` and the cross-thread flags
(`nextRainSlide`, `rainSlidesRunning`, `rainSlidesPaused`, `cancelRainSlides`,
`keepTimelineAfterStop`) are declared at
`WeatherWarningActivity.java:243-254`. `rainRadarRunnable` runs on a background
thread at `THREAD_PRIORITY_BACKGROUND`, sets `nextRainSlide = 0`, marks playback
running, makes the slider container visible, then loops while not cancelled:
if paused it sleeps 50 ms and re-checks; otherwise it posts to the UI thread
`drawRadarSlide(nextRainSlide); nextRainSlide++;`, wraps to 0 after index 23, and
sleeps the remainder of the 750 ms budget (busy-waiting on `rainDrawLock` if drawing
overran) (`WeatherWarningActivity.java:270-357`). On exit it hides the timeline
unless a user STOP requested `keepTimelineAfterStop`, then resets all flags.
`startRainRadar()` starts the thread only when `radarEnabled()` is true and no run
is active (`WeatherWarningActivity.java:359-390`; `radarEnabled()` at
`WeatherWarningActivity.java:993-994`).

### 1.4 How each frame is shown

* **Vector/MapLibre path:** `drawRadarSlide()` delegates to
  `applyVectorRadarFrame(count)` (`WeatherWarningActivity.java:1773-1784`). That
  method guards on `useVectorMap`, a live `mapLibreMap`, a ready style and the
  `showRadarLayer` toggle, validates the cache file, decodes it with
  `RadarMN2.getBitmap(context,count)`, and on the UI thread creates
  `ImageSource("twfg-radar-imagesource", radarLatLngQuad(), bitmap)` once; on all
  later frames the same source is updated with **`source.setImage(bitmap)`**. A
  `RasterLayer("twfg-radar-raster")` with 0.8 opacity and 0 fade duration is added
  below the warning fills (`WeatherWarningActivity.java:2931-2992`; IDs and
  opacity at `WeatherWarningActivity.java:195-198`). The quad is derived from the
  DWD EPSG:3857 box: 4.4287–16.0888 °E, 46.5010–55.5533 °N
  (`RadarMN2.java:196-217`). There is no network access and none of the legacy
  hand pixel corrections in this path.
* **Legacy static-map path:** `drawRadarSlide()` clears `radarBitmap` to
  `Color.TRANSPARENT`, draws `RadarMN2.getScaledBitmap(context,count)`
  (`RadarMN2.java:294-296`, an alias of `getBitmap()`) onto a `Canvas` with
  `SRC_OVER`, then `drawMapBitmap()` composites warnings, admin areas and radar
  into `visibleBitmap` and updates the caption
  (`WeatherWarningActivity.java:1785-1802`, `1804-1825`).
* **Palette transform in the shared decoder:** `RadarMN2.getBitmap()` decodes the
  cached PNG, reads all ARGB pixels, maps the four exact DWD background values
  `0xFFBEBEBE`, `0xFFFC3FFF`, `0xFFBDBDBD`, `0xFFFFFFFF` to `Color.TRANSPARENT`,
  and writes the pixels back before returning the bitmap
  (`RadarMN2.java:265-292`). The rain colours are `Radarmap.RAINCOLORS`
  (`Radarmap.java:103-108`), index 0 being the transparent/no-data entry used by
  the legend (`WeatherWarningActivity.java:207-214`).

### 1.5 TASK-70 play / pause / stop / step / seek

All controls are wired in `setupRainTimelineControls()`
(`WeatherWarningActivity.java:1617-1662`); the layouts pin four 48 dp targets
(`RadarTimelineTest.java:67-119`).

* **PLAY / PAUSE** — `toggleRainSlides()`: when not running it calls
  `startRainRadar()`; while running the same button flips `rainSlidesPaused` and
  swaps the icon/content description between `ic_radar_pause_24dp` /
  `ic_radar_play_24dp` (`WeatherWarningActivity.java:1664-1672`, `1740-1747`).
  Pause keeps the timer thread alive but stops advancing frames
  (`WeatherWarningActivity.java:295-305`).
* **STOP** — `stopAndResetRainSlides()`: sets `keepTimelineAfterStop = true`,
  unpauses, cancels the loop, and jumps to frame 0; the timeline stays visible so
  single frames can still be stepped (`WeatherWarningActivity.java:1674-1688`,
  `338-350`).
* **STEP ±1** — `stepRainSlide(delta)` pauses a running timer first so it cannot
  overwrite the choice, then `jumpToRainSlide()` normalizes the index with
  `normalizeRainFrame()` (modulo 24, wrapping both ways), redraws, updates slider,
  caption, frame text and icons (`WeatherWarningActivity.java:1690-1731`).
* **SEEK** — the `SeekBar` maps its 0–100 position back to `DATASET_SIZE/100f * i`,
  immediately swaps the cached frame (`applyVectorRadarFrame` in vector mode,
  `drawRadarSlide` in legacy mode when paused/stopped) and refreshes both captions
  (`WeatherWarningActivity.java:1574-1615`).

No control starts a download; all frame changes reuse the 24 cached PNGs.

---

## 2. Are multiple images intended? Yes.

The design is explicitly a **24-image sequence**: 24 files in the cache, 24 indices
on the seek bar, a `nextRainSlide` counter that advances one frame every 750 ms,
and one `ImageSource.setImage()`/bitmap redraw per step. A correct end-to-end
display looks like this: 24 valid `radarMN-*.png` frames covering a ~2 h window;
the slider thumb walks left→right while the caption shows "Frame 1/24 … 24/24" and
`HH:mm` = start + (frame−1)×5 min; irregular rain patches visibly move across the
georeferenced Germany composite (0.8 opacity, below the warning fills). With the
real fixture this means **92,050 rain-palette pixels (6.1 % of 1,506,880)** in small,
irregular speckles — not solid rectangles — with the other 93.5 % transparent so
the basemap shows through.

Why a live session can nevertheless look empty:

1. **Dry weather.** If the DWD composite has no rain pixels, every pixel is one of
   the background colours that `RadarMN2.getBitmap()` makes transparent
   (`RadarMN2.java:274-287`). The overlay is then correctly blank; the basemap is
   all you see. Files from the dry 2026-11-28 samples in the QA workspace are only
   ~37–39 KB versus 96.6 KB for the rainy fixture, which is a useful size signal.
2. **Transparent palette entries.** Only pixels with *exact* `RAINCOLORS` values
   survive. The fixture is 1,408,487 background pixels → transparent, 98,393
   opaque pixels after the transform, of which 92,050 are exact rain colours and
   6,343 are small anti-aliased edge blends. A frame with mostly white
   (`0xFFFFFFFF`, 1,207,256 pixels here) or grey looks empty by design.
3. **Cached near-empty frames.** `radarCacheFileValid()` checks only `> 5000` bytes
   (`APIReaders.java:1616-1624`), so a nearly empty PNG can pass, and
   `fullRadarDataSet()` tolerates 22 valid files (`APIReaders.java:1626-1637`).
   The downloader reuses that set for up to 5 minutes (`APIReaders.java:1743-1759`),
   and an invalid index makes `drawRadarSlide()`/`applyVectorRadarFrame()` return
   silently (`WeatherWarningActivity.java:1785-1800`, `2946-2955`) — the timer
   keeps advancing with no visible change.
4. **Timestamp mismatch.** The caption comes from one stored `startTime`, not from
   the PNGs. Restored activity state, an injected/stale frame, a clock or time-zone
   change, or the 1.5 h "stale" colouring (`WeatherWarningActivity.java:1436-1438`)
   can make `HH:mm` describe a time that has nothing to do with the cached pixels.
   The vector path used to leave the caption stale; TASK-70 fixed that explicitly
   (`WeatherWarningActivity.java:1779-1781`).
5. **Layer/style gating.** In vector mode `applyVectorRadarFrame()` does nothing
   unless the radar layer is switched on, the MapLibre style is ready and the map
   exists (`WeatherWarningActivity.java:2946-2950`, `2959-2969`; `radarEnabled()`
   at `993-994`). In legacy mode `hide_rain` has the same effect
   (`WeatherWarningActivity.java:1794-1797`). Also, the play bar is hidden until
   playback starts and is hidden again after an automatic stop
   (`WeatherWarningActivity.java:284-294`, `338-350`, `370-377`).

---

## 3. Test infrastructure today

* The PR gate is the JVM unit-test suite: JUnit 4.13.2 + Robolectric 4.16, no
  emulator or DWD network access (`docs/testing.md:3-4`, `app/build.gradle:162-163`).
  `includeAndroidResources = true`, JDK 21 `--add-opens` flags and four pinned
  `android-all-instrumented` jars (SDK 26/28/33/36, tests enabled for 33/36) are
  configured in `app/build.gradle:58-79` and `194-223`.
* Static fixtures already live in `app/src/test/resources/fixtures/` and are read
  from the classpath with `getResourceAsStream("/fixtures/...")`
  (`docs/testing.md:68-70`; `TextForecastParserTest.java:42-57`). There is a direct
  precedent for a **binary** fixture copied into Robolectric app data at test time:
  `DatabaseMigrationTest.java:80-101` (`RuntimeEnvironment.getApplication()`,
  `Files.copy(..., REPLACE_EXISTING)`, `@After` cleanup).
* Existing radar tests pin source structure and pure math rather than pixels:
  `VectorRadarTest.java:54-126`, `RadarTimelineTest.java:67-162`,
  `VectorPinAndCacheTest.java:49-99`.
* **There is no screenshot/Roborazzi/Paparazzi framework and no `androidTest`
  source set** in this worktree. `docs/testing.md:71-72` explicitly warns that
  Robolectric's `ShadowCanvas` does not reproduce custom-drawn bitmap colours and
  asks tests to assert extracted state instead of pixels. Therefore the correct
  "screenshot-style" proof here is to assert the *decoded bitmap's pixel data*
  through the production decoder, not to render a canvas. Real screenshots remain
  an on-device activity (TASK-71 / `docs/radar-alignment-qa.md:38-46`).

## 4. Proposed real-fixture integration (for the later implementation step)

### 4.1 Fixture files to add — no synthetic patch

```
app/src/test/resources/fixtures/radar-real-20261126T0600Z.png        # 96,599 bytes, copy verbatim
app/src/test/resources/fixtures/radar-real-20261126T0600Z.meta.txt   # provenance + expected numbers
```

Source of the PNG: `/var/home/j/.cache/tmp/opencode/radar-real-20261126T0600Z.png`
(do **not** use the hi-res variant for these tests; the scale-1 fixture matches
`RadarMN2.getFixedRadarMapWidth/Height()` = 1108 x 1360 when the Robolectric
display is below `WIDTH_SCALE2 = 1079`-derived scale-2 threshold — assert that
explicitly, see risks).

`meta.txt` should record: DWD WMS URL from
`/var/home/j/.cache/tmp/opencode/radar-real-20261126T0600Z.meta.txt`,
`time=2026-11-26T06:00:00.000Z`, retrieval UTC, dimensions 1108 x 1360,
SHA-256 `aa69756c2dc6ca50e40cdd1bf0fdc38fa0b0e14b0eed5feafe0bb109a4aa1e9f`,
and the expected pixel counts below. This keeps magic numbers reviewable and
lets the test fail loudly if the fixture is replaced by a synthetic image.

Measured properties of that PNG (reproduced with
`/var/home/j/.cache/tmp/opencode/radar_png_stats.py`; its palette and transparent
sets mirror `Radarmap.java:103-105` and `RadarMN2.java:274-287`):

| Property | Value |
| --- | --- |
| Size / dimensions | 96,599 bytes; 1108 x 1360 (RGB, 8 bit); 1,506,880 pixels |
| Exact `RAINCOLORS` pixels | **92,050** (6.109 %) |
| `0xFF009934` (idx 4) | 30,870 |
| `0xFF00CACA` (idx 3) | 19,221 |
| `0xFF33FFFF` (idx 2) | 14,310 |
| `0xFF4DBF1A` (idx 5) | 12,705 |
| `0xFF99FFFF` (idx 1) | 8,483 |
| Background colours mapped to transparent | `0xFFFFFFFF` 1,207,256; `0xFFBEBEBE` 201,206; plus `0xFFBDBDBD`/`0xFFFC3FFF` — **1,408,487 total** |
| Opaque after `RadarMN2.getBitmap()` | **98,393** (92,050 palette + 6,343 edge blends in 193 other colours) |

Note: `RAINCOLORS[9] == RAINCOLORS[10] == 0xFFFFC400`; count palette membership
through a `Set<Integer>` so those 58 pixels are counted once.

### 4.2 New test `app/src/test/java/de/kaffeemitkoffein/tinyweatherforecastgermany/RadarRealFixtureTest.java`

`@RunWith(RobolectricTestRunner.class) @Config(sdk = 33)`, following the style of
`RadarTimelineTest.java:39-41` and the fixture-copy pattern of
`DatabaseMigrationTest.java:80-101`:

* `@Before`: `Context context = RuntimeEnvironment.getApplication()`; copy the
  classpath resource into
  `APIReaders.RadarMNSetGeoserverRunnable.getRadarMNFile(context, 0)` with
  `Files.copy(in, path, REPLACE_EXISTING)`. `@After`: delete that file (and any
  other cache files the test seeded) so no fixture leaks into other tests — the
  same teardown discipline required by `docs/radar-alignment-qa.md:34-56`.
* `fixtureIsTheRealDwdFrame()`: assert resource length 96,599, SHA-256 equals the
  value above, and `RadarMNSetGeoserverRunnable.radarCacheFileValid(context, 0)`
  is true (this also pins the `>5000` cache rule).
* `productionPaletteTransformKeepsRealRainPixels()`: call
  `RadarMN2.getBitmap(context, 0)` (the *production* decoder); assert non-null,
  `getWidth()==1108`, `getHeight()==1360`; read all pixels with `getPixels()`;
  assert transparent count 1,408,487 and opaque count 98,393; assert palette
  membership count 92,050 and the per-bucket counts in the table; assert that none
  of the four exact background ARGB values remain opaque. This proves real rain
  pixels survive the exact path that feeds both `ImageSource.setImage()` and the
  legacy canvas.
* Optionally `getScaledBitmap()` is the alias used by the legacy path
  (`RadarMN2.java:294-296`); assert both decoders return the same rain count.

### 4.3 New test `app/src/test/java/de/kaffeemitkoffein/tinyweatherforecastgermany/RadarFrameSetFixtureTest.java`

Proves the "multiple frames are loaded" contract without any network or emulator:

* Copy the same real fixture into all 24 cache slots via
  `getRadarMNFile(context, i)` (`i = 0..DATASET_SIZE-1`) and assert
  `fullRadarDataSet(context)` is true. Document in the class comment that the
  identical pixels are intentional: this test exercises index → file → bitmap
  plumbing, not motion; motion is proven by the timer/source-swap tests already in
  `RadarTimelineTest`/`VectorRadarTest`.
* For each `i`, call `RadarMN2.getBitmap(context, i)` and assert the same 92,050
  palette pixels; also assert that an out-of-range index (24) has no cache file,
  mirroring the silent-skip guard at `WeatherWarningActivity.java:2949-2951`.
* Assert `DATASET_SIZE == 24`, `TIMESTEP_5MINUTES == 5*60*1000`,
  `roundUTCUpToNextFiveMinutes()` maps a non-aligned epoch to a multiple of
  5 minutes, and `getUrlStringForTime(1795672800000L)` contains
  `time=2026-11-26T06:00:00.000Z` (`APIReaders.java:1529-1593`).
* For the caption contract, either (a) extend `RadarTimelineTest` with the
  existing source-level pin of `WeatherWarningActivity.java:1430` and a pure
  arithmetic check `start + i*TIMESTEP` for `i = 0..23` with a fixed
  `TimeZone.setDefault(TimeZone.getTimeZone("UTC"))`, or (b) *prefer* a tiny
  production extraction in the implementation step:
  `static String radarSlideTimeLabel(long startMs, int frame)` used by
  `updateSlideTimeText()`, asserted for frame 0 → `06:00`, frame 23 → `07:55`,
  and frame 24 → `08:00`. This is the only proposed app-code change; keep it
  mechanical (move the existing expression, no behaviour change).

If a second *distinct* real DWD frame becomes available from TASK-71, add it as
another checksummed resource and use frames 0/1 to assert the decoded bitmaps
actually differ; do not generate a synthetic patch for this.

### 4.4 Validation and risks

* Validate with the repo's supported wrapper from the primary checkout, e.g.
  `TWFG_REPO=<worktree> scripts/build.sh :app:testDebugUnitTest --tests ...`
  (see `docs/testing.md:36-50`, `95-115`). No new Gradle dependency is needed, so
  `gradle/verification-metadata.xml` is unaffected.
* **Risk 1 — Robolectric bitmap codec.** No existing test calls
  `BitmapFactory.decodeFile()` with `inMutable=true`; verify on first integration
  that `ShadowBitmapFactory` returns a mutable bitmap and that `get/setPixels`
  work. Fallback (still real data, no fake pixels): assert fixture identity with a
  plain JVM test, decode via `BitmapFactory.decodeStream()` under Robolectric, and
  move the palette loop of `RadarMN2.getBitmap()` into a small pure static helper
  that the test calls directly. Do not reimplement the palette in the test only.
* **Risk 2 — scale factor.** `RadarMN2.getBitmap()` sizes its pixel array from
  `getScaleFactor(context)` (`RadarMN2.java:29-41`, `62-68`, `261-263`). Assert
  `RadarMN2.getFixedRadarMapWidth(context) == 1108` in the test (optionally pin
  `@Config(qualifiers = "w320dp-mdpi")`) so a scale-2 Robolectric display cannot
  silently misdecode the scale-1 fixture. A separate hi-res fixture would be
  needed to exercise the scale-2 path.
* **Risk 3 — fixture drift.** Keep the SHA-256 assertion; if the numbers ever
  change legitimately, update the `meta.txt` and the expected counts together.

---

## 5. Notes for TASK-71 (on-device verification with the same fixture)

* Cache path is `/data/data/de.kaffeemitkoffein.tinyweatherforecastgermany/cache/radarMN-0.png`
  … `radarMN-23.png` (`APIReaders.java:1606-1609`;
  `docs/radar-alignment-qa.md:8-21`). This fixture is **real DWD rain data**, not a
  synthetic patch, but it is still injected test data and must be cleaned up.
* On a live device the downloader will **delete all 24 files** and fetch fresh
  frames unless `fullRadarDataSet()` is true and `getPrefRadarLastdatapoll()` is
  within 5 minutes of "now" (`APIReaders.java:1712-1714`, `1743-1759`;
  `WeatherSettings.java:795-799`). So to keep the fixture visible either seed a
  full set and set the stored start time to the current rounded-up 5-minute value
  (then note that the `HH:mm` caption will intentionally be "now", not 06:00Z), or
  work offline so `suitableNetworkAvailable()` is false and the set is reused.
  Frame 0 of this fixture corresponds to `startTime = 2026-11-26T06:00:00Z`
  (epoch 1795672800000).
* Expected visual: 92,050 green/cyan/blue/yellow speckles (no solid blocks),
  ~93.5 % transparent background, slider caption "Frame 1/24" and time 06:00 for
  index 0; after 750 ms "Frame 2/24" 06:05, etc. The precipitation palette to
  check by eye is `99ffff, 33ffff, 00caca, 009934, 4dbf1a, 99cc00`
  (`docs/radar-alignment-qa.md:40-44`).
* Teardown is mandatory: use the in-app refresh action, which calls
  `RadarMN2.clearRadarCache()` (`WeatherWarningActivity.java:849-856`), wait for
  24 frames to re-download, and capture a screenshot (or, if offline, host-side
  `rm -f .../cache/radarMN-*.png` followed by reopening the activity and a fresh
  download) — see `docs/radar-alignment-qa.md:34-56`.

---

## 6. One-paragraph answer to the user

Yes — the play bar is a 24-frame animation. The app downloads 24 five-minute DWD
PNG snapshots once (22 is tolerated), caches them as `radarMN-0..23.png`, and every
750 ms swaps the next cached bitmap into one MapLibre `ImageSource` via
`source.setImage()` (or onto the legacy canvas), while the caption shows
`rainSlidesStartTime + frame*5min` and the TASK-70 play/pause/stop/step controls
only manipulate that index. Nothing is fetched per frame. If the bar looks static
or empty, the frames are present but blank (dry weather; DWD white/grey entries are
explicitly turned transparent), stale/near-empty cached frames passed the 5 KB
validity check, the stored timestamp does not match the pixels, or the radar layer
was off/style not ready. A real end-to-end display shows irregular rain speckles
moving west→east across Germany with the frame/time captions advancing in step.
The real fixture should be committed as `app/src/test/resources/fixtures/radar-real-20261126T0600Z.png`
plus a checksum/provenance `.meta.txt`, and asserted in new Robolectric tests
`RadarRealFixtureTest.java` and `RadarFrameSetFixtureTest.java` by copying it into
`context.getCacheDir()` at test time and calling the production
`RadarMN2.getBitmap()` — expecting 92,050 palette rain pixels, 1,408,487
transparent pixels and 98,393 opaque pixels — rather than by synthetic patches or
canvas screenshots (which Robolectric cannot render faithfully).

# Radar data source, timeline counter and layering — research report (TASK-86)

**Status:** research + report only. No app code, no test code, no device/emulator session and
no uploads in this task. Worktree
`/var/home/j/.local/share/opencode/worktree/bea291/TASK-86-radar-source-report`,
branch `tasks/TASK-86-radar-source-report` at `48cefbbf` (clean; merge-base with `master`
is `48cefbbf`).

**File shorthand used below:**

| Shorthand | Path |
|---|---|
| `WWA` | `app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java` |
| `API` | `app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/APIReaders.java` |
| `RMN2` | `app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/RadarMN2.java` |
| `RMAP` | `app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/Radarmap.java` |
| `LAYOUT` | `app/src/main/res/layout/activity_weatherwarning.xml` (landscape variant: `app/src/main/res/layout-w600dp-land/activity_weatherwarning.xml`) |

---

## 0. Short answers to the user's questions (a)–(g)

* **(a) Why does `Bild 1/24` stay while the slider moves?** Because the slider and the
  counter are driven from two different places. `drawRadarSlide()` updates the slider and
  the HH:mm label, but it never calls `updateRainFrameText()`. During PLAY the caption is
  written exactly once when the timer thread starts (while `nextRainSlide==0`), so it is
  frozen at `Bild 1/24`; the slider thumb advances because `updateRainSeekBar(count)` *is*
  called on every drawn frame. Details and exact lines in §1.

* **(b) The empty band above the timeline is wasted.** It is the caption row of the bottom
  timeline container: in vector mode the HH:mm time and rain-legend strip above it are
  hidden (`WWA:3210-3212`), leaving a full-width band with only a right-aligned
  `Bild 1/24`. That is real unused space; §2 recommends filling it with the HH:mm time
  (left) and the precipitation colour scale (middle), keeping the counter right.

* **(c) If only one image exists, nothing should move.** Agreed, and today the app does
  move anyway: `fullRadarDataSet()` is only a download gate, while playback always loops
  `0..23` and the caption denominator is hard-coded to 24. With one cached PNG the
  seekbar/time/counter sweep through 24 steps while `applyVectorRadarFrame()` silently
  skips invalid indices, so the pixels are static. §3.3 gives the concrete gating fix.

* **(d) Data source / one vs many / intended state.** One WMS GetMap per frame from DWD
  `dwd:Radar_wn-product_1x1km_ger`, 24 frames at 5-minute steps (≈2 h window, matching the
  DWD product's 0–2 h forecast), downloaded in parallel once and cached as
  `radarMN-0.png … radarMN-23.png`. One frame or an empty set is a partial/failed/first-launch
  cache, not the intended state. Intended steady state: 24 valid frames; when unavailable,
  show the basemap/warnings with **no** animated timeline and an honest unavailable/stale
  indication. §3.

* **(e) What is/should be visible on the radar image.** DWD WN precipitation reflectivity
  in dB, 1 km resolution, 5-minute steps. Only pixels matching `Radarmap.RAINCOLORS`
  indices 1–16 (palette dBZ ≈ 5.5 … 85) are rain; four exact white/grey background values
  are converted to fully transparent in `RadarMN2.getBitmap()`. Absence of colour means
  **no precipitation / no data**, not a failed download. The real fixture shows a southern
  rain band with small irregular speckles, not solid blocks. §4.

* **(f) Where to see the screenshots.** All 107 PNGs are **local files** under
  `/var/home/j/.cache/tmp/opencode/` (mode `0600`), not uploaded anywhere. Full inventory
  with paths and descriptions in §6. The referenced TASK-71 images are described in
  `docs/radar-real-fixture.md:101-117`.

* **(g) Radar topmost with transparency?** Recommendation: **no** — keep the radar raster
  *below* the coloured warning fills and outlines as it is today (`WWA:2984-2988`). Radar on
  top would recolour/tint warning polygons and make severity harder to read. The underlying
  basemap already stays visible because ≈93 % of a real frame is transparent and the layer
  is at 0.8 opacity; if heavy rain hides warning fills, lower `RADAR_RASTER_OPACITY` from
  0.8 to ~0.7 at `WWA:198` / `2981`. Trade-offs and exact edit locations in §5.

---

## 1. (a) The stale `Bild 1/24` counter — precise code path

### 1.1 Who writes the caption

The widget is `warningactivity_rainslideframe` (`LAYOUT:179-194`, right-aligned at the top
of the timeline container). Its **only** writer is `updateRainFrameText()`
(`WWA:1733-1738`):

```java
rainSlideFrame.setText(getString(R.string.radar_frame,nextRainSlide+1,
        APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE));
```

`R.string.radar_frame` is `"Bild %1$d/%2$d"` (`app/src/main/res/values-de/strings.xml:611`;
English equivalent in `values/strings.xml`). Note that the denominator is the compile-time
constant 24, not the number of frames actually present.

`updateRainFrameText()` is called from exactly four places:

| Caller | Line | When |
|---|---|---|
| `startRainRadar()` start runnable | `WWA:373` | once, before the timer thread starts |
| SeekBar `onProgressChanged` | `WWA:1598` | only on user drag |
| `jumpToRainSlide()` | `WWA:1716` | only STOP / STEP ±1 (TASK-70 controls) |
| `refreshRadarTransportControls()` | `WWA:1746` | when the transport icons are refreshed |

### 1.2 What the PLAY timer actually does

`rainRadarRunnable` sets `nextRainSlide=0` (`WWA:274`), marks playback running and calls
`refreshRadarTransportControls()` on the UI thread (`WWA:278-283`) — at that moment the
field is still 0, so the caption becomes `Bild 1/24`. Then, every 750 ms
(`RAINSLIDEDELAY`, `WWA:254`), it posts (`WWA:309-316`):

```java
drawRadarSlide(nextRainSlide);
nextRainSlide++;
```

`drawRadarSlide(count)` (`WWA:1773-1802`) updates:

* `applyVectorRadarFrame(count)` — the pixels (`WWA:1777`),
* `updateRainSeekBar(count)` — the slider thumb (`WWA:1778`),
* `updateSlideTimeText()` — the HH:mm label (`WWA:1781`),

but **not** `updateRainFrameText()`. The legacy path similarly ends in `drawMapBitmap()`
which calls `updateSlideTimeText()` (`WWA:1822-1825`) but not the frame caption.

So during PLAY:

| UI element | Updated per frame? | Result |
|---|---|---|
| radar bitmap | yes (`WWA:1777`, `2956-2991`) | rain field changes |
| SeekBar thumb | yes (`WWA:1778`, `1576-1615`) | thumb walks right |
| HH:mm label | yes (`WWA:1428-1443`) | advances by 5 min |
| **`Bild n/24` counter** | **no** | **stays `Bild 1/24`** |

The increment happens *after* the draw (`WWA:313`), so the HH:mm label correctly describes
the drawn frame; only the frame counter is orphaned. On exit the runnable refreshes the
controls again (`WWA:340-350`), which rewrites the caption from whatever `nextRainSlide`
happens to be — often 24 or 0 after a wrap, which is another source of off-by-one looks.

### 1.3 Why the screenshots show exactly this

* `task71-real-stepped-full.png` shows `Bild 2/24`: STEP goes through
  `stepRainSlide()` → `jumpToRainSlide()` → `updateRainFrameText()` (`WWA:1691-1717`).
* `task71-real-playing-full.png` shows the seekbar advanced while
  `task71-real-timeline-label-crop.png` and `task71-real-final-vector-full.png` still show
  `Bild 1/24` (`docs/radar-real-fixture.md:112-116`). Same for STOP, which calls
  `jumpToRainSlide(0)` (`WWA:1681-1687`) and therefore writes frame 1 correctly.

### 1.4 Minimal fix (for TASK-87)

Make the caption a function of the *drawn* frame, not of the mutable field:

1. Change `updateRainFrameText()` to `updateRainFrameText(int frame)` and format
   `frame+1` / `usableFrameCount` (see §3.3).
2. Call it inside `drawRadarSlide(count)` for both the vector and legacy branches, next to
   the existing `updateSlideTimeText()` call (`WWA:1773-1782`); optionally pass `count` to
   `updateSlideTimeText()` too so both captions describe the same frame without a race.
3. Keep calling it from the SeekBar and transport paths, which then remain correct
   automatically.

---

## 2. (b) The empty band above the timeline

### 2.1 What the band is

`LAYOUT:166-278` defines `warningactivity_rainsliderSeekbarContainer`, bottom-aligned,
`wrap_content`. It contains three rows:

1. `warningactivity_rainslideframe` — the right-aligned caption (`LAYOUT:179-194`);
2. `warningactivity_rainsliderColors` — an 8 dp precipitation-colour strip (`LAYOUT:196-203`);
3. a 48 dp transport row with SeekBar + four 48 dp buttons (`LAYOUT:205-276`).

Above the container, `warningactivity_mapinfo_container` (`LAYOUT:106-161`) is a
horizontal bar with three columns: HH:mm time (weight 3), the rain legend/scale (weight
14) and an explicitly empty `warningactivity_reservedForFutureUse` spacer (weight 3,
`LAYOUT:151-159`).

### 2.2 Why it looks empty in vector mode

`applyMapMode()` hides `warningactivity_mapinfo_container` entirely when `useVectorMap`
is true (`WWA:3210-3212`). It also hides the separate progress bar and its `%` text
(`WWA:3217-3218`). The result is visible in
`/var/home/j/.cache/tmp/opencode/task71-real-final-vector-full.png` and
`task71-real-timeline-label-crop.png`: a full-width cream band with only `Bild 1/24` at the
right, no time and no colour scale. The colour strip ImageView is only populated in the
legacy success path (`WWA:734-745`), so the vector-mode strip is blank too.

### 2.3 Recommendation

The band itself is useful screen real estate; the waste is the *content*. Fill the caption
row instead of deleting it:

* left: HH:mm time (`rainSlideTime`), which already exists and is colour-coded for
  valid/stale/no-set (`WWA:1428-1443`) — either stop hiding the whole mapinfo container in
  vector mode or add a small time TextView to the timeline row;
* middle: the precipitation colour scale (already built by
  `buildMapLegendRadarColors()`, `WWA:202-214`, and `ForecastBitmap` in legacy mode);
* right: keep `Bild n/24`.

Do not shrink the container to zero in vector mode: the four 48 dp transport targets need
the height (`RadarTimelineTest.java:67-119`). **Unverified:** the exact visual intent of
the band is inferred from the layout plus the two screenshots above; no design document in
the repo describes it.

---

## 3. (d) Data source, frame counts and missing-data states

### 3.1 What the data source is

`APIReaders.RadarMNSetGeoserverRunnable` (`API:1491-1760`) is the only source:

* URL template (`API:1520-1523`):
  `https://maps.dwd.de/geoserver/dwd/wms?service=WMS&version=1.1.0&request=GetMap&layers=dwd:Radar_wn-product_1x1km_ger&bbox=493000.00,5861000.00,1791000.00,7470000.00&time=TIMESTAMP&width=W&height=H&srs=EPSG:3857&format=image/png`
  (`bbox` at `RMN2:197`; `W×H` at `RMN2:62-68` = 1108×1360 at scale 1, 2216×2720 at scale 2).
* Official DWD page for the layer:
  <https://www.dwd.de/DE/leistungen/radarprodukte/radarlayer.html> — `dwd:Radar_wn-product_1x1km_ger`
  is "Reflektivitätskomposit in dB", 1100×1200 km, 1 km resolution, 5-minute cadence,
  "inklusive Vorhersage (0-2h)". Service root: <https://maps.dwd.de/geoserver/dwd/wms>
  (GetCapabilities linked from <https://www.dwd.de/DE/leistungen/geodienste/help/nutzung_geodienste.html>).
  Retrieved/verified 2026-11-28.
* `DATASET_SIZE = 24`, `TIMESTEP_5MINUTES = 5 min` (`API:1496-1497`); 24 × 5 min = 2 h,
  matching DWD's advertised forecast range. The comment says "there are 23 OR 24 future
  images available at the GeoServer".
* `startTime` is rounded **up** to the next UTC 5-minute boundary (`API:1529-1540`) and
  formatted `time=YYYY-MM-DDTHH:MM:00.000Z` (`API:1542-1593`). Each frame `i` is fetched
  at `startTime + i*TIMESTEP_5MINUTES` (`API:1669-1685`).
* `multiFetchRadarSet()` deletes the old set, computes a fresh `startTime`, then fetches
  all 24 frames with 24 threads (`API:1708-1728`). On completion it stores `startTime`
  in `PREF_RADAR_LASTDATAPOLL`; if more than 3 requests errored it reuses the previous
  stored start time and reports `success=false` (`API:1687-1697`).
* Raw HTTP bodies are written straight to `context.getCacheDir()` as
  `radarMN-0.png … radarMN-23.png` (`API:1494-1495`, `1606-1609`, `1650-1666`); no
  re-encoding. The activity queues this once (`WWA:513-527`) and restores `startTime` in
  `onCreate()` (`WWA:605`).
* Validity rule: a frame exists if the file exists, and is *valid* if it is **> 5000 bytes**
  (`API:1611-1624`). A "full" set is `count >= DATASET_SIZE-2` = 22 valid files
  (`API:1626-1637`). `run()` fetches only when the set is incomplete, stale (> 5 min,
  `WeatherSettings.java:795-799`) or forced **and** a suitable network is available;
  otherwise it reuses the cache and reports success (`API:1743-1759`).
* Manual refresh clears all 24 frames and re-queues the download
  (`WWA:849-856`, `RMN2:245-259`).

### 3.2 When is there one image / an empty set? Intended steady state

Intended steady state: **24 valid frames, 5 minutes apart, covering ~2 hours**, present in
the cache and replayed every 750 ms by swapping bitmaps into one MapLibre `ImageSource`
(or onto the legacy canvas). No network access happens per frame.

One frame or no frames occurs in these cases:

| Cause | Mechanism |
|---|---|
| First launch / cache cold | No files until the parallel fetch writes them; `onProgress(progress==1)` draws frame 0 early (`WWA:694-717`). |
| Partial/failed download | `multiFetchRadarSet()` deletes all old files before fetching (`API:1714`); with errors the set can end up with 0–n files and `success=false` (`API:1687-1697`). |
| Failed manual refresh | `clearRadarCache()` first removes all 24 (`RMN2:245-259`), then the new fetch may fail (offline / DWD error). |
| Dry weather | The files can be complete but contain no rain pixels; 2026-11-28 samples were ~37–39 KB vs 96.6 KB for the rainy fixture (`docs/radar-animation-and-fixture-tests.md:167-176`). Visually "empty", but data is present. |
| Stale cached set | Offline or within the 5-minute freshness window the old set is reused with its old `startTime` (`API:1754-1758`); HH:mm then lies about the pixels unless the orange/yellow logic warns (`WWA:1432-1441`). |
| Near-empty file passes validation | `radarCacheFileValid()` only checks > 5000 bytes, and `fullRadarDataSet()` accepts 22 files — a truncated PNG can pass. |

### 3.3 What should be shown when unavailable / when only one frame exists

Current behaviour is dishonest: the timer always walks 0..23, `updateRainFrameText()`
always prints `/24`, and invalid indices make `applyVectorRadarFrame()` return silently
(`WWA:2949-2951`) or `drawRadarSlide()` do nothing (`WWA:1798-1800`), so the pixels freeze
while the UI keeps moving.

Recommended rule:

1. Derive a **usable frame count** from the cache (e.g. the largest `n` such that frames
   `0..n-1` are all `radarCacheFileValid()`), not from `DATASET_SIZE`.
2. Drive the timer wrap (`WWA:307-319`), `normalizeRainFrame()` (`WWA:1721-1731`), the
   SeekBar `max`, the caption denominator (`WWA:1733-1738`) and `getSeekBarPosition()`
   (`WWA:1576-1579`) from that count.
3. If `usableCount <= 1`: do not start the animation, hide/disable the timeline and
   transport controls, and show no `Bild 1/24`. Keep the basemap and warning polygons.
   Optionally show a short status such as "Radar nicht verfügbar" in the time label; if a
   stale set is deliberately reused, keep the existing yellow/orange colour hint
   (`WWA:1432-1441`) and label it as stale.
4. Keep 24 as the *download target*, not as a display promise.

---

## 4. (e) What the radar image contains

* Product semantics: DWD WN reflectivity in dB, 1×1 km, 5-minute cadence, 0–2 h forecast
  (official URL in §3.1). The app's palette is `RMAP:103-108`:
  `RAINCOLORS[0] = 0x00000000` (transparent clutter/no-data) and indices 1–16 from
  `0xff99ffff` to `0xffff33ff`, paired with `DBZVALUES` 5.5 … 85 dBZ (index 0 = 1 dBZ).
  The legend uses indices 1..16 (`WWA:207-214`).
* Decoding (`RMN2:265-292`) maps four **exact** background ARGB values to
  `Color.TRANSPARENT`: `0xFFBEBEBE`, `0xFFFC3FFF`, `0xFFBDBDBD`, `0xFFFFFFFF`
  (decimal `-4276546`, `-245761`, `-4342339`, `-1`). Only exact `RAINCOLORS` pixels survive
  as rain; small anti-aliased blends remain opaque but are not palette entries.
* Therefore: **no colour means no precipitation (or clutter/background), not missing
  data**. Files from dry days are still valid 5-minute frames.
* Real fixture (`docs/radar-real-fixture.md:1-43`, `docs/radar-animation-and-fixture-tests.md:246-263`):
  DWD WMS frame `time=2026-11-26T06:00:00.000Z`, 1108×1360 (hi-res 2216×2720 used on the
  2026-11-28 emulator run), SHA-256 `aa69756c2dc6ca50e40cdd1bf0fdc38fa0b0e14b0eed5feafe0bb109a4aa1e9f`.
  It contains a coherent rain band over southern Germany / Switzerland / Austria:
  **92,050** exact palette-rain pixels (6.1 %), **1,408,487** background pixels turned
  transparent, **98,393** opaque pixels after the transform. By eye: cyan/turquoise/green
  speckles, not solid rectangles (`docs/radar-alignment-qa.md:40-44`).
  Same-day 2026-11-28 frames were essentially dry (~100 exact rain pixels per frame).
* The two production paths consume this bitmap identically: `ImageSource.setImage(bitmap)`
  (`WWA:2971-2977`) and the legacy canvas (`WWA:1785-1795`).

---

## 5. (g) Layer order and opacity — recommendation

Current vector style stack (bottom → top): basemap tiles → radar `RasterLayer` → warning
fills → warning outlines → excluded fills/outlines → pin/wind overlays.

* Radar layer: `RADAR_LAYER_ID = "twfg-radar-raster"`,
  `RADAR_RASTER_OPACITY = 0.8f` (`WWA:195-198`). It is created with
  `rasterOpacity(0.8)` and `rasterFadeDuration(0)` and inserted **below** the warning fill
  layer (`WWA:2978-2988`).
* Warning fills: `fillOpacity = 0.45`, outlines 2 px (`WWA:2742-2761`); excluded fills
  0.30 and dashed outlines (`WWA:2767-2790`). The reverse insertion guard keeps warning
  fills above radar after a style reload (`WWA:2747-2753`).
* Verified visually: task71 screenshots show the yellow frost polygons and magenta
  outlines readable above the cyan/green rain band
  (`docs/radar-real-fixture.md:121-127`).

**Recommendation: keep this order; do not make radar topmost.** Reasons:

1. Warning severity is encoded by fill colour; a radar layer on top would tint those
   polygons (green/cyan over yellow) and change their perceived severity.
2. Radar is already transparent where there is no rain (≈93.5 % of the real fixture) and
   the 0.8 raster opacity lets the basemap through, so "underlying data stays visible" is
   already satisfied for the base map.
3. With radar below, the translucent 0.45 warning fill still lets rain show through inside
   warning areas — both datasets remain readable at once.

**Trade-off:** in very heavy rain the bright radar colours can compete with a faint
warning fill. If that is observed, lower opacity rather than reorder: change
`RADAR_RASTER_OPACITY` at `WWA:198` from `0.8f` to about `0.7f` (the single use site is
`WWA:2981`), leaving `WARNING_FILL_OPACITY` (`WWA:193`) alone. Keep the insertion at
`WWA:2984-2988` and the symmetric guard at `WWA:2749-2753` so the order survives style
reloads. Any change must be re-checked with the TASK-71 fixture screenshots (same rain
band, same warning polygons). **Unverified:** no quantitative contrast measurement was
made; the 0.7 suggestion is a judgement call, not a measured optimum.

---

## 6. (f) Screenshot inventory (all local, none uploaded)

All files below exist on disk (checked with `ls -la`), are mode `0600`, and live under
`/var/home/j/.cache/tmp/opencode/`. They were captured on the local QA device/workstation
for TASK-54/55/70/71; **nothing was uploaded to any external service in this task**.

* TASK-71 descriptions come from `docs/radar-real-fixture.md:101-117`; I additionally
  opened `task71-real-timeline-label-crop.png` and `task71-real-final-vector-full.png`.
* TASK-70/54/55 descriptions are **inferred from the filenames and the task context**;
  those images were not individually re-opened here, so treat their one-line descriptions
  as best-effort labels rather than verified visual facts.

| Screenshot (absolute path) | Description |
|---|---|
| `/var/home/j/.cache/tmp/opencode/task71-real-default-crop.png` | same as default, cropped to map area only |
| `/var/home/j/.cache/tmp/opencode/task71-real-default-full.png` | default zoom, vector map, timeline visible, auto-play running |
| `/var/home/j/.cache/tmp/opencode/task71-real-final-rain-crop.png` | cropped final rain band |
| `/var/home/j/.cache/tmp/opencode/task71-real-final-vector-full.png` | final clean vector view: rain band + yellow polygons + warning list + Bild 1/24 |
| `/var/home/j/.cache/tmp/opencode/task71-real-playing-full.png` | PLAY running; seekbar advanced along the 24-frame timeline |
| `/var/home/j/.cache/tmp/opencode/task71-real-south-zoom-crop.png` | cropped version of the southern rain band |
| `/var/home/j/.cache/tmp/opencode/task71-real-south-zoom-full.png` | zoomed into the southern band: turquoise/cyan/green rain, yellow warning polygons still legible |
| `/var/home/j/.cache/tmp/opencode/task71-real-stepped-crop.png` | cropped frame-0 vs frame-1 comparison; 22,508 / 140,300 pixels (16.04 %) differ |
| `/var/home/j/.cache/tmp/opencode/task71-real-stepped-full.png` | STEP FORWARD: Bild 2/24, frame-1 pixels visibly different |
| `/var/home/j/.cache/tmp/opencode/task71-real-stopped-frame0.png` | STOP pressed; resets to Bild 1/24 and restores the play icon |
| `/var/home/j/.cache/tmp/opencode/task71-real-timeline-label-crop.png` | timeline caption Bild 1/24, seekbar and play/stop/back/forward controls |
| `/var/home/j/.cache/tmp/opencode/task71-real-zoomed-out-full.png` | zoomed out to the full DWD composite; cyan/green rain band over southern Germany/Austria |
| `/var/home/j/.cache/tmp/opencode/task71-real-zoomed-out-rain-crop.png` | cropped rain band at the zoomed-out zoom level |
| `/var/home/j/.cache/tmp/opencode/task70-after-pause-wait-crop.png` | after PAUSE then waiting: frame index must not advance (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-after-pause-wait.png` | after PAUSE then waiting: frame index must not advance (full view) |
| `/var/home/j/.cache/tmp/opencode/task70-after-play-crop.png` | state immediately after pressing PLAY (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-after-play.png` | state immediately after pressing PLAY |
| `/var/home/j/.cache/tmp/opencode/task70-before-overlap-crop.png` | before the timeline/control overlap fix (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-before-overlap.png` | before the timeline/control overlap fix (full view) |
| `/var/home/j/.cache/tmp/opencode/task70-final-landscape-timeline.png` | tight crop of the final landscape timeline |
| `/var/home/j/.cache/tmp/opencode/task70-final-landscape.png` | final landscape state after the control sequence |
| `/var/home/j/.cache/tmp/opencode/task70-land-paused-crop.png` | landscape state after PAUSE (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-land-paused-stable-crop.png` | settled landscape state after PAUSE (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-land-paused-stable.png` | settled landscape state after PAUSE |
| `/var/home/j/.cache/tmp/opencode/task70-land-paused.png` | landscape state after PAUSE |
| `/var/home/j/.cache/tmp/opencode/task70-land-sequence.png` | landscape montage of successive radar frames |
| `/var/home/j/.cache/tmp/opencode/task70-land-step-back-crop.png` | landscape state after STEP BACK (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-land-step-back.png` | landscape state after STEP BACK |
| `/var/home/j/.cache/tmp/opencode/task70-land-step-forward-crop.png` | landscape state after STEP FORWARD (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-land-step-forward.png` | landscape state after STEP FORWARD |
| `/var/home/j/.cache/tmp/opencode/task70-land-stop-reset-crop.png` | landscape state after STOP reset (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-land-stop-reset.png` | landscape state after STOP reset |
| `/var/home/j/.cache/tmp/opencode/task70-landscape-logo-area.png` | crop of the landscape top-left logo area |
| `/var/home/j/.cache/tmp/opencode/task70-landscape-small.png` | landscape vector view with the TASK-70 timeline (downscaled full view) |
| `/var/home/j/.cache/tmp/opencode/task70-landscape-timeline.png` | tight crop of the landscape timeline bar |
| `/var/home/j/.cache/tmp/opencode/task70-landscape-wait.png` | landscape view while waiting for the timeline/settling |
| `/var/home/j/.cache/tmp/opencode/task70-landscape.png` | landscape vector view with the TASK-70 timeline |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-before-pause-crop.png` | legacy state before PAUSE (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-before-pause.png` | legacy state before PAUSE |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-landscape-small.png` | legacy static map in landscape with timeline (downscaled full view) |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-landscape.png` | legacy static map in landscape with timeline |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-landscape2-bottom.png` | bottom strip of the second legacy landscape capture |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-landscape2.png` | second legacy landscape capture |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-paused-crop.png` | legacy state after PAUSE (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-paused-stable-crop.png` | settled legacy state after PAUSE (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-paused-stable.png` | settled legacy state after PAUSE |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-paused.png` | legacy state after PAUSE |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-step-back-crop.png` | legacy state after STEP BACK (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-step-back.png` | legacy state after STEP BACK |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-step-forward-crop.png` | legacy state after STEP FORWARD (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-step-forward.png` | legacy state after STEP FORWARD |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-stop-crop.png` | legacy state after STOP/reset (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-stop.png` | legacy state after STOP/reset |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-timeline-actions.png` | legacy timeline with the transport buttons visible |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-timeline.png` | legacy static-map timeline bar |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-timeline2-crop.png` | second legacy timeline capture (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-timeline2.png` | second legacy timeline capture |
| `/var/home/j/.cache/tmp/opencode/task70-legacy-times.png` | legacy HH:mm time-label states |
| `/var/home/j/.cache/tmp/opencode/task70-paused-crop.png` | state after pressing PAUSE (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-paused-stable-crop.png` | settled state after PAUSE (no further frame advance) (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-paused-stable.png` | settled state after PAUSE (no further frame advance) |
| `/var/home/j/.cache/tmp/opencode/task70-paused.png` | state after pressing PAUSE |
| `/var/home/j/.cache/tmp/opencode/task70-portrait-attribution.png` | crop of the map attribution text area |
| `/var/home/j/.cache/tmp/opencode/task70-portrait-initial-small.png` | initial portrait view after the timeline appears (downscaled full view) |
| `/var/home/j/.cache/tmp/opencode/task70-portrait-initial.png` | initial portrait view after the timeline appears |
| `/var/home/j/.cache/tmp/opencode/task70-portrait-logo-area.png` | crop of the top-left logo/app-icon area |
| `/var/home/j/.cache/tmp/opencode/task70-portrait-timeline.png` | tight crop of the portrait timeline bar |
| `/var/home/j/.cache/tmp/opencode/task70-portrait.png` | portrait vector view with the TASK-70 timeline |
| `/var/home/j/.cache/tmp/opencode/task70-replay-crop.png` | state after replaying the sequence (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-replay.png` | state after replaying the sequence |
| `/var/home/j/.cache/tmp/opencode/task70-restore-check-small.png` | check that the restored timeline state matches the pre-interaction state (downscaled full view) |
| `/var/home/j/.cache/tmp/opencode/task70-restore-check.png` | check that the restored timeline state matches the pre-interaction state |
| `/var/home/j/.cache/tmp/opencode/task70-sequence-montage.png` | montage of successive radar frames during playback |
| `/var/home/j/.cache/tmp/opencode/task70-settings-small.png` | settings screen showing radar-related preferences (downscaled full view) |
| `/var/home/j/.cache/tmp/opencode/task70-settings.png` | settings screen showing radar-related preferences |
| `/var/home/j/.cache/tmp/opencode/task70-settings2-small.png` | second settings screen (scroll position 2) (downscaled full view) |
| `/var/home/j/.cache/tmp/opencode/task70-settings2.png` | second settings screen (scroll position 2) |
| `/var/home/j/.cache/tmp/opencode/task70-settings3-small.png` | third settings screen (scroll position 3) (downscaled full view) |
| `/var/home/j/.cache/tmp/opencode/task70-settings3.png` | third settings screen (scroll position 3) |
| `/var/home/j/.cache/tmp/opencode/task70-settings4-small.png` | fourth settings screen (scroll position 4) (downscaled full view) |
| `/var/home/j/.cache/tmp/opencode/task70-settings4.png` | fourth settings screen (scroll position 4) |
| `/var/home/j/.cache/tmp/opencode/task70-settings5-small.png` | fifth settings screen (scroll position 5) (downscaled full view) |
| `/var/home/j/.cache/tmp/opencode/task70-settings5.png` | fifth settings screen (scroll position 5) |
| `/var/home/j/.cache/tmp/opencode/task70-settings6-small.png` | sixth settings screen (scroll position 6) (downscaled full view) |
| `/var/home/j/.cache/tmp/opencode/task70-settings6.png` | sixth settings screen (scroll position 6) |
| `/var/home/j/.cache/tmp/opencode/task70-step-back-crop.png` | state after one STEP BACK press (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-step-back.png` | state after one STEP BACK press |
| `/var/home/j/.cache/tmp/opencode/task70-step-forward-x2-crop.png` | state after two STEP FORWARD presses (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-step-forward-x2.png` | state after two STEP FORWARD presses |
| `/var/home/j/.cache/tmp/opencode/task70-stop-reset-crop.png` | state after STOP reset to frame 0 (tight crop) |
| `/var/home/j/.cache/tmp/opencode/task70-stop-reset.png` | state after STOP reset to frame 0 |
| `/var/home/j/.cache/tmp/opencode/task70-timeline-before-interaction.png` | full portrait view before the first timeline interaction |
| `/var/home/j/.cache/tmp/opencode/task54-default-zoom.png` | same default view after a zoom step |
| `/var/home/j/.cache/tmp/opencode/task54-default.png` | vector map default view with the radar timeline visible |
| `/var/home/j/.cache/tmp/opencode/task54-seek.png` | state after dragging the radar SeekBar |
| `/var/home/j/.cache/tmp/opencode/task54-seekbar-cycled.png` | state after cycling through SeekBar positions |
| `/var/home/j/.cache/tmp/opencode/task54-synthetic-default.png` | synthetic radar patch injected for georeferencing verification (test data) |
| `/var/home/j/.cache/tmp/opencode/task54-zoomed.png` | zoomed view of the georeferenced radar overlay |
| `/var/home/j/.cache/tmp/opencode/task54-zoomed2.png` | second zoomed view of the radar overlay |
| `/var/home/j/.cache/tmp/opencode/task54-zoomtest.png` | zoom-level test view of the overlay |
| `/var/home/j/.cache/tmp/opencode/task55-600dp.png` | 600dp-width (tablet) layout with map overlays |
| `/var/home/j/.cache/tmp/opencode/task55-landscape.png` | landscape layout with map overlays |
| `/var/home/j/.cache/tmp/opencode/task55-legacy.png` | legacy static-map mode with overlays |
| `/var/home/j/.cache/tmp/opencode/task55-portrait-combined.png` | portrait layout, combined overlays view |
| `/var/home/j/.cache/tmp/opencode/task55-portrait.png` | portrait layout with map overlays |
| `/var/home/j/.cache/tmp/opencode/task55-tap.png` | state after tapping a warning polygon/pin |
| `/var/home/j/.cache/tmp/opencode/task55-zoomed.png` | zoomed state with map overlays |

Source documentation for the sets:
`docs/radar-real-fixture.md:101-117` (TASK-71),
`docs/radar-animation-and-fixture-tests.md` and commit `fa1431f0` (TASK-70),
`docs/radar-alignment-qa.md` (TASK-54/55 QA practice), commits `6b57db0a` (TASK-54) and
`f18bb8a8` (TASK-55).

---

## 7. Recommended fixes for TASK-87 (priority order)

1. **Fix the frame caption** — `WWA:1733-1738`, `1773-1802`: write `Bild n/N` from the
   drawn frame inside `drawRadarSlide()`, passing `count` rather than reading the mutable
   field. Pass the same frame to `updateSlideTimeText()` (`WWA:1428-1443`) so caption,
   time and slider always describe the same frame.
2. **Derive the frame count from the cache** — add a helper next to `fullRadarDataSet()`
   (`API:1626-1637`) returning the usable frame count, then use it for the timer wrap
   (`WWA:307-319`), `normalizeRainFrame()` (`WWA:1721-1731`), SeekBar range and caption
   denominator. For `count <= 1`, suppress animation/controls and show an honest
   unavailable state (§3.3).
3. **Use the empty band** — `LAYOUT:179-194` / `WWA:3210-3212`: place the HH:mm label on
   the left of the timeline caption row and the precipitation colour strip in the middle
   for vector mode; keep the counter on the right.
4. **Optional, only after visual comparison** — lower `RADAR_RASTER_OPACITY` (`WWA:198`,
   `2981`) from 0.8 toward 0.7 if bright rain obscures warning fills; do **not** move radar
   above the warning layers.
5. **Regression tests (JVM/Robolectric, no emulator)** — extend `RadarTimelineTest` /
   `VectorRadarTest` with source-level pins that `drawRadarSlide()` calls the frame-caption
   updater and that the denominator is not a hard-coded 24; add a pure helper test for the
   frame-count → caption mapping. Keep the existing fixture tests
   (`RadarRealFixtureTest`, `RadarFrameSetFixtureTest`) unchanged.

**Scope statement:** no files other than this report were changed in this worktree; no
commit or push was made; no other task was touched; no emulator was started and no data
was uploaded.

**Unverified items (summary):** the precise product/colour semantics rely on the DWD
web page and WMS behaviour documented in `docs/radar-real-fixture.md` (retrieved 2026-11-28,
not re-fetched in this task); the "empty band" interpretation is inferred from layout and
two screenshots; TASK-70/54/55 screenshot descriptions are filename-derived, not
individually inspected; the proposed 0.7 opacity is a judgement, not a measurement.

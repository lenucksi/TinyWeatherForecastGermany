# Real DWD radar fixture (TASK-71)

Purpose: a **real**, unmodified radar frame from the same official DWD endpoint the app
uses, containing visible precipitation, for on-device QA. No app code was changed.

## 1. Source and retrieval

Exact URL (one line; `time` is the 5-minute-aligned frame timestamp, colons are sent raw
exactly as the app builds them):

```
https://maps.dwd.de/geoserver/dwd/wms?service=WMS&version=1.1.0&request=GetMap&layers=dwd%3ARadar_wn-product_1x1km_ger&bbox=493000.00%2C5861000.00%2C1791000.00%2C7470000.00&time=2026-11-26T06:00:00.000Z&width=1108&height=1360&srs=EPSG%3A3857&styles=&format=image%2Fpng
```

Hi-res variant (same URL with `width=2216&height=2720`) was also downloaded.

- Retrieval time: **2026-11-28T10:30:35Z** (curl, `HTTP/1.1 200`, `Content-Type: image/png`,
  `Content-Disposition: inline; filename=dwd-Radar_wn-product_1x1km_ger.png`).
- Response headers: `Warning: 99 Default value used: REFERENCE_TIME=[2026-11-28 10:25:00.0]`,
  `geowebcache-cache-result: MISS`, `Access-Control-Allow-Origin: *`. Raw headers retained in
  `/var/home/j/.cache/tmp/opencode/radar-real-20261126T0600Z.headers.txt` (0600).
- WMS capabilities (`https://maps.dwd.de/geoserver/dwd/wms?service=WMS&version=1.1.0&request=GetCapabilities`,
  retrieved 2026-11-28T10:25Z) advertise this layer's time extent as
  `2026-11-25T00:00:00.000Z/2026-11-28T12:20:00.000Z/PT5M`.
- Why an archive frame: frames for 2026-11-28 (the current day) are essentially dry — a full
  day of sampled timestamps yielded only ~100 exact rain-palette pixels per frame. The
  2026-11-26 06:00 UTC archive frame has a clear, coherent rain band (see below), so it is the
  first choice for visual verification.

## 2. Local fixture files (all mode 0600, outside the repo)

| File | Dimensions | Bytes | Purpose |
|---|---|---|---|
| `/var/home/j/.cache/tmp/opencode/radar-real-20261126T0600Z.png` | 1108 × 1360 | 96,599 | fixture for scale-factor-1 devices |
| `/var/home/j/.cache/tmp/opencode/radar-real-20261126T0600Z-hires.png` | 2216 × 2720 | 256,553 | **fixture used on the emulator (scale factor 2)** |
| `/var/home/j/.cache/tmp/opencode/radar-real-20261126T0600Z.headers.txt` | — | 1,237 | raw HTTP response headers |
| `/var/home/j/.cache/tmp/opencode/radar-real-20261126T0600Z.meta.txt` | — | 556 | retrieval time, URL, sha256 |
| `/var/home/j/.cache/tmp/opencode/radar_png_stats.py`, `png_crop.py` | — | — | pure-stdlib PNG decoder/stats/crop helpers |

SHA-256 of primary PNG: `aa69756c2dc6ca50e40cdd1bf0fdc38fa0b0e14b0eed5feafe0bb109a4aa1e9f`.
SHA-256 of hi-res PNG: `6ec08709b89d3b7df6ceaf649ad62065a777bcc1e58f299c309389eed5cce751`.
Independent re-downloads produced identical SHA-256 values, and 24 consecutive frames
(2026-11-26 06:00–07:55 UTC) were downloaded for the on-device timeline.

## 3. Why this is byte-for-byte the app's expected format

- URL template: `APIReaders.java:1522` — WMS 1.1.0 `GetMap`, layer
  `dwd:Radar_wn-product_1x1km_ger`, `srs=EPSG%3A3857`, `styles=` (default), `format=image%2Fpng`.
- BBOX: `RadarMN2.java:197` — `493000.00,5861000.00,1791000.00,7470000.00` (EPSG:3857).
- Timestamp format: `APIReaders.java:1542-1593` — `time=YYYY-MM-DDTHH:MM:00.000Z`, 5-minute grid.
- Dimensions: `APIReaders.java:1522` uses `RadarMN2.getFixedRadarMapWidth/Height`
  (`RadarMN2.java:62-68`) = `scaleFactor × 1108` by `scaleFactor × 1360`, with `scaleFactor`
  from `RadarMN2.java:29-41`.
  **Important:** `RadarMN2.java:34` compares `displayMetrics.widthPixels > 1079` in *pixels*,
  not dp. The emulator is 1080 × 2400 @ 420 dpi, so `scaleFactor == 2` and the app requires
  **2216 × 2720**. Injecting the 1108 × 1360 variant caused
  `java.lang.IllegalArgumentException: x + width must be <= bitmap.width()` at
  `RadarMN2.getBitmap()` (`RadarMN2.java:272`) and a MapLibre JNI abort; using the hi-res
  variant fixed it. This is an alignment requirement, not a defect in the fixture.
- PNG is written to cache as the **raw HTTP body** (`APIReaders.java:1650-1666`), no
  re-encoding; `BitmapFactory.decodeFile` reads it back in `RadarMN2.java:265-272`. Our files
  are the raw bodies saved by curl (`file`: `PNG image data, 2216 x 2720, 8-bit/color RGB,
  non-interlaced`).
- Cache naming: prefix `radarMN-`, suffix `.png` (`APIReaders.java:1494-1495`), file
  `new File(context.getCacheDir(), prefix+count+suffix)` (`APIReaders.java:1606-1609`).
  A frame is valid when larger than 5000 bytes (`APIReaders.java:1616-1624`);
  a full set is 24 frames at 5-minute steps (`APIReaders.java:1496-1497`, `1671-1676`,
  `1626-1637`).
- Colour palette: `Radarmap.java:103-105` (`RAINCOLORS`). The app makes four exact colours
  transparent when decoding: `#FFBEBEBE`, `#FFFC3FFF`, `#FFBDBDBD`, `#FFFFFFFF`
  (`RadarMN2.java:274-286`). Both legacy static-map and vector/MapLibre paths use this decode
  (`WeatherWarningActivity.java:1570-1596`, `2739-2771`).

## 4. On-device verification (2026-11-28, emulator-5554, Android 17)

Build/install (docs-only branch; all gates green):

```
TWFG_REPO=<this worktree> TWFG_GRADLE_HOME=/var/home/j/.gradle-twfg-clean \
  bash scripts/build.sh :app:assembleDebug :app:testDebugUnitTest :app:lintDebug \
  -Dorg.gradle.dependency.verification=strict
```

`BUILD SUCCESSFUL in 55s`, Gradle exit 0; APK sha256
`8ea1eefab156ab7a9e431be7f0311cfae678f7ce1920dc2cea83e4431f100692`; installed versionName
`0.63.4-debug_202611332-11050010` (the previously installed APK predated TASK-70).

Injection:

- 24 hi-res real frames (06:00–07:55 UTC) pushed to
  `/data/user/0/de.kaffeemitkoffein.tinyweatherforecastgermany/cache/radarMN-0.png` …
  `radarMN-23.png`, owner `u0_a231:u0_a231_cache`, mode `0600`. Frame 0 sha256 equals the
  hi-res fixture sha256 above.
- Radar start-time preference (`DataStorage.DATASTORAGE_RADAR_LASTDATAPOLL = 21`,
  `DataStorage.java:27`; `WeatherSettings.java:777-793`) set to `1795672800000`, i.e.
  **2026-11-26T06:00:00Z**, so `updateSlideTimeText()` (`WeatherWarningActivity.java:1428-1443`)
  bases the timeline on the real frame time.
- Warning-sync timestamp (id 105) set to "now" and airplane mode enabled while capturing, so
  the 34 cached DWD warnings/polygons stayed readable and the app could not replace the frames.

### Screenshots (all 0600)

| Path | What is visible |
|---|---|
| `/var/home/j/.cache/tmp/opencode/task71-real-default-full.png` | default zoom, vector map, timeline visible, auto-play running |
| `/var/home/j/.cache/tmp/opencode/task71-real-default-crop.png` | same, cropped to the map area only |
| `/var/home/j/.cache/tmp/opencode/task71-real-stopped-frame0.png` | STOP pressed; resets to `Bild 1/24`, play icon restored |
| `/var/home/j/.cache/tmp/opencode/task71-real-zoomed-out-full.png` | zoomed out to the full DWD composite; cyan/green rain band across southern Germany/Austria |
| `/var/home/j/.cache/tmp/opencode/task71-real-zoomed-out-rain-crop.png` | cropped rain band at that zoom |
| `/var/home/j/.cache/tmp/opencode/task71-real-south-zoom-full.png` | zoomed into the southern rain band: turquoise/cyan and green precipitation from Switzerland to Austria, yellow warning polygons over Germany still legible |
| `/var/home/j/.cache/tmp/opencode/task71-real-south-zoom-crop.png` | cropped version of that band |
| `/var/home/j/.cache/tmp/opencode/task71-real-stepped-full.png` | STEP FORWARD: `Bild 2/24`, frame 1 pixels visibly different |
| `/var/home/j/.cache/tmp/opencode/task71-real-stepped-crop.png` | cropped comparison: **22,508 / 140,300 pixels (16.04 %) differ** from the frame-0 crop |
| `/var/home/j/.cache/tmp/opencode/task71-real-playing-full.png` | PLAY running: seekbar advanced toward the end of the 24-frame timeline |
| `/var/home/j/.cache/tmp/opencode/task71-real-timeline-label-crop.png` | timeline caption `Bild 1/24`, seekbar and the play/stop/back/forward controls |
| `/var/home/j/.cache/tmp/opencode/task71-real-final-vector-full.png` | final clean vector view: rain band + yellow polygons + warning list + `Bild 1/24` |
| `/var/home/j/.cache/tmp/opencode/task71-real-final-rain-crop.png` | cropped final rain band |

Observations:

- The rain overlay is georeferenced correctly: the southern band sits over southern Germany,
  Switzerland and Austria, matching the raw fixture (whose southern band spans roughly
  lon 5.6–15.7°, lat 46.7–49.0°). Cyan, turquoise, green and yellow pixels are the
  `RAINCOLORS` steps composited through `RADAR_RASTER_OPACITY` and the yellow warning fills.
- The yellow frost-warning polygons and their magenta outlines remain readable above the
  radar layer (`applyVectorRadarFrame` inserts the raster below `WARNING_FILL_LAYER_ID`,
  `WeatherWarningActivity.java:2772-2782`).
- The TASK-70 controls work on the cached real frames: STOP resets to frame 0, STEP FORWARD
  changes both the caption and the rendered rain field, PLAY advances the seekbar. No app
  FATAL occurred during the vector-mode run (logcat checked).
- Caveat: the HH:mm `warningactivity_rainslidetime` TextView is `View.GONE` in vector mode
  (`WeatherWarningActivity.java:3210-3212`), so vector screenshots can only show the
  `Bild n/24` frame caption; the injected start-time preference is the evidence that this
  caption corresponds to 2026-11-26 06:00Z + 5-minute steps. A legacy-mode attempt to show
  the HH:mm label hit a separate pre-existing NPE at
  `WeatherWarningActivity.drawRadarSlide()` (`WeatherWarningActivity.java:1786`,
  `radarBitmap` null) before map initialisation; this is unrelated to the fixture and no app
  code was changed.

## 5. Cleanup (device restored)

Procedure performed after capturing evidence:

```
adb shell am force-stop de.kaffeemitkoffein.tinyweatherforecastgermany
adb shell rm -f /data/user/0/de.kaffeemitkoffein.tinyweatherforecastgermany/cache/radarMN-*.png
adb shell rm -rf /data/local/tmp/task71
adb shell 'sqlite3 /data/user/0/.../databases/weather "update data set value_long=1795863600000 where id=21;"'
adb shell cmd connectivity airplane-mode disable
# reopen Weather warnings and press the refresh button to fetch current frames
```

Proof: cache went from 24 injected frames to 0, then to 24 **current** DWD frames
(`radarMN-0.png` = 122,552 bytes, sha256
`6f12ec1f17d6d26115aa4d3909dedcc9e87a65d10bc566d212b99d50a800db3b`, timestamp
2026-11-28 11:23 UTC). A `sha256sum | grep` over all 24 cache files found **0** matches for
either the 1108 × 1360 or the 2216 × 2720 archive fixture sha256. Airplane mode is off
(`settings get global airplane_mode_on` = 0), the radar preference is back to its original
value, and logcat showed no FATAL after cleanup. No app data was wiped.

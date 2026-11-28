# Radar alignment QA: mandatory fixture cleanup

Alignment tests sometimes inject a **synthetic radar patch** into the DWD frame cache
so a georeferencing change can be seen clearly (e.g. a green/turquoise rectangle over
Berlin or Frankfurt). Such a patch is **test data**, never weather. It must not survive
the test.

## Where the frames live

The vector radar (`RadarMN2` / `WeatherWarningActivity.applyVectorRadarFrame`) reads the
same cache as the legacy static map:

```
/data/data/de.kaffeemitkoffein.tinyweatherforecastgermany/cache/radarMN-0.png
...
/data/data/de.kaffeemitkoffein.tinyweatherforecastgermany/cache/radarMN-23.png
```

(`Context.getCacheDir()`, prefix `radarMN-`, suffix `.png`, 24 five-minute frames.)
On an ordinary device `/data/data` is only reachable with `adb root`; do not clear app
data, only the `radarMN-*.png` files.

## Why a normal refresh used to keep the fixture

`APIReaders.RadarMNSetGeoserverRunnable.run()` reuses the cache when a complete set is
present and not outdated, and `setForceUpdate(true)` is reset to `false` in `run()`.
A fixture frame larger than 5 KB therefore passed `radarCacheFileValid()` and stayed
on screen indefinitely.

TASK-66 added `RadarMN2.clearRadarCache(context)` and wired it into the **refresh**
action (`WeatherWarningActivity.onOptionsItemSelected`, `R.id.menu_refresh`), which
deletes all 24 frames and queues a clean download.

## Teardown checklist (required at the end of *every* alignment test)

1. In the app, open the warnings map in vector mode and tap **refresh** (circular arrow
   in the action bar). This is the normal, user-visible cleanup path.
2. Wait for the rain-slider progress to finish (24 frames, one network fetch per frame).
3. Confirm on the host that all frames were replaced. Either check the `mtime` of
   `radarMN-0.png` .. `radarMN-23.png` is newer than the injection, or pull a few frames
   and assert no solid green/turquoise rectangle remains. The precipitation colours that
   must **not** form large solid blocks are
   `99ffff`, `33ffff`, `00caca`, `009934`, `4dbf1a`, `99cc00` (light rain → moderate rain).
   A real radar frame contains only small, irregular speckles.
4. Capture a screenshot after the refresh (`screencap -p`) and attach it to the task.
   A fixture is not "gone" until that screenshot exists.
5. If the device has no network, fall back to host-side deletion:
   ```
   adb -s <serial> shell 'rm -f /data/data/de.kaffeemitkoffein.tinyweatherforecastgermany/cache/radarMN-*.png'
   ```
   then re-open the warnings activity (or relaunch the app) so the set is downloaded
   again, and repeat steps 3–4. Deleting without a following download leaves an empty
   radar layer — that is acceptable only as a last resort and must be reported.
6. Never leave an injected frame in the cache at the end of a session, and never upload
   a cache file (real radar data may contain location-sensitive information) outside
   the local QA workspace.

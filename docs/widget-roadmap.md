# Widget sizing & font roadmap (TASK-26)

Scope: the four home-screen widgets (`ClassicWidget`, `BoldWidget`,
`ChartWidget`, `ClockWidget`), their layouts and `WidgetRefresher`.

Reports in scope:

- [Starfish/TinyWeatherForecastGermany#295](https://codeberg.org/Starfish/TinyWeatherForecastGermany/issues/295)
  — Android 16: only vertical resizing, empty space, fixed number of days,
  text cut in the corners.
- [Starfish/TinyWeatherForecastGermany#298](https://codeberg.org/Starfish/TinyWeatherForecastGermany/issues/298)
  — Android 13/15: widget does not fit the frame, resize option missing,
  forecast days disappear, font sizing still wrong after the other symptoms
  were fixed.

Linked, explicitly **not implemented** here:

- [Starfish/TinyWeatherForecastGermany#139](https://codeberg.org/Starfish/TinyWeatherForecastGermany/issues/139)
- [Starfish/TinyWeatherForecastGermany#140](https://codeberg.org/Starfish/TinyWeatherForecastGermany/issues/140)

These request *user-selectable widget contents* (which fields/days a widget
shows). They are linked as the follow-up for configurable days, but this
change keeps the current automatic day selection; no new preference, string or
configuration UI is added.

## Root causes found

1. `minResizeWidth` equalled `minWidth` for the Bold, Classic and Clock
   providers, so launchers had no horizontal resize range. Conversely,
   `minResizeHeight` was far below the height the layouts need (e.g. 53dp for
   a three-row Bold layout), so a vertical resize could clip content.
2. `targetCellHeight="1"` for the Bold and Chart providers on Android 12+
   overrode the taller dp defaults; one cell (~50–60dp) cannot hold the
   three-row Bold layout, the chart plus its DWD note, or the clock plus
   weather rows.
3. Canvas-rendered forecast text measured font sizes in pixels without the
   system font scale; the search loop had no lower bound and could run away
   for narrow cells. Temperature strings were centred with the weekday paint.
4. The clock text size was computed as `pixels / scaledDensity`, which divides
   out the user's font-size setting, so it never grew with the system scale.
5. The BoldWidget compared launcher **dp** widths to thresholds measured in
   **pixels** via `Paint`; on high-density devices the thresholds were far too
   high and the widget silently dropped from 5 to 2 forecast days.
6. The chart note measured `dwd_notice` while the layout displays the longer
   `dwd_notice_app`, ignored its 16dp horizontal margins and could make the
   chart bitmap height zero.
7. WidgetRefresher sent the ChartWidget its refresh action using the
   ClockWidget constant (the values happen to be identical today).

## Small fixes in this change

- **Sizing metadata:** every provider now declares distinct default and
  resize bounds plus `maxResizeWidth`/`maxResizeHeight`; the Android 12+
  variants use `targetCellHeight="2"` for Bold/Chart and 4x2 for the clock.
- **Font scaling:** shared pure arithmetic in
  `ClassicWidget.WidgetSizing` clamps requested sp text to the actual frame in
  px using `density * fontScale`; the clock, the canvas forecast bar and the
  chart note all use it. Text views get `ellipsize="end"`/`maxLines` so a long
  station name or warning cannot be cut at the widget corner.
- **Resize behaviour:** day/layout selection uses dp thresholds with a damped
  font-scale factor; a 260dp widget keeps three days even at 1.3x font scale,
  and wide widgets still show four or five days.
- **Refresh:** the chart provider receives its own refresh action.

## Remaining roadmap

1. *Done (this change):* sizing bounds, font scaling, day preservation,
   ellipsis, refresh.
2. *Next:* supply `OPTION_APPWIDGET_SIZES`-based `RemoteViews` size mappings
   so the launcher can switch compact/wide layouts without waking the app.
   This needs new layout variants and belongs to a separate change.
3. *Deferred to #139/#140:* make the number of days and the visible fields
   user-selectable. Without a settings UI and new strings the automatic
   policy above is the safest way to keep days visible.
4. *Follow-up:* `DeviceTweaks.confirmPlausibleWidgetSize` uses index `[0]`
   (width) for its default height fallback; untouched here because that file
   is outside this task's ownership.

## Verification

- Unit tests: `WidgetSizingTest` (font scale / day counts / bounds) and
  `WidgetProviderSizingTest` (provider metadata, refresh action, roadmap
  links).
- Build: `assembleDebug`, `testDebugUnitTest`, `lintDebug` with strict
  dependency verification.
- Emulator: install the worktree debug APK, place each widget, trigger the
  app-namespace refresh broadcast, capture phone screenshots at font scale
  1.0 and 1.3 (restore 1.0), and repeat on a 600dp+ configuration.

# Licensing and attribution (fork)

## Fork license

**SPDX-License-Identifier: AGPL-3.0-or-later**

This fork is licensed under the GNU Affero General Public License, version 3 or
(at your option) any later version. The canonical, verbatim license text is
[`COPYING`](../COPYING): it is byte-identical to
<https://www.gnu.org/licenses/agpl-3.0.txt>
(SHA-256 `0d96a4ff68ad6d4b6f1f30f713b18d5184912ba8dd389f86aa7710db079abcb0`).
Copyright and attribution notices are collected in [`NOTICE`](../NOTICE) and in the
License/Credits sections of [`README.md`](../README.md).

## Vendored code

Every vendored source keeps its original copyright and license header; headers were
checked verbatim and are not rewritten:

| Component | Source file | Upstream license | Header |
| --- | --- | --- | --- |
| Gadgetbridge `WeatherSpec` v4 | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/model/WeatherSpec.java` | AGPL-3.0-or-later | Copyright (C) 2016-2024 Andreas Shimokawa, Arjan Schrijver, beardhatcode, Carsten Pfeiffer, Daniele Gobbetti, Enrico Brambilla, José Rebelo, Taavi Eomäe; full AGPLv3 notice |
| Gadgetbridge model package note | `app/src/main/java/nodomain/freeyourgadget/gadgetbridge/model/package-info.java` | (package doc for the above) | Names Gadgetbridge as origin of `WeatherSpec` |
| PNPOLY point-in-polygon | `app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/Polygon.java` | original author's terms, referenced from upstream | `isInPolygon()` javadoc credits Wm. Randolph Franklin (1970-2003) and links the original source |
| Astronomy class | `app/src/main/java/org/astronomie/info/Astronomy.java` | GPL-3.0-or-later | Copyright (C) 2010-2012 Helmut Lehmeyer; full GPLv3 notice |

The Android `androidx.annotation` types used by the unmodified `WeatherSpec` are supplied
`compileOnly` in `app/build.gradle`; no annotation library source is vendored, so no
second license text is introduced into the tree. The AGPLv3-or-later and GPLv3-or-later
notices above are license-compatible with this fork's AGPLv3-or-later grant.

## Why AGPLv3-or-later (decision D-09)

The fork adopts AGPLv3-or-later uniformly because the vendored Gadgetbridge `WeatherSpec`
v4 is AGPLv3-or-later and is kept upstream-verbatim. Under AGPLv3 §5 a derivative work
must be licensed under the same license, so applying GPLv3-only to the fork would
conflict with the included class. Using "or later" throughout also matches the upstream
Gadgetbridge and Astronomy grants.

## No clean-room replacement is planned

`WeatherSpec` will continue to be carried verbatim rather than reimplemented from a
specification, for four reasons:

1. **License compatibility.** Keeping the original AGPLv3-or-later file preserves the
   upstream copyright and authorship notices required by AGPLv3 §4/§5; a clean-room
   rewrite would still be a derivative work of the wire format and would add no
   licensing benefit.
2. **Protocol correctness.** `WeatherSpec` v4 defines the Gadgetbridge weather broadcast
   format used since Gadgetbridge 0.48.0. An independent reimplementation risks
   field-level drift (temperatures, units, timestamps, moon phase) that is hard to
   detect in unit tests but breaks users' wearables.
3. **Attribution integrity.** The original authors are named in the source header, in
   `NOTICE` and in the README Credits; verbatim copying keeps those attributions intact.
4. **Maintenance cost.** Reimplementing and then continually re-syncing a divergent
   copy costs more than carrying one upstream file with a `compileOnly` annotation
   dependency.

## F-Droid metadata preparation

The F-Droid package recipe (in the external `fdroiddata` repository; the in-tree
`fastlane/metadata/android/<locale>/` files cover descriptions and changelogs only)
must declare the license with the SPDX identifier, not the legacy GPL wording:

```
License: AGPL-3.0-or-later
```

Use the same value for IzzyOnDroid and for the planned fork repository at
<https://lenucksi.github.io/TinyWeatherForecastGermany/fdroid/repo/>. `License:` is a
single-value field: do **not** enter `GPL-3.0-or-later`, even though older resources
still carry that wording (see follow-ups below).

## Known inconsistencies outside this task's scope

The following still say "GNU General Public License" and should be aligned by a change
that owns Java sources / Android resources / root build config:

- `app/src/main/res/raw/license.txt` ships the GPLv3 text and is what the in-app
  "License" screen (`LicenseInfo.java`) displays.
- Java file headers in the app package and the root `build.gradle` use the GPLv3
  wording.
- The PNPOLY note in `Polygon.java` points at a `COPYING.txt` that is not present in
  this tree; the upstream license reference should be supplied or corrected.

This documentation task intentionally does not touch those files; the repository-level
license (`COPYING`), `README.md`, `NOTICE` and the `app/build.gradle` header already
state AGPL-3.0-or-later.

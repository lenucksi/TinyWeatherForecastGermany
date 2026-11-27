/**
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2026 Pawel Dube
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Display-name overrides for DWD stations whose derived municipality name is misleading.
 *
 * <p>Where this mapping comes from:</p>
 * <ul>
 *   <li>DWD station lexicon (Stationslexikon):
 *       https://www.dwd.de/DE/leistungen/klimadatendeutschland/stationsliste.html
 *       (download: /DE/leistungen/klimadatendeutschland/statliste/statlex_html.html?view=nasPublication,
 *       checked 2026-11-27). It lists station code {@code L821} as "Wiesbaden-Auringen",
 *       50.132 N / 8.317 E, 263 m. The same name/id/coordinates are already present in
 *       {@code res/raw/stations6.txt}; that station-list mapping is correct and is not changed.</li>
 *   <li>Codeberg issue #306: after selecting L821 the UI showed "Wiesbaden-Süd" although
 *       Auringen is in the north-east of Wiesbaden. The DWD warning-cell polygon
 *       "Wiesbaden-Süd" ({@code 706414102} in res/raw/areas.txt) happens to cover the station
 *       point, and {@link WeatherLocationManager#getDescriptionAlternate} derives the
 *       municipality name from warning cells. Warning-cell geometry is official warning
 *       data and must not be edited, so only the derived display name is overridden here.</li>
 * </ul>
 *
 * <p>The map is a fixed set of constants: station-data import stays deterministic and
 * no other station is affected. Entries use the station id (second field of stations6.txt,
 * also used in DWD Open Data file names) and the exact station name from stations6.txt.</p>
 */
public final class StationNameMapping {

    public static final String STATION_WIESBADEN_AURINGEN = "L821";
    public static final String NAME_WIESBADEN_AURINGEN = "WIESBADEN-AURINGEN";

    private static final Map<String, String> OVERRIDES;

    static {
        Map<String, String> overrides = new LinkedHashMap<String, String>();
        overrides.put(STATION_WIESBADEN_AURINGEN, NAME_WIESBADEN_AURINGEN);
        OVERRIDES = Collections.unmodifiableMap(overrides);
    }

    private StationNameMapping() {
    }

    /**
     * Returns the corrected display name for the given station id, or null if the
     * station has no override. Station ids are matched exactly as stored in stations6.txt.
     */
    public static String getDisplayName(String stationId) {
        if (stationId == null) {
            return null;
        }
        return OVERRIDES.get(stationId.trim());
    }

    /**
     * Returns true when the given station id has a display-name override.
     */
    public static boolean hasOverride(String stationId) {
        return getDisplayName(stationId) != null;
    }

    /**
     * Number of known overrides (for consistency checks).
     */
    public static int size() {
        return OVERRIDES.size();
    }
}

/**
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2026 Pawel Dube
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Pure JUnit coverage for the station-name override (issue #306). No Android classes,
 * no Robolectric and no device are needed.
 */
public class StationNameMappingTest {

    private static class Station {
        final String id;
        final String description;
        final String coordinates;

        Station(String id, String description, String coordinates) {
            this.id = id;
            this.description = description;
            this.coordinates = coordinates;
        }
    }

    @Test
    public void reportedStationGetsItsDwdName() {
        assertEquals(StationNameMapping.NAME_WIESBADEN_AURINGEN,
                StationNameMapping.getDisplayName("L821"));
        assertTrue(StationNameMapping.hasOverride("L821"));
    }

    @Test
    public void nearbyStationsAreNotOverridden() {
        assertNull(StationNameMapping.getDisplayName("10633"));   // WIESBADEN
        assertNull(StationNameMapping.getDisplayName("L819"));    // WIESBADEN-BIEBRICH
        assertNull(StationNameMapping.getDisplayName("P0489"));   // unrelated station
        assertFalse(StationNameMapping.hasOverride("10633"));
    }

    @Test
    public void mappingIsKeyedByStationIdAndIsDeterministic() {
        assertNull(StationNameMapping.getDisplayName(null));
        assertNull(StationNameMapping.getDisplayName(""));
        // The misleading warning-cell name is not a station id and must not be mapped.
        assertNull(StationNameMapping.getDisplayName("Wiesbaden-Süd"));
        assertEquals(1, StationNameMapping.size());
        for (int i = 0; i < 3; i++) {
            assertEquals("WIESBADEN-AURINGEN", StationNameMapping.getDisplayName("L821"));
        }
    }

    @Test
    public void stationListDataStillContainsOriginalDwdMapping() throws IOException {
        List<Station> stations = readStationList();
        List<Station> auringen = stationsWithId(stations, "L821");
        assertEquals("station list data must not be edited to work around #306", 1, auringen.size());
        assertEquals("WIESBADEN-AURINGEN", auringen.get(0).description);
        assertEquals("8.32,50.13,263.0", auringen.get(0).coordinates);

        List<Station> wiesbaden = stationsWithId(stations, "10633");
        assertEquals(1, wiesbaden.size());
        assertEquals("WIESBADEN", wiesbaden.get(0).description);
        List<Station> biebrich = stationsWithId(stations, "L819");
        assertEquals(1, biebrich.size());
        assertEquals("WIESBADEN-BIEBRICH", biebrich.get(0).description);

        // Every override must refer to an existing station, and no other station may
        // silently acquire a different display name.
        int overridden = 0;
        for (Station station : stations) {
            if (StationNameMapping.hasOverride(station.id)) {
                overridden++;
                assertEquals("L821", station.id);
            }
        }
        assertEquals(1, overridden);
    }

    private static List<Station> stationsWithId(List<Station> stations, String id) {
        List<Station> result = new ArrayList<Station>();
        for (Station station : stations) {
            if (id.equals(station.id)) {
                result.add(station);
            }
        }
        return result;
    }

    /** Parses stations6.txt with the same separators as StationsManager.readStations. */
    private static List<Station> readStationList() throws IOException {
        File file = null;
        String[] candidates = {
                "src/main/res/raw/stations6.txt",     // Gradle test working dir: app/
                "app/src/main/res/raw/stations6.txt"  // repository root
        };
        for (String candidate : candidates) {
            File current = new File(candidate);
            if (current.isFile()) {
                file = current;
                break;
            }
        }
        assertNotNull("stations6.txt not found; cannot verify station import", file);
        List<Station> stations = new ArrayList<Station>();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                for (String item : line.split("\\|")) {
                    String[] values = item.split(";");
                    if (values.length > 3) {
                        stations.add(new Station(values[2], values[1], values[3]));
                    }
                }
            }
        } finally {
            reader.close();
        }
        if (stations.isEmpty()) {
            fail("stations6.txt could not be parsed");
        }
        return stations;
    }
}

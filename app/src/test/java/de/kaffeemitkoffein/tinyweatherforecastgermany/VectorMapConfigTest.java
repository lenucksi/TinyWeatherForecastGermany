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

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TASK-52: pins the OpenFreeMap vector basemap wiring without starting the native map
 * library. Constants are checked directly; the layout/preference/activity plumbing is
 * checked from source so both density/orientation variants are covered.
 */
public class VectorMapConfigTest {

    @Test
    public void styleUrlIsOpenFreeMapPositronOverHttps() {
        assertEquals("https://tiles.openfreemap.org/styles/positron",
                VectorMapConfig.OPENFREEMAP_STYLE_URL);
        assertTrue(VectorMapConfig.OPENFREEMAP_STYLE_URL.startsWith("https://"));
    }

    @Test
    public void preferenceKeyAndDefaultAreStable() {
        assertEquals("PREF_map_vector", VectorMapConfig.PREF_USE_VECTOR_MAP);
        assertTrue("the new vector map is the default; static map is the fallback",
                VectorMapConfig.PREF_USE_VECTOR_MAP_DEFAULT);
    }

    @Test
    public void bothMapLayoutsDeclareMapViewContainerAndAttribution() throws Exception {
        for (String layout : new String[]{
                "app/src/main/res/layout/activity_weatherwarning.xml",
                "app/src/main/res/layout-w600dp-land/activity_weatherwarning.xml"}) {
            String source = read(layout);
            assertTrue(layout + " must declare the MapLibre MapView",
                    source.contains("org.maplibre.android.maps.MapView"));
            assertTrue(layout + " must declare the vector map container",
                    source.contains("@+id/warningactivity_maplibre_container"));
            assertTrue(layout + " must declare the vector attribution view",
                    source.contains("@+id/warningactivity_maplibre_attribution"));
            // Legacy static map must remain present as a fallback.
            assertTrue(layout + " must keep the static map fallback",
                    source.contains("@+id/warningactivity_map"));
            assertTrue(layout + " must keep the legacy seek bar",
                    source.contains("@+id/warningactivity_rainsliderSeekbar"));
        }
    }

    @Test
    public void preferencesDeclareVectorMapSwitch() throws Exception {
        String prefs = read("app/src/main/res/xml/preferences.xml");
        assertTrue(prefs.contains("android:key=\"" + VectorMapConfig.PREF_USE_VECTOR_MAP + "\""));
        assertTrue("preference default and code default must agree",
                prefs.contains("android:key=\"PREF_map_vector\"") &&
                        prefs.contains("android:defaultValue=\"true\""));
    }

    @Test
    public void attributionNamesAllThreeDataProviders() throws Exception {
        String source = read("app/src/main/res/values/strings.xml");
        assertTrue(source.contains("OpenFreeMap"));
        assertTrue(source.contains("OpenMapTiles"));
        assertTrue(source.contains("OpenStreetMap"));
    }

    @Test
    public void activityForwardsEveryMapViewLifecycleCall() throws Exception {
        String source = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        String[] calls = {
                "mapLibreMapView.onCreate",
                "mapLibreMapView.onStart",
                "mapLibreMapView.onResume",
                "mapLibreMapView.onPause",
                "mapLibreMapView.onStop",
                "mapLibreMapView.onDestroy",
                "mapLibreMapView.onSaveInstanceState",
                "mapLibreMapView.getMapAsync"
        };
        for (String call : calls) {
            assertTrue("missing lifecycle forwarding: " + call, source.contains(call));
        }
        assertTrue("MapView inflation requires MapLibre singleton initialisation",
                source.contains("MapLibre.getInstance"));
        assertTrue("vector style must be applied on map ready",
                source.contains("map.setStyle(VectorMapConfig.OPENFREEMAP_STYLE_URL)"));
        assertTrue("legacy map drawing must be skipped in vector mode",
                source.contains("if (useVectorMap)"));
        assertFalse("no cleartext tile/style URLs allowed",
                source.contains("http://tiles.openfreemap.org"));
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) {
            // Tests are also run from the primary checkout against a worktree source tree.
            file = new File(path.substring("app/".length()));
        }
        assertTrue("missing source file: " + path, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

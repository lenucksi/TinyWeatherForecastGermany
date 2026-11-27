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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * TASK-38: static guards for dead-code removal. Pure JUnit, no Android classes are loaded.
 */
public class DeadCodeRemovalTest {

    private static final String PKG = "src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/";

    private static File sourceFile(String name) {
        for (String candidate : new String[]{PKG + name, "app/" + PKG + name}) {
            File file = new File(candidate);
            if (file.isFile()) {
                return file;
            }
        }
        fail("source file not found: " + name);
        return null;
    }

    private static String source(String name) throws IOException {
        return new String(Files.readAllBytes(sourceFile(name).toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void deprecatedRadarImplementationsAreRemoved() throws IOException {
        for (String name : new String[]{"RadarMN.java", "RawRadarmap.java"}) {
            for (String candidate : new String[]{PKG + name, "app/" + PKG + name}) {
                assertFalse("dead file still present: " + name, new File(candidate).exists());
            }
        }
        assertFalse(source("APIReaders.java").contains("multiFetchRadarSetOld"));
        assertFalse(source("WeatherSettings.java").contains("RadarMN."));
        assertFalse(source("Radarmap.java").contains("RawRadarmap"));
        // The live radar implementation and its color table must still be there.
        assertTrue(source("APIReaders.java").contains("class RadarMNSetGeoserverRunnable"));
        assertTrue(source("Radarmap.java").contains("RAINCOLORS"));
    }

    @Test
    public void widgetRefreshActionUsesAppNamespace() throws IOException {
        String widget = source("ClassicWidget.java");
        assertTrue(widget.contains("de.kaffeemitkoffein.tinyweatherforecastgermany.WIDGET_CUSTOM_ACTION_REFRESH"));
        assertFalse("foreign namespace still present", widget.contains("feinstaubwidget"));
        assertTrue(widget.contains("action.equals(WIDGET_CUSTOM_REFRESH_ACTION)"));

        // WidgetRefresher sends the shared constant to every widget class (inherited by
        // BoldWidget, ClockWidget and ChartWidget), so the rename reaches all widget paths.
        String refresher = source("WidgetRefresher.java");
        assertTrue(refresher.contains("ClassicWidget.WIDGET_CUSTOM_REFRESH_ACTION"));
        assertTrue(refresher.contains("BoldWidget.WIDGET_CUSTOM_REFRESH_ACTION"));
        assertTrue(refresher.contains("ClockWidget.WIDGET_CUSTOM_REFRESH_ACTION"));
    }

    @Test
    public void commentedOutCodeIsRemoved() throws IOException {
        for (String name : new String[]{"APIReaders.java", "ClassicWidget.java"}) {
            String text = source(name);
            for (String dead : new String[]{
                    "//return \"https:",
                    "// HttpsURLConnection",
                    "//private static final String WN_RADAR_URL",
                    "//WN_RADAR_URL=",
                    "// String urlString =",
                    "//String weekday ="
            }) {
                assertFalse(name + " still contains commented-out code: " + dead, text.contains(dead));
            }
        }
    }

    @Test
    public void parserAndHttpClientSeamsArePreserved() throws IOException {
        String apiReaders = source("APIReaders.java");
        assertTrue(apiReaders.contains("SecureXml.parse"));
        assertTrue(apiReaders.contains("Http.get("));
        assertTrue(apiReaders.contains("HttpsUrl.build("));
        assertTrue(apiReaders.contains("disallow-doctype-decl"));
    }
}

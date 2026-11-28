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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
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
 * TASK-64: pins the map legend to the colours actually used by the warning and
 * radar layers, and checks the action-bar/menu/string plumbing from source so
 * no Android runtime is needed.
 */
public class MapLegendTest {

    @Test
    public void warningLegendUsesTheDwdSeverityColours() {
        int[] expected = new int[]{
                0xffffeb3b, // minor / yellow
                0xfffb8c00, // moderate / orange
                0xffe53935, // severe / red
                0xff880e4f, // extreme / dark red
                0xffcc99ff, // heat / violet
                0xfffe68fe  // UV / magenta
        };
        assertEquals("legend must show every DWD level in severity order",
                expected.length, WeatherWarning.Severity.colors.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("level " + i, expected[i], WeatherWarning.Severity.colors[i]);
        }
    }

    @Test
    public void radarLegendUsesEveryPaintedRainColor() {
        int[] ladder = new int[Radarmap.RAINCOLORS.length - 1];
        System.arraycopy(Radarmap.RAINCOLORS, 1, ladder, 0, ladder.length);
        assertEquals(16, ladder.length);
        int[] namedSteps = new int[]{
                0xff99ffff, // cyan
                0xff4848ff, // blue
                0xff009934, // green
                0xffffff00, // yellow
                0xffffc400, // orange
                0xffff0000, // red
                0xff990099, // violet
                0xffff33ff  // magenta
        };
        for (int color : namedSteps) {
            boolean found = false;
            for (int step : ladder) {
                if (step == color) {
                    found = true;
                    break;
                }
            }
            assertTrue("radar colour missing from legend ladder: " + Integer.toHexString(color), found);
        }
        assertEquals("magenta is the last radar step", 0xffff33ff, ladder[ladder.length - 1]);
    }

    @Test
    public void activityDerivesLegendFromLayerValues() throws Exception {
        String source = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        for (String token : new String[]{
                "MAP_LEGEND_WARNING_COLORS=WeatherWarning.Severity.colors",
                "MAP_LEGEND_RADAR_COLORS=buildMapLegendRadarColors",
                "System.arraycopy(source,1,result,0,result.length)",
                "Math.round(opacity*255f)",
                "WARNING_FILL_OPACITY",
                "EXCLUDED_FILL_OPACITY",
                "EXCLUDED_FILL_COLOR",
                "EXCLUDED_LINE_COLOR",
                "R.string.radar_rain1",
                "R.string.radar_rain2",
                "R.string.radar_rain3",
                "R.string.radar_rain4",
                "R.mipmap.pin",
                "LegendWindDrawable",
                "showMapLegend()",
                "new AlertDialog.Builder(this)",
                "new ScrollView(this)",
                "setContentDescription(label)",
                "setMinHeight(legendDp(48))"
        }) {
            assertTrue("missing legend wiring: " + token, source.contains(token));
        }
        assertTrue("legend must not reuse the main-screen overview info icon",
                !source.contains("OverviewChartLegend") && !source.contains("R.id.menu_about"));
    }

    @Test
    public void actionBarHasItsOwnLegendItem() throws Exception {
        String menu = read("app/src/main/res/menu/weatherwarning_activity.xml");
        assertTrue(menu.contains("@+id/map_legend"));
        assertTrue(menu.contains("@string/map_legend_title"));
        assertTrue("legend must be reachable without opening the overflow menu",
                menu.contains("showAsAction=\"always\""));
        assertFalse("do not reuse the main-screen overview-chart info icon",
                menu.contains("ic_info_outline"));
    }

    @Test
    public void stringsExistInEnglishAndGerman() throws Exception {
        String english = read("app/src/main/res/values/strings.xml");
        String german = read("app/src/main/res/values-de/strings.xml");
        String[] names = new String[]{
                "map_legend_title", "map_legend_warning_levels", "map_legend_warning_fill",
                "map_legend_minor", "map_legend_moderate", "map_legend_severe",
                "map_legend_extreme", "map_legend_heat", "map_legend_uv",
                "map_legend_radar", "map_legend_radar_scale", "map_legend_excluded",
                "map_legend_others", "map_legend_pin", "map_legend_wind"
        };
        for (String name : names) {
            assertTrue("missing English string: " + name,
                    english.contains("<string name=\"" + name + "\""));
            assertTrue("missing German string: " + name,
                    german.contains("<string name=\"" + name + "\""));
        }
        assertTrue("German warning levels must be translated",
                german.contains("DWD-Warnstufen") && german.contains("dunkelrot"));
        assertTrue("German radar scale must be translated",
                german.contains("Cyan, Blau, Grün, Gelb, Orange, Rot, Violett, Magenta"));
        assertTrue("qualitative radar labels are reused from the legacy legend",
                german.contains("radar_rain1\">gering") && german.contains("radar_rain4\">extrem"));
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

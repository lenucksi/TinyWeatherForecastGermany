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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * TASK-61 static guards for the two main-screen regressions:
 * (A) the overview chart must be redrawn on every weather display pass, and
 * (B) the legend info icon must sit beside the reset X, never over chart data.
 */
public class OverviewChartRedrawTest {

    private static final String ACTIVITY =
            "src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/MainActivity.java";

    private static String read(String relativePath) throws IOException {
        File file = new File(relativePath);
        if (!file.isFile()) {
            file = new File("app", relativePath);
        }
        if (!file.isFile()) {
            fail("source file not found: " + relativePath);
        }
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("method not found: " + signature, start >= 0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        fail("unterminated method: " + signature);
        return null;
    }

    @Test
    public void chartIsRedrawnOnEveryDisplayPass() throws IOException {
        String source = read(ACTIVITY);
        String displayWeatherForecast = methodBody(source, "public void displayWeatherForecast()");
        assertTrue("chart redraw must be scheduled from displayWeatherForecast()",
                displayWeatherForecast.contains("scheduleOverviewChartRedraw(weatherCard)"));
        String displayAdapter = methodBody(source, "private void displayAdapter(final CurrentWeatherInfo weatherCard)");
        assertFalse("chart must not be drawn only from displayAdapter()",
                displayAdapter.contains("displayOverviewChart("));
        String loadCurrentWeather = methodBody(source, "private void loadCurrentWeather()");
        assertTrue("view-setting changes still recreate the activity",
                loadCurrentWeather.contains("recreate()"));
        String onResume = methodBody(source, "protected void onResume()");
        assertTrue("plain onResume must reach the display pass",
                onResume.contains("loadCurrentWeather()"));
    }

    @Test
    public void chartFailureAndSettingAreLogged() throws IOException {
        String source = read(ACTIVITY);
        String chart = methodBody(source, "private void displayOverviewChart(final CurrentWeatherInfo currentWeatherInfo)");
        assertTrue("log the display setting value", chart.contains("Overview chart display setting="));
        assertTrue("log why a bitmap could not be produced", chart.contains("setLiveChartBitmap skipped"));
        assertTrue("log the forecast size", chart.contains("forecast1hourly="));
        assertTrue("log the view size", chart.contains("getWidth()"));
    }

    @Test
    public void stationChangeReplacesLiveBitmapOrLogsSkip() throws IOException {
        String source = read(ACTIVITY);
        String chart = methodBody(source, "private void displayOverviewChart(final CurrentWeatherInfo currentWeatherInfo)");
        assertTrue("live legend bitmap must be replaced after a station change",
                chart.contains("OverviewChartLegend.setLiveChartBitmap(overViewChartBitmap)"));
        assertTrue("log each successful setLiveChartBitmap call",
                chart.contains("setLiveChartBitmap: updated"));
        assertTrue("log why the live bitmap was not replaced",
                chart.contains("setLiveChartBitmap skipped"));
        assertTrue("never leave the previous station's bitmap on screen",
                chart.contains("overviewChartImageView.setImageBitmap(null)"));
        String fallback = methodBody(source,
                "private Bitmap getOverviewChartBitmap(int width, int height, CurrentWeatherInfo card)");
        assertTrue("retry chart generation without empty trailing forecast rows",
                fallback.contains("empty trailing hours"));
        assertTrue("retry uses ForecastBitmap.getOverviewChart",
                fallback.contains("ForecastBitmap.getOverviewChart"));
    }

    @Test
    public void legendIconSitsBesideResetXInBothOrientations() throws IOException {
        for (String relative : new String[]{
                "src/main/res/layout/activity_main.xml",
                "src/main/res/layout-w600dp-land/activity_main.xml"}) {
            String layout = read(relative);
            int rowStart = layout.indexOf("android:id=\"@+id/main_spinner_linearlayout\"");
            assertTrue(relative + ": spinner row missing", rowStart >= 0);
            int rowEnd = layout.indexOf("</LinearLayout>", rowStart);
            assertTrue(relative + ": spinner row not closed", rowEnd > rowStart);
            String row = layout.substring(rowStart, rowEnd);
            assertTrue(relative + ": legend icon must be inside the X control row",
                    row.contains("@+id/main_overviewchart_legend"));
            assertTrue(relative + ": 48dp touch target", row.contains("android:minWidth=\"48dp\"")
                    && row.contains("android:minHeight=\"48dp\""));
            assertTrue(relative + ": small glyph via 16dp internal padding",
                    row.contains("android:padding=\"16dp\""));
            assertTrue(relative + ": icon must be tinted for every theme",
                    row.contains("android:tint=\"?attr/colorText\""));
            assertFalse(relative + ": icon must not overlay the chart",
                    layout.contains("android:layout_alignTop=\"@id/main_overview_chart\""));
        }
    }
}

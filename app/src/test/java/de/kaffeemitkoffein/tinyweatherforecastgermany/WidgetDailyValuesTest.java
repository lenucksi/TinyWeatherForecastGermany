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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.*;

/**
 * TASK-56: the large ClassicWidget must render the per-day min/max values and
 * use the whole daily bar. The providers need a launcher / AppWidgetManager,
 * so the render contract is verified against the provider sources and the
 * layouts, in the same JVM-only style as WidgetNoDataTest.
 */
public class WidgetDailyValuesTest {

    private static final String SRC =
            "src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/";

    @Test
    public void largeLayoutAlwaysRequestsTemperatures() throws Exception {
        String source = read(SRC + "ClassicWidget.java");
        int start = source.indexOf("public void updateWidgetDisplay(");
        int end = source.indexOf("public void widgetRefreshAction(", start);
        assertTrue("missing updateWidgetDisplay", start >= 0 && end > start);
        String method = source.substring(start, end);

        assertTrue("layout decision must be made in dp",
                method.contains("WidgetSizing.useLargeWidgetLayout(heightDp)"));
        assertTrue("the old lines==2 / >240dp branch must be gone",
                !method.contains("lines==2") && !method.contains("lines==3"));
        assertTrue("the daily bar must follow the shared temperature policy",
                method.contains("WidgetSizing.showDailyTemperatures(heightDp)"));
        assertFalse("no large-widget path may suppress the daily temperatures",
                method.contains("get10DaysForecastBar(c,widgetWidth,widgetHeight,weatherCard,false"));
        assertTrue("the bitmap must still be attached to the large layout",
                method.contains("setImageViewBitmap(R.id.largewidget_10daysbitmap"));
    }

    @Test
    public void dailyBarDrawsBothTemperatureValues() throws Exception {
        String source = read(SRC + "ClassicWidget.java");
        int start = source.indexOf("private Bitmap getDailyBar(");
        int end = source.indexOf("private Bitmap get10DaysForecastBar(", start);
        assertTrue("missing getDailyBar", start >= 0 && end > start);
        String method = source.substring(start, end);

        assertTrue("max temperature must be drawn",
                method.contains("hasMaxTemperature()") &&
                        method.contains("getMaxTemperatureInCelsiusInt()"));
        assertTrue("min temperature must be drawn",
                method.contains("hasMinTemperature()") &&
                        method.contains("getMinTemperatureInCelsiusInt()"));
        assertTrue("temperatures must use the active theme text colour",
                method.contains("ThemePicker.getWidgetTextColor(context)"));
    }

    @Test
    public void runtimeBarAndStaticPreviewAgreeOnDailyRows() throws Exception {
        String runtimeLayout = read("src/main/res/layout/largewidget_layout.xml");
        String previewLayout = read("src/main/res/layout/preview_classic_widget.xml");

        assertTrue("large layout must host the generated daily bar",
                runtimeLayout.contains("android:id=\"@+id/largewidget_10daysbitmap\""));
        assertTrue("preview must show a weekday row",
                previewLayout.contains("android:id=\"@+id/preview_classic_fake_day1\""));
        assertTrue("preview must show a max temperature row",
                previewLayout.contains("android:id=\"@+id/preview_classic_fake_max1\""));
        assertTrue("preview must show a min temperature row",
                previewLayout.contains("android:id=\"@+id/preview_classic_fake_min1\""));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}

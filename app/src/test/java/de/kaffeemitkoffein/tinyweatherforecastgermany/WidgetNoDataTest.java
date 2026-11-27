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
 * TASK-48: the three widget providers must publish a complete RemoteViews when
 * the weather database is empty. The providers themselves need a launcher and
 * an AppWidgetManager, so the contract is verified against their sources (the
 * same JVM-only style used by WidgetProviderSizingTest) together with the
 * dedicated no-data layout.
 */
public class WidgetNoDataTest {

    private static final String[] PROVIDERS =
            {"ClassicWidget.java", "BoldWidget.java", "ChartWidget.java"};

    private static final String SRC =
            "src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/";

    @Test
    public void everyProviderRendersAndPushesViewsWithoutWeatherData() throws Exception {
        for (String provider : PROVIDERS) {
            String source = read(SRC + provider);
            int ifIndex = source.indexOf("if (weatherCard!=null){");
            assertTrue(provider + " lost its weatherCard branch", ifIndex >= 0);
            int elseIndex = source.indexOf("} else {", ifIndex);
            assertTrue(provider + " has no no-data branch", elseIndex >= 0);
            int syncIndex = source.indexOf("requestSync(", elseIndex);
            assertTrue(provider + " stopped requesting sync in the no-data branch", syncIndex >= 0);
            String noDataBranch = source.substring(elseIndex, syncIndex);

            assertTrue(provider + " must call updateAppWidget with no data",
                    noDataBranch.contains("awm.updateAppWidget("));
            assertTrue(provider + " must use the themed no-data views",
                    noDataBranch.contains("getNoDataWidgetViews(c)"));
            assertTrue(provider + " must cover every widget instance",
                    noDataBranch.contains("widget_instances.length"));
            assertTrue(provider + " must keep the with-data update path",
                    source.substring(ifIndex, elseIndex).contains("awm.updateAppWidget("));
        }
    }

    @Test
    public void sharedNoDataViewsApplyBackgroundHintAndClickAction() throws Exception {
        String classic = read(SRC + "ClassicWidget.java");
        int helper = classic.indexOf("getNoDataWidgetViews(Context c)");
        assertTrue("missing shared no-data view builder", helper >= 0);
        String body = classic.substring(helper, classic.indexOf("updateWidgetDisplay(", helper));
        assertTrue("no-data widget must load the dedicated layout",
                body.contains("R.layout.widget_nodata_layout"));
        assertTrue("no-data widget must set the themed background",
                body.contains("setImageViewResource(android.R.id.background"));
        assertTrue("no-data widget must apply the configured opacity",
                body.contains("setImageAlpha"));
        assertTrue("no-data widget must show an explicit hint",
                body.contains("setTextViewText(R.id.widget_nodata_text"));
        assertTrue("no-data widget hint must come from a string resource",
                body.contains("R.string.error_no_data"));
        assertTrue("no-data widget must keep the tap-to-open action",
                body.contains("setOnClickPendingIntent(R.id.widget_nodata_maincontainer"));
    }

    @Test
    public void noDataLayoutContainsNoRawPlaceholders() throws Exception {
        String layout = read("src/main/res/layout/widget_nodata_layout.xml");
        assertTrue("no-data layout must carry the background view",
                layout.contains("android:id=\"@android:id/background\""));
        assertTrue("no-data layout must carry the hint view",
                layout.contains("android:id=\"@+id/widget_nodata_text\""));
        assertTrue("no-data layout must default to a localized hint",
                layout.contains("android:text=\"@string/error_no_data\""));
        assertFalse("raw temperature placeholder leaked into no-data layout",
                layout.contains("-°"));
        assertFalse("raw question placeholder leaked into no-data layout",
                layout.contains("?\""));
        assertFalse("raw demo location leaked into no-data layout",
                layout.contains("HAMBURG INNENSTADT"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}

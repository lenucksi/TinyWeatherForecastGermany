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
 * TASK-63: widgets could stay in the no-data state after a station change.
 * The fix has three corners - a closed cursor, a throttled sync request that
 * also fires when the refresh came from the sync adapter, and a station-change
 * path that fetches data for a station with no cached dataset. The throttle
 * decision is exercised directly; the Android-bound call sites are verified
 * against their sources (the JVM-only style used by WidgetNoDataTest).
 */
public class WidgetNoDataSelfHealTest {

    private static final String SRC =
            "src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/";

    @Test
    public void throttleGrantsFirstRequestOnlyAndNeverLoops() {
        long window = WidgetRefresher.WIDGET_NODATA_SYNC_THROTTLE_MILLIS;
        long start = 1_000_000L;
        assertTrue("first request must be granted",
                WidgetRefresher.shouldRequestNoDataSync(start, 0L, window));

        // Record the grant, then hammer the same path: nothing else may pass.
        long grantedAt = start;
        for (int i = 1; i < window / 1000L; i++) {
            long now = grantedAt + i * 1000L;
            assertFalse("request inside throttle window must be blocked at +" + i + "s",
                    WidgetRefresher.shouldRequestNoDataSync(now, grantedAt, window));
        }
        assertTrue("request exactly at the window boundary must be granted",
                WidgetRefresher.shouldRequestNoDataSync(grantedAt + window, grantedAt, window));
        assertFalse("a clock that jumped backwards must not grant a request",
                WidgetRefresher.shouldRequestNoDataSync(grantedAt - 1L, grantedAt, window));
    }

    @Test
    public void getCurrentWeatherInfoClosesCursorOnEveryPath() throws Exception {
        String weather = read(SRC + "Weather.java");
        int method = weather.indexOf("public static CurrentWeatherInfo getCurrentWeatherInfo(Context context)");
        assertTrue("getCurrentWeatherInfo is missing", method >= 0);
        int end = weather.indexOf("public static boolean hasUVHIData", method);
        assertTrue("method boundary is missing", end > method);
        String body = weather.substring(method, end);

        assertTrue("cursor must be obtained", body.contains("contentResolver.query("));
        assertTrue("load the weather card from the open cursor",
                body.contains("WeatherContentManager.getWeatherInfo(context,cursor)"));
        assertTrue("cursor must be closed", body.contains("cursor.close();"));
        assertTrue("null cursor from the query must be handled", body.contains("if (cursor!=null)"));
        assertFalse("the with-data path must not return before close()",
                body.indexOf("return currentWeatherInfo;") < body.indexOf("cursor.close();"));
        assertTrue("no-data cause must be logged", body.contains("No cached weather data for station"));
    }

    @Test
    public void noDataProvidersUseThrottledGuardWithoutSyncAdapterSuppression() throws Exception {
        for (String provider : new String[]{"ClassicWidget.java", "BoldWidget.java", "ChartWidget.java"}) {
            String source = read(SRC + provider);
            assertTrue(provider + " must use the shared throttle guard",
                    source.contains("WidgetRefresher.requestNoDataSyncAllowed(c,source)"));
            assertFalse(provider + " must not suppress sync for the sync adapter",
                    source.contains("source!=WidgetRefresher.FROM_SYNCADAPTER"));
            assertTrue(provider + " must still issue the sync request",
                    source.contains("ContentResolver.requestSync("));
            assertTrue(provider + " must keep the fully rendered no-data view",
                    source.contains("getNoDataWidgetViews(c)"));
        }
    }

    @Test
    public void refresherPersistsTimestampAndLogsTheReason() throws Exception {
        String refresher = read(SRC + "WidgetRefresher.java");
        assertTrue("throttled helper must exist",
                refresher.contains("public static boolean requestNoDataSyncAllowed(Context context, int source)"));
        assertTrue("timestamp must be read from DataStorage",
                refresher.contains("DataStorage.getLong(context,DATASTORAGE_WIDGET_NODATA_LAST_SYNC,0L)"));
        assertTrue("timestamp must be stored before the caller requests a sync",
                refresher.contains("DataStorage.setLong(context,DATASTORAGE_WIDGET_NODATA_LAST_SYNC,now)"));
        assertTrue("granted decision must be auditable", refresher.contains("requestSync granted"));
        assertTrue("throttled decision must be auditable", refresher.contains("requestSync throttled"));
        assertTrue("log must state the reason",
                refresher.contains("no cached dataset for the selected station"));
        assertTrue("focused refresh must exclude the clock provider",
                refresher.contains("public static void refreshNoDataAwareWidgets(Context context, int source)"));
    }

    @Test
    public void stationChangeWithoutDueSyncFetchesDataAndRefreshesWidgets() throws Exception {
        String main = read(SRC + "MainActivity.java");
        int method = main.indexOf("private void newWeatherRegionSelected(final Weather.WeatherLocation weatherLocation)");
        assertTrue("newWeatherRegionSelected is missing", method >= 0);
        int end = main.indexOf("public static class SpinnerListener", method);
        assertTrue("method boundary is missing", end > method);
        String body = main.substring(method, end);

        assertTrue("must distinguish a station with no cached data", body.contains("if (weatherCard==null)"));
        assertTrue("must use the shared throttle guard",
                body.contains("WidgetRefresher.requestNoDataSyncAllowed(context,WidgetRefresher.FROM_MAIN_APP)"));
        assertTrue("must actually request the weather data", body.contains("ContentResolver.requestSync("));
        assertTrue("must refresh the widgets that render no-data",
                body.contains("WidgetRefresher.refreshNoDataAwareWidgets(context,WidgetRefresher.FROM_MAIN_APP)"));
        assertTrue("must log why the sync is requested",
                body.contains("reason: station change, sync not due"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}

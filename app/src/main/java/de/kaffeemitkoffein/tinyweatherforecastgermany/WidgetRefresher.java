/**
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2020, 2021, 2022, 2023, 2024 Pawel Dube
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

import android.content.Context;
import android.content.Intent;

public class WidgetRefresher {

    public final static String EXTRA_SOURCE = "extra_source";
    public final static int FROM_SYSTEM = 0;
    public final static int FROM_MAIN_APP = 1;
    public final static int FROM_SYNCADAPTER = 2;
    public final static int FROM_SETTINGS = 3;
    public final static int FROM_ALARMCLOCK = 4;

    // TASK-63: distinct DataStorage id (does not collide with the update-time
    // slots 102/105/108/111/114 or the preference ids 0..23).
    public final static int DATASTORAGE_WIDGET_NODATA_LAST_SYNC = 120;
    public final static long WIDGET_NODATA_SYNC_THROTTLE_MILLIS = 60_000L;

    /**
     * TASK-63: a widget rendered without cached weather data used to request a
     * sync on every refresh. When the refresh came FROM_SYNCADAPTER that request
     * was suppressed, so the no-data state could survive a station change.
     * A sync is now always allowed, but at most once per throttle window: the
     * timestamp is recorded here before the caller issues requestSync, which
     * makes a sync -> widget-no-data -> sync loop impossible.
     *
     * @return true when the caller should issue the weather sync request.
     */
    public static boolean requestNoDataSyncAllowed(Context context, int source){
        long now = System.currentTimeMillis();
        long lastRequest = DataStorage.getLong(context,DATASTORAGE_WIDGET_NODATA_LAST_SYNC,0L);
        if (shouldRequestNoDataSync(now,lastRequest,WIDGET_NODATA_SYNC_THROTTLE_MILLIS)){
            DataStorage.setLong(context,DATASTORAGE_WIDGET_NODATA_LAST_SYNC,now);
            PrivateLog.log(context,PrivateLog.WIDGET,PrivateLog.INFO,
                    "Widget no-data: requestSync granted (source="+source+
                    ", previous request="+lastRequest+
                    "); reason: no cached dataset for the selected station");
            return true;
        }
        PrivateLog.log(context,PrivateLog.WIDGET,PrivateLog.INFO,
                "Widget no-data: requestSync throttled (source="+source+
                ", elapsed="+(now-lastRequest)+"ms"+
                "); reason: no cached dataset for the selected station");
        return false;
    }

    /** Pure decision helper so the throttle can be verified without Android. */
    static boolean shouldRequestNoDataSync(long now, long lastRequest, long throttleMillis){
        // No request yet, or the throttle window elapsed. A clock that jumped
        // backwards gives a negative delta and is treated as throttled.
        return lastRequest<=0 || (now-lastRequest)>=throttleMillis;
    }

    public static void refresh(Context context, int source){
        refreshBoldWidget(context,source);
        refreshClassicWidget(context,source);
        refreshClockWidget(context,source);
        refreshChartWidget(context,source);
    }

    /**
     * TASK-63: refresh only the providers that publish the dedicated no-data
     * view. Used right after a station change with no cached dataset: the clock
     * provider has no no-data layout and would otherwise fire its own,
     * unthrottled sync request on FROM_MAIN_APP.
     */
    public static void refreshNoDataAwareWidgets(Context context, int source){
        refreshBoldWidget(context,source);
        refreshClassicWidget(context,source);
        refreshChartWidget(context,source);
    }

    public static void refreshBoldWidget(Context context, int source){
        // update bold widget
        Intent intent = new Intent(context,BoldWidget.class);
        intent.setAction(BoldWidget.WIDGET_CUSTOM_REFRESH_ACTION);
        intent.putExtra(EXTRA_SOURCE,source);
        context.sendBroadcast(intent);
    }

    public static void refreshClassicWidget(Context context, int source){
        // update classic widget
        Intent intent = new Intent(context,ClassicWidget.class);
        intent.setAction(ClassicWidget.WIDGET_CUSTOM_REFRESH_ACTION);
        intent.putExtra(EXTRA_SOURCE,source);
        context.sendBroadcast(intent);
    }

    public static void refreshClockWidget(Context context, int source){
        // update clock widget
        Intent intent = new Intent(context,ClockWidget.class);
        intent.setAction(ClockWidget.WIDGET_CUSTOM_REFRESH_ACTION);
        intent.putExtra(EXTRA_SOURCE,source);
        context.sendBroadcast(intent);
    }

    public static void refreshChartWidget(Context context, int source){
        // update chart widget (TASK-26: use the chart provider's own action,
        // not the clock provider's, even though both actions currently share
        // the same string constant).
        Intent intent = new Intent(context,ChartWidget.class);
        intent.setAction(ChartWidget.WIDGET_CUSTOM_REFRESH_ACTION);
        intent.putExtra(EXTRA_SOURCE,source);
        context.sendBroadcast(intent);
    }

}

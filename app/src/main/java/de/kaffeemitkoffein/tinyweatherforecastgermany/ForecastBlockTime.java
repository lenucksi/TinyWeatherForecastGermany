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

import java.util.ArrayList;
import java.util.Calendar;

/**
 * Time logic for the multi-hour forecast blocks (issue #149).
 *
 * The timestamp of a 6h/24h WeatherInfo is the END of the period it describes.
 * The list adapter used to clamp the period start to "now, truncated to the
 * hour" whenever the natural start lay in the past. When the adapter was built
 * from a stale cached card (background refresh race with "Always update"), the
 * block itself could already have ended, so the clamped start ended up AFTER
 * the block end and the heading read e.g. "13:00 - 12:00".
 *
 * These helpers are deliberately free of Android UI dependencies so the time
 * rules can be verified with plain JUnit and a fixed time zone.
 */
public final class ForecastBlockTime {

    private ForecastBlockTime() {
    }

    /** Number of hours covered by one block, derived from its forecast type. */
    static int getBlockIntervalHours(int forecastType) {
        if (forecastType == Weather.WeatherInfo.ForecastType.HOURS_24) {
            return 24;
        }
        if (forecastType == Weather.WeatherInfo.ForecastType.HOURS_6) {
            return 6;
        }
        return 1;
    }

    /**
     * Returns the start timestamp that may be displayed for a forecast block.
     * The result is guaranteed to be strictly before the block's end timestamp.
     *
     * For a future block the previous behaviour is kept: if the natural start
     * is in the past, display "from the current hour". For a block that has
     * already ended (stale cache) that clamp is not applied, because it would
     * invert the range. The block's real interval is used instead.
     */
    static long getBlockStartTime(Weather.WeatherInfo weatherInfo, long nowMillis) {
        final long end = weatherInfo.getTimestamp();
        final int intervalHours = getBlockIntervalHours(weatherInfo.getForecastType());

        // Calendar.add() keeps wall-clock hours correct across DST changes.
        Calendar startCalendar = Calendar.getInstance();
        startCalendar.setTimeInMillis(end);
        startCalendar.add(Calendar.HOUR_OF_DAY, -intervalHours);
        final long naturalStart = startCalendar.getTimeInMillis();

        long start = naturalStart;
        if (start < nowMillis) {
            Calendar nowHour = Calendar.getInstance();
            nowHour.setTimeInMillis(nowMillis);
            nowHour.set(Calendar.MINUTE, 0);
            nowHour.set(Calendar.SECOND, 0);
            nowHour.set(Calendar.MILLISECOND, 0);
            start = nowHour.getTimeInMillis();
        }
        if (start >= end) {
            // Stale/expired block: keep its real interval instead of showing an
            // impossible range such as "13:00 - 12:00" (issue #149).
            start = naturalStart;
        }
        return start;
    }

    /**
     * Removes 6h/24h blocks that have already ended at the given time. Hourly
     * items are kept so the existing list layout is not changed.
     *
     * This makes a stale cached dataset render the same first multi-hour block
     * as a freshly fetched one: the expired block is invisible in both cases.
     * If removing expired blocks would empty the whole list, the original list
     * is returned so that an old cache never produces a blank screen.
     */
    static ArrayList<Weather.WeatherInfo> withoutExpiredLongRangeBlocks(
            ArrayList<Weather.WeatherInfo> forecasts, long nowMillis) {
        if (forecasts == null) {
            return null;
        }
        ArrayList<Weather.WeatherInfo> result = new ArrayList<Weather.WeatherInfo>();
        for (int i = 0; i < forecasts.size(); i++) {
            Weather.WeatherInfo item = forecasts.get(i);
            if (item == null) {
                continue;
            }
            int type = item.getForecastType();
            boolean isLongRange = (type == Weather.WeatherInfo.ForecastType.HOURS_6)
                    || (type == Weather.WeatherInfo.ForecastType.HOURS_24);
            if (isLongRange && item.getTimestamp() <= nowMillis) {
                continue;
            }
            result.add(item);
        }
        if (result.isEmpty() && !forecasts.isEmpty()) {
            return forecasts;
        }
        return result;
    }
}

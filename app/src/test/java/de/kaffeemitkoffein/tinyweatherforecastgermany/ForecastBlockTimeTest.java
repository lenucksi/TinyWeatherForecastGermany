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
import java.util.TimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for issue #149: the first 6-hour block showed an
 * impossible range ("13:00 - 12:00") after a background refresh while
 * "Always update" was enabled.
 *
 * Pure JUnit with a fixed Europe/Berlin time zone (the zone from the report).
 * No Android runtime, emulator or network is needed.
 *
 * Equivalent two-refresh device check (manual, API 37 emulator, not run here):
 * 1. Set the display type to "6 hours", enable "Always update", zone Europe/Berlin.
 * 2. Let a background auto-refresh complete while the app is open. Read the
 *    first block header: start must be strictly before end (never "13:00 - 12:00").
 * 3. Use the menu refresh. The first block header must be identical to step 2.
 */
public class ForecastBlockTimeTest {

    private TimeZone previousTimeZone;

    @Before
    public void fixTimeZone() {
        previousTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
    }

    @After
    public void restoreTimeZone() {
        TimeZone.setDefault(previousTimeZone);
    }

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Europe/Berlin"));
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    private static Weather.WeatherInfo block(int forecastType, long timestamp) {
        Weather.WeatherInfo info = new Weather.WeatherInfo();
        info.setForecastType(forecastType);
        info.setTimestamp(timestamp);
        return info;
    }

    @Test
    public void reportedStaleSixHourBlockNeverStartsAfterItsEnd() {
        long now = at(2022, 11, 24, 13, 20);
        // The reported bad header was "13:00 - 12:00": the block had already
        // ended but the old code clamped its start to the current hour.
        Weather.WeatherInfo stale = block(Weather.WeatherInfo.ForecastType.HOURS_6,
                at(2022, 11, 24, 12, 0));
        long start = ForecastBlockTime.getBlockStartTime(stale, now);

        assertTrue("start must be before end, got " + start + " / " + stale.getTimestamp(),
                start < stale.getTimestamp());
        assertEquals(at(2022, 11, 24, 6, 0), start);
    }

    @Test
    public void futureSixHourBlockKeepsTheCurrentHourClamp() {
        long now = at(2022, 11, 24, 13, 20);
        Weather.WeatherInfo future = block(Weather.WeatherInfo.ForecastType.HOURS_6,
                at(2022, 11, 24, 18, 0));

        assertEquals(at(2022, 11, 24, 13, 0),
                ForecastBlockTime.getBlockStartTime(future, now));
    }

    @Test
    public void staleTwentyFourHourBlockAlsoHasAValidRange() {
        long now = at(2022, 11, 24, 13, 20);
        Weather.WeatherInfo stale = block(Weather.WeatherInfo.ForecastType.HOURS_24,
                at(2022, 11, 24, 0, 0));
        long start = ForecastBlockTime.getBlockStartTime(stale, now);

        assertTrue(start < stale.getTimestamp());
        assertEquals(at(2022, 11, 23, 0, 0), start);
    }

    @Test
    public void hourlyBlockStartIsBeforeItsEnd() {
        long now = at(2022, 11, 24, 13, 20);
        Weather.WeatherInfo nextHour = block(Weather.WeatherInfo.ForecastType.ONE_HOUR,
                at(2022, 11, 24, 14, 0));

        assertEquals(at(2022, 11, 24, 13, 0),
                ForecastBlockTime.getBlockStartTime(nextHour, now));
    }

    @Test
    public void startRemainsBeforeEndAcrossDaylightSavingChange() {
        // Europe/Berlin jumps 02:00 -> 03:00 on 2022-03-27.
        long now = at(2022, 3, 27, 4, 30);
        Weather.WeatherInfo future = block(Weather.WeatherInfo.ForecastType.HOURS_6,
                at(2022, 3, 27, 6, 0));

        assertTrue(ForecastBlockTime.getBlockStartTime(future, now) < future.getTimestamp());
    }

    @Test
    public void expiredLongRangeBlocksAreDroppedButFutureOnesRemain() {
        long now = at(2022, 11, 24, 13, 20);
        ArrayList<Weather.WeatherInfo> staleCard = new ArrayList<Weather.WeatherInfo>();
        staleCard.add(block(Weather.WeatherInfo.ForecastType.HOURS_6, at(2022, 11, 24, 12, 0)));
        staleCard.add(block(Weather.WeatherInfo.ForecastType.HOURS_6, at(2022, 11, 24, 18, 0)));
        staleCard.add(block(Weather.WeatherInfo.ForecastType.HOURS_24, at(2022, 11, 25, 0, 0)));

        ArrayList<Weather.WeatherInfo> result =
                ForecastBlockTime.withoutExpiredLongRangeBlocks(staleCard, now);

        assertEquals(2, result.size());
        assertEquals(at(2022, 11, 24, 18, 0), result.get(0).getTimestamp());
        assertEquals(at(2022, 11, 25, 0, 0), result.get(1).getTimestamp());
    }

    @Test
    public void hourlyBlocksAreKeptEvenWhenTheirTimeHasPassed() {
        long now = at(2022, 11, 24, 13, 20);
        ArrayList<Weather.WeatherInfo> forecasts = new ArrayList<Weather.WeatherInfo>();
        forecasts.add(block(Weather.WeatherInfo.ForecastType.ONE_HOUR, at(2022, 11, 24, 7, 0)));
        forecasts.add(block(Weather.WeatherInfo.ForecastType.HOURS_6, at(2022, 11, 24, 12, 0)));
        forecasts.add(block(Weather.WeatherInfo.ForecastType.ONE_HOUR, at(2022, 11, 24, 14, 0)));

        ArrayList<Weather.WeatherInfo> result =
                ForecastBlockTime.withoutExpiredLongRangeBlocks(forecasts, now);

        assertEquals(2, result.size());
        assertEquals(Weather.WeatherInfo.ForecastType.ONE_HOUR, result.get(0).getForecastType());
        assertEquals(Weather.WeatherInfo.ForecastType.ONE_HOUR, result.get(1).getForecastType());
    }

    @Test
    public void completelyExpiredListIsKeptToAvoidAnEmptyScreen() {
        long now = at(2022, 11, 24, 13, 20);
        ArrayList<Weather.WeatherInfo> forecasts = new ArrayList<Weather.WeatherInfo>();
        forecasts.add(block(Weather.WeatherInfo.ForecastType.HOURS_6, at(2022, 11, 24, 12, 0)));

        ArrayList<Weather.WeatherInfo> result =
                ForecastBlockTime.withoutExpiredLongRangeBlocks(forecasts, now);

        assertEquals(forecasts, result);
    }

    @Test
    public void automaticAndManualRefreshProduceTheSameFirstBlock() {
        long now = at(2022, 11, 24, 13, 20);
        // Automatic refresh: the adapter still holds a cached card that also
        // contains the already-ended 12:00 block.
        ArrayList<Weather.WeatherInfo> cachedCard = new ArrayList<Weather.WeatherInfo>();
        cachedCard.add(block(Weather.WeatherInfo.ForecastType.HOURS_6, at(2022, 11, 24, 12, 0)));
        cachedCard.add(block(Weather.WeatherInfo.ForecastType.HOURS_6, at(2022, 11, 24, 18, 0)));
        cachedCard.add(block(Weather.WeatherInfo.ForecastType.HOURS_6, at(2022, 11, 25, 0, 0)));
        // Manual refresh: freshly fetched card starts with the 18:00 block.
        ArrayList<Weather.WeatherInfo> freshCard = new ArrayList<Weather.WeatherInfo>();
        freshCard.add(block(Weather.WeatherInfo.ForecastType.HOURS_6, at(2022, 11, 24, 18, 0)));
        freshCard.add(block(Weather.WeatherInfo.ForecastType.HOURS_6, at(2022, 11, 25, 0, 0)));

        ArrayList<Weather.WeatherInfo> automatic =
                ForecastBlockTime.withoutExpiredLongRangeBlocks(cachedCard, now);
        ArrayList<Weather.WeatherInfo> manual =
                ForecastBlockTime.withoutExpiredLongRangeBlocks(freshCard, now);

        assertEquals(manual.size(), automatic.size());
        for (int i = 0; i < manual.size(); i++) {
            assertEquals("refresh paths must show identical block ends",
                    manual.get(i).getTimestamp(), automatic.get(i).getTimestamp());
            assertEquals("refresh paths must show identical block starts",
                    ForecastBlockTime.getBlockStartTime(manual.get(i), now),
                    ForecastBlockTime.getBlockStartTime(automatic.get(i), now));
        }
    }
}

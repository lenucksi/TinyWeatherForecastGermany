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
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for issue #187 (DST change breaks the daily detailed view).
 *
 * The daily forecast bar used to select its six hourly cells by fixed index
 * offsets (20 entries back, then every 4th entry). Across a DST change a local
 * day is 23 or 25 hours long, so the cells collapsed into a single repeated
 * "03:00" entry at the right edge of the row.
 *
 * These tests exercise the instant-vs-local-time helpers with the two Europe/
 * Berlin transition directions and assert that zones without DST keep the old
 * (fixed millisecond) behaviour.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33, 36})
public class DstDetailedViewTimeTest {

    private static final String BERLIN = "Europe/Berlin";

    private TimeZone previousTimeZone;

    @Before
    public void useBerlin() {
        previousTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(BERLIN));
    }

    @After
    public void restoreTimeZone() {
        TimeZone.setDefault(previousTimeZone);
    }

    private static long at(String zone, int year, int month, int day, int hour) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(zone));
        calendar.clear();
        calendar.set(year, month - 1, day, hour, 0, 0);
        return calendar.getTimeInMillis();
    }

    /** Hourly DWD-style instants: consecutive local hours via Calendar arithmetic. */
    private static ArrayList<Weather.WeatherInfo> hourlyForecasts(long first, int count) {
        ArrayList<Weather.WeatherInfo> result = new ArrayList<Weather.WeatherInfo>();
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(first);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        for (int i = 0; i < count; i++) {
            Weather.WeatherInfo item = new Weather.WeatherInfo();
            item.setForecastType(Weather.WeatherInfo.ForecastType.ONE_HOUR);
            item.setTimestamp(calendar.getTimeInMillis());
            result.add(item);
            calendar.add(Calendar.HOUR_OF_DAY, 1);
        }
        return result;
    }

    /** The six daily-bar instants selected for a block ending at blockEnd. */
    private static long[] dailyBarInstants(long blockEnd, ArrayList<Weather.WeatherInfo> hourly) {
        long[] result = new long[6];
        for (int j = 0; j < 6; j++) {
            long target = Weather.getLocalHourBefore(blockEnd, 20 - (j * 4));
            int position = Weather.getClosestHourlyPosition(hourly, target);
            assertTrue("missing hourly entry for bar cell " + j, position >= 0);
            result[j] = hourly.get(position).getTimestamp();
        }
        return result;
    }

    private static int[] localHoursOf(String zone, long[] instants) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(zone));
        int[] hours = new int[instants.length];
        for (int i = 0; i < instants.length; i++) {
            calendar.setTimeInMillis(instants[i]);
            hours[i] = calendar.get(Calendar.HOUR_OF_DAY);
        }
        return hours;
    }

    private static void assertNoDuplicates(long[] instants) {
        for (int i = 0; i < instants.length; i++) {
            for (int j = i + 1; j < instants.length; j++) {
                assertTrue("bar cell " + i + " and " + j + " show the same instant",
                        instants[i] != instants[j]);
            }
        }
    }

    @Test
    public void autumnFallbackKeepsSixDistinctCells() {
        // 2023-10-29: 03:00 CEST -> 02:00 CET, the day reported in #187.
        long blockEnd = at(BERLIN, 2023, 10, 29, 23);
        ArrayList<Weather.WeatherInfo> hourly =
                hourlyForecasts(Weather.getLocalHourBefore(blockEnd, 24), 26);

        long[] cells = dailyBarInstants(blockEnd, hourly);
        assertNoDuplicates(cells);
        assertArrayEqualsInt(new int[]{3, 7, 11, 15, 19, 23}, localHoursOf(BERLIN, cells));
        assertEquals(blockEnd, cells[5]);
        for (int i = 1; i < cells.length; i++) {
            assertEquals("four local hours per cell", 4 * 60 * 60 * 1000L, cells[i] - cells[i - 1]);
        }
    }

    @Test
    public void springForwardKeepsSixDistinctCells() {
        // 2023-03-26: 02:00 CET -> 03:00 CEST, local hour 02:00 does not exist.
        long blockEnd = at(BERLIN, 2023, 3, 26, 23);
        ArrayList<Weather.WeatherInfo> hourly =
                hourlyForecasts(Weather.getLocalHourBefore(blockEnd, 24), 26);

        long[] cells = dailyBarInstants(blockEnd, hourly);
        assertNoDuplicates(cells);
        assertArrayEqualsInt(new int[]{3, 7, 11, 15, 19, 23}, localHoursOf(BERLIN, cells));
        assertEquals(blockEnd, cells[5]);
    }

    @Test
    public void sixHourBarAlsoStaysDistinctAtAutumnFallback() {
        long blockEnd = at(BERLIN, 2023, 10, 29, 3);
        ArrayList<Weather.WeatherInfo> hourly =
                hourlyForecasts(Weather.getLocalHourBefore(blockEnd, 24), 26);

        long[] cells = new long[6];
        for (int j = 0; j < 6; j++) {
            long target = Weather.getLocalHourBefore(blockEnd, 5 - j);
            int position = Weather.getClosestHourlyPosition(hourly, target);
            assertTrue(position >= 0);
            cells[j] = hourly.get(position).getTimestamp();
        }
        assertNoDuplicates(cells);
        assertEquals(blockEnd, cells[5]);
    }

    @Test
    public void dailyBlockHeadingStartIsBeforeEndAtBothTransitions() {
        long[] transitions = {
                at(BERLIN, 2023, 3, 26, 23),   // spring
                at(BERLIN, 2023, 10, 29, 23)   // autumn
        };
        for (int i = 0; i < transitions.length; i++) {
            long end = transitions[i];
            Weather.WeatherInfo daily = new Weather.WeatherInfo();
            daily.setForecastType(Weather.WeatherInfo.ForecastType.HOURS_24);
            daily.setTimestamp(end);
            long start = ForecastBlockTime.getBlockStartTime(daily, end);

            assertTrue("heading range must be monotonic", start < end);
            assertEquals("start must use local clock arithmetic",
                    Weather.getLocalHourBefore(end, 24), start);
        }
    }

    @Test
    public void nonDstZoneKeepsFixedHourArithmetic() {
        String[] zones = {"UTC", "Asia/Kolkata"};
        for (int z = 0; z < zones.length; z++) {
            String zone = zones[z];
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            long blockEnd = at(zone, 2023, 10, 29, 23);

            assertEquals("no DST: 20 local hours are 20 instant hours",
                    blockEnd - 20 * 60 * 60 * 1000L, Weather.getLocalHourBefore(blockEnd, 20));

            ArrayList<Weather.WeatherInfo> hourly =
                    hourlyForecasts(Weather.getLocalHourBefore(blockEnd, 24), 26);
            long[] cells = dailyBarInstants(blockEnd, hourly);
            assertNoDuplicates(cells);
            assertArrayEqualsInt(new int[]{3, 7, 11, 15, 19, 23}, localHoursOf(zone, cells));
            for (int i = 1; i < cells.length; i++) {
                assertEquals(4 * 60 * 60 * 1000L, cells[i] - cells[i - 1]);
            }
        }
    }

    private static void assertArrayEqualsInt(int[] expected, int[] actual) {
        assertEquals("number of bar cells", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("bar cell " + i, expected[i], actual[i]);
        }
    }
}

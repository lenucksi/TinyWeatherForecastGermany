/*
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

import nodomain.freeyourgadget.gadgetbridge.model.WeatherSpec;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for Codeberg #307: GadgetbridgeAPI used to drop the first
 * hourly forecast unconditionally. Some watches (Zepp OS) require a complete
 * hourly series and show no weather at all when the first hour is missing.
 *
 * Documented check for "Gadgetbridge receives at least one hourly forecast"
 * (no live device needed): these tests assert that buildHourlyForecast()
 * returns a non-empty WeatherSpec.hourly list both when the first value is the
 * current hour and when it is a later hour. On a device the same can be seen
 * in the logcat line "Hourly forecasts   : N" (tag GB, PrivateLog.INFO); N must
 * be >= 1. No emulator, network or Gadgetbridge installation is involved here.
 *
 * Robolectric is used only because WeatherSpec implements Parcelable; the
 * time logic itself is plain Java with a fixed Europe/Berlin time zone.
 */
@RunWith(RobolectricTestRunner.class)
public class GadgetbridgeHourlyForecastTest {

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

    private static long utc(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        calendar.clear();
        calendar.set(year, month - 1, day, hour, minute, 0);
        return calendar.getTimeInMillis();
    }

    private static Weather.WeatherInfo hourlyValue(long timestamp) {
        Weather.WeatherInfo info = new Weather.WeatherInfo();
        info.setForecastType(Weather.WeatherInfo.ForecastType.ONE_HOUR);
        info.setTimestamp(timestamp);
        info.setTemperature(20.0);
        return info;
    }

    private static CurrentWeatherInfo card(long currentTimestamp, long... hourlyTimestamps) {
        CurrentWeatherInfo card = new CurrentWeatherInfo();
        card.currentWeather.setTimestamp(currentTimestamp);
        for (long timestamp : hourlyTimestamps) {
            card.forecast1hourly.add(hourlyValue(timestamp));
        }
        return card;
    }

    @Test
    public void firstEntryIsSkippedWhenItIsTheCurrentHour() {
        long currentHour = at(2026, 9, 5, 18, 0);
        long nextHour = at(2026, 9, 5, 19, 0);
        CurrentWeatherInfo card = card(currentHour, currentHour, nextHour);

        assertEquals(1, GadgetbridgeAPI.firstHourlyForecastIndex(card.forecast1hourly, currentHour));

        ArrayList<WeatherSpec.Hourly> hourly = GadgetbridgeAPI.buildHourlyForecast(card);
        assertEquals(1, hourly.size());
        assertEquals((int) (nextHour / 1000), hourly.get(0).timestamp);
    }

    @Test
    public void currentHourWithMinutePrecisionIsStillSkipped() {
        // The report showed a current weather timestamp of 18:44:01 while the
        // hourly values start on the full hour.
        long reportedTimestamp = at(2026, 9, 5, 18, 44) + 1000L;
        long currentHour = at(2026, 9, 5, 18, 0);
        long nextHour = at(2026, 9, 5, 19, 0);
        CurrentWeatherInfo card = card(reportedTimestamp, currentHour, nextHour);

        assertTrue(GadgetbridgeAPI.isCurrentHour(currentHour, reportedTimestamp));
        assertEquals(1, GadgetbridgeAPI.firstHourlyForecastIndex(card.forecast1hourly, reportedTimestamp));
        assertEquals(1, GadgetbridgeAPI.buildHourlyForecast(card).size());
    }

    @Test
    public void firstEntryIsKeptWhenItIsTheNextHour() {
        // Exactly the reported case: current weather 18:44, hourly starts 20:00.
        // The old unconditional skip dropped the first real forecast hour.
        long currentTimestamp = at(2026, 9, 5, 18, 44);
        long firstHour = at(2026, 9, 5, 20, 0);
        long secondHour = at(2026, 9, 5, 21, 0);
        CurrentWeatherInfo card = card(currentTimestamp, firstHour, secondHour);

        assertEquals(0, GadgetbridgeAPI.firstHourlyForecastIndex(card.forecast1hourly, currentTimestamp));

        ArrayList<WeatherSpec.Hourly> hourly = GadgetbridgeAPI.buildHourlyForecast(card);
        assertEquals(2, hourly.size());
        assertEquals((int) (firstHour / 1000), hourly.get(0).timestamp);
        assertEquals((int) (secondHour / 1000), hourly.get(1).timestamp);
    }

    @Test
    public void staleCachedForecastStartingHoursLaterKeepsItsFirstEntry() {
        long currentTimestamp = at(2026, 9, 5, 18, 44);
        long staleFirstHour = at(2026, 9, 5, 20, 0);
        CurrentWeatherInfo card = card(currentTimestamp, staleFirstHour,
                at(2026, 9, 5, 21, 0), at(2026, 9, 5, 22, 0));

        assertEquals(0, GadgetbridgeAPI.firstHourlyForecastIndex(card.forecast1hourly, currentTimestamp));
        ArrayList<WeatherSpec.Hourly> hourly = GadgetbridgeAPI.buildHourlyForecast(card);
        assertEquals(3, hourly.size());
        assertEquals((int) (staleFirstHour / 1000), hourly.get(0).timestamp);
    }

    @Test
    public void singleCurrentHourEntryIsKeptSoTheHourlyListIsNeverEmpty() {
        long currentHour = at(2026, 9, 5, 18, 0);
        CurrentWeatherInfo card = card(currentHour, currentHour);

        assertEquals(0, GadgetbridgeAPI.firstHourlyForecastIndex(card.forecast1hourly, currentHour));
        ArrayList<WeatherSpec.Hourly> hourly = GadgetbridgeAPI.buildHourlyForecast(card);
        assertEquals("Gadgetbridge must receive at least one hourly forecast", 1, hourly.size());
        assertEquals((int) (currentHour / 1000), hourly.get(0).timestamp);
    }

    @Test
    public void emptyAndNullInputsAreSafeAndServeNoEntries() {
        long now = at(2026, 9, 5, 18, 44);

        assertEquals(0, GadgetbridgeAPI.firstHourlyForecastIndex(null, now));
        assertEquals(0, GadgetbridgeAPI.firstHourlyForecastIndex(
                new ArrayList<Weather.WeatherInfo>(), now));
        assertTrue(GadgetbridgeAPI.buildHourlyForecast(null).isEmpty());
        assertTrue(GadgetbridgeAPI.buildHourlyForecast(new CurrentWeatherInfo()).isEmpty());
    }

    @Test
    public void laterHourWithSameWallClockHourAfterDstChangeIsKept() {
        // Europe/Berlin falls back from 02:00 CEST to 02:00 CET on 2026-10-25.
        // 00:30 UTC is 02:30 CEST, 01:00 UTC is 02:00 CET: same wall-clock hour
        // number, but a whole hour later, so it must not be skipped.
        long firstOccurrence = utc(2026, 10, 25, 0, 30);
        long laterSameHourNumber = utc(2026, 10, 25, 1, 0);

        Calendar berlin = Calendar.getInstance(TimeZone.getTimeZone("Europe/Berlin"));
        berlin.setTimeInMillis(firstOccurrence);
        assertEquals(2, berlin.get(Calendar.HOUR_OF_DAY));
        berlin.setTimeInMillis(laterSameHourNumber);
        assertEquals(2, berlin.get(Calendar.HOUR_OF_DAY));

        assertFalse(GadgetbridgeAPI.isCurrentHour(laterSameHourNumber, firstOccurrence));
        CurrentWeatherInfo card = card(firstOccurrence, laterSameHourNumber,
                utc(2026, 10, 25, 2, 0));
        assertEquals(0, GadgetbridgeAPI.firstHourlyForecastIndex(card.forecast1hourly, firstOccurrence));
        assertEquals(2, GadgetbridgeAPI.buildHourlyForecast(card).size());
    }
}

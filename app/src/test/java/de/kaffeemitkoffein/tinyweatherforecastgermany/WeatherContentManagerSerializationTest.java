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
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/**
 * TASK-16: round-trip coverage for the string encoding WeatherContentManager uses
 * in ContentValues (timesteps and all forecast parameter arrays). The helpers
 * delegate to android.text.TextUtils, so this is a Robolectric test rather than
 * plain JUnit. No database, provider or network is involved.
 */
@RunWith(RobolectricTestRunner.class)
public class WeatherContentManagerSerializationTest {

    @Test
    public void stringArraySurvivesSerializeDeserialize() {
        String[] original = {
                "2026-11-27T12:00:00.000+0100",
                "2026-11-27T18:00:00.000+0100",
                "8.32,50.13,263.0"   // a plain comma is not the separator
        };

        String stored = WeatherContentManager.serializeString(original);

        assertArrayEquals(original, WeatherContentManager.deSerializeString(stored));
    }

    @Test
    public void arrayListSurvivesSerializeDeserialize() {
        List<String> original = Arrays.asList(
                "L821", "WIESBADEN-AURINGEN", "8.32,50.13,263.0");

        String stored = WeatherContentManager.serializeStringFromArrayList(
                new ArrayList<String>(original));

        assertEquals(original, WeatherContentManager.deSerializeStringToArraylist(stored));
        assertEquals(original.size(), WeatherContentManager.deSerializeString(stored).length);
    }

    @Test
    public void emptyValuesBetweenEntriesArePreserved() {
        String[] original = {"1", "", "3"};

        String stored = WeatherContentManager.serializeString(original);

        // The separator itself is literal ("_,_"), so a bare comma in a value
        // (the coordinates above) cannot split it during deserialization.
        assertEquals("_,_", WeatherContentManager.serializeString(new String[]{"", ""}));
        assertArrayEquals(original, WeatherContentManager.deSerializeString(stored));
        assertArrayEquals(original,
                WeatherContentManager.deSerializeString(
                        WeatherContentManager.serializeStringFromArrayList(
                                new ArrayList<String>(Arrays.asList(original)))));
    }

    @Test
    public void forecastParameterArraysRoundTripAfterProviderStorage() {
        // The exact columns getContentValuesFromWeatherCard() writes for one card.
        String[] timesteps = {"2026-11-27T06:00:00.000+0100", "2026-11-27T12:00:00.000+0100"};
        String[] ttt = {"3.5", "7.25"};
        String[] dd = {"220", "270"};

        String[] restoredTimesteps = WeatherContentManager.deSerializeString(
                WeatherContentManager.serializeString(timesteps));
        String[] restoredTtt = WeatherContentManager.deSerializeString(
                WeatherContentManager.serializeString(ttt));
        String[] restoredDd = WeatherContentManager.deSerializeString(
                WeatherContentManager.serializeString(dd));

        assertArrayEquals(timesteps, restoredTimesteps);
        assertArrayEquals(ttt, restoredTtt);
        assertArrayEquals(dd, restoredDd);
        assertEquals(Double.parseDouble(ttt[1]), Double.parseDouble(restoredTtt[1]), 0.0);
        assertEquals(Integer.parseInt(dd[0]), Integer.parseInt(restoredDd[0]));
    }
}

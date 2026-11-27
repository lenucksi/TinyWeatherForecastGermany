/*
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2026 Pawel Dube
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import android.location.Location;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Regression tests for CodeQL java/polynomial-redos (alert #4): parsing an
 * externally supplied geo intent must be linear-time, length-capped and strict.
 *
 * <p>The old pattern used -?[\d]*\.?[\d]* (nested `*` quantifiers) which can
 * backtrack polynomially. These tests pin the replacement behaviour: valid geo
 * intents still yield a Location, malformed/oversized input is rejected, and
 * pathological input terminates quickly.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33, 36})
public class GeoUriRedosTest {

    @Test
    public void validGeoUriSetsLocation(){
        Location location = MainActivity.locationFromGeoUri("geo:52.52,13.405");
        assertNotNull(location);
        assertEquals("manual", location.getProvider());
        assertEquals(52.52, location.getLatitude(), 1e-6);
        assertEquals(13.405, location.getLongitude(), 1e-6);
    }

    @Test
    public void validGeoUriWithNegativeCoordinatesAndTrailingParams(){
        // Trailing, non-numeric intent parameters (e.g. "?z=10") must keep working.
        Location location = MainActivity.locationFromGeoUri("geo:-33.8688,151.2093?z=10");
        assertNotNull(location);
        assertEquals(-33.8688, location.getLatitude(), 1e-6);
        assertEquals(151.2093, location.getLongitude(), 1e-6);
    }

    @Test
    public void caseInsensitiveSchemeIsAccepted(){
        Location location = MainActivity.locationFromGeoUri("GEO:48.137,11.575");
        assertNotNull(location);
        assertEquals(48.137, location.getLatitude(), 1e-6);
        assertEquals(11.575, location.getLongitude(), 1e-6);
    }

    @Test
    public void malformedGeoUrisAreRejected(){
        assertNull(MainActivity.locationFromGeoUri(null));
        assertNull(MainActivity.locationFromGeoUri(""));
        assertNull(MainActivity.locationFromGeoUri("geo:"));
        assertNull(MainActivity.locationFromGeoUri("geo:52.52"));
        assertNull(MainActivity.locationFromGeoUri("geo:52.52,"));
        assertNull(MainActivity.locationFromGeoUri("geo:,13.405"));
        assertNull(MainActivity.locationFromGeoUri("geo:.,."));
        assertNull(MainActivity.locationFromGeoUri("geo:abc,def"));
        assertNull(MainActivity.locationFromGeoUri("http://example.com/52,13"));
        // The previous pattern also accepted partial dots (".5") and trailing
        // dots ("52."); the strict pattern must not.
        assertNull(MainActivity.locationFromGeoUri("geo:.5,13.405"));
        assertNull(MainActivity.locationFromGeoUri("geo:52.,13.405"));
    }

    @Test(timeout = 2000)
    public void oversizedInputIsRejectedQuickly(){
        StringBuilder hostile = new StringBuilder("geo:");
        for (int i = 0; i < 100000; i++) {
            hostile.append('0');
        }
        hostile.append(",1");
        assertNull("oversized input must be rejected",
                MainActivity.locationFromGeoUri(hostile.toString()));
    }

    @Test(timeout = 2000)
    public void excessiveTrailingContentIsRejectedQuickly(){
        StringBuilder hostile = new StringBuilder("geo:52.52,13.405?z=");
        for (int i = 0; i < 1000; i++) {
            hostile.append('x');
        }
        assertNull("input over the length cap must be rejected",
                MainActivity.locationFromGeoUri(hostile.toString()));
    }

    @Test(timeout = 2000)
    public void repeatedZerosWithinLengthCapParseLinearly(){
        // Under the 256-char cap this used to trigger the worst backtracking;
        // the strict pattern parses it in linear time.
        StringBuilder input = new StringBuilder("geo:");
        for (int i = 0; i < 100; i++) {
            input.append('0');
        }
        input.append(",1");
        Location location = MainActivity.locationFromGeoUri(input.toString());
        assertNotNull(location);
        assertEquals(0.0, location.getLatitude(), 1e-6);
        assertEquals(1.0, location.getLongitude(), 1e-6);
    }
}

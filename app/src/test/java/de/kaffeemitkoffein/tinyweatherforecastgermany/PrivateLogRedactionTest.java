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

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure JVM coverage for the redaction seam. PrivateLog.Redaction is a nested class with no
 * Android references, so these tests need neither Robolectric nor a device (doc-5).
 */
public class PrivateLogRedactionTest {

    @Test
    public void geoUrisAreRemoved() {
        String result = PrivateLog.Redaction.redact("Looking up geo:53.55,9.98?z=14 failed");
        assertTrue(result, result.contains("[redacted]"));
        assertFalse(result, result.contains("geo:"));
        assertFalse(result, result.contains("53.55"));
        assertFalse(result, result.contains("9.98"));
    }

    @Test
    public void coordinatePairsAreRemovedRegardlessOfSeparator() {
        for (String message : new String[]{
                "Set station to 53.550 / 9.980",
                "Location 53.55,9.98",
                "Coordinates: 53.55; 9.98"
        }) {
            String result = PrivateLog.Redaction.redact(message);
            assertTrue(result, result.contains("[redacted]"));
            assertFalse(result, result.contains("53.55"));
            assertFalse(result, result.contains("9.98"));
        }
    }

    @Test
    public void labelledCoordinatesAreRemoved() {
        String result = PrivateLog.Redaction.redact("lat=53.55 longitude=9.98");
        assertFalse(result, result.contains("53.55"));
        assertFalse(result, result.contains("9.98"));
        assertTrue(result, result.contains("[redacted]"));
    }

    @Test
    public void configuredHostAndUrlsAreRemoved() {
        String withHost = PrivateLog.Redaction.redact(
                "Data url changed to: opendata.dwd.de", "opendata.dwd.de");
        assertEquals("Data url changed to: [redacted]", withHost);

        String withUrl = PrivateLog.Redaction.redact(
                "Fetching https://opendata.dwd.de/weather/alerts/latest.zip");
        assertFalse(withUrl, withUrl.contains("opendata.dwd.de"));
        assertTrue(withUrl, withUrl.contains("[redacted]"));
    }

    @Test
    public void abandonedFeedbackAddressIsRemoved() {
        String result = PrivateLog.Redaction.redact("Contact weather@kaffeemitkoffein.de");
        assertFalse(result, result.contains("weather@kaffeemitkoffein.de"));
        assertTrue(result, result.contains("[redacted]"));
    }

    @Test
    public void ordinaryMessagesAreNotChanged() {
        assertEquals("Weather update: success",
                PrivateLog.Redaction.redact("Weather update: success"));
        assertEquals("Updated 12 warnings.",
                PrivateLog.Redaction.redact("Updated 12 warnings."));
    }

    @Test
    public void nullMessageStaysNull() {
        assertNull(PrivateLog.Redaction.redact(null));
        assertNull(PrivateLog.Redaction.redact(null, "opendata.dwd.de"));
    }

    @Test
    public void logcatIsRestrictedToDebugAndUserdebugBuilds() {
        assertTrue(PrivateLog.Redaction.isLogcatAllowed(true, "0.63.4"));
        assertTrue(PrivateLog.Redaction.isLogcatAllowed(false, "0.63.4-userdebug_20261127"));
        assertTrue(PrivateLog.Redaction.isLogcatAllowed(false, "0.63.4-debug_20261127"));
        assertFalse(PrivateLog.Redaction.isLogcatAllowed(false, "0.63.4"));
        assertFalse(PrivateLog.Redaction.isLogcatAllowed(false, null));
    }
}

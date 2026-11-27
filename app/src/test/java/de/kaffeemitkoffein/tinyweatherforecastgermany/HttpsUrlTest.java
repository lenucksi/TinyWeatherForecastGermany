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

import java.net.URL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure JUnit coverage for the single URL-construction seam used by the network core.
 * No Android classes, no Robolectric and no device are required: APIReaders.HttpsUrl is
 * a static nested class with no framework references.
 */
public class HttpsUrlTest {

    @Test
    public void configuredHostAlwaysBecomesAnHttpsUrl() throws Exception {
        String url = APIReaders.HttpsUrl.build("opendata.dwd.de", "/weather/alerts/cap/latest.zip");
        assertEquals("https://opendata.dwd.de/weather/alerts/cap/latest.zip", url);
        assertEquals("https", new URL(url).getProtocol());
        assertTrue(url.startsWith(APIReaders.HttpsUrl.HTTPS_SCHEME));
    }

    @Test
    public void legacyCleartextHostIsNormalisedInsteadOfCreatingAnHttpUrl() throws Exception {
        // Installs upgraded from the old TLS-downgrade preference may still hold a scheme.
        String url = APIReaders.HttpsUrl.build("http://opendata.dwd.de", "/weather/local_forecasts/mos");
        assertEquals("https://opendata.dwd.de/weather/local_forecasts/mos", url);
        assertEquals("https", new URL(url).getProtocol());
    }

    @Test
    public void uppercaseAndTrailingSlashInHostDoNotBreakUrls() {
        assertEquals("opendata.dwd.de", APIReaders.HttpsUrl.normalizeHost("  HTTPS://OpenData.DWD.DE/  "));
        assertEquals("opendata.dwd.de", APIReaders.HttpsUrl.normalizeHost("http://opendata.dwd.de/"));
        assertEquals("https://opendata.dwd.de/weather",
                APIReaders.HttpsUrl.build("opendata.dwd.de/", "weather"));
    }

    @Test
    public void pathsKeepTheirCaseBecauseStationNamesAreCaseSensitive() {
        String url = APIReaders.HttpsUrl.build("opendata.dwd.de",
                "/weather/local_forecasts/mos/MOSMIX_L_LATEST_P0489.kmz");
        assertTrue(url.contains("MOSMIX_L_LATEST_P0489.kmz"));
    }

    @Test
    public void emptyHostAndNullInputsCannotYieldARequestUrl() {
        assertNull(APIReaders.HttpsUrl.normalizeHost(null));
        assertNull(APIReaders.HttpsUrl.build("", "/weather"));
        assertNull(APIReaders.HttpsUrl.build(null, "/weather"));
        assertNull(APIReaders.HttpsUrl.build("opendata.dwd.de", null));
    }
}

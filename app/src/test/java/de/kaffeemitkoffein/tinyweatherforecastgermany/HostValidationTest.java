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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * TASK-11: pure JUnit coverage for the host allowlist, the https-only URL seam and the
 * strict file-name validation used for scraped directory entries. No Android classes are
 * loaded: APIReaders.HttpsUrl is a static nested class with no framework references.
 */
public class HostValidationTest {

    @Test
    public void onlyDocumentedDwdDataHostsAreAllowed() {
        assertTrue(APIReaders.HttpsUrl.isAllowedHost("opendata.dwd.de"));
        assertTrue(APIReaders.HttpsUrl.isAllowedHost("maps.dwd.de"));
        assertTrue(APIReaders.HttpsUrl.isAllowedHost("https://OpenData.DWD.DE/"));
        assertTrue(APIReaders.HttpsUrl.isAllowedHost("  MAPS.DWD.DE  "));

        for (String host : new String[]{
                "evil.example",
                "opendata.dwd.de.evil.example",
                "opendata.dwd.de@evil.example",
                "http://opendata.dwd.de@evil.example/",
                "opendata.dwd.de:8080",
                "opendata.dwd.de/../evil",
                "opendata.dwd.de?x=1",
                "www.dwd.de",
                "kunden.dwd.de",
                "localhost",
                "",
                " "
        }) {
            assertFalse("unexpectedly allowed host: " + host,
                    APIReaders.HttpsUrl.isAllowedHost(host));
        }
        assertFalse(APIReaders.HttpsUrl.isAllowedHost(null));
    }

    @Test
    public void everyDerivedUrlUsesHttpsAndAnAllowlistedHost() throws Exception {
        String url = APIReaders.HttpsUrl.build("opendata.dwd.de",
                "/weather/alerts/cap/latest.zip");
        assertEquals("https://opendata.dwd.de/weather/alerts/cap/latest.zip", url);
        assertEquals("https", new URL(url).getProtocol());
        assertEquals("opendata.dwd.de", new URL(url).getHost());

        String legacy = APIReaders.HttpsUrl.build("http://maps.dwd.de/",
                "geoserver/dwd/ows");
        assertEquals("https://maps.dwd.de/geoserver/dwd/ows", legacy);
        assertEquals("https", new URL(legacy).getProtocol());
    }

    @Test
    public void disallowedHostCannotProduceARequestUrl() {
        assertNull(APIReaders.HttpsUrl.build("evil.example", "/weather"));
        assertNull(APIReaders.HttpsUrl.build("opendata.dwd.de@evil.example", "/weather"));
        assertNull(APIReaders.HttpsUrl.build("opendata.dwd.de:444", "/weather"));
        assertNull(APIReaders.HttpsUrl.build("maps.dwd.de.evil.example", "/weather"));
        assertNull(APIReaders.HttpsUrl.build("www.dwd.de", "/weather"));
        assertNull(APIReaders.HttpsUrl.build(null, "/weather"));
        assertNull(APIReaders.HttpsUrl.build("opendata.dwd.de", null));
    }

    @Test
    public void strictFileNamePatternRejectsTraversalAndQueryInjection() {
        assertTrue(APIReaders.HttpsUrl.isValidFileName("MOSMIX_L_LATEST_P0489.kmz"));
        assertTrue(APIReaders.HttpsUrl.isValidFileName("foo-bar_1.2.zip"));

        for (String name : new String[]{
                "../etc/passwd",
                "..",
                ".",
                "foo..bar",
                "file?x=1",
                "file#fragment",
                "file%2e%2e",
                "dir/file",
                "dir\\file",
                "file name.kmz",
                "äöü.kmz",
                ""
        }) {
            assertFalse("unexpectedly accepted file name: " + name,
                    APIReaders.HttpsUrl.isValidFileName(name));
        }
        assertFalse(APIReaders.HttpsUrl.isValidFileName(null));
    }

    @Test
    public void scrapedDirectoryEntryIsOnlyAcceptedAsAPlainFileName() {
        assertEquals("MOSMIX_L_LATEST_P0489.kmz",
                APIReaders.HttpsUrl.validatedDirectoryEntry(
                        "<html><a href=\"?C=M;O=A\">sort</a>"
                                + "<a href=\"MOSMIX_L_LATEST_P0489.kmz\">file</a></html>"));

        assertNull(APIReaders.HttpsUrl.validatedDirectoryEntry(
                "<a href=\"../../secret\">parent</a>"));
        assertNull(APIReaders.HttpsUrl.validatedDirectoryEntry(
                "<a href=\"file.kmz?download=1\">file</a>"));
        assertNull(APIReaders.HttpsUrl.validatedDirectoryEntry(
                "<a href=\"//evil.example/file.kmz\">file</a>"));
        assertNull(APIReaders.HttpsUrl.validatedDirectoryEntry("<a href=\"file.kmz>"));
        assertNull(APIReaders.HttpsUrl.validatedDirectoryEntry("no link at all"));
        assertNull(APIReaders.HttpsUrl.validatedDirectoryEntry(null));
    }
}

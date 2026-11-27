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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Static guard for TASK-41 acceptance criteria 1-3. Behavioural coverage lives in
 * {@link HttpsUrlTest} and {@link HttpsWarningUrlTest}; this test makes reintroducing a
 * cleartext path a build-time failure even on code paths a unit test does not execute.
 *
 * Pure JUnit: it only reads the repo's own source files, no Android classes are loaded.
 */
public class CleartextFallbackGuardTest {

    private static final String PKG = "src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/";
    private static final String[] OWNED_SOURCES = {
            PKG + "APIReaders.java",
            PKG + "WeatherSettings.java",
            PKG + "Settings.java",
            PKG + "TextForecasts.java"
    };

    private static File sourceFile(String relativePath) {
        String[] candidates = {
                relativePath,                 // Gradle unit-test working dir is app/
                "app/" + relativePath         // repository root
        };
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile()) {
                return file;
            }
        }
        fail("source file not found: " + relativePath);
        return null;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void networkCoreHasNoCleartextFallbacksOrTlsDowngradeFlag() throws IOException {
        for (String relativePath : OWNED_SOURCES) {
            File file = sourceFile(relativePath);
            assertNotNull(file);
            String source = read(file);
            for (String forbidden : new String[]{
                    "HttpURLConnection",
                    "isTLSdisabled",
                    "setDisableTLS",
                    "PREF_DISABLE_TLS",
                    "PREF_disable_tls",
                    "webPathLegacy"
            }) {
                assertFalseInSource(relativePath, source, forbidden);
            }
            for (String line : source.split("\\r?\\n")) {
                String trimmed = line.trim();
                // License headers and explanatory comments are not URL construction.
                if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                    continue;
                }
                assertFalseInSource(relativePath, trimmed, "http://");
            }
        }
    }

    @Test
    public void syncAdapterNoLongerBroadcastsTheRemovedSslErrorIntent() throws IOException {
        File file = sourceFile(PKG + "WeatherSyncAdapter.java");
        assertNotNull(file);
        assertFalseInSource("WeatherSyncAdapter.java", read(file), "MAINAPP_SSL_ERROR");
    }

    @Test
    public void platformNetworkSecurityConfigStillForbidsCleartext() throws IOException {
        File file = sourceFile("src/main/res/xml/network_security_config.xml");
        assertNotNull(file);
        assertTrue("cleartextTrafficPermitted=false must remain in the network security config",
                read(file).contains("cleartextTrafficPermitted=\"false\""));
    }

    private static void assertFalseInSource(String file, String source, String forbidden) {
        if (source.contains(forbidden)) {
            fail(file + " must not contain " + forbidden + " any more (TASK-41)");
        }
    }
}

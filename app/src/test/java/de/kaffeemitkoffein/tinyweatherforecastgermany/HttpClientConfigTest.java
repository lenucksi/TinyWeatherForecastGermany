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

import javax.net.ssl.HttpsURLConnection;
import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * TASK-31: verifies the central HTTP configuration (explicit timeouts, bounded retry with
 * capped backoff, cancellation) and that APIReaders has no duplicated connection code left.
 * Pure JUnit: APIReaders.Http is a static nested class with no framework references.
 */
public class HttpClientConfigTest {

    @Test
    public void timeoutsAreConfiguredCentrally() throws Exception {
        assertEquals(10_000, APIReaders.Http.CONNECT_TIMEOUT_MS);
        assertEquals(20_000, APIReaders.Http.READ_TIMEOUT_MS);

        // openConnection() does not connect, so this is safe without a network.
        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://opendata.dwd.de/weather").openConnection();
        APIReaders.Http.configure(connection);
        assertEquals(APIReaders.Http.CONNECT_TIMEOUT_MS, connection.getConnectTimeout());
        assertEquals(APIReaders.Http.READ_TIMEOUT_MS, connection.getReadTimeout());
        assertFalse("redirects would bypass the host allowlist", connection.getInstanceFollowRedirects());
        assertFalse(connection.getUseCaches());
    }

    @Test
    public void onlyHttpsUrlsAreOpened() throws Exception {
        try {
            APIReaders.Http.open(new URL("http://opendata.dwd.de/weather"));
            fail("cleartext URL was accepted");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("https"));
        }
    }

    @Test
    public void backoffIsBoundedAndCapped() {
        assertEquals(0, APIReaders.Http.backoffDelayMs(0));
        assertEquals(200, APIReaders.Http.backoffDelayMs(1));
        assertEquals(400, APIReaders.Http.backoffDelayMs(2));
        assertEquals(800, APIReaders.Http.backoffDelayMs(3));
        for (int i = 1; i <= 20; i++) {
            long delay = APIReaders.Http.backoffDelayMs(i);
            assertTrue("backoff below zero: " + delay, delay > 0);
            assertTrue("backoff above cap: " + delay, delay <= APIReaders.Http.MAX_BACKOFF_MS);
        }
        assertEquals(APIReaders.Http.MAX_BACKOFF_MS, APIReaders.Http.backoffDelayMs(20));
    }

    @Test
    public void retriesAreBoundedForTransientFailures() throws IOException {
        final AtomicInteger attempts = new AtomicInteger();
        try {
            APIReaders.Http.withRetry(() -> {
                attempts.incrementAndGet();
                throw new SocketTimeoutException("stalled endpoint");
            }, null);
            fail("retry loop did not fail");
        } catch (SocketTimeoutException expected) {
            // Expected after the bounded number of attempts.
        }
        assertEquals(APIReaders.Http.MAX_ATTEMPTS, attempts.get());
    }

    @Test
    public void nonTransientFailuresAreNotRetried() throws IOException {
        final AtomicInteger attempts = new AtomicInteger();
        try {
            APIReaders.Http.withRetry(() -> {
                attempts.incrementAndGet();
                throw new IOException("bad request");
            }, null);
            fail("retry loop did not fail");
        } catch (IOException expected) {
            // Expected immediately.
        }
        assertEquals(1, attempts.get());
    }

    @Test
    public void cancelledJobMakesNoAttempt() throws Exception {
        APIReaders.Cancellation cancellation = new APIReaders.Cancellation();
        cancellation.cancel();
        assertTrue(cancellation.isCancelled());

        final AtomicInteger attempts = new AtomicInteger();
        try {
            APIReaders.Http.withRetry(() -> {
                attempts.incrementAndGet();
                return "ok";
            }, cancellation);
            fail("cancelled job still ran a request");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("cancel"));
        }
        assertEquals(0, attempts.get());

        try {
            APIReaders.Http.get(new URL("https://opendata.dwd.de/weather"), cancellation);
            fail("cancelled job still opened a connection");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("cancel"));
        }
    }

    @Test
    public void allNetworkCallsGoThroughTheCentralClient() throws IOException {
        String source = readSource("src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/APIReaders.java");
        int connections = 0;
        int cancels = 0;
        for (String line : source.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
                continue;
            }
            if (trimmed.contains("openConnection()")) {
                connections++;
            }
            if (trimmed.equals("public void cancel() {")) {
                cancels++;
            }
        }
        assertEquals("exactly one openConnection() in Http", 1, connections);
        assertTrue("runnable cancel() methods missing", cancels >= 8);
        assertTrue(source.contains("setConnectTimeout(CONNECT_TIMEOUT_MS)"));
        assertTrue(source.contains("setReadTimeout(READ_TIMEOUT_MS)"));
        assertTrue(source.contains("Http.get(url, cancellation)"));
    }

    private static String readSource(String relativePath) throws IOException {
        String[] candidates = {relativePath, "app/" + relativePath};
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("source file not found: " + relativePath);
    }
}

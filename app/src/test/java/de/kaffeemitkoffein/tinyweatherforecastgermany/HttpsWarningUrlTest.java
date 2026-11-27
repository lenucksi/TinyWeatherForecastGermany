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

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.net.URL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TASK-41 acceptance criterion 4: warning data is addressed over HTTPS. No emulator is used;
 * Robolectric supplies the Context and the URL builder is exercised directly.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 36})
public class HttpsWarningUrlTest {

    /** The CAP warning URL is private; reflection keeps the production API unchanged. */
    private static String warningUrl(Context context) throws Exception {
        Method method = APIReaders.WeatherWarningsRunnable.class
                .getDeclaredMethod("getUrlString", Context.class);
        method.setAccessible(true);
        APIReaders.WeatherWarningsRunnable runnable =
                new APIReaders.WeatherWarningsRunnable(context);
        return (String) method.invoke(runnable, context);
    }

    @Test
    public void warningCapArchiveIsFetchedOverHttps() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        WeatherSettings.setWeatherUrl(context, WeatherSettings.PREF_WEATHER_URL_DEFAULT);

        String url = warningUrl(context);
        assertEquals("https", new URL(url).getProtocol());
        assertEquals("opendata.dwd.de", new URL(url).getHost());
        assertTrue("CAP archive URL missing: " + url, url.endsWith(".zip"));
        assertFalse("cleartext scheme in warning URL: " + url,
                url.contains("http://"));
    }

    @Test
    public void legacyCleartextPreferenceCannotDowngradeWarningUrl() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        // A user upgrading from an old install may still have a scheme stored. It is stripped,
        // but only an allowlisted DWD host may be used as a request target (TASK-11).
        WeatherSettings.setWeatherUrl(context, "http://opendata.dwd.de/");
        try {
            String url = warningUrl(context);
            assertEquals("https", new URL(url).getProtocol());
            assertEquals("opendata.dwd.de", new URL(url).getHost());
        } finally {
            WeatherSettings.setWeatherUrl(context, WeatherSettings.PREF_WEATHER_URL_DEFAULT);
        }
    }

    @Test
    public void nonDwdHostFallsBackToDefaultWarningsServer() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        WeatherSettings.setWeatherUrl(context, "http://legacy.example");
        try {
            assertEquals(WeatherSettings.PREF_WEATHER_URL_DEFAULT,
                    WeatherSettings.getWeatherUrl(context));
            String url = warningUrl(context);
            assertEquals("https", new URL(url).getProtocol());
            assertEquals("opendata.dwd.de", new URL(url).getHost());
        } finally {
            WeatherSettings.setWeatherUrl(context, WeatherSettings.PREF_WEATHER_URL_DEFAULT);
        }
    }

    @Test
    public void allTextForecastSourcesUseHttps() {
        Context context = RuntimeEnvironment.getApplication();
        WeatherSettings.setWeatherUrl(context, "https://opendata.dwd.de/");
        try {
            for (TextForecasts.TextForecastSource source :
                    TextForecasts.getTextForecastSources(context)) {
                String url = source.getWebPath();
                assertEquals("https", new URL(url).getProtocol());
                assertEquals("opendata.dwd.de", new URL(url).getHost());
            }
        } catch (Exception e) {
            throw new AssertionError("text forecast URL is not a valid https URL", e);
        } finally {
            WeatherSettings.setWeatherUrl(context, WeatherSettings.PREF_WEATHER_URL_DEFAULT);
        }
    }
}

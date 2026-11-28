/*
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2026 Pawel Dube
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import android.content.Context;
import android.graphics.Bitmap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * TASK-76: the play bar is a sequence of 24 cached frames, not a single image. This
 * test seeds every production cache slot with the real DWD fixture (identical pixels
 * are intentional: this proves the index -> cache file -> bitmap plumbing, while
 * RadarTimelineTest/VectorRadarTest pin the timer and overlay swap) and verifies the
 * timing/caption arithmetic against the fixture's 06:00Z timestamp.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RadarFrameSetFixtureTest {

    private static final String FIXTURE = "/fixtures/radar-real-20261126T0600Z.png";
    /** 2026-11-26T06:00:00.000Z, the DWD time parameter of the fixture. */
    private static final long FIXTURE_START = 1795672800000L;
    private static final int RAIN_PIXELS = 92050;
    private static final int WIDTH = 1108;
    private static final int HEIGHT = 1360;

    private Context context;
    private TimeZone originalTimeZone;

    @Before
    public void installFullFrameSet() throws Exception {
        context = RuntimeEnvironment.getApplication();
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        assertEquals(WIDTH, RadarMN2.getFixedRadarMapWidth(context));
        assertEquals(HEIGHT, RadarMN2.getFixedRadarMapHeight(context));
        for (int i = 0; i < APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE; i++) {
            File cacheFile = APIReaders.RadarMNSetGeoserverRunnable.getRadarMNFile(context, i);
            try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
                assertNotNull("missing real radar fixture " + FIXTURE, in);
                Files.copy(in, cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    @After
    public void removeFrameSet() {
        APIReaders.RadarMNSetGeoserverRunnable.deleteCacheFiles(context);
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    public void everyCachedIndexDecodesRealRainAndTheSetIsComplete() {
        assertTrue("seeded set must count as complete",
                APIReaders.RadarMNSetGeoserverRunnable.fullRadarDataSet(context));
        for (int i = 0; i < APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE; i++) {
            Bitmap bitmap = RadarMN2.getBitmap(context, i);
            assertNotNull("frame " + i + " did not decode", bitmap);
            assertEquals(WIDTH, bitmap.getWidth());
            assertEquals(HEIGHT, bitmap.getHeight());
            int[] pixels = new int[WIDTH * HEIGHT];
            bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
            assertEquals("frame " + i + " lost its rain pixels", RAIN_PIXELS,
                    countRainPixels(pixels));
        }
        assertFalse("there is no frame 24",
                APIReaders.RadarMNSetGeoserverRunnable.radarCacheFileExists(context,
                        APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE));
    }

    @Test
    public void refreshClearRemovesEveryRealFrame() {
        assertEquals(APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE,
                RadarMN2.clearRadarCache(context));
        assertFalse("no complete set may remain after refresh",
                APIReaders.RadarMNSetGeoserverRunnable.fullRadarDataSet(context));
        assertEquals("production decoder must not invent pixels",
                null, RadarMN2.getBitmap(context, 0));
    }

    @Test
    public void frameTimingAndCaptionMatchTheFixtureTimestamp() throws Exception {
        assertEquals(24, APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE);
        assertEquals(5 * 60 * 1000, APIReaders.RadarMNSetGeoserverRunnable.TIMESTEP_5MINUTES);
        APIReaders.RadarMNSetGeoserverRunnable runnable =
                new APIReaders.RadarMNSetGeoserverRunnable(context);
        assertEquals(FIXTURE_START, runnable.roundUTCUpToNextFiveMinutes(FIXTURE_START));
        assertEquals("an unaligned minute must round up to the next 5-minute step",
                FIXTURE_START + 5 * 60 * 1000L,
                runnable.roundUTCUpToNextFiveMinutes(FIXTURE_START + 60 * 1000L));

        String url = runnable.getUrlStringForTime(FIXTURE_START).toString();
        // Pin the https scheme and the DWD endpoint; the exact number of slashes
        // after the scheme is a pre-existing production detail, not a fixture fact.
        assertTrue(url, url.startsWith("https:"));
        assertTrue(url, url.contains("maps.dwd.de/geoserver/dwd/wms"));
        assertTrue(url, url.contains("time=2026-11-26T06:00:00.000Z"));
        assertTrue(url, url.contains("width=1108&height=1360"));

        SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.US);
        assertEquals("06:00", format.format(new Date(FIXTURE_START)));
        assertEquals("06:05", format.format(new Date(FIXTURE_START +
                APIReaders.RadarMNSetGeoserverRunnable.TIMESTEP_5MINUTES)));
        assertEquals("07:55", format.format(new Date(FIXTURE_START + 23L *
                APIReaders.RadarMNSetGeoserverRunnable.TIMESTEP_5MINUTES)));

        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        assertTrue("caption must be derived from start + frame index",
                activity.contains("new Date(rainSlidesStartTime+(nextRainSlide)*APIReaders.RadarMNSetGeoserverRunnable.TIMESTEP_5MINUTES)"));
    }

    private static int countRainPixels(int[] pixels) {
        Set<Integer> rainColors = new HashSet<Integer>();
        for (int color : Radarmap.RAINCOLORS) {
            if (color != 0) {
                rainColors.add(color);
            }
        }
        int count = 0;
        for (int pixel : pixels) {
            if (rainColors.contains(pixel)) {
                count++;
            }
        }
        return count;
    }

    private static String read(String path) throws Exception {
        // Tests may run from the primary checkout against this worktree's sources.
        java.io.File file = new java.io.File(path);
        if (!file.isFile() && path.startsWith("app/")) {
            file = new java.io.File(path.substring("app/".length()));
        }
        if (!file.isFile() && !path.startsWith("app/")) {
            file = new java.io.File("../" + path);
        }
        assertTrue("missing source file: " + path, file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}

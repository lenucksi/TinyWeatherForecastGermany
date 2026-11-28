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
import android.graphics.Color;

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
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * TASK-76: prove with a REAL DWD radar composite that the production cache decoder
 * (RadarMN2.getBitmap) keeps rain pixels instead of rendering an empty frame. The PNG
 * is an immutable classpath fixture; it is copied into the app cache at test time
 * under the production cache name and removed again afterwards. No network, no
 * emulator and no synthetic pixels are involved.
 *
 * Pixel statistics come from the fixture's meta.txt and were verified against the
 * exact palette/background mapping in RadarMN2.getBitmap() and Radarmap.RAINCOLORS.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RadarRealFixtureTest {

    private static final String FIXTURE = "/fixtures/radar-real-20261126T0600Z.png";
    private static final String META = "/fixtures/radar-real-20261126T0600Z.meta.txt";
    private static final String SHA256 = "aa69756c2dc6ca50e40cdd1bf0fdc38fa0b0e14b0eed5feafe0bb109a4aa1e9f";
    private static final int FRAME = 0;
    private static final int EXPECTED_BYTES = 96599;
    private static final int WIDTH = 1108;
    private static final int HEIGHT = 1360;
    private static final int RAIN_PIXELS = 92050;
    private static final int TRANSPARENT_PIXELS = 1408487;
    private static final int OPAQUE_PIXELS = 98393;

    /** The DWD background colours RadarMN2.getBitmap() turns into Color.TRANSPARENT. */
    private static final int[] BACKGROUND_COLORS = {
            0xFFBEBEBE, 0xFFFC3FFF, 0xFFBDBDBD, 0xFFFFFFFF
    };

    private Context context;
    private File cacheFile;

    @Before
    public void installFixture() throws Exception {
        context = RuntimeEnvironment.getApplication();
        // The fixture is 1108x1360 (scale 1). A scale-2 Robolectric display would
        // make the production decoder read outside the bitmap, so pin this here.
        assertEquals("fixture requires the scale-1 radar map", WIDTH,
                RadarMN2.getFixedRadarMapWidth(context));
        assertEquals("fixture requires the scale-1 radar map", HEIGHT,
                RadarMN2.getFixedRadarMapHeight(context));
        cacheFile = APIReaders.RadarMNSetGeoserverRunnable.getRadarMNFile(context, FRAME);
        try (InputStream in = getClass().getResourceAsStream(FIXTURE)) {
            assertNotNull("missing real radar fixture " + FIXTURE, in);
            Files.copy(in, cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @After
    public void removeFixture() {
        if (cacheFile != null) {
            cacheFile.delete();
        }
    }

    @Test
    public void fixtureIsTheRealDwdFrameAndIsAcceptedByTheCache() throws Exception {
        byte[] bytes = Files.readAllBytes(cacheFile.toPath());
        assertEquals("fixture changed or was replaced", EXPECTED_BYTES, bytes.length);
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
            hex.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        assertEquals("fixture changed or was replaced", SHA256, hex.toString());
        assertTrue("real frame must pass the >5000 byte cache rule",
                APIReaders.RadarMNSetGeoserverRunnable.radarCacheFileValid(context, FRAME));

        try (InputStream in = getClass().getResourceAsStream(META)) {
            assertNotNull("fixture provenance is missing", in);
            String meta = new String(readAll(in), StandardCharsets.UTF_8);
            assertTrue("provenance must name DWD", meta.contains("Deutscher Wetterdienst"));
            assertTrue("provenance must record CC BY 4.0", meta.contains("CC BY 4.0"));
            assertTrue("provenance checksum must match", meta.contains(SHA256));
        }
    }

    @Test
    public void productionDecoderKeepsRealRainPixels() {
        Bitmap bitmap = RadarMN2.getBitmap(context, FRAME);
        assertNotNull("production decoder returned no bitmap", bitmap);
        assertEquals(WIDTH, bitmap.getWidth());
        assertEquals(HEIGHT, bitmap.getHeight());

        int[] pixels = new int[WIDTH * HEIGHT];
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        assertPaletteStatistics(pixels);
    }

    @Test
    public void legacyScaledBitmapDecoderSeesTheSameRain() {
        Bitmap bitmap = RadarMN2.getScaledBitmap(context, FRAME);
        assertNotNull(bitmap);
        assertEquals(WIDTH, bitmap.getWidth());
        assertEquals(HEIGHT, bitmap.getHeight());
        int[] pixels = new int[WIDTH * HEIGHT];
        bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
        assertEquals(RAIN_PIXELS, countRainPixels(pixels));
    }

    private static void assertPaletteStatistics(int[] pixels) {
        int transparent = 0;
        int opaque = 0;
        int backgroundLeft = 0;
        Set<Integer> rainColors = new HashSet<Integer>();
        for (int color : Radarmap.RAINCOLORS) {
            rainColors.add(color); // RAINCOLORS[9] == RAINCOLORS[10]; count membership once.
        }
        rainColors.remove(0); // index 0 is the no-data entry, not rain.
        Map<Integer, Integer> rainByColor = new HashMap<Integer, Integer>();
        for (int pixel : pixels) {
            if (pixel == Color.TRANSPARENT) {
                transparent++;
            } else {
                opaque++;
            }
            for (int background : BACKGROUND_COLORS) {
                if (pixel == background) {
                    backgroundLeft++;
                }
            }
            if (rainColors.contains(pixel)) {
                Integer previous = rainByColor.get(pixel);
                rainByColor.put(pixel, previous == null ? 1 : previous + 1);
            }
        }
        assertEquals("DWD background must be transparent", TRANSPARENT_PIXELS, transparent);
        assertEquals("fixture must not decode to a blank frame", OPAQUE_PIXELS, opaque);
        assertEquals("no DWD background colour may remain opaque", 0, backgroundLeft);
        assertEquals("real rain pixels must survive the palette transform",
                RAIN_PIXELS, countRainPixels(rainByColor));

        // A few individual buckets guard against a frame that merely has the right
        // total but lost the spatial distribution of the real composite.
        assertEquals(8483, count(rainByColor, Radarmap.RAINCOLORS[1]));
        assertEquals(14310, count(rainByColor, Radarmap.RAINCOLORS[2]));
        assertEquals(19221, count(rainByColor, Radarmap.RAINCOLORS[3]));
        assertEquals(30870, count(rainByColor, Radarmap.RAINCOLORS[4]));
        assertEquals(12705, count(rainByColor, Radarmap.RAINCOLORS[5]));
        assertFalse("no rain at all", countRainPixels(rainByColor) == 0);
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

    private static int countRainPixels(Map<Integer, Integer> rainByColor) {
        int count = 0;
        for (Integer value : rainByColor.values()) {
            count += value;
        }
        return count;
    }

    private static int count(Map<Integer, Integer> counts, int color) {
        Integer value = counts.get(color);
        return value == null ? 0 : value;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}

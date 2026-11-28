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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TASK-66: guards the scaled, tip-anchored vector station pin and the mandatory radar
 * cache invalidation on a manual refresh. The anchor maths is pure Java so it can be
 * tested exactly without a native map or a Bitmap; the wiring is pinned by source
 * inspection, as in VectorOverlayTest / VectorRadarTest.
 *
 * QA teardown for fixture injections is kept in the project's local records.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class VectorPinAndCacheTest {

    @Test
    public void pinIsScaledToRoughlySixtyPercent(){
        assertEquals(60,RadarMN2.scaleVectorPinSize(100));
        assertEquals(12,RadarMN2.scaleVectorPinSize(20));
        assertEquals(24,RadarMN2.scaleVectorPinSize(40));
        // never degenerate to a zero-width bitmap
        assertEquals(1,RadarMN2.scaleVectorPinSize(1));
        assertEquals(1,RadarMN2.scaleVectorPinSize(0));
    }

    @Test
    public void spriteTipStaysOnTheCanvasCentreAtEverySize(){
        for (int size : new int[]{8,10,12,17,24,41,60,120,240}){
            int scaled = RadarMN2.scaleVectorPinSize(size);
            int canvas = RadarMN2.vectorPinCanvasSize(scaled);
            int offset = RadarMN2.vectorPinOffsetX(scaled);
            int tipX = offset + Math.round(RadarMN2.VECTOR_PIN_TIP_U*scaled);
            int tipY = scaled; // sprite is drawn at y=0, tip on its bottom edge
            assertEquals("tip x must be the bitmap centre for size "+size,canvas/2,tipX);
            assertEquals("tip y must be the bitmap centre for size "+size,canvas/2,tipY);
            assertTrue("sprite must fit the padded canvas for size "+size,
                    offset>=0 && offset+scaled<=canvas);
            assertTrue("scaled pin must be smaller for size "+size,scaled<size);
        }
    }

    @Test
    public void vectorPinUsesTheScaledAndAnchoredGeometry() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        String pin = between(activity,"private void applyVectorPin()","private void applyVectorWind()");
        assertTrue(pin.contains("RadarMN2.scaleVectorPinSize(sourcePinPixels)"));
        assertTrue(pin.contains("RadarMN2.vectorPinCanvasSize(pinSizePixels)"));
        assertTrue(pin.contains("RadarMN2.vectorPinOffsetX(pinSizePixels)"));
        assertTrue(pin.contains("new LatLng(ownLocation.latitude,ownLocation.longitude)"));
        assertTrue(pin.contains("IconFactory.getInstance(context).fromBitmap(anchored)"));
        // the old full-size, downward-only padding must be gone
        assertFalse(pin.contains("pinSizePixels*2"));
        // and the vector pin must still not use any legacy static-map pixel correction
        for (String forbidden : new String[]{"getPlotPoint","getXPixel","getYPixel","mercatorProjectionTile"}){
            assertFalse("vector pin must not use "+forbidden,pin.contains(forbidden));
        }
    }

    @Test
    public void refreshClearsEveryCachedRadarFrameAndQueuesFreshDownload() throws Exception {
        String radar = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/RadarMN2.java");
        assertTrue(radar.contains("public static int clearRadarCache(Context context)"));
        assertTrue(radar.contains("APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE"));
        assertTrue(radar.contains("APIReaders.RadarMNSetGeoserverRunnable.deleteCacheFile(context,i)"));
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        String refresh = between(activity,"if (item_id == R.id.menu_refresh)","if (item_id==R.id.map_legend)");
        assertTrue(refresh.contains("RadarMN2.clearRadarCache(context)"));
        assertTrue(refresh.contains("scheduledExecutorService.execute(radarMNSetGeoserverRunnable)"));
    }

    private static String between(String source, String start, String end){
        int from = source.indexOf(start);
        int to = source.indexOf(end,from);
        assertTrue("missing block "+start+" .. "+end,(from>=0) && (to>from));
        return source.substring(from,to);
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()) {
            // Tests are also run from the primary checkout against a worktree source tree.
            file = new File(path.substring("app/".length()));
        }
        assertTrue("missing source file: " + path,file.isFile());
        return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    }
}

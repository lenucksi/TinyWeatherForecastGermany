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
 * TASK-54: pins the georeferencing of the DWD radar composite and the vector ImageSource
 * wiring without running the native map. The legacy static-map radar path stays untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class VectorRadarTest {

    private static final double EPS = 0.0000000001d;
    private static final double EARTH_RADIUS = 6378137d;

    private static double webMercatorLongitude(double x){
        return Math.toDegrees(x/EARTH_RADIUS);
    }

    private static double webMercatorLatitude(double y){
        return Math.toDegrees(Math.PI/2 - 2*Math.atan(Math.exp(-y/EARTH_RADIUS)));
    }

    @Test
    public void cornersMatchTheDwdEpsg3857BboxExactly(){
        assertEquals(webMercatorLongitude(493000d),RadarMN2.RADAR_WEST_LONGITUDE,EPS);
        assertEquals(webMercatorLongitude(1791000d),RadarMN2.RADAR_EAST_LONGITUDE,EPS);
        assertEquals(webMercatorLatitude(5861000d),RadarMN2.RADAR_SOUTH_LATITUDE,EPS);
        assertEquals(webMercatorLatitude(7470000d),RadarMN2.RADAR_NORTH_LATITUDE,EPS);
        // Dimensional sanity: the bbox spans roughly 11.66 degrees of longitude.
        assertTrue(RadarMN2.RADAR_EAST_LONGITUDE>RadarMN2.RADAR_WEST_LONGITUDE);
        assertTrue(RadarMN2.RADAR_NORTH_LATITUDE>RadarMN2.RADAR_SOUTH_LATITUDE);
        double[] bounds = RadarMN2.getRadarVectorBounds();
        assertEquals(4,bounds.length);
        assertEquals(RadarMN2.RADAR_WEST_LONGITUDE,bounds[0],EPS);
        assertEquals(RadarMN2.RADAR_NORTH_LATITUDE,bounds[1],EPS);
        assertEquals(RadarMN2.RADAR_EAST_LONGITUDE,bounds[2],EPS);
        assertEquals(RadarMN2.RADAR_SOUTH_LATITUDE,bounds[3],EPS);
    }

    @Test
    public void latLngQuadUsesTopLeftTopRightBottomRightBottomLeftOrder(){
        org.maplibre.android.geometry.LatLngQuad quad = WeatherWarningActivity.radarLatLngQuad();
        assertEquals(RadarMN2.RADAR_NORTH_LATITUDE,quad.getTopLeft().getLatitude(),EPS);
        assertEquals(RadarMN2.RADAR_WEST_LONGITUDE,quad.getTopLeft().getLongitude(),EPS);
        assertEquals(RadarMN2.RADAR_NORTH_LATITUDE,quad.getTopRight().getLatitude(),EPS);
        assertEquals(RadarMN2.RADAR_EAST_LONGITUDE,quad.getTopRight().getLongitude(),EPS);
        assertEquals(RadarMN2.RADAR_SOUTH_LATITUDE,quad.getBottomRight().getLatitude(),EPS);
        assertEquals(RadarMN2.RADAR_EAST_LONGITUDE,quad.getBottomRight().getLongitude(),EPS);
        assertEquals(RadarMN2.RADAR_SOUTH_LATITUDE,quad.getBottomLeft().getLatitude(),EPS);
        assertEquals(RadarMN2.RADAR_WEST_LONGITUDE,quad.getBottomLeft().getLongitude(),EPS);
    }

    @Test
    public void oneImageSourceAndRasterLayerAreReusedForAllFrames() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        assertTrue(activity.contains("ImageSource source = style.getSourceAs(RADAR_SOURCE_ID)"));
        assertTrue(activity.contains("new ImageSource(RADAR_SOURCE_ID,radarLatLngQuad(),bitmap)"));
        assertTrue(activity.contains("source.setImage(bitmap)"));
        assertTrue(activity.contains("new RasterLayer(RADAR_LAYER_ID,RADAR_SOURCE_ID)"));
        // TASK-91: opacity comes from the TASK-89 setting, and the raster is anchored above
        // the fills but below the outlines (no hard-coded 0.8f alpha any more).
        assertTrue(activity.contains("float radarOpacity=WeatherSettings.getRadarOpacity(context)"));
        assertTrue(activity.contains("PropertyFactory.rasterOpacity(opacity)"));
        assertFalse(activity.contains("RADAR_RASTER_OPACITY"));
        assertTrue(activity.contains("style.addLayerBelow(raster,belowId)"));
        assertTrue(activity.contains("radarOrderIsCorrect(ids)"));
        assertTrue(activity.contains("style.removeLayer(RADAR_LAYER_ID)"));
        // Installed only from the style-ready callback, never before.
        assertTrue(activity.contains("applyVectorRadarFrame(nextRainSlide)"));
    }

    @Test
    public void vectorRadarPathHasNoHandPixelCorrectionOrPerFrameNetwork() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        String vectorRadarMethod = between(activity,"private void applyVectorRadarFrame","private void removeVectorRadarLayer");
        assertTrue(vectorRadarMethod.contains("RadarMN2.getBitmap(context,count)"));
        for (String forbidden : new String[]{
                "getXPixel","getYPixel","getPlotPoint","getScaledBitmap",
                "Http.","getRadarInputStream","getUrlStringForTime","new URL"}) {
            assertFalse("vector radar path must not use "+forbidden,vectorRadarMethod.contains(forbidden));
        }
        // The legacy pixel corrections themselves must remain available to the static map.
        String radarMN2 = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/RadarMN2.java");
        assertTrue(radarMN2.contains("x = x + 7 - (x/width)*14"));
        assertTrue(radarMN2.contains("y = y - 5 - (y/height)*5"));
        // Legacy drawing path still paints the static bitmap.
        assertTrue(activity.contains("Canvas radarCanvas = new Canvas(radarBitmap)"));
        assertTrue(activity.contains("RadarMN2.getScaledBitmap(context,count)"));
    }

    @Test
    public void seekBarSwapsCachedFrameImmediately() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        String listener = between(activity,"public void onProgressChanged","public void onStartTrackingTouch");
        // TASK-87: the drag guard also checks that a real animation exists.
        assertTrue(listener.contains("if (fromUser && (usableRainFrames>1))"));
        assertTrue(listener.contains("applyVectorRadarFrame(nextRainSlide)"));
        // Frames are downloaded once at startup for both modes; no early return remains.
        assertFalse(activity.contains("Starting the reader in vector mode would also try to measure"));
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

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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * TASK-65: warning row / notification click -> map camera. The geometry helper is pure, so
 * the WGS84 extent (longitude = polygonX, latitude = polygonY) can be verified on the JVM.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class WarningCameraJumpTest {

    private static Polygon polygon(float[] longitudes,float[] latitudes){
        return new Polygon(longitudes,latitudes,"fixture");
    }

    private static WeatherWarning warning(){
        WeatherWarning warning = new WeatherWarning();
        warning.identifier = "fixture-warning";
        warning.event = "WIND";
        warning.area_color = "255 0 0";
        warning.polygonlist = new ArrayList<Polygon>();
        warning.excluded_polygonlist = new ArrayList<Polygon>();
        return warning;
    }

    @Test
    public void boundsUseLongitudeFromXAndLatitudeFromY(){
        WeatherWarning warning = warning();
        warning.polygonlist.add(polygon(new float[]{9f,10f,10f,9f},new float[]{50f,50f,51f,51f}));
        assertArrayEquals(new double[]{9d,50d,10d,51d},WeatherWarningActivity.warningBounds(warning),0d);
    }

    @Test
    public void excludedPolygonsExtendTheFramedExtent(){
        WeatherWarning warning = warning();
        warning.polygonlist.add(polygon(new float[]{9f,10f,10f,9f},new float[]{50f,50f,51f,51f}));
        warning.excluded_polygonlist.add(polygon(new float[]{8f,9.5f},new float[]{49f,52f}));
        assertArrayEquals(new double[]{8d,49d,10d,52d},WeatherWarningActivity.warningBounds(warning),0d);
    }

    @Test
    public void malformedCoordinatesAreIgnored(){
        WeatherWarning warning = warning();
        warning.polygonlist.add(polygon(
                new float[]{9f,200f,Float.NaN,10f},
                new float[]{50f,51f,52f,51f}));
        assertArrayEquals(new double[]{9d,50d,10d,51d},WeatherWarningActivity.warningBounds(warning),0d);
        // Mismatched arrays are truncated to the shorter one instead of crashing.
        warning.excluded_polygonlist.add(polygon(new float[]{8f},new float[]{49f,48f}));
        assertArrayEquals(new double[]{8d,49d,10d,51d},WeatherWarningActivity.warningBounds(warning),0d);
    }

    @Test
    public void warningsWithoutAnyGeometryHaveNoBounds(){
        assertNull(WeatherWarningActivity.warningBounds(warning()));
        WeatherWarning noLists = new WeatherWarning();
        assertNull(WeatherWarningActivity.warningBounds(noLists));
        assertNull(WeatherWarningActivity.warningBounds(null));
    }

    @Test
    public void degenerateBoundsKeepTheCurrentZoom(){
        assertTrue(WeatherWarningActivity.boundsAreDegenerate(new double[]{9d,50d,9d,50d}));
        assertTrue(WeatherWarningActivity.boundsAreDegenerate(new double[]{9d,50d,9d,51d}));
        assertFalse(WeatherWarningActivity.boundsAreDegenerate(new double[]{9d,50d,9.1d,50.5d}));
        assertTrue(WeatherWarningActivity.boundsAreDegenerate(null));
    }

    @Test
    public void rowClickAndNotificationUseTheSameCameraJump() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        for (String token : new String[]{
                "weatherWarningAdapter.setWarningClickListener",
                "onWarningSelected(warning,-1)",
                "maybeJumpToPendingWarning()",
                "WeatherSyncAdapter.EXTRA_WARNING_IDENTIFIER",
                "jumpMapToWarning(warning)",
                "double[] bounds = warningBounds(warning)",
                "CameraUpdateFactory.newLatLngBounds(latLngBounds,WARNING_CAMERA_PADDING)",
                "MAX_WARNING_CAMERA_ZOOM",
                "boundsAreDegenerate(targetBounds)",
                "CameraUpdateFactory.newLatLng(latLngBounds.getCenter())",
                "R.string.warning_no_geometry"
        }) {
            assertTrue("missing camera wiring: "+token,activity.contains(token));
        }
        String adapter = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningAdapter.java");
        assertTrue("row taps must reach the activity",
                adapter.contains("warningClickListener.onWarningClick(warning)"));
        // The static map keeps its inverse tap->list jump; it must not be removed.
        assertTrue(activity.contains("jumpListViewToSelection(polygoncache.get(position))"));
    }

    @Test
    public void geometrylessWarningFallbackIsDocumented() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        String jump = between(activity,"private void jumpMapToWarning","private boolean isDeviceLandscape");
        assertTrue(jump.contains("neither a <polygon>"));
        assertTrue(jump.contains("WARNCELLID"));
        assertTrue(jump.contains("UV"));
        assertTrue(jump.contains("map camera unchanged"));
    }

    private static String between(String source,String start,String end){
        int from = source.indexOf(start);
        int to = source.indexOf(end,from);
        assertTrue("missing block "+start+" .. "+end,(from>=0) && (to>from));
        return source.substring(from,to);
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()){
            // Tests are also run from the primary checkout against a worktree source tree.
            file = new File(path.substring("app/".length()));
        }
        assertTrue("missing source file: "+path,file.isFile());
        return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    }
}

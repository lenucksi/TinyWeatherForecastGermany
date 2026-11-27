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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TASK-55: pins the geo-referenced station pin / wind arcs and guards against reintroducing
 * the legacy hand pixel corrections in the vector path. No native map library is loaded.
 */
public class VectorOverlayTest {

    @Test
    public void pinUsesStationLatLngInsteadOfStaticMapPixels() throws Exception {
        String pin = between(readActivity(),"private void applyVectorPin()","private void applyVectorWind()");
        assertTrue(pin.contains("new LatLng(ownLocation.latitude,ownLocation.longitude)"));
        assertTrue(pin.contains("new MarkerOptions()"));
        assertTrue(pin.contains("IconFactory.getInstance(context).fromBitmap(anchored)"));
        for (String forbidden : new String[]{"getPlotPoint","getXPixel","getYPixel","mercatorProjectionTile","pinPoint"}) {
            assertFalse("vector pin must not use "+forbidden,pin.contains(forbidden));
        }
    }

    @Test
    public void windArcsUseGeoDestinationCoordinatesOnly() throws Exception {
        String activity = readActivity();
        String wind = between(activity,"private void applyVectorWind()","private void addVectorWindLine");
        assertTrue(wind.contains("RadarMN2.getDestinationCoordinates(ownLocation.longitude,ownLocation.latitude"));
        assertTrue(wind.contains("WeatherSettings.displayWindDistance(context)"));
        assertTrue(wind.contains("Weather.getCurrentWeatherInfo(context)"));
        assertTrue(wind.contains("WarningUiDataGuard.windDataIndex"));
        assertTrue(wind.contains("mapLibreMap.removePolyline(line)"));
        for (String forbidden : new String[]{
                "getXPixel","getYPixel","mercatorProjectionTile","Canvas","drawLine","DashPathEffect"}) {
            assertFalse("vector wind must not use "+forbidden,wind.contains(forbidden));
        }
        String line = between(activity,"private void addVectorWindLine","private void setupVectorMap");
        assertTrue(line.contains("new PolylineOptions()"));
        assertTrue(line.contains("new LatLng(from[1],from[0])"));
        assertTrue(line.contains("new LatLng(to[1],to[0])"));
    }

    @Test
    public void overlaysAreInstalledAfterStyleReadyAndOnStationChange() throws Exception {
        String activity = readActivity();
        int styleFrom = activity.indexOf("public void onStyleLoaded(Style style)");
        int styleTo = activity.indexOf("map.moveCamera",styleFrom);
        assertTrue(styleFrom>=0 && styleTo>styleFrom);
        assertTrue(activity.substring(styleFrom,styleTo).contains("applyVectorOverlays()"));

        int displayFrom = activity.indexOf("// Same warning data as the legacy path");
        int displayTo = activity.indexOf("scheduledExecutorService.execute",displayFrom);
        assertTrue(displayFrom>=0 && displayTo>displayFrom);
        assertTrue(activity.substring(displayFrom,displayTo).contains("applyVectorOverlays()"));
    }

    @Test
    public void destinationCoordinatesAreGeographicallyCorrect(){
        // ~111.195 km equals one degree of latitude / longitude at the equator.
        double kmPerDegree = 6371d*Math.PI/180d;
        double[] north = RadarMN2.getDestinationCoordinates(10d,50d,0d,kmPerDegree);
        assertEquals(10d,north[0],0.0001);
        assertEquals(51d,north[1],0.0001);
        double[] east = RadarMN2.getDestinationCoordinates(0d,0d,90d,kmPerDegree);
        assertEquals(1d,east[0],0.0001);
        assertEquals(0d,east[1],0.0001);
    }

    private static String between(String source, String start, String end){
        int from = source.indexOf(start);
        int to = source.indexOf(end,from);
        assertTrue("missing block "+start+" .. "+end,(from>=0) && (to>from));
        return source.substring(from,to);
    }

    private static String readActivity() throws Exception {
        String path = "app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java";
        File file = new File(path);
        if (!file.isFile()) {
            // Tests are also run from the primary checkout against a worktree source tree.
            file = new File(path.substring("app/".length()));
        }
        assertTrue("missing source file: " + path,file.isFile());
        return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    }
}

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

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TASK-53: verifies the conversion of parsed CAP polygons into the GeoJSON strings fed to
 * MapLibre, and pins the vector layer wiring without loading the native map library.
 * The static-map renderer is covered by the existing suite and must stay untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class WarningGeoJsonTest {

    private static WeatherWarning warning(String identifier, String areaColor){
        WeatherWarning warning = new WeatherWarning();
        warning.identifier = identifier;
        warning.area_color = areaColor;
        return warning;
    }

    private static Polygon polygon(float[] longitudes, float[] latitudes){
        return new Polygon(longitudes,latitudes,"unused");
    }

    private static JSONObject firstFeature(String geoJson) throws Exception {
        JSONObject collection = new JSONObject(geoJson);
        assertEquals("FeatureCollection",collection.getString("type"));
        JSONArray features = collection.getJSONArray("features");
        assertTrue(features.length()>0);
        return features.getJSONObject(0);
    }

    @Test
    public void warningPolygonsBecomeClosedGeoJsonRingsWithColorAndIdentifier() throws Exception {
        ArrayList<WeatherWarning> warnings = new ArrayList<WeatherWarning>();
        WeatherWarning warning = warning("warn-1","255 235 59"); // CAP AREA_COLOR level 1
        warning.polygonlist = new ArrayList<Polygon>();
        warning.polygonlist.add(polygon(new float[]{7f,8f,8f},new float[]{50f,50f,51f}));
        warning.excluded_polygonlist = new ArrayList<Polygon>();
        warnings.add(warning);

        String geoJson = WeatherWarningActivity.WarningGeoJson.featureCollection(warnings,false);
        JSONObject feature = firstFeature(geoJson);
        assertEquals("Feature",feature.getString("type"));
        assertEquals("Polygon",feature.getJSONObject("geometry").getString("type"));
        assertEquals("warn-1",feature.getJSONObject("properties").getString("identifier"));
        assertEquals("#FFEB3B",feature.getJSONObject("properties").getString("color"));

        JSONArray ring = feature.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0);
        assertEquals("ring must be explicitly closed",4,ring.length());
        JSONArray first = ring.getJSONArray(0);
        JSONArray last = ring.getJSONArray(3);
        assertEquals(7.0,first.getDouble(0),0.0001);   // polygonX is longitude
        assertEquals(50.0,first.getDouble(1),0.0001);  // polygonY is latitude
        assertEquals(first.getDouble(0),last.getDouble(0),0.0001);
        assertEquals(first.getDouble(1),last.getDouble(1),0.0001);
        assertEquals(8.0,ring.getJSONArray(1).getDouble(0),0.0001);
    }

    @Test
    public void excludedPolygonsAreExportedOnlyIntoTheSeparateCollection() throws Exception {
        ArrayList<WeatherWarning> warnings = new ArrayList<WeatherWarning>();
        WeatherWarning warning = warning("warn-2","229 57 53"); // level 3
        warning.polygonlist = new ArrayList<Polygon>();
        warning.polygonlist.add(polygon(new float[]{9f,10f,10f},new float[]{51f,51f,52f}));
        warning.excluded_polygonlist = new ArrayList<Polygon>();
        warning.excluded_polygonlist.add(polygon(new float[]{9.2f,9.4f,9.4f},new float[]{51.2f,51.2f,51.4f}));
        warnings.add(warning);

        JSONObject warningCollection = new JSONObject(
                WeatherWarningActivity.WarningGeoJson.featureCollection(warnings,false));
        JSONObject excludedCollection = new JSONObject(
                WeatherWarningActivity.WarningGeoJson.featureCollection(warnings,true));
        assertEquals(1,warningCollection.getJSONArray("features").length());
        assertEquals(1,excludedCollection.getJSONArray("features").length());
        assertEquals("warn-2",excludedCollection.getJSONArray("features").getJSONObject(0)
                .getJSONObject("properties").getString("identifier"));
        assertEquals("#E53935",excludedCollection.getJSONArray("features").getJSONObject(0)
                .getJSONObject("properties").getString("color"));
        JSONArray excludedRing = excludedCollection.getJSONArray("features").getJSONObject(0)
                .getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0);
        assertEquals(9.2,excludedRing.getJSONArray(0).getDouble(0),0.0001);
    }

    @Test
    public void nullAndDegeneratePolygonsAreSkipped() throws Exception {
        ArrayList<WeatherWarning> warnings = new ArrayList<WeatherWarning>();
        warnings.add(null);
        WeatherWarning withoutPolygons = warning("none","128 128 128");
        warnings.add(withoutPolygons);
        WeatherWarning degenerate = warning("short","128 128 128");
        degenerate.polygonlist = new ArrayList<Polygon>();
        degenerate.polygonlist.add(polygon(new float[]{7f,8f},new float[]{50f,51f}));
        warnings.add(degenerate);

        String geoJson = WeatherWarningActivity.WarningGeoJson.featureCollection(warnings,false);
        assertEquals(0,new JSONObject(geoJson).getJSONArray("features").length());
        assertEquals("FeatureCollection",new JSONObject(geoJson).getString("type"));
    }

    @Test
    public void vectorLayersAreInstalledOnlyAfterStyleAndUseDistinctExcludedStyle() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        assertTrue(activity.contains("Style.OnStyleLoaded"));
        assertTrue(activity.contains("vectorWarningStyleReady"));
        assertTrue(activity.contains("new GeoJsonSource(WARNING_SOURCE_ID,warningGeoJson)"));
        assertTrue(activity.contains("new GeoJsonSource(EXCLUDED_SOURCE_ID,excludedGeoJson)"));
        assertTrue(activity.contains("warningSource.setGeoJson(warningGeoJson)"));
        assertTrue(activity.contains("excludedSource.setGeoJson(excludedGeoJson)"));
        assertTrue(activity.contains("new FillLayer(WARNING_FILL_LAYER_ID,WARNING_SOURCE_ID)"));
        assertTrue(activity.contains("new LineLayer(WARNING_LINE_LAYER_ID,WARNING_SOURCE_ID)"));
        assertTrue(activity.contains("new FillLayer(EXCLUDED_FILL_LAYER_ID,EXCLUDED_SOURCE_ID)"));
        assertTrue(activity.contains("new LineLayer(EXCLUDED_LINE_LAYER_ID,EXCLUDED_SOURCE_ID)"));
        assertTrue(activity.contains("PropertyFactory.fillColor(Expression.get(WarningGeoJson.PROPERTY_COLOR))"));
        assertTrue(activity.contains("PropertyFactory.lineDasharray"));
        assertTrue(activity.contains("EXCLUDED_FILL_COLOR"));
        // Tapping uses the same parsed polygon cache as the legacy static map.
        assertTrue(activity.contains("map.addOnMapClickListener"));
        assertTrue(activity.contains("checkForTapInPolygonWarning((float) point.getLongitude(),(float) point.getLatitude())"));
        // The static fallback must keep its own rendering path.
        assertTrue(activity.contains("excluded_polygoncache.add(new Polygon(polygonX,polygonY,warning.identifier))"));
    }

    @Test
    public void noExternalGeoJsonParserDependencyWasAdded() throws Exception {
        String gradle = read("app/build.gradle");
        assertTrue(gradle.contains("org.maplibre.gl:android-sdk-opengl:13.8.0"));
        assertFalse("GeoJSON is parsed by MapLibre itself",gradle.contains("geojson-jackson"));
        assertFalse(gradle.contains("com.fasterxml.jackson"));
        assertFalse(gradle.contains("org.geojson:"));
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

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
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * TASK-74: the cached DWD UV raster is a sixth MapLibre layer. These checks pin the exact
 * georeferencing of the WMS bbox, the layer order relative to the TASK-91 radar contract,
 * the cached-PNG-only render path, and the explicit MainActivity entry point.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class UvMapLayerTest {

    private static final double EPS = 0.0000001d;
    private static final String UV = "twfg-uvi-raster";
    private static final String FILL = "twfg-warning-fill";
    private static final String EXCLUDED_FILL = "twfg-excluded-fill";
    private static final String RADAR = "twfg-radar-raster";
    private static final String OUTLINE = "twfg-warning-outline";

    @Test
    public void quadUsesWms11BboxCornersInTopLeftOrder(){
        // WeatherLayer.WarnMapGeo is {minx,miny,maxx,maxy} for WMS 1.1.0 / EPSG:4326.
        float[] geo = WeatherLayer.WarnMapGeo;
        assertEquals(4,geo.length);
        assertEquals(5.86599899999999f,geo[0],0f);
        assertEquals(47.270362f,geo[1],0f);
        assertEquals(15.037507f,geo[2],0f);
        assertEquals(55.057375f,geo[3],0f);

        org.maplibre.android.geometry.LatLngQuad quad = WeatherWarningActivity.uvLatLngQuad(geo);
        // Top row is maxy (north), bottom row is miny (south); x is west/east as requested.
        assertEquals(geo[3],quad.getTopLeft().getLatitude(),EPS);
        assertEquals(geo[0],quad.getTopLeft().getLongitude(),EPS);
        assertEquals(geo[3],quad.getTopRight().getLatitude(),EPS);
        assertEquals(geo[2],quad.getTopRight().getLongitude(),EPS);
        assertEquals(geo[1],quad.getBottomRight().getLatitude(),EPS);
        assertEquals(geo[2],quad.getBottomRight().getLongitude(),EPS);
        assertEquals(geo[1],quad.getBottomLeft().getLatitude(),EPS);
        assertEquals(geo[0],quad.getBottomLeft().getLongitude(),EPS);
        assertTrue(quad.getTopLeft().getLatitude()>quad.getBottomLeft().getLatitude());
        assertTrue(quad.getTopRight().getLongitude()>quad.getTopLeft().getLongitude());
    }

    @Test
    public void uvRasterSitsBelowEveryWarningLayer(){
        assertEquals(FILL,WeatherWarningActivity.uvBelowLayerId(
                Arrays.asList(UV,FILL,EXCLUDED_FILL,RADAR,OUTLINE)));
        assertEquals(RADAR,WeatherWarningActivity.uvBelowLayerId(
                Arrays.asList(UV,RADAR,OUTLINE)));
        assertEquals(OUTLINE,WeatherWarningActivity.uvBelowLayerId(
                Arrays.asList(UV,OUTLINE)));
        assertNull("no guard layer means UV can be added on top of the basemap",
                WeatherWarningActivity.uvBelowLayerId(Arrays.asList(UV)));
        assertNull(WeatherWarningActivity.uvBelowLayerId(null));

        assertTrue(WeatherWarningActivity.uvOrderIsCorrect(
                Arrays.asList(UV,FILL,EXCLUDED_FILL,RADAR,OUTLINE)));
        assertTrue(WeatherWarningActivity.uvOrderIsCorrect(Arrays.asList(UV,RADAR)));
        assertTrue(WeatherWarningActivity.uvOrderIsCorrect(Arrays.asList(UV)));
        // A UV raster above the fills would hide warning colours.
        assertFalse(WeatherWarningActivity.uvOrderIsCorrect(Arrays.asList(FILL,UV,RADAR)));
        assertFalse(WeatherWarningActivity.uvOrderIsCorrect(Arrays.asList(OUTLINE,UV)));
        assertFalse(WeatherWarningActivity.uvOrderIsCorrect(Arrays.asList(FILL,RADAR)));
        assertFalse(WeatherWarningActivity.uvOrderIsCorrect(null));
    }

    @Test
    public void radarOrderingContractSurvivesTheUvLayer(){
        assertTrue("radar must still be above fills and below outlines with UV installed",
                WeatherWarningActivity.radarOrderIsCorrect(
                        Arrays.asList(UV,FILL,EXCLUDED_FILL,RADAR,OUTLINE)));
        assertFalse("radar below an outline is still rejected with UV installed",
                WeatherWarningActivity.radarOrderIsCorrect(
                        Arrays.asList(UV,FILL,OUTLINE,RADAR)));
        assertEquals("UV must not change the radar anchor",
                WeatherWarningActivity.radarBelowLayerId(Arrays.asList(UV,FILL,OUTLINE)),
                WeatherWarningActivity.radarBelowLayerId(Arrays.asList(FILL,OUTLINE)));
    }

    @Test
    public void cachedUviPngIsRenderedWithoutPerFrameNetwork() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        String render = between(activity,"private void applyVectorUvLayer(){","private void refreshVectorUvLayer(){");
        for (String token : new String[]{
                "WeatherLayer.Layers.UVI_CLOUDS_0",
                "BitmapFactory.decodeFile(uvLayer.getFullChacheFilepath(context))",
                "new ImageSource(UVI_SOURCE_ID,uvLatLngQuad(WeatherLayer.WarnMapGeo),bitmap)",
                "source.setImage(bitmap)",
                "repositionVectorUvLayer(style)"
        }) {
            assertTrue("missing UV render wiring: "+token,render.contains(token));
        }
        for (String token : new String[]{
                "new RasterLayer(UVI_LAYER_ID,UVI_SOURCE_ID)",
                "uvOrderIsCorrect(ids)",
                "style.addLayerBelow(raster,belowId)",
                "style.removeLayer(UVI_LAYER_ID)",
                "removeManagedStyleSource(style,UVI_SOURCE_ID)"
        }) {
            assertTrue("missing UV ordering/removal wiring: "+token,activity.contains(token));
        }
        for (String forbidden : new String[]{
                "Http.","new URL","getLayerInputStream","getGeoServerURL",
                "getXPixel","getYPixel","getPlotPoint"
        }) {
            assertFalse("cached UV render must not use "+forbidden,render.contains(forbidden));
        }
    }

    @Test
    public void refreshUsesTheExistingWeatherLayerUpdatePath() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        String refresh = between(activity,"private void refreshVectorUvLayer(){","private void removeVectorUvLayer(){");
        for (String token : new String[]{
                "uvLayer.isOutdated(context)",
                "new APIReaders.getLayerImages(context,layersToFetch)",
                "scheduledExecutorService.execute(reader)",
                "public void onFinished(boolean success)",
                "applyVectorUvLayer()"
        }) {
            assertTrue("missing UV refresh wiring: "+token,refresh.contains(token));
        }
        String styleReady = between(activity,"public void onStyleLoaded(Style style){","map.moveCamera(");
        assertTrue("style-ready must install UV before radar",
                styleReady.indexOf("refreshVectorUvLayer()") < styleReady.indexOf("applyVectorRadarFrame(nextRainSlide)"));
        String selector = between(activity,"private void applyLayerSelection(){","private boolean radarEnabled(){");
        assertTrue("selector must refresh UV when enabled",selector.contains("refreshVectorUvLayer()"));
        assertTrue("selector must remove UV when disabled",selector.contains("removeVectorUvLayer()"));
    }

    @Test
    public void mainActivityOpensWarningMapWithUvPreselected() throws Exception {
        String menu = read("app/src/main/res/menu/main_activity.xml");
        assertTrue(menu.contains("@+id/menu_uv_map"));
        assertTrue(menu.contains("@string/menu_uv_map"));
        assertTrue("the legacy maps entry must remain",menu.contains("@+id/menu_maps"));

        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/MainActivity.java");
        assertTrue(activity.contains("new Intent(this, WeatherWarningActivity.class)"));
        assertTrue(activity.contains("i.putExtra(WeatherWarningActivity.EXTRA_SHOW_UV_LAYER,true)"));
        assertTrue(activity.contains("R.id.menu_uv_map"));
        assertTrue("existing legacy maps path stays intact",
                activity.contains("new Intent(this, WeatherLayersActivity.class)"));
        assertFalse("no implicit PendingIntent may be introduced",
                activity.contains("PendingIntent.getActivity("));

        String warning = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        assertTrue(warning.contains("public final static String EXTRA_SHOW_UV_LAYER=\"show_uv_layer\""));
        assertTrue(warning.contains("launchIntent.getBooleanExtra(EXTRA_SHOW_UV_LAYER,false)"));
        assertTrue(warning.contains("state.putBoolean(SIS_SHOW_UV_LAYER_EXTRA,uvLayerExtraPending)"));
    }

    @Test
    public void uvStringsExistInEnglishAndGerman() throws Exception {
        String english = read("app/src/main/res/values/strings.xml");
        String german = read("app/src/main/res/values-de/strings.xml");
        String[] names = new String[]{"map_layer_uv","menu_uv_map","map_legend_uv_index",
                "map_legend_uv_source","map_legend_uv_scale","map_legend_uv_max"};
        for (String name : names){
            assertTrue("missing English string: "+name,english.contains("<string name=\""+name+"\""));
            assertTrue("missing German string: "+name,german.contains("<string name=\""+name+"\""));
        }
        assertTrue(german.contains("UV-Index-Overlay") && german.contains("UV-Karte"));
        assertTrue("DWD attribution must be visible in the legend",
                english.contains("Deutscher Wetterdienst, CC BY 4.0") &&
                        german.contains("Deutscher Wetterdienst, CC BY 4.0"));
    }

    private static String between(String source,String start,String end){
        int from=source.indexOf(start);
        int to=source.indexOf(end,from);
        assertTrue("missing block "+start+" .. "+end,(from>=0) && (to>from));
        return source.substring(from,to);
    }

    private static String read(String path) throws Exception {
        File file=new File(path);
        if (!file.isFile()){
            // Tests are also run from the primary checkout against a worktree source tree.
            file=new File(path.substring("app/".length()));
        }
        assertTrue("missing source file: "+path,file.isFile());
        return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    }
}

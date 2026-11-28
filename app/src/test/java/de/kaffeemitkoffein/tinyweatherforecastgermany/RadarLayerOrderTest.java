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

import android.content.Context;
import android.preference.PreferenceManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
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
 * TASK-91: the vector radar raster belongs above both warning fills but below both warning
 * outlines. The pure ordering rules are exercised directly (no native map needed), the
 * TASK-89 opacity is read through the real WeatherSettings, and source pins make sure every
 * layer/toggle/style-ready path re-anchors the raster.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RadarLayerOrderTest {

    private static final String FILL="twfg-warning-fill";
    private static final String EXCLUDED_FILL="twfg-excluded-fill";
    private static final String RADAR="twfg-radar-raster";
    private static final String OUTLINE="twfg-warning-outline";
    private static final String EXCLUDED_OUTLINE="twfg-excluded-outline";

    @Test
    public void anchorIsTheLowestInstalledOutline(){
        assertEquals(OUTLINE,WeatherWarningActivity.radarBelowLayerId(
                Arrays.asList(FILL,EXCLUDED_FILL,RADAR,OUTLINE,EXCLUDED_OUTLINE)));
        assertEquals(EXCLUDED_OUTLINE,WeatherWarningActivity.radarBelowLayerId(
                Arrays.asList(FILL,EXCLUDED_FILL,RADAR,EXCLUDED_OUTLINE)));
        assertEquals(OUTLINE,WeatherWarningActivity.radarBelowLayerId(
                Arrays.asList(OUTLINE,EXCLUDED_OUTLINE)));
        // No outline: adding at the top is still above every fill.
        assertNull(WeatherWarningActivity.radarBelowLayerId(
                Arrays.asList(FILL,EXCLUDED_FILL,RADAR)));
        assertNull(WeatherWarningActivity.radarBelowLayerId(Arrays.asList(FILL)));
        assertNull(WeatherWarningActivity.radarBelowLayerId(null));
    }

    @Test
    public void orderCheckAcceptsFillsBelowAndOutlinesAboveOnly(){
        assertTrue(WeatherWarningActivity.radarOrderIsCorrect(
                Arrays.asList(FILL,EXCLUDED_FILL,RADAR,OUTLINE,EXCLUDED_OUTLINE)));
        // Toggled-off layers are absent but must not invalidate the remaining order.
        assertTrue(WeatherWarningActivity.radarOrderIsCorrect(Arrays.asList(FILL,RADAR)));
        assertTrue(WeatherWarningActivity.radarOrderIsCorrect(Arrays.asList(RADAR,EXCLUDED_OUTLINE)));
        assertTrue(WeatherWarningActivity.radarOrderIsCorrect(Arrays.asList(RADAR)));
        // Old order (radar below fills) and outlines buried under the radar are rejected.
        assertFalse(WeatherWarningActivity.radarOrderIsCorrect(
                Arrays.asList(RADAR,FILL,OUTLINE)));
        assertFalse(WeatherWarningActivity.radarOrderIsCorrect(
                Arrays.asList(FILL,OUTLINE,RADAR)));
        assertFalse(WeatherWarningActivity.radarOrderIsCorrect(
                Arrays.asList(OUTLINE,FILL,RADAR,EXCLUDED_OUTLINE)));
        assertFalse(WeatherWarningActivity.radarOrderIsCorrect(Arrays.asList(FILL,OUTLINE)));
        assertFalse(WeatherWarningActivity.radarOrderIsCorrect(null));
    }

    @Test
    public void rasterOpacityFollowsTheRadarSetting(){
        Context context=RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .remove(WeatherSettings.PREF_RADAR_OPACITY).commit();
        assertEquals(0.55f,WeatherSettings.getRadarOpacity(context),0.0001f);
        WeatherSettings.setRadarOpacity(context,80);
        assertEquals(0.8f,WeatherSettings.getRadarOpacity(context),0.0001f);
        WeatherSettings.setRadarOpacity(context,0);
        assertEquals(0f,WeatherSettings.getRadarOpacity(context),0.0001f);
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .remove(WeatherSettings.PREF_RADAR_OPACITY).commit();
    }

    @Test
    public void everyLayerToggleAndRestylePathReanchorsTheRaster() throws Exception {
        String activity=read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");

        String warningLayers=between(activity,"private void applyVectorWarningLayers(){",
                "/** Rebuilds the caches used by");
        assertTrue("fills must be inserted below the raster",
                warningLayers.contains("addWarningFillLayer(style,fill)"));
        assertTrue("excluded fills must be inserted below the raster too",
                warningLayers.contains("addWarningFillLayer(style,excludedFill)"));
        assertTrue("outlines must be anchored above the raster",
                warningLayers.contains("addWarningOutlineLayer(style,outline)"));
        assertTrue("excluded outlines must be anchored above the raster",
                warningLayers.contains("addWarningOutlineLayer(style,excludedOutline)"));
        assertTrue("toggling warning layers must re-anchor the raster",
                warningLayers.contains("repositionVectorRadarLayer(style,WeatherSettings.getRadarOpacity(context))"));

        String radarFrame=between(activity,"private void applyVectorRadarFrame",
                "/** Hides radar in vector mode");
        assertTrue("opacity must come from WeatherSettings",
                radarFrame.contains("float radarOpacity=WeatherSettings.getRadarOpacity(context)"));
        assertTrue("raster must be (re-)anchored on every frame",
                radarFrame.contains("repositionVectorRadarLayer(style,radarOpacity)"));

        String layerSelector=between(activity,"private void applyLayerSelection(){","private boolean radarEnabled(){");
        assertTrue("selector must refresh warning layers",layerSelector.contains("applyVectorWarningLayers()"));
        assertTrue("selector must refresh radar when enabled",layerSelector.contains("applyVectorRadarFrame(nextRainSlide)"));
        assertTrue("selector must remove radar when disabled",layerSelector.contains("removeVectorRadarLayer()"));

        String styleReady=between(activity,"public void onStyleLoaded(Style style){","map.moveCamera(");
        assertTrue("style-ready must install warning layers first",styleReady.contains("applyVectorWarningLayers()"));
        assertTrue("style-ready must install/anchor radar afterwards",styleReady.contains("applyVectorRadarFrame(nextRainSlide)"));

        // The old below-fills placement and the hard-coded 0.8 alpha must be gone.
        assertFalse(activity.contains("style.addLayerBelow(raster,WARNING_FILL_LAYER_ID)"));
        assertFalse(activity.contains("style.addLayerAbove(fill,RADAR_LAYER_ID)"));
        assertFalse(activity.contains("RADAR_RASTER_OPACITY"));
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

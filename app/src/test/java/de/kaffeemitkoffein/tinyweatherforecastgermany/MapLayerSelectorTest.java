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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TASK-65: the five vector map layers are independent, persisted switches. This test checks
 * the real SharedPreferences implementation under Robolectric plus the source-level wiring of
 * the action bar, the popup state and the vector rendering paths (no native map required).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class MapLayerSelectorTest {

    @Test
    public void everyLayerDefaultsToVisible(){
        android.content.Context context = RuntimeEnvironment.getApplication();
        assertTrue(WeatherSettings.showMapLayerWarnings(context));
        assertTrue(WeatherSettings.showMapLayerExcluded(context));
        assertTrue(WeatherSettings.showMapLayerRadar(context));
        assertTrue(WeatherSettings.showMapLayerWind(context));
        assertTrue(WeatherSettings.showMapLayerPin(context));
    }

    @Test
    public void eachLayerPersistsIndependently(){
        android.content.Context context = RuntimeEnvironment.getApplication();
        WeatherSettings.setMapLayerWarnings(context,false);
        WeatherSettings.setMapLayerExcluded(context,true);
        WeatherSettings.setMapLayerRadar(context,false);
        WeatherSettings.setMapLayerWind(context,true);
        WeatherSettings.setMapLayerPin(context,false);
        // Re-read from the shared preferences file, not from activity fields.
        assertFalse(WeatherSettings.showMapLayerWarnings(context));
        assertTrue(WeatherSettings.showMapLayerExcluded(context));
        assertFalse(WeatherSettings.showMapLayerRadar(context));
        assertTrue(WeatherSettings.showMapLayerWind(context));
        assertFalse(WeatherSettings.showMapLayerPin(context));
        // Flip one switch back; the others must not follow it.
        WeatherSettings.setMapLayerRadar(context,true);
        assertTrue(WeatherSettings.showMapLayerRadar(context));
        assertFalse(WeatherSettings.showMapLayerWarnings(context));
        assertFalse(WeatherSettings.showMapLayerPin(context));
    }

    @Test
    public void actionBarHasDedicatedLayerPopupAndKeepsLegacyRainFilter() throws Exception {
        String menu = read("app/src/main/res/menu/weatherwarning_activity.xml");
        assertTrue(menu.contains("@+id/map_layers"));
        assertTrue(menu.contains("@string/map_layers_title"));
        assertTrue(menu.contains("?attr/ic_filter_list"));
        assertTrue("rain filter must remain for the static map",menu.contains("@+id/hide_rain"));
        assertTrue(menu.contains("@string/hide_rain"));
        assertTrue("legend from TASK-64 must stay reachable",menu.contains("@+id/map_legend"));
    }

    @Test
    public void vectorPathsHonourEveryFlagAndRemoveSourcesCleanly() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        for (String token : new String[]{
                "if (showWarningPolygons){",
                "if (showExcludedAreas){",
                "(!showRadarLayer)",
                "(!showWindArcs)",
                "(!showStationPin)",
                "useVectorMap ? showRadarLayer : !hide_rain",
                "removeManagedStyleSource(style,WARNING_SOURCE_ID)",
                "removeManagedStyleSource(style,EXCLUDED_SOURCE_ID)",
                "removeManagedStyleSource(style,RADAR_SOURCE_ID)",
                "style.addLayerAbove(fill,RADAR_LAYER_ID)",
                "WeatherSettings.setMapLayerWarnings(context,isChecked)",
                "WeatherSettings.setMapLayerExcluded(context,isChecked)",
                "WeatherSettings.setMapLayerRadar(context,isChecked)",
                "WeatherSettings.setMapLayerWind(context,isChecked)",
                "WeatherSettings.setMapLayerPin(context,isChecked)"
        }) {
            assertTrue("missing layer wiring: "+token,activity.contains(token));
        }
        // hide_rain belongs to the static map only; vector radar is not driven by it.
        String radarMethod = between(activity,"private void applyVectorRadarFrame","private void removeVectorRadarLayer");
        assertFalse("vector radar must not depend on hide_rain",radarMethod.contains("hide_rain"));
    }

    @Test
    public void popupAndPendingWarningSurviveRotation() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        for (String token : new String[]{
                "SIS_MAP_LAYER_DIALOG_OPEN",
                "state.putBoolean(SIS_MAP_LAYER_DIALOG_OPEN,layerDialog!=null && layerDialog.isShowing())",
                "state.putString(SIS_PENDING_WARNING_IDENTIFIER,pendingWarningIdentifier)",
                "showLayerSelector()",
                "setMultiChoiceItems(labels,checked",
                "restoreLayerState(state)",
                "EXTRA_WARNING_IDENTIFIER"
        }) {
            assertTrue("missing state wiring: "+token,activity.contains(token));
        }
        String sync = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherSyncAdapter.java");
        assertEquals("the audited notification PendingIntent must remain single",
                1,count(sync,"PendingIntent.getActivity("));
        assertTrue(sync.contains("intent.putExtra(EXTRA_WARNING_IDENTIFIER, weatherWarning.identifier)"));
    }

    @Test
    public void legendOnlyDescribesRenderedLayers() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        String legend = between(activity,"private void showMapLegend()","private void addLegendHeader");
        assertTrue(legend.contains("if (!useVectorMap || showWarningPolygons)"));
        assertTrue(legend.contains("if (!useVectorMap || showRadarLayer)"));
        assertTrue(legend.contains("if (!useVectorMap || showExcludedAreas)"));
        assertTrue(legend.contains("if (!useVectorMap || showStationPin)"));
        assertTrue(legend.contains("if (!useVectorMap || showWindArcs)"));
        // The TASK-64 content itself (colours, labels, sizing) is unchanged.
        assertTrue(legend.contains("MAP_LEGEND_WARNING_COLORS[i]"));
        assertTrue(legend.contains("MAP_LEGEND_RADAR_COLORS"));
    }

    @Test
    public void stringsExistInEnglishAndGerman() throws Exception {
        String english = read("app/src/main/res/values/strings.xml");
        String german = read("app/src/main/res/values-de/strings.xml");
        String[] names = new String[]{"map_layers_title","map_layer_warnings","map_layer_excluded",
                "map_layer_radar","map_layer_wind","map_layer_pin","warning_no_geometry"};
        for (String name : names){
            assertTrue("missing English string: "+name,english.contains("<string name=\""+name+"\""));
            assertTrue("missing German string: "+name,german.contains("<string name=\""+name+"\""));
        }
        assertTrue(german.contains("Kartenebenen") && german.contains("Warnflächen"));
    }

    private static int count(String source,String token){
        int result = 0;
        int index = source.indexOf(token);
        while (index>=0){
            result++;
            index = source.indexOf(token,index+token.length());
        }
        return result;
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

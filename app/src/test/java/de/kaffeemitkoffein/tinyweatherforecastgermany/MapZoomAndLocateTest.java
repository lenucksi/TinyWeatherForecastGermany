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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * TASK-67: the MapLibre Native Android 13.8 UiSettings has no zoom buttons, so the warning
 * map supplies its own 48dp controls. These tests check the two layouts, the vector-only
 * wiring and the pure zoom-clamping arithmetic without loading any native map code.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class MapZoomAndLocateTest {

    @Test
    public void bothLayoutsHaveThree48dpLabelledControlsAtBottomLeft(){
        for (String path : new String[]{
                "app/src/main/res/layout/activity_weatherwarning.xml",
                "app/src/main/res/layout-w600dp-land/activity_weatherwarning.xml"}){
            String layout;
            try {
                layout = read(path);
            } catch (Exception e){
                throw new AssertionError("missing layout: "+path,e);
            }
            assertTrue(path+" has no control row",layout.contains("@+id/warningactivity_map_controls"));
            for (String id : new String[]{"warningactivity_map_zoom_in","warningactivity_map_zoom_out",
                    "warningactivity_map_locate"}){
                assertTrue(path+" missing "+id,layout.contains("@+id/"+id));
            }
            assertTrue(path+" zoom-in label missing",layout.contains("@string/a11y_map_zoom_in"));
            assertTrue(path+" zoom-out label missing",layout.contains("@string/a11y_map_zoom_out"));
            assertTrue(path+" locate label missing",layout.contains("@string/a11y_map_locate"));
            assertTrue(path+" plus icon missing",layout.contains("?attr/ic_add"));
            assertTrue(path+" minus icon missing",layout.contains("@drawable/map_minus_24dp"));
            assertTrue(path+" locate icon missing",layout.contains("?attr/ic_gps_fixed"));
            // Right-side vertical stack, anchored above the attribution and the SeekBar.
            int controls = layout.indexOf("@+id/warningactivity_map_controls");
            String row = layout.substring(controls,layout.indexOf("</LinearLayout>",controls));
            // Three targets, each 48dp wide and 48dp high.
            assertEquals(path+" must have three 48dp controls",6,count(row,"\"48dp\""));
            assertTrue(path+" stack must be vertical",row.contains("android:orientation=\"vertical\""));
            assertTrue(path+" stack must be on the right",row.contains("android:layout_alignParentRight=\"true\""));
            assertTrue(path+" stack must sit above the attribution",
                    row.contains("android:layout_above=\"@id/warningactivity_maplibre_attribution\""));
            assertTrue(path+" stack needs a small gap above the attribution",
                    row.contains("android:layout_marginBottom=\"6dp\""));
            assertTrue(path+" - and locate must follow + downwards",
                    2==count(row,"android:layout_marginTop=\"4dp\""));
            // Transparent buttons with a 48dp target but a ~16dp drawn glyph.
            assertEquals(path+" buttons must have no opaque background",
                    3,count(row,"android:background=\"@null\""));
            assertEquals(path+" glyphs must be tinted for theme contrast",
                    3,count(row,"android:tint=\"?attr/colorText\""));
            assertEquals(path+" generous internal padding",
                    3,count(row,"android:padding=\"16dp\""));
            assertTrue(path+" stack order must be +, -, locate",
                    row.indexOf("warningactivity_map_zoom_in")<row.indexOf("warningactivity_map_zoom_out")
                            && row.indexOf("warningactivity_map_zoom_out")<row.indexOf("warningactivity_map_locate"));
            assertTrue(path+" vector container must stay above the SeekBar",
                    layout.contains("android:layout_above=\"@id/warningactivity_rainsliderSeekbarContainer\""));
            assertTrue(path+" attribution must stay bottom-right",
                    layout.contains("@+id/warningactivity_maplibre_attribution")
                            && layout.contains("android:layout_alignParentRight=\"true\""));
        }
    }

    @Test
    public void controlsAreVectorOnlyAndWorkAfterRecreation() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        for (String token : new String[]{
                "setupMapControls();",
                "private void setupMapControls(){",
                "setVectorViewVisibility(R.id.warningactivity_map_controls,useVectorMap ? View.VISIBLE : View.GONE);",
                "CameraUpdateFactory.zoomTo(targetZoom)",
                "mapLibreMap.getMinZoomLevel()",
                "mapLibreMap.getMaxZoomLevel()",
                "CameraUpdateFactory.newLatLngZoom(target,zoom)",
                "WeatherLocationManager.hasLocationPermission(context)",
                "WeatherLocationManager.getLastKnownLocation(context)",
                "WeatherSettings.getSetStationLocation(context)",
                "R.string.map_locate_fallback",
                "R.string.map_locate_device",
                "TWFG_MAP67"
        }){
            assertTrue("missing TASK-67 wiring: "+token,activity.contains(token));
        }
        // TASK-64 legend and TASK-65 layer selector must remain reachable.
        String menu = read("app/src/main/res/menu/weatherwarning_activity.xml");
        assertTrue("legend must remain reachable",menu.contains("@+id/map_legend"));
        assertTrue("layer selector must remain reachable",menu.contains("@+id/map_layers"));
    }

    @Test
    public void zoomClampRespectsNativeLimitsAndToleratesReversedBounds(){
        assertEquals(7d,WeatherWarningActivity.clampVectorZoom(8d,2d,7d),0.0001d);
        assertEquals(2d,WeatherWarningActivity.clampVectorZoom(1d,2d,7d),0.0001d);
        assertEquals(6d,WeatherWarningActivity.clampVectorZoom(6d,2d,7d),0.0001d);
        assertEquals(0d,WeatherWarningActivity.clampVectorZoom(-1d,0d,22d),0.0001d);
        assertEquals(22d,WeatherWarningActivity.clampVectorZoom(99d,0d,22d),0.0001d);
        assertEquals(6d,WeatherWarningActivity.clampVectorZoom(6d,7d,2d),0.0001d);
    }

    @Test
    public void stringsExistInBothLocales() throws Exception {
        String english = read("app/src/main/res/values/strings.xml");
        String german = read("app/src/main/res/values-de/strings.xml");
        for (String name : new String[]{"a11y_map_zoom_in","a11y_map_zoom_out","a11y_map_locate",
                "map_locate_device","map_locate_fallback"}){
            assertTrue("missing English string: "+name,english.contains("<string name=\""+name+"\""));
            assertTrue("missing German string: "+name,german.contains("<string name=\""+name+"\""));
        }
        assertTrue(german.contains("Karte vergrößern") && german.contains("Zum eigenen Standort zoomen"));
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

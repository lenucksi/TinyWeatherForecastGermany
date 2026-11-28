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
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TASK-70: the radar timeline gets play/pause, stop/reset and single-frame steps in
 * both warning layouts. These tests check the layout contract, the wiring in the
 * activity and the pure frame-index arithmetic without touching the native map.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RadarTimelineTest {

    private static String read(String path) throws Exception {
        // Tests run from the primary checkout against the worktree source tree:
        // Gradle's test working directory is the app module, not the repo root.
        java.io.File file = new java.io.File(path);
        if (!file.isFile() && path.startsWith("app/")){
            file = new java.io.File(path.substring("app/".length()));
        }
        if (!file.isFile() && !path.startsWith("app/")){
            file = new java.io.File("../"+path);
        }
        assertTrue("missing source file: "+path,file.isFile());
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static int count(String haystack, String needle){
        int total = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle,from))>=0){
            total++;
            from += needle.length();
        }
        return total;
    }

    @Test
    public void bothLayoutsHaveThemedSliderAndFour48dpTransportTargets() throws Exception {
        for (String path : new String[]{
                "app/src/main/res/layout/activity_weatherwarning.xml",
                "app/src/main/res/layout-w600dp-land/activity_weatherwarning.xml"}){
            String layout = read(path);
            assertTrue(path+" has no timeline container",
                    layout.contains("@+id/warningactivity_rainsliderSeekbarContainer"));
            assertTrue(path+" has no transport row",
                    layout.contains("@+id/warningactivity_rainslidetransport"));
            assertTrue(path+" has no frame caption",
                    layout.contains("@+id/warningactivity_rainslideframe"));
            assertTrue(path+" frame caption is not localized",layout.contains("@string/radar_frame")
                    || layout.contains("android:text=\"\""));
            assertTrue(path+" time label is not theme-aware",
                    layout.contains("android:textColor=\"?attr/colorText\""));
            for (String id : new String[]{"warningactivity_radar_play","warningactivity_radar_stop",
                    "warningactivity_radar_step_back","warningactivity_radar_step_forward"}){
                assertTrue(path+" missing "+id,layout.contains("@+id/"+id));
            }
            // The play button starts in the PAUSE state (the timer starts on load),
            // so its initial contentDescription is the pause label; the play label is
            // swapped in by refreshRadarTransportControls() when playback is stopped.
            for (String label : new String[]{"a11y_radar_pause","a11y_radar_stop",
                    "a11y_radar_step_back","a11y_radar_step_forward","a11y_radar_timeline"}){
                assertTrue(path+" missing label "+label,layout.contains("@string/"+label));
            }
            assertTrue(path+" keeps the slim round thumb",
                    layout.contains("@drawable/radar_seek_thumb"));
            assertTrue(path+" keeps the slim themed track",
                    layout.contains("@drawable/radar_seekbar_progress"));
            assertFalse(path+" still uses the old square thumb",layout.contains("@drawable/seekthumb"));
            assertFalse(path+" still hard-codes the blue thumb colour",
                    layout.contains("@color/seekbar_thumb"));
            int rowStart = layout.indexOf("@+id/warningactivity_rainslidetransport");
            String row = layout.substring(rowStart,layout.indexOf("</LinearLayout>",rowStart));
            // Four square 48dp touch targets, transparent background, tinted glyphs.
            // The row itself is 48dp high, hence five height declarations.
            assertEquals(path+" four 48dp buttons",4,count(row,"android:layout_width=\"48dp\""));
            assertEquals(path+" 48dp row and buttons",5,count(row,"android:layout_height=\"48dp\""));
            assertEquals(path+" transparent button backgrounds",4,count(row,"android:background=\"@null\""));
            assertEquals(path+" themed button glyphs",4,count(row,"android:tint=\"?attr/colorText\""));
            assertTrue(path+" slider must share the row",row.contains("android:layout_weight=\"1\""));
            assertTrue(path+" transport must be horizontal",
                    row.contains("android:orientation=\"horizontal\""));
            assertTrue(path+" play must precede stop",
                    row.indexOf("warningactivity_radar_play")<row.indexOf("warningactivity_radar_stop"));
            assertTrue(path+" step back must precede step forward",
                    row.indexOf("warningactivity_radar_step_back")<row.indexOf("warningactivity_radar_step_forward"));
            assertTrue(path+" timeline must stay above the warning list",
                    layout.contains("android:layout_above=\"@id/warningactivity_rainsliderSeekbarContainer\""));
        }
    }

    @Test
    public void activityWiresTransportToExistingFramePaths() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        for (String token : new String[]{
                "setupRainTimelineControls();",
                "private void setupRainTimelineControls(){",
                "private void toggleRainSlides(){",
                "private void stopAndResetRainSlides(){",
                "private void stepRainSlide(final int delta){",
                "private void jumpToRainSlide(final int frame){",
                "private void refreshRadarTransportControls(){",
                "private void updateRainFrameText(){",
                "static int normalizeRainFrame(int frame){",
                "applyVectorRadarFrame(count);",
                "drawRadarSlide(target);",
                "updateRainSeekBar(target);",
                "R.string.a11y_radar_play",
                "R.string.a11y_radar_pause",
                "private volatile boolean rainSlidesPaused = false;",
                "if (rainSlidesPaused){",
                "RAINSLIDEDELAY=750",
                "TWFG_MAP70"
        }){
            assertTrue("missing TASK-70 wiring: "+token,activity.contains(token));
        }
        // Attribution: native logo + info button replaced by the app's own TextView.
        assertTrue("logo must be disabled",activity.contains("uiSettings.setLogoEnabled(false)"));
        assertTrue("native attribution button must be disabled",
                activity.contains("uiSettings.setAttributionEnabled(false)"));
    }

    @Test
    public void frameIndexWrapsInBothDirections(){
        int size = APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE;
        assertEquals(24,size);
        assertEquals(0,WeatherWarningActivity.normalizeRainFrame(0));
        assertEquals(size-1,WeatherWarningActivity.normalizeRainFrame(size-1));
        assertEquals(0,WeatherWarningActivity.normalizeRainFrame(size));
        assertEquals(size-1,WeatherWarningActivity.normalizeRainFrame(-1));
        assertEquals(1,WeatherWarningActivity.normalizeRainFrame(size+1));
        assertEquals(7,WeatherWarningActivity.normalizeRainFrame(7));
    }

    @Test
    public void creditsAndTranslationsSurviveTheFix() throws Exception {
        String english = read("app/src/main/res/values/strings.xml");
        String german = read("app/src/main/res/values-de/strings.xml");
        for (String name : new String[]{"radar_frame","a11y_radar_timeline","a11y_radar_play",
                "a11y_radar_pause","a11y_radar_stop","a11y_radar_step_back","a11y_radar_step_forward"}){
            assertTrue("missing English string: "+name,english.contains("<string name=\""+name+"\""));
            assertTrue("missing German string: "+name,german.contains("<string name=\""+name+"\""));
        }
        String attribution = english.substring(english.indexOf("<string name=\"map_attribution_vector\""));
        attribution = attribution.substring(0,attribution.indexOf("</string>"));
        for (String credit : new String[]{"OpenFreeMap","OpenMapTiles","OpenStreetMap contributors"}){
            assertTrue("attribution lost "+credit,attribution.contains(credit));
        }
        String notice = read("NOTICE");
        assertTrue("MapLibre BSD notice must stay",notice.contains("MapLibre contributors, BSD-2-Clause"));
    }
}

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

import static org.junit.Assert.assertArrayEquals;
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
    public void bothLayoutsHaveThemedSliderAndSix48dpTransportTargets() throws Exception {
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
                    "warningactivity_radar_step_back","warningactivity_radar_step_forward",
                    "warningactivity_radar_jump_to_now","warningactivity_radar_speed"}){
                assertTrue(path+" missing "+id,layout.contains("@+id/"+id));
            }
            // The play button starts in the PAUSE state (the timer starts on load),
            // so its initial contentDescription is the pause label; the play label is
            // swapped in by refreshRadarTransportControls() when playback is stopped.
            for (String label : new String[]{"a11y_radar_pause","a11y_radar_stop",
                    "a11y_radar_step_back","a11y_radar_step_forward","a11y_radar_jump_to_now",
                    "a11y_radar_speed","a11y_radar_timeline"}){
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
            // Six square 48dp touch targets (play, stop, back, forward, now, speed);
            // transparent background, tinted glyphs. The row itself is 48dp high, hence seven.
            assertEquals(path+" six 48dp buttons",6,count(row,"android:layout_width=\"48dp\""));
            assertEquals(path+" 48dp row and buttons",7,count(row,"android:layout_height=\"48dp\""));
            assertEquals(path+" transparent button backgrounds",6,count(row,"android:background=\"@null\""));
            assertEquals(path+" themed button glyphs",6,count(row,"android:tint=\"?attr/colorText\""));
            assertTrue(path+" glyphs must be visually compact",
                    count(row,"android:padding=\"16dp\"")==6);
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
                "drawRadarSlide(frame);",
                "updateRainSeekBar(frame);",
                "private void showAvailableFrame(final int frame,",
                "FrameLoadState.LOADING",
                "FrameLoadState.UNAVAILABLE",
                "RADAR_PLAYBACK_SPEEDS",
                "radarPlaybackSpeed",
                "Math.round(RAINSLIDEDELAY/radarPlaybackSpeed)",
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
    public void frameIndexWrapsOnlyForLegacySteppingNotForTheAbsoluteTimeline(){
        // The legacy legacy constant still pins the old on-disk fixture set.
        int size = APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE;
        assertEquals(24,size);
        assertEquals(0,WeatherWarningActivity.normalizeRainFrame(0));
        assertEquals(size-1,WeatherWarningActivity.normalizeRainFrame(size-1));
        assertEquals(0,WeatherWarningActivity.normalizeRainFrame(size));
        assertEquals(size-1,WeatherWarningActivity.normalizeRainFrame(-1));
        // TASK-90: the frame model is dynamic; any count works and the SeekBar clamps.
        for (int count : new int[]{2,7,24,96}){
            assertEquals(count-1,WeatherWarningActivity.normalizeRainFrame(-1,count));
            assertEquals((count+5)%count,WeatherWarningActivity.normalizeRainFrame(count+5,count));
        }
        RadarCapabilities.Window window = new RadarCapabilities.Window(
                1795672800000L, 1795672800000L + 96L * 5L * 60L * 1000L,
                5L * 60L * 1000L, 1795672800000L + 48L * 5L * 60L * 1000L, 0L, true);
        RadarTimeline timeline = RadarTimeline.create(window, WeatherSettings.Radar.WINDOW_FULL);
        assertEquals(97,timeline.frameCount);
        // Scrubbing past the end clamps to the last frame instead of wrapping to the first.
        assertEquals(timeline.timeForIndex(timeline.frameCount-1),timeline.timeForProgress(100,100));
        assertEquals(timeline.timeForIndex(0),timeline.timeForProgress(0,100));
        long last = timeline.timeForIndex(timeline.frameCount-1);
        assertEquals(timeline.progressForIndex(timeline.frameCount-1,100),
                timeline.progressForTime(last+10_000L,100));
        // Endpoint-exact SeekBar mapping: 312 frames end on thumb 100, not one slice short.
        RadarCapabilities.Window exact = new RadarCapabilities.Window(
                1795672800000L, 1795672800000L + 311L * 5L * 60L * 1000L,
                5L * 60L * 1000L, 1795672800000L + 156L * 5L * 60L * 1000L, 0L, true);
        RadarTimeline exactTimeline = RadarTimeline.create(exact, WeatherSettings.Radar.WINDOW_FULL);
        assertEquals(312,exactTimeline.frameCount);
        assertEquals(100,exactTimeline.progressForIndexExact(311,100));
        assertEquals(311,exactTimeline.indexForProgressExact(100,100));
        assertEquals(0,exactTimeline.progressForIndexExact(0,100));
        assertEquals(exactTimeline.timeForIndex(311),
                exactTimeline.timeForProgress(100,100));
    }

    @Test
    public void playbackSpeedCycleScalesThe750msFrameTimer(){
        assertArrayEquals(new float[]{0.5f,1.0f,1.5f,2.0f,3.0f},
                WeatherWarningActivity.RADAR_PLAYBACK_SPEEDS,0f);
        assertEquals("0.5x",WeatherWarningActivity.radarSpeedLabel(0.5f));
        assertEquals("1.0x",WeatherWarningActivity.radarSpeedLabel(1.0f));
        assertEquals("3.0x",WeatherWarningActivity.radarSpeedLabel(3.0f));
        assertEquals(1500L,Math.round(WeatherWarningActivity.RAINSLIDEDELAY/0.5f));
        assertEquals(750L,Math.round(WeatherWarningActivity.RAINSLIDEDELAY/1.0f));
        assertEquals(250L,Math.round(WeatherWarningActivity.RAINSLIDEDELAY/3.0f));
    }

    @Test
    public void loadingKeepsTheLastGoodCaptionAndOnlyFailuresShowTheBanner() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        int start = activity.indexOf("private void requestRadarFrame(final int index");
        int end = activity.indexOf("private void showAvailableFrame",start);
        assertTrue("requestRadarFrame block not found",start>=0 && end>start);
        String request = activity.substring(start,end);
        assertTrue(request.contains("frameState = FrameLoadState.LOADING;"));
        assertFalse("a lazy load must not repaint the caption with an unavailable state",
                request.contains("updateSlideTimeText(target)"));
        int captionStart = activity.indexOf("public void updateSlideTimeText(final int frame){");
        int captionEnd = activity.indexOf("private int resolveThemeColor",captionStart);
        String caption = activity.substring(captionStart,captionEnd);
        assertTrue(caption.contains("frameState==FrameLoadState.UNAVAILABLE"));
        assertTrue(caption.contains("displayedRainFrame"));
        // 8-frame cancellable prefetch and bounded bitmap LRU.
        assertTrue(activity.contains("frameLoader.prefetchAfter(target,radarTimeline)"));
    }

    @Test
    public void creditsAndTranslationsSurviveTheFix() throws Exception {
        String english = read("app/src/main/res/values/strings.xml");
        String german = read("app/src/main/res/values-de/strings.xml");
        for (String name : new String[]{"radar_frame","a11y_radar_timeline","a11y_radar_play",
                "a11y_radar_pause","a11y_radar_stop","a11y_radar_step_back","a11y_radar_step_forward",
                "a11y_radar_jump_to_now","a11y_radar_speed","radar_speed_changed"}){
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

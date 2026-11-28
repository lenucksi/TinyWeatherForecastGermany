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

import android.content.Context;

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
import static org.junit.Assert.assertTrue;

/**
 * TASK-87: the radar caption, timer wrap, seekbar range and no-data state must follow the
 * frames that are actually cached, not the download target of 24. The JVM tests exercise
 * the pure index arithmetic and the real cache-count helper; source pins keep the UI
 * wiring (caption on every frame change, disabled controls, layer order) in place.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RadarFrameCounterTest {

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()){
            file = new File(path.substring("app/".length()));
        }
        assertTrue("missing source file: "+path,file.isFile());
        return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end){
        int from = source.indexOf(start);
        int to = source.indexOf(end,from);
        assertTrue("missing block "+start+" .. "+end,(from>=0) && (to>from));
        return source.substring(from,to);
    }

    @Test
    public void frameAndSeekBarArithmeticFollowTheCacheSize(){
        // A partial set must wrap at its own length, never at 24.
        assertEquals(0,WeatherWarningActivity.normalizeRainFrame(0,3));
        assertEquals(2,WeatherWarningActivity.normalizeRainFrame(2,3));
        assertEquals(0,WeatherWarningActivity.normalizeRainFrame(3,3));
        assertEquals(2,WeatherWarningActivity.normalizeRainFrame(-1,3));
        assertEquals(1,WeatherWarningActivity.normalizeRainFrame(4,3));
        assertEquals(0,WeatherWarningActivity.normalizeRainFrame(-1,1));
        // The first and last slices must map back to frame 0 and N-1 respectively.
        for (int count : new int[]{2,3,7,24}){
            assertEquals(0,WeatherWarningActivity.frameForSeekBarProgress(0,count));
            assertEquals(count-1,WeatherWarningActivity.frameForSeekBarProgress(100,count));
            for (int frame=0; frame<count; frame++){
                int progress = WeatherWarningActivity.seekBarProgressForFrame(frame,count);
                assertTrue("progress out of range",(progress>=0) && (progress<=100));
                assertEquals("round trip frame="+frame+" count="+count,
                        frame,WeatherWarningActivity.frameForSeekBarProgress(progress,count));
            }
        }
        // No frame exists when the cache is empty, whatever the slider says.
        assertEquals(0,WeatherWarningActivity.normalizeRainFrame(5,0));
        assertEquals(0,WeatherWarningActivity.frameForSeekBarProgress(100,0));
        assertEquals(0,WeatherWarningActivity.seekBarProgressForFrame(0,0));
    }

    @Test
    public void usableCountFollowsContiguousValidCacheFiles() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        byte[] valid = new byte[5001];
        Arrays.fill(valid,(byte) 0x42);
        byte[] invalid = new byte[100];
        try {
            assertEquals(0,WeatherWarningActivity.usableRadarFrameCount(context));
            write(context,0,valid);
            write(context,1,valid);
            write(context,2,valid);
            assertEquals(3,WeatherWarningActivity.usableRadarFrameCount(context));
            // A gap stops the usable prefix; later frames must not be advertised.
            write(context,3,invalid);
            write(context,4,valid);
            assertEquals(3,WeatherWarningActivity.usableRadarFrameCount(context));
            assertTrue(APIReaders.RadarMNSetGeoserverRunnable.radarCacheFileValid(context,4));
            assertFalse(APIReaders.RadarMNSetGeoserverRunnable.fullRadarDataSet(context));
        } finally {
            for (int i=0; i<APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE; i++){
                APIReaders.RadarMNSetGeoserverRunnable.deleteCacheFile(context,i);
            }
        }
        assertEquals(0,WeatherWarningActivity.usableRadarFrameCount(context));
    }

    private static void write(Context context, int index, byte[] data) throws Exception {
        File file = APIReaders.RadarMNSetGeoserverRunnable.getRadarMNFile(context,index);
        Files.write(file.toPath(),data);
        assertTrue(file.isFile());
    }

    @Test
    public void captionAndTimerAreDrivenByTheDrawnFrameAndRealCount() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        String draw = between(activity,"private void drawRadarSlide(final int count){","private void drawMapBitmap()");
        assertTrue(draw.contains("updateRainFrameText(count)"));
        assertTrue(draw.contains("updateSlideTimeText(count)"));
        String vector = between(activity,"private void applyVectorRadarFrame(final int count){","private void removeVectorRadarLayer()");
        assertTrue(vector.contains("updateRainFrameText(count)"));
        assertTrue(vector.contains("updateSlideTimeText(count)"));
        String caption = between(activity,"private void updateRainFrameText(final int frame){","private void refreshRadarTransportControls(){");
        assertTrue(caption.contains("normalizeRainFrame(frame,usableRainFrames)"));
        assertTrue(caption.contains("getString(R.string.radar_frame,index+1,usableRainFrames)"));
        assertFalse("denominator must not be a hard-coded 24",caption.contains("DATASET_SIZE"));
        String timer = between(activity,"private final Runnable rainRadarRunnable","private synchronized void startRainRadar");
        assertTrue(timer.contains("nextRainSlide<usableRainFrames"));
        String start = between(activity,"private synchronized void startRainRadar(){",
                "APIReaders.RadarMNSetGeoserverRunnable radarMNSetGeoserverRunnable;");
        assertTrue(start.contains("if (usableRainFrames<=1)"));
        String seek = between(activity,"private final static int SEEKBAR_MAX","private void updateRainSeekBar");
        assertTrue(seek.contains("frameCount<=0"));
        String listener = between(activity,"public void onProgressChanged","public void onStartTrackingTouch");
        assertTrue(listener.contains("usableRainFrames>1"));
        assertTrue(listener.contains("frameForSeekBarProgress(i,usableRainFrames)"));
        assertTrue(listener.contains("updateRainFrameText(nextRainSlide)"));
        assertTrue(listener.contains("updateSlideTimeText(nextRainSlide)"));
    }

    @Test
    public void noUsableFramesDisablesEveryMovingPart() throws Exception {
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        String refresh = between(activity,"private void refreshRadarTransportControls(){","private int getPrecipitationAtPinPoint");
        assertTrue(refresh.contains("boolean canAnimate = usableRainFrames>1"));
        for (String button : new String[]{"radarPlayButton","radarStopButton","radarStepBackButton",
                "radarStepForwardButton","rainsliderSeekbar"}){
            assertTrue(button+" must be disabled without frames",refresh.contains(button+".setEnabled(canAnimate)"));
        }
        assertTrue(refresh.contains("rainSlideTransportRow.setVisibility(motionVisibility)"));
        assertTrue(refresh.contains("updateSlideTimeText(canAnimate ? nextRainSlide : 0)"));
        assertTrue(refresh.contains("rainsliderSeekbarContainer.setVisibility(View.VISIBLE)"));
        String start = between(activity,"private synchronized void startRainRadar(){",
                "APIReaders.RadarMNSetGeoserverRunnable radarMNSetGeoserverRunnable;");
        assertTrue(start.contains("usableRainFrames<=1"));
        String step = between(activity,"private void stepRainSlide(final int delta){","private void jumpToRainSlide");
        assertTrue(step.contains("usableRainFrames<=1"));
        String toggle = between(activity,"private void toggleRainSlides(){","private void stopAndResetRainSlides");
        assertTrue(toggle.contains("usableRainFrames<=1"));
        String jump = between(activity,"private void jumpToRainSlide(final int frame){","static int normalizeRainFrame(int frame){");
        assertTrue(jump.contains("usableRainFrames<=1"));
    }

    @Test
    public void bothLayoutsUseOneCompactCaptionRowAndKeepRadarBelowWarnings() throws Exception {
        for (String path : new String[]{
                "app/src/main/res/layout/activity_weatherwarning.xml",
                "app/src/main/res/layout-w600dp-land/activity_weatherwarning.xml"}){
            String layout = read(path);
            assertTrue(path+" has no caption row",layout.contains("@+id/warningactivity_rainslidecaption"));
            assertTrue(path+" has no timeline time label",layout.contains("@+id/warningactivity_rainslidetimeline_time"));
            assertTrue(path+" has no frame counter",layout.contains("@+id/warningactivity_rainslideframe"));
            assertTrue(path+" has no colour scale",layout.contains("@+id/warningactivity_rainsliderColors"));
            int row = layout.indexOf("@+id/warningactivity_rainslidecaption");
            int transport = layout.indexOf("@+id/warningactivity_rainslidetransport");
            assertTrue(path+" caption row must precede transport",(row>=0) && (transport>row));
            String caption = layout.substring(row,transport);
            assertTrue(path+" colour scale must sit in the caption row",
                    caption.contains("android:layout_weight=\"1\""));
            assertTrue(path+" transport must sit below the caption row",
                    layout.contains("android:layout_below=\"@id/warningactivity_rainslidecaption\""));
            assertFalse(path+" still has the old empty colour strip row",
                    caption.contains("android:layout_below=\"@id/warningactivity_rainslideframe\""));
        }
        String activity = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherWarningActivity.java");
        // TASK-86(g): keep radar below the warning fills; no opacity change without a visual comparison.
        assertTrue(activity.contains("style.addLayerBelow(raster,WARNING_FILL_LAYER_ID)"));
        assertTrue(activity.contains("RADAR_RASTER_OPACITY=0.8f"));
        String english = read("app/src/main/res/values/strings.xml");
        String german = read("app/src/main/res/values-de/strings.xml");
        assertTrue(english.contains("<string name=\"radar_timeline_unavailable\""));
        assertTrue(german.contains("<string name=\"radar_timeline_unavailable\""));
    }
}

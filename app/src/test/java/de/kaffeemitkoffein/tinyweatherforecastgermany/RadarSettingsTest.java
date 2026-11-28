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
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TASK-89: radar settings are plain SharedPreferences. These tests pin the defaults, the
 * round-trip through WeatherSettings, the pure UTC timestamp helper and the preferences
 * screen contract (including the English/German resources).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RadarSettingsTest {

    private static String read(String path) throws Exception {
        java.io.File file = new java.io.File(path);
        if (!file.isFile() && path.startsWith("app/")){
            file = new java.io.File(path.substring("app/".length()));
        }
        if (!file.isFile() && !path.startsWith("app/")){
            file = new java.io.File("app/"+path);
        }
        assertTrue("missing source file: "+path,file.isFile());
        return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    }

    private static int count(String haystack,String needle){
        int total = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle,from))>=0){
            total++;
            from += needle.length();
        }
        return total;
    }

    private static SharedPreferences clearRadarPreferences(Context context){
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor editor = preferences.edit();
        editor.remove(WeatherSettings.PREF_RADAR_USE_MOBILE_DATA);
        editor.remove(WeatherSettings.PREF_RADAR_TIME_WINDOW);
        editor.remove(WeatherSettings.PREF_RADAR_PREFETCH_PROFILE);
        editor.remove(WeatherSettings.PREF_RADAR_PLAYBACK_START);
        editor.remove(WeatherSettings.PREF_RADAR_OPACITY);
        editor.remove(WeatherSettings.PREF_MAP_LAYER_RADAR);
        editor.commit();
        return preferences;
    }

    @Test
    public void defaultsAreMobileOffTwentyFourHoursBalancedNowAndVisible(){
        Context context = RuntimeEnvironment.getApplication();
        clearRadarPreferences(context);

        assertFalse(WeatherSettings.useRadarOnMobileData(context));
        assertEquals(WeatherSettings.Radar.WINDOW_24H,WeatherSettings.getRadarTimeWindow(context));
        assertEquals(WeatherSettings.Radar.PREFETCH_BALANCED,WeatherSettings.getRadarPrefetchProfile(context));
        assertEquals(WeatherSettings.Radar.PLAYBACK_NOW,WeatherSettings.getRadarPlaybackStart(context));
        assertEquals(55,WeatherSettings.getRadarOpacityPercent(context));
        assertEquals(0.55f,WeatherSettings.getRadarOpacity(context),0.0001f);
        assertTrue(WeatherSettings.showMapLayerRadar(context));
    }

    @Test
    public void everySettingRoundTripsThroughWeatherSettings(){
        Context context = RuntimeEnvironment.getApplication();
        clearRadarPreferences(context);

        WeatherSettings.setRadarUseMobileData(context,true);
        WeatherSettings.setRadarTimeWindow(context,WeatherSettings.Radar.WINDOW_FULL);
        WeatherSettings.setRadarPrefetchProfile(context,WeatherSettings.Radar.PREFETCH_MINIMAL);
        WeatherSettings.setRadarPlaybackStart(context,WeatherSettings.Radar.PLAYBACK_NEXT_MIDNIGHT);
        WeatherSettings.setRadarOpacity(context,80);
        WeatherSettings.setMapLayerRadar(context,false);

        assertTrue(WeatherSettings.useRadarOnMobileData(context));
        assertEquals(WeatherSettings.Radar.WINDOW_FULL,WeatherSettings.getRadarTimeWindow(context));
        assertEquals(WeatherSettings.Radar.PREFETCH_MINIMAL,WeatherSettings.getRadarPrefetchProfile(context));
        assertEquals(WeatherSettings.Radar.PLAYBACK_NEXT_MIDNIGHT,WeatherSettings.getRadarPlaybackStart(context));
        assertEquals(80,WeatherSettings.getRadarOpacityPercent(context));
        assertEquals(0.8f,WeatherSettings.getRadarOpacity(context),0.0001f);
        assertFalse(WeatherSettings.showMapLayerRadar(context));

        WeatherSettings.setRadarUseMobileData(context,false);
        WeatherSettings.setRadarTimeWindow(context,WeatherSettings.Radar.WINDOW_2H);
        WeatherSettings.setRadarPrefetchProfile(context,WeatherSettings.Radar.PREFETCH_FULL);
        WeatherSettings.setRadarPlaybackStart(context,WeatherSettings.Radar.PLAYBACK_OLDEST);
        WeatherSettings.setRadarOpacity(context,10);

        assertFalse(WeatherSettings.useRadarOnMobileData(context));
        assertEquals(WeatherSettings.Radar.WINDOW_2H,WeatherSettings.getRadarTimeWindow(context));
        assertEquals(WeatherSettings.Radar.PREFETCH_FULL,WeatherSettings.getRadarPrefetchProfile(context));
        assertEquals(WeatherSettings.Radar.PLAYBACK_OLDEST,WeatherSettings.getRadarPlaybackStart(context));
        assertEquals(10,WeatherSettings.getRadarOpacityPercent(context));
    }

    @Test
    public void corruptedValuesFallBackToDefaults(){
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = clearRadarPreferences(context);
        preferences.edit()
                .putString(WeatherSettings.PREF_RADAR_TIME_WINDOW,"garbage")
                .putString(WeatherSettings.PREF_RADAR_PREFETCH_PROFILE,"")
                .putString(WeatherSettings.PREF_RADAR_PLAYBACK_START,"tomorrow")
                .putString(WeatherSettings.PREF_RADAR_OPACITY,"not-a-number")
                .commit();

        assertEquals(WeatherSettings.Radar.WINDOW_24H,WeatherSettings.getRadarTimeWindow(context));
        assertEquals(WeatherSettings.Radar.PREFETCH_BALANCED,WeatherSettings.getRadarPrefetchProfile(context));
        assertEquals(WeatherSettings.Radar.PLAYBACK_NOW,WeatherSettings.getRadarPlaybackStart(context));
        assertEquals(55,WeatherSettings.getRadarOpacityPercent(context));
    }

    @Test
    public void plus24HoursAndNextMidnightUseUtcEpochMillis(){
        long reference = Instant.parse("2026-03-29T00:30:00Z").toEpochMilli(); // German DST change day
        long dayAfter = Instant.parse("2026-03-30T00:30:00Z").toEpochMilli();
        long nextMidnight = Instant.parse("2026-03-30T00:00:00Z").toEpochMilli();
        long windowStart = Instant.parse("2026-03-28T00:30:00Z").toEpochMilli();

        assertEquals(dayAfter, WeatherSettings.Radar.resolvePlaybackStartMillis(
                WeatherSettings.Radar.PLAYBACK_PLUS_24H,reference,windowStart));
        assertEquals(nextMidnight, WeatherSettings.Radar.resolvePlaybackStartMillis(
                WeatherSettings.Radar.PLAYBACK_NEXT_MIDNIGHT,reference,windowStart));
        assertEquals(reference, WeatherSettings.Radar.resolvePlaybackStartMillis(
                WeatherSettings.Radar.PLAYBACK_NOW,reference,windowStart));
        assertEquals(windowStart, WeatherSettings.Radar.resolvePlaybackStartMillis(
                WeatherSettings.Radar.PLAYBACK_OLDEST,reference,windowStart));
        assertEquals(reference, WeatherSettings.Radar.resolvePlaybackStartMillis("unknown",reference,windowStart));
    }

    @Test
    public void midnightBoundaryIsStrictAndPreEpochIsSafe(){
        long midnight = Instant.parse("2026-03-29T00:00:00Z").toEpochMilli();
        assertEquals(Instant.parse("2026-03-30T00:00:00Z").toEpochMilli(),
                WeatherSettings.Radar.nextUtcMidnight(midnight));
        assertEquals(0L,WeatherSettings.Radar.nextUtcMidnight(-1L));
        assertEquals(Instant.parse("1970-01-02T00:00:00Z").toEpochMilli(),
                WeatherSettings.Radar.nextUtcMidnight(0L));
    }

    @Test
    public void storedPlaybackOptionIsResolvedByConvenienceGetter(){
        Context context = RuntimeEnvironment.getApplication();
        clearRadarPreferences(context);
        long reference = Instant.parse("2026-11-28T13:37:00Z").toEpochMilli();
        long windowStart = Instant.parse("2026-11-27T13:37:00Z").toEpochMilli();

        assertEquals(reference,WeatherSettings.getRadarPlaybackStartMillis(context,reference,windowStart));

        WeatherSettings.setRadarPlaybackStart(context,WeatherSettings.Radar.PLAYBACK_PLUS_24H);
        assertEquals(reference+WeatherSettings.Radar.MILLIS_PER_DAY,
                WeatherSettings.getRadarPlaybackStartMillis(context,reference,windowStart));

        WeatherSettings.setRadarPlaybackStart(context,WeatherSettings.Radar.PLAYBACK_NEXT_MIDNIGHT);
        assertEquals(Instant.parse("2026-11-29T00:00:00Z").toEpochMilli(),
                WeatherSettings.getRadarPlaybackStartMillis(context,reference,windowStart));

        WeatherSettings.setRadarPlaybackStart(context,WeatherSettings.Radar.PLAYBACK_OLDEST);
        assertEquals(windowStart,WeatherSettings.getRadarPlaybackStartMillis(context,reference,windowStart));
    }

    @Test
    public void preferencesScreenDeclaresAllRadarSettingsWithSaneDefaults() throws Exception {
        String xml = read("src/main/res/xml/preferences.xml");
        int start = xml.indexOf("PREF_category_radar");
        assertTrue("missing radar preference category",start>=0);
        int end = xml.indexOf("</PreferenceCategory>",start);
        assertTrue("unterminated radar category",end>start);
        String radar = xml.substring(start,end);

        for (String key : new String[]{
                WeatherSettings.PREF_RADAR_USE_MOBILE_DATA,
                WeatherSettings.PREF_RADAR_TIME_WINDOW,
                WeatherSettings.PREF_RADAR_PREFETCH_PROFILE,
                WeatherSettings.PREF_RADAR_PLAYBACK_START,
                WeatherSettings.PREF_MAP_LAYER_RADAR,
                WeatherSettings.PREF_RADAR_OPACITY}){
            assertTrue("missing preference "+key,radar.contains("android:key=\""+key+"\""));
        }
        assertTrue("mobile data must default off",
                radar.contains("android:key=\""+WeatherSettings.PREF_RADAR_USE_MOBILE_DATA+"\"") &&
                radar.substring(radar.indexOf(WeatherSettings.PREF_RADAR_USE_MOBILE_DATA))
                        .contains("android:defaultValue=\"false\""));
        assertTrue("24h window default",radar.contains("android:defaultValue=\"24h\""));
        assertTrue("balanced prefetch default",radar.contains("android:defaultValue=\"balanced\""));
        assertTrue("playback starts at now",radar.contains("android:defaultValue=\"now\""));
        assertTrue("opacity default 55",radar.contains("android:defaultValue=\"55\""));
        // Holo/Views rows: standard widgets with title+summary, no custom layout/targets.
        assertFalse("radar prefs must use platform rows",radar.contains("android:layout="));
        assertFalse("radar prefs must use platform rows",radar.contains("custom:"));
        assertEquals("six radar rows",6,count(radar,"<CheckBoxPreference")+count(radar,"<ListPreference"));
        assertEquals("category and every row have a title",7,count(radar,"android:title="));
        assertEquals("every row has a summary",6,count(radar,"android:summary="));
    }

    @Test
    public void englishAndGermanResourcesProvideFourPlaybackOptions() throws Exception {
        String english = read("src/main/res/values/strings.xml");
        String german = read("src/main/res/values-de/strings.xml");
        for (String name : new String[]{
                "preference_category_radar",
                "preference_radar_use_mobile_data_title","preference_radar_use_mobile_data_summary",
                "preference_radar_time_window_title","preference_radar_time_window_summary",
                "preference_radar_prefetch_profile_title","preference_radar_prefetch_profile_summary",
                "preference_radar_playback_start_title","preference_radar_playback_start_summary",
                "preference_radar_layer_visible_title","preference_radar_layer_visible_summary",
                "preference_radar_opacity_title","preference_radar_opacity_summary",
                "preference_radar_current_value"}){
            assertTrue("missing English string "+name,english.contains("<string name=\""+name+"\""));
            assertTrue("missing German string "+name,german.contains("<string name=\""+name+"\""));
        }
        for (String array : new String[]{"radar_time_window","radar_prefetch_profile",
                "radar_playback_start","radar_opacity"}){
            assertTrue("missing English array "+array,english.contains("name=\""+array+"_values\""));
            assertTrue("missing German array "+array,german.contains("name=\""+array+"_values\""));
        }
        assertEquals("four playback options EN",4,arrayItems(english,"radar_playback_start_values"));
        assertEquals("four playback options DE",4,arrayItems(german,"radar_playback_start_values"));
        assertTrue("+24h option present",english.contains("+24 hours from now"));
        assertTrue("next midnight option present",german.contains("00:00 des nächsten Tages"));
        // Android ListPreference.getSummary() calls String.format() on summaries containing
        // '%'; a trailing percent in an entry crashes the Settings screen on bind.
        assertFalse("no percent in EN opacity entries",arrayBody(english,"radar_opacity_text").contains("%"));
        assertFalse("no percent in DE opacity entries",arrayBody(german,"radar_opacity_text").contains("%"));
    }

    private static String arrayBody(String source,String name){
        Matcher matcher = Pattern.compile("<string-array name=\""+name+"\"[^>]*>(.*?)</string-array>",
                Pattern.DOTALL).matcher(source);
        assertTrue("missing array "+name,matcher.find());
        return matcher.group(1);
    }

    private static int arrayItems(String source,String name){
        Matcher matcher = Pattern.compile("<string-array name=\""+name+"\"[^>]*>(.*?)</string-array>",
                Pattern.DOTALL).matcher(source);
        assertTrue("missing array "+name,matcher.find());
        return count(matcher.group(1),"<item>");
    }
}

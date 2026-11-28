/*
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2026 Pawel Dube
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.kaffeemitkoffein.tinyweatherforecastgermany;

import android.content.ContentValues;
import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * TASK-77: the provider seam is pure Java where possible; Context is used only for the
 * preference/database isolation checks. Fixtures follow the live MeteoAlarm CAP/Atom structure
 * observed on 2026-11-28.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class WarningProviderTest {

    private static String fixture(String name) throws Exception {
        try (InputStream in = WarningProviderTest.class.getResourceAsStream("/xml/" + name)) {
            assertNotNull("missing /xml/" + name, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static Weather.WeatherLocation location(double latitude, double longitude) {
        Weather.WeatherLocation location = new Weather.WeatherLocation();
        location.latitude = latitude;
        location.longitude = longitude;
        return location;
    }

    @Test
    public void atomIndexOnlyAcceptsHttpsCapLinksOnTheMeteoAlarmHost() throws Exception {
        List<WarningProvider.CapFeedDecoder.CapReference> refs =
                WarningProvider.CapFeedDecoder.parseIndex(fixture("meteoalarm-atom-denmark.xml"));
        assertEquals(1, refs.size());
        assertEquals("11111111-2222-3333-4444-555555555555", refs.get(0).capId);
        assertEquals("https://feeds.meteoalarm.org/api/v1/warnings/feeds-denmark/"
                + "11111111-2222-3333-4444-555555555555", refs.get(0).url);
        assertTrue(refs.get(0).title.contains("Flood"));
    }

    @Test
    public void areaCodeOnlyWarningHasNoGeometryAndNamesItsSource() throws Exception {
        WarningProvider.CapFeedDecoder.CapReference reference =
                new WarningProvider.CapFeedDecoder.CapReference("cap-77",
                        "https://feeds.meteoalarm.org/api/v1/warnings/feeds-denmark/cap-77", "test");
        WeatherWarning warning = WarningProvider.CapFeedDecoder.parseCap(
                fixture("meteoalarm-cap-denmark.xml"), reference, WarningProvider.METEOALARM,
                "DK", "en", 1000L);
        assertNotNull(warning);
        assertEquals(WarningProvider.ID_METEOALARM, warning.provider_id);
        assertEquals("meteoalarm:DK", warning.provider_key);
        assertEquals("cap-77", warning.identifier);
        assertEquals("Moderate", warning.severity);
        assertEquals("2; yellow; Moderate", warning.parameter_values.get(
                warning.parameter_names.indexOf("awareness_level")));
        assertEquals("255 235 59", warning.area_color);
        assertEquals(2, WeatherWarning.Severity.toInt(warning.severity));
        assertTrue(warning.polygons.isEmpty());
        assertTrue(warning.excluded_polygons.isEmpty());
        assertTrue(warning.area_warncellIDs.isEmpty());
        assertTrue(warning.area_names.get(0).startsWith("MeteoAlarm (DK)"));
        assertTrue(warning.area_names.get(0).contains("yellow/flood"));
        assertTrue(warning.area_names.get(0).contains(WarningProvider.WarningProviderState.NO_GEOMETRY_HINT));
        assertTrue(warning.area_names.contains("Hovedstaden [EMMA_ID DK001]"));
    }

    @Test
    public void explicitLevelMappingDocumentsTheDwdLevelTwoCollision() {
        WarningProvider.WarningLevel yellow =
                WarningProvider.WarningLevel.fromAwarenessLevel("2; yellow; Moderate");
        WarningProvider.WarningLevel orange =
                WarningProvider.WarningLevel.fromAwarenessLevel("3; orange; Severe");
        WarningProvider.WarningLevel red =
                WarningProvider.WarningLevel.fromAwarenessLevel("4; red; Extreme");
        assertEquals(WarningProvider.WarningLevel.METEOALARM_YELLOW, yellow);
        assertEquals(WarningProvider.WarningLevel.METEOALARM_ORANGE, orange);
        assertEquals(WarningProvider.WarningLevel.METEOALARM_RED, red);
        assertEquals("Moderate", yellow.severity);
        assertEquals("255 235 59", yellow.areaColor);
        assertEquals("255 140 0", orange.areaColor);
        assertEquals("229 57 53", red.areaColor);
        assertEquals(2, yellow.level);
        // DWD level 2 is orange, MeteoAlarm level 2 is yellow: same severity integer,
        // different explicit enum/colour so the collision cannot be overlooked.
        WarningProvider.WarningLevel dwdOrange =
                WarningProvider.WarningLevel.fromDwdSeverity(WeatherWarning.Severity.MODERATE);
        assertEquals(2, dwdOrange.level);
        assertEquals("255 140 0", dwdOrange.areaColor);
        assertFalse(yellow.areaColor.equals(dwdOrange.areaColor));
        assertEquals(WeatherWarning.Severity.SEVERE, orange.severity);
        assertEquals(WeatherWarning.Severity.EXTREME, red.severity);
    }

    @Test
    public void stationCoordinatesSelectExactlyOneProvider() {
        assertEquals(WarningProvider.DWD,
                WarningProvider.Registry.forLocation(location(53.55, 9.98)));   // Hamburg
        assertEquals("DE", WarningProvider.Registry.countryCodeFor(53.55, 9.98));
        assertEquals(WarningProvider.METEOALARM,
                WarningProvider.Registry.forLocation(location(55.58, 12.13)));   // 06170
        assertEquals("DK", WarningProvider.METEOALARM.countryKey(location(55.58, 12.13)));
        assertEquals("NO", WarningProvider.METEOALARM.countryKey(location(59.93, 10.72)));
        assertEquals("FR", WarningProvider.METEOALARM.countryKey(location(48.85, 2.35)));
        assertEquals(WarningProvider.NONE,
                WarningProvider.Registry.forLocation(location(49.18, -123.17))); // Vancouver
        assertEquals(WarningProvider.NONE,
                WarningProvider.Registry.forLocation(location(37.37, -121.93))); // San Jose
        // 39 public country feeds in total: Germany stays on DWD, the other 38 are
        // MeteoAlarm feeds.
        assertEquals(38, WarningProvider.Registry.feeds().size());
        for (WarningProvider.MeteoAlarmFeed feed : WarningProvider.Registry.feeds()) {
            assertTrue("feed must be https and token-free: " + feed.indexUrl(),
                    feed.indexUrl().startsWith("https://feeds.meteoalarm.org/feeds/"));
            assertFalse("token in feed URL", feed.indexUrl().contains("token"));
        }
    }

    @Test
    public void providerStateIsScopedByFeed() {
        Context context = RuntimeEnvironment.getApplication();
        String dwdKey = WarningProvider.WarningProviderState.feedKey(WarningProvider.DWD, "DE");
        String dkKey = WarningProvider.WarningProviderState.feedKey(WarningProvider.METEOALARM, "DK");
        String noKey = WarningProvider.WarningProviderState.feedKey(WarningProvider.METEOALARM, "NO");
        assertEquals("dwd", dwdKey);
        assertEquals("meteoalarm:DK", dkKey);
        assertEquals("meteoalarm:NO", noKey);
        APIReaders.CapCache.saveEtag(context, dkKey, "\"etag-dk\"");
        APIReaders.CapCache.saveEtag(context, noKey, "\"etag-no\"");
        assertEquals("\"etag-dk\"", APIReaders.CapCache.loadEtag(context, dkKey));
        assertEquals("\"etag-no\"", APIReaders.CapCache.loadEtag(context, noKey));
        WarningProvider.WarningProviderState.saveLastUpdate(context, dkKey, 1234L);
        assertEquals(1234L, WarningProvider.WarningProviderState.loadLastUpdate(context, dkKey));
        assertEquals(0L, WarningProvider.WarningProviderState.loadLastUpdate(context, noKey));
        WarningProvider.WarningProviderState.rememberCapIds(context, dkKey,
                java.util.Collections.singleton("cap-dk-1"));
        Set<String> ids = WarningProvider.WarningProviderState.loadCapIds(context, dkKey);
        assertTrue(ids.contains("cap-dk-1"));
        assertTrue(WarningProvider.WarningProviderState.loadCapIds(context, noKey).isEmpty());
    }

    @Test
    public void databaseRowsOfDifferentProvidersDoNotOverwriteEachOther() {
        Context context = RuntimeEnvironment.getApplication();
        WeatherWarnings.cleanWeatherWarningsDatabase(context);

        WeatherWarning dwd = new WeatherWarning();
        dwd.identifier = "dwd-77";
        dwd.provider_id = WarningProvider.ID_DWD;
        dwd.provider_key = WarningProvider.ID_DWD;
        dwd.area_color = "255 235 59";
        dwd.severity = "Minor";
        dwd.area_names = new java.util.ArrayList<>();
        dwd.polygons = new java.util.ArrayList<>();
        dwd.excluded_polygons = new java.util.ArrayList<>();
        dwd.area_warncellIDs = new java.util.ArrayList<>();
        dwd.codes = new java.util.ArrayList<>();
        dwd.references = new java.util.ArrayList<>();
        dwd.groups = new java.util.ArrayList<>();
        dwd.parameter_names = new java.util.ArrayList<>();
        dwd.parameter_values = new java.util.ArrayList<>();

        WeatherWarning meteoalarm = new WeatherWarning();
        meteoalarm.identifier = "cap-77";
        meteoalarm.provider_id = WarningProvider.ID_METEOALARM;
        meteoalarm.provider_key = "meteoalarm:DK";
        meteoalarm.area_color = "255 235 59";
        meteoalarm.severity = "Moderate";
        meteoalarm.area_names = new java.util.ArrayList<>();
        meteoalarm.polygons = new java.util.ArrayList<>();
        meteoalarm.excluded_polygons = new java.util.ArrayList<>();
        meteoalarm.area_warncellIDs = new java.util.ArrayList<>();
        meteoalarm.codes = new java.util.ArrayList<>();
        meteoalarm.references = new java.util.ArrayList<>();
        meteoalarm.groups = new java.util.ArrayList<>();
        meteoalarm.parameter_names = new java.util.ArrayList<>();
        meteoalarm.parameter_values = new java.util.ArrayList<>();

        WeatherWarnings.writeWarningsToDatabase(context, new java.util.ArrayList<>(
                java.util.Arrays.asList(dwd, meteoalarm)));
        assertEquals(1, WeatherWarnings.getCurrentWarnings(context, false,
                WarningProvider.ID_DWD).size());
        assertEquals(1, WeatherWarnings.getCurrentWarnings(context, false,
                WarningProvider.ID_METEOALARM).size());

        WeatherWarnings.cleanWeatherWarningsDatabase(context, WarningProvider.ID_DWD);
        assertTrue(WeatherWarnings.getCurrentWarnings(context, false,
                WarningProvider.ID_DWD).isEmpty());
        assertEquals(1, WeatherWarnings.getCurrentWarnings(context, false,
                WarningProvider.ID_METEOALARM).size());

        WeatherWarnings.pruneFeed(context, "meteoalarm:DK",
                java.util.Collections.<String>emptySet());
        assertTrue(WeatherWarnings.getCurrentWarnings(context, false,
                WarningProvider.ID_METEOALARM).isEmpty());
    }
}

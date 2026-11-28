/*
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2026 Pawel Dube
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.kaffeemitkoffein.tinyweatherforecastgermany;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * TASK-90: capabilities parsing, secure parsing and pure ISO-8601 helpers. The anchor must
 * come from the DWD REFERENCE_TIME extent, not from the device clock.
 */
public class RadarCapabilitiesTest {

    private static final long START = 1795672800000L; // 2026-11-26T06:00:00Z
    private static final long END = START + 96L * 5L * 60L * 1000L;
    private static final long REFERENCE = START + 48L * 5L * 60L * 1000L;

    private static String capabilities(String timeExtent, String referenceExtent) {
        return "<WMT_MS_Capabilities version=\"1.1.0\"><Capability><Layer><Title>DWD</Title>"
                + "<Layer><Name>Radar_wn-product_1x1km_ger</Name><Title>Radar</Title>"
                + timeExtent + referenceExtent
                + "</Layer></Layer></Capability></WMT_MS_Capabilities>";
    }

    @Test
    public void intervalExtentAndReferenceTimeDefineTheWindow() throws Exception {
        String xml = capabilities(
                "<Extent name=\"time\" default=\"2026-11-26T08:00:00.000Z\">"
                        + "2026-11-26T06:00:00.000Z/2026-11-26T14:00:00.000Z/PT5M</Extent>",
                "<Extent name=\"REFERENCE_TIME\" default=\"2026-11-26T10:00:00.000Z\">"
                        + "2026-11-26T10:00:00.000Z</Extent>");
        RadarCapabilities.Window window = RadarCapabilities.parse(xml);
        assertNotNull(window);
        assertEquals(START, window.startMillis);
        assertEquals(END, window.endMillis);
        assertEquals(5L * 60L * 1000L, window.stepMillis);
        assertEquals(START + 4L * 60L * 60L * 1000L, window.anchorMillis);
        assertTrue(window.fromCapabilities);
    }

    @Test
    public void commaEnumerationUsesFirstAndLastValues() throws Exception {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0) {
                values.append(',');
            }
            values.append(java.time.Instant.ofEpochMilli(START + i * 5L * 60L * 1000L).toString());
        }
        String xml = capabilities(
                "<Extent name=\"time\" default=\"" + java.time.Instant.ofEpochMilli(REFERENCE)
                        + "\">" + values + "</Extent>",
                "<Extent name=\"REFERENCE_TIME\" default=\""
                        + java.time.Instant.ofEpochMilli(REFERENCE) + "\"/>");
        RadarCapabilities.Window window = RadarCapabilities.parse(xml);
        assertNotNull(window);
        assertEquals(START, window.startMillis);
        assertEquals(START + 11L * 5L * 60L * 1000L, window.endMillis);
        assertEquals(5L * 60L * 1000L, window.stepMillis);
        assertEquals(REFERENCE, window.anchorMillis);
    }

    @Test
    public void wms13DimensionElementsParseLikeExtents() throws Exception {
        // The live DWD service answers with WMS 1.3.0: no DTD, Dimension instead of Extent,
        // and a non-time "current" default for the time dimension.
        String xml = "<WMS_Capabilities version=\"1.3.0\"><Capability><Layer>"
                + "<Layer><Name>Radar_wn-product_1x1km_ger</Name>"
                + "<Dimension name=\"time\" default=\"current\" units=\"ISO8601\">"
                + "2026-11-26T06:00:00.000Z/2026-11-26T14:00:00.000Z/PT5M</Dimension>"
                + "<Dimension name=\"REFERENCE_TIME\" default=\"2026-11-26T10:00:00.000Z\" "
                + "units=\"ISO8601\">2026-11-26T10:00:00.000Z</Dimension>"
                + "</Layer></Layer></Capability></WMS_Capabilities>";
        RadarCapabilities.Window window = RadarCapabilities.parse(xml);
        assertNotNull(window);
        assertEquals(START, window.startMillis);
        assertEquals(END, window.endMillis);
        assertEquals(5L * 60L * 1000L, window.stepMillis);
        assertEquals(START + 4L * 60L * 60L * 1000L, window.anchorMillis);
    }

    @Test
    public void absentRadarLayerOrTimeExtentYieldsNull() throws Exception {
        assertNull(RadarCapabilities.parse("<Capability><Layer><Name>other</Name></Layer></Capability>"));
        assertNull(RadarCapabilities.parse(capabilities("", "")));
        assertNull(RadarCapabilities.parse(null));
    }

    @Test
    public void capabilitiesAreParsedThroughTheHardenedSeam() throws Exception {
        String malicious = "<!DOCTYPE xxe [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" + capabilities(
                "<Extent name=\"time\">2026-11-26T06:00:00Z/2026-11-26T14:00:00Z/PT5M</Extent>", "");
        try {
            RadarCapabilities.parse(malicious);
            fail("DTD was accepted by the capabilities parser");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("DOCTYPE"));
        }
    }

    @Test
    public void pureInstantAndDurationHelpers() {
        assertEquals(START, RadarCapabilities.parseInstant("2026-11-26T06:00:00.000Z").longValue());
        assertEquals(START, RadarCapabilities.parseInstant("2026-11-26T07:00:00+01:00").longValue());
        assertNull(RadarCapabilities.parseInstant("not-a-date"));
        assertNull(RadarCapabilities.parseInstant(null));
        assertEquals(300_000L, RadarCapabilities.parseDurationMillis("PT5M"));
        assertEquals(300_000L, RadarCapabilities.parseDurationMillis("PT300S"));
        assertEquals(3_600_000L, RadarCapabilities.parseDurationMillis("PT1H"));
        assertEquals(90_000_000L, RadarCapabilities.parseDurationMillis("P1DT1H"));
        assertEquals(0L, RadarCapabilities.parseDurationMillis("garbage"));
    }

    @Test
    public void heuristicIsClockDerivedButAnchorIsNeverTreatedAsCapabilities() {
        long now = START;
        RadarCapabilities.Window heuristic = RadarCapabilities.heuristic(now);
        assertFalse(heuristic.fromCapabilities);
        assertEquals(now - RadarCapabilities.HEURISTIC_PAST_MILLIS, heuristic.startMillis);
        assertEquals(now + RadarCapabilities.HEURISTIC_FUTURE_MILLIS, heuristic.endMillis);
        assertEquals(RadarCapabilities.HEURISTIC_STEP_MILLIS, heuristic.stepMillis);
        assertFalse(RadarCapabilities.isFresh(heuristic, now));
    }
}

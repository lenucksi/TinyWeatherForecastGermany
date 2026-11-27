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
import org.w3c.dom.Document;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * TASK-50: CAP warning ZIP conditional GET and polygon area names. The ETag decision logic and
 * the CAP area parser are pure Java, so they are exercised on the JVM; the source guards pin the
 * wiring (If-None-Match on the poll, no DB rewrite on 304) that cannot be run without a device.
 */
public class CapEtagConditionalGetTest {

    private static String fixture(String name) throws IOException {
        try (InputStream input = CapEtagConditionalGetTest.class.getResourceAsStream("/xml/" + name)) {
            assertNotNull("missing fixture /xml/" + name, input);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String source(String name) throws IOException {
        for (String candidate : new String[]{
                "src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/" + name,
                "app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/" + name}) {
            File file = new File(candidate);
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("source file not found: " + name);
    }

    @Test
    public void etagIsKeptVerbatimButInjectionIsRejected() {
        assertEquals("\"6b09e538-76b52\"",
                APIReaders.CapCache.normalizeEtag("\"6b09e538-76b52\""));
        assertEquals("\"6b09e538-76b52\"",
                APIReaders.CapCache.normalizeEtag("  \"6b09e538-76b52\"  "));
        assertNull(APIReaders.CapCache.normalizeEtag(null));
        assertNull(APIReaders.CapCache.normalizeEtag(""));
        assertNull(APIReaders.CapCache.normalizeEtag("\"bad\r\nInjected: 1\""));
        assertNull(APIReaders.CapCache.normalizeEtag(new String(new char[200]).replace('\0', 'x')));
    }

    @Test
    public void notModifiedKeepsValidatorAndTwoHundredWithoutEtagClearsIt() {
        String stored = "\"6b09e538-76b52\"";
        assertTrue(APIReaders.CapCache.isNotModified(304));
        assertFalse(APIReaders.CapCache.isNotModified(200));
        // DWD repeats the validator on the 304; a proxy that omits it must not lose it.
        assertEquals("\"6b09e538-76b52\"",
                APIReaders.CapCache.etagToStore(304, "\"6b09e538-76b52\"", stored));
        assertEquals(stored, APIReaders.CapCache.etagToStore(304, null, stored));
        assertEquals("\"neweTag-1\"",
                APIReaders.CapCache.etagToStore(200, "\"neweTag-1\"", stored));
        assertNull(APIReaders.CapCache.etagToStore(200, null, stored));
    }

    @Test
    public void polygonAreasGetTheirAreaDescToo() throws Exception {
        Document document = APIReaders.SecureXml.parse(fixture("cap-polygon-areas.xml"));
        WeatherWarning warning = new WeatherWarning();
        APIReaders.WeatherWarningsRunnable.parseAreas(document, warning);

        assertEquals(2, warning.polygons.size());
        // The generic polygon description is kept once; the named warncell area is still present.
        assertEquals(2, warning.area_names.size());
        assertTrue(warning.area_names.contains("polygonal event area"));
        assertTrue(warning.area_names.contains("Stadt Roßwein"));
        assertEquals(1, warning.area_warncellIDs.size());
        assertEquals("145221070", warning.area_warncellIDs.get(0));
    }

    @Test
    public void conditionalGetIsWiredIntoTheWarningPollWithoutRewritingOn304() throws IOException {
        String apiReaders = source("APIReaders.java");
        assertTrue("stored ETag is not read for the CAP poll",
                apiReaders.contains("Http.getConditional(url, CapCache.loadEtag(context), cancellation)"));
        assertTrue("If-None-Match is not sent",
                apiReaders.contains("setRequestProperty(CapCache.HEADER_IF_NONE_MATCH, validator)"));
        assertTrue("304 is not detected", apiReaders.contains("CapCache.isNotModified(code)"));

        int notModified = apiReaders.indexOf("if (notModified){");
        int clean = apiReaders.indexOf("WeatherWarnings.cleanWeatherWarningsDatabase(context);");
        int write = apiReaders.indexOf("WeatherWarnings.writeWarningsToDatabase(context,warnings);");
        assertTrue("304 branch missing", notModified > 0);
        assertTrue("DB rewrite is not guarded by the 304 branch",
                clean > notModified && write > notModified);

        int areaDesc = apiReaders.indexOf("String areaDesc = getValue(element,\"areaDesc\");");
        int polygon = apiReaders.indexOf("String polygon = getValue(element,\"polygon\");");
        assertTrue("areaDesc is still collected only in the non-polygon branch",
                areaDesc > 0 && polygon > areaDesc);
    }
}

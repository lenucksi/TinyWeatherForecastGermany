/**
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2026 Pawel Dube
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import org.junit.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * TASK-30: fixture-driven coverage of the single hardened XML parsing seam. All fixtures live
 * under app/src/test/resources/xml, including a malicious DTD with an external file entity.
 * Pure JUnit: APIReaders.SecureXml has no framework references.
 */
public class SecureXmlParserTest {

    private static String fixture(String name) throws IOException {
        try (InputStream input = SecureXmlParserTest.class.getResourceAsStream("/xml/" + name)) {
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

    @Test
    public void validWarningFixtureParses() throws Exception {
        Document document = APIReaders.SecureXml.parse(fixture("valid-warning.xml"));
        assertEquals("2.49.0.1.276.0.DWD.PVW.1234567890",
                document.getElementsByTagName("identifier").item(0).getTextContent());
        assertEquals("opendata@dwd.de",
                document.getElementsByTagName("sender").item(0).getTextContent());
    }

    @Test
    public void validKmlFixtureParsesThroughReaderOverload() throws Exception {
        Document document = APIReaders.SecureXml.parse(
                new StringReader(fixture("valid-kml.xml")));
        assertEquals("Berlin-Dahlem",
                document.getElementsByTagName("kml:description").item(0).getTextContent());
    }

    @Test
    public void dtdAndEntityFixturesAreRejectedBeforeParsing() throws Exception {
        for (String name : new String[]{
                "malicious-dtd.xml", "external-entity.xml", "entity-expansion.xml"}) {
            try {
                APIReaders.SecureXml.parse(fixture(name));
                fail("DTD fixture was accepted: " + name);
            } catch (IOException expected) {
                assertTrue("unexpected message: " + expected.getMessage(),
                        expected.getMessage().contains("DOCTYPE"));
            }
        }
    }

    @Test
    public void hardenedParserFactoryRejectsDoctypeEvenWithoutThePreScreen() throws Exception {
        // Bypass the pre-screen to prove the factory itself is hardened.
        try {
            APIReaders.SecureXml.newSecureDocumentBuilder().parse(
                    new InputSource(new StringReader(fixture("malicious-dtd.xml"))));
            fail("parser accepted a DOCTYPE declaration");
        } catch (SAXParseException expected) {
            // Expected: disallow-doctype-decl is active on the factory.
        }
    }

    @Test
    public void malformedXmlIsRejected() throws Exception {
        try {
            APIReaders.SecureXml.parse(fixture("malformed.xml"));
            fail("malformed XML was accepted");
        } catch (SAXParseException expected) {
            // Expected.
        }
    }

    @Test
    public void nullInputsAreRejected() throws Exception {
        try {
            APIReaders.SecureXml.parse((String) null);
            fail("null XML was accepted");
        } catch (IOException expected) {
            // Expected.
        }
        try {
            APIReaders.SecureXml.parse((StringReader) null);
            fail("null reader was accepted");
        } catch (IOException expected) {
            // Expected.
        }
    }

    @Test
    public void dtdScreenIsCaseInsensitiveAndToleratesWhitespace() {
        assertTrue(APIReaders.SecureXml.containsDtdDeclaration("<!DOCTYPE alert>"));
        assertTrue(APIReaders.SecureXml.containsDtdDeclaration("<!doctype alert>"));
        assertTrue(APIReaders.SecureXml.containsDtdDeclaration("< !  ENTITY xxe SYSTEM \"file:///x\">"));
        assertFalse(APIReaders.SecureXml.containsDtdDeclaration("<alert>&amp;</alert>"));
        assertFalse(APIReaders.SecureXml.containsDtdDeclaration(null));
    }

    @Test
    public void allDomParsingInApiReadersUsesTheHardenedSeam() throws IOException {
        String source = readSource("src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/APIReaders.java");
        int factories = countOccurrences(source, "DocumentBuilderFactory.newInstance()");
        assertEquals("exactly one factory (inside SecureXml) is allowed", 1, factories);
        assertTrue(source.contains("factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)"));
        assertTrue(source.contains("disallow-doctype-decl"));
        assertTrue(source.contains("SecureXml.parse(zipfile)"));
        assertTrue(source.contains("SecureXml.parse(new InputStreamReader(zipInputStream"));
        assertFalse("unhardened stream parse still present", source.contains("documentBuilder.parse(zipInputStream)"));
    }

    private static String readSource(String relativePath) throws IOException {
        String[] candidates = {relativePath, "app/" + relativePath};
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IOException("source file not found: " + relativePath);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }
}

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
import java.util.HashSet;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

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
    public void dtdFixtureIsRejectedThroughReaderOverload() throws Exception {
        // The reader overload buffers before delegating to parse(String); the DTD screen must
        // still reject an external-entity attack (CodeQL alert #9, java/xxe).
        try {
            APIReaders.SecureXml.parse(new StringReader(fixture("external-entity.xml")));
            fail("XXE fixture was accepted through the reader overload");
        } catch (IOException expected) {
            assertTrue("unexpected message: " + expected.getMessage(),
                    expected.getMessage().contains("DOCTYPE"));
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

    /**
     * Android's DocumentBuilderFactory does not expose the JAXP secure-processing feature.
     * This factory emulates that parser so the regression (every DWD download failing with
     * ParserConfigurationException) is covered by a JVM test.
     */
    private static class ReducedFeatureFactory extends DocumentBuilderFactory {
        private final DocumentBuilderFactory delegate = DocumentBuilderFactory.newInstance();
        private final Set<String> unsupported = new HashSet<String>();

        ReducedFeatureFactory reject(String... features) {
            for (String feature : features) {
                unsupported.add(feature);
            }
            return this;
        }

        @Override public DocumentBuilder newDocumentBuilder() throws ParserConfigurationException {
            return delegate.newDocumentBuilder();
        }
        @Override public void setAttribute(String name, Object value) {
            delegate.setAttribute(name, value);
        }
        @Override public Object getAttribute(String name) {
            return delegate.getAttribute(name);
        }
        @Override public void setFeature(String name, boolean value) throws ParserConfigurationException {
            if (unsupported.contains(name)) {
                throw new ParserConfigurationException("feature not supported by this parser: " + name);
            }
            delegate.setFeature(name, value);
        }
        @Override public boolean getFeature(String name) throws ParserConfigurationException {
            return delegate.getFeature(name);
        }
        @Override public void setNamespaceAware(boolean awareness) {
            delegate.setNamespaceAware(awareness);
        }
        @Override public boolean isNamespaceAware() { return delegate.isNamespaceAware(); }
        @Override public void setValidating(boolean validating) { delegate.setValidating(validating); }
        @Override public boolean isValidating() { return delegate.isValidating(); }
        @Override public void setIgnoringElementContentWhitespace(boolean whitespace) {
            delegate.setIgnoringElementContentWhitespace(whitespace);
        }
        @Override public boolean isIgnoringElementContentWhitespace() {
            return delegate.isIgnoringElementContentWhitespace();
        }
        @Override public void setExpandEntityReferences(boolean expand) {
            delegate.setExpandEntityReferences(expand);
        }
        @Override public boolean isExpandEntityReferences() { return delegate.isExpandEntityReferences(); }
        @Override public void setIgnoringComments(boolean ignore) { delegate.setIgnoringComments(ignore); }
        @Override public boolean isIgnoringComments() { return delegate.isIgnoringComments(); }
        @Override public void setCoalescing(boolean coalescing) { delegate.setCoalescing(coalescing); }
        @Override public boolean isCoalescing() { return delegate.isCoalescing(); }
    }

    @Test
    public void parserWithoutSecureProcessingFeatureStillParsesDwdData() throws Exception {
        ReducedFeatureFactory factory = new ReducedFeatureFactory()
                .reject(XMLConstants.FEATURE_SECURE_PROCESSING);
        DocumentBuilder builder = APIReaders.SecureXml.newSecureDocumentBuilder(factory);
        Document document = builder.parse(new InputSource(
                new StringReader(fixture("valid-kml.xml"))));
        assertEquals("Berlin-Dahlem",
                document.getElementsByTagName("kml:description").item(0).getTextContent());
    }

    @Test
    public void parserWithoutSecureProcessingStillRejectsDoctype() throws Exception {
        ReducedFeatureFactory factory = new ReducedFeatureFactory()
                .reject(XMLConstants.FEATURE_SECURE_PROCESSING);
        DocumentBuilder builder = APIReaders.SecureXml.newSecureDocumentBuilder(factory);
        try {
            builder.parse(new InputSource(new StringReader(fixture("malicious-dtd.xml"))));
            fail("parser accepted a DOCTYPE declaration");
        } catch (SAXParseException expected) {
            // Expected: the disallow-doctype-decl switch is still active.
        }
    }

    @Test
    public void dtdIsStillRejectedWhenNoFactoryFeatureIsSupported() throws Exception {
        // Emulates the Android parser, which accepts none of the JAXP hardening features.
        // Safety then rests on the DTD pre-screen in SecureXml.parse(), not on the factory.
        ReducedFeatureFactory factory = new ReducedFeatureFactory().reject(
                XMLConstants.FEATURE_SECURE_PROCESSING,
                "http://apache.org/xml/features/disallow-doctype-decl",
                "http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd");
        DocumentBuilder builder = APIReaders.SecureXml.newSecureDocumentBuilder(factory);
        assertNotNull(builder.parse(new InputSource(
                new StringReader(fixture("valid-kml.xml")))));
        try {
            APIReaders.SecureXml.parse(new StringReader(fixture("malicious-dtd.xml")));
            fail("DTD reached the parser without factory support");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("DOCTYPE"));
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

    /**
     * TASK-57: CodeQL's java/xxe query cannot follow the hardening through the
     * setFeatureIfSupported() helper or see the DTD pre-screen, so the single flagged parse
     * call carries a narrowly scoped suppression. Guard the marker's placement (the comment
     * must be on the line directly above the alert) and the documented best-effort rationale,
     * and make sure the fail-closed setFeature implementation from before TASK-47 stays gone.
     */
    @Test
    public void xxeSuppressionIsNarrowAndDocumentsBestEffortHardening() throws IOException {
        String source = readSource("src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/APIReaders.java");
        String[] lines = source.split("\\r?\\n");
        int parseLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("return newSecureDocumentBuilder().parse(")) {
                parseLine = i;
                break;
            }
        }
        assertTrue("hardened parse call not found", parseLine > 0);
        assertTrue("codeql[java/xxe] must sit directly above the flagged parse call",
                "// codeql[java/xxe]".equals(lines[parseLine - 1].trim()));
        StringBuilder justification = new StringBuilder();
        for (int i = Math.max(0, parseLine - 8); i < parseLine - 1; i++) {
            justification.append(lines[i]).append('\n');
        }
        String note = justification.toString();
        assertTrue("suppression must justify the builder hardening",
                note.contains("newSecureDocumentBuilder()"));
        assertTrue("suppression must mention the DTD pre-screen", note.contains("pre-screen"));
        assertTrue("suppression must record why best-effort is required",
                note.contains("best-effort") && note.contains("TASK-47"));
        assertFalse("fail-closed setFeature implementation reintroduced",
                source.contains("throw new ParserConfigurationException(\"XML parser cannot disable"));
        assertTrue("setFeature stays best-effort",
                source.contains("catch (ParserConfigurationException | IllegalArgumentException unsupported)"));
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

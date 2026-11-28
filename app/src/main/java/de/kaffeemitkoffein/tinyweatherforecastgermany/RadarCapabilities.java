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

import android.content.Context;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TASK-90: capabilities-driven radar window and DWD-provided time anchor.
 *
 * <p>The WMS GetCapabilities document defines the exact time range and the reference time
 * (forecast run) of {@value #LAYER_NAME}. All instants are UTC epoch millis and come from the
 * document - the device clock is never used as the anchor. The document is roughly 2 MB, so it
 * is fetched at most once per process; the parsed metadata is additionally TTL-cached in
 * {@link DataStorage} for about an hour and falls back to the last good value or to a heuristic
 * window without ever blocking the first paint.</p>
 */
public final class RadarCapabilities {

    private RadarCapabilities() {
    }

    public static final String LAYER_NAME = "Radar_wn-product_1x1km_ger";
    public static final String EXTENT_TIME = "time";
    public static final String EXTENT_REFERENCE_TIME = "REFERENCE_TIME";

    /** Capabilities are always read from this allowlisted DWD host, never from a preference. */
    public static final String CAPABILITIES_HOST = "maps.dwd.de";
    /**
     * WMS 1.3.0 is deliberate: the 1.1.0 GetCapabilities response carries a DOCTYPE with an
     * internal DTD subset, which the hardened parser must refuse. The 1.3.0 document has no
     * DTD and declares the same layer/time metadata as {@code Dimension} elements.
     */
    public static final String CAPABILITIES_PATH =
            "/geoserver/dwd/wms?service=WMS&version=1.3.0&request=GetCapabilities";

    public static final long TTL_MILLIS = 60L * 60L * 1000L;
    /** Hard upper bound for the ~2 MB response; a larger body is discarded. */
    public static final int MAX_CAPABILITIES_BYTES = 8 * 1024 * 1024;

    /** Heuristic used only when no capabilities metadata is available at all. */
    public static final long HEURISTIC_PAST_MILLIS = 24L * 60L * 60L * 1000L;
    public static final long HEURISTIC_FUTURE_MILLIS = 2L * 60L * 60L * 1000L;
    public static final long HEURISTIC_STEP_MILLIS = 5L * 60L * 1000L;

    // DataStorage keys. They are deliberately high so they cannot collide with the
    // long-lived ids in DataStorage.java (0..23, 102..114).
    static final int DS_WINDOW_START = 240;
    static final int DS_WINDOW_END = 241;
    static final int DS_STEP_MILLIS = 242;
    static final int DS_ANCHOR_MILLIS = 243;
    static final int DS_FETCHED_AT = 244;
    static final int DS_SOURCE = 245;
    static final int DS_LAST_REFERENCE_TIME = 246;

    static final int SOURCE_CAPABILITIES = 1;
    static final int SOURCE_HEURISTIC = 2;

    /** The parsed window. Immutable so it can be shared with the loader and the timeline. */
    public static final class Window {
        public final long startMillis;
        public final long endMillis;
        public final long stepMillis;
        /** DWD reference time; the only anchor used for playback and "now". */
        public final long anchorMillis;
        /** Wall-clock time at which this metadata was obtained (for TTL only). */
        public final long fetchedAtMillis;
        public final boolean fromCapabilities;

        public Window(long startMillis, long endMillis, long stepMillis, long anchorMillis,
                      long fetchedAtMillis, boolean fromCapabilities) {
            if (stepMillis <= 0 || endMillis < startMillis) {
                throw new IllegalArgumentException("invalid capabilities window");
            }
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.stepMillis = stepMillis;
            this.anchorMillis = anchorMillis;
            this.fetchedAtMillis = fetchedAtMillis;
            this.fromCapabilities = fromCapabilities;
        }
    }

    // ------------------------------------------------------------------ pure parsing

    /** Parses one ISO-8601 instant; returns null when the value is absent or malformed. */
    static Long parseInstant(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // Keep trying with explicit offsets and without fractional seconds.
        }
        try {
            return OffsetDateTime.parse(text).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** Parses an ISO-8601 duration such as PT5M, PT300S, PT1H or P1DT2H. */
    static long parseDurationMillis(String value) {
        if (value == null) {
            return 0L;
        }
        String text = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!text.startsWith("P")) {
            return 0L;
        }
        int index = 1;
        long days = 0L;
        boolean hasTime = false;
        long hours = 0L;
        long minutes = 0L;
        long seconds = 0L;
        while (index < text.length()) {
            char marker = text.charAt(index);
            if (marker == 'T') {
                hasTime = true;
                index++;
                continue;
            }
            int digitStart = index;
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            if (digitStart == index || index >= text.length()) {
                return 0L;
            }
            long amount;
            try {
                amount = Long.parseLong(text.substring(digitStart, index));
            } catch (NumberFormatException e) {
                return 0L;
            }
            char unit = text.charAt(index++);
            if (!hasTime) {
                if (unit == 'D') {
                    days = amount;
                } else {
                    return 0L;
                }
            } else if (unit == 'H') {
                hours = amount;
            } else if (unit == 'M') {
                minutes = amount;
            } else if (unit == 'S') {
                seconds = amount;
            } else {
                return 0L;
            }
        }
        try {
            return Math.addExact(Math.multiplyExact(days, 24L * 60L * 60L * 1000L),
                    Math.addExact(Math.multiplyExact(hours, 60L * 60L * 1000L),
                            Math.addExact(Math.multiplyExact(minutes, 60L * 1000L),
                                    Math.multiplyExact(seconds, 1000L))));
        } catch (ArithmeticException e) {
            return 0L;
        }
    }

    private static String directChildText(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && tag.equals(child.getNodeName())) {
                return child.getTextContent();
            }
        }
        return null;
    }

    private static Element findRadarLayer(Document document) {
        NodeList layers = document.getElementsByTagName("Layer");
        for (int i = 0; i < layers.getLength(); i++) {
            Element layer = (Element) layers.item(i);
            String name = directChildText(layer, "Name");
            if (LAYER_NAME.equals(name == null ? null : name.trim())) {
                return layer;
            }
        }
        return null;
    }

    /** WMS 1.1 calls them Extent, WMS 1.3 calls them Dimension; both carry a name attribute. */
    private static boolean isExtentElement(Element element) {
        String tag = element.getLocalName();
        if (tag == null) {
            tag = element.getNodeName();
        }
        return "Extent".equals(tag) || "Dimension".equals(tag);
    }

    private static Element findExtent(Element layer, String extentName) {
        Element current = layer;
        while (current != null) {
            NodeList children = current.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) child;
                    String name = element.getAttribute("name");
                    if (extentName.equals(name) && isExtentElement(element)) {
                        return element;
                    }
                }
            }
            Node parent = current.getParentNode();
            current = (parent != null && parent.getNodeType() == Node.ELEMENT_NODE)
                    ? (Element) parent : null;
        }
        return null;
    }

    /**
     * Pure, framework-free parse of a WMS Capabilities document. Uses the single hardened
     * parsing seam ({@link APIReaders.SecureXml}), never a stock DocumentBuilderFactory.
     *
     * @return the window described by the radar layer, or null when the layer/extents are absent.
     */
    public static Window parse(String xml) throws Exception {
        if (xml == null || xml.isEmpty()) {
            return null;
        }
        Document document = APIReaders.SecureXml.parse(xml);
        Element layer = findRadarLayer(document);
        if (layer == null) {
            return null;
        }
        Element timeExtent = findExtent(layer, EXTENT_TIME);
        if (timeExtent == null) {
            return null;
        }
        String timeValue = timeExtent.getTextContent();
        if (timeValue == null) {
            return null;
        }
        timeValue = timeValue.trim();

        Long start = null;
        Long end = null;
        long step = 0L;
        if (timeValue.contains("/")) {
            String[] parts = timeValue.split("/", -1);
            if (parts.length >= 2) {
                start = parseInstant(parts[0]);
                end = parseInstant(parts[1]);
            }
            if (parts.length >= 3) {
                step = parseDurationMillis(parts[2]);
            }
        } else if (timeValue.contains(",")) {
            String[] values = timeValue.split(",");
            for (String value : values) {
                Long parsed = parseInstant(value);
                if (parsed == null) {
                    continue;
                }
                if (start == null) {
                    start = parsed;
                }
                end = parsed;
            }
            String[] firstTwo = values.length >= 2 ? new String[]{values[0], values[1]} : null;
            if (firstTwo != null) {
                Long a = parseInstant(firstTwo[0]);
                Long b = parseInstant(firstTwo[1]);
                if (a != null && b != null && b > a) {
                    step = b - a;
                }
            }
        } else {
            Long only = parseInstant(timeValue);
            start = only;
            end = only;
        }

        // The default attribute is the server's current frame and a safe step fallback.
        Long timeDefault = parseInstant(timeExtent.getAttribute("default"));
        if (start == null) {
            start = timeDefault;
        }
        if (end == null) {
            // Only a single-value extent needs the default to complete the range. Never
            // extend a parsed interval/enumeration: the default may be a reference-time
            // sample well outside the enumerated frames.
            end = timeDefault;
        }
        if (step <= 0) {
            step = HEURISTIC_STEP_MILLIS;
        }
        if (start == null || end == null || end < start) {
            return null;
        }

        Element referenceExtent = findExtent(layer, EXTENT_REFERENCE_TIME);
        Long anchor = referenceExtent == null ? null : parseInstant(referenceExtent.getAttribute("default"));
        if (anchor == null && referenceExtent != null) {
            String refText = referenceExtent.getTextContent();
            if (refText != null && refText.contains(",")) {
                String[] values = refText.split(",");
                Long first = parseInstant(values.length > 0 ? values[0] : null);
                Long last = parseInstant(values[values.length - 1]);
                anchor = last != null ? last : first;
            } else {
                anchor = parseInstant(refText);
            }
        }
        // Never use the device clock as the anchor: prefer server-provided values only.
        if (anchor == null) {
            anchor = timeDefault != null ? timeDefault : end;
        }
        return new Window(start, end, step, anchor, System.currentTimeMillis(), true);
    }

    // ------------------------------------------------------------------ DataStorage TTL cache

    /** Persists parsed metadata so a later process can paint before the next network fetch. */
    public static void save(Context context, Window window) {
        if (context == null || window == null) {
            return;
        }
        DataStorage.setLong(context, DS_WINDOW_START, window.startMillis);
        DataStorage.setLong(context, DS_WINDOW_END, window.endMillis);
        DataStorage.setLong(context, DS_STEP_MILLIS, window.stepMillis);
        DataStorage.setLong(context, DS_ANCHOR_MILLIS, window.anchorMillis);
        DataStorage.setLong(context, DS_FETCHED_AT, window.fetchedAtMillis);
        DataStorage.setInt(context, DS_SOURCE, window.fromCapabilities
                ? SOURCE_CAPABILITIES : SOURCE_HEURISTIC);
    }

    /** Returns the last persisted window, or null when this install never stored one. */
    public static Window load(Context context) {
        if (context == null) {
            return null;
        }
        long start = DataStorage.getLong(context, DS_WINDOW_START, Long.MIN_VALUE);
        long end = DataStorage.getLong(context, DS_WINDOW_END, Long.MIN_VALUE);
        long step = DataStorage.getLong(context, DS_STEP_MILLIS, 0L);
        long anchor = DataStorage.getLong(context, DS_ANCHOR_MILLIS, Long.MIN_VALUE);
        long fetchedAt = DataStorage.getLong(context, DS_FETCHED_AT, 0L);
        int source = DataStorage.getInt(context, DS_SOURCE, 0);
        if (start == Long.MIN_VALUE || end == Long.MIN_VALUE || anchor == Long.MIN_VALUE
                || step <= 0 || fetchedAt <= 0L) {
            return null;
        }
        try {
            return new Window(start, end, step, anchor, fetchedAt,
                    source == SOURCE_CAPABILITIES);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static boolean isFresh(Window window, long nowMillis) {
        return window != null && window.fromCapabilities
                && nowMillis - window.fetchedAtMillis >= 0L
                && nowMillis - window.fetchedAtMillis < TTL_MILLIS;
    }

    /** Clock-derived heuristic, used only when no capabilities metadata exists at all. */
    public static Window heuristic(long nowMillis) {
        return new Window(nowMillis - HEURISTIC_PAST_MILLIS, nowMillis + HEURISTIC_FUTURE_MILLIS,
                HEURISTIC_STEP_MILLIS, nowMillis + HEURISTIC_FUTURE_MILLIS, nowMillis, false);
    }

    // ------------------------------------------------------------------ once-per-session fetch

    private static final AtomicBoolean SESSION_ATTEMPTED = new AtomicBoolean(false);
    private static volatile Window SESSION_WINDOW;

    /** Immediate, never-blocking metadata for the first paint. */
    public static Window current(Context context, long nowMillis) {
        Window session = SESSION_WINDOW;
        if (session != null) {
            return session;
        }
        Window stored = load(context);
        if (stored != null && (stored.fromCapabilities || isFresh(stored, nowMillis))) {
            // Last-good capabilities metadata (fresh or expired) beats a clock guess. An
            // expired *heuristic* is not trusted: its anchor was wall-clock derived.
            SESSION_WINDOW = stored;
            return stored;
        }
        Window fallback = heuristic(nowMillis);
        save(context, fallback);
        SESSION_WINDOW = fallback;
        return fallback;
    }

    /**
     * Fetches GetCapabilities at most once per process. The caller runs this off the UI thread;
     * failures keep the last-good/heuristic window and are silent to the user.
     *
     * @return true when a fresh capabilities document was parsed and persisted.
     */
    public static boolean refreshOnce(Context context, APIReaders.Cancellation cancellation) {
        if (context == null || !SESSION_ATTEMPTED.compareAndSet(false, true)) {
            return false;
        }
        String url = APIReaders.HttpsUrl.build(CAPABILITIES_HOST, CAPABILITIES_PATH);
        if (url == null) {
            return false;
        }
        try {
            java.net.URL target = new java.net.URL(url);
            InputStream input = APIReaders.Http.get(target, cancellation);
            byte[] bytes = readBounded(input);
            String xml = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            Window parsed = parse(xml);
            if (parsed == null) {
                return false;
            }
            save(context, parsed);
            SESSION_WINDOW = parsed;
            return true;
        } catch (Exception e) {
            PrivateLog.log(context, PrivateLog.DATA, PrivateLog.ERR,
                    "GetCapabilities unavailable, keeping cached radar window: " + e.getMessage());
            return false;
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_CAPABILITIES_BYTES) {
                    throw new IOException("GetCapabilities response too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            try {
                input.close();
            } catch (IOException ignored) {
                // Nothing left to do.
            }
        }
    }

    /** The last DWD reference time this install displayed, for incremental refresh. */
    public static long loadLastReferenceTime(Context context) {
        return context == null ? 0L
                : DataStorage.getLong(context, DS_LAST_REFERENCE_TIME, 0L);
    }

    public static void saveLastReferenceTime(Context context, long referenceTimeMillis) {
        if (context != null) {
            DataStorage.setLong(context, DS_LAST_REFERENCE_TIME, referenceTimeMillis);
        }
    }

    /** Test seam: forget the once-per-session latch and the in-memory window. */
    public static void resetForTests() {
        SESSION_ATTEMPTED.set(false);
        SESSION_WINDOW = null;
    }
}

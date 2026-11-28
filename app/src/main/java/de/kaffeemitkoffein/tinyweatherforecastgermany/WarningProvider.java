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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import android.content.Context;
import android.content.SharedPreferences;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * TASK-77: the seam between the app and all weather-warning sources.
 *
 * A provider describes its identity/legal attribution, the station locations it covers, the
 * request it needs, and how its own level vocabulary maps onto the app's levels. The fetch
 * plumbing lives in {@code APIReaders.WeatherWarningsRunnable}; decoding of a provider's own
 * wire format lives in {@link CapFeedDecoder}. Consumers keep using {@link WeatherWarning},
 * so neither the adapter, the list nor the map needs to know which provider produced a row.
 *
 * The core of this class is deliberately free of Android framework calls (apart from the
 * preference helpers and string resource ids), so the country/coverage/level decisions are
 * unit-testable on the JVM. Later providers (ECCC, MET Norway, NWS, SMHI) add one
 * implementation and one coverage entry here; no consumer needs to be rewritten.
 */
public interface WarningProvider {

    String ID_DWD = "dwd";
    String ID_METEOALARM = "meteoalarm";
    String ID_NONE = "none";

    /** Stable, provider-scoped identifier; also stored in the warning database. */
    String id();

    /** Human-readable source, used in the warning list and the About screen. */
    String displayName();

    /** Resource id of the attribution line shown in the warning list/About. */
    int attributionTextResId();

    /** Official source/licence page for the attribution link. */
    String attributionUrl();

    /** True when this provider is responsible for the given station. */
    boolean covers(Weather.WeatherLocation location);

    /** Feed/country key (ISO 3166-1 alpha-2 for MeteoAlarm), or null when not applicable. */
    String countryKey(Weather.WeatherLocation location);

    /** Builds the provider-specific poll request; null means "nothing to fetch". */
    WarningRequest buildRequest(Weather.WeatherLocation location, String countryKey, String language);

    /** Maps the provider's own level vocabulary onto the app's explicit levels. */
    WarningLevel levelFor(String providerLevel, String capSeverity);

    /** Minimum time between two automatic polls; 0 keeps the legacy DWD behaviour. */
    long minPollIntervalMillis();

    /** Upper bound of per-message documents downloaded in one poll. */
    int maxCapDocumentsPerPoll();

    /** True only for the unchanged legacy DWD path. */
    boolean isDwd();

    /**
     * The single CAP message decoder used by every CAP-based provider. Atom index parsing and
     * CAP normalisation are pure Java so fixtures can be exercised without a device.
     */
    final class CapFeedDecoder {
        public static final int MAX_CAP_BYTES = 128 * 1024;
        public static final int MAX_INDEX_BYTES = 512 * 1024;
        public static final int MAX_ENTRIES = 24;

        private CapFeedDecoder() {
        }

        /** One Atom entry: the immutable CAP document id and its https URL. */
        public static final class CapReference {
            public final String capId;
            public final String url;
            public final String title;

            public CapReference(String capId, String url, String title) {
                this.capId = capId;
                this.url = url;
                this.title = title;
            }
        }

        private static String firstValue(NodeList nodes) {
            if (nodes.getLength() == 0) {
                return null;
            }
            String value = nodes.item(0).getTextContent();
            return (value == null || value.trim().isEmpty()) ? null : value.trim();
        }

        private static String tagValue(Element parent, String tag) {
            return firstValue(parent.getElementsByTagName(tag));
        }

        private static String tagValue(Document document, String tag) {
            return firstValue(document.getElementsByTagName(tag));
        }

        private static String capValue(Element parent, String tag) {
            NodeList nodes = parent.getElementsByTagNameNS(
                    "urn:oasis:names:tc:emergency:cap:1.2", tag);
            if (nodes.getLength() > 0) {
                String value = nodes.item(0).getTextContent();
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
            return tagValue(parent, tag);
        }

        /** Extracts the last path segment of a CAP URL as its immutable cache id. */
        public static String capIdFromUrl(String url) {
            if (url == null) {
                return null;
            }
            int slash = url.lastIndexOf('/');
            if (slash < 0 || slash == url.length() - 1) {
                return null;
            }
            String id = url.substring(slash + 1);
            // Only plain UUID-like ids; query strings or path tricks are not cache keys.
            return id.matches("[A-Za-z0-9._-]+") ? id : null;
        }

        /**
         * Parses a MeteoAlarm legacy Atom index. Only https links on
         * feeds.meteoalarm.org with type application/cap+xml are admitted, so a poisoned feed
         * cannot redirect the app to another host.
         */
        public static List<CapReference> parseIndex(String xml) throws Exception {
            List<CapReference> result = new ArrayList<>();
            if (xml == null || xml.isEmpty()) {
                return result;
            }
            Document document = APIReaders.SecureXml.parse(xml);
            // The shared hardened parser is deliberately not namespace-aware (Android's
            // parser is not), so match the local XML names and validate the link target.
            NodeList entries = document.getElementsByTagName("entry");
            Set<String> seen = new LinkedHashSet<>();
            for (int i = 0; i < entries.getLength() && result.size() < MAX_ENTRIES; i++) {
                Node node = entries.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element entry = (Element) node;
                NodeList links = entry.getElementsByTagName("link");
                for (int j = 0; j < links.getLength(); j++) {
                    Element link = (Element) links.item(j);
                    if (!"application/cap+xml".equals(link.getAttribute("type"))) {
                        continue;
                    }
                    String url = link.getAttribute("href");
                    if (url == null || !url.startsWith("https://feeds.meteoalarm.org/api/v1/warnings/")) {
                        continue;
                    }
                    String capId = capIdFromUrl(url);
                    if (capId == null || !seen.add(capId)) {
                        continue;
                    }
                    result.add(new CapReference(capId, url, tagValue(entry, "title")));
                    break;
                }
            }
            return result;
        }

        /**
         * Normalises one CAP 1.2 message into the app's warning model.
         *
         * The preferred language info block is chosen first (en-GB/en for non-German devices,
         * de/de-DE when available for German devices). No text is translated or invented.
         * Geometry is used only when the CAP itself contains polygons; otherwise the list
         * receives an area-code hint and the map receives no shape at all.
         */
        public static WeatherWarning parseCap(String xml, CapReference reference,
                                              WarningProvider provider, String countryKey,
                                              String language, long pollingTime) throws Exception {
            Document document = APIReaders.SecureXml.parse(xml);
            WeatherWarning warning = new WeatherWarning();
            warning.polling_time = pollingTime;
            warning.provider_id = provider.id();
            warning.provider_key = WarningProviderState.feedKey(provider, countryKey);
            warning.identifier = reference == null ? tagValue(document, "identifier") : reference.capId;
            warning.sender = tagValue(document, "sender");
            warning.sent = WarningProviderState.timestamp(tagValue(document, "sent"));
            warning.status = tagValue(document, "status");
            warning.msgType = tagValue(document, "msgType");
            warning.source = reference == null ? null : reference.url;
            warning.scope = tagValue(document, "scope");
            warning.codes = new ArrayList<>();
            warning.references = new ArrayList<>();
            warning.groups = new ArrayList<>();
            warning.parameter_names = new ArrayList<>();
            warning.parameter_values = new ArrayList<>();
            warning.polygons = new ArrayList<>();
            warning.excluded_polygons = new ArrayList<>();
            warning.area_names = new ArrayList<>();
            // Never put foreign area codes into the German warncell fallback: that would
            // draw a German polygon for a Danish/French/... area.
            warning.area_warncellIDs = new ArrayList<>();

            NodeList codes = document.getElementsByTagName("code");
            for (int i = 0; i < codes.getLength(); i++) {
                String code = codes.item(i).getTextContent();
                if (code != null && !code.trim().isEmpty()) {
                    warning.codes.add(code.trim());
                }
            }

            Element info = preferredInfo(document, language);
            if (info == null) {
                return null;
            }
            warning.language = capValue(info, "language");
            warning.category = capValue(info, "category");
            warning.event = capValue(info, "event");
            warning.responseType = capValue(info, "responseType");
            warning.urgency = capValue(info, "urgency");
            warning.severity = capValue(info, "severity");
            warning.certainty = capValue(info, "certainty");
            warning.effective = WarningProviderState.timestamp(capValue(info, "effective"));
            warning.onset = WarningProviderState.timestamp(capValue(info, "onset"));
            warning.expires = WarningProviderState.timestamp(capValue(info, "expires"));
            warning.senderName = capValue(info, "senderName");
            warning.headline = capValue(info, "headline");
            warning.description = capValue(info, "description");
            warning.instruction = capValue(info, "instruction");
            warning.web = capValue(info, "web");
            warning.contact = capValue(info, "contact");

            String awarenessLevel = null;
            String awarenessType = null;
            NodeList parameters = info.getElementsByTagName("parameter");
            for (int i = 0; i < parameters.getLength(); i++) {
                if (parameters.item(i).getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element parameter = (Element) parameters.item(i);
                String name = tagValue(parameter, "valueName");
                String value = tagValue(parameter, "value");
                if (name == null || value == null) {
                    continue;
                }
                warning.parameter_names.add(name);
                warning.parameter_values.add(value);
                if ("awareness_level".equals(name)) {
                    awarenessLevel = value;
                } else if ("awareness_type".equals(name)) {
                    awarenessType = value;
                }
            }

            if (awarenessType != null) {
                int separator = awarenessType.indexOf(';');
                if (separator >= 0) {
                    awarenessType = awarenessType.substring(separator + 1).trim();
                }
            }
            WarningLevel level = provider.levelFor(awarenessLevel, warning.severity);
            if (level == null) {
                // Unknown level: keep the raw CAP severity and the provider's own colour
                // vocabulary, but never guess a DWD level.
                return null;
            }
            warning.warning_level = level.name();
            warning.severity = level.severity;
            warning.area_color = level.areaColor;

            NodeList areas = info.getElementsByTagName("area");
            for (int i = 0; i < areas.getLength(); i++) {
                if (areas.item(i).getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element area = (Element) areas.item(i);
                String areaDesc = capValue(area, "areaDesc");
                NodeList areaPolygons = area.getElementsByTagName("polygon");
                for (int j = 0; j < areaPolygons.getLength(); j++) {
                    String polygon = areaPolygons.item(j).getTextContent();
                    if (polygon != null && !polygon.trim().isEmpty()) {
                        warning.polygons.add(polygon.trim());
                    }
                }
                List<String> areaCodes = new ArrayList<>();
                NodeList geocodes = area.getElementsByTagName("geocode");
                for (int j = 0; j < geocodes.getLength(); j++) {
                    if (geocodes.item(j).getNodeType() != Node.ELEMENT_NODE) {
                        continue;
                    }
                    Element geocode = (Element) geocodes.item(j);
                    String name = tagValue(geocode, "valueName");
                    String value = tagValue(geocode, "value");
                    if (name != null && value != null) {
                        areaCodes.add(name + " " + value);
                    }
                }
                StringBuilder label = new StringBuilder();
                if (areaDesc != null) {
                    label.append(areaDesc);
                }
                if (!areaCodes.isEmpty()) {
                    if (label.length() > 0) {
                        label.append(" ");
                    }
                    label.append("[");
                    for (int j = 0; j < areaCodes.size(); j++) {
                        if (j > 0) {
                            label.append(", ");
                        }
                        label.append(areaCodes.get(j));
                    }
                    label.append("]");
                }
                if (label.length() > 0 && !warning.area_names.contains(label.toString())) {
                    warning.area_names.add(label.toString());
                }
            }

            if (warning.polygons.isEmpty()) {
                String providerLabel = provider.displayName();
                if (countryKey != null) {
                    providerLabel += " (" + countryKey + ")";
                }
                String hint = level.colour + (awarenessType == null ? "" : "/" + awarenessType);
                warning.area_names.add(0, providerLabel + " · " + hint
                        + " · " + WarningProviderState.NO_GEOMETRY_HINT);
            }
            return warning;
        }

        private static Element preferredInfo(Document document, String language) {
            NodeList infos = document.getElementsByTagName("info");
            Element fallback = null;
            for (int i = 0; i < infos.getLength(); i++) {
                if (infos.item(i).getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element info = (Element) infos.item(i);
                String lang = capValue(info, "language");
                if (lang == null) {
                    continue;
                }
                if (fallback == null) {
                    fallback = info;
                }
                String normalized = lang.toLowerCase(Locale.ROOT);
                String wantedPrefix = (language == null || language.length() < 2)
                        ? "en" : language.substring(0, 2).toLowerCase(Locale.ROOT);
                boolean wanted = (language == null)
                        ? normalized.startsWith("en")
                        : normalized.equals(language.toLowerCase(Locale.ROOT))
                        || normalized.startsWith(wantedPrefix);
                if (wanted) {
                    return info;
                }
            }
            return fallback;
        }
    }

    /**
     * Explicit level mapping. The collision is intentional and documented: DWD level 2 is
     * orange while MeteoAlarm level 2 ("Moderate") is yellow. Both map to the app's
     * severity value 2 for sorting/notifications, but they keep different colour values and
     * different enum names, so the difference is never silently lost.
     */
    enum WarningLevel {
        DWD_YELLOW("Minor", "255 235 59", 1, "yellow"),
        DWD_ORANGE("Moderate", "255 140 0", 2, "orange"),
        DWD_RED("Severe", "229 57 53", 3, "red"),
        DWD_VIOLET("Extreme", "136 14 79", 4, "violet"),
        METEOALARM_YELLOW("Moderate", "255 235 59", 2, "yellow"),
        METEOALARM_ORANGE("Severe", "255 140 0", 3, "orange"),
        METEOALARM_RED("Extreme", "229 57 53", 4, "red");

        public final String severity;
        public final String areaColor;
        public final int level;
        public final String colour;

        WarningLevel(String severity, String areaColor, int level, String colour) {
            this.severity = severity;
            this.areaColor = areaColor;
            this.level = level;
            this.colour = colour;
        }

        /** Reads the numeric prefix of values such as "3; orange; Severe". */
        public static WarningLevel fromAwarenessLevel(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            int semicolon = trimmed.indexOf(';');
            String prefix = (semicolon > 0 ? trimmed.substring(0, semicolon) : trimmed).trim();
            try {
                switch (Integer.parseInt(prefix)) {
                    case 2: return METEOALARM_YELLOW;
                    case 3: return METEOALARM_ORANGE;
                    case 4: return METEOALARM_RED;
                    default: return null;
                }
            } catch (NumberFormatException e) {
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (lower.contains("yellow") || lower.contains("moderate")) return METEOALARM_YELLOW;
                if (lower.contains("orange") || lower.contains("severe")) return METEOALARM_ORANGE;
                if (lower.contains("red") || lower.contains("extreme")) return METEOALARM_RED;
                return null;
            }
        }

        /** DWD's own four-step scale, used only for the unchanged German feed. */
        public static WarningLevel fromDwdSeverity(String severity) {
            if (WeatherWarning.Severity.MINOR.equals(severity)) return DWD_YELLOW;
            if (WeatherWarning.Severity.MODERATE.equals(severity)) return DWD_ORANGE;
            if (WeatherWarning.Severity.SEVERE.equals(severity)) return DWD_RED;
            if (WeatherWarning.Severity.EXTREME.equals(severity)) return DWD_VIOLET;
            return null;
        }
    }

    /** Everything the network orchestrator needs to know about one poll. */
    final class WarningRequest {
        public final WarningProvider provider;
        public final String countryKey;
        public final String indexUrl;
        public final String etagKey;
        public final String stateKey;
        public final String language;
        public final int maxCapDocuments;
        public final long minPollIntervalMillis;

        WarningRequest(WarningProvider provider, String countryKey, String indexUrl,
                       String etagKey, String stateKey, String language,
                       int maxCapDocuments, long minPollIntervalMillis) {
            this.provider = provider;
            this.countryKey = countryKey;
            this.indexUrl = indexUrl;
            this.etagKey = etagKey;
            this.stateKey = stateKey;
            this.language = language;
            this.maxCapDocuments = maxCapDocuments;
            this.minPollIntervalMillis = minPollIntervalMillis;
        }
    }

    /** One MeteoAlarm country feed, with a coarse but deterministic bbox. */
    final class MeteoAlarmFeed {
        public final String country;
        public final String slug;
        public final double minLat;
        public final double minLon;
        public final double maxLat;
        public final double maxLon;

        MeteoAlarmFeed(String country, String slug, double minLat, double minLon,
                       double maxLat, double maxLon) {
            this.country = country;
            this.slug = slug;
            this.minLat = minLat;
            this.minLon = minLon;
            this.maxLat = maxLat;
            this.maxLon = maxLon;
        }

        public boolean contains(double latitude, double longitude) {
            return latitude >= minLat && latitude <= maxLat
                    && longitude >= minLon && longitude <= maxLon;
        }

        public double area() {
            return (maxLat - minLat) * (maxLon - minLon);
        }

        public String indexUrl() {
            return "https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-" + slug;
        }
    }

    /** Registry, station-country resolution and provider-scoped preference state. */
    final class Registry {
        public static final String DE = "DE";

        // 39 token-free public Atom feeds listed on https://feeds.meteoalarm.org/ on
        // 2026-11-28, plus the DWD home country used for the unchanged legacy path.
        private static final List<MeteoAlarmFeed> FEEDS = Collections.unmodifiableList(Arrays.asList(
                new MeteoAlarmFeed("AD", "andorra", 42.43, 1.41, 42.70, 1.79),
                new MeteoAlarmFeed("AT", "austria", 46.30, 9.50, 49.11, 17.17),
                new MeteoAlarmFeed("BA", "bosnia-herzegovina", 42.56, 15.72, 45.28, 19.63),
                new MeteoAlarmFeed("BE", "belgium", 49.49, 2.54, 51.51, 6.41),
                new MeteoAlarmFeed("BG", "bulgaria", 41.23, 22.35, 44.16, 28.61),
                new MeteoAlarmFeed("CH", "switzerland", 45.81, 5.95, 47.81, 10.50),
                new MeteoAlarmFeed("CY", "cyprus", 34.56, 32.27, 35.72, 34.60),
                new MeteoAlarmFeed("CZ", "czechia", 48.55, 12.09, 51.06, 18.86),
                // Denmark is more specific than Germany; the smaller bbox wins in the
                // narrow overlap at the border (station 06170 is 55.58 N, well inside DK).
                new MeteoAlarmFeed("DK", "denmark", 54.50, 7.50, 57.90, 13.00),
                new MeteoAlarmFeed("EE", "estonia", 57.51, 21.74, 59.71, 28.21),
                new MeteoAlarmFeed("ES", "spain", 35.95, -9.39, 43.79, 3.34),
                new MeteoAlarmFeed("FI", "finland", 59.80, 20.55, 70.10, 31.59),
                new MeteoAlarmFeed("FR", "france", 41.30, -5.20, 51.10, 9.60),
                new MeteoAlarmFeed("GB", "united-kingdom", 49.90, -8.65, 60.90, 1.77),
                new MeteoAlarmFeed("GR", "greece", 34.80, 19.30, 41.75, 28.25),
                new MeteoAlarmFeed("HR", "croatia", 42.39, 13.49, 46.56, 19.46),
                new MeteoAlarmFeed("HU", "hungary", 45.74, 16.20, 48.59, 22.90),
                new MeteoAlarmFeed("IE", "ireland", 51.42, -10.48, 55.39, -5.99),
                new MeteoAlarmFeed("IL", "israel", 29.45, 34.27, 33.35, 35.90),
                new MeteoAlarmFeed("IS", "iceland", 63.30, -24.55, 66.53, -13.50),
                new MeteoAlarmFeed("IT", "italy", 35.49, 6.63, 47.09, 18.52),
                new MeteoAlarmFeed("LT", "lithuania", 53.90, 20.94, 56.45, 26.84),
                new MeteoAlarmFeed("LU", "luxembourg", 49.44, 5.73, 50.18, 6.53),
                new MeteoAlarmFeed("LV", "latvia", 55.67, 20.97, 58.09, 28.24),
                new MeteoAlarmFeed("MD", "moldova", 45.14, 26.62, 48.47, 30.15),
                new MeteoAlarmFeed("ME", "montenegro", 41.85, 18.42, 43.56, 20.36),
                new MeteoAlarmFeed("MK", "republic-of-north-macedonia", 40.85, 20.45, 42.37, 23.03),
                new MeteoAlarmFeed("MT", "malta", 35.78, 14.19, 36.31, 14.59),
                new MeteoAlarmFeed("NL", "netherlands", 50.75, 3.36, 53.56, 7.23),
                new MeteoAlarmFeed("NO", "norway", 57.98, 4.60, 71.19, 31.10),
                new MeteoAlarmFeed("PL", "poland", 49.00, 14.12, 54.84, 24.16),
                new MeteoAlarmFeed("PT", "portugal", 36.96, -9.51, 42.16, -6.19),
                new MeteoAlarmFeed("RO", "romania", 43.62, 20.26, 48.27, 29.70),
                new MeteoAlarmFeed("RS", "serbia", 41.85, 18.81, 46.19, 23.03),
                new MeteoAlarmFeed("SE", "sweden", 55.34, 10.96, 69.06, 24.17),
                new MeteoAlarmFeed("SI", "slovenia", 45.42, 13.37, 46.88, 16.61),
                new MeteoAlarmFeed("SK", "slovakia", 47.73, 16.84, 49.62, 22.56),
                new MeteoAlarmFeed("UA", "ukraine", 44.36, 22.15, 52.38, 40.23)
        ));

        // Germany is checked separately: German stations must keep DWD even when a nearby
        // foreign bbox would also contain the coordinate.
        private static final MeteoAlarmFeed GERMANY =
                new MeteoAlarmFeed(DE, "germany", 47.27, 5.86, 55.06, 15.05);

        private Registry() {
        }

        public static List<MeteoAlarmFeed> feeds() {
            return FEEDS;
        }

        public static MeteoAlarmFeed feedForCountry(String country) {
            if (country == null) {
                return null;
            }
            for (MeteoAlarmFeed feed : FEEDS) {
                if (feed.country.equalsIgnoreCase(country)) {
                    return feed;
                }
            }
            return null;
        }

        /** Smallest matching bbox wins; null when the station is outside all feeds. */
        public static String countryCodeFor(double latitude, double longitude) {
            MeteoAlarmFeed best = null;
            for (MeteoAlarmFeed feed : FEEDS) {
                if (feed.contains(latitude, longitude)
                        && (best == null || feed.area() < best.area())) {
                    best = feed;
                }
            }
            if (GERMANY.contains(latitude, longitude)) {
                // Home country always wins inside its own borders. Station 06170 (55.58 N)
                // is outside this bbox, so Denmark is selected for Copenhagen.
                return DE;
            }
            return best == null ? null : best.country;
        }

        public static WarningProvider forLocation(Weather.WeatherLocation location) {
            if (location == null) {
                return DWD;
            }
            String country = countryCodeFor(location.latitude, location.longitude);
            if (DE.equals(country)) {
                return DWD;
            }
            if (country != null && feedForCountry(country) != null) {
                return METEOALARM;
            }
            return NONE;
        }

        public static WarningProvider byId(String id) {
            if (ID_METEOALARM.equals(id)) return METEOALARM;
            if (ID_DWD.equals(id)) return DWD;
            return NONE;
        }

        /** The provider the current station uses; persisted for the warning list/About. */
        public static WarningProvider activeProvider(Context context) {
            if (context == null) {
                return DWD;
            }
            Weather.WeatherLocation location = WeatherSettings.getSetStationLocation(context);
            return forLocation(location);
        }
    }

    /** Provider-scoped ETag/last-update/CAP-id state, all in the app's own preferences. */
    final class WarningProviderState {
        public static final String PREF_WARNING_PROVIDER = "PREF_warning_provider";
        public static final String PREF_ETAG_PREFIX = "PREF_warning_cap_etag:";
        public static final String PREF_LAST_UPDATE_PREFIX = "PREF_warning_last_update:";
        public static final String PREF_CAP_IDS_PREFIX = "PREF_warning_cap_ids:";
        public static final String NO_GEOMETRY_HINT = "area codes only – no map outline";
        public static final int MAX_CACHED_CAP_IDS = 300;

        private WarningProviderState() {
        }

        public static String feedKey(WarningProvider provider, String countryKey) {
            if (provider == null) {
                return ID_NONE;
            }
            if (provider.isDwd()) {
                return ID_DWD;
            }
            return provider.id() + ":" + (countryKey == null ? "unknown" : countryKey);
        }

        private static SharedPreferences prefs(Context context) {
            return WeatherSettings.getSharedPreferences(context);
        }

        public static String loadEtag(Context context, String key) {
            if (context == null || key == null) {
                return null;
            }
            return APIReaders.CapCache.normalizeEtag(
                    prefs(context).getString(PREF_ETAG_PREFIX + key, ""));
        }

        public static void saveEtag(Context context, String key, String etag) {
            if (context == null || key == null) {
                return;
            }
            String value = APIReaders.CapCache.normalizeEtag(etag);
            prefs(context).edit().putString(PREF_ETAG_PREFIX + key,
                    value == null ? "" : value).apply();
        }

        public static long loadLastUpdate(Context context, String key) {
            if (context == null || key == null) {
                return 0;
            }
            return prefs(context).getLong(PREF_LAST_UPDATE_PREFIX + key, 0);
        }

        public static void saveLastUpdate(Context context, String key, long timestamp) {
            if (context == null || key == null) {
                return;
            }
            prefs(context).edit().putLong(PREF_LAST_UPDATE_PREFIX + key, timestamp).apply();
        }

        /** Immutable CAP ids already downloaded for this feed. */
        public static Set<String> loadCapIds(Context context, String key) {
            Set<String> result = new LinkedHashSet<>();
            if (context == null || key == null) {
                return result;
            }
            String stored = prefs(context).getString(PREF_CAP_IDS_PREFIX + key, "");
            if (stored == null || stored.isEmpty()) {
                return result;
            }
            for (String id : stored.split(",")) {
                String trimmed = id.trim();
                if (WarningProvider.CapFeedDecoder.capIdFromUrl("https://example.invalid/" + trimmed) != null) {
                    result.add(trimmed);
                }
            }
            return result;
        }

        public static void rememberCapIds(Context context, String key, Set<String> ids) {
            if (context == null || key == null || ids == null || ids.isEmpty()) {
                return;
            }
            Set<String> merged = new LinkedHashSet<>(loadCapIds(context, key));
            merged.addAll(ids);
            while (merged.size() > MAX_CACHED_CAP_IDS) {
                merged.remove(merged.iterator().next());
            }
            StringBuilder joined = new StringBuilder();
            for (String id : merged) {
                if (joined.length() > 0) {
                    joined.append(',');
                }
                joined.append(id);
            }
            prefs(context).edit().putString(PREF_CAP_IDS_PREFIX + key, joined.toString()).apply();
        }

        /** CAP timestamps use ISO-8601 with an optional timezone suffix. */
        public static long timestamp(String source) {
            if (source == null || source.isEmpty()) {
                return 0;
            }
            try {
                return javax.xml.datatype.DatatypeFactory.newInstance()
                        .newXMLGregorianCalendar(source).toGregorianCalendar().getTimeInMillis();
            } catch (Exception e) {
                return 0;
            }
        }
    }

    /** The unchanged German provider. */
    WarningProvider DWD = new WarningProvider() {
        @Override public String id() { return ID_DWD; }
        @Override public String displayName() { return "Deutscher Wetterdienst (DWD)"; }
        @Override public int attributionTextResId() { return R.string.dwd_notice_app; }
        @Override public String attributionUrl() {
            return "https://www.dwd.de/EN/ourservices/opendata/opendata.html";
        }
        @Override public boolean covers(Weather.WeatherLocation location) {
            return location != null && Registry.DE.equals(
                    Registry.countryCodeFor(location.latitude, location.longitude));
        }
        @Override public String countryKey(Weather.WeatherLocation location) { return Registry.DE; }
        @Override public WarningRequest buildRequest(Weather.WeatherLocation location,
                                                      String countryKey, String language) {
            // The DWD ZIP URL remains assembled by getUrlString() so the existing HTTPS
            // and locale behaviour is byte-for-byte unchanged.
            return new WarningRequest(this, Registry.DE, null, APIReaders.CapCache.PREF_CAP_ETAG,
                    WarningProviderState.feedKey(this, Registry.DE), language, 0, 0);
        }
        @Override public WarningLevel levelFor(String providerLevel, String capSeverity) {
            return WarningLevel.fromDwdSeverity(capSeverity);
        }
        @Override public long minPollIntervalMillis() { return 0; }
        @Override public int maxCapDocumentsPerPoll() { return 0; }
        @Override public boolean isDwd() { return true; }
    };

    /** First international provider: public legacy Atom index plus immutable CAP documents. */
    WarningProvider METEOALARM = new WarningProvider() {
        @Override public String id() { return ID_METEOALARM; }
        @Override public String displayName() { return "MeteoAlarm"; }
        @Override public int attributionTextResId() { return R.string.warning_provider_attribution_meteoalarm; }
        @Override public String attributionUrl() { return "https://meteoalarm.org/"; }
        @Override public boolean covers(Weather.WeatherLocation location) {
            if (location == null) {
                return false;
            }
            String country = Registry.countryCodeFor(location.latitude, location.longitude);
            return country != null && !Registry.DE.equals(country)
                    && Registry.feedForCountry(country) != null;
        }
        @Override public String countryKey(Weather.WeatherLocation location) {
            return location == null ? null : Registry.countryCodeFor(location.latitude, location.longitude);
        }
        @Override public WarningRequest buildRequest(Weather.WeatherLocation location,
                                                      String countryKey, String language) {
            if (countryKey == null) {
                return null;
            }
            MeteoAlarmFeed feed = Registry.feedForCountry(countryKey);
            if (feed == null) {
                return null;
            }
            String key = WarningProviderState.feedKey(this, countryKey);
            return new WarningRequest(this, countryKey, feed.indexUrl(), key, key,
                    language, maxCapDocumentsPerPoll(), minPollIntervalMillis());
        }
        @Override public WarningLevel levelFor(String providerLevel, String capSeverity) {
            WarningLevel level = WarningLevel.fromAwarenessLevel(providerLevel);
            if (level != null) {
                return level;
            }
            // A feed without awareness_level still has a CAP severity; map it through the
            // same explicit three-colour vocabulary, never through DWD's area colour.
            return WarningLevel.fromAwarenessLevel("0; " + capSeverity);
        }
        @Override public long minPollIntervalMillis() { return 60_000L; }
        @Override public int maxCapDocumentsPerPoll() { return 12; }
        @Override public boolean isDwd() { return false; }
    };

    /** No provider for this station yet: keep the last good rows, fetch nothing. */
    WarningProvider NONE = new WarningProvider() {
        @Override public String id() { return ID_NONE; }
        @Override public String displayName() { return ""; }
        @Override public int attributionTextResId() { return R.string.dwd_notice_app; }
        @Override public String attributionUrl() { return null; }
        @Override public boolean covers(Weather.WeatherLocation location) { return false; }
        @Override public String countryKey(Weather.WeatherLocation location) { return null; }
        @Override public WarningRequest buildRequest(Weather.WeatherLocation location,
                                                      String countryKey, String language) { return null; }
        @Override public WarningLevel levelFor(String providerLevel, String capSeverity) { return null; }
        @Override public long minPollIntervalMillis() { return Long.MAX_VALUE; }
        @Override public int maxCapDocumentsPerPoll() { return 0; }
        @Override public boolean isDwd() { return false; }
    };
}

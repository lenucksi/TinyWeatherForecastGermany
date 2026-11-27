/**
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2020, 2021, 2022, 2023, 2024 Pawel Dube
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

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.w3c.dom.*;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

public class APIReaders {

    /**
     * Single seam for every DWD URL built by this app. The configured weather host is a bare
     * host name (see Settings), but older installs may still carry a scheme in the preference.
     * Normalising the host and always prepending https makes it impossible for a stored value
     * to turn a request into cleartext HTTP.
     *
     * Pure Java on purpose: unit tests can exercise it without Robolectric or a device.
     */
    public static final class HttpsUrl {
        public static final String HTTPS_SCHEME = "https://";
        // Only used to strip a legacy scheme from a stored host; never to open a connection.
        // Kept as a composed literal so a cleartext scheme cannot appear in URL construction.
        private static final String LEGACY_HTTP_SCHEME = "http" + ":" + "//";

        /**
         * Explicit allowlist of the DWD servers this app is allowed to contact. Both are data
         * endpoints used in this code base. www.dwd.de and kunden.dwd.de only appear in
         * documentation/about texts, not in data URLs, so they are deliberately not included.
         */
        private static final Set<String> ALLOWED_HOSTS = Collections.unmodifiableSet(
                new HashSet<String>(Arrays.asList("opendata.dwd.de", "maps.dwd.de")));

        /** Plain file-name pattern: no separators, no query/fragment delimiters, no escapes. */
        private static final Pattern SAFE_FILE_NAME = Pattern.compile("^[A-Za-z0-9_.-]+$");

        private HttpsUrl() {
        }

        public static String normalizeHost(String host) {
            if (host == null) {
                return null;
            }
            String result = host.trim().toLowerCase(Locale.ROOT);
            if (result.startsWith(HTTPS_SCHEME)) {
                result = result.substring(HTTPS_SCHEME.length());
            } else if (result.startsWith(LEGACY_HTTP_SCHEME)) {
                result = result.substring(LEGACY_HTTP_SCHEME.length());
            }
            while (result.endsWith("/")) {
                result = result.substring(0, result.length() - 1);
            }
            return result;
        }

        /**
         * The normalised host if, and only if, it is on the allowlist. Exact matching also
         * rejects userinfo tricks (host@attacker), ports, paths and query strings.
         */
        public static String validatedHost(String host) {
            String normalizedHost = normalizeHost(host);
            return (normalizedHost != null && ALLOWED_HOSTS.contains(normalizedHost))
                    ? normalizedHost : null;
        }

        public static boolean isAllowedHost(String host) {
            return validatedHost(host) != null;
        }

        /**
         * Builds an absolute https URL from an allowlisted host and an absolute path.
         * Returns null for a missing/disallowed host so no request can be made at all.
         */
        public static String build(String host, String path) {
            String normalizedHost = validatedHost(host);
            if (normalizedHost == null || path == null) {
                return null;
            }
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            return HTTPS_SCHEME + normalizedHost + path;
        }

        /** True for a single safe file name; rejects traversal (., ..) and query injection. */
        public static boolean isValidFileName(String name) {
            return name != null && SAFE_FILE_NAME.matcher(name).matches()
                    && !name.contains("..") && !name.equals(".");
        }

        /**
         * Extracts the last href from an Apache-style directory index and returns it only when
         * it is a safe file name. A missing link, path traversal, a query string or HTML noise
         * yields null instead of being concatenated into a request URL.
         */
        public static String validatedDirectoryEntry(String html) {
            if (html == null) {
                return null;
            }
            int tag = html.lastIndexOf("<a href=");
            if (tag < 0) {
                return null;
            }
            int quoteStart = html.indexOf('"', tag);
            if (quoteStart < 0) {
                return null;
            }
            int quoteEnd = html.indexOf('"', quoteStart + 1);
            if (quoteEnd < 0) {
                return null;
            }
            String candidate = html.substring(quoteStart + 1, quoteEnd);
            return isValidFileName(candidate) ? candidate : null;
        }
    }

    /**
     * TASK-30: the only XML parsing seam used by this app. DOM is kept (the existing code walks
     * the tree), but the parser is configured to refuse DOCTYPE declarations and any external
     * entity resolution. A pre-parse DTD screen is applied as an extra, platform-independent
     * guard so a malicious document is rejected before the parser sees it.
     *
     * Pure Java on purpose: fixtures can be parsed in JVM unit tests without a device.
     */
    public static final class SecureXml {
        private static final Pattern DTD_DECLARATION =
                Pattern.compile("<\\s*!\\s*(DOCTYPE|ENTITY)\\b", Pattern.CASE_INSENSITIVE);
        // Feature/property URIs are composed so no cleartext scheme literal appears in code
        // (TASK-41 guard). Android's compile-time XMLConstants does not expose the JAXP names.
        private static final String URI_SCHEME = "http" + ":" + "//";
        private static final String ACCESS_EXTERNAL_DTD =
                URI_SCHEME + "javax.xml.XMLConstants/property/accessExternalDTD";
        private static final String ACCESS_EXTERNAL_SCHEMA =
                URI_SCHEME + "javax.xml.XMLConstants/property/accessExternalSchema";

        private SecureXml() {
        }

        private static final String DISALLOW_DOCTYPE =
                URI_SCHEME + "apache.org/xml/features/disallow-doctype-decl";
        private static final String EXTERNAL_GENERAL_ENTITIES =
                URI_SCHEME + "xml.org/sax/features/external-general-entities";
        private static final String EXTERNAL_PARAMETER_ENTITIES =
                URI_SCHEME + "xml.org/sax/features/external-parameter-entities";
        private static final String LOAD_EXTERNAL_DTD =
                URI_SCHEME + "apache.org/xml/features/nonvalidating/load-external-dtd";

        public static DocumentBuilder newSecureDocumentBuilder() throws ParserConfigurationException {
            return newSecureDocumentBuilder(DocumentBuilderFactory.newInstance());
        }

        /**
         * Configures the supplied factory. Package-visible so a unit test can emulate a parser
         * with a reduced feature set (Android's parser rejects several JAXP feature URIs).
         *
         * Factory features are best-effort hardening only. The decisive protection is applied
         * by {@link #parse(String)}/{@link #parse(Reader)}: a DTD pre-screen rejects every
         * DOCTYPE/ENTITY declaration before the parser sees the document, and the entity
         * resolver below refuses to fetch anything external. Unsupported features must not be
         * fatal here, otherwise no DWD forecast, warning or text can ever be loaded on Android.
         */
        static DocumentBuilder newSecureDocumentBuilder(DocumentBuilderFactory factory)
                throws ParserConfigurationException {
            try {
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            } catch (ParserConfigurationException | IllegalArgumentException unsupported) {
                // Not exposed by every parser; the DTD pre-screen remains decisive.
            }
            setFeatureIfSupported(factory, DISALLOW_DOCTYPE, true);
            setFeatureIfSupported(factory, EXTERNAL_GENERAL_ENTITIES, false);
            setFeatureIfSupported(factory, EXTERNAL_PARAMETER_ENTITIES, false);
            setFeatureIfSupported(factory, LOAD_EXTERNAL_DTD, false);
            try {
                factory.setXIncludeAware(false);
                factory.setExpandEntityReferences(false);
            } catch (UnsupportedOperationException ignored) {
                // Optional on some parsers; DOCTYPE declarations are screened before parsing.
            }
            // JAXP access properties are optional on Android; tolerate parsers without them.
            setAttributeIfSupported(factory, ACCESS_EXTERNAL_DTD, "");
            setAttributeIfSupported(factory, ACCESS_EXTERNAL_SCHEMA, "");

            DocumentBuilder builder = factory.newDocumentBuilder();
            // Last line of defence: never fetch a URL or file named by an external entity.
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder;
        }

        private static boolean setFeatureIfSupported(DocumentBuilderFactory factory,
                                                     String name, boolean value) {
            try {
                factory.setFeature(name, value);
                return true;
            } catch (ParserConfigurationException | IllegalArgumentException unsupported) {
                return false;
            }
        }

        private static void setAttributeIfSupported(DocumentBuilderFactory factory, String name, String value) {
            try {
                factory.setAttribute(name, value);
            } catch (IllegalArgumentException ignored) {
                // Not implemented by this parser; the feature flags above still apply.
            }
        }

        public static boolean containsDtdDeclaration(String xml) {
            return xml != null && DTD_DECLARATION.matcher(xml).find();
        }

        public static Document parse(String xml) throws ParserConfigurationException, IOException, SAXException {
            if (xml == null) {
                throw new IOException("XML document is null");
            }
            if (containsDtdDeclaration(xml)) {
                throw new IOException("DOCTYPE/ENTITY declarations are forbidden in DWD XML data");
            }
            return newSecureDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        }

        /** Buffers the reader so the DTD screen covers the whole document before parsing. */
        public static Document parse(Reader reader) throws ParserConfigurationException, IOException, SAXException {
            if (reader == null) {
                throw new IOException("XML reader is null");
            }
            StringBuilder stringBuilder = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                stringBuilder.append(buffer, 0, read);
            }
            return parse(stringBuilder.toString());
        }
    }

    /**
     * TASK-31: one HTTP configuration and one request path for every DWD download. All URLs
     * reaching this class were built by HttpsUrl (https only, DWD allowlist); redirects are
     * disabled so a response cannot move the request to an unvalidated host. Retries are
     * bounded and use capped exponential backoff; a Cancellation can abort a running request.
     *
     * Pure Java on purpose: the configuration, backoff and retry logic is unit-testable.
     */
    public static final class Http {
        public static final int CONNECT_TIMEOUT_MS = 10_000;
        public static final int READ_TIMEOUT_MS = 20_000;
        public static final int MAX_ATTEMPTS = 3;
        public static final long BASE_BACKOFF_MS = 200L;
        public static final long MAX_BACKOFF_MS = 2_000L;

        private Http() {
        }

        /** Applies the central timeout/security settings to a fresh connection. */
        public static void configure(HttpsURLConnection connection) {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
        }

        public static HttpsURLConnection open(URL url) throws IOException {
            if (url == null) {
                throw new IOException("No URL to request");
            }
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IOException("Only https URLs are allowed");
            }
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            configure(connection);
            return connection;
        }

        /** Capped exponential backoff: 200 ms, 400 ms, ... never above MAX_BACKOFF_MS. */
        public static long backoffDelayMs(int retry) {
            if (retry <= 0) {
                return 0;
            }
            long delay = BASE_BACKOFF_MS;
            for (int i = 1; i < retry && delay < MAX_BACKOFF_MS; i++) {
                delay = Math.min(delay * 2, MAX_BACKOFF_MS);
            }
            return delay;
        }

        private static boolean isRetryable(IOException e) {
            return e instanceof SocketTimeoutException
                    || e instanceof ConnectException
                    || e instanceof UnknownHostException
                    || e instanceof SSLException;
        }

        private static void throwIfCancelled(Cancellation cancellation) throws IOException {
            if (cancellation != null && cancellation.isCancelled()) {
                throw new IOException("Request cancelled");
            }
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new IOException("Request interrupted");
            }
        }

        /** Bounded retry helper; also used by tests with a fake operation. */
        public static <T> T withRetry(Callable<T> operation, Cancellation cancellation) throws IOException {
            if (operation == null) {
                throw new IOException("No request operation");
            }
            IOException last = null;
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                throwIfCancelled(cancellation);
                try {
                    return operation.call();
                } catch (IOException e) {
                    last = e;
                    if (!isRetryable(e)) {
                        throw e;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Request interrupted", e);
                } catch (Exception e) {
                    throw new IOException("Request failed", e);
                }
                if (attempt + 1 < MAX_ATTEMPTS) {
                    long delay = backoffDelayMs(attempt + 1);
                    if (delay > 0) {
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Request interrupted during backoff", e);
                        }
                    }
                }
            }
            throw last;
        }

        public static InputStream get(URL url) throws IOException {
            return get(url, null);
        }

        /** The single download path: timeouts + bounded retries + cancellation. */
        public static InputStream get(URL url, Cancellation cancellation) throws IOException {
            return withRetry(() -> {
                HttpsURLConnection connection = open(url);
                if (cancellation != null) {
                    cancellation.register(connection);
                }
                try {
                    InputStream stream = new BufferedInputStream(connection.getInputStream());
                    // Keep the connection registered until the caller closes the stream so
                    // cancel() can disconnect a request that is already streaming.
                    return new FilterInputStream(stream) {
                        @Override
                        public void close() throws IOException {
                            try {
                                super.close();
                            } finally {
                                if (cancellation != null) {
                                    cancellation.unregister(connection);
                                }
                                connection.disconnect();
                            }
                        }
                    };
                } catch (IOException e) {
                    if (cancellation != null) {
                        cancellation.unregister(connection);
                    }
                    connection.disconnect();
                    throw e;
                }
            }, cancellation);
        }
    }

    /**
     * A background job whose owner can stop it when the owning UI element is destroyed.
     * Implementations must route the cancellation into their TASK-31 {@link Cancellation}
     * token so in-flight HTTPS requests are disconnected as well as the worker interrupted.
     */
    public interface CancellableTask extends Runnable {
        void cancel();
    }

    /**
     * Per-job cancellation token. Cancelling disconnects all currently open connections of
     * that job and prevents further attempts; the network layer also honours thread interrupts
     * so callers that stop an executor without a token still cancel in-flight requests.
     */
    public static final class Cancellation {
        private final Set<HttpsURLConnection> active =
                Collections.newSetFromMap(new ConcurrentHashMap<HttpsURLConnection, Boolean>());
        private volatile boolean cancelled;

        public boolean isCancelled() {
            return cancelled;
        }

        public void cancel() {
            cancelled = true;
            for (HttpsURLConnection connection : active) {
                try {
                    connection.disconnect();
                } catch (Exception ignored) {
                    // Best effort: the timeout still bounds the request.
                }
            }
            active.clear();
        }

        /** Reuse a static-token job for a new run after a previous cancellation. */
        public void reset() {
            cancel();
            cancelled = false;
        }

        void register(HttpsURLConnection connection) throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                connection.disconnect();
                throw new IOException("Request cancelled");
            }
            active.add(connection);
        }

        void unregister(HttpsURLConnection connection) {
            active.remove(connection);
        }
    }

   public static class WeatherWarningsRunnable implements CancellableTask {

       public Context context;
       public Weather.WeatherLocation weatherLocation;

       private final Cancellation cancellation = new Cancellation();

       // all polling times should be the same per poll to allow for logic checking if outdated
       private long pollingTime = Calendar.getInstance().getTimeInMillis();

       public WeatherWarningsRunnable(Context context) {
           this.context = context;
           weatherLocation = WeatherSettings.getSetStationLocation(context);
       }

       public void cancel() {
           cancellation.cancel();
       }

       public long getTimeStampFromString(String source){
           if (source != null){
               SimpleDateFormat kml_dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
               kml_dateFormat.setLenient(true);
               try {
                   Date date = kml_dateFormat.parse(source);
                   return date.getTime();
               } catch (ParseException e){
                   PrivateLog.log(context,PrivateLog.DATA,PrivateLog.ERR,"Malformed timestamp in warning ("+source+"):"+e.getMessage());
                   return 0;
               }
           }
           return 0;
       }

       private String getValue(Element element, String tag){
           NodeList nl2 = element.getElementsByTagName(tag);
           if (nl2.getLength()>0){
               return nl2.item(0).getTextContent();
           }
           return null;
       }

       private WeatherWarning parseWarning(String zipfile) throws ParserConfigurationException, IOException, SAXException {
           WeatherWarning warning = new WeatherWarning();
           // TASK-30: all warning XML goes through the hardened parser (no DTD, no entities).
           Document document = SecureXml.parse(zipfile);
           NodeList nl = document.getElementsByTagName("identifier");
           warning.polling_time = pollingTime;
           for (int i=0;i<nl.getLength(); i++){
               // should be only one, but we take the latest
               Element elIdentifier = (Element) nl.item(i);
               warning.identifier =  elIdentifier.getFirstChild().getNodeValue();
           }

           nl = document.getElementsByTagName("sender");
           for (int i=0;i<nl.getLength(); i++){
               // should be only one, but we take the latest
               Element element = (Element) nl.item(i);
               warning.sender =  element.getFirstChild().getNodeValue();
           }

           nl = document.getElementsByTagName("sent");
           for (int i=0;i<nl.getLength(); i++){
               // should be only one, but we take the latest
               Element element = (Element) nl.item(i);
               String time =  element.getFirstChild().getNodeValue();
               warning.sent = getTimeStampFromString(time);
           }

           nl = document.getElementsByTagName("status");
           for (int i=0;i<nl.getLength(); i++){
               // should be only one, but we take the latest
               Element element = (Element) nl.item(i);
               warning.status =  element.getFirstChild().getNodeValue();
           }

           nl = document.getElementsByTagName("msgType");
           for (int i=0;i<nl.getLength(); i++){
               // should be only one, but we take the latest
               Element element = (Element) nl.item(i);
               warning.msgType =  element.getFirstChild().getNodeValue();
           }

           nl = document.getElementsByTagName("source");
           for (int i=0;i<nl.getLength(); i++){
               // should be only one, but we take the latest
               Element element = (Element) nl.item(i);
               warning.source =  element.getFirstChild().getNodeValue();
           }

           nl = document.getElementsByTagName("scope");
           for (int i=0;i<nl.getLength(); i++){
               // should be only one, but we take the latest
               Element element = (Element) nl.item(i);
               warning.scope =  element.getFirstChild().getNodeValue();
           }

           nl = document.getElementsByTagName("code");
           warning.codes = new ArrayList<String>();
           for (int i=0;i<nl.getLength(); i++){
               // should be only one, but we take the latest
               Element element = (Element) nl.item(i);
               warning.codes.add(element.getFirstChild().getNodeValue());
           }

           nl = document.getElementsByTagName("references");
           warning.references = new ArrayList<String>();
           for (int i=0;i<nl.getLength(); i++){
               // usually 1, but seems to be intended as multiple. We handle it in an arraylist to be safe.
               Element element = (Element) nl.item(i);
               String reference = element.getFirstChild().getNodeValue();
               warning.references.add(reference.substring(reference.indexOf(",")+1,reference.lastIndexOf(",")));
           }

           nl = document.getElementsByTagName("info");
           for (int n=0; n< nl.getLength(); n++){
               Node node = nl.item(n);
               if (node.getNodeType() == Node.ELEMENT_NODE){
                   Element element = (Element) node;
                   warning.language = getValue(element,"language");
                   warning.category = getValue(element,"category");
                   warning.event = getValue(element,"event");
                   warning.responseType = getValue(element,"responseType");
                   warning.urgency = getValue(element,"urgency");
                   warning.severity = getValue(element,"severity");
                   warning.certainty = getValue(element,"certainty");
                   warning.effective = getTimeStampFromString(getValue(element,"effective"));
                   warning.onset = getTimeStampFromString(getValue(element,"onset"));
                   warning.expires = getTimeStampFromString(getValue(element,"expires"));
                   warning.senderName = getValue(element,"senderName");
                   warning.headline = getValue(element,"headline");
                   warning.description = getValue(element,"description");
                   warning.instruction = getValue(element,"instruction");
                   warning.web = getValue(element,"web");
                   warning.contact = getValue(element,"contact");
               }
           }

           nl = document.getElementsByTagName("eventCode");
           warning.groups = new ArrayList<String>();
           for (int n=0; n< nl.getLength(); n++){
               Node node = nl.item(n);
               if (node.getNodeType() == Node.ELEMENT_NODE){
                   Element element = (Element) node;
                   String valuename = getValue(element,"valueName");
                   String value = getValue(element,"value");
                   switch (valuename){
                       case "PROFILE_VERSION": warning.profile_version = value; break;
                       case "LICENSE": warning.license = value; break;
                       case "II": warning.ii = value; break;
                       case "AREA_COLOR": warning.area_color=value; break;
                       case "GROUP": warning.groups.add(value); break;
                   }
               }
           }

           nl = document.getElementsByTagName("parameter");
           warning.parameter_names = new ArrayList<String>();
           warning.parameter_values = new ArrayList<String>();
           for (int n=0; n< nl.getLength(); n++){
               Node node = nl.item(n);
               if (node.getNodeType() == Node.ELEMENT_NODE){
                   Element element = (Element) node;
                   warning.parameter_names.add(getValue(element,"valueName"));
                   warning.parameter_values.add(getValue(element,"value"));
               }
           }

           nl = document.getElementsByTagName("area");
           warning.polygons = new ArrayList<String>();
           warning.excluded_polygons = new ArrayList<String>();
           warning.area_names = new ArrayList<String>();
           warning.area_warncellIDs = new ArrayList<String>();
           for (int n=0; n< nl.getLength(); n++){
               Node node = nl.item(n);
               if (node.getNodeType() == Node.ELEMENT_NODE){
                   Element element = (Element) node;
                   String polygon = getValue(element,"polygon");
                   if (polygon!=null){
                       warning.polygons.add(polygon);
                   } else {
                       warning.area_names.add(getValue(element,"areaDesc"));
                       NodeList subnodelist = element.getElementsByTagName("geocode");
                       for (int mm=0; mm<subnodelist.getLength(); mm++){
                           Node subnode = subnodelist.item(mm);
                           if (subnode.getNodeType() == Node.ELEMENT_NODE){
                               Element subelement = (Element) subnode;
                               String valueName = getValue(subelement,"valueName");
                               String value = getValue(subelement,"value");
                               if (valueName.equals("EXCLUDE_POLYGON")){
                                   if (value!=null){
                                       warning.excluded_polygons.add(value);
                                   }
                               }
                               if (valueName.equals("WARNCELLID")){
                                   if (value == null){
                                       value="-";
                                   }
                                   warning.area_warncellIDs.add(value);
                               }
                           }
                       }
                   }
               }
           }

           return warning;
       }

       @SuppressWarnings("deprecation")
       private String getUrlString(Context context){
           String country = "EN";
           if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.N){
               country = context.getResources().getConfiguration().getLocales().get(0).getCountry();
           } else {
               country = context.getResources().getConfiguration().locale.getCountry();
           }
           // 3.2.1 Gemeindebasis mit DWD Aktualisierungsstrategie
           final String C_PATH = "/weather/alerts/cap/COMMUNEUNION_DWD_STAT/Z_CAP_C_EDZW_LATEST_PVW_STATUS_PREMIUMDWD_COMMUNEUNION_";
           final String C_LAST  = ".zip";
           switch (country){
               case "FR": return HttpsUrl.build(WeatherSettings.getWeatherUrl(context),C_PATH+"FR"+C_LAST);
               case "ES": return HttpsUrl.build(WeatherSettings.getWeatherUrl(context),C_PATH+"ES"+C_LAST);
               case "DE": return HttpsUrl.build(WeatherSettings.getWeatherUrl(context),C_PATH+"DE"+C_LAST);
           }
           return HttpsUrl.build(WeatherSettings.getWeatherUrl(context),C_PATH+"EN"+C_LAST);
       }

       private InputStream getWeatherWarningInputStream() throws IOException {
           // Warnings are always polled over TLS. A failed handshake is reported, never
           // retried over cleartext (the network security config also forbids it).
           try {
               String urlString = getUrlString(context);
               if (urlString==null){
                   throw new IOException("Disallowed weather data host; not requesting warnings.");
               }
               URL url = new URL(urlString);
               return Http.get(url, cancellation);
           } catch (MalformedURLException e){
               PrivateLog.log(context,PrivateLog.DATA,PrivateLog.ERR,"Malformed URL for DWD resource:"+e.getMessage());
               throw e;
           } catch (SSLException e){
               PrivateLog.log(context,PrivateLog.DATA,PrivateLog.ERR,"ssl connection could not be established, but http is not allowed.");
               throw e;
           }
       }

       public ArrayList<WeatherWarning> doInBackground() {
           ArrayList<WeatherWarning> warnings = new ArrayList<WeatherWarning>();
           try {
               InputStream inputStream = new BufferedInputStream(getWeatherWarningInputStream());
               ZipInputStream zipInputStream = new ZipInputStream(inputStream);
               // iterate through the warnings; each warning is a file
               int warnings_counter = 0;
               while (zipInputStream.getNextEntry() != null){
                   warnings_counter ++;
                   StringBuffer stringBuffer = new StringBuffer();
                   byte[] buffer = new byte[4096];
                   int l=0;
                   while ((l=zipInputStream.read(buffer))>0){
                       stringBuffer.append(new String(buffer,0,l));
                   }
                   try {
                       WeatherWarning warning = parseWarning(stringBuffer.toString());
                       warnings.add(warning);
                   } catch (ParserConfigurationException e){
                       PrivateLog.log(context,PrivateLog.DATA,PrivateLog.ERR,"Could not configure parser, skipping a warning:"+e.getMessage());
                   } catch (IOException e){
                       PrivateLog.log(context,PrivateLog.DATA,PrivateLog.ERR,"I/O Error, skipping a warning:"+e.getMessage());
                   } catch (SAXException e){
                       PrivateLog.log(context,PrivateLog.DATA,PrivateLog.ERR,"Possibly malformed warning, skipping a warning:"+e.getMessage());
                   }
               }
           } catch (MalformedURLException e){
               PrivateLog.log(context,PrivateLog.DATA,PrivateLog.ERR,"Malformed URL for DWD resource:"+e.getMessage());
               return null;
           } catch (IOException e){
               PrivateLog.log(context,PrivateLog.DATA,PrivateLog.ERR,"Unable to open DWD stream:"+e.getMessage());
               return null;
           }
           return warnings;
       }

       //* Override to do something before getting data starts

       public void onStart(){
           // do something
       }

       public void onNegativeResult(){
           // do nothing at the moment.
       }

       public void onPositiveResult(){
           // do nothing at the moment.
       }

       public void onPositiveResult(ArrayList<WeatherWarning> warnings){
           onPositiveResult();
       }

       private void onPostExecute(ArrayList<WeatherWarning> warnings) {
           if (warnings!=null){
               WeatherWarnings.cleanWeatherWarningsDatabase(context);
               WeatherWarnings.writeWarningsToDatabase(context,warnings);
               onPositiveResult(warnings);
           } else {
               PrivateLog.log(context,PrivateLog.DATA,PrivateLog.ERR,"getting warnings failed.");
               onNegativeResult();
           }
       }

       @Override
       public void run() {
           onStart();
           pollingTime = Calendar.getInstance().getTimeInMillis();
           ArrayList<WeatherWarning> weatherWarnings = doInBackground();
           onPostExecute(weatherWarnings);
       }
   }

    public static class WeatherForecastRunnable implements CancellableTask {

        private Context context;
        private ArrayList<Weather.WeatherLocation> weatherLocations;
        public boolean ssl_exception = false;
        private final Cancellation cancellation = new Cancellation();

        public void cancel() {
            cancellation.cancel();
        }

        private String[] seperateValues(String s){
            String[] resultarray = new String[Weather.DATA_SIZE];
            int index =0;
            while (String.valueOf(s.charAt(index)).equals(" ")){
                index++;
            }
            s=s.substring(index);
            resultarray = s.split(" +");
            return resultarray;
        }

        private String[] assigntoRaw(final Element element){
            String[] result = new String[Weather.DATA_SIZE];
            NodeList values = element.getElementsByTagName("dwd:value");
            for (int j=0; j<values.getLength(); j++){
                Element value_element = (Element) values.item(j);
                String value_string = value_element.getFirstChild().getNodeValue();
                result = seperateValues(value_string);
            }
            return result;
        }

        public WeatherForecastRunnable(Context context, ArrayList<Weather.WeatherLocation> weatherLocations){
            this.context = context;
            this.weatherLocations = weatherLocations;
        }

        public void setWeatherLocations(ArrayList<Weather.WeatherLocation> weatherLocations) {
            this.weatherLocations = weatherLocations;
        }

        public String getLastestDMOUrl(Context context, String stationName) throws IOException {
            if (!HttpsUrl.isValidFileName(stationName)){
                return null;
            }
            String basicUrl = HttpsUrl.build(WeatherSettings.getWeatherUrl(context),
                    "/weather/local_forecasts/dmo/icon-eu/single_stations/" + stationName + "/kmz/");
            if (basicUrl==null){
                return null;
            }
            InputStream inputStream = null;
            try {
                URL url = new URL(basicUrl);
                inputStream = Http.get(url, cancellation);
            } catch (MalformedURLException e){
                return null;
            } catch (SSLException e) {
                ssl_exception = true;
                throw e;
            } catch (IOException e) {
                throw e;
            } catch (Exception e){
                return null;
            }
            if (inputStream!=null){
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
                StringBuilder stringBuilder = new StringBuilder();
                for (String l; (l=bufferedReader.readLine())!=null;){
                    stringBuilder.append(l);
                }
                bufferedReader.close();
                // The directory index is remote input: validate the scraped entry before it
                // becomes part of a request URL (traversal and query injection must not pass).
                String fileName = HttpsUrl.validatedDirectoryEntry(stringBuilder.toString());
                if (fileName==null){
                    PrivateLog.log(context,PrivateLog.DATA,PrivateLog.ERR,"Rejected invalid DMO directory entry.");
                    return null;
                }
                return fileName;
            } else {
            }
            return null;
        }


        private InputStream getWeatherInputStream(Weather.WeatherLocation weatherLocation) throws IOException {
            String stationName = weatherLocation.getName().replace("*","");
            if (!HttpsUrl.isValidFileName(stationName)){
                throw new IOException("Invalid station name; not requesting weather data.");
            }
            String weatherPath;
            // stationType MOS is default
            weatherPath = "/weather/local_forecasts/mos/MOSMIX_L/single_stations/"+stationName+"/kml/MOSMIX_L_LATEST_"+stationName+".kmz";
            // change to DMO if applicable
            if (weatherLocation.type==RawWeatherInfo.Source.DMO){
                String latestFile = getLastestDMOUrl(context,stationName);
                if (latestFile==null){
                    throw new IOException("No valid DMO directory entry; not requesting a forecast file.");
                }
                weatherPath = "/weather/local_forecasts/dmo/icon-eu/single_stations/"+stationName+"/kmz/"+latestFile;
            }
            String weather_url = HttpsUrl.build(WeatherSettings.getWeatherUrl(context),weatherPath);
            if (weather_url==null){
                throw new IOException("Disallowed weather data host; not requesting weather data.");
            }
            // PrivateLog.log(context,Tag.SERVICE2,"URL: "+weather_url);
            URL url = new URL(weather_url);
            try {
                return Http.get(url, cancellation);
            } catch (SSLException e){
                ssl_exception = true;
                PrivateLog.log(context,PrivateLog.DATA,PrivateLog.ERR,"Error: ssl connection could not be established, but http is not allowed.");
                throw e;
            }
        }

        private RawWeatherInfo updateWeatherLocationData(Weather.WeatherLocation weatherLocation) {
            try{
                InputStream inputStream = new BufferedInputStream(getWeatherInputStream(weatherLocation));
                ZipInputStream zipInputStream = new ZipInputStream(inputStream);
                zipInputStream.getNextEntry();
                // init new RawWeatherInfo instance to fill with data
                RawWeatherInfo rawWeatherInfo = new RawWeatherInfo();
                // set data source
                rawWeatherInfo.source = RawWeatherInfo.Source.MOS;   // MOSMIX data
                // populate name from settings, as name is file-name in API but not repeated in the content
                rawWeatherInfo.weatherLocation = new Weather.WeatherLocation(weatherLocation);
                try {
                    // TASK-30: KML from the DWD archive is remote input as well.
                    Document document = SecureXml.parse(new InputStreamReader(zipInputStream, StandardCharsets.UTF_8));
                    rawWeatherInfo.weatherLocation.setDescription("?");
                    // get sensor description, usually city name. This should be equal to weatherLocation.description,
                    // but we take it from the api to be sure nothing changed and the right city gets displayed!

                    NodeList placemark_nodes = document.getElementsByTagName("kml:description");
                    for (int i=0;i<placemark_nodes.getLength(); i++){
                        // should be only one, but we take the latest
                        Element placemark_element = (Element) placemark_nodes.item(i);
                        String description = placemark_element.getFirstChild().getNodeValue();
                        rawWeatherInfo.weatherLocation.setDescription(description);
                        if (!rawWeatherInfo.weatherLocation.hasAlternateDescription()){
                            String alternateDescription = WeatherLocationManager.getDescriptionAlternate(context,rawWeatherInfo.weatherLocation);
                            if (alternateDescription!=null) {
                                rawWeatherInfo.weatherLocation.setDescriptionAlternate(alternateDescription);
                                WeatherSettings.setDescriptionAlternate(context,alternateDescription);
                            }
                        }
                    }

                    // get the issue time; should be only one, but we take the latest
                    NodeList issuetime_nodes = document.getElementsByTagName("dwd:IssueTime");
                    for (int i=0;i<issuetime_nodes.getLength(); i++){
                        // should be only one, but we take the latest
                        Element issuetime_element = (Element) issuetime_nodes.item(i);
                        String timetext = issuetime_element.getFirstChild().getNodeValue();
                        timetext = timetext.replace("T"," ");
                        rawWeatherInfo.timetext = timetext;
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                        try {
                            Date date = sdf.parse(timetext);
                            rawWeatherInfo.timestamp = date.getTime();
                        } catch (ParseException e){
                            rawWeatherInfo.timestamp = 0;
                        }
                    }

                    NodeList timesteps = document.getElementsByTagName("dwd:TimeStep");
                    for (int i=0; i<timesteps.getLength(); i++){
                        Element element = (Element) timesteps.item(i);
                        // replace formatting to work like expected with UTC time later. This is done here once to
                        // increase performance.
                        rawWeatherInfo.timesteps[i] = element.getFirstChild().getNodeValue().replace("Z","+0000");
                        rawWeatherInfo.elements = i;
                    }
                    NodeList forecast = document.getElementsByTagName("dwd:Forecast");
                    for (int i=0; i<forecast.getLength(); i++){
                        Element element = (Element) forecast.item(i);
                        String type     = element.getAttribute("dwd:elementName");
                        switch (type){
                            case "TTT": rawWeatherInfo.TTT = assigntoRaw(element); break;
                            case "E_TTT": rawWeatherInfo.E_TTT = assigntoRaw(element); break;
                            case "T5cm": rawWeatherInfo.T5cm = assigntoRaw(element); break;
                            case "Td": rawWeatherInfo.Td = assigntoRaw(element); break;
                            case "E_Td": rawWeatherInfo.E_Td = assigntoRaw(element); break;
                            case "Tx": rawWeatherInfo.Tx = assigntoRaw(element); break;
                            case "Tn": rawWeatherInfo.Tn = assigntoRaw(element); break;
                            case "TM": rawWeatherInfo.TM = assigntoRaw(element); break;
                            case "TG": rawWeatherInfo.TG = assigntoRaw(element); break;
                            case "DD": rawWeatherInfo.DD = assigntoRaw(element); break;
                            case "E_DD": rawWeatherInfo.E_DD = assigntoRaw(element); break;
                            case "FF": rawWeatherInfo.FF = assigntoRaw(element); break;
                            case "E_FF": rawWeatherInfo.E_FF = assigntoRaw(element); break;
                            case "FX1": rawWeatherInfo.FX1 = assigntoRaw(element); break;
                            case "FX3": rawWeatherInfo.FX3 = assigntoRaw(element); break;
                            case "FXh": rawWeatherInfo.FXh = assigntoRaw(element); break;
                            case "FXh25": rawWeatherInfo.FXh25 = assigntoRaw(element); break;
                            case "FXh40": rawWeatherInfo.FXh40 = assigntoRaw(element); break;
                            case "FXh55": rawWeatherInfo.FXh55 = assigntoRaw(element); break;
                            case "FX625": rawWeatherInfo.FX625 = assigntoRaw(element); break;
                            case "FX640": rawWeatherInfo.FX640 = assigntoRaw(element); break;
                            case "FX655": rawWeatherInfo.FX655 = assigntoRaw(element); break;
                            case "RR1": rawWeatherInfo.RR1 = assigntoRaw(element); break;
                            case "RR1c": rawWeatherInfo.RR1c = assigntoRaw(element); break;
                            case "RRL1c": rawWeatherInfo.RRL1c = assigntoRaw(element); break;
                            case "RR3": rawWeatherInfo.RR3 = assigntoRaw(element); break;
                            case "RR6": rawWeatherInfo.RR6 = assigntoRaw(element); break;
                            case "RR3c": rawWeatherInfo.RR3c = assigntoRaw(element); break;
                            case "RR6c": rawWeatherInfo.RR6c = assigntoRaw(element); break;
                            case "RRhc": rawWeatherInfo.RRhc = assigntoRaw(element); break;
                            case "RRdc": rawWeatherInfo.RRdc = assigntoRaw(element); break;
                            case "RRS1c": rawWeatherInfo.RRS1c = assigntoRaw(element); break;
                            case "RRS3c": rawWeatherInfo.RRS3c = assigntoRaw(element); break;
                            case "R101": rawWeatherInfo.R101 = assigntoRaw(element); break;
                            case "R102": rawWeatherInfo.R102 = assigntoRaw(element); break;
                            case "R103": rawWeatherInfo.R103 = assigntoRaw(element); break;
                            case "R105": rawWeatherInfo.R105 = assigntoRaw(element); break;
                            case "R107": rawWeatherInfo.R107 = assigntoRaw(element); break;
                            case "R110": rawWeatherInfo.R110 = assigntoRaw(element); break;
                            case "R120": rawWeatherInfo.R120 = assigntoRaw(element); break;
                            case "R130": rawWeatherInfo.R130 = assigntoRaw(element); break;
                            case "R150": rawWeatherInfo.R150 = assigntoRaw(element); break;
                            case "RR1o1": rawWeatherInfo.RR1o1 = assigntoRaw(element); break;
                            case "RR1w1": rawWeatherInfo.RR1w1 = assigntoRaw(element); break;
                            case "RR1u1": rawWeatherInfo.RR1u1 = assigntoRaw(element); break;
                            case "R600": rawWeatherInfo.R600 = assigntoRaw(element); break;
                            case "Rh00": rawWeatherInfo.Rh00 = assigntoRaw(element); break;
                            case "R602": rawWeatherInfo.R602 = assigntoRaw(element); break;
                            case "Rh02": rawWeatherInfo.Rh02 = assigntoRaw(element); break;
                            case "Rd02": rawWeatherInfo.Rd02 = assigntoRaw(element); break;
                            case "R610": rawWeatherInfo.R610 = assigntoRaw(element); break;
                            case "Rh10": rawWeatherInfo.Rh10 = assigntoRaw(element); break;
                            case "R650": rawWeatherInfo.R650 = assigntoRaw(element); break;
                            case "Rh50": rawWeatherInfo.Rh50 = assigntoRaw(element); break;
                            case "Rd00": rawWeatherInfo.Rd00 = assigntoRaw(element); break;
                            case "Rd10": rawWeatherInfo.Rd10 = assigntoRaw(element); break;
                            case "Rd50": rawWeatherInfo.Rd50 = assigntoRaw(element); break;
                            case "wwPd": rawWeatherInfo.wwPd = assigntoRaw(element); break;
                            case "DRR1": rawWeatherInfo.DRR1 = assigntoRaw(element); break;
                            case "wwZ": rawWeatherInfo.wwZ = assigntoRaw(element); break;
                            case "wwZ6": rawWeatherInfo.wwZ6 = assigntoRaw(element); break;
                            case "wwZh": rawWeatherInfo.wwZh = assigntoRaw(element); break;
                            case "wwD": rawWeatherInfo.wwD = assigntoRaw(element); break;
                            case "wwD6": rawWeatherInfo.wwD6 = assigntoRaw(element); break;
                            case "wwDh": rawWeatherInfo.wwDh = assigntoRaw(element); break;
                            case "wwC": rawWeatherInfo.wwC = assigntoRaw(element); break;
                            case "wwC6": rawWeatherInfo.wwC6 = assigntoRaw(element); break;
                            case "wwCh": rawWeatherInfo.wwCh = assigntoRaw(element); break;
                            case "wwT": rawWeatherInfo.wwT = assigntoRaw(element); break;
                            case "wwT6": rawWeatherInfo.wwT6 = assigntoRaw(element); break;
                            case "wwTh": rawWeatherInfo.wwTh = assigntoRaw(element); break;
                            case "wwTd": rawWeatherInfo.wwTd = assigntoRaw(element); break;
                            case "wwL": rawWeatherInfo.wwL = assigntoRaw(element); break;
                            case "wwL6": rawWeatherInfo.wwL6 = assigntoRaw(element); break;
                            case "wwLh": rawWeatherInfo.wwLh = assigntoRaw(element); break;
                            case "wwS": rawWeatherInfo.wwS = assigntoRaw(element); break;
                            case "wwS6": rawWeatherInfo.wwS6 = assigntoRaw(element); break;
                            case "wwSh": rawWeatherInfo.wwSh = assigntoRaw(element); break;
                            case "wwF": rawWeatherInfo.wwF = assigntoRaw(element); break;
                            case "wwF6": rawWeatherInfo.wwF6 = assigntoRaw(element); break;
                            case "wwFh": rawWeatherInfo.wwFh = assigntoRaw(element); break;
                            case "wwP": rawWeatherInfo.wwP = assigntoRaw(element); break;
                            case "wwP6": rawWeatherInfo.wwP6 = assigntoRaw(element); break;
                            case "wwPh": rawWeatherInfo.wwPh = assigntoRaw(element); break;
                            case "VV10": rawWeatherInfo.VV10 = assigntoRaw(element); break;
                            case "ww": rawWeatherInfo.ww = assigntoRaw(element); break;
                            case "ww3": rawWeatherInfo.ww3 = assigntoRaw(element); break;
                            case "W1W2": rawWeatherInfo.W1W2 = assigntoRaw(element); break;
                            case "WPc11": rawWeatherInfo.WPc11 = assigntoRaw(element); break;
                            case "WPc31": rawWeatherInfo.WPc31 = assigntoRaw(element); break;
                            case "WPc61": rawWeatherInfo.WPc61 = assigntoRaw(element); break;
                            case "WPch1": rawWeatherInfo.WPch1 = assigntoRaw(element); break;
                            case "WPcd1": rawWeatherInfo.WPcd1 = assigntoRaw(element); break;
                            case "N": rawWeatherInfo.N = assigntoRaw(element); break;
                            case "Neff": rawWeatherInfo.Neff = assigntoRaw(element); break;
                            case "N05": rawWeatherInfo.N05 = assigntoRaw(element); break;
                            case "Nl": rawWeatherInfo.Nl = assigntoRaw(element); break;
                            case "Nm": rawWeatherInfo.Nm = assigntoRaw(element); break;
                            case "Nh": rawWeatherInfo.Nh = assigntoRaw(element); break;
                            case "Nlm": rawWeatherInfo.Nlm = assigntoRaw(element); break;
                            case "H_BsC": rawWeatherInfo.H_BsC = assigntoRaw(element); break;
                            case "PPPP": rawWeatherInfo.PPPP = assigntoRaw(element); break;
                            case "E_PPP": rawWeatherInfo.E_PPP = assigntoRaw(element); break;
                            case "RadS1": rawWeatherInfo.RadS1 = assigntoRaw(element); break;
                            case "RadS3": rawWeatherInfo.RadS3 = assigntoRaw(element); break;
                            case "RRad1": rawWeatherInfo.RRad1 = assigntoRaw(element); break;
                            case "Rad1h": rawWeatherInfo.Rad1h = assigntoRaw(element); break;
                            case "RadL3": rawWeatherInfo.RadL3 = assigntoRaw(element); break;
                            case "VV": rawWeatherInfo.VV = assigntoRaw(element); break;
                            case "D1": rawWeatherInfo.D1 = assigntoRaw(element); break;
                            case "SunD": rawWeatherInfo.SunD = assigntoRaw(element); break;
                            case "SunD3": rawWeatherInfo.SunD3 = assigntoRaw(element); break;
                            case "RSunD": rawWeatherInfo.RSunD = assigntoRaw(element); break;
                            case "PSd00": rawWeatherInfo.PSd00 = assigntoRaw(element); break;
                            case "PSd30": rawWeatherInfo.PSd30 = assigntoRaw(element); break;
                            case "PSd60": rawWeatherInfo.PSd60 = assigntoRaw(element); break;
                            case "wwM": rawWeatherInfo.wwM = assigntoRaw(element); break;
                            case "wwM6": rawWeatherInfo.wwM6 = assigntoRaw(element); break;
                            case "wwMh": rawWeatherInfo.wwMh = assigntoRaw(element); break;
                            case "wwMd": rawWeatherInfo.wwMd = assigntoRaw(element); break;
                            case "PEvap": rawWeatherInfo.PEvap = assigntoRaw(element); break;
                        }
                    }
                    PrivateLog.log(context, PrivateLog.DATA,PrivateLog.INFO,"Weather elements read: "+rawWeatherInfo.elements);
                    return rawWeatherInfo;
                } catch (Exception e){
                    PrivateLog.log(context, PrivateLog.DATA,PrivateLog.ERR,
                            "Failed to parse forecast data: "+e.getClass().getSimpleName()+": "+e.getMessage());
                }
            } catch (IOException e){
                PrivateLog.log(context, PrivateLog.DATA,PrivateLog.ERR,
                        "Failed to download forecast data: "+e.getClass().getSimpleName()+": "+e.getMessage());
            }
            return null;
        }

        //* Override to do something before getting data starts

        public void onStart(){
          // do something
        }

        public void onNegativeResult(){
            // do nothing at the moment.
            PrivateLog.log(context,PrivateLog.DATA,PrivateLog.ERR,"Failed getting forecast data!");
        }

        /*
         * Override this routine to define what to do if obtaining data succeeded.
         * Remember: at this point, the new data is already written to the database and can be
         * accessed via the CardHandler class.
         */

        public void onPositiveResult(){
            // remove the flag that data was removed previously, if set
            WeatherSettings.removeWeatherUpdatedFlag(context,WeatherSettings.UpdateType.DATA_CLEARED);
        }

        public void onPositiveResult(ArrayList<RawWeatherInfo> rawWeatherInfos){
            onPositiveResult();
        }

        private void addEntryToDatabase(RawWeatherInfo rawWeatherInfo){
            // get timestamp
            Calendar calendar = Calendar.getInstance();
            rawWeatherInfo.polling_time = calendar.getTimeInMillis();
            //context.getContentResolver().insert(WeatherContentManager.FORECAST_URI_ALL,WeatherContentManager.getContentValuesFromWeatherCard(rawWeatherInfo));
            String selection = ""+WeatherContentProvider.WeatherDatabaseHelper.KEY_FORECASTS_name+"=?";
            String[] selectionArgs = {rawWeatherInfo.weatherLocation.getName()};
            int result = context.getContentResolver().update(WeatherContentManager.FORECAST_URI_ALL,WeatherContentManager.getContentValuesFromWeatherCard(rawWeatherInfo),selection,selectionArgs);
            PrivateLog.log(context,PrivateLog.UPDATER,PrivateLog.INFO,"Weather entries updated: "+result);
            if (result==0){
                // insert, if the entry is not present in the data base. This happens, when this is a new station.
                Uri uri = context.getContentResolver().insert(WeatherContentManager.FORECAST_URI_ALL,WeatherContentManager.getContentValuesFromWeatherCard(rawWeatherInfo));
                PrivateLog.log(context,PrivateLog.UPDATER,PrivateLog.INFO,"New weather entry, content uri is: "+uri.toString());
            }
        }

        private void onPostExecute(ArrayList<RawWeatherInfo> rawWeatherInfos) {
            if (rawWeatherInfos.size()==0) {
                onNegativeResult();
            } else {
                for (int i=0; i<rawWeatherInfos.size(); i++){
                    addEntryToDatabase(rawWeatherInfos.get(i));
                }
                WeatherSettings.setWeatherUpdatedFlag(context,WeatherSettings.UpdateType.VIEWS);
                onPositiveResult(rawWeatherInfos);
            }
        }

        @Override
        public void run() {
            onStart();
            ArrayList<RawWeatherInfo> rawWeatherInfos = new ArrayList<RawWeatherInfo>();
            if (weatherLocations!=null){
                for (int i=0; i<weatherLocations.size(); i++){
                    final RawWeatherInfo rawWeatherInfo = updateWeatherLocationData(weatherLocations.get(i));
                    if (rawWeatherInfo!=null){
                        // get uv hazard index data
                        if (WeatherSettings.UVHIfetchData(context)){
                            final long currentTime = Calendar.getInstance().getTimeInMillis();
                            long[] timeArray = new long[3];
                            timeArray[0] = WeatherLayer.getMidnightTime(currentTime,0);
                            timeArray[1] = WeatherLayer.getMidnightTime(currentTime,Pollen.Tomorrow);
                            timeArray[2] = WeatherLayer.getMidnightTime(currentTime,Pollen.DayAfterTomorrow);
                            final ArrayList<Weather.WeatherLocation> finalWeatherLocations = weatherLocations;
                            APIReaders.GetUvIndexForLocation getUvIndexForLocation = new APIReaders.GetUvIndexForLocation(context,weatherLocations.get(i),timeArray){
                                @Override
                                public void onFinished(long[] timeArray, int[] resultArray) {
                                    rawWeatherInfo.addUVHazardIndexData(timeArray,resultArray);
                                }
                            };
                            getUvIndexForLocation.run();
                        } else {
                            // init uvData arrays to empty -1 values
                            rawWeatherInfo.addUVHazardIndexData(null,null);
                        }
                        rawWeatherInfos.add(rawWeatherInfo);
                    }
                }
            }
            onPostExecute(rawWeatherInfos);
        }
    }

    public static class TextForecastRunnable implements CancellableTask {

        private Context context;
        private final Cancellation cancellation = new Cancellation();

        public void cancel() {
            cancellation.cancel();
        }

        private ArrayList<String> getTextFromUrl(String url_string){
            try {
                // Text forecasts are always polled over TLS; a failed handshake fails the poll.
                URL url = new URL(url_string);
                InputStream inputStream = Http.get(url, cancellation);
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.ISO_8859_1));
                ArrayList<String> resultList = new ArrayList<String>();
                String line = null;
                while ((line = bufferedReader.readLine()) != null) {
                    resultList.add(line);
                }
                bufferedReader.close();
                return resultList;
            } catch (Exception e) {
                return null;
            }
        }

        private String getStringFromUrl(String url_string){
            StringBuilder stringBuilder = new StringBuilder();
            ArrayList<String> resultList = getTextFromUrl(url_string);
            for (int i=0; i<resultList.size(); i++){
                stringBuilder.append(resultList.get(i));
            }
            return stringBuilder.toString();
        }

        private ArrayList<TextForecast> getTextForecastMetadata(){
            ArrayList<TextForecast> textForecasts = new ArrayList<TextForecast>();
            ArrayList<TextForecasts.TextForecastSource> fileSources = TextForecasts.getTextForecastSources(context);
            for (int sourcePosition=0; sourcePosition<fileSources.size(); sourcePosition++){
                ArrayList<String> index = getTextFromUrl(fileSources.get(sourcePosition).getWebPath());
                if (index!=null) {
                    for (int i = 0; i < index.size(); i++) {
                        String s = index.get(i);
                        TextForecasts.TextForecastFile forecastFile = fileSources.get(sourcePosition).getValidFile(s);
                        if (forecastFile != null) {
                            TextForecast textForecast = new TextForecast();
                            textForecast.setIssued(s, 84);
                            textForecast.identifier = s.substring(s.indexOf(forecastFile.filename), s.indexOf(forecastFile.filename)+22);
                            if (textForecast.identifier.contains(">")){
                                textForecast.identifier = textForecast.identifier.substring(0,18);
                            }
                            textForecast.webUrl = fileSources.get(sourcePosition).getWebPath();
                            textForecast.type = forecastFile.type;
                            textForecasts.add(textForecast);
                        }
                    }
                }
            }
            return textForecasts;
        }

        public TextForecastRunnable(Context context) {
            this.context = context;
        }

        //* Override to do something before getting data starts

        public void onStart(){
            // do something
        }

        public void onNegativeResult(){
            // do nothing at the moment.
        }

        /*
         * Override this routine to define what to do if obtaining data succeeded.
         * Remember: at this point, the new data is already written to the database and can be
         * accessed via the CardHandler class.
         */

        public void onPositiveResult(){
            // do nothing at the moment.
        }

        @Override
        public void run() {
            onStart();
            ArrayList<TextForecast> textForecasts = getTextForecastMetadata();
            // determine which texts are new
            ArrayList<TextForecast> newTextForecasts = TextForecasts.getNewTextForecasts(context,textForecasts);
            PrivateLog.log(context,PrivateLog.DATA,PrivateLog.INFO,newTextForecasts.size()+ " new texts found in "+textForecasts.size()+ " available online.");
            for (int i=0;i<newTextForecasts.size(); i++){
                // read text data from web and parse
               newTextForecasts.get(i).parse(getTextFromUrl(newTextForecasts.get(i).getUrlString()));
               newTextForecasts.get(i).polled = Calendar.getInstance().getTimeInMillis();
            }
            // save new texts to database
            TextForecasts.writeTextForecastsToDatabaseUnconditionally(context,newTextForecasts);
            // call
            onPositiveResult();
        }
    }

    public static class RadarMNSetGeoserverRunnable implements CancellableTask{

        private String WN_RADAR_URL;
        public static final String RADAR_CACHE_FILENAME_PREFIX    = "radarMN-";
        public static final String RADAR_CACHE_FILENAME_SUFFIX    = ".png";
        public final static int TIMESTEP_5MINUTES = 1000*60*5; // 5 minutes
        public final static int DATASET_SIZE = 24; // there are 23 OR 24 future images available at the GeoServer

        private Context context;
        long startTime = 0;
        private boolean forceUpdate = false;
        public boolean ssl_exception = false;
        int progress = 0;
        int errors = 0;
        int threadsDone = 0;

        private final Cancellation cancellation = new Cancellation();
        private volatile ExecutorService executor;

        private final static int THREADS_USED = 24;

        public void cancel() {
            cancellation.cancel();
            ExecutorService current = executor;
            if (current != null) {
                current.shutdownNow();
            }
        }

        public RadarMNSetGeoserverRunnable(Context context){
            this.context = context;
            WN_RADAR_URL="//maps.dwd.de/geoserver/dwd/wms?service=WMS&version=1.1.0&request=GetMap&layers=dwd%3ARadar_wn-product_1x1km_ger&"+RadarMN2.BBOX+"&TIMESTAMP&width="+RadarMN2.getFixedRadarMapWidth(context)+"&height="+RadarMN2.getFixedRadarMapHeight(context)+"&srs=EPSG%3A3857&styles=&format=image%2Fpng";
        }

        public void setForceUpdate(boolean forceUpdate){
            this.forceUpdate = forceUpdate;
        }

        public long roundUTCUpToNextFiveMinutes(long time){
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            calendar.setTimeInMillis(time);
            calendar.set(Calendar.MILLISECOND,0);
            calendar.set(Calendar.SECOND,0);
            int minutes = calendar.get(Calendar.MINUTE);
            while (minutes%5!=0){
                minutes++;
            }
            calendar.set(Calendar.MINUTE,minutes);
            return calendar.getTimeInMillis();
        }

        public URL getUrlStringForTime(long time){
            /*
            The forecast is available at the Geoserver for every five minutes, eg 10:00, 10:05, 10:10
            Other timestamps give an error. Format is:
            time=2023-03-20T11:00:00.000Z
             */
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            calendar.setTimeInMillis(time);
            calendar.set(Calendar.MILLISECOND,0);
            calendar.set(Calendar.SECOND,0);
            int minutes = calendar.get(Calendar.MINUTE);
            while (minutes%5!=0){
                minutes++;
            }
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("time=");
            stringBuilder.append(calendar.get(Calendar.YEAR));
            stringBuilder.append("-");
            int monthOfYear = calendar.get(Calendar.MONTH)+1;
            if (monthOfYear<10){
                stringBuilder.append("0");
            }
            stringBuilder.append(monthOfYear);
            stringBuilder.append("-");
            int dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH);
            if (dayOfMonth<10){
                stringBuilder.append("0");
            }
            stringBuilder.append(dayOfMonth);
            stringBuilder.append("T");
            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            if (hour<10){
                stringBuilder.append("0");
            }
            stringBuilder.append(hour);
            stringBuilder.append(":");
            if (minutes<10){
                stringBuilder.append("0");
            }
            stringBuilder.append(minutes);
            stringBuilder.append(":00.000Z");
            String result = String.copyValueOf(WN_RADAR_URL.toCharArray());
            result = result.replace("TIMESTAMP",stringBuilder.toString());
            result = HttpsUrl.HTTPS_SCHEME + result;
            URL url = null;
            try {
                url = new URL(result);
            } catch (MalformedURLException e){
                // do nothing
            }
            return url;
        }

        private InputStream getRadarInputStream(long time) throws IOException {
            URL url = getUrlStringForTime(time);
            try {
                return Http.get(url, cancellation);
            } catch (SSLException e){
                ssl_exception = true;
                PrivateLog.log(context,PrivateLog.DATA,PrivateLog.ERR,"Error: ssl connection could not be established, but http is not allowed.");
                throw e;
            }
        }

        public static File getRadarMNFile(Context context, int count){
            File cacheDir = context.getCacheDir();
            return new File(cacheDir,RADAR_CACHE_FILENAME_PREFIX+count+RADAR_CACHE_FILENAME_SUFFIX);
        }

        public static boolean radarCacheFileExists(Context context, int count){
            File cacheFile = getRadarMNFile(context,count);
            return cacheFile.exists();
        }

        public static boolean radarCacheFileValid(Context context, int count){
            File cacheFile = getRadarMNFile(context,count);
            if (cacheFile.exists()){
                if (cacheFile.length()>5000){
                    return true;
                }
            }
            return false;
        }

        public static boolean fullRadarDataSet(Context context){
            int count = 0;
            for (int i=0; i<DATASET_SIZE; i++){
                if (radarCacheFileExists(context,i)){
                    if (radarCacheFileValid(context,i)){
                        count++;
                    }
                }
            }
            // a full set consists of 23 OR 24 images, one less is tolerated.
            return count >= DATASET_SIZE-2;
        }

        public static boolean deleteCacheFile(Context context, int count){
            File cacheFile=getRadarMNFile(context,count);
            return cacheFile.delete();
        }

        public static void deleteCacheFiles(Context context){
            for (int i=0; i<DATASET_SIZE; i++){
                deleteCacheFile(context,i);
            }
        }

        private boolean putRadarMapToCache(InputStream inputStream, int count){
            File cacheFile = getRadarMNFile(context,count);
            try {
                FileOutputStream fileOutputStream = new FileOutputStream(cacheFile);
                int filesize=0;
                int i;
                byte[] cache = new byte[8192];
                while ((i=inputStream.read(cache))!=-1){
                    fileOutputStream.write(cache,0,i);
                    filesize=filesize+i;
                }
                fileOutputStream.close();
                inputStream.close();
            } catch (Exception e){
                return false;
            }
            return true;
        }

        public void fetchRadarSet(int start, int stop){
            // data available for 2h with 5 min steps => 24 data sets at max
            for (int i=start; i<=stop; i++){
                if (cancellation.isCancelled() || Thread.currentThread().isInterrupted()){
                    return;
                }
                try {
                    putRadarMapToCache(getRadarInputStream(startTime+i*TIMESTEP_5MINUTES),i);
                    incrementProgress();
                } catch (IOException e){
                    incrementErrors();
                    incrementProgress();
                }
            }
            threadsDone++;
            checkThreadsDone();
        }

        private void checkThreadsDone(){
            if (threadsDone>=THREADS_USED){
                if (errors>3){
                    startTime = WeatherSettings.getPrefRadarLastdatapoll(context);
                    onFinished(startTime,false);
                } else {
                    WeatherSettings.setPrefRadarLastdatapoll(context,startTime);
                    onFinished(startTime,true);
                }
            }
        }

        public void incrementProgress(){
            progress++;
            onProgress(startTime,progress);
        }

        public void incrementErrors(){
            errors++;
        }

        private void multiFetchRadarSet(){
            progress = 0;
            threadsDone = 0;
            // use 0 to 24 for full set
            startTime = roundUTCUpToNextFiveMinutes(Calendar.getInstance().getTimeInMillis());
            // need to delete if read is incomplete
            deleteCacheFiles(context);
            errors = 0;
            ExecutorService executor = Executors.newFixedThreadPool(THREADS_USED);
            this.executor = executor;
            final int slidesPerThread = DATASET_SIZE / THREADS_USED;
            for (int i=0; i<THREADS_USED; i++){
                final int thread = i;
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        fetchRadarSet(thread*slidesPerThread, (thread+1)*slidesPerThread - 1);
                    }
                });
            }
        }

        public void onStart(){
            // override to do something once update started
        }

        public void onFinished(long startTime, boolean success){

            // override to do something with the map
        }

        public void onProgress(long startTime, int progress){
            // override to do something once the 1st image was loaded from the geoserver
        }

        @Override
        public void run() {
            onStart();
            forceUpdate = false;
            /* either update or return immediately to reuse present data
             * conditions for update:
             * corrupted / incomplete dataset, OR forced update (currently not used in production) AND there is internet access.
             */
            if ((!fullRadarDataSet(context) || (WeatherSettings.isRadarDataOutdated(context)) || (forceUpdate)) && (Weather.suitableNetworkAvailable(context))){
                // onFinished is called form multiFetch
                multiFetchRadarSet();
            } else {
                // when re-using data, the startTime needs to be loaded from settings
                startTime = WeatherSettings.getPrefRadarLastdatapoll(context);
                onFinished(startTime,true);
            }
        }
    }

    public static class getLayerImages implements CancellableTask{

        private Context context;
        private final ArrayList<WeatherLayer> layers;
        public boolean ssl_exception = false;
        private boolean forceUpdate = false;
        private boolean alreadyToastedForbiddenUpdate = false;
        private final Cancellation cancellation = new Cancellation();

        public void cancel() {
            cancellation.cancel();
        }

        public getLayerImages(Context context, final ArrayList<WeatherLayer> layers){
            this.context = context;
            this.layers = layers;
        }

        public static URL getGeoServerURL(WeatherLayer weatherLayer){
            float x0 = weatherLayer.mapGeo[0]; float y0 = weatherLayer.mapGeo[1]; float x1 = weatherLayer.mapGeo[2]; float y1 = weatherLayer.mapGeo[3];
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(HttpsUrl.HTTPS_SCHEME);
            stringBuilder.append("maps.dwd.de/geoserver/dwd/wms?service=WMS&version=1.1.0&request=GetMap&layers=dwd%3A");
            stringBuilder.append(WeatherLayer.getLayerID(weatherLayer.layer));
            stringBuilder.append("&bbox=");
            stringBuilder.append(x0); stringBuilder.append("%2C"); stringBuilder.append(y0); stringBuilder.append("%2C");
            stringBuilder.append(x1); stringBuilder.append("%2C"); stringBuilder.append(y1); stringBuilder.append("&");
            if (weatherLayer.getTargetTime()!=0){
                stringBuilder.append(getTimeStamp(weatherLayer.getTargetTime()));
                stringBuilder.append("&");
            }
            stringBuilder.append("width="); stringBuilder.append(weatherLayer.width); stringBuilder.append("&");
            stringBuilder.append("height="); stringBuilder.append(weatherLayer.height); stringBuilder.append("&");
            if (weatherLayer.srs!=null){
                stringBuilder.append("srs=EPSG%3A"); stringBuilder.append(weatherLayer.srs); stringBuilder.append("&");
            }
            stringBuilder.append("styles=&format=image%2Fpng");
            String strResult = stringBuilder.toString();;
            try {
                return new URL(strResult);
            } catch (MalformedURLException e){
                return null;
            }
        }

        public static String getTimeStamp(long timestamp){
            /*
            Format is:
            time=2023-03-20T11:00:00.000Z
             */
            Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            calendar.setTimeInMillis(timestamp);
            calendar.set(Calendar.MILLISECOND,0);
            calendar.set(Calendar.SECOND,0);
            int minutes = calendar.get(Calendar.MINUTE);
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append("time=");
            stringBuilder.append(calendar.get(Calendar.YEAR));
            stringBuilder.append("-");
            int monthOfYear = calendar.get(Calendar.MONTH)+1;
            if (monthOfYear<10){
                stringBuilder.append("0");
            }
            stringBuilder.append(monthOfYear);
            stringBuilder.append("-");
            int dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH);
            if (dayOfMonth<10){
                stringBuilder.append("0");
            }
            stringBuilder.append(dayOfMonth);
            stringBuilder.append("T");
            int hour = calendar.get(Calendar.HOUR_OF_DAY);
            if (hour<10){
                stringBuilder.append("0");
            }
            stringBuilder.append(hour);
            stringBuilder.append(":");
            if (minutes<10){
                stringBuilder.append("0");
            }
            stringBuilder.append(minutes);
            stringBuilder.append(":00.000Z");
            return stringBuilder.toString();
        }

        public InputStream getLayerInputStream(WeatherLayer weatherLayer) throws IOException {
            URL url = getGeoServerURL(weatherLayer);
            try {
                return Http.get(url, cancellation);
            } catch (SSLException e){
                ssl_exception = true;
                PrivateLog.log(context,PrivateLog.DATA,PrivateLog.ERR,"Error: ssl connection could not be established, but http is not allowed.");
                throw e;
            }
        }

        public static boolean readImage(InputStream inputStream, File targetFile){
            try {
                FileOutputStream fileOutputStream = new FileOutputStream(targetFile);
                int filesize=0;
                int i;
                byte[] cache = new byte[8192];
                while ((i=inputStream.read(cache))!=-1){
                    fileOutputStream.write(cache,0,i);
                    filesize=filesize+i;
                }
                fileOutputStream.close();
                inputStream.close();
            } catch (Exception e){
                // do nothing
            }
            return true;
        }

        public boolean readLayer(WeatherLayer weatherLayer, boolean isSubLayer){
            // read outdated images from geoServer. Ignore pollen layers, because they are generated locally.
            if ((weatherLayer.isOutdated(context) && !weatherLayer.isPollen()) || (forceUpdate)){
                try {
                    File cacheDir = context.getCacheDir();
                    File targetFile = new File(cacheDir,weatherLayer.getCacheFilename());
                    InputStream layerInputStream = getLayerInputStream(weatherLayer);
                    boolean result = readImage(layerInputStream,targetFile);
                } catch (Exception e){
                    PrivateLog.log(context,PrivateLog.DATA,PrivateLog.ERR,"i/o error while fetching layers: "+e.getMessage());
                    return false;
                }
                // recursively iterate through dependant atop-layers, in case any of them is missing.
                // Reason: this class may also be initiated with a layer-subset or a single layer.
                if (weatherLayer.atop!=null){
                    for (int i=0; i<weatherLayer.atop.length; i++){
                        if (weatherLayer.atop[i]!=weatherLayer.layer){
                            readLayer(new WeatherLayer(weatherLayer.atop[i]),true);
                        }
                    }
                }
            } else {
                // layer in place. Do nothing special here.
            }
            if (!isSubLayer){
                onProgress(weatherLayer);
            }
            return true;
        }

        public void setForceUpdate(boolean b){
            this.forceUpdate = b;
        }

        public void onStart(){
            // override to do something once update started
        }

        public void onProgress(WeatherLayer weatherLayer){

        }

        public void onFinished(boolean success){

        }

        @Override
        public void run() {
            onStart();
            boolean success = true;
            if (layers!=null){
                for (int i=0; i<layers.size(); i++){
                    WeatherLayer weatherLayer = layers.get(i);
                    if (!readLayer(weatherLayer,false)){
                        success = false;
                    }
                }
            }
            onFinished(success);
        }
    }

    public static class PollenReader implements CancellableTask{

        // Instance-owned: a static Context leaked the Activity and a static token let one
        // instance cancel another instance's in-flight request.
        private final Context context;
        private final Cancellation cancellation = new Cancellation();

        public PollenReader(Context context){
            this.context = context;
        }

        public void cancel() {
            cancellation.cancel();
        }

        public InputStream getPollenStream() {
            String urlString = HttpsUrl.build(WeatherSettings.getWeatherUrl(context),
                    "/climate_environment/health/alerts/s31fg.json");
            try {
                URL url = new URL(urlString);
                return Http.get(url, cancellation);
            } catch (MalformedURLException e){
                PrivateLog.log(context,PrivateLog.UPDATER,PrivateLog.FATAL,"Malformed pollen url: "+e.getMessage());
            } catch (IOException e) {
                PrivateLog.log(context,PrivateLog.UPDATER,PrivateLog.FATAL,"I/O error reading pollen data: "+e.getMessage());
            }
            return null;
        }

        public String readPollenDataRawString() {
            InputStream pollenImputStream = getPollenStream();
            if (pollenImputStream!=null){
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(pollenImputStream));
                StringBuilder stringBuilder = new StringBuilder();
                String s;
                try {
                    while ((s=bufferedReader.readLine())!=null){
                        stringBuilder.append(s);
                    }
                    bufferedReader.close();
                    return stringBuilder.toString();
                } catch (IOException e){
                    PrivateLog.log(context,PrivateLog.UPDATER,PrivateLog.FATAL,"I/O error reading raw pollen data: "+e.getMessage());
                }
            }
            // pollenInputStream is null
            PrivateLog.log(context,PrivateLog.UPDATER,PrivateLog.ERR,"Unable to fetch pollen data.");
            return null;
        }

        public long parsePollenDateToMillis(String source){
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.getDefault());
            long result = 0 ;
            try{
                Date date = simpleDateFormat.parse(source);
                result = date.getTime();
            } catch (Exception e){
                result = 0;
            }
            return result;
        }

        public static int[] getPollenDataFromJson(JSONObject pollenObject, int type){
            int[] result = new int[6];
            result[0] = -1; result[1] = -1; result[2] = -1; result[3] = -1; result[4] = -1; result[5] = -1;
            try {
                int [] today = Pollen.getMinMax(pollenObject.getString("today"));
                int [] tomorrow = Pollen.getMinMax(pollenObject.getString("tomorrow"));
                int [] dayAfterTomorrow = Pollen.getMinMax(pollenObject.getString("dayafter_to"));
                result[0] = today[0]; result[1] = today[1];
                result[2] = tomorrow[0]; result[3] = tomorrow[1];
                result[4] = dayAfterTomorrow[0]; result[5] = dayAfterTomorrow[1];
            } catch (JSONException e) {
                // nothing to do
            }
            return result;
        }

        public boolean readPollenData(){
            String rawJsonData = readPollenDataRawString();
            if (rawJsonData==null){
                return false;
            }
            try {
                Pollen basicPollen = new Pollen();
                ArrayList<Pollen> pollenArrayList = new ArrayList<Pollen>();
                JSONObject mainObject = new JSONObject(rawJsonData);
                basicPollen.last_update = mainObject.getString("last_update");
                basicPollen.last_update_UTC = parsePollenDateToMillis(basicPollen.last_update);
                basicPollen.next_update = mainObject.getString("next_update");
                basicPollen.next_update_UTC = parsePollenDateToMillis(basicPollen.next_update);
                //pollen.name = mainObject.getString("name");
                //pollen.sender = mainObject.getString("sender");
                String content = mainObject.getString("content");
                JSONArray contentArray = new JSONArray(content);
                for (int i=0; i<contentArray.length(); i++){
                    JSONObject contentArrayObject = contentArray.getJSONObject(i);
                    JSONObject pollenObject = contentArrayObject.getJSONObject("Pollen");
                    Pollen pollen = new Pollen(basicPollen);
                    pollen.partregion_name = contentArrayObject.getString("partregion_name");
                    pollen.region_name = contentArrayObject.getString("region_name");
                    pollen.region_id = contentArrayObject.getInt("region_id");
                    pollen.partregion_id = contentArrayObject.getInt("partregion_id");
                    pollen.ambrosia = getPollenDataFromJson(pollenObject.getJSONObject("Ambrosia"),Pollen.Ambrosia);
                    pollen.beifuss  = getPollenDataFromJson(pollenObject.getJSONObject("Beifuss"),Pollen.Beifuss);
                    pollen.roggen   = getPollenDataFromJson(pollenObject.getJSONObject("Roggen"),Pollen.Roggen);
                    pollen.esche    = getPollenDataFromJson(pollenObject.getJSONObject("Esche"),Pollen.Esche);
                    pollen.birke    = getPollenDataFromJson(pollenObject.getJSONObject("Birke"),Pollen.Birke);
                    pollen.hasel    = getPollenDataFromJson(pollenObject.getJSONObject("Hasel"),Pollen.Hasel);
                    pollen.erle     = getPollenDataFromJson(pollenObject.getJSONObject("Erle"),Pollen.Erle);
                    pollen.graeser  = getPollenDataFromJson(pollenObject.getJSONObject("Graeser"),Pollen.Graeser);
                    pollenArrayList.add(pollen);
                }
                Pollen.WritePollenToDatabase(context,pollenArrayList);
                PrivateLog.log(context,PrivateLog.UPDATER,PrivateLog.ERR,"Pollen data fetched successfully & added to database. Entries: "+pollenArrayList.size());
            } catch (JSONException e) {
                PrivateLog.log(context,PrivateLog.UPDATER,PrivateLog.ERR,"Error reading pollen forecast: "+e.getMessage());
                return false;
            }
            return true;
        }

        public void onStart(){
        }

        public void onFinished(boolean success){
        }

        @Override
        public void run() {
            cancellation.reset();
            onStart();
            onFinished(readPollenData());
        }
    }

    public static class PollenAreaReader implements CancellableTask {

        // Instance-owned for the same reason as PollenReader.
        private final Context context;
        private final Cancellation cancellation = new Cancellation();

        public PollenAreaReader(Context context) {
            this.context = context;
        }

        public void cancel() {
            cancellation.cancel();
        }

        public InputStream getPollenAreaStream() {
            String urlString = HttpsUrl.build("maps.dwd.de",
                    "/geoserver/dwd/ows?service=WFS&version=1.0.0&request=GetFeature&typeName=dwd%3APollenfluggebiete&outputFormat=application%2Fjson");
            try {
                URL url = new URL(urlString);
                return Http.get(url, cancellation);
            } catch (MalformedURLException e) {
                PrivateLog.log(context, PrivateLog.UPDATER, PrivateLog.FATAL, "Malformed pollen area url: " + e.getMessage());
            } catch (IOException e) {
                PrivateLog.log(context, PrivateLog.UPDATER, PrivateLog.FATAL, "I/O error reading pollen area data: " + e.getMessage());
            }
            return null;
        }

        public String readPollenAreaDataRawString() {
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(getPollenAreaStream()));
            StringBuilder stringBuilder = new StringBuilder();
            String s;
            try {
                while ((s = bufferedReader.readLine()) != null) {
                    stringBuilder.append(s);
                }
                bufferedReader.close();
                return stringBuilder.toString();
            } catch (IOException e) {
                PrivateLog.log(context, PrivateLog.UPDATER, PrivateLog.FATAL, "I/O error reading raw pollen data: " + e.getMessage());
            }
            return null;
        }

        public void readPollenAreas() {
            String rawJsonData = readPollenAreaDataRawString();
            ArrayList<PollenArea> pollenPolygons = new ArrayList<PollenArea>();
            int regions = 0;
            try {
                JSONObject mainObject = new JSONObject(rawJsonData);
                // featuresArray has a polyon (multiple polygons? unclear!) that is part of a PollenArea.
                JSONArray featuresArray = mainObject.getJSONArray("features");
                for (int feature = 0; feature < featuresArray.length(); feature++) {
                    regions ++;
                    JSONObject featureObject = featuresArray.getJSONObject(feature);
                    String id = featureObject.getString("id");
                    JSONObject properties = featureObject.getJSONObject("properties");
                    int partregion_id = properties.getInt("GF");
                    int region_id = (partregion_id/10)*10; // replace last digit by 0
                    String GEN = properties.getString("GEN");
                    JSONObject geometry = featureObject.getJSONObject("geometry");
                    String type = geometry.getString("type");
                    JSONArray coordinates = geometry.getJSONArray("coordinates");
                    // seems coordinates may have multiple polygons
                    for (int coordinate = 0; coordinate < coordinates.length(); coordinate++) {
                        JSONArray polygonData = coordinates.getJSONArray(coordinate);
                        if (type.equals("Polygon")){
                            PollenArea singlePollenAreaPolygon = new PollenArea();
                            singlePollenAreaPolygon.polygonString = polygonData.toString();
                            singlePollenAreaPolygon.region_id = region_id;
                            singlePollenAreaPolygon.partregion_id = partregion_id;
                            singlePollenAreaPolygon.description = GEN;
                            pollenPolygons.add(singlePollenAreaPolygon);
                        } else {
                            // this is MultiPolygon
                            for (int i = 0; i < polygonData.length(); i++) {
                                JSONArray coordinatePair = polygonData.getJSONArray(i);
                                PollenArea singlePollenAreaPolygon = new PollenArea();
                                singlePollenAreaPolygon.polygonString = coordinatePair.toString();
                                singlePollenAreaPolygon.region_id = region_id;
                                singlePollenAreaPolygon.partregion_id = partregion_id;
                                singlePollenAreaPolygon.description = GEN;
                                pollenPolygons.add(singlePollenAreaPolygon);
                            }
                        }
                    }
                }
                PollenArea.WritePollenAreasToDatabase(context,pollenPolygons);
            } catch (Exception e){
                PrivateLog.log(context,PrivateLog.DATA,PrivateLog.ERR,"Error reading pollen areas: "+e.getMessage());
            }
        }

        public void onFinished(){

        }

        @Override
        public void run() {
            cancellation.reset();
            readPollenAreas();
            onFinished();
        }
    }

    public static class WarnAreasReader implements CancellableTask {

        private Context context;
        ArrayList<Areas.Area> areas;
        ContentResolver contentResolver;
        private static final Cancellation CANCELLATION = new Cancellation();

        public WarnAreasReader(Context context){
            this.context = context;
            this.areas = new ArrayList<Areas.Area>();
            this.contentResolver = context.getApplicationContext().getContentResolver();
        }

        public void cancel() {
            CANCELLATION.cancel();
        }

        public static InputStream getAreaStream(Context context, String area) {
            String urlString = HttpsUrl.build("maps.dwd.de",
                    "/geoserver/dwd/ows?service=WFS&version=1.0.0&request=GetFeature&typeName=dwd%3A"+area+"&outputFormat=application%2Fjson");
            try {
                URL url = new URL(urlString);
                return Http.get(url, CANCELLATION);
            } catch (MalformedURLException e) {
                PrivateLog.log(context, PrivateLog.UPDATER, PrivateLog.FATAL, "Malformed pollen area url: " + e.getMessage());
            } catch (IOException e) {
                PrivateLog.log(context, PrivateLog.UPDATER, PrivateLog.FATAL, "I/O error reading pollen area data: " + e.getMessage());
            }
            return null;
        }

        public String readAreaDataRawString(String area) {
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(getAreaStream(context,area)));
            StringBuilder stringBuilder = new StringBuilder();
            String s;
            try {
                while ((s = bufferedReader.readLine()) != null) {
                    stringBuilder.append(s);
                }
                bufferedReader.close();
                return stringBuilder.toString();
            } catch (IOException e) {
                PrivateLog.log(context, PrivateLog.UPDATER, PrivateLog.FATAL, "I/O error reading raw pollen data: " + e.getMessage());
            }
            return null;
        }

        private String getPolygonString(JSONArray jsonArray) {
            StringBuilder stringBuilder = new StringBuilder();
            for (int i=0; i< jsonArray.length(); i++) {
                try {
                    JSONObject jsonObject = jsonArray.getJSONObject(i);
                    String zero = jsonObject.getString("0");
                    String one = jsonObject.getString("1");
                    // switch order
                    stringBuilder.append(one); stringBuilder.append(","); stringBuilder.append(zero);
                    // skip space symbol when last item
                    if (i<jsonArray.length()-1) {
                        stringBuilder.append(" ");
                    }
                } catch (Exception e) {
                    return null;
                }
            }
            return stringBuilder.toString();
        }

        public int doFile(String areaName, int areaType) {
            String rawJsonData = readAreaDataRawString("Warngebiete_Binnenseen");
            int areaCount = 0;
            try {
                JSONObject mainObject = new JSONObject(rawJsonData);
                // featuresArray holds the Areas
                JSONArray featuresArray = mainObject.getJSONArray("features");
                for (int feature = 0; feature < featuresArray.length(); feature++) {
                    JSONObject featureObject = featuresArray.getJSONObject(feature);
                    String id = featureObject.getString("id");
                    JSONObject properties = featureObject.getJSONObject("properties");
                    Areas.Area area = new Areas.Area();
                    area.warncellID = properties.getString("WARNCELLID");
                    area.warncenter = properties.getString("WARNCENTER");
                    area.type       = properties.getInt("TYPE");
                    area.name       = properties.getString("NAME");
                    JSONObject geometry = featureObject.getJSONObject("geometry");
                    String type = geometry.getString("type");
                    JSONArray coordinates = geometry.getJSONArray("coordinates");
                    ArrayList<String> polygons = new ArrayList<String>();
                    // seems coordinates may have multiple polygons
                    for (int coordinate = 0; coordinate < coordinates.length(); coordinate++) {
                        JSONArray polygonData = coordinates.getJSONArray(coordinate);
                        if (type.equals("Polygon")){
                            String polygon = getPolygonString(polygonData);
                            polygons.add(polygon);
                        } else {
                            // this is MultiPolygon
                            for (int i = 0; i < polygonData.length(); i++) {
                                JSONArray multiPolygon = polygonData.getJSONArray(i);
                                String polygon = getPolygonString(multiPolygon);
                                polygons.add(polygon);
                            }
                        }
                    }
                    area.polygonString = WeatherContentManager.serializeStringFromArrayList(polygons);
                    areas.add(area);
                    contentResolver.insert(WeatherContentManager.AREA_URI_ALL,WeatherContentManager.getContentValuesFromArea(area));
                    areaCount ++;
                }
                // todo: write area to database
            } catch (Exception e) {
                return 0;
            }
            return areaCount;
        }

        @Override
        public void run() {
            CANCELLATION.reset();
            doFile("Warngebiete_Binnenseen", Areas.Area.Type.BINNENSEE);
                doFile("Warngebiete_Bundeslaender", Areas.Area.Type.BINNENSEE);
                doFile("Warngebiete_Gemeinden", Areas.Area.Type.BINNENSEE);
                doFile("Warngebiete_Kreise", Areas.Area.Type.BINNENSEE);
                doFile("Warngebiete_Kueste", Areas.Area.Type.BINNENSEE);
                //doFile("Warngebiete_See", Areas.Area.Type.BINNENSEE);
        }
    }

    public static class GetUvIndexForLocation implements CancellableTask{

    public final static double GEOACCURACY_UVI = 0.01d;
    private Context context;
    private Weather.WeatherLocation weatherLocation;
    private long[] timeArray;
    private static final Cancellation CANCELLATION = new Cancellation();

    public void cancel() {
        CANCELLATION.cancel();
    }

    public GetUvIndexForLocation(Context context, Weather.WeatherLocation weatherLocation, long[] timeArray){
        this.context = context;
        this.weatherLocation = weatherLocation;
        this.timeArray = timeArray;
    }

        private InputStream getUVIStream(long time){
            // https://maps.dwd.de/geoserver/dwd/wms?service=WMS&version=1.1.0&request=GetMap&layers=dwd%3AUVI_Global_CL&bbox=10.11%2C53.66%2C10.13%2C53.67&width=1&height=1&srs=EPSG%3A4326&format=image%2Fpng
            String y0 = String.format(Locale.US,"%.2f",weatherLocation.latitude-GEOACCURACY_UVI);
            String y1 = String.format(Locale.US,"%.2f",weatherLocation.latitude+GEOACCURACY_UVI);
            String x0 = String.format(Locale.US,"%.2f",weatherLocation.longitude-GEOACCURACY_UVI);
            String x1 = String.format(Locale.US,"%.2f",weatherLocation.longitude+GEOACCURACY_UVI);
            String timestring = getLayerImages.getTimeStamp(time);
            String urlString = HttpsUrl.build("maps.dwd.de",
                    "/geoserver/dwd/wms?service=WMS&version=1.1.0&request=GetMap&layers=dwd%3AUVI_Global_"+WeatherSettings.getPrefUVHIMaindisplayType(context)+"&bbox="+x0+"%2C"+y0+"%2C"+x1+"%2C"+y1+"&"+timestring+"&width=1&height=1&srs=EPSG%3A4326&format=image%2Fpng");
            try {
                URL url = new URL(urlString);
                return Http.get(url, CANCELLATION);
            } catch (Exception e){
                // do nothing

            }
            return null;
        }

        private int getUvIndex(long time){
            try {
                BufferedInputStream bufferedInputStream = new BufferedInputStream(getUVIStream(time));
                Bitmap bitmap = BitmapFactory.decodeStream(bufferedInputStream);
                int pixel = bitmap.getPixel(0,0);
                bufferedInputStream.close();
                return UVHazardIndex.getUvIndexFromColor(pixel);
            } catch (Exception e){
                return -1;
            }
        }

        public void onFinished(long[] timeArray, int[] resultArray){

        }

        @Override
        public void run() {
            CANCELLATION.reset();
            if ((timeArray!=null) && (WeatherSettings.UVHIfetchData(context))){
                long[] timeResult = new long[timeArray.length];
                int[] result = new int[timeArray.length];
                for (int position=0; position<timeArray.length; position++){
                    long midnightTime = WeatherLayer.getMidnightTime(timeArray[position]);
                    timeResult[position] = midnightTime;
                    result[position] = getUvIndex(midnightTime);
                }
                onFinished(timeResult,result);
            } else {
                onFinished(null,null);
            }
        }
    }


}

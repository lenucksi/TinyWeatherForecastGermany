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

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public class PrivateLog {
    public final static String LOGHANDLE="tiwefoge";
    public final static String LOGFILENAME="logs.txt";
    public final static String CLIPBOARD_LOGLABEL="Logs for";
    public final static String[] CLIPBOARD_MIMETYPES={"text/plain"};
    public final static long LOG_MAX_FILESIZE = 1024 * 512;

    public static final String MAIN = "main";
    public static final String SERVICE = "service";
    public static final String ONBOOT = "onboot";
    public static final String GB = "wearable";
    public static final String UPDATER = "updater";
    public static final String SYNC = "sync";
    public static final String DATA = "data";
    public static final String WARNINGS = "warnings";
    public static final String WIDGET = "widget";
    public static final String TEXTS = "texts";
    public static final String STATIONS = "stations";
    public static final String RADAR  = "radar";
    public static final String GEO  = "geo";
    public static final String ALERTS  = "alerts";
    public static final String LAYERS  = "layers";
    public static final String DATASTORAGE  = "data";

    public static final int INFO = 0;
    public static final int WARN = 1;
    public static final int ERR  = 2;
    public static final int FATAL = 3;

    /**
     * Redaction is centralised here so every log producer benefits without having to remember
     * to sanitise its own message. This nested class has no Android references on purpose:
     * pure JVM tests can exercise it (see PrivateLogRedactionTest).
     */
    public static final class Redaction {
        public static final String MASK = "[redacted]";

        private static final Pattern GEO_URI = Pattern.compile("(?i)\\bgeo:[^\\s\"'<>]+");
        private static final Pattern URL_WITH_SCHEME =
                Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://[^\\s\"'<>,;)]+");
        private static final Pattern NAMED_COORDINATE =
                Pattern.compile("(?i)\\b(lat(?:itude)?|lon(?:gitude)?|long)\\s*[:=]\\s*[-+]?\\d+(?:[.,]\\d+)?\\b");
        private static final Pattern COORDINATE_PAIR =
                Pattern.compile("(?<![\\d.,])([-+]?\\d{1,3}[.,]\\d{2,})\\s*[,;/\\-]?\\s*([-+]?\\d{1,3}[.,]\\d{2,})(?![\\d.,])");
        private static final Pattern EMAIL =
                Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");

        private Redaction() {
        }

        public static String redact(String message) {
            return redact(message, null);
        }

        /** Removes geo URIs, coordinates, URLs, mail addresses and the configured data host. */
        public static String redact(String message, String configuredHost) {
            if (message == null) {
                return null;
            }
            String result = GEO_URI.matcher(message).replaceAll(MASK);
            result = URL_WITH_SCHEME.matcher(result).replaceAll(MASK);
            result = NAMED_COORDINATE.matcher(result).replaceAll("$1=" + MASK);
            result = COORDINATE_PAIR.matcher(result).replaceAll(MASK);
            result = EMAIL.matcher(result).replaceAll(MASK);
            if (configuredHost != null && !configuredHost.trim().isEmpty()) {
                result = Pattern.compile(Pattern.quote(configuredHost.trim()), Pattern.CASE_INSENSITIVE)
                        .matcher(result).replaceAll(MASK);
            }
            return result;
        }

        /** Logcat stays off in release builds, even if a user enabled the debug preference. */
        public static boolean isLogcatAllowed(boolean debugBuild, String versionName) {
            return debugBuild || (versionName != null
                    && versionName.toLowerCase(Locale.ROOT).contains("debug"));
        }
    }

    /* ----------------------------------------------------------------------
     * TASK-35: asynchronous logging.
     *
     * The ~965 log call sites run on UI, service and receiver threads. They
     * must only prepare a tiny entry and hand it to a bounded queue; all file
     * I/O happens on one daemon worker. The worker keeps redaction and the
     * logcat gate from TASK-42 unchanged.
     * -------------------------------------------------------------------- */

    static final int LOG_QUEUE_CAPACITY = 512;
    static final long FLUSH_TIMEOUT_MS = 500;
    private static final long CRASH_FLUSH_TIMEOUT_MS = 1000;
    private static final long WORKER_POLL_MS = 100;
    private static final long CLOSE_TIMEOUT_MS = 2000;
    private static final String TIMESTAMP_PATTERN = "yyyy-MM-dd kk:mm:ss.SSS ";

    /** Sink interface with no Android dependency so the queue can be tested on the JVM. */
    interface LineSink {
        void writeBatch(List<String> lines) throws IOException;
        void flush() throws IOException;
        void close() throws IOException;
    }

    interface LogcatWriter {
        void log(String message);
    }

    interface Clock {
        long currentTimeMillis();
    }

    static String formatLine(long timestampMillis, String message) {
        // Locale is explicit: same behaviour as the historic default-locale formatter,
        // but without a locale-dependent lint finding in the log sink.
        SimpleDateFormat sdf = new SimpleDateFormat(TIMESTAMP_PATTERN, Locale.getDefault());
        return sdf.format(new Date(timestampMillis)) + message + System.lineSeparator();
    }

    static final class AsyncLogWriter implements Closeable {
        private static final class Entry {
            final String message;
            final String configuredHost;
            final boolean logcatEnabled;
            final long timestampMillis;

            Entry(String message, String configuredHost, boolean logcatEnabled, long timestampMillis) {
                this.message = message;
                this.configuredHost = configuredHost;
                this.logcatEnabled = logcatEnabled;
                this.timestampMillis = timestampMillis;
            }
        }

        private final BlockingQueue<Entry> queue;
        private final LineSink sink;
        private final LogcatWriter logcatWriter;
        private final Clock clock;
        private final Thread worker;
        private final Object monitor = new Object();
        private final AtomicLong dropped = new AtomicLong();
        private final AtomicLong failures = new AtomicLong();
        private volatile Throwable lastFailure;
        private volatile boolean closed;
        private long enqueued;
        private long completed;

        AsyncLogWriter(LineSink sink, LogcatWriter logcatWriter, Clock clock, int capacity) {
            this.queue = new ArrayBlockingQueue<Entry>(capacity);
            this.sink = sink;
            this.logcatWriter = logcatWriter;
            this.clock = clock;
            this.worker = new Thread(this::drain, "PrivateLog-writer");
            this.worker.setDaemon(true);
            this.worker.start();
        }

        /** Non-blocking hand-off: offer() never waits for a free slot or for the sink. */
        boolean enqueue(String message, String configuredHost, boolean logcatEnabled) {
            synchronized (monitor) {
                if (closed || !queue.offer(new Entry(message, configuredHost, logcatEnabled,
                        clock.currentTimeMillis()))) {
                    dropped.incrementAndGet();
                    return false;
                }
                enqueued++;
            }
            return true;
        }

        /**
         * Waits at most timeoutMillis for entries enqueued so far to reach the sink.
         * Used only by explicit log viewing/clearing and the crash path, never by log().
         */
        void flush(long timeoutMillis) {
            synchronized (monitor) {
                long target = enqueued;
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
                while (completed < target) {
                    long remainingNanos = deadline - System.nanoTime();
                    if (remainingNanos <= 0) {
                        recordFailure(new TimeoutException("log flush timed out after "
                                + timeoutMillis + " ms"));
                        break;
                    }
                    try {
                        monitor.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        recordFailure(e);
                        break;
                    }
                }
            }
        }

        void discardPending() {
            synchronized (monitor) {
                queue.clear();
                enqueued = completed;
            }
        }

        @Override
        public void close() {
            synchronized (monitor) {
                closed = true;
                monitor.notifyAll();
            }
            try {
                worker.join(CLOSE_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordFailure(e);
            }
        }

        void recordFailure(Throwable failure) {
            if (failure == null) {
                return;
            }
            lastFailure = failure;
            failures.incrementAndGet();
        }

        Throwable lastFailure() {
            return lastFailure;
        }

        long failureCount() {
            return failures.get();
        }

        long droppedCount() {
            return dropped.get();
        }

        private void drain() {
            while (!closed || !queue.isEmpty()) {
                Entry entry;
                try {
                    entry = queue.poll(WORKER_POLL_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    recordFailure(e);
                    break;
                }
                if (entry == null) {
                    continue;
                }
                List<Entry> batch = new ArrayList<Entry>();
                batch.add(entry);
                queue.drainTo(batch);
                List<String> lines = new ArrayList<String>(batch.size());
                List<String> logcatMessages = new ArrayList<String>(batch.size());
                for (Entry current : batch) {
                    String s = current.message;
                    String safeMessage = Redaction.redact(s, current.configuredHost);
                    lines.add(formatLine(current.timestampMillis, safeMessage));
                    if (current.logcatEnabled) {
                        logcatMessages.add(safeMessage);
                    }
                }
                // A failing sink must not kill the worker. Keep the original throwable so the
                // failure (including its stack trace) can be inspected via getLastLoggingFailure().
                try {
                    sink.writeBatch(lines);
                    sink.flush();
                } catch (IOException | RuntimeException e) {
                    recordFailure(e);
                }
                for (String message : logcatMessages) {
                    try {
                        logcatWriter.log(message);
                    } catch (RuntimeException e) {
                        recordFailure(e);
                    }
                }
                synchronized (monitor) {
                    completed += batch.size();
                    monitor.notifyAll();
                }
            }
            try {
                sink.close();
            } catch (IOException | RuntimeException e) {
                recordFailure(e);
            }
        }
    }

    /** File sink. Only the worker (or the crash path) ever touches the file. */
    static final class AndroidFileSink implements LineSink {
        private volatile Context applicationContext;
        private volatile File logFile;

        void bind(Context context) {
            Context app = context.getApplicationContext();
            if (app != null) {
                applicationContext = app;
            }
        }

        private File requireFile() throws IOException {
            Context app = applicationContext;
            if (app == null) {
                throw new IOException("log sink is not bound to an application context");
            }
            File file = logFile;
            if (file == null) {
                file = new File(app.getFilesDir(), LOGFILENAME);
                logFile = file;
            }
            return file;
        }

        @Override
        public void writeBatch(List<String> lines) throws IOException {
            File file = requireFile();
            List<String> output = prepareOutput(file, lines);
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                for (String line : output) {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        void writeCrashReport(String report) throws IOException {
            File file = requireFile();
            List<String> reportLines = new ArrayList<String>(1);
            reportLines.add(report);
            List<String> output = prepareOutput(file, reportLines);
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                for (String line : output) {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        private List<String> prepareOutput(File file, List<String> lines) throws IOException {
            if (file.exists() && file.length() > LOG_MAX_FILESIZE && !file.delete()) {
                throw new IOException("cannot rotate oversized log file");
            }
            if (file.exists()) {
                return lines;
            }
            List<String> output = new ArrayList<String>(lines.size() + 2);
            output.add(formatLine(System.currentTimeMillis(), "Logging started, new file created."));
            String deviceInfo = safeDeviceInfo(applicationContext);
            if (deviceInfo != null && !deviceInfo.isEmpty()) {
                output.add(deviceInfo);
            }
            output.addAll(lines);
            if (!file.createNewFile()) {
                throw new IOException("cannot create log file");
            }
            return output;
        }

        @Override
        public void flush() throws IOException {
            // Each batch closes its stream, so the bytes are already visible to readers.
        }

        @Override
        public void close() throws IOException {
        }
    }

    /** Pure-JVM stack trace formatting shared by the opt-in API and the crash handler. */
    static final class CrashReport {
        private CrashReport() {
        }

        static String stackTrace(Throwable throwable) {
            StringWriter buffer = new StringWriter();
            PrintWriter writer = new PrintWriter(buffer);
            throwable.printStackTrace(writer);
            writer.flush();
            return buffer.toString();
        }

        static String format(Thread thread, Throwable throwable, String configuredHost) {
            StringBuilder builder = new StringBuilder();
            builder.append("FATAL uncaught exception in thread ").append(thread.getName())
                    .append(System.lineSeparator());
            builder.append(stackTrace(throwable));
            return Redaction.redact(builder.toString(), configuredHost);
        }
    }

    static final class CrashHandler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler previous;

        CrashHandler(Thread.UncaughtExceptionHandler previous) {
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            // Best-effort local crash report; diagnostics must never mask the real crash.
            // The original throwable is always forwarded to the previous handler below.
            try {
                WRITER.flush(CRASH_FLUSH_TIMEOUT_MS);
                String report = CrashReport.format(thread, throwable, lastKnownHost);
                FILE_SINK.writeCrashReport(report);
            } catch (Throwable failure) {
                WRITER.recordFailure(failure);
            }
            if (previous != null && previous != this) {
                previous.uncaughtException(thread, throwable);
            }
        }
    }

    private static final AndroidFileSink FILE_SINK = new AndroidFileSink();
    private static final AsyncLogWriter WRITER = new AsyncLogWriter(FILE_SINK,
            new LogcatWriter() {
                @Override
                public void log(String message) {
                    Log.v(LOGHANDLE, message);
                }
            }, new Clock() {
        @Override
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    }, LOG_QUEUE_CAPACITY);
    private static final AtomicBoolean crashReporterInstalled = new AtomicBoolean(false);
    private static volatile String lastKnownHost;

    private static void installCrashReporterOnce() {
        if (crashReporterInstalled.compareAndSet(false, true)) {
            Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(previous));
        }
    }

    private static String safeDeviceInfo(Context context) {
        try {
            return getInfoString(context);
        } catch (RuntimeException e) {
            WRITER.recordFailure(e);
            return "Device info unavailable: " + e.getClass().getSimpleName();
        }
    }

    public static Throwable getLastLoggingFailure() {
        return WRITER.lastFailure();
    }

    public static long getLoggingFailureCount() {
        return WRITER.failureCount();
    }

    public static long getDroppedLogCount() {
        return WRITER.droppedCount();
    }

    public static String getLogs(Context context){
        WRITER.flush(FLUSH_TIMEOUT_MS);
        try (InputStreamReader inputStreamReader =
                     new InputStreamReader(new FileInputStream(context.getFileStreamPath(LOGFILENAME)),
                             StandardCharsets.UTF_8)) {
            StringBuilder stringBuilder = new StringBuilder();
            char[] buffer = new char[1024];
            int length;
            while ((length = inputStreamReader.read(buffer)) != -1) {
                stringBuilder.append(buffer,0,length);
            }
            // Sanitise on read as well: logs written by older versions may still be present.
            return Redaction.redact(stringBuilder.toString(),WeatherSettings.getWeatherUrl(context));
        } catch (IOException | RuntimeException e) {
            // Recorded with its stack trace instead of being silently swallowed.
            WRITER.recordFailure(e);
            return null;
        }
    }

    public static ArrayList<String> getLogsList(Context context){
        WRITER.flush(FLUSH_TIMEOUT_MS);
        try (BufferedReader bufferedReader = new BufferedReader(
                new InputStreamReader(new FileInputStream(context.getFileStreamPath(LOGFILENAME)),
                        StandardCharsets.UTF_8))) {
            ArrayList<String> logList = new ArrayList<String>();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                logList.add(Redaction.redact(line,WeatherSettings.getWeatherUrl(context)));
            }
            return logList;
        } catch (IOException | RuntimeException e) {
            WRITER.recordFailure(e);
            return null;
        }
    }

    public static long getLogFilesize(Context context){
        File path = context.getFilesDir();
        File logfile = new File(path,LOGFILENAME);
        long logsize = logfile.length();
        return logsize;
    }

    public static boolean log(Context context, String s){
        if (context == null) {
            return false;
        }
        try {
            if (!WeatherSettings.loggingEnabled(context)) {
                return false;
            }
            Context app = context.getApplicationContext();
            FILE_SINK.bind(app != null ? app : context);
            String host = WeatherSettings.getWeatherUrl(context);
            lastKnownHost = host;
            boolean logcatEnabled = WeatherSettings.loggingToLogcatEnabled(context)
                    && Redaction.isLogcatAllowed(BuildConfig.DEBUG,BuildConfig.VERSION_NAME);
            installCrashReporterOnce();
            return WRITER.enqueue(s, host, logcatEnabled);
        } catch (RuntimeException e) {
            // Logging must never break its caller. Keep the throwable, do not swallow it silently.
            WRITER.recordFailure(e);
            return false;
        }
    }

    public static boolean log(Context context,String tag, int severity, String s){
        return log(context,tag.toUpperCase(Locale.ROOT)+" ["+severity+"] "+s);
    }

    /** Records a throwable (including its stack trace) through the normal redacting sink. */
    public static boolean logException(Context context, String tag, int severity, Throwable throwable){
        if (throwable == null) {
            return false;
        }
        return log(context, tag, severity, CrashReport.stackTrace(throwable));
    }

    public static class BoxDefinition{
        private final char c1;
        private final char c2;
        private final char c3;
        private final int width;
        public BoxDefinition(int width, String c1, String c2, String c3){
            this.c1 = c1.toCharArray()[0];
            this.c2 = c2.toCharArray()[0];
            this.c3 = c3.toCharArray()[0];
            this.width = width;
        }
        public boolean log(Context context,String tag, int severity, String s){
            StringBuilder messageBuilder = new StringBuilder();
            messageBuilder.append(c1);
            // messageBuilder.append(c2);
            messageBuilder.append(s);
            int gap = width - s.length() - 1;
            for (int i=0; i<gap; i++){
                messageBuilder.append(c2);
            }
            messageBuilder.append(c3);
            return PrivateLog.log(context,tag,severity,messageBuilder.toString());
        }
    }

    public static boolean copyLogsToClipboard(Context context){
        String logs = getLogs(context);
        if (logs != null){
            String applicationName = String.valueOf(context.getApplicationContext().getApplicationInfo().loadDescription(context.getPackageManager()));
            ClipData clipData = new ClipData(CLIPBOARD_LOGLABEL+" "+ applicationName,CLIPBOARD_MIMETYPES,new ClipData.Item(logs));
            ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboardManager.setPrimaryClip(clipData);
            return true;
        } else {
            return false;
        }
    }

    public static boolean clearLogs(Context context){
        WRITER.flush(FLUSH_TIMEOUT_MS);
        WRITER.discardPending();
        File file = new File(context.getFilesDir().getAbsolutePath(),LOGFILENAME);
        try {
            return file.delete();
        } catch (SecurityException e) {
            WRITER.recordFailure(e);
            return false;
        }
    }

    public static class AsyncGetLogs implements Runnable {

        private Context context;

        public AsyncGetLogs(Context context){
            this.context = context;
        }

        public void onNegativeResult(){
        }

        public void onPositiveResult(ArrayList<String> result) {
        }

        @Override
        public void run() {
            ArrayList<String> logs = getLogsList(context);
            if (logs==null){
                onNegativeResult();
            } else {
                onPositiveResult(logs);
            }
        }
    }

    public static String getDebugInfoString(Context context) {
        final String lineBreak = System.getProperty("line.separator");
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(lineBreak);
        stringBuilder.append(lineBreak);
        stringBuilder.append("Debug information");
        stringBuilder.append(lineBreak);
        stringBuilder.append("=================");
        stringBuilder.append(lineBreak);
        return stringBuilder.toString();
    }


    public static String getInfoString(Context context) {
        final String lineBreak = System.getProperty("line.separator");
        DecimalFormat df = new DecimalFormat("#.##");
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(lineBreak);
        stringBuilder.append("Device info:");
        stringBuilder.append(lineBreak);
        stringBuilder.append("------------------------------------------------------"); stringBuilder.append(lineBreak);
        stringBuilder.append("Android SDK version: "+android.os.Build.VERSION.SDK_INT); stringBuilder.append(lineBreak);
        stringBuilder.append("Android SDK version: "+ Build.VERSION.CODENAME); stringBuilder.append(lineBreak);
        stringBuilder.append("Android build: "+ Build.DISPLAY); stringBuilder.append(lineBreak);
        stringBuilder.append("Hardware: "+ Build.HARDWARE); stringBuilder.append(lineBreak);
        stringBuilder.append("Product: "+ Build.PRODUCT); stringBuilder.append(lineBreak);
        stringBuilder.append("Model: "+ Build.MODEL); stringBuilder.append(lineBreak);
        stringBuilder.append("Manufacturer: "+ Build.MANUFACTURER); stringBuilder.append(lineBreak);
        stringBuilder.append("App build: "+ BuildConfig.VERSION_CODE); stringBuilder.append(lineBreak);
        stringBuilder.append("App build name: "+ BuildConfig.VERSION_NAME); stringBuilder.append(lineBreak);
        stringBuilder.append("------------------------------------------------------"); stringBuilder.append(lineBreak);
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE); stringBuilder.append(lineBreak);
        stringBuilder.append(lineBreak);
        Display display = windowManager.getDefaultDisplay();
        DisplayMetrics displayMetrics = new DisplayMetrics();
        display.getMetrics(displayMetrics);
        stringBuilder.append("Available display:");
        stringBuilder.append(lineBreak);
        stringBuilder.append("Logical density: "+displayMetrics.density);
        stringBuilder.append(lineBreak);
        stringBuilder.append("Scaled density: "+displayMetrics.scaledDensity);
        stringBuilder.append(lineBreak);
        stringBuilder.append("Width  (pixels): "+displayMetrics.widthPixels);
        stringBuilder.append(lineBreak);
        stringBuilder.append("Height (pixels): "+displayMetrics.heightPixels);
        stringBuilder.append(lineBreak);
        stringBuilder.append("x-dpi: "+displayMetrics.xdpi);
        stringBuilder.append(lineBreak);
        stringBuilder.append("y-dpi: "+displayMetrics.ydpi);
        stringBuilder.append(lineBreak);
        stringBuilder.append("User-set fontScale: "+context.getResources().getConfiguration().fontScale);
        stringBuilder.append(lineBreak);
        stringBuilder.append("calculated physical metrics:");
        stringBuilder.append(lineBreak);
        stringBuilder.append("Width  (x): "+df.format(displayMetrics.widthPixels/displayMetrics.xdpi) +" inch");
        stringBuilder.append(lineBreak);
        stringBuilder.append("Height (y): "+df.format(displayMetrics.heightPixels/displayMetrics.ydpi) +" inch");
        stringBuilder.append(lineBreak);
        stringBuilder.append("Pixel-independent metrics:");
        stringBuilder.append(lineBreak);
        // px = dp * (dpi/160)
        // px/(dpi/160) = dp
        stringBuilder.append("Width (x) in dp: "+Math.round(displayMetrics.widthPixels/(displayMetrics.xdpi/160)));
        stringBuilder.append(lineBreak);
        stringBuilder.append("Height (y) in dp: "+Math.round(displayMetrics.heightPixels/(displayMetrics.ydpi/160)));
        stringBuilder.append(lineBreak);
        // Mem Info
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        stringBuilder.append(lineBreak);
        stringBuilder.append("RAM available: "+memoryInfo.availMem/(1024*1024)+ "Mb RAM total: "+memoryInfo.totalMem/(1024*1024)+" Mb");
        stringBuilder.append(lineBreak);
        stringBuilder.append("Threshold for low memory state: "+memoryInfo.threshold/(1024*1024)+ " Mb");
        stringBuilder.append(lineBreak);
        if (memoryInfo.lowMemory){
            stringBuilder.append("The device is in a low memory state, potentially killing services and other processes.");
        } else {
            stringBuilder.append("The device is not in a low memory state.");
        }
        stringBuilder.append(lineBreak);
        return stringBuilder.toString();
    }

    public static String getCurrentStationInfoString(Context context){
        Weather.WeatherLocation weatherLocation = WeatherSettings.getSetStationLocation(context);
        final String lineBreak = System.getProperty("line.separator");
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(lineBreak);
        stringBuilder.append("Currently set station in settings:");
        stringBuilder.append(lineBreak);
        stringBuilder.append("Station name: "+weatherLocation.getName());
        stringBuilder.append(lineBreak);
        stringBuilder.append("Station description: "+weatherLocation.getDescription(context));
        stringBuilder.append(lineBreak);
        return stringBuilder.toString();
    }


}

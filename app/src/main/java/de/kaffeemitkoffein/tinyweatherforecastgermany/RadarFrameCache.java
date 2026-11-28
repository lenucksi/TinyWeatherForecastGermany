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
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TASK-88: storage foundation for keyed radar frames.
 *
 * <p>Every frame is stored under {@code cache/radar/<layer>-<epochSeconds>-<width>x<height>.png}.
 * The dimensions are part of the key so a scale change (rotation / high-resolution map) can
 * never make the app read a frame rendered for the other scale.</p>
 *
 * <p>Writes go to a {@code .tmp} sibling and are renamed only after the complete stream has
 * been validated as a PNG with the requested IHDR dimensions. An invalid response (for
 * example a GeoServer XML error body) therefore never becomes a final cache file. An in-memory
 * LRU index pins the currently displayed 24-frame anchor and bounds the cache by count and by
 * bytes.</p>
 *
 * <p>All helpers are static and free of Activity state so Robolectric tests can exercise the
 * validation, eviction and migration rules directly.</p>
 */
public final class RadarFrameCache {

    private RadarFrameCache() {
    }

    public static final String CACHE_DIRECTORY   = "radar";
    public static final String LEGACY_PREFIX     = "radarMN-";
    public static final String LEGACY_SUFFIX     = ".png";
    public static final String TEMP_SUFFIX       = ".tmp";
    public static final String LAYER_RAIN_RADAR  = "radarwn";

    /** getFixedRadarMapWidth() returns this value times the scale factor. */
    public static final int BASE_FRAME_WIDTH = 1108;

    /** Keep the historical lower bound for a real DWD composite. */
    public static final int MIN_FRAME_BYTES = 5000;

    /** 12 complete sets of 24 frames. */
    public static final int MAX_ENTRIES = 288;
    public static final long MAX_BYTES_SCALE_1 = 32L * 1024L * 1024L;
    public static final long MAX_BYTES_SCALE_2 = 64L * 1024L * 1024L;

    private static final String PREF_MIGRATED = "radar_frame_cache_migrated_v1";

    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47,
            (byte) 0x0d, (byte) 0x0a, (byte) 0x1a, (byte) 0x0a
    };

    /** An entry in the LRU index. Public so tests can build deterministic eviction scenarios. */
    public static final class Entry {
        public final String key;
        public final File file;
        public final int width;
        public final int height;
        public final long bytes;

        public Entry(String key, File file, int width, int height, long bytes) {
            this.key = key;
            this.file = file;
            this.width = width;
            this.height = height;
            this.bytes = bytes;
        }

        public int scale() {
            return scaleForWidth(width);
        }
    }

    private static final Object LOCK = new Object();
    /** accessOrder=true: iteration runs from least recently used to most recently used. */
    private static final LinkedHashMap<String, Entry> INDEX =
            new LinkedHashMap<String, Entry>(32, 0.75f, true);
    private static final Set<String> PINNED = new HashSet<String>();
    private static boolean initialized = false;

    // ------------------------------------------------------------------ keys & paths

    public static String key(String layer, long epochSeconds, int width, int height) {
        return layer + "-" + epochSeconds + "-" + width + "x" + height + ".png";
    }

    /** Frames are timestamped in whole seconds, like the DWD time parameter. */
    public static long epochSeconds(long timeMillis) {
        return Math.floorDiv(timeMillis, 1000L);
    }

    public static int scaleForWidth(int width) {
        return width > BASE_FRAME_WIDTH ? 2 : 1;
    }

    public static long maxBytesForScale(int scale) {
        return scale == 2 ? MAX_BYTES_SCALE_2 : MAX_BYTES_SCALE_1;
    }

    public static File cacheDirectory(Context context) {
        File directory = new File(context.getCacheDir(), CACHE_DIRECTORY);
        if (!directory.isDirectory()) {
            directory.mkdirs();
        }
        return directory;
    }

    public static File file(Context context, String layer, long epochSeconds, int width, int height) {
        return new File(cacheDirectory(context), key(layer, epochSeconds, width, height));
    }

    /** Extracts the dimensions encoded in a keyed file name, or null for any other file. */
    public static int[] dimensionsFromKey(String fileName) {
        if (fileName == null || !fileName.endsWith(LEGACY_SUFFIX)) {
            return null;
        }
        String stem = fileName.substring(0, fileName.length() - LEGACY_SUFFIX.length());
        int dash = stem.lastIndexOf('-');
        if (dash < 0) {
            return null;
        }
        String dimensions = stem.substring(dash + 1);
        int separator = dimensions.indexOf('x');
        if (separator <= 0) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(dimensions.substring(0, separator)),
                    Integer.parseInt(dimensions.substring(separator + 1))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ validation

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16) |
                ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    /** A GeoServer error is XML/HTML text, never a PNG stream. */
    public static boolean looksLikeXmlError(byte[] bytes) {
        if (bytes == null || bytes.length < 5) {
            return false;
        }
        return bytes[0] == '<' || (bytes[0] == 0xef && bytes[1] == 0xbb && bytes[2] == 0xbf);
    }

    /** Pure header check: PNG signature, IHDR chunk and exact requested dimensions. */
    public static boolean hasValidPngHeader(byte[] header, int expectedWidth, int expectedHeight) {
        if (header == null || header.length < 24 || looksLikeXmlError(header)) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (header[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        if (header[12] != 'I' || header[13] != 'H' || header[14] != 'D' || header[15] != 'R') {
            return false;
        }
        return readInt(header, 16) == expectedWidth && readInt(header, 20) == expectedHeight;
    }

    public static boolean isValidPng(File file, int expectedWidth, int expectedHeight) {
        if (file == null || !file.isFile() || file.length() < MIN_FRAME_BYTES) {
            return false;
        }
        byte[] header = new byte[24];
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            int read = 0;
            while (read < header.length) {
                int count = input.read(header, read, header.length - read);
                if (count < 0) {
                    return false;
                }
                read += count;
            }
            return hasValidPngHeader(header, expectedWidth, expectedHeight);
        } catch (IOException e) {
            return false;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // nothing left to do
                }
            }
        }
    }

    /**
     * Streams into a {@code .tmp} sibling, validates the complete result and only then
     * renames it to its final name. Nothing invalid is ever visible under the final key.
     *
     * @return true only when the validated file is in place.
     */
    public static boolean writeAtomic(File target, int width, int height, InputStream input) {
        if (target == null || input == null) {
            return false;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return false;
        }
        File temporary = new File(parent, target.getName() + TEMP_SUFFIX);
        FileOutputStream output = null;
        boolean renamed = false;
        try {
            output = new FileOutputStream(temporary, false);
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > 0) {
                    output.write(buffer, 0, count);
                }
            }
            output.flush();
            output.close();
            output = null;
            if (!isValidPng(temporary, width, height)) {
                return false;
            }
            renamed = temporary.renameTo(target);
            return renamed;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                    // nothing left to do
                }
            }
            try {
                input.close();
            } catch (IOException ignored) {
                // nothing left to do
            }
            if (!renamed && temporary.exists()) {
                temporary.delete();
            }
        }
    }

    // ------------------------------------------------------------------ LRU index

    /** Pure eviction helper: removes entries in place and returns them in eviction order. */
    public static List<Entry> evictToFit(LinkedHashMap<String, Entry> entries, Set<String> pinned,
                                         int maxCount, long maxBytesScale1, long maxBytesScale2) {
        List<Entry> evicted = new ArrayList<Entry>();
        if (entries == null) {
            return evicted;
        }
        while (!entries.isEmpty()) {
            long bytes1 = 0;
            long bytes2 = 0;
            for (Entry entry : entries.values()) {
                if (entry.scale() == 2) {
                    bytes2 += entry.bytes;
                } else {
                    bytes1 += entry.bytes;
                }
            }
            boolean overLimit = entries.size() > maxCount || bytes1 > maxBytesScale1 ||
                    bytes2 > maxBytesScale2;
            if (!overLimit) {
                break;
            }
            Entry victim = null;
            for (Entry entry : entries.values()) {
                if ((pinned != null) && pinned.contains(entry.key)) {
                    continue;
                }
                boolean violates = entries.size() > maxCount ||
                        ((entry.scale() == 1) && (bytes1 > maxBytesScale1)) ||
                        ((entry.scale() == 2) && (bytes2 > maxBytesScale2));
                if (violates) {
                    victim = entry;
                    break;
                }
            }
            if (victim == null) {
                break; // only pinned frames left; never evict the displayed anchor
            }
            entries.remove(victim.key);
            evicted.add(victim);
        }
        return evicted;
    }

    private static void enforceCaps() {
        List<Entry> evicted = evictToFit(INDEX, PINNED, MAX_ENTRIES,
                MAX_BYTES_SCALE_1, MAX_BYTES_SCALE_2);
        for (Entry entry : evicted) {
            if (entry.file.isFile()) {
                entry.file.delete();
            }
        }
    }

    /** Pins the 24 keyed frames of the currently displayed set. */
    public static synchronized void setAnchor(Context context) {
        long startMillis = WeatherSettings.getPrefRadarLastdatapoll(context);
        int width = RadarMN2.getFixedRadarMapWidth(context);
        int height = RadarMN2.getFixedRadarMapHeight(context);
        Set<String> anchor = new HashSet<String>();
        for (int i = 0; i < APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE; i++) {
            File frame = file(context, LAYER_RAIN_RADAR,
                    epochSeconds(startMillis + i * APIReaders.RadarMNSetGeoserverRunnable.TIMESTEP_5MINUTES),
                    width, height);
            anchor.add(frame.getAbsolutePath());
        }
        PINNED.clear();
        PINNED.addAll(anchor);
        for (String key : anchor) {
            Entry entry = INDEX.get(key);
            if (entry != null) {
                INDEX.remove(key);
                INDEX.put(key, entry);
            }
        }
        enforceCaps();
    }

    public static synchronized void initialize(Context context) {
        migrateIfNeeded(context);
        INDEX.clear();
        PINNED.clear();
        File[] files = cacheDirectory(context).listFiles();
        if (files != null) {
            for (File frame : files) {
                if (!frame.isFile() || !frame.getName().endsWith(LEGACY_SUFFIX)) {
                    continue;
                }
                int[] dimensions = dimensionsFromKey(frame.getName());
                if ((dimensions != null) && isValidPng(frame, dimensions[0], dimensions[1])) {
                    Entry entry = new Entry(frame.getAbsolutePath(), frame,
                            dimensions[0], dimensions[1], frame.length());
                    INDEX.put(entry.key, entry);
                }
            }
        }
        initialized = true;
        setAnchor(context);
    }

    public static synchronized void register(Context context, File frame, int width, int height,
                                             boolean pinned) {
        if (!initialized) {
            initialize(context);
        }
        if ((frame == null) || !frame.isFile()) {
            return;
        }
        String key = frame.getAbsolutePath();
        Entry entry = new Entry(key, frame, width, height, frame.length());
        INDEX.remove(key);
        INDEX.put(key, entry);
        if (pinned) {
            PINNED.add(key);
        }
        enforceCaps();
    }

    /** Marks an entry as recently used; drops stale entries whose file has disappeared. */
    public static synchronized void touch(File frame) {
        if (!initialized || frame == null) {
            return;
        }
        String key = frame.getAbsolutePath();
        Entry entry = INDEX.get(key);
        if (entry == null) {
            return;
        }
        if (!frame.isFile()) {
            INDEX.remove(key);
            PINNED.remove(key);
            return;
        }
        INDEX.remove(key);
        INDEX.put(key, entry);
    }

    public static synchronized void unregister(File frame) {
        if (frame == null) {
            return;
        }
        String key = frame.getAbsolutePath();
        INDEX.remove(key);
        PINNED.remove(key);
    }

    /** Test access to the current index (a copy; access order is preserved). */
    public static synchronized Map<String, Entry> indexSnapshot() {
        return new LinkedHashMap<String, Entry>(INDEX);
    }

    public static synchronized Set<String> pinnedSnapshot() {
        return new HashSet<String>(PINNED);
    }

    // ------------------------------------------------------------------ migration & clearing

    private static final FilenameFilter LEGACY_FILTER = new FilenameFilter() {
        @Override
        public boolean accept(File directory, String name) {
            return name.startsWith(LEGACY_PREFIX) && name.endsWith(LEGACY_SUFFIX);
        }
    };

    private static final FilenameFilter TEMP_FILTER = new FilenameFilter() {
        @Override
        public boolean accept(File directory, String name) {
            return name.endsWith(TEMP_SUFFIX);
        }
    };

    /** Deletes legacy frames and leftover temporary files; returns the number of frames removed. */
    public static synchronized int migrate(Context context) {
        int removed = 0;
        File[] legacy = context.getCacheDir().listFiles(LEGACY_FILTER);
        if (legacy != null) {
            for (File frame : legacy) {
                if (frame.isFile() && frame.delete()) {
                    removed++;
                }
            }
        }
        File[] temporary = cacheDirectory(context).listFiles(TEMP_FILTER);
        if (temporary != null) {
            for (File frame : temporary) {
                if (frame.isFile()) {
                    frame.delete();
                }
            }
        }
        return removed;
    }

    /** Runs the legacy purge at most once per install. */
    public static synchronized int migrateIfNeeded(Context context) {
        SharedPreferences preferences = WeatherSettings.getSharedPreferences(context);
        if (preferences.getBoolean(PREF_MIGRATED, false)) {
            return 0;
        }
        int removed = migrate(context);
        preferences.edit().putBoolean(PREF_MIGRATED, true).apply();
        return removed;
    }

    /** Clears the whole keyed cache directory (and the LRU index) plus any legacy frames. */
    public static synchronized int clear(Context context) {
        if (!initialized) {
            initialize(context);
        }
        int removed = 0;
        for (Entry entry : new ArrayList<Entry>(INDEX.values())) {
            if (entry.file.isFile() && entry.file.delete()) {
                removed++;
            }
        }
        INDEX.clear();
        PINNED.clear();
        File[] files = cacheDirectory(context).listFiles();
        if (files != null) {
            for (File frame : files) {
                if (frame.isFile() && frame.delete()) {
                    removed++;
                }
            }
        }
        removed += migrate(context);
        return removed;
    }
}

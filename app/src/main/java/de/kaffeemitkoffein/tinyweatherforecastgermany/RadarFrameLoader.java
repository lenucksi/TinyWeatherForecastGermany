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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TASK-90: lazy, bounded loader for arbitrary absolute radar frames.
 *
 * <ul>
 *   <li>one on-demand worker; concurrent requests for the same frame are deduplicated;</li>
 *   <li>failed/missing frames are negatively cached so scrubbing cannot start a retry storm;</li>
 *   <li>2-4 prefetch workers fetch only the next three frames and are cancelled on scrub;</li>
 *   <li>every result carries the generation that requested it; late results are dropped;</li>
 *   <li>offline/unavailable frames are reported honestly - never skipped or gap-wrapped;</li>
 *   <li>metered connections are used only when the TASK-89 mobile-data setting allows it;</li>
 *   <li>files are written through the validated TASK-88 {@link RadarFrameCache} seam.</li>
 * </ul>
 *
 * <p>Callbacks may arrive on worker threads; callers that touch views must re-post to the UI
 * thread. The owner must call {@link #shutdown()} when its lifecycle ends.</p>
 */
public final class RadarFrameLoader {

    public interface FrameCallback {
        /** available=false means "honestly unavailable", including offline and denied policy. */
        void onFrame(long timeMillis, boolean available, boolean cancelled);
    }

    public static final long NEGATIVE_CACHE_MILLIS = 60L * 1000L;
    /** TASK-90 review: a wider, cancellable window keeps playback from stalling on scrubs. */
    public static final int PREFETCH_FRAMES = 8;
    /** Small in-memory bitmap LRU around the current frame (bounded memory, no UI decoding). */
    public static final int FRAME_BITMAP_LRU_SIZE = 8;

    private final Context context;
    private final String host;
    private final int width;
    private final int height;
    private final int decodeSampleSize;

    /** accessOrder=true: iteration runs least-recently-used first. Guarded by its own monitor. */
    private final LinkedHashMap<Long, android.graphics.Bitmap> bitmaps =
            new LinkedHashMap<Long, android.graphics.Bitmap>(FRAME_BITMAP_LRU_SIZE, 0.75f, true);

    private final ExecutorService onDemandExecutor;
    private final ExecutorService prefetchExecutor;
    private final APIReaders.Cancellation onDemandCancellation = new APIReaders.Cancellation();
    private final APIReaders.Cancellation prefetchCancellation = new APIReaders.Cancellation();

    /** Keyed by epoch seconds: every waiter for an in-flight frame. */
    private final ConcurrentHashMap<Long, List<FrameCallback>> inFlight =
            new ConcurrentHashMap<Long, List<FrameCallback>>();
    private final ConcurrentHashMap<Long, Long> negativeUntil =
            new ConcurrentHashMap<Long, Long>();

    private final AtomicLong generation = new AtomicLong(0L);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger cacheHits = new AtomicInteger();
    private final AtomicInteger deduped = new AtomicInteger();
    private final AtomicInteger negativeHits = new AtomicInteger();
    private final AtomicInteger networkLoads = new AtomicInteger();
    private final AtomicInteger prefetchIssued = new AtomicInteger();
    private final AtomicInteger prefetchLoaded = new AtomicInteger();
    private final AtomicInteger errors = new AtomicInteger();
    private final AtomicInteger droppedLate = new AtomicInteger();

    /** Immutable counters snapshot for tests and diagnostics. */
    public static final class Counters {
        public final int requests;
        public final int cacheHits;
        public final int deduped;
        public final int negativeHits;
        public final int networkLoads;
        public final int prefetchIssued;
        public final int prefetchLoaded;
        public final int errors;
        public final int droppedLate;

        Counters(int requests, int cacheHits, int deduped, int negativeHits, int networkLoads,
                 int prefetchIssued, int prefetchLoaded, int errors, int droppedLate) {
            this.requests = requests;
            this.cacheHits = cacheHits;
            this.deduped = deduped;
            this.negativeHits = negativeHits;
            this.networkLoads = networkLoads;
            this.prefetchIssued = prefetchIssued;
            this.prefetchLoaded = prefetchLoaded;
            this.errors = errors;
            this.droppedLate = droppedLate;
        }

        @Override
        public String toString() {
            return "requests=" + requests + " cacheHits=" + cacheHits + " deduped=" + deduped
                    + " negativeHits=" + negativeHits + " networkLoads=" + networkLoads
                    + " prefetchIssued=" + prefetchIssued + " prefetchLoaded=" + prefetchLoaded
                    + " errors=" + errors + " droppedLate=" + droppedLate;
        }
    }

    public RadarFrameLoader(Context context, String prefetchProfile) {
        this.context = context == null ? null : context.getApplicationContext();
        this.host = APIReaders.HttpsUrl.validatedHost("maps.dwd.de");
        this.width = this.context == null ? RadarFrameCache.BASE_FRAME_WIDTH
                : RadarMN2.getFixedRadarMapWidth(this.context);
        this.height = this.context == null ? 1360
                : RadarMN2.getFixedRadarMapHeight(this.context);
        int targetWidth = this.context == null ? width
                : this.context.getResources().getDisplayMetrics().widthPixels;
        this.decodeSampleSize = Math.max(1, width / Math.max(1, targetWidth));
        int prefetchThreads = prefetchThreadCount(prefetchProfile);
        this.onDemandExecutor = Executors.newSingleThreadExecutor();
        this.prefetchExecutor = Executors.newFixedThreadPool(prefetchThreads);
    }

    /** minimal=2, balanced=3, full=4 bounded prefetch workers. */
    public static int prefetchThreadCount(String profile) {
        if (WeatherSettings.Radar.PREFETCH_MINIMAL.equals(profile)) {
            return 2;
        }
        if (WeatherSettings.Radar.PREFETCH_FULL.equals(profile)) {
            return 4;
        }
        return 3;
    }

    /** Pure network-policy decision; metered links require the explicit radar setting. */
    public static boolean networkAllowed(boolean connected, boolean metered, boolean mobileDataAllowed) {
        return connected && (!metered || mobileDataAllowed);
    }

    /** Test seam: forces the connectivity decision without touching a real radio. */
    Boolean networkOverride;

    void setNetworkAllowedForTests(Boolean allowed) {
        networkOverride = allowed;
    }

    /** Applies the TASK-89 mobile-data toggle on top of the platform connectivity state. */
    public boolean networkAllowed() {
        if (networkOverride != null) {
            return networkOverride;
        }
        if (context == null) {
            return false;
        }
        boolean connected = false;
        boolean metered = true;
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = manager.getActiveNetwork();
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                connected = capabilities != null
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                metered = capabilities == null
                        || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            } else {
                NetworkInfo info = manager.getActiveNetworkInfo();
                connected = info != null && info.isConnected();
                metered = info == null || info.getType() != ConnectivityManager.TYPE_WIFI;
            }
        } catch (SecurityException e) {
            return false;
        }
        return networkAllowed(connected, metered,
                WeatherSettings.useRadarOnMobileData(context));
    }

    private static long key(long timeMillis) {
        return RadarFrameCache.epochSeconds(timeMillis);
    }

    private File cachedFile(long timeMillis) {
        if (context == null) {
            return null;
        }
        return RadarFrameCache.file(context, RadarFrameCache.LAYER_RAIN_RADAR,
                key(timeMillis), width, height);
    }

    /** True when a validated frame is already on disk (the TASK-88 cache is reused as-is). */
    public boolean isCached(long timeMillis) {
        File file = cachedFile(timeMillis);
        return file != null && RadarFrameCache.isValidPng(file, width, height);
    }

    /** Returns an already-decoded frame from the bounded bitmap LRU; never decodes here. */
    public android.graphics.Bitmap bitmapForTime(long timeMillis) {
        synchronized (bitmaps) {
            android.graphics.Bitmap bitmap = bitmaps.get(key(timeMillis));
            if (bitmap != null) {
                // Touch access order without removing the entry.
                bitmaps.get(key(timeMillis));
            }
            return bitmap;
        }
    }

    /** True when a bitmap is decoded and ready for immediate display. */
    public boolean hasReadyBitmap(long timeMillis) {
        return bitmapForTime(timeMillis) != null;
    }

    private void rememberBitmap(long timeMillis, android.graphics.Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        synchronized (bitmaps) {
            long cacheKey = key(timeMillis);
            android.graphics.Bitmap old = bitmaps.remove(cacheKey);
            bitmaps.put(cacheKey, bitmap);
            while (bitmaps.size() > FRAME_BITMAP_LRU_SIZE) {
                Map.Entry<Long, android.graphics.Bitmap> eldest =
                        bitmaps.entrySet().iterator().next();
                bitmaps.remove(eldest.getKey());
            }
            if (old != null && old != bitmap) {
                old.recycle();
            }
        }
    }

    private void forgetBitmap(long timeMillis) {
        synchronized (bitmaps) {
            bitmaps.remove(key(timeMillis));
        }
    }

    /**
     * Background-only decode with a view-sized sample size and the same transparency
     * corrections as {@link RadarMN2#getBitmap(Context, int)}. No UI thread ever touches a
     * full-resolution DWD PNG.
     */
    private android.graphics.Bitmap decodeBitmap(long timeMillis) {
        File file = cachedFile(timeMillis);
        if (file == null || !RadarFrameCache.isValidPng(file, width, height)) {
            return null;
        }
        android.graphics.BitmapFactory.Options options =
                new android.graphics.BitmapFactory.Options();
        options.inSampleSize = decodeSampleSize;
        options.inMutable = true;
        android.graphics.Bitmap bitmap =
                android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) {
            return null;
        }
        int decodedWidth = bitmap.getWidth();
        int decodedHeight = bitmap.getHeight();
        int[] pixels = new int[decodedWidth * decodedHeight];
        bitmap.getPixels(pixels, 0, decodedWidth, 0, 0, decodedWidth, decodedHeight);
        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] == -4276546 || pixels[i] == -245761
                    || pixels[i] == -4342339 || pixels[i] == -1) {
                pixels[i] = android.graphics.Color.TRANSPARENT;
            }
        }
        bitmap.setPixels(pixels, 0, decodedWidth, 0, 0, decodedWidth, decodedHeight);
        RadarFrameCache.touch(file);
        return bitmap;
    }

    /** Decodes on the on-demand worker and caches the result for immediate later frames. */
    private void decodeInBackground(final long timeMillis) {
        if (shutdown.get()) {
            return;
        }
        try {
            onDemandExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    android.graphics.Bitmap bitmap = decodeBitmap(timeMillis);
                    if (bitmap != null) {
                        rememberBitmap(timeMillis, bitmap);
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // The caller still gets the file-level result; a later frame can retry decoding.
        }
    }

    private boolean negativelyCached(long timeMillis, long nowMillis) {
        Long until = negativeUntil.get(key(timeMillis));
        return until != null && until > nowMillis;
    }

    private void rememberNegative(long timeMillis) {
        negativeUntil.put(key(timeMillis), System.currentTimeMillis() + NEGATIVE_CACHE_MILLIS);
    }

    /** Drops negative entries so a genuine retry (new anchor / restored network) can happen. */
    public void clearNegativeCache() {
        negativeUntil.clear();
    }

    private static void deliver(FrameCallback callback, long timeMillis, boolean available,
                                boolean cancelled) {
        if (callback != null) {
            callback.onFrame(timeMillis, available, cancelled);
        }
    }

    /**
     * Requests one absolute frame. A cached frame is delivered immediately; concurrent requests
     * for the same frame share one network operation. Unavailable frames are reported, never
     * skipped. The returned boolean is true when the request was accepted (cache hit or queued).
     */
    public boolean load(long timeMillis, FrameCallback callback) {
        if (shutdown.get()) {
            deliver(callback, timeMillis, false, true);
            return false;
        }
        requests.incrementAndGet();
        long now = System.currentTimeMillis();
        if (isCached(timeMillis)) {
            cacheHits.incrementAndGet();
            RadarFrameCache.touch(cachedFile(timeMillis));
            if (bitmapForTime(timeMillis)!=null){
                deliver(callback, timeMillis, true, false);
                return true;
            }
            // Decode off the UI thread and deliver only once the frame is displayable.
            long decodeKey = key(timeMillis);
            List<FrameCallback> waiters = new CopyOnWriteArrayList<FrameCallback>();
            List<FrameCallback> existing = inFlight.putIfAbsent(decodeKey, waiters);
            if (existing != null) {
                deduped.incrementAndGet();
                if (callback != null) {
                    existing.add(callback);
                }
                return true;
            }
            if (callback != null) {
                waiters.add(callback);
            }
            final long decodeGeneration = generation.get();
            try {
                onDemandExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        android.graphics.Bitmap bitmap = decodeBitmap(timeMillis);
                        if (bitmap != null) {
                            rememberBitmap(timeMillis, bitmap);
                        }
                        finish(timeMillis, decodeKey, decodeGeneration, waiters,
                                bitmap != null, false);
                    }
                });
            } catch (RuntimeException e) {
                inFlight.remove(decodeKey);
                errors.incrementAndGet();
                deliver(callback, timeMillis, false, false);
                return false;
            }
            return true;
        }
        if (negativelyCached(timeMillis, now)) {
            negativeHits.incrementAndGet();
            deliver(callback, timeMillis, false, false);
            return false;
        }
        long cacheKey = key(timeMillis);
        List<FrameCallback> waiters = new CopyOnWriteArrayList<FrameCallback>();
        List<FrameCallback> existing = inFlight.putIfAbsent(cacheKey, waiters);
        if (existing != null) {
            deduped.incrementAndGet();
            if (callback != null) {
                existing.add(callback);
            }
            return true;
        }
        if (callback != null) {
            waiters.add(callback);
        }
        final long requestedGeneration = generation.get();
        try {
            onDemandExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    fetch(timeMillis, cacheKey, requestedGeneration, false);
                }
            });
        } catch (RuntimeException e) {
            inFlight.remove(cacheKey);
            rememberNegative(timeMillis);
            errors.incrementAndGet();
            deliver(callback, timeMillis, false, false);
            return false;
        }
        return true;
    }

    private void fetch(long timeMillis, long cacheKey, long requestedGeneration, boolean prefetch) {
        List<FrameCallback> waiters = inFlight.get(cacheKey);
        try {
            if (!networkAllowed()) {
                rememberNegative(timeMillis);
                errors.incrementAndGet();
                finish(timeMillis, cacheKey, requestedGeneration, waiters, false, false);
                return;
            }
            URL url = getMapUrl(timeMillis);
            if (url == null) {
                rememberNegative(timeMillis);
                errors.incrementAndGet();
                finish(timeMillis, cacheKey, requestedGeneration, waiters, false, false);
                return;
            }
            InputStream input = APIReaders.Http.get(url,
                    prefetch ? prefetchCancellation : onDemandCancellation);
            File target = cachedFile(timeMillis);
            boolean stored = RadarFrameCache.writeAtomic(target, width, height, input);
            if (!stored && target != null && target.isFile() && !target.delete()) {
                // Leave the invalid target in place; writeAtomic only publishes valid PNGs.
            }
            if (stored) {
                RadarFrameCache.register(context, target, width, height, false);
                // Decode on this worker and warm the bitmap LRU so the next 750 ms tick
                // can paint without touching a full-resolution PNG on the UI thread.
                android.graphics.Bitmap decoded = decodeBitmap(timeMillis);
                if (decoded != null) {
                    rememberBitmap(timeMillis, decoded);
                }
                if (prefetch) {
                    prefetchLoaded.incrementAndGet();
                } else {
                    networkLoads.incrementAndGet();
                }
            } else {
                rememberNegative(timeMillis);
                errors.incrementAndGet();
            }
            finish(timeMillis, cacheKey, requestedGeneration, waiters, stored, false);
        } catch (Exception e) {
            rememberNegative(timeMillis);
            errors.incrementAndGet();
            finish(timeMillis, cacheKey, requestedGeneration, waiters, false, false);
        }
    }

    private void finish(long timeMillis, long cacheKey, long requestedGeneration,
                        List<FrameCallback> waiters, boolean available, boolean cancelled) {
        inFlight.remove(cacheKey);
        if (waiters == null) {
            return;
        }
        boolean drop = requestedGeneration != generation.get() || shutdown.get();
        if (drop) {
            droppedLate.incrementAndGet();
            for (FrameCallback callback : waiters) {
                deliver(callback, timeMillis, false, true);
            }
            return;
        }
        for (FrameCallback callback : waiters) {
            deliver(callback, timeMillis, available, cancelled);
        }
    }

    /**
     * Cancels outstanding prefetch work and invalidates late results. Called on every scrub so
     * a fast drag cannot leave stale frames on screen or waste bandwidth.
     */
    public void cancelPending() {
        generation.incrementAndGet();
        prefetchCancellation.cancel();
        prefetchCancellation.reset();
    }

    /** Prefetches at most the next three frames of the selected window. */
    public void prefetchAfter(int index, RadarTimeline timeline) {
        if (shutdown.get() || timeline == null || !networkAllowed()) {
            return;
        }
        final long requestedGeneration = generation.get();
        for (int offset = 1; offset <= PREFETCH_FRAMES; offset++) {
            int targetIndex = index + offset;
            if (targetIndex >= timeline.frameCount) {
                break;
            }
            final long timeMillis = timeline.timeForIndex(targetIndex);
            final long cacheKey = key(timeMillis);
            if (isCached(timeMillis) || negativelyCached(timeMillis, System.currentTimeMillis())
                    || inFlight.containsKey(cacheKey)) {
                continue;
            }
            List<FrameCallback> waiters = new CopyOnWriteArrayList<FrameCallback>();
            if (inFlight.putIfAbsent(cacheKey, waiters) != null) {
                continue;
            }
            prefetchIssued.incrementAndGet();
            try {
                prefetchExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
                        fetch(timeMillis, cacheKey, requestedGeneration, true);
                    }
                });
            } catch (RuntimeException e) {
                inFlight.remove(cacheKey);
                errors.incrementAndGet();
            }
        }
    }

    /**
     * Incremental refresh: only forecast frames of the new DWD reference run are invalidated.
     * Analysis frames (older than the anchor) are immutable and are never deleted or refetched.
     *
     * @return the number of forecast frames that were dropped for a lazy re-fetch.
     */
    public int refreshAnchor(long newAnchorMillis, RadarTimeline timeline) {
        if (timeline == null) {
            return 0;
        }
        clearNegativeCache();
        cancelPending();
        int invalidated = 0;
        for (int i = 0; i < timeline.frameCount; i++) {
            long timeMillis = timeline.timeForIndex(i);
            if (!timeline.isForecast(timeMillis)) {
                continue;
            }
            File file = cachedFile(timeMillis);
            if (file != null && file.isFile() && file.delete()) {
                RadarFrameCache.unregister(file);
                forgetBitmap(timeMillis);
                invalidated++;
            }
        }
        return invalidated;
    }

    public Counters counters() {
        return new Counters(requests.get(), cacheHits.get(), deduped.get(), negativeHits.get(),
                networkLoads.get(), prefetchIssued.get(), prefetchLoaded.get(), errors.get(),
                droppedLate.get());
    }

    /** Stops both executors; in-flight callbacks are delivered as cancelled. */
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        generation.incrementAndGet();
        onDemandCancellation.cancel();
        prefetchCancellation.cancel();
        onDemandExecutor.shutdownNow();
        prefetchExecutor.shutdownNow();
        synchronized (bitmaps) {
            bitmaps.clear();
        }
        for (Long cacheKey : new ArrayList<Long>(inFlight.keySet())) {
            List<FrameCallback> waiters = inFlight.remove(cacheKey);
            if (waiters != null) {
                for (FrameCallback callback : waiters) {
                    deliver(callback, cacheKey * 1000L, false, true);
                }
            }
        }
    }

    public boolean isShutdown() {
        return shutdown.get();
    }

    // ------------------------------------------------------------------ WMS GetMap URL

    private static String timeParameter(long timeMillis) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        calendar.setTimeInMillis(timeMillis);
        StringBuilder builder = new StringBuilder("time=");
        builder.append(calendar.get(Calendar.YEAR)).append('-');
        int month = calendar.get(Calendar.MONTH) + 1;
        if (month < 10) {
            builder.append('0');
        }
        builder.append(month).append('-');
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        if (day < 10) {
            builder.append('0');
        }
        builder.append(day).append('T');
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        if (hour < 10) {
            builder.append('0');
        }
        builder.append(hour).append(':');
        int minute = calendar.get(Calendar.MINUTE);
        if (minute < 10) {
            builder.append('0');
        }
        builder.append(minute).append(":00.000Z");
        return builder.toString();
    }

    /** Absolute, allowlisted HTTPS GetMap URL for one frame. */
    public URL getMapUrl(long timeMillis) {
        if (host == null) {
            return null;
        }
        String path = "/geoserver/dwd/wms?service=WMS&version=1.1.0&request=GetMap"
                + "&layers=dwd%3A" + RadarCapabilities.LAYER_NAME
                + "&" + RadarMN2.BBOX + "&" + timeParameter(timeMillis)
                + "&width=" + width + "&height=" + height
                + "&srs=EPSG%3A3857&styles=&format=image%2Fpng";
        String url = APIReaders.HttpsUrl.build(host, path);
        if (url == null) {
            return null;
        }
        try {
            return new URL(url);
        } catch (java.net.MalformedURLException e) {
            return null;
        }
    }
}

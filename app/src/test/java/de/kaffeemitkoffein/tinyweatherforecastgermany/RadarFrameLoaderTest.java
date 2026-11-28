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

import android.content.Context;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * TASK-90: bounded, honest lazy loading. Offline frames are reported as unavailable, the
 * negative cache prevents retry storms, and an anchor change drops only forecast frames.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RadarFrameLoaderTest {

    private static final long START = 1795672800000L; // 2026-11-26T06:00:00Z
    private static final long STEP = 5L * 60L * 1000L;
    private static final long ANCHOR = START + 48L * STEP;

    private RadarFrameLoader loader;

    @After
    public void tearDown() {
        if (loader != null) {
            loader.shutdown();
            loader = null;
        }
        RadarMN2.clearRadarCache(RuntimeEnvironment.getApplication());
    }

    private RadarTimeline timeline(int frames) {
        RadarCapabilities.Window window = new RadarCapabilities.Window(
                START, START + (frames - 1L) * STEP, STEP, ANCHOR, 0L, true);
        return RadarTimeline.create(window, WeatherSettings.Radar.WINDOW_FULL);
    }

    @Test
    public void networkPolicyTreatsMeteredLinksAsOptIn() {
        assertTrue(RadarFrameLoader.networkAllowed(true, false, false));
        assertFalse(RadarFrameLoader.networkAllowed(false, false, true));
        assertFalse(RadarFrameLoader.networkAllowed(true, true, false));
        assertTrue(RadarFrameLoader.networkAllowed(true, true, true));
    }

    @Test
    public void prefetchProfileBoundsTheWorkerCount() {
        assertEquals(2, RadarFrameLoader.prefetchThreadCount(WeatherSettings.Radar.PREFETCH_MINIMAL));
        assertEquals(3, RadarFrameLoader.prefetchThreadCount(WeatherSettings.Radar.PREFETCH_BALANCED));
        assertEquals(4, RadarFrameLoader.prefetchThreadCount(WeatherSettings.Radar.PREFETCH_FULL));
        assertEquals("wider cancellable prefetch window",8,RadarFrameLoader.PREFETCH_FRAMES);
        assertTrue("bitmap LRU must stay bounded",
                RadarFrameLoader.FRAME_BITMAP_LRU_SIZE>=8 && RadarFrameLoader.FRAME_BITMAP_LRU_SIZE<=12);
    }

    @Test
    public void offlineFrameIsReportedUnavailableAndNegativelyCached() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        loader = new RadarFrameLoader(context, WeatherSettings.Radar.PREFETCH_BALANCED);
        loader.setNetworkAllowedForTests(false);
        final long time = START + 60L * STEP; // outside any cached set

        final CountDownLatch first = new CountDownLatch(1);
        final boolean[] available = new boolean[]{true};
        loader.load(time, new RadarFrameLoader.FrameCallback() {
            @Override
            public void onFrame(long t, boolean isAvailable, boolean cancelled) {
                available[0] = isAvailable;
                first.countDown();
            }
        });
        assertTrue("offline callback", first.await(5, TimeUnit.SECONDS));
        assertFalse("offline frame must be honestly unavailable", available[0]);

        final CountDownLatch second = new CountDownLatch(1);
        loader.load(time, new RadarFrameLoader.FrameCallback() {
            @Override
            public void onFrame(long t, boolean isAvailable, boolean cancelled) {
                second.countDown();
            }
        });
        assertTrue("negative-cache callback", second.await(5, TimeUnit.SECONDS));
        RadarFrameLoader.Counters counters = loader.counters();
        assertEquals(2, counters.requests);
        assertEquals(1, counters.negativeHits);
        assertEquals(1, counters.errors);
    }

    @Test
    public void shutdownRejectsFurtherWork() {
        Context context = RuntimeEnvironment.getApplication();
        loader = new RadarFrameLoader(context, WeatherSettings.Radar.PREFETCH_MINIMAL);
        loader.shutdown();
        assertTrue(loader.isShutdown());
        final boolean[] called = new boolean[]{false};
        loader.load(START, new RadarFrameLoader.FrameCallback() {
            @Override
            public void onFrame(long t, boolean available, boolean cancelled) {
                called[0] = true;
            }
        });
        assertTrue("shutdown must answer immediately", called[0]);
    }

    @Test
    public void anchorChangeInvalidatesOnlyForecastFrames() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        loader = new RadarFrameLoader(context, WeatherSettings.Radar.PREFETCH_BALANCED);
        RadarTimeline timeline = timeline(64);
        long analysisTime = timeline.timeForIndex(4);
        long forecastTime = timeline.timeForIndex(52);
        assertTrue(timeline.isForecast(forecastTime));
        assertFalse(timeline.isForecast(analysisTime));
        File analysis = RadarFrameCache.file(context, RadarFrameCache.LAYER_RAIN_RADAR,
                RadarFrameCache.epochSeconds(analysisTime),
                RadarMN2.getFixedRadarMapWidth(context), RadarMN2.getFixedRadarMapHeight(context));
        File forecast = RadarFrameCache.file(context, RadarFrameCache.LAYER_RAIN_RADAR,
                RadarFrameCache.epochSeconds(forecastTime),
                RadarMN2.getFixedRadarMapWidth(context), RadarMN2.getFixedRadarMapHeight(context));
        byte[] valid = validPng(RadarMN2.getFixedRadarMapWidth(context),
                RadarMN2.getFixedRadarMapHeight(context));
        java.nio.file.Files.write(analysis.toPath(), valid);
        java.nio.file.Files.write(forecast.toPath(), valid);
        assertTrue(analysis.isFile());
        assertTrue(forecast.isFile());

        assertEquals(1, loader.refreshAnchor(ANCHOR + STEP, timeline));
        assertTrue("analysis frames are immutable", analysis.isFile());
        assertFalse("stale forecast frame must be re-fetched", forecast.isFile());
    }

    @Test
    public void getMapUrlIsHttpsAllowlistedAndUsesAbsoluteTime() {
        Context context = RuntimeEnvironment.getApplication();
        loader = new RadarFrameLoader(context, WeatherSettings.Radar.PREFETCH_BALANCED);
        assertNotNull(loader.getMapUrl(START));
        String url = loader.getMapUrl(START).toString();
        assertTrue(url.startsWith("https://maps.dwd.de/"));
        assertTrue(url.contains("time=2026-11-26T06:00:00.000Z"));
        assertTrue(url.contains(RadarCapabilities.LAYER_NAME));
        assertTrue(url.contains("service=WMS"));
    }

    private static byte[] validPng(int width, int height) {
        byte[] data = new byte[5001];
        Arrays.fill(data, (byte) 0x42);
        byte[] signature = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        System.arraycopy(signature, 0, data, 0, signature.length);
        data[12] = 'I';
        data[13] = 'H';
        data[14] = 'D';
        data[15] = 'R';
        writeInt(data, 16, width);
        writeInt(data, 20, height);
        return data;
    }

    private static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 24) & 0xff);
        data[offset + 1] = (byte) ((value >> 16) & 0xff);
        data[offset + 2] = (byte) ((value >> 8) & 0xff);
        data[offset + 3] = (byte) (value & 0xff);
    }
}

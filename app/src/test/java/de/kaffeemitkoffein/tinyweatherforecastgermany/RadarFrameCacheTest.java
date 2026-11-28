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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * TASK-88: the radar frame cache is a storage foundation. These tests exercise the key
 * format, the validated atomic write, the LRU/byte caps, the one-time legacy purge and the
 * cache-side downloader fixes without any network access.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RadarFrameCacheTest {

    private static final int WIDTH = 1108;
    private static final int HEIGHT = 1360;

    private static byte[] validPng(int width, int height){
        byte[] data = new byte[RadarFrameCache.MIN_FRAME_BYTES + 1];
        byte[] signature = new byte[]{(byte) 0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a};
        System.arraycopy(signature,0,data,0,signature.length);
        data[12]='I'; data[13]='H'; data[14]='D'; data[15]='R';
        writeInt(data,16,width);
        writeInt(data,20,height);
        return data;
    }

    private static void writeInt(byte[] data, int offset, int value){
        data[offset]   = (byte) ((value>>24) & 0xff);
        data[offset+1] = (byte) ((value>>16) & 0xff);
        data[offset+2] = (byte) ((value>>8) & 0xff);
        data[offset+3] = (byte) (value & 0xff);
    }

    private static InputStream brokenStream(){
        return new InputStream() {
            private int read = 0;
            @Override
            public int read() throws IOException {
                if (read++ > 128){
                    throw new IOException("simulated truncated download");
                }
                return 0x42;
            }
        };
    }

    @Test
    public void keyCarriesLayerEpochSecondAndRequestedDimensions(){
        assertEquals("radarwn-1795672800-1108x1360.png",
                RadarFrameCache.key("radarwn",1795672800L,1108,1360));
        assertFalse(RadarFrameCache.key("radarwn",1795672800L,1108,1360).equals(
                RadarFrameCache.key("radarwn",1795672800L,2216,2720)));
        assertEquals(1795672800L,RadarFrameCache.epochSeconds(1795672800123L));
        assertEquals(1,RadarFrameCache.scaleForWidth(1108));
        assertEquals(2,RadarFrameCache.scaleForWidth(2216));
        assertEquals(RadarFrameCache.MAX_BYTES_SCALE_2,RadarFrameCache.maxBytesForScale(2));
        assertArrayEqualsInt(new int[]{1108,1360},
                RadarFrameCache.dimensionsFromKey("radarwn-1795672800-1108x1360.png"));
        assertEquals(null,RadarFrameCache.dimensionsFromKey("radarMN-0.png"));
    }

    @Test
    public void headerValidationRejectsBadSignatureXmlAndWrongDimensions(){
        byte[] valid = validPng(WIDTH,HEIGHT);
        assertTrue(RadarFrameCache.hasValidPngHeader(valid,WIDTH,HEIGHT));
        byte[] badSignature = valid.clone();
        badSignature[1] = 0x00;
        assertFalse(RadarFrameCache.hasValidPngHeader(badSignature,WIDTH,HEIGHT));
        assertFalse(RadarFrameCache.hasValidPngHeader(valid,800,600));
        assertFalse(RadarFrameCache.hasValidPngHeader(new byte[]{1,2,3},WIDTH,HEIGHT));
        byte[] xml = ("<?xml version=\"1.0\"?><ServiceExceptionReport>no image</ServiceExceptionReport>")
                .getBytes(StandardCharsets.UTF_8);
        assertTrue(RadarFrameCache.looksLikeXmlError(xml));
        assertFalse(RadarFrameCache.hasValidPngHeader(xml,WIDTH,HEIGHT));
    }

    @Test
    public void atomicWritePublishesValidPngAndDeletesTemporaryFile() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File target = RadarFrameCache.file(context,RadarFrameCache.LAYER_RAIN_RADAR,1234,WIDTH,HEIGHT);
        File temporary = new File(target.getParentFile(),target.getName()+RadarFrameCache.TEMP_SUFFIX);
        try {
            assertTrue(RadarFrameCache.writeAtomic(target,WIDTH,HEIGHT,
                    new ByteArrayInputStream(validPng(WIDTH,HEIGHT))));
            assertTrue(target.isFile());
            assertFalse(temporary.exists());
            assertTrue(RadarFrameCache.isValidPng(target,WIDTH,HEIGHT));
        } finally {
            if (target.isFile()){
                target.delete();
            }
        }
    }

    @Test
    public void truncatedStreamNeverBecomesAFinalFile() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File target = RadarFrameCache.file(context,RadarFrameCache.LAYER_RAIN_RADAR,1235,WIDTH,HEIGHT);
        File temporary = new File(target.getParentFile(),target.getName()+RadarFrameCache.TEMP_SUFFIX);
        try {
            assertFalse(RadarFrameCache.writeAtomic(target,WIDTH,HEIGHT,
                    new ByteArrayInputStream(new byte[]{0x42,0x42,0x42})));
            assertFalse(target.exists());
            assertFalse(temporary.exists());
            assertFalse(RadarFrameCache.writeAtomic(target,WIDTH,HEIGHT,brokenStream()));
            assertFalse(target.exists());
            assertFalse(temporary.exists());
        } finally {
            if (target.isFile()){
                target.delete();
            }
        }
    }

    @Test
    public void wrongDimensionsAndXmlErrorsAreRejectedBeforeRename() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File wrongSize = RadarFrameCache.file(context,RadarFrameCache.LAYER_RAIN_RADAR,1236,WIDTH,HEIGHT);
        File xmlFile = RadarFrameCache.file(context,RadarFrameCache.LAYER_RAIN_RADAR,1237,WIDTH,HEIGHT);
        try {
            assertFalse(RadarFrameCache.writeAtomic(wrongSize,WIDTH,HEIGHT,
                    new ByteArrayInputStream(validPng(800,600))));
            assertFalse(wrongSize.exists());
            assertFalse(new File(wrongSize.getParentFile(),
                    wrongSize.getName()+RadarFrameCache.TEMP_SUFFIX).exists());

            byte[] xml = ("<?xml version=\"1.0\"?><ServiceExceptionReport>error</ServiceExceptionReport>")
                    .getBytes(StandardCharsets.UTF_8);
            assertFalse(RadarFrameCache.writeAtomic(xmlFile,WIDTH,HEIGHT,new ByteArrayInputStream(xml)));
            assertFalse(xmlFile.exists());
            assertFalse(new File(xmlFile.getParentFile(),
                    xmlFile.getName()+RadarFrameCache.TEMP_SUFFIX).exists());
        } finally {
            if (wrongSize.isFile()){
                wrongSize.delete();
            }
            if (xmlFile.isFile()){
                xmlFile.delete();
            }
        }
    }

    @Test
    public void renameFailureRemovesTemporaryAndLeavesNoFinalFile() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File target = RadarFrameCache.file(context,RadarFrameCache.LAYER_RAIN_RADAR,1238,WIDTH,HEIGHT);
        File temporary = new File(target.getParentFile(),target.getName()+RadarFrameCache.TEMP_SUFFIX);
        // A directory at the target path makes File.renameTo fail deterministically.
        assertTrue(target.mkdirs());
        try {
            assertFalse(RadarFrameCache.writeAtomic(target,WIDTH,HEIGHT,
                    new ByteArrayInputStream(validPng(WIDTH,HEIGHT))));
            assertTrue(target.isDirectory());
            assertFalse("no PNG may be published under a failed target",target.isFile());
            assertFalse("the temporary file must not survive a failed rename",temporary.exists());
        } finally {
            target.delete();
            if (temporary.exists()){
                temporary.delete();
            }
        }
    }

    @Test
    public void evictionOrderIsLeastRecentlyUsedAndPinsAreNeverEvicted(){
        LinkedHashMap<String,RadarFrameCache.Entry> entries = new LinkedHashMap<String,RadarFrameCache.Entry>(8,0.75f,true);
        add(entries,"a",4L*1024*1024,WIDTH);
        add(entries,"b",4L*1024*1024,WIDTH);
        add(entries,"c",4L*1024*1024,WIDTH);
        add(entries,"d",4L*1024*1024,WIDTH);
        Set<String> pinned = new HashSet<String>();
        pinned.add("b");
        List<RadarFrameCache.Entry> evicted = RadarFrameCache.evictToFit(entries,pinned,3,
                RadarFrameCache.MAX_BYTES_SCALE_1,RadarFrameCache.MAX_BYTES_SCALE_2);
        assertEquals(Arrays.asList("a"),keys(evicted));
        assertEquals(Arrays.asList("b","c","d"),new java.util.ArrayList<String>(entries.keySet()));
        assertEquals(288,RadarFrameCache.MAX_ENTRIES);

        // Touching c makes d the next least recently used unpinned frame.
        RadarFrameCache.Entry c = entries.get("c");
        entries.remove("c");
        entries.put("c",c);
        evicted = RadarFrameCache.evictToFit(entries,pinned,2,
                RadarFrameCache.MAX_BYTES_SCALE_1,RadarFrameCache.MAX_BYTES_SCALE_2);
        assertEquals(Arrays.asList("d"),keys(evicted));
        assertTrue(entries.containsKey("b"));
        assertTrue(entries.containsKey("c"));
    }

    @Test
    public void byteCapsAreEnforcedPerScaleAndAnchorsSurvive(){
        LinkedHashMap<String,RadarFrameCache.Entry> entries = new LinkedHashMap<String,RadarFrameCache.Entry>(8,0.75f,true);
        add(entries,"old-1x",20L*1024*1024,WIDTH);
        add(entries,"anchor-1x",20L*1024*1024,WIDTH);
        add(entries,"old-2x",60L*1024*1024,2216);
        add(entries,"anchor-2x",4L*1024*1024,2216);
        add(entries,"extra-2x",8L*1024*1024,2216);
        Set<String> pinned = new HashSet<String>();
        pinned.add("anchor-1x");
        pinned.add("anchor-2x");
        List<RadarFrameCache.Entry> evicted = RadarFrameCache.evictToFit(entries,pinned,288,
                RadarFrameCache.MAX_BYTES_SCALE_1,RadarFrameCache.MAX_BYTES_SCALE_2);
        assertEquals(Arrays.asList("old-1x","old-2x"),keys(evicted));
        assertEquals(3,entries.size());
        assertTrue(entries.keySet().containsAll(pinned));
    }

    @Test
    public void legacyFramesArePurgedOnlyOnce() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File legacy = new File(context.getCacheDir(),
                APIReaders.RadarMNSetGeoserverRunnable.RADAR_CACHE_FILENAME_PREFIX+"0"+
                        APIReaders.RadarMNSetGeoserverRunnable.RADAR_CACHE_FILENAME_SUFFIX);
        try {
            Files.write(legacy.toPath(),validPng(WIDTH,HEIGHT));
            assertEquals(1,RadarFrameCache.migrateIfNeeded(context));
            assertFalse(legacy.exists());
            Files.write(legacy.toPath(),validPng(WIDTH,HEIGHT));
            assertEquals("migration must run only once",0,RadarFrameCache.migrateIfNeeded(context));
            assertTrue("the marker must stop further purges",legacy.exists());
        } finally {
            if (legacy.isFile()){
                legacy.delete();
            }
        }
    }

    @Test
    public void clearRadarCacheAlsoEmptiesTheKeyedDirectory() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File keyed = RadarFrameCache.file(context,RadarFrameCache.LAYER_RAIN_RADAR,4321,WIDTH,HEIGHT);
        assertTrue(RadarFrameCache.writeAtomic(keyed,WIDTH,HEIGHT,
                new ByteArrayInputStream(validPng(WIDTH,HEIGHT))));
        assertTrue(keyed.isFile());
        assertTrue(RadarMN2.clearRadarCache(context)>=1);
        assertFalse(keyed.exists());
        assertEquals(0,RadarFrameCache.indexSnapshot().size());
    }

    @Test
    public void fullDataSetRequiresEveryFrameNotJustAMajority() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        int width = RadarMN2.getFixedRadarMapWidth(context);
        int height = RadarMN2.getFixedRadarMapHeight(context);
        try {
            RadarMN2.clearRadarCache(context);
            assertFalse(APIReaders.RadarMNSetGeoserverRunnable.fullRadarDataSet(context));
            for (int i=0; i<APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE-1; i++){
                File frame = APIReaders.RadarMNSetGeoserverRunnable.getRadarMNFile(context,i);
                Files.write(frame.toPath(),validPng(width,height));
            }
            assertFalse("23 of 24 frames are not a complete set",
                    APIReaders.RadarMNSetGeoserverRunnable.fullRadarDataSet(context));
            File last = APIReaders.RadarMNSetGeoserverRunnable.getRadarMNFile(context,
                    APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE-1);
            Files.write(last.toPath(),validPng(width,height));
            assertTrue(APIReaders.RadarMNSetGeoserverRunnable.fullRadarDataSet(context));
            // A single corrupt frame invalidates the whole set again.
            Files.write(last.toPath(),new byte[]{0x3c,0x3f,0x78,0x6d,0x6c});
            assertFalse(APIReaders.RadarMNSetGeoserverRunnable.fullRadarDataSet(context));
        } finally {
            RadarMN2.clearRadarCache(context);
        }
    }

    @Test
    public void downloaderKeepsFallbackAndDoesNotResetForceUpdate() throws Exception {
        String source = read("app/src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/APIReaders.java");
        String run = between(source,
                "public void run() {\n            onStart();\n            RadarFrameCache.initialize(context);",
                "public static class getLayerImages");
        assertFalse("an explicit forceUpdate must survive until run() decides",
                run.contains("forceUpdate = false"));
        assertTrue(run.contains("forceUpdate"));

        String fetch = between(source,"private void multiFetchRadarSet(){","public void onStart(){");
        assertFalse("old frames must stay as fallback while fetching",
                fetch.contains("deleteCacheFiles(context)"));
        assertTrue(fetch.contains("finished.set(false)"));
        assertTrue(source.contains("private final AtomicInteger errors = new AtomicInteger(0)"));

        String done = between(source,"private void checkThreadsDone(){","public void incrementProgress(){");
        assertTrue("the executor is shut down on normal completion",done.contains("current.shutdown()"));
        assertTrue("success requires zero errors",done.contains("errors.get()>0"));
        assertTrue("a cancelled fetch must keep the old set",done.contains("cancellation.isCancelled()"));
        assertFalse("the old tolerate-three-errors rule is gone",done.contains("errors>3"));

        APIReaders.RadarMNSetGeoserverRunnable runnable =
                new APIReaders.RadarMNSetGeoserverRunnable(RuntimeEnvironment.getApplication());
        runnable.setForceUpdate(true);
        java.lang.reflect.Field field = APIReaders.RadarMNSetGeoserverRunnable.class
                .getDeclaredField("forceUpdate");
        field.setAccessible(true);
        assertTrue(field.getBoolean(runnable));
    }

    private static void add(LinkedHashMap<String,RadarFrameCache.Entry> entries,
                            String key, long bytes, int width){
        entries.put(key,new RadarFrameCache.Entry(key,new File(key),width,HEIGHT,bytes));
    }

    private static List<String> keys(List<RadarFrameCache.Entry> entries){
        java.util.List<String> result = new java.util.ArrayList<String>();
        for (RadarFrameCache.Entry entry : entries){
            result.add(entry.key);
        }
        return result;
    }

    private static void assertArrayEqualsInt(int[] expected, int[] actual){
        assertNotNull(actual);
        assertEquals(expected.length,actual.length);
        for (int i=0; i<expected.length; i++){
            assertEquals(expected[i],actual[i]);
        }
    }

    private static String between(String source, String start, String end){
        int from = source.indexOf(start);
        int to = source.indexOf(end,from);
        assertTrue("missing block "+start+" .. "+end,(from>=0) && (to>from));
        return source.substring(from,to);
    }

    private static String read(String path) throws Exception {
        File file = new File(path);
        if (!file.isFile()){
            file = new File(path.substring("app/".length()));
        }
        if (!file.isFile() && !path.startsWith("app/")){
            file = new File("../"+path);
        }
        assertTrue("missing source file: "+path,file.isFile());
        return new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
    }
}

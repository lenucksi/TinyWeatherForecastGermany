/**
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2026 Pawel Dube
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TASK-35: pure-JVM coverage for the asynchronous log queue. The sink is injected, so a
 * deliberately blocked or failing sink can prove that enqueue() never waits for file I/O
 * without needing a device or Robolectric.
 */
public class PrivateLogAsyncWriterTest {

    private static final class RecordingSink implements PrivateLog.LineSink {
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch release;
        boolean fail;

        @Override
        public void writeBatch(List<String> lines) throws IOException {
            writeStarted.countDown();
            if (release != null) {
                try {
                    if (!release.await(3, TimeUnit.SECONDS)) {
                        throw new IOException("test sink was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            if (fail) {
                throw new IOException("disk full");
            }
            this.lines.addAll(lines);
        }

        @Override
        public void flush() throws IOException {
        }

        @Override
        public void close() throws IOException {
        }
    }

    private static PrivateLog.AsyncLogWriter newWriter(PrivateLog.LineSink sink,
                                                        List<String> logcat, int capacity) {
        return new PrivateLog.AsyncLogWriter(sink, new PrivateLog.LogcatWriter() {
            @Override
            public void log(String message) {
                logcat.add(message);
            }
        }, new PrivateLog.Clock() {
            @Override
            public long currentTimeMillis() {
                return 1_700_000_000_000L;
            }
        }, capacity);
    }

    @Test
    public void enqueueDoesNotWaitForABlockedSink() throws Exception {
        RecordingSink sink = new RecordingSink();
        sink.release = new CountDownLatch(1);
        List<String> logcat = new ArrayList<String>();
        PrivateLog.AsyncLogWriter writer = newWriter(sink, logcat, 512);
        try {
            assertTrue(writer.enqueue("first", null, false));
            assertTrue("worker never reached the sink", sink.writeStarted.await(2, TimeUnit.SECONDS));
            long start = System.nanoTime();
            for (int i = 0; i < 200; i++) {
                assertTrue(writer.enqueue("message " + i, null, false));
            }
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue("enqueue waited behind file I/O for " + elapsedMillis + " ms",
                    elapsedMillis < 1000);
            assertEquals(0, writer.failureCount());
        } finally {
            sink.release.countDown();
            writer.flush(3000);
            writer.close();
        }
        assertEquals(201, sink.lines.size());
    }

    @Test
    public void redactionHappensBeforeFileAndLogcatOutput() {
        RecordingSink sink = new RecordingSink();
        List<String> logcat = new ArrayList<String>();
        PrivateLog.AsyncLogWriter writer = newWriter(sink, logcat, 16);
        try {
            assertTrue(writer.enqueue("Fetch geo:53.55,9.98 from https://opendata.dwd.de/data",
                    "opendata.dwd.de", true));
            assertTrue(writer.enqueue("Contact weather@example.org", null, true));
            writer.flush(3000);
        } finally {
            writer.close();
        }
        String fileContent = String.join("\n", sink.lines);
        assertTrue(fileContent, fileContent.contains(PrivateLog.Redaction.MASK));
        assertFalse(fileContent, fileContent.contains("geo:"));
        assertFalse(fileContent, fileContent.contains("53.55"));
        assertFalse(fileContent, fileContent.contains("opendata.dwd.de"));
        assertFalse(fileContent, fileContent.contains("weather@example.org"));
        assertEquals(2, logcat.size());
        for (String message : logcat) {
            assertTrue(message, message.contains(PrivateLog.Redaction.MASK));
            assertFalse(message, message.contains("53.55"));
            assertFalse(message, message.contains("opendata.dwd.de"));
        }
    }

    @Test
    public void fullQueueDropsEntriesInsteadOfBlockingTheCaller() throws Exception {
        RecordingSink sink = new RecordingSink();
        sink.release = new CountDownLatch(1);
        PrivateLog.AsyncLogWriter writer = newWriter(sink, new ArrayList<String>(), 8);
        try {
            assertTrue(writer.enqueue("first", null, false));
            assertTrue(sink.writeStarted.await(2, TimeUnit.SECONDS));
            for (int i = 0; i < 18; i++) {
                writer.enqueue("overflow " + i, null, false);
            }
            // Worker holds the first entry; eight slots remain before the queue is full.
            assertEquals(10, writer.droppedCount());
        } finally {
            sink.release.countDown();
            writer.flush(3000);
            writer.close();
        }
        assertEquals(9, sink.lines.size());
    }

    @Test
    public void sinkFailuresAreRecordedWithTheirOriginalThrowable() {
        RecordingSink sink = new RecordingSink();
        sink.fail = true;
        PrivateLog.AsyncLogWriter writer = newWriter(sink, new ArrayList<String>(), 8);
        try {
            assertTrue(writer.enqueue("will fail", null, false));
            writer.flush(3000);
        } finally {
            writer.close();
        }
        assertTrue(writer.failureCount() >= 1);
        assertTrue(String.valueOf(writer.lastFailure()),
                writer.lastFailure() instanceof IOException);
        assertEquals("disk full", writer.lastFailure().getMessage());
    }

    @Test
    public void crashReportContainsStackTraceAndIsRedacted() {
        Throwable failure = new IllegalStateException("lookup geo:53.55,9.98 at opendata.dwd.de");
        String report = PrivateLog.CrashReport.format(new Thread("crash-test"), failure,
                "opendata.dwd.de");
        assertTrue(report, report.contains("FATAL uncaught exception in thread crash-test"));
        assertTrue(report, report.contains("java.lang.IllegalStateException"));
        assertTrue(report, report.contains("crashReportContainsStackTraceAndIsRedacted"));
        assertTrue(report, report.contains(PrivateLog.Redaction.MASK));
        assertFalse(report, report.contains("geo:53.55"));
        assertFalse(report, report.contains("opendata.dwd.de"));
    }

    @Test
    public void stackTraceHelperKeepsCauses() {
        Throwable cause = new IllegalArgumentException("cause marker");
        Throwable failure = new IllegalStateException("outer marker", cause);
        String trace = PrivateLog.CrashReport.stackTrace(failure);
        assertTrue(trace, trace.contains("outer marker"));
        assertTrue(trace, trace.contains("cause marker"));
        assertTrue(trace, trace.contains("Caused by: java.lang.IllegalArgumentException"));
    }
}

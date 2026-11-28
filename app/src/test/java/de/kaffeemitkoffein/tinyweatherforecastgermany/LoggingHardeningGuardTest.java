/**
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2026 Pawel Dube
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * TASK-35: static guards for properties that a behavioural unit test cannot see:
 * the hot log() path must not touch files, TASK-42's privacy gates must survive and
 * broad catches must not be reintroduced silently.
 */
public class LoggingHardeningGuardTest {

    private static final String PKG = "src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/";

    private static String read(String relativePath) throws IOException {
        // Gradle runs unit tests with the module directory as working directory;
        String[] candidates = {relativePath, "app/" + relativePath,
                "../" + relativePath, "../app/" + relativePath};
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        fail("source file not found: " + relativePath);
        return null;
    }

    private static String hotLogPath(String source) {
        int start = source.indexOf("public static boolean log(Context context, String s){");
        int end = source.indexOf("public static boolean log(Context context,String tag", start);
        assertTrue("log(Context,String) not found", start >= 0 && end > start);
        return source.substring(start, end);
    }

    @Test
    public void hotLogPathOnlyEnqueues() throws IOException {
        String source = read(PKG + "PrivateLog.java");
        String hotPath = hotLogPath(source);
        assertFalse(hotPath, hotPath.contains("FileOutputStream"));
        assertFalse(hotPath, hotPath.contains("FileInputStream"));
        assertFalse(hotPath, hotPath.contains("getFilesDir"));
        assertFalse(hotPath, hotPath.contains("createNewFile"));
        assertTrue("log() must hand off to the bounded queue", hotPath.contains("WRITER.enqueue("));
        assertTrue("the queue must be bounded", source.contains("ArrayBlockingQueue"));
        assertTrue("offer() must be used instead of a blocking put()",
                source.contains("queue.offer(") && !source.contains("queue.put("));
        assertTrue("file I/O must run on a dedicated worker", source.contains("new Thread(this::drain"));
    }

    @Test
    public void task42RedactionAndLogcatGateArePreserved() throws IOException {
        String source = read(PKG + "PrivateLog.java");
        assertTrue("central redaction must run in the worker before writing",
                source.contains("String safeMessage = Redaction.redact(s,"));
        assertTrue("logcat must stay restricted to debug/userdebug builds",
                source.contains("Redaction.isLogcatAllowed(BuildConfig.DEBUG,BuildConfig.VERSION_NAME)"));
    }

    @Test
    public void noBroadSilentCatchesRemainInPrivateLog() throws IOException {
        String source = read(PKG + "PrivateLog.java");
        assertFalse(source, source.contains("catch (Exception"));
        assertFalse(source, source.contains("catch(Exception"));
        assertTrue("failures must be recorded with their throwable",
                source.contains("WRITER.recordFailure(e)"));
    }

    @Test
    public void localCrashPathExists() throws IOException {
        String source = read(PKG + "PrivateLog.java");
        assertTrue(source.contains("Thread.setDefaultUncaughtExceptionHandler"));
        assertTrue(source.contains("printStackTrace"));
        assertTrue(source.contains("writeCrashReport"));
        assertTrue("opt-in exception logging API must remain available",
                source.contains("public static boolean logException("));
    }

}

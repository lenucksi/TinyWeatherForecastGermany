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

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Source guard for CodeQL java/android/implicit-pendingintents (alert #7). Robolectric proves
 * runtime behaviour; these assertions lock in the structural property the CodeQL query checks:
 * no implicit ACTION_SEND Intent may reach PendingIntent.getActivity.
 */
public class WarningPendingIntentGuardTest {

    private static final String SOURCE =
            "src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WeatherSyncAdapter.java";

    private static String readSource() throws IOException {
        String[] candidates = {SOURCE, "app/" + SOURCE};
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        fail("source file not found: " + SOURCE);
        return null;
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("missing method: " + signature, start >= 0);
        int open = source.indexOf('{', start);
        assertTrue("missing method body: " + signature, open >= 0);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        fail("unterminated method body: " + signature);
        return null;
    }

    @Test
    public void pendingIntentUsesExplicitWarningScreenIntent() throws IOException {
        String source = readSource();
        String body = methodBody(source, "public static PendingIntent getWarningPendingIntent(");
        assertTrue("PendingIntent must not wrap the implicit share intent",
                !body.contains("getWarningIntent("));
        assertTrue("PendingIntent must use the explicit content intent",
                body.contains("getWarningContentIntent("));
        assertTrue("explicit destination must be re-asserted at the PendingIntent sink",
                body.contains("intent.setPackage(context.getPackageName())"));
        assertTrue("FLAG_IMMUTABLE must be retained on API 23+",
                body.contains("Build.VERSION.SDK_INT >= 23")
                        && body.contains("flags |= PendingIntent.FLAG_IMMUTABLE"));
    }

    @Test
    public void contentIntentTargetsThisAppAndCarriesIdentifier() throws IOException {
        String source = readSource();
        String body = methodBody(source, "public static Intent getWarningContentIntent(");
        assertTrue("content intent must name the warning activity explicitly",
                body.contains("new Intent(context, WeatherWarningActivity.class)"));
        assertTrue("content intent must be restricted to this app's package",
                body.contains("intent.setPackage(context.getPackageName())"));
        assertTrue("content intent must carry the warning identifier",
                body.contains("intent.putExtra(EXTRA_WARNING_IDENTIFIER, weatherWarning.identifier)"));
    }

    @Test
    public void actionSendIsConfinedToTheDirectUserShareIntent() throws IOException {
        String source = readSource();
        int occurrences = 0;
        int index = source.indexOf("Intent.ACTION_SEND");
        while (index >= 0) {
            occurrences++;
            index = source.indexOf("Intent.ACTION_SEND", index + 1);
        }
        assertEqualsShareActionOccurrenceCount(1, occurrences);
        String notification = methodBody(source, "public static Notification getWarningNotification(");
        assertTrue("notification must not attach a share PendingIntent",
                !notification.contains("shareWarningPendingIntent")
                        && !notification.contains("Notification.Action"));
        assertTrue("notification tap must use the audited explicit PendingIntent",
                notification.contains("setContentIntent(pendingIntent)"));
    }

    private static void assertEqualsShareActionOccurrenceCount(int expected, int actual) {
        if (expected != actual) {
            fail("ACTION_SEND must occur only in the user share intent, found " + actual
                    + " occurrences");
        }
    }
}

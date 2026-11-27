/**
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2026 Pawel Dube
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Static guard for the TASK-42 acceptance criteria that a behavioural test cannot see. */
public class LogPrivacyGuardTest {

    private static final String PKG = "src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/";

    private static String read(String relativePath) throws IOException {
        String[] candidates = {relativePath, "app/" + relativePath};
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        fail("source file not found: " + relativePath);
        return null;
    }

    private static void assertAbsent(String file, String source, String forbidden) {
        if (source.contains(forbidden)) {
            fail(file + " must not contain " + forbidden + " any more (TASK-42)");
        }
    }

    @Test
    public void abandonedFeedbackAddressIsGoneFromOwnedFiles() throws IOException {
        assertAbsent("PrivateLog.java", read(PKG + "PrivateLog.java"),
                "weather@kaffeemitkoffein.de");
        assertAbsent("LoggingActivity.java", read(PKG + "LoggingActivity.java"),
                "weather@kaffeemitkoffein.de");
        assertAbsent("preferences.xml",
                read("src/main/res/xml/preferences.xml"), "weather@kaffeemitkoffein.de");
    }

    @Test
    public void logSharingDoesNotUseFileUris() throws IOException {
        String activity = read(PKG + "LoggingActivity.java");
        assertAbsent("LoggingActivity.java", activity, "getLogUri");
        assertAbsent("LoggingActivity.java", activity, "setDataAndType");
        assertAbsent("LoggingActivity.java", activity, "EXTRA_EMAIL");
        assertTrue("EXTRA_TEXT sharing must remain", activity.contains("Intent.EXTRA_TEXT"));
    }

    @Test
    public void logcatWriteIsGatedByBuildType() throws IOException {
        String privateLog = read(PKG + "PrivateLog.java");
        assertTrue("Log.v must be gated by Redaction.isLogcatAllowed",
                privateLog.contains("Redaction.isLogcatAllowed(BuildConfig.DEBUG,BuildConfig.VERSION_NAME)"));
        assertTrue("central redaction must run in the log sink",
                privateLog.contains("String safeMessage = Redaction.redact(s,"));
    }
}

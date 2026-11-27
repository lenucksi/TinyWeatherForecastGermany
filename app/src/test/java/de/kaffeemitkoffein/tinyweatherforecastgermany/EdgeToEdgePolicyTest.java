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
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** TASK-15: phone rotation stays user-controlled; 600dp+ screens stay unlocked. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class EdgeToEdgePolicyTest {

    @Test
    public void largeScreensFollowDeviceOrientation() {
        assertTrue("600dp is the tablet/foldable threshold", MainActivity.followDeviceOrientation(600));
        assertTrue(MainActivity.followDeviceOrientation(720));
        assertTrue(MainActivity.followDeviceOrientation(1200));
        assertFalse("phones keep the user rotation preference", MainActivity.followDeviceOrientation(599));
        assertFalse(MainActivity.followDeviceOrientation(360));
    }

    @Test
    public void manifestDoesNotLockOrientation() throws Exception {
        String manifest = read("src/main/AndroidManifest.xml");
        assertFalse("manifest-level locks are ignored on large screens and conflict with the policy",
                manifest.contains("screenOrientation"));
    }

    @Test
    public void mainLayoutRootsDoNotDeclareSystemWindowsFitting() throws Exception {
        // TASK-61: the Holo themes set android:fitsSystemWindows=true app-wide. With the
        // manual OnApplyWindowInsetsListener this double-applied the top inset (root and
        // main_layout both padded 247px), producing an empty band above "Region". The
        // roots must not add a second declaration; MainActivity clears the inherited flag.
        for (String layout : new String[]{
                "src/main/res/layout/activity_main.xml",
                "src/main/res/layout-w600dp-land/activity_main.xml"}) {
            assertFalse(layout + " must not re-declare fitsSystemWindows",
                    read(layout).contains("fitsSystemWindows=\"true\""));
        }
        String activity = read("src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/MainActivity.java");
        assertTrue("inherited fitsSystemWindows must be cleared in code",
                activity.contains("clearFitsSystemWindows(root)"));
    }

    @Test
    public void mainActivityAppliesInsetsAndBarAppearance() throws Exception {
        String source = read("src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/MainActivity.java");
        assertTrue(source.contains("setOnApplyWindowInsetsListener"));
        assertTrue(source.contains("WindowInsets.Type.systemBars()"));
        assertTrue(source.contains("WindowInsets.Type.ime()"));
        // On the Holo themes this API hid the status-bar icons on API 37; insets padding
        // alone keeps content clear without touching SystemUI icon appearance.
        assertFalse(source.contains("setSystemBarsAppearance"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}

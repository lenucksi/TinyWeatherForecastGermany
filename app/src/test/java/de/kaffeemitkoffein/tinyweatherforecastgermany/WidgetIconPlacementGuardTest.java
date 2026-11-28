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

/**
 * TASK-83: the classic and large widget icons must not hug the widget's top edge,
 * and the warning symbol must be only slightly larger than before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class WidgetIconPlacementGuardTest {

    private static final String[] WIDGET_LAYOUTS = {
            "src/main/res/layout/largewidget_layout.xml",
            "src/main/res/layout/classicwidget_layout.xml"
    };

    @Test
    public void widgetsUseDedicatedWarningIconSizeAndLoweredPosition() throws Exception {
        for (String layout : WIDGET_LAYOUTS) {
            String xml = read(layout);
            assertTrue(layout + " warning icon must use the dedicated size dimen",
                    xml.contains("android:layout_width=\"@dimen/widget_warning_icon_size\""));
            assertTrue(layout + " warning icon must use the dedicated size dimen",
                    xml.contains("android:layout_height=\"@dimen/widget_warning_icon_size\""));
            assertTrue(layout + " warning icon must be moved down from the top edge",
                    xml.contains("android:layout_marginTop=\"@dimen/widget_warning_icon_top\""));
            assertFalse(layout + " must not restore the old 2dp top margin",
                    xml.contains("android:layout_marginTop=\"2dp\""));
            assertFalse(layout + " must not reuse the text size for the icon size",
                    xml.contains("android:layout_width=\"@dimen/widget_textsize_verylarge\""));
        }
    }

    @Test
    public void widgetsOffsetTheWeatherConditionIconFromTheTop() throws Exception {
        for (String layout : WIDGET_LAYOUTS) {
            String xml = read(layout);
            assertTrue(layout + " weather icon needs a top offset",
                    xml.contains("android:layout_marginTop=\"@dimen/widget_weathericon_top_offset\""));
        }
    }

    @Test
    public void dimensKeepModestIconSizing() throws Exception {
        String dimens = read("src/main/res/values/dimens.xml");
        assertTrue(dimens.contains("<dimen name=\"widget_warning_icon_size\">20sp</dimen>"));
        assertTrue(dimens.contains("<dimen name=\"widget_warning_icon_top\">14dp</dimen>"));
        assertTrue(dimens.contains("<dimen name=\"widget_weathericon_top_offset\">32dp</dimen>"));
        // 20sp is ~11% larger than the old 18sp; 14dp sits in the requested 12-16dp band.
        assertTrue(dimens.contains("<dimen name=\"widget_textsize_verylarge\">18sp</dimen>"));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}

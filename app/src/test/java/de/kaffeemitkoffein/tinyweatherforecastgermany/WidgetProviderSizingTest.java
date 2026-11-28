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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** TASK-26: launcher sizing metadata and the linked-but-deferred roadmap. */
public class WidgetProviderSizingTest {

    private static final String[] PROVIDERS = {"classicwidget", "boldwidget", "chartwidget", "clockwidget"};

    @Test
    public void resizeRangesAreUsableOnModernLaunchers() throws Exception {
        for (String provider : PROVIDERS) {
            for (String dir : new String[]{"src/main/res/xml", "src/main/res/xml-v31"}) {
                String path = dir + "/" + provider + ".xml";
                String xml = read(path);
                int minWidth = dp(xml, "minWidth");
                int minHeight = dp(xml, "minHeight");
                int resizeWidth = dp(xml, "minResizeWidth");
                int resizeHeight = dp(xml, "minResizeHeight");
                int maxWidth = dp(xml, "maxResizeWidth");
                int maxHeight = dp(xml, "maxResizeHeight");
                assertTrue(path + " must allow horizontal shrink", resizeWidth > 0 && resizeWidth < minWidth);
                assertTrue(path + " must allow vertical resize", resizeHeight > 0 && resizeHeight <= minHeight);
                assertTrue(path + " needs a maximum width", maxWidth >= minWidth);
                assertTrue(path + " needs a maximum height", maxHeight >= minHeight);
                assertTrue(path + " must be resizable in both axes",
                        xml.contains("resizeMode=\"horizontal|vertical\""));
            }
        }
    }

    @Test
    public void cellDefaultsMatchTheLayoutHeight() throws Exception {
        for (String provider : PROVIDERS) {
            String xml = read("src/main/res/xml-v31/" + provider + ".xml");
            assertTrue(provider + " needs a cell-based default width", intAttr(xml, "targetCellWidth") >= 3);
            assertTrue(provider + " needs at least two cells of height or a compact layout",
                    intAttr(xml, "targetCellHeight") >= 2);
        }
    }

    @Test
    public void refreshUsesTheChartProvidersOwnAction() throws Exception {
        String refresher = read("src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/WidgetRefresher.java");
        int chartMethod = refresher.indexOf("void refreshChartWidget");
        int chartBlockEnd = refresher.indexOf("public static void ", chartMethod + 1);
        if (chartBlockEnd < 0) {
            chartBlockEnd = refresher.length();
        }
        String chartBlock = refresher.substring(chartMethod, chartBlockEnd);
        assertTrue("chart refresh must use the chart provider's own action",
                chartBlock.contains("intent.setAction(ChartWidget.WIDGET_CUSTOM_REFRESH_ACTION)"));
        assertFalse("chart refresh must not reuse the clock action",
                chartBlock.contains("ClockWidget.WIDGET_CUSTOM_REFRESH_ACTION"));
    }


    private static int dp(String xml, String attribute) {
        Matcher matcher = Pattern.compile("android:" + attribute + "=\"(\\d+)dp\"").matcher(xml);
        assertTrue("missing " + attribute, matcher.find());
        return Integer.parseInt(matcher.group(1));
    }

    private static int intAttr(String xml, String attribute) {
        Matcher matcher = Pattern.compile("android:" + attribute + "=\"(\\d+)\"").matcher(xml);
        assertTrue("missing " + attribute, matcher.find());
        return Integer.parseInt(matcher.group(1));
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}

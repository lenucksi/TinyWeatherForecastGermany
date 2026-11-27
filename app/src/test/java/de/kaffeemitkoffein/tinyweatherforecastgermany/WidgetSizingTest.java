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

import static de.kaffeemitkoffein.tinyweatherforecastgermany.ClassicWidget.WidgetSizing.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** TASK-26: font-scale and resize arithmetic without a launcher. */
public class WidgetSizingTest {

    @Test
    public void clockTextGrowsWithSystemFontScaleWhileThereIsRoom() {
        float density = 2f;
        int framePx = 400; // e.g. 200dp frame, clock gets half of the widget height
        float baseSp = 60f;
        float normal = textSizeSpForFrame(baseSp, framePx, density, 1f);
        float large = textSizeSpForFrame(baseSp, framePx, density, 1.3f);
        assertEquals(60f, normal, 0.01f);
        assertEquals(60f, large, 0.01f);
        // The system multiplies sp by density * fontScale: the rendered text
        // must grow when the user increases the font size.
        assertTrue(large * density * 1.3f > normal * density);
    }

    @Test
    public void clockTextIsClampedToTheFrameAtEveryFontScale() {
        float density = 2.75f;
        int framePx = 360;
        float baseSp = 120f;
        for (float scale = 1f; scale <= 2.01f; scale += 0.1f) {
            float sp = textSizeSpForFrame(baseSp, framePx, density, scale);
            assertTrue("sp must stay readable", sp >= MIN_FONT_SP);
            assertTrue("rendered text must stay inside the frame",
                    sp * density * scale <= framePx + 0.01f);
        }
    }

    @Test
    public void degenerateMetricsFallBackToTheRequestedSize() {
        assertEquals(64f, textSizeSpForFrame(64f, 0, 2f, 1f), 0.01f);
        assertEquals(64f, textSizeSpForFrame(64f, 200, 0f, 1f), 0.01f);
        assertEquals(64f, textSizeSpForFrame(64f, 200, 2f, 0f), 0.01f);
    }

    @Test
    public void forecastDaysSurviveLargeFontsAndNarrowWidgets() {
        assertEquals(2, boldForecastDays(180, 1f));
        assertEquals(3, boldForecastDays(260, 1f));
        assertEquals("large fonts must not lose a day", 3, boldForecastDays(260, 1.3f));
        assertEquals(3, boldForecastDays(260, MAX_SUPPORTED_FONT_SCALE));
        assertEquals(4, boldForecastDays(320, 1.3f));
        assertEquals(5, boldForecastDays(600, 1.3f));
        int previous = 0;
        for (int width = 150; width <= 700; width += 10) {
            int days = boldForecastDays(width, 1.3f);
            assertTrue("day count must not decrease when the widget grows", days >= previous);
            assertTrue("day count must stay in the supported layouts", days >= 2 && days <= 5);
            previous = days;
        }
    }

    @Test
    public void noteLineCountIsBoundedAndNeverDividesByZero() {
        assertEquals(1, noteLines(200f, 0));
        assertEquals(1, noteLines(0f, 200));
        assertEquals(2, noteLines(200f, 100));
        assertEquals(3, noteLines(1000f, 100));
    }

    @Test
    public void fontSizesStayWithinBounds() {
        assertEquals(8f, boundedFontSize(2f, 8f, 40f), 0.01f);
        assertEquals(40f, boundedFontSize(70f, 8f, 40f), 0.01f);
        assertEquals(14f, boundedFontSize(14f, 8f, 40f), 0.01f);
    }

    @Test
    public void largeWidgetLayoutStartsAbove120Dp() {
        assertFalse(useLargeWidgetLayout(LARGE_WIDGET_HEIGHT_DP));
        assertTrue(useLargeWidgetLayout(LARGE_WIDGET_HEIGHT_DP + 1));
        assertTrue(useLargeWidgetLayout(240));
    }

    @Test
    public void everyLargeWidgetShowsDailyMinAndMax() {
        // TASK-56: the old 240 dp threshold hid the temperatures in the common
        // two-row widget. The large bar has room for four rows, so it must
        // always render weekday / icon / max / min.
        assertFalse(showDailyTemperatures(120));
        assertTrue(showDailyTemperatures(121));
        assertTrue(showDailyTemperatures(160));
        assertTrue(showDailyTemperatures(240));
        assertTrue(showDailyTemperatures(400));
        int previous = 0;
        for (int height = 0; height <= 600; height += 10) {
            int shown = showDailyTemperatures(height) ? 1 : 0;
            assertTrue("daily values must not disappear as the widget grows", shown >= previous);
            previous = shown;
        }
    }
}

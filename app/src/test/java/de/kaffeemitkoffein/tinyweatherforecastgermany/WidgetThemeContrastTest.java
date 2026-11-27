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
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * TASK-56: widgets must stay readable in every theme. The contrast is checked
 * against the same colour resources the runtime uses (roundedbox_* backgrounds
 * and ThemePicker.getWidgetTextColor), without needing an Android runtime.
 */
public class WidgetThemeContrastTest {

    private static final String SRC =
            "src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/";

    /** WCAG AA for normal-size text; widget text is 7-14 sp. */
    private static final double MIN_CONTRAST = 4.5d;

    @Test
    public void everyThemeHasReadableWidgetTextOnItsWidgetBackground() throws Exception {
        Map<String, String> colors = readColors();
        // background from roundedbox_<theme>.xml, text colour from ThemePicker
        checkTheme("DARK", colors, "colorWidgetBackground_DarkTheme", "colorText_DarkTheme", false);
        checkTheme("LIGHT", colors, "colorWidgetBackground_LightTheme", "colorText_LightTheme", true);
        checkTheme("SOLARIZED", colors, "colorWidgetBackground_Solarized", "colorText_Solarized", true);
        // TASK-56: SolarizedDark uses base1 (TEXTLIGHT) instead of base0 to
        // clear 4.5:1 on the dark teal widget background.
        checkTheme("SOLARIZED_DARK", colors,
                "colorWidgetBackground_SolarizedDark", "colorTextLight_SolarizedDark", false);
    }

    @Test
    public void widgetTextColorSelectionIsThemeAware() throws Exception {
        String picker = read(SRC + "ThemePicker.java");
        int start = picker.indexOf("public static int getWidgetTextColor(");
        int end = picker.indexOf("public static int getColorTextLight(", start);
        assertTrue("missing getWidgetTextColor", start >= 0 && end > start);
        String method = picker.substring(start, end);

        assertTrue("dark Solarized variants must use the higher-contrast base1 colour",
                method.contains("WeatherSettings.Theme.SOLARIZED_DARK.equals(themePreference)") &&
                        method.contains("ThemeColor.TEXTLIGHT"));
        assertTrue("follow-device dark must get the same treatment",
                method.contains("WeatherSettings.Theme.FOLLOW_DEVICE.equals(themePreference)"));
        assertTrue("light themes must keep their own text colour",
                method.contains("return getColor(context,ThemeColor.TEXT);"));
    }

    @Test
    public void visibleWidgetTextIsRecolouredForTheActiveTheme() throws Exception {
        String classic = read(SRC + "ClassicWidget.java");
        assertTrue("warning text must follow the theme",
                classic.contains("remoteViews.setTextColor(textViewId,ThemePicker.getWidgetTextColor(context))"));
        int classicStart = classic.indexOf("if (weatherCard.currentWeather.hasTemperature()){");
        String classicTemperature = classic.substring(classicStart, classicStart + 700);
        assertTrue("the '-' temperature placeholder must follow the theme",
                classicTemperature.contains("remoteViews.setTextColor(R.id.classicwidget_temperature,ThemePicker.getWidgetTextColor(c))"));

        String bold = read(SRC + "BoldWidget.java");
        assertTrue("bold widget placeholders must follow the theme",
                bold.contains("remoteViews.setTextColor(dayOfWeekID,ThemePicker.getWidgetTextColor(c))") &&
                        bold.contains("remoteViews.setTextColor(maxTempID,ThemePicker.getWidgetTextColor(c))") &&
                        bold.contains("remoteViews.setTextColor(minTempID,ThemePicker.getWidgetTextColor(c))"));

        String chart = read(SRC + "ChartWidget.java");
        assertTrue("chart widget reference text must follow the theme",
                chart.contains("remoteViews.setTextColor(R.id.widget_reference_text,ThemePicker.getWidgetTextColor(context))"));
    }

    @Test
    public void staticPreviewsAreDocumentedAsDarkThemeOnly() throws Exception {
        // Previews are one static PNG/XML per provider, so they cannot follow
        // the device theme. They must at least be internally consistent and
        // explicitly document that the runtime applies the active theme.
        for (String provider : new String[]{"classic", "bold", "chart", "clock"}) {
            String layout = read("src/main/res/layout/preview_" + provider + "_widget.xml");
            assertTrue(provider + " preview background must be the dark widget colour",
                    layout.contains("@color/colorWidgetBackground_SolarizedDark"));
            assertTrue(provider + " preview must use light text on that background",
                    layout.contains("@color/colorTextWidget"));
            assertTrue(provider + " preview must state that runtime themes are applied",
                    layout.contains("ThemePicker applies a matching"));
        }
    }

    private static void checkTheme(String theme, Map<String, String> colors,
                                   String backgroundName, String textName, boolean expectDarkText) {
        String background = colors.get(backgroundName);
        String text = colors.get(textName);
        assertNotNull(theme + " background missing", background);
        assertNotNull(theme + " text colour missing", text);
        double ratio = contrast(background, text);
        assertTrue(theme + " widget contrast " + String.format("%.2f", ratio) + " < " + MIN_CONTRAST,
                ratio >= MIN_CONTRAST);
        if (expectDarkText) {
            assertTrue(theme + " must render dark text on a light background",
                    luminance(text) < luminance(background));
        } else {
            assertTrue(theme + " must render light text on a dark background",
                    luminance(text) > luminance(background));
        }
    }

    private static Map<String, String> readColors() throws Exception {
        String xml = read("src/main/res/values/colors.xml");
        Matcher matcher = Pattern.compile("<color\\s+name=\"([^\"]+)\"[^>]*>([^<]+)</color>")
                .matcher(xml);
        Map<String, String> colors = new HashMap<>();
        while (matcher.find()) {
            colors.put(matcher.group(1), matcher.group(2).trim());
        }
        // resolve aliases such as @color/colorText_DarkTheme
        for (Map.Entry<String, String> entry : new HashMap<>(colors).entrySet()) {
            String value = entry.getValue();
            if (value.startsWith("@color/")) {
                String resolved = colors.get(value.substring("@color/".length()));
                if (resolved != null) {
                    colors.put(entry.getKey(), resolved);
                }
            }
        }
        return colors;
    }

    private static double contrast(String color1, String color2) {
        double l1 = luminance(color1);
        double l2 = luminance(color2);
        return (Math.max(l1, l2) + 0.05d) / (Math.min(l1, l2) + 0.05d);
    }

    private static double luminance(String hex) {
        assertTrue("expected a #RRGGBB colour but got " + hex,
                hex != null && hex.matches("#[0-9a-fA-F]{6}"));
        double r = channel(hex.substring(1, 3));
        double g = channel(hex.substring(3, 5));
        double b = channel(hex.substring(5, 7));
        return 0.2126d * r + 0.7152d * g + 0.0722d * b;
    }

    private static double channel(String hex) {
        double value = Integer.parseInt(hex, 16) / 255d;
        return value <= 0.03928d ? value / 12.92d : Math.pow((value + 0.055d) / 1.055d, 2.4d);
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}

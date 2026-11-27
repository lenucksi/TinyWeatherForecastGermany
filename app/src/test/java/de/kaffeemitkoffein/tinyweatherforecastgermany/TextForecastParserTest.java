/*
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * TASK-16: fixture-based coverage of TextForecast.parse() for a DWD "feature"
 * text (the FA / "Wetterlage und Entwicklung" product). The fixture is a small
 * representative copy of the wire format, not production data: no network,
 * no Android classes and no device are needed.
 */
public class TextForecastParserTest {

    private static ArrayList<String> readFixture() throws IOException {
        InputStream in = TextForecastParserTest.class.getResourceAsStream(
                "/fixtures/feature-forecast.txt");
        assertNotNull("feature fixture missing from test resources", in);
        ArrayList<String> lines = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } finally {
            reader.close();
        }
        return lines;
    }

    @Test
    public void featureTextIsSplitIntoTitleSubtitleAndBody() throws IOException {
        TextForecast forecast = new TextForecast();
        forecast.type = TextForecasts.Type.FEATURE;

        forecast.parse(readFixture());

        assertEquals(TextForecasts.Type.FEATURE, forecast.type);
        assertEquals("Wetterlage und Entwicklung", forecast.title);
        assertNotNull(forecast.subtitle);
        assertTrue(forecast.subtitle.startsWith("Heiter bis wolkig, am Nachmittag Regen."));
        assertTrue(forecast.subtitle.endsWith("…"));
        assertNotNull(forecast.content);
        assertTrue(forecast.content.contains("Heiter bis wolkig, am Nachmittag Regen."));
        assertTrue(forecast.content.contains("Im Norden bleibt es trocken."));
    }

    @Test
    public void leadingBlankLinesAreSkippedWhenFindingTheTitle() throws IOException {
        ArrayList<String> lines = new ArrayList<String>();
        lines.add("");
        lines.add("");
        lines.addAll(readFixture());
        TextForecast forecast = new TextForecast();
        forecast.type = TextForecasts.Type.FEATURE;

        forecast.parse(lines);

        assertEquals("Wetterlage und Entwicklung", forecast.title);
    }
}

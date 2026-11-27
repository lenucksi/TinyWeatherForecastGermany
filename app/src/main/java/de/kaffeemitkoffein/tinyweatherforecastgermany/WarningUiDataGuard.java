/*
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2026 Pawel Dube
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import java.util.ArrayList;

/**
 * Null-safe preparation of warning data before the warning UI renders it.
 *
 * <p>Code evidence for issue #285 (no stack trace was supplied by the reporter):</p>
 * <ul>
 *   <li>{@link WeatherWarnings#getCurrentWarnings} returns null on a database error, but
 *       {@code WeatherWarningActivity.displayWarnings()} passed that value to
 *       {@link WeatherWarnings#getFirstWarningPosition}, which dereferences the list.</li>
 *   <li>{@code WeatherWarning.getWarningColor()} calls {@code area_color.trim()} and parses
 *       three integers. {@code area_color} is read verbatim from the warning database
 *       ({@code WeatherContentManager.getWeatherWarningFromCursor}), so a missing or malformed
 *       value throws when the map paints an active warning.</li>
 *   <li>{@code WeatherWarningActivity.drawAdvancedWindStencil()} dereferences
 *       {@code Weather.getCurrentWeatherInfo()}, which returns null when no forecast is cached.</li>
 * </ul>
 *
 * <p>This helper is intentionally free of Android imports so it can be covered by pure JUnit
 * tests. It never changes warning semantics: it only replaces unusable display data with
 * neutral values and skips unavailable forecast entries.</p>
 */
public final class WarningUiDataGuard {

    /** Neutral grey, valid for WeatherWarning.getWarningColor(). */
    public static final String DEFAULT_AREA_COLOR = "128 128 128";

    private WarningUiDataGuard() {
    }

    /** Returns a non-null list (a new empty list if the input was null). */
    public static ArrayList<WeatherWarning> nonNullList(ArrayList<WeatherWarning> warnings) {
        if (warnings == null) {
            return new ArrayList<WeatherWarning>();
        }
        return warnings;
    }

    /**
     * Returns a warning list that is safe to render: no null entries, a non-null identifier
     * and a parseable area colour. The original data is not modified.
     */
    public static ArrayList<WeatherWarning> sanitized(ArrayList<WeatherWarning> warnings) {
        ArrayList<WeatherWarning> result = new ArrayList<WeatherWarning>();
        for (WeatherWarning warning : nonNullList(warnings)) {
            if (warning == null) {
                continue;
            }
            if (warning.identifier == null) {
                warning.identifier = "";
            }
            if (!isValidAreaColor(warning.area_color)) {
                warning.area_color = DEFAULT_AREA_COLOR;
            }
            result.add(warning);
        }
        return result;
    }

    /** True when the value can be parsed by WeatherWarning.getWarningColor(). */
    public static boolean isValidAreaColor(String areaColor) {
        if (areaColor == null) {
            return false;
        }
        String[] parts = areaColor.trim().split("\\s+");
        if (parts.length != 3) {
            return false;
        }
        try {
            for (String part : parts) {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) {
                    return false;
                }
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return true;
    }

    /** Null-safe replacement for WeatherWarnings.getFirstWarningPosition. */
    public static int firstWarningPosition(ArrayList<WeatherWarning> fullList, ArrayList<WeatherWarning> applyingList) {
        if (fullList == null || applyingList == null) {
            return 0;
        }
        for (int i = 0; i < fullList.size(); i++) {
            WeatherWarning full = fullList.get(i);
            if (full == null || full.identifier == null) {
                continue;
            }
            for (int j = 0; j < applyingList.size(); j++) {
                WeatherWarning applying = applyingList.get(j);
                if (applying != null && full.identifier.equals(applying.identifier)) {
                    return i;
                }
            }
        }
        return 0;
    }

    /** Returns a usable index into a wind forecast list, or -1 when no data is available. */
    public static int windDataIndex(int requestedIndex, int listSize) {
        if (listSize <= 0 || requestedIndex < 0 || requestedIndex >= listSize) {
            return -1;
        }
        return requestedIndex;
    }
}

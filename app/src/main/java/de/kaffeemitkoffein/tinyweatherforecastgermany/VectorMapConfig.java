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

import android.content.Context;
import android.preference.PreferenceManager;

/**
 * TASK-52: configuration for the optional MapLibre vector basemap.
 *
 * <p>Kept free of MapLibre types so that the constants and the preference switch can be
 * verified by plain JVM unit tests without initialising the native map library.</p>
 */
public final class VectorMapConfig {

    /** Preference key declared in {@code res/xml/preferences.xml}. */
    public static final String PREF_USE_VECTOR_MAP = "PREF_map_vector";

    /**
     * The new vector map is the default; the bundled static map stays available as an
     * explicit fallback (see WeatherWarningActivity).
     */
    public static final boolean PREF_USE_VECTOR_MAP_DEFAULT = true;

    /** Free, open vector basemap served over HTTPS. */
    public static final String OPENFREEMAP_STYLE_URL = "https://tiles.openfreemap.org/styles/positron";

    /** Initial camera position: centred on Germany so the whole warning area is visible. */
    public static final double DEFAULT_CENTER_LATITUDE = 51.0;
    public static final double DEFAULT_CENTER_LONGITUDE = 10.0;
    public static final double DEFAULT_ZOOM = 5.0;

    private VectorMapConfig() {
    }

    /** Returns whether the MapLibre vector basemap should be used instead of the static map. */
    public static boolean isVectorMapEnabled(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_USE_VECTOR_MAP, PREF_USE_VECTOR_MAP_DEFAULT);
    }
}

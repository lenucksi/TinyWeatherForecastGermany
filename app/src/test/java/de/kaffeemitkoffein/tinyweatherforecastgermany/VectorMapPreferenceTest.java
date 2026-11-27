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

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** TASK-52: the vector/static map switch is a plain SharedPreferences boolean. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class VectorMapPreferenceTest {

    @Test
    public void vectorMapIsEnabledByDefaultAndCanBeSwitchedOff() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        preferences.edit().remove(VectorMapConfig.PREF_USE_VECTOR_MAP).commit();

        assertTrue(VectorMapConfig.isVectorMapEnabled(context));

        preferences.edit().putBoolean(VectorMapConfig.PREF_USE_VECTOR_MAP, false).commit();
        assertFalse(VectorMapConfig.isVectorMapEnabled(context));

        preferences.edit().putBoolean(VectorMapConfig.PREF_USE_VECTOR_MAP, true).commit();
        assertTrue(VectorMapConfig.isVectorMapEnabled(context));
    }
}

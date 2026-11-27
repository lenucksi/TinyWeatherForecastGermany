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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for issue #63: the wind-direction arc could take the long
 * way around the compass when the two absolute directions crossed 0/360 or
 * were outside [0,360). Pure JUnit - the helper is a static method of
 * Weather.WeatherInfo and needs no Android runtime.
 */
public class WindArcSweepTest {

    private static final float EPSILON = 0.0001f;

    private static float sweep(float source, float target) {
        return Weather.WeatherInfo.getSweepAngleFromAbsoluteDegrees(source, target);
    }

    /** The previous implementation, used only to prove ordinary angles are unchanged. */
    private static float legacySweep(float source, float target) {
        float result = target - source;
        if (result < -180) {
            result += 360;
        } else if (result > 180) {
            result -= 360;
        }
        return result;
    }

    @Test
    public void zeroDegreesHasNoSweep() {
        assertEquals(0f, sweep(0f, 0f), EPSILON);
        assertEquals(0f, sweep(180f, 180f), EPSILON);
        assertEquals(0f, sweep(359f, 359f), EPSILON);
    }

    @Test
    public void oneEightyDegreeBoundaryKeepsItsDirection() {
        assertEquals(180f, sweep(0f, 180f), EPSILON);
        assertEquals(-180f, sweep(180f, 0f), EPSILON);
        assertEquals(-180f, sweep(0f, -180f), EPSILON);
        assertEquals(180f, sweep(0f, 540f), EPSILON);
    }

    @Test
    public void threeHundredFiftyNineDegreesIsOnlyOneDegreeAway() {
        assertEquals(-1f, sweep(0f, 359f), EPSILON);
        assertEquals(1f, sweep(359f, 0f), EPSILON);
        assertEquals(179f, sweep(180f, 359f), EPSILON);
        assertEquals(-179f, sweep(359f, 180f), EPSILON);
    }

    @Test
    public void wrapAroundForwardTakesTheShortWay() {
        assertEquals(30f, sweep(350f, 20f), EPSILON);
        assertEquals(170f, sweep(280f, 90f), EPSILON);
        assertEquals(20f, sweep(359f, 19f), EPSILON);
    }

    @Test
    public void wrapAroundBackwardTakesTheShortWay() {
        assertEquals(-20f, sweep(10f, 350f), EPSILON);
        assertEquals(-10f, sweep(40f, 30f), EPSILON);
        assertEquals(-60f, sweep(180f, 120f), EPSILON);
    }

    @Test
    public void outOfRangeAbsoluteAnglesAreNormalized() {
        // A single +/-360 correction was not enough for these inputs.
        assertEquals(20f, sweep(720f, 20f), EPSILON);
        assertEquals(90f, sweep(1000f, 10f), EPSILON);
        assertEquals(20f, sweep(-10f, 10f), EPSILON);
        assertEquals(-180f, sweep(0f, -540f), EPSILON);
        assertEquals(0f, sweep(720f, 360f), EPSILON);
    }

    @Test
    public void sweepIsAlwaysTheShortestRotation() {
        for (int source = 0; source < 360; source += 15) {
            for (int target = 0; target < 360; target += 15) {
                float result = sweep(source, target);
                assertTrue("sweep out of range for " + source + "->" + target,
                        result >= -180f && result <= 180f);
                double landed = ((source + result) % 360 + 360) % 360;
                assertEquals("wrong destination for " + source + "->" + target,
                        target, landed, 0.001);
                assertTrue("long rotation for " + source + "->" + target,
                        Math.abs(result) <= Math.abs(result - 360f)
                                && Math.abs(result) <= Math.abs(result + 360f));
            }
        }
    }

    @Test
    public void ordinaryAnglesMatchThePreviousRendering() {
        // No visual regression in rows whose directions stay inside [0,360).
        for (int source = 0; source < 360; source += 5) {
            for (int target = 0; target < 360; target += 5) {
                assertEquals("changed rendering for " + source + "->" + target,
                        legacySweep(source, target), sweep(source, target), EPSILON);
            }
        }
    }
}

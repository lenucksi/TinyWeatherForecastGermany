/*
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2026 Pawel Dube
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import org.junit.Test;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression coverage for Codeberg #285 (crash when opening active warnings). No stack trace
 * was supplied by the reporter, so these tests encode the null/malformed data paths found by
 * code review: a null warning list from WeatherWarnings.getCurrentWarnings(), warnings with
 * missing colour or identifier fields, and a short/absent wind forecast.
 */
public class WarningUiDataGuardTest {

    private static WeatherWarning warning(String identifier, String areaColor) {
        WeatherWarning warning = new WeatherWarning();
        warning.identifier = identifier;
        warning.area_color = areaColor;
        return warning;
    }

    @Test
    public void nullWarningListBecomesEmptyList(){
        assertEquals(0, WarningUiDataGuard.nonNullList(null).size());
        assertNotNull(WarningUiDataGuard.sanitized(null));
        assertEquals(0, WarningUiDataGuard.sanitized(null).size());
    }

    @Test
    public void sanitizingDropsNullEntriesAndRepairsMissingFields(){
        ArrayList<WeatherWarning> warnings = new ArrayList<WeatherWarning>();
        warnings.add(null);
        warnings.add(warning(null,null));
        warnings.add(warning("valid-1","255 0 0"));
        ArrayList<WeatherWarning> safe = WarningUiDataGuard.sanitized(warnings);
        assertEquals(2, safe.size());
        assertEquals("", safe.get(0).identifier);
        assertEquals(WarningUiDataGuard.DEFAULT_AREA_COLOR, safe.get(0).area_color);
        assertEquals("valid-1", safe.get(1).identifier);
        assertEquals("255 0 0", safe.get(1).area_color);
        assertTrue(WarningUiDataGuard.isValidAreaColor(safe.get(1).area_color));
    }

    @Test
    public void malformedColorsAreRejected(){
        assertFalse(WarningUiDataGuard.isValidAreaColor(null));
        assertFalse(WarningUiDataGuard.isValidAreaColor(""));
        assertFalse(WarningUiDataGuard.isValidAreaColor("255,0,0"));
        assertFalse(WarningUiDataGuard.isValidAreaColor("256 0 0"));
        assertFalse(WarningUiDataGuard.isValidAreaColor("-1 0 0"));
        assertFalse(WarningUiDataGuard.isValidAreaColor("255 0"));
        assertFalse(WarningUiDataGuard.isValidAreaColor("255 0 red"));
        assertTrue(WarningUiDataGuard.isValidAreaColor("0 128 255"));
    }

    @Test
    public void firstWarningPositionToleratesNullAndMissingIdentifiers(){
        ArrayList<WeatherWarning> full = new ArrayList<WeatherWarning>();
        full.add(warning(null,"255 0 0"));
        full.add(warning("second","255 0 0"));
        ArrayList<WeatherWarning> applying = new ArrayList<WeatherWarning>();
        applying.add(warning("second","255 0 0"));
        assertEquals(1, WarningUiDataGuard.firstWarningPosition(full,applying));
        assertEquals(0, WarningUiDataGuard.firstWarningPosition(null,applying));
        assertEquals(0, WarningUiDataGuard.firstWarningPosition(full,null));
        assertEquals(0, WarningUiDataGuard.firstWarningPosition(new ArrayList<WeatherWarning>(),applying));
    }

    @Test
    public void windForecastIndexSkipsUnavailableData(){
        assertEquals(-1, WarningUiDataGuard.windDataIndex(0,0));
        assertEquals(-1, WarningUiDataGuard.windDataIndex(-1,5));
        assertEquals(-1, WarningUiDataGuard.windDataIndex(5,5));
        assertEquals(-1, WarningUiDataGuard.windDataIndex(8,3));
        assertEquals(0, WarningUiDataGuard.windDataIndex(0,1));
        assertEquals(2, WarningUiDataGuard.windDataIndex(2,5));
    }
}

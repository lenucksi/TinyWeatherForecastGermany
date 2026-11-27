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

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for Codeberg #308: an expired warning must not be announced again, and a
 * warning that was announced once (and possibly dismissed by the user) stays announced-only
 * across consecutive app starts. The map passed between steps stands in for the persisted
 * SharedPreferences state; no Android runtime is needed for this state logic.
 */
public class WeatherWarningAlertStateTest {

    private static final long NOW = 1700000000000L;
    private static final long TWELVE_DAYS = 1036800000L;

    private WeatherWarning warning(String identifier, long expires, long onset){
        WeatherWarning warning = new WeatherWarning();
        warning.identifier = identifier;
        warning.expires = expires;
        warning.onset = onset;
        warning.effective = onset;
        warning.severity = "Moderate";
        warning.event = "Rain";
        warning.sent = NOW - 60000L;
        return warning;
    }

    /** Simulates reading the persisted state back after a process restart. */
    private Map<String,String> restart(Map<String,String> persistedState){
        return new HashMap<String,String>(persistedState);
    }

    @Test
    public void expiredWarningIsNotAnnouncedOnFirstOrSecondStart(){
        WeatherWarning warning = warning("expired-1",NOW-1000L,NOW-60000L);
        Map<String,String> state = new HashMap<String,String>();
        assertTrue(warning.isExpired(NOW));
        assertFalse(WeatherWarning.AlertNotificationState.shouldAnnounce(warning,NOW,state));
        assertFalse(WeatherWarning.AlertNotificationState.shouldAnnounce(warning,NOW,restart(state)));
    }

    @Test
    public void expiredWarningIsNotAnnouncedEvenIfItWasAnnouncedEarlier(){
        WeatherWarning warning = warning("expired-2",NOW+60000L,NOW-60000L);
        Map<String,String> state = new HashMap<String,String>();
        assertTrue(WeatherWarning.AlertNotificationState.shouldAnnounce(warning,NOW,state));
        WeatherWarning.AlertNotificationState.record(state,warning,23,warning.getApplicableExpires());
        // Time moved past the stored expiry; the warning is still in the feed.
        assertFalse(WeatherWarning.AlertNotificationState.shouldAnnounce(warning,NOW+120000L,restart(state)));
    }

    @Test
    public void activeWarningIsAnnouncedOnceAcrossTwoConsecutiveStarts(){
        WeatherWarning warning = warning("active-1",NOW+3600000L,NOW);
        Map<String,String> firstStart = new HashMap<String,String>();
        assertTrue(WeatherWarning.AlertNotificationState.shouldAnnounce(warning,NOW,firstStart));
        WeatherWarning.AlertNotificationState.record(firstStart,warning,4711,warning.getApplicableExpires());

        Map<String,String> secondStart = restart(firstStart);
        assertFalse(WeatherWarning.AlertNotificationState.shouldAnnounce(warning,NOW+1000L,secondStart));
        String key = WeatherWarning.AlertNotificationState.prefKey(warning);
        assertEquals(4711,WeatherWarning.AlertNotificationState.notificationId(secondStart.get(key)));
        assertEquals(NOW+3600000L,WeatherWarning.AlertNotificationState.expiry(secondStart.get(key)));
    }

    @Test
    public void dismissedNotificationStaysDismissedAfterRestart(){
        WeatherWarning warning = warning("dismissed-1",NOW+3600000L,NOW);
        Map<String,String> firstStart = new HashMap<String,String>();
        WeatherWarning.AlertNotificationState.record(firstStart,warning,17,warning.getApplicableExpires());
        // Swiping the notification away does not remove the persisted announcement.
        Map<String,String> secondStart = restart(firstStart);
        assertFalse(WeatherWarning.AlertNotificationState.shouldAnnounce(warning,NOW+1000L,secondStart));
        assertFalse(WeatherWarning.AlertNotificationState.shouldAnnounce(warning,NOW+7200000L,secondStart));
    }

    @Test
    public void notificationIdIsStableForTheSameWarningEvent(){
        WeatherWarning warning = warning("stable-1",NOW+3600000L,NOW);
        Map<String,String> state = new HashMap<String,String>();
        WeatherWarning.AlertNotificationState.record(state,warning,42,warning.getApplicableExpires());
        WeatherWarning.AlertNotificationState.record(state,warning,99,warning.getApplicableExpires());
        assertEquals(42,WeatherWarning.AlertNotificationState.notificationId(state.get(WeatherWarning.AlertNotificationState.prefKey(warning))));
    }

    @Test
    public void differentWarningsKeepIndependentState(){
        WeatherWarning first = warning("first-1",NOW+3600000L,NOW);
        WeatherWarning second = warning("second-1",NOW+7200000L,NOW);
        Map<String,String> state = new HashMap<String,String>();
        WeatherWarning.AlertNotificationState.record(state,first,11,first.getApplicableExpires());
        WeatherWarning.AlertNotificationState.record(state,second,12,second.getApplicableExpires());
        Map<String,String> restarted = restart(state);
        assertFalse(WeatherWarning.AlertNotificationState.shouldAnnounce(first,NOW,restarted));
        assertFalse(WeatherWarning.AlertNotificationState.shouldAnnounce(second,NOW,restarted));
        assertNotEquals(WeatherWarning.AlertNotificationState.prefKey(first),WeatherWarning.AlertNotificationState.prefKey(second));
    }

    @Test
    public void missingExpiryUsesFallbackAndExpiresAfterTwelveDays(){
        WeatherWarning stillValid = warning("fallback-1",0,NOW-TWELVE_DAYS+1000L);
        WeatherWarning expired = warning("fallback-2",0,NOW-TWELVE_DAYS-1000L);
        assertFalse(WeatherWarning.AlertNotificationState.isExpired(stillValid,NOW));
        assertTrue(WeatherWarning.AlertNotificationState.isExpired(expired,NOW));
        Map<String,String> state = new HashMap<String,String>();
        assertTrue(WeatherWarning.AlertNotificationState.shouldAnnounce(stillValid,NOW,state));
        assertFalse(WeatherWarning.AlertNotificationState.shouldAnnounce(expired,NOW,state));
    }

    @Test
    public void legacyNegativeNotificationIdAndExpiryAreParsed(){
        String encoded = WeatherWarning.AlertNotificationState.encode(-2147483640,NOW+1000L);
        assertEquals(-2147483640,WeatherWarning.AlertNotificationState.notificationId(encoded));
        assertEquals(NOW+1000L,WeatherWarning.AlertNotificationState.expiry(encoded));
    }

    @Test
    public void corruptStateIsRepairedOnNextStart(){
        WeatherWarning warning = warning("corrupt-1",NOW+3600000L,NOW);
        Map<String,String> state = new HashMap<String,String>();
        state.put(WeatherWarning.AlertNotificationState.prefKey(warning),"corrupt");
        assertTrue(WeatherWarning.AlertNotificationState.shouldAnnounce(warning,NOW,state));
        WeatherWarning.AlertNotificationState.record(state,warning,5,warning.getApplicableExpires());
        assertEquals(5,WeatherWarning.AlertNotificationState.notificationId(state.get(WeatherWarning.AlertNotificationState.prefKey(warning))));
    }

    @Test
    public void eventKeyFallbackIsStableWhenIdentifierIsMissing(){
        WeatherWarning first = warning(null,NOW+3600000L,NOW);
        WeatherWarning same = warning(null,NOW+3600000L,NOW);
        WeatherWarning other = warning(null,NOW+3600000L,NOW+1000L);
        assertEquals(WeatherWarning.AlertNotificationState.prefKey(first),WeatherWarning.AlertNotificationState.prefKey(same));
        assertNotEquals(WeatherWarning.AlertNotificationState.prefKey(first),WeatherWarning.AlertNotificationState.prefKey(other));
    }
}

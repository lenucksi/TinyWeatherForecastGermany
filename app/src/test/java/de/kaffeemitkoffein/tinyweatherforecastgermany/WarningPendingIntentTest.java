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

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import java.util.ArrayList;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPendingIntent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for CodeQL java/android/implicit-pendingintents (alert #7): the
 * PendingIntent attached to a warning notification must wrap an explicit intent that opens
 * this app, not the implicit ACTION_SEND share intent.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {33, 36})
public class WarningPendingIntentTest {

    private static WeatherWarning warning(String identifier) {
        WeatherWarning warning = new WeatherWarning();
        warning.identifier = identifier;
        warning.event = "Thunderstorm";
        warning.headline = "Thunderstorm warning";
        warning.description = "Heavy thunderstorms expected.";
        warning.severity = "Severe";
        warning.urgency = "Immediate";
        warning.certainty = "Observed";
        warning.area_names = new ArrayList<String>();
        warning.area_names.add("Berlin");
        warning.onset = 1700000000000L;
        warning.expires = warning.onset + 3600000L;
        return warning;
    }

    @Test
    public void contentIntentIsExplicitAndCarriesWarningIdentifier(){
        Context context = RuntimeEnvironment.getApplication();
        Intent intent = WeatherSyncAdapter.getWarningContentIntent(context, warning("cap-123"));
        assertNotNull("component must be set explicitly", intent.getComponent());
        assertEquals(WeatherWarningActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(context.getPackageName(), intent.getPackage());
        assertEquals("cap-123", intent.getStringExtra(WeatherSyncAdapter.EXTRA_WARNING_IDENTIFIER));
        assertFalse(Intent.ACTION_SEND.equals(intent.getAction()));
    }

    @Test
    public void notificationPendingIntentOpensWarningScreenInsteadOfShareChooser(){
        Context context = RuntimeEnvironment.getApplication();
        PendingIntent pendingIntent = WeatherSyncAdapter.getWarningPendingIntent(context, warning("cap-456"), 4711);
        ShadowPendingIntent shadow = Shadows.shadowOf(pendingIntent);
        assertTrue("notification action must start an activity", shadow.isActivity());
        assertEquals(4711, shadow.getRequestCode());
        Intent saved = shadow.getSavedIntent();
        assertNotNull(saved);
        assertFalse("ACTION_SEND must never be wrapped in a notification PendingIntent",
                Intent.ACTION_SEND.equals(saved.getAction()));
        assertNotNull("wrapped intent must have an explicit component", saved.getComponent());
        assertEquals(WeatherWarningActivity.class.getName(), saved.getComponent().getClassName());
        assertEquals(context.getPackageName(), saved.getPackage());
        assertEquals("cap-456", saved.getStringExtra(WeatherSyncAdapter.EXTRA_WARNING_IDENTIFIER));
    }

    @Test
    public void notificationPendingIntentIsImmutableOnApi23AndAbove(){
        Context context = RuntimeEnvironment.getApplication();
        PendingIntent pendingIntent = WeatherSyncAdapter.getWarningPendingIntent(context, warning("flag-1"), 7);
        int flags = Shadows.shadowOf(pendingIntent).getFlags();
        assertEquals("FLAG_IMMUTABLE must be set on API 23+",
                PendingIntent.FLAG_IMMUTABLE, flags & PendingIntent.FLAG_IMMUTABLE);
    }

    @Test
    public void shareIntentStaysImplicitOnlyForDirectUserShareGesture(){
        Context context = RuntimeEnvironment.getApplication();
        Intent shareIntent = WeatherSyncAdapter.getWarningIntent(context, warning("share-1"));
        assertEquals(Intent.ACTION_SEND, shareIntent.getAction());
        assertEquals("text/plain", shareIntent.getType());
        assertNotNull(shareIntent.getStringExtra(Intent.EXTRA_TEXT));
        assertNotNull(shareIntent.getStringExtra(Intent.EXTRA_SUBJECT));
        // Deliberate no component: started directly from the warning list by the user,
        // never handed to a PendingIntent where it could be redirected.
        assertNull(shareIntent.getComponent());
    }
}

/*
 * This file is part of TinyWeatherForecastGermany.
 *
 * Regression tests for TASK-7 (receiver registration crash on Android 8-12L).
 *
 * Robolectric owns the version-branch proof: the flag-aware overload
 * Context.registerReceiver(receiver, filter, flags) only exists from API 33
 * (TIRAMISU). Calling it on API 26-32 throws NoSuchMethodError at runtime.
 * The live emulator AVD is API 37 and therefore cannot prove the API 26/28
 * branch; these tests can.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 28, 33, 36})
public class ReceiverRegistrationGuardTest {

    /**
     * Records which registerReceiver overload the production helper calls, without relying
     * on Robolectric shadow internals. The flag-aware overload is deliberately declared here
     * so that an unguarded call on API 26/28 is observable (and would throw NoSuchMethodError
     * against the real framework class if it reached the platform).
     */
    private static final class RecordingContext extends ContextWrapper {
        boolean legacyCalled = false;
        boolean flaggedCalled = false;
        int flags = Integer.MIN_VALUE;

        RecordingContext(Context base) {
            super(base);
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) {
            legacyCalled = true;
            return super.registerReceiver(receiver, filter);
        }

        @Override
        public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
            flaggedCalled = true;
            this.flags = flags;
            return super.registerReceiver(receiver, filter, flags);
        }
    }

    @Test
    public void registrationOverloadMatchesApiLevel() {
        RecordingContext context = new RecordingContext(RuntimeEnvironment.getApplication());
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                // no-op
            }
        };
        IntentFilter filter = new IntentFilter(
                "de.kaffeemitkoffein.tinyweatherforecastgermany.INTERNAL_TEST_ACTION");

        // Same helper used by all five Activities: MainActivity, TextForecastListActivity,
        // WeatherDetailsActivity, WeatherLayersActivity and WeatherWarningActivity.
        MainActivity.registerForInternalBroadcasts(context, receiver, filter);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            assertTrue("API 33+ must use the flag-aware overload", context.flaggedCalled);
            assertFalse("API 33+ must not use the legacy overload", context.legacyCalled);
            assertEquals("Internal broadcasts must not be exported",
                    Context.RECEIVER_NOT_EXPORTED, context.flags);
        } else {
            assertTrue("API 26-32 must use the legacy two-argument overload", context.legacyCalled);
            assertFalse("The flag-aware overload does not exist below API 33 "
                    + "(would throw NoSuchMethodError)", context.flaggedCalled);
        }
    }

    /**
     * The first-launch branch of MainActivity.onCreate() finishes the activity before
     * onResume() registers the receiver. onPause() must therefore not unregister a receiver
     * that was never registered.
     */
    @Test
    public void unregisterIsSkippedWhenReceiverWasNeverRegistered() throws Exception {
        MainActivity activity = new MainActivity();
        Method method = MainActivity.class.getDeclaredMethod("unRegisterForBroadcast");
        method.setAccessible(true);
        try {
            method.invoke(activity);
        } catch (InvocationTargetException e) {
            fail("unregisterReceiver must be skipped when nothing was registered: "
                    + e.getCause());
        }
    }
}

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

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** TASK-32: one owner per executor, cancelled tokens, no callbacks after destroy. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ExecutorLifecycleTest {

    private static final class TrackedTask implements APIReaders.CancellableTask {
        final CountDownLatch ran = new CountDownLatch(1);
        volatile boolean cancelled;

        @Override
        public void run() {
            ran.countDown();
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    @Test
    public void shutdownCancelsTrackedJobsAndDropsLaterWork() throws Exception {
        ScheduledExecutorService delegate = Executors.newSingleThreadScheduledExecutor();
        MainActivity.LifecycleExecutor owner = new MainActivity.LifecycleExecutor(delegate);

        TrackedTask immediate = new TrackedTask();
        owner.execute(immediate);
        assertTrue("submitted work must run before shutdown", immediate.ran.await(5, TimeUnit.SECONDS));

        TrackedTask delayed = new TrackedTask();
        owner.schedule(delayed, 10, TimeUnit.MINUTES);

        owner.shutdown();
        assertTrue("running job token must be cancelled", immediate.cancelled);
        assertTrue("scheduled job token must be cancelled", delayed.cancelled);
        assertTrue("delegate executor must be stopped", delegate.isShutdown());
        assertFalse("scheduled job must never run", delayed.ran.await(200, TimeUnit.MILLISECONDS));

        TrackedTask afterDestroy = new TrackedTask();
        owner.execute(afterDestroy);
        owner.schedule(afterDestroy, 1, TimeUnit.MILLISECONDS);
        assertFalse("no job may be tracked after destroy", afterDestroy.cancelled);
        assertFalse("no callback may run after destroy", afterDestroy.ran.await(300, TimeUnit.MILLISECONDS));
    }

    @Test
    public void activityDestroyShutsDownItsExecutor() throws Exception {
        MainActivity activity = Robolectric.buildActivity(MainActivity.class).get();
        ScheduledExecutorService delegate = Executors.newSingleThreadScheduledExecutor();
        MainActivity.LifecycleExecutor owner = new MainActivity.LifecycleExecutor(delegate);
        Field field = MainActivity.class.getDeclaredField("executor");
        field.setAccessible(true);
        field.set(activity, owner);

        activity.onDestroy();

        assertTrue("onDestroy must shut down the owned executor", owner.isShutdown());
        assertTrue("onDestroy must stop the worker thread", delegate.isShutdown());
    }

    @Test
    public void serviceCreatesOneExecutorAndStopsItOnDestroy() throws Exception {
        CreateAreasDatabaseService service = Robolectric.buildService(CreateAreasDatabaseService.class).get();
        Field field = CreateAreasDatabaseService.class.getDeclaredField("executor");
        field.setAccessible(true);

        service.onCreate();
        Object first = field.get(service);
        assertNotNull("onCreate must create the single executor", first);
        ExecutorService executor = (ExecutorService) first;

        service.onDestroy();
        assertTrue("onDestroy must shut down the service executor", executor.isShutdown());
    }

    @Test
    public void serviceDoesNotCreateASecondExecutorInOnStartCommand() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/de/kaffeemitkoffein/tinyweatherforecastgermany/CreateAreasDatabaseService.java")),
                StandardCharsets.UTF_8);
        int start = source.indexOf("onStartCommand");
        int end = source.indexOf("onDestroy", start);
        assertTrue("onStartCommand block not found", start >= 0 && end > start);
        assertFalse("no duplicate executor may be created in onStartCommand",
                source.substring(start, end).contains("newSingleThreadExecutor"));
    }

    @Test
    public void pollenReadersOwnContextAndCancellationPerInstance() throws Exception {
        assertNotStatic(APIReaders.PollenReader.class, "context");
        assertNotStatic(APIReaders.PollenReader.class, "cancellation");
        assertNotStatic(APIReaders.PollenAreaReader.class, "context");
        assertNotStatic(APIReaders.PollenAreaReader.class, "cancellation");

        APIReaders.PollenReader first = new APIReaders.PollenReader(null);
        APIReaders.PollenReader second = new APIReaders.PollenReader(null);
        first.cancel();
        assertTrue(cancellationOf(APIReaders.PollenReader.class, first).isCancelled());
        assertFalse("one reader must not cancel another reader's request",
                cancellationOf(APIReaders.PollenReader.class, second).isCancelled());

        APIReaders.PollenAreaReader firstArea = new APIReaders.PollenAreaReader(null);
        APIReaders.PollenAreaReader secondArea = new APIReaders.PollenAreaReader(null);
        firstArea.cancel();
        assertTrue(cancellationOf(APIReaders.PollenAreaReader.class, firstArea).isCancelled());
        assertFalse(cancellationOf(APIReaders.PollenAreaReader.class, secondArea).isCancelled());
    }

    private static void assertNotStatic(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        assertFalse(fieldName + " must be instance-owned to avoid leaking a Context",
                Modifier.isStatic(field.getModifiers()));
    }

    private static APIReaders.Cancellation cancellationOf(Class<?> type, Object instance) throws Exception {
        Field field = type.getDeclaredField("cancellation");
        field.setAccessible(true);
        return (APIReaders.Cancellation) field.get(instance);
    }
}

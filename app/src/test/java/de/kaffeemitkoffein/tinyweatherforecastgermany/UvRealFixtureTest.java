/*
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2026 Pawel Dube
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package de.kaffeemitkoffein.tinyweatherforecastgermany;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.InputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * TASK-92: three REAL DWD dwd:UVI_Global_CL GetMap responses (see
 * /fixtures/uvi-cl-real-PROVENANCE.txt for the exact URLs, times and hashes). The expected
 * values below were measured from the unmodified files, not invented. They prove that the
 * dark band is the legitimate UV index 0 step (UVHazardIndex.UVIndexColors[0]) and that
 * the map overlay consumes those exact pixels without recolouring.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class UvRealFixtureTest {

    private static final int WIDTH = 622;
    private static final int HEIGHT = 844;

    // Image row 0 = north (55.057375 N); column 0 = west (5.865999 E).
    private static final int HAMBURG_X = 279;
    private static final int HAMBURG_Y = 163;
    private static final int BERLIN_X = 510;
    private static final int BERLIN_Y = 275;

    private static Bitmap bitmap(String name) throws Exception {
        try (InputStream in = UvRealFixtureTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertNotNull("missing fixture " + name, in);
            Bitmap bitmap = BitmapFactory.decodeStream(in);
            assertNotNull("cannot decode " + name, bitmap);
            return bitmap;
        }
    }

    private static int count(Bitmap bitmap, int color) {
        int result = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (bitmap.getPixel(x, y) == color) {
                    result++;
                }
            }
        }
        return result;
    }

    @Test
    public void realFrame20261128HasBlackZeroStepAtHamburgAndGreenOneAtBerlin() throws Exception {
        Bitmap bitmap = bitmap("uvi-cl-real-20261128.png");
        assertEquals(WIDTH, bitmap.getWidth());
        assertEquals(HEIGHT, bitmap.getHeight());
        assertEquals(0xff000000, bitmap.getPixel(HAMBURG_X, HAMBURG_Y));
        assertEquals(0xff4eb400, bitmap.getPixel(BERLIN_X, BERLIN_Y));
        assertEquals(142925, count(bitmap, 0xff000000));
        assertEquals(382043, count(bitmap, 0xff4eb400));
    }

    @Test
    public void realFrame20261129DiffersButUsesTheSameTwoPaletteSteps() throws Exception {
        Bitmap bitmap = bitmap("uvi-cl-real-20261129.png");
        assertEquals(WIDTH, bitmap.getWidth());
        assertEquals(HEIGHT, bitmap.getHeight());
        assertEquals(0xff000000, bitmap.getPixel(HAMBURG_X, HAMBURG_Y));
        assertEquals(0xff4eb400, bitmap.getPixel(BERLIN_X, BERLIN_Y));
        assertEquals(262663, count(bitmap, 0xff000000));
        assertEquals(262305, count(bitmap, 0xff4eb400));
    }

    @Test
    public void realFrame20261130PlacesTheZeroStepOverBerlinToo() throws Exception {
        Bitmap bitmap = bitmap("uvi-cl-real-20261130.png");
        assertEquals(WIDTH, bitmap.getWidth());
        assertEquals(HEIGHT, bitmap.getHeight());
        assertEquals(0xff000000, bitmap.getPixel(HAMBURG_X, HAMBURG_Y));
        assertEquals(0xff000000, bitmap.getPixel(BERLIN_X, BERLIN_Y));
        assertEquals(313574, count(bitmap, 0xff000000));
        assertEquals(211394, count(bitmap, 0xff4eb400));
    }

    @Test
    public void blackAndGreenAreDocumentedUvStepsNotMissingData() throws Exception {
        Bitmap bitmap = bitmap("uvi-cl-real-20261128.png");
        assertEquals(0, UVHazardIndex.getUvIndexFromColor(0xff000000));
        assertEquals(1, UVHazardIndex.getUvIndexFromColor(0xff4eb400));
        int transparent = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (bitmap.getPixel(x, y) == 0) {
                    transparent++;
                }
            }
        }
        assertEquals("real DWD frame has no no-data/transparent pixels", 0, transparent);
        // The two documented steps account for every pixel of this frame.
        assertTrue(count(bitmap, 0xff000000) + count(bitmap, 0xff4eb400) == WIDTH * HEIGHT);
    }
}

/*
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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import de.kaffeemitkoffein.tinyweatherforecastgermany.WeatherContentProvider.WeatherDatabaseHelper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * TASK-33: verifies that the incremental migrations turn a real, already
 * populated database from an older schema version into the current schema
 * without throwing away cached data. The fixtures are SQLite files generated
 * from the CREATE TABLE statements of the corresponding historical app
 * versions; they contain one cached row in every table that existed then.
 */
@RunWith(RobolectricTestRunner.class)
public class DatabaseMigrationTest {

    private static final String[] TABLES = {
            WeatherContentProvider.TABLE_NAME_FORECASTS,
            WeatherContentProvider.TABLE_NAME_WARNINGS,
            WeatherContentProvider.TABLE_NAME_TEXTS,
            WeatherContentProvider.TABLE_NAME_AREAS,
            WeatherContentProvider.TABLE_NAME_POLLENAREAS,
            WeatherContentProvider.TABLE_NAME_POLLEN,
            WeatherContentProvider.TABLE_NAME_DATA
    };

    private static final String[] CREATE_STATEMENTS = {
            WeatherDatabaseHelper.SQL_COMMAND_CREATE_TABLE_FORECASTS,
            WeatherDatabaseHelper.SQL_COMMAND_CREATE_TABLE_WARNINGS,
            WeatherDatabaseHelper.SQL_COMMAND_CREATE_TABLE_TEXTS,
            WeatherDatabaseHelper.SQL_COMMAND_CREATE_TABLE_AREAS,
            WeatherDatabaseHelper.SQL_COMMAND_CREATE_TABLE_POLLENAREAS,
            WeatherDatabaseHelper.SQL_COMMAND_CREATE_TABLE_POLLEN,
            WeatherDatabaseHelper.SQL_COMMAND_CREATE_TABLE_DATA
    };

    private Context context;
    private File databaseFile;

    @Before
    public void installFixture() throws IOException {
        context = RuntimeEnvironment.getApplication();
        databaseFile = context.getDatabasePath(WeatherContentProvider.DATABASE_NAME);
        File databaseDir = databaseFile.getParentFile();
        assertTrue(databaseDir.isDirectory() || databaseDir.mkdirs());
        copyFixture("weather-v2.db");
    }

    @After
    public void removeDatabase() {
        new File(databaseFile.getAbsolutePath() + "-wal").delete();
        new File(databaseFile.getAbsolutePath() + "-shm").delete();
        databaseFile.delete();
    }

    private void copyFixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + name)) {
            assertNotNull("missing database fixture " + name, in);
            Files.copy(in, databaseFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private SQLiteDatabase upgrade() {
        WeatherDatabaseHelper helper = new WeatherDatabaseHelper(context);
        // Opening the database triggers onUpgrade because the fixture has a
        // lower user_version than WeatherContentProvider.DATABASE_VERSION.
        return helper.getWritableDatabase();
    }

    @Test
    public void upgradeFromVersion2KeepsCachedDataAndAddsNewColumns() {
        SQLiteDatabase db = upgrade();
        assertEquals(WeatherContentProvider.DATABASE_VERSION, userVersion(db));

        Cursor forecasts = db.query(WeatherContentProvider.TABLE_NAME_FORECASTS,
                null, null, null, null, null, null);
        try {
            assertEquals(1, forecasts.getCount());
            assertTrue(forecasts.moveToFirst());
            assertEquals("TEST-STATION", text(forecasts, WeatherDatabaseHelper.KEY_FORECASTS_name));
            assertEquals("Cached station", text(forecasts, WeatherDatabaseHelper.KEY_FORECASTS_description));
            assertEquals("3.5_,_7.25", text(forecasts, WeatherDatabaseHelper.KEY_FORECASTS_TTT));
            assertEquals(1767240000001L, forecasts.getLong(
                    forecasts.getColumnIndexOrThrow(WeatherDatabaseHelper.KEY_FORECASTS_polling_time)));
            // Columns introduced by the v3, v5 and v6 migrations are present and
            // backfilled with the serialized "no data" value, not left null.
            assertEquals("", text(forecasts, WeatherDatabaseHelper.KEY_FORECASTS_RR1));
            assertEquals("", text(forecasts, WeatherDatabaseHelper.KEY_FORECASTS_Neff));
            assertEquals("", text(forecasts, WeatherDatabaseHelper.KEY_FORECASTS_RadS1));
            assertEquals("", text(forecasts, WeatherDatabaseHelper.KEY_FORECASTS_description_alternate));
        } finally {
            forecasts.close();
        }

        Cursor warnings = db.query(WeatherContentProvider.TABLE_NAME_WARNINGS,
                null, null, null, null, null, null);
        try {
            assertEquals(1, warnings.getCount());
            assertTrue(warnings.moveToFirst());
            assertEquals("Cached warning", text(warnings, WeatherDatabaseHelper.KEY_WARNINGS_headline));
        } finally {
            warnings.close();
        }

        Cursor texts = db.query(WeatherContentProvider.TABLE_NAME_TEXTS,
                null, null, null, null, null, null);
        try {
            assertEquals(1, texts.getCount());
            assertTrue(texts.moveToFirst());
            assertEquals("Cached text forecast", text(texts, WeatherDatabaseHelper.KEY_TEXTS_content));
        } finally {
            texts.close();
        }

        // v6 added centroid columns; their values are derived from the cached
        // polygons instead of requiring an areas.txt re-download.
        Cursor areas = db.query(WeatherContentProvider.TABLE_NAME_AREAS,
                null, null, null, null, null, null);
        try {
            assertEquals(1, areas.getCount());
            assertTrue(areas.moveToFirst());
            assertEquals("Test area", text(areas, WeatherDatabaseHelper.KEY_AREAS_name));
            // The fixture polygon closes on its first point, so the mean of
            // the five stored vertices is 50.4 / 8.4 (as in Areas.buildDatabase).
            assertEquals(50.4, areas.getDouble(
                    areas.getColumnIndexOrThrow(WeatherDatabaseHelper.KEY_AREAS_centroid_latitude)), 0.001);
            assertEquals(8.4, areas.getDouble(
                    areas.getColumnIndexOrThrow(WeatherDatabaseHelper.KEY_AREAS_centroid_longitude)), 0.001);
        } finally {
            areas.close();
        }

        // Tables added in v4 and v7 exist and are usable.
        ContentValues values = new ContentValues();
        values.put(WeatherDatabaseHelper.KEY_DATA_type, 1);
        values.put(WeatherDatabaseHelper.KEY_DATA_valueString, "migrated");
        assertTrue(db.insert(WeatherContentProvider.TABLE_NAME_DATA, null, values) > 0);
        assertEquals(1, rowCount(db, WeatherContentProvider.TABLE_NAME_DATA));
        assertTrue(hasTable(db, WeatherContentProvider.TABLE_NAME_POLLEN));
        assertTrue(hasTable(db, WeatherContentProvider.TABLE_NAME_POLLENAREAS));
    }

    @Test
    public void upgradeFromVersion6KeepsPollenCacheAndAddsDescription() throws IOException {
        copyFixture("weather-v6.db");
        SQLiteDatabase db = upgrade();
        assertEquals(WeatherContentProvider.DATABASE_VERSION, userVersion(db));

        Cursor pollen = db.query(WeatherContentProvider.TABLE_NAME_POLLEN,
                null, null, null, null, null, null);
        try {
            assertEquals(1, pollen.getCount());
            assertTrue(pollen.moveToFirst());
            assertEquals(1, pollen.getInt(pollen.getColumnIndexOrThrow(
                    WeatherDatabaseHelper.KEY_POLLEN_region_id)));
            assertEquals(2, pollen.getInt(pollen.getColumnIndexOrThrow(
                    WeatherDatabaseHelper.KEY_POLLEN_ambrosia0)));
        } finally {
            pollen.close();
        }

        Cursor pollenAreas = db.query(WeatherContentProvider.TABLE_NAME_POLLENAREAS,
                null, null, null, null, null, null);
        try {
            assertEquals(1, pollenAreas.getCount());
            assertTrue(pollenAreas.moveToFirst());
            assertEquals("", text(pollenAreas, WeatherDatabaseHelper.KEY_POLLENAREA_description));
        } finally {
            pollenAreas.close();
        }

        assertEquals(1, rowCount(db, WeatherContentProvider.TABLE_NAME_FORECASTS));
    }

    @Test
    public void migratedSchemaHasSameColumnsAndTypesAsFreshDatabase() {
        SQLiteDatabase migrated = upgrade();
        SQLiteDatabase fresh = SQLiteDatabase.create(null);
        try {
            for (String statement : CREATE_STATEMENTS) {
                fresh.execSQL(statement);
            }
            for (String table : TABLES) {
                assertEquals("schema of " + table, schema(fresh, table), schema(migrated, table));
            }
        } finally {
            fresh.close();
        }
    }

    @Test
    public void upgradeWithNoNewVersionStepsDoesNotTouchRows() {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        try {
            for (String statement : CREATE_STATEMENTS) {
                db.execSQL(statement);
            }
            ContentValues values = new ContentValues();
            values.put(WeatherDatabaseHelper.KEY_FORECASTS_name, "KEEP-ME");
            db.insert(WeatherContentProvider.TABLE_NAME_FORECASTS, null, values);

            // Same version on both sides: there is no step to run and no table
            // may be dropped (the old implementation always dropped all seven).
            new WeatherDatabaseHelper(context).onUpgrade(db,
                    WeatherContentProvider.DATABASE_VERSION, WeatherContentProvider.DATABASE_VERSION);

            assertEquals(1, rowCount(db, WeatherContentProvider.TABLE_NAME_FORECASTS));
            Cursor cursor = db.query(WeatherContentProvider.TABLE_NAME_FORECASTS,
                    new String[]{WeatherDatabaseHelper.KEY_FORECASTS_name}, null, null, null, null, null);
            try {
                assertTrue(cursor.moveToFirst());
                assertEquals("KEEP-ME", text(cursor, WeatherDatabaseHelper.KEY_FORECASTS_name));
            } finally {
                cursor.close();
            }
        } finally {
            db.close();
        }
    }

    private static int userVersion(SQLiteDatabase db) {
        Cursor cursor = db.rawQuery("PRAGMA user_version", null);
        try {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        } finally {
            cursor.close();
        }
    }

    private static String text(Cursor cursor, String column) {
        return cursor.getString(cursor.getColumnIndexOrThrow(column));
    }

    private static int rowCount(SQLiteDatabase db, String table) {
        Cursor cursor = db.query(table, new String[]{"id"}, null, null, null, null, null);
        try {
            return cursor.getCount();
        } finally {
            cursor.close();
        }
    }

    private static boolean hasTable(SQLiteDatabase db, String table) {
        Cursor cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
                new String[]{table});
        try {
            return cursor.moveToFirst();
        } finally {
            cursor.close();
        }
    }

    /** Column name -> declared type, independent of the column order. */
    private static Map<String, String> schema(SQLiteDatabase db, String table) {
        Cursor cursor = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        Map<String, String> result = new TreeMap<>();
        try {
            int nameIndex = cursor.getColumnIndexOrThrow("name");
            int typeIndex = cursor.getColumnIndexOrThrow("type");
            while (cursor.moveToNext()) {
                result.put(cursor.getString(nameIndex), cursor.getString(typeIndex));
            }
            assertFalse("no columns in " + table, result.isEmpty());
            return result;
        } finally {
            cursor.close();
        }
    }
}

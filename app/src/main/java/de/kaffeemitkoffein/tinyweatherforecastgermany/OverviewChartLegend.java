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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Single source of truth for the meaning of the colours and layers used by
 * {@link ForecastBitmap#getOverviewChart(Context, int, int, ArrayList, ArrayList)}.
 *
 * <p>The alpha values are shared with the chart so the legend can never drift
 * away from what is actually drawn. The sample image is a real chart rendered
 * from synthetic data with the very same drawing routine.</p>
 */
public final class OverviewChartLegend {

    public static final int ALPHA_CLOUDS = 125;
    public static final int ALPHA_RAIN = 125;
    /** Was 256 in the chart; Paint.setAlpha() clamps to 255, so 255 is identical. */
    public static final int ALPHA_RAIN_AMOUNT = 255;
    public static final int ALPHA_WARNINGS = 65;

    public enum Shape {
        LINE, AREA, BAR, BAND, BACKGROUND, DASH
    }

    public static class Item {
        public final int labelResource;
        public final int color;
        public final int[] colors;
        public final int alpha;
        public final Shape shape;
        public final boolean visible;

        private Item(int labelResource, int color, int[] colors, int alpha, Shape shape, boolean visible) {
            this.labelResource = labelResource;
            this.color = color;
            this.colors = colors;
            this.alpha = alpha;
            this.shape = shape;
            this.visible = visible;
        }
    }

    private OverviewChartLegend() {
    }

    public static List<Item> getItems(Context context) {
        List<Item> items = new ArrayList<Item>();
        items.add(new Item(R.string.legend_temp_above,
                ThemePicker.getColor(context, ThemePicker.ThemeColor.ORANGE), null, 255, Shape.LINE, true));
        items.add(new Item(R.string.legend_temp_below,
                ThemePicker.getColor(context, ThemePicker.ThemeColor.CYAN), null, 255, Shape.LINE, true));
        items.add(new Item(R.string.legend_clouds,
                ThemePicker.getColor(context, ThemePicker.ThemeColor.TEXTDARK), null, ALPHA_CLOUDS, Shape.AREA, true));
        items.add(new Item(R.string.legend_rain_prob,
                ThemePicker.getColor(context, ThemePicker.ThemeColor.BLUE), null, ALPHA_RAIN, Shape.AREA, true));
        items.add(new Item(R.string.legend_rain_amount,
                ThemePicker.getColor(context, ThemePicker.ThemeColor.CYAN), null, ALPHA_RAIN_AMOUNT, Shape.BAR,
                WeatherSettings.displayPrecipitationAmountInOverviewChart(context)));
        boolean showHumidity = WeatherSettings.displayRHInOverviewChart(context);
        boolean showDewPoint = showHumidity && WeatherSettings.displayDewPoint(context);
        items.add(new Item(R.string.legend_humidity,
                ThemePicker.getColor(context, ThemePicker.ThemeColor.BLUE), null, 255, Shape.LINE,
                showHumidity && !showDewPoint));
        items.add(new Item(R.string.legend_dewpoint,
                ThemePicker.getColor(context, ThemePicker.ThemeColor.BLUE), null, 255, Shape.LINE, showDewPoint));
        items.add(new Item(R.string.legend_wind,
                ThemePicker.getColor(context, ThemePicker.ThemeColor.TEXT), null, 255, Shape.LINE,
                WeatherSettings.displayWindInCharts(context)));
        items.add(new Item(R.string.legend_wind_high,
                ThemePicker.getColor(context, ThemePicker.ThemeColor.YELLOW), null, 255, Shape.LINE,
                WeatherSettings.displayWindInCharts(context)));
        items.add(new Item(R.string.legend_warnings, 0, WeatherWarning.Severity.colors, ALPHA_WARNINGS, Shape.BAND,
                !WeatherSettings.areWarningsDisabled(context)));
        items.add(new Item(R.string.legend_daytime, getDaytimeColor(context), null, getDaytimeAlpha(context),
                Shape.BACKGROUND, WeatherSettings.visualizeDaytime(context)));
        items.add(new Item(R.string.legend_midnight,
                ThemePicker.getColor(context, ThemePicker.ThemeColor.TEXTLIGHT), null, 255, Shape.DASH, true));
        items.add(new Item(R.string.legend_axis,
                ThemePicker.getColor(context, ThemePicker.ThemeColor.TEXTLIGHT), null, 255, Shape.LINE, true));
        return items;
    }

    /** Mirrors the daytime colour calculation in ForecastBitmap.getOverviewChart(). */
    private static int getDaytimeColor(Context context) {
        int color = ThemePicker.getColor(context, ThemePicker.ThemeColor.PRIMARY);
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = getDaytimeAlpha(context);
        return Color.HSVToColor(hsv);
    }

    private static int getDaytimeAlpha(Context context) {
        String theme = WeatherSettings.getThemePreference(context);
        if (WeatherSettings.Theme.LIGHT.equals(theme)) {
            return 50;
        }
        if (WeatherSettings.Theme.SOLARIZED.equals(theme)) {
            return 125;
        }
        return 25;
    }

    /** Draws a small swatch that matches the way the element is drawn in the chart. */
    public static Bitmap getSwatchBitmap(Context context, Item item, int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setAlpha(item.alpha);
        int[] colors = item.colors;
        if (colors != null && colors.length > 0) {
            float stripeWidth = (float) width / colors.length;
            for (int i = 0; i < colors.length; i++) {
                paint.setColor(colors[i]);
                canvas.drawRect(i * stripeWidth, 0, (i + 1) * stripeWidth, height, paint);
            }
            return bitmap;
        }
        paint.setColor(item.color);
        float centerY = height / 2f;
        switch (item.shape) {
            case AREA:
                canvas.drawRect(new RectF(1, height * 0.15f, width - 1, height - 1), paint);
                break;
            case BACKGROUND:
                canvas.drawRect(new RectF(0, 0, width, height), paint);
                break;
            case BAR:
                paint.setStrokeWidth(Math.max(6, width / 8));
                canvas.drawLine(width / 2f, height - 1, width / 2f, height * 0.2f, paint);
                break;
            case BAND:
                canvas.drawRect(new RectF(0, height * 0.2f, width, height * 0.8f), paint);
                break;
            case DASH:
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2);
                paint.setPathEffect(new DashPathEffect(new float[]{3, 4}, 0));
                canvas.drawLine(width / 2f, 1, width / 2f, height - 1, paint);
                break;
            case LINE:
            default:
                paint.setStrokeWidth(4);
                canvas.drawLine(2, centerY, width - 2, centerY, paint);
                break;
        }
        return bitmap;
    }

    /**
     * The chart bitmap currently shown on the main screen. MainActivity hands it
     * over whenever it redraws the chart; a weak reference avoids keeping a large
     * bitmap alive after the main screen is gone.
     */
    private static WeakReference<Bitmap> liveChart = new WeakReference<Bitmap>(null);

    public static void setLiveChartBitmap(Bitmap bitmap) {
        liveChart = new WeakReference<Bitmap>(bitmap);
    }

    /** Returns the live chart, or null if it has not been rendered yet. */
    public static Bitmap getLiveChartBitmap() {
        return liveChart.get();
    }
}

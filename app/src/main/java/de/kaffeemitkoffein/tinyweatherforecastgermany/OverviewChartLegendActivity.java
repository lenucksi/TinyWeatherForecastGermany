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

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Illustrated legend for the overview chart on the main screen (issue #304). */
public class OverviewChartLegendActivity extends Activity {

    private Context context;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        context = getApplicationContext();
        try {
            ThemePicker.SetTheme(this);
        } catch (Exception e) {
            PrivateLog.log(context, PrivateLog.MAIN, PrivateLog.ERR,
                    "Error setting theme in OverviewChartLegendActivity.");
        }
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        WeatherSettings.setRotationMode(this);
        setContentView(R.layout.activity_overviewchart_legend);

        Button back = (Button) findViewById(R.id.legend_button_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });

        displaySample();
        displayItems();
    }

    private void displaySample() {
        ImageView sample = (ImageView) findViewById(R.id.legend_sample);
        if (sample == null) {
            return;
        }
        android.graphics.Bitmap bitmap = OverviewChartLegend.getLiveChartBitmap();
        if (bitmap == null) {
            sample.setVisibility(View.GONE);
            TextView caption = (TextView) findViewById(R.id.legend_sample_caption);
            if (caption != null) {
                caption.setVisibility(View.GONE);
            }
            return;
        }
        sample.setImageBitmap(bitmap);
    }

    private void displayItems() {
        LinearLayout container = (LinearLayout) findViewById(R.id.legend_items);
        if (container == null) {
            return;
        }
        int textColor = ThemePicker.getWidgetTextColor(context);
        float textSize = getResources().getDimension(R.dimen.fcmain_textsize_medium);
        for (OverviewChartLegend.Item item : OverviewChartLegend.getItems(context)) {
            if (!item.visible) {
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            ImageView swatch = new ImageView(this);
            LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(dp(56), dp(28));
            swatchParams.setMargins(dp(6), dp(3), dp(8), dp(3));
            swatch.setLayoutParams(swatchParams);
            swatch.setScaleType(ImageView.ScaleType.FIT_XY);
            swatch.setImageBitmap(OverviewChartLegend.getSwatchBitmap(context, item, dp(56), dp(28)));
            row.addView(swatch);

            TextView label = new TextView(this);
            label.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            label.setText(item.labelResource);
            label.setTextColor(textColor);
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
            row.addView(label);

            container.addView(row);
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}

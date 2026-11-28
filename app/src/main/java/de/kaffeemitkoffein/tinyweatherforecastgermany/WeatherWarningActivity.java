/*
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2020, 2021, 2022, 2023, 2024 Pawel Dube
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package de.kaffeemitkoffein.tinyweatherforecastgermany;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.UnderlineSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.maplibre.android.MapLibre;
import org.maplibre.android.WellKnownTileServer;
import org.maplibre.android.annotations.IconFactory;
import org.maplibre.android.annotations.Marker;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.annotations.Polyline;
import org.maplibre.android.annotations.PolylineOptions;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.geometry.LatLngQuad;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.OnMapReadyCallback;
import org.maplibre.android.maps.Style;
import org.maplibre.android.maps.UiSettings;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.RasterLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.sources.ImageSource;

public class WeatherWarningActivity extends Activity {

    ArrayList<WeatherWarning> weatherWarnings;
    ArrayList<WeatherWarning> localWarnings;
    Weather.WeatherLocation ownLocation;
    View.OnTouchListener mapTouchListener;
    ArrayList<Polygon> polygoncache;
    ArrayList<Polygon> excluded_polygoncache;
    ImageView germany;
    ImageView warningactivity_map_collapsed;
    RelativeLayout mapcontainer;
    ProgressBar rainSlideProgressBar;
    TextView rainSlideProgressBarText;
    TextView rainSlideTime;
    ImageView rainDescription;
    Bitmap germanyBitmap;
    Bitmap warningsBitmap;
    Bitmap radarBitmap;
    Bitmap administrativeBitmap;
    Bitmap advancedWindStencilBitmap;
    Bitmap visibleBitmap;
    ZoomableImageView mapZoomable;
    // TASK-52: optional MapLibre vector basemap; the static map above remains the fallback.
    MapView mapLibreMapView;
    boolean useVectorMap;
    // TASK-53: vector warning layers. The native map is only touched after its style loaded.
    MapLibreMap mapLibreMap;
    boolean vectorWarningStyleReady;
    // TASK-55: vector-only station pin and wind-distance arcs.
    Marker vectorPinMarker;
    ArrayList<Polyline> vectorWindPolylines = new ArrayList<Polyline>();
    // TASK-67: custom MapLibre navigation controls (MapLibre 13.8 has no zoom buttons).
    View mapControls;
    ImageButton mapZoomInButton;
    ImageButton mapZoomOutButton;
    ImageButton mapLocateButton;
    RelativeLayout map_collapsed_container;
    LinearLayout warningactivityMapinfoContainer;
    boolean deviceIsLandscape;
    private GestureDetector gestureDetector;
    ListView weatherList;
    WeatherWarningAdapter weatherWarningAdapter;
    Context context;
    ActionBar actionBar;
    ScheduledExecutorService scheduledExecutorService;
    boolean hide_rain = false;
    boolean hide_admin = true;
    // TASK-65: vector layer switches. All default to on in WeatherSettings.
    boolean showWarningPolygons = true;
    boolean showExcludedAreas = true;
    boolean showRadarLayer = true;
    boolean showWindArcs = true;
    boolean showStationPin = true;
    boolean[] layerChoices = new boolean[]{true,true,true,true,true};
    AlertDialog layerDialog;
    boolean restoreLayerDialogOnResume = false;
    // Notification deep link: consumed once warnings/style are ready, restored on rotation.
    String pendingWarningIdentifier;
    boolean pendingWarningJumpDone = false;
    WeatherLocationManager weatherLocationManager;
    RelativeLayout gpsProgressHolder;
    RelativeLayout rainsliderSeekbarContainer;
    SeekBar rainsliderSeekbar;
    // TASK-70: timeline context and transport controls, all inside the SeekBar container.
    TextView rainSlideFrame;
    TextView rainSlideTimelineTime;
    View rainSlideTransportRow;
    ImageButton radarPlayButton;
    ImageButton radarStopButton;
    ImageButton radarStepBackButton;
    ImageButton radarStepForwardButton;

    Bundle zoomMapState = null;

    RadarMN2.MercatorProjectionTile mercatorProjectionTile;
    PlotPoint pinPoint;
    int[] precipitationAtPinPointArray = new int[24];
    ImageView precipitationAtPinPointColors;
    Bitmap precipitationAtPinPointBitmap;

    boolean forceWeatherUpdateFlag = false;

    boolean mapIsDrawn = false;

    static float MAP_PIXEL_WIDTH;
    static float MAP_PIXEL_HEIGHT;

    public final static String WEATHER_WARNINGS_UPDATE="WEATHER_WARNINGS_UPDATE";
    public final static String WEATHER_WARNINGS_UPDATE_RESULT="WEATHER_WARNINGS_UPDATE_RESULT";
    public final static String ACTION_RAINRADAR_UPDATE="ACTION_RAINRADAR_UPDATE";

    public final static String SIS_ZOOMMAPSTATEBUNDLE="ZOOMMAPSTATEBUNDLE";
    public final static String SIS_HIDERAIN="HIDERAIN";
    public final static String SIS_HIDEADMIN="HIDEADMIN";
    // TASK-65: popup/storage state survives rotation independently of the map preference.
    public final static String SIS_MAP_LAYER_WARNINGS="MAP_LAYER_WARNINGS";
    public final static String SIS_MAP_LAYER_EXCLUDED="MAP_LAYER_EXCLUDED";
    public final static String SIS_MAP_LAYER_RADAR="MAP_LAYER_RADAR";
    public final static String SIS_MAP_LAYER_WIND="MAP_LAYER_WIND";
    public final static String SIS_MAP_LAYER_PIN="MAP_LAYER_PIN";
    public final static String SIS_MAP_LAYER_DIALOG_OPEN="MAP_LAYER_DIALOG_OPEN";
    public final static String SIS_PENDING_WARNING_IDENTIFIER="PENDING_WARNING_IDENTIFIER";

    // TASK-53: stable ids for the vector warning GeoJSON sources and layers.
    private final static String WARNING_SOURCE_ID="twfg-warning-polygons";
    private final static String WARNING_FILL_LAYER_ID="twfg-warning-fill";
    private final static String WARNING_LINE_LAYER_ID="twfg-warning-outline";
    private final static String EXCLUDED_SOURCE_ID="twfg-excluded-polygons";
    private final static String EXCLUDED_FILL_LAYER_ID="twfg-excluded-fill";
    private final static String EXCLUDED_LINE_LAYER_ID="twfg-excluded-outline";
    // Excluded areas are painted dark and dashed so they read differently from the
    // coloured warning areas (which use the CAP AREA_COLOR via getWarningColor()).
    private final static String EXCLUDED_FILL_COLOR="#424242";
    private final static String EXCLUDED_LINE_COLOR="#000000";
    private final static Float WARNING_FILL_OPACITY=0.45f;
    private final static Float EXCLUDED_FILL_OPACITY=0.30f;
    // TASK-54: one georeferenced ImageSource for all cached radar frames.
    private final static String RADAR_SOURCE_ID="twfg-radar-imagesource";
    private final static String RADAR_LAYER_ID="twfg-radar-raster";
    private final static Float RADAR_RASTER_OPACITY=0.8f;
    // TASK-67: zoom step for the custom buttons and the locate camera target level.
    private final static double MAP_ZOOM_STEP=1d;
    private final static double MAP_LOCATE_ZOOM=11d;
    // TASK-64: the legend reads the same data the layers render, so a change of a
    // warning/radar colour in the map code is reflected in the legend automatically.
    private final static int[] MAP_LEGEND_WARNING_COLORS=WeatherWarning.Severity.colors;
    private final static int[] MAP_LEGEND_RADAR_COLORS=buildMapLegendRadarColors();

    private static int[] buildMapLegendRadarColors(){
        // RAINCOLORS[0] is the transparent clutter/no-data entry; the radar paints
        // indices 1..16, so show exactly those steps in the legend.
        int[] source=Radarmap.RAINCOLORS;
        int[] result=new int[source.length-1];
        System.arraycopy(source,1,result,0,result.length);
        return result;
    }

    /** TASK-87: a compact horizontal colour scale for the vector-mode timeline row. */
    private static Bitmap buildRadarLegendBitmap(){
        int width = Math.max(64,MAP_LEGEND_RADAR_COLORS.length*8);
        int height = 8;
        Bitmap bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        int tileWidth = width/MAP_LEGEND_RADAR_COLORS.length;
        for (int i=0; i<MAP_LEGEND_RADAR_COLORS.length; i++){
            for (int x=i*tileWidth; x<((i+1)*tileWidth); x++){
                for (int y=0; y<height; y++){
                    bitmap.setPixel(x,y,MAP_LEGEND_RADAR_COLORS[i]);
                }
            }
        }
        return bitmap;
    }

    public final static long CREDENTIALS_FADE_TIME_DELAY = 7000l;

    PopupWindow hintPopupWindow = null;

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (intent!=null){
                // update warning display if warnings have been updated
                if (WEATHER_WARNINGS_UPDATE.equals(intent.getAction())) {
                    weatherWarnings = WeatherWarnings.getCurrentWarnings(getApplicationContext(),true);
                    displayWarnings();
                    updateActionBarLabels();
                    hideProgressBar();
                }
                if (intent.hasExtra(WEATHER_WARNINGS_UPDATE_RESULT)){
                    // gets result if update was successful, currently not used
                    boolean updateResult = intent.getBooleanExtra(WEATHER_WARNINGS_UPDATE_RESULT,false);
                }
                if (MainActivity.MAINAPP_HIDE_PROGRESS.equals(intent.getAction())){
                    forceWeatherUpdateFlag = false;
                    hideProgressBar();
                }
            }
        }
    };

    // TASK-70: the frame timer thread and the UI (SeekBar/transport buttons) both
    // touch these flags, so they must be visible across threads.
    private volatile int nextRainSlide = 0;
    private int lastDrawnRainSlide = 0;
    private long rainSlidesStartTime = 0;
    private volatile boolean cancelRainSlides = false;
    private volatile boolean rainSlidesRunning = false;
    // Pause suspends the 750 ms loop without tearing down the thread; STOP cancels it.
    private volatile boolean rainSlidesPaused = false;
    private volatile boolean keepTimelineAfterStop = false;
    private boolean validSlideSetObtained = false;
    // TASK-87: playback is driven by the frames actually cached, never by the download
    // target (24). Volatile because the timer thread and the UI thread both read it.
    private volatile int usableRainFrames = 0;
    public final static int RAINSLIDEDELAY=750;

    /**
     * TASK-87: the number of usable frames is the length of the contiguous valid prefix
     * of the cache. A partial set is displayed honestly (its real length), and a gap in
     * the middle is never skipped over: frames after the gap are not advertised.
     */
    static int usableRadarFrameCount(Context context){
        if (context==null){
            return 0;
        }
        int count = 0;
        while ((count<APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE) &&
                APIReaders.RadarMNSetGeoserverRunnable.radarCacheFileValid(context,count)){
            count++;
        }
        return count;
    }

    private void refreshUsableRainFrames(){
        usableRainFrames = usableRadarFrameCount(context);
    }

    private boolean isNextRainSlide(){
        int frameCount = usableRainFrames;
        if (frameCount<=1){
            return false;
        }
        if (nextRainSlide==lastDrawnRainSlide+1){
            return true;
        }
        if (nextRainSlide==0){
            if (lastDrawnRainSlide==frameCount){
                return true;
            }
        }
        return false;
    }

    private boolean rainDrawLock = false;

    private final Runnable rainRadarRunnable = new Runnable() {
        @Override
        public void run() {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            // TASK-87: a single cached image is a still picture, not an animation.
            refreshUsableRainFrames();
            if (usableRainFrames<=1){
                rainSlidesRunning = false;
                runOnUiThread(new Runnable(){
                    @Override
                    public void run(){
                        refreshRadarTransportControls();
                    }
                });
                return;
            }
            nextRainSlide=0;
            rainSlidesRunning = true;
            // The icon must show PAUSE as soon as the timer actually runs, not only
            // when the (asynchronous) start runnable was posted.
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    refreshRadarTransportControls();
                }
            });
            // check if rain slider is visible and make it visible if necessary
            if (rainsliderSeekbarContainer!=null){
                if (rainsliderSeekbarContainer.getVisibility()!=View.VISIBLE){
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            rainsliderSeekbarContainer.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
            while (!cancelRainSlides){
                // TASK-70: pause keeps the timer thread alive but stops advancing frames.
                // A pending STOP is still observed on the next iteration.
                if (rainSlidesPaused){
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        // do nothing
                    }
                    continue;
                }
                long startTime = Calendar.getInstance().getTimeInMillis();
                if (nextRainSlide<usableRainFrames) {
                    rainDrawLock = true;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            drawRadarSlide(nextRainSlide);
                            nextRainSlide++;
                            rainDrawLock = false;
                        }
                    });
                } else {
                    nextRainSlide=0;
                }
                long stopTime = Calendar.getInstance().getTimeInMillis();
                long waitTime = RAINSLIDEDELAY - (stopTime-startTime);
                if (waitTime>0){
                    try {
                        Thread.sleep(waitTime);
                    } catch (InterruptedException e) {
                        // do nothing
                    }
                } else {
                    while (rainDrawLock){
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            // do nothing
                        }
                    }
                }
            }
            // TASK-70: a user-requested STOP leaves the timeline visible so single
            // frames can be stepped; automatic stops still hide it as before.
            if (rainsliderSeekbarContainer!=null){
                final boolean keepVisible = keepTimelineAfterStop;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!keepVisible){
                            rainsliderSeekbarContainer.setVisibility(View.INVISIBLE);
                        }
                        refreshRadarTransportControls();
                    }
                });
            }
            rainSlidesRunning = false;
            rainSlidesPaused = false;
            keepTimelineAfterStop = false;
            cancelRainSlides = false;
        }
    };

    private synchronized void startRainRadar(){
        // In vector mode the layer switch owns radar visibility; leave hide_rain to the
        // legacy static map so the two settings stay independent.
        refreshUsableRainFrames();
        if (usableRainFrames<=1){
            // TASK-87: no usable animation. Do not post a runnable that would sweep
            // 0..23 while the pixels stay on the only cached image.
            runOnUiThread(new Runnable(){
                @Override
                public void run(){
                    refreshRadarTransportControls();
                }
            });
            return;
        }
        if (radarEnabled()){
            if (!rainSlidesRunning){
                // TASK-70: a fresh run always starts in the playing state.
                rainSlidesPaused = false;
                keepTimelineAfterStop = false;
                Runnable start = new Runnable() {
                    @Override
                    public void run() {
                        // make slider visible before rain radar starts
                        if (rainsliderSeekbarContainer!=null){
                            updateRainSeekBar(0);
                            updateRainFrameText(0);
                            rainsliderSeekbarContainer.setVisibility(View.VISIBLE);
                        }
                        refreshRadarTransportControls();
                        new Thread(rainRadarRunnable).start();
                    }
                };
                if (useVectorMap){
                    // The static ImageView is hidden in vector mode; post on the UI thread
                    // instead of relying on a hidden view's handler.
                    runOnUiThread(start);
                } else {
                    // Legacy path: make sure not to start before views are created.
                    germany.post(start);
                }
            }
        }
    }

    APIReaders.RadarMNSetGeoserverRunnable radarMNSetGeoserverRunnable;

    View.OnTouchListener forwardRainSlidesOnTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            float position = motionEvent.getX()/ view.getWidth();
            nextRainSlide = frameForSeekBarProgress(Math.round(position*100f),usableRainFrames);
            return true;
        }
    };

    APIReaders.WeatherWarningsRunnable weatherWarningsUpdateRunnable;

    @Override
    public void onSaveInstanceState(Bundle state){
        if (mapZoomable != null){
            zoomMapState = mapZoomable.saveZoomViewState();
        }
        if (zoomMapState!=null){
            state.putBundle(SIS_ZOOMMAPSTATEBUNDLE,zoomMapState);
        }
        state.putBoolean(SIS_HIDERAIN,hide_rain);
        state.putBoolean(SIS_HIDEADMIN,hide_admin);
        state.putBoolean(SIS_MAP_LAYER_WARNINGS,showWarningPolygons);
        state.putBoolean(SIS_MAP_LAYER_EXCLUDED,showExcludedAreas);
        state.putBoolean(SIS_MAP_LAYER_RADAR,showRadarLayer);
        state.putBoolean(SIS_MAP_LAYER_WIND,showWindArcs);
        state.putBoolean(SIS_MAP_LAYER_PIN,showStationPin);
        state.putBoolean(SIS_MAP_LAYER_DIALOG_OPEN,layerDialog!=null && layerDialog.isShowing());
        state.putString(SIS_PENDING_WARNING_IDENTIFIER,pendingWarningIdentifier);
        // TASK-52: MapView needs the saved state to restore its camera/native map.
        if (useVectorMap && mapLibreMapView!=null){
            mapLibreMapView.onSaveInstanceState(state);
        }
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onRestoreInstanceState(Bundle state){
        super.onRestoreInstanceState(state);
        restoreLayerState(state);
        pendingWarningIdentifier = state.getString(SIS_PENDING_WARNING_IDENTIFIER,null);
        // Re-opening the dialog here would leak the pre-rotation window; do it in onResume.
        restoreLayerDialogOnResume = state.getBoolean(SIS_MAP_LAYER_DIALOG_OPEN,false);
    }

    /** Reads the persisted layer switches (or their rotated activity state) once per start. */
    private void restoreLayerState(Bundle state){
        showWarningPolygons = state!=null ? state.getBoolean(SIS_MAP_LAYER_WARNINGS,WeatherSettings.showMapLayerWarnings(context))
                : WeatherSettings.showMapLayerWarnings(context);
        showExcludedAreas = state!=null ? state.getBoolean(SIS_MAP_LAYER_EXCLUDED,WeatherSettings.showMapLayerExcluded(context))
                : WeatherSettings.showMapLayerExcluded(context);
        showRadarLayer = state!=null ? state.getBoolean(SIS_MAP_LAYER_RADAR,WeatherSettings.showMapLayerRadar(context))
                : WeatherSettings.showMapLayerRadar(context);
        showWindArcs = state!=null ? state.getBoolean(SIS_MAP_LAYER_WIND,WeatherSettings.showMapLayerWind(context))
                : WeatherSettings.showMapLayerWind(context);
        showStationPin = state!=null ? state.getBoolean(SIS_MAP_LAYER_PIN,WeatherSettings.showMapLayerPin(context))
                : WeatherSettings.showMapLayerPin(context);
        syncLayerChoices();
    }

    private void syncLayerChoices(){
        layerChoices = new boolean[]{showWarningPolygons,showExcludedAreas,showRadarLayer,showWindArcs,showStationPin};
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (useVectorMap && mapLibreMapView!=null){
            mapLibreMapView.onStart();
        }
    }

    @Override
    protected void onStop() {
        if (useVectorMap && mapLibreMapView!=null){
            mapLibreMapView.onStop();
        }
        super.onStop();
    }

    @Override
    public void onLowMemory() {
        if (useVectorMap && mapLibreMapView!=null){
            mapLibreMapView.onLowMemory();
        }
        super.onLowMemory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Saved state is delivered after onStart(), so re-open the layer popup here.
        if (restoreLayerDialogOnResume){
            restoreLayerDialogOnResume = false;
            showLayerSelector();
        }
        if (useVectorMap && mapLibreMapView!=null){
            mapLibreMapView.onResume();
        }
        // invalidate some map views so that they will be updated
        mapIsDrawn = false;
        // stop rain radar while processing warnings to avoid performance issues on older devices
        cancelRainSlides=true;
        registerForBroadcast();
        if (germany==null){
            germany = (ImageView) findViewById(R.id.warningactivity_map);
        }
        PrivateLog.log(getApplicationContext(),PrivateLog.WARNINGS,PrivateLog.INFO,"app resumed.");
        if (WeatherSettings.GPSAuto(context)){
            weatherLocationManager.checkLocation();
        }
        if (DataStorage.Updates.isSyncDue(context,WeatherSettings.Updates.Category.WARNINGS)){
            if (Weather.suitableNetworkAvailable(context)){
                PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.INFO,"Weather warnings are outdated, updating data.");
                scheduledExecutorService.execute(weatherWarningsUpdateRunnable);
            } else {
                PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.ERR,"Weather warnings need to be updated, but no suitable network connection found.");
            }
        } else {
            // displayWarnings() must be in scheduledExecutorService queue to make sure it is executed before
            // radarMNSetGeoserverRunnable (see below)
            scheduledExecutorService.execute(new Runnable() {
                @Override
                public void run() {
                    weatherWarnings = WeatherWarnings.getCurrentWarnings(getApplicationContext(),true);
                    PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.INFO,"Weather warnings are up to date, showing the data available.");
                    displayWarnings();
                }
            });
        }
        // at this point, do not cancel slides (anymore)
        cancelRainSlides = false;
        // TASK-54: the frames are downloaded once for both map modes. The vector path only
        // swaps already cached bitmaps into its ImageSource; its UI callbacks are guarded.
        scheduledExecutorService.execute(radarMNSetGeoserverRunnable);
    }

    @Override
    protected void onPause(){
        cancelRainSlides = true;
        if (useVectorMap && mapLibreMapView!=null){
            mapLibreMapView.onPause();
        }
        // save the map state if desired
        if (WeatherSettings.saveMapState(context)){
            PrivateLog.log(getApplicationContext(),PrivateLog.WARNINGS,PrivateLog.INFO,"Saving map state enabled.");
            if (mapZoomable!=null){
                zoomMapState = mapZoomable.saveZoomViewState();
                WeatherSettings.setZoomStateBundle(context,zoomMapState);
                PrivateLog.log(getApplicationContext(),PrivateLog.WARNINGS,PrivateLog.INFO,"Map zoom state saved.");
            }
        }
        unregisterReceiver(receiver);
        super.onPause();
        PrivateLog.log(getApplicationContext(),PrivateLog.WARNINGS,PrivateLog.INFO,"app paused.");
    }

    @Override
    protected void onDestroy() {
        if (layerDialog!=null){
            if (layerDialog.isShowing()){
                layerDialog.dismiss();
            }
            layerDialog = null;
        }
        if (useVectorMap && mapLibreMapView!=null){
            mapLibreMapView.onDestroy();
            mapLibreMapView = null;
        }
        mapLibreMap = null;
        vectorWarningStyleReady = false;
        super.onDestroy();
        if (hintPopupWindow!=null){
            if (hintPopupWindow.isShowing()){
                hintPopupWindow.dismiss();
                WeatherSettings.setHintCounter2(context,WeatherSettings.getHintCounter2(context)-1);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        PrivateLog.log(getApplicationContext(),PrivateLog.WARNINGS,PrivateLog.INFO,"WeatherWarningActivity started.");
        try {
            ThemePicker.SetTheme(this);
        } catch (Exception e){
            PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.INFO,"Error setting theme in WeatherWarnings activity.");
        }
        super.onCreate(savedInstanceState);
        context = getApplicationContext();
        restoreLayerState(savedInstanceState);
        // TASK-65: a notification tap carries the warning identifier. Defer the camera move
        // until both the warning list and the vector style are ready (see maybeJumpToPendingWarning).
        Intent launchIntent = getIntent();
        if ((launchIntent!=null) && (savedInstanceState==null)){
            String warningIdentifier = launchIntent.getStringExtra(WeatherSyncAdapter.EXTRA_WARNING_IDENTIFIER);
            if ((warningIdentifier!=null) && (!warningIdentifier.isEmpty())){
                pendingWarningIdentifier = warningIdentifier;
            }
        }
        useVectorMap = VectorMapConfig.isVectorMapEnabled(context);
        // MapLibre 13.8 refuses to inflate a MapView unless its singleton was initialised first.
        // Both map layouts contain the MapView, so this must happen in either mode.
        if (!MapLibre.hasInstance()){
            MapLibre.getInstance(context,"",WellKnownTileServer.MapLibre);
        }
        mercatorProjectionTile = RadarMN2.getRadarMapMercatorProjectionTile(context);
        mercatorProjectionTile.setScaleFactor(RadarMN2.getScaleFactor(context));
        // set to station, perhaps override with current location later
        ownLocation = WeatherSettings.getSetStationLocation(getApplicationContext());
        // calculate coordinates of user location on map
        pinPoint = getPlotPoint((float) ownLocation.longitude, (float) ownLocation.latitude);
        rainSlidesStartTime = WeatherSettings.getPrefRadarLastdatapoll(context);
        WeatherSettings.setRotationMode(this);
        setContentView(R.layout.activity_weatherwarning);
        // TASK-52: pick the vector basemap or the legacy static map once per activity start.
        applyMapMode();
        // TASK-67: re-attached on every creation, so rotation and style reloads keep the
        // zoom/locate buttons alive. The controls themselves are vector-only views.
        setupMapControls();
        if (useVectorMap){
            setupVectorMap(savedInstanceState);
        }
        registerForBroadcast();
        // try to restore zoom factor if it is available from the savedInstanceState
        if (savedInstanceState!=null){
            Bundle bundle = savedInstanceState.getBundle(SIS_ZOOMMAPSTATEBUNDLE);
            if (bundle!=null){
                zoomMapState = bundle;
            }
            hide_rain = savedInstanceState.getBoolean(SIS_HIDERAIN,!WeatherSettings.showRadarByDefault(getApplicationContext()));
            hide_admin = savedInstanceState.getBoolean(SIS_HIDEADMIN,!WeatherSettings.showAdminMapByDefault(getApplicationContext()));
        } else {
            if (WeatherSettings.saveMapState(context)){
                PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.INFO,"Restored map position.");
                zoomMapState = WeatherSettings.getZoomStateBundle(context);
            }
            hide_rain = !WeatherSettings.showRadarByDefault(getApplicationContext());
            hide_admin = !WeatherSettings.showAdminMapByDefault(getApplicationContext());
        }
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        // action bar layout
        actionBar = getActionBar();
        if (actionBar!=null){
            actionBar.setDisplayOptions(ActionBar.DISPLAY_SHOW_HOME|ActionBar.DISPLAY_HOME_AS_UP|ActionBar.DISPLAY_SHOW_TITLE);
        }
        mapcontainer = (RelativeLayout) findViewById(R.id.warningactivity_mapcontainer);
        map_collapsed_container = (RelativeLayout) findViewById(R.id.warningactivity_map_collapsed_container);
        warningactivity_map_collapsed = (ImageView) findViewById(R.id.warningactivity_map_collapsed);
        rainsliderSeekbarContainer = (RelativeLayout) findViewById(R.id.warningactivity_rainsliderSeekbarContainer);
        precipitationAtPinPointColors = (ImageView) findViewById(R.id.warningactivity_rainsliderColors);
        warningactivityMapinfoContainer = (LinearLayout) findViewById(R.id.warningactivity_mapinfo_container);
        if (warningactivity_map_collapsed!=null){
            warningactivity_map_collapsed.setImageResource(WeatherIcons.getIconResource(context,WeatherIcons.MAP_COLLAPSED));
        }
        // in layout w6600dp-land this element does not exist. This is the safest way to
        // limit collapse-function to portrait mode.
        if (map_collapsed_container!=null){
            deviceIsLandscape = false;
            map_collapsed_container.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent motionEvent) {
                    if (germany!=null){
                        showMap();
                    }
                    return true;
                }
            });
        } else {
            deviceIsLandscape = true;
        }
        rainSlideProgressBar = (ProgressBar) findViewById(R.id.warningactivity_rainslideprogressbar);
        rainSlideProgressBarText = (TextView) findViewById(R.id.warningactivity_rainslideprogressbartext);
        rainSlideTime = (TextView) findViewById(R.id.warningactivity_rainslidetime);
        // TASK-70: wire the timeline caption and the play/stop/step transport row.
        setupRainTimelineControls();
        /*
        rainSlideTime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                nextRainSlide=0;
            }
        });
         */
        rainDescription = (ImageView) findViewById(R.id.warningactivity_mapinfo);
        // rainDescription.setOnTouchListener(forwardRainSlidesOnTouchListener);
        gpsProgressHolder = (RelativeLayout) findViewById(R.id.gps_progress_holder);
        displayOsmNotice();
        radarMNSetGeoserverRunnable = new APIReaders.RadarMNSetGeoserverRunnable(getApplicationContext()){
            @Override
            public void onStart() {
                super.onStart();
                if (useVectorMap){
                    return;
                }
                validSlideSetObtained = false;
                showRainSlideProgressBar();
                updateRainSlideProgressBar(0);
            }

            @Override
            public void onProgress(long startTime, final int progress) {
                if (useVectorMap){
                    return;
                }
                if (progress==1){
                    rainSlidesStartTime = startTime;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            germany.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (!hide_rain){
                                        drawRadarSlide(0);
                                    }
                                }
                            });
                        }
                    });
                }
                if (progress<=APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE){
                    updateRainSlideProgressBar(progress);
                }
            }
            @Override
            public void onFinished(long startTime, boolean success){
                super.onFinished(startTime,success);
                rainSlidesStartTime = startTime;
                // TASK-87: the frames on disk, not success alone, define the timeline.
                refreshUsableRainFrames();
                runOnUiThread(new Runnable(){
                    @Override
                    public void run(){
                        updateSlideTimeText(0);
                        refreshRadarTransportControls();
                    }
                });
                if (useVectorMap){
                    // Vector radar uses the same cached frames and the same timer; only the
                    // drawing target differs (ImageSource.setImage instead of a bitmap).
                    validSlideSetObtained = success;
                    if (success){
                        startRainRadar();
                    }
                    return;
                }
                hideRainSlideProgressBar();
                if (success){
                    validSlideSetObtained = true;
                    if (precipitationAtPinPointColors!=null){
                        precipitationAtPinPointArray = getPrecipitationArrayAtPinPoint();
                        //precipitationAtPinPointBitmap = ForecastBitmap.CreatePrecipitationAtPinPointBitmap(context,precipitationAtPinPointArray,precipitationLabelFontSize);
                        // make sure drawing only happens after view hierarchy was created
                        germany.post(new Runnable() {
                            @Override
                            public void run() {
                                precipitationAtPinPointBitmap = ForecastBitmap.CreatePrecipitationAtPinPointBitmap(context,precipitationAtPinPointArray,rainsliderSeekbarContainer);
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        precipitationAtPinPointColors.setImageBitmap(precipitationAtPinPointBitmap);
                                    }
                                });
                            }
                        });
                    }
                    germany.post(new Runnable() {
                        @Override
                        public void run() {
                            startRainRadar();
                        }
                    });
                } else {
                    validSlideSetObtained = false;
                }
            }
        };
        weatherLocationManager = new WeatherLocationManager(context){
          @Override
          public void newLocation(Location location){
              super.newLocation(location);
              Weather.WeatherLocationFinder weatherLocationFinder = new Weather.WeatherLocationFinder(context,location){
                @Override
                public void newWeatherLocation(Weather.WeatherLocation weatherLocation){
                    ownLocation = weatherLocation;
                    displayWarnings();
                }
              };
              weatherLocationFinder.run();
          }
        };
        weatherWarningsUpdateRunnable = new APIReaders.WeatherWarningsRunnable(getApplicationContext()) {
            @Override
            public void onStart() {
                showProgressBar();
                super.onStart();
            }
            @Override
            public void onNegativeResult() {
                hideProgressBar();
                displayWarnings();
                PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.ERR,"Getting warnings failed.");
                super.onNegativeResult();
            }
            @Override
            public void onPositiveResult(ArrayList<WeatherWarning> warnings) {
                hideProgressBar();
                DataStorage.Updates.setLastUpdate(context,WeatherSettings.Updates.Category.WARNINGS,Calendar.getInstance().getTimeInMillis());
                PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.INFO,"Updated warnings: "+warnings.size()+" records.");
                weatherWarnings = warnings;
                for (int i=0; i<weatherWarnings.size(); i++){
                    weatherWarnings.get(i).initPolygons(context);
                }
                super.onPositiveResult(warnings);
                // finally do a sync of other parameters; if nothing is due, nothing will happen
                // warnings will be also ignored, because setLastUpdate was called.
                scheduledExecutorService.execute(new Runnable() {
                    @Override
                    public void run() {
                        ContentResolver.requestSync(MainActivity.getManualSyncRequest(context,WeatherSyncAdapter.UpdateFlags.FLAG_UPDATE_DEFAULT));
                    }
                });
                displayWarnings();
            }
        };
        WeatherSettings.saveGPSfixtime(context,0);
        getApplication().registerActivityLifecycleCallbacks(weatherLocationManager);
        weatherLocationManager.setView(gpsProgressHolder);
        weatherLocationManager.registerCancelButton((Button) findViewById(R.id.cancel_gps));
        popupHint();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu){
        MenuInflater mi = getMenuInflater();
        mi.inflate(R.menu.weatherwarning_activity,menu);
        // TASK-65: the legacy rain/admin filter belongs to the static map; the vector map
        // uses the dedicated per-layer selector instead.
        MenuItem layersItem = menu.findItem(R.id.map_layers);
        if (layersItem!=null){
            layersItem.setVisible(useVectorMap);
        }
        MenuItem rainItem = menu.findItem(R.id.hide_rain);
        if (rainItem!=null){
            rainItem.setVisible(!useVectorMap);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem mi) {
        int item_id = mi.getItemId();
        if (item_id == R.id.menu_refresh) {
            if (mapZoomable!=null){
                zoomMapState = mapZoomable.saveZoomViewState();
            }
            PrivateLog.log(this, PrivateLog.WARNINGS,PrivateLog.INFO, "starting update of weather warnings");
            SyncRequest syncRequest = MainActivity.getManualSyncRequest(context,WeatherSyncAdapter.UpdateFlags.FLAG_UPDATE_WARNINGS);
            ContentResolver.requestSync(syncRequest);
            forceWeatherUpdateFlag = true;
            // force update or rain radar if shown
            if (!hide_rain){
                    cancelRainSlides=true;
            }
            // TASK-66: a manual refresh must invalidate the radar cache as well. The
            // downloader reuses a complete/fresh set, so an injected QA frame would
            // otherwise survive every refresh. Clear first, then queue a clean fetch.
            RadarMN2.clearRadarCache(context);
            cancelRainSlides = true;
            if (radarMNSetGeoserverRunnable!=null){
                scheduledExecutorService.execute(radarMNSetGeoserverRunnable);
            }
            return true;
        }
        if (item_id==R.id.map_legend) {
            showMapLegend();
            return true;
        }
        if (item_id==R.id.map_layers) {
            showLayerSelector();
            return true;
        }
        if (item_id==R.id.hide_rain) {
            // The legacy filter is static-map only. In vector mode the layer popup owns
            // radar visibility, so a stale hide_rain value must not remove vector layers.
            if (useVectorMap){
                return true;
            }
            // invalidate some map views so that they will be drawn again once
            mapIsDrawn = false;
            if ((hide_rain) && (hide_admin)){
                hide_rain = false;
            } else
            if ((!hide_rain) && (hide_admin)){
                hide_admin = false;
            } else
            if (!hide_rain) {
                hide_rain = true;
            }
            else {
                hide_rain = true; hide_admin = true;
            }
            if (!hide_rain){
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        startRainRadar();
                    }
                });
            } else {
                if (rainSlidesRunning){
                    cancelRainSlides=true;
                }
                if (useVectorMap){
                    removeVectorRadarLayer();
                }
            }
            drawMapBitmap();
            return true;
        }
        return super.onOptionsItemSelected(mi);
    }

    /* ------------------------------------------------------------------
     * TASK-65: per-layer selector for the vector warning map. The five flags are
     * independent of the vector/static preference and of the legacy hide_rain flag.
     * ------------------------------------------------------------------ */

    private void showLayerSelector(){
        if (isFinishing()){
            return;
        }
        if ((layerDialog!=null) && layerDialog.isShowing()){
            layerDialog.dismiss();
        }
        syncLayerChoices();
        final boolean[] checked = layerChoices.clone();
        String[] labels = new String[]{
                getString(R.string.map_layer_warnings),getString(R.string.map_layer_excluded),
                getString(R.string.map_layer_radar),getString(R.string.map_layer_wind),
                getString(R.string.map_layer_pin)};
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.map_layers_title)
                .setMultiChoiceItems(labels,checked,new DialogInterface.OnMultiChoiceClickListener(){
                    @Override
                    public void onClick(DialogInterface dialogInterface,int which,boolean isChecked){
                        checked[which]=isChecked;
                        switch (which){
                            case 0:
                                showWarningPolygons=isChecked;
                                WeatherSettings.setMapLayerWarnings(context,isChecked);
                                break;
                            case 1:
                                showExcludedAreas=isChecked;
                                WeatherSettings.setMapLayerExcluded(context,isChecked);
                                break;
                            case 2:
                                showRadarLayer=isChecked;
                                WeatherSettings.setMapLayerRadar(context,isChecked);
                                break;
                            case 3:
                                showWindArcs=isChecked;
                                WeatherSettings.setMapLayerWind(context,isChecked);
                                break;
                            case 4:
                                showStationPin=isChecked;
                                WeatherSettings.setMapLayerPin(context,isChecked);
                                break;
                            default:
                                break;
                        }
                        applyLayerSelection();
                    }
                })
                .setPositiveButton(R.string.alertdialog_ok,null)
                .create();
        final AlertDialog createdDialog = dialog;
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                if (layerDialog==createdDialog){
                    layerDialog=null;
                }
            }
        });
        layerDialog = dialog;
        dialog.show();
    }

    /** Re-applies all layer switches to whichever map is currently active. */
    private void applyLayerSelection(){
        syncLayerChoices();
        if (useVectorMap){
            applyVectorWarningLayers();
            if (showRadarLayer){
                applyVectorRadarFrame(nextRainSlide);
            } else {
                removeVectorRadarLayer();
            }
            applyVectorOverlays();
        } else {
            // Only the radar switch has a legacy static-map counterpart.
            hide_rain = !showRadarLayer;
            mapIsDrawn = false;
            drawMapBitmap();
        }
    }

    private boolean radarEnabled(){
        return useVectorMap ? showRadarLayer : !hide_rain;
    }

    /* ------------------------------------------------------------------
     * TASK-64: legend for the vector warning map. The dialog is built from
     * themed views and the same colour arrays/constants the layers use, so
     * no new image assets and no hard-coded duplicate colour values.
     * ------------------------------------------------------------------ */

    private int legendDp(float value){
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,value,
                getResources().getDisplayMetrics()));
    }

    private void showMapLegend(){
        if (isFinishing()){
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = legendDp(16);
        root.setPadding(padding,padding,padding,padding);
        int textColor = ThemePicker.getWidgetTextColor(context);
        float textSize = getResources().getDimension(R.dimen.fcmain_textsize_medium);
        float headerSize = getResources().getDimension(R.dimen.fcmain_textsize_xl_half);

        // TASK-65: only describe layers that are actually rendered. The static map always
        // paints all of them, so the full legend is shown there.
        if (!useVectorMap || showWarningPolygons){
        addLegendHeader(root,R.string.map_legend_warning_levels,textColor,headerSize,false);
        addLegendNote(root,R.string.map_legend_warning_fill,textColor,textSize);
        int[] warningLabels = new int[]{
                R.string.map_legend_minor,R.string.map_legend_moderate,R.string.map_legend_severe,
                R.string.map_legend_extreme,R.string.map_legend_heat,R.string.map_legend_uv};
        for (int i=0; i<MAP_LEGEND_WARNING_COLORS.length && i<warningLabels.length; i++){
            addLegendSwatchRow(root,MAP_LEGEND_WARNING_COLORS[i],getString(warningLabels[i]),
                    textColor,textSize,false);
        }
        }

        if (!useVectorMap || showRadarLayer){
        addLegendHeader(root,R.string.map_legend_radar,textColor,headerSize,true);
        LinearLayout radarRow = new LinearLayout(this);
        radarRow.setOrientation(LinearLayout.HORIZONTAL);
        radarRow.setContentDescription(getString(R.string.map_legend_radar_scale));
        for (int color : MAP_LEGEND_RADAR_COLORS){
            View step = new View(this);
            step.setBackgroundColor(color);
            step.setLayoutParams(new LinearLayout.LayoutParams(0,legendDp(28),1f));
            step.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            radarRow.addView(step);
        }
        root.addView(radarRow,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));

        // The four words are the labels of the legacy radar legend; keep their positions
        // relative to the colour steps instead of inventing new intensity wording.
        int[] radarLabels = new int[]{
                R.string.radar_rain1,R.string.radar_rain2,R.string.radar_rain3,R.string.radar_rain4};
        LinearLabelRow labelRow = new LinearLabelRow(this,textColor,textSize);
        for (int label : radarLabels){
            labelRow.addLabel(getString(label));
        }
        root.addView(labelRow,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (!useVectorMap || showExcludedAreas){
            addLegendSwatchRow(root,Color.parseColor(EXCLUDED_FILL_COLOR),
                getString(R.string.map_legend_excluded),textColor,textSize,true);
        }
        if (!useVectorMap || showStationPin || showWindArcs){
        addLegendHeader(root,R.string.map_legend_others,textColor,headerSize,true);
        }
        if (!useVectorMap || showStationPin){
            addLegendImageRow(root,R.mipmap.pin,getString(R.string.map_legend_pin),textColor,textSize);
        }
        if (!useVectorMap || showWindArcs){
            addLegendImageRow(root,-1,getString(R.string.map_legend_wind),textColor,textSize);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(root,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.map_legend_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok,null)
                .create();
        // A dialog-hosted custom view otherwise measures to a narrow wrap_content column,
        // which makes the radar labels wrap even on wide devices. Give it a readable width
        // and cap its height so the ScrollView works in landscape and at 600dp.
        DisplayMetrics metrics = new DisplayMetrics();
        ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getMetrics(metrics);
        final int maxHeight = metrics.heightPixels - legendDp(96);
        final int dialogWidth = Math.min(metrics.widthPixels - legendDp(32),legendDp(380));
        final Window dialogWindow = dialog.getWindow();
        // AlertDialog re-applies its own window attributes in onStart(); onShow runs after
        // that, so sizing here is reliable in every orientation.
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface dialogInterface) {
                WindowManager.LayoutParams params = dialogWindow.getAttributes();
                params.width = dialogWidth;
                dialogWindow.setAttributes(params);
                root.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()){
                            return;
                        }
                        // Measure at the final width; the extra space covers the dialog
                        // title and the OK button row.
                        root.measure(View.MeasureSpec.makeMeasureSpec(dialogWidth,View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                        int desired = root.getMeasuredHeight()+legendDp(100);
                        WindowManager.LayoutParams heightParams = dialogWindow.getAttributes();
                        heightParams.height = Math.min(desired,maxHeight);
                        dialogWindow.setAttributes(heightParams);
                    }
                });
            }
        });
        dialog.show();
        Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (ok!=null){
            // 48dp minimum touch target for the only interactive control in the dialog.
            ok.setMinHeight(legendDp(48));
            ok.setContentDescription(getString(android.R.string.ok));
        }
    }

    private void addLegendHeader(LinearLayout parent,int resId,int textColor,float textSize,boolean spaced){
        TextView header = new TextView(this);
        header.setText(resId);
        header.setTextColor(textColor);
        header.setTextSize(TypedValue.COMPLEX_UNIT_PX,textSize);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = legendDp(spaced ? 14 : 0);
        params.bottomMargin = legendDp(4);
        parent.addView(header,params);
    }

    private void addLegendNote(LinearLayout parent,int resId,int textColor,float textSize){
        TextView note = new TextView(this);
        note.setText(resId);
        note.setTextColor(textColor);
        note.setTextSize(TypedValue.COMPLEX_UNIT_PX,textSize);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = legendDp(6);
        parent.addView(note,params);
    }

    private void addLegendSwatchRow(LinearLayout parent,int color,String label,int textColor,
                                    float textSize,boolean dashed){
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View swatch = new View(this);
        swatch.setBackground(makeLegendSwatch(color,dashed));
        swatch.setContentDescription(label);
        int swatchHeight = legendDp(22);
        row.addView(swatch,new LinearLayout.LayoutParams(legendDp(36),swatchHeight));

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(textColor);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX,textSize);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);
        textParams.setMargins(legendDp(8),0,0,0);
        row.addView(text,textParams);

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0,legendDp(2),0,legendDp(2));
        parent.addView(row,rowParams);
    }

    /** Fill uses the layer opacity; the outline uses the full layer colour. */
    private Drawable makeLegendSwatch(int color,boolean dashed){
        float opacity = dashed ? EXCLUDED_FILL_OPACITY : WARNING_FILL_OPACITY;
        // MapLibre renders the polygons over the light OpenFreeMap basemap, not over the
        // dialog. Use the same light underlay so 45%/30% fills keep their map colours in
        // both light and dark app themes.
        ShapeDrawable underlay = new ShapeDrawable(new RectShape());
        underlay.getPaint().setColor(0xfffafafa);
        underlay.getPaint().setStyle(Paint.Style.FILL);

        int fill = Color.argb(Math.round(opacity*255f),Color.red(color),Color.green(color),Color.blue(color));
        ShapeDrawable fillDrawable = new ShapeDrawable(new RectShape());
        fillDrawable.getPaint().setColor(fill);
        fillDrawable.getPaint().setStyle(Paint.Style.FILL);

        ShapeDrawable outlineDrawable = new ShapeDrawable(new RectShape());
        outlineDrawable.getPaint().setColor(dashed ? Color.parseColor(EXCLUDED_LINE_COLOR) : color);
        outlineDrawable.getPaint().setStyle(Paint.Style.STROKE);
        float stroke = legendDp(2);
        outlineDrawable.getPaint().setStrokeWidth(stroke);
        if (dashed){
            // Same 2:1 dash rhythm as the excluded layer's lineDasharray {2f,1f}.
            outlineDrawable.getPaint().setPathEffect(new DashPathEffect(
                    new float[]{legendDp(6),legendDp(3)},0f));
        }
        LayerDrawable result = new LayerDrawable(new Drawable[]{underlay,fillDrawable,outlineDrawable});
        int inset = Math.round(stroke/2f);
        result.setLayerInset(2,inset,inset,inset,inset);
        return result;
    }

    private void addLegendImageRow(LinearLayout parent,int resource,String label,int textColor,float textSize){
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (resource>=0){
            ImageView pin = new ImageView(this);
            pin.setImageResource(resource);
            pin.setScaleType(ImageView.ScaleType.FIT_CENTER);
            // The real pin is red/black. A light patch keeps it visible in dark themes.
            pin.setBackgroundColor(0xffe8e8e8);
            row.addView(pin,new LinearLayout.LayoutParams(legendDp(32),legendDp(32)));
            pin.setContentDescription(label);
        } else {
            // Wind arcs are black on the light basemap; draw them on a light patch too.
            View glyph = new View(this);
            glyph.setContentDescription(label);
            glyph.setBackground(new LegendWindDrawable(legendDp(2)));
            row.addView(glyph,new LinearLayout.LayoutParams(legendDp(32),legendDp(32)));
        }

        TextView text = new TextView(this);
        text.setText(label);
        text.setTextColor(textColor);
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX,textSize);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);
        textParams.setMargins(legendDp(8),0,0,0);
        row.addView(text,textParams);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0,legendDp(2),0,legendDp(2));
        parent.addView(row,rowParams);
    }

    private static class LinearLabelRow extends LinearLayout{
        LinearLabelRow(Context context,int textColor,float textSize){
            super(context);
            setOrientation(LinearLayout.HORIZONTAL);
            for (int i=0; i<4; i++){
                TextView label = new TextView(context);
                label.setTextColor(textColor);
                label.setTextSize(TypedValue.COMPLEX_UNIT_PX,textSize);
                label.setGravity(Gravity.CENTER);
                addView(label,new LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
            }
        }
        void addLabel(String text){
            for (int i=0; i<getChildCount(); i++){
                TextView label = (TextView) getChildAt(i);
                if (label.getText().length()==0){
                    label.setText(text);
                    return;
                }
            }
        }
    }

    /** Small black wind path with distance arcs, matching the black vector polylines. */
    private static class LegendWindDrawable extends Drawable{
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        LegendWindDrawable(float strokeWidth){
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokeWidth);
        }

        @Override
        public void draw(Canvas canvas){
            Rect bounds = getBounds();
            canvas.drawColor(0xffe8e8e8);
            float x0 = bounds.left+bounds.width()*0.15f;
            float x1 = bounds.right-bounds.width()*0.15f;
            float y0 = bounds.top+bounds.height()*0.68f;
            float y1 = bounds.top+bounds.height()*0.36f;
            Path path = new Path();
            path.moveTo(x0,y0);
            path.quadTo(bounds.left+bounds.width()*0.58f,y1-bounds.height()*0.12f,x1,y1);
            canvas.drawPath(path,paint);
            canvas.drawLine(x1,y1,x1-bounds.width()*0.13f,y1+bounds.height()*0.02f,paint);
            canvas.drawLine(x1,y1,x1-bounds.width()*0.10f,y1+bounds.height()*0.12f,paint);
            canvas.drawLine(x0-bounds.width()*0.05f,bounds.top+bounds.height()*0.28f,
                    x0+bounds.width()*0.12f,bounds.top+bounds.height()*0.22f,paint);
            canvas.drawLine(x0+bounds.width()*0.05f,bounds.top+bounds.height()*0.44f,
                    x0+bounds.width()*0.22f,bounds.top+bounds.height()*0.38f,paint);
        }

        @Override
        public void setAlpha(int alpha){
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter){
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity(){
            return PixelFormat.TRANSLUCENT;
        }
    }

    public void updateActionBarLabels(){
        final SimpleDateFormat simpleDateFormat = Weather.getSimpleDateFormat(Weather.SimpleDateFormats.DATEYEARTIME);
        String update = simpleDateFormat.format(DataStorage.Updates.getLastUpdate(context,WeatherSettings.Updates.Category.WARNINGS));
        if (actionBar==null){
            return;
        }
        if (weatherWarnings!=null){
            actionBar.setSubtitle(update+" ("+weatherWarnings.size()+")");
        } else {
            actionBar.setSubtitle(getApplicationContext().getResources().getString(R.string.warnings_update_fail));
        }
    }

    private void displayWarnings(){
        // getCurrentWarnings() can return null on a database error. Normalise once so that
        // neither the adapter nor the map can dereference a null list (issue #285).
        weatherWarnings = WarningUiDataGuard.sanitized(weatherWarnings);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateActionBarLabels();
            }
        });
        TextView noWarnings = (TextView) findViewById(R.id.warningactivity_no_warnings);
        if (noWarnings!=null){
            if (weatherWarnings.size()==0){
                noWarnings.setVisibility(View.VISIBLE);
            } else {
                noWarnings.setVisibility(View.GONE);
            }
        }
        TextView warningsDeprecated = (TextView) findViewById(R.id.warningactivity_warnings_deprecated);
        if (warningsDeprecated!=null){
            if (DataStorage.Updates.isSyncDue(context,WeatherSettings.Updates.Category.WARNINGS)){
                warningsDeprecated.setVisibility(View.VISIBLE);
            } else {
                warningsDeprecated.setVisibility(View.GONE);
            }
        }
        WeatherWarnings.getWarningsForLocationRunnable getWarningsForLocationRunnable = new WeatherWarnings.getWarningsForLocationRunnable(getApplicationContext(),null,null) {
            @Override
            public void onResult(ArrayList<WeatherWarning> result) {
                localWarnings = WarningUiDataGuard.sanitized(result);
                final ArrayList<WeatherWarning> safeWarnings = WarningUiDataGuard.sanitized(weatherWarnings);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        weatherList = (ListView) findViewById(R.id.warningactivity_listview);
                        weatherWarningAdapter = new WeatherWarningAdapter(getBaseContext(),safeWarnings,scheduledExecutorService);
                        weatherWarningAdapter.setLocalWarnings(localWarnings);
                        // TASK-65: inverse of map-tap -> list jump. Row clicks (including the
                        // expandable area text) are forwarded by the adapter so one callback
                        // highlights the row and moves the vector camera to its polygon bounds.
                        weatherWarningAdapter.setWarningClickListener(new WeatherWarningAdapter.OnWarningClickListener() {
                            @Override
                            public void onWarningClick(WeatherWarning warning){
                                onWarningSelected(warning,-1);
                            }
                        });
                        if (weatherList!=null){
                            weatherList.setAdapter(weatherWarningAdapter);
                            weatherList.setSelection(WarningUiDataGuard.firstWarningPosition(safeWarnings,localWarnings));
                            weatherList.invalidate();
                        }
                        maybeJumpToPendingWarning();
                    }
                });
            }
        };
        scheduledExecutorService.execute(getWarningsForLocationRunnable);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (useVectorMap){
                    // Same warning data as the legacy path, but rendered as GeoJSON layers.
                    applyVectorWarningLayers();
                    // TASK-55: refresh pin/wind when the station location changes.
                    applyVectorOverlays();
                } else {
                    displayMap();
                }
            }
        });
    }

    public static class PlotPoint{
        float x;
        float y;
    }

    public PlotPoint getPlotPoint(float lon, float lat){
        PlotPoint plotPoint = new PlotPoint();
        plotPoint.x = (float) mercatorProjectionTile.getXPixel(lon);
        plotPoint.y = (float) mercatorProjectionTile.getYPixel(lat);
        return plotPoint;
    }

    private float getXGeo(PlotPoint plotPoint){
        float xCoord = (float) mercatorProjectionTile.getXCoord(plotPoint.x);
        return xCoord;
    }

    private float getYGeo(PlotPoint plotPoint){
        float yCoord = (float) mercatorProjectionTile.getYCoord(plotPoint.y);
        return yCoord;
    }

    private void drawStrokedText(Canvas canvas, String text, float x, float y, Paint paint){
        Paint strokePaint = new Paint();
        strokePaint.setColor(Color.BLACK);
        strokePaint.setStyle(Paint.Style.FILL_AND_STROKE);
        strokePaint.setTypeface(Typeface.DEFAULT);
        strokePaint.setTextSize(paint.getTextSize());
        strokePaint.setAntiAlias(true);
        int shiftX = Math.max(2,germany.getWidth()/RadarMN2.getFixedRadarMapWidth(context));
        int shiftY = Math.max(2,germany.getHeight()/RadarMN2.getFixedRadarMapHeight(context));
        canvas.drawText(text,x-shiftX,y,strokePaint);
        canvas.drawText(text,x+shiftX,y,strokePaint);
        canvas.drawText(text,x,y-shiftY,strokePaint);
        canvas.drawText(text,x,y+shiftY,strokePaint);
        canvas.drawText(text,x,y,paint);
    }

    public void updateSlideTimeText(){
        updateSlideTimeText(nextRainSlide);
    }

    /** TASK-87: the time label always describes the frame that was actually drawn. */
    public void updateSlideTimeText(final int frame){
        if (rainSlideTime==null && rainSlideTimelineTime==null){
            return;
        }
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        Date rainSlideDate = new Date(rainSlidesStartTime+frame*APIReaders.RadarMNSetGeoserverRunnable.TIMESTEP_5MINUTES);
        String radartime = simpleDateFormat.format(rainSlideDate);
        int textColor;
        if (usableRainFrames<=1){
            radartime = getString(R.string.radar_timeline_unavailable);
            textColor = resolveThemeColor(R.attr.colorAccentYellow);
        } else if (validSlideSetObtained) {
            // TASK-70: theme attributes instead of fixed white/yellow so the caption
            // stays readable in light, dark and solarized themes.
            textColor = resolveThemeColor(R.attr.colorText);
            if (Calendar.getInstance().getTimeInMillis() > rainSlidesStartTime + 1000*60*60*1.5f){
                textColor = resolveThemeColor(R.attr.colorAccentOrange);
            }
        } else {
            textColor = resolveThemeColor(R.attr.colorAccentYellow);
        }
        // Legacy mapinfo label and the vector-mode timeline label must describe the
        // same frame; either one may be absent from the active layout.
        if (rainSlideTime!=null){
            rainSlideTime.setTextColor(textColor);
            rainSlideTime.setText(radartime);
        }
        if (rainSlideTimelineTime!=null){
            rainSlideTimelineTime.setTextColor(textColor);
            rainSlideTimelineTime.setText(radartime);
        }
    }

    private int resolveThemeColor(int attribute){
        TypedValue value = new TypedValue();
        if (getTheme().resolveAttribute(attribute,value,true)){
            return value.data;
        }
        return Color.WHITE;
    }

    @SuppressLint("NewApi")
    private void showRainDescription(){
        // Determine the height of the info bar
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics displayMetrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);
        int infoBarHeight = displayMetrics.heightPixels/12;
        int infoBarWidth  = Math.round(displayMetrics.widthPixels*(8f/20f)*(14f/20f));
        if (!deviceIsLandscape){
            infoBarWidth = Math.round(displayMetrics.widthPixels*(14f/20f));
            infoBarHeight = Math.round(displayMetrics.heightPixels*(1/28f)); // less than 30 to account for action bar & system elements at top of screen
        }
        // this is the result bitmap holding the color bar and the text legend
        Bitmap infoBitmap=Bitmap.createBitmap(Math.round(infoBarWidth),Math.round(infoBarHeight), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(infoBitmap);
        // this is the color bar, holding 50% of the space
        // original bitmap resolution of the info bar is 824x34 pixels
        float targetHeightColorBar   = infoBarHeight/2f;
        float targetWidthColorBar    = infoBarWidth;
        Bitmap radarinfobarResourceBitmap;
        radarinfobarResourceBitmap = Bitmap.createScaledBitmap(BitmapFactory.decodeResource(getResources(),WeatherIcons.getIconResource(getApplicationContext(),WeatherIcons.RADARINFOBAR)),Math.round(targetWidthColorBar),Math.round(targetHeightColorBar),true);
        Paint rpaint = new Paint();
        rpaint.setStyle(Paint.Style.FILL);
        canvas.drawBitmap(radarinfobarResourceBitmap,0,infoBitmap.getHeight()-radarinfobarResourceBitmap.getHeight(),rpaint);
        Paint radarTextPaint = new Paint();
        radarTextPaint.setTypeface(Typeface.DEFAULT);
        radarTextPaint.setStyle(Paint.Style.FILL_AND_STROKE);
        radarTextPaint.setAntiAlias(true);
        radarTextPaint.setFakeBoldText(true);
        int textsize = infoBarHeight/2;
        radarTextPaint.setTextSize(textsize);
        // fix the textsize to prevent overlapping of labels if the total width available is small
        String rainRadarLabelText1 = getResources().getString(R.string.radar_rain1);
        String rainRadarLabelText2 = getResources().getString(R.string.radar_rain2);
        String rainRadarLabelText3 = getResources().getString(R.string.radar_rain3);
        String rainRadarLabelText4 = getResources().getString(R.string.radar_rain4);
        int widestRadarLabel = 1; String widestRadarLabelText = rainRadarLabelText1;
        if (radarTextPaint.measureText(rainRadarLabelText2)>radarTextPaint.measureText(rainRadarLabelText1)){
            widestRadarLabel = 2; widestRadarLabelText = rainRadarLabelText2;
        }
        if (radarTextPaint.measureText(rainRadarLabelText3)>radarTextPaint.measureText(widestRadarLabelText)){
            widestRadarLabel = 3; widestRadarLabelText = rainRadarLabelText3;
        }
        if (radarTextPaint.measureText(rainRadarLabelText4)>radarTextPaint.measureText(widestRadarLabelText)){
            widestRadarLabel = 4; widestRadarLabelText = rainRadarLabelText4;
        }
        int widestLabelWidth = Math.round(radarTextPaint.measureText(widestRadarLabelText));
        while ((textsize>6) && (infoBarWidth-(radarTextPaint.measureText(widestRadarLabelText)*6)<1)){
            textsize--;
            radarTextPaint.setTextSize(textsize);
        }
        radarTextPaint.setColor(Color.WHITE);
        float ff=1.1f;
        radarTextPaint.setColor(Radarmap.RAINCOLORS[2]);
        drawStrokedText(canvas,getResources().getString(R.string.radar_rain1),infoBarWidth*0.1f,infoBitmap.getHeight()-radarinfobarResourceBitmap.getHeight()*ff,radarTextPaint);
        radarTextPaint.setColor(Radarmap.RAINCOLORS[7]);
        drawStrokedText(canvas,getResources().getString(R.string.radar_rain2),infoBarWidth*0.3f,infoBitmap.getHeight()-radarinfobarResourceBitmap.getHeight(),radarTextPaint);
        radarTextPaint.setColor(Radarmap.RAINCOLORS[11]);
        drawStrokedText(canvas,getResources().getString(R.string.radar_rain3),infoBarWidth*0.6f,infoBitmap.getHeight()-radarinfobarResourceBitmap.getHeight(),radarTextPaint);
        radarTextPaint.setColor(Radarmap.RAINCOLORS[16]);
        drawStrokedText(canvas,getResources().getString(R.string.radar_rain4),infoBarWidth*0.8f,infoBitmap.getHeight()-radarinfobarResourceBitmap.getHeight(),radarTextPaint);
        if (rainDescription!=null){
            rainDescription.setImageBitmap(infoBitmap);
        }
    }

    private void clearRainDescription(){
        if (rainDescription==null){
            rainDescription = (ImageView) findViewById(R.id.warningactivity_mapinfo);
        }
        if (rainDescription!=null){
            rainDescription.setImageDrawable(null);
        }
    }

    private Bitmap loadBitmapMap(int res_id){
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        Bitmap source = BitmapFactory.decodeResource(getResources(),res_id);
        if (source==null){
            return null;
        }
        return Bitmap.createScaledBitmap(source,RadarMN2.getFixedRadarMapWidth(context),RadarMN2.getFixedRadarMapHeight(context),false);
    }

    private void showRainSlideProgressBar(){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                rainSlideProgressBar.setVisibility(View.VISIBLE);
                rainSlideProgressBar.invalidate();
                rainSlideProgressBarText.setVisibility(View.VISIBLE);
                rainSlideProgressBarText.invalidate();
            }
        });
    }

    private void hideRainSlideProgressBar(){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                rainSlideProgressBar.setVisibility(View.INVISIBLE);
                rainSlideProgressBar.invalidate();
                rainSlideProgressBarText.setVisibility(View.INVISIBLE);
                rainSlideProgressBarText.invalidate();
            }
        });
    }

    private void updateRainSlideProgressBar(final int progress){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                rainSlideProgressBar.setProgress(progress);
                rainSlideProgressBar.invalidate();
                rainSlideProgressBarText.setText(Math.round((float) progress/(float) (APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE-1)*100f)+"%");
                rainSlideProgressBarText.invalidate();
            }
        });
    }

    private final static int SEEKBAR_MAX = 100;

    /** TASK-87: place each cached frame in its own equal slice of the 0..100 bar. */
    static int seekBarProgressForFrame(int frame, int frameCount){
        if (frameCount<=0){
            return 0;
        }
        int index = Math.max(0,Math.min(frame,frameCount-1));
        int progress = Math.round(((index+0.5f)/frameCount)*SEEKBAR_MAX);
        return Math.max(0,Math.min(SEEKBAR_MAX,progress));
    }

    /** Inverse of seekBarProgressForFrame(); never wraps the last slice back to frame 0. */
    static int frameForSeekBarProgress(int progress, int frameCount){
        if (frameCount<=0){
            return 0;
        }
        int clamped = Math.max(0,Math.min(progress,SEEKBAR_MAX));
        int frame = (int) (((float) clamped*frameCount)/SEEKBAR_MAX);
        return Math.max(0,Math.min(frame,frameCount-1));
    }

    private int getSeekBarPosition(int count){
        return seekBarProgressForFrame(count,usableRainFrames);
    }

    private void updateRainSeekBar(final int count){
        if (rainsliderSeekbar==null) {
            rainsliderSeekbar = (SeekBar) findViewById(R.id.warningactivity_rainsliderSeekbar);
            if (rainsliderSeekbar!=null){
                rainsliderSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar seekBar, int i, boolean fromUser) {
                        if (fromUser && (usableRainFrames>1)){
                            // TASK-87: map the drag into the real cache length so the last
                            // slice cannot wrap back to frame 0.
                            nextRainSlide = frameForSeekBarProgress(i,usableRainFrames);
                            if (useVectorMap){
                                // Swap the cached bitmap immediately; no network request here.
                                applyVectorRadarFrame(nextRainSlide);
                            } else if ((!rainSlidesRunning) || (rainSlidesPaused)){
                                // TASK-70: while paused/stopped the legacy timer will not
                                // repaint, so show the dragged frame through the existing path.
                                drawRadarSlide(nextRainSlide);
                            }
                            // Both captions describe the same frame as the slider.
                            updateRainFrameText(nextRainSlide);
                            updateSlideTimeText(nextRainSlide);
                        }
                    }
                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }
                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                });
            }
        }
        if (rainsliderSeekbar!=null){
            int position = getSeekBarPosition(count);
            rainsliderSeekbar.setProgress(position);
        }
    }

    /* ------------------------------------------------------------------
     * TASK-70: compact radar transport controls. All frame changes go through
     * drawRadarSlide(), i.e. the existing applyVectorRadarFrame() / legacy bitmap
     * paths. No new download is started from this UI.
     * ------------------------------------------------------------------ */

    private void setupRainTimelineControls(){
        refreshUsableRainFrames();
        rainSlideFrame = (TextView) findViewById(R.id.warningactivity_rainslideframe);
        rainSlideTimelineTime = (TextView) findViewById(R.id.warningactivity_rainslidetimeline_time);
        rainSlideTransportRow = findViewById(R.id.warningactivity_rainslidetransport);
        // Vector mode never builds the legacy precipitation-at-pin-point strip. Give the
        // middle of the caption row the same radar colour scale the legend uses.
        if ((useVectorMap) && (precipitationAtPinPointColors!=null) &&
                (precipitationAtPinPointColors.getDrawable()==null)){
            precipitationAtPinPointColors.setImageBitmap(buildRadarLegendBitmap());
        }
        radarPlayButton = (ImageButton) findViewById(R.id.warningactivity_radar_play);
        radarStopButton = (ImageButton) findViewById(R.id.warningactivity_radar_stop);
        radarStepBackButton = (ImageButton) findViewById(R.id.warningactivity_radar_step_back);
        radarStepForwardButton = (ImageButton) findViewById(R.id.warningactivity_radar_step_forward);
        if (radarPlayButton!=null){
            radarPlayButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view){
                    toggleRainSlides();
                }
            });
        }
        if (radarStopButton!=null){
            radarStopButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view){
                    stopAndResetRainSlides();
                }
            });
        }
        if (radarStepBackButton!=null){
            radarStepBackButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view){
                    stepRainSlide(-1);
                }
            });
        }
        if (radarStepForwardButton!=null){
            radarStepForwardButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view){
                    stepRainSlide(1);
                }
            });
        }
        refreshRadarTransportControls();
    }

    /** PLAY starts the existing timer; while it runs the same button becomes PAUSE. */
    private void toggleRainSlides(){
        if (usableRainFrames<=1){
            refreshRadarTransportControls();
            return;
        }
        if (!rainSlidesRunning){
            startRainRadar();
        } else {
            rainSlidesPaused = !rainSlidesPaused;
            refreshRadarTransportControls();
        }
    }

    /** STOP cancels the timer and leaves frame 0 on screen with the timeline visible. */
    private void stopAndResetRainSlides(){
        if (rainSlidesRunning){
            keepTimelineAfterStop = true;
            rainSlidesPaused = false;
            cancelRainSlides = true;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run(){
                if (usableRainFrames>1){
                    jumpToRainSlide(0);
                } else {
                    updateRainFrameText(0);
                    updateSlideTimeText(0);
                    updateRainSeekBar(0);
                }
                refreshRadarTransportControls();
            }
        });
    }

    /** A single frame step always pauses first so the timer cannot overwrite it. */
    private void stepRainSlide(final int delta){
        if (usableRainFrames<=1){
            refreshRadarTransportControls();
            return;
        }
        if (rainSlidesRunning){
            rainSlidesPaused = true;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run(){
                jumpToRainSlide(nextRainSlide+delta);
            }
        });
    }

    /** Must be called on the UI thread; reuses the existing drawing and seekbar paths. */
    private void jumpToRainSlide(final int frame){
        if (usableRainFrames<=1){
            refreshRadarTransportControls();
            return;
        }
        int target = normalizeRainFrame(frame,usableRainFrames);
        nextRainSlide = target;
        if (BuildConfig.DEBUG){
            android.util.Log.d("TWFG_MAP70","frame="+target+" running="+rainSlidesRunning+" paused="+rainSlidesPaused);
        }
        if (rainsliderSeekbarContainer!=null){
            rainsliderSeekbarContainer.setVisibility(View.VISIBLE);
        }
        drawRadarSlide(target);
        updateRainSeekBar(target);
        updateSlideTimeText(target);
        updateRainFrameText(target);
        refreshRadarTransportControls();
    }

    /** Wraps a frame index into 0..DATASET_SIZE-1 (24 frames, 5 minutes apart). */
    static int normalizeRainFrame(int frame){
        return normalizeRainFrame(frame,APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE);
    }

    /** TASK-87: wraps a frame index into the number of frames actually cached. */
    static int normalizeRainFrame(int frame, int frameCount){
        if (frameCount<=0){
            return 0;
        }
        int result = frame % frameCount;
        if (result<0){
            result += frameCount;
        }
        return result;
    }

    private void updateRainFrameText(){
        updateRainFrameText(nextRainSlide);
    }

    /** TASK-87: the caption is a pure function of the drawn frame and the real cache size. */
    private void updateRainFrameText(final int frame){
        if (rainSlideFrame==null){
            return;
        }
        if (usableRainFrames<=1){
            rainSlideFrame.setText("");
            return;
        }
        int index = normalizeRainFrame(frame,usableRainFrames);
        rainSlideFrame.setText(getString(R.string.radar_frame,index+1,usableRainFrames));
    }

    private void refreshRadarTransportControls(){
        boolean canAnimate = usableRainFrames>1;
        if (radarPlayButton!=null){
            boolean playing = rainSlidesRunning && !rainSlidesPaused && canAnimate;
            radarPlayButton.setImageResource(playing?R.drawable.ic_radar_pause_24dp:R.drawable.ic_radar_play_24dp);
            radarPlayButton.setContentDescription(getString(playing?R.string.a11y_radar_pause:R.string.a11y_radar_play));
            radarPlayButton.setEnabled(canAnimate);
        }
        if (radarStopButton!=null){
            radarStopButton.setEnabled(canAnimate);
        }
        if (radarStepBackButton!=null){
            radarStepBackButton.setEnabled(canAnimate);
        }
        if (radarStepForwardButton!=null){
            radarStepForwardButton.setEnabled(canAnimate);
        }
        int motionVisibility = canAnimate ? View.VISIBLE : View.GONE;
        if (rainSlideTransportRow!=null){
            rainSlideTransportRow.setVisibility(motionVisibility);
        }
        if (rainsliderSeekbar!=null){
            rainsliderSeekbar.setVisibility(motionVisibility);
            rainsliderSeekbar.setEnabled(canAnimate);
        }
        if (precipitationAtPinPointColors!=null){
            precipitationAtPinPointColors.setVisibility(motionVisibility);
        }
        updateRainFrameText();
        updateSlideTimeText(canAnimate ? nextRainSlide : 0);
        if (!canAnimate && radarEnabled() && (rainsliderSeekbarContainer!=null)){
            // Keep the honest "no animation" message visible instead of an empty band.
            rainsliderSeekbarContainer.setVisibility(View.VISIBLE);
        }
    }

    private int getPrecipitationAtPinPoint(final int count, final Bitmap slideBitmap){
        return slideBitmap.getPixel(Math.round(pinPoint.x),Math.round(pinPoint.y));
    }

    private int getPrecipitationAtPinPoint(final int count) {
        try {
            Bitmap slideBitmap = RadarMN2.getScaledBitmap(context, count);
            if (slideBitmap != null) {
                return getPrecipitationAtPinPoint(count, slideBitmap);
            }
        } catch (Exception e){
            // usually this occurs when there are less than 24 slides, no need to worry, just ignore and return zero
        }
        return 0;
    }

    private int[] getPrecipitationArrayAtPinPoint(){
        int[] resultarray = new int[24];
        for (int i=0; i<APIReaders.RadarMNSetGeoserverRunnable.DATASET_SIZE; i++){
            resultarray[i] = getPrecipitationAtPinPoint(i);
        }
        return resultarray;
    }

    private void drawRadarSlide(final int count){
        // TASK-87: the counter follows the DRAWN frame, never the mutable field that the
        // timer increments afterwards. This is the single fix for the frozen Bild 1/24.
        updateRainFrameText(count);
        updateSlideTimeText(count);
        if (useVectorMap){
            // TASK-54: no static bitmap and no hand pixel corrections here; the raw cached
            // PNG is georeferenced by the ImageSource LatLngQuad.
            applyVectorRadarFrame(count);
            updateRainSeekBar(count);
            lastDrawnRainSlide=count;
            return;
        }
        if (APIReaders.RadarMNSetGeoserverRunnable.radarCacheFileValid(context,count)) {
            radarBitmap.eraseColor(Color.TRANSPARENT);
            Canvas radarCanvas = new Canvas(radarBitmap);
            Bitmap slideBitmap = RadarMN2.getScaledBitmap(context,count);
            final Paint radarPaint = new Paint();
            radarPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
            if (slideBitmap!=null){
                radarCanvas.drawBitmap(slideBitmap,0,0,radarPaint);
            }
            if (!hide_rain) {
                drawMapBitmap();
                updateRainSeekBar(count);
            }
        } else {
            // do nothing
        }
        lastDrawnRainSlide=count;
    }

    private void drawMapBitmap(){
        // The map may be drawn before the warning data/zoom view were created (e.g. from a
        // broadcast or a location callback). Nothing can be painted without these objects.
        if (germanyBitmap==null || warningsBitmap==null || mapZoomable==null){
            return;
        }
        visibleBitmap = germanyBitmap.copy(Bitmap.Config.ARGB_8888,true);
        Canvas canvas = new Canvas(visibleBitmap);
        final Paint cp = new Paint();
        cp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
        canvas.drawBitmap(warningsBitmap,0,0,cp);
        if ((!hide_admin)){
            if (administrativeBitmap==null){
                administrativeBitmap = getAdministrativeBitmap(context,germanyBitmap.getWidth(),germanyBitmap.getHeight(),WeatherSettings.getAreaTypeArray(context));
            }
            cp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
            canvas.drawBitmap(administrativeBitmap,0,0,cp);
        }
        if ((!hide_rain) && (radarBitmap!=null)){
            updateSlideTimeText();
            cp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
            canvas.drawBitmap(radarBitmap, 0,0,cp);
            if (!mapIsDrawn){
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showRainDescription();
                    }
                });
            }
        } else {
            if (!mapIsDrawn){
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        clearRainDescription();
                    }
                });
            }
        }
        if (WeatherSettings.displayWindDistance(context)){
            if (advancedWindStencilBitmap==null){
                advancedWindStencilBitmap = getAdvancedWindStencilBitmap(context,germanyBitmap.getWidth(),germanyBitmap.getHeight());
            }
            cp.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER));
            canvas.drawBitmap(advancedWindStencilBitmap,0,0,cp);
            drawAdvancedWindStencil(canvas);
        }
        mapZoomable.updateBitmap(visibleBitmap);
        visibleBitmap.recycle();
        // set this to true to avoid unnecessary updates of views when the rain radar runs; this might be invalidated
        // by manually changing the layers visible.
        mapIsDrawn = true;
    }

    private void drawWindIcon(){
        final Context context = getApplicationContext();
        final ImageView windicon = (ImageView) findViewById(R.id.warningactivity_windicon);
        Runnable windRunnable = new Runnable() {
            @Override
            public void run() {
                final RelativeLayout windiconContainer = (RelativeLayout) findViewById(R.id.warningactivity_windicon_container);
                if (windiconContainer!=null){
                    if (WeatherSettings.displayWindInRadar(getApplicationContext())){
                        windiconContainer.setVisibility(View.VISIBLE);
                        final ImageView windiconBackground = (ImageView) findViewById(R.id.warningactivity_windicon_background);
                        CurrentWeatherInfo currentWeatherInfo = Weather.getCurrentWeatherInfo(context);
                        if (currentWeatherInfo!=null){
                            final Bitmap windiconBitmap = currentWeatherInfo.currentWeather.getWindSymbol(getApplicationContext(),WeatherSettings.getWindDisplayType(getApplicationContext()),false);
                            ThemePicker.applyColor(context,windiconBitmap,false);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    windiconBackground.setImageResource(R.drawable.blue);
                                    windiconBackground.setColorFilter(ThemePicker.getColorPrimary(context),PorterDuff.Mode.SRC_IN);
                                    windicon.setImageBitmap(windiconBitmap);
                                }
                            });
                        }
                    } else {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                windiconContainer.setVisibility(View.INVISIBLE);
                            }
                        });

                    }
                }
            }
        };
        scheduledExecutorService.execute(windRunnable);
    }

    private void drawAdvancedWindStencil(Canvas canvas){
        int windPathTime = WeatherSettings.getWindDistanceHours(context)*60;
        int arcSize = WeatherSettings.getWindDistanceArc(context)/2;
        Paint windLinePaint =  new Paint();
        windLinePaint.setStyle(Paint.Style.STROKE);
        windLinePaint.setStrokeWidth(3);
        windLinePaint.setColor(Color.BLACK);
        if (mapZoomable!=null){
            windLinePaint.setStrokeWidth(mapZoomable.scaleFactor*3f);
        }
        float positionX = (float) mercatorProjectionTile.getXPixel(ownLocation.longitude);
        float positionY = (float) mercatorProjectionTile.getYPixel(ownLocation.latitude);
        // testing enhanced wind arrow
        CurrentWeatherInfo currentWeatherInfo = Weather.getCurrentWeatherInfo(context);
        if (currentWeatherInfo==null){
            // No forecast cached yet: skip the optional overlay instead of crashing the map.
            return;
        }
        ArrayList<Weather.WindData> windDataList = currentWeatherInfo.getWindForecast(windPathTime/60+1);
        final int windPathLength = windPathTime/15; // number of arcs drawn, corresponding to 15 mins rain progress
        final double ARCSTEPS = 15;
        final DashPathEffect dashPathEffect = new DashPathEffect(new float[]{1f,2f},0f);
        float[] windPathX = new float[windPathLength+1];
        float[] windPathY = new float[windPathLength+1];
        windPathX[0] = positionX; windPathY[0] = positionY;
        double distance = 0; // in km from the current location
        for (int i=1; i<=windPathLength; i++){
            windLinePaint.setStrokeWidth(1f);
            if (i % 4 == 0){
                windLinePaint.setPathEffect(null);
            } else {
                windLinePaint.setPathEffect(dashPathEffect);
            }
            int windDataIndex = WarningUiDataGuard.windDataIndex(i/4, windDataList==null ? 0 : windDataList.size());
            if (windDataIndex<0){
                // Forecast is shorter than the configured wind path; skip this segment.
                continue;
            }
            Weather.WindData windData = windDataList.get(windDataIndex);
            double windDirection = windData.getDirection()+180;
            if (windDirection>360){
                windDirection = windDirection - 360;
            }
            double windSpeed = windData.getSpeedKmh(); // in km/h
            double windWay15MinInKm = windSpeed / 4;
            distance = distance + windWay15MinInKm;
            double[] pathTarget = RadarMN2.getDestinationCoordinates((float) ownLocation.longitude,ownLocation.latitude,windDirection,distance);
            windPathX[i] = (float) mercatorProjectionTile.getXPixel(pathTarget[0]); windPathY[i] = (float) mercatorProjectionTile.getYPixel(pathTarget[1]);
            double[] coordinates = RadarMN2.getDestinationCoordinates((float) ownLocation.longitude,ownLocation.latitude,windDirection - arcSize,distance);
            float x1 = (float) mercatorProjectionTile.getXPixel(coordinates[0]);
            float y1 = (float) mercatorProjectionTile.getYPixel(coordinates[1]);
            for (double arcAngle = windDirection - arcSize; arcAngle <= windDirection + arcSize; arcAngle = arcAngle + ARCSTEPS){
                coordinates = RadarMN2.getDestinationCoordinates((float) ownLocation.longitude,ownLocation.latitude,arcAngle,distance);
                float x2 = (float) mercatorProjectionTile.getXPixel(coordinates[0]);
                float y2 = (float) mercatorProjectionTile.getYPixel(coordinates[1]);
                canvas.drawLine(x1,y1,x2,y2,windLinePaint);
                x1 = x2; y1 = y2;
            }
            windLinePaint.setPathEffect(null);
            canvas.drawLine(windPathX[i-1], windPathY[i-1],windPathX[i], windPathY[i],windLinePaint);
        }
    }

    public Bitmap getAdvancedWindStencilBitmap(Context context, int targetWidth, int targetHeight) {
        Bitmap resultBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        resultBitmap.eraseColor(Color.TRANSPARENT);
        Canvas canvas = new Canvas(resultBitmap);
        drawAdvancedWindStencil(canvas);
        return resultBitmap;
    }

    private boolean hideMap(){
        // The collapsed-container view only exists in the portrait layout.
        if (germany==null || mapcontainer==null || map_collapsed_container==null || weatherList==null){
            return false;
        }
        cancelRainSlides = true;
        germany.setVisibility(View.GONE);
        germany.invalidate();
        LinearLayout.LayoutParams rllp = (LinearLayout.LayoutParams) mapcontainer.getLayoutParams();
        rllp.height=0;
        rllp.weight=0;
        mapcontainer.setLayoutParams(rllp);
        mapcontainer.invalidate();
        mapcontainer.setVisibility(View.GONE);
        LinearLayout.LayoutParams mclp = (LinearLayout.LayoutParams) map_collapsed_container.getLayoutParams();
        map_collapsed_container.setVisibility(View.VISIBLE);
        mclp.weight=1;
        map_collapsed_container.setLayoutParams(mclp);
        map_collapsed_container.invalidate();
        LinearLayout.LayoutParams lop = (LinearLayout.LayoutParams) weatherList.getLayoutParams();
        lop.weight=29;
        weatherList.setLayoutParams(lop);
        weatherList.invalidate();
        //LinearLayout.LayoutParams infoContLop = (LinearLayout.LayoutParams) warningactivityMapinfoContainer.getLayoutParams();
        // infoContLop.weight=0;
        return true;
    }

    private boolean showMap(){
        if (germany==null || mapcontainer==null || weatherList==null){
            return false;
        }
        germany.setVisibility(View.VISIBLE);
        mapcontainer.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams rllp = (LinearLayout.LayoutParams) mapcontainer.getLayoutParams();
        rllp.height=0;
        rllp.weight=18;
        mapcontainer.setLayoutParams(rllp);
        mapcontainer.invalidate();
        if (map_collapsed_container!=null){
            LinearLayout.LayoutParams mclp = (LinearLayout.LayoutParams) map_collapsed_container.getLayoutParams();
            mclp.height=0;
            mclp.weight=0;
            map_collapsed_container.setLayoutParams(mclp);
            map_collapsed_container.invalidate();
            map_collapsed_container.setVisibility(View.GONE);
        }
        LinearLayout.LayoutParams lop = (LinearLayout.LayoutParams) weatherList.getLayoutParams();
        lop.height=0;
        lop.weight=11;
        weatherList.setLayoutParams(lop);
        weatherList.invalidate();
        if (!hide_rain){
            startRainRadar();
        }
        // LinearLayout.LayoutParams infoContLop = (LinearLayout.LayoutParams) warningactivityMapinfoContainer.getLayoutParams();
        // infoContLop.weight=1;
        return true;
    }
    
    private void displayMap(){
        // In vector mode the static bitmap pipeline is intentionally unused.
        if (useVectorMap){
            return;
        }
        if (germany==null || pinPoint==null || weatherWarnings==null){
            return;
        }
        germanyBitmap = loadBitmapMap(WeatherIcons.getIconResource(getApplicationContext(),WeatherIcons.GERMANY));
        if (germanyBitmap==null){
            return;
        }
        warningsBitmap = Bitmap.createBitmap(germanyBitmap.getWidth(),germanyBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        warningsBitmap.eraseColor(Color.TRANSPARENT);
        radarBitmap = Bitmap.createBitmap(germanyBitmap.getWidth(),germanyBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        radarBitmap.eraseColor(Color.TRANSPARENT);
        MAP_PIXEL_HEIGHT = warningsBitmap.getHeight();
        MAP_PIXEL_WIDTH  = warningsBitmap.getWidth();
        polygoncache = new ArrayList<Polygon>();
        excluded_polygoncache = new ArrayList<Polygon>();
        final Canvas canvas = new Canvas(warningsBitmap);
        ArrayList<WeatherWarning> drawWarnings = WarningUiDataGuard.sanitized(new ArrayList<WeatherWarning>(weatherWarnings));
        Collections.sort(drawWarnings);
        Collections.reverse(drawWarnings);
        for (int warning_counter=0; warning_counter<drawWarnings.size(); warning_counter++){
            WeatherWarning warning = drawWarnings.get(warning_counter);
            // A warning whose polygons were never initialised must not abort the whole map.
            if (warning.polygonlist==null){
                warning.initPolygons(context);
            }
            for (int polygon_counter=0; (warning.polygonlist!=null) && (polygon_counter<warning.polygonlist.size()); polygon_counter++){
                float[] polygonX = warning.polygonlist.get(polygon_counter).polygonX;
                float[] polygonY = warning.polygonlist.get(polygon_counter).polygonY;
                // add polygon to cache
                polygoncache.add(new Polygon(polygonX,polygonY,warning.identifier));
                if (polygonX.length>0){
                    Path path = new Path();
                    PlotPoint plotPoint = getPlotPoint(polygonX[0],polygonY[0]);
                    path.moveTo(plotPoint.x, plotPoint.y);
                    for (int vertex_count=1; vertex_count<polygonX.length; vertex_count++){
                        plotPoint = getPlotPoint(polygonX[vertex_count],polygonY[vertex_count]);
                        path.lineTo(plotPoint.x, plotPoint.y);
                    }
                    Paint polypaint = new Paint();
                    polypaint.setColor(warning.getWarningColor());
                    polypaint.setAlpha(128);
                    polypaint.setStyle(Paint.Style.FILL_AND_STROKE);
                    canvas.drawPath(path,polypaint);
                }
            }
            // draw black excluded polygons after other polygons were drawn
            for (int polygon_counter=0; (warning.excluded_polygonlist!=null) && (polygon_counter<warning.excluded_polygonlist.size()); polygon_counter++){
                float[] polygonX = warning.excluded_polygonlist.get(polygon_counter).polygonX;
                float[] polygonY = warning.excluded_polygonlist.get(polygon_counter).polygonY;
                // add excluded-polygon to cache
                excluded_polygoncache.add(new Polygon(polygonX,polygonY,warning.identifier));
                if (polygonX.length>0){
                    Path path = new Path();
                    PlotPoint plotPoint = getPlotPoint(polygonX[0],polygonY[0]);
                    path.moveTo(plotPoint.x,plotPoint.y);
                    for (int vertex_count=1; vertex_count<polygonX.length; vertex_count++){
                        plotPoint = getPlotPoint(polygonX[vertex_count],polygonY[vertex_count]);
                        path.lineTo(plotPoint.x,plotPoint.y);
                    }
                    Paint polypaint = new Paint();
                    Color color = new Color();
                    polypaint.setColor(Color.TRANSPARENT);
                    polypaint.setStyle(Paint.Style.FILL_AND_STROKE);
                    canvas.drawPath(path,polypaint);
                }
            }
        }
        float pinSize = WeatherSettings.getMapPinSize(context)/2f;
        int pinSizePixels = Math.round(18*this.getApplicationContext().getResources().getDisplayMetrics().density*pinSize);
        Bitmap pinSource = BitmapFactory.decodeResource(getResources(),R.mipmap.pin);
        Bitmap pinBitmap = (pinSource==null) ? null : Bitmap.createScaledBitmap(pinSource,pinSizePixels,pinSizePixels,false);
        float pinX = pinPoint.x; float pinY = pinPoint.y;
        // drawAdvancedWindStencil(canvas,145,300);
        drawWindIcon();
        // set close listener
        ImageView closeImageview = (ImageView) findViewById(R.id.closeicon_map);
        if (closeImageview != null){
            closeImageview.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent motionEvent) {
                    return hideMap();
                }
            });
        }
        // set listener
        germany = (ImageView) findViewById(R.id.warningactivity_map);
        gestureDetector = new GestureDetector(this,new MapGestureListener());
        //mapZoomable = new ZoomableImageView(getApplicationContext(),germany,germanyBitmap) {
        // testing
        mapZoomable = new ZoomableImageView(getApplicationContext(),germany,warningsBitmap){
            @Override
            public void onGestureFinished(float scaleFactor, float lastXtouch, float lastYtouch, float xFocus, float yFocus, float xFocusRelative, float yFocusRelative, RectF currentlyVisibleArea){
                final PlotPoint plotPoint = new PlotPoint();
                plotPoint.x = lastXtouch;
                plotPoint.y = lastYtouch;
                Runnable tapRunnable = new Runnable() {
                    @Override
                    public void run() {
                        checkForTapInPolygonWarning(getXGeo(plotPoint),getYGeo(plotPoint));
                    }
                };
                scheduledExecutorService.execute(tapRunnable);
            }
            @Override
            public void onLongPress(float scaleFactor, float lastXtouch, float lastYtouch, float xFocus, float yFocus, float xFocusRelative, float yFocusRelative, RectF currentlyVisibleArea) {
                // currently does nothing; reserved for a future long press event
            }
        };
        if (RadarMN2.getScaleFactor(context)>1){
            mapZoomable.setScaleRange(0.12f,1f);
        }
        if (zoomMapState!=null){
            PrivateLog.log(getApplicationContext(),PrivateLog.WARNINGS,PrivateLog.INFO,"Restoring map state...");
            mapZoomable.restoreZoomViewState(zoomMapState);
        } else {
            PrivateLog.log(getApplicationContext(),PrivateLog.WARNINGS,PrivateLog.INFO,"Not restoring map map state because data is null");
        }
        drawMapBitmap();
        // add the pin sprite
        if ((pinSize>0) && (pinBitmap!=null)){
            mapZoomable.addSpite(pinBitmap,pinX,pinY-pinBitmap.getHeight(),ZoomableImageView.SPRITEFIXPOINT.BOTTOM_LEFT,null);
            //
        }
        mapZoomable.redrawBitmap();
        mapTouchListener = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                mapZoomable.onTouchEvent(motionEvent);
                return true;
            };
        };
        germany.setOnTouchListener(mapTouchListener);

    }

    class MapGestureListener extends GestureDetector.SimpleOnGestureListener{
        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            return super.onSingleTapUp(e);
        }

        @Override
        public void onLongPress(MotionEvent e) {
            super.onLongPress(e);
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            return super.onScroll(e1, e2, distanceX, distanceY);
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            return super.onFling(e1, e2, velocityX, velocityY);
        }

        @Override
        public void onShowPress(MotionEvent e) {
            super.onShowPress(e);
        }

        @Override
        public boolean onDown(MotionEvent e)
        {
            // catch collapse-button-press prior to map-selection
            // perform this only if NOT in landscape layout
            if ((!deviceIsLandscape) && (map_collapsed_container!=null) && (germany!=null) && (weatherList!=null)){
                float button_border_right  = (float) (germany.getMeasuredWidth() * 0.127427184);
                float button_border_bottom = (float) (germany.getMeasuredHeight() * 0.10041841);
                if ((e.getX()<button_border_right) && (e.getY()<button_border_bottom)){
                    return hideMap();
                }
            }
            return false;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            return super.onDoubleTap(e);
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            return super.onDoubleTapEvent(e);
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            return super.onSingleTapConfirmed(e);
        }

        @Override
        public boolean onContextClick(MotionEvent e) {
            return super.onContextClick(e);
        }
    }

    private void checkForTapInPolygonWarning(float x_geo, float y_geo){

        if (polygoncache!=null){
            int position = 0;
            // first check if pointer is in excluded polygon; it is more efficient to do this first.
            if (excluded_polygoncache!=null){
                while (position<excluded_polygoncache.size()){
                    if (excluded_polygoncache.get(position).isInPolygon(x_geo,y_geo)){
                        // break (& do nothing) if pointer is in excluded polygon.
                        return;
                    }
                    position++;
                }
            }
            position = 0;
            while (position<polygoncache.size()){
                if (polygoncache.get(position).isInPolygon(x_geo,y_geo)){
                    jumpListViewToSelection(polygoncache.get(position));
                    return;
                }
                position++;
            }
        }
    }

    private void jumpListViewToSelection(Polygon polygon){
        if (weatherWarnings==null){
            return;
        }
        int position=0;
        while (position<weatherWarnings.size()){
            if (weatherWarnings.get(position).identifier.equals(polygon.identifier_link)){
                break;
            }
            position++;
        }
        if (weatherList != null){
            final int jumpPosition = position;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    weatherList.setSelection(jumpPosition);
                }
            });
        }
    }

    /** Maximum zoom used by the warning-row/notification camera jump. */
    static final double MAX_WARNING_CAMERA_ZOOM = 12d;
    /** Padding in pixels passed to CameraUpdateFactory.newLatLngBounds. */
    static final int WARNING_CAMERA_PADDING = 64;
    /** Spans at or below this value (degrees) count as a degenerate bounding box. */
    static final double WARNING_BOUNDS_EPSILON = 0.00001d;

    private int warningPosition(WeatherWarning warning){
        if ((warning==null) || (weatherWarnings==null)){
            return -1;
        }
        for (int i=0; i<weatherWarnings.size(); i++){
            if (weatherWarnings.get(i)==warning){
                return i;
            }
            if ((weatherWarnings.get(i)!=null) && (warning.identifier!=null) &&
                    warning.identifier.equals(weatherWarnings.get(i).identifier)){
                return i;
            }
        }
        return -1;
    }

    /** Row/notification selection: highlight the list entry, then try to frame its geometry. */
    private void onWarningSelected(WeatherWarning warning,int position){
        if (warning==null){
            return;
        }
        if (position<0){
            position = warningPosition(warning);
        }
        PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.INFO,
                "Warning row selected: "+(warning.identifier==null ? "" : warning.identifier));
        if (weatherWarningAdapter!=null){
            weatherWarningAdapter.setSelectedIdentifier(warning.identifier);
            weatherWarningAdapter.notifyDataSetChanged();
        }
        if ((weatherList!=null) && (position>=0)){
            weatherList.setSelection(position);
        }
        // A user gesture supersedes a still pending notification jump.
        pendingWarningIdentifier = null;
        jumpMapToWarning(warning);
    }

    /**
     * Consumes WeatherSyncAdapter.EXTRA_WARNING_IDENTIFIER once the warnings and (in vector
     * mode) the MapLibre style are ready. Called from displayWarnings and the style callback.
     */
    private void maybeJumpToPendingWarning(){
        if ((pendingWarningIdentifier==null) || pendingWarningJumpDone || (weatherWarnings==null)){
            return;
        }
        if (useVectorMap && ((mapLibreMap==null) || (!vectorWarningStyleReady))){
            return;
        }
        for (int i=0; i<weatherWarnings.size(); i++){
            WeatherWarning warning = weatherWarnings.get(i);
            if ((warning!=null) && (pendingWarningIdentifier.equals(warning.identifier))){
                pendingWarningJumpDone = true;
                pendingWarningIdentifier = null;
                onWarningSelected(warning,i);
                return;
            }
        }
    }

    private void jumpMapToWarning(WeatherWarning warning){
        if (warning==null){
            return;
        }
        if (warning.polygonlist==null){
            warning.initPolygons(context);
        }
        double[] bounds = warningBounds(warning);
        PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.INFO,
                "Warning geometry extent: "+(bounds==null ? "none" :
                        java.util.Arrays.toString(bounds))+" vector="+useVectorMap+" map="+(mapLibreMap!=null));
        if (bounds==null){
            // DWD CAP bulletins that only carry free-text area descriptions (notably UV
            // radiation / sun protection and some heat advisories) have neither a <polygon>
            // nor WARNCELLID geocodes. initPolygons() then yields no geometry at all: keep
            // the current camera and just show a short hint.
            PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.INFO,
                    "Warning without geometry; map camera unchanged: "+(warning.event==null ? "" : warning.event));
            Toast.makeText(context,R.string.warning_no_geometry,Toast.LENGTH_SHORT).show();
            return;
        }
        if ((!useVectorMap) || (mapLibreMap==null)){
            return;
        }
        final double[] targetBounds = bounds;
        final LatLngBounds.Builder boundsBuilder = new LatLngBounds.Builder()
                .include(new LatLng(bounds[1],bounds[0]))
                .include(new LatLng(bounds[3],bounds[2]));
        final LatLngBounds latLngBounds = boundsBuilder.build();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mapLibreMap==null){
                    return;
                }
                try {
                    if (boundsAreDegenerate(targetBounds)){
                        // Keep the current zoom for point-like warnings.
                        mapLibreMap.animateCamera(CameraUpdateFactory.newLatLng(latLngBounds.getCenter()));
                        return;
                    }
                    mapLibreMap.animateCamera(
                            CameraUpdateFactory.newLatLngBounds(latLngBounds,WARNING_CAMERA_PADDING),
                            new MapLibreMap.CancelableCallback() {
                                @Override
                                public void onFinish() {
                                    if ((mapLibreMap!=null) &&
                                            (mapLibreMap.getCameraPosition().zoom>MAX_WARNING_CAMERA_ZOOM)){
                                        mapLibreMap.easeCamera(CameraUpdateFactory.zoomTo(MAX_WARNING_CAMERA_ZOOM));
                                    }
                                }
                                @Override
                                public void onCancel() {
                                }
                            });
                } catch (Exception e){
                    // newLatLngBounds needs a measured map; fall back to the known centre.
                    try {
                        double zoom = Math.min(mapLibreMap.getCameraPosition().zoom,MAX_WARNING_CAMERA_ZOOM);
                        mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLngBounds.getCenter(),zoom));
                    } catch (Exception ignored){
                        // No usable camera at all: leave the current view untouched.
                    }
                }
            }
        });
    }

    /**
     * Pure geometry helper: WGS84 extent {west,south,east,north} of one warning. Both its
     * polygons and its excluded areas are included so the cut-out stays in view, and
     * Polygon.polygonX/polygonY are longitude/latitude respectively (see Polygon.java).
     * Returns null when the warning carries no usable geometry.
     */
    static double[] warningBounds(WeatherWarning warning){
        if (warning==null){
            return null;
        }
        double minLon = Double.POSITIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        int points = 0;
        ArrayList<ArrayList<Polygon>> groups = new ArrayList<ArrayList<Polygon>>();
        groups.add(warning.polygonlist);
        groups.add(warning.excluded_polygonlist);
        for (ArrayList<Polygon> polygons : groups){
            if (polygons==null){
                continue;
            }
            for (Polygon polygon : polygons){
                if ((polygon==null) || (polygon.polygonX==null) || (polygon.polygonY==null)){
                    continue;
                }
                int count = Math.min(polygon.polygonX.length,polygon.polygonY.length);
                for (int i=0; i<count; i++){
                    double lon = polygon.polygonX[i];
                    double lat = polygon.polygonY[i];
                    if ((!Double.isFinite(lon)) || (!Double.isFinite(lat)) ||
                            (Math.abs(lon)>180d) || (Math.abs(lat)>90d)){
                        continue;
                    }
                    minLon = Math.min(minLon,lon);
                    maxLon = Math.max(maxLon,lon);
                    minLat = Math.min(minLat,lat);
                    maxLat = Math.max(maxLat,lat);
                    points++;
                }
            }
        }
        if (points==0){
            return null;
        }
        return new double[]{minLon,minLat,maxLon,maxLat};
    }

    static boolean boundsAreDegenerate(double[] bounds){
        if ((bounds==null) || (bounds.length<4)){
            return true;
        }
        return (Math.abs(bounds[2]-bounds[0])<=WARNING_BOUNDS_EPSILON) ||
                (Math.abs(bounds[3]-bounds[1])<=WARNING_BOUNDS_EPSILON);
    }

    private boolean isDeviceLandscape(){
        Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        int orientation = display.getRotation();
        if ((orientation==Surface.ROTATION_0)|| (orientation==Surface.ROTATION_180)){
            return false;
        }
        return true;
    }

    private void showProgressBar(){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ProgressBar progressBar = (ProgressBar) findViewById(R.id.warningactivity_progressbar);
                if (progressBar!=null){
                    progressBar.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void hideProgressBar(){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ProgressBar progressBar = (ProgressBar) findViewById(R.id.warningactivity_progressbar);
                if (progressBar!=null){
                    progressBar.setVisibility(View.INVISIBLE);
                }
            }
        });
    }

    private void registerForBroadcast(){
        IntentFilter filter = new IntentFilter();
        filter.addAction(WEATHER_WARNINGS_UPDATE);
        filter.addAction(MainActivity.MAINAPP_HIDE_PROGRESS);
        filter.addAction(ACTION_RAINRADAR_UPDATE);
        MainActivity.registerForInternalBroadcasts(this,receiver,filter);
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Override
    public void onRequestPermissionsResult(int permRequestCode, String perms[], int[] grantRes){
        Boolean hasLocationPermission = false;
        for (int i=0; i<grantRes.length; i++){
            if ((perms[i].equals(Manifest.permission.ACCESS_FINE_LOCATION)) && (grantRes[i]== PackageManager.PERMISSION_GRANTED)){
                hasLocationPermission = true;
            }
        }
        if (permRequestCode == WeatherLocationManager.PERMISSION_CALLBACK_LOCATION){
            if (hasLocationPermission){
                if (weatherLocationManager!=null){
                    weatherLocationManager.checkLocation();
                }
            } else {
                if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)){
                    showLocationPermissionsRationale();
                }
            }
        }
    }

    private void showSimpleLocationAlert(String text){
        AlertDialog.Builder builder = new AlertDialog.Builder(this,0);
        builder.setTitle(getApplicationContext().getResources().getString(R.string.geoinput_title));
        Drawable drawable = new BitmapDrawable(getResources(),WeatherIcons.getIconBitmap(context,WeatherIcons.IC_GPS_FIXED,false));
        builder.setIcon(drawable);
        builder.setMessage(text);
        builder.setPositiveButton(R.string.alertdialog_ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                dialogInterface.dismiss();
            }
        });
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
    }

    private void showLocationPermissionsRationale(){
        showSimpleLocationAlert(getApplicationContext().getResources().getString(R.string.geoinput_rationale));
    }

    public Bitmap getAdministrativeBitmap(Context context, int targetWidth, int targetHeight, int[] types){
        Bitmap resultBitmap = Bitmap.createBitmap(targetWidth,targetHeight, Bitmap.Config.ARGB_8888);
        resultBitmap.eraseColor(Color.TRANSPARENT);
        Canvas canvas = new Canvas(resultBitmap);
        for (int type=0; type<types.length; type++){
            ArrayList<Areas.Area> allAreas = Areas.getAreas(context, types[type]);
            Paint areaPaint = new Paint();
            areaPaint.setColor(Color.BLACK);
            areaPaint.setAlpha(96);
            areaPaint.setStyle(Paint.Style.STROKE);
            if (types[type]==Areas.Area.Type.SEE){
                areaPaint.setColor(Color.CYAN);
            }
            if (types[type]==Areas.Area.Type.KUESTE){
                areaPaint.setColor(Color.YELLOW);
            }
            if (types[type]==Areas.Area.Type.GEMEINDE){
                areaPaint.setColor(Color.GRAY);
            }
            if (types[type]==Areas.Area.Type.BUNDESLAND){
                areaPaint.setColor(Color.BLUE);
                areaPaint.setStrokeWidth(2);
            }
            for (int i=0; i<allAreas.size(); i++){
                Areas.Area cellArea = allAreas.get(i);
                ArrayList<Polygon> areaPolygons = cellArea.polygons;
                for (int p=0; p<areaPolygons.size(); p++){
                    Polygon areaPolygon = areaPolygons.get(p);
                    Path path = new Path();
                     PlotPoint plotPoint = getPlotPoint(areaPolygon.polygonX[0],areaPolygon.polygonY[0]);
                    path.moveTo(plotPoint.x, plotPoint.y);
                    for (int v=0; v<areaPolygon.polygonX.length; v++){
                        plotPoint = getPlotPoint(areaPolygon.polygonX[v],areaPolygon.polygonY[v]);
                        path.lineTo(plotPoint.x, plotPoint.y);
                    }
                    canvas.drawPath(path,areaPaint);
                }
            }
        }
        return resultBitmap;
    }

    public void popupHint(){
        final int[] hintTimes = {20,3,6,9};
        final int count = WeatherSettings.getHintCounter2(context);
        if ((count==hintTimes[1]) || (count==hintTimes[2]) || (count==hintTimes[3])){
            final RelativeLayout anchorView = (RelativeLayout) findViewById(R.id.warningactivity_main_relative_container);
            if (anchorView!=null){
                anchorView.post(new Runnable() {
                    @Override
                    public void run() {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                DisplayMetrics displayMetrics = new DisplayMetrics();
                                getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                                int displayWidth  = Math.round(displayMetrics.widthPixels);
                                int displayHeight = Math.round(displayMetrics.heightPixels);
                                final boolean isLandscape = displayWidth>displayHeight;
                                LayoutInflater layoutInflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                                final View popupView = layoutInflater.inflate(R.layout.popup_hint1,null);
                                // set correct theme textcolors
                                TextView textView1 = (TextView) popupView.findViewById(R.id.hint1_text);
                                textView1.setTextColor(Color.WHITE);
                                // register click callbacks
                                Button bottonOk = (Button) popupView.findViewById(R.id.hint1_button);
                                bottonOk.setTextColor(Color.WHITE);
                                bottonOk.setOnClickListener(new View.OnClickListener() {
                                    @Override
                                    public void onClick(View view) {
                                        if (hintPopupWindow!=null){
                                            hintPopupWindow.dismiss();
                                        }
                                    }
                                });
                                CheckBox checkNo = (CheckBox) popupView.findViewById(R.id.hint1_checkbox);
                                checkNo.setTextColor(Color.WHITE);
                                checkNo.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                                    @Override
                                    public void onCheckedChanged(CompoundButton compoundButton, boolean checked) {
                                        if (checked){
                                            WeatherSettings.setHintCounter1(context,hintTimes[0]);
                                            WeatherSettings.setHintCounter2(context,hintTimes[0]);
                                        } else {
                                            WeatherSettings.setHintCounter1(context,0);
                                            WeatherSettings.setHintCounter2(context,0);
                                        }
                                    }
                                });
                                int width  = Math.round(displayWidth * 0.8f);
                                int height = Math.round(displayHeight * 0.26f);
                                if (isLandscape){
                                    height = Math.round(displayHeight * 0.4f);
                                }
                                ImageView imageView = (ImageView) popupView.findViewById(R.id.hint1_image);
                                if (count==hintTimes[1]){
                                    textView1.setText(context.getResources().getString(R.string.hint_1));
                                    imageView.setImageResource(R.drawable.radar_hint);
                                    height = Math.round(displayHeight * 0.47f);
                                    if (isLandscape){
                                        height = Math.round(displayHeight * 0.6f);
                                    }
                                }
                                if (count==hintTimes[2]){
                                    textView1.setText(context.getResources().getString(R.string.welcome_s3_text1));
                                    imageView.setImageResource(R.drawable.collapse_hint);
                                }
                                if (count==hintTimes[3]){
                                    textView1.setText(context.getResources().getString(R.string.welcome_s3_text2));
                                    imageView.setImageResource(R.drawable.expand_hint);
                                }
                                hintPopupWindow = new PopupWindow(popupView,width,height,true);
                                hintPopupWindow.showAtLocation(anchorView,Gravity.CENTER,0,0);
                                hintPopupWindow.setOnDismissListener(new PopupWindow.OnDismissListener() {
                                    @Override
                                    public void onDismiss() {
                                        displayOsmNotice();
                                    }
                                });
                            }
                        });
                    }
                });
            }
        }
        if (count<hintTimes[0]){
            int newCount = count + 1;
            WeatherSettings.setHintCounter2(context,newCount);
        }
    }

    /** Removes a layer before its source; MapLibre rejects source removal while in use. */
    private static void removeManagedStyleLayer(Style style,String layerId){
        try {
            if (style.getLayer(layerId)!=null){
                style.removeLayer(layerId);
            }
        } catch (Exception e){
            // A concurrent style reload may have removed it already.
        }
    }

    private static void removeManagedStyleSource(Style style,String sourceId){
        try {
            if (style.getSourceAs(sourceId)!=null){
                style.removeSource(sourceId);
            }
        } catch (Exception e){
            // A concurrent style reload may have removed it already.
        }
    }

    /**
     * TASK-53: convert the parsed CAP polygons of the current warnings into GeoJSON and push
     * them into MapLibre. Layers are only installed once the OpenFreeMap style is ready;
     * before that the callback in {@link #setupVectorMap(Bundle)} installs the latest data.
     * MapLibre parses the GeoJSON string itself - no external GeoJSON parser is involved.
     */
    private void applyVectorWarningLayers(){
        if (!useVectorMap || mapLibreMap==null || !vectorWarningStyleReady){
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!useVectorMap || mapLibreMap==null || !vectorWarningStyleReady){
                    return;
                }
                Style style;
                try {
                    style = mapLibreMap.getStyle();
                } catch (Exception e){
                    return;
                }
                if (style==null){
                    return;
                }
                ArrayList<WeatherWarning> safeWarnings = WarningUiDataGuard.sanitized(weatherWarnings);
                // Populate the tap cache from exactly the same data that is rendered, so a
                // click resolves to the same warning row as on the legacy static map.
                refreshVectorPolygonCaches(safeWarnings);
                if (showWarningPolygons){
                    String warningGeoJson = WarningGeoJson.featureCollection(safeWarnings,false);
                    GeoJsonSource warningSource = style.getSourceAs(WARNING_SOURCE_ID);
                    if (warningSource==null){
                        warningSource = new GeoJsonSource(WARNING_SOURCE_ID,warningGeoJson);
                        style.addSource(warningSource);
                    } else {
                        warningSource.setGeoJson(warningGeoJson);
                    }
                    if (style.getLayer(WARNING_FILL_LAYER_ID)==null){
                        FillLayer fill = new FillLayer(WARNING_FILL_LAYER_ID,WARNING_SOURCE_ID)
                                .withProperties(
                                        PropertyFactory.fillColor(Expression.get(WarningGeoJson.PROPERTY_COLOR)),
                                        PropertyFactory.fillOpacity(WARNING_FILL_OPACITY));
                        // Radar must stay underneath the coloured fills even when this layer
                        // is (re-)added after a style reload or an undock/toggle cycle.
                        if (style.getLayer(RADAR_LAYER_ID)!=null){
                            style.addLayerAbove(fill,RADAR_LAYER_ID);
                        } else {
                            style.addLayer(fill);
                        }
                    }
                    if (style.getLayer(WARNING_LINE_LAYER_ID)==null){
                        LineLayer outline = new LineLayer(WARNING_LINE_LAYER_ID,WARNING_SOURCE_ID)
                                .withProperties(
                                        PropertyFactory.lineColor(Expression.get(WarningGeoJson.PROPERTY_COLOR)),
                                        PropertyFactory.lineWidth(2f));
                        style.addLayer(outline);
                    }
                } else {
                    removeManagedStyleLayer(style,WARNING_FILL_LAYER_ID);
                    removeManagedStyleLayer(style,WARNING_LINE_LAYER_ID);
                    removeManagedStyleSource(style,WARNING_SOURCE_ID);
                }
                if (showExcludedAreas){
                    String excludedGeoJson = WarningGeoJson.featureCollection(safeWarnings,true);
                    GeoJsonSource excludedSource = style.getSourceAs(EXCLUDED_SOURCE_ID);
                    if (excludedSource==null){
                        excludedSource = new GeoJsonSource(EXCLUDED_SOURCE_ID,excludedGeoJson);
                        style.addSource(excludedSource);
                    } else {
                        excludedSource.setGeoJson(excludedGeoJson);
                    }
                    if (style.getLayer(EXCLUDED_FILL_LAYER_ID)==null){
                        FillLayer excludedFill = new FillLayer(EXCLUDED_FILL_LAYER_ID,EXCLUDED_SOURCE_ID)
                                .withProperties(
                                        PropertyFactory.fillColor(EXCLUDED_FILL_COLOR),
                                        PropertyFactory.fillOpacity(EXCLUDED_FILL_OPACITY));
                        style.addLayer(excludedFill);
                    }
                    if (style.getLayer(EXCLUDED_LINE_LAYER_ID)==null){
                        LineLayer excludedOutline = new LineLayer(EXCLUDED_LINE_LAYER_ID,EXCLUDED_SOURCE_ID)
                                .withProperties(
                                        PropertyFactory.lineColor(EXCLUDED_LINE_COLOR),
                                        PropertyFactory.lineWidth(2f),
                                        PropertyFactory.lineDasharray(new Float[]{2f,1f}));
                        style.addLayer(excludedOutline);
                    }
                } else {
                    removeManagedStyleLayer(style,EXCLUDED_FILL_LAYER_ID);
                    removeManagedStyleLayer(style,EXCLUDED_LINE_LAYER_ID);
                    removeManagedStyleSource(style,EXCLUDED_SOURCE_ID);
                }
            }
        });
    }

    /** Rebuilds the caches used by {@link #checkForTapInPolygonWarning(float, float)}. */
    private void refreshVectorPolygonCaches(ArrayList<WeatherWarning> warnings){
        polygoncache = new ArrayList<Polygon>();
        excluded_polygoncache = new ArrayList<Polygon>();
        if (warnings==null){
            return;
        }
        for (WeatherWarning warning : warnings){
            if (warning==null){
                continue;
            }
            if (warning.polygonlist==null){
                warning.initPolygons(context);
            }
            if (warning.polygonlist!=null){
                for (Polygon polygon : warning.polygonlist){
                    if (polygon!=null){
                        polygoncache.add(new Polygon(polygon.polygonX,polygon.polygonY,warning.identifier));
                    }
                }
            }
            if (warning.excluded_polygonlist!=null){
                for (Polygon polygon : warning.excluded_polygonlist){
                    if (polygon!=null){
                        excluded_polygoncache.add(new Polygon(polygon.polygonX,polygon.polygonY,warning.identifier));
                    }
                }
            }
        }
    }

    /**
     * TASK-53: pure conversion from the parsed CAP polygon arrays to a GeoJSON
     * FeatureCollection string. Kept free of MapLibre types so the geometry, colour and
     * identifier mapping can be verified by a JVM unit test. MapLibre receives the string
     * and parses it itself; this class never parses GeoJSON.
     */
    static final class WarningGeoJson {
        static final String PROPERTY_IDENTIFIER="identifier";
        static final String PROPERTY_COLOR="color";
        private static final String TYPE="type";
        private static final String FEATURE="Feature";
        private static final String FEATURE_COLLECTION="FeatureCollection";
        private static final String POLYGON="Polygon";
        private static final String GEOMETRY="geometry";
        private static final String PROPERTIES="properties";
        private static final String COORDINATES="coordinates";
        private static final String FEATURES="features";

        private WarningGeoJson(){
        }

        /**
         * @param excluded when true the excluded (cut-out) polygons are exported instead of
         *                 the warning polygons, so they can use a distinct layer style.
         */
        static String featureCollection(ArrayList<WeatherWarning> warnings, boolean excluded){
            try {
                return buildFeatureCollection(warnings,excluded);
            } catch (JSONException e){
                // Never let malformed warning data break the map layer update.
                return "{\"type\":\"FeatureCollection\",\"features\":[]}";
            }
        }

        private static String buildFeatureCollection(ArrayList<WeatherWarning> warnings, boolean excluded) throws JSONException {
            JSONArray features = new JSONArray();
            if (warnings!=null){
                for (WeatherWarning warning : warnings){
                    if (warning==null){
                        continue;
                    }
                    ArrayList<Polygon> polygons = excluded ? warning.excluded_polygonlist : warning.polygonlist;
                    if (polygons==null){
                        continue;
                    }
                    for (Polygon polygon : polygons){
                        if ((polygon==null) || (polygon.polygonX==null) || (polygon.polygonY==null)){
                            continue;
                        }
                        int vertexCount = Math.min(polygon.polygonX.length,polygon.polygonY.length);
                        // A GeoJSON linear ring needs at least three distinct positions.
                        if (vertexCount<3){
                            continue;
                        }
                        JSONArray ring = new JSONArray();
                        for (int i=0; i<vertexCount; i++){
                            JSONArray coordinate = new JSONArray();
                            // Polygon.polygonX is longitude, polygonY is latitude (see Polygon).
                            coordinate.put((double) polygon.polygonX[i]);
                            coordinate.put((double) polygon.polygonY[i]);
                            ring.put(coordinate);
                        }
                        // GeoJSON requires the ring to be explicitly closed.
                        JSONArray closing = new JSONArray();
                        closing.put((double) polygon.polygonX[0]);
                        closing.put((double) polygon.polygonY[0]);
                        ring.put(closing);
                        JSONArray coordinates = new JSONArray();
                        coordinates.put(ring);
                        JSONObject geometry = new JSONObject();
                        geometry.put(TYPE,POLYGON);
                        geometry.put(COORDINATES,coordinates);
                        JSONObject properties = new JSONObject();
                        properties.put(PROPERTY_IDENTIFIER,warning.identifier==null ? "" : warning.identifier);
                        // CAP AREA_COLOR is exposed by getWarningColor() as an ARGB int.
                        properties.put(PROPERTY_COLOR,colorToHex(warning.getWarningColor()));
                        JSONObject feature = new JSONObject();
                        feature.put(TYPE,FEATURE);
                        feature.put(GEOMETRY,geometry);
                        feature.put(PROPERTIES,properties);
                        features.put(feature);
                    }
                }
            }
            JSONObject collection = new JSONObject();
            collection.put(TYPE,FEATURE_COLLECTION);
            collection.put(FEATURES,features);
            return collection.toString();
        }

        private static String colorToHex(int argb){
            return String.format(Locale.US,"#%06X",0xFFFFFF & argb);
        }
    }

    /**
     * TASK-54: exact DWD radar corners as a MapLibre LatLngQuad. LatLngQuad takes
     * TOP-LEFT, TOP-RIGHT, BOTTOM-RIGHT, BOTTOM-LEFT (not lat/lon sorted pairs).
     * No hand pixel correction is applied: the cached PNG is georeferenced directly.
     */
    static LatLngQuad radarLatLngQuad(){
        double[] bounds = RadarMN2.getRadarVectorBounds();
        return new LatLngQuad(
                new LatLng(bounds[1],bounds[0]), // TL: west / north
                new LatLng(bounds[1],bounds[2]), // TR: east / north
                new LatLng(bounds[3],bounds[2]), // BR: east / south
                new LatLng(bounds[3],bounds[0]));// BL: west / south
    }

    /**
     * TASK-54: swap one cached radar frame into the single MapLibre ImageSource. The bitmap
     * comes from the already-downloaded PNG (RadarMN2.getBitmap) - there is no network call
     * per frame, and RadarMN2's legacy getXPixel/getYPixel corrections are not used here.
     */
    private void applyVectorRadarFrame(final int count){
        // Direct callers (SeekBar, layer selector, style-ready callback) do not always go
        // through drawRadarSlide(); keep their caption in sync as well.
        updateRainFrameText(count);
        updateSlideTimeText(count);
        if ((!useVectorMap) || (mapLibreMap==null) || (!vectorWarningStyleReady) || (!showRadarLayer)){
            return;
        }
        if (!APIReaders.RadarMNSetGeoserverRunnable.radarCacheFileValid(context,count)){
            return;
        }
        final Bitmap bitmap = RadarMN2.getBitmap(context,count);
        if (bitmap==null){
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if ((!useVectorMap) || (mapLibreMap==null) || (!vectorWarningStyleReady) || (!showRadarLayer)){
                    return;
                }
                Style style;
                try {
                    style = mapLibreMap.getStyle();
                } catch (Exception e){
                    return;
                }
                if (style==null){
                    return;
                }
                ImageSource source = style.getSourceAs(RADAR_SOURCE_ID);
                if (source==null){
                    source = new ImageSource(RADAR_SOURCE_ID,radarLatLngQuad(),bitmap);
                    style.addSource(source);
                } else {
                    source.setImage(bitmap);
                }
                if (style.getLayer(RADAR_LAYER_ID)==null){
                    RasterLayer raster = new RasterLayer(RADAR_LAYER_ID,RADAR_SOURCE_ID)
                            .withProperties(
                                    PropertyFactory.rasterOpacity(RADAR_RASTER_OPACITY),
                                    PropertyFactory.rasterFadeDuration(0f));
                    // Keep the coloured warning fills readable above the radar overlay.
                    if (style.getLayer(WARNING_FILL_LAYER_ID)!=null){
                        style.addLayerBelow(raster,WARNING_FILL_LAYER_ID);
                    } else {
                        style.addLayer(raster);
                    }
                }
            }
        });
    }

    /** Hides radar in vector mode when the user switches the radar layer off. */
    private void removeVectorRadarLayer(){
        if ((mapLibreMap==null) || (!vectorWarningStyleReady)){
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Style style;
                try {
                    style = mapLibreMap.getStyle();
                } catch (Exception e){
                    return;
                }
                if (style!=null){
                    style.removeLayer(RADAR_LAYER_ID);
                    removeManagedStyleSource(style,RADAR_SOURCE_ID);
                }
            }
        });
    }

    /**
     * TASK-55: station pin and wind-distance arcs on the vector map. Both use WGS84
     * coordinates from WeatherSettings/RadarMN2.getDestinationCoordinates - no hand pixel
     * correction from the legacy static map is applied here.
     */
    private void applyVectorOverlays(){
        if ((!useVectorMap) || (mapLibreMap==null) || (!vectorWarningStyleReady)){
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                applyVectorPin();
                applyVectorWind();
            }
        });
    }

    /** Places the station pin at the exact geo position of the configured station. */
    private void applyVectorPin(){
        if (mapLibreMap==null){
            return;
        }
        if (vectorPinMarker!=null){
            mapLibreMap.removeMarker(vectorPinMarker);
            vectorPinMarker=null;
        }
        if ((!showStationPin) || (ownLocation==null)){
            return;
        }
        float pinSize = WeatherSettings.getMapPinSize(context)/2f;
        int sourcePinPixels = Math.max(1,Math.round(18*getResources().getDisplayMetrics().density*pinSize));
        // TASK-66: the full-size padded sprite dwarfed the vector basemap and warning
        // polygons. Scale it down while keeping the actual sprite tip on the station.
        int pinSizePixels = RadarMN2.scaleVectorPinSize(sourcePinPixels);
        Bitmap pinSource = BitmapFactory.decodeResource(getResources(),R.mipmap.pin);
        if (pinSource==null){
            return;
        }
        Bitmap pinBitmap = Bitmap.createScaledBitmap(pinSource,pinSizePixels,pinSizePixels,false);
        // MapLibre 13.8 has no setAnchor(): a Marker icon is anchored at the centre of its
        // bitmap. Pad to a square canvas and shift the sprite so its bottom-left tip (not
        // its bounding-box centre) is exactly at that centre, i.e. at the station LatLng.
        int canvasSize = RadarMN2.vectorPinCanvasSize(pinSizePixels);
        Bitmap anchored = Bitmap.createBitmap(canvasSize,canvasSize,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(anchored);
        canvas.drawBitmap(pinBitmap,RadarMN2.vectorPinOffsetX(pinSizePixels),0,null);
        vectorPinMarker = mapLibreMap.addMarker(new MarkerOptions()
                .position(new LatLng(ownLocation.latitude,ownLocation.longitude))
                .icon(IconFactory.getInstance(context).fromBitmap(anchored)));
    }

    /** Rebuilds the wind-distance arcs as geo-referenced MapLibre polylines. */
    private void applyVectorWind(){
        if (mapLibreMap==null){
            return;
        }
        for (Polyline line : vectorWindPolylines){
            mapLibreMap.removePolyline(line);
        }
        vectorWindPolylines.clear();
        if ((!showWindArcs) || (ownLocation==null) || (!WeatherSettings.displayWindDistance(context))){
            return;
        }
        CurrentWeatherInfo currentWeatherInfo = Weather.getCurrentWeatherInfo(context);
        if (currentWeatherInfo==null){
            // No forecast cached yet: skip the optional overlay instead of crashing the map.
            return;
        }
        int windPathTime = WeatherSettings.getWindDistanceHours(context)*60;
        int arcSize = WeatherSettings.getWindDistanceArc(context)/2;
        ArrayList<Weather.WindData> windDataList = currentWeatherInfo.getWindForecast(windPathTime/60+1);
        final int windPathLength = windPathTime/15;
        final double ARCSTEPS = 15;
        double distance = 0;
        double[] previous = new double[]{ownLocation.longitude,ownLocation.latitude};
        for (int i=1; i<=windPathLength; i++){
            int windDataIndex = WarningUiDataGuard.windDataIndex(i/4,windDataList==null ? 0 : windDataList.size());
            if (windDataIndex<0){
                // Forecast is shorter than the configured wind path; skip this segment.
                continue;
            }
            Weather.WindData windData = windDataList.get(windDataIndex);
            double windDirection = windData.getDirection()+180;
            if (windDirection>360){
                windDirection = windDirection - 360;
            }
            double windSpeed = windData.getSpeedKmh();
            distance = distance + windSpeed/4;
            double[] target = RadarMN2.getDestinationCoordinates(ownLocation.longitude,ownLocation.latitude,windDirection,distance);
            addVectorWindLine(previous,target,(i%4==0) ? 2.5f : 1.3f);
            previous = target;
            double[] arcPoint = RadarMN2.getDestinationCoordinates(ownLocation.longitude,ownLocation.latitude,windDirection-arcSize,distance);
            for (double arcAngle=windDirection-arcSize+ARCSTEPS; arcAngle<=windDirection+arcSize; arcAngle=arcAngle+ARCSTEPS){
                double[] next = RadarMN2.getDestinationCoordinates(ownLocation.longitude,ownLocation.latitude,arcAngle,distance);
                addVectorWindLine(arcPoint,next,1f);
                arcPoint = next;
            }
        }
    }

    private void addVectorWindLine(double[] from, double[] to, float width){
        try {
            Polyline line = mapLibreMap.addPolyline(new PolylineOptions()
                    .add(new LatLng(from[1],from[0]),new LatLng(to[1],to[0]))
                    .color(Color.BLACK)
                    .alpha(0.8f)
                    .width(width));
            if (line!=null){
                vectorWindPolylines.add(line);
            }
        } catch (Exception e){
            // A malformed forecast point must not take the map down.
        }
    }

    /**
     * TASK-52: initialise the MapLibre MapView declared in both warning-map layouts.
     * The style is loaded from OpenFreeMap over HTTPS; the legacy static map is not touched.
     */
    private void setupVectorMap(Bundle savedInstanceState){
        mapLibreMapView = (MapView) findViewById(R.id.warningactivity_maplibre);
        if (mapLibreMapView==null){
            // Defensive fallback if a future layout variant omits the vector container.
            useVectorMap = false;
            applyMapMode();
            return;
        }
        mapLibreMapView.onCreate(savedInstanceState);
        mapLibreMapView.getMapAsync(new OnMapReadyCallback() {
            @Override
            public void onMapReady(MapLibreMap map){
                if (map==null){
                    return;
                }
                mapLibreMap = map;
                // TASK-70: MapLibre draws its wordmark and a circled "i" attribution
                // button on top of each other in the bottom-left corner. Our own
                // warningactivity_maplibre_attribution TextView carries all data
                // credits (OpenFreeMap, OpenMapTiles, OpenStreetMap contributors)
                // in both orientations, and the MapLibre BSD notice stays in NOTICE,
                // so the duplicate native widgets can be switched off.
                UiSettings uiSettings = map.getUiSettings();
                if (uiSettings!=null){
                    uiSettings.setLogoEnabled(false);
                    uiSettings.setAttributionEnabled(false);
                }
                // TASK-53: keep the legacy tap-to-warning behaviour. The click coordinates
                // are tested against the same parsed polygon cache the static map uses.
                map.addOnMapClickListener(new MapLibreMap.OnMapClickListener() {
                    @Override
                    public boolean onMapClick(LatLng point){
                        if ((!useVectorMap) || (point==null)){
                            return false;
                        }
                        checkForTapInPolygonWarning((float) point.getLongitude(),(float) point.getLatitude());
                        return true;
                    }
                });
                try {
                    // Style loading is asynchronous: warning sources/layers are added only
                    // from the OnStyleLoaded callback, never from onMapReady itself.
                    // Async counterpart of map.setStyle(VectorMapConfig.OPENFREEMAP_STYLE_URL).
                    map.setStyle(VectorMapConfig.OPENFREEMAP_STYLE_URL,new Style.OnStyleLoaded() {
                        @Override
                        public void onStyleLoaded(Style style){
                            vectorWarningStyleReady = true;
                            applyVectorWarningLayers();
                            // TASK-54: install the radar source/layer from a cached frame if
                            // one is already available; the timer updates it as frames arrive.
                            applyVectorRadarFrame(nextRainSlide);
                            // TASK-55: station pin and wind arcs after the style is ready.
                            applyVectorOverlays();
                            // TASK-65: a notification tap can only jump once the style exists.
                            maybeJumpToPendingWarning();
                        }
                    });
                    map.moveCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
                            .target(new LatLng(VectorMapConfig.DEFAULT_CENTER_LATITUDE,VectorMapConfig.DEFAULT_CENTER_LONGITUDE))
                            .zoom(VectorMapConfig.DEFAULT_ZOOM)
                            .build()));
                } catch (Exception e){
                    PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.ERR,"Could not initialise the vector basemap.");
                }
            }
        });
    }

    /** Shows the selected map kind and removes static-map-only overlays in vector mode. */
    private void applyMapMode(){
        setVectorViewVisibility(R.id.warningactivity_maplibre_container,useVectorMap ? View.VISIBLE : View.GONE);
        setVectorViewVisibility(R.id.warningactivity_maplibre_attribution,useVectorMap ? View.VISIBLE : View.GONE);
        // TASK-67: zoom/locate belong to the vector map only.
        setVectorViewVisibility(R.id.warningactivity_map_controls,useVectorMap ? View.VISIBLE : View.GONE);
        if (useVectorMap){
            setVectorViewVisibility(R.id.warningactivity_map,View.GONE);
            setVectorViewVisibility(R.id.warningactivity_mapinfo_container,View.GONE);
            // Keep the SeekBar view measured (invisible, not gone): legacy precipitation
            // helpers may still measure it, and it never becomes visible in vector mode.
            setVectorViewVisibility(R.id.closeicon_map,View.GONE);
            setVectorViewVisibility(R.id.warningactivity_windicon_container,View.GONE);
            setVectorViewVisibility(R.id.warningactivity_rainslideprogressbar,View.GONE);
            setVectorViewVisibility(R.id.warningactivity_rainslideprogressbartext,View.GONE);
        }
    }

    private void setVectorViewVisibility(int id, int visibility){
        View view = findViewById(id);
        if (view!=null){
            view.setVisibility(visibility);
        }
    }

    /**
     * TASK-67: attach the three custom map controls. There is no native zoom UI in
     * MapLibre Native 13.8 (UiSettings only exposes gestures/compass/attribution).
     */
    private void setupMapControls(){
        mapControls = findViewById(R.id.warningactivity_map_controls);
        mapZoomInButton = (ImageButton) findViewById(R.id.warningactivity_map_zoom_in);
        mapZoomOutButton = (ImageButton) findViewById(R.id.warningactivity_map_zoom_out);
        mapLocateButton = (ImageButton) findViewById(R.id.warningactivity_map_locate);
        if (mapZoomInButton!=null){
            mapZoomInButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view){
                    changeVectorZoom(MAP_ZOOM_STEP);
                }
            });
        }
        if (mapZoomOutButton!=null){
            mapZoomOutButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view){
                    changeVectorZoom(-MAP_ZOOM_STEP);
                }
            });
        }
        if (mapLocateButton!=null){
            mapLocateButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view){
                    locateVectorMap();
                }
            });
        }
    }

    /** Keeps a requested zoom inside the map's configured zoom range (order-independent). */
    static double clampVectorZoom(double requestedZoom, double minZoom, double maxZoom){
        double low = minZoom;
        double high = maxZoom;
        if (low>high){
            double swap = low;
            low = high;
            high = swap;
        }
        return Math.max(low,Math.min(high,requestedZoom));
    }

    /** One +/- step, animated, honouring the native map's min/max zoom preferences. */
    private void changeVectorZoom(double zoomDelta){
        if ((!useVectorMap) || (mapLibreMap==null)){
            return;
        }
        try {
            double currentZoom = mapLibreMap.getZoom();
            double targetZoom = clampVectorZoom(currentZoom+zoomDelta,mapLibreMap.getMinZoomLevel(),mapLibreMap.getMaxZoomLevel());
            mapLibreMap.animateCamera(CameraUpdateFactory.zoomTo(targetZoom));
            // Debug-only camera trace for emulator verification (stripped from release).
            if (BuildConfig.DEBUG){
                android.util.Log.d("TWFG_MAP67","zoom before="+currentZoom+" after="+targetZoom);
            }
            PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.INFO,
                    "TASK-67 vector zoom "+currentZoom+" -> "+targetZoom);
        } catch (Exception e){
            // Camera/native map not ready: fall back to the built-in relative updates.
            try {
                if (zoomDelta>0){
                    mapLibreMap.animateCamera(CameraUpdateFactory.zoomIn());
                } else {
                    mapLibreMap.animateCamera(CameraUpdateFactory.zoomOut());
                }
            } catch (Exception ignored){
                // A control tap must never take the activity down.
            }
        }
    }

    /** Google-Maps style locate: last known device position, station pin as fallback. */
    private void locateVectorMap(){
        if ((!useVectorMap) || (mapLibreMap==null)){
            return;
        }
        Location deviceLocation = null;
        if (WeatherLocationManager.hasLocationPermission(context)){
            try {
                deviceLocation = WeatherLocationManager.getLastKnownLocation(context);
            } catch (Exception e){
                deviceLocation = null;
            }
        }
        LatLng target;
        boolean stationFallback = false;
        if (deviceLocation!=null){
            target = new LatLng(deviceLocation.getLatitude(),deviceLocation.getLongitude());
        } else {
            Weather.WeatherLocation station = WeatherSettings.getSetStationLocation(context);
            if (station==null){
                target = new LatLng(VectorMapConfig.DEFAULT_CENTER_LATITUDE,VectorMapConfig.DEFAULT_CENTER_LONGITUDE);
            } else {
                target = new LatLng(station.latitude,station.longitude);
            }
            stationFallback = true;
        }
        double zoom = MAP_LOCATE_ZOOM;
        try {
            zoom = clampVectorZoom(zoom,mapLibreMap.getMinZoomLevel(),mapLibreMap.getMaxZoomLevel());
        } catch (Exception ignored){
            // Keep the default level if the native limits are unavailable yet.
        }
        mapLibreMap.animateCamera(CameraUpdateFactory.newLatLngZoom(target,zoom));
        Toast.makeText(this,stationFallback?R.string.map_locate_fallback:R.string.map_locate_device,
                Toast.LENGTH_SHORT).show();
        // Debug-only camera trace for emulator verification (stripped from release).
        if (BuildConfig.DEBUG){
            android.util.Log.d("TWFG_MAP67","locate lat="+target.getLatitude()+" lon="+target.getLongitude()
                    +" zoom="+zoom+" fallback="+stationFallback);
        }
        PrivateLog.log(context,PrivateLog.WARNINGS,PrivateLog.INFO,
                "TASK-67 vector locate lat="+target.getLatitude()+" lon="+target.getLongitude()
                        +" zoom="+zoom+" fallback="+stationFallback);
    }

    public void displayOsmNotice(){
        // Vector mode has its own attribution view (OpenFreeMap/OpenMapTiles/OSM).
        if (useVectorMap){
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (germany==null){
                    germany = (ImageView) findViewById(R.id.warningactivity_map);
                }
                // this is to display the osm notice properly in the bottom-right map corner as a textview aligned to the
                // right border of the bitmap inside the imageview.
                germany.post(new Runnable() {
                    @Override
                    public void run() {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // get the scale matrix from the imageview to calculate the bitmap size inside the imageview
                                float[] matrix = new float[9];
                                germany.getImageMatrix().getValues(matrix);
                                float scaledMapWidth=germany.getDrawable().getIntrinsicWidth()*matrix[Matrix.MSCALE_X];
                                float scaledMapHeight=germany.getDrawable().getIntrinsicHeight()*matrix[Matrix.MSCALE_Y];
                                WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                                DisplayMetrics displayMetrics = new DisplayMetrics();
                                windowManager.getDefaultDisplay().getMetrics(displayMetrics);
                                // calculate the offset of the textview
                                int marginRight = Math.round((displayMetrics.widthPixels-scaledMapWidth)/2);
                                // tweak offset for landscape mode
                                if (deviceIsLandscape){
                                    RelativeLayout warningactivityLeftcontainer = (RelativeLayout) findViewById(R.id.warningactivity_leftcontainer);
                                    marginRight = Math.round((warningactivityLeftcontainer.getWidth() - scaledMapWidth)/2);
                                }
                                // create the textview
                                TextView newTextView = new TextView(context);
                                // underline the text without the (c)
                                String osmString = context.getResources().getString(R.string.map_attribution);
                                if (WeatherSettings.appReleaseIsUserdebug()){
                                    osmString = osmString + " " +context.getResources().getString(R.string.map_attribution_link);
                                }
                                SpannableString spannableString = new SpannableString(osmString);
                                spannableString.setSpan(new UnderlineSpan(),2,spannableString.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
                                newTextView.setText(spannableString);
                                newTextView.setAutoLinkMask(1);
                                newTextView.setTextSize(12);
                                if (!WeatherSettings.appReleaseIsUserdebug()){
                                    newTextView.setTextSize(10);
                                }
                                newTextView.setVisibility(View.VISIBLE);
                                newTextView.setTextColor(ThemePicker.getColor(context,ThemePicker.ThemeColor.CYAN));
                                newTextView.setBackgroundColor(ThemePicker.getColor(context,ThemePicker.ThemeColor.PRIMARYLIGHT));
                                newTextView.setPadding(2,1,2,1);
                                RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                                layoutParams.setMargins(2,1,marginRight,1);
                                layoutParams.addRule(RelativeLayout.ALIGN_BOTTOM,R.id.warningactivity_map);
                                layoutParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
                                newTextView.setLayoutParams(layoutParams);
                                RelativeLayout warningactivity_mapcontainer = (RelativeLayout) findViewById(R.id.warningactivity_mapcontainer);
                                final int newTextViewId = View.generateViewId();
                                newTextView.setId(newTextViewId);
                                warningactivity_mapcontainer.addView(newTextView);
                                // make the textview clickable to open the license link
                                newTextView.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        final TextView newTV = (TextView) findViewById(newTextViewId);
                                        newTV.setOnClickListener(new View.OnClickListener() {
                                            @Override
                                            public void onClick(View view) {
                                                String osmUri = "https://openstreetmap.org/copyright";
                                                Intent openLicenseIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(osmUri));
                                                try {
                                                    startActivity(openLicenseIntent);
                                                } catch (ActivityNotFoundException e){
                                                    Toast.makeText(context,osmUri,Toast.LENGTH_LONG).show();
                                                }
                                            }
                                        });
                                        // make the textview disappear after 7 sec;
                                        // in debug builds, the textview does not disappear
                                        if (!WeatherSettings.appReleaseIsUserdebug()){
                                            newTV.postDelayed(new Runnable() {
                                                @Override
                                                public void run() {
                                                    runOnUiThread(new Runnable() {
                                                        @Override
                                                        public void run() {
                                                            newTV.setVisibility(View.GONE);
                                                            newTV.setOnClickListener(null);
                                                        }
                                                    });
                                                }
                                            },CREDENTIALS_FADE_TIME_DELAY);
                                        }
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });
    }

}

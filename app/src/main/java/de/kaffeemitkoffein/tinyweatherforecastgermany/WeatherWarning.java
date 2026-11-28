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

import android.content.Context;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.Map;

public class WeatherWarning implements Comparable<WeatherWarning> {

    public static class Severity{
        final static String MINOR = "Minor";
        final static String MODERATE = "Moderate";
        final static String SEVERE = "Severe";
        final static String EXTREME = "Extreme";

        final static int MINOR_INT = 1;
        final static int MODERATE_INT = 2;
        final static int SEVERE_INT = 3;
        final static int EXTREME_INT = 4;

        public static int toInt(String value){
            if (value.equals(MINOR)){
                return MINOR_INT;
            }
            if (value.equals(MODERATE)){
                return MODERATE_INT;
            }
            if (value.equals(SEVERE)){
                return SEVERE_INT;
            }
            if (value.equals(EXTREME)){
                return EXTREME_INT;
            }
            return 0;
        }

        /*
        Warning colors according to https://www.dwd.de/EN/weather/warnings/warnings_node.html
        level 1                       : ffeb3b
        level 2                       : fb8c00
        level 3                       : e53935
        level 4                       : 880e4f
        heat alert                    : cc99ff
        ultraviolet radiation warning : fe68fe
         */

        final static int[] colors = new int[]{0xffffeb3b,0xfffb8c00,0xffe53935,0xff880e4f,0xffcc99ff,0xfffe68fe};

        public static int GetColorHue(int color){
            float hsv[] = new float[3];
            Color.colorToHSV(color,hsv);
            return Math.round(hsv[0]);
        }
    }

    public final static String ID_UPDATE="Update";
    public final static String ID_SILENT_UPDATE="SILENT_UPDATE";

    // TASK-77: provider ownership. Defaults keep every legacy/DWD warning on the
    // unchanged German path; international providers set these explicitly.
    String provider_id = WarningProvider.ID_DWD;
    String provider_key = WarningProvider.ID_DWD;
    String warning_level;

    long polling_time;
    String identifier;      // id of the warning
    String sender;          // sender, usually "opendata@dwd.de"
    long sent;              // time of issuing in UTC
    String status;          // status, e.g. "Actual"
    String msgType;         // e.g. "Update"
    String source;          // local source of warning
    String scope;           // only known value at opendata is "Public", but indicates there may be others
    ArrayList<String> codes;
    ArrayList<String> references; // id of reference
    String language;
    String category;
    String event;
    String responseType;
    String urgency;
    String severity;
    String certainty;
    long effective = 0;
    long onset = 0;
    long expires = 0;
    String senderName;
    String headline;
    String description;
    String instruction;
    String web;
    String contact;
    String profile_version;
    String license;
    String ii;
    ArrayList<String> groups;
    String area_color;
    ArrayList<String> parameter_names;
    ArrayList<String> parameter_values;
    ArrayList<String> polygons;
    ArrayList<String> excluded_polygons;
    ArrayList<String> area_names;
    ArrayList<String> area_warncellIDs;

    ArrayList<Polygon> polygonlist;
    ArrayList<Polygon> excluded_polygonlist;

    public void initPolygons(Context context){
        polygonlist = new ArrayList<Polygon>();
        excluded_polygonlist = new ArrayList<Polygon>();
        if (polygons!=null){
            for (int j=0; j<polygons.size(); j++){
                Polygon polygon = new Polygon(polygons.get(j));
                polygonlist.add(polygon);
            }
            if (excluded_polygons!=null){
                for (int j=0; j<excluded_polygons.size(); j++){
                    Polygon polygon = new Polygon(excluded_polygons.get(j));
                    excluded_polygonlist.add(polygon);
                }
            }
        }
        // TASK-77: the warncell->polygon database is Germany-specific. A foreign area code
        // must never be fed into it: that would invent a shape on the map.
        boolean dwdProvider = (provider_id == null) || WarningProvider.ID_DWD.equals(provider_id);
        if ((polygonlist.size()==0) && dwdProvider){
            ArrayList<Areas.Area> areas = Areas.getAreas(context,area_warncellIDs);
            for (int i=0; i<areas.size(); i++){
                polygonlist.addAll(areas.get(i).polygons);
            }
        }
    }

    public boolean isInPolygonGeo(float testy, float testx){
        if (polygonlist==null){
            return false;
        }
        if (polygonlist.size()==0){
            return false;
        }
        // return false if point is in excluded polygon; it is efficient to check this first.
        for (int j=0; j<excluded_polygonlist.size(); j++){
            Polygon polygon = excluded_polygonlist.get(j);
            if (polygon.isInPolygon(testx,testy)){
                return false;
            }
        }
        // return true if point is in polygon
        for (int j=0; j<polygonlist.size(); j++){
            Polygon polygon = polygonlist.get(j);
            if (polygon.isInPolygon(testx,testy)){
                return true;
            }
        }
        // otherwise false
        return false;
    }

    public boolean isInPolygonGeo(Weather.WeatherLocation weatherLocation){
        return isInPolygonGeo((float) weatherLocation.latitude,(float) weatherLocation.longitude);
    }

    public int getWarningColor(){
        String[] colors = area_color.trim().split("\\s+");
        int result = Color.rgb(Integer.parseInt(colors[0]),Integer.parseInt(colors[1]),Integer.parseInt(colors[2]));
        return result;
    }

    /**
     * Checks if this warning is an update of a previously issued warning.
     *
     * @return true if the warning is an update
     */

    public boolean isUpdate(){
        return msgType.equalsIgnoreCase(ID_UPDATE);
    }

    public boolean isSilentUpdate(){
        for (int i=0; i<codes.size(); i++){
            if (codes.get(i).equalsIgnoreCase(ID_SILENT_UPDATE)){
                return true;
            }
        }
        return false;
    }

    public boolean hasReferenceID(String referenceID){
        for (int i=0; i<references.size(); i++){
            if (references.get(i).equalsIgnoreCase(referenceID)){
                return true;
            }
        }
        return false;
    }

    public int getSeverity(){
        if (severity!=null){
            return Severity.toInt(severity);
        }
        return 0;
    }

    /*
    public void outputToLog(){
        Log.v(Tag.WARNINGS,"====================================================");
        Log.v(Tag.WARNINGS,"Identifier: "+identifier);
        Log.v(Tag.WARNINGS, "Sender: "+sender);
        Log.v(Tag.WARNINGS,"Sent: "+sent);
        Log.v(Tag.WARNINGS, "Status: "+status);
        Log.v(Tag.WARNINGS, "MsgType: "+msgType);
        Log.v(Tag.WARNINGS, "Source: "+source);
        Log.v(Tag.WARNINGS, "Scopa: "+scope);
        Log.v(Tag.WARNINGS, "Codes: "+codes.size());
        for (int i=0; i<references.size(); i++){
            Log.v(Tag.WARNINGS,"Ref #"+i+": "+references.get(i));
        }
        Log.v(Tag.WARNINGS, "Language: "+language);
        Log.v(Tag.WARNINGS, "Category: "+category);
        Log.v(Tag.WARNINGS, "Event: "+event);
        Log.v(Tag.WARNINGS, "ResponseType: "+responseType);
        Log.v(Tag.WARNINGS, "Urgency: "+urgency);
        Log.v(Tag.WARNINGS, "Severity: "+severity);
        Log.v(Tag.WARNINGS, "Certainty: "+certainty);
        Log.v(Tag.WARNINGS, "Effective: "+effective);
        Log.v(Tag.WARNINGS, "Onset    : "+onset);
        Log.v(Tag.WARNINGS, "Expires  : "+expires);
        Log.v(Tag.WARNINGS, "SenderName: "+senderName);
        Log.v(Tag.WARNINGS, "Headline: "+headline);
        Log.v(Tag.WARNINGS, "Description: "+description);
        Log.v(Tag.WARNINGS, "Instruction: "+instruction);
        Log.v(Tag.WARNINGS, "Web: "+web);
        Log.v(Tag.WARNINGS, "Contact: "+contact);
        Log.v(Tag.WARNINGS, "Groups     #: "+groups.size());
        Log.v(Tag.WARNINGS, "Paramters  #: "+parameter_names.size());
        Log.v(Tag.WARNINGS, "Values     #: "+parameter_values.size());
        Log.v(Tag.WARNINGS, "Polygons   #: "+polygons.size());
        Log.v(Tag.WARNINGS, "(-)Polygons#: "+excluded_polygons.size());
        Log.v(Tag.WARNINGS, "Cities     #: "+area_names.size());
        Log.v(Tag.WARNINGS, "WarnCellID #: "+area_warncellIDs.size());
    }

     */

    public static String getUnderlineString(String source, String underlineChar){
        if (source==null){
            return null;
        } else {
            StringBuilder stringBuilder = new StringBuilder();
            for (int i=0; i<source.length(); i++){
                stringBuilder.append(underlineChar);
            }
            return stringBuilder.toString();
        }
    }

    public String getPlainTextWarning(Context context, boolean includeCredentials){
        String newLine = System.getProperty("line.separator");
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(WeatherSettings.getSetStationLocation(context).getDescription(context).toUpperCase(Locale.getDefault()));
        stringBuilder.append(": ");
        stringBuilder.append(this.description);
        stringBuilder.append(newLine);
        stringBuilder.append(newLine);
        stringBuilder.append(WeatherWarningAdapter.formatTime(this.effective)); stringBuilder.append(" ");
        stringBuilder.append(this.status);
        stringBuilder.append(" ");
        stringBuilder.append(this.msgType);
        stringBuilder.append(newLine);
        stringBuilder.append(this.event.toUpperCase(Locale.getDefault()));
        stringBuilder.append(newLine);
        stringBuilder.append(getUnderlineString(this.event,"="));
        stringBuilder.append(newLine);
        stringBuilder.append(WeatherWarningAdapter.formatTime(this.onset)); stringBuilder.append(" ");
        if (this.expires!=0){
            stringBuilder.append(context.getResources().getString(R.string.warnings_until)); stringBuilder.append(" ");
            stringBuilder.append(WeatherWarningAdapter.formatTime(this.expires));
        }
        stringBuilder.append(newLine);
        stringBuilder.append(this.urgency);  stringBuilder.append(" ");
        stringBuilder.append(this.severity); stringBuilder.append(" ");
        stringBuilder.append(this.certainty);
        stringBuilder.append(newLine);
        stringBuilder.append("> ");
        boolean limitLocationsInWarnings = true;
        int max_locations = WeatherSettings.getMaxLocationsInSharedWarnings(context);
        int locationCount = 0;
        // if zero, do not limit location count but set to arraylist length
        if (max_locations == 0){
            limitLocationsInWarnings = false;
            max_locations = area_names.size();
        }
        for (int i=0; ((i<this.area_names.size()) && (i<max_locations)); i++){
            stringBuilder.append(this.area_names.get(i));
            if (i<this.area_names.size()-1){
                stringBuilder.append(", ");
            }
            locationCount++;
        }
        if ((limitLocationsInWarnings) && (locationCount<this.area_names.size())){
            stringBuilder.append("…");
        }
        stringBuilder.append(newLine); stringBuilder.append(newLine);
        stringBuilder.append(this.headline);
        stringBuilder.append(newLine);
        stringBuilder.append(getUnderlineString(this.headline,"="));
        stringBuilder.append(newLine);
        stringBuilder.append(newLine);
        // Instruction field is optional per CAP (Common Alerting Protocol) standard
        // Only append if present and non-empty to avoid NullPointerException
        if (this.instruction != null && this.instruction.trim().length()>0){
            stringBuilder.append(this.instruction);
            stringBuilder.append(newLine); stringBuilder.append(newLine);
        }
        if ((this.parameter_names!=null) && (this.parameter_values!=null)){
            for (int i=0; i<this.parameter_names.size() && i<this.parameter_values.size(); i++){
                stringBuilder.append(this.parameter_names.get(i)); stringBuilder.append(": ");
                stringBuilder.append(this.parameter_values.get(i)); stringBuilder.append(newLine);
            }
            if (this.parameter_names.size()>0){
                stringBuilder.append(newLine);
            }
        }
        if (includeCredentials){
            stringBuilder.append(context.getResources().getString(R.string.dwd_warnings_notice));
        }
        return stringBuilder.toString();
    }

    /*
     * Warnings are sorted by severity and timestamp.
     */

    @Override
    public int compareTo(WeatherWarning w) {
        if ((Severity.toInt(this.severity) == Severity.toInt(w.severity))){
            if ((this.effective==0)||(w.effective==0)){
                // when one of the objects has no effective time stamp, they are regarded "equal"
                return 0;
            }
            if (this.effective<w.effective){
                return -1;
            }
            if (this.effective> w.effective){
                return 1;
            }
        } else if (Severity.toInt(this.severity) > Severity.toInt(w.severity)){
            return -1;
        } else if (Severity.toInt(this.severity) < Severity.toInt(w.severity)){
            return 1;
        }
        return 0;
    }

    // some warnings may have no expiry date, we need to put fictional ones in for graphs & calculations if this
    // warning applies

    public long getApplicableExpires(){
        if (expires==0){
            if (onset!=0){
                return onset + 1036800000L;  // plus 12 days in millis
            } else {
                return Calendar.getInstance().getTimeInMillis() + 1036800000L;
            }
        } else {
            return expires;
        }
    }

    /**
     * A warning whose applicable expiry has passed must never be announced again.
     * The explicit "now" parameter keeps this decision testable.
     */
    public boolean isExpired(long now){
        return getApplicableExpires() < now;
    }

    public boolean isExpired(){
        return isExpired(Calendar.getInstance().getTimeInMillis());
    }

    /**
     * Persisted announcement state for warning events (Codeberg #308).
     *
     * The legacy notification list in WeatherWarnings is cleared on boot and every 12 hours,
     * which made already-announced warnings reappear with a new notification id. This state
     * lives in the app's default SharedPreferences (WeatherSettings.getSharedPreferences)
     * under keys defined here, so WeatherSettings.java and the boot receiver do not need changes:
     *
     *   key:   weather_warning_alert_state:&lt;event key&gt;
     *   value: &lt;stable notification id&gt;:&lt;expiry in millis&gt;
     *
     * The event key is the CAP identifier (with a deterministic fallback when it is missing).
     * Presence of an entry means "announced": a user who dismisses the notification does not
     * get it again on the next start, and the stored id keeps later notifications stable.
     */
    public static final class AlertNotificationState {

        public static final String PREF_KEY_PREFIX = "weather_warning_alert_state:";
        public static final int INVALID_NOTIFICATION_ID = Integer.MIN_VALUE;
        private static final String VALUE_SEPARATOR = ":";

        private AlertNotificationState() {
        }

        public static String eventKey(WeatherWarning warning){
            if ((warning.identifier!=null) && (warning.identifier.length()>0)){
                return warning.identifier;
            }
            // Fallback for malformed CAP messages: still stable across app restarts.
            return String.valueOf(warning.event)+VALUE_SEPARATOR+warning.sent+VALUE_SEPARATOR+warning.onset+VALUE_SEPARATOR+warning.effective;
        }

        public static String prefKey(WeatherWarning warning){
            return PREF_KEY_PREFIX+eventKey(warning);
        }

        public static String encode(int notificationId, long expires){
            return notificationId+VALUE_SEPARATOR+expires;
        }

        public static int notificationId(String encoded){
            if (encoded==null){
                return INVALID_NOTIFICATION_ID;
            }
            int separator = encoded.indexOf(VALUE_SEPARATOR);
            if (separator<=0){
                return INVALID_NOTIFICATION_ID;
            }
            try {
                return Integer.parseInt(encoded.substring(0,separator));
            } catch (NumberFormatException e){
                return INVALID_NOTIFICATION_ID;
            }
        }

        public static long expiry(String encoded){
            if (encoded==null){
                return 0;
            }
            int separator = encoded.indexOf(VALUE_SEPARATOR);
            if ((separator<0) || (separator==encoded.length()-1)){
                return 0;
            }
            try {
                return Long.parseLong(encoded.substring(separator+1));
            } catch (NumberFormatException e){
                return 0;
            }
        }

        /**
         * Records an announcement without ever replacing an existing, valid entry.
         */
        public static void record(Map<String,String> state, WeatherWarning warning, int notificationId, long expires){
            String key = prefKey(warning);
            if ((!state.containsKey(key)) || (notificationId(state.get(key))==INVALID_NOTIFICATION_ID)){
                state.put(key,encode(notificationId,expires));
            }
        }

        public static boolean isExpired(WeatherWarning warning, long now){
            return warning.isExpired(now);
        }

        /**
         * True only for a warning that has not expired and has not been announced before.
         * Corrupt state is treated like no state so it can be repaired with a stable id.
         */
        public static boolean shouldAnnounce(WeatherWarning warning, long now, Map<String,String> state){
            if (isExpired(warning,now)){
                return false;
            }
            String key = prefKey(warning);
            return (!state.containsKey(key)) || (notificationId(state.get(key))==INVALID_NOTIFICATION_ID);
        }
    }

}

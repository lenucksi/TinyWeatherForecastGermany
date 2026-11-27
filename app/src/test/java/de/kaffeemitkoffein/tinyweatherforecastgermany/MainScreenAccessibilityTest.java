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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TASK-34: the main screens must be usable with TalkBack and a keyboard. The checks read the
 * layout sources directly so they also cover the density/qualifier variants that have no
 * instrumentation test on this project.
 */
public class MainScreenAccessibilityTest {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    private static final String[] MAIN_LAYOUTS = {
            "src/main/res/layout/activity_main.xml",
            "src/main/res/layout-w600dp-land/activity_main.xml",
            "src/main/res/layout/actionbar.xml",
            "src/main/res/layout/actionbar_weatherdetails.xml",
            "src/main/res/layout/actionbar_textforecastview.xml",
            "src/main/res/layout/activity_overviewchart_legend.xml",
            "src/main/res/layout/activity_textforecastlist.xml",
            "src/main/res/layout/activity_textforecastview.xml",
            "src/main/res/layout/activity_weatherdetails.xml",
            "src/main/res/layout-w600dp-land/activity_weatherdetails.xml",
            "src/main/res/layout/activity_weatherwarning.xml",
            "src/main/res/layout-w600dp-land/activity_weatherwarning.xml",
            "src/main/res/layout/warning_item.xml",
            "src/main/res/layout/activity_weatherlayers.xml",
            "src/main/res/layout/activity_weatherlayermap.xml",
            "src/main/res/layout-w600dp-land/activity_weatherlayermap.xml",
            "src/main/res/layout/forecastitem.xml",
            "src/main/res/layout/forecastitem2.xml",
            "src/main/res/layout-sw320dp/forecastitem.xml",
            "src/main/res/layout-sw320dp/forecastitem2.xml",
            "src/main/res/layout-sw400dp/forecastitem.xml",
            "src/main/res/layout-sw400dp/forecastitem2.xml",
            "src/main/res/layout/detailslistitem.xml",
            "src/main/res/layout/detailslistitem2.xml",
            "src/main/res/layout/detailslistitem3.xml",
            "src/main/res/layout-sw320dp/detailslistitem.xml",
            "src/main/res/layout-sw320dp/detailslistitem2.xml",
            "src/main/res/layout-sw320dp/detailslistitem3.xml",
            "src/main/res/layout-sw400dp/detailslistitem.xml",
            "src/main/res/layout-sw400dp/detailslistitem2.xml",
            "src/main/res/layout-sw400dp/detailslistitem3.xml",
            "src/main/res/layout/horizontalinfoitem.xml",
            "src/main/res/layout-w600dp-land/horizontalinfoitem.xml",
            "src/main/res/layout/textforecast_item.xml"
    };

    /** Icon-only controls: they need a spoken name (not @null) and a keyboard focusable view. */
    private static final String[] NAMED_CONTROLS = {
            "main_reset_favorites", "main_overview_chart", "main_overviewchart_legend",
            "actionbar_search_icon", "fcitem_forecastbar", "fcitem_warningsymbol",
            "warning_item_shareicon", "closeicon_map", "textforecasts_circlefloat",
            "dl_chart"
    };

    @Test
    public void everyImageViewOnMainScreensDeclaresTalkBackBehaviour() throws Exception {
        for (String path : MAIN_LAYOUTS) {
            Document doc = parse(path);
            NodeList views = doc.getElementsByTagName("ImageView");
            views = merge(views, doc.getElementsByTagName("ImageButton"));
            for (int i = 0; i < views.getLength(); i++) {
                Element view = (Element) views.item(i);
                assertTrue(path + ": " + id(view) + " needs contentDescription (use @null when decorative)",
                        view.hasAttributeNS(ANDROID, "contentDescription"));
            }
        }
    }

    @Test
    public void iconOnlyControlsHaveSpokenNames() throws Exception {
        Map<String, String> found = new HashMap<String, String>();
        for (String path : MAIN_LAYOUTS) {
            for (Element view : elements(path)) {
                String id = id(view);
                if (id != null && Arrays.asList(NAMED_CONTROLS).contains(id)) {
                    String description = attr(view, "contentDescription");
                    assertTrue(path + ": " + id + " must not be hidden from TalkBack",
                            description != null && description.startsWith("@string/"));
                    found.put(id, description);
                }
            }
        }
        assertEquals("@string/legend_open", found.get("main_overview_chart"));
        assertEquals("@string/legend_open", found.get("main_overviewchart_legend"));
        assertEquals("@string/a11y_forecast_bar", found.get("fcitem_forecastbar"));
        assertEquals("@string/a11y_reset_favorites", found.get("main_reset_favorites"));
        assertEquals("@string/a11y_search", found.get("actionbar_search_icon"));
    }

    @Test
    public void interactiveTargetsAreAtLeast48dp() throws Exception {
        // id -> attribute that carries the minimum touch size.
        String[][] targets = {
                {"main_reset_favorites", "minWidth"}, {"main_reset_favorites", "minHeight"},
                {"main_overviewchart_legend", "layout_width"}, {"main_overviewchart_legend", "layout_height"},
                {"cancel_gps", "layout_height"}, {"cancel_gps", "minHeight"},
                {"main_nodata_button", "minHeight"},
                {"actionbar_search_icon", "minWidth"}, {"actionbar_search_icon", "minHeight"},
                {"actionbar_textview", "minHeight"},
                {"warning_item_shareicon", "layout_width"}, {"warning_item_shareicon", "layout_height"},
                {"textforecasts_circlefloat", "minWidth"}, {"textforecasts_circlefloat", "minHeight"},
                {"legend_button_back", "minHeight"}
        };
        for (String[] target : targets) {
            boolean found = false;
            for (String path : MAIN_LAYOUTS) {
                for (Element view : elements(path)) {
                    if (target[0].equals(id(view))) {
                        found = true;
                        String value = attr(view, target[1]);
                        assertTrue(path + ": " + target[0] + " " + target[1] + " must be >=48dp, was " + value,
                                value != null && dp(value) >= 48f);
                    }
                }
            }
            assertTrue("no layout defines " + target[0], found);
        }
    }

    @Test
    public void textSizesUseSpAndSurviveFontScale() throws Exception {
        String dimens = read("src/main/res/values/dimens.xml");
        Map<String, String> units = new HashMap<String, String>();
        Matcher matcher = Pattern.compile("<dimen name=\"([^\"]+)\">[^<]*?(dp|sp|dip)</dimen>").matcher(dimens);
        while (matcher.find()) {
            units.put(matcher.group(1), matcher.group(2));
        }
        for (String path : MAIN_LAYOUTS) {
            for (Element view : elements(path)) {
                String textSize = attr(view, "textSize");
                if (textSize == null) {
                    continue;
                }
                assertFalse(path + ": hard-coded dp text size on " + id(view),
                        textSize.endsWith("dp") || textSize.endsWith("dip"));
                if (textSize.startsWith("@dimen/")) {
                    String name = textSize.substring("@dimen/".length());
                    assertFalse(path + ": fixed text size on " + id(view), name.endsWith("_fixed"));
                    assertEquals(path + ": " + id(view) + " -> " + textSize, "sp", units.get(name));
                } else {
                    assertTrue(path + ": literal text size must be sp", textSize.endsWith("sp"));
                }
            }
        }
    }

    @Test
    public void keyboardFocusOrderIsDeclaredOnMainScreens() throws Exception {
        assertFocus("layout/activity_main.xml", "stations_spinner", "nextFocusForward", "@id/main_reset_favorites");
        // TASK-61: the legend info icon now lives beside the X in the control row, so
        // keyboard focus follows the visual order: X -> info -> chart -> list.
        assertFocus("layout/activity_main.xml", "main_reset_favorites", "nextFocusForward", "@id/main_overviewchart_legend");
        assertFocus("layout/activity_main.xml", "main_overviewchart_legend", "nextFocusForward", "@id/main_overview_chart");
        assertFocus("layout/activity_main.xml", "main_overview_chart", "nextFocusForward", "@id/main_listview");
        assertFocus("layout-w600dp-land/activity_main.xml", "main_reset_favorites", "nextFocusForward", "@id/main_overviewchart_legend");
        assertFocus("layout-w600dp-land/activity_main.xml", "main_overviewchart_legend", "nextFocusRight", "@id/main_overview_chart");
        assertFocus("layout-w600dp-land/activity_main.xml", "main_overview_chart", "nextFocusRight", "@id/main_listview");
        assertFocus("layout/actionbar.xml", "actionbar_textview", "nextFocusForward", "@id/actionbar_search_icon");
        assertFocus("layout/actionbar.xml", "actionbar_search_icon", "nextFocusForward", "@id/actionbar_textview");
        assertFocus("layout/activity_overviewchart_legend.xml", "legend_scrollview", "nextFocusForward", "@id/legend_button_back");
        assertFocus("layout/activity_overviewchart_legend.xml", "legend_button_back", "nextFocusUp", "@id/legend_scrollview");
    }

    @Test
    public void chartTextualEquivalentIsTheExistingLegend() throws Exception {
        for (String relative : new String[]{"layout/activity_main.xml", "layout-w600dp-land/activity_main.xml"}) {
            String path = "src/main/res/" + relative;
            Element chart = viewWithId(path, "main_overview_chart");
            assertEquals(path, "@string/legend_open", attr(chart, "contentDescription"));
            Element info = viewWithId(path, "main_overviewchart_legend");
            assertEquals(path, "@string/legend_open", attr(info, "contentDescription"));
        }
        String legend = read("src/main/res/layout/activity_overviewchart_legend.xml");
        assertTrue("legend rows are real text, not another image", legend.contains("@+id/legend_items"));
        assertTrue(legend.contains("@string/legend_sample_caption"));
        String english = read("src/main/res/values/strings.xml");
        String german = read("src/main/res/values-de/strings.xml");
        for (String key : new String[]{"legend_open", "legend_title", "legend_temp_above", "legend_rain_prob"}) {
            assertTrue("missing " + key, english.contains("name=\"" + key + "\""));
            assertTrue("missing German " + key, german.contains("name=\"" + key + "\""));
        }
        for (String key : new String[]{"a11y_forecast_bar", "a11y_reset_favorites", "a11y_search", "a11y_chart_data"}) {
            assertTrue("missing " + key, english.contains("name=\"" + key + "\""));
            assertTrue("missing German " + key, german.contains("name=\"" + key + "\""));
        }
    }

    private static void assertFocus(String relativeLayout, String viewId, String attribute, String expected)
            throws Exception {
        String path = "src/main/res/" + relativeLayout;
        Element view = viewWithId(path, viewId);
        assertEquals(path + ": " + viewId + "." + attribute, expected, attr(view, attribute));
        assertTrue(path + ": " + viewId + " must be keyboard reachable",
                "true".equals(attr(view, "focusable")) || view.getTagName().equals("Spinner")
                        || view.getTagName().equals("AutoCompleteTextView")
                        || view.getTagName().equals("ListView")
                        || view.getTagName().equals("Button")
                        || view.getTagName().equals("ScrollView"));
    }

    private static Element viewWithId(String path, String viewId) throws Exception {
        for (Element view : elements(path)) {
            if (viewId.equals(id(view))) {
                return view;
            }
        }
        throw new AssertionError(path + ": no view with id " + viewId);
    }

    private static List<Element> elements(String path) throws Exception {
        List<Element> result = new ArrayList<Element>();
        NodeList all = parse(path).getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            result.add((Element) all.item(i));
        }
        return result;
    }

    private static NodeList merge(NodeList first, NodeList second) {
        List<Element> merged = new ArrayList<Element>();
        for (int i = 0; i < first.getLength(); i++) {
            merged.add((Element) first.item(i));
        }
        for (int i = 0; i < second.getLength(); i++) {
            merged.add((Element) second.item(i));
        }
        return new org.w3c.dom.NodeList() {
            public int getLength() { return merged.size(); }
            public org.w3c.dom.Node item(int index) { return merged.get(index); }
        };
    }

    private static Document parse(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new File(path));
    }

    private static String id(Element element) {
        String value = attr(element, "id");
        return value == null ? null : value.replace("@+id/", "").replace("@id/", "");
    }

    private static String attr(Element element, String name) {
        if (!element.hasAttributeNS(ANDROID, name)) {
            return null;
        }
        return element.getAttributeNS(ANDROID, name);
    }

    private static float dp(String value) {
        Matcher matcher = Pattern.compile("([0-9.]+)dp").matcher(value);
        return matcher.matches() ? Float.parseFloat(matcher.group(1)) : 0f;
    }

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}

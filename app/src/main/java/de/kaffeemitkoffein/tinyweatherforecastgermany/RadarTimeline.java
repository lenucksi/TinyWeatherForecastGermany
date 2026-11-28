/*
 * This file is part of TinyWeatherForecastGermany.
 *
 * Copyright (c) 2026 Pawel Dube
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.kaffeemitkoffein.tinyweatherforecastgermany;

/**
 * TASK-90: an absolute epoch-millis frame model for the selected radar window.
 *
 * <p>Every frame has a real UTC timestamp instead of a 0..23 index. The SeekBar therefore
 * maps to an absolute time and always clamps at the ends; scrubbing past the last frame never
 * wraps around to the first one. The DWD reference time from GetCapabilities is the only
 * "now" used by the playback-start options.</p>
 *
 * <p>Pure Java on purpose so the arithmetic is unit-testable without a device.</p>
 */
public final class RadarTimeline {

    /** Upper bound for a defensively capped full-range timeline (about 7 days at 5 minutes). */
    public static final int MAX_FRAMES = 2048;

    public final long startMillis;
    public final long endMillis;
    public final long stepMillis;
    /** DWD-provided anchor; frames at or after it are forecast frames. */
    public final long anchorMillis;
    public final int frameCount;

    private final long[] frames;

    private RadarTimeline(long startMillis, long endMillis, long stepMillis, long anchorMillis) {
        long alignedStart = alignUp(startMillis, stepMillis);
        long alignedEnd = alignDown(endMillis, stepMillis);
        if (alignedEnd < alignedStart) {
            alignedEnd = alignedStart;
        }
        long rawCount = Math.floorDiv(alignedEnd - alignedStart, stepMillis) + 1L;
        int count = (int) Math.max(0L, Math.min(rawCount, MAX_FRAMES));
        long[] values = new long[count];
        for (int i = 0; i < count; i++) {
            values[i] = alignedStart + (long) i * stepMillis;
        }
        this.startMillis = alignedStart;
        this.endMillis = count > 0 ? values[count - 1] : alignedStart;
        this.stepMillis = stepMillis;
        this.anchorMillis = anchorMillis;
        this.frames = values;
        this.frameCount = count;
    }

    private static long alignUp(long timeMillis, long stepMillis) {
        return Math.ceilDiv(timeMillis, stepMillis) * stepMillis;
    }

    private static long alignDown(long timeMillis, long stepMillis) {
        return Math.floorDiv(timeMillis, stepMillis) * stepMillis;
    }

    /**
     * Builds a timeline from capabilities metadata and a stored window setting
     * ({@link WeatherSettings.Radar#WINDOW_2H}, {@code WINDOW_24H} or {@code WINDOW_FULL}).
     */
    public static RadarTimeline create(RadarCapabilities.Window window, String windowSetting) {
        if (window == null) {
            RadarCapabilities.Window fallback = RadarCapabilities.heuristic(
                    WeatherSettings.Radar.MILLIS_PER_DAY);
            window = fallback;
        }
        long anchor = window.anchorMillis;
        long from;
        long to;
        if (WeatherSettings.Radar.WINDOW_2H.equals(windowSetting)) {
            from = anchor - 2L * 60L * 60L * 1000L;
            to = anchor + 2L * 60L * 60L * 1000L;
        } else if (WeatherSettings.Radar.WINDOW_24H.equals(windowSetting)) {
            from = anchor - WeatherSettings.Radar.MILLIS_PER_DAY;
            to = anchor + 2L * 60L * 60L * 1000L;
        } else {
            from = window.startMillis;
            to = window.endMillis;
        }
        // Never leave the capabilities range: it is the only trusted source of frames.
        from = Math.max(from, window.startMillis);
        to = Math.min(to, window.endMillis);
        if (to < from) {
            // A very narrow server window still yields one honest frame.
            from = window.startMillis;
            to = window.endMillis;
        }
        return new RadarTimeline(from, to, window.stepMillis, anchor);
    }

    /** Number of frames in a window, without allocating the timeline. */
    public static int frameCountFor(RadarCapabilities.Window window, String windowSetting) {
        return create(window, windowSetting).frameCount;
    }

    public int clampIndex(int index) {
        if (frameCount <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(index, frameCount - 1));
    }

    /** Absolute timestamp of a frame; out-of-range indices are clamped, never wrapped. */
    public long timeForIndex(int index) {
        if (frameCount <= 0) {
            return 0L;
        }
        return frames[clampIndex(index)];
    }

    /** Index of the frame at (or nearest to) an absolute timestamp; always clamped. */
    public int indexForTime(long timeMillis) {
        if (frameCount <= 0) {
            return 0;
        }
        if (timeMillis <= frames[0]) {
            return 0;
        }
        if (timeMillis >= frames[frameCount - 1]) {
            return frameCount - 1;
        }
        long index = Math.floorDiv(timeMillis - frames[0], stepMillis);
        int lower = clampIndex((int) index);
        long lowerTime = frames[lower];
        if (timeMillis - lowerTime <= stepMillis / 2L) {
            return lower;
        }
        return clampIndex(lower + 1);
    }

    /** True for frames belonging to the current DWD forecast run. */
    public boolean isForecast(long timeMillis) {
        return timeMillis >= anchorMillis;
    }

    /**
     * Resolves a playback-start option against the DWD anchor, never the device clock.
     * The resulting instant is then mapped to the nearest frame and clamped into the window.
     */
    public long resolvePlaybackStartMillis(String option) {
        return WeatherSettings.Radar.resolvePlaybackStartMillis(option, anchorMillis, startMillis);
    }

    public int playbackStartIndex(String option) {
        return indexForTime(resolvePlaybackStartMillis(option));
    }

    /** Maps a 0..max SeekBar progress to a frame index; the last slice stays on the last frame. */
    public int indexForProgress(int progress, int max) {
        if (frameCount <= 0 || max <= 0) {
            return 0;
        }
        int clamped = Math.max(0, Math.min(progress, max));
        long scaled = (long) clamped * frameCount / max;
        return clampIndex((int) scaled);
    }

    /** True when both instants fall on the same local calendar day. */
    public static boolean isSameLocalDay(long firstMillis, long secondMillis) {
        java.util.Calendar first = java.util.Calendar.getInstance();
        java.util.Calendar second = java.util.Calendar.getInstance();
        first.setTimeInMillis(firstMillis);
        second.setTimeInMillis(secondMillis);
        return first.get(java.util.Calendar.YEAR) == second.get(java.util.Calendar.YEAR)
                && first.get(java.util.Calendar.DAY_OF_YEAR)
                == second.get(java.util.Calendar.DAY_OF_YEAR);
    }

    /** Inverse of {@link #indexForProgress(int, int)}; each frame owns an equal slice. */
    public int progressForIndex(int index, int max) {
        if (frameCount <= 0 || max <= 0) {
            return 0;
        }
        int clamped = clampIndex(index);
        long progress = ((long) clamped * max) / frameCount;
        return (int) Math.max(0L, Math.min(progress, max));
    }

    /**
     * Endpoint-exact SeekBar mapping: the first frame is 0 and the last frame is max.
     * This keeps the thumb aligned with the caption at both ends (no apparent off-by-one).
     */
    public int progressForIndexExact(int index, int max) {
        if (frameCount <= 1 || max <= 0) {
            return 0;
        }
        int clamped = clampIndex(index);
        long progress = Math.round((double) clamped * max / (frameCount - 1));
        return (int) Math.max(0L, Math.min(progress, max));
    }

    /** Inverse of {@link #progressForIndexExact(int, int)}; always clamped, never wrapped. */
    public int indexForProgressExact(int progress, int max) {
        if (frameCount <= 0 || max <= 0) {
            return 0;
        }
        int clamped = Math.max(0, Math.min(progress, max));
        long index = Math.round((double) clamped * (frameCount - 1) / max);
        return clampIndex((int) index);
    }

    /** Absolute timestamp selected by a SeekBar position. */
    public long timeForProgress(int progress, int max) {
        return timeForIndex(indexForProgress(progress, max));
    }

    /** SeekBar position of an absolute timestamp. */
    public int progressForTime(long timeMillis, int max) {
        return progressForIndex(indexForTime(timeMillis), max);
    }
}

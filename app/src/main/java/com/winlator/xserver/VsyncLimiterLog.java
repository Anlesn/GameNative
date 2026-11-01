package com.winlator.xserver;

import java.util.Locale;

// Logs which pacing mode a frame limiter runs in (one line per mode change,
// normally once per session) and how many released frames missed their vsync
// slot (a summary every REPORT_INTERVAL_NS and when the mode changes or the
// limiter is disarmed). Logcat tag: FrameLimiter.
public final class VsyncLimiterLog {
    private static final String TAG = "FrameLimiter";
    private static final long REPORT_INTERVAL_NS = 60_000_000_000L;

    private final String source;
    private int lastFps = -1;
    private int lastVsyncs = -1;
    private long lastHzRounded = -1;
    private long locked = 0;
    private long fallback = 0;
    private long misses = 0;
    private long lastReportNs = 0;

    public VsyncLimiterLog(String source) {
        this.source = source;
    }

    // One scheduled frame: vsyncs > 0 when it was locked to an n-vsync cadence.
    public synchronized void onFrame(int fps, int vsyncs, VsyncPacing.Grid grid, long nowNs) {
        long hzRounded = grid != null ? Math.round(grid.refreshHz()) : 0;
        if (fps != lastFps || vsyncs != lastVsyncs || hzRounded != lastHzRounded) {
            summary("mode change");
            lastFps = fps;
            lastVsyncs = vsyncs;
            lastHzRounded = hzRounded;
            lastReportNs = nowNs;
            android.util.Log.i(TAG, describe(fps, vsyncs, grid));
        }
        if (vsyncs > 0) locked++;
        else fallback++;
        if (nowNs - lastReportNs > REPORT_INTERVAL_NS) {
            lastReportNs = nowNs;
            summary("periodic");
        }
    }

    public synchronized void onMiss() {
        misses++;
    }

    public synchronized void onDisarm() {
        summary("disarmed");
        lastFps = -1;
        lastVsyncs = -1;
        lastHzRounded = -1;
    }

    private String describe(int fps, int vsyncs, VsyncPacing.Grid grid) {
        if (vsyncs > 0) {
            return String.format(Locale.US, "%s: vsync-locked %d vsyncs @ %.2f Hz for %d fps",
                    source, vsyncs, grid.refreshHz(), fps);
        }
        if (grid == null) {
            return String.format(Locale.US, "%s: fallback wall-clock for %d fps (no vsync source yet)",
                    source, fps);
        }
        return String.format(Locale.US, "%s: fallback wall-clock for %d fps (%.2f Hz has no integer multiple)",
                source, fps, grid.refreshHz());
    }

    private void summary(String reason) {
        long total = locked + fallback;
        if (total == 0) return;
        android.util.Log.i(TAG, String.format(Locale.US,
                "%s summary (%s): frames=%d locked=%d fallback=%d misses=%d (%.2f%% of locked)",
                source, reason, total, locked, fallback, misses,
                locked > 0 ? 100.0 * misses / locked : 0.0));
        locked = 0;
        fallback = 0;
        misses = 0;
    }
}

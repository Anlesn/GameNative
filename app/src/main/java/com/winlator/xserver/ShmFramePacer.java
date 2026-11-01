package com.winlator.xserver;

import java.util.concurrent.ConcurrentHashMap;

// FPS limiter for clients that present via full-drawable SHM blits instead of
// the Present extension. The caller suspends the client's socket reads for the
// returned delay, which blocks the game inside its own per-frame XSync; the
// blitted frame is still displayed immediately. Slots are locked to every n-th
// vsync when the cap divides the live refresh rate (see VsyncPacing).
public class ShmFramePacer {
    private static volatile int frameRateLimit = 0;

    private static class Timing { long nextNs; }
    private static final ConcurrentHashMap<Integer, Timing> timings = new ConcurrentHashMap<>();

    private static final int MAX_TRACKED_DRAWABLES = 128;

    private static final long STREAM_RESUME_NS = 1_000_000_000L;
    private static final VsyncLimiterLog limiterLog = new VsyncLimiterLog("shm");

    public static void setFrameRateLimit(int limit) {
        frameRateLimit = Math.max(0, limit);
        if (frameRateLimit == 0) {
            timings.clear();
            limiterLog.onDisarm();
        }
    }

    // Returns how long (ns) the caller should suspend this client's reads, or 0.
    public static long framePresented(int drawableId) {
        int fps = frameRateLimit;
        if (fps <= 0) return 0;
        if (timings.size() > MAX_TRACKED_DRAWABLES) evictStalest(drawableId);
        Timing t = timings.computeIfAbsent(drawableId, k -> new Timing());
        long now = System.nanoTime();
        VsyncPacing.Grid grid = VsyncClock.currentGrid(now);
        int vsyncs = grid != null ? VsyncPacing.vsyncsPerFrame(grid.refreshHz(), fps) : 0;
        limiterLog.onFrame(fps, vsyncs, grid, now);
        if (vsyncs > 0) {
            // The game blitted after its slot: this frame lands at least a vsync late.
            // nextNs == 0 is a drawable's first frame, an old one a resumed stream.
            if (t.nextNs != 0 && now - t.nextNs < STREAM_RESUME_NS
                    && VsyncPacing.isMiss(now, t.nextNs, grid.periodNs)) limiterLog.onMiss();
            long slot = VsyncPacing.snapToGrid(Math.max(t.nextNs, now), grid);
            t.nextNs = slot + vsyncs * grid.periodNs;
            return Math.max(0, slot - now);
        }
        if (t.nextNs < now) t.nextNs = now;
        long delay = t.nextNs - now;
        t.nextNs += 1_000_000_000L / fps;
        return delay;
    }

    // Evicts the longest-idle entry (smallest nextNs), never the presenting
    // drawable, so an overflow frees one slot without resetting active pacing.
    private static void evictStalest(int presentingDrawableId) {
        Integer evict = null;
        long oldest = Long.MAX_VALUE;
        for (java.util.Map.Entry<Integer, Timing> e : timings.entrySet()) {
            if (e.getKey() == presentingDrawableId) continue;
            long nextNs = e.getValue().nextNs;
            if (nextNs < oldest) {
                oldest = nextNs;
                evict = e.getKey();
            }
        }
        if (evict != null) timings.remove(evict);
    }
}

package com.winlator.xserver;

// Timing math for the vsync-locked frame limiter. No Android dependencies, so
// it is unit-tested directly; VsyncClock feeds it the live vsync timestamps.
//
// A wall-clock limiter releases frames at start + k * (1e9 / fps). That target
// drifts against the panel's vsync, so a 30 fps cap on a 60 Hz panel shows
// 16.7 / 50 ms pairs whenever the frame lands near a vsync boundary. Locking
// the release to every N-th vsync (N = refresh / fps) keeps the phase fixed.
// Caps without an integer ratio (45 or 40 fps on 60 Hz) cannot be paced evenly
// at all; they keep the wall-clock limiter.
public final class VsyncPacing {
    private VsyncPacing() {}

    // A cap within this fraction of refresh / N still counts as an N-vsync cadence
    // (30 fps on a 59.94 Hz panel locks to 2 vsyncs; 31 fps on 60 Hz does not).
    static final double LOCK_TOLERANCE = 0.03;

    static final long MIN_PERIOD_NS = 2_000_000L;   // 500 Hz
    static final long MAX_PERIOD_NS = 50_000_000L;  // 20 Hz

    // A vsync grid: anchorNs is a real vsync timestamp (System.nanoTime base),
    // every anchorNs + k * periodNs is a vsync.
    public static final class Grid {
        public final long anchorNs;
        public final long periodNs;

        public Grid(long anchorNs, long periodNs) {
            this.anchorNs = anchorNs;
            this.periodNs = periodNs;
        }

        public double refreshHz() {
            return 1e9 / periodNs;
        }
    }

    // Vsyncs per released frame for an even cadence, or 0 when refresh / fps is
    // not close to an integer (the caller then keeps wall-clock pacing).
    public static int vsyncsPerFrame(double refreshHz, int fps) {
        if (refreshHz <= 0 || fps <= 0) return 0;
        long n = Math.round(refreshHz / fps);
        if (n < 1) return 0;
        return Math.abs(refreshHz / n - fps) <= fps * LOCK_TOLERANCE ? (int) n : 0;
    }

    // The vsync of the grid nearest to t.
    public static long snapToGrid(long t, Grid grid) {
        long k = Math.floorDiv(t - grid.anchorNs + grid.periodNs / 2, grid.periodNs);
        return grid.anchorNs + k * grid.periodNs;
    }

    // Release slot for the next frame on an n-vsync cadence. Mirrors the wall-clock
    // limiter: the slot advances by one frame from the previous one, and restarts
    // from now when the game fell more than a whole frame behind (or on the first
    // frame, prevSlotNs = 0). The result is always a vsync of the grid.
    public static long nextSlot(long prevSlotNs, long nowNs, Grid grid, int n) {
        long frameNs = n * grid.periodNs;
        long target = prevSlotNs <= nowNs - frameNs ? nowNs + frameNs : prevSlotNs + frameNs;
        return snapToGrid(target, grid);
    }

    // A frame released more than half a period after its slot shows up a vsync
    // late on screen: a quantization miss.
    public static boolean isMiss(long releasedNs, long slotNs, long periodNs) {
        return releasedNs - slotNs > periodNs / 2;
    }

    // Vsync period from consecutive vsync timestamps. An exact period (Choreographer
    // frame-timeline spacing, API 33+) wins; otherwise the period is estimated from
    // timestamp deltas, which are whole multiples of it when callbacks are skipped.
    public static final class PeriodEstimator {
        private static final int SEED_SAMPLES = 4;
        // Consecutive deltas of the same multiple k >= 2 before the panel is taken to
        // have slowed down (e.g. 120 -> 60 Hz) rather than callbacks being skipped.
        private static final int SLOWDOWN_SAMPLES = 8;

        private long lastFrameNs = 0;
        private long periodNs = 0;
        private int seedCount = 0;
        private long seedMinNs = Long.MAX_VALUE;
        private long lastMultiple = 0;
        private int sameMultipleCount = 0;

        public long periodNs() {
            return periodNs;
        }

        // Forget the last timestamp (the feed paused); the period estimate stays.
        public void restart() {
            lastFrameNs = 0;
            sameMultipleCount = 0;
        }

        public void onVsync(long frameTimeNs, long exactPeriodNs) {
            long prev = lastFrameNs;
            lastFrameNs = frameTimeNs;
            if (inRange(exactPeriodNs)) {
                periodNs = exactPeriodNs;
                return;
            }
            if (prev == 0) return;
            long delta = frameTimeNs - prev;
            if (delta <= 0) return;
            if (periodNs == 0) {
                if (inRange(delta)) seedMinNs = Math.min(seedMinNs, delta);
                if (++seedCount >= SEED_SAMPLES && seedMinNs != Long.MAX_VALUE) periodNs = seedMinNs;
                return;
            }
            long k = Math.max(1, Math.round((double) delta / periodNs));
            if (k >= 2 && k == lastMultiple) {
                if (++sameMultipleCount >= SLOWDOWN_SAMPLES && inRange(delta)) {
                    periodNs = delta;
                    sameMultipleCount = 0;
                    lastMultiple = 1;
                    return;
                }
            } else {
                sameMultipleCount = k >= 2 ? 1 : 0;
            }
            lastMultiple = k;
            long sample = delta / k;
            if (!inRange(sample)) return;
            if (Math.abs(sample - periodNs) * 4 > periodNs) {
                periodNs = sample; // refresh switch to a faster rate
            } else {
                periodNs += (sample - periodNs) / 8;
            }
        }

        private static boolean inRange(long ns) {
            return ns >= MIN_PERIOD_NS && ns <= MAX_PERIOD_NS;
        }
    }
}

package com.winlator.xserver;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;

import java.util.concurrent.atomic.AtomicBoolean;

// Live vsync grid for the frame limiters. The limiters run on X server threads,
// which have no Looper and so no Choreographer; this clock subscribes to the
// main-thread Choreographer and publishes the latest vsync timestamp and period.
// The period comes from the vsync callbacks themselves, so it is the rate the
// panel actually runs at, not the one GameNative requested.
//
// Demand-driven: currentGrid() starts the feed, and it stops on its own once no
// limiter has asked for IDLE_STOP_NS, so an uncapped session costs nothing.
public final class VsyncClock {
    private static final long IDLE_STOP_NS = 2_000_000_000L;
    // An anchor older than this (main thread stalled, app in background) is not
    // trusted; the limiters fall back to wall-clock pacing meanwhile.
    private static final long STALE_NS = 500_000_000L;

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static volatile long lastDemandNs = 0;
    private static volatile VsyncPacing.Grid grid = null;
    // Touched only on the main thread.
    private static final VsyncPacing.PeriodEstimator estimator = new VsyncPacing.PeriodEstimator();
    private static Handler mainHandler;
    private static Object vsyncCallback; // Choreographer.VsyncCallback, API 33+
    private static final Choreographer.FrameCallback frameCallback = frameTimeNanos -> onVsync(frameTimeNanos, 0);

    private VsyncClock() {}

    // Latest vsync grid, or null while none is fresh. Safe from any thread.
    public static VsyncPacing.Grid currentGrid(long nowNs) {
        lastDemandNs = nowNs;
        if (running.compareAndSet(false, true)) {
            try {
                handler().post(VsyncClock::postNext);
            } catch (RuntimeException e) {
                running.set(false);
                android.util.Log.w("VsyncClock", "main looper unavailable: " + e);
                return null;
            }
        }
        VsyncPacing.Grid g = grid;
        return g != null && nowNs - g.anchorNs < STALE_NS ? g : null;
    }

    private static synchronized Handler handler() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    // Main thread.
    private static void postNext() {
        Choreographer choreographer = Choreographer.getInstance();
        if (Build.VERSION.SDK_INT >= 33) {
            if (vsyncCallback == null) {
                vsyncCallback = (Choreographer.VsyncCallback) data -> {
                    Choreographer.FrameTimeline[] timelines = data.getFrameTimelines();
                    long exact = timelines.length > 1
                            ? timelines[1].getExpectedPresentationTimeNanos()
                                - timelines[0].getExpectedPresentationTimeNanos()
                            : 0;
                    onVsync(data.getFrameTimeNanos(), exact);
                };
            }
            choreographer.postVsyncCallback((Choreographer.VsyncCallback) vsyncCallback);
        } else {
            choreographer.postFrameCallback(frameCallback);
        }
    }

    // Main thread.
    private static void onVsync(long frameTimeNs, long exactPeriodNs) {
        estimator.onVsync(frameTimeNs, exactPeriodNs);
        long periodNs = estimator.periodNs();
        if (periodNs > 0) grid = new VsyncPacing.Grid(frameTimeNs, periodNs);
        if (System.nanoTime() - lastDemandNs > IDLE_STOP_NS) {
            estimator.restart();
            grid = null;
            running.set(false);
            return;
        }
        postNext();
    }
}

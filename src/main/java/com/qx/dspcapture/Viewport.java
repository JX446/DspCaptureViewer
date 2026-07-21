package com.qx.dspcapture;

/**
 * Display window that slides along the sample timeline.
 * <p>
 * Two modes:
 * <ul>
 *   <li><b>Live</b> — the window auto-follows the write pointer.</li>
 *   <li><b>Replay</b> — the window is positioned manually (slider).</li>
 * </ul>
 * <p>
 * Thread-safe: all methods are called from the JavaFX thread.
 */
public class Viewport {

    /** Default number of samples to display. */
    public static final int DEFAULT_VIEW_COUNT = 2048;
    public static final int MIN_VIEW_COUNT = 128;
    public static final int MAX_VIEW_COUNT = 65536;

    private long viewStart;        // global sample index at left edge of window
    private int viewCount;         // window width in samples
    private boolean liveMode = true;
    private double sliderPos;      // 0.0 (oldest) … 1.0 (newest)

    public Viewport() {
        this.viewCount = DEFAULT_VIEW_COUNT;
    }

    // ── Per-frame tick ───────────────────────────────────────────────

    /**
     * Advance one frame. In live mode snaps the window to the latest data.
     *
     * @param totalWritten latest sample count from the ring buffer
     */
    public void tick(long totalWritten) {
        if (liveMode && totalWritten > 0) {
            viewStart = Math.max(0, totalWritten - viewCount);
            sliderPos = 1.0;
        }
        // replay mode: viewStart is set by slider, nothing to do
    }

    // ── Setters ──────────────────────────────────────────────────────

    /** Switch between live and replay mode. */
    public void setLive(boolean live) {
        this.liveMode = live;
        if (live) {
            sliderPos = 1.0;
        }
    }

    /**
     * Position the window via a 0…1 slider value.
     *
     * @param pos        0.0 = oldest data, 1.0 = newest
     * @param validStart oldest readable sample index
     * @param validEnd   newest readable sample index (exclusive)
     */
    public void setSliderPosition(double pos, long validStart, long validEnd) {
        this.sliderPos = Math.max(0.0, Math.min(1.0, pos));
        long span = validEnd - validStart;
        if (span <= viewCount) {
            viewStart = validStart;
        } else {
            viewStart = validStart + (long) (sliderPos * (span - viewCount));
        }
    }

    /** Zoom: change the window width (samples). Clamped to MIN/MAX. */
    public void setViewCount(int count) {
        this.viewCount = Math.max(MIN_VIEW_COUNT, Math.min(MAX_VIEW_COUNT, count));
    }

    /** Adjust view count by a factor (e.g. 0.5 to zoom in, 2.0 to zoom out). */
    public void zoom(double factor) {
        setViewCount((int) (viewCount * factor));
    }

    /**
     * Zoom centered on an anchor sample — keeps the same data under the
     * cursor after zooming.
     *
     * @param factor       zoom multiplier (0.5 = in, 2.0 = out)
     * @param anchorSample the sample index under the cursor
     * @param cursorFrac   cursor position as fraction of plot width (0..1)
     * @param maxStart     max allowed viewStart (validEnd - viewCount)
     */
    public void zoomAt(double factor, long anchorSample, double cursorFrac, long maxStart) {
        int newCount = Math.max(MIN_VIEW_COUNT, Math.min(MAX_VIEW_COUNT, (int) (viewCount * factor)));
        long newStart = anchorSample - (long) (newCount * cursorFrac);
        if (newStart < 0) newStart = 0;
        if (newStart > maxStart) newStart = maxStart;
        viewCount = newCount;
        viewStart = newStart;
    }

    /** Jump the window forward/backward by a fraction of the current viewCount. */
    public void scroll(double fractionOfWindow, long validStart, long validEnd) {
        long delta = (long) (viewCount * fractionOfWindow);
        long newStart = viewStart + delta;
        if (newStart < validStart) newStart = validStart;
        long maxStart = Math.max(validStart, validEnd - viewCount);
        if (newStart > maxStart) newStart = maxStart;
        viewStart = newStart;
        // Update slider to reflect new position
        long span = validEnd - validStart;
        if (span > viewCount) {
            sliderPos = (double) (viewStart - validStart) / (span - viewCount);
        } else {
            sliderPos = 0.0;
        }
    }

    // ── Getters ──────────────────────────────────────────────────────

    public long getViewStart() { return viewStart; }
    public int getViewCount() { return viewCount; }
    public boolean isLive() { return liveMode; }
    public double getSliderPosition() { return sliderPos; }
}

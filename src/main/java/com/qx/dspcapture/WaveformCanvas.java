package com.qx.dspcapture;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * J-Scope style waveform on Canvas — multiple channels overlaid on one plot.
 * <p>
 * Data comes from a {@link DataProvider}; the visible window is controlled
 * by a {@link Viewport}.  Rendering is driven by a fixed-frame-rate timer
 * (default 60 FPS).
 */
public class WaveformCanvas extends Canvas {

    // ── Layout ──
    private static final double MARGIN_LEFT   = 52;
    private static final double MARGIN_RIGHT  = 8;
    private static final double MARGIN_TOP    = 8;
    private static final double MARGIN_BOTTOM = 28;

    // ── Colors ──
    private static final Color BG     = Color.web("#000000");
    private static final Color GRID   = Color.web("#1a1a2a");
    private static final Color LABEL  = Color.web("#888899");
    private static final Color AXIS   = Color.web("#444455");

    static final Color[] CHANNEL_COLORS = {
        Color.web("#2a78d6"),  // blue
        Color.web("#1baf7a"),  // aqua
        Color.web("#eda100"),  // yellow
        Color.web("#e34948"),  // red
    };

    // ── External state ──
    private DataProvider dataProvider;
    private Viewport viewport;
    private int numChannels = 1;
    private Runnable onViewportChanged;  // notify App to sync slider/stats

    // ── Y-axis ──
    private double yMin = -1.0;
    private double yMax =  1.0;
    private boolean autoRange = true;

    // ── Visibility ──
    private final boolean[] channelVisible = {true, true, true, true};

    // ── Hover state ──
    private double hoverX = -1;

    // ── Fixed-frame-rate rendering ──
    private final AnimationTimer renderTimer;
    private long lastFrameNanos = 0;
    private volatile long frameIntervalNanos;

    public WaveformCanvas() {
        super(800, 400);
        setPickOnBounds(true);  // receive events across entire area
        setAccessibleText("Real-time DSP signal waveform");

        frameIntervalNanos = 16_666_666L;  // ~16.7ms → 60 FPS
        renderTimer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (now - lastFrameNanos >= frameIntervalNanos) {
                    lastFrameNanos = now;
                    tickAndDraw();
                }
            }
        };
        renderTimer.start();

        setOnMouseMoved(e -> {
            double mx = e.getX(), my = e.getY();
            if (mx >= left() && mx <= right() && my >= top() && my <= bottom()) {
                hoverX = mx;
            } else {
                hoverX = -1;
            }
        });
        setOnMouseExited(e -> { hoverX = -1; });

        // Auto-focus on hover (enables keyboard + scroll without clicking first)
        setOnMouseEntered(e -> requestFocus());

        // Scroll wheel → zoom centered on cursor (event filter: capture phase)
        addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (viewport == null || dataProvider == null) return;
            if (dataProvider.getTotalSamples() == 0) return;
            double mx = e.getX();
            if (mx < left() || mx > right()) return;
            double frac = (mx - left()) / pw();
            long anchor = viewport.getViewStart() + (long)(viewport.getViewCount() * frac);
            double factor = (e.getDeltaY() > 0) ? 0.7 : 1.4;
            viewport.zoomAt(factor, anchor, frac,
                    Math.max(0, dataProvider.getTotalSamples() - viewport.getViewCount()));
            if (onViewportChanged != null) onViewportChanged.run();
            e.consume();
        });

        // Keyboard crosshair control
        setFocusTraversable(true);
        focusedProperty().addListener((obs, ov, nv) -> {
            if (nv && hoverX < 0) hoverX = left();
        });
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (viewport == null || dataProvider == null) return;
            long maxStart = Math.max(0, dataProvider.getTotalSamples() - viewport.getViewCount());
            switch (e.getCode()) {
                case SPACE:
                    viewport.setLive(!viewport.isLive());
                    if (onViewportChanged != null) onViewportChanged.run();
                    e.consume();
                    break;
                case LEFT:
                    viewport.setLive(false);  // panning exits live
                    viewport.scroll(-0.05, 0, maxStart);
                    if (onViewportChanged != null) onViewportChanged.run();
                    e.consume();
                    break;
                case RIGHT:
                    viewport.setLive(false);
                    viewport.scroll(0.05, 0, maxStart);
                    if (onViewportChanged != null) onViewportChanged.run();
                    e.consume();
                    break;
                case HOME:
                    viewport.setLive(false);
                    viewport.scroll(-1.0, 0, maxStart);
                    if (onViewportChanged != null) onViewportChanged.run();
                    e.consume();
                    break;
                case END:
                    viewport.setLive(false);
                    viewport.scroll(1.0, 0, maxStart);
                    if (onViewportChanged != null) onViewportChanged.run();
                    e.consume();
                    break;
            }
        });
    }

    // ── Public API ──

    public void setDataProvider(DataProvider dp) {
        this.dataProvider = dp;
        this.numChannels = (dp != null) ? dp.getNumChannels() : 1;
    }

    public void setViewport(Viewport vp) {
        this.viewport = vp;
    }

    /** Register a callback invoked after user-driven viewport changes (scroll, keyboard). */
    public void setOnViewportChanged(Runnable r) {
        this.onViewportChanged = r;
    }

    public DataProvider getDataProvider() { return dataProvider; }
    public Viewport getViewport() { return viewport; }

    public void setNumChannels(int n) {
        this.numChannels = n;
        draw();
    }

    public void setChannelVisible(int ch, boolean v) { channelVisible[ch] = v; draw(); }
    public boolean isChannelVisible(int ch) { return channelVisible[ch]; }

    /** Zoom centered on a cursor position within the canvas. Called from scroll events. */
    public void scrollZoom(double cursorX, double deltaY) {
        if (viewport == null || dataProvider == null) return;
        if (dataProvider.getTotalSamples() == 0) return;
        double factor = (deltaY > 0) ? 0.7 : 1.4;
        if (viewport.isLive()) {
            // Live mode: only change zoom level, stay following
            viewport.zoom(factor);
        } else {
            // Replay mode: zoom centered on cursor, sync slider
            if (cursorX < left() || cursorX > right()) return;
            double frac = (cursorX - left()) / pw();
            long anchor = viewport.getViewStart() + (long)(viewport.getViewCount() * frac);
            viewport.zoomAt(factor, anchor, frac,
                    Math.max(0, dataProvider.getTotalSamples() - viewport.getViewCount()));
            if (onViewportChanged != null) onViewportChanged.run();
        }
    }

    public void setTargetFps(int fps) {
        frameIntervalNanos = Math.max(1, 1_000_000_000L / fps);
        lastFrameNanos = 0;
    }

    public void dispose() {
        renderTimer.stop();
    }

    /** Force an immediate redraw (e.g. after clear or visibility change). */
    public void draw() {
        double w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = getGraphicsContext2D();
        gc.setFill(BG);
        gc.fillRect(0, 0, w, h);

        if (!hasData()) {
            gc.setFill(LABEL);
            gc.setFont(Font.font("Segoe UI", 14));
            gc.fillText("Waiting for data…", w / 2 - 60, h / 2);
        } else {
            drawAllWaveforms(gc);
        }
        drawGridAndAxes(gc);
        if (hoverX >= 0) drawHoverOverlay(gc);
    }

    // ── Per-frame tick ──

    private void tickAndDraw() {
        if (viewport != null && dataProvider != null) {
            viewport.tick(dataProvider.getTotalSamples());
        }
        draw();
    }

    // ── Data helpers ──

    private boolean hasData() {
        return dataProvider != null && dataProvider.getTotalSamples() > 0;
    }

    /**
     * Fetch the samples that are currently visible through the viewport.
     * Returns null if no data is available.
     */
    private float[] getVisibleSamples(int channel) {
        if (dataProvider == null || viewport == null) return null;
        long total = dataProvider.getTotalSamples();
        if (total == 0) return null;
        long start = viewport.getViewStart();
        int count = viewport.getViewCount();
        if (start < 0) start = 0;
        if (start + count > total) count = (int) (total - start);
        if (count <= 0) return null;
        return dataProvider.getSamples(channel, start, count);
    }

    // ── Layout helpers ──
    private double left()   { return MARGIN_LEFT; }
    private double top()    { return MARGIN_TOP; }
    private double right()  { return Math.max(left() + 1, getWidth()  - MARGIN_RIGHT); }
    private double bottom() { return Math.max(top()  + 1, getHeight() - MARGIN_BOTTOM); }
    private double pw()     { return right() - left(); }
    private double ph()     { return bottom() - top(); }

    // ── Waveform rendering ──

    private void drawAllWaveforms(GraphicsContext gc) {
        double l = left(), t = top(), r = right(), b = bottom();
        double plotW = pw(), plotH = ph();
        if (plotW <= 0 || plotH <= 0) return;

        // Fetch visible samples for all channels (one pass for auto-range + draw)
        float[][] visSamples = new float[numChannels][];
        int maxN = 0;
        for (int ch = 0; ch < numChannels; ch++) {
            float[] s = getVisibleSamples(ch);
            visSamples[ch] = s;
            if (s != null && s.length > maxN) maxN = s.length;
        }
        if (maxN == 0) return;

        // Auto-range Y across visible window
        if (autoRange) {
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            for (int ch = 0; ch < numChannels; ch++) {
                float[] s = visSamples[ch];
                if (s == null) continue;
                for (float v : s) {
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
            }
            if (min == Float.MAX_VALUE) { min = -1; max = 1; }
            double m = Math.max(0.1, (max - min) * 0.1);
            yMin = min - m;
            yMax = max + m;
            if (yMax - yMin < 1e-6) { yMin -= 1; yMax += 1; }
        }

        double yRange = yMax - yMin;
        if (yRange <= 0) yRange = 2;
        double yScale = plotH / yRange;

        // Draw each channel trace
        for (int ch = 0; ch < numChannels; ch++) {
            float[] buf = visSamples[ch];
            if (buf == null || buf.length == 0) continue;
            int n = buf.length;

            Color c = CHANNEL_COLORS[ch];
            gc.setStroke(channelVisible[ch] ? c : Color.rgb(
                    (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255), 0.12));
            gc.setLineWidth(2);
            gc.beginPath();

            if (n < plotW) {
                // Upsampling: fewer samples than pixels —
                // spread samples evenly across full plot width
                double pxPerSample = plotW / Math.max(1, n);
                boolean first = true;
                for (int s = 0; s < n; s++) {
                    double px = l + (s + 0.5) * pxPerSample;
                    double val = buf[s];
                    double y = b - (val - yMin) * yScale;
                    y = Math.max(t, Math.min(b, y));
                    if (first) { gc.moveTo(px, y); first = false; }
                    else       { gc.lineTo(px, y); }
                }
            } else {
                // Downsampling: average samples that fall in each pixel column
                double spp = (double) n / plotW;
                boolean first = true;
                for (int px = (int) l; px <= (int) r; px++) {
                    int s0 = (int) Math.floor((px - l) * spp);
                    int s1 = (int) Math.floor((px - l + 1) * spp);
                    if (s0 < 0) s0 = 0;
                    if (s1 > n) s1 = n;
                    if (s0 >= n) break;

                    double sum = 0;
                    int count = 0;
                    for (int s = s0; s < s1; s++) {
                        sum += buf[s];
                        count++;
                    }
                    if (count == 0) continue;

                    double avg = sum / count;
                    double y = b - (avg - yMin) * yScale;
                    y = Math.max(t, Math.min(b, y));

                    if (first) { gc.moveTo(px + 0.5, y); first = false; }
                    else       { gc.lineTo(px + 0.5, y); }
                }
            }
            gc.stroke();
        }
    }

    // ── Grid & axes ──

    private void drawGridAndAxes(GraphicsContext gc) {
        double l = left(), t = top(), r = right(), b = bottom();
        double plotW = pw(), plotH = ph();

        // Grid lines
        gc.setStroke(GRID);
        gc.setLineWidth(1);
        for (int i = 0; i <= 5; i++) {
            double y = t + plotH * i / 5.0;
            gc.strokeLine(l, y, r, y);
        }
        // Vertical grid divisions (4)
        for (int i = 1; i <= 3; i++) {
            double x = l + plotW * i / 4.0;
            gc.strokeLine(x, t, x, b);
        }

        // Axes
        gc.setStroke(AXIS);
        gc.setLineWidth(1);
        gc.strokeLine(l, t, l, b);
        gc.strokeLine(l, b, r, b);

        // Y labels
        gc.setFill(LABEL);
        gc.setFont(Font.font("Segoe UI", 9));
        if (hasData()) {
            gc.fillText(fmt(yMax), 2, t + 12);
            gc.fillText(fmt((yMin + yMax) / 2), 2, t + plotH / 2 + 3);
            gc.fillText(fmt(yMin), 2, b - 2);
        } else {
            gc.fillText("1.0", 2, t + 12);
            gc.fillText("0.0", 2, t + plotH / 2 + 3);
            gc.fillText("-1.0", 2, b - 2);
        }

        // X axis labels — show sample range
        if (viewport != null && hasData()) {
            long vStart = viewport.getViewStart();
            int vCount  = viewport.getViewCount();
            gc.setFont(Font.font("Segoe UI", 9));
            gc.fillText(String.valueOf(vStart), (int) l, (int) b + 14);
            gc.fillText(String.valueOf(vStart + vCount / 2), (int)(l + plotW / 2 - 20), (int) b + 14);
            gc.fillText(String.valueOf(vStart + vCount), (int) r - 30, (int) b + 14);
        }

        // Axis titles
        gc.setFont(Font.font("Segoe UI", 10));
        gc.fillText("Value", 2, 10);
        gc.fillText("Sample", r - 40, getHeight() - 2);

        // Replay badge — top-left, prominent, no conflict with channel legend
        if (viewport != null && !viewport.isLive()) {
            double bx = l;
            double by = t + 14;
            double bw = 75, bh = 18;
            gc.setFill(Color.rgb(237, 161, 0, 0.25));   // amber tint
            gc.fillRoundRect(bx, by, bw, bh, 4, 4);
            gc.setStroke(Color.web("#eda100"));
            gc.setLineWidth(1);
            gc.strokeRoundRect(bx, by, bw, bh, 4, 4);
            gc.setFill(Color.web("#eda100"));
            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
            gc.fillText("⏸  REPLAY", bx + 6, by + 13);
        }

        // Channel legend (top-right corner)
        double lx = r - numChannels * 80;
        double ly = t - 2;
        for (int ch = 0; ch < numChannels; ch++) {
            gc.setFill(channelVisible[ch] ? CHANNEL_COLORS[ch] : GRID);
            gc.fillRoundRect(lx + ch * 80, ly + 1, 22, 8, 3, 3);
            gc.setFill(channelVisible[ch] ? LABEL : GRID);
            gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
            gc.fillText("Ch" + ch, lx + ch * 80 + 26, ly + 9);
        }
    }

    // ── Hover crosshair + tooltip ──

    private void drawHoverOverlay(GraphicsContext gc) {
        double l = left(), t = top(), r = right(), b = bottom();
        double plotW = pw(), plotH = ph();
        if (plotW <= 0 || plotH <= 0 || !hasData()) return;

        float[][] visSamples = new float[numChannels][];
        int maxN = 0;
        for (int ch = 0; ch < numChannels; ch++) {
            float[] s = getVisibleSamples(ch);
            visSamples[ch] = s;
            if (s != null && s.length > maxN) maxN = s.length;
        }
        if (maxN == 0) return;

        double spp = (double) maxN / plotW;
        int idx = (int) Math.floor((hoverX - l) * spp);
        idx = Math.max(0, Math.min(idx, maxN - 1));
        double yRange = yMax - yMin;
        if (yRange <= 0) yRange = 2;
        double yScale = plotH / yRange;

        // ── Vertical crosshair (full-height, semi-transparent white) ──
        gc.setStroke(Color.rgb(255, 255, 255, 0.30));
        gc.setLineWidth(1);
        gc.setLineDashes(6, 3);
        gc.strokeLine(hoverX, t, hoverX, b);
        gc.setLineDashes(null);

        // ── Dots on each visible trace ──
        double[] dotYs = new double[numChannels];
        float[] dotVals = new float[numChannels];
        for (int ch = 0; ch < numChannels; ch++) {
            if (!channelVisible[ch]) { dotYs[ch] = -1; continue; }
            float[] buf = visSamples[ch];
            if (buf == null || buf.length == 0) { dotYs[ch] = -1; continue; }
            int i = Math.max(0, Math.min(idx, buf.length - 1));
            float val = buf[i];
            dotVals[ch] = val;
            double dy = b - (val - yMin) * yScale;
            dy = Math.max(t, Math.min(b, dy));
            dotYs[ch] = dy;

            // Outer ring (white) + inner fill (channel color)
            gc.setFill(Color.WHITE);
            gc.fillOval(hoverX - 5, dy - 5, 10, 10);
            gc.setFill(CHANNEL_COLORS[ch]);
            gc.fillOval(hoverX - 3, dy - 3, 6, 6);
        }

        // ── Tooltip card ──
        // Count visible channels for card height
        int visCount = 0;
        for (int ch = 0; ch < numChannels; ch++) {
            if (channelVisible[ch] && visSamples[ch] != null && visSamples[ch].length > 0)
                visCount++;
        }
        if (visCount == 0) return;

        // Layout constants
        double cardPadH = 10, cardPadV = 6;
        double lineH    = 16;   // height per channel row
        double dotR     = 4;    // colored dot radius in card
        double cardW    = 130;  // fixed width — cleaner than guessing from text
        double cardH    = lineH * visCount + cardPadV * 2;
        double cardX    = hoverX + 16;  // to the right of crosshair
        double cardY    = t + 8;        // near top — avoids jumping with cursor Y
        if (cardX + cardW > r) cardX = hoverX - cardW - 16;  // flip to left if near edge

        // Card background
        gc.setFill(Color.rgb(12, 12, 22, 0.94));
        gc.setStroke(Color.rgb(80, 80, 100, 0.8));
        gc.setLineWidth(1);
        gc.fillRoundRect(cardX, cardY, cardW, cardH, 5, 5);
        gc.strokeRoundRect(cardX, cardY, cardW, cardH, 5, 5);

        // Per-channel rows
        gc.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        int row = 0;
        for (int ch = 0; ch < numChannels; ch++) {
            if (!channelVisible[ch] || visSamples[ch] == null || visSamples[ch].length == 0)
                continue;

            double ry = cardY + cardPadV + lineH * row + lineH * 0.75;

            // Colored dot
            gc.setFill(CHANNEL_COLORS[ch]);
            gc.fillOval(cardX + cardPadH, ry - dotR, dotR * 2, dotR * 2);

            // Channel label + value
            String label = String.format("Ch%d  %.4f", ch, dotVals[ch]);
            gc.setFill(Color.rgb(180, 180, 200));  // muted label
            gc.fillText(label, cardX + cardPadH + dotR * 2 + 7, ry + 2);

            row++;
        }
    }

    // ── Formatting ──

    private static String fmt(double v) {
        if (Math.abs(v) < 1e-6) return "0.00";
        if (Math.abs(v) >= 1000 || Math.abs(v) < 0.01)
            return String.format("%.2e", v);
        return String.format("%.2f", v);
    }
}

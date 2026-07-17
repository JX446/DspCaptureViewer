package com.qx.dspcapture;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * J-Scope style waveform on Canvas — multiple channels overlaid on one plot.
 * Full redraw each chunk; fast enough for ~2000-sample × N-channel waveforms.
 */
public class WaveformCanvas extends Canvas {

    // ── Layout ──
    private static final double MARGIN_LEFT   = 52;
    private static final double MARGIN_RIGHT  = 8;
    private static final double MARGIN_TOP    = 8;
    private static final double MARGIN_BOTTOM = 28;

    // ── Config ──
    private int numChannels;
    private final int maxSamples;
    private final int numChunks;

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

    // ── State ──
    @SuppressWarnings("unchecked")
    private final List<Float>[] ringBufs = new List[4];
    private double yMin = -1.0;
    private double yMax =  1.0;
    private boolean autoRange = true;

    // ── Visibility ──
    private final boolean[] channelVisible = {true, true, true, true};

    // ── Hover state ──
    private double hoverX = -1;
    private double hoverY = -1;

    public WaveformCanvas(int numChannels, int maxSamples, int numChunks) {
        super(800, 400);  // default size — StackPane resize listeners will adjust
        this.numChannels = numChannels;
        this.maxSamples  = maxSamples;
        this.numChunks   = numChunks;
        for (int ch = 0; ch < 4; ch++) {
            ringBufs[ch] = new ArrayList<>(maxSamples + 512);
        }
        setAccessibleText("Real-time DSP signal waveform");

        // Hover crosshair + tooltip
        setOnMouseMoved(e -> {
            double mx = e.getX(), my = e.getY();
            if (mx >= left() && mx <= right() && my >= top() && my <= bottom()) {
                hoverX = mx; hoverY = my;
            } else {
                hoverX = -1; hoverY = -1;
            }
            draw();
        });
        setOnMouseExited(e -> { hoverX = -1; hoverY = -1; draw(); });
    }

    // ── Public API ──

    /** Push samples for a specific channel. */
    public void pushChunk(int channel, float[] values) {
        if (channel >= numChannels) return;
        List<Float> buf = ringBufs[channel];
        for (float v : values) buf.add(v);
        int drop = Math.max(0, buf.size() - maxSamples);
        if (drop > 0) buf.subList(0, drop).clear();
        draw();
    }

    public void clearData() {
        for (int ch = 0; ch < 4; ch++) ringBufs[ch].clear();
        draw();
    }

    public void setNumChannels(int n) {
        this.numChannels = n;
        clearData();  // clear all + redraw
    }

    public void setChannelVisible(int ch, boolean v) { channelVisible[ch] = v; draw(); }
    public boolean isChannelVisible(int ch) { return channelVisible[ch]; }

    /** Returns stats for channel: {current, min, max, avg, pkpk, samples}. Null if no data. */
    public float[] getChannelStats(int ch) {
        if (ch >= numChannels) return null;
        List<Float> buf = ringBufs[ch];
        int n = buf.size();
        if (n == 0) return null;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        double sum = 0;
        for (float v : buf) {
            if (v < min) min = v;
            if (v > max) max = v;
            sum += v;
        }
        return new float[] { buf.get(n - 1), min, max, (float)(sum / n), max - min, n };
    }

    private boolean hasData() {
        for (int ch = 0; ch < numChannels; ch++) {
            if (!ringBufs[ch].isEmpty()) return true;
        }
        return false;
    }

    // ── Layout helpers ──
    private double left()   { return MARGIN_LEFT; }
    private double top()    { return MARGIN_TOP; }
    private double right()  { return Math.max(left() + 1, getWidth()  - MARGIN_RIGHT); }
    private double bottom() { return Math.max(top()  + 1, getHeight() - MARGIN_BOTTOM); }
    private double pw()     { return right() - left(); }
    private double ph()     { return bottom() - top(); }

    // ── Draw ──

    public void draw() {
        double w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = getGraphicsContext2D();
        gc.setFill(BG);
        gc.fillRect(0, 0, w, h);

        if (!hasData()) {
            gc.setFill(LABEL);
            gc.setFont(Font.font("Segoe UI", 14));
            gc.fillText("Waiting for data...", w / 2 - 60, h / 2);
        } else {
            drawAllWaveforms(gc);
        }
        drawGridAndAxes(gc);
        if (hoverX >= 0) drawHoverOverlay(gc);
    }

    private void drawAllWaveforms(GraphicsContext gc) {
        double l = left(), t = top(), r = right(), b = bottom();
        double plotW = pw(), plotH = ph();
        if (plotW <= 0 || plotH <= 0) return;

        // Auto-range Y across ALL channels
        if (autoRange) {
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            for (int ch = 0; ch < numChannels; ch++) {
                for (float v : ringBufs[ch]) {
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
        double spp   = Math.max(1.0, (double) maxSamples / plotW);

        // Draw each channel as a separate trace
        for (int ch = 0; ch < numChannels; ch++) {
            List<Float> buf = ringBufs[ch];
            int n = buf.size();
            if (n == 0) continue;

            Color c = CHANNEL_COLORS[ch];
            gc.setStroke(channelVisible[ch] ? c : Color.rgb(
                    (int)(c.getRed()*255), (int)(c.getGreen()*255), (int)(c.getBlue()*255), 0.12));
            gc.setLineWidth(2);
            gc.beginPath();

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
                    sum += buf.get(s);
                    count++;
                }
                if (count == 0) continue;

                double avg = sum / count;
                double y = b - (avg - yMin) * yScale;
                y = Math.max(t, Math.min(b, y));

                if (first) { gc.moveTo(px + 0.5, y); first = false; }
                else       { gc.lineTo(px + 0.5, y); }
            }
            gc.stroke();
        }
    }

    private void drawGridAndAxes(GraphicsContext gc) {
        double l = left(), t = top(), r = right(), b = bottom();
        double plotW = pw(), plotH = ph();

        // Grid
        gc.setStroke(GRID);
        gc.setLineWidth(1);
        for (int i = 0; i <= 5; i++) {
            double y = t + plotH * i / 5.0;
            gc.strokeLine(l, y, r, y);
        }
        if (numChunks > 1 && plotW > 0) {
            double divW = plotW / numChunks;
            for (int i = 1; i < numChunks; i++) {
                double x = l + i * divW;
                gc.strokeLine(x, t, x, b);
            }
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

        // X labels
        if (plotW > 0 && numChunks > 1) {
            double divW = plotW / numChunks;
            for (int i = 0; i <= numChunks; i++) {
                double x = l + i * divW;
                String label = String.valueOf(i * (maxSamples / numChunks));
                gc.fillText(label, x - label.length() * 3.5, b + 14);
            }
        }

        // Axis titles
        gc.setFont(Font.font("Segoe UI", 10));
        gc.fillText("Value", 2, 10);
        gc.fillText("Sample", r - 40, getHeight() - 2);

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

        double spp = Math.max(1.0, (double) maxSamples / plotW);
        int idx = (int) Math.floor((hoverX - l) * spp);
        double yRange = yMax - yMin;
        if (yRange <= 0) yRange = 2;
        double yScale = plotH / yRange;

        // Vertical crosshair (white on black)
        gc.setStroke(Color.rgb(255, 255, 255, 0.25));
        gc.setLineWidth(1);
        gc.setLineDashes(4, 4);
        gc.strokeLine(hoverX, t, hoverX, b);
        gc.setLineDashes(null);

        // Dots + tooltip text
        StringBuilder sb = new StringBuilder();
        double dotY = 0;
        for (int ch = 0; ch < numChannels; ch++) {
            if (!channelVisible[ch]) continue;
            List<Float> buf = ringBufs[ch];
            if (buf.isEmpty()) continue;
            int i = idx;
            if (i < 0) i = 0;
            if (i >= buf.size()) i = buf.size() - 1;
            float val = buf.get(i);
            double dy = b - (val - yMin) * yScale;
            dy = Math.max(t, Math.min(b, dy));
            gc.setFill(CHANNEL_COLORS[ch]);
            gc.fillOval(hoverX - 3, dy - 3, 6, 6);
            gc.setStroke(BG);
            gc.setLineWidth(2);
            gc.strokeOval(hoverX - 3, dy - 3, 6, 6);
            if (sb.length() > 0) sb.append("  ");
            sb.append(String.format("Ch%d:%.4f", ch, val));
            dotY = dy;
        }

        String text = sb.toString();
        if (text.isEmpty()) return;
        gc.setFont(Font.font("Segoe UI", 10));
        double tw = text.length() * 6 + 14;
        double th = 20;
        double tx = hoverX + 14;
        double ty = dotY - th - 10;
        if (tx + tw > r) tx = hoverX - tw - 14;
        if (ty < t) ty = dotY + 14;

        gc.setFill(Color.rgb(20, 20, 35, 0.92));
        gc.setStroke(Color.rgb(100, 100, 130, 0.6));
        gc.setLineWidth(1);
        gc.fillRoundRect(tx, ty, tw, th, 4, 4);
        gc.strokeRoundRect(tx, ty, tw, th, 4, 4);
        gc.setFill(Color.web("#e0e0e0"));
        gc.fillText(text, tx + 7, ty + 14);
    }

    private static String fmt(double v) {
        if (Math.abs(v) < 1e-6) return "0.00";
        if (Math.abs(v) >= 1000 || Math.abs(v) < 0.01)
            return String.format("%.2e", v);
        return String.format("%.2f", v);
    }
}

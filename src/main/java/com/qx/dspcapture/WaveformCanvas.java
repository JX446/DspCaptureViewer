package com.qx.dspcapture;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * Oscilloscope-style waveform on Canvas. Full redraw each chunk —
 * fast enough for ~2000-sample waveforms on modern hardware.
 */
public class WaveformCanvas extends Canvas {

    // ── Layout ──
    private static final double MARGIN_LEFT   = 52;
    private static final double MARGIN_RIGHT  = 8;
    private static final double MARGIN_TOP    = 8;
    private static final double MARGIN_BOTTOM = 28;

    // ── Config ──
    private final int maxSamples;
    private final int numChunks;

    // ── Colors ──
    private static final Color BG     = Color.web("#fcfcfb");
    private static final Color GRID   = Color.web("#e1e0d9");
    private static final Color SIGNAL = Color.web("#2a78d6");
    private static final Color LABEL  = Color.web("#898781");
    private static final Color AXIS   = Color.web("#c3c2b7");

    // ── State ──
    private final List<Float> ringBuf;
    private double yMin = -1.0;
    private double yMax =  1.0;
    private boolean autoRange = true;

    // ── Hover state ──
    private double hoverX = -1;  // -1 = not in plot area
    private double hoverY = -1;
    public WaveformCanvas(double width, double height, int maxSamples, int numChunks) {
        super(width, height);
        this.maxSamples = maxSamples;
        this.numChunks  = numChunks;
        this.ringBuf    = new ArrayList<>(maxSamples + 512);
        setAccessibleText("Real-time DSP signal waveform");

        // ── Hover crosshair + tooltip ──
        setOnMouseMoved(e -> {
            double mx = e.getX(), my = e.getY();
            if (mx >= left() && mx <= right() && my >= top() && my <= bottom()) {
                hoverX = mx;
                hoverY = my;
            } else {
                hoverX = -1;
                hoverY = -1;
            }
            draw();
        });
        setOnMouseExited(e -> {
            hoverX = -1;
            hoverY = -1;
            draw();
        });
    }

    // ── Public API ──

    public void pushChunk(float[] values) {
        for (float v : values) ringBuf.add(v);
        int drop = Math.max(0, ringBuf.size() - maxSamples);
        if (drop > 0) ringBuf.subList(0, drop).clear();
        draw();
    }

    public void clearData() {
        ringBuf.clear();
        draw();
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

        if (ringBuf.isEmpty()) {
            gc.setFill(LABEL);
            gc.setFont(Font.font("Segoe UI", 14));
            gc.fillText("Waiting for data...", w / 2 - 60, h / 2);
        } else {
            drawWaveform(gc);
        }
        drawGridAndAxes(gc);
        if (hoverX >= 0) drawHoverOverlay(gc);
    }

    private void drawWaveform(GraphicsContext gc) {
        double l = left(), t = top(), r = right(), b = bottom();
        double plotW = pw(), plotH = ph();
        if (plotW <= 0 || plotH <= 0) return;

        // Auto-range Y
        if (autoRange && ringBuf.size() > 1) {
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
            for (float v : ringBuf) {
                if (v < min) min = v;
                if (v > max) max = v;
            }
            double m = Math.max(0.1, (max - min) * 0.1);
            yMin = min - m;
            yMax = max + m;
            if (yMax - yMin < 1e-6) { yMin -= 1; yMax += 1; }
        }

        double yRange = yMax - yMin;
        if (yRange <= 0) yRange = 2;
        double yScale = plotH / yRange;

        int n = ringBuf.size();
        if (n == 0) return;
        // spp MUST use maxSamples (fixed window), NOT n.
        // Using n causes horizontal rescaling every chunk → visual "jump".
        double spp = Math.max(1.0, (double) maxSamples / plotW);

        gc.setStroke(SIGNAL);
        gc.setLineWidth(2);  // 2px — dataviz line mark spec
        gc.beginPath();

        boolean first = true;
        for (int px = (int) l; px <= (int) r; px++) {
            int s0 = (int) Math.floor((px - l) * spp);
            int s1 = (int) Math.floor((px - l + 1) * spp);
            if (s0 < 0) s0 = 0;
            if (s1 > n) s1 = n;
            if (s0 >= n) break;

            // Mean of samples mapped to this pixel column
            double sum = 0;
            int count = 0;
            for (int s = s0; s < s1; s++) {
                sum += ringBuf.get(s);
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

    private void drawGridAndAxes(GraphicsContext gc) {
        double l = left(), t = top(), r = right(), b = bottom();
        double plotW = pw(), plotH = ph();

        // Grid
        gc.setStroke(GRID);
        gc.setLineWidth(1);  // hairline — dataviz grid spec
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
        gc.strokeLine(l, t, l, b);  // Y
        gc.strokeLine(l, b, r, b);  // X

        // Y labels
        gc.setFill(LABEL);
        gc.setFont(Font.font("Segoe UI", 9));
        if (!ringBuf.isEmpty()) {
            gc.fillText(fmt(yMax), 2, t + 12);
            gc.fillText(fmt((yMin + yMax) / 2), 2, t + plotH / 2 + 3);
            gc.fillText(fmt(yMin), 2, b - 2);
        } else {
            gc.fillText("1.0", 2, t + 12);
            gc.fillText("0.0", 2, t + plotH / 2 + 3);
            gc.fillText("-1.0", 2, b - 2);
        }

        // X labels — show sample range based on fixed window (maxSamples)
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
    }

    // ── Hover crosshair + tooltip ──

    private void drawHoverOverlay(GraphicsContext gc) {
        double l = left(), t = top(), r = right(), b = bottom();
        double plotW = pw(), plotH = ph();
        if (plotW <= 0 || plotH <= 0 || ringBuf.isEmpty()) return;

        // Find sample at hover X
        double spp = Math.max(1.0, (double) maxSamples / plotW);
        int idx = (int) Math.floor((hoverX - l) * spp);
        if (idx < 0) idx = 0;
        if (idx >= ringBuf.size()) idx = ringBuf.size() - 1;
        float val = ringBuf.get(idx);

        double yRange = yMax - yMin;
        if (yRange <= 0) yRange = 2;
        double yScale = plotH / yRange;
        double dataY = b - (val - yMin) * yScale;
        dataY = Math.max(t, Math.min(b, dataY));

        // Crosshair lines — subtle, dashed, no data-ink
        gc.setStroke(Color.rgb(0, 0, 0, 0.18));
        gc.setLineWidth(1);
        gc.setLineDashes(4, 4);
        gc.strokeLine(hoverX, t, hoverX, b);   // vertical
        gc.setLineDashes(null);

        // Dot at waveform intersection (data Y)
        gc.setFill(SIGNAL);
        gc.fillOval(hoverX - 3, dataY - 3, 6, 6);
        gc.setStroke(BG);
        gc.setLineWidth(1);
        gc.strokeOval(hoverX - 3, dataY - 3, 6, 6);

        // Tooltip box
        String text = String.format("#%d  %.4f", idx, val);
        gc.setFont(Font.font("Segoe UI", 11));
        double tw = text.length() * 7 + 14;  // estimate: ~7px per char + padding
        double th = 22;
        double tx = hoverX + 14;
        double ty = dataY - th - 10;
        if (tx + tw > r) tx = hoverX - tw - 14;
        if (ty < t) ty = dataY + 14;

        gc.setFill(Color.rgb(252, 252, 251, 0.92));        // light surface
        gc.setStroke(Color.rgb(195, 194, 183, 0.7));       // AXIS tint for border
        gc.setLineWidth(1);
        gc.fillRoundRect(tx, ty, tw, th, 4, 4);
        gc.strokeRoundRect(tx, ty, tw, th, 4, 4);

        gc.setFill(Color.web("#2a78d6"));
        gc.fillText(text, tx + 7, ty + 15);
    }

    private static String fmt(double v) {
        if (Math.abs(v) < 1e-6) return "0.00";
        if (Math.abs(v) >= 1000 || Math.abs(v) < 0.01)
            return String.format("%.2e", v);
        return String.format("%.2f", v);
    }

}

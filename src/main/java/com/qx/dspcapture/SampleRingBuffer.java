package com.qx.dspcapture;

import java.util.Arrays;

/**
 * Thread-safe circular buffer that stores the full capture history for all
 * channels as float samples.
 * <p>
 * Write once (from the capture listener, on the JavaFX thread).  Read many
 * (from the canvas render timer and the stats refresh — also JavaFX).
 * {@code totalWritten} is volatile so the render timer always sees a recent
 * value without explicit synchronization.
 */
public class SampleRingBuffer implements DataProvider {

    private final int numChannels;
    private final int capacity;           // per-channel capacity (samples)
    private final float[][] buffers;       // [numChannels][capacity]
    private volatile long totalWritten;   // monotonic sample count

    /**
     * @param numChannels 1–4
     * @param capacity    per-channel ring buffer size in samples
     */
    public SampleRingBuffer(int numChannels, int capacity) {
        this.numChannels = numChannels;
        this.capacity = capacity;
        this.buffers = new float[numChannels][capacity];
    }

    // ── Write ────────────────────────────────────────────────────────

    /**
     * Append {@code count} float values for one channel.
     * Called at capture rate (typically every 10ms) from the JavaFX thread.
     */
    public void push(int channel, float[] values, int count) {
        if (channel >= numChannels || count <= 0) return;
        float[] buf = buffers[channel];
        long wi = totalWritten;
        int cap = capacity;

        int actual = Math.min(count, values.length);
        for (int i = 0; i < actual; i++) {
            buf[(int) ((wi + i) % cap)] = values[i];
        }
        // Advance totalWritten only after all channels have been written
        // (the caller pushes each channel in order, then advances once
        //  after the last channel — see advanceTotalWritten).
    }

    /**
     * Advance the global sample counter by {@code count}.
     * Call this ONCE per capture cycle after pushing all channels.
     */
    public void advanceTotalWritten(int count) {
        if (count > 0) totalWritten += count;
    }

    // ── Read ─────────────────────────────────────────────────────────

    @Override
    public float[] getSamples(int channel, long startSample, int sampleCount) {
        if (sampleCount <= 0 || channel >= numChannels) return new float[0];

        float[] buf = buffers[channel];
        int cap = capacity;
        float[] out = new float[sampleCount];

        long base = startSample;
        for (int i = 0; i < sampleCount; i++) {
            out[i] = buf[(int) ((base + i) % cap)];
        }
        return out;
    }

    @Override
    public long getTotalSamples() {
        return totalWritten;
    }

    @Override
    public int getNumChannels() {
        return numChannels;
    }

    /** Oldest valid sample index (inclusive). */
    public long getValidStart() {
        long tw = totalWritten;
        return Math.max(0, tw - capacity);
    }

    /** Newest valid sample index (exclusive, i.e. totalWritten). */
    public long getValidEnd() {
        return totalWritten;
    }

    /** Number of readable samples currently stored. */
    public long getAvailable() {
        return Math.min(totalWritten, capacity);
    }

    /** Reset all state — clears buffers and zeros the write counter. */
    public void clear() {
        for (int ch = 0; ch < numChannels; ch++) {
            Arrays.fill(buffers[ch], 0f);
        }
        totalWritten = 0;
    }
}

package com.qx.dspcapture;

/**
 * Data access abstraction — the GUI never knows whether samples come from
 * a live DSP ring buffer, a saved .bin file, or a network stream.
 * <p>
 * Implementations must be thread-safe for concurrent reads.
 */
public interface DataProvider {

    /**
     * Read {@code sampleCount} samples for one channel starting at
     * {@code startSample} (global sample index, 0-based).
     * <p>
     * Returns an array of exactly {@code sampleCount} floats. Callers are
     * responsible for clamping startSample / sampleCount to the valid range
     * before calling.
     */
    float[] getSamples(int channel, long startSample, int sampleCount);

    /** Total number of samples ever written (monotonic). */
    long getTotalSamples();

    /** Number of channels (1–4). */
    int getNumChannels();
}

package com.qx.dspcapture;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exports captured DSP data to CSV.
 * Reads directly from the central {@link SampleRingBuffer} — no longer
 * coupled to the Chunk history list.
 */
public class CaptureExporter {

    /** DSP ISR period in microseconds (from Timer0 config). */
    private static final double ISR_PERIOD_US = 200.0;

    /**
     * Export all samples currently stored in the ring buffer as a
     * time-aligned CSV: one row per sample instant, each channel in
     * its own column.
     *
     * <pre>
     *   time_us, Ch0, Ch1, Ch2, Ch3
     * </pre>
     */
    public static void exportCSV(SampleRingBuffer buffer, Path file) throws IOException {
        int numCh = buffer.getNumChannels();
        long total = buffer.getTotalSamples();
        if (total == 0) return;

        // For very large buffers, export in segments to avoid OOM
        final int SEGMENT = 100_000;

        try (PrintWriter w = new PrintWriter(
                Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            // Header
            w.print("time_us");
            for (int ch = 0; ch < numCh; ch++) w.print(",Ch" + ch);
            w.println();

            long cursor = 0;
            while (cursor < total) {
                int seg = (int) Math.min(SEGMENT, total - cursor);

                // Fetch segment for all channels
                float[][] segData = new float[numCh][];
                for (int ch = 0; ch < numCh; ch++) {
                    segData[ch] = buffer.getSamples(ch, cursor, seg);
                }

                // Write rows
                for (int s = 0; s < seg; s++) {
                    double timeUs = (cursor + s) * ISR_PERIOD_US;
                    w.printf("%.1f", timeUs);
                    for (int ch = 0; ch < numCh; ch++) {
                        float[] data = segData[ch];
                        w.printf(",%.6f", (data != null && s < data.length) ? data[s] : 0f);
                    }
                    w.println();
                }

                cursor += seg;
            }
        }
    }
}

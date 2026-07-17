package com.qx.dspcapture;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Exports captured DSP data to CSV or raw binary files.
 */
public class CaptureExporter {

    /**
     * Export chunks as time-aligned CSV: one row per sample instant,
     * with each channel in its own column.
     *
     * Format: timestamp_ms, ch0, ch1, ...
     */
    public static void exportCSV(List<CaptureEngine.Chunk> chunks, Path file) throws IOException {
        // Auto-detect number of channels from the first few chunks
        int numCh = 1;
        for (int i = 1; i < Math.min(chunks.size(), 10); i++) {
            if (chunks.get(i).channel <= chunks.get(i - 1).channel) {
                numCh = chunks.get(i - 1).channel + 1;
                break;
            }
        }

        try (PrintWriter w = new PrintWriter(
                Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            // Header
            w.print("timestamp_ms");
            for (int ch = 0; ch < numCh; ch++) w.print(",Ch" + ch);
            w.println();

            // Group chunks by cycle: N consecutive chunks (one per channel)
            int i = 0;
            while (i + numCh <= chunks.size()) {
                CaptureEngine.Chunk[] cycle = new CaptureEngine.Chunk[numCh];
                boolean valid = true;
                for (int ch = 0; ch < numCh; ch++) {
                    cycle[ch] = chunks.get(i + ch);
                    if (cycle[ch].channel != ch) { valid = false; break; }
                }
                if (!valid) { i++; continue; }

                int newCount = cycle[0].newCount;
                long ts = cycle[0].timestampMs;
                int n = cycle[0].data.length;

                // Write each sample instant as one row
                for (int s = 0; s < newCount; s++) {
                    w.print(ts);
                    for (int ch = 0; ch < numCh; ch++) {
                        CaptureEngine.Chunk c = cycle[ch];
                        int idx = (c.wrValue - newCount + s + n) % n;
                        int raw = c.data[idx];
                        w.printf(",%.6f", Float.intBitsToFloat(raw));
                    }
                    w.println();
                }

                i += numCh;  // advance to next cycle
            }
        }
    }

    /**
     * Export a single chunk as raw little-endian binary (int32 words).
     */
    public static void exportBin(CaptureEngine.Chunk chunk, Path file) throws IOException {
        byte[] raw = new byte[chunk.data.length * 4];
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(raw)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int v : chunk.data) {
            buf.putInt(v);
        }
        Files.write(file, raw);
    }
}

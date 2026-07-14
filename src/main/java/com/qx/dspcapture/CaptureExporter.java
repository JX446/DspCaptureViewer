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
     * Export all chunks to a CSV file.
     *
     * Format: chunk_index, sample_index, value_dec, value_hex
     */
    public static void exportCSV(List<CaptureEngine.Chunk> chunks, Path file) throws IOException {
        try (PrintWriter w = new PrintWriter(
                Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            w.println("chunk,timestamp_ms,sample_index,value_dec,value_hex");
            for (CaptureEngine.Chunk chunk : chunks) {
                int[] data = chunk.data;
                for (int i = 0; i < data.length; i++) {
                    w.printf("%d,%d,%d,%d,0x%X%n",
                            chunk.index, chunk.timestampMs,
                            i, data[i], data[i]);
                }
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

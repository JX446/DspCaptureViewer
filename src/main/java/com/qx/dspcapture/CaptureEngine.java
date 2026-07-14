package com.qx.dspcapture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Background capture engine -- polls the wr (write pointer) register at a
 * configurable interval, and reads the ring buffer when wr has changed.
 *
 * Lifecycle: create → configure → start() → (running) → stop() → close()
 */
public class CaptureEngine {

    // ---- Configuration (set before start()) ----
    private String host = "localhost";
    private int port = 4333;
    private int wrAddr = 0xA000;               // monitor_wr
    private int bufferAddr = 0x9000;           // monitor_buf
    private int bufferSize = 1024;             // words
    private int pollIntervalMs = 10;           // polling interval

    // ---- State ----
    private volatile boolean running;
    private Thread captureThread;
    private ProxyCaptureClient client;
    private int lastWr = -1;
    private int chunkCount;
    private final List<CaptureListener> listeners = new CopyOnWriteArrayList<>();

    // ---- Output ----
    private Path outputDir;
    private boolean saveToDisk = true;

    /**
     * Called when a new chunk of data has been captured.
     * Override {@link #onError} to receive error notifications.
     */
    @FunctionalInterface
    public interface CaptureListener {
        void onChunk(Chunk chunk);
        default void onError(Exception e) {}
    }

    /** One captured buffer-full of data. */
    public static class Chunk {
        public final int index;
        public final int wrValue;        // current write pointer position
        public final int[] data;         // raw 32-bit words (full buffer)
        public final int newCount;       // how many words are NEW since last chunk
        public final long timestampMs;

        public Chunk(int index, int wrValue, int[] data, int newCount, long timestampMs) {
            this.index = index;
            this.wrValue = wrValue;
            this.data = data;
            this.newCount = newCount;
            this.timestampMs = timestampMs;
        }

        public int size() { return data.length; }
    }

    // ---- Configuration setters ----

    public CaptureEngine host(String host) { this.host = host; return this; }
    public CaptureEngine port(int port) { this.port = port; return this; }
    public CaptureEngine wrAddr(int addr) { this.wrAddr = addr; return this; }
    public CaptureEngine bufferAddr(int addr) { this.bufferAddr = addr; return this; }
    public CaptureEngine bufferSize(int size) { this.bufferSize = size; return this; }
    public CaptureEngine pollIntervalMs(int ms) { this.pollIntervalMs = ms; return this; }
    public CaptureEngine outputDir(Path dir) { this.outputDir = dir; return this; }
    public CaptureEngine saveToDisk(boolean save) { this.saveToDisk = save; return this; }

    public void addListener(CaptureListener listener) {
        listeners.add(listener);
    }

    public void removeListener(CaptureListener listener) {
        listeners.remove(listener);
    }

    // ---- Lifecycle ----

    public synchronized void start() throws IOException {
        if (running) return;

        client = new ProxyCaptureClient(host, port);
        client.connect();

        running = true;
        lastWr = -1;
        chunkCount = 0;

        captureThread = new Thread(this::captureLoop, "DspCapture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (captureThread != null) {
            captureThread.interrupt();
            try { captureThread.join(2000); } catch (InterruptedException ignored) {}
            captureThread = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
    }

    public boolean isRunning() { return running; }
    public int getChunkCount() { return chunkCount; }

    // ---- Main capture loop ----

    private void captureLoop() {
        int[] localBuf = new int[bufferSize];
        try {
            while (running) {
                byte[] wrRaw = client.readMemory(wrAddr, 4);
                int wr = java.nio.ByteBuffer.wrap(wrRaw)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
                System.out.println("[CaptureEngine] wr=" + wr + " lastWr=" + lastWr);

                if (wr != lastWr) {
                    long tRead = System.currentTimeMillis();
                    int newCount;

                    if (lastWr == -1) {
                        // First read: full buffer
                        int[] data = client.readWords(bufferAddr, bufferSize);
                        System.arraycopy(data, 0, localBuf, 0, bufferSize);
                        newCount = bufferSize;
                    } else if (wr > lastWr) {
                        // Normal: read [lastWr .. wr-1]
                        newCount = wr - lastWr;
                        int[] seg = client.readWords(bufferAddr + lastWr * 4, newCount);
                        System.arraycopy(seg, 0, localBuf, lastWr, newCount);
                    } else {
                        // Wrap-around: [lastWr .. END] + [0 .. wr-1]
                        int n1 = bufferSize - lastWr;
                        int n2 = wr;
                        newCount = n1 + n2;
                        int[] seg1 = client.readWords(bufferAddr + lastWr * 4, n1);
                        System.arraycopy(seg1, 0, localBuf, lastWr, n1);
                        if (n2 > 0) {
                            int[] seg2 = client.readWords(bufferAddr, n2);
                            System.arraycopy(seg2, 0, localBuf, 0, n2);
                        }
                    }

                    System.out.println("[CaptureEngine] read " + newCount
                            + " new words in " + (System.currentTimeMillis() - tRead) + "ms");

                    Chunk chunk = new Chunk(chunkCount, wr,
                            Arrays.copyOf(localBuf, bufferSize), newCount,
                            System.currentTimeMillis());
                    chunkCount++;

                    if (saveToDisk && outputDir != null) {
                        saveChunk(chunk);
                    }

                    for (CaptureListener l : listeners) {
                        try { l.onChunk(chunk); } catch (Exception ignored) {}
                    }
                }

                lastWr = wr;
                Thread.sleep(pollIntervalMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            for (CaptureListener l : listeners) {
                try { l.onError(e); } catch (Exception ignored) {}
            }
        } finally {
            running = false;
        }
    }

    private void saveChunk(Chunk chunk) {
        try {
            byte[] raw = new byte[chunk.data.length * 4];
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(raw)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            for (int v : chunk.data) buf.putInt(v);
            Files.write(outputDir.resolve("chunk_" + chunk.index + ".bin"), raw);
        } catch (IOException e) {
            // best-effort disk write
        }
    }
}

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
    private int numChannels = 1;              // 1-4

    // ---- State ----
    private volatile boolean running;
    private Thread captureThread;
    private ProxyCaptureClient client;
    private int[][] localBufs;                   // [numChannels][bufferSize]
    private int[] lastWrs;                       // [numChannels]
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
        public final int channel;        // 0-3, which channel
        public final int index;
        public final int wrValue;        // current write pointer position
        public final int[] data;         // raw 32-bit words (full buffer)
        public final int newCount;       // how many words are NEW since last chunk
        public final long timestampMs;

        public Chunk(int channel, int index, int wrValue, int[] data, int newCount, long timestampMs) {
            this.channel = channel;
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
    public CaptureEngine numChannels(int n) { this.numChannels = Math.max(1, Math.min(4, n)); return this; }

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
        chunkCount = 0;

        // Init per-channel state
        localBufs = new int[numChannels][bufferSize];
        lastWrs   = new int[numChannels];
        for (int ch = 0; ch < numChannels; ch++) lastWrs[ch] = -1;

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
    public int getNumChannels() { return numChannels; }

    /** Address for channel N buffer. Stride = (bufferSize+1)*4 bytes. */
    private int chBufferAddr(int ch) { return bufferAddr + ch * (bufferSize + 1) * 4; }
    /** Address for channel N wr — sits right after channel buffer. */
    private int chWrAddr(int ch)    { return chBufferAddr(ch) + bufferSize * 4; }

    // ---- Main capture loop ----

    private void captureLoop() {
        final int combinedWords = bufferSize + 1;  // buffer + adjacent wr
        try {
            // ── Auto-detect: probe old wr (0xA000) to decide layout ──
            int oldWrSample;
            try {
                byte[] r1 = client.readMemory(0xA000, 4);
                oldWrSample = java.nio.ByteBuffer.wrap(r1).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
            } catch (IOException e) {
                System.out.println("[Capture] ERROR probing wr: " + e.getMessage());
                return;
            }
            boolean isOld = (oldWrSample >= 0 && oldWrSample < 1024);

            final int detectedWrAddr = isOld ? 0xA000 : 0x9800;
            final int detectedBufSize = isOld ? 1024 : bufferSize;

            while (running) {
                // ── Phase 1: read wr from Ch0 only ──
                int wr;
                if (lastWrs[0] == -1) {
                                    int[] combined = client.readWords(chBufferAddr(0),
                            isOld ? 1025 : combinedWords);
                    System.arraycopy(combined, 0, localBufs[0], 0, detectedBufSize);
                    wr = combined[detectedBufSize];
                } else {
                    byte[] wrRaw = client.readMemory(detectedWrAddr, 4);
                    wr = java.nio.ByteBuffer.wrap(wrRaw)
                            .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();
                }

                for (int ch = 0; ch < numChannels; ch++) {
                    int lastWr = lastWrs[ch];
                    if (wr == lastWr) continue;

                    int bufAddr = chBufferAddr(ch);
                    int[] localBuf = localBufs[ch];
                    int bs = detectedBufSize;

                    if (lastWr == -1) {
                        if (ch > 0) {
                            int[] data = client.readWords(bufAddr, bs);
                            System.arraycopy(data, 0, localBuf, 0, bs);
                        }
                    } else if (wr > lastWr) {
                        int newCount = wr - lastWr;
                        int[] seg = client.readWords(bufAddr + lastWr * 4, newCount);
                        System.arraycopy(seg, 0, localBuf, lastWr, newCount);
                    } else {
                        int n1 = bs - lastWr;
                        int n2 = wr;
                        int[] seg1 = client.readWords(bufAddr + lastWr * 4, n1);
                        System.arraycopy(seg1, 0, localBuf, lastWr, n1);
                        if (n2 > 0) {
                            int[] seg2 = client.readWords(bufAddr, n2);
                            System.arraycopy(seg2, 0, localBuf, 0, n2);
                        }
                    }

                    int newCount = (lastWr == -1) ? bs
                            : (wr > lastWr) ? wr - lastWr
                            : (bs - lastWr) + wr;

                    Chunk chunk = new Chunk(ch, chunkCount, wr,
                            Arrays.copyOf(localBuf, bs), newCount,
                            System.currentTimeMillis());
                    chunkCount++;

                    if (saveToDisk && outputDir != null) saveChunk(chunk);
                    for (CaptureListener l : listeners) {
                        try { l.onChunk(chunk); } catch (Exception ignored) {}
                    }

                    lastWrs[ch] = wr;
                }

                Thread.sleep(pollIntervalMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.out.println("[Capture] IO ERROR: " + e.getMessage());
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

package com.qx.dspcapture;

import java.io.IOException;
import java.util.Arrays;

/**
 * Throughput benchmark for DSP proxy connection.
 *
 * Usage: mvn exec:java -Dexec.mainClass="com.qx.dspcapture.Benchmark"
 */
public class Benchmark {

    private static final String HOST = "localhost";
    private static final int    PORT = 4333;
    private static final int WR_ADDR  = 0xA000;
    private static final int BUF_ADDR = 0x9000;
    private static final int BUF_SIZE = 1024;    // words (2048 exceeds proxy limit → EOFException)
    private static final int WARMUP   = 100;
    private static final int ITER     = 200;

    public static void main(String[] args) throws Exception {
        System.out.println("=== DSP Proxy Throughput Benchmark ===\n");

        try (ProxyCaptureClient client = new ProxyCaptureClient(HOST, PORT)) {
            client.connect();
            System.out.println("[OK] Connected to " + HOST + ":" + PORT + "\n");

            benchOverhead(client);     // 1. per-request overhead
            benchBulk(client, 1024);   // 2. bulk read 1024 words
            // 2048 words (8192B) exceeds proxy single-read limit → EOFException
            benchStream(client);       // 3. sustained poll + incremental read
        }
    }

    // ── 1. Per-request overhead (round-trip for tiny reads) ──
    //    This is the floor — every poll pays at least this.
    private static void benchOverhead(ProxyCaptureClient c) throws IOException {
        System.out.println("─── 1. Per-request overhead (4-byte read, no data payload) ───");

        for (int i = 0; i < WARMUP; i++) c.readMemory(WR_ADDR, 4);

        long[] samples = new long[ITER];
        for (int i = 0; i < ITER; i++) {
            long t0 = System.nanoTime();
            c.readMemory(WR_ADDR, 4);
            samples[i] = System.nanoTime() - t0;
        }
        Arrays.sort(samples);

        long min = samples[0];
        long max = samples[ITER - 1];
        long p50 = samples[ITER / 2];
        long p95 = samples[(int)(ITER * 0.95)];
        long sum = 0;
        for (long s : samples) sum += s;
        double avg = sum / (double) ITER;

        System.out.printf("  min : %7.0f µs%n", min / 1000.0);
        System.out.printf("  p50 : %7.0f µs%n", p50 / 1000.0);
        System.out.printf("  avg : %7.0f µs%n", avg / 1000.0);
        System.out.printf("  p95 : %7.0f µs%n", p95 / 1000.0);
        System.out.printf("  max : %7.0f µs%n", max / 1000.0);
        System.out.printf("  → max poll rate : %,.0f req/s  (1 / p50)%n",
                1_000_000_000.0 / p50);
        System.out.println();
    }

    // ── 2/3. Bulk burst — sustained reads, measuring raw data throughput ──
    private static void benchBulk(ProxyCaptureClient c, int words) throws IOException {
        int bytes = words * 4;
        int iter = ITER * 1024 / words;  // scale iterations inversely
        if (iter < 20) iter = 20;
        System.out.printf("─── Bulk burst (%d words = %d bytes × %d iterations) ───%n",
                words, bytes, iter);
        for (int i = 0; i < WARMUP * 1024 / words; i++) c.readWords(BUF_ADDR, words);

        long[] samples = new long[iter];
        for (int i = 0; i < iter; i++) {
            long t0 = System.nanoTime();
            c.readWords(BUF_ADDR, words);
            samples[i] = System.nanoTime() - t0;
        }
        Arrays.sort(samples);

        long p50 = samples[iter / 2], p95 = samples[(int)(iter * 0.95)];
        long sum = 0; for (long s : samples) sum += s;
        double avg = sum / (double) iter;
        double mbps = bytes / (avg / 1_000_000_000.0) / (1024 * 1024);
        double dataTimeUs = p50 / 1000.0 - 848.0;
        double pureMbps = dataTimeUs > 0 ? bytes / (dataTimeUs / 1_000_000.0) / (1024 * 1024) : 0;

        System.out.printf("  p50 : %7.0f µs  (%.2f MB/s)%n", p50 / 1000.0, mbps);
        System.out.printf("  avg : %7.0f µs%n", avg / 1000.0);
        System.out.printf("  p95 : %7.0f µs%n", p95 / 1000.0);
        System.out.printf("  扣掉固定开销(848µs): 纯数据传输 %.2f MB/s%n", pureMbps);
        System.out.println();
    }

    // ── 3. Simulated capture stream: poll wr + read delta ──
    //    Incremental read of a typical delta (words between wr polls).
    private static void benchStream(ProxyCaptureClient c) throws IOException {
        System.out.println("─── 3. Simulated capture: poll wr(4B) + read delta(N words) ───");

        int[] deltaSizes = {10, 50, 100, 512};  // typical new-data word counts
        int warm = 30, iter = 100;

        for (int delta : deltaSizes) {
            int dBytes = delta * 4;
            // warmup
            for (int i = 0; i < warm; i++) {
                c.readMemory(WR_ADDR, 4);
                c.readWords(BUF_ADDR, delta);
            }
            long[] samples = new long[iter];
            for (int i = 0; i < iter; i++) {
                long t0 = System.nanoTime();
                c.readMemory(WR_ADDR, 4);
                c.readWords(BUF_ADDR, delta);
                samples[i] = System.nanoTime() - t0;
            }
            Arrays.sort(samples);
            long p50 = samples[iter / 2];
            long sum = 0; for (long s : samples) sum += s;
            double avg = sum / (double) iter;
            double hz = 1_000_000_000.0 / p50;
            System.out.printf("  delta=%4d words (%5d bytes) | p50:%7.0f µs  avg:%7.0f µs  → %6.0f chunks/s%n",
                    delta, dBytes, p50 / 1000.0, avg / 1000.0, hz);
        }
        System.out.println();
    }
}

package com.qx.dspcapture;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Plain TCP client for the DSP capture proxy (port 4333).
 *
 * Protocol: send "R &lt;hex-addr&gt;,&lt;hex-len&gt;\n", receive raw bytes.
 * Pure JDK -- no Eclipse or DSF dependencies.
 */
public class ProxyCaptureClient implements AutoCloseable {

    private final String host;
    private final int port;
    private Socket socket;
    private OutputStream out;
    private DataInputStream in;

    public ProxyCaptureClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /** Connect to the proxy. Idempotent if already connected. */
    private static final int CONNECT_TIMEOUT_MS = 3000;

    public void connect() throws IOException {
        if (socket != null && !socket.isClosed()) {
            return;
        }
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setTcpNoDelay(true);
        out = socket.getOutputStream();
        in = new DataInputStream(socket.getInputStream());
    }

    /** Send "R addr,len\n" and read len bytes. */
    public byte[] readMemory(int addr, int len) throws IOException {
        String cmd = String.format("R %X,%X\n", addr, len);
        out.write(cmd.getBytes());
        out.flush();

        byte[] data = new byte[len];
        in.readFully(data);

        // Drain any immediately-available extra bytes (non-blocking)
        int leftover = in.available();
        if (leftover > 0) {
            in.readFully(new byte[leftover]);
        }
        return data;
    }

    /**
     * Read one 32-bit little-endian word from DSP memory.
     * Convenience for the common act-register polling case.
     */
    public int readWord(int addr) throws IOException {
        byte[] data = readMemory(addr, 4);
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /** Read a block of BUF_SIZE 32-bit words, returned as int[]. */
    public int[] readWords(int addr, int count) throws IOException {
        byte[] data = readMemory(addr, count * 4);
        int[] words = new int[count];
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            words[i] = buf.getInt();
        }
        return words;
    }

    public boolean isConnected() {
        return socket != null && !socket.isClosed() && socket.isConnected();
    }

    // ---- Standalone test (no Eclipse needed) ----

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 4333;

        try (ProxyCaptureClient client = new ProxyCaptureClient(host, port)) {
            client.connect();
            System.out.println("[OK] Connected to " + host + ":" + port);

            // Read AF40+4 (act flag at offset 4 in the 8-byte descriptor)
            int act = client.readWord(0xAF44);
            System.out.println("[*] act  = " + act + " (0x" + Integer.toHexString(act) + ")");

            // Read 8 bytes from AF40 (the full act buffer descriptor)
            byte[] desc = client.readMemory(0xAF40, 8);
            System.out.print("[*] AF40 raw = ");
            for (byte b : desc) System.out.printf("%02X ", b);
            System.out.println();

            // Read 16 words from buffer A (9FA0)
            int[] words = client.readWords(0x9FA0, 16);
            System.out.println("[*] First 16 words @ 0x9FA0:");
            for (int i = 0; i < words.length; i++) {
                System.out.printf("    [%2d] %10d  0x%08X%n", i, words[i], words[i]);
            }
        }
    }

    @Override
    public void close() {
        try {
            if (in != null) in.close();
            if (out != null) out.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            // best-effort
        }
    }
}

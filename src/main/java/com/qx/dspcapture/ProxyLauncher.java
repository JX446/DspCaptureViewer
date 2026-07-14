package com.qx.dspcapture;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

/**
 * Launches or_debug_proxy.exe with correct arguments if not already listening.
 *
 * Minimum command: or_debug_proxy.exe -r &lt;port&gt; --chip&lt;type&gt;
 * e.g. or_debug_proxy.exe -r 4333 --chip37xd
 */
public class ProxyLauncher {

    private final String proxyPath;
    private final String host;
    private final int port;
    private final String chipArg;
    private Process process;

    /**
     * @param proxyPath full path to or_debug_proxy.exe
     * @param host      proxy host (usually localhost)
     * @param port      proxy TCP port (usually 4333)
     * @param chipArg   chip type argument, e.g. "--chip37xd"
     */
    public ProxyLauncher(String proxyPath, String host, int port, String chipArg) {
        this.proxyPath = proxyPath;
        this.host = host;
        this.port = port;
        this.chipArg = chipArg;
    }

    public boolean isProxyRunning() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean startIfNeeded() throws IOException {
        if (isProxyRunning()) {
            System.out.println("[ProxyLauncher] Proxy already running on " + host + ":" + port);
            return true;
        }

        File exe = new File(proxyPath);
        String workDir = exe.getParent();
        String exeName = exe.getName();

        // Build command: .\or_debug_proxy.exe -r 4333 --chip37xd
        ProcessBuilder pb = new ProcessBuilder(
                "cmd", "/c",
                ".\\" + exeName + " -r " + port + " " + chipArg
        );
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);

        System.out.println("[ProxyLauncher] Starting: " + exeName + " -r " + port + " " + chipArg);
        System.out.println("[ProxyLauncher] Work dir: " + workDir);
        process = pb.start();

        // Wait for proxy to be ready (up to 10 seconds)
        for (int i = 0; i < 50; i++) {
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
            if (isProxyRunning()) {
                System.out.println("[ProxyLauncher] Proxy ready after " + (i * 200) + "ms");
                return true;
            }
        }
        System.err.println("[ProxyLauncher] Proxy did not start within 10s");
        return false;
    }

    public void stop() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try { process.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException e) {
                process.destroyForcibly();
            }
            System.out.println("[ProxyLauncher] Proxy stopped");
        }
    }
}

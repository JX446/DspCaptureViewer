"""
DSP Capture Viewer — Python verification script (fixed: incremental append only).

Connects to or_debug_proxy.exe via TCP, polls the DSP write pointer,
reads ONLY new data on each change, and plots a continuous waveform.

Usage:  python capture_plot.py [host] [port]
Default: localhost:4333
"""

import socket
import struct
import sys
import time
import numpy as np
import matplotlib.pyplot as plt
from collections import deque

# ── Config ──
HOST = sys.argv[1] if len(sys.argv) > 1 else "localhost"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 4333

WR_ADDR = 0xA000       # write pointer register
BUF_ADDR = 0x9000      # DSP circular buffer base
BUF_SIZE = 1024         # buffer size in 32-bit words
POLL_INTERVAL = 0.01    # 10 ms
MAX_HISTORY = 8192      # max samples in plot history

# ── TCP helpers ──
def read_memory(sock: socket.socket, addr: int, length: int) -> bytes:
    """Send 'R <hex-addr>,<hex-len>\n' and receive raw bytes."""
    cmd = f"R {addr:X},{length:X}\n".encode()
    sock.sendall(cmd)
    data = bytearray()
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise ConnectionError("Proxy disconnected")
        data.extend(chunk)
    # Drain any extra bytes (shouldn't be any in normal operation)
    sock.setblocking(False)
    try:
        while True:
            extra = sock.recv(4096)
            if not extra:
                break
    except BlockingIOError:
        pass
    sock.setblocking(True)
    return bytes(data)


def read_word(sock: socket.socket, addr: int) -> int:
    """Read one 32-bit little-endian signed word."""
    data = read_memory(sock, addr, 4)
    return struct.unpack("<i", data)[0]


def read_words(sock: socket.socket, addr: int, count: int) -> list[int]:
    """Read count 32-bit little-endian signed words."""
    if count <= 0:
        return []
    data = read_memory(sock, addr, count * 4)
    return list(struct.unpack(f"<{count}i", data))


def raw_to_float(raw: int) -> float:
    """Interpret 32-bit int as IEEE 754 float bits (matching Java Float.intBitsToFloat)."""
    return struct.unpack("<f", struct.pack("<i", raw))[0]


# ── Main ──
def main():
    print(f"Connecting to {HOST}:{PORT}...")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(5)
    sock.connect((HOST, PORT))
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    print("[OK] Connected")

    # Persistent buffer reconstruction (mirrors CaptureEngine.localBuf)
    local_buf = [0] * BUF_SIZE

    # Continuous history — only NEW samples appended each chunk
    float_hist: deque = deque(maxlen=MAX_HISTORY)
    raw_hist: deque = deque(maxlen=MAX_HISTORY)

    last_wr = -1
    chunk_idx = 0
    total_new = 0

    # ── Matplotlib setup ──
    plt.rcParams.update({
        "figure.facecolor": "#0d0d20",
        "axes.facecolor": "#0d0d20",
        "axes.edgecolor": "#555577",
        "axes.labelcolor": "#8888aa",
        "text.color": "#8888aa",
        "xtick.color": "#8888aa",
        "ytick.color": "#8888aa",
        "grid.color": "#1a1a35",
        "lines.linewidth": 1.2,
    })

    fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(14, 8),
                                   gridspec_kw={"height_ratios": [3, 1]})
    fig.tight_layout(pad=3.0)

    ax1.set_title("DSP Buffer — Float (intBitsToFloat)  |  INCREMENTAL append, NO overlap",
                  color="#7c8aff", fontsize=12)
    ax1.set_ylabel("Value")
    ax1.grid(True, alpha=0.5)

    ax2.set_title("DSP Buffer — Raw Integer", color="#7c8aff", fontsize=12)
    ax2.set_xlabel("Sample index (continuous stream, oldest → newest)")
    ax2.set_ylabel("Raw int")
    ax2.grid(True, alpha=0.5)

    (line1,) = ax1.plot([], [], color="#00e5ff", linewidth=0.8)
    (line2,) = ax2.plot([], [], color="#ff9100", linewidth=0.6)

    info_text = ax1.text(0.02, 0.98, "", transform=ax1.transAxes,
                         va="top", color="#8888aa", fontfamily="monospace", fontsize=9)

    plt.ion()
    fig.show()

    print("[*] Polling DSP... (close plot window to stop)")
    print(f"{'chunk':>6} {'wr':>6} {'lastWr':>6} {'new':>6} {'total':>8} {'read_ms':>8} {'f_min':>12} {'f_max':>12}")
    print("-" * 85)

    try:
        while plt.fignum_exists(fig.number):
            wr = read_word(sock, WR_ADDR)

            if wr != last_wr:
                t0 = time.time()
                new_in_this_chunk = 0

                # ── Read new data from DSP ──
                if last_wr == -1:
                    # First read: grab entire buffer
                    words = read_words(sock, BUF_ADDR, BUF_SIZE)
                    local_buf[:] = words
                    new_in_this_chunk = BUF_SIZE
                elif wr > last_wr:
                    # Normal: DSP wrote [lastWr .. wr-1]
                    new_in_this_chunk = wr - last_wr
                    seg = read_words(sock, BUF_ADDR + last_wr * 4, new_in_this_chunk)
                    local_buf[last_wr:wr] = seg
                else:
                    # Wrap: DSP wrote [lastWr .. END] + [0 .. wr-1]
                    n1 = BUF_SIZE - last_wr
                    n2 = wr
                    new_in_this_chunk = n1 + n2
                    seg1 = read_words(sock, BUF_ADDR + last_wr * 4, n1)
                    local_buf[last_wr:] = seg1
                    if n2 > 0:
                        seg2 = read_words(sock, BUF_ADDR, n2)
                        local_buf[:n2] = seg2

                elapsed = (time.time() - t0) * 1000
                chunk_idx += 1
                total_new += new_in_this_chunk

                # ── Append ONLY new samples to history (time order) ──
                if last_wr == -1:
                    # First chunk: all 1024 samples, ordered from wr (oldest) → wr-1 (newest)
                    for i in range(BUF_SIZE):
                        buf_idx = (wr + i) % BUF_SIZE
                        raw = local_buf[buf_idx]
                        float_hist.append(raw_to_float(raw))
                        raw_hist.append(raw)
                elif wr > last_wr:
                    # New data: local_buf[lastWr .. wr-1], already in time order
                    for i in range(new_in_this_chunk):
                        raw = local_buf[last_wr + i]
                        float_hist.append(raw_to_float(raw))
                        raw_hist.append(raw)
                else:
                    # Wrap: local_buf[lastWr..END] then local_buf[0..wr-1]
                    n1 = BUF_SIZE - last_wr
                    n2 = wr
                    for i in range(n1):
                        raw = local_buf[last_wr + i]
                        float_hist.append(raw_to_float(raw))
                        raw_hist.append(raw)
                    for i in range(n2):
                        raw = local_buf[i]
                        float_hist.append(raw_to_float(raw))
                        raw_hist.append(raw)

                # ── Update plots ──
                if float_hist:
                    f_arr = np.array(list(float_hist), dtype=np.float32)
                    r_arr = np.array(list(raw_hist), dtype=np.int32)
                    x_arr = np.arange(len(float_hist))

                    line1.set_data(x_arr, f_arr)
                    line2.set_data(x_arr, r_arr)

                    fmin, fmax = float(np.min(f_arr)), float(np.max(f_arr))
                    rmin, rmax = int(np.min(r_arr)), int(np.max(r_arr))

                    fmargin = max(0.1, (fmax - fmin) * 0.1)
                    ax1.set_xlim(0, max(1, len(float_hist) - 1))
                    ax1.set_ylim(fmin - fmargin, fmax + fmargin)

                    rmargin = max(1, (rmax - rmin) * 0.1)
                    ax2.set_xlim(0, max(1, len(raw_hist) - 1))
                    ax2.set_ylim(rmin - rmargin, rmax + rmargin)

                    info_text.set_text(
                        f"chunks: {chunk_idx}  |  wr: {wr}  |  "
                        f"new this chunk: {new_in_this_chunk}  |  "
                        f"total samples: {len(float_hist)}  |  "
                        f"float: [{fmin:.4f}, {fmax:.4f}]  |  "
                        f"raw: [{rmin}, {rmax}]"
                    )

                fig.canvas.draw_idle()
                fig.canvas.flush_events()

                print(f"{chunk_idx:>6} {wr:>6} {last_wr:>6} {new_in_this_chunk:>6} "
                      f"{len(float_hist):>8} {elapsed:>7.1f}ms {fmin:>12.4f} {fmax:>12.4f}")

            last_wr = wr
            time.sleep(POLL_INTERVAL)

    except KeyboardInterrupt:
        print("\n[!] Interrupted")
    except ConnectionError as e:
        print(f"\n[!] Connection lost: {e}")
    finally:
        sock.close()
        plt.close("all")
        print(f"[*] Done. Total new samples: {total_new} across {chunk_idx} chunks")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Minimal SOCKS5 server (no auth, CONNECT with IPv4 or domain names) for raven/e2e.sh's per-profile proxy check.
Usage: e2e-socks.py <port-file> [remap-host]
Binds 127.0.0.1 on a free port and writes the port number to <port-file>. Every accepted CONNECT is counted in
<port-file>.count (a number) and its requested target is appended to <port-file>.log, so the test can tell whether
traffic went through the proxy. Requests for remap-host (the device-side address of the test server) are connected to
127.0.0.1 instead."""
import socket
import socketserver
import struct
import sys
import threading

PORT_FILE = sys.argv[1]
REMAP = sys.argv[2] if len(sys.argv) > 2 else None
LOCK = threading.Lock()
COUNT = 0


def recv_exact(sock, n):
    data = b""
    while len(data) < n:
        chunk = sock.recv(n - len(data))
        if not chunk:
            raise ConnectionError("closed")
        data += chunk
    return data


def pipe(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        global COUNT
        c = self.request
        try:
            ver, nmethods = recv_exact(c, 2)
            if ver != 5:
                return
            recv_exact(c, nmethods)
            c.sendall(b"\x05\x00")
            ver, cmd, _, atyp = recv_exact(c, 4)
            if atyp == 1:
                host = socket.inet_ntoa(recv_exact(c, 4))
            elif atyp == 3:
                host = recv_exact(c, recv_exact(c, 1)[0]).decode("ascii", "replace")
            else:
                c.sendall(b"\x05\x08\x00\x01\x00\x00\x00\x00\x00\x00")
                return
            (port,) = struct.unpack("!H", recv_exact(c, 2))
            if cmd != 1:
                c.sendall(b"\x05\x07\x00\x01\x00\x00\x00\x00\x00\x00")
                return
            target = "127.0.0.1" if REMAP and host == REMAP else host
            try:
                up = socket.create_connection((target, port), timeout=10)
            except OSError:
                c.sendall(b"\x05\x05\x00\x01\x00\x00\x00\x00\x00\x00")
                return
            c.sendall(b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00")
            with LOCK:
                COUNT += 1
                with open(PORT_FILE + ".count", "w") as f:
                    f.write(str(COUNT))
                with open(PORT_FILE + ".log", "a") as f:
                    f.write("%s %s:%d\n" % ("domain" if atyp == 3 else "ip", host, port))
            up.settimeout(None)
            t = threading.Thread(target=pipe, args=(up, c), daemon=True)
            t.start()
            pipe(c, up)
            t.join(5)
            up.close()
        except (OSError, ValueError):
            pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


srv = Server(("127.0.0.1", 0), Handler)
with open(PORT_FILE + ".count", "w") as f:
    f.write("0")
with open(PORT_FILE, "w") as f:
    f.write(str(srv.server_address[1]))
srv.serve_forever()

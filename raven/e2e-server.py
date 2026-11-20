#!/usr/bin/env python3
"""Tiny test server for raven/e2e.sh: serves e2e-page.html (text/html) and /e2e.txt (as an attachment).
Usage: e2e-server.py <port-file>   Binds 0.0.0.0 on a free port and writes the port number to <port-file>."""
import http.server
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path in ("/", "/e2e-page.html"):
            body = open(os.path.join(HERE, "e2e-page.html"), "rb").read()
            hdr = {"Content-Type": "text/html; charset=utf-8"}
        elif path == "/e2e.txt":
            body = b"raven e2e download\n"
            hdr = {"Content-Type": "text/plain", "Content-Disposition": "attachment; filename=e2e.txt"}
        else:
            self.send_error(404)
            return
        self.send_response(200)
        for k, v in hdr.items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


srv = http.server.ThreadingHTTPServer(("0.0.0.0", 0), H)
with open(sys.argv[1], "w") as f:
    f.write(str(srv.server_address[1]))
srv.serve_forever()

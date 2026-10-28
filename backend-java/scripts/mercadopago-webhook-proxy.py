"""Sandbox tunnel target: expose only the Mercado Pago webhook, never the app.

Run on loopback and point an HTTPS tunnel at --port. The backend continues to
validate the official HMAC and fetch the order; this proxy grants no trust.
"""
import argparse
import http.client
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


class WebhookProxy(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        # Avoid recording signature headers, notification bodies or URL query.
        pass

    def reply(self, status, body):
        encoded = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(encoded)
        self.close_connection = True

    def do_GET(self):
        self.reply(404, {"code": "NOT_FOUND"})

    def do_POST(self):
        if urlsplit(self.path).path != "/api/v1/webhooks/mercadopago":
            self.reply(404, {"code": "NOT_FOUND"})
            return
        try:
            length = int(self.headers.get("Content-Length", "-1"))
        except ValueError:
            length = -1
        if self.headers.get("Transfer-Encoding") or not 0 < length <= 65536:
            self.reply(413, {"code": "NOTIFICATION_SIZE_INVALID"})
            return
        if self.headers.get_content_type() != "application/json":
            self.reply(415, {"code": "NOTIFICATION_TYPE_INVALID"})
            return
        self.connection.settimeout(10)
        upstream = http.client.HTTPConnection("127.0.0.1", self.server.backend_port, timeout=25)
        try:
            body = self.rfile.read(length)
            if len(body) != length:
                self.reply(400, {"code": "NOTIFICATION_INCOMPLETE"})
                return
            headers = {"Content-Type": "application/json"}
            for name in ("x-signature", "x-request-id"):
                if self.headers.get(name):
                    headers[name] = self.headers[name]
            upstream.request("POST", self.path, body, headers)
            response = upstream.getresponse()
            # Provider needs only acknowledgement; do not expose backend details.
            self.reply(response.status, {"received": 200 <= response.status < 300})
        except (OSError, http.client.HTTPException):
            self.reply(503, {"code": "SANDBOX_BACKEND_UNAVAILABLE"})
        finally:
            upstream.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=3987)
    parser.add_argument("--backend-port", type=int, default=3005)
    args = parser.parse_args()
    if not all(1024 <= port <= 65535 for port in (args.port, args.backend_port)) or args.port == args.backend_port:
        parser.error("Use two distinct unprivileged local ports")
    with ThreadingHTTPServer(("127.0.0.1", args.port), WebhookProxy) as server:
        server.backend_port = args.backend_port
        server.serve_forever()

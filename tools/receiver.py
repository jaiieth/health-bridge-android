"""Minimal TLS protocol example; not a durable receiver."""
import hashlib
import hmac
import json
import os
import ssl
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

TOKEN = os.environ["HEALTH_BRIDGE_TOKEN"].encode("utf-8")

class Receiver(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 64 * 1024 * 1024:
                self.send_error(413)
                return
            body = self.rfile.read(size)
            bearer = self.headers.get("Authorization", "").encode("utf-8")
            signature = self.headers.get("X-Health-Bridge-Signature", "").encode("ascii")
            expected = b"sha256=" + hmac.new(TOKEN, body, hashlib.sha256).hexdigest().encode("ascii")
            authorized = hmac.compare_digest(bearer, b"Bearer " + TOKEN)
            signed = hmac.compare_digest(signature, expected)
            if not (authorized and signed):
                self.send_error(401)
                return
            data = json.loads(body)
            if data["schemaVersion"] != 1 or data["batchId"] != self.headers.get("X-Health-Bridge-Batch-Id") or not isinstance(data["records"], list):
                raise ValueError("Invalid batch")
            # Persist and deduplicate data["batchId"] here before acknowledging.
            self.send_response(204)
            self.end_headers()
        except (ValueError, KeyError, UnicodeError):
            self.send_error(400)

if __name__ == "__main__":
    server = HTTPServer(("127.0.0.1", 8443), Receiver)
    server.socket.settimeout(30)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(sys.argv[1], sys.argv[2])
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    server.serve_forever()

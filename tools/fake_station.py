#!/usr/bin/env python3
"""A stand-in for Luna's station server, for testing the app without Luna.

    python3 tools/fake_station.py            # needs: pip install cryptography (segno for a QR)

It speaks the same protocol as web/station.py in the Luna repo (pairing, /say, /talk,
/turn, /clip, /stop, /answer) and answers with canned replies and short beeps instead
of her voice. Recordings sent to /talk are saved, so you can listen to what the
phone's mic actually sent.

Her portrait (/portrait/) is a plain stand-in page; /playing is logged.

Things to type at it:
  ask     she asks a permission question first (Allow / Deny on the device)
  slow    a long, slow reply (to try Stop)
  error   the turn fails part way
  anything else gets an echo.

It makes its own certificate authority (in ~/.cache/luna-fake-station), separate
from Luna's, and remembers paired devices there across restarts, like Luna does: a device paired with this has to Forget and pair again for the real one.
Home-network addresses only, like Luna. Nothing here is meant for anything but testing.
"""
import argparse
import datetime
import hashlib
import http.server
import ipaddress
import json
import math
import os
import secrets
import socket
import ssl
import struct
import threading
import time
import wave
import io
from urllib.parse import parse_qs, urlsplit

COOKIE = "luna_station"
MAX_AUDIO = 6 * 1024 * 1024
MAX_TYPED = 2000
ASK_SECONDS = 120
TURN_KEEP = 300

codes = {}        # one-time pairing codes -> expiry
tokens = set()    # sha256 of paired devices' tokens, kept in devices.json like Luna does
turns = {}
lock = threading.Lock()
args = None


# --- certificates, made like Luna's -----------------------------------------------------
def certificates(folder, ip):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

    os.makedirs(folder, mode=0o700, exist_ok=True)
    ca_path, ca_key_path = os.path.join(folder, "luna-ca.crt"), os.path.join(folder, "ca.key")
    cert_path, key_path = os.path.join(folder, "server.crt"), os.path.join(folder, "server.key")
    now = datetime.datetime.now(datetime.timezone.utc)
    raw = dict(encoding=serialization.Encoding.PEM, format=serialization.PrivateFormat.PKCS8,
               encryption_algorithm=serialization.NoEncryption())
    if not os.path.exists(ca_path):
        key = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Fake Luna station (testing only)")])
        ca = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
              .serial_number(x509.random_serial_number())
              .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=3650))
              .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
              .add_extension(x509.KeyUsage(digital_signature=True, key_cert_sign=True, crl_sign=True,
                                           content_commitment=False, key_encipherment=False, data_encipherment=False,
                                           key_agreement=False, encipher_only=False, decipher_only=False), critical=True)
              .add_extension(x509.SubjectKeyIdentifier.from_public_key(key.public_key()), critical=False)
              .add_extension(x509.NameConstraints(permitted_subtrees=[
                  *(x509.IPAddress(ipaddress.ip_network(n)) for n in
                    ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16")),
                  x509.DNSName("local"), x509.DNSName("localhost")], excluded_subtrees=None), critical=True)
              .sign(key, hashes.SHA256()))
        with open(ca_key_path, "wb") as f:
            f.write(key.private_bytes(**raw))
        os.chmod(ca_key_path, 0o600)
        with open(ca_path, "wb") as f:
            f.write(ca.public_bytes(serialization.Encoding.PEM))
    with open(ca_path, "rb") as f:
        ca = x509.load_pem_x509_certificate(f.read())
    with open(ca_key_path, "rb") as f:
        ca_key = serialization.load_pem_private_key(f.read(), None)
    key = ec.generate_private_key(ec.SECP256R1())
    names = [x509.IPAddress(ipaddress.ip_address(ip)), x509.IPAddress(ipaddress.ip_address("127.0.0.1")),
             x509.DNSName("localhost")]
    cert = (x509.CertificateBuilder().subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, ip)]))
            .issuer_name(ca.subject).public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=397))
            .add_extension(x509.SubjectAlternativeName(names), critical=False)
            .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
            .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
            .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(ca_key.public_key()), critical=False)
            .sign(ca_key, hashes.SHA256()))
    with open(key_path, "wb") as f:
        f.write(key.private_bytes(**raw))
    os.chmod(key_path, 0o600)
    with open(cert_path, "wb") as f:
        f.write(cert.public_bytes(serialization.Encoding.PEM) + ca.public_bytes(serialization.Encoding.PEM))
    fingerprint = ":".join(f"{b:02X}" for b in hashlib.sha256(ca.public_bytes(serialization.Encoding.DER)).digest())
    return ca_path, cert_path, key_path, fingerprint


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))        # no packet is sent; it just picks the route
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def home(address):
    try:
        ip = ipaddress.ip_address(address)
    except ValueError:
        return False
    if getattr(ip, "ipv4_mapped", None):
        ip = ip.ipv4_mapped
    return ip.is_private or ip.is_loopback or ip.is_link_local


# --- a pretend turn -----------------------------------------------------------------
def beep(freq, seconds=0.35, rate=24000):
    """A short tone as a WAV, standing in for one of her spoken sentences."""
    n = int(rate * seconds)
    pcm = b"".join(struct.pack("<h", int(9000 * math.sin(2 * math.pi * freq * i / rate)
                                          * min(1, i / 400, (n - i) / 400))) for i in range(n))
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm)
    return out.getvalue()


class Turn:
    def __init__(self, device):
        self.id = secrets.token_urlsafe(8)
        self.device = device
        self.state, self.heard, self.reply, self.clips = "hearing", None, [], []
        self.ask, self.error, self.typed, self.stopped = None, "", False, False
        self.done_at = 0.0
        self._answer = None
        self._asked = threading.Event()

    def view(self):
        return {"state": self.state, "heard": self.heard, "reply": " ".join(self.reply),
                "clips": len(self.clips), "ask": self.ask, "error": self.error,
                "typed": self.typed, "stopped": self.stopped}

    def say(self, sentence, freq):
        if self.stopped:
            return False
        self.state = "speaking"
        self.reply.append(sentence)
        self.clips.append(beep(freq))
        return True

    def run(self, audio=None, text=None):
        try:
            if text is not None:
                self.typed, self.heard = True, text
            else:
                n = len(os.listdir(args.recordings)) + 1
                path = os.path.join(args.recordings, f"recording-{n:03d}.wav")
                with open(path, "wb") as f:
                    f.write(audio)
                with wave.open(io.BytesIO(audio)) as w:
                    seconds = w.getnframes() / w.getframerate()
                    detail = f"{seconds:.1f} s, {w.getframerate()} Hz, {w.getnchannels()} channel(s)"
                time.sleep(0.8)                          # Whisper's turn
                self.heard = f"(fake: heard {detail}, saved as {path})"
                print(f"  recording saved: {path} ({detail})")
            self.state = "thinking"
            words = self.heard.lower() if self.typed else ""   # the keywords are for typing
            time.sleep(0.7)
            if "ask" in words:
                self.ask = {"kind": "file", "title": "Write ~/notes/test.txt?",
                            "body": "A test question from the fake station.\nNothing is written either way.", "warn": False}
                self._asked.wait(ASK_SECONDS)
                allowed = bool(self._asked.is_set() and self._answer)
                self.ask = None
                self.say("You allowed it." if allowed else "You said no, so I won't.", 660)
            elif "error" in words:
                self.say("Starting to answer...", 440)
                time.sleep(0.6)
                self.error = "a pretend failure from the fake station"
                return
            elif "slow" in words:
                for i in range(12):
                    if not self.say(f"Slow sentence {i + 1} of 12.", 330 + 40 * i):
                        return
                    time.sleep(1.2)
            else:
                for i, s in enumerate(["This is the fake station, not Luna.",
                                       f"You said: {self.heard}",
                                       "Each beep stands in for one of her spoken sentences."]):
                    if not self.say(s, [523, 659, 784][i]):
                        return
                    time.sleep(0.6)
        except Exception as e:
            self.error = str(e)[:300]
        finally:
            self.state = "done"
            self.done_at = time.time()

    def answer(self, allow):
        self._answer = bool(allow)
        self._asked.set()


# --- the two servers ----------------------------------------------------------------
class Base(http.server.BaseHTTPRequestHandler):
    server_version = "FakeLuna"
    sys_version = ""

    def log_message(self, fmt, *a):
        print(f"  {self.client_address[0]} {fmt % a}")

    def send(self, code, body=b"", kind="text/plain; charset=utf-8", headers=()):
        body = body.encode() if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        for k, v in headers:
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def json(self, code, obj, headers=()):
        self.send(code, json.dumps(obj), "application/json", headers)

    def allowed(self):
        if home(self.client_address[0]):
            return True
        self.send(403, "home network only")
        return False


class Setup(Base):
    def do_GET(self):
        if not self.allowed():
            return
        url = urlsplit(self.path)
        if url.path == "/luna-ca.crt":
            with open(args.ca_path, "rb") as f:
                return self.send(200, f.read(), "application/x-x509-ca-cert")
        if url.path == "/":
            return self.send(200, "Fake Luna station. Pair with the app.")
        self.send(404, "not found")


def hashed(token):
    return hashlib.sha256(token.encode()).hexdigest()


def load_tokens():
    try:
        with open(os.path.join(args.dir, "devices.json")) as f:
            tokens.update(json.load(f))
    except (OSError, ValueError):
        pass


def save_tokens():
    path = os.path.join(args.dir, "devices.json")
    with open(path, "w") as f:
        json.dump(sorted(tokens), f)
    os.chmod(path, 0o600)


def token_of(headers):
    for part in (headers.get("Cookie") or "").split(";"):
        k, _, v = part.strip().partition("=")
        if k == COOKIE and v:
            return v
    return ""


class Station(Base):
    def device(self):
        t = token_of(self.headers)
        return t if t and hashed(t) in tokens else None

    def turn(self, tid, device):
        t = turns.get(tid)
        return t if t and t.device == device else None

    def do_GET(self):
        if not self.allowed():
            return
        url = urlsplit(self.path)
        device = self.device()
        if url.path == "/pair":
            code = (parse_qs(url.query).get("code") or [""])[0]
            with lock:
                until = codes.pop(code, 0)
            if until < time.time():
                return self.send(403, "That pairing code is used up or too old.")
            token = secrets.token_urlsafe(32)
            with lock:
                tokens.add(hashed(token))
                save_tokens()
            print(f"  paired a device ({len(tokens)} now)")
            return self.send(303, "", headers=[("Location", "/"), ("Set-Cookie", f"{COOKIE}={token}; Path=/; Secure; HttpOnly; SameSite=Lax")])
        if not device:
            return self.json(401, {"error": "not paired"})
        if url.path.startswith("/turn/"):
            t = self.turn(url.path[6:], device)
            return self.json(200, t.view()) if t else self.json(404, {"error": "no such turn"})
        if url.path in ("/portrait", "/portrait/"):
            return self.send(200, FAKE_PORTRAIT, "text/html; charset=utf-8")
        if url.path.startswith("/clip/"):
            tid, _, n = url.path[6:].partition("/")
            t = self.turn(tid, device)
            if not t or not n.isdigit() or int(n) >= len(t.clips):
                return self.json(404, {"error": "no such clip"})
            return self.send(200, t.clips[int(n)], "audio/wav")
        self.json(404, {"error": "not found"})

    def do_POST(self):
        if not self.allowed():
            return
        length = int(self.headers.get("Content-Length") or 0)
        if length < 0 or length > MAX_AUDIO:
            return self.json(413, {"error": "too long"})
        body = self.rfile.read(length)
        origin = self.headers.get("Origin")
        if origin and urlsplit(origin).netloc != self.headers.get("Host"):
            return self.json(403, {"error": "wrong origin"})
        url = urlsplit(self.path)
        device = self.device()
        if not device:
            return self.json(401, {"error": "not paired"})
        if url.path in ("/talk", "/say"):
            if url.path == "/talk":
                if not body.startswith(b"RIFF") or self.headers.get("Content-Type") != "audio/wav":
                    return self.json(400, {"error": "send a WAV recording"})
                work = dict(audio=body)
            else:
                try:
                    text = str(json.loads(body or b"{}").get("text") or "").strip()
                except (ValueError, AttributeError):
                    text = ""
                if not text:
                    return self.json(400, {"error": "nothing to say"})
                if len(text) > MAX_TYPED:
                    return self.json(413, {"error": f"keep it under {MAX_TYPED} characters"})
                work = dict(text=text)
            with lock:
                for k in [k for k, t in turns.items() if t.done_at and time.time() - t.done_at > TURN_KEEP]:
                    del turns[k]
                if any(t.device == device and not t.done_at for t in turns.values()):
                    return self.json(409, {"error": "she's still answering the last one"})
                t = Turn(device)
                turns[t.id] = t
            threading.Thread(target=t.run, kwargs=work, daemon=True).start()
            return self.json(200, {"turn": t.id})
        if url.path.startswith("/playing/"):
            tid, _, n = url.path[9:].partition("/")
            if not self.turn(tid, device):
                return self.json(404, {"error": "no such turn"})
            print(f"  playing clip {n} (her portrait's mouth would move now)")
            return self.json(200, {"ok": True})
        if url.path.startswith("/stop/"):
            t = self.turn(url.path[6:], device)
            if not t:
                return self.json(404, {"error": "no such turn"})
            t.stopped = True
            return self.json(200, {"ok": True})
        if url.path.startswith("/answer/"):
            t = self.turn(url.path[8:], device)
            try:
                allow = bool(json.loads(body or b"{}").get("allow"))
            except (ValueError, AttributeError):
                return self.json(400, {"error": "bad answer"})
            if not t or not t.ask:
                return self.json(404, {"error": "nothing asked"})
            t.answer(allow)
            return self.json(200, {"ok": True})
        self.json(404, {"error": "not found"})


# Stands in for her portrait: proves the app's WebView got in (cookie, certificate,
# localStorage) without needing her model. Real Luna serves the real thing here.
FAKE_PORTRAIT = """<!doctype html><html><head><meta name=viewport content="width=device-width,initial-scale=1">
<style>body{margin:0;height:100vh;display:grid;place-items:center;background:#14111F;color:#E8E3F5;font:16px sans-serif;text-align:center}
b{color:#FF87D7;font-size:40px}</style></head><body><div><b>Luna</b><p>Fake portrait: the WebView is in.</p>
<p id=s></p></div><script>
let n = +(localStorage.getItem('visits') || 0) + 1; localStorage.setItem('visits', n);
document.getElementById('s').textContent = 'Opened ' + n + ' time(s) (localStorage works).';
</script></body></html>"""


def new_code(ip):
    code = secrets.token_urlsafe(9)
    with lock:
        codes[code] = time.time() + 15 * 60
    link = f"http://{ip}:{args.port + 1}/?code={code}"
    print(f"\nPairing link (one use, 15 minutes):\n  {link}")
    try:
        import segno
        segno.make(link, error="m").terminal(compact=True)
    except ImportError:
        print("  (pip install segno to get a QR code here; or type it in the app)")
    print()


def main():
    global args
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--ip", help="address to put in the certificate and link (default: this PC's LAN address)")
    p.add_argument("--port", type=int, default=8793, help="station port; setup is one above (default 8793)")
    p.add_argument("--dir", default=os.path.expanduser("~/.cache/luna-fake-station"))
    args = p.parse_args()
    ip = args.ip or lan_ip()
    args.recordings = os.path.join(args.dir, "recordings")
    os.makedirs(args.recordings, exist_ok=True)
    args.ca_path, cert, key, fingerprint = certificates(args.dir, ip)
    load_tokens()

    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(cert, key)
    station = http.server.ThreadingHTTPServer(("0.0.0.0", args.port), Station)
    station.socket = ctx.wrap_socket(station.socket, server_side=True)
    setup = http.server.ThreadingHTTPServer(("0.0.0.0", args.port + 1), Setup)
    for s in (station, setup):
        threading.Thread(target=s.serve_forever, daemon=True).start()

    print(f"Fake Luna station on {ip}: https port {args.port}, setup port {args.port + 1}")
    print(f"Its certificate (SHA-256): {fingerprint}")
    print(f"Recordings go to {args.recordings}")
    print(f"Paired devices: {len(tokens)} (delete {os.path.join(args.dir, 'devices.json')} to unpair them all)")
    print("If the phone can't connect on Fedora: "
          f"sudo firewall-cmd --add-port={args.port}-{args.port + 1}/tcp (until reboot)")
    new_code(ip)
    try:
        while True:
            input("Press Enter for a new pairing code, Ctrl+C to quit.\n")
            new_code(ip)
    except (KeyboardInterrupt, EOFError):
        print("\nbye")


if __name__ == "__main__":
    main()

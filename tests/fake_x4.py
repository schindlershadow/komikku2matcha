#!/usr/bin/env python3
"""Test double for an X4 in File Transfer mode: WebDAV with the firmware's rules + UDP discovery.

Mirrors src/network/WebDAVHandler.cpp and CrossPointWebServer.cpp: PUT needs the parent folder to
exist, MKCOL answers 405 for an existing folder and 409 without a parent, any '.'-prefixed segment is
refused, DELETE is recursive, PROPFIND Depth 1 lists sizes; UDP "hello" on 8134 gets
"crosspoint (on <host>);81".
    python3 fake_x4.py <sd-root> <bind-ip> <http-port>
"""
import os, shutil, socket, sys, threading, urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(sys.argv[1]).resolve(); ROOT.mkdir(parents=True, exist_ok=True)
BIND, PORT = sys.argv[2], int(sys.argv[3])
LOG = []
SETTINGS = {"reversePageTurn": 0}  # a subset of the firmware's SettingsList keys


def udp():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", 8134))
    while True:
        data, addr = s.recvfrom(64)
        if data == b"hello":
            s.sendto(b"crosspoint (on CrossPoint-Reader-FAKE);81", addr)


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def path_(self):
        # firmware WebServer::urlDecode turns '+' into a space, like unquote_plus
        p = urllib.parse.unquote_plus(urllib.parse.urlsplit(self.path).path).rstrip("/") or "/"
        return p

    def fs(self, p):
        return ROOT / p.lstrip("/")

    def protected(self, p):
        return any(seg.startswith(".") for seg in p.split("/") if seg)

    def reply(self, code, body=b"", ctype="text/plain"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_PROPFIND(self):
        p = self.path_(); f = self.fs(p)
        if self.protected(p) or not f.exists():
            return self.reply(404)
        # Like the firmware: Depth "infinity" is treated as 1, dot-entries are hidden, folder hrefs end in '/'.
        kids = sorted(c for c in f.iterdir() if not c.name.startswith(".")) if f.is_dir() and self.headers.get("Depth", "1") != "0" else []
        items = [f] + kids
        rs = []
        for it in items:
            href = urllib.parse.quote("/" + str(it.relative_to(ROOT)) if it != ROOT else "/") + ("/" if it.is_dir() and it != ROOT else "")
            prop = "<d:resourcetype><d:collection/></d:resourcetype>" if it.is_dir() else \
                f"<d:resourcetype/><d:getcontentlength>{it.stat().st_size}</d:getcontentlength>"
            rs.append(f"<d:response><d:href>{href}</d:href><d:propstat><d:prop>{prop}</d:prop>"
                      f"<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        self.reply(207, f'<?xml version="1.0"?><d:multistatus xmlns:d="DAV:">{"".join(rs)}</d:multistatus>'.encode(),
                   "application/xml")

    def do_MKCOL(self):
        p = self.path_(); f = self.fs(p)
        if self.protected(p):
            return self.reply(403)
        if f.exists():
            return self.reply(405, b"Already exists")
        if not f.parent.exists():
            return self.reply(409, b"Parent directory does not exist")
        f.mkdir(); LOG.append(("MKCOL", p)); self.reply(201)

    def do_PUT(self):
        p = self.path_(); f = self.fs(p)
        n = int(self.headers.get("Content-Length") or 0)
        data = self.rfile.read(n)
        if self.protected(p) or not f.parent.exists():
            return self.reply(409 if not self.protected(p) else 403)
        existed = f.exists(); f.write_bytes(data); LOG.append(("PUT", p))
        self.reply(204 if existed else 201)

    def do_POST(self):
        u = urllib.parse.urlsplit(self.path)
        if u.path == "/mkdir":
            # handleCreateFolder: form "path" (parent) + "name"; 400 if it exists; only filesystem folders protected.
            n = int(self.headers.get("Content-Length") or 0)
            q = urllib.parse.parse_qs(self.rfile.read(n).decode())
            parent, name = q.get("path", ["/"])[0], q.get("name", [""])[0]
            if not name or "/" in name or name in ("System Volume Information", "XTCache"):
                return self.reply(400)
            f = self.fs(parent.rstrip("/") + "/" + name)
            if f.exists():
                return self.reply(400, b"Folder already exists")
            f.mkdir(parents=False)
            return self.reply(200, b"Folder created")
        if u.path == "/upload":
            # handleUpload: multipart file into ?path=; refuses to overwrite an existing file.
            import email.parser, email.policy
            n = int(self.headers.get("Content-Length") or 0)
            msg = email.parser.BytesParser(policy=email.policy.HTTP).parsebytes(
                b"Content-Type: " + self.headers["Content-Type"].encode() + b"\r\n\r\n" + self.rfile.read(n))
            part = next(msg.iter_parts())
            name = part.get_filename()
            folder = self.fs(urllib.parse.parse_qs(u.query).get("path", ["/"])[0])
            target = folder / name
            if not folder.is_dir():
                return self.reply(400, b"Folder missing")
            if target.exists():
                return self.reply(400, ("File already exists: " + name).encode())
            target.write_bytes(part.get_payload(decode=True))
            return self.reply(200, b"File uploaded successfully")
        if urllib.parse.urlsplit(self.path).path == "/api/settings":
            # Like handlePostSettings: a JSON object of {key: value}; toggles store 0/1.
            import json as _json
            n = int(self.headers.get("Content-Length") or 0)
            try:
                doc = _json.loads(self.rfile.read(n) or b"")
            except ValueError:
                return self.reply(400, b"Invalid JSON")
            for k, v in doc.items():
                if k in SETTINGS:
                    SETTINGS[k] = 1 if v else 0
            return self.reply(200, b"Settings saved")
        # Like CrossPointWebServer::handleDelete: form field "path"; only the filesystem's own folders are
        # protected (dot-folders such as /.crosspoint are deletable); folders go recursively.
        if urllib.parse.urlsplit(self.path).path != "/delete":
            return self.reply(404)
        n = int(self.headers.get("Content-Length") or 0)
        p = urllib.parse.parse_qs(self.rfile.read(n).decode()).get("path", [""])[0]
        if not p or p == "/" or ".." in p.split("/") or any(s in ("System Volume Information", "XTCache") for s in p.split("/")):
            return self.reply(400)
        f = self.fs(p)
        if not f.exists():
            return self.reply(400, b"not found")
        shutil.rmtree(f) if f.is_dir() else f.unlink(); LOG.append(("POST-DELETE", p)); self.reply(200)

    def do_DELETE(self):
        p = self.path_(); f = self.fs(p)
        if self.protected(p) or p == "/":
            return self.reply(403)
        if not f.exists():
            return self.reply(404)
        shutil.rmtree(f) if f.is_dir() else f.unlink(); LOG.append(("DELETE", p)); self.reply(204)

    def do_GET(self):
        u = urllib.parse.urlsplit(self.path)
        if u.path == "/download":
            # Like CrossPointWebServer::handleDownload: only the file's own name may not start with '.',
            # so /.crosspoint/<cache>/progress.bin is readable; folders give 400, missing files 404.
            p = urllib.parse.parse_qs(u.query).get("path", [""])[0]
            name = p.rstrip("/").rsplit("/", 1)[-1]
            if not p or p == "/" or name.startswith(".") or ".." in p.split("/"):
                return self.reply(403 if name.startswith(".") else 400)
            f = self.fs(p)
            if not f.exists():
                return self.reply(404)
            if f.is_dir():
                return self.reply(400, b"Path is a directory")
            return self.reply(200, f.read_bytes(), "application/octet-stream")
        if u.path == "/api/settings":
            import json as _json
            items = [{"key": k, "name": k, "category": "Controls", "type": "toggle", "value": v} for k, v in SETTINGS.items()]
            return self.reply(200, _json.dumps(items).encode(), "application/json")
        if u.path == "/api/files":
            # Like handleFileListData: JSON list of a folder; only the filesystem's own folders are refused,
            # so /.crosspoint/<cache> can be listed.
            p = urllib.parse.parse_qs(u.query).get("path", ["/"])[0]
            f = self.fs(p)
            if any(s in ("System Volume Information", "XTCache") for s in p.split("/")) or ".." in p.split("/"):
                return self.reply(403)
            import json as _json
            items = [{"name": c.name, "size": c.stat().st_size if c.is_file() else 0, "isDirectory": c.is_dir(),
                      "isEpub": c.name.endswith(".epub")} for c in sorted(f.iterdir())] if f.is_dir() else []
            return self.reply(200, _json.dumps(items).encode(), "application/json")
        if self.path == "/__log":
            import json
            return self.reply(200, json.dumps(LOG).encode(), "application/json")
        self.reply(404)


threading.Thread(target=udp, daemon=True).start()
ThreadingHTTPServer((BIND, PORT), H).serve_forever()

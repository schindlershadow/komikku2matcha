#!/usr/bin/env python3
"""HTTP service behind the komikku2matcha Android app.

It wraps komikku2matcha.py: the phone uploads Komikku chapter CBZs, starts conversion jobs, polls
them, downloads finished books, and has books pushed to the X4 over WiFi (WebDAV), either by this
server or by the phone itself using the manifest/file endpoints.

    python3 k2m_server.py                    # uses ~/.config/k2m-server/config.json
    python3 k2m_server.py --print-token      # show the app token (created on first run)

Every /api route except /api/health needs "Authorization: Bearer <token>". Standard library only;
conversion runs komikku2matcha.py with the configured venv, one job at a time.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import ipaddress
import json
import os
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import komikku2matcha as k2m  # noqa: E402  (shared discovery, naming and state)
import covers  # noqa: E402

VERSION = 1
# K2M_SERVER_HOME puts config and state in one folder instead (used for testing).
_HOME = os.environ.get("K2M_SERVER_HOME")
CONFIG_DIR = Path(_HOME) / "config" if _HOME else Path.home() / ".config/k2m-server"
STATE_DIR = Path(_HOME) / "state" if _HOME else Path.home() / ".local/state/k2m-server"
DEFAULTS = {
    "input": str(Path.home() / "Manga/Komikku"),
    "output": str(Path.home() / "Manga/Matcha"),
    "python": str(HERE / ".venv/bin/python"),
    "language": "ja",
    "host": "0.0.0.0",
    "port": 8765,
    "max_upload_mb": 1024,
    "gotify": False,
    "fetch_covers": True,   # look series art up on AniList
}
DEVICE_UDP_PORT = 8134          # CrossPointWebServer LOCAL_UDP_PORT; reply "crosspoint (on <host>);<wsPort>"
DEVICE_HTTP_PORT = 80           # WebDAV lives on the device's main web server
# Files that can change without the rest of a book changing (a redrawn cover page, a retitle), so a
# book differing only in these is updated in place rather than re-sent whole.
INCREMENTAL_FILES = {"page_0000.jpg", "meta.bin", "toc.idx"}
BOOK_FILES = re.compile(r"^(page_\d+\.(jpg|jpeg|png|bmp)|panels/p\d+_\d+\.(jpg|bmp)|panels\.(idx|dat)|meta\.bin|toc\.idx)$")


# ── Config / auth ───────────────────────────────────────────────


def load_config() -> dict:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    path = CONFIG_DIR / "config.json"
    cfg = dict(DEFAULTS)
    if path.exists():
        cfg.update(json.loads(path.read_text()))
    else:
        path.write_text(json.dumps(DEFAULTS, indent=2) + "\n")
    token_path = CONFIG_DIR / "token"
    if not token_path.exists():
        fd = os.open(token_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(secrets.token_urlsafe(32) + "\n")
    cfg["token"] = token_path.read_text().strip()
    return cfg


def safe_name(name: str) -> bool:
    """One path segment the tool itself could have produced: no separators, no dot-prefix, no controls."""
    return bool(name) and name == name.strip() and not name.startswith(".") and "/" not in name \
        and "\\" not in name and not any(ord(c) < 32 for c in name) and len(name.encode()) <= 200


def within(root: Path, rel: str) -> Path | None:
    p = (root / rel).resolve()
    return p if p == root.resolve() or root.resolve() in p.parents else None


def split_device(device: str) -> tuple[str, int]:
    """'192.168.1.20' or '192.168.1.20:8080' → (ip, port); the X4 itself always serves on 80."""
    ip, _, port = device.partition(":")
    return ip, int(port) if port.isdigit() else DEVICE_HTTP_PORT


def is_lan(device: str) -> bool:
    ip = split_device(device)[0]
    try:
        a = ipaddress.ip_address(ip)
    except ValueError:
        return False
    return a.is_private and not a.is_loopback


# ── Library ─────────────────────────────────────────────────────


def library(cfg, device: str | None = None, cover_page: bool | None = None) -> dict:
    """Titles and chapters in the input folder, with each chapter's conversion status. With [device] /
    [cover_page], a chapter converted for the other screen or with/without a cover page counts as "pending",
    so the app's Sync converts it again."""
    inp, out = Path(cfg["input"]), Path(cfg["output"])
    state = k2m.State(out, readonly=True)
    ign = ignored(cfg)
    titles = []
    for t in k2m.discover(inp, out if inp in out.parents else None):
        chapters = []
        for ch in sorted(t.chapters, key=lambda c: k2m.natural_key(c.cbz.name)):
            key = f"{k2m.MANGA_SUBDIR}/{t.folder}/{ch.folder}"
            e = state.entries.get(key)
            st = ch.cbz.stat()
            fp = {"path": str(ch.cbz.relative_to(inp)), "size": st.st_size, "mtime_ns": st.st_mtime_ns}
            done = e and e.get("sources") == [fp] and (out / key / "panels.idx").is_file()
            if done and ((device in ("x4", "x3") and e.get("device", "x4") != device) or
                         (cover_page is not None and bool(e.get("cover_page")) != cover_page)):
                done = False
            chapters.append({
                "file": ch.cbz.name, "size": st.st_size, "book": key,
                "status": "converted" if done else ("changed" if e else "pending"),
                "text_blocks": e.get("text_blocks") if e else None,
                "ocr": e.get("ocr") if e else None,
            })
        cdir = covers_dir(cfg)
        titles.append({"dir": str(t.src.relative_to(inp)), "folder": t.folder, "chapters": chapters,
                       "ignored": str(t.src.relative_to(inp)) in ign["titles"],
                       "cover": any((cdir / f"{t.folder}{s}").is_file() for s in (".custom.jpg", ".komikku.jpg", ".jpg")),
                       "series": t.display()})
    # Ignored series / chapters whose CBZs are gone still need listing, so the app knows not to upload them.
    return {"titles": titles, "ignored": ign}


def ignored(cfg) -> dict:
    """Chapters / whole series the user deleted and doesn't want synced again: {"titles": [dir], "chapters": ["dir/file"]}."""
    try:
        d = json.loads((STATE_DIR / "ignored.json").read_text())
        return {"titles": list(d.get("titles", [])), "chapters": list(d.get("chapters", []))}
    except (OSError, ValueError):
        return {"titles": [], "chapters": []}


def save_ignored(d: dict):
    p = STATE_DIR / "ignored.json"
    p.write_text(json.dumps({"titles": sorted(set(d["titles"])), "chapters": sorted(set(d["chapters"]))}, ensure_ascii=False))


def covers_dir(cfg) -> Path:
    return Path(cfg["output"]) / ".covers"


def sleep_dir(cfg) -> Path:
    return Path(cfg["output"]) / ".sleep"


def sleep_size(device: str | None) -> tuple[int, int]:
    return k2m.DEVICE_TARGETS.get(device or "x4", k2m.DEVICE_TARGETS["x4"])


def sleep_items(cfg) -> list[dict]:
    """The sleep screens on the server: one per series folder, with where each came from."""
    sdir = sleep_dir(cfg)
    if not sdir.is_dir():
        return []
    series = {t["folder"]: t["series"] for t in library(cfg)["titles"]}
    out = []
    for img in sorted(sdir.glob("*.bmp"), key=lambda p: k2m.natural_key(p.name)):
        st = img.stat()
        out.append({"name": img.stem, "series": series.get(img.stem, img.stem), "bytes": st.st_size,
                    "mtime": st.st_mtime, "custom": covers.sleep_files(sdir, img.stem)[2].is_file()})
    return out


def generate_sleep(cfg, folders: list[str], device: str | None, force: bool = False) -> dict[str, str]:
    """Draw sleep screens from series art (fetching it if needed). folder → result (see covers.make_sleep)."""
    titles = {t["folder"]: t["series"] for t in library(cfg)["titles"]}
    out = {}
    for folder in folders:
        art = covers.cover_art(covers_dir(cfg), folder, titles.get(folder, folder), fetch=cfg.get("fetch_covers", True))
        try:
            out[folder] = covers.make_sleep(sleep_dir(cfg), folder, art, sleep_size(device), force=force)
        except Exception as e:  # a corrupt art file must not stop the others
            out[folder] = f"failed: {e}"
    return out


def cover_candidates(cfg, folder: str) -> list[dict]:
    """Choices for a series' cover: what's in use now (own / Komikku / AniList), more AniList search results,
    and the first page of the first and the latest chapter. Candidate images are cached under .covers/cand/."""
    cdir = covers_dir(cfg)
    cand = cdir / "cand" / folder
    cand.mkdir(parents=True, exist_ok=True)
    out = []
    for suffix, source, label in ((".custom.jpg", "custom", "Your cover"), (".komikku.jpg", "komikku", "Komikku"),
                                  (".jpg", "anilist", "AniList (automatic)")):
        if (cdir / f"{folder}{suffix}").is_file():
            out.append({"id": source, "source": source, "label": label})
    title = next((t for t in library(cfg)["titles"] if t["folder"] == folder), None)
    series = title["series"] if title else folder
    if cfg.get("fetch_covers", True):
        for media in covers.anilist_search(series):
            f = cand / f"anilist-{media['id']}.jpg"
            if f.is_file() or covers.download_jpeg(media["url"], f):
                out.append({"id": f"anilist-{media['id']}", "source": "anilist", "label": media["title"]})
    if title:
        chs = [c for c in title["chapters"]]
        for which, ch in (("first", chs[0] if chs else None), ("latest", chs[-1] if len(chs) > 1 else None)):
            if not ch:
                continue
            f = cand / f"page-{which}.jpg"
            try:
                info = k2m.scan_cbz(Path(cfg["input"]) / title["dir"] / ch["file"])
                if info.pages:
                    with zipfile.ZipFile(info.path) as zf:
                        covers._save_jpeg(zf.read(info.pages[0].name), f)
                    out.append({"id": f"page-{which}", "source": "page",
                                "label": f"First page of {ch['file'].removesuffix('.cbz')}"})
            except Exception:
                pass
    return out


def candidate_path(cfg, folder: str, cid: str) -> Path | None:
    cdir = covers_dir(cfg)
    fixed = {"custom": cdir / f"{folder}.custom.jpg", "komikku": cdir / f"{folder}.komikku.jpg", "anilist": cdir / f"{folder}.jpg"}
    if cid in fixed:
        return fixed[cid] if fixed[cid].is_file() else None
    if not re.fullmatch(r"(anilist-\d+|page-(first|latest))", cid):
        return None
    p = cdir / "cand" / folder / f"{cid}.jpg"
    return p if p.is_file() else None


def books(cfg) -> list[dict]:
    root = Path(cfg["output"]) / k2m.MANGA_SUBDIR
    out = []
    if not root.is_dir():
        return out
    for idx in sorted(root.rglob("panels.idx"), key=lambda p: k2m.natural_key(str(p))):
        book = idx.parent
        if any(part.startswith(".") for part in book.relative_to(root).parts):
            continue  # a .partial/.old folder mid-install
        meta = k2m.read_meta(book / "meta.bin")
        files = book_files(book)
        # "sig" identifies the version: the app computes the same from the X4's folder listing.
        out.append({"path": str(book.relative_to(Path(cfg["output"]))), "title": meta[0] if meta else book.name,
                    "files": len(files), "bytes": sum(s for _, s in files), "dat": (book / "panels.dat").stat().st_size,
                    "sig": book_sig(files)})
    return out


_sha_cache: dict[tuple[str, int, int], str] = {}


def file_sha1(path: Path) -> str:
    """Content hash of a book file, cached by path/size/mtime (a chapter is ~100 files of ~100 KB)."""
    st = path.stat()
    key = (str(path), st.st_size, st.st_mtime_ns)
    if key not in _sha_cache:
        import hashlib
        _sha_cache[key] = hashlib.sha1(path.read_bytes()).hexdigest()
    return _sha_cache[key]


def record_rate(pages: int, seconds: float):
    """Remember conversion speed across jobs (last ~50 chapters) for the first estimate of a new job."""
    p = STATE_DIR / "rates.json"
    try:
        hist = json.loads(p.read_text())
    except (OSError, ValueError):
        hist = []
    hist = (hist + [[pages, seconds]])[-50:]
    p.write_text(json.dumps(hist))


def recent_rate() -> float | None:
    try:
        hist = json.loads((STATE_DIR / "rates.json").read_text())
    except (OSError, ValueError):
        return None
    pages, secs = sum(h[0] for h in hist), sum(h[1] for h in hist)
    return pages / secs if secs else None


def _pushed_path(dest: str) -> Path:
    import hashlib
    return STATE_DIR / "pushed" / (hashlib.sha1(dest.strip("/").lower().encode()).hexdigest() + ".json")


def pushed_record(dest: str) -> dict[str, str]:
    """What this server last sent to that device folder (file → sha1): catches a changed file whose size didn't."""
    try:
        return json.loads(_pushed_path(dest).read_text())
    except (OSError, ValueError):
        return {}


def remember_pushed(dest: str, shas: dict[str, str]):
    p = _pushed_path(dest)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(shas))


def book_sig(files: list[tuple[str, int]]) -> str:
    """Version fingerprint of a book: its top-level files' names and sizes (the X4 lists exactly these
    over WebDAV, so the app can compute the same from the device). Any re-conversion that changes a page,
    the panel data or the title changes it; panels.dat's size alone missed page-only changes."""
    import hashlib
    top = sorted(f"{n}:{s}" for n, s in files if "/" not in n)
    return hashlib.sha1("\n".join(top).encode()).hexdigest()[:16]


def check_book(book: Path) -> list[str]:
    """Problems that stop the X4 showing a page, or that a newer conversion avoids."""
    from PIL import Image  # server venv's Pillow isn't needed: system python3 has it
    issues = []
    for req in ("panels.idx", "panels.dat"):
        if not (book / req).is_file():
            issues.append(f"{req} missing")
    pages = sorted(p for p in book.iterdir() if p.is_file() and p.name.startswith("page_"))
    try:
        count = int.from_bytes((book / "panels.idx").read_bytes()[4:8], "little")
        if count != len(pages):
            issues.append(f"{len(pages)} page files but panels.idx lists {count}")
    except OSError:
        pass
    png = [p.name for p in pages if p.suffix.lower() == ".png"]
    if png:
        issues.append(f"PNG page(s) {', '.join(png[:3])}: the X4 often can't decode PNG mid-chapter (page load error)")
    for p in pages:
        try:
            with Image.open(p) as im:
                if im.info.get("progressive") or im.info.get("progression"):
                    issues.append(f"{p.name} is a progressive JPEG (the X4 shows it blurry or not at all)")
                im.load()
        except Exception as e:
            issues.append(f"{p.name} can't be decoded ({e})")
    return issues


def check_books(cfg, device: str | None = None, cover_page: bool | None = None) -> list[dict]:
    """Every book with problems, and the source chapter to re-convert it from (if the CBZ is still there).
    With [device], books converted for the other screen count as a problem too."""
    out_root = Path(cfg["output"])
    source = {c["book"]: f"{t['dir']}/{c['file']}" for t in library(cfg)["titles"] for c in t["chapters"]}
    state = k2m.State(out_root, readonly=True).entries
    folders = {c["book"]: t["folder"] for t in library(cfg)["titles"] for c in t["chapters"]}
    cdir = covers_dir(cfg)
    found = []
    for b in books(cfg):
        issues = check_book(out_root / b["path"])
        fix = "reconvert" if issues else None       # bad pages: convert again from the CBZ
        entry = state.get(b["path"]) or {}
        made_for = entry.get("device", "x4")
        if device in ("x4", "x3") and made_for != device:
            issues.append(f"converted for the {made_for.upper()} screen; the app is set to {device.upper()}")
            fix = fix or "convert"
        if cover_page:
            folder = folders.get(b["path"], "")
            art = covers.cover_art(cdir, folder, "", fetch=False) if folder else None   # current art, no lookup
            if not entry.get("cover_page"):
                issues.append("no cover page (series art + chapter number)")
                fix = fix or "convert"
            elif not entry.get("cover_art"):
                issues.append("cover page has no series art: " + ("art is available now" if art else
                              "none found yet (Komikku's or AniList's is tried; or use ⋯ → Change cover)"))
                fix = fix or "cover"
            elif art and covers.art_sig(art) != entry.get("cover_art"):
                issues.append("cover page shows older series art (a new cover was set or found)")
                fix = fix or "cover"
        if issues:
            found.append({"path": b["path"], "issues": issues, "chapter": source.get(b["path"]), "fix": fix,
                          "folder": folders.get(b["path"])})
    return found


def book_files(book: Path) -> list[tuple[str, int]]:
    """(relative name, size) of the files that make up a book, panels.idx last: the device treats a
    folder as a book once panels.idx exists, so it must be the final file written anywhere."""
    files = []
    for p in book.rglob("*"):
        if p.is_file():
            rel = p.relative_to(book).as_posix()
            if BOOK_FILES.match(rel):
                files.append((rel, p.stat().st_size))
    files.sort(key=lambda f: (f[0] == "panels.idx", k2m.natural_key(f[0])))
    return files


def resolve_book(cfg, rel: str) -> Path | None:
    out = Path(cfg["output"])
    p = within(out, rel)
    if p is None or not rel.startswith(k2m.MANGA_SUBDIR + "/") or any(s.startswith(".") for s in Path(rel).parts):
        return None
    return p if p.is_dir() else None


# ── Jobs ────────────────────────────────────────────────────────


class Jobs:
    """Each kind (convert, delete, push) has its own queue and worker thread, so a long conversion run never
    blocks a delete or a send. Convert and delete both touch the state file (komikku2matcha.py's State), so
    it merges each save onto whatever is on disk rather than overwriting it (see State.save)."""

    def __init__(self, cfg):
        self.cfg = cfg
        self.lock = threading.Lock()
        self.jobs: dict[str, dict] = {}
        self.procs: dict[str, subprocess.Popen] = {}
        self.queues = {"convert": [], "delete": [], "push": []}
        self.cv = threading.Condition(self.lock)
        (STATE_DIR / "logs").mkdir(parents=True, exist_ok=True)
        self.path = STATE_DIR / "jobs.json"
        resume = []
        if self.path.exists():
            try:
                for j in json.loads(self.path.read_text()):
                    if j["status"] in ("queued", "running"):
                        # Interrupted by a restart: run it again. Conversion skips chapters already done and
                        # sends are delta syncs, so a resumed job only does what's left.
                        j.update(status="queued", started=None, current=None, outcomes=[], done=0,
                                 note="resumed after a server restart")
                        resume.append(j["id"])
                    self.jobs[j["id"]] = j
            except (OSError, ValueError, KeyError):
                pass
        for jid in sorted(resume, key=lambda i: self.jobs[i]["created"]):
            self.queues[self.jobs[jid]["kind"]].append(jid)
        workers = {"convert": int(cfg.get("convert_workers", 2)), "push": int(cfg.get("push_workers", 2)), "delete": 1}
        for kind, n in workers.items():
            for _ in range(max(1, n)):
                threading.Thread(target=self._worker, args=(kind,), daemon=True).start()

    def _save(self):
        recent = sorted(self.jobs.values(), key=lambda j: j["created"])[-200:]
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(recent))
        tmp.replace(self.path)

    def submit(self, kind: str, params: dict) -> dict:
        job = {"id": uuid.uuid4().hex[:12], "kind": kind, "params": params, "status": "queued",
               "created": time.time(), "started": None, "finished": None, "total": None,
               "done": 0, "current": None, "outcomes": [], "error": None}
        with self.cv:
            self.jobs[job["id"]] = job
            self.queues[kind].append(job["id"])
            self._save()
            self.cv.notify_all()
        return job

    def cancel(self, jid: str) -> bool:
        with self.cv:
            job = self.jobs.get(jid)
            if not job or job["status"] not in ("queued", "running"):
                return False
            job["cancel"] = True
            if job["status"] == "queued":
                self.queues[job["kind"]].remove(jid)
                job["status"], job["finished"] = "cancelled", time.time()
                self._save()
            proc = self.procs.get(jid)
        if proc and proc.poll() is None:
            os.killpg(proc.pid, signal.SIGINT)  # the tool cleans up its temp dirs on Ctrl-C
        return True

    def get(self, jid: str) -> dict | None:
        with self.lock:
            j = self.jobs.get(jid)
            if j and j["status"] == "running" and j["kind"] == "convert":
                self._eta(j)
            return {**json.loads(json.dumps(j)), "titles": self._titles(j)} if j else None

    @staticmethod
    def _titles(job) -> list[str]:
        """Title folders a job is about: converted chapters when known, else what was asked for."""
        def title(label: str) -> str | None:
            parts = label.split("/")
            if job["kind"] == "convert":
                return parts[0] if len(parts) > 1 else None
            return parts[1] if len(parts) > 2 else None
        p = job["params"]
        labels = [o["label"] for o in job.get("outcomes", []) if o.get("status") in ("converted", "pushed", "deleted", "skipped")]
        labels = labels or list(p.get("chapters") or p.get("books") or [])
        return list(dict.fromkeys(t for l in labels if (t := title(l))))

    def list(self) -> list[dict]:
        with self.lock:
            for j in self.jobs.values():
                if j["status"] == "running" and j["kind"] == "convert":
                    self._eta(j)
            return [{**{k: v for k, v in j.items() if k not in ("outcomes", "plan_pages")}, "titles": self._titles(j)}
                    for j in sorted(self.jobs.values(), key=lambda j: -j["created"])[:50]]

    def clear_finished(self) -> int:
        """Drop every job that isn't queued or running. Returns how many were removed."""
        with self.lock:
            done = [jid for jid, j in self.jobs.items() if j["status"] not in ("queued", "running")]
            for jid in done:
                del self.jobs[jid]
                (STATE_DIR / "logs" / f"{jid}.log").unlink(missing_ok=True)
            self._save()
            return len(done)

    def log_text(self, jid: str) -> str | None:
        """A job's conversion log if it wrote one, else a summary of what it did -- push and delete
        jobs have no log file, so a tap on one falls back to their recorded outcomes."""
        with self.lock:
            job = self.jobs.get(jid)
            if not job:
                return None
            job = json.loads(json.dumps(job))
        log_path = STATE_DIR / "logs" / f"{jid}.log"
        if log_path.is_file():
            # K2M-EVENT lines are this same data as machine-readable JSON, for the server's own progress
            # tracking; the human-readable line right after each one already says the same thing.
            lines = [l for l in log_path.read_text(errors="replace").splitlines() if not l.startswith("K2M-EVENT ")]
            return "\n".join(lines)
        lines = [f"{job['kind']} · {job['status']}"]
        if job.get("error"):
            lines.append(f"Error: {job['error']}")
        for o in job.get("outcomes", []):
            detail = f" ({o['detail']})" if o.get("detail") else ""
            lines.append(f"[{o['status']}] {o['label']}{detail}")
            if o.get("warning"):
                lines.append(f"  warning: {o['warning']}")
        return "\n".join(lines)

    def _conflicts(self, a: dict, b: dict) -> bool:
        """Whether two same-kind jobs must not run at once: converting the same chapter twice would race on its
        output, and two sends to one device would fight over it. An empty chapter list means "everything"."""
        pa, pb = a["params"], b["params"]
        if a["kind"] == "convert":
            ca, cb = pa.get("chapters"), pb.get("chapters")
            return not ca or not cb or bool(set(ca) & set(cb))
        if a["kind"] == "push":
            return pa.get("device") == pb.get("device")
        return True

    def _next_runnable(self, kind: str) -> str | None:
        running = [j for j in self.jobs.values() if j["kind"] == kind and j["status"] == "running"]
        for jid in self.queues[kind]:
            if not any(self._conflicts(self.jobs[jid], r) for r in running):
                return jid
        return None

    def _worker(self, kind: str):
        while True:
            with self.cv:
                while (jid := self._next_runnable(kind)) is None:
                    self.cv.wait()
                self.queues[kind].remove(jid)
                job = self.jobs[jid]
                job["status"], job["started"] = "running", time.time()
                self._save()
            try:
                {"convert": self._convert, "delete": self._delete}.get(job["kind"], self._push)(job)
                with self.lock:
                    if job.get("cancel"):
                        job["status"] = "cancelled"
                    elif job["status"] == "running":
                        failed = any(o["status"] == "failed" for o in job["outcomes"])
                        job["status"] = "done_with_errors" if failed else "done"
            except Exception as e:  # never let one job kill the worker
                with self.lock:
                    job["status"], job["error"] = "failed", f"{type(e).__name__}: {e}"
            with self.lock:
                job["finished"] = time.time()
                job["current"] = None
                self.procs.pop(jid, None)
                self._save()
                self.cv.notify_all()
            if self.cfg.get("gotify"):
                n = {s: sum(o["status"] == s for o in job["outcomes"]) for s in ("converted", "pushed", "failed")}
                k2m.notify(f"k2m {kind} {job['status']}",
                           f"{n['converted'] + n['pushed']} done, {n['failed']} failed", 8 if n["failed"] else 3)

    def _convert(self, job):
        p = job["params"]
        cmd = [sys.executable, str(HERE / "komikku2matcha.py"), self.cfg["input"], self.cfg["output"],
               "--python", self.cfg["python"], "--ocr", p.get("ocr", "local")]
        if self.cfg.get("language"):
            cmd += ["--language", self.cfg["language"]]
        for only in p.get("chapters") or []:
            cmd += ["--only", only]
        if p.get("chapters"):
            cmd.append("--exact")
        if p.get("force"):
            cmd.append("--force")
        if p.get("reocr"):
            cmd.append("--reocr")
        if p.get("device") in ("x4", "x3"):
            cmd += ["--device", p["device"]]
        if p.get("cover_page") is False:
            cmd.append("--no-cover-page")
        if not self.cfg.get("fetch_covers", True):
            cmd.append("--no-fetch-covers")
        env = dict(os.environ, K2M_EVENTS="1")
        env.pop("GEMINI_API_KEY", None)  # the server only runs free local OCR
        log = open(STATE_DIR / "logs" / f"{job['id']}.log", "w")
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
                                env=env, start_new_session=True, errors="replace")
        with self.lock:
            self.procs[job["id"]] = proc
        for line in proc.stdout:
            log.write(line)
            log.flush()
            line = line.rstrip("\n")
            if not line.startswith("K2M-EVENT "):
                if line.startswith("Error") or line.startswith("komikku2matcha.py: error"):
                    with self.lock:
                        job["error"] = line
                continue
            ev = json.loads(line[len("K2M-EVENT "):])
            with self.lock:
                if ev["event"] == "start":
                    job["total"] = ev["chapters"]
                elif ev["event"] == "plan":
                    plan = {i["label"]: i["pages"] for i in ev["items"]}
                    job.update(plan_pages=plan, pages_total=sum(plan.values()), pages_done=0, to_convert=len(plan))
                elif ev["event"] == "current":
                    job["current"] = ev["label"]
                    job["current_pages"] = ev.get("pages")
                    job["current_started"] = time.time()
                    # Pages already done before this chapter, so "page" events below (and this chapter's
                    # own outcome) can set an absolute total instead of accumulating on top of each other.
                    job["pages_done_before_current"] = job.get("pages_done", 0)
                elif ev["event"] == "page":
                    # Live progress *within* the current chapter -- panel detection and OCR each walk
                    # every page in turn, so this fires twice per chapter (harmless: it only ever reports
                    # how far the pass in progress has gotten, never accumulates across passes).
                    job["pages_done"] = job.get("pages_done_before_current", 0) + ev["done"]
                elif ev["event"] == "outcome":
                    job["outcomes"].append({k: ev[k] for k in ("label", "status", "detail", "warning") if ev.get(k)})
                    job["done"] = sum(1 for o in job["outcomes"] if not o["label"].endswith("(merged volume)"))
                    job["current"] = None
                    if ev.get("pages") and ev.get("seconds"):
                        job["conv_pages"] = job.get("conv_pages", 0) + ev["pages"]
                        job["conv_seconds"] = job.get("conv_seconds", 0) + ev["seconds"]
                        record_rate(ev["pages"], ev["seconds"])
                    if ev["label"] in job.get("plan_pages", {}):
                        job["pages_done"] = job.get("pages_done_before_current", job.get("pages_done", 0)) + \
                            job["plan_pages"][ev["label"]]
                self._eta(job)
        proc.wait()
        log.close()
        with self.lock:
            if proc.returncode != 0 and not job["outcomes"] and not job.get("cancel"):
                job["status"] = "failed"
                job["error"] = job.get("error") or f"converter exited {proc.returncode}"
            converted = sorted({o["label"].split("/")[0] for o in job["outcomes"] if o["status"] == "converted" and "/" in o["label"]})
        if p.get("sleep") and converted and not job.get("cancel"):
            # A failure here (no art, no network) is a note on the job, never a failed conversion.
            try:
                res = generate_sleep(self.cfg, converted, p.get("device"))
                made = sum(v in ("created", "updated") for v in res.values())
                if made:
                    with self.lock:
                        job["note"] = f"{made} sleep screen(s) made"
            except Exception as e:
                with self.lock:
                    job["note"] = f"sleep screens failed: {e}"

    def _delete(self, job):
        """Delete converted books from the server; with "ignore", also their uploaded CBZs, and remember them so
        the app doesn't upload them again (whole series in "ignore_titles": future chapters too)."""
        p = job["params"]
        out = Path(self.cfg["output"])
        state = k2m.State(out, readonly=False)
        source = {c["book"]: (t["dir"], c["file"]) for t in library(self.cfg)["titles"] for c in t["chapters"]}
        ign = ignored(self.cfg)
        with self.lock:
            job["total"] = len(p["books"])
        for rel in p["books"]:
            book = resolve_book(self.cfg, rel)
            if book:
                shutil.rmtree(book, ignore_errors=True)
            state.remove(rel)
            detail = "deleted"
            if p.get("ignore") and rel in source:
                tdir, f = source[rel]
                (Path(self.cfg["input"]) / tdir / f).unlink(missing_ok=True)
                ign["chapters"].append(f"{tdir}/{f}")
                detail = "deleted; won't be synced again"
            with self.lock:
                job["outcomes"].append({"label": rel, "status": "deleted", "detail": detail})
                job["done"] += 1
        for tdir in p.get("ignore_titles") or []:
            ign["titles"].append(tdir)
        # Title folders left empty (output and input) go too.
        for d in {Path(r).parent for r in p["books"]}:
            od = out / d
            if od.is_dir() and not any(od.iterdir()):
                od.rmdir()
        for tdir in {source[r][0] for r in p["books"] if r in source}:
            idir = Path(self.cfg["input"]) / tdir
            if idir.is_dir() and not any(idir.iterdir()):
                idir.rmdir()
        state.save()
        save_ignored(ign)

    @staticmethod
    def _eta(job):
        """Seconds left: planned pages not yet converted ÷ pages per second (measured in this job once a
        chapter is done, else from recent jobs). pages_done already reflects live progress within the
        chapter in progress (see the "page" event), so it needs no separate adjustment for that."""
        total = job.get("pages_total")
        if not total:
            return
        rate = (job["conv_pages"] / job["conv_seconds"]) if job.get("conv_seconds") else recent_rate()
        if not rate:
            return
        left = total - job.get("pages_done", 0)
        job["eta_seconds"] = max(0, int(left / rate))

    def _push(self, job):
        p = job["params"]
        pusher = DevicePusher(p["device"])
        paths = p["books"]
        # Resolved up front so "bytes_total" (and the progress bar) covers the whole job, not just one book.
        # A delta sync may only send part of a book's bytes, so this is an upper bound, not an exact total.
        books = {rel: (book, book_files(book)) for rel in paths if (book := resolve_book(self.cfg, rel))}
        with self.lock:
            job["total"] = len(paths)
            job["bytes_total"] = sum(s for _, files in books.values() for _, s in files)
            job["bytes_done"] = 0
        for rel in paths:
            if job.get("cancel"):
                break
            with self.lock:
                job["current"] = rel
            entry = books.get(rel)
            if entry is None:
                status, detail = "failed", "no such book on the server"
            else:
                book, _ = entry

                def on_file(sent, job=job):
                    with self.lock:
                        job["bytes_done"] += sent

                try:
                    status, detail = pusher.push(book, (p.get("dests") or {}).get(rel, "/" + rel), replace=p.get("replace", False),
                                                 cancelled=lambda: job.get("cancel"), on_file=on_file)
                except Exception as e:
                    status, detail = "failed", f"{type(e).__name__}: {e}"
            with self.lock:
                job["outcomes"].append({"label": rel, "status": status, "detail": detail})
                job["done"] += 1
            if p.get("sleep") and entry is not None and status != "failed":
                try:
                    pusher.push_sleep(sleep_dir(self.cfg), rel.split("/")[1])
                except Exception as e:
                    with self.lock:
                        job["note"] = f"sleep screen not sent: {e}"


# ── Device (X4 File Transfer mode) ──────────────────────────────


def discover_devices(timeout: float = 2.0) -> list[dict]:
    """Broadcast the firmware's discovery probe; each X4 in File Transfer mode answers."""
    found = {}
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    s.settimeout(0.3)
    try:
        for _ in range(2):
            s.sendto(b"hello", ("255.255.255.255", DEVICE_UDP_PORT))
            end = time.monotonic() + timeout / 2
            while time.monotonic() < end:
                try:
                    data, (ip, _) = s.recvfrom(256)
                except socket.timeout:
                    continue
                m = re.match(r"crosspoint \(on (.*)\);(\d+)", data.decode("utf-8", "replace"))
                if m:
                    found[ip] = {"ip": ip, "hostname": m.group(1)}
    finally:
        s.close()
    return list(found.values())


def firmware_hash(s: str) -> int:
    """std::hash<std::string> on the X4 (libstdc++ _Hash_bytes, 32-bit size_t: MurmurHash2, seed 0xc70f6907)."""
    data, m, M = s.encode(), 0x5BD1E995, 0xFFFFFFFF
    h, i, n = (0xC70F6907 ^ len(data)) & M, 0, len(data)
    while n >= 4:
        k = int.from_bytes(data[i:i + 4], "little")
        k = (k * m) & M; k ^= k >> 24; k = (k * m) & M
        h = (h * m) & M; h ^= k
        i += 4; n -= 4
    if n == 3: h ^= data[i + 2] << 16
    if n >= 2: h ^= data[i + 1] << 8
    if n >= 1: h ^= data[i]; h = (h * m) & M
    h ^= h >> 13; h = (h * m) & M; h ^= h >> 15
    return h


class DevicePusher:
    """Copies a book folder onto the device over its WebDAV server.

    The firmware's PUT needs the parent folder to exist (so every level gets an MKCOL, where 405 means
    it already exists), refuses any path segment starting with '.', and deletes folders recursively.
    panels.idx goes last, so an interrupted push never leaves a folder the Library shows as a book."""

    def __init__(self, device: str):
        if not is_lan(device):
            raise ValueError(f"{device} is not a LAN address")
        ip, port = split_device(device)
        self.base = f"http://{ip}:{port}"

    def _req(self, method: str, path: str, data=None, headers=None, timeout=60):
        route, _, query = path.partition("?")
        url = self.base + urllib.parse.quote(route) + (f"?{query}" if query else "")
        req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.status, r.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    def sizes(self, path: str) -> dict[str, int] | None:
        """File sizes directly inside a device folder, or None if it doesn't exist."""
        status, body = self._req("PROPFIND", path, headers={"Depth": "1"})
        if status == 404:
            return None
        if status != 207:
            raise RuntimeError(f"PROPFIND {path}: HTTP {status}")
        ns = {"d": "DAV:"}
        out = {}
        for r in ET.fromstring(body).findall("d:response", ns):
            href = urllib.parse.unquote(r.findtext("d:href", "", ns)).rstrip("/")
            size = r.findtext(".//d:getcontentlength", None, ns)
            if size is not None:
                out[href.rsplit("/", 1)[-1]] = int(size)
        return out

    def mkdirs(self, path: str):
        cur = ""
        for seg in path.strip("/").split("/"):
            cur += "/" + seg
            status, body = self._req("MKCOL", cur)
            if status not in (200, 201, 405):
                raise RuntimeError(f"MKCOL {cur}: HTTP {status} {body[:80]!r}")

    def listing(self, dest: str, with_panels: bool) -> dict[str, int] | None:
        """Sizes of a book folder's files on the device, panels/ crops included; None if it isn't there."""
        have = self.sizes(dest)
        if have is None:
            return None
        if with_panels:
            have.update({f"panels/{n}": s for n, s in (self.sizes(f"{dest}/panels") or {}).items()})
        return have

    def push(self, book: Path, dest: str, replace: bool, cancelled=lambda: False,
             on_file=lambda sent: None) -> tuple[str, str]:
        """Copy a book to the device. A new book (or replace=True) is copied whole; one already there gets a
        delta sync: only files that are missing, differ in size, or whose content changed since this server
        last sent them go over, and files the book no longer has are deleted (see delta()).
        [on_file] is called with each file's size right after it's sent, for progress reporting."""
        files = book_files(book)
        have = self.listing(dest, any("/" in n for n, _ in files))
        if have is not None and not replace:
            return self.delta(book, dest, files, have, cancelled, on_file)
        if have is not None:
            status, _ = self._req("DELETE", dest)
            if status not in (200, 204, 404):
                raise RuntimeError(f"DELETE {dest}: HTTP {status}")
        self.mkdirs(dest)
        made = set()
        sent = 0
        for name, size in files:
            if cancelled():
                return "failed", "cancelled (partial copy left without panels.idx, so the device ignores it)"
            if "/" in name and (sub := name.rsplit("/", 1)[0]) not in made:
                self.mkdirs(f"{dest}/{sub}")
                made.add(sub)
            self.put(dest, name, (book / name).read_bytes())
            sent += size
            on_file(size)
        if have is not None:
            self.clear_render_cache(dest)
        remember_pushed(dest, {n: file_sha1(book / n) for n, _ in files})
        return "pushed", f"{len(files)} files, {sent / 1e6:.1f} MB"

    def delta(self, book: Path, dest: str, files: list[tuple[str, int]], have: dict[str, int], cancelled,
              on_file=lambda sent: None) -> tuple[str, str]:
        known = pushed_record(dest)
        shas = {n: file_sha1(book / n) for n, _ in files}
        sizes = dict(files)
        need = [n for n, s in files if have.get(n) != s or (n in known and known[n] != shas[n])]
        wanted = {n for n, _ in files}
        stray = [n for n in have if n not in wanted and BOOK_FILES.match(n)]
        if not need and not stray:
            remember_pushed(dest, shas)
            return "skipped", "already on the device"
        if all(n in INCREMENTAL_FILES for n in need) and not stray:
            # A new cover page or title: the pages are untouched, so the book stays listed while it updates.
            for n in need:
                self.put(dest, n, (book / n).read_bytes())
                on_file(sizes[n])
        else:
            # Pages change: hide the book while it's inconsistent (the X4 lists a folder only with panels.idx),
            # send what differs, drop what's gone, and put panels.idx back last.
            self._req("DELETE", f"{dest}/panels.idx")
            if any(n.startswith("panels/") for n in need):
                self.mkdirs(f"{dest}/panels")
            for n in need:
                if cancelled():
                    return "failed", "cancelled (the book is hidden on the device until it's sent again)"
                if n != "panels.idx":
                    self.put(dest, n, (book / n).read_bytes())
                    on_file(sizes[n])
            for n in stray:
                self._req("DELETE", f"{dest}/{n}")
            self.put(dest, "panels.idx", (book / "panels.idx").read_bytes())
            on_file(sizes["panels.idx"])
        self.clear_render_cache(dest)
        remember_pushed(dest, shas)
        size = sum(s for n, s in files if n in need)
        return "pushed", f"updated {len(need)} of {len(files)} files ({size / 1e6:.1f} MB)" + \
            (f", removed {len(stray)}" if stray else "")

    def push_sleep(self, sdir: Path, folder: str) -> bool:
        """Copy a series' sleep screen to /sleep/<folder>.bmp (Matcha's Custom sleep screen picks from there).
        Skipped if the X4 has it with the same size and it's what this server last sent (every BMP for a screen
        is the same size, so size alone can't tell a redrawn one). Returns whether it sent one."""
        img = covers.sleep_files(sdir, folder)[0]
        if not img.is_file():
            return False
        data = img.read_bytes()
        sha = hashlib.sha1(data).hexdigest()
        have = self.sizes("/sleep")
        if have is None:
            self.mkdirs("/sleep")
            have = {}
        known = pushed_record("/sleep")
        if have.get(img.name) == len(data) and known.get(img.name) == sha:
            return False
        self.put("/sleep", img.name, data)
        remember_pushed("/sleep", {**known, img.name: sha})
        return True

    def put(self, dest: str, name: str, data: bytes):
        status, body = self._req("PUT", f"{dest}/{name}", data=data,
                                 headers={"Content-Type": "application/octet-stream"}, timeout=120)
        if status not in (200, 201, 204):
            raise RuntimeError(f"PUT {name}: HTTP {status} {body[:80]!r}")

    def clear_render_cache(self, dest: str):
        """Drop the X4's cached page renders and cover thumbnails for a replaced book, keeping progress.bin
        (see the app's DeviceClient.clearRenderCache: /api/files and /delete reach /.crosspoint)."""
        cache = f"/.crosspoint/manga_{firmware_hash('/' + dest.strip('/'))}"
        status, body = self._req("GET", "/api/files?path=" + urllib.parse.quote(cache, safe=""))
        if status != 200:
            return
        try:
            entries = json.loads(body)
        except ValueError:
            return
        for e in entries:
            name = e.get("name", "")
            if name and name != "progress.bin":
                form = urllib.parse.urlencode({"path": f"{cache}/{name}"}).encode()
                self._req("POST", "/delete", data=form, headers={"Content-Type": "application/x-www-form-urlencoded"})


# ── HTTP ────────────────────────────────────────────────────────


class Handler(BaseHTTPRequestHandler):
    server_version = "k2m-server"
    cfg: dict = {}
    jobs: Jobs = None  # type: ignore

    def log_message(self, fmt, *args):  # quieter than the default, and never logs headers
        sys.stderr.write(f"{self.address_string()} {self.command} {urllib.parse.urlsplit(self.path).path} "
                         f"{args[1] if len(args) > 1 else ''}\n")

    def _json(self, obj, status=200):
        body = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _err(self, status, msg):
        self._json({"error": msg}, status)

    def _authed(self) -> bool:
        got = self.headers.get("Authorization", "")
        ok = got.startswith("Bearer ") and hmac.compare_digest(got[7:].strip().encode(), self.cfg["token"].encode())
        if not ok:
            time.sleep(0.5)  # slows guessing
            self._err(401, "missing or wrong token")
        return ok

    def _body_json(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if n > 1_000_000:
            raise ValueError("request too large")
        return json.loads(self.rfile.read(n) or b"{}")

    def _route(self):
        u = urllib.parse.urlsplit(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        return u.path.rstrip("/") or "/", q

    def do_GET(self):
        path, q = self._route()
        if path == "/api/health":
            return self._json({"ok": True, "version": VERSION})
        if path == "/api/app/version":
            return self._app_version()
        if path == "/app.apk":  # the Android app itself; holds no secrets, so no token needed to fetch it
            apk = HERE / "komikku2matcha.apk"
            if not apk.is_file():
                return self._err(404, "no APK built")
            data = apk.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Disposition", 'attachment; filename="komikku2matcha.apk"')
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            return self.wfile.write(data)
        if not self._authed():
            return
        try:
            if path == "/api/library":
                cover = {"1": True, "0": False}.get(q.get("cover_page", ""))
                return self._json(library(self.cfg, q.get("device"), cover))
            if path == "/api/books":
                return self._json({"books": books(self.cfg)})
            if path == "/api/books/check":
                cover = {"1": True, "0": False}.get(q.get("cover_page", ""))
                return self._json({"problems": check_books(self.cfg, q.get("device"), cover)})
            if path == "/api/covers":
                return self._cover(q.get("title", ""), int(q.get("w") or 0))
            if path == "/api/covers/candidates":
                if not safe_name(q.get("title", "")):
                    return self._err(400, "title: a title folder name")
                return self._json({"candidates": cover_candidates(self.cfg, q["title"])})
            if path == "/api/covers/candidate":
                cand = candidate_path(self.cfg, q.get("title", ""), q.get("id", "")) if safe_name(q.get("title", "")) else None
                return self._image(cand, int(q.get("w") or 0)) if cand else self._err(404, "no such candidate")
            if path == "/api/sleep":
                return self._json({"items": sleep_items(self.cfg)})
            if path == "/api/sleep/file":  # the BMP itself, for sending to the X4 from the phone
                name = q.get("name", "")
                f = covers.sleep_files(sleep_dir(self.cfg), name)[0] if safe_name(name) else None
                if not f or not f.is_file():
                    return self._err(404, "no such sleep screen")
                data = f.read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "image/bmp")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                return self.wfile.write(data)
            if path == "/api/sleep/image":  # a JPEG preview
                name = q.get("name", "")
                f = covers.sleep_files(sleep_dir(self.cfg), name)[0] if safe_name(name) else None
                return self._image(f, int(q.get("w") or 400)) if f and f.is_file() else self._err(404, "no such sleep screen")
            if path == "/api/books/manifest":
                book = resolve_book(self.cfg, q.get("path", ""))
                if not book:
                    return self._err(404, "no such book")
                return self._json({"path": q["path"], "files": [{"name": n, "size": s, "sha1": file_sha1(book / n)}
                                                                 for n, s in book_files(book)]})
            if path == "/api/books/file":
                book = resolve_book(self.cfg, q.get("path", ""))
                name = q.get("name", "")
                if not book or not BOOK_FILES.match(name) or not (f := within(book, name)) or not f.is_file():
                    return self._err(404, "no such file")
                data = f.read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                return self.wfile.write(data)
            if path == "/api/books/zip":
                return self._zip(q.get("path", ""))
            if path == "/api/jobs":
                return self._json({"jobs": self.jobs.list()})
            if m := re.fullmatch(r"/api/jobs/([0-9a-f]{12})", path):
                job = self.jobs.get(m.group(1))
                return self._json(job) if job else self._err(404, "no such job")
            if m := re.fullmatch(r"/api/jobs/([0-9a-f]{12})/log", path):
                text = self.jobs.log_text(m.group(1))
                return self._json({"text": text}) if text is not None else self._err(404, "no such job")
            if path == "/api/device/discover":
                return self._json({"devices": discover_devices()})
            return self._err(404, "not found")
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _app_version(self):
        """The published app's version, for the app's update check (no token: it holds nothing secret)."""
        info = HERE / "komikku2matcha.apk.json"
        if not info.is_file():
            return self._err(404, "no app published")
        return self._json(json.loads(info.read_text()))

    def _cover(self, folder: str, width: int):
        """A series' art (the user's, else AniList's); ?w= scales it down for list thumbnails."""
        if not safe_name(folder):
            return self._err(400, "title: a title folder name")
        cdir = covers_dir(self.cfg)
        path = next((p for p in (cdir / f"{folder}.custom.jpg", cdir / f"{folder}.komikku.jpg", cdir / f"{folder}.jpg") if p.is_file()), None)
        if path is None:
            return self._err(404, "no cover")
        return self._image(path, width)

    def _image(self, path: Path, width: int):
        data = path.read_bytes()
        if 0 < width < 1000:
            from PIL import Image
            import io
            with Image.open(io.BytesIO(data)) as im:
                im.thumbnail((width, width * 2))
                buf = io.BytesIO()
                im.convert("RGB").save(buf, "JPEG", quality=85)
                data = buf.getvalue()
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(data)

    def _zip(self, rel: str):
        """A book, or a whole title's books, as one zip laid out for the SD card root (manga/...)."""
        out = Path(self.cfg["output"])
        target = within(out, rel)
        if not target or not rel.startswith(k2m.MANGA_SUBDIR + "/") or not target.is_dir():
            return self._err(404, "no such book or title")
        roots = [target] if (target / "panels.idx").is_file() else \
            sorted((p.parent for p in target.rglob("panels.idx")
                    if not any(s.startswith(".") for s in p.parent.relative_to(out).parts)),
                   key=lambda b: k2m.natural_key(b.relative_to(out).as_posix()))
        if not roots:
            return self._err(404, "no converted books there")
        entries = [(b / n, f"{b.relative_to(out).as_posix()}/{n}") for b in roots for n, _ in book_files(b)]
        # Stored (images don't compress), so the size is exact and known up front: per file a local
        # header, the data, a 16-byte data descriptor (the socket can't seek back to fill in the
        # header), and a central-directory entry; then the end record.
        size = sum(30 + 2 * len(a.encode()) + f.stat().st_size + 16 + 46 for f, a in entries) + 22
        self.send_response(200)
        self.send_header("Content-Type", "application/zip")
        self.send_header("Content-Length", str(size))
        name = urllib.parse.quote(Path(rel).name + ".zip")
        self.send_header("Content-Disposition", f"attachment; filename*=UTF-8''{name}")
        self.end_headers()
        with zipfile.ZipFile(_Unseekable(self.wfile), "w", zipfile.ZIP_STORED) as zf:
            for f, arc in entries:
                zi = zipfile.ZipInfo(arc, date_time=(2020, 1, 1, 0, 0, 0))
                zi.flag_bits |= 0x800  # UTF-8 names
                zi.file_size = f.stat().st_size
                with zf.open(zi, "w") as dst, open(f, "rb") as src:
                    while chunk := src.read(1 << 20):
                        dst.write(chunk)

    def do_PUT(self):
        path, q = self._route()
        if not self._authed():
            return
        if path == "/api/covers":
            folder = q.get("title", "")
            n = int(self.headers.get("Content-Length") or -1)
            if not safe_name(folder) or not 0 < n <= 20_000_000:
                return self._err(400, "title: a title folder name; body: an image up to 20 MB")
            try:
                covers.set_custom_art(covers_dir(self.cfg), folder, self.rfile.read(n))
            except Exception as e:
                return self._err(400, f"not an image ({e})")
            return self._json({"ok": True})
        if path == "/api/sleep":  # the user's own image (any format Pillow reads) becomes the series' sleep screen
            name = q.get("name", "")
            n = int(self.headers.get("Content-Length") or -1)
            if not safe_name(name) or not 0 < n <= 20_000_000:
                return self._err(400, "name: a title folder name; body: an image up to 20 MB")
            try:
                covers.set_custom_sleep(sleep_dir(self.cfg), name, self.rfile.read(n), sleep_size(q.get("device")))
            except Exception as e:
                return self._err(400, f"not an image ({e})")
            return self._json({"ok": True})
        if path != "/api/upload":
            return self._err(404, "not found")
        title, name = q.get("title", ""), q.get("file", "")
        if not safe_name(title) or not safe_name(name) or not name.lower().endswith(".cbz"):
            return self._err(400, "title and file must be plain names, file ending in .cbz")
        n = int(self.headers.get("Content-Length") or -1)
        if n <= 0 or n > self.cfg["max_upload_mb"] * 1_000_000:
            return self._err(411 if n < 0 else 413, "Content-Length required, within max_upload_mb")
        dest_dir = Path(self.cfg["input"]) / title
        dest_dir.mkdir(parents=True, exist_ok=True)
        tmp = dest_dir / f".upload-{uuid.uuid4().hex}"
        try:
            left = n
            with open(tmp, "wb") as f:
                while left:
                    chunk = self.rfile.read(min(left, 1 << 20))
                    if not chunk:
                        raise ConnectionError("upload cut short")
                    f.write(chunk)
                    left -= len(chunk)
            if not zipfile.is_zipfile(tmp):
                raise ValueError("not a zip/cbz file")
            os.replace(tmp, dest_dir / name)
        except (ValueError, ConnectionError, OSError) as e:
            tmp.unlink(missing_ok=True)
            return self._err(400, str(e))
        self._json({"ok": True, "title": title, "file": name, "size": n})

    def do_POST(self):
        path, q = self._route()
        if not self._authed():
            return
        try:
            body = self._body_json()
        except ValueError as e:
            return self._err(400, str(e))
        if path == "/api/jobs":
            chapters = body.get("chapters") or []
            if not isinstance(chapters, list) or not all(isinstance(c, str) and c.count("/") >= 1 for c in chapters):
                return self._err(400, "chapters: list of '<title dir>/<file.cbz>'")
            params = {"chapters": chapters, "force": bool(body.get("force")), "reocr": bool(body.get("reocr"))}
            if body.get("device") in ("x4", "x3"):
                params["device"] = body["device"]
            if isinstance(body.get("cover_page"), bool):
                params["cover_page"] = body["cover_page"]
            params["sleep"] = bool(body.get("sleep"))
            return self._json(self.jobs.submit("convert", params), 201)
        if path == "/api/device/push":
            device, paths = body.get("device", ""), body.get("books") or []
            if not is_lan(device):
                return self._err(400, "device must be a LAN IP address")
            if not paths or not all(isinstance(p, str) and resolve_book(self.cfg, p) for p in paths):
                return self._err(400, "books: list of existing book paths (manga/<title>/<chapter>)")
            # Where a book already is on the X4 under another name (copied by hand, say): replace it there.
            dests = body.get("dests") or {}
            if not isinstance(dests, dict) or not all(
                    isinstance(k, str) and isinstance(v, str) and v.startswith("/") and
                    not any(seg in ("..", "") or seg.startswith(".") for seg in v.strip("/").split("/"))
                    for k, v in dests.items()):
                return self._err(400, "dests: {book path: '/<folder on the X4>'}")
            return self._json(self.jobs.submit("push", {"device": device, "books": paths, "dests": dests,
                                                        "replace": bool(body.get("replace")),
                                                        "sleep": bool(body.get("sleep"))}), 201)
        if path == "/api/covers/komikku":
            # {"covers": {"<title folder>": "<Komikku's thumbnail URL>"}} from the app's reading of a Komikku backup.
            wanted = body.get("covers") or {}
            if not isinstance(wanted, dict) or not all(safe_name(k) and isinstance(v, str) and v.startswith(("http://", "https://"))
                                                       for k, v in wanted.items()):
                return self._err(400, "covers: {title folder: http(s) URL}")
            return self._json({"saved": {k: covers.set_komikku_art(covers_dir(self.cfg), k, v) for k, v in wanted.items()}})
        if path == "/api/covers/fetch-missing":
            # Titles without any art: look AniList up again now (the one-week "not found" wait is skipped).
            found = {}
            titles = {t["folder"]: t["series"] for t in library(self.cfg)["titles"]}
            for folder in body.get("titles") or []:
                if isinstance(folder, str) and folder in titles:
                    (covers_dir(self.cfg) / f"{folder}.none").unlink(missing_ok=True)
                    found[folder] = covers.cover_art(covers_dir(self.cfg), folder, titles[folder],
                                                     fetch=self.cfg.get("fetch_covers", True)) is not None
            return self._json({"found": found})
        if path == "/api/covers/choose":
            folder, cid = body.get("title", ""), body.get("id", "")
            cand = candidate_path(self.cfg, folder, cid) if safe_name(folder) else None
            if cand is None:
                return self._err(404, "no such candidate")
            if cid != "custom":
                shutil.copyfile(cand, covers_dir(self.cfg) / f"{folder}.custom.jpg")
            return self._json({"ok": True})
        if path == "/api/sleep/generate":
            # {"titles": [folder...] | "all": true, "device": "x4"|"x3", "force": bool}: draw from the series art.
            titles = {t["folder"] for t in library(self.cfg)["titles"]}
            folders = sorted(titles) if body.get("all") else body.get("titles") or []
            if not isinstance(folders, list) or not all(isinstance(f, str) and f in titles for f in folders):
                return self._err(400, "titles: list of title folder names from the library")
            return self._json({"results": generate_sleep(self.cfg, folders, body.get("device"), bool(body.get("force")))})
        if path == "/api/sleep/delete":
            names = body.get("names") or []
            if not isinstance(names, list) or not all(isinstance(n, str) and safe_name(n) for n in names):
                return self._err(400, "names: list of sleep screen names")
            return self._json({"deleted": [n for n in names if covers.delete_sleep(sleep_dir(self.cfg), n)]})
        if path == "/api/books/delete":
            books_ = body.get("books") or []
            if not books_ or not all(isinstance(b, str) and resolve_book(self.cfg, b) for b in books_):
                return self._err(400, "books: list of existing book paths")
            titles = body.get("ignore_titles") or []
            if not all(isinstance(t, str) and safe_name(t.split("/")[-1]) for t in titles):
                return self._err(400, "ignore_titles: title dirs")
            return self._json(self.jobs.submit("delete", {"books": books_, "ignore": bool(body.get("ignore")),
                                                          "ignore_titles": titles}), 201)
        if path == "/api/ignored/remove":
            d = ignored(self.cfg)
            d["titles"] = [t for t in d["titles"] if t not in (body.get("titles") or [])]
            d["chapters"] = [c for c in d["chapters"] if c not in (body.get("chapters") or [])]
            save_ignored(d)
            return self._json({"ok": True, "ignored": d})
        if path in ("/api/covers/reset", "/api/covers/refetch"):
            folder = body.get("title", "")
            if not safe_name(folder):
                return self._err(400, "title: a title folder name")
            cdir = covers_dir(self.cfg)
            (cdir / f"{folder}.custom.jpg").unlink(missing_ok=True)   # both: drop the user's own art
            if path.endswith("refetch"):                            # refetch: also look AniList up again
                (cdir / f"{folder}.jpg").unlink(missing_ok=True)
                (cdir / f"{folder}.none").unlink(missing_ok=True)
                series = next((t["series"] for t in library(self.cfg)["titles"] if t["folder"] == folder), folder)
                found = covers.cover_art(cdir, folder, series)
                return self._json({"ok": True, "found": found is not None})
            return self._json({"ok": True})
        if m := re.fullmatch(r"/api/jobs/([0-9a-f]{12})/cancel", path):
            return self._json({"ok": self.jobs.cancel(m.group(1))})
        if path == "/api/jobs/clear":
            return self._json({"removed": self.jobs.clear_finished()})
        return self._err(404, "not found")


class _Unseekable:
    """zipfile streams to a non-seekable socket file (data descriptors) when tell/seek are absent."""

    def __init__(self, f):
        self.f = f

    def write(self, b):
        return self.f.write(b)

    def flush(self):
        self.f.flush()


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--print-token", action="store_true")
    args = ap.parse_args()
    cfg = load_config()
    if args.print_token:
        print(cfg["token"])
        return
    Handler.cfg = cfg
    Handler.jobs = Jobs(cfg)
    ThreadingHTTPServer.request_queue_size = 128  # the default backlog of 5 drops connections under load
    srv = ThreadingHTTPServer((cfg["host"], int(cfg["port"])), Handler)
    srv.daemon_threads = True
    print(f"k2m-server on {cfg['host']}:{cfg['port']}  input={cfg['input']}  output={cfg['output']}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()

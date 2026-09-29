#!/usr/bin/env python3
"""Convert Komikku / Mihon / Tachiyomi chapter CBZs into Matcha Reader manga folders.

Pipeline per chapter:
  1. scan     read the CBZ's entries, identify each by its magic bytes (not its extension)
  2. normalize extract to a temp dir, flatten folders, drop non-images, convert formats the
              converter can't read (and PNG, which the X4 often can't decode mid-chapter) to
              baseline JPEG, rename pages 0001.ext.. in natural order
  3. convert  run Matcha Reader's tools/manga_convert/convert_manga.py on that folder
  4. install  move the result into OUTPUT/manga/<Title>/<Chapter>/ atomically, record it in
              OUTPUT/.komikku2matcha-state.json so reruns skip it

Source CBZs are only ever read. See README.md for usage.
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import html
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import warnings
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import covers  # noqa: E402
SHIM = HERE / "matcha_ocr_shim.py"
LOCAL_OCR = HERE / "local_ocr.py"
OCR_ENGINES = ["local", "gemini", "off"]
THINKING_LEVELS = ["minimal", "low", "medium", "high", "default"]

STATE_FILE = ".komikku2matcha-state.json"
STATE_VERSION = 1
MANGA_SUBDIR = "manga"
TEST_SUBDIR = "_test"
DEFAULT_TEST_PAGES = 3

# Mihon/Komikku append "_<6 hex>" to a chapter's file name to keep it unique ("Chapter 79.2_f6981c").
MIHON_HASH_SUFFIX = re.compile(r"_[0-9a-f]{6}$")

# Flags this script passes to convert_manga.py; checked against its --help before any work starts,
# so an upstream rename fails loudly instead of per chapter.
REQUIRED_CONVERTER_FLAGS = ["--input", "--output-dir", "--no-ocr", "--max-pages",
                            "--title", "--author", "--language", "--toc-file"]
DEVICE_FLAGS = {"x4": "--x4", "x3": "--x3", "original": None}
# convert_manga.py's DEVICE_TARGETS (portrait width x height); the cover page is drawn at exactly this size
# so the converter passes it through. "original" keeps pages as they are; its cover uses the X4's size.
DEVICE_TARGETS = {"x4": (480, 800), "x3": (528, 792), "original": (480, 800)}

# Formats convert_manga.py reads: its IMAGE_EXTS is {.jpg,.jpeg,.png,.webp,.bmp}, and pages are
# opened with Pillow, so webp additionally needs Pillow's webp codec (checked at startup).
CONVERTER_FORMATS = {"jpeg": ".jpg", "png": ".png", "webp": ".webp", "bmp": ".bmp"}


# ── Small helpers ───────────────────────────────────────────────


def natural_key(s: str):
    """'page 2' < 'page 10'; case-insensitive; the raw string breaks ties ('01' vs '1')."""
    parts = [(0, int(t), "") if t.isdigit() else (1, 0, t.lower()) for t in re.split(r"(\d+)", s) if t]
    return parts, s


_FAT_BAD = re.compile(r'[<>:"/\\|?*\x00-\x1f\x7f]')


def clean_name(name: str, max_bytes: int = 120) -> str:
    """A folder name safe on FAT32/exFAT SD cards: no reserved characters, no leading dot,
    no trailing dot/space, collapsed whitespace, bounded length."""
    name = _FAT_BAD.sub("_", name)
    name = re.sub(r"\s+", " ", name).strip().lstrip(".").rstrip(". ")
    while len(name.encode("utf-8")) > max_bytes:
        name = name[:-1].rstrip(". ")
    return name or "untitled"


def sniff(head: bytes) -> str | None:
    """Image format from magic bytes, or None for anything that isn't an image."""
    if head[:3] == b"\xff\xd8\xff":
        return "jpeg"
    if head[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    if head[:4] == b"RIFF" and head[8:12] == b"WEBP":
        return "webp"
    if head[:6] in (b"GIF87a", b"GIF89a"):
        return "gif"
    if head[:2] == b"BM" and len(head) >= 14:
        return "bmp"
    if head[:4] in (b"II*\x00", b"MM\x00*"):
        return "tiff"
    if head[:2] == b"\xff\x0a" or head[:12] == b"\x00\x00\x00\x0cJXL \r\n\x87\n":
        return "jxl"
    if head[4:8] == b"ftyp":
        brand = head[8:12]
        if brand in (b"avif", b"avis"):
            return "avif"
        if brand in (b"heic", b"heix", b"hevc", b"hevx", b"mif1", b"msf1", b"heim", b"heis"):
            return "heif"
    return None


def safe_rel(name: str) -> str | None:
    """Archive member path as a relative POSIX path, or None if it would escape (zip slip)."""
    norm = name.replace("\\", "/")
    if norm.startswith("/") or re.match(r"^[A-Za-z]:", norm):
        return None
    parts = [p for p in norm.split("/") if p not in ("", ".")]
    if not parts or ".." in parts:
        return None
    return "/".join(parts)


# Whole numbers in a chapter name, not the ".1" of "76.1". The device's shelf view sorts books by
# their meta.bin title with a plain byte compare (CoverLibraryActivity::loadShelfBooks), so
# "Chapter 10" would land before "Chapter 9" unless these are zero-padded to a common width.
_WHOLE_NUMBER = re.compile(r"(?<!\d\.)(?<!\d)\d+")


def pad_numbers(name: str, width: int) -> str:
    return _WHOLE_NUMBER.sub(lambda m: m.group().zfill(width), name)


def read_meta(path: Path) -> tuple[str, str, bytes] | None:
    """(title, author, language trailer) from a converter-written meta.bin (format in convert_manga.py)."""
    try:
        b = path.read_bytes()
        _, tl, al = struct.unpack("<IHH", b[:8])
        return b[8:8 + tl].decode(), b[8 + tl:8 + tl + al].decode(), b[8 + tl + al:]
    except (OSError, struct.error, UnicodeDecodeError):
        return None


def write_meta_title(path: Path, title: str) -> bool:
    """Retitle a book in place: meta.bin is tiny, so fixing the shelf order never needs a reconvert."""
    meta = read_meta(path)
    if meta is None or meta[0] == title:
        return False
    t, a = title.encode(), meta[1].encode()
    tmp = path.with_suffix(".tmp")
    tmp.write_bytes(struct.pack("<IHH", 1, len(t), len(a)) + t + a + meta[2])
    tmp.replace(path)
    return True


def event(kind: str, **data) -> None:
    """With K2M_EVENTS=1 (set by k2m_server.py), also report progress as one JSON line per event, so
    callers never have to parse the human-readable output (titles can contain " - ", for one)."""
    if os.environ.get("K2M_EVENTS"):
        print("K2M-EVENT " + json.dumps({"event": kind, **data}, ensure_ascii=False), flush=True)


def fmt_counts(counts: dict[str, int]) -> str:
    return ", ".join(f"{k}×{v}" for k, v in sorted(counts.items(), key=lambda kv: -kv[1])) or "none"


# ── Archive scan (diagnose) ─────────────────────────────────────


@dataclasses.dataclass
class Page:
    name: str       # member name in the zip
    rel: str        # safe relative path, used for sorting
    kind: str       # sniffed format


@dataclasses.dataclass
class ArchiveInfo:
    path: Path
    pages: list[Page] = dataclasses.field(default_factory=list)
    junk: list[str] = dataclasses.field(default_factory=list)          # non-image entries
    unsafe: list[str] = dataclasses.field(default_factory=list)        # zip-slip paths, dropped
    mismatched: list[tuple[str, str]] = dataclasses.field(default_factory=list)  # (name, real kind)
    depth: int = 0                                                     # deepest folder level of a page
    comicinfo: dict[str, str] = dataclasses.field(default_factory=dict)
    error: str | None = None

    def kinds(self) -> dict[str, int]:
        out: dict[str, int] = {}
        for p in self.pages:
            out[p.kind] = out.get(p.kind, 0) + 1
        return out


EXT_KIND = {".jpg": "jpeg", ".jpeg": "jpeg", ".png": "png", ".webp": "webp", ".gif": "gif", ".bmp": "bmp",
            ".tif": "tiff", ".tiff": "tiff", ".jxl": "jxl", ".avif": "avif", ".heic": "heif", ".heif": "heif"}


def parse_comicinfo(xml: str) -> dict[str, str]:
    out = {}
    for tag in ("Title", "Series", "Number", "Writer", "LanguageISO"):
        m = re.search(rf"<{tag}>([^<]*)</{tag}>", xml)
        if m and m.group(1).strip():
            out[tag] = html.unescape(m.group(1)).strip()
    return out


def scan_cbz(path: Path) -> ArchiveInfo:
    info = ArchiveInfo(path)
    try:
        with zipfile.ZipFile(path) as zf:
            for zi in zf.infolist():
                if zi.is_dir():
                    continue
                rel = safe_rel(zi.filename)
                if rel is None:
                    info.unsafe.append(zi.filename)
                    continue
                with zf.open(zi) as f:
                    head = f.read(32)
                kind = sniff(head)
                if kind is None:
                    info.junk.append(rel)
                    if os.path.basename(rel).lower() == "comicinfo.xml":
                        info.comicinfo = parse_comicinfo(zf.read(zi).decode("utf-8", "replace"))
                    continue
                ext_kind = EXT_KIND.get(os.path.splitext(rel)[1].lower())
                if ext_kind != kind:
                    info.mismatched.append((rel, kind))
                info.pages.append(Page(zi.filename, rel, kind))
                info.depth = max(info.depth, rel.count("/"))
    except zipfile.BadZipFile as e:
        info.error = f"not a valid zip ({e})"
    except RuntimeError as e:  # encrypted members
        info.error = f"cannot read archive ({e})"
    except (OSError, zipfile.LargeZipFile) as e:
        info.error = f"cannot read archive ({e})"
    info.pages.sort(key=lambda p: natural_key(p.rel))
    if not info.error and not info.pages:
        info.error = "no image pages in archive"
    return info


def describe(info: ArchiveInfo, accepted: set[str]) -> list[str]:
    """Human-readable diagnosis lines for one archive."""
    if info.error and not info.pages:
        return [f"ERROR: {info.error}"]
    kinds = info.kinds()
    lines = [f"{len(info.pages)} pages: {fmt_counts(kinds)} | folder depth {info.depth}"
             + (" (will flatten)" if info.depth else " (flat)")]
    convert = {k: v for k, v in kinds.items() if needs_jpeg(k, accepted)}
    if convert:
        lines.append(f"to JPEG (PNG, or not readable by the converter): {fmt_counts(convert)}")
    if info.junk:
        shown = ", ".join(info.junk[:5]) + (f", … (+{len(info.junk) - 5})" if len(info.junk) > 5 else "")
        lines.append(f"dropped non-image: {shown}")
    if info.mismatched:
        ex = ", ".join(f"{n} is {k}" for n, k in info.mismatched[:3])
        lines.append(f"extension ≠ content for {len(info.mismatched)} file(s): {ex}")
    if info.unsafe:
        lines.append(f"dropped unsafe path(s): {', '.join(info.unsafe[:3])}")
    return lines


# ── Normalize ───────────────────────────────────────────────────


class NormalizeError(Exception):
    pass


def needs_jpeg(kind: str, accepted: set[str]) -> bool:
    """Pages that must be re-encoded before the converter sees them: formats it can't read, and PNG.

    PNG is readable, and the converter passes a PNG that already fits the screen through untouched,
    but the X4 decodes PNG with a ~40 KB decoder that needs one contiguous heap block. Mid-chapter
    the heap is often too fragmented for it (PngToFramebufferConverter's pngDecoderFits check) and
    the page shows "page load error". JPEG decodes in a fraction of that, so every page ships as one.
    """
    return kind == "png" or kind not in accepted


def to_jpeg(data: bytes, kind: str, out_path: Path) -> None:
    """Decode any image Pillow or ImageMagick can read and write it as a baseline JPEG (first frame
    only). Transparency is flattened onto white, as the X4's renderer does; grayscale stays grayscale."""
    pil_err = None
    try:
        from PIL import Image
        with warnings.catch_warnings(), Image.open(io.BytesIO(data)) as im:
            warnings.simplefilter("ignore")  # "AVIF support not installed": ImageMagick handles it below
            im.seek(0)
            im.load()
            if im.mode == "P":
                im = im.convert("RGBA" if "transparency" in im.info else "RGB")
            if "A" in im.getbands():
                rgba = im.convert("RGBA")
                bg = Image.new("RGB", im.size, (255, 255, 255))
                bg.paste(rgba, mask=rgba.getchannel("A"))
                im = bg
            if im.mode not in ("L", "RGB"):
                im = im.convert("RGB")
            if im.mode == "RGB" and im.convert("L").convert("RGB").tobytes() == im.tobytes():
                im = im.convert("L")  # a gray page stored as RGB (common for scans): smaller, same look
            im.save(out_path, "JPEG", quality=95, progressive=False, optimize=True)
        return
    except Exception as e:  # unsupported codec in this Pillow build, or corrupt
        pil_err = e
    magick = shutil.which("magick") or shutil.which("convert")
    if not magick:
        raise NormalizeError(f"cannot decode {kind} with Pillow ({pil_err}) and ImageMagick is not installed")
    with tempfile.NamedTemporaryFile(suffix="." + kind, delete=False) as tf:
        tf.write(data)
        src = tf.name
    try:
        r = subprocess.run([magick, f"{src}[0]", "-background", "white", "-flatten", "-quality", "95",
                            "-interlace", "none", f"jpg:{out_path}"], capture_output=True, text=True)
        if r.returncode != 0 or not out_path.exists():
            raise NormalizeError(f"ImageMagick failed on {kind}: {(r.stderr or '').strip()[:200]}")
    finally:
        os.unlink(src)


def verify_decodes(data: bytes) -> str | None:
    """None if Pillow fully decodes the image, else the reason. Catches truncated downloads,
    which would otherwise crash the converter halfway through a chapter."""
    try:
        from PIL import Image
        with Image.open(io.BytesIO(data)) as im:
            im.load()
        return None
    except Exception as e:
        return str(e) or type(e).__name__


def normalize(info: ArchiveInfo, dest: Path, accepted: set[str], start: int = 0) -> tuple[int, int]:
    """Extract info's pages into dest as flat, sequentially numbered files. Returns (pages, converted)."""
    dest.mkdir(parents=True, exist_ok=True)
    converted = 0
    with zipfile.ZipFile(info.path) as zf:
        for i, page in enumerate(info.pages, start=start + 1):
            data = zf.read(page.name)
            if not needs_jpeg(page.kind, accepted):
                bad = verify_decodes(data)
                if bad:
                    raise NormalizeError(f"page {page.rel} is unreadable: {bad}")
                (dest / f"{i:04d}{CONVERTER_FORMATS[page.kind]}").write_bytes(data)
            else:
                try:
                    to_jpeg(data, page.kind, dest / f"{i:04d}.jpg")
                except NormalizeError as e:
                    raise NormalizeError(f"page {page.rel}: {e}") from None
                converted += 1
    return len(info.pages), converted


# ── Library model ───────────────────────────────────────────────


@dataclasses.dataclass
class Chapter:
    cbz: Path
    folder: str                   # output folder name
    info: ArchiveInfo | None = None

    def scan(self) -> ArchiveInfo:
        if self.info is None:
            self.info = scan_cbz(self.cbz)
        return self.info

    def display(self) -> str:
        return self.scan().comicinfo.get("Title") or self.folder

    def sort_key(self):
        num = self.scan().comicinfo.get("Number", "")
        try:
            n = float(num)
        except ValueError:
            n = float("inf")
        return n, natural_key(self.cbz.stem)


@dataclasses.dataclass
class Title:
    src: Path
    folder: str
    chapters: list[Chapter]

    def meta(self) -> dict[str, str]:
        """Series/author/language from the first chapter that has a ComicInfo.xml."""
        for ch in self.chapters:
            ci = ch.scan().comicinfo
            if ci:
                return ci
        return {}

    def display(self) -> str:
        return self.meta().get("Series") or self.src.name

    def number_width(self) -> int:
        """Digits in the longest whole number across all chapter names (every chapter, not just
        the selected ones, so the padding doesn't depend on --only)."""
        return max((len(m.group().lstrip("0") or "0") for ch in self.chapters
                    for m in _WHOLE_NUMBER.finditer(ch.display())), default=1)


def discover(root: Path, exclude: Path | None) -> list[Title]:
    by_dir: dict[Path, list[Path]] = {}
    for p in root.rglob("*"):
        if p.is_file() and p.suffix.lower() == ".cbz":
            if exclude and exclude in p.resolve().parents:
                continue
            by_dir.setdefault(p.parent, []).append(p)

    titles: list[Title] = []
    used: dict[str, int] = {}
    for d in sorted(by_dir, key=lambda d: natural_key(str(d.relative_to(root)))):
        name = clean_name(d.name)
        if name in used:  # same title from two sources: downloads/<source>/<title>
            name = clean_name(f"{d.name} ({d.parent.name})")
        used[name] = 1

        stems = [p.stem for p in by_dir[d]]
        stripped = [MIHON_HASH_SUFFIX.sub("", s) for s in stems]
        chapters = []
        for p, s in zip(by_dir[d], stripped):
            folder = clean_name(s if stripped.count(s) == 1 else p.stem)
            chapters.append(Chapter(p, folder))
        chapters.sort(key=lambda c: natural_key(c.cbz.name))
        titles.append(Title(d, name, chapters))
    return titles


# ── State (what's already converted) ────────────────────────────


class State:
    def __init__(self, out: Path, readonly: bool):
        self.path = out / STATE_FILE
        self.readonly = readonly
        self.entries: dict[str, dict] = {}
        self._dirty: set[str] = set()
        self._removed: set[str] = set()
        if self.path.exists():
            try:
                data = json.loads(self.path.read_text())
                if data.get("version") == STATE_VERSION:
                    self.entries = data.get("entries", {})
            except (OSError, json.JSONDecodeError):
                print(f"Warning: ignoring unreadable {self.path}", file=sys.stderr)

    def set(self, key: str, value: dict):
        self.entries[key] = value
        self._dirty.add(key)
        self._removed.discard(key)

    def touch(self, key: str):
        """Mark a key already mutated in place (through .entries) as needing to be saved."""
        self._dirty.add(key)
        self._removed.discard(key)

    def remove(self, key: str):
        self.entries.pop(key, None)
        self._removed.add(key)
        self._dirty.discard(key)

    def save(self):
        """Merge this instance's own edits onto whatever is on disk now, rather than overwriting the file with
        a snapshot that may be stale: a long conversion run and a server-side delete can touch the state file
        around the same time, each holding its own in-memory copy from whenever it started."""
        if self.readonly:
            return
        fresh = self.entries
        if self.path.exists():
            try:
                data = json.loads(self.path.read_text())
                if data.get("version") == STATE_VERSION:
                    fresh = data.get("entries", {})
            except (OSError, json.JSONDecodeError):
                pass
        for k in self._removed:
            fresh.pop(k, None)
        for k in self._dirty:
            if k in self.entries:
                fresh[k] = self.entries[k]
        self.entries = fresh
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps({"version": STATE_VERSION, "entries": self.entries}, indent=1, ensure_ascii=False))
        tmp.replace(self.path)


def fingerprint(cbzs: list[Path], root: Path) -> list[dict]:
    out = []
    for p in cbzs:
        st = p.stat()
        out.append({"path": str(p.relative_to(root)), "size": st.st_size, "mtime_ns": st.st_mtime_ns})
    return out


# ── Converter ───────────────────────────────────────────────────


@dataclasses.dataclass
class ConverterEnv:
    python: str
    script: Path
    accepted: set[str]
    yolo: bool


def find_converter(arg: str | None) -> Path | None:
    candidates = [arg] if arg else [
        os.environ.get("MATCHA_CONVERTER"),
        HERE / "matcha-reader/tools/manga_convert/convert_manga.py",
        HERE.parent / "matcha-reader/tools/manga_convert/convert_manga.py",
        Path.home() / "matcha-reader/tools/manga_convert/convert_manga.py",
    ]
    for c in candidates:
        if c and Path(c).is_file():
            return Path(c).resolve()
    return None


def preflight(python: str, script: Path, device_flag: str | None, engine: str) -> ConverterEnv:
    """Check the converter's CLI still has the flags we use, and what its interpreter can do."""
    r = subprocess.run([python, str(script), "--help"], capture_output=True, text=True)
    if r.returncode != 0:
        sys.exit(f"Error: `{python} {script} --help` failed:\n{r.stderr.strip()}")
    needed = REQUIRED_CONVERTER_FLAGS + ([device_flag] if device_flag else [])
    missing = [f for f in needed if not re.search(rf"(?<![\w-]){re.escape(f)}(?![\w-])", r.stdout)]
    if missing:
        sys.exit(f"Error: converter at {script} lacks flag(s) {', '.join(missing)}; "
                 "its CLI changed, update this script.")
    probe = ("import importlib.util as u\n"
             "try:\n from PIL import features; print('pil', features.check('webp'))\n"
             "except ImportError: print('nopil')\n"
             "print('yolo', bool(u.find_spec('ultralytics') and u.find_spec('huggingface_hub')))\n")
    out = subprocess.run([python, "-c", probe], capture_output=True, text=True).stdout
    if "nopil" in out:
        sys.exit(f"Error: {python} has no Pillow, which convert_manga.py needs (pip install Pillow).")
    accepted = {"jpeg", "png", "bmp"} | ({"webp"} if "pil True" in out else set())
    if engine == "gemini":
        r = subprocess.run([python, str(SHIM), str(script), "--k2m-check"], capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit((r.stderr or r.stdout).strip() or f"Error: {SHIM.name} --k2m-check failed")
    if engine == "local":
        r = subprocess.run([python, str(LOCAL_OCR), str(script), "--check"], capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit(((r.stderr or r.stdout).strip() or f"Error: {LOCAL_OCR.name} --check failed")
                     + f"\nLocal OCR needs mokuro in the converter's interpreter ({python}); "
                       "pass --python .venv/bin/python, or --ocr off / --ocr gemini.")
    return ConverterEnv(python, script, accepted, "yolo True" in out)


@dataclasses.dataclass
class ConvertResult:
    ok: bool
    reason: str = ""
    pages: int = 0
    panels: int = 0
    text_blocks: int = 0
    gemini_warnings: int = 0
    tokens_in: int = 0
    tokens_out: int = 0      # includes thinking tokens, which are billed as output
    seconds: float = 0.0


def run_converter(env: ConverterEnv, cfg, in_dir: Path, out_dir: Path, title: str, author: str,
                  language: str, toc: Path | None, max_pages: int | None) -> ConvertResult:
    # OCR runs go through the shim (thinking level, text-only prompt, token counts); see its docstring.
    shim = [str(SHIM), str(env.script), "--k2m-thinking", cfg.thinking] + ([] if cfg.translate else ["--k2m-no-translation"])
    cmd = [env.python] + (shim if cfg.gemini else [str(env.script)]) + ["--input", str(in_dir), "--output-dir", str(out_dir), "--title", title]
    if DEVICE_FLAGS[cfg.device]:
        cmd.append(DEVICE_FLAGS[cfg.device])
    if author:
        cmd += ["--author", author]
    if language:
        cmd += ["--language", language]
    if toc:
        cmd += ["--toc-file", str(toc)]
    if max_pages:
        cmd += ["--max-pages", str(max_pages)]
    if not cfg.gemini:
        cmd.append("--no-ocr")  # local OCR runs afterwards, on the finished book
    cmd += cfg.extra

    child_env = thread_env(cfg.threads)
    if not cfg.gemini:
        child_env.pop("GEMINI_API_KEY", None)  # nothing downstream gets the key it doesn't need

    res = ConvertResult(ok=False)
    tail: list[str] = []
    start = time.monotonic()
    tty = sys.stdout.isatty() and not cfg.verbose
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                            bufsize=1, env=child_env, errors="replace", preexec_fn=lambda: os.nice(10))
    try:
        for line in proc.stdout:
            line = cfg.scrub(line.rstrip("\n"))
            if m := re.match(r"K2M-USAGE in=(\d+) out=(\d+) thoughts=(\d+)", line):
                res.tokens_in += int(m.group(1))
                res.tokens_out += int(m.group(2)) + int(m.group(3))
                continue
            tail = (tail + [line])[-40:]
            if m := re.match(r"\[(\d+)/(\d+)\]", line):
                res.pages = int(m.group(1))
                event("page", done=res.pages, total=int(m.group(2)))
                if tty:
                    print(f"\r      page {m.group(1)}/{m.group(2)} ", end="", flush=True)
            if m := re.match(r"\s*Panels: (\d+)", line):
                res.panels = int(m.group(1))
            if m := re.match(r"\s*Text blocks: (\d+)", line):
                res.text_blocks = int(m.group(1))
            if "Gemini error" in line or "could not parse Gemini response" in line:
                res.gemini_warnings += 1
                if cfg.test and not cfg.verbose:
                    print(f"      {line.strip()}")
            if cfg.verbose:
                print(f"      │ {line}")
        proc.wait()
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.wait()
        if tty:
            print("\r" + " " * 30 + "\r", end="", flush=True)
    res.seconds = time.monotonic() - start

    if proc.returncode != 0:
        errs = [l for l in tail if l.strip()]
        pick = next((l for l in reversed(errs) if re.match(r"\s*(Error|\w+Error|\w+Exception)\b", l)), None)
        res.reason = f"converter exited {proc.returncode}: {(pick or (errs[-1] if errs else '')).strip()}"
        return res
    if not (out_dir / "panels.idx").is_file():
        res.reason = "converter finished but wrote no panels.idx"
        return res
    res.ok = True
    return res


def thread_env(threads: int) -> dict:
    """Environment for child processes with torch/OpenMP capped to `threads` cores. Uncapped, torch
    takes every core, which on a shared home server starves other services and runs the CPU hot."""
    env = dict(os.environ)
    for var in ("OMP_NUM_THREADS", "MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS"):
        env[var] = str(threads)
    return env


class LocalOcr:
    """One long-lived local_ocr.py worker, so the models load once per run rather than per chapter."""

    def __init__(self, env: ConverterEnv, verbose: bool, threads: int):
        self.env, self.verbose, self.threads = env, verbose, threads
        self.proc: subprocess.Popen | None = None
        self.log = None

    def _start(self) -> str | None:
        self.log = tempfile.TemporaryFile("w+")
        print("      (loading local OCR models…)", flush=True)
        self.proc = subprocess.Popen([self.env.python, str(LOCAL_OCR), str(self.env.script), "--serve",
                                      "--threads", str(self.threads)],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True, bufsize=1,
                                     stderr=None if self.verbose else self.log, env=thread_env(self.threads),
                                     preexec_fn=lambda: os.nice(10))
        return None if self.proc.stdout.readline().strip() else self._died()

    def _died(self) -> str:
        if self.proc:
            self.proc.kill()
            self.proc.wait()
        self.proc = None
        tail = ""
        if self.log:
            self.log.seek(0)
            lines = [l.strip() for l in self.log.read().splitlines() if l.strip()]
            tail = lines[-1] if lines else ""
        return f"local OCR worker died: {tail or 'no output'}"

    def run(self, book: Path, pages: list[Path], rtl: bool, on_page=lambda done, total: None) -> dict:
        if self.proc is None and (err := self._start()):
            return {"ok": False, "error": err}
        self.proc.stdin.write(json.dumps({"book": str(book), "pages": [str(p) for p in pages], "rtl": rtl}) + "\n")
        self.proc.stdin.flush()
        while True:
            line = self.proc.stdout.readline()
            if not line:
                return {"ok": False, "error": self._died()}
            reply = json.loads(line)
            if reply.get("progress"):
                on_page(reply["page"], reply["pages"])
                continue
            return reply

    def close(self):
        if self.proc:
            self.proc.stdin.close()
            try:
                self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
            self.proc = None


def install(tmp_out: Path, dest: Path) -> None:
    """Move a finished book into place so dest is either the old book or the complete new one,
    never a half-copied folder the device would pick up."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    staging = dest.parent / f".{dest.name}.partial"
    old = dest.parent / f".{dest.name}.old"
    for p in (staging, old):
        if p.exists():
            shutil.rmtree(p)
    shutil.move(str(tmp_out), str(staging))
    if dest.exists():
        dest.rename(old)
    staging.rename(dest)
    if old.exists():
        shutil.rmtree(old)


# ── Main flow ───────────────────────────────────────────────────


@dataclasses.dataclass
class Outcome:
    label: str
    status: str   # converted / skipped / failed / would-convert
    detail: str = ""
    warning: str = ""


class Runner:
    def __init__(self, cfg, env: ConverterEnv | None):
        self.cfg = cfg
        self.env = env
        self.state = State(cfg.output, readonly=cfg.dry_run or cfg.test)
        self.outcomes: list[Outcome] = []
        self.local_ocr = LocalOcr(env, cfg.verbose, cfg.threads) if env and cfg.engine == "local" else None

    def record(self, label, status, detail="", warning="", **extra):
        self.outcomes.append(Outcome(label, status, detail, warning))
        event("outcome", label=label, status=status, detail=detail, warning=warning, **extra)
        tag = {"converted": "OK  ", "skipped": "skip", "failed": "FAIL", "would-convert": "plan"}[status]
        print(f"  [{tag}] {label}" + (f" - {detail}" if detail else "") + (f"\n         WARNING: {warning}" if warning else ""))

    def is_done(self, key: str, fp: list[dict]) -> bool:
        e = self.state.entries.get(key)
        if not e or self.cfg.force or e.get("sources") != fp:
            return False
        if not (self.cfg.output / key / "panels.idx").is_file():
            return False
        if self.cfg.reocr and self.cfg.ocr and not (e.get("ocr") and e.get("text_blocks", 0) > 0):
            return False
        # Converted for the other screen, or with/without a cover page: convert again.
        if e.get("device", "x4") != self.cfg.device or bool(e.get("cover_page")) != self.cfg.cover_page:
            return False
        return True

    def cover_for(self, title: "Title", chapters: list["Chapter"]) -> tuple[bytes, str | None]:
        """The generated first page for a book, and the art version it used."""
        art = covers.cover_art(self.cfg.covers_dir, title.folder, title.display(), fetch=self.cfg.fetch_covers)
        if len(chapters) == 1:
            ch = chapters[0]
            big, small = covers.chapter_number(ch.display(), ch.scan().comicinfo.get("Number", "")), "CHAPTER"
        else:
            nums = [covers.chapter_number(c.display(), c.scan().comicinfo.get("Number", "")) for c in chapters]
            big, small = f"{nums[0]}–{nums[-1]}", "CHAPTERS"
        # Without art, the chapter's own first page stands in.
        fallback = None
        first = chapters[0].scan()
        if not art and first.pages:
            with zipfile.ZipFile(first.path) as zf:
                fallback = zf.read(first.pages[0].name)
        size = DEVICE_TARGETS.get(self.cfg.device, DEVICE_TARGETS["x4"])
        return covers.render_cover(size, art, title.display(), big, small, fallback), covers.art_sig(art)

    def refresh_cover(self, key: str, title: "Title", chapters: list["Chapter"]) -> bool:
        """The series art changed since this book was converted: redraw its first page in place (same size,
        so panels.idx still fits) instead of converting it again. True if redrawn."""
        e = self.state.entries.get(key)
        if not (self.cfg.cover_page and e and e.get("cover_page") and not self.cfg.dry_run):
            return False
        art = covers.cover_art(self.cfg.covers_dir, title.folder, title.display(), fetch=self.cfg.fetch_covers)
        if covers.art_sig(art) == e.get("cover_art"):
            return False
        page0 = self.cfg.output / key / "page_0000.jpg"
        if not page0.is_file():
            return False
        data, sig = self.cover_for(title, chapters)
        tmp = page0.with_suffix(".tmp")
        tmp.write_bytes(data)
        tmp.replace(page0)
        e["cover_art"] = sig
        self.state.touch(key)
        self.state.save()
        return True

    def language(self, title: Title) -> str:
        return self.cfg.language or title.meta().get("LanguageISO", "")

    # One book = one converter run: a single chapter, or a whole title when merging. A merged
    # volume leaves out chapters that can't be read (each reported as failed) rather than failing
    # outright; they stay in the fingerprint, so re-downloading one rebuilds the volume.
    def convert_book(self, label: str, key: str, dest: Path, chapters: list[Chapter], title: Title,
                     book_title: str, fp: list[dict] | None, max_pages: int | None = None) -> ConvertResult | None:
        merged = len(chapters) > 1
        left_out: list[str] = []

        def bad(ch: Chapter, reason: str):
            left_out.append(ch.cbz.name)
            self.record(f"{title.folder}/{ch.folder}" if merged else label, "failed",
                        f"{ch.cbz.name}: {reason}" if merged or ch.info.error else reason)

        good = []
        for ch in chapters:
            if ch.scan().error:
                bad(ch, ch.info.error)
            else:
                good.append(ch)
        chapters = good
        if not chapters:
            if merged:
                self.record(label, "failed", "no readable chapters")
            return None
        if self.cfg.dry_run:
            for ch in chapters:
                print(f"         {ch.cbz.name}: " + "; ".join(describe(ch.info, self.env.accepted)))
            conv = sum(v for ch in chapters for k, v in ch.info.kinds().items() if needs_jpeg(k, self.env.accepted))
            pages = sum(len(ch.info.pages) for ch in chapters)
            self.record(label, "would-convert", f"{pages} pages" + (f", {conv} to JPEG" if conv else "")
                        + f" → {dest.relative_to(self.cfg.output)}")
            return None

        work = Path(tempfile.mkdtemp(prefix="komikku2matcha_", dir=self.cfg.tmp_dir))
        try:
            pages_dir, out_dir = work / "pages", work / "book"
            pages_dir.mkdir()
            n = converted = 0
            toc_lines = []
            cover_sig = None
            if self.cfg.cover_page:
                # Page 1 is the cover card: the X4 uses a manga's first page as its Library cover.
                data, cover_sig = self.cover_for(title, chapters)
                (pages_dir / "0000.jpg").write_bytes(data)
                n = 1
            for ch in chapters:
                # Each chapter lands in its own staging dir first, so a page failing halfway
                # leaves nothing behind in the volume.
                staging = work / "chapter"
                try:
                    got, conv = normalize(ch.info, staging, self.env.accepted, start=n)
                except (NormalizeError, zipfile.BadZipFile, OSError) as e:
                    bad(ch, str(e))
                    shutil.rmtree(staging, ignore_errors=True)
                    continue
                for f in staging.iterdir():
                    f.rename(pages_dir / f.name)
                staging.rmdir()
                toc_lines.append(f"{n}\t{ch.display()}")
                n += got
                converted += conv
            if n <= (1 if self.cfg.cover_page else 0):
                if merged:
                    self.record(label, "failed", "no readable chapters")
                return None
            toc = None
            if merged:
                toc = work / "toc.txt"
                toc.write_text("\n".join(toc_lines) + "\n", encoding="utf-8")

            meta = title.meta()
            event("current", label=label, pages=n)
            print(f"  … {label}: {n} pages" + (f" ({converted} converted to JPEG)" if converted else "")
                  + (f", first {max_pages}" if max_pages else ""), flush=True)
            res = run_converter(self.env, self.cfg, pages_dir, out_dir, book_title, meta.get("Writer", ""),
                                self.language(title), toc, max_pages)
            if not res.ok:
                self.record(label, "failed", res.reason)
                return res
            if self.local_ocr:
                pages = sorted(pages_dir.iterdir(), key=lambda p: natural_key(p.name))
                reply = self.local_ocr.run(out_dir, pages, rtl="--ltr" not in self.cfg.extra,
                                           on_page=lambda done, total: event("page", done=done, total=total))
                if not reply.get("ok"):
                    res.ok = False
                    self.record(label, "failed", f"local OCR: {reply.get('error')}")
                    return res
                res.text_blocks = reply["blocks"]
                res.seconds += reply["seconds"]
            install(out_dir, dest)
        finally:
            shutil.rmtree(work, ignore_errors=True)

        detail = f"{res.pages} pages, {res.panels} panels"
        warning = ""
        if self.cfg.ocr:
            detail += f", {res.text_blocks} text blocks"
            if self.cfg.gemini:
                detail += f", tokens in/out {res.tokens_in:,}/{res.tokens_out:,}"
            if res.text_blocks == 0:
                warning = ("OCR returned no text (bad key, quota or rate limit?); rerun later with --reocr"
                           if self.cfg.gemini else "OCR found no text on any page")
        detail += f", {res.seconds:.0f}s"
        self.record(label, "converted", detail, warning, pages=res.pages, seconds=round(res.seconds, 1))
        if fp is not None:
            self.state.set(key, {
                "sources": fp, "ocr": self.cfg.engine if self.cfg.ocr else False, "language": self.language(title),
                "pages": res.pages, "panels": res.panels, "text_blocks": res.text_blocks, "left_out": left_out,
                "thinking": self.cfg.thinking if self.cfg.gemini else None, "translation": self.cfg.gemini and self.cfg.translate,
                "tokens_in": res.tokens_in, "tokens_out": res.tokens_out,
                "device": self.cfg.device, "cover_page": self.cfg.cover_page, "cover_art": cover_sig,
                "converted_at": dt.datetime.now().isoformat(timespec="seconds"),
            })
            self.state.save()
        return res

    def plan(self, selected) -> None:
        """Announce what will actually be converted (chapters already done are left out) with page counts, so
        the server can estimate the time left. Only with K2M_EVENTS."""
        if not os.environ.get("K2M_EVENTS") or self.cfg.dry_run:
            return
        items = []
        for title, chapters in selected:
            if self.cfg.merge:
                key = f"{MANGA_SUBDIR}/{title.folder}"
                chs = sorted(title.chapters, key=Chapter.sort_key)
                if not self.is_done(key, fingerprint([c.cbz for c in chs], self.cfg.input)):
                    items.append({"label": f"{title.folder} (merged volume)", "pages": sum(len(c.scan().pages) for c in chs)})
                continue
            for ch in chapters:
                key = f"{MANGA_SUBDIR}/{title.folder}/{ch.folder}"
                if not self.is_done(key, fingerprint([ch.cbz], self.cfg.input)):
                    items.append({"label": f"{title.folder}/{ch.folder}", "pages": len(ch.scan().pages) + (1 if self.cfg.cover_page else 0)})
        event("plan", items=items)

    def check_layout(self, title_dir: Path, merged: bool) -> str | None:
        """Refuse to nest books inside books (the device treats any folder with panels.idx as one)."""
        if not title_dir.is_dir():
            return None
        if not merged and (title_dir / "panels.idx").exists():
            return f"{title_dir.relative_to(self.cfg.output)} holds a merged volume; rerun with --merge or remove it"
        if merged and any((d / "panels.idx").exists() for d in title_dir.iterdir() if d.is_dir()):
            return f"{title_dir.relative_to(self.cfg.output)} holds per-chapter books; rerun without --merge or remove it"
        return None

    def run_title(self, title: Title, chapters: list[Chapter]):
        cfg = self.cfg
        base = cfg.output / MANGA_SUBDIR
        title_dir = base / title.folder
        print(f"\n{title.src.relative_to(cfg.input)}  →  {title_dir.relative_to(cfg.output)}/")

        if problem := self.check_layout(title_dir, cfg.merge):
            for ch in chapters:
                self.record(f"{title.folder}/{ch.folder}", "failed", problem)
            return

        if cfg.merge:
            # A volume always holds every chapter; --only just picks which titles to build.
            chapters = sorted(title.chapters, key=Chapter.sort_key)
            key = f"{MANGA_SUBDIR}/{title.folder}"
            fp = fingerprint([c.cbz for c in chapters], cfg.input)
            label = f"{title.folder} (merged volume)"
            if self.is_done(key, fp):
                if self.refresh_cover(key, title, chapters):
                    self.record(label, "converted", "series cover updated (first page redrawn)")
                    return
                missing = self.state.entries[key].get("left_out")
                self.record(label, "skipped", "already converted",
                            f"volume is missing unreadable chapter(s): {', '.join(missing)}" if missing else "")
                return
            self.convert_book(label, key, title_dir, chapters, title, title.display(), fp)
            return

        width = title.number_width()
        for ch in chapters:
            key = f"{MANGA_SUBDIR}/{title.folder}/{ch.folder}"
            fp = fingerprint([ch.cbz], cfg.input)
            label = f"{title.folder}/{ch.folder}"
            book_title = f"{title.display()} - {pad_numbers(ch.display(), width)}"
            if self.is_done(key, fp):
                if self.refresh_cover(key, title, [ch]):
                    self.record(label, "converted", "series cover updated (first page redrawn)")
                    continue
                # A new chapter can widen the numbering (99 -> 100), so older books get retitled.
                retitled = not cfg.dry_run and write_meta_title(title_dir / ch.folder / "meta.bin", book_title)
                self.record(label, "skipped", "already converted" + (f"; retitled '{book_title}'" if retitled else ""))
                continue
            self.convert_book(label, key, title_dir / ch.folder, [ch], title, book_title, fp)

    def run_test(self, titles: list[tuple[Title, list[Chapter]]], everything: list[Title]):
        title, chapters = titles[0]
        ch = chapters[0]
        dest = self.cfg.output / TEST_SUBDIR / title.folder / ch.folder
        print(f"\nTEST: first {self.cfg.test_pages} page(s) of {ch.cbz.relative_to(self.cfg.input)}"
              f"  →  {dest.relative_to(self.cfg.output)}/  (OCR {self.cfg.engine})")
        res = self.convert_book(f"{title.folder}/{ch.folder}", "", dest, [ch], title,
                                f"{title.display()} - {ch.display()}", None, max_pages=self.cfg.test_pages)
        if not res or not res.ok:
            return
        total_pages = sum(len(c.scan().pages) for t in everything for c in t.chapters if not c.scan().error)
        per_page = res.panels / max(res.pages, 1)
        print(f"\n  Test result: {res.pages} pages → {res.panels} panels in {res.seconds:.1f}s")
        if self.cfg.engine == "local":
            print(f"  Local OCR: {res.text_blocks} text blocks, no API calls, no cost")
            print(f"  Whole input ({sum(len(t.chapters) for t in everything)} chapters): {total_pages} pages ≈ "
                  f"{res.seconds / max(res.pages, 1) * total_pages / 60:.0f} min at this rate "
                  "(this test includes loading the models once)")
        elif self.cfg.gemini:
            print(f"  Gemini calls made: {res.panels} (one per panel), text blocks returned: {res.text_blocks}, "
                  f"Gemini warnings: {res.gemini_warnings}")
            if res.text_blocks == 0:
                print("  WARNING: no text came back. The converter silently gives up after 3 retries on "
                      "429/503, so check the key and your quota before a full run.")
            pages = max(res.pages, 1)
            print(f"  Tokens: {res.tokens_in:,} in / {res.tokens_out:,} out (thinking included) = "
                  f"{res.tokens_in / pages:,.0f} / {res.tokens_out / pages:,.0f} per page")
            print(f"  Whole input ≈ {res.tokens_in / pages * total_pages:,.0f} in / "
                  f"{res.tokens_out / pages * total_pages:,.0f} out tokens; multiply by your model's per-token prices")
            print(f"  Whole input ({sum(len(t.chapters) for t in everything)} chapters): {total_pages} pages ≈ {round(per_page * total_pages)} Gemini calls, "
                  f"≈ {res.seconds / max(res.pages, 1) * total_pages / 60:.0f} min at this rate")
        else:
            print("  OCR was off, so the book has panels but no text.")
        print(f"  Inspect: {dest}")

    def summary(self) -> int:
        counts: dict[str, int] = {}
        for o in self.outcomes:
            counts[o.status] = counts.get(o.status, 0) + 1
        order = ["would-convert", "converted", "skipped", "failed"]
        print("\n" + "=" * 60)
        print("Summary: " + ", ".join(f"{counts[s]} {s}" for s in order if s in counts) if counts
              else "Summary: nothing to do")
        for o in self.outcomes:
            if o.status == "failed":
                print(f"  FAILED  {o.label}: {o.detail}")
            elif o.warning:
                print(f"  WARN    {o.label}: {o.warning}")
        if not self.cfg.dry_run and not self.cfg.test and counts.get("converted"):
            print(f"\nCopy {self.cfg.output / MANGA_SUBDIR} to the SD card root (any folder works; "
                  "the Library finds every folder holding panels.idx).")
        return 1 if counts.get("failed") else 0


def run_diagnose(titles, accepted, root: Path):
    print(f"Converter-readable formats: {', '.join(sorted(accepted))}; everything else becomes PNG.\n")
    for title, chapters in titles:
        print(f"{title.src.relative_to(root)}/")
        for ch in chapters:
            print(f"  {ch.cbz.name}  →  {ch.folder}/")
            for line in describe(ch.scan(), accepted):
                print(f"      {line}")


def notify(title: str, message: str, priority: int):
    try:
        conf = json.loads((Path.home() / ".config/gotify/cli.json").read_text())
        subprocess.run(["curl", "-s", "-o", "/dev/null", "-X", "POST", f"{conf['url'].rstrip('/')}/message",
                        "-F", f"title={title}", "-F", f"message={message}", "-F", f"priority={priority}",
                        "-H", f"X-Gotify-Key: {conf['token']}"], timeout=15)
    except Exception as e:
        print(f"(Gotify notification failed: {e})", file=sys.stderr)


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Convert Komikku/Mihon chapter CBZs into Matcha Reader manga folders.",
        epilog="Anything after `--` is passed to convert_manga.py verbatim (e.g. -- --trim-margins --mono).")
    ap.add_argument("input", type=Path, help="Folder scanned recursively for .cbz (e.g. Komikku's downloads/)")
    ap.add_argument("output", type=Path, help="Output folder; books go to OUTPUT/manga/<Title>/<Chapter>/")
    ap.add_argument("--no-cover-page", dest="cover_page", action="store_false",
                    help="Don't add a cover page (series art + large chapter number) as each chapter's first page")
    ap.add_argument("--covers-dir", type=Path, help="Series art folder (default OUTPUT/.covers)")
    ap.add_argument("--no-fetch-covers", dest="fetch_covers", action="store_false",
                    help="Don't look up series art on AniList (use only art already in the covers folder)")
    ap.add_argument("--merge", action="store_true", help="One book per title (all chapters, with a chapter "
                    "list) at OUTPUT/manga/<Title>/ instead of one book per chapter")
    ap.add_argument("--language", help="Book language tag, e.g. ja (default: ComicInfo LanguageISO if present). "
                    "Also tells OCR what language to expect")
    ap.add_argument("--device", choices=DEVICE_FLAGS, default="x4",
                    help="Screen to scale for (default x4: 480x800, also right for the X4 Pro)")
    ap.add_argument("--ocr", choices=OCR_ENGINES, default="local",
                    help="Text for dictionary lookup: local (default; free, Mokuro's comic-text-detector + "
                         "manga-ocr, Japanese only), gemini (paid, needs GEMINI_API_KEY), or off")
    ap.add_argument("--no-ocr", action="store_true", help="Same as --ocr off")
    ap.add_argument("--threads", type=int, default=4, metavar="N",
                    help="CPU cores for panel detection and local OCR (default 4); both also run at low priority")
    ap.add_argument("--thinking", choices=THINKING_LEVELS, default="minimal",
                    help="--ocr gemini: thinking level (default minimal; 'default' = the model's own, which "
                         "measured ~4.5x the output tokens for the same text)")
    ap.add_argument("--translate", action="store_true",
                    help="--ocr gemini: also ask for an English translation per panel (default: text only, which is all "
                         "dictionary lookup needs)")
    ap.add_argument("--test", action="store_true", help="Convert only the first N pages of the first selected "
                    f"chapter into OUTPUT/{TEST_SUBDIR}/ and estimate Gemini calls for the rest")
    ap.add_argument("--test-pages", type=int, default=DEFAULT_TEST_PAGES, metavar="N",
                    help=f"Pages for --test (default {DEFAULT_TEST_PAGES})")
    ap.add_argument("--only", action="append", default=[], metavar="TEXT",
                    help="Only chapters whose '<title dir>/<file>' contains TEXT (case-insensitive; repeatable)")
    ap.add_argument("--exact", action="store_true",
                    help="--only must match '<title dir>/<file>' exactly (case-sensitive) instead of as a substring")
    ap.add_argument("--diagnose", action="store_true", help="Report what each CBZ contains and exit")
    ap.add_argument("--dry-run", action="store_true", help="Diagnose and show what would be converted; write nothing")
    ap.add_argument("--force", action="store_true", help="Reconvert even if already converted")
    ap.add_argument("--reocr", action="store_true", help="With OCR on, also reconvert books previously "
                    "converted without OCR text (--no-ocr runs, or OCR that came back empty)")
    ap.add_argument("--converter", help="Path to convert_manga.py (default: $MATCHA_CONVERTER or "
                    "./matcha-reader or ../matcha-reader next to this script)")
    ap.add_argument("--python", default=sys.executable, help="Interpreter to run the converter with, e.g. a "
                    "venv that has ultralytics for YOLO panel detection (default: this one)")
    ap.add_argument("--tmp-dir", help="Where to extract chapters (default: system temp)")
    ap.add_argument("--notify", action="store_true", help="Send the summary via Gotify (~/.config/gotify/cli.json)")
    ap.add_argument("-v", "--verbose", action="store_true", help="Show the converter's own output")

    argv = sys.argv[1:]
    extra: list[str] = []
    if "--" in argv:
        i = argv.index("--")
        argv, extra = argv[:i], argv[i + 1:]
    cfg = ap.parse_args(argv)
    cfg.extra = extra
    cfg.input = cfg.input.resolve()
    cfg.output = cfg.output.resolve()
    cfg.covers_dir = (cfg.covers_dir or cfg.output / ".covers").resolve()
    if not cfg.input.is_dir():
        ap.error(f"input folder not found: {cfg.input}")
    if cfg.threads < 1:
        ap.error("--threads must be at least 1")
    if cfg.test_pages < 1:
        ap.error("--test-pages must be at least 1")

    # The key only ever lives in this process's memory and the converter's environment.
    key = os.environ.get("GEMINI_API_KEY", "").strip()
    cfg.engine = "off" if cfg.no_ocr else cfg.ocr
    if cfg.engine == "gemini" and not key:
        ap.error("--ocr gemini needs GEMINI_API_KEY in the environment")
    if cfg.engine == "local" and {"--webtoon", "--trim-margins"} & set(cfg.extra):
        ap.error("local OCR maps text onto the untouched source pages, so it can't be combined with "
                 "--webtoon or --trim-margins (they re-cut or crop pages); use --ocr gemini or --ocr off")
    cfg.ocr = cfg.engine != "off"
    cfg.gemini = cfg.engine == "gemini"
    cfg.scrub = (lambda s: s.replace(key, "***")) if key else (lambda s: s)

    converter = find_converter(cfg.converter)
    if not converter:
        ap.error("convert_manga.py not found; pass --converter or clone "
                 "https://github.com/eszter007/matcha-reader next to this script")
    env = preflight(cfg.python, converter, DEVICE_FLAGS[cfg.device], cfg.engine)

    exclude = cfg.output if cfg.input in cfg.output.parents or cfg.output == cfg.input else None
    titles = discover(cfg.input, exclude)
    selected: list[tuple[Title, list[Chapter]]] = []
    for t in titles:
        chs = [c for c in t.chapters
               if not cfg.only or any((o == f"{t.src.name}/{c.cbz.name}") if cfg.exact
                                      else (o.lower() in f"{t.src.name}/{c.cbz.name}".lower()) for o in cfg.only)]
        if chs:
            selected.append((t, chs))
    if not selected:
        print(f"No .cbz files found under {cfg.input}" + (" matching --only" if cfg.only else ""))
        return 1

    n_ch = sum(len(c) for _, c in selected)
    event("start", titles=len(selected), chapters=n_ch)
    print(f"Input:     {cfg.input}  ({len(selected)} title(s), {n_ch} chapter(s))")
    print(f"Output:    {cfg.output}")
    print(f"Converter: {converter}  [{cfg.python}]")
    print(f"           reads {', '.join(sorted(env.accepted))}; panel detector: "
          f"{'YOLO' if env.yolo else 'gutter heuristic (ultralytics not installed)'}")
    print(f"Device:    {cfg.device}   Mode: {'merged volume per title' if cfg.merge else 'one book per chapter'}")
    print(f"OCR:       " + ("local (Mokuro: comic-text-detector + manga-ocr; free)" if cfg.engine == "local" else
                           f"Gemini (key from GEMINI_API_KEY; thinking {cfg.thinking}, "
                           f"{'text + translation' if cfg.translate else 'text only'})" if cfg.gemini else "off"))
    if cfg.engine == "local" and cfg.language and not cfg.language.lower().startswith("ja"):
        print(f"           note: manga-ocr reads Japanese only; --language {cfg.language} books will get "
              "garbage text (use --ocr gemini or --ocr off)")
    if cfg.gemini and not cfg.language and not any(t.meta().get("LanguageISO") for t, _ in selected):
        print("           note: no --language and no LanguageISO in ComicInfo; pass --language (e.g. ja) "
              "for better OCR and per-language reading stats")

    if cfg.diagnose:
        print()
        run_diagnose(selected, env.accepted, cfg.input)
        return 0

    runner = Runner(cfg, env)
    started = time.monotonic()
    try:
        if cfg.test:
            runner.run_test(selected, titles)
        else:
            runner.plan(selected)
            for title, chapters in selected:
                runner.run_title(title, chapters)
    except KeyboardInterrupt:
        print("\nInterrupted.")
        runner.summary()
        return 130
    finally:
        if runner.local_ocr:
            runner.local_ocr.close()
    rc = runner.summary()
    if cfg.notify and not cfg.dry_run:
        c = {s: sum(o.status == s for o in runner.outcomes) for s in ("converted", "skipped", "failed")}
        notify("komikku2matcha " + ("failed" if rc else "done"),
               f"{c['converted']} converted, {c['skipped']} skipped, {c['failed']} failed "
               f"in {(time.monotonic() - started) / 60:.0f} min", 8 if rc else 3)
    return rc


if __name__ == "__main__":
    sys.exit(main())

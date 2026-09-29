#!/usr/bin/env python3
"""Free, local OCR for Matcha Reader manga books: Mokuro's comic-text-detector + manga-ocr.

Takes a book that convert_manga.py wrote with --no-ocr (panels, crops, panels.idx/.dat) plus the
full-resolution source pages it was made from, reads every page's text, assigns each text block to
the panel it sits in, and rewrites panels.idx/.dat with text and per-line boxes. The records are
encoded by the converter's own encode_page(), so the format always matches the converter's.

OCR runs on the full-resolution pages, not the screen-sized crops the converter keeps: manga-ocr
reads a line at a time, and small text and furigana need the pixels.

    python3 local_ocr.py <convert_manga.py> --check          # dependencies present?
    python3 local_ocr.py <convert_manga.py> --serve [--cpu] [--threads N]  # JSON lines on stdin/stdout

Request:  {"book": "<dir>", "pages": ["<page 1>", ...], "rtl": true}
Reply:    zero or more {"progress": true, "page": i, "pages": N} lines as pages finish, then one
          {"ok": true, "pages": N, "blocks": N, "unplaced": N, "seconds": S} or {"ok": false, "error": "..."}

Models load once per process (about 15 s), so one worker serves a whole run.
"""

from __future__ import annotations

import importlib.util
import json
import os
import re
import struct
import sys
import time

_FULLWIDTH_ALNUM = {c: c - 0xFEE0 for r in ((0xFF10, 0xFF19), (0xFF21, 0xFF3A), (0xFF41, 0xFF5A))
                    for c in range(r[0], r[1] + 1)}


def normalize_text(s: str) -> str:
    """manga-ocr writes ellipses as runs of full-width dots and Latin letters/digits full-width;
    turn those into what the page shows (…) and plain ASCII, so dictionary lookup sees clean text."""
    s = s.translate(_FULLWIDTH_ALNUM)
    s = re.sub(r"[．.・]{2,}", lambda m: "…" * max(1, round(len(m.group()) / 3)), s)
    return s.strip()


def load_converter(path: str):
    spec = importlib.util.spec_from_file_location("convert_manga", path)
    cm = importlib.util.module_from_spec(spec)
    sys.modules["convert_manga"] = cm
    spec.loader.exec_module(cm)
    for name in ("encode_page", "_write_panel_index", "sort_panels_reading_order",
                 "IDX_HEADER", "IDX_RECORD", "PANEL_BOX", "CROP_BOX", "TEXT_BLOCK", "LINE_HEADER", "LINE_BOX"):
        if not hasattr(cm, name):
            raise SystemExit(f"Error: {path} no longer has {name}; update local_ocr.py")
    return cm


def read_book(cm, book: str):
    """Pages as [(w, h, [panel dict, ...]), ...] from panels.idx/.dat (text blocks are kept)."""
    idx = open(os.path.join(book, "panels.idx"), "rb").read()
    dat = open(os.path.join(book, "panels.dat"), "rb").read()
    hsize, rsize = struct.calcsize(cm.IDX_HEADER), struct.calcsize(cm.IDX_RECORD)
    version, count = struct.unpack(cm.IDX_HEADER, idx[:hsize])
    if version < 3:
        raise ValueError(f"panels.idx version {version}; this tool writes v3 line boxes")
    pb, cb, tb = (struct.calcsize(f) for f in (cm.PANEL_BOX, cm.CROP_BOX, cm.TEXT_BLOCK))
    lh, lb = struct.calcsize(cm.LINE_HEADER), struct.calcsize(cm.LINE_BOX)
    pages = []
    for i in range(count):
        off, length, w, h = struct.unpack(cm.IDX_RECORD, idx[hsize + i * rsize:hsize + (i + 1) * rsize])
        d = dat[off:off + length]
        o = 2
        panels = []
        for _ in range(d[0]):
            x, y, pw, ph, tcount, _, tlen = struct.unpack(cm.PANEL_BOX, d[o:o + pb])
            o += pb
            translation = d[o:o + tlen].decode("utf-8", "replace")
            o += tlen
            cx, cy, cw, ch = struct.unpack(cm.CROP_BOX, d[o:o + cb])
            o += cb
            for _ in range(tcount):  # skip any existing text: it is replaced
                *_, txl = struct.unpack(cm.TEXT_BLOCK, d[o:o + tb])
                o += tb + txl
                lc, _ = struct.unpack(cm.LINE_HEADER, d[o:o + lh])
                o += lh + lc * lb
            panels.append({"box": [x, y, x + pw, y + ph], "crop": [cx, cy, cx + cw, cy + ch],
                           "translation": translation, "text_blocks": []})
        pages.append((w, h, panels))
    return pages


def overlap(a, b) -> int:
    return max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(0, min(a[3], b[3]) - max(a[1], b[1]))


def page_blocks(result: dict, sx: float, sy: float) -> list[dict]:
    """Mokuro blocks → converter text blocks in book-page pixels, lines in reading order."""
    out = []
    for blk in result["blocks"]:
        vertical = bool(blk.get("vertical"))
        lines = []
        for coords, text in zip(blk.get("lines_coords", []), blk.get("lines", [])):
            text = normalize_text(text)
            if not text or "\n" in text:
                continue
            xs = [p[0] for p in coords]
            ys = [p[1] for p in coords]
            lines.append(([int(min(xs) * sx), int(min(ys) * sy), int(max(xs) * sx) + 1, int(max(ys) * sy) + 1], text))
        if not lines:
            continue
        # Vertical columns read right to left, horizontal rows top to bottom.
        lines.sort(key=(lambda l: -(l[0][0] + l[0][2])) if vertical else (lambda l: l[0][1] + l[0][3]))
        x1, y1, x2, y2 = (float(v) for v in blk["box"])
        box = [int(x1 * sx), int(y1 * sy), int(x2 * sx) + 1, int(y2 * sy) + 1]
        for lbox, _ in lines:
            box = [min(box[0], lbox[0]), min(box[1], lbox[1]), max(box[2], lbox[2]), max(box[3], lbox[3])]
        out.append({"box": box, "text": "\n".join(t for _, t in lines),
                    "lines": [b for b, _ in lines], "vertical": vertical})
    return out


def assign(cm, panels: list[dict], blocks: list[dict], rtl: bool) -> int:
    """Put each block in the panel it overlaps most (nearest one if it overlaps none), then order
    each panel's blocks with the converter's own reading-order sort. Returns blocks left unplaced
    (only when the page has no panels at all)."""
    if not panels:
        return len(blocks)
    for b in blocks:
        best = max(range(len(panels)), key=lambda i: overlap(panels[i]["box"], b["box"]))
        if overlap(panels[best]["box"], b["box"]) == 0:
            bx, by = (b["box"][0] + b["box"][2]) / 2, (b["box"][1] + b["box"][3]) / 2
            best = min(range(len(panels)), key=lambda i: (
                max(panels[i]["box"][0] - bx, 0, bx - panels[i]["box"][2]) ** 2
                + max(panels[i]["box"][1] - by, 0, by - panels[i]["box"][3]) ** 2))
        panels[best]["text_blocks"].append(b)
    for p in panels:
        tbs = p["text_blocks"]
        if len(tbs) > 1:
            order = cm.sort_panels_reading_order([list(t["box"]) for t in tbs], rtl=rtl)
            remaining = list(tbs)
            p["text_blocks"] = []
            for box in order:
                i = next(i for i, t in enumerate(remaining) if list(t["box"]) == list(box))
                p["text_blocks"].append(remaining.pop(i))
    return 0


def ocr_book(cm, mocr, book: str, page_paths: list[str], rtl: bool, on_page=lambda done, total: None) -> dict:
    from PIL import Image
    start = time.monotonic()
    pages = read_book(cm, book)
    if len(page_paths) < len(pages):
        raise ValueError(f"book has {len(pages)} pages but only {len(page_paths)} source pages were given")
    idx_records, chunks, off, nblocks, unplaced = [], [], 0, 0, 0
    for i, ((w, h, panels), src) in enumerate(zip(pages, page_paths)):
        with Image.open(src) as im:
            W, H = im.size
        result = mocr(src)
        blocks = page_blocks(result, w / W, h / H)
        unplaced += assign(cm, panels, blocks, rtl)
        nblocks += len(blocks)
        chunk = cm.encode_page(panels)
        idx_records.append((off, len(chunk), w, h))
        chunks.append(chunk)
        off += len(chunk)
        on_page(i + 1, len(pages))
    cm._write_panel_index(book, idx_records, chunks)
    return {"ok": True, "pages": len(pages), "blocks": nblocks - unplaced, "unplaced": unplaced,
            "seconds": round(time.monotonic() - start, 1)}


def main():
    if len(sys.argv) < 3 or sys.argv[2] not in ("--check", "--serve"):
        raise SystemExit(__doc__)
    cm = load_converter(sys.argv[1])
    missing = [m for m in ("mokuro", "manga_ocr") if importlib.util.find_spec(m) is None]
    if missing:
        raise SystemExit(f"Error: {', '.join(missing)} not installed for {sys.executable} (pip install mokuro)")
    if sys.argv[2] == "--check":
        print("ok")
        return

    # The protocol owns stdout; anything the libraries print goes to stderr.
    proto = os.fdopen(os.dup(sys.stdout.fileno()), "w", buffering=1)
    sys.stdout = sys.stderr
    from mokuro.manga_page_ocr import MangaPageOcr
    import torch
    if "--threads" in sys.argv:
        torch.set_num_threads(int(sys.argv[sys.argv.index("--threads") + 1]))
    mocr = MangaPageOcr(force_cpu="--cpu" in sys.argv or not torch.cuda.is_available())
    proto.write(json.dumps({"ready": True}) + "\n")
    for line in sys.stdin:
        if not line.strip():
            continue
        def on_page(done, total):
            proto.write(json.dumps({"progress": True, "page": done, "pages": total}) + "\n")
        try:
            req = json.loads(line)
            reply = ocr_book(cm, mocr, req["book"], req["pages"], req.get("rtl", True), on_page=on_page)
        except Exception as e:  # report and keep serving the next book
            reply = {"ok": False, "error": f"{type(e).__name__}: {e}"}
        proto.write(json.dumps(reply) + "\n")


if __name__ == "__main__":
    main()

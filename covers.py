"""Series cover art and generated chapter cover pages for komikku2matcha.

The X4 shows a manga chapter's first page (page_0000) as its cover in the Library, and a shelf shows the
cover of its most recently opened book; there is no separate cover file. So the "cover" is a generated
first page: the series' art with the chapter number in large type, sized exactly to the target screen
(the converter then passes it through untouched).

Series art, per title folder, in <covers dir>, first match wins:
  <folder>.custom.jpg   set by the user (the app's "Change cover")
  <folder>.komikku.jpg  the cover Komikku shows (its URL comes from Komikku's backup, via the app)
  <folder>.jpg          fetched from AniList (free, no key) by series title
  <folder>.none         AniList found nothing; retried after a week
"""

from __future__ import annotations

import hashlib
import io
import json
import re
import time
import urllib.request
from pathlib import Path

ANILIST = "https://graphql.anilist.co"
# AniList sits behind Cloudflare, which refuses Python's default "Python-urllib" user agent (error 1010).
_UA = "komikku2matcha/1.0 (+https://github.com/eszter007/matcha-reader)"
_QUERY = "query($s:String){Media(search:$s,type:MANGA){title{romaji english} coverImage{extraLarge large}}}"
RETRY_NONE_SECONDS = 7 * 86400

_FONT_DIGITS = ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf"]
_FONT_TEXT = [("/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc", 0), ("/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf", 0),
              ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 0)]


def _font(candidates, size):
    from PIL import ImageFont
    for c in candidates:
        path, index = c if isinstance(c, tuple) else (c, 0)
        try:
            return ImageFont.truetype(path, size, index=index)
        except OSError:
            continue
    return ImageFont.load_default(size=size)


def title_variants(series: str) -> list[str]:
    """Search strings for AniList, most specific first: scanlation tags ("- RAW", "(Raw)") and bracketed
    subtitles make the search miss."""
    out = [series]
    s = re.sub(r"\s*[-–]\s*raw\s*$|\s*[(\[]raw[)\]]\s*$", "", series, flags=re.I).strip()
    out.append(s)
    out.append(re.sub(r"[［\[(（].*?[］\])）]", " ", s).strip())
    out.append(s.replace("_", " ").replace("?", "").strip())
    return [v for i, v in enumerate(out) if v and v not in out[:i]]


_SEARCH = "query($s:String){Page(perPage:8){media(search:$s,type:MANGA){id title{romaji english native} coverImage{extraLarge large}}}}"


def anilist_search(series: str, timeout: float = 10) -> list[dict]:
    """Several AniList matches (id, title, cover url) for the cover picker; empty on any failure."""
    seen, out = set(), []
    for q in title_variants(series)[:2]:
        body = json.dumps({"query": _SEARCH, "variables": {"s": q}}).encode()
        req = urllib.request.Request(ANILIST, data=body, headers={"Content-Type": "application/json",
                                                                   "Accept": "application/json", "User-Agent": _UA})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                media = ((json.load(r).get("data") or {}).get("Page") or {}).get("media") or []
        except Exception:
            media = []
        for m in media:
            url = (m.get("coverImage") or {}).get("extraLarge") or (m.get("coverImage") or {}).get("large")
            if url and m["id"] not in seen:
                seen.add(m["id"])
                t = m.get("title") or {}
                out.append({"id": m["id"], "url": url, "title": t.get("english") or t.get("romaji") or t.get("native") or str(m["id"])})
    return out[:8]


def download_jpeg(url: str, dest: Path, timeout: float = 20) -> bool:
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": _UA}), timeout=timeout) as r:
            dest.parent.mkdir(parents=True, exist_ok=True)
            _save_jpeg(r.read(), dest)
        return True
    except Exception:
        return False


def anilist_cover_url(series: str, timeout: float = 10) -> str | None:
    for q in title_variants(series):
        body = json.dumps({"query": _QUERY, "variables": {"s": q}}).encode()
        req = urllib.request.Request(ANILIST, data=body, headers={"Content-Type": "application/json",
                                                                   "Accept": "application/json", "User-Agent": _UA})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                media = (json.load(r).get("data") or {}).get("Media")
        except Exception:
            media = None
        if media and media.get("coverImage"):
            url = media["coverImage"].get("extraLarge") or media["coverImage"].get("large")
            if url:
                return url
        time.sleep(0.7)  # AniList allows ~90 requests/minute; be polite between variants
    return None


def _save_jpeg(data: bytes, dest: Path) -> None:
    from PIL import Image
    with Image.open(io.BytesIO(data)) as im:
        im.load()
        im = im.convert("RGB")
        tmp = dest.with_suffix(".tmp")
        im.save(tmp, "JPEG", quality=92)
        tmp.replace(dest)


def cover_art(covers_dir: Path, folder: str, series: str, fetch: bool = True) -> Path | None:
    """The series' art: the user's own, else AniList's (fetched once and cached), else None."""
    covers_dir.mkdir(parents=True, exist_ok=True)
    custom, auto, none = (covers_dir / f"{folder}.custom.jpg", covers_dir / f"{folder}.jpg", covers_dir / f"{folder}.none")
    if custom.is_file():
        return custom
    if (covers_dir / f"{folder}.komikku.jpg").is_file():
        return covers_dir / f"{folder}.komikku.jpg"
    if auto.is_file():
        return auto
    if not fetch or (none.is_file() and time.time() - none.stat().st_mtime < RETRY_NONE_SECONDS):
        return None
    url = anilist_cover_url(series)
    if url:
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": _UA}), timeout=20) as r:
                _save_jpeg(r.read(), auto)
            none.unlink(missing_ok=True)
            return auto
        except Exception:
            pass
    none.touch()
    return None


def set_komikku_art(covers_dir: Path, folder: str, url: str, timeout: float = 20) -> bool:
    """Download the cover Komikku uses (its thumbnail URL from a Komikku backup). Skipped if this URL was
    already fetched. Sources often want a Referer from their own site, so one is sent."""
    covers_dir.mkdir(parents=True, exist_ok=True)
    marker = covers_dir / f"{folder}.komikku.url"
    dest = covers_dir / f"{folder}.komikku.jpg"
    if dest.is_file() and marker.is_file() and marker.read_text().strip() == url:
        return True
    from urllib.parse import urlsplit
    origin = "{0.scheme}://{0.netloc}/".format(urlsplit(url))
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Android) komikku2matcha", "Referer": origin})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            _save_jpeg(r.read(), dest)
    except Exception:
        return False
    marker.write_text(url)
    return True


def set_custom_art(covers_dir: Path, folder: str, data: bytes) -> Path:
    covers_dir.mkdir(parents=True, exist_ok=True)
    dest = covers_dir / f"{folder}.custom.jpg"
    _save_jpeg(data, dest)
    return dest


def art_sig(path: Path | None) -> str | None:
    return hashlib.sha1(path.read_bytes()).hexdigest()[:12] if path and path.is_file() else None


def chapter_number(name: str, number: str = "") -> str:
    """"76.1" from ComicInfo <Number>, else the last number in the chapter name, else the name itself."""
    if number.strip():
        n = number.strip()
        return n[:-2] if n.endswith(".0") else n
    nums = re.findall(r"\d+(?:\.\d+)?", name)
    return nums[-1] if nums else name


def render_cover(size: tuple[int, int], art: Path | None, series: str, big: str, small: str = "CHAPTER",
                 fallback: bytes | None = None) -> bytes:
    """A cover page: the art on top, and below it a white band with the chapter number as large as it
    fits (it has to read on a ~150 px wide Library thumbnail), then the series title."""
    from PIL import Image, ImageDraw, ImageOps
    W, H = size
    page = Image.new("L", (W, H), 255)
    art_h = int(H * 0.58)
    src = None
    for data in ([art.read_bytes()] if art and art.is_file() else []) + ([fallback] if fallback else []):
        try:
            src = Image.open(io.BytesIO(data)).convert("L")
            break
        except Exception:
            continue
    if src is not None:
        src = ImageOps.autocontrast(ImageOps.fit(src, (W, art_h), Image.LANCZOS, centering=(0.5, 0.35)), cutoff=1)
        page.paste(src, (0, 0))
    draw = ImageDraw.Draw(page)
    draw.rectangle([0, art_h, W, art_h + 5], fill=0)

    band_top = art_h + 5
    small_font = _font(_FONT_DIGITS, max(12, int(H * 0.04)))
    sl, st, sr, sb = draw.textbbox((0, 0), small, font=small_font)
    label_y = band_top + H * 0.018
    draw.text(((W - (sr - sl)) / 2 - sl, label_y - st), small, font=small_font, fill=90)
    label_bottom = label_y + (sb - st)

    # Largest size whose glyphs fit 92% of the width and ~24% of the height.
    max_w, max_h = W * 0.92, H * 0.24
    size_px = int(max_h * 1.4)
    while size_px > 10:
        f = _font(_FONT_DIGITS, size_px)
        l, t, r, b = draw.textbbox((0, 0), big, font=f)
        if r - l <= max_w and b - t <= max_h:
            break
        size_px -= 4
    f = _font(_FONT_DIGITS, size_px)
    l, t, r, b = draw.textbbox((0, 0), big, font=f)
    num_top = label_bottom + H * 0.015
    draw.text(((W - (r - l)) / 2 - l, num_top - t), big, font=f, fill=0)

    # Series title, up to two lines, in a font that covers Japanese.
    tf = _font(_FONT_TEXT, max(12, int(H * 0.036)))
    lines, line = [], ""
    # Wrap at spaces; a word longer than a line (or Japanese, which has none) breaks between characters.
    for word in re.split(r"(?<= )", series):
        if draw.textlength(line + word, font=tf) <= W * 0.9:
            line += word
            continue
        if line.strip():
            lines.append(line)
            line = ""
        for ch in word:
            if draw.textlength(line + ch, font=tf) > W * 0.9:
                lines.append(line)
                line = ch
            else:
                line += ch
    lines.append(line)
    if len(lines) > 2:
        lines = [lines[0], lines[1][:-1] + "…"]
    y = H - H * 0.02 - len(lines) * H * 0.045
    for ln in lines:
        lw = draw.textlength(ln.strip(), font=tf)
        draw.text(((W - lw) / 2, y), ln.strip(), font=tf, fill=40)
        y += H * 0.045

    out = io.BytesIO()
    page.save(out, "JPEG", quality=92, optimize=True, progressive=False)
    return out.getvalue()


# ── Sleep screens ───────────────────────────────────────────────
# Matcha shows a random image from /sleep on the card when its "Custom" sleep screen is chosen. One 8-bit
# grayscale BMP per series, made from the series art, is kept in <output>/.sleep/<folder>.bmp:
#   <folder>.bmp     the image (screen-sized, so the firmware draws it 1:1)
#   <folder>.sig     hash of the art it was drawn from, so a new cover redraws it
#   <folder>.custom  marker: the user's own image; automatic generation leaves it alone


def render_sleep(size: tuple[int, int], art: bytes) -> bytes:
    """A full-screen 8-bit grayscale BMP of [art], cropped to the screen's shape."""
    from PIL import Image, ImageOps
    with Image.open(io.BytesIO(art)) as im:
        im.load()
        # Transparent art (PNG uploads) goes on white, not black.
        if im.mode in ("RGBA", "LA", "P"):
            im = im.convert("RGBA")
            bg = Image.new("RGBA", im.size, (255, 255, 255, 255))
            im = Image.alpha_composite(bg, im)
        gray = ImageOps.autocontrast(ImageOps.fit(im.convert("L"), size, Image.LANCZOS, centering=(0.5, 0.35)), cutoff=1)
    out = io.BytesIO()
    gray.save(out, "BMP")
    return out.getvalue()


def sleep_files(sleep_dir: Path, folder: str) -> tuple[Path, Path, Path]:
    return sleep_dir / f"{folder}.bmp", sleep_dir / f"{folder}.sig", sleep_dir / f"{folder}.custom"


def _write_sleep(sleep_dir: Path, folder: str, bmp: bytes, sig: str, custom: bool) -> None:
    sleep_dir.mkdir(parents=True, exist_ok=True)
    img, sigf, marker = sleep_files(sleep_dir, folder)
    tmp = img.with_suffix(".tmp")
    tmp.write_bytes(bmp)
    tmp.replace(img)
    sigf.write_text(sig)
    if custom:
        marker.touch()
    else:
        marker.unlink(missing_ok=True)


def make_sleep(sleep_dir: Path, folder: str, art: Path | None, size: tuple[int, int], force: bool = False) -> str:
    """Draw a series' sleep screen from its art. Returns "created", "updated", "unchanged", "custom" (a user
    image is kept) or "no art"."""
    img, sigf, marker = sleep_files(sleep_dir, folder)
    if marker.is_file() and not force:
        return "custom"
    if art is None or not art.is_file():
        return "no art"
    sig = f"{size[0]}x{size[1]}:{art_sig(art)}"
    if img.is_file() and not marker.is_file() and sigf.is_file() and sigf.read_text() == sig:
        return "unchanged"
    existed = img.is_file()
    _write_sleep(sleep_dir, folder, render_sleep(size, art.read_bytes()), sig, custom=False)
    return "updated" if existed else "created"


def set_custom_sleep(sleep_dir: Path, folder: str, data: bytes, size: tuple[int, int]) -> None:
    """Use the user's own image as a series' sleep screen (any format Pillow reads)."""
    _write_sleep(sleep_dir, folder, render_sleep(size, data), "custom", custom=True)


def delete_sleep(sleep_dir: Path, folder: str) -> bool:
    img = sleep_files(sleep_dir, folder)
    existed = img[0].is_file()
    for p in img:
        p.unlink(missing_ok=True)
    return existed

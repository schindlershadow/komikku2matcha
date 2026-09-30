#!/usr/bin/env python3
"""Server-side tests: series covers, cover pages, X3/X4 jobs, incremental X4 updates with render-cache clearing.

Run by run-android-tests.sh with a throwaway server (K2M_TEST_URL/TOKEN/OUT) and fake X4 (K2M_TEST_X4, _SD).
"""
import io
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import k2m_server  # noqa: E402

URL, TOKEN, X4, SD, OUT = (os.environ[k] for k in ("K2M_TEST_URL", "K2M_TEST_TOKEN", "K2M_TEST_X4", "K2M_TEST_SD", "K2M_TEST_OUT"))
AUTH = {"Authorization": f"Bearer {TOKEN}"}
failures = []


def req(method, path, body=None, headers=None, raw=False):
    data = json.dumps(body).encode() if isinstance(body, dict) else body
    r = urllib.request.Request(URL + path, data=data, method=method, headers={**AUTH, **(headers or {})})
    try:
        with urllib.request.urlopen(r, timeout=300) as resp:
            out = resp.read()
            return resp.status, (out if raw else json.loads(out or b"{}"))
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def wait(job_id):
    while True:
        _, j = req("GET", f"/api/jobs/{job_id}")
        if j["status"] not in ("queued", "running"):
            return j
        time.sleep(1)


def check(name, cond, detail=""):
    print(f"  {'ok  ' if cond else 'FAIL'} server.{name}" + (f": {detail}" if detail and not cond else ""))
    if not cond:
        failures.append(name)


def jpeg(color):
    from PIL import Image
    b = io.BytesIO()
    Image.new("RGB", (300, 450), color).save(b, "JPEG")
    return b.getvalue()


# 1. Series cover: upload our own art (no network in tests), fetch it back as a thumbnail.
st, _ = req("PUT", "/api/covers?title=Test%20Title", jpeg((200, 30, 30)), {"Content-Type": "image/jpeg"})
check("cover_upload", st == 200)
st, thumb = req("GET", "/api/covers?title=Test%20Title&w=100", raw=True)
check("cover_thumbnail", st == 200 and thumb[:2] == b"\xff\xd8")
st, _ = req("PUT", "/api/covers?title=..%2Fx", b"x")
check("cover_rejects_bad_title", st == 400)

# 2. Convert with a cover page (fetching disabled via the env of the throwaway server? AniList isn't needed:
#    the custom art above wins). Page 1 must be the cover, at the X4's exact size.
st, job = req("POST", "/api/jobs", {"chapters": ["Test Title/Chapter 2.cbz"], "force": True, "cover_page": True, "device": "x4"})
j = wait(job["id"])
check("convert_with_cover", j["status"] == "done", str(j.get("outcomes") or j.get("error")))
book = Path(OUT) / "manga/Test Title/Chapter 2"
from PIL import Image  # noqa: E402
check("cover_page_size", Image.open(book / "page_0000.jpg").size == (480, 800))
check("cover_page_extra", len(list(book.glob("page_*"))) == 12)   # 11 pages + cover

# 3. Send to the fake X4, then change the art: a sync redraws page 1 only, and the next push sends only it,
#    clearing the X4's cached renders but keeping the reading position.
st, job = req("POST", "/api/device/push", {"device": X4, "books": ["manga/Test Title/Chapter 2"]})
check("push_full", wait(job["id"])["outcomes"][0]["status"] == "pushed")
cache = Path(SD) / f".crosspoint/manga_{k2m_server.firmware_hash('/manga/Test Title/Chapter 2')}"
cache.mkdir(parents=True, exist_ok=True)
for n in ("progress.bin", "page_0.2bp", "thumb_120.bmp"):
    (cache / n).write_bytes(b"1234")
req("PUT", "/api/covers?title=Test%20Title", jpeg((30, 30, 200)), {"Content-Type": "image/jpeg"})
st, job = req("POST", "/api/jobs", {"chapters": ["Test Title/Chapter 2.cbz"], "cover_page": True, "device": "x4"})
j = wait(job["id"])
check("cover_refresh_not_reconvert", "cover updated" in j["outcomes"][0].get("detail", ""), str(j["outcomes"]))
st, job = req("POST", "/api/device/push", {"device": X4, "books": ["manga/Test Title/Chapter 2"]})
o = wait(job["id"])["outcomes"][0]
check("push_incremental", o["status"] == "pushed" and o["detail"].startswith("updated 1 of "), str(o))
check("render_cache_cleared", sorted(p.name for p in cache.iterdir()) == ["progress.bin"],
      str(sorted(p.name for p in cache.iterdir())))
check("x4_copy_matches", (Path(SD) / "manga/Test Title/Chapter 2/page_0000.jpg").read_bytes() == (book / "page_0000.jpg").read_bytes())

# 3b. Delta repair from the server: a damaged page and a stray file on the X4; only the damaged page goes over.
dev_book = Path(SD) / "manga/Test Title/Chapter 2"
(dev_book / "page_0005.jpg").write_bytes(b"broken")
(dev_book / "page_0099.png").write_bytes(b"stray")
st, job = req("POST", "/api/device/push", {"device": X4, "books": ["manga/Test Title/Chapter 2"]})
o = wait(job["id"])["outcomes"][0]
check("push_delta_repair", o["status"] == "pushed" and o["detail"].startswith("updated 1 of ") and "removed 1" in o["detail"], str(o))
check("push_delta_restored", (dev_book / "page_0005.jpg").read_bytes() == (book / "page_0005.jpg").read_bytes()
      and not (dev_book / "page_0099.png").exists() and (dev_book / "panels.idx").is_file())

# 4. X3: a job for the X3 re-converts at its size, and Fix problems flags X3 books when the app says X4.
st, job = req("POST", "/api/jobs", {"chapters": ["Test Title/Chapter 2.cbz"], "cover_page": True, "device": "x3"})
wait(job["id"])
check("x3_cover_size", Image.open(book / "page_0000.jpg").size == (528, 792))
st, res = req("GET", "/api/books/check?device=x4")
issues = next((p["issues"] for p in res["problems"] if p["path"] == "manga/Test Title/Chapter 2"), [])
check("check_flags_other_screen", any("X3 screen" in i for i in issues), str(issues))

# 5. App updates: the published version, readable without a token.
r = urllib.request.urlopen(URL + "/api/app/version", timeout=10)
v = json.loads(r.read())
check("app_version_public", r.status == 200 and {"versionCode", "versionName", "sha256"} <= v.keys(), str(v))

# 6. Komikku covers: the server downloads the URL the app found in Komikku's backup (sending a Referer, which
#    image hosts often require), and that art wins over AniList's.
import http.server, threading  # noqa: E401,E402
seen = {}
class Img(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        seen["referer"] = self.headers.get("Referer")
        if not self.headers.get("Referer"):
            self.send_response(403); self.end_headers(); return
        body = jpeg((20, 160, 20)); self.send_response(200); self.send_header("Content-Length", str(len(body)))
        self.end_headers(); self.wfile.write(body)
img = http.server.HTTPServer(("127.0.0.1", 0), Img)
threading.Thread(target=img.serve_forever, daemon=True).start()
st, res = req("POST", "/api/covers/komikku", {"covers": {"Test Title": f"http://127.0.0.1:{img.server_port}/c.jpg"}})
check("komikku_cover_saved", st == 200 and res["saved"] == {"Test Title": True}, str(res))
check("komikku_cover_referer", (seen.get("referer") or "").startswith("http://127.0.0.1"), str(seen))
st, res = req("POST", "/api/covers/komikku", {"covers": {"../x": "http://a/b"}})
check("komikku_cover_rejects_bad", st == 400)

# 7. Fix problems finds a cover page drawn without art (the CLI pre-conversion had none), and a normal run
#    redraws it once art exists.
req("POST", "/api/covers/reset", {"title": "Test Title"})      # drop the custom art; Komikku's remains
st, res = req("GET", "/api/books/check?device=x3&cover_page=1")
p = next((x for x in res["problems"] if x["path"] == "manga/Test Title/Chapter 2"), None)
check("check_reports_cover", p is not None and p.get("fix") == "cover" and p.get("folder") == "Test Title"
      and any("older series art" in i for i in p["issues"]), str(p))
st, job = req("POST", "/api/jobs", {"chapters": ["Test Title/Chapter 2.cbz"], "cover_page": True, "device": "x3"})
j = wait(job["id"])
check("cover_fix_is_redraw", "cover updated" in j["outcomes"][0].get("detail", ""), str(j["outcomes"]))
st, res = req("GET", "/api/books/check?device=x3&cover_page=1")
check("cover_fixed", not any(x["path"] == "manga/Test Title/Chapter 2" for x in res["problems"]), str(res["problems"]))

# 8. Conversion estimates: the tool announces its plan (pages per chapter), the job reports progress and ETA.
st, job = req("POST", "/api/jobs", {"chapters": ["Test Title/Chapter 2.cbz"], "force": True, "cover_page": True, "device": "x3"})
j = wait(job["id"])
check("plan_pages", j.get("pages_total") == 12 and j.get("pages_done") == 12, f"{j.get('pages_total')} {j.get('pages_done')}")
check("eta_reported", j.get("eta_seconds") == 0, str(j.get("eta_seconds")))

# 9. Cover picker: the chapter's first page is a choice; choosing it makes it the series' own cover.
st, res = req("GET", "/api/covers/candidates?title=Test%20Title")
ids = [c["id"] for c in res.get("candidates", [])]
check("candidates_list", "page-first" in ids and "komikku" in ids, str(ids))
st, img = req("GET", "/api/covers/candidate?title=Test%20Title&id=page-first&w=100", raw=True)
check("candidate_image", st == 200 and img[:2] == b"\xff\xd8")
st, _ = req("POST", "/api/covers/choose", {"title": "Test Title", "id": "page-first"})
covers_dir = Path(OUT) / ".covers"
check("candidate_chosen", st == 200 and (covers_dir / "Test Title.custom.jpg").read_bytes() ==
      (covers_dir / "cand/Test Title/page-first.jpg").read_bytes())
st, _ = req("POST", "/api/covers/choose", {"title": "Test Title", "id": "../../etc/passwd"})
check("candidate_rejects_bad", st == 404)

# 10. Sleep screens: drawn from the series art, custom images survive regeneration, pushed to /sleep on the X4.
from PIL import Image
st, res = req("POST", "/api/sleep/generate", {"titles": ["Test Title"], "device": "x4"})
check("sleep_generate", st == 200 and res["results"].get("Test Title") in ("created", "updated"), str(res))
sleep_bmp = Path(OUT) / ".sleep/Test Title.bmp"
im = Image.open(sleep_bmp)
check("sleep_bmp_format", im.size == (480, 800) and im.mode == "L", f"{im.size} {im.mode}")
st, res = req("GET", "/api/sleep")
check("sleep_list", any(i["name"] == "Test Title" and not i["custom"] for i in res["items"]), str(res))
st, img = req("GET", "/api/sleep/image?name=Test%20Title", raw=True)
check("sleep_image_preview", st == 200 and img[:2] == b"\xff\xd8")
st, f = req("GET", "/api/sleep/file?name=Test%20Title", raw=True)
check("sleep_file", st == 200 and f == sleep_bmp.read_bytes())
st, res = req("POST", "/api/sleep/generate", {"titles": ["Test Title"], "device": "x4"})
check("sleep_unchanged", res["results"].get("Test Title") == "unchanged", str(res))
st, _ = req("POST", "/api/sleep/generate", {"titles": ["../x"]})
check("sleep_generate_rejects_bad", st == 400)

st, job = req("POST", "/api/device/push", {"device": X4, "books": ["manga/Test Title/Chapter 2"]})
check("push_without_sleep", not (Path(SD) / "sleep").exists() and wait(job["id"])["status"] == "done")
st, job = req("POST", "/api/device/push", {"device": X4, "books": ["manga/Test Title/Chapter 2"], "sleep": True})
wait(job["id"])
x4_sleep = Path(SD) / "sleep/Test Title.bmp"
check("sleep_pushed", x4_sleep.is_file() and x4_sleep.read_bytes() == sleep_bmp.read_bytes())
marker = b"\0" * len(sleep_bmp.read_bytes())
x4_sleep.write_bytes(marker)
st, job = req("POST", "/api/device/push", {"device": X4, "books": ["manga/Test Title/Chapter 2"], "sleep": True})
wait(job["id"])
check("sleep_repush_skipped", x4_sleep.read_bytes() == marker)
x4_sleep.unlink()
st, job = req("POST", "/api/device/push", {"device": X4, "books": ["manga/Test Title/Chapter 2"], "sleep": True})
wait(job["id"])
check("sleep_repush_when_missing", x4_sleep.is_file())

Image.new("RGB", (100, 100), (0, 0, 255)).save(buf := io.BytesIO(), "PNG")
st, _ = req("PUT", "/api/sleep?name=Test%20Title&device=x4", buf.getvalue(), {"Content-Type": "image/png"})
check("sleep_custom_upload", st == 200 and Image.open(sleep_bmp).size == (480, 800))
custom = sleep_bmp.read_bytes()
st, res = req("POST", "/api/sleep/generate", {"titles": ["Test Title"], "device": "x4"})
check("sleep_custom_kept", res["results"].get("Test Title") == "custom" and sleep_bmp.read_bytes() == custom, str(res))
st, res = req("POST", "/api/sleep/generate", {"titles": ["Test Title"], "device": "x4", "force": True})
check("sleep_force_redraws", res["results"].get("Test Title") in ("updated", "created") and sleep_bmp.read_bytes() != custom, str(res))
st, _ = req("PUT", "/api/sleep?name=Test%20Title", b"not an image")
check("sleep_upload_rejects_non_image", st == 400)

st, job = req("POST", "/api/jobs", {"chapters": ["Test Title/Chapter 2.cbz"], "force": True, "device": "x4", "sleep": True})
j = wait(job["id"])
check("sleep_on_convert", j["status"] == "done" and "sleep screen" in (j.get("note") or "") or sleep_bmp.is_file(), str(j.get("note")))
st, res = req("POST", "/api/sleep/delete", {"names": ["Test Title"]})
check("sleep_delete", res.get("deleted") == ["Test Title"] and not sleep_bmp.exists(), str(res))

# 11. Delete + don't sync again: the book and its CBZ go, the chapter is remembered as ignored; "sync again" undoes it.
st, job = req("POST", "/api/books/delete", {"books": ["manga/Test Title/Chapter 2"], "ignore": True})
j = wait(job["id"])
check("delete_job", j["status"] == "done" and j["outcomes"][0]["status"] == "deleted", str(j))
check("delete_removed", not book.exists() and not (Path(OUT).parent / "in/Test Title/Chapter 2.cbz").exists())
st, lib = req("GET", "/api/library")
check("delete_ignored", "Test Title/Chapter 2.cbz" in lib["ignored"]["chapters"], str(lib["ignored"]))
req("POST", "/api/ignored/remove", {"chapters": ["Test Title/Chapter 2.cbz"]})
st, lib = req("GET", "/api/library")
check("unignore", "Test Title/Chapter 2.cbz" not in lib["ignored"]["chapters"])

sys.exit(1 if failures else 0)

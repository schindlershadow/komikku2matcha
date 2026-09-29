import io, zipfile, subprocess, os, sys, tempfile
from PIL import Image, ImageDraw
root = os.path.join(sys.argv[1], "downloads/TestSource/Test Manga_ Vol"); os.makedirs(root, exist_ok=True)
tmp = tempfile.mkdtemp()
def page(n, w=900, h=1300):
    im = Image.new("RGB", (w, h), "white"); d = ImageDraw.Draw(im)
    d.rectangle([30, 30, w-30, h//2-20], outline="black", width=8); d.rectangle([30, h//2+20, w-30, h-30], outline="black", width=8)
    d.text((w//3, h//4), str(n), fill="black", font_size=300); return im
def enc(im, fmt): b = io.BytesIO(); im.save(b, fmt); return b.getvalue()
ci = lambda t, n: f"<ComicInfo><Title>{t}</Title><Series>Test Manga? Vol</Series><Number>{n}</Number><Writer>Some Author</Writer><LanguageISO>ja</LanguageISO></ComicInfo>"
with zipfile.ZipFile(f"{root}/Chapter 1_a1b2c3.cbz", "w") as z:
    for i in range(1, 12): z.writestr(f"{i:03d}.webp", enc(page(i), "WEBP"))
    z.writestr("ComicInfo.xml", ci("Chapter 1", 1)); z.writestr(".nomedia", "")
with zipfile.ZipFile(f"{root}/Chapter 2.cbz", "w") as z:
    for i in range(1, 12): z.writestr(f"Chapter 2/scans/{i}.{'png' if i%3==0 else 'jpg'}", enc(page(100+i), "PNG" if i%3==0 else "JPEG"))
    z.writestr("__MACOSX/Chapter 2/scans/._1.jpg", b"\x00\x05\x16\x07junk"); z.writestr("Chapter 2/ComicInfo.xml", ci("Chapter 2", 2))
for i in range(1, 4):
    page(200+i).save(f"{tmp}/p{i}.png"); subprocess.run(["magick", f"{tmp}/p{i}.png", f"{tmp}/p{i}.avif"], check=True)
with zipfile.ZipFile(f"{root}/Chapter 3.cbz", "w") as z:
    for i in range(1, 4): z.writestr(f"{i:02d}.avif", open(f"{tmp}/p{i}.avif","rb").read())
    z.writestr("04.jpg", enc(page(204), "WEBP")); z.writestr("ComicInfo.xml", ci("Chapter 3", 3))
with zipfile.ZipFile(f"{root}/Chapter 4.cbz", "w") as z:
    z.writestr("001.jpg", enc(page(301), "JPEG")); z.writestr("002.jpg", enc(page(302), "JPEG")[:20000])
open(f"{root}/Chapter 5.cbz", "wb").write(b"not a zip")
with zipfile.ZipFile(f"{root}/Chapter 6.cbz", "w") as z: z.writestr("ComicInfo.xml", ci("Chapter 6", 6))

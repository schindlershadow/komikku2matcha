#!/usr/bin/env python3
"""Run Matcha Reader's convert_manga.py with cheaper Gemini OCR, without editing the converter.

    python3 matcha_ocr_shim.py <convert_manga.py> [--k2m-thinking LEVEL] [--k2m-no-translation] [converter args...]
    python3 matcha_ocr_shim.py <convert_manga.py> --k2m-check

It loads convert_manga.py as a module, replaces its Gemini call and prompt builder, then runs its main():

  --k2m-thinking LEVEL   Sent as generationConfig.thinkingConfig.thinkingLevel ("default" sends nothing,
                         i.e. the model's own default). Thinking tokens are billed as output; on a
                         measured panel they were 1305 of 1816 output tokens, and "minimal" gave the
                         same text.
  --k2m-no-translation   Ask for the text only. Uses the converter's own "already in the target
                         language" prompt path, so the panel's translation field is left empty.

Always, when OCR runs:
  - a block's box is widened to cover its line boxes (with little thinking the model can return a
    block box shorter than the lines it contains; the device hit-tests the lines, but the outline
    uses the block)
  - the API key goes to curl on stdin (-H @-), not on its command line where `ps` would show it
  - each call prints "K2M-USAGE in=N out=N thoughts=N" so the caller can total the token spend
  - a panel that still fails after the retries prints a "Gemini error" warning instead of silently
    coming back empty
"""

from __future__ import annotations

import base64
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import time

# What this shim touches in convert_manga.py; --k2m-check fails if any of it is gone.
REQUIRED = ["main", "call_gemini_panel_ocr", "_call_gemini_panel_ocr_once", "build_panel_ocr_prompt",
            "OCR_LANGUAGE_NAMES", "TRANSLATION_TARGET", "GEMINI_URL"]
TRANSIENT = ("UNAVAILABLE", "RESOURCE_EXHAUSTED", "DEADLINE_EXCEEDED", "INTERNAL")
EMPTY = {"blocks": [], "translation": ""}


def load(path: str):
    spec = importlib.util.spec_from_file_location("convert_manga", path)
    cm = importlib.util.module_from_spec(spec)
    sys.modules["convert_manga"] = cm
    spec.loader.exec_module(cm)
    return cm


def union_line_boxes(block: dict) -> None:
    lines = block.get("lines")
    boxes = [l.get("bbox_2d") for l in lines] if isinstance(lines, list) and lines else []
    if not boxes or not all(isinstance(b, list) and len(b) == 4 for b in boxes):
        return
    try:
        u = [min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes)]
    except TypeError:
        return
    own = block.get("bbox_2d")
    if isinstance(own, list) and len(own) == 4 and all(isinstance(v, (int, float)) for v in own):
        u = [min(u[0], own[0]), min(u[1], own[1]), max(u[2], own[2]), max(u[3], own[3])]
    block["bbox_2d"] = u


def install(cm, thinking: str, no_translation: bool) -> None:
    last_error: dict[int, str] = {}  # per thread: why the last attempt failed

    def once(image_path: str, api_key: str, prompt: str, timeout: int) -> dict | None:
        """Same contract as the converter's: None = transient, retry; a dict = final answer."""
        import threading
        tid = threading.get_ident()
        with open(image_path, "rb") as f:
            image_b64 = base64.b64encode(f.read()).decode("ascii")
        mime = "image/png" if image_path.lower().endswith(".png") else "image/jpeg"
        gen = {"responseMimeType": "application/json"}
        if thinking != "default":
            gen["thinkingConfig"] = {"thinkingLevel": thinking}
        payload = {"contents": [{"parts": [{"text": prompt},
                                           {"inline_data": {"mime_type": mime, "data": image_b64}}]}],
                   "generationConfig": gen}
        with tempfile.NamedTemporaryFile(mode="w", suffix=".json", delete=False, encoding="utf-8") as tf:
            json.dump(payload, tf)
            payload_path = tf.name
        try:
            result = subprocess.run(
                ["curl", "-s", "-X", "POST", cm.GEMINI_URL, "-H", "Content-Type: application/json",
                 "-H", "@-", "-d", f"@{payload_path}"],
                input=f"x-goog-api-key: {api_key}\n", capture_output=True, text=True, timeout=timeout)
        except (subprocess.TimeoutExpired, subprocess.SubprocessError, OSError) as e:
            last_error[tid] = type(e).__name__
            return None
        finally:
            os.unlink(payload_path)
        if result.returncode != 0:
            last_error[tid] = f"curl exit {result.returncode}"
            return None
        try:
            response = json.loads(result.stdout)
        except json.JSONDecodeError:
            last_error[tid] = "malformed response"
            return None

        if "error" in response:
            status = response["error"].get("status", "")
            if status in TRANSIENT:
                last_error[tid] = status
                return None
            print(f"  Warning: Gemini error: {response['error'].get('message', status)[:200]}", file=sys.stderr)
            return dict(EMPTY)

        u = response.get("usageMetadata", {})
        print(f"K2M-USAGE in={u.get('promptTokenCount', 0)} out={u.get('candidatesTokenCount', 0)} "
              f"thoughts={u.get('thoughtsTokenCount', 0)}", flush=True)
        try:
            parsed = json.loads(response["candidates"][0]["content"]["parts"][0]["text"])
            if not isinstance(parsed, dict):
                return dict(EMPTY)
            blocks = parsed.get("blocks", [])
            blocks = [b for b in blocks if isinstance(b, dict) and "text" in b] if isinstance(blocks, list) else []
            for b in blocks:
                union_line_boxes(b)
            translation = parsed.get("translation", "")
            return {"blocks": blocks, "translation": translation if isinstance(translation, str) else ""}
        except (KeyError, IndexError, TypeError, json.JSONDecodeError) as e:
            print(f"  Warning: could not parse Gemini response ({e}): {result.stdout[:200]}", file=sys.stderr)
            return dict(EMPTY)

    def call(image_path: str, api_key: str, prompt: str, timeout: int = 60, retries: int = 3) -> dict:
        import threading
        for attempt in range(retries):
            result = once(image_path, api_key, prompt, timeout)
            if result is not None:
                return result
            if attempt < retries - 1:
                time.sleep(2 ** attempt)
        why = last_error.get(threading.get_ident(), "unknown")
        print(f"  Warning: Gemini error: gave up on a panel after {retries} attempts ({why})", file=sys.stderr)
        return dict(EMPTY)

    cm._call_gemini_panel_ocr_once = once
    cm.call_gemini_panel_ocr = call

    if no_translation:
        original_prompt = cm.build_panel_ocr_prompt

        def prompt(language: str = "", rtl: bool = True) -> str:
            # The converter asks for no translation when the book is already in TRANSLATION_TARGET,
            # so pointing the target at the book's own language reuses that exact prompt.
            primary = language.strip().lower().replace("_", "-").partition("-")[0]
            name = cm.OCR_LANGUAGE_NAMES.get(primary)
            if not name:
                print("  Note: no known --language, so the OCR prompt still asks for a translation",
                      file=sys.stderr)
                return original_prompt(language, rtl)
            saved = cm.TRANSLATION_TARGET
            cm.TRANSLATION_TARGET = name
            try:
                return original_prompt(language, rtl)
            finally:
                cm.TRANSLATION_TARGET = saved

        cm.build_panel_ocr_prompt = prompt


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    converter, args = sys.argv[1], sys.argv[2:]
    thinking, no_translation, check = "default", False, False
    rest = []
    i = 0
    while i < len(args):
        if args[i] == "--k2m-thinking":
            thinking = args[i + 1]
            i += 2
            continue
        if args[i] == "--k2m-no-translation":
            no_translation = True
        elif args[i] == "--k2m-check":
            check = True
        else:
            rest.append(args[i])
        i += 1

    cm = load(converter)
    missing = [n for n in REQUIRED if not hasattr(cm, n)]
    if missing:
        sys.exit(f"Error: {converter} no longer has {', '.join(missing)}; update matcha_ocr_shim.py")
    if check:
        print("ok")
        return
    install(cm, thinking, no_translation)
    sys.argv = [converter] + rest
    cm.main()


if __name__ == "__main__":
    main()

"""Image-based formula recognizers fed with rendered strokes.

Strokes are rasterized black-on-white, the way NibTeXa would render a selected
region before handing it to an image model on the device.

Latency here is measured on the laptop (GPU if available, else CPU) and is not
an Android figure.
"""

from __future__ import annotations

import time

from PIL import Image, ImageDraw

from .inkml import Sample

TARGET_HEIGHT = 128
PADDING = 16


def render(sample: Sample, target_height: int = TARGET_HEIGHT) -> Image.Image:
    xs = [x for s in sample.strokes for x, _, _ in s]
    ys = [y for s in sample.strokes for _, y, _ in s]
    width, height = max(xs) - min(xs), max(ys) - min(ys)
    scale = target_height / max(height, width / 8, 1.0)  # cap the scale for very flat expressions
    line = max(2, round(target_height / 40))
    w = int(width * scale) + 2 * PADDING
    h = int(height * scale) + 2 * PADDING
    image = Image.new("RGB", (w, h), "white")
    draw = ImageDraw.Draw(image)
    for stroke in sample.strokes:
        points = [((x - min(xs)) * scale + PADDING, (y - min(ys)) * scale + PADDING) for x, y, _ in stroke]
        if len(points) == 1:
            (px, py), r = points[0], line / 2
            draw.ellipse([px - r, py - r, px + r, py + r], fill="black")
        else:
            draw.line(points, fill="black", width=line, joint="curve")
    return image


def recognize(candidate: str, samples: list[Sample]) -> dict[str, dict]:
    predict = _load(candidate)
    results = {}
    for s in samples:
        image = render(s)
        started = time.perf_counter()
        try:
            latex, code = predict(image), 0
        except Exception as e:  # a recognizer crash is a failed sample, not a failed run
            latex, code = "", 1
            print(f"{s.id}: {type(e).__name__}: {e}")
        results[s.id] = {"latex": latex, "exit": code, "seconds": round(time.perf_counter() - started, 3), "max_rss_kb": None}
    return results


def _load(candidate: str):
    if candidate == "pix2tex":
        from pix2tex.cli import LatexOCR

        model = LatexOCR()
        return lambda image: model(image)

    if candidate.startswith("texteller"):
        import os

        import torch
        from texteller import img2latex, load_model, load_tokenizer

        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        if candidate == "texteller":
            model = load_model()
        elif candidate == "texteller-onnx":
            model = load_model(use_onnx=True)
        else:  # texteller-onnx-int8: output of quantize_texteller.py
            from optimum.onnxruntime import ORTModelForVision2Seq

            model = ORTModelForVision2Seq.from_pretrained(os.environ["TEXTELLER_INT8_DIR"])
        tokenizer = load_tokenizer()

        def predict(image: Image.Image) -> str:
            import numpy as np

            return img2latex(model, tokenizer, [np.array(image)], device=device)[0]

        return predict

    raise ValueError(candidate)

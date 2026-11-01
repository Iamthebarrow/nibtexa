"""Exports parity fixtures for the Kotlin port in :core:recognition.

Two fixtures, both built only from project-owned inputs:
- preprocess.json: TexTeller's `transform` applied to synthetic images defined by a formula
  that the Kotlin test reproduces (no third-party images). Each entry stores the output
  size, sum, sum of squares and values at fixed sample positions.
- decode.json: the corpus prompts (app/src/main/assets/corpus/prompts.tsv) encoded with
  TexTeller's tokenizer, with the text `tokenizer.decode(..., skip_special_tokens=True)`
  returns, so the Kotlin byte-level BPE decoder can be checked against it.

Usage:
    python -m nibtexa_eval.export_kotlin_fixtures --prompts PATH --out DIR
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

IMAGES = {"wide": (40, 300), "large": (700, 900), "tall": (200, 50), "small": (20, 60)}
SAMPLES = 200


def synthetic(height: int, width: int) -> np.ndarray:
    """White page with a dark frame, a diagonal stroke and a gradient band (same as the Kotlin test)."""
    y, x = np.mgrid[0:height, 0:width]
    image = np.full((height, width), 255, dtype=np.int64)
    frame = (x >= width // 10) & (x < width - width // 10) & (y >= height // 10) & (y < height - height // 10)
    image[frame] = 255 - (((x[frame] * 7 + y[frame] * 13) % 97) * 2)
    diagonal = np.abs(x * height - y * width) <= max(height, width)
    image[diagonal] = 0
    return np.clip(image, 0, 255).astype(np.uint8)


def sample_positions(size: int = 448) -> list[tuple[int, int]]:
    return [((i * 7919) % size, (i * 104729) % size) for i in range(SAMPLES)]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prompts", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    from texteller import load_tokenizer
    from texteller.utils.image import transform

    args.out.mkdir(parents=True, exist_ok=True)

    entries = {}
    for name, (h, w) in IMAGES.items():
        gray = synthetic(h, w)
        rgb = np.stack([gray] * 3, axis=-1)
        tensor = transform([rgb])[0][0].numpy().astype(np.float64)  # 448 x 448
        nonpad = np.argwhere(tensor != 0)
        entries[name] = {
            "height": h,
            "width": w,
            "sum": float(tensor.sum()),
            "sumSquares": float((tensor ** 2).sum()),
            "contentRows": int(nonpad[:, 0].max()) + 1,
            "contentCols": int(nonpad[:, 1].max()) + 1,
            "samples": [[r, c, float(tensor[r, c])] for r, c in sample_positions()],
        }
    (args.out / "preprocess.json").write_text(json.dumps(entries, indent=1) + "\n", encoding="utf-8")

    tokenizer = load_tokenizer()
    cases = []
    for line in args.prompts.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        latex = line.split("\t")[2]
        ids = tokenizer.encode(latex)
        cases.append({"ids": ids, "text": tokenizer.decode(ids, skip_special_tokens=True)})
    (args.out / "decode.json").write_text(json.dumps(cases, ensure_ascii=False, indent=0) + "\n", encoding="utf-8")
    print(f"{len(entries)} images, {len(cases)} decode cases, special ids {tokenizer.all_special_ids}")


if __name__ == "__main__":
    main()

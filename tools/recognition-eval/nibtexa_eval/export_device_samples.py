"""Exports ink samples for the on-device end-to-end check (TexTellerEndToEndTest in :app).

Writes samples.json: for each selected sample its id, ground truth, strokes (x, y, t) and,
if a laptop results file is given, the laptop prediction for the same sample, so the device
can report per-sample agreement as well as accuracy.

The data is evaluation-only (MathWriting is CC BY-NC-SA): write it outside the repository
and push it to the emulator; never commit it.

Usage:
    python -m nibtexa_eval.export_device_samples --data DIR --count 100 \
        --laptop RESULTS.jsonl --out DIR
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from .run import select


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--count", type=int, default=100)
    parser.add_argument("--laptop", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    laptop = {}
    if args.laptop:
        for line in args.laptop.read_text(encoding="utf-8").splitlines():
            row = json.loads(line)
            laptop[row["id"]] = row["predicted"]

    samples = []
    for sample in select(args.data, args.count, seed=0):
        samples.append({
            "id": sample.id,
            "truth": sample.truth,
            "strokes": [[[x, y, t] for x, y, t in stroke] for stroke in sample.strokes],
            "laptop": laptop.get(sample.id),
        })
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "samples.json").write_text(json.dumps(samples), encoding="utf-8")
    print(f"{len(samples)} samples, {sum(s['laptop'] is not None for s in samples)} with laptop predictions")


if __name__ == "__main__":
    main()

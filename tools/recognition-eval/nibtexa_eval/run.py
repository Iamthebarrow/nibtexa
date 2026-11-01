"""Evaluates recognizers on a deterministic sample of InkML files.

Usage:
    python -m nibtexa_eval.run --data DIR --candidate seshat --limit 500 --out RESULTS_DIR

Per-sample results contain dataset labels, so RESULTS_DIR must be outside the
repository when evaluating third-party data (MathWriting is CC BY-NC-SA).
"""

from __future__ import annotations

import argparse
import json
import random
import statistics
import time
from pathlib import Path

from . import latex
from .inkml import Sample, load


def select(data: Path, limit: int, seed: int) -> list[Sample]:
    files = sorted(data.glob("*.inkml"))
    random.Random(seed).shuffle(files)
    return [load(f) for f in files[:limit]]


def score(samples: list[Sample], outputs: dict[str, dict]) -> tuple[list[dict], dict]:
    rows = []
    for s in samples:
        out = outputs.get(s.id, {})
        predicted = out.get("latex") or ""
        truth_tokens, predicted_tokens = latex.normalize(s.truth), latex.normalize(predicted)
        distance = latex.edit_distance(predicted_tokens, truth_tokens)
        rows.append({
            "id": s.id,
            "truth": s.truth,
            "predicted": predicted,
            "exact": predicted_tokens == truth_tokens,
            "token_errors": distance,
            "token_error_rate": distance / max(1, len(truth_tokens)),
            "failed": not predicted or out.get("exit", 0) != 0,
            "seconds": out.get("seconds"),
            "max_rss_kb": out.get("max_rss_kb"),
            "in_seshat_vocabulary": latex.in_seshat_vocabulary(s.truth),
        })
    return rows, summarize(rows)


def summarize(rows: list[dict]) -> dict:
    def block(subset: list[dict]) -> dict:
        n = len(subset)
        if n == 0:
            return {"n": 0}
        secs = sorted(r["seconds"] for r in subset if r["seconds"] is not None)
        rss = [r["max_rss_kb"] for r in subset if r["max_rss_kb"] is not None]
        return {
            "n": n,
            "exact_match": sum(r["exact"] for r in subset) / n,
            # A failed (empty) output is never a near-miss, even for one-token truths.
            "within_1_token": sum(not r["failed"] and r["token_errors"] <= 1 for r in subset) / n,
            "within_2_tokens": sum(not r["failed"] and r["token_errors"] <= 2 for r in subset) / n,
            "mean_token_error_rate": statistics.fmean(1.0 if r["failed"] else min(1.0, r["token_error_rate"]) for r in subset),
            "failures": sum(r["failed"] for r in subset),
            "latency_p50_s": secs[len(secs) // 2] if secs else None,
            "latency_p95_s": secs[min(len(secs) - 1, int(len(secs) * 0.95))] if secs else None,
            "peak_rss_mb_max": max(rss) / 1024 if rss else None,
        }

    return {
        "all": block(rows),
        "in_seshat_vocabulary": block([r for r in rows if r["in_seshat_vocabulary"]]),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument(
        "--candidate",
        choices=["seshat", "texteller", "texteller-onnx", "texteller-onnx-int8", "pix2tex"],
        required=True,
    )
    parser.add_argument("--limit", type=int, default=500)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--jobs", type=int, default=8)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    samples = select(args.data, args.limit, args.seed)
    started = time.time()
    if args.candidate == "seshat":
        from . import seshat
        outputs = seshat.recognize(samples, jobs=args.jobs)
    else:
        from . import image_models
        outputs = image_models.recognize(args.candidate, samples)
    rows, summary = score(samples, outputs)
    if rows and all(r["failed"] for r in rows):
        raise SystemExit("Every sample failed: the candidate is not running correctly; not writing results.")
    summary.update({
        "candidate": args.candidate,
        "data": str(args.data),
        "limit": args.limit,
        "seed": args.seed,
        "wall_seconds": round(time.time() - started, 1),
    })

    args.out.mkdir(parents=True, exist_ok=True)
    stem = f"{args.candidate}_{args.data.name}_n{args.limit}_s{args.seed}"
    with open(args.out / f"{stem}.jsonl", "w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    (args.out / f"{stem}.summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()

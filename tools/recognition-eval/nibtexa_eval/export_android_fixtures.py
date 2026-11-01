"""Exports fixtures for the on-device ONNX Runtime check (spike/recognition).

For each sample it writes:
- <id>.pixels.f32: TexTeller's preprocessed input (1x1x448x448 float32, little-endian),
- an entry in expected.json: the token ids produced by greedy decoding with ONNX Runtime on
  the laptop, both cache-less (`token_ids`, Phase 3) and with the KV cache as the app decodes
  (`cached_token_ids`), plus the decoded LaTeX and the ground truth. With dynamic int8
  quantization the two can differ by a token: activation scales depend on the sequence fed.

The Android test must reproduce the token ids exactly, which shows the same model
and runtime behave identically on the device.

Usage:
    python -m nibtexa_eval.export_android_fixtures --data DIR --model DIR --count 10 --out DIR
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort

from .image_models import render
from .run import select

BOS, EOS = 0, 2
MAX_TOKENS = 256


def greedy_decode(encoder: ort.InferenceSession, decoder: ort.InferenceSession, pixels: np.ndarray) -> list[int]:
    hidden = encoder.run(None, {"pixel_values": pixels})[0]
    empty = {
        i.name: np.zeros((1, i.shape[1], 0, i.shape[3]), dtype=np.float32)
        for i in decoder.get_inputs() if i.name.startswith("past_key_values")
    }
    ids = [BOS]
    for _ in range(MAX_TOKENS):
        feeds = {"input_ids": np.array([ids], dtype=np.int64), "encoder_hidden_states": hidden, **empty}
        if any(i.name == "use_cache_branch" for i in decoder.get_inputs()):
            feeds["use_cache_branch"] = np.array([False])
        logits = decoder.run(["logits"], feeds)[0]
        next_id = int(np.argmax(logits[0, -1]))
        ids.append(next_id)
        if next_id == EOS:
            break
    return ids


def cached_decode(encoder: ort.InferenceSession, decoder: ort.InferenceSession, pixels: np.ndarray) -> list[int]:
    """Greedy decoding with the KV cache, as the app does (OnnxTexTellerModel): the encoder
    (cross-attention) caches come from the first step and are reused unchanged."""
    hidden = encoder.run(None, {"pixel_values": pixels})[0]
    inputs = [i for i in decoder.get_inputs() if i.name.startswith("past_key_values")]
    past = {i.name: np.zeros((1, i.shape[1], 0, i.shape[3]), dtype=np.float32) for i in inputs}
    output_names = [o.name for o in decoder.get_outputs()]
    ids = [BOS]
    for step in range(MAX_TOKENS):
        feeds = {"input_ids": np.array([[ids[-1]]], dtype=np.int64), "encoder_hidden_states": hidden,
                 "use_cache_branch": np.array([step > 0]), **past}
        outputs = dict(zip(output_names, decoder.run(None, feeds)))
        next_id = int(np.argmax(outputs["logits"][0, -1]))
        for name in past:
            if step == 0 or ".encoder." not in name:
                past[name] = outputs[name.replace("past_key_values.", "present.")]
        ids.append(next_id)
        if next_id == EOS:
            break
    return ids


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--count", type=int, default=10)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    from texteller import load_tokenizer
    from texteller.utils.image import transform

    tokenizer = load_tokenizer()
    options = ort.SessionOptions()
    encoder = ort.InferenceSession(str(args.model / "encoder_model.onnx"), options, providers=["CPUExecutionProvider"])
    decoder = ort.InferenceSession(str(args.model / "decoder_model_merged.onnx"), options, providers=["CPUExecutionProvider"])

    args.out.mkdir(parents=True, exist_ok=True)
    expected = []
    for sample in select(args.data, args.count, seed=0):
        pixels = transform([np.array(render(sample))])[0].unsqueeze(0).numpy().astype("<f4")
        (args.out / f"{sample.id}.pixels.f32").write_bytes(pixels.tobytes())
        started = time.perf_counter()
        ids = greedy_decode(encoder, decoder, pixels)
        seconds = time.perf_counter() - started
        cached = cached_decode(encoder, decoder, pixels)
        expected.append({
            "id": sample.id,
            "shape": list(pixels.shape),
            "token_ids": ids,
            "cached_token_ids": cached,
            "latex": tokenizer.decode(ids, skip_special_tokens=True),
            "truth": sample.truth,
            "laptop_seconds": round(seconds, 3),
        })
        print(f"{sample.id}: {len(ids)} tokens, {seconds:.2f}s, cached {'same' if cached == ids else 'DIFFERS'}  {expected[-1]['latex']}")
    (args.out / "expected.json").write_text(json.dumps(expected, indent=1), encoding="utf-8")


if __name__ == "__main__":
    main()

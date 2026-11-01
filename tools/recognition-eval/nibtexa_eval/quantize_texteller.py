"""Exports TexTeller's published ONNX files with dynamic int8 weight quantization.

Usage:
    python -m nibtexa_eval.quantize_texteller --out DIR

The F32 ONNX files come from the model's Hugging Face repository (downloaded by
texteller with use_onnx=True). Only weights are quantized (dynamic QInt8), which
is what ONNX Runtime Mobile supports without calibration data.
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path

from huggingface_hub import snapshot_download
from onnxruntime.quantization import QuantType, quantize_dynamic

REPO = "OleehyO/TexTeller"
ONNX_FILES = ["encoder_model.onnx", "decoder_model_merged.onnx"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    source = Path(snapshot_download(REPO, allow_patterns=["*.json", "*.txt", *ONNX_FILES]))
    args.out.mkdir(parents=True, exist_ok=True)
    for f in source.iterdir():
        if f.suffix in (".json", ".txt"):
            shutil.copy(f, args.out / f.name)
    for name in ONNX_FILES:
        # The merged decoder keeps its weights inside If-node subgraphs (with/without
        # KV cache); without EnableSubgraph they are silently left in F32.
        quantize_dynamic(
            source / name, args.out / name,
            weight_type=QuantType.QInt8,
            extra_options={"EnableSubgraph": True},
        )
    for f in sorted(list(source.glob("*.onnx")) + list(args.out.glob("*.onnx"))):
        print(f"{f.stat().st_size / 1048576:9.1f} MB  {f}")


if __name__ == "__main__":
    main()

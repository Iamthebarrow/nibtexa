"""Reads handwriting samples from InkML (MathWriting and NibTeXa corpus files)."""

from __future__ import annotations

import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

NS = {"ink": "http://www.w3.org/2003/InkML"}

Point = tuple[float, float, float]  # x, y, t (ms)


@dataclass(frozen=True)
class Sample:
    id: str
    strokes: list[list[Point]]
    truth: str
    source: str  # "mathwriting" or "nibtexa"


def load(path: Path) -> Sample:
    root = ET.parse(path).getroot()
    annotations = {a.get("type"): (a.text or "") for a in root.findall("ink:annotation", NS)}

    channels = [c.get("name") for c in root.findall("ink:traceFormat/ink:channel", NS)]
    ix, iy = channels.index("X"), channels.index("Y")
    it = channels.index("T") if "T" in channels else None

    strokes: list[list[Point]] = []
    for trace in root.findall("ink:trace", NS):
        points: list[Point] = []
        for raw in (trace.text or "").split(","):
            values = raw.split()
            if not values:
                continue
            t = float(values[it]) if it is not None else float(len(points))
            points.append((float(values[ix]), float(values[iy]), t))
        if points:
            strokes.append(points)

    if "normalizedLabel" in annotations:
        truth, source = annotations["normalizedLabel"], "mathwriting"
    elif "truth" in annotations:
        truth, source = annotations["truth"].strip().strip("$"), "nibtexa"
    else:
        raise ValueError(f"{path}: no ground-truth annotation")
    return Sample(id=path.stem, strokes=strokes, truth=truth, source=source)


def to_scgink(sample: Sample) -> str:
    """Seshat's plain-text input format."""
    lines = ["SCG_INK", str(len(sample.strokes))]
    for stroke in sample.strokes:
        lines.append(str(len(stroke)))
        lines.extend(f"{x:.2f} {y:.2f}" for x, y, _ in stroke)
    return "\n".join(lines) + "\n"

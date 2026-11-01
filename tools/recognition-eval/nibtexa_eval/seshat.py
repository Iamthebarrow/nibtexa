"""Runs Seshat (GPL-3.0) inside its period-correct Docker image.

Seshat runs as a Linux binary in Docker on the laptop, so latencies here are
laptop figures and say nothing about Android performance.
"""

from __future__ import annotations

import base64
import shutil
import subprocess
import tempfile
from pathlib import Path

from .inkml import Sample, to_scgink

IMAGE = "nibtexa-seshat:84c677c"

# Recognizes each sample under GNU time. Prints one line per sample:
# RESULT<TAB>id<TAB>exit<TAB>"seconds kb"<TAB>base64(latex)
_SCRIPT = r"""
cd /seshat
run_one() {
  f="$1"; id=$(basename "$f" .scgink)
  out=$(/usr/bin/time -f '%e %M' -o /tmp/$id.time timeout 120 ./seshat -c Config/CONFIG -i "$f" 2>/dev/null)
  code=$?
  latex=$(printf '%s\n' "$out" | awk 'found {print; exit} /^LaTeX:/ {found=1}')
  printf 'RESULT\t%s\t%d\t%s\t%s\n' "$id" "$code" "$(tail -n 1 /tmp/$id.time 2>/dev/null)" "$(printf '%s' "$latex" | base64 -w 0)"
}
export -f run_one
ls /in/*.scgink | xargs -P "$JOBS" -I{} bash -c 'run_one {}'
"""


def recognize(samples: list[Sample], jobs: int = 8) -> dict[str, dict]:
    if shutil.which("docker") is None:
        raise RuntimeError("docker is required for the Seshat candidate")
    with tempfile.TemporaryDirectory() as tmp:
        for s in samples:
            # Bytes, not text: Seshat requires LF line endings, and Windows text mode writes CRLF.
            (Path(tmp) / f"{s.id}.scgink").write_bytes(to_scgink(s).encode("ascii"))
        proc = subprocess.run(
            ["docker", "run", "--rm", "-e", f"JOBS={jobs}", "-v", f"{tmp}:/in:ro", IMAGE, "bash", "-c", _SCRIPT],
            capture_output=True, text=True, check=True,
        )
    return parse_output(proc.stdout)


def parse_output(stdout: str) -> dict[str, dict]:
    results = {}
    for line in stdout.splitlines():
        parts = line.split("\t")
        if len(parts) != 5 or parts[0] != "RESULT":
            continue
        _, sample_id, code, timing, encoded = parts
        fields = timing.split()
        seconds = kb = None
        if len(fields) == 2:
            try:
                seconds, kb = float(fields[0]), int(fields[1])
            except ValueError:
                pass
        results[sample_id] = {
            "latex": base64.b64decode(encoded).decode("utf-8", errors="replace").strip(),
            "exit": int(code),
            "seconds": seconds,
            "max_rss_kb": kb,
        }
    return results

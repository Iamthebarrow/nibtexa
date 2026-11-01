# Recognition evaluation harness (Phase 3)

Evaluation-only tooling for comparing handwriting-to-LaTeX recognizers. Nothing
here ships in the app. The conclusions are in
[ADR-0003](../../docs/decisions/ADR-0003-recognizer.md).

## Data rules

- **Third-party data stays outside the repository.** MathWriting is CC BY-NC-SA 4.0
  and may only be used locally for evaluation. Keep it, and every per-sample
  result file (which contains its labels), in a sibling directory such as
  `../nibtexa-eval-data/`. Only aggregate metrics may be published.
- NibTeXa's own corpus (`test-corpus/`) can be evaluated the same way.

## Setup (Windows, Git Bash)

```sh
cd tools/recognition-eval
py -3.12 -m venv .venv                      # git-ignored
.venv/Scripts/python.exe -m pip install -r requirements.txt
.venv/Scripts/python.exe -m unittest discover -s tests -t .
```

The Seshat candidate needs Docker. Build its period-correct image (Ubuntu 14.04,
GCC 4.8; the 2015 source does not compile with modern compilers) from this
Dockerfile:

```dockerfile
FROM ubuntu:14.04
RUN apt-get update && apt-get install -y --no-install-recommends g++ make libxerces-c-dev libboost-dev git ca-certificates time && rm -rf /var/lib/apt/lists/*
RUN git clone https://github.com/falvaro/seshat.git /seshat && cd /seshat && git checkout 84c677cf3b916646ea122003236b5ca96039ff2a && make -j8
WORKDIR /seshat
```

```sh
docker build -t nibtexa-seshat:84c677c .
```

## Running

```sh
DATA=../../../nibtexa-eval-data/mathwriting-2024/test
OUT=../../../nibtexa-eval-data/results
.venv/Scripts/python.exe -m nibtexa_eval.run --data $DATA --candidate seshat    --limit 500 --jobs 16 --out $OUT
.venv/Scripts/python.exe -m nibtexa_eval.run --data $DATA --candidate texteller --limit 500 --out $OUT

# int8 variant
.venv/Scripts/python.exe -m nibtexa_eval.quantize_texteller --out ../../../nibtexa-eval-data/texteller-int8
TEXTELLER_INT8_DIR=../../../nibtexa-eval-data/texteller-int8 \
  .venv/Scripts/python.exe -m nibtexa_eval.run --data $DATA --candidate texteller-onnx-int8 --limit 100 --out $OUT
```

Samples are chosen deterministically: all files are sorted, shuffled with
`--seed` (default 0), and the first `--limit` are taken. A smaller `--limit` with
the same seed is therefore a prefix of a larger one.

## Metrics

Both sides are normalized before comparison (see `nibtexa_eval/latex.py`):

- spacing and `\left`/`\right` are dropped, and so are math delimiters;
- synonyms are unified (`\dfrac`→`\frac`, `\to`→`\rightarrow` and so on);
- operator names are spelled out (`\sin` and `sin` are the same handwriting);
- script arguments are always braced (`x^2` is the same as `x^{2}`).

| Metric | Meaning |
|---|---|
| `exact_match` | Normalized token sequences are identical (the "ExpRate" used in CROHME). |
| `within_1_token`, `within_2_tokens` | Token edit distance ≤ 1 or ≤ 2. A failed (empty) output never counts. |
| `mean_token_error_rate` | Edit distance divided by truth length, capped at 1. Failures count as 1. |
| `latency_p50_s`, `latency_p95_s` | Per-sample recognition time **on the laptop**. This is not an Android figure. |
| `in_seshat_vocabulary` block | The same metrics restricted to expressions that use only Seshat's 101 symbols. |

# ADR-0003: Handwriting-to-LaTeX recognizer

- **Status:** Accepted, with its training-data question still open
- **Date:** 2026-10-27
- **Amended:** 2026-10-28, 2026-10-31

## Context

Recognition must be free and offline after setup, with code, weights and data compatible with the project's licence. Phase 3 evaluated Seshat, TexTeller and pix2tex on 500 MathWriting test samples, and ran TexTeller int8 on the Android emulator. MathWriting (CC BY-NC-SA) was used locally for evaluation only, and is never in this repository.

## Decision

1. **The recognizer sits behind an interface,** taking strokes and returning LaTeX, with errors and cancellation. The model can be replaced without touching the UI or storage.
2. **The runtime is ONNX Runtime Android** (MIT), verified bit-identical to a desktop run on the emulator.
3. **The reference model is TexTeller 3.0.** Its int8 dynamic-quantized version (303 MB) scored 63% exact and 74% within one token on MathWriting.
4. **This project doesn't ship or host the model.** The app downloads TexTeller's own ONNX files from its authors when the user asks, at a pinned commit, after showing where it comes from and what is unsettled. After that it runs fully offline.

**Rejected:**

- **Seshat:** 6.8% exact, p95 of 41 s on a laptop, a 101-symbol vocabulary, and unmaintained since 2015.
- **pix2tex:** a printed-formula model, with 0% on handwriting.
- **ML Kit:** proprietary, with no math recognition.

## Open question

TexTeller's code and weights are tagged Apache-2.0, but upstream says its handwritten subset is "collected from existing open-source handwritten datasets", without saying which or under what licences. It may include test sets, which would inflate its scores, and non-commercial datasets.

Downloading from the authors means this project isn't redistributing the model, but that doesn't settle the question for anyone using it, so the app says so. A smaller model could ship only if the authors confirm its data, or one is trained on data with clear terms. MathWriting's licence is not an obvious basis for that.

## Consequences

- Maths recognition needs a download of about 1.25 GB and about 1.7 GB of memory, so tablets under 6 GB are warned first and load it only when needed ([model setup](../guide/MODEL_SETUP.md)).
- Without the model, the app still works, and LaTeX can be typed by hand.
- Kotlin holds TexTeller's preprocessing, KV-cache greedy decoding, tokenizer decoding and LaTeX post-processing, checked against Python fixtures.
- For local testing, a build flag packs the int8 model into a debug or release APK. The files come from outside the repository, the flag is refused for a Play bundle, and such an APK is never published.
- Real-tablet latency and memory stay to be measured (HW-8 in [Testing](../development/TESTING.md)).

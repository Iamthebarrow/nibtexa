# The math recognition model

NibTeXa reads handwritten maths with [TexTeller 3.0](https://github.com/OleehyO/TexTeller), run through ONNX Runtime. **This project doesn't include or host the model.** Its code and weights are labelled Apache-2.0 by its authors, but they describe its handwritten training data only as collected from existing open-source datasets, without stating their licences. So it comes from them, not from this project, and that question stays open ([ADR-0003](../decisions/ADR-0003-recognizer.md)).

Without the model the app still works: writing, lasso, regions, typing the LaTeX by hand in the correction panel, saving and export.

## Downloading it in the app

More → Settings → **Download math recognition…**. It's about 1.25 GB, needs more room than that while it comes down, and goes on where it stopped if interrupted. The files are the authors' own, at a pinned commit, checked against their SHA-256.

**What it costs,** measured on an emulator with 6 GB of memory: the app's memory goes from about 150 MB to about 1.7 GB, loading takes about 10 s, and a recognition about 1.3 s on a host computer's CPU. A real tablet will be slower. With less than 6 GB of memory, the download warns first, and the app waits to load the model until the first conversion. Tablets with 4 GB may struggle.

## Making a smaller version yourself

A quantized (int8) version is a quarter of the size, at the accuracy in ADR-0003. You make it on a computer, as described in [tools/recognition-eval/README.md](../../tools/recognition-eval/README.md). The app reads three files from its model folder:

| File | Size | Source |
|---|---|---|
| `encoder_model.onnx` | 85 MB | `quantize_texteller.py` output |
| `decoder_model_merged.onnx` | 219 MB | same |
| `vocab.json` | 144 KB | TexTeller's tokenizer, copied by the same script |

**To load it in the app:** More → Settings → **Load recognition model…**, then pick the folder holding the three files. They're copied and checked before the current model is replaced.

**To load it with adb,** from Git Bash with the app installed:

```sh
MODEL=<folder with the three files>
DEST=/sdcard/Android/data/io.github.iamthebarrow.nibtexa/files/models/texteller-int8
MSYS_NO_PATHCONV=1 adb shell mkdir -p $DEST
for f in encoder_model.onnx decoder_model_merged.onnx vocab.json; do
  MSYS_NO_PATHCONV=1 adb push $MODEL/$f $DEST/
done
```

That folder is app-specific storage, so no permission is needed, and it's removed when the app is uninstalled.

Don't share an APK or a copy of the model you've made this way: the training-data question above applies to you too.

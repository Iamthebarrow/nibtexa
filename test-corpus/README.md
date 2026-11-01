# NibTeXa handwriting corpus

Handwriting samples with ground-truth LaTeX, used to evaluate recognizers (Phase 3)
and as regression fixtures afterwards.

## License and provenance

Every sample in this directory must be **written by a project contributor using
NibTeXa's corpus capture mode**, or generated synthetically by project code. Samples
are distributed under the repository's license (GPL-3.0).

Do **not** add:

- third-party datasets (CROHME, MathWriting, IAM and others), whose terms are unclear
  or non-commercial;
- anything containing personal information. The `device` annotation records only the
  manufacturer, model and API level.

## Layout

```
test-corpus/
  README.md
  raw/        captured samples, one pair of files per sample (added in Phase 3)
```

Each sample is two files with the same base name, `<prompt-id>_<UTC timestamp>`:

- `.inkml`: W3C InkML in the CROHME subset. Channels are X, Y and T (ms from the
  sample's first point), plus F (pressure 0..1) when every point has pressure.
  Annotations: `truth` (LaTeX in `$...$`), `UI` (the base name), `category`, `device`.
- `.json`: the project's native `nibtexa-ink` format, version 1. It is lossless and
  includes tilt and orientation when the device reports them.

Prompts come from `app/src/main/assets/corpus/prompts.tsv`
(`id<TAB>category<TAB>latex`).

## Capturing samples

1. Run the app. The canvas shows a prompt bar.
2. Write the prompt with a stylus. On the emulator, use the mouse with **Draw with
   finger** turned on, or inject strokes with `adb shell input stylus swipe ...`.
3. Tap **Save sample**. The page clears and the next prompt appears.
4. Pull the samples (from Git Bash, `MSYS_NO_PATHCONV=1` stops path rewriting):

   ```sh
   MSYS_NO_PATHCONV=1 adb pull /sdcard/Android/data/io.github.iamthebarrow.nibtexa/files/corpus test-corpus/raw
   ```

## Caveats

- Emulator samples have constant pressure and tilt: `adb input stylus` reports
  pressure 1.0 and tilt 0. Mouse and finger samples have no pressure. These samples
  are fine for geometry and timing, but not for pressure-dependent analysis. That
  needs real hardware (see HW-1 and HW-2 in `docs/development/TESTING.md`).
- Mouse writing is much less natural than a stylus. Record which tool was used and
  treat emulator samples as provisional.

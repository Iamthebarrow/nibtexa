# Testing

## Commands

Run from the repository root. On Windows, use Git Bash.

```sh
# Build, JVM tests and lint: this is what CI runs
./gradlew assembleDebug :core:maths:test :core:model:test :core:export:test :core:recognition:test :core:sync:test testDebugUnitTest lintDebug

# Device tests, with an emulator running
./gradlew connectedDebugAndroidTest

# Device tests on a release build
./gradlew :app:connectedReleaseAndroidTest -Pnibtexa.testRelease=true -Pnibtexa.releaseAbi=x86_64
```

Reports are in `app/build/reports/` and `app/build/outputs/androidTest-results/`.

**A Gradle connected run uninstalls the app,** which wipes its data, model and saved token. To keep them, install the app and test APKs, and run a class at a time:

```sh
adb shell am instrument -w -e class io.github.iamthebarrow.nibtexa.notebook.EraserDeviceTest \
  io.github.iamthebarrow.nibtexa.test/androidx.test.runner.AndroidJUnitRunner
```

`SyncTest`, `IntegratedTrialTest` and `RealHostSyncTest` clear the saved sync settings and token, or use a real Git host, so leave them out unless that's what you want.

## The emulator

The reference emulator is a Pixel Tablet (2560×1600) on Android 17 (API 37), Google APIs, x86_64, named `NibTeXa_Tablet_API37`, with 6144 MB of RAM. With 4 GB, long sessions with the real model hung it.

In Android Studio: **Device Manager → Create Virtual Device → Tablet → Pixel Tablet**, then API 37 Google APIs x86_64, landscape, 6144 MB of RAM. Or from the command line:

```sh
SDK=<your Android SDK>
"$SDK/cmdline-tools/latest/bin/android" sdk install system-images/android-37.0/google_apis/x86_64
"$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n NibTeXa_Tablet_API37 \
  -k "system-images;android-37.0;google_apis;x86_64" -d pixel_tablet
"$SDK/emulator/emulator" -avd NibTeXa_Tablet_API37
```

For long runs, start it with `-gpu swiftshader_indirect`, as its freezes come from host-GPU graphics. If `adb shell` stops answering, cold-boot with `-no-snapshot-load`. Repeated rebuilds can bloat the debug APK, so clear `app/build/outputs/apk/debug` and `app/build/intermediates/incremental/packageDebug` if it looks large.

## Writing device tests

- **Pen input:** `TestMotionEvents` builds `MotionEvent`s with pressure, tilt and button state, for injecting into the surface. Through the real input pipeline, use `adb shell input stylus swipe x1 y1 x2 y2 ms`, where pressure is always 1.0.
- **Test setup:** `TestServicesRule` gives fresh services and keeps your settings. It turns automatic regions, margins and Android clipboard copying off unless a test asks, and starts in a booklet unless `startInLibrary = true`.
- **Timing:** let the screen update after `setTool` before writing, as the surface takes its tool then. Maths capture waits on the test clock, so advance it. Let the clock run before a screenshot, or it shows the page before it redraws.
- **Orientation:** a run can stay in the last orientation, so check `cur=` in `adb shell dumpsys window displays`. `WalkthroughTest` covers both.
- **Process death:** `am kill` doesn't kill a just-backgrounded app. Use `adb shell run-as io.github.iamthebarrow.nibtexa kill -9 <pid>`.
- **Keys in the math editor:** MathLive navigates by key code, so tests inject scan-coded `KeyEvent`s (`HardwareKeys.kt`); plain `input keyevent` won't move the caret.
- **Git Bash:** prefix `adb` commands that use device paths with `MSYS_NO_PATHCONV=1`.

## Optional test data

- **Git sync on the emulator** needs the Docker remote and `adb reverse tcp:18080 tcp:18080` ([tools/git-test-remote](../../tools/git-test-remote/README.md)). Without it those tests skip.
- **Recognition tests** use fixtures in `/data/local/tmp/nibtexa-spike/` and `nibtexa-e2e/`, made by `tools/recognition-eval`, and skip without them. Handwriting datasets stay outside the repository.
- **The tokenizer check** runs when `NIBTEXA_TEXTELLER_DIR` points at a model folder.

## Compiling exported LaTeX

Exports are compiled with a pinned TeX Live image, offline:

```sh
docker run --rm --network none -v "<dir>:/work" -w /work \
  texlive/texlive@sha256:2ecb65b3dea70e1ff0549060cbca6ac331429029984cfd05c9561372a8f1d073 \
  pdflatex -no-shell-escape main.tex
```

That image has no `pgfplots`. For a graph's export, use a local copy with it added (`tlmgr install pgfplots`, then `docker commit`).

## Secrets scan

See [tools/secrets-scan](../../tools/secrets-scan/README.md).

## What needs real hardware

Emulator results are never reported as hardware results. These can't be checked on an emulator, and each stays unverified until it's been tested on real hardware:

| ID | Behaviour | Acceptance | Needs |
|---|---|---|---|
| HW-1 | Pressure | Stroke width follows pressure, with no clipping at the extremes | Active stylus |
| HW-2 | Tilt and orientation | Recorded when reported; works without them | Stylus with tilt |
| HW-3 | Palm rejection | No stray strokes in 5 minutes of writing with the palm resting | Tablet and stylus |
| HW-4 | Latency | Feels acceptable; measured with high-speed video if possible | Tablet and stylus |
| HW-5 | Accidental finger touch | A finger pans or zooms and doesn't ink | Tablet |
| HW-6 | Hover and pen buttons | Each button action and the eraser's hover ring work as reported | Stylus with buttons |
| HW-7 | Eraser end | Switches to erasing | Stylus with an eraser end |
| HW-8 | Recognition | Latency and memory acceptable | Mid-range tablet |
| HW-9 | Battery and heat | No sustained throttling in 30 minutes | Tablet |
| HW-10 | System gestures | Edge gestures don't conflict with ink | Tablet |
| HW-11 | Math editor keys | A physical keyboard moves the caret through bounds and scripts | Tablet and keyboard |
| HW-12 | Math editor access | Stylus taps place the caret, and TalkBack reads the equation | Tablet and stylus |
| HW-13 | Canvas recreation | Rotating or resizing 50 times doesn't crash | Tablet |
| HW-14 | Live erasing | Stays smooth on a slower tablet, on a page with a lot of ink | Mid-range tablet |

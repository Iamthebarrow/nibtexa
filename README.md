# NibTeXa

_Handwritten notes. LaTeX ready. Repository synced._

NibTeXa is a notebook for writing by hand on an Android tablet, made for maths, science and engineering notes. Your handwriting becomes editable LaTeX, and your notes can be exported or pushed to a Git repository.

![The open notebook](fastlane/metadata/android/en-US/images/tenInchScreenshots/1.png)

## What it does

- **Write** with math, text, drawing and scratch pens, on blank, ruled or grid paper, in several page sizes.
- **Convert** handwriting to LaTeX and text on the tablet, and check each result in a rendered editor.
- **Draw** clean block diagrams, signal flow graphs and circuits, and graph your equations on the page.
- **Export** PDF, LaTeX and Markdown. **Import** LaTeX, Markdown, and PDFs to write on.
- **Keep it safe** with automatic backups to a folder you choose, and commit and push to any Git server you set up.

There are no accounts or ads. See the [feature guide](docs/guide/FEATURES.md) for how it all works.

## Reading your handwriting

- **Words** use Google ML Kit on the tablet, after a language download of about 20 MB. It sends Google usage metrics, as the [privacy page](docs/PRIVACY.md) explains.
- **Maths** uses TexTeller, a separate model that doesn't come with the app. Settings can download it from its authors, about 1.25 GB. It uses about 1.7 GB of memory while running, so a tablet with 6 GB or more is best. See [model setup](docs/guide/MODEL_SETUP.md).

Everything except these downloads, Git sync and ML Kit's metrics works offline.

## Status

Early. Most testing is automated, on an emulator, with basic use tried on one Samsung tablet with an S Pen. It runs on 64-bit ARM tablets with Android 8.0 or newer, and is made for a stylus.

## Install

Build it yourself and install it by following [Installing NibTeXa](docs/guide/INSTALLING.md).

## Build

You need Android Studio or the Android SDK with compile platform 37, and any JDK 17 or newer to launch Gradle. The build itself runs on JDK 21, which Gradle downloads if needed.

```sh
./gradlew assembleDebug   # build the debug APK
./gradlew installDebug    # install it on a running emulator or a connected tablet
```

On Windows, run `gradlew.bat` or use Git Bash. [Testing](docs/development/TESTING.md) has the test commands and emulator setup.

## Documentation

The [documentation index](docs/README.md) lists everything: the feature guide, architecture, design notes and decision records. Also see [CONTRIBUTING.md](CONTRIBUTING.md) and [SECURITY.md](SECURITY.md).

## License

Copyright © 2026 Ian Barrow. NibTeXa is free software under the GNU General Public License, version 3, with an additional permission for Google ML Kit: see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md). It comes with no warranty. The components it includes keep their own licences, listed in [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md).

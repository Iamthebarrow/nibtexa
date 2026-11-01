# CLAUDE.md

Guidance for AI coding agents working in this repository.

NibTeXa is a tablet-first Android app for handwritten notes that become LaTeX, with optional Git sync. It is GPL-3.0 with a section 7 permission for ML Kit (`NOTICE.md`).

Start with [docs/README.md](docs/README.md), which indexes everything. [Architecture](docs/development/ARCHITECTURE.md) has the modules, and [Testing](docs/development/TESTING.md) has device test advice. Work out the repository's state from the files, not from memory.

## Working rules

- Do only the scope requested: planning, one phase, a bug fix, review or release work. Don't start the next phase without approval.
- Present a short proposal before implementing a phase. End with what changed, decisions made, test evidence, risks, and the next action.
- **Don't commit, push, merge, branch or otherwise change Git history.** The maintainer handles all Git operations. Leave changes in the working tree, and don't delete repository content without explicit authorization.
- Never add secrets, credentials, local machine paths, personal details or build outputs to the repository.
- Report test results as they actually ran: the command, the environment and pass, fail or skip. Keep unit-tested, emulator-tested, manually reviewed and hardware-validated as separate claims.
- The core note and recognition workflow must work offline. Get approval before adding paid services, mandatory cloud processing or licence-incompatible dependencies. Every dependency, model weight and dataset must fit the licence, and go in `docs/THIRD_PARTY.md`.
- Third-party datasets and per-sample results stay outside the repository. The math model is never shipped or hosted.
- Prefer Kotlin and Android-supported components. Add native code only for a measured, documented need.
- Any change to the math editor's WebView or bridge must keep `docs/design/MATH_BRIDGE.md` true.
- A change that adds stored data must extend `core/export/Backup.kt` and raise its `VERSION`, keeping older versions readable.
- Add a line to `docs/CHANGELOG.md` for each phase, and update the guide and design documents it affects.
- `docs/` is also the website (`zensical.toml`). Add new pages to its `nav`, and use full GitHub URLs for links that leave `docs/`.

## Commands

Run from the repository root in Git Bash. Gradle runs its daemon on JDK 21, selected through `gradle/gradle-daemon-jvm.properties`.

```sh
./gradlew assembleDebug :core:model:test :core:export:test :core:recognition:test :core:sync:test :core:maths:test testDebugUnitTest lintDebug
./gradlew installDebug -Pnibtexa.personal=true   # a local test build with the model packed in; never publish it
./gradlew bundleRelease                          # the bundle for Google Play
```

- Run device tests a class at a time with `am instrument`, as a Gradle connected run uninstalls the app and its data. Ask before running a whole connected suite, or `SyncTest`, `IntegratedTrialTest` or `RealHostSyncTest`.
- Screenshot before tapping or typing on a shared emulator or tablet.
- Write files containing LaTeX or backslashes with the file tools, not shell heredocs: Git Bash collapses a double backslash.

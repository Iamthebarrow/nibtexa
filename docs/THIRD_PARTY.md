# Third-party components

What NibTeXa ships or downloads, and under which terms. The project is
GPL-3.0, with the additional permission in [NOTICE.md](https://github.com/Iamthebarrow/nibtexa/blob/main/NOTICE.md). Inventory of 2026-10-31, taken from the `:app`
`releaseRuntimeClasspath` (`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`)
and the vendored assets.

Status labels:

- **Verified:** read in the artifact's POM or bundled licence file on the
  date above.
- **Family:** the AndroidX and Kotlin libraries, whose POMs declare Apache-2.0.
  These were spot-checked, not all read one by one.

## In the APK

| Component | Version | Licence | How it ships | Status |
|---|---|---|---|---|
| AndroidX (activity, annotation, autofill, collection, Compose runtime/ui/foundation/animation/material/material3, concurrent, core, customview, emoji2, fragment, graphics, interpolator, lifecycle, loader, navigationevent, profileinstaller, room, savedstate, sqlite, startup, tracing, versionedparcelable, viewpager, webkit, window) | BOM 2026.09.00, Room 2.8.5, webkit 1.18.0 | Apache-2.0 | Java and Kotlin bytecode | Family |
| androidx.ink (authoring, brush, geometry, nativeloader, rendering, strokes) | 1.1.0-alpha10 | Apache-2.0 | Bytecode and native libraries per ABI | Family |
| `androidx.test.espresso:espresso-idling-resource` | 3.5.0 | Apache-2.0 | A small runtime library that androidx.ink-authoring depends on. It is not test code from this project. | Family |
| `com.google.guava:listenablefuture` | 1.0 | Apache-2.0 | An empty marker artifact | Family |
| Kotlin standard library, kotlinx-coroutines | Kotlin 2.4.21 | Apache-2.0 | Bytecode | Family |
| kotlinx-serialization (core, json) | 1.12.0 | Apache-2.0 | Bytecode | Verified (POM) |
| `org.jetbrains:annotations` | 23.0.0 | Apache-2.0 | Annotations | Verified (POM) |
| `org.jspecify:jspecify` | 1.0.0 | Apache-2.0 | Annotations | Verified (POM) |
| ONNX Runtime Android | 1.31.1 | MIT | Bytecode and native libraries (24–41 MB per ABI). The AAR contains **no** licence or notice file (checked 2026-10-27). The native code includes third-party components whose notices are in the ONNX Runtime source repository (`ThirdPartyNotices.txt` for the 1.31.1 tag); they must be collected for the release notices (Phase 10). | Verified (POM); notices open |
| JGit | 7.8.0.202609011348-r | EDL 1.0 (BSD-3-Clause) | Bytecode | Verified (parent POM, ADR-0006) |
| JavaEWAH | 1.2.3 | Apache-2.0 | Bytecode (JGit dependency) | Verified (POM) |
| slf4j-api | 2.0.18 | MIT | Bytecode (JGit dependency). No logging provider is shipped, so its output goes nowhere. | Verified (bundled `LICENSE.txt`) |
| commons-codec | 1.22.1 | Apache-2.0 | Bytecode (JGit dependency) | Verified (bundled `LICENSE.txt`) |
| MathLive, with the KaTeX fonts it bundles | 0.111.1 | MIT | Vendored web assets, hash-pinned in [feature/mathedit/VENDORED.md](https://github.com/Iamthebarrow/nibtexa/blob/main/feature/mathedit/VENDORED.md) | Verified |
| Nunito (variable font), the app's typeface | Google Fonts `main`, fetched 2026-10-28 | SIL Open Font License 1.1 | `res/font/nunito.ttf`, unmodified, hash in [third_party/README.md](https://github.com/Iamthebarrow/nibtexa/blob/main/third_party/README.md). The OFL lets the font ship inside other software, provided the font is not sold on its own and the licence travels with it (it is on the licences screen). | Verified (bundled `OFL.txt`) |
| Baloo 2 (variable font), the NibTeXa title | Google Fonts `main`, fetched 2026-10-29 | SIL Open Font License 1.1 | `res/font/baloo2.ttf`, unmodified, hash in [third_party/README.md](https://github.com/Iamthebarrow/nibtexa/blob/main/third_party/README.md); same terms as Nunito, licence on the licences screen. | Verified (bundled `OFL.txt`) |

All of these are permissive licences compatible with GPL-3.0. Apache-2.0
is compatible with GPL-3.0 (not with GPL-2.0-only).

### Proprietary: Google ML Kit (ADR-0007)

**Google ML Kit Digital Ink Recognition is proprietary.** NibTeXa is distributed with it under the additional permission in [NOTICE.md](https://github.com/Iamthebarrow/nibtexa/blob/main/NOTICE.md). POM licences were read on 2026-10-28 from
the Gradle cache.

| Component | Version | Licence | How it ships | Status |
|---|---|---|---|---|
| `com.google.mlkit:digital-ink-recognition` | 19.0.0 | ML Kit Terms of Service (under the Google APIs Terms of Service): proprietary, no reverse engineering or model extraction | Bytecode and a native library (`libdigitalink.so`) | Verified (POM) |
| `com.google.mlkit:common`, `digital-ink-common` | 18.11.0, 16.0.0 | ML Kit Terms of Service | Bytecode | Verified (POM) |
| `com.google.android.gms:play-services-base`, `-basement`, `-tasks` | 18.5.0, 18.4.0, 18.2.0 | Android Software Development Kit License: proprietary | Bytecode (client libraries) | Verified (POM) |
| Google datatransport (`transport-api`, `-backend-cct`, `-runtime`) | 2.2.1, 2.3.3, 2.2.6 | Apache-2.0 | Bytecode. It carries ML Kit's usage metrics to Google ([PRIVACY.md](PRIVACY.md)). | Verified (POM) |
| Firebase components and encoders (`firebase-components`, `firebase-encoders`, `-json`) | 16.1.0, 16.1.0, 17.1.0 | Apache-2.0 | Bytecode | Verified (POM) |
| WorkManager (`androidx.work:work-runtime`) | 2.7.0 | Apache-2.0 | Bytecode | Verified (POM) |
| OkHttp | 3.12.1 | Apache-2.0 (parent POM) | Bytecode. An old release (2018), pulled in by ML Kit. NibTeXa does not use it directly. | Verified (parent POM) |
| Okio | 2.0.0 | Apache-2.0 | Bytecode | Verified (POM) |

The English handwriting pack (about 20 MB, reported) is **not in the APK**.
ML Kit downloads it into app storage when the user asks, under the same ML
Kit terms.

## Not in the APK

| Component | Licence | Where | Status |
|---|---|---|---|
| TexTeller 3.0 int8 model (ONNX), 303 MB | The code and weights are tagged Apache-2.0 upstream. Licences of its handwritten training sources aren't stated (ADR-0003). | **Never distributed by this project.** The app downloads the authors' full version when the user asks (about 1.25 GB, pinned by SHA-256). | Upstream checked 2026-10-31 |
| MathWriting dataset | CC BY-NC-SA 4.0 | `../nibtexa-eval-data/`, outside the repo, used for evaluation only | Verified |
| TeX Live Docker image (`texlive/texlive@sha256:2ecb65b3…`) | Various free licences | Developer machine; compiles exports in tests | Tool only |
| Git test remote (Alpine 3.22, git 2.49.1 GPL-2.0, lighttpd 1.4.85 BSD-3-Clause) | As listed | `tools/git-test-remote/`, developer machine | Tool only |
| Seshat build image | GPL-3.0 | Developer machine, recognizer evaluation | Tool only |
| gitleaks (secrets scan) | MIT | Developer machine | Tool only |

## Test-only dependencies (not shipped)

JUnit 4.13.2 (EPL-1.0), AndroidX Test and Espresso (Apache-2.0), Compose UI
Test with the Accessibility Test Framework (Apache-2.0), and Room testing
(Apache-2.0).

## Before a release

Re-run this inventory. See [Releasing](development/RELEASING.md).

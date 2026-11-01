# ADR-0001: Toolchain, SDK levels and project identity

- **Status:** Accepted
- **Date:** 2026-10-26

## Context

The project needed a reproducible Android build on Windows, with no
dependency on machine-specific paths. Two facts shaped the choices:

- `JAVA_HOME` on the developer machine is JDK 25.
- The bundled Android Studio JBR has no `java.exe`.

Versions were taken from the Google Maven and Maven Central metadata, and
from developer.android.com, on 2026-10-26.

## Decision

| Item | Choice | Reason |
|---|---|---|
| AGP | 9.4.1 | Latest stable release. 9.5 and 9.6 are previews. |
| Gradle wrapper | 9.8.2, pinned by SHA-256 | AGP 9.4 needs Gradle 9.6.0 or newer. Lint recommends the latest. The checksum protects the supply chain. |
| Gradle daemon JDK | 21, via `gradle/gradle-daemon-jvm.properties` and the foojay resolver | Makes the build independent of `JAVA_HOME` and the Studio JBR. Gradle downloads JDK 21 if it isn't present. |
| Kotlin | 2.4.21 (Compose compiler plugin) | Latest stable release. AGP 9 provides built-in Kotlin support, so the `kotlin-android` plugin isn't applied. |
| Java bytecode target | 17 | Matches AGP 9's baseline. |
| Compose BOM | 2026.09.00 | Latest. |
| compileSdk | 37 | Required by current AndroidX (core 1.19, Compose UI 1.12). |
| targetSdk | 37 | Lint `OldTargetApi`. Keeps Play target-API policy in view. |
| **minSdk** | **26** (Android 8.0) | Reasons below. |
| Application ID / namespace | `io.github.iamthebarrow.nibtexa` | Reverse-DNS of the project's GitHub Pages domain. It is permanent once published. |
| Primary AVD | `NibTeXa_Tablet_API37`: Pixel Tablet, API 37 Google APIs x86_64, 4 GB RAM (6 GB since Phase 8), host GPU, hardware keyboard | Matches targetSdk. x86_64 with WHPX gives near-native speed. |

Reasons for minSdk 26:

- `androidx.ink` 1.0.0 needs API 23 and ONNX Runtime Android 1.31.1 needs
  API 24. Both were checked in the AAR manifests.
- JGit 7.8 needs Java 17 APIs. API 26 is the earliest release with the
  `java.nio.file` APIs that JGit relies on. Its full suitability is
  verified in Phase 7.
- API 26 also removes the need for legacy launcher icon rasters.

## Consequences

- AGP installed SDK platform `android-37.0` on first build. `cmdline-tools`
  (the new `android` CLI 1.1) and the API 37 system image were installed
  into the developer's SDK during Phase 1. `sdkmanager` now delegates to
  `android sdk`, which uses slash-separated package names, for example
  `system-images/android-37.0/google_apis/x86_64`.
- Cloud backup and device transfer are disabled for now
  (`data_extraction_rules.xml`). This is revisited in Phase 5 (storage) and
  Phase 7 (credentials).
- If a later dependency raises minSdk, record a new ADR.

## Alternatives considered

- **minSdk 24:** would cover more devices, but blocks the `java.nio.file`
  APIs JGit needs. Pre-API-26 tablets are rare among stylus devices.
- **minSdk 29 or higher:** no requirement for it yet.
- **compileSdk 36:** rejected because current AndroidX libraries refuse to
  compile against it.
- **Running Gradle on JDK 25 (`JAVA_HOME`):** it works today, but isn't
  reproducible for other contributors or CI.

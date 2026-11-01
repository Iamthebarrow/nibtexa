# Releasing NibTeXa

How to publish on GitHub and Google Play.

## What is published

- **The licence:** GPL-3.0, with the additional permission in [NOTICE.md](../../NOTICE.md) that lets the app be distributed with Google ML Kit.
- **Reading handwritten words:** Google ML Kit, in the app. It sends usage metrics to Google, which [PRIVACY.md](../PRIVACY.md) discloses.
- **Reading handwritten maths:** the TexTeller model is **not in the app**, and this project doesn't host it. The app downloads it from its authors when the user asks, after showing where it comes from and what is unsettled about its training data ([ADR-0003](../decisions/ADR-0003-recognizer.md)).
- **The source:** GPL-3.0 requires the source, with the build scripts, for as long as you distribute a binary. A tag for each release does this.

## A test build with the model packed in

The normal build has no model. To test without downloading it:

```sh
./gradlew installDebug -Pnibtexa.personal=true       # the emulator, or a tablet with USB debugging on
./gradlew assembleRelease -Pnibtexa.personal=true    # a signed APK to copy to a tablet
```

The files come from `../nibtexa-eval-data/texteller-int8/`, or `-Pnibtexa.modelDir=<folder>`. **Never publish or share an APK made with this flag,** as it contains the model. A bundle for Google Play refuses the flag.

## Before each release

```sh
./gradlew check bundleRelease assembleRelease

# Must print 0: no model in what you publish
unzip -l app/build/outputs/bundle/release/app-release.aab | grep -c '\.onnx'
unzip -l app/build/outputs/apk/release/app-release.apk | grep -c '\.onnx'

# Must print nothing: no signing details are tracked
git ls-files | grep -i keystore
```

Then run the [secrets scan](../../tools/secrets-scan/README.md), and:

- [ ] `docs/THIRD_PARTY.md` matches `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`.
- [ ] `versionCode` and `versionName` raised in `app/build.gradle.kts`, with a new `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
- [ ] Checked on a real tablet.
- [ ] The commit tagged (`git tag v0.1.0`) and pushed, and the APK attached to a GitHub release from that tag.

## Signing

Create the key as in [Installing](../guide/INSTALLING.md), and keep it and its password somewhere other than the build computer. Lose it, and you can't update what people have installed from GitHub. For Google Play, use it as your upload key, with Play App Signing.

## Google Play

The app already meets these technical rules, checked 2026-10-31: it targets API 37 ([36 or higher is required](https://support.google.com/googleplay/android-developer/answer/11926878)), its native libraries are aligned for 16 KB pages, and it's well under the [500 MB limit](https://support.google.com/googleplay/android-developer/answer/9859372). `bundleRelease` builds the bundle, for 64-bit ARM tablets.

In the Play Console:

1. **Create a developer account,** and complete its identity checks.
2. **Create the app,** with the ID `io.github.iamthebarrow.nibtexa`, which can't be changed afterwards.
3. **The store listing:** text and graphics are in `fastlane/metadata/android/en-US/`. Add a support email.
4. **Privacy policy:** a link to [PRIVACY.md](../PRIVACY.md) on GitHub.
5. **Data safety:**
   - **The developer** receives nothing: no accounts, analytics or ads.
   - **ML Kit in the app** sends Google the usage metrics listed in [PRIVACY.md](../PRIVACY.md), so declare them, following [Google's disclosure](https://developers.google.com/ml-kit/android-data-disclosure).
   - **Handwriting** stays on the device, and Git sync goes only where the user chooses.
6. **Content rating, target audience and app access:** the questionnaires. Nothing needs a login, and there are no ads.
7. **Foreground service:** ML Kit's WorkManager brings the `FOREGROUND_SERVICE` permission. If the Console asks about it, the app itself runs no foreground service.
8. **Devices:** consider excluding phones, as the app is made and tested for tablets.
9. **The closed test:** a [personal account made after November 13, 2023 needs at least 12 testers, opted in continuously for 14 days](https://support.google.com/googleplay/android-developer/answer/14151465), before applying for production.

Play's policies on an app downloading a model weren't checked in full. The model is data, not code, but read the current wording when you submit.

### Android developer verification

[Developer verification](https://developer.android.com/developer-verification) is due to expand worldwide in 2027. Play registers most apps by itself. APKs from GitHub may be affected on certified devices, so check the rules when it arrives, and register the app.

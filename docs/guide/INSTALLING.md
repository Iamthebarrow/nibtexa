# Installing NibTeXa

This guide builds a release APK signed with your own key, and installs it on an Android tablet with Android 8.0 or later (64-bit ARM, which covers every current tablet).

## 1. Create your signing key (once)

Android only installs an update over an existing app if both are signed with the same key. Keep the key and its password safe and **outside the repository**. Lose the key and you can't update; you'd have to uninstall, which deletes your notes.

In Git Bash, from the repository root:

```sh
mkdir -p ../nibtexa-signing
keytool -genkeypair -keystore ../nibtexa-signing/nibtexa-release.jks \
  -alias nibtexa -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Your Name"
```

`keytool` comes with the JDK. It asks for a password; use the same one for the key when asked.

Then create `keystore.properties` in the repository root. It's git-ignored:

```properties
storeFile=../nibtexa-signing/nibtexa-release.jks
storePassword=YOUR_PASSWORD
keyAlias=nibtexa
keyPassword=YOUR_PASSWORD
```

Instead of the file, you can set `NIBTEXA_STORE_FILE`, `NIBTEXA_STORE_PASSWORD`, `NIBTEXA_KEY_ALIAS` and `NIBTEXA_KEY_PASSWORD`.

## 2. Build the APK

```sh
./gradlew assembleRelease
```

The result is `app/build/outputs/apk/release/app-release.apk`, about 50 MB. Check that it's signed with your key (the path is the Android SDK's):

```sh
<sdk>/build-tools/37.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

To try a release build on an x86_64 emulator: `./gradlew assembleRelease -Pnibtexa.releaseAbi=x86_64`.

## 3. Install it

- **With a USB cable:** turn on Developer options and USB debugging on the tablet, plug it in, then run `adb install -r app/build/outputs/apk/release/app-release.apk`.
- **With no cable:** copy the APK to the tablet and open it there. Android asks you to allow "Install unknown apps" for the app you opened it from. Allow it for that app only.

## 4. Set up recognition

- **Words:** the app downloads the English handwriting language, about 20 MB, when it opens. On mobile data it asks first, and Settings can download it too.
- **Maths:** More → Settings → **Download math recognition…**. It's about 1.25 GB, so use Wi-Fi. See [model setup](MODEL_SETUP.md).

Ink then groups into regions by itself after you pause. Turn this off with **Group ink into regions automatically** in Settings.

## 5. Updating

Build again with the same key, and install over the old app with `adb install -r`, or by opening the new APK. Notes, settings and the model are kept.

A debug build, your own signed APK and a Google Play version are signed differently, so Android won't install one over another. To change between them: back up, uninstall, install, then restore.

## 6. What uninstalling deletes

Everything the app stores is private to it and is removed on uninstall: notes, exports, the model, the handwriting language, and the Git settings and token. Android backup is turned off for the app.

To keep your work, turn on **Back up automatically…** in Settings, or export it or push it with **Sync with Git…** first.

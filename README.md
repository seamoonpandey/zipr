# zipr

Zip **every** photo and video in a folder at once. No 500-file limit, no ads, no tracking.
A tiny Android app (~1 MB) with a Rust core and a Kotlin UI. It runs fine on cheap, old phones (Android 5.0+).

## Download

**[⬇ Download zipr-1.0.0.apk](https://github.com/seamoonpandey/zipr/releases/download/v1.0.0/zipr-1.0.0.apk)**

Or see [all releases](https://github.com/seamoonpandey/zipr/releases/latest).

To install: open the APK on your phone and allow "Install unknown apps" for your browser or file manager when Android asks.

## Why I made this

I wanted to send my photos to another device. The share sheet stopped me at 500 items. Google Drive had the same cap.

So I installed a zipping app from the store to bundle my photos and videos into one file. It threw endless ads at me, the UI was a mess, and it still didn't get the job done.

So I built my own: pick a folder, zip it, share one file. That's it.

## How to use

1. Tap **Choose folder** and pick a folder (e.g. `DCIM/Camera` for all your camera photos and videos).
2. Everything in it, subfolders included, is selected. Filter to **Photos** or **Videos**, or untick what you don't want.
3. Tap **Zip N files** and choose where to save the `.zip`.
4. Tap **Share** to send that one file anywhere.

## Features

- **No file-count limit.** Files are streamed into the zip one at a time, so 10 or 50,000 files work the same.
- **Fast.** Photos and videos are already compressed, so they are stored as-is instead of re-compressed. Other files use fast deflate.
- **Huge files OK.** ZIP64 is used, so videos over 4 GB work.
- **Light.** About 1 MB, no permissions, no internet access, no analytics.
- **Clean UI.** One screen with light and dark mode.

## Build from source

Requirements: Rust (with `rustup`), Android SDK + NDK, and a JDK 17+ (Android Studio's bundled JBR works).

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
./gradlew assembleRelease
```

The Gradle build runs `scripts/build-rust.sh` first. That script compiles `core/` into `libzipr.so` for arm64-v8a, armeabi-v7a and x86_64.
Release signing reads `keystore.properties` at the repo root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`). Without that file the release build is unsigned.

Layout:

- `core/`: Rust zip engine, exposed to Kotlin over JNI (`open` / `add` / `finish` / `abort`)
- `app/`: Kotlin Android app, plain Views with no Compose or AppCompat

## Known limits

- Keep the app open while zipping. Android may stop it if it sits in the background for a long time.
- Android does not let apps pick the root of internal storage or the `Download` folder as a whole. Pick a folder inside it instead.

## License

MIT

#!/usr/bin/env bash
# Builds libzipr.so for all app ABIs into app/src/main/jniLibs/. Callable from anywhere.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MIN_API=21 # must match the app's minSdk

if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  NDK_DIR="${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk"
  ANDROID_NDK_HOME="$(ls -d "$NDK_DIR"/*/ 2>/dev/null | sort -V | tail -n1)"
  ANDROID_NDK_HOME="${ANDROID_NDK_HOME%/}"
  [[ -n "$ANDROID_NDK_HOME" ]] || { echo "No NDK found in $NDK_DIR; set ANDROID_NDK_HOME" >&2; exit 1; }
fi
export ANDROID_NDK_HOME

cd "$ROOT/core"
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -P "$MIN_API" -o "$ROOT/app/src/main/jniLibs" build --release

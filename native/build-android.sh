#!/usr/bin/env bash
# Builds libkinesis_native.so (text extrusion + Whisper) for the Android ABIs and puts them where Gradle packs them:
# app/src/main/jniLibs/<abi>/. Needs: Rust (rustup), cargo-ndk, cmake and the Android NDK (ANDROID_NDK_HOME).
#
#   native/build-android.sh                 # arm64-v8a armeabi-v7a x86_64
#   ABIS="arm64-v8a" native/build-android.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/../app/src/main/jniLibs"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_NDK:-}}}"
API="${ANDROID_API:-26}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "ANDROID_NDK_HOME is not set or not a directory" >&2; exit 1; }

triple_of() {
  case "$1" in
    arm64-v8a) echo aarch64-linux-android ;;
    armeabi-v7a) echo armv7-linux-androideabi ;;
    x86_64) echo x86_64-linux-android ;;
    x86) echo i686-linux-android ;;
    *) echo "unknown ABI $1" >&2; exit 1 ;;
  esac
}
libdir_of() { # directory of libc++_shared.so inside the NDK sysroot
  case "$1" in
    arm64-v8a) echo aarch64-linux-android ;;
    armeabi-v7a) echo arm-linux-androideabi ;;
    x86_64) echo x86_64-linux-android ;;
    x86) echo i686-linux-android ;;
  esac
}

cd "$HERE"
for abi in $ABIS; do
  triple="$(triple_of "$abi")"
  rustup target add "$triple" >/dev/null
  echo "== $abi ($triple)"
  # whisper-rs-sys forwards CMAKE_* variables to CMake: this is CMake's own Android cross-compiling mode.
  CMAKE_SYSTEM_NAME=Android \
  CMAKE_SYSTEM_VERSION="$API" \
  CMAKE_ANDROID_ARCH_ABI="$abi" \
  CMAKE_ANDROID_NDK="$NDK" \
  CMAKE_ANDROID_STL_TYPE=c++_shared \
  cargo ndk --platform "$API" -t "$abi" -o "$OUT" build --release --features whisper
  # Whisper is C++: the shared C++ runtime of the NDK must travel with the library.
  cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/$(libdir_of "$abi")/libc++_shared.so" "$OUT/$abi/"
done
ls -l "$OUT"/*/

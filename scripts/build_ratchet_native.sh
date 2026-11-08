#!/usr/bin/env bash
set -Eeuo pipefail
# Host checks and four API23 Android libraries; no keys or production data.
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
: "${ANDROID_HOME:?Android SDK required}"
lanu_ndk="$ANDROID_HOME/ndk/28.0.13004108"
compiler="$lanu_ndk/toolchains/llvm/prebuilt/linux-x86_64/bin"
[[ -x "$compiler/aarch64-linux-android23-clang" ]]
export RUSTUP_TOOLCHAIN=1.90.0
cargo test --locked --manifest-path native/ratchet/Cargo.toml
cargo build --locked --release --manifest-path native/ratchet/Cargo.toml
java --source 17 native/ratchet/tests/java/com/example/core/messaging/OlmRatchetNative.java \
  "$root/native/ratchet/target/release/liblanu_ratchet.so"
mkdir -p build/ratchet/host
cp native/ratchet/target/release/liblanu_ratchet.so build/ratchet/host/
for plan in \
  'aarch64-linux-android|aarch64-linux-android|arm64-v8a' \
  'armv7-linux-androideabi|armv7a-linux-androideabi|armeabi-v7a' \
  'x86_64-linux-android|x86_64-linux-android|x86_64' \
  'i686-linux-android|i686-linux-android|x86'; do
  IFS='|' read -r triple clang abi <<< "$plan"
  variable="CARGO_TARGET_$(tr '[:lower:]-' '[:upper:]_' <<< "$triple")_LINKER"
  env "$variable=$compiler/${clang}23-clang" \
    RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384' \
    cargo build --locked --release --target "$triple" --manifest-path native/ratchet/Cargo.toml
  mkdir -p "build/ratchet/jniLibs/$abi"
  cp "native/ratchet/target/$triple/release/liblanu_ratchet.so" "build/ratchet/jniLibs/$abi/"
  "$compiler/llvm-readelf" -lW "build/ratchet/jniLibs/$abi/liblanu_ratchet.so" |
    python3 -c 'import sys; load=[line.split()[-1] for line in sys.stdin if line.strip().startswith("LOAD ")]; assert load and all(int(a,16)>=16384 for a in load), "Native ELF page alignment failed"'
  echo "PASS: $abi API23 ratchet JNI built with16KiB ELF alignment"
done

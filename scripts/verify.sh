#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/home/ubuntu/toolchain/jdk-17.0.21+9}"
export ANDROID_HOME="${ANDROID_HOME:-/home/ubuntu/toolchain/android-sdk}"
export QEMU_LD_PREFIX="${QEMU_LD_PREFIX:-/usr/x86_64-linux-gnu}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
mkdir -p verification
./gradlew --no-daemon assembleDebug lintDebug testDebugUnitTest 2>&1 | tee verification/build.log
apk="$PWD/app/build/outputs/apk/debug/app-debug.apk"
test -s "$apk"
{
  printf 'APK: %s\n' "$apk"
  stat -c 'Size: %s bytes' "$apk"
  sha256sum "$apk"
  "$ANDROID_HOME/build-tools/35.0.0/aapt2" dump badging "$apk"
} | tee verification/apk-proof.txt
cp app/build/reports/lint-results-debug.txt verification/lint-results-debug.txt

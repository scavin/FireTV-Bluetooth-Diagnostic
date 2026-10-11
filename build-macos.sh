#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
if ! command -v gradle >/dev/null; then
  echo "Gradle not found. Install Android Studio or use GitHub Actions workflow, or brew install gradle." >&2
  echo "Requires JDK 17 and Android SDK Platform 35." >&2
  exit 1
fi
gradle --no-daemon :app:assembleDebug
echo "APK: $(pwd)/app/build/outputs/apk/debug/app-debug.apk"

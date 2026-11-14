#!/bin/bash
set -e

echo "=== ShopM Verification Suite (CI/Local) ==="

# Set JAVA_HOME to OpenJDK 17 if available
if [ -d "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
fi

echo "[1/3] Running Unit Tests (testDebugUnitTest)..."
./android/gradlew -p android testDebugUnitTest --continue

echo "[2/3] Building Debug APK (assembleDebug)..."
./android/gradlew -p android assembleDebug

echo "[3/3] Building Release APK with R8 / Proguard (assembleRelease)..."
./android/gradlew -p android assembleRelease

echo "=== Verification SUCCESS: All tests passed & both APKs built cleanly ==="

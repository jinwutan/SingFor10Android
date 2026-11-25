#!/bin/bash
# SingFor10Android 本地一键构建 Debug APK
# 前提：已安装 JDK 17 + Android SDK，并配置 ANDROID_HOME / ANDROID_SDK_ROOT
# 用法：bash scripts/build-apk.sh
# 产物：app/build/outputs/apk/debug/app-debug.apk

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "==> [1/3] 环境检查"
java -version
if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  echo "WARNING: 未设置 ANDROID_HOME / ANDROID_SDK_ROOT，AGP 将尝试自动探测 SDK"
fi

echo "==> [2/3] 构建 APK"
if [ -x ./gradlew ]; then
  ./gradlew assembleDebug --no-daemon --stacktrace
else
  gradle assembleDebug --no-daemon --stacktrace
fi

echo "==> [3/3] 完成"
APK="app/build/outputs/apk/debug/app-debug.apk"
ls -lh "$APK"
shasum -a 256 "$APK" 2>/dev/null || sha256sum "$APK"
echo "APK 路径: $ROOT/$APK"

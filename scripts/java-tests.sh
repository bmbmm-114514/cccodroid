#!/bin/sh
# Java 侧的纯逻辑回归（不依赖安卓运行时）：Ed25519 验签向量、特权命令确认规则、硬件密钥认证验链。
# 编译产物放私有缓存目录，不进仓库。用法：sh scripts/java-tests.sh
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${TMPDIR:-$HOME/.cache}/cccodroid-java-tests
rm -rf "$OUT"; mkdir -p "$OUT"
javac -nowarn -d "$OUT" "$ROOT/android/tests/stub/android/util/Base64.java" "$ROOT/android/java/cc/codroid/Ed25519.java" "$ROOT/android/tests/cc/codroid/Ed25519Vectors.java"
java -cp "$OUT" cc.codroid.Ed25519Vectors
javac -nowarn -d "$OUT" "$ROOT/android/java/cc/codroid/PrivRules.java" "$ROOT/android/tests/cc/codroid/PrivRulesTest.java"
java -cp "$OUT" cc.codroid.PrivRulesTest
javac -nowarn -d "$OUT" "$ROOT/android/java/cc/codroid/KeyAttest.java" "$ROOT/android/tests/cc/codroid/KeyAttestTest.java"
java -cp "$OUT" cc.codroid.KeyAttestTest
# Android host helpers use the application's compiled classes and Material/AndroidX resource IDs.
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
AJAR=${ANDROID_JAR:-$SDK/platforms/android-35/android.jar}
if [ -f "$AJAR" ]; then
  sh "$ROOT/scripts/android-gradle.sh" hostClasspath
  CP="$OUT:$AJAR:$(cat "$ROOT/android/gradle-build/host-classpath.txt")"
  for name in WhatsNewPickTest Stab2LHostTest InstallerCarryTest; do
    javac -nowarn -d "$OUT" -cp "$CP" "$ROOT/android/tests/cc/codroid/$name.java"
    java -cp "$CP" "cc.codroid.$name"
  done
else
  echo "跳过 Android 宿主测试（需要 Android SDK platform 35）"
fi

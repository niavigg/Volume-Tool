#!/usr/bin/env bash
#
# VolumeTool 一键构建脚本
# 流程：aapt2 编译链接资源 → javac 编译 → d8 转 dex → 打包 → zipalign → apksigner 签名
#
# 用法：
#   export ANDROID_SDK=/path/to/android-sdk      # 需含 build-tools/34.0.0 和 platforms/android-34
#   export JAVA_HOME=/path/to/jdk                # 需 JDK 11+（留空则用 PATH 里的）
#   ./build.sh
#
# 说明：aapt2 打不开含中文/空格的路径，所以脚本会把 project 拷进
# 系统 ASCII 临时目录里构建，仓库放哪儿都能编译。
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$SCRIPT_DIR/dist"

ANDROID_SDK="${ANDROID_SDK:-$SCRIPT_DIR/../sdk}"
BT="$ANDROID_SDK/build-tools/34.0.0"
AJ="$ANDROID_SDK/platforms/android-34/android.jar"

# Windows 下工具带 .exe 后缀，其他平台没有
EXE=""
[ -f "$BT/aapt2.exe" ] && EXE=".exe"
AAPT2="$BT/aapt2$EXE"
ZIPALIGN="$BT/zipalign$EXE"

[ -f "$AJ" ]    || { echo "ERROR: android.jar not found: $AJ";    exit 1; }
[ -f "$AAPT2" ] || { echo "ERROR: aapt2 not found: $AAPT2";       exit 1; }

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin}"
JAVAC="${JAVA_BIN:+$JAVA_BIN/}javac"
JARTOOL="${JAVA_BIN:+$JAVA_BIN/}jar"
JAVA="${JAVA_BIN:+$JAVA_BIN/}java"
KEYTOOL="${JAVA_BIN:+$JAVA_BIN/}keytool"

# ---- 准备 ASCII 临时工作目录 ----
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cp -r "$SCRIPT_DIR/project" "$WORK/project"
cd "$WORK"
mkdir -p gen cls dex

echo "[1/6] aapt2 compile"
"$AAPT2" compile --dir project/res -o res.zip

echo "[2/6] aapt2 link"
"$AAPT2" link -o base.apk -I "$AJ" \
    --manifest project/AndroidManifest.xml \
    --min-sdk-version 26 --target-sdk-version 34 \
    --java gen res.zip

echo "[3/6] javac"
find project/src gen -name '*.java' > sources.txt
"$JAVAC" --release 11 -encoding UTF-8 \
    -classpath "$AJ" -d cls @sources.txt

echo "[4/6] d8 dex"
"$JARTOOL" cf app.jar -C cls .
"$JAVA" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --lib "$AJ" --min-api 26 --output dex app.jar
"$JARTOOL" uf base.apk -C dex classes.dex

echo "[5/6] zipalign"
"$ZIPALIGN" -f 4 base.apk aligned.apk

echo "[6/6] sign"
KS="$OUT/volume.keystore"
mkdir -p "$OUT"
if [ ! -f "$KS" ]; then
    # 密钥丢了会酿成大事故：换签名 = 手机上已装的版本无法覆盖升级，只能卸载重装。
    # 所以先把话说清楚再生成，别让人莫名其妙装不上。
    echo
    echo "  !!! WARNING: keystore not found at $KS"
    echo "  !!! A brand new key will be generated."
    echo "  !!! Any device with an older build installed CANNOT be updated in place"
    echo "  !!! (INSTALL_FAILED_UPDATE_INCOMPATIBLE) - it must be uninstalled first."
    echo "  !!! If you have a backup of the original key, put it at $KS and rebuild."
    echo
    "$KEYTOOL" -genkeypair -keystore "$KS" -alias volumetool \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass volumetool123 -keypass volumetool123 \
        -dname "CN=VolumeTool"
fi

VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' project/AndroidManifest.xml)
APK="$OUT/VolumeTool-v$VERSION.apk"

"$JAVA" -cp "$BT/lib/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
    --ks "$KS" --ks-pass pass:volumetool123 --key-pass pass:volumetool123 \
    --out "$APK" aligned.apk

echo
echo "Done: $APK"

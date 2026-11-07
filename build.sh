#!/bin/bash
# 车机歌词 —— 手工构建 APK（不依赖 Gradle，也不需要 Google 官方 maven 源）
#
# 工具链要求（只需三样，都免安装解压即可用）：
#   - JDK 17            （ javac / java / keytool ）
#   - Android build-tools 34.0.0 （ aapt2 / zipalign / apksigner / d8.jar ）
#   - Android platform 35 的 android.jar
#
# 路径默认值按本仓库作者的 Windows Git Bash 环境写的，**别的机器用环境变量覆盖即可**，例：
#   export JDK_HOME=/opt/jdk-17
#   export BUILD_TOOLS=/opt/android-sdk/build-tools/34.0.0
#   export ANDROID_JAR=/opt/android-sdk/platforms/android-35/android.jar
#   bash build.sh
#
# 注意脚本内用到的工具都带 .exe 后缀（面向 Windows Git Bash）。
# Linux/macOS 上把 .exe 去掉或做 alias，思路完全一样。
set -e

export PATH="/usr/bin:/bin:$PATH"

JDK="${JDK_HOME:-D:/Apps/jdk17}"
BT="${BUILD_TOOLS:-D:/Apps/android-sdk/build-tools/34.0.0}"
AJ="${ANDROID_JAR:-D:/Apps/android-sdk/platforms/android-35/android.jar}"

JAVAC="$JDK/bin/javac.exe"
JAVA="$JDK/bin/java.exe"
KEYTOOL="$JDK/bin/keytool.exe"

# 项目根目录 = 脚本自身所在目录（不再依赖任何写死的绝对路径）
PROJ="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJ"
# 给内嵌的 Python 用的 Windows 风格路径（非 Git Bash 环境下原样透传）
PROJ_WIN="$(cygpath -w "$PROJ" 2>/dev/null || echo "$PROJ")"
export PROJ_WIN

# ============================================================================
# 关于「清理」这一步 —— 别改回 rmtree(build)
# ----------------------------------------------------------------------------
# 原来这里写的是 python shutil.rmtree("build")，看着最干净，实际是个雷：
# 某些沙箱/安全策略对删除有批量保护，单回合删除目标数超过 ~50 就拦下来，
# 而且**命令退出码仍是 0** —— 脚本以为清完了，其实 build/ 里全是上一轮的旧
# class / 旧 dex，接下去编出来的包可能是旧的，还一切「看起来成功」。
#
# 正确解法不是去绕保护，而是压根不需要删：每轮用一个带时间戳的新输出目录。
# 不删除 ⇒ 不可能被拦；旧目录留着也不影响正确性，要清的时候人工清就行。
# ============================================================================
OUT="build/b$(date +%s)"
export OUT
echo "=== 0. 本轮输出目录：$OUT（不删旧目录）==="
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "=== 1. aapt2 compile 资源 ==="
"$BT/aapt2.exe" compile --dir res -o "$OUT/res.zip"

echo "=== 2. aapt2 link 生成 R.java + base.apk ==="
"$BT/aapt2.exe" link \
  -o "$OUT/base.apk" \
  -I "$AJ" \
  --manifest AndroidManifest.xml \
  --java "$OUT/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --version-code 12 \
  --version-name "2.1" \
  --no-version-vectors \
  "$OUT/res.zip"

echo "--- 生成的 R.java ---"
find "$OUT/gen" -name '*.java'

echo "=== 3. javac 编译 ==="
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -bootclasspath "$AJ" \
  -d "$OUT/classes" \
  $(find "$OUT/gen" java -name '*.java')

echo "--- 编译产物 ---"
find "$OUT/classes" -name '*.class' | wc -l

echo "=== 4. d8 转 dex ==="
"$JAVA" -Xmx1024M -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$AJ" \
  --min-api 26 \
  --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

ls -l "$OUT/dex/"

echo "=== 5. 把 classes.dex 塞进 apk ==="
"$JAVA" -version >/dev/null 2>&1
python - <<'PYEOF'
import zipfile, shutil, os
out = os.environ["OUT"].replace("/", os.sep)
proj = os.environ.get("PROJ_WIN", ".")
os.chdir(proj)
shutil.copyfile(os.path.join(out, "base.apk"), os.path.join(out, "unsigned.apk"))
with zipfile.ZipFile(os.path.join(out, "unsigned.apk"), "a", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(out, "dex", "classes.dex"), "classes.dex")
print("classes.dex 已写入:", [n for n in zipfile.ZipFile(os.path.join(out, "unsigned.apk")).namelist()][:12])
PYEOF

echo "=== 6. zipalign ==="
"$BT/zipalign.exe" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
"$BT/zipalign.exe" -c -v 4 "$OUT/aligned.apk"

echo "=== 7. 签名证书 ==="
# 仓库自带 debug.keystore（口令 android/android，别名 androiddebugkey）。
# **不要换掉它** —— 仓库里的 CarLyrics.apk 就是用它签的，
# 保持同一签名，网友自己编译的包才能直接覆盖安装官方包。
# 证书不存在时自动重新生成（重新生成的证书和官方包签名不同，只能卸载重装）。
if [ ! -f debug.keystore ]; then
  "$KEYTOOL" -genkeypair -keystore debug.keystore \
    -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=CarLyrics, OU=Dev, O=WBHome, L=Beijing, ST=Beijing, C=CN"
fi

echo "=== 8. 签名 ==="
"$JAVA" -jar "$BT/lib/apksigner.jar" sign \
  --ks debug.keystore \
  --ks-pass pass:android \
  --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --min-sdk-version 26 \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$OUT/signed.apk" \
  "$OUT/aligned.apk"

cp "$OUT/signed.apk" CarLyrics.apk

echo "=== 9. 验证（以产物自身为凭，不信命令跑过就算成）==="
"$JAVA" -jar "$BT/lib/apksigner.jar" verify --min-sdk-version 26 --print-certs CarLyrics.apk
echo ""
"$BT/aapt2.exe" dump badging CarLyrics.apk 2>/dev/null | head -12
echo ""
ls -lh CarLyrics.apk
echo ""
echo "构建完成 → $OUT ，成品 CarLyrics.apk"

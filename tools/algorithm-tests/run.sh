#!/usr/bin/env bash
# 全部测试入口
#
# 分两层：
#   第一层 —— 纯算法与解析逻辑（只依赖 JDK，不需要 Android SDK），快速、可在任何机器上跑；
#   第二层 —— Robolectric 启动与布局测试（需要 Android SDK），会真的把 Activity 跑起来，
#             用于拦截「点进去闪退」这类只有在运行时才会暴露的问题。
#
# 用法：
#   bash tools/algorithm-tests/run.sh            # 只跑算法层
#   bash tools/algorithm-tests/run.sh --android  # 算法层 + Android 运行时层
set -e
cd "$(dirname "$0")"

JDK_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"

echo "==================== 第一层：算法与解析逻辑 ===================="
for f in TotpTest UriTest ImportTest AnyDigitTest; do
  javac -encoding UTF-8 "$f.java"
  echo "########## $f ##########"
  java "$f"
  echo
done

# 启动路径静态审计：需要已编译的 APK，存在才跑
APK="../../app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
  javac -encoding UTF-8 StartupAudit.java
  echo "########## StartupAudit ##########"
  java StartupAudit "$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"
  echo
else
  echo "跳过 StartupAudit（未找到 APK，先执行 assembleDebug）"
  echo
fi

if [ "${1:-}" != "--android" ]; then
  echo "全部测试通过（未包含 Android 运行时层，加 --android 可一并执行）。"
  exit 0
fi

echo "==================== 第二层：Android 运行时（Robolectric） ===================="
ROOT="$(cd ../.. && pwd)"

# Robolectric 需要在离线仓库里找到 android-all-instrumented jar。
# 若沙箱内没有网络，请先把该 jar 放入以下目录：
#   $ROBO_MAVEN/org/robolectric/android-all-instrumented/14-robolectric-10818077-i7/
ROBO_MAVEN="${ROBO_MAVEN:-/opt/robolectric-maven}"

if [ -d "$ROBO_MAVEN" ]; then
  EXTRA="-Drobolectric.offline=true -Drobolectric.dependency.repo.url=file://${ROBO_MAVEN}"
else
  EXTRA=""
  echo "提示：未找到本地离线仓库 ${ROBO_MAVEN}，将尝试联网获取 Robolectric 依赖。"
fi

# Robolectric 的离线模式会在工作目录直接查找 android-all jar（而不是走仓库），
# 因此在跑测试前把它放到 Gradle 会用到的工作目录里。
ROBO_JAR_NAME="android-all-instrumented-14-robolectric-10818077-i6.jar"
ROBO_JAR_SRC="$(find "$ROBO_MAVEN" "$HOME" /opt/robolectric-deps -name 'android-all-instrumented-*.jar' 2>/dev/null | head -1 || true)"
if [ -n "$ROBO_JAR_SRC" ] && [ -f "$ROBO_JAR_SRC" ]; then
  for d in "$ROOT" "$ROOT/app"; do
    [ -f "$d/$ROBO_JAR_NAME" ] || cp "$ROBO_JAR_SRC" "$d/$ROBO_JAR_NAME"
  done
  echo "已就绪 Robolectric 离线依赖：$(basename "$ROBO_JAR_SRC")"
else
  echo "提示：未找到 android-all jar，Robolectric 可能会尝试联网下载。"
  echo "      可运行 tools/setup-robolectric.sh 自动获取。"
fi

: "${GRADLE_CMD:=gradle}"
( cd "$ROOT" && $GRADLE_CMD :app:testDebugUnitTest --console=plain $EXTRA )

echo "全部测试通过。"

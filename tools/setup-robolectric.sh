#!/usr/bin/env bash
# 获取 Robolectric 运行所需的 android-all jar（约 150MB）。
#
# 为什么需要这个脚本：
#   Robolectric 默认去 repo1.maven.org 下载 android-all jar，国内网络经常连不上，
#   导致所有测试报 SSLHandshakeException / MavenArtifactFetcher 失败。
#   这里改用阿里云中央仓库，并把结果放进本地离线仓库 + 项目目录，
#   之后测试即可完全离线运行。
#
# 用法：bash tools/setup-robolectric.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR_ID="14-robolectric-10818077-i7"       # 对应 Android SDK 34
JAR="android-all-instrumented-${JAR_ID}.jar"
OFFLINE_NAME="android-all-instrumented-14-robolectric-10818077-i6.jar"  # 离线模式下 Robolectric 期望的名字

MIRROR="https://maven.aliyun.com/repository/central"
DEST_DIR="/opt/robolectric-maven/org/robolectric/android-all-instrumented/${JAR_ID}"
CACHE="/opt/robolectric-deps"

echo "==> 准备 Robolectric 依赖 (${JAR_ID})"

mkdir -p "$CACHE" "$DEST_DIR"

if [ -s "$CACHE/$JAR" ]; then
  echo "    已缓存，跳过下载：$CACHE/$JAR"
else
  echo "    从阿里云中央仓库下载 ..."
  curl -fSL --retry 3 --retry-delay 3 -m 1800 \
    -H "User-Agent: Mozilla/5.0" \
    -o "$CACHE/$JAR" \
    "${MIRROR}/org/robolectric/android-all-instrumented/${JAR_ID}/${JAR}"
fi

# 1) 放入本地 Maven 仓库（供联网/仓库模式使用）
cp -f "$CACHE/$JAR" "$DEST_DIR/$JAR"
cat > "$DEST_DIR/android-all-instrumented-${JAR_ID}.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>org.robolectric</groupId>
  <artifactId>android-all-instrumented</artifactId>
  <version>${JAR_ID}</version>
  <packaging>jar</packaging>
</project>
POM

# 2) 放入项目目录（Robolectric 离线模式会在工作目录直接找这个文件名）
for d in "$ROOT" "$ROOT/app"; do
  cp -f "$CACHE/$JAR" "$d/$OFFLINE_NAME"
done

echo
echo "==> 完成"
echo "    本地 Maven 仓库：$DEST_DIR"
echo "    离线 jar：$ROOT/$OFFLINE_NAME"
echo
echo "现在可以运行：bash tools/algorithm-tests/run.sh --android"

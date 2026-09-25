#!/usr/bin/env bash
# 算法与解析逻辑的独立验证（不依赖 Android SDK，仅需 JDK 17+）
# 运行：bash tools/algorithm-tests/run.sh
set -e
cd "$(dirname "$0")"
for f in TotpTest UriTest ImportTest AnyDigitTest; do
  javac -encoding UTF-8 "$f.java"
  echo "########## $f ##########"
  java "$f"
  echo
done

# 启动路径审计：需要已编译的 APK，存在才跑
APK="../../app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
  javac -encoding UTF-8 StartupAudit.java
  echo "########## StartupAudit ##########"
  java StartupAudit "$(cd "$(dirname "$APK")" && pwd)/$(basename "$APK")"
  echo
else
  echo "跳过 StartupAudit（未找到 APK，先执行 assembleDebug）"
fi

echo "全部测试通过。"

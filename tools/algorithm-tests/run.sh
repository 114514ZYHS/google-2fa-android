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
echo "全部测试通过。"

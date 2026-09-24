#!/usr/bin/env bash
# Linux 一键构建脚本（容器 / CI / WSL 可用）
#
# 与 Windows 上的 build-apk.ps1 等价，区别是用国内镜像下载依赖，
# 适合国内网络环境（官方 dl.google.com / services.gradle.org 可能不可达）。
#
# 用法：
#   bash build-apk-linux.sh              # 只构建，工具已装好时用
#   bash build-apk-linux.sh --setup      # 先下载并安装 JDK/SDK/Gradle，再构建
#
# 产物：app/build/outputs/apk/debug/app-debug.apk

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${ROOT}/.build-tools-linux"
DOWNLOADS="${TOOLS}/downloads"
SDK="${TOOLS}/android-sdk"
GRADLE_HOME_DIR="${TOOLS}/gradle-8.10.2"
JDK_DIR="${TOOLS}/jdk-17"

# ---- 镜像地址（都在国内可直连）----
MIRROR_SDK="https://mirrors.cloud.tencent.com/AndroidSDK"
MIRROR_GRADLE="https://mirrors.cloud.tencent.com/gradle"
MIRROR_ADOPTIUM="https://mirrors.tuna.tsinghua.edu.cn/Adoptium"

# 组件与版本
CMDLINE_ZIP="commandlinetools-linux-11076708_latest.zip"
PLATFORM_ZIP="platform-35_r01.zip"
BUILDTOOLS_ZIP="build-tools_r35_linux.zip"
PLATFORM_TOOLS_ZIP="platform-tools_r35.0.2-linux.zip"
GRADLE_ZIP="gradle-8.10.2-bin.zip"
JDK_TAR="OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz"

download() {
  local url="$1" out="$2"
  if [ -s "$out" ]; then
    echo "  已存在，跳过：$(basename "$out")"
    return
  fi
  echo "  下载 $(basename "$out") ..."
  curl -fSL --retry 3 --retry-delay 2 -m 900 -o "$out" "$url"
}

setup() {
  echo "==> 安装构建工具到 ${TOOLS}"
  mkdir -p "$DOWNLOADS" "$SDK"

  download "${MIRROR_ADOPTIUM}/17/jdk/x64/linux/${JDK_TAR}" "${DOWNLOADS}/${JDK_TAR}"
  download "${MIRROR_GRADLE}/${GRADLE_ZIP}" "${DOWNLOADS}/${GRADLE_ZIP}"
  download "${MIRROR_SDK}/${CMDLINE_ZIP}" "${DOWNLOADS}/${CMDLINE_ZIP}"
  download "${MIRROR_SDK}/${PLATFORM_ZIP}" "${DOWNLOADS}/${PLATFORM_ZIP}"
  download "${MIRROR_SDK}/${BUILDTOOLS_ZIP}" "${DOWNLOADS}/${BUILDTOOLS_ZIP}"
  download "${MIRROR_SDK}/${PLATFORM_TOOLS_ZIP}" "${DOWNLOADS}/${PLATFORM_TOOLS_ZIP}"

  if [ ! -x "${JDK_DIR}/bin/java" ]; then
    echo "==> 解压 JDK 17"
    mkdir -p "$JDK_DIR"
    tar -xzf "${DOWNLOADS}/${JDK_TAR}" -C "$JDK_DIR" --strip-components=1
  fi

  if [ ! -d "${GRADLE_HOME_DIR}" ]; then
    echo "==> 解压 Gradle"
    unzip -q "${DOWNLOADS}/${GRADLE_ZIP}" -d "$TOOLS"
  fi

  if [ ! -x "${SDK}/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "==> 解压 Android 命令行工具"
    mkdir -p "${SDK}/cmdline-tools"
    rm -rf "${TOOLS}/cmdline-tmp"
    unzip -q "${DOWNLOADS}/${CMDLINE_ZIP}" -d "${TOOLS}/cmdline-tmp"
    mv "${TOOLS}/cmdline-tmp/cmdline-tools" "${SDK}/cmdline-tools/latest"
    rm -rf "${TOOLS}/cmdline-tmp"
  fi

  if [ ! -d "${SDK}/platforms/android-35" ]; then
    echo "==> 解压 platform 35"
    mkdir -p "${SDK}/platforms"
    unzip -q "${DOWNLOADS}/${PLATFORM_ZIP}" -d "${SDK}/platforms"
  fi

  # build-tools 的 zip 解压出来是 android-15（内部版本号），需要改名成 35.0.0
  if [ ! -d "${SDK}/build-tools/35.0.0" ]; then
    echo "==> 解压 build-tools 35.0.0"
    mkdir -p "${SDK}/build-tools"
    rm -rf "${TOOLS}/bt-tmp"
    unzip -q "${DOWNLOADS}/${BUILDTOOLS_ZIP}" -d "${TOOLS}/bt-tmp"
    inner="$(find "${TOOLS}/bt-tmp" -maxdepth 1 -mindepth 1 -type d | head -1)"
    mv "$inner" "${SDK}/build-tools/35.0.0"
    rm -rf "${TOOLS}/bt-tmp"
  fi

  if [ ! -d "${SDK}/platform-tools" ]; then
    echo "==> 解压 platform-tools"
    unzip -q "${DOWNLOADS}/${PLATFORM_TOOLS_ZIP}" -d "${SDK}"
  fi
}

if [ "${1:-}" = "--setup" ]; then
  setup
fi

export JAVA_HOME="${JDK_DIR}"
export ANDROID_HOME="${SDK}"
export ANDROID_SDK_ROOT="${SDK}"
export GRADLE_USER_HOME="${TOOLS}/gradle-home"
export PATH="${JAVA_HOME}/bin:${GRADLE_HOME_DIR}/bin:${PATH}"

# 关闭 AGP 的远程 SDK 组件自动下载：所需组件已由本脚本装好，
# 且 dl.google.com 在部分网络下不可达，避免构建时卡住。
printf 'android.builder.sdkDownload=false\n' > "${TOOLS}/sdk-extra.properties"

echo "==> JAVA_HOME = ${JAVA_HOME}"
java -version 2>&1 | head -1
echo "==> ANDROID_HOME = ${SDK}"
echo

echo "==> 开始构建"
gradle --no-daemon -p "$ROOT" assembleDebug

echo
echo "==> 构建完成"
echo "APK: ${ROOT}/app/build/outputs/apk/debug/app-debug.apk"
ls -lh "${ROOT}/app/build/outputs/apk/debug/app-debug.apk"

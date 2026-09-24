# Google 2FA Android

一个纯本地、零第三方依赖的 Android TOTP 验证器（动态口令），支持多账户管理与导入导出。

> 所有账户数据只保存在本机，不联网、不上传、不含统计与广告代码。应用层面未申请任何网络权限。

## 功能

**验证码**

- 每 30 秒自动刷新 TOTP 验证码，支持 **SHA1 / SHA256 / SHA512** 三种算法
- 支持 **6 位 / 8 位** 验证码，以及自定义周期（5–300 秒）
- 验证码按位分组显示（6 位显示为 `123 456`，8 位显示为 `1234 5678`），便于核对
- 点击验证码即复制到剪贴板，并提示剩余有效秒数
- 每个账户带独立进度条；剩余 5 秒内验证码变蓝提醒即将失效

**账户管理**

- 添加、删除账户
- 长按账户或验证码调出操作菜单：**置顶 / 取消置顶、重命名、复制密钥、删除**
- 置顶账户始终排在最前，其余按名称排序
- 顶部搜索框，按名称、发行方或密钥实时过滤

**导入与导出**

- 直接粘贴 Google Authenticator / 其他验证器的 **`otpauth://` 链接** 即可添加，
  自动解析发行方、账户名、算法、位数与周期
- 批量导入：支持从**剪贴板**粘贴，或从**文件**读取
- 导出备份：可**复制到剪贴板**或**保存为文件**，内容为逐行 `otpauth://` 链接
- 导入时自动跳过重复密钥，并报告「新增 / 重复 / 无效」数量

## 导入格式

每行一条，支持以下三种写法，`#` 开头的行会被忽略：

```
# 标准 otpauth 链接
otpauth://totp/Google:alice@gmail.com?secret=JBSWY3DPEHPK3PXP&issuer=Google
otpauth://totp/GitHub:dev@x.com?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=GitHub&algorithm=SHA256&digits=8&period=60

# 简写：名称,密钥
微博,JBSWY3DPEB3W64TMMQ

# 简写：名称<TAB>密钥（从表格软件复制粘贴时常用）
邮箱	KRSXG5CTMVRXEZLU
```

`otpauth` 链接不区分参数顺序，`algorithm` 可写 `SHA1`、`sha-256`、`Sha512` 等形式，会自动归一化。
`digits` 只接受 6 或 8，`period` 超出 5–300 会回退为 30 秒。
简写行按**最后一个**逗号或制表符分隔，因此名称里含逗号也能正确解析；密钥段会做 Base32 合法性校验。

## 构建

### 方式一：Windows 一键脚本

双击或在 PowerShell 中运行 `build-apk.ps1`。脚本会自动下载 JDK 17、Android SDK 命令行工具与 Gradle 8.10.2 到 `.build-tools/`，然后执行构建。

产物路径：`app/build/outputs/apk/debug/app-debug.apk`

### 方式二：Android Studio

直接用 Android Studio 打开工程目录，等待 Gradle 同步完成后运行即可。

### 方式三：命令行 Gradle

```
gradle assembleDebug
```

需要已安装 JDK 17、Android SDK（`platforms;android-35` 与 `build-tools;35.0.0`）。

### 方式四：Linux / WSL / 容器一键脚本

```
bash build-apk-linux.sh --setup
```

`--setup` 会从**国内镜像**下载全部工具链（腾讯 AndroidSDK / 腾讯 Gradle / 清华 Adoptium JDK 17）
到项目内的 `.build-tools-linux/`，然后构建。工具已装好时可省略 `--setup` 直接构建。

镜像地址（脚本内已内置，如需改可编辑脚本顶部）：

```
Android SDK   https://mirrors.cloud.tencent.com/AndroidSDK
Gradle        https://mirrors.cloud.tencent.com/gradle
JDK 17        https://mirrors.tuna.tsinghua.edu.cn/Adoptium
Maven 依赖     https://maven.aliyun.com/repository/google（见 settings.gradle）
```

### 方式五：GitHub Actions

推送到 `main` 分支或手动触发 `Build APK` 工作流，构建完成后在 Artifacts 中下载 `app-debug`。

## 已验证

本项目已在 Linux 环境实际完成完整构建，产物经过校验：

```
BUILD SUCCESSFUL
app-debug.apk   27 KB
APK 类型        Android package (APK), with APK Signing Block
签名            v1 (JAR signing) = true, v2 (APK Signature Scheme v2) = true
zipalign        Verification succesful
申请权限        无（aapt2 dump permissions 为空）
版本            versionCode=2  versionName=1.1  minSdk=23  targetSdk=35
```

另外校验过 APK 内的 DEX，确认新增功能确实进入产物：
`parseOtpAuth`、`parseShorthand`、`importPayload`、`showExportDialog`、
`buildExportPayload`、`writeExport`、`sortAccounts`、`isPureBase32` 等
方法以及 `SHA1` / `SHA256` / `SHA512`、中文界面文案、`otpauth://totp/` 协议串
均在 `classes2.dex` 中找到。

## 环境要求

| 项目 | 版本 |
| --- | --- |
| compileSdk / targetSdk | 35 |
| minSdk | 23（Android 6.0） |
| JDK | 17 |
| Android Gradle Plugin | 8.6.1 |
| Gradle | 8.10.2 |
| 第三方依赖 | 无 |

## 逻辑验证

`tools/algorithm-tests/` 下有一组不依赖 Android SDK 的独立测试，可直接在命令行运行，覆盖：

- TOTP 算法：RFC 6238 官方测试向量（SHA1 / SHA256 / SHA512 × 6 个时间点，共 18 个向量）、
  6/8 位输出、自定义周期
- `otpauth://` 解析：标准链接、中文与 URL 编码、只带 label、容错回退、导出后重新解析的往返一致性
- 导入逻辑：混合格式解析、重复密钥去重、无效行统计、简写行边界情况

```
bash tools/algorithm-tests/run.sh
```

当前状态：29 + 26 + 19 = **74 项全部通过**。

## 安全说明

- 账户密钥以**明文**保存在应用私有的 `SharedPreferences` 中，依赖 Android 应用沙箱隔离。
- 导出的备份内容包含**明文密钥**，请勿分享或提交到公开仓库。
- 本应用不需要联网即可使用全部功能。

## 目录结构

```
app/src/main/java/com/codex/google2fa/MainActivity.java   全部界面与 TOTP 逻辑
app/src/main/res/values/colors.xml                        配色
app/src/main/res/values/styles.xml                        主题
build-apk.ps1                                             Windows 一键构建脚本
build-apk-linux.sh                                        Linux 一键构建脚本（国内镜像）
.github/workflows/build.yml                               CI 构建工作流
tools/algorithm-tests/                                    算法与解析逻辑的独立测试
```

## 变更记录

### 1.1

- 支持 SHA1 / SHA256 / SHA512 与 6/8 位验证码、自定义周期
- 支持解析 `otpauth://` 链接，批量导入（剪贴板 / 文件）
- 支持导出备份到剪贴板或文件
- 新增搜索过滤、置顶、重命名、复制密钥
- 界面中文化，验证码分组显示，临期变色提醒
- 修复名称中残留 `Issuer:` 前缀的问题；修复含逗号名称被误解析的问题
- 修复 `Typeface.MONOSPACE` 传给 `int` 形参导致的编译错误（重载 `text()` 助手）
- 新增 Linux 一键构建脚本 `build-apk-linux.sh`，改用国内镜像下载工具链
- 补充 GitHub Actions 构建工作流与中文文档

### 1.0

- 初版：多账户 TOTP、添加/删除、点击复制、本地存储

## 许可

见 `LICENSE`。

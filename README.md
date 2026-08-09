# Pocket Agent

Pocket Agent 是运行在 Android 手机上的轻量代码 Agent。它只访问用户通过系统文件选择器明确授权的工程目录，可以索引、搜索、阅读和编辑手机本地源码。

## 当前功能

- Android 系统目录授权，权限可跨重启保留
- 递归工程索引，自动跳过 `.git`、`build`、`node_modules` 等目录
- 文件路径筛选、源码查看和手动编辑
- OpenAI Responses API Agent 工具循环
- `list_files`、`read_file`、`search_files`、`write_file` 工具
- Agent 写入前显示 Diff，并要求人工批准
- Android Keystore 加密保存 API Key
- API 地址和模型可配置

当前版本不执行 Shell 命令。后续可通过 Termux 桥接增加 Git、编译和测试能力。

## 安装

APK 位于：

```text
release/PocketAgent-debug.apk
```

连接已开启 USB 调试的手机后，在 PowerShell 运行：

```powershell
.\install-on-phone.ps1
```

也可以把 APK 发送到手机，在系统文件管理器中打开安装。iQOO 首次安装需要允许当前来源安装应用。

## 使用

1. 打开“设置”，填写 API Key。默认 API 地址为 `https://api.openai.com/v1/responses`。
2. 默认模型为 `gpt-5.3-codex`，可按账号实际可用模型修改。
3. 回到“Agent”，点击“选择工程”，授权手机中的工程目录。
4. 输入任务。Agent 会按需读取和搜索文件。
5. Agent 请求写入时检查 Diff，选择“写入”或“拒绝”。

建议把工程放在 `Documents/Projects` 或手机内部存储的独立目录。Android 无 Root 时不能访问其他应用的私有目录 `/data/data/...`。

## 本地构建

要求 JDK 17 和 Android SDK 36：

```powershell
.\gradlew.bat assembleDebug
```

构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 安全边界

- 应用只持有系统文件选择器授予的目录权限。
- 相对路径中的 `..` 会被拒绝。
- 单文件读取限制为 1 MB，工程索引限制为 2500 个条目。
- API Key 使用 Android Keystore 的 AES-GCM 密钥加密。
- 源码内容仅在模型调用相关工具时发送到配置的 API 地址。
- 每次 Agent 写入都需要人工确认；手动编辑器的“保存”属于用户直接操作。

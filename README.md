# 油漆

“油漆”是运行在 Android 手机上的角色化代码 Agent。它可以读取和编辑用户通过系统文件选择器授权的工程，并通过 Termux 执行 Git、构建、测试和脚本任务。聊天界面采用左右消息流，文件、搜索、写入和终端操作会显示为独立执行记录。

## 功能

- 导入 SillyTavern PNG 或 JSON 角色卡
- 使用角色卡的名称、描述、性格、场景、示例对话和系统提示与用户交流
- 支持 OpenAI、DeepSeek、OpenRouter、硅基流动、Kimi、阿里云百炼、智谱、Gemini、火山方舟和自定义中转 API
- 从供应商 `/models` 接口获取和搜索可用模型
- 自动兼容 Responses API 与 Chat Completions；Responses 不可用时自动回退
- 扫描、搜索、读取和写入手机本地工程
- 通过 Termux 执行真实 Shell 命令并回传退出码、标准输出和错误输出
- 使用 Android Keystore AES-GCM 加密保存各供应商的 API Key

## APK

```text
release/PocketAgent-debug.apk
```

连接已开启 USB 调试的手机后，可在 PowerShell 运行：

```powershell
.\install-on-phone.ps1
```

## 使用

1. 在“配置”页选择模型供应商，填写 API Key，并点击“获取模型”选择模型。
2. 自定义中转站可填写 Base URL，例如 `https://example.com/v1`，也可填写完整的 `/responses` 或 `/chat/completions` 地址。
3. 在“对话”页选择手机上的工程目录。
4. 直接交代任务。Agent 会按需读取文件、修改工程并运行命令。
5. 在“配置”页可以导入 SillyTavern 角色卡，让 Agent 使用角色设定交流和解释执行过程。

中转模型必须支持 Function Calling / Tools，否则只能聊天，不能执行 Agent 工具。

## 角色卡

支持以下常见格式：

- PNG `tEXt`、`zTXt` 或 `iTXt` 块中的 Base64 `chara` 数据
- SillyTavern Character Card V1 JSON
- Character Card V2/V3 的 `data` 结构

当前读取核心人格字段：`name`、`description`、`personality`、`scenario`、`first_mes`、`mes_example`、`system_prompt` 和 `post_history_instructions`。角色设定只改变语气、人格和解释方式，不允许覆盖工具真实性规则；Agent 不得编造未发生的文件修改或命令结果。

## Agent 权限

- **每次询问**：工程写入和所有 Termux 命令都弹窗确认。
- **标准自动**：自动写入已授权工程，自动执行只读命令；其他命令弹窗确认。
- **完全自动**：自动写入已授权工程，并执行模型请求的所有 Termux 命令。

“完全自动”具有真实文件删除、脚本执行和网络命令风险。只应对可信模型和可信角色卡启用。

## Termux

Termux 需要启用外部命令，并有权访问共享存储中的工程：

```sh
mkdir -p ~/.termux
echo 'allow-external-apps=true' >> ~/.termux/termux.properties
termux-reload-settings
pkg install git ripgrep python nodejs
```

Android 11 及以上还需要在系统设置中允许 Termux“所有文件访问”。“油漆”自身仍只通过 SAF 访问用户明确选择的工程目录，但 Termux 的 Shell 权限范围更广。

## 构建

需要 JDK 17 和 Android SDK 36：

```powershell
.\gradlew.bat assembleDebug lintDebug
```

构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

## 安全边界

- 工程文件 API 只访问系统文件选择器授权的目录，并拒绝包含 `..` 的相对路径。
- 单文件读取和工程索引有大小与条目数量限制。
- API Key 不以明文写入 SharedPreferences。
- 角色卡内容会作为模型提示词发送到所配置的 API。
- “完全自动”不会弹出写入或命令确认；切换到“每次询问”可恢复逐项审批。

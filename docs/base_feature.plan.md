# base_feature.plan.md - OpenCode JetBrains Plugin

**Author**: hhlai1990@gmail.com

## 概述

这是一个 JetBrains IDE 插件，用于将 [OpenCode](https://opencode.ai)（开源 AI coding agent）集成到 IDE 工作流中。

本文档描述“基础能力”的最终用户体验与实现要点（以 Claude Code JetBrains 插件的交互习惯为对齐目标）。

## 已实现功能

### 1) Quick Launch（快速打开/聚焦终端）

| 平台 | 快捷键 |
|------|--------|
| Mac | `Cmd + Esc` |
| Windows/Linux | `Ctrl + Esc` |

**行为：**
- 查找名为 `OpenCode` 的 Terminal tab
- 若存在：聚焦该 tab，并激活 Terminal Tool Window
- 若不存在：创建新 tab（命名为 `OpenCode`），执行 `opencode --hostname 0.0.0.0 --port <auto>`（端口自动探测；如已存在 server 则使用 `opencode --port <port>`）
- IDE 启动时不会自动创建终端，只有用户显式触发才会启动

### 2) Add Context to Terminal（添加上下文到终端）

| 平台 | 快捷键 |
|------|--------|
| Mac | `Opt + Cmd + K` |
| Windows/Linux | `Ctrl + Alt + K` |

**行为：**
- **Editor（有选区）**：发送 `@path#Lstart-end`（仅引用，不发送源码内容）
- **Editor（无选区）**：发送 `@path`（当前文件引用）
- **Project View（多选文件/目录）**：为每个条目发送 `@path`
- 若 OpenCode 终端未打开：插件会自动创建/聚焦终端，然后再插入引用（避免“无终端就没反应”的困惑）

### 3) Sidebar Icon（右侧栏图标）

- 右侧栏仅保留一个 OpenCode 图标
- 点击图标触发 Quick Launch 行为（聚焦/创建终端）
- ToolWindow 本身不展示复杂内容：触发后立即隐藏（对齐 Claude 的“点击即跳转”风格）

### 4) Context Tracking（状态栏上下文追踪）

- 状态栏可启用 OpenCode 图标（由 JetBrains “Status Bar Widgets” 统一开关管理）
- 悬停显示当前上下文（文件与可选行号范围）
- 点击会把当前上下文插入到 OpenCode 终端（如未启动会自动创建/聚焦终端）

## 技术实现

### 架构（核心对象）

```
OpenCodeService (Project-scoped)
├── focusOrCreateTerminal()            # 终端生命周期
├── pasteToTerminal()                  # 粘贴到已存在终端
└── focusOrCreateTerminalAndPaste()    # 统一 UX：可自动创建并重试粘贴

OpenCodeToolWindowFactory              # 右侧栏图标行为
QuickLaunchAction                      # Cmd+Esc handler
SendSelectionToTerminalAction           # Opt+Cmd+K handler

SelectionContextService                # 跟踪 active file/selection
OpenCodeContextWidgetFactory           # 状态栏 Widget
```

### 关键 API

- `TerminalToolWindowManager.getInstance(project)` - Terminal 管理
- `TerminalToolWindowManager.createLocalShellWidget()` - 创建 Terminal tab
- `ShellTerminalWidget.executeCommand()` - 执行命令
- `TtyConnector.write()` - 向终端注入文本
- `ToolWindowManager` / `ToolWindowManagerListener` - ToolWindow 生命周期
- `Alarm` - 防抖/调度（避免自建线程池）

### plugin.xml（关键注册点）

- Actions:
  - `OpenCode.QuickLaunch`（仅快捷键，不放入 Tools 菜单）
  - `OpenCode.AddLines`（EditorPopupMenu）
  - `OpenCode.AddFile`（ProjectViewPopupMenu）
- Extensions:
  - `toolWindow` id=`OpenCode`
  - `statusBarWidgetFactory` id=`OpenCodeContext`
  - `projectService`：`OpenCodeService` / `SelectionContextService`

## 项目结构（关键路径）

```
src/main/kotlin/ai/opencode/ide/jetbrains/
├── OpenCodeService.kt
├── OpenCodeToolWindow.kt
├── QuickLaunchAction.kt
├── SendSelectionToTerminalAction.kt
├── context/
│   ├── SelectionContextService.kt
│   └── OpenCodeContextWidget.kt
├── diff/
├── session/
└── util/

src/main/resources/META-INF/plugin.xml
```

## 验证用例

1. 按 `Cmd+Esc` / `Ctrl+Esc`：创建或聚焦 `OpenCode` 终端
2. Editor 无选区按 `Opt+Cmd+K`：插入 `@current-file`
3. Editor 有选区按 `Opt+Cmd+K`：插入 `@current-file#Lx-y`
4. Project View 选中多个文件/目录按 `Opt+Cmd+K`：插入多个 `@path`
5. 点击右侧栏 OpenCode 图标：聚焦/创建终端
6. 启用状态栏 Widget：悬停可见当前上下文，点击可插入上下文到终端

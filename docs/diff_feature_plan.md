# OpenCode JetBrains 插件 Diff 功能开发计划

## 概述

本文档描述了 OpenCode JetBrains 插件的 Diff 功能实现状态。功能设计对标 Claude Code JetBrains 插件，旨在提供流畅、原生且无缝的代码审查体验。

---

## 核心架构与数据流

插件采用 **本地 Git 操作优先** 的策略来管理代码变更，而非依赖服务端的 Revert API。这种设计使得插件在无状态（Stateless）模式下运行更健壮，且能充分利用 JetBrains 的 Git 集成能力。

### 架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              JetBrains IDE                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────────────┐    ┌───────────────────────────────────────────────┐  │
│  │  OpenCode        │    │  OpenCode Diff Viewer                         │  │
│  │  Terminal Tab    │    │  [Accept] [Reject]                            │  │
│  │                  │    │  ← → 文件切换   ↑ ↓ 变更跳转                  │  │
│  │  $ opencode      │    └───────────────────────────────────────────────┘  │
│  │    --port 4096   │                                                      │
│  │                  │                                                      │
│  └──────────────────┘                                                      │
│           │                           │                                      │
│           │ 启动时指定端口              │ 点击文件                             │
│           ▼                           ▼                                      │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  JetBrains Diff Editor                                               │   │
│  │  ┌────────────────────────┐  ┌────────────────────────────────────┐  │   │
│  │  │  Before (HEAD)         │  │  After (Modified)                  │  │   │
│  │  │                        │  │                                    │  │   │
│  │  │  fun hello() {         │  │  fun hello() {                     │  │   │
│  │  │ -  println("Hello")    │  │ +  println("Hello, World!")        │  │   │
│  │  │  }                     │  │  }                                 │  │   │
│  │  └────────────────────────┘  └────────────────────────────────────┘  │   │
│  │  [Accept] (git add)   [Reject] (restore before)                     │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  OpenCodeApiClient (SDK)              SessionManager                  │   │
│  │  - discoverPort()                     - activeSessionId               │   │
│  │  - connectToServer()                  - onDiffReceived()              │   │
│  │  - getSessionDiff()                   - acceptDiff() -> git add       │   │
│  │                                       - rejectDiff() -> restore before │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                          │                                                   │
│                          ▼ HTTP + SSE                                        │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  OpenCode Server (http://localhost:4096)                            │   │
│  │  ├── GET  /session/:id/diff    -> 获取 Diff 数据                      │   │
│  │  └── GET  /event (SSE)         -> 实时事件流 (session.diff)           │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 关键流程

#### 1. Diff 获取
- 插件监听 SSE `session.idle` 事件（表示 AI 完成一轮输出）。
- 调用 SDK `client.getSessionDiff()` 获取当前 Session 的所有变更。
- 将 Diff 数据传递给 `DiffViewerService` 进行展示。

#### 2. Diff 展示
- 使用 JetBrains 原生 `DiffManager`。
- 支持多文件链式展示（`SimpleDiffRequestChain`），用户可使用快捷键导航。
- 默认聚焦最后一个修改的文件。

#### 3. Accept (接受变更)
- **操作**: 用户点击 "Accept"。
- **执行**: 插件在后台执行 `git add <file>`。
- **效果**: 文件被暂存（Staged），并在 Diff 列表中标记为完成。

#### 4. Reject (拒绝变更)
- **操作**: 用户点击 "Reject"。
- **执行**: 使用 `diff.before` 内容恢复文件（见下方详细策略）。
- **效果**: 文件回滚到 **OpenCode 修改前** 的状态，并在 Diff 列表中移除。

---

## Accept/Reject 策略详解

### 核心原则

1. **Reject 恢复到 OpenCode 修改前的状态**，而非 Git HEAD
2. **利用 `diff.before` 字段**：服务端返回的 Diff 数据包含 `before`（修改前）和 `after`（修改后）内容
3. **LocalHistory 保护**：所有破坏性操作前先创建 LocalHistory 标签，防止误操作导致数据丢失
4. **最小化副作用**：只修改 worktree 文件，不主动操作 Git staging area
5. **空内容保护**：当 `before` 为空时，先判断文件是否已被 Git 跟踪；仅对未跟踪文件执行删除

### 文件状态场景全覆盖

以下是所有可能的文件状态组合及对应的 Accept/Reject 行为：

| 场景 | 文件原状态 | OpenCode 操作 | `diff.before` | Accept 行为 | Reject 行为 |
|------|-----------|---------------|---------------|-------------|-------------|
| **A** | 不存在 | 创建新文件 | `""` (空字符串) | `git add` | 删除文件 |
| **B** | Untracked + 有内容 | 修改文件 | 用户原内容 | `git add` | 写回 `before` 内容 |
| **C** | Tracked + Clean (= HEAD) | 修改文件 | HEAD 版本内容 | `git add` | 写回 `before` 内容 |
| **D** | Tracked + Unstaged 修改 | 修改文件 | 用户 unstaged 内容 | `git add` | 写回 `before` 内容 |
| **E** | Tracked + Staged 修改 | 修改文件 | 用户 staged 后的 worktree 内容 | `git add` | 写回 `before` 内容 (staging 保持) |
| **F** | Tracked + Staged + Unstaged | 修改文件 | 用户 worktree 内容 | `git add` | 写回 `before` 内容 (staging 保持) |

### 决策流程图

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Reject 决策流程                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│                    ┌─────────────────┐                              │
│                    │ 用户点击 Reject │                              │
│                    └────────┬────────┘                              │
│                             │                                        │
│                             ▼                                        │
│                    ┌─────────────────┐                              │
│                    │ LocalHistory    │                              │
│                    │ 创建恢复点标签   │                              │
│                    └────────┬────────┘                              │
│                             │                                        │
│                             ▼                                        │
│                    ┌─────────────────┐                              │
│                    │ 获取 DiffEntry  │                              │
│                    │ diff.before     │                              │
│                    └────────┬────────┘                              │
│                             │                                        │
│                             ▼                                        │
│               ┌─────────────────────────┐                           │
│               │ before 为空且未跟踪?    │                           │
│               └────────────┬────────────┘                           │
│                    ┌───────┴───────┐                                │
│                   Yes              No                               │
│                    │               │                                │
│                    ▼               ▼                                │
│        ┌───────────────────────┐  ┌────────────────────────────┐   │
│        │ 场景 A: 新建未跟踪文件 │  │ 场景 B-F/已跟踪空文件      │   │
│        │ → 删除文件            │  │ → 写入 before 内容         │   │
│        └───────────────────────┘  └────────────────────────────┘   │
│                                                                      │
│                             │                                        │
│                             ▼                                        │
│                    ┌─────────────────┐                              │
│                    │ 刷新 VFS        │                              │
│                    │ 移除 Diff 记录  │                              │
│                    └─────────────────┘                              │
└─────────────────────────────────────────────────────────────────────┘
```

### 旧策略的问题（已修复）

旧实现使用 `git restore --source=HEAD --staged --worktree` 存在以下问题：

| 问题 | 描述 | 影响 |
|------|------|------|
| 丢失用户 unstaged 变更 | `--source=HEAD` 恢复到 HEAD，而非 OpenCode 修改前 | 场景 D, E, F |
| 丢失用户 untracked 文件 | 对 untracked 文件直接删除，不判断是否有 before 内容 | 场景 B |
| 破坏 staging 状态 | `--staged` 参数会清除用户已暂存的变更 | 场景 E, F |

### LocalHistory 保护机制

为防止误操作，插件在以下时机创建 LocalHistory 标签：

1. **Reject 操作前**：`"OpenCode: Before rejecting <file>"`
2. **Accept 操作前**（可选）：`"OpenCode: Before accepting <file>"`

用户可通过 IDE 的 `Local History > Show History` 功能恢复任意历史版本。

---

## 代码结构

项目已重构为使用官方生成的 Java SDK (`okhttp-gson` 版)，移除了手写的模型类。

```
src/main/kotlin/ai/opencode/ide/jetbrains/
├── OpenCodeService.kt              # 核心服务：协调 API、Session 和 Diff Viewer
├── OpenCodeToolWindow.kt           # 侧边栏入口
├── api/                            # API 层
│   ├── OpenCodeApiClient.kt        # SDK 包装器，处理 API 调用
│   ├── SseEventListener.kt         # SSE 监听器（手动实现）
│   ├── sdk/                        # 自动生成的 OpenAPI SDK
│   └── models/                     # 辅助模型
│       ├── Events.kt               # SSE 事件模型
│       ├── DiffEntry.kt            # Diff 业务实体
│       └── Session.kt              # SessionStatus 模型（兼容性保留）
│
├── diff/                           # Diff UI
│   ├── DiffViewerService.kt        # Diff 展示服务
│   └── OpenCodeDiffEditorActions.kt # Diff 界面动作 (Accept/Reject)
│
├── session/                        # 状态管理
│   └── SessionManager.kt           # 管理 Diff 状态，执行 Git 操作
│
└── util/                           # 工具类
    └── PortFinder.kt               # 端口发现
```

---

## 开发规范与最佳实践

1.  **SDK 使用**: 优先使用 `ai.opencode.ide.jetbrains.api.sdk` 下生成的类。
2.  **Git 操作**: 使用 `GeneralCommandLine` 执行 Git 命令，确保操作的原子性和安全性。
3.  **UI 交互**: 保持非模态交互，Diff 窗口应在后台准备好，并在合适的时机（如 session.idle）自动弹出或通过通知提示。
4.  **日志**: 关键业务操作（Diff 接收、Accept、Reject）使用 INFO 级别，频度高的轮询或底层事件使用 DEBUG 级别。

---

## 进度追踪

| 功能模块 | 状态 | 说明 |
|----------|------|------|
| 端口发现与启动 | ✅ | 自动寻找可用端口 |
| SSE 连接 | ✅ | 稳定接收服务器事件 |
| SDK 集成 | ✅ | 使用生成的 SDK 替代手写 Client |
| Diff 展示 | ✅ | 原生多文件 Diff 界面 |
| Accept 操作 | ✅ | 本地 `git add` 实现 |
| Reject 操作 | ✅ | 使用 `diff.before` 恢复原内容（已修复） |
| LocalHistory 保护 | ✅ | 破坏性操作前创建恢复点 |

---

## 参考

- [OpenCode Server API](https://opencode.ai/docs/server)
- [JetBrains Platform SDK](https://plugins.jetbrains.com/docs/intellij/)

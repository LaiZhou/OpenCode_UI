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
│  │  [Accept] (git add)   [Reject] (git restore/rm)                      │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  OpenCodeApiClient (SDK)              SessionManager                  │   │
│  │  - discoverPort()                     - activeSessionId               │   │
│  │  - connectToServer()                  - onDiffReceived()              │   │
│  │  - getSessionDiff()                   - acceptDiff() -> git add       │   │
│  │                                       - rejectDiff() -> git restore   │   │
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
- **执行**:
    - 若文件为 **Untracked** (新文件): 执行文件删除操作 (`rm`)。
    - 若文件为 **Tracked** (已有文件): 执行 `git restore --source=HEAD --staged --worktree <file>`。
- **效果**: 文件回滚到修改前的状态（HEAD），并在 Diff 列表中移除。

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
| Reject 操作 | ✅ | 本地 `git restore` / `rm` 实现 |
| 状态栏上下文 | ✅ | 显示当前选中上下文 |
| Diff 列表面板 | 🚫 | 已废弃，采用纯 Diff View 体验 |

---

## 参考

- [OpenCode Server API](https://opencode.ai/docs/server)
- [JetBrains Platform SDK](https://plugins.jetbrains.com/docs/intellij/)

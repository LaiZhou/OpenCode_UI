# OpenCode JetBrains Plugin

[![JetBrains Plugin](https://img.shields.io/badge/JetBrains-Plugin-blue)](https://plugins.jetbrains.com)
[![OpenCode](https://img.shields.io/badge/OpenCode-AI%20Agent-green)](https://opencode.ai)

A JetBrains IDE plugin that integrates [OpenCode](https://opencode.ai) — the open-source AI coding agent — directly into your development workflow.

## Features

| Feature | Description | Shortcut (Mac) | Shortcut (Win/Linux) |
|---------|-------------|----------------|----------------------|
| **Quick Launch** | Connect to existing OpenCode server or create new terminal | `Cmd + Esc` | `Ctrl + Esc` |
| **Add to Terminal** | Send current file/selection or selected files to OpenCode | `Opt + Cmd + K` | `Ctrl + Alt + K` |
| **Diff Review** | View diffs and accept/reject changes in IDE | — | — |
| **Notifications** | System alert when task completes | — | — |
| **Auto-Resume** | Restore last session on launch | — | — |
| **Smart Links** | Clickable file paths in terminal | — | — |
| **Auth Support** | Optional password for OpenCode server | — | — |
| **Local Change Alert** | Warn when local edits differ from AI output | — | — |

### Feature Comparison with Claude Code

| Feature | Claude Code | OpenCode |
|---------|-------------|----------|
| Quick Launch | ✅ | ✅ |
| Diff Viewing | ✅ | ✅ |
| File Reference Shortcuts | ✅ | ✅ |
| Diagnostic Sharing | ✅ | ❌ (uses built-in LSP) |

### Sidebar Icon

Click the **OpenCode** icon in the right sidebar to instantly focus or create an OpenCode terminal session.

### Context Menus

- **Editor**: Right-click in editor → *OpenCode: Add Context*
- **Project View**: Right-click on files/folders → *OpenCode: Add File(s)*

## Requirements

- **JetBrains IDE**: IntelliJ IDEA, WebStorm, PyCharm, etc. (2025.2+)
- **OpenCode CLI**: Install via `npm install -g opencode` or see [opencode.ai/download](https://opencode.ai/download)

## Installation

**Plugin URL**: https://plugins.jetbrains.com/plugin/29744-opencode-ui

Open **Settings** → **Plugins** → **Marketplace** → Search "OpenCode" → **Install**

## Usage

### 1. Launch OpenCode Terminal

Press `Cmd+Esc` (Mac) or `Ctrl+Esc` (Win/Linux) to open the connection dialog. You can:

- **Connect to existing server**: Enter `host:port` (e.g., `127.0.0.1:58052`) and optional password to connect to OpenCode Desktop or any running OpenCode server. Authentication is detected automatically if available.
- **Create new terminal**: Use default `127.0.0.1:4096` to create a local OpenCode terminal session. The terminal tab will be named `OpenCode(4096)`.

![Step 1: Launch OpenCode](images/1.png)

### 2. Send Code Context to OpenCode

In the editor or Project View, press `Opt+Cmd+K` (Mac) or `Ctrl+Alt+K` (Win/Linux).

- If the OpenCode terminal is not open yet, the plugin creates/focuses it automatically.
- In the editor, it shares the **current file** even if nothing is selected.

![Step 2: Selection](images/2.png)

The plugin will send:

- **Editor selection**: `@path/to/file.kt#L10-25`
- **Editor (no selection)**: `@path/to/file.kt`
- **Project View selection**: `@path/to/file.kt` for each selected file

![Step 3: Result in Terminal](images/3.png)

This allows OpenCode to understand the context of your question or request.

### 3. Sidebar Button

Click the OpenCode icon in the right sidebar to quickly focus or create the OpenCode terminal.

![Step 4: Sidebar Button](images/4.png)

### 4. Review Diffs

When OpenCode edits files, the plugin opens a native IDE diff viewer.

- **Chronological View**: Changes are shown in the order they were made, starting from the first modified file.
- **Navigation**: Use **← →** arrows to switch files and **↑ ↓** arrows to jump between changes.
- **Trigger**: The diff viewer opens automatically when OpenCode finishes a response (session idle).
- **Accept**: Confirms if local edits differ, then writes AI output and stages the file.
- **Reject**: Restores the file to the pre-OpenCode baseline (Local History fallback if needed).
- **Local Modified**: The diff title shows `(Local Modified)` when your file differs from AI output.
- **Auto-advance**: After accepting/rejecting, automatically opens the next pending diff.

![Diff Viewer - Accept](images/5.png)
![Diff Viewer - Reject](images/6.png)

### 5. Task Notifications

The plugin sends a system notification when OpenCode finishes a task (transitions from Busy to Idle). This allows you to switch to other work while the AI is generating code, and be notified immediately when it's done.

> **Tip**: To receive desktop notifications, please ensure your operating system allows notifications for the JetBrains IDE (e.g., on macOS: *System Settings > Notifications > IntelliJ IDEA*).

### 6. Smart File Links

File paths in the terminal output (e.g., `@src/main/kotlin/Main.kt#L10-20`) are clickable. Clicking them opens the file in the editor and highlights the referenced lines.

## Keyboard Shortcuts

All shortcuts are customizable via **Settings** → **Keymap** → search for "OpenCode".

| Action | Mac | Windows/Linux |
|--------|-----|---------------|
| Open/Focus OpenCode | `Cmd + Esc` | `Ctrl + Esc` |
| Add to OpenCode Terminal | `Opt + Cmd + K` | `Ctrl + Alt + K` |

## Terminal Management

The plugin uses a single terminal tab per project named **"OpenCode({port})"**.

- Only one OpenCode terminal session per project
- The terminal persists across plugin actions
- Closing the terminal tab will create a new one on next launch

## Troubleshooting

### "opencode: command not found"

Install the OpenCode CLI:

```bash
npm install -g opencode
```

Or download from [opencode.ai/download](https://opencode.ai/download)

### Terminal not responding

Try closing the "OpenCode({port})" terminal tab and pressing `Cmd+Esc` again to create a fresh session.

### Shortcuts not working

1. Check for conflicts in **Settings** → **Keymap**
2. Search for your shortcut to see if it's assigned to another action
3. Reassign or remove conflicting shortcuts

## Support

- [OpenCode Documentation](https://opencode.ai/docs)
- [GitHub Issues](https://github.com/anomalyco/opencode/issues)
- [Discord Community](https://opencode.ai/discord)

## License

MIT License. See [LICENSE](LICENSE) for details.

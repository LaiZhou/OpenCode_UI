# Plan.md - OpenCode JetBrains Plugin

**Author**: hhlai1990@gmail.com

## Overview

JetBrains IDE plugin for integrating [OpenCode](https://opencode.ai) AI coding agent.

## Implemented Features

### 1. Quick Launch

| Platform | Shortcut |
|----------|----------|
| Mac | `Cmd + Esc` |
| Windows/Linux | `Ctrl + Esc` |

**Behavior:**
- Searches for a terminal tab named `OpenCode`
- If found → focuses the tab and activates Terminal tool window
- If not found → creates new terminal tab, names it `OpenCode`, runs `opencode` command

### 2. Send Selection to Terminal

| Platform | Shortcut |
|----------|----------|
| Mac | `Opt + Cmd + K` |
| Windows/Linux | `Ctrl + Alt + K` |

**Behavior:**
- **Editor selection**: Sends `@path#Lstart-end` + selected code
- **Project View selection**: Sends `@path` for each file/directory
- **No selection**: Action is disabled (not visible)

### 3. Sidebar Icon

- Icon displayed in right sidebar
- Clicking icon triggers Quick Launch behavior (focus or create terminal)
- No panel content - tool window hides immediately after triggering action

## Technical Implementation

### Architecture

```
OpenCodeService (Project-scoped)
├── focusOrCreateTerminal()   # Terminal lifecycle
└── pasteToTerminal()         # Text injection

OpenCodeToolWindow            # Sidebar factory
QuickLaunchAction             # Cmd+Esc handler
SendSelectionToTerminalAction # Opt+Cmd+K handler
```

### Key APIs Used

- `TerminalView.getInstance(project)` - Terminal management
- `ShellTerminalWidget.executeCommand()` - Command execution
- `TtyConnector.write()` - Raw text injection
- `ToolWindowManager` - Tool window lifecycle
- `ToolWindowManagerListener` - Sidebar click detection

### Plugin Configuration

**Dependencies:**
- `com.intellij.modules.platform`
- `org.jetbrains.plugins.terminal`

**Actions:**
- `OpenCode.QuickLaunch` → MainToolbar
- `OpenCode.AddLines` → EditorPopupMenu
- `OpenCode.AddFile` → ProjectViewPopupMenu

**Extensions:**
- `toolWindow` id="OpenCode" anchor="right"

## Project Structure

```
.
├── src/main/
│   ├── kotlin/ai/opencode/jetbrains/
│   │   ├── OpenCodeService.kt          # Core terminal management logic
│   │   ├── OpenCodeToolWindow.kt       # Sidebar tool window factory
│   │   ├── QuickLaunchAction.kt        # Cmd+Esc action handler
│   │   └── SendSelectionToTerminalAction.kt  # Add to terminal action
│   └── resources/
│       ├── META-INF/plugin.xml         # Plugin configuration
│       └── icons/opencode.svg          # Sidebar icon
├── build.gradle.kts                    # Gradle build configuration
├── gradle.properties                   # Gradle configuration properties
├── gradlew                             # *nix Gradle Wrapper script
├── gradlew.bat                         # Windows Gradle Wrapper script
├── settings.gradle.kts                 # Gradle project settings
├── Plan.md                             # This file (technical documentation)
└── README.md                           # Plugin description and usage manual
```

## Development

### Prerequisites

- JDK 17+
- Gradle 9.0+

### Build

```bash
./gradlew build
```

### Run (Development Mode)

```bash
./gradlew runIde
```

This launches a sandboxed IntelliJ IDEA instance with the plugin installed.

### Package

```bash
./gradlew buildPlugin
```

The plugin ZIP will be generated in `build/distributions/`.

## Verification

### Test Scenarios

1. Press `Cmd+Esc` → creates OpenCode terminal tab, runs `opencode`
2. Press `Cmd+Esc` again → focuses existing terminal (no new tab)
3. Select code → `Opt+Cmd+K` → text appears in terminal
4. Select files in Project View → `Opt+Cmd+K` → paths appear in terminal
5. Click sidebar icon → focuses terminal

## Changelog

### v1.0.0

- Initial release
- Quick Launch action with keyboard shortcut
- Send Selection action for editor and Project View
- Sidebar tool window with OpenCode branding
- Custom SVG icon based on OpenCode brand

# OpenCode IntelliJ Plugin - Developer Guide for Agents

This repository contains the source code for the OpenCode IntelliJ Platform plugin. This document provides guidelines for AI agents and developers working on this codebase.

## 1. Project Overview
- **Type**: IntelliJ Platform Plugin
- **Language**: Kotlin (JDK 17)
- **Build System**: Gradle (Kotlin DSL)
- **Target Platform**: IntelliJ IDEA 2024.2+ (Since Build 242)
- **Core Dependencies**:
  - IntelliJ Platform SDK (managed via `intellijPlatform` plugin)
  - OkHttp 4.12.0 (Networking)
  - Gson 2.11.0 (JSON Serialization)

## 2. Build and Verification Commands
Always use the provided Gradle wrapper (`./gradlew`) in the root directory.

### Build & Run
- **Build Project**:
  ```bash
  ./gradlew build
  ```
  *Compiles code, runs tests, and builds the plugin distribution.*

- **Run IDE (Sandbox)**:
  ```bash
  ./gradlew runIde
  ```
  *Launches a sandbox IntelliJ instance with the plugin installed. Use this to manually verify UI and integration.*

- **Clean Build**:
  ```bash
  ./gradlew clean build
  ```

### Testing
- **Run All Tests**:
  ```bash
  ./gradlew test
  ```
- **Run Single Test Class**:
  ```bash
  ./gradlew test --tests "ai.opencode.ide.jetbrains.util.PortFinderTest"
  ```
- **Run Single Test Method**:
  ```bash
  ./gradlew test --tests "ai.opencode.ide.jetbrains.util.PortFinderTest.testPortAvailability"
  ```
- **Run Tests Matching Pattern**:
  ```bash
  ./gradlew test --tests "*PortFinder*"
  ```

### Code Quality
- **Lint & Verify**:
  ```bash
  ./gradlew check
  ```
  *Runs all verification tasks including static analysis and tests.*

## 3. Code Style & Conventions
Follow the official [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html) and [IntelliJ Platform SDK Guidelines](https://plugins.jetbrains.com/docs/intellij/intro.html).

### Language & Comments Policy
- **Source Code Language**: All source code MUST be in English
- **Comments & Documentation**: All comments, documentation, and user-facing strings MUST be in English
- **No Non-English Content**: Do not include Chinese, Japanese, or any other non-English text in source files
- **Exception**: Non-English content is only allowed in:
  - User-facing messages that are explicitly localized
  - Documentation files specifically targeting non-English audiences
  - Test data that requires non-English characters for testing purposes

### Formatting
- **Indentation**: 4 spaces (no tabs).
- **Line Length**: 120 characters preferred.
- **Braces**: K&R style (opening brace on same line).
- **Imports**:
  - Avoid wildcard imports (`*`) unless >5 classes from same package are used (IntelliJ default).
  - Group `com.intellij.*` and `ai.opencode.*` separately.
- **File Encoding**: UTF-8.

### Naming
- **Classes/Objects**: `PascalCase` (e.g., `OpenCodeService`).
- **Functions/Properties**: `camelCase` (e.g., `findAvailablePort`, `serverUrl`).
- **Constants**: `UPPER_SNAKE_CASE` in `companion object` (e.g., `DEFAULT_PORT`).
- **Packages**: `lowercase` (e.g., `ai.opencode.ide.jetbrains`).

### Kotlin Best Practices
- **Immutability**: Prefer `val` over `var`.
- **Null Safety**: Use safe calls (`?.`), elvis operator (`?:`), and `lateinit` responsibly. Avoid `!!`.
- **Singletons**: Use `object` for stateless utilities or singletons.
- **Extension Functions**: Use them to extend platform classes cleanly without inheritance.

## 4. Architecture & Patterns

### Directory Structure
```
src/main/kotlin/
└── ai/opencode/ide/jetbrains/
    ├── OpenCodeToolWindow.kt       # UI Entry point (ToolWindowFactory)
    ├── OpenCodeService.kt          # Main plugin logic (Service)
    ├── actions/                    # AnAction implementations
    ├── listeners/                  # IDE Event Listeners
    ├── terminal/                   # Terminal editor components
    │   ├── OpenCodeTerminalVirtualFile.kt    # Virtual file for terminal tab
    │   ├── OpenCodeTerminalFileEditor.kt     # FileEditor wrapper for terminal
    │   └── OpenCodeTerminalFileEditorProvider.kt  # Provider for terminal editors
    └── util/                       # Utility classes (PortFinder, etc.)
```

### IntelliJ Platform Components
- **Services**: Use Light Services (`@Service`) for logic. Access via `project.service<MyService>()`.
- **UI Threading**:
  - **Read Actions**: `runReadAction { ... }` for reading PSI/project model.
  - **Write Actions**: `runWriteAction { ... }` for modifying PSI/project model.
  - **UI Updates**: Must happen on EDT. Use `invokeLater { ... }`.
  ```kotlin
  ApplicationManager.getApplication().invokeLater {
      // Update UI components here
  }
  ```

### Networking
- **Http Clients**: Use `OkHttp` for external API calls.
- **Proxy**: Use `Proxy.NO_PROXY` for local server connections (localhost/127.0.0.1) to avoid corporate proxy issues.
- **Timeouts**: Always set connection and read timeouts (default ~2000ms for local checks).

### Dynamic Plugin Development
Dynamic plugin loading allows hot reload without IDE restart. Key requirements:

- **Services Must Implement Disposable**: All `@Service` classes MUST implement `Disposable` interface
  ```kotlin
  @Service(Service.Level.PROJECT)
  class MyService(private val project: Project) : Disposable {
      override fun dispose() {
          // Clean up resources, cancel tasks, clear caches
      }
  }
  ```
- **MessageBus Connections**: Always connect to a disposable parent to ensure cleanup
  ```kotlin
  // Correct - connects to service disposable
  project.messageBus.connect(this).subscribe(...)
  
  // Wrong - creates orphaned connection
  project.messageBus.connect().subscribe(...)
  ```
- **Memory Management**: Use `WeakHashMap` for caches to prevent memory leaks during hot reload
- **Resource Cleanup**: Cancel scheduled tasks, close connections, unregister listeners in `dispose()`
- **Extension Points**: Some extension points (like `toolWindow`, `fileEditorProvider`) work with dynamic loading if properly implemented

### Logging
- Use the platform logger:
  ```kotlin
  private val logger = Logger.getInstance(MyClass::class.java)
  logger.info("Initializing OpenCode plugin")
  logger.warn("Failed to connect to server", exception)
  ```

## 5. Error Handling
- **Exceptions**: Catch specific exceptions (`IOException`, `ProcessCanceledException`).
- **User Feedback**: For errors requiring user attention, use `NotificationGroupManager` or `Messages` utility, not just logs.
- **Graceful Failure**: If the backend server is unreachable, the UI should show a "disconnected" state, not crash.

## 6. Testing Strategy
- **Unit Tests**: Test utilities and logic in isolation.
- **Integration Tests**: Use `BasePlatformTestCase` to test interactions with the IDE (PSI, Project model).
- **Location**: Place tests in `src/test/kotlin` mirroring the source package structure.

---
*This file is strictly for AI agents to understand the repository context.*

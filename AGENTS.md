# OpenCode IntelliJ Plugin - Developer Guide for Agents

This repository contains the source code for the OpenCode IntelliJ Platform plugin.
This document provides guidelines for AI agents and developers working on this codebase.

## 1. Project Overview
- **Type**: IntelliJ Platform Plugin
- **Language**: Kotlin (JDK 17)
- **Build System**: Gradle (Kotlin DSL)
- **Target Platform**: IntelliJ IDEA 2024.2+ (Since Build 242)
- **Components**:
  - `src/main/kotlin`: Plugin source code
  - `sdk/js/src`: JS SDK source (reference implementation)
- **Core Dependencies**:
  - IntelliJ Platform SDK
  - OkHttp 4.12.0 (Networking)
  - Gson 2.11.0 (JSON Serialization)

## 2. Build and Verification Commands
Always use the provided Gradle wrapper (`./gradlew`) in the root directory.

### Build & Run
- **Build Project**:
  ```bash
  ./gradlew build
  ```
- **Run IDE (Sandbox)**:
  ```bash
  ./gradlew runIde
  ```
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

## 3. Code Style & Conventions
Follow the official [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html) and [IntelliJ Platform SDK Guidelines](https://plugins.jetbrains.com/docs/intellij/intro.html).

### Formatting & Naming
- **Indentation**: 4 spaces (no tabs).
- **Line Length**: 120 characters preferred.
- **Classes**: `PascalCase` (e.g., `OpenCodeService`).
- **Functions/Properties**: `camelCase` (e.g., `findAvailablePort`).
- **Constants**: `UPPER_SNAKE_CASE` in `companion object`.
- **Packages**: `lowercase` (e.g., `ai.opencode.ide.jetbrains`).
- **Imports**:
  - Avoid wildcard imports (`*`) unless >5 classes from same package.
  - Group `com.intellij.*` and `ai.opencode.*` separately.

### Language Policy
- **English Only**: Comments, documentation, and user-facing strings must be in English.
- **Exceptions**: Non-English allowed only in localized messages or test data.

### Best Practices
- **Immutability**: Prefer `val` over `var`.
- **Null Safety**: Use `?.`, `?:`, and `lateinit` responsibly. Avoid `!!`.
- **Services**: Use `@Service(Service.Level.PROJECT)` for logic.
- **UI Threading**: Updates must happen on EDT (`invokeLater`).
  ```kotlin
  ApplicationManager.getApplication().invokeLater { ... }
  ```

## 4. Architecture & Patterns

### Directory Structure
```
src/main/kotlin/ai/opencode/ide/jetbrains/
├── OpenCodeToolWindow.kt       # UI Entry point
├── OpenCodeService.kt          # Main plugin logic (@Service)
├── api/                        # API Client & Models
├── diff/                       # Diff Viewer implementation
├── session/                    # Session state management
├── terminal/                   # Terminal integration
│   ├── OpenCodeTerminalVirtualFile.kt
│   └── OpenCodeTerminalFileEditorProvider.kt
└── util/                       # Utilities
```

### Key Components
- **Services**: Logic resides in project-level services (`OpenCodeService`, `SessionManager`).
- **Networking**: `OkHttp` used for API calls. Use `Proxy.NO_PROXY` for localhost.
- **Dynamic Loading**: Services must implement `Disposable`. Connect listeners to disposable parents.

### Error Handling
- Catch specific exceptions (`IOException`).
- Use `NotificationGroupManager` or `Messages` for user feedback.
- Log warnings/errors using `Logger.getInstance(...)`.

## 5. Git Workflow
- **Commit Messages**: specific format observed (e.g., `commit: description`).
- **Diff Handling**: The plugin handles diffs via `SessionManager` snapshots to support "Reject" functionality safely.

---
*This file is strictly for AI agents to understand the repository context.*

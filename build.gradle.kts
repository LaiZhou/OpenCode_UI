plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "ai.opencode"
version = "1.0.3"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea("2025.2.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Terminal API
        bundledPlugin("org.jetbrains.plugins.terminal")

    }

    // HTTP client and JSON
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            // No untilBuild - compatible with all future versions
        }

        changeNotes = """
            <h2>1.0.3</h2>
            <ul>
                <li><strong>Dynamic Plugin Support:</strong> Plugin now supports hot reload without IDE restart. All services properly implement Disposable interface and clean up resources on unload.</li>
                <li><strong>Terminal in Editor Tab:</strong> OpenCode terminal now opens in editor area tab instead of bottom panel, providing larger display space for TUI interface.</li>
                <li><strong>Memory Leak Fixes:</strong> Fixed MessageBus connection leaks and static map cleanup issues to prevent memory accumulation during plugin reloads.</li>
                <li><strong>Code Quality:</strong> All source code and comments converted to English, following international development standards.</li>
                <li><strong>Error Handling:</strong> Enhanced error handling with proper user feedback and logging throughout the plugin.</li>
            </ul>
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask> {
        systemProperty("ide.no.platform.update", "true")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

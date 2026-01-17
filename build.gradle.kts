plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "ai.opencode"
version = "1.0.4"

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
            <h2>1.0.4</h2>
            <ul>
                <li><b>Session Auto-Resume:</b> New terminals now automatically load the most recent session history.</li>
                <li><b>Task Notification:</b> Added system notification when AI completes a task (Busy -> Idle).</li>
                <li><b>Stability:</b> Fixed issue where moving the terminal tab caused disconnection.</li>
                <li><b>Diff View:</b> Accept now writes AI output before staging, Reject restores the AI baseline, and Local History is used as fallback.</li>
                <li><b>Diff Alerts:</b> Shows a Local Modified label when disk content differs from AI output.</li>
                <li><b>Input:</b> Improved prompt injection reliability using TUI API.</li>
                <li><b>Links:</b> Added support for clickable file links (@file#Lx-y) in terminal.</li>
                <li><b>Windows Paths:</b> Fixed path separator handling so the Diff panel opens correctly.</li>
                <li><b>Auth:</b> Connection dialog now supports optional OpenCode server password.</li>
                <li><b>Port Detection:</b> Improved logic to avoid suggesting ports that are already in use.</li>
                <li><b>Terminal Tabs:</b> Terminal tab names now include the port (OpenCode(4096)).</li>
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

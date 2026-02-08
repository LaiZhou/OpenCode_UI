plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "ai.opencode"
version = "1.0.9"

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
    
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242"
            // No untilBuild - compatible with all future versions
        }

        changeNotes = """
            <h2>1.0.9</h2>
            <ul>
                <li>Fix: clean opencode process when exit or idle timeout.</li>
                <li>Fix: in Project View Add to OpenCode Terminal, directory selections now send the directory path instead of all child file paths.</li>
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

    // Disable buildSearchableOptions as it is flaky and often fails
    named("buildSearchableOptions") {
        enabled = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

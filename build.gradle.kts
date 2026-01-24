plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "ai.opencode"
version = "1.0.6"

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
            <h2>1.0.6</h2>
            <ul>
              <li>Fix: Strict turn isolation to prevent "ghost diffs" from previous turns appearing in new messages.</li>
              <li>Fix: Robust handling of file deletions (Improved Rescue logic with LocalHistory fallback).</li>
              <li>Fix: Ensured diffs for basic modifications appear even if server signals are delayed.</li>
              <li>Fix: Resolved missing diffs for new files by trusting Server API signals.</li>
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

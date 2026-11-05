plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.2"
}

group = "ai.opencode"
version = "1.1.17"

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
            <h2>1.1.17</h2>
            <ul>
                <li>Fix: the "quiet" window now covers the whole session tree and is 20 seconds. The field log shows an announcement while a child session had just finished and the parent was about to resume: the child's last event was 0.2s before the announcement and the next parent message arrived 0.6s later.</li>
            </ul>
            <h2>1.1.16</h2>
            <ul>
                <li>Fix: closing one project no longer disconnects OpenCode in another project. Project teardown used to dispose every registered terminal (clearAll) and stop its pty process; the field log shows a second project's OpenCode logging "disposing all instances" 230ms after the first project closed. Teardown now only drops the plugin's own registrations and never kills a process.</li>
                <li>Note: re-applies the project-scoped terminal teardown that was missing from the 1.1.3-1.1.15 builds.</li>
            </ul>
            <h2>1.1.15</h2>
            <ul>
                <li>Fix: sub-agent sessions no longer trigger completion notifications. Sub-agents run as child sessions (parentID set) and finish while the main session keeps working, which produced several popups and sounds mid-turn.</li>
            </ul>
            <h2>1.1.14</h2>
            <ul>
                <li>Fix: completion now also requires 12 seconds without any SSE activity for that session (status, message and part updates). OpenCode persists a message slightly after the agent resumes, so a check could see the previous, finished message and announce while the next one was already streaming.</li>
            </ul>
            <h2>1.1.13</h2>
            <ul>
                <li>Fix: a pending completion announcement is dropped when a newer assistant message appears for the same session before the announcement fires, closing the last step-boundary race.</li>
                <li>Diagnostics: completion logs now carry the session id so announcements can be matched to the session they belong to (especially with several OpenCode sessions open).</li>
            </ul>
            <h2>1.1.12</h2>
            <ul>
                <li>Fix: the completion announcement now waits 12 seconds after the session reports idle (was 4), so it no longer overlaps PyCharm's own post-turn activity (VFS refresh, highlighting, indexing) that keeps the status-bar progress alive after OpenCode is done.</li>
                <li>Diagnostics: the completion gate logs "Completion gating v12 active" once, so the running build can be verified from idea.log.</li>
            </ul>
            <h2>1.1.11</h2>
            <ul>
                <li>Diagnostics: the completion gate logs its version once per connection ("Completion gating v11 active") so the running build can be verified from idea.log.</li>
            </ul>
            <h2>1.1.10</h2>
            <ul>
                <li>Fix: a turn whose last step finished with reason "tool-calls" is treated as still running, which is how OpenCode itself marks an unfinished turn.</li>
                <li>Fix: completion is also deferred while the IDE is indexing (DumbService), so the announcement no longer overlaps PyCharm's own progress bar after the agent rewrites files.</li>
            </ul>
            <h2>1.1.9</h2>
            <ul>
                <li>Fix: manually interrupted turns are no longer announced as completed. When the newest assistant message carries an abort error (MessageAbortedError), the completion notification and sound are suppressed for that turn.</li>
            </ul>
            <h2>1.1.8</h2>
            <ul>
                <li>Fix: completion is now verified against the newest assistant message. A missing completion timestamp, or a pending/running tool part, means the turn is still running; the message must also stay unchanged across one re-check. This removes the remaining "completed" announcements that fired while work was still in progress.</li>
            </ul>
            <h2>1.1.7</h2>
            <ul>
                <li>Fix: the "diff not opened automatically" warning is now only shown when there is something to review. A batch that only deletes oversized files stays silent (logged), and the notification suggests git instead of a file that no longer exists.</li>
                <li>New: that notification has an "Open anyway" action, so an oversized diff can still be opened deliberately.</li>
            </ul>
            <h2>1.1.6</h2>
            <ul>
                <li>Fix: completion is now judged by the whole session tree instead of the parent session alone. While any child session (background sub-agent) is still busy or retrying, the announcement is deferred and re-checked every 2 seconds, so "task completed" no longer fires while agents are still running.</li>
            </ul>
            <h2>1.1.5</h2>
            <ul>
                <li>Fix: "task completed" announcements. Completion is now announced only after the session stays idle for 4 seconds, once per turn, and pending announcements are cancelled as soon as new work starts — no more premature or repeated notifications.</li>
                <li>New: completion also plays a system sound, so it is audible even when the IDE is in the background or system notification sounds are off.</li>
            </ul>
            <h2>1.1.4</h2>
            <ul>
                <li>Fix: Diffs larger than 1 MB per file (or 3 MB in total) are no longer opened automatically. Opening very large diffs freezes the editor for tens of seconds while IDE folding and highlighting run; a notification now names the file so it can be reviewed manually.</li>
                <li>Fix: Synthetic diff generation no longer reads files larger than 8 MB into memory.</li>
            </ul>
            <h2>1.1.3</h2>
            <ul>
                <li>Fix: Out of memory / IDE freeze in long sessions. The SSE response body is now always closed, oversized event lines are dropped instead of buffered, reconnects back off exponentially (3s to 60s), and stale callbacks can no longer reschedule a reconnect.</li>
                <li>Fix: Before-state capture now skips files above 1 MB, binary files and VCS-ignored paths, and caps each turn at 64 MB. Known-state cache entries are bounded (5000 entries) and removed when a file is deleted.</li>
                <li>Fix: Per-turn snapshot and diff payload state is released when the turn finishes, instead of being retained until the next turn of the same session starts.</li>
            </ul>
            <h2>1.1.0</h2>
            <ul>
                <li>New: Custom base path support — added a "Custom base path" dropdown in the connection dialog, allowing users to specify a working directory for OpenCode terminal sessions. Automatically populated with detected project modules.</li>
                <li>Fix: Fixed the OpenCode CLI install command in README to match official download instructions.</li>
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

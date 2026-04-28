plugins {
    kotlin("multiplatform") version "2.2.20" apply false
    kotlin("plugin.serialization") version "2.2.20" apply false
    kotlin("plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.compose") version "1.9.0" apply false
    // TODO restore once code-structure plugin is published for the version this build uses.
    //   The Gradle plugin marker for com.seanshubin.code.structure is not available in
    //   maven-local on this machine; source lives at D:\keep\github\sean\code-structure but
    //   has not yet been ported to Windows.
    // id("com.seanshubin.code.structure") version "1.1.1"
}

allprojects {
    group = "com.seanshubin.vote"
    version = "1.0.0-SNAPSHOT"

    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
            freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
    }
}

// Stand-in for the analyzeCodeStructure task contributed by
// com.seanshubin.code.structure. Other modules (notably :documentation) depend on
// this task by name. Keep this no-op until the plugin can be re-enabled.
tasks.register("analyzeCodeStructure") {
    group = "verification"
    description = "No-op placeholder. Reinstate the com.seanshubin.code.structure plugin to perform real analysis."
    doLast {
        logger.info("analyzeCodeStructure: skipped (plugin disabled in this checkout)")
    }
}

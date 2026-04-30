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

// Code-structure analysis: invoked as a plain executable JAR pulled from
// Maven Central, rather than through the (Windows-port-blocked) Gradle plugin.
// Source: https://github.com/SeanShubin/code-structure
val codeStructureClasspath: Configuration = configurations.create("codeStructureClasspath")

dependencies {
    codeStructureClasspath("com.seanshubin.code.structure:code-structure-console:1.1.2")
}

tasks.register<JavaExec>("analyzeCodeStructure") {
    group = "verification"
    description = "Analyze dependency structure (cycles, naming, layering) — see generated/code-structure/."
    classpath = codeStructureClasspath
    mainClass.set("com.seanshubin.code.structure.console.EntryPoint")
    workingDir = rootDir
    // Reads code-structure-config.json from the working directory; outputs to generated/code-structure/.
    outputs.dir(layout.projectDirectory.dir("generated/code-structure"))
    inputs.file(layout.projectDirectory.file("code-structure-config.json"))

    // The analyzer reads compiled .class files. Make sure all JVM modules
    // are built before scanning. (Frontend's pure-JS target is naturally
    // skipped since it produces no .class output.)
    subprojects.forEach { sub ->
        sub.tasks.findByName("classes")?.let { dependsOn(it) }
        sub.tasks.findByName("jvmMainClasses")?.let { dependsOn(it) }
    }
}

// ── Markdown table padding ─────────────────────────────────────────────────
// Algorithm and CLI live in :tools (vote-dev pad-tables). These root tasks are
// thin Gradle entry points; the pre-commit hook calls the same CLI.
val toolsRuntime: Configuration = configurations.create("toolsRuntime") {
    isCanBeResolved = true
    isCanBeConsumed = false
}
dependencies {
    toolsRuntime(project(":tools"))
}

fun padTablesExec(taskName: String, vararg cliArgs: String) =
    tasks.register<JavaExec>(taskName) {
        classpath = toolsRuntime
        mainClass.set("com.seanshubin.vote.tools.app.MainKt")
        args = listOf("pad-tables") + cliArgs.toList()
    }

padTablesExec("padMarkdownTables", "--root", rootDir.absolutePath).configure {
    group = "documentation"
    description = "Rewrites .md files in place so markdown tables have aligned columns."
}

padTablesExec("checkMarkdownTables", "--check", "--root", rootDir.absolutePath).configure {
    group = "verification"
    description = "Lists .md files whose tables are not column-aligned. Non-zero exit if any."
}

tasks.register("installGitHooks") {
    group = "documentation"
    description = "Installs the pre-commit hook that pads markdown tables on commit."
    val hookSource = rootDir.resolve("scripts/git-hooks/pre-commit")
    val hookTarget = rootDir.resolve(".git/hooks/pre-commit")
    inputs.file(hookSource)
    outputs.file(hookTarget)
    dependsOn(":tools:installDist")
    doLast {
        hookTarget.parentFile.mkdirs()
        hookSource.copyTo(hookTarget, overwrite = true)
        hookTarget.setExecutable(true)
        logger.lifecycle("Installed pre-commit hook at ${hookTarget.relativeTo(rootDir)}")
    }
}

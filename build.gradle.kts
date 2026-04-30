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

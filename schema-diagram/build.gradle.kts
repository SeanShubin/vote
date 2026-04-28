import org.gradle.process.ExecOperations
import javax.inject.Inject

plugins {
    kotlin("jvm")
    application
}

interface InjectedExecOps {
    @get:Inject val execOps: ExecOperations
}

dependencies {
    implementation(kotlin("stdlib"))
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.seanshubin.vote.schema.MainKt")
}

// Task to generate schema diagram
tasks.register("generateSchemaDiagram") {
    description = "Generate relational model diagram from schema.sql"
    group = "documentation"
    dependsOn("compileKotlin")

    val schemaFile = file("../backend/src/main/resources/database/schema.sql")
    val outputDir = file("../generated/schema-diagram")
    val runtimeClasspath = sourceSets["main"].runtimeClasspath
    val execOps = project.objects.newInstance<InjectedExecOps>().execOps

    inputs.file(schemaFile)
    outputs.dir(outputDir)

    doLast {
        if (!schemaFile.exists()) {
            throw GradleException("Schema file not found: ${schemaFile.absolutePath}")
        }

        outputDir.mkdirs()

        // First pass: generate .dot and .mmd files
        execOps.javaexec {
            classpath = runtimeClasspath
            mainClass.set("com.seanshubin.vote.schema.MainKt")
            args = listOf(
                schemaFile.absolutePath,
                outputDir.absolutePath
            )
        }

        // Try to render SVG if dot is available
        val dotFile = file("$outputDir/schema.dot")
        val svgFile = file("$outputDir/schema.svg")

        if (dotFile.exists()) {
            try {
                execOps.exec {
                    commandLine("dot", "-Tsvg", dotFile.absolutePath, "-o", svgFile.absolutePath)
                    isIgnoreExitValue = true
                }
                if (svgFile.exists()) {
                    println("✓ Generated SVG diagram: ${svgFile.absolutePath}")

                    // Second pass: regenerate HTML with embedded SVG
                    execOps.javaexec {
                        classpath = runtimeClasspath
                        mainClass.set("com.seanshubin.vote.schema.MainKt")
                        args = listOf(
                            schemaFile.absolutePath,
                            outputDir.absolutePath
                        )
                    }
                } else {
                    println("⚠ GraphViz 'dot' command not found. Install GraphViz to generate SVG.")
                }
            } catch (e: Exception) {
                println("⚠ Could not render SVG: ${e.message}")
            }
        }
    }
}

// Make generateSchemaDiagram part of the build process
tasks.named("build") {
    dependsOn("generateSchemaDiagram")
}

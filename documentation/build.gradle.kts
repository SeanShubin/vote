plugins {
    kotlin("jvm")
    application
    kotlin("plugin.serialization")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation(project(":domain"))
    implementation(project(":contract"))
    implementation(project(":backend"))

    // Access integration test classes (TestContext, DatabaseProvider, etc.)
    implementation(project(":integration", "testArchive"))

    // For database access
    implementation("com.mysql:mysql-connector-j:${project.property("mysql.connector.version")}")
    implementation("aws.sdk.kotlin:dynamodb:${project.property("aws.sdk.kotlin.version")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${project.property("kotlinx.coroutines.version")}")

    // For TestContainers
    implementation("org.testcontainers:testcontainers:${project.property("testcontainers.version")}")
    implementation("org.testcontainers:mysql:${project.property("testcontainers.version")}")
    implementation("org.testcontainers:localstack:${project.property("testcontainers.version")}")

    // For JSON serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${project.property("kotlinx.serialization.version")}")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:${project.property("kotlinx.datetime.version")}")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.seanshubin.vote.documentation.MainKt")
}

// Task to generate documentation
tasks.register("generateDocumentation") {
    description = "Generate sample data documentation (SQL, DynamoDB, Events, HTTP)"
    group = "documentation"
    dependsOn("compileKotlin")

    val outputDir = file("../generated/documentation")

    outputs.dir(outputDir)

    doLast {
        outputDir.mkdirs()

        javaexec {
            classpath = sourceSets["main"].runtimeClasspath
            mainClass.set("com.seanshubin.vote.documentation.MainKt")
            args = listOf(outputDir.absolutePath)

            // Docker configuration for TestContainers
            environment("DOCKER_HOST", "unix:///Users/seashubi/.colima/default/docker.sock")
            environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
        }

        println("✓ Generated documentation in: ${outputDir.absolutePath}")
    }
}

// Make generateDocumentation part of the build process
tasks.named("build") {
    dependsOn("generateDocumentation")
}

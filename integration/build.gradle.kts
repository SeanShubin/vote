plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(project(":backend"))
    testImplementation(project(":domain"))
    testImplementation(project(":contract"))

    // Kotlinx
    testImplementation("org.jetbrains.kotlinx:kotlinx-datetime:${project.property("kotlinx.datetime.version")}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${project.property("kotlinx.serialization.version")}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${project.property("kotlinx.coroutines.version")}")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:${project.property("junit.version")}")

    // Test databases
    testImplementation("com.h2database:h2:${project.property("h2.version")}")
    testImplementation("org.testcontainers:testcontainers:${project.property("testcontainers.version")}")
    testImplementation("org.testcontainers:testcontainers-mysql:${project.property("testcontainers.version")}")
    testImplementation("org.testcontainers:testcontainers-localstack:${project.property("testcontainers.version")}")
    testImplementation("com.mysql:mysql-connector-j:${project.property("mysql.connector.version")}")
    testImplementation("aws.sdk.kotlin:dynamodb:${project.property("aws.sdk.kotlin.version")}")
}

tasks.test {
    useJUnitPlatform()
    // Docker configuration for TestContainers. Honor explicit overrides if
    // present, otherwise let testcontainers auto-detect (works with Docker
    // Desktop on Windows + macOS, and the standard socket on Linux).
    System.getenv("DOCKER_HOST")?.let { environment("DOCKER_HOST", it) }
    System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE")?.let {
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", it)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

// Expose test classes to other modules (for documentation generator)
val testArchive by configurations.creating
configurations.getByName("default").extendsFrom(testArchive)
val testJar by tasks.registering(Jar::class) {
    archiveClassifier.set("tests")
    from(sourceSets["test"].output)
}
artifacts {
    add("testArchive", testJar)
}

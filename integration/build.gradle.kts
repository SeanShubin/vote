plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(project(":backend"))
    testImplementation(project(":domain"))
    testImplementation(project(":contract"))

    // Kotlinx
    testImplementation("org.jetbrains.kotlinx:kotlinx-datetime:${project.property("kotlinx.datetime.version")}")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:${project.property("junit.version")}")

    // Test databases
    testImplementation("com.h2database:h2:${project.property("h2.version")}")
    testImplementation("org.testcontainers:testcontainers:${project.property("testcontainers.version")}")
    testImplementation("org.testcontainers:localstack:${project.property("testcontainers.version")}")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

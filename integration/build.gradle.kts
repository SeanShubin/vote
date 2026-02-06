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
    testImplementation("org.testcontainers:mysql:${project.property("testcontainers.version")}")
    testImplementation("org.testcontainers:localstack:${project.property("testcontainers.version")}")
    testImplementation("com.mysql:mysql-connector-j:${project.property("mysql.connector.version")}")
    testImplementation("aws.sdk.kotlin:dynamodb:${project.property("aws.sdk.kotlin.version")}")
}

tasks.test {
    useJUnitPlatform()
    // Configure TestContainers to use Colima Docker socket
    environment("DOCKER_HOST", "unix:///Users/seashubi/.colima/default/docker.sock")
    environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

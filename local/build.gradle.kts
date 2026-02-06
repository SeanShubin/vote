plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":backend"))
    implementation(project(":domain"))
    implementation(project(":contract"))

    // Testcontainers for local DynamoDB and MySQL
    implementation("org.testcontainers:testcontainers:${project.property("testcontainers.version")}")
    implementation("org.testcontainers:mysql:${project.property("testcontainers.version")}")
    implementation("org.testcontainers:localstack:${project.property("testcontainers.version")}")

    // H2 for local development
    implementation("com.h2database:h2:${project.property("h2.version")}")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.seanshubin.vote.local.LocalMainKt")
}

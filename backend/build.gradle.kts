plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
    id("com.gradleup.shadow") version "9.0.0"
}

dependencies {
    // Project modules
    implementation(project(":domain"))
    implementation(project(":contract"))

    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${project.property("kotlinx.serialization.version")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${project.property("kotlinx.coroutines.version")}")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:${project.property("kotlinx.datetime.version")}")

    // Jetty HTTP server
    implementation("org.eclipse.jetty:jetty-server:${project.property("jetty.version")}")
    implementation("org.eclipse.jetty:jetty-servlet:${project.property("jetty.version")}")

    // Database - MySQL
    implementation("mysql:mysql-connector-java:${project.property("mysql.connector.version")}")
    implementation("com.h2database:h2:${project.property("h2.version")}")

    // Database - DynamoDB
    implementation("aws.sdk.kotlin:dynamodb:${project.property("aws.sdk.kotlin.version")}")

    // Email - AWS SES (Lambda role uses ses:SendEmail; no SMTP credentials needed)
    implementation("aws.sdk.kotlin:ses:${project.property("aws.sdk.kotlin.version")}")

    // SSM Parameter Store - fetch the rotating invite code without redeploying.
    implementation("aws.sdk.kotlin:ssm:${project.property("aws.sdk.kotlin.version")}")

    // JWT/Auth
    implementation("com.auth0:java-jwt:4.4.0")

    // AWS Lambda runtime (used when packaged as a Lambda fat JAR)
    implementation("com.amazonaws:aws-lambda-java-core:1.2.3")
    implementation("com.amazonaws:aws-lambda-java-events:3.15.0")

    // Email
    implementation("javax.mail:mail:1.4.7")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("org.slf4j:slf4j-api:2.0.9")
    // Structured JSON layout for CloudWatch Logs Insights field queries.
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:${project.property("junit.version")}")
    testImplementation("org.testcontainers:testcontainers:${project.property("testcontainers.version")}")
    testImplementation("org.testcontainers:testcontainers-mysql:${project.property("testcontainers.version")}")
    testImplementation("org.testcontainers:testcontainers-localstack:${project.property("testcontainers.version")}")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.seanshubin.vote.backend.app.MainKt")
}

tasks.shadowJar {
    archiveBaseName.set("vote-backend-lambda")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":contract"))
    implementation(project(":backend"))

    implementation("com.github.ajalt.clikt:clikt:5.0.2")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${project.property("kotlinx.serialization.version")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${project.property("kotlinx.coroutines.version")}")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:${project.property("kotlinx.datetime.version")}")

    implementation("org.eclipse.jetty:jetty-server:${project.property("jetty.version")}")
    implementation("org.eclipse.jetty:jetty-servlet:${project.property("jetty.version")}")

    implementation("mysql:mysql-connector-java:${project.property("mysql.connector.version")}")

    implementation("aws.sdk.kotlin:dynamodb:${project.property("aws.sdk.kotlin.version")}")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:${project.property("junit.version")}")
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
    mainClass.set("com.seanshubin.vote.tools.app.MainKt")
    applicationName = "vote-dev"
}

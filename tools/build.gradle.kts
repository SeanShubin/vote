import java.util.regex.Matcher

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
    implementation("aws.sdk.kotlin:ssm:${project.property("aws.sdk.kotlin.version")}")

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

// Windows CMD caps a command line at ~8KB. Gradle's default startScripts
// expands every dependency JAR onto the CLASSPATH line; with ~80 deps and a
// long worktree path, the expansion blows past the limit ("input line is too
// long"). The JVM itself supports `dir/*` as a classpath entry, so we emit
// that and keep the shell command short regardless of dependency count.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val winReplacement = Matcher.quoteReplacement(
            "set CLASSPATH=%APP_HOME%\\lib\\*"
        )
        windowsScript.writeText(
            windowsScript.readText().replace(
                Regex("(?m)^set CLASSPATH=.*$"),
                winReplacement,
            )
        )
        val unixReplacement = Matcher.quoteReplacement(
            "CLASSPATH=\$APP_HOME/lib/*"
        )
        unixScript.writeText(
            unixScript.readText().replace(
                Regex("(?m)^CLASSPATH=.*$"),
                unixReplacement,
            )
        )
    }
}

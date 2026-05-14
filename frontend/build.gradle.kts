plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    js(IR) {
        browser {
            commonWebpackConfig {
                cssSupport {
                    enabled.set(true)
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(project(":domain"))
                implementation(project(":contract"))

                // Compose for Web
                implementation(compose.html.core)
                implementation(compose.runtime)

                // Kotlin
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${project.property("kotlinx.serialization.version")}")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${project.property("kotlinx.coroutines.version")}")
            }
        }

        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${project.property("kotlinx.coroutines.version")}")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:${project.property("kotlinx.datetime.version")}")
            }
        }
    }
}

// Generates BuildConfig.kt with the API base URL from gradle.properties.
// Override with `-Papi.base.url=https://api.pairwisevote.com` for production builds.
val generatedSrcDir = layout.buildDirectory.dir("generated/buildConfig/jsMain/kotlin")

val generateBuildConfig by tasks.registering {
    val apiBaseUrl = (project.findProperty("api.base.url") as? String) ?: "http://localhost:8080"
    val gitHash = (project.findProperty("git.hash") as? String) ?: "dev"
    val outputDir = generatedSrcDir
    inputs.property("apiBaseUrl", apiBaseUrl)
    inputs.property("gitHash", gitHash)
    outputs.dir(outputDir)
    doLast {
        val pkgDir = outputDir.get().asFile.resolve("com/seanshubin/vote/frontend")
        pkgDir.mkdirs()
        pkgDir.resolve("BuildConfig.kt").writeText(
            """
            |package com.seanshubin.vote.frontend
            |
            |internal object BuildConfig {
            |    const val API_BASE_URL: String = "$apiBaseUrl"
            |    const val GIT_HASH: String = "$gitHash"
            |}
            |
            """.trimMargin()
        )
    }
}

kotlin.sourceSets.named("jsMain") {
    kotlin.srcDir(generateBuildConfig)
}

// Cache busting for frontend.js + styles.css. Runs after jsBrowserDistribution
// has assembled the dist directory, so the modified index.html survives the
// bundle copy. Both assets get the same timestamp so they update in lockstep —
// otherwise users see new JS rendering against a stale cached CSS.
val addCacheBusting by tasks.registering {
    description = "Add cache busting query parameters to frontend.js and styles.css references"
    dependsOn("jsBrowserDistribution")

    val htmlSource = file("src/jsMain/resources/index.html")
    val htmlDest = file("build/dist/js/productionExecutable/index.html")

    inputs.file(htmlSource)
    outputs.file(htmlDest)

    doLast {
        val buildTimestamp = System.currentTimeMillis()
        val html = htmlSource.readText()
        // Absolute paths (leading slash) are required so deep-URL refreshes
        // resolve assets against the SPA root, not the deep path's parent.
        val modifiedHtml = html
            .replace(
                """<script src="/frontend.js"></script>""",
                """<script src="/frontend.js?v=$buildTimestamp"></script>"""
            )
            .replace(
                """<link rel="stylesheet" href="/styles.css">""",
                """<link rel="stylesheet" href="/styles.css?v=$buildTimestamp">"""
            )
        htmlDest.writeText(modifiedHtml)
        println("✓ Cache busting added: frontend.js?v=$buildTimestamp + styles.css?v=$buildTimestamp")
    }
}

tasks.named("assemble") {
    dependsOn(addCacheBusting)
}

// The jsTest source set currently holds only test helpers (FakeApiClient,
// ComposeTestHelper) — the @Test classes were removed with the Discord-only
// login change. Gradle 9 fails a test task that discovers zero tests, so
// allow it until frontend tests are re-added against the helpers.
tasks.named<AbstractTestTask>("jsBrowserTest") {
    failOnNoDiscoveredTests = false
}

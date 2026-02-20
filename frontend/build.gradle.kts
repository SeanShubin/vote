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

// Cache busting for frontend.js
tasks.register("addCacheBusting") {
    description = "Add cache busting query parameter to frontend.js reference"

    val htmlSource = file("src/jsMain/resources/index.html")
    val htmlDest = file("build/dist/js/productionExecutable/index.html")

    inputs.file(htmlSource)
    outputs.file(htmlDest)

    doLast {
        val buildTimestamp = System.currentTimeMillis()
        val html = htmlSource.readText()
        val modifiedHtml = html.replace(
            """<script src="frontend.js"></script>""",
            """<script src="frontend.js?v=$buildTimestamp"></script>"""
        )
        htmlDest.writeText(modifiedHtml)
        println("✓ Cache busting added: frontend.js?v=$buildTimestamp")
    }
}

// Run after webpack builds the production bundle
tasks.named("jsBrowserProductionWebpack") {
    finalizedBy("addCacheBusting")
}

// Also run after the build task to ensure it runs in incremental builds
tasks.named("build") {
    finalizedBy("addCacheBusting")
}

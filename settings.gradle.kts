pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "vote"

include(
    ":domain",
    ":contract",
    ":backend",
    ":frontend",
    ":deploy",
    ":local",
    ":integration",
    ":schema-diagram",
    ":documentation"
)

package com.seanshubin.vote.tools.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

class CheckJava : CliktCommand(name = "check-java") {
    override fun help(context: Context) = "Print Java configuration and verify the runtime version."

    override fun run() {
        val expected = "21"
        val javaVersion = System.getProperty("java.version") ?: "unknown"
        val javaVendor = System.getProperty("java.vendor") ?: "unknown"
        val javaHome = System.getenv("JAVA_HOME") ?: System.getProperty("java.home") ?: "not set"
        val majorVersion = javaVersion.substringBefore('.')

        println("Checking Java configuration...")
        println()
        println("Expected: Java $expected (per .tool-versions)")
        println("Current:  Java $javaVersion ($javaVendor)")
        println("JAVA_HOME: $javaHome")
        println()

        if (majorVersion == expected) {
            println("[OK] Java version matches.")
        } else {
            println("WARNING: Java major version $majorVersion does not match expected $expected.")
        }
    }
}

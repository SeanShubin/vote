package com.seanshubin.vote.tools.lib

object Output {
    fun banner(title: String) {
        val line = "=".repeat(42)
        println(line)
        println(title)
        println(line)
        println()
    }

    fun section(title: String) {
        println()
        println("=== $title ===")
        println()
    }

    fun step(message: String) {
        println(message)
    }

    fun success(message: String) {
        println("[OK] $message")
    }

    fun warn(message: String) {
        System.err.println("WARNING: $message")
    }

    fun error(message: String): Nothing {
        System.err.println("ERROR: $message")
        kotlin.system.exitProcess(1)
    }
}

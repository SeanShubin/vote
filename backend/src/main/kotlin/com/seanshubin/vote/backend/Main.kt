package com.seanshubin.vote.backend

import com.seanshubin.vote.backend.dependencies.ApplicationDependencies

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 8080
    val app = ApplicationDependencies(port)
    app.start()
}

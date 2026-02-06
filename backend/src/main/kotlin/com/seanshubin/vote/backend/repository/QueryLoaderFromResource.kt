package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.QueryLoader

class QueryLoaderFromResource : QueryLoader {
    override fun load(name: String): String {
        val resourceName = "database/$name.sql"
        val classLoader = this.javaClass.classLoader
        val inputStream = classLoader.getResourceAsStream(resourceName)
            ?: error("Query resource not found: $resourceName")
        return inputStream.bufferedReader().use { it.readText() }
    }
}

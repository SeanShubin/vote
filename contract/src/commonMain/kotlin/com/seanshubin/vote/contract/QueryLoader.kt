package com.seanshubin.vote.contract

interface QueryLoader {
    fun load(name: String): String
}

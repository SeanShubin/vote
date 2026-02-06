package com.seanshubin.vote.contract

import kotlinx.datetime.Instant

interface Clock {
    fun now(): Instant
}

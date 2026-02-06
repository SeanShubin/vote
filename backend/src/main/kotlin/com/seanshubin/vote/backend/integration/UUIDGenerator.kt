package com.seanshubin.vote.backend.integration

import com.seanshubin.vote.contract.UniqueIdGenerator
import java.util.*

object UUIDGenerator : UniqueIdGenerator {
    override fun generate(): String = UUID.randomUUID().toString()
}

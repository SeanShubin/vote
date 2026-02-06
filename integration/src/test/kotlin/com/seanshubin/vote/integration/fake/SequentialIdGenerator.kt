package com.seanshubin.vote.integration.fake

import com.seanshubin.vote.contract.UniqueIdGenerator

class SequentialIdGenerator : UniqueIdGenerator {
    private var counter = 0

    override fun generate(): String {
        return "id-${++counter}"
    }
}

package com.seanshubin.vote.backend.repository

import com.seanshubin.vote.contract.SystemSettings
import com.seanshubin.vote.domain.FeatureFlag

class InMemorySystemSettings : SystemSettings {
    private val flags = mutableMapOf<FeatureFlag, Boolean>()

    override fun isEnabled(flag: FeatureFlag): Boolean =
        flags[flag] ?: flag.defaultEnabled

    override fun setEnabled(flag: FeatureFlag, enabled: Boolean) {
        flags[flag] = enabled
    }

    override fun listAll(): Map<FeatureFlag, Boolean> =
        FeatureFlag.entries.associateWith { isEnabled(it) }
}

package com.seanshubin.vote.backend.dependencies

import com.seanshubin.vote.contract.Integrations

class BootstrapDependencies(
    integrations: Integrations
) {
    val bootstrap: Bootstrap = Bootstrap(integrations)
}

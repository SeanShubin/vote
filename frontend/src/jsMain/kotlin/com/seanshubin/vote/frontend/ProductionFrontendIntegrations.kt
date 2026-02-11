package com.seanshubin.vote.frontend

import com.seanshubin.vote.contract.ApiClient
import com.seanshubin.vote.contract.FrontendIntegrations

class ProductionFrontendIntegrations(
    baseUrl: String = "http://localhost:8080"
) : FrontendIntegrations {
    override val apiClient: ApiClient = HttpApiClient(baseUrl)
}

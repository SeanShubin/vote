package com.seanshubin.vote.backend.integration

import com.seanshubin.vote.contract.DeployManifest
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Reads the deploy pipeline's `deployed-version.json` over HTTPS from the
 * frontend origin — it is a plain public asset in the frontend S3 bucket,
 * served through CloudFront.
 *
 * HTTPS rather than the S3 SDK on purpose: it keeps the Lambda free of an
 * S3 dependency and an extra IAM grant, and the deploy already invalidates
 * CloudFront, so the fetched copy is fresh after every deploy.
 *
 * Every failure mode collapses to null ("no manifest available"): a network
 * error, a non-200, or a body that isn't the manifest JSON — note that a
 * missing object is turned into a 200 of index.html by the SPA-fallback
 * rule, so a non-JSON 200 must also be treated as "absent".
 */
class HttpDeployManifestReader(
    private val frontendBaseUrl: String,
    private val httpClient: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun read(): DeployManifest? =
        try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$frontendBaseUrl/deployed-version.json"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                null
            } else {
                json.decodeFromString<DeployManifest>(response.body())
            }
        } catch (e: Exception) {
            null
        }
}

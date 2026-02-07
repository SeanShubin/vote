package com.seanshubin.vote.documentation

class HttpHtmlGenerator(private val exchanges: List<HttpExchange>) {
    fun generate(): String = buildString {
        appendLine("<!DOCTYPE html>")
        appendLine("<html>")
        appendLine("<head>")
        appendLine("  <meta charset=\"UTF-8\">")
        appendLine("  <title>HTTP API Documentation</title>")
        appendLine("  <style>")
        appendLine(css())
        appendLine("  </style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("  <h1>HTTP API Documentation</h1>")
        appendLine("  <p class=\"description\">Complete HTTP request/response sequence from the comprehensive scenario.</p>")
        appendLine("  <p class=\"description\">Shows actual API usage with real data.</p>")
        appendLine()

        appendLine("  <div class=\"exchanges\">")
        for ((index, exchange) in exchanges.withIndex()) {
            appendLine(generateExchange(index + 1, exchange))
        }
        appendLine("  </div>")

        appendLine("</body>")
        appendLine("</html>")
    }

    private fun generateExchange(number: Int, exchange: HttpExchange): String = buildString {
        val methodColor = getMethodColor(exchange.method)
        val statusColor = getStatusColor(exchange.responseStatus)

        appendLine("    <div class=\"exchange\">")
        appendLine("      <div class=\"exchange-header\">")
        appendLine("        <span class=\"exchange-number\">#$number</span>")
        appendLine("        <span class=\"method\" style=\"background-color: $methodColor;\">${exchange.method}</span>")
        appendLine("        <span class=\"path\">${escapeHtml(exchange.path)}</span>")
        appendLine("        <span class=\"status\" style=\"color: $statusColor;\">${exchange.responseStatus}</span>")
        appendLine("      </div>")

        appendLine("      <div class=\"exchange-body\">")

        // Request section
        appendLine("        <div class=\"section\">")
        appendLine("          <h3>Request</h3>")

        // Request headers
        val importantHeaders = exchange.requestHeaders.filter {
            it.key in listOf("Content-Type", "Authorization")
        }
        if (importantHeaders.isNotEmpty()) {
            appendLine("          <div class=\"headers\">")
            for ((name, values) in importantHeaders) {
                val displayValue = if (name == "Authorization") {
                    "Bearer {...}"
                } else {
                    values.joinToString(", ")
                }
                appendLine("            <div class=\"header\"><span class=\"header-name\">$name:</span> $displayValue</div>")
            }
            appendLine("          </div>")
        }

        // Request body
        if (exchange.requestBody != null) {
            appendLine("          <div class=\"body-label\">Body:</div>")
            appendLine("          <pre class=\"body\">${formatJson(exchange.requestBody)}</pre>")
        }

        appendLine("        </div>")

        // Response section
        appendLine("        <div class=\"section\">")
        appendLine("          <h3>Response</h3>")

        // Response body
        appendLine("          <pre class=\"body\">${formatJson(exchange.responseBody)}</pre>")

        appendLine("        </div>")

        appendLine("      </div>")
        appendLine("    </div>")
    }

    private fun formatJson(text: String): String {
        return try {
            // Already formatted if from Json { prettyPrint = true }
            escapeHtml(text)
        } catch (e: Exception) {
            escapeHtml(text)
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private fun getMethodColor(method: String): String = when (method) {
        "GET" -> "#2ecc71"
        "POST" -> "#3498db"
        "PUT" -> "#f39c12"
        "DELETE" -> "#e74c3c"
        else -> "#95a5a6"
    }

    private fun getStatusColor(status: Int): String = when {
        status in 200..299 -> "#2ecc71"
        status in 300..399 -> "#3498db"
        status in 400..499 -> "#f39c12"
        status >= 500 -> "#e74c3c"
        else -> "#95a5a6"
    }

    private fun css() = """
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            max-width: 1200px;
            margin: 0 auto;
            padding: 20px;
            background-color: #f5f5f5;
        }
        h1 {
            color: #333;
            border-bottom: 3px solid #3498db;
            padding-bottom: 10px;
        }
        h3 {
            color: #555;
            margin: 0 0 15px 0;
            font-size: 14px;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }
        .description {
            color: #666;
            font-size: 14px;
            margin-bottom: 10px;
        }
        .exchanges {
            display: flex;
            flex-direction: column;
            gap: 20px;
        }
        .exchange {
            background: white;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            overflow: hidden;
        }
        .exchange-header {
            background: #ecf0f1;
            padding: 15px;
            display: flex;
            align-items: center;
            gap: 12px;
        }
        .exchange-number {
            font-weight: bold;
            color: #7f8c8d;
            font-family: monospace;
            min-width: 40px;
        }
        .method {
            padding: 6px 12px;
            border-radius: 4px;
            color: white;
            font-size: 12px;
            font-weight: 700;
            min-width: 60px;
            text-align: center;
        }
        .path {
            font-family: monospace;
            font-size: 14px;
            color: #2c3e50;
            flex: 1;
        }
        .status {
            font-weight: bold;
            font-family: monospace;
            font-size: 14px;
        }
        .exchange-body {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 0;
        }
        .section {
            padding: 20px;
            border-right: 1px solid #ecf0f1;
        }
        .section:last-child {
            border-right: none;
        }
        .headers {
            margin-bottom: 15px;
        }
        .header {
            font-size: 13px;
            padding: 4px 0;
            font-family: monospace;
        }
        .header-name {
            font-weight: 600;
            color: #7f8c8d;
        }
        .body-label {
            font-size: 12px;
            color: #7f8c8d;
            margin-bottom: 8px;
            text-transform: uppercase;
            letter-spacing: 0.5px;
        }
        .body {
            background: #2c3e50;
            color: #ecf0f1;
            padding: 15px;
            border-radius: 4px;
            font-size: 12px;
            line-height: 1.5;
            overflow-x: auto;
            margin: 0;
            font-family: 'Monaco', 'Menlo', monospace;
        }
    """.trimIndent()
}

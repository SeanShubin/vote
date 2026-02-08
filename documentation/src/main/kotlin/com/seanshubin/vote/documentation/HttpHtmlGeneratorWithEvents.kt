package com.seanshubin.vote.documentation

import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.EventEnvelope

/**
 * Generates http.html with events grouped by the HTTP calls that triggered them.
 * Each event from the scenario becomes a section header.
 */
class HttpHtmlGeneratorWithEvents(private val recorder: DocumentationRecorder) {
    fun generate(): String = buildString {
        val sections = EventGrouping.groupByEvents(recorder.getEntries())

        appendLine("<!DOCTYPE html>")
        appendLine("<html>")
        appendLine("<head>")
        appendLine("  <meta charset=\"UTF-8\">")
        appendLine("  <title>HTTP API with Events</title>")
        appendLine("  <style>")
        appendLine(css())
        appendLine("  </style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("  <h1>HTTP API with Events</h1>")
        appendLine("  <p class=\"description\">HTTP calls grouped by the events they generated.</p>")
        appendLine("  <p class=\"description\">Each event from the scenario summary becomes a section showing its HTTP calls.</p>")
        appendLine()

        appendLine("  <div class=\"stats\">")
        appendLine("    <div class=\"stat-box\">")
        appendLine("      <div class=\"stat-value\">${sections.sumOf { it.httpCalls.size }}</div>")
        appendLine("      <div class=\"stat-label\">HTTP Calls</div>")
        appendLine("    </div>")
        appendLine("    <div class=\"stat-box\">")
        appendLine("      <div class=\"stat-value\">${sections.size}</div>")
        appendLine("      <div class=\"stat-label\">Events</div>")
        appendLine("    </div>")
        appendLine("  </div>")
        appendLine()

        appendLine("  <div class=\"sections\">")
        for (section in sections) {
            appendLine(generateSection(section))
        }
        appendLine("  </div>")

        appendLine("</body>")
        appendLine("</html>")
    }

    private fun generateSection(section: EventGrouping.EventSection): String = buildString {
        val eventDescription = formatEvent(section.event.event)

        appendLine("    <div class=\"section\">")
        appendLine("      <h2>$eventDescription</h2>")

        // Show HTTP calls
        if (section.httpCalls.isNotEmpty()) {
            appendLine("      <div class=\"http-calls\">")
            for (exchange in section.httpCalls) {
                appendLine(generateHttpCall(exchange))
            }
            appendLine("      </div>")
        }

        // Show the event that resulted
        appendLine("      <div class=\"event-result\">")
        appendLine("        <div class=\"result-label\">→ Event Generated:</div>")
        appendLine(generateEventSummary(section.event))
        appendLine("      </div>")

        appendLine("    </div>")
    }

    private fun generateHttpCall(exchange: HttpExchange): String = buildString {
        val methodColor = getMethodColor(exchange.method)
        val statusColor = getStatusColor(exchange.responseStatus)

        appendLine("        <div class=\"http-call\">")
        appendLine("          <div class=\"http-header\">")
        appendLine("            <span class=\"method\" style=\"background-color: $methodColor;\">${exchange.method}</span>")
        appendLine("            <span class=\"path\">${escapeHtml(exchange.path)}</span>")
        appendLine("            <span class=\"status\" style=\"color: $statusColor;\">${exchange.responseStatus}</span>")
        appendLine("          </div>")

        appendLine("          <div class=\"http-details\">")
        if (exchange.requestBody != null && exchange.requestBody != "{}") {
            appendLine("            <div class=\"detail-section\">")
            appendLine("              <div class=\"detail-label\">Request</div>")
            appendLine("              <pre class=\"code\">${escapeHtml(exchange.requestBody)}</pre>")
            appendLine("            </div>")
        }
        appendLine("            <div class=\"detail-section\">")
        appendLine("              <div class=\"detail-label\">Response</div>")
        appendLine("              <pre class=\"code\">${escapeHtml(exchange.responseBody)}</pre>")
        appendLine("            </div>")
        appendLine("          </div>")

        appendLine("        </div>")
    }

    private fun generateEventSummary(envelope: EventEnvelope): String = buildString {
        appendLine("        <div class=\"event-summary\">")
        appendLine("          <span class=\"event-id\">#${envelope.eventId}</span>")
        appendLine("          <span class=\"event-type\">${envelope.event::class.simpleName}</span>")
        appendLine("          <span class=\"event-authority\">by ${envelope.authority}</span>")
        appendLine("        </div>")
    }

    private fun formatEvent(event: DomainEvent): String = when (event) {
        is DomainEvent.UserRegistered ->
            "<strong>${event.name}</strong> registered with role <strong>${event.role}</strong>"
        is DomainEvent.UserRoleChanged ->
            "<strong>${event.userName}</strong> role changed to <strong>${event.newRole}</strong>"
        is DomainEvent.UserPasswordChanged ->
            "<strong>${event.userName}</strong> changed password"
        is DomainEvent.UserEmailChanged ->
            "<strong>${event.userName}</strong> changed email to <strong>${event.newEmail}</strong>"
        is DomainEvent.UserNameChanged ->
            "<strong>${event.oldUserName}</strong> renamed to <strong>${event.newUserName}</strong>"
        is DomainEvent.UserRemoved ->
            "<strong>${event.userName}</strong> removed"
        is DomainEvent.ElectionCreated ->
            "Election <strong>${event.electionName}</strong> created by <strong>${event.ownerName}</strong>"
        is DomainEvent.ElectionUpdated -> {
            val changes = mutableListOf<String>()
            event.allowVote?.let { changes.add("voting ${if (it) "enabled" else "disabled"}") }
            event.allowEdit?.let { changes.add("editing ${if (it) "enabled" else "disabled"}") }
            event.secretBallot?.let { changes.add("secret ballot ${if (it) "enabled" else "disabled"}") }
            "Election <strong>${event.electionName}</strong> updated: ${changes.joinToString(", ")}"
        }
        is DomainEvent.ElectionDeleted ->
            "Election <strong>${event.electionName}</strong> deleted"
        is DomainEvent.CandidatesAdded ->
            "Added candidates to <strong>${event.electionName}</strong>: ${event.candidateNames.joinToString(", ")}"
        is DomainEvent.CandidatesRemoved ->
            "Removed candidates from <strong>${event.electionName}</strong>: ${event.candidateNames.joinToString(", ")}"
        is DomainEvent.VotersAdded ->
            "Added voters to <strong>${event.electionName}</strong>: ${event.voterNames.joinToString(", ")}"
        is DomainEvent.VotersRemoved ->
            "Removed voters from <strong>${event.electionName}</strong>: ${event.voterNames.joinToString(", ")}"
        is DomainEvent.BallotCast -> {
            val rankings = event.rankings.sortedBy { it.rank }.take(3)
                .joinToString(" > ") { "${it.candidateName}" }
            val more = if (event.rankings.size > 3) "..." else ""
            "<strong>${event.voterName}</strong> cast ballot in <strong>${event.electionName}</strong>: $rankings$more"
        }
        is DomainEvent.BallotTimestampUpdated ->
            "Ballot ${event.confirmation} timestamp updated"
        is DomainEvent.BallotRankingsChanged -> {
            val rankings = event.newRankings.sortedBy { it.rank }.take(3)
                .joinToString(" > ") { "${it.candidateName}" }
            val more = if (event.newRankings.size > 3) "..." else ""
            "Ballot rankings updated in <strong>${event.electionName}</strong>: $rankings$more"
        }
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

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
            max-width: 1400px;
            margin: 0 auto;
            padding: 20px;
            background-color: #f5f5f5;
        }
        h1 {
            color: #333;
            border-bottom: 3px solid #3498db;
            padding-bottom: 10px;
        }
        h2 {
            color: #2c3e50;
            font-size: 1.2em;
            margin: 0 0 15px 0;
        }
        .description {
            color: #666;
            font-size: 14px;
            margin-bottom: 10px;
        }
        .stats {
            display: flex;
            gap: 20px;
            margin: 30px 0;
        }
        .stat-box {
            background: white;
            padding: 20px;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            text-align: center;
            flex: 1;
        }
        .stat-value {
            font-size: 36px;
            font-weight: bold;
            color: #3498db;
        }
        .stat-label {
            font-size: 14px;
            color: #666;
            margin-top: 5px;
        }
        .sections {
            display: flex;
            flex-direction: column;
            gap: 25px;
        }
        .section {
            background: white;
            border-radius: 8px;
            padding: 20px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }
        .http-calls {
            margin: 15px 0;
        }
        .http-call {
            margin-bottom: 20px;
            background: #f8f9fa;
            border-radius: 6px;
            padding: 15px;
        }
        .http-header {
            display: flex;
            align-items: center;
            gap: 10px;
            margin-bottom: 10px;
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
            flex: 1;
        }
        .status {
            font-family: monospace;
            font-size: 14px;
            font-weight: bold;
        }
        .http-details {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 10px;
        }
        .detail-section {
            background: white;
            padding: 10px;
            border-radius: 4px;
        }
        .detail-label {
            font-size: 11px;
            color: #7f8c8d;
            text-transform: uppercase;
            letter-spacing: 0.5px;
            margin-bottom: 5px;
            font-weight: 600;
        }
        .code {
            background: #2c3e50;
            color: #ecf0f1;
            padding: 10px;
            border-radius: 4px;
            font-size: 11px;
            line-height: 1.4;
            overflow-x: auto;
            margin: 0;
            font-family: 'Monaco', 'Menlo', monospace;
        }
        .event-result {
            margin-top: 15px;
            padding: 12px;
            background: #e8f8f5;
            border-left: 4px solid #27ae60;
            border-radius: 4px;
        }
        .result-label {
            font-size: 12px;
            color: #27ae60;
            font-weight: 600;
            margin-bottom: 8px;
        }
        .event-summary {
            display: flex;
            align-items: center;
            gap: 10px;
        }
        .event-id {
            font-weight: bold;
            color: #27ae60;
            font-family: monospace;
        }
        .event-type {
            font-size: 13px;
            color: #555;
            font-weight: 600;
        }
        .event-authority {
            font-size: 12px;
            color: #999;
            font-style: italic;
            margin-left: auto;
        }
        strong {
            color: #2c3e50;
            font-weight: 600;
        }
    """.trimIndent()
}

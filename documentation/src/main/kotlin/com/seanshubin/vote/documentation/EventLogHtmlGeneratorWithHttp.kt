package com.seanshubin.vote.documentation

import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.EventEnvelope
import kotlinx.datetime.Instant

/**
 * Generates events.html with HTTP calls grouped by the events they generated.
 * Each event from the scenario becomes a section header.
 */
class EventLogHtmlGeneratorWithHttp(private val recorder: DocumentationRecorder) {
    fun generate(): String = buildString {
        val sections = EventGrouping.groupByEvents(recorder.getEntries())

        appendLine("<!DOCTYPE html>")
        appendLine("<html>")
        appendLine("<head>")
        appendLine("  <meta charset=\"UTF-8\">")
        appendLine("  <title>Event Log</title>")
        appendLine("  <style>")
        appendLine(css())
        appendLine("  </style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("  <h1>Event Log</h1>")
        appendLine("  <p class=\"description\">Domain events with full HTTP request/response details.</p>")
        appendLine("  <p class=\"description\">Each event shows the complete HTTP interactions that triggered it.</p>")
        appendLine()

        appendLine("  <div class=\"stats\">")
        appendLine("    <div class=\"stat-box\">")
        appendLine("      <div class=\"stat-value\">${sections.size}</div>")
        appendLine("      <div class=\"stat-label\">Events</div>")
        appendLine("    </div>")
        appendLine("    <div class=\"stat-box\">")
        appendLine("      <div class=\"stat-value\">${sections.sumOf { it.httpCalls.size }}</div>")
        appendLine("      <div class=\"stat-label\">HTTP Calls</div>")
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

        // Show HTTP calls that led to this event
        if (section.httpCalls.isNotEmpty()) {
            appendLine("      <div class=\"http-calls\">")
            for (exchange in section.httpCalls) {
                appendLine(generateHttpCall(exchange))
            }
            appendLine("      </div>")
        }

        // Show the event itself
        appendLine("      <div class=\"event-detail\">")
        appendLine(generateEventDetail(section.event))
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

    private fun generateEventDetail(envelope: EventEnvelope): String = buildString {
        appendLine("        <div class=\"event-header\">")
        appendLine("          <span class=\"event-id\">#${envelope.eventId}</span>")
        appendLine("          <span class=\"event-type\">${envelope.event::class.simpleName}</span>")
        appendLine("          <span class=\"event-authority\">by ${envelope.authority}</span>")
        appendLine("        </div>")
        appendLine("        <div class=\"event-data\">")
        appendLine(formatEventDetails(envelope.event))
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

    private fun formatEventDetails(event: DomainEvent): String = when (event) {
        is DomainEvent.UserRegistered -> """
            <div class="detail-row"><span class="label">Name:</span> ${event.name}</div>
            <div class="detail-row"><span class="label">Email:</span> ${event.email}</div>
            <div class="detail-row"><span class="label">Role:</span> ${event.role}</div>
        """.trimIndent()
        is DomainEvent.UserEmailChanged -> """
            <div class="detail-row"><span class="label">User:</span> ${event.userName}</div>
            <div class="detail-row"><span class="label">New Email:</span> ${event.newEmail}</div>
        """.trimIndent()
        is DomainEvent.UserNameChanged -> """
            <div class="detail-row"><span class="label">Old Name:</span> ${event.oldUserName}</div>
            <div class="detail-row"><span class="label">New Name:</span> ${event.newUserName}</div>
        """.trimIndent()
        is DomainEvent.UserPasswordChanged -> """
            <div class="detail-row"><span class="label">User:</span> ${event.userName}</div>
        """.trimIndent()
        is DomainEvent.UserRoleChanged -> """
            <div class="detail-row"><span class="label">User:</span> ${event.userName}</div>
            <div class="detail-row"><span class="label">New Role:</span> ${event.newRole}</div>
        """.trimIndent()
        is DomainEvent.UserRemoved -> """
            <div class="detail-row"><span class="label">User:</span> ${event.userName}</div>
        """.trimIndent()
        is DomainEvent.ElectionCreated -> """
            <div class="detail-row"><span class="label">Owner:</span> ${event.ownerName}</div>
            <div class="detail-row"><span class="label">Election:</span> ${event.electionName}</div>
        """.trimIndent()
        is DomainEvent.ElectionUpdated -> """
            <div class="detail-row"><span class="label">Election:</span> ${event.electionName}</div>
            ${event.allowVote?.let { "<div class=\"detail-row\"><span class=\"label\">Allow Vote:</span> $it</div>" } ?: ""}
            ${event.allowEdit?.let { "<div class=\"detail-row\"><span class=\"label\">Allow Edit:</span> $it</div>" } ?: ""}
            ${event.secretBallot?.let { "<div class=\"detail-row\"><span class=\"label\">Secret Ballot:</span> $it</div>" } ?: ""}
        """.trimIndent()
        is DomainEvent.ElectionDeleted -> """
            <div class="detail-row"><span class="label">Election:</span> ${event.electionName}</div>
        """.trimIndent()
        is DomainEvent.CandidatesAdded -> """
            <div class="detail-row"><span class="label">Election:</span> ${event.electionName}</div>
            <div class="detail-row"><span class="label">Candidates:</span> ${event.candidateNames.joinToString(", ")}</div>
        """.trimIndent()
        is DomainEvent.CandidatesRemoved -> """
            <div class="detail-row"><span class="label">Election:</span> ${event.electionName}</div>
            <div class="detail-row"><span class="label">Candidates:</span> ${event.candidateNames.joinToString(", ")}</div>
        """.trimIndent()
        is DomainEvent.VotersAdded -> """
            <div class="detail-row"><span class="label">Election:</span> ${event.electionName}</div>
            <div class="detail-row"><span class="label">Voters:</span> ${event.voterNames.joinToString(", ")}</div>
        """.trimIndent()
        is DomainEvent.VotersRemoved -> """
            <div class="detail-row"><span class="label">Election:</span> ${event.electionName}</div>
            <div class="detail-row"><span class="label">Voters:</span> ${event.voterNames.joinToString(", ")}</div>
        """.trimIndent()
        is DomainEvent.BallotCast -> """
            <div class="detail-row"><span class="label">Voter:</span> ${event.voterName}</div>
            <div class="detail-row"><span class="label">Election:</span> ${event.electionName}</div>
            <div class="detail-row"><span class="label">Rankings:</span> ${event.rankings.joinToString(", ") { "${it.candidateName}:${it.rank}" }}</div>
            <div class="detail-row"><span class="label">Confirmation:</span> ${event.confirmation}</div>
        """.trimIndent()
        is DomainEvent.BallotTimestampUpdated -> """
            <div class="detail-row"><span class="label">Confirmation:</span> ${event.confirmation}</div>
            <div class="detail-row"><span class="label">New Timestamp:</span> ${event.newWhenCast}</div>
        """.trimIndent()
        is DomainEvent.BallotRankingsChanged -> """
            <div class="detail-row"><span class="label">Confirmation:</span> ${event.confirmation}</div>
            <div class="detail-row"><span class="label">Election:</span> ${event.electionName}</div>
            <div class="detail-row"><span class="label">New Rankings:</span> ${event.newRankings.joinToString(", ") { "${it.candidateName}:${it.rank}" }}</div>
        """.trimIndent()
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
            max-width: 1200px;
            margin: 0 auto;
            padding: 20px;
            background-color: #f5f5f5;
        }
        h1 {
            color: #333;
            border-bottom: 3px solid #2ecc71;
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
            color: #2ecc71;
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
            padding-left: 20px;
            border-left: 3px solid #3498db;
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
        .event-detail {
            margin-top: 15px;
            padding: 15px;
            background: #e8f8f5;
            border-left: 4px solid #27ae60;
            border-radius: 4px;
        }
        .event-header {
            display: flex;
            align-items: center;
            gap: 10px;
            margin-bottom: 10px;
        }
        .event-id {
            font-weight: bold;
            color: #27ae60;
            font-family: monospace;
        }
        .event-type {
            font-size: 13px;
            color: #666;
            font-weight: 600;
        }
        .event-authority {
            font-size: 12px;
            color: #999;
            font-style: italic;
            margin-left: auto;
        }
        .event-data {
            font-size: 14px;
        }
        .detail-row {
            padding: 3px 0;
        }
        .label {
            font-weight: 600;
            color: #555;
            display: inline-block;
            min-width: 120px;
        }
        strong {
            color: #2c3e50;
            font-weight: 600;
        }
    """.trimIndent()
}

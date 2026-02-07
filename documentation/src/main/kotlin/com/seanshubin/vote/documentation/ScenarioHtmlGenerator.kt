package com.seanshubin.vote.documentation

import com.seanshubin.vote.contract.EventLog
import com.seanshubin.vote.domain.DomainEvent
import com.seanshubin.vote.domain.EventEnvelope

class ScenarioHtmlGenerator(private val eventLog: EventLog) {
    fun generate(): String = buildString {
        val events = eventLog.eventsToSync(0)

        appendLine("<!DOCTYPE html>")
        appendLine("<html>")
        appendLine("<head>")
        appendLine("  <meta charset=\"UTF-8\">")
        appendLine("  <title>Scenario Summary</title>")
        appendLine("  <style>")
        appendLine(css())
        appendLine("  </style>")
        appendLine("</head>")
        appendLine("<body>")
        appendLine("  <h1>Scenario Summary</h1>")
        appendLine("  <p class=\"description\">Comprehensive scenario showing all actions in the order they occurred.</p>")
        appendLine()

        // Summary statistics
        appendLine("  <div class=\"stats\">")
        val userEvents = events.count { it.event is DomainEvent.UserRegistered }
        val electionEvents = events.count { it.event is DomainEvent.ElectionCreated }
        val ballotEvents = events.count { it.event is DomainEvent.BallotCast }
        appendLine("    <div class=\"stat-box\">")
        appendLine("      <div class=\"stat-value\">$userEvents</div>")
        appendLine("      <div class=\"stat-label\">Users Registered</div>")
        appendLine("    </div>")
        appendLine("    <div class=\"stat-box\">")
        appendLine("      <div class=\"stat-value\">$electionEvents</div>")
        appendLine("      <div class=\"stat-label\">Elections Created</div>")
        appendLine("    </div>")
        appendLine("    <div class=\"stat-box\">")
        appendLine("      <div class=\"stat-value\">$ballotEvents</div>")
        appendLine("      <div class=\"stat-label\">Ballots Cast</div>")
        appendLine("    </div>")
        appendLine("    <div class=\"stat-box\">")
        appendLine("      <div class=\"stat-value\">${events.size}</div>")
        appendLine("      <div class=\"stat-label\">Total Events</div>")
        appendLine("    </div>")
        appendLine("  </div>")
        appendLine()

        // Display all events in chronological order
        appendLine("  <div class=\"section\">")
        appendLine("    <h2>Events</h2>")
        appendLine("    <ul class=\"event-list\">")
        for (envelope in events) {
            val description = formatEvent(envelope.event)
            val authority = if (envelope.authority == "system") "" else " <span class=\"authority\">(by ${envelope.authority})</span>"
            appendLine("      <li>$description$authority</li>")
        }
        appendLine("    </ul>")
        appendLine("  </div>")
        appendLine()

        appendLine("</body>")
        appendLine("</html>")
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
            "Election created by <strong>${event.ownerName}</strong>"

        is DomainEvent.ElectionUpdated -> {
            val changes = mutableListOf<String>()
            event.allowVote?.let { changes.add("voting ${if (it) "enabled" else "disabled"}") }
            event.allowEdit?.let { changes.add("editing ${if (it) "enabled" else "disabled"}") }
            event.secretBallot?.let { changes.add("secret ballot ${if (it) "enabled" else "disabled"}") }
            "Election updated: ${changes.joinToString(", ")}"
        }

        is DomainEvent.ElectionDeleted ->
            "Election deleted"

        is DomainEvent.CandidatesAdded ->
            "Added candidates: ${event.candidateNames.joinToString(", ") { "<strong>$it</strong>" }}"

        is DomainEvent.CandidatesRemoved ->
            "Removed candidates: ${event.candidateNames.joinToString(", ") { "<strong>$it</strong>" }}"

        is DomainEvent.VotersAdded ->
            "Added eligible voters: ${event.voterNames.joinToString(", ") { "<strong>$it</strong>" }}"

        is DomainEvent.VotersRemoved ->
            "Removed eligible voters: ${event.voterNames.joinToString(", ") { "<strong>$it</strong>" }}"

        is DomainEvent.BallotCast -> {
            val rankings = event.rankings.sortedBy { it.rank }.take(3)
                .joinToString(" > ") { "${it.candidateName} (#${it.rank})" }
            val more = if (event.rankings.size > 3) "..." else ""
            "<strong>${event.voterName}</strong> cast ballot: $rankings$more"
        }

        is DomainEvent.BallotTimestampUpdated ->
            "Ballot timestamp updated"

        is DomainEvent.BallotRankingsChanged -> {
            val rankings = event.newRankings.sortedBy { it.rank }.take(3)
                .joinToString(" > ") { "${it.candidateName} (#${it.rank})" }
            val more = if (event.newRankings.size > 3) "..." else ""
            "<strong>Ballot updated</strong>: $rankings$more"
        }
    }

    private fun css() = """
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            max-width: 1000px;
            margin: 0 auto;
            padding: 20px;
            background-color: #f5f5f5;
            line-height: 1.6;
        }
        h1 {
            color: #333;
            border-bottom: 3px solid #27ae60;
            padding-bottom: 10px;
        }
        h2 {
            color: #27ae60;
            margin-top: 30px;
            margin-bottom: 15px;
            font-size: 1.3em;
        }
        .description {
            color: #666;
            font-size: 14px;
            margin-bottom: 20px;
        }
        .stats {
            display: flex;
            gap: 15px;
            margin: 30px 0;
            flex-wrap: wrap;
        }
        .stat-box {
            background: white;
            padding: 20px;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            text-align: center;
            flex: 1;
            min-width: 120px;
        }
        .stat-value {
            font-size: 36px;
            font-weight: bold;
            color: #27ae60;
        }
        .stat-label {
            font-size: 13px;
            color: #666;
            margin-top: 5px;
        }
        .section {
            background: white;
            border-radius: 8px;
            padding: 25px;
            margin-bottom: 20px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }
        .event-list {
            list-style: none;
            padding: 0;
            margin: 0;
        }
        .event-list li {
            padding: 10px 0;
            border-bottom: 1px solid #eee;
        }
        .event-list li:last-child {
            border-bottom: none;
        }
        .event-list li:before {
            content: "→";
            color: #27ae60;
            font-weight: bold;
            margin-right: 10px;
        }
        .authority {
            color: #999;
            font-size: 0.9em;
            font-style: italic;
        }
        strong {
            color: #2c3e50;
            font-weight: 600;
        }
    """.trimIndent()
}
